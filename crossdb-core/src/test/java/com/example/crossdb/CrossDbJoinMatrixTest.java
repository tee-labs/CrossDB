package com.example.crossdb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全场景覆盖测试（第六批 C）：跨库 JOIN 矩阵。参考 Trino/Presto 跨 catalog 联邦、
 * ShardingSphere 分片自连接与 TPC-H 多表形态：等值键类型矩阵（int/string/
 * decimal/date/timestamp/boolean）、复合键、外连接族、半/反连接族、多表星型/
 * 链式、自连接与镜像分片、相关子查询/LATERAL、下推形态断言与非等值回退。
 * 与前几批互补、不重复；尚未支持的形态以 {@code @Disabled("待支持: ...")} 标注。
 */
class CrossDbJoinMatrixTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("credsdb", Fixtures.CREDS)
        .register("quotasdb", Fixtures.QUOTAS)
        .register("eventdb", Fixtures.EVENTS)
        .register("pingdb", Fixtures.PINGS)
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS)
        .register("logdb", Fixtures.LOGS);
  }

  private static List<String> rows(CrossDb db, String sql) throws SQLException {
    try (ResultSet rs = db.query(sql)) {
      List<String> out = new ArrayList<>();
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        List<String> cells = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
          Object v = rs.getObject(i);
          cells.add(v == null ? "NULL" : v.toString());
        }
        out.add(String.join(",", cells));
      }
      return out;
    }
  }

  // ---------- 等值键类型矩阵 ----------

  @Nested
  @DisplayName("等值键类型矩阵")
  class KeyTypes {

    @Test void intKeyInnerJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id ORDER BY o.id"));
      }
    }

    @Test void stringKeyCrossDb() throws Exception {
      // regions.name ↔ users.name（无交集 → 空结果也是正确性验证）
      try (CrossDb db = all()) {
        assertEquals(List.of(), rows(db,
            "SELECT u.id FROM userdb.users u JOIN regiondb.regions r ON r.name = u.name"));
      }
    }

    @Test void stringKeyWithOverlap() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,10", "bob,11"), rows(db,
            "SELECT TRIM(u.name), p.id FROM (VALUES ('alice'), ('bob')) u(name) "
                + "JOIN gooddb.products p ON p.name = TRIM(CASE u.name WHEN 'alice' THEN 'desk' ELSE 'chair' END) "
                + "ORDER BY p.id"));
      }
    }

    @Test void decimalKeyJoin() throws Exception {
      try (CrossDb db = all()) {
        // price(DECIMAL) ↔ amount(DECIMAL)：无相等值 → 空
        assertEquals(List.of(), rows(db,
            "SELECT p.id FROM gooddb.products p JOIN pingdb.pings x ON x.amount = p.price"));
      }
    }

    @Test void dateKeyJoinNowWorks() throws Exception {
      // products.made(DATE) ↔ shipments.shipped(DATE)：2026-01-15 无 shipment、无匹配
      try (CrossDb db = all()) {
        assertEquals(List.of(), rows(db,
            "SELECT p.id FROM gooddb.products p JOIN regiondb.shipments s ON s.shipped = p.made"));
      }
    }

    @Test void dateKeyJoinPositiveMatch() throws Exception {
      try (CrossDb db = all()) {
        // made+5 = 2026-01-20 = shipment 100；id=11 的 2026-02-25 无对应
        assertEquals(List.of("10,100"), rows(db,
            "SELECT p.id, s.order_id FROM gooddb.products p "
                + "JOIN regiondb.shipments s ON s.shipped = p.made + 5 "
                + "WHERE p.id < 12 ORDER BY p.id"));
      }
    }

    @Test void timestampKeyJoin() throws Exception {
      try (CrossDb db = all()) {
        // pings.ts(1) = 2026-01-02 03:04:05 与 logs(1) 相等
        assertEquals(List.of("1,1"), rows(db,
            "SELECT p.id, l.id FROM pingdb.pings p JOIN logdb.logs l ON l.ts = p.ts "
                + "ORDER BY p.id"));
      }
    }

    @Test void booleanKeyJoin() throws Exception {
      try (CrossDb db = all()) {
        // pings.flag ↔ products.active：TRUE↔TRUE（10,12）、FALSE↔FALSE（11）；NULL 不匹配
        assertEquals(List.of("1,10", "1,12", "3,11"), rows(db,
            "SELECT p.id, g.id FROM pingdb.pings p JOIN gooddb.products g ON g.active = p.flag "
                + "ORDER BY p.id, g.id"));
      }
    }

    @Test void intToStringKeyNeedsCast() throws Exception {
      try (CrossDb db = all()) {
        // CAST 后类型一致可 Bind Join
        assertEquals(List.of("100"), rows(db,
            "SELECT o.id FROM orderdb.orders o JOIN userdb.small s "
                + "ON CAST(o.user_id AS VARCHAR) = CAST(s.id AS VARCHAR) "
                + "WHERE o.id = 100"));
      }
    }
  }

  // ---------- 复合键 ----------

  @Nested
  @DisplayName("复合键连接")
  class CompositeKeys {

    @Test void compositeTwoColumnJoin() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20"), rows(db,
            "SELECT c.login, q.quota FROM credsdb.creds c JOIN quotasdb.quotas q "
                + "ON q.user_id = c.user_id AND q.tenant_id = c.tenant_id ORDER BY c.login"));
      }
    }

    @Test void compositeThreeColumnJoin() throws Exception {
      try (CrossDb db = all()) {
        // 三列复合键：user_id + tenant_id + 常量比对面额
        assertEquals(List.of("a1,10"), rows(db,
            "SELECT c.login, q.quota FROM credsdb.creds c JOIN quotasdb.quotas q "
                + "ON q.user_id = c.user_id AND q.tenant_id = c.tenant_id "
                + "AND q.quota = 10 ORDER BY c.login"));
      }
    }

    @Test void compositeKeyWithNullSideRow() throws Exception {
      try (CrossDb db = all()) {
        // pings(3) user_id NULL：复合键 (user_id, note) 永不匹配
        assertEquals(List.of("1,100"), rows(db,
            "SELECT p.id, o.id FROM pingdb.pings p JOIN orderdb.orders o "
                + "ON o.user_id = p.user_id AND o.amount = 10 ORDER BY p.id"));
      }
    }

    @Test void usingCompositeKey() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20"), rows(db,
            "SELECT login, quota FROM credsdb.creds c JOIN quotasdb.quotas q "
                + "USING (user_id, tenant_id) ORDER BY login"));
      }
    }
  }

  // ---------- 外连接族 ----------

  @Nested
  @DisplayName("外连接族")
  class OuterJoins {

    @Test void leftJoinNullPadding() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "alice,102", "bob,101", "bob,103", "carol,NULL"),
            rows(db, "SELECT u.name, o.id FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id ORDER BY u.id, o.id"));
      }
    }

    @Test void leftJoinFilterOnNullSide() throws Exception {
      // 过滤内表列 IS NULL = 反半连接
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db,
            "SELECT u.name FROM userdb.users u LEFT JOIN orderdb.orders o "
                + "ON o.user_id = u.id WHERE o.id IS NULL"));
      }
    }

    @Test void rightJoinColumnOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,alice", "101,bob", "102,alice", "103,bob", "NULL,carol"),
            rows(db, "SELECT o.id, u.name FROM userdb.users u "
                + "RIGHT JOIN orderdb.orders o ON o.user_id = u.id "
                + "UNION ALL SELECT NULL, u2.name FROM userdb.users u2 "
                + "WHERE u2.id = 3 ORDER BY 1 NULLS LAST"));
      }
    }

    @Test void fullJoinBothSidesUnmatched() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1,alice", "2,9,NULL", "NULL,NULL,bob", "NULL,NULL,carol"),
            rows(db, "SELECT e.id, e.user_id, u.name FROM eventdb.events e "
                + "FULL JOIN userdb.users u ON e.user_id = u.id "
                + "ORDER BY e.id NULLS LAST, u.id NULLS LAST"));
      }
    }

    @Test void fullJoinCompositeKey() throws Exception {
      try (CrossDb db = all()) {
        // creds(3,100,c1) 无 quota；quotas 全有主
        assertEquals(List.of("a1,10", "a2,5", "b1,20", "c1,NULL"), rows(db,
            "SELECT c.login, q.quota FROM credsdb.creds c "
                + "FULL JOIN quotasdb.quotas q ON q.user_id = c.user_id AND q.tenant_id = c.tenant_id "
                + "ORDER BY c.login NULLS LAST"));
      }
    }

    @Test void leftJoinAggregateOverNullGroup() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,15", "bob,21", "carol,NULL"), rows(db,
            "SELECT u.name, SUM(o.amount) FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void nestedLeftJoinsThreeTables() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100,pen", "alice,100,ink", "alice,102,ink",
            "alice,102,pen", "bob,101,pen", "bob,101,pad", "bob,103,pad",
            "bob,103,ink", "carol,NULL,NULL"), rows(db,
            "SELECT u.name, o.id, l.sku FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "LEFT JOIN orderdb.lineitems l ON l.order_id = o.id "
                + "ORDER BY u.id, o.id, l.id"));
      }
    }
  }

  // ---------- 半/反连接族 ----------

  @Nested
  @DisplayName("半连接与反连接族")
  class SemiAnti {

    @Test void existsWithExtraPredicate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("bob"), rows(db,
            "SELECT u.name FROM userdb.users u WHERE EXISTS (SELECT 1 FROM orderdb.orders o "
                + "WHERE o.user_id = u.id AND o.amount > 15)"));
      }
    }

    @Test void notExistsWithJoinInside() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"), rows(db,
            "SELECT u.name FROM userdb.users u WHERE NOT EXISTS (SELECT 1 FROM orderdb.orders o "
                + "JOIN orderdb.lineitems l ON l.order_id = o.id "
                + "WHERE o.user_id = u.id AND l.qty >= 5)"));
      }
    }

    @Test void inSubquerySimple() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT name FROM userdb.users WHERE id IN (SELECT user_id FROM orderdb.orders) "
                + "ORDER BY name"));
      }
    }

    @Test void notInSubqueryNoNulls() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db,
            "SELECT name FROM userdb.users WHERE id NOT IN "
                + "(SELECT user_id FROM orderdb.orders WHERE user_id IS NOT NULL)"));
      }
    }

    @Test void notInSubqueryWithNullIsEmpty() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of(), rows(db,
            "SELECT id FROM userdb.users WHERE id NOT IN (SELECT user_id FROM pingdb.pings)"));
      }
    }

    @Test void leftSemiJoinDialect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT name FROM userdb.users u LEFT SEMI JOIN orderdb.orders o "
                + "ON o.user_id = u.id ORDER BY name"));
      }
    }

    @Test void leftAntiJoinDialect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db,
            "SELECT name FROM userdb.users u LEFT ANTI JOIN orderdb.orders o "
                + "ON o.user_id = u.id"));
      }
    }

    @Test void doubleNegationAsSemi() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT name FROM userdb.users u WHERE NOT (NOT EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id)) ORDER BY name"));
      }
    }
  }

  // ---------- 多表连接 ----------

  @Nested
  @DisplayName("多表连接形态")
  class MultiWay {

    @Test void threeTableStar() throws Exception {
      try (CrossDb db = all()) {
        // users ↔ orders ↔ pings（三库）
        assertEquals(List.of("alice,100,a", "alice,102,a"), rows(db,
            "SELECT u.name, o.id, p.note FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN pingdb.pings p ON p.user_id = u.id "
                + "WHERE p.note IS NOT NULL ORDER BY o.id"));
      }
    }

    @Test void fourTableChain() throws Exception {
      try (CrossDb db = all()) {
        // users → orders → lineitems → 跨库（shipments 按 order_id）
        assertEquals(List.of("alice,100,pen,2026-01-20"), rows(db,
            "SELECT u.name, o.id, l.sku, CAST(s.shipped AS VARCHAR) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN orderdb.lineitems l ON l.order_id = o.id "
                + "JOIN regiondb.shipments s ON s.order_id = o.id "
                + "WHERE l.sku = 'pen' AND l.qty = 2"));
      }
    }

    @Test void twoCrossDbJoinsMixedSides() throws Exception {
      try (CrossDb db = all()) {
        // 中间表在第三个库：userdb → eventdb → orderdb（events 无 amount，构造键）
        assertEquals(List.of("alice,1,100"), rows(db,
            "SELECT u.name, e.id, o.id FROM userdb.users u "
                + "JOIN eventdb.events e ON e.user_id = u.id "
                + "JOIN orderdb.orders o ON o.user_id = u.id AND o.amount = 10 "
                + "WHERE e.id = 1"));
      }
    }

    @Test void joinThenAggregateThreeDb() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,2,15", "bob,2,21"), rows(db,
            "SELECT u.name, COUNT(o.id), SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id AND c.tenant_id = 100 "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void selfJoinSameDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,102", "101,103", "102,100", "103,101"), rows(db,
            "SELECT a.id, b.id FROM orderdb.orders a JOIN orderdb.orders b "
                + "ON a.user_id = b.user_id AND a.id <> b.id ORDER BY a.id, b.id"));
      }
    }

    @Test void mirrorShardJoinDateKey() throws Exception {
      // 镜像分片 + DATE 键（本轮修复回归）
      try (CrossDb db = all()) {
        assertEquals(List.of("10", "11"), rows(db,
            "SELECT a.id FROM gooddb.products a JOIN regiondb.products b "
                + "ON a.made = b.made WHERE a.price > 30 ORDER BY a.id"));
      }
    }

    @Test void mirrorShardCompositeAllTypes() throws Exception {
      // 全列 NATURAL JOIN（复合：id/region/name/price/made/active）
      try (CrossDb db = all()) {
        assertEquals(List.of("10", "11", "12"), rows(db,
            "SELECT a.id FROM gooddb.products a NATURAL JOIN regiondb.products b "
                + "WHERE a.price >= 25 ORDER BY a.id"));
      }
    }

    @Test void joinWithBothSideFilters() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,101"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE u.id = 2 AND o.amount > 10"));
      }
    }

    @Test void joinKeyExpressionOnDriverSide() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,101", "alice,103"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id + 1 WHERE u.id = 1"));
      }
    }
  }

  // ---------- 相关子查询与 LATERAL ----------

  @Nested
  @DisplayName("相关子查询与 LATERAL")
  class Correlated {

    @Test void scalarSubqueryInSelect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,15", "bob,21", "carol,0"), rows(db,
            "SELECT u.name, (SELECT COALESCE(SUM(o.amount), 0) FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) FROM userdb.users u ORDER BY u.id"));
      }
    }

    @Test void scalarSubqueryInWhere() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT id FROM orderdb.orders o WHERE amount > "
                + "(SELECT COALESCE(AVG(amount), 0) FROM orderdb.orders o2 "
                + "WHERE o2.user_id = o.user_id) ORDER BY id"));
      }
    }

    @Test void lateralJoinAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"), rows(db,
            "SELECT u.name, x.c FROM userdb.users u, LATERAL "
                + "(SELECT COUNT(*) c FROM orderdb.orders o WHERE o.user_id = u.id) x "
                + "ORDER BY u.id"));
      }
    }

    @Test void lateralWithLimit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101"), rows(db,
            "SELECT u.name, x.id FROM userdb.users u, LATERAL "
                + "(SELECT id FROM orderdb.orders o WHERE o.user_id = u.id "
                + "ORDER BY id LIMIT 1) x ORDER BY u.id"));
      }
    }

    @Test void correlatedInSelectList() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,2", "2,0", "3,0"), rows(db,
            "SELECT p.id, (SELECT COUNT(*) FROM logdb.logs l WHERE l.user_id = p.user_id) "
                + "FROM pingdb.pings p ORDER BY p.id"));
      }
    }

    @Test void existsAcrossThreeDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("100", "102"), rows(db,
            "SELECT o.id FROM orderdb.orders o WHERE EXISTS ("
                + "SELECT 1 FROM userdb.users u JOIN pingdb.pings p ON p.user_id = u.id "
                + "WHERE u.id = o.user_id AND p.amount IS NOT NULL) ORDER BY o.id"));
      }
    }
  }

  // ---------- 下推形态与回退 ----------

  @Nested
  @DisplayName("下推形态断言与非等值回退")
  class PushdownShapes {

    @Test void innerJoinUsesBindJoin() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Recording.dataSource(Fixtures.ORDERS, sqls, props))) {
        assertEquals(List.of("alice,100", "alice,102"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id WHERE u.id = 1 ORDER BY o.id"));
        assertTrue(sqls.stream().anyMatch(s -> s.contains("IN (?")),
            "内表应按键 IN 下推: " + sqls);
      }
    }

    @Test void driverFilterPropagatesToInner() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Recording.dataSource(Fixtures.ORDERS, sqls, props))) {
        rows(db, "SELECT u.name FROM userdb.users u JOIN orderdb.orders o "
            + "ON o.user_id = u.id WHERE u.id = 2");
        assertTrue(sqls.stream().anyMatch(s -> s.contains("USER_ID")),
            "驱动侧等值条件应传播到内表 SQL: " + sqls);
      }
    }

    @Test void nonEquiJoinFallsBackCorrectly() throws Exception {
      // 非等值（<）回退本地计划，结果仍正确
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,102", "bob,103"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id AND o.amount < u.id * 10 ORDER BY o.id"));
      }
    }

    @Test void residualOrConditionFallback() throws Exception {
      try (CrossDb db = core()) {
        // OR 混合跨侧条件：无纯等值对 → 本地计划
        assertEquals(List.of("alice,100"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON (o.user_id = u.id AND o.amount = 10) OR (o.id = 999) "
                + "ORDER BY o.id"));
      }
    }

    @Test void topNOverJoinPushesLimit() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Recording.dataSource(Fixtures.ORDERS, sqls, props))) {
        assertEquals(List.of("101,20", "100,10"), rows(db,
            "SELECT o.id, o.amount FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id ORDER BY o.amount DESC, o.id LIMIT 2"));
        assertTrue(sqls.stream().anyMatch(
            s -> s.contains("LIMIT") || s.contains("FETCH FIRST") || s.contains("FETCH NEXT")),
            "驱动侧应携带 LIMIT 下推: " + sqls);
      }
    }
  }
}

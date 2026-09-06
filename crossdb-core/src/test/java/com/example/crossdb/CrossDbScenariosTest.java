package com.example.crossdb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨库 SQL 场景覆盖测试。
 *
 * <p>用例参考同类系统的公开测试集设计：Apache Calcite（JdbcTest / SQL 兼容性用例）、
 * Presto・Trino（federated query）、Apache ShardingSphere（联邦查询集成用例）、
 * Vitess / MyCat（分片合并、NULL key 与批拆边界）等，按本引擎语义裁剪为
 * 共享 H2 内存夹具（Fixtures）。语义断言优先（结果正确性），少量用例额外断言下推形态。
 * 断言体保持标准 SQL 语义，不为缺陷行为放宽预期。
 */
class CrossDbScenariosTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb corePlusCreds() throws SQLException {
    return core().register("credsdb", Fixtures.CREDS);
  }

  private static CrossDb corePlusPings() throws SQLException {
    return core().register("pingdb", Fixtures.PINGS);
  }

  /** 全部行按「列 1,列 2,...」拼串，NULL 记为 NULL 字面量。 */
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

  /** 单行单列结果取字符串（NULL 记为 NULL 字面量）。 */
  private static String scalar(CrossDb db, String sql) throws SQLException {
    try (ResultSet rs = db.query(sql)) {
      assertTrue(rs.next(), "应至少返回一行: " + sql);
      Object v = rs.getObject(1);
      return v == null ? "NULL" : v.toString();
    }
  }

  // ---------- JOIN 家族（参考 Calcite JdbcTest / Presto federated） ----------

  @Nested
  @DisplayName("跨库 JOIN 场景")
  class Joins {

    @Test void innerJoinBasic() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"),
            rows(db, "SELECT u.name, o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id"));
      }
    }

    @Test void innerJoinSkipsNullKeys() throws Exception {
      // pings.user_id 含 NULL（p3）与无匹配 key（p2.user_id=9）：内连接下均不匹配
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1,alice"),
            rows(db, "SELECT p.id, u.name FROM pingdb.pings p "
                + "JOIN userdb.users u ON p.user_id = u.id ORDER BY p.id"));
      }
    }

    @Test void threeWayJoinAcrossThreeDbs() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of(
                "alice,100,a1", "alice,100,a2", "bob,101,b1",
                "alice,102,a1", "alice,102,a2", "bob,103,b1"),
            rows(db, "SELECT u.name, o.id, c.login FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id ORDER BY o.id, c.login"));
      }
    }

    @Test void threeWayJoinWithAggregation() throws Exception {
      // alice 2 单 × 2 登录 = 4 行，金额 (10+5)×2 = 30；bob 2 单 × 1 登录 = 2 行，(20+1)×1 = 21
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice,4,30", "bob,2,21"),
            rows(db, "SELECT u.name, COUNT(*), SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void leftJoinWithCoalesceProjection() throws Exception {
      // ORDER BY 用序数引用已选列（ORDER BY 引用未选列会触发输出泄漏缺陷，见回归用例）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "alice,102", "bob,101", "bob,103", "carol,-1"),
            rows(db, "SELECT u.name, COALESCE(o.id, -1) FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id ORDER BY 1, 2"));
      }
    }

    @Test void leftJoinWhereOnRightColumnTurnsInner() throws Exception {
      // 左连接后对右列过滤（> 6 排除 NULL）：等价内连接（Calcite 自动化简）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE o.amount > 6 ORDER BY u.name"));
      }
    }

    @Test void leftJoinCountIgnoresUnmatchedNulls() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice,1", "bob,0", "carol,0"),
            rows(db, "SELECT u.name, COUNT(p.id) FROM userdb.users u "
                + "LEFT JOIN pingdb.pings p ON p.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void rightJoinThenGroupByPreservedSide() throws Exception {
      try (CrossDb db = core().register("eventdb", Fixtures.EVENTS)) {
        assertEquals(List.of("1,1", "2,0"),
            rows(db, "SELECT e.id, COUNT(u.id) FROM userdb.users u "
                + "RIGHT JOIN eventdb.events e ON e.user_id = u.id "
                + "GROUP BY e.id ORDER BY e.id"));
      }
    }

    @Test void rightJoinWithLeftSideNullFilter() throws Exception {
      try (CrossDb db = core().register("eventdb", Fixtures.EVENTS)) {
        assertEquals(List.of("alice,1"),
            rows(db, "SELECT u.name, e.id FROM userdb.users u "
                + "RIGHT JOIN eventdb.events e ON e.user_id = u.id "
                + "WHERE u.name IS NOT NULL ORDER BY e.id"));
      }
    }

    @Test
    void fullJoinCountsAllMatchedAndUnmatched() throws Exception {
      try (CrossDb db = core().register("eventdb", Fixtures.EVENTS)) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "FULL JOIN eventdb.events e ON e.user_id = u.id"));
      }
    }

    @Test
    void sameTableJoinedUnderTwoSchemas() throws Exception {
      // 同一 DataSource 注册成两个 schema：按跨库语义执行（跨分片同构表场景）
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT a.name FROM userdb.users a "
                + "JOIN usermirror.users b ON a.id = b.id ORDER BY a.name"));
      }
    }

    @Test void sameDbSelfJoinStillPushedDown() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users a "
            + "JOIN userdb.users b ON a.id = b.id"));
      }
    }

    @Test
    void varcharKeyEquiJoin() throws Exception {
      // VARCHAR 等值 key：本夹具中登录名与用户名无交集，验证空结果正确
      try (CrossDb db = corePlusCreds()) {
        assertTrue(rows(db, "SELECT u.name FROM userdb.users u "
            + "JOIN credsdb.creds c ON c.login = u.name").isEmpty());
      }
    }

    @Test
    void expressionJoinKeyFallsBackButCorrect() throws Exception {
      // ON 两侧为表达式（非裸列对）：Bind Join 不改写，回退原生计划，结果须正确
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102", "103"),
            rows(db, "SELECT o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id + 0 = u.id ORDER BY o.id"));
      }
    }

    @Test void crossJoinWithWhereFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "CROSS JOIN orderdb.orders o WHERE o.amount > 15"));
      }
    }

    @Test void innerDerivedTableWithFilterAsInnerSide() throws Exception {
      // 内表带 WHERE：单输入算子链可整体下推，Bind Join 应仍生效（结果断言）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102"),
            rows(db, "SELECT u.name, d.id FROM userdb.users u "
                + "JOIN (SELECT id, user_id FROM orderdb.orders WHERE amount >= 5) d "
                + "ON d.user_id = u.id ORDER BY d.id"));
      }
    }

    @Test void innerDerivedTableWithSortAsInnerSide() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"),
            rows(db, "SELECT u.name, d.id FROM userdb.users u "
                + "JOIN (SELECT id, user_id FROM orderdb.orders ORDER BY id) d "
                + "ON d.user_id = u.id ORDER BY d.id"));
      }
    }

    @Test void innerDerivedTableWithAggregateAsInnerSide() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2"),
            rows(db, "SELECT u.name, d.n FROM userdb.users u "
                + "JOIN (SELECT user_id, COUNT(*) AS n FROM orderdb.orders GROUP BY user_id) d "
                + "ON d.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void pingsAsDriverLeftJoinUsersWithNullOuterKey() throws Exception {
      // NULL key 在外表侧：不与任何行匹配，LEFT JOIN 补 NULL（引擎级 NULL key 边界）
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1,alice", "2,NULL", "3,NULL"),
            rows(db, "SELECT p.id, u.name FROM pingdb.pings p "
                + "LEFT JOIN userdb.users u ON u.id = p.user_id ORDER BY p.id"));
      }
    }

    @Test void mixedSameDbAndCrossDbJoin() throws Exception {
      // 三库：users⋈small 同库下推 + users⋈orders 跨库 Bind Join
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT DISTINCT u.name FROM userdb.users u "
                + "JOIN userdb.small s ON s.id = u.id "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void emptyJoinResult() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 999"));
      }
    }
  }

  // ---------- 子查询（EXISTS/IN/标量，参考 Calcite / ShardingSphere） ----------

  @Nested
  @DisplayName("子查询场景")
  class Subqueries {

    @Test void inSubqueryCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT o.user_id FROM orderdb.orders o) ORDER BY u.name"));
      }
    }

    @Test void notInSubqueryCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id NOT IN "
                + "(SELECT o.user_id FROM orderdb.orders o) ORDER BY u.name"));
      }
    }

    @Test void existsWithResidualNonEquiCondition() throws Exception {
      // EXISTS 带残余非等值关联条件：Bind Join 不改写（回退），结果须正确
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT DISTINCT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND o.amount > 6) "
                + "ORDER BY u.name"));
      }
    }

    @Test void notExistsWithResidualNonEquiCondition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND o.amount > 6) "
                + "ORDER BY u.name"));
      }
    }

    @Test void semiDirectionInnerSideUsers() throws Exception {
      // 驱动侧为 events、内表侧为 users 的 SEMI 方向
      try (CrossDb db = core().register("eventdb", Fixtures.EVENTS)) {
        assertEquals(List.of("1"),
            rows(db, "SELECT e.id FROM eventdb.events e WHERE EXISTS "
                + "(SELECT 1 FROM userdb.users u WHERE u.id = e.user_id) ORDER BY e.id"));
      }
    }

    @Test void antiDirectionInnerSideUsers() throws Exception {
      try (CrossDb db = core().register("eventdb", Fixtures.EVENTS)) {
        assertEquals(List.of("2"),
            rows(db, "SELECT e.id FROM eventdb.events e WHERE NOT EXISTS "
                + "(SELECT 1 FROM userdb.users u WHERE u.id = e.user_id) ORDER BY e.id"));
      }
    }

    @Test void inLiteralList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"),
            rows(db, "SELECT name FROM userdb.users WHERE id IN (1, 3) ORDER BY 1"));
      }
    }

    @Test void uncorrelatedScalarSubqueryInWhere() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id = "
                + "(SELECT MAX(o.user_id) FROM orderdb.orders o)"));
      }
    }

    @Test void uncorrelatedScalarSubqueryInSelectList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,4", "bob,4", "carol,4"),
            rows(db, "SELECT u.name, (SELECT COUNT(*) FROM orderdb.orders) "
                + "FROM userdb.users u ORDER BY 1"));
      }
    }

    @Test void correlatedScalarSubqueryInSelectList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"),
            rows(db, "SELECT u.name, (SELECT COUNT(*) FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) FROM userdb.users u ORDER BY 1"));
      }
    }

    @Test void derivedTableInFromSingleDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT t.name FROM (SELECT id, name FROM userdb.users "
                + "WHERE id <= 2) t ORDER BY 1"));
      }
    }

    @Test void derivedTableWithAggregateJoinedAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,alice", "2,bob"),
            rows(db, "SELECT t.n, u.name FROM "
                + "(SELECT user_id, COUNT(*) AS n FROM orderdb.orders GROUP BY user_id) t "
                + "JOIN userdb.users u ON u.id = t.user_id ORDER BY u.name"));
      }
    }
  }

  // ---------- 聚合（参考 TPC-H 形态 / ShardingSphere 聚合用例） ----------

  @Nested
  @DisplayName("聚合场景")
  class Aggregates {

    @Test void globalAggregatesNoGroupBy() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("4,36,1,20"),
            rows(db, "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void avgOverJoinKey() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT AVG(amount) FROM orderdb.orders"))
            .compareTo(new BigDecimal("9")));
      }
    }

    @Test
    void groupByHavingOnCount() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2"),
            rows(db, "SELECT u.name, COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name HAVING COUNT(*) >= 2 ORDER BY u.name"));
      }
    }

    @Test void havingOnSum() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,21"),
            rows(db, "SELECT u.name, SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name HAVING SUM(o.amount) > 15 ORDER BY u.name"));
      }
    }

    @Test
    void countDistinctOverJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT COUNT(DISTINCT o.user_id) "
            + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id"));
      }
    }

    @Test
    void groupByTwoColumnsAfterCompositeBindJoin() throws Exception {
      try (CrossDb db = corePlusCreds().register("quotasdb", Fixtures.QUOTAS)) {
        assertEquals(List.of("100,1,1", "100,2,1", "200,1,1"),
            rows(db, "SELECT c.tenant_id, c.user_id, COUNT(*) FROM credsdb.creds c "
                + "JOIN quotasdb.quotas q ON c.user_id = q.user_id "
                + "AND c.tenant_id = q.tenant_id "
                + "GROUP BY c.tenant_id, c.user_id ORDER BY c.tenant_id, c.user_id"));
      }
    }

    @Test
    void groupByExpressionMod() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0,2", "1,2"),
            rows(db, "SELECT MOD(u.id, 2), COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY MOD(u.id, 2) ORDER BY MOD(u.id, 2)"));
      }
    }

    @Test void aggregateOfEmptyInput() throws Exception {
      // 空集聚合：COUNT 为 0，MIN 为 NULL（不是错误）
      try (CrossDb db = core()) {
        assertEquals(List.of("0,NULL"),
            rows(db, "SELECT COUNT(*), MIN(id) FROM userdb.users WHERE id > 999"));
      }
    }

    @Test void aggregateOverUnionAllBranches() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("7", scalar(db, "SELECT SUM(c) FROM "
            + "(SELECT COUNT(*) AS c FROM userdb.users "
            + "UNION ALL SELECT COUNT(*) FROM orderdb.orders) t"));
      }
    }

    @Test void countStarVsCountNullableColumn() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("3,2,2"),
            rows(db, "SELECT COUNT(*), COUNT(user_id), COUNT(note) FROM pingdb.pings"));
      }
    }
  }

  // ---------- 集合操作（参考 Presto/Trino federated set-op 用例） ----------

  @Nested
  @DisplayName("UNION / INTERSECT / EXCEPT 场景")
  class SetOperations {

    @Test void unionDistinctDedupAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM userdb.users UNION "
                + "SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void unionDistinctWithOrderByLimit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "102"),
            rows(db, "SELECT id FROM userdb.users UNION "
                + "SELECT id FROM orderdb.orders ORDER BY id DESC LIMIT 2"));
      }
    }

    @Test void intersectAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT id FROM userdb.users INTERSECT "
                + "SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void exceptAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users EXCEPT "
                + "SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void unionAllWithTagColumn() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,order", "101,order", "102,order", "103,order",
                "1,user", "2,user", "3,user"),
            rows(db, "SELECT id, 'order' AS src FROM orderdb.orders "
                + "UNION ALL SELECT id, 'user' FROM userdb.users ORDER BY src, id"));
      }
    }

    @Test void unionAllThreeBranchesWithTopN() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("103", "102", "101"),
            rows(db, "SELECT id FROM (SELECT id FROM userdb.users "
                + "UNION ALL SELECT id FROM orderdb.orders "
                + "UNION ALL SELECT user_id FROM credsdb.creds) t "
                + "ORDER BY id DESC LIMIT 3"));
      }
    }

    @Test void unionAllBranchesWithWhereEach() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3", "100", "101"),
            rows(db, "SELECT id FROM userdb.users WHERE id >= 2 "
                + "UNION ALL SELECT id FROM orderdb.orders WHERE amount >= 10 ORDER BY id"));
      }
    }

    @Test
    void joinInsideUnionAllBranch() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals("8", scalar(db, "SELECT COUNT(*) FROM "
            + "(SELECT u.id AS x FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id "
            + "UNION ALL SELECT user_id FROM credsdb.creds) t"));
      }
    }

    @Test void unionAllSameTableUnderTwoSchemas() throws Exception {
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals("6", scalar(db, "SELECT COUNT(*) FROM "
            + "(SELECT id FROM userdb.users UNION ALL SELECT id FROM usermirror.users) t"));
      }
    }
  }

  // ---------- 排序与分页（参考 MyCat/Vitess 分页用例） ----------

  @Nested
  @DisplayName("排序与分页场景")
  class OrderLimit {

    @Test void orderByMultipleColumnsOnJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,1", "bob,20", "alice,5", "alice,10"),
            rows(db, "SELECT u.name, o.amount FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY u.name DESC, o.amount"));
      }
    }

    @Test
    void orderByExpressionWithLimit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "102"),
            rows(db, "SELECT o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY -o.id LIMIT 2"));
      }
    }

    @Test
    void topNWithOffset() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("101", "102"),
            rows(db, "SELECT o.id FROM orderdb.orders o "
                + "JOIN userdb.users u ON o.user_id = u.id "
                + "ORDER BY o.id LIMIT 2 OFFSET 1"));
      }
    }

    @Test void topNOrderByInnerSideColumn() throws Exception {
      // ORDER BY 列属内表侧（非驱动侧）：Top-N 不下推、回退原生，结果须正确
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,103", "alice,102"),
            rows(db, "SELECT u.name, o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY o.id DESC LIMIT 2"));
      }
    }

    @Test void orderByAlias() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3", "2", "1"),
            rows(db, "SELECT id AS uid FROM userdb.users ORDER BY uid DESC"));
      }
    }

    @Test void limitZeroReturnsEmpty() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users LIMIT 0").isEmpty());
      }
    }

    @Test void offsetOnlyWithoutFetch() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id OFFSET 1 ROW"));
      }
    }

    @Test void shardTopNOverThreeBranchesWithOffset() throws Exception {
      // 分片 Top-N + OFFSET：每分支回 offset+fetch 行，本地归并裁剪（三分支）。
      // 全体 id 降序 103,102,101,100,3,3,2,2,1,1,1 → 跳过 2 行取 2 行
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("101", "100"),
            rows(db, "SELECT id FROM (SELECT id FROM userdb.users "
                + "UNION ALL SELECT id FROM orderdb.orders "
                + "UNION ALL SELECT user_id FROM credsdb.creds) t "
                + "ORDER BY id DESC LIMIT 2 OFFSET 2"));
      }
    }

    @Test
    void orderByNonSelectedColumnLeaksIntoOutput() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"),
            rows(db, "SELECT name FROM userdb.users WHERE id IN (1, 3) ORDER BY id"));
      }
    }
  }

  // ---------- CTE（参考 Calcite WITH 用例） ----------

  @Nested
  @DisplayName("CTE 场景")
  class Ctes {

    @Test void cteFilterThenCrossDbJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101"),
            rows(db, "WITH recent AS (SELECT id, user_id FROM orderdb.orders "
                + "WHERE amount >= 10) "
                + "SELECT u.name, r.id FROM userdb.users u "
                + "JOIN recent r ON r.user_id = u.id ORDER BY r.id"));
      }
    }

    @Test void twoCtesJoinedAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101"),
            rows(db, "WITH a AS (SELECT id, name FROM userdb.users WHERE id <= 2), "
                + "b AS (SELECT id, user_id FROM orderdb.orders WHERE amount >= 10) "
                + "SELECT a.name, b.id FROM a JOIN b ON b.user_id = a.id ORDER BY b.id"));
      }
    }

    @Test void cteWithAggregateJoinedCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,15", "bob,21"),
            rows(db, "WITH per_user AS (SELECT user_id, SUM(amount) AS s "
                + "FROM orderdb.orders GROUP BY user_id) "
                + "SELECT u.name, p.s FROM userdb.users u "
                + "JOIN per_user p ON p.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void nestedCte() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "WITH outer1 AS (WITH inner1 AS (SELECT id FROM userdb.users "
                + "WHERE id = 1) SELECT id FROM inner1) "
                + "SELECT u.name FROM userdb.users u JOIN outer1 o ON o.id = u.id"));
      }
    }
  }

  // ---------- 表达式与函数（参考 Calcite SQL 兼容用例 / H2 方言） ----------

  @Nested
  @DisplayName("表达式与函数场景")
  class Expressions {

    @Test void caseWhenInProjection() throws Exception {
      // 字面量分支推导为 CHAR(5)（取最大分支长度）：'big' 补空格为 'big  '，
      // 与 PostgreSQL bpchar 语义一致
      try (CrossDb db = core()) {
        assertEquals(List.of("100,big  ", "101,big  ", "102,small", "103,small"),
            rows(db, "SELECT o.id, CASE WHEN o.amount >= 10 THEN 'big' ELSE 'small' END "
                + "FROM orderdb.orders o ORDER BY o.id"));
      }
    }

    @Test void caseWhenWithLeftJoinNullCheck() throws Exception {
      // 同上：'has' 按 CHAR(4)（'none' 长度）补空格；DISTINCT 折叠 join 产生的重复行
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,has ", "bob,has ", "carol,none"),
            rows(db, "SELECT DISTINCT u.name, CASE WHEN o.id IS NULL THEN 'none' ELSE 'has' END "
                + "FROM userdb.users u LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY u.name"));
      }
    }

    @Test void upperAndLike() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ALICE,alice"),
            rows(db, "SELECT UPPER(name), LOWER(name) FROM userdb.users "
                + "WHERE name LIKE 'a%'"));
      }
    }

    @Test void substringFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("al"),
            rows(db, "SELECT SUBSTRING(name, 1, 2) FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void concatPipeOperator() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice!"),
            rows(db, "SELECT name || '!' FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void numericFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,1,2"),
            rows(db, "SELECT ABS(-5), MOD(7, 3), FLOOR(2.7) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void betweenFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users WHERE id BETWEEN 2 AND 3 ORDER BY 1"));
      }
    }

    @Test void arithmeticInWhereAndProjection() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("20", "30"),
            rows(db, "SELECT id * 10 FROM userdb.users WHERE id + 1 >= 3 ORDER BY 1"));
      }
    }

    @Test void isNullFilterOnLeftJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE o.id IS NULL ORDER BY u.name"));
      }
    }

    @Test void isNotNullFilterOnLeftJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT DISTINCT u.name FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE o.id IS NOT NULL ORDER BY u.name"));
      }
    }

    @Test void distinctProjection() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT DISTINCT user_id FROM orderdb.orders ORDER BY user_id"));
      }
    }

    @Test void selectStarOnCrossDbJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,alice,100,1,10"),
            rows(db, "SELECT * FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id ORDER BY o.id LIMIT 1"));
      }
    }

    @Test void quotedIdentifiers() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT `name` FROM `userdb`.`users` WHERE `id` = 1"));
      }
    }
  }

  // ---------- 边界与执行形态（参考 Vitess/ShardingSphere 分批与 NULL 边界） ----------

  @Nested
  @DisplayName("边界与执行形态场景")
  class EdgeCases {

    @Test
    void smallBatchSizeSplitsIntoMultipleInQueries() throws Exception {
      // batchSize=1：2 个 distinct key 应拆成 2 条 IN 下推（去重合批的另一侧边界）
      List<String> sqls = Collections.synchronizedList(new ArrayList<>());
      DataSource users = Recording.dataSource(Fixtures.USERS, sqls, new ArrayList<>());
      DataSource orders = Recording.dataSource(Fixtures.ORDERS, sqls, new ArrayList<>());
      try (CrossDb db = new CrossDb(1000, 1_000_000L, 1, 1)
          .register("userdb", users).register("orderdb", orders)) {
        assertEquals(List.of("alice", "alice", "bob", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY u.name"));
      }
      long inCount = sqls.stream().filter(s -> s.contains(" IN (")).count();
      assertEquals(2, inCount, "batchSize=1 时 2 个 key 应拆为 2 条 IN 查询: " + sqls);
    }

    @Test
    void analyzeOnComplexQuery() throws Exception {
      // 复合查询（JOIN + GROUP BY + LIMIT）的 analyze 报告可用性
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT u.name, COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id "
            + "GROUP BY u.name ORDER BY u.name LIMIT 2");
        assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
        assertTrue(report.contains("bindJoin"), report);
      }
    }

    @Test void explainOnSetOperation() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT id FROM userdb.users UNION "
            + "SELECT id FROM orderdb.orders ORDER BY id DESC LIMIT 2");
        assertTrue(plan.contains("EnumerableUnion") || plan.contains("Union"), plan);
      }
    }

    @Test void safeModeAllowsBindJoinButRejectsBareUnionScan() throws Exception {
      // safeMode：Bind Join 内表带 IN 过滤放行；UNION 分支裸拉取拦截
      try (CrossDb db = core().safeMode()) {
        SQLException e = org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders"));
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(root instanceof CrossDbUnsafeQueryException, "根因应是 safeMode 拦截: " + root);
      }
    }
  }

  // ---------- TPC-H 形态（参考 TPC-H 规范 / Calcite TpchQueryTest / Trino tpc-h 测试集） ----------

  @Nested
  @DisplayName("TPC-H 形态场景")
  class TpchShaped {

    /** Q1 形态：单表过滤 + 多聚合 + 排序。 */
    @Test void q1SingleTableMultiAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,6,1,5,3"),
            rows(db, "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount), AVG(amount) "
                + "FROM orderdb.orders WHERE amount <= 5"));
      }
    }

    /** Q3 形态：三表 JOIN + 分组聚合 + ORDER BY + LIMIT。 */
    @Test void q3ThreeWayJoinGroupLimit() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("bob,21", "alice,15"),
            rows(db, "SELECT u.name, SUM(o.amount) AS revenue FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id AND c.tenant_id = 100 "
                + "GROUP BY u.name ORDER BY revenue DESC"));
      }
    }

    /** Q4 形态：EXISTS 过滤 + GROUP BY。 */
    @Test
    void q4ExistsThenGroupBy() throws Exception {
      // EXISTS 只过滤用户行（每人一行）：COUNT(*) 按用户计，匹配者各计 1
      try (CrossDb db = core()) {
        assertEquals(List.of("1,1", "2,1"),
            rows(db, "SELECT u.id, COUNT(*) FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND o.amount > 5) "
                + "GROUP BY u.id ORDER BY u.id"));
      }
    }

    /** Q13 形态：LEFT JOIN + COUNT(右列) + 负向 LIKE 过滤。 */
    @Test void q13LeftJoinCountWithNegativeLike() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice,1", "bob,0", "carol,0"),
            rows(db, "SELECT u.name, COUNT(p.id) FROM userdb.users u "
                + "LEFT JOIN pingdb.pings p ON p.user_id = u.id "
                + "WHERE u.name NOT LIKE 'zz%' "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    /** Q15 形态：CTE 聚合 + 外层对聚合值再聚合（MAX over 聚合子查询）。 */
    @Test void q15CteAggregateThenMax() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("21", scalar(db, "WITH per_user AS (SELECT user_id, SUM(amount) AS s "
            + "FROM orderdb.orders GROUP BY user_id) "
            + "SELECT MAX(s) FROM per_user"));
      }
    }

    /** Q20 形态：嵌套 IN 子查询（IN 内含 IN）。 */
    @Test void q20NestedInSubqueries() throws Exception {
      // 订单金额 IN (1,2)（small 表）→ 订单 103 → 用户 bob
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT o.user_id FROM orderdb.orders o WHERE o.amount IN "
                + "(SELECT id FROM userdb.small)) "
                + "ORDER BY u.name"));
      }
    }

    /** Q21 形态：EXISTS 与 NOT EXISTS 组合关联过滤。 */
    @Test void q21ExistsAndNotExistsCombination() throws Exception {
      // 有 pings 且有小表记录的用户：alice(id=1) 有 ping；bob(id=2) 无 ping 被排除
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
                + "AND NOT EXISTS "
                + "(SELECT 1 FROM pingdb.pings p WHERE p.user_id = u.id AND p.amount IS NULL) "
                + "ORDER BY u.name"));
      }
    }

    /** Q22 形态：NOT IN + LIKE + 聚合子查询组合。 */
    @Test void q22NotInWithLikeAndAggregate() throws Exception {
      // name LIKE '%a%' → alice、carol；金额 >= AVG(9) 的订单用户 (1,2) 被排除 → carol
      try (CrossDb db = core()) {
        assertEquals(List.of("1,3"),
            rows(db, "SELECT COUNT(*), SUM(id) FROM userdb.users u "
                + "WHERE u.name LIKE '%a%' AND u.id NOT IN "
                + "(SELECT o.user_id FROM orderdb.orders o WHERE o.amount >= "
                + "(SELECT AVG(amount) FROM orderdb.orders))"));
      }
    }
  }

  // ---------- NULL 语义与三值逻辑（参考 PostgreSQL regression / Calcite SqlToConverterTest / Trino） ----------

  @Nested
  @DisplayName("NULL 语义与三值逻辑场景")
  class NullSemantics {

    @Test void nullComparisonFiltersOutRows() throws Exception {
      // 与 NULL 字面量比较恒为 UNKNOWN（不返回行）；`<>` 对 NULL key 行同样过滤
      try (CrossDb db = corePlusPings()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM pingdb.pings WHERE user_id = NULL"));
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings WHERE user_id <> 1"));
      }
    }

    @Test void threeValuedLogicInWhere() throws Exception {
      // 三值逻辑：NULL OR TRUE = TRUE（保留），NULL AND TRUE = UNKNOWN（过滤）
      try (CrossDb db = corePlusPings()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id IS NULL OR TRUE"));
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id IS NOT NULL AND TRUE"));
      }
    }

    @Test void notInWithNullInSubqueryYieldsEmpty() throws Exception {
      // 三值逻辑核心用例（PostgreSQL regress）：NOT IN 子查询含 NULL 时恒 UNKNOWN，应返回空集
      try (CrossDb db = corePlusPings()) {
        assertTrue(rows(db, "SELECT u.id FROM userdb.users u WHERE u.id NOT IN "
            + "(SELECT p.user_id FROM pingdb.pings p)").isEmpty(),
            "NOT IN 子查询含 NULL 应返回空集（三值逻辑）");
      }
    }

    @Test void inWithNullLiteralInList() throws Exception {
      // IN 列表含 NULL：只有真正相等的行匹配，NULL 不参与相等判定
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "102"),
            rows(db, "SELECT id FROM orderdb.orders WHERE user_id IN (1, NULL) ORDER BY id"));
      }
    }

    @Test void sumAndAvgOfAllNullIsNull() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("NULL", scalar(db, "SELECT SUM(amount) FROM pingdb.pings WHERE id = 2"));
        assertEquals("NULL", scalar(db, "SELECT AVG(amount) FROM pingdb.pings WHERE amount IS NULL"));
        // MIN/MAX 忽略 NULL
        assertEquals("1.25", scalar(db, "SELECT MIN(amount) FROM pingdb.pings"));
      }
    }

    @Test void coalesceAndNullif() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1.25", "0.00", "3.50"),
            rows(db, "SELECT COALESCE(amount, 0) FROM pingdb.pings ORDER BY id"));
        assertEquals("NULL", scalar(db, "SELECT NULLIF(1, 1) FROM userdb.small LIMIT 1"));
        // NULLIF(a,b)：a<>b 返回 a，a=b 返回 NULL
        assertEquals("2", scalar(db, "SELECT NULLIF(2, 1) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void orderByWithExplicitNullsFirstLast() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("NULL", "9", "1"),
            rows(db, "SELECT user_id FROM pingdb.pings ORDER BY user_id DESC NULLS FIRST"));
        assertEquals(List.of("1", "9", "NULL"),
            rows(db, "SELECT user_id FROM pingdb.pings ORDER BY user_id NULLS LAST"));
      }
    }

    @Test void caseWhenNullConditionTakesElse() throws Exception {
      // CASE 条件为 NULL 视为不匹配，走 ELSE 分支
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("has ", "has ", "none"),
            rows(db, "SELECT CASE WHEN user_id > 0 THEN 'has' ELSE 'none' END "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void distinctTreatsNullsAsEqual() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1", "9", "NULL"),
            rows(db, "SELECT DISTINCT user_id FROM pingdb.pings ORDER BY user_id NULLS LAST"));
      }
    }

    @Test void isDistinctFromNullPredicate() throws Exception {
      // IS DISTINCT FROM：NULL 安全比较（标准 SQL / Calcite / PostgreSQL 支持）
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id IS DISTINCT FROM 1"));
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id IS NOT DISTINCT FROM NULL"));
      }
    }
  }

  // ---------- 量化比较与复杂谓词（参考 MySQL 8.0 / PostgreSQL 文档用例） ----------

  @Nested
  @DisplayName("量化比较与复杂谓词场景")
  class QuantifiedPredicates {

    @Test void anyQuantifiedComparison() throws Exception {
      // = ANY 等价 IN
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id = ANY "
                + "(SELECT o.user_id FROM orderdb.orders o) ORDER BY u.name"));
      }
    }

    @Test void allQuantifiedComparison() throws Exception {
      // > ALL：大于子查询最大值（20）→ 无；> ALL(小表 id) → id > 2
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users WHERE id > ALL "
                + "(SELECT id FROM userdb.small) ORDER BY id"));
      }
    }

    @Test void betweenSymmetricSwapsBounds() throws Exception {
      // BETWEEN SYMMETRIC：边界自动交换（PostgreSQL/Calcite 支持）
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users WHERE id BETWEEN SYMMETRIC 3 AND 2 ORDER BY id"));
      }
    }

    @Test void notBetweenFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id FROM userdb.users WHERE id NOT BETWEEN 2 AND 99 ORDER BY id"));
      }
    }

    @Test void likeUnderscoreSingleChar() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT name FROM userdb.users WHERE name LIKE 'b_b'"));
      }
    }

    @Test void likeWithEscapeCharacter() throws Exception {
      // ESCAPE：'a\%' 为字面量 "a%"（无匹配），普通 'a%' 通配匹配 alice
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db, "SELECT name FROM userdb.users WHERE name LIKE 'a%'"));
        assertTrue(rows(db, "SELECT name FROM userdb.users WHERE name LIKE 'a\\%' ESCAPE '\\'").isEmpty());
      }
    }

    @Test void havingWithoutGroupBy() throws Exception {
      // 无 GROUP BY 的 HAVING：全表聚为一组再过滤（标准 SQL）
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM orderdb.orders HAVING COUNT(*) > 3"));
        assertTrue(rows(db, "SELECT COUNT(*) FROM orderdb.orders HAVING COUNT(*) > 99").isEmpty());
      }
    }

    @Test void scalarSubqueryComparisonWithAggregate() throws Exception {
      // 与聚合标量子查询比较：amount > AVG(amount)=9 → 100、101
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"),
            rows(db, "SELECT id FROM orderdb.orders WHERE amount > "
                + "(SELECT AVG(amount) FROM orderdb.orders) ORDER BY id"));
      }
    }
  }

  // ---------- 窗口函数（参考 Calcite WindowTest / Trino window 用例） ----------

  @Nested
  @DisplayName("窗口函数场景")
  class WindowFunctions {

    @Test void rowNumberOverOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103,1", "102,2", "101,3", "100,4"),
            rows(db, "SELECT id, ROW_NUMBER() OVER (ORDER BY id DESC) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void rankAndDenseRankOverJoin() throws Exception {
      // 窗口排序按 name（alice=1,1 / bob=3,2），输出按 o.id（窗口先算、外层再排序）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,1,1", "bob,3,2", "alice,1,1"),
            rows(db, "SELECT u.name, RANK() OVER (ORDER BY u.name), "
                + "DENSE_RANK() OVER (ORDER BY u.name) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id LIMIT 3"));
      }
    }
    @Test void runningSumOverPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,15", "2,21"),
            rows(db, "SELECT user_id, SUM(SUM(amount)) OVER (PARTITION BY user_id) "
                + "FROM orderdb.orders GROUP BY user_id ORDER BY user_id"));
      }
    }
  }

  // ---------- 类型与转换（参考 Calcite SqlOperatorsTest / JDBC 类型兼容用例） ----------

  @Nested
  @DisplayName("类型与转换场景")
  class TypesAndCasts {

    @Test void castIntToVarcharAndConcat() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("u1", "u2", "u3"),
            rows(db, "SELECT 'u' || CAST(id AS VARCHAR) FROM userdb.users ORDER BY id"));
      }
    }

    @Test void castVarcharToIntComparison() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("a1"),
            rows(db, "SELECT login FROM credsdb.creds WHERE CAST(tenant_id AS VARCHAR) = '100' "
                + "AND login = 'a1'"));
      }
    }

    @Test void implicitIntToDecimalPromotion() throws Exception {
      // INT 与 DECIMAL 混算：隐式提升（pings.amount 为 DECIMAL）
      try (CrossDb db = corePlusPings()) {
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT amount + 1 FROM pingdb.pings WHERE id = 1"))
            .compareTo(new BigDecimal("2.25")));
      }
    }

    @Test void integerDivisionAndModulo() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,1"),
            rows(db, "SELECT 7 / 3, MOD(7, 3) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void extractFromTimestamp() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("2026,2"),
            rows(db, "SELECT EXTRACT(YEAR FROM ts), EXTRACT(MONTH FROM ts) "
                + "FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void timestampComparisonFilter() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1", "3"),
            rows(db, "SELECT id FROM pingdb.pings WHERE ts >= TIMESTAMP '2026-01-01 00:00:00' "
                + "ORDER BY id"));
      }
    }

    @Test void stringFunctionsPositionTrimReplace() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,alic"),
            rows(db, "SELECT POSITION('li' IN name), TRIM(TRAILING 'e' FROM name) "
                + "FROM userdb.users WHERE id = 1"));
        assertEquals("abXde",
            scalar(db, "SELECT REPLACE('abcde', 'c', 'X') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void booleanColumnDirectFilter() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1"), rows(db, "SELECT id FROM pingdb.pings WHERE flag = TRUE"));
        assertEquals(List.of("3"), rows(db, "SELECT id FROM pingdb.pings WHERE flag = FALSE"));
        // IS [NOT] FALSE：NULL 不属于两者
        assertEquals(List.of("1", "3"),
            rows(db, "SELECT id FROM pingdb.pings WHERE flag IS NOT UNKNOWN ORDER BY id"));
      }
    }
  }

  // ---------- 分页深边界（参考 MyCat / Vitess 分页用例） ----------

  @Nested
  @DisplayName("分页深边界场景")
  class PagingEdges {

    @Test void offsetBeyondResultSizeReturnsEmpty() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users ORDER BY id LIMIT 5 OFFSET 10").isEmpty());
      }
    }

    @Test void limitLargerThanResultReturnsAll() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id LIMIT 999"));
      }
    }

    @Test void offsetEqualsResultSizeReturnsEmpty() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users ORDER BY id OFFSET 3").isEmpty());
      }
    }

    @Test void topNOverUnionWithNullKeys() throws Exception {
      // 集合操作 UNION 含 NULL 行 + Top-N 下推：显式 NULLS FIRST 消除引擎默认序歧义
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("9", "3"),
            rows(db, "SELECT user_id AS id FROM pingdb.pings "
                + "UNION ALL SELECT id FROM userdb.users "
                + "ORDER BY id DESC NULLS FIRST LIMIT 2 OFFSET 1"));
      }
    }

    @Test void topNBindJoinWithOrderByOuterExpression() throws Exception {
      // Top-N + Bind Join：ORDER BY 表达式（-o.id）位于驱动侧，下推后语义保持
      try (CrossDb db = core()) {
        assertEquals(List.of("102", "101", "100"),
            rows(db, "SELECT o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY -o.id LIMIT 3 OFFSET 1"));
      }
    }
  }

  // ---------- 高级窗口（参考 Trino window / Calcite WindowTest 用例） ----------

  @Nested
  @DisplayName("高级窗口函数场景")
  class AdvancedWindows {

    @Test void lagAndLeadOverOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL,101", "101,100,102", "102,101,103", "103,102,NULL"),
            rows(db, "SELECT id, LAG(id) OVER (ORDER BY id), LEAD(id) OVER (ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void partitionByWithOrderByRowNumber() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,10,1", "alice,5,2", "bob,20,1", "bob,1,2"),
            rows(db, "SELECT u.name, o.amount, "
                + "ROW_NUMBER() OVER (PARTITION BY u.name ORDER BY o.amount DESC) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY 1, 3"));
      }
    }

    @Test void firstValueOverPartitionByJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,100", "bob,101"),
            rows(db, "SELECT u.name, FIRST_VALUE(o.id) OVER "
                + "(PARTITION BY u.name ORDER BY o.id) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY o.id"));
      }
    }

    @Test void countOverPartitionOnJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100,2", "bob,101,2", "alice,102,2", "bob,103,2"),
            rows(db, "SELECT u.name, o.id, COUNT(*) OVER (PARTITION BY u.name) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY o.id"));
      }
    }

    @Test void globalSumOverWithoutPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,36", "101,36", "102,36", "103,36"),
            rows(db, "SELECT id, SUM(amount) OVER () FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void topNPerGroupViaRowNumberDerivedTable() throws Exception {
      // 窗口叠在跨库 Join 之上：派生表内 ROW_NUMBER 过滤每组 Top-1（经典 per-group TopN 形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101"),
            rows(db, "SELECT name, id FROM "
                + "(SELECT u.name, o.id, ROW_NUMBER() OVER "
                + "(PARTITION BY u.name ORDER BY o.amount DESC) AS rn "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id) t "
                + "WHERE rn = 1 ORDER BY name"));
      }
    }
  }

  // ---------- 分组扩展（参考 Calcite grouping 用例 / Trino GROUPING SETS） ----------

  @Nested
  @DisplayName("GROUPING SETS / ROLLUP / CUBE 场景")
  class GroupingExtensions {

    @Test void rollupAddsGrandTotalRow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "NULL,4"),
            rows(db, "SELECT u.name, COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY ROLLUP(u.name) ORDER BY u.name NULLS LAST"));
      }
    }

    @Test void groupingSetsTwoLevels() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("100,1,1", "100,2,1", "100,3,1", "100,NULL,3", "200,1,1", "200,NULL,1"),
            rows(db, "SELECT tenant_id, user_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY GROUPING SETS ((tenant_id, user_id), (tenant_id)) "
                + "ORDER BY tenant_id, user_id NULLS LAST"));
      }
    }

    @Test void cubeSingleColumn() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("100,3", "200,1", "NULL,4"),
            rows(db, "SELECT tenant_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY CUBE(tenant_id) ORDER BY tenant_id NULLS LAST"));
      }
    }
  }

  // ---------- TPC-H 补充形态（Q5/Q17/Q18 条件聚合与过滤聚合） ----------

  @Nested
  @DisplayName("TPC-H 补充形态场景")
  class TpchMoreShapes {

    /** Q5 形态：三表 JOIN + 维度过滤 + 按维度分组求和。 */
    @Test void q5ThreeWayJoinDimensionFilterSum() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("100,36"),
            rows(db, "SELECT c.tenant_id, SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id AND c.tenant_id = 100 "
                + "GROUP BY c.tenant_id"));
      }
    }

    /** Q17 形态：与聚合子查询（带系数）比较过滤。 */
    @Test void q17FilterAgainstScaledAverageSubquery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102"),
            rows(db, "SELECT u.name, o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE o.amount > 0.5 * (SELECT AVG(amount) FROM orderdb.orders) "
                + "ORDER BY o.id"));
      }
    }

    /** Q18 形态：IN 子查询 + GROUP BY + HAVING 聚合过滤组合。 */
    @Test void q18InGroupByHaving() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice,15", "bob,21"),
            rows(db, "SELECT u.name, SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE u.id IN (SELECT user_id FROM credsdb.creds WHERE tenant_id = 100) "
                + "GROUP BY u.name HAVING SUM(o.amount) > 5 ORDER BY u.name"));
      }
    }

    /** Q8 形态：条件聚合（CASE 计数占比形态）。 */
    @Test void q8ConditionalAggregateShare() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,4"),
            rows(db, "SELECT SUM(CASE WHEN o.amount >= 10 THEN 1 ELSE 0 END), COUNT(*) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id"));
      }
    }
  }

  // ---------- 集合操作链与 NULL 成员（参考 PostgreSQL regress 集合用例） ----------

  @Nested
  @DisplayName("集合操作链场景")
  class SetOpChains {

    @Test void unionExceptChainLeftAssociative() throws Exception {
      // 左结合：(users ∪ orders) EXCEPT users = {100..103}
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102", "103"),
            rows(db, "SELECT id FROM userdb.users UNION "
                + "SELECT id FROM orderdb.orders "
                + "EXCEPT SELECT id FROM userdb.users ORDER BY id"));
      }
    }

    @Test void intersectHandlesNullMembers() throws Exception {
      // INTERSECT DISTINCT 下 NULL 视为相等：pings{1,9,NULL} ∩ users{1,2,3} = {1}
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT user_id FROM pingdb.pings INTERSECT "
                + "SELECT id FROM userdb.users ORDER BY user_id"));
      }
    }

    @Test void exceptKeepsNullAndUnmatched() throws Exception {
      // pings EXCEPT users = {9, NULL}（NULL 归组相等保留）
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("NULL", "9"),
            rows(db, "SELECT user_id FROM pingdb.pings EXCEPT "
                + "SELECT id FROM userdb.users ORDER BY user_id NULLS FIRST"));
      }
    }

    @Test void threeWayUnionDistinct() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("1", "2", "3", "100", "101", "102", "103"),
            rows(db, "SELECT id FROM userdb.users UNION "
                + "SELECT id FROM orderdb.orders UNION "
                + "SELECT user_id FROM credsdb.creds ORDER BY id"));
      }
    }

    @Test void unionAllInsideCteJoinedBack() throws Exception {
      // 分片合并结果回流 JOIN 同库小表：分片 + 关联组合形态
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH allids AS "
            + "(SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders) "
            + "SELECT COUNT(*) FROM allids a JOIN userdb.small s ON s.id = a.id"));
      }
    }

    @Test void intersectDedupsDuplicatePairs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT user_id FROM orderdb.orders INTERSECT "
                + "SELECT id FROM userdb.small ORDER BY user_id"));
      }
    }
  }

  // ---------- VALUES / APPLY（参考 Calcite VALUES 与 SQL Server APPLY 形态） ----------

  @Nested
  @DisplayName("VALUES 与 LATERAL APPLY 场景")
  class ValuesAndApply {

    @Test void valuesTableJoinedCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN (VALUES (1), (3)) AS t(x) ON u.id = t.x ORDER BY u.name"));
      }
    }

    @Test
    void crossApplyCorrelatedMaxAmount() throws Exception {
      // CROSS APPLY = 相关派生表内连接语义：无订单的 carol 不出现
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,10", "bob,20"),
            rows(db, "SELECT u.name, d.max_amount FROM userdb.users u "
                + "CROSS APPLY (SELECT MAX(o.amount) AS max_amount FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d ORDER BY u.name"));
      }
    }

    @Test
    void outerApplyKeepsUnmatchedOuterRows() throws Exception {
      // OUTER APPLY = LEFT 相关派生表：carol 保留且补 NULL
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,10", "bob,20", "carol,NULL"),
            rows(db, "SELECT u.name, d.max_amount FROM userdb.users u "
                + "OUTER APPLY (SELECT MAX(o.amount) AS max_amount FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d ORDER BY u.name"));
      }
    }
  }

  // ---------- 空集 / 重复 key / 标量子查询边界（参考 Vitess 批处理边界用例） ----------

  @Nested
  @DisplayName("空集与标量子查询边界场景")
  class EmptyAndScalarEdges {

    @Test void emptyDriverSideJoinYieldsEmpty() throws Exception {
      // 驱动侧过滤后零行：Bind Join 以空 key 集合执行，不发 IN 查询、返回空
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id > 99"));
      }
    }

    @Test void emptyInnerDerivedTableJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN (SELECT user_id FROM orderdb.orders WHERE amount > 999) d "
            + "ON d.user_id = u.id"));
      }
    }

    @Test void inEmptySubqueryMatchesNothing() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE id IN "
            + "(SELECT id FROM userdb.users WHERE id > 99)"));
      }
    }

    @Test void notInEmptySubqueryMatchesAll() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE id NOT IN "
            + "(SELECT id FROM userdb.users WHERE id > 99)"));
      }
    }

    @Test void scalarSubqueryZeroRowsYieldsNullComparison() throws Exception {
      // 零行标量子查询 = NULL → 等值比较恒 UNKNOWN → 空集
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE id = "
            + "(SELECT MAX(id) FROM userdb.users WHERE id > 99)"));
      }
    }

    @Test
    void scalarSubqueryMultipleRowsRejected() throws Exception {
      // 多行标量子查询应为错误（标准 SQL 行为）
      try (CrossDb db = core()) {
        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users WHERE id = "
                + "(SELECT user_id FROM orderdb.orders)"));
      }
    }

    @Test void semiWithDuplicateDriverKeys() throws Exception {
      // 驱动侧存在重复 key（orders.user_id 各 2 次）：SEMI 匹配计数不受重复影响
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM orderdb.orders o WHERE EXISTS "
            + "(SELECT 1 FROM userdb.users u WHERE u.id = o.user_id)"));
      }
    }

    @Test void antiWithDuplicateDriverKeys() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM orderdb.orders o WHERE NOT EXISTS "
            + "(SELECT 1 FROM userdb.users u WHERE u.id = o.user_id)"));
      }
    }
  }

  // ---------- 表达式补充（参考 Calcite SqlOperatorsTest / PostgreSQL 函数用例） ----------

  @Nested
  @DisplayName("表达式补充场景")
  class MoreExpressions {

    @Test
    void similarToPredicate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT name FROM userdb.users WHERE name SIMILAR TO 'a%'"));
      }
    }

    @Test void nullConcatPropagatesNull() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("NULL",
            scalar(db, "SELECT note || 'x' FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void roundCeilFloorSign() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,2,1,-1"),
            rows(db, "SELECT ROUND(2.5), CEIL(1.2), FLOOR(1.8), SIGN(-3) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void powerSqrtAbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("8.0,4.0,5"),
            rows(db, "SELECT POWER(2, 3), SQRT(16.0), ABS(-5) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void intervalAdditionFilter() throws Exception {
      // ts + INTERVAL '1' DAY：id1 恰好到达边界、id3 超过、id2 为 NULL → 2 行
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE ts + INTERVAL '1' DAY >= TIMESTAMP '2026-01-03 03:04:05'"));
      }
    }

    @Test void timestampDiffMonths() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("1", scalar(db, "SELECT TIMESTAMPDIFF(MONTH, "
            + "(SELECT ts FROM pingdb.pings WHERE id = 1), "
            + "(SELECT ts FROM pingdb.pings WHERE id = 3))"));
      }
    }

    @Test void coalesceMultiArgFallbackChain() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("a", "b", "none"),
            rows(db, "SELECT COALESCE(note, CAST(user_id AS VARCHAR), 'none') "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }
  }

  // ---------- 分组补充边界（参考 PostgreSQL regress 分组用例） ----------

  @Nested
  @DisplayName("分组补充边界场景")
  class GroupEdges {

    @Test void nullGroupKeyFormsOwnGroup() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1,1", "9,1", "NULL,1"),
            rows(db, "SELECT user_id, COUNT(*) FROM pingdb.pings "
                + "GROUP BY user_id ORDER BY user_id NULLS LAST"));
      }
    }

    @Test void havingReferencesGroupKey() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,2"),
            rows(db, "SELECT u.name, COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name HAVING u.name <> 'alice' ORDER BY u.name"));
      }
    }

    @Test void orderByAggregateOrdinalDesc() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,21", "alice,15"),
            rows(db, "SELECT u.name, SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY 2 DESC"));
      }
    }

    @Test void groupByUnselectedColumn() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1", "1", "1"),
            rows(db, "SELECT COUNT(*) FROM pingdb.pings "
                + "GROUP BY user_id ORDER BY user_id NULLS LAST"));
      }
    }

    @Test
    void countDistinctMultipleColumns() throws Exception {
      // 多列 COUNT DISTINCT：(100,1),(100,2),(200,1),(100,3) 共 4 组
      try (CrossDb db = corePlusCreds()) {
        assertEquals("4", scalar(db,
            "SELECT COUNT(DISTINCT tenant_id, user_id) FROM credsdb.creds"));
      }
    }

    @Test void maxOfStringAfterJoin() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice,a2", "bob,b1", "carol,c1"),
            rows(db, "SELECT u.name, MAX(c.login) FROM userdb.users u "
                + "JOIN credsdb.creds c ON c.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }
  }

  // ---------- 相关派生表 APPLY / LATERAL（参考 SQL Server APPLY / PostgreSQL LATERAL 用例） ----------

  @Nested
  @DisplayName("APPLY 与 LATERAL 扩展场景")
  class ApplyAndLateral {

    @Test void crossApplyCorrelatedCount() throws Exception {
      // 相关 COUNT：alice/orders 2 行、bob 2 行，carol 无订单不出现（内连接语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2"),
            rows(db, "SELECT u.name, d.n FROM userdb.users u "
                + "CROSS APPLY (SELECT COUNT(*) AS n FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d ORDER BY u.name"));
      }
    }

    @Test void outerApplyCountZeroFill() throws Exception {
      // OUTER APPLY + COUNT：carol 保留且相关计数为 0（空集聚合语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"),
            rows(db, "SELECT u.name, d.n FROM userdb.users u "
                + "OUTER APPLY (SELECT COUNT(*) AS n FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d ORDER BY u.name"));
      }
    }

    @Test void crossApplyFilterOnOutputColumn() throws Exception {
      // 对 APPLY 输出列再过滤：alice(10)、bob(20) 满足 m>=10，carol 被内连接排除
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "CROSS APPLY (SELECT MAX(o.amount) AS m FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d WHERE d.m >= 10 ORDER BY u.name"));
      }
    }

    @Test void crossApplyThenOuterAggregate() throws Exception {
      // APPLY 输出再聚合：alice/bob 相关计数均为 2，GROUP BY 计数得 2 组
      try (CrossDb db = core()) {
        assertEquals(List.of("2,2"),
            rows(db, "SELECT d.n, COUNT(*) FROM userdb.users u "
                + "CROSS APPLY (SELECT COUNT(*) AS n FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d GROUP BY d.n"));
      }
    }

    @Test void outerApplyOrderByOutputDesc() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,20", "alice,10"),
            rows(db, "SELECT u.name, d.m FROM userdb.users u "
                + "CROSS APPLY (SELECT MAX(o.amount) AS m FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) d ORDER BY d.m DESC, u.name"));
      }
    }

    @Test void lateralJoinCorrelatedCount() throws Exception {
      // LATERAL 等价 CROSS APPLY ON TRUE 形态
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2"),
            rows(db, "SELECT u.name, d.n FROM userdb.users u JOIN LATERAL "
                + "(SELECT COUNT(*) AS n FROM orderdb.orders o WHERE o.user_id = u.id) d "
                + "ON TRUE ORDER BY u.name"));
      }
    }

    @Test void analyzeOnApplyShowsBindJoin() throws Exception {
      // APPLY 去相关后走 Bind Join 批内 IN 下推（引擎可见性）
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT u.name, d.m FROM userdb.users u "
            + "CROSS APPLY (SELECT MAX(o.amount) AS m FROM orderdb.orders o "
            + "WHERE o.user_id = u.id) d");
        assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
        assertTrue(report.contains("bindJoin"), report);
      }
    }
  }

  // ---------- SEMI/ANTI 深组合（参考 Calcite EXISTS 用例 / Vitess 半连接用例） ----------

  @Nested
  @DisplayName("SEMI/ANTI 深组合场景")
  class SemiAntiCombinations {

    @Test void antiThenGroupBy() throws Exception {
      // ANTI 后 GROUP BY：仅 carol（id=3）无订单，组内 1 行
      try (CrossDb db = core()) {
        assertEquals(List.of("3,1"),
            rows(db, "SELECT u.id, COUNT(*) FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
                + "GROUP BY u.id ORDER BY u.id"));
      }
    }

    @Test void semiWithResidualFilterThenGroupByName() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,1", "bob,1"),
            rows(db, "SELECT u.name, COUNT(*) FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND o.amount > 5) "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void existsWithOrOverTopLevelPredicate() throws Exception {
      // EXISTS 与顶层谓词 OR：Bind Join 不改写（回退原生），结果须正确
      try (CrossDb db = core()) {
        assertEquals(List.of("bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND o.amount > 15) "
                + "OR u.id = 3 ORDER BY u.name"));
      }
    }

    @Test void nestedExistsAcrossThreeDbs() throws Exception {
      // 两层相关 EXISTS（users←orders←creds）：有订单且订单用户有凭证 → alice、bob
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id AND EXISTS "
                + "(SELECT 1 FROM credsdb.creds c WHERE c.user_id = o.user_id)) "
                + "ORDER BY u.name"));
      }
    }

    @Test void semiWithJoinInsideSubquery() throws Exception {
      // 内子查询含同库 JOIN（orders⋈small）：金额 ∈ {1,2} 的订单 103 → bob
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o JOIN userdb.small s ON s.id = o.amount "
                + "WHERE o.user_id = u.id) ORDER BY u.name"));
      }
    }

    @Test void semiThenLeftJoinComposition() throws Exception {
      // SEMI 过滤后再 LEFT JOIN：alice/bob 各补 pings，carol 被 SEMI 排除
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice,1", "bob,NULL"),
            rows(db, "SELECT u.name, p.id FROM userdb.users u "
                + "LEFT JOIN pingdb.pings p ON p.user_id = u.id WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
                + "ORDER BY u.name, p.id"));
      }
    }
  }

  // ---------- 聚合扩展（参考 MySQL/PostgreSQL 聚合用例） ----------

  @Nested
  @DisplayName("聚合扩展场景")
  class AggregateExtensions {

    @Test void countDistinctCompositeWithGroupBy() throws Exception {
      // 多列 COUNT DISTINCT + 分组：tenant 100 → (1,a1)(2,b1)(1,a2) 3 组；200 → 1 组
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("100,3", "200,1"),
            rows(db, "SELECT tenant_id, COUNT(DISTINCT user_id, login) FROM credsdb.creds "
                + "GROUP BY tenant_id ORDER BY tenant_id"));
      }
    }

    @Test void countDistinctCompositeWithOtherAggregate() throws Exception {
      // 多列 COUNT DISTINCT 与普通聚合混用：4 组 distinct，SUM(user_id)=1+2+1+3=7
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("4,7"),
            rows(db, "SELECT COUNT(DISTINCT user_id, login), SUM(user_id) FROM credsdb.creds"));
      }
    }

    @Test void countDistinctTwoCompositeSets() throws Exception {
      // 两个不同的多列去重集合：(user_id,login) 4 组；(tenant_id,user_id) 4 组
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("4,4"),
            rows(db, "SELECT COUNT(DISTINCT user_id, login), "
                + "COUNT(DISTINCT tenant_id, user_id) FROM credsdb.creds"));
      }
    }

    @Test void avgDistinct() throws Exception {
      // AVG(DISTINCT)：user_id 去重 {1,2} → SUM/COUNT = 3/2；Calcite 整数 AVG 按整除
      // 返回 1（引擎一致性语义，非 PostgreSQL 的 numeric 1.5）
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "SELECT AVG(DISTINCT user_id) FROM orderdb.orders"));
      }
    }

    @Test void avgCaseWhenRatio() throws Exception {
      // 条件平均（占比形态）：金额 >= 10 的订单占比 = 2/4 = 0.5
      try (CrossDb db = core()) {
        assertEquals("0.5", scalar(db,
            "SELECT AVG(CASE WHEN amount >= 10 THEN 1.0 ELSE 0.0 END) FROM orderdb.orders"));
      }
    }

    @Test void bitAggregates() throws Exception {
      // 位聚合：AND(100,101,102,103)=100，OR=103（二进制 ALL-ONES）
      try (CrossDb db = core()) {
        assertEquals(List.of("100,103"),
            rows(db, "SELECT BIT_AND(id), BIT_OR(id) FROM orderdb.orders"));
      }
    }

    @Test void havingWithScalarSubquery() throws Exception {
      // HAVING 与标量子查询比较：最大分组计数为 2，alice/bob 各 2
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2"),
            rows(db, "SELECT u.name, COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name HAVING COUNT(*) = "
                + "(SELECT MAX(c) FROM (SELECT COUNT(*) AS c FROM orderdb.orders "
                + "GROUP BY user_id) t) ORDER BY u.name"));
      }
    }

    @Test void expressionOnBothSidesOfIn() throws Exception {
      // IN 左侧与子查询输出均为表达式：tenant-99=1 匹配用户 1
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id FROM userdb.users WHERE id + 0 IN "
                + "(SELECT tenant_id - 99 FROM credsdb.creds WHERE login = 'a1') ORDER BY id"));
      }
    }
  }

  // ---------- 集合操作扩展（参考 PostgreSQL regress 集合用例） ----------

  @Nested
  @DisplayName("集合操作扩展场景")
  class SetOpExtensions {

    @Test void exceptAllMultisetSemantics() throws Exception {
      // EXCEPT ALL 多重集语义：users{1,2,3} 减 orders{1,2,1,2}（按出现次数）→ {3}
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users EXCEPT ALL "
                + "SELECT user_id FROM orderdb.orders ORDER BY 1"));
      }
    }

    @Test void exceptAllCountsDuplicates() throws Exception {
      // 左侧重复行按次数抵消：{1,2,3,1,2,3} − {1,2,1,2} → {3,3}
      try (CrossDb db = core()) {
        assertEquals(List.of("3", "3"),
            rows(db, "SELECT id FROM userdb.users UNION ALL SELECT id FROM userdb.users "
                + "EXCEPT ALL SELECT user_id FROM orderdb.orders ORDER BY 1"));
      }
    }

    @Test void exceptAllSelfCancellation() throws Exception {
      // 自身 EXCEPT ALL 完全抵消为空
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users EXCEPT ALL "
            + "SELECT id FROM userdb.users ORDER BY 1").isEmpty());
      }
    }

    @Test void intersectThreeWayChain() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT id FROM userdb.users INTERSECT "
                + "SELECT id FROM userdb.users INTERSECT "
                + "SELECT user_id FROM orderdb.orders ORDER BY 1"));
      }
    }

    @Test void unionAllBooleanWithNulls() throws Exception {
      // 布尔列与 NULL 的 UNION ALL：类型一致、NULL 原样保留
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("false", "false", "true", "true", "NULL", "NULL"),
            rows(db, "SELECT flag FROM pingdb.pings UNION ALL "
                + "SELECT flag FROM pingdb.pings ORDER BY 1 NULLS LAST"));
      }
    }
  }

  // ---------- 语法兼容（LENIENT conformance 放行形态，参考 SQL Server/MySQL 方言用例） ----------

  @Nested
  @DisplayName("语法兼容场景")
  class ParserCompat {

    @Test void notSimilarToPredicate() throws Exception {
      // NOT SIMILAR TO：整串匹配 'a|b%'（'a' 或 'b%'）排除 alice/bob，保留 carol
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"),
            rows(db, "SELECT name FROM userdb.users WHERE name NOT SIMILAR TO 'a|b%' "
                + "ORDER BY id"));
      }
    }

    @Test void similarToWithEscapeClause() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT name FROM userdb.users WHERE name SIMILAR TO 'a%' ESCAPE '\\'"));
      }
    }

    @Test void bangEqualOperator() throws Exception {
      // LENIENT conformance 放行 != （MySQL 方言）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "3"),
            rows(db, "SELECT id FROM userdb.users WHERE id != 2 ORDER BY id"));
      }
    }

    @Test void limitStartCountSyntax() throws Exception {
      // MySQL 风格 LIMIT start, count
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id LIMIT 1, 2"));
      }
    }

    @Test void coalesceInJoinKey() throws Exception {
      // join key 含 COALESCE：p2(9) 无用户 9、p3(NULL→9) 亦无匹配，仅 p1 命中
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT p.id FROM pingdb.pings p JOIN userdb.users u "
                + "ON u.id = COALESCE(p.user_id, 9) ORDER BY p.id"));
      }
    }
  }
}

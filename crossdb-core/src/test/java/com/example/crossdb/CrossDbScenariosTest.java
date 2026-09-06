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
}

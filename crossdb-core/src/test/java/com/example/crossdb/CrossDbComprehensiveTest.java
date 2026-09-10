package com.example.crossdb;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量场景补齐测试（一次性覆盖跨库 SQL 各类场景，参考同类系统公开用例设计，
 * 与既有五组测试互补、不重复）。
 *
 * <p>用例来源参考：PostgreSQL regression（RANGE 数值帧、行构造器不等比较、
 * EXTRACT 季度/年积日、对称差）、Apache Calcite（SqlOperatorsTest / JdbcTest /
 * WINDOW 子句 / VALUES）、Presto・Trino federated（QUALIFY、分支内聚合 UNION、
 * 集合操作链）、MySQL 8.0（REGEXP、DECODE・TIMESTAMPDIFF 单位语义）、
 * SQL Server（多级括号集合优先级）、Oracle（LISTAGG ON OVERFLOW、NULL=NULL
 * 的 DECODE 语义）、Apache ShardingSphere・Vitess（分片归并组合形态）、
 * TPC-H・TPC-DS（占比、组内份额、条件聚合）。
 *
 * <p>预期：全部按标准语义断言。尚未支持的特性以 {@code @Disabled("待支持: ...")}
 * 标记跳过，作为后续修复清单，不为缺陷行为放宽预期。
 *
 * <p>时间承载约定：TIMESTAMP 以 epoch millis（Long）透出、按 UTC 墙钟解释，
 * DATE 以 epoch days 承载；涉及时间值的断言按该约定（必要时经 CAST 比对字面值）。
 */
class CrossDbComprehensiveTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  /** 商品域：同一 DataSource 注册两个 schema，把区域维度拆成独立「库」制造跨库链。 */
  private static CrossDb coreGoods() throws SQLException {
    return new CrossDb()
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS);
  }

  private static CrossDb coreLogs() throws SQLException {
    return core().register("logdb", Fixtures.LOGS);
  }

  private static CrossDb all() throws SQLException {
    return coreLogs()
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS)
        .register("credsdb", Fixtures.CREDS)
        .register("quotasdb", Fixtures.QUOTAS)
        .register("eventdb", Fixtures.EVENTS)
        .register("pingdb", Fixtures.PINGS);
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

  // ---------- JOIN 深组合（跨库链、残余条件回退、同源双 schema） ----------

  @Nested
  @DisplayName("JOIN 深组合场景")
  class JoinsMore {

    @Test void fourDbChainUsersOrdersShipmentsRegions() throws Exception {
      // users←orders←shipments→regions 四库链；订单 103 无区域被内连接丢弃
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,north", "bob,south", "alice,north"),
            rows(db, "SELECT u.name, r.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN gooddb.shipments sh ON sh.order_id = o.id "
                + "JOIN regiondb.regions r ON r.region_id = sh.region_id "
                + "ORDER BY o.id"));
      }
    }

    @Test void doubleLeftJoinKeepsNullRegionOrders() throws Exception {
      // 双重 LEFT JOIN：订单 103 经 shipments(NULL region) 仍保留、区域补 NULL
      try (CrossDb db = all()) {
        assertEquals(List.of("100,north", "101,south", "102,north", "103,NULL"),
            rows(db, "SELECT o.id, r.name FROM orderdb.orders o "
                + "LEFT JOIN gooddb.shipments sh ON sh.order_id = o.id "
                + "LEFT JOIN regiondb.regions r ON r.region_id = sh.region_id "
                + "ORDER BY o.id"));
      }
    }

    @Test void fullJoinGroupByOuterKeyWithNullGroup() throws Exception {
      // FULL JOIN 后按左键分组：bob/carol 计 0，右侧未匹配(event 2)单独成 NULL 组
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1", "2,0", "3,0", "NULL,1"),
            rows(db, "SELECT u.id, COUNT(e.id) FROM userdb.users u "
                + "FULL JOIN eventdb.events e ON e.user_id = u.id "
                + "GROUP BY u.id ORDER BY u.id NULLS LAST"));
      }
    }

    @Test void orPredicateJoinChainedAcrossThreeDbs() throws Exception {
      // OR 连接条件回退原生后继续三库 JOIN（orders⋈users 6 行 × creds 计数 = 8）
      try (CrossDb db = all()) {
        assertEquals("8", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id OR o.amount = 5 "
            + "JOIN credsdb.creds c ON c.user_id = u.id"));
      }
    }

    @Test void joinBackToDriverTableAfterCrossDb() throws Exception {
      // 跨库往返：users⋈orders 再回连同库 small（同库下推与跨库 Bind Join 混合）
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id "
            + "JOIN userdb.small s ON s.id = u.id"));
      }
    }

    @Test void leftJoinDerivedAggregateWithCoalesceFill() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,1", "bob,0", "carol,0"),
            rows(db, "SELECT u.name, COALESCE(d.c, 0) FROM userdb.users u "
                + "LEFT JOIN (SELECT user_id, COUNT(*) AS c FROM pingdb.pings "
                + "GROUP BY user_id) d ON d.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void rightJoinWithResidualNonEquiKeepsAllRightRows() throws Exception {
      // 右连接 ON 含残余非等值（u.id > 99 无命中）：右侧行全保留、左侧补 NULL
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT e.id FROM userdb.users u "
                + "RIGHT JOIN eventdb.events e ON e.user_id = u.id AND u.id > 99 "
                + "ORDER BY e.id"));
      }
    }

    @Test void joinKeyWithCastFallsBackButCorrect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON CAST(o.user_id AS BIGINT) = u.id"));
      }
    }

    @Test void sameTableThreeWayAcrossMirrorSchemas() throws Exception {
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users a "
            + "JOIN usermirror.users b ON a.id = b.id "
            + "JOIN userdb.users c ON c.id = b.id"));
      }
    }

    @Test void antiJoinCompositeAcrossThreeDbs() throws Exception {
      // 无「凭证+配额」完整匹配的用户 → carol（c1 无 tenant=100 配额）
      try (CrossDb db = all()) {
        assertEquals(List.of("carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM credsdb.creds c JOIN quotasdb.quotas q "
                + "ON q.user_id = c.user_id AND q.tenant_id = c.tenant_id "
                + "WHERE c.user_id = u.id) ORDER BY u.name"));
      }
    }

    @Test void threeWayJoinWithPartitionedRowNumber() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100,1", "bob,101,1", "alice,102,2", "bob,103,2"),
            rows(db, "SELECT u.name, o.id, ROW_NUMBER() OVER "
                + "(PARTITION BY u.id ORDER BY o.id) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id"));
      }
    }

    @Test void semiJoinAcrossMirrorSchemasWithFilter() throws Exception {
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM userdb.users a WHERE EXISTS "
            + "(SELECT 1 FROM usermirror.users b WHERE b.id = a.id AND b.id > 1)"));
      }
    }
  }

  // ---------- 子查询深组合（相关 ANY/IN、聚合选择列表、行值相关等值） ----------

  @Nested
  @DisplayName("子查询深组合场景")
  class SubqueriesMore {

    @Test void correlatedAnyQuantifiedComparison() throws Exception {
      // 相关 ANY：carol 子查询为空 → false；alice/bob 各有更小金额
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id < ANY "
                + "(SELECT o.amount FROM orderdb.orders o WHERE o.user_id = u.id) "
                + "ORDER BY u.name"));
      }
    }

    @Test void existsWithOrderByInside() throws Exception {
      // EXISTS 内 ORDER BY 合法且不影响语义
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id "
                + "ORDER BY o.id) ORDER BY u.name"));
      }
    }

    @Test void nestedScalarsInCaseBranches() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "SELECT CASE WHEN "
            + "(SELECT MAX(amount) FROM orderdb.orders) > 15 "
            + "THEN (SELECT MIN(amount) FROM orderdb.orders) ELSE 0 END "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void existsWithAggregateSelectListAndHaving() throws Exception {
      // 聚合选择列表 + HAVING（标准形态）：恰好 2 单的用户
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT COUNT(*) FROM orderdb.orders o WHERE o.user_id = u.id "
                + "HAVING COUNT(*) = 2) ORDER BY u.name"));
      }
    }

    @Test void betweenTwoScalarSubqueries() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE id BETWEEN "
            + "(SELECT MIN(amount) FROM orderdb.orders) "
            + "AND (SELECT MAX(amount) FROM orderdb.orders)"));
      }
    }

    @Test void inSubqueryOverValuesConstructor() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT x FROM (VALUES (1), (3)) AS t(x)) ORDER BY u.name"));
      }
    }

    @Test void correlatedInWithResidualExpressionPredicate() throws Exception {
      // IN 关联 + 残余表达式条件（o.id > u.id*30）：alice/bob 命中、carol 无订单用户
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT o.user_id FROM orderdb.orders o WHERE o.id > u.id * 30) "
                + "ORDER BY u.name"));
      }
    }

    @Test void notExistsWithCompositeCorrelationQuotas() throws Exception {
      // 无 tenant=200 配额的用户 → bob、carol
      try (CrossDb db = all()) {
        assertEquals(List.of("bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM quotasdb.quotas q "
                + "WHERE q.user_id = u.id AND q.tenant_id = 200) ORDER BY u.name"));
      }
    }

    @Test void rowValueCorrelatedEqualityInsideExists() throws Exception {
      // 行值相关等值：(user_id, tenant_id) = (u.id, 200) 仅命中 alice
      try (CrossDb db = all()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM credsdb.creds c "
                + "WHERE (c.user_id, c.tenant_id) = (u.id, 200)) ORDER BY u.name"));
      }
    }

    @Test void havingAgainstScalarSubqueryAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name "
                + "HAVING SUM(o.amount) > (SELECT AVG(amount) FROM orderdb.orders) "
                + "ORDER BY u.name"));
      }
    }

    @Test void existsUncorrelatedEmptyYieldsNoRows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE EXISTS "
            + "(SELECT 1 FROM orderdb.orders WHERE amount > 999)"));
      }
    }

    @Test void inSubqueryInsideCaseCondition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("yes", scalar(db, "SELECT CASE WHEN 1 IN "
            + "(SELECT id FROM userdb.small) THEN 'yes' ELSE 'no' END "
            + "FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- 聚合深组合（组内份额、条件聚合、多样 DISTINCT 集合） ----------

  @Nested
  @DisplayName("聚合深组合场景")
  class AggregatesMore {

    @Test void rankOverGroupedAggregate() throws Exception {
      // 窗口排序作用于分组聚合值（TPC-DS 组内排名形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,1", "alice,2"),
            rows(db, "SELECT u.name, RANK() OVER (ORDER BY SUM(o.amount) DESC) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY 2, 1"));
      }
    }

    @Test void groupShareOfTotal() throws Exception {
      // 组内占比（整数除法语义）：15*100/36=41、21*100/36=58
      try (CrossDb db = core()) {
        assertEquals(List.of("1,41", "2,58"),
            rows(db, "SELECT user_id, SUM(amount) * 100 / SUM(SUM(amount)) OVER () "
                + "FROM orderdb.orders GROUP BY user_id ORDER BY user_id"));
      }
    }

    @Test void groupByCoalesceKeyNormalizesNulls() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("-1,1", "1,1", "9,1"),
            rows(db, "SELECT COALESCE(p.user_id, -1), COUNT(*) FROM pingdb.pings p "
                + "GROUP BY COALESCE(p.user_id, -1) ORDER BY 1"));
      }
    }

    @Test void havingOnConditionalSumFiltersAllGroups() throws Exception {
      // alice/bob 条件计数各 1 → 无组满足 >= 2
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT u.name FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name "
            + "HAVING SUM(CASE WHEN o.amount >= 10 THEN 1 ELSE 0 END) >= 2 "
            + "ORDER BY u.name").isEmpty());
      }
    }

    @Test void varSampSingleRowIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("NULL", scalar(db,
            "SELECT VAR_SAMP(amount) FROM orderdb.orders WHERE amount = 1"));
      }
    }

    @Test void varSampOverAllRowsExactValue() throws Exception {
      // amount {10,20,5,1}：样本方差 = 202/3 = 67.3333
      try (CrossDb db = core()) {
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT CAST(VAR_SAMP(amount) AS DECIMAL(10,4)) FROM orderdb.orders"))
            .compareTo(new BigDecimal("67.3333")));
      }
    }

    @Test void countDistinctOverExpression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db,
            "SELECT COUNT(DISTINCT UPPER(name)) FROM userdb.users"));
      }
    }

    @Test void mixedDistinctAggregateFamilies() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,36"),
            rows(db, "SELECT COUNT(DISTINCT user_id), SUM(DISTINCT amount) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void aggregateArithmeticProjection() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("68", scalar(db,
            "SELECT SUM(amount) * 2 - COUNT(*) FROM orderdb.orders"));
      }
    }

    @Test void groupByTwoExpressionKeys() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1,2", "0,2,1", "1,3,1"),
            rows(db, "SELECT MOD(user_id, 2), user_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY MOD(user_id, 2), user_id ORDER BY user_id"));
      }
    }

    @Test void minOverVarcharGroupedCrossDbJoin() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,a1", "bob,b1", "carol,c1"),
            rows(db, "SELECT u.name, MIN(c.login) FROM userdb.users u "
                + "JOIN credsdb.creds c ON c.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void conditionalSumOverEmptySetIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0,NULL"),
            rows(db, "SELECT COUNT(*), SUM(CASE WHEN id > 0 THEN 1 ELSE 0 END) "
                + "FROM userdb.users WHERE id > 99"));
      }
    }
  }

  // ---------- 窗口深组合（RANGE 数值帧、多窗口共存、默认帧边界） ----------

  @Nested
  @DisplayName("窗口深组合场景")
  class WindowsMore {

    @Test void rangeNumericFrameSum() throws Exception {
      // RANGE 数值帧（值域 ±5）：100→{5,10}=15；101→{20}=20；102→{1,5,10}=16；103→{1,5}=6
      try (CrossDb db = core()) {
        assertEquals(List.of("100,15", "101,20", "102,16", "103,6"),
            rows(db, "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE "
                + "BETWEEN 5 PRECEDING AND 5 FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void derivedWindowWithBetweenFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "102"),
            rows(db, "SELECT id FROM (SELECT id, ROW_NUMBER() OVER "
                + "(ORDER BY amount DESC, id) rn FROM orderdb.orders) t "
                + "WHERE rn BETWEEN 2 AND 3 ORDER BY id"));
      }
    }

    @Test void multipleWindowSpecsInOneQuery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,2,4", "101,2,4", "102,2,4", "103,2,4"),
            rows(db, "SELECT id, COUNT(*) OVER (PARTITION BY user_id), "
                + "COUNT(*) OVER () FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void firstValueUnderDefaultFrame() throws Exception {
      // 默认帧（RANGE UNBOUNDED PRECEDING..CURRENT ROW）下首值恒为 'a'
      try (CrossDb db = all()) {
        assertEquals(List.of("1,a", "2,a", "3,a"),
            rows(db, "SELECT id, FIRST_VALUE(note) OVER (ORDER BY id) "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void lagWithTieBrokenOrderKeys() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL", "101,102", "102,100", "103,101"),
            rows(db, "SELECT id, LAG(id) OVER (ORDER BY user_id, id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void denseRankTopNPerGroupDerivedFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101"),
            rows(db, "SELECT name, id FROM (SELECT u.name, o.id, DENSE_RANK() OVER "
                + "(PARTITION BY u.name ORDER BY o.amount DESC) dr "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id) t "
                + "WHERE dr = 1 ORDER BY name, id"));
      }
    }

    @Test void ntileThreeBucketsOverFourRows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,2", "103,3"),
            rows(db, "SELECT id, NTILE(3) OVER (ORDER BY id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void rankOverAggregateDerivedJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,21,1", "alice,15,2"),
            rows(db, "SELECT u.name, t.s, RANK() OVER (ORDER BY t.s DESC) "
                + "FROM userdb.users u JOIN (SELECT user_id, SUM(amount) AS s "
                + "FROM orderdb.orders GROUP BY user_id) t ON t.user_id = u.id "
                + "ORDER BY t.s DESC"));
      }
    }

    @Test void lastValueDefaultFrameIsCurrentRow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,100", "101,101", "102,102", "103,103"),
            rows(db, "SELECT id, LAST_VALUE(id) OVER (ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void globalWindowOverEmptyFilteredInput() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id, SUM(amount) OVER () FROM orderdb.orders "
            + "WHERE amount > 999").isEmpty());
      }
    }
  }

  // ---------- 集合操作深组合（分支聚合、CTE 内集合、括号优先级） ----------

  @Nested
  @DisplayName("集合操作深组合场景")
  class SetOpsMore {

    @Test void intersectWithOrderByAndLimit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"),
            rows(db, "SELECT user_id FROM orderdb.orders INTERSECT "
                + "SELECT id FROM userdb.users ORDER BY user_id DESC LIMIT 1"));
      }
    }

    @Test void exceptAllWithNullRows() throws Exception {
      // pings{1,9,NULL} 多重集减 users{1,2,3} → {9,NULL}
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL", "9"),
            rows(db, "SELECT user_id FROM pingdb.pings EXCEPT ALL "
                + "SELECT id FROM userdb.users ORDER BY 1 NULLS FIRST"));
      }
    }

    @Test void unionAllWithGroupedBranchAndConstantBranch() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2", "2,2", "3,0"),
            rows(db, "SELECT user_id, COUNT(*) FROM orderdb.orders GROUP BY user_id "
                + "UNION ALL SELECT id, 0 FROM userdb.users WHERE id = 3 ORDER BY 1"));
      }
    }

    @Test void setOpInsideCteJoinedToDimension() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "WITH active AS (SELECT user_id FROM orderdb.orders "
                + "WHERE amount >= 10 INTERSECT SELECT id FROM userdb.users) "
                + "SELECT u.name FROM userdb.users u "
                + "JOIN active a ON a.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void parenthesizedMixedSetPrecedence() throws Exception {
      // (users{1} ∪ users{2}) EXCEPT users{2} = {1}
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM ("
            + "(SELECT id FROM userdb.users WHERE id = 1 "
            + "UNION SELECT id FROM userdb.users WHERE id = 2) "
            + "EXCEPT SELECT id FROM userdb.users WHERE id = 2) t"));
      }
    }

    @Test void unionAllAcrossThreeMirrorSchemas() throws Exception {
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals("8", scalar(db, "SELECT SUM(c) FROM ("
            + "SELECT COUNT(*) AS c FROM userdb.users "
            + "UNION ALL SELECT COUNT(*) FROM usermirror.users "
            + "UNION ALL SELECT COUNT(*) FROM userdb.small) t"));
      }
    }

    @Test void intersectChainTreatsNullAsMember() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT user_id FROM pingdb.pings INTERSECT "
                + "SELECT user_id FROM orderdb.orders ORDER BY 1"));
      }
    }

    @Test void symmetricDifferenceViaDoubleExcept() throws Exception {
      // 对称差：(users EXCEPT small) ∪ (small EXCEPT users) = {3}
      // （EXCEPT 与 UNION 同级左结合，须括号表达两支）
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "(SELECT id FROM userdb.users EXCEPT SELECT id FROM userdb.small) "
                + "UNION (SELECT id FROM userdb.small EXCEPT SELECT id FROM userdb.users) "
                + "ORDER BY 1"));
      }
    }

    @Test void intersectOfDerivedAggregateAndTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT user_id FROM (SELECT user_id, COUNT(*) c "
                + "FROM orderdb.orders GROUP BY user_id HAVING c >= 2) t "
                + "INTERSECT SELECT id FROM userdb.users ORDER BY 1"));
      }
    }
  }

  // ---------- CTE 深组合（链式依赖、双引用、递归多种子） ----------

  @Nested
  @DisplayName("CTE 深组合场景")
  class CtesMore {

    @Test void cteWithWindowJoinedCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "WITH ranked AS (SELECT id, user_id, ROW_NUMBER() OVER "
                + "(ORDER BY amount DESC) rn FROM orderdb.orders) "
                + "SELECT u.name FROM userdb.users u "
                + "JOIN ranked r ON r.user_id = u.id WHERE r.rn = 1"));
      }
    }

    @Test void chainedCtesWithDependency() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "WITH a AS (SELECT id FROM userdb.users "
            + "WHERE id <= 2), b AS (SELECT id FROM a WHERE id >= 2) "
            + "SELECT COUNT(*) FROM b"));
      }
    }

    @Test void recursiveSumProgression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("15", scalar(db, "WITH RECURSIVE t(n) AS (VALUES (1) UNION ALL "
            + "SELECT n + 1 FROM t WHERE n < 5) SELECT SUM(n) FROM t"));
      }
    }

    @Test void recursiveMultiSeedWalk() throws Exception {
      // 种子 {1,3}：1→2→3（3 不再增长）∪ 种子 → 共 4 行
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "WITH RECURSIVE t(n) AS (VALUES (1), (3) "
            + "UNION ALL SELECT n + 1 FROM t WHERE n < 3) SELECT COUNT(*) FROM t"));
      }
    }

    @Test void cteReferencedTwiceWithDistinctAliases() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH u AS (SELECT id, name FROM userdb.users) "
            + "SELECT COUNT(*) FROM u a JOIN u b ON a.id = b.id + 1"));
      }
    }

    @Test void cteAggregateReferencedInTwoScalarSubqueries() throws Exception {
      // 注意：CTE 名不能取 PER（解析器保留字）
      try (CrossDb db = core()) {
        assertEquals("6", scalar(db, "WITH per_user AS (SELECT user_id, SUM(amount) AS s "
            + "FROM orderdb.orders GROUP BY user_id) "
            + "SELECT (SELECT MAX(s) FROM per_user) - (SELECT MIN(s) FROM per_user)"));
      }
    }
  }

  // ---------- 排序分页深组合 ----------

  @Nested
  @DisplayName("排序分页深组合场景")
  class PagingMore {

    @Test void orderByVarcharDescNullsLast() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("b", "a", "NULL"),
            rows(db, "SELECT note FROM pingdb.pings ORDER BY note DESC NULLS LAST"));
      }
    }

    @Test void orderByCaseExpressionBucketing() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102", "103"),
            rows(db, "SELECT id FROM orderdb.orders ORDER BY "
                + "CASE WHEN amount >= 10 THEN 0 ELSE 1 END, id"));
      }
    }

    @Test void limitOverDistinctCrossDbJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT DISTINCT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "ORDER BY u.name LIMIT 1"));
      }
    }

    @Test void offsetFetchAfterUnionDistinct() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.small UNION SELECT user_id FROM credsdb.creds "
                + "ORDER BY 1 OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY"));
      }
    }

    @Test void shardTopNOverExpressionKeysThreeBranches() throws Exception {
      // 负号表达式键的归并 Top-N：全体升序最小 4 个
      try (CrossDb db = all()) {
        assertEquals(List.of("-103", "-102", "-101", "-100"),
            rows(db, "SELECT id FROM (SELECT -id AS id FROM userdb.users "
                + "UNION ALL SELECT -id FROM orderdb.orders "
                + "UNION ALL SELECT -user_id FROM credsdb.creds) t ORDER BY id LIMIT 4"));
      }
    }
  }

  // ---------- NULL 语义深组合 ----------

  @Nested
  @DisplayName("NULL 语义深组合场景")
  class NullSemanticsMore {

    @Test void coalesceBothNullFallsThroughToLiteral() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a", "b", "zz"),
            rows(db, "SELECT COALESCE(p.note, CAST(p.user_id AS VARCHAR), 'zz') "
                + "FROM pingdb.pings p ORDER BY p.id"));
      }
    }

    @Test void nullifAcrossJoinedVarcharColumns() throws Exception {
      // tenant=100 凭证：alice-a1、bob-b1、carol-c1（a2 属 tenant 200 不参与）
      try (CrossDb db = all()) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT NULLIF(u.name, c.login) FROM userdb.users u "
                + "JOIN credsdb.creds c ON c.user_id = u.id AND c.tenant_id = 100 "
                + "ORDER BY u.name, c.login"));
      }
    }

    @Test void fullJoinNullSideFilterBothDirections() throws Exception {
      // 两侧未匹配行均保留：排序键 COALESCE(u.id,0) → (0,2) 最先
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,2", "2,NULL", "3,NULL"),
            rows(db, "SELECT u.id, e.id FROM userdb.users u "
                + "FULL JOIN eventdb.events e ON e.user_id = u.id "
                + "WHERE u.id IS NULL OR e.id IS NULL "
                + "ORDER BY COALESCE(u.id, 0), COALESCE(e.id, 0)"));
      }
    }

    @Test void inSubqueryWithNullRowsStillMatchesEquality() throws Exception {
      // 子查询含 NULL 不影响真值匹配（仅 NOT IN 受三值逻辑影响）
      try (CrossDb db = all()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE id IN (SELECT user_id FROM pingdb.pings)"));
      }
    }

    @Test void antiJoinKeepsNullKeyDriverRows() throws Exception {
      // ANTI 反连接下 NULL key 驱动行视为「无匹配」保留（log 4）
      try (CrossDb db = all()) {
        assertEquals(List.of("4"),
            rows(db, "SELECT l.id FROM logdb.logs l WHERE NOT EXISTS "
                + "(SELECT 1 FROM userdb.users u WHERE u.id = l.user_id) ORDER BY l.id"));
      }
    }

    @Test void nullGroupSurvivesHaving() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1", "9,1", "NULL,1"),
            rows(db, "SELECT user_id, COUNT(*) FROM pingdb.pings GROUP BY user_id "
                + "HAVING COUNT(*) = 1 ORDER BY user_id NULLS LAST"));
      }
    }
  }

  // ---------- 类型与转换深组合 ----------

  @Nested
  @DisplayName("类型与转换深组合场景")
  class TypesCastsMore {

    @Test void castDecimalRoundsToTargetScale() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals("99.9", scalar(db,
            "SELECT CAST(price AS DECIMAL(10,1)) FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void castIntToBoolean() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db,
            "SELECT CAST(1 AS BOOLEAN) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void datePlusIntervalDaysComparison() throws Exception {
      // DATE + INTERVAL 后比较（非空 made 三行均 > 2026-01-19）
      try (CrossDb db = coreGoods()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM gooddb.products "
            + "WHERE made + INTERVAL '5' DAY > DATE '2026-01-19'"));
      }
    }

    @Test void castTimestampToDateThenVarchar() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2026-01-02", scalar(db,
            "SELECT CAST(CAST(ts AS DATE) AS VARCHAR) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void extractQuarterAndDoy() throws Exception {
      // 2026-03-10：Q1、年积日 69（31+28+10）
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("1,69"), rows(db,
            "SELECT EXTRACT(QUARTER FROM made), EXTRACT(DOY FROM made) "
                + "FROM gooddb.products WHERE id = 12"));
      }
    }

    @Test void castTsVarcharPrefixLike() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE CAST(ts AS VARCHAR) LIKE '2026-01%'"));
      }
    }

    @Test void bigintMultiplyBeyondIntRange() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3000000000", scalar(db,
            "SELECT CAST(id AS BIGINT) * 1000000000 FROM userdb.users WHERE id = 3"));
      }
    }

    @Test void booleanConditionalAggregate() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("1,0"), rows(db, "SELECT MAX(CASE WHEN active THEN 1 "
            + "ELSE 0 END), MIN(CASE WHEN active THEN 1 ELSE 0 END) FROM gooddb.products"));
      }
    }

    @Test void decimalDivisionExactScale() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT price / 2 FROM gooddb.products WHERE id = 11"))
            .compareTo(new BigDecimal("24.75")));
      }
    }
  }

  // ---------- 方言函数与改写回归（DECODE/WITH TIES/SEMI·ANTI 语法路径） ----------

  @Nested
  @DisplayName("方言函数与改写回归场景")
  class DialectAndRewrites {

    @Test void decodeNullEqualsNullSemantic() throws Exception {
      // Oracle DECODE 的 NULL=NULL 相等由 IS NOT DISTINCT FROM 改写承载：
      // note 为 NULL 的行（p3）命中 NULL 搜索值
      try (CrossDb db = all()) {
        assertEquals(List.of("not-null", "not-null", "was-null"),
            rows(db, "SELECT DECODE(note, NULL, 'was-null', 'not-null') "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void withTiesReturnsDuplicateTieRows() throws Exception {
      // creds.user_id {1,2,1,3}：前 1 个去重键 {1} → 两行并列返回
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "1"),
            rows(db, "SELECT user_id FROM credsdb.creds ORDER BY user_id "
                + "FETCH FIRST 1 ROW WITH TIES"));
      }
    }

    @Test void withTiesDescending() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "102"),
            rows(db, "SELECT id FROM orderdb.orders ORDER BY id DESC "
                + "FETCH FIRST 2 ROW WITH TIES"));
      }
    }

    @Test void semiJoinParenthesizedDerivedRightSide() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u LEFT SEMI JOIN "
                + "(SELECT user_id FROM orderdb.orders WHERE amount >= 10) d "
                + "ON d.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void antiJoinParenthesizedDerivedRightSide() throws Exception {
      // tenant=200 仅 alice 有凭证 → ANTI 保留 bob/carol
      try (CrossDb db = all()) {
        assertEquals(List.of("bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u LEFT ANTI JOIN "
                + "(SELECT user_id FROM credsdb.creds WHERE tenant_id = 200) d "
                + "ON d.user_id = u.id ORDER BY u.name"));
      }
    }

    @Test void floorTimestampToMonth() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2026-01-01 00:00:00.0", scalar(db,
            "SELECT FLOOR(ts TO MONTH) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void timestampDiffHourAndMinuteUnits() throws Exception {
      // 03:04:05 → 15:04:05：12 小时 = 720 分钟
      try (CrossDb db = all()) {
        assertEquals("12", scalar(db, "SELECT TIMESTAMPDIFF(HOUR, ts, "
            + "TIMESTAMP '2026-01-02 15:04:05') FROM pingdb.pings WHERE id = 1"));
        assertEquals("720", scalar(db, "SELECT TIMESTAMPDIFF(MINUTE, ts, "
            + "TIMESTAMP '2026-01-02 15:04:05') FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void initcapWithMixedDelimiters() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("Alice-Smith O'Neil", scalar(db,
            "SELECT INITCAP('alice-smith o''neil') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void overlayFourArgForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("aXYef", scalar(db,
            "SELECT OVERLAY('abcdef' PLACING 'XY' FROM 2 FOR 3) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void greatestLeastFourArgs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,2"), rows(db, "SELECT GREATEST(1, 5, 3, 2), "
            + "LEAST(4, 2, 8, 6) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void concatWsSkipsNullsAfterCast() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2-bob", scalar(db, "SELECT CONCAT_WS('-', CAST(id AS VARCHAR), name) "
            + "FROM userdb.users WHERE id = 2"));
      }
    }

    @Test void quotedAliasWithSpace() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id AS `user id` FROM userdb.users WHERE id = 1"));
      }
    }
  }

  // ---------- 商品域/日志域业务链场景 ----------

  @Nested
  @DisplayName("商品域与日志域业务链场景")
  class DomainScenarios {

    @Test void avgPricePerRegion() throws Exception {
      // north {99.90,49.50} 均价 74.70；south 25.00
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("north,74.70", "south,25.00"),
            rows(db, "SELECT r.name, AVG(p.price) FROM gooddb.products p "
                + "JOIN regiondb.regions r ON r.region_id = p.region_id "
                + "GROUP BY r.name ORDER BY r.name"));
      }
    }

    @Test void productsLeftJoinRegionsKeepsNullForeignKey() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("desk,north", "chair,north", "lamp,south", "box,NULL"),
            rows(db, "SELECT p.name, r.name FROM gooddb.products p "
                + "LEFT JOIN regiondb.regions r ON r.region_id = p.region_id "
                + "ORDER BY p.id"));
      }
    }

    @Test void ordersShipmentsRegionsChainAggregation() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("north,2,15", "south,1,20"),
            rows(db, "SELECT r.name, COUNT(*), SUM(o.amount) FROM orderdb.orders o "
                + "JOIN gooddb.shipments sh ON sh.order_id = o.id "
                + "JOIN regiondb.regions r ON r.region_id = sh.region_id "
                + "GROUP BY r.name ORDER BY r.name"));
      }
    }

    @Test void shipmentsLeftJoinOrdersNullRegionOnly() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("103,1"),
            rows(db, "SELECT sh.order_id, o.amount FROM gooddb.shipments sh "
                + "LEFT JOIN orderdb.orders o ON o.id = sh.order_id "
                + "LEFT JOIN regiondb.regions r ON r.region_id = sh.region_id "
                + "WHERE r.region_id IS NULL ORDER BY sh.order_id"));
      }
    }

    @Test void groupByDateColumnWithVarcharRender() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("2026-01-15,1", "2026-02-20,1", "2026-03-10,1"),
            rows(db, "SELECT CAST(made AS VARCHAR), COUNT(*) FROM gooddb.products "
                + "WHERE made IS NOT NULL GROUP BY made ORDER BY 1"));
      }
    }

    @Test void logFirstAndLastPerUser() throws Exception {
      // MIN/MAX(ts) 按 UTC 墙钟经 CAST 比对（epoch millis 承载约定）
      try (CrossDb db = all()) {
        assertEquals(List.of(
                "1,2026-01-02 03:04:05,2026-01-02 03:10:00",
                "2,2026-01-03 09:00:00,2026-01-03 09:00:00",
                "NULL,2026-01-03 09:30:00,2026-01-03 09:30:00"),
            rows(db, "SELECT user_id, CAST(MIN(ts) AS VARCHAR), CAST(MAX(ts) AS VARCHAR) "
                + "FROM logdb.logs GROUP BY user_id ORDER BY user_id NULLS LAST"));
      }
    }

    @Test void logConditionalLevelCounts() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1,2", "2,1,1", "NULL,0,1"),
            rows(db, "SELECT user_id, SUM(CASE WHEN level = 'INFO' THEN 1 ELSE 0 END), "
                + "COUNT(*) FROM logdb.logs GROUP BY user_id ORDER BY user_id NULLS LAST"));
      }
    }

    @Test void timestampDiffMinutesBetweenUserLogs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("5", scalar(db, "SELECT TIMESTAMPDIFF(MINUTE, MIN(ts), MAX(ts)) "
            + "FROM logdb.logs WHERE user_id = 1"));
      }
    }

    @Test void usersWithBothLogsAndOrders() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM logdb.logs l WHERE l.user_id = u.id) AND EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) "
                + "ORDER BY u.name"));
      }
    }

    @Test void logLevelSwitchCountViaLag() throws Exception {
      // 相邻日志分级变化次数：INFO→WARN→INFO→ERROR 共 3 次（别名避保留字 PREV）
      try (CrossDb db = all()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM (SELECT id, level, "
            + "LAG(level) OVER (ORDER BY id) AS prev_level FROM logdb.logs) t "
            + "WHERE prev_level IS NOT NULL AND level <> prev_level"));
      }
    }
  }

  // ---------- 可观测性与引擎行为 ----------

  @Nested
  @DisplayName("可观测性与引擎行为场景")
  class ObservabilityEngine {

    @Test void analyzeOnSemiJoinReportsBindJoin() throws Exception {
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT u.name FROM userdb.users u WHERE EXISTS "
            + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id)");
        assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
        assertTrue(report.contains("bindJoin"), report);
      }
    }

    @Test void explainOnLeftSemiJoinSyntaxProducesPlan() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT u.name FROM userdb.users u LEFT SEMI JOIN "
            + "orderdb.orders o ON o.user_id = u.id");
        assertTrue(plan.contains("Join"), plan);
      }
    }

    @Test void explainOnWithTiesRewriteProducesPlan() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT id FROM userdb.users ORDER BY id "
            + "FETCH FIRST 1 ROW WITH TIES");
        assertTrue(plan.contains("Enumerable"), plan);
      }
    }

    @Test void safeModeAllowsFilteredDerivedAggregate() throws Exception {
      try (CrossDb db = core().safeMode()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM "
            + "(SELECT id FROM userdb.users WHERE id > 1) t"));
      }
    }

    @Test void scalarSubquerySumAcrossTwoDbs() throws Exception {
      // 36 + 35 = 71（两库标量子查询算术组合）
      try (CrossDb db = all()) {
        assertEquals("71", scalar(db, "SELECT (SELECT SUM(amount) FROM orderdb.orders) "
            + "+ (SELECT SUM(quota) FROM quotasdb.quotas)"));
      }
    }
  }

  // ---------- 错误契约补充 ----------

  @Nested
  @DisplayName("错误契约补充场景")
  class ErrorContractsMore {

    @Test void groupByOrdinalOutOfRangeRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users GROUP BY 5"));
      }
    }

    @Test void orderByOrdinalOutOfRangeRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users ORDER BY 9"));
      }
    }

    @Test void unknownFunctionRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT NO_SUCH_FN(id) FROM userdb.users"));
      }
    }

    @Test void joinOnTrueCartesianSemantics() throws Exception {
      // ON TRUE 显式笛卡尔（非裸 CROSS JOIN 形态）语义正确
      try (CrossDb db = core()) {
        assertEquals("12", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON TRUE"));
      }
    }

    @Test void castInvalidBooleanRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(Exception.class,
            () -> db.query("SELECT CAST('maybe' AS BOOLEAN) FROM userdb.small"));
      }
    }

    @Test void valuesArityMismatchRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT * FROM (VALUES (1, 2), (3)) AS t(x, y)"));
      }
    }
  }

  // ---------- 结构构造补充（行构造器、VALUES NULL 推导） ----------

  @Nested
  @DisplayName("结构构造补充场景")
  class ConstructMore {

    @Test void rowConstructorInequalityComparison() throws Exception {
      // 行值字典序比较：(o.id, u.id) < (102, 2) 命中 100/101/102
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob", "alice"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE (o.id, u.id) < (102, 2) ORDER BY o.id"));
      }
    }

    @Test void valuesWithNullInfersNullableType() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "NULL"),
            rows(db, "SELECT x FROM (VALUES (1), (NULL)) AS t(x) ORDER BY x"));
      }
    }

    @Test void valuesMultipleRowsJoinedBackWithAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2", "2,2"),
            rows(db, "SELECT t.x, COUNT(*) FROM (VALUES (1), (2)) AS t(x) "
                + "JOIN orderdb.orders o ON o.user_id = t.x GROUP BY t.x ORDER BY t.x"));
      }
    }
  }

  // ---------- 方言扩展候选（预计暂不支持；失败则标记 @Disabled 待支持） ----------

  @Nested
  @DisplayName("方言扩展候选场景")
  class DialectCandidates {

    @Test void qualifyClauseFilterOnWindow() throws Exception {
      // Trino/Snowflake QUALIFY：窗口函数结果直接过滤
      try (CrossDb db = core()) {
        assertEquals(List.of("101", "100"),
            rows(db, "SELECT id FROM orderdb.orders "
                + "QUALIFY ROW_NUMBER() OVER (ORDER BY amount DESC) <= 2"));
      }
    }

    @Test void boolAndBoolOrAggregates() throws Exception {
      // PostgreSQL 布尔聚合：active {TRUE,FALSE,TRUE,NULL} → AND=false、OR=true
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("false,true"), rows(db,
            "SELECT BOOL_AND(active), BOOL_OR(active) FROM gooddb.products"));
      }
    }

    @Test void regexpMatchOperator() throws Exception {
      // MySQL REGEXP 操作符
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT name FROM userdb.users WHERE name REGEXP '^a'"));
      }
    }

    @Test void listaggWithOverflowClause() throws Exception {
      // 标准 LISTAGG ON OVERFLOW 子句
      try (CrossDb db = core()) {
        assertEquals("alice,bob,carol", scalar(db,
            "SELECT LISTAGG(name, ',') ON OVERFLOW ERROR "
                + "WITHIN GROUP (ORDER BY id) FROM userdb.users"));
      }
    }

    @Test void matchRecognizePatternDetection() throws Exception {
      // SQL:2011 MATCH_RECOGNIZE：相邻日志分级连续变化序列
      try (CrossDb db = all()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM logdb.logs "
            + "MATCH_RECOGNIZE (ORDER BY id ALL ROWS PER MATCH "
            + "PATTERN (A B+) DEFINE B AS level <> A.level)"));
      }
    }
  }
}

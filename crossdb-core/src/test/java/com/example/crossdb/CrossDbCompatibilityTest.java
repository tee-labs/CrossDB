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
 * 方言与特性兼容性测试（参考同类系统公开用例补充）。
 *
 * <p>用例来源参考：PostgreSQL regression 套件（USING/NATURAL、集合操作链、窗口帧、
 * FILTER 聚合、RECURSIVE CTE）、Apache Calcite（SqlOperatorsTest / JdbcTest 的
 * 标准语法形态）、Presto・Trino federated（类型放宽合并、窗口函数）、
 * SQL Server（TOP / APPLY 方言）、Oracle（MINUS / FETCH FIRST / NVL）、
 * MySQL（GROUP_CONCAT / IFNULL / LIMIT a,b）、Apache ShardingSphere・Vitess
 * （分片合并边界形态）。
 *
 * <p>预期：全部按标准语义断言。尚未支持的特性以 {@code @Disabled("待支持: ...")}
 * 标记跳过，作为后续修复清单，不为缺陷行为放宽预期。
 */
class CrossDbCompatibilityTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb corePlusCreds() throws SQLException {
    return core().register("credsdb", Fixtures.CREDS).register("quotasdb", Fixtures.QUOTAS);
  }

  private static CrossDb corePlusPings() throws SQLException {
    return core().register("pingdb", Fixtures.PINGS).register("eventdb", Fixtures.EVENTS);
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

  // ---------- JOIN 扩展（参考 PostgreSQL regress JOIN / Calcite JdbcTest） ----------

  @Nested
  @DisplayName("JOIN 扩展场景")
  class JoinExtensions {

    @Test void joinUsingSharedColumns() throws Exception {
      // USING：共享列（user_id, tenant_id）合并为单列输出（PostgreSQL regress）
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20"),
            rows(db, "SELECT login, quota FROM credsdb.creds "
                + "JOIN quotasdb.quotas USING (user_id, tenant_id) ORDER BY login"));
      }
    }

    @Test
    @Disabled("待修复: USING 共享列在 SELECT 裸引用触发校验器 AssertionError"
        + "（SqlValidatorImpl.expandCommonColumn），待支持")
    void usingSharedColumnBareReferenceCrashes() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("a1,10,1", "a2,5,1", "b1,20,2"),
            rows(db, "SELECT login, quota, user_id FROM credsdb.creds "
                + "JOIN quotasdb.quotas USING (user_id, tenant_id) ORDER BY login"));
      }
    }

    @Test void naturalJoinSharedColumns() throws Exception {
      // NATURAL JOIN：按全部同名列等值连接
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20"),
            rows(db, "SELECT login, quota FROM credsdb.creds "
                + "NATURAL JOIN quotasdb.quotas ORDER BY login"));
      }
    }

    @Test
    @Disabled("待支持: 表别名带列名清单 FROM t(a, b)（标准 SQL/PostgreSQL）校验不识别，待支持")
    void tableAliasWithColumnList() throws Exception {
      // 别名列清单（标准 SQL / PostgreSQL 支持）：t(name, uid)
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,1"),
            rows(db, "SELECT n, uid FROM userdb.users t(name, uid) WHERE uid = 1"));
      }
    }

    @Test void fourWayJoinAcrossFourDbs() throws Exception {
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice,10", "bob,20", "alice,10", "bob,20"),
            rows(db, "SELECT u.name, q.quota FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN credsdb.creds c ON c.user_id = u.id AND c.tenant_id = 100 "
                + "JOIN quotasdb.quotas q ON q.user_id = u.id AND q.tenant_id = 100 "
                + "ORDER BY o.id"));
      }
    }

    @Test void joinWithOrConditionFallsBackCorrect() throws Exception {
      // OR 连接条件（非纯等值对）：Bind Join 不改写，回退原生计划
      // 命中：100→u1；101→u2；102(amount=5)→全体；103→u2 = 6 行
      try (CrossDb db = core()) {
        assertEquals("6", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id OR o.amount = 5"));
      }
    }

    @Test void bothSidesAggregateDerivedTablesJoin() throws Exception {
      // 两侧均为聚合派生表的跨库 JOIN（Trino federated 形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,15,1", "2,21,1"),
            rows(db, "SELECT t.user_id, t.s, u.c FROM "
                + "(SELECT user_id, SUM(amount) AS s FROM orderdb.orders GROUP BY user_id) t "
                + "JOIN (SELECT id, COUNT(*) AS c FROM userdb.users GROUP BY id) u "
                + "ON u.id = t.user_id ORDER BY t.user_id"));
      }
    }

    @Test void fullJoinWhereOnCoalescedKey() throws Exception {
      // FULL JOIN 后按 COALESCE key 过滤：保留 bob/carol 左未匹配 + event 2 右未匹配
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("bob,NULL", "carol,NULL", "NULL,2"),
            rows(db, "SELECT u.name, e.id FROM userdb.users u "
                + "FULL JOIN eventdb.events e ON e.user_id = u.id "
                + "WHERE COALESCE(e.user_id, u.id) >= 2 "
                + "ORDER BY COALESCE(e.user_id, u.id)"));
      }
    }

    @Test void antiJoinCompositeKeyNotExists() throws Exception {
      // 复合键 ANTI：无 tenant=100 配额的用户 → carol
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM quotasdb.quotas q "
                + "WHERE q.user_id = u.id AND q.tenant_id = 100) ORDER BY u.name"));
      }
    }

    @Test void semiWithUnionInsideSubquery() throws Exception {
      // IN 子查询内含 UNION：{orders.user_id} ∪ {events.id} = {1,2,9} → alice、bob
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT user_id FROM orderdb.orders UNION SELECT id FROM eventdb.events) "
                + "ORDER BY u.name"));
      }
    }

    @Test void uncorrelatedExistsIsConstantTrue() throws Exception {
      // 非相关 EXISTS：子查询非空 → 全体保留
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.amount > 15) ORDER BY u.name"));
      }
    }

    @Test void sameTableJoinedThreeTimesUnderTwoSchemas() throws Exception {
      // 同一 DataSource 三个 schema 自连接（分片同构表聚合场景）
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS).register("usermirror", Fixtures.USERS)) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT a.name FROM userdb.users a "
                + "JOIN usermirror.users b ON b.id = a.id "
                + "JOIN userdb.small s ON s.id = a.id ORDER BY a.name"));
      }
    }
  }

  // ---------- 集合操作扩展（参考 PostgreSQL regress 集合用例 / Oracle MINUS / 标准 FETCH） ----------

  @Nested
  @DisplayName("集合操作扩展场景")
  class SetOpCompat {

    @Test void unionTypeWideningIntToBigint() throws Exception {
      // INT 与 BIGINT 合并：类型放宽为 BIGINT（Trino/Calcite 类型合并语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT CAST(id AS BIGINT) FROM userdb.small UNION "
                + "SELECT id FROM userdb.users ORDER BY 1"));
      }
    }

    @Test void unionIntWithVarcharCoercesToString() throws Exception {
      // INT 与 VARCHAR 合并：放宽为 VARCHAR（PostgreSQL/Trino 允许，按字符串排序）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "alice", "bob", "carol"),
            rows(db, "SELECT id FROM userdb.users UNION "
                + "SELECT name FROM userdb.users ORDER BY 1"));
      }
    }

    @Test void parenthesizedSetOperands() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "(SELECT id FROM userdb.users WHERE id = 1) "
                + "UNION (SELECT id FROM userdb.users WHERE id = 2) ORDER BY 1"));
      }
    }

    @Test void offsetRowsFetchNextOnlySyntax() throws Exception {
      // 标准 SQL / Oracle 12c OFFSET...FETCH 语法
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id "
                + "OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY"));
      }
    }

    @Test void fetchFirstOneRowOnly() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id FETCH FIRST 1 ROW ONLY"));
      }
    }

    @Test void minusKeywordAsExcept() throws Exception {
      // Oracle 风格 MINUS 等价 EXCEPT
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users MINUS "
                + "SELECT id FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void unionAllWithLimitInsideBranch() throws Exception {
      // 分支内 ORDER BY + LIMIT 需括号包裹（标准 SQL/PostgreSQL 亦要求）——分片局部 Top-N 形态
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "103"),
            rows(db, "SELECT id FROM ((SELECT id FROM userdb.users ORDER BY id LIMIT 2) "
                + "UNION ALL (SELECT id FROM orderdb.orders ORDER BY id DESC LIMIT 1)) t "
                + "ORDER BY 1"));
      }
    }

    @Test void unionInUncorrelatedExists() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT id FROM userdb.users WHERE id = 1 "
                + "UNION SELECT id FROM userdb.users WHERE id = 99) ORDER BY u.name"));
      }
    }

    @Test void cteWithUnionReferencedTwice() throws Exception {
      // CTE 含 UNION 被引用两次
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH u AS (SELECT id FROM userdb.users "
            + "UNION ALL SELECT id FROM userdb.users) "
            + "SELECT COUNT(*) FROM u WHERE id = 1"));
      }
    }

    @Test void exceptThenUnionChain() throws Exception {
      // 链式优先级：(users EXCEPT small) UNION orders = {3} ∪ {100..103}
      try (CrossDb db = core()) {
        assertEquals(List.of("3", "100", "101", "102", "103"),
            rows(db, "SELECT id FROM userdb.users EXCEPT SELECT id FROM userdb.small "
                + "UNION SELECT id FROM orderdb.orders ORDER BY 1"));
      }
    }

    @Test void unionDistinctThenUnionAll() throws Exception {
      // 混合链：前两个 UNION 去重为 {1,2,3}，再 UNION ALL 追加 → 6 行
      try (CrossDb db = core()) {
        assertEquals("6", scalar(db, "SELECT COUNT(*) FROM ("
            + "SELECT id FROM userdb.users UNION SELECT id FROM userdb.users "
            + "UNION ALL SELECT id FROM userdb.users) t"));
      }
    }
  }

  // ---------- 窗口帧与排名（参考 PostgreSQL regress window / Trino window 用例） ----------

  @Nested
  @DisplayName("窗口帧与排名扩展场景")
  class WindowFrames {

    @Test void rowsFramePrecedingToCurrent() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,30", "102,25", "103,6"),
            rows(db, "SELECT id, SUM(amount) OVER "
                + "(ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void unboundedPrecedingRunningSum() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,30", "102,35", "103,36"),
            rows(db, "SELECT id, SUM(amount) OVER "
                + "(ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void rowsFrameCurrentToFollowing() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,30", "101,25", "102,6", "103,1"),
            rows(db, "SELECT id, SUM(amount) OVER "
                + "(ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void ntileBuckets() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,2", "103,2"),
            rows(db, "SELECT id, NTILE(2) OVER (ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: CUME_DIST 窗口聚合在 Enumerable 约定下不可实现"
        + "（Unable to get aggregate implementation），待支持")
    void cumeDistOnSingleTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,0.25", "101,0.5", "102,0.75", "103,1.0"),
            rows(db, "SELECT id, CUME_DIST() OVER (ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: PERCENT_RANK 窗口聚合在 Enumerable 约定下不可实现，待支持")
    void percentRankWithTies() throws Exception {
      // 含并列（alice 2 行）：PERCENT_RANK = (rank-1)/(n-1)，并列同为 0.0/0.333.../1.0
      try (CrossDb db = core()) {
        List<String> r = rows(db, "SELECT DISTINCT CAST(PERCENT_RANK() OVER "
            + "(ORDER BY u.name) AS DECIMAL(4,3)) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY 1");
        assertEquals(3, r.size());
        assertEquals(0, new BigDecimal(r.get(0)).compareTo(new BigDecimal("0")));
        assertEquals(0, new BigDecimal(r.get(1)).compareTo(new BigDecimal("0.333")));
        assertEquals(0, new BigDecimal(r.get(2)).compareTo(new BigDecimal("1")));
      }
    }

    @Test
    @Disabled("待修复: NTH_VALUE 默认窗口帧与标准 SQL 不一致——首行即可见后行"
        + "（标准默认帧 RANGE UNBOUNDED PRECEDING AND CURRENT ROW，首行应为 NULL），待修复")
    void nthValueWindow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL", "101,101", "102,101", "103,101"),
            rows(db, "SELECT id, NTH_VALUE(id, 2) OVER (ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void windowOverUnionDerived() throws Exception {
      // UNION ALL 派生表上开窗：7 行降序编号
      try (CrossDb db = core()) {
        assertEquals(List.of("103,1", "102,2", "101,3", "100,4", "3,5", "2,6", "1,7"),
            rows(db, "SELECT id, ROW_NUMBER() OVER (ORDER BY id DESC) FROM "
                + "(SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders) t "
                + "ORDER BY 2"));
      }
    }
  }

  // ---------- 聚合扩展（参考 PostgreSQL FILTER / MySQL GROUP_CONCAT / Calcite LISTAGG） ----------

  @Nested
  @DisplayName("聚合扩展场景")
  class AggregateCompat {

    @Test void filterClauseOnAggregates() throws Exception {
      // FILTER (WHERE ...) 条件聚合（标准 SQL / PostgreSQL）
      try (CrossDb db = core()) {
        assertEquals(List.of("2,6"),
            rows(db, "SELECT COUNT(*) FILTER (WHERE amount >= 10), "
                + "SUM(amount) FILTER (WHERE amount <= 5) FROM orderdb.orders"));
      }
    }

    @Test void listaggWithinGroup() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("alice,bob,carol",
            scalar(db, "SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY id) FROM userdb.users"));
      }
    }

    @Test void listaggGroupedAfterJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100,102", "bob,101,103"),
            rows(db, "SELECT u.name, LISTAGG(o.id, ',') WITHIN GROUP (ORDER BY o.id) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test
    @Disabled("待支持: STRING_AGG 未注册（Calcite library 函数），待支持")
    void stringAggPostgresAlias() throws Exception {
      // PostgreSQL 风格 STRING_AGG
      try (CrossDb db = core()) {
        assertEquals("alice,bob,carol",
            scalar(db, "SELECT STRING_AGG(name, ',') FROM userdb.users"));
      }
    }

    @Test
    @Disabled("待支持: GROUP_CONCAT 未注册（MySQL library 函数），待支持")
    void groupConcatMysqlSyntax() throws Exception {
      // MySQL 风格 GROUP_CONCAT
      try (CrossDb db = core()) {
        assertEquals("alice,bob,carol",
            scalar(db, "SELECT GROUP_CONCAT(name ORDER BY id SEPARATOR ',') FROM userdb.users"));
      }
    }

    @Test
    @Disabled("待修复: VAR_POP/STDDEV_POP 对整数输入被截断为整数"
        + "（50/7，PostgreSQL 等标准语义应为 50.5/7.106），待修复")
    void varianceAndStddevAggregates() throws Exception {
      // 方差/标准差：amount {10,20,5,1}，VAR_POP=50.5，STDDEV_POP=√50.5≈7.11
      try (CrossDb db = core()) {
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT CAST(VAR_POP(amount) AS DECIMAL(10,2)) FROM orderdb.orders"))
            .compareTo(new BigDecimal("50.5")));
        assertEquals(0, new BigDecimal(scalar(db,
                "SELECT CAST(STDDEV_POP(amount) AS DECIMAL(10,2)) FROM orderdb.orders"))
            .compareTo(new BigDecimal("7.11")));
      }
    }

    @Test void sumDistinct() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT SUM(DISTINCT user_id) FROM orderdb.orders"));
      }
    }

    @Test void sumDividedByCount() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("9", scalar(db, "SELECT SUM(amount) / COUNT(*) FROM orderdb.orders"));
      }
    }
  }

  // ---------- 函数与表达式扩展（参考 PostgreSQL / Calcite SqlOperatorsTest / Oracle / MySQL） ----------

  @Nested
  @DisplayName("函数与表达式扩展场景")
  class FuncCompat {

    @Test
    @Disabled("待修复: INITCAP 被原样下推到源库执行而 H2 无此函数；未注册函数应本地求值，待修复")
    void initcapFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("Alice", scalar(db,
            "SELECT INITCAP(name) FROM userdb.users WHERE id = 1"));
      }
    }

    @Test
    @Disabled("待支持: LPAD/RPAD 未注册（Calcite library 函数），待支持")
    void lpadRpadFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("007,abxx"),
            rows(db, "SELECT LPAD('7', 3, '0'), RPAD('ab', 4, 'x') FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: REPEAT 未注册，待支持")
    void repeatFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("aba", scalar(db, "SELECT REPEAT('ab', 2) || 'a' FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待修复: OVERLAY 被原样下推到源库执行而 H2 无此函数；应本地求值，待修复")
    void overlayStandardSyntax() throws Exception {
      // 标准 SQL OVERLAY（PostgreSQL 支持）
      try (CrossDb db = core()) {
        assertEquals("abXYef", scalar(db,
            "SELECT OVERLAY('abcdef' PLACING 'XY' FROM 3 FOR 2) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void substringFromForSyntax() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("al", scalar(db,
            "SELECT SUBSTRING(name FROM 1 FOR 2) FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void trimLeadingTrailingBoth() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("b,a,a"),
            rows(db, "SELECT TRIM(LEADING 'a' FROM 'aab'), "
                + "TRIM(TRAILING 'b' FROM 'abb'), TRIM(BOTH 'x' FROM 'xxaxx') "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: ASCII/CHR 未注册，待支持")
    void asciiAndChrFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("65,A"),
            rows(db, "SELECT ASCII('A'), CHR(65) FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: GREATEST/LEAST 未注册，待支持")
    void greatestLeastFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,0"),
            rows(db, "SELECT GREATEST(1, 5, 3), LEAST(2, 0, 7) FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: NVL/IFNULL 未注册，待支持")
    void nvlAndIfnull() throws Exception {
      // Oracle NVL / MySQL IFNULL
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("a,0", "b,0", "NULL,d"),
            rows(db, "SELECT note, NVL(note, 'd') FROM pingdb.pings ORDER BY id"));
        assertEquals("0", scalar(db,
            "SELECT COUNT(*) FROM pingdb.pings WHERE IFNULL(note, '') = 'b'"));
      }
    }

    @Test void caseWithoutElseYieldsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("NULL", scalar(db,
            "SELECT CASE WHEN 1 = 2 THEN 'x' END FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nestedCaseExpression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("b", scalar(db,
            "SELECT CASE WHEN id = 1 THEN (CASE WHEN id = 2 THEN 'a' ELSE 'b' END) "
            + "ELSE 'c' END FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void castStringToDate() throws Exception {
      // DATE 列经 getObject 承载为 epoch days（与 TIMESTAMP 承载为 Long 的契约一致），
      // 断言经 VARCHAR 转换后的字面值
      try (CrossDb db = core()) {
        assertEquals("2026-03-04", scalar(db,
            "SELECT CAST(CAST('2026-03-04' AS DATE) AS VARCHAR) FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待修复: FLOOR(ts TO DAY) 下推源库后语法变形（源方言不支持 datetime 截断）；"
        + "应本地求值，待修复")
    void floorTimestampToDay() throws Exception {
      // FLOOR(timestamp TO 单位)（标准 SQL datetime 截断）
      try (CrossDb db = corePlusPings()) {
        assertEquals("2026-01-02 00:00:00.0", scalar(db,
            "SELECT FLOOR(ts TO DAY) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void timestampMinusInterval() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE ts - INTERVAL '1' DAY < TIMESTAMP '2026-01-03 03:04:05'"));
      }
    }

    @Test void currentDateIsNotNull() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(!"NULL".equals(scalar(db, "SELECT CURRENT_DATE FROM userdb.small LIMIT 1")));
      }
    }

    @Test void currentTimestampIsNotNull() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(!"NULL".equals(
            scalar(db, "SELECT CURRENT_TIMESTAMP FROM userdb.small LIMIT 1")));
      }
    }

    @Test void selectWithoutFrom() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT 1 + 1"));
        assertEquals("y", scalar(db, "SELECT CASE WHEN 1 = 1 THEN 'y' ELSE 'n' END"));
      }
    }

    @Test void charLengthVsOctetLength() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,5"),
            rows(db, "SELECT CHAR_LENGTH('alice'), OCTET_LENGTH('alice') "
                + "FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- 递归 CTE / PIVOT / 方言（参考 PostgreSQL WITH RECURSIVE、Calcite PIVOT、SQL Server TOP） ----------

  @Nested
  @DisplayName("递归 CTE、PIVOT 与方言语法场景")
  class DialectFeatures {

    @Test
    @Disabled("待支持: 递归 CTE 触发引擎 NPE（CalciteSchema table 为 null），待支持")
    void recursiveCteCounting() throws Exception {
      // WITH RECURSIVE 计数到 3（PostgreSQL regress 经典形态）
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "WITH RECURSIVE t(n) AS "
            + "(VALUES (1) UNION ALL SELECT n + 1 FROM t WHERE n < 3) "
            + "SELECT COUNT(*) FROM t"));
      }
    }

    @Test void pivotCountByColumn() throws Exception {
      // Calcite PIVOT 语义：隐式按剩余列（amount）分组，FOR 列各值展开为计数列
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0,1", "5,1,0", "10,1,0", "20,0,1"),
            rows(db, "SELECT * FROM (SELECT user_id, amount FROM orderdb.orders) "
                + "PIVOT (COUNT(*) FOR user_id IN (1, 2)) ORDER BY 1"));
      }
    }

    @Test
    @Disabled("待支持: SQL Server TOP 语法解析不支持，待评估")
    void topNSqlServerSyntax() throws Exception {
      // SQL Server TOP 语法
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT TOP 2 id FROM userdb.users ORDER BY id"));
      }
    }

    @Test void groupByAliasReference() throws Exception {
      // MySQL/PostgreSQL 风格 GROUP BY 别名：id 1,2,3 → m=1 两行、m=0 一行
      try (CrossDb db = core()) {
        assertEquals(List.of("0,1", "1,2"),
            rows(db, "SELECT MOD(id, 2) AS m, COUNT(*) FROM userdb.users GROUP BY m ORDER BY m"));
      }
    }
  }

  // ---------- 错误契约（参考 Calcite/PostgreSQL 错误路径用例） ----------

  @Nested
  @DisplayName("错误契约场景")
  class ErrorContracts {

    @Test void castNonNumericTextToIntRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(Exception.class, () -> db.query(
            "SELECT CAST('abc' AS INT) FROM userdb.small"));
      }
    }

    @Test void divisionByZeroRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(Exception.class, () -> db.query(
            "SELECT 1 / 0 FROM userdb.small"));
      }
    }

    @Test void columnNotInGroupByRejected() throws Exception {
      // 非分组列不得直接出现在 SELECT（标准 SQL）
      try (CrossDb db = core()) {
        assertThrows(Exception.class, () -> db.query(
            "SELECT name, COUNT(*) FROM userdb.users GROUP BY id"));
      }
    }

    @Test void unknownColumnRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT no_such_col FROM userdb.users"));
      }
    }
  }
}

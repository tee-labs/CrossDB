package com.example.crossdb;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全场景覆盖测试（第二批）：对标同类系统公开测试集的用例设计，与既有六组测试互补、
 * 不重复，按「先补用例、失败即标记 {@code @Disabled("待支持: ...")} 作为修复清单」的
 * 约定维护，全部按标准语义断言。
 *
 * <p>用例来源参考：PostgreSQL regression（BETWEEN SYMMETRIC、IS NOT DISTINCT FROM、
 * 行构造器字典序、GROUPING SETS）、Apache Calcite（SqlOperatorsTest / JdbcTest /
 * AggImplementor 覆盖面）、Trino・Presto federated（跨源量化谓词、NULL 传播、
 * 全局归并聚合）、MySQL 8.0（REGEXP、GROUP_CONCAT SEPARATOR、ANY_VALUE、
 * TIMESTAMPDIFF 单位）、SQL Server（TOP DISTINCT、IIF）、Oracle（TRANSLATE、
 * SOUNDEX、DECODE 多分支缺省）、Apache ShardingSphere・Vitess（分片全局归并：
 * SUM/COUNT 合并、全局均值 = ΣΣ/Σ）、TPC-H・TPC-DS（占比、条件聚合、组内排名）。
 *
 * <p>时间承载约定：TIMESTAMP 以 epoch millis（Long）透出、按 UTC 墙钟解释，
 * DATE 以 epoch days 承载；涉及时间值的断言经 CAST 比对字面值。
 */
class CrossDbFullCoverageTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb coreGoods() throws SQLException {
    return new CrossDb()
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("logdb", Fixtures.LOGS)
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

  // ---------- 标量函数全景 ----------

  @Nested
  @DisplayName("标量函数全景场景")
  class ScalarFunctions {

    @Test void absSignPowerSqrt() throws Exception {
      // PostgreSQL/Calcite 数值函数族
      try (CrossDb db = core()) {
        assertEquals(List.of("5,-1,1,1024.0,9.0"), rows(db, "SELECT ABS(-5), SIGN(-3), SIGN(4), "
            + "POWER(2, 10), SQRT(81) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void floorCeilNumericAllQuadrants() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,3,-3,-2"), rows(db, "SELECT CAST(FLOOR(2.7) AS INT), "
            + "CAST(CEIL(2.1) AS INT), CAST(FLOOR(-2.1) AS INT), CAST(CEIL(-2.7) AS INT) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void logExpRoundTripCastInt() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,2"), rows(db, "SELECT CAST(LOG10(1000) AS INT), "
            + "CAST(ROUND(LN(EXP(2))) AS INT) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void replaceFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("a+b+c", scalar(db, "SELECT REPLACE('a-b-c', '-', '+') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void positionStringScan() throws Exception {
      try (CrossDb db = core()) {
        // banana 中 n 首次出现于第 3 位
        assertEquals("3", scalar(db, "SELECT POSITION('n' IN 'banana') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nullifCoalesceChain() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("x", "b", "x"), rows(db, "SELECT COALESCE(NULLIF(note, 'a'), 'x') "
            + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void repeatConcatNested() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("ababab", scalar(db,
            "SELECT REPEAT('ab', 3) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void translateOracle() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("a23x5", scalar(db, "SELECT TRANSLATE('12345', '14', 'ax') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void soundexFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("R163", scalar(db, "SELECT SOUNDEX('Robert') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void iifSqlServer() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("y", scalar(db, "SELECT IIF(1 < 2, 'y', 'n') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void isnullSqlServer() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a", "b", "x"), rows(db, "SELECT ISNULL(note, 'x') "
            + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void ltrimRtrim() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("x,y"), rows(db, "SELECT LTRIM(' x'), RTRIM('y ') "
            + "FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- 字符串模式匹配 ----------

  @Nested
  @DisplayName("字符串模式匹配场景")
  class PatternMatching {

    @Test void likeUnderscoreSingleChar() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db,
            "SELECT name FROM userdb.users WHERE name LIKE '_ob'"));
      }
    }

    @Test void likeEscapeClause() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db, "SELECT 'a%b' LIKE 'a\\%b' ESCAPE '\\' "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void notLikeExcludes() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db,
            "SELECT name FROM userdb.users WHERE name NOT LIKE '%o%' ORDER BY name"));
      }
    }

    @Test void ilikeCaseInsensitive() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db, "SELECT 'ALICE' ILIKE 'a%' FROM userdb.small LIMIT 1"));
      }
    }

    @Test void notRegexpOperator() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db,
            "SELECT name FROM userdb.users WHERE name NOT REGEXP '^[ab]' ORDER BY name"));
      }
    }

    @Test void regexpCharacterClass() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE name REGEXP '^[ab]$' OR name REGEXP 'o'"));
      }
    }
  }

  // ---------- 聚合扩展（GROUPING SETS/CUBE、布尔聚合、不支持清单） ----------

  @Nested
  @DisplayName("聚合扩展场景")
  class Aggregates2 {

    @Test void cubeSingleKeyEqualsGroupingSets() throws Exception {
      // TPC-DS 分组集家族：CUBE 单键 = (k) ∪ ()
      try (CrossDb db = all()) {
        assertEquals(List.of("100,3", "200,1", "NULL,4"), rows(db,
            "SELECT tenant_id, COUNT(*) FROM credsdb.creds GROUP BY CUBE(tenant_id) "
                + "ORDER BY tenant_id NULLS LAST"));
      }
    }

    @Test void groupingSetsWithGroupingFunction() throws Exception {
      // GROUPING() 区分「NULL 键组」与「聚合总计组」
      try (CrossDb db = coreGoods()) {
        assertEquals(List.of("1,0,2", "2,0,1", "NULL,0,1", "NULL,1,4"), rows(db,
            "SELECT region_id, GROUPING(region_id), COUNT(*) FROM gooddb.products "
                + "GROUP BY GROUPING SETS ((region_id), ()) ORDER BY 2, 1 NULLS LAST"));
      }
    }

    @Test void everyIsBoolAndAlias() throws Exception {
      // PostgreSQL EVERY 聚合
      try (CrossDb db = coreGoods()) {
        assertEquals("false", scalar(db, "SELECT EVERY(active) FROM gooddb.products"));
      }
    }

    @Test void havingWithoutGroupByGlobalFilter() throws Exception {
      // 无 GROUP BY 的全局 HAVING
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db,
            "SELECT COUNT(*) FROM orderdb.orders HAVING SUM(amount) > 30"));
      }
    }

    @Test void groupByExpressionNotInSelectList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "2"), rows(db, "SELECT COUNT(*) FROM orderdb.orders "
            + "GROUP BY user_id ORDER BY user_id"));
      }
    }

    @Test void percentileDiscOrderedSet() throws Exception {
      // 标准 PERCENTILE_DISC(0.5)：升序 [1,5,10,20] 取第 ceil(0.5*4)=2 位 → 5
      try (CrossDb db = core()) {
        assertEquals("5", scalar(db, "SELECT PERCENTILE_DISC(0.5) WITHIN GROUP "
            + "(ORDER BY amount) FROM orderdb.orders"));
      }
    }

    @Test void arrayAggAggregate() throws Exception {
      // 引擎以 "[v1, v2, ...]" 字符串渲染承载 ARRAY_AGG（不支持 ARRAY 值类型透出）
      try (CrossDb db = core()) {
        assertEquals("[1, 2]", scalar(db, "SELECT ARRAY_AGG(id ORDER BY id) FROM userdb.small"));
      }
    }

    @Test void anyValueMySql() throws Exception {
      // ANY_VALUE 语义上非确定，本地取首见非 NULL 值（users 按 id 序扫描 → alice）
      try (CrossDb db = core()) {
        assertEquals("alice", scalar(db, "SELECT ANY_VALUE(name) FROM userdb.users"));
      }
    }

    @Test void modeAggregate() throws Exception {
      // user_id 频次 {1:2, 2:2} 并列，取最小值（Oracle 语义）→ 1
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "SELECT MODE(user_id) FROM orderdb.orders"));
      }
    }

    @Test void countDistinctOverWindow() throws Exception {
      // user_id {1,2,1,2} 去重计数 = 2；上游 EnumerableWindow 会静默丢 DISTINCT，本地 UDAF 修正
      try (CrossDb db = core()) {
        assertEquals(List.of("100,2", "101,2", "102,2", "103,2"), rows(db,
            "SELECT id, COUNT(DISTINCT user_id) OVER () FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void filterInsideWindow() throws Exception {
      // amount>5 过滤后 10+20=30，窗口帧为整分区
      try (CrossDb db = core()) {
        assertEquals(List.of("100,30", "101,30", "102,30", "103,30"), rows(db,
            "SELECT id, SUM(amount) FILTER (WHERE amount > 5) OVER () "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }
  }

  // ---------- 窗口扩展（GROUPS 帧、组内占比、跨分区 LAG） ----------

  @Nested
  @DisplayName("窗口扩展场景")
  class Windows2 {

    @Test
    @Disabled("待支持: 解析器不支持 GROUPS 帧关键字（SQL:2011）；等价改写需把窗口"
        + " ORDER BY 换成派生表 DENSE_RANK 分组列再转 RANGE 帧，待支持")
    void groupsFrameCountsPeerGroups() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,30", "102,25", "103,6"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id GROUPS BETWEEN 1 PRECEDING "
                + "AND CURRENT ROW) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void ratioToReportViaWindowSum() throws Exception {
      // Oracle RATIO_TO_REPORT 等价形态（TPC-H 占比）
      try (CrossDb db = core()) {
        assertEquals(List.of("100,0.2778", "101,0.5556", "102,0.1389", "103,0.0278"),
            rows(db, "SELECT id, ROUND(CAST(amount AS DOUBLE) / SUM(amount) OVER (), 4) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void lagRespectsPartitionBoundary() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL", "101,NULL", "102,100", "103,101"), rows(db,
            "SELECT id, LAG(id) OVER (PARTITION BY user_id ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void maxOverPartitionWithDuplicateKeys() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,15", "101,21", "102,15", "103,21"), rows(db,
            "SELECT id, SUM(amount) OVER (PARTITION BY user_id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void rowGreaterEqualRowConstructor() throws Exception {
      // 行构造器字典序比较（> 形态，与 Comprehensive 的 < 形态互补）
      try (CrossDb db = core()) {
        assertEquals(List.of("101", "102", "103"), rows(db,
            "SELECT o.id FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "WHERE (o.id, u.id) > (100, 2) ORDER BY o.id"));
      }
    }

    @Test void rangeIntervalFrameOnTimestamp() throws Exception {
      // 1 小时回看窗：log1/2 相距 5 分 55 秒同窗（计 2）、log3/4 相距 30 分钟同窗（计 2）
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1", "2,2", "3,1", "4,2"), rows(db,
            "SELECT id, COUNT(*) OVER (ORDER BY ts RANGE BETWEEN INTERVAL '1' HOUR "
                + "PRECEDING AND CURRENT ROW) FROM logdb.logs ORDER BY id"));
      }
    }

    @Test void frameExcludeClause() throws Exception {
      // EXCLUDE NO OTHERS 为缺省恒等形态：累计计数 1..4
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,2", "102,3", "103,4"), rows(db,
            "SELECT id, COUNT(*) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING "
                + "AND CURRENT ROW EXCLUDE NO OTHERS) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void firstValueIgnoreNulls() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,a", "2,a", "3,a"), rows(db,
            "SELECT id, FIRST_VALUE(note) IGNORE NULLS OVER (ORDER BY id) "
            + "FROM pingdb.pings ORDER BY id"));
      }
    }
  }

  // ---------- 子查询扩展（三值逻辑与量化谓词的 NULL 边界） ----------

  @Nested
  @DisplayName("子查询扩展场景")
  class Subqueries2 {

    @Test void notInSubqueryWithNullYieldsEmpty() throws Exception {
      // 经典三值逻辑：NOT IN 遇 NULL → 整体 UNKNOWN → 0 行
      try (CrossDb db = all()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE id NOT IN (SELECT user_id FROM pingdb.pings)"));
      }
    }

    @Test void notInLiteralListWithNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db,
            "SELECT COUNT(*) FROM userdb.users WHERE id NOT IN (2, NULL)"));
      }
    }

    @Test void inLiteralListWithNullKeepsTrue() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db,
            "SELECT COUNT(*) FROM userdb.users WHERE id IN (1, NULL)"));
      }
    }

    @Test void equalsAnyOverEmptySubquery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE id = ANY (SELECT id FROM userdb.users WHERE id > 99)"));
      }
    }

    @Test void notEqualsAllWithNullSubquery() throws Exception {
      // ALL 量化遇到 NULL 比较为 UNKNOWN → 无人通过
      try (CrossDb db = all()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE id <> ALL (SELECT user_id FROM pingdb.pings)"));
      }
    }

    @Test void greaterThanAllQuantified() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db, "SELECT u.name FROM userdb.users u "
            + "WHERE u.id > ALL (SELECT o.user_id FROM orderdb.orders o) ORDER BY u.name"));
      }
    }

    @Test void correlatedScalarCountInSelectList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"), rows(db,
            "SELECT u.name, (SELECT COUNT(*) FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) FROM userdb.users u ORDER BY u.name"));
      }
    }

    @Test void twoLevelNestedExistsCorrelation() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT u.name FROM userdb.users u WHERE EXISTS (SELECT 1 FROM orderdb.orders o "
                + "WHERE o.user_id = u.id AND EXISTS (SELECT 1 FROM credsdb.creds c "
                + "WHERE c.user_id = u.id AND c.tenant_id = 100)) ORDER BY u.name"));
      }
    }

    @Test void singleRowSubqueryMultipleRowsRejected() throws Exception {
      // 标量子查询返回多行 → 错误契约
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT name FROM userdb.users WHERE id = (SELECT user_id FROM orderdb.orders)"));
      }
    }

    @Test void semiJoinWithExtraOuterFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db, "SELECT u.name FROM userdb.users u "
            + "WHERE u.id > 1 AND EXISTS (SELECT 1 FROM orderdb.orders o "
            + "WHERE o.user_id = u.id) ORDER BY u.name"));
      }
    }
  }

  // ---------- JOIN 扩展（非等值、表达式键、反连接等价形态） ----------

  @Nested
  @DisplayName("JOIN 扩展场景")
  class Joins2 {

    @Test void rangeNonEquiJoinFallsBackCorrect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("9", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.amount BETWEEN u.id AND u.id * 10"));
      }
    }

    @Test void joinOnArithmeticExpressionKey() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id + 0 = u.id"));
      }
    }

    @Test void crossJoinWithWhereEqualsInner() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "CROSS JOIN userdb.small s WHERE u.id = s.id"));
      }
    }

    @Test void leftJoinNullFilterAsAntiJoin() throws Exception {
      // LEFT JOIN + IS NULL 反连接等价形态（Bind Join 边界外仍需正确）
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db, "SELECT u.name FROM userdb.users u "
            + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
            + "WHERE o.id IS NULL ORDER BY u.name"));
      }
    }

    @Test void fullJoinOfAggregatedDerivedBothSides() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,NULL,NULL,1", "NULL,NULL,9,1", "1,15,1,1", "2,21,NULL,NULL"),
            rows(db, "SELECT a.user_id, a.s, b.user_id, b.c FROM "
                + "(SELECT user_id, SUM(amount) AS s FROM orderdb.orders GROUP BY user_id) a "
                + "FULL JOIN (SELECT user_id, COUNT(*) AS c FROM pingdb.pings "
                + "GROUP BY user_id) b ON b.user_id = a.user_id "
                + "ORDER BY COALESCE(a.user_id, 0), COALESCE(b.user_id, 0)"));
      }
    }

    @Test void threeWayJoinMixedInnerLeftWithNullTail() throws Exception {
      // 内连接 + 左连接混合链：bob 无事件日志 → 尾部补 NULL
      try (CrossDb db = all()) {
        assertEquals(List.of("alice,1", "bob,NULL", "alice,1", "bob,NULL"),
            rows(db, "SELECT u.name, e.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "LEFT JOIN eventdb.events e ON e.user_id = u.id "
                + "ORDER BY o.id"));
      }
    }
  }

  // ---------- CTE 与派生表扩展 ----------

  @Nested
  @DisplayName("CTE 与派生表扩展场景")
  class Ctes2 {

    @Test void cteInsideSetOperationBranch() throws Exception {
      // CTE 分支 2 行（small 1、2 各产一行常量）+ small 本身 [1,2]
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "1", "1", "2"), rows(db, "WITH t AS "
            + "(SELECT 1 AS x FROM userdb.small) SELECT x FROM t "
            + "UNION ALL SELECT id FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void recursiveTwoColumnProgression() throws Exception {
      // 斐波那契式双列递归（PostgreSQL regression 风格）
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "WITH RECURSIVE f(n, m) AS (VALUES (1, 1) UNION ALL "
            + "SELECT n + m, m + 1 FROM f WHERE m < 4) SELECT COUNT(*) FROM f"));
      }
    }

    @Test void cteReferencedInScalarAndFrom() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("32", scalar(db, "WITH t AS (SELECT SUM(amount) AS s FROM orderdb.orders) "
            + "SELECT (SELECT s FROM t) - (SELECT COUNT(*) FROM orderdb.orders)"));
      }
    }

    @Test void cteWithColumnAliasList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db, "WITH t(x, y) AS "
            + "(SELECT id, name FROM userdb.users) SELECT y FROM t WHERE x = 3"));
      }
    }

    @Test void nestedDerivedWithCorrelatedExists() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db, "SELECT u.name FROM userdb.users u "
            + "WHERE EXISTS (SELECT 1 FROM (SELECT user_id FROM orderdb.orders "
            + "WHERE amount >= 10) d WHERE d.user_id = u.id) ORDER BY u.name"));
      }
    }
  }

  // ---------- 排序分页扩展 ----------

  @Nested
  @DisplayName("排序分页扩展场景")
  class Paging2 {

    @Test void offsetBeyondSizeYieldsEmpty() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users ORDER BY id OFFSET 10 ROWS")
            .isEmpty());
      }
    }

    @Test void fetchZeroRowsYieldsEmpty() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users "
            + "ORDER BY id FETCH FIRST 0 ROWS ONLY").isEmpty());
      }
    }

    @Test void nullsFirstWithDescending() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL", "b", "a"), rows(db,
            "SELECT note FROM pingdb.pings ORDER BY note DESC NULLS FIRST"));
      }
    }

    @Test void multiKeyTieBreakDeterministic() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "102,5", "101,20", "103,1"), rows(db,
            "SELECT id, amount FROM orderdb.orders ORDER BY user_id, amount DESC"));
      }
    }

    @Test void orderByExpressionAlias() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-20", "-10", "-5", "-1"), rows(db,
            "SELECT amount * -1 AS neg FROM orderdb.orders ORDER BY neg"));
      }
    }
  }

  // ---------- NULL 语义扩展 ----------

  @Nested
  @DisplayName("NULL 语义扩展场景")
  class Nulls2 {

    @Test void isNotDistinctFromDirectPredicate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1"), rows(db,
            "SELECT id FROM pingdb.pings WHERE note IS NOT DISTINCT FROM 'a'"));
      }
    }

    @Test void nullArithmeticPropagates() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("NULL", scalar(db,
            "SELECT amount + 1 FROM pingdb.pings WHERE id = 2"));
      }
    }

    @Test void concatNullPropagates() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("NULL", scalar(db,
            "SELECT note || 'x' FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void coalesceKeyOrderingKeepsDeterminism() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2", "3"), rows(db,
            "SELECT id FROM pingdb.pings ORDER BY COALESCE(user_id, 999)"));
      }
    }

    @Test void countStarVersusCountColumn() throws Exception {
      // COUNT(*) 计 NULL 行、COUNT(col) 跳过 NULL（user_id: 1,9,NULL / note: a,b,NULL）
      try (CrossDb db = all()) {
        assertEquals(List.of("3,2,2"), rows(db,
            "SELECT COUNT(*), COUNT(user_id), COUNT(note) FROM pingdb.pings"));
      }
    }

    @Test void distinctTreatsNullAsSingleGroup() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "9", "NULL"), rows(db,
            "SELECT DISTINCT user_id FROM pingdb.pings ORDER BY 1 NULLS LAST"));
      }
    }
  }

  // ---------- 类型与转换扩展 ----------

  @Nested
  @DisplayName("类型与转换扩展场景")
  class Types2 {

    @Test void intDivisionTruncatesTowardZero() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-3,3"), rows(db, "SELECT CAST(-7 / 2 AS INT), "
            + "CAST(7 / 2 AS INT) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void decimalMultiplyKeepsScale() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals("199.80", scalar(db,
            "SELECT price * 2 FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void decimalCastRoundsHalfUp() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1.23", scalar(db, "SELECT CAST(1.2345 AS DECIMAL(10, 2)) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void booleanLiteralComparison() throws Exception {
      try (CrossDb db = coreGoods()) {
        assertEquals("2", scalar(db,
            "SELECT COUNT(*) FROM gooddb.products WHERE active = TRUE"));
      }
    }

    @Test void timestampLiteralComparisonUtc() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE ts > TIMESTAMP '2026-01-15 00:00:00'"));
      }
    }

    @Test void castVarcharTruncatesToLength() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("abc", scalar(db, "SELECT CAST('abcdef' AS VARCHAR(3)) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void extractMinuteSecond() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("4,5"), rows(db, "SELECT EXTRACT(MINUTE FROM ts), "
            + "EXTRACT(SECOND FROM ts) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void floorDateToWeekStartsMonday() throws Exception {
      // 2026-01-15 为周四 → WEEK 截断到周一 2026-01-12
      try (CrossDb db = coreGoods()) {
        assertEquals("2026-01-12", scalar(db, "SELECT CAST(FLOOR(made TO WEEK) AS VARCHAR) "
            + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void nullCastStaysNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("NULL", scalar(db, "SELECT CAST(NULL AS INT) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void modZeroRejected() throws Exception {
      try (CrossDb db = core()) {
        // 常量折叠在本地求值 MOD(1, 0)，以运行期异常拒绝
        assertThrows(Exception.class,
            () -> db.query("SELECT MOD(1, 0) FROM userdb.small"));
      }
    }
  }

  // ---------- 方言改写回归扩展 ----------

  @Nested
  @DisplayName("方言改写回归扩展场景")
  class Dialects2 {

    @Test void timestampDiffWeekQuarterYearUnits() throws Exception {
      // 以列驱动避免常量折叠路径；WEEK 按 7 天整除、QUARTER/YEAR 按完整日历跨越
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT TIMESTAMPDIFF(WEEK, ts, ts + INTERVAL '14' DAY) "
            + "FROM logdb.logs WHERE id = 1"));
        assertEquals("1", scalar(db, "SELECT TIMESTAMPDIFF(QUARTER, ts, ts + INTERVAL '3' MONTH) "
            + "FROM logdb.logs WHERE id = 1"));
        assertEquals("1", scalar(db, "SELECT TIMESTAMPDIFF(YEAR, ts, ts + INTERVAL '12' MONTH) "
            + "FROM logdb.logs WHERE id = 1"));
      }
    }

    @Test void topDistinctSqlServerSyntax() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db, "SELECT DISTINCT TOP 2 user_id "
            + "FROM orderdb.orders ORDER BY user_id"));
      }
    }

    @Test void withTiesMultiKey() throws Exception {
      // 双列键 (user_id, amount) 的首键组 (1,10) 无并列 → 仅一行；
      // WITH TIES 改写要求 ORDER BY 键出现在 SELECT 列表中（改写边界）
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1,10"), rows(db, "SELECT id, user_id, amount "
            + "FROM orderdb.orders ORDER BY user_id ASC, amount DESC "
            + "FETCH FIRST 1 ROW WITH TIES"));
      }
    }

    @Test void notSimilarToOperator() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("carol"), rows(db, "SELECT name FROM userdb.users "
            + "WHERE name NOT SIMILAR TO '[ab]%' ORDER BY name"));
      }
    }

    @Test void decodeNumericWithDefaultBranch() throws Exception {
      // 分支字面量按 CHAR 定长语义填充（'one' → 'one '，标准 SQL 行为），
      // 以 TRIM 去掉定长填充后比对
      try (CrossDb db = core()) {
        assertEquals(List.of("one", "many", "one", "many"), rows(db,
            "SELECT TRIM(DECODE(user_id, 1, 'one', 'many')) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void stringAggWithOrderByDesc() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("carol,bob,alice", scalar(db, "SELECT STRING_AGG(name, ',' "
            + "ORDER BY name DESC) FROM userdb.users"));
      }
    }

    @Test void groupConcatSeparatorClause() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("alice|bob|carol", scalar(db, "SELECT GROUP_CONCAT(name "
            + "SEPARATOR '|') FROM userdb.users"));
      }
    }

    @Test void listaggOverExpression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("ALICE|BOB|CAROL", scalar(db, "SELECT LISTAGG(UPPER(name), '|') "
            + "WITHIN GROUP (ORDER BY UPPER(name)) FROM userdb.users"));
      }
    }
  }

  // ---------- 集合操作扩展（优先级与多重集语义） ----------

  @Nested
  @DisplayName("集合操作扩展场景")
  class SetOps2 {

    @Test void intersectBindsTighterThanExcept() throws Exception {
      // 两条等价改写的对照断言优先级：users{1,2,3}, small{1,2}, orders.user_id{1,2}
      try (CrossDb db = core()) {
        assertTrue(rows(db, "(SELECT id FROM userdb.users EXCEPT "
            + "SELECT user_id FROM orderdb.orders) INTERSECT "
            + "SELECT id FROM userdb.small").isEmpty());
        assertEquals(List.of("3"), rows(db, "SELECT id FROM userdb.users EXCEPT "
            + "(SELECT user_id FROM orderdb.orders INTERSECT SELECT id FROM userdb.small)"));
      }
    }

    @Test void exceptAllMultiplicity() throws Exception {
      // creds{1,2,1,3} EXCEPT ALL users{1,2,3} = {1}
      try (CrossDb db = all()) {
        assertEquals(List.of("1"), rows(db, "SELECT user_id FROM credsdb.creds "
            + "EXCEPT ALL SELECT id FROM userdb.users ORDER BY 1"));
      }
    }

    @Test void nestedParenthesizedUnionAll() throws Exception {
      // 括号分支 [1,2] + [2] + [3,3]
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "2", "3", "3"), rows(db, "(SELECT id FROM userdb.small "
            + "UNION ALL SELECT id FROM userdb.small WHERE id = 2) "
            + "UNION ALL SELECT 3 FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void globalAvgViaSumSumOverShards() throws Exception {
      // 分片全局均值 = ΣSUM / ΣCOUNT（ShardingSphere 归并公式）：(36+35)/(4+3) = 10
      try (CrossDb db = all()) {
        assertEquals("10", scalar(db, "SELECT SUM(s) / SUM(c) FROM ("
            + "SELECT SUM(amount) AS s, COUNT(*) AS c FROM orderdb.orders "
            + "UNION ALL SELECT SUM(quota), COUNT(*) FROM quotasdb.quotas) t"));
      }
    }

    @Test void globalCountMergeAcrossDomains() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("10", scalar(db, "SELECT SUM(c) FROM ("
            + "SELECT COUNT(*) AS c FROM userdb.users "
            + "UNION ALL SELECT COUNT(*) FROM orderdb.orders "
            + "UNION ALL SELECT COUNT(*) FROM pingdb.pings) t"));
      }
    }
  }

  // ---------- 错误契约扩展 ----------

  @Nested
  @DisplayName("错误契约扩展场景")
  class Errors2 {

    @Test void whereNonBooleanRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users WHERE id"));
      }
    }

    @Test void aggregateInWhereRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id FROM userdb.users WHERE COUNT(*) > 1"));
      }
    }

    @Test void nestedAggregateRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT SUM(COUNT(*)) FROM userdb.users"));
      }
    }

    @Test void duplicateCteNameLenientlyAccepted() throws Exception {
      // 同名 CTE 不报错（宽松方言行为），后定义覆盖前定义 → 取 small 的 2 行
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH t AS "
            + "(SELECT id FROM userdb.users), t AS (SELECT id FROM userdb.small) "
            + "SELECT COUNT(*) FROM t"));
      }
    }

    @Test void functionArityMismatchRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT UPPER(name, 'x') FROM userdb.users"));
      }
    }

    @Test void negativeFetchRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id FROM userdb.users FETCH FIRST -1 ROWS ONLY"));
      }
    }

    @Test void distinctWithForeignOrderByRejected() throws Exception {
      // DISTINCT 查询的 ORDER BY 必须取自选择列表
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT DISTINCT user_id FROM orderdb.orders ORDER BY amount"));
      }
    }

    @Test void multiStatementRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT 1; SELECT 2"));
      }
    }
  }

  // ---------- 引擎行为扩展（注释、分号、元数据、safeMode） ----------

  @Nested
  @DisplayName("引擎行为扩展场景")
  class Engine2 {

    @Test void commentsIgnoredEverywhere() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"), rows(db, "SELECT /* 内联 */ id -- 行尾\n"
            + "FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void trailingSemicolonAccepted() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db, "SELECT id FROM userdb.users WHERE id = 2;"));
      }
    }

    @Test void keywordCaseInsensitive() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"), rows(db,
            "select ID from userdb.users order by id"));
      }
    }

    @Test void unionMetadataFromFirstBranch() throws Exception {
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id AS uid FROM userdb.users WHERE id = 1 "
            + "UNION ALL SELECT id FROM userdb.small WHERE id = 2")) {
          assertEquals(1, rs.getMetaData().getColumnCount());
          assertEquals("uid", rs.getMetaData().getColumnLabel(1));
          List<String> out = new ArrayList<>();
          while (rs.next()) {
            out.add(rs.getObject(1).toString());
          }
          assertEquals(List.of("1", "2"), out);
        }
      }
    }

    @Test void safeModeRejectsUnfilteredUnionBranch() throws Exception {
      try (CrossDb db = core().safeMode()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id FROM userdb.users UNION ALL SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void analyzeOnFullJoinReportsBothSources() throws Exception {
      try (CrossDb db = all()) {
        String report = db.analyze("SELECT u.id, e.id FROM userdb.users u "
            + "FULL JOIN eventdb.events e ON e.user_id = u.id");
        assertTrue(report.contains("userdb") && report.contains("eventdb"), report);
      }
    }

    @Test void quotedIdentifierPreservesCase() throws Exception {
      // Lex.MYSQL：引号标识符保留大小写
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db,
            "SELECT `NAME` FROM userdb.users WHERE id = 1"));
      }
    }
  }
}

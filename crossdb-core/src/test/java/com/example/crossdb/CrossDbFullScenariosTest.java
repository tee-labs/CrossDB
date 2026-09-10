package com.example.crossdb;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全场景覆盖测试（第四批）：对标同类系统公开测试集的用例设计，与既有七组测试
 * 互补、不重复，按「先补用例、失败即标记 {@code @Disabled("待支持: ...")} 作为
 * 修复清单」的约定维护，全部按标准语义断言。
 *
 * <p>本批重点：① 本轮引擎修复（方言标量/聚合改写、FILTER+OVER、IGNORE NULLS、
 * 窗口 DISTINCT、ARRAY_AGG 渲染、列名保真、尾分号）的回归面；② 数学/日期函数
 * 全景、双键 CUBE、位聚合、逗号连接、NOT IN 防 NULL 等尚未覆盖的标准场景；
 * ③ 跨库组合管线（窗口×连接、ARRAY_AGG×跨库、分位数×维度、CTE 链）。
 *
 * <p>用例来源参考：PostgreSQL regression（三角函数族、EXTRACT DOW、INTERVAL
 * 运算、recursive CTE）、MySQL 8.0（LOCATE/INSTR/LEFT/RIGHT/SPACE/CHAR/STRCMP、
 * DIV、位运算、STRAIGHT_JOIN、BIT_AND/BIT_OR 聚合）、Oracle（TRANSLATE 删字符、
 * MODE、DATE+n、LAST_DAY）、SQL Server（IIF、LEFT/RIGHT）、SQL:2011/2016 有序集
 * 聚合与 FILTER 子句、TPC-H/DS（条件占比、组内分位数）、Apache Calcite
 * SqlOperatorTest（CUBE/GROUPING SETS 全格）、Trino federated（跨源聚合管线）。
 *
 * <p>时间承载约定：TIMESTAMP 以 epoch millis（Long）透出、按 UTC 墙钟解释，
 * DATE 以 epoch days 承载；涉及时间值的断言经 CAST 比对字面值。
 */
class CrossDbFullScenariosTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
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

  // ---------- 本轮引擎修复的回归面 ----------

  @Nested
  @DisplayName("新改写回归场景")
  class NewRewrites {

    @Test void translateDeletesUnmappedChars() throws Exception {
      // from 多出的字符（to 为空串）删除：保留 1,3,6,7
      try (CrossDb db = core()) {
        assertEquals("1367", scalar(db, "SELECT TRANSLATE('12345678', '2458', '') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void translateEmptyFromKeepsOriginal() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("abc", scalar(db, "SELECT TRANSLATE('abc', '', 'xy') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void soundexHomophonesCompareEqual() throws Exception {
      // Robert 与 Rupert 同音编码 R163
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db, "SELECT SOUNDEX('Robert') = SOUNDEX('Rupert') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void ltrimRtrimOnNullColumnPropagates() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,NULL"), rows(db,
            "SELECT LTRIM(note), RTRIM(note) FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void iifInsideWhereActsAsCase() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM orderdb.orders "
            + "WHERE IIF(amount > 5, 1, 0) = 1"));
      }
    }

    @Test void isnullFallsBackToCoalesceChain() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a", "b", "z"), rows(db,
            "SELECT ISNULL(note, COALESCE(NULL, 'z')) FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void ilikeUnderscoreSingleCharPattern() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db,
            "SELECT name FROM userdb.users WHERE name ILIKE '_LICE'"));
      }
    }

    @Test void notIlikeExcludesCaseInsensitively() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db, "SELECT name FROM userdb.users "
            + "WHERE name NOT ILIKE '%O%' ORDER BY name"));
      }
    }

    @Test void percentileDiscFractionSweep() throws Exception {
      // 升序 [1,5,10,20]：0.25→第 1 位、0.75→第 3 位、1.0→第 4 位
      try (CrossDb db = core()) {
        assertEquals(List.of("1,10,20"), rows(db,
            "SELECT PERCENTILE_DISC(0.25) WITHIN GROUP (ORDER BY amount), "
                + "PERCENTILE_DISC(0.75) WITHIN GROUP (ORDER BY amount), "
                + "PERCENTILE_DISC(1.0) WITHIN GROUP (ORDER BY amount) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void percentileDiscGroupedByCrossDbDimension() throws Exception {
      // alice 额度 {10,5}→第 1 位 5；bob {20,1}→第 1 位 1
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,5", "bob,1"), rows(db,
            "SELECT u.name, PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY o.amount) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void arrayAggRendersBracketList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("[1, 2]", scalar(db, "SELECT ARRAY_AGG(id ORDER BY id) "
            + "FROM userdb.small"));
      }
    }

    @Test void arrayAggSkipsNullElements() throws Exception {
      // Calcite UDAF 管道过滤 NULL 入参（与 LISTAGG 族一致）
      try (CrossDb db = all()) {
        assertEquals("[1, 9]", scalar(db, "SELECT ARRAY_AGG(user_id) FROM pingdb.pings"));
      }
    }

    @Test void arrayAggOverCrossDbJoinGrouped() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,[100, 102]", "bob,[101, 103]"), rows(db,
            "SELECT u.name, ARRAY_AGG(o.id ORDER BY o.id) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name "
                + "ORDER BY u.name"));
      }
    }

    @Test void anyValuePerGroupFirstSeen() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,10", "2,20"), rows(db,
            "SELECT user_id, ANY_VALUE(amount) FROM orderdb.orders "
                + "GROUP BY user_id ORDER BY user_id"));
      }
    }

    @Test void modeTieBreaksSmallestPerGroup() throws Exception {
      // 每组两行并列（{100,102}、{101,103}），取最小值
      try (CrossDb db = core()) {
        assertEquals(List.of("1,100", "2,101"), rows(db,
            "SELECT user_id, MODE(id) FROM orderdb.orders GROUP BY user_id "
                + "ORDER BY user_id"));
      }
    }

    @Test void modeIgnoresNullKeys() throws Exception {
      // pings.user_id {1,9,NULL} 频次并列 1:1，取最小 → 1
      try (CrossDb db = all()) {
        assertEquals("1", scalar(db, "SELECT MODE(user_id) FROM pingdb.pings"));
      }
    }

    @Test void countDistinctWindowWithPartition() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,100,2", "1,200,2", "2,100,1", "3,100,1"), rows(db,
            "SELECT user_id, tenant_id, COUNT(DISTINCT tenant_id) OVER "
                + "(PARTITION BY user_id) FROM credsdb.creds "
                + "ORDER BY user_id, tenant_id"));
      }
    }

    @Test void filterOverCountStar() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,2", "101,2", "102,2", "103,2"), rows(db,
            "SELECT id, COUNT(*) FILTER (WHERE amount > 5) OVER () "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void filterOverAvgDoubleCast() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,7.5", "101,7.5", "102,7.5", "103,7.5"), rows(db,
            "SELECT id, AVG(CAST(amount AS DOUBLE)) FILTER (WHERE user_id = 1) OVER () "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void filterGroupedMinAndMax() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,20"), rows(db, "SELECT MIN(amount) FILTER (WHERE user_id = 1), "
            + "MAX(amount) FILTER (WHERE user_id = 2) FROM orderdb.orders"));
      }
    }

    @Test void lastValueIgnoreNullsWithExplicitFrame() throws Exception {
      // 帧 1 PRECEDING..CURRENT：{a}→a、{a,b}→b、{b,NULL}→b
      try (CrossDb db = all()) {
        assertEquals(List.of("1,a", "2,b", "3,b"), rows(db,
            "SELECT id, LAST_VALUE(note) IGNORE NULLS OVER (ORDER BY id ROWS BETWEEN "
                + "1 PRECEDING AND CURRENT ROW) FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void firstValueIgnoreNullsDescendingLeadingNull() throws Exception {
      // DESC 序首行 note 为 NULL：IGNORE NULLS 越过 NULL 取 'b'；普通形态恒 NULL
      try (CrossDb db = all()) {
        assertEquals(List.of("1,b", "2,b", "3,NULL"), rows(db,
            "SELECT id, FIRST_VALUE(note) IGNORE NULLS OVER (ORDER BY id DESC) "
                + "FROM pingdb.pings ORDER BY id"));
        assertEquals(List.of("1,NULL", "2,NULL", "3,NULL"), rows(db,
            "SELECT id, FIRST_VALUE(note) OVER (ORDER BY id DESC) "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void trailingSemicolonWithWhitespaceAccepted() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db, "SELECT id FROM userdb.users WHERE id = 2 ;  "));
      }
    }

    @Test void semicolonInsideStringLiteralKept() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT id FROM userdb.users WHERE name = 'x;'").isEmpty());
      }
    }

    @Test void exceptMetadataUsesFirstBranchAlias() throws Exception {
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id AS uid FROM userdb.users "
            + "EXCEPT SELECT user_id FROM orderdb.orders")) {
          assertEquals("uid", rs.getMetaData().getColumnLabel(1));
          List<String> out = new ArrayList<>();
          while (rs.next()) {
            out.add(rs.getObject(1).toString());
          }
          assertEquals(List.of("3"), out);
        }
      }
    }

    @Test void unionDistinctMetadataUsesFirstBranchAlias() throws Exception {
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id AS uid FROM userdb.users "
            + "UNION SELECT id FROM userdb.small")) {
          assertEquals("uid", rs.getMetaData().getColumnLabel(1));
          List<String> out = new ArrayList<>();
          while (rs.next()) {
            out.add(rs.getObject(1).toString());
          }
          assertEquals(List.of("1", "2", "3"), out);
        }
      }
    }
  }

  // ---------- 数学函数全景 ----------

  @Nested
  @DisplayName("数学函数全景场景")
  class ScalarMath {

    @Test void trigFamilySweep() throws Exception {
      // PostgreSQL/Calcite 三角函数族：SIN/COS/TAN/ATAN/ATAN2/DEGREES/RADIANS
      try (CrossDb db = core()) {
        assertEquals(List.of("0.0,1.0,0.0,0.7854,0.7854,180.0,3.1416"), rows(db,
            "SELECT ROUND(SIN(0), 4), ROUND(COS(0), 4), ROUND(TAN(0), 4), "
                + "ROUND(ATAN(1), 4), ROUND(ATAN2(1, 1), 4), ROUND(DEGREES(PI()), 1), "
                + "ROUND(RADIANS(180), 4) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void piAndCotangent() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3.141592653589793,0.6421"), rows(db,
            "SELECT PI(), ROUND(COT(1), 4) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void powerFractionalAndSqrtAgree() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1.4142,1.4142"), rows(db,
            "SELECT ROUND(POWER(2, 0.5), 4), ROUND(SQRT(2), 4) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void modFloatOperandKeepsScale() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1.5"), rows(db,
            "SELECT MOD(5.5, 2) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void expEulerConstant() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2.7183", scalar(db, "SELECT ROUND(EXP(1), 4) "
            + "FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- 字符串函数扩展 ----------

  @Nested
  @DisplayName("字符串函数扩展场景")
  class StringFunctions {

    @Test void asciiOfFirstCharacter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("65,97"), rows(db, "SELECT ASCII('A'), ASCII('abc') "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void concatPipeAndFunctionNested() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("abc", scalar(db, "SELECT 'a' || CONCAT('b', 'c') "
            + "FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- 日期时间函数全景 ----------

  @Nested
  @DisplayName("日期时间函数全景场景")
  class DateTimes {

    @Test void extractYearMonthHour() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026,1,3"), rows(db, "SELECT EXTRACT(YEAR FROM ts), "
            + "EXTRACT(MONTH FROM ts), EXTRACT(HOUR FROM ts) FROM logdb.logs WHERE id = 1"));
      }
    }

    @Test void extractDowSundayIsOne() throws Exception {
      // 2026-01-15 为周四；Calcite EXTRACT(DOW) 以周日=1（SQL Server 语义）→ 5
      try (CrossDb db = all()) {
        assertEquals("5", scalar(db, "SELECT EXTRACT(DOW FROM made) "
            + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void timestampaddComposesWithTimestampdiff() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("3", scalar(db, "SELECT TIMESTAMPDIFF(HOUR, ts, "
            + "TIMESTAMPADD(HOUR, 3, ts)) FROM logdb.logs WHERE id = 1"));
      }
    }

    @Test void lastDayOfMonth() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2026-01-31", scalar(db, "SELECT CAST(LAST_DAY(made) AS VARCHAR) "
            + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void timestampdiffSecondsAcrossMonths() throws Exception {
      // 2026-01-02 03:04:05 → 2026-02-03 04:05:06 = 32 天 1 时 1 分 1 秒
      try (CrossDb db = all()) {
        assertEquals("2768461", scalar(db, "SELECT TIMESTAMPDIFF(SECOND, "
            + "(SELECT ts FROM pingdb.pings WHERE id = 1), "
            + "(SELECT ts FROM pingdb.pings WHERE id = 3))"));
      }
    }

    @Test void intervalArithmeticInFilter() throws Exception {
      // +30 分钟后仍晚于 03:45 的只有 Jan3 的两条
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM logdb.logs "
            + "WHERE ts + INTERVAL '30' MINUTE > TIMESTAMP '2026-01-02 03:45:00'"));
      }
    }

    @Test void castVarcharDateRoundTrip() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("true", scalar(db, "SELECT CAST(CAST(made AS VARCHAR) AS DATE) = made "
            + "FROM gooddb.products WHERE id = 10"));
      }
    }
  }

  // ---------- 聚合扩展（CUBE/GROUPING SETS、位聚合、布尔聚合） ----------

  @Nested
  @DisplayName("聚合扩展全景场景")
  class Aggregates3 {

    @Test void cubeTwoKeysFullLattice() throws Exception {
      // TPC-DS 双键 CUBE：4 基础组 + 2×键一汇总 + 2×键二汇总 + 总计
      try (CrossDb db = all()) {
        assertEquals(List.of("100,1,1", "100,2,1", "100,3,1", "100,NULL,3", "200,1,1",
            "200,NULL,1", "NULL,1,2", "NULL,2,1", "NULL,3,1", "NULL,NULL,4"), rows(db,
            "SELECT tenant_id, user_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY CUBE(tenant_id, user_id) ORDER BY 1 NULLS LAST, 2 NULLS LAST"));
      }
    }

    @Test void bitAndBitOrAggregates() throws Exception {
      // MySQL 位聚合：1&2&1&2=0、1|2|1|2=3
      try (CrossDb db = core()) {
        assertEquals(List.of("0,3"), rows(db, "SELECT BIT_AND(user_id), BIT_OR(user_id) "
            + "FROM orderdb.orders"));
      }
    }

    @Test void stddevPopPerGroup() throws Exception {
      // user1 {10,5}→σ=2.5；user2 {20,1}→σ=9.5
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2.5", "2,9.5"), rows(db,
            "SELECT user_id, ROUND(STDDEV_POP(amount), 4) FROM orderdb.orders "
                + "GROUP BY user_id ORDER BY user_id"));
      }
    }

    @Test void groupingSetsThreeAlternatives() throws Exception {
      // (tenant_id) ∪ (user_id) ∪ ()：键一汇总 100→3/200→1、键二汇总 1→2/2→1/3→1、总计 4
      try (CrossDb db = all()) {
        assertEquals(List.of("100,NULL,3", "200,NULL,1", "NULL,1,2", "NULL,2,1",
            "NULL,3,1", "NULL,NULL,4"), rows(db,
            "SELECT tenant_id, user_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY GROUPING SETS ((tenant_id), (user_id), ()) "
                + "ORDER BY 1 NULLS LAST, 2 NULLS LAST"));
      }
    }

    @Test void havingOnCountDistinct() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db, "SELECT user_id FROM orderdb.orders "
            + "GROUP BY user_id HAVING COUNT(DISTINCT id) >= 2 ORDER BY user_id"));
      }
    }

    @Test void conditionalShareOfTotal() throws Exception {
      // TPC-H 条件占比：SUM(>5)/SUM(all) = 30/36
      try (CrossDb db = core()) {
        assertEquals("0.8333", scalar(db,
            "SELECT ROUND(CAST(SUM(CASE WHEN amount > 5 THEN amount END) AS DOUBLE) "
                + "/ SUM(amount), 4) FROM orderdb.orders"));
      }
    }

    @Test void boolAggregatesPerGroupWithNull() throws Exception {
      // 区域 1 {T,F}→false、区域 2 {T}→true、区域 NULL {NULL}→NULL（三值布尔聚合）
      try (CrossDb db = all()) {
        assertEquals(List.of("1,false", "2,true", "NULL,NULL"), rows(db,
            "SELECT region_id, EVERY(active) FROM gooddb.products GROUP BY region_id "
                + "ORDER BY 1 NULLS LAST"));
      }
    }
  }

  // ---------- 窗口扩展（帧变体、跨库窗口、DISTINCT 窗口） ----------

  @Nested
  @DisplayName("窗口扩展全景场景")
  class Windows3 {

    @Test void lagWithExpressionDefault() throws Exception {
      // 首行缺前驱，以表达式 id*100 兜底
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10000", "101,100", "102,101", "103,102"), rows(db,
            "SELECT id, LAG(id, 1, id * 100) OVER (ORDER BY id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void rangeUnboundedFollowingTotalPerRow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,36", "101,36", "102,36", "103,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id RANGE BETWEEN UNBOUNDED PRECEDING "
                + "AND UNBOUNDED FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void rangeIntervalMinuteFrame() throws Exception {
      // 6 分钟回看窗：log2（03:10）覆盖 03:04 起的 log1；log3/log4 相距 30 分钟互不覆盖
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1", "2,2", "3,1", "4,1"), rows(db,
            "SELECT id, COUNT(*) OVER (ORDER BY ts RANGE BETWEEN INTERVAL '6' MINUTE "
                + "PRECEDING AND CURRENT ROW) FROM logdb.logs ORDER BY id"));
      }
    }

    @Test void countDistinctWindowWithSlidingFrame() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,1", "103,1"), rows(db,
            "SELECT id, COUNT(DISTINCT user_id) OVER (PARTITION BY user_id ORDER BY id "
                + "ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void denseRankOverCrossDbJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,2", "102,1", "103,2"), rows(db,
            "SELECT o.id, DENSE_RANK() OVER (ORDER BY u.name) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id"));
      }
    }

    @Test void nthValueFollowsSlidingFrame() throws Exception {
      // 帧 1 PRECEDING..CURRENT 的第 2 行即当前行；首行帧内不足 2 行 → NULL
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL", "101,20", "102,5", "103,1"), rows(db,
            "SELECT id, NTH_VALUE(amount, 2) OVER (ORDER BY id ROWS BETWEEN "
                + "1 PRECEDING AND CURRENT ROW) FROM orderdb.orders ORDER BY id"));
      }
    }
  }

  // ---------- 子查询扩展（防 NULL 守卫、嵌套 IN、标量组合） ----------

  @Nested
  @DisplayName("子查询扩展全景场景")
  class Subqueries3 {

    @Test void notInWithNullGuardClassic() throws Exception {
      // NOT IN 反模式的标准修法：内查询过滤 NULL 后外查询恢复行
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM userdb.users WHERE id NOT IN "
            + "(SELECT user_id FROM pingdb.pings WHERE user_id IS NOT NULL)"));
      }
    }

    @Test void inSubqueryOrderedLimitedInside() throws Exception {
      // 内层 ORDER BY+LIMIT 取最大额度行的 user
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db, "SELECT name FROM userdb.users WHERE id IN "
            + "(SELECT user_id FROM orderdb.orders ORDER BY amount DESC LIMIT 1)"));
      }
    }

    @Test void scalarSubqueryMinusDecimalColumn() throws Exception {
      // 20 - {1.25, NULL, 3.50}：结果保持 DECIMAL(10,2) 精度渲染
      try (CrossDb db = all()) {
        assertEquals(List.of("18.75", "NULL", "16.50"), rows(db,
            "SELECT (SELECT MAX(amount) FROM orderdb.orders) - amount "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void doubleNestedInSubquery() throws Exception {
      // pings{1,9} ∩ orders.user_id{1,2} → users{1}
      try (CrossDb db = all()) {
        assertEquals(List.of("alice"), rows(db, "SELECT name FROM userdb.users "
            + "WHERE id IN (SELECT user_id FROM orderdb.orders WHERE user_id IN "
            + "(SELECT user_id FROM pingdb.pings WHERE user_id IS NOT NULL))"));
      }
    }

    @Test void inSubqueryDistinctDedups() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db, "SELECT name FROM userdb.users "
            + "WHERE id IN (SELECT DISTINCT user_id FROM orderdb.orders) ORDER BY name"));
      }
    }

    @Test void correlatedScalarCountComparedAcrossDb() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice"), rows(db, "SELECT u.name FROM userdb.users u "
            + "WHERE (SELECT COUNT(*) FROM pingdb.pings p WHERE p.user_id = u.id) >= 1 "
            + "ORDER BY u.name"));
      }
    }
  }

  // ---------- JOIN 扩展（逗号连接、OR 条件回退、自连接） ----------

  @Nested
  @DisplayName("JOIN 扩展全景场景")
  class Joins3 {

    @Test void commaJoinImplicitCrossWithWhere() throws Exception {
      // MySQL/PostgreSQL 逗号连接（= INNER JOIN）
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users u, "
            + "orderdb.orders o WHERE u.id = o.user_id"));
      }
    }

    @Test void orOfEquiPairsFallsBackButCorrect() throws Exception {
      // OR 条件不满足 Bind Join 触发条件，回退本地计划，语义须仍正确：
      // u1 命中 user_id 匹配的 {o100,o102} 加上 amount=1 的 o103，u2 命中 {o101,o103}
      try (CrossDb db = core()) {
        assertEquals("5", scalar(db, "SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON u.id = o.user_id OR u.id = o.amount"));
      }
    }

    @Test void threeWayJoinAggregatePipeline() throws Exception {
      // events 仅 user 1 有匹配：o100/o102 各乘 1 行 → SUM=15
      try (CrossDb db = all()) {
        assertEquals(List.of("1,15"), rows(db,
            "SELECT u.id, SUM(o.amount) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN eventdb.events e ON e.user_id = u.id GROUP BY u.id"));
      }
    }

    @Test void crossDbJoinToMultiRowDimension() throws Exception {
      // quotas 中 user1 两行、user2 一行 → 6 行
      try (CrossDb db = all()) {
        assertEquals("6", scalar(db, "SELECT COUNT(*) FROM orderdb.orders o "
            + "JOIN quotasdb.quotas q ON q.user_id = o.user_id"));
      }
    }

    @Test void selfJoinQualifiedSameDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,1", "3,2"), rows(db,
            "SELECT a.id, b.id FROM userdb.users a JOIN userdb.users b "
                + "ON a.id = b.id + 1 ORDER BY a.id"));
      }
    }

    @Test void leftJoinArrayAggAllNullGroupYieldsNull() throws Exception {
      // carol 无订单：组内全 NULL 入参被 UDAF 管道过滤 → 聚合结果 NULL（PG 空组语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,[100, 102]", "bob,[101, 103]", "carol,NULL"), rows(db,
            "SELECT u.name, ARRAY_AGG(o.id ORDER BY o.id) FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name "
                + "ORDER BY u.name"));
      }
    }
  }

  // ---------- 集合操作扩展 ----------

  @Nested
  @DisplayName("集合操作扩展全景场景")
  class SetOps3 {

    @Test void unionDistinctOfGroupedAggregates() throws Exception {
      // 分组聚合结果作为集合操作分支（ShardingSphere 全局归并形态）
      try (CrossDb db = all()) {
        assertEquals(List.of("1,15", "2,21", "100,3", "200,1"), rows(db,
            "(SELECT user_id, SUM(amount) AS s FROM orderdb.orders GROUP BY user_id) "
                + "UNION (SELECT tenant_id, COUNT(*) FROM credsdb.creds "
                + "GROUP BY tenant_id) ORDER BY 1"));
      }
    }

    @Test void exceptWithNegatedProjection() throws Exception {
      // {-10,-20,-5,-1} \ {1,2} = 原 4 行（负值不与事件 id 相交）
      try (CrossDb db = all()) {
        assertEquals(List.of("-20", "-10", "-5", "-1"), rows(db,
            "SELECT amount * -1 FROM orderdb.orders EXCEPT SELECT id "
                + "FROM eventdb.events ORDER BY 1"));
      }
    }

    @Test void intersectAllMultiColumnKeys() throws Exception {
      // creds(user,tenant) ∩ quotas(user,tenant)（多重集语义）：(1,100)(2,100)(1,200) 各 1 次
      try (CrossDb db = all()) {
        assertEquals(List.of("1,100", "1,200", "2,100"), rows(db,
            "SELECT user_id, tenant_id FROM credsdb.creds INTERSECT ALL "
                + "SELECT user_id, tenant_id FROM quotasdb.quotas ORDER BY 1, 2"));
      }
    }
  }

  // ---------- CTE 扩展（递归去重、日期序列、CTE 链） ----------

  @Nested
  @DisplayName("CTE 扩展全景场景")
  class Ctes3 {

    @Test void recursiveUnionDistinctDedups() throws Exception {
      // UNION DISTINCT 语义：{1,2,3}（增量序列无重复，但 DISTINCT 保证幂等）
      try (CrossDb db = core()) {
        assertEquals("6", scalar(db, "WITH RECURSIVE s(n) AS (SELECT 1 UNION "
            + "SELECT n + 1 FROM s WHERE n < 3) SELECT SUM(n) FROM s"));
      }
    }

    @Test void recursiveIntervalDaySeries() throws Exception {
      // DATE + INTERVAL 逐日推进 4 天
      try (CrossDb db = core()) {
        assertEquals(List.of("4,4"), rows(db, "WITH RECURSIVE d(dt) AS "
            + "(SELECT DATE '2026-01-01' UNION ALL SELECT dt + INTERVAL '1' DAY FROM d "
            + "WHERE dt < DATE '2026-01-04') SELECT COUNT(*), COUNT(DISTINCT dt) FROM d"));
      }
    }

    @Test void cteShadowsTableName() throws Exception {
      // CTE 名与已注册表同名时 CTE 优先
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH users AS (SELECT id FROM userdb.small) "
            + "SELECT COUNT(*) FROM users"));
      }
    }

    @Test void chainedThreeCtesAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db,
            "WITH a AS (SELECT id FROM userdb.users WHERE id <= 2), "
                + "b AS (SELECT user_id FROM orderdb.orders WHERE user_id IN (SELECT id FROM a)), "
                + "c AS (SELECT COUNT(*) AS n FROM b) SELECT n FROM c"));
      }
    }

    @Test void cteWindowFilteredThenAggregated() throws Exception {
      // 组内 Top-1 额度再汇总（TPC-H 子查询下推形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,10", "2,20"), rows(db,
            "WITH w AS (SELECT user_id, amount, ROW_NUMBER() OVER (PARTITION BY user_id "
                + "ORDER BY amount DESC) AS rn FROM orderdb.orders) "
                + "SELECT user_id, SUM(amount) FROM w WHERE rn = 1 GROUP BY user_id "
                + "ORDER BY user_id"));
      }
    }
  }

  // ---------- 排序分页扩展 ----------

  @Nested
  @DisplayName("排序分页扩展场景")
  class Paging3 {

    @Test void nullsFirstMultiKeyDescending() throws Exception {
      // user_id ASC NULLS FIRST，再 id DESC：NULL 组（id3）→ 1（id1）→ 9（id2）
      try (CrossDb db = all()) {
        assertEquals(List.of("3", "1", "2"), rows(db,
            "SELECT id FROM pingdb.pings ORDER BY user_id ASC NULLS FIRST, id DESC"));
      }
    }

    @Test void stablePaginationWithUniqueTiebreak() throws Exception {
      // 额度降序唯一化后取第 2-3 行：10(100)、5(102)
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "102"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY amount DESC, id ASC "
                + "OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY"));
      }
    }
  }

  // ---------- 类型与转换扩展 ----------

  @Nested
  @DisplayName("类型与转换扩展场景")
  class Types3 {

    @Test void implicitCompareWithDecimalLiteral() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM orderdb.orders "
            + "WHERE amount > 9.5"));
      }
    }

    @Test void varcharToNumericCastArithmetic() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("43", scalar(db, "SELECT CAST('42' AS INT) + 1 "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void booleanThreeValuedLogic() throws Exception {
      // TRUE AND NULL=UNKNOWN、FALSE AND NULL=FALSE、FALSE OR NULL=UNKNOWN
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,false,NULL"), rows(db, "SELECT TRUE AND NULL, "
            + "FALSE AND NULL, FALSE OR NULL FROM userdb.small LIMIT 1"));
      }
    }

    @Test void doubleAdditionKeepsFloatPrecision() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0.3", scalar(db, "SELECT ROUND(CAST(0.1 AS DOUBLE) "
            + "+ CAST(0.2 AS DOUBLE), 4) FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- NULL 语义扩展 ----------

  @Nested
  @DisplayName("NULL 语义扩展场景")
  class Nulls3 {

    @Test void equalityAgainstNullAlwaysUnknown() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE note = NULL OR note <> NULL"));
      }
    }

    @Test void notInLiteralListAllNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "WHERE id NOT IN (NULL, NULL)"));
      }
    }

    @Test void coalesceWidensTypeScale() throws Exception {
      // COALESCE(DECIMAL(10,2), 0) → DECIMAL(10,2)：0 与 3.5 按列精度渲染
      try (CrossDb db = all()) {
        assertEquals(List.of("1.25", "0.00", "3.50"), rows(db,
            "SELECT COALESCE(amount, 0) FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void distinctTreatsNullPairOnce() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,a", "9,b", "NULL,NULL"), rows(db,
            "SELECT DISTINCT user_id, note FROM pingdb.pings ORDER BY 1 NULLS LAST"));
      }
    }

    @Test void nullBooleanFilterExcludesUnknown() throws Exception {
      // flag {TRUE,NULL,FALSE}：WHERE flag 仅保留 TRUE
      try (CrossDb db = all()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings WHERE flag"));
      }
    }
  }

  // ---------- 引擎行为（元数据、类型化取值、可观测性） ----------

  @Nested
  @DisplayName("引擎行为全景场景")
  class Engine3 {

    @Test void metadataTypeNamesDecimalTimestampBoolean() throws Exception {
      try (CrossDb db = all(); ResultSet rs = db.query(
          "SELECT amount, ts, flag FROM pingdb.pings WHERE id = 1")) {
        assertEquals("DECIMAL", rs.getMetaData().getColumnTypeName(1));
        assertEquals("TIMESTAMP", rs.getMetaData().getColumnTypeName(2));
        assertEquals("BOOLEAN", rs.getMetaData().getColumnTypeName(3));
      }
    }

    @Test void metadataNullabilityFlags() throws Exception {
      try (CrossDb db = core(); ResultSet rs = db.query(
          "SELECT u.id, o.user_id FROM userdb.users u JOIN orderdb.orders o "
              + "ON o.user_id = u.id LIMIT 1")) {
        assertEquals(java.sql.ResultSetMetaData.columnNoNulls,
            rs.getMetaData().isNullable(1));
        assertEquals(java.sql.ResultSetMetaData.columnNullable,
            rs.getMetaData().isNullable(2));
      }
    }

    @Test void findColumnCaseInsensitive() throws Exception {
      try (CrossDb db = core(); ResultSet rs = db.query(
          "SELECT id, name FROM userdb.users LIMIT 1")) {
        assertEquals(1, rs.findColumn("id"));
        assertEquals(1, rs.findColumn("ID"));
      }
    }

    @Test void getStringOnNumericColumn() throws Exception {
      try (CrossDb db = core(); ResultSet rs = db.query(
          "SELECT id FROM orderdb.orders WHERE id = 100")) {
        assertTrue(rs.next());
        assertEquals("100", rs.getString(1));
      }
    }

    @Test void getBigDecimalWithScaleArgument() throws Exception {
      try (CrossDb db = all(); ResultSet rs = db.query(
          "SELECT amount FROM pingdb.pings WHERE id = 1")) {
        assertTrue(rs.next());
        assertEquals(new BigDecimal("1.3"), rs.getBigDecimal(1, 1));
      }
    }

    @Test void getTimestampOnEpochMillisCarrier() throws Exception {
      try (CrossDb db = all(); ResultSet rs = db.query(
          "SELECT ts FROM pingdb.pings WHERE id = 1")) {
        assertTrue(rs.next());
        assertEquals(Timestamp.valueOf("2026-01-02 03:04:05"), rs.getTimestamp(1));
      }
    }

    @Test void analyzeNamesAllThreeSources() throws Exception {
      try (CrossDb db = all()) {
        String report = db.analyze("SELECT COUNT(*) FROM userdb.users u "
            + "JOIN logdb.logs l ON l.user_id = u.id "
            + "JOIN credsdb.creds c ON c.user_id = u.id");
        assertTrue(report.contains("userdb") && report.contains("logdb")
            && report.contains("credsdb"), report);
      }
    }

    @Test void explainShowsBindJoinForCrossDbEquiJoin() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT u.name, o.amount FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1");
        assertTrue(plan.contains("BindJoin"), plan);
      }
    }
  }

  // ---------- 错误契约扩展 ----------

  @Nested
  @DisplayName("错误契约扩展场景")
  class Errors3 {

    @Test void orderByOrdinalOutOfRangeRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users ORDER BY 5"));
      }
    }

    @Test void castInvalidDateStringRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(Exception.class,
            () -> db.query("SELECT CAST('not-a-date' AS DATE) FROM userdb.small"));
      }
    }

    @Test void unknownFunctionRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class,
            () -> db.query("SELECT NO_SUCH_FN(id) FROM userdb.users"));
      }
    }

    @Test void windowOrderByUnknownColumnRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id, SUM(amount) OVER (ORDER BY nope) FROM orderdb.orders"));
      }
    }
  }

  // ---------- 方言扩展候选（预计暂不支持；失败即保持 @Disabled 待支持） ----------

  @Nested
  @DisplayName("方言扩展候选场景（第二批）")
  class DialectCandidates2 {

    @Test void logSingleArgumentPostgres() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1.0", scalar(db, "SELECT ROUND(LOG(EXP(1)), 4) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void locateWithStartPositionMysql() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "SELECT LOCATE('n', 'banana', 3) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void leftRightSubstringFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ab,cd"), rows(db, "SELECT LEFT('abcd', 2), RIGHT('abcd', 2) "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void spaceAndCharFunctions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("[   ],A"), rows(db,
            "SELECT CONCAT('[', SPACE(3), ']'), CHAR(65) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void strcmpMysql() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-1,1,0"), rows(db, "SELECT STRCMP('a', 'b'), STRCMP('b', 'a'), "
            + "STRCMP('a', 'a') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void datePlusIntegerDaysOracle() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2026-01-16", scalar(db, "SELECT CAST(made + 1 AS VARCHAR) "
            + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void bitwiseOperatorFamily() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,7,6"), rows(db, "SELECT 5 & 3, 5 | 3, 5 ^ 3 "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void integerDivOperatorMysql() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db, "SELECT 7 DIV 2 FROM userdb.small LIMIT 1"));
      }
    }

    @Test void fetchFirstPercentRows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db, "SELECT id FROM orderdb.orders "
            + "ORDER BY id FETCH FIRST 50 PERCENT ROWS ONLY"));
      }
    }

    @Test void straightJoinHint() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM userdb.users "
            + "STRAIGHT_JOIN orderdb.orders o ON o.user_id = userdb.users.id"));
      }
    }

    @Test void castBooleanToInteger() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0"), rows(db, "SELECT CAST(TRUE AS INT), CAST(FALSE AS INT) "
            + "FROM userdb.small LIMIT 1"));
      }
    }
  }
}

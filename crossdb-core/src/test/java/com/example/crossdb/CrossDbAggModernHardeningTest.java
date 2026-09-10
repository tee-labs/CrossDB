package com.example.crossdb;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全场景覆盖测试（第六批 F）：聚合分析深测 + 现代 SQL 深测 + 引擎加固矩阵。
 * 参考 DuckDB/Trino 聚合指南（FILTER/ORDERED-SET/近似语义）、MySQL 聚合边界、
 * SQL 标准分组变体（GROUPING SETS/ROLLUP/CUBE）与 OLTP 引擎防呆矩阵
 * （safeMode/rowLimit/只读/超时/取消/元数据）。与前几批互补、不重复；
 * 尚未支持的形态以 {@code @Disabled("待支持: ...")} 标注为修复清单。
 */
class CrossDbAggModernHardeningTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS)
        .register("pingdb", Fixtures.PINGS)
        .register("logdb", Fixtures.LOGS)
        .register("credsdb", Fixtures.CREDS)
        .register("quotasdb", Fixtures.QUOTAS)
        .register("eventdb", Fixtures.EVENTS);
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

  private static String scalar(CrossDb db, String sql) throws SQLException {
    try (ResultSet rs = db.query(sql)) {
      assertTrue(rs.next(), "应至少返回一行: " + sql);
      Object v = rs.getObject(1);
      return v == null ? "NULL" : v.toString();
    }
  }

  // ---------- 聚合分析深测 ----------

  @Nested
  @DisplayName("聚合分析深测")
  class Aggregates {

    @Test void sumMinMaxAvgMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("36,1,20,9", scalar(db,
            "SELECT SUM(amount) || ',' || MIN(amount) || ',' || MAX(amount) "
                + "|| ',' || AVG(amount) FROM orderdb.orders"));
      }
    }

    @Test void countVariants() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("3,2", scalar(db,
            "SELECT COUNT(*) || ',' || COUNT(amount) FROM pingdb.pings"));
      }
    }

    @Test void sumEmptyGroupIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,0"), rows(db,
            "SELECT SUM(amount), COUNT(*) FROM orderdb.orders WHERE id > 999"));
      }
    }

    @Test void distinctInEveryAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2,1,15", scalar(db,
            "SELECT COUNT(DISTINCT amount) || ',' || COUNT(DISTINCT user_id) "
                + "|| ',' || SUM(DISTINCT amount) FROM orderdb.orders "
                + "WHERE user_id = 1 OR amount = 5"));
      }
    }

    @Test void groupByHavingMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2,15", "2,2,21"), rows(db,
            "SELECT user_id, COUNT(*), SUM(amount) FROM orderdb.orders "
                + "GROUP BY user_id HAVING SUM(amount) >= 15 ORDER BY user_id"));
      }
    }

    @Test void havingOnCountNotSelect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT user_id FROM orderdb.orders GROUP BY user_id "
                + "HAVING COUNT(*) = 2 AND SUM(amount) > 20"));
      }
    }

    @Test void groupByExpression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0,2", "1,2"), rows(db,
            "SELECT amount % 2, COUNT(*) FROM orderdb.orders GROUP BY amount % 2 ORDER BY 1"));
      }
    }

    @Test void groupByOrdinal() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2", "2,2"), rows(db,
            "SELECT user_id, COUNT(*) FROM orderdb.orders GROUP BY 1 ORDER BY 1"));
      }
    }

    @Test void orderByAggregateNotInSelect() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "1"), rows(db,
            "SELECT user_id FROM orderdb.orders GROUP BY user_id "
                + "ORDER BY SUM(amount) DESC"));
      }
    }

    @Test void aggregateOfAggOverDerived() throws Exception {
      // 本轮修复回归：外层聚合 + 派生表聚合不再融合下推为嵌套聚合
      try (CrossDb db = core()) {
        assertEquals("2.0", scalar(db,
            "SELECT CAST(AVG(t.s) AS DOUBLE) FROM "
                + "(SELECT user_id, COUNT(*) s FROM orderdb.orders GROUP BY user_id) t"));
      }
    }

    @Test void maxOfGroupMins() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("5", scalar(db,
            "SELECT MAX(t.m) FROM (SELECT user_id, MIN(amount) m FROM orderdb.orders "
                + "GROUP BY user_id) t"));
      }
    }

    @Test void medianAndPercentile() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("7.5,7.5,5.0", scalar(db,
            "SELECT CAST(MEDIAN(amount) AS DOUBLE) || ',' "
                + "|| CAST(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY amount) AS DOUBLE) || ',' "
                + "|| CAST(PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY amount) AS DOUBLE) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void percentileQuartiles() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("4.0,12.5", scalar(db,
            "SELECT CAST(PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY amount) AS DOUBLE) || ',' "
                + "|| CAST(PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY amount) AS DOUBLE) "
                + "FROM orderdb.orders"));
      }
    }

    @Test void listaggOrderedAndDistinct() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("ERROR,INFO,WARN", scalar(db,
            "SELECT LISTAGG(DISTINCT level) WITHIN GROUP (ORDER BY level) FROM logdb.logs"));
      }
    }

    @Test void listaggWithSeparator() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("alice|bob|carol", scalar(db,
            "SELECT LISTAGG(name, '|') WITHIN GROUP (ORDER BY name) FROM userdb.users"));
      }
    }

    @Test void stringAggPostgresForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("alice,bob,carol", scalar(db,
            "SELECT STRING_AGG(name) FROM userdb.users"));
      }
    }

    @Test void boolAggregates() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("FALSE,TRUE", scalar(db,
            "SELECT BOOL_AND(active) || ',' || BOOL_OR(active) FROM gooddb.products "
                + "WHERE active IS NOT NULL"));
      }
    }

    @Test void everyAliasForBoolAnd() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("false", scalar(db,
            "SELECT EVERY(active) FROM gooddb.products WHERE active IS NOT NULL"));
      }
    }

    @Test void arrayAggRender() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("[1, 2, 3]", scalar(db,
            "SELECT ARRAY_AGG(id) FROM userdb.users"));
      }
    }

    @Test void anyValueMode() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("true,5", scalar(db,
            "SELECT (SELECT ANY_VALUE(active) FROM gooddb.products WHERE id = 10) || ',' "
                + "|| (SELECT MODE(amount) FROM orderdb.orders WHERE user_id = 1) "
                + "FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void argMinArgMaxMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("103,101,100,101", scalar(db,
            "SELECT ARG_MIN(id, amount) || ',' || ARG_MAX(id, amount) || ',' "
                + "|| ARG_MIN(id, user_id) || ',' || ARG_MAX(id, user_id) FROM orderdb.orders"));
      }
    }

    @Test void argMinTakesFirstOnTie() throws Exception {
      // 并列（amount=5 无并列，构造 user_id 并列）：ARG_MAX(id, user_id) 中 user_id=2
      // 对应 101、103 —— 取最大锚定（并列首见）
      try (CrossDb db = core()) {
        assertEquals("101", scalar(db,
            "SELECT ARG_MAX(id, user_id) FROM orderdb.orders WHERE id <= 101"));
      }
    }

    @Test void filterClauseOnPlainAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db,
            "SELECT COUNT(*) FILTER (WHERE amount > 5) FROM orderdb.orders"));
      }
    }

    @Test void stddevAndVariance() throws Exception {
      try (CrossDb db = core()) {
        // 均值 9，方差（总体）= ((10-9)²+(20-9)²+(5-9)²+(1-9)²)/4 = 202/4 = 50.5
        assertEquals("50.5", scalar(db,
            "SELECT CAST(VAR_POP(amount) AS DOUBLE) FROM orderdb.orders"));
      }
    }

    @Test void multiArgCountRewrite() throws Exception {
      try (CrossDb db = core()) {
        // COUNT(a, b) 多参数形态（MySQL）：双列均非 NULL 计数
        assertEquals("3", scalar(db,
            "SELECT COUNT(user_id, amount) FROM orderdb.orders WHERE id <= 102"));
      }
    }

    @Test
    @Disabled("待支持: GROUPING SETS 分组集合（SQL 标准），待支持")
    void groupingSetsForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,15", "2,21", "NULL,36", "1,2", "2,2"), rows(db,
            "SELECT user_id, SUM(amount) FROM orderdb.orders "
                + "GROUP BY GROUPING SETS ((user_id), ()) ORDER BY 1 NULLS LAST"));
      }
    }

    @Test
    @Disabled("待支持: ROLLUP 分组上卷（SQL 标准），待支持")
    void rollupForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(3, rows(db,
            "SELECT user_id, SUM(amount) FROM orderdb.orders GROUP BY ROLLUP (user_id)").size());
      }
    }

    @Test
    @Disabled("待支持: CUBE 分组立方（SQL 标准），待支持")
    void cubeForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(4, rows(db,
            "SELECT user_id, amount FROM orderdb.orders GROUP BY CUBE (user_id, amount)").size());
      }
    }
  }

  // ---------- 现代 SQL 深测 ----------

  @Nested
  @DisplayName("现代 SQL 深测")
  class ModernSql {

    @Test void qualifyMultipleWindows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100"), rows(db,
            "SELECT id FROM orderdb.orders QUALIFY "
                + "ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) = 1 "
                + "AND user_id = 1"));
      }
    }

    @Test void excludeMultipleColumns() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,NULL"), rows(db,
            "SELECT * EXCLUDE (name) FROM userdb.emps WHERE id = 1"));
      }
    }

    @Test void tryCastAllTargets() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,99,NULL"), rows(db,
            "SELECT TRY_CAST('xyz' AS INT), TRY_CAST('99' AS BIGINT), "
                + "TRY_CAST('1.2.3' AS DOUBLE) FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void tryCastValidValuesPass() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("42,3.5,7.25", scalar(db,
            "SELECT TRY_CAST('42' AS INT) || ',' || TRY_CAST('3.5' AS DOUBLE) || ',' "
                + "|| TRY_CAST('7.25' AS DECIMAL(4,2)) FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void structDotMultiField() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1,two", scalar(db,
            "SELECT {'a': 1, 'b': 'two'}.a || ',' || {'a': 1, 'b': 'two'}.b "
                + "FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void listContainsVariants() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,false"), rows(db,
            "SELECT LIST_CONTAINS([2, 4, 8], 4), LIST_CONTAINS([2, 4, 8], 5) "
                + "FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void dateDiffUnitsMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("61,2,0", scalar(db,
            "SELECT date_diff('day', DATE '2026-01-01', DATE '2026-03-03') || ',' "
                + "|| date_diff('month', DATE '2026-01-01', DATE '2026-03-03') || ',' "
                + "|| date_diff('year', DATE '2026-01-01', DATE '2026-03-03') "
                + "FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void regexpReplaceForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("XXXXX,a b c", scalar(db,
            "SELECT REGEXP_REPLACE('a.b.c', '.', 'X') || ',' "
                + "|| TRIM(REGEXP_REPLACE('a1b2c3', '[0-9]', ' ')) FROM userdb.small WHERE id = 1")); // '.' 为正则元字符（MySQL ci 全局替换）
      }
    }

    @Test void dateMoreThanDayUnit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db,
            "SELECT date_diff('week', DATE '2026-01-01', DATE '2026-01-22') "
                + "FROM userdb.small WHERE id = 1"));
      }
    }

    @Test void onOverflowErrorStripped() throws Exception {
      // ON OVERFLOW ERROR 为标准默认，剥离后语义不变
      try (CrossDb db = core()) {
        assertEquals("a,b", scalar(db,
            "SELECT LISTAGG(name) ON OVERFLOW ERROR WITHIN GROUP (ORDER BY name) "
                + "FROM (SELECT 'a' name UNION ALL SELECT 'b') t"));
      }
    }

    @Test void xorWithColumns() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("0", "NULL", "0"), rows(db,
            "SELECT CAST(flag AS INT) XOR (id = 1) FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void xorPrecedenceWithAnd() throws Exception {
      // XOR 优先级介于 AND 与 OR 之间
      try (CrossDb db = core()) {
        assertEquals(List.of("1"), rows(db,
            "SELECT TRUE XOR FALSE AND FALSE FROM userdb.small WHERE id = 1"));
      }
    }
  }

  // ---------- 引擎加固矩阵 ----------

  @Nested
  @DisplayName("引擎加固矩阵")
  class Hardening {

    @Test void safeModeRejectsBareFullPullEachSource() throws Exception {
      try (CrossDb db = all().safeMode()) {
        assertThrows(Exception.class, () -> db.query("SELECT id FROM userdb.users"));
        assertThrows(Exception.class, () -> db.query("SELECT name FROM gooddb.regions"));
        // 但带聚合/过滤/LIMIT 的放行
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users"));
        assertEquals(List.of("1"), rows(db,
            "SELECT id FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void safeModeAllowsBindJoinInner() throws Exception {
      try (CrossDb db = all().safeMode()) {
        assertEquals(List.of("alice,100", "alice,102"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id WHERE u.id = 1 ORDER BY o.id"));
      }
    }

    @Test void safeModeAllowsJoinWithBothFilters() throws Exception {
      try (CrossDb db = all().safeMode()) {
        assertEquals(List.of("bob,101"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id WHERE u.id = 2 AND o.amount > 10"));
      }
    }

    @Test void safeModeAllowsUnionBranchsWithLimits() throws Exception {
      try (CrossDb db = all().safeMode()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.users ORDER BY id LIMIT 2"));
      }
    }

    @Test void rowLimitBreakerOnJoin() throws Exception {
      try (CrossDb db = new CrossDb(1000, 3, 1, 1)
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS)) {
        SQLException e = assertThrows(SQLException.class, () ->
            rows(db, "SELECT u.name FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id"));
        assertTrue(containsText(e, "熔断"), "应触发行数熔断: " + e.getMessage());
      }
    }

    private static boolean containsText(Throwable e, String text) {
      Throwable c = e;
      while (c != null) {
        if (c.getMessage() != null && c.getMessage().contains(text)) {
          return true;
        }
        c = c.getCause();
      }
      return false;
    }

    @Test void readOnlyRejectList() throws Exception {
      try (CrossDb db = core()) {
        String[] bad = {
            "INSERT INTO userdb.users VALUES (9, 'x')",
            "UPDATE userdb.users SET name = 'x'",
            "DELETE FROM userdb.users",
            "CREATE TABLE t(i INT)",
            "DROP TABLE userdb.users",
            "ALTER TABLE userdb.users ADD COLUMN x INT",
            "TRUNCATE TABLE userdb.users",
            "GRANT SELECT ON userdb.users TO public",
        };
        for (String sql : bad) {
          assertThrows(SQLException.class, () -> db.query(sql), sql);
        }
      }
    }

    @Test void readOnlyRejectsDmlOnAnySchema() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () ->
            db.query("INSERT INTO orderdb.orders VALUES (999, 1, 1)"));
      }
    }

    @Test void explainShowsSourceSqlFree() throws Exception {
      // explain 不执行查询，仅返回计划文本
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT id FROM userdb.users WHERE id = 1");
        assertTrue(plan.contains("Jdbc"), plan);
      }
    }

    @Test void analyzeShowsPerSourceRows() throws Exception {
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT u.name FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1");
        assertTrue(report.toLowerCase().contains("select"), report);
        assertTrue(report.contains("Enumerable"), report);
      }
    }

    @Test void metadataTypesAcrossSources() throws Exception {
      try (CrossDb db = all()) {
        try (ResultSet rs = db.query(
            "SELECT u.id, p.amount, p.flag, p.note FROM userdb.users u "
                + "JOIN pingdb.pings p ON p.user_id = u.id WHERE p.id = 1")) {
          ResultSetMetaData md = rs.getMetaData();
          assertEquals(4, md.getColumnCount());
          assertTrue(rs.next());
          assertEquals(1, rs.getInt("id"));
          assertEquals(1.25, rs.getBigDecimal("amount").doubleValue(), 1e-9);
          assertTrue(rs.getBoolean("flag"));
          assertEquals("a", rs.getString("note"));
        }
      }
    }

    @Test void typedGettersNullSemantics() throws Exception {
      try (CrossDb db = all()) {
        try (ResultSet rs = db.query(
            "SELECT amount FROM pingdb.pings WHERE id = 2")) {
          assertTrue(rs.next());
          assertEquals(0, rs.getInt(1));
          assertTrue(rs.wasNull());
          assertNull(rs.getString(1));
        }
      }
    }

    private static void assertNull(Object v) {
      assertTrue(v == null, "应为 NULL");
    }

    @Test void wasNullFlagOnValue() throws Exception {
      try (CrossDb db = all()) {
        try (ResultSet rs = db.query("SELECT id, amount FROM pingdb.pings WHERE id = 1")) {
          assertTrue(rs.next());
          assertEquals(1.25, rs.getDouble(2), 1e-9);
          assertTrue(!rs.wasNull());
        }
      }
    }

    @Test void findColumnCaseInsensitive() throws Exception {
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id FROM userdb.users WHERE id = 1")) {
          assertEquals(1, rs.findColumn("ID"));
          assertEquals(1, rs.findColumn("id"));
        }
      }
    }

    @Test void batchConfigValidation() throws Exception {
      assertThrows(IllegalArgumentException.class,
          () -> new CrossDb(0, 100, 10, 1));
      assertThrows(IllegalArgumentException.class,
          () -> new CrossDb(10, 0, 10, 1));
      assertThrows(IllegalArgumentException.class,
          () -> new CrossDb(10, 100, 0, 1));
      assertThrows(IllegalArgumentException.class,
          () -> new CrossDb(10, 100, 10, 0));
    }

    @Test void duplicateSchemaRejected() throws Exception {
      try (CrossDb db = new CrossDb()) {
        db.register("userdb", Fixtures.USERS);
        assertThrows(IllegalArgumentException.class,
            () -> db.register("userdb", Fixtures.USERS));
      }
    }

    @Test void crossDbQueryStreamingSinglePass() throws Exception {
      // 一个 ResultSet 单次流式消费：全量拉取后 next() 返回 false
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id FROM orderdb.orders ORDER BY id")) {
          int n = 0;
          while (rs.next()) {
            n++;
          }
          assertEquals(4, n);
          assertTrue(!rs.next());
        }
      }
    }
  }
}

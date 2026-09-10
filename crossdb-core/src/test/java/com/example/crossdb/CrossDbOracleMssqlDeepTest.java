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
 * 全场景覆盖测试（第六批 E）：Oracle / SQL Server 方言深测。参考 Oracle SQL
 * Reference（DECODE/NVL/TO_CHAR/INSTR/日期函数族/CONNECT BY/旧式外连接）与
 * SQL Server 文档（TOP/ISNULL/IIF/STRING 函数）：条件表达式矩阵、日期函数
 * 矩阵、层次查询变体、(+) 外连接变体、TOP 形态与字符串函数补充。
 * 与前几批互补、不重复；尚未支持的形态以 {@code @Disabled("待支持: ...")} 标注。
 */
class CrossDbOracleMssqlDeepTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS)
        .register("eventdb", Fixtures.EVENTS)
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

  private static List<String> row1(CrossDb db, String expr) throws SQLException {
    return rows(db, "SELECT " + expr + " FROM userdb.small LIMIT 1");
  }

  // ---------- 条件表达式（Oracle / SQL Server） ----------

  @Nested
  @DisplayName("Oracle/SS 条件表达式")
  class Conditionals {

    @Test void decodeNumericPairs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("mid,mid,big"), row1(db,
            "TRIM(DECODE(1, 2, 'big', 1, 'mid', 'small')) || ',' "
                + "|| TRIM(DECODE(2, 2, 'mid', 'small')) || ',' "
                + "|| TRIM(DECODE(9, 2, 'mid', 'big'))"));
      }
    }

    @Test void decodeNullMatch() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("hit,miss"), row1(db,
            "TRIM(DECODE(NULL, NULL, 'hit', 'miss')) || ',' "
                + "|| TRIM(DECODE(NULL, 1, 'hit', 'miss'))"));
      }
    }

    @Test void decodeWithExpressionSearch() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("one,1"), rows(db,
            "SELECT TRIM(DECODE(amount, 10, 'one', 'many')), TRIM(DECODE(id, 100, '1', 'x')) "
                + "FROM orderdb.orders WHERE id = 100"));
      }
    }

    @Test void nvl2Forms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("yes,no"), row1(db,
            "TRIM(NVL2(1, 'yes', 'no')), TRIM(NVL2(NULL, 'yes', 'no'))"));
      }
    }

    @Test void nvlNumericAndString() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("42,42,fallback"), row1(db,
            "NVL(NULL, 42), NVL(42, 0), NVL(NULL, 'fallback')"));
      }
    }

    @Test void iifNestedThreeLevel() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("mid"), row1(db,
            "TRIM(IIF(1 > 2, 'high', IIF(1 = 1, 'mid', 'low')))"));
      }
    }

    @Test void isnullInWhere() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small WHERE ISNULL(NULL, 'x') = 'x' ORDER BY id"));
      }
    }
  }

  // ---------- 日期函数矩阵（Oracle） ----------

  @Nested
  @DisplayName("Oracle 日期函数矩阵")
  class OracleDates {

    @Test void addMonthsMatrix() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-04-15,2025-10-15,2026-02-15"), rows(db,
            "SELECT (SELECT CAST(ADD_MONTHS(made, 3) AS VARCHAR) FROM gooddb.products WHERE id = 10) a, "
                + "(SELECT CAST(ADD_MONTHS(made, -3) AS VARCHAR) FROM gooddb.products WHERE id = 10) b, "
                + "(SELECT CAST(ADD_MONTHS(made, 1) AS VARCHAR) FROM gooddb.products WHERE id = 10) c "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void monthsBetweenSigns() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2.0,-2.0,0"), rows(db,
            "SELECT (SELECT CAST(MONTHS_BETWEEN(DATE '2026-03-15', DATE '2026-01-15') AS DOUBLE) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(MONTHS_BETWEEN(DATE '2026-01-15', DATE '2026-03-15') AS DOUBLE) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(MONTHS_BETWEEN(DATE '2026-01-15', DATE '2026-01-15') AS INT) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void lastDayEdgeMonthEnd() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-31,2026-01-31"), rows(db,
            "SELECT (SELECT CAST(LAST_DAY(DATE '2026-01-31') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(LAST_DAY(DATE '2026-01-01') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nextDayAllWeekdays() throws Exception {
      // 2026-01-15 是星期四：各目标星期
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-16,2026-01-17,2026-01-18,2026-01-19,2026-01-20,2026-01-21,2026-01-22"),
            rows(db,
                "SELECT (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 6) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 7) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 1) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 2) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 3) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 4) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) || ',' "
                    + "|| (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 5) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) "
                    + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nextDayAbbreviations() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-19,2026-01-19"), rows(db,
            "SELECT (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 'MON') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(NEXT_DAY(DATE '2026-01-15', 'monday') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void dateArithRange() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-14,2026-01-16,2026-01-10"), rows(db,
            "SELECT (SELECT CAST(made - 1 AS VARCHAR) FROM gooddb.products WHERE id = 10), "
                + "(SELECT CAST(made + 1 AS VARCHAR) FROM gooddb.products WHERE id = 10), "
                + "(SELECT CAST(made - 5 AS VARCHAR) FROM gooddb.products WHERE id = 10) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void toCharFullPattern() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-02 03:04:05"), rows(db,
            "SELECT TO_CHAR(TIMESTAMP '2026-01-02 03:04:05', 'YYYY-MM-DD HH24:MI:SS') "
                + "FROM gooddb.regions WHERE region_id = 1"));
      }
    }

    @Test void instrWithStart() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,4,0"), row1(db,
            "INSTR('banana', 'an'), INSTR('banana', 'an', 3), INSTR('banana', 'an', 5)"));
      }
    }
  }

  // ---------- CONNECT BY 层次查询变体 ----------

  @Nested
  @DisplayName("CONNECT BY 层次查询变体")
  class ConnectBy {

    @Test void fullTreeFromRoot() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "4"), rows(db,
            "SELECT id FROM userdb.emps START WITH mgr_id IS NULL "
                + "CONNECT BY NOCYCLE PRIOR id = mgr_id ORDER BY id"));
      }
    }

    @Test void subtreeFromMidNode() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "4"), rows(db,
            "SELECT id FROM userdb.emps START WITH id = 2 "
                + "CONNECT BY NOCYCLE PRIOR id = mgr_id ORDER BY id"));
      }
    }

    @Test void reversedClauseOrder() throws Exception {
      // Oracle 允许 CONNECT BY 在 START WITH 之前
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "4"), rows(db,
            "SELECT id FROM userdb.emps CONNECT BY NOCYCLE PRIOR id = mgr_id "
                + "START WITH mgr_id IS NULL ORDER BY id"));
      }
    }

    @Test void withoutNocycleKeyword() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "4"), rows(db,
            "SELECT id FROM userdb.emps START WITH mgr_id IS NULL "
                + "CONNECT BY PRIOR id = mgr_id ORDER BY id"));
      }
    }

    @Test void reversedPriorSide() throws Exception {
      // mgr_id = PRIOR id 等价形态
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "4"), rows(db,
            "SELECT id FROM userdb.emps START WITH mgr_id IS NULL "
                + "CONNECT BY mgr_id = PRIOR id ORDER BY id"));
      }
    }

    @Test void withTableAlias() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "4"), rows(db,
            "SELECT e.id FROM userdb.emps e START WITH e.id = 2 "
                + "CONNECT BY NOCYCLE PRIOR e.id = e.mgr_id ORDER BY e.id"));
      }
    }

    @Test void withWhereFilter() throws Exception {
      // WHERE 为层次后过滤（Oracle 语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "4"), rows(db,
            "SELECT id FROM userdb.emps WHERE id <> 3 START WITH mgr_id IS NULL "
                + "CONNECT BY NOCYCLE PRIOR id = mgr_id ORDER BY id"));
      }
    }

    @Test void withProjectionOnColumns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,ceo", "2,ann", "4,cid"), rows(db,
            "SELECT id, name FROM userdb.emps WHERE id <> 3 START WITH mgr_id IS NULL "
                + "CONNECT BY NOCYCLE PRIOR id = mgr_id ORDER BY id"));
      }
    }

    @Test void levelPseudocolumn() throws Exception {
      // LEVEL 伪列由 CONNECT BY → 递归 CTE 改写的 level 列承载：ceo=1，ann/ben=2，cid=3
      try (CrossDb db = core()) {
        assertEquals(List.of("1,1", "2,2", "3,2", "4,3"), rows(db,
            "SELECT id, LEVEL FROM userdb.emps START WITH mgr_id IS NULL "
                + "CONNECT BY NOCYCLE PRIOR id = mgr_id ORDER BY id"));
      }
    }
  }

  // ---------- 旧式 (+) 外连接变体 ----------

  @Nested
  @DisplayName("Oracle (+) 旧式外连接变体")
  class OracleOuter {

    @Test void plusOnRightSide() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("3,NULL"), rows(db,
            "SELECT u.id, e.id FROM userdb.users u, eventdb.events e "
                + "WHERE u.id = e.user_id(+) AND u.id = 3"));
      }
    }

    @Test void plusOnLeftSideMeansRightJoin() throws Exception {
      // (+) 在左表列上 = 右外连接（未匹配内表行保留时左侧补 NULL）
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1", "NULL,2"), rows(db,
            "SELECT u.id, e.id FROM userdb.users u, eventdb.events e "
                + "WHERE u.id(+) = e.user_id AND e.id <= 2 ORDER BY e.id"));
      }
    }

    @Test void plusWithExtraAndConditions() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2,NULL", "3,NULL"), rows(db,
            "SELECT u.id, e.id FROM userdb.users u, eventdb.events e "
                + "WHERE u.id = e.user_id(+) AND u.id >= 2 AND e.id IS NULL ORDER BY u.id"));
      }
    }

    @Test void plusMultipleMatches() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,100", "1,102"), rows(db,
            "SELECT u.id, o.id FROM userdb.users u, orderdb.orders o "
                + "WHERE u.id = o.user_id(+) AND u.id = 1 ORDER BY o.id"));
      }
    }

    @Test void plusCompositeKey() throws Exception {
      // 复合键双条件 (+)：creds LEFT JOIN quotas USING 复合键
      try (CrossDb db = core().register("credsdb", Fixtures.CREDS)
          .register("quotasdb", Fixtures.QUOTAS)) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20", "c1,NULL"), rows(db,
            "SELECT c.login, q.quota FROM credsdb.creds c, quotasdb.quotas q "
                + "WHERE c.user_id = q.user_id(+) AND c.tenant_id = q.tenant_id(+) "
                + "ORDER BY c.login"));
      }
    }
  }

  // ---------- SQL Server TOP 形态 ----------

  @Nested
  @DisplayName("SQL Server TOP 形态")
  class SqlServerTop {

    @Test void topWithOrderBy() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "102"), rows(db,
            "SELECT TOP 2 id FROM orderdb.orders ORDER BY amount, id"));
      }
    }

    @Test void topWithoutOrderBy() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(2, rows(db, "SELECT TOP 2 id FROM orderdb.orders").size());
      }
    }

    @Test void topParenthesized() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT TOP (2) id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void topDistinctKeyword() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT DISTINCT TOP 2 user_id FROM orderdb.orders ORDER BY user_id"));
      }
    }

    @Test void topPercent() throws Exception {
      // TOP 25 PERCENT：4 行 × 25% 向上取整 = 1 行
      try (CrossDb db = core()) {
        assertEquals(List.of("100"), rows(db,
            "SELECT TOP 25 PERCENT id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void topWithTies() throws Exception {
      // TOP 1 WITH TIES：user_id 最小值组（=1）并列行 100、102 全保留
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "102,1"), rows(db,
            "SELECT TOP 1 WITH TIES id, user_id FROM orderdb.orders ORDER BY user_id"));
      }
    }
  }

  // ---------- 字符串函数补充（SQL Server / Oracle） ----------

  @Nested
  @DisplayName("字符串函数补充")
  class StringSupplement {

    @Test void charindexLikePosition() throws Exception {
      // POSITION 形态已覆盖；这里验证 INSTR 起点边界
      try (CrossDb db = core()) {
        assertEquals(List.of("0,1"), row1(db,
            "INSTR('abc', 'x', 1), INSTR('abc', 'a', 1)"));
      }
    }

    @Test void substrPositiveOnly() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ban,na"), row1(db,
            "SUBSTRING('banana' FROM 1 FOR 3), SUBSTRING('banana' FROM 5)"));
      }
    }

    @Test void charFuncMultiCodepoint() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("A"), row1(db, "CHAR(65)"));
      }
    }

    @Test void quotientAndRemainder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,1,-3,-1"), row1(db,
            "7 DIV 2, MOD(7, 2), -7 DIV 2, MOD(-7, 2)"));
      }
    }
  }
}

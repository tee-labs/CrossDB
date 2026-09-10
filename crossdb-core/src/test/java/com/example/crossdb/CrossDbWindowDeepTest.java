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
 * 全场景覆盖测试（第六批 B）：窗口函数深测。参考 PostgreSQL 窗口函数文档用例、
 * Oracle 分析函数指南与 DuckDB 窗口帧语义，覆盖排名族、聚合窗口、导航族
 * （LAG/LEAD/FIRST_VALUE/LAST_VALUE/NTH_VALUE）、帧子句（ROWS/RANGE/GROUPS ×
 * 边界组合）、WINDOW 命名复用、窗口与表达式/过滤组合；与前几批互补、不重复。
 * 尚未支持的形态以 {@code @Disabled("待支持: ...")} 标注为修复清单。
 */
class CrossDbWindowDeepTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("logdb", Fixtures.LOGS)
        .register("gooddb", Fixtures.GOODS)
        .register("pingdb", Fixtures.PINGS);
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

  // ---------- 排名族 ----------

  @Nested
  @DisplayName("排名函数族")
  class Ranking {

    @Test void rowNumberGlobalAndPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,2", "103,2"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
        assertEquals(List.of("100,1", "101,2", "102,3", "103,4"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void denseRankNoGaps() throws Exception {
      try (CrossDb db = core()) {
        // amount: 1,5,10,20 → DENSE_RANK 无跳号
        assertEquals(List.of("103,1", "102,2", "100,3", "101,4"), rows(db,
            "SELECT id, DENSE_RANK() OVER (ORDER BY amount) FROM orderdb.orders "
                + "ORDER BY amount"));
      }
    }

    @Test void rankTiesShareRank() throws Exception {
      try (CrossDb db = core()) {
        // user_id: 1,1,2,2 → 两名并列后跳号
        assertEquals(List.of("100,1", "102,1", "101,3", "103,3"), rows(db,
            "SELECT id, RANK() OVER (ORDER BY user_id) FROM orderdb.orders "
                + "ORDER BY user_id, id"));
      }
    }

    @Test void percentRankAndCumeDistEquivalents() throws Exception {
      try (CrossDb db = core()) {
        // PERCENT_RANK = (RANK-1)/(COUNT-1)：amount 升序 4 行
        assertEquals(List.of("103,0.0", "102,0.3333", "100,0.6667", "101,1.0"), rows(db,
            "SELECT id, ROUND(PERCENT_RANK() OVER (ORDER BY amount), 4) "
                + "FROM orderdb.orders ORDER BY amount"));
      }
    }

    @Test void cumeDistEquivalent() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103,0.25", "102,0.5", "100,0.75", "101,1.0"), rows(db,
            "SELECT id, ROUND(CUME_DIST() OVER (ORDER BY amount), 4) "
                + "FROM orderdb.orders ORDER BY amount"));
      }
    }

    @Test void ntileBuckets() throws Exception {
      try (CrossDb db = core()) {
        // 4 行分 2 桶：前 ceil(4/2)-1 桶多一行 → 1,1,2,2
        assertEquals(List.of("100,1", "101,1", "102,2", "103,2"), rows(db,
            "SELECT id, NTILE(2) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void rankOverCrossDbJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,1"), rows(db,
            "SELECT u.name, RANK() OVER (ORDER BY SUM(o.amount) DESC) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }
  }

  // ---------- 聚合窗口 ----------

  @Nested
  @DisplayName("聚合窗口")
  class AggregateWindows {

    @Test void runningSumRowsUnboundedPreceding() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,30", "102,35", "103,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void partitionSumNoOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,15", "101,21", "102,15", "103,21"), rows(db,
            "SELECT id, SUM(amount) OVER (PARTITION BY user_id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void movingAverageTwoPreceding() throws Exception {
      try (CrossDb db = core()) {
        // (10)=10, (10+20)/2=15, (10+20+5)/3=11.667, (20+5+1)/3=8.667
        assertEquals(List.of("100,10.0", "101,15.0", "102,11.6667", "103,8.6667"), rows(db,
            "SELECT id, ROUND(AVG(CAST(amount AS DOUBLE)) OVER "
                + "(ORDER BY id ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), 4) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void minMaxOverPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,5", "101,1", "102,5", "103,1"), rows(db,
            "SELECT id, MIN(amount) OVER (PARTITION BY user_id) FROM orderdb.orders "
                + "ORDER BY id"));
        assertEquals(List.of("100,10", "101,20", "102,10", "103,20"), rows(db,
            "SELECT id, MAX(amount) OVER (PARTITION BY user_id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void countOverEntireSet() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,4", "101,4", "102,4", "103,4"), rows(db,
            "SELECT id, COUNT(*) OVER () FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void sumOverRowsBetweenBounds() throws Exception {
      try (CrossDb db = core()) {
        // 窗口 [1 preceding, 1 following]
        assertEquals(List.of("100,30", "101,35", "102,26", "103,6"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING "
                + "AND 1 FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void sumOverRowsUnboundedBoth() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,36", "101,36", "102,36", "103,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING "
                + "AND UNBOUNDED FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void sumOverRowsFollowingOnly() throws Exception {
      try (CrossDb db = core()) {
        // 窗口 [current, unbounded following]
        assertEquals(List.of("100,36", "101,26", "102,6", "103,1"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS BETWEEN CURRENT ROW "
                + "AND UNBOUNDED FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void rangeFrameNumericPeerGroups() throws Exception {
      try (CrossDb db = core()) {
        // RANGE 默认 [unbounded preceding, current]：按 user_id 排序，同组对等行都入窗
        assertEquals(List.of("100,15", "101,36", "102,15", "103,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY user_id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void aggregateWindowOverCrossDbUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1,5", "1,2,5", "1,3,5", "2,4,5", "2,5,5"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id), COUNT(*) OVER () FROM "
                + "(SELECT id FROM userdb.small UNION ALL SELECT user_id FROM logdb.logs WHERE user_id IS NOT NULL) t "
                + "ORDER BY id"));
      }
    }

    @Test void windowExpressionArithmetic() throws Exception {
      try (CrossDb db = core()) {
        // amount - 分区均值
        assertEquals(List.of("100,2.5", "101,9.5", "102,-2.5", "103,-9.5"), rows(db,
            "SELECT id, amount - AVG(CAST(amount AS DOUBLE)) OVER (PARTITION BY user_id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }
  }

  // ---------- 导航族 ----------

  @Nested
  @DisplayName("导航函数族")
  class Navigation {

    @Test void firstValueDefaultFrame() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,10", "102,10", "103,10"), rows(db,
            "SELECT id, FIRST_VALUE(amount) OVER (ORDER BY id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void lastValueDefaultFrame() throws Exception {
      // 默认帧止于当前行：LAST_VALUE 恒为当前行值
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,20", "102,5", "103,1"), rows(db,
            "SELECT id, LAST_VALUE(amount) OVER (ORDER BY id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void lastValueUnboundedFrame() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,1", "103,1"), rows(db,
            "SELECT id, LAST_VALUE(amount) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED "
                + "PRECEDING AND UNBOUNDED FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void firstValueInPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,20", "102,10", "103,20"), rows(db,
            "SELECT id, FIRST_VALUE(amount) OVER (PARTITION BY user_id ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void lagLeadOffsets() throws Exception {
      try (CrossDb db = core()) {
        // LAG/LEAD 取前一/后一行的 amount（amounts: 10,20,5,1）
        assertEquals(List.of("100,NULL", "101,10", "102,20", "103,5"), rows(db,
            "SELECT id, LAG(amount) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
        assertEquals(List.of("100,20", "101,5", "102,1", "103,NULL"), rows(db,
            "SELECT id, LEAD(amount) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
        // 显式偏移 + 缺省值：行号 ≤ 偏移时取缺省
        assertEquals(List.of("100,0", "101,0", "102,10", "103,20"), rows(db,
            "SELECT id, LAG(amount, 2, 0) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void nthValueFrameAware() throws Exception {
      try (CrossDb db = core()) {
        // 帧 [unbounded, current]：第 2 行值
        assertEquals(List.of("100,NULL", "101,20", "102,20", "103,20"), rows(db,
            "SELECT id, NTH_VALUE(amount, 2) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED "
                + "PRECEDING AND CURRENT ROW) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void firstValueIgnoreNulls() throws Exception {
      try (CrossDb db = all()) {
        // note 序列（按 id）：a, b, NULL → IGNORE NULLS 首个非 NULL = a
        assertEquals(List.of("1,a", "2,a", "3,a"), rows(db,
            "SELECT id, FIRST_VALUE(note) IGNORE NULLS OVER (ORDER BY id) "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void lastValueIgnoreNullsFullFrame() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,a", "2,b", "3,b"), rows(db,
            "SELECT id, LAST_VALUE(note) IGNORE NULLS OVER (ORDER BY id ROWS BETWEEN "
                + "UNBOUNDED PRECEDING AND CURRENT ROW) FROM pingdb.pings ORDER BY id"));
      }
    }
  }

  // ---------- WINDOW 命名与组合 ----------

  @Nested
  @DisplayName("WINDOW 子句与组合形态")
  class WindowClause {

    @Test void namedWindowReuse() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,15,2", "101,21,2", "102,15,2", "103,21,2"), rows(db,
            "SELECT id, SUM(amount) OVER w, COUNT(*) OVER w FROM orderdb.orders "
                + "WINDOW w AS (PARTITION BY user_id) ORDER BY id"));
      }
    }

    @Test void twoDifferentWindows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10,36", "101,30,36", "102,35,36", "103,36,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS UNBOUNDED PRECEDING), "
                + "SUM(amount) OVER () FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void windowOrderByExpression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103,4", "102,3", "100,2", "101,1"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (ORDER BY amount * 10 DESC) "
                + "FROM orderdb.orders ORDER BY amount"));
      }
    }

    @Test void windowOrderByDescNullsFirst() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,3", "2,2", "3,1"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (ORDER BY note DESC NULLS FIRST) "
                + "FROM pingdb.pings ORDER BY id"));
      }
    }

    @Test void qualifyAfterWindow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT id FROM orderdb.orders QUALIFY RANK() OVER (PARTITION BY user_id "
                + "ORDER BY amount DESC) = 1 ORDER BY id"));
      }
    }

    @Test void windowOverDerivedTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,1", "1,2", "2,1"), rows(db,
            "SELECT user_id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY id) FROM "
                + "(SELECT id, user_id FROM orderdb.orders WHERE amount < 20) t "
                + "ORDER BY user_id, id"));
      }
    }

    @Test void nestedWindowAggOfAggBarrier() throws Exception {
      // 外层聚合套内层聚合（含窗口列的派生表）
      try (CrossDb db = core()) {
        assertEquals("8", rows(db,
            "SELECT SUM(c) FROM (SELECT user_id, COUNT(*) OVER (PARTITION BY user_id) c "
                + "FROM orderdb.orders) t").get(0));
      }
    }
  }

  // ---------- 窗口帧语义（GROUPS / 混合） ----------

  @Nested
  @DisplayName("GROUPS 帧与混合形态")
  class GroupFrames {

    @Test void groupsCurrentRowOnly() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,15", "101,21", "102,15", "103,21"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY user_id GROUPS CURRENT ROW) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void groupsBetweenPrecedingAndCurrent() throws Exception {
      try (CrossDb db = core()) {
        // user_id 分组序：100,102 属组1；101,103 属组2 → 1 前组+当前组
        assertEquals(List.of("100,15", "101,36", "102,15", "103,36"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY user_id GROUPS BETWEEN 1 PRECEDING "
                + "AND CURRENT ROW) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void groupsExcludeCurrentRow() throws Exception {
      try (CrossDb db = core()) {
        // EXCLUDE CURRENT ROW：组内排除当前行（同组另一行仍入窗）
        assertEquals(List.of("100,5", "101,1", "102,5", "103,1"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY user_id GROUPS CURRENT ROW "
                + "EXCLUDE CURRENT ROW) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void rowsFrameWithConstantExpressionBounds() throws Exception {
      try (CrossDb db = core()) {
        // FOLLOWING 边界为常量表达式
        assertEquals(List.of("100,30", "101,35", "102,26", "103,6"), rows(db,
            "SELECT id, SUM(amount) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING "
                + "AND 1 + 0 FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }
  }
}

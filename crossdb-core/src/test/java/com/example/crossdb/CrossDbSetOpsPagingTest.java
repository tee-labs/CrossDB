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
 * 全场景覆盖测试（第六批 D）：集合运算与分页深测。参考 PostgreSQL 集合操作语义
 * （INTERSECT/EXCEPT 的 DISTINCT 与 ALL 多重集形态、优先级）、MySQL LIMIT 形态、
 * SQL:2008 FETCH 形态与 ShardingSphere 分页归并；覆盖三向嵌套、括号优先级、
 * 分页边界（OFFSET 越界、LIMIT 0、WITH TIES、PERCENT）、DISTINCT 组合。
 * 与前几批互补、不重复；尚未支持的形态以 {@code @Disabled("待支持: ...")} 标注。
 */
class CrossDbSetOpsPagingTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("eventdb", Fixtures.EVENTS)
        .register("pingdb", Fixtures.PINGS)
        .register("logdb", Fixtures.LOGS)
        .register("credsdb", Fixtures.CREDS)
        .register("quotasdb", Fixtures.QUOTAS);
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

  // ---------- UNION 族 ----------

  @Nested
  @DisplayName("UNION 族语义")
  class Unions {

    @Test void unionAllKeepsDuplicates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "1", "2", "2"), rows(db,
            "SELECT id FROM userdb.small UNION ALL SELECT id FROM userdb.small ORDER BY id"));
      }
    }

    @Test void unionDistinctDedups() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2", "9"), rows(db,
            "SELECT id FROM userdb.small UNION SELECT user_id FROM orderdb.orders "
                + "UNION SELECT user_id FROM eventdb.events WHERE user_id IS NOT NULL "
                + "ORDER BY id"));
      }
    }

    @Test void unionColumnNamesFromFirstBranch() throws Exception {
      try (CrossDb db = core()) {
        try (ResultSet rs = db.query("SELECT id FROM orderdb.orders WHERE id = 100 "
            + "UNION SELECT id FROM userdb.small ORDER BY id")) {
          assertEquals("ID", rs.getMetaData().getColumnLabel(1).toUpperCase());
        }
      }
    }

    @Test void unionWithExpressions() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("INFO", "big", "small"), rows(db,
            "SELECT TRIM(CASE WHEN amount > 15 THEN 'big' ELSE 'small' END) FROM orderdb.orders "
                + "UNION SELECT level FROM logdb.logs WHERE id = 1 ORDER BY 1"));
      }
    }

    @Test void threeWayUnionAllWithMixedSources() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "1", "1", "2", "9", "9"), rows(db,
            "SELECT user_id FROM eventdb.events WHERE user_id IS NOT NULL "
                + "UNION ALL SELECT user_id FROM pingdb.pings WHERE user_id IS NOT NULL "
                + "UNION ALL SELECT id FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void unionThenAggregate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("6", rows(db, "SELECT COUNT(*) FROM ("
            + "SELECT user_id FROM eventdb.events WHERE user_id IS NOT NULL "
            + "UNION ALL SELECT user_id FROM pingdb.pings WHERE user_id IS NOT NULL "
            + "UNION ALL SELECT id FROM userdb.small) t").get(0));
      }
    }
  }

  // ---------- INTERSECT / EXCEPT 族 ----------

  @Nested
  @DisplayName("INTERSECT / EXCEPT 族语义")
  class IntersectExcept {

    @Test void intersectDistinct() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small INTERSECT SELECT user_id FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void intersectEmptyResult() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of(), rows(db,
            "SELECT id FROM userdb.small INTERSECT SELECT 9 FROM eventdb.events"));
      }
    }

    @Test void intersectAllMultiset() throws Exception {
      // {1,2} ∩ {1,2,1,2} = {1,2}（各取最小出现次数）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small INTERSECT ALL "
                + "SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void exceptDistinct() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT id FROM userdb.users EXCEPT SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void exceptAllMultiset() throws Exception {
      // {1,2,3} EXCEPT ALL {1,1,2,2} = {3}
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT id FROM userdb.users EXCEPT ALL SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void exceptReversedOperands() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db,
            "SELECT user_id FROM orderdb.orders EXCEPT SELECT id FROM userdb.users"));
      }
    }

    @Test void chainedSetOpsLeftAssociative() throws Exception {
      // UNION 与 EXCEPT 同级：左结合
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT id FROM userdb.users UNION SELECT id FROM userdb.small "
                + "EXCEPT SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void parenthesizedPriority() throws Exception {
      try (CrossDb db = all()) {
        // 括号内先 UNION，再整体 EXCEPT
        assertEquals(List.of("3", "9"), rows(db,
            "(SELECT id FROM userdb.users UNION SELECT 9 FROM eventdb.events) "
                + "EXCEPT SELECT user_id FROM orderdb.orders ORDER BY id"));
      }
    }
  }

  // ---------- 分页形态 ----------

  @Nested
  @DisplayName("分页与 LIMIT 形态")
  class Paging {

    @Test void limitWithOffset() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("102", "103"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id LIMIT 2 OFFSET 2"));
      }
    }

    @Test void offsetCommaLimitForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("102"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id LIMIT 2, 1"));
      }
    }

    @Test void fetchFirstNextEquivalence() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(
            rows(db, "SELECT id FROM orderdb.orders ORDER BY id LIMIT 2"),
            rows(db, "SELECT id FROM orderdb.orders ORDER BY id FETCH FIRST 2 ROWS ONLY"));
        assertEquals(
            rows(db, "SELECT id FROM orderdb.orders ORDER BY id LIMIT 2"),
            rows(db, "SELECT id FROM orderdb.orders ORDER BY id FETCH NEXT 2 ROWS ONLY"));
      }
    }

    @Test void offsetBeyondRowCount() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id LIMIT 3 OFFSET 99"));
      }
    }

    @Test void limitZero() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db, "SELECT id FROM orderdb.orders LIMIT 0"));
      }
    }

    @Test void limitAllKeyword() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102", "103"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id LIMIT ALL"));
      }
    }

    @Test void withTiesIncludesPeers() throws Exception {
      try (CrossDb db = core()) {
        // user_id 升序取 1 行 WITH TIES：并列的 2 行（100、102）都带出
        assertEquals(List.of("100,1", "102,1"), rows(db,
            "SELECT id, user_id FROM orderdb.orders ORDER BY user_id FETCH FIRST 1 ROWS WITH TIES"));
      }
    }

    @Test void fetchPercentForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id FETCH FIRST 25 PERCENT ROWS ONLY"));
        assertEquals(List.of("100", "101", "102"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id FETCH FIRST 75 PERCENT ROWS ONLY"));
      }
    }

    @Test void paginationOverUnionAllPages() throws Exception {
      try (CrossDb db = all()) {
        // 并集 6 行（1,1,1,2,9,9）：offset 4 取 2 行
        assertEquals(List.of("9", "9"), rows(db,
            "SELECT user_id FROM eventdb.events WHERE user_id IS NOT NULL "
                + "UNION ALL SELECT user_id FROM pingdb.pings WHERE user_id IS NOT NULL "
                + "UNION ALL SELECT id FROM userdb.small ORDER BY 1 LIMIT 2 OFFSET 4"));
      }
    }

    @Test void orderByInSubqueryWithLimit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT id FROM (SELECT id FROM orderdb.orders ORDER BY amount DESC, id LIMIT 2) t "
                + "ORDER BY id"));
      }
    }

    @Test void limitInExistsSubquery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT name FROM userdb.users u WHERE EXISTS (SELECT 1 FROM orderdb.orders o "
                + "WHERE o.user_id = u.id ORDER BY o.id LIMIT 1) ORDER BY name"));
      }
    }
  }

  // ---------- DISTINCT 组合 ----------

  @Nested
  @DisplayName("DISTINCT 组合形态")
  class DistinctForms {

    @Test void distinctSingleColumn() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT DISTINCT user_id FROM orderdb.orders ORDER BY user_id"));
      }
    }

    @Test void distinctMultiColumn() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,5", "1,10", "2,1", "2,20"), rows(db,
            "SELECT DISTINCT user_id, amount FROM orderdb.orders ORDER BY user_id, amount"));
      }
    }

    @Test void distinctWithNull() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL", "1", "9"), rows(db,
            "SELECT DISTINCT user_id FROM pingdb.pings ORDER BY user_id NULLS FIRST"));
      }
    }

    @Test void distinctOverUnionAll() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "9"), rows(db,
            "SELECT DISTINCT user_id FROM (SELECT user_id FROM eventdb.events "
                + "UNION ALL SELECT user_id FROM pingdb.pings) t "
                + "WHERE user_id IS NOT NULL ORDER BY user_id"));
      }
    }

    @Test void countDistinctOverSetOp() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2", rows(db, "SELECT COUNT(DISTINCT user_id) FROM ("
            + "SELECT user_id FROM eventdb.events UNION ALL SELECT user_id FROM pingdb.pings) t")
            .get(0));
      }
    }

    @Test void unionBranchesWithDifferentTypes() throws Exception {
      try (CrossDb db = all()) {
        // INT 与 VARCHAR 分支：类型合并（CAST 显式）
        assertEquals(List.of("1", "2", "x"), rows(db,
            "SELECT CAST(id AS VARCHAR) FROM userdb.small "
                + "UNION SELECT 'x' FROM eventdb.events WHERE id = 1 ORDER BY 1"));
      }
    }
  }

  // ---------- 集合运算边界 ----------

  @Nested
  @DisplayName("集合运算边界与错误")
  class SetOpEdges {

    @Test void columnCountMismatchRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> rows(db,
            "SELECT id, name FROM userdb.users UNION SELECT id FROM userdb.small"));
      }
    }

    @Test void orderByOnNonOutputColumnRejected() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> rows(db,
            "SELECT id FROM orderdb.orders UNION SELECT id FROM userdb.small ORDER BY name"));
      }
    }

    @Test void unionAllPreservesOrderWithinBranch() throws Exception {
      // 外层 ORDER BY 决定最终序（分支内序不保证）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "1", "2", "2"), rows(db,
            "SELECT id FROM userdb.small UNION ALL SELECT id FROM userdb.small ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: EXCEPT/INTERSECT 的 MINUS 关键字别名（Oracle），待支持")
    void minusKeywordAlias() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT id FROM userdb.users MINUS SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void setOpInsideCte() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "WITH t AS (SELECT id FROM userdb.small UNION SELECT user_id FROM orderdb.orders) "
                + "SELECT id FROM t ORDER BY id"));
      }
    }

    @Test void nestedSetOpsThreeLevels() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2", "3", "9"), rows(db,
            "SELECT id FROM userdb.users UNION "
                + "(SELECT user_id FROM orderdb.orders UNION "
                + "(SELECT user_id FROM eventdb.events)) ORDER BY id"));
      }
    }
  }
}

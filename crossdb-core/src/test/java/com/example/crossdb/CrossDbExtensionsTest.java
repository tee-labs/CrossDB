package com.example.crossdb;

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
 * 扩展场景覆盖（参考同类系统公开用例补充，与 CrossDbScenariosTest /
 * CrossDbCompatibilityTest 互补）。
 *
 * <p>用例来源参考：PostgreSQL regression（LATERAL 横向引用、UNNEST、GROUPING、
 * NULLS LAST）、MySQL（CONCAT / CONCAT_WS / REVERSE / LIMIT a,b / GROUP_CONCAT）、
 * SQL Server（CROSS APPLY 相关派生表、TOP）、Oracle（MEDIAN / LISTAGG DISTINCT）、
 * 标准 SQL（VALUES 行构造器、INTERSECT/EXCEPT ALL、PERCENTILE_CONT、
 * TIMESTAMPDIFF、窗口滑动帧）、Apache Calcite（PIVOT、COUNT(DISTINCT a, b)）、
 * Apache ShardingSphere / Vitess（分片合并边界形态）。
 *
 * <p>预期：全部按标准语义断言。尚未支持的特性以 {@code @Disabled("待支持/待修复: ...")}
 * 标记跳过，作为后续修复清单，不为缺陷行为放宽预期。
 */
class CrossDbExtensionsTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS)
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

  // ---------- 横向引用与表构造器（PostgreSQL LATERAL / SQL Server APPLY / 标准 VALUES） ----------

  @Nested
  @DisplayName("横向引用与表构造器场景")
  class LateralAndConstructors {

    @Test void valuesConstructorJoinedCrossDb() throws Exception {
      // VALUES 行构造器作为派生表，跨库 JOIN（Calcite SqlOperatorsTest 形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,x", "2,y"),
            rows(db, "SELECT t.id, t.n FROM (VALUES (1, 'x'), (2, 'y')) AS t(id, n) "
                + "JOIN userdb.users u ON u.id = t.id"));
      }
    }

    @Test void valuesConstructorBare() throws Exception {
      // 修复记录：裸 VALUES 派生表曾因校验器附加复合排序特征触发计划收尾断言失败，
      // 引擎已对非 simple 的 requiredTraits 净化后放行
      try (CrossDb db = core()) {
        assertEquals(List.of("1,x", "2,y"),
            rows(db, "SELECT t.id, t.n FROM (VALUES (1, 'x'), (2, 'y')) AS t(id, n)"));
      }
    }

    @Test void lateralDerivedTableCrossDb() throws Exception {
      // LATERAL 相关派生表跨库（PostgreSQL regress lateral 形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"),
            rows(db, "SELECT u.name, x.id FROM userdb.users u, "
                + "LATERAL (SELECT id FROM orderdb.orders o WHERE o.user_id = u.id) x "
                + "ORDER BY x.id"));
      }
    }

    @Test void crossApplyDerivedTableCrossDb() throws Exception {
      // CROSS APPLY（SQL Server 方言；LENIENT 一致性放行）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"),
            rows(db, "SELECT u.name, x.id FROM userdb.users u "
                + "CROSS APPLY (SELECT id FROM orderdb.orders o WHERE o.user_id = u.id) x "
                + "ORDER BY x.id"));
      }
    }

    @Test void crossApplyWithColumnList() throws Exception {
      // 列别名须为不带限定名的标识符：原 t(o.id) 形态解析器不支持（且外层 o.id
      // 在 APPLY 作用域外不可解析，本属无效 SQL），改用单段名 t(oid)
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,100", "bob,101", "alice,102", "bob,103"),
            rows(db, "SELECT u.name, t.oid FROM userdb.users u "
                + "CROSS APPLY (SELECT id FROM orderdb.orders o WHERE o.user_id = u.id) t(oid) "
                + "ORDER BY t.oid"));
      }
    }

    @Test void unnestArraySource() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT * FROM UNNEST(ARRAY[1, 2, 3]) AS t(x)"));
      }
    }
  }

  // ---------- 集合操作与分页扩展（标准 ALL 变体 / MySQL LIMIT a,b） ----------

  @Nested
  @DisplayName("集合操作与分页扩展场景")
  class SetOpAndPaging {

    @Test void exceptAllKeepsDuplicates() throws Exception {
      // EXCEPT ALL：保留重复计数（users {1,2,3} EXCEPT ALL small {1,2} → {3}）
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users EXCEPT ALL SELECT id FROM userdb.small"));
      }
    }

    @Test void intersectAllKeepsDuplicates() throws Exception {
      // INTERSECT ALL：users{1,2,3} ∩ ALL orders.user_id{1,2,1,2} → {1,2}
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT id FROM userdb.users INTERSECT ALL "
                + "SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void unionAllColumnAliasFromFirstBranch() throws Exception {
      // 集合操作列名取自第一个分支（位置对齐，与列名无关）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3", "1", "2"),
            rows(db, "SELECT id AS a FROM userdb.users UNION ALL SELECT id FROM userdb.small"));
      }
    }

    @Test void mysqlLimitOffsetSyntax() throws Exception {
      // MySQL 风格 LIMIT offset, count
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id LIMIT 1, 2"));
      }
    }

    @Test void orderByOrdinalAndMultiKey() throws Exception {
      // ORDER BY 序数 + 多键混合排序
      try (CrossDb db = core()) {
        assertEquals(List.of("101,20", "100,10", "102,5"),
            rows(db, "SELECT id, amount FROM orderdb.orders ORDER BY 2 DESC, 1 LIMIT 3"));
      }
    }
  }

  // ---------- 函数扩展（MySQL CONCAT 族 / 标准窗口帧 / 本地 UDF 跨库一致性） ----------

  @Nested
  @DisplayName("函数扩展场景")
  class FunctionExtensions {

    @Test void concatFunctionMySql() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("alice!",
            scalarOf(db, "SELECT CONCAT(name, '!') FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void concatWsSkipsNullValues() throws Exception {
      // CONCAT_WS 跳过 NULL 值参（MySQL 语义）：note 为 NULL 的行只剩 'x'
      try (CrossDb db = core()) {
        assertEquals("alice-x",
            scalarOf(db, "SELECT CONCAT_WS('-', name, 'x') FROM userdb.users WHERE id = 1"));
        assertEquals("x",
            scalarOf(db, "SELECT CONCAT_WS('-', note, 'x') FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void reverseFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("ecila",
            scalarOf(db, "SELECT REVERSE(name) FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void greatestOverCrossDbJoinedColumns() throws Exception {
      // GREATEST 跨库列比较（本地求值 UDF 与 Bind Join 组合）
      try (CrossDb db = core()) {
        assertEquals(List.of("10,alice", "20,bob"),
            rows(db, "SELECT o.amount, u.name FROM orderdb.orders o "
                + "JOIN userdb.users u ON u.id = o.user_id "
                + "WHERE GREATEST(o.amount, 6) = o.amount ORDER BY o.id"));
      }
    }

    @Test void ceilTimestampToMinute() throws Exception {
      // CEIL(datetime TO unit) 本地截断（与 FLOOR 对称）
      try (CrossDb db = core()) {
        assertEquals("2026-01-02 03:05:00.0",
            scalarOf(db, "SELECT CEIL(ts TO MINUTE) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void timestampDiffFunction() throws Exception {
      // 修复记录：TIMESTAMPDIFF 曾尝试下推 H2（无对应函数）执行失败，现已改写为
      // CROSSDB_TIMESTAMPDIFF 本地求值
      try (CrossDb db = core()) {
        assertEquals("10", scalarOf(db,
            "SELECT TIMESTAMPDIFF(DAY, ts, TIMESTAMP '2026-01-12 03:04:05') "
                + "FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void lastValueWithExplicitFullFrame() throws Exception {
      // 显式整分区帧的 LAST_VALUE（标准语义：取帧末行）
      try (CrossDb db = core()) {
        assertEquals("103", scalarOf(db,
            "SELECT LAST_VALUE(id) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING "
                + "AND UNBOUNDED FOLLOWING) FROM orderdb.orders ORDER BY id LIMIT 1"));
      }
    }

    @Test void countOverSlidingRowsFrame() throws Exception {
      // 滑动帧计数：首尾行帧内只有 2 行，中间行 3 行
      try (CrossDb db = core()) {
        assertEquals(List.of("2", "3", "3", "2"),
            rows(db, "SELECT COUNT(*) OVER (ORDER BY id "
                + "ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void minOverPartition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5", "1", "5", "1"),
            rows(db, "SELECT MIN(amount) OVER (PARTITION BY user_id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void denseRankOverOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,100", "1,101", "3,102", "4,103"),
            rows(db, "SELECT DENSE_RANK() OVER (ORDER BY amount DESC) rnk, id "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    private String scalarOf(CrossDb db, String sql) throws SQLException {
      try (ResultSet rs = db.query(sql)) {
        assertTrue(rs.next(), "应至少返回一行: " + sql);
        Object v = rs.getObject(1);
        return v == null ? "NULL" : v.toString();
      }
    }
  }

  // ---------- 聚合扩展（Calcite COUNT(DISTINCT a, b) / GROUPING / PIVOT / Oracle LISTAGG） ----------

  @Nested
  @DisplayName("聚合扩展场景")
  class AggregateExtensions {

    @Test void countDistinctMultipleArguments() throws Exception {
      // 多列去重计数（引擎 MultiArgCountRule 改写路径）：orders 4 行全部不同 → 4
      try (CrossDb db = core()) {
        assertEquals("4",
            scalarOf(db, "SELECT COUNT(DISTINCT user_id, amount) FROM orderdb.orders"));
      }
    }

    @Test void groupingWithRollup() throws Exception {
      // GROUPING() 识别 ROLLUP 小计行（PostgreSQL regress grouping 形态）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,15,0", "2,21,0", "3,NULL,0", "NULL,36,1"),
            rows(db, "SELECT u.id, SUM(o.amount) AS s, GROUPING(u.id) "
                + "FROM userdb.users u LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY ROLLUP(u.id) ORDER BY 1 NULLS LAST"));
      }
    }

    @Test void pivotOverDerivedTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0,1", "5,1,0", "10,1,0", "20,0,1"),
            rows(db, "SELECT * FROM (SELECT user_id, amount FROM orderdb.orders) "
                + "PIVOT (COUNT(*) FOR user_id IN (1, 2))"));
      }
    }

    @Test void listaggDistinct() throws Exception {
      // 修复记录：LISTAGG(DISTINCT ..) 曾触发原生计划 ArrayIndexOutOfBoundsException，
      // 现改写为 CROSSDB_LISTAGG 本地去重；标准语义 = 去重后按 WITHIN GROUP 升序拼接
      // （user 1: {5,10} → "5;10"；user 2: {1,20} → "1;20"）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,5;10", "2,1;20"),
            rows(db, "SELECT user_id, LISTAGG(DISTINCT amount, ';') "
                + "WITHIN GROUP (ORDER BY amount) FROM orderdb.orders GROUP BY user_id"));
      }
    }

    @Test void medianAggregate() throws Exception {
      // 修复记录：MEDIAN 已注册为本地聚合（CROSSDB_MEDIAN），偶数行取中间两值均值
      try (CrossDb db = core()) {
        assertEquals("7.5", scalarOf(db, "SELECT MEDIAN(amount) FROM orderdb.orders"));
      }
    }

    @Test void percentileContOrderedSetAggregate() throws Exception {
      // 修复记录：PERCENTILE_CONT 有序集聚合已改写为 CROSSDB_PERCENTILE_CONT
      // 本地连续插值实现
      try (CrossDb db = core()) {
        assertEquals("7.5", scalarOf(db, "SELECT PERCENTILE_CONT(0.5) "
            + "WITHIN GROUP (ORDER BY amount) FROM orderdb.orders"));
      }
    }

    private String scalarOf(CrossDb db, String sql) throws SQLException {
      try (ResultSet rs = db.query(sql)) {
        assertTrue(rs.next(), "应至少返回一行: " + sql);
        Object v = rs.getObject(1);
        return v == null ? "NULL" : v.toString();
      }
    }
  }

  // ---------- 跨库组合场景（CTE / 嵌套派生表 / 空集合语义 / 谓词组合） ----------

  @Nested
  @DisplayName("跨库组合场景")
  class CrossDbCompositions {

    @Test void cteAggregateJoinedCrossDbWithHaving() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,15", "bob,21"),
            rows(db, "WITH top_users AS (SELECT user_id, SUM(amount) AS s "
                + "FROM orderdb.orders GROUP BY user_id) "
                + "SELECT u.name, t.s FROM userdb.users u JOIN top_users t ON t.user_id = u.id "
                + "WHERE t.s > 5 ORDER BY u.name"));
      }
    }

    @Test void threeLevelNestedDerivedTables() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT x.a FROM (SELECT u.id AS a FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id WHERE o.amount > 5) x "
                + "JOIN userdb.small s ON s.id = x.a"));
      }
    }

    @Test void notInEmptySubqueryKeepsAllRows() throws Exception {
      // NOT IN 空子查询 → 全体保留（三值逻辑边界）
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM userdb.users WHERE id NOT IN "
                + "(SELECT user_id FROM orderdb.orders WHERE amount > 999)"));
      }
    }

    @Test void coalesceInJoinCondition() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob", "alice"),
            rows(db, "SELECT u.name FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id AND COALESCE(o.amount, 0) > 1 ORDER BY o.id"));
      }
    }

    @Test void havingWithAggregateAlias() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob,2", "alice,2"),
            rows(db, "SELECT name, COUNT(*) AS c FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY name HAVING c >= 2"));
      }
    }

    @Test void notPredicate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id FROM userdb.users WHERE NOT (id > 1)"));
      }
    }

    @Test void scalarSubqueryArithmeticAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("19", scalarOf(db, "SELECT (SELECT MAX(amount) FROM orderdb.orders) "
            + "- (SELECT MIN(amount) FROM orderdb.orders)"));
      }
    }

    private String scalarOf(CrossDb db, String sql) throws SQLException {
      try (ResultSet rs = db.query(sql)) {
        assertTrue(rs.next(), "应至少返回一行: " + sql);
        Object v = rs.getObject(1);
        return v == null ? "NULL" : v.toString();
      }
    }
  }

  // ---------- 错误契约（同名歧义列，标准 SQL 一致行为） ----------

  @Nested
  @DisplayName("错误契约场景")
  class ErrorContracts {

    @Test void ambiguousUnqualifiedColumnRejected() throws Exception {
      // JOIN 两侧同名列（users.name / orders.user_id 不同名；此处 user_id vs id 不歧义；
      // pings 与 orders 同含 amount）裸引用必须报歧义（标准 SQL 语义）
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT NULLIF(amount, 5) FROM orderdb.orders o "
                + "LEFT JOIN pingdb.pings p ON p.user_id = o.user_id"));
      }
    }

    @Test void selfJoinSameColumnNameRejected() throws Exception {
      // 自连接裸引用同名列 → 歧义错误（须以别名限定）
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id, u2.name FROM userdb.users u1 JOIN userdb.users u2 ON u2.id = u1.id"));
      }
    }
  }
}

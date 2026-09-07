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
 * 全量场景覆盖测试（参考同类系统公开用例补充，与既有四组测试互补，避免重复）。
 *
 * <p>用例来源参考：PostgreSQL regression（窗口帧/RANGE、NULLS 排序默认位、递归 CTE、
 * 行构造器、EXTRACT）、Apache Calcite（SqlOperatorsTest / JdbcTest / WINDOW 子句）、
 * Presto・Trino federated（集合操作优先级、类型放宽、LIMIT ALL）、MySQL 8.0
 * （TRUNCATE / MOD 符号 / GROUP BY 序数）、SQL Server（行构造器比较、APPLY 变体）、
 * Oracle（DECODE / MEDIAN / PERCENTILE_CONT，已本地化实现的部分在此回归）。
 *
 * <p>预期：全部按标准语义断言。尚未支持的特性以 {@code @Disabled("待支持: ...")}
 * 标记跳过，作为后续修复清单，不为缺陷行为放宽预期。
 *
 * <p>时间承载约定：TIMESTAMP 列以 epoch millis（Long）透出、按 UTC 墙钟解释
 * （与 Exec / CROSSDB_FLOOR·CEIL·TIMESTAMPDIFF 一致），涉及时间值的断言按该约定写。
 */
class CrossDbCoverageTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb corePlusPings() throws SQLException {
    return core().register("pingdb", Fixtures.PINGS);
  }

  private static CrossDb corePlusCreds() throws SQLException {
    return core().register("credsdb", Fixtures.CREDS).register("quotasdb", Fixtures.QUOTAS);
  }

  private static CrossDb coreAll() throws SQLException {
    return corePlusPings().register("credsdb", Fixtures.CREDS)
        .register("eventdb", Fixtures.EVENTS);
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

  // ---------- 投影与 DISTINCT（参考 Calcite SqlOperatorsTest / PostgreSQL regress） ----------

  @Nested
  @DisplayName("投影与 DISTINCT 场景")
  class Projection {

    @Test void multiColumnDistinct() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,5", "1,10", "2,1", "2,20"),
            rows(db, "SELECT DISTINCT user_id, amount FROM orderdb.orders "
                + "ORDER BY user_id, amount"));
      }
    }

    @Test void qualifiedStarWithExtraColumns() throws Exception {
      // t.* 可与其他列混用（PostgreSQL/Calcite 支持）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,100,1,10"),
            rows(db, "SELECT u.id, o.* FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id ORDER BY o.id LIMIT 1"));
      }
    }

    @Test void distinctKeepsNullGroup() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("a", "b", "NULL"),
            rows(db, "SELECT DISTINCT note FROM pingdb.pings ORDER BY note NULLS LAST"));
      }
    }

    @Test void columnAliasWithoutAs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"),
            rows(db, "SELECT id uid FROM userdb.users WHERE id = 2"));
      }
    }
  }

  // ---------- JOIN 补充（参考 PostgreSQL regress JOIN / ShardingSphere 联邦用例） ----------

  @Nested
  @DisplayName("JOIN 补充场景")
  class Joins {

    @Test void leftJoinResidualNonEquiCondition() throws Exception {
      // ON 含残余非等值条件（amount > 15）：Bind Join 不改写回退原生，左侧全体保留
      // alice/orders(10,5) 无 >15；bob/101(20) 命中 → 3 个用户各 1 行
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id AND o.amount > 15 "
                + "ORDER BY u.name"));
      }
    }

    @Test void innerDerivedTableWithDistinct() throws Exception {
      // 内表派生表带 DISTINCT（Aggregate 链可整体下推），Bind Join 仍生效
      try (CrossDb db = core()) {
        assertEquals("2",
            scalar(db, "SELECT COUNT(*) FROM userdb.users u "
                + "JOIN (SELECT DISTINCT user_id FROM orderdb.orders) d ON d.user_id = u.id"));
      }
    }

    @Test void innerDerivedTableWithLimit() throws Exception {
      // 内表派生表带 ORDER BY + LIMIT：orders 前 2 条（100,101）→ 用户 1、2
      try (CrossDb db = core()) {
        assertEquals("2",
            scalar(db, "SELECT COUNT(*) FROM userdb.users u "
                + "JOIN (SELECT user_id FROM orderdb.orders ORDER BY id LIMIT 2) d "
                + "ON d.user_id = u.id"));
      }
    }

    @Test void semiJoinCompositeKey() throws Exception {
      // 复合等值键的 SEMI（EXISTS）：tenant=200 的凭证仅 user 1
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM credsdb.creds c "
                + "WHERE c.user_id = u.id AND c.tenant_id = 200) ORDER BY u.name"));
      }
    }

    @Test void bareCrossJoinCartesianCount() throws Exception {
      // 裸 CROSS JOIN（无 WHERE）：3 × 4 = 12（跨库笛卡尔回退原生）
      try (CrossDb db = core()) {
        assertEquals("12",
            scalar(db, "SELECT COUNT(*) FROM userdb.users u CROSS JOIN orderdb.orders o"));
      }
    }

    @Test void threeDbChainWithFilterOnThird() throws Exception {
      try (CrossDb db = coreAll()) {
        assertEquals("2",
            scalar(db, "SELECT COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN pingdb.pings p ON p.user_id = o.user_id WHERE p.id = 1"));
      }
    }

    @Test void rightJoinDerivedTableOnRight() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,1", "bob,2"),
            rows(db, "SELECT u.name, d.user_id FROM userdb.users u RIGHT JOIN "
                + "(SELECT DISTINCT user_id FROM orderdb.orders) d ON d.user_id = u.id "
                + "ORDER BY u.name"));
      }
    }
  }

  // ---------- 子查询补充（参考 MySQL 8.0 / PostgreSQL quantified 用例） ----------

  @Nested
  @DisplayName("子查询补充场景")
  class Subqueries {

    @Test void rowValueInSubquery() throws Exception {
      // 行值 (a, b) IN (SELECT ...)（标准 SQL 行构造器）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE (u.id, u.id) IN "
                + "(SELECT 1, 1 FROM userdb.small)"));
      }
    }

    @Test void multiColumnRowInSubquery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100"),
            rows(db, "SELECT o.id FROM orderdb.orders o WHERE (o.user_id, o.id) IN "
                + "(SELECT u.id, 100 FROM userdb.users u)"));
      }
    }

    @Test void rowConstructorEqualityAcrossDbs() throws Exception {
      // 行构造器等值比较作为跨库 JOIN 后过滤
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT u.name FROM userdb.users u JOIN orderdb.orders o "
                + "ON o.user_id = u.id WHERE (o.id, u.id) = (101, 2) ORDER BY u.name"));
      }
    }

    @Test void notEqualsAllQuantified() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"),
            rows(db, "SELECT id FROM userdb.users WHERE id <> ALL "
                + "(SELECT id FROM userdb.small) ORDER BY id"));
      }
    }

    @Test void lessThanAnyQuantified() throws Exception {
      // id < ANY({1,2})：id=1 满足（1 < 2）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id < ANY "
                + "(SELECT o.user_id FROM orderdb.orders o)"));
      }
    }

    @Test void inSubqueryWithGroupByHaving() throws Exception {
      // IN 子查询内含 GROUP BY + HAVING（各 2 单的用户 {1,2}）
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE u.id IN "
                + "(SELECT user_id FROM orderdb.orders GROUP BY user_id HAVING COUNT(*) = 2) "
                + "ORDER BY u.name"));
      }
    }

    @Test void scalarSubqueryInsideCase() throws Exception {
      // 标量子查询嵌入 CASE 条件；'big'/'small' 分支推导 CHAR(5) 补空格
      try (CrossDb db = core()) {
        assertEquals("big  ",
            scalar(db, "SELECT CASE WHEN (SELECT MAX(amount) FROM orderdb.orders) > 10 "
                + "THEN 'big' ELSE 'small' END FROM userdb.small LIMIT 1"));
      }
    }

    @Test void notExistsWithNullAmountPings() throws Exception {
      // pings 中 amount IS NULL 的行（p2）user_id=9 不匹配任何用户 → 全体通过
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("alice", "bob", "carol"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM pingdb.pings p "
                + "WHERE p.user_id = u.id AND p.amount IS NULL) ORDER BY u.name"));
      }
    }

    @Test
    @Disabled("待支持: 相关 EXISTS 子查询内 HAVING 聚合过滤（去相关后形态）校验不支持，待支持")
    void existsWithHavingCorrelation() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT o.user_id FROM orderdb.orders o WHERE o.user_id = u.id "
                + "HAVING COUNT(*) > 1) ORDER BY u.name"));
      }
    }
  }

  // ---------- 聚合补充（参考 TPC-H / MySQL GROUP BY 用例） ----------

  @Nested
  @DisplayName("聚合补充场景")
  class Aggregates {

    @Test void groupByCaseExpression() throws Exception {
      // 分组键为 CASE 表达式（'big' 按 CHAR(5) 补空格参与排序）
      try (CrossDb db = core()) {
        assertEquals(List.of("big  ,2", "small,2"),
            rows(db, "SELECT CASE WHEN amount >= 10 THEN 'big' ELSE 'small' END AS k, "
                + "COUNT(*) FROM orderdb.orders "
                + "GROUP BY CASE WHEN amount >= 10 THEN 'big' ELSE 'small' END ORDER BY k"));
      }
    }

    @Test void havingAggregateNotInSelectList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name "
                + "HAVING MAX(o.amount) > 15 ORDER BY u.name"));
      }
    }

    @Test void minTimestampFollowsEngineConvention() throws Exception {
      // TIMESTAMP 聚合值按引擎约定以 epoch millis（Long）透出（UTC 墙钟解释）
      try (CrossDb db = corePlusPings()) {
        assertEquals("1767323045000",
            scalar(db, "SELECT MIN(ts) FROM pingdb.pings"));
      }
    }

    @Test void avgDecimalRoundsToColumnScale() throws Exception {
      // H2 AVG(DECIMAL(10,2)) 保留 2 位小数：(1.25 + 3.5) / 2 → 2.38
      try (CrossDb db = corePlusPings()) {
        assertEquals(0, new BigDecimal(scalar(db, "SELECT AVG(amount) FROM pingdb.pings"))
            .compareTo(new BigDecimal("2.38")));
      }
    }

    @Test void sumDecimalExact() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals(0, new BigDecimal(scalar(db, "SELECT SUM(amount) FROM pingdb.pings"))
            .compareTo(new BigDecimal("4.75")));
      }
    }

    @Test void countDistinctSkipsNulls() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT COUNT(DISTINCT user_id) FROM pingdb.pings"));
      }
    }

    @Test void groupByOrdinalNumber() throws Exception {
      // GROUP BY 序数（MySQL/LENIENT 语义）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2", "2,2"),
            rows(db, "SELECT user_id, COUNT(*) FROM orderdb.orders GROUP BY 1 ORDER BY 1"));
      }
    }
  }

  // ---------- 窗口补充（参考 PostgreSQL regress window / Calcite WindowTest） ----------

  @Nested
  @DisplayName("窗口函数补充场景")
  class Windows {

    @Test void namedWindowClause() throws Exception {
      // WINDOW 命名窗口被子多个函数复用（标准 SQL / PostgreSQL）
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,30", "102,35", "103,36"),
            rows(db, "SELECT id, SUM(amount) OVER w FROM orderdb.orders "
                + "WINDOW w AS (ORDER BY id)"));
      }
    }

    @Test void rangeFrameWithTies() throws Exception {
      // RANGE 帧（值域帧）：并列 user_id 的行互为 peers
      // user 1 行（100,102）帧内 2 行；user 2 行（101,103）帧内全体 4 行
      try (CrossDb db = core()) {
        assertEquals(List.of("100,2", "101,4", "102,2", "103,4"),
            rows(db, "SELECT id, COUNT(*) OVER (ORDER BY user_id RANGE "
                + "BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void leadWithOffsetAndDefault() throws Exception {
      // LEAD(id, 2, 0)：偏移 2、越界补 0
      try (CrossDb db = core()) {
        assertEquals(List.of("102", "103", "0", "0"),
            rows(db, "SELECT LEAD(id, 2, 0) OVER (ORDER BY id) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void lagWithOffsetYieldsNull() throws Exception {
      // LAG(id, 2)：前 2 行越界为 NULL
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL", "NULL", "100", "101"),
            rows(db, "SELECT LAG(id, 2) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void nthValueRespectsPartitionFrame() throws Exception {
      // NTH_VALUE 按帧内第 2 行取值：各分区首行帧内仅 1 行 → NULL
      try (CrossDb db = core()) {
        assertEquals(List.of("100,NULL", "101,NULL", "102,102", "103,103"),
            rows(db, "SELECT id, NTH_VALUE(id, 2) OVER (PARTITION BY user_id ORDER BY id) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void windowOverEmptyInput() throws Exception {
      try (CrossDb db = core()) {
        assertTrue(rows(db, "SELECT ROW_NUMBER() OVER (ORDER BY id) FROM orderdb.orders "
            + "WHERE amount > 999").isEmpty());
      }
    }

    @Test void rankDerivedTableTopOne() throws Exception {
      // 窗口排名派生表过滤 Top-1：amount 最大（20）的订单 101
      try (CrossDb db = core()) {
        assertEquals(List.of("101"),
            rows(db, "SELECT id FROM (SELECT id, RANK() OVER "
                + "(ORDER BY amount DESC) rk FROM orderdb.orders) t WHERE rk <= 1"));
      }
    }
  }

  // ---------- 集合操作补充（参考 Trino federated / PostgreSQL regress 集合用例） ----------

  @Nested
  @DisplayName("集合操作补充场景")
  class SetOps {

    @Test void intersectBindsTighterThanUnion() throws Exception {
      // 优先级：users UNION (small INTERSECT small) = users ∪ small
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM userdb.users UNION SELECT id FROM userdb.small "
                + "INTERSECT SELECT id FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void emptyBranchWithImpossibleFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"),
            rows(db, "SELECT id FROM userdb.users WHERE 1 = 0 "
                + "UNION ALL SELECT id FROM userdb.small ORDER BY 1"));
      }
    }

    @Test void threeBranchTypeWidening() throws Exception {
      // 三分支 INT∪INT∪VARCHAR 放宽为 VARCHAR，按字符串序取前 2
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("1", "100"),
            rows(db, "SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders "
                + "UNION ALL SELECT login FROM credsdb.creds ORDER BY 1 LIMIT 2"));
      }
    }

    @Test void unionDistinctMultiColumnPairs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,5", "1,10", "2,1", "2,20"),
            rows(db, "SELECT user_id, amount FROM orderdb.orders "
                + "UNION SELECT user_id, amount FROM orderdb.orders "
                + "ORDER BY user_id, amount"));
      }
    }

    @Test void valuesUnionAllWithTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "7", "8"),
            rows(db, "SELECT id FROM userdb.small UNION ALL SELECT x FROM "
                + "(VALUES (7), (8)) AS t(x) ORDER BY 1"));
      }
    }

    @Test void unionArityMismatchRejected() throws Exception {
      // 集合操作列数不一致必须报错（标准 SQL）
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.query(
            "SELECT id, name FROM userdb.users UNION SELECT id FROM userdb.small"));
      }
    }
  }

  // ---------- VALUES 行构造器（参考 Calcite VALUES / 标准 SQL 行值表达式） ----------

  @Nested
  @DisplayName("VALUES 行构造器场景")
  class ValuesTables {

    @Test void valuesMultiColumnWithWhere() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,b"),
            rows(db, "SELECT t.x, t.y FROM (VALUES (1, 'a'), (2, 'b')) AS t(x, y) "
                + "WHERE t.x = 2"));
      }
    }

    @Test void valuesWithScalarExpressions() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,A"),
            rows(db, "SELECT t.a, t.b FROM (VALUES (1 + 1, UPPER('a'))) AS t(a, b)"));
      }
    }

    @Test void rowConstructorEqualitySingleTable() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100"),
            rows(db, "SELECT id FROM orderdb.orders WHERE (id, user_id) = (100, 1)"));
      }
    }
  }

  // ---------- 排序分页补充（参考 Vitess / MyCat 分页边界） ----------

  @Nested
  @DisplayName("排序分页补充场景")
  class OrderPaging {

    @Test void limitAllKeepsAllRows() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id LIMIT ALL"));
      }
    }

    @Test void orderByAscPutsNullsLastByDefault() throws Exception {
      // 默认 ASC 空值排最后（PostgreSQL/Calcite 语义）
      try (CrossDb db = corePlusPings()) {
        assertEquals(List.of("1", "9", "NULL"),
            rows(db, "SELECT user_id FROM pingdb.pings ORDER BY user_id"));
      }
    }

    @Test void shardTopNAscending() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2", "3"),
            rows(db, "SELECT id FROM (SELECT id FROM userdb.users "
                + "UNION ALL SELECT id FROM orderdb.orders) t ORDER BY id LIMIT 3"));
      }
    }

    @Test void shardTopNWithDuplicateKeysAcrossBranches() throws Exception {
      // 并列键跨分支：全体 id 降序 {3,3,2,2,1,1} 前 3 → [3,3,2]
      try (CrossDb db = corePlusCreds()) {
        assertEquals(List.of("3", "3", "2"),
            rows(db, "SELECT user_id AS id FROM credsdb.creds "
                + "UNION ALL SELECT id FROM userdb.users ORDER BY id DESC LIMIT 3"));
      }
    }

    @Test
    @Disabled("待支持: Calcite 解析器不支持 FETCH FIRST n ROW WITH TIES 语法，待支持")
    void fetchFirstWithTies() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"),
            rows(db, "SELECT id FROM userdb.users ORDER BY id FETCH FIRST 1 ROW WITH TIES"));
      }
    }
  }

  // ---------- 表达式与函数补充（参考 MySQL / PostgreSQL / Oracle 函数用例） ----------

  @Nested
  @DisplayName("表达式与函数补充场景")
  class Expressions {

    @Test void simpleCaseExpression() throws Exception {
      // 简单 CASE（switch 形态）；分支 'one'/'other' 推导 CHAR(6) 补空格
      try (CrossDb db = core()) {
        assertEquals("one  ",
            scalar(db, "SELECT CASE user_id WHEN 1 THEN 'one' ELSE 'other' END "
                + "FROM orderdb.orders WHERE id = 100"));
      }
    }

    @Test void roundWithPrecision() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2.57", scalar(db, "SELECT ROUND(2.567, 2) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void truncateFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2.56", scalar(db, "SELECT TRUNCATE(2.567, 2) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void lnExpRoundTrip() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1.0", scalar(db, "SELECT LN(EXP(1.0)) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nullifStringComparison() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("NULL",
            scalar(db, "SELECT NULLIF(name, 'alice') FROM userdb.users WHERE id = 1"));
        assertEquals("bob",
            scalar(db, "SELECT NULLIF(name, 'alice') FROM userdb.users WHERE id = 2"));
      }
    }

    @Test void modSignFollowsDividend() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("-1", scalar(db, "SELECT MOD(-7, 3) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void concatOperatorAcceptsNumber() throws Exception {
      // || 对数值自动转字符串（Calcite 语义）
      try (CrossDb db = core()) {
        assertEquals("1a", scalar(db, "SELECT 1 || 'a' FROM userdb.small LIMIT 1"));
      }
    }

    @Test void upperNullPropagates() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("NULL", scalar(db, "SELECT UPPER(note) FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void extractDayAndEpoch() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT EXTRACT(DAY FROM ts) FROM pingdb.pings WHERE id = 1"));
        assertEquals("1767323045",
            scalar(db, "SELECT EXTRACT(EPOCH FROM ts) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void castTimestampToVarchar() throws Exception {
      // VARCHAR 化按 UTC 墙钟渲染（引擎承载约定，见类注释）
      try (CrossDb db = corePlusPings()) {
        assertEquals("2026-01-02 03:04:05",
            scalar(db, "SELECT CAST(ts AS VARCHAR) FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void timestampMinusIntervalHours() throws Exception {
      // timestamp - INTERVAL 结果仍以 epoch millis 透出：03:04:05 - 2h = 01:04:05
      try (CrossDb db = corePlusPings()) {
        assertEquals("1767315845000",
            scalar(db, "SELECT ts - INTERVAL '2' HOUR FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void dateComparesWithTimestamp() throws Exception {
      // DATE 字面量与 TIMESTAMP 列比较：p1(01-02)、p3(02-03) 均晚于 01-01
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE ts > DATE '2026-01-01'"));
      }
    }

    @Test void caseCharPaddingDisappearsOnConcat() throws Exception {
      // CASE 分支推导 CHAR(1)，拼接后不补空格（bpchar 定标边界）
      try (CrossDb db = core()) {
        assertEquals("ax", scalar(db, "SELECT CASE WHEN 1 = 1 THEN 'a' ELSE 'b' END || 'x' "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: Oracle DECODE 未注册（可经 CASE 等价改写实现），待支持")
    void decodeFunction() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("one", scalar(db,
            "SELECT DECODE(id, 1, 'one', 'other') FROM userdb.users WHERE id = 1"));
      }
    }

    @Test
    @Disabled("待支持: 解析器不支持 LEFT SEMI JOIN 语法（SEMI 语义经 EXISTS/IN 表达），待支持")
    void leftSemiJoinSyntax() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"),
            rows(db, "SELECT u.name FROM userdb.users u LEFT SEMI JOIN "
                + "orderdb.orders o ON o.user_id = u.id ORDER BY u.name"));
      }
    }
  }

  // ---------- NULL 三值逻辑补充（参考 PostgreSQL regress boolean 用例） ----------

  @Nested
  @DisplayName("NULL 三值逻辑补充场景")
  class NullSemantics {

    @Test void betweenNullKeyExcluded() throws Exception {
      // user_id ∈ {1, 9, NULL}：BETWEEN 0 AND 9 命中 1、9，NULL 为 UNKNOWN
      try (CrossDb db = corePlusPings()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id BETWEEN 0 AND 9"));
      }
    }

    @Test void notBetweenNullKeyExcluded() throws Exception {
      // NOT BETWEEN：1、9 均在界内为假，NULL 仍 UNKNOWN → 0 行
      try (CrossDb db = corePlusPings()) {
        assertEquals("0", scalar(db, "SELECT COUNT(*) FROM pingdb.pings "
            + "WHERE user_id NOT BETWEEN 0 AND 9"));
      }
    }

    @Test void likeOnNullColumnExcluded() throws Exception {
      try (CrossDb db = corePlusPings()) {
        assertEquals("1", scalar(db, "SELECT COUNT(*) FROM pingdb.pings WHERE note LIKE 'a%'"));
      }
    }
  }

  // ---------- 递归 CTE（参考 PostgreSQL regress with / Calcite RepeatUnion 用例） ----------

  @Nested
  @DisplayName("递归 CTE 场景")
  class RecursiveCtes {

    @Test void recursiveWalkOverRealTable() throws Exception {
      // 种子 user 1，逐层 +1 关联到 user 3：{1,2,3}
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "WITH RECURSIVE t(id, name) AS "
            + "(SELECT id, name FROM userdb.users WHERE id = 1 "
            + "UNION ALL SELECT u.id, u.name FROM userdb.users u JOIN t ON u.id = t.id + 1) "
            + "SELECT COUNT(*) FROM t"));
      }
    }

    @Test void recursiveUnionDistinctTerminates() throws Exception {
      // UNION（去重）递归在重复行处自然终止
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "WITH RECURSIVE t(n) AS "
            + "(VALUES (1) UNION SELECT n + 1 FROM t WHERE n < 3) SELECT COUNT(*) FROM t"));
      }
    }

    @Test void recursiveSeedFromOtherDb() throws Exception {
      // 种子取自 orderdb、迭代取自 userdb（递归跨库组合）
      try (CrossDb db = core()) {
        assertEquals("3", scalar(db, "WITH RECURSIVE t(uid) AS "
            + "(SELECT user_id FROM orderdb.orders WHERE id = 100 "
            + "UNION ALL SELECT u.id FROM userdb.users u JOIN t ON u.id = t.uid + 1) "
            + "SELECT COUNT(*) FROM t"));
      }
    }

    @Test void recursiveOutputWithLocalTopN() throws Exception {
      // 递归输出套 ORDER BY + LIMIT（本地裁剪 1..9 中前 2 行）
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db, "WITH RECURSIVE t(n) AS "
            + "(VALUES (1) UNION ALL SELECT n + 1 FROM t WHERE n < 9) "
            + "SELECT COUNT(*) FROM (SELECT n FROM t ORDER BY n LIMIT 2) x"));
      }
    }
  }

  // ---------- 只读硬化补充（参考 README「只读硬化」契约） ----------

  @Nested
  @DisplayName("只读硬化补充场景")
  class ReadOnlyGuard {

    private static final String[] NON_QUERIES = {
        "UPDATE userdb.users SET name = 'x' WHERE id = 1",
        "DELETE FROM userdb.users WHERE id = 1",
        "INSERT INTO userdb.users VALUES (9, 'eve')",
        "CREATE TABLE userdb.t9x(id INT)",
        "DROP TABLE userdb.users",
        "ALTER TABLE userdb.users ADD COLUMN c INT",
        "GRANT SELECT ON userdb.users TO PUBLIC",
        "SET @v = 1",
        "MERGE INTO userdb.users u USING userdb.small s ON (u.id = s.id)"
            + " WHEN MATCHED THEN UPDATE SET name = 'x'",
        "TRUNCATE TABLE userdb.users",
    };

    @Test void queryRejectsAllNonQueryForms() {
      for (String sql : NON_QUERIES) {
        try (CrossDb db = core()) {
          // 契约：一律拒绝。可解析的 DML 带「只读引擎」提示；
          // DDL 等不可解析形态在解析层即抛错（同为拒绝）
          assertThrows(SQLException.class, () -> db.query(sql), "应拒绝: " + sql);
        } catch (SQLException closeError) {
          throw new IllegalStateException(closeError);
        }
      }
    }

    @Test void dmlRejectionCarriesReadOnlyMessage() throws Exception {
      // 可解析的 DML 必须以「只读引擎」文案拒绝（区别于解析错误）
      try (CrossDb db = core()) {
        SQLException e = assertThrows(SQLException.class,
            () -> db.query("UPDATE userdb.users SET name = 'x' WHERE id = 1"));
        assertTrue(e.getMessage().contains("只读"), e.getMessage());
      }
    }

    @Test void explainAndAnalyzeRejectNonQuery() throws Exception {
      try (CrossDb db = core()) {
        assertThrows(SQLException.class, () -> db.explain(
            "DELETE FROM userdb.users WHERE id = 1"));
        assertThrows(SQLException.class, () -> db.analyze(
            "DELETE FROM userdb.users WHERE id = 1"));
      }
    }
  }

  // ---------- safeMode 补充（参考 README「safeMode」边界） ----------

  @Nested
  @DisplayName("safeMode 补充场景")
  class SafeModeEdges {

    @Test void aggregateCountsAsReductionAndPasses() throws Exception {
      // 聚合视为有归约：全表 COUNT 放行
      try (CrossDb db = core().safeMode()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users"));
      }
    }

    @Test void bareSortWithoutLimitRejected() throws Exception {
      // 无 LIMIT 的 ORDER BY 裁不掉行：仍属全表拉取，运行期拦截
      try (CrossDb db = core().safeMode()) {
        SQLException e = assertThrows(SQLException.class,
            () -> db.query("SELECT id FROM userdb.users ORDER BY id"));
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(root instanceof CrossDbUnsafeQueryException, "根因应是 safeMode 拦截: " + root);
      }
    }

    @Test void bindJoinWithDriverFilterPasses() throws Exception {
      // 行级 Bind Join：驱动侧 WHERE 下推、内表侧由传递谓词补过滤 → 双侧均非裸拉取
      try (CrossDb db = core().safeMode()) {
        assertEquals(List.of("alice", "alice"),
            rows(db, "SELECT u.name FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1"));
      }
    }

    @Test void aggregateTransposeBarePullRejected() throws Exception {
      // COUNT(*) over JOIN 被优化器转置为「两侧单列拉取」的原生连接，
      // 右侧为无过滤全表拉取——safeMode 零容忍，运行期拦截（预期防呆）
      try (CrossDb db = core().safeMode()) {
        SQLException e = assertThrows(SQLException.class,
            () -> scalar(db, "SELECT COUNT(*) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1"));
        Throwable root = e;
        while (root.getCause() != null) {
          root = root.getCause();
        }
        assertTrue(root instanceof CrossDbUnsafeQueryException, "根因应是 safeMode 拦截: " + root);
      }
    }
  }

  // ---------- explain / analyze 补充（参考 README 可观测性契约） ----------

  @Nested
  @DisplayName("explain / analyze 补充场景")
  class Observability {

    @Test void analyzeOnAggregateOverJoinReportsBindJoin() throws Exception {
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id");
        assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
        assertTrue(report.contains("bindJoin"), report);
      }
    }

    @Test void analyzeOnSetOperationReportsBothSources() throws Exception {
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT COUNT(*) FROM ("
            + "SELECT id FROM userdb.users UNION ALL SELECT id FROM orderdb.orders) t");
        assertTrue(report.contains("userdb") && report.contains("orderdb"), report);
      }
    }
  }

  // ---------- ResultSet 边界（参考 JDBC 契约） ----------

  @Nested
  @DisplayName("ResultSet 边界场景")
  class ResultSetEdges {

    @Test void unknownColumnMessageListsCandidates() throws Exception {
      try (CrossDb db = core();
          ResultSet rs = db.query("SELECT id FROM userdb.users")) {
        assertTrue(rs.next());
        SQLException e = assertThrows(SQLException.class, () -> rs.getString("NOPE"));
        assertTrue(e.getMessage().contains("列不存在"),
            "应提示列不存在: " + e.getMessage());
      }
    }

    @Test void getterBeforeNextRejected() throws Exception {
      try (CrossDb db = core();
          ResultSet rs = db.query("SELECT id FROM userdb.users")) {
        assertThrows(SQLException.class, () -> rs.getInt(1));
      }
    }

    @Test void getBytesOnVarcharRejected() throws Exception {
      try (CrossDb db = core();
          ResultSet rs = db.query("SELECT name FROM userdb.users WHERE id = 1")) {
        assertTrue(rs.next());
        SQLException e = assertThrows(SQLException.class, () -> rs.getBytes(1));
        assertTrue(e.getMessage().contains("byte[]"), e.getMessage());
      }
    }
  }
}

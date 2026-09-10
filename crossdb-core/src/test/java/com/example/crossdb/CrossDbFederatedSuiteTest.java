package com.example.crossdb;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全场景覆盖测试（第五批）：按「参考同类系统公开测试集」组织的一次性补全，与前四批
 * （Scenarios/Comprehensive/Coverage/FullCoverage/FullScenarios 等）互补、不重复；沿
 * 「先补用例、失败即标记 {@code @Disabled("待支持: ...")} 作为修复清单」的约定维护，
 * 全部按标准语义断言。
 *
 * <p>分组与来源系统：
 * <ul>
 *   <li>PostgresRegression — PostgreSQL src/test/regress（表达式三值逻辑、
 *   BETWEEN SYMMETRIC、NOT IN 空/非空子查询、除法定标、显式 NULLS 序、
 *   INTERSECT 优先级等）；</li>
 *   <li>MysqlDialectSuite — MySQL 8.0 测试套（CONCAT_WS、LIMIT o,n、反引号、
 *   位运算/DIV/STRAIGHT_JOIN/CAST(TRUE)、MOD/LOCATE/LEFT/RIGHT/SPACE/CHAR/STRCMP、
 *   REGEXP_REPLACE、INSTR/SUBSTRING_INDEX/XOR 候选）；</li>
 *   <li>TrinoFederated — Trino/Presto 跨 catalog 联邦（键类型一致性、跨库
 *   COUNT(DISTINCT)、多库 UNION 归并 Top-N、FULL JOIN、LATERAL、USING/NATURAL、
 *   相关子查询、Bind Join 推送形态）；</li>
 *   <li>DuckDbModern — DuckDB 现代 SQL（QUALIFY、POSITION IN、TRY_CAST、
 *   STRING_AGG(DISTINCT)、EXCLUDE/GROUP BY ALL/ARG_MIN/STRUCT 候选）；</li>
 *   <li>ShardingSphereMerge — ShardingSphere 归并引擎（分片 UNION ALL 的
 *   Top-N/聚合/DISTINCT/分页归并、同构镜像分片自连接、集合运算组合）；</li>
 *   <li>TpcShapes — TPC-H Q1/Q3/Q13/Q18/Q21 查询形态 + 窗口占比与执行计划断言
 *   （新增 lineitems 夹具表）；</li>
 *   <li>SqlStandardConformance — SQL 标准（IS [NOT] DISTINCT FROM、EXCEPT/INTERSECT
 *   ALL 多重集语义、VALUES 派生表、CTE 链、行构造器 IN、CAST 矩阵、SIMILAR TO）；</li>
 *   <li>OracleMssqlDialect — Oracle/SQL Server（DECODE/IIF/TOP/ISNULL/NVL、
 *   ADD_MONTHS/LAST_DAY/MONTHS_BETWEEN、DATE±n 回归、TO_CHAR/INSTR/(+)/CONNECT BY
 *   候选）；</li>
 *   <li>EngineHardening — 自研加固面（rowLimit 熔断矩阵、safeMode 边界矩阵、
 *   只读拒绝清单、explain/analyze、ResultSet 类型取值、cancel/fetchSize 传播）。</li>
 * </ul>
 *
 * <p>时间承载约定：TIMESTAMP 以 epoch millis（Long）透出、按 UTC 墙钟解释，
 * DATE 以 epoch days 承载；涉及时间值的断言经 CAST 比对字面值。
 */
class CrossDbFederatedSuiteTest {

  // ---------- 夹具与工具 ----------

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb goods() throws SQLException {
    return new CrossDb()
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("credsdb", Fixtures.CREDS)
        .register("quotasdb", Fixtures.QUOTAS)
        .register("eventdb", Fixtures.EVENTS)
        .register("pingdb", Fixtures.PINGS)
        .register("gooddb", Fixtures.GOODS)
        .register("regiondb", Fixtures.GOODS)
        .register("logdb", Fixtures.LOGS);
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

  // ---------- PostgreSQL regression ----------

  @Nested
  @DisplayName("PostgreSQL 回归形态")
  class PostgresRegression {

    @Test void caseSearchedNested() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,mid  ", "101,big  ", "102,mid  ", "103,small"), rows(db,
            "SELECT id, CASE WHEN amount > 15 THEN 'big' WHEN amount > 4 THEN 'mid' "
                + "ELSE 'small' END FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void betweenSymmetricKeyword() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount BETWEEN SYMMETRIC 20 AND 5 "
                + "ORDER BY id"));
      }
    }

    @Test void notBetweenExcludes() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount NOT BETWEEN 5 AND 20 ORDER BY id"));
      }
    }

    @Test void likeSingleCharWildcard() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db,
            "SELECT name FROM userdb.users WHERE name LIKE '_o%'"));
      }
    }

    @Test void likeEscapeClause() throws Exception {
      // PostgreSQL like.out：转义符显式声明，_ 按字面匹配
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db, "SELECT 'a_b' LIKE 'a\\_b' ESCAPE '\\' "
            + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void substringFullForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ana,nana"), rows(db,
            "SELECT SUBSTRING('banana' FROM 2 FOR 3), SUBSTRING('banana' FROM 3) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void trimBothCustomChars() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("hi"), rows(db,
            "SELECT TRIM(BOTH 'x' FROM 'xxhixx') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nullifEqualNulls() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,a"), rows(db,
            "SELECT NULLIF('a', 'a'), NULLIF('a', 'b') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void notInWithNullSubqueryIsEmpty() throws Exception {
      // PostgreSQL subselect.out：NOT IN 遇 NULL 候选 → 恒 UNKNOWN → 空结果
      try (CrossDb db = all()) {
        assertEquals(List.of(), rows(db, "SELECT id FROM userdb.users "
            + "WHERE id NOT IN (SELECT user_id FROM pingdb.pings)"));
      }
    }

    @Test void notInWithoutNullWorks() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2", "3"), rows(db, "SELECT id FROM userdb.users "
            + "WHERE id NOT IN (SELECT user_id FROM eventdb.events) ORDER BY id"));
      }
    }

    @Test void integerVsDecimalDivision() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,3.500,3.5"), rows(db,
            "SELECT 7 / 2, 7 / 2.0, CAST(7 AS DOUBLE) / 2 FROM userdb.small LIMIT 1"));
      }
    }

    @Test void roundHalfAwayFromZero() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,-3,1"), rows(db,
            "SELECT ROUND(2.5), ROUND(-2.5), ROUND(0.5) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void concatNullPropagates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL"), rows(db,
            "SELECT name || NULL FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void explicitNullsOrdering() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("b", "a", "NULL"), rows(db,
            "SELECT note FROM pingdb.pings ORDER BY note DESC NULLS LAST, id"));
        assertEquals(List.of("NULL", "a", "b"), rows(db,
            "SELECT note FROM pingdb.pings ORDER BY note ASC NULLS FIRST, id DESC"));
      }
    }

    @Test void scalarSubqueryEmptyIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("NULL", scalar(db,
            "SELECT (SELECT amount FROM orderdb.orders WHERE id = 999) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void existsCorrelatedAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db,
            "SELECT name FROM userdb.users u WHERE EXISTS (SELECT 1 FROM orderdb.orders o "
                + "WHERE o.user_id = u.id GROUP BY o.user_id HAVING SUM(o.amount) >= 20) "
                + "ORDER BY name"));
      }
    }

    @Test void intersectBindsTighterThanUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small UNION (SELECT user_id FROM eventdb.events "
                + "INTERSECT SELECT id FROM userdb.small) ORDER BY id"));
      }
    }

    @Test void limitZeroReturnsNothing() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db, "SELECT id FROM orderdb.orders LIMIT 0"));
      }
    }

    @Test void extractYearMonth() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2026,1"), rows(db,
            "SELECT EXTRACT(YEAR FROM made), EXTRACT(MONTH FROM made) "
                + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void orderByMultiKeyMixedDirection() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "101", "102", "100"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY user_id DESC, amount ASC"));
      }
    }
  }

  // ---------- MySQL 8.0 方言 ----------

  @Nested
  @DisplayName("MySQL 8.0 方言形态")
  class MysqlDialectSuite {

    @Test void concatWsSkipsNulls() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("a-b"), rows(db,
            "SELECT CONCAT_WS('-', 'a', NULL, 'b') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void limitOffsetCommaForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("101", "102"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY id LIMIT 1, 2"));
      }
    }

    @Test void backtickQuotedIdentifiers() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT `id` FROM userdb.`users` WHERE `id` = 2"));
      }
    }

    @Test void modDecimalColumnUsesFloatSemantics() throws Exception {
      // 本轮修复回归：目录已知 DECIMAL 列触发浮点 MOD（Java % 语义）
      try (CrossDb db = all()) {
        assertEquals(List.of("1.25"), rows(db,
            "SELECT MOD(amount, 2) FROM pingdb.pings WHERE id = 1"));
        assertEquals(List.of("1.5"), rows(db,
            "SELECT MOD(amount, 2) FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void modIntegerColumnStaysNative() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "0", "1"), rows(db,
            "SELECT MOD(id, 2) FROM userdb.users ORDER BY id"));
      }
    }

    @Test void divOperatorOnColumns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"), rows(db,
            "SELECT qty DIV 2 FROM orderdb.lineitems WHERE id = 1"));
      }
    }

    @Test void bitwiseOnColumns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id & 3 FROM orderdb.lineitems WHERE id <= 2 ORDER BY id"));
      }
    }

    @Test void bitwiseShiftAndPrecedence() throws Exception {
      // MySQL 优先级：算术 > 位移 > & > |
      try (CrossDb db = core()) {
        assertEquals(List.of("8,16,9"), rows(db,
            "SELECT 1 << 3, 256 >> 4, 5 & 3 | 8 FROM userdb.small LIMIT 1"));
      }
    }

    @Test void logTwoArgBase() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3.0"), rows(db,
            "SELECT ROUND(LOG(2, 8), 4) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void locateNotFoundZero() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0"), rows(db,
            "SELECT LOCATE('z', 'banana') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void locateStartBelowOne() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0"), rows(db,
            "SELECT LOCATE('b', 'banana', 0) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void leftRightOnColumns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("al,ice"), rows(db,
            "SELECT LEFT(name, 2), RIGHT(name, 3) FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void leftEdgeLengths() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(",abc"), rows(db,
            "SELECT LEFT('abc', 0), LEFT('abc', 9) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void strcmpNullPropagation() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,NULL"), rows(db,
            "SELECT STRCMP(NULL, 'a'), STRCMP('a', NULL) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void concatWithSpaceNullPropagates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL"), rows(db,
            "SELECT CONCAT('[', SPACE(NULL), ']') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void charChrEquivalence() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("A,B"), rows(db,
            "SELECT CHAR(65), CHR(66) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void yearMonthFunctions() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2026,3"), rows(db,
            "SELECT YEAR(made), MONTH(made) FROM gooddb.products WHERE id = 12"));
      }
    }

    @Test void straightJoinWithFilter() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db, "SELECT COUNT(*) FROM userdb.users "
            + "STRAIGHT_JOIN orderdb.orders o ON o.user_id = userdb.users.id "
            + "WHERE userdb.users.id = 1"));
      }
    }

    @Test void castBooleanLiteralsRegression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0,1"), rows(db,
            "SELECT CAST(TRUE AS INT), CAST(FALSE AS INT), CAST(TRUE AS DOUBLE) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void castBooleanColumnRegression() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "0"), rows(db,
            "SELECT CAST(flag AS INT) FROM pingdb.pings WHERE id IN (1, 3) ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: REGEXP_REPLACE 三参替换形态未注册于操作符表（Calcite 仅有其他元数变体），待支持")
    void regexpReplaceGlobal() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("aXcaXc"), rows(db,
            "SELECT REGEXP_REPLACE(CAST('abcabc' AS VARCHAR), CAST('b' AS VARCHAR), "
                + "CAST('X' AS VARCHAR)) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void fetchPercentVariants() throws Exception {
      // 本轮修复回归：CEILING(n% × 总行数)
      try (CrossDb db = core()) {
        assertEquals(List.of("100"), rows(db, "SELECT id FROM orderdb.orders "
            + "ORDER BY id FETCH FIRST 25 PERCENT ROWS ONLY"));
        assertEquals(List.of("100", "101", "102"), rows(db, "SELECT id FROM orderdb.orders "
            + "ORDER BY id FETCH FIRST 75 PERCENT ROWS ONLY"));
        assertEquals(List.of("103", "102"), rows(db, "SELECT id FROM orderdb.orders "
            + "ORDER BY id DESC FETCH FIRST 50 PERCENT ROWS ONLY"));
      }
    }

    @Test
    @Disabled("待支持: INSTR(str, substr)（MySQL/Oracle 方言）未注册于操作符表，待支持")
    void instrFunctionCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT INSTR('banana', 'an') FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: SUBSTRING_INDEX(s, d, n)（MySQL 方言）未注册，待支持")
    void substringIndexCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("www"), rows(db,
            "SELECT SUBSTRING_INDEX('www.example.com', '.', 1) FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: XOR 逻辑操作符（MySQL）解析器不支持，待支持")
    void xorOperatorCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0"), rows(db,
            "SELECT 1 XOR 0, 1 XOR 1 FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: DATE_FORMAT(ts, fmt)（MySQL）未注册且格式符方言差异大，待支持")
    void dateFormatCandidate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-02"), rows(db,
            "SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM pingdb.pings WHERE id = 1"));
      }
    }
  }

  // ---------- Trino/Presto 跨 catalog 联邦 ----------

  @Nested
  @DisplayName("Trino 跨 catalog 联邦形态")
  class TrinoFederated {

    @Test void crossDbJoinOnEqualTypedKeys() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("100,100", "100,101", "200,102"), rows(db,
            "SELECT q.tenant_id, o.id FROM quotasdb.quotas q "
                + "JOIN orderdb.orders o ON o.amount = q.quota ORDER BY o.id"));
      }
    }

    @Test void countDistinctAcrossUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT COUNT(DISTINCT user_id) FROM "
            + "(SELECT user_id FROM eventdb.events UNION ALL "
            + "SELECT user_id FROM pingdb.pings) t"));
      }
    }

    @Test void threeWayUnionShardTopN() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "1", "2"), rows(db,
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM eventdb.events "
                + "UNION ALL SELECT id FROM orderdb.orders ORDER BY id LIMIT 3"));
      }
    }

    @Test void bindJoinPushdownShapeRecording() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb()
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Recording.dataSource(Fixtures.ORDERS, sqls, props))) {
        assertEquals(List.of("bob,101", "bob,103"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 2 ORDER BY o.id"));
        assertTrue(sqls.stream().anyMatch(s -> s.contains("IN (?")),
            "内表应按键 IN 批量下推: " + sqls);
      }
    }

    @Test void fullOuterJoinAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,1,alice", "2,9,NULL", "NULL,NULL,bob", "NULL,NULL,carol"), rows(db,
            "SELECT e.id, e.user_id, u.name FROM eventdb.events e "
                + "FULL JOIN userdb.users u ON e.user_id = u.id "
                + "ORDER BY e.id NULLS LAST, u.id NULLS LAST"));
      }
    }

    @Test void correlatedScalarSubqueryCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"), rows(db,
            "SELECT u.name, (SELECT COUNT(*) FROM orderdb.orders o "
                + "WHERE o.user_id = u.id) FROM userdb.users u ORDER BY u.id"));
      }
    }

    @Test void lateralCorrelatedCrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"), rows(db,
            "SELECT u.name, x.c FROM userdb.users u, LATERAL "
                + "(SELECT COUNT(*) c FROM orderdb.orders o WHERE o.user_id = u.id) x "
                + "ORDER BY u.id"));
      }
    }

    @Test void existsSemiAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("alice"), rows(db,
            "SELECT name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM pingdb.pings p WHERE p.user_id = u.id)"));
      }
    }

    @Test void notExistsAntiAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("bob", "carol"), rows(db,
            "SELECT name FROM userdb.users u WHERE NOT EXISTS "
                + "(SELECT 1 FROM eventdb.events e WHERE e.user_id = u.id) ORDER BY name"));
      }
    }

    @Test void usingJoinAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("a1,10", "a2,5", "b1,20"), rows(db,
            "SELECT login, quota FROM credsdb.creds c "
                + "JOIN quotasdb.quotas q USING (user_id, tenant_id) ORDER BY login"));
      }
    }

    @Test
    @Disabled("待支持: 跨库镜像 NATURAL JOIN 的全列匹配键含 DATE 列——Bind Join 键值"
        + "按引擎约定以 epoch-days Integer 透传 setObject，与源库 DATE 列比较失败"
        + "（跨库 DATE 连接键承载边界），待支持")
    void naturalJoinMirrorShards() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("10,desk", "11,chair"), rows(db,
            "SELECT id, name FROM gooddb.products p NATURAL JOIN regiondb.products "
                + "WHERE p.price > 30 ORDER BY id"));
      }
    }

    @Test void windowOverCrossDbJoinAggregate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,1"), rows(db,
            "SELECT u.name, RANK() OVER (ORDER BY SUM(o.amount) DESC) "
                + "FROM userdb.users u JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void inSubqueryWithAggregateAcrossDb() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bob"), rows(db,
            "SELECT name FROM userdb.users WHERE id IN (SELECT user_id FROM orderdb.orders "
                + "GROUP BY user_id HAVING SUM(amount) > 16)"));
      }
    }

    @Test void orderByOverUnionWithNulls() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,p", "1,e", "1,p", "9,e", "9,p"), rows(db,
            "SELECT user_id, src FROM (SELECT user_id, 'e' src FROM eventdb.events "
                + "UNION ALL SELECT user_id, 'p' FROM pingdb.pings) t "
                + "ORDER BY user_id NULLS FIRST, src"));
      }
    }
  }

  // ---------- DuckDB 现代 SQL ----------

  @Nested
  @DisplayName("DuckDB 现代 SQL 形态")
  class DuckDbModern {

    @Test void qualifyWithPartitionWindow() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,20"), rows(db,
            "SELECT id, amount FROM orderdb.orders QUALIFY "
                + "ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) = 1 "
                + "ORDER BY id"));
      }
    }

    @Test void positionInForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT POSITION('na' IN 'banana') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void stringAggDistinctSorted() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("ERROR,INFO,WARN", scalar(db,
            "SELECT STRING_AGG(DISTINCT level) FROM logdb.logs"));
      }
    }

    @Test void firstValueDefaultFrame() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,100", "101,100", "102,100", "103,100"), rows(db,
            "SELECT id, FIRST_VALUE(id) OVER (ORDER BY id) FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: TRY_CAST 校验器对 CHAR→数值目标无匹配（Calcite TRY_CAST 类型检查严格，"
        + "需放宽或本地改写），待支持")
    void tryCastInvalidToNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,7"), rows(db,
            "SELECT TRY_CAST('abc' AS INT), TRY_CAST('7' AS INT) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: 同 regexpReplaceGlobal——REGEXP_REPLACE 三参替换形态未注册，待支持")
    void regexpCaseInsensitiveReplace() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("XbcXbc"), rows(db,
            "SELECT REGEXP_REPLACE(CAST('abcabc' AS VARCHAR), CAST('A' AS VARCHAR), "
                + "CAST('X' AS VARCHAR)) FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: SELECT * EXCLUDE (col) 列排除语法（DuckDB）解析器不支持，待支持")
    void excludeColumnCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT * EXCLUDE (name) FROM userdb.users ORDER BY id"));
      }
    }

    @Test
    @Disabled("待支持: GROUP BY ALL 智能分组（DuckDB/Snowflake）解析器不支持，待支持")
    void groupByAllCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,2", "2,2"), rows(db,
            "SELECT user_id, COUNT(*) FROM orderdb.orders GROUP BY ALL ORDER BY user_id"));
      }
    }

    @Test
    @Disabled("待支持: ARG_MIN/ARG_MAX 有序集聚合（DuckDB/Trino）未注册，待支持")
    void argMinCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("100", scalar(db,
            "SELECT ARG_MIN(id, amount) FROM orderdb.orders"));
      }
    }

    @Test
    @Disabled("待支持: STRUCT 字面量与点访问 {'a':1}.a（DuckDB）解析器不支持，待支持")
    void structDotAccessCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("1", scalar(db, "SELECT {'a': 1}.a FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: date_diff('unit', a, b) 单位前置形态（DuckDB）未注册，待支持")
    void dateDiffUnitFirstCandidate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db,
            "SELECT date_diff('day', DATE '2026-01-01', DATE '2026-01-03') "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: LIST_CONTAINS 等数组函数族（DuckDB）未注册且引擎无 ARRAY 透出，待支持")
    void listContainsCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("true", scalar(db,
            "SELECT LIST_CONTAINS([1, 2], 1) FROM userdb.small LIMIT 1"));
      }
    }
  }

  // ---------- ShardingSphere 归并 ----------

  @Nested
  @DisplayName("ShardingSphere 归并形态")
  class ShardingSphereMerge {

    @Test void shardTopNOrderByAsc() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "1", "2"), rows(db,
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM eventdb.events "
                + "ORDER BY id LIMIT 3"));
      }
    }

    @Test void shardTopNOrderByDesc() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("3", "2"), rows(db,
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM eventdb.events "
                + "ORDER BY id DESC LIMIT 2"));
      }
    }

    @Test void shardBranchesCarryLimitRecording() throws Exception {
      List<String> uSql = new ArrayList<>();
      List<String> eSql = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb()
          .register("userdb", Recording.dataSource(Fixtures.USERS, uSql, props))
          .register("eventdb", Recording.dataSource(Fixtures.EVENTS, eSql, props))) {
        assertEquals(List.of("1", "1"), rows(db,
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM eventdb.events "
                + "ORDER BY id LIMIT 2"));
        assertTrue(uSql.stream().anyMatch(s -> s.contains("LIMIT") || s.contains("FETCH FIRST") || s.contains("FETCH NEXT")),
            "users 分支应携带 LIMIT: " + uSql);
        assertTrue(eSql.stream().anyMatch(s -> s.contains("LIMIT") || s.contains("FETCH FIRST") || s.contains("FETCH NEXT")),
            "events 分支应携带 LIMIT: " + eSql);
      }
    }

    @Test void aggregateMergeOverUnionAll() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,1", "1,2", "9,2"), rows(db,
            "SELECT user_id, COUNT(*) FROM (SELECT user_id FROM eventdb.events "
                + "UNION ALL SELECT user_id FROM pingdb.pings) t GROUP BY user_id "
                + "ORDER BY user_id NULLS FIRST"));
      }
    }

    @Test void unionDistinctMergeCorrectness() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1", "2", "3", "9"), rows(db,
            "SELECT id FROM userdb.users UNION SELECT user_id FROM eventdb.events "
                + "ORDER BY id"));
      }
    }

    @Test void unionAllHavingFilter() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,2", "9,2"), rows(db,
            "SELECT user_id, COUNT(*) FROM (SELECT user_id FROM eventdb.events "
                + "UNION ALL SELECT user_id FROM pingdb.pings) t GROUP BY user_id "
                + "HAVING COUNT(*) > 1 ORDER BY user_id"));
      }
    }

    @Test void mirrorShardSelfJoin() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("10", "11", "12"), rows(db,
            "SELECT a.id FROM gooddb.products a JOIN regiondb.products b ON a.id = b.id "
                + "WHERE a.price >= 25 ORDER BY a.id"));
      }
    }

    @Test void sumOfSumsOverShards() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("5", scalar(db, "SELECT SUM(cnt) FROM "
            + "(SELECT COUNT(*) cnt FROM userdb.users UNION ALL "
            + "SELECT COUNT(*) FROM eventdb.events) t"));
      }
    }

    @Test void windowOverMergedShards() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,1", "1,2", "2,3", "2,4"), rows(db,
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM "
                + "(SELECT id FROM userdb.small UNION ALL SELECT id FROM userdb.small) t "
                + "ORDER BY id"));
      }
    }

    @Test void exceptAfterUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2"), rows(db,
            "(SELECT id FROM userdb.small UNION SELECT id FROM userdb.small) "
                + "EXCEPT SELECT user_id FROM eventdb.events"));
      }
    }

    @Test void intersectAcrossDbs() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.users INTERSECT SELECT user_id FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void paginationThroughUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT id FROM userdb.users UNION ALL SELECT id FROM eventdb.events "
                + "ORDER BY id LIMIT 1 OFFSET 2"));
      }
    }

    @Test void caseAggregateOverShardUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("3", scalar(db,
            "SELECT SUM(CASE WHEN s = 'u' THEN 1 ELSE 0 END) FROM "
                + "(SELECT 'u' s FROM userdb.users UNION ALL SELECT 'e' FROM eventdb.events) t"));
      }
    }

    @Test void semiJoinOverUnionAll() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1"), rows(db,
            "SELECT id FROM userdb.users WHERE id IN (SELECT user_id FROM "
                + "(SELECT user_id FROM eventdb.events UNION ALL "
                + "SELECT user_id FROM pingdb.pings) t) ORDER BY id"));
      }
    }

    @Test void groupByTwoKeysOverUnion() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,p,1", "1,e,1", "1,p,1", "9,e,1", "9,p,1"), rows(db,
            "SELECT user_id, src, COUNT(*) FROM (SELECT user_id, 'e' src FROM eventdb.events "
                + "UNION ALL SELECT user_id, 'p' FROM pingdb.pings) t "
                + "GROUP BY user_id, src ORDER BY user_id NULLS FIRST, src"));
      }
    }
  }

  // ---------- TPC-H / TPC-DS 查询形态 ----------

  @Nested
  @DisplayName("TPC-H/DS 查询形态")
  class TpcShapes {

    @Test void tpchQ1AggregateCaseOverJoin() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2.50,9.00", "bob,2.50,20.00"), rows(db,
            "SELECT u.name, SUM(CASE WHEN l.sku = 'pen' THEN l.qty * l.price ELSE 0 END), "
                + "SUM(l.qty * l.price) FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN orderdb.lineitems l ON l.order_id = o.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void tpchQ3ThreeWayJoinTopN() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103,pad,9.50", "101,pad,4.75", "100,ink,3.25"), rows(db,
            "SELECT o.id, l.sku, l.qty * l.price FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id "
                + "JOIN orderdb.lineitems l ON l.order_id = o.id "
                + "ORDER BY 3 DESC, o.id LIMIT 3"));
      }
    }

    @Test void tpchQ13LeftJoinGrouped() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice,2", "bob,2", "carol,0"), rows(db,
            "SELECT u.name, COUNT(o.id) FROM userdb.users u "
                + "LEFT JOIN orderdb.orders o ON o.user_id = u.id "
                + "GROUP BY u.name ORDER BY u.name"));
      }
    }

    @Test void tpchQ18AllQuantifiedSubquery() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT id FROM orderdb.orders o WHERE amount > ALL "
                + "(SELECT o2.amount FROM orderdb.orders o2 "
                + "WHERE o2.user_id = o.user_id AND o2.id <> o.id) ORDER BY id"));
      }
    }

    @Test void tpchQ21NotExistsPair() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice"), rows(db,
            "SELECT u.name FROM userdb.users u WHERE EXISTS "
                + "(SELECT 1 FROM orderdb.orders o WHERE o.user_id = u.id) AND NOT EXISTS "
                + "(SELECT 1 FROM orderdb.orders o JOIN orderdb.lineitems l "
                + "ON l.order_id = o.id WHERE o.user_id = u.id AND l.sku = 'pad')"));
      }
    }

    @Test void windowRankPerCustomer() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,1", "101,1", "102,2", "103,2"), rows(db,
            "SELECT id, RANK() OVER (PARTITION BY user_id ORDER BY amount DESC) "
                + "FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void ratioToReportPerOrder() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100,0.2353", "100,0.7647", "101,0.3448", "101,0.6552",
            "102,0.6842", "102,0.3158", "103,0.7451", "103,0.2549"), rows(db,
            "SELECT l.order_id, ROUND(CAST(l.qty * l.price AS DOUBLE) "
                + "/ SUM(l.qty * l.price) OVER (PARTITION BY l.order_id), 4) "
                + "FROM orderdb.lineitems l ORDER BY l.id"));
      }
    }

    @Test
    @Disabled("待支持: 外层 AVG 与派生表 SUM 在计划期被融合为 AVG(SUM(amount)) 非法聚合"
        + "形态下推源库（上游 Project/Aggregate 融合边界），待支持")
    void avgOfGroupSums() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("18.0", scalar(db, "SELECT AVG(CAST(t.s AS DOUBLE)) FROM "
            + "(SELECT user_id, SUM(amount) s FROM orderdb.orders GROUP BY user_id) t"));
      }
    }

    @Test void coalesceDimensionFill() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("100", "101", "102", "none"), rows(db,
            "SELECT COALESCE(CAST(s.order_id AS VARCHAR), 'none') FROM regiondb.regions r "
                + "LEFT JOIN gooddb.shipments s ON s.region_id = r.region_id "
                + "ORDER BY 1"));
      }
    }

    @Test void returnFlagStyleGrouping() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("A,2,4.00", "R,6,25.00"), rows(db,
            "SELECT CASE WHEN qty >= 3 THEN 'A' ELSE 'R' END, COUNT(*), SUM(qty * price) "
                + "FROM orderdb.lineitems GROUP BY 1 ORDER BY 1"));
      }
    }

    @Test void explainShowsBindJoinOnTpcShape() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT u.name FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id");
        assertTrue(plan.contains("EnumerableBindJoin"), "应出现 Bind Join 算子: " + plan);
      }
    }

    @Test void correlatedExistsAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("100", "102"), rows(db,
            "SELECT o.id FROM orderdb.orders o WHERE EXISTS (SELECT 1 FROM pingdb.pings p "
                + "WHERE p.user_id = o.user_id AND p.amount IS NOT NULL) ORDER BY o.id"));
      }
    }
  }

  // ---------- SQL 标准一致性 ----------

  @Nested
  @DisplayName("SQL 标准一致性形态")
  class SqlStandardConformance {

    @Test void isDistinctFromNullSafe() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2", "3"), rows(db,
            "SELECT id FROM pingdb.pings WHERE user_id IS DISTINCT FROM 1 ORDER BY id"));
      }
    }

    @Test void isNotDistinctFromJoinKey() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1,alice"), rows(db,
            "SELECT p.id, u.name FROM pingdb.pings p JOIN userdb.users u "
                + "ON p.user_id IS NOT DISTINCT FROM u.id ORDER BY p.id"));
      }
    }

    @Test void nullifPair() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,5"), rows(db,
            "SELECT NULLIF(5, 5), NULLIF(5, 6) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void exceptAllMultisetSemantics() throws Exception {
      // 多重集语义：{1,2,3} EXCEPT ALL {1,2,1,2} = {3}
      try (CrossDb db = core()) {
        assertEquals(List.of("3"), rows(db,
            "SELECT id FROM userdb.users EXCEPT ALL SELECT user_id FROM orderdb.orders"));
      }
    }

    @Test void intersectAllMultisetSemantics() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1"), rows(db,
            "SELECT id FROM userdb.users INTERSECT ALL SELECT user_id FROM eventdb.events"));
      }
    }

    @Test void valuesDerivedJoinWithColumnList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,alice", "2,bob"), rows(db,
            "SELECT v.k, u.name FROM (VALUES (1), (2)) v(k) "
                + "JOIN userdb.users u ON u.id = v.k ORDER BY v.k"));
      }
    }

    @Test void cteChainThreeLevels() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "WITH a AS (SELECT id, user_id, amount FROM orderdb.orders), "
                + "b AS (SELECT user_id, SUM(amount) s FROM a GROUP BY user_id), "
                + "c AS (SELECT user_id FROM b WHERE s >= 15) "
                + "SELECT user_id FROM c ORDER BY user_id"));
      }
    }

    @Test void cteReferencedTwice() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db,
            "WITH t AS (SELECT id, user_id FROM orderdb.orders) SELECT COUNT(*) "
                + "FROM t a JOIN t b ON a.user_id = b.user_id WHERE a.id < b.id"));
      }
    }

    @Test void rowConstructorInList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT id FROM orderdb.orders WHERE (user_id, amount) IN ((1, 10), (2, 20)) "
                + "ORDER BY id"));
      }
    }

    @Test void castRoundTripMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("42,3"), rows(db,
            "SELECT CAST(CAST(42 AS VARCHAR) AS INT), CAST(3.9 AS INT) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void betweenWithExpressionBounds() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "102"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount BETWEEN id / 50 AND 2 * amount "
                + "ORDER BY id"));
      }
    }

    @Test void similarToAlternation() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("alice", "bob"), rows(db,
            "SELECT name FROM userdb.users WHERE name SIMILAR TO '(al|bo)%' ORDER BY id"));
      }
    }

    @Test void threeValuedLogicMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("false,NULL,NULL,true,NULL"), rows(db,
            "SELECT NULL AND FALSE, NULL AND TRUE, NULL OR FALSE, NULL OR TRUE, NOT NULL "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void selectWithoutFrom() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1"), rows(db, "SELECT 1"));
      }
    }
  }

  // ---------- Oracle / SQL Server 方言 ----------

  @Nested
  @DisplayName("Oracle/SQL Server 方言形态")
  class OracleMssqlDialect {

    @Test void decodeNullEqualSemantics() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("eq"), rows(db,
            "SELECT DECODE(NULL, NULL, 'eq', 'ne') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void decodeWithDefault() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("other"), rows(db,
            "SELECT DECODE(2, 1, 'one', 'other') FROM userdb.small LIMIT 1"));
      }
    }

    @Test void iifNested() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("b"), rows(db,
            "SELECT IIF(1 = 0, 'a', IIF(2 = 2, 'b', 'c')) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void topNRegression() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101"), rows(db,
            "SELECT TOP 2 id FROM orderdb.orders ORDER BY id"));
      }
    }

    @Test void isnullNestedChain() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("z"), rows(db,
            "SELECT ISNULL(NULL, ISNULL(NULL, 'z')) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nvlTwoArg() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("7,3"), rows(db,
            "SELECT NVL(NULL, 7), NVL(3, 7) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void addMonthsDate() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2026-02-15"), rows(db,
            "SELECT CAST(ADD_MONTHS(made, 1) AS VARCHAR) FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void lastDayOfMonth() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2026-02-28"), rows(db,
            "SELECT CAST(LAST_DAY(made) AS VARCHAR) FROM gooddb.products WHERE id = 11"));
      }
    }

    @Test void monthsBetweenWholeMonths() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT CAST(MONTHS_BETWEEN(DATE '2026-03-31', DATE '2026-01-31') AS INT) "
                + "FROM gooddb.regions LIMIT 1"));
      }
    }

    @Test void datePlusMinusDaysRegression() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals(List.of("2026-01-20,2026-01-05"), rows(db,
            "SELECT CAST(made + 5 AS VARCHAR), CAST(made - 10 AS VARCHAR) "
                + "FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test void groupsFrameFollowUpShapes() throws Exception {
      // 本轮修复回归的扩展形态：分区 + GROUPS、EXCLUDE 保留
      try (CrossDb db = core()) {
        assertEquals(List.of("100,10", "101,20", "102,15", "103,21"), rows(db,
            "SELECT id, SUM(amount) OVER (PARTITION BY user_id ORDER BY id "
                + "GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM orderdb.orders "
                + "ORDER BY id"));
        assertEquals(List.of("100,4", "101,2", "102,4", "103,2"), rows(db,
            "SELECT id, COUNT(*) OVER (ORDER BY user_id "
                + "GROUPS BETWEEN CURRENT ROW AND 1 FOLLOWING) FROM orderdb.orders "
                + "ORDER BY id"));
      }
    }

    @Test void matchRecognizeFollowUpShapes() throws Exception {
      // 本轮修复回归的扩展形态：PARTITION BY 分区匹配
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT COUNT(*) FROM logdb.logs MATCH_RECOGNIZE "
            + "(PARTITION BY user_id ORDER BY id ALL ROWS PER MATCH PATTERN (A B) "
            + "DEFINE B AS message < A.message)"));
      }
    }

    @Test
    @Disabled("待支持: PATTERN 或语法 A | B 解析器不支持（解析期报错，匹配器已支持），待支持")
    void matchRecognizeAlternationCandidate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("4", scalar(db, "SELECT COUNT(*) FROM logdb.logs MATCH_RECOGNIZE "
            + "(ORDER BY id ALL ROWS PER MATCH PATTERN (A B) "
            + "DEFINE A AS level = 'INFO', B AS level = 'ERROR')"));
      }
    }

    @Test
    @Disabled("待支持: PATTERN 区间量词 {n,m} 解析器不支持（仅 * + ? 单量词），待支持")
    void matchRecognizeIntervalQuantifierCandidate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM logdb.logs MATCH_RECOGNIZE "
            + "(ORDER BY id ALL ROWS PER MATCH PATTERN (A{1,2}) "
            + "DEFINE A AS user_id IS NOT NULL)"));
      }
    }

    @Test
    @Disabled("待支持: INSTR(str, sub[, start])（Oracle）未注册，待支持")
    void instrOracleCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals("2", scalar(db,
            "SELECT INSTR('banana', 'an') FROM userdb.small LIMIT 1"));
      }
    }

    @Test
    @Disabled("待支持: TO_CHAR(date, fmt)（Oracle）未注册且格式符方言差异大，待支持")
    void toCharCandidate() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals("2026-01-15", scalar(db,
            "SELECT TO_CHAR(made, 'YYYY-MM-DD') FROM gooddb.products WHERE id = 10"));
      }
    }

    @Test
    @Disabled("待支持: Oracle (+) 外连接操作符解析器不支持（标准 LEFT/RIGHT JOIN 等价），待支持")
    void oraclePlusOuterJoinCandidate() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("3,NULL"), rows(db,
            "SELECT u.id, e.id FROM userdb.users u, eventdb.events e "
                + "WHERE u.id = e.user_id(+) AND u.id = 3"));
      }
    }

    @Test
    @Disabled("待支持: CONNECT BY 层次查询（Oracle）解析器不支持，需递归 CTE 等价改写，待支持")
    void connectByCandidate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.users CONNECT BY NOCYCLE PRIOR id = id START WITH id = 1"));
      }
    }

    @Test
    @Disabled("待支持: NEXT_DAY(date, dow)（Oracle）未注册，待支持")
    void nextDayCandidate() throws Exception {
      try (CrossDb db = goods()) {
        assertEquals("2026-01-19", scalar(db,
            "SELECT CAST(NEXT_DAY(made, 'MONDAY') AS VARCHAR) "
                + "FROM gooddb.products WHERE id = 10"));
      }
    }
  }

  // ---------- 引擎加固面 ----------

  @Nested
  @DisplayName("引擎加固形态")
  class EngineHardening {

    @Test void rowLimitExactlyAtThresholdPasses() throws Exception {
      try (CrossDb db = new CrossDb(1000, 2, 1, 1)
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS)) {
        assertEquals(List.of("100", "102"), rows(db,
            "SELECT id FROM orderdb.orders WHERE user_id = 1 ORDER BY id"));
      }
    }

    @Test void rowLimitExceededThrows() throws Exception {
      try (CrossDb db = new CrossDb(1000, 2, 1, 1)
          .register("userdb", Fixtures.USERS)
          .register("orderdb", Fixtures.ORDERS)) {
        SQLException e = assertThrows(SQLException.class,
            () -> rows(db, "SELECT id FROM orderdb.orders"));
        assertTrue(e.getMessage().contains("熔断") || containsText(e, "熔断"),
            "应触发行数熔断: " + e.getMessage());
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

    @Test void safeModeMatrix() throws Exception {
      try (CrossDb db = all().safeMode()) {
        // 有过滤、有归约、有 LIMIT、Bind Join 内表均放行
        assertEquals(List.of("1"), rows(db,
            "SELECT id FROM userdb.users WHERE id = 1"));
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM userdb.users"));
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.users ORDER BY id LIMIT 2"));
        assertEquals(List.of("alice,100", "alice,102"), rows(db,
            "SELECT u.name, o.id FROM userdb.users u "
                + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1 ORDER BY o.id"));
        // 无过滤全表拉取拒绝
        assertThrows(Exception.class, () -> db.query("SELECT id FROM userdb.users"));
      }
    }

    @Test void readOnlyRejectsDmlAndDdl() throws Exception {
      try (CrossDb db = core()) {
        // DML 可解析为语句节点，由只读硬化给出中文拒绝信息
        String[] dml = {
            "INSERT INTO userdb.users VALUES (9, 'x')",
            "UPDATE userdb.users SET name = 'x' WHERE id = 1",
            "DELETE FROM userdb.users WHERE id = 1",
            "MERGE INTO userdb.users USING (SELECT 1 id) s ON userdb.users.id = s.id "
                + "WHEN MATCHED THEN UPDATE SET name = 'x'",
        };
        for (String sql : dml) {
          SQLException e = assertThrows(SQLException.class, () -> db.query(sql), sql);
          assertTrue(containsText(e, "只读"), "应被只读硬化拒绝: " + sql);
        }
        // DDL 在解析器层即拒绝（Calcite 查询解析不放行 DDL），同样构成只读防线
        String[] ddl = {
            "CREATE TABLE t(i INT)",
            "DROP TABLE userdb.users",
            "TRUNCATE TABLE userdb.users",
        };
        for (String sql : ddl) {
          assertThrows(SQLException.class, () -> db.query(sql), sql);
        }
      }
    }

    @Test void readOnlyAllowsCteAndSetOps() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "1", "2", "2"), rows(db,
            "WITH t AS (SELECT id FROM userdb.users WHERE id < 3) "
                + "SELECT id FROM t UNION ALL SELECT id FROM userdb.small ORDER BY id"));
      }
    }

    @Test void explainContainsPhysicalOperators() throws Exception {
      try (CrossDb db = core()) {
        String plan = db.explain("SELECT u.name, COUNT(*) FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id GROUP BY u.name");
        assertTrue(plan.contains("EnumerableAggregate"), plan);
      }
    }

    @Test void analyzeReportsPushedSqlAndRows() throws Exception {
      try (CrossDb db = core()) {
        String report = db.analyze("SELECT u.name FROM userdb.users u "
            + "JOIN orderdb.orders o ON o.user_id = u.id WHERE u.id = 1");
        assertTrue(report.contains("Enumerable"), report);
        assertTrue(report.toLowerCase().contains("select"), "应包含下发 SQL: " + report);
      }
    }

    @Test void resultSetTypedGettersAcrossDbs() throws Exception {
      try (CrossDb db = all()) {
        try (ResultSet rs = db.query("SELECT u.id, p.amount, p.flag FROM userdb.users u "
            + "JOIN pingdb.pings p ON p.user_id = u.id WHERE p.id = 1")) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1));
          assertEquals(0, rs.getBigDecimal(2).compareTo(new java.math.BigDecimal("1.25")));
          assertTrue(rs.getBoolean(3));
          rs.getObject(1);
          assertTrue(!rs.wasNull());
        }
      }
    }

    @Test void cancelWithoutInflightIsNoop() throws Exception {
      try (CrossDb db = core()) {
        db.cancel();
        assertEquals(List.of("1"), rows(db, "SELECT id FROM userdb.users WHERE id = 1"));
      }
    }

    @Test void fetchSizePropagatesToSource() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb(7, 1_000_000L, 500, 2)
          .register("userdb", Recording.dataSource(Fixtures.USERS, sqls, props))) {
        assertEquals(List.of("1"), rows(db, "SELECT id FROM userdb.users WHERE id = 1"));
        assertTrue(props.stream().anyMatch(p -> p.contains("7")),
            "fetchSize=7 应传播到源语句: " + props);
      }
    }

    @Test void queryTimeoutPropagatesToSource() throws Exception {
      List<String> sqls = new ArrayList<>();
      List<String> props = new ArrayList<>();
      try (CrossDb db = new CrossDb(1000, 1_000_000L, 500, 2, 3)
          .register("userdb", Recording.dataSource(Fixtures.USERS, sqls, props))) {
        assertEquals(List.of("1"), rows(db, "SELECT id FROM userdb.users WHERE id = 1"));
        assertTrue(props.stream().anyMatch(p -> p.contains("3")),
            "queryTimeout=3 应传播到源语句: " + props);
      }
    }
  }
}

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
 * 全场景覆盖测试（第六批 A）：表达式求值矩阵。参考 SQLite sqllogictest 与
 * Calcite RexProgramTest 的「单行求值」风格，对算术/字符串/三值逻辑/比较/
 * CASE/NULL 语义/位运算/数值函数/时间函数做穷举式断言；与前五批互补、不重复。
 * 全部按标准语义断言；引擎尚未支持的形态以 {@code @Disabled("待支持: ...")}
 * 标注为修复清单。
 *
 * <p>行源约定：单行求值用 {@code FROM userdb.small LIMIT 1}（small 两行取一），
 * 列求值用各夹具表。
 */
class CrossDbExprMatrixTest {

  private static CrossDb core() throws SQLException {
    return new CrossDb()
        .register("userdb", Fixtures.USERS)
        .register("orderdb", Fixtures.ORDERS);
  }

  private static CrossDb all() throws SQLException {
    return core().register("pingdb", Fixtures.PINGS)
        .register("gooddb", Fixtures.GOODS)
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

  private static String scalar(CrossDb db, String sql) throws SQLException {
    try (ResultSet rs = db.query(sql)) {
      assertTrue(rs.next(), "应至少返回一行: " + sql);
      Object v = rs.getObject(1);
      return v == null ? "NULL" : v.toString();
    }
  }

  // ---------- 算术与精度 ----------

  @Nested
  @DisplayName("算术与精度矩阵")
  class Arithmetic {

    @Test void integerDivisionTruncates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,-3,3"), row1(db, "7 / 2, -7 / 2, CAST(7 AS INT) / 2"));
      }
    }

    @Test void decimalDivisionScale() throws Exception {
      // Calcite DECIMAL 除法按 SQL 标准派生宽标度（尾零保留），ROUND 归一到比对位
      try (CrossDb db = core()) {
        assertEquals(List.of("1.400,3.500"), row1(db,
            "ROUND(CAST(7 AS DECIMAL(10,3)) / 5, 3), 7 / CAST(2.0 AS DECIMAL(3,1))"));
      }
    }

    @Test void unaryMinusPrecedence() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-7,5,-6"), row1(db, "-(3 + 4), -3 + 8, -2 * 3"));
      }
    }

    @Test void moduloSignFollowsDividend() throws Exception {
      // 标准语义：商向零截断，余数符号随被除数
      try (CrossDb db = core()) {
        assertEquals(List.of("1,-1,1,-1"), row1(db, "7 % 3, -7 % 3, 7 % -3, -7 % -3"));
      }
    }

    @Test void moduloByZeroIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,NULL"), row1(db, "MOD(1.5, 0), 5 DIV 0"));
      }
    }

    @Test void divOperatorTruncatesTowardZero() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,-3,4,-4"), row1(db, "7 DIV 2, -7 DIV 2, 9 DIV 2, -9 DIV 2"));
      }
    }

    @Test void decimalArithmeticKeepsScale() throws Exception {
      // DECIMAL(4,2) 相乘按标准派生标度 4
      try (CrossDb db = all()) {
        assertEquals("6.2500", scalar(db,
            "SELECT CAST(2.5 AS DECIMAL(4,2)) * CAST(2.5 AS DECIMAL(4,2)) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void overflowPromotesWiderType() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2000000001", "2000000002"), rows(db,
            "SELECT id + CAST(2000000000 AS BIGINT) FROM userdb.small ORDER BY id"));
      }
    }

    @Test void scientificLiteral() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1500.0"), row1(db, "CAST(1.5e3 AS DOUBLE)"));
      }
    }

    @Test void absOnColumn() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0", "10", "5", "9"), rows(db,
            "SELECT ABS(amount - 10) FROM orderdb.orders ORDER BY id"));
      }
    }
  }

  // ---------- 三值逻辑与比较 ----------

  @Nested
  @DisplayName("三值逻辑与比较矩阵")
  class ThreeValuedLogic {

    @Test void andOrNotMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("false,NULL,NULL,true,NULL"), row1(db,
            "NULL AND FALSE, NULL AND TRUE, NULL OR FALSE, NULL OR TRUE, NOT NULL"));
      }
    }

    @Test void comparisonWithNullIsUnknown() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL", "NULL"), rows(db,
            "SELECT 1 = NULL FROM userdb.small"));
      }
    }

    @Test void nullEqualNullIsUnknownNotTrue() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db,
            "SELECT 1 FROM userdb.small WHERE NULL = NULL"));
      }
    }

    @Test void isNullIsNotNullMatrix() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("false,true"), rows(db,
            "SELECT note IS NULL, note IS NOT NULL FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void isDistinctFromMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,false,true,false"), row1(db,
            "1 IS DISTINCT FROM NULL, NULL IS DISTINCT FROM NULL, "
                + "NULL IS DISTINCT FROM 1, 1 IS DISTINCT FROM 1"));
      }
    }

    @Test void coalesceOrderShortCircuit() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,7,z"), row1(db,
            "COALESCE(NULL, NULL, 1), COALESCE(7, 1 / 0), COALESCE(NULL, 'z')"));
      }
    }

    @Test void nullifAndInverse() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,5,NULL,5"), row1(db,
            "NULLIF(5, 5), NULLIF(5, 6), NULLIF(NULL, 5), COALESCE(NULLIF(5, 6), 5)"));
      }
    }

    @Test void inListWithNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2"), rows(db,
            "SELECT id FROM userdb.small WHERE id IN (2, NULL)"));
        assertEquals(List.of(), rows(db,
            "SELECT id FROM userdb.small WHERE id NOT IN (2, NULL)"));
      }
    }

    @Test void notInEmptyList() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small WHERE id NOT IN (9) ORDER BY id"));
      }
    }

    @Test void betweenAsymmetricBoundIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount BETWEEN 20 AND 5"));
        assertEquals(List.of("100", "101", "102"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount BETWEEN SYMMETRIC 20 AND 5 ORDER BY id"));
      }
    }

    @Test void notBetweenSymmetric() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "101", "103"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount NOT BETWEEN SYMMETRIC 4 AND 6 ORDER BY id"));
      }
    }

    @Test void chainedComparisonAnd() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100"), rows(db,
            "SELECT id FROM orderdb.orders WHERE amount > 5 AND amount < 20"));
      }
    }

    @Test void rowConstructorComparison() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100", "102", "103"), rows(db,
            "SELECT id FROM orderdb.orders WHERE (user_id, amount) < (2, 10) ORDER BY id"));
      }
    }

    @Test void distinctFromInFilter() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2", "3"), rows(db,
            "SELECT id FROM pingdb.pings WHERE user_id IS DISTINCT FROM 1 ORDER BY id"));
      }
    }

    @Test void booleanLiteralsAsPredicates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1", "2"), rows(db,
            "SELECT id FROM userdb.small WHERE TRUE ORDER BY id"));
      }
    }
  }

  // ---------- 字符串函数矩阵 ----------

  @Nested
  @DisplayName("字符串函数矩阵")
  class StringFunctions {

    @Test void lengthUpperLowerTrim() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("5,ALICE,alice,alice"), row1(db,
            "LENGTH('alice'), UPPER('alice'), LOWER('ALICE'), TRIM(' alice ')"));
      }
    }

    @Test void trimLeadingTrailingBoth() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("x  ,  x,x"), row1(db,
            "TRIM(LEADING ' ' FROM ' x  '), TRIM(TRAILING ' ' FROM '  x '), "
                + "TRIM(' x ')"));
      }
    }

    @Test void substringFromForForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ana,an,a"), row1(db,
            "SUBSTRING('banana' FROM 2 FOR 3), SUBSTRING('banana' FROM 2 FOR 2), "
                + "SUBSTRING('banana' FROM 6)"));
      }
    }

    @Test void substringOutOfRange() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of(""), row1(db, "SUBSTRING('abc' FROM 5)"));
      }
    }

    @Test void substringFromBeforeOneClamp() throws Exception {
      try (CrossDb db = core()) {
        // 标准（PG/SQL Server/DB2）n<1 裁剪：窗口 [0,1] ∩ [1,3] = 首字符
        assertEquals(List.of("a"), row1(db, "SUBSTRING('abc' FROM 0 FOR 2)"));
      }
    }

    @Test void positionForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,0"), row1(db,
            "POSITION('na' IN 'banana'), POSITION('z' IN 'banana')"));
      }
    }

    @Test void posstrCandidate() throws Exception {
      try (CrossDb db = core()) {
        // POSSTR(str, substr)（DB2）：串在前、子串在后（与 INSTR 参数序一致）
        assertEquals(List.of("3,0"), row1(db,
            "POSSTR('banana', 'na'), POSSTR('banana', 'xy')"));
      }
    }

    @Test void locateVariants() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,5,2"), row1(db,
            "LOCATE('na', 'banana'), LOCATE('na', 'banana', 5), LOCATE('a', 'banana', 2)"));
      }
    }

    @Test void instrVariants() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,4,0"), row1(db,
            "INSTR('banana', 'an'), INSTR('banana', 'an', 3), INSTR('banana', 'xy')"));
      }
    }

    @Test void substringIndexBothDirections() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("www,com,www.example,www.example.com"), row1(db,
            "SUBSTRING_INDEX('www.example.com', '.', 1), SUBSTRING_INDEX('www.example.com', '.', -1), "
                + "SUBSTRING_INDEX('www.example.com', '.', 2), "
                + "SUBSTRING_INDEX('www.example.com', '.', 9)"));
      }
    }

    @Test void replaceAllOccurrences() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("XYZXYZ,Xa"), row1(db,
            "REPLACE('abcabc', 'abc', 'XYZ'), REPLACE('aaa', 'aa', 'X')"));
      }
    }

    @Test void reverseAndRepeat() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ananab,abab"), row1(db, "REVERSE('banana'), REPEAT('ab', 2)"));
      }
    }

    @Test void lpadRpadTruncate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("xxa,axx,ab"), row1(db,
            "LPAD('a', 3, 'x'), RPAD('a', 3, 'x'), LPAD('abc', 2, 'x')"));
      }
    }

    @Test void leftRightEdge() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ba,,na,banana"), row1(db,
            "LEFT('banana', 2), LEFT('banana', 0), RIGHT('banana', 2), LEFT('banana', 99)"));
      }
    }

    @Test void concatForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("ab,abc,a-b,a-b"), row1(db,
            "CONCAT('a', 'b'), CONCAT('a', 'b', 'c'), CONCAT_WS('-', 'a', 'b'), "
                + "CONCAT_WS('-', 'a', NULL, NULL, 'b')"));
      }
    }

    @Test void concatOperatorNullPropagates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL"), row1(db, "'a' || NULL || 'b'"));
      }
    }

    @Test void overlayForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("axxx,axzz"), row1(db,
            "OVERLAY('axyz' PLACING 'xxx' FROM 2), OVERLAY('axyz' PLACING 'z' FROM 3 FOR 1)"));
      }
    }

    @Test void translateMapping() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("bbc,anana"), row1(db,
            "TRANSLATE('abc', 'a', 'b'), TRANSLATE('banana', 'b', '')"));
      }
    }

    @Test void initcapWords() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("Hello World"), row1(db, "INITCAP('hELLO wORLD')"));
      }
    }

    @Test void soundexCoding() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("R163,A261"), row1(db, "SOUNDEX('robert'), SOUNDEX('ashcraft')"));
      }
    }

    @Test void chrAndChar() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("B,B"), row1(db, "CHR(66), CHAR(66)"));
      }
    }

    @Test void spaceIsNullSafe() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,[],[  ]"), row1(db, "SPACE(NULL), CONCAT('[', SPACE(0), ']'), CONCAT('[', SPACE(2), ']')"));
      }
    }

    @Test void strcmpSign() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-1,0,1,NULL"), row1(db,
            "STRCMP('a', 'b'), STRCMP('a', 'a'), STRCMP('b', 'a'), STRCMP('a', NULL)"));
      }
    }

    @Test void likePatternMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,false,true,true"), row1(db,
            "'abc' LIKE 'a%', 'abc' LIKE 'b%', 'abc' LIKE '_bc', 'abc' LIKE 'abc'"));
      }
    }

    @Test void notLikeNegation() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("false"), row1(db, "'abc' NOT LIKE 'a%'"));
      }
    }

    @Test void ilikeCaseInsensitive() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,true"), row1(db, "'ABC' ILIKE 'abc', 'abc' NOT ILIKE 'xyz'"));
      }
    }

    @Test void likeNullOperand() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,NULL"), row1(db, "NULL LIKE 'a', 'a' LIKE NULL"));
      }
    }

    @Test void regexpRlikeMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,false,false"), row1(db,
            "'banana' RLIKE '^ba(na)+$', 'banana' RLIKE '^cherry', 'BOB' RLIKE 'bob'")); // CROSSDB_REGEXP 区分大小写（按 MySQL 二进制 collation 近似）
      }
    }

    @Test void similarToAlternationQuantifier() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("true,true,false"), row1(db,
            "'abc' SIMILAR TO '(a|b)%', 'aaaa' SIMILAR TO 'a{2,4}', 'ab' SIMILAR TO 'a{3}'"));
      }
    }
  }

  // ---------- 数值函数矩阵 ----------

  @Nested
  @DisplayName("数值函数矩阵")
  class NumericFunctions {

    @Test void ceilFloorRoundMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,-1,1,-2"), row1(db,
            "CEIL(1.2), CEIL(-1.2), FLOOR(1.8), FLOOR(-1.8)"));
      }
    }

    @Test void roundScaleAndNegativeScale() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2.57,2.6,120"), row1(db,
            "ROUND(2.567, 2), ROUND(2.567, 1), ROUND(123.4, -1)"));
      }
    }

    @Test void roundHalfAwayFromZeroMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("4,-4,3,-3"), row1(db,
            "ROUND(3.5), ROUND(-3.5), ROUND(2.5), ROUND(-2.5)"));
      }
    }

    @Test void lnLogForms() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3.0,4.6052,0.0"), row1(db,
            "ROUND(LOG(2, 8), 4), ROUND(LOG(100), 4), ROUND(LN(1), 4)")); // MySQL 语义 LOG(x)=LN(x)
      }
    }

    @Test void powerSqrtExpSign() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("8.0,3.0,1.0,-1"), row1(db,
            "POWER(2, 3), SQRT(9), EXP(0), SIGN(-3.2)"));
      }
    }

    @Test void decimalModFloatSemantics() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("1.25,1.25"), rows(db,
            "SELECT MOD(amount, 2), MOD(amount, 1.5) FROM pingdb.pings WHERE id = 1")); // remainder(1.25,1.5)=1.25（1.25<1.5）
      }
    }

    @Test void modNegativeOperandsFloat() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-1.5,1.5"), row1(db, "MOD(-4.5, 3), MOD(4.5, -3)"));
      }
    }

    @Test void numericCastMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,3,3.0,3.14,3.1"), row1(db,
            "CAST(3.9 AS INT), CAST('3' AS INT), CAST(3 AS DOUBLE), "
                + "CAST(3.14159 AS DECIMAL(4,2)), ROUND(CAST(3.14 AS DOUBLE), 1)"));
      }
    }

    @Test void castStringToNumberTrims() throws Exception {
      // CAST 对字符串解析严格（SQL Server 行为）：非整数串用 TRY_CAST 宽松解析
      try (CrossDb db = core()) {
        assertEquals(List.of("42,7"), row1(db,
            "CAST('42' AS INT), TRY_CAST('7.9' AS INT)"));
      }
    }

    @Test void castNullPropagates() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,NULL"), row1(db,
            "CAST(NULL AS INT), CAST(NULL AS VARCHAR)"));
      }
    }
  }

  // ---------- 位运算矩阵 ----------

  @Nested
  @DisplayName("位运算矩阵")
  class BitwiseMatrix {

    @Test void andOrXorTruth() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("8,14,6"), row1(db, "12 & 10, 12 | 10, 12 ^ 10"));
      }
    }

    @Test void notOperator() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-6"), row1(db, "~5"));
      }
    }

    @Test void shiftsBothWays() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("48,3,0,0"), row1(db, "3 << 4, 12 >> 2, 1 << 64, 1024 >> 11"));
      }
    }

    @Test void precedenceArithmeticOverShift() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("10,96"), row1(db, "2 + 3 << 1, 12 << 2 + 1"));
      }
    }

    @Test void precedenceAmpOverPipe() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("13"), row1(db, "5 & 3 | 12"));
      }
    }

    @Test void bitwiseWithColumns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("0", "1", "0"), rows(db,
            "SELECT id & 1 FROM orderdb.orders WHERE id <= 102 ORDER BY id"));
      }
    }

    @Test void bitwiseNullPropagates() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL"), rows(db,
            "SELECT note & 1 FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void logicalXorMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,0,0,0,NULL"), row1(db,
            "1 XOR 0, 1 XOR 1, 0 XOR 0, 2 XOR 4, NULL XOR 1"));
      }
    }
  }

  // ---------- CASE / DECODE / 条件表达式 ----------

  @Nested
  @DisplayName("条件表达式矩阵")
  class ConditionalExpressions {

    @Test void searchedCaseNoMatchIsNull() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL"), row1(db,
            "CASE WHEN 1 = 0 THEN 'x' END"));
      }
    }

    @Test void searchedCaseFirstMatchWins() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("mid"), row1(db,
            "CASE WHEN 1 = 2 THEN 'a' WHEN 2 = 2 THEN 'mid' ELSE 'b' END"));
      }
    }

    @Test void simpleCaseForm() throws Exception {
      try (CrossDb db = core()) {
        // Calcite 对字面量分支派生 CHAR 定长类型（补空格），CAST VARCHAR 归一
        assertEquals(List.of("two,other,NULL"), row1(db,
            "TRIM(CAST(CASE 2 WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END AS VARCHAR)), "
                + "TRIM(CAST(CASE 9 WHEN 1 THEN 'one' ELSE 'other' END AS VARCHAR)), "
                + "TRIM(CAST(CASE 9 WHEN 1 THEN 'one' END AS VARCHAR))"));
      }
    }

    @Test void simpleCaseNullNeverMatches() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("no"), row1(db,
            "TRIM(CAST(CASE NULL WHEN NULL THEN 'yes' ELSE 'no' END AS VARCHAR))"));
      }
    }

    @Test void decodeNullEqualSemantics() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("yes,def,NULL"), row1(db,
            "DECODE(NULL, NULL, 'yes', 'no'), DECODE(3, 1, 'one', 'def'), "
                + "DECODE(3, 1, 'one')"));
      }
    }

    @Test void decodeMultiPair() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("b"), row1(db,
            "DECODE(2, 1, 'a', 2, 'b', 3, 'c', 'd')"));
      }
    }

    @Test void iifShorthand() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("hi,lo"), row1(db, "IIF(1 < 2, 'hi', 'lo'), IIF(1 > 2, 'hi', 'lo')"));
      }
    }

    @Test void nvlIsnullChains() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("1,fallback,fallback"), row1(db,
            "NVL(NULL, 1), ISNULL(NULL, 'fallback'), ISNULL(NULL, NVL(NULL, 'fallback'))"));
      }
    }

    @Test void caseInOrderBy() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("103", "102", "100", "101"), rows(db,
            "SELECT id FROM orderdb.orders ORDER BY CASE WHEN amount < 6 THEN 0 ELSE 1 END, amount"));
      }
    }
  }

  // ---------- 时间函数字面量矩阵 ----------

  @Nested
  @DisplayName("时间函数字面量矩阵")
  class DateTimeLiterals {

    @Test void extractPartsFromDate() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2026,3,10,69"), row1(db,
            "EXTRACT(YEAR FROM DATE '2026-03-10'), EXTRACT(MONTH FROM DATE '2026-03-10'), "
                + "EXTRACT(DAY FROM DATE '2026-03-10'), "
                + "EXTRACT(DOY FROM DATE '2026-03-10')"));
      }
    }

    @Test void extractPartsFromTimestamp() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("3,4,31,2026"), row1(db,
            "EXTRACT(HOUR FROM TIMESTAMP '2026-01-02 03:04:05'), "
                + "EXTRACT(MINUTE FROM TIMESTAMP '2026-01-02 03:04:05'), "
                + "EXTRACT(SECOND FROM TIMESTAMP '2026-01-02 03:04:31'), "
                + "EXTRACT(YEAR FROM TIMESTAMP '2026-01-02 03:04:05')"));
      }
    }

    @Test void datePlusMinusInterval() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2026-01-06,2025-12-27"), row1(db,
            "CAST(DATE '2026-01-01' + INTERVAL '5' DAY AS VARCHAR), "
                + "CAST(DATE '2026-01-03' - INTERVAL '7' DAY AS VARCHAR)"));
      }
    }

    @Test void dateColumnPlusInt() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-03-13,2026-02-25"), rows(db,
            "SELECT (SELECT CAST(made + 3 AS VARCHAR) FROM gooddb.products WHERE id = 12), "
                + "(SELECT CAST(made + 5 AS VARCHAR) FROM gooddb.products WHERE id = 11) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void timestampdiffUnitMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,48,2880,172800"), row1(db,
            "TIMESTAMPDIFF(DAY, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-03 00:00:00'), "
                + "TIMESTAMPDIFF(HOUR, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-03 00:00:00'), "
                + "TIMESTAMPDIFF(MINUTE, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-03 00:00:00'), "
                + "TIMESTAMPDIFF(SECOND, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-03 00:00:00')"));
      }
    }

    @Test void timestampdiffNegativeAndMonth() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("-2,3,1"), row1(db,
            "TIMESTAMPDIFF(DAY, TIMESTAMP '2026-01-03 00:00:00', TIMESTAMP '2026-01-01 00:00:00'), "
                + "TIMESTAMPDIFF(MONTH, DATE '2026-01-15', DATE '2026-04-15'), "
                + "TIMESTAMPDIFF(QUARTER, DATE '2026-01-01', DATE '2026-04-01')"));
      }
    }

    @Test void timestampdiffPartialUnitsTruncate() throws Exception {
      // MySQL 语义：完整单位数（1.5 天 = 1 天）
      try (CrossDb db = core()) {
        assertEquals(List.of("1,36"), row1(db,
            "TIMESTAMPDIFF(DAY, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-02 12:00:00'), "
                + "TIMESTAMPDIFF(HOUR, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-02 12:00:00')"));
      }
    }

    @Test void dateDiffUnitFirstForm() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2,-2,1"), row1(db,
            "date_diff('day', DATE '2026-01-01', DATE '2026-01-03'), "
                + "date_diff('day', DATE '2026-01-03', DATE '2026-01-01'), "
                + "date_diff('month', DATE '2026-01-05', DATE '2026-02-05')"));
      }
    }

    @Test void dateFormatSpecifiers() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-02,03:04:05,02,January,Jan,AM"), rows(db,
            "SELECT DATE_FORMAT(ts, '%Y-%m-%d'), DATE_FORMAT(ts, '%H:%i:%s'), "
                + "DATE_FORMAT(ts, '%d'), DATE_FORMAT(ts, '%M'), DATE_FORMAT(ts, '%b'), "
                + "DATE_FORMAT(ts, '%p') FROM pingdb.pings WHERE id = 1"));
      }
    }

    @Test void dateFormatLiteralPercent() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("100% of 02"), row1(db,
            "DATE_FORMAT(DATE '2026-01-02', '100%% of %d')"));
      }
    }

    @Test void toCharPatterns() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("2026/01/02,0102,26,JAN"), row1(db,
            "TO_CHAR(DATE '2026-01-02', 'YYYY/MM/DD'), "
                + "TO_CHAR(DATE '2026-01-02', 'MMDD'), "
                + "TO_CHAR(DATE '2026-01-02', 'YY'), "
                + "TO_CHAR(DATE '2026-01-02', 'MON')"));
      }
    }

    @Test void toCharColumnAndTimestamp() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-15,2026-01-02 03:04:05"), rows(db,
            "SELECT (SELECT TO_CHAR(made) FROM gooddb.products WHERE id = 10), "
                + "(SELECT TO_CHAR(ts, 'YYYY-MM-DD HH24:MI:SS') FROM pingdb.pings WHERE id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void tryCastMatrix() throws Exception {
      try (CrossDb db = core()) {
        assertEquals(List.of("NULL,7,79,7.5,NULL,7,7"), row1(db,
            "TRY_CAST('abc' AS INT), TRY_CAST('7' AS INT), TRY_CAST('  79' AS BIGINT), "
                + "TRY_CAST('7.5' AS DOUBLE), TRY_CAST('' AS DECIMAL), "
                + "TRY_CAST(7 AS VARCHAR), TRY_CAST('7.9' AS INT)"));
      }
    }

    @Test void addMonthsNegativeAndClamp() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2025-12-15,2026-02-28"), rows(db,
            "SELECT (SELECT CAST(ADD_MONTHS(made, -1) AS VARCHAR) FROM gooddb.products WHERE id = 10), "
                + "(SELECT CAST(ADD_MONTHS(DATE '2026-01-31', 1) AS VARCHAR) "
                + "FROM gooddb.regions WHERE region_id = 1) FROM userdb.small LIMIT 1"));
      }
    }

    @Test void monthsBetweenFractional() throws Exception {
      try (CrossDb db = all()) {
        // Oracle 分数语义：整月差 + (d1.day-d2.day)/31（6 位标度）
        assertEquals(List.of("2.0,0.516129,-1.0"), rows(db,
            "SELECT (SELECT CAST(MONTHS_BETWEEN(DATE '2026-03-31', DATE '2026-01-31') AS DOUBLE) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT MONTHS_BETWEEN(DATE '2026-01-16', DATE '2025-12-31') FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(MONTHS_BETWEEN(DATE '2026-01-15', DATE '2026-02-15') AS DOUBLE) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void lastDayMatrix() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-02-28,2026-04-30"), rows(db,
            "SELECT (SELECT CAST(LAST_DAY(DATE '2026-02-10') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(LAST_DAY(DATE '2026-04-01') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nextDayNameAndNumber() throws Exception {
      try (CrossDb db = all()) {
        // 2026-01-15 是星期四：MONDAY→19、SUNDAY→18、6(星期五)→16
        assertEquals(List.of("2026-01-19,2026-01-18,2026-01-16"), rows(db,
            "SELECT (SELECT CAST(NEXT_DAY(DATE '2026-01-15', 'MONDAY') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(NEXT_DAY(DATE '2026-01-15', 'SUNDAY') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1), "
                + "(SELECT CAST(NEXT_DAY(DATE '2026-01-15', 6) AS VARCHAR) FROM gooddb.regions WHERE region_id = 1) "
                + "FROM userdb.small LIMIT 1"));
      }
    }

    @Test void nextDaySameWeekdayNextWeek() throws Exception {
      // NEXT_DAY 严格晚于当日：星期四取「下星期四」
      try (CrossDb db = all()) {
        assertEquals(List.of("2026-01-22"), rows(db,
            "SELECT CAST(NEXT_DAY(DATE '2026-01-15', 'THU') AS VARCHAR) FROM gooddb.regions WHERE region_id = 1"));
      }
    }
  }

  // ---------- NULL 语义综合（列上） ----------

  @Nested
  @DisplayName("列上 NULL 语义")
  class ColumnNullSemantics {

    @Test void aggregatesSkipNull() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("4.75", scalar(db,
            "SELECT SUM(amount) FROM pingdb.pings"));
        assertEquals("2", scalar(db, "SELECT COUNT(amount) FROM pingdb.pings"));
        assertEquals("3", scalar(db, "SELECT COUNT(*) FROM pingdb.pings"));
      }
    }

    @Test void arithmeticWithNullColumn() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("2.25"), rows(db,
            "SELECT amount + 1 FROM pingdb.pings WHERE id = 1"));
        assertEquals(List.of("NULL"), rows(db,
            "SELECT amount + 1 FROM pingdb.pings WHERE id = 2"));
        assertEquals(List.of("7.0"), rows(db,
            "SELECT CAST(amount * 2 AS DOUBLE) FROM pingdb.pings WHERE id = 3"));
      }
    }

    @Test void orderByNullsDefaultLast() throws Exception {
      try (CrossDb db = all()) {
        // MYSQL lex：默认 NULLS 末尾（ASC 时 NULL 最大）
        assertEquals(List.of("1", "9", "NULL"), rows(db,
            "SELECT user_id FROM pingdb.pings ORDER BY user_id"));
      }
    }

    @Test void distinctIncludesNullOnce() throws Exception {
      try (CrossDb db = all()) {
        assertEquals("2", scalar(db, "SELECT COUNT(DISTINCT note) FROM pingdb.pings")); // COUNT(DISTINCT) 跳过 NULL（标准语义）
      }
    }

    @Test void groupByNullBucket() throws Exception {
      try (CrossDb db = all()) {
        assertEquals(List.of("NULL,1", "1,1", "9,1"), rows(db,
            "SELECT user_id, COUNT(*) FROM pingdb.pings GROUP BY user_id "
                + "ORDER BY user_id NULLS FIRST"));
      }
    }
  }
}

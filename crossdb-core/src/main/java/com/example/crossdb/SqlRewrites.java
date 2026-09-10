package com.example.crossdb;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlWithinGroupOperator;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransformCascade;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.util.SqlShuttle;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.util.Optionality;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析期兼容性改写：把易踩坑/不可执行的语法在进入校验器之前改写为引擎可控、
 * 跨源行为一致的等价形态。全部改写保持语义等价；无法安全改写的形态原样保留，
 * 交由校验器给出真实错误。
 *
 * <ul>
 *   <li>TOP n（SQL Server 方言，Calcite 解析器不支持）→ FETCH FIRST n ROWS ONLY；</li>
 *   <li>SIMILAR TO → CROSSDB_SIMILAR 本地 UDF（Enumerable 不实现、下推必错）；</li>
 *   <li>INITCAP / OVERLAY / FLOOR・CEIL(ts TO unit) → 本地 UDF（源库普遍缺对应
 *   函数或语法，下推必错，本地求值保证跨源一致）；</li>
 *   <li>VAR_POP/VAR_SAMP/STDDEV_POP/STDDEV_SAMP → 入参 CAST AS DOUBLE
 *   （Calcite 本地实现对整数输入做整数除法，标准语义应为浮点）；</li>
 *   <li>CUME_DIST / PERCENT_RANK → RANK/COUNT(*) 等价改写（Enumerable 约定缺
 *   这两个窗口聚合的实现）；</li>
 *   <li>NTH_VALUE → CROSSDB_NTH_VALUE 本地窗口聚合（Calcite 与常见源库均忽略
 *   窗口帧、按整分区取值，与标准「帧内第 n 行」语义不符）；</li>
 *   <li>STRING_AGG / GROUP_CONCAT（PostgreSQL/MySQL 聚合）→ 标准 LISTAGG；</li>
 *   <li>DECODE（Oracle，未注册于标准操作符表）→ CASE WHEN .. IS NOT DISTINCT FROM
 *   等价改写（NULL=NULL 相等语义保留）；</li>
 *   <li>IIF（SQL Server）→ CASE WHEN 等价改写；ISNULL（SQL Server）→ COALESCE；
 *   ILIKE（PostgreSQL）→ LOWER(x) LIKE LOWER(p)；</li>
 *   <li>PERCENTILE_DISC / ARRAY_AGG / ANY_VALUE / MODE → 本地 UDAF（Enumerable
 *   无实现或引擎不支持 ARRAY 值类型，ARRAY_AGG 以 {@code "[..]"} 字符串渲染）；</li>
 *   <li>AGG(x) FILTER (WHERE c) OVER w → AGG(CASE WHEN c THEN x END) OVER w
 *   （校验器拒绝 FILTER 与 OVER 组合；跳 NULL 语义聚合上等价）；</li>
 *   <li>FIRST_VALUE/LAST_VALUE(x) IGNORE NULLS OVER w → 本地首/末非 NULL 端点
 *   窗口聚合（Enumerable 无 IGNORE NULLS 实现）；</li>
 *   <li>COUNT(DISTINCT x) OVER w → CROSSDB_COUNT_DISTINCT 窗口聚合
 *   （EnumerableWindow 静默丢弃 DISTINCT 量化符，错误地得到 COUNT(*) 语义）；</li>
 *   <li>LEFT SEMI/ANTI JOIN（Calcite 解析器不支持的语法）→ 等价 CROSS APPLY
 *   (SELECT 1 .. HAVING COUNT(*) 比较)；</li>
 *   <li>FETCH FIRST n ROWS WITH TIES（Calcite 解析器不支持）→ 「前 n 个去重键组」
 *   等价 IN 半连接改写（裸列键）；</li>
 *   <li>USING 共享列裸引用（Calcite 校验器 AssertionError，上游缺陷）→ 等价 ON
 *   等值连接 + 裸引用替换为 COALESCE(l.c, r.c)；</li>
 *   <li>表别名列名清单 FROM t(a, b)（标准 SQL）→ 派生表列重命名；</li>
 *   <li>语句尾分号（多数驱动接受、Calcite 解析器不接受）→ 预处理剥离。</li>
 * </ul> */
final class SqlRewrites {
  private SqlRewrites() {}

  // ---------- 内置 UDF 操作符（本地求值） ----------

  private static final SqlReturnTypeInference ARG0_NULLABLE =
      new SqlTypeTransformCascade(ReturnTypes.ARG0, SqlTypeTransforms.TO_NULLABLE);
  private static final SqlReturnTypeInference VARCHAR_NULLABLE =
      new SqlTypeTransformCascade(
          b -> b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR),
          SqlTypeTransforms.TO_NULLABLE);

  static final SqlUserDefinedFunction SIMILAR_FN_2 =
      similarFn("CROSSDB_SIMILAR", 2);
  static final SqlUserDefinedFunction SIMILAR_FN_3 =
      similarFn("CROSSDB_SIMILAR", 3);

  private static final SqlUserDefinedFunction INITCAP_FN =
      udf("CROSSDB_INITCAP", "initcap",
          List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE);
  private static final SqlUserDefinedFunction OVERLAY_FN_3 =
      udf("CROSSDB_OVERLAY", "overlay3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);
  private static final SqlUserDefinedFunction OVERLAY_FN_4 =
      udf("CROSSDB_OVERLAY", "overlay4",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING,
              SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);
  private static final SqlReturnTypeInference ANY_NULLABLE =
      new SqlTypeTransformCascade(
          b -> b.getTypeFactory().createSqlType(SqlTypeName.ANY),
          SqlTypeTransforms.TO_NULLABLE);
  private static final SqlUserDefinedFunction FLOOR_UNIT_FN =
      udf("CROSSDB_FLOOR", "floorUnit",
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), SqlTypeName.ANY, ANY_NULLABLE);
  private static final SqlUserDefinedFunction CEIL_UNIT_FN =
      udf("CROSSDB_CEIL", "ceilUnit",
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), SqlTypeName.ANY, ANY_NULLABLE);

  // GREATEST/LEAST 可变元参：Calcite 校验器无法对 ANY 类型的同名胜负看重叠做优先级
  // 消解（SqlTypeExplicitPrecedenceList 断言失败），改用「按元数独立命名 + 解析期
  // 改写挂载」的形态，绕开按名重载消解。
  private static final SqlUserDefinedFunction GREATEST2 = extremumFn("CROSSDB_GREATEST2", 2, true);
  private static final SqlUserDefinedFunction GREATEST3 = extremumFn("CROSSDB_GREATEST3", 3, true);
  private static final SqlUserDefinedFunction GREATEST4 = extremumFn("CROSSDB_GREATEST4", 4, true);
  private static final SqlUserDefinedFunction LEAST2 = extremumFn("CROSSDB_LEAST2", 2, false);
  private static final SqlUserDefinedFunction LEAST3 = extremumFn("CROSSDB_LEAST3", 3, false);
  private static final SqlUserDefinedFunction LEAST4 = extremumFn("CROSSDB_LEAST4", 4, false);

  /** TIMESTAMPDIFF 本地标量：unit 操作数（SqlIntervalQualifier）改写为字符串字面量。
   * 操作数派生类型须为 ANY（udf() 按同一类型派生全部操作数，若设 BIGINT 会给
   * 'DAY' 等字符串参数插入 CAST），返回类型单独声明 BIGINT。 */
  private static final SqlUserDefinedFunction TIMESTAMPDIFF_FN =
      udf("CROSSDB_TIMESTAMPDIFF", "timestampDiff",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ReturnTypes.BIGINT_NULLABLE);

  /** MySQL REGEXP/RLIKE：Java 正则任意位置匹配，本地求值（解析器仅认 RLIKE 关键字，
   * REGEXP 由语句级预处理替换为 RLIKE 后挂载到此实现）。 */
  private static final SqlUserDefinedFunction REGEXP_FN =
      udf("CROSSDB_REGEXP", "regexp",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING), SqlTypeName.VARCHAR,
          ReturnTypes.BOOLEAN_NULLABLE);
  private static final SqlUserDefinedFunction TRANSLATE_FN =
      udf("CROSSDB_TRANSLATE", "translate",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);

  private static final SqlUserDefinedAggFunction LISTAGG_DISTINCT_FN =
      aggFn("CROSSDB_LISTAGG", VARCHAR_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), CrossDbAggregates.ListaggDistinct.class);
  private static final SqlUserDefinedAggFunction BOOL_AND_FN =
      aggFn("CROSSDB_BOOL_AND", ReturnTypes.BOOLEAN_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.BoolAnd.class);
  private static final SqlUserDefinedAggFunction BOOL_OR_FN =
      aggFn("CROSSDB_BOOL_OR", ReturnTypes.BOOLEAN_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.BoolOr.class);
  private static final SqlUserDefinedAggFunction MEDIAN_FN =
      aggFn("CROSSDB_MEDIAN", ReturnTypes.DOUBLE_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.Median.class);
  private static final SqlUserDefinedAggFunction PERCENTILE_CONT_FN =
      aggFn("CROSSDB_PERCENTILE_CONT", ReturnTypes.DOUBLE_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.PercentileCont.class);
  private static final SqlUserDefinedAggFunction NTH_VALUE2_FN =
      aggFn("CROSSDB_NTH_VALUE2", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue2.class);
  private static final SqlUserDefinedAggFunction NTH_VALUE3_FN =
      aggFn("CROSSDB_NTH_VALUE3", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue3.class);
  private static final SqlUserDefinedAggFunction NTH_VALUE4_FN =
      aggFn("CROSSDB_NTH_VALUE4", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue4.class);
  private static final SqlUserDefinedAggFunction PERCENTILE_DISC_FN =
      aggFn("CROSSDB_PERCENTILE_DISC", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.PercentileDisc.class);
  private static final SqlUserDefinedAggFunction ARRAY_AGG_FN =
      aggFn("CROSSDB_ARRAY_AGG", VARCHAR_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.ArrayAgg.class);
  private static final SqlUserDefinedAggFunction ANY_VALUE_FN =
      aggFn("CROSSDB_ANY_VALUE", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.AnyValue.class);
  private static final SqlUserDefinedAggFunction MODE_FN =
      aggFn("CROSSDB_MODE", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.Mode.class);
  private static final SqlUserDefinedAggFunction COUNT_DISTINCT_FN =
      aggFn("CROSSDB_COUNT_DISTINCT", ReturnTypes.BIGINT_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.CountDistinct.class);
  private static final SqlUserDefinedAggFunction FIRST_VALUE_NN_FN =
      aggFn("CROSSDB_FIRST_VALUE_NN", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.FirstNonNull.class);
  private static final SqlUserDefinedAggFunction LAST_VALUE_NN_FN =
      aggFn("CROSSDB_LAST_VALUE_NN", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.LastNonNull.class);

  /** 注册进引擎操作符表的本地 UDAF（名字 SQL 不可见，仅由解析期改写挂载）。 */
  private static final List<org.apache.calcite.sql.SqlOperator> AGGS = List.of(
      LISTAGG_DISTINCT_FN, MEDIAN_FN, PERCENTILE_CONT_FN,
      NTH_VALUE2_FN, NTH_VALUE3_FN, NTH_VALUE4_FN, BOOL_AND_FN, BOOL_OR_FN,
      PERCENTILE_DISC_FN, ARRAY_AGG_FN, ANY_VALUE_FN, MODE_FN, COUNT_DISTINCT_FN,
      FIRST_VALUE_NN_FN, LAST_VALUE_NN_FN);

  private static SqlUserDefinedAggFunction aggFn(String name, SqlReturnTypeInference ret,
      List<SqlTypeFamily> fams, Class<?> impl) {
    SqlOperandMetadata meta = OperandTypes.operandMetadata(fams,
        tf -> Collections.nCopies(fams.size(), tf.createSqlType(SqlTypeName.ANY)),
        i -> "VALUE", i -> true);
    // requiresOrder=false：UDAF 允许作无 ORDER BY 的窗口聚合（如
    // COUNT(DISTINCT x) OVER ()）；作普通分组聚合不受该标志影响
    return new SqlUserDefinedAggFunction(new SqlIdentifier(name, SqlParserPos.ZERO),
        SqlKind.OTHER_FUNCTION, ret, null, meta,
        AggregateFunctionImpl.create(impl), false, false, Optionality.IGNORED);
  }

  /** 注册进引擎操作符表的本地函数（名字即 SQL 可见名；与标准表无同名冲突）。
   * 注意：解析期改写「按名挂载」的操作符（INITCAP/GREATEST/LEAST/NTH_VALUE 等）
   * 同样必须在此注册——校验器对已挂载的函数调用仍会按名重查操作符表。 */
  static final List<org.apache.calcite.sql.SqlOperator> UDFS = List.of(
      udf("INITCAP", "initcap", List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("LPAD", "lpad",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
          SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("RPAD", "rpad",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
          SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("REPEAT", "repeat",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("CHR", "chr", List.of(SqlTypeFamily.NUMERIC), SqlTypeName.VARCHAR, VARCHAR_NULLABLE),
      udf("NVL", "nvl", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), SqlTypeName.ANY, ARG0_NULLABLE),
      udf("CONCAT", "concat2", List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("CONCAT", "concat3", List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING,
          SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("CONCAT_WS", "concatWs2", List.of(SqlTypeFamily.STRING, SqlTypeFamily.ANY,
          SqlTypeFamily.ANY), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("CONCAT_WS", "concatWs3", List.of(SqlTypeFamily.STRING, SqlTypeFamily.ANY,
          SqlTypeFamily.ANY, SqlTypeFamily.ANY), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("REVERSE", "reverse", List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      TRANSLATE_FN,
      udf("SOUNDEX", "soundex", List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("LTRIM", "ltrim", List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("RTRIM", "rtrim", List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE),
      udf("IFNULL", "ifnull", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ARG0_NULLABLE),
      FLOOR_UNIT_FN, CEIL_UNIT_FN, OVERLAY_FN_3, OVERLAY_FN_4, INITCAP_FN,
      SIMILAR_FN_2, SIMILAR_FN_3, REGEXP_FN,
      GREATEST2, GREATEST3, GREATEST4, LEAST2, LEAST3, LEAST4,
      TIMESTAMPDIFF_FN);
  /** 全部本地操作符（标量 UDF + 聚合/窗口 UDAF）：注册进引擎操作符表。 */
  static final List<org.apache.calcite.sql.SqlOperator> OPERATORS;
  static {
    List<org.apache.calcite.sql.SqlOperator> all =
        new ArrayList<>(UDFS.size() + AGGS.size());
    all.addAll(UDFS);
    all.addAll(AGGS);
    OPERATORS = List.copyOf(all);
  }

  private static SqlUserDefinedFunction extremumFn(String name, int arity, boolean greatest) {
    String impl = (greatest ? "greatest" : "least") + arity;
    try {
      Method m = CrossDbFunctions.class.getMethod(impl, nOf(Object.class, arity));
      SqlOperandMetadata meta = OperandTypes.operandMetadata(
          Collections.nCopies(arity, SqlTypeFamily.ANY),
          tf -> Collections.nCopies(arity, tf.createSqlType(SqlTypeName.ANY)),
          i -> "VALUE", i -> true);
      return new SqlUserDefinedFunction(new SqlIdentifier(name, SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION, ARG0_NULLABLE, null, meta, ScalarFunctionImpl.create(m));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("CrossDbFunctions 缺少实现方法 " + impl, e);
    }
  }

  /** name→SQL 可见名；impl→CrossDbFunctions 静态方法名；fams→各参数类型族；
   * type→派生参数 SQL 类型；ret→返回类型推导。 */
  private static SqlUserDefinedFunction udf(String name, String impl, List<SqlTypeFamily> fams,
      SqlTypeName type, SqlReturnTypeInference ret) {
    try {
      Class<?>[] params = implParams(impl, fams.size());
      Method m = CrossDbFunctions.class.getMethod(impl, params);
      SqlOperandMetadata meta = OperandTypes.operandMetadata(fams,
          tf -> Collections.nCopies(fams.size(), tf.createSqlType(type)),
          i -> "VALUE", i -> true);
      return new SqlUserDefinedFunction(new SqlIdentifier(name, SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION, ret, null, meta, ScalarFunctionImpl.create(m));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("CrossDbFunctions 缺少实现方法 " + impl, e);
    }
  }

  /** 按实现方法名约定推导 Java 参数类型（与 CrossDbFunctions 的签名一一对应）。 */
  private static Class<?>[] implParams(String impl, int arity) {
    return switch (impl) {
      case "initcap", "similar" -> nOf(String.class, arity);
      case "chr" -> new Class<?>[]{BigDecimal.class};
      case "repeat" -> new Class<?>[]{String.class, BigDecimal.class};
      case "lpad", "rpad" -> new Class<?>[]{String.class, BigDecimal.class, String.class};
      case "overlay3" -> new Class<?>[]{String.class, String.class, BigDecimal.class};
      case "overlay4" -> new Class<?>[]{String.class, String.class, BigDecimal.class, BigDecimal.class};
      case "floorUnit", "ceilUnit" -> new Class<?>[]{Object.class, String.class};
      case "nvl", "ifnull" -> new Class<?>[]{Object.class, Object.class};
      case "timestampDiff" -> new Class<?>[]{String.class, Object.class, Object.class};
      case "regexp" -> new Class<?>[]{String.class, String.class};
      case "concat2" -> new Class<?>[]{String.class, String.class};
      case "concat3" -> new Class<?>[]{String.class, String.class, String.class};
      case "concatWs2" -> new Class<?>[]{String.class, Object.class, Object.class};
      case "concatWs3" -> new Class<?>[]{String.class, Object.class, Object.class, Object.class};
      case "reverse" -> new Class<?>[]{String.class};
      case "translate" -> new Class<?>[]{String.class, String.class, String.class};
      case "soundex", "ltrim", "rtrim" -> new Class<?>[]{String.class};
      default -> {
        if (impl.startsWith("greatest") || impl.startsWith("least")) {
          yield nOf(Object.class, arity);
        }
        throw new IllegalStateException("未知 UDF 实现 " + impl);
      }
    };
  }

  private static Class<?>[] nOf(Class<?> c, int n, int... intAt) {
    Class<?>[] out = new Class<?>[n];
    Arrays.fill(out, c);
    for (int i : intAt) {
      out[i] = Integer.class;
    }
    return out;
  }

  private static SqlUserDefinedFunction similarFn(String name, int args) {
    try {
      Class<?>[] params = new Class<?>[args];
      Arrays.fill(params, String.class);
      Method method = CrossDbFunctions.class.getMethod("similar", params);
      return new SqlUserDefinedFunction(new SqlIdentifier(name, SqlParserPos.ZERO),
          SqlKind.OTHER_FUNCTION, ReturnTypes.BOOLEAN_NULLABLE, null,
          OperandTypes.operandMetadata(Collections.nCopies(args, SqlTypeFamily.STRING),
              typeFactory -> Collections.nCopies(args,
                  typeFactory.createSqlType(SqlTypeName.VARCHAR)),
              i -> "VALUE", i -> false),
          ScalarFunctionImpl.create(method));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- SQL 文本预处理（TOP → FETCH FIRST、SEMI/ANTI JOIN、WITH TIES） ----------

  private static final Pattern TOP = Pattern.compile(
      "(?is)^(\\s*SELECT\\s+(?:DISTINCT\\s+|ALL\\s+)?)TOP\\s*\\(?\\s*(\\d+)\\s*\\)?");
  private static final Pattern SEMI_ANTI_JOIN = Pattern.compile(
      "(?i)\\bLEFT\\s+(SEMI|ANTI)\\s+JOIN\\b");
  private static final Pattern FETCH_WITH_TIES = Pattern.compile(
      "(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+WITH\\s+TIES\\s*(;?)\\s*$");
  /** ON 条件扫描在深度 0 遇到这些子句关键字即止。 */
  private static final java.util.Set<String> CLAUSE_STOPPERS = java.util.Set.of(
      "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION",
      "INTERSECT", "EXCEPT", "JOIN", "LEFT", "RIGHT", "INNER", "CROSS", "FULL",
      "OUTER", "NATURAL", "ON", "APPLY");

  /** 语句级预处理入口：TOP n / LEFT SEMI・ANTI JOIN / FETCH FIRST .. WITH TIES /
   * LISTAGG ON OVERFLOW ERROR / REGEXP / 语句尾分号剥离。各改写仅在能安全识别
   * 边界时生效，否则原样返回交由解析器/校验器报真实错误。 */
  static String preprocess(String sql) {
    sql = preprocessTrailingSemicolon(sql);
    sql = preprocessTop(sql);
    sql = preprocessSemiAntiJoin(sql);
    sql = preprocessFetchWithTies(sql);
    sql = preprocessListaggOverflowError(sql);
    sql = preprocessRegexp(sql);
    return sql;
  }

  /** 语句尾分号剥离（仅活字符位置的分号 + 尾随空白；可多重）：绝大多数驱动/工具
   * 允许语句带尾分号，Calcite 解析器不接受。语句内部的分号（多语句）保留，
   * 仍由解析器按多语句拒绝。 */
  private static String preprocessTrailingSemicolon(String sql) {
    boolean[] live = liveMask(sql);
    int end = sql.length();
    while (end > 0) {
      int e = end - 1;
      while (e >= 0 && Character.isWhitespace(sql.charAt(e))) {
        e--;
      }
      if (e >= 0 && sql.charAt(e) == ';' && live[e]) {
        end = e;
      } else {
        break;
      }
    }
    return end == sql.length() ? sql : sql.substring(0, end);
  }

  private static final Pattern LISTAGG_OVERFLOW_ERROR =
      Pattern.compile("(?i)\\bON\\s+OVERFLOW\\s+ERROR\\b");

  /** LISTAGG 的 {@code ON OVERFLOW ERROR} 子句（Calcite 解析器不支持该语法）→ 剥离。
   * 标准默认行为即 ON OVERFLOW ERROR，语义不变；TRUNCATE 形态无法等价剥除，
   * 不在此处理，交由解析器报真实错误。 */
  private static String preprocessListaggOverflowError(String sql) {
    return stripLiveMatches(sql, LISTAGG_OVERFLOW_ERROR);
  }

  private static final Pattern REGEXP_OPERATOR = Pattern.compile("(?i)\\bREGEXP\\b");

  /** MySQL {@code REGEXP} 操作符（Calcite 解析器仅支持同义关键字 RLIKE）→ RLIKE，
   * 随后由解析树改写挂载到本地 CROSSDB_REGEXP。NOT REGEXP 同步生效（NOT RLIKE 合法）。 */
  private static String preprocessRegexp(String sql) {
    Matcher m = REGEXP_OPERATOR.matcher(sql);
    if (!m.find()) {
      return sql;
    }
    boolean[] live = liveMask(sql);
    StringBuilder out = new StringBuilder(sql.length());
    int pos = 0;
    m.reset();
    while (m.find()) {
      if (!spanLive(live, m.start(), m.end())) {
        continue;   // 字面量/注释内的伪命中
      }
      out.append(sql, pos, m.start()).append("RLIKE");
      pos = m.end();
    }
    if (pos == 0) {
      return sql;
    }
    return out.append(sql.substring(pos)).toString();
  }

  /** 剥离 sql 中所有「活字符」区间的正则命中（字面量/注释内不动）。 */
  private static String stripLiveMatches(String sql, Pattern pattern) {
    Matcher m = pattern.matcher(sql);
    if (!m.find()) {
      return sql;
    }
    boolean[] live = liveMask(sql);
    StringBuilder out = new StringBuilder(sql.length());
    int pos = 0;
    m.reset();
    while (m.find()) {
      if (!spanLive(live, m.start(), m.end())) {
        continue;
      }
      out.append(sql, pos, m.start());
      pos = m.end();
    }
    if (pos == 0) {
      return sql;
    }
    return out.append(sql.substring(pos)).toString();
  }

  /** 语句级 TOP n 改写为末尾 FETCH FIRST n ROWS ONLY（语义：有 ORDER BY 取前 n 行、
   * 无 ORDER BY 任取 n 行，与 T-SQL TOP 一致）。不匹配则原样返回。 */
  private static String preprocessTop(String sql) {
    Matcher m = TOP.matcher(sql);
    if (!m.find()) {
      return sql;
    }
    String rest = sql.substring(m.end());
    String trimmed = rest.stripTrailing();
    String semi = trimmed.endsWith(";") ? ";" : "";
    if (!semi.isEmpty()) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    } else {
      trimmed = rest;
    }
    return m.group(1) + trimmed + " FETCH FIRST " + m.group(2) + " ROWS ONLY" + semi;
  }

  /** LEFT SEMI/ANTI JOIN（Calcite 解析器不支持的语法）→ 等价相关 APPLY：
   * <ul>
   *   <li>{@code A LEFT SEMI JOIN B ON cond} →
   *       {@code A CROSS APPLY (SELECT 1 FROM B WHERE cond HAVING COUNT(*) >= 1)}
   *       （有匹配输出 1 行、无匹配空集，内连接丢空行即 SEMI 语义）；</li>
   *   <li>{@code A LEFT ANTI JOIN B ON cond} →
   *       {@code A CROSS APPLY (SELECT 1 FROM B WHERE cond HAVING COUNT(*) = 0)}
   *       （无匹配输出 1 行、有匹配空集，即 ANTI 语义）。</li>
   * </ul>
   * 聚合空集仍返回单行使去相关稳定（无需 LIMIT）。右侧 FROM 项与 ON 条件边界
   * 无法安全识别（USING/NATURAL、关键字歧义等）时该处保留原样。 */
  private static String preprocessSemiAntiJoin(String sql) {
    if (!SEMI_ANTI_JOIN.matcher(sql).find()) {
      return sql;
    }
    boolean[] live = liveMask(sql);
    StringBuilder out = new StringBuilder(sql.length());
    int pos = 0;
    Matcher m = SEMI_ANTI_JOIN.matcher(sql);
    while (m.find()) {
      if (!spanLive(live, m.start(), m.end()) || m.start() < pos) {
        continue;   // 字面量内的伪命中，或上一条件括号内嵌套的 SEMI/ANTI（已随条件整体保留）
      }
      // 关键字之前的段落先落盘（SEMI/ANTI JOIN 关键字由 CROSS APPLY 形态替换），
      // 再尝试解析右侧 FROM 项与 ON 条件
      int[] tail = parseJoinTail(sql, live, m.end());
      if (tail == null) {
        continue;
      }
      out.append(sql, pos, m.start());
      boolean anti = m.group(1).equalsIgnoreCase("ANTI");
      out.append("CROSS APPLY (SELECT 1 FROM ")
          .append(sql, tail[0], tail[1])
          .append(" WHERE ")
          .append(sql, tail[2], tail[3])
          .append(" HAVING COUNT(*) ").append(anti ? "= 0" : ">= 1")
          .append(')');
      pos = tail[3];
    }
    if (pos == 0) {
      return sql;
    }
    return out.append(sql.substring(pos)).toString();
  }

  /** 解析 SEMI/ANTI JOIN 尾段：跳过空白与右侧 FROM 项（括号包裹的子查询或点分
   * 标识符 + 可选别名），要求后随 ON，条件止于深度 0 的子句关键字/逗号/右括号。
   * 返回 {rhsStart, rhsEnd, condStart, condEnd}，无法安全解析返回 null。 */
  private static int[] parseJoinTail(String sql, boolean[] live, int from) {
    int n = sql.length();
    int i = skipBlank(sql, live, from);
    if (i >= n) {
      return null;
    }
    int rhsStart = i;
    if (sql.charAt(i) == '(') {
      i = matchParen(sql, live, i);
      if (i < 0) {
        return null;
      }
    } else {
      if (!isIdentStart(sql.charAt(i))) {
        return null;
      }
      while (i < n && live[i] && (isIdentPart(sql.charAt(i)) || sql.charAt(i) == '.')) {
        i++;
      }
    }
    int afterRhs = skipBlank(sql, live, i);
    if (!keywordAt(sql, live, afterRhs, "ON")) {
      // 右侧 FROM 项的可选别名（表别名 / 派生表别名）
      if (afterRhs < n && live[afterRhs] && isIdentStart(sql.charAt(afterRhs))) {
        int aliasEnd = afterRhs;
        while (aliasEnd < n && live[aliasEnd] && isIdentPart(sql.charAt(aliasEnd))) {
          aliasEnd++;
        }
        afterRhs = skipBlank(sql, live, aliasEnd);
      }
      if (!keywordAt(sql, live, afterRhs, "ON")) {
        return null;
      }
    }
    int condStart = afterRhs + 2;
    int depth = 0;
    int prevLive = -1;
    for (int j = condStart; j < n; j++) {
      if (!live[j]) {
        continue;
      }
      char c = sql.charAt(j);
      if (Character.isWhitespace(c)) {
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        if (depth == 0) {
          return new int[]{rhsStart, afterRhs, condStart, j};
        }
        depth--;
      } else if (c == ',' && depth == 0) {
        return new int[]{rhsStart, afterRhs, condStart, j};
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        String word = wordAt(sql, j);
        if (CLAUSE_STOPPERS.contains(word.toUpperCase())) {
          return new int[]{rhsStart, afterRhs, condStart, j};
        }
        j += word.length() - 1;
      }
      prevLive = c;
    }
    return new int[]{rhsStart, afterRhs, condStart, n};
  }

  /** FETCH FIRST n ROW[S] WITH TIES（Calcite 解析器不支持）→ 等价改写：
   * 「rank ≤ n」等价于「键值属于排序后前 n 个去重键组」，故改写为
   * {@code SELECT * FROM (core) s WHERE (keys) IN
   * (SELECT keys FROM (core) c GROUP BY keys ORDER BY keys FETCH FIRST n ROWS ONLY)}
   * ，无多余输出列。边界：仅语句级形态（WITH TIES 在语句末尾）；键须为（可带
   * 限定符的）裸列、不支持序数与显式 NULLS FIRST/LAST（IN 对 NULL 组行的三值
   * 逻辑限制）；ORDER BY 键须为 core 输出列。无法安全识别边界时原样保留，
   * 交由解析器报真实错误。 */
  private static String preprocessFetchWithTies(String sql) {
    Matcher m = FETCH_WITH_TIES.matcher(sql);
    if (!m.find()) {
      return sql;
    }
    String suffix = m.group(2);
    boolean[] live = liveMask(sql);
    int[] orderBy = lastTopLevelOrderBy(sql, live, m.start());
    if (orderBy == null) {
      return sql;
    }
    List<String> keys = new ArrayList<>();
    List<String> directions = new ArrayList<>();
    if (!parseOrderKeys(sql.substring(orderBy[1], m.start()).trim(), keys, directions)) {
      return sql;
    }
    String core = sql.substring(0, orderBy[0]);
    String keyList = String.join(", ", keys);
    String orderList = "";
    for (int i = 0; i < keys.size(); i++) {
      orderList += (i > 0 ? ", " : "") + keys.get(i) + " " + directions.get(i);
    }
    String inList = "SELECT " + keyList + " FROM (" + core + ") crossdb_ties_src "
        + "GROUP BY " + keyList + " ORDER BY " + orderList
        + " FETCH FIRST " + m.group(1) + " ROWS ONLY";
    return "SELECT * FROM (" + core + ") crossdb_ties_row WHERE (" + keyList + ") IN ("
        + inList + ") ORDER BY " + orderList + suffix;
  }

  /** 解析 ORDER BY 键清单：每项须为（可带限定符的）裸列名 + 可选 ASC/DESC；
   * 表达式、序数、显式 NULLS FIRST/LAST 均不支持（返回 false 原样保留）。
   * 限定符剥离为裸列名（在 core 派生表内按列名解析）。 */
  private static boolean parseOrderKeys(String keysText, List<String> keys,
      List<String> directions) {
    if (keysText.isEmpty()) {
      return false;
    }
    for (String piece : keysText.split(",")) {
      String p = piece.trim();
      if (p.isEmpty()) {
        return false;
      }
      String dir = "ASC";
      Matcher dirM = java.util.regex.Pattern.compile(
          "(?i)\\s+(ASC|DESC)(\\s+NULLS\\s+(FIRST|LAST))?$").matcher(p);
      if (dirM.find()) {
        dir = dirM.group(1).toUpperCase();
        if (dirM.group(2) != null) {
          return false;
        }
        p = p.substring(0, dirM.start()).trim();
      }
      if (!p.matches("(?i)[a-z_][a-z0-9_$]*(\\s*\\.\\s*[a-z_][a-z0-9_$]*)*")) {
        return false;
      }
      keys.add(p.substring(p.lastIndexOf('.') + 1).trim());
      directions.add(dir);
    }
    return !keys.isEmpty();
  }

  /** 深度 0 的最后一个 ORDER BY 关键字位置（{关键字起点, 列清单起点}）；
   * 与 FETCH 之间出现深度 0 的 OFFSET/集合操作关键字则视为不支持。 */
  private static int[] lastTopLevelOrderBy(String sql, boolean[] live, int limit) {
    int depth = 0;
    int[] last = null;
    int prevLive = -1;
    for (int i = 0; i < limit; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        String word = wordAt(sql, i);
        String upper = word.toUpperCase();
        if (upper.equals("ORDER") && keywordAt(sql, live, i, "ORDER BY")) {
          last = new int[]{i, i + "ORDER BY".length()};
        } else if (last != null && (upper.equals("OFFSET") || upper.equals("UNION")
            || upper.equals("INTERSECT") || upper.equals("EXCEPT"))) {
          return null;
        }
        i += word.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return last;
  }

  /** ORDER BY 键清单是否含序数项（纯数字键在窗口 ORDER BY 内是常量，语义不同）。 */

  /** 活字符掩码：字符串字面量、引号标识符与注释内的位置为 false。 */
  private static boolean[] liveMask(String sql) {
    int n = sql.length();
    boolean[] live = new boolean[n];
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        char quote = c;
        int j = i + 1;
        while (j < n) {
          if (sql.charAt(j) == quote) {
            if (j + 1 < n && sql.charAt(j + 1) == quote) {
              j += 2;   // 双写转义
              continue;
            }
            j++;
            break;
          }
          j++;
        }
        i = j;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        i += 2;
        while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
          i++;
        }
        i = Math.min(i + 2, n);
      } else {
        live[i] = true;
        i++;
      }
    }
    return live;
  }

  private static boolean spanLive(boolean[] live, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!live[i]) {
        return false;
      }
    }
    return true;
  }

  /** 自 start 起跳过空白（仅活字符位置），返回下一个活字符下标。 */
  private static int skipBlank(String sql, boolean[] live, int start) {
    int i = start;
    while (i < sql.length() && (!live[i] || Character.isWhitespace(sql.charAt(i)))) {
      i++;
    }
    return i;
  }

  /** 括号匹配：返回 ')' 之后的位置，不匹配返回 -1。 */
  private static int matchParen(String sql, boolean[] live, int open) {
    int depth = 0;
    for (int i = open; i < sql.length(); i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i + 1;
      }
    }
    return -1;
  }

  /** i 处是否为关键字 keyword（大小写不敏感、词间任意空白、词边界完整）。 */
  private static boolean keywordAt(String sql, boolean[] live, int start, String keyword) {
    String[] words = keyword.toUpperCase().split("\\s+");
    int i = start;
    for (String word : words) {
      i = skipBlank(sql, live, i);
      if (i >= sql.length() || !sql.regionMatches(true, i, word, 0, word.length())) {
        return false;
      }
      int end = i + word.length();
      if (end < sql.length() && live[end] && isIdentPart(sql.charAt(end))) {
        return false;
      }
      i = end;
    }
    return true;
  }

  /** 自 i 起的完整标识符/关键字词（含词内部分）。 */
  private static String wordAt(String sql, int i) {
    int j = i;
    while (j < sql.length() && isIdentPart(sql.charAt(j))) {
      j++;
    }
    return sql.substring(i, j);
  }

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  // ---------- 解析树改写 ----------

  /** 改写入口：表达式级 shuttle 改写 + USING / 别名列清单的 FROM 级改写。 */
  static SqlNode rewrite(SqlNode parsed, SchemaPlus root, JavaTypeFactory typeFactory) {
    parsed = parsed.accept(rewriter());
    return new FromRewriter(root, typeFactory).expand(parsed);
  }

  private static SqlShuttle rewriter() {
    return new SqlShuttle() {
      @Override public SqlNode visit(SqlCall call) {
        call = (SqlCall) super.visit(call);
        if (call.getOperator() == SqlStdOperatorTable.SIMILAR_TO
            || call.getOperator() == SqlStdOperatorTable.NOT_SIMILAR_TO) {
          boolean negative = call.getOperator() == SqlStdOperatorTable.NOT_SIMILAR_TO;
          List<SqlNode> operands = call.getOperandList();
          SqlBasicCall similar = new SqlBasicCall(
              operands.size() >= 3 ? SIMILAR_FN_3 : SIMILAR_FN_2, operands,
              call.getParserPosition());
          return negative
              ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(similar),
                  call.getParserPosition())
              : similar;
        }
        String name = call.getOperator().getName();
        String upper = name.toUpperCase();
        if (upper.equals("RLIKE") || upper.equals("NOT RLIKE")) {
          return rewriteRlike(call);
        }
        if (call.getKind() == SqlKind.TIMESTAMP_DIFF || upper.equals("TIMESTAMPDIFF")) {
          return rewriteTimestampDiff(call);
        }
        if (upper.equals("STRING_AGG") || upper.equals("GROUP_CONCAT")) {
          return rewriteListAgg(call);
        }
        if (upper.equals("LISTAGG")) {
          // 注意：解析器构造的 LISTAGG 是 SqlListaggAggFunction 专有实例，
          // 不能按操作符实例判等，须按名匹配
          return rewriteListaggDistinct(call);
        }
        if (upper.equals("GREATEST") || upper.equals("LEAST")) {
          return rewriteExtremum(call);
        }
        if (upper.equals("DECODE")) {
          return rewriteDecode(call);
        }
        if (upper.equals("MEDIAN")) {
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 1
              ? new SqlBasicCall(MEDIAN_FN, ops, call.getParserPosition())
              : call;
        }
        if (upper.equals("IIF")) {
          return rewriteIif(call);
        }
        if (upper.equals("ISNULL")) {
          return rewriteIsnull(call);
        }
        if (upper.equals("ILIKE") || upper.equals("NOT ILIKE")) {
          return rewriteIlike(call);
        }
        if (upper.equals("ANY_VALUE") || upper.equals("MODE")) {
          // MySQL ANY_VALUE / Oracle MODE：本地 UDAF（ANY_VALUE 取首见非 NULL，
          // MODE 取众数、并列取最小值）
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 1
              ? new SqlBasicCall(upper.equals("ANY_VALUE") ? ANY_VALUE_FN : MODE_FN,
                  ops, call.getParserPosition())
              : call;
        }
        if (upper.equals("ARRAY_AGG")) {
          return rewriteArrayAgg(call);
        }
        if (upper.equals("TRANSLATE") || upper.equals("TRANSLATE3")) {
          // Oracle/PostgreSQL 三参 TRANSLATE：与标准表 SqlTranslateFunction 同名
          // 重载消解冲突（解析器挂 TRANSLATE3 名），统一按名改挂本地实现
          List<SqlNode> ops = call.getOperandList();
          return ops.size() == 3
              ? new SqlBasicCall(TRANSLATE_FN, ops, call.getParserPosition())
              : call;
        }
        if (call.getKind() == SqlKind.WITHIN_GROUP) {
          return rewriteWithinGroup(call);
        }
        if (call.getKind() == SqlKind.OTHER_FUNCTION) {
          return switch (upper) {
            case "BOOL_AND", "EVERY" -> new SqlBasicCall(BOOL_AND_FN, call.getOperandList(),
                call.getParserPosition());
            case "BOOL_OR" -> new SqlBasicCall(BOOL_OR_FN, call.getOperandList(),
                call.getParserPosition());
            case "INITCAP" -> new SqlBasicCall(INITCAP_FN, call.getOperandList(),
                call.getParserPosition());
            case "OVERLAY" -> {
              List<SqlNode> ops = call.getOperandList();
              yield ops.size() == 3 || ops.size() == 4
                  ? new SqlBasicCall(ops.size() == 3 ? OVERLAY_FN_3 : OVERLAY_FN_4,
                      ops, call.getParserPosition())
                  : call;
            }
            case "STRING_AGG", "GROUP_CONCAT" -> rewriteListAgg(call);
            case "VAR_POP", "VAR_SAMP", "STDDEV_POP", "STDDEV_SAMP" -> {
              List<SqlNode> ops = call.getOperandList();
              yield ops.size() == 1
                  ? new SqlBasicCall(call.getOperator(),
                      List.of(castDouble(ops.get(0), call.getParserPosition())),
                      call.getParserPosition())
                  : call;
            }
            default -> call;
          };
        }
        if (call.getKind() == SqlKind.FLOOR || call.getKind() == SqlKind.CEIL) {
          // FLOOR/CEIL(x TO unit)：datetime 截断语法下推源库必错，改本地 UDF
          List<SqlNode> ops = call.getOperandList();
          if (ops.size() == 2 && ops.get(1) instanceof SqlIntervalQualifier q
              && q.getUnit() != null) {
            return new SqlBasicCall(
                call.getKind() == SqlKind.FLOOR ? FLOOR_UNIT_FN : CEIL_UNIT_FN,
                List.of(ops.get(0), SqlLiteral.createCharString(q.getUnit().name(),
                    call.getParserPosition())),
                call.getParserPosition());
          }
          return call;
        }
        if (switch (call.getKind()) {
          case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL -> true;
          default -> false;
        }) {
          // 行构造器不等比较：Enumerable 运行时不实现 ROW 类型排序比较，
          // 展开为字典序等价的标量比较组合
          return rewriteRowComparison(call);
        }
        if (call.getKind() == SqlKind.OVER) {
          return rewriteOver(call);
        }
        return call;
      }
    };
  }

  /** {@code a RLIKE / NOT RLIKE pattern}（校验器未注册的 SqlLikeOperator）→
   * {@code CROSSDB_REGEXP(a, pattern)} 本地求值；转义子句形态不支持，保留原样。 */
  private static SqlNode rewriteRlike(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlBasicCall regexp = new SqlBasicCall(REGEXP_FN, ops, pos);
    return call.getOperator().getName().equalsIgnoreCase("NOT RLIKE")
        ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(regexp), pos)
        : regexp;
  }

  /** IIF(cond, t, f)（SQL Server 方言，未注册于标准操作符表）→ CASE WHEN cond
   * THEN t ELSE f END（语义等价）。 */
  private static SqlNode rewriteIif(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 3) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(ops.get(0));
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(ops.get(1));
    return new SqlCase(pos, null, whens, thens, ops.get(2));
  }

  /** ISNULL(a, b)（SQL Server 方言）→ COALESCE(a, b)（NULL 语义一致）。 */
  private static SqlNode rewriteIsnull(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    return new SqlBasicCall(SqlStdOperatorTable.COALESCE, ops, call.getParserPosition());
  }

  /** {@code a ILIKE p / a NOT ILIKE p}（PostgreSQL 大小写不敏感 LIKE，校验器未注册）→
   * {@code LOWER(a) [NOT] LIKE LOWER(p)}（等价：两侧同折叠后匹配）。 */
  private static SqlNode rewriteIlike(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode like = new SqlBasicCall(SqlStdOperatorTable.LIKE, List.of(
        new SqlBasicCall(SqlStdOperatorTable.LOWER, List.of(ops.get(0)), pos),
        new SqlBasicCall(SqlStdOperatorTable.LOWER, List.of(ops.get(1)), pos)), pos);
    return call.getOperator().getName().equalsIgnoreCase("NOT ILIKE")
        ? new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(like), pos)
        : like;
  }

  /** ARRAY_AGG(x [ORDER BY o])（引擎不支持 ARRAY 值类型透出）→ CROSSDB_ARRAY_AGG(x)
   * （{@code "[v1, v2, ...]"} 字符串渲染、按值升序保证确定性）；ORDER BY 与取值
   * 表达式一致时升序内置于 UDAF、直接去包装，其余排序变体保留 WITHIN GROUP 形态
   * （Calcite 对 UDAF 不强制输入有序，排序尽力而为）。 */
  private static SqlNode rewriteArrayAgg(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode value = ops.get(0);
    SqlNodeList order = ops.size() > 1 && ops.get(1) instanceof SqlNodeList list
        ? list : null;
    SqlNode agg = new SqlBasicCall(ARRAY_AGG_FN, List.of(value), pos);
    // SqlNode.equals 为恒等语义，排序项与取值表达式的一致性按打印形态比较
    if (order == null || (order.size() == 1
        && order.get(0).toString().equalsIgnoreCase(value.toString()))) {
      return agg;
    }
    return new SqlBasicCall(new SqlWithinGroupOperator(), List.of(agg, order), pos);
  }

  /** AGG(x) FILTER (WHERE c) → AGG(CASE WHEN c THEN x END)：跳 NULL 语义的聚合上
   * 等价（SUM/COUNT/AVG/MIN/MAX 及本地 UDAF）；COUNT(*) 的星号操作数换为常量 1；
   * DISTINCT 量化符保留（COUNT 的去重变体直接换挂本地 CROSSDB_COUNT_DISTINCT）。
   * 多操作数（LISTAGG 等）形态返回 null，保留原样交校验器报错。 */
  private static SqlNode filterToCase(SqlCall agg, SqlNode cond, SqlParserPos pos) {
    List<SqlNode> ops = agg.getOperandList();
    SqlNode arg;
    if (ops.size() == 1 && ops.get(0) instanceof SqlIdentifier id && id.isStar()) {
      arg = SqlLiteral.createExactNumeric("1", pos);
    } else if (ops.size() == 1) {
      arg = ops.get(0);
    } else {
      return null;
    }
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(cond);
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(arg);
    SqlNode wrapped =
        new SqlCase(pos, null, whens, thens, SqlLiteral.createNull(pos));
    SqlLiteral quantifier =
        agg instanceof SqlBasicCall basic ? basic.getFunctionQuantifier() : null;
    if (quantifier != null && agg.getOperator().getName().equalsIgnoreCase("COUNT")
        && org.apache.calcite.sql.SqlSelectKeyword.DISTINCT
            == quantifier.getValueAs(org.apache.calcite.sql.SqlSelectKeyword.class)) {
      return new SqlBasicCall(COUNT_DISTINCT_FN, List.of(wrapped), pos);
    }
    return new SqlBasicCall(agg.getOperator(), List.of(wrapped), pos, quantifier);
  }

  /** 行构造器不等比较（Enumerable 运行时不实现 ROW 类型排序比较）→ 按标准 SQL
   * 行值比较的展开定义改写为标量比较组合：
   * {@code (a1..an) OP (b1..bn) ≡ OR_i ( AND_{j<i} a_j = b_j AND a_i OP b_i )}
   * （NULL 三值逻辑与展开定义一致）。任一侧非行构造器、字段数不符或为空时保留原样。 */
  private static SqlNode rewriteRowComparison(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !isRowCall(ops.get(0)) || !isRowCall(ops.get(1))) {
      return call;
    }
    List<SqlNode> left = ((SqlCall) ops.get(0)).getOperandList();
    List<SqlNode> right = ((SqlCall) ops.get(1)).getOperandList();
    if (left.size() != right.size() || left.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    org.apache.calcite.sql.SqlOperator cmpOp = switch (call.getKind()) {
      case LESS_THAN -> SqlStdOperatorTable.LESS_THAN;
      case LESS_THAN_OR_EQUAL -> SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
      case GREATER_THAN -> SqlStdOperatorTable.GREATER_THAN;
      default -> SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
    };
    SqlNode out = null;
    for (int i = 0; i < left.size(); i++) {
      SqlNode term = new SqlBasicCall(cmpOp, List.of(left.get(i), right.get(i)), pos);
      for (int j = i - 1; j >= 0; j--) {
        term = new SqlBasicCall(SqlStdOperatorTable.AND, List.of(
            new SqlBasicCall(SqlStdOperatorTable.EQUALS,
                List.of(left.get(j), right.get(j)), pos), term), pos);
      }
      out = out == null ? term
          : new SqlBasicCall(SqlStdOperatorTable.OR, List.of(out, term), pos);
    }
    return out;
  }

  private static boolean isRowCall(SqlNode node) {
    return node instanceof SqlCall c && c.getKind() == SqlKind.ROW;
  }

  /** STRING_AGG(x[, sep][, ORDER BY ..]) / GROUP_CONCAT(x[, ORDER BY ..][, SEPARATOR sep])
   * → LISTAGG(x, sep) [WITHIN GROUP (ORDER BY ..)]。 */
  private static SqlNode rewriteListAgg(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.isEmpty()) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode value = ops.get(0);
    SqlNode sep = SqlLiteral.createCharString(",", pos);
    SqlNodeList order = null;
    for (int i = 1; i < ops.size(); i++) {
      SqlNode op = ops.get(i);
      if (op instanceof SqlNodeList list) {
        order = list;
      } else if (op instanceof SqlCall sepCall && sepCall.getKind() == SqlKind.SEPARATOR) {
        sep = sepCall.operand(0);
      } else if (op instanceof SqlLiteral lit && lit.getTypeName() == SqlTypeName.VARCHAR) {
        sep = op;
      }
    }
    // 原调用带 DISTINCT 量化符（STRING_AGG(DISTINCT ..) / GROUP_CONCAT(DISTINCT ..)）：
    // 直接换成本地去重 UDAF，避免静默丢失去重语义
    boolean distinct = call instanceof SqlBasicCall basic && basic.getFunctionQuantifier() != null;
    SqlNode rewritten = distinct
        ? new SqlBasicCall(LISTAGG_DISTINCT_FN, List.of(value, sep), pos)
        : new SqlBasicCall(SqlStdOperatorTable.LISTAGG, List.of(value, sep), pos);
    return order == null ? rewritten
        : new SqlBasicCall(new SqlWithinGroupOperator(), List.of(rewritten, order), pos);
  }

  /** DECODE(e, s1, r1[, s2, r2...][, default]) → 等价 searched CASE：
   * {@code CASE WHEN e IS NOT DISTINCT FROM s1 THEN r1 ... [ELSE default] END}
   * （Oracle 语义：NULL 与 NULL 视为相等，由 IS NOT DISTINCT FROM 承载；
   * 无缺省分支回落 NULL；元数 ≥3 的任意形态均可改写，否则原样保留交由校验器报错）。 */
  private static SqlNode rewriteDecode(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() < 3) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    SqlNode target = ops.get(0);
    SqlNodeList whens = new SqlNodeList(pos);
    SqlNodeList thens = new SqlNodeList(pos);
    for (int i = 1; i + 1 < ops.size(); i += 2) {
      whens.add(new SqlBasicCall(SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
          List.of(target, ops.get(i)), pos));
      thens.add(ops.get(i + 1));
    }
    SqlNode elseNode = ops.size() % 2 == 0
        ? ops.get(ops.size() - 1)        // 末位缺省分支
        : SqlLiteral.createNull(pos);    // 无缺省回落 NULL
    return new SqlCase(pos, null, whens, thens, elseNode);
  }

  /** GREATEST/LEAST(a, b, ...) → 按元数挂载对应本地 UDF（跳过 NULL 取极值）。 */
  private static SqlNode rewriteExtremum(SqlCall call) {
    int n = call.operandCount();
    if (n < 2 || n > 4) {
      return call;
    }
    boolean greatest = call.getOperator().getName().equalsIgnoreCase("GREATEST");
    SqlUserDefinedFunction fn = switch ((greatest ? "G" : "L") + n) {
      case "G2" -> GREATEST2;
      case "G3" -> GREATEST3;
      case "G4" -> GREATEST4;
      case "L2" -> LEAST2;
      case "L3" -> LEAST3;
      default -> LEAST4;
    };
    return new SqlBasicCall(fn, call.getOperandList(), call.getParserPosition());
  }

  /** TIMESTAMPDIFF(unit, a, b)（Calcite SqlTimestampDiffFunction，下推源库普遍无此
   * 函数）→ CROSSDB_TIMESTAMPDIFF('unit', a, b) 本地求值。兼容 BigQuery 的
   * 「时间在前、unit 在后」参数顺序。无法安全改写的形态原样保留。 */
  private static SqlNode rewriteTimestampDiff(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 3) {
      return call;
    }
    SqlIntervalQualifier unit = null;
    List<SqlNode> times = new ArrayList<>();
    for (SqlNode op : ops) {
      if (op instanceof SqlIntervalQualifier q && unit == null) {
        unit = q;
      } else {
        times.add(op);
      }
    }
    if (unit == null || unit.getStartUnit() == null || times.size() != 2) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    return new SqlBasicCall(TIMESTAMPDIFF_FN, List.of(
        SqlLiteral.createCharString(unit.getStartUnit().toString(), pos),
        times.get(0), times.get(1)), pos);
  }

  /** WITHIN GROUP 包装改写：
   * <ul>
   *   <li>PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x) → CROSSDB_PERCENTILE_CONT(x, p)
   *   （Enumerable 无原生实现）；</li>
   *   <li>PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY x) → CROSSDB_PERCENTILE_DISC(x, p)
   *   （离散百分位，Enumerable 无原生实现）；</li>
   *   <li>LISTAGG(DISTINCT x, sep) WITHIN GROUP (ORDER BY x) → CROSSDB_LISTAGG(x, sep)
   *   （原生 LISTAGG DISTINCT 计划期 AIOOBE；排序内置为按值升序，故要求 ORDER BY
   *   与取值表达式一致，否则保留原形态交由校验器报错）；</li>
   * </ul> */
  private static SqlNode rewriteWithinGroup(SqlCall within) {
    List<SqlNode> ops = within.getOperandList();
    if (ops.size() != 2 || !(ops.get(0) instanceof SqlCall agg)
        || !(ops.get(1) instanceof SqlNodeList order) || order.size() != 1) {
      return within;
    }
    SqlParserPos pos = within.getParserPosition();
    if (agg.getOperator() == PERCENTILE_CONT_FN || agg.getKind() == SqlKind.PERCENTILE_CONT
        || agg.getOperator().getName().equalsIgnoreCase("PERCENTILE_CONT")) {
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 1) {
        return new SqlBasicCall(PERCENTILE_CONT_FN,
            List.of(order.get(0), aggOps.get(0)), pos);
      }
      return within;
    }
    if (agg.getOperator() == PERCENTILE_DISC_FN || agg.getKind() == SqlKind.PERCENTILE_DISC
        || agg.getOperator().getName().equalsIgnoreCase("PERCENTILE_DISC")) {
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 1) {
        return new SqlBasicCall(PERCENTILE_DISC_FN,
            List.of(order.get(0), aggOps.get(0)), pos);
      }
      return within;
    }
    if (agg.getOperator() == LISTAGG_DISTINCT_FN) {
      // 内层 LISTAGG(DISTINCT ..) 已被改写；仅当排序表达式与取值一致时去掉包装
      List<SqlNode> aggOps = agg.getOperandList();
      if (aggOps.size() == 2 && order.get(0).equals(aggOps.get(0))) {
        return agg;
      }
    }
    return within;
  }

  /** LISTAGG(DISTINCT x, sep) → CROSSDB_LISTAGG(x, sep)（DISTINCT 量化符剥离，
   * 去重 + 按值升序由本地 UDAF 实现；排序语义由 WITHIN GROUP 包装层收口）。 */
  private static SqlNode rewriteListaggDistinct(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !(call instanceof SqlBasicCall basic)
        || basic.getFunctionQuantifier() == null) {
      return call;
    }
    return new SqlBasicCall(LISTAGG_DISTINCT_FN, ops, call.getParserPosition());
  }

  /** 窗口聚合改写：CUME_DIST/PERCENT_RANK 用 RANK/COUNT(*) 等价表达；
   * NTH_VALUE 换成本地窗口聚合（帧内第 n 行）；
   * FILTER（与 OVER 组合时校验器拒绝「OVER must be applied to aggregate function」）→
   * 等价 CASE 包参改写；IGNORE NULLS（FIRST_VALUE/LAST_VALUE）→ 本地非 NULL 端点
   * 窗口聚合；COUNT(DISTINCT x)（EnumerableWindow 静默丢弃 DISTINCT 量化符）→
   * 本地去重计数窗口聚合；其余原样。 */
  private static SqlNode rewriteOver(SqlCall over) {
    List<SqlNode> ops = over.getOperandList();
    if (ops.size() != 2 || !(ops.get(0) instanceof SqlCall agg)
        || !(ops.get(1) instanceof org.apache.calcite.sql.SqlWindow w)) {
      return over;
    }
    SqlParserPos pos = over.getParserPosition();
    // AGG(x) FILTER (WHERE c) OVER w → AGG(CASE WHEN c THEN x END) OVER w：
    // 跳 NULL 语义的聚合（SUM/COUNT/AVG/MIN/MAX 及本地 UDAF）上两者等价。
    // 注意解析树上 std 聚合尚未绑定（kind=OTHER_FUNCTION），isAggregator() 为
    // false，须按名白名单 + 已绑定操作符共同判定
    if (agg.getKind() == SqlKind.FILTER) {
      List<SqlNode> fops = agg.getOperandList();
      if (fops.size() == 2 && fops.get(0) instanceof SqlCall inner) {
        String innerName = inner.getOperator().getName().toUpperCase();
        if (inner.getOperator().isAggregator() || innerName.equals("SUM")
            || innerName.equals("COUNT") || innerName.equals("AVG")
            || innerName.equals("MIN") || innerName.equals("MAX")) {
          SqlNode rewritten = filterToCase(inner, fops.get(1), pos);
          if (rewritten != null) {
            return over(rewritten, w, pos);
          }
        }
      }
      return over;   // 无法安全改写的形态保留，交校验器报真实错误
    }
    // FIRST_VALUE/LAST_VALUE(x) IGNORE NULLS OVER w → 本地首/末非 NULL 端点聚合；
    // 仅在 OVER 语境下改写（裸 IGNORE NULLS 无窗口本就非法）。LEAD/LAG 等保留。
    if (agg.getKind() == SqlKind.IGNORE_NULLS && agg.getOperandList().size() == 1
        && agg.getOperandList().get(0) instanceof SqlCall inner
        && inner.getOperandList().size() == 1) {
      String name = inner.getOperator().getName().toUpperCase();
      if (name.equals("FIRST_VALUE") || name.equals("LAST_VALUE")) {
        return over(new SqlBasicCall(
            name.equals("FIRST_VALUE") ? FIRST_VALUE_NN_FN : LAST_VALUE_NN_FN,
            inner.getOperandList(), inner.getParserPosition()), w, pos);
      }
      return over;
    }
    // COUNT(DISTINCT x) OVER w：EnumerableWindow 静默丢弃 DISTINCT（得到 COUNT(*)
    // 语义的错误结果），改挂本地去重计数 UDAF 修正
    if (agg instanceof SqlBasicCall basic
        && basic.getFunctionQuantifier() != null
        && org.apache.calcite.sql.SqlSelectKeyword.DISTINCT
            == basic.getFunctionQuantifier()
                .getValueAs(org.apache.calcite.sql.SqlSelectKeyword.class)
        && agg.getOperator().getName().equalsIgnoreCase("COUNT")
        && agg.getOperandList().size() == 1
        && !(agg.getOperandList().get(0) instanceof SqlIdentifier id && id.isStar())) {
      return over(new SqlBasicCall(COUNT_DISTINCT_FN, agg.getOperandList(),
          agg.getParserPosition()), w, pos);
    }
    switch (agg.getOperator().getName().toUpperCase()) {
      case "CUME_DIST" -> {        // CUME_DIST() OVER w == RANK() OVER w / COUNT(*) OVER (同分区、无排序)
        return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
            castDouble(over(rank(pos), w, pos), pos),
            over(count(pos), partitionOnly(w), pos)), pos);
      }
      case "PERCENT_RANK" -> {
        // PERCENT_RANK() OVER w == (RANK() OVER w - 1) / (COUNT(*) OVER (同分区) - 1)
        return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
            new SqlBasicCall(SqlStdOperatorTable.MINUS, List.of(
                castDouble(over(rank(pos), w, pos), pos),
                SqlLiteral.createExactNumeric("1", pos)), pos),
            new SqlBasicCall(SqlStdOperatorTable.MINUS, List.of(
                over(count(pos), partitionOnly(w), pos),
                SqlLiteral.createExactNumeric("1", pos)), pos)), pos);
      }
      case "NTH_VALUE" -> {
        // NTH_VALUE(x, n) → CROSSDB_NTH_VALUE{n}(x)：本地窗口聚合按「帧内第 n 行」
        // 取值（Calcite 内建与源库均按整分区取值、忽略帧）。常量 n 不进操作数、
        // 编码进函数名，绕开窗口聚合常量参数被输入投影裁剪的缺陷；n 不在 1..4
        // 时保留原样。
        List<SqlNode> aggOps = agg.getOperandList();
        if (aggOps.size() == 2 && aggOps.get(1) instanceof SqlLiteral lit
            && lit.getValue() instanceof Number num
            && num.intValue() >= 2 && num.intValue() <= 4) {
          SqlUserDefinedAggFunction fn = switch (num.intValue()) {
            case 2 -> NTH_VALUE2_FN;
            case 3 -> NTH_VALUE3_FN;
            default -> NTH_VALUE4_FN;
          };
          return over(new SqlBasicCall(fn, List.of(aggOps.get(0)), agg.getParserPosition()),
              w, pos);
        }
        return over;
      }
      default -> {
        return over;
      }
    }
  }

  private static SqlNode rank(SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.RANK, new SqlNode[0], pos);
  }

  private static SqlNode count(SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.COUNT, List.of(SqlIdentifier.STAR), pos);
  }

  private static SqlNode over(SqlNode aggCall, org.apache.calcite.sql.SqlWindow w, SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.OVER, List.of(aggCall, w), pos);
  }

  /** 同分区、无排序的窗口副本：COUNT(*) OVER (PARTITION BY ..) 给出分区总行数。
   * 在克隆上改写，不影响原窗口（RANK 仍需其 ORDER BY）。 */
  @SuppressWarnings("unchecked")
  private static org.apache.calcite.sql.SqlWindow partitionOnly(
      org.apache.calcite.sql.SqlWindow w) {
    org.apache.calcite.sql.SqlWindow copy = (org.apache.calcite.sql.SqlWindow) w.clone(w.getParserPosition());
    List<SqlNode> operands = copy.getOperandList();
    for (int i = 0; i < operands.size(); i++) {
      if (operands.get(i) == copy.getOrderList()) {
        copy.setOperand(i, new SqlNodeList(w.getParserPosition()));
        return copy;
      }
    }
    return copy;
  }

  private static SqlNode castDouble(SqlNode expr, SqlParserPos pos) {
    return new SqlBasicCall(SqlStdOperatorTable.CAST, List.of(expr,
        new SqlDataTypeSpec(new SqlBasicTypeNameSpec(SqlTypeName.DOUBLE, pos), pos)), pos);
  }

  // ---------- FROM 级改写（USING 展开、别名列清单） ----------

  /** 遍历每个 SELECT 作用域的 FROM 树：USING 连接展开为 ON 并记录共享列；
   * 表别名列名清单展开为派生表；随后把本层裸引用的共享列替换为 COALESCE。 */
  private static final class FromRewriter {
    private final SchemaPlus root;
    private final JavaTypeFactory typeFactory;

    FromRewriter(SchemaPlus root, JavaTypeFactory typeFactory) {
      this.root = root;
      this.typeFactory = typeFactory;
    }

    SqlNode expand(SqlNode stmt) {
      switch (stmt) {
        case SqlOrderBy ob -> expand(ob.query);
        case org.apache.calcite.sql.SqlWith with -> expand(with.body);
        case SqlCall set when set.getKind() == SqlKind.UNION
            || set.getKind() == SqlKind.INTERSECT || set.getKind() == SqlKind.EXCEPT -> {
          for (SqlNode op : set.getOperandList()) {
            if (op != null) {
              expand(op);
            }
          }
        }
        case SqlSelect select -> expandSelect(select);
        default -> {
        }
      }
      return stmt;
    }

    private void expandSelect(SqlSelect select) {
      if (select.getFrom() == null) {
        return;
      }
      Map<String, SqlNode[]> coalesce = new LinkedHashMap<>();
      select.setFrom(rewriteFrom(select.getFrom(), coalesce));
      if (!coalesce.isEmpty()) {
        substituteBare(select, coalesce);
      }
    }

    /** 递归改写 FROM 树，返回改写后的节点（可能为原实例）。 */
    private SqlNode rewriteFrom(SqlNode node, Map<String, SqlNode[]> coalesce) {
      if (node instanceof SqlJoin join) {
        SqlNode left = rewriteFrom(join.getLeft(), coalesce);
        SqlNode right = rewriteFrom(join.getRight(), coalesce);
        join.setLeft(left);
        join.setRight(right);
        if (join.getConditionType() == JoinConditionType.USING
            && join.getCondition() instanceof SqlNodeList cols) {
          String ln = itemName(join.getLeft());
          String rn = itemName(join.getRight());
          if (ln != null && rn != null) {
            // USING (c..) → ON l.c = r.c AND ...（INNER/OUTER 语义均与 COALESCE 合并列一致）
            SqlParserPos pos = node.getParserPosition();
            SqlNode on = null;
            for (SqlNode colNode : cols) {
              String col = ((SqlIdentifier) colNode).getSimple();
              SqlNode eq = new SqlBasicCall(SqlStdOperatorTable.EQUALS,
                  List.of(qualify(ln, col, pos), qualify(rn, col, pos)), pos);
              on = on == null ? eq
                  : new SqlBasicCall(SqlStdOperatorTable.AND, List.of(on, eq), pos);
              coalesce.putIfAbsent(col, new SqlNode[]{qualify(ln, col, pos),
                  qualify(rn, col, pos)});
            }
            // operand 布局：[left, natural, joinType, right, conditionType, condition]
            join.setOperand(4, SqlLiteral.createSymbol(JoinConditionType.ON, pos));
            join.setOperand(5, on);
          }
        }
        return join;
      }
      if (node instanceof SqlBasicCall as && as.getKind() == SqlKind.AS
          && as.operandCount() >= 3
          && as.getOperandList().stream().skip(2).allMatch(o -> o instanceof SqlIdentifier)) {
        // t(a, b) 列名清单 → 派生表列重命名
        SqlNode inner = rewriteFrom(as.operand(0), coalesce);
        SqlNode derived = deriveWithColumns(inner, as);
        return derived != null
            ? new SqlBasicCall(SqlStdOperatorTable.AS, List.of(derived, as.operand(1)),
                node.getParserPosition())
            : as;
      }
      return node;
    }

    /** 仅当底层是 schema 可解析字段名的表引用时，生成等价派生表，否则 null。 */
    private SqlNode deriveWithColumns(SqlNode table, SqlBasicCall as) {
      if (!(table instanceof SqlIdentifier ident)) {
        return null;
      }
      List<String> fields = resolveFields(ident);
      if (fields == null || fields.size() < as.operandCount() - 2) {
        return null;
      }
      SqlParserPos pos = as.getParserPosition();
      SqlNodeList selectList = new SqlNodeList(pos);
      List<String> cols = as.getOperandList().stream().skip(2)
          .map(o -> ((SqlIdentifier) o).getSimple()).toList();
      for (int i = 0; i < cols.size(); i++) {
        selectList.add(new SqlBasicCall(SqlStdOperatorTable.AS, List.of(
            new SqlIdentifier(fields.get(i), pos), new SqlIdentifier(cols.get(i), pos)), pos));
      }
      // (pos, keywords, selectList, from, where, groupBy, having, windowDecls, orderBy,
      //  offset, fetch, hints)
      return new SqlSelect(pos, new SqlNodeList(pos), selectList, ident, null, null, null,
          new SqlNodeList(pos), null, null, null, new SqlNodeList(pos));
    }

    private List<String> resolveFields(SqlIdentifier ident) {
      List<String> names = ident.names;
      SchemaPlus s = root;
      for (int i = 0; i < names.size() - 1; i++) {
        s = findSchema(s, names.get(i));
        if (s == null) {
          return null;
        }
      }
      Table table = findTable(s, names.get(names.size() - 1));
      return table == null ? null : table.getRowType(typeFactory).getFieldNames();
    }

    private SchemaPlus findSchema(SchemaPlus parent, String name) {
      for (String n : parent.getSubSchemaNames()) {
        if (n.equalsIgnoreCase(name)) {
          return parent.getSubSchema(n);
        }
      }
      return null;
    }

    private Table findTable(SchemaPlus schema, String name) {
      for (String n : schema.getTableNames()) {
        if (n.equalsIgnoreCase(name)) {
          return schema.getTable(n);
        }
      }
      return null;
    }

    /** FROM 项的可见名：表名或别名；派生表等无名字形态返回 null。 */
    private static String itemName(SqlNode node) {
      if (node instanceof SqlIdentifier id) {
        return id.names.get(id.names.size() - 1);
      }
      if (node instanceof SqlBasicCall as && as.getKind() == SqlKind.AS
          && as.operandCount() >= 2 && as.operand(1) instanceof SqlIdentifier alias) {
        return alias.getSimple();
      }
      return null;
    }

    private static SqlIdentifier qualify(String base, String col, SqlParserPos pos) {
      return new SqlIdentifier(List.of(base, col), pos);
    }

    /** 本层 SELECT 的裸引用（单段名命中 USING 共享列）替换为 COALESCE(l.c, r.c)；
     * 不下钻嵌套子查询（新作用域）。 */
    private static void substituteBare(SqlSelect select, Map<String, SqlNode[]> coalesce) {
      substituteList(select.getSelectList(), coalesce);
      if (select.getWhere() != null) {
        select.setWhere(substitute(select.getWhere(), coalesce));
      }
      if (select.getGroup() != null) {
        substituteList(select.getGroup(), coalesce);
      }
      if (select.getHaving() != null) {
        select.setHaving(substitute(select.getHaving(), coalesce));
      }
    }

    private static void substituteList(SqlNodeList list, Map<String, SqlNode[]> coalesce) {
      for (int i = 0; i < list.size(); i++) {
        list.set(i, substitute(list.get(i), coalesce));
      }
    }

    private static SqlNode substitute(SqlNode node, Map<String, SqlNode[]> coalesce) {
      if (node == null) {
        return null;
      }
      if (node instanceof SqlIdentifier id && id.names.size() == 1) {
        SqlNode[] pair = coalesce.get(id.getSimple());
        return pair == null ? node
            : new SqlBasicCall(SqlStdOperatorTable.COALESCE, List.of(pair[0], pair[1]),
                id.getParserPosition());
      }
      if (node instanceof SqlSelect || node instanceof org.apache.calcite.sql.SqlWith) {
        return node;
      }
      if (node instanceof SqlBasicCall call) {
        List<SqlNode> ops = call.getOperandList();
        List<SqlNode> newOps = new ArrayList<>(ops.size());
        boolean changed = false;
        for (SqlNode op : ops) {
          SqlNode nn = op == null ? null : substitute(op, coalesce);
          newOps.add(nn);
          changed |= nn != op;
        }
        return changed
            ? new SqlBasicCall(call.getOperator(), newOps, call.getParserPosition())
            : call;
      }
      return node;
    }
  }
}

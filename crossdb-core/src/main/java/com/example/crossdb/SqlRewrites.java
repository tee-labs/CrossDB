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
  /** LEFT/RIGHT(s, n)（MySQL/SQL Server 方言）→ 本地实现（操作数 ANY 混合承载）。 */
  private static final SqlUserDefinedFunction LEFT_FN =
      udf("CROSSDB_LEFT", "left", List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  private static final SqlUserDefinedFunction RIGHT_FN =
      udf("CROSSDB_RIGHT", "right", List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  /** LOCATE(substr, str[, start])（MySQL/PostgreSQL 方言）→ 本地实现。 */
  // 按元数异名（同 GREATEST/LEAST 先例）：同名双元数会在校验器按名重查时触发
  // ANY 类型优先级断言（SqlTypeExplicitPrecedenceList）
  private static final SqlUserDefinedFunction LOCATE2_FN =
      udf("CROSSDB_LOCATE2", "locate2", List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.ANY, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction LOCATE3_FN =
      udf("CROSSDB_LOCATE3", "locate3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ReturnTypes.BIGINT_NULLABLE);
  /** MOD 浮点语义修正（仅浮点操作数改写挂载；整数走原生 MOD 保下推）。 */
  private static final SqlUserDefinedFunction MOD_FN =
      udf("CROSSDB_MOD", "mod", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ReturnTypes.DOUBLE_NULLABLE);
  /** DIV 整除（MySQL 操作符改写目标）。 */
  private static final SqlUserDefinedFunction IDIV_FN =
      udf("CROSSDB_IDIV", "idiv", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ReturnTypes.BIGINT_NULLABLE);
  /** Oracle 日期函数：本地实现（H2 亦同名可下推；ADD_MONTHS 返回类型随首参 DATE）。 */
  private static final SqlUserDefinedFunction ADD_MONTHS_FN =
      udf("ADD_MONTHS", "addMonths", List.of(SqlTypeFamily.ANY, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  private static final SqlUserDefinedFunction MONTHS_BETWEEN_FN =
      udf("MONTHS_BETWEEN", "monthsBetween", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, new SqlTypeTransformCascade(
              b -> b.getTypeFactory().createSqlType(SqlTypeName.DECIMAL),
              SqlTypeTransforms.TO_NULLABLE));
  /** 位运算族（& | ^ ~ << >> 操作符改写目标，MySQL/PostgreSQL）。 */
  private static final SqlUserDefinedFunction BITAND_FN =
      udf("CROSSDB_BITAND", "bitAnd", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction BITOR_FN =
      udf("CROSSDB_BITOR", "bitOr", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction BITXOR_FN =
      udf("CROSSDB_BITXOR", "bitXor", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction BITNOT_FN =
      udf("CROSSDB_BITNOT", "bitNot", List.of(SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction SHL_FN =
      udf("CROSSDB_SHL", "shl", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);
  private static final SqlUserDefinedFunction SHR_FN =
      udf("CROSSDB_SHR", "shr", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, ReturnTypes.BIGINT_NULLABLE);

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

  /** REPEAT / CHR：SPACE→REPEAT、CHAR→CHR 改写挂载目标（亦作 SQL 可见函数）。 */
  static final SqlUserDefinedFunction REPEAT_FN =
      udf("REPEAT", "repeat",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC), SqlTypeName.VARCHAR, ARG0_NULLABLE);
  static final SqlUserDefinedFunction CHR_FN =
      udf("CHR", "chr", List.of(SqlTypeFamily.NUMERIC), SqlTypeName.VARCHAR, VARCHAR_NULLABLE);

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
      REPEAT_FN,
      CHR_FN,
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
      TIMESTAMPDIFF_FN,
      LEFT_FN, RIGHT_FN, LOCATE2_FN, LOCATE3_FN, MOD_FN, IDIV_FN,
      ADD_MONTHS_FN, MONTHS_BETWEEN_FN,
      BITAND_FN, BITOR_FN, BITXOR_FN, BITNOT_FN, SHL_FN, SHR_FN);
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
      case "left", "right" -> new Class<?>[]{String.class, BigDecimal.class};
      case "locate2" -> new Class<?>[]{String.class, String.class};
      case "locate3" -> new Class<?>[]{String.class, String.class, BigDecimal.class};
      case "mod", "idiv" -> new Class<?>[]{Object.class, Object.class};
      case "addMonths" -> new Class<?>[]{Object.class, BigDecimal.class};
      case "monthsBetween" -> new Class<?>[]{Object.class, Object.class};
      case "bitAnd", "bitOr", "bitXor", "shl", "shr" -> new Class<?>[]{Long.class, Long.class};
      case "bitNot" -> new Class<?>[]{Long.class};
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
   * LISTAGG ON OVERFLOW ERROR / REGEXP / STRAIGHT_JOIN / 语句尾分号剥离。各改写仅在
   * 能安全识别边界时生效，否则原样返回交由解析器/校验器报真实错误。 */
  static String preprocess(String sql) {
    sql = preprocessTrailingSemicolon(sql);
    sql = preprocessTop(sql);
    sql = preprocessSemiAntiJoin(sql);
    sql = preprocessFetchWithTies(sql);
    sql = preprocessListaggOverflowError(sql);
    sql = preprocessRegexp(sql);
    sql = preprocessStraightJoin(sql);
    sql = preprocessFetchPercent(sql);
    sql = preprocessGroupsFrame(sql);
    sql = preprocessBitwiseDiv(sql);
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

  private static final Pattern STRAIGHT_SELECT =
      Pattern.compile("(?i)\\bSELECT\\s+STRAIGHT_JOIN\\b");
  private static final Pattern STRAIGHT_JOIN_KW =
      Pattern.compile("(?i)\\bSTRAIGHT_JOIN\\b");

  /** MySQL STRAIGHT_JOIN（Calcite 解析器不支持）：语句级 {@code SELECT STRAIGHT_JOIN}
   * 为查询提示 → 剥离提示字；连接级 STRAIGHT_JOIN ≡ INNER JOIN（连接顺序提示，
   * 语义不变）→ JOIN。字面量/注释内的伪命中不动。 */
  private static String preprocessStraightJoin(String sql) {
    if (!STRAIGHT_JOIN_KW.matcher(sql).find()) {
      return sql;
    }
    String out = replaceLiveMatches(sql, STRAIGHT_SELECT, "SELECT");
    return replaceLiveMatches(out, STRAIGHT_JOIN_KW, "JOIN");
  }

  /** 活字符命中的正则替换为固定串（字面量/注释内不动）。 */
  private static String replaceLiveMatches(String sql, Pattern pattern, String replacement) {
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
        continue;   // 字面量/注释内的伪命中
      }
      out.append(sql, pos, m.start()).append(replacement);
      pos = m.end();
    }
    if (pos == 0) {
      return sql;
    }
    return out.append(sql.substring(pos)).toString();
  }

  // ---------- GROUPS 窗口帧等价改写（SQL:2011） ----------

  /** GROUPS 帧（SQL:2011，Calcite 解析器不支持该关键字）→ 等价 DENSE_RANK 改写：
   * GROUPS 按「对等组（peer group）」计数，恰为 DENSE_RANK 编号上的 RANGE 数值
   * 偏移——把 FROM 包一层派生表追加 {@code DENSE_RANK() OVER ([PARTITION BY ..]
   * ORDER BY 原键) AS crossdb_grp}，窗口改为 {@code ORDER BY crossdb_grp RANGE
   * 同边界}（grp 沿原键序递增，RANGE 偏移即组偏移，数学等价；EXCLUDE 子句原样
   * 兼容）。边界：平铺单表（含派生表）SELECT + WHERE；所有 GROUPS 窗口须共用
   * 同一 PARTITION/ORDER 签名（排序键为裸列，可带限定符）；顶层集合操作 /
   * GROUP BY / HAVING / DISTINCT / 多表 FROM 不改写。无法安全改写时原样保留
   * 交解析器报真实错误。 */
  private static String preprocessGroupsFrame(String sql) {
    boolean[] live = liveMask(sql);
    List<int[]> occ = new ArrayList<>();
    collectLiveWord(sql, live, "GROUPS", occ);
    if (occ.isEmpty()) {
      return sql;
    }
    if (hasTopLevelAny(sql, live, sql.length(), "UNION", "INTERSECT", "EXCEPT",
        "GROUP", "HAVING")) {
      return sql;
    }
    // 解析每处 GROUPS 窗口：定位其 ORDER BY 与可选 PARTITION BY，校验签名一致
    List<int[]> spans = new ArrayList<>();   // {orderStart, groupsEnd}，倒序替换
    List<String> keys = null;
    List<String> dirs = null;
    String partition = null;
    for (int[] g : occ) {
      int orderStart = lastKeywordBefore(sql, live, g[0], "ORDER BY");
      if (orderStart < 0) {
        return sql;
      }
      int keysStart = orderStart + "ORDER BY".length();
      List<String> ks = new ArrayList<>();
      List<String> ds = new ArrayList<>();
      if (!parseOrderKeys(sql.substring(keysStart, g[0]).trim(), ks, ds)) {
        return sql;
      }
      // 窗口内 ORDER BY 之前只允许 PARTITION BY 子句
      int over = lastKeywordBefore(sql, live, orderStart, "OVER");
      if (over < 0 || skipBlank(sql, live, over + 4) >= sql.length()
          || sql.charAt(skipBlank(sql, live, over + 4)) != '(') {
        return sql;
      }
      int specStart = skipBlank(sql, live, over + 4) + 1;
      String before = sql.substring(specStart, orderStart).trim();
      String part;
      if (before.isEmpty()) {
        part = "";
      } else if (before.toUpperCase().matches("(?s)^PARTITION\\s+BY\\s+.*")) {
        part = before.replaceFirst("(?is)^PARTITION\\s+BY\\s+", "").trim();
      } else {
        return sql;
      }
      if (keys == null) {
        keys = ks;
        dirs = ds;
        partition = part;
      } else if (!String.join(",", keys).equalsIgnoreCase(String.join(",", ks))
          || !String.join(",", dirs).equalsIgnoreCase(String.join(",", ds))
          || !partition.equalsIgnoreCase(part)) {
        return sql;   // 多窗口签名不一致：一个 grp 列无法共享
      }
      spans.add(new int[]{orderStart, g[1]});
    }
    // 倒序替换各窗口：ORDER BY <keys> GROUPS → ORDER BY crossdb_grp RANGE
    for (int s = spans.size() - 1; s >= 0; s--) {
      int[] span = spans.get(s);
      sql = sql.substring(0, span[0]) + "ORDER BY crossdb_grp RANGE"
          + sql.substring(span[1]);
    }
    // 替换后重扫 FROM 与单表 FROM 项（替换改变后续位置）
    live = liveMask(sql);
    if (!sql.trim().toUpperCase().startsWith("SELECT")
        || sql.trim().toUpperCase().matches("(?is)^SELECT\\s+DISTINCT\\b.*")) {
      return sql;
    }
    int fromIdx = topLevelFrom(sql, live, sql.length());
    if (fromIdx < 0) {
      return sql;
    }
    int n = sql.length();
    int i = skipBlank(sql, live, fromIdx + 4);
    if (i >= n || !live[i]) {
      return sql;
    }
    if (sql.charAt(i) == '(') {
      int end = matchParen(sql, live, i);
      if (end < 0) {
        return sql;
      }
      i = end;
    } else {
      if (!isIdentStart(sql.charAt(i))) {
        return sql;
      }
      while (i < n && live[i] && (isIdentPart(sql.charAt(i)) || sql.charAt(i) == '.')) {
        i++;
      }
    }
    String alias = null;
    int a = skipBlank(sql, live, i);
    if (a < n && live[a] && isIdentStart(sql.charAt(a))) {
      String w = wordAt(sql, a).toUpperCase();
      if (w.equals("AS")) {
        int b = skipBlank(sql, live, a + 2);
        if (b < n && live[b] && isIdentStart(sql.charAt(b))
            && !CLAUSE_STOPPERS.contains(wordAt(sql, b).toUpperCase())) {
          alias = wordAt(sql, b);
          i = b + alias.length();
        } else {
          return sql;
        }
      } else if (!CLAUSE_STOPPERS.contains(w)) {
        alias = wordAt(sql, a);
        i = a + alias.length();
      }
    }
    int itemEnd = skipBlank(sql, live, i);
    if (itemEnd < n && live[itemEnd]) {
      char c = sql.charAt(itemEnd);
      if (c == ',' || c == '(' || !isIdentStart(c)
          || !(CLAUSE_STOPPERS.contains(wordAt(sql, itemEnd).toUpperCase())
              || wordAt(sql, itemEnd).equalsIgnoreCase("WINDOW"))) {
        return sql;   // JOIN 家族 / 多表 / 未预期形态
      }
    }
    String fromItem = sql.substring(fromIdx + 4, Math.min(itemEnd, n)).trim();
    if (alias == null) {
      if (fromItem.startsWith("(")) {
        return sql;   // 无别名派生表无法在包裹层限定列
      }
      alias = fromItem.substring(fromItem.lastIndexOf('.') + 1).trim();
    }
    String orderList = "";
    for (int k = 0; k < keys.size(); k++) {
      orderList += (k > 0 ? ", " : "") + keys.get(k) + " " + dirs.get(k);
    }
    String denseRank = "DENSE_RANK() OVER ("
        + (partition == null || partition.isEmpty() ? ""
            : "PARTITION BY " + partition + " ")
        + "ORDER BY " + orderList + ")";
    String wrapped = "(SELECT " + alias + ".*, " + denseRank
        + " AS crossdb_grp FROM " + fromItem + ") " + alias;
    return sql.substring(0, fromIdx + 4) + " " + wrapped
        + (itemEnd < n ? " " + sql.substring(itemEnd) : "");
  }

  /** 收集活字符词 word 的全部出现 {start, end}。 */
  private static void collectLiveWord(String sql, boolean[] live, String word,
      List<int[]> out) {
    int n = sql.length();
    for (int i = 0; i < n; i++) {
      if ((wordEquals(sql, i, word) || wordEquals(sql, i, word.toLowerCase()))
          && isWordStart(sql, live, i) && isWordEnd(sql, live, i + word.length())
          && spanLive(live, i, i + word.length())) {
        out.add(new int[]{i, i + word.length()});
        i += word.length() - 1;
      }
    }
  }

  /** limit 前最后一个词关键字（如 "ORDER BY"/"OVER"）的起点；无则 -1。 */
  private static int lastKeywordBefore(String sql, boolean[] live, int limit,
      String keyword) {
    String[] words = keyword.toUpperCase().split("\\s+");
    int best = -1;
    int n = Math.min(limit, sql.length());
    for (int i = 0; i < n; i++) {
      if (!live[i] || !isIdentStart(sql.charAt(i))) {
        continue;
      }
      if (keywordAt(sql, live, i, keyword)) {
        best = i;
        i += words[0].length() - 1;
      } else {
        String w = wordAt(sql, i);
        i += w.length() - 1;
      }
    }
    return best;
  }

  // ---------- 位运算 / DIV 操作符文本改写（MySQL / PostgreSQL） ----------

  private static final Pattern FETCH_PERCENT = Pattern.compile(
      "(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+(?:\\.\\d+)?)\\s+PERCENT\\s+ROWS?\\s+ONLY\\s*(;?)\\s*$");

  /** FETCH FIRST n PERCENT ROWS ONLY（SQL:2008 扩展，Calcite 解析器不支持）→
   * 等价改写：按标准语义取「前 CEILING(n% × 总行数) 行」——
   * {@code SELECT <原输出列> FROM (SELECT <原清单>[, 缺失排序键],
   * ROW_NUMBER() OVER (ORDER BY 原键) AS crossdb_pct_rn, COUNT(*) OVER () AS
   * crossdb_pct_cnt FROM 原FROM..) WHERE crossdb_pct_rn <= CEILING(cnt * n / 100.0)
   * ORDER BY crossdb_pct_rn}（行号序即键序，无列泄漏；无 ORDER BY 时行选择任意，
   * 与标准一致）。边界：仅语句级平铺 SELECT（顶层集合操作/DISTINCT/OFFSET 不改
   * 写）；排序键须为（可带限定符的）裸列；输出列名可推导（* / t.* 不支持）。
   * 无法安全改写时原样保留交解析器报真实错误。 */
  private static String preprocessFetchPercent(String sql) {
    Matcher m = FETCH_PERCENT.matcher(sql);
    if (!m.find()) {
      return sql;
    }
    boolean[] live = liveMask(sql);
    int fetchStart = m.start();
    int[] orderBy = lastTopLevelOrderBy(sql, live, fetchStart);
    List<String> keys = new ArrayList<>();
    List<String> dirs = new ArrayList<>();
    boolean hasOrder = orderBy != null;
    if (hasOrder && !parseOrderKeys(sql.substring(orderBy[1], fetchStart).trim(),
        keys, dirs)) {
      return sql;
    }
    String core = sql.substring(0, hasOrder ? orderBy[0] : fetchStart);
    if (!hasOrder && hasTopLevelAny(sql, live, core.length(),
        "ORDER", "OFFSET", "UNION", "INTERSECT", "EXCEPT")) {
      return sql;   // ORDER BY 存在但夹有 OFFSET 等，或顶层集合操作
    }
    int fromIdx = topLevelFrom(sql, live, core.length());
    if (fromIdx < 0) {
      return sql;
    }
    String head = sql.substring(0, fromIdx);
    if (head.matches("(?is)^\\s*SELECT\\s+DISTINCT\\b.*")
        || head.matches("(?is)^\\s*SELECT\\s+ALL\\b.*")) {
      return sql;   // DISTINCT 与窗口函数组合的命名推导复杂，不改写
    }
    java.util.regex.Matcher selM = java.util.regex.Pattern.compile(
        "(?is)^\\s*SELECT\\s+").matcher(head);
    if (!selM.find()) {
      return sql;
    }
    int listStart = selM.end();
    String listText = head.substring(listStart).stripTrailing();
    String fromTail = sql.substring(fromIdx, core.length()).trim();
    List<String> items = splitTopLevel(listText, live, listStart);
    if (items == null) {
      return sql;
    }
    List<String> names = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      String name = outputColumnName(items.get(i), i);
      if (name == null) {
        return sql;   // * / t.* 等无法文本推导输出列名
      }
      names.add(name);
    }
    List<String> appendedKeys = new ArrayList<>();
    for (String key : keys) {
      boolean inList = names.stream().anyMatch(n -> n.equalsIgnoreCase(key));
      if (!inList) {
        appendedKeys.add(key);
      }
    }
    StringBuilder inner = new StringBuilder("SELECT ").append(listText);
    for (String key : appendedKeys) {
      inner.append(", ").append(key);
    }
    inner.append(", ROW_NUMBER() OVER (");
    if (!keys.isEmpty()) {
      inner.append("ORDER BY ");
    }
    for (int i = 0; i < keys.size(); i++) {
      inner.append(i > 0 ? ", " : "").append(keys.get(i)).append(' ').append(dirs.get(i));
    }
    inner.append(") AS crossdb_pct_rn, COUNT(*) OVER () AS crossdb_pct_cnt ")
        .append(fromTail);
    String where = "crossdb_pct_rn <= CEILING(crossdb_pct_cnt * " + m.group(1)
        + " / 100.0)";
    String suffix = m.group(2);
    return "SELECT " + String.join(", ", names) + " FROM (" + inner
        + ") crossdb_pct_row WHERE " + where
        + (hasOrder ? " ORDER BY crossdb_pct_rn" : "") + suffix;
  }

  /** limit 前是否存在深度 0 的指定关键字之一。 */
  private static boolean hasTopLevelAny(String sql, boolean[] live, int limit,
      String... words) {
    int depth = 0;
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
        String upper = wordAt(sql, i).toUpperCase();
        for (String w : words) {
          if (upper.equals(w)) {
            return true;
          }
        }
        i += upper.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return false;
  }

  /** limit 前最后一个深度 0 的 FROM 关键字位置（-1 表示无）。 */
  private static int topLevelFrom(String sql, boolean[] live, int limit) {
    int depth = 0;
    int found = -1;
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
        String upper = wordAt(sql, i).toUpperCase();
        if (upper.equals("FROM")) {
          found = i;
        }
        i += upper.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return found;
  }

  /** 深度 0 逗号切分 select 清单；baseOffset 为片段在原语句中的起点偏移
   * （用于复用原语句掩码）。返回各项（保留原文空白），无法切分返回 null。 */
  private static List<String> splitTopLevel(String listText, boolean[] live, int baseOffset) {
    if (listText.isEmpty()) {
      return null;
    }
    List<String> items = new ArrayList<>();
    int depth = 0;
    int start = 0;
    int n = listText.length();
    for (int i = 0; i < n; i++) {
      int abs = baseOffset + i;
      boolean liveHere = abs >= 0 && abs < live.length && live[abs];
      if (!liveHere) {
        continue;
      }
      char c = listText.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        items.add(listText.substring(start, i).trim());
        start = i + 1;
      }
    }
    items.add(listText.substring(start).trim());
    return items.stream().noneMatch(String::isEmpty) ? items : null;
  }

  /** select 清单项 → 输出列名：显式别名 > 裸（点分）列尾段 > EXPR$索引
   * （与 Calcite 校验器对无别名表达式项的命名一致）；* / t.* 与无法识别形态
   * 返回 null（调用方放弃改写）。 */
  private static String outputColumnName(String item, int index) {
    if (item.isEmpty() || item.equals("*") || item.endsWith(".*")) {
      return null;
    }
    java.util.regex.Matcher as = java.util.regex.Pattern.compile(
        "(?is)^(.+?)\\s+AS\\s+([a-zA-Z_][a-zA-Z0-9_$]*)$").matcher(item);
    if (as.matches()) {
      return as.group(2);
    }
    if (item.matches("(?i)^[a-zA-Z_][a-zA-Z0-9_$]*(\\s*\\.\\s*[a-zA-Z_][a-zA-Z0-9_$]*)*$")) {
      return item.substring(item.lastIndexOf('.') + 1).trim();
    }
    return "EXPR$" + index;
  }

  // 位运算主体见下方方法群

  /** 表达式边界关键字：作为操作数原子扫描的硬边界出现（出现在操作数位置即判定
   * 无法安全改写，跳过该处改写交由解析器报真实错误）。 */
  private static final java.util.Set<String> EXPR_KEYWORDS = java.util.Set.of(
      "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "BY", "LIMIT", "OFFSET",
      "FETCH", "FIRST", "NEXT", "ROWS", "ROW", "ONLY", "WITH", "RECURSIVE", "UNION",
      "INTERSECT", "EXCEPT", "MINUS", "ALL", "DISTINCT", "AS", "ON", "USING", "JOIN",
      "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "APPLY", "AND",
      "OR", "NOT", "XOR", "IS", "NULL", "LIKE", "ILIKE", "RLIKE", "REGEXP", "IN",
      "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
      "NULLS", "OVER", "PARTITION", "WINDOW", "VALUES", "PRECEDING", "FOLLOWING",
      "CURRENT", "UNBOUNDED", "TIES", "PERCENT", "EXCLUDE", "FILTER", "RESPECT",
      "IGNORE", "CAST", "INTERVAL", "DATE", "TIME", "TIMESTAMP");

  private static final java.util.Set<String> MULDIV_CONN =
      java.util.Set.of("*", "/", "%", "MOD");
  private static final java.util.Set<String> ARITH_CONN =
      java.util.Set.of("*", "/", "%", "MOD", "+", "-");

  /** 位运算操作符 & | ^ ~ << >> 与整除 DIV（MySQL/PostgreSQL；Calcite 解析器不支持）
   * → 等价本地 UDF 函数调用。这些 token 在本解析器不存在其他合法用途，语句中
   * 出现（活字符）即必为位运算/整除；按绑定优先级从紧到松分轮改写：~（一元）→
   * DIV（乘除级，操作数为带符号单原子）→ << >>（操作数为乘除链）→ & → ^ → |
   * （操作数为算术链）。每轮最左优先、改写后重扫，天然左结合。{@code ||} 串接与
   * {@code &&} 逻辑与不参与匹配；操作数扫描遇表达式边界关键字/无法识别形态即
   * 放弃该处改写（保留原样交解析器报错）。
   *
   * <p>已知边界：科学计数法字面量作为左操作数时（如 {@code 1e-3 & x}）左向扫描
   * 会误拆指数符号，该形态不支持（罕见）；CASE…END 直接作操作数不支持（加括号
   * 即可）。字面量/注释内的伪命中不动。 */
  private static String preprocessBitwiseDiv(String sql) {
    boolean[] live = liveMask(sql);
    if (!hasBitwiseDivOp(sql, live)) {
      return sql;
    }
    sql = rewriteBitNot(sql);
    sql = rewriteBinaryOp(sql, "DIV", "CROSSDB_IDIV", java.util.Set.of());
    sql = rewriteBinaryOp(sql, "<<", "CROSSDB_SHL", MULDIV_CONN);
    sql = rewriteBinaryOp(sql, ">>", "CROSSDB_SHR", MULDIV_CONN);
    sql = rewriteBinaryOp(sql, "&", "CROSSDB_BITAND", ARITH_CONN);
    sql = rewriteBinaryOp(sql, "^", "CROSSDB_BITXOR", ARITH_CONN);
    sql = rewriteBinaryOp(sql, "|", "CROSSDB_BITOR", ARITH_CONN);
    return sql;
  }

  /** 语句中是否存在活字符的位运算/DIV 操作符（|| 与 && 不算）。 */
  private static boolean hasBitwiseDivOp(String sql, boolean[] live) {
    int n = sql.length();
    for (int i = 0; i < n; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '^' || c == '~' || c == '&') {
        if (c != '&' || !isDoubled(sql, live, i, '&')) {
          return true;
        }
      } else if (c == '|') {
        if (!isDoubled(sql, live, i, '|')) {
          return true;
        }
      } else if (c == '<' && i + 1 < n && live[i + 1] && sql.charAt(i + 1) == '<') {
        return true;
      } else if (c == '>' && i + 1 < n && live[i + 1] && sql.charAt(i + 1) == '>') {
        return true;
      } else if ((c == 'd' || c == 'D') && wordEquals(sql, i, "DIV")
          && isWordStart(sql, live, i) && isWordEnd(sql, live, i + 3)) {
        return true;
      }
    }
    return false;
  }

  /** i 处字符与相邻同字符构成双字符 token（|| / &&），不能按位运算处理。 */
  private static boolean isDoubled(String sql, boolean[] live, int i, char c) {
    return (i + 1 < sql.length() && live[i + 1] && sql.charAt(i + 1) == c)
        || (i > 0 && live[i - 1] && sql.charAt(i - 1) == c);
  }

  private static boolean wordEquals(String sql, int i, String word) {
    return sql.regionMatches(true, i, word, 0, word.length());
  }

  private static boolean isWordStart(String sql, boolean[] live, int i) {
    return i == 0 || !live[i - 1] || !isIdentPart(sql.charAt(i - 1));
  }

  private static boolean isWordEnd(String sql, boolean[] live, int end) {
    return end >= sql.length() || !live[end] || !isIdentPart(sql.charAt(end));
  }

  /** 一元按位取反 {@code ~x} → CROSSDB_BITNOT(x)：操作数为（可带符号的）单原子。 */
  private static String rewriteBitNot(String sql) {
    int searchFrom = 0;
    while (true) {
      boolean[] live = liveMask(sql);
      int op = -1;
      for (int i = searchFrom; i < sql.length(); i++) {
        if (live[i] && sql.charAt(i) == '~') {
          op = i;
          break;
        }
      }
      if (op < 0) {
        return sql;
      }
      int atomStart = skipBlank(sql, live, op + 1);
      int atomEnd = atomRight(sql, live, atomStart, true);
      if (atomEnd < 0) {
        searchFrom = op + 1;
        continue;
      }
      sql = sql.substring(0, op) + "CROSSDB_BITNOT("
          + sql.substring(atomStart, atomEnd) + ")" + sql.substring(atomEnd);
      searchFrom = 0;
    }
  }

  /** 二元位运算/整除一轮改写：最左优先，操作数链按 connectors 扩展；改写一处后
   * 从头重扫（左结合）。op 为 "DIV" 时按词匹配，否则按字符匹配（避开 || / &&
   * 与 << >> 的单字符误配：按 op 长度精确消费）。 */
  private static String rewriteBinaryOp(String sql, String op, String fn,
      java.util.Set<String> connectors) {
    boolean word = op.equals("DIV");
    int searchFrom = 0;
    while (true) {
      boolean[] live = liveMask(sql);
      int opStart = -1;
      int opEnd = -1;
      for (int i = searchFrom; i < sql.length(); i++) {
        if (!live[i]) {
          continue;
        }
        char c = sql.charAt(i);
        if (word) {
          if ((c == 'd' || c == 'D') && wordEquals(sql, i, op)
              && isWordStart(sql, live, i) && isWordEnd(sql, live, i + op.length())) {
            opStart = i;
            opEnd = i + op.length();
            break;
          }
        } else if (c == op.charAt(0) && sql.regionMatches(i, op, 0, op.length())
            && (op.length() == 2 || !isDoubled(sql, live, i, c))
            && (i + op.length() >= sql.length() || live[i + op.length()])) {
          // 双字符操作符（<< >>）按整体匹配即可（<= 不会误配）；单字符操作符
          // 须避开 || / && 双字符 token
          opStart = i;
          opEnd = i + op.length();
          break;
        }
      }
      if (opStart < 0) {
        return sql;
      }
      int ls = scanOperandLeft(sql, live, opStart, connectors);
      int re = ls < 0 ? -1 : scanOperandRight(sql, live, opEnd, connectors);
      if (re < 0) {
        searchFrom = opEnd;
        continue;
      }
      sql = sql.substring(0, ls) + fn + "(" + sql.substring(ls, opStart).trim() + ", "
          + sql.substring(opEnd, re).trim() + ")" + sql.substring(re);
      searchFrom = 0;
    }
  }

  /** signPos 处的 +/- 是否为一元符号：其前（跳过空白）不是原子结尾（标识符/
   * 数字/右括号/字面量）即为符号，否则为二元操作符。 */
  private static boolean signIsUnary(String sql, boolean[] live, int signPos) {
    int p = skipBlankBack(sql, live, signPos - 1);
    return p < 0 || !live[p] || !isAtomEnder(sql.charAt(p));
  }

  /** 目标操作符左操作数起点：单原子 + 向左按连接符扩展（+/- 需原子邻接判定
   * 二元/符号）。返回起点下标，失败 -1。 */
  private static int scanOperandLeft(String sql, boolean[] live, int opStart,
      java.util.Set<String> connectors) {
    int i = skipBlankBack(sql, live, opStart - 1);
    int atomStart = atomLeft(sql, live, i);
    if (atomStart < 0) {
      return -1;
    }
    while (true) {
      int j = skipBlankBack(sql, live, atomStart - 1);
      int[] conn = connectorAtLeft(sql, live, j, connectors);
      if (conn == null) {
        return atomStart;
      }
      int k = skipBlankBack(sql, live, conn[0] - 1);
      int prev = atomLeft(sql, live, k);
      if (prev < 0) {
        // 连接符左侧不是原子：若为一元符号则并入操作数，否则到此为止
        if (conn[1] == conn[0] + 1 && k >= 0 && k < sql.length() && live[k]
            && (sql.charAt(k) == '-' || sql.charAt(k) == '+')
            && signIsUnary(sql, live, k)) {
          return k;
        }
        return atomStart;
      }
      atomStart = prev;
    }
  }

  /** 目标操作符右操作数终点：单原子 + 向右按连接符扩展。返回终点下标（排他），
   * 失败 -1。 */
  private static int scanOperandRight(String sql, boolean[] live, int opEnd,
      java.util.Set<String> connectors) {
    int i = skipBlank(sql, live, opEnd);
    int atomEnd = atomRight(sql, live, i, true);
    if (atomEnd < 0) {
      return -1;
    }
    while (true) {
      int j = skipBlank(sql, live, atomEnd);
      int[] conn = connectorAtRight(sql, live, j, connectors);
      if (conn == null) {
        return atomEnd;
      }
      int k = skipBlank(sql, live, conn[1]);
      int next = atomRight(sql, live, k, true);
      if (next < 0) {
        return atomEnd;
      }
      atomEnd = next;
    }
  }

  /** j 处（活字符）是否为连接符：返回 {起点, 终点}，非连接符返回 null。 */
  private static int[] connectorAtRight(String sql, boolean[] live, int j,
      java.util.Set<String> connectors) {
    if (j >= sql.length() || !live[j]) {
      return null;
    }
    char c = sql.charAt(j);
    String one = String.valueOf(c);
    if ((c == '*' || c == '/' || c == '%' || c == '+' || c == '-')
        && connectors.contains(one)) {
      return new int[]{j, j + 1};
    }
    if ((c == 'm' || c == 'M') && connectors.contains("MOD")
        && wordEquals(sql, j, "MOD") && isWordStart(sql, live, j)
        && isWordEnd(sql, live, j + 3)) {
      return new int[]{j, j + 3};
    }
    return null;
  }

  /** j 处（活字符，含 j）向左的连接符：返回 {起点, 终点+1}。 */
  private static int[] connectorAtLeft(String sql, boolean[] live, int j,
      java.util.Set<String> connectors) {
    if (j < 0 || !live[j]) {
      return null;
    }
    char c = sql.charAt(j);
    String one = String.valueOf(c);
    if ((c == '*' || c == '/' || c == '%' || c == '+' || c == '-')
        && connectors.contains(one)) {
      return new int[]{j, j + 1};
    }
    if ((c == 'd' || c == 'D') && connectors.contains("MOD")
        && j >= 2 && wordEquals(sql, j - 2, "MOD")
        && isWordStart(sql, live, j - 2) && j + 1 <= sql.length()
        && isWordEnd(sql, live, j + 1)) {
      return new int[]{j - 2, j + 1};
    }
    return null;
  }

  /** 自 i（活字符）起向右扫一个原子：字面量（死区）/ 括号组 / 函数调用 /
   * 点分标识符 / 数值字面量（含指数）；allowSign 允许前导 +/- 符号。返回原子
   * 终点（排他），无法识别返回 -1。表达式边界关键字不是原子。 */
  private static int atomRight(String sql, boolean[] live, int i, boolean allowSign) {
    int n = sql.length();
    if (i >= n) {
      return -1;
    }
    if (!live[i]) {
      while (i < n && !live[i]) {
        i++;
      }
      return i;   // 字面量整体为一个原子
    }
    char c = sql.charAt(i);
    if (allowSign && (c == '-' || c == '+')) {
      return atomRight(sql, live, skipBlank(sql, live, i + 1), false);
    }
    if (c == '(') {
      int end = matchParen(sql, live, i);
      return end < 0 ? -1 : end;
    }
    if (Character.isDigit(c) || c == '.') {
      int j = i + 1;
      while (j < n && live[j] && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) {
        j++;
      }
      if (j < n && live[j] && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
        int k = j + 1;
        if (k < n && live[k] && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
          k++;
        }
        if (k < n && live[k] && Character.isDigit(sql.charAt(k))) {
          while (k < n && live[k] && Character.isDigit(sql.charAt(k))) {
            k++;
          }
          j = k;
        }
      }
      return j;
    }
    if (isIdentStart(c)) {
      int j = i + 1;
      while (j < n && live[j] && (isIdentPart(sql.charAt(j)) || sql.charAt(j) == '.')) {
        j++;
      }
      String word = wordAt(sql, i).toUpperCase();
      if (word.equals("TRUE") || word.equals("FALSE")) {
        return j;
      }
      if (EXPR_KEYWORDS.contains(word)) {
        return -1;
      }
      int k = skipBlank(sql, live, j);
      if (k < n && live[k] && sql.charAt(k) == '(') {
        int end = matchParen(sql, live, k);
        return end < 0 ? -1 : end;
      }
      return j;
    }
    return -1;
  }

  /** 自 i（活字符）起向左扫一个原子（i 为原子最后一个字符）：返回原子起点，
   * 无法识别返回 -1。 */
  private static int atomLeft(String sql, boolean[] live, int i) {
    if (i < 0) {
      return -1;
    }
    if (!live[i]) {
      while (i >= 0 && !live[i]) {
        i--;
      }
      return i + 1;   // 字面量整体为一个原子
    }
    char c = sql.charAt(i);
    if (c == ')') {
      int start = matchParenBack(sql, live, i);
      if (start < 0) {
        return -1;
      }
      int before = skipBlankBack(sql, live, start - 1);
      // 函数调用：F( ... ) —— 括号前紧跟标识符则并入原子
      if (before >= 0 && live[before] && isIdentPart(sql.charAt(before))) {
        int j = before;
        while (j >= 0 && live[j] && (isIdentPart(sql.charAt(j)) || sql.charAt(j) == '.')) {
          j--;
        }
        String word = wordAt(sql, j + 1).toUpperCase();
        if (EXPR_KEYWORDS.contains(word)) {
          return start;   // 关键字后随括号（如 IN (…)）不是函数调用
        }
        return j + 1;
      }
      // 前置一元符号（如 -(a+b)）：并入括号原子
      if (before >= 0 && live[before]
          && (sql.charAt(before) == '-' || sql.charAt(before) == '+')
          && signIsUnary(sql, live, before)) {
        return before;
      }
      return start;
    }
    if (isIdentPart(c) || c == '.') {
      int j = i;
      while (j >= 0 && live[j] && (isIdentPart(sql.charAt(j)) || sql.charAt(j) == '.')) {
        j--;
      }
      int start = j + 1;
      String word = wordAt(sql, start).toUpperCase();
      if (word.equals("TRUE") || word.equals("FALSE")) {
        return start;
      }
      if (EXPR_KEYWORDS.contains(word)) {
        return -1;
      }
      // 前置一元符号：符号本身之前不是原子结尾（二元操作数）即为符号
      int before = skipBlankBack(sql, live, start - 1);
      if (before >= 0 && live[before]
          && (sql.charAt(before) == '-' || sql.charAt(before) == '+')
          && signIsUnary(sql, live, before)) {
        return before;
      }
      return start;
    }
    return -1;
  }

  /** 括号反向匹配：'(' 的位置，不匹配返回 -1。 */
  private static int matchParenBack(String sql, boolean[] live, int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == ')') {
        depth++;
      } else if (c == '(' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /** 自 start 起向左跳过空白，返回最后一个活字符下标；无则返回 -1。 */
  private static int skipBlankBack(String sql, boolean[] live, int start) {
    int i = start;
    while (i >= 0 && (!live[i] || Character.isWhitespace(sql.charAt(i)))) {
      i--;
    }
    return i;
  }

  /** 字符是否可作为原子的结尾（用于一元符号与二元操作符的消歧）。 */
  private static boolean isAtomEnder(char c) {
    return isIdentPart(c) || c == ')' || c == '.';
  }

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

  /** 改写入口：表达式级 shuttle 改写 + USING / 别名列清单的 FROM 级改写。
   * hints 为已注册源库的列类型目录（可为 null，等价空目录）。 */
  static SqlNode rewrite(SqlNode parsed, SchemaPlus root, JavaTypeFactory typeFactory,
      ColumnHints hints) {
    ColumnHints h = hints == null ? ColumnHints.EMPTY : hints;
    parsed = parsed.accept(rewriter(h));
    return new FromRewriter(root, typeFactory).expand(parsed);
  }

  private static SqlShuttle rewriter(ColumnHints hints) {
    return new SqlShuttle() {
      @Override public SqlNode visit(SqlCall call) {
        if (call.getKind().belongsTo(SqlKind.DDL) || call.getKind().belongsTo(SqlKind.DML)) {
          // DDL/DML 仅会被只读硬化拒绝，且其专用节点（SqlCreate 等）不能按
          // 通用 createCall 重建（shuttle 深入会崩），直接原样返回
          return call;
        }
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
        if (upper.equals("NVL") && call.getOperandList().size() == 2) {
          // Oracle NVL(a, b) ≡ COALESCE(a, b)（NULL 字面量首参时 UDF 的 ARG0
          // 返回类型推导为 Void 会在运行期抛类型转换异常，改写挂标准 COALESCE）
          return new SqlBasicCall(SqlStdOperatorTable.COALESCE, call.getOperandList(),
              call.getParserPosition());
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
        if (upper.equals("LOG")) {
          return rewriteLog(call);
        }
        if (upper.equals("SPACE") && call.getOperandList().size() == 1) {
          // MySQL SPACE(n) → REPEAT(' ', n)（复用本地 UDF）
          SqlParserPos pos = call.getParserPosition();
          return new SqlBasicCall(REPEAT_FN, List.of(
              SqlLiteral.createCharString(" ", pos), call.getOperandList().get(0)), pos);
        }
        if (upper.equals("CHAR") && call.getOperandList().size() == 1) {
          // MySQL CHAR(n) → CHR(n)（复用本地 UDF）
          return new SqlBasicCall(CHR_FN, call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("STRCMP")) {
          return rewriteStrcmp(call);
        }
        if ((upper.equals("LEFT") || upper.equals("RIGHT"))
            && call.getOperandList().size() == 2) {
          // MySQL/SQL Server LEFT/RIGHT(s, n) → 本地 UDF
          return new SqlBasicCall(upper.equals("LEFT") ? LEFT_FN : RIGHT_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if (upper.equals("LOCATE")
            && (call.getOperandList().size() == 2 || call.getOperandList().size() == 3)) {
          // MySQL/PostgreSQL LOCATE(substr, str[, start]) → 本地 UDF
          return new SqlBasicCall(
              call.getOperandList().size() == 2 ? LOCATE2_FN : LOCATE3_FN,
              call.getOperandList(), call.getParserPosition());
        }
        if ((call.getOperator() == SqlStdOperatorTable.MOD || upper.equals("MOD"))
            && modFloatInvolved(call, hints)) {
          // MOD 浮点语义修正：仅操作数含浮点（字面量或目录已知浮点列）时挂本地
          // 实现（Java % 语义）；整数 MOD 保持原生路径（可下推源库）。
          // MOD(a,b) 函数形与 a MOD b / a % b 操作符形同名同实例，按名兜底匹配
          return new SqlBasicCall(MOD_FN, call.getOperandList(), call.getParserPosition());
        }
        if (call.getKind() == SqlKind.CAST) {
          return rewriteCastBoolean(call, hints);
        }
        if (call.getKind() == SqlKind.PLUS || call.getKind() == SqlKind.MINUS) {
          return rewriteDateArith(call, hints);
        }
        if (call.getKind() == SqlKind.MATCH_RECOGNIZE) {
          // SQL:2011 标准默认 AFTER MATCH SKIP PAST LAST ROW——Calcite 解析器/
          // 转换器默认给 SKIP TO NEXT ROW（与标准及 Oracle/PostgreSQL 不一致），
          // 未显式指定 AFTER 时补上标准默认字面量
          org.apache.calcite.sql.SqlMatchRecognize mr =
              (org.apache.calcite.sql.SqlMatchRecognize) call;
          if (mr.getAfter() == null) {
            mr.setOperand(org.apache.calcite.sql.SqlMatchRecognize.OPERAND_AFTER,
                SqlLiteral.createSymbol(
                    org.apache.calcite.sql.SqlMatchRecognize.AfterOption.SKIP_PAST_LAST_ROW,
                    mr.getParserPosition()));
          }
          return call;
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

  /** LOG(x)（PostgreSQL/MySQL 单参 = 自然对数，Calcite 仅注册 LN）→ LN(x)；
   * LOG(b, x)（对数底 b）→ LN(x) / LN(b)。其余元数保留原样交校验器报错。 */
  private static SqlNode rewriteLog(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    SqlParserPos pos = call.getParserPosition();
    if (ops.size() == 1) {
      return new SqlBasicCall(SqlStdOperatorTable.LN, ops, pos);
    }
    if (ops.size() == 2) {
      return new SqlBasicCall(SqlStdOperatorTable.DIVIDE, List.of(
          new SqlBasicCall(SqlStdOperatorTable.LN, List.of(ops.get(1)), pos),
          new SqlBasicCall(SqlStdOperatorTable.LN, List.of(ops.get(0)), pos)), pos);
    }
    return call;
  }

  /** STRCMP(a, b)（MySQL）→ CASE：任一 NULL 得 NULL；a=b 得 0、a<b 得 -1、
   * 其余（a>b）得 1。比较语义由校验器按操作数类型推导。 */
  private static SqlNode rewriteStrcmp(SqlCall call) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    SqlNode a = ops.get(0);
    SqlNode b = ops.get(1);
    SqlParserPos pos = call.getParserPosition();
    SqlNodeList whens = new SqlNodeList(pos);
    whens.add(new SqlBasicCall(SqlStdOperatorTable.OR, List.of(
        new SqlBasicCall(SqlStdOperatorTable.IS_NULL, List.of(a), pos),
        new SqlBasicCall(SqlStdOperatorTable.IS_NULL, List.of(b), pos)), pos));
    whens.add(new SqlBasicCall(SqlStdOperatorTable.EQUALS, List.of(a, b), pos));
    whens.add(new SqlBasicCall(SqlStdOperatorTable.LESS_THAN, List.of(a, b), pos));
    SqlNodeList thens = new SqlNodeList(pos);
    thens.add(SqlLiteral.createNull(pos));
    thens.add(SqlLiteral.createExactNumeric("0", pos));
    thens.add(SqlLiteral.createExactNumeric("-1", pos));
    return new SqlCase(pos, null, whens, thens,
        SqlLiteral.createExactNumeric("1", pos));
  }

  private static final java.util.Set<SqlTypeName> NUMERIC_TYPE_NAMES = java.util.Set.of(
      SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER, SqlTypeName.BIGINT,
      SqlTypeName.DECIMAL, SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE);

  /** CAST(布尔 AS 数值)（MySQL 布尔即 tinyint 语义，Calcite 校验器类型系统拒绝）→
   * 等价改写：TRUE/FALSE 字面量直接换 1/0；布尔列（列类型目录判定）换
   * CASE WHEN x THEN 1 WHEN NOT x THEN 0 END（缺省 ELSE NULL，NULL→NULL）。 */
  private static SqlNode rewriteCastBoolean(SqlCall call, ColumnHints hints) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2 || !(ops.get(1) instanceof SqlDataTypeSpec spec)
        || !(spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec basic)) {
      return call;
    }
    // SqlBasicTypeNameSpec.getTypeName() 返回 SqlIdentifier（如 INT 归一为
    // INTEGER），经 SqlTypeName.get 映射回枚举判定数值目标类型
    SqlTypeName target = SqlTypeName.get(basic.getTypeName().getSimple());
    if (target == null || !NUMERIC_TYPE_NAMES.contains(target)) {
      return call;
    }
    SqlNode operand = ops.get(0);
    SqlParserPos pos = call.getParserPosition();
    if (operand instanceof SqlLiteral lit && lit.getTypeName() == SqlTypeName.BOOLEAN) {
      return SqlLiteral.createExactNumeric(
          Boolean.TRUE.equals(lit.getValue()) ? "1" : "0", pos);
    }
    if (operand instanceof SqlIdentifier id && id.isSimple()
        && hints.isBooleanColumn(id.getSimple())) {
      SqlNodeList whens = new SqlNodeList(pos);
      whens.add(operand);
      whens.add(new SqlBasicCall(SqlStdOperatorTable.NOT, List.of(operand), pos));
      SqlNodeList thens = new SqlNodeList(pos);
      thens.add(SqlLiteral.createExactNumeric("1", pos));
      thens.add(SqlLiteral.createExactNumeric("0", pos));
      return new SqlCase(pos, null, whens, thens, SqlLiteral.createNull(pos));
    }
    return call;
  }

  /** DATE 列 ± 整数（Oracle 语义：日加减，Calcite 类型系统原生不支持 DATE 与
   * 数值直接加减）→ DATE ± INTERVAL 'n' DAY（结果仍为 DATE，天精度区间不加带
   * 时间部分）。仅当裸列名在已注册库目录中无歧义地为 DATE/TIMESTAMP 时改写；
   * n + date 仅加法交换后改写。 */
  private static SqlNode rewriteDateArith(SqlCall call, ColumnHints hints) {
    List<SqlNode> ops = call.getOperandList();
    if (ops.size() != 2) {
      return call;
    }
    boolean plus = call.getKind() == SqlKind.PLUS;
    SqlNode date;
    SqlNode other;
    if (isDateColumn(ops.get(0), hints)) {
      date = ops.get(0);
      other = ops.get(1);
    } else if (plus && isDateColumn(ops.get(1), hints)) {
      date = ops.get(1);
      other = ops.get(0);
    } else {
      return call;
    }
    Long days = integerLiteral(other);
    if (days == null) {
      return call;
    }
    SqlParserPos pos = call.getParserPosition();
    org.apache.calcite.avatica.util.TimeUnit dayUnit =
        org.apache.calcite.avatica.util.TimeUnit.DAY;
    long magnitude = Math.abs(days);
    SqlNode interval = SqlLiteral.createInterval(days < 0 ? -1 : 1,
        Long.toString(magnitude), new SqlIntervalQualifier(dayUnit, null, pos), pos);
    return new SqlBasicCall(plus ? SqlStdOperatorTable.PLUS : SqlStdOperatorTable.MINUS,
        List.of(date, interval), pos);
  }

  private static boolean isDateColumn(SqlNode node, ColumnHints hints) {
    return node instanceof SqlIdentifier id && id.isSimple()
        && hints.isDateColumn(id.getSimple());
  }

  /** 整数字面量（含一元负号形态）→ Long；非整数字面量返回 null。 */
  private static Long integerLiteral(SqlNode node) {
    if (node instanceof SqlLiteral lit && lit.getValue() instanceof BigDecimal bd
        && bd.scale() <= 0) {
      try {
        return bd.longValueExact();
      } catch (ArithmeticException e) {
        return null;
      }
    }
    if (node instanceof SqlBasicCall neg
        && neg.getOperator() == SqlStdOperatorTable.UNARY_MINUS
        && neg.getOperandList().size() == 1) {
      Long v = integerLiteral(neg.getOperandList().get(0));
      return v == null ? null : -v;
    }
    return null;
  }

  /** MOD 操作数是否含浮点成分：浮点/带标度 DECIMAL 字面量，或列类型目录中的
   * 浮点列（裸列名）。整数 MOD 不改写，保持原生下推路径。 */
  private static boolean modFloatInvolved(SqlCall mod, ColumnHints hints) {
    for (SqlNode op : mod.getOperandList()) {
      if (isFloatLiteral(op) || (op instanceof SqlIdentifier id && id.isSimple()
          && hints.isFloatColumn(id.getSimple()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFloatLiteral(SqlNode node) {
    if (!(node instanceof SqlLiteral lit)) {
      return false;
    }
    return switch (lit.getTypeName()) {
      case DOUBLE, FLOAT, REAL -> true;
      case DECIMAL -> lit.getValue() instanceof BigDecimal bd
          && bd.stripTrailingZeros().scale() > 0;
      default -> false;
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

  /** 列类型目录：供解析期「类型感知改写」（DATE±整数、CAST(布尔 AS 数值)、MOD
   * 浮点修正）按裸列名判定列类型。经 JDBC DatabaseMetaData 扫描各已注册源库构建；
   * 跨库同名但类别不一致的列名一律剔除（防误改写）；扫描失败降级为空目录
   * （相关改写不生效，维持原生报错路径）。类别仅保留粗粒度：date / bool /
   * float / int / other。 */
  static final class ColumnHints {
    static final ColumnHints EMPTY = new ColumnHints(Map.of());
    private final Map<String, String> categories;

    private ColumnHints(Map<String, String> categories) {
      this.categories = categories;
    }

    boolean isDateColumn(String name) {
      return "date".equals(categories.get(key(name)));
    }

    boolean isBooleanColumn(String name) {
      return "bool".equals(categories.get(key(name)));
    }

    boolean isFloatColumn(String name) {
      return "float".equals(categories.get(key(name)));
    }

    private static String key(String name) {
      return name.toLowerCase(java.util.Locale.ROOT);
    }

    static ColumnHints scan(java.util.Collection<javax.sql.DataSource> sources) {
      Map<String, String> cats = new LinkedHashMap<>();
      java.util.Set<String> dropped = new java.util.HashSet<>();
      for (javax.sql.DataSource ds : sources) {
        try (java.sql.Connection c = ds.getConnection();
             java.sql.ResultSet rs = c.getMetaData().getColumns(null, null, "%", "%")) {
          while (rs.next()) {
            String col = rs.getString("COLUMN_NAME");
            int scale = rs.getInt("DECIMAL_DIGITS");
            String cat = category(rs.getInt("DATA_TYPE"), rs.wasNull() ? -1 : scale);
            String key = key(col);
            String prev = cats.get(key);
            if (prev == null) {
              cats.put(key, cat);
            } else {
              cats.put(key, mergeCategory(prev, cat, key, dropped));
            }
          }
        } catch (java.sql.SQLException e) {
          return EMPTY;   // 元数据不可得：降级为空目录，改写安全旁路
        }
      }
      dropped.forEach(cats::remove);
      return new ColumnHints(cats);
    }

    /** 同名列类别合并：数值类内部以「更宽」者为准（int 遇 float → float：按浮点
     * 语义改写 MOD 等，正确性优先，代价仅是该列 MOD 不再下推）；跨大类冲突
     * （date/bool 与其他）无法安全裁决 → 剔除该列。 */
    private static String mergeCategory(String a, String b, String key,
        java.util.Set<String> dropped) {
      if (a.equals(b)) {
        return a;
      }
      java.util.Set<String> s = java.util.Set.of(a, b);
      if (s.equals(java.util.Set.of("int", "float"))) {
        return "float";
      }
      dropped.add(key);
      return a;
    }

    private static String category(int jdbcType, int scale) {
      return switch (jdbcType) {
        case java.sql.Types.DATE, java.sql.Types.TIMESTAMP -> "date";
        case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "bool";
        case java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL -> "float";
        case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> scale > 0 ? "float" : "int";
        case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
            java.sql.Types.BIGINT -> "int";
        default -> "other";
      };
    }
  }
}

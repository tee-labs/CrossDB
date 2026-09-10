package com.example.crossdb;

import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransformCascade;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.util.Optionality;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 本地操作符表：全部本地标量 UDF / 聚合与窗口 UDAF 操作符的定义、注册与
 * 工厂方法（实现体见 CrossDbFunctions / CrossDbAggregates）。其中多数名字
 * SQL 不可见（CROSSDB_ 前缀），由解析期改写（SqlTextRewrites /
 * BitwiseDivRewrites / SqlTreeRewrites）按名挂载到调用点；少数与标准表无冲突
 * 的名字（INITCAP、CONCAT、NVL 等）直接 SQL 可见。 */
final class LocalOperators {
  private LocalOperators() {}

  // ---------- 内置 UDF 操作符（本地求值） ----------

  private static final SqlReturnTypeInference ARG0_NULLABLE =
      new SqlTypeTransformCascade(ReturnTypes.ARG0, SqlTypeTransforms.TO_NULLABLE);
  private static final SqlReturnTypeInference VARCHAR_NULLABLE =
      new SqlTypeTransformCascade(
          b -> b.getTypeFactory().createSqlType(SqlTypeName.VARCHAR),
          SqlTypeTransforms.TO_NULLABLE);

  /** 数值/布尔返回的本地 UDF 必须 FORCE_NULLABLE：TO_NULLABLE 仅在操作数可空时
   * 置空，字面量参数的调用会被推导为 NOT NULL，生成代码对 UDF 返回值无条件拆箱
   * （.intValue()/.doubleValue()），而本地实现对非法输入返回 NULL（三值逻辑），
   * 拆箱即 NPE。 */
  private static SqlReturnTypeInference forced(SqlTypeName type) {
    return new SqlTypeTransformCascade(
        b -> b.getTypeFactory().createSqlType(type), SqlTypeTransforms.FORCE_NULLABLE);
  }

  private static final SqlReturnTypeInference BIGINT_FORCED = forced(SqlTypeName.BIGINT);

  static final SqlUserDefinedFunction SIMILAR_FN_2 =
      similarFn("CROSSDB_SIMILAR", 2);
  static final SqlUserDefinedFunction SIMILAR_FN_3 =
      similarFn("CROSSDB_SIMILAR", 3);

  static final SqlUserDefinedFunction INITCAP_FN =
      udf("CROSSDB_INITCAP", "initcap",
          List.of(SqlTypeFamily.STRING), SqlTypeName.VARCHAR, ARG0_NULLABLE);
  static final SqlUserDefinedFunction OVERLAY_FN_3 =
      udf("CROSSDB_OVERLAY", "overlay3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);
  static final SqlUserDefinedFunction OVERLAY_FN_4 =
      udf("CROSSDB_OVERLAY", "overlay4",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING,
              SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);
  private static final SqlReturnTypeInference ANY_NULLABLE =
      new SqlTypeTransformCascade(
          b -> b.getTypeFactory().createSqlType(SqlTypeName.ANY),
          SqlTypeTransforms.TO_NULLABLE);
  static final SqlUserDefinedFunction FLOOR_UNIT_FN =
      udf("CROSSDB_FLOOR", "floorUnit",
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), SqlTypeName.ANY, ANY_NULLABLE);
  static final SqlUserDefinedFunction CEIL_UNIT_FN =
      udf("CROSSDB_CEIL", "ceilUnit",
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), SqlTypeName.ANY, ANY_NULLABLE);

  // GREATEST/LEAST 可变元参：Calcite 校验器无法对 ANY 类型的同名胜负看重叠做优先级
  // 消解（SqlTypeExplicitPrecedenceList 断言失败），改用「按元数独立命名 + 解析期
  // 改写挂载」的形态，绕开按名重载消解。
  static final SqlUserDefinedFunction GREATEST2 = extremumFn("CROSSDB_GREATEST2", 2, true);
  static final SqlUserDefinedFunction GREATEST3 = extremumFn("CROSSDB_GREATEST3", 3, true);
  static final SqlUserDefinedFunction GREATEST4 = extremumFn("CROSSDB_GREATEST4", 4, true);
  static final SqlUserDefinedFunction LEAST2 = extremumFn("CROSSDB_LEAST2", 2, false);
  static final SqlUserDefinedFunction LEAST3 = extremumFn("CROSSDB_LEAST3", 3, false);
  static final SqlUserDefinedFunction LEAST4 = extremumFn("CROSSDB_LEAST4", 4, false);

  /** TIMESTAMPDIFF 本地标量：unit 操作数（SqlIntervalQualifier）改写为字符串字面量。
   * 操作数派生类型须为 ANY（udf() 按同一类型派生全部操作数，若设 BIGINT 会给
   * 'DAY' 等字符串参数插入 CAST），返回类型单独声明 BIGINT。 */
  static final SqlUserDefinedFunction TIMESTAMPDIFF_FN =
      udf("CROSSDB_TIMESTAMPDIFF", "timestampDiff",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, BIGINT_FORCED);

  /** MySQL REGEXP/RLIKE：Java 正则任意位置匹配，本地求值（解析器仅认 RLIKE 关键字，
   * REGEXP 由语句级预处理替换为 RLIKE 后挂载到此实现）。 */
  static final SqlUserDefinedFunction REGEXP_FN =
      udf("CROSSDB_REGEXP", "regexp",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING), SqlTypeName.VARCHAR,
          forced(SqlTypeName.BOOLEAN));
  static final SqlUserDefinedFunction TRANSLATE_FN =
      udf("CROSSDB_TRANSLATE", "translate",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.VARCHAR, ARG0_NULLABLE);
  /** LEFT/RIGHT(s, n)（MySQL/SQL Server 方言）→ 本地实现（操作数 ANY 混合承载）。 */
  static final SqlUserDefinedFunction LEFT_FN =
      udf("CROSSDB_LEFT", "left", List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  static final SqlUserDefinedFunction RIGHT_FN =
      udf("CROSSDB_RIGHT", "right", List.of(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  /** LOCATE(substr, str[, start])（MySQL/PostgreSQL 方言）→ 本地实现。 */
  // 按元数异名（同 GREATEST/LEAST 先例）：同名双元数会在校验器按名重查时触发
  // ANY 类型优先级断言（SqlTypeExplicitPrecedenceList）
  static final SqlUserDefinedFunction LOCATE2_FN =
      udf("CROSSDB_LOCATE2", "locate2", List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.ANY, BIGINT_FORCED);
  static final SqlUserDefinedFunction LOCATE3_FN =
      udf("CROSSDB_LOCATE3", "locate3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, BIGINT_FORCED);
  /** MOD 浮点语义修正（仅浮点操作数改写挂载；整数走原生 MOD 保下推）。 */
  static final SqlUserDefinedFunction MOD_FN =
      udf("CROSSDB_MOD", "mod", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, forced(SqlTypeName.DOUBLE));
  /** DIV 整除（MySQL 操作符改写目标）。 */
  static final SqlUserDefinedFunction IDIV_FN =
      udf("CROSSDB_IDIV", "idiv", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, BIGINT_FORCED);
  /** Oracle 日期函数：本地实现（H2 亦同名可下推；ADD_MONTHS 返回类型随首参 DATE）。 */
  static final SqlUserDefinedFunction ADD_MONTHS_FN =
      udf("ADD_MONTHS", "addMonths", List.of(SqlTypeFamily.ANY, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  static final SqlUserDefinedFunction MONTHS_BETWEEN_FN =
      udf("MONTHS_BETWEEN", "monthsBetween", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, forced(SqlTypeName.DECIMAL));
  /** CONCAT_WS 可变参 4/5 元形态（2/3 元以 CONCAT_WS 名直接注册；同名多元数并存
   * 会触发优先级断言，故 4/5 元以 CROSSDB_ 前缀由解析期改写挂载）。 */
  static final SqlUserDefinedFunction CONCAT_WS4_FN =
      udf("CROSSDB_CONCAT_WS4", "concatWs4", List.of(SqlTypeFamily.STRING,
          SqlTypeFamily.ANY, SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ARG0_NULLABLE);
  static final SqlUserDefinedFunction CONCAT_WS5_FN =
      udf("CROSSDB_CONCAT_WS5", "concatWs5", List.of(SqlTypeFamily.STRING,
          SqlTypeFamily.ANY, SqlTypeFamily.ANY, SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ARG0_NULLABLE);

  /** INSTR(str, substr[, start])（MySQL/Oracle）→ 本地实现（参数序与 LOCATE 相反）。 */
  static final SqlUserDefinedFunction INSTR2_FN =
      udf("CROSSDB_INSTR2", "instr2", List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.ANY, BIGINT_FORCED);
  static final SqlUserDefinedFunction INSTR3_FN =
      udf("CROSSDB_INSTR3", "instr3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, BIGINT_FORCED);
  /** SUBSTRING_INDEX(s, delim, n)（MySQL）→ 本地实现。 */
  static final SqlUserDefinedFunction SUBSTRING_INDEX_FN =
      udf("CROSSDB_SUBSTRING_INDEX", "substringIndex",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
          SqlTypeName.ANY, ARG0_NULLABLE);
  /** DATE_FORMAT(ts, fmt)（MySQL）→ 本地实现（TIMESTAMP/DATE 承载约定同引擎）。 */
  static final SqlUserDefinedFunction DATE_FORMAT_FN =
      udf("CROSSDB_DATE_FORMAT", "dateFormat",
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), SqlTypeName.ANY,
          VARCHAR_NULLABLE);
  /** REGEXP_REPLACE(s, pat, repl) 3 参全局替换（MySQL ci 语义）→ 本地实现。 */
  static final SqlUserDefinedFunction REGEXP_REPLACE3_FN =
      udf("CROSSDB_REGEXP_REPLACE3", "regexpReplace3",
          List.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          SqlTypeName.ANY, ARG0_NULLABLE);
  /** TO_CHAR(date[, fmt])（Oracle）→ 本地实现。 */
  static final SqlUserDefinedFunction TO_CHAR2_FN =
      udf("CROSSDB_TO_CHAR", "toChar", List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING),
          SqlTypeName.ANY, VARCHAR_NULLABLE);
  /** NEXT_DAY(date, dow)（Oracle）→ 本地实现。 */
  static final SqlUserDefinedFunction NEXT_DAY_FN =
      udf("CROSSDB_NEXT_DAY", "nextDay", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, ARG0_NULLABLE);
  /** MySQL 逻辑 XOR 操作符改写目标（文本级操作数链扫描挂载）。 */
  static final SqlUserDefinedFunction XOR_FN =
      udf("CROSSDB_XOR", "xor", List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY),
          SqlTypeName.ANY, forced(SqlTypeName.INTEGER));
  /** TRY_CAST 解析失败转 NULL 族：按目标类型独立命名（挂载层从类型名编码）。 */
  static final SqlUserDefinedFunction TRY_INT_FN =
      udf("CROSSDB_TRY_INT", "tryInt", List.of(SqlTypeFamily.ANY), SqlTypeName.ANY,
          forced(SqlTypeName.INTEGER));
  static final SqlUserDefinedFunction TRY_BIGINT_FN =
      udf("CROSSDB_TRY_BIGINT", "tryBigint", List.of(SqlTypeFamily.ANY), SqlTypeName.ANY,
          BIGINT_FORCED);
  static final SqlUserDefinedFunction TRY_DOUBLE_FN =
      udf("CROSSDB_TRY_DOUBLE", "tryDouble", List.of(SqlTypeFamily.ANY), SqlTypeName.ANY,
          forced(SqlTypeName.DOUBLE));
  static final SqlUserDefinedFunction TRY_DECIMAL_FN =
      udf("CROSSDB_TRY_DECIMAL", "tryDecimal", List.of(SqlTypeFamily.ANY), SqlTypeName.ANY,
          forced(SqlTypeName.DECIMAL));
  /** 位运算族（& | ^ ~ << >> 操作符改写目标，MySQL/PostgreSQL）。 */
  static final SqlUserDefinedFunction BITAND_FN =
      udf("CROSSDB_BITAND", "bitAnd", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);
  static final SqlUserDefinedFunction BITOR_FN =
      udf("CROSSDB_BITOR", "bitOr", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);
  static final SqlUserDefinedFunction BITXOR_FN =
      udf("CROSSDB_BITXOR", "bitXor", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);
  static final SqlUserDefinedFunction BITNOT_FN =
      udf("CROSSDB_BITNOT", "bitNot", List.of(SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);
  static final SqlUserDefinedFunction SHL_FN =
      udf("CROSSDB_SHL", "shl", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);
  static final SqlUserDefinedFunction SHR_FN =
      udf("CROSSDB_SHR", "shr", List.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlTypeName.BIGINT, BIGINT_FORCED);

  static final SqlUserDefinedAggFunction LISTAGG_DISTINCT_FN =
      aggFn("CROSSDB_LISTAGG", VARCHAR_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.STRING), CrossDbAggregates.ListaggDistinct.class);
  static final SqlUserDefinedAggFunction BOOL_AND_FN =
      aggFn("CROSSDB_BOOL_AND", ReturnTypes.BOOLEAN_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.BoolAnd.class);
  static final SqlUserDefinedAggFunction BOOL_OR_FN =
      aggFn("CROSSDB_BOOL_OR", ReturnTypes.BOOLEAN_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.BoolOr.class);
  static final SqlUserDefinedAggFunction MEDIAN_FN =
      aggFn("CROSSDB_MEDIAN", ReturnTypes.DOUBLE_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.Median.class);
  static final SqlUserDefinedAggFunction PERCENTILE_CONT_FN =
      aggFn("CROSSDB_PERCENTILE_CONT", ReturnTypes.DOUBLE_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.PercentileCont.class);
  static final SqlUserDefinedAggFunction NTH_VALUE2_FN =
      aggFn("CROSSDB_NTH_VALUE2", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue2.class);
  static final SqlUserDefinedAggFunction NTH_VALUE3_FN =
      aggFn("CROSSDB_NTH_VALUE3", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue3.class);
  static final SqlUserDefinedAggFunction NTH_VALUE4_FN =
      aggFn("CROSSDB_NTH_VALUE4", ANY_NULLABLE, List.of(SqlTypeFamily.ANY),
          CrossDbAggregates.NthValue4.class);
  static final SqlUserDefinedAggFunction PERCENTILE_DISC_FN =
      aggFn("CROSSDB_PERCENTILE_DISC", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.PercentileDisc.class);
  static final SqlUserDefinedAggFunction ARRAY_AGG_FN =
      aggFn("CROSSDB_ARRAY_AGG", VARCHAR_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.ArrayAgg.class);
  static final SqlUserDefinedAggFunction ANY_VALUE_FN =
      aggFn("CROSSDB_ANY_VALUE", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.AnyValue.class);
  static final SqlUserDefinedAggFunction MODE_FN =
      aggFn("CROSSDB_MODE", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.Mode.class);
  static final SqlUserDefinedAggFunction COUNT_DISTINCT_FN =
      aggFn("CROSSDB_COUNT_DISTINCT", ReturnTypes.BIGINT_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.CountDistinct.class);
  static final SqlUserDefinedAggFunction FIRST_VALUE_NN_FN =
      aggFn("CROSSDB_FIRST_VALUE_NN", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.FirstNonNull.class);
  static final SqlUserDefinedAggFunction LAST_VALUE_NN_FN =
      aggFn("CROSSDB_LAST_VALUE_NN", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY), CrossDbAggregates.LastNonNull.class);
  /** ARG_MIN/ARG_MAX(v, o)（DuckDB/Trino）：按序键取锚定值。 */
  static final SqlUserDefinedAggFunction ARG_MIN_FN =
      aggFn("CROSSDB_ARG_MIN", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.ArgMin.class);
  static final SqlUserDefinedAggFunction ARG_MAX_FN =
      aggFn("CROSSDB_ARG_MAX", ANY_NULLABLE,
          List.of(SqlTypeFamily.ANY, SqlTypeFamily.ANY), CrossDbAggregates.ArgMax.class);

  /** 注册进引擎操作符表的本地 UDAF（名字 SQL 不可见，仅由解析期改写挂载）。 */
  private static final List<org.apache.calcite.sql.SqlOperator> AGGS = List.of(
      LISTAGG_DISTINCT_FN, MEDIAN_FN, PERCENTILE_CONT_FN,
      NTH_VALUE2_FN, NTH_VALUE3_FN, NTH_VALUE4_FN, BOOL_AND_FN, BOOL_OR_FN,
      PERCENTILE_DISC_FN, ARRAY_AGG_FN, ANY_VALUE_FN, MODE_FN, COUNT_DISTINCT_FN,
      FIRST_VALUE_NN_FN, LAST_VALUE_NN_FN, ARG_MIN_FN, ARG_MAX_FN);

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
      INSTR2_FN, INSTR3_FN, SUBSTRING_INDEX_FN, DATE_FORMAT_FN, REGEXP_REPLACE3_FN,
      CONCAT_WS4_FN, CONCAT_WS5_FN,
      TO_CHAR2_FN, NEXT_DAY_FN, XOR_FN,
      TRY_INT_FN, TRY_BIGINT_FN, TRY_DOUBLE_FN, TRY_DECIMAL_FN,
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
      case "concatWs4" -> new Class<?>[]{String.class, Object.class, Object.class, Object.class};
      case "concatWs5" -> new Class<?>[]{String.class, Object.class, Object.class, Object.class, Object.class};
      case "reverse" -> new Class<?>[]{String.class};
      case "translate" -> new Class<?>[]{String.class, String.class, String.class};
      case "soundex", "ltrim", "rtrim" -> new Class<?>[]{String.class};
      case "left", "right" -> new Class<?>[]{String.class, BigDecimal.class};
      case "locate2" -> new Class<?>[]{String.class, String.class};
      case "locate3" -> new Class<?>[]{String.class, String.class, BigDecimal.class};
      case "instr2" -> new Class<?>[]{String.class, String.class};
      case "instr3" -> new Class<?>[]{String.class, String.class, BigDecimal.class};
      case "substringIndex" -> new Class<?>[]{String.class, String.class, BigDecimal.class};
      case "dateFormat" -> new Class<?>[]{Object.class, String.class};
      case "regexpReplace3" -> new Class<?>[]{String.class, String.class, String.class};
      case "toChar" -> new Class<?>[]{Object.class, String.class};
      case "nextDay" -> new Class<?>[]{Object.class, Object.class};
      case "xor" -> new Class<?>[]{Object.class, Object.class};
      case "tryInt", "tryBigint", "tryDouble", "tryDecimal" -> new Class<?>[]{Object.class};
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
}

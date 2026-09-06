package com.example.crossdb;

import org.apache.calcite.runtime.SqlFunctions;

/** 本地求值的内置标量函数（UDF）：承接 Calcite 解析但不实现、且不可下推源库的
 * 语法（当前仅 SIMILAR TO）。
 *
 * <p>SIMILAR TO 的求值委托 Calcite 运行时（模式→正则翻译，含缓存）；返回 Boolean
 * 以保留三值逻辑（任一入参为 NULL → NULL/UNKNOWN）。 */
public final class CrossDbFunctions {
  private static final SqlFunctions.SimilarFunction SIMILAR =
      new SqlFunctions.SimilarFunction();
  private static final SqlFunctions.SimilarEscapeFunction SIMILAR_ESCAPE =
      new SqlFunctions.SimilarEscapeFunction();

  private CrossDbFunctions() {}

  /** {@code x SIMILAR TO pattern}（默认转义符，与 Calcite 两参语义一致）。 */
  public static Boolean similar(String value, String pattern) {
    return value == null || pattern == null ? null : SIMILAR.similar(value, pattern);
  }

  /** {@code x SIMILAR TO pattern ESCAPE escape}。 */
  public static Boolean similar(String value, String pattern, String escape) {
    return value == null || pattern == null || escape == null
        ? null : SIMILAR_ESCAPE.similar(value, pattern, escape);
  }
}

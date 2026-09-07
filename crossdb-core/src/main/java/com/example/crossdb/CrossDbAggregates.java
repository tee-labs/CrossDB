package com.example.crossdb;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** 本地求值的内置聚合/窗口聚合（UDAF）与标量日期函数实现：承接 Calcite Enumerable
 * 无实现（PERCENTILE_CONT）、方言差异（LISTAGG DISTINCT / MEDIAN）或窗口帧语义
 * 与标准不符（NTH_VALUE）的聚合函数，统一本地执行保证跨源一致。
 *
 * <p>全部按 Calcite UDAF 约定组织：类内提供 {@code init/add/merge/result} 静态方法，
 * 由 {@link org.apache.calcite.schema.impl.AggregateFunctionImpl} 反射接入；
 * 经 SqlRewrites 在解析期把标准语法形态改写到对应实现上（CROSSDB_ 前缀，SQL 不可见）。
 *
 * <p>语义约定：
 * <ul>
 *   <li>聚合入参一律跳过 NULL（标准聚合语义）；</li>
 *   <li>LISTAGG DISTINCT：值去重后升序拼接，空组返回 NULL；</li>
 *   <li>MEDIAN / PERCENTILE_CONT：连续插值（标准 SQL 有序集聚合语义）；</li>
 *   <li>NTH_VALUE：计数「帧内第 n 行」——EnumerableWindow 仅按当前窗口帧喂数，
 *    因此天然遵循帧语义（Calcite 内建实现忽略帧、按整分区取值，与标准不符）；</li>
 *   <li>TIMESTAMPDIFF：完整单位数（MySQL 语义），时间承载与 Exec/Enumerable 一致
 *   （TIMESTAMP → Long epoch millis，按 UTC 墙钟解释，与 CROSSDB_FLOOR/CEIL 相同）。</li>
 * </ul> */
public final class CrossDbAggregates {
  private CrossDbAggregates() {}

  // ---------- LISTAGG(DISTINCT x, sep) ----------

  /** 去重有序聚合状态：TreeSet 天然去重 + 升序。 */
  public static final class ListaggDistinctState {
    final TreeSet<Object> values = new TreeSet<>();
    String sep = ",";
  }

  /** CROSSDB_LISTAGG（承接 LISTAGG(DISTINCT ..)，Calcite 原生 LISTAGG DISTINCT
   * 计划期即抛 ArrayIndexOutOfBoundsException）。 */
  public static final class ListaggDistinct {
    private ListaggDistinct() {}

    public static ListaggDistinctState init() {
      return new ListaggDistinctState();
    }

    public static ListaggDistinctState add(ListaggDistinctState s, Object v, Object sep) {
      if (v != null) {
        s.values.add(v);
      }
      if (sep != null) {
        s.sep = sep.toString();
      }
      return s;
    }

    public static ListaggDistinctState merge(ListaggDistinctState a, ListaggDistinctState b) {
      a.values.addAll(b.values);
      return a;
    }

    public static String result(ListaggDistinctState s) {
      if (s.values.isEmpty()) {
        return null;
      }
      StringBuilder b = new StringBuilder();
      for (Object v : s.values) {
        if (b.length() > 0) {
          b.append(s.sep);
        }
        b.append(v);
      }
      return b.toString();
    }
  }

  // ---------- MEDIAN(x) / PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x) ----------

  /** 数值收集状态（MEDIAN 与 PERCENTILE_CONT 共用形状）。 */
  public static final class NumberListState {
    final List<Double> values = new ArrayList<>();
    double fraction;
    String sep = ",";
  }

  /** CROSSDB_MEDIAN：中位数（偶数个取中间两值均值，连续插值语义）。 */
  public static final class Median {
    private Median() {}

    public static NumberListState init() {
      return new NumberListState();
    }

    public static NumberListState add(NumberListState s, Object v) {
      if (v != null) {
        s.values.add(((Number) v).doubleValue());
      }
      return s;
    }

    public static NumberListState merge(NumberListState a, NumberListState b) {
      a.values.addAll(b.values);
      return a;
    }

    public static Double result(NumberListState s) {
      return interpolate(s, 0.5);
    }
  }

  /** CROSSDB_PERCENTILE_CONT(x, p)：连续百分位（标准 PERCENTILE_CONT 语义）。
   * 分数 p 由改写层从 WITHIN GROUP 形态提出、作为常量列随入参传递。 */
  public static final class PercentileCont {
    private PercentileCont() {}

    public static NumberListState init() {
      return new NumberListState();
    }

    public static NumberListState add(NumberListState s, Object v, Object p) {
      if (v != null && p != null) {
        s.values.add(((Number) v).doubleValue());
        s.fraction = ((Number) p).doubleValue();
      }
      return s;
    }

    public static NumberListState merge(NumberListState a, NumberListState b) {
      a.values.addAll(b.values);
      a.fraction = b.fraction;
      return a;
    }

    public static Double result(NumberListState s) {
      return interpolate(s, s.fraction);
    }
  }

  /** 排序后按连续插值取分位：rank = p*(n-1)，落在 [floor,ceil] 两行之间线性内插。 */
  private static Double interpolate(NumberListState s, double p) {
    int n = s.values.size();
    if (n == 0) {
      return null;
    }
    List<Double> sorted = new ArrayList<>(s.values);
    sorted.sort(null);
    double rank = p * (n - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    double xlo = sorted.get(lo);
    double xhi = sorted.get(hi);
    return xlo + (xhi - xlo) * (rank - lo);
  }

  // ---------- NTH_VALUE(x, n) OVER w（帧内第 n 行） ----------

  /** 帧内行计数状态：按喂入顺序计数，记录第 n 行的值。 */
  public static final class NthState {
    int count;
    Object value;
  }

  /** CROSSDB_NTH_VALUE2：帧内第 2 行取值（按元数独立命名，绕开窗口聚合常量参数
   * 被输入投影裁剪的 Calcite 缺陷——常量 n 不进操作数，编码进函数名）。 */
  public static final class NthValue2 {
    private NthValue2() {}

    public static NthState init() {
      return new NthState();
    }

    public static NthState add(NthState s, Object v) {
      if (++s.count == 2) {
        s.value = v;
      }
      return s;
    }

    public static Object result(NthState s) {
      return s.value;
    }
  }

  /** CROSSDB_NTH_VALUE3：帧内第 3 行取值。 */
  public static final class NthValue3 {
    private NthValue3() {}

    public static NthState init() {
      return new NthState();
    }

    public static NthState add(NthState s, Object v) {
      if (++s.count == 3) {
        s.value = v;
      }
      return s;
    }

    public static Object result(NthState s) {
      return s.value;
    }
  }

  /** CROSSDB_NTH_VALUE4：帧内第 4 行取值。 */
  public static final class NthValue4 {
    private NthValue4() {}

    public static NthState init() {
      return new NthState();
    }

    public static NthState add(NthState s, Object v) {
      if (++s.count == 4) {
        s.value = v;
      }
      return s;
    }

    public static Object result(NthState s) {
      return s.value;
    }
  }

  // ---------- TIMESTAMPDIFF(unit, a, b)（标量） ----------

  /** 完整单位数差值（MySQL 语义）：SEC/MIN/HOUR/DAY/WEEK 按时间长度整除；
   * MONTH/QUARTER/YEAR 按日历完整跨越计数。NULL 入参返回 NULL。
   * 时间承载约定与 CROSSDB_FLOOR/CEIL 一致（Long epoch millis / Timestamp /
   * LocalDateTime / DATE epoch days 均可）。 */
  public static Long timestampDiff(String unit, Object a, Object b) {
    if (unit == null || a == null || b == null) {
      return null;
    }
    LocalDateTime x = toDateTime(a);
    LocalDateTime y = toDateTime(b);
    if (x == null || y == null) {
      return null;
    }
    return switch (unit.toUpperCase()) {
      case "NANOSECOND" -> floorDiv(ChronoUnit.NANOS.between(x, y), 1L);
      case "MICROSECOND" -> floorDiv(ChronoUnit.NANOS.between(x, y), 1_000L);
      case "SECOND" -> floorDiv(ChronoUnit.NANOS.between(x, y), 1_000_000_000L);
      case "MINUTE" -> floorDiv(ChronoUnit.NANOS.between(x, y), 60_000_000_000L);
      case "HOUR" -> floorDiv(ChronoUnit.NANOS.between(x, y), 3_600_000_000_000L);
      case "DAY" -> floorDiv(ChronoUnit.NANOS.between(x, y), 86_400_000_000_000L);
      case "WEEK" -> floorDiv(ChronoUnit.DAYS.between(x, y), 7L);
      case "MONTH" -> ChronoUnit.MONTHS.between(x, y);
      case "QUARTER" -> floorDiv(ChronoUnit.MONTHS.between(x, y), 3L);
      case "YEAR" -> floorDiv(ChronoUnit.MONTHS.between(x, y), 12L);
      default -> throw new IllegalArgumentException(
          "CROSSDB_TIMESTAMPDIFF 不支持时间单位 " + unit);
    };
  }

  private static long floorDiv(long a, long b) {
    return java.lang.Math.floorDiv(a, b);
  }

  /** 入参统一转 LocalDateTime：按 UTC 墙钟解释 epoch millis（引擎承载约定）。 */
  private static LocalDateTime toDateTime(Object v) {
    if (v instanceof Number num) {
      return Instant.ofEpochMilli(num.longValue()).atZone(ZoneOffset.UTC).toLocalDateTime();
    }
    if (v instanceof Timestamp t) {
      return Instant.ofEpochMilli(t.getTime()).atZone(ZoneOffset.UTC).toLocalDateTime();
    }
    if (v instanceof LocalDateTime dt) {
      return dt;
    }
    if (v instanceof java.util.Date d) {
      return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalDateTime();
    }
    if (v instanceof Integer days) {
      return java.time.LocalDate.ofEpochDay(days).atStartOfDay();
    }
    return null;
  }
}

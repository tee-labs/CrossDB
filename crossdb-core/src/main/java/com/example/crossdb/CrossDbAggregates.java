package com.example.crossdb;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  // ---------- BOOL_AND(x) / BOOL_OR(x)（PostgreSQL 三值布尔聚合） ----------

  /** 布尔聚合状态：是否见过非 NULL 值、TRUE、FALSE。 */
  public static final class BoolState {
    boolean seen;
    boolean sawTrue;
    boolean sawFalse;
  }

  /** CROSSDB_BOOL_AND：全 TRUE 才 TRUE，见 FALSE 即 FALSE；空集（全 NULL）返回 NULL。
   * {@code EVERY} 为其同义别名（改写层归一）。 */
  public static final class BoolAnd {
    private BoolAnd() {}

    public static BoolState init() {
      return new BoolState();
    }

    public static BoolState add(BoolState s, Object v) {
      if (v != null) {
        s.seen = true;
        if (Boolean.TRUE.equals(v)) {
          s.sawTrue = true;
        } else {
          s.sawFalse = true;
        }
      }
      return s;
    }

    public static BoolState merge(BoolState a, BoolState b) {
      a.seen |= b.seen;
      a.sawTrue |= b.sawTrue;
      a.sawFalse |= b.sawFalse;
      return a;
    }

    public static Boolean result(BoolState s) {
      return s.seen ? !s.sawFalse : null;
    }
  }

  /** CROSSDB_BOOL_OR：见 TRUE 即 TRUE，全 FALSE 才 FALSE；空集（全 NULL）返回 NULL。 */
  public static final class BoolOr {
    private BoolOr() {}

    public static BoolState init() {
      return new BoolState();
    }

    public static BoolState add(BoolState s, Object v) {
      if (v != null) {
        s.seen = true;
        if (Boolean.TRUE.equals(v)) {
          s.sawTrue = true;
        } else {
          s.sawFalse = true;
        }
      }
      return s;
    }

    public static BoolState merge(BoolState a, BoolState b) {
      a.seen |= b.seen;
      a.sawTrue |= b.sawTrue;
      a.sawFalse |= b.sawFalse;
      return a;
    }

    public static Boolean result(BoolState s) {
      return s.seen ? s.sawTrue : null;
    }
  }

  // ---------- PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY x) ----------

  /** 对象值收集状态：保留入参原类型（PERCENTILE_DISC 返回输入类型的值）。 */
  public static final class ObjectListState {
    final List<Object> values = new ArrayList<>();
    double fraction;
  }

  /** CROSSDB_PERCENTILE_DISC(x, p)：离散百分位（标准 SQL 有序集聚合语义）——
   * 取升序第 {@code ceil(p*n)} 位（1 基）的输入值，返回类型与输入一致。 */
  public static final class PercentileDisc {
    private PercentileDisc() {}

    public static ObjectListState init() {
      return new ObjectListState();
    }

    public static ObjectListState add(ObjectListState s, Object v, Object p) {
      if (v != null && p != null) {
        s.values.add(v);
        s.fraction = ((Number) p).doubleValue();
      }
      return s;
    }

    public static ObjectListState merge(ObjectListState a, ObjectListState b) {
      a.values.addAll(b.values);
      a.fraction = b.fraction;
      return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object result(ObjectListState s) {
      int n = s.values.size();
      if (n == 0) {
        return null;
      }
      List<Object> sorted = new ArrayList<>(s.values);
      sorted.sort((x, y) -> ((Comparable) x).compareTo(y));
      int k = (int) Math.ceil(s.fraction * n);
      return sorted.get(Math.min(Math.max(k, 1), n) - 1);
    }
  }

  // ---------- ARRAY_AGG(x)（VARCHAR 渲染） ----------

  /** 值列表状态：按喂入顺序收集（含 NULL 元素）。 */
  public static final class ValueListState {
    final List<Object> values = new ArrayList<>();
  }

  /** CROSSDB_ARRAY_AGG(x)：数组聚合的本地替身。引擎不支持 ARRAY 值类型透出，
   * 以 {@code "[v1, v2, ...]"} 字符串渲染承载；Calcite UDAF 管道默认过滤 NULL
   * 入参（与 LISTAGG 族一致跳过 NULL 元素），全 NULL/空组返回 NULL。喂入顺序
   * 不保证（WITHIN GROUP 对 UDAF 不强制排序），渲染按值升序保证确定性。 */
  public static final class ArrayAgg {
    private ArrayAgg() {}

    public static ValueListState init() {
      return new ValueListState();
    }

    public static ValueListState add(ValueListState s, Object v) {
      s.values.add(v);
      return s;
    }

    public static ValueListState merge(ValueListState a, ValueListState b) {
      a.values.addAll(b.values);
      return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String result(ValueListState s) {
      if (s.values.isEmpty()) {
        return null;
      }
      List<Object> sorted = new ArrayList<>(s.values);
      sorted.sort((x, y) -> ((Comparable) x).compareTo(y));
      StringBuilder b = new StringBuilder("[");
      for (int i = 0; i < sorted.size(); i++) {
        if (i > 0) {
          b.append(", ");
        }
        b.append(String.valueOf(sorted.get(i)));
      }
      return b.append(']').toString();
    }
  }

  // ---------- ANY_VALUE(x)（MySQL） / MODE(x)（Oracle） ----------

  /** 首见非 NULL 状态：ANY_VALUE 语义上非确定，本地取首见值保证确定性。 */
  public static final class AnyValueState {
    Object value;
    boolean set;
  }

  /** CROSSDB_ANY_VALUE：组内任取一值（首见非 NULL；全 NULL/空组返回 NULL）。 */
  public static final class AnyValue {
    private AnyValue() {}

    public static AnyValueState init() {
      return new AnyValueState();
    }

    public static AnyValueState add(AnyValueState s, Object v) {
      if (v != null && !s.set) {
        s.value = v;
        s.set = true;
      }
      return s;
    }

    public static AnyValueState merge(AnyValueState a, AnyValueState b) {
      return a.set ? a : b;
    }

    public static Object result(AnyValueState s) {
      return s.set ? s.value : null;
    }
  }

  /** 频次统计状态：按值自然序（TreeMap）以便并列时取最小值。 */
  public static final class ModeState {
    final java.util.TreeMap<Object, Long> counts = new java.util.TreeMap<>();
  }

  /** CROSSDB_MODE：众数（出现频次最高的值，跳过 NULL；频次并列取最小值——
   * Oracle 语义）；空组返回 NULL。 */
  public static final class Mode {
    private Mode() {}

    public static ModeState init() {
      return new ModeState();
    }

    public static ModeState add(ModeState s, Object v) {
      if (v != null) {
        s.counts.merge(v, 1L, Long::sum);
      }
      return s;
    }

    public static ModeState merge(ModeState a, ModeState b) {
      b.counts.forEach((k, c) -> a.counts.merge(k, c, Long::sum));
      return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object result(ModeState s) {
      Object best = null;
      long bestCount = 0;
      for (Map.Entry<Object, Long> e : s.counts.entrySet()) {
        if (e.getValue() > bestCount) {
          best = e.getKey();
          bestCount = e.getValue();
        }
      }
      return best;
    }
  }

  // ---------- COUNT(DISTINCT x) OVER w（窗口去重计数） ----------

  /** CROSSDB_COUNT_DISTINCT：窗口内去重计数（跳过 NULL）。Calcite EnumerableWindow
   * 静默丢弃窗口聚合的 DISTINCT 量化符（返回 COUNT(*) 语义），改写层把
   * {@code COUNT(DISTINCT x) OVER w} 挂载到本实现修正。 */
  public static final class CountDistinct {
    private CountDistinct() {}

    public static java.util.HashSet<Object> init() {
      return new java.util.HashSet<>();
    }

    public static java.util.HashSet<Object> add(java.util.HashSet<Object> s, Object v) {
      if (v != null) {
        s.add(v);
      }
      return s;
    }

    public static java.util.HashSet<Object> merge(java.util.HashSet<Object> a,
        java.util.HashSet<Object> b) {
      a.addAll(b);
      return a;
    }

    public static Long result(java.util.HashSet<Object> s) {
      return (long) s.size();
    }
  }

  // ---------- FIRST_VALUE / LAST_VALUE IGNORE NULLS ----------

  /** 首/末非 NULL 状态。 */
  public static final class NonNullEndsState {
    Object first;
    Object last;
  }

  /** CROSSDB_FIRST_VALUE_NN：帧内首个非 NULL（EnumerableWindow 按当前帧喂数，
   * 喂入顺序即帧内行序——与 FIRST_VALUE(x) IGNORE NULLS 标准语义一致）。 */
  public static final class FirstNonNull {
    private FirstNonNull() {}

    public static NonNullEndsState init() {
      return new NonNullEndsState();
    }

    public static NonNullEndsState add(NonNullEndsState s, Object v) {
      if (v != null) {
        if (s.first == null) {
          s.first = v;
        }
        s.last = v;
      }
      return s;
    }

    public static NonNullEndsState merge(NonNullEndsState a, NonNullEndsState b) {
      if (a.first == null) {
        a.first = b.first;
      }
      if (b.last != null) {
        a.last = b.last;
      }
      return a;
    }

    public static Object result(NonNullEndsState s) {
      return s.first;
    }
  }

  /** CROSSDB_LAST_VALUE_NN：帧内末个非 NULL。 */
  public static final class LastNonNull {
    private LastNonNull() {}

    public static NonNullEndsState init() {
      return new NonNullEndsState();
    }

    public static NonNullEndsState add(NonNullEndsState s, Object v) {
      if (v != null) {
        if (s.first == null) {
          s.first = v;
        }
        s.last = v;
      }
      return s;
    }

    public static NonNullEndsState merge(NonNullEndsState a, NonNullEndsState b) {
      if (a.first == null) {
        a.first = b.first;
      }
      if (b.last != null) {
        a.last = b.last;
      }
      return a;
    }

    public static Object result(NonNullEndsState s) {
      return s.last;
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

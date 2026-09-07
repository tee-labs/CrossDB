package com.example.crossdb;

import org.apache.calcite.runtime.SqlFunctions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/** 本地求值的内置标量/聚合函数（UDF）：承接 Calcite 不实现、或下推源库会因方言差异
 * 出错的函数，统一在本地 Enumerable 执行，保证跨源行为一致。
 *
 * <p>包含两类：
 * <ul>
 *   <li>SIMILAR TO（Calcite Enumerable 不实现且不可下推）；</li>
 *   <li>各数据库方言常用但 Calcite 未注册 / 注册后 H2 等源库缺函数的标量函数
 *   （INITCAP、LPAD/RPAD、REPEAT、CHR、GREATEST/LEAST、NVL/IFNULL、
 *   FLOOR/CEIL(datetime TO unit)、OVERLAY）。</li>
 * </ul>
 *
 * <p>标量函数全部对 NULL 入参返回 NULL（三值逻辑）；比较类函数跳过 NULL 操作数
 * （GREATEST/LEAST 的标准语义）。 */
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

  /** INITCAP：每个单词（以非字母数字分隔）首字母大写，其余小写（Oracle/PostgreSQL 语义）。 */
  public static String initcap(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    StringBuilder b = new StringBuilder(s.length());
    boolean upper = true;
    for (char c : s.toLowerCase().toCharArray()) {
      b.append(upper ? Character.toUpperCase(c) : c);
      upper = !Character.isLetterOrDigit(c);
    }
    return b.toString();
  }

  /** LPAD(s, n, pad)：左侧补 pad 到长度 n；超长截前 n 字符（Oracle/MySQL 语义）。 */
  public static String lpad(String s, BigDecimal n, String pad) {
    return padImpl(s, n, pad, true);
  }

  /** RPAD(s, n, pad)：右侧补 pad 到长度 n；超长截前 n 字符。 */
  public static String rpad(String s, BigDecimal n, String pad) {
    return padImpl(s, n, pad, false);
  }

  private static String padImpl(String s, BigDecimal n, String pad, boolean left) {
    if (s == null || n == null || pad == null || pad.isEmpty() || n.signum() < 0) {
      return s == null || n == null || pad == null ? null : s;
    }
    int len = n.intValue();
    if (s.length() >= len) {
      return s.substring(0, len);
    }
    StringBuilder fill = new StringBuilder();
    while (fill.length() < len - s.length()) {
      fill.append(pad);
    }
    fill.setLength(len - s.length());
    return left ? fill.append(s).toString() : new StringBuilder(s).append(fill).toString();
  }

  /** REPEAT(s, n)。 */
  public static String repeat(String s, BigDecimal n) {
    if (s == null || n == null) {
      return null;
    }
    return n.signum() <= 0 ? "" : s.repeat(n.intValue());
  }

  /** CHR(c)：码点转单字符（Oracle 语义）。 */
  public static String chr(BigDecimal c) {
    return c == null ? null : new String(Character.toChars(c.intValue()));
  }

  /** CONCAT(a, b)：两参字符串拼接（MySQL 语义）。 */
  public static String concat2(String a, String b) {
    return a == null || b == null ? null : a + b;
  }

  /** CONCAT(a, b, c)。 */
  public static String concat3(String a, String b, String c) {
    return a == null || b == null || c == null ? null : a + b + c;
  }

  /** CONCAT_WS(sep, a, b)：以 sep 连接，跳过 NULL 值参（MySQL 语义）。 */
  public static String concatWs2(String sep, Object a, Object b) {
    return joinWs(sep, a, b);
  }

  /** CONCAT_WS(sep, a, b, c)。 */
  public static String concatWs3(String sep, Object a, Object b, Object c) {
    return joinWs(sep, a, b, c);
  }

  private static String joinWs(String sep, Object... parts) {
    if (sep == null) {
      return null;
    }
    StringBuilder b = new StringBuilder();
    for (Object p : parts) {
      if (p != null) {
        if (b.length() > 0) {
          b.append(sep);
        }
        b.append(p);
      }
    }
    return b.toString();
  }

  /** REVERSE(s)。 */
  public static String reverse(String s) {
    return s == null ? null : new StringBuilder(s).reverse().toString();
  }

  /** OVERLAY(s PLACING r FROM from)（标准 SQL，len 默认为 r 的长度）。 */
  public static String overlay3(String s, String r, BigDecimal from) {
    return overlay(s, r, from, r == null ? null : BigDecimal.valueOf(r.length()));
  }

  /** OVERLAY(s PLACING r FROM from FOR len)（标准 SQL）。 */
  public static String overlay4(String s, String r, BigDecimal from, BigDecimal len) {
    return overlay(s, r, from, len);
  }

  private static String overlay(String s, String r, BigDecimal from, BigDecimal len) {
    if (s == null || r == null || from == null || len == null) {
      return null;
    }
    int start = Math.max(from.intValue(), 1) - 1;
    int end = Math.min(Math.max(start + len.intValue(), start), s.length());
    return s.substring(0, start) + r + s.substring(end);
  }

  /** GREATEST：跳过 NULL 取最大（标准比较语义），全 NULL 返回 NULL。 */
  public static Object greatest2(Object a, Object b) {
    return extremum(a, b, true);
  }

  public static Object greatest3(Object a, Object b, Object c) {
    return extremum(extremum(a, b, true), c, true);
  }

  public static Object greatest4(Object a, Object b, Object c, Object d) {
    return extremum(extremum(extremum(a, b, true), c, true), d, true);
  }

  /** LEAST：跳过 NULL 取最小，全 NULL 返回 NULL。 */
  public static Object least2(Object a, Object b) {
    return extremum(a, b, false);
  }

  public static Object least3(Object a, Object b, Object c) {
    return extremum(extremum(a, b, false), c, false);
  }

  public static Object least4(Object a, Object b, Object c, Object d) {
    return extremum(extremum(extremum(a, b, false), c, false), d, false);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object extremum(Object a, Object b, boolean max) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    int cmp = ((Comparable) a).compareTo(b);
    return (max ? cmp >= 0 : cmp <= 0) ? a : b;
  }

  public static Object nvl(Object a, Object b) {
    return a != null ? a : b;
  }

  public static Object ifnull(Object a, Object b) {
    return a != null ? a : b;
  }

  /** TIMESTAMPDIFF(unit, a, b)：完整单位数差值（MySQL 语义），实现见
   * {@link CrossDbAggregates#timestampDiff}。 */
  public static Long timestampDiff(String unit, Object a, Object b) {
    return CrossDbAggregates.timestampDiff(unit, a, b);
  }

  /** FLOOR(timestamp/date TO unit)：datetime 截断（标准 SQL 语义，本地求值，
   * 避免下推源库方言不支持 {@code FLOOR(x TO unit)} 语法）。入参承载约定与
   * Exec/Enumerable 一致：TIMESTAMP → Long（epoch millis），DATE → Integer（epoch days）。 */
  public static Object floorUnit(Object v, String unit) {
    return truncate(v, unit, true);
  }

  /** CEIL(timestamp/date TO unit)：datetime 向上取整到单位。 */
  public static Object ceilUnit(Object v, String unit) {
    return truncate(v, unit, false);
  }

  private static Object truncate(Object v, String unit, boolean down) {
    if (v == null || unit == null) {
      return null;
    }
    // DATE（Integer epoch days）：按日历日期截断后转回 epoch days
    if (v instanceof Integer days) {
      java.time.LocalDate d = java.time.LocalDate.ofEpochDay(days);
      java.time.LocalDate t = switch (unit.toUpperCase()) {
        case "YEAR" -> d.withDayOfYear(1);
        case "QUARTER" -> d.withDayOfMonth(1).withMonth((d.getMonthValue() - 1) / 3 * 3 + 1);
        case "MONTH" -> d.withDayOfMonth(1);
        case "WEEK" -> d.minusDays(d.getDayOfWeek().getValue() - 1L);
        case "DAY" -> d;
        default -> null;
      };
      if (t == null) {
        throw new IllegalArgumentException("CROSSDB_FLOOR/CEIL 不支持对 DATE 的时间单位 " + unit);
      }
      if (!down && t.equals(d)) {
        return java.sql.Date.valueOf(t);
      }
      if (!down) {
        t = switch (unit.toUpperCase()) {
          case "YEAR" -> t.plusYears(1);
          case "QUARTER" -> t.plusMonths(3);
          case "MONTH" -> t.plusMonths(1);
          case "WEEK" -> t.plusWeeks(1);
          default -> t.plusDays(1);
        };
      }
      return java.sql.Date.valueOf(t);
    }
    long millis = v instanceof Number num ? num.longValue()
        : v instanceof Timestamp t ? t.getTime()
        : v instanceof LocalDateTime dt ? Timestamp.valueOf(dt).getTime()
        : Long.MIN_VALUE;
    if (millis == Long.MIN_VALUE) {
      throw new IllegalArgumentException("CROSSDB_FLOOR/CEIL 不支持入参类型 " + v.getClass().getName());
    }
    java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
    java.time.ZonedDateTime z = instant.atZone(java.time.ZoneOffset.UTC);
    java.time.ZonedDateTime truncated = switch (unit.toUpperCase()) {
      case "YEAR" -> z.withDayOfYear(1).toLocalDate().atStartOfDay(z.getZone());
      case "QUARTER" -> z.withDayOfMonth(1).toLocalDate()
          .withMonth((z.getMonthValue() - 1) / 3 * 3 + 1).atStartOfDay(z.getZone());
      case "MONTH" -> z.toLocalDate().withDayOfMonth(1).atStartOfDay(z.getZone());
      case "WEEK" -> z.toLocalDate().minusDays(z.getDayOfWeek().getValue() - 1L).atStartOfDay(z.getZone());
      case "DAY" -> z.toLocalDate().atStartOfDay(z.getZone());
      case "HOUR" -> z.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
      case "MINUTE" -> z.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
      case "SECOND" -> z.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      default -> throw new IllegalArgumentException("CROSSDB_FLOOR/CEIL 不支持时间单位 " + unit);
    };
    if (!down) {
      if (!truncated.toInstant().equals(instant)) {
        truncated = switch (unit.toUpperCase()) {
          case "YEAR" -> truncated.plusYears(1);
          case "QUARTER" -> truncated.plusMonths(3);
          case "MONTH" -> truncated.plusMonths(1);
          case "WEEK" -> truncated.plusWeeks(1);
          case "DAY" -> truncated.plusDays(1);
          case "HOUR" -> truncated.plusHours(1);
          case "MINUTE" -> truncated.plusMinutes(1);
          case "SECOND" -> truncated.plusSeconds(1);
          default -> truncated;
        };
      }
    }
    return Timestamp.from(truncated.toInstant());
  }
}

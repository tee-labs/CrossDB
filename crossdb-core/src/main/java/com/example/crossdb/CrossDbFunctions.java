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

  /** MySQL REGEXP/RLIKE：Java 正则在 value 内任意位置匹配（POSIX 方言近似）。
   * 与 MySQL 默认 collation 不同，此处大小写敏感；任一侧 NULL 返回 NULL。 */
  public static Boolean regexp(String value, String pattern) {
    if (value == null || pattern == null) {
      return null;
    }
    return java.util.regex.Pattern.compile(pattern).matcher(value).find();
  }

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

  /** CONCAT_WS(sep, a, b, c, d)（4 参调用形态）。 */
  public static String concatWs4(String sep, Object a, Object b, Object c) {
    return joinWs(sep, a, b, c);
  }

  /** CONCAT_WS(sep, a, b, c, d)（5 参调用形态）。 */
  public static String concatWs5(String sep, Object a, Object b, Object c, Object d) {
    return joinWs(sep, a, b, c, d);
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

  /** TRANSLATE(s, from, to)（Oracle/PostgreSQL 语义）：from→to 按位逐字符映射，
   * from 多出的字符（to 较短）删除；from 为空返回原串；任一 NULL 返回 NULL。 */
  public static String translate(String s, String from, String to) {
    if (s == null || from == null || to == null) {
      return null;
    }
    if (from.isEmpty()) {
      return s;
    }
    StringBuilder b = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      int idx = from.indexOf(c);
      if (idx < 0) {
        b.append(c);
      } else if (idx < to.length()) {
        b.append(to.charAt(idx));
      }
    }
    return b.toString();
  }

  /** SOUNDEX(s)：标准 Soundex 语音编码（首字母 + 三位数字，不足补 0）。
   * 非字母字符忽略；仅字母时返回空串。 */
  public static String soundex(String s) {
    if (s == null) {
      return null;
    }
    String t = s.toUpperCase().replaceAll("[^A-Z]", "");
    if (t.isEmpty()) {
      return "";
    }
    StringBuilder b = new StringBuilder(4).append(t.charAt(0));
    int prev = soundexCode(t.charAt(0));
    for (int i = 1; i < t.length() && b.length() < 4; i++) {
      char c = t.charAt(i);
      if (c == 'H' || c == 'W') {
        continue;   // 不编码，但保留前码（H/W 隔断的同码仍合并）
      }
      int d = soundexCode(c);
      if (d == 0) {
        prev = 0;   // 元音重置合并基线
      } else if (d != prev) {
        b.append((char) ('0' + d));
        prev = d;
      }
    }
    while (b.length() < 4) {
      b.append('0');
    }
    return b.toString();
  }

  /** Soundex 辅音码位；元音与 Y/H/W 返回 0（不编码）。 */
  private static int soundexCode(char c) {
    return switch (c) {
      case 'B', 'F', 'P', 'V' -> 1;
      case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> 2;
      case 'D', 'T' -> 3;
      case 'L' -> 4;
      case 'M', 'N' -> 5;
      case 'R' -> 6;
      default -> 0;
    };
  }

  /** LTRIM(s)：去除前导空格（Oracle/PostgreSQL 语义，仅空格字符）。 */
  public static String ltrim(String s) {
    if (s == null) {
      return null;
    }
    int i = 0;
    while (i < s.length() && s.charAt(i) == ' ') {
      i++;
    }
    return s.substring(i);
  }

  /** RTRIM(s)：去除尾随空格。 */
  public static String rtrim(String s) {
    if (s == null) {
      return null;
    }
    int i = s.length();
    while (i > 0 && s.charAt(i - 1) == ' ') {
      i--;
    }
    return s.substring(0, i);
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

  /** LEFT(s, n)（MySQL/SQL Server）：左起 n 字符；n<=0 得空串、n 超长得整串。 */
  public static String left(String s, BigDecimal n) {
    if (s == null || n == null) {
      return null;
    }
    int len = n.intValue();
    return len <= 0 ? "" : s.substring(0, Math.min(len, s.length()));
  }

  /** RIGHT(s, n)（MySQL/SQL Server）：右起 n 字符；n<=0 得空串、n 超长得整串。 */
  public static String right(String s, BigDecimal n) {
    if (s == null || n == null) {
      return null;
    }
    int len = n.intValue();
    return len <= 0 ? "" : s.substring(Math.max(s.length() - len, 0));
  }

  /** LOCATE(substr, str[, start])（MySQL/PostgreSQL）：substr 在 str 中自 start
   * （1 基）起首次出现位置，未找到得 0；start<1 得 0；任一 NULL 得 NULL。 */
  public static Long locate2(String substr, String str) {
    return locate(substr, str, 1L);
  }

  /** LOCATE(substr, str, start) 三参形态。 */
  public static Long locate3(String substr, String str, BigDecimal start) {
    return locate(substr, str, start == null ? null : start.longValue());
  }

  private static Long locate(String substr, String str, Long start) {
    if (substr == null || str == null || start == null) {
      return null;
    }
    if (start < 1) {
      return 0L;
    }
    return (long) str.indexOf(substr, (int) (start - 1)) + 1;
  }

  /** MOD(a, b) 浮点语义修正实现：BigDecimal.remainder 与 Java {@code %} 同为
   * 「商向零截断」语义（符号随被除数），标准/MySQL/PostgreSQL 一致；b=0 得 NULL
   * （MySQL 语义）；任一 NULL 得 NULL。整数操作数仍走原生 MOD 不进本实现。 */
  public static Double mod(Object a, Object b) {
    if (a == null || b == null) {
      return null;
    }
    try {
      return number(a).remainder(number(b)).doubleValue();
    } catch (ArithmeticException e) {
      return null;   // 除数为 0：MySQL 返回 NULL
    }
  }

  /** DIV 整除（MySQL）：商向零截断；除数为 0 得 NULL；任一 NULL 得 NULL。 */
  public static Long idiv(Object a, Object b) {
    if (a == null || b == null) {
      return null;
    }
    try {
      return number(a).divideToIntegralValue(number(b)).longValueExact();
    } catch (ArithmeticException e) {
      return null;   // 除数为 0 或商超 LONG
    }
  }

  private static BigDecimal number(Object v) {
    if (v instanceof BigDecimal bd) {
      return bd;
    }
    if (v instanceof java.math.BigInteger bi) {
      return new java.math.BigDecimal(bi);
    }
    if (v instanceof Double || v instanceof Float) {
      return BigDecimal.valueOf(((Number) v).doubleValue());
    }
    if (v instanceof Number nu) {
      return BigDecimal.valueOf(nu.longValue());
    }
    throw new IllegalArgumentException("要求数值操作数: " + v);
  }

  /** 位运算族（MySQL/PostgreSQL 操作符改写目标）：NULL 进 NULL 出，参数按 LONG。 */
  public static Long bitAnd(Long a, Long b) {
    return a == null || b == null ? null : a & b;
  }

  public static Long bitOr(Long a, Long b) {
    return a == null || b == null ? null : a | b;
  }

  public static Long bitXor(Long a, Long b) {
    return a == null || b == null ? null : a ^ b;
  }

  /** 一元按位取反（PostgreSQL {@code ~}）。 */
  public static Long bitNot(Long a) {
    return a == null ? null : ~a;
  }

  /** 左移 {@code a << n}（n>=64 得 0；n<=0 不移动）。 */
  public static Long shl(Long a, Long n) {
    if (a == null || n == null) {
      return null;
    }
    if (n <= 0) {
      return a;
    }
    return n >= 64 ? 0L : a << n;
  }

  /** 右移 {@code a >> n}（算术右移；移位超出 63 得 0/-1，负移位数不移动）。 */
  public static Long shr(Long a, Long n) {
    if (a == null || n == null) {
      return null;
    }
    if (n <= 0) {
      return a;
    }
    return n >= 64 ? (a < 0 ? -1L : 0L) : a >> n;
  }

  /** ADD_MONTHS(date, n)（Oracle）：DATE 承载约定为 epoch days（Integer）或
   * java.sql.Date；n 可为负。NULL 进 NULL 出。 */
  public static java.sql.Date addMonths(Object date, BigDecimal months) {
    java.time.LocalDate d = toLocalDate(date);
    if (d == null || months == null) {
      return null;
    }
    return java.sql.Date.valueOf(d.plusMonths(months.longValue()));
  }

  /** MONTHS_BETWEEN(d1, d2)（Oracle 语义）：同为月末日或同 day-of-month 时为整月
   * 差；否则带 (d1.day - d2.day)/31 分数部分（保留 6 位标度）。 */
  public static BigDecimal monthsBetween(Object a, Object b) {
    java.time.LocalDate d1 = toLocalDate(a);
    java.time.LocalDate d2 = toLocalDate(b);
    if (d1 == null || d2 == null) {
      return null;
    }
    long months = (d1.getYear() - d2.getYear()) * 12L + d1.getMonthValue() - d2.getMonthValue();
    boolean whole = d1.getDayOfMonth() == d2.getDayOfMonth()
        || (isMonthEnd(d1) && isMonthEnd(d2));
    if (whole) {
      return BigDecimal.valueOf(months);
    }
    return BigDecimal.valueOf(months).add(
        BigDecimal.valueOf(d1.getDayOfMonth() - d2.getDayOfMonth())
            .divide(BigDecimal.valueOf(31), 6, java.math.RoundingMode.HALF_UP));
  }

  private static java.time.LocalDate toLocalDate(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Integer days) {
      return java.time.LocalDate.ofEpochDay(days);
    }
    if (v instanceof java.sql.Date d) {
      return d.toLocalDate();
    }
    if (v instanceof java.time.LocalDate d) {
      return d;
    }
    throw new IllegalArgumentException("日期函数不支持入参类型 " + v.getClass().getName());
  }

  private static boolean isMonthEnd(java.time.LocalDate d) {
    return d.getDayOfMonth() == d.lengthOfMonth();
  }

  /** INSTR(str, substr)（MySQL/Oracle）：substr 在 str 中首次出现位置（1 基），
   * 未找到 0；任一 NULL 得 NULL。与 LOCATE 参数顺序相反。 */
  public static Long instr2(String str, String substr) {
    if (str == null || substr == null) {
      return null;
    }
    return (long) str.indexOf(substr) + 1;
  }

  /** INSTR(str, substr, start)（Oracle）：自 start（1 基）起首次出现位置；
   * start<1 或未找到得 0。 */
  public static Long instr3(String str, String substr, BigDecimal start) {
    if (str == null || substr == null || start == null) {
      return null;
    }
    long s = start.longValue();
    if (s < 1) {
      return 0L;
    }
    return (long) str.indexOf(substr, (int) (s - 1)) + 1;
  }

  /** SUBSTRING_INDEX(s, delim, n)（MySQL）：n>0 取第 n 个 delim 之前的前缀；
   * n<0 取倒数第 |n| 个 delim 之后的后缀；n=0 得空串；delim 空串得空串。 */
  public static String substringIndex(String s, String delim, BigDecimal n) {
    if (s == null || delim == null || n == null) {
      return null;
    }
    if (delim.isEmpty() || n.signum() == 0) {
      return "";
    }
    int count = n.intValue();
    if (count > 0) {
      int idx = -1;
      for (int i = 0; i < count; i++) {
        idx = s.indexOf(delim, idx + 1);
        if (idx < 0) {
          return s;
        }
      }
      return s.substring(0, idx);
    }
    int idx = s.length();
    for (int i = 0; i < -count; i++) {
      idx = s.lastIndexOf(delim, idx - 1);
      if (idx < 0) {
        return s;
      }
    }
    return s.substring(idx + delim.length());
  }

  /** REGEXP_REPLACE(s, pat, repl)（MySQL 3 参全局替换；默认 ci collation 语义：
   * 匹配不区分大小写，替换全部出现）。 */
  public static String regexpReplace3(String s, String pat, String repl) {
    if (s == null || pat == null || repl == null) {
      return null;
    }
    if (pat.isEmpty()) {
      return s;
    }
    try {
      return java.util.regex.Pattern.compile(pat,
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
          .matcher(s).replaceAll(java.util.regex.Matcher.quoteReplacement(repl));
    } catch (java.util.regex.PatternSyntaxException e) {
      throw new IllegalArgumentException("REGEXP_REPLACE 模式非法: " + pat, e);
    }
  }

  /** MySQL 逻辑 XOR（a XOR b）：任一 NULL 得 NULL；否则按 MySQL 真值规则
   * （数值非 0 / 布尔）异或，返回 1/0。 */
  public static Integer xor(Object a, Object b) {
    if (a == null || b == null) {
      return null;
    }
    return truthy(a) ^ truthy(b) ? 1 : 0;
  }

  private static boolean truthy(Object v) {
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (v instanceof String s) {
      try {
        return new BigDecimal(s.trim()).signum() != 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    throw new IllegalArgumentException("XOR 不支持操作数类型 " + v.getClass().getName());
  }

  /** TRY_CAST 目标类型的本地实现族：解析失败/溢出返回 NULL（DuckDB/SQL Server 语义）。
   * 输入按字符串/数值承载统一转 BigDecimal 解析。 */
  public static Integer tryInt(Object v) {
    BigDecimal d = tryNumber(v);
    try {
      return d == null ? null : d.setScale(0, java.math.RoundingMode.DOWN).intValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
  }

  public static Long tryBigint(Object v) {
    BigDecimal d = tryNumber(v);
    try {
      return d == null ? null : d.setScale(0, java.math.RoundingMode.DOWN).longValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
  }

  public static Double tryDouble(Object v) {
    BigDecimal d = tryNumber(v);
    return d == null ? null : d.doubleValue();
  }

  public static BigDecimal tryDecimal(Object v) {
    return tryNumber(v);
  }

  private static BigDecimal tryNumber(Object v) {
    if (v == null) {
      return null;
    }
    try {
      if (v instanceof BigDecimal d) {
        return d;
      }
      if (v instanceof Number n) {
        return new BigDecimal(n.toString());
      }
      if (v instanceof Boolean b) {
        return b ? BigDecimal.ONE : BigDecimal.ZERO;
      }
      String s = v.toString().trim();
      if (s.isEmpty()) {
        return null;
      }
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** NEXT_DAY(date, dow)（Oracle）：严格晚于 date 的第一个星期 dow。
   * dow 可为星期全名/缩写（大小写不敏感）或 1-7（1=星期日）。返回 DATE 承载。 */
  public static java.sql.Date nextDay(Object date, Object dow) {
    java.time.LocalDate d = toLocalDate(date);
    if (d == null || dow == null) {
      return null;
    }
    java.time.DayOfWeek target = parseDayOfWeek(dow);
    if (target == null) {
      throw new IllegalArgumentException("NEXT_DAY 无法识别星期: " + dow);
    }
    java.time.LocalDate next = d.plusDays(1);
    while (next.getDayOfWeek() != target) {
      next = next.plusDays(1);
    }
    return java.sql.Date.valueOf(next);
  }

  private static java.time.DayOfWeek parseDayOfWeek(Object dow) {
    if (dow instanceof Number n) {
      int v = n.intValue();
      // Oracle：1=星期日 … 7=星期六 → ISO DayOfWeek（1=星期一 … 7=星期日）
      return switch (v) {
        case 1 -> java.time.DayOfWeek.SUNDAY;
        case 2 -> java.time.DayOfWeek.MONDAY;
        case 3 -> java.time.DayOfWeek.TUESDAY;
        case 4 -> java.time.DayOfWeek.WEDNESDAY;
        case 5 -> java.time.DayOfWeek.THURSDAY;
        case 6 -> java.time.DayOfWeek.FRIDAY;
        case 7 -> java.time.DayOfWeek.SATURDAY;
        default -> null;
      };
    }
    String s = dow.toString().trim().toUpperCase();
    if (s.length() < 3) {
      return null;
    }
    for (java.time.DayOfWeek w : java.time.DayOfWeek.values()) {
      String full = w.name();
      // 全名前缀或 ≥3 字符缩写前缀（MON/MONDAY 等）
      if (full.startsWith(s) || full.startsWith(s.substring(0, 3))) {
        return w;
      }
    }
    return null;
  }

  /** TO_CHAR(date[, fmt])（Oracle）：单参形态 CAST 为 VARCHAR（时间列按 ISO
   * yyyy-MM-dd；真数值列字符串化）；双参按 Oracle 格式模型渲染常用子集：
   * YYYY/YY/MM/MON/MONTH/DD/DY/DAY/HH24/MI/SS 与字面文本，其余字符原样。
   * 注意 Integer 按引擎约定为 DATE（epoch days）承载，不作数值解读。 */
  public static String toChar(Object v, String fmt) {
    if (v == null) {
      return null;
    }
    if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
      return v.toString();
    }
    java.time.LocalDateTime dt = toWallDateTime(v);
    if (dt == null) {
      return v.toString();
    }
    if (fmt == null) {
      return dt.toLocalDate().toString();
    }
    StringBuilder b = new StringBuilder();
    String f = fmt.toUpperCase();
    int i = 0;
    while (i < f.length()) {
      String rest = f.substring(i);
      int take;
      String out;
      if (rest.startsWith("YYYY")) {
        out = String.format("%04d", dt.getYear());
        take = 4;
      } else if (rest.startsWith("YY")) {
        out = String.format("%02d", dt.getYear() % 100);
        take = 2;
      } else if (rest.startsWith("MONTH")) {
        out = " " + dt.getMonth().name();
        take = 5;
      } else if (rest.startsWith("MON")) {
        out = dt.getMonth().name().substring(0, 3);
        take = 3;
      } else if (rest.startsWith("MM")) {
        out = String.format("%02d", dt.getMonthValue());
        take = 2;
      } else if (rest.startsWith("DAY")) {
        out = " " + dt.getDayOfWeek().name();
        take = 3;
      } else if (rest.startsWith("DY")) {
        out = dt.getDayOfWeek().name().substring(0, 3);
        take = 2;
      } else if (rest.startsWith("DD")) {
        out = String.format("%02d", dt.getDayOfMonth());
        take = 2;
      } else if (rest.startsWith("HH24")) {
        out = String.format("%02d", dt.getHour());
        take = 4;
      } else if (rest.startsWith("MI")) {
        out = String.format("%02d", dt.getMinute());
        take = 2;
      } else if (rest.startsWith("SS")) {
        out = String.format("%02d", dt.getSecond());
        take = 2;
      } else {
        out = String.valueOf(fmt.charAt(i));
        take = 1;
      }
      b.append(out);
      i += take;
    }
    return b.toString();
  }

  /** 统一时间承载 → UTC 墙钟 LocalDateTime（Integer=epoch days，Number=epoch millis）。 */
  private static java.time.LocalDateTime toWallDateTime(Object v) {
    if (v instanceof Integer days) {
      return java.time.LocalDate.ofEpochDay(days).atStartOfDay();
    }
    if (v instanceof Number millis) {
      return java.time.Instant.ofEpochMilli(millis.longValue())
          .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
    }
    if (v instanceof Timestamp t) {
      return java.time.Instant.ofEpochMilli(t.getTime())
          .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
    }
    if (v instanceof LocalDateTime dt) {
      return dt;
    }
    if (v instanceof java.util.Date d) {
      return java.time.Instant.ofEpochMilli(d.getTime())
          .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
    }
    return null;
  }

  /** DATE_FORMAT(ts, fmt)（MySQL）：按 MySQL 格式符渲染（时间按 UTC 墙钟解释，
   * 与引擎承载约定一致）。支持常用子集：%Y %y %m %c %d %e %H %k %h %i %s %S
   * %T %p %M %b %j %W %a %D %f %%；其他 %x 原样保留。 */
  public static String dateFormat(Object v, String fmt) {
    if (v == null || fmt == null) {
      return null;
    }
    java.time.LocalDateTime dt = toWallDateTime(v);
    if (dt == null) {
      throw new IllegalArgumentException("DATE_FORMAT 不支持入参类型 " + v.getClass().getName());
    }
    StringBuilder b = new StringBuilder();
    int n = fmt.length();
    for (int i = 0; i < n; i++) {
      char c = fmt.charAt(i);
      if (c != '%' || i + 1 >= n) {
        b.append(c);
        continue;
      }
      char s = fmt.charAt(++i);
      switch (s) {
        case 'Y' -> b.append(String.format("%04d", dt.getYear()));
        case 'y' -> b.append(String.format("%02d", dt.getYear() % 100));
        case 'm' -> b.append(String.format("%02d", dt.getMonthValue()));
        case 'c' -> b.append(dt.getMonthValue());
        case 'd' -> b.append(String.format("%02d", dt.getDayOfMonth()));
        case 'e' -> b.append(dt.getDayOfMonth());
        case 'H', 'k' -> b.append(String.format(s == 'H' ? "%02d" : "%d", dt.getHour()));
        case 'h', 'l' -> {
          int h12 = dt.getHour() % 12;
          if (h12 == 0) {
            h12 = 12;
          }
          b.append(String.format(s == 'h' ? "%02d" : "%d", h12));
        }
        case 'i' -> b.append(String.format("%02d", dt.getMinute()));
        case 's', 'S' -> b.append(String.format("%02d", dt.getSecond()));
        case 'f' -> b.append(String.format("%06d", dt.getNano() / 1_000));
        case 'T' -> b.append(String.format("%02d:%02d:%02d",
            dt.getHour(), dt.getMinute(), dt.getSecond()));
        case 'p' -> b.append(dt.getHour() < 12 ? "AM" : "PM");
        case 'M' -> b.append(titleCase(dt.getMonth().name()));
        case 'b' -> b.append(titleCase(dt.getMonth().name().substring(0, 3)));
        case 'W' -> b.append(titleCase(dt.getDayOfWeek().name()));
        case 'a' -> b.append(titleCase(dt.getDayOfWeek().name().substring(0, 3)));
        case 'j' -> b.append(String.format("%03d", dt.getDayOfYear()));
        case 'D' -> b.append(dt.getDayOfMonth()).append(daySuffix(dt.getDayOfMonth()));
        case '%' -> b.append('%');
        default -> b.append('%').append(s);
      }
    }
    return b.toString();
  }

  /** MySQL 渲染约定：月/星期名标题式（January / Jan / Monday / Mon）。 */
  private static String titleCase(String upperName) {
    return upperName.charAt(0) + upperName.substring(1).toLowerCase(java.util.Locale.ROOT);
  }

  private static String daySuffix(int day) {
    return switch (day % 10) {
      case 1 -> day / 10 == 1 ? "th" : "st";
      case 2 -> day / 10 == 1 ? "th" : "nd";
      case 3 -> day / 10 == 1 ? "th" : "rd";
      default -> "th";
    };
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

package com.example.crossdb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.crossdb.SqlText.*;

/** 语句级 SQL 文本预处理：解析器不认识的方言子句/关键字在进入解析器之前改写为
 * 等价的标准语法形态（TOP n、LEFT SEMI/ANTI JOIN、WITH TIES、FETCH FIRST n
 * PERCENT、GROUPS 帧、REGEXP、STRAIGHT_JOIN、LISTAGG ON OVERFLOW、尾分号）；
 * 位运算与 DIV 的 token 级改写委托给 BitwiseDivRewrites。各改写仅在能安全
 * 识别边界时生效，否则原样返回交由解析器/校验器报真实错误。 */
final class SqlTextRewrites {
  private SqlTextRewrites() {}

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
    sql = BitwiseDivRewrites.preprocess(sql);
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
}

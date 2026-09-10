package com.example.crossdb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.example.crossdb.SqlText.*;

/** 语句级 SQL 文本预处理：解析器不认识的方言子句/关键字在进入解析器之前改写为
 * 等价的标准语法形态（TOP n [PERCENT|WITH TIES]、LEFT SEMI/ANTI JOIN、WITH TIES、
 * FETCH FIRST n PERCENT、GROUPS 帧、REGEXP、STRAIGHT_JOIN、LISTAGG ON OVERFLOW、
 * 尾分号）；位运算与 DIV 的 token 级改写委托给 BitwiseDivRewrites。各改写仅在能安全
 * 识别边界时生效，否则原样返回交由解析器/校验器报真实错误。 */
final class SqlTextRewrites {
  private SqlTextRewrites() {}

  private static final Pattern TOP = Pattern.compile(
      "(?is)^(\\s*SELECT\\s+(?:DISTINCT\\s+|ALL\\s+)?)TOP\\s*\\(?\\s*(\\d+(?:\\.\\d+)?)\\s*\\)?"
          + "(\\s*PERCENT\\b|\\s*WITH\\s+TIES\\b)?");
  private static final Pattern SEMI_ANTI_JOIN = Pattern.compile(
      "(?i)\\bLEFT\\s+(SEMI|ANTI)\\s+JOIN\\b");
  private static final Pattern FETCH_WITH_TIES = Pattern.compile(
      "(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+WITH\\s+TIES\\s*(;?)\\s*$");
  /** ON 条件扫描在深度 0 遇到这些子句关键字即止。 */
  private static final java.util.Set<String> CLAUSE_STOPPERS = java.util.Set.of(
      "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION",
      "INTERSECT", "EXCEPT", "JOIN", "LEFT", "RIGHT", "INNER", "CROSS", "FULL",
      "OUTER", "NATURAL", "ON", "APPLY");
  /** 语句级预处理入口：TOP n [PERCENT|WITH TIES] / LEFT SEMI・ANTI JOIN /
   * FETCH FIRST .. WITH TIES / LISTAGG ON OVERFLOW ERROR / REGEXP / STRAIGHT_JOIN /
   * 语句尾分号剥离，及本轮的方言族（DATE_DIFF 改名、STRUCT 点访问、LIST_CONTAINS、
   * (+) 外连接、CONNECT BY、GROUP BY ALL、* EXCLUDE、位运算/DIV、XOR）。各改写仅在
   * 能安全识别边界时生效，否则原样返回交由解析器/校验器报真实错误。 */
  static String preprocess(String sql) {
    return preprocess(sql, ColumnHints.EMPTY);
  }

  static String preprocess(String sql, ColumnHints hints) {
    sql = preprocessTrailingSemicolon(sql);
    sql = preprocessTop(sql);
    sql = preprocessSemiAntiJoin(sql);
    sql = preprocessFetchWithTies(sql);
    sql = preprocessListaggOverflowError(sql);
    sql = preprocessRegexp(sql);
    sql = preprocessStraightJoin(sql);
    sql = preprocessFetchPercent(sql);
    sql = preprocessGroupsFrame(sql);
    sql = preprocessDateDiffRename(sql);
    sql = preprocessStructDot(sql);
    sql = preprocessListContains(sql);
    sql = preprocessOracleOuterJoin(sql);
    sql = preprocessConnectBy(sql);
    sql = preprocessGroupByAll(sql);
    sql = preprocessExclude(sql, hints);
    sql = BitwiseDivRewrites.preprocess(sql);
    sql = preprocessXor(sql);
    return sql;
  }

  // ---------- DuckDB date_diff('unit', a, b) ----------

  private static final java.util.regex.Pattern DATE_DIFF_CALL =
      java.util.regex.Pattern.compile("(?i)\\bdate_diff\\s*(?=\\()");

  /** date_diff('unit', a, b)（DuckDB 单位前置形态）→ CROSSDB_TIMESTAMPDIFF。Calcite
   * 解析器对「末参为 DATE 字面量」的 date_diff 调用存在语法怪癖（直接报错），且
   * 函数未注册；按词改名后语义与 MySQL TIMESTAMPDIFF 一致（b - a 完整单位数）。 */
  private static String preprocessDateDiffRename(String sql) {
    if (!DATE_DIFF_CALL.matcher(sql).find()) {
      return sql;
    }
    return replaceLiveMatches(sql, DATE_DIFF_CALL, "CROSSDB_TIMESTAMPDIFF");
  }

  // ---------- DuckDB STRUCT 字面量点访问 {'k': e}.k ----------

  /** {'k1': e1[, 'k2': e2 ...]}.k1 → (e1)：键为引号字符串、点访问命中键时以
   * 对应值表达式替换（DuckDB STRUCT 字面量的常量折叠子集）。未命中/形态不符
   * 保留原样交解析器报真实错误。 */
  private static String preprocessStructDot(String sql) {
    int searchFrom = 0;
    while (true) {
      boolean[] live = liveMask(sql);
      int brace = -1;
      for (int i = searchFrom; i < sql.length(); i++) {
        if (live[i] && sql.charAt(i) == '{') {
          brace = i;
          break;
        }
      }
      if (brace < 0) {
        return sql;
      }
      int close = matchBrace(sql, live, brace);
      int after = close < 0 ? -1 : skipBlank(sql, live, close);
      if (after < 0 || after >= sql.length() || sql.charAt(after) != '.'
          || !live[after]) {
        searchFrom = brace + 1;
        continue;
      }
      int nameStart = skipBlank(sql, live, after + 1);
      if (nameStart >= sql.length() || !live[nameStart]
          || !isIdentStart(sql.charAt(nameStart))) {
        searchFrom = brace + 1;
        continue;
      }
      int nameEnd = nameStart + 1;
      while (nameEnd < sql.length() && live[nameEnd] && isIdentPart(sql.charAt(nameEnd))) {
        nameEnd++;
      }
      String field = sql.substring(nameStart, nameEnd);
      String value = structFieldValue(sql, live, brace + 1, close - 1, field);
      if (value == null) {
        searchFrom = brace + 1;
        continue;
      }
      sql = sql.substring(0, brace) + "(" + value + ")" + sql.substring(nameEnd);
      searchFrom = 0;
    }
  }

  /** 花括号匹配：返回 '}' 的位置（排他 +1 语义与 matchParen 对齐），不匹配 -1。 */
  private static int matchBrace(String sql, boolean[] live, int open) {
    int depth = 0;
    for (int i = open; i < sql.length(); i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i + 1;
      }
    }
    return -1;
  }

  /** STRUCT 字面量体内按键取值表达式：体为 {'k' : expr} 逗号分隔对；键大小写不敏感
   * 匹配；任一对形态不符返回 null。 */
  private static String structFieldValue(String sql, boolean[] live, int from, int to,
      String field) {
    List<int[]> commas = new ArrayList<>();
    int depth = 0;
    for (int i = from; i < to; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == ',' && depth == 0) {
        commas.add(new int[]{i, i + 1});
      }
    }
    List<int[]> pairs = new ArrayList<>();
    pairs.add(new int[]{from, 0});
    for (int[] c : commas) {
      pairs.add(new int[]{c[1], 0});
    }
    for (int i = 0; i < pairs.size(); i++) {
      pairs.get(i)[1] = i + 1 < pairs.size() ? commas.get(i)[0] : to;
    }
    for (int[] pair : pairs) {
      String text = sql.substring(pair[0], pair[1]).trim();
      java.util.regex.Matcher m = java.util.regex.Pattern.compile(
          "(?is)^(['\\\"])((?:.|\\n)*?)\\1\\s*:\\s*(.+)$").matcher(text);
      if (!m.matches()) {
        return null;
      }
      if (m.group(2).equalsIgnoreCase(field)) {
        return m.group(3).trim();
      }
    }
    return null;
  }

  // ---------- DuckDB LIST_CONTAINS(字面量数组, v) ----------

  /** LIST_CONTAINS([e1, e2, ...], v)（DuckDB）→ (v) IN (e1, e2, ...)：仅支持
   * 方括号数组字面量形态（引擎无 ARRAY 值类型透出，列数组/其余数组函数族保留
   * 原样交解析器报错）。 */
  private static String preprocessListContains(String sql) {
    int searchFrom = 0;
    while (true) {
      boolean[] live = liveMask(sql);
      int[] call = findLiveCall(sql, live, searchFrom, "LIST_CONTAINS");
      if (call == null) {
        return sql;
      }
      int open = call[1];
      int close = matchParen(sql, live, open) - 1;
      if (close < 0) {
        return sql;
      }
      int arrOpen = skipBlank(sql, live, open + 1);
      if (arrOpen >= sql.length() || !live[arrOpen] || sql.charAt(arrOpen) != '[') {
        searchFrom = call[1];
        continue;
      }
      int arrClose = -1;
      for (int i = arrOpen + 1; i <= close; i++) {
        if (live[i] && sql.charAt(i) == ']') {
          arrClose = i;
          break;
        }
      }
      if (arrClose < 0) {
        searchFrom = call[1];
        continue;
      }
      // 第二参 = 数组闭括号后的逗号之后到调用闭括号之前
      int comma = skipBlank(sql, live, arrClose + 1);
      if (comma >= sql.length() || !live[comma] || sql.charAt(comma) != ',') {
        searchFrom = call[1];
        continue;
      }
      String elements = sql.substring(arrOpen + 1, arrClose).trim();
      String value = sql.substring(comma + 1, close).trim();
      if (elements.isEmpty() || value.isEmpty()) {
        searchFrom = call[1];
        continue;
      }
      sql = sql.substring(0, call[0]) + "(" + value + ") IN (" + elements + ")"
          + sql.substring(close + 1);
      searchFrom = 0;
    }
  }

  /** 活字符词 word 后随 '(' 的调用起点 {词起点, '(' 位置}；无则 null。 */
  private static int[] findLiveCall(String sql, boolean[] live, int from, String word) {
    for (int i = from; i < sql.length(); i++) {
      if (!live[i] || !isIdentStart(sql.charAt(i))
          || !(wordEquals(sql, i, word) || wordEquals(sql, i, word.toLowerCase()))
          || !isWordStart(sql, live, i) || !isWordEnd(sql, live, i + word.length())) {
        continue;
      }
      int p = skipBlank(sql, live, i + word.length());
      if (p < sql.length() && live[p] && sql.charAt(p) == '(') {
        return new int[]{i, p};
      }
      i += word.length() - 1;
    }
    return null;
  }

  // ---------- Oracle (+) 旧式外连接 ----------

  /** 子句边界关键字：WHERE/CONNECT BY 段落扫描在这些深度 0 关键字处终止。 */
  private static final java.util.Set<String> TAIL_STOPPERS = java.util.Set.of(
      "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION",
      "INTERSECT", "EXCEPT", "MINUS", "WINDOW", "CONNECT", "START");

  /** Oracle 旧式外连接 {@code WHERE a.x = b.y(+)} → {@code FROM a LEFT JOIN b ON
   * a.x = b.y}：仅支持双表逗号 FROM、(+) 全部落在同一受限表的简单等值条件上
   * （复合键多条件允许）；其余形态保留原样交解析器报错。 */
  private static String preprocessOracleOuterJoin(String sql) {
    boolean[] live = liveMask(sql);
    int plus = indexOfLive(sql, live, "(+)");
    if (plus < 0) {
      return sql;
    }
    int n = sql.length();
    // 顶层形态：SELECT ... FROM <item1>, <item2> WHERE ...（FROM 之前无深度 0 逗号）
    if (!sql.trim().toUpperCase().startsWith("SELECT")) {
      return sql;
    }
    int fromIdx = topLevelFrom(sql, live, n);
    if (fromIdx < 0) {
      return sql;
    }
    int whereIdx = -1;
    int depth = 0;
    int prevLive = -1;
    for (int i = fromIdx + 4; i < n; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        String w = wordAt(sql, i).toUpperCase();
        if (w.equals("WHERE")) {
          whereIdx = i;
          break;
        }
        if (w.equals("GROUP") || w.equals("ORDER") || w.equals("HAVING")
            || w.equals("LIMIT") || w.equals("UNION") || w.equals("WINDOW")) {
          return sql;   // 无 WHERE：(+) 不可能出现（不存在其它合法位置）
        }
        i += w.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    if (whereIdx < 0) {
      return sql;
    }
    // FROM 双表项切分（深度 0 逗号，且各项为普通 [schema.]table [AS] alias）
    List<String> items = splitTopLevel(sql.substring(fromIdx + 4, whereIdx).trim(),
        live, fromIdx + 4);
    if (items == null || items.size() != 2
        || items.stream().anyMatch(s -> s.toUpperCase().contains("JOIN"))) {
      return sql;
    }
    String[][] parts = new String[2][];
    for (int i = 0; i < 2; i++) {
      parts[i] = tableRef(items.get(i));
      if (parts[i] == null) {
        return sql;
      }
    }
    // WHERE 段终点：深度 0 的尾部子句关键字
    int whereEnd = n;
    depth = 0;
    prevLive = -1;
    for (int i = whereIdx + 5; i < n; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        String w = wordAt(sql, i).toUpperCase();
        if (TAIL_STOPPERS.contains(w)) {
          whereEnd = i;
          break;
        }
        i += w.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    // WHERE 顶层 AND 切分
    List<int[]> ands = topLevelKeywordSpans(sql, live, whereIdx + 5, whereEnd, "AND");
    List<int[]> conjuncts = new ArrayList<>();
    int segStart = whereIdx + 5;
    for (int[] and : ands) {
      conjuncts.add(new int[]{segStart, and[0]});
      segStart = and[1];
    }
    conjuncts.add(new int[]{segStart, whereEnd});
    // (+) 条件归类
    List<String> onConds = new ArrayList<>();
    String deficient = null;
    int preservedIdx = -1;
    StringBuilder rest = new StringBuilder();
    for (int[] seg : conjuncts) {
      String text = sql.substring(seg[0], seg[1]).trim();
      if (text.isEmpty()) {
        return sql;
      }
      boolean[] segLive = liveMask(text);
      if (indexOfLive(text, segLive, "(+)") >= 0) {
        int eq = topLevelEquals(text, segLive);
        if (eq < 0) {
          return sql;
        }
        String left = text.substring(0, eq).trim();
        String right = text.substring(eq + 1).trim();
        String stripped;
        String defCol;
        if (left.endsWith("(+)")) {
          defCol = left.substring(0, left.length() - 3).trim();
          stripped = defCol + " = " + right;
        } else if (right.endsWith("(+)")) {
          defCol = right.substring(0, right.length() - 3).trim();
          stripped = left + " = " + defCol;
        } else {
          return sql;   // (+) 不在等值某一侧的末尾：形态不支持
        }
        String qual = qualifierOf(defCol);
        if (qual == null) {
          return sql;
        }
        if (deficient == null) {
          deficient = qual;
        } else if (!deficient.equalsIgnoreCase(qual)) {
          return sql;   // (+) 落在两张表：不支持
        }
        onConds.add(stripped);
      } else {
        if (rest.length() > 0) {
          rest.append(" AND ");
        }
        rest.append(text);
      }
    }
    if (onConds.isEmpty() || deficient == null) {
      return sql;
    }
    int defIdx = -1;
    for (int i = 0; i < 2; i++) {
      if (parts[i][1] != null && parts[i][1].equalsIgnoreCase(deficient)) {
        defIdx = i;
      }
    }
    if (defIdx < 0) {
      return sql;
    }
    preservedIdx = 1 - defIdx;
    // 剩余文本中不允许再出现 (+)（ORDER BY 等）
    String tail = sql.substring(whereEnd);
    if (indexOfLive(tail, liveMask(tail), "(+)") >= 0) {
      return sql;
    }
    String sel = sql.substring(0, fromIdx);
    StringBuilder out = new StringBuilder(sel).append("FROM ")
        .append(items.get(preservedIdx)).append(" LEFT JOIN ").append(items.get(defIdx))
        .append(" ON ").append(String.join(" AND ", onConds));
    if (rest.length() > 0) {
      out.append(" WHERE ").append(rest);
    }
    return out.append(' ').append(tail.trim()).toString();
  }

  /** 活字符子串首次出现位置；无则 -1。 */
  private static int indexOfLive(String sql, boolean[] live, String needle) {
    outer:
    for (int i = 0; i + needle.length() <= sql.length(); i++) {
      if (!live[i]) {
        continue;
      }
      for (int j = 0; j < needle.length(); j++) {
        if (!live[i + j] || sql.charAt(i + j) != needle.charAt(j)) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** 文本内深度 0 的单等号位置；无则 -1。 */
  private static int topLevelEquals(String text, boolean[] live) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      if (!live[i]) {
        continue;
      }
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == '=' && depth == 0
          && (i + 1 >= text.length() || text.charAt(i + 1) != '=')
          && (i == 0 || text.charAt(i - 1) != '<' && text.charAt(i - 1) != '>'
              && text.charAt(i - 1) != '!' && text.charAt(i - 1) != '=')) {
        return i;
      }
    }
    return -1;
  }

  /** 列引用的限定符（a.b 的 a）；裸列名返回 null。 */
  private static String qualifierOf(String colRef) {
    if (!colRef.matches("(?i)[a-z_][a-z0-9_$]*(\\s*\\.\\s*[a-z_][a-z0-9_$]*)+")) {
      return null;
    }
    return colRef.substring(0, colRef.indexOf('.')).trim();
  }

  /** 表引用解析：{完整项, 别名|null}；形态不符返回 null。 */
  private static String[] tableRef(String item) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
        "(?i)^([a-z_][a-z0-9_$]*(\\s*\\.\\s*[a-z_][a-z0-9_$]*)*)"
        + "(\\s+(as\\s+)?([a-z_][a-z0-9_$]*))?$").matcher(item.trim());
    if (!m.matches()) {
      return null;
    }
    return new String[]{m.group(1), m.group(5)};
  }

  // ---------- Oracle CONNECT BY 层次查询 ----------

  /** CONNECT BY 层次查询（Oracle）→ 递归 CTE 等价改写：
   * {@code SELECT .. FROM t WHERE f START WITH s CONNECT BY NOCYCLE PRIOR p = c}
   * →
   * {@code WITH crossdb_cte AS (SELECT q.*, 1 AS level FROM t q WHERE s
   * UNION ALL SELECT q.*, level+1 FROM t q JOIN crossdb_cte c ON q.c = c.p
   * WHERE c.level < 100) SELECT .. FROM crossdb_cte q WHERE f}。
   * LEVEL 伪列由 CTE 的 {@code level} 列承载（外层 SELECT/WHERE/ORDER BY 对
   * LEVEL 的裸引用或别名限定引用均自然解析；基表自身含 level 列时与之冲突，
   * 该形态保留原样交校验器报真实错误）。
   * 边界：单表 FROM（可带别名）、连接条件为 PRIOR 标记的裸/限定列简单等值、
   * WHERE 为层次后过滤（Oracle 语义）；NOCYCLE 由 level 上限防护（无环数据不受
   * 影响）。其余形态保留原样交解析器报错。 */
  private static String preprocessConnectBy(String sql) {
    boolean[] live = liveMask(sql);
    int[] connect = findTopLevelKeyword(sql, live, sql.length(), "CONNECT BY");
    if (connect == null) {
      return sql;
    }
    if (!sql.trim().toUpperCase().startsWith("SELECT")) {
      return sql;
    }
    int n = sql.length();
    int cbCondStart = connect[1];
    boolean nocycle = false;
    int p = skipBlank(sql, live, cbCondStart);
    if (keywordAt(sql, live, p, "NOCYCLE")) {
      nocycle = true;
      p = skipBlank(sql, live, p + "NOCYCLE".length());
    }
    if (keywordAt(sql, live, p, "ORDER SIBLINGS BY")) {
      return sql;
    }
    // 连接条件：PRIOR a = b 或 a = PRIOR b（a/b 为可限定列名）
    java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
        "(?i)(prior\\s+)?([a-z_][a-z0-9_$]*(\\s*\\.\\s*[a-z_][a-z0-9_$]*)*)"
        + "\\s*=\\s*(prior\\s+)?([a-z_][a-z0-9_$]*(\\s*\\.\\s*[a-z_][a-z0-9_$]*)*)").matcher(
            sql.substring(p));
    if (!cm.find() || cm.start() != 0) {
      return sql;
    }
    String parentCol = cm.group(2);
    String childCol = cm.group(5);
    if (cm.group(1) == null && cm.group(4) == null) {
      return sql;   // 无 PRIOR 标记：不支持
    }
    if (cm.group(1) == null) {
      // a = PRIOR b：父列为 b
      parentCol = cm.group(5);
      childCol = cm.group(2);
    }
    int condEnd = p + cm.end();
    // START WITH（可在 CONNECT BY 前或后）
    int[] start = findTopLevelKeyword(sql, live, n, "START WITH");
    String startCond = null;
    int startSpanBegin = -1;
    int startSpanEnd = -1;
    if (start != null) {
      startSpanBegin = start[1];
      if (start[0] > connect[0]) {
        // START WITH 在 CONNECT BY 之后：条件止于尾部子句关键字
        startSpanEnd = clauseBoundary(sql, live, startSpanBegin, n);
      } else {
        // START WITH 在前：条件止于 CONNECT BY
        startSpanEnd = connect[0];
      }
      startCond = sql.substring(startSpanBegin, startSpanEnd).trim();
      if (startCond.isEmpty()) {
        return sql;
      }
    }
    // 尾段：CONNECT BY 条件之后（去除可能的 START WITH 段）
    int tailStart = condEnd;
    if (start != null && start[0] > connect[0]) {
      tailStart = Math.max(tailStart, startSpanEnd);
    }
    String tail = sql.substring(tailStart).trim();
    // FROM 单表项与可选 WHERE 过滤
    int hierStart = start == null || connect[0] < start[0] ? connect[0] : start[0];
    int fromIdx = topLevelFrom(sql, live, hierStart);
    if (fromIdx < 0) {
      return sql;
    }
    int itemEnd = fromItemEnd(sql, live, fromIdx + 4);
    if (itemEnd < 0) {
      return sql;
    }
    String fromItem = sql.substring(fromIdx + 4, itemEnd).trim();
    if (fromItem.contains(",") || fromItem.toUpperCase().contains("JOIN")
        || fromItem.contains("(")) {
      return sql;
    }
    String[] ref = tableRef(fromItem);
    if (ref == null) {
      return sql;
    }
    String alias = ref[1];
    String qual = alias != null ? alias : ref[0].substring(ref[0].lastIndexOf('.') + 1);
    // WHERE 过滤段（介于 FROM 项之后与层次子句之前；Oracle 语义为层次后过滤）
    String filter = null;
    int[] where = findTopLevelKeyword(sql, live, hierStart, "WHERE");
    if (where != null) {
      filter = sql.substring(where[1], hierStart).trim();
      if (filter.isEmpty()) {
        return sql;
      }
    } else if (hierStart > itemEnd) {
      String between = sql.substring(itemEnd, hierStart).trim();
      if (!between.isEmpty()) {
        return sql;   // FROM 与层次子句之间有无法识别的内容
      }
    }
    String sel = sql.substring(0, fromIdx);
    String bareParent = parentCol.substring(parentCol.lastIndexOf('.') + 1).trim();
    String bareChild = childCol.substring(childCol.lastIndexOf('.') + 1).trim();
    StringBuilder cte = new StringBuilder("WITH RECURSIVE crossdb_cte AS (SELECT ").append(qual)
        .append(".*, 1 AS level FROM ").append(fromItem);
    if (startCond != null) {
      cte.append(" WHERE ").append(startCond);
    }
    cte.append(" UNION ALL SELECT ").append(qual).append(".*, level + 1 FROM ")
        .append(fromItem).append(" JOIN crossdb_cte ON ").append(qual).append('.')
        .append(bareChild).append(" = crossdb_cte.").append(bareParent)
        .append(" WHERE crossdb_cte.level < 100")
        .append(") ").append(sel.replaceFirst("(?is)^\\s*SELECT\\s+", "SELECT "))
        .append(" FROM crossdb_cte").append(alias == null ? "" : " " + alias);
    if (filter != null) {
      cte.append(" WHERE ").append(filter);
    }
    if (!tail.isEmpty()) {
      cte.append(' ').append(tail);
    }
    return cte.toString();
  }

  /** 深度 0 关键字（可含空格）位置 {起点, 结束+1}；无则 null。 */
  private static int[] findTopLevelKeyword(String sql, boolean[] live, int limit,
      String keyword) {
    String[] words = keyword.toUpperCase().split("\\s+");
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
        if (keywordAt(sql, live, i, keyword)) {
          return new int[]{i, i + keyword.length()};
        }
        String w = wordAt(sql, i);
        i += w.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return null;
  }

  /** 自 from 起深度 0 的下一个子句边界关键字位置。 */
  private static int clauseBoundary(String sql, boolean[] live, int from, int limit) {
    int depth = 0;
    int prevLive = -1;
    for (int i = from; i < limit; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        String w = wordAt(sql, i).toUpperCase();
        if (TAIL_STOPPERS.contains(w)) {
          return i;
        }
        i += w.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return limit;
  }

  /** FROM 后第一个表项的终点（含别名），无法识别返回 -1。 */
  private static int fromItemEnd(String sql, boolean[] live, int from) {
    int n = sql.length();
    int i = skipBlank(sql, live, from);
    if (i >= n || !live[i] || !isIdentStart(sql.charAt(i))) {
      return -1;
    }
    while (i < n && live[i] && (isIdentPart(sql.charAt(i)) || sql.charAt(i) == '.')) {
      i++;
    }
    int a = skipBlank(sql, live, i);
    if (a < n && live[a] && isIdentStart(sql.charAt(a))) {
      String w = wordAt(sql, a).toUpperCase();
      if (w.equals("AS")) {
        a = skipBlank(sql, live, a + 2);
        if (a < n && live[a] && isIdentStart(sql.charAt(a))) {
          i = a;
          while (i < n && live[i] && isIdentPart(sql.charAt(i))) {
            i++;
          }
        }
      } else if (!TAIL_STOPPERS.contains(w) && !w.equals("WHERE")) {
        i = a;
        while (i < n && live[i] && isIdentPart(sql.charAt(i))) {
          i++;
        }
      }
    }
    return i;
  }

  /** 深度 0 AND 关键字全部出现位置 {起点, 终点+1}（按序）。 */
  private static List<int[]> topLevelKeywordSpans(String sql, boolean[] live, int from,
      int limit, String keyword) {
    List<int[]> out = new ArrayList<>();
    int depth = 0;
    int prevLive = -1;
    for (int i = from; i < limit; i++) {
      if (!live[i]) {
        continue;
      }
      char c = sql.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0 && isIdentStart(c) && prevLive != '.') {
        if (keywordAt(sql, live, i, keyword)) {
          out.add(new int[]{i, i + keyword.length()});
          i += keyword.length() - 1;
          continue;
        }
        String w = wordAt(sql, i);
        i += w.length() - 1;
      }
      if (!Character.isWhitespace(c)) {
        prevLive = c;
      }
    }
    return out;
  }

  // ---------- DuckDB/Snowflake GROUP BY ALL ----------

  /** GROUP BY ALL 智能分组（DuckDB/Snowflake，解析器不支持）→ 展开为显式分组键：
   * 取 select 清单中不含聚合/窗口调用且不含子查询的项作键；全部项均为聚合时
   * 整个子句删除（等价无分组）。仅顶层平铺 SELECT；无法安全改写时原样保留。 */
  private static String preprocessGroupByAll(String sql) {
    boolean[] live = liveMask(sql);
    int[] gba = findTopLevelKeyword(sql, live, sql.length(), "GROUP BY ALL");
    if (gba == null || !sql.trim().toUpperCase().startsWith("SELECT")) {
      return sql;
    }
    if (hasTopLevelAny(sql, live, gba[0], "UNION", "INTERSECT", "EXCEPT")) {
      return sql;
    }
    int fromIdx = topLevelFrom(sql, live, gba[0]);
    if (fromIdx < 0) {
      return sql;
    }
    String head = sql.substring(0, fromIdx);
    if (head.matches("(?is)^\\s*SELECT\\s+DISTINCT\\b.*")
        || head.matches("(?is)^\\s*SELECT\\s+ALL\\b.*")) {
      return sql;
    }
    java.util.regex.Matcher selM = java.util.regex.Pattern.compile(
        "(?is)^\\s*SELECT\\s+").matcher(head);
    if (!selM.find()) {
      return sql;
    }
    List<String> items = splitTopLevel(head.substring(selM.end()).stripTrailing(),
        live, selM.end());
    if (items == null) {
      return sql;
    }
    List<String> keys = new ArrayList<>();
    for (String item : items) {
      if (!containsAggregateCall(item)) {
        keys.add(item.trim());
      }
    }
    if (keys.isEmpty()) {
      // 全聚合项：删除 GROUP BY ALL 子句（保留前后空隙各一空格）
      return sql.substring(0, gba[0]) + sql.substring(gba[1]);
    }
    return sql.substring(0, gba[0]) + "GROUP BY " + String.join(", ", keys)
        + sql.substring(gba[1]);
  }

  /** 判断 select 项文本是否含聚合/窗口调用（或子查询等不可作键形态）。保守判定：
   * 出现任何聚合名后随 '('、OVER (、或 SELECT 词即视为含。 */
  private static boolean containsAggregateCall(String item) {
    boolean[] live = liveMask(item);
    int n = item.length();
    for (int i = 0; i < n; i++) {
      if (!live[i] || !isIdentStart(item.charAt(i))) {
        continue;
      }
      String w = wordAt(item, i).toUpperCase();
      if (w.equals("SELECT")) {
        return true;
      }
      int j = skipBlank(item, live, i + w.length());
      if (j < n && live[j] && item.charAt(j) == '(' && AGG_NAMES.contains(w)) {
        return true;
      }
      if (w.equals("OVER")) {
        return true;
      }
      i += w.length() - 1;
    }
    return false;
  }

  /** 聚合函数名集合（按词整体匹配用）。 */
  private static final java.util.Set<String> AGG_NAMES = java.util.Set.of(
      "COUNT", "SUM", "AVG", "MIN", "MAX", "MEDIAN", "LISTAGG", "STRING_AGG",
      "GROUP_CONCAT", "ARRAY_AGG", "BOOL_AND", "BOOL_OR", "EVERY", "ANY_VALUE",
      "MODE", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VAR_POP", "VAR_SAMP",
      "PERCENTILE_CONT", "PERCENTILE_DISC", "BIT_AND", "BIT_OR", "BIT_XOR",
      "ARG_MIN", "ARG_MAX", "APPROX_DISTINCT", "APPROX_COUNT_DISTINCT");

  // ---------- DuckDB SELECT * EXCLUDE (cols) ----------

  /** SELECT * EXCLUDE (c1[, c2..])（DuckDB 列排除，解析器不支持）→ 按列目录展开为
   * 显式列清单（保留其余列的目录顺序）。仅支持顶层单表 FROM；表不在目录或形态
   * 不符时保留原样。 */
  private static String preprocessExclude(String sql, ColumnHints hints) {
    boolean[] live = liveMask(sql);
    int[] exc = findTopLevelKeyword(sql, live, sql.length(), "EXCLUDE");
    if (exc == null || !sql.trim().toUpperCase().startsWith("SELECT")) {
      return sql;
    }
    // EXCLUDE 之前须为 SELECT * / SELECT DISTINCT * 前缀
    int star = BitwiseDivRewrites.skipBlankBack(sql, live, exc[0] - 1);
    if (star < 0 || !live[star] || sql.charAt(star) != '*') {
      return sql;
    }
    String head = sql.substring(0, star);
    if (!head.matches("(?is)^\\s*SELECT\\s+(DISTINCT\\s+)?$")) {
      return sql;
    }
    int open = skipBlank(sql, live, exc[1]);
    if (open >= sql.length() || !live[open] || sql.charAt(open) != '(') {
      return sql;
    }
    int close = matchParen(sql, live, open) - 1;
    if (close < 0) {
      return sql;
    }
    List<String> cols = new ArrayList<>();
    for (String c : sql.substring(open + 1, close).split(",")) {
      if (!c.trim().matches("(?i)[a-z_][a-z0-9_$]*")) {
        return sql;
      }
      cols.add(c.trim());
    }
    int fromIdx = topLevelFrom(sql, live, sql.length());
    if (fromIdx < 0 || fromIdx != skipBlank(sql, live, close + 1)) {
      return sql;   // 排除清单后必须紧跟 FROM
    }
    int itemEnd = fromItemEnd(sql, live, fromIdx + 4);
    if (itemEnd < 0) {
      return sql;
    }
    String fromItem = sql.substring(fromIdx + 4, itemEnd).trim();
    if (fromItem.contains(",") || fromItem.toUpperCase().contains("JOIN")) {
      return sql;
    }
    String[] ref = tableRef(fromItem);
    if (ref == null) {
      return sql;
    }
    List<String> all = hints.columnsOf(ref[0]);
    if (all == null || all.isEmpty()) {
      return sql;
    }
    java.util.Set<String> drop = new java.util.HashSet<>();
    for (String c : cols) {
      drop.add(c.toLowerCase(java.util.Locale.ROOT));
    }
    List<String> kept = new ArrayList<>();
    for (String c : all) {
      if (!drop.remove(c.toLowerCase(java.util.Locale.ROOT))) {
        kept.add(c);
      }
    }
    if (!drop.isEmpty()) {
      return sql;   // 排除列在表中不存在：保留原样报错
    }
    // head 已含 "SELECT [DISTINCT ]" 前缀（止于 * 之前），直接续以保留列清单
    return sql.substring(0, head.length()) + String.join(", ", kept)
        + sql.substring(close + 1);
  }

  // ---------- MySQL XOR 逻辑操作符 ----------

  /** MySQL 逻辑 XOR（优先级介于 OR 与 AND 之间，解析器不支持）→ CROSSDB_XOR(L, R)。
   * 操作数为「NOT 前缀 + 算术链 [比较符 算术链]」经 AND 连接的布尔链（IS/LIKE/
   * IN/BETWEEN/RLIKE 形态不支持，保留原样报错）。最左优先，改写后重扫。 */
  private static String preprocessXor(String sql) {
    int searchFrom = 0;
    while (true) {
      boolean[] live = liveMask(sql);
      int xor = -1;
      for (int i = searchFrom; i < sql.length(); i++) {
        if (live[i] && (sql.charAt(i) == 'x' || sql.charAt(i) == 'X')
            && wordEquals(sql, i, "XOR") && isWordStart(sql, live, i)
            && isWordEnd(sql, live, i + 3)) {
          xor = i;
          break;
        }
      }
      if (xor < 0) {
        return sql;
      }
      int ls = boolOperandLeft(sql, live, xor);
      int re = ls < 0 ? -1 : boolOperandRight(sql, live, xor + 3);
      if (re < 0) {
        searchFrom = xor + 3;
        continue;
      }
      sql = sql.substring(0, ls) + "CROSSDB_XOR(" + sql.substring(ls, xor).trim() + ", "
          + sql.substring(xor + 3, re).trim() + ")" + sql.substring(re);
      searchFrom = 0;
    }
  }

  /** 布尔操作数左向扫描（XOR 左侧）：NOT/AND/比较连接的算术链，返回操作数起点。 */
  private static int boolOperandLeft(String sql, boolean[] live, int opPos) {
    int end = BitwiseDivRewrites.skipBlankBack(sql, live, opPos - 1);
    int start = boolChainLeft(sql, live, end);
    if (start < 0) {
      return -1;
    }
    while (true) {
      // NOT 前缀并入
      int before = BitwiseDivRewrites.skipBlankBack(sql, live, start - 1);
      if (before >= 0 && keywordAt(sql, live, before - 2, "NOT")
          && isWordStart(sql, live, before - 2)) {
        int notStart = before - 2;
        start = notStart;
        continue;
      }
      // AND 继续向左
      if (before >= 0 && keywordAt(sql, live, before - 2, "AND")
          && isWordStart(sql, live, before - 2)) {
        int prevEnd = BitwiseDivRewrites.skipBlankBack(sql, live, before - 4);
        int prev = boolChainLeft(sql, live, prevEnd);
        if (prev < 0) {
          return start;
        }
        start = prev;
        continue;
      }
      return start;
    }
  }

  /** 布尔操作数右向扫描（XOR 右侧）：返回操作数终点（排他）。 */
  private static int boolOperandRight(String sql, boolean[] live, int from) {
    int pos = skipBlank(sql, live, from);
    pos = boolChainRight(sql, live, pos);
    if (pos < 0) {
      return -1;
    }
    while (true) {
      int j = skipBlank(sql, live, pos);
      if (keywordAt(sql, live, j, "AND") && isWordStart(sql, live, j)) {
        int k = skipBlank(sql, live, j + 3);
        while (keywordAt(sql, live, k, "NOT") && isWordStart(sql, live, k)) {
          k = skipBlank(sql, live, k + 3);
        }
        int next = boolChainRight(sql, live, k);
        if (next < 0) {
          return -1;
        }
        pos = next;
      } else {
        return pos;
      }
    }
  }

  /** 单个比较链右向扫描：算术链 [比较符 算术链]，返回终点（排他）。
   * NULL 字面量是合法布尔原子（EXPR_KEYWORDS 排除项的例外，MySQL 真值语义）。 */
  private static int boolChainRight(String sql, boolean[] live, int start) {
    if (start >= 0 && start < sql.length() && live[start]
        && keywordAt(sql, live, start, "NULL")) {
      return start + 4;
    }
    int pos = BitwiseDivRewrites.scanOperandRight(sql, live, start,
        BitwiseDivRewrites.ARITH_CONN);
    if (pos < 0) {
      return -1;
    }
    int j = skipBlank(sql, live, pos);
    int[] cmp = comparisonAt(sql, live, j);
    if (cmp == null) {
      return pos;
    }
    int next = boolChainRight(sql, live, cmp[1]);
    return next < 0 ? pos : next;
  }

  /** 单个比较链左向扫描：start 为链尾最后字符，返回链起点。NULL 字面量同上例外。 */
  private static int boolChainLeft(String sql, boolean[] live, int end) {
    if (end >= 0 && end < sql.length() && live[end]
        && keywordAt(sql, live, end - 3, "NULL") && isWordStart(sql, live, end - 3)) {
      return end - 3;
    }
    int start = BitwiseDivRewrites.scanOperandLeft(sql, live, end + 1,
        BitwiseDivRewrites.ARITH_CONN);
    if (start < 0) {
      return -1;
    }
    // 左侧是否紧邻比较符（如 a = 1 的 a）：向左找「比较符 + 算术链」
    int before = BitwiseDivRewrites.skipBlankBack(sql, live, start - 1);
    int[] cmp = comparisonAtLeft(sql, live, before);
    if (cmp == null) {
      return start;
    }
    int prevEnd = BitwiseDivRewrites.skipBlankBack(sql, live, cmp[0] - 1);
    if (prevEnd < 0) {
      return start;
    }
    int prev = boolChainLeft(sql, live, prevEnd);
    return prev < 0 ? start : prev;
  }

  /** j 处（活字符）比较操作符：返回 {起点, 终点+1}；非比较符返回 null。 */
  private static int[] comparisonAt(String sql, boolean[] live, int j) {
    if (j < 0 || j >= sql.length() || !live[j]) {
      return null;
    }
    char c = sql.charAt(j);
    return switch (c) {
      case '=' -> new int[]{j, j + 1};
      case '<' -> j + 1 < sql.length() && live[j + 1]
          && (sql.charAt(j + 1) == '=' || sql.charAt(j + 1) == '>')
              ? new int[]{j, j + 2} : new int[]{j, j + 1};
      case '>' -> j + 1 < sql.length() && live[j + 1] && sql.charAt(j + 1) == '='
          ? new int[]{j, j + 2} : new int[]{j, j + 1};
      case '!' -> j + 1 < sql.length() && live[j + 1] && sql.charAt(j + 1) == '='
          ? new int[]{j, j + 2} : null;
      default -> null;
    };
  }

  /** j 处（活字符，含 j）向左的比较操作符终点前缀：返回 {起点, j+1}。 */
  private static int[] comparisonAtLeft(String sql, boolean[] live, int j) {
    if (j < 0 || !live[j]) {
      return null;
    }
    char c = sql.charAt(j);
    if (c == '=') {
      if (j > 0 && live[j - 1]
          && (sql.charAt(j - 1) == '<' || sql.charAt(j - 1) == '>'
              || sql.charAt(j - 1) == '!')) {
        return new int[]{j - 1, j + 1};   // <= >= != 双字符操作符
      }
      return new int[]{j, j + 1};
    }
    if (c == '<' || c == '>') {
      return new int[]{j, j + 1};
    }
    return null;
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
   * 无 ORDER BY 任取 n 行，与 T-SQL TOP 一致）。T-SQL 扩展形态顺次归一到标准改写族：
   * TOP n PERCENT → FETCH FIRST n PERCENT ROWS ONLY（后续 preprocessFetchPercent
   * 按 CEILING(n% × 总行数) 取前缀）；TOP n WITH TIES → FETCH FIRST n ROWS WITH
   * TIES（后续 preprocessFetchWithTies 按前 n 去重键组展开）。不匹配则原样返回。 */
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
    String tail = m.group(3) == null ? " ROWS ONLY"
        : m.group(3).trim().regionMatches(true, 0, "PERCENT", 0, 7)
            ? " PERCENT ROWS ONLY" : " ROWS WITH TIES";
    return m.group(1) + trimmed + " FETCH FIRST " + m.group(2) + tail + semi;
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

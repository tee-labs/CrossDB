package com.example.crossdb;

import java.util.ArrayList;
import java.util.List;

import static com.example.crossdb.SqlText.*;

/** 位运算 / DIV 操作符的 token 级文本改写（MySQL / PostgreSQL 方言）。 */
final class BitwiseDivRewrites {
  private BitwiseDivRewrites() {}

  /** 表达式边界关键字：作为操作数原子扫描的硬边界出现（出现在操作数位置即判定
   * 无法安全改写，跳过该处改写交由解析器报真实错误）。 */
  /** 后随 '(' 时按函数调用原子处理的类型/转换前缀（EXPR_KEYWORDS 的例外）。 */
  private static final java.util.Set<String> FUNC_PREFIXES = java.util.Set.of(
      "CAST", "DATE", "TIMESTAMP", "TIME", "INTERVAL");

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
  static final java.util.Set<String> ARITH_CONN =
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
  static String preprocess(String sql) {
    boolean[] live = liveMask(sql);
    if (!hasBitwiseDivOp(sql, live)) {
      return sql;
    }
    // MATCH_RECOGNIZE 的 PATTERN (...) 内 | 是模式或语法——不是位运算；按段切分，
    // 仅对模式段之外的文本做改写
    List<int[]> spans = matchRecognizePatternSpans(sql, live);
    if (spans == null) {
      return rewriteAll(sql);
    }
    StringBuilder out = new StringBuilder(sql.length());
    int pos = 0;
    for (int[] span : spans) {
      out.append(rewriteAll(sql.substring(pos, span[0]))).append(sql, span[0], span[1]);
      pos = span[1];
    }
    out.append(rewriteAll(sql.substring(pos)));
    return out.toString();
  }

  /** 全语句位运算/DIV 改写（无 MATCH_RECOGNIZE 时的原路径）。 */
  private static String rewriteAll(String sql) {
    sql = rewriteBitNot(sql);
    sql = rewriteBinaryOp(sql, "DIV", "CROSSDB_IDIV", java.util.Set.of());
    sql = rewriteBinaryOp(sql, "<<", "CROSSDB_SHL", ARITH_CONN);
    sql = rewriteBinaryOp(sql, ">>", "CROSSDB_SHR", ARITH_CONN);
    sql = rewriteBinaryOp(sql, "&", "CROSSDB_BITAND", ARITH_CONN);
    sql = rewriteBinaryOp(sql, "^", "CROSSDB_BITXOR", ARITH_CONN);
    sql = rewriteBinaryOp(sql, "|", "CROSSDB_BITOR", ARITH_CONN);
    return sql;
  }

  /** 语句含 MATCH_RECOGNIZE 时返回各 PATTERN (...) 段的 {起, 止}（含括号，按序）；
   * 无 MATCH_RECOGNIZE 返回 null（调用方走原路径）。 */
  private static List<int[]> matchRecognizePatternSpans(String sql, boolean[] live) {
    boolean hasMr = false;
    for (int i = 0; i < sql.length() && !hasMr; i++) {
      if (live[i] && (sql.charAt(i) == 'm' || sql.charAt(i) == 'M')
          && wordEquals(sql, i, "MATCH_RECOGNIZE") && isWordStart(sql, live, i)
          && isWordEnd(sql, live, i + "MATCH_RECOGNIZE".length())) {
        hasMr = true;
      }
    }
    if (!hasMr) {
      return null;
    }
    List<int[]> spans = new ArrayList<>();
    for (int i = 0; i < sql.length(); i++) {
      if (!live[i] || (sql.charAt(i) != 'p' && sql.charAt(i) != 'P')
          || !wordEquals(sql, i, "PATTERN") || !isWordStart(sql, live, i)
          || !isWordEnd(sql, live, i + 7)) {
        continue;
      }
      int open = skipBlank(sql, live, i + 7);
      if (open < sql.length() && live[open] && sql.charAt(open) == '(') {
        int end = matchParen(sql, live, open);
        if (end > 0) {
          spans.add(new int[]{i, end});
          i = end - 1;
        }
      }
    }
    return spans;
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
  static boolean signIsUnary(String sql, boolean[] live, int signPos) {
    int p = skipBlankBack(sql, live, signPos - 1);
    return p < 0 || !live[p] || !isAtomEnder(sql.charAt(p));
  }

  /** 目标操作符左操作数起点：单原子 + 向左按连接符扩展（+/- 需原子邻接判定
   * 二元/符号）。返回起点下标，失败 -1。 */
  static int scanOperandLeft(String sql, boolean[] live, int opStart,
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
  static int scanOperandRight(String sql, boolean[] live, int opEnd,
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
  static int[] connectorAtRight(String sql, boolean[] live, int j,
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
  static int[] connectorAtLeft(String sql, boolean[] live, int j,
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
  static int atomRight(String sql, boolean[] live, int i, boolean allowSign) {
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
      int k = skipBlank(sql, live, j);
      boolean followedByParen = k < n && live[k] && sql.charAt(k) == '(';
      if (EXPR_KEYWORDS.contains(word)
          && !(followedByParen && FUNC_PREFIXES.contains(word))) {
        return -1;
      }
      if (followedByParen) {
        int end = matchParen(sql, live, k);
        return end < 0 ? -1 : end;
      }
      return j;
    }
    return -1;
  }

  /** 自 i（活字符）起向左扫一个原子（i 为原子最后一个字符）：返回原子起点，
   * 无法识别返回 -1。 */
  static int atomLeft(String sql, boolean[] live, int i) {
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
          if (!FUNC_PREFIXES.contains(word)) {
            return start;   // 关键字后随括号（如 IN (…)）不是函数调用
          }
          // CAST( / TIMESTAMP( 等类型函数前缀：并入原子
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
  static int skipBlankBack(String sql, boolean[] live, int start) {
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
}

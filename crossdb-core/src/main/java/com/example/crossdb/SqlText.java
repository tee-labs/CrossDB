package com.example.crossdb;

/** SQL 文本词法扫描工具：供语句级文本改写（SqlTextRewrites / BitwiseDivRewrites）
 * 共用。核心概念是「活字符掩码」——字符串字面量、引号标识符与注释内的位置不算
 * 活字符，文本改写只作用于活字符区间，天然避开字面量/注释内的伪命中。 */
final class SqlText {
  private SqlText() {}

  static boolean wordEquals(String sql, int i, String word) {
    return sql.regionMatches(true, i, word, 0, word.length());
  }

  static boolean isWordStart(String sql, boolean[] live, int i) {
    return i == 0 || !live[i - 1] || !isIdentPart(sql.charAt(i - 1));
  }

  static boolean isWordEnd(String sql, boolean[] live, int end) {
    return end >= sql.length() || !live[end] || !isIdentPart(sql.charAt(end));
  }

  /** 活字符掩码：字符串字面量、引号标识符与注释内的位置为 false。 */
  static boolean[] liveMask(String sql) {
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

  static boolean spanLive(boolean[] live, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!live[i]) {
        return false;
      }
    }
    return true;
  }

  /** 自 start 起跳过空白（仅活字符位置），返回下一个活字符下标。 */
  static int skipBlank(String sql, boolean[] live, int start) {
    int i = start;
    while (i < sql.length() && (!live[i] || Character.isWhitespace(sql.charAt(i)))) {
      i++;
    }
    return i;
  }

  /** 括号匹配：返回 ')' 之后的位置，不匹配返回 -1。 */
  static int matchParen(String sql, boolean[] live, int open) {
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
  static boolean keywordAt(String sql, boolean[] live, int start, String keyword) {
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
  static String wordAt(String sql, int i) {
    int j = i;
    while (j < sql.length() && isIdentPart(sql.charAt(j))) {
      j++;
    }
    return sql.substring(i, j);
  }

  static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_';
  }

  static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}

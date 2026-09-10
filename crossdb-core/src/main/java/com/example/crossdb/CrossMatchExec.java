package com.example.crossdb;

import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/** MATCH_RECOGNIZE 最小子集的本地运行时：输入物化 → 按 ORDER BY 排序 → 按
 * PARTITION BY 分组 → 逐起点回溯模式匹配（左最优先、量词贪婪/懒惰按声明）。
 * 由 {@link EnumerableCrossMatch} 代码生成调用。
 *
 * <p>DEFINE 谓词以「扩展行」形式求值：扩展行 = [当前候选行各列] 后接各符号
 * 「最后绑定行」的各列块（块宽 = 输入列数，基址 = 块号 × 列数）。符号引用
 * {@code A.c}（含经 LAST/PREV(x, 0) 包装的形态）已被改写为扩展行输入引用；
 * 本符号自引用与输入别名引用均指向当前候选行块。未绑定符号块置 NULL。
 * 无 DEFINE 的模式符号（如开头的锚点符号）恒真。
 *
 * <p>匹配语义：确定性贪婪回溯（PATTERN_QUANTIFIER 的懒惰标记按声明 honored）；
 * 空匹配（零行）按标准视为不匹配；AFTER 仅支持 SKIP PAST LAST ROW（标准默认）
 * 与 SKIP TO NEXT ROW。ALL ROWS PER MATCH 输出命中行（行序即排序后行序）。 */
public final class CrossMatchExec {
  private CrossMatchExec() {}

  /** 模式节点：SYMBOL 叶子 / SEQ 顺序拼接 / ALTER 或 / QUANT 子模式量词。 */
  public static final class PNode {
    static final int SYMBOL = 0;
    static final int SEQ = 1;
    static final int ALTER = 2;
    static final int QUANT = 3;
    /** QUANT 的 max 无上界哨兵值。 */
    static final int UNBOUNDED = -1;

    final int type;
    final String symbol;
    final PNode[] children;
    final int min;
    final int max;
    final boolean reluctant;

    PNode(int type, String symbol, PNode[] children, int min, int max, boolean reluctant) {
      this.type = type;
      this.symbol = symbol;
      this.children = children;
      this.min = min;
      this.max = max;
      this.reluctant = reluctant;
    }

    public static PNode symbol(String name) {
      return new PNode(SYMBOL, name, null, 1, 1, false);
    }

    public static PNode seq(PNode... children) {
      return new PNode(SEQ, null, children, 1, 1, false);
    }

    public static PNode alter(PNode... children) {
      return new PNode(ALTER, null, children, 1, 1, false);
    }

    public static PNode quant(PNode child, int min, int max, boolean reluctant) {
      return new PNode(QUANT, null, new PNode[]{child}, min, max, reluctant);
    }
  }

  /** AFTER MATCH 跳转模式：0 = SKIP PAST LAST ROW（标准默认），1 = SKIP TO NEXT ROW。 */
  static final int AFTER_PAST_LAST = 0;
  static final int AFTER_NEXT_ROW = 1;

  /** 执行入口：pattern 以纯 JDK 类型编码传递（嵌套 Object[]：{type, symbol,
   * min, max, reluctant, children[]}，避免跨类加载器的方法签名解析问题），进入后
   * 先经 {@link #buildPattern} 重建为模式树；symbols 与 predicates 一一对应
   * （无 DEFINE 的符号其谓词由符号名反查，未命中即恒真）；alphaNames 为扩展行
   * 各符号块名，块号 b 的基址 = b × nCols，块 0 固定为当前候选行块（输入别名）。
   * public：Calcite 代码生成经反射按 public 方法解析调用目标。 */
  public static Enumerable<Object[]> match(Enumerable<Object[]> input, Object[] patternSpec,
      String[] symbols, java.util.function.Predicate<Object[]>[] predicates,
      String[] alphaNames, int nCols, int[] partitionCols, int[] orderCols,
      boolean[] orderDesc, int afterMode) {
    PNode pattern = buildPattern(patternSpec);
    List<Object[]> rows = new ArrayList<>();
    for (Object[] row : input) {
      rows.add(row);
    }
    rows.sort(rowComparator(orderCols, orderDesc));
    // 分区（保持首见序）：分区键 NULL 参与分组（渲染为字面量区分）
    Map<String, List<Object[]>> parts = new LinkedHashMap<>();
    for (Object[] row : rows) {
      parts.computeIfAbsent(partitionKey(row, partitionCols),
          k -> new ArrayList<>()).add(row);
    }
    Matcher m = new Matcher(symbols, predicates, alphaNames, nCols);
    List<Object[]> out = new ArrayList<>();
    for (List<Object[]> part : parts.values()) {
      m.rows = part;
      int i = 0;
      while (i < part.size()) {
        int end = m.tryMatch(pattern, i);
        if (end < 0) {
          i++;
          continue;
        }
        for (int r = i; r < end; r++) {
          out.add(part.get(r));
        }
        i = afterMode == AFTER_NEXT_ROW ? i + 1 : end;
      }
    }
    return Linq4j.asEnumerable(out);
  }

  /** Object[] 编码的模式树 → PNode：{type, symbol, min, max, reluctant, Object[] 子}。 */
  static PNode buildPattern(Object[] spec) {
    int type = (Integer) spec[0];
    String symbol = (String) spec[1];
    int min = spec[2] == null ? 1 : (Integer) spec[2];
    int max = spec[3] == null ? 1 : (Integer) spec[3];
    boolean reluctant = Boolean.TRUE.equals(spec[4]);
    PNode[] children = null;
    if (spec[5] instanceof Object[] childSpecs) {
      children = new PNode[childSpecs.length];
      for (int i = 0; i < childSpecs.length; i++) {
        children[i] = buildPattern((Object[]) childSpecs[i]);
      }
    }
    return new PNode(type, symbol, children, min, max, reluctant);
  }

  /** 单分区回溯匹配器；bindings 为「块号 → 已绑定行位栈」（栈顶即 LAST）。 */  private static final class Matcher {
    final String[] symbols;
    final java.util.function.Predicate<Object[]>[] predicates;
    final String[] alphaNames;
    final int nCols;
    List<Object[]> rows;
    final Deque<Integer>[] bindings;
    final Object[] expanded;

    @SuppressWarnings("unchecked")
    Matcher(String[] symbols, java.util.function.Predicate<Object[]>[] predicates, String[] alphaNames,
        int nCols) {
      this.symbols = symbols;
      this.predicates = predicates;
      this.alphaNames = alphaNames;
      this.nCols = nCols;
      this.bindings = new ArrayDeque[alphaNames.length];
      for (int i = 0; i < bindings.length; i++) {
        bindings[i] = new ArrayDeque<>();
      }
      this.expanded = new Object[nCols * alphaNames.length];
    }

    /** 自 start 起尝试一次完整匹配：成功返回匹配结束行位（排他，>start），失败 -1。
     * 沿成功路径的符号绑定保留（供输出阶段解读）；失败路径在回溯中自行撤销；
     * 每次尝试前清空上次（含成功）遗留的绑定。 */
    int tryMatch(PNode pattern, int start) {
      for (Deque<Integer> binding : bindings) {
        binding.clear();
      }
      int[] end = {-1};
      if (matchNode(pattern, start, p -> {
        end[0] = p;
        return true;
      }) && end[0] > start) {
        return end[0];   // 空匹配按标准视为不匹配
      }
      return -1;
    }

    boolean matchNode(PNode node, int pos, IntPredicate cont) {
      switch (node.type) {
        case PNode.SYMBOL: {
          if (pos >= rows.size() || !testSymbol(node.symbol, pos)) {
            return false;
          }
          int block = blockOf(node.symbol);
          if (block >= 0) {
            bindings[block].push(pos);
          }
          boolean ok = cont.test(pos + 1);
          if (!ok && block >= 0) {
            bindings[block].pop();
          }
          return ok;
        }
        case PNode.SEQ:
          return matchSeq(node.children, 0, pos, cont);
        case PNode.ALTER: {
          for (PNode child : node.children) {
            if (matchNode(child, pos, cont)) {
              return true;
            }
          }
          return false;
        }
        case PNode.QUANT:
          return matchQuant(node, pos, 0, cont);
        default:
          return false;
      }
    }

    private boolean matchSeq(PNode[] children, int idx, int pos, IntPredicate cont) {
      return idx == children.length
          ? cont.test(pos)
          : matchNode(children[idx], pos, p -> matchSeq(children, idx + 1, p, cont));
    }

    private boolean matchQuant(PNode q, int pos, int count, IntPredicate cont) {
      boolean canExit = count >= q.min;
      boolean canMore = q.max == PNode.UNBOUNDED || count < q.max;
      if (!q.reluctant && canMore
          && matchNode(q.children[0], pos, p -> matchQuant(q, p, count + 1, cont))) {
        return true;
      }
      if (canExit && cont.test(pos)) {
        return true;
      }
      return !q.reluctant ? false
          : canMore && matchNode(q.children[0], pos, p -> matchQuant(q, p, count + 1, cont));
    }

    /** 块号反查：符号名命中 alphaNames；无引用且未定义的符号无块（-1）。 */
    private int blockOf(String symbol) {
      for (int i = 0; i < alphaNames.length; i++) {
        if (alphaNames[i].equals(symbol)) {
          return i;
        }
      }
      return -1;
    }

    /** 在候选行 pos 上测试符号 S 的 DEFINE：无 DEFINE 恒真；否则构建扩展行求值。
     * 本符号块与当前行块（块 0）都填候选行；其余符号块填最后绑定行或 NULL。 */
    boolean testSymbol(String symbol, int pos) {
      int predIdx = -1;
      for (int i = 0; i < symbols.length; i++) {
        if (symbols[i].equals(symbol)) {
          predIdx = i;
          break;
        }
      }
      if (predIdx < 0 || predicates[predIdx] == null) {
        return true;
      }
      Object[] row = rows.get(pos);
      for (int b = 0; b < alphaNames.length; b++) {
        Object[] src = row;
        if (b != 0 && !alphaNames[b].equals(symbol)) {
          Integer bound = bindings[b].peek();
          src = bound == null ? null : rows.get(bound);
        }
        int base = b * nCols;
        for (int c = 0; c < nCols; c++) {
          expanded[base + c] = src == null ? null : src[c];
        }
      }
      return predicates[predIdx].test(expanded);
    }
  }

  /** 行比较器：按 orderCols 顺序；方向按 orderDesc；空值按 Calcite 约定
   * （升序 NULLS FIRST、降序 NULLS LAST）。 */
  private static Comparator<Object[]> rowComparator(int[] orderCols, boolean[] orderDesc) {
    return (a, b) -> {
      for (int i = 0; i < orderCols.length; i++) {
        int c = orderDesc[i] ? compareNulls(b[orderCols[i]], a[orderCols[i]])
            : compareNulls(a[orderCols[i]], b[orderCols[i]]);
        if (c != 0) {
          return c;
        }
      }
      return 0;
    };
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareNulls(Object x, Object y) {
    if (x == null) {
      return y == null ? 0 : -1;
    }
    if (y == null) {
      return 1;
    }
    if (x instanceof Number && y instanceof Number
        && !x.getClass().equals(y.getClass())) {
      return Double.compare(((Number) x).doubleValue(), ((Number) y).doubleValue());
    }
    return ((Comparable) x).compareTo(y);
  }

  private static String partitionKey(Object[] row, int[] cols) {
    if (cols.length == 0) {
      return "";
    }
    StringBuilder b = new StringBuilder();
    for (int c : cols) {
      b.append(row[c] == null ? "NULL" : row[c].toString()).append(' ');
    }
    return b.toString();
  }
}

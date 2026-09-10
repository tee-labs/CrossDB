package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.core.Match;
import org.apache.calcite.rel.logical.LogicalMatch;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexPatternFieldRef;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlMatchRecognize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LogicalMatch → EnumerableCrossMatch：上游 EnumerableMatch 的运行时翻译未完成
 * （PATTERN 量词与 DEFINE 符号引用无法编译，执行期抛 unknown kind）。本规则把
 * 可支持子集编译为本地回溯匹配器参数：PATTERN 符号拼接/或/量词（贪婪与懒惰）、
 * DEFINE 符号列引用（含 LAST/PREV(x, 0)）、PARTITION BY、ORDER BY、
 * ALL ROWS PER MATCH、AFTER SKIP PAST LAST ROW・TO NEXT ROW。
 * 子集外形态（MEASURES/SUBSET/锚定/区间/ONE ROW PER MATCH/SKIP TO FIRST・LAST、
 * 非零偏移导航、聚合度量）不转换——保留上游计划路径报真实错误。 */
class CrossMatchRule extends RelOptRule {
  static final CrossMatchRule INSTANCE = new CrossMatchRule();

  private CrossMatchRule() {
    super(operand(LogicalMatch.class, any()), "CrossMatchRule");
  }

  /** 子集外形态信号（onMatch 捕获后放弃转换）。 */
  private static final class Unsupported extends RuntimeException {
    Unsupported(String why) {
      super(why);
    }
  }

  @Override public void onMatch(RelOptRuleCall call) {
    LogicalMatch match = call.rel(0);
    final Compiled compiled;
    try {
      compiled = compile(match);
    } catch (Unsupported e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("CrossMatchRule 跳过: " + e.getMessage());
      }
      return;
    }
    org.apache.calcite.rel.RelNode input =
        RelOptRule.convert(match.getInput(), EnumerableConvention.INSTANCE);
    call.transformTo(new EnumerableCrossMatch(match.getCluster(),
        match.getCluster().traitSetOf(EnumerableConvention.INSTANCE), input,
        match.getRowType(), match.getPattern(), match.isStrictStart(),
        match.isStrictEnd(), match.getPatternDefinitions(), match.getMeasures(),
        match.getAfter(), match.getSubsets(), match.isAllRows(),
        match.getPartitionKeys(), match.getOrderKeys(), match.getInterval(),
        compiled.defines, compiled.expandedType, compiled.pattern, compiled.alphas,
        compiled.symbols, compiled.partitions, compiled.orders, compiled.orderDesc,
        compiled.after));
  }

  private static final class Compiled {
    final org.apache.calcite.rex.RexBuilder rexBuilder;
    Compiled(org.apache.calcite.rex.RexBuilder rexBuilder) { this.rexBuilder = rexBuilder; }
    com.google.common.collect.ImmutableMap<String, RexNode> defines;
    RelDataType expandedType;
    CrossMatchExec.PNode pattern;
    String[] alphas;
    String[] symbols;
    int[] partitions;
    int[] orders;
    boolean[] orderDesc;
    int after;
  }

  private static Compiled compile(Match m) {
    if (!m.getMeasures().isEmpty()) {
      throw new Unsupported("MEASURES 暂不支持");
    }
    if (!m.getSubsets().isEmpty()) {
      throw new Unsupported("SUBSET 暂不支持");
    }
    if (m.isStrictStart() || m.isStrictEnd()) {
      throw new Unsupported("锚定 (^ $) 暂不支持");
    }
    if (m.getInterval() != null) {
      throw new Unsupported("WITHIN INTERVAL 暂不支持");
    }
    if (!m.isAllRows()) {
      throw new Unsupported("ONE ROW PER MATCH 暂不支持");
    }
    int after = afterMode(m.getAfter());
    LinkedHashSet<String> patternSymbols = new LinkedHashSet<>();
    CrossMatchExec.PNode pattern = parsePattern(m.getPattern(), patternSymbols);

    int nCols = m.getInput().getRowType().getFieldCount();
    Set<String> defined = m.getPatternDefinitions().keySet();
    List<String> symbols = new ArrayList<>(defined);
    // 扩展行块：块 0 = 当前行（输入别名引用）；其后为每个「被引用的模式符号」
    // 一块——含未定义符号（如锚点符号 A，被 DEFINE 引用但自身无定义）
    final LinkedHashSet<String> referenced = new LinkedHashSet<>();
    for (RexNode def : m.getPatternDefinitions().values()) {
      collectReferencedAlphas(def, referenced);
    }
    List<String> blocks = new ArrayList<>();
    blocks.add("__current__");
    for (String alpha : referenced) {
      if (patternSymbols.contains(alpha)) {
        blocks.add(alpha);
      }
    }
    // 非符号 alpha（输入别名）一律映射块 0
    final Map<String, Integer> blockOf = new LinkedHashMap<>();
    for (int i = 1; i < blocks.size(); i++) {
      blockOf.put(blocks.get(i), i);
    }

    RexBuilder rexBuilder = new RexBuilder(m.getCluster().getTypeFactory());
    RelDataTypeFactory.Builder tb = m.getCluster().getTypeFactory().builder();
    for (int i = 0; i < nCols; i++) {
      RelDataType t = m.getInput().getRowType().getFieldList().get(i).getType();
      tb.add("cur$" + i, m.getCluster().getTypeFactory().createTypeWithNullability(t, true));
    }
    for (int b = 1; b < blocks.size(); b++) {
      for (int i = 0; i < nCols; i++) {
        RelDataType t = m.getInput().getRowType().getFieldList().get(i).getType();
        tb.add(blocks.get(b) + "$" + i,
            m.getCluster().getTypeFactory().createTypeWithNullability(t, true));
      }
    }
    RelDataType expanded = tb.build();

    // DEFINE 条件改写：LAST/PREV(ref, 0) 与裸 ref → 扩展行输入引用
    Map<String, RexNode> rewritten = new LinkedHashMap<>();
    for (Map.Entry<String, RexNode> e : m.getPatternDefinitions().entrySet()) {
      rewritten.put(e.getKey(),
          rewriteDefine(e.getValue(), blockOf, nCols, rexBuilder));
    }

    Compiled c = new Compiled(rexBuilder);
    c.defines = com.google.common.collect.ImmutableMap.copyOf(rewritten);
    c.expandedType = expanded;
    c.pattern = pattern;
    c.alphas = blocks.toArray(new String[0]);
    c.symbols = symbols.toArray(new String[0]);
    c.partitions = m.getPartitionKeys().toArray();
    c.orders = m.getOrderKeys().getFieldCollations().stream()
        .mapToInt(RelFieldCollation::getFieldIndex).toArray();
    c.orderDesc = toBoolArray(m.getOrderKeys());
    c.after = after;
    return c;
  }

  private static boolean[] toBoolArray(RelCollation collation) {
    boolean[] out = new boolean[collation.getFieldCollations().size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = collation.getFieldCollations().get(i).direction
          == RelFieldCollation.Direction.DESCENDING;
    }
    return out;
  }

  private static int afterMode(RexNode after) {
    if (after instanceof RexLiteral lit) {
      SqlMatchRecognize.AfterOption opt =
          lit.getValueAs(SqlMatchRecognize.AfterOption.class);
      if (opt == SqlMatchRecognize.AfterOption.SKIP_PAST_LAST_ROW) {
        return CrossMatchExec.AFTER_PAST_LAST;
      }
      if (opt == SqlMatchRecognize.AfterOption.SKIP_TO_NEXT_ROW) {
        return CrossMatchExec.AFTER_NEXT_ROW;
      }
    }
    throw new Unsupported("AFTER " + after + " 暂不支持");
  }

  /** PATTERN 树 → 本地模式节点：LITERAL=符号，PATTERN_CONCAT=顺序，
   * PATTERN_ALTER=或，PATTERN_QUANTIFIER=量词（操作数 [子模式, min, max, 懒惰]）。
   * 出现的全部符号名收集进 symbolsOut。 */
  private static CrossMatchExec.PNode parsePattern(RexNode node,
      LinkedHashSet<String> symbolsOut) {
    switch (node.getKind()) {
      case LITERAL:
        String symbol = ((RexLiteral) node).getValueAs(String.class);
        if (symbol == null) {
          throw new Unsupported("非常量模式符号: " + node);
        }
        symbolsOut.add(symbol);
        return CrossMatchExec.PNode.symbol(symbol);
      case PATTERN_CONCAT:
      case PATTERN_ALTER: {
        List<CrossMatchExec.PNode> children = new ArrayList<>();
        for (RexNode op : ((RexCall) node).getOperands()) {
          children.add(parsePattern(op, symbolsOut));
        }
        return node.getKind() == SqlKind.PATTERN_CONCAT
            ? CrossMatchExec.PNode.seq(children.toArray(new CrossMatchExec.PNode[0]))
            : CrossMatchExec.PNode.alter(children.toArray(new CrossMatchExec.PNode[0]));
      }
      case PATTERN_QUANTIFIER: {
        List<RexNode> ops = ((RexCall) node).getOperands();
        if (ops.size() < 3 || !(ops.get(1) instanceof RexLiteral minLit)
            || !(ops.get(2) instanceof RexLiteral maxLit)) {
          throw new Unsupported("量词形态: " + node);
        }
        Integer min = minLit.getValueAs(Integer.class);
        Integer max = maxLit.getValueAs(Integer.class);
        boolean reluctant = ops.size() > 3 && ops.get(3) instanceof RexLiteral rLit
            && Boolean.TRUE.equals(rLit.getValueAs(Boolean.class));
        return CrossMatchExec.PNode.quant(parsePattern(ops.get(0), symbolsOut),
            min == null ? 0 : min, max == null ? CrossMatchExec.PNode.UNBOUNDED : max,
            reluctant);
      }
      default:
        throw new Unsupported("模式节点 " + node.getKind() + " 暂不支持");
    }
  }

  /** 收集条件里出现的全部 alpha（符号或输入别名）。 */
  private static void collectReferencedAlphas(RexNode def,
      LinkedHashSet<String> out) {
    def.accept(new RexVisitorImpl<Void>(true) {
      @Override public Void visitPatternFieldRef(RexPatternFieldRef ref) {
        out.add(ref.getAlpha());
        return null;
      }
    });
  }

  /** DEFINE 条件改写（自底向上）：PATTERN_INPUT_REF → 扩展行引用（符号块或块 0）；
   * LAST/PREV(ref, 0) ≡ ref；其余标量调用递归克隆。非零偏移、CLASSIFIER、
   * MATCH_NUMBER、导航/聚合度量等子集外语义直接抛 Unsupported。 */
  private static RexNode rewriteDefine(RexNode node, Map<String, Integer> blockOf,
      int nCols, RexBuilder rexBuilder) {
    switch (node.getKind()) {
      case PATTERN_INPUT_REF: {
        RexPatternFieldRef ref = (RexPatternFieldRef) node;
        int block = blockOf.getOrDefault(ref.getAlpha(), 0);
        return rexBuilder.makeInputRef(ref.getType(), block * nCols + ref.getIndex());
      }
      case LAST:
      case PREV: {
        List<RexNode> ops = ((RexCall) node).getOperands();
        if (ops.size() == 2 && ops.get(0).getKind() == SqlKind.PATTERN_INPUT_REF
            && ops.get(1) instanceof RexLiteral lit
            && Integer.valueOf(0).equals(lit.getValueAs(Integer.class))) {
          return rewriteDefine(ops.get(0), blockOf, nCols, rexBuilder);
        }
        throw new Unsupported("导航 " + node + " 仅支持 0 偏移");
      }
      case CLASSIFIER:
      case MATCH_NUMBER:
      case NEXT:
      case FIRST:
      case RUNNING:
      case FINAL:
        throw new Unsupported("度量导航 " + node.getKind() + " 暂不支持");
      default:
        break;
    }
    if (node instanceof RexCall call) {
      if (call.getKind().belongsTo(SqlKind.AGGREGATE)) {
        throw new Unsupported("DEFINE 内聚合暂不支持");
      }
      List<RexNode> ops = new ArrayList<>(call.getOperands().size());
      for (RexNode op : call.getOperands()) {
        ops.add(rewriteDefine(op, blockOf, nCols, rexBuilder));
      }
      return call.clone(call.getType(), ops);
    }
    if (node instanceof RexPatternFieldRef) {
      // 非调用形态的模式引用（理论上已被上面覆盖，保险处理）
      RexPatternFieldRef ref = (RexPatternFieldRef) node;
      int block = blockOf.getOrDefault(ref.getAlpha(), 0);
      return rexBuilder.makeInputRef(ref.getType(), block * nCols + ref.getIndex());
    }
    return node;
  }
}

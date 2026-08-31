package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableCalc;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverter;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;

/** NOT EXISTS 的 ANTI Bind Join 规则。
 *
 * <p>Calcite 将 {@code NOT EXISTS} 去相关为「LEFT JOIN + 常量标记列 IS NULL 过滤」
 * 形态（标记列由去相关器投影为常量 TRUE，未匹配时补 NULL，{@code IS NULL} 保留
 * 无匹配的外表行）。本规则把该形态改写为 {@code EnumerableCalc(无过滤) ←
 * EnumerableBindJoin(ANTI)}：内表按外表 key 分批 IN 下推，只回传命中行，
 * 替代原生的「内表 distinct key 全量拉取 + 本地合并/哈希连接」。
 *
 * <p>判别条件（宁可不改写不可误改写）：
 * <ul>
 *   <li>过滤条件恰为 {@code IS NOT NULL(右表单列)}，且投影表达式只引用左表列
 *       （ANTI Bind Join 只输出外表行，右表列随改写消失）；</li>
 *   <li>被过滤的右表列可追溯到<b>常量标记</b>（去相关器产物）。用户手写的
 *       {@code LEFT JOIN + WHERE 右列 IS NOT NULL} 是 INNER 语义，引用真实列，
 *       不满足常量标记判别，不会被本规则碰；</li>
 *   <li>连接条件满足 Bind Join 触发条件（复用 {@link BindJoinRule#make}）。</li>
 * </ul>
 */
class AntiBindJoinRule extends RelOptRule {
  private final java.util.Map<String, javax.sql.DataSource> sources;
  private final int batchSize;
  private final int parallelism;

  AntiBindJoinRule(java.util.Map<String, javax.sql.DataSource> sources, int batchSize,
      int parallelism) {
    super(operand(EnumerableCalc.class,
        some(operand(Join.class, any()))), "AntiBindJoinRule");
    this.sources = sources;
    this.batchSize = batchSize;
    this.parallelism = parallelism;
  }

  @Override public boolean matches(RelOptRuleCall call) {
    return call.rel(0) instanceof EnumerableCalc calc
        && call.rel(1) instanceof Join join
        && calc.getTraitSet().getConvention() == EnumerableConvention.INSTANCE
        && join.getTraitSet().getConvention() == EnumerableConvention.INSTANCE
        && join.getJoinType() == JoinRelType.LEFT;
  }

  @Override public void onMatch(RelOptRuleCall call) {
    try {
      attempt(call);
    } catch (Throwable e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("AntiBindJoinRule: 生成候选失败");
        e.printStackTrace(System.err);
      }
    }
  }

  private void attempt(RelOptRuleCall call) {
    EnumerableCalc calc = call.rel(0);
    Join join = call.rel(1);
    RexProgram program = calc.getProgram();
    RexNode condition = program.getCondition();
    // IS NULL(右表标记列) = NOT EXISTS 的 ANTI 形态；IS NOT NULL(右表标记列)
    // = EXISTS 的 SEMI 形态（Calcite 对 EXISTS 通常已生成 SEMI Join，此处兜底）
    JoinRelType bindType;
    if (condition instanceof RexCall isnn
        && isnn.getOperands().size() == 1
        && isnn.getOperands().get(0) instanceof RexInputRef ref
        && ref.getIndex() >= join.getLeft().getRowType().getFieldCount()) {
      bindType = isnn.getKind() == SqlKind.IS_NULL ? JoinRelType.ANTI
          : isnn.getKind() == SqlKind.IS_NOT_NULL ? JoinRelType.SEMI : null;
      if (bindType != null) {
        generate(call, calc, join, program, ref, bindType);
      }
    }
  }

  private void generate(RelOptRuleCall call, EnumerableCalc calc, Join join,
      RexProgram program, RexInputRef ref, JoinRelType bindType) {
    int leftCount = join.getLeft().getRowType().getFieldCount();
    if (ref.getIndex() < leftCount) {
      return; // 过滤的是左表列：不是去相关的半连接形态
    }
    // SEMI/ANTI Bind Join 只输出外表行：投影必须全部只引用左表列
    for (RexLocalRef projRef : program.getProjectList()) {
      if (!refsAllLeft(program.expandLocalRef(projRef), leftCount)) {
        return;
      }
    }
    // 被过滤的右表列必须是常量标记（沿 Sort/Filter/Project/Calc 链下探），
    // 排除「LEFT JOIN + WHERE 右表真实列 IS [NOT] NULL」的 INNER/LEFT 语义形态
    if (!isConstantMarker(join.getRight(), ref.getIndex() - leftCount)) {
      return;
    }

    EnumerableBindJoin candidate = BindJoinRule.make(
        BindJoinRule.toEnumerable(join.getLeft()),
        BindJoinRule.toEnumerable(join.getRight()),
        join.getCondition(), bindType, sources, batchSize, parallelism);
    if (candidate == null) {
      return;
    }
    // 原投影表达式只引用左表列，SEMI/ANTI 行型 = 左表列（顺序不变），引用下标原样有效
    java.util.List<RexNode> exprs = new java.util.ArrayList<>();
    for (RexLocalRef projRef : program.getProjectList()) {
      exprs.add(program.expandLocalRef(projRef));
    }
    RexProgram newProgram = RexProgram.create(candidate.getRowType(), exprs, null,
        calc.getRowType().getFieldNames(), calc.getCluster().getRexBuilder());
    call.transformTo(EnumerableCalc.create(candidate, newProgram));
    if (Boolean.getBoolean("crossdb.debug")) {
      System.err.println("AntiBindJoinRule: 已生成 " + bindType + " Bind Join 候选");
    }
  }

  /** 表达式引用的输入列是否全部属于左表（下标 < leftCount）。 */
  private static boolean refsAllLeft(RexNode expr, int leftCount) {
    boolean[] ok = {true};
    expr.accept(new RexVisitorImpl<Void>(true) {
      @Override public Void visitInputRef(RexInputRef inputRef) {
        if (inputRef.getIndex() >= leftCount) {
          ok[0] = false;
        }
        return null;
      }
    });
    return ok[0];
  }

  /** 右子树第 pos 列是否为常量标记：沿单输入链（Sort/Filter/Project/Calc）下探，
   * 遇投影节点解析该列表达式——引用则继续下探、常量即命中，其余形态保守放弃。
   * 供本规则与 AntiBindJoinFilterRule 复用。 */
  static boolean isConstantMarker(RelNode right, int pos) {
    RelNode n = BindJoinRule.unwrapSubset(right);
    while (true) {
      if (n instanceof JdbcToEnumerableConverter c) {
        n = BindJoinRule.unwrapSubset(c.getInput());
        continue;
      }
      if (n instanceof Project proj) {
        RexNode e = proj.getProjects().get(pos);
        if (e instanceof RexInputRef r) {
          pos = r.getIndex();
          n = BindJoinRule.unwrapSubset(proj.getInput());
          continue;
        }
        return e instanceof RexLiteral lit && !lit.isNull();
      }
      if (n instanceof EnumerableCalc calc) {
        RexNode e = calc.getProgram().expandLocalRef(
            calc.getProgram().getProjectList().get(pos));
        if (e instanceof RexInputRef r) {
          pos = r.getIndex();
          n = calc.getInput();
          continue;
        }
        return e instanceof RexLiteral lit && !lit.isNull();
      }
      if (n instanceof Sort s) {
        n = BindJoinRule.unwrapSubset(s.getInput());
        continue;
      }
      if (n instanceof Filter f) {
        n = BindJoinRule.unwrapSubset(f.getInput());
        continue;
      }
      return false;
    }
  }
}

package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableCalc;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.sql.SqlKind;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code Filter(Join)} 形态的 SEMI/ANTI Bind Join 规则（{@link AntiBindJoinRule}
 * 的姊妹规则）。
 *
 * <p>火山优化阶段，「LEFT JOIN + 常量标记列 IS NULL/IS NOT NULL 过滤」的父节点是
 * {@code Filter}（EnumerableCalc 是优化结束后的 calc 合并产物，规则期不可见）。
 * 本规则在该形态上把 Join 改写为 SEMI/ANTI Bind Join，Filter 语义（只保留匹配/
 * 未匹配的外表行）由 ANTI/SEMI 执行直接满足；输出仍包一层投影 Calc，把右表列
 * 置为常量 NULL，行型与原 LEFT Join 完全一致，上层节点无感知。
 */
class AntiBindJoinFilterRule extends RelOptRule {
  private final Map<String, DataSource> sources;
  private final int batchSize;
  private final int parallelism;

  AntiBindJoinFilterRule(Map<String, DataSource> sources, int batchSize, int parallelism) {
    super(operand(Filter.class, operand(Join.class, any())), "AntiBindJoinFilterRule");
    this.sources = sources;
    this.batchSize = batchSize;
    this.parallelism = parallelism;
  }

  @Override public boolean matches(RelOptRuleCall call) {
    return call.rel(1) instanceof Join join
        && join.getJoinType() == JoinRelType.LEFT;
  }

  @Override public void onMatch(RelOptRuleCall call) {
    try {
      attempt(call);
    } catch (Throwable e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("AntiBindJoinFilterRule: 生成候选失败");
        e.printStackTrace(System.err);
      }
    }
  }

  private void attempt(RelOptRuleCall call) {
    Filter filter = call.rel(0);
    Join join = call.rel(1);
    RexNode condition = filter.getCondition();
    if (!(condition instanceof RexCall isnn)
        || isnn.getOperands().size() != 1
        || !(isnn.getOperands().get(0) instanceof RexInputRef ref)) {
      return;
    }
    // IS NULL(右表标记列) = NOT EXISTS 的 ANTI 形态；IS NOT NULL = EXISTS 的 SEMI 形态
    JoinRelType bindType;
    if (isnn.getKind() == SqlKind.IS_NULL) {
      bindType = JoinRelType.ANTI;
    } else if (isnn.getKind() == SqlKind.IS_NOT_NULL) {
      bindType = JoinRelType.SEMI;
    } else {
      return;
    }
    int leftCount = join.getLeft().getRowType().getFieldCount();
    if (ref.getIndex() < leftCount) {
      return; // 过滤的是左表列：不是去相关的半连接形态
    }
    // 被过滤的右表列必须是常量标记（沿 Sort/Filter/Project/Calc 链下探），
    // 排除「LEFT JOIN + WHERE 右表真实列 IS [NOT] NULL」的 INNER 语义形态
    if (!AntiBindJoinRule.isConstantMarker(join.getRight(), ref.getIndex() - leftCount)) {
      return;
    }

    EnumerableBindJoin candidate = BindJoinRule.make(
        BindJoinRule.toEnumerable(join.getLeft()),
        BindJoinRule.toEnumerable(join.getRight()),
        join.getCondition(), bindType, sources, batchSize, parallelism);
    if (candidate == null) {
      return;
    }
    // 行型保持 [左 ++ 右]（右表列恒为 NULL），与原 Filter 的输出完全一致
    List<RexNode> exprs = new ArrayList<>();
    for (int i = 0; i < leftCount; i++) {
      exprs.add(new RexInputRef(i, join.getRowType().getFieldList().get(i).getType()));
    }
    for (int i = leftCount; i < join.getRowType().getFieldCount(); i++) {
      RelDataTypeField field = join.getRowType().getFieldList().get(i);
      exprs.add(join.getCluster().getRexBuilder().makeNullLiteral(field.getType()));
    }
    RexProgram program = RexProgram.create(candidate.getRowType(), exprs, null,
        join.getRowType().getFieldNames(), join.getCluster().getRexBuilder());
    call.transformTo(EnumerableCalc.create(candidate, program));
    if (Boolean.getBoolean("crossdb.debug")) {
      System.err.println("AntiBindJoinFilterRule: 已生成 " + bindType + " Bind Join 候选");
    }
  }
}

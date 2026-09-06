package com.example.crossdb;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;

import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多列 COUNT 计数可移植化：把 {@code COUNT(a, b)}（多参计数，MySQL 专有语法，
 * H2/PostgreSQL 等主流后端不支持）改写为 {@code COUNT(CASE WHEN a IS NOT NULL
 * AND b IS NOT NULL THEN 1 END)}（单参，普遍可下推）。语义等价：两者都统计
 * 「a、b 均非 NULL」的行数。
 *
 * <p>典型上游形态：{@code COUNT(DISTINCT a, b)} 经 AGGREGATE_EXPAND_DISTINCT_
 * AGGREGATES 扩展后产出「分组去重子查询 + 多参 COUNT」，本规则在其后运行，
 * 使最终下推 SQL 回到可移植形态。仅处理无 DISTINCT 的 COUNT 聚合；其余聚合调用
 * 原样保留。多列 COUNT 出现于 GROUPING SETS 时不改写（极罕见形态，维持原状）。
 */
class MultiArgCountRule extends RelOptRule {

  MultiArgCountRule() {
    super(operand(Aggregate.class, any()), "MultiArgCountRule");
  }

  @Override public void onMatch(RelOptRuleCall call) {
    Aggregate aggregate = call.rel(0);
    if (aggregate.getGroupSets().size() != 1) {
      return;
    }
    // 待改写的多参 COUNT 调用（参数列集合 → 新增哨兵列下标）
    Map<List<Integer>, Integer> caseColumns = new LinkedHashMap<>();
    for (AggregateCall c : aggregate.getAggCallList()) {
      if (isMultiArgCount(c)) {
        caseColumns.putIfAbsent(List.copyOf(c.getArgList()), 0);
      }
    }
    if (caseColumns.isEmpty()) {
      return;
    }

    try {
      attempt(call, aggregate, caseColumns);
    } catch (Throwable e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("MultiArgCountRule: 生成候选失败");
        e.printStackTrace(System.err);
      }
    }
  }

  private static boolean isMultiArgCount(AggregateCall c) {
    return !c.isDistinct() && c.getAggregation().getKind() == SqlKind.COUNT
        && c.getArgList().size() > 1;
  }

  private void attempt(RelOptRuleCall call, Aggregate aggregate,
      Map<List<Integer>, Integer> caseColumns) {
    RelBuilder builder = call.builder();
    builder.push(aggregate.getInput());
    int inputWidth = aggregate.getInput().getRowType().getFieldCount();
    RexBuilder rex = builder.getRexBuilder();
    List<RexNode> exprs = new ArrayList<>();
    for (int i = 0; i < inputWidth; i++) {
      exprs.add(rex.makeInputRef(aggregate.getInput(), i));
    }
    // 唯一参数集合各生成一个 CASE 非空哨兵列
    for (List<Integer> args : caseColumns.keySet()) {
      exprs.add(nonNullGuard(rex, aggregate.getInput().getRowType(), args));
      caseColumns.put(args, exprs.size() - 1);
    }
    builder.project(exprs);

    List<AggregateCall> newCalls = new ArrayList<>();
    for (AggregateCall c : aggregate.getAggCallList()) {
      List<Integer> key = List.copyOf(c.getArgList());
      newCalls.add(isMultiArgCount(c)
          ? c.copy(List.of(caseColumns.get(key)), c.filterArg)
          : c.copy(c.getArgList(), c.filterArg));
    }
    builder.aggregate(builder.groupKey(aggregate.getGroupSet()), newCalls);
    call.transformTo(builder.build());
    if (Boolean.getBoolean("crossdb.debug")) {
      System.err.println("MultiArgCountRule: 多参 COUNT 已改写为单参 CASE 计数");
    }
  }

  /** {@code CASE WHEN a IS NOT NULL AND b IS NOT NULL THEN 1 END}（多参全非空判定）。 */
  private static RexNode nonNullGuard(RexBuilder rex, RelDataType inputType,
      List<Integer> args) {
    List<RexNode> notNulls = new ArrayList<>();
    for (int arg : args) {
      notNulls.add(rex.makeCall(SqlStdOperatorTable.IS_NOT_NULL,
          rex.makeInputRef(inputType, arg)));
    }
    RexNode guard = notNulls.size() == 1 ? notNulls.get(0)
        : rex.makeCall(SqlStdOperatorTable.AND, notNulls);
    return rex.makeCall(SqlStdOperatorTable.CASE, ImmutableList.of(guard,
        rex.makeExactLiteral(BigDecimal.ONE),
        rex.makeNullLiteral(rex.getTypeFactory().createSqlType(SqlTypeName.INTEGER))));
  }
}

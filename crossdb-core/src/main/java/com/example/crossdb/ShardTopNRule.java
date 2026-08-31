package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableLimit;
import org.apache.calcite.adapter.enumerable.EnumerableSort;
import org.apache.calcite.adapter.enumerable.EnumerableUnion;
import org.apache.calcite.adapter.jdbc.JdbcConvention;
import org.apache.calcite.adapter.jdbc.JdbcRules.JdbcSort;
import org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverter;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexLiteral;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 分片合并 Top-N 下推：{@code UNION ALL} 跨库合并 + {@code ORDER BY + LIMIT} 时，
 * 把排序与裁剪一起下推进每个分支的源库 SQL（每库只返回 offset+fetch 行），
 * 本地保留 Sort/Limit 归并保证语义，网络传输从 O(各分支全量行) 降为
 * O((offset+fetch) × 分支数)。
 *
 * <p>仅限 {@code UNION ALL}：{@code UNION}（去重）的全局语义无法按分支裁剪后合并，
 * 维持原生计划。offset 不下推进分支（避免在源库先跳过行），分支取前
 * offset+fetch 行，本地 Sort 后统一按原 offset/fetch 裁剪。
 */
class ShardTopNRule extends RelOptRule {
  ShardTopNRule() {
    super(operand(EnumerableLimit.class,
        operand(EnumerableSort.class,
            operand(EnumerableUnion.class, any()))), "ShardTopNRule");
  }

  @Override public boolean matches(RelOptRuleCall call) {
    if (!(call.rel(0) instanceof EnumerableLimit limit)
        || limit.fetch == null
        || !(call.rel(1) instanceof EnumerableSort sort)
        || sort.getCollation().getFieldCollations().isEmpty()
        || !(call.rel(2) instanceof EnumerableUnion union)
        || !union.all) {
      return false;
    }
    int width = union.getRowType().getFieldCount();
    return sort.getCollation().getFieldCollations().stream()
            .allMatch(fc -> fc.getFieldIndex() < width)
        && union.getInputs().stream().allMatch(ShardTopNRule::pushableBranch);
  }

  /** 分支可整体下推：Enumerable 子集之下是 JDBC 约定的整棵子树，且尚未做过
   * 本规则的分支裁剪（防重复触发）。 */
  private static boolean pushableBranch(RelNode input) {
    RelNode n = BindJoinRule.unwrapSubset(input);
    if (!(n instanceof JdbcToEnumerableConverter converter)) {
      return false;
    }
    RelNode jdbc = BindJoinRule.unwrapDeep(converter.getInput());
    return jdbc.getTraitSet().getConvention() instanceof JdbcConvention
        && !(jdbc instanceof Sort pushed && pushed.fetch != null);
  }

  @Override public void onMatch(RelOptRuleCall call) {
    try {
      attempt(call);
    } catch (Throwable e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println("ShardTopNRule: 生成候选失败");
        e.printStackTrace(System.err);
      }
    }
  }

  private void attempt(RelOptRuleCall call) {
    EnumerableLimit limit = call.rel(0);
    EnumerableSort sort = call.rel(1);
    EnumerableUnion union = call.rel(2);

    long offset = limit.offset == null ? 0 : RexLiteral.intValue(limit.offset);
    long fetch = RexLiteral.intValue(limit.fetch);
    RelCollation collation = sort.getCollation();

    List<RelNode> branches = new ArrayList<>();
    for (RelNode input : union.getInputs()) {
      JdbcToEnumerableConverter converter =
          (JdbcToEnumerableConverter) BindJoinRule.unwrapSubset(input);
      RelNode jdbc = BindJoinRule.unwrapDeep(converter.getInput());
      JdbcConvention convention = (JdbcConvention) jdbc.getTraitSet().getConvention();
      // 分支取前 offset+fetch 行：全局第 offset..offset+fetch-1 名必在其中
      RelTraitSet traits = jdbc.getCluster().traitSetOf(convention).replace(collation);
      JdbcSort pushed = new JdbcSort(jdbc.getCluster(), traits, jdbc, collation, null,
          jdbc.getCluster().getRexBuilder()
              .makeExactLiteral(BigDecimal.valueOf(offset + fetch)));
      branches.add(new JdbcToEnumerableConverter(
          pushed.getCluster(),
          pushed.getCluster().traitSetOf(EnumerableConvention.INSTANCE), pushed) {
      });
    }
    EnumerableUnion merged = new EnumerableUnion(
        union.getCluster(),
        union.getCluster().traitSetOf(EnumerableConvention.INSTANCE), branches, true);
    EnumerableSort localSort = EnumerableSort.create(merged, collation, null, null);
    call.transformTo(EnumerableLimit.create(localSort, limit.offset, limit.fetch));
    if (Boolean.getBoolean("crossdb.debug")) {
      System.err.println("ShardTopNRule: 已生成分片 Top-N 候选 fetch=" + fetch);
    }
  }
}

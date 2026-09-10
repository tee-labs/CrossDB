package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.EnumUtils;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.Match;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/** MATCH_RECOGNIZE 本地物理算子（上游 EnumerableMatch 运行时翻译未完成：模式量词
 * 与 DEFINE 符号引用均无法编译）：输入转 ENUMERABLE 后整体交由
 * {@link CrossMatchExec} 回溯匹配执行。支持子集：PATTERN 符号拼接 / 或 / 量词
 * （含贪婪与懒惰）、DEFINE 中符号列引用（含 LAST/PREV(x, 0) 包装）、PARTITION BY、
 * ORDER BY、ALL ROWS PER MATCH、AFTER MATCH SKIP PAST LAST ROW / TO NEXT ROW；
 * 不支持 MEASURES / SUBSET / 锚定（^ $）/ 区间 / 一次行模式与 SKIP TO FIRST・LAST
 * ——超集形态不生成本算子，保持原上游报错路径。
 *
 * <p>DEFINE 谓词编译：把条件中全部符号列引用改写为「扩展行」输入引用（当前行块
 * 在前、各符号最后绑定行块在后），经 RexToLixTranslator 生成 Object[] 谓词。 */
class EnumerableCrossMatch extends Match implements EnumerableRel {
  /** 改写后的 DEFINE 条件（扩展行输入引用形态）。 */
  final ImmutableMap<String, RexNode> crossDefines;
  /** 扩展行类型：[当前行块 | 符号块...]，块宽 = 输入列数。 */
  final RelDataType expandedRowType;
  final CrossMatchExec.PNode crossPattern;
  final String[] crossAlphas;
  final String[] crossSymbols;
  final int[] crossPartitions;
  final int[] crossOrders;
  final boolean[] crossOrderDesc;
  final int crossAfter;

  EnumerableCrossMatch(RelOptCluster cluster, RelTraitSet traitSet, RelNode input,
      RelDataType rowType, RexNode pattern, boolean strictStart, boolean strictEnd,
      Map<String, RexNode> patternDefinitions, Map<String, RexNode> measures,
      RexNode after, Map<String, ? extends SortedSet<String>> subsets, boolean allRows,
      ImmutableBitSet partitionKeys, org.apache.calcite.rel.RelCollation orderKeys,
      RexNode interval, ImmutableMap<String, RexNode> crossDefines,
      RelDataType expandedRowType, CrossMatchExec.PNode crossPattern, String[] crossAlphas,
      String[] crossSymbols, int[] crossPartitions, int[] crossOrders,
      boolean[] crossOrderDesc, int crossAfter) {
    super(cluster, traitSet, input, rowType, pattern, strictStart, strictEnd,
        patternDefinitions, measures, after, subsets, allRows, partitionKeys, orderKeys,
        interval);
    this.crossDefines = crossDefines;
    this.expandedRowType = expandedRowType;
    this.crossPattern = crossPattern;
    this.crossAlphas = crossAlphas;
    this.crossSymbols = crossSymbols;
    this.crossPartitions = crossPartitions;
    this.crossOrders = crossOrders;
    this.crossOrderDesc = crossOrderDesc;
    this.crossAfter = crossAfter;
  }

  @Override public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new EnumerableCrossMatch(getCluster(),
        traitSet.replace(EnumerableConvention.INSTANCE), inputs.get(0), rowType, pattern,
        strictStart, strictEnd, patternDefinitions, measures, after, subsets, allRows,
        partitionKeys, orderKeys, interval, crossDefines, expandedRowType, crossPattern,
        crossAlphas, crossSymbols, crossPartitions, crossOrders, crossOrderDesc,
        crossAfter);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw).item("crossMatch", true);
  }

  /** 与 BindJoin 半连接同策略：可编译子集内恒优（上游 EnumerableMatch 的运行时
   * 不可用，真实代价比较无意义）；不可编译形态不会生成本算子。 */
  @Override public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return planner.getCostFactory().makeCost(0, 0, 0);
  }

  @Override public EnumerableRel.Result implement(EnumerableRelImplementor implementor,
      EnumerableRel.Prefer pref) {
    final BlockBuilder builder = new BlockBuilder();
    final EnumerableRel.Result inputResult =
        implementor.visitChild(this, 0, (EnumerableRel) getInput(), pref);
    final Expression inputE = builder.append("input", inputResult.block);
    // 输入行 → Object[]（照 EnumerableBindJoin 手法：仅非 ARRAY 格式时逐字段重组）
    final ParameterExpression row =
        Expressions.parameter(inputResult.physType.getJavaRowType(), "row");
    final BlockBuilder mapperBlock = new BlockBuilder();
    final int width = getInput().getRowType().getFieldCount();
    final Expression mappedRow;
    if (inputResult.physType.getFormat() == JavaRowFormat.ARRAY) {
      mappedRow = row;
    } else {
      List<Expression> fields = new ArrayList<>(width);
      for (int i = 0; i < width; i++) {
        fields.add(inputResult.physType.fieldReference(row, i));
      }
      mappedRow = Expressions.newArrayInit(Object.class, fields);
    }
    mapperBlock.add(Expressions.return_(null, mappedRow));
    final Expression inputRows = builder.append("matchInput", Expressions.call(inputE,
        BuiltInMethod.SELECT.method,
        Expressions.lambda(Function1.class, mapperBlock.toBlock(), row)));

    // 各 DEFINE 条件编译为扩展行谓词（java.util.function.Predicate，入参擦除为
    // Object、体首强转 Object[]；字段取值经 PhysType 按扩展行类型定标，保证
    // 比较等操作符能解析到具体类型的 SqlFunctions 实现）
    final RexBuilder rexBuilder = new RexBuilder(implementor.getTypeFactory());
    final List<Expression> predicates = new ArrayList<>();
    final ParameterExpression erasedRow = Expressions.parameter(Object.class, "erasedRow");
    final Expression arrayRow = Expressions.convert_(erasedRow, Object[].class);
    final PhysType expandedPhysType = PhysTypeImpl.of(implementor.getTypeFactory(),
        expandedRowType, JavaRowFormat.ARRAY, false);
    for (Map.Entry<String, RexNode> e : crossDefines.entrySet()) {
      RexProgramBuilder programBuilder =
          new RexProgramBuilder(expandedRowType, rexBuilder);
      programBuilder.addCondition(e.getValue());
      final BlockBuilder body = new BlockBuilder();
      Expression condition = RexToLixTranslator.translateCondition(
          programBuilder.getProgram(),
          (org.apache.calcite.adapter.java.JavaTypeFactory) getCluster().getTypeFactory(),
          body,
          new RexToLixTranslator.InputGetterImpl(arrayRow, expandedPhysType),
          v -> null, implementor.getConformance());
      body.add(Expressions.return_(null, condition));
      predicates.add(Expressions.new_(java.util.function.Predicate.class,
          new ArrayList<Expression>(),
          EnumUtils.overridingMethodDecl(BuiltInMethod.PREDICATE_TEST.method,
              List.of(erasedRow), body.toBlock())));
    }

    builder.add(Expressions.call(CrossMatchExec.class, "match",
        inputRows,
        patternSpecExpression(crossPattern),
        constantArray(String.class, crossSymbols),
        Expressions.newArrayInit(java.util.function.Predicate.class, predicates),
        constantArray(String.class, crossAlphas),
        Expressions.constant(width),
        constantArray(int.class, crossPartitions),
        constantArray(int.class, crossOrders),
        constantArray(boolean.class, crossOrderDesc),
        Expressions.constant(crossAfter)));
    final PhysType physType =
        PhysTypeImpl.of(implementor.getTypeFactory(), getRowType(), JavaRowFormat.ARRAY,
            false);
    return implementor.result(physType, builder.toBlock());
  }

  /** 模式树 → 纯 JDK 类型编码表达式（嵌套 Object[]：{type, symbol, min, max,
   * reluctant, children[]}），运行时由 CrossMatchExec.buildPattern 重建。
   * 避免 crossdb 类型进入生成代码的方法签名（Janino 跨类加载器解析问题）。 */
  private static Expression patternSpecExpression(CrossMatchExec.PNode node) {
    List<Expression> items = new ArrayList<>(6);
    items.add(Expressions.constant(node.type));
    items.add(node.symbol == null
        ? Expressions.constant(null, String.class) : Expressions.constant(node.symbol));
    items.add(node.type == CrossMatchExec.PNode.QUANT
        ? Expressions.constant(node.min) : Expressions.constant(null, Integer.class));
    items.add(node.type == CrossMatchExec.PNode.QUANT
        ? Expressions.constant(node.max) : Expressions.constant(null, Integer.class));
    items.add(Expressions.constant(node.type == CrossMatchExec.PNode.QUANT
        && node.reluctant));
    if (node.children == null) {
      items.add(Expressions.constant(null, Object[].class));
    } else {
      List<Expression> children = new ArrayList<>(node.children.length);
      for (CrossMatchExec.PNode child : node.children) {
        children.add(patternSpecExpression(child));
      }
      items.add(Expressions.newArrayInit(Object.class, children));
    }
    return Expressions.newArrayInit(Object.class, items);
  }

  private static Expression constantArray(Class<?> type, String[] values) {
    List<Expression> items = new ArrayList<>();
    for (String v : values) {
      items.add(Expressions.constant(v));
    }
    return Expressions.newArrayInit(type, items);
  }

  private static Expression constantArray(Class<?> type, int[] values) {
    List<Expression> items = new ArrayList<>();
    for (int v : values) {
      items.add(Expressions.constant(v));
    }
    return Expressions.newArrayInit(type, items);
  }

  private static Expression constantArray(Class<?> type, boolean[] values) {
    List<Expression> items = new ArrayList<>();
    for (boolean v : values) {
      items.add(Expressions.constant(v));
    }
    return Expressions.newArrayInit(type, items);
  }
}

package com.example.crossdb;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRules;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.adapter.jdbc.JdbcTableScan;
import org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverter;
import org.apache.calcite.config.Lex;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** 引擎入口：register(schema, DataSource) 注册任意 JDBC 库，query(sql) 跨库执行。
 *
 * <p>同库过滤/JOIN/聚合自动下推方言 SQL；跨库 JOIN 走 Bind Join（分批 IN 下推 +
 * 并发拉取 + 流水线式流式执行，见 BindJoinRule/BindJoinExec）或本地 hash join。
 * 所有拉取带 fetchSize 流式读取、行数熔断与 queryTimeout 超时传播（Guarded）。
 *
 * <p>其他入口：
 * <ul>
 *   <li>{@link #explain} — 返回优化后的物理计划；</li>
 *   <li>{@link #analyze} — 执行并输出各源库实际下发的 SQL、网络行数、Bind Join 批次；</li>
 *   <li>{@link #safeMode()} — 拦截「无过滤条件的源库全表拉取」的查询（OLTP 防呆）。</li>
 * </ul>
 */
public class CrossDb implements AutoCloseable {
  public static final int DEFAULT_FETCH_SIZE = 1000;
  public static final long DEFAULT_ROW_LIMIT = 1_000_000L;
  public static final int DEFAULT_BIND_BATCH_SIZE = 500;
  public static final int DEFAULT_BIND_PARALLELISM = 4;
  public static final int DEFAULT_QUERY_TIMEOUT = 0;

  private final CalciteConnection connection;
  private final Map<String, DataSource> sources = new HashMap<>();
  private final Stats stats = new Stats();
  private final int fetchSize;
  private final long rowLimit;
  private final int bindBatchSize;
  private final int bindParallelism;
  private final int queryTimeout;
  private boolean safeMode;

  public CrossDb() throws SQLException {
    this(DEFAULT_FETCH_SIZE, DEFAULT_ROW_LIMIT, DEFAULT_BIND_BATCH_SIZE,
        DEFAULT_BIND_PARALLELISM, DEFAULT_QUERY_TIMEOUT);
  }

  public CrossDb(int fetchSize, long rowLimit, int bindBatchSize, int bindParallelism)
      throws SQLException {
    this(fetchSize, rowLimit, bindBatchSize, bindParallelism, DEFAULT_QUERY_TIMEOUT);
  }

  /** @param queryTimeout 每条源库语句的 queryTimeout 秒数（0=不限制），超时由 JDBC
   * 驱动在源库侧取消执行，防止慢查询拖垮连接池 */
  public CrossDb(int fetchSize, long rowLimit, int bindBatchSize, int bindParallelism,
      int queryTimeout) throws SQLException {
    if (fetchSize < 1 || rowLimit < 1 || bindBatchSize < 1 || bindParallelism < 1
        || queryTimeout < 0) {
      throw new IllegalArgumentException("fetchSize/rowLimit/batchSize/parallelism 必须 >= 1，"
          + "queryTimeout 必须 >= 0");
    }
    Properties info = new Properties();
    info.setProperty("lex", "MYSQL");
    Connection raw = DriverManager.getConnection("jdbc:calcite:", info);
    this.connection = raw.unwrap(CalciteConnection.class);
    this.fetchSize = fetchSize;
    this.rowLimit = rowLimit;
    this.bindBatchSize = bindBatchSize;
    this.bindParallelism = bindParallelism;
    this.queryTimeout = queryTimeout;
  }

  /** 开启 safeMode：计划中出现「无过滤条件的源库全表拉取」（Bind Join 内表除外，
   * 其始终带 key IN 过滤；聚合视为有归约）时拒绝执行。 */
  public CrossDb safeMode() {
    this.safeMode = true;
    return this;
  }

  public CrossDb register(String schema, DataSource dataSource) throws SQLException {
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("schema 名称不能为空");
    }
    if (sources.containsKey(schema)) {
      throw new IllegalArgumentException("schema 重复注册: " + schema);
    }
    DataSource guarded =
        Guarded.wrap(dataSource, fetchSize, rowLimit, schema, stats, queryTimeout);
    sources.put(schema, guarded);
    connection.getRootSchema().add(schema,
        JdbcSchema.create(connection.getRootSchema(), schema, guarded, null, null));
    return this;
  }

  public ResultSet query(String sql) throws SQLException {
    RelNode best = plan(sql);
    if (safeMode) {
      checkSafe(best);
    }
    // Stats.ACTIVE 保留到结果集迭代结束（Bind Join 在迭代期执行）；
    // 同一 CrossDb 并发多条查询时统计会串场，CalciteConnection 本身也不支持并发。
    stats.reset();
    stats.safeMode = safeMode;
    Stats.ACTIVE = stats;
    ResultSet rs = Exec.query(connection, best);
    return Guarded.limit(rs, rowLimit);
  }

  /** 级联取消当前查询在所有源库的在途语句（供外部线程/看门狗调用；无在途语句时为
   * 空操作）。{@code Statement.cancel} 交由源库 JDBC 驱动中止执行，防慢查询拖垮
   * 连接池；与 queryTimeout 的被动超时保护互补。 */
  public void cancel() throws SQLException {
    stats.cancelAll();
  }

  /** 返回优化后的物理计划（EXPLAIN）。 */
  public String explain(String sql) throws SQLException {
    return RelOptUtil.toString(plan(sql));
  }

  /** EXPLAIN ANALYZE：执行查询并返回物理计划 + 各源库实际下发的 SQL、
   * 每库网络行数、Bind Join 批次与拉取行数。注意会消费整个结果集。 */
  public String analyze(String sql) throws SQLException {
    RelNode best = plan(sql);
    if (safeMode) {
      checkSafe(best);
    }
    stats.reset();
    stats.safeMode = safeMode;
    Stats.ACTIVE = stats;
    try (ResultSet rs = Guarded.limit(Exec.query(connection, best), rowLimit)) {
      while (rs.next()) {
        // 全量消费以统计网络行数
      }
    } finally {
      Stats.ACTIVE = null;
    }
    return RelOptUtil.toString(best) + "\n" + stats.render();
  }

  private RelNode plan(String sql) throws SQLException {
    FrameworkConfig config = Frameworks.newConfigBuilder()
        // 操作符表：标准表 + 本地 UDF（方言兼容函数与 SIMILAR TO 改写目标）
        .operatorTable(SqlOperatorTables.chain(
            org.apache.calcite.sql.fun.SqlStdOperatorTable.instance(),
            SqlOperatorTables.of(SqlRewrites.OPERATORS)))
        .parserConfig(SqlParser.config().withLex(Lex.MYSQL)
            // LENIENT：放行 CROSS/OUTER APPLY（SQL Server 风格相关派生表）；
            // 对既有语法仅为放宽（!= / LIMIT a,b 等在 LENIENT 下同样可用）
            .withConformance(SqlConformanceEnum.LENIENT))
        .defaultSchema(connection.getRootSchema())
        .programs(queryProgram())
        .build();
    Planner planner = Frameworks.getPlanner(config);
    RelNode best;
    try {
      SqlNode parsed = planner.parse(SqlRewrites.preprocess(sql));
      parsed = SqlRewrites.rewrite(parsed, connection.getRootSchema(),
          connection.getTypeFactory());
      checkReadOnly(parsed);
      SqlNode validated = planner.validate(parsed);
      RelRoot root = planner.rel(validated);
      // 校验器会给「列名清单派生表」等形态附加复合排序特征（RelCompositeTrait，
      // 非简单特征），VolcanoPlanner.changeTraits 的 allSimple() 断言会拒绝；
      // 顶层行序由计划内 Sort 算子保证，这里安全地退回空排序。
      org.apache.calcite.plan.RelTraitSet required =
          root.rel.getTraitSet().replace(EnumerableConvention.INSTANCE);
      if (!required.allSimple()) {
        required = required.replace(
            org.apache.calcite.rel.RelCollations.EMPTY);
      }
      best = planner.transform(0, required, root.rel);
      // 优化后行型可能宽于验证后的输出行型（如 ORDER BY 引用未 SELECT 的列/表达式键，
      // 排序列会留在计划输出里），按 root.fields 补最终投影裁掉，避免输出列泄漏
      final RelNode optimized = best;
      RelRoot trimmed = root.withRel(optimized);
      if (!trimmed.isRefTrivial()) {
        org.apache.calcite.rex.RexBuilder rexBuilder =
            optimized.getCluster().getRexBuilder();
        List<org.apache.calcite.rex.RexNode> exprs = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        trimmed.fields.forEach((ordinal, name) -> {
          exprs.add(rexBuilder.makeInputRef(optimized, ordinal));
          names.add(name);
        });
        org.apache.calcite.rel.type.RelDataType outRow =
            rexBuilder.getTypeFactory().createStructType(
                exprs.stream().map(org.apache.calcite.rex.RexNode::getType).toList(),
                names);
        best = org.apache.calcite.adapter.enumerable.EnumerableCalc.create(optimized,
            org.apache.calcite.rex.RexProgram.create(optimized.getRowType(), exprs, null,
                outRow, rexBuilder));
      }
      if (Boolean.getBoolean("crossdb.debug")) {
        System.err.println(RelOptUtil.toString(best));
      }
    } catch (Exception e) {
      if (Boolean.getBoolean("crossdb.debug")) {
        e.printStackTrace(System.err);
        for (Throwable c : e.getSuppressed()) {
          c.printStackTrace(System.err);
        }
      }
      throw e instanceof SQLException se ? se : new SQLException(e);
    } finally {
      planner.close();
    }
    return best;
  }

  // SIMILAR TO 与其余方言兼容语法的解析期改写见 SqlRewrites。
  /** 防呆：源库拉取子树不允许只有 Scan/Filter/Project/无 LIMIT Sort 链（全表拉取）。
   * Bind Join 内表例外——其 SQL 运行时必带 key IN 过滤；Aggregate 视为有归约。 */
  private void checkSafe(RelNode plan) throws CrossDbUnsafeQueryException {
    RelNode n = BindJoinRule.unwrapSubset(plan);
    if (n instanceof EnumerableBindJoin bind) {
      checkSafe(BindJoinRule.unwrapSubset(bind.getLeft()));
      return;
    }
    if (n instanceof JdbcToEnumerableConverter converter
        && isBarePull(BindJoinRule.unwrapDeep(converter.getInput()))) {
      throw new CrossDbUnsafeQueryException("crossdb safeMode: 检测到无过滤条件的源库"
          + "全表拉取（" + BindJoinRule.unwrapDeep(converter.getInput()).explain()
          + "），已拒绝执行。请补充 WHERE 条件或 LIMIT。");
    }
    for (RelNode input : n.getInputs()) {
      checkSafe(input);
    }
  }

  private static boolean isBarePull(RelNode n) {
    while (true) {
      if (n instanceof JdbcTableScan) {
        return true;
      }
      // 有行数归约能力的算子视作已过滤
      if (n instanceof org.apache.calcite.rel.core.Filter
          || n instanceof org.apache.calcite.rel.core.Aggregate) {
        return false;
      }
      if (n instanceof Sort s) {
        if (s.offset == null && s.fetch == null && s.getInputs().size() == 1) {
          n = s.getInput(0);
          continue;
        }
        return false;
      }
      if (n.getInputs().size() == 1 && !(n instanceof Join)) {
        n = n.getInput(0);
        continue;
      }
      return false;
    }
  }

  /** 只读硬化：仅放行查询形态（SELECT、UNION/INTERSECT/EXCEPT 集合操作、
   * ORDER BY/LIMIT 与 WITH CTE 包裹），DML/DDL/SET 等一律拒绝——引擎
   * Read-only by design，写入必须直接走各源库。 */
  private static void checkReadOnly(SqlNode parsed) throws SQLException {
    if (!isQuery(parsed)) {
      throw new SQLException("crossdb 只读引擎：拒绝非查询语句（" + parsed.getKind()
          + "），仅支持 SELECT。写入/DDL 请直接操作各源库。");
    }
  }

  private static boolean isQuery(SqlNode n) {
    while (true) {
      if (n instanceof org.apache.calcite.sql.SqlOrderBy ob) {
        n = ob.query;
        continue;
      }
      if (n instanceof org.apache.calcite.sql.SqlWith with) {
        n = with.body;
        continue;
      }
      break;
    }
    if (n instanceof SqlSelect) {
      return true;
    }
    if (n instanceof org.apache.calcite.sql.SqlCall call) {
      return switch (call.getKind()) {
        case UNION, INTERSECT, EXCEPT -> call.getOperandList().stream()
            .filter(java.util.Objects::nonNull).allMatch(CrossDb::isQuery);
        default -> false;
      };
    }
    return false;
  }

  private Program queryProgram() {
    BindJoinRule rule = new BindJoinRule(sources, bindBatchSize, bindParallelism);
    TopNBindJoinRule topN = new TopNBindJoinRule(sources, bindBatchSize, bindParallelism);
    AntiBindJoinRule anti = new AntiBindJoinRule(sources, bindBatchSize, bindParallelism);
    AntiBindJoinFilterRule antiFilter =
        new AntiBindJoinFilterRule(sources, bindBatchSize, bindParallelism);
    ShardTopNRule shardTopN = new ShardTopNRule();
    Program standard = Programs.standard();
    // 前置扩展：多列去重聚合（COUNT(DISTINCT a, b)）先改写为「分组去重 + 计数」，
    // 否则标准程序里的 JdbcAggregateRule 会把它原样下推源库——H2/PostgreSQL 等主流
    // 后端不支持该语法（MySQL 支持）。扩展产物的多参 COUNT(a,b) 同样非可移植语法，
    // 再经 MultiArgCountRule 归一为单参 CASE 计数。
    Program expandDistinct = Programs.of(
        new org.apache.calcite.plan.hep.HepProgramBuilder()
            .addRuleInstance(org.apache.calcite.rel.rules.CoreRules
                .AGGREGATE_EXPAND_DISTINCT_AGGREGATES)
            .addRuleInstance(new MultiArgCountRule())
            .build(),
        true, org.apache.calcite.rel.metadata.DefaultRelMetadataProvider.INSTANCE);
    Program withCustomRules = (planner, rel, requiredTraits, materializations, lattices) -> {
      planner.addRule(rule);
      planner.addRule(topN);
      planner.addRule(anti);
      planner.addRule(antiFilter);
      planner.addRule(shardTopN);
      // 剔除上游缺陷规则 EnumerableMergeUnionRule：它对 UNION DISTINCT 也把
      // (offset+fetch) 的 LIMIT 压进每个分支——去重发生在合并层，分支先截断会丢失
      // 应保留的行。跨库 UNION ALL 的 Top-N 下推由 ShardTopNRule 安全承担。
      planner.removeRule(EnumerableRules.ENUMERABLE_MERGE_UNION_RULE);
      return standard.run(planner, rel, requiredTraits, materializations, lattices);
    };
    return Programs.sequence(expandDistinct, withCustomRules);
  }

  @Override public void close() throws SQLException {
    connection.close();
  }
}

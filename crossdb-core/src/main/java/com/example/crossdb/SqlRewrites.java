package com.example.crossdb;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;

/** 解析期兼容性改写：把易踩坑/不可执行的语法在进入校验器之前改写为引擎可控、
 * 跨源行为一致的等价形态。全部改写保持语义等价；无法安全改写的形态原样保留，
 * 交由校验器给出真实错误。
 *
 * <p>本类是改写族的门面，具体分工：
 * {@link SqlTextRewrites} 语句级文本预处理（TOP n、SEMI/ANTI JOIN、WITH TIES、
 * FETCH PERCENT、GROUPS 帧等，位运算/DIV 委托 {@link BitwiseDivRewrites}）；
 * {@link SqlTreeRewrites} 解析树表达式改写 + FROM 级改写（{@link FromRewriter}）；
 * {@link LocalOperators} 改写挂载目标的本地 UDF/UDAF 操作符表；
 * {@link SqlText} 共享的文本词法扫描工具；{@link ColumnHints} 类型感知改写
 * 所用的列类型目录。
 *
 * <ul>
 *   <li>TOP n（SQL Server 方言，Calcite 解析器不支持）→ FETCH FIRST n ROWS ONLY；</li>
 *   <li>SIMILAR TO → CROSSDB_SIMILAR 本地 UDF（Enumerable 不实现、下推必错）；</li>
 *   <li>INITCAP / OVERLAY / FLOOR・CEIL(ts TO unit) → 本地 UDF（源库普遍缺对应
 *   函数或语法，下推必错，本地求值保证跨源一致）；</li>
 *   <li>VAR_POP/VAR_SAMP/STDDEV_POP/STDDEV_SAMP → 入参 CAST AS DOUBLE
 *   （Calcite 本地实现对整数输入做整数除法，标准语义应为浮点）；</li>
 *   <li>CUME_DIST / PERCENT_RANK → RANK/COUNT(*) 等价改写（Enumerable 约定缺
 *   这两个窗口聚合的实现）；</li>
 *   <li>NTH_VALUE → CROSSDB_NTH_VALUE 本地窗口聚合（Calcite 与常见源库均忽略
 *   窗口帧、按整分区取值，与标准「帧内第 n 行」语义不符）；</li>
 *   <li>STRING_AGG / GROUP_CONCAT（PostgreSQL/MySQL 聚合）→ 标准 LISTAGG；</li>
 *   <li>DECODE（Oracle，未注册于标准操作符表）→ CASE WHEN .. IS NOT DISTINCT FROM
 *   等价改写（NULL=NULL 相等语义保留）；</li>
 *   <li>IIF（SQL Server）→ CASE WHEN 等价改写；ISNULL（SQL Server）→ COALESCE；
 *   ILIKE（PostgreSQL）→ LOWER(x) LIKE LOWER(p)；</li>
 *   <li>PERCENTILE_DISC / ARRAY_AGG / ANY_VALUE / MODE → 本地 UDAF（Enumerable
 *   无实现或引擎不支持 ARRAY 值类型，ARRAY_AGG 以 {@code "[..]"} 字符串渲染）；</li>
 *   <li>AGG(x) FILTER (WHERE c) OVER w → AGG(CASE WHEN c THEN x END) OVER w
 *   （校验器拒绝 FILTER 与 OVER 组合；跳 NULL 语义聚合上等价）；</li>
 *   <li>FIRST_VALUE/LAST_VALUE(x) IGNORE NULLS OVER w → 本地首/末非 NULL 端点
 *   窗口聚合（Enumerable 无 IGNORE NULLS 实现）；</li>
 *   <li>COUNT(DISTINCT x) OVER w → CROSSDB_COUNT_DISTINCT 窗口聚合
 *   （EnumerableWindow 静默丢弃 DISTINCT 量化符，错误地得到 COUNT(*) 语义）；</li>
 *   <li>LEFT SEMI/ANTI JOIN（Calcite 解析器不支持的语法）→ 等价 CROSS APPLY
 *   (SELECT 1 .. HAVING COUNT(*) 比较)；</li>
 *   <li>FETCH FIRST n ROWS WITH TIES（Calcite 解析器不支持）→ 「前 n 个去重键组」
 *   等价 IN 半连接改写（裸列键）；</li>
 *   <li>USING 共享列裸引用（Calcite 校验器 AssertionError，上游缺陷）→ 等价 ON
 *   等值连接 + 裸引用替换为 COALESCE(l.c, r.c)；</li>
 *   <li>表别名列名清单 FROM t(a, b)（标准 SQL）→ 派生表列重命名；</li>
 *   <li>语句尾分号（多数驱动接受、Calcite 解析器不接受）→ 预处理剥离。</li>
 * </ul> */
final class SqlRewrites {
  private SqlRewrites() {}

  static String preprocess(String sql) {
    return SqlTextRewrites.preprocess(sql);
  }

  static SqlNode rewrite(SqlNode parsed, SchemaPlus root, JavaTypeFactory typeFactory,
      ColumnHints hints) {
    return SqlTreeRewrites.rewrite(parsed, root, typeFactory, hints);
  }
}

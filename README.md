# crossdb — 基于 Apache Calcite 的跨库 SQL 引擎

把多个 JDBC 数据库注册成 schema，用一条 SQL 跨库查询。
定位：**OLTP 点查 / 运维排障** —— 低延迟、低网络 I/O、防慢查与爆内存，纯关系型库、极简开箱即用、只读。

## 核心机制一览

- **同库下推**：单库内的过滤/投影/JOIN/聚合/排序由 Calcite `JdbcRules` 整体下推成该库方言 SQL（内置 MySQL/PostgreSQL/Oracle/DB2/H2 等方言翻译），跨库查询对每个源库都只下发「完整的一条 SQL」；
- **Bind Join（跨库 JOIN）**：外表 key 分批 `IN` 下推到内表所在库、虚拟线程并发拉取、本地 hash 探测（`BindJoinRule` + `EnumerableBindJoin` + `BindJoinExec`），支持 **INNER/LEFT/RIGHT/FULL/SEMI/ANTI** 与**复合键（tuple-IN）**；RIGHT 交换内外侧按 LEFT 形态执行（行型恢复原始列序），FULL 在外表耗尽后对内表补一次分块 `NOT IN` 反连接；
- **SEMI/ANTI 半连接**：`EXISTS` / `IN` 子查询走 SEMI Bind Join，`NOT EXISTS` 走 ANTI Bind Join（识别去相关后的「LEFT JOIN + 常量标记列 IS NULL」形态）——内表只回传命中 key 的行，网络消耗最小化；`NOT IN` 被 Calcite 展开为「计数聚合」形态、无等值连接对，自动走原生计划（结果正确）；
- **Top-N 下推**：`ORDER BY + LIMIT` 且排序列在驱动侧时一起下推进驱动侧源库 SQL，网络传输降为 O(LIMIT) 量级；
- **分片合并 Top-N**：`UNION ALL` 跨库合并 + `ORDER BY + LIMIT` 时，把排序与裁剪下推进**每个分支**的源库 SQL（每库只回 offset+fetch 行），本地归并保持语义，网络传输 O((offset+fetch) × 分支数)；
- **传递谓词下推**：`ON a.t = b.t` + `WHERE a.t = 1` 自动把 `b.t = 1` 补到内表侧源库，从源头减少网络传输；
- **流水线式流式执行**：外表游标按窗口流式读取、每批异步并发拉取，输出流式 yield——驱动侧内存 O(batchSize × parallelism)，不再全量驻内存；外表按 join key 有序时（排序下推场景）自动**每窗口淘汰**哈希旧条目；
- **IN 列表自适应分片**：单批 key 超过 1000（Oracle IN 列表上限）自动拆为 `IN (...) OR IN (...)`，任意方言安全；
- **全链路安全护栏**：所有源库拉取带 fetchSize 流式读取、行数熔断、queryTimeout 超时传播与级联取消；内表并发拉取用 **JDK 21 虚拟线程**（按 `parallelism` 限流）；引擎强制只读，safeMode 可进一步拦截全表拉取。

自包含子项目（Maven 多模块：`crossdb-core` + `crossdb-spring-boot-starter`），与本仓库其他部分无关，可随时拆成独立仓库。需要 **JDK 21+** 构建。

## 快速开始

```bash
mvn test                                                     # 1243 个 JUnit 单元测试（两个模块；11 个 @Disabled("待支持: …") 兼容性用例按设计跳过）
mvn -q -pl crossdb-core exec:java -Dexec.mainClass=com.example.crossdb.Main   # 端到端自检
# 加 -Dcrossdb.debug=true 可打印物理计划与规则匹配过程
# 非 UTC 时区的机器请加 -DargLine="-Duser.timezone=UTC"（TIMESTAMP 按 UTC 墙钟承载）
```

第一个跨库查询（H2 内存库演示，真实库见下）：

```java
try (CrossDb db = new CrossDb()) {
    db.register("shop", buildHikari("jdbc:mysql://host:3306/shop", user, pass));
    db.register("crm",  buildHikari("jdbc:postgresql://host:5432/crm", user, pass));
    ResultSet rs = db.query(
        "SELECT u.name, SUM(o.amount) FROM shop.orders o " +
        "JOIN crm.users u ON u.id = o.user_id GROUP BY u.name");
    while (rs.next()) { /* 消费一次；结果集是流式一次性迭代，不可 reset */ }
}
```

驱动已在 pom.xml 里（mysql-connector-j / postgresql）。生产建议每个目标库一个独立 HikariCP 连接池（`Guarded` 代理是透明的，直接包住 HikariDataSource 传入即可）。Schema 元数据由 Calcite `JdbcSchema` 在首次用到表时懒加载并缓存于 `CrossDb` 生命周期内，无需额外配置。

## 查询特性

| 特性 | 说明 |
| --- | --- |
| Bind Join | 跨库 INNER/LEFT/RIGHT/FULL/SEMI/ANTI 等值 JOIN 自动改写：外表 key 每批 `batchSize` 个去重后以 `IN (?)` 下推内表库，`parallelism` 个虚拟线程并发拉取，本地 hash 探测；LEFT/FULL 未匹配外表行补 NULL，FULL 再对内表补一次分块 `NOT IN` 反连接；RIGHT 交换内外侧执行、行型保持原始 [左 ++ 右]；SEMI/ANTI 只输出外表行（内表只回传命中 key） |
| 复合键 tuple-IN | 多列等值键（`ON a.k1=b.k1 AND a.k2=b.k2`）在 H2/MySQL/PostgreSQL/Oracle 生成 `(k1,k2) IN ((?,?),...)`，其余方言降级为 `(k1=? AND k2=?) OR ...`；单批 key 超 1000（Oracle IN 上限）自动拆为 `IN (...) OR IN (...)` |
| EXISTS / NOT EXISTS | `EXISTS`/`IN` 子查询去相关后的 SEMI Join 走 IN 下推；`NOT EXISTS` 去相关为「LEFT JOIN + 常量标记列 IS NULL」形态，由 `AntiBindJoinFilterRule` 改写为 ANTI Bind Join（投影只引用外表列时生效）；`NOT IN` 展开为计数聚合形态、无等值连接对，走原生计划（结果正确） |
| Top-N 下推 | `ORDER BY <驱动侧列> + LIMIT` 下推进驱动侧源库 SQL（ORDER BY + FETCH；带 OFFSET 时源库返回前 offset+fetch 行，OFFSET 只在本地裁剪一次），本地仍保留 Sort/Limit 保证语义 |
| 分片合并 Top-N | `UNION ALL` 多库合并 + `ORDER BY + LIMIT`（`ShardTopNRule`）：每个分支源库 SQL 带上 ORDER BY + 裁剪（每库只回 offset+fetch 行），本地归并排序后按原 offset/fetch 裁剪；仅限 UNION ALL（UNION 去重语义不可按分支裁剪） |
| 传递谓词下推 | 驱动侧 join key 上的常量条件自动补到内表侧源库 SQL（去重：内表已有等值过滤不重复包裹） |
| 哈希窗口淘汰 | 驱动侧按 join key 有序（如排序下推后的计划）时，每窗口合并前淘汰更小的 key，内表哈希内存从 O(distinct keys) 降为 O(窗口 keys)；检测保守，宁可不淘汰不错杀匹配 |
| 行数熔断 | 每个 `DataSource` 被代理：语句级 `maxRows = 阈值 + 1`（驱动侧封顶），结果集拉取计数超阈值即抛 `SQLException` 拒绝执行；对最终结果、每个源库扫描和 FULL 反连接同样生效 |
| 流式拉取 | 源库语句统一 `setFetchSize(fetchSize)`，逐批读取；Bind Join 外表流式读窗口、输出流式 yield，驱动侧不驻全量 |
| 超时传播 | `queryTimeout` 传播到每条源库语句，慢查询由 JDBC 驱动在源库侧取消，防连接池耗尽 |
| 级联取消 | `db.cancel()`（外部线程调用）遍历在途源库语句逐个 `Statement.cancel()`，主动掐断慢查询；语句关闭自动注销注册表 |
| 只读硬化 | 仅放行查询语句（SELECT、UNION/INTERSECT/EXCEPT、ORDER BY/LIMIT 与 WITH CTE 包裹）；INSERT/UPDATE/DELETE/MERGE/DDL/SET 等在解析层直接拒绝——写入请直接操作各源库 |
| safeMode | `db.safeMode()` 后，计划中出现「无过滤条件的源库全表拉取」直接抛 `CrossDbUnsafeQueryException`（Bind Join 内表例外，其必带 key IN 过滤；聚合视为有归约）——OLTP 零容忍全表拉取。FULL JOIN 的反连接 SQL 在运行期生成：外表零行/全 NULL key 时反连接将无过滤全表拉取内表，safeMode 会在执行期拒绝（见「Bind Join 当前边界」） |
| explain / analyze | `explain(sql)` 返回优化后物理计划；`analyze(sql)` 执行并输出各源库实际下发的 SQL、每库网络行数、Bind Join 批次与拉取行数（注意：analyze 会消费整个结果集） |
| ResultSet API | 结果集支持常用取值方法（`getInt/getLong/getByte/getShort/getFloat/getDouble/getString/getBoolean/getBigDecimal/getBytes/getTimestamp/getDate/getTime/getObject`，下标与列名两种重载）+ `wasNull()`（NULL 的数值取 0，由 `wasNull` 区分）+ `getMetaData()`（列数/名称/类型/精度/可空等基础元数据，供 Spring JdbcTemplate、MyBatis 等框架使用）；未支持的元数据方法显式抛「不支持」，不静默返回假值 |

## 典型场景与执行路径

| 场景 | SQL 形态 | 执行路径 |
| --- | --- | --- |
| 跨库 JOIN 点查 | `... FROM a.t JOIN b.t ON ... WHERE a.pk = 1` | 外表过滤下推 → Bind Join 内表 IN 下推（小 IN 查询，命中索引） |
| 差集排查 | `... WHERE NOT EXISTS (SELECT 1 FROM b.t WHERE ...)` | ANTI Bind Join：内表按外表 key IN 下推，只保留无匹配的外表行 |
| 存在性排查 | `... WHERE EXISTS (SELECT 1 FROM b.t WHERE ...)` | SEMI Bind Join：内表 IN 下推，只保留有匹配的外表行 |
| 最新 N 条（单库驱动侧） | `... JOIN ... ORDER BY a.col LIMIT n` | Top-N 下推：源库只回 N 行 |
| 最新 N 条（多库分片合并） | `(SELECT ... FROM a.t UNION ALL SELECT ... FROM b.t) ORDER BY ... LIMIT n` | 分片 Top-N：每库回 offset+fetch 行，本地归并 |
| 多库合并清单 | `SELECT ... FROM a.t UNION ALL SELECT ... FROM b.t` | 各分支整条 SQL 下发，合并输出（行数熔断封顶） |
| 复合键关联 | `ON a.k1=b.k1 AND a.k2=b.k2` | tuple-IN / OR 降级分批下推 |
| 同库 JOIN / 单库查询 | 单一 schema 内 | 全部下推方言 SQL，引擎只透传（不抢 Calcite 原生下推） |

## 安全护栏与可观测性

所有注册的 `DataSource` 都被 `Guarded` 动态代理，形成四层防线：

1. **行数熔断**：语句级 `maxRows = rowLimit + 1` + 结果集拉取计数，超阈值抛 `SQLException`（危险 SQL 熔断）；最终结果、每个源库扫描、FULL 反连接全覆盖；
2. **流式拉取**：统一 `setFetchSize`，驱动侧按窗口流水线消费，不驻全量；
3. **超时传播**：`queryTimeout > 0` 时传播到每条源库语句，由源库驱动中止执行；
4. **级联取消**：`db.cancel()` 从任何线程掐断当前查询的全部在途源库语句。

再加两道闸门：

- **只读硬化**：`query/explain/analyze` 只接受查询语句，DML/DDL 在解析层拒绝（中文错误信息），杜绝误用；
- **safeMode**（可选，`db.safeMode()`）：进一步拒绝「无过滤条件的源库全表拉取」。

观测：`explain(sql)` 看物理计划（是否走了 Bind Join / Top-N 下推），`analyze(sql)` 看每个源库实际下发的 SQL 与网络行数、Bind Join 批次统计——跨库慢查询先 analyze 再定位。

配置（构造参数，默认 `1000 / 1_000_000 / 500 / 4 / 0`）：

```java
try (CrossDb db = new CrossDb(fetchSize, rowLimit, bindBatchSize, bindParallelism,
        queryTimeoutSeconds /* 0=不限 */).safeMode() /* 可选 */) { ... }
```

注意：`query(sql)` 返回的 `ResultSet` 是**一次性流式迭代**（与 JDBC 消费语义一致，不支持 reset/重复消费）；同一 `CrossDb` 同一时刻只支持一条并发查询（CalciteConnection 本身不并发安全），并发统计会串场。多条并发查询请各建一个 `CrossDb` 实例（注册的 DataSource 池是共享的，代价仅为 schema 元数据缓存）。

## Spring Boot 接入

`crossdb-spring-boot-starter` 模块提供自动装配（Spring Boot 3.x）：

```properties
# application.properties（均有默认值，可不配）
crossdb.fetch-size=1000
crossdb.row-limit=1000000
crossdb.bind-batch-size=500
crossdb.bind-parallelism=4
crossdb.query-timeout=0
crossdb.safe-mode=true
```

```java
// 数据源通过 Customizer Bean 注册，注入已有的 HikariCP DataSource 即可
@Bean
CrossDbCustomizer crossDbCustomizer(DataSource shopDs) {
  return db -> db.register("shop", shopDs);
}
```

已声明 `CrossDb` Bean 时不装配；关闭时自动 `db.close()`（destroyMethod）。

## Bind Join 当前边界（触发条件）

满足以下全部条件才改写为 Bind Join，否则**自动退回 Calcite 原生计划**（回退是正确行为，不是缺陷）：

- INNER/LEFT/RIGHT/FULL/SEMI/ANTI JOIN，连接条件**全部**为跨侧等值对（支持多列复合键），无残余非等值条件（有则退回原生计划）；RIGHT 交换内外侧执行，FULL 的反连接会把外表 distinct key 全量攒内存（受行数熔断封顶），且每块 NOT IN 均补 `key IS NULL` 保证内表 NULL key 的未匹配行不丢行（三值逻辑下 NULL 的 NOT IN 恒 UNKNOWN）；SEMI/ANTI 要求投影只引用外表列（去相关形态满足）；SEMI/ANTI 的改写还会校验过滤列可追溯到去相关器生成的**常量标记**，用户手写的 `LEFT JOIN + WHERE 右列 IS [NOT] NULL`（INNER/LEFT 语义）不会被误改写；
- 内表可整体下推为一条 JDBC SQL（单输入算子链——Scan/Filter/Project/Sort/Aggregate——顶层为表扫描，且输出列名唯一）；
- 存在至少一对等值连接 key（`NOT IN` 展开形态、跨库笛卡尔积无 key 可绑，一律回退原生计划）；
- 左右两侧来自**不同**已注册库（同库 JOIN 走原生方言下推，不抢）；
- key 经 JDBC `getObject/setObject` 传递，且两侧 key 列类型一致（类型不一致回退）；
- 哈希淘汰仅在驱动侧按 key 有序时启用（检测保守，宁可不淘汰不错杀匹配）。

safeMode 与 FULL JOIN 的补充边界：

- safeMode 的计划期检查对 Bind Join 只看**驱动侧**子树（内表必带 key IN 过滤）；FULL 的反连接 SQL 在运行期生成，故当外表耗尽后可用 key 为空（外表零行或 key 全 NULL，反连接将无 WHERE 全表拉取内表）时，由执行器在**运行期**拒绝（`CrossDbUnsafeQueryException`，挂在 RuntimeException 的 cause 链上）；
- 若优化器因代价比较选择原生 FULL JOIN 回退计划（两侧均为裸拉取），safeMode 会在计划期按「无过滤全表拉取」拦截——这与原生计划的语义一致，属预期防呆而非缺陷。

其他下推的边界：

- Top-N 下推要求排序列全部属于 join 的左操作数（驱动侧），仅 INNER/LEFT；
- 分片 Top-N 仅限 UNION ALL；每个分支须可整体下推为单库 SQL，且必须带 LIMIT（纯 ORDER BY 不推，裁不掉行就没意义）。

## 路线图（按需再补）

- ~~Statement.cancel 级联取消~~ ✅ `db.cancel()`
- ~~RIGHT / FULL JOIN 的 Bind Join 改写~~ ✅ RIGHT 交换执行 + FULL NOT IN 反连接
- ~~内表哈希表每窗口淘汰~~ ✅ 排序驱动侧自动启用
- ~~Spring Boot 轻量集成~~ ✅ `crossdb-spring-boot-starter`；Quarkus 需要时再加
- ~~EXISTS / IN 半连接 IN 下推~~ ✅ SEMI Bind Join（代价恒优于原生半连接）
- ~~NOT EXISTS 的 ANTI Bind Join~~ ✅ `AntiBindJoinFilterRule` 识别去相关形态改写（顺带修复 ANTI 执行标志接线与空 key 误改写两个缺陷）
- ~~IN 列表 1000 上限（Oracle）~~ ✅ 自动拆分 `IN (...) OR IN (...)`
- ~~JDK 21 虚拟线程并发拉取~~ ✅ 替换固定线程池，`parallelism` 限流
- ~~分片 UNION ALL Top-N 归并下推~~ ✅ `ShardTopNRule`
- ~~只读硬化~~ ✅ 解析层拒绝 DML/DDL
- NOT IN 的 IN 下推：Calcite 展开为计数聚合形态，需先支持「聚合形态内表」的 key 绑定与三值逻辑（NULL 语义）改写；当前走原生计划，结果正确
- 行数/索引元数据计价：Bind Join 半连接目前以「策略性零代价」恒优先于原生半连接，如需真实代价比较，先给引擎接入行数/索引元数据或自定义 RelSubset 约束
- 常用排障 SQL 固化为命名视图（`ViewTable`），跨查询复用
- 跨库写事务：Calcite 不提供，需引入 XA/Seata 级别的外部组件，超出本项目「纯查询、零侵入」定位，明确不做；有真实诉求时建议在应用层用 Saga/补偿，而不是下沉到查询引擎

## 单元测试覆盖

  1243 个测试分十八组（其中 11 个以 `@Disabled("待支持: …")` 标记的兼容性用例暂跳过，作为后续修复清单）：

- `GuardedTest`：熔断阈值（放行/超限拒绝）、fetchSize/maxRows/setQueryTimeout、SQL 与行数统计、在途语句取消注册表；
- `BindJoinExecTest`：流式执行器（多批次并发、去重合批、NULL key、LEFT/RIGHT 行序、FULL 反连接（含内表 NULL key 不丢行）、复合键 tuple-IN 与 OR 降级、按需拉批、排序淘汰、SEMI/ANTI 输出形态、SQL 失败传播、WHERE 构造形态与超限分片、safeMode 下反连接拦截/放行、tuple-IN 方言判定表）；
- `CrossDbTest`：端到端（JOIN+GROUP BY、WHERE/LIMIT 回归、LEFT/RIGHT/FULL 的 IN 下推、EXISTS 半连接 IN 下推、NOT EXISTS ANTI 下推、NOT IN/笛卡尔回退、复合键跨库、Top-N 下推、分片 UNION ALL Top-N（含 OFFSET）与无 Top-N 熔断、传递谓词下推、只读硬化（DML 拒绝 / CTE 放行）、safeMode 拦截（含 FULL 反连接退化拦截）、超时传播、级联取消、explain/analyze、行数熔断、非法配置/SQL 拒绝、schema 重名/空名拒绝、ResultSet 取值 API（typed getter / wasNull / 元数据））；
- `CrossDbScenariosTest`：跨库 SQL 场景覆盖（参考 Calcite/Presto・Trino/ShardingSphere/Vitess 等同类系统用例设计）：JOIN 家族（三库链式、左右/全外连接、NULL key、同库混合、子查询内表、表达式键）、子查询（IN/EXISTS/NOT EXISTS 双方向、标量子查询、派生表）、聚合（无分组多列聚合、HAVING、COUNT DISTINCT、分组表达式、空集聚合）、集合操作（UNION/INTERSECT/EXCEPT、带标签列合并、三分支 Top-N）、排序分页（多列/别名/OFFSET/LIMIT 0）、CTE（过滤/聚合/嵌套）、表达式函数（CASE/字符串/数值/IS NULL/LIKE/BETWEEN）、边界形态（小批次拆分、safeMode 组合、引号标识符）、高级窗口（LAG/LEAD/FIRST_VALUE、PARTITION 分组窗口、组内 Top-N 派生表）、分组扩展（ROLLUP/CUBE/GROUPING SETS）、TPC-H 补充形态（Q5/Q17/Q18/Q8）、集合链（UNION-EXCEPT 链、NULL 成员集合操作、分片 UNION 回流 JOIN）、VALUES/APPLY（VALUES 表跨库 JOIN、CROSS/OUTER APPLY）、空集与标量子查询边界（空驱动侧、空 IN/NOT IN、零行标量、重复 key SEMI/ANTI）、表达式与分组补充（SIMILAR TO、TIMESTAMPDIFF、区间算术、NULL 分组、多列 COUNT DISTINCT）、APPLY/LATERAL 扩展（相关 COUNT/MAX、对输出列过滤、外层聚合、analyze 形态）、SEMI/ANTI 深组合（ANTI 后 GROUP BY、OR 回退、嵌套 EXISTS、SEMI 后 LEFT JOIN）、聚合扩展（多列 COUNT DISTINCT 分组/混用/双集合、AVG(DISTINCT)、位聚合、HAVING 比标量子查询）、集合操作扩展（EXCEPT ALL 多重集语义、三分支 INTERSECT、布尔 NULL 合并）、语法兼容（LENIENT：`!=`、`LIMIT start,count`、NOT SIMILAR TO、ESCAPE 子句、COALESCE join key）；
- `CrossDbCompatibilityTest`：方言与特性兼容性覆盖（参考 PostgreSQL regress/Calcite/Trino/SQL Server/Oracle/MySQL 公开用例补充）：JOIN 扩展（USING/NATURAL、四库链、OR 条件回退、双侧聚合派生表、复合键 ANTI、IN 子查询含 UNION）、集合操作扩展（类型放宽合并、括号操作数、标准 OFFSET…FETCH、Oracle MINUS、分支内 LIMIT、CTE 含 UNION 双引用、EXCEPT-UNION 链）、窗口帧与排名（ROWS 帧三形态、NTILE、CUME_DIST/PERCENT_RANK 等价改写、NTH_VALUE 帧内语义本地实现）、聚合扩展（FILTER 条件聚合、LISTAGG、VAR/STDDEV、SUM DISTINCT）、函数扩展（TRIM 变体、SUBSTRING FROM/FOR、OVERLAY/INITCAP/FLOOR…TO 本地改写、CHR/LPAD/REPEAT/GREATEST/NVL/STRING_AGG/GROUP_CONCAT）、递归 CTE（EnumerableRepeatUnion 落地）/PIVOT/GROUP BY 别名、错误契约（非法 CAST、除零、非分组列）；
- `CrossDbExtensionsTest`：扩展场景覆盖（PostgreSQL LATERAL / SQL Server APPLY / 标准 VALUES / MySQL CONCAT 族 / Oracle MEDIAN·LISTAGG 等）：横向引用与表构造器（VALUES 派生表、LATERAL、CROSS/OUTER APPLY、UNNEST）、集合操作与分页扩展（EXCEPT/INTERSECT ALL、MySQL LIMIT a,b、ORDER BY 序数）、函数扩展（CONCAT_WS、REVERSE、CEIL(ts TO unit)、滑动帧窗口、DENSE_RANK）、聚合扩展（多列 COUNT DISTINCT、GROUPING+ROLLUP、PIVOT、LISTAGG DISTINCT、MEDIAN、PERCENTILE_CONT 本地实现）、跨库组合（CTE+HAVING、三层嵌套派生表、NOT IN 空子查询、COALESCE join key）、错误契约（歧义列、自连接裸引用）；
- `CrossDbCoverageTest`：全量场景补充覆盖（与上述用例互补，参考 PostgreSQL regress / Calcite / Trino / MySQL 8.0 / SQL Server / Oracle 公开用例）：投影与 DISTINCT（多列去重、t.* 混用、无 AS 别名）、JOIN 补充（残余非等值 LEFT JOIN、DISTINCT/LIMIT 内表派生表、复合键 SEMI、裸 CROSS JOIN、三库链、RIGHT JOIN 派生表）、子查询补充（行值 IN、多列行构造器、<> ALL、< ANY、IN+GROUP BY+HAVING、CASE 内标量子查询、NOT EXISTS NULL 语义）、聚合补充（CASE 分组键、HAVING 未选聚合、TIMESTAMP 聚合承载约定、DECIMAL AVG/SUM、COUNT DISTINCT 跳 NULL、GROUP BY 序数）、窗口补充（WINDOW 命名子句、RANGE 帧 peers、LEAD/LAG 偏移与默认值、NTH_VALUE 分区帧、空集窗口、排名派生表 Top-1）、集合补充（INTERSECT 优先级、恒假分支、三分支类型放宽、多列 UNION 去重、VALUES∪表）、VALUES 行构造器（多列+WHERE、标量表达式、行等值比较）、排序分页（LIMIT ALL、ASC 默认 NULLS LAST、分片 Top-N 升序/跨分支并列键）、表达式补充（简单 CASE、ROUND/TRUNCATE/LN/EXP、MOD 符号、|| 数值、EXTRACT DAY/EPOCH、CAST TIMESTAMP→VARCHAR、ts-INTERVAL 承载、DATE 与 TIMESTAMP 比较、CHAR 定标拼接）、NULL 三值逻辑（BETWEEN/NOT BETWEEN/LIKE 对 NULL）、递归 CTE（真实表游走、UNION 去重终止、跨库种子、输出 Top-N）、只读硬化（UPDATE/DELETE/INSERT/CREATE/DROP/ALTER/GRANT/SET/MERGE/TRUNCATE 全拒绝、explain/analyze 同拒）、safeMode（聚合归约放行、裸排序拦截、Bind Join 双侧过滤放行、聚合转置裸拉取拦截）、explain/analyze 与 ResultSet 边界（列名缺失提示、next 前取值、getBytes 类型拒收）；
- `CrossDbComprehensiveTest` / `CrossDbFullCoverageTest` / `CrossDbFullScenariosTest`：三批「全量场景 + 修复清单」覆盖——方言标量/聚合改写（TRANSLATE/SOUNDEX/IIF/ISNULL/LIKE 族/分位数/ARRAY_AGG/MOD 浮点/LOG/LOCATE/LEFT・RIGHT/SPACE・CHAR/STRCMP/DATE±n/位运算/DIV/STRAIGHT_JOIN/CAST(布尔 AS 数值)）、窗口帧扩展（GROUPS 帧 DENSE_RANK 等价改写、FILTER+OVER、IGNORE NULLS、COUNT(DISTINCT) OVER、RANGE 间隔帧、EXCLUDE）、行构造器不等比较、MATCH_RECOGNIZE 子集（自研 EnumerableCrossMatch/CrossMatchExec 回溯匹配算子：PATTERN 拼接/量词、DEFINE 符号引用、PARTITION BY、ORDER BY、ALL ROWS PER MATCH、标准 AFTER 默认）、FETCH FIRST n PERCENT、TPC-H/DS 形态、错误契约与观测面；
- `CrossDbFederatedSuiteTest`：按参考系统分组的联邦场景（PostgreSQL regression、MySQL 8.0、Trino 跨 catalog、DuckDB 现代 SQL、ShardingSphere 归并、TPC-H/DS 形态、SQL 标准一致性、Oracle/SQL Server、引擎加固面）：三值逻辑矩阵、NOT IN NULL 语义、分片 UNION 归并（Top-N/聚合/分页/分支 LIMIT 下推 Recording）、镜像分片自连接、跨库 FULL/USING/LATERAL/半反连接、Q1/Q3/Q13/Q18/Q21 形态与窗口占比、EXCEPT/INTERSECT ALL 多重集、IS [NOT] DISTINCT FROM、行构造器 IN、DECODE/ADD_MONTHS/LAST_DAY/MONTHS_BETWEEN、rowLimit/safeMode/只读/超时/取消矩阵；前几批 14 个与第五批 22 个历史待支持用例（INSTR、SUBSTRING_INDEX、XOR、DATE_FORMAT、TRY_CAST、REGEXP_REPLACE 三参、EXCLUDE、GROUP BY ALL、ARG_MIN、STRUCT 点访问、date_diff、LIST_CONTAINS、TO_CHAR、(+) 外连接、CONNECT BY、NEXT_DAY、PATTERN 区间量词/或语法、AVG(派生 SUM) 融合、跨库 DATE 连接键承载等）已全部修复启用，并伴随多项引擎缺陷修复（数值 UDF FORCE_NULLABLE 拆箱 NPE、TIMESTAMPDIFF 的 DATE 分支序、列目录系统表污染、Bind Join 全列承载转换、限定列 DATE±n、PATTERN 内位运算误伤、原子扫描 CAST 前缀）；
- `CrossDbExprMatrixTest`：sqllogictest 风格表达式求值矩阵——算术/精度（整除截断、DECIMAL 标度、模符号、DIV 零）、三值逻辑与比较（AND/OR/NOT 矩阵、IS DISTINCT FROM、IN 含 NULL、BETWEEN SYMMETRIC、行构造器比较）、字符串函数（LENGTH/SUBSTRING/POSITION/LOCATE/INSTR/SUBSTRING_INDEX/REPLACE/REVERSE/LPAD/RPAD/LEFT/RIGHT/CONCAT 族/OVERLAY/TRANSLATE/INITCAP/SOUNDEX/CHR/LIKE/ILIKE/RLIKE/SIMILAR TO）、数值函数（CEIL/FLOOR/ROUND 矩阵、LN/LOG/POWER/SQRT、MOD 浮点、CAST 矩阵、TRY_CAST）、位运算与逻辑 XOR、CASE/DECODE/IIF/NVL/ISNULL、时间函数矩阵（EXTRACT、DATE±n、TIMESTAMPDIFF 单位矩阵、DATE_FORMAT/TO_CHAR/NEXT_DAY/ADD_MONTHS/MONTHS_BETWEEN/LAST_DAY）、列上 NULL 语义（聚合跳 NULL、排序 NULLS、分组 NULL 桶）；
- `CrossDbWindowDeepTest`：窗口函数深测——排名族（ROW_NUMBER/RANK/DENSE_RANK/PERCENT_RANK/CUME_DIST、跨库 JOIN 上排名）、聚合窗口（累计/分区/移动均值/ROWS 边界组合/RANGE 对等组/窗口表达式运算）、导航族（FIRST_VALUE/LAST_VALUE 默认帧与 UNBOUNDED、NTH_VALUE 帧内、IGNORE NULLS）、WINDOW 命名复用、QUALIFY、GROUPS 帧（CURRENT ROW/1 PRECEDING/EXCLUDE CURRENT ROW）、派生表窗口与聚合套聚合屏障；
- `CrossDbJoinMatrixTest`：跨库 JOIN 矩阵——键类型矩阵（int/string/decimal/date/timestamp/boolean、CAST 对齐）、复合键（双列/三列/NULL 侧）、外连接族（LEFT/RIGHT/FULL、NULL 侧过滤即反半、三表嵌套 LEFT）、半反连接族（EXISTS/NOT EXISTS/IN/NOT IN/LEFT SEMI・ANTI/双重否定）、多表（三/四表星型链式、自连接、镜像分片 DATE 键与全列 NATURAL）、相关子查询（标量 SELECT/WHERE、LATERAL、三库 EXISTS）、下推形态断言（Bind Join IN 下推、驱动侧谓词传播、Top-N LIMIT 下推）与非等值回退正确性；
- `CrossDbSetOpsPagingTest`：集合运算与分页——UNION 族（ALL 保重/DISTINCT 去重/首分支列名/表达式分支）、INTERSECT/EXCEPT（DISTINCT 与 ALL 多重集、交换律、左结合、括号优先级）、分页（LIMIT/OFFSET、`LIMIT a,b`、FETCH FIRST/NEXT 等价、越界/LIMIT 0/LIMIT ALL、WITH TIES 并列组、PERCENT、并集分页、子查询内 LIMIT、EXISTS 内 LIMIT）、DISTINCT 组合（多列、含 NULL、COUNT DISTINCT、类型放宽分支）、边界（列数不匹配拒绝、非输出列排序拒绝、MINUS 关键字候选）；
- `CrossDbOracleMssqlDeepTest`：Oracle/SQL Server 方言深测——条件（DECODE 数对/NULL 匹配、NVL/NVL2/ISNULL/IIF 嵌套）、Oracle 日期函数矩阵（ADD_MONTHS/MONTHS_BETWEEN/LAST_DAY/NEXT_DAY 全星期与缩写/DATE±n/TO_CHAR/INSTR 起点）、CONNECT BY 变体（全树/子树/子句双序/无 NOCYCLE/PREVOR 反侧/别名/WHERE 层次后过滤/列投影/LEVEL 候选）、(+) 旧式外连接（右侧/左侧/复合条件/多匹配/复合键）、TOP 形态（ORDER BY/无序/括号/DISTINCT/PERCENT・WITH TIES 候选）、字符串与整除补充；
- `CrossDbAggModernHardeningTest`：聚合分析深测 + 现代 SQL + 引擎加固——聚合矩阵（SUM/MIN/MAX/AVG、COUNT 变体、空组、DISTINCT 聚合、HAVING 矩阵、分组表达式/序数、聚合排序、聚合套聚合修复回归、MEDIAN/PERCENTILE_CONT/DISC 分位、LISTAGG/STRING_AGG、BOOL 族、ARRAY_AGG、ANY_VALUE/MODE、ARG_MIN/MAX、FILTER、VAR_POP、多参 COUNT、GROUPING SETS/ROLLUP/CUBE 候选）、现代 SQL（QUALIFY 多窗口、EXCLUDE 多列、TRY_CAST 矩阵、STRUCT 多字段、LIST_CONTAINS、date_diff 单位矩阵、REGEXP_REPLACE、ON OVERFLOW 剥离、XOR 列上与优先级）、加固矩阵（safeMode 全表拉取逐库拒绝/Bind Join 内表放行/双侧过滤放行、JOIN 行数熔断、只读拒绝清单、explain/analyze、元数据与 typed getter NULL 语义、findColumn 大小写、配置校验、schema 重名、单遍流式消费）；
- `CrossDbAutoConfigurationTest`：Spring 配置绑定与 Customizer 装配。

自检 Main 覆盖同场景的运行时串联验证（含 ANTI 下推、只读拦截、分片 Top-N）。

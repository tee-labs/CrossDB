# AGENTS.md — CrossDB

Cross-database SQL engine on Apache Calcite: register multiple JDBC databases as
schemas, query them with one SQL. Positioning is **OLTP point lookup / ops
troubleshooting** — low latency, guards against full-table pulls and memory
blowups. Read-only by design; cross-DB write transactions are explicitly out of
scope (see README roadmap). Self-contained project; requires **JDK 21+** (uses
virtual threads).

## Layout

- `crossdb-core/` (artifactId `crossdb`, package `com.example.crossdb`) — the engine:
  - `CrossDb` — entry point: `register()` / `query()` / `explain()` / `analyze()` / `safeMode()` / `cancel()`; also enforces the read-only guard (SELECT/set-ops/CTE only) and registers all planner rules
  - `BindJoinRule`, `TopNBindJoinRule` — planner rules rewriting cross-DB joins into batched IN-pushdown (Bind Join)
  - `AntiBindJoinFilterRule` (+ `AntiBindJoinRule` for calc-shaped trees) — rewrites decorrelated `NOT EXISTS` (LEFT join + constant marker `IS NULL`) into ANTI Bind Join, and `EXISTS` marker forms into SEMI; rejects user-written real-column `IS [NOT] NULL` filters via the constant-marker check
  - `ShardTopNRule` — pushes ORDER BY + LIMIT into every branch of a cross-DB UNION ALL (each source returns offset+fetch rows; local merge keeps semantics). `CrossDb.queryProgram` also **removes upstream `EnumerableMergeUnionRule`** from the planner: it pushes `(offset+fetch)` limits into union branches even for UNION DISTINCT, truncating before dedup (loses rows); do not re-add it
  - `MultiArgCountRule` — rewrites MySQL-style multi-arg `COUNT(a, b)` into portable `COUNT(CASE WHEN a IS NOT NULL AND b IS NOT NULL THEN 1 END)` so it can push down
  - `CrossMatchRule` + `EnumerableCrossMatch` + `CrossMatchExec` — MATCH_RECOGNIZE subset: upstream `EnumerableMatch` cannot compile pattern quantifiers or symbol-referencing DEFINEs at runtime; the rule compiles a supported subset (PATTERN concat/quantifiers incl. greedy-reluctant, DEFINE symbol column refs incl. `LAST/PREV(x, 0)`, PARTITION BY, ORDER BY, ALL ROWS PER MATCH, AFTER SKIP PAST LAST ROW / TO NEXT ROW) into a local backtracking matcher. DEFINE predicates are rewritten to "expanded-row" input refs (current-row block + one block per referenced symbol) and compiled via `RexToLixTranslator`. `CrossMatchExec` must stay **public** (Calcite codegen resolves callee classes reflectively from generated code in the unnamed package — a package-private class yields Janino "no applicable method" on an identical signature). `SqlTreeRewrites` also injects the standard default `AFTER MATCH SKIP PAST LAST ROW` when absent (Calcite normalizes to SKIP TO NEXT ROW, deviating from SQL:2011/Oracle)
  - `ColumnHints` — column type catalog scanned once via JDBC `DatabaseMetaData` at first plan (invalidated on register); powers type-aware rewrites: DATE-column ± integer literal → `DATE ± INTERVAL 'n' DAY` (Oracle semantics), `CAST(boolean AS numeric)` → literal/CASE (MySQL tinyint), float-involved `MOD` → local `CROSSDB_MOD` (Java `%` semantics; integer MOD stays native for pushdown). Same-name columns across DBs merge to the wider numeric kind (int+float→float); cross-kind (date/bool) conflicts drop the name
  - `SqlRewrites` is the **facade** (`preprocess()` / `rewrite()`) over the parse-stage (pre-validation) semantic-preserving rewrite family, split by stage: `SqlTextRewrites` (statement-level text rewrites; delegates bitwise/DIV token rewrites to `BitwiseDivRewrites`), `SqlTreeRewrites` (parse-tree expression rewrites + `FromRewriter` for USING expansion / alias column lists), `LocalOperators` (the local UDF/UDAF operator table both stages mount onto), and `SqlText` (shared live-mask lexical scanning). Behavior, for dialect compatibility: `TOP n` → `FETCH FIRST`, `SIMILAR TO` / `INITCAP` / `OVERLAY` / `FLOOR・CEIL(ts TO unit)` → local UDFs, `VAR_*/STDDEV_*` args `CAST AS DOUBLE`, `CUME_DIST/PERCENT_RANK` equivalent rewrites, `NTH_VALUE` → per-arity local window UDAFs (frame-aware), `TIMESTAMPDIFF` / `LISTAGG(DISTINCT …)` / `MEDIAN` / `PERCENTILE_CONT … WITHIN GROUP` / `PERCENTILE_DISC` → local UDF/UDAF implementations, `DECODE` → `CASE WHEN … IS NOT DISTINCT FROM` (Oracle NULL=NULL equality), `IIF` → `CASE WHEN`, `ISNULL` → `COALESCE`, `ILIKE` → `LOWER(x) LIKE LOWER(p)`, `TRANSLATE` → local UDF (name clashes with the std `SqlTranslateFunction`), `ANY_VALUE`/`MODE`/`ARRAY_AGG` → local UDAFs (ARRAY_AGG renders `"[v1, v2, …]"`, value-ascending), `AGG(x) FILTER (WHERE …) OVER` → `AGG(CASE WHEN … THEN x END) OVER` (validator rejects FILTER+OVER; note pre-validation std aggregates are still unbound — `isAggregator()` is false, match by name), plus this batch: `LOG(x)`→`LN(x)`/`LOG(b,x)`→`LN(x)/LN(b)`, `SPACE(n)`→`REPEAT(' ',n)`, `CHAR(n)`→`CHR(n)`, `STRCMP`→3-branch CASE, `LEFT/RIGHT(s,n)`/`LOCATE(sub,str[,start])`/`NVL`→local UDF/COALESSE, `STRAIGHT_JOIN`→`JOIN` (statement-level hint stripped), token-level rewrites for bitwise `& | ^ ~ << >>` and `DIV` → CROSSDB_BIT*/IDIV calls (operand-chain scanner honoring MySQL/PG precedence; `||` concat and `&&` never matched), `FETCH FIRST n PERCENT ROWS` → ROW_NUMBER/COUNT window derived-table form, GROUPS frames → DENSE_RANK wrap + RANGE (flat single-relation SELECT, bare-column keys only), `FIRST_VALUE/LAST_VALUE … IGNORE NULLS OVER` → local non-null-end UDAFs, `COUNT(DISTINCT x) OVER` → local distinct-count window UDAF (upstream `EnumerableWindow` silently drops the DISTINCT qualifier), `LEFT SEMI/ANTI JOIN` → equivalent `CROSS APPLY (SELECT 1 … HAVING COUNT(*) …)`, `FETCH FIRST n ROWS WITH TIES` → first-n-distinct-keys `IN` semi-join (bare-column keys that also appear in the SELECT list), `REGEXP` → `RLIKE` → local `CROSSDB_REGEXP` (Java regex, case-sensitive), `BOOL_AND/BOOL_OR/EVERY` → local UDAFs, `LISTAGG … ON OVERFLOW ERROR` clause stripped (standard default), row-constructor `< <= > >=` expanded into lexicographic scalar comparisons, trailing statement semicolons stripped. Latest batch: `INSTR(2/3-arg)`/`SUBSTRING_INDEX`/`DATE_FORMAT`/`REGEXP_REPLACE(3-arg)`/`TO_CHAR`/`NEXT_DAY`/`ARG_MIN/ARG_MAX` local implementations; `TRY_CAST(x AS numeric)` -> per-target CROSSDB_TRY_* UDFs (failed parse -> NULL), char targets degrade to CAST; `NVL2` -> CASE IS NOT NULL; `LENGTH` -> CHAR_LENGTH; `CONCAT_WS` 4/5-arity via CROSSDB_CONCAT_WS4/5; text-level `date_diff(u,a,b)` rename, MySQL logical `XOR` -> CROSSDB_XOR (boolean-operand chain scanner; NULL literal operands allowed), `GROUP BY ALL` -> explicit keys (select items without aggregates), `SELECT * EXCLUDE (cols)` -> catalog-expanded column list (ColumnHints table catalog), Oracle `(+)` comma-join -> LEFT JOIN (two-table, (+) confined to one deficient alias), `CONNECT BY [NOCYCLE] PRIOR a=b` (+START WITH/WHERE, either clause order) -> `WITH RECURSIVE` CTE (lvl<100 cycle guard; the CTE's `level` column exposes the LEVEL pseudocolumn), DuckDB `{k: e}.k` struct-dot constant folding, `LIST_CONTAINS([literals], v)` -> `v IN (...)`. Seventh batch: `TOP n PERCENT` / `TOP n WITH TIES` -> normalized onto the FETCH FIRST PERCENT / WITH TIES standard rewrite family, `POSSTR(str, substr)` (DB2) -> CROSSDB_INSTR2 (same operand order as INSTR), `SUBSTRING(x FROM n [FOR m])` standard n<1 clamping (start clamped to 1, length shrunk by the overshoot — CASE-wrapped or literal-folded; literal n>=1 keeps the native pushdown path), MINUS/NTILE/LAG/LEAD/GROUPING SETS/ROLLUP/CUBE verified natively supported by Calcite 1.42 + LENIENT. Unsafe forms are left untouched for the validator; `SqlRewrites.preprocess(sql, hints)` is the hints-aware entry (EXCLUDE expansion needs the column catalog). Unsafe forms are left untouched for the validator
  - `CrossDbFunctions` — local scalar UDF implementations registered by `LocalOperators` (three-valued NULL logic): includes `CROSSDB_TRANSLATE`, `SOUNDEX`, `LTRIM`/`RTRIM`; keeps behavior identical regardless of which source DB evaluates what
  - `CrossDbAggregates` — local aggregate/window UDAF implementations (`CROSSDB_LISTAGG` distinct-listagg, `CROSSDB_MEDIAN`, `CROSSDB_PERCENTILE_CONT/DISC`, `CROSSDB_NTH_VALUE2/3/4` frame-aware nth-value, `CROSSDB_BOOL_AND/BOOL_OR` three-valued booleans, `CROSSDB_ARRAY_AGG`, `CROSSDB_ANY_VALUE`, `CROSSDB_MODE`, `CROSSDB_COUNT_DISTINCT`, `CROSSDB_FIRST/LAST_VALUE_NN`) + `TIMESTAMPDIFF` evaluation; wired in by `LocalOperators` (operator registration) + `SqlTreeRewrites` (call-site mounting). Note: UDAFs are registered with `requiresOrder=false` so they can serve order-less windows, and the Calcite UDAF pipeline pre-filters NULL inputs (empty/all-NULL groups yield NULL)
  - `EnumerableBindJoin` (Calcite physical rel) + `BindJoinExec` (streaming runtime; SEMI/ANTI both pass `semi=true`, ANTI additionally `anti=true`)
  - `Guarded` — DataSource proxy enforcing fetchSize, row-limit breaker, queryTimeout, statement cancel registry
  - `Stats` — per-query execution stats (SQL actually sent per source, rows pulled, Bind Join batches); exposed via `analyze()`
  - `Main` — end-to-end self-check runner
- `crossdb-spring-boot-starter/` (package `com.example.crossdb.spring`) — Spring Boot 3.x
  autoconfig: `CrossDbProperties` (`crossdb.*` props), `CrossDbCustomizer` (register DataSources),
  `CrossDbAutoConfiguration` (backs off if a `CrossDb` bean is already declared).
- `crossdb-example/` (package `com.example.crossdb.example`) — manual verification
  entry point: `ExampleMain.main` registers two in-memory H2 sources (swap-in templates
  for MySQL/PostgreSQL in comments) and runs pushdown / cross-DB join / aggregate /
  UNION ALL Top-N / explain / analyze / safeMode demos. Run with
  `mvn -q -DskipTests install` once, then `mvn -q -pl crossdb-example exec:java`.
  Not covered by JUnit tests — it is a scratchpad, not a test suite.
- `README.md` is the authoritative spec. Read it before changing Bind Join rules,
  safety guards, or fallback behavior — especially the section
  「Bind Join 当前边界（触发条件）」.

## Commands

```bash
mvn test                                                    # all JUnit 5 tests, both modules (1243; 0 failures / 0 skipped — the former 11-case @Disabled backlog has been fully fixed and re-enabled)
mvn -q -pl crossdb-core exec:java -Dexec.mainClass=com.example.crossdb.Main   # end-to-end self-check
mvn -q -DskipTests install && mvn -q -pl crossdb-example compile exec:java    # manual example entry (two H2 sources; exec skips compile)
# add -Dcrossdb.debug=true to any run to print physical plans and rule matching
# test-JVM timezone is pinned to UTC by the parent pom's <argLine> property: TIMESTAMP values
# are carried as epoch millis interpreted as UTC wall time (see Exec / CrossDbFunctions)
```

## Conventions

- All Javadoc, comments, exception messages, and docs are in **Chinese** — match that.
- 2-space indent; no formatter/linter config exists.
- Tests share in-memory H2 fixtures in `crossdb-core/src/test/.../Fixtures.java`
  (per-JVM init, read-only data). Add new fixture tables there instead of spinning
  up new H2 instances.
- `crossdb-core` must stay Spring-free; only the starter module touches Spring.
- Features not yet supported are covered by tests marked `@Disabled("待支持: …")`
  (currently **none**) — that is the tracked fix backlog convention: keep the
  assertions at standard semantics; fix the engine and re-enable rather than
  deleting or weakening them. Former backlogs fixed and re-enabled: the 4-case set
  in `CrossDbCoverageTest`, the 5-case set in `CrossDbComprehensiveTest`, the
  17-case set in `CrossDbFullCoverageTest`, the 14-case set spanning
  `CrossDbFullCoverageTest`/`CrossDbComprehensiveTest`/`CrossDbFullScenariosTest`,
  the 22-case set in `CrossDbFederatedSuiteTest`
  (INSTR、SUBSTRING_INDEX、XOR、DATE_FORMAT、TRY_CAST、REGEXP_REPLACE 三参、EXCLUDE、
  GROUP BY ALL、ARG_MIN、STRUCT 点访问、date_diff 前置形态、LIST_CONTAINS、TO_CHAR、
  (+) 外连接、CONNECT BY→递归 CTE、NEXT_DAY、NVL2、PATTERN 区间量词/或语法、
  AVG(派生 SUM) 融合、跨库 DATE 连接键承载——伴随 FORCE_NULLABLE 拆箱 NPE、
  toDateTime DATE 分支序、ColumnHints 系统表污染、Bind Join 全列承载转换、
  限定列 DATE±n、PATTERN 内位运算误伤、原子扫描 CAST/TIMESTAMP 前缀等多项引擎修复),
  and the seventh batch 11-case set (SUBSTRING FROM<1 裁剪语义、POSSTR、NTILE、
  LAG/LEAD、MINUS 关键字、LEVEL 伪列、TOP n PERCENT、TOP n WITH TIES、GROUPING
  SETS、ROLLUP、CUBE — 其中 NTILE/LAG/LEAD/MINUS/GROUPING SETS 族经复核
  Calcite 1.42 原生可用，修正断言至标准语义即启用；其余四项为引擎修复：TOP
  PERCENT/WITH TIES 归一到 FETCH 标准改写族、CONNECT BY 改写的 CTE 层级列命名
  level 直接承载 LEVEL 伪列、POSSTR 挂 INSTR 实现、SUBSTRING 的 n<1 CASE 夹取改写).
- The sixth batch of scenario tests (one-shot complement modeled on sibling
  systems' public suites, ~321 cases) lives in `CrossDbExprMatrixTest`
  (sqllogictest-style expression/3VL/string/numeric/bitwise/conditional/
  datetime matrices), `CrossDbWindowDeepTest` (ranking/aggregate/navigation
  windows, frames incl. GROUPS, WINDOW clause, QUALIFY), `CrossDbJoinMatrixTest`
  (cross-DB join key-type matrix incl. DATE/TIMESTAMP/BOOLEAN keys, composite
  keys, outer/semi/anti families, multi-way, correlated/LATERAL, pushdown
  shape assertions), `CrossDbSetOpsPagingTest` (UNION/INTERSECT/EXCEPT ×
  DISTINCT/ALL, precedence, LIMIT/OFFSET/FETCH/WITH TIES/PERCENT, DISTINCT),
  `CrossDbOracleMssqlDeepTest` (DECODE/NVL/NVL2/IIF/ISNULL, Oracle date
  functions, CONNECT BY variants, (+) outer joins, TOP forms), and
  `CrossDbAggModernHardeningTest` (aggregate deep matrix incl. LISTAGG/
  PERCENTILE/ARG_MIN/MODE/FILTER, nested aggregates, modern SQL — QUALIFY/
  EXCLUDE/TRY_CAST/STRUCT/LIST_CONTAINS/XOR/date_diff — plus safeMode/
  rowLimit/read-only/metadata hardening). When adding new rewrites or engine
  behavior, extend these classes rather than spawning new fixture H2s.


## Gotchas

- Numeric/boolean-returning local UDFs must use `SqlTypeTransforms.FORCE_NULLABLE`
  (see `LocalOperators.forced`): `TO_NULLABLE` only nulls the type when some
  operand is nullable, so literal-argument calls derive NOT NULL and the
  generated code unboxes the UDF result unconditionally (`Integer.intValue()`)
  — null-returns (MOD by 0, TRY_CAST failures…) then NPE.
- `CrossDbAggregates.toDateTime` must test `Integer` (DATE epoch-days carrier)
  BEFORE the `Number` (TIMESTAMP epoch-millis) branch, or DATE arguments get
  misread as milliseconds.
- `ColumnHints.scanSources` filters system schemas (information_schema/pg_*)
  and keys the table→columns catalog by registered-schema name; `SELECT *
  EXCLUDE` expansion depends on it. Same-named columns across DBs still merge by
  bare name for the category hints.
- Bind Join reads the inner ResultSet directly, bypassing Calcite's
  representation normalization — `EnumerableBindJoin.colTypes` (all inner
  columns' SqlTypeNames) drives `BindJoinExec.readValue/bindValue` (DATE↔epoch
  days, TIMESTAMP↔epoch millis) for rows, keys and parameters; outer keys are
  normalized with the paired `rightKeys` type.
- The Bind Join inner-pushdown wrapper SQL is hand-built (`BindJoinRule.wrapInner`):
  the derived-table alias must omit the `AS` keyword for Oracle (table aliases
  reject AS — ORA-00933; only column aliases may use it). Never hardcode
  `... ) AS "T"` in new hand-built source SQL — go through `wrapInner`.
- `CrossDb.plan()` runs `fixNestedJdbcAggregates` after planning: JdbcAggregate
  over JdbcAggregate would be rendered by JdbcImplementor as one nested-agg SQL
  (`SUM(SUM(x))`) that H2 rejects (upstream JDBC-adapter flaw); the fix
  re-parents the outer aggregate chain onto local Enumerable. A logical-layer
  barrier does NOT survive — optimizer rules remove pure-identity rels.
- `BitwiseDivRewrites` must not touch `MATCH_RECOGNIZE ... PATTERN (...)` spans
  (`|` is alternation there), and its atom scanners treat `CAST(`/`TIMESTAMP(`
  style prefixes as function-call atoms (`FUNC_PREFIXES` exception to
  `EXPR_KEYWORDS`); `<<`/`>>` operands are arithmetic chains (MySQL/PG
  precedence: shifts bind looser than `+ -`).
- `CrossDb.plan()` appends a rename/trim projection whenever the optimized rel's
  field names drift from the validated row type — JDBC pushdown absorbs projection
  aliases into source scans (labels fall back to catalog column names) and
  `RelRoot.isRefTrivial()` alone doesn't catch it. ResultSet labels therefore
  follow validated output names (aliases, UNION first-branch names).
- One `query(sql)` ResultSet = one streaming consumption (no reset/re-iteration);
  one concurrent query per `CrossDb` instance (CalciteConnection is not
  thread-safe). Do not add concurrent query paths.
- Bind Join intentionally falls back to native Calcite plans when its triggering
  conditions don't hold (residual non-equi conditions, same-DB join, inner side
  not fully pushable, no equi pair — e.g. NOT IN's count-aggregate expansion,
  cross-DB cartesian products). Fallback is correct behavior, not a bug to fix.
- Every registered DataSource is wrapped by `Guarded`; the row-limit breaker applies
  to the final result, every source scan, and the FULL-join anti-join.
- TIMESTAMP values are carried as epoch millis (Long) interpreted as **UTC wall
  time** through the engine (Exec typed getters, `CROSSDB_FLOOR/CEIL/TIMESTAMPDIFF`,
  CAST-to-VARCHAR rendering). On a non-UTC JVM, wall-clock renderings shift by the
  zone offset — the parent pom therefore pins surefire's test JVM to
  `-Duser.timezone=UTC` via the `argLine` property (plain `mvn test` works on any
  machine; CI is UTC Linux).
- safeMode intentionally rejects `COUNT(*)`-over-cross-DB-join shapes: the optimizer
  transposes the aggregate into two single-column pulls and the right side becomes an
  unfiltered full pull. Row-level joins with a driver-side filter pass (inner side
  gets the transitive predicate).
- Calcite codegen (Janino) resolves callee classes reflectively from generated code
  in the unnamed package: any crossdb class invoked from `EnumerableRel.implement`
  must be **public** (a package-private class fails with a baffling Janino
  "no applicable method" on a byte-identical signature), and avoid crossdb nested
  types in the generated method signature (pass `Object[]`/`String[]` specs and
  rebuild at runtime — see `CrossMatchExec.buildPattern`).
- Two same-named UDF operators of different arity (e.g. a 2-arg and 3-arg
  CROSSDB_LOCATE) trip `SqlTypeExplicitPrecedenceList`'s ANY assertion during
  by-name re-lookup; register per-arity distinct names (the GREATEST/LEAST
  precedent: CROSSDB_LOCATE2/CROSSDB_LOCATE3).
- Dev machines are Windows with no native Maven/JDK; run tests via WSL Ubuntu:
  `wsl -d Ubuntu -- bash -lc 'cd /mnt/d/CrossDB && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test'`
  (the pom's argLine already pins the test JVM to UTC).
  CI (`.github/workflows/opencode.yml`) runs an OpenCode
  agent on Linux, triggered by `/oc` comments on issues/PRs. Note: the parent pom
  pins maven-compiler-plugin 3.13.0 because Maven ≤3.8's default compiler plugin
  ignores `maven.compiler.release`.

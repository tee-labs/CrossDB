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
  - `CrossMatchRule` + `EnumerableCrossMatch` + `CrossMatchExec` — MATCH_RECOGNIZE subset: upstream `EnumerableMatch` cannot compile pattern quantifiers or symbol-referencing DEFINEs at runtime; the rule compiles a supported subset (PATTERN concat/quantifiers incl. greedy-reluctant, DEFINE symbol column refs incl. `LAST/PREV(x, 0)`, PARTITION BY, ORDER BY, ALL ROWS PER MATCH, AFTER SKIP PAST LAST ROW / TO NEXT ROW) into a local backtracking matcher. DEFINE predicates are rewritten to "expanded-row" input refs (current-row block + one block per referenced symbol) and compiled via `RexToLixTranslator`. `CrossMatchExec` must stay **public** (Calcite codegen resolves callee classes reflectively from generated code in the unnamed package — a package-private class yields Janino "no applicable method" on an identical signature). `SqlRewrites` also injects the standard default `AFTER MATCH SKIP PAST LAST ROW` when absent (Calcite normalizes to SKIP TO NEXT ROW, deviating from SQL:2011/Oracle)
  - `SqlRewrites.ColumnHints` — column type catalog scanned once via JDBC `DatabaseMetaData` at first plan (invalidated on register); powers type-aware rewrites: DATE-column ± integer literal → `DATE ± INTERVAL 'n' DAY` (Oracle semantics), `CAST(boolean AS numeric)` → literal/CASE (MySQL tinyint), float-involved `MOD` → local `CROSSDB_MOD` (Java `%` semantics; integer MOD stays native for pushdown). Same-name columns across DBs merge to the wider numeric kind (int+float→float); cross-kind (date/bool) conflicts drop the name
  - `SqlRewrites` — parse-stage (pre-validation) semantic-preserving rewrites for dialect compatibility: `TOP n` → `FETCH FIRST`, `SIMILAR TO` / `INITCAP` / `OVERLAY` / `FLOOR・CEIL(ts TO unit)` → local UDFs, `VAR_*/STDDEV_*` args `CAST AS DOUBLE`, `CUME_DIST/PERCENT_RANK` equivalent rewrites, `NTH_VALUE` → per-arity local window UDAFs (frame-aware), `TIMESTAMPDIFF` / `LISTAGG(DISTINCT …)` / `MEDIAN` / `PERCENTILE_CONT … WITHIN GROUP` / `PERCENTILE_DISC` → local UDF/UDAF implementations, `DECODE` → `CASE WHEN … IS NOT DISTINCT FROM` (Oracle NULL=NULL equality), `IIF` → `CASE WHEN`, `ISNULL` → `COALESCE`, `ILIKE` → `LOWER(x) LIKE LOWER(p)`, `TRANSLATE` → local UDF (name clashes with the std `SqlTranslateFunction`), `ANY_VALUE`/`MODE`/`ARRAY_AGG` → local UDAFs (ARRAY_AGG renders `"[v1, v2, …]"`, value-ascending), `AGG(x) FILTER (WHERE …) OVER` → `AGG(CASE WHEN … THEN x END) OVER` (validator rejects FILTER+OVER; note pre-validation std aggregates are still unbound — `isAggregator()` is false, match by name), plus this batch: `LOG(x)`→`LN(x)`/`LOG(b,x)`→`LN(x)/LN(b)`, `SPACE(n)`→`REPEAT(' ',n)`, `CHAR(n)`→`CHR(n)`, `STRCMP`→3-branch CASE, `LEFT/RIGHT(s,n)`/`LOCATE(sub,str[,start])`/`NVL`→local UDF/COALESSE, `STRAIGHT_JOIN`→`JOIN` (statement-level hint stripped), token-level rewrites for bitwise `& | ^ ~ << >>` and `DIV` → CROSSDB_BIT*/IDIV calls (operand-chain scanner honoring MySQL/PG precedence; `||` concat and `&&` never matched), `FETCH FIRST n PERCENT ROWS` → ROW_NUMBER/COUNT window derived-table form, GROUPS frames → DENSE_RANK wrap + RANGE (flat single-relation SELECT, bare-column keys only), `FIRST_VALUE/LAST_VALUE … IGNORE NULLS OVER` → local non-null-end UDAFs, `COUNT(DISTINCT x) OVER` → local distinct-count window UDAF (upstream `EnumerableWindow` silently drops the DISTINCT qualifier), `LEFT SEMI/ANTI JOIN` → equivalent `CROSS APPLY (SELECT 1 … HAVING COUNT(*) …)`, `FETCH FIRST n ROWS WITH TIES` → first-n-distinct-keys `IN` semi-join (bare-column keys that also appear in the SELECT list), `REGEXP` → `RLIKE` → local `CROSSDB_REGEXP` (Java regex, case-sensitive), `BOOL_AND/BOOL_OR/EVERY` → local UDAFs, `LISTAGG … ON OVERFLOW ERROR` clause stripped (standard default), row-constructor `< <= > >=` expanded into lexicographic scalar comparisons, trailing statement semicolons stripped. Unsafe forms are left untouched for the validator
  - `CrossDbFunctions` — local scalar UDF implementations registered by `SqlRewrites` (three-valued NULL logic): includes `CROSSDB_TRANSLATE`, `SOUNDEX`, `LTRIM`/`RTRIM`; keeps behavior identical regardless of which source DB evaluates what
  - `CrossDbAggregates` — local aggregate/window UDAF implementations (`CROSSDB_LISTAGG` distinct-listagg, `CROSSDB_MEDIAN`, `CROSSDB_PERCENTILE_CONT/DISC`, `CROSSDB_NTH_VALUE2/3/4` frame-aware nth-value, `CROSSDB_BOOL_AND/BOOL_OR` three-valued booleans, `CROSSDB_ARRAY_AGG`, `CROSSDB_ANY_VALUE`, `CROSSDB_MODE`, `CROSSDB_COUNT_DISTINCT`, `CROSSDB_FIRST/LAST_VALUE_NN`) + `TIMESTAMPDIFF` evaluation; wired in by `SqlRewrites`. Note: UDAFs are registered with `requiresOrder=false` so they can serve order-less windows, and the Calcite UDAF pipeline pre-filters NULL inputs (empty/all-NULL groups yield NULL)
  - `EnumerableBindJoin` (Calcite physical rel) + `BindJoinExec` (streaming runtime; SEMI/ANTI both pass `semi=true`, ANTI additionally `anti=true`)
  - `Guarded` — DataSource proxy enforcing fetchSize, row-limit breaker, queryTimeout, statement cancel registry
  - `Stats` — per-query execution stats (SQL actually sent per source, rows pulled, Bind Join batches); exposed via `analyze()`
  - `Main` — end-to-end self-check runner
- `crossdb-spring-boot-starter/` (package `com.example.crossdb.spring`) — Spring Boot 3.x
  autoconfig: `CrossDbProperties` (`crossdb.*` props), `CrossDbCustomizer` (register DataSources),
  `CrossDbAutoConfiguration` (backs off if a `CrossDb` bean is already declared).
- `README.md` is the authoritative spec. Read it before changing Bind Join rules,
  safety guards, or fallback behavior — especially the section
  「Bind Join 当前边界（触发条件）」.

## Commands

```bash
mvn test                                                    # all JUnit 5 tests, both modules (922; 22 @Disabled("待支持: …") compatibility cases skip by design)
mvn -q -pl crossdb-core exec:java -Dexec.mainClass=com.example.crossdb.Main   # end-to-end self-check
# add -Dcrossdb.debug=true to any run to print physical plans and rule matching
# run with -DargLine="-Duser.timezone=UTC" on a non-UTC machine: TIMESTAMP values
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
  (currently 22, all dialect candidates in `CrossDbFederatedSuiteTest`: INSTR、
  SUBSTRING_INDEX、XOR、DATE_FORMAT、TRY_CAST、REGEXP_REPLACE 三参、EXCLUDE、
  GROUP BY ALL、ARG_MIN、STRUCT 点访问、date_diff 前置形态、LIST_CONTAINS、
  TO_CHAR、(+) 外连接、CONNECT BY、NEXT_DAY、PATTERN 区间量词 {n,m}、PATTERN
  或语法、AVG(派生 SUM) 融合、跨库 DATE 连接键承载) — they are the tracked fix
  backlog. Keep the assertions at standard semantics; fix the engine and re-enable
  rather than deleting or weakening them. Former backlogs fixed and re-enabled:
  the 4-case set in `CrossDbCoverageTest`, the 5-case set in
  `CrossDbComprehensiveTest`, the 17-case set in `CrossDbFullCoverageTest`, and
  the 14-case set spanning `CrossDbFullCoverageTest`/`CrossDbComprehensiveTest`/
  `CrossDbFullScenariosTest` (GROUPS 帧 DENSE_RANK 改写、MATCH_RECOGNIZE 自研
  CrossMatch 算子、MOD 浮点、LOG、LOCATE、LEFT/RIGHT、SPACE/CHAR、STRCMP、
  DATE±n、位运算、DIV、FETCH FIRST n PERCENT、STRAIGHT_JOIN、CAST(布尔 AS 数值)).


## Gotchas

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
  zone offset — run tests with `-Duser.timezone=UTC` (CI is UTC Linux).
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
  `wsl -d Ubuntu -- bash -lc 'cd /mnt/d/CrossDB && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test -Duser.timezone=UTC'`.
  CI (`.github/workflows/opencode.yml`) runs an OpenCode
  agent on Linux, triggered by `/oc` comments on issues/PRs. Note: the parent pom
  pins maven-compiler-plugin 3.13.0 because Maven ≤3.8's default compiler plugin
  ignores `maven.compiler.release`.

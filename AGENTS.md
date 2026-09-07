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
  - `ShardTopNRule` — pushes ORDER BY + LIMIT into every branch of a cross-DB UNION ALL (each source returns offset+fetch rows; local merge keeps semantics)
  - `MultiArgCountRule` — rewrites MySQL-style multi-arg `COUNT(a, b)` into portable `COUNT(CASE WHEN a IS NOT NULL AND b IS NOT NULL THEN 1 END)` so it can push down
  - `SqlRewrites` — parse-stage (pre-validation) semantic-preserving rewrites for dialect compatibility: `TOP n` → `FETCH FIRST`, `SIMILAR TO` / `INITCAP` / `OVERLAY` / `FLOOR・CEIL(ts TO unit)` → local UDFs, `VAR_*/STDDEV_*` args `CAST AS DOUBLE`, `CUME_DIST/PERCENT_RANK` equivalent rewrites, `NTH_VALUE` → per-arity local window UDAFs (frame-aware), `TIMESTAMPDIFF` / `LISTAGG(DISTINCT …)` / `MEDIAN` / `PERCENTILE_CONT … WITHIN GROUP` → local UDF/UDAF implementations. Unsafe forms are left untouched for the validator
  - `CrossDbFunctions` — local scalar UDF implementations registered by `SqlRewrites` (three-valued NULL logic); keeps behavior identical regardless of which source DB evaluates what
  - `CrossDbAggregates` — local aggregate/window UDAF implementations (`CROSSDB_LISTAGG` distinct-listagg, `CROSSDB_MEDIAN`, `CROSSDB_PERCENTILE_CONT`, `CROSSDB_NTH_VALUE2/3/4` frame-aware nth-value) + `TIMESTAMPDIFF` evaluation; wired in by `SqlRewrites`
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
mvn test                                                    # all JUnit 5 tests, both modules (441; 4 @Disabled("待支持: …") compatibility cases skip by design)
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
  (currently 4, in `CrossDbCoverageTest`) — they are the tracked fix backlog. Keep the
  assertions at standard semantics; fix the engine and re-enable rather than deleting
  or weakening them. The former 8-case `待支持/待修复` backlog in
  `CrossDbCompatibilityTest` / `CrossDbExtensionsTest` was fixed and re-enabled.

## Gotchas

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
- Dev machines are Windows; CI (`.github/workflows/opencode.yml`) runs an OpenCode
  agent on Linux, triggered by `/oc` comments on issues/PRs. Note: the parent pom
  pins maven-compiler-plugin 3.13.0 because Maven ≤3.8's default compiler plugin
  ignores `maven.compiler.release`.

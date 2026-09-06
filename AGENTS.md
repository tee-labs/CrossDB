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
  - `EnumerableBindJoin` (Calcite physical rel) + `BindJoinExec` (streaming runtime; SEMI/ANTI both pass `semi=true`, ANTI additionally `anti=true`)
  - `Guarded` — DataSource proxy enforcing fetchSize, row-limit breaker, queryTimeout, statement cancel registry
  - `Main` — end-to-end self-check runner
- `crossdb-spring-boot-starter/` (package `com.example.crossdb.spring`) — Spring Boot 3.x
  autoconfig: `CrossDbProperties` (`crossdb.*` props), `CrossDbCustomizer` (register DataSources),
  `CrossDbAutoConfiguration` (backs off if a `CrossDb` bean is already declared).
- `README.md` is the authoritative spec. Read it before changing Bind Join rules,
  safety guards, or fallback behavior — especially the section
  「Bind Join 当前边界（触发条件）」.

## Commands

```bash
mvn test                                                    # all 259 JUnit 5 tests (both modules)
mvn -q -pl crossdb-core exec:java -Dexec.mainClass=com.example.crossdb.Main   # end-to-end self-check
# add -Dcrossdb.debug=true to any run to print physical plans and rule matching
```

## Conventions

- All Javadoc, comments, exception messages, and docs are in **Chinese** — match that.
- 2-space indent; no formatter/linter config exists.
- Tests share in-memory H2 fixtures in `crossdb-core/src/test/.../Fixtures.java`
  (per-JVM init, read-only data). Add new fixture tables there instead of spinning
  up new H2 instances.
- `crossdb-core` must stay Spring-free; only the starter module touches Spring.

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
- Dev machines are Windows; CI (`.github/workflows/opencode.yml`) runs an OpenCode
  agent on Linux, triggered by `/oc` comments on issues/PRs. Note: the parent pom
  pins maven-compiler-plugin 3.13.0 because Maven ≤3.8's default compiler plugin
  ignores `maven.compiler.release`.

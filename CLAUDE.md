# CLAUDE.md: Cloud Kitchen Orchestration System

Read this file at the start of **every** session. It is short on purpose; the details live in `docs/`.

**Current status:** Phase 0 (documentation baseline) is **complete, frozen and committed** (2026-10-10, commit `992c1fe`). Phase 1 (skeleton + zero-install spike) is **complete**: one local commit tagged `phase-1-complete` holds the code, tests and evidence (see `docs/EVIDENCE.md`); nothing has been pushed, and Windows, macOS and standalone Tomcat remain unverified. Phase 2 (domain and services, no database) is **complete**: one local commit tagged `phase-2-complete` holds the code, tests and evidence (`docs/EVIDENCE.md` EV-016..EV-019); nothing has been pushed. Phase 3 (persistence) is **complete**: one local commit tagged `phase-3-complete` holds the code, tests and evidence (`docs/EVIDENCE.md` EV-020..EV-025); nothing has been pushed; real MySQL 8 compatibility is **unverified** (only MariaDB was available). Phase 4 has not started and needs the user's explicit go-ahead. Implemented: the skeleton, the pure domain and services, the versioned schema and seed (`V001`/`V002` applied by `SchemaInitializer`), `Database` with deadlock retry, six JDBC DAOs (including lease, fencing and compensation statements), `OrderService`, `AuthService`, `MenuService`, `StockService`, `OrderQueryService`, `UserSeeder`, the application context and a temporary read-only page `GET /demo/orders`. **No station queue, worker, orchestrator, sweeper, monitor, simulator, file log, snapshot, login flow or other web page exists.** Do not claim anything else is implemented. The active phase is recorded in the table at the end of this file.

## 1. What this project is
A Java web app (Servlets + JSP + JDBC + MySQL-compatible DB, Tomcat 10.1) for an undergraduate project, built by one developer with Claude Code across several sessions. It accepts simulated orders from Swiggy, Zomato and an in-house app, validates them, routes each to its brand's kitchen station, processes them concurrently, tracks the lifecycle and persists everything.

## 2. Source of truth and reading order
1. `docs/PROJECT_OVERVIEW.md`: scope, assumptions, decision log, glossary.
2. `docs/SRS.md`: requirements (IDs), use cases, acceptance criteria.
3. `docs/SDD.md` and `docs/DATABASE_DESIGN.md`: exact classes, methods, routes, tables.
4. `docs/IMPLEMENTATION_ROADMAP.md`: phase tasks, exit criteria, gate commands.
5. `docs/TESTING_STRATEGY.md`, `docs/TRACEABILITY.md`, `docs/JAVA_CONCEPT_MAPPING.md`, `docs/EVIDENCE.md`.

**The docs are the source of truth.** If code and docs disagree, or a doc is wrong or incomplete: **stop and report the conflict** to the user (what, where, options). Do not silently fix either side. Use the same terminology as the glossary in every file, comment and message.

## 3. Locked decisions (never change; report conflicts instead)
- **Zero-install:** only JDK 17+, Git, browser, Claude Code. **Never require or install** Maven, MySQL, Tomcat, Docker or any system software. Use Maven Wrapper (`./mvnw`, `mvnw.cmd`). Tomcat 10.1 is **embedded** (`tomcat-embed-core`, `tomcat-embed-jasper` 10.1.x) and started by `Launcher` via `./mvnw exec:java`; the build also produces a standard WAR for standalone Tomcat 10.1. The DB is embedded **MariaDB4j** started by `DbBootstrap`, data in `./data` (git-ignored).
- **SQL** stays in the common MySQL/MariaDB subset so the same scripts run on MySQL 8 by changing config only. Driver: try **MySQL Connector/J** first; if the Phase 1 spike shows incompatibility use **MariaDB Connector/J**. DAO code is plain driver-agnostic JDBC. Fallback ladder and decision log: `docs/PROJECT_OVERVIEW.md` §6. **H2 only with the user's explicit approval.**
- **Stack:** Java 17, Servlet 6.0, JSP 3.1, Jakarta JSTL, JUnit 5, SLF4J + Logback, HikariCP. **No** Spring, JPA, Hibernate, Mockito. No other dependency without approval. **Recorded deviation DEV-1** (`docs/PROJECT_OVERVIEW.md` §6.5a): `spring-core` and `spring-jcl` arrive only as transitive dependencies of MariaDB4j; never declare or import Spring ourselves (`ArchitectureRulesTest`); they are not in the WAR. Web tests: embedded Tomcat on a random port + `java.net.http.HttpClient`.
- **Domain:** abstract `Order`; `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder` override `calculateDeliveryEstimate()` and `processPayment()`; interfaces `Trackable`, `Payable`; one kitchen station per brand; roles `ADMIN`, `KITCHEN_STAFF`.
- **Lifecycle:** `PLACED, VALIDATED, ROUTED, PREPARING, PACKED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, FAILED`. Transitions: PLACED->VALIDATED|FAILED|CANCELLED; VALIDATED->ROUTED|CANCELLED|FAILED; ROUTED->PREPARING|CANCELLED|FAILED; PREPARING->PACKED|FAILED; PACKED->OUT_FOR_DELIVERY; OUT_FOR_DELIVERY->DELIVERED|FAILED. DELIVERED/CANCELLED/FAILED terminal. Enforced **inside the `OrderStatus` enum**. Stock restored on CANCELLED/FAILED only if the order had not reached PREPARING.
- **Inventory:** item-level stock per station with `reorder_level`; atomic conditional `UPDATE ... SET stock_qty = stock_qty - ? WHERE ... AND stock_qty >= ?` inside the order-creation transaction; multi-item deduction in sorted `item_id` order; deadlock (SQLState 40001 / code 1213) retried up to 3 times.
- **Money/time/text:** `BigDecimal` + `DECIMAL(10,2)`, never `double`/`float`; UTC timestamps; utf8mb4; UTF-8 filter.
- **Multithreading (mandatory):** one bounded `ArrayBlockingQueue` per station; one `StationWorker` per station on an `ExecutorService` owned by `OrderOrchestrator`; `FeedSimulator` on a `ScheduledExecutorService` with start/stop/rate; scheduled snapshot task; queue-full = `offer` with timeout then compensation (restore stock, mark FAILED, log, report); threads named (`station-<brand>-worker`); `InterruptedException` restores the flag and exits cleanly; a failure in one order never kills a worker; **no shared `Connection`** (each task borrows a pooled one); all transitions are conditional updates (`WHERE status = ?`); `UNIQUE (source, external_ref)` + in-flight `ConcurrentHashMap` guard; graceful shutdown (stop accepting, drain/persist, `awaitTermination` with timeout, `shutdownNow`) triggered by **both** a `ServletContextListener` and a JVM shutdown hook; metrics with `AtomicLong` (submitted, processed, failed, rejected, average processing time).
- **Order flow:** validate (pure) -> TX1 (insert order + items + conditional stock deduction + VALIDATED + history) -> commit -> route and enqueue -> conditional update to ROUTED. Enqueue failure -> compensating transaction. On startup reload every non-terminal order from the DB and re-enqueue (**DB is the source of truth**).
- **Files:** NIO append-only thread-safe `orders.log` (structured events); CSV daily report; paths from config; **no PII or secrets in logs**.
- **Serialization:** `Serializable` `OrderSnapshotDTO` (`serialVersionUID`, plain fields only) of in-flight orders written periodically and at shutdown to `snapshots/`, signed HMAC-SHA256 (secret from config); on startup verify HMAC, then deserialize through an `ObjectInputFilter` allowlist, insert only orders missing from the DB. **Never deserialize web input or uploads.** No credentials, connections or thread state in snapshots.
- **Web:** MVC; POST-redirect-GET with flash messages; CSRF token on every POST form; `AuthFilter` + `RoleFilter` on every protected route; PBKDF2WithHmacSHA256 (>= 600,000 iterations, configurable, salted, constant-time compare); session id regenerated at login; 30-minute timeout; full invalidation at logout; HttpOnly + SameSite=Lax cookies, `Secure` via config; 5 failed logins lock the account for 5 minutes; generic 400/403/404/500 pages without stack traces; dashboard auto-refresh every 5 s showing low-stock flags, queue sizes/capacity, worker states, metrics; order page shows status history. **Initial user passwords come from git-ignored config at first bootstrap; never in docs, code or commits.**
- **Out of scope:** recipes/ingredients, real payment/delivery APIs, microservices, HTTPS, WebSockets, customer accounts, email password reset.
- **Tiers:** MUST, SHOULD (CSV export, admin menu/stock management, worker panel), COULD (order search, public tracking page, admin password reset, charts). COULD only if everything else passes (Phase 8).

### Approved additions (2026-10-10; not deviations; details in `docs/PROJECT_OVERVIEW.md` §6.5)
- **Routing rules RR-1..RR-11** (`docs/SDD.md` §7.8): worker routes a VALIDATED order itself; never compensate after a successful `offer`; transient DB errors are retried/parked, never turned into FAILED; terminal orders are never processed; cancel re-reads and retries.
- **`OrphanSweeper`** (`docs/SDD.md` §7.9): thread `reconcile-scheduler`, configurable schedule, re-enqueues only genuine orphans, completes pending compensations separately; changes state only through the standard conditional transitions.
- **Account enforcement** (`docs/SDD.md` §12.10): `AuthFilter` validates `enabled`, role and `session_version` against the DB on every protected request (fail closed); disabled accounts' sessions are rejected and invalidated.
- **Atomicity** (`docs/SDD.md` §13.1): one transaction per workflow; conditional updates and unique indexes are guards, not the atomicity mechanism; metrics, logs, flash and queue operations happen only after commit.
- **Durable compensation** (`docs/SDD.md` §7.10): `orders.compensation_state` marker; the caller is answered only after FAILED or the PENDING marker is committed (else `INDETERMINATE`); a pending order is never leased, routed or processed, also after a restart; recovery resolves pending compensations before any enqueue.
- **Worker health and fencing** (`docs/SDD.md` §7.11): ownership of an order is a database lease with a fencing epoch (in-memory sets are hints only); `WorkerMonitor` detects dead/stalled workers, restarts them on the same queue and reclaims leased orders; restart budget then `DEAD`.
- **Complete fencing** (`docs/SDD.md` §7.11.12): every worker mutation is a transaction whose first statement is the fenced conditional `UPDATE` (epoch + owner + valid lease + status); later statements run only if it changed one row. `StationWorker` may call only `acquireLease/renewLease/releaseLease/transitionAsOwner/failAsOwner`; `ArchitectureRulesTest` enforces the call-site rules of §7.12.5.
- **Lease rules** (`docs/SDD.md` §7.11.13): renewal never revives an expired, cleared or superseded lease; retired workers never acquire; expiry without supersession re-acquires, supersession stands down.
- **Lost-id recovery** (`docs/SDD.md` §7.11.14): `WorkerMonitor` reconciles durable state with the physical queue every health interval; the sweeper runs the same repair routine as a second, independent path (plus a coarse stale-order query); hint sets are never proof.
- **Lease stamp protocol** (`docs/SDD.md` §7.11.15): lease-validity timestamps are read only inside the DAO as the last action before the statement executes (no lease method has a timestamp parameter); renewal and owner-fenced steps require `lease_expires_at > stamp + margin` with the exact epoch and owner; a statement or commit slipping beyond `margin/2` is rolled back (`LeaseStampStaleException`) and retried with a fresh stamp; an expired lease is never revived, ownership is regained only via `acquireLease` (new epoch).
- **Shared repair** (`docs/SDD.md` §7.13): `WorkerMonitor` and `OrphanSweeper` both run `repairStation` (on-demand health, `restartWorker(code, expectedGeneration, ...)`, lease reclaim for retired generations only, reconcile); reconcile never writes lease columns; acquire accepts a free lease or the same owner string; DAO lease methods read the `Clock` internally; after a 0-row step the worker continues, re-acquires or stands down per rule L-5.
- **Compensation operator** (`docs/SDD.md` §7.10.11): only the ADMIN *Retry compensation* operation (TX4, audited) resolves a PENDING order; `cancelOrder` refuses it; nothing may clear the marker without the guarded stock restore.
- Any future change that contradicts a locked decision must be recorded as a *deviation* in `docs/PROJECT_OVERVIEW.md` §6.5 and approved first.

## 4. Package structure (root `com.cloudkitchen`; full list in `docs/SDD.md` §3)
```
bootstrap  config  domain  domain.enums  domain.contract  exception  dto
dao  dao.jdbc  db  service  pipeline  io  snapshot  util
web.controller  web.filter  web.listener  web.support
```
Layering: `web -> service -> dao -> db`; `domain` and `pipeline` have no servlet types; only `dao.jdbc`, `db`, `bootstrap` (and snapshot recovery through `db`) use `java.sql`; `ObjectInputStream` only in `snapshot`. Enforced by `ArchitectureRulesTest`.

Non-Java layout: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/`, `config/application.properties.example` (committed) and `config/application.properties` (git-ignored), `src/main/resources/db/V00n__*.sql`, `src/main/webapp/WEB-INF/{web.xml,views/**,tags/**}`, `src/test/java/com/cloudkitchen/{<package>,testsupport}`. Runtime dirs (git-ignored): `data/`, `logs/`, `snapshots/`, `reports/`.

## 5. Naming and coding conventions
- Classes `UpperCamelCase`; methods/fields `lowerCamelCase`; constants `UPPER_SNAKE`; packages lower-case; SQL `lower_snake` (tables plural); config keys `lower.dotted`.
- Tests: `<ClassUnderTest>Test` (concurrency: `...ConcurrencyTest`/`...RaceTest`; web: `...WebTest`) in the mirrored package; names in `docs/TESTING_STRATEGY.md` §13 are authoritative.
- Servlets `<Thing>Servlet`, filters `<Thing>Filter`, DAO interfaces `<Thing>Dao`, JDBC impls `Jdbc<Thing>Dao`.
- Entities: private fields, constructor validation, no public setters for identity fields, unmodifiable collections out. Read-only results are records. `@Override` always.
- Inject `java.time.Clock` wherever "now" matters; no `System.currentTimeMillis()` in business logic.
- All SQL via `PreparedStatement`; DAOs take a `Connection` parameter and never commit/close it; transactions only through `Database.inTransaction`.
- Every thread: named; every `InterruptedException` handler restores the flag; every scheduled task body catches exceptions.
- Logging: SLF4J only; never log customer names, addresses, passwords, hashes, secrets, session ids; money in logs only as ids/codes.
- JSPs: EL + JSTL only, no scriptlets, escape all output, under `WEB-INF/views`.
- Comments: sparse; explain *why*, not *what*. Javadoc on public types.

## 6. Per-phase rules (non-negotiable)
1. **Scope fence:** do only the current phase's tasks (`docs/IMPLEMENTATION_ROADMAP.md`). Need something from a later phase? Stop and report.
2. **Docs are the source of truth;** report conflicts, do not silently resolve them.
3. **Never claim a test passed without evidence in `docs/EVIDENCE.md`** (real command output, date). Failures are recorded too.
4. **Never commit secrets** (passwords, HMAC secret, DB credentials, real tokens). Config with secrets is git-ignored; the `.example` file has placeholders only. Run the secret scan before every commit that ends a phase.
5. **Never require system installs.** If a task seems to need one, stop and report; use the fallback ladder only with the user's approval.
6. At the end of every phase: update `docs/TRACEABILITY.md` statuses, add **viva notes** to `docs/JAVA_CONCEPT_MAPPING.md` §3, add entries to `docs/EVIDENCE.md`, update the decision log, tick the roadmap boxes, then create the local tag `phase-<n>-complete` on the designated branch (push tags only if the user approves).
7. **Stop at the phase gate and wait for the user's approval** before starting the next phase.
8. No flaky tests: investigate every intermittent failure as a bug; never `@Disabled`, retry or lengthen sleeps to hide one. Concurrency tests are `@RepeatedTest(20)` and must pass 20/20.
9. Do not add dependencies, change locked decisions, or widen scope without asking.
10. If asked "is X done?", answer from TRACEABILITY.md and EVIDENCE.md, not from memory.

## 7. Git rules
- Develop on branch `claude/cloud-kitchen-orchestration-7d1aok`; never push elsewhere without permission. Commit in small, descriptive commits; push with `git push -u origin <branch>` (retry network failures with exponential backoff: 2s, 4s, 8s, 16s).
- Do not create a pull request unless the user asks. Do not rewrite or overwrite existing user files (`README.md`, `.gitignore` are only appended to, with approval, per the roadmap).
- Commit messages end with the attribution trailer supplied by the environment. Do not put model identifiers in code, comments or docs.

## 8. Build / run / test commands
**Verified in Phase 1 on Linux only** (Ubuntu 24.04, JDK 21, Maven 3.9.16 via the wrapper, run as an unprivileged user; evidence EV-001..EV-014 in `docs/EVIDENCE.md`). The Windows forms (`mvnw.cmd`) and macOS are **not verified**, and the WAR has not been deployed on a standalone Tomcat.

**Prerequisites:** JDK 17+ (tested with 21), Git, a browser, internet access for the first build (Maven Central only). Nothing else is installed. **Run everything as an ordinary (non-root) operating-system user:** the embedded MariaDB server refuses to run as `root` (EV-002). The first run unpacks the MariaDB binaries (about 0.5 GB) into `data/runtime`.

| Purpose | Command (Windows: `mvnw.cmd`, unverified) |
|---|---|
| Show Maven (wrapper) version | `./mvnw -v` (prints Maven 3.9.16) |
| Compile + run all tests (also builds the WAR) | `./mvnw -q clean verify` |
| One or more test classes | `./mvnw -q test -Dtest='DbSpikeTest,EmbeddedTomcatSpikeTest'` |
| Skip integration-tagged tests | `./mvnw -q test -DexcludedGroups=integration` (no test is tagged yet; not exercised) |
| Run the app (embedded Tomcat + embedded DB) | `./mvnw compile exec:java`, then open `http://localhost:8080/health` (body `OK`), `http://localhost:8080/demo/orders` (read-only order list from the database, Phase 3) or `http://localhost:8080/spike.jsp` (Phase 1 spike page; there is no `/` page yet). The first start creates the schema, the reference data and the two accounts from `bootstrap.*` in the config file (all four values are required while the user table is empty). Stop with Ctrl+C. |
| Build the WAR | `./mvnw -q package` (output `target/*.war`; built by `verify` too) |
| Check WAR contents | `jar tf target/*.war` (must contain JSTL, HikariCP, the driver and Logback; must not contain `tomcat-embed`, `mariaDB4j`, Spring, servlet-api) |
| Config | copy `config/application.properties.example` to `config/application.properties` and replace **every** `CHANGE_ME...` value (the app refuses to start otherwise); the file is git-ignored. `snapshot.hmac.secret` (>= 32 characters) is required |
| Secret scan | see `docs/IMPLEMENTATION_ROADMAP.md` G-SECRET |

**Ports and files:** web server `127.0.0.1:8080` (`server.port`, `0` = random; no shutdown port); embedded database `127.0.0.1:3307` (`db.embedded.port`), grants enforced, loopback only (SDD §2.3a); runtime data in git-ignored `data/` (database `data/db`, binaries `data/runtime`, Tomcat `data/tomcat`, test scratch `data/test`).

**Shutdown:** Ctrl+C (SIGINT) or SIGTERM runs the shutdown hook, which stops Tomcat and then the database and logs `Embedded database stopped` / `Cloud Kitchen stopped` (exit code 130 for SIGINT, 143 for SIGTERM; verified by `LauncherShutdownSpikeTest`, Linux only). The embedded launcher sets `logbackDisableServletContainerInitializer` so that Logback keeps logging while Tomcat stops (EV-014).

**Environment-specific (only when Maven cannot reach Central through a proxy, as in the cloud sandbox):** add `-Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1` to a Maven command when you see `Name or service not known` or HTTP 429 from Central; add `-U` once to retry a download that Maven cached as failed. Offline runs (`-o`) work after the first build.

## 9. Reporting a conflict (format)
`CONFLICT: <short title>` / where (file + section) / what the docs say / what the code or reality shows / options (with consequences) / recommendation. Then wait for the user.

## 10. Phase status
| Phase | Name | Status |
|---|---|---|
| 0 | Documentation baseline | **Complete, frozen 2026-10-10**, committed `992c1fe` |
| 1 | Skeleton + zero-install spike | **Complete** (local commit + tag `phase-1-complete`, not pushed); verified on Linux only (EV-001..EV-014), limitations in EV-013 |
| 2 | Domain + services (no DB) | **Complete** (local commit + tag `phase-2-complete`, not pushed); 680 tests in the full build (EV-016) |
| 3 | Persistence | **Complete** (local commit + tag `phase-3-complete`, not pushed); 1047 tests in the full build (EV-020); MySQL 8 unverified |
| 4 | Concurrent pipeline | **Next**, not started (needs the user's go-ahead) |
| 5 | Resilience (simulator, recovery, shutdown, metrics) | Not started |
| 6 | File handling + serialization | Not started |
| 7 | Web layer | Not started |
| 8 | Integration audit + Review 2 pack | Not started |

Update this table (and nothing else here) when a phase starts or is approved.

# CLAUDE.md: Cloud Kitchen Orchestration System

Read this file at the start of **every** session. It is short on purpose; the details live in `docs/`.

**Current status:** documentation baseline only. **No application code, SQL or build files exist yet.** Do not claim anything is implemented. The active phase is recorded in the table at the end of this file.

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
- **Stack:** Java 17, Servlet 6.0, JSP 3.1, Jakarta JSTL, JUnit 5, SLF4J + Logback, HikariCP. **No** Spring, JPA, Hibernate, Mockito. No other dependency without approval. Web tests: embedded Tomcat on a random port + `java.net.http.HttpClient`.
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
**UNVERIFIED until Phase 1 completes.** These are the planned forms; Phase 1 must confirm or correct them and remove this banner.

| Purpose | Command (Windows: `mvnw.cmd`) |
|---|---|
| Show Maven (wrapper) version | `./mvnw -v` |
| Compile + run all tests | `./mvnw -q clean verify` |
| One test class | `./mvnw -q test -Dtest=OrderStatusTest` |
| Skip integration-tagged tests | `./mvnw -q test -DexcludedGroups=integration` |
| Run the app (embedded Tomcat + embedded DB) | `./mvnw compile exec:java` then open `http://localhost:8080/` |
| Build the WAR | `./mvnw -q package` (output `target/*.war`) |
| Check WAR contents | `jar tf target/*.war` |
| Config | copy `config/application.properties.example` to `config/application.properties` and edit (git-ignored) |
| Secret scan | see `docs/IMPLEMENTATION_ROADMAP.md` G-SECRET |

## 9. Reporting a conflict (format)
`CONFLICT: <short title>` / where (file + section) / what the docs say / what the code or reality shows / options (with consequences) / recommendation. Then wait for the user.

## 10. Phase status
| Phase | Name | Status |
|---|---|---|
| 0 | Documentation baseline | Written, awaiting user approval |
| 1 | Skeleton + zero-install spike | Not started |
| 2 | Domain + services (no DB) | Not started |
| 3 | Persistence | Not started |
| 4 | Concurrent pipeline | Not started |
| 5 | Resilience (simulator, recovery, shutdown, metrics) | Not started |
| 6 | File handling + serialization | Not started |
| 7 | Web layer | Not started |
| 8 | Integration audit + Review 2 pack | Not started |

Update this table (and nothing else here) when a phase starts or is approved.

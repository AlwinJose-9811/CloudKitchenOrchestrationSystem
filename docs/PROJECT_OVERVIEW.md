# Project Overview

**Project:** Cloud Kitchen Orchestration System (undergraduate project, single developer)
**Document status:** Phase 0 documentation baseline **complete and frozen on 2026-10-10**; the user approved it and it is committed as `992c1fe` on branch `claude/cloud-kitchen-orchestration-7d1aok`. **Phase 1 (skeleton + zero-install spike) is committed locally and tagged `phase-1-complete` on that branch (not pushed):** only what EVIDENCE.md records as run is verified (Linux only); every other statement describes intent, not existing behaviour. After the freeze, a document changes only through the conflict-report procedure (CLAUDE.md §9) plus a recorded decision.
**Related:** [SRS](SRS.md) · [SDD](SDD.md) · [DATABASE_DESIGN](DATABASE_DESIGN.md) · [JAVA_CONCEPT_MAPPING](JAVA_CONCEPT_MAPPING.md) · [IMPLEMENTATION_ROADMAP](IMPLEMENTATION_ROADMAP.md) · [TRACEABILITY](TRACEABILITY.md) · [TESTING_STRATEGY](TESTING_STRATEGY.md) · [EVIDENCE](EVIDENCE.md) · [../CLAUDE.md](../CLAUDE.md)

---

## 1. Objectives

1. Build a working **Cloud Kitchen Orchestration System**: a Java web application that accepts orders from Swiggy, Zomato and an in-house app (all simulated), validates them, routes each to the kitchen station of the right brand, processes them concurrently, tracks the full order lifecycle, and persists everything in a MySQL-compatible database.
2. Demonstrate, in real working code, every mandatory Java concept: classes/objects, encapsulation, abstraction, inheritance, polymorphism, interfaces, abstract classes, overloading, overriding, collections, generics, enums, custom exceptions, multithreading and concurrency control, file I/O, serialization, JDBC with prepared statements and transactions, servlets, JSP and sessions (exact mapping in [JAVA_CONCEPT_MAPPING](JAVA_CONCEPT_MAPPING.md)).
3. Be **correct under concurrency**: no oversold stock, no duplicate orders, no lost orders after a crash, no double side effects.
4. Be **runnable with zero system installs**: only JDK 17+, Git, a browser and Claude Code.
5. Be **defensible in review**: every claim backed by a requirement ([SRS](SRS.md)), a design element ([SDD](SDD.md)), a test ([TRACEABILITY](TRACEABILITY.md)) and recorded evidence ([EVIDENCE](EVIDENCE.md)).

## 2. System summary

Orders arrive as `OrderRequest`s (from the feed simulator or the web form), are validated by a pure `OrderValidator`, persisted with stock deduction in one transaction (TX1), routed onto the bounded queue of the brand's station, and processed by one `StationWorker` per station through `PLACED -> VALIDATED -> ROUTED -> PREPARING -> PACKED -> OUT_FOR_DELIVERY -> DELIVERED` (or `CANCELLED` / `FAILED`). The database is the source of truth; on startup every non-terminal order is reloaded and re-enqueued. A signed snapshot of in-flight orders is also written periodically and at shutdown. Staff (ADMIN, KITCHEN_STAFF) monitor and operate through a servlet/JSP web application with a self-refreshing dashboard.

Architecture overview: [SDD §2](SDD.md).

## 3. Scope

### 3.1 Scope tiers

| Tier | Meaning | Items |
|---|---|---|
| **MUST** | Project fails without it. Built and verified in its phase. | Zero-install build/run; embedded Tomcat + embedded DB; WAR; domain model and polymorphism; lifecycle enforced in `OrderStatus`; validation; TX1 with atomic stock deduction, sorted order, deadlock retry; idempotency (unique constraint + in-flight guard); station queues, workers, orchestrator; queue-full compensation with durable pending state and ADMIN retry; DB leases with fencing and worker monitor/queue reconciliation; orphan sweeper (backstop); fault isolation; interrupt handling; metrics; feed simulator with start/stop/rate; startup recovery from DB; graceful shutdown (listener + hook); orders.log (NIO); signed and filtered snapshots with recovery; login, sessions, lockout, per-request account-status enforcement, CSRF, roles, filters; PRG + flash; generic error pages; dashboard; order page with history; simulator control page; UTF-8; security headers; docs, traceability, evidence |
| **SHOULD** | Planned and done after the phase's MUSTs. | CSV daily report export; admin menu management; admin stock management; worker panel (pause/resume); in-house order entry form; snapshot-on-demand button; enable/disable staff accounts; basic usability checklist; sustained-load smoke test |
| **COULD** | Only if every MUST and SHOULD passes and is evidenced. | Order search; public tracking page; admin password reset; dashboard charts |

Requirement-level priorities are in [SRS §3](SRS.md).

### 3.2 Out of scope
Recipes/ingredients, real payment or delivery APIs, microservices, HTTPS (cookie `Secure` flag is configurable only), WebSockets, customer accounts, email password reset. Also out of scope: multi-node deployment, internationalisation beyond UTF-8 data, mobile apps, payment reconciliation, log rotation of `orders.log`.

## 4. Assumptions

Items marked **[APPROVAL]** need the user's explicit decision before the phase that depends on them.

| ID | Assumption | Needed by |
|---|---|---|
| A-01 **[APPROVAL]** | "Zero-install" means no *system* software beyond JDK 17+, Git and a browser, but the **first build needs outbound internet access** to download the Maven distribution (via the wrapper) and dependencies and embedded-DB binaries (all published on Maven Central). Afterwards the local Maven repository (`~/.m2`) caches them. **Phase 1 fact:** Confirmed 2026-10-10 on Linux: the first build downloads the wrapper zip, Maven 3.9.16, plugins, MariaDB4j 3.3.1 (about 492 MB unpacked), Connector/J, HikariCP, JUnit and Tomcat from Maven Central only. Central rate-limits with HTTP 429 and, behind the agent proxy, two Maven flags were needed (EV-007). | Phase 1 |
| A-02 **[APPROVAL]** | The embedded MariaDB4j server (a native `mysqld` binary shipped inside a Maven artifact and started as a child process) runs on the developer's OS. On Linux it may need common shared libraries (for example `libaio`, `libncurses`) and may refuse to run as `root`. If the Phase 1 spike shows a system package is required, the work stops and the fallback ladder (§6.3) is presented to the user; nothing is installed silently. **Phase 1 fact:** Confirmed 2026-10-10 on Ubuntu 24.04: no system package was needed (`ldd` shows no missing library), but `mariadbd` refuses to run as the operating-system `root` user (EV-002); it ran correctly as the unprivileged user `claude` (EV-003). MariaDB4j's default arguments are also insecure (EV-004); see D-043. | Phase 1 |
| A-03 | Maven Wrapper is created **without Maven being installed**: the official wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, "only-script" type) are fetched from the official source in Phase 1, because `mvn wrapper:wrapper` itself requires Maven. The "only-script" type avoids committing a wrapper JAR (`*.jar` is git-ignored). **Phase 1 fact:** Confirmed 2026-10-10 (EV-001): wrapper 3.3.4 `only-script` zip from Maven Central, SHA-1 verified, no JAR committed; `distributionSha256Sum` pinned for Maven 3.9.16. | Phase 1 |
| A-04 **[APPROVAL]** | The existing `.gitignore` is **appended to (never rewritten)** in Phase 1 with: `target/`, `data/`, `logs/`, `snapshots/`, `reports/`, `config/application.properties`, `*.ser`, IDE folders, OS files. Not done in this documentation session because the instruction was not to modify existing files. | Phase 1 |
| A-05 | Three demo brands/stations (`BIRYANI`, `BURGER`, `PIZZA`) with five menu items each. Names, prices and stock levels are placeholders the user may change (open point O-4). | Phase 3 |
| A-06 | Preparation, packing and delivery are **simulated** with configurable delays by the `StationWorker`; no rider or customer interaction exists. | Phase 4 |
| A-07 | All displayed times are UTC and labelled as such. | Phase 7 |
| A-08 **[APPROVAL]** | `README.md` stays untouched until Phase 8, when a "Quick start" section is appended with the user's consent. | Phase 8 |
| A-09 | One application instance per database (single JVM). `InFlightRegistry` is per-JVM; the database unique constraint is the authoritative duplicate guard. | all |
| A-10 | Dependency versions are chosen in Phase 1 (latest stable Tomcat 10.1.x, MariaDB4j, HikariCP, JDBC driver, Logback, JUnit 5), pinned explicitly (no ranges), and recorded in the decision log with the date. This document intentionally states no version numbers it has not verified. | Phase 1 |
| A-11 | Tests never read real credentials; they generate random secrets and users at runtime. | Phase 2+ |
| A-12 **[APPROVAL]** | All work happens on branch `claude/cloud-kitchen-orchestration-7d1aok`. Phase tags (`phase-<n>-complete`) are created locally on that branch; **pushing tags** and opening a pull request happen only when the user asks. | each phase end |
| A-13 | Java language level 17 (records, `switch` expressions and text blocks may be used; preview features are not). | Phase 1 |
| A-15 | The embedded database keeps its default durability (`innodb_flush_log_at_trx_commit = 1`); verified in spike S-17. If the embedded distribution defaults differently, the setting is overridden and recorded, or the deviation is reported. | Phase 1 |
| A-14 **[APPROVAL]** | "HikariCP as the only other dependency besides the embedded server/DB artifacts" is read as: the JDBC driver, Jakarta Servlet/JSP/JSTL APIs and implementation, MariaDB4j, Tomcat Embed and Jasper count as server/DB artifacts; SLF4J + Logback are in the locked stack; JUnit 5 is test scope; Maven plugins (compiler, surefire, war, exec, resources) are build tooling. No other library will be added without approval. | Phase 1 |

## 5. Limitations (accepted)

| ID | Limitation |
|---|---|
| L-01 | No HTTPS; `Secure` cookie flag only via configuration. |
| L-02 | Metrics are in-memory and reset at restart. |
| L-03 | `orders.log` is append-only without rotation. |
| L-04 | Snapshots are signed but not encrypted and contain the customer name; protect the directory with OS permissions. |
| L-05 | Orders recovered from a snapshot only (database lost) are inserted without re-deducting stock. |
| L-06 | Single node; no clustering or leader election. |
| L-07 | Commission rates and delivery-time formulas are constants in code. |
| L-08 | ~~A disabled user keeps an existing session until timeout~~ **Resolved 2026-10-10:** disabled accounts are rejected on the next protected request (D-033). Remaining: a request already past the filter when the account is disabled completes; failed-login lockout deliberately does not end existing sessions. |
| L-09 | Payment and delivery are simulated; there are no external API calls. |
| L-10 | Native serialization is used because the course requires it; it is deliberately confined, signed and allowlisted ([SDD §9](SDD.md)). |
| L-11 | The embedded database is for development/demo, not production. |
| L-12 | Dashboard refresh uses `<meta refresh>` (page reload), not push updates. |
| L-13 | The first build requires internet access (assumption A-01). |
| L-14 | Platform coverage: only operating systems actually tried are claimed in [EVIDENCE](EVIDENCE.md). |

## 6. Decision log

Status values: **Locked** (given by the user, not to be changed; conflicts are reported), **Decided** (derived and consistent with the locked decisions), **Proposed** (needs approval), **Pending** (to be settled by a Phase 1 spike), **Done** (settled; recorded with date and evidence).

### 6.1 Locked decisions (summary; full text in [../CLAUDE.md](../CLAUDE.md))

| ID | Locked decision |
|---|---|
| LD-01 | Environment: zero-install; Maven Wrapper; embedded Tomcat 10.1 started by `Launcher` via `./mvnw exec:java`; standard WAR also built; embedded MariaDB4j started by `DbBootstrap`; data in `./data` (git-ignored); SQL within the common MySQL/MariaDB subset. |
| LD-02 | Driver: try MySQL Connector/J first; if the Phase 1 spike shows incompatibility use MariaDB Connector/J; DAO code plain driver-agnostic JDBC. |
| LD-03 | Fallbacks (only through this log): portable MySQL zip, a machine with MySQL, H2 MySQL-mode **only with explicit user approval**. |
| LD-04 | Stack: Java 17, Servlet 6.0, JSP 3.1, Jakarta JSTL, JUnit 5, SLF4J + Logback, HikariCP. No Spring, JPA, Hibernate, Mockito. Web tests on embedded Tomcat, random port, `java.net.http.HttpClient`. |
| LD-05 | Domain: abstract `Order`; `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder` override `calculateDeliveryEstimate()` and `processPayment()`; interfaces `Trackable`, `Payable`; one station per brand; roles `ADMIN`, `KITCHEN_STAFF`. |
| LD-06 | Lifecycle statuses and transition table; enforced inside `OrderStatus`; stock restored on CANCELLED/FAILED only if the order had not reached PREPARING. |
| LD-07 | Inventory: item-level stock per station with `reorder_level`; atomic conditional UPDATE in the order-creation transaction; sorted `item_id`; deadlock retry up to 3 (SQLState 40001 / code 1213). |
| LD-08 | Money `BigDecimal`/`DECIMAL(10,2)`; UTC timestamps; utf8mb4; UTF-8 filter. |
| LD-09 | Multithreading design (queues, workers, executor owned by orchestrator, simulator, snapshot task, queue-full compensation, named threads, interrupt handling, fault isolation, no shared connections, conditional transitions, UNIQUE + in-flight guard, graceful shutdown by listener and hook, AtomicLong metrics). |
| LD-10 | Order flow: validate -> TX1 -> commit -> route/enqueue -> conditional ROUTED; compensation on enqueue failure; startup reload of non-terminal orders. |
| LD-11 | Files: NIO append-only `orders.log`; CSV daily export; paths from config; no PII/secrets in logs. |
| LD-12 | Serialization: `OrderSnapshotDTO`, HMAC-SHA256, ObjectInputFilter allowlist, insert only missing orders; never deserialize web input. |
| LD-13 | Web: MVC, PRG + flash, CSRF on every POST, AuthFilter + RoleFilter, PBKDF2 (>= 600,000 iterations), session regeneration, 30-minute timeout, secure cookies, lockout (5 failures / 5 minutes), generic error pages, 5-second dashboard refresh, initial passwords only from git-ignored config. |
| LD-14 | Out of scope: recipes, real payment/delivery APIs, microservices, HTTPS, WebSockets, customer accounts, email reset. |
| LD-15 | Scope tiers MUST / SHOULD / COULD; COULD only if everything else passes. |

### 6.2 Derived and proposed decisions

| ID | Decision | Rationale | Status |
|---|---|---|---|
| D-001 | Embedded MariaDB4j is the MySQL-compatible server | Only embedded option that speaks MySQL protocol with no install; MariaDB is MySQL-compatible for the chosen SQL subset | Decided (spike confirms) |
| D-002 | JDBC driver selection | Locked order: Connector/J first, MariaDB Connector/J if incompatible. Spike checklist in [IMPLEMENTATION_ROADMAP Phase 1](IMPLEMENTATION_ROADMAP.md) | Settled 2026-10-10: MySQL Connector/J 9.7.0, accepted by the user (6.7) |
| D-003 | Servlets/filters/listener declared in `web.xml`, not annotations | Filter order is deterministic only in the descriptor; one descriptor serves embedded Tomcat and the WAR | Decided |
| D-004 | Station queues carry only the order id (`Long`) | DB is the source of truth; queue content is disposable and rebuilt at startup; small memory footprint | Decided |
| D-005 | Submissions rejected by validation, insufficient stock or duplicate are **not persisted**; they are logged, counted and returned to the caller | Keeps the `orders` table to real orders; avoids dead rows for aggregator retries | Decided |
| D-006 | `orders.stock_deducted` flag guards stock restore | Guarantees exactly-once restore under races (cancel vs compensation) and correctness for snapshot-recovered orders | Decided |
| D-007 | Worker is driven by the *current DB status* (fresh auto-commit reads) and **routes a still-VALIDATED order itself** (no claim retry/sleep) | Makes recovery resumable and resolves the race between "enqueue" and "ROUTED update" without changing the locked flow (revised after the 2026-10-10 routing review, SDD §7.8) | Decided, revised, awaiting approval of the review |
| D-008 | Statuses as `VARCHAR` + `CHECK`, not SQL `ENUM` | Portability and simple migrations | Decided |
| D-009 | `DATETIME(3)` in UTC set by the application | Deterministic tests, no time-zone conversion, no 2038 limit | Decided |
| D-010 | Manual wiring through `AppContext`; no DI framework | Locked stack forbids Spring; keeps lifecycle explicit | Decided |
| D-011 | Layering enforced by `ArchitectureRulesTest` (source scan), not ArchUnit | No extra dependency | Decided |
| D-012 | Surefire only (no Failsafe); integration tests tagged `integration` | Fewer plugins for one developer | Decided |
| D-013 | `OrderSnapshotDTO` uses `String` money and epoch-millis timestamps | "Plain fields only"; keeps the deserialization allowlist to three classes | Decided |
| D-014 | Snapshot = `.ser` file + separate `.hmac` file; HMAC verified before deserialization | Cannot deserialize unauthenticated bytes | Decided |
| D-015 | `CsrfFilter` runs before `AuthFilter`; a session is created on `GET /login` to hold the token | Login POST must also be CSRF-protected | Decided |
| D-016 | Login failures, unknown users and locked accounts share one generic message | Avoids account enumeration | Decided |
| D-017 | Embedded Tomcat/Jasper/MariaDB4j declared `provided`; `exec:java` uses `classpathScope=test`; WAR excludes them | Prevents duplicate servlet APIs inside standalone Tomcat; fallback: Maven profile | Settled 2026-10-10 (6.7) |
| D-018 | Maven Wrapper "only-script" files fetched from the official source | Maven not installed; no wrapper JAR committed | Settled 2026-10-10 (6.7) |
| D-019 | Recovery runs on thread `recovery-loader`; `OrderService.accepting=false` until all recovered ids are enqueued | Avoids queue-full failures of new orders during recovery without blocking startup | Decided |
| D-020 | Feed simulator starts stopped (unless `simulator.autostart=true`) | Predictable demos and tests | Decided |
| D-021 | Dashboard refresh by `<meta http-equiv="refresh" content="5">` | No JavaScript; strict CSP | Decided |
| D-022 | Every order has a random `tracking_code` | Cheap now, enables the COULD tracking page without a schema change | Decided |
| D-023 | Configuration file `config/application.properties` (git-ignored) with committed `.example`; startup fails if missing | Satisfies "secrets only in git-ignored config" | Decided |
| D-024 | Event logging is best-effort (a failed write never fails an order) | Order integrity outranks log completeness; failures logged via SLF4J | Decided |
| D-025 | Tests build `AppConfig` programmatically with random secrets | No credentials in the repository | Decided |
| D-026 | `OrderService.transition` is the single place for status change + history + conditional stock restore | One audited code path | Decided |
| D-027 | Post-offer rule: once the queue accepted the id the submitter never compensates or terminates the order; a failed `ROUTED` update is benign (RR-1, RR-2, RR-11) | Prevents a queued order from being failed by the submitter while a worker owns it | **Approved 2026-10-10 (user)** |
| D-028 | Workers classify failures: transient DB errors are retried in place and parked, never turned into FAILED; permanent errors fail the order (RR-8) | "No silent loss" during outages without failing healthy orders | **Approved 2026-10-10 (user)** |
| D-029 | `UNIQUE (order_id, from_status, to_status)` on `order_status_history` | Database backstop so a duplicate transition cannot commit (invariant I-4) | **Approved 2026-10-10 (user)** |
| D-030 | `cancelOrder` re-reads and retries (max 3) instead of failing on a lost harmless race (RR-7) | Avoids false "cannot cancel" messages during routing | **Approved 2026-10-10 (user)** |
| D-031 | Recovery fails orders whose station is missing/inactive (RR-9); compensation has bounded extra retries and a `COMPENSATION_PENDING` state (RR-10) | Nothing is left unexplained in the database | **Approved 2026-10-10 (user)** |
| D-032 | Orphan sweeper (`OrphanSweeper`, thread `reconcile-scheduler`, configurable schedule; SDD §7.9) | Removes the "stuck until restart" residual risk U-1 while the JVM runs; identifies orphans by staleness + unknown-to-station + not-pending-compensation + fresh re-check; handles `COMPENSATION_PENDING` separately; adds no new way to change state | **Approved 2026-10-10 (user), in baseline** |
| D-033 | Account status enforced on every protected request: `AuthFilter` re-validates `enabled`/role/`session_version` from the database, rejects and invalidates stale sessions, fails closed; `staff_users.session_version` added; admin enable/disable (SHOULD) with self/last-admin guards (SDD §12.10) | User decision 2026-10-10 (answer 4); replaces accepted limitation L-08 | **Approved 2026-10-10 (user)** |
| D-034 | Workflow atomicity is provided only by one transaction per workflow; conditional updates and unique indexes are guards; post-commit actions (metrics, log, flash, queue) never drive correctness; durability setting verified (SDD §13.1, TXR-1..7) | User instruction 2026-10-10 | **Approved 2026-10-10 (user)** |
| D-035 | **Durable pending compensation**: `orders.compensation_state/reason/attempts/next_at`; answer-after-durable rule with `INDETERMINATE`; database predicates (lease acquisition, `VALIDATED->ROUTED`) plus worker, recovery, sweeper and snapshot exclusions; recovery resolves pending compensations before any enqueue; retry with backoff forever; the only manual escape hatch is the ADMIN operation *Retry compensation* (TX4, audited), staff cancel is refused for pending orders (SDD §7.10, §7.10.11). Replaces the in-memory marker | User instruction 2026-10-10; closes residual risk U-2 | **Approved 2026-10-10 (user, schema change recorded in §6.5)** |
| D-036 | **Worker health, database leases and fencing**: `orders.claim_epoch/claimed_by/lease_expires_at`, fenced worker updates, `WorkerMonitor` (died/stalled detection, restart with new generation, lease reclaim, restart budget, DEAD), sweeper decisions based on DB lease not `activeIds`, startup lease clearing, `WorkerHook` test seam (SDD §7.11) | User instruction 2026-10-10; in-memory sets must not be the only proof of active processing | **Approved 2026-10-10 (user, schema change recorded in §6.5)** |
| D-037 | **Complete fencing**: every worker mutation catalogued (WM-1..WM-9) and executed as a transaction whose first statement is the fenced conditional update (rules F-1..F-8); `transitionAsOwner`/`failAsOwner` are the only worker entry points; history actor `worker:<CODE>#<generation>`; call-site restrictions enforced by `ArchitectureRulesTest` (SDD §7.11.12, §7.12.5) | Final adversarial audit finding F-A1 (unfenced `failOrder`) and F-A2 | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |
| D-038 | **Lease predicates**: renewal and steps require epoch, owner and a valid lease (never revive); acquire only for free leases; re-acquire after expiry without supersession, stand down otherwise; retired workers never acquire; `now` read inside the DAO; new keys `worker.reacquire.max` (SDD §7.11.13) | Audit findings F-B1, F-B2 | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |
| D-039 | **Lost-id recovery by reconcile**: `WorkerMonitor` compares durable state with the physical queue every health interval (VALIDATED after `recovery.dispatch.window.seconds`); the sweeper runs the same repair routine as a second, independent path and keeps a coarse stale-order query (see D-041); new keys `worker.reconcile.batch`, `recovery.dispatch.window.seconds` (SDD §7.11.14) | Audit findings F-C1, F-C2: recovery must not depend on in-memory sets or the 120 s orphan timeout | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |
| D-040 | **Operator compensation retry**: ADMIN-only `retryCompensation` and `/admin/compensations`; `cancelOrder` refuses PENDING orders; no operation clears the marker without the guarded restore (SDD §7.10.11) | Audit finding F-D1 | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |
| D-041 | **Shared repair routine and lost-reply idempotence**: `OrderOrchestrator.repairAll/repairStation` run by both `WorkerMonitor` and `OrphanSweeper`; worker health evaluated on demand; `restartWorker(code, expectedGeneration, reason)` compare-and-set; acquire allowed for the same owner string; rule L-5 gains a *continue* case; lease/fenced DAO methods read the `Clock` internally (SDD §7.13) | Final verification review 2026-10-10 (corrections 1-5 of §7.13.8) | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |
| D-042 | **Lease stamp protocol**: fresh in-DAO stamp as the last action before execution, usable predicate `lease_expires_at > stamp + margin` with epoch and owner, execution and pre-commit guard (`margin/2`) with rollback and retry, no timestamp parameters on lease methods; new keys `worker.lease.margin.ms`, `worker.lease.stamp.retry`; new `LeaseStampContext`, `LeaseStampHook`, `LeaseStampStaleException` (SDD §7.11.15) | User review of RI-3 (2026-10-10): renewal must never revive an expired lease | **Approved 2026-10-10 (user, design only; not implemented, not verified)** |

### 6.3 Database fallback ladder (use strictly in this order; record every step here)

| Step | Option | Trigger | Constraint |
|---|---|---|---|
| 1 | MariaDB4j + MySQL Connector/J | Default | - |
| 2 | MariaDB4j + MariaDB Connector/J | Spike shows Connector/J incompatibility with the embedded server | DAO code unchanged (plain JDBC) |
| 3 | Portable MySQL zip extracted into the project (git-ignored folder, started by `DbBootstrap`) | MariaDB4j binaries cannot run on the machine | Requires user approval: it is a manual download |
| 4 | An existing MySQL/MariaDB server on a machine (`db.mode=external`) | Steps 1-3 unusable | User supplies URL/credentials in git-ignored config |
| 5 | H2 in MySQL-compatibility mode | Last resort | **Only with the user's explicit approval**; breaks the "same SQL on MySQL 8" guarantee for some constructs and must be recorded as a deviation |

### 6.4 Inspection findings and conflicts to report (2026-10-10)

Repository at the start of this session contained only `README.md` and `.gitignore` (one commit, `Initial commit`). Nothing was overwritten or deleted.

| ID | Finding / conflict | Impact | Proposed handling |
|---|---|---|---|
| C-01 | `.gitignore` ignores `*.jar`, `*.zip`, `*.war`, `*.log`, `*.class` | A Maven wrapper JAR would be ignored and never committed | Use the "only-script" wrapper (A-03). If a JAR is ever needed add `!.mvn/wrapper/maven-wrapper.jar` (needs approval) |
| C-02 | `.gitignore` lacks `target/`, `data/`, `snapshots/`, `reports/`, `config/application.properties`, `*.ser`, IDE folders | Build output, DB files, snapshots and secrets could be committed by accident | Append in Phase 1 (A-04); a secret scan is part of every gate |
| C-03 | Maven Wrapper bootstrap paradox: generating it normally needs Maven | Violates zero-install if done naively | A-03 / D-018 |
| C-04 | "Zero-install" vs first-run downloads | Offline first build impossible | A-01 |
| C-05 | MariaDB4j native binary may need system libraries on Linux | Could conflict with "no system install" | A-02, fallback ladder |
| C-06 | "HikariCP only other dependency" vs unavoidable artifacts (driver, JSTL implementation, Jakarta APIs, Logback) | Literal reading is impossible | A-14 |
| C-07 | Locked flow enqueues *before* the ROUTED update, so a fast worker can dequeue an order still in VALIDATED | A naive worker would drop or double-handle it | Reviewed 2026-10-10 (SDD §7.8): **no conflict**, ROUTED is bookkeeping; D-007, D-027..D-031 close the surrounding gaps; locked flow unchanged |
| C-08 | `PLACED -> FAILED/CANCELLED` are in the locked table but PLACED is never committed alone | Those two edges are only exercised in unit tests | Documented in [SDD §4.3](SDD.md); consistent with the lock |
| C-09 | "Tag at phase end" vs "work only on the designated branch" | Tag push could be seen as pushing elsewhere | A-12 |
| C-10 | Locked list of metrics has no `cancelled` | The metric invariant needs cancelled counts | Taken from the DB in tests; no extra counter (SDD §7.7) |

### 6.5 Approved additions and deviation register (2026-10-10)

**Deviations from the locked decisions: one, DEV-1 (Phase 1), recorded in §6.5a below.** Apart from DEV-1 the locked architecture is unchanged. The other user-approved changes in this table are additions or refinements inside it. Anything that would later contradict a locked decision must be added to this table as a *deviation* and approved before it is implemented.

| ID | Addition / refinement | Locked decision it touches | Why it is not a deviation | Approved |
|---|---|---|---|---|
| D-007, D-027 | Worker routes a VALIDATED order itself; post-offer rule | LD-10 (order flow) | The flow validate -> TX1 -> commit -> enqueue -> conditional ROUTED is unchanged; ROUTED may be set by the worker, still conditionally | 2026-10-10 |
| D-028 | Transient/permanent failure classification, in-place retry and parking | LD-09 (a failure in one order never kills a worker; recover cleanly) | Strengthens it | 2026-10-10 |
| D-029 | Unique history edge index | LD-09 (conditional transitions) | Extra DB guard | 2026-10-10 |
| D-030 | Cancel re-read loop | LD-06 | Same transition rules | 2026-10-10 |
| D-031 | Recovery fails orders of missing stations; compensation retries and pending state | LD-10 (startup reload, compensation) | Extends recovery without changing it | 2026-10-10 |
| D-032 | Orphan sweeper (extra scheduled thread, counters separate from the five locked metrics) | LD-09 (thread list is not exclusive), LD-10 | Runs after the locked startup recovery; changes state only through the locked conditional transitions | 2026-10-10 |
| D-033 | `AuthFilter` does a database check per request; `staff_users.session_version`; enable/disable page | LD-13 (AuthFilter + RoleFilter, 30-minute timeout, full invalidation) | All locked properties retained; timeout and cookie rules untouched | 2026-10-10 |
| D-034 | Documentation of transaction boundaries; durability check S-17 | LD-07, LD-10 | Documentation and verification only | 2026-10-10 |
| D-035 | Durable compensation columns on `orders`; `INDETERMINATE` outcome | LD-10 (compensating transaction, DB is the source of truth), LD-06 (lifecycle) | The compensating transaction is unchanged and is still the primary path; the marker is an orthogonal attribute, **no status was added** and the transition table is untouched | 2026-10-10 |
| D-037..D-040 | Complete-fencing rules, stricter lease predicates, monitor reconcile pass, operator compensation retry/page, `FaultInjecting*Dao` extensions | LD-09, LD-10, LD-13 | All refine already approved mechanisms (conditional updates, recovery, compensation, role-protected admin pages); no schema change beyond D-035/D-036 | proposed 2026-10-10 |
| D-036 | Lease columns, fencing predicate on worker updates, `WorkerMonitor` (extra thread), `ThreadPoolExecutor` with spare capacity | LD-09 (executor owned by orchestrator, named threads, conditional updates, fault isolation) | Worker updates remain conditional (`WHERE status = ?`) with one more predicate; the executor is still owned by the orchestrator; threads stay named | 2026-10-10 |
| - | `SubmissionHook`, `WorkerHook` test seams, `FaultInjecting*Dao` test doubles | LD-04 (no Mockito) | Hand-written fakes, no mocking framework | 2026-10-10 |
| D-043 | `DbBootstrap` hardened two-phase start of the embedded database: one-time socket-only bootstrap that removes anonymous accounts and the `test` database and creates the application account, then grants enforced and `--bind-address=127.0.0.1` on every start (SDD 2.3a) | LD-01 (embedded MariaDB4j started by `DbBootstrap`), LD-13 (secrets only in git-ignored config) | MariaDB4j itself and the locked start-up path are unchanged; only its default (unauthenticated, all interfaces, EV-004) is replaced by an authenticated, loopback-only server (EV-005, EV-006). Added at the user's request to investigate and propose the least risky configuration | 2026-10-10 |
| D-044 | `OrderService` exposes `transitionWork(...)` returning a `TxWork<Boolean>` instead of the SDD 5.3 overload `transition(Connection, ...)`, so that package `service` never names a JDBC type (CLAUDE.md section 4, `ArchitectureRulesTest`); callers compose it inside their own `Database.inTransaction` | The frozen SDD 5.3 signature and the frozen layering rule disagree (reported in EV-019); the user chose this resolution at the start of Phase 3 | No rule or allow-list was changed; only the SDD 5.3 wording | 2026-10-10 |
| D-045 | Four DAO interface refinements: `MenuItemDao.insert` and `StaffUserDao.insert` take fields (a `MenuItem`/`StaffUser` always has an id, so the Phase 2 signatures could not create a row); additions `OrderDao.findState`, `OrderDao.findItems`, `StockDao.create` | Found while implementing Phase 3; additive or strictly needed | No frozen requirement changes | 2026-10-10 |
| D-046 | `GET /demo/orders` (`OrdersDemoServlet`, `WEB-INF/views/demo/orders.jsp`): a public, read-only order list without customer data, to demonstrate Tomcat -> servlet -> service -> DAO -> database before the login flow exists | Phase 3 brief; it is replaced by the authenticated `/orders` (SDD 10.2 route 6) in Phase 7 and must be removed then | Not an SDD route; temporary by design | 2026-10-10 |
| D-047 | Stale lease stamps are raised from the DAO as `StaleStampSqlException` and mapped to `LeaseStampStaleException` by `Database` (SDD 7.11.15 implementation note) | DAO methods only declare `SQLException` | Same observable outcome as S-5: rollback and retry with a fresh stamp | 2026-10-10 |
| D-048 | `OrderService` gains three read-side members for the pipeline: `readProgress(orderId)` (fresh auto-commit read returning the new `dto.OrderProgress`), `findReconcilableIds(stationId, window, limit)` and the static `isTransientFailure(Throwable)` | `ArchitectureRulesTest` forbids package `pipeline` from importing `dao` or `db`; the worker and the reconcile pass need these reads and the RR-8 classification | Additive; no frozen signature changes | 2026-10-10 |
| D-049 | One immutable record `pipeline.PipelineSettings` carries every pipeline tunable (built from `AppConfig`, or directly by tests); the poll timeout of 500 ms (SDD 7.3) is a constant of it, not a new configuration key | Keeps the workers free of `AppConfig` and lets tests use short delays without touching the configuration | No new configuration key | 2026-10-10 |
| D-050 | The worker records when it last extended its lease (acquire, applied step, renewal) on its own `Clock` and renews between sleep slices once `worker.lease.renew.seconds` have passed since then | SDD 7.11.3 says "renews every `worker.lease.renew.seconds`" without saying from when; measuring from the last extension avoids renewing a lease that a step just extended and is testable with a separate fake worker clock | Refines 7.11.3; safety unchanged (every step is fenced anyway) | 2026-10-10 |
| D-051 | `OrderOrchestrator.shutdownGracefully(drain, await)` implements steps 4-6 of SDD 7.6 now (the roadmap calls it "basic stop"); `ShutdownCoordinator`, the JVM hook, the final snapshot and the log close remain Phase 5/6 | Task 4.1 requires a basic stop and the executor must be shut down by its owner | Phase 5 wires it into the coordinator | 2026-10-10 |
| D-052 | The Phase 4 brief's `OrderOrchestrator.submit(order)` maps to the frozen pair `OrderService.submitOrder` + `OrderOrchestrator.dispatch`; no second entry point is added. Delivery is represented only by the `PACKED -> OUT_FOR_DELIVERY -> DELIVERED` steps and `worker.delivery.ms` (A-06: no delivery-partner entity) | Frozen SDD 6.1, 7.2, 7.3 and CLAUDE.md section 3 | None | 2026-10-10 |
| D-053 | The brief's `OrderFeedSimulator` and `Metrics` are implemented under the frozen names `FeedSimulator` and `MetricsRegistry`; recoveries, sweeper hand-backs and simulator output are separate immutable `Stats` records, not extra members of the locked five counters | SDD 7.5, 7.7; FR-CON-09 names exactly five counters | None; no concept is counted twice | 2026-10-10 |
| D-054 | Startup recovery enqueues oldest id first, not status by status; a `VALIDATED` order is routed by the worker that dequeues it (RR-3) | Frozen RR-9 and SDD 7.10.6; the brief asked for `VALIDATED` first | None; the frozen order is kept | 2026-10-10 |
| D-055 | `OrderService` gains the recovery/sweeper reads (`findRecoverableOrders`, `findPendingCompensationIds`, `findDuePendingCompensationIds`, `findStaleOrders`, `isStale`, `pendingCompensationSummary`), `OrderDao` gains `isStaleNonTerminal`, and `dto.RecoverableOrder` carries the result | Package `pipeline` must not import `dao`/`db`; the sweeper needs a fresh single-order re-check (SDD 7.9.3 step 3.3) | Additive | 2026-10-10 |
| D-056 | `DashboardService` takes the pipeline parts as `Supplier`/`BooleanSupplier`/`IntSupplier` instead of pipeline types | SDD 6.1 says it combines `OrchestratorStationViews`; `ArchitectureRulesTest` allows `service` only `OrderDispatcher`, `InFlightRegistry`, `MetricsRegistry` | No rule changed | 2026-10-10 |
| D-057 | `ShutdownCoordinator.shutdown()` returns `boolean` (complete, or a waiting caller gave up) and handles interrupts as described in 7.15.3; `OrderOrchestrator.stop` returns `StopOutcome` (`DRAINED`, `STOPPED`, `FORCED`, `NOT_TERMINATED`) and `shutdownGracefully` is kept as its boolean reduction | The brief asks to record whether `shutdownNow` was needed; SDD 7.6 only says it is called | Refines 7.6; Phase 4 callers unchanged | 2026-10-10 |
| D-058 | `AppContext` wires `NoOpOrderEventLog` and a no-op snapshot step until Phase 6, installs the coordinator's JVM hook, and does not stop an embedded database it did not start; the embedded `Launcher` keeps its Phase 1 hook (stops Tomcat, whose listener ends in the same coordinator) | Roadmap 5.4 ("`Launcher` remains the Phase 1 spike launcher"), Phase 6 owns the log and snapshots | None | 2026-10-10 |
| D-059 | An order whose station has no worker and whose status has no edge to `FAILED` (`PACKED`), or whose station is `DEAD`, is left unchanged in the database and counted as skipped by recovery | RR-9 says "move to FAILED" but the locked lifecycle has no `PACKED -> FAILED` edge, and failing an order because of a worker outage would turn an outage into a lost order | Documented limitation: such an order waits for an operator restart | 2026-10-10 |

### 6.5a Recorded deviations (Phase 1)

| ID | Deviation | Locked decision it contradicts | Scope and conditions | Evidence | Approved |
|---|---|---|---|---|---|
| DEV-1 | `spring-core` (and its `spring-jcl`) are present on the runtime classpath as **transitive compile dependencies of MariaDB4j 3.3.1** (`mariaDB4j-core`). MariaDB4j's `Util` class uses Spring's `PathMatchingResourcePatternResolver` to unpack the database binaries from the jar, so the embedded database cannot start without them. | LD-04, literal wording "No Spring, JPA, Hibernate, Mockito" | Narrow exception for those two artifacts only, as a transitive runtime need of an artifact already approved under A-14. **The literal "No Spring" rule is not fully satisfied by the project and must not be described as satisfied.** Conditions: no direct Spring dependency declaration in any `pom.xml`; no `org.springframework` import in our own code (to be enforced by `ArchitectureRulesTest`); no other Spring module may be added; `spring-context` stays absent (it is optional in MariaDB4j). Re-evaluate if a MariaDB4j release drops Spring, or if the fallback ladder (§6.3) is used. | Bytecode inspection: only `ch.vorburger.mariadb4j.Util` references `org.springframework` (`Resource`, `PathMatchingResourcePatternResolver`, `ResourcePatternResolver`); spike log line `Unpacked 92 files from classpath*:ch/vorburger/mariadb4j/mariadb-11.4.5/linux/**`; MariaDB4j 3.3.1 is the latest release on Maven Central (2026-10-10). Exclusion was **not** tried. | 2026-10-10 (user) |

### 6.6 Final adversarial design audit (2026-10-10)

Documentation review only; **nothing has been implemented or run**. Details, interleavings and invariants: [SDD §7.12](SDD.md).

| Issue | Finding | Fix (documented) | Planned deterministic tests |
|---|---|---|---|
| 1 Complete fencing | F-A1 unfenced `failOrder` let a stale worker fail an order already owned by its replacement; F-A2 no rule stopped statements after a 0-row update | WM-1..WM-9 catalogue, F-1..F-8, `transitionAsOwner`/`failAsOwner`, call-site rules, actor with generation | `WorkerMutationFencingTest`, `StaleWorkerWakeTest`, `LeaseFencingTest`, `ArchitectureRulesTest` |
| 2 Lease correctness | F-B1 renewal could revive a cleared or expired lease; F-B2 check-then-act gap | Predicates and rules L-1..L-7, check inside the mutating statement with the row lock held | `LeaseFencingTest`, `LeaseExpiryReacquireTest`, `StaleWorkerWakeTest` |
| 3 Lost-id recovery | F-C1 id lost after `poll` waited for the 120 s orphan rule and trusted in-memory sets; F-C2 id dropped when a lease merely expired | Death-point table P0-P9, monitor reconcile against the physical queue, L-5 re-acquire, duplicate-safety argument | `PollToLeaseCrashWindowTest`, `QueueReconcileTest`, `DeadWorkerRaceTest`, `WorkerMonitorTest` |
| 4 Compensation recovery | F-D1 staff cancel was the escape hatch; risk of a resolve shortcut skipping the restore | ADMIN `retryCompensation` (TX4), cancel refused for PENDING, invariant I-12, call-site rules | `CompensationOperatorTest`, `CompensationOperatorWebTest`, `PendingCompensationRaceTest`, `ArchitectureRulesTest` |

Counts after the audit (before the final verification review below): 112 requirements, 102 planned test classes. Verification performed: scripted cross-document consistency checks only. No test has been run; every row in TRACEABILITY.md remains `Planned`.

### 6.6b Final verification review (2026-10-10, SDD §7.13)

Two questions only: (1) does every worker-owned mutation follow one transaction-scoped fencing protocol; (2) does queue reconciliation recover lost ids without disturbing valid leases, also if the monitor dies. **Result: both are satisfied at design level** after five corrections (SDD §7.13.8): DAO clock rule, same-owner re-acquire after a lost reply, L-5 *continue* case, a shared repair routine so the sweeper alone can recover, and on-demand worker health. New: FR-CON-18; amended FR-CON-14, FR-CON-15, FR-RES-06. Counts at that time: 113 requirements, 105 planned test classes (superseded by §6.6c). Nothing is implemented or run; all rows remain `Planned`.

### 6.6c Review of RI-3 (2026-10-10, SDD §7.11.15)

The user found that RI-3 and rule L-2 allowed a renewal using a timestamp computed before expiry to succeed after expiry. Resolved by decision D-042: the old L-2 and RI-3 are withdrawn; renewal and steps use a stamp taken inside the DAO as the last action before execution, require the lease to be usable (`expiry > stamp + margin`) with the matching epoch and owner, and are rolled back if execution or the pre-commit check slips beyond `margin/2`. RI-3 is now RI-3a (pause before the DAO call), RI-3b (stall inside the DAO) and RI-3c (commit-latency boundary, residual U-12). Counts: 114 requirements (100 MUST, 10 SHOULD, 4 COULD), 107 planned test classes. Nothing is implemented or run.

### 6.6d Phase 0 closeout (2026-10-10)

Baseline frozen after bounded checks (consistency of the nine modified files, counts, stale-protocol sweep, recorded limitations, actionable Phase 1 entry criteria). Final wording of the lease/commit-latency limitation: SDD §7.11.15 items 1-4 (fencing guarantee, timing limitation U-12, required safety invariant, unproven assumption -> spike S-19). Decisions D-037..D-042 are approved as **design only**. Still pending, by design, until the Phase 1 spike: D-002 (driver), D-017 (packaging), D-018 (wrapper), assumptions A-01, A-02, A-03, A-04, A-14, and the unverified items S-17, S-18, S-19. Answers recorded from the user: demo brands are placeholders (A-05); missing config refuses startup with a clear error and a safe example file; extra worker controls only after core requirements; disabled accounts are enforced per request (D-033); COULD items only after all core features and tests pass, then rerun relevant tests.

### 6.7 Log of settled decisions (filled in during implementation)

| Date | Decision ID | Outcome | Evidence ref |
|---|---|---|---|
| 2026-10-10 | A-01 | **Approved.** Outbound internet on the first build to fetch Maven, dependencies and embedded-DB binaries from the approved official repositories. Network restrictions must not be bypassed; Maven Central must be reachable before proceeding. | User approval, Phase 1 start (no test evidence yet; connectivity evidence goes to EVIDENCE.md) |
| 2026-10-10 | A-02 | **Approved with restrictions.** Attempt to start and test the embedded MariaDB4j native binary on this OS. No system packages, no global system configuration changes, no unapproved workarounds. On failure or if system software appears necessary: stop, report complete evidence and the §6.3 ladder, and ask before any fallback that needs approval. | User approval, Phase 1 start (spike outcome pending) |
| 2026-10-10 | A-03 / D-018 | **Approved.** Official Maven Wrapper scripts, documented `only-script` type, no wrapper JAR committed; official source only (D-018 stays "Proposed" until the scripts are obtained and verified). | User approval, Phase 1 start (download and checksum verification pending) |
| 2026-10-10 | A-04 | **Approved.** Append the exact Phase 1 ignore rules (roadmap task 1.3, including `reports/` and `config/application.properties`) to the existing `.gitignore`; never rewrite it or remove existing rules. | User approval, Phase 1 start. Append done 2026-10-10: 13 lines added to `.gitignore` (comment header plus the 11 rules), no existing line changed; `git check-ignore` confirms `data/`, `target/` and `config/application.properties` are ignored while `mvnw`, `pom.xml` and `.mvn/` are not |
| 2026-10-10 | A-14 | **Approved.** The interpretation of the dependency rule stated in A-14 (server/DB artifacts, JDBC driver, Servlet/JSP/JSTL, locked logging stack, JUnit 5 test scope, Maven build plugins); no other library without further approval. | User approval, Phase 1 start |
| 2026-10-10 | Phase 1 naming | Frozen documentation kept as the source of truth: `config/application.properties(.example)`, `reports/`, tag `phase-1-complete`, `/spike.jsp` with `EmbeddedTomcatSpikeTest` plus a public `/health` (HTTP 200, body `OK`) and a JSTL `c:out` rendering check; default port 8080. | User decision, Phase 1 start |
| 2026-10-10 | Phase 2 scope | **Frozen Phase 2** (roadmap 2.1-2.12). The in-memory `OrderService`, `InventoryManager`, generic repositories, `Customer`, `DeliveryPartner` and `KitchenStation` of the Phase 2 brief are not built: they conflict with the frozen database-based design. Stock restoration "exactly once" as a service behaviour is Phase 3; the pure rule `OrderStatus.isStockRestorableTo` is tested now. | User decision, Phase 2 start (answers to the conflict questions) |
| 2026-10-10 | Phase 2 naming and tag | Frozen SDD names are used; the tag is `phase-2-complete` (not `phase-2-done`). | User decision, Phase 2 start |
| 2026-10-10 | `CloudKitchenException` | **Checked**, as in SDD 4.2. Phase 1 callers (`DbBootstrap`, `DbSpikeMain`, `Launcher`, two spike tests) now declare it. | User decision, Phase 2 start; EV-017 |
| 2026-10-10 | Phase 2 clarifications (no requirement changed) | `snapshot.hmac.secret` is required by `AppConfig` (SDD 15); `db.password` shape from the example file is enforced; `Order` is an immutable snapshot with database-driven transitions; `ArchitectureRulesTest` carries three recorded allow-lists where SDD 2.2 and the class catalogue disagree (`OrderFactory` uses `dto` and `service.MenuCatalog`; `ValidationException` uses `dto.FieldError`; `dao` interfaces import `java.sql.Connection`/`SQLException`); `OrderService.transition(Connection, ...)` (SDD 5.3) vs "no `java.sql` in `service`" is left open for Phase 3 to report. | EV-017, EV-019 |
| 2026-10-10 | Phase 3 scope | **Full frozen roadmap 3.1-3.8** (schema, `db`, DAOs incl. leases, fencing and durable compensation, `OrderService`, `AuthService`, `MenuService`, `StockService`, `OrderQueryService`, test support) plus the read-only demonstration page requested in the brief. | User decision, Phase 3 start |
| 2026-10-10 | Phase 3 entities | **Frozen mapping, no new tables:** `stations` = kitchen station, `inventory` = `StockDao`, order lines live in `OrderDao`, payment fields are columns of `orders`; no Customer, DeliveryPartner or Payment table or DAO and no delivery-partner seed data (riders are out of scope, A-06). | User decision, Phase 3 start |
| 2026-10-10 | Phase 3 naming | **Frozen names:** `V001__schema.sql`, `V002__seed_reference_data.sql` applied by `SchemaInitializer` and recorded in `schema_version` (not `schema.sql`/`seed.sql` applied by `DbBootstrap`). | User decision, Phase 3 start |
| 2026-10-10 | Phase 3 architecture conflict | Resolved as D-044 (`TxWork` factory). | User decision, Phase 3 start |
| 2026-10-10 | Phase 4 scope | **Frozen roadmap 4.1-4.4 including 4.2b** (orchestrator, workers, leases and fencing, worker monitor with reconcile). `OrphanSweeper`, `RecoveryService`, `ShutdownCoordinator`, `FeedSimulator` and the file log stay in Phases 5-6 as frozen. | Phase 4 brief read against the frozen roadmap; no conflict requiring a user decision |
| 2026-10-10 | Phase 4 decisions | D-048..D-052 (see 6.5). | Phase 4 implementation |
| 2026-10-10 | Phase 5 scope | **Frozen roadmap 5.1-5.10** (simulator, recovery, shutdown coordinator, `AppContext`, metrics finalised, orphan sweeper, operator retry tests). The Phase 5 brief omitted the sweeper and the operator tests; the frozen roadmap and exit criteria include them and the brief asks for no competing recovery loops, so the sweeper is implemented as the documented second path. Snapshots, the file log and every web page stay in Phases 6-7. | Brief read against the frozen roadmap; no conflict requiring a user decision |
| 2026-10-10 | Phase 5 decisions | D-053..D-059 (see 6.5). | Phase 5 implementation |
| 2026-10-10 | D-002 | **Settled: MySQL Connector/J 9.7.0 (fallback-ladder step 1), accepted by the user on 2026-10-10.** Basis: with Connector/J, HikariCP 7.0.2 and MariaDB 11.4.5 every executed spike item passed: S-03 connection, S-04 utf8mb4, S-05 DECIMAL/BigDecimal, S-06 DATETIME(3), S-07..S-09 update counts, `FOR UPDATE`, generated keys, duplicate key, S-10 deadlock 40001/1213 (20/20), S-11 CHECK, S-12 schema features, S-17..S-19 (EV-006, EV-010). **Reconciliation of the roadmap wording:** S-03 ("then repeat with MariaDB Connector/J"), S-10 ("observed with each driver") and task 1.7 ("once per candidate driver") can be read as asking for a second driver run, but the locked decision (CLAUDE.md section 3, section 6.3 ladder step 2, D-002) adds MariaDB Connector/J only when incompatibility is demonstrated, and none was. The user decided on 2026-10-10 that no second driver is added for comparison. **MariaDB Connector/J was therefore NOT tested and nothing is claimed about it**; it stays the documented fallback. | EV-006, EV-010 |
| 2026-10-10 | D-017 | **Settled as designed:** Tomcat Embed, Jasper and MariaDB4j are `provided`; `exec:java` uses `classpathScope=test`; the WAR excludes them (and the transitive Spring jars of DEV-1). The Maven-profile fallback was not needed. JSTL API and implementation are compile scope with servlet/JSP/EL API exclusions. | EV-009, EV-011, EV-012 |
| 2026-10-10 | D-018 | **Settled:** wrapper `only-script` 3.3.4 from Maven Central, checksum-pinned Maven 3.9.16; no wrapper JAR. `mvnw.cmd` (Windows) is included but NOT verified. | EV-001 |
| 2026-10-10 | O-3 | **Settled: servlet-context attribute.** `Launcher` sets `ck.config` before Tomcat starts and the listener reads it (same `AppConfig` class thanks to the class-loader rules in `Launcher`). | EV-009 |
| 2026-10-10 | D-043 | Hardened `DbBootstrap` (see 6.5). Consequence: the application must run as a non-root OS user, and changing `db.password` for an existing data directory does not change the stored password. | EV-002..EV-006 |
| 2026-10-10 | DEV-1 | Deviation recorded in 6.5a (transitive `spring-core`/`spring-jcl` of MariaDB4j). Guarded by `ArchitectureRulesTest`; not in the WAR. | EV-008, EV-011 |
| 2026-10-10 | Findings for Phase 3 | (a) classify deadlocks by vendor code 1213, not SQLState 40001 alone (lock wait timeout 1205 also reports 40001); (b) every table needs an explicit `COLLATE=utf8mb4_unicode_ci`; (c) driver default update count is found-rows, decide `useAffectedRows` for the JDBC URL; (d) the application account must not get `PROCESS`. | EV-010 |
| 2026-10-10 | A-14 (confirmed) | **Confirmed by the user on 2026-10-10:** transitive dependencies required by explicitly approved artifacts are permitted, provided they are necessary, documented, and do not introduce unapproved direct dependencies or frameworks. This is not blanket permission to add arbitrary dependencies. The full inventory of the 36 jars on the test class path is kept in EVIDENCE.md EV-013, and the Spring exception DEV-1 (6.5a) stands. | EV-013 |
| 2026-10-10 | Open items / limitations | Windows (`mvnw.cmd`) and macOS are unverified; a standalone Tomcat 10.1 deployment of the WAR is unverified (only its contents were checked); evidence is Linux only (Ubuntu 24.04, JDK 21, run as a non-root user); MariaDB Connector/J is untested (D-002). The S-14 shutdown log caveat is resolved (EV-014). See EV-013. | EV-013, EV-014 |

## 7. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-01 | MariaDB4j binary does not start (libs, root user, antivirus) | Medium | High | Phase 1 spike first; fallback ladder; do not proceed to Phase 2 until resolved |
| R-02 | Connector/J incompatibility with MariaDB server | Medium | Medium | Spike checklist; swap driver, DAO code driver-agnostic |
| R-03 | `exec:java` classpath vs `provided` scope; WAR contents | Medium | Medium | Spike; Maven profile fallback; `jar tf` gate |
| R-04 | JSP/JSTL TLD discovery fails in embedded Tomcat | Medium | Medium | Spike renders a JSTL page before building views |
| R-05 | Flaky concurrency tests | Medium | High | `@RepeatedTest(20)`, latches, no sleeps, treat each failure as a bug |
| R-06 | Scope creep across sessions | High | High | Scope fence in CLAUDE.md; phase gates; COULD last |
| R-07 | Documentation and code drift between sessions | High | Medium | Docs are the source of truth; conflicts reported; traceability updated every phase |
| R-08 | 600,000-iteration PBKDF2 slows tests | Low | Low | Few hashes per test; shared pre-hashed fixtures built once per JVM |
| R-09 | Port conflicts (8080, 3307) | Medium | Low | Configurable ports; random ports in tests; clear startup message |
| R-11 | An order may stay non-terminal with nobody processing it | Low | Medium | Orphan sweeper (D-032, SDD §7.9); `COMPENSATION_PENDING`/`ORDER_STALLED` logs; residual: dead worker thread (SDD §7.9.6) |
| R-13 | Lease/epoch design adds database writes per step and relies on one JVM's clock for expiry | Low | Low | TTL/renew configurable; single instance assumed (A-09); multi-instance would need DB time (not planned) |
| R-14 | A hung worker thread cannot be killed | Low | Low | Fencing keeps data safe; bounded executor maximum; leak detection (SDD §7.11.11) |
| R-16 | The stamp guard can abort legitimate long multi-statement worker transactions (large restores) under slow I/O | Low | Low | Aborts are clean and retried; `worker.lease.margin.ms` configurable | 
| R-15 | Complete fencing and reconcile add statements per step and a periodic query per station | Low | Low | Indexed queries, configurable intervals, batch limits |
| R-12 | Per-request account validation adds one database lookup per protected request and fails closed on database errors | Low | Low | Primary-key lookup; acceptable at this scale; documented (SDD §12.10) |
| R-10 | Windows path/line-ending issues in wrapper and scripts | Medium | Low | `.gitattributes` (Phase 1) forcing LF for `mvnw`, CRLF for `mvnw.cmd`; Windows run recorded or declared unverified |

## 8. Glossary (the same words are used in every document)

| Term | Meaning |
|---|---|
| **Order** | A customer order held in the system; abstract class `Order` with concrete `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder`. |
| **Source** | Where an order comes from: `OrderSource` = `SWIGGY`, `ZOMATO`, `IN_HOUSE`. |
| **External reference** (`external_ref`) | The source's own order id; with `source` it uniquely identifies an order (idempotency key). |
| **Brand** | A virtual restaurant brand operated by the kitchen (for example Biryani, Burger, Pizza), identified by `brandCode`. |
| **Station** | The kitchen station of one brand (exactly one per brand), class `Station`, with one queue and one worker. |
| **Station queue** | The bounded `ArrayBlockingQueue<Long>` of order ids for a station. |
| **StationWorker** | The runnable that consumes one station's queue and advances orders. |
| **Orchestrator** | `OrderOrchestrator`: owns the queues, the executor and the workers; implements `OrderDispatcher`. |
| **Submission** | One call of `OrderService.submitOrder(OrderRequest)`; result is `SubmissionResult`. |
| **TX1** | The order-creation transaction: insert order + items + conditional stock deduction + VALIDATED + history. |
| **Compensation** | The compensating transaction that restores stock and marks the order FAILED when routing/enqueue fails. |
| **In-flight order** | An order in a non-terminal status. |
| **In-flight guard** | `InFlightRegistry`: concurrent set of `source:externalRef` keys currently being submitted. |
| **Pending compensation** | An order in `VALIDATED` whose queue insertion failed and whose compensation could not yet commit; marked durably (`compensation_state = PENDING`) and never processed. |
| **Lease / fencing token** | Database record that a worker generation owns an order until `lease_expires_at`; `claim_epoch` increments on each acquisition and every worker update must carry the current value. |
| **Terminal status** | `DELIVERED`, `CANCELLED`, `FAILED`. |
| **Conditional update** | `UPDATE ... WHERE id = ? AND status = ?` (or `AND stock_qty >= ?`); affecting 0 rows means another actor won. |
| **Snapshot** | Signed serialized file of in-flight orders (`OrderSnapshotDTO` list) in `snapshots/`. |
| **Flash message** | One-time message stored in the session across a redirect (PRG). |
| **PRG** | Post-Redirect-Get pattern. |
| **Lockout** | Temporary account lock after 5 consecutive failed logins (5 minutes). |
| **Low-stock flag** | Shown when `stock_qty <= reorder_level`. |
| **Feed simulator** | `FeedSimulator`: scheduled producer of simulated orders from all three sources. |
| **Worker state** | `WorkerState`: `STARTING`, `IDLE`, `PROCESSING`, `PAUSED`, `STOPPING`, `STOPPED`, `DEAD`. |
| **Tracking code** | Random 12-character code per order for the (COULD) public tracking page. |
| **Gate command** | A command whose real output must be pasted into EVIDENCE.md to close a phase. |

## 9. How the documents fit together

```
PROJECT_OVERVIEW (why, scope, decisions)
   -> SRS (what: requirements, use cases, acceptance)
        -> SDD + DATABASE_DESIGN (how)
             -> JAVA_CONCEPT_MAPPING (which class proves which concept)
   -> IMPLEMENTATION_ROADMAP (when: phases, gates)
   -> TESTING_STRATEGY (how verified)
   -> TRACEABILITY (requirement -> design -> file -> test -> status)
   -> EVIDENCE (real command output; the only proof of "done")
CLAUDE.md (rules for every coding session)
```

Rule: if code and documents disagree, the documents win until the user approves a change; the disagreement is reported, not silently resolved.

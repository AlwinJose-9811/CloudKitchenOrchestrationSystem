# Project Overview

**Project:** Cloud Kitchen Orchestration System (undergraduate project, single developer)
**Document status:** Planning baseline created 2026-10-10. **Nothing is implemented.** Every statement describes intent, not existing behaviour.
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
| **MUST** | Project fails without it. Built and verified in its phase. | Zero-install build/run; embedded Tomcat + embedded DB; WAR; domain model and polymorphism; lifecycle enforced in `OrderStatus`; validation; TX1 with atomic stock deduction, sorted order, deadlock retry; idempotency (unique constraint + in-flight guard); station queues, workers, orchestrator; queue-full compensation; fault isolation; interrupt handling; metrics; feed simulator with start/stop/rate; startup recovery from DB; graceful shutdown (listener + hook); orders.log (NIO); signed and filtered snapshots with recovery; login, sessions, lockout, CSRF, roles, filters; PRG + flash; generic error pages; dashboard; order page with history; simulator control page; UTF-8; security headers; docs, traceability, evidence |
| **SHOULD** | Planned and done after the phase's MUSTs. | CSV daily report export; admin menu management; admin stock management; worker panel (pause/resume); in-house order entry form; snapshot-on-demand button; basic usability checklist; sustained-load smoke test |
| **COULD** | Only if every MUST and SHOULD passes and is evidenced. | Order search; public tracking page; admin password reset; dashboard charts |

Requirement-level priorities are in [SRS §3](SRS.md).

### 3.2 Out of scope
Recipes/ingredients, real payment or delivery APIs, microservices, HTTPS (cookie `Secure` flag is configurable only), WebSockets, customer accounts, email password reset. Also out of scope: multi-node deployment, internationalisation beyond UTF-8 data, mobile apps, payment reconciliation, log rotation of `orders.log`.

## 4. Assumptions

Items marked **[APPROVAL]** need the user's explicit decision before the phase that depends on them.

| ID | Assumption | Needed by |
|---|---|---|
| A-01 **[APPROVAL]** | "Zero-install" means no *system* software beyond JDK 17+, Git and a browser, but the **first build needs outbound internet access** to download the Maven distribution (via the wrapper) and dependencies and embedded-DB binaries (all published on Maven Central). Afterwards the local Maven repository (`~/.m2`) caches them. | Phase 1 |
| A-02 **[APPROVAL]** | The embedded MariaDB4j server (a native `mysqld` binary shipped inside a Maven artifact and started as a child process) runs on the developer's OS. On Linux it may need common shared libraries (for example `libaio`, `libncurses`) and may refuse to run as `root`. If the Phase 1 spike shows a system package is required, the work stops and the fallback ladder (§6.3) is presented to the user; nothing is installed silently. | Phase 1 |
| A-03 | Maven Wrapper is created **without Maven being installed**: the official wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, "only-script" type) are fetched from the official source in Phase 1, because `mvn wrapper:wrapper` itself requires Maven. The "only-script" type avoids committing a wrapper JAR (`*.jar` is git-ignored). | Phase 1 |
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
| L-08 | A disabled or locked user keeps an existing session until timeout (open point O-5). |
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
| D-002 | JDBC driver selection | Locked order: Connector/J first, MariaDB Connector/J if incompatible. Spike checklist in [IMPLEMENTATION_ROADMAP Phase 1](IMPLEMENTATION_ROADMAP.md) | **Pending** Phase 1 |
| D-003 | Servlets/filters/listener declared in `web.xml`, not annotations | Filter order is deterministic only in the descriptor; one descriptor serves embedded Tomcat and the WAR | Decided |
| D-004 | Station queues carry only the order id (`Long`) | DB is the source of truth; queue content is disposable and rebuilt at startup; small memory footprint | Decided |
| D-005 | Submissions rejected by validation, insufficient stock or duplicate are **not persisted**; they are logged, counted and returned to the caller | Keeps the `orders` table to real orders; avoids dead rows for aggregator retries | Decided |
| D-006 | `orders.stock_deducted` flag guards stock restore | Guarantees exactly-once restore under races (cancel vs compensation) and correctness for snapshot-recovered orders | Decided |
| D-007 | Worker is driven by the *current DB status* and claims with bounded retry | Makes recovery resumable and resolves the race between "enqueue" and "ROUTED update" without changing the locked flow | Decided |
| D-008 | Statuses as `VARCHAR` + `CHECK`, not SQL `ENUM` | Portability and simple migrations | Decided |
| D-009 | `DATETIME(3)` in UTC set by the application | Deterministic tests, no time-zone conversion, no 2038 limit | Decided |
| D-010 | Manual wiring through `AppContext`; no DI framework | Locked stack forbids Spring; keeps lifecycle explicit | Decided |
| D-011 | Layering enforced by `ArchitectureRulesTest` (source scan), not ArchUnit | No extra dependency | Decided |
| D-012 | Surefire only (no Failsafe); integration tests tagged `integration` | Fewer plugins for one developer | Decided |
| D-013 | `OrderSnapshotDTO` uses `String` money and epoch-millis timestamps | "Plain fields only"; keeps the deserialization allowlist to three classes | Decided |
| D-014 | Snapshot = `.ser` file + separate `.hmac` file; HMAC verified before deserialization | Cannot deserialize unauthenticated bytes | Decided |
| D-015 | `CsrfFilter` runs before `AuthFilter`; a session is created on `GET /login` to hold the token | Login POST must also be CSRF-protected | Decided |
| D-016 | Login failures, unknown users and locked accounts share one generic message | Avoids account enumeration | Decided |
| D-017 | Embedded Tomcat/Jasper/MariaDB4j declared `provided`; `exec:java` uses `classpathScope=test`; WAR excludes them | Prevents duplicate servlet APIs inside standalone Tomcat; fallback: Maven profile | **Pending** Phase 1 |
| D-018 | Maven Wrapper "only-script" files fetched from the official source | Maven not installed; no wrapper JAR committed | Proposed (A-03) |
| D-019 | Recovery runs on thread `recovery-loader`; `OrderService.accepting=false` until all recovered ids are enqueued | Avoids queue-full failures of new orders during recovery without blocking startup | Decided |
| D-020 | Feed simulator starts stopped (unless `simulator.autostart=true`) | Predictable demos and tests | Decided |
| D-021 | Dashboard refresh by `<meta http-equiv="refresh" content="5">` | No JavaScript; strict CSP | Decided |
| D-022 | Every order has a random `tracking_code` | Cheap now, enables the COULD tracking page without a schema change | Decided |
| D-023 | Configuration file `config/application.properties` (git-ignored) with committed `.example`; startup fails if missing | Satisfies "secrets only in git-ignored config" | Decided |
| D-024 | Event logging is best-effort (a failed write never fails an order) | Order integrity outranks log completeness; failures logged via SLF4J | Decided |
| D-025 | Tests build `AppConfig` programmatically with random secrets | No credentials in the repository | Decided |
| D-026 | `OrderService.transition` is the single place for status change + history + conditional stock restore | One audited code path | Decided |

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
| C-07 | Locked flow enqueues *before* the ROUTED update, so a fast worker can dequeue an order still in VALIDATED | A naive worker would drop or double-handle it | D-007 (claim retry; no change to the locked flow) |
| C-08 | `PLACED -> FAILED/CANCELLED` are in the locked table but PLACED is never committed alone | Those two edges are only exercised in unit tests | Documented in [SDD §4.3](SDD.md); consistent with the lock |
| C-09 | "Tag at phase end" vs "work only on the designated branch" | Tag push could be seen as pushing elsewhere | A-12 |
| C-10 | Locked list of metrics has no `cancelled` | The metric invariant needs cancelled counts | Taken from the DB in tests; no extra counter (SDD §7.7) |

### 6.5 Log of settled decisions (filled in during implementation)

| Date | Decision ID | Outcome | Evidence ref |
|---|---|---|---|
| - | - | (none yet) | - |

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
| **Terminal status** | `DELIVERED`, `CANCELLED`, `FAILED`. |
| **Conditional update** | `UPDATE ... WHERE id = ? AND status = ?` (or `AND stock_qty >= ?`); affecting 0 rows means another actor won. |
| **Snapshot** | Signed serialized file of in-flight orders (`OrderSnapshotDTO` list) in `snapshots/`. |
| **Flash message** | One-time message stored in the session across a redirect (PRG). |
| **PRG** | Post-Redirect-Get pattern. |
| **Lockout** | Temporary account lock after 5 consecutive failed logins (5 minutes). |
| **Low-stock flag** | Shown when `stock_qty <= reorder_level`. |
| **Feed simulator** | `FeedSimulator`: scheduled producer of simulated orders from all three sources. |
| **Worker state** | `WorkerState`: `STARTING`, `IDLE`, `PROCESSING`, `PAUSED`, `STOPPING`, `STOPPED`. |
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

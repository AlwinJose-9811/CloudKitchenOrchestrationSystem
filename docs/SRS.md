# Software Requirements Specification (SRS)

**Project:** Cloud Kitchen Orchestration System
**Status:** Planned, nothing is implemented yet. Every statement below describes intended behaviour.
**Related documents:** [PROJECT_OVERVIEW](PROJECT_OVERVIEW.md) · [SDD](SDD.md) · [DATABASE_DESIGN](DATABASE_DESIGN.md) · [TRACEABILITY](TRACEABILITY.md) · [TESTING_STRATEGY](TESTING_STRATEGY.md)

---

## 1. Introduction

### 1.1 Purpose
This document defines what the system must do and how each requirement is accepted. It is detailed enough for an implementer (Claude Code, across several sessions) to build from and for a reviewer to verify against.

### 1.2 Scope
A Java web application (Servlets + JSP + JDBC + MySQL-compatible database, Tomcat 10.1) that accepts simulated orders from Swiggy, Zomato and an in-house app, validates them, routes each to the right brand's kitchen station, processes them concurrently, tracks the lifecycle and persists everything. See [PROJECT_OVERVIEW §3](PROJECT_OVERVIEW.md) for scope tiers and out-of-scope items.

### 1.3 Terminology
All terms are defined once in [PROJECT_OVERVIEW §8 Glossary](PROJECT_OVERVIEW.md). The same words are used in every document: *order*, *source*, *external reference*, *brand*, *station*, *station queue*, *StationWorker*, *orchestrator*, *submission*, *TX1*, *compensation*, *in-flight order*, *terminal status*, *snapshot*, *flash message*, *lockout*, *low-stock flag*.

### 1.4 Requirement conventions
* IDs are unique and never reused: `FR-<AREA>-<nn>` (functional) and `NFR-<AREA>-<nn>` (non-functional).
* Priority: **MUST** (project fails without it), **SHOULD** (planned, implemented after all MUSTs of the phase), **COULD** (only if every MUST and SHOULD is done and verified).
* *Phase* is the roadmap phase in which the requirement is implemented and verified ([IMPLEMENTATION_ROADMAP](IMPLEMENTATION_ROADMAP.md)).
* A requirement is only "Done" when its acceptance criterion has passed **and** the evidence is in [EVIDENCE.md](EVIDENCE.md).
* Totals: **95** requirements: **82** MUST, **9** SHOULD, **4** COULD.

---

## 2. Overall description

### 2.1 Actors

| Actor | Kind | Description |
|---|---|---|
| ADMIN | Human, role `ADMIN` | Manages menu, stock, simulator, workers, reports; can do everything KITCHEN_STAFF can. |
| KITCHEN_STAFF | Human, role `KITCHEN_STAFF` | Monitors dashboard and orders, enters in-house orders, cancels orders, views stock. |
| Aggregator (Swiggy, Zomato) | External system, **simulated** | Sends orders; represented only by the feed simulator. No real API is called. |
| In-house app | External system, **simulated** | Same: web form or feed simulator. |
| Feed simulator | Internal producer | `FeedSimulator`, controlled by ADMIN. |
| StationWorker | Internal consumer | One per station; moves orders through preparation. |
| Operator | Human | Starts/stops the application (`./mvnw exec:java`, Ctrl+C or Tomcat stop). |
| Public visitor | Human, anonymous | Only for the COULD public tracking page. |

### 2.2 Operating environment (locked)
JDK 17+, Git, a browser, Claude Code. Maven Wrapper, embedded Tomcat 10.1, embedded MariaDB4j. Nothing else is installed. Details and fallbacks: [PROJECT_OVERVIEW §6 Decision log](PROJECT_OVERVIEW.md).

### 2.3 Constraints
Stack: Java 17, Servlet 6.0, JSP 3.1, JSTL (Jakarta), JUnit 5, SLF4J + Logback, HikariCP. No Spring, JPA, Hibernate or Mockito. Money is `BigDecimal`/`DECIMAL(10,2)`, timestamps UTC, utf8mb4.

### 2.4 Assumptions and dependencies
See [PROJECT_OVERVIEW §4](PROJECT_OVERVIEW.md) (assumptions A-01..) and §5 (limitations).

### 2.5 Order lifecycle (reference)

`PLACED -> VALIDATED | FAILED | CANCELLED`; `VALIDATED -> ROUTED | CANCELLED | FAILED`; `ROUTED -> PREPARING | CANCELLED | FAILED`; `PREPARING -> PACKED | FAILED`; `PACKED -> OUT_FOR_DELIVERY`; `OUT_FOR_DELIVERY -> DELIVERED | FAILED`. `DELIVERED`, `CANCELLED`, `FAILED` are terminal. Stock is restored on CANCELLED/FAILED only if the order had not yet reached PREPARING. Full state diagram: [SDD §4.3](SDD.md).

### 2.6 External interface: the order submission contract
All sources, simulated or real, are reduced to one `OrderRequest`:

| Field | Type | Notes |
|---|---|---|
| `source` | `OrderSource` | `SWIGGY`, `ZOMATO`, `IN_HOUSE` |
| `externalRef` | String (1-64) | Source's own order id; auto-generated for `IN_HOUSE` when blank |
| `brandCode` | String | Station/brand code (e.g. `BIRYANI`) |
| `customerName` | String (1-100) | Treated as personal data: never logged |
| `deliveryDistanceKm` | BigDecimal | 0.00-25.00 |
| `paymentMode` | `PaymentMode` | `PREPAID_AGGREGATOR` for Swiggy/Zomato; `CASH`/`UPI`/`CARD` for in-house |
| `items` | List of `OrderItemRequest` (`itemId`, `quantity`) | 1-20 lines; client prices are ignored |

---

## 3. Requirements

### 3.1 Environment, build and configuration (FR-ENV)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-ENV-01 | MUST | **Zero-install build and run.** The project builds and runs with only JDK 17+, Git and a browser, using the Maven Wrapper (`./mvnw`, `mvnw.cmd`). Tomcat 10.1 is embedded and started by `Launcher` through `./mvnw exec:java`. No Maven, MySQL, Tomcat or Docker installation is required. | On a clean clone with only JDK 17+ and Git, `./mvnw -q clean verify` succeeds and `./mvnw exec:java` serves `/login`. | 1 |
| FR-ENV-02 | MUST | **Embedded MySQL-compatible database.** `DbBootstrap` starts an embedded MariaDB (MariaDB4j) with data in `./data` (git-ignored) when `db.mode=embedded`, and does nothing when `db.mode=external`. | First start creates `./data/...`; second start reuses it and previously stored orders are still present; `git status` never shows `./data`. | 1 |
| FR-ENV-03 | MUST | **Standard WAR.** `./mvnw package` produces a standard WAR deployable to standalone Tomcat 10.1. Embedded Tomcat, Jasper and MariaDB4j artifacts are excluded from the WAR so they do not clash with the container. | The WAR contains `WEB-INF/web.xml`, JSPs, application classes, HikariCP, the JDBC driver, SLF4J/Logback and JSTL, and contains no `tomcat-embed-*` or `mariaDB4j` jars (checked with `jar tf`). | 1 |
| FR-ENV-04 | MUST | **Portable SQL, config-only switch to MySQL 8.** All SQL scripts and DAO statements stay within the common MySQL/MariaDB subset. Pointing `db.mode=external` and `db.url`/`db.user`/`db.password` at a real MySQL 8 server must work without code changes. | `SqlPortabilityTest` finds no forbidden constructs; the same schema scripts run on MariaDB4j (and, as a manual check in Phase 8 if a MySQL 8 server is available, on MySQL 8). | 3 |
| FR-ENV-05 | MUST | **Configuration from git-ignored file.** `AppConfig` reads `config/application.properties` (path overridable with `-Dck.config=`). A committed `config/application.properties.example` contains placeholders only. Missing or invalid configuration stops startup with a clear message and no secret values in it. | Start without the file: process exits with a message naming the example file; `git ls-files config/` lists only the example. | 2 |
| FR-ENV-06 | MUST | **Money, time and character-set rules.** Money is `BigDecimal` in Java and `DECIMAL(10,2)` in SQL (never `double`/`float`). All timestamps are UTC (`DATETIME(3)`). Database, tables and connections use `utf8mb4`. | Round-trip tests store 19.99 and an emoji/Hindi customer name and read them back identical; `ArchitectureRulesTest` finds no `double`/`float` money fields. | 3 |

### 3.2 Order intake and validation (FR-ORD)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-ORD-01 | MUST | **Single submission entry point.** Orders from SWIGGY, ZOMATO and IN_HOUSE enter through `OrderService.submitOrder(OrderRequest)`. The feed simulator and the web order form use the same method. | Submitting one valid request per source yields three persisted orders of the matching subclass with status VALIDATED or later. | 3 |
| FR-ORD-02 | MUST | **Pure validation.** `OrderValidator` validates a request against rules VR-01..VR-14 without any I/O and returns all violations together. | Each rule has an accepted and a rejected test case; the validator class has no `java.sql` or servlet imports. | 2 |
| FR-ORD-03 | MUST | **Server-side pricing.** Item prices, order total and platform fee are computed by the server from the catalogue; prices sent by a source are ignored. | A request carrying a tampered unit price still stores the catalogue price and the correct total. | 2 |
| FR-ORD-04 | MUST | **Idempotent submission.** `UNIQUE (source, external_ref)` plus the in-flight guard (`InFlightRegistry`) ensure the same external order is accepted once. A repeat returns a DUPLICATE result referencing the existing order and causes no second order or stock deduction. | 20 concurrent identical submissions produce exactly one order row and one stock deduction (@RepeatedTest(20)). | 4 |
| FR-ORD-05 | MUST | **Source-specific behaviour (polymorphism).** `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder` override `calculateDeliveryEstimate()` and `processPayment()` with source-specific rules. | For the same item set and distance the three subclasses return different estimates/fees as specified in SDD §4.4. | 2 |
| FR-ORD-06 | MUST | **One brand per order.** Every order belongs to exactly one brand's station and all its items come from that station's menu. | A request mixing items of two brands is rejected (VR-12). | 2 |
| FR-ORD-07 | MUST | **Rejection handling.** A submission that fails validation, is a duplicate, or hits insufficient stock is rejected: nothing remains persisted for validation/stock failures, the caller receives structured errors, `rejected` is incremented and a REJECTED event is logged. | After a rejected submission the `orders` table is unchanged, stock is unchanged, the rejected counter increased by 1. | 3 |
| FR-ORD-08 | SHOULD | **In-house order entry form.** Authenticated staff can enter an IN_HOUSE order through a web form (POST, PRG, CSRF, flash message). | Valid form submission redirects to the new order page; invalid submission redirects back with field errors and retained input. | 7 |
| FR-ORD-09 | COULD | **Order search.** Orders can be filtered by external reference, status, source and date. | Search by partial external reference returns only matching orders. | 8 |
| FR-ORD-10 | COULD | **Public tracking page.** A public page shows status for a given tracking code and never shows customer details. | Valid code shows status and timeline; unknown code shows a generic not-found page; no PII in HTML. | 8 |

### 3.3 Order lifecycle (FR-LIF)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-LIF-01 | MUST | **Lifecycle enforced in OrderStatus.** `OrderStatus` defines the nine statuses and the allowed transitions; `canTransitionTo` and `requireTransitionTo` are the only place the rules exist. | All 81 (from,to) pairs behave exactly as the locked transition table; illegal ones throw `InvalidStatusTransitionException`. | 2 |
| FR-LIF-02 | MUST | **Terminal statuses.** DELIVERED, CANCELLED and FAILED have no outgoing transitions. | `OrderStatus.isTerminal()` true for exactly those three; any transition out of them is rejected. | 2 |
| FR-LIF-03 | MUST | **Conditional, audited transitions.** Every status change is `UPDATE ... SET status=? WHERE id=? AND status=?` and, if one row changed, inserts an `order_status_history` row in the same transaction. Zero rows changed means someone else already moved the order. | Two threads attempting the same transition: exactly one succeeds and exactly one history row exists (@RepeatedTest(20)). | 3 |
| FR-LIF-04 | MUST | **Status history visible.** The order page lists every status change with UTC time, from/to status and actor. | After a full lifecycle the order page shows PLACED->VALIDATED->ROUTED->PREPARING->PACKED->OUT_FOR_DELIVERY->DELIVERED in order. | 7 |
| FR-LIF-05 | MUST | **Cancellation.** Staff can cancel an order only from statuses where the transition table allows CANCELLED (PLACED, VALIDATED, ROUTED). Other attempts show a flash error and change nothing. | Cancel on a PREPARING order is refused; cancel on a ROUTED order succeeds and restores stock once. | 3 |
| FR-LIF-06 | MUST | **Trackable and Payable contracts.** `Order` implements the `Trackable` and `Payable` interfaces so status/history and payment are accessed through contracts. | `Order` is assignable to both interfaces; dashboards/reports use the interface types where possible. | 2 |

### 3.4 Inventory (FR-INV)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-INV-01 | MUST | **Item-level stock per station.** Each menu item (owned by one station) has one inventory row with `stock_qty` and `reorder_level`. | Seed data gives every active menu item a stock row; the DAO returns stock joined to station. | 3 |
| FR-INV-02 | MUST | **Atomic conditional deduction.** Stock is deducted with `UPDATE inventory SET stock_qty = stock_qty - ? WHERE item_id = ? AND stock_qty >= ?` inside the order-creation transaction (TX1); zero rows updated means insufficient stock. | N threads competing for K units sell exactly K units; stock never negative (@RepeatedTest(20)). | 3 |
| FR-INV-03 | MUST | **Deterministic lock order.** Multi-item deductions are executed in ascending `item_id` order (sorted map). | Test with two orders containing the same items in opposite order completes without deadlock exception surfacing. | 3 |
| FR-INV-04 | MUST | **Deadlock retry.** A transaction failing with SQLState 40001 / vendor code 1213 is rolled back and retried up to 3 times (`tx.retry.max`), then surfaced as `PersistenceException`. | A forced deadlock is retried and succeeds; a permanent deadlock fails after exactly 3 retries. | 3 |
| FR-INV-05 | MUST | **All-or-nothing deduction.** If any line has insufficient stock the whole TX1 is rolled back: no order row, no partial deduction. | Order with items A (enough) and B (not enough) leaves A's stock unchanged. | 3 |
| FR-INV-06 | MUST | **Conditional stock restore.** On transition to CANCELLED or FAILED from VALIDATED or ROUTED, stock is restored exactly once (guarded by `orders.stock_deducted`). From PREPARING or later, stock is not restored. | Cancel from ROUTED restores; fail from PREPARING does not; second restore attempt changes nothing. | 3 |
| FR-INV-07 | MUST | **Low-stock flag.** An item is flagged low-stock when `stock_qty <= reorder_level`; the flag appears on the dashboard and inventory page. | Stock at reorder level shows the flag; above it does not. | 3 |
| FR-INV-08 | SHOULD | **Admin menu management.** ADMIN can add menu items, change price and activate/deactivate items. | Price change applies to new orders only; old orders keep their stored unit price. | 7 |
| FR-INV-09 | SHOULD | **Admin stock management.** ADMIN can set stock quantity and reorder level per item. | Adjustment is atomic and visible on the dashboard at next refresh; negative values refused. | 7 |

### 3.5 Routing (FR-RTE)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-RTE-01 | MUST | **One station per brand.** `RoutingService` maps an order's brand to exactly one kitchen station; unknown or inactive brands are rejected. | Orders for each seeded brand land on the matching station queue. | 2 |
| FR-RTE-02 | MUST | **Post-commit routing.** After TX1 commits, the order id is enqueued on its station queue and then moved VALIDATED->ROUTED by a conditional update. | Order history shows VALIDATED then ROUTED; the ROUTED row is written after the enqueue. | 4 |

### 3.6 Concurrency and pipeline (FR-CON)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-CON-01 | MUST | **Bounded station queues.** Each station owns one `ArrayBlockingQueue<Long>` of order ids with capacity `station.queue.capacity`. | `remainingCapacity()` equals configured capacity minus queue size for every station. | 4 |
| FR-CON-02 | MUST | **One worker per station.** `OrderOrchestrator` owns an `ExecutorService` running one `StationWorker` per station; threads are named `station-<brand>-worker`. | Thread dump of a running test shows one thread per seeded brand with the expected name. | 4 |
| FR-CON-03 | MUST | **Queue-full compensation.** If `offer(orderId, timeout)` fails, a compensating transaction restores stock, marks the order FAILED with a history row, logs the event, and the caller receives a QUEUE_FULL result. | With a full queue and stalled worker the submission returns QUEUE_FULL, stock equals its initial value, order is FAILED, and the `failed` counter increased by 1 (not `rejected`; see SDD §7.7). | 4 |
| FR-CON-04 | MUST | **Worker fault isolation.** An exception while processing one order marks that order FAILED (if possible), is logged, and never terminates the worker. | A poisoned order is FAILED and the next order on the same queue is still delivered. | 4 |
| FR-CON-05 | MUST | **Interrupt handling.** On `InterruptedException` the worker (and every other thread-owning class) restores the interrupt flag and exits cleanly. | After `shutdownNow()` the worker thread terminates within 2 s and its interrupt status was preserved when it exited. | 4 |
| FR-CON-06 | MUST | **No shared connections.** No `Connection` is stored in a field or shared between threads; each task borrows a pooled connection via `Database` and returns it. | `ArchitectureRulesTest` finds no `Connection` fields in service/pipeline classes; pool active count returns to 0 after load. | 3 |
| FR-CON-07 | MUST | **Harmless duplicate processing.** Processing the same order id twice (duplicate enqueue, recovery overlap) causes no double effect, because every step is a conditional update and the worker skips orders it cannot claim. | Enqueueing one order id three times yields one DELIVERED order and one history chain. | 4 |
| FR-CON-08 | MUST | **Claim race tolerance.** A worker that dequeues an order still in VALIDATED (ROUTED update not yet visible) retries the claim a bounded number of times and then performs the conditional VALIDATED->ROUTED itself before claiming. | Test inserting an artificial delay before the ROUTED update still ends in DELIVERED. | 4 |
| FR-CON-09 | MUST | **Metrics.** `MetricsRegistry` keeps `AtomicLong` counters: submitted, processed, failed, rejected, plus total processing time and sample count for the average. | After a mixed run, counters satisfy the invariant in SDD §7.7. | 2 |
| FR-CON-10 | MUST | **Worker states.** Each worker exposes a `WorkerState` (STARTING, IDLE, PROCESSING, PAUSED, STOPPING, STOPPED) readable by the dashboard. | State is IDLE when the queue is empty, PROCESSING while an order is handled, STOPPED after shutdown. | 4 |
| FR-CON-11 | SHOULD | **Pause and resume workers.** ADMIN can pause and resume a station worker from the worker panel; a paused worker leaves orders queued. | Paused worker: queue grows, no transitions; after resume the queue drains. | 7 |

### 3.7 Resilience, recovery and shutdown (FR-RES)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-RES-01 | MUST | **Feed simulator.** `FeedSimulator` produces simulated orders for all three sources via a `ScheduledExecutorService`, with `start`, `stop`, `setRate` (orders per minute) and `isRunning`, controllable by ADMIN in the UI. | Start at 60/min creates about 1 order/s; stop halts production; rate change takes effect without restart. | 5 |
| FR-RES-02 | MUST | **Startup recovery from DB.** At startup, before accepting feed or web orders, `RecoveryService` reloads every non-terminal order from the database and re-enqueues it on its station queue. | Orders left in VALIDATED/ROUTED/PREPARING/PACKED/OUT_FOR_DELIVERY before a restart reach DELIVERED after it. | 5 |
| FR-RES-03 | MUST | **Resumable processing.** The worker advances an order from whatever non-terminal status it finds, so recovered orders continue where they stopped. | An order recovered in PACKED goes to OUT_FOR_DELIVERY then DELIVERED without repeating earlier steps. | 5 |
| FR-RES-04 | MUST | **Graceful shutdown.** `ShutdownCoordinator.shutdown()` (idempotent) stops accepting orders, stops the simulator and snapshot scheduler, drains or persists in-flight work, `awaitTermination` with a timeout, then `shutdownNow()`, writes the final snapshot, closes pool, logs and embedded DB. It is invoked by both `AppBootstrapListener` (ServletContextListener) and a JVM shutdown hook. | Triggering shutdown twice (listener + hook) runs the steps once; after shutdown no application threads remain; submissions during shutdown are refused. | 5 |
| FR-RES-05 | MUST | **Scheduled snapshot task.** `SnapshotTask` runs on a `ScheduledExecutorService` every `snapshot.interval.seconds` and writes a signed snapshot of in-flight orders. | After N intervals N snapshot files exist (up to the retention limit). | 6 |

### 3.8 File handling (FR-FIL)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-FIL-01 | MUST | **Structured orders.log.** `NioOrderEventLog` appends one structured key=value event per line to `orders.log` using NIO (`FileChannel`, `APPEND`), thread-safe, append-only. | 1,000 events from 16 threads yield exactly 1,000 intact lines (@RepeatedTest(20)). | 6 |
| FR-FIL-02 | MUST | **No PII or secrets in logs.** Neither `orders.log` nor the application log contains customer names, addresses, passwords, hashes, HMAC secrets or session ids. | `NoPiiInLogsTest` greps all log output of a full run for planted sentinel values and finds none. | 6 |
| FR-FIL-03 | SHOULD | **CSV daily report.** ADMIN can export a daily report (UTC date) as CSV: RFC 4180 quoting, UTF-8, cells starting with `=`, `+`, `-`, `@` neutralised. | Export of a day containing an order whose external reference is `=cmd` yields a safe cell; row count equals orders created that day; the file has no customer name column. | 6 |
| FR-FIL-04 | MUST | **Paths from configuration.** Log, snapshot and report directories come from configuration and are created if missing; no hard-coded absolute paths. | Changing `log.orders.path` moves the log file. | 6 |

### 3.9 Serialization (FR-SER)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-SER-01 | MUST | **OrderSnapshotDTO.** `OrderSnapshotDTO` is `Serializable`, declares `serialVersionUID`, and contains plain fields only (primitives, `String`, nested serializable DTO lines; money as `String`, timestamps as epoch millis). | Reflection test asserts every field type is on the allowed plain list. | 6 |
| FR-SER-02 | MUST | **Periodic and shutdown snapshots.** In-flight (non-terminal) orders are serialized to `snapshots/` periodically and at shutdown. | A final snapshot exists after graceful shutdown and contains exactly the non-terminal orders. | 6 |
| FR-SER-03 | MUST | **HMAC-signed snapshots.** Each snapshot is signed with HMAC-SHA256 using `snapshot.hmac.secret`; the signature is verified (constant time) before any deserialization. | Flipping one byte of the file or signature yields `SnapshotIntegrityException` and no `readObject` call. | 6 |
| FR-SER-04 | MUST | **ObjectInputFilter allowlist.** Deserialization uses an `ObjectInputFilter` allowlist (plus depth/size limits) built by `SnapshotFilterFactory`; everything else is rejected. | A validly signed stream containing an unlisted class is rejected. | 6 |
| FR-SER-05 | MUST | **Snapshot recovery.** At startup, after signature check and filtered deserialization, only orders missing from the database (by source+external_ref) are inserted, with `stock_deducted=0`, then queued like other recovered orders. | Snapshot containing 3 orders of which 1 is missing from DB inserts exactly 1. | 6 |
| FR-SER-06 | MUST | **Serialization safety rules.** Web input and uploads are never deserialized; snapshots contain no credentials, connections, locks or thread state. | `ArchitectureRulesTest` finds `ObjectInputStream` only in the snapshot package; the DTO has no non-plain fields. | 6 |
| FR-SER-07 | MUST | **Serialization documentation.** Docs state why native serialization is used (academic requirement), the risks of untrusted deserialization, and the versioning policy. | SDD §9 contains the three subsections; reviewed at Phase 6 exit. | 6 |

### 3.10 Authentication and authorisation (FR-AUT)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-AUT-01 | MUST | **Login and password storage.** Staff log in with username and password. Passwords are stored as PBKDF2WithHmacSHA256 with a per-user random salt and at least 600,000 iterations (configurable, never below 600,000); comparison is constant-time. | Stored hash format `pbkdf2_sha256$iterations$salt$hash`; configuring fewer than 600,000 iterations fails startup. | 2 |
| FR-AUT-02 | MUST | **Session fixation protection.** The session id is regenerated at login (`HttpServletRequest.changeSessionId()`) and the CSRF token is rotated. | Session cookie value before and after login differs. | 7 |
| FR-AUT-03 | MUST | **Session timeout.** Sessions time out after 30 minutes of inactivity (`session-timeout` 30 in `web.xml`). | Deployment descriptor value is 30 and session `getMaxInactiveInterval()` is 1800 s. | 7 |
| FR-AUT-04 | MUST | **Logout.** Logout is a POST (with CSRF token) that fully invalidates the session and redirects to the login page. | After logout the old session cookie no longer grants access. | 7 |
| FR-AUT-05 | MUST | **Account lockout.** Five consecutive failed logins lock the account for five minutes (`staff_users.failed_attempts`, `locked_until`); the response never reveals whether the username exists or is locked. | 5 wrong passwords then the correct one is refused with the same generic message; after 5 minutes (test clock) it succeeds. | 3 |
| FR-AUT-06 | MUST | **Roles and access filters.** Roles are ADMIN and KITCHEN_STAFF. `AuthFilter` and `RoleFilter` guard every protected route using the single `RoutePolicy` table. | `RoleAccessWebTest` iterates every route in the SDD routes table for anonymous, KITCHEN_STAFF and ADMIN. | 7 |
| FR-AUT-07 | MUST | **Initial users from config.** At first bootstrap, if `staff_users` is empty, `UserSeeder` creates the initial ADMIN and KITCHEN_STAFF users from git-ignored configuration. Passwords never appear in docs, commits or logs. | Second start with changed config values does not alter existing users; secret scan of the repository finds no credentials. | 3 |
| FR-AUT-08 | MUST | **Cookie attributes.** The session cookie is `HttpOnly`, `SameSite=Lax`, and `Secure` when `server.session.cookie.secure=true`. | Set-Cookie header contains HttpOnly and SameSite=Lax; with config true it also contains Secure. | 7 |
| FR-AUT-09 | COULD | **Admin password reset.** ADMIN can set a new password for a staff user (no email flow). | New password works, old one fails, failed-attempt counters are reset. | 8 |

### 3.11 Web layer (FR-WEB)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| FR-WEB-01 | MUST | **MVC separation.** Servlets are controllers, JSPs are views (under `WEB-INF/views`, no scriptlets, no SQL, no business logic), DTO/domain objects are models passed via request attributes. | JSP files contain no `<%` scriptlets (checked by test) and are not directly reachable by URL. | 7 |
| FR-WEB-02 | MUST | **POST-redirect-GET with flash.** Every successful or failed POST responds with a redirect; one-time flash messages carry the outcome. | Refreshing the page after a POST does not resubmit; the flash message appears once. | 7 |
| FR-WEB-03 | MUST | **CSRF protection.** Every POST form contains a per-session CSRF token; `CsrfFilter` rejects POSTs with a missing or wrong token (403) using constant-time comparison. | POST without token returns 403 for every POST route (route list from `RoutePolicy`). | 7 |
| FR-WEB-04 | MUST | **UTF-8 everywhere.** `EncodingFilter` sets UTF-8 on request and response; pages declare UTF-8. | A customer name with Hindi and emoji characters displays unchanged after round trip. | 7 |
| FR-WEB-05 | MUST | **Generic error pages.** 400, 403, 404 and 500 (and uncaught `Throwable`) map to generic pages without stack traces, class names or SQL; the server log keeps the detail under an error id shown to the user. | Forcing an exception returns a 500 page that contains none of: `Exception`, `at com.`, `SQL`. | 7 |
| FR-WEB-06 | MUST | **Dashboard.** The dashboard auto-refreshes every 5 seconds and shows low-stock flags, queue size/capacity per station, worker states, order counts by status and metrics. | Page contains `refresh` set to 5 and all five blocks; values match backend state in a controlled test. | 7 |
| FR-WEB-07 | MUST | **Order list and detail.** Staff can list orders (filter by status, paginated) and open an order page with items, totals, payment info and status history. | List shows newest first with 20 per page; detail shows all items and history. | 7 |
| FR-WEB-08 | MUST | **Output escaping.** All user-supplied text is escaped when rendered (`<c:out>` / `fn:escapeXml`). | A customer name `<script>alert(1)</script>` is shown as text, never executed or unescaped in HTML. | 7 |
| FR-WEB-09 | MUST | **Simulator control page.** ADMIN can start/stop the feed simulator and change its rate from the web UI. | Start, change rate, stop each take effect and are reflected on the page; KITCHEN_STAFF receives 403. | 7 |
| FR-WEB-10 | SHOULD | **Worker panel.** ADMIN sees per-station worker state, queue fill, current order id, processed count and can pause/resume. | Panel reflects state changes within one refresh. | 7 |
| FR-WEB-11 | COULD | **Dashboard charts.** Orders by status/source shown as server-rendered SVG or CSS bars (no extra dependency). | Chart data equals the counts table on the same page. | 8 |
| FR-WEB-12 | MUST | **Security headers.** Responses carry `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` and a restrictive `Content-Security-Policy`; pages use no inline scripts. | Header assertions on a sample of routes. | 7 |
| FR-WEB-13 | SHOULD | **Snapshot on demand.** ADMIN can trigger an immediate snapshot from the admin UI. | Pressing the button creates a new signed snapshot file and shows a flash message. | 7 |

### 3.12 Non-functional requirements (NFR)

| ID | Priority | Requirement | Acceptance criterion | Phase |
|---|---|---|---|---|
| NFR-SEC-01 | MUST | **No secrets in the repository.** No passwords, HMAC secrets or DB credentials are committed; config, data, logs, snapshots, reports are git-ignored. | `git grep` for the sentinel patterns in the Phase checklist finds nothing; `git ls-files` shows only `*.example` config. | 1 |
| NFR-SEC-02 | MUST | **Prepared statements only.** All SQL uses `PreparedStatement` with bound parameters; no string-concatenated SQL with user data; multi-statement mode disabled. | `ArchitectureRulesTest` finds no `createStatement(` outside schema execution and no `+` concatenation inside SQL literals in `dao.jdbc`. | 3 |
| NFR-SEC-03 | MUST | **Authentication errors are uniform.** Login failures, unknown users and locked accounts produce the same visible message and similar timing (dummy hash on unknown user). | Response bodies for the three cases are identical. | 3 |
| NFR-REL-01 | MUST | **No lost orders.** Every accepted order either reaches a terminal status or is present as non-terminal in the database and recovered after restart. | CrashRecoveryTest: after abrupt stop and restart no accepted order is missing or stuck. | 5 |
| NFR-REL-02 | MUST | **Stock integrity.** Stock never negative; for every item `initial = remaining + units held by non-restored orders` at quiescence. | Invariant checker run at the end of every concurrency test and in the end-to-end test. | 4 |
| NFR-REL-03 | MUST | **Bounded shutdown.** Shutdown completes within `shutdown.drain.seconds + shutdown.await.seconds + 5` seconds even with stuck workers. | Test with a worker blocked in a long sleep still terminates the JVM-side executors in time. | 5 |
| NFR-PERF-01 | SHOULD | **Sustained simulated load.** At the default configuration the system sustains 60 simulated orders/minute for 5 minutes with no queue-full failures, and the dashboard renders in under 1 s. | `ThroughputSmokeTest` (scaled-down duration) passes; manual 5-minute run recorded in EVIDENCE.md. | 8 |
| NFR-PORT-01 | MUST | **Cross-platform.** The documented commands work on Windows (`mvnw.cmd`), Linux and macOS with JDK 17+. Only the platforms actually tried are claimed in EVIDENCE.md. | EVIDENCE.md lists at least one verified OS; others marked unverified. | 1 |
| NFR-MNT-01 | MUST | **Layering rules.** Dependencies flow web -> service -> dao -> db; domain and pipeline contain no servlet types; only `dao.jdbc`, `db`, `bootstrap` and recovery code use `java.sql`. | `ArchitectureRulesTest` passes. | 2 |
| NFR-MNT-02 | MUST | **Docs stay the source of truth.** Each phase updates TRACEABILITY.md, viva notes and EVIDENCE.md; conflicts between code and docs are reported, not silently resolved. | Phase exit checklist items ticked with evidence. | 1 |
| NFR-TST-01 | MUST | **Deterministic tests, no mocking framework.** Tests use JUnit 5 only (hand-written fakes, injected `Clock`); concurrency tests use `@RepeatedTest(20)`; web tests use embedded Tomcat on a random port and `java.net.http.HttpClient`. | `./mvnw -q verify` passes three times consecutively without flakes; no Mockito on the classpath. | 1 |
| NFR-OBS-01 | MUST | **Application logging.** SLF4J + Logback with levels INFO (lifecycle), WARN (recoverable), ERROR (failures with error id); no PII; thread name in the pattern. | Log lines show thread names such as `station-BIRYANI-worker`. | 6 |
| NFR-USA-01 | SHOULD | **Basic usability.** Pages work at 1280x720 and 375 px widths, forms have labels, and status/low-stock are shown with text as well as colour. | Manual review checklist at Phase 7 exit. | 7 |


---

## 4. Use cases

Notation: *Main flow* is the success path; *Alt* lines are alternative or exception flows keyed to the main-flow step they deviate from.

### UC-01 Log in (MUST)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-AUT-01, FR-AUT-02, FR-AUT-05, NFR-SEC-03.
* **Preconditions:** User exists and is enabled; user has no valid session.
* **Main flow:**
  1. User opens `/login` (GET); a session is created to carry the CSRF token and the form is shown.
  2. User submits username and password (POST `/login`, with CSRF token).
  3. System checks the lock state, verifies the PBKDF2 hash in constant time, resets the failed counter.
  4. System regenerates the session id, stores `SessionUser`, rotates the CSRF token and redirects to `/dashboard`.
* **Alt 3a (wrong password / unknown user):** System increments `failed_attempts` (or performs a dummy hash for an unknown user), redirects to `/login` with the generic flash message "Invalid credentials or account temporarily locked."
* **Alt 3b (5th consecutive failure):** System sets `locked_until = now + 5 minutes`; same generic message.
* **Alt 3c (account locked):** Correct password is not accepted until `locked_until` passes; same generic message.
* **Alt 2a (missing/invalid CSRF token):** 403 generic page.
* **Postconditions:** On success: authenticated session with new id, `failed_attempts = 0`, `last_login_at` set. On failure: no authenticated session; counters updated; nothing about the cause revealed.

### UC-02 Log out (MUST)
* **Actor:** any authenticated user. **Covers:** FR-AUT-04.
* **Preconditions:** Authenticated session.
* **Main flow:** 1. User presses *Log out* (POST `/logout` with CSRF token). 2. System invalidates the whole session. 3. System redirects to `/login`.
* **Alt 2a:** Token missing: 403, session remains.
* **Postconditions:** Old session id is unusable.

### UC-03 Receive an aggregator order (MUST)
* **Actor:** Aggregator (simulated via `FeedSimulator`). **Covers:** FR-ORD-01..07, FR-INV-02..05, FR-RTE-01..02, FR-CON-03.
* **Preconditions:** Application started; recovery finished; simulator running or a test calls `OrderService.submitOrder`.
* **Main flow:**
  1. Source sends `OrderRequest` (SWIGGY or ZOMATO).
  2. System acquires the in-flight key (`source:externalRef`).
  3. System validates the request (pure, VR-01..VR-14).
  4. System runs TX1: insert order + items + conditional stock deduction + status VALIDATED + history rows; commit.
  5. System offers the order id to the station queue (with timeout) and moves the order to ROUTED.
  6. System returns `SubmissionResult(ACCEPTED, orderId)`; `submitted` incremented; ROUTED/accepted event logged.
* **Alt 2a (key already in flight):** Return `DUPLICATE` without touching the database.
* **Alt 3a (validation errors):** Return `REJECTED` with all `FieldError`s; nothing persisted; `rejected` incremented.
* **Alt 4a (duplicate `source+external_ref`):** Unique violation maps to `DuplicateOrderException`; TX1 rolled back; return `DUPLICATE` with the existing order id.
* **Alt 4b (insufficient stock):** TX1 rolled back; return `REJECTED` (reason `INSUFFICIENT_STOCK`); `rejected` incremented.
* **Alt 4c (deadlock):** Roll back and retry TX1 up to 3 times; then `PersistenceException` -> `REJECTED` (reason `TEMPORARY_FAILURE`).
* **Alt 5a (queue full after timeout):** Compensation transaction restores stock, marks the order FAILED (history row), logs; return `QUEUE_FULL`; `failed` incremented.
* **Postconditions (accepted):** Order is ROUTED (or later) in DB; stock reduced; one id on the station queue; in-flight key released.

### UC-04 Enter an in-house order (SHOULD)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-ORD-08, FR-WEB-02, FR-WEB-03.
* **Preconditions:** Authenticated.
* **Main flow:** 1. User opens `/orders/new` (GET). 2. Selects brand, items and quantities, payment mode, enters name and distance. 3. Submits (POST `/orders/new`, CSRF). 4. System builds an `OrderRequest(IN_HOUSE)` with a generated external reference and calls `OrderService.submitOrder`. 5. System redirects (PRG) to `/orders/view?id=<id>` with a success flash message.
* **Alt 4a (rejected):** Redirect to `/orders/new` with errors and retained input in the flash scope.
* **Alt 4b (QUEUE_FULL):** Redirect to the order page (status FAILED) with an error flash message.
* **Postconditions:** As UC-03.

### UC-05 Process an order at its station (MUST)
* **Actor:** StationWorker. **Covers:** FR-CON-02, FR-CON-04, FR-CON-07, FR-CON-08, FR-RES-03.
* **Preconditions:** Order id on the station queue; worker running and not paused.
* **Main flow:** 1. Worker polls the queue (with timeout) and takes an id. 2. Worker loads the order. 3. Worker claims it (ROUTED->PREPARING, conditional). 4. Simulated preparation; PREPARING->PACKED. 5. PACKED->OUT_FOR_DELIVERY. 6. Simulated delivery; OUT_FOR_DELIVERY->DELIVERED. 7. Metrics updated (`processed`, processing time). Each step is a conditional update plus a history row.
* **Alt 3a (still VALIDATED):** Retry the claim up to `worker.claim.retry.count` times, `worker.claim.retry.delay.ms` apart.
* **Alt 3b (already advanced/terminal, e.g. cancelled or duplicate id):** Skip silently (logged at DEBUG).
* **Alt 4a (processing exception):** Mark the order FAILED if the transition is legal (no stock restore after PREPARING), log with an error id, `failed` incremented; the worker continues with the next id.
* **Alt 1a (interrupt):** Restore interrupt flag, leave the loop; the unfinished order stays non-terminal in the DB and is recovered at next start.
* **Postconditions:** Order is DELIVERED/FAILED, or untouched if skipped; worker alive.

### UC-06 Cancel an order (MUST)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-LIF-05, FR-INV-06.
* **Preconditions:** Authenticated; order exists.
* **Main flow:** 1. User opens the order page and presses *Cancel* (POST `/orders/cancel`, CSRF). 2. System attempts a conditional transition to CANCELLED from the current status; if the order had a stock deduction and had not reached PREPARING, stock is restored in the same transaction. 3. PRG with success flash message.
* **Alt 2a (status not cancellable, or a worker moved it first):** Zero rows changed; flash error "Order can no longer be cancelled"; no data changed.
* **Postconditions:** CANCELLED with a history row and restored stock, or unchanged.

### UC-07 Monitor the dashboard (MUST)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-WEB-06, FR-INV-07, FR-CON-10.
* **Preconditions:** Authenticated.
* **Main flow:** 1. User opens `/dashboard`. 2. System shows counts per status, per-station queue size/capacity and worker state, metrics (submitted, processed, failed, rejected, average processing time), low-stock items. 3. Page refreshes itself every 5 seconds.
* **Alt 2a (database temporarily unavailable):** Generic 500 page; the page keeps refreshing, no stack trace shown.
* **Postconditions:** Read-only; no state change.

### UC-08 View an order and its history (MUST)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-LIF-04, FR-WEB-07.
* **Main flow:** User opens `/orders` (filter by status, paged) then `/orders/view?id=`. System shows items, totals, payment, estimated delivery and the status history table.
* **Alt:** Unknown id: generic 404 page.

### UC-09 Manage menu and stock (SHOULD)
* **Actor:** ADMIN. **Covers:** FR-INV-08, FR-INV-09.
* **Main flow:** Admin opens `/admin/menu` or `/admin/stock`, edits price/active flag or sets stock quantity/reorder level (POST + CSRF), receives a flash message.
* **Alt:** Invalid values (negative stock, price with more than 2 decimals) are rejected with a flash error.
* **Postconditions:** Changes apply to subsequent orders; existing orders keep their stored prices.

### UC-10 Control the feed simulator (MUST)
* **Actor:** ADMIN. **Covers:** FR-RES-01, FR-WEB-09.
* **Main flow:** Admin opens `/admin/simulator`, sets rate (orders/minute) and starts or stops (POST + CSRF).
* **Alt:** Rate outside `1..simulator.max.rate.per.minute` is refused. Starting a running simulator, or stopping a stopped one, is a no-op with an informational message.

### UC-11 Pause or resume a worker (SHOULD)
* **Actor:** ADMIN. **Covers:** FR-CON-11, FR-WEB-10.
* **Main flow:** Admin opens `/admin/workers`, pauses a station; its worker finishes the current order and stops taking new ones (state PAUSED); resume restores IDLE.

### UC-12 Export a daily CSV report (SHOULD)
* **Actor:** ADMIN. **Covers:** FR-FIL-03.
* **Main flow:** Admin opens `/admin/reports`, picks a UTC date; GET `/admin/reports/daily.csv?date=` returns a CSV attachment (also saved to the configured report directory).
* **Alt:** Invalid date: 400 page. No orders: header-only CSV.

### UC-13 Start up and recover (MUST)
* **Actor:** Operator / system. **Covers:** FR-ENV-02, FR-RES-02, FR-SER-05, NFR-REL-01.
* **Preconditions:** `config/application.properties` present.
* **Main flow:** 1. Load configuration (fail fast). 2. `DbBootstrap` starts embedded DB if `db.mode=embedded`. 3. Pool and schema/seed initialisation; `UserSeeder` if no users. 4. Create queues and start workers (`OrderOrchestrator`). 5. `RecoveryService`: verify and read the latest valid snapshot, insert orders missing from the DB; reload all non-terminal orders from the DB; re-enqueue. 6. Start snapshot scheduler; accept orders and web traffic.
* **Alt 5a (snapshot invalid or tampered):** Log the integrity failure (no content), skip the snapshot, continue with DB recovery only.
* **Alt 5b (more recovered orders than queue capacity):** Recovery loader blocks on `put` while workers drain; startup of the web layer is not delayed beyond the configured limit.
* **Alt 1a (configuration missing/invalid):** Exit with a clear error.
* **Postconditions:** All non-terminal orders are queued; workers running.

### UC-14 Shut down gracefully (MUST)
* **Actor:** Operator (Ctrl+C, Tomcat stop). **Covers:** FR-RES-04, FR-SER-02, NFR-REL-03.
* **Main flow:** Listener `contextDestroyed` or JVM hook calls `ShutdownCoordinator.shutdown()`: refuse new orders, stop simulator, drain queues up to `shutdown.drain.seconds`, `awaitTermination`, `shutdownNow`, final snapshot, close pool, close log, stop embedded DB.
* **Alt:** Second trigger (listener + hook) is a no-op that waits for the first to finish. A stuck worker is interrupted after the timeout; its order stays non-terminal and is recovered later.

### UC-15 Search orders (COULD)
* **Actor:** ADMIN, KITCHEN_STAFF. **Covers:** FR-ORD-09. Filter by external reference substring, status, source, date range on `/orders`.

### UC-16 Track an order publicly (COULD)
* **Actor:** Public visitor. **Covers:** FR-ORD-10. `/track?code=<tracking_code>` shows status and timeline only. Unknown code: generic not-found message.

### UC-17 Reset a staff password (COULD)
* **Actor:** ADMIN. **Covers:** FR-AUT-09. Admin sets a new password for a user (POST + CSRF); counters reset; no email flow.

---

## 5. Acceptance

### 5.1 Global acceptance (project level)
The project is accepted when all of the following hold, each backed by an entry in [EVIDENCE.md](EVIDENCE.md):
1. Every MUST requirement is "Done" in [TRACEABILITY.md](TRACEABILITY.md).
2. `./mvnw -q clean verify` passes on a clean clone with only JDK 17+ and Git, three runs in a row.
3. `@RepeatedTest(20)` concurrency tests pass 20/20.
4. The application starts with `./mvnw exec:java`, the demo script in the Review 2 pack runs end to end, and a graceful stop plus restart recovers in-flight orders.
5. The built WAR contains no embedded-server jars and boots on standalone Tomcat 10.1 *if such an environment is available*; otherwise this is declared unverified.
6. Secret scan clean; no real credentials in the repository history.

### 5.2 Definition of done per requirement
Implemented, unit/DAO/web test written and passing, traceability row updated to "Done" with commit hash, evidence recorded, viva note written. Full definition: [TESTING_STRATEGY §11](TESTING_STRATEGY.md).

### 5.3 Role permission summary
The authoritative route-by-role table is [SDD §10.2](SDD.md) and [SDD §10.6](SDD.md); `RoutePolicy` implements it and `RoleAccessWebTest` verifies it.

# Review 2 Preparation

**Purpose.** A study and demonstration pack for the Review 2 viva of the Cloud Kitchen Orchestration System. Every statement here is tied to code in this repository or to an entry in [EVIDENCE.md](EVIDENCE.md). Where something is **not** verified the text says so; do not claim more in the viva than this page does.

**Scope of what was verified.** Linux (Ubuntu 24.04), JDK 21, embedded Tomcat 10.1 and embedded MariaDB 11.4.5, headless Chromium. Not verified: real MySQL 8, a standalone Tomcat 10.1, Windows, macOS, other browsers, HTTPS, mobile devices.

**State of the work.** Phases 1 to 6 are committed on branch `claude/cloud-kitchen-orchestration-7d1aok` (Phase 6 tip `969146a`). Phase 7 (web layer) is committed as `1208332`, the Phase 8 fixes and tests as `1a35962`, the Phase 8 documentation as `c6e0c59`, and a documentation-only commit `294d4e8` records these hashes; the branch is pushed through `294d4e8`. They are **unreleased**: no tag exists for Phase 7 or Phase 8 (the earlier tag push was refused with HTTP 403). Nothing here may be presented as released.

---

## 1. Architecture walkthrough (3 minutes)

```
                       +--------------------- Tomcat 10.1 (embedded, Launcher) -----------------------+
Browser --HTTP--> Encoding -> SecurityHeaders -> Csrf -> Auth -> Role  --> *Servlet (controllers) --> JSP views
                       +----------------------------------------------------------------------------+
                                                         |  AppServices (narrow interfaces only)
                                                         v
        service layer:  OrderService (TX1, transitions, cancel, compensation)  AuthService  MenuService  StockService
                        OrderQueryService  DashboardService  ReportService  SnapshotControl / WorkerControl / SimulatorControl
                                                         |
                                  dao (interfaces) -> dao.jdbc (plain JDBC, PreparedStatement) -> db.Database (transactions, retry)
                                                         |
                                       HikariCP -> MariaDB (embedded, data in ./data)   [MySQL 8 intended, unverified]

  pipeline:  OrderOrchestrator --(bounded ArrayBlockingQueue per brand)--> StationWorker x3 (thread station-<brand>-worker)
             FeedSimulator (ScheduledExecutorService)   WorkerMonitor / OrphanSweeper (repair)   RecoveryService (start-up)
             ShutdownCoordinator (8 ordered steps)       MetricsRegistry (AtomicLong)
  io / snapshot:  NioOrderEventLog (orders.log)   CsvReportExporter   SnapshotService (HMAC-signed Serializable DTO)
```

Key ideas to say out loud:

1. **The database is the source of truth.** A queue holds only order ids; after any crash `RecoveryService` reloads every non-terminal order from the database and re-queues it.
2. **One transaction per workflow.** Order creation (insert order and items, conditional stock deduction, `VALIDATED`, history row) is one transaction (TX1). Metrics, logs, flash messages and queue operations happen only **after** the commit.
3. **Guards, not locks.** Status changes are conditional updates (`WHERE status = ?`); stock deduction is `UPDATE ... SET stock_qty = stock_qty - ? WHERE ... AND stock_qty >= ?`; duplicates are stopped by `UNIQUE (source, external_ref)`.
4. **Ownership of an order is a database lease with a fencing epoch**, so a stalled or replaced worker cannot overwrite the work of its successor.
5. **Layering is enforced by a test** (`ArchitectureRulesTest`, 35 rules), including "web never imports pipeline" and "ObjectInputStream only in snapshot".

## 2. Key-class walkthrough

| Class | What to say |
|---|---|
| `domain.Order` and `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder` | Abstract class with state and two abstract methods, `calculateDeliveryEstimate()` and `processPayment()`; each source overrides them differently. Called through an `Order` reference (runtime polymorphism). Implements `Trackable` and `Payable`. |
| `domain.enums.OrderStatus` | The lifecycle rules live **inside the enum** (allowed targets, `requireTransitionTo`, terminal states, whether stock is restorable). Nothing else may decide a transition. |
| `service.OrderService` | `submitOrder` (validate purely, then TX1), `transition`, `cancelOrder`, compensation. Stock lines are processed in sorted `item_id` order to avoid deadlocks; deadlocks are retried up to 3 times by `Database`. |
| `db.Database.inTransaction` | The only place that commits or rolls back; DAOs take a `Connection` and never commit or close it. Retries SQLState 40001 / error 1213. |
| `dao.jdbc.JdbcOrderDao` | All SQL through `PreparedStatement`; lease statements (`acquireLease`, `renewLease`, `transitionAsOwner`, `failAsOwner`) read the clock inside the DAO as the last step before executing. |
| `pipeline.OrderOrchestrator` | Owns the three bounded queues and the worker executor; `dispatch` does `offer` with a timeout and compensates on a full queue; `restartWorker` (automatic path, refuses a `DEAD` worker) and `operatorRestart` (administrator path, may revive a `DEAD` worker, D-095). |
| `pipeline.StationWorker` | One per station; `poll`, acquire lease, run PREPARING -> PACKED -> OUT_FOR_DELIVERY -> DELIVERED with simulated step times; a failing order never kills the worker; `InterruptedException` restores the flag and exits. |
| `pipeline.WorkerMonitor` / `OrphanSweeper` | Two independent repair paths using the same `repairStation` routine; detect dead or stalled workers, restart with a budget, then `DEAD`; reclaim leases of retired generations only; re-queue orphans. |
| `pipeline.RecoveryService` | Start-up: resolve pending compensations first, then re-queue non-terminal orders, with a bounded retry and failure isolation per order. |
| `pipeline.ShutdownCoordinator` | Runs 8 steps once even if called by both the servlet listener and the JVM hook; bounded drain, `awaitTermination`, then `shutdownNow`. |
| `snapshot.SnapshotService`, `HmacSigner`, `SnapshotFilterFactory` | Writes `OrderSnapshotDTO` (plain fields, `serialVersionUID`) signed with HMAC-SHA256; at start-up verifies the signature **before** deserializing, then deserializes through an `ObjectInputFilter` allowlist. |
| `io.NioOrderEventLog` | Append-only `orders.log` via `FileChannel`, thread-safe with a lock, structured events, no customer data. |
| `web.filter.*` | Fixed chain Encoding -> SecurityHeaders -> Csrf -> Auth -> Role; `AuthFilter` re-reads enabled/role/session version from the database on every protected request and fails closed. `RoutePolicy` is the single route table. |
| `web.support.AppServices` | A record in a servlet-context attribute; controllers see only narrow interfaces, so `web` never imports `pipeline`. |

## 3. Java concept map (only concepts that genuinely appear)

| Concept | Where | Test that shows it |
|---|---|---|
| Abstract class, inheritance, overriding, polymorphism | `Order` and its three subclasses | `OrderPolymorphismTest` |
| Interfaces | `Trackable`, `Payable`, `OrderDispatcher`, `OrderEventLog`, the DAO interfaces | `OrderFactoryTest`, `ArchitectureRulesTest` |
| Encapsulation | private fields, unmodifiable collections out, no setters for identity | `DomainValueObjectsTest`, `OrderFactoryTest` |
| Enums with behaviour | `OrderStatus` transition table | `OrderStatusTest` |
| Generics, functional interface | `Database.inTransaction(TxWork<T>)`, `Page<T>` | `DatabaseTransactionTest` |
| Collections | `EnumMap`/`EnumSet` (lifecycle), `ConcurrentHashMap.newKeySet` (in-flight guard), `ArrayBlockingQueue` (stations), `TreeMap` (sorted stock lines) | `InFlightRegistryTest`, `OrderOrchestratorTest` |
| Threads, executors, scheduling | `OrderOrchestrator`, `FeedSimulator`, `WorkerMonitor`, `OrphanSweeper`, `NamedThreadFactory` | `OrderOrchestratorTest`, `FeedSimulatorTest` |
| Atomics, locks | `MetricsRegistry` (`AtomicLong`), `NioOrderEventLog` (`ReentrantLock`) | `MetricsInvariantTest`, `NioOrderEventLogTest` |
| Interrupt handling | every worker/shutdown catch block restores the flag | `ShutdownCoordinatorTest` |
| Custom exceptions | `ValidationException`, `InvalidStatusTransitionException`, `InsufficientStockException`, ... | `OrderValidatorTest` |
| JDBC, transactions, pooling | `dao.jdbc.*`, `Database`, `DataSourceFactory` (HikariCP) | `TransactionAtomicityTest`, `StockDeductionConcurrencyTest` |
| NIO file I/O | `NioOrderEventLog`, `CsvReportExporter`, snapshot files | `NioOrderEventLogTest`, `CsvReportExporterTest` |
| Serialization and its risks | `OrderSnapshotDTO`, HMAC check, `ObjectInputFilter` allowlist | `SnapshotRoundTripTest`, `SnapshotTamperTest`, `SnapshotFilterTest` |
| Servlets, filters, listeners, sessions, JSP/EL/JSTL | `web.*`, `WEB-INF/views` | `RoleAccessWebTest`, `CsrfWebTest`, `JspRulesTest` |

The full table with exact method names is in [JAVA_CONCEPT_MAPPING.md](JAVA_CONCEPT_MAPPING.md). Concepts the project does **not** use and should not be claimed: Spring/JPA/Hibernate (forbidden), WebSockets, reflection-based frameworks, parallel streams.

## 4. Timed demo script (10 minutes)

Preparation (before the room): `config/application.properties` filled in (git-ignored), `./mvnw compile exec:java` started once so the database binaries are unpacked, then stopped. Start it again 2 minutes before the review and wait for `/health` to print `OK`. Have two browser windows (one signed in as staff, one as administrator).

The steps below were executed as a scripted run against the real application with default settings (11 of 11 steps passed, total scripted run 48 s, EV-065; repeated on the final tree after the remediation pass, 11 of 11 again, with the order delivered in 2.2 s, EV-069). The timings in the table are the **human** budget.

| Min | Step | What you do and say | Expected | If it goes wrong |
|---|---|---|---|---|
| 0:00 | 1 Start | Show `/health` = `OK` and the sign-in page. Mention embedded Tomcat + embedded MariaDB, nothing installed. | `OK`, sign-in page | Wrong password: use the `bootstrap.*` values from your private config. |
| 1:00 | 2 Staff | Sign in as staff. Show dashboard (queues x/50, worker states, metrics). Open `/admin/users` -> **403** generic page (role check). | 403 without a stack trace; admin links hidden | - |
| 2:00 | 3 Order | `/orders/new`: choose a brand, quantities, submit. Open the order page and watch `PREPARING -> PACKED -> OUT_FOR_DELIVERY -> DELIVERED` (about 2 s at the default step times; reload the order page once or twice to catch the middle states, or set `worker.prep.ms` higher for the demo); show the status history (7 rows). Show stock reduced on `/inventory`. | `DELIVERED`, stock down by the quantity | Refresh by hand if the page lags; auto-refresh is every 5 s. |
| 4:00 | 4 Oversell | Order more than the stock. | "not enough stock", stock unchanged | - |
| 4:30 | 5 Administrator | Sign in as administrator. `/admin/simulator`: start 60/min, wait about 30 s. Dashboard shows queues filling and metrics rising. Stop the simulator. | queues grow (for example 4/50, 8/50, 7/50), `rejected` may be 1 | At the default step times the kitchens carry 90 orders/minute, so 60/min is sustained; above 90/min queues fill and orders are compensated (by design). |
| 6:00 | 6 Cancel | `/admin/workers`: pause the workers, enter an order, cancel it, show stock restored; resume. | `CANCELLED`, stock back to the earlier value | If an order already started preparing, cancel is refused (correct behaviour). |
| 7:00 | 7 Restart | Restart one worker on the worker panel (generation-checked). Explain the generation number and the stale-click refusal. | "The worker of PIZZA was restarted (generation 2)" | A `DEAD` worker cannot be produced from the UI; do not promise this live. |
| 8:00 | 8 Files | `/admin/reports`: download the daily CSV (no customer data). Press *snapshot now*; show the file name. | `orders-<date>.csv`, "A snapshot was written: snapshot-....ser" | - |
| 9:00 | 9 Logout | Log out, press Back, replay the old cookie (mention the test) -> sign-in page. | `/login` | - |
| 9:30 | 10 Stop | Ctrl+C in the terminal; show `Embedded database stopped` / `Cloud Kitchen stopped`. | clean exit | If the next start fails: `pgrep -af mariadbd` (see README Troubleshooting). |

Optional (only if asked): the crash drill. Stop the Java process with `kill -9` while orders are in flight and start again: the start stops the database server that the killed process left behind (log line `Stopping database server ... that an application process left behind`), recovery re-queues the unfinished orders, and every accepted order reaches a final status (executed once with the earlier code, EV-065; the leftover-server clean-up executed with a real `kill -9`, EV-068).

## 5. Viva questions and answers

1. **Why an abstract `Order`?** The three sources share identity, items, totals and lifecycle but differ in delivery estimate and payment handling; `calculateDeliveryEstimate()` and `processPayment()` are abstract so each subclass must define them, and callers use an `Order` reference (runtime polymorphism).
2. **Where are the status rules?** Inside the `OrderStatus` enum (allowed targets per state, terminal states, whether stock may be restored). Services call `requireTransitionTo`; the database update is additionally conditional on the old status.
3. **How is overselling prevented?** The deduction is a single conditional `UPDATE` (`stock_qty >= ?`) inside the order-creation transaction; if any line affects zero rows the whole transaction rolls back. `StockDeductionConcurrencyTest` runs it concurrently (20 repetitions) and checks the ledger.
4. **Why sort item ids when deducting?** Two orders locking the same items in opposite order can deadlock; a fixed order removes that. Remaining deadlocks (SQLState 40001 / 1213) are retried up to 3 times by `Database.inTransaction`.
5. **What if the queue is full?** `dispatch` does `offer` with a timeout; on failure a compensating transaction restores stock and marks the order `FAILED`. After a successful `offer` the order is never compensated. Observed in the 5-minute load run: 23 of 285 orders were failed this way with exact stock.
6. **Why does the worker route the order, and what is a lease?** Ownership must survive thread death. A lease is a database row state (owner, expiry, epoch). Every worker mutation is a transaction whose first statement is a conditional update on epoch + owner + valid lease + status, so a superseded worker changes nothing (fencing).
7. **How does a crash get repaired?** At start-up `RecoveryService` resolves pending compensations, then re-queues non-terminal orders. While running, `WorkerMonitor` and `OrphanSweeper` find dead or stalled workers and orphaned orders. A real `kill -9` drill showed all accepted orders intact.
8. **What is `DEAD` and how is it left?** After the restart budget (5 in 300 s by default) the automatic path stops restarting a worker. An administrator can still use `operatorRestart`, which resets the budget (D-095). Tests: `OperatorRestartTest`, `WorkerPanelDeadWebTest`. It could not be triggered from the UI in the manual run.
9. **Why bounded queues?** Back-pressure and memory safety: a flood cannot grow memory; overload becomes visible as rejected/compensated orders instead of an outage.
10. **How is shutdown done?** `ShutdownCoordinator` runs eight steps once even if triggered by both the servlet listener and the JVM hook: stop accepting, stop the simulator and monitors, drain workers (default 20 s), `awaitTermination` (10 s), `shutdownNow`, final snapshot, close log, close database.
11. **Is the final snapshot guaranteed on Ctrl+C?** “An orderly stop writes the final snapshot before the database stops; a hard kill writes none.” MariaDB4j registers its own JVM hook that used to stop the database concurrently with ours (the premise of the former deviation DEV-2). `ApplicationStoppedDb` leaves that hook out, so the application stops the database as the last shutdown step. `LauncherSmokeTest` asserts the written snapshot after SIGTERM and failed 3 of 3 times with the old hook; DEV-2 was retired by the user on 2026-10-12 (D-101). Limits: the snapshot step is non-failing (a full disk is a WARN), a hard kill writes none and recovery uses the database and the last periodic snapshot, and this is verified on Linux only.
12. **What is serialization used for here and what is dangerous about it?** Snapshots of in-flight orders. Deserialization of untrusted bytes is an attack vector, so the file is HMAC-SHA256 signed and verified **before** `ObjectInputStream` is touched, then read through an allowlist `ObjectInputFilter`; web input is never deserialized. `SnapshotTamperTest` and `SnapshotFilterTest` cover this.
13. **Why PBKDF2 and how many iterations?** PBKDF2WithHmacSHA256, at least 600,000 iterations (configurable, start-up fails if lower), per-user salt, constant-time compare. Hash format `pbkdf2_sha256$iterations$salt$hash`.
14. **How are brute-force logins limited?** Five failed logins lock the account for five minutes (injected `Clock`, state in the database row, concurrent attempts cannot bypass the counter); all refusal kinds give one generic message.
15. **What stops session fixation?** The session id is changed at login (`changeSessionId`), logout invalidates the whole session, cookies are `HttpOnly` and `SameSite=Lax` (`Secure` by configuration; not exercised over HTTPS).
16. **How does CSRF protection work?** A per-session random token in a hidden input on every POST form (`<t:csrf/>`), checked by `CsrfFilter` before anything else; `JspFormRulesTest` proves every POST form carries it; missing/wrong/cross-session tokens return 403.
17. **What does a disabled user experience?** `AuthFilter` re-reads enabled, role and session version from the database on every protected request, fails closed, and invalidates the session of a disabled account at its next request.
18. **How is XSS prevented?** All output goes through JSTL `c:out`/escaping, no scriptlets (`JspRulesTest` fails the build otherwise), and `XssEscapingWebTest` posts markup and checks it is escaped; security headers include a restrictive CSP.
19. **Why the narrow `AppServices` interfaces?** So `web` cannot import `pipeline` or `bootstrap`; the layering rule is checked by `ArchitectureRulesTest`.
20. **Why plain JDBC and no ORM?** The project specification forbids JPA/Hibernate/Spring; plain JDBC also makes the transaction and locking behaviour (conditional updates, sorted locks, retry) explicit and teachable.
21. **Why `BigDecimal` and UTC?** Money must not use binary floating point (`DECIMAL(10,2)` in the schema, `ArchitectureRulesTest` forbids `double`/`float`); timestamps are stored in UTC so the server zone cannot change data (tested with a different JVM time zone).
22. **How do you test concurrency without flaky tests?** Deterministic hooks (latches, a `holdAt` hook, injected `Clock`) rather than sleeps, and every concurrency test is `@RepeatedTest(20)` that must pass 20/20; the 12 concurrency tests that were not repeated were re-run 20 times each in Phase 8 (EV-063).
23. **What did you find in the final audit?** Six findings (section 6): a race in the order page (fixed with a regression test), the throughput shortfall (default step times), the slow and variable database start (TLS key generation), the orphan database process after a hard kill, noisy shutdown warnings (and the best-effort snapshot behind them) and leaking test scratch directories. All six were fixed and verified on Linux; the fixes are committed (`1a35962`) and pushed and were accepted by the user on 2026-10-12 as decisions D-096..D-100. Stale documentation statements were corrected as well.
24. **Is the throughput requirement met?** Now yes, on the test machine: the first 5-minute run at 60 orders/minute failed 23 of 285 orders because each station completed only 7.5 orders/minute (3 s + 1 s + 4 s an order, one worker per station, locked). The default step times are now 1 s + 0.3 s + 0.7 s: 30 orders/minute per station, 90 in all. The second 5-minute run delivered 299 of 299 orders, none failed, queues at most 2 deep, dashboard at most 13 ms; the ledger was exact. The requirement was not weakened and queue-full failures are still reported, not hidden. It is one run on one machine (D-096 awaits your acceptance).
25. **Does it run on MySQL 8?** Unknown. The SQL is kept in the MySQL/MariaDB common subset and checked by `SqlPortabilityTest`, but only MariaDB was available; the claim is "intended", not "verified".
26. **Does it run on Windows/macOS or a standalone Tomcat?** Unverified; the WAR contents were inspected and the application ran inside embedded Tomcat only.
27. **What happens to a lost order if the JVM dies between commit and enqueue?** The order is committed in `VALIDATED`; the queue holds only ids. Recovery and the sweeper find committed orders that are in no queue and re-queue them (crash windows W1-W4 tested with a real restart in `RoutingCrashWindowTest`).
28. **Why POST-redirect-GET?** A refresh after a form submit must not repeat the action; every state-changing route answers 302 and shows a one-time flash message (`Flash`), checked in each `*WebTest`.
29. **What is logged and what is not?** `orders.log` has structured events (ids and codes); customer names, addresses, passwords, hashes, secrets and session ids are never logged; money appears only as ids/codes (`NioOrderEventLogTest`, secret scans).
30. **What would you do next?** Decide the throughput defect, fix the shutdown error logging and the orphan-database finding, verify MySQL 8 and a standalone Tomcat, then build the COULD features (order search, public tracking, password reset, charts) only after that.

## 6. Limitations, defects and trade-offs (state them yourself)

| Id | Severity | Finding | Status |
|---|---|---|---|
| P8-D3 | Medium | NFR-PERF-01 not met with the former default step times (7.5 orders/min per station). Defaults now 1000/300/700 ms (D-096). | **Fixed (committed in C2 `1a35962`)**; 5-minute run 299/299 delivered, 0 failed (EV-068); D-096 accepted by the user on 2026-10-12 |
| P8-D4 | Medium | Order page could answer 500 when an order advanced between the reads of its row and its history. | **Fixed** (one transaction); `OrderDetailConsistentReadTest` failed 20/20 before, passes 20/20 |
| P8-D5 | Medium | Embedded MariaDB 11.4 generated an RSA-4096 TLS key at each start (mean 7.7 s, up to 18 s; MariaDB4j gives up at 30 s). `--skip-ssl` rejected (the 11.4 client needs TLS). | **Fixed (committed in C2 `1a35962`)** (prepared EC certificate with key/certificate check, D-097): 0.30 s mean, 0.45 s max over 20 starts |
| P8-D2 | Medium | After `kill -9` the embedded `mariadbd` survived and blocked the next start. | **Fixed (committed in C2 `1a35962`)** (operating-system file lock, D-098); verified with a real `kill -9` |
| P8-D1 | Low | Shutdown logged HikariCP warnings and "final snapshot not written" because MariaDB4j's own hook stopped the database first. | **Fixed (committed in C2 `1a35962`)** (D-099); `LauncherSmokeTest` asserts it, fails 3/3 with the old hook |
| P8-D6 | Low | Tests left one scratch directory per application start in `data/test`. | **Fixed** for new runs (D-100); 6,423 old directories left in place, removal command in the README |
| DEV-2 | Retired deviation | Final snapshot on the JVM-hook path was best effort. | **Retired by the user on 2026-10-12 (D-101).** Locked wording: “An orderly stop writes the final snapshot before the database stops; a hard kill writes none.” The fix overrides a protected method of MariaDB4j 3.3.1; `LauncherSmokeTest` is the guard against an upgrade |
| - | Unverified | MySQL 8, standalone Tomcat, Windows, macOS, other browsers, HTTPS/`Secure` cookies, mobile, accessibility tools; the new database ownership and certificate code on Windows (`keytool.exe`, no POSIX permissions) | Stated in README and TRACEABILITY |
| - | Not built | Order search, public tracking, administrator password reset, charts (COULD tier). | Deferred |
| - | Trade-off | The DB-backed lease protocol is heavier than a plain in-memory queue; chosen so that crashes cannot lose or duplicate work. | By design |

## 7. What remains before a release

Phase 7 and Phase 8 are committed and pushed (C1 `1208332`, C2 `1a35962`, C3 `c6e0c59`, C4 `294d4e8`) but nothing is tagged. A fresh clone of the local branch (taken before the push) passed three consecutive offline `clean verify` builds of 2,471 tests each (EV-071); the README-following manual start-up was not repeated in that clone and first-time dependency downloads were not tested. A release needs the owner's explicit authorization for: creating tags (`phase-7-complete`, `phase-8-complete`, `review2-ready`, and the push of the earlier tags, which was refused with HTTP 403). The owner has accepted D-096..D-100 (new default step times, prepared TLS certificate, owner lock, application-stopped database, test scratch cleanup) and retired DEV-2 (D-101) on 2026-10-12. Not verified and therefore not claimed: MySQL 8, a standalone Tomcat, Windows, macOS, other browsers, HTTPS, mobile devices, accessibility tools; the 11-step browser demo and the 62 HTTP probes were repeated on the final tree and passed (EV-069), but only in headless Chromium on Linux.

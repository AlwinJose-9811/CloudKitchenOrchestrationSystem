# Testing Strategy

**Status:** Planned. No test exists yet. This document fixes test names, structure and rules so that phases can be verified objectively. Planned test classes: **74**.

Related: [SRS](SRS.md) (acceptance criteria) · [TRACEABILITY](TRACEABILITY.md) (requirement -> test) · [EVIDENCE](EVIDENCE.md) (real outputs) · [IMPLEMENTATION_ROADMAP](IMPLEMENTATION_ROADMAP.md) (gate commands).

---

## 1. Principles

1. **JUnit 5 only.** No Mockito or other mocking/assertion framework. Collaborators are replaced with hand-written fakes in `src/test` (for example a fixed `java.time.Clock`, a `RecordingOrderEventLog`, a `BlockingOrderDispatcher`).
2. **Real database for anything that touches SQL.** DAO, service-transaction, concurrency, recovery and web tests run against the embedded MariaDB4j server, never against mocks. This is the only way conditional updates, deadlock behaviour and constraints are really exercised.
3. **Deterministic concurrency tests.** Use `CountDownLatch`/`CyclicBarrier` to line threads up, `ExecutorService` with bounded pools, and `assertTimeoutPreemptively`/`@Timeout`. No `Thread.sleep` to "wait for something"; poll with a bounded `awaitCondition(timeout, supplier)` helper in `TestSupport`.
4. **Every concurrency test is `@RepeatedTest(20)`** and must pass 20 out of 20. A single failure is a defect, not a flake (see §11).
5. **Web tests run against embedded Tomcat on a random port** (`port 0`) using `java.net.http.HttpClient` with a cookie manager and `followRedirects(NEVER)` so that PRG redirects can be asserted.
6. **Tests never need real credentials.** `TestApp` builds an `AppConfig` programmatically (`AppConfig.fromProperties(Properties)`) with generated random values for the HMAC secret and test-user passwords; nothing is read from or written to `config/application.properties`.
7. **Evidence rule.** A test counts as passed only when its command output is recorded in [EVIDENCE.md](EVIDENCE.md). Claims without evidence are not allowed.

## 2. Test types

| Type | Purpose | Needs DB | Runs in | Typical speed |
|---|---|---|---|---|
| Unit | Pure logic: enum rules, validator, polymorphism, hashing, config, metrics | No | `mvn test` | milliseconds |
| DAO / service-transaction | SQL, constraints, transactions, TX1, compensation, lockout | Yes | `mvn test` | seconds |
| Concurrency | Races, queues, workers, shutdown | Yes | `mvn test` | seconds (x20) |
| Recovery | Restart, resumable processing, abrupt stop | Yes | `mvn test` | seconds |
| Serialization | Snapshot round trip, tamper, filter, recovery insert | Yes (recovery) | `mvn test` | seconds |
| Web | Servlets, filters, JSPs, security headers, PRG | Yes | `mvn test` | seconds |
| Integration | Whole system, launcher, throughput smoke | Yes | `mvn verify` | tens of seconds |
| Spike (Phase 1) | Prove the zero-install stack works; findings go to the decision log | Yes | `mvn test` (spike package) | seconds |

All automated tests are named `*Test` and run in Surefire (no Failsafe plugin; fewer moving parts for a single developer). Integration-class tests are tagged `@Tag("integration")` so they can be excluded during fast loops with `-DexcludedGroups=integration`.

## 3. Test infrastructure (planned shared code)

Located in `src/test/java/com/cloudkitchen/testsupport/`.

| Class | Responsibility |
|---|---|
| `TestDatabase` | Starts one embedded MariaDB4j per JVM (random port, directory under `target/test-db`), registers a shutdown hook, creates a fresh uniquely-named database per test class, applies `V001`/`V002` through `SchemaInitializer`, offers `resetData()` (delete rows, re-seed reference data) |
| `TestApp` | Builds a full `AppContext` + embedded Tomcat on port 0 with a programmatic `AppConfig`; `close()` calls `ShutdownCoordinator` and asserts threads ended |
| `HttpTestClient` | Wraps `HttpClient`: cookie jar, `get`, `postForm`, `loginAs(role)`, `extractCsrf(html)`, `assertRedirect(response, location)` |
| `TestData` | Builders: `anOrderRequest()`, `aSwiggyRequest()`, items, menu catalogue |
| `FakeClock` | Mutable `Clock` for lockout and timestamps |
| `TestSupport` | `awaitCondition`, `runConcurrently(n, task)`, `assertNoLeakedThreads(prefix)`, stock invariant checker `assertStockInvariant(...)` |

Surefire is configured with `forkCount=1`, `reuseForks=true`, JUnit parallel execution disabled; concurrency lives *inside* tests, not between them.

## 4. Unit tests (Phase 2 mostly)

Focus areas and the boundary values that must be present:

* `OrderStatusTest`: loop over all 9 x 9 pairs and compare with an independently written expected table; terminal states; `isStockRestorableTo(...)` true only for VALIDATED/ROUTED -> CANCELLED/FAILED.
* `OrderPolymorphismTest`: same items+distance on the three subclasses give the documented different estimates and fees; calls made through the `Order` reference; `Trackable`/`Payable` assignability.
* `OrderValidatorTest`: one accepted and one rejected case per rule VR-01..VR-14; boundaries: quantity 0/1/20/21, 0 and 21 lines, distance -0.01/0.00/25.00/25.01/scale 3, name length 0/1/100/101, externalRef length 0/1/64/65 and illegal characters, total exactly 50000.00 and 50000.01; multiple errors reported together; null request.
* `PasswordHasherTest`, `LockoutPolicyTest`, `AppConfigTest`, `MoneyUtilTest`, `RoutingServiceTest`, `InFlightRegistryTest`, `MetricsRegistryTest`: see catalogue.
* `ArchitectureRulesTest`: scans `src/main/java` text for forbidden imports/patterns (layering, `double` money, `ObjectInputStream` location, `createStatement(`, `Connection` fields, scriptlets in JSPs once they exist).
* `SqlPortabilityTest` (Phase 3): scans `src/main/resources/db/*.sql` for forbidden tokens.

## 5. DAO and service-transaction tests (Phase 3)

* Each DAO method has: success case, not-found/empty case, constraint-violation case where applicable.
* Money: store `0.01`, `19.99`, `99999999.99`; read back `BigDecimal` with `compareTo == 0` and scale 2.
* Time: store `Instant` values around midnight and DST-sensitive local dates; read back identical `Instant`; default JVM time zone is deliberately set to `Asia/Kolkata` in the test JVM (`-Duser.timezone`) to prove UTC handling does not depend on it.
* utf8mb4: names containing Hindi text and an emoji round-trip.
* `OrderServiceTest`: TX1 happy path; insufficient stock on the second of two items leaves the first unchanged; duplicate; sorted-deduction; restore matrix over (status x cancel/fail) with exactly-once restore; compensation without a queue.
* `DatabaseTransactionTest`: commit, rollback on checked and unchecked exceptions, connection returned to pool (pool `activeConnections == 0` afterwards).
* `DeadlockRetryTest`: two transactions lock two rows in opposite order (with latches) to force SQLState 40001; first is retried and succeeds; a fake always-deadlock `TxWork` fails after exactly 3 retries.

## 6. Concurrency tests (Phase 4 and 5)

All use `@RepeatedTest(20)`, `@Timeout`, thread names asserted, and finish with `assertNoLeakedThreads("station-")`.

| Test | Scenario | Assertions |
|---|---|---|
| `StockDeductionConcurrencyTest` | Stock = 10; 40 threads each submit an order for 1 unit of the same item, started together with a `CyclicBarrier` | Exactly 10 ACCEPTED, 30 REJECTED(INSUFFICIENT_STOCK); stock = 0, never negative; stock invariant |
| `DuplicateSubmissionConcurrencyTest` | 16 threads submit the identical `(source, externalRef)` | Exactly one order row; one ACCEPTED, others DUPLICATE; one deduction |
| `ConditionalTransitionRaceTest` | Two threads call the same transition on one order | Exactly one `true`; exactly one history row for that transition |
| `CancelVsWorkerRaceTest` | Cancel and worker claim race on a ROUTED order | Final status is CANCELLED (stock restored once) **or** PREPARING+ (stock kept); never both; invariant holds |
| `QueueFullCompensationTest` | Capacity 2, worker paused with a latch, submit 5 | 2 ACCEPTED (queued), later ones `QUEUE_FULL` after the offer timeout; those orders FAILED; stock restored for them; `failed` counter equals the count |
| `WorkerResilienceTest` | One order whose processing throws (injected failing step) between healthy orders | Poisoned order FAILED; neighbours DELIVERED; worker thread still alive |
| `WorkerInterruptTest` | Interrupt while blocked on the queue and while sleeping in simulated preparation | Thread ends within 2 s; interrupt status observed set before exit |
| `FeedSimulatorTest` | start/stop/setRate, double start, stop while submitting | No orders after `stop()` returns plus a drain period; rates observed within tolerance using a fake dispatcher |
| `MetricsInvariantTest` | Mixed run of accepted, rejected, cancelled, failed | `submitted == rejected + delivered + failed + cancelled + inFlight` (cancelled and inFlight counted from DB) |
| `OrderEventLogConcurrencyTest` | 16 threads x 500 events | Exactly 8,000 lines, each parseable, none interleaved |
| `SnapshotTaskTest` | Short interval, one run throws | Later runs still happen; stops cleanly |

Rules: a concurrency test that fails once in 20 is investigated as a real race (see §11), never retried away.

## 7. Recovery tests (Phase 5, 8)

* `RecoveryServiceTest`: insert orders in each non-terminal status (+ some terminal), start `AppContext`, assert each non-terminal one is enqueued exactly once and reaches DELIVERED; terminal ones untouched.
* `ResumableProcessingTest`: orders pre-set to PREPARING, PACKED, OUT_FOR_DELIVERY continue and produce only the remaining history rows.
* Recovery with more orders than queue capacity: all eventually processed, startup completes.
* `ShutdownCoordinatorTest` / `GracefulShutdownTest`: double trigger, ordering of steps recorded by a fake step list, timeout path with a stuck worker, final snapshot present.
* `CrashRecoveryTest` (Phase 8): stop executors abruptly (no drain, no snapshot), start a new `AppContext` on the same database, verify no accepted order lost or stuck. A real `kill -9` run is performed manually once and recorded in EVIDENCE.md.

## 8. Serialization tests (Phase 6)

* Round trip equality, file naming, retention (keep last N).
* **Tamper:** flip one byte in payload; flip one byte in signature; truncate; empty file; wrong secret. Each must raise `SnapshotIntegrityException` **before** any object is deserialized (a counting `ObjectInputFilter` or a spy-free check: the filter is never invoked).
* **Filter:** craft a signed stream (test signs with the test secret) containing an unlisted class (for example `java.util.HashMap` or a test-only class with a malicious `readObject`) and assert rejection plus that the malicious `readObject` did not run. Depth/array/bytes limits tested.
* **Recovery insert:** snapshot with orders present/absent in DB; unknown station id is skipped and logged; `stock_deducted = 0` for inserted orders.

## 9. Web tests (Phase 7)

Run through `HttpTestClient` against embedded Tomcat on a random port.

* **Authentication:** login success/failure, session id differs before/after login, generic failure message, lockout after 5 and after-5-minute recovery (the clock is injectable into `AuthService` through `AppContext` for tests), logout invalidates.
* **Authorisation:** matrix test iterates `RoutePolicy.allRoutes()` for anonymous / KITCHEN_STAFF / ADMIN and asserts redirect-to-login / 403 / 200 as specified.
* **CSRF:** every POST route without token, with a token of another session, and with a valid token; token present as hidden input in every rendered form.
* **PRG:** POST returns 302; following the redirect shows flash exactly once; re-GET shows none.
* **Error pages:** force 400 (bad parameter), 403 (CSRF), 404 (unknown id/route), 500 (test-only failing route registered by `TestApp`); bodies contain no `Exception`, `at com.`, `SQL`, `java.`.
* **Encoding:** Hindi + emoji names round-trip; response `Content-Type` has `charset=UTF-8`.
* **XSS:** `<script>` payload in customer name and menu name is escaped on list, detail, dashboard.
* **Headers/cookies:** security headers on a sample of routes; `Set-Cookie` attributes; Secure flag when configured.
* **Dashboard:** auto-refresh element, low-stock flag appears when stock <= reorder level, queue size/capacity and worker states match backend values, metrics block present.
* **JSP rule:** a unit-level scan asserts no scriptlets and no direct `javax`/`java.sql` usage in JSPs.

## 10. Edge-case catalogue (must each be covered somewhere)

| # | Edge case | Covered by |
|---|---|---|
| E-01 | Same `externalRef` from different sources is allowed; same source+ref is not | `OrderServiceTest` |
| E-02 | `externalRef` differing only in letter case treated as different (binary collation) | `OrderDaoTest` |
| E-03 | Quantity and line-count boundaries | `OrderValidatorTest` |
| E-04 | Order where one item inactive / other brand | `OrderValidatorTest` |
| E-05 | Insufficient stock on last line only | `OrderServiceTest` |
| E-06 | Exactly-zero remaining stock after deduction (allowed) | `StockDaoTest` |
| E-07 | Cancel on each of the 9 statuses | `OrderServiceTest`, `OrderStatusTest` |
| E-08 | Fail from PREPARING (no restore) vs from ROUTED (restore) | `OrderServiceTest` |
| E-09 | Double restore attempt | `OrderServiceTest`, `CancelVsWorkerRaceTest` |
| E-10 | Queue full at submit; queue full during recovery | `QueueFullCompensationTest`, `RecoveryServiceTest` |
| E-11 | Worker dequeues before ROUTED update visible | `StationWorkerTest` |
| E-12 | Same order id enqueued twice | `StationWorkerTest` |
| E-13 | Interrupt during `take`, during simulated work, during DB call | `WorkerInterruptTest` |
| E-14 | Shutdown during submission burst; submission after shutdown begins | `GracefulShutdownTest` |
| E-15 | Shutdown triggered twice | `ShutdownCoordinatorTest` |
| E-16 | Snapshot with future `serialVersionUID`/unknown class | `SnapshotFilterTest` |
| E-17 | Snapshot directory missing or read-only | `SnapshotRoundTripTest` |
| E-18 | Report for empty day; external reference starting with `=`; no customer data in CSV | `CsvReportExporterTest` |
| E-19 | Login with unknown user vs wrong password: same body, similar timing | `AuthServiceTest`, `LockoutWebTest` |
| E-20 | Lock expiry boundary (exactly at `locked_until`) | `LockoutPolicyTest` |
| E-21 | Unicode normalisation in names (precomposed vs combining) stored as given | `EncodingWebTest` |
| E-22 | Very long form field (10,000 chars) -> 400, not 500 | `OrderPrgWebTest` |
| E-23 | Session expired during form fill -> redirect to login, no data loss crash | `SessionSecurityWebTest` |
| E-24 | Missing config key / too-low PBKDF2 iterations | `AppConfigTest` |
| E-25 | Database unavailable at runtime -> generic 500, app stays up | `ErrorPagesWebTest` |

## 11. Definition of done

**Per requirement:** code merged on the phase branch; test(s) from [TRACEABILITY](TRACEABILITY.md) exist and pass; no skipped/disabled tests; TRACEABILITY status updated; EVIDENCE entry added; viva note written.

**Per phase (exit gate):**
1. All gate commands in the roadmap's phase section executed; their *real* output pasted into EVIDENCE.md with date.
2. `./mvnw -q clean verify` green, run **three times** in a row for phases 4 onward.
3. Concurrency tests 20/20 for the whole phase set.
4. No `@Disabled`, no `Thread.sleep`-based synchronisation, no commented-out tests.
5. Secret scan clean (command in roadmap).
6. TRACEABILITY, JAVA_CONCEPT_MAPPING viva notes and EVIDENCE updated; any doc/code conflict reported.
7. Git tag `phase-<n>-complete` created.

**Per project:** SRS §5.1.

**Flake policy:** a test that fails intermittently is treated as a defect in either the test or the code. It is diagnosed (log the interleaving, add a latch, fix the cause); it is never `@Disabled`, retried automatically or "fixed" by increasing a sleep.

## 12. Commands (final forms confirmed in Phase 1)

| Purpose | Command |
|---|---|
| All tests | `./mvnw -q clean verify` (Windows: `mvnw.cmd -q clean verify`) |
| One class | `./mvnw -q test -Dtest=OrderStatusTest` |
| One phase's concurrency set | `./mvnw -q test -Dtest='*ConcurrencyTest,*RaceTest'` |
| Skip integration | `./mvnw -q test -DexcludedGroups=integration` |
| Build WAR | `./mvnw -q package` then `jar tf target/*.war` |

## 13. Planned test catalogue

| Test class | Package (under `src/test/java/com/cloudkitchen/`) | Phase | Type | What it proves |
|---|---|---|---|---|
| `DbSpikeTest` | `spike` | 1 | Spike | Driver compatibility: connect, utf8mb4 round trip, DECIMAL/BigDecimal, DATETIME(3) UTC, update-count semantics, generated keys, duplicate-key and deadlock error codes, CHECK enforcement |
| `EmbeddedTomcatSpikeTest` | `spike` | 1 | Spike | Embedded Tomcat 10.1 on a random port serves a JSP using JSTL and a servlet via java.net.http.HttpClient |
| `AppConfigTest` | `config` | 2 | Unit | Defaults, required keys, minimum PBKDF2 iterations, secret redaction in toString() |
| `ArchitectureRulesTest` | `architecture` | 2 | Unit | Source-scan layering rules: no servlet imports outside web.*, no java.sql outside dao.jdbc/db/bootstrap/snapshot recovery, no double/float for money |
| `InFlightRegistryTest` | `pipeline` | 2 | Unit | tryAcquire/release semantics, key normalisation, multithreaded single-winner |
| `LockoutPolicyTest` | `service` | 2 | Unit | 5th failure locks 5 minutes, lock expiry, reset on success (injected Clock) |
| `MetricsRegistryTest` | `pipeline` | 2 | Unit | Counters, average processing time with zero samples, snapshot view immutability |
| `MoneyUtilTest` | `util` | 2 | Unit | Scale 2, HALF_UP rounding, no double paths, overloads |
| `OrderFactoryTest` | `domain` | 2 | Unit | OrderFactory.create returns the right subclass per OrderSource |
| `OrderPolymorphismTest` | `domain` | 2 | Unit | calculateDeliveryEstimate()/processPayment() differ per subclass; Order references dispatch dynamically; equals/hashCode on (source, externalRef) |
| `OrderStatusTest` | `domain/enums` | 2 | Unit | All 9x9 transition pairs against the allowed table; terminal states; stock-restorable rule |
| `OrderValidatorTest` | `service` | 2 | Unit | Every validation rule VR-01..VR-14 accepted/rejected cases incl. boundaries, overloads |
| `PasswordHasherTest` | `service` | 2 | Unit | Hash format, salt uniqueness, verify true/false, constant-time compare helper, minimum iterations enforced |
| `RoutingServiceTest` | `service` | 2 | Unit | Brand to station mapping, unknown/inactive brand rejected |
| `AuthServiceTest` | `service` | 3 | DAO | Login success, wrong password, unknown user (uniform failure), lockout after 5, unlock after 5 minutes, disabled user |
| `DatabaseTransactionTest` | `db` | 3 | DAO | Commit, rollback on exception, connection always returned, no nested connection sharing |
| `DeadlockRetryTest` | `db` | 3 | DAO | Forced deadlock (two crossing transactions) retried up to 3 times then surfaced as PersistenceException |
| `MenuItemDaoTest` | `dao/jdbc` | 3 | DAO | Catalogue load per station, price update, activate/deactivate |
| `OrderDaoTest` | `dao/jdbc` | 3 | DAO | Insert, UNIQUE(source, external_ref), conditional transition row counts, find non-terminal, count by status, BigDecimal/UTC round trip, utf8mb4 round trip |
| `OrderServiceTest` | `service` | 3 | DAO | TX1 happy path, insufficient stock rollback, duplicate, sorted deduction, cancel/fail restore rules, restore exactly once, compensation without queue |
| `SchemaInitializerTest` | `bootstrap` | 3 | DAO | Applies V001/V002 once, idempotent re-run, schema_version row |
| `SqlPortabilityTest` | `db` | 3 | Unit | Scans db/*.sql for forbidden non-portable tokens (ENUM, JSON, triggers, NOW(), RETURNING, SKIP LOCKED, utf8mb4_0900*) |
| `StaffUserDaoTest` | `dao/jdbc` | 3 | DAO | Create, find by username, lockout columns, SELECT FOR UPDATE flow |
| `StatusHistoryDaoTest` | `dao/jdbc` | 3 | DAO | Ordered history per order, actor/note stored |
| `StockDaoTest` | `dao/jdbc` | 3 | DAO | Conditional deduction succeeds/fails on boundary, restore, low-stock query, never negative |
| `UserSeederTest` | `bootstrap` | 3 | DAO | Seeds only when staff_users empty; passwords from config, stored hashed; never logged |
| `CancelVsWorkerRaceTest` | `pipeline` | 4 | Concurrency | Cancel racing a worker: consistent final state, stock restored at most once, stock rule respected (@RepeatedTest(20)) |
| `ConditionalTransitionRaceTest` | `pipeline` | 4 | Concurrency | Two threads attempt same transition: exactly one wins, one history row (@RepeatedTest(20)) |
| `DuplicateSubmissionConcurrencyTest` | `pipeline` | 4 | Concurrency | Concurrent identical (source, external_ref): exactly one order, one deduction (@RepeatedTest(20)) |
| `OrderOrchestratorTest` | `pipeline` | 4 | Concurrency | One queue and worker per station, thread names station-<brand>-worker, routing to correct station, ROUTED set after enqueue |
| `QueueFullCompensationTest` | `pipeline` | 4 | Concurrency | Full queue: offer times out, stock restored, order FAILED, caller told, metrics updated |
| `StationWorkerTest` | `pipeline` | 4 | Concurrency | Full lifecycle ROUTED to DELIVERED with history; claim retry when status still VALIDATED |
| `StockDeductionConcurrencyTest` | `pipeline` | 4 | Concurrency | N threads race for limited stock: no oversell, stock never negative, sum invariant (@RepeatedTest(20)) |
| `WorkerInterruptTest` | `pipeline` | 4 | Concurrency | Interrupt flag restored, worker exits cleanly, no leaked thread |
| `WorkerResilienceTest` | `pipeline` | 4 | Concurrency | A poisoned order (DAO failure) marks that order FAILED and the worker keeps processing the next one |
| `FeedSimulatorTest` | `pipeline` | 5 | Concurrency | Start/stop/rate change, idempotent start, produces unique external refs, stops on shutdown |
| `GracefulShutdownTest` | `pipeline` | 5 | Recovery | In-flight orders drained or persisted, no new submissions accepted, threads terminated |
| `MetricsInvariantTest` | `pipeline` | 5 | Concurrency | submitted = rejected + delivered + failed + cancelled + in-flight at quiescence |
| `RecoveryServiceTest` | `pipeline` | 5 | Recovery | Non-terminal orders re-enqueued after restart; terminal ones ignored; more orders than queue capacity do not block startup forever |
| `ResumableProcessingTest` | `pipeline` | 5 | Recovery | Order recovered in PREPARING/PACKED/OUT_FOR_DELIVERY resumes from current status |
| `ShutdownCoordinatorTest` | `pipeline` | 5 | Recovery | Idempotent, ordered steps, double trigger (listener + hook) runs once, timeout then shutdownNow |
| `CsvReportExporterTest` | `io` | 6 | Unit | RFC 4180 quoting, UTF-8, header, CSV-injection neutralisation of text cells (e.g. external reference starting with =), empty day, no customer data in file |
| `HmacSignerTest` | `snapshot` | 6 | Unit | Known-answer test, tamper detection, constant-time verify, wrong key |
| `NioOrderEventLogTest` | `io` | 6 | Unit | Append-only structured lines, UTF-8, file created from config path, close idempotent |
| `NoPiiInLogsTest` | `io` | 6 | Unit | Customer name, address, passwords, secrets never appear in orders.log or app log output |
| `OrderEventLogConcurrencyTest` | `io` | 6 | Concurrency | Many threads, no interleaved/lost lines (@RepeatedTest(20)) |
| `SnapshotFilterTest` | `snapshot` | 6 | Serialization | Disallowed class in stream rejected by ObjectInputFilter; depth/size limits |
| `SnapshotRecoveryTest` | `snapshot` | 6 | Serialization | Only orders missing from DB inserted; present ones untouched; stock not re-deducted; rejected unknown station |
| `SnapshotRoundTripTest` | `snapshot` | 6 | Serialization | Write then read returns equal DTOs; file layout; retention of last N |
| `SnapshotTamperTest` | `snapshot` | 6 | Serialization | Modified payload or HMAC rejected before any deserialization (SnapshotIntegrityException) |
| `SnapshotTaskTest` | `snapshot` | 6 | Concurrency | Scheduled task runs periodically, survives a failed run, stops on shutdown |
| `AdminMenuStockWebTest` | `web` | 7 | Web | Admin menu and stock management flows |
| `CsrfWebTest` | `web` | 7 | Web | POST without/with wrong token gets 403; token present in every form |
| `DashboardWebTest` | `web` | 7 | Web | Auto-refresh 5s, low-stock flags, queue size/capacity, worker states, metrics rendered |
| `EncodingWebTest` | `web` | 7 | Web | Non-ASCII (e.g. Hindi, emoji) names survive request and response |
| `ErrorPagesWebTest` | `web` | 7 | Web | 400/403/404/500 generic bodies, no stack trace or class names |
| `LockoutWebTest` | `web` | 7 | Web | 5 failed logins lock account; correct password refused during lock; generic message |
| `LoginFlowWebTest` | `web` | 7 | Web | Login success/failure, session id changes at login, redirect, unauthenticated access redirected |
| `OrderDetailWebTest` | `web` | 7 | Web | Order page lists status history in order; cancel button rules |
| `OrderPrgWebTest` | `web` | 7 | Web | POST new order returns 302 then GET shows flash once; refresh does not resubmit |
| `ReportWebTest` | `web` | 7 | Web | CSV download headers and content; admin only |
| `RoleAccessWebTest` | `web` | 7 | Web | Route-by-role matrix: ADMIN vs KITCHEN_STAFF vs anonymous for every protected route |
| `SecurityHeadersWebTest` | `web` | 7 | Web | nosniff, frame deny, CSP present on all responses |
| `SessionSecurityWebTest` | `web` | 7 | Web | HttpOnly, SameSite=Lax, Secure by config, 30-minute timeout config, logout invalidates |
| `SimulatorWebTest` | `web` | 7 | Web | Admin start/stop/rate via UI; kitchen staff forbidden |
| `WorkerPanelWebTest` | `web` | 7 | Web | Worker panel states and pause/resume (admin) |
| `XssEscapingWebTest` | `web` | 7 | Web | Script in customer name and menu name rendered escaped |
| `AdminPasswordResetWebTest` | `web` | 8 | Web | COULD: admin resets staff password; old password rejected |
| `CrashRecoveryTest` | `integration` | 8 | Recovery | Abrupt stop (no drain, no final snapshot), restart on same DB, all non-terminal orders complete |
| `EndToEndLifecycleTest` | `integration` | 8 | Integration | Feed to DELIVERED across all three sources and stations with DB, files and metrics consistent |
| `LauncherSmokeTest` | `integration` | 8 | Integration | Launcher boots embedded DB + Tomcat + pipeline, serves /login, shuts down cleanly via hook path |
| `OrderSearchWebTest` | `web` | 8 | Web | COULD: search by ref/status/source |
| `PublicTrackingWebTest` | `web` | 8 | Web | COULD: tracking page by code shows status only, no PII |
| `ThroughputSmokeTest` | `integration` | 8 | Integration | Sustained simulated load completes; no FAILED from queue-full at default settings; bounded memory |

Test-support classes (`TestDatabase`, `TestApp`, `HttpTestClient`, `TestData`, `FakeClock`, `TestSupport`) are listed in §3.

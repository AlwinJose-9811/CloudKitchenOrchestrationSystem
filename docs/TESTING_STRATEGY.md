# Testing Strategy

**Status:** Planned. No test exists yet. This document fixes test names, structure and rules so that phases can be verified objectively. Planned test classes: **107**.

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
| `TestSupport` | `awaitCondition`, `runConcurrently(n, task)`, `assertNoLeakedThreads(prefix)`, stock invariant checker `assertStockInvariant(...)`, history checker `assertEachEdgeOnce(orderId)` (no repeated `(from,to)`), ledger checker `assertStockLedger(...)` (deducted/restored exactly once per order, using `stock_deducted` and item quantities) |
| `GatedOrderDispatcher` | Wraps the real orchestrator; latches let a test hold the submitting thread between a successful `offer` and the `ROUTED` update (routing-race tests) |
| `AlwaysFullOrderDispatcher`, `BlockingOrderDispatcher` | Fake `OrderDispatcher`s for compensation and service tests |
| `FaultInjectingOrderDao`, `FaultInjectingStockDao`, `FaultInjectingStatusHistoryDao` | Created in Phase 3 (needed by the atomicity tests, reused in Phase 4). Delegate to the real DAO but throw a configurable transient (`SQLTransientException`-based) or permanent exception for the N-th call of a chosen method; a `FaultPoint` enum names the step (for example `AFTER_STATUS_UPDATE`, `AFTER_HISTORY_INSERT`, `AFTER_CLAIM_RESTORE`) |
| `WorkerHook` implementations | Throw an `Error` or block on a latch at `AFTER_POLL`, `AFTER_LEASE`, `BEFORE_RENEW`, `BEFORE_STEP`, `AFTER_STEP`, `BEFORE_PARK_OFFER` (each with order id and step) to kill or hang a worker at an exact point; `FakeClock.advance(...)` moves lease time; tests call `WorkerMonitor.runOnce()` and `OrphanSweeper.runOnce()` directly, never `Thread.sleep` |
| `LeaseStampHook` implementations | Stall or run a callback at `AFTER_STAMP` (clock read, statement not yet executed), `AFTER_EXECUTE` and `BEFORE_COMMIT` inside `JdbcOrderDao`/`Database`; the callback advances the `FakeClock` or blocks on a latch, so the stale-stamp windows of SDD §7.11.15 are reproduced without sleeping |
| `FaultInjectingOrderDao` fault points | also `AFTER_FENCED_UPDATE`, `AFTER_HISTORY_INSERT`, `AFTER_CLAIM_RESTORE`; a recording DAO wrapper counts the statements per transaction so tests can prove the first-statement rule: a 0-row first statement is followed by no other statement |
| `CrashingSubmissionHook` | `SubmissionHook` that throws a simulated crash (`Error` subclass) at `AFTER_TX1`, `AFTER_OFFER` or `AFTER_ROUTED`; the test then drops all in-memory state and starts a new `AppContext` on the same database |

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
* `TransactionAtomicityTest`: for each workflow in [SDD §13.1](SDD.md) inject a fault between every pair of consecutive steps (using the fault-injecting DAOs): TX1 (after order insert, after items, after the 2nd deduction, after `markStockDeducted`), transition (after the status update, after the history insert), restore (after `claimStockRestore`, after the first inventory restore), account disable (after the update). Assert **everything** rolled back (status, history rows, `stock_deducted`, stock quantities, `session_version`) and that repeating the call without the fault succeeds exactly once.
* `PostCommitSideEffectsTest`: failing commit and 0-row conditional updates emit no metric increment and no event line; a successful call emits exactly one of each.
* `AccountDisableServiceTest`: `setEnabled` increments `session_version`; self-disable and last-enabled-ADMIN disable refused; `validateSession` rejects disabled, unknown and stale-version sessions and returns the current role; disabled users cannot authenticate; re-enabling does not revive old sessions.
* `DeadlockRetryTest`: two transactions lock two rows in opposite order (with latches) to force SQLState 40001; first is retried and succeeds; a fake always-deadlock `TxWork` fails after exactly 3 retries.

## 6. Concurrency tests (Phase 4 and 5)

All use `@RepeatedTest(20)`, `@Timeout`, thread names asserted, and finish with `assertNoLeakedThreads("station-")`.

| Test | Scenario | Assertions |
|---|---|---|
| `StockDeductionConcurrencyTest` | Stock = 10; 40 threads each submit an order for 1 unit of the same item, started together with a `CyclicBarrier` | Exactly 10 ACCEPTED, 30 REJECTED(INSUFFICIENT_STOCK); stock = 0, never negative; stock invariant |
| `DuplicateSubmissionConcurrencyTest` | 16 threads submit the identical `(source, externalRef)` | Exactly one order row; one ACCEPTED, others DUPLICATE; one deduction |
| `ConditionalTransitionRaceTest` | Two threads call the same transition on one order | Exactly one `true`; exactly one history row for that transition |
| `CancelVsWorkerRaceTest` | Cancel and worker claim race on a ROUTED order | Final status is CANCELLED (stock restored once) **or** PREPARING+ (stock kept); never both; invariant holds |
| `QueueFullCompensationTest` | Capacity 2, worker paused with a latch, submit 5; plus variants: interrupt before insert, interrupt after insert, station unavailable, forced compensation failure | 2 ACCEPTED (queued), later ones `QUEUE_FULL` after the offer timeout; those orders FAILED; stock restored for them; `failed` counter equals the count; interrupt after insert stays ACCEPTED; forced failure leaves VALIDATED, deducted, `compensated=false` |
| `RoutingRaceTest` | `GatedOrderDispatcher` holds the submitter after `offer`; the worker takes the id while the DB says VALIDATED; variants: submitter's ROUTED update fails/throws | Order DELIVERED, exactly one `VALIDATED->ROUTED` row, result ACCEPTED, no compensation, stock deducted once, `assertEachEdgeOnce` |
| `CompensationRaceTest` | Cancel vs compensation vs ROUTED update started from a barrier | CANCELLED xor FAILED, exactly one terminal edge, `assertStockLedger` passes (restored once), caller result consistent, `failed++` only when compensation won |
| `DuplicateQueueEntryTest` | One order id offered 3 times to the queue; variant with a second `StationWorker` on the same queue | One chain, each edge once, `processed` +1, stock unchanged |
| `TerminalOrderNeverResumedTest` | Enqueue ids of DELIVERED, CANCELLED, FAILED orders | No new history rows, stock and metrics unchanged, `ORDER_SKIPPED_TERMINAL` logged |
| `TransientFailureRecoveryTest` | `FaultInjectingOrderDao` fails transiently (1, `worker.retry.max`, `worker.retry.max + 3` times) at claim, `PREPARING->PACKED`, `OUT_FOR_DELIVERY->DELIVERED`, `failOrder`; permanent failure variant | Transient: order eventually DELIVERED, never FAILED, id never lost, parking observed after the limit, worker alive, `consecutiveFailures` resets; permanent: FAILED with stock rule |
| `WorkerResilienceTest` | One order whose processing throws (injected failing step) between healthy orders | Poisoned order FAILED; neighbours DELIVERED; worker thread still alive |
| `WorkerInterruptTest` | Interrupt while blocked on the queue and while sleeping in simulated preparation | Thread ends within 2 s; interrupt status observed set before exit |
| `FeedSimulatorTest` | start/stop/setRate, double start, stop while submitting | No orders after `stop()` returns plus a drain period; rates observed within tolerance using a fake dispatcher |
| `MetricsInvariantTest` | Mixed run of accepted, rejected, cancelled, failed | `submitted == rejected + delivered + failed + cancelled + inFlight` (cancelled and inFlight counted from DB) |
| `OrderEventLogConcurrencyTest` | 16 threads x 500 events | Exactly 8,000 lines, each parseable, none interleaved |
| `SnapshotTaskTest` | Short interval, one run throws | Later runs still happen; stops cleanly |
| `DurableCompensationTest` | Fault points between the steps of TX4; TX4 fails permanently; marker write fails too | Rollback as a unit; marker committed when TX4 fails; INDETERMINATE when neither commits; answer only after a durable outcome; stock ledger intact |
| `PendingCompensationRaceTest` | Pending order raced by cancel, sweeper completion, a worker trying to lease/route it (DB guards) and duplicate submissions | One terminal edge, stock restored once, never leased, routed or processed; DUPLICATE does not revive it |
| `LeaseFencingTest` | Acquire/renew/release/expire; two acquirers; stale epoch update | Exclusive while unexpired; epoch increments; stale-epoch update changes 0 rows; pending/terminal orders cannot be leased |
| `LeaseRenewalFreshStampTest` / `LeaseStampGuardTest` | Race windows RW-17, RW-18 | See §6.1: renewal after expiry fails, never revives; late statements roll back |
| `LostReplyIdempotenceTest` / `ReconcileLeaseSafetyTest` / `MonitorDeathSweeperRecoveryTest` | Race windows RW-12..RW-16 | See §6.1 |
| `WorkerMonitorTest` | `WorkerHook` kills a worker; restart budget exhausted; shutdown in progress; admin restart | Replacement generation on the same queue, ids reclaimed, DEAD after budget, no restart during shutdown |
| `WorkerHangRecoveryTest` | Worker blocked while leased; clock advanced past stall and lease expiry; zombie released | Replaced; order delivered exactly once; zombie fenced; each edge once |
| `DeadWorkerRaceTest` | Worker dies at random fault points with concurrent cancels, submissions and sweeper passes | No loss, no duplicate edge/effect, stock ledger intact, terminal never resumed |
| `OrphanSweeperTest` / `OrphanSweeperRaceTest` | Orders planted in the DB with old `updated_at` and no queue entry; fresh ones; queued, active (blocked worker), paused and parked ones; terminal ones; sweeps run in parallel with live submissions, cancels and compensations | Only genuine orphans are enqueued and delivered; others untouched; no duplicate edge, no double restore, stock ledger intact; race variant @RepeatedTest(20) |
| `CompensationOperatorTest` / `CompensationOperatorWebTest` | ADMIN retry; double click; concurrent sweeper; forced failure; KITCHEN_STAFF attempt; cancel on a PENDING order | One history row with admin actor and `COMPENSATION_MANUAL`; restore once; rollback on failure; 403 for staff; cancel refused; `ArchitectureRulesTest` forbids any other path |
| `WorkerMutationFencingTest` / `StaleWorkerWakeTest` / `LeaseExpiryReacquireTest` / `PollToLeaseCrashWindowTest` / `QueueReconcileTest` | Race windows RW-1..RW-8 above | See §6.1 |
| `CompensationPendingSweepTest` | Compensation forced to fail then healed | Sweeper never enqueues the order, completes the compensation once, marker cleared; cancel-wins variant; restart variant |

Rules: a concurrency test that fails once in 20 is investigated as a real race (see §11), never retried away.

### 6.1 Deterministic race-window catalogue (no sleeps; latches, `WorkerHook`, `FakeClock`, direct `runOnce()` calls)

| ID | Exact race window | Mechanism | Test | Expected |
|---|---|---|---|---|
| RW-1 | Stale worker wakes after its lease expired **and** another worker superseded it | A blocked at `BEFORE_STEP`; `FakeClock` +70 s; monitor pass; B acquires epoch+1 and finishes a step; A released | `StaleWorkerWakeTest` (@RepeatedTest(20)) | A's step, renew, `failAsOwner`, release: 0 rows in `orders`, `order_status_history`, `inventory`; no metric/log; A stands down, no re-offer; each edge once |
| RW-2 | Lease expired, nobody superseded | `FakeClock` +31 s while A is blocked at `BEFORE_RENEW`; A released | `LeaseExpiryReacquireTest` | Renew/step 0 rows; non-retired A re-acquires (epoch+1) once and delivers; retired A does not re-acquire and the id is re-registered; `worker.reacquire.max` respected |
| RW-3 | Crash between queue poll and lease acquisition | Hook throws `Error` at `AFTER_POLL` | `PollToLeaseCrashWindowTest` | One `WorkerMonitor.runOnce()` restarts the worker and re-offers the order from durable state; clock advanced by less than the orphan threshold; delivered once |
| RW-4 | Crash after the lease commit, before use | Hook at `AFTER_LEASE` | `PollToLeaseCrashWindowTest` | Monitor expires the retired owner's lease and re-offers in the same pass |
| RW-5 | Stale `failAsOwner` | Hook forces an exception in A after supersession | `WorkerMutationFencingTest`, `StaleWorkerWakeTest` | Order not failed, stock untouched, no history row |
| RW-6 | Renewal after expiry / after monitor clear / after supersession; release by a stale owner | direct DAO calls with `FakeClock` | `LeaseFencingTest` | All 0 rows; the new owner's lease untouched |
| RW-7 | Monitor dies between `expireLeasesOf` and the re-offer | Hook/exception in the monitor | `WorkerMonitorTest` | Next pass re-offers; no loss |
| RW-8 | Duplicate entries, two generations polling during a restart overlap | enqueue id several times, retired flag | `DuplicateQueueEntryTest`, `QueueReconcileTest` | One chain; extra entries skipped |
| RW-9 | Operator retry vs sweeper TX4 vs staff cancel | barrier + row lock ordering | `PendingCompensationRaceTest`, `CompensationOperatorTest` | One terminal edge, stock +qty once, cancel refused |
| RW-10 | Marker write vs worker lease acquisition on a VALIDATED order | barrier | `PendingCompensationRaceTest` | Marker wins: worker's acquire/route statement 0 rows; never processed |
| RW-17 | **Renewal paused before the DAO call, clock advanced beyond expiry, then executed** (the RI-3a window) | Worker holds its token and is blocked at `WorkerHook.BEFORE_RENEW`; `FakeClock.advance` to `expiry + 2 s`; optionally one reconcile pass offers the order; release | `LeaseRenewalFreshStampTest` | The DAO takes a fresh stamp; the usable predicate fails; renewal returns lost; `lease_expires_at`, `claim_epoch`, `claimed_by` unchanged; no history row; the worker regains ownership only via `acquireLease` (epoch+1) |
| RW-18 | Stall inside the DAO between stamp and execution, or before commit (RI-3b/c) | `LeaseStampHook` at `AFTER_STAMP`, `BEFORE_COMMIT`; clock advanced beyond the guard budget | `LeaseStampGuardTest` | `LeaseStampStaleException`, whole transaction rolled back (lease, status, history, inventory unchanged); retry with a fresh stamp fails if the lease expired; stalls within the budget commit; margin boundary respected |
| RW-12 | Commit succeeds, reply lost: acquire, fenced step, `failAsOwner` | fault point `AFTER_COMMIT_BEFORE_RETURN` | `LostReplyIdempotenceTest` | Same-owner re-acquire; retried step continues; retried failure stands down; no second history row or restore |
| RW-13 | Reconcile or sweeper pass racing acquire/renew/step/expiry; healthy worker's poll-to-lease window | barrier + recording DAO | `ReconcileLeaseSafetyTest` (@RepeatedTest(20)) | Zero writes to lease columns by reconcile; valid lease untouched; duplicate entry skipped; one owner |
| RW-14 | Monitor stopped, worker killed after poll / after lease / mid-step; sweeper `runOnce()` only | stop the monitor, hook, `FakeClock` +61 s | `MonitorDeathSweeperRecoveryTest` | Worker restarted once (expectedGeneration), order delivered once, orphan threshold not reached |
| RW-15 | Monitor and sweeper repair the same dead worker concurrently | barrier on `restartWorker(code, gen)` | `MonitorDeathSweeperRecoveryTest`, `WorkerMonitorTest` | One restart, one new generation |
| RW-16 | Full state matrix: queue in/out x lease valid/free x ordinary/pending/terminal | parameterised | `QueueReconcileTest` | Only free-lease, ordinary, absent orders re-offered |
| RW-11 | Worker dies at each stage while cancels, operator retries and the sweeper run | random hook stage, 20 repeats | `DeadWorkerRaceTest` | No loss, no duplicate effect, ledger intact |

## 7. Recovery tests (Phase 5, 8)

* `RecoveryServiceTest`: insert orders in each non-terminal status (+ some terminal), start `AppContext`, assert each non-terminal one is enqueued exactly once and reaches DELIVERED; terminal ones untouched.
* `ResumableProcessingTest`: orders pre-set to PREPARING, PACKED, OUT_FOR_DELIVERY continue and produce only the remaining history rows.
* Recovery with more orders than queue capacity: all eventually processed, startup completes.
* `RoutingCrashWindowTest` (Phase 5): with `CrashingSubmissionHook`, crash after TX1 commit, after `offer`, after the ROUTED commit, and during compensation; restart on the same DB; assert each order reaches DELIVERED (or its consistent compensated state), stock deducted exactly once (`assertStockLedger`), each edge once, a repeat submission with the same external reference returns DUPLICATE with the existing id.
* `PendingCompensationRestartTest` (Phase 5): restart with PENDING orders (TX4 forced to fail before and after the restart) and with stale leases: leases cleared first, compensation attempted before any enqueue, still-pending orders never enqueued/leased/processed, snapshot files contain none of them, ordinary orders unaffected. `CompensationBackoffTest`: attempts, `compensation_next_at` backoff to the cap, `COMPENSATION_STUCK` threshold with `FakeClock`, operator retry resolves it (ignoring backoff, never the restore guard), staff cancel is refused.
* `RecoveryServiceTest` also covers: terminal orders never loaded, oldest-first order, order of an inactive station -> FAILED(`STATION_UNAVAILABLE`) with restore only when VALIDATED/ROUTED.
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
* **Account status:** `AccountDisableWebTest`: log in as KITCHEN_STAFF, disable the account as ADMIN, then issue a GET and a POST with the old session (302 to `/login`, session invalidated, no data changed); login refused with the generic message; re-enable and verify the old session stays dead while a fresh login works; change a user's role and verify the next request uses the new role; disabling oneself or the last ADMIN is refused; CSRF and role checks on the enable/disable POST.
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
| E-11 | Worker dequeues before ROUTED update visible; submitter's ROUTED update 0 rows or throwing | `RoutingRaceTest`, `StationWorkerTest` |
| E-26 | Crash after TX1 / after offer / after ROUTED / during compensation | `RoutingCrashWindowTest` |
| E-27 | Cancel vs ROUTED update; cancel vs compensation; two cancels | `CompensationRaceTest`, `CancelVsWorkerRaceTest` |
| E-28 | Transient DB failure at each worker step, beyond the retry limit | `TransientFailureRecoveryTest` |
| E-29 | Stale/duplicate ids of terminal orders; duplicate history edge insert | `TerminalOrderNeverResumedTest`, `StatusHistoryDaoTest` |
| E-31 | Orphan older than threshold but queued/active/paused/parked; order becomes terminal between the query and the enqueue | `OrphanSweeperTest`, `OrphanSweeperRaceTest` |
| E-32 | Sweeper pass while compensation pending; restart with a pending compensation | `CompensationPendingSweepTest` |
| E-33 | Fault between each pair of steps of every multi-step workflow | `TransactionAtomicityTest` |
| E-34 | Metrics/log after a failed commit or 0-row update | `PostCommitSideEffectsTest` |
| E-36 | Pending compensation: restart, repeated failure, cancel, duplicate submission, worker/sweeper attempts | `PendingCompensationRestartTest`, `CompensationBackoffTest`, `PendingCompensationRaceTest` |
| E-37 | Worker death at each `WorkerHook` stage; hung worker plus zombie wake-up; restart budget exhausted | `WorkerMonitorTest`, `WorkerHangRecoveryTest`, `DeadWorkerRaceTest` |
| E-39 | Stale worker acts after expiry+supersession: step, failure handling, renew, release | `StaleWorkerWakeTest`, `WorkerMutationFencingTest`, `LeaseFencingTest` |
| E-40 | Lease expired without supersession; retired vs live worker | `LeaseExpiryReacquireTest` |
| E-41 | Crash between poll and lease; after lease commit; during parking | `PollToLeaseCrashWindowTest`, `DeadWorkerRaceTest` |
| E-42 | Operator retry vs sweeper vs cancel on a PENDING order; staff cancel of PENDING | `CompensationOperatorTest`, `PendingCompensationRaceTest` |
| E-43 | Any call to an unfenced mutation from `StationWorker`, or to restricted DAO methods from a non-allowed class | `ArchitectureRulesTest` |
| E-47 | Renewal whose thread was paused past expiry (initial state held); renewal within the margin before expiry; expiry observed by reconcile before the renewal executes | `LeaseRenewalFreshStampTest`, `LeaseFencingTest` |
| E-48 | Stall inside the DAO between stamp and execute; between statements; before commit | `LeaseStampGuardTest` |
| E-44 | Lost reply after commit (acquire, step, failure) | `LostReplyIdempotenceTest` |
| E-45 | Reconcile/sweeper never write lease columns; stale-`now` renewal boundary | `ReconcileLeaseSafetyTest`, `LeaseExpiryReacquireTest` |
| E-46 | Monitor dead, sweeper alone; both repair concurrently | `MonitorDeathSweeperRecoveryTest` |
| E-38 | Order with an unexpired lease is never reclaimed by the sweeper even when `activeIds` is empty; stale `activeIds` entry does not block a genuine orphan | `OrphanSweeperTest`, `LeaseFencingTest` |
| E-35 | Disabled user with an open session issues GET and POST; last-admin and self-disable guards | `AccountDisableWebTest`, `AccountDisableServiceTest` |
| E-30 | Recovered order whose station is inactive; snapshot-inserted order later cancelled (no restore) | `RecoveryServiceTest`, `SnapshotRecoveryTest` |
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
| `CryptoSpikeTest` | `spike` | 1 | Spike | PBKDF2WithHmacSHA256 at 600,000 iterations: availability, determinism, salt effect, constant-time compare, measured cost (S-16) |
| `LauncherShutdownSpikeTest` | `spike` | 1 | Spike | The real `Launcher.main` in a child JVM (embedded database + Tomcat): `/health` answers, SIGTERM runs the shutdown hook, whose log lines, exit code 143, stopped database process and closed port are asserted (S-14); skipped on Windows |
| `AppConfigTest` | `config` | 2 | Unit | Defaults, required keys, minimum PBKDF2 iterations, sweeper/lease/monitor/reconcile/compensation keys cross-validated (renew*3 <= ttl, stall > ttl, 50 <= margin <= 2000 ms and 10*margin <= renew interval, orphan.after >= 2*ttl and >= 10*offer timeout, dispatch window > offer timeout + compensation retry window), secret redaction in toString() |
| `ArchitectureRulesTest` | `architecture` | 2 | Unit | Source-scan layering rules: no servlet imports outside web.*, no java.sql outside dao.jdbc/db/bootstrap/snapshot recovery, no double/float for money, StationWorker uses only the owner-fenced OrderService methods, OrderDao.transition and the compensation/lease DAO methods are called only from the services allowed in SDD section 7.12.5, no lease DAO or OrderService lease method has a timestamp (Instant) parameter, Clock is read for lease validity only inside the DAO stamp helper |
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
| `AccountDisableServiceTest` | `service` | 3 | DAO | setEnabled bumps session_version, refuses self-disable and disabling the last enabled ADMIN, validateSession rejects disabled/unknown/stale-version users and returns the current role, disabled user cannot authenticate, re-enable does not revive old sessions |
| `AuthServiceTest` | `service` | 3 | DAO | Login success, wrong password, unknown user (uniform failure), disabled user (uniform failure, hash still compared), lockout after 5, unlock after 5 minutes, validateSession |
| `DatabaseTransactionTest` | `db` | 3 | DAO | Commit, rollback on exception, connection always returned, no nested connection sharing, autocommit read sees concurrent commit while a REPEATABLE READ transaction read does not (documented behaviour) |
| `DeadlockRetryTest` | `db` | 3 | DAO | Forced deadlock (two crossing transactions) retried up to 3 times then surfaced as PersistenceException |
| `MenuItemDaoTest` | `dao/jdbc` | 3 | DAO | Catalogue load per station, price update, activate/deactivate |
| `OrderDaoTest` | `dao/jdbc` | 3 | DAO | Insert, UNIQUE(source, external_ref), conditional transition row counts, fenced transition (epoch predicate), VALIDATED->ROUTED refused while compensation PENDING, terminal transition resolves the marker, lease columns, find non-terminal (excludes pending), count by status, BigDecimal/UTC and utf8mb4 round trips |
| `OrderServiceTest` | `service` | 3 | DAO | TX1 happy path, insufficient stock rollback, duplicate, sorted deduction, cancel re-read loop, cancel refused for PENDING-compensation orders, cancel/fail restore matrix, restore exactly once, compensation without queue, post-offer rule with a fake dispatcher |
| `PostCommitSideEffectsTest` | `service` | 3 | DAO | Metrics counters and orders.log events are emitted only after commit and only by the winning caller; a failed commit or a 0-row conditional update emits nothing |
| `SchemaInitializerTest` | `bootstrap` | 3 | DAO | Applies V001/V002 once, idempotent re-run, schema_version row |
| `SqlPortabilityTest` | `db` | 3 | Unit | Scans db/*.sql for forbidden non-portable tokens (ENUM, JSON, triggers, NOW(), RETURNING, SKIP LOCKED, utf8mb4_0900*) |
| `StaffUserDaoTest` | `dao/jdbc` | 3 | DAO | Create, find by username, lockout columns, SELECT FOR UPDATE flow, findSessionState, setEnabled/session_version bump, countEnabledAdmins |
| `StatusHistoryDaoTest` | `dao/jdbc` | 3 | DAO | Ordered history per order, actor (incl. worker:<CODE>#<generation>) and note stored, duplicate (order, from, to) edge rejected by the unique index |
| `StockDaoTest` | `dao/jdbc` | 3 | DAO | Conditional deduction succeeds/fails on boundary, restore, low-stock query, never negative |
| `TransactionAtomicityTest` | `service` | 3 | DAO | Fault injected between the steps of each multi-step workflow (status update / history insert / claimStockRestore / inventory restore / TX1 items / disable-account): everything rolls back together; repeating the call afterwards behaves idempotently |
| `UserSeederTest` | `bootstrap` | 3 | DAO | Seeds only when staff_users empty; passwords from config, stored hashed; never logged |
| `SqlErrorsTest` | `db` | 3 | Unit | Deadlock = vendor code 1213 (or SQLState 40001 that is not 1205), duplicate key, unavailable and transient classification, through cause chains |
| `StationDaoTest` | `dao/jdbc` | 3 | DAO | Active stations, lookup by code and id, inactive stations hidden from the active list, unique code |
| `DurableCompensationTest` | `service` | 3 | DAO | PENDING marker when TX4 cannot commit, INDETERMINATE when even that fails, pending order never leased/routed/cancelled, completion restores stock once, backoff and alert, ADMIN-only audited operator retry (no queue needed: fake dispatchers) |
| `OrderServiceLeaseTest` | `service` | 3 | DAO | Owner-fenced API: lifecycle walk with worker actors, superseded owner writes nothing, `failAsOwner` restore rule, epochs and startup clear, stale-stamp rollback and retry with a fresh stamp, no lease revival |
| `BootstrapRestartTest` | `bootstrap` | 3 | Integration | The real start-up sequence three times across database restarts: scripts, reference data and accounts created once |
| `CatalogueServicesTest` | `service` | 3 | DAO | `MenuService`, `StockService`, `OrderQueryService`: validation, create with inventory row, adjustment, paging, filters, detail with history |
| `OrdersDemoWebTest` | `web` | 3 | Integration | Real Tomcat + real database: the read-only `/demo/orders` servlet/JSP lists persisted orders, escapes output, rejects bad input, survives a restart; an unreachable database fails the start |
| `CancelVsWorkerRaceTest` | `pipeline` | 4 | Concurrency | Cancel racing a worker claim and the ROUTED update: consistent final state, cancel retries instead of false refusal, stock restored at most once, stock rule respected (@RepeatedTest(20)) |
| `CompensationRaceTest` | `pipeline` | 4 | Concurrency | Cancel racing queue-full compensation and the ROUTED update: CANCELLED xor FAILED, one terminal edge, stock restored exactly once, caller sees QUEUE_FULL without failed++ when cancel won (@RepeatedTest(20)) |
| `ConditionalTransitionRaceTest` | `pipeline` | 4 | Concurrency | Two threads attempt same transition: exactly one wins, one history row (@RepeatedTest(20)) |
| `DeadWorkerRaceTest` | `pipeline` | 4 | Concurrency | Worker dies at every WorkerHook stage (after poll, after lease, before/after each step, during park) while cancels, submissions, operator retries, the monitor, the sweeper and reconcile run: no order lost, recovered within one pass, no duplicate edge or side effect, no valid lease disturbed, stock ledger intact, terminal never resumed (@RepeatedTest(20)) |
| `DuplicateQueueEntryTest` | `pipeline` | 4 | Concurrency | Same order id queued several times (and a second worker on the same order): one chain, each edge once, one processed increment, stock unchanged (@RepeatedTest(20)) |
| `DuplicateSubmissionConcurrencyTest` | `pipeline` | 4 | Concurrency | Concurrent identical (source, external_ref): exactly one order, one deduction; DUPLICATE reports the existing status and never revives a FAILED or pending order (@RepeatedTest(20)) |
| `DurableCompensationTest` | `service` | 4 | Concurrency | Fault injected at every step of the compensation (status update, history, claimStockRestore, inventory restore, marker write): TX4 rolls back as a unit; marker committed when TX4 fails; INDETERMINATE when even the marker cannot be written; caller answered only after FAILED or PENDING is durable; stock ledger intact; repeat is idempotent |
| `LeaseExpiryReacquireTest` | `pipeline` | 4 | Concurrency | Lease expires (FakeClock) while nobody supersedes: renewal and fenced step affect 0 rows, the non-retired worker re-acquires (epoch+1) and continues exactly once; a retired worker does not re-acquire and the id is re-registered; re-acquire bounded by worker.reacquire.max |
| `LeaseFencingTest` | `service` | 4 | Concurrency | Acquire/renew/release/expire predicates: acquire only for a free lease or the same owner string; renewal and steps require epoch, owner and a lease usable at the fresh stamp (expiry > stamp + margin); renewal never revives an expired, NULL-cleared, superseded or within-margin lease; stale owner's release cannot free a new owner's lease; epoch increments; acquire refused for pending-compensation and terminal orders; expireLeasesOf touches only the named owner; startup clears all leases; NULL comparison semantics; no lease method accepts a timestamp parameter |
| `LeaseRenewalFreshStampTest` | `service` | 4 | Concurrency | Deterministic (WorkerHook BEFORE_RENEW latch + FakeClock): the worker holds its token (initial state), the clock is advanced beyond lease expiry (optionally a reconcile pass offers the order again), the renewal then executes: the DAO takes a fresh stamp, the usable predicate fails, renewal returns lost, lease_expires_at/claim_epoch/claimed_by unchanged, no history row; renewal inside the margin before expiry also fails; the worker only regains ownership through acquireLease (new epoch), never by reviving the old lease |
| `LeaseStampGuardTest` | `service` | 4 | Concurrency | Deterministic (LeaseStampHook AFTER_STAMP / AFTER_EXECUTE / BEFORE_COMMIT + FakeClock): a stall between stamp and execution, or between statements, or before commit, longer than the guard budget aborts with LeaseStampStaleException and rolls the whole transaction back (lease, status, history, inventory unchanged); the retry takes a fresh stamp and fails if the lease expired; stalls within the budget commit; boundary of margin and budget |
| `LostReplyIdempotenceTest` | `service` | 4 | Concurrency | Commit succeeds but the reply is lost (fault point AFTER_COMMIT_BEFORE_RETURN) for lease acquire, a fenced step and failAsOwner: same-owner re-acquire succeeds (epoch+1), a retried step finds epoch/owner/valid lease unchanged and continues from the committed status, a retried failAsOwner finds the order terminal and stands down; no second history row or restore |
| `OrderOrchestratorTest` | `pipeline` | 4 | Concurrency | One queue and worker per station, thread names station-<brand>-worker, routing to correct station, ROUTED set after enqueue |
| `PendingCompensationRaceTest` | `pipeline` | 4 | Concurrency | Pending order raced by operator retry, sweeper completion, staff cancel attempts (refused), a worker lease/claim attempt (DB guards) and duplicate submissions: exactly one terminal edge, stock restored once, never routed or processed, DUPLICATE never revives it (@RepeatedTest(20)) |
| `PollToLeaseCrashWindowTest` | `pipeline` | 4 | Recovery | Worker killed (WorkerHook Error) right after poll, before lease acquisition, and after the lease commit before use: id is in no queue and no memory set; one WorkerMonitor pass (no orphan timeout, FakeClock advanced by less than the health interval plus the lease TTL) restarts the worker, reconciles from the DB and the order is delivered once |
| `QueueFullCompensationTest` | `pipeline` | 4 | Concurrency | Compensation only when the id was not inserted (full, interrupted before insert, station unavailable, closed); interrupt after insert is success; TX4 failure -> durable PENDING marker + QUEUE_FULL(compensated=false); marker failure -> INDETERMINATE; metrics updated |
| `QueueReconcileTest` | `pipeline` | 4 | Concurrency | Reconcile rule over the full state matrix (queue in/out x lease valid/free x ordinary/pending/terminal): only free-lease, ordinary, absent-from-physical-queue orders are re-offered (VALIDATED only after the dispatch window); DEAD or unhealthy stations skipped; full queue skipped until next pass; repeated passes yield only benign duplicates; batch limit with oldest-first progress |
| `ReconcileLeaseSafetyTest` | `pipeline` | 4 | Concurrency | Reconcile and sweeper passes racing worker acquire/renew/step/expiry: a recording DAO proves they execute zero writes on lease columns; a valid lease is never re-offered or disturbed; an expired lease observed by reconcile is never revived by a later renewal; a healthy worker's poll-to-lease window yields only a skipped duplicate entry; no two owners, each edge once, ledger intact (@RepeatedTest(20)) |
| `RoutingRaceTest` | `pipeline` | 4 | Concurrency | Worker dequeues before the submitter's ROUTED update (GatedOrderDispatcher): one VALIDATED->ROUTED row, DELIVERED, ACCEPTED, stock deducted once; failing ROUTED update after offer is benign (@RepeatedTest(20)) |
| `StaleWorkerWakeTest` | `pipeline` | 4 | Concurrency | Deterministic (WorkerHook latches + FakeClock): worker A paused before a step, clock advanced past lease expiry, B acquires epoch+1 and completes PACKED, A released: A's step, renewal, failAsOwner and release change nothing, A stands down without re-offering, order delivered once, each edge once (@RepeatedTest(20)) |
| `StationWorkerTest` | `pipeline` | 4 | Concurrency | Full lifecycle ROUTED to DELIVERED under a lease with owner-fenced steps; worker self-routes a VALIDATED order; skips terminal, pending-compensation and foreign-leased ids; heartbeat renews the lease during sleeps; L-5 outcomes: continue after a lost reply, re-acquire after plain expiry, stand down when superseded; history actor carries the generation |
| `StockDeductionConcurrencyTest` | `pipeline` | 4 | Concurrency | N threads race for limited stock: no oversell, stock never negative, sum invariant (@RepeatedTest(20)) |
| `TerminalOrderNeverResumedTest` | `pipeline` | 4 | Concurrency | Stale or duplicate ids of DELIVERED/CANCELLED/FAILED orders are skipped with no status, history, stock or metric change; recovery never loads them |
| `TransientFailureRecoveryTest` | `pipeline` | 4 | Concurrency | FaultInjectingOrderDao fails transiently N times at each step (claim, PACKED, DELIVERED, failOrder, submitter ROUTED update): order still delivered, never FAILED, never dropped, parking after worker.retry.max, worker alive; permanent error -> FAILED |
| `WorkerHangRecoveryTest` | `pipeline` | 4 | Concurrency | Worker blocked while holding a lease (no heartbeat): detected as stalled, retired and replaced, new worker takes over, the zombie later wakes and is fenced out (renew, step, failAsOwner, release): order delivered exactly once, each edge once (@RepeatedTest(20)) |
| `WorkerInterruptTest` | `pipeline` | 4 | Concurrency | Interrupt flag restored, worker exits cleanly, no leaked thread |
| `WorkerMonitorTest` | `pipeline` | 4 | Concurrency | Worker killed by an injected Error (WorkerHook) is detected, replaced with generation+1 on the same queue (restartWorker with expectedGeneration is idempotent), leased ids reclaimed and re-offered by the same pass, restart budget exhausted -> DEAD with ERROR, no restart during shutdown, admin restart; monitor death between expire and re-offer repaired by the next pass |
| `WorkerMutationFencingTest` | `service` | 4 | Concurrency | Every worker mutation (self-route, each step, failAsOwner with stock restore, renew, release) attempted with a stale epoch, a wrong owner, an expired lease, a monitor-cleared lease and a superseded owner: a recording DAO shows the transaction executed exactly one statement (the fenced UPDATE) and zero rows changed in orders, order_status_history and inventory, no metric, no log event; with a valid token the statement sequence is exactly S1..S4 of SDD 7.13.1; fault injected after each statement rolls back all of them |
| `WorkerResilienceTest` | `pipeline` | 4 | Concurrency | A permanent error (poisoned order) marks that order FAILED and the worker keeps processing the next one; Throwable per order never kills the thread |
| `CompensationBackoffTest` | `pipeline` | 5 | Recovery | Repeated compensation failure: attempts and compensation_next_at back off exponentially to the cap, COMPENSATION_STUCK after the alert threshold, never gives up, never auto-FAILs without restore, operator retry ignores backoff but not the restore guard, dashboard counters |
| `CompensationOperatorTest` | `service` | 5 | DAO | ADMIN-only retryCompensation: one transaction (TX4) with guarded restore, history row with admin actor and COMPENSATION_MANUAL note, idempotent double click, loses cleanly to the sweeper, failure rolls back and bumps attempts, cancelOrder refuses PENDING orders, no path leaves PENDING without the restore guard or enqueues/processes a PENDING order |
| `CompensationPendingSweepTest` | `pipeline` | 5 | Recovery | Sweeper completes durable pending compensations (due ones only, batch-limited), never enqueues them, loses races to cancel cleanly, marker resolved by the winning transition |
| `FeedSimulatorTest` | `pipeline` | 5 | Concurrency | Start/stop/rate change, idempotent start, produces unique external refs, stops on shutdown |
| `GracefulShutdownTest` | `pipeline` | 5 | Recovery | In-flight orders drained or persisted, no new submissions accepted, threads terminated |
| `MetricsInvariantTest` | `pipeline` | 5 | Concurrency | submitted = rejected + delivered + failed + cancelled + in-flight at quiescence |
| `MonitorDeathSweeperRecoveryTest` | `pipeline` | 5 | Recovery | Worker monitor stopped; a worker killed at AFTER_POLL, AFTER_LEASE and mid-step; one OrphanSweeper.runOnce() runs the same station repair routine (health check, restart with expectedGeneration, lease reclaim, reconcile) and the order is delivered once; concurrent monitor and sweeper repairs restart a worker only once |
| `OrphanSweeperRaceTest` | `pipeline` | 5 | Concurrency | Sweeper concurrent with live submissions, workers, cancel, compensation and worker restarts: no duplicate edge, no double restore, no terminal order resumed, stock ledger intact (@RepeatedTest(20)) |
| `OrphanSweeperTest` | `pipeline` | 5 | Recovery | Second independent recovery path: runs the shared station repair routine first; stale (>= threshold), unleased, non-pending, not-in-physical-queue orders re-enqueued; fresh, leased, queued, paused, parked skipped; in-memory sets never prevent a reclaim; pending compensations retried with backoff; missing station -> FAILED(STATION_UNAVAILABLE); terminal untouched; batch limit; disabled sweeper |
| `PendingCompensationRestartTest` | `pipeline` | 5 | Recovery | Restart with PENDING orders: leases cleared first, compensation attempted before anything is enqueued, still-failing ones stay PENDING and are never enqueued or processed, snapshot excludes them, ordinary orders unaffected |
| `RecoveryServiceTest` | `pipeline` | 5 | Recovery | Startup order: clear leases, resolve pending compensations, then enqueue non-pending non-terminal orders oldest first; terminal never loaded; more orders than queue capacity do not block startup forever; order of a missing/inactive station ends FAILED(STATION_UNAVAILABLE) |
| `ResumableProcessingTest` | `pipeline` | 5 | Recovery | Orders found in VALIDATED, ROUTED, PREPARING, PACKED, OUT_FOR_DELIVERY resume from the current status; only remaining edges are added; stock ledger unchanged |
| `RoutingCrashWindowTest` | `pipeline` | 5 | Recovery | SubmissionHook simulated crash after TX1 / after offer / after ROUTED / during compensation, then new AppContext on the same DB: each order reaches DELIVERED (or its consistent compensated state) with stock deducted/restored exactly once |
| `ShutdownCoordinatorTest` | `pipeline` | 5 | Recovery | Idempotent, ordered steps (sweeper, worker monitor and snapshot scheduler stopped before draining), double trigger (listener + hook) runs once, no worker restart during shutdown, timeout then shutdownNow |
| `CsvReportExporterTest` | `io` | 6 | Unit | RFC 4180 quoting, UTF-8, header, CSV-injection neutralisation of text cells (e.g. external reference starting with =), empty day, no customer data in file |
| `HmacSignerTest` | `snapshot` | 6 | Unit | Known-answer test, tamper detection, constant-time verify, wrong key |
| `NioOrderEventLogTest` | `io` | 6 | Unit | Append-only structured lines, UTF-8, file created from config path, close idempotent |
| `NoPiiInLogsTest` | `io` | 6 | Unit | Customer name, address, passwords, secrets never appear in orders.log or app log output |
| `OrderEventLogConcurrencyTest` | `io` | 6 | Concurrency | Many threads, no interleaved/lost lines (@RepeatedTest(20)) |
| `SnapshotFilterTest` | `snapshot` | 6 | Serialization | Disallowed class in stream rejected by ObjectInputFilter; depth/size limits |
| `SnapshotRecoveryTest` | `snapshot` | 6 | Serialization | Only orders missing from DB inserted; present ones untouched; stock not re-deducted; unknown station rejected; snapshots never contain orders awaiting compensation |
| `SnapshotRoundTripTest` | `snapshot` | 6 | Serialization | Write then read returns equal DTOs; file layout; retention of last N |
| `SnapshotTamperTest` | `snapshot` | 6 | Serialization | Modified payload or HMAC rejected before any deserialization (SnapshotIntegrityException) |
| `SnapshotTaskTest` | `snapshot` | 6 | Concurrency | Scheduled task runs periodically, survives a failed run, stops on shutdown |
| `AccountDisableWebTest` | `web` | 7 | Web | Disabled account: existing session rejected and invalidated on the very next GET and POST, redirected to login with a generic message; login refused; re-enabled account needs a fresh login; role change takes effect on the next request; enable/disable needs ADMIN and CSRF |
| `AdminMenuStockWebTest` | `web` | 7 | Web | Admin menu and stock management flows |
| `CompensationOperatorWebTest` | `web` | 7 | Web | /admin/compensations: ADMIN only, CSRF, PRG, double submit, list of pending orders with attempts and age, order page shows no Cancel button for a PENDING order, KITCHEN_STAFF gets 403 |
| `CsrfWebTest` | `web` | 7 | Web | POST without/with wrong token gets 403; token present in every form |
| `DashboardWebTest` | `web` | 7 | Web | Auto-refresh 5s, low-stock flags, queue size/capacity, worker states and generations, metrics, pending-compensation count and age |
| `EncodingWebTest` | `web` | 7 | Web | Non-ASCII (e.g. Hindi, emoji) names survive request and response |
| `ErrorPagesWebTest` | `web` | 7 | Web | 400/403/404/500 generic bodies, no stack trace or class names |
| `LockoutWebTest` | `web` | 7 | Web | 5 failed logins lock account; correct password refused during lock; generic message |
| `LoginFlowWebTest` | `web` | 7 | Web | Login success/failure, session id changes at login, redirect, unauthenticated access redirected |
| `OrderDetailWebTest` | `web` | 7 | Web | Order page lists status history in order; cancel button rules; PENDING-compensation banner without a Cancel button (ADMIN sees a link to the compensation page) |
| `OrderPrgWebTest` | `web` | 7 | Web | POST new order returns 302 then GET shows flash once; refresh does not resubmit |
| `ReportWebTest` | `web` | 7 | Web | CSV download headers and content; admin only |
| `RoleAccessWebTest` | `web` | 7 | Web | Route-by-role matrix: ADMIN vs KITCHEN_STAFF vs anonymous for every protected route (incl. /admin/users); role taken from the database on every request |
| `SecurityHeadersWebTest` | `web` | 7 | Web | nosniff, frame deny, CSP present on all responses |
| `SessionSecurityWebTest` | `web` | 7 | Web | HttpOnly, SameSite=Lax, Secure by config, 30-minute timeout config, logout invalidates |
| `SimulatorWebTest` | `web` | 7 | Web | Admin start/stop/rate via UI; kitchen staff forbidden |
| `WorkerPanelWebTest` | `web` | 7 | Web | Worker panel states (incl. DEAD, generation, restarts), pause/resume and restart (admin only) |
| `XssEscapingWebTest` | `web` | 7 | Web | Script in customer name and menu name rendered escaped |
| `AdminPasswordResetWebTest` | `web` | 8 | Web | COULD: admin resets staff password; old password rejected; the user's existing sessions are invalidated (session_version bump) |
| `CrashRecoveryTest` | `integration` | 8 | Recovery | Abrupt stop (no drain, no final snapshot), restart on same DB, all non-terminal orders complete; sweeper active during the run |
| `EndToEndLifecycleTest` | `integration` | 8 | Integration | Feed to DELIVERED across all three sources and stations with DB, files and metrics consistent |
| `LauncherSmokeTest` | `integration` | 8 | Integration | Launcher boots embedded DB + Tomcat + pipeline, serves /login, shuts down cleanly via hook path |
| `OrderSearchWebTest` | `web` | 8 | Web | COULD: search by ref/status/source |
| `PublicTrackingWebTest` | `web` | 8 | Web | COULD: tracking page by code shows status only, no PII |
| `ThroughputSmokeTest` | `integration` | 8 | Integration | Sustained simulated load completes; no FAILED from queue-full at default settings; bounded memory |

Test-support classes (`TestDatabase`, `TestApp`, `HttpTestClient`, `TestData`, `FakeClock`, `TestSupport`) are listed in §3.

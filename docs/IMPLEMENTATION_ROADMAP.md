# Implementation Roadmap

**Status:** Phase 0 and Phase 1 are complete; Phases 2-8 are planned and their boxes are unticked. A box is ticked only when the task is done **and** the supporting output is in [EVIDENCE.md](EVIDENCE.md).
**Related:** [SRS](SRS.md) · [SDD](SDD.md) · [TRACEABILITY](TRACEABILITY.md) · [TESTING_STRATEGY](TESTING_STRATEGY.md) · [../CLAUDE.md](../CLAUDE.md)

---

## 0. How phases work

### 0.1 Dependency graph

```mermaid
flowchart LR
    P0["Phase 0: Documentation baseline (COMPLETE, frozen 2026-10-10)"] --> P1
    P1["1 Skeleton + zero-install spike"] --> P2["2 Domain + services (no DB)"]
    P2 --> P3["3 Persistence"]
    P3 --> P4["4 Concurrent pipeline"]
    P4 --> P5["5 Resilience: simulator, recovery, shutdown, metrics"]
    P5 --> P6["6 File handling + serialization"]
    P6 --> P7["7 Web layer"]
    P7 --> P8["8 Integration audit + Review 2 pack (+ COULD items)"]
```

Strictly sequential: a phase starts only after the previous phase's exit gate is evidenced **and the user has approved** moving on.

### 0.2 Protocol for every phase (copy into the phase's first message)
**Start**
- [ ] Read `CLAUDE.md`, this phase's section, the SRS rows (TRACEABILITY rows whose *Phase* = this phase) and the SDD sections they reference.
- [ ] Report any conflict between documents and the plan **before** coding.
- [ ] `git status` clean; on branch `claude/cloud-kitchen-orchestration-7d1aok`.
- [ ] Resolve the **approvals** listed for the phase.

**During**
- [ ] Scope fence: do only this phase's tasks. If something from a later phase is needed, stop and report.
- [ ] Tests are written with (or before) the code they prove.
- [ ] Never commit secrets; never require a system installation.

**End (exit gate)**
- [ ] Every exit criterion met.
- [ ] Every gate command run; real output pasted into EVIDENCE.md (phase, command, output, date).
- [ ] TRACEABILITY.md statuses updated (`Done (<hash>)`).
- [ ] Viva notes filled in JAVA_CONCEPT_MAPPING.md §3 for this phase.
- [ ] Decision log updated if anything was settled (PROJECT_OVERVIEW §6.7).
- [ ] Secret scan (G-SECRET) clean.
- [ ] Commit(s) pushed to the designated branch; local tag `phase-<n>-complete` created (tag push only with user approval, A-12).
- [ ] Short phase report to the user; **stop and wait for approval**.

### 0.3 Common gate commands

| ID | Command (Windows: `mvnw.cmd`) | Expected |
|---|---|---|
| G-BUILD | `./mvnw -q clean verify` | Exit code 0, all tests green |
| G-BUILD-x3 | run G-BUILD three times in a row (phases 4-8) | Exit code 0 each time |
| G-SECRET | `git ls-files \| grep -E '^(data\|logs\|snapshots\|reports)/\|^config/application\.properties$'` and `git grep -nIE '(password\|secret)[[:space:]]*[:=][[:space:]]*"[^"]+"' -- src/main` | Both print nothing |
| G-TREE | `git status --short` | Only intended files |
| G-TAG | `git tag -a phase-<n>-complete -m "Phase <n> complete"` | Tag exists locally |

The exact test-selection commands per phase are listed in each section. Final command forms are confirmed in Phase 1 and recorded in CLAUDE.md.

---

## Phase 1: Skeleton + zero-install spike

**Goal:** prove the whole zero-install stack works before any domain code exists, and settle the driver and packaging decisions.
**Depends on:** Phase 0 complete (baseline frozen; the commit of the documents is approved separately by the user).
**Status: COMPLETE (2026-10-10), approved by the user for one local commit and the local tag `phase-1-complete`; nothing pushed. Verified on Linux only (EVIDENCE.md EV-001..EV-014); limitations listed in EV-013: Windows, macOS and standalone Tomcat unverified, MariaDB Connector/J untested by the user's decision.**
**Approvals needed first:** A-01 (internet on first build), A-02 (native DB binary, no system packages), A-03/D-018 (wrapper fetch), A-04 (append to `.gitignore`), A-14 (dependency interpretation).

### Entry criteria (all must be true before task 1.1; each is checkable)
- [x] The user has explicitly approved committing the Phase 0 documents and the commit exists on the designated branch `claude/cloud-kitchen-orchestration-7d1aok` (`git log --oneline` shows it; `git status --short` is clean). Commit `992c1fe`; tree was clean at the start of Phase 1.
- [x] The user has answered **approved / not approved** for each of A-01, A-02, A-03 (D-018), A-04 and A-14 (record the answers in PROJECT_OVERVIEW §6.7). Answered 2026-10-10: all approved, A-02 with restrictions; recorded in PROJECT_OVERVIEW §6.7.
- [x] `java -version` prints 17 or newer and `git --version` works (record both in EVIDENCE.md §2); no other tool is needed. OpenJDK 21.0.12.1, git 2.43.0, recorded in EVIDENCE.md §2.
- [x] Outbound HTTPS to Maven Central works: `curl -sI https://repo.maven.apache.org/maven2/ | head -1` shows a 200 (record the output; if it fails, stop and report per A-01). It failed (HTTP 403 policy denial) until the user changed the environment network settings, then every Maven host returned HTTP 200 (EV-007). Note: behind the agent proxy this exact command prints the proxy line `HTTP/1.1 200 Connection Established`; use `curl -s -o /dev/null -w '%{http_code}'` for the real status.
- [x] README.md and `.gitignore` are still untouched except for the approved `.gitignore` append in task 1.3. `README.md` unchanged; `.gitignore` only appended (13 lines, none removed).

### Tasks
- [x] 1.1 `pom.xml`: `packaging=war`, `maven.compiler.release=17`, UTF-8; pinned versions (A-10) for tomcat-embed-core, tomcat-embed-jasper (10.1.x), Jakarta Servlet/JSP/JSTL API + JSTL implementation, HikariCP, MySQL Connector/J, MariaDB4j (+ platform DB artifacts), SLF4J, Logback, JUnit 5; plugins: compiler, surefire (JUnit platform, `forkCount=1`), war, exec (mainClass `com.cloudkitchen.bootstrap.Launcher`, `classpathScope` per D-017), resources. **Done:** `pom.xml` with pinned versions, WAR packaging, `provided` embedded artifacts (D-017), JSTL exclusions; EV-011.
- [x] 1.2 Maven Wrapper: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` ("only-script"), executable bit on `mvnw`; `.gitattributes` (`mvnw` LF, `mvnw.cmd` CRLF). **Done:** `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.16, SHA-256 pinned), `.gitattributes`; EV-001. `mvnw.cmd` not run.
- [x] 1.3 Append to `.gitignore` (A-04): `target/`, `data/`, `logs/`, `snapshots/`, `reports/`, `config/application.properties`, `*.ser`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`. **Done:** 13 lines appended to `.gitignore`; PROJECT_OVERVIEW 6.7.
- [x] 1.4 `config/application.properties.example` (placeholders only; startup rejects placeholders for secrets) and a minimal `AppConfig` sufficient for the spike. **Done:** `config/application.properties.example` and `AppConfig` (Phase 1 keys); EV-008.
- [x] 1.5 Package skeleton: directories and `package-info.java` for every package in [SDD §3](SDD.md) (one-line Javadoc each); `src/main/resources/logback.xml`. **Done:** 19 `package-info.java` and `logback.xml`; EV-008.
- [x] 1.6 Throwaway-but-kept spike code: `Launcher` (embedded Tomcat, port from config, `addWebapp`, `/spike.jsp` using JSTL, a plain servlet), `DbBootstrap` (MariaDB4j start/stop, data dir `./data`), `web.xml` with two ordered filters and a listener. **Done:** `Launcher`, `DbBootstrap` (hardened, D-043), `web.xml` (two ordered filters, listener), `/spike.jsp` and `/health`; EV-009, EV-012.
- [x] 1.7 Spike tests `DbSpikeTest` and `EmbeddedTomcatSpikeTest` covering checklist S-01..S-19 (below); run `DbSpikeTest` once per candidate driver. **Done:** `DbSpikeTest`, `EmbeddedTomcatSpikeTest`, `LauncherShutdownSpikeTest`, `CryptoSpikeTest`, `AppConfigTest` and `ArchitectureRulesTest` pass with MySQL Connector/J (EV-006, EV-008..EV-010, EV-014). The "once per candidate driver" wording was reconciled with the locked fallback ladder by the user's decision of 2026-10-10: the only candidate is Connector/J, MariaDB Connector/J was not run and is untested (PROJECT_OVERVIEW 6.7, D-002).
- [x] 1.8 Decide the driver (D-002), packaging approach (D-017), wrapper (D-018), config hand-off (O-3); record versions and decisions with dates in PROJECT_OVERVIEW §6.7. **Done:** D-002 (accepted by the user), D-017, D-018, O-3 settled; PROJECT_OVERVIEW 6.7.
- [x] 1.9 Record the actual first-run requirements (downloads, any OS libraries) in EVIDENCE.md and update assumptions A-01/A-02 to facts. **Done:** first-run requirements in EVIDENCE.md 2 and EV-007; A-01, A-02, A-03 updated to facts.
- [x] 1.10 Fill in CLAUDE.md "Build / run / test commands" with the commands proven in this phase. **Done:** CLAUDE.md section 8 filled in (verified on Linux only).

### Spike checklist (each item pass/fail with evidence)
| ID | Check |
|---|---|
| S-01 | `./mvnw -v` works on a machine with only JDK 17+ and Git (wrapper downloads Maven) |
| S-02 | MariaDB4j starts, creates `./data/...`, stops cleanly, leaves no orphan `mysqld`; a second start reuses the data |
| S-03 | HikariCP + MySQL Connector/J connects to it (then repeat with MariaDB Connector/J) |
| S-04 | utf8mb4 round trip of Hindi text and an emoji |
| S-05 | `DECIMAL(10,2)` <-> `BigDecimal` exact (0.01, 19.99, 99999999.99) |
| S-06 | `DATETIME(3)` UTC round trip, identical with `-Duser.timezone=Asia/Kolkata` |
| S-07 | Conditional `UPDATE` row-count semantics (1 vs 0) and `SELECT ... FOR UPDATE` |
| S-08 | `RETURN_GENERATED_KEYS` works |
| S-09 | Duplicate key error: SQLState `23000`, vendor code `1062` |
| S-10 | Two crossing transactions produce a deadlock with SQLState `40001` / code `1213` (observed with each driver) |
| S-11 | `CHECK` constraint enforced (negative `stock_qty` insert fails) |
| S-12 | `CREATE TABLE IF NOT EXISTS`, `utf8mb4_bin` column and `utf8mb4_unicode_ci` default accepted; multi-statements off |
| S-13 | Embedded Tomcat on port 0: servlet + JSP with JSTL render via `HttpClient`; filter order from `web.xml` is respected; `SessionCookieConfig.setAttribute("SameSite","Lax")` appears in `Set-Cookie`; a servlet-context attribute set by `Launcher` is visible in the listener; error-report valve can hide details |
| S-14 | `./mvnw exec:java` classpath works with the chosen scopes; Ctrl+C runs the shutdown hook and stops the DB |
| S-15 | `./mvnw package` WAR contains JSTL implementation, driver, Hikari, Logback and **no** `tomcat-embed-*` / `mariaDB4j` jars |
| S-16 | PBKDF2WithHmacSHA256 with 600,000 iterations measured (ms) on this machine |
| S-17 | Durability: `SELECT @@innodb_flush_log_at_trx_commit` is `1` on the embedded server (otherwise override in `DbBootstrap` and record, TXR-7); also record `@@transaction_isolation` / `@@tx_isolation` |
| S-18 | Portability of the statements added for leases and durable compensation: `UPDATE ... SET col = CASE WHEN ... END`, `claim_epoch = claim_epoch + 1` with read-back in the same transaction, `SELECT ... FOR UPDATE` filtered by `claimed_by`, `CHECK (x IS NULL OR x IN (...))`, nullable `DATETIME(3)` comparisons, affected-row counts of conditional updates (DATABASE_DESIGN §4) |

| S-19 | Row-lock and current-read behaviour that the fencing invariant relies on (SDD §7.11.15 item 4): with two connections a competing conditional `UPDATE` on the same `orders` row blocks until the first transaction commits and then re-evaluates its predicate on the committed row (no lost update, no stale-predicate success) under `REPEATABLE READ`, also with an injected delay between the `UPDATE` and the commit; record `innodb_lock_wait_timeout` and the error code when it expires |

### Exit criteria
- [x] S-01..S-19 all pass, or any failure has a decision recorded with the user's approval via the fallback ladder (PROJECT_OVERVIEW §6.3). Every executed item passed (EV-001, EV-003, EV-006, EV-009..EV-012, EV-014). Not executed, by the user's decision of 2026-10-10: the MariaDB Connector/J repeat named in S-03 and S-10 (MariaDB Connector/J is untested; see task 1.7).
- [x] Driver chosen and recorded; DAO plan unchanged. MySQL Connector/J 9.7.0 (D-002, accepted by the user); DAOs stay plain JDBC.
- [x] No system software was installed; first-run requirements documented. Nothing was installed; the only environment change was the permissions of the project root directory (`root:claude 1775`, non-recursive) so the unprivileged user can build (EV-013).
- [x] `.gitignore` additions in place; secret scan clean. EV-013.
- [x] CLAUDE.md commands filled in. Linux only.

### Gate commands
```
./mvnw -v
./mvnw -q clean verify
./mvnw -q test -Dtest='DbSpikeTest,EmbeddedTomcatSpikeTest'
./mvnw -q package && jar tf target/*.war | grep -Ei 'tomcat-embed|mariadb4j|jstl|HikariCP|connector|logback'
./mvnw exec:java        # then open http://localhost:8080/spike.jsp and press Ctrl+C
```
Plus G-SECRET, G-TREE, G-TAG (`phase-1-complete`).

**Requirements verified:** FR-ENV-01, FR-ENV-02, FR-ENV-03, NFR-SEC-01, NFR-PORT-01 (plus spike inputs to FR-ENV-04).

---

## Phase 2: Domain + services (no DB)

**Goal:** all pure-Java logic with unit tests; nothing touches a database or a servlet.
**Depends on:** Phase 1.

### Tasks
- [ ] 2.1 `domain.enums`: `OrderStatus` (transition table, `canTransitionTo`, `requireTransitionTo`, `allowedTargets`, `isTerminal`, `nonTerminal`, `isStockRestorableTo`), `OrderSource`, `PaymentMode` (`allowedFor`), `PaymentStatus`, `Role`, `WorkerState`.
- [ ] 2.2 `domain.contract`: `Trackable`, `Payable`.
- [ ] 2.3 `domain`: `Order` (abstract), `SwiggyOrder`, `ZomatoOrder`, `InHouseOrder`, `OrderItem`, `OrderFactory`, `PaymentResult`, `StatusHistoryEntry`, `Station`, `MenuItem`, `StockLevel`, `StaffUser`.
- [ ] 2.4 `exception`: full hierarchy ([SDD §4.2](SDD.md)).
- [ ] 2.5 `dto`: `OrderRequest`, `OrderItemRequest`, `SubmissionResult`, `ValidationResult`, `FieldError`, `SessionUser`, `OrderFilter`, `Page<T>`, `OrderSummary`, `StockRow`, `ReportRow`, `DashboardView`, `StationView`, `WorkerView`, `MetricsView`.
- [ ] 2.6 `util`: `MoneyUtil` (overloads), `TimeUtil` (UTC helpers).
- [ ] 2.7 `service`: `OrderValidator` (VR-01..VR-14), `MenuCatalog`, `RoutingService`, `PasswordHasher`, `LockoutPolicy`.
- [ ] 2.8 `pipeline`: `InFlightRegistry`, `MetricsRegistry`, `OrderDispatcher` (interface only).
- [ ] 2.9 `io`: `OrderEventLog` (interface), `NoOpOrderEventLog`, `EventType`.
- [ ] 2.10 `dao`: DAO **interfaces** only (signatures per [SDD §5.2](SDD.md)).
- [ ] 2.11 `config.AppConfig`: full typed loader, defaults, validation, secret masking.
- [ ] 2.12 Tests: `OrderStatusTest`, `OrderPolymorphismTest`, `OrderFactoryTest`, `OrderValidatorTest`, `MoneyUtilTest`, `PasswordHasherTest`, `LockoutPolicyTest`, `RoutingServiceTest`, `InFlightRegistryTest`, `MetricsRegistryTest`, `AppConfigTest`, `ArchitectureRulesTest`. `ArchitectureRulesTest` already contains the call-site restrictions of [SDD §7.12.5](SDD.md) (they bite as the classes appear in Phases 3-5).

### Exit criteria
- [ ] All Phase 2 tests pass; `OrderStatusTest` covers all 81 pairs.
- [ ] Tests run with **no database started** (no `mysqld` process, no network).
- [ ] `ArchitectureRulesTest` green.
- [ ] Formulas in SDD §4.4 match the code (or the SDD is updated with approval).

### Gate commands
```
./mvnw -q clean verify
./mvnw -q test -Dtest='OrderStatusTest,OrderPolymorphismTest,OrderFactoryTest,OrderValidatorTest,MoneyUtilTest,PasswordHasherTest,LockoutPolicyTest,RoutingServiceTest,InFlightRegistryTest,MetricsRegistryTest,AppConfigTest,ArchitectureRulesTest'
pgrep -fl 'mysqld|mariadbd' || echo "no database process"
```
Plus G-SECRET, G-TREE, G-TAG (`phase-2-complete`).

**Requirements verified:** FR-ENV-05, FR-ORD-02, -03, -05, -06; FR-LIF-01, -02, -06; FR-RTE-01; FR-CON-09; FR-AUT-01; NFR-MNT-01.

---

## Phase 3: Persistence

**Goal:** schema, DAOs, transactions, TX1, transitions with conditional stock restore, authentication with lockout, all tested against the real embedded database.
**Depends on:** Phase 2 and the driver decision (D-002).

### Tasks
- [ ] 3.1 `V001__schema.sql`, `V002__seed_reference_data.sql` per [DATABASE_DESIGN](DATABASE_DESIGN.md) (including `uq_history_order_edge`, the lease columns `claim_epoch`/`claimed_by`/`lease_expires_at`, the durable-compensation columns `compensation_state`/`reason`/`attempts`/`next_at`, `staff_users.session_version`, and the indexes `idx_orders_status_updated`, `idx_orders_compensation`, `idx_orders_claimed_by`); `SchemaInitializer`.
- [ ] 3.2 `db`: `DataSourceFactory`, `Database` (`inTransaction` + overload, `withConnection`, deadlock retry, `LeaseStampContext` binding and pre-commit guard), `TxWork<T>`, `SqlErrors`, `LeaseStampContext`, `LeaseStampHook`, `exception.LeaseStampStaleException` (stamp protocol S-1..S-7, [SDD §7.11.15](SDD.md); the lease DAO methods in 3.4 use it).
- [ ] 3.3 `bootstrap.DbBootstrap` (final form), `bootstrap.UserSeeder`.
- [ ] 3.4 `dao.jdbc`: `JdbcStationDao`, `JdbcMenuItemDao`, `JdbcStockDao`, `JdbcOrderDao`, `JdbcStatusHistoryDao`, `JdbcStaffUserDao` (prepared statements only, batch item insert, UTC timestamps). `JdbcOrderDao` also implements the lease operations, the fenced `transition` overload, the pending-compensation statements and the `compensation_state` guards (SDD §5.2, DATABASE_DESIGN §4).
- [ ] 3.5 `service.OrderService` (`submitOrder` with TX1, `transition` + overload, `cancelOrder`, `failOrder`, `setAccepting`) using a **fake `OrderDispatcher`** (always succeeds / always full) because the real orchestrator arrives in Phase 4; events through `NoOpOrderEventLog`.
- [ ] 3.6 `service`: `AuthService` (+ lockout via `LockoutPolicy`; `validateSession`, `setEnabled` with self/last-admin guards, `session_version` handling; disabled accounts refused at login), `MenuService`, `StockService`, `OrderQueryService` (read paths needed by tests). DAO additions: `StaffUserDao.findSessionState/setEnabled/countEnabledAdmins`, `OrderDao.findStaleNonTerminal`; schema: `staff_users.session_version`, `idx_orders_status_updated`.
- [ ] 3.7 Test support: `TestDatabase`, `TestData`, `FakeClock`, `TestSupport` (incl. `assertEachEdgeOnce`, `assertStockLedger`), `FaultInjectingOrderDao`, `FaultInjectingStockDao`, `FaultInjectingStatusHistoryDao`.
- [ ] 3.7b Workflow atomicity per [SDD §13.1](SDD.md): one transaction per workflow (TXR-1..TXR-7); post-commit actions only after commit (`SubmissionHook` seam added with `OrderService`).
- [ ] 3.8 Tests: `SqlPortabilityTest`, `SchemaInitializerTest`, `DatabaseTransactionTest`, `DeadlockRetryTest`, `StockDaoTest`, `OrderDaoTest`, `StatusHistoryDaoTest`, `MenuItemDaoTest`, `StaffUserDaoTest`, `OrderServiceTest`, `AuthServiceTest`, `UserSeederTest`, `TransactionAtomicityTest`, `PostCommitSideEffectsTest`, `AccountDisableServiceTest`.

### Exit criteria
- [ ] All Phase 3 tests pass against embedded MariaDB4j.
- [ ] TX1 rolls back completely on insufficient stock; restore matrix passes; restore exactly once.
- [ ] Lockout works with an injected clock; disabled accounts cannot log in; `validateSession` behaves per SDD §12.10.
- [ ] `TransactionAtomicityTest` proves rollback of every workflow step pair (SDD §13.1.3); `PostCommitSideEffectsTest` passes.
- [ ] `ArchitectureRulesTest` still green (no `Connection` fields, only prepared statements).
- [ ] If a MySQL 8 server is available, the schema scripts were applied to it as a manual check; otherwise recorded as *unverified*.

### Gate commands
```
./mvnw -q clean verify
./mvnw -q test -Dtest='SqlPortabilityTest,SchemaInitializerTest,DatabaseTransactionTest,DeadlockRetryTest,StockDaoTest,OrderDaoTest,StatusHistoryDaoTest,MenuItemDaoTest,StaffUserDaoTest,OrderServiceTest,AuthServiceTest,UserSeederTest,TransactionAtomicityTest,PostCommitSideEffectsTest,AccountDisableServiceTest'
./mvnw -q test -Dtest='DeadlockRetryTest' -Duser.timezone=Asia/Kolkata
```
Plus G-SECRET, G-TREE, G-TAG (`phase-3-complete`).

**Requirements verified:** NFR-REL-04, NFR-REL-05, FR-ENV-04, FR-ENV-06, FR-ORD-01, -07, FR-LIF-03, -05, FR-INV-01..07, FR-RTE-02 (service side), FR-CON-06, FR-AUT-05, -07, NFR-SEC-02, -03.

---

## Phase 4: Concurrent pipeline

**Goal:** queues, workers and orchestrator working under concurrency with proven safety properties.
**Depends on:** Phase 3.

### Tasks
- [ ] 4.1 `pipeline`: `NamedThreadFactory`, `StationRuntime`, `StationWorker` (loop; self-routing claim RR-3; fresh reads RR-4; terminal skip RR-5; loser stands down RR-6; failure classification, in-place retry and parking RR-8; resumable `processOrder`; pause/resume; `WorkerState`; interrupt handling), `OrderOrchestrator` (`start`, `dispatch` honouring RR-11, `enqueueRecovered`, `pause`, `resume`, `stationViews`, basic stop). Add `SqlErrors.isTransient`.
- [ ] 4.2 Wire `OrderService` to the real `OrderDispatcher`; queue-full compensation (TX4) with RR-10 retries and the **durable** fallback (TXC1 marker, `INDETERMINATE`, answer-after-durable DC-1, SDD §7.10); post-offer rule RR-1/RR-2; `cancelOrder` re-read loop RR-7; DUPLICATE with existing status; lease operations and fenced `transition` in `OrderService` (SDD §7.11.3); `SubmissionHook` test seam; metrics and in-flight guard integrated in `submitOrder`. (Indexes and columns come from the Phase 3 schema.)
- [ ] 4.2b `pipeline.WorkerMonitor` (thread `worker-monitor`: D1 died, D2 stalled, restart/reclaim per SDD §7.11.5, restart budget, DEAD, **queue reconciliation per §7.11.14** against the physical queue), `WorkerHook` seam (stages `AFTER_POLL`, `AFTER_LEASE`, `BEFORE_RENEW`, `BEFORE_STEP`, `AFTER_STEP`, `BEFORE_PARK_OFFER`), `ThreadPoolExecutor` sizing and self-naming workers, `StationWorker` per the complete-fencing rules F-1..F-8 (§7.11.12: only `acquireLease/renewLease/releaseLease/transitionAsOwner/failAsOwner`, first-statement rule, history actor `worker:<CODE>#<generation>`), lease rules L-1..L-7 (§7.11.13: renewal never revives, re-acquire vs stand down, retired workers never acquire), `activeIds`/`queuedIds` hints, `OrderOrchestrator.restartWorker`.
- [ ] 4.3 Test doubles: `BlockingOrderDispatcher`, `AlwaysFullOrderDispatcher`, `GatedOrderDispatcher`, `FaultInjectingOrderDao`, `CrashingSubmissionHook`, helpers `assertEachEdgeOnce`, `assertStockLedger`.
- [ ] 4.4 Tests: `OrderOrchestratorTest`, `StationWorkerTest`, `QueueFullCompensationTest`, `StockDeductionConcurrencyTest`, `DuplicateSubmissionConcurrencyTest`, `ConditionalTransitionRaceTest`, `CancelVsWorkerRaceTest`, `WorkerResilienceTest`, `WorkerInterruptTest`, `RoutingRaceTest`, `CompensationRaceTest`, `DuplicateQueueEntryTest`, `TerminalOrderNeverResumedTest`, `TransientFailureRecoveryTest`, `DurableCompensationTest`, `PendingCompensationRaceTest`, `LeaseFencingTest`, `WorkerMonitorTest`, `WorkerHangRecoveryTest`, `DeadWorkerRaceTest`, `WorkerMutationFencingTest`, `StaleWorkerWakeTest`, `LeaseExpiryReacquireTest`, `PollToLeaseCrashWindowTest`, `QueueReconcileTest`, `ReconcileLeaseSafetyTest`, `LostReplyIdempotenceTest`, `LeaseRenewalFreshStampTest`, `LeaseStampGuardTest` (concurrency tests `@RepeatedTest(20)`; the exact race windows RW-1..RW-11 are catalogued in [TESTING_STRATEGY §6.1](TESTING_STRATEGY.md)).

### Exit criteria
- [ ] All concurrency tests 20/20, three consecutive full builds green.
- [ ] Thread names `station-<code>-worker` observed; no leaked threads.
- [ ] Stock invariant checker passes after each concurrency test.
- [ ] No `Thread.sleep` used for synchronisation in tests.
- [ ] Routing-flow invariants I-1..I-8 ([SDD §7.8.2](SDD.md)) each demonstrated by at least one passing test; `assertStockLedger` and `assertEachEdgeOnce` used by every race test.
- [ ] A pending-compensation order is never leased, routed or processed (DB guards, `PendingCompensationRaceTest` 20/20).
- [ ] The lease stamp protocol of [SDD §7.11.15](SDD.md) holds: no lease DAO or `OrderService` method has a timestamp parameter; the stamp is the last action before execution; renewal after expiry fails and never revives (`LeaseRenewalFreshStampTest`); stalls beyond the guard budget roll back (`LeaseStampGuardTest`).
- [ ] The statement sequences of [SDD §7.13.1](SDD.md) (S1..S4) are asserted by the recording DAO; reconcile and sweeper perform zero writes on lease columns (`ReconcileLeaseSafetyTest`); lost replies after commit are harmless (`LostReplyIdempotenceTest`); DAO methods read the `Clock` internally (no `now` parameter for lease operations).
- [ ] Every worker mutation (WM-1..WM-9, SDD §7.11.12) is proven to change nothing under a stale epoch, expired/cleared lease or superseded owner (`WorkerMutationFencingTest`, `StaleWorkerWakeTest`), including zero statements after a 0-row first statement.
- [ ] A lease never revives after expiry, monitor clear or supersession; expiry without supersession re-acquires exactly once (`LeaseFencingTest`, `LeaseExpiryReacquireTest`).
- [ ] A worker killed right after `poll` or after the lease commit is recovered by one monitor pass without waiting for the orphan threshold (`PollToLeaseCrashWindowTest`).
- [ ] A killed worker and a hung worker are replaced, no order is lost, none is processed twice, each edge once (`WorkerMonitorTest`, `WorkerHangRecoveryTest`, `DeadWorkerRaceTest` 20/20).
- [ ] Scenario tables SDD §7.8.4-§7.8.9 match the observed behaviour (or the SDD is corrected with approval).

### Gate commands
```
./mvnw -q clean verify        # x3
./mvnw -q test -Dtest='OrderOrchestratorTest,StationWorkerTest,QueueFullCompensationTest,StockDeductionConcurrencyTest,DuplicateSubmissionConcurrencyTest,ConditionalTransitionRaceTest,CancelVsWorkerRaceTest,WorkerResilienceTest,WorkerInterruptTest,RoutingRaceTest,CompensationRaceTest,DuplicateQueueEntryTest,TerminalOrderNeverResumedTest,TransientFailureRecoveryTest,DurableCompensationTest,PendingCompensationRaceTest,LeaseFencingTest,WorkerMonitorTest,WorkerHangRecoveryTest,DeadWorkerRaceTest,WorkerMutationFencingTest,StaleWorkerWakeTest,LeaseExpiryReacquireTest,PollToLeaseCrashWindowTest,QueueReconcileTest,ReconcileLeaseSafetyTest,LostReplyIdempotenceTest,LeaseRenewalFreshStampTest,LeaseStampGuardTest'
```
Plus G-SECRET, G-TREE, G-TAG (`phase-4-complete`).

**Requirements verified:** FR-ORD-04, FR-ORD-11, FR-RTE-02, FR-CON-01..08, -10, -12, -14..19, FR-LIF-03 (race), FR-LIF-07, FR-INV-06 (race), NFR-REL-02.

---

## Phase 5: Resilience (simulator, recovery, shutdown, metrics)

**Goal:** the system survives crashes, restarts and shutdown; operators can generate load.
**Depends on:** Phase 4.

### Tasks
- [ ] 5.1 `pipeline.FeedSimulator` (`start`, `stop`, `setRate`, `isRunning`, `produced`).
- [ ] 5.2 `pipeline.RecoveryService` with the **startup order of SDD §7.10.6**: snapshot insert of missing orders, clear all leases, resolve pending compensations, then reload non-terminal orders excluding `PENDING`, enqueue oldest first with blocking put on `recovery-loader`; snapshot writer excludes pending orders (hook for Phase 6).
- [ ] 5.3 `pipeline.ShutdownCoordinator` (ordered idempotent steps, JVM hook, drain + `awaitTermination` + `shutdownNow`), orchestrator `shutdownGracefully`.
- [ ] 5.4 `bootstrap.AppContext` (full manual wiring per [SDD §2.4](SDD.md), start/close, recovery latch, `accepting` handling). `Launcher` remains the Phase 1 spike launcher.
- [ ] 5.5 `MetricsRegistry` finalised and invariant checked; `DashboardService` data side (`stationViews`, metrics view) available.
- [ ] 5.6 Tests: `FeedSimulatorTest`, `RecoveryServiceTest`, `ResumableProcessingTest`, `RoutingCrashWindowTest`, `ShutdownCoordinatorTest`, `GracefulShutdownTest`, `MetricsInvariantTest`.
- [ ] 5.7 Recovery per RR-9 (missing station -> FAILED `STATION_UNAVAILABLE`), shared helper `RecoveryService.reenqueueOrFail`.
- [ ] 5.8 `pipeline.OrphanSweeper` per [SDD §7.9](SDD.md) (approved, lease-based): DB lease proof (not `activeIds`), `queuedOnHealthyWorker`, durable pending-compensation retries with backoff and `COMPENSATION_STUCK`, configurable schedule keys and `AppConfig` cross-validation, started after recovery, stopped first at shutdown together with `WorkerMonitor`. Role: coarse backstop behind the monitor's reconcile pass (SDD §7.9 intro); decisions use the DB lease and the physical queue, never `activeIds`.
- [ ] 5.9 Tests: `OrphanSweeperTest`, `OrphanSweeperRaceTest`, `CompensationPendingSweepTest`, `PendingCompensationRestartTest`, `CompensationBackoffTest`, `CompensationOperatorTest`, `MonitorDeathSweeperRecoveryTest`.
- [ ] 5.10 `OrderService.retryCompensation(orderId, SessionUser admin)` per [SDD §7.10.11](SDD.md) (ADMIN-only, TX4, history actor `admin:<username>`, note `COMPENSATION_MANUAL`, idempotent, no bypass) and `cancelOrder` refusal for PENDING orders.

### Exit criteria
- [ ] Recovery after abrupt stop proven (orders in every non-terminal status complete after restart), including simulated crashes after TX1, after `offer`, after ROUTED and during compensation, with stock deducted/restored exactly once.
- [ ] With the worker monitor stopped, one sweeper pass restarts a killed worker (once, compare-and-set on the generation) and recovers the lost id (`MonitorDeathSweeperRecoveryTest`).
- [ ] Sweeper never re-enqueues queued/active/paused/parked/fresh/pending-compensation orders, never causes a duplicate edge or double restore (`OrphanSweeperRaceTest` 20/20).
- [ ] Shutdown triggered twice runs once; bounded by configured timeouts; no leaked threads.
- [ ] Metrics invariant holds at quiescence.
- [ ] Three consecutive full builds green; concurrency tests 20/20.

### Gate commands
```
./mvnw -q clean verify        # x3
./mvnw -q test -Dtest='FeedSimulatorTest,RecoveryServiceTest,ResumableProcessingTest,RoutingCrashWindowTest,OrphanSweeperTest,OrphanSweeperRaceTest,CompensationPendingSweepTest,PendingCompensationRestartTest,CompensationBackoffTest,CompensationOperatorTest,MonitorDeathSweeperRecoveryTest,ShutdownCoordinatorTest,GracefulShutdownTest,MetricsInvariantTest'
```
Plus G-SECRET, G-TREE, G-TAG (`phase-5-complete`).

**Requirements verified:** FR-RES-01..04, FR-RES-06..10, FR-CON-09 (final), NFR-REL-01 (service level), NFR-REL-03.

---

## Phase 6: File handling + serialization

**Goal:** structured log, CSV export, and signed/filtered snapshots integrated with recovery and shutdown.
**Depends on:** Phase 5.

### Tasks
- [ ] 6.1 `io.NioOrderEventLog` (NIO append-only, lock, sanitising); wire `OrderEventLog` calls at every event point in services/pipeline; production wiring in `AppContext`.
- [ ] 6.2 `io.CsvReportExporter`, `service.ReportService`.
- [ ] 6.3 `snapshot.OrderSnapshotDTO`, `HmacSigner`, `SnapshotFilterFactory`, `SnapshotService` (`writeSnapshot`, `readLatestValid`, `recoverMissingOrders`, `pruneOld`), `SnapshotTask`.
- [ ] 6.4 Integrate: `RecoveryService` calls `recoverMissingOrders()`; `ShutdownCoordinator` final snapshot step; periodic task started after recovery.
- [ ] 6.5 `logback.xml` finalised (thread name in pattern; no PII).
- [ ] 6.6 Documentation check: SDD §9 contains rationale, risks and versioning; confirm the allowlist pattern in the code and update SDD §9.4 if it differs.
- [ ] 6.7 Tests: `NioOrderEventLogTest`, `OrderEventLogConcurrencyTest`, `NoPiiInLogsTest`, `CsvReportExporterTest`, `HmacSignerTest`, `SnapshotRoundTripTest`, `SnapshotTamperTest`, `SnapshotFilterTest`, `SnapshotRecoveryTest`, `SnapshotTaskTest`.

### Exit criteria
- [ ] Tamper and filter tests prove rejection **before** deserialization.
- [ ] No PII/secret in any log output of a full run.
- [ ] `ObjectInputStream` appears only in package `snapshot` (`ArchitectureRulesTest`).
- [ ] Three consecutive full builds green; concurrency tests 20/20.
- [ ] FR-SER-07 documentation review ticked.

### Gate commands
```
./mvnw -q clean verify        # x3
./mvnw -q test -Dtest='NioOrderEventLogTest,OrderEventLogConcurrencyTest,NoPiiInLogsTest,CsvReportExporterTest,HmacSignerTest,SnapshotRoundTripTest,SnapshotTamperTest,SnapshotFilterTest,SnapshotRecoveryTest,SnapshotTaskTest'
git grep -n "ObjectInputStream" -- src/main
```
Plus G-SECRET, G-TREE, G-TAG (`phase-6-complete`).

**Requirements verified:** FR-FIL-01..04, FR-SER-01..07, FR-RES-05, NFR-OBS-01.

---

## Phase 7: Web layer

**Goal:** the complete MVC web application (MUST and SHOULD items) on embedded Tomcat and in the WAR.
**Depends on:** Phase 6 (and Phase 5 `AppContext`).

### Tasks
- [ ] 7.1 `web.xml` (servlets, filter order, listener, session config, error pages, `jsp-config`), `AppBootstrapListener`, final `Launcher` (replaces spike behaviour; spike JSP removed or moved under `test`).
- [ ] 7.2 `web.support`: `BaseServlet`, `CsrfTokens`, `Flash`, `RoutePolicy`.
- [ ] 7.3 `web.filter`: `EncodingFilter`, `SecurityHeadersFilter`, `CsrfFilter`, `AuthFilter` (per-request `validateSession`, fail closed, session invalidation), `RoleFilter` (role from `ck.currentUser`).
- [ ] 7.4 Controllers (MUST): `HomeServlet`, `ErrorServlet`, `LoginServlet`, `LogoutServlet`, `DashboardServlet`, `OrderListServlet`, `OrderDetailServlet`, `OrderCancelServlet`, `InventoryServlet`, `SimulatorServlet`.
- [ ] 7.5 Controllers (SHOULD): `OrderCreateServlet`, `AdminMenuServlet`, `AdminStockServlet`, `WorkerPanelServlet` (pause/resume/**restart**, shows DEAD/generation), `ReportServlet`, `SnapshotServlet`, `UserAdminServlet` (enable/disable only; password reset stays COULD); `DashboardService` and `MenuService`/`StockService` admin methods. `CompensationServlet` (`/admin/compensations`, ADMIN-only retry, PRG) and the order-page banner without a Cancel button for PENDING orders.
- [ ] 7.6 Views: JSPs, tag files, `app.css`; error pages; escaping everywhere; no scriptlets.
- [ ] 7.7 `TestApp`, `HttpTestClient`.
- [ ] 7.8 Tests: `LoginFlowWebTest`, `LockoutWebTest`, `SessionSecurityWebTest`, `CsrfWebTest`, `RoleAccessWebTest`, `OrderPrgWebTest`, `OrderDetailWebTest`, `DashboardWebTest`, `ErrorPagesWebTest`, `EncodingWebTest`, `SimulatorWebTest`, `AdminMenuStockWebTest`, `ReportWebTest`, `WorkerPanelWebTest`, `SecurityHeadersWebTest`, `XssEscapingWebTest`, `AccountDisableWebTest`, `CompensationOperatorWebTest`.
- [ ] 7.9 Manual usability checklist (NFR-USA-01) executed and recorded.

### Exit criteria
- [ ] `RoleAccessWebTest` covers every route in SDD §10.2 for all three actors.
- [ ] CSRF test covers every POST route.
- [ ] Error pages leak nothing.
- [ ] A disabled user's open session is rejected on the next GET and POST (`AccountDisableWebTest`).
- [ ] Dashboard shows worker generation/restarts and the count and age of orders awaiting compensation; the order page shows the "awaiting compensation" banner (`DashboardWebTest`, `OrderDetailWebTest`, `WorkerPanelWebTest`).
- [ ] `./mvnw exec:java` serves the full UI; manual smoke (login, simulator start, dashboard refresh, cancel, logout) recorded.
- [ ] WAR contains no embedded-server jars (`jar tf`).
- [ ] Three consecutive full builds green.

### Gate commands
```
./mvnw -q clean verify        # x3
./mvnw -q test -Dtest='*WebTest'
./mvnw -q package && jar tf target/*.war | grep -Ei 'tomcat-embed|mariadb4j' || echo "no embedded server jars in WAR"
./mvnw exec:java              # manual smoke, then Ctrl+C; capture log tail showing ordered shutdown
```
Plus G-SECRET, G-TREE, G-TAG (`phase-7-complete`).

**Requirements verified:** FR-WEB-14, FR-AUT-10, FR-AUT-11, FR-ORD-08, FR-LIF-04, FR-LIF-05 (UI), FR-INV-07 (UI), FR-INV-08, -09, FR-CON-10 (UI), FR-CON-11, FR-AUT-02, -03, -04, -06, -08, FR-WEB-01..10, -12, -13, NFR-USA-01.

---

## Phase 8: Integration audit + Review 2 pack

**Goal:** prove the whole system, close every gap, produce the review material. COULD items only if everything above is green.
**Depends on:** Phase 7.

### Tasks
- [ ] 8.1 Traceability audit: every MUST row `Done (<hash>)` with evidence; every SHOULD `Done` or `Deferred (<reason>)`; any `Conflict` resolved with the user.
- [ ] 8.2 Integration tests: `EndToEndLifecycleTest`, `CrashRecoveryTest`, `ThroughputSmokeTest`, `LauncherSmokeTest`.
- [ ] 8.3 Manual real crash drill: run the app, start the simulator, `kill -9` the JVM, restart, verify no accepted order is lost (queries + orders.log + dashboard); record in EVIDENCE.md.
- [ ] 8.4 Manual 5-minute load run at 60 orders/min; record metrics (NFR-PERF-01).
- [ ] 8.5 Security checklist walk-through (SDD §12.9) with evidence; secret scan over full history (`git log -p | grep` patterns) recorded.
- [ ] 8.6 Portability: run on the other available OS or mark unverified; MySQL 8 manual check or mark unverified; standalone Tomcat 10.1 WAR deployment check or mark unverified (NFR-PORT-01).
- [ ] 8.7 Concept coverage review: JAVA_CONCEPT_MAPPING checklist ticked with evidence; viva notes complete.
- [ ] 8.8 **COULD items** (only now, only if 8.1-8.7 are green): order search (FR-ORD-09), public tracking (FR-ORD-10), admin password reset incl. session invalidation (FR-AUT-09), dashboard charts (FR-WEB-11) with their tests (`OrderSearchWebTest`, `PublicTrackingWebTest`, `AdminPasswordResetWebTest`; charts covered in `DashboardWebTest`).
- [ ] 8.9 Review 2 pack, created now as `docs/REVIEW2_PACK.md`: architecture one-pager, demo script (step-by-step with expected output), evidence index, requirement coverage summary, known limitations, viva Q&A pulled from the notes.
- [ ] 8.10 `README.md` Quick start section appended (with user consent, A-08).
- [ ] 8.11 Final tags: `phase-8-complete` and `review2-ready`.

### Exit criteria
- [ ] Three consecutive `./mvnw -q clean verify` green on a **fresh clone** (clone to a new folder, run the commands from the README only).
- [ ] All concurrency tests 20/20 in the final run.
- [ ] Every MUST requirement `Done`; unverified platforms/environments declared honestly.
- [ ] Review 2 pack complete and consistent with EVIDENCE.md.

### Gate commands
```
git clone <repo> /tmp/fresh-clone && cd /tmp/fresh-clone && git checkout claude/cloud-kitchen-orchestration-7d1aok
./mvnw -q clean verify        # x3
./mvnw -q test -Dtest='EndToEndLifecycleTest,CrashRecoveryTest,ThroughputSmokeTest,LauncherSmokeTest'
./mvnw -q package && jar tf target/*.war | grep -Ei 'tomcat-embed|mariadb4j' || echo "no embedded server jars in WAR"
git grep -nIE '(password|secret)[[:space:]]*[:=][[:space:]]*"[^"]+"' -- src/main
```
Plus G-SECRET, G-TREE, G-TAG.

**Requirements verified:** NFR-REL-01 (full), NFR-PERF-01, NFR-PORT-01, NFR-TST-01, NFR-MNT-02, and the COULD requirements if implemented.

---

## Master checklist (tick when the phase gate is evidenced and approved)

- [x] Phase 0 documentation baseline complete and frozen (2026-10-10); committed as `992c1fe`
- [x] Phase 1 Skeleton + zero-install spike (local commit and tag `phase-1-complete`, not pushed; Linux-only evidence)
- [ ] Phase 2 Domain + services (no DB)
- [ ] Phase 3 Persistence
- [ ] Phase 4 Concurrent pipeline
- [ ] Phase 5 Resilience
- [ ] Phase 6 File handling + serialization
- [ ] Phase 7 Web layer
- [ ] Phase 8 Integration audit + Review 2 pack

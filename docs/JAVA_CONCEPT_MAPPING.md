# Java Concept Mapping

**Status:** Planned. Every class and method named here is a *planned* name from [SDD](SDD.md); none exists yet. A row becomes verifiable only when the class exists, its test passes and the evidence is in [EVIDENCE](EVIDENCE.md).

**Purpose:** for the viva, each mandatory Java concept points to exact classes/methods where it is demonstrated for a real reason (not a toy example), the test that proves it, and the phase that delivers it. Package prefix `com.cloudkitchen.` is omitted.

---

## 1. Concept to code mapping

### 1.1 Object-oriented concepts

| # | Concept | Exact planned class / method | How it appears in this project | Proving test | Phase |
|---|---|---|---|---|---|
| 1 | **Classes and objects** | `domain.Order` hierarchy, `domain.OrderItem`, `domain.Station`, `domain.MenuItem`, `domain.StockLevel`, `domain.StaffUser`; created by `domain.OrderFactory.create(OrderRequest, Station, MenuCatalog, Instant)` | Real entities with identity, state and behaviour; objects built by the factory and by the DAOs (`JdbcOrderDao.findById`) | `OrderFactoryTest`, `OrderPolymorphismTest` | 2 |
| 2 | **Encapsulation** | `Order` (private fields, constructor validation, `getItems()` / `getHistory()` return unmodifiable copies, no public setter for `id`/`source`/`externalRef`); `StaffUser.toString()` omits the hash; `AppConfig.toString()` masks secrets; `StockLevel.isLow()` derives state instead of exposing a flag | Invariants are protected: an `Order` cannot be built with zero items; passwords/secrets cannot leak through `toString()` | `OrderPolymorphismTest`, `AppConfigTest`, `NoPiiInLogsTest` | 2, 6 |
| 3 | **Abstraction** | Abstract class `domain.Order` (`calculateDeliveryEstimate()`, `processPayment()`); interfaces `pipeline.OrderDispatcher`, `io.OrderEventLog`, all `dao.*Dao`; `db.TxWork<T>` | Callers (`OrderService`, `StationWorker`) depend on abstractions, never on queues/executors/JDBC details | `OrderPolymorphismTest`, `OrderServiceTest` (with fake dispatcher) | 2-4 |
| 4 | **Inheritance** | `domain.SwiggyOrder extends Order`, `domain.ZomatoOrder extends Order`, `domain.InHouseOrder extends Order`; `exception.AccountLockedException extends AuthenticationException`; all domain exceptions extend `exception.CloudKitchenException`; every servlet extends `web.support.BaseServlet` | Shared state/behaviour in the parent (`getSubtotal()`, `basePreparation()`), differences in children | `OrderPolymorphismTest`, `OrderFactoryTest` | 2 |
| 5 | **Polymorphism (runtime)** | `Order.calculateDeliveryEstimate()` and `Order.processPayment()` invoked on an `Order` reference in `OrderFactory`/`OrderService.submitOrder` and `ReportService.exportDaily`; `OrderEventLog` implementations (`NioOrderEventLog` / `NoOpOrderEventLog`); `OrderDispatcher` implementations | One call site, behaviour chosen by the concrete source (different delivery estimate and platform fee per source) | `OrderPolymorphismTest`, `OrderServiceTest` | 2, 3 |
| 6 | **Interfaces** | `domain.contract.Trackable` (`getStatus()`, `getHistory()`, `getTrackingCode()`), `domain.contract.Payable` (`getPayableAmount()`, `processPayment()`), both implemented by `Order`; also `OrderDispatcher.dispatch(long, String)`, `OrderEventLog.log(...)`, `Runnable` (`StationWorker.run()`), `ThreadFactory` (`NamedThreadFactory.newThread`), `ShutdownCoordinator.ShutdownStep` | Contracts for tracking and paying, and seams that make tests possible without a mocking library | `OrderPolymorphismTest`, `ShutdownCoordinatorTest` | 2, 5 |
| 7 | **Abstract classes** | `domain.Order` (state + partial implementation + abstract methods); `web.support.BaseServlet extends HttpServlet` (abstract, template for rendering, flash, parameter parsing) | Abstract class used where shared state and partial behaviour exist (contrast with interfaces above) | `OrderPolymorphismTest`, `RoleAccessWebTest` | 2, 7 |
| 8 | **Method overloading** | `db.Database.inTransaction(TxWork<T>)` / `inTransaction(TxWork<T>, int maxRetries)`; `util.MoneyUtil.of(String)` / `of(long)` / `of(BigDecimal)`; `io.OrderEventLog.log(EventType)` / `log(EventType, long)` / `log(EventType, long, String)`; `service.PasswordHasher.hash(char[])` / `hash(char[], int)`; `service.OrderService.transition(...)` with and without a `Connection`; `io.CsvReportExporter.export(LocalDate, List)` / `export(LocalDate, List, Path)`; `domain.Order` constructors (full / minimal) | Overloads provide defaults vs explicit control, never ambiguous | `MoneyUtilTest`, `PasswordHasherTest`, `DatabaseTransactionTest`, `NioOrderEventLogTest`, `CsvReportExporterTest` | 2-6 |
| 9 | **Method overriding** | `SwiggyOrder` / `ZomatoOrder` / `InHouseOrder`: `calculateDeliveryEstimate()`, `processPayment()`, `toString()`; `Order.equals(Object)` / `hashCode()` (identity = `(source, externalRef)`); every servlet overrides `doGet` / `doPost`; every filter overrides `doFilter`; `StationWorker.run()` | `@Override` used everywhere; `equals`/`hashCode` contract tested | `OrderPolymorphismTest`, `OrderFactoryTest` | 2, 7 |

### 1.2 Core library concepts

| # | Concept | Exact planned class / method | How it appears | Proving test | Phase |
|---|---|---|---|---|---|
| 10 | **Collections: List** | `Order.items`, `Order.history`; `OrderDao.findNonTerminal(Connection)` returns `List<Order>`; `OrderQueryService.list(...)` returns a `Page<OrderSummary>` | Ordered, indexable data | `OrderDaoTest` | 3 |
| 11 | **Collections: Set** | `OrderStatus.allowedTargets()` returns an `EnumSet`; `OrderStatus.nonTerminal()`; `InFlightRegistry` backed by `ConcurrentHashMap.newKeySet()`; `RoutePolicy` role sets | Membership tests, thread-safe set for the in-flight guard | `OrderStatusTest`, `InFlightRegistryTest` | 2 |
| 12 | **Collections: Map** | `OrderStatus` transition table (`EnumMap`); `OrderDao.countByStatus` (`EnumMap`); `OrderService.submitOrder` builds a `TreeMap<Long,Integer>` (item id -> quantity) so deduction is in **sorted** order; `MenuItemDao.findByIds` returns `Map<Long,MenuItem>`; `RoutingService` station map | Sorted deterministic locking, fast lookup | `OrderStatusTest`, `OrderServiceTest` | 2, 3 |
| 13 | **Collections: Queue** | `pipeline.StationRuntime` holds `ArrayBlockingQueue<Long>`; `OrderOrchestrator.dispatch` uses `offer(e, timeout, unit)`; `StationWorker.run` uses `poll(timeout, unit)`; `RecoveryService` uses `put` | Bounded producer/consumer buffer with back-pressure | `OrderOrchestratorTest`, `QueueFullCompensationTest` | 4 |
| 14 | **Collections utilities** | `List.copyOf`, `Collections.unmodifiableList`, `Comparator.comparing(...)` in `RecoveryService` (oldest first), `List.sort` | Immutability and ordering | `RecoveryServiceTest` | 5 |
| 15 | **Generics** | `db.TxWork<T>` (`T run(Connection)`), `Database.inTransaction<T>(TxWork<T>)`, `dto.Page<T>`, `Optional<T>` returns in DAOs, `ArrayBlockingQueue<Long>`, `Map<OrderStatus,Long>` | Reusable transaction template; generic paging container | `DatabaseTransactionTest`, `OrderServiceTest` | 3 |
| 16 | **Enums** | `domain.enums.OrderStatus` (constructor-declared allowed targets, methods `canTransitionTo`, `requireTransitionTo`, `isTerminal`, `isStockRestorableTo`); `OrderSource`, `PaymentMode.allowedFor(OrderSource)`, `Role`, `WorkerState`, `io.EventType`, `SubmissionResult.Outcome` | Enums carry behaviour (the lifecycle rules live **inside** `OrderStatus`) | `OrderStatusTest` | 2 |
| 17 | **Custom exceptions** | `exception.*`: `ValidationException` (thrown by `OrderValidator.requireValid`), `InvalidStatusTransitionException` (`OrderStatus.requireTransitionTo`), `InsufficientStockException` (`OrderService` TX1), `DuplicateOrderException`, `QueueFullException` (`OrderOrchestrator.dispatch`), `PersistenceException` (`Database.inTransaction`), `AuthenticationException` / `AccountLockedException` (`AuthService.authenticate`), `SnapshotIntegrityException` (`SnapshotService.readLatestValid`), `ConfigurationException` (`AppConfig.load`) | Checked business exceptions with stable `code`; unchecked only for startup configuration | `OrderStatusTest`, `OrderServiceTest`, `SnapshotTamperTest`, `AppConfigTest` | 2-6 |

### 1.3 Concurrency, I/O, serialization, persistence

| # | Concept | Exact planned class / method | How it appears | Proving test | Phase |
|---|---|---|---|---|---|
| 18 | **Threads and executors** | `OrderOrchestrator.start(OrderService)` (fixed pool via `NamedThreadFactory`, thread names `station-<code>-worker`), `StationWorker.run()`, `FeedSimulator.start(int)` (`ScheduledExecutorService`), `SnapshotTask.start()` (scheduled), `RecoveryService.recover()` on `recovery-loader`, `OrphanSweeper.start()/runOnce()` (`ScheduledExecutorService`, thread `reconcile-scheduler`), `WorkerMonitor.run()` (thread `worker-monitor`) and `OrderOrchestrator.restartWorker` (`ThreadPoolExecutor`, `Future.cancel(true)`, `Thread.UncaughtExceptionHandler` via `NamedThreadFactory`), `ShutdownCoordinator.installJvmHook()` | One worker per station; producer/consumer pipeline | `OrderOrchestratorTest`, `FeedSimulatorTest`, `SnapshotTaskTest`, `OrphanSweeperTest` | 4-6 |
| 19 | **Concurrency control primitives** | `MetricsRegistry` (`AtomicLong`); `InFlightRegistry.tryAcquire` (`ConcurrentHashMap.newKeySet().add`); `NioOrderEventLog.log` (`ReentrantLock`); `FeedSimulator.start/stop/setRate` (`synchronized`); `StationWorker` flags (`volatile boolean paused, stopRequested`, `volatile WorkerState state`); `ShutdownCoordinator.shutdown` (`AtomicBoolean` + `CountDownLatch`); `StationRuntime.queuedOnHealthyWorker` (`ConcurrentHashMap.newKeySet()` / `ConcurrentHashMap<Long,Long>` hint sets `queuedIds`/`activeIds`); `StationWorker` `volatile` heartbeat and `retired` flag; `FencingToken` + `claim_epoch` fencing (database-level optimistic concurrency); `ArrayBlockingQueue` internal lock | Each shared mutable field has a named guard (SDD §7.1) | `MetricsRegistryTest`, `InFlightRegistryTest`, `OrderEventLogConcurrencyTest`, `ShutdownCoordinatorTest` | 2-6 |
| 20 | **Database-level concurrency control** | Leases and fencing: `JdbcOrderDao.acquireLease/renewLease/releaseLease/expireLeasesOf` and the epoch + owner + valid-lease predicate in the first statement of `OrderService.transitionAsOwner/failAsOwner` (optimistic concurrency with a fencing token), reconcile query `findReconcilable`; durable marker `markCompensationPending`; conditional `UPDATE` in `JdbcStockDao.deduct` (`stock_qty >= ?`), `JdbcOrderDao.transition` (`AND status = ?`), `claimStockRestore`; `SELECT ... FOR UPDATE` in `JdbcStaffUserDao.findByUsernameForUpdate`; deadlock retry in `Database.inTransaction` | Lock-free application code, atomicity by the database | `StockDeductionConcurrencyTest`, `ConditionalTransitionRaceTest`, `DeadlockRetryTest` | 3, 4 |
| 21 | **Interrupt handling** | `StationWorker.run`, `OrderOrchestrator.dispatch`, `Database.inTransaction` (backoff), `FeedSimulator.shutdown`, `ShutdownCoordinator.shutdown`: `catch (InterruptedException e) { Thread.currentThread().interrupt(); ... }` | Cooperative cancellation without losing the interrupt | `WorkerInterruptTest`, `ShutdownCoordinatorTest` | 4, 5 |
| 22 | **File I/O (NIO)** | `io.NioOrderEventLog` (`FileChannel.open(CREATE, APPEND, WRITE)`, `ByteBuffer`), `io.CsvReportExporter.export` (`Files.newBufferedWriter`), `snapshot.SnapshotService.writeSnapshot` (`Files.write`, `Files.move(ATOMIC_MOVE)`, directory listing for retention), `config.AppConfig.load` (`Files.newInputStream` + `Properties`) | Append-only structured log, report export, atomic snapshot files | `NioOrderEventLogTest`, `CsvReportExporterTest`, `SnapshotRoundTripTest`, `AppConfigTest` | 2, 6 |
| 23 | **Serialization** | `snapshot.OrderSnapshotDTO implements Serializable` (`serialVersionUID`, nested `ItemLine`); `SnapshotService.writeSnapshot` (`ObjectOutputStream`); `SnapshotService.readLatestValid` (`ObjectInputStream.setObjectInputFilter`); `SnapshotFilterFactory.create()` (`ObjectInputFilter`); `HmacSigner.sign/verify` (`Mac` HmacSHA256, `MessageDigest.isEqual`) | Controlled native serialization with integrity and allowlist (rationale, risks, versioning in SDD §9) | `SnapshotRoundTripTest`, `SnapshotTamperTest`, `SnapshotFilterTest`, `SnapshotRecoveryTest` | 6 |
| 24 | **JDBC with prepared statements** | All `dao.jdbc.Jdbc*Dao`: `Connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)`, bound parameters, `ResultSet` mapping, `JdbcOrderDao.insertItems` uses `addBatch()` / `executeBatch()` | No concatenated SQL; `ArchitectureRulesTest` enforces | `OrderDaoTest`, `StockDaoTest`, `ArchitectureRulesTest` | 3 |
| 25 | **JDBC transactions** | `Database.inTransaction` (`setAutoCommit(false)`, `commit`, `rollback`, restore auto-commit); `OrderService.submitOrder` = TX1; `OrderService.transition` = TX3; `AuthService.authenticate` = TX7; `AuthService.setEnabled` = TX10; workflow table in SDD §13.1 | Atomic multi-statement business operations with rollback and retry | `DatabaseTransactionTest`, `OrderServiceTest`, `DeadlockRetryTest` | 3 |
| 26 | **Connection pooling** | `db.DataSourceFactory.create` (HikariCP) | One pooled connection per task, none shared | `DatabaseTransactionTest` | 3 |

### 1.4 Web concepts

| # | Concept | Exact planned class / method | How it appears | Proving test | Phase |
|---|---|---|---|---|---|
| 27 | **Servlets** | `web.controller.*Servlet` (`doGet`, `doPost`), `web.support.BaseServlet`, `web.xml` mappings | Controllers of the MVC design (routes in SDD §10.2) | `RoleAccessWebTest`, `OrderPrgWebTest` | 7 |
| 28 | **Filters** | `EncodingFilter.doFilter`, `SecurityHeadersFilter`, `CsrfFilter`, `AuthFilter`, `RoleFilter` (chain order fixed in `web.xml`) | Cross-cutting web concerns | `CsrfWebTest`, `RoleAccessWebTest`, `SecurityHeadersWebTest`, `EncodingWebTest` | 7 |
| 29 | **Listeners** | `web.listener.AppBootstrapListener` (`contextInitialized`, `contextDestroyed`) | Application lifecycle bridge to `AppContext` and `ShutdownCoordinator` | `LauncherSmokeTest`, `GracefulShutdownTest` | 5, 7 |
| 30 | **JSP, EL, JSTL** | `/WEB-INF/views/**/*.jsp`, `layout/header.jspf` (`<%@ include %>`/`<jsp:include>`), tag files `csrf.tag`, `statusBadge.tag`; `<c:forEach>`, `<c:if>`, `<c:out>`, `<fmt:*>`, `<fn:escapeXml>` | Views without scriptlets; escaping by default | `DashboardWebTest`, `XssEscapingWebTest`, `ArchitectureRulesTest` | 1 (spike), 7 |
| 31 | **Sessions** | `LoginServlet.doPost` (`request.changeSessionId()`, `session.setAttribute("ck.user", SessionUser)`), `LogoutServlet.doPost` (`session.invalidate()`), `Flash` (session-scoped one-time messages), `CsrfTokens` (per-session token), `AuthFilter` + `AuthService.validateSession` (per-request account check, `SessionUser.sessionVersion`), `web.xml` `session-timeout` 30, `AppBootstrapListener` (`SessionCookieConfig`: HttpOnly, Secure, SameSite=Lax) | Authentication state, flash, CSRF token, timeout | `LoginFlowWebTest`, `SessionSecurityWebTest`, `OrderPrgWebTest` | 7 |

### 1.5 Bonus: language features and patterns used (not required, still worth knowing for the viva)

| Item | Where |
|---|---|
| Records (Java 17) | `PaymentResult`, `StatusHistoryEntry`, DTO views, `LockoutPolicy.LockoutState` |
| Lambdas / functional interface | `TxWork<T>` usage in services; `ShutdownStep`; scheduled tasks |
| Static nested class | `OrderSnapshotDTO.ItemLine` |
| Factory | `OrderFactory.create` |
| Null Object | `NoOpOrderEventLog` |
| Template Method | `BaseServlet`, `Order.basePreparation()` + abstract hooks |
| Dependency inversion by constructor injection | `OrderService(…, OrderDispatcher, …)` wired in `AppContext` |
| Immutable value objects | `OrderRequest` (defensive `List.copyOf`), `MenuCatalog` |

---

## 2. Concept coverage checklist (tick only when verified and evidenced)

- [ ] Classes/objects  - [ ] Encapsulation  - [ ] Abstraction  - [ ] Inheritance  - [ ] Polymorphism
- [ ] Interfaces  - [ ] Abstract classes  - [ ] Overloading  - [ ] Overriding
- [ ] Collections (List, Set, Map, queues)  - [ ] Generics  - [ ] Enums  - [ ] Custom exceptions
- [ ] Multithreading and concurrency control  - [ ] File I/O  - [ ] Serialization
- [ ] JDBC prepared statements and transactions  - [ ] Servlets  - [ ] JSP  - [ ] Sessions

---

## 3. Viva notes

> Empty on purpose. Claude Code adds concise, first-person-explainable notes at the end of each phase (what was built, why this design, one thing that went wrong and how it was fixed, likely examiner questions and answers). Do not pre-fill.

| Phase | Concept(s) covered | Viva note (what to say) | Likely questions and short answers | Date |
|---|---|---|---|---|
| 1 | JDBC and connection pooling (`DbSpikeTest`); transactions, deadlock and row locks (S-10, S-19); `BigDecimal` and `java.time` (S-05, S-06); class loaders (`Launcher`, `EmbeddedTomcatSpikeTest`); shutdown hook and `CountDownLatch` (`Launcher.RunningApp`); `ProcessHandle`; exceptions (`CloudKitchenException`); reflection in tests | **What I built:** the zero-install skeleton: Maven Wrapper, an embedded Tomcat that serves a servlet and a JSTL page, and an embedded MariaDB that I start and stop from Java. **Why:** to prove the whole stack works before writing domain code. **What went wrong:** (1) MariaDB4j starts the database with no password on every network interface; I measured it (a user with any name logged in) and replaced it with a two-step start that removes the anonymous accounts, creates one account and binds to 127.0.0.1. (2) The app returned 404 under `mvn exec:java` although tests passed, because `exec:java` loads classes in an isolated class loader; I wrote a test that recreates that loader and fixed the parent loader. (3) `main()` returned while the shutdown hook was still running; `await()` now waits on a latch released at the end of `close()`. (4) After Ctrl+C the hook's own log lines were missing: Logback stops itself when Tomcat destroys the web application; I found it by comparing a run with and without the failing web application, disabled that behaviour in the launcher and wrote `LauncherShutdownSpikeTest`, which starts the launcher in a child JVM and checks the hook's log lines. | **Q: Why a connection pool?** Opening a database connection is slow; HikariCP reuses a few, and each task borrows one and returns it (no shared `Connection`). **Q: How does the database avoid two workers changing the same row?** A conditional `UPDATE ... WHERE status = ?` returns 1 or 0 rows; a competing update waits for the row lock and then re-checks the condition (S-19, 20/20 runs). **Q: What is a deadlock and how do you detect it?** Two transactions each hold a lock the other needs; MariaDB aborts one with error 1213 and the code retries; I check the vendor code 1213, because a lock wait timeout (1205) also reports SQLState 40001. **Q: Why `BigDecimal` for money?** `double` cannot represent 0.01 exactly; the spike round-trips 0.01, 19.99 and 99999999.99 through `DECIMAL(10,2)` unchanged. **Q: What does a shutdown hook do?** It runs when the JVM is stopped (Ctrl+C) and stops Tomcat and then the database in order. | 2026-10-10 |
| 2 | | | | |
| 3 | | | | |
| 4 | | | | |
| 5 | | | | |
| 6 | | | | |
| 7 | | | | |
| 8 | | | | |

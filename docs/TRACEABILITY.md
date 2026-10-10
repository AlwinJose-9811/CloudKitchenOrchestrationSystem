# Traceability Matrix

**Status of every row: Planned.** Nothing is implemented. Do not change a status to *Done* without a passing test **and** a matching entry in [EVIDENCE.md](EVIDENCE.md).

**How to use (every phase):**
1. At phase start, read the rows whose *Phase* equals the current phase.
2. When a row's code and tests exist and pass, change *Status* to `Done (<short commit hash>)` and add the EVIDENCE.md entry id.
3. Statuses allowed: `Planned` -> `In progress` -> `Done (<hash>)`, or `Deferred (<reason>)` for COULD items, or `Conflict (<note>)` when code and docs disagree and the user has not yet decided.
4. If a planned file or test name changes, update this table, [SDD.md](SDD.md) and [TESTING_STRATEGY.md](TESTING_STRATEGY.md) together.

Requirement text and acceptance criteria: [SRS.md](SRS.md). Design references (`SDD §n`) point to [SDD.md](SDD.md). Total requirements: **95**.

Path conventions: main code `src/main/java/com/cloudkitchen/...`, tests `src/test/java/com/cloudkitchen/...`. Entries in parentheses are not automated files (documentation, manual checks, gate commands).

## Requirement to design to file to test

| Requirement | Pri | Title | Design | Planned file(s) | Planned test(s) | Phase | Status |
|---|---|---|---|---|---|---|---|
| FR-ENV-01 | MUST | Zero-install build and run | SDD §2.4, §15 | `pom.xml`<br>`mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties`<br>`src/main/java/com/cloudkitchen/bootstrap/Launcher.java` | `EmbeddedTomcatSpikeTest`<br>`LauncherSmokeTest` | 1 | Planned |
| FR-ENV-02 | MUST | Embedded MySQL-compatible database | SDD §2.4, §15 | `src/main/java/com/cloudkitchen/bootstrap/DbBootstrap.java`<br>`src/main/java/com/cloudkitchen/bootstrap/AppContext.java` | `DbSpikeTest`<br>`LauncherSmokeTest` | 1 | Planned |
| FR-ENV-03 | MUST | Standard WAR | SDD §2.4, §10.1 | `pom.xml`<br>`src/main/webapp/WEB-INF/web.xml`<br>`src/main/java/com/cloudkitchen/web/listener/AppBootstrapListener.java` | (gate command: jar tf check, recorded in EVIDENCE.md) | 1 | Planned |
| FR-ENV-04 | MUST | Portable SQL, config-only switch to MySQL 8 | SDD §5.1; DATABASE_DESIGN §1 | `src/main/resources/db/V001__schema.sql`<br>`src/main/resources/db/V002__seed_reference_data.sql`<br>`src/main/java/com/cloudkitchen/bootstrap/SchemaInitializer.java` | `SqlPortabilityTest`<br>`SchemaInitializerTest` | 3 | Planned |
| FR-ENV-05 | MUST | Configuration from git-ignored file | SDD §15 | `src/main/java/com/cloudkitchen/config/AppConfig.java`<br>`config/application.properties.example`<br>`src/main/java/com/cloudkitchen/exception/ConfigurationException.java` | `AppConfigTest` | 2 | Planned |
| FR-ENV-06 | MUST | Money, time and character-set rules | SDD §4.4, §13; DATABASE_DESIGN §1 | `src/main/java/com/cloudkitchen/util/MoneyUtil.java`<br>`src/main/java/com/cloudkitchen/util/TimeUtil.java`<br>`src/main/resources/db/V001__schema.sql` | `MoneyUtilTest`<br>`OrderDaoTest`<br>`ArchitectureRulesTest` | 3 | Planned |
| FR-ORD-01 | MUST | Single submission entry point | SDD §5.3, §6 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/dto/OrderRequest.java`<br>`src/main/java/com/cloudkitchen/dto/SubmissionResult.java` | `OrderServiceTest`<br>`EndToEndLifecycleTest` | 3 | Planned |
| FR-ORD-02 | MUST | Pure validation | SDD §11 | `src/main/java/com/cloudkitchen/service/OrderValidator.java`<br>`src/main/java/com/cloudkitchen/service/MenuCatalog.java`<br>`src/main/java/com/cloudkitchen/dto/ValidationResult.java`<br>`src/main/java/com/cloudkitchen/dto/FieldError.java` | `OrderValidatorTest`<br>`ArchitectureRulesTest` | 2 | Planned |
| FR-ORD-03 | MUST | Server-side pricing | SDD §4.4, §11 | `src/main/java/com/cloudkitchen/service/OrderValidator.java`<br>`src/main/java/com/cloudkitchen/domain/Order.java`<br>`src/main/java/com/cloudkitchen/util/MoneyUtil.java` | `OrderValidatorTest`<br>`OrderServiceTest` | 2 | Planned |
| FR-ORD-04 | MUST | Idempotent submission | SDD §7.4, §13 | `src/main/java/com/cloudkitchen/pipeline/InFlightRegistry.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/exception/DuplicateOrderException.java` | `InFlightRegistryTest`<br>`DuplicateSubmissionConcurrencyTest`<br>`OrderDaoTest` | 4 | Planned |
| FR-ORD-05 | MUST | Source-specific behaviour (polymorphism) | SDD §4.2, §4.4 | `src/main/java/com/cloudkitchen/domain/Order.java`<br>`src/main/java/com/cloudkitchen/domain/SwiggyOrder.java`<br>`src/main/java/com/cloudkitchen/domain/ZomatoOrder.java`<br>`src/main/java/com/cloudkitchen/domain/InHouseOrder.java`<br>`src/main/java/com/cloudkitchen/domain/OrderFactory.java`<br>`src/main/java/com/cloudkitchen/domain/PaymentResult.java` | `OrderPolymorphismTest`<br>`OrderFactoryTest` | 2 | Planned |
| FR-ORD-06 | MUST | One brand per order | SDD §11 | `src/main/java/com/cloudkitchen/service/OrderValidator.java`<br>`src/main/java/com/cloudkitchen/service/RoutingService.java` | `OrderValidatorTest`<br>`RoutingServiceTest` | 2 | Planned |
| FR-ORD-07 | MUST | Rejection handling | SDD §6.2, §14 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/dto/SubmissionResult.java`<br>`src/main/java/com/cloudkitchen/pipeline/MetricsRegistry.java` | `OrderServiceTest`<br>`MetricsInvariantTest` | 3 | Planned |
| FR-ORD-08 | SHOULD | In-house order entry form | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/OrderCreateServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `OrderPrgWebTest` | 7 | Planned |
| FR-ORD-09 | COULD | Order search | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/OrderListServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderQueryService.java`<br>`src/main/java/com/cloudkitchen/dao/OrderDao.java` | `OrderSearchWebTest` | 8 | Planned |
| FR-ORD-10 | COULD | Public tracking page | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/TrackServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderQueryService.java` | `PublicTrackingWebTest` | 8 | Planned |
| FR-LIF-01 | MUST | Lifecycle enforced in OrderStatus | SDD §4.3 | `src/main/java/com/cloudkitchen/domain/enums/OrderStatus.java`<br>`src/main/java/com/cloudkitchen/exception/InvalidStatusTransitionException.java` | `OrderStatusTest` | 2 | Planned |
| FR-LIF-02 | MUST | Terminal statuses | SDD §4.3 | `src/main/java/com/cloudkitchen/domain/enums/OrderStatus.java` | `OrderStatusTest` | 2 | Planned |
| FR-LIF-03 | MUST | Conditional, audited transitions | SDD §7.4, §13 | `src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStatusHistoryDao.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java` | `ConditionalTransitionRaceTest`<br>`OrderDaoTest`<br>`StatusHistoryDaoTest` | 3 | Planned |
| FR-LIF-04 | MUST | Status history visible | SDD §10.5 | `src/main/java/com/cloudkitchen/web/controller/OrderDetailServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderQueryService.java`<br>`src/main/java/com/cloudkitchen/domain/StatusHistoryEntry.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `OrderDetailWebTest`<br>`StatusHistoryDaoTest` | 7 | Planned |
| FR-LIF-05 | MUST | Cancellation | SDD §6.4, §10.2 | `src/main/java/com/cloudkitchen/web/controller/OrderCancelServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/domain/enums/OrderStatus.java` | `OrderServiceTest`<br>`CancelVsWorkerRaceTest`<br>`OrderDetailWebTest` | 3 | Planned |
| FR-LIF-06 | MUST | Trackable and Payable contracts | SDD §4.2 | `src/main/java/com/cloudkitchen/domain/contract/Trackable.java`<br>`src/main/java/com/cloudkitchen/domain/contract/Payable.java`<br>`src/main/java/com/cloudkitchen/domain/Order.java` | `OrderPolymorphismTest` | 2 | Planned |
| FR-INV-01 | MUST | Item-level stock per station | DATABASE_DESIGN §3 | `src/main/resources/db/V001__schema.sql`<br>`src/main/resources/db/V002__seed_reference_data.sql`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java`<br>`src/main/java/com/cloudkitchen/domain/StockLevel.java` | `StockDaoTest`<br>`SchemaInitializerTest` | 3 | Planned |
| FR-INV-02 | MUST | Atomic conditional deduction | SDD §13 | `src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java` | `StockDaoTest`<br>`StockDeductionConcurrencyTest` | 3 | Planned |
| FR-INV-03 | MUST | Deterministic lock order | SDD §13 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java` | `OrderServiceTest`<br>`DeadlockRetryTest` | 3 | Planned |
| FR-INV-04 | MUST | Deadlock retry | SDD §5.1, §13 | `src/main/java/com/cloudkitchen/db/Database.java`<br>`src/main/java/com/cloudkitchen/db/SqlErrors.java` | `DeadlockRetryTest`<br>`DatabaseTransactionTest` | 3 | Planned |
| FR-INV-05 | MUST | All-or-nothing deduction | SDD §13 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/exception/InsufficientStockException.java` | `OrderServiceTest`<br>`StockDeductionConcurrencyTest` | 3 | Planned |
| FR-INV-06 | MUST | Conditional stock restore | SDD §6.4, §13 | `src/main/java/com/cloudkitchen/domain/enums/OrderStatus.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java` | `OrderStatusTest`<br>`OrderServiceTest`<br>`CancelVsWorkerRaceTest` | 3 | Planned |
| FR-INV-07 | MUST | Low-stock flag | SDD §10.5 | `src/main/java/com/cloudkitchen/service/StockService.java`<br>`src/main/java/com/cloudkitchen/service/DashboardService.java`<br>`src/main/java/com/cloudkitchen/dto/StockRow.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `StockDaoTest`<br>`DashboardWebTest` | 3 | Planned |
| FR-INV-08 | SHOULD | Admin menu management | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/AdminMenuServlet.java`<br>`src/main/java/com/cloudkitchen/service/MenuService.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcMenuItemDao.java` | `MenuItemDaoTest`<br>`AdminMenuStockWebTest` | 7 | Planned |
| FR-INV-09 | SHOULD | Admin stock management | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/AdminStockServlet.java`<br>`src/main/java/com/cloudkitchen/service/StockService.java` | `AdminMenuStockWebTest`<br>`StockDaoTest` | 7 | Planned |
| FR-RTE-01 | MUST | One station per brand | SDD §5.3, §7.2 | `src/main/java/com/cloudkitchen/service/RoutingService.java`<br>`src/main/java/com/cloudkitchen/domain/Station.java` | `RoutingServiceTest`<br>`OrderOrchestratorTest` | 2 | Planned |
| FR-RTE-02 | MUST | Post-commit routing | SDD §6.1, §6.2 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderDispatcher.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java` | `OrderOrchestratorTest`<br>`OrderServiceTest` | 4 | Planned |
| FR-CON-01 | MUST | Bounded station queues | SDD §7.2 | `src/main/java/com/cloudkitchen/pipeline/StationRuntime.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java` | `OrderOrchestratorTest` | 4 | Planned |
| FR-CON-02 | MUST | One worker per station | SDD §7.1, §7.2 | `src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java`<br>`src/main/java/com/cloudkitchen/pipeline/StationWorker.java`<br>`src/main/java/com/cloudkitchen/pipeline/NamedThreadFactory.java` | `OrderOrchestratorTest` | 4 | Planned |
| FR-CON-03 | MUST | Queue-full compensation | SDD §6.3, §14 | `src/main/java/com/cloudkitchen/service/OrderService.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java`<br>`src/main/java/com/cloudkitchen/exception/QueueFullException.java` | `QueueFullCompensationTest` | 4 | Planned |
| FR-CON-04 | MUST | Worker fault isolation | SDD §7.3 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java` | `WorkerResilienceTest` | 4 | Planned |
| FR-CON-05 | MUST | Interrupt handling | SDD §7.3, §7.6 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java`<br>`src/main/java/com/cloudkitchen/pipeline/FeedSimulator.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java` | `WorkerInterruptTest`<br>`ShutdownCoordinatorTest` | 4 | Planned |
| FR-CON-06 | MUST | No shared connections | SDD §5.1, §7.1 | `src/main/java/com/cloudkitchen/db/Database.java`<br>`src/main/java/com/cloudkitchen/db/DataSourceFactory.java` | `DatabaseTransactionTest`<br>`ArchitectureRulesTest` | 3 | Planned |
| FR-CON-07 | MUST | Harmless duplicate processing | SDD §7.4 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java` | `ConditionalTransitionRaceTest`<br>`StationWorkerTest` | 4 | Planned |
| FR-CON-08 | MUST | Claim race tolerance | SDD §7.4 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java` | `StationWorkerTest` | 4 | Planned |
| FR-CON-09 | MUST | Metrics | SDD §7.7 | `src/main/java/com/cloudkitchen/pipeline/MetricsRegistry.java`<br>`src/main/java/com/cloudkitchen/dto/MetricsView.java` | `MetricsRegistryTest`<br>`MetricsInvariantTest` | 2 | Planned |
| FR-CON-10 | MUST | Worker states | SDD §7.3 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java`<br>`src/main/java/com/cloudkitchen/domain/enums/WorkerState.java`<br>`src/main/java/com/cloudkitchen/pipeline/StationRuntime.java` | `StationWorkerTest`<br>`DashboardWebTest` | 4 | Planned |
| FR-CON-11 | SHOULD | Pause and resume workers | SDD §7.3, §10.2 | `src/main/java/com/cloudkitchen/web/controller/WorkerPanelServlet.java`<br>`src/main/java/com/cloudkitchen/pipeline/StationWorker.java` | `WorkerPanelWebTest`<br>`StationWorkerTest` | 7 | Planned |
| FR-RES-01 | MUST | Feed simulator | SDD §7.5 | `src/main/java/com/cloudkitchen/pipeline/FeedSimulator.java`<br>`src/main/java/com/cloudkitchen/web/controller/SimulatorServlet.java` | `FeedSimulatorTest`<br>`SimulatorWebTest` | 5 | Planned |
| FR-RES-02 | MUST | Startup recovery from DB | SDD §6.5 | `src/main/java/com/cloudkitchen/pipeline/RecoveryService.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java` | `RecoveryServiceTest`<br>`CrashRecoveryTest` | 5 | Planned |
| FR-RES-03 | MUST | Resumable processing | SDD §7.3 | `src/main/java/com/cloudkitchen/pipeline/StationWorker.java` | `ResumableProcessingTest` | 5 | Planned |
| FR-RES-04 | MUST | Graceful shutdown | SDD §7.6 | `src/main/java/com/cloudkitchen/pipeline/ShutdownCoordinator.java`<br>`src/main/java/com/cloudkitchen/web/listener/AppBootstrapListener.java`<br>`src/main/java/com/cloudkitchen/bootstrap/AppContext.java` | `ShutdownCoordinatorTest`<br>`GracefulShutdownTest`<br>`LauncherSmokeTest` | 5 | Planned |
| FR-RES-05 | MUST | Scheduled snapshot task | SDD §9 | `src/main/java/com/cloudkitchen/snapshot/SnapshotTask.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotService.java` | `SnapshotTaskTest` | 6 | Planned |
| FR-FIL-01 | MUST | Structured orders.log | SDD §8 | `src/main/java/com/cloudkitchen/io/NioOrderEventLog.java`<br>`src/main/java/com/cloudkitchen/io/OrderEventLog.java`<br>`src/main/java/com/cloudkitchen/io/EventType.java` | `NioOrderEventLogTest`<br>`OrderEventLogConcurrencyTest` | 6 | Planned |
| FR-FIL-02 | MUST | No PII or secrets in logs | SDD §8, §12 | `src/main/java/com/cloudkitchen/io/NioOrderEventLog.java`<br>`src/main/java/com/cloudkitchen/config/AppConfig.java`<br>`src/main/resources/logback.xml` | `NoPiiInLogsTest`<br>`AppConfigTest` | 6 | Planned |
| FR-FIL-03 | SHOULD | CSV daily report | SDD §8 | `src/main/java/com/cloudkitchen/io/CsvReportExporter.java`<br>`src/main/java/com/cloudkitchen/service/ReportService.java`<br>`src/main/java/com/cloudkitchen/web/controller/ReportServlet.java`<br>`src/main/java/com/cloudkitchen/dto/ReportRow.java` | `CsvReportExporterTest`<br>`ReportWebTest` | 6 | Planned |
| FR-FIL-04 | MUST | Paths from configuration | SDD §8, §15 | `src/main/java/com/cloudkitchen/config/AppConfig.java`<br>`src/main/java/com/cloudkitchen/io/NioOrderEventLog.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotService.java`<br>`src/main/java/com/cloudkitchen/io/CsvReportExporter.java` | `AppConfigTest`<br>`NioOrderEventLogTest` | 6 | Planned |
| FR-SER-01 | MUST | OrderSnapshotDTO | SDD §9 | `src/main/java/com/cloudkitchen/snapshot/OrderSnapshotDTO.java` | `SnapshotRoundTripTest` | 6 | Planned |
| FR-SER-02 | MUST | Periodic and shutdown snapshots | SDD §9 | `src/main/java/com/cloudkitchen/snapshot/SnapshotService.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotTask.java`<br>`src/main/java/com/cloudkitchen/pipeline/ShutdownCoordinator.java` | `SnapshotRoundTripTest`<br>`GracefulShutdownTest` | 6 | Planned |
| FR-SER-03 | MUST | HMAC-signed snapshots | SDD §9 | `src/main/java/com/cloudkitchen/snapshot/HmacSigner.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotService.java`<br>`src/main/java/com/cloudkitchen/exception/SnapshotIntegrityException.java` | `HmacSignerTest`<br>`SnapshotTamperTest` | 6 | Planned |
| FR-SER-04 | MUST | ObjectInputFilter allowlist | SDD §9 | `src/main/java/com/cloudkitchen/snapshot/SnapshotFilterFactory.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotService.java` | `SnapshotFilterTest` | 6 | Planned |
| FR-SER-05 | MUST | Snapshot recovery | SDD §6.5, §9 | `src/main/java/com/cloudkitchen/snapshot/SnapshotService.java`<br>`src/main/java/com/cloudkitchen/pipeline/RecoveryService.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java` | `SnapshotRecoveryTest`<br>`RecoveryServiceTest` | 6 | Planned |
| FR-SER-06 | MUST | Serialization safety rules | SDD §9, §12 | `src/main/java/com/cloudkitchen/snapshot/SnapshotService.java`<br>`src/main/java/com/cloudkitchen/snapshot/OrderSnapshotDTO.java` | `ArchitectureRulesTest`<br>`SnapshotRoundTripTest` | 6 | Planned |
| FR-SER-07 | MUST | Serialization documentation | SDD §9 | (documentation) | (review checklist in IMPLEMENTATION_ROADMAP Phase 6) | 6 | Planned |
| FR-AUT-01 | MUST | Login and password storage | SDD §12 | `src/main/java/com/cloudkitchen/service/PasswordHasher.java`<br>`src/main/java/com/cloudkitchen/service/AuthService.java`<br>`src/main/java/com/cloudkitchen/config/AppConfig.java` | `PasswordHasherTest`<br>`AuthServiceTest`<br>`AppConfigTest` | 2 | Planned |
| FR-AUT-02 | MUST | Session fixation protection | SDD §12 | `src/main/java/com/cloudkitchen/web/controller/LoginServlet.java`<br>`src/main/java/com/cloudkitchen/web/support/CsrfTokens.java` | `LoginFlowWebTest` | 7 | Planned |
| FR-AUT-03 | MUST | Session timeout | SDD §10.4, §12 | `src/main/webapp/WEB-INF/web.xml` | `SessionSecurityWebTest` | 7 | Planned |
| FR-AUT-04 | MUST | Logout | SDD §10.2, §12 | `src/main/java/com/cloudkitchen/web/controller/LogoutServlet.java` | `LoginFlowWebTest`<br>`SessionSecurityWebTest` | 7 | Planned |
| FR-AUT-05 | MUST | Account lockout | SDD §12; DATABASE_DESIGN §3.7 | `src/main/java/com/cloudkitchen/service/AuthService.java`<br>`src/main/java/com/cloudkitchen/service/LockoutPolicy.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStaffUserDao.java` | `LockoutPolicyTest`<br>`AuthServiceTest`<br>`StaffUserDaoTest`<br>`LockoutWebTest` | 3 | Planned |
| FR-AUT-06 | MUST | Roles and access filters | SDD §10.2, §10.3, §10.6 | `src/main/java/com/cloudkitchen/web/filter/AuthFilter.java`<br>`src/main/java/com/cloudkitchen/web/filter/RoleFilter.java`<br>`src/main/java/com/cloudkitchen/web/support/RoutePolicy.java`<br>`src/main/java/com/cloudkitchen/domain/enums/Role.java` | `RoleAccessWebTest` | 7 | Planned |
| FR-AUT-07 | MUST | Initial users from config | SDD §12, §15 | `src/main/java/com/cloudkitchen/bootstrap/UserSeeder.java`<br>`src/main/java/com/cloudkitchen/config/AppConfig.java` | `UserSeederTest` | 3 | Planned |
| FR-AUT-08 | MUST | Cookie attributes | SDD §12 | `src/main/java/com/cloudkitchen/web/listener/AppBootstrapListener.java` | `SessionSecurityWebTest` | 7 | Planned |
| FR-AUT-09 | COULD | Admin password reset | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/UserAdminServlet.java`<br>`src/main/java/com/cloudkitchen/service/AuthService.java` | `AdminPasswordResetWebTest` | 8 | Planned |
| FR-WEB-01 | MUST | MVC separation | SDD §10.1 | `src/main/java/com/cloudkitchen/web/support/BaseServlet.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `ArchitectureRulesTest`<br>`RoleAccessWebTest` | 7 | Planned |
| FR-WEB-02 | MUST | POST-redirect-GET with flash | SDD §10.4 | `src/main/java/com/cloudkitchen/web/support/BaseServlet.java`<br>`src/main/java/com/cloudkitchen/web/support/Flash.java` | `OrderPrgWebTest` | 7 | Planned |
| FR-WEB-03 | MUST | CSRF protection | SDD §10.3, §12 | `src/main/java/com/cloudkitchen/web/filter/CsrfFilter.java`<br>`src/main/java/com/cloudkitchen/web/support/CsrfTokens.java` | `CsrfWebTest` | 7 | Planned |
| FR-WEB-04 | MUST | UTF-8 everywhere | SDD §10.3 | `src/main/java/com/cloudkitchen/web/filter/EncodingFilter.java` | `EncodingWebTest` | 7 | Planned |
| FR-WEB-05 | MUST | Generic error pages | SDD §10.5, §12 | `src/main/webapp/WEB-INF/web.xml`<br>`src/main/webapp/WEB-INF/views/error/{400,403,404,500}.jsp` | `ErrorPagesWebTest` | 7 | Planned |
| FR-WEB-06 | MUST | Dashboard | SDD §10.5 | `src/main/java/com/cloudkitchen/web/controller/DashboardServlet.java`<br>`src/main/java/com/cloudkitchen/service/DashboardService.java`<br>`src/main/java/com/cloudkitchen/dto/DashboardView.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `DashboardWebTest` | 7 | Planned |
| FR-WEB-07 | MUST | Order list and detail | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/OrderListServlet.java`<br>`src/main/java/com/cloudkitchen/web/controller/OrderDetailServlet.java`<br>`src/main/java/com/cloudkitchen/service/OrderQueryService.java`<br>`src/main/java/com/cloudkitchen/dto/OrderSummary.java` | `OrderDetailWebTest`<br>`OrderPrgWebTest` | 7 | Planned |
| FR-WEB-08 | MUST | Output escaping | SDD §12 | `src/main/webapp/WEB-INF/views/**/*.jsp` | `XssEscapingWebTest` | 7 | Planned |
| FR-WEB-09 | MUST | Simulator control page | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/SimulatorServlet.java`<br>`src/main/java/com/cloudkitchen/pipeline/FeedSimulator.java` | `SimulatorWebTest` | 7 | Planned |
| FR-WEB-10 | SHOULD | Worker panel | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/WorkerPanelServlet.java`<br>`src/main/java/com/cloudkitchen/dto/StationView.java`<br>`src/main/java/com/cloudkitchen/dto/WorkerView.java` | `WorkerPanelWebTest` | 7 | Planned |
| FR-WEB-11 | COULD | Dashboard charts | SDD §10.5 | `src/main/java/com/cloudkitchen/web/controller/DashboardServlet.java`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | `DashboardWebTest` | 8 | Planned |
| FR-WEB-12 | MUST | Security headers | SDD §10.3, §12 | `src/main/java/com/cloudkitchen/web/filter/SecurityHeadersFilter.java` | `SecurityHeadersWebTest` | 7 | Planned |
| FR-WEB-13 | SHOULD | Snapshot on demand | SDD §10.2 | `src/main/java/com/cloudkitchen/web/controller/SnapshotServlet.java`<br>`src/main/java/com/cloudkitchen/snapshot/SnapshotService.java` | `SimulatorWebTest` | 7 | Planned |
| NFR-SEC-01 | MUST | No secrets in the repository | SDD §12, §15 | `.gitignore additions (see Phase 1)`<br>`config/application.properties.example` | (gate: secret scan command, EVIDENCE.md) | 1 | Planned |
| NFR-SEC-02 | MUST | Prepared statements only | SDD §12 | `src/main/java/com/cloudkitchen/dao/jdbc/JdbcOrderDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStaffUserDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcMenuItemDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStatusHistoryDao.java`<br>`src/main/java/com/cloudkitchen/dao/jdbc/JdbcStationDao.java` | `ArchitectureRulesTest` | 3 | Planned |
| NFR-SEC-03 | MUST | Authentication errors are uniform | SDD §12 | `src/main/java/com/cloudkitchen/service/AuthService.java`<br>`src/main/java/com/cloudkitchen/service/PasswordHasher.java` | `AuthServiceTest`<br>`LockoutWebTest` | 3 | Planned |
| NFR-REL-01 | MUST | No lost orders | SDD §6.5, §14 | `src/main/java/com/cloudkitchen/pipeline/RecoveryService.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java` | `CrashRecoveryTest`<br>`RecoveryServiceTest` | 5 | Planned |
| NFR-REL-02 | MUST | Stock integrity | SDD §13 | `src/main/java/com/cloudkitchen/dao/jdbc/JdbcStockDao.java`<br>`src/main/java/com/cloudkitchen/service/OrderService.java` | `StockDeductionConcurrencyTest`<br>`EndToEndLifecycleTest` | 4 | Planned |
| NFR-REL-03 | MUST | Bounded shutdown | SDD §7.6 | `src/main/java/com/cloudkitchen/pipeline/ShutdownCoordinator.java` | `ShutdownCoordinatorTest`<br>`GracefulShutdownTest` | 5 | Planned |
| NFR-PERF-01 | SHOULD | Sustained simulated load | SDD §7.5 | `src/main/java/com/cloudkitchen/pipeline/FeedSimulator.java`<br>`src/main/java/com/cloudkitchen/pipeline/OrderOrchestrator.java` | `ThroughputSmokeTest` | 8 | Planned |
| NFR-PORT-01 | MUST | Cross-platform | SDD §2.4 | `mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties`<br>`pom.xml` | (manual, recorded in EVIDENCE.md) | 1 | Planned |
| NFR-MNT-01 | MUST | Layering rules | SDD §2.2 | (all packages) | `ArchitectureRulesTest` | 2 | Planned |
| NFR-MNT-02 | MUST | Docs stay the source of truth | CLAUDE.md | (documentation) | (phase exit checklist) | 1 | Planned |
| NFR-TST-01 | MUST | Deterministic tests, no mocking framework | TESTING_STRATEGY | `pom.xml`<br>`src/test/java/com/cloudkitchen/testsupport/TestDatabase.java`<br>`src/test/java/com/cloudkitchen/testsupport/TestApp.java`<br>`src/test/java/com/cloudkitchen/testsupport/HttpTestClient.java` | (all) | 1 | Planned |
| NFR-OBS-01 | MUST | Application logging | SDD §8 | `src/main/resources/logback.xml` | `NoPiiInLogsTest` | 6 | Planned |
| NFR-USA-01 | SHOULD | Basic usability | SDD §10.5 | `src/main/webapp/static/css/app.css`<br>`src/main/webapp/WEB-INF/views/**/*.jsp` | (manual checklist) | 7 | Planned |

## Use case coverage

| Use case | Requirements covered | Primary test(s) |
|---|---|---|
| UC-01 Log in | FR-AUT-01, FR-AUT-02, FR-AUT-05, NFR-SEC-03 | `LoginFlowWebTest`, `LockoutWebTest`, `AuthServiceTest` |
| UC-02 Log out | FR-AUT-04 | `LoginFlowWebTest`, `SessionSecurityWebTest` |
| UC-03 Receive aggregator order | FR-ORD-01..07, FR-INV-02..05, FR-RTE-01..02, FR-CON-03 | `OrderServiceTest`, `QueueFullCompensationTest`, `EndToEndLifecycleTest` |
| UC-04 Enter in-house order | FR-ORD-08 | `OrderPrgWebTest` |
| UC-05 Process order at station | FR-CON-02, FR-CON-04, FR-CON-07, FR-CON-08, FR-RES-03 | `StationWorkerTest`, `WorkerResilienceTest` |
| UC-06 Cancel order | FR-LIF-05, FR-INV-06 | `CancelVsWorkerRaceTest`, `OrderDetailWebTest` |
| UC-07 Monitor dashboard | FR-WEB-06, FR-INV-07, FR-CON-10 | `DashboardWebTest` |
| UC-08 View order and history | FR-LIF-04, FR-WEB-07 | `OrderDetailWebTest` |
| UC-09 Manage menu and stock | FR-INV-08, FR-INV-09 | `AdminMenuStockWebTest` |
| UC-10 Control simulator | FR-RES-01, FR-WEB-09 | `FeedSimulatorTest`, `SimulatorWebTest` |
| UC-11 Pause/resume worker | FR-CON-11, FR-WEB-10 | `WorkerPanelWebTest` |
| UC-12 CSV report | FR-FIL-03 | `CsvReportExporterTest`, `ReportWebTest` |
| UC-13 Start up and recover | FR-ENV-02, FR-RES-02, FR-SER-05, NFR-REL-01 | `RecoveryServiceTest`, `CrashRecoveryTest` |
| UC-14 Shut down gracefully | FR-RES-04, FR-SER-02, NFR-REL-03 | `ShutdownCoordinatorTest`, `GracefulShutdownTest` |
| UC-15 Search orders (COULD) | FR-ORD-09 | `OrderSearchWebTest` |
| UC-16 Public tracking (COULD) | FR-ORD-10 | `PublicTrackingWebTest` |
| UC-17 Reset password (COULD) | FR-AUT-09 | `AdminPasswordResetWebTest` |

## Mandatory Java concept coverage

See [JAVA_CONCEPT_MAPPING.md](JAVA_CONCEPT_MAPPING.md): each concept maps to exact planned classes and methods; those classes appear in the file column above.

## Change log of this matrix

| Date | Change | By |
|---|---|---|
| 2026-10-10 | Initial matrix generated from SRS; all rows Planned | Claude Code (documentation session) |

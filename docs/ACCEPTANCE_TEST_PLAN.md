# Acceptance Test Plan

How to prove, on your own machine, that the Cloud Kitchen Orchestration System works end to end, and what each kind of check does and does not prove. The recorded results of one execution are in [EVIDENCE.md](EVIDENCE.md) (EV-072). Everything here was run on **Linux, JDK 21, embedded Tomcat and embedded MariaDB** only; Windows, macOS, a standalone Tomcat and a real MySQL 8 are unverified.

## 1. Kinds of check (what each one proves)

| Kind | Where | What it proves | What it does not prove |
|---|---|---|---|
| **Unit test** | pure classes: `OrderStatusTest`, `OrderValidatorTest`, `PasswordHasherTest`, `LockoutPolicyTest`, `MoneyUtilTest`, ... | one class behaves as specified, no database, no threads | that the parts work together |
| **Integration test** | real embedded MariaDB: DAO, `OrderService`, worker, recovery, snapshot tests (`StockDeductionConcurrencyTest`, `CrashRecoveryTest`, `LeaseFencingTest`, `QueueFullCompensationTest`, `SnapshotRecoveryTest`, ...) and real embedded Tomcat plus HTTP: the `...WebTest` classes (`CsrfWebTest`, `RoleAccessWebTest`, `OrderEntryWebTest`, `WorkerPanelWebTest`, `WorkerPanelDeadWebTest`, ...) | a subsystem keeps its invariants under success, failure and concurrency; the web layer enforces login, roles and CSRF server-side | the exact process that you start from the command line |
| **Smoke test (automated)** | `EndToEndLifecycleTest` (real `AppContext`: three sources reach `DELIVERED`, cancel, duplicate), `LauncherSmokeTest` (the real launcher in a child JVM: start, work, SIGTERM, ordered stop, signed final snapshot, no `WARN`/`ERROR`), `ThroughputSmokeTest` (60 orders/minute time-scaled; overload degrades by compensation only) | the assembled application starts, processes and stops | behaviour on other operating systems |
| **Live acceptance script** | `scripts/live_acceptance.py` against a running instance (section 3) | the packaged application, over real HTTP, honours login/CSRF/roles, validation, stock limits under 25 concurrent requests, cancel, worker pause/resume/restart, report, snapshot, simulator; 62 checks | crash and restart (section 4 is manual) |
| **Manual check** | sections 4 and 5 | a hard kill, recovery on restart, graceful stop, persistence | anything not listed |

The Maven build (`./mvnw clean verify`) runs every unit, integration and automated smoke test: **2,471 tests, 0 failures** at commit `6acce8f` (EV-072; earlier builds EV-063, EV-069, EV-071). The live script and the manual checks are not part of the build.

## 2. Fresh setup and start-up

1. `git clone https://github.com/AlwinJose-9811/CloudKitchenOrchestrationSystem.git`, `cd` into it, `git checkout claude/cloud-kitchen-orchestration-7d1aok`.
2. `cp config/application.properties.example config/application.properties` (Windows: `copy config\application.properties.example config\application.properties`) and replace **every** `CHANGE_ME...` value (see the README table: `db.password` 12-128 characters from the allowed set, `snapshot.hmac.secret` at least 32 characters, the two bootstrap passwords). The file is git-ignored.
3. Run as an ordinary (non-root) user: `./mvnw compile exec:java` (Windows: `mvnw.cmd compile exec:java`).
4. Expected: log lines `Embedded database started on 127.0.0.1:...`, the migrations, `Recovery finished`, `Cloud Kitchen is accepting orders`; `curl http://127.0.0.1:8080/health` prints `OK`. A start with an unreplaced placeholder is refused with a message that names the key.

Measured on Linux with a brand-new data directory and the database binaries already unpacked: healthy in about 6 seconds (EV-072). The very first run on a machine also downloads Maven, the dependencies and unpacks about 0.5 GB of database binaries and takes much longer.

## 3. Live acceptance script (62 checks)

Use a **throw-away instance**: the script places orders, sets the stock of item 12, starts/stops the simulator and pauses/restarts the PIZZA worker. Needs Python 3 (standard library only).

```sh
# terminal 1: start the application with a scratch configuration (separate data/log/snapshot directories and ports)
./mvnw compile exec:java -Dck.config=/path/to/scratch.properties
# terminal 2:
CK_BASE=http://127.0.0.1:8080 CK_ADMIN_PASSWORD='...' CK_STAFF_PASSWORD='...' python3 scripts/live_acceptance.py --confirm
```

It exits 0 only if every check passed. Groups: **A** health, anonymous access redirected, generic sign-in failure, session id rotated at sign-in, staff refused on seven `/admin/*` pages and on POSTs to `/admin/stock` and `/admin/workers` (even with a valid CSRF token), POST without or with a forged CSRF token refused, generic 404; **B** a valid order reaches `DELIVERED` with the legal seven-row history and the exact stock deduction; **C** seven kinds of invalid input are refused, no order stored, stock unchanged; **D** an order above the stock is refused, and 25 concurrent orders for a stock of 10 yield exactly 10 accepted orders, stock exactly 0, never negative; **E** pause, cancel (stock restored, repeat cancel harmless), resume, checked restart (stale generation ignored); **F** CSV report (no customer data) and snapshot on demand; **G** simulator at 60 orders/minute for 25 s: every order reaches a terminal state, no `FAILED`, no negative stock.

## 4. Crash, recovery, graceful stop and persistence (manual)

With a running instance (default scratch configuration, `snapshot.interval.seconds=10` is convenient):

1. Sign in as the administrator, open *Workers*, **Pause** the PIZZA worker, place three PIZZA orders on *New order*: they stay `ROUTED`.
2. Kill the Java process hard: `kill -9 <pid>` (Windows: `taskkill /F /PID <pid>`; **unverified on Windows**). The web port closes; the embedded database server keeps running (an orphan).
3. Start the application again. Expected log: `Stopping database server <pid> ... that the application process <pid> left behind`, `Snapshot recovery: 0 inserted, 3 already present`, `Recovery finished: 3 enqueued`. Expected result: the three orders become `DELIVERED` with one legal history each; no order is lost, none is duplicated.
4. Stop gracefully (Ctrl+C or `kill -TERM <pid>`). Expected log: eight `Shutdown step ...` lines, `Shutdown finished in ... ms (8 steps, 0 failed)`, a signed `snapshot-....ser` plus `.ser.hmac` in the snapshot directory, `Embedded database stopped`, `Cloud Kitchen stopped`, and no leftover database server.
5. Start again: all orders are still listed; delivered orders stay `DELIVERED`.

## 5. Operator recovery of a dead worker

A worker that failed repeatedly ends in state `DEAD`. The administrator restarts it from *Workers* (button **Restart**, which carries the generation the page showed). A dead worker cannot be provoked from the web interface, so this is proven by automated tests only: `OperatorRestartTest` (66 cases, including a deterministic shutdown interleaving), `WorkerPanelDeadWebTest`, `WorkerPanelWebTest`, `DeadWorkerRaceTest`, `WorkerHangRecoveryTest`, `WorkerMonitorTest`.

## 6. Requirement coverage

[TRACEABILITY.md](TRACEABILITY.md) maps each requirement to code, tests and evidence and separates *Verified*, *Partially verified* and *Unverified*. Not built (COULD tier): order search, public tracking page, administrator password reset, charts.

## 7. Limits of this plan

* One operating system (Linux), one JDK (21), one browser engine for the earlier manual demo (headless Chromium); the live script and the manual checks use plain HTTP, not a browser.
* The script uses the seeded item numbers (PIZZA 11 to 15) and a loopback instance; it is not a load test.
* Timing is machine dependent (the simulator check assumes the default step times, 2 seconds an order and station).
* A real MySQL 8, a standalone Tomcat, HTTPS and the Windows and macOS command forms are not covered.

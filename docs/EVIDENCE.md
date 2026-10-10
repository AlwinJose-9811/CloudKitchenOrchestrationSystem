# Evidence Log

**Status:** Phase 1 in progress. Entries EV-001..EV-014 record the Phase 1 work: wrapper, embedded database and its security, JDBC behaviour, embedded Tomcat, WAR, shutdown, and the full verification. Nothing else has been run, so nothing else is evidence. Phase 1 is committed locally and tagged `phase-1-complete` (not pushed) with the limitations listed in EV-013.

**Rules**
1. This file is the **only** proof that something works. A requirement, test, phase or concept may be called *done* / *passed* only if a matching entry exists below.
2. Paste the **real, unedited output** of the command (trim only long repetitive middles, and mark every cut with `[... N lines omitted ...]`). Never write, summarise from memory or "reconstruct" output.
3. Record failures too (with the fix that followed). A failed run is evidence of honesty, not a problem.
4. Each entry has: id, phase, command (exact), working directory if relevant, real output, exit code, date (UTC, `YYYY-MM-DD HH:MM`), environment (OS, JDK version), commit hash, requirement/test ids it supports.
5. Never paste secrets, passwords, HMAC secrets, session ids or customer data. Redact and say `[REDACTED]`.
6. Environment facts (what was needed to run on a clean machine, which OS was verified) are recorded in §2 and decide what the final documents may claim (NFR-PORT-01).

---

## 1. Index

| ID | Phase | Command (short) | Result | Date (UTC) | Supports (requirements / tests) | Commit |
|---|---|---|---|---|---|---|
| EV-001 | 1 | `./mvnw -v` (wrapper, Maven 3.9.16, SHA-256 pinned) | Passed | 2026-10-10 | FR-ENV-01 (partial) | Phase 1 commit (base 992c1fe) |
| EV-002 | 1 | `exec:java` DbSpikeMain as root | **Failed** (mariadbd refuses root) | 2026-10-10 | FR-ENV-02, A-02 | Phase 1 commit |
| EV-003 | 1 | `exec:java` DbSpikeMain as `claude`, twice | Passed (start, stop, data reuse) | 2026-10-10 | FR-ENV-02 (S-02) | Phase 1 commit |
| EV-004 | 1 | manual `mariadbd` with MariaDB4j default arguments | Finding: unauthenticated, all interfaces | 2026-10-10 | NFR-SEC-01, A-02 | Phase 1 commit |
| EV-005 | 1 | manual two-phase hardened start | Passed (checks listed) | 2026-10-10 | NFR-SEC-01, S-17 | Phase 1 commit |
| EV-006 | 1 | `./mvnw ... -Dtest=DbSpikeTest test` as `claude`, twice | Passed 6/6 and 6/6 | 2026-10-10 | FR-ENV-02; S-02, S-03, S-04, S-05, S-17 (partial) | Phase 1 commit |
| EV-007 | 1 | network / rate-limit facts | Recorded | 2026-10-10 | A-01 | Phase 1 commit |
| EV-008 | 1 | `AppConfigTest`, `ArchitectureRulesTest` (+ mutation check) | Passed 10/10, 9/9 | 2026-10-10 | FR-ENV-05 (partial), NFR-SEC-01, DEV-1 guard | Phase 1 commit |
| EV-009 | 1 | `EmbeddedTomcatSpikeTest` (real Launcher) | Passed 11/11 after two defects fixed | 2026-10-10 | FR-ENV-01, S-13 | Phase 1 commit |
| EV-010 | 1 | `DbSpikeTest` S-06..S-12, S-17..S-19, `CryptoSpikeTest` S-16 | Passed 55/55 and 2/2 | 2026-10-10 | FR-ENV-02; S-06..S-12, S-16..S-19 | Phase 1 commit |
| EV-011 | 1 | `jar tf target/*.war` | Passed after one defect fixed | 2026-10-10 | FR-ENV-03, S-15, D-017 | Phase 1 commit |
| EV-012 | 1 | `./mvnw compile exec:java` + SIGINT | Passed (log-visibility limitation noted) | 2026-10-10 | FR-ENV-01, S-14 | Phase 1 commit |
| EV-013 | 1 | `./mvnw clean verify` and the roadmap gate commands | Passed (final run recorded in EV-013) | 2026-10-10 | all Phase 1 requirements (Linux only) | Phase 1 commit |
| EV-014 | 1 | `LauncherShutdownSpikeTest` (SIGTERM to the real launcher) | Passed after root cause fixed | 2026-10-10 | FR-ENV-01, S-14 | Phase 1 commit |

(Add one row per entry below, newest last.)

---

## 2. Environment record (fill in during Phase 1; update when another machine is used)

| Item | Value |
|---|---|
| OS and version | Ubuntu 24.04.5 LTS, kernel 6.18.44-fc-v114, x86_64 (cloud container; session user root, database run as unprivileged `claude`, uid 999) |
| JDK (`java -version`) | OpenJDK 21.0.12.1 (Ubuntu 21.0.12.1+1-1-24.04.4); project target is Java 17 (`maven.compiler.release=17`) |
| Git version | 2.43.0 |
| Internet needed for first build (what was downloaded) | Yes. Maven Wrapper scripts zip (Central), Apache Maven 3.9.16 (Central), plugins, MariaDB4j 3.3.1 with MariaDB 11.4.5 binaries (about 492 MB unpacked), Connector/J 9.7.0, HikariCP 7.0.2, JUnit 5.14.4, surefire 3.6.0. All from Maven Central. Central returned intermittent HTTP 429 (see EV-007). |
| System libraries / packages needed (must be "none") | None installed. `ldd` on `mariadbd` shows no missing library on this image. The server refuses to run as root, so a non-root OS user is required (EV-002, EV-003). |
| Browser used for manual checks | None. HTTP checks used `java.net.http.HttpClient` and `curl`; no rendering in a real browser was done |
| Other OS verified | (none: Windows `mvnw.cmd` and macOS are NOT verified) |
| Real MySQL 8 verified | (not verified) |
| Standalone Tomcat 10.1 WAR deployment verified | (not verified: only the WAR contents were checked, EV-011) |

---

## 3. Entry template (copy for each entry)

```
### EV-000  Phase <n>: <short title>

- Date (UTC): YYYY-MM-DD HH:MM
- Environment: <OS>, <JDK>
- Commit: <short hash>
- Supports: <FR-.../NFR-... ; TestClass1, TestClass2>
- Working directory: <path, if not repo root>
- Command:
  <exact command>
- Exit code: <n>
- Output:
  <real output pasted here>
- Notes / follow-up: <anything unusual, failures fixed, items left unverified>
```

---

## 4. Entries

### Phase 1: Skeleton + zero-install spike

All entries: 2026-10-10, Linux 6.18.44 (Ubuntu 24.04.5), OpenJDK 21.0.12.1, working tree based on commit 992c1fe; the Phase 1 work was committed afterwards in one local commit tagged `phase-1-complete`. Logs referred to are session scratchpad files; the lines quoted are real and unedited except where marked.

### EV-001  Phase 1: Maven Wrapper
- Supports: FR-ENV-01 (partial: wrapper only; build and run are not yet proven).
- Download: `maven-wrapper-distribution-3.3.4-only-script.zip` from Maven Central. First attempts returned HTTP 429; after waiting, HTTP 200, 8492 bytes. `sha1sum` computed locally = `640f7c39ee902b630ada7e77086a6ddb9fceae56`, equal to the value supplied by the user. Contents: `mvnw`, `mvnw.cmd`, `mvnwDebug`, `mvnwDebug.cmd` (no JAR). Installed: `mvnw`, `mvnw.cmd`.
- Maven 3.9.16: `apache-maven-3.9.16-bin.zip` SHA-512 computed locally equals the SHA-512 published by Central (`ed41650d...454af3`); SHA-256 computed locally = `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`, pinned as `distributionSha256Sum`.
- Command: `./mvnw -v` (root, existing cache). Exit code 0. Output: `Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)`, `Java version: 21.0.12.1, vendor: Ubuntu`, `OS name: "linux", version: "6.18.44-fc-v114", arch: "amd64"`.
- Fresh download with the checksum property set was exercised later by user `claude` (empty `~/.m2`): the wrapper downloaded Maven 3.9.16 and the build ran, so the SHA-256 check did not reject it.
- Not verified: Windows (`mvnw.cmd`), `./mvnw -v` on a machine with no Maven cache other than as `claude`.

### EV-002  Phase 1: embedded database start as root (FAILED)
- Supports: FR-ENV-02; assumption A-02.
- Command: `./mvnw -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 exec:java` (mainClass `com.cloudkitchen.bootstrap.DbSpikeMain`, MariaDB4j 3.3.1), user root. Exit code 1.
- Output (key lines): `mariadbd: Please consult the Knowledge Base to find out how to run mysqld as root!` / `[ERROR] Aborting` / `ManagedProcessException: Database does not seem to have started up correctly? Magic string not seen in 30000ms: : ready for connections.`
- Facts: `mariadb-install-db` succeeded; `ldd data/base/bin/mariadbd` showed no missing shared libraries; no database process left behind. Server MariaDB 11.4.5.
- Outcome: A-02 scenario confirmed. No workaround tried at this point; user chose the non-root route.

### EV-003  Phase 1: embedded database start and stop as an unprivileged user
- Supports: FR-ENV-02; spike S-02 (start, stop, restart on existing data, no orphan process).
- Command (twice): `setpriv --reuid=999 --regid=30001 --clear-groups env HOME=/home/claude ./mvnw -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 exec:java` (second run added `-o`). Exit code 0 both times, `BUILD SUCCESS` (35.8 s, then 7.5 s).
- Output (key lines): `mariadbd: ... ready for connections.` / `Version: '11.4.5-MariaDB'  socket: ... port: 3307` / `Stopping the database...` / `Database stopped.` / `SPIKE stopped cleanly`. Second run printed `existing data dir reused: true`.
- After each run: `pgrep -a 'mariadbd|mysqld'` printed nothing; port closed; socket file removed.
- This used MariaDB4j defaults (`--skip-grant-tables`); see EV-004. Two earlier attempts as `claude` failed only on HTTP 429 for `exec-maven-plugin` (Maven cached the failure as "absent"; a later forced update succeeded).
- Not verified: Windows and macOS.

### EV-004  Phase 1: security of MariaDB4j default arguments (finding)
- Supports: NFR-SEC-01; risk note for the embedded database.
- Method: `mariadbd` started by hand as `claude` with exactly the arguments MariaDB4j logged (`--no-defaults --console --skip-grant-tables --max_allowed_packet=64M --basedir ... --datadir ... --tmpdir ... --port ... --socket ...`), on a spare port and a copy of a disposable data directory.
- Results: user `nobody_at_all` with no password logged in over `127.0.0.1` (`SELECT @@skip_grant_tables` = 1, `@@bind_address` = NULL i.e. all interfaces); a user `foo` also connected through the host's non-loopback address `192.0.2.2`; that user could read 31 tables in the `mysql` schema. The installer also leaves anonymous accounts (`''@localhost`, `''@vm`) and a `test` database.
- Conclusion: with MariaDB4j defaults the embedded server is unauthenticated and reachable on every interface of the host. Mitigation is implemented in `DbBootstrap` (EV-005, EV-006).

### EV-005  Phase 1: hardened two-phase start (manual validation)
- Supports: NFR-SEC-01; S-17 (partial).
- Method: phase A (`--skip-grant-tables --skip-networking`, private socket) deletes the anonymous accounts, drops `test`, creates `ckdb` (utf8mb4) and `ck_app`; phase B starts without `--skip-grant-tables` and with `--bind-address=127.0.0.1`.
- Results (phase B): arbitrary user denied (`ERROR 1045`); `root` over TCP denied (`ERROR 1698`); `ck_app` with a wrong password denied; `ck_app` with the right password works (`@@skip_grant_tables` = 0, `@@bind_address` = 127.0.0.1, `@@innodb_flush_log_at_trx_commit` = 1, `@@tx_isolation` = REPEATABLE-READ); connection through `192.0.2.2` refused (`ERROR 2002 ... (115)`); `ck_app` cannot `SELECT` from `mysql.user` (`ERROR 1142`).
- A first attempt failed the "arbitrary user" check (user `foo` logged in) because the anonymous accounts had not been removed; the cleanup statements were added and the check then passed.

### EV-006  Phase 1: database integration test (`DbSpikeTest`)
- Supports: FR-ENV-02; spike items S-02, S-03 (Connector/J only; MariaDB Connector/J not tried because Connector/J worked), S-04, S-05, S-17; NFR-SEC-01 (hardening).
- Command (run twice, user `claude`, `HOME=/home/claude`): `./mvnw -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 -Dtest=DbSpikeTest test`; second run with `-o`. Exit code 0 both times.
- Output: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 35.12 s` and `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 42.06 s`, `BUILD SUCCESS`. Per test (first run): `selectOneThroughHikari`, `decimalAndUtf8mb4RoundTripIsExact`, `serverRunsWithGrantsEnforcedOnLoopbackAndDurableCommits`, `anonymousWrongPasswordAndRootAreRejected`, `applicationAccountCannotReadServerPrivilegeTables`, `serverIsStoppedEvenWhenTheBodyFails` all passed.
- Versions: MariaDB 11.4.5 (MariaDB4j 3.3.1), MySQL Connector/J 9.7.0, HikariCP 7.0.2, JUnit 5.14.4, surefire 3.6.0, Logback 1.5.38. No mocks and no in-memory database.
- What the tests prove: real server started by `DbBootstrap`; HikariCP pool connects through Connector/J; `SELECT 1` returns 1; `DECIMAL(10,2)` values 0.01, 19.99, 99999999.99 come back equal as `BigDecimal` (scale included); a Hindi string and an emoji round-trip exactly, the emoji occupying 4 bytes; anonymous, wrong-password and `root` logins are rejected; `ck_app` cannot read `mysql.user`; `@@skip_grant_tables` = 0, `@@bind_address` = 127.0.0.1, `@@innodb_flush_log_at_trx_commit` = 1; the server process is gone after `close()` both normally and when the test body throws (checked by scanning running processes for the port).
- After each run: no `mariadbd`/`mysqld` process; per-test directories under `data/test` removed.
- At the time of this entry S-06..S-12, S-18, S-19 were not yet written; they were added later and are recorded in EV-010. Still not verified anywhere: the MariaDB Connector/J comparison run, Windows/macOS, tests run as root.

### EV-007  Phase 1: network and rate-limit facts
- Supports: assumption A-01; first-run requirements.
- After the environment network settings were changed, `repo.maven.apache.org`, `repo1.maven.org`, `maven.apache.org`, `dlcdn.apache.org` and `archive.apache.org` returned HTTP 200. Before that they returned `CONNECT tunnel failed, response 403`.
- Maven Central returned intermittent HTTP 429 (also for single `curl` requests, alternating 200 and 429). Maven's default transport could not use the agent proxy (`Name or service not known`); `-Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1` made downloads work. After dependencies are cached, `-o` runs offline.
- `docs` note: the roadmap command `curl -sI https://repo.maven.apache.org/maven2/ | head -1` prints the proxy line `HTTP/1.1 200 Connection Established` here; a status-code check (`curl -w '%{http_code}'`) shows the real response.


### EV-008  Phase 1: package skeleton, AppConfig, example configuration, architecture rules
- Supports: FR-ENV-05 (partial: only the keys Phase 1 needs), NFR-SEC-01, guard for DEV-1 (PROJECT_OVERVIEW 6.5a).
- Created: `package-info.java` for all 19 packages of SDD section 3; `AppConfig`, `CloudKitchenException`, `ConfigurationException`; `config/application.properties.example`; `src/main/resources/logback.xml` (console only).
- Command: `setpriv --reuid=999 --regid=30001 --clear-groups env HOME=/home/claude ./mvnw -o -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 -Dtest=AppConfigTest test`. Result: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`. Same for `-Dtest=ArchitectureRulesTest`: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`.
- Defects found while doing it (kept as evidence of method): (1) the example file used trailing `# ...` comments after values, which `java.util.Properties` does not strip (`server.port=8080   # ...` would not parse); fixed by putting comments on their own lines, and `AppConfigTest.theExampleFileIsUsableOnceItsPlaceholdersAreReplaced` now proves the file loads. (2) A first draft of that test replaced only `db.password`; the implementation correctly rejected the other placeholder secrets, so the test was corrected. (3) I had introduced `DbBootstrapException`, which is not in the SDD exception list; it was replaced by the listed `CloudKitchenException`.
- Mutation check for the architecture rules: a temporary file `util/TempViolation.java` importing `java.sql.Connection` and `org.springframework.core.io.Resource` and calling `System.currentTimeMillis()` made 3 of 9 tests fail (`javaSqlIsOnlyUsedByDaoJdbcDbAndBootstrap`, `noSpringTypesAreUsedInOurOwnCode`, `businessCodeDoesNotReadTheWallClockDirectly`); after deleting the file all 9 passed. The temporary file was removed.
- Not yet covered (Phase 2 owns them): the full `AppConfigTest` and `ArchitectureRulesTest` rule sets of TESTING_STRATEGY.

### EV-009  Phase 1: embedded Tomcat, /health, /spike.jsp (S-13)
- Supports: FR-ENV-01; spike S-13.
- Command: `... ./mvnw -o ... -Dtest=EmbeddedTomcatSpikeTest test` (as `claude`). Final result: `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`.
- Proven over HTTP on a random port (`server.port=0`), by the real `Launcher`: `GET /health` returns 200 with the exact body `OK`, `text/plain`, no cookie; `GET /spike.jsp?name=%3Cb%3Ex%3C%2Fb%3E` returns 200 with `c:out says: &lt;b&gt;x&lt;/b&gt;` (so JSTL `c:out` ran and escaped), `EL sum: 3`, no raw `<b>x</b>`, no `<c:out` or `taglib` in the output; the attribute set by `Launcher` before start is visible in the listener and the JSP (`launcher mark: launcher-...`, `config-typed=true`, answers open question O-3: servlet-context attribute works); header `X-Filter-Order: first,second` (descriptor order respected); `Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Lax`; a 404 body contains no `Apache Tomcat`, `org.apache` or `Exception`; `/WEB-INF/web.xml` and `/META-INF/MANIFEST.MF` return 404; after `close()` the port refuses connections.
- Defects found and fixed (each only visible through the real `exec:java` path, not under surefire):
  1. Under `exec:java` both URLs returned 404. Cause (Tomcat log): `ClassNotFoundException: org.apache.tomcat.util.descriptor.web.FilterDef` while parsing `web.xml`, because the web application's parent class loader was the system loader and `exec:java` keeps application and Tomcat classes in an isolated loader. Fix: `context.setParentClassLoader(Launcher.class.getClassLoader())`, plus a fail-fast check that the context state is available (previously the launcher printed "up" with a dead context). A regression test `launcherWorksWhenLoadedThroughAnIsolatedClassLoaderLikeExecJava` rebuilds the isolation; with the fix removed it fails with `The web application did not start (state STOPPED)` (mutation check run), with the fix it passes. While building the test, two harness errors of mine appeared and were fixed (`Error: factory already defined`, the JVM-wide URL handler factory registered twice in one JVM, avoided with `TomcatURLStreamHandlerFactory.disable()` for the isolated copy; and closing the `URLClassLoader` before stopping Tomcat).
  2. `main()` returned as soon as Tomcat's own `await()` returned, i.e. when a stop began, while the shutdown hook was still running. `RunningApp.await()` now waits on a latch released at the end of `close()`; test `awaitReturnsOnlyAfterCloseHasCompletelyFinished` failed before the change (`expected: <true> but was: <false>`) and passes after.
- Notes: only Tomcat's own "add --add-opens for memory-leak detection" warnings appear in the test log. Tomcat binds to `127.0.0.1` and has no shutdown port (`-1`), by design.

### EV-010  Phase 1: JDBC behaviour items (S-06..S-12, S-16..S-19)
- Supports: FR-ENV-02; spike items named below; inputs to Phase 3 (DAO and `SqlErrors`).
- Command: `... ./mvnw -o ... -Dtest=DbSpikeTest test` and `-Dtest=CryptoSpikeTest`. Result: `DbSpikeTest` `Tests run: 55, Failures: 0, Errors: 0, Skipped: 0` (15 single tests plus the two concurrency tests S-10 and S-19, each repeated 20 times: 20/20 and 20/20; 15 + 40 = 55); `CryptoSpikeTest` `Tests run: 2, Failures: 0`.
- Per item (all with MySQL Connector/J 9.7.0 against MariaDB 11.4.5): S-06 `DATETIME(3)` written and read as `LocalDateTime` and as `Timestamp` with a UTC calendar is identical with the JVM zone set to UTC, Asia/Kolkata and America/Los_Angeles, and the stored text is the same `2026-10-10 18:30:15.123000`; S-07 conditional `UPDATE` returns 1 then 0, `SELECT ... FOR UPDATE` works; S-08 `RETURN_GENERATED_KEYS` returns 1 then 2; S-09 duplicate key gives SQLState `23000`, vendor code `1062`, `SQLIntegrityConstraintViolationException`; S-10 two crossing transactions: exactly one is the victim with SQLState `40001`, code `1213`, `SQLTransactionRollbackException`, the other commits (20/20); S-11 negative `stock_qty` insert and underflowing `UPDATE` are rejected with a `23...` SQLState, and the conditional deduction returns 0 when stock is insufficient; S-12 `CREATE TABLE IF NOT EXISTS` twice, `utf8mb4_bin` column case-sensitive, `utf8mb4_unicode_ci` case-insensitive, multi-statements rejected by default; S-16 PBKDF2WithHmacSHA256 with 600,000 iterations took 629 ms (warmed up, this machine); S-17 `innodb_flush_log_at_trx_commit` = 1, `transaction_isolation` = REPEATABLE-READ, `innodb_lock_wait_timeout` = 50; S-18 the lease statements of DATABASE_DESIGN section 4 behave as designed (acquire with epoch increment and read-back, same-owner re-acquire, takeover after expiry, fenced step refused for stale epoch/owner/expired/NULL lease, `CASE` transition resolves a pending compensation only for FAILED/CANCELLED, `SELECT ... FOR UPDATE` by `claimed_by`, `CHECK (x IS NULL OR x IN (...))`, `DATETIME(3)` sub-second precision); S-19 with a 600 ms injected delay between the first `UPDATE` and its commit, the competing conditional `UPDATE` waited 601 ms and then affected 0 rows (predicate re-evaluated on the committed row, no lost update) in 20/20 repetitions, and a lock wait timeout of 1 s produced vendor code `1205`.
- Findings for later phases: (a) the lock-wait-timeout error has SQLState `40001` and the same exception class as a deadlock, so `SqlErrors` must classify deadlocks by vendor code `1213`, not by SQLState alone (retrying a `1205` as a deadlock would be wrong); (b) naming only `DEFAULT CHARSET=utf8mb4` gives `utf8mb4_general_ci`, so every table must state `COLLATE=utf8mb4_unicode_ci` (the DATABASE_DESIGN section 3 DDL already does); (c) the driver's default update count is "found rows": a matching `UPDATE` that changes nothing returns 1, with `useAffectedRows=true` it returns 0 (our conditional updates always change a value, so "1 = predicate matched" holds either way; decide the URL flag in Phase 3); (d) `information_schema.INNODB_LOCK_WAITS` needs the `PROCESS` privilege, which the application account must not have, so lock waits are not polled by the tests.
- Not run, by the user's decision of 2026-10-10: the same checks with MariaDB Connector/J (S-03 and S-10 and task 1.7 can be read as asking for a second driver run, but the locked ladder uses MariaDB Connector/J only if Connector/J proves incompatible, and it did not). MariaDB Connector/J is therefore **untested**; nothing is claimed about it (PROJECT_OVERVIEW 6.7, D-002).

### EV-011  Phase 1: WAR contents (S-15, FR-ENV-03, D-017)
- Supports: FR-ENV-03; spike S-15; decision D-017.
- Command: `setpriv ... ./mvnw -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 -DskipTests clean package` then `jar tf target/*.war | grep -Ei 'tomcat-embed|mariadb4j|jstl|HikariCP|connector|logback'`.
- Result: the WAR (about 8.7 MB) contains `WEB-INF/lib/jakarta.servlet.jsp.jstl-3.0.1.jar` (implementation), `jakarta.servlet.jsp.jstl-api-3.0.2.jar`, `HikariCP-7.0.2.jar`, `mysql-connector-j-9.7.0.jar`, `logback-classic-1.5.38.jar`, `logback-core-1.5.38.jar`, `slf4j-api-2.0.17.jar`, `protobuf-java-4.31.1.jar` (a dependency of Connector/J), `WEB-INF/web.xml`, `spike.jsp`, `WEB-INF/classes/logback.xml` and the application classes. No entry matches `tomcat-embed|mariadb4j|spring|jasper|servlet-api|el-api` (count 0), so the `provided` scopes keep the embedded server, the embedded database and the transitive Spring jars (DEV-1) out of the deployable WAR. No `application.properties`, `.ser` or log file is inside.
- Defect found and fixed: the first WAR also contained `jakarta.servlet-api-6.0.0.jar` and `jakarta.el-api-5.0.0.jar` (transitive dependencies of the JSTL artifacts), which Tomcat would ignore with a warning. Fixed with `<exclusions>` for `jakarta.servlet-api`, `jakarta.servlet.jsp-api` and `jakarta.el-api` on both JSTL dependencies; the embedded run is unaffected because `tomcat-embed` provides those classes.
- An earlier shell output of mine that claimed "none present" was produced while no WAR existed (the build had failed offline); it was discarded and the check was repeated on a real WAR.
- Not verified: deployment of this WAR into a standalone Tomcat 10.1.

### EV-012  Phase 1: `exec:java` run and Ctrl+C (S-14)
- Supports: FR-ENV-01; spike S-14.
- Command: `setpriv --reuid=999 --regid=30001 --clear-groups env HOME=/home/claude ./mvnw -o -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 compile exec:java` with a generated, git-ignored `config/application.properties` (mode 600, secrets random, nothing printed).
- Result after the fixes in EV-009: log `Embedded database started on 127.0.0.1:3307 (grants enforced, loopback only)` and `Cloud Kitchen is up: http://localhost:8080/spike.jsp and http://localhost:8080/health`; `curl http://localhost:8080/health` gave `OK` with HTTP 200; `curl 'http://localhost:8080/spike.jsp?name=<b>x</b>'` gave HTTP 200 with `c:out says: &lt;b&gt;x&lt;/b&gt;`, `X-Filter-Order: first,second`, `Set-Cookie: JSESSIONID=...; HttpOnly; SameSite=Lax`. The URLs are therefore `http://localhost:8080/health` and `http://localhost:8080/spike.jsp` (context path empty, port from `server.port`, default 8080, bound to 127.0.0.1 only).
- Ctrl+C: SIGINT sent to the Maven JVM; the process exited with code 130, `mariadbd` was gone, port 8080 refused connections, no `java` or `mariadbd` process remained.
- Proof that our own hook ran: temporary file tracing inside the hook (since removed) recorded, in one run, `hook: entered`, `close: start`, `close: tomcat.stop done`, `close: tomcat.destroy done`, `close: db.close start`, `close: db.close done`, `close: end`, `hook: returned` within about 260 ms.
- Limitation seen here, resolved in EV-014: after SIGINT the console showed nothing past `Pausing ProtocolHandler`, so the hook's own log lines were missing. Cause found and fixed in EV-014 (Logback stopped its own logger context when Tomcat destroyed the web application).
- An earlier run, before the defects of EV-009 were fixed, returned HTTP 404 for both URLs; it is kept here because it is what exposed them.

### EV-013  Phase 1: final verification, dependency inventory and limitations
- Environment: Ubuntu 24.04.5, OpenJDK 21.0.12.1, Maven 3.9.16 via the wrapper, every Maven run as the unprivileged user `claude` (uid 999, gid 30001, `HOME=/home/claude`). The project root directory is `root:claude 1775` (non-recursive) so that `claude` can create and remove `target/` and `data/`; the sticky bit prevents it from touching root-owned files.
- Final run, after the last code change (`DbBootstrap`, `Launcher`), 2026-10-10 about 10:50 UTC:
  1. `./mvnw -Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1 clean verify`: exit code 0, `Tests run: 88, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, total time 1:13 min. Per class: `AppConfigTest` 10, `LauncherShutdownSpikeTest` 1, `EmbeddedTomcatSpikeTest` 11, `CryptoSpikeTest` 2, `DbSpikeTest` 55 (15 single tests plus S-10 and S-19 repeated 20 times each), `ArchitectureRulesTest` 9.
  2. `./mvnw ... -q clean verify`: exit code 0.
  3. `./mvnw ... -q test -Dtest=DbSpikeTest,EmbeddedTomcatSpikeTest`: exit code 0.
  4. `./mvnw -v`: `Apache Maven 3.9.16`, exit code 0 (EV-001).
  5. `./mvnw ... compile exec:java` with SIGINT: EV-012 (before the final small changes; the same hook path is covered by the automated `LauncherShutdownSpikeTest`, EV-014).
- WAR of the final build (about 8.7 MB): `WEB-INF/lib` = `HikariCP-7.0.2`, `jakarta.servlet.jsp.jstl-3.0.1`, `jakarta.servlet.jsp.jstl-api-3.0.2`, `logback-classic-1.5.38`, `logback-core-1.5.38`, `mysql-connector-j-9.7.0`, `protobuf-java-4.31.1`, `slf4j-api-2.0.17`; `WEB-INF/web.xml`, `spike.jsp` and `WEB-INF/classes/logback.xml` present; zero entries matching `tomcat-embed|mariadb4j|spring|jasper|servlet-api|el-api|ecj`; no `application.properties`, `.ser` or log file.
- Dependency inventory (the 36 jars on the test class path; the pom declares only the ones marked D): declared: `tomcat-embed-core`, `tomcat-embed-jasper` (provided), `mariaDB4j` (provided), JSTL API and implementation, `logback-classic`, `HikariCP`, `mysql-connector-j` (runtime), `junit-jupiter` (test). Arriving transitively: of Tomcat Embed and Jasper `tomcat-embed-el`, `tomcat-annotations-api`, `ecj` (Jasper's Java compiler); of MariaDB4j `mariaDB4j-core`, the platform database artifacts `mariaDB4j-db-linux64`, `-macos-arm64`, `-winx64` (11.4.5), `exec` (ch.vorburger), `commons-exec`, `commons-io`, `commons-lang3`, `commons-collections4`, `jakarta.annotation-api`, `error_prone_annotations`, `jspecify`, and `spring-core`/`spring-jcl` (deviation DEV-1); of Connector/J `protobuf-java`; of Logback `logback-core`, `slf4j-api`; of JUnit `junit-jupiter-api/-engine/-params`, `junit-platform-*`, `opentest4j`, `apiguardian-api`. A-14 names categories (JDBC driver, Servlet/JSP/JSTL, MariaDB4j, Tomcat Embed and Jasper, SLF4J/Logback, JUnit 5, Maven plugins) and forbids adding other libraries; it does not list transitives. Reading used here: transitive dependencies of an approved artifact are not "added" by us. **The user confirmed this reading on 2026-10-10, with the conditions that such dependencies are necessary and documented, introduce no unapproved direct dependency or framework, and are not blanket permission to add arbitrary libraries.** Direct declarations are checked by `ArchitectureRulesTest` (no Spring, Hibernate, JPA, Mockito, H2).
- Secret scan (G-SECRET): `git ls-files | grep -E '^(data|logs|snapshots|reports)/|^config/application\.properties$'` printed nothing; `git grep -nIE '(password|secret)[[:space:]]*[:=][[:space:]]*"[^"]+"' -- src/main` printed nothing; no secret-like literal in any file a commit would add; the four secrets in `config/application.properties.example` are still `CHANGE_ME...`; `config/application.properties`, `data/` and `target/` are git-ignored.
- Tree (G-TREE) before the commit: modified `.gitignore`, `CLAUDE.md`, `docs/EVIDENCE.md`, `docs/IMPLEMENTATION_ROADMAP.md`, `docs/JAVA_CONCEPT_MAPPING.md`, `docs/PROJECT_OVERVIEW.md`, `docs/SDD.md`, `docs/TESTING_STRATEGY.md`, `docs/TRACEABILITY.md`; 46 untracked files (6 build/config files: `.gitattributes`, `.mvn/wrapper/maven-wrapper.properties`, `config/application.properties.example`, `mvnw`, `mvnw.cmd`, `pom.xml`; 32 under `src/main`, of which 29 Java; 8 under `src/test`). Every removed line in the tracked files was reviewed and is an intended replacement. Nothing was committed or tagged at that point (see the Phase 1 commit afterwards).
- Limitations that remain (stated, not hidden): (1) evidence is from Linux only (Ubuntu 24.04, JDK 21, non-root user); Windows (`mvnw.cmd`, the POSIX-permission fallback in `DbBootstrap`) and macOS were never run; (2) the WAR was not deployed in a standalone Tomcat 10.1, only its contents were checked; (3) MariaDB Connector/J is untested, by the user's decision (D-002); (4) the application cannot run as the operating-system root user (MariaDB's rule); (5) Maven needs two extra flags and sometimes retries when downloading through the sandbox proxy (EV-007); (6) A-14 does not enumerate transitive dependencies (the user confirmed the reading above on 2026-10-10) and DEV-1 stands; (7) the user approved one local commit and the local tag on 2026-10-10; nothing was pushed, and the Done statuses in TRACEABILITY.md need a commit hash and are set in a later documentation update.

### EV-014  Phase 1: shutdown hook log visibility, root cause and permanent test (S-14)
- Supports: FR-ENV-01; spike S-14 ("Ctrl+C runs the shutdown hook and stops the DB").
- Question: EV-012 left the hook's own log lines invisible after Ctrl+C. Does that block S-14? Finding: no, the hook always ran (temporary trace in EV-012), but the caveat was a real defect with a known cause, so it was fixed and made testable.
- Root cause: `logback-classic` ships `LogbackServletContainerInitializer`, which registers a listener that stops the Logback logger context when a web application is destroyed. `tomcat.stop()` destroys our web application, so everything logged afterwards by the hook (and by `DbBootstrap`) was silently dropped. Experiment (direct `java -cp ... Launcher`, SIGTERM): without the property the log shows nothing after `Pausing ProtocolHandler`; with `-DlogbackDisableServletContainerInitializer=true` it shows `[ck-shutdown-hook] DbBootstrap - Embedded database stopped` and `[ck-shutdown-hook] Launcher - Cloud Kitchen stopped`, exit 143. The first S-14 run (before the web application started correctly) also showed the lines, which is what pointed at the web application stop.
- Fix: the embedded `Launcher` sets that system property before starting Tomcat (it owns the JVM and its logging lifecycle). A standalone WAR keeps Logback's default clean-up on undeploy.
- Permanent test: `LauncherShutdownSpikeTest` starts the real `Launcher.main` in a child JVM (embedded database on a random port plus Tomcat on a random port, configuration file in `data/test`), checks `GET /health` = `OK` and that exactly one database process exists for the port, sends SIGTERM (`Process.destroy()`), and asserts: exit code 143, log lines `Embedded database stopped` and `Cloud Kitchen stopped` written by thread `ck-shutdown-hook`, no database process left, web port closed, no `ERROR` line. Skipped on Windows (`Process.destroy()` does not run hooks there; Windows is unverified).
- Results: before the fix the test failed (`hook lines []`); with the fix `Tests run: 1, Failures: 0` (18.8 s); mutation check: with the property line commented out the test fails again (`hook lines []`), with it restored it passes. Four direct runs with the fix (SIGTERM after 0 s and 2 s, twice each) all showed both lines and `JAVA-EXIT=143`.
- Two harness errors of mine on the way, kept for honesty: my first version of the test read the child's output through a pipe, and Java closes the pipe when the child exits (`IOException: Stream closed` in the reader), which loses the last lines; the test now redirects the child's output to a file. An experiment of mine also signalled the wrapping `sh` instead of the JVM (`pgrep -f` matched the shell) and was discarded.
- Ctrl+C itself (SIGINT) was exercised by hand in EV-012; the automated test uses SIGTERM because Java has no portable way to send SIGINT. Both run the same JVM shutdown hooks.
- Not verified: Windows.

### Phase 2: Domain + services (no DB)
(no entries yet)

### Phase 3: Persistence
(no entries yet)

### Phase 4: Concurrent pipeline
(no entries yet)

### Phase 5: Resilience
(no entries yet)

### Phase 6: File handling + serialization
(no entries yet)

### Phase 7: Web layer
(no entries yet)

### Phase 8: Integration audit + Review 2 pack
(no entries yet)

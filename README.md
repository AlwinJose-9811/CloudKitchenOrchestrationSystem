# CloudKitchenOrchestrationSystem

A Java web application that simulates a cloud kitchen. It accepts orders from three sources (Swiggy and Zomato feeds simulated by the application, and an in-house entry form), validates them, reserves stock, routes each order to the kitchen station of its brand, processes the orders concurrently, tracks every status change, and persists everything in a database. Built with Core Java, OOP, JDBC, Servlets, JSP/JSTL, session management, multithreading, NIO file handling and serialization. It is an undergraduate project; it is not a production system.

**Verification status in one paragraph.** Everything described as working below was run on **Linux (Ubuntu 24.04, JDK 21) with the embedded Tomcat and the embedded MariaDB** and nowhere else. A real MySQL 8 server, a standalone Tomcat 10.1, Windows, macOS, browsers other than headless Chromium, HTTPS and mobile devices are **unverified**. `docs/EVIDENCE.md` lists each check with its command and result, and `docs/TRACEABILITY.md` states for every requirement whether it is verified, partially verified or unverified. Known defects and gaps are in [Known limitations](#known-limitations).

## Contents
1. [Architecture](#architecture)
2. [Prerequisites](#prerequisites)
3. [First run](#first-run)
4. [Configuration](#configuration)
5. [Using a real MySQL 8 server (unverified)](#using-a-real-mysql-8-server-unverified)
6. [Tests, demo and the WAR](#tests-demo-and-the-war)
7. [Stopping, backing up and resetting `./data`](#stopping-backing-up-and-resetting-data)
8. [Pages and roles](#pages-and-roles)
9. [Operational notes](#operational-notes)
10. [Troubleshooting](#troubleshooting)
11. [Known limitations](#known-limitations)
12. [Documentation map](#documentation-map)

## Architecture

```
Browser -> Tomcat 10.1 (embedded) -> filters (encoding, security headers, CSRF, auth, role)
        -> controllers (servlets) -> services -> DAOs (plain JDBC) -> MariaDB / MySQL
                                   -> OrderOrchestrator: one bounded queue + one worker thread per brand station
FeedSimulator (scheduled) -> OrderService -> queues;  RecoveryService / OrphanSweeper / WorkerMonitor repair after crashes
NioOrderEventLog (orders.log), CsvReportExporter (daily CSV), SnapshotService (signed, serialized in-flight orders)
```

Layering is `web -> service -> dao -> db`; `domain` and `pipeline` contain no servlet types; only `dao.jdbc`, `db` and `bootstrap` use `java.sql`; `ObjectInputStream` appears only in `snapshot`. `ArchitectureRulesTest` enforces these rules. The database is the source of truth: on start-up every non-terminal order is reloaded and re-queued. Details: `docs/SDD.md`; a guided tour for the Review 2 viva: `docs/REVIEW_2_PREPARATION.md`.

## Prerequisites

* **JDK 17 or newer** (tested with 21), **Git**, a browser.
* **Internet access the first time you build**: the Maven Wrapper downloads Apache Maven 3.9.16 and all dependencies from Maven Central, and the embedded database unpacks about 0.5 GB of MariaDB binaries into `data/runtime` on the first run. Later runs work offline (`./mvnw -o ...`). Nothing else is installed: no Maven, MySQL, Tomcat or Docker.
* **An ordinary (non-root) operating-system user.** The embedded MariaDB server refuses to run as `root`.
* Disk: roughly 1.5 GB (binaries, build output, data).

## First run

Unix/macOS (macOS is **unverified**):

```sh
git clone <repository-url> CloudKitchenOrchestrationSystem
cd CloudKitchenOrchestrationSystem
git checkout claude/cloud-kitchen-orchestration-7d1aok     # the branch that holds this work
cp config/application.properties.example config/application.properties
# edit config/application.properties: replace EVERY value that starts with CHANGE_ME
./mvnw compile exec:java
```

Windows (**unverified**; the same steps with `mvnw.cmd`):

```bat
copy config\application.properties.example config\application.properties
mvnw.cmd compile exec:java
```

Values you must set (the application refuses to start while any `CHANGE_ME...` placeholder remains):

| Key | Rule |
|---|---|
| `db.password` | 12-128 characters from `A-Z a-z 0-9 _ . ! @ # % ^ * + = -` |
| `snapshot.hmac.secret` | at least 32 characters; signs the snapshot files |
| `bootstrap.admin.password`, `bootstrap.staff.password` | initial passwords of the two accounts; required while the user table is empty (first start) |

The file is git-ignored. Never commit it, and never put these values in documents or logs.

**Expected output** (times vary; the first run is slower because it unpacks the database): log lines for `Embedded database started on 127.0.0.1:3307 (grants enforced, loopback only)`, the schema/seed migrations, the recovery pass, the start of the three station workers, and finally the line `Cloud Kitchen is up: http://localhost:8080/ (sign in) and /health (Ctrl+C stops it)`. Then:

```sh
curl http://127.0.0.1:8080/health        # prints: OK
```

Open `http://localhost:8080/` (it redirects to the sign-in page) and sign in with `bootstrap.admin.username` / `bootstrap.admin.password` (administrator) or the staff account (kitchen staff). Stop with **Ctrl+C**; the log ends with `Embedded database stopped` and `Cloud Kitchen stopped`.

A guided 10-minute walk through the application is in `docs/REVIEW_2_PREPARATION.md` (section "Demo script").

## Configuration

* **Location.** `config/application.properties` (relative to the directory the application is started from), or any file named with `-Dck.config=/path/to/file`: `./mvnw compile exec:java -Dck.config=/path/to/file`. The documented keys, with defaults and ranges, are in `config/application.properties.example`.
* **Environment variables.** The application reads none for its settings. The only knob outside the file is the directory of the rolling application log (`logs/` by default): `-DCK_APP_LOG_DIR=/path` or the environment variable `CK_APP_LOG_DIR`.
* **Common properties.**

| Property | Default | Meaning |
|---|---|---|
| `server.port` | 8080 | web port (bound to `127.0.0.1`); `0` picks a random port |
| `server.session.cookie.secure` | false | set `true` only behind HTTPS (HTTPS itself is out of scope and unverified) |
| `db.mode` | embedded | `embedded` (MariaDB4j) or `external` (needs `db.url`) |
| `db.embedded.port` | 3307 | port of the embedded database (loopback only) |
| `db.embedded.data.dir` / `db.embedded.base.dir` | `./data/db` / `./data/runtime` | database files / unpacked binaries |
| `station.queue.capacity` | 50 | bounded queue per station |
| `worker.prep.ms`, `worker.pack.ms`, `worker.delivery.ms` | 1000, 300, 700 | simulated processing time per step (2 s per order: 30 orders/min per station, 90/min in all) |
| `simulator.default.rate.per.minute` | 30 | feed simulator default rate (max 600) |
| `snapshot.dir`, `snapshot.interval.seconds` | `./snapshots`, 30 | signed snapshots of in-flight orders |
| `log.orders.path`, `report.export.dir` | `./logs/orders.log`, `./reports` | order event log and CSV reports |
| `security.pbkdf2.iterations` | 600000 | password hashing cost (minimum 600000) |
| `security.lockout.max.attempts`, `security.lockout.minutes` | 5, 5 | account lock after failed sign-ins |

## Using a real MySQL 8 server (unverified)

The SQL is written in the common MySQL/MariaDB subset, and the DAOs are plain JDBC, so the intent is that only configuration changes: set `db.mode=external`, set `db.url=jdbc:mysql://host:3306/cloudkitchen?...`, `db.user`, `db.password`, and create the database and user yourself. **This has never been run.** Only MariaDB was available during development (see `docs/EVIDENCE.md` EV-020 and the Phase 8 entries); treat MySQL 8 compatibility as a hypothesis, not a result.

## Tests, demo and the WAR

| Purpose | Command |
|---|---|
| Whole build: compile, all tests, WAR | `./mvnw clean verify` (Windows: `mvnw.cmd clean verify`). The embedded database needs a non-root user. Takes about 15 to 16 minutes on the 4-CPU test machine (three measured builds: 927 to 954 s). The test count is stated in `docs/EVIDENCE.md`; do not rely on a number copied elsewhere. |
| A few test classes | `./mvnw -q test -Dtest='OrderServiceTest,ArchitectureRulesTest'` |
| Build only the WAR | `./mvnw -q package` -> `target/*.war` (built by `verify` too) |
| Inspect the WAR | `jar tf target/*.war` (contains JSTL, HikariCP, the driver, Logback; no Tomcat, MariaDB4j, Spring or servlet API) |

The tests use their own throw-away embedded databases and random ports; they do not touch `./data`. The WAR is meant for a standalone Tomcat 10.1; it was **not** deployed on one (only its contents were inspected and it was exercised inside the embedded Tomcat of the tests, see the Phase 8 evidence).

## Stopping, backing up and resetting `./data`

* Stop with Ctrl+C or `kill -TERM <pid>` and wait for `Cloud Kitchen stopped`. Never delete `data/db` while the application is running.
* Prefer a normal stop. If the Java process is killed anyway (`kill -9`, out-of-memory killer), the embedded database server it started keeps running; the next start recognises that (an operating-system lock on `data/.ck-db-owner` that the dead process can no longer hold, and the pid it left in that file), stops that leftover server, and carries on (verified with a real `kill -9`). A second application started on the same data directory while the first runs is refused with `DB_IN_USE` and changes nothing.
* **Back up:** stop the application, then copy `data/db`, `snapshots/` and `logs/orders.log` somewhere safe.
* **Reset to an empty system (keeps your backup):**

```sh
# after the application has stopped
mkdir -p backup-$(date -u +%Y%m%dT%H%M%S)
mv data/db backup-*/db
mv snapshots backup-*/snapshots       # otherwise old in-flight orders could be restored into the new database
./mvnw compile exec:java              # schema, reference data and the two accounts are created again
```

`data/runtime` (the unpacked binaries) can stay. This procedure was run and checked on Linux (stopped, moved, restarted: empty order table, schema present, the two bootstrap accounts recreated, old data intact in the backup).

## Pages and roles

Sign in at `http://localhost:8080/`. Every page is protected by the session, a per-session CSRF token on every POST form, and a role check that is re-read from the database on every request.

| Page | Who | What it does |
|---|---|---|
| `/dashboard`, `/orders`, `/orders/view?id=`, `/inventory` | staff, administrator | Live counts, queues and workers (refreshes every 5 s); order list and status history; stock with a LOW flag |
| `/orders/new` | staff, administrator | Enter an in-house order (choose a brand, then quantities; the price always comes from the menu) |
| `/orders/cancel` (button on the order page) | staff, administrator | Cancel an order that has not started preparing; stock is restored |
| `/admin/simulator` | administrator | Start, stop and change the rate of the simulated Swiggy/Zomato feed |
| `/admin/menu`, `/admin/stock` | administrator | Add items, change price and active flag; replace stock and reorder level |
| `/admin/workers` | administrator | Pause, resume and restart a station worker (also a `DEAD` one); write a snapshot now |
| `/admin/reports` | administrator | Download the daily CSV report (one UTC day, streamed, no customer data) |
| `/admin/users` | administrator | Enable or disable an account (not your own, not the last enabled administrator) |
| `/admin/compensations` | administrator | Retry a stuck stock compensation |

Not built (COULD tier): order search, public tracking page, administrator password reset, charts.

## Operational notes

* **Pause is "between orders".** After *Pause* a worker takes no new order once it has finished the one in progress; a worker already waiting on its queue may still take one more.
* **Restart is checked.** The button carries the worker generation the page showed; if the worker was replaced meanwhile nothing is restarted and the page says so.
* **A disabled account is signed out** at its next request.
* **Snapshots.** The application writes signed (HMAC-SHA256, not encrypted) snapshots of in-flight orders every `snapshot.interval.seconds` and a final one at shutdown. With the embedded database the final snapshot is written before the database is stopped, also on Ctrl+C and SIGTERM (verified on Linux by `LauncherSmokeTest`; this retired the earlier deviation DEV-2, see D-101 in `docs/PROJECT_OVERVIEW.md`). A hard kill writes no final snapshot; recovery then uses the database and the newest periodic snapshot. Snapshot files contain customer names: protect the directory.
* **Logs.** `logs/orders.log` (structured order events, no customer data or secrets) and the rolling application log in `logs/`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Configuration file not found ... Copy config/application.properties.example to config/application.properties` | Do the copy in [First run](#first-run); start the command from the repository root or pass `-Dck.config`. |
| Refuses to start naming a `CHANGE_ME` value | Replace every placeholder (see the table in First run). |
| The embedded database does not start / "refuses to run as root" | Run as a non-root user. |
| `Address already in use` on 8080 or 3307 | Another process uses the port: change `server.port` or `db.embedded.port`. |
| After killing the application with `kill -9` the next start fails with `Can't lock aria control file` / `DB_IN_USE` | `DB_IN_USE` means a live application still uses the data directory: stop it first (the message names its pid). The `aria control file` error means a leftover database server that no application of this version owned (started by hand, or by a version before the owner lock existed): stop it yourself (`pgrep -af mariadbd`, then `kill -TERM <pid>`). Leftovers of a killed application of this version are stopped automatically at the next start. |
| Maven says `Name or service not known` or HTTP 429 from Central (proxied networks) | Add `-Dmaven.resolver.transport=wagon -Dmaven.artifact.threads=1`; add `-U` once to retry a download cached as failed. Offline: `-o` after the first successful build. |
| `Embedded database failed to start ... Magic string not seen in 30000ms` | The server did not announce "ready" within 30 s. Before Phase 8 this was usually the random RSA key generation (fixed by the prepared certificate in `data/tls`); if it still happens, look for a second `mariadbd` (`pgrep -af mariadbd`), a full disk, or a heavily loaded machine, and start again. |
| Dashboard shows `FAILED` orders and `QUEUE_FULL` in `orders.log` under heavy simulator load | By design: a full station queue compensates the order (stock restored, order FAILED). At the default step times (2 s an order) a station completes 30 orders/minute and the three stations 90/minute; offered load above that fills the queues (50 each) after a few minutes. |
| Locked out after failed sign-ins | The account unlocks by itself after `security.lockout.minutes` (5). Administrator password reset is not built. |

## Known limitations

These are the honest gaps at the time of writing; the evidence is in `docs/EVIDENCE.md` (Phase 8 section).

* **Platform coverage.** Only Linux + JDK 21 + embedded Tomcat + embedded MariaDB were exercised. Windows (`mvnw.cmd`), macOS, a standalone Tomcat 10.1, a real MySQL 8, HTTPS (`Secure` cookies), mobile devices, assistive technology and browsers other than headless Chromium are **unverified**.
* **Phase 8 findings, status.** P8-D3 (throughput), P8-D5 (slow database start), P8-D2 (orphan database server), P8-D1 (shutdown warnings) and P8-D6 (test scratch directories) were fixed in the working tree in the Phase 8 remediation pass and are verified on Linux only (`docs/EVIDENCE.md` EV-068 and the independent review EV-069). The fixes are still uncommitted; D-096..D-100 in `docs/PROJECT_OVERVIEW.md` are accepted decisions (2026-10-12) and D-101 retires DEV-2: “An orderly stop writes the final snapshot before the database stops; a hard kill writes none.”
* **Timing is machine dependent.** The 5-minute throughput run was made once on a 4-CPU machine (299 orders at 60/minute, none failed, queues at most 2 deep). A slower machine, or an offered rate above 90 orders/minute, fills the queues; that is reported as failed orders, never as lost ones.
* **Old test scratch.** `data/test` of an existing working copy may still hold thousands of `app-files-*` directories from builds made before the fix (6,423 in the development copy). They are not deleted automatically; remove them when no build is running (`find data/test -maxdepth 1 -type d -name 'app-files-*' -exec rm -rf {} +`).
* Password reset by an administrator, order search, public tracking and charts are not implemented.

## Documentation map

* `CLAUDE.md` - project rules and the current phase status.
* `docs/PROJECT_OVERVIEW.md` - scope, assumptions, decision log, glossary.
* `docs/SRS.md`, `docs/SDD.md`, `docs/DATABASE_DESIGN.md` - requirements, design, schema.
* `docs/IMPLEMENTATION_ROADMAP.md`, `docs/TESTING_STRATEGY.md` - plan and test strategy.
* `docs/TRACEABILITY.md` - requirement -> implementation -> verification -> evidence -> status.
* `docs/EVIDENCE.md` - the only proof that something works (commands, results, dates, limitations).
* `docs/JAVA_CONCEPT_MAPPING.md`, `docs/REVIEW_2_PREPARATION.md` - Java concepts and viva preparation.

# CloudKitchenOrchestrationSystem
A Java-based Cloud Kitchen Orchestration System that streamlines order management, kitchen operations, inventory tracking, and order processing. Built using Core Java, OOP principles, JDBC, MySQL, Servlets, JSP, session management, multithreading, file handling, and serialization.

## Operational note: shutdown and snapshots

The application writes signed snapshots of the in-flight orders periodically (`snapshot.interval.seconds`) and once more at shutdown. The database is the source of truth; snapshots are a secondary safety net used at start-up to put back orders that a lost database no longer has.

- **Stopping through the web server (servlet listener, for example a Tomcat stop):** the final snapshot is written while the database is still open.
- **Stopping with Ctrl+C or SIGTERM while using the embedded database:** the embedded MariaDB4j server shuts itself down in its own JVM shutdown hook, possibly before the final snapshot is written. The final snapshot is therefore best effort and is usually logged as `NOT WRITTEN`. This is a recorded and approved deviation (DEV-2 in `docs/PROJECT_OVERVIEW.md`). No order is lost: the database holds every order, and the newest periodic snapshot (at most `snapshot.interval.seconds` old) is the fallback, but orders created or changed after it are not in any snapshot.
- Snapshot files contain customer names and are signed (HMAC-SHA256), not encrypted; protect the `snapshots/` directory with operating-system permissions.
- Old snapshot pairs are pruned, but an unfinished `.ser` without its `.hmac`, or a lone `.hmac`, is not deleted automatically and may be removed by hand.

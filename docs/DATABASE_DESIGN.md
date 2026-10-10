# Database Design

**Status:** Planned design. No SQL script exists yet; the scripts will be written in Phase 3 from this document.
**Related:** [SDD §5, §13](SDD.md) · [SRS](SRS.md) · [TRACEABILITY](TRACEABILITY.md)

---

## 1. Principles and portability rules

| # | Rule |
|---|---|
| P-1 | Engine InnoDB; database and every table `utf8mb4`; default collation `utf8mb4_unicode_ci` (exists in both MySQL 8 and MariaDB). Columns that must compare exactly (external references) use `utf8mb4_bin`. Collations specific to one vendor (`utf8mb4_0900_ai_ci`, `uca1400_*`) are forbidden. |
| P-2 | Money: `DECIMAL(10,2)`. Time: `DATETIME(3)` holding **UTC**, written by the application (`Instant`), never `NOW()`/`CURRENT_TIMESTAMP` defaults, never `TIMESTAMP` (avoids time-zone conversion and the 2038 limit). Sessions set `time_zone = '+00:00'` as a safeguard. |
| P-3 | Identifiers `lower_snake_case`; tables plural; primary key `id BIGINT AUTO_INCREMENT` (exception: `inventory.item_id`). Names avoid reserved/vendor keywords (`user_role`, not `role`). |
| P-4 | Allowed constructs: `CREATE TABLE`, `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY ... REFERENCES`, `INDEX`, `CHECK` (enforced in MariaDB >= 10.2 and MySQL >= 8.0.16), `AUTO_INCREMENT`, `INSERT`, `UPDATE`, `SELECT ... FOR UPDATE`, `LIMIT/OFFSET`, `COUNT/SUM`, `GROUP BY`, `LIKE`. |
| P-5 | Forbidden: `ENUM`/`SET` types (statuses are `VARCHAR` + `CHECK` + Java enum), `JSON`, generated columns, triggers, stored procedures/functions, events, views, window functions/CTEs, `RETURNING`, `SKIP LOCKED`/`NOWAIT`, `INSERT ... ON DUPLICATE KEY` (use explicit logic), `NOW()`, `ON UPDATE CURRENT_TIMESTAMP`, backtick-quoted identifiers. `SqlPortabilityTest` enforces this list. |
| P-6 | One statement per `;` at end of line; no `;` inside string literals or comments (the simple script splitter in `SchemaInitializer` depends on it). Comments use `-- ` only. |
| P-7 | Foreign keys are `ON DELETE RESTRICT` (no cascading deletes; nothing in the application deletes business rows). |
| P-8 | Status and source strings are the Java enum `name()`s and are validated by `CHECK` constraints for defence in depth. |
| P-9 | Scripts: `src/main/resources/db/V001__schema.sql` (tables, indexes), `V002__seed_reference_data.sql` (stations, menu, stock). Users are **not** seeded in SQL (passwords come from config via `UserSeeder`). Applied in order by `SchemaInitializer`, recorded in `schema_version`. |
| P-10 | Switching to real MySQL 8 is a configuration change (`db.mode=external`, `db.url`, `db.user`, `db.password`); no script change. Verified on MariaDB4j; MySQL 8 verification is a manual Phase 8 check if a server is available (declared unverified otherwise). |

Connection settings (driver-specific details finalised in the Phase 1 spike): UTF-8 (`utf8mb4`) connection character set, `time_zone = '+00:00'` via Hikari `connectionInitSql`, multi-statement queries disabled, `useAffectedRows` semantics verified (conditional updates change the status/qty value, so "matched" and "changed" row counts agree either way).

---

## 2. Entity-relationship diagram

```mermaid
erDiagram
    STATIONS ||--o{ MENU_ITEMS : "offers"
    MENU_ITEMS ||--|| INVENTORY : "has stock"
    STATIONS ||--o{ ORDERS : "prepares"
    ORDERS ||--|{ ORDER_ITEMS : "contains"
    MENU_ITEMS ||--o{ ORDER_ITEMS : "ordered as"
    ORDERS ||--|{ ORDER_STATUS_HISTORY : "audited by"

    STATIONS {
        bigint id PK
        varchar code UK
        varchar brand_name
        tinyint active
        datetime created_at
    }
    MENU_ITEMS {
        bigint id PK
        bigint station_id FK
        varchar name
        decimal price
        tinyint active
        datetime created_at
        datetime updated_at
    }
    INVENTORY {
        bigint item_id PK
        int stock_qty
        int reorder_level
        datetime updated_at
    }
    ORDERS {
        bigint id PK
        char tracking_code UK
        varchar source
        varchar external_ref
        bigint station_id FK
        varchar status
        varchar customer_name
        decimal delivery_distance_km
        varchar payment_mode
        varchar payment_status
        decimal total_amount
        decimal platform_fee
        tinyint stock_deducted
        datetime estimated_delivery_at
        datetime created_at
        datetime updated_at
    }
    ORDER_ITEMS {
        bigint id PK
        bigint order_id FK
        bigint item_id FK
        varchar item_name
        decimal unit_price
        int quantity
    }
    ORDER_STATUS_HISTORY {
        bigint id PK
        bigint order_id FK
        varchar from_status
        varchar to_status
        datetime changed_at
        varchar actor
        varchar note
    }
    STAFF_USERS {
        bigint id PK
        varchar username UK
        varchar password_hash
        varchar user_role
        tinyint enabled
        int failed_attempts
        datetime locked_until
        datetime last_login_at
        datetime created_at
    }
    SCHEMA_VERSION {
        int version PK
        varchar script_name
        datetime applied_at
    }
```

`STAFF_USERS` and `SCHEMA_VERSION` have no relationship to other tables by design (`order_status_history.actor` stores the user name as text so history survives user changes and avoids a user FK on the hot write path).

---

## 3. Table definitions

Legend: **NN** NOT NULL, **UK** unique, **PK** primary key, **FK** foreign key. All tables: `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`.

### 3.1 `stations`
One row per brand/kitchen station (exactly one station per brand).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `code` | VARCHAR(20) | NN, UK `uq_stations_code` | Brand code used in routing and thread names, e.g. `BIRYANI`; `CHECK (code = UPPER(code))` is **not** used (portability); the application upper-cases |
| `brand_name` | VARCHAR(80) | NN | Display name |
| `active` | TINYINT(1) | NN, DEFAULT 1, `CHECK (active IN (0,1))` | Inactive stations get no worker and cannot receive orders |
| `created_at` | DATETIME(3) | NN | UTC |

### 3.2 `menu_items`
| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | The "item_id" used in `inventory`, `order_items`, requests |
| `station_id` | BIGINT | NN, FK -> `stations(id)` | Item belongs to exactly one brand |
| `name` | VARCHAR(100) | NN | |
| `price` | DECIMAL(10,2) | NN, `CHECK (price >= 0)` | Current price; orders copy it |
| `active` | TINYINT(1) | NN, DEFAULT 1, `CHECK (active IN (0,1))` | |
| `created_at`, `updated_at` | DATETIME(3) | NN | UTC |

Constraints/indexes: `uq_menu_items_station_name (station_id, name)`; `idx_menu_items_station_active (station_id, active)`.

### 3.3 `inventory`
Item-level stock; one row per menu item (so stock is per station through the item).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `item_id` | BIGINT | PK, FK -> `menu_items(id)` | |
| `stock_qty` | INT | NN, `CHECK (stock_qty >= 0)` | Last line of defence against negative stock |
| `reorder_level` | INT | NN, DEFAULT 10, `CHECK (reorder_level >= 0)` | Low-stock when `stock_qty <= reorder_level` |
| `updated_at` | DATETIME(3) | NN | UTC |

### 3.4 `orders`
| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | Internal id |
| `tracking_code` | CHAR(12) | NN, UK `uq_orders_tracking_code` | Random, unguessable (A-Z0-9, `SecureRandom`); used by COULD public tracking |
| `source` | VARCHAR(16) | NN, `CHECK (source IN ('SWIGGY','ZOMATO','IN_HOUSE'))` | |
| `external_ref` | VARCHAR(64) | NN, COLLATE `utf8mb4_bin` | Source's order id; case-sensitive |
| `station_id` | BIGINT | NN, FK -> `stations(id)` | |
| `status` | VARCHAR(20) | NN, `CHECK (status IN ('PLACED','VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','FAILED'))` | Transition rules live in `OrderStatus`, not the DB |
| `customer_name` | VARCHAR(100) | NN | Personal data: never logged |
| `delivery_distance_km` | DECIMAL(5,2) | NN, `CHECK (delivery_distance_km >= 0)` | |
| `payment_mode` | VARCHAR(20) | NN, `CHECK (payment_mode IN ('PREPAID_AGGREGATOR','CASH','UPI','CARD'))` | |
| `payment_status` | VARCHAR(10) | NN, `CHECK (payment_status IN ('PENDING','PAID','FAILED'))` | From `processPayment()` |
| `total_amount` | DECIMAL(10,2) | NN, `CHECK (total_amount >= 0)` | Server-computed subtotal |
| `platform_fee` | DECIMAL(10,2) | NN, DEFAULT 0.00 | From `processPayment()` |
| `stock_deducted` | TINYINT(1) | NN, DEFAULT 0, `CHECK (stock_deducted IN (0,1))` | 1 while this order holds deducted stock; flipped to 0 atomically when stock is restored; 0 for orders recovered from a snapshot |
| `estimated_delivery_at` | DATETIME(3) | NN | UTC |
| `created_at`, `updated_at` | DATETIME(3) | NN | UTC |

Constraints/indexes:
* `uq_orders_source_ref UNIQUE (source, external_ref)` — idempotency guarantee (FR-ORD-04).
* `idx_orders_status (status)` — startup recovery (`status NOT IN (terminal)`), dashboard counts.
* `idx_orders_station_status (station_id, status)` — per-station views.
* `idx_orders_created (created_at)` — list ordering and daily reports.

### 3.5 `order_items`
| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `order_id` | BIGINT | NN, FK -> `orders(id)` | |
| `item_id` | BIGINT | NN, FK -> `menu_items(id)` | |
| `item_name` | VARCHAR(100) | NN | Name at order time |
| `unit_price` | DECIMAL(10,2) | NN, `CHECK (unit_price >= 0)` | Price at order time |
| `quantity` | INT | NN, `CHECK (quantity BETWEEN 1 AND 20)` | |

Constraints/indexes: `uq_order_items_order_item (order_id, item_id)`; `idx_order_items_item (item_id)`.

### 3.6 `order_status_history`
Append-only audit of every transition (FR-LIF-03/04).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | Tie-breaker for equal timestamps |
| `order_id` | BIGINT | NN, FK -> `orders(id)` | |
| `from_status` | VARCHAR(20) | NULL | NULL only for the first row (`-> PLACED`) |
| `to_status` | VARCHAR(20) | NN | Same status vocabulary as `orders.status` (CHECK in list) |
| `changed_at` | DATETIME(3) | NN | UTC |
| `actor` | VARCHAR(50) | NN | `SYSTEM`, `worker:<CODE>`, `simulator`, or a username |
| `note` | VARCHAR(255) | NULL | e.g. `QUEUE_FULL`, `RECOVERED_FROM_SNAPSHOT`; no PII |

Index: `idx_history_order (order_id, changed_at, id)`.

### 3.7 `staff_users`
| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `username` | VARCHAR(50) | NN, UK `uq_staff_users_username` | Stored lower-case; case-insensitive collation also protects |
| `password_hash` | VARCHAR(255) | NN | `pbkdf2_sha256$iterations$salt$hash` |
| `user_role` | VARCHAR(20) | NN, `CHECK (user_role IN ('ADMIN','KITCHEN_STAFF'))` | |
| `enabled` | TINYINT(1) | NN, DEFAULT 1, `CHECK (enabled IN (0,1))` | |
| `failed_attempts` | INT | NN, DEFAULT 0, `CHECK (failed_attempts >= 0)` | **Lockout column** |
| `locked_until` | DATETIME(3) | NULL | **Lockout column**: NULL or a UTC instant; locked while `> now` |
| `last_login_at` | DATETIME(3) | NULL | |
| `created_at` | DATETIME(3) | NN | |

### 3.8 `schema_version`
| Column | Type | Constraints |
|---|---|---|
| `version` | INT | PK |
| `script_name` | VARCHAR(100) | NN |
| `applied_at` | DATETIME(3) | NN |

### 3.9 Seed reference data (V002; placeholders, renameable)

| Station `code` | `brand_name` | Menu items (5 each) | Initial `stock_qty` / `reorder_level` |
|---|---|---|---|
| `BIRYANI` | Spice Route Biryani | e.g. Chicken Biryani, Veg Biryani, Mutton Biryani, Raita, Gulab Jamun | 50 / 10 |
| `BURGER` | Burger Barn | e.g. Classic Burger, Cheese Burger, Veg Burger, Fries, Cola | 50 / 10 |
| `PIZZA` | Pizza Piazza | e.g. Margherita, Farmhouse, Pepperoni, Garlic Bread, Brownie | 50 / 10 |

Final names and prices are decided in Phase 3 (open point O-4). Prices are fixed-scale decimals (for example `249.00`).

---

## 4. Key statements (design of SQL, not scripts)

All use bound parameters (`?`).

| Purpose | Statement shape |
|---|---|
| Deduct stock (atomic, conditional) | `UPDATE inventory SET stock_qty = stock_qty - ?, updated_at = ? WHERE item_id = ? AND stock_qty >= ?` — rows = 1 means success |
| Restore stock | `UPDATE inventory SET stock_qty = stock_qty + ?, updated_at = ? WHERE item_id = ?` |
| Conditional transition | `UPDATE orders SET status = ?, updated_at = ? WHERE id = ? AND status = ?` — rows = 1 means this caller won |
| Mark stock held | `UPDATE orders SET stock_deducted = 1 WHERE id = ? AND stock_deducted = 0` |
| Claim restore (exactly once) | `UPDATE orders SET stock_deducted = 0 WHERE id = ? AND stock_deducted = 1` |
| Recovery set | `SELECT ... FROM orders WHERE status IN ('VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY','PLACED') ORDER BY id` |
| Lock user row | `SELECT ... FROM staff_users WHERE username = ? FOR UPDATE` |
| Low stock | `SELECT ... FROM inventory i JOIN menu_items m ON m.id = i.item_id WHERE i.stock_qty <= i.reorder_level` |
| Status counts | `SELECT status, COUNT(*) FROM orders GROUP BY status` |
| Daily report | `... WHERE created_at >= ? AND created_at < ? ORDER BY id` |

---

## 5. Transaction scenarios

Isolation is InnoDB default `REPEATABLE READ`. Correctness uses atomic conditional statements and row locks. A step marked (L) takes a row lock held until commit.

### TS-1 Create order (TX1)
1. Insert `orders` row (`PLACED`, `stock_deducted=0`) (L new row). 2. Insert `order_items`. 3. Insert history `NULL -> PLACED`.
4. For each item in ascending `item_id`: conditional `UPDATE inventory` (L on that row). Any 0-row result: rollback everything.
5. `UPDATE orders SET stock_deducted=1`. 6. Conditional `PLACED -> VALIDATED`. 7. History `PLACED -> VALIDATED`. Commit.
*Effects on failure:* rollback leaves nothing. *Concurrency:* two orders touching the same items lock the inventory rows in the same ascending order, so lock-order deadlocks cannot arise from stock rows; remaining deadlock risk (gap/FK locks) is covered by retry x3.

### TS-2 Route (TX2)
Conditional `VALIDATED -> ROUTED` + history. Zero rows is acceptable.

### TS-3 Worker step / cancel / fail (TX3)
Conditional transition; if 1 row -> history insert; if restore applies -> `claimStockRestore`; if that returns 1 -> `UPDATE inventory` additions in ascending `item_id`. Commit. If the conditional update returns 0, the transaction ends with no other statement executed.

### TS-4 Compensation after queue-full (TX4)
TS-3 with `from = VALIDATED`, `to = FAILED`, actor `SYSTEM`, note `QUEUE_FULL`. Restore is guaranteed once by `stock_deducted`.

### TS-5 Admin stock adjustment
Single `UPDATE inventory SET stock_qty = ?, reorder_level = ?, updated_at = ? WHERE item_id = ?`. Setting an absolute quantity can race with in-flight deductions; the UI states that the value replaces the current stock (accepted; documented).

### TS-6 Login attempt (TX7)
`SELECT ... FOR UPDATE` on the user's row serialises concurrent attempts so the failed-attempt counter and lock cannot be bypassed; counters updated; commit.

### TS-7 Recovery from snapshot (TX8)
For each snapshot order: `SELECT id FROM orders WHERE source=? AND external_ref=?`; if absent: insert order (status from snapshot, `stock_deducted=0`), items, one history row (`note='RECOVERED_FROM_SNAPSHOT'`); commit per order. Unique violation here simply means someone inserted it meanwhile (treated as present).

### TS-8 Deadlock handling
`Database.inTransaction` catches `SQLException` where `SqlErrors.isDeadlock(e)` (SQLState `40001` or vendor code `1213`), rolls back, backs off and reruns the entire work up to 3 times (also covers lock wait timeout code `1205` only if the spike shows it behaves similarly; otherwise not retried).

### TS-9 Schema initialisation
Statements run one by one in autocommit mode (DDL implicitly commits). Each script is recorded in `schema_version` only after all its statements succeed, so a failed script is rerun from the start; therefore every DDL uses `CREATE TABLE IF NOT EXISTS` and seed inserts are guarded by `NOT EXISTS`-style selects on `code`/`(station_id,name)` (kept to the portable subset).

---

## 6. Data volume and retention assumptions
Academic scale: thousands of orders. No archival or deletion is implemented; indexes are sized for tens of thousands of rows. History grows about 7 rows per delivered order.

## 7. Backup and recovery
Embedded database files live in `./data` (git-ignored); backup is a file copy while the application is stopped. The snapshots (SDD §9) are a secondary, partial safety net, not a backup.

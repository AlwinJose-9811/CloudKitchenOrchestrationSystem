-- Cloud Kitchen Orchestration System: schema (DATABASE_DESIGN section 3).
-- Portable subset of MySQL 8 / MariaDB: no triggers, ENUM, JSON, NOW() or vendor collations.
-- Every statement is idempotent (CREATE TABLE IF NOT EXISTS); statements are separated by a semicolon at the end of a line.

CREATE TABLE IF NOT EXISTS schema_version (
  version INT NOT NULL,
  script_name VARCHAR(100) NOT NULL,
  applied_at DATETIME(3) NOT NULL,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stations (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(20) NOT NULL,
  brand_name VARCHAR(80) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_stations_code UNIQUE (code),
  CONSTRAINT chk_stations_active CHECK (active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS menu_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  station_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_menu_items_station FOREIGN KEY (station_id) REFERENCES stations (id),
  CONSTRAINT uq_menu_items_station_name UNIQUE (station_id, name),
  CONSTRAINT chk_menu_items_price CHECK (price >= 0),
  CONSTRAINT chk_menu_items_active CHECK (active IN (0,1)),
  INDEX idx_menu_items_station_active (station_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS inventory (
  item_id BIGINT NOT NULL,
  stock_qty INT NOT NULL,
  reorder_level INT NOT NULL DEFAULT 10,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (item_id),
  CONSTRAINT fk_inventory_item FOREIGN KEY (item_id) REFERENCES menu_items (id),
  CONSTRAINT chk_inventory_stock CHECK (stock_qty >= 0),
  CONSTRAINT chk_inventory_reorder CHECK (reorder_level >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tracking_code CHAR(12) NOT NULL,
  source VARCHAR(16) NOT NULL,
  external_ref VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  station_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  customer_name VARCHAR(100) NOT NULL,
  delivery_distance_km DECIMAL(5,2) NOT NULL,
  payment_mode VARCHAR(20) NOT NULL,
  payment_status VARCHAR(10) NOT NULL,
  total_amount DECIMAL(10,2) NOT NULL,
  platform_fee DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  stock_deducted TINYINT(1) NOT NULL DEFAULT 0,
  claim_epoch INT NOT NULL DEFAULT 0,
  claimed_by VARCHAR(40) NULL,
  lease_expires_at DATETIME(3) NULL,
  compensation_state VARCHAR(10) NULL,
  compensation_reason VARCHAR(30) NULL,
  compensation_attempts INT NOT NULL DEFAULT 0,
  compensation_next_at DATETIME(3) NULL,
  estimated_delivery_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_orders_tracking_code UNIQUE (tracking_code),
  CONSTRAINT uq_orders_source_ref UNIQUE (source, external_ref),
  CONSTRAINT fk_orders_station FOREIGN KEY (station_id) REFERENCES stations (id),
  CONSTRAINT chk_orders_source CHECK (source IN ('SWIGGY','ZOMATO','IN_HOUSE')),
  CONSTRAINT chk_orders_status CHECK (status IN ('PLACED','VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','FAILED')),
  CONSTRAINT chk_orders_distance CHECK (delivery_distance_km >= 0),
  CONSTRAINT chk_orders_payment_mode CHECK (payment_mode IN ('PREPAID_AGGREGATOR','CASH','UPI','CARD')),
  CONSTRAINT chk_orders_payment_status CHECK (payment_status IN ('PENDING','PAID','FAILED')),
  CONSTRAINT chk_orders_total CHECK (total_amount >= 0),
  CONSTRAINT chk_orders_stock_deducted CHECK (stock_deducted IN (0,1)),
  CONSTRAINT chk_orders_claim_epoch CHECK (claim_epoch >= 0),
  CONSTRAINT chk_orders_compensation_state CHECK (compensation_state IS NULL OR compensation_state IN ('PENDING','RESOLVED')),
  CONSTRAINT chk_orders_compensation_attempts CHECK (compensation_attempts >= 0),
  INDEX idx_orders_status (status),
  INDEX idx_orders_station_status (station_id, status),
  INDEX idx_orders_created (created_at),
  INDEX idx_orders_status_updated (status, updated_at),
  INDEX idx_orders_compensation (compensation_state, compensation_next_at),
  INDEX idx_orders_claimed_by (claimed_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  item_name VARCHAR(100) NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  quantity INT NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
  CONSTRAINT fk_order_items_item FOREIGN KEY (item_id) REFERENCES menu_items (id),
  CONSTRAINT uq_order_items_order_item UNIQUE (order_id, item_id),
  CONSTRAINT chk_order_items_price CHECK (unit_price >= 0),
  CONSTRAINT chk_order_items_quantity CHECK (quantity BETWEEN 1 AND 20),
  INDEX idx_order_items_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS order_status_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status VARCHAR(20) NOT NULL,
  changed_at DATETIME(3) NOT NULL,
  actor VARCHAR(50) NOT NULL,
  note VARCHAR(255) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_history_order FOREIGN KEY (order_id) REFERENCES orders (id),
  CONSTRAINT uq_history_order_edge UNIQUE (order_id, from_status, to_status),
  CONSTRAINT chk_history_from CHECK (from_status IS NULL OR from_status IN ('PLACED','VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','FAILED')),
  CONSTRAINT chk_history_to CHECK (to_status IN ('PLACED','VALIDATED','ROUTED','PREPARING','PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','FAILED')),
  INDEX idx_history_order (order_id, changed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS staff_users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  user_role VARCHAR(20) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  session_version INT NOT NULL DEFAULT 0,
  last_login_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_staff_users_username UNIQUE (username),
  CONSTRAINT chk_staff_users_role CHECK (user_role IN ('ADMIN','KITCHEN_STAFF')),
  CONSTRAINT chk_staff_users_enabled CHECK (enabled IN (0,1)),
  CONSTRAINT chk_staff_users_failed CHECK (failed_attempts >= 0),
  CONSTRAINT chk_staff_users_session_version CHECK (session_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

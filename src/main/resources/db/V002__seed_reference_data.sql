-- Reference data only (DATABASE_DESIGN 3.9): three brands with one station each, five menu items per brand and
-- their stock. No users and no credentials: staff accounts are created by UserSeeder from the git-ignored config.
-- Each insert is guarded so that a re-run adds nothing.

INSERT INTO stations (code, brand_name, active, created_at)
SELECT 'BIRYANI', 'Spice Route Biryani', 1, UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stations WHERE code = 'BIRYANI');

INSERT INTO stations (code, brand_name, active, created_at)
SELECT 'BURGER', 'Burger Barn', 1, UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stations WHERE code = 'BURGER');

INSERT INTO stations (code, brand_name, active, created_at)
SELECT 'PIZZA', 'Pizza Piazza', 1, UTC_TIMESTAMP(3) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM stations WHERE code = 'PIZZA');

INSERT INTO menu_items (station_id, name, price, active, created_at, updated_at)
SELECT s.id, v.name, v.price, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
FROM stations s
JOIN (
  SELECT 'BIRYANI' AS code, 'Chicken Biryani' AS name, 249.00 AS price, 1 AS n
  UNION ALL SELECT 'BIRYANI', 'Veg Biryani', 199.00, 2
  UNION ALL SELECT 'BIRYANI', 'Mutton Biryani', 299.00, 3
  UNION ALL SELECT 'BIRYANI', 'Raita', 49.00, 4
  UNION ALL SELECT 'BIRYANI', 'Gulab Jamun', 79.00, 5
  UNION ALL SELECT 'BURGER', 'Classic Burger', 149.00, 6
  UNION ALL SELECT 'BURGER', 'Cheese Burger', 169.00, 7
  UNION ALL SELECT 'BURGER', 'Veg Burger', 129.00, 8
  UNION ALL SELECT 'BURGER', 'Fries', 89.00, 9
  UNION ALL SELECT 'BURGER', 'Cola', 59.00, 10
  UNION ALL SELECT 'PIZZA', 'Margherita', 299.00, 11
  UNION ALL SELECT 'PIZZA', 'Farmhouse', 399.00, 12
  UNION ALL SELECT 'PIZZA', 'Pepperoni', 449.00, 13
  UNION ALL SELECT 'PIZZA', 'Garlic Bread', 149.00, 14
  UNION ALL SELECT 'PIZZA', 'Brownie', 129.00, 15
) v ON v.code = s.code
WHERE NOT EXISTS (SELECT 1 FROM menu_items m WHERE m.station_id = s.id AND m.name = v.name)
ORDER BY v.n;

INSERT INTO inventory (item_id, stock_qty, reorder_level, updated_at)
SELECT m.id, 50, 10, UTC_TIMESTAMP(3)
FROM menu_items m
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.item_id = m.id)
ORDER BY m.id;

package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.jdbc.JdbcMenuItemDao;
import com.cloudkitchen.dao.jdbc.JdbcOrderDao;
import com.cloudkitchen.dao.jdbc.JdbcStaffUserDao;
import com.cloudkitchen.dao.jdbc.JdbcStationDao;
import com.cloudkitchen.dao.jdbc.JdbcStatusHistoryDao;
import com.cloudkitchen.dao.jdbc.JdbcStockDao;
import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderFactory;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.MenuCatalog;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The real DAOs over a {@link DbFixture}, plus helpers that build and persist orders from the seeded catalogue. */
public final class DbTestSupport {

    public static final Duration MARGIN = Duration.ofMillis(200);
    public static final Duration TTL = Duration.ofSeconds(30);

    public final DbFixture db;
    public final Clock clock;
    public final JdbcOrderDao orders;
    public final JdbcStockDao stock;
    public final JdbcStatusHistoryDao history;
    public final JdbcMenuItemDao menu;
    public final JdbcStaffUserDao users;
    public final JdbcStationDao stations;

    public DbTestSupport(DbFixture db) {
        this.db = db;
        this.clock = db.clock();
        this.orders = new JdbcOrderDao(clock, MARGIN);
        this.stock = new JdbcStockDao(clock);
        this.history = new JdbcStatusHistoryDao();
        this.menu = new JdbcMenuItemDao(clock);
        this.users = new JdbcStaffUserDao(clock);
        this.stations = new JdbcStationDao();
    }

    public MenuCatalog catalog() throws CloudKitchenException {
        return db.database().withConnection(c -> {
            List<Station> all = new ArrayList<>(stations.findAllActive(c));
            return MenuCatalog.of(all, menu.findAll(c));
        });
    }

    public long itemId(String name) throws CloudKitchenException {
        return db.database().withConnection(c -> {
            for (MenuItem item : menu.findAll(c)) {
                if (item.getName().equals(name)) {
                    return item.getId();
                }
            }
            throw new IllegalArgumentException("no seeded item " + name);
        });
    }

    public Station station(String code) throws CloudKitchenException {
        return db.database().withConnection(c -> stations.findByCode(c, code).orElseThrow());
    }

    /** A valid request; {@code itemsAndQuantities} alternates item names and quantities. */
    public OrderRequest request(OrderSource source, String ref, String brand, Object... itemsAndQuantities) throws CloudKitchenException {
        List<OrderItemRequest> lines = new ArrayList<>();
        for (int i = 0; i < itemsAndQuantities.length; i += 2) {
            lines.add(new OrderItemRequest(itemId((String) itemsAndQuantities[i]), (Integer) itemsAndQuantities[i + 1]));
        }
        PaymentMode mode = source == OrderSource.IN_HOUSE ? PaymentMode.CASH : PaymentMode.PREPAID_AGGREGATOR;
        return new OrderRequest(source, ref, brand, "Asha Rao", new BigDecimal("6.10"), mode, lines);
    }

    public Order create(OrderRequest request) throws CloudKitchenException {
        return OrderFactory.create(request, station(request.brandCode()), catalog(), clock.instant());
    }

    /** Order with the default two lines (2 x Chicken Biryani + 1 x Raita = 547.00). */
    public Order biryaniOrder(String ref) throws CloudKitchenException {
        return create(request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1));
    }

    /** Inserts the order, its lines and the initial history row in one transaction; returns the id. */
    public long persist(Order order) throws CloudKitchenException {
        return db.database().inTransaction(c -> {
            long id = orders.insert(c, order);
            orders.insertItems(c, id, order.getItems());
            history.insert(c, new StatusHistoryEntry(id, null, OrderStatus.PLACED, clock.instant(), "SYSTEM", null));
            return id;
        });
    }

    /** Persists a new order and walks it along {@code path} with plain conditional transitions and history rows. */
    public long persistAt(Order order, OrderStatus... path) throws CloudKitchenException {
        long id = persist(order);
        OrderStatus current = OrderStatus.PLACED;
        for (OrderStatus next : path) {
            OrderStatus from = current;
            db.database().inTransaction(c -> {
                if (!orders.transition(c, id, from, next, clock.instant())) {
                    throw new IllegalStateException("transition refused " + from + " -> " + next);
                }
                history.insert(c, new StatusHistoryEntry(id, from, next, clock.instant(), "SYSTEM", null));
                return null;
            });
            current = next;
        }
        return id;
    }

    /** Runs boolean-returning work in a transaction (avoids generic-inference ambiguity inside assertions). */
    public boolean ok(com.cloudkitchen.db.TxWork<Boolean> work) throws CloudKitchenException {
        return Boolean.TRUE.equals(db.database().inTransaction(work));
    }

    public long num(com.cloudkitchen.db.TxWork<? extends Number> work) throws CloudKitchenException {
        return db.database().inTransaction(work).longValue();
    }

    // ---- raw reads for assertions ----

    public String text(String sql, Object... params) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                p.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public long number(String sql, Object... params) throws SQLException {
        String value = text(sql, params);
        return value == null ? -1 : Long.parseLong(value);
    }

    public int stockOf(String itemName) throws SQLException {
        return (int) number("SELECT i.stock_qty FROM inventory i JOIN menu_items m ON m.id = i.item_id WHERE m.name = ?", itemName);
    }

    public void execute(String sql, Object... params) throws SQLException {
        try (Connection c = db.connection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                p.setObject(i + 1, params[i]);
            }
            p.executeUpdate();
        }
    }
}

package com.cloudkitchen.service;

import com.cloudkitchen.dao.MenuItemDao;
import com.cloudkitchen.dao.StationDao;
import com.cloudkitchen.dao.StockDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.dto.BrandOption;
import com.cloudkitchen.dto.FieldError;
import com.cloudkitchen.dto.MenuItemRow;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.PersistenceException;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Menu administration and lookup (SHOULD tier; the read paths are needed from Phase 3). */
public final class MenuService {

    public static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");

    private final Database database;
    private final MenuItemDao items;
    private final StockDao stock;
    private final StationDao stations;

    public MenuService(Database database, MenuItemDao items, StockDao stock, StationDao stations) {
        this.database = Objects.requireNonNull(database, "database");
        this.items = Objects.requireNonNull(items, "items");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.stations = Objects.requireNonNull(stations, "stations");
    }

    public Map<Long, MenuItem> findByIds(Collection<Long> ids) throws CloudKitchenException {
        return database.withConnection(c -> items.findByIds(c, ids));
    }

    public List<MenuItem> listAll() throws CloudKitchenException {
        return database.withConnection(c -> items.findAll(c));
    }

    /** The active brands in code order, for the order-entry form and the menu page. */
    public List<BrandOption> activeBrands() throws CloudKitchenException {
        return database.withConnection(c -> stations.findAllActive(c).stream()
                .sorted(java.util.Comparator.comparing(Station::getCode))
                .map(s -> new BrandOption(s.getCode(), s.getBrandName())).toList());
    }

    /** Every menu item, active or not, with its brand, ordered by brand code and name (the administrator's menu page). */
    public List<MenuItemRow> listRows() throws CloudKitchenException {
        return database.withConnection(c -> {
            Map<Long, Station> byId = new java.util.HashMap<>();
            List<MenuItemRow> rows = new ArrayList<>();
            for (MenuItem item : items.findAll(c)) {
                Station station = byId.get(item.getStationId());
                if (station == null) {
                    station = stations.findById(c, item.getStationId()).orElse(null);
                    if (station != null) {
                        byId.put(item.getStationId(), station);
                    }
                }
                rows.add(new MenuItemRow(item.getId(), station == null ? "?" : station.getCode(), station == null ? "" : station.getBrandName(),
                        item.getName(), item.getPrice(), item.isActive()));
            }
            rows.sort(java.util.Comparator.comparing(MenuItemRow::brandCode).thenComparing(MenuItemRow::name));
            return rows;
        });
    }

    /** The active items of one brand, or an empty list for an unknown brand code. */
    public List<MenuItem> activeForStation(String stationCode) throws CloudKitchenException {
        return database.withConnection(c -> {
            var station = stations.findByCode(c, stationCode);
            return station.isEmpty() ? List.<MenuItem>of() : items.findByStation(c, station.get().getId(), true);
        });
    }

    /** TX6: creates the item and its inventory row together; returns the new item id. */
    public long create(String stationCode, String name, BigDecimal price, int initialStock, int reorderLevel) throws CloudKitchenException {
        List<FieldError> errors = new ArrayList<>();
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > OrderItem.MAX_NAME_LENGTH) {
            errors.add(new FieldError("name", "NAME_INVALID", "The name must be 1-" + OrderItem.MAX_NAME_LENGTH + " characters."));
        }
        checkPrice(price, errors);
        if (initialStock < 0 || reorderLevel < 0) {
            errors.add(new FieldError("stock", "STOCK_INVALID", "Stock and reorder level must not be negative."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        try {
            return database.inTransaction(c -> {
                Station station = stations.findByCode(c, stationCode)
                        .orElseThrow(() -> new ValidationException(List.of(new FieldError("stationCode", "BRAND_UNKNOWN", "This brand is not available."))));
                long id = items.insert(c, station.getId(), trimmed, MoneyUtil.scale(price), true);
                stock.create(c, id, initialStock, reorderLevel);
                return id;
            });
        } catch (PersistenceException e) {
            if (SqlErrors.isDuplicateKey(e)) {
                throw new ValidationException(List.of(new FieldError("name", "NAME_TAKEN", "This brand already has an item with that name.")));
            }
            throw e;
        }
    }

    /** Changes the price and the active flag of an item; {@code false} if the item does not exist. */
    public boolean update(long itemId, BigDecimal price, boolean active) throws CloudKitchenException {
        List<FieldError> errors = new ArrayList<>();
        checkPrice(price, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return database.inTransaction(c -> items.update(c, itemId, MoneyUtil.scale(price), active));
    }

    private static void checkPrice(BigDecimal price, List<FieldError> errors) {
        if (price == null || price.signum() < 0 || price.compareTo(MAX_PRICE) > 0 || !MoneyUtil.fitsTwoDecimals(price)) {
            errors.add(new FieldError("price", "PRICE_INVALID", "The price must be between 0.00 and " + MAX_PRICE.toPlainString() + " with at most two decimals."));
        }
    }
}

package com.cloudkitchen.service;

import com.cloudkitchen.dao.StockDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.dto.FieldError;
import com.cloudkitchen.dto.StockRow;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stock lists and the admin adjustment (TX5). The adjustment replaces the current quantity (DATABASE_DESIGN TS-5). */
public final class StockService {

    public static final int MAX_QUANTITY = 1_000_000;

    private final Database database;
    private final StockDao stock;

    public StockService(Database database, StockDao stock) {
        this.database = Objects.requireNonNull(database, "database");
        this.stock = Objects.requireNonNull(stock, "stock");
    }

    public List<StockRow> listAll() throws CloudKitchenException {
        return database.withConnection(c -> stock.findAll(c));
    }

    /** Items whose stock is at or below the reorder level (FR-INV-07). */
    public List<StockRow> listLow() throws CloudKitchenException {
        return database.withConnection(c -> stock.findLow(c));
    }

    public void adjust(long itemId, int stockQty, int reorderLevel) throws CloudKitchenException {
        List<FieldError> errors = new ArrayList<>();
        if (stockQty < 0 || stockQty > MAX_QUANTITY) {
            errors.add(new FieldError("stockQty", "STOCK_INVALID", "The stock must be between 0 and " + MAX_QUANTITY + "."));
        }
        if (reorderLevel < 0 || reorderLevel > MAX_QUANTITY) {
            errors.add(new FieldError("reorderLevel", "REORDER_INVALID", "The reorder level must be between 0 and " + MAX_QUANTITY + "."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        database.inTransaction(c -> {
            if (stock.find(c, itemId).isEmpty()) {
                throw new ValidationException(List.of(new FieldError("itemId", "ITEM_UNKNOWN", "This item does not exist.")));
            }
            stock.set(c, itemId, stockQty, reorderLevel);
            return null;
        });
    }
}

package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.StockDao;

/** Fault injection for {@link StockDao}, e.g. fail the second {@code deduct} of an order. */
public final class FaultInjectingStockDao {

    private FaultInjectingStockDao() {
    }

    public static FaultInjector<StockDao> wrap(StockDao real) {
        return FaultInjector.wrap(StockDao.class, real);
    }
}

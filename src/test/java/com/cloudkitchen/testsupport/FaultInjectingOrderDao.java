package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.OrderDao;

/** Fault injection for {@link OrderDao}, e.g. {@code failAfter("transition", 1, ...)} for the step after the status update. */
public final class FaultInjectingOrderDao {

    private FaultInjectingOrderDao() {
    }

    public static FaultInjector<OrderDao> wrap(OrderDao real) {
        return FaultInjector.wrap(OrderDao.class, real);
    }
}

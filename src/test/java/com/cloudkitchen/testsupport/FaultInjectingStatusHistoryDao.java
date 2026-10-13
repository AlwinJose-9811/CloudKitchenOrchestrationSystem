package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.StatusHistoryDao;

/** Fault injection for {@link StatusHistoryDao}, e.g. fail the history insert right after a status update. */
public final class FaultInjectingStatusHistoryDao {

    private FaultInjectingStatusHistoryDao() {
    }

    public static FaultInjector<StatusHistoryDao> wrap(StatusHistoryDao real) {
        return FaultInjector.wrap(StatusHistoryDao.class, real);
    }
}

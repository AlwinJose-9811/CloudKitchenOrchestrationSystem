package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.dto.ReportRow;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link OrderDao} that holds no database: it answers {@code findForReportAfter} from {@code total} synthetic rows with ids
 * 1..total, honouring {@code afterId} and {@code limit}, counts its queries, records the size of each page and can fail on a
 * chosen query. Every other method throws, so a test cannot rely on one by accident.
 */
public final class SyntheticReportDao {

    private SyntheticReportDao() {
    }

    public static ReportRow row(long id) {
        return new ReportRow(id, Instant.parse("2026-10-10T08:00:00Z").plusSeconds(id), OrderSource.IN_HOUSE, "BIRYANI", "IH-" + id,
                OrderStatus.DELIVERED, 2, new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("100.00"), PaymentMode.CASH, PaymentStatus.PAID);
    }

    public static List<ReportRow> rows(int total) {
        List<ReportRow> all = new ArrayList<>();
        for (int id = 1; id <= total; id++) {
            all.add(row(id));
        }
        return all;
    }

    /** @param failOnQuery the 1-based number of the query that throws an {@link SQLException}, or -1 for none */
    public static OrderDao of(int total, AtomicInteger queries, List<Integer> pageSizes, int failOnQuery) {
        return (OrderDao) Proxy.newProxyInstance(OrderDao.class.getClassLoader(), new Class<?>[] {OrderDao.class}, (proxy, method, args) -> {
            if (!method.getName().equals("findForReportAfter")) {
                throw new UnsupportedOperationException(method.getName());
            }
            int n = queries.incrementAndGet();
            if (n == failOnQuery) {
                throw new SQLException("simulated failure on query " + n);
            }
            long after = (Long) args[3];
            int limit = (Integer) args[4];
            List<ReportRow> page = new ArrayList<>();
            for (long id = after + 1; id <= total && page.size() < limit; id++) {
                page.add(row(id));
            }
            pageSizes.add(page.size());
            return page;
        });
    }
}

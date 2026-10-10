package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Everything the dashboard page shows, taken at one moment. Collections are copied and unmodifiable. */
public record DashboardView(Map<OrderStatus, Long> statusCounts, List<StockRow> lowStock,
                            List<StationView> stations, MetricsView metrics, boolean simulatorRunning,
                            int simulatorRatePerMinute, boolean recovering, long pendingCompensations,
                            long oldestPendingCompensationAgeSeconds) {

    public DashboardView {
        EnumMap<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, statusCounts.getOrDefault(status, 0L));
        }
        statusCounts = Collections.unmodifiableMap(counts);
        lowStock = Collections.unmodifiableList(new ArrayList<>(lowStock));
        stations = Collections.unmodifiableList(new ArrayList<>(stations));
    }
}

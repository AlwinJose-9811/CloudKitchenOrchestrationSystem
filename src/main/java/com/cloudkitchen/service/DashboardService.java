package com.cloudkitchen.service;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.DashboardView;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.exception.CloudKitchenException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Assembles the data side of the dashboard (SDD 6.1): order counts, low stock, one {@link StationView} per station
 * (queue size and capacity, worker state and processed count), the metrics, the simulator and the recovery state. The
 * pipeline parts arrive as suppliers so that package {@code service} stays independent of package {@code pipeline};
 * every part is an immutable copy, so the result can be handed to a page without exposing queues, workers or counters.
 */
public final class DashboardService {

    private final OrderQueryService queries;
    private final StockService stock;
    private final OrderService orders;
    private final Supplier<List<StationView>> stations;
    private final Supplier<MetricsView> metrics;
    private final BooleanSupplier simulatorRunning;
    private final IntSupplier simulatorRate;
    private final BooleanSupplier recovering;

    public DashboardService(OrderQueryService queries, StockService stock, OrderService orders, Supplier<List<StationView>> stations,
                            Supplier<MetricsView> metrics, BooleanSupplier simulatorRunning, IntSupplier simulatorRate,
                            BooleanSupplier recovering) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.stations = Objects.requireNonNull(stations, "stations");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.simulatorRunning = Objects.requireNonNull(simulatorRunning, "simulatorRunning");
        this.simulatorRate = Objects.requireNonNull(simulatorRate, "simulatorRate");
        this.recovering = Objects.requireNonNull(recovering, "recovering");
    }

    /** One consistent-enough picture: each part is read once; counters keep moving between the reads (documented in SDD 7.7). */
    public DashboardView snapshot() throws CloudKitchenException {
        Map<OrderStatus, Long> counts = queries.countByStatus();
        long[] pending = orders.pendingCompensationSummary();
        return new DashboardView(counts, stock.listLow(), stations.get(), metrics.get(), simulatorRunning.getAsBoolean(),
                simulatorRate.getAsInt(), recovering.getAsBoolean(), pending[0], pending[1]);
    }
}

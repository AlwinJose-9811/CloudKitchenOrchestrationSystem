package com.cloudkitchen.web.support;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.service.AuthService;
import com.cloudkitchen.service.CompensationOperator;
import com.cloudkitchen.service.DashboardService;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.service.OrderQueryService;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.ReportService;
import com.cloudkitchen.service.SimulatorControl;
import com.cloudkitchen.service.SnapshotControl;
import com.cloudkitchen.service.StockService;
import com.cloudkitchen.service.WorkerControl;
import java.time.Clock;
import java.util.Objects;

/**
 * The services a servlet or filter may use, handed over by {@code AppBootstrapListener} in the servlet-context attribute
 * {@value #ATTRIBUTE}. The web packages may not import {@code bootstrap} or {@code pipeline} (SDD 2.2, enforced by
 * {@code ArchitectureRulesTest}), so they never see {@code AppContext}; the listener, which may, copies the references
 * it needs into this record. The simulator (C4) is reached through {@link SimulatorControl} and the compensation operator
 * through {@link CompensationOperator}, both service-layer interfaces; the worker panel (C5) through {@link WorkerControl}, implemented by the orchestrator, and the snapshot on demand through {@link SnapshotControl}, an adapter built by {@code AppContext}.
 */
public record AppServices(AppConfig config, Clock clock, AuthService auth, OrderService orders, OrderQueryService orderQueries,
                          DashboardService dashboard, MenuService menu, StockService stock, ReportService reports,
                          SimulatorControl simulator, CompensationOperator compensation, WorkerControl workers,
                          SnapshotControl snapshots) {

    public static final String ATTRIBUTE = "ck.services";

    public AppServices {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(orderQueries, "orderQueries");
        Objects.requireNonNull(dashboard, "dashboard");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(reports, "reports");
        Objects.requireNonNull(simulator, "simulator");
        Objects.requireNonNull(compensation, "compensation");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(snapshots, "snapshots");
    }
}

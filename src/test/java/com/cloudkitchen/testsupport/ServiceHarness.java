package com.cloudkitchen.testsupport;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.dao.StatusHistoryDao;
import com.cloudkitchen.dao.StockDao;
import com.cloudkitchen.dto.MetricsView;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.RoutingService;
import com.cloudkitchen.service.SubmissionHook;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real services over the real embedded database. Only the dispatcher, the clock and the event log are fakes. The
 * three DAOs are wrapped in {@link FaultInjector}s that do nothing until a test configures a fault; they also record
 * the statement sequence, which the atomicity tests use.
 */
public final class ServiceHarness implements AutoCloseable {

    public final FakeClock clock;
    public final DbFixture db;
    public final DbTestSupport t;
    public final MetricsRegistry metrics = new MetricsRegistry();
    public final InFlightRegistry inFlight = new InFlightRegistry();
    public final RecordingOrderEventLog events = new RecordingOrderEventLog();
    public final RoutingService routing = new RoutingService();
    public final FakeDispatchers.Switchable dispatcher = new FakeDispatchers.Switchable();
    public final FaultInjector<OrderDao> orderFaults;
    public final FaultInjector<StockDao> stockFaults;
    public final FaultInjector<StatusHistoryDao> historyFaults;
    public final AtomicReference<SubmissionHook> hook = new AtomicReference<>(SubmissionHook.NO_OP);
    public final OrderService service;
    private MetricsView baseline;

    private ServiceHarness(FakeClock clock, DbFixture db) throws CloudKitchenException {
        this.clock = clock;
        this.db = db;
        this.t = new DbTestSupport(db);
        db.database().withConnection(c -> {
            routing.refresh(t.stations.findAllActive(c));
            return null;
        });
        this.orderFaults = FaultInjectingOrderDao.wrap(t.orders);
        this.stockFaults = FaultInjectingStockDao.wrap(t.stock);
        this.historyFaults = FaultInjectingStatusHistoryDao.wrap(t.history);
        this.service = new OrderService(db.database(), orderFaults.dao(), stockFaults.dao(), historyFaults.dao(), t.menu,
                new OrderValidator(), routing, dispatcher, inFlight, metrics, events, clock, stage -> hook.get().at(stage),
                OrderService.Settings.from(db.config()));
        service.setAccepting(true);
        this.baseline = metrics.view();
    }

    /** What the counters gained since the last {@link #reset()}: tests never see another test's increments. */
    public MetricsView metricsDelta() {
        MetricsView now = metrics.view();
        return new MetricsView(now.submitted() - baseline.submitted(), now.processed() - baseline.processed(),
                now.failed() - baseline.failed(), now.rejected() - baseline.rejected(),
                now.processingSamples() - baseline.processingSamples(), now.averageProcessingMillis());
    }

    public static ServiceHarness create() throws CloudKitchenException, SQLException {
        FakeClock clock = new FakeClock();
        return new ServiceHarness(clock, DbFixture.create(clock));
    }

    /** Back to the seeded state, with no faults, no recorded events and an accepting dispatcher. */
    public void reset() throws SQLException {
        db.resetData();
        events.clear();
        orderFaults.clearFaults();
        orderFaults.resetCounters();
        stockFaults.clearFaults();
        stockFaults.resetCounters();
        historyFaults.clearFaults();
        historyFaults.resetCounters();
        hook.set(SubmissionHook.NO_OP);
        dispatcher.use(new FakeDispatchers.Accepting());
        service.setAccepting(true);
        baseline = metrics.view();
    }

    @Override
    public void close() {
        db.close();
    }
}

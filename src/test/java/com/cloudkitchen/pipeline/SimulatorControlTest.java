package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.service.SimulatorControl;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The narrow interface the web layer uses for the simulator (C4): the real simulator behind {@link SimulatorControl}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatorControlTest {

    private ServiceHarness h;
    private MenuService menu;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
        menu = new MenuService(h.db.database(), h.t.menu, h.t.stock, h.t.stations);
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    @Test
    void theStatusReportsTheStateTheRateTheLimitAndTheCounters() throws Exception {
        FeedSimulator sim = new FeedSimulator(h.service, FeedSimulator.randomGenerator(h.routing, menu, new Random(7)), 30, 600, h.clock, h.events);
        try {
            SimulatorControl control = sim;
            assertEquals(new SimulatorControl.Status(false, 30, 600, 0, 0, 0, 0), control.status());
            assertTrue(control.start(120));
            assertEquals(new SimulatorControl.Status(true, 120, 600, 0, 0, 0, 0).running(), control.status().running());
            assertEquals(120, control.status().ratePerMinute());
            control.setRate(240);
            assertEquals(240, control.status().ratePerMinute());
            assertTrue(control.stop());
            assertFalse(control.status().running());
            sim.produceOne();
            SimulatorControl.Status after = control.status();
            assertEquals(1, after.produced());
            assertEquals(1, after.accepted());
            assertEquals(0, after.notAccepted());
            assertEquals(0, after.errors());
            assertEquals(sim.stats().produced(), after.produced(), "the status agrees with the detailed statistics");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void theInterfaceKeepsTheContractOfTheSimulator() {
        FeedSimulator sim = new FeedSimulator(h.service, seq -> Optional.empty(), 30, 600, h.clock, h.events);
        try {
            SimulatorControl control = sim;
            assertThrows(IllegalArgumentException.class, () -> control.start(0));
            assertThrows(IllegalArgumentException.class, () -> control.start(601));
            assertThrows(IllegalArgumentException.class, () -> control.setRate(601));
            assertFalse(control.stop(), "not running");
            assertTrue(control.start(60));
            assertFalse(control.start(90), "already running");
            assertEquals(60, control.status().ratePerMinute(), "the second start did not change the rate");
            assertTrue(control.stop());
        } finally {
            sim.shutdown();
        }
    }
}

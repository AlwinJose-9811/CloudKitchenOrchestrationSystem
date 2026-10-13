package com.cloudkitchen.service;

/**
 * Test seam inside {@link OrderService#submitOrder}: a test installs a hook that throws a simulated crash (an
 * {@link Error} that no {@code catch} in the service handles) to emulate a process death at one of three points.
 * Production wiring never sets it; the default does nothing.
 */
@FunctionalInterface
public interface SubmissionHook {

    /** The points of the submission flow that can be interrupted. */
    enum Stage { AFTER_TX1, AFTER_OFFER, AFTER_ROUTED }

    SubmissionHook NO_OP = stage -> { };

    void at(Stage stage);
}

package com.cloudkitchen.db;

/**
 * Test seam for the lease stamp protocol (SDD 7.11.15, S-7): a test can stall or advance a fake clock at exactly
 * these points. The default does nothing.
 */
@FunctionalInterface
public interface LeaseStampHook {

    /** The moments at which the protocol can be interrupted. */
    enum Phase { AFTER_STAMP, AFTER_EXECUTE, BEFORE_COMMIT }

    LeaseStampHook NO_OP = phase -> { };

    void at(Phase phase);
}

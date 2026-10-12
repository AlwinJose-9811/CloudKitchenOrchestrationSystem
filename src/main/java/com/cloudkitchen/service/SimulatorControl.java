package com.cloudkitchen.service;

/**
 * What the web layer may do with the order feed simulator (FR-WEB-09, FR-RES-01): start, stop, change the rate and read the
 * state. The simulator itself is a pipeline class; the web packages see only this interface, so they never import
 * {@code pipeline} (SDD 2.2, {@code ArchitectureRulesTest}).
 */
public interface SimulatorControl {

    /** The state of the simulator at one moment, as an immutable copy. */
    record Status(boolean running, int ratePerMinute, int maxRatePerMinute, long produced, long accepted, long notAccepted, long errors) {
    }

    /** Starts producing at {@code ratePerMinute}; {@code false} if it is already running. @throws IllegalArgumentException if the rate is out of range */
    boolean start(int ratePerMinute);

    /** Stops producing; {@code false} if it was not running. */
    boolean stop();

    /** Changes the rate, at once if running. @throws IllegalArgumentException if the rate is out of range */
    void setRate(int ratePerMinute);

    Status status();
}

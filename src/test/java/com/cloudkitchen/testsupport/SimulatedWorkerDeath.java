package com.cloudkitchen.testsupport;

/**
 * Thrown from a {@code WorkerHook} to kill a worker thread. It is a {@link VirtualMachineError} because the worker
 * deliberately lets those escape its per-order catch (an OutOfMemoryError is the realistic cause of death).
 */
public final class SimulatedWorkerDeath extends VirtualMachineError {

    private static final long serialVersionUID = 1L;

    public SimulatedWorkerDeath(String where) {
        super("simulated worker death at " + where);
    }
}

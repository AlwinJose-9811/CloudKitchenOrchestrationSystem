package com.cloudkitchen.testsupport;

/** An {@link Error} that no service {@code catch} handles: a submitter or caller "dies" at a hook point. */
public final class SimulatedCrash extends Error {

    private static final long serialVersionUID = 1L;

    public SimulatedCrash(String where) {
        super("simulated crash at " + where);
    }
}

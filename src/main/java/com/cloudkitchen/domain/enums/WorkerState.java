package com.cloudkitchen.domain.enums;

/** State of a station worker as shown on the dashboard. */
public enum WorkerState {
    STARTING, IDLE, PROCESSING, PAUSED, STOPPING, STOPPED,
    /** The monitor gave up restarting the worker after its restart budget (SDD 7.11.7). */
    DEAD
}

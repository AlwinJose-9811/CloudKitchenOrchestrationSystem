package com.cloudkitchen.pipeline;

import com.cloudkitchen.config.AppConfig;

/**
 * Every tunable of the pipeline in one immutable record, taken from {@link AppConfig} in production and built
 * directly by tests (short delays, small queues). Durations are in the units named by the component.
 */
public record PipelineSettings(int queueCapacity, int offerTimeoutMs, int prepMs, int packMs, int deliveryMs,
                               int retryMax, int retryBackoffMs, int leaseRenewMs, int reacquireMax, int pollMs,
                               int stallMs, int restartMax, int restartWindowSeconds, int healthIntervalSeconds,
                               int reconcileBatch, int dispatchWindowSeconds) {

    /** The worker's queue poll timeout (SDD 7.3). */
    public static final int POLL_MS = 500;

    public static PipelineSettings from(AppConfig c) {
        return new PipelineSettings(c.stationQueueCapacity(), c.queueOfferTimeoutMs(), c.workerPrepMs(), c.workerPackMs(),
                c.workerDeliveryMs(), c.workerRetryMax(), c.workerRetryBackoffMs(), c.workerLeaseRenewSeconds() * 1000,
                c.workerReacquireMax(), POLL_MS, c.workerStallSeconds() * 1000, c.workerRestartMax(),
                c.workerRestartWindowSeconds(), c.workerHealthIntervalSeconds(), c.workerReconcileBatch(),
                c.recoveryDispatchWindowSeconds());
    }

    public PipelineSettings withQueue(int capacity, int offerTimeoutMs) {
        return new PipelineSettings(capacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }

    public PipelineSettings withDelays(int prepMs, int packMs, int deliveryMs) {
        return new PipelineSettings(queueCapacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }

    public PipelineSettings withRetry(int retryMax, int retryBackoffMs) {
        return new PipelineSettings(queueCapacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }

    public PipelineSettings withPollMs(int pollMs) {
        return new PipelineSettings(queueCapacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }

    public PipelineSettings withHealth(int stallMs, int restartMax, int restartWindowSeconds) {
        return new PipelineSettings(queueCapacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }

    public PipelineSettings withLease(int leaseRenewMs, int reacquireMax) {
        return new PipelineSettings(queueCapacity, offerTimeoutMs, prepMs, packMs, deliveryMs, retryMax, retryBackoffMs,
                leaseRenewMs, reacquireMax, pollMs, stallMs, restartMax, restartWindowSeconds, healthIntervalSeconds,
                reconcileBatch, dispatchWindowSeconds);
    }
}

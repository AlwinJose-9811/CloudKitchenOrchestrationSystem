package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.WorkerState;

/** What the dashboard shows about one station worker. */
public record WorkerView(String stationCode, WorkerState state, Long currentOrderId, long processedCount,
                         int consecutiveFailures, int generation, int restarts, long heartbeatAgeMillis,
                         Long leasedOrderId) {
}

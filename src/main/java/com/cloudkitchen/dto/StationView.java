package com.cloudkitchen.dto;

/** What the dashboard shows about one station: its queue and its worker. */
public record StationView(String stationCode, String brandName, int queueSize, int queueCapacity, boolean paused,
                          WorkerView worker) {
}

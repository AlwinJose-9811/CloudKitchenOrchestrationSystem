package com.cloudkitchen.dto;

/** An immutable copy of the counters at one moment; see {@code MetricsRegistry}. */
public record MetricsView(long submitted, long processed, long failed, long rejected, long processingSamples,
                          double averageProcessingMillis) {
}

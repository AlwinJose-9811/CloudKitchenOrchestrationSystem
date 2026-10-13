package com.cloudkitchen.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** UTC helpers. All timestamps in the system are UTC; the database stores {@code DATETIME(3)}, i.e. milliseconds. */
public final class TimeUtil {

    private TimeUtil() {
    }

    /** Drops anything finer than a millisecond so that a value survives a database round trip unchanged. */
    public static Instant truncateToMillis(Instant instant) {
        return Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MILLIS);
    }

    public static LocalDateTime toUtcLocal(Instant instant) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }

    public static Instant fromUtcLocal(LocalDateTime utc) {
        return Objects.requireNonNull(utc, "utc").toInstant(ZoneOffset.UTC);
    }

    /** First instant of the UTC day (inclusive). */
    public static Instant startOfUtcDay(LocalDate day) {
        return Objects.requireNonNull(day, "day").atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** First instant of the next UTC day: the exclusive upper bound of a daily report. */
    public static Instant endOfUtcDayExclusive(LocalDate day) {
        return startOfUtcDay(day).plus(1, ChronoUnit.DAYS);
    }
}

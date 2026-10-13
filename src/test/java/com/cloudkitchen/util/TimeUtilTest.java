package com.cloudkitchen.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void truncationDropsEverythingBelowTheMillisecond() {
        assertEquals(Instant.parse("2026-10-10T12:00:00.123Z"), TimeUtil.truncateToMillis(Instant.parse("2026-10-10T12:00:00.123999999Z")));
        assertEquals(Instant.parse("2026-10-10T12:00:00.123Z"), TimeUtil.truncateToMillis(Instant.parse("2026-10-10T12:00:00.123Z")));
        assertThrows(NullPointerException.class, () -> TimeUtil.truncateToMillis(null));
    }

    @Test
    void utcConversionRoundTrips() {
        Instant instant = Instant.parse("2026-10-10T23:59:59.999Z");
        LocalDateTime local = TimeUtil.toUtcLocal(instant);
        assertEquals(LocalDateTime.of(2026, 10, 10, 23, 59, 59, 999_000_000), local);
        assertEquals(instant, TimeUtil.fromUtcLocal(local));
    }

    @Test
    void aUtcDayIsHalfOpen() {
        LocalDate day = LocalDate.of(2026, 10, 10);
        assertEquals(Instant.parse("2026-10-10T00:00:00Z"), TimeUtil.startOfUtcDay(day));
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), TimeUtil.endOfUtcDayExclusive(day));
        assertEquals(TimeUtil.endOfUtcDayExclusive(day), TimeUtil.startOfUtcDay(day.plusDays(1)), "consecutive days leave no gap and no overlap");
    }
}

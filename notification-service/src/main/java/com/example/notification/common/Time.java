package com.example.notification.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class Time {

    private Time() {
    }

    /** Now in UTC, with the precision that PostgreSQL stores (microseconds). */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** MNY-3: the daily limit counts per UTC calendar day. */
    public static OffsetDateTime startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}

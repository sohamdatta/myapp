package com.example.loginapp.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Pure helpers for punches: distance between two positions, and which work date a moment belongs to. */
public final class PunchChecks {

    /** A position reported less precisely than this cannot prove someone is at the location. */
    public static final int MAX_ACCURACY_M = 200;

    /** How long after a night shift's end a punch still counts towards the day the shift started. */
    private static final Duration NIGHT_SHIFT_TAIL = Duration.ofHours(4);

    private PunchChecks() {}

    /** Great-circle distance in metres. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static boolean crossesMidnight(LocalTime shiftStart, LocalTime shiftEnd) {
        return !shiftEnd.isAfter(shiftStart);
    }

    /**
     * The local time at which a work date begins. For an ordinary shift that is
     * midnight. For a shift that ends the next day it is four hours after the
     * shift's end, so a late punch-out still belongs to the day the shift started.
     */
    public static Duration dayStartOffset(LocalTime shiftStart, LocalTime shiftEnd) {
        if (!crossesMidnight(shiftStart, shiftEnd)) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(shiftEnd.toSecondOfDay()).plus(NIGHT_SHIFT_TAIL);
    }

    /** The work date a moment belongs to, in the location's time zone. */
    public static LocalDate workDate(Instant at, ZoneId zone, LocalTime shiftStart, LocalTime shiftEnd) {
        return at.atZone(zone).toLocalDateTime().minus(dayStartOffset(shiftStart, shiftEnd)).toLocalDate();
    }

    /** The first moment of a work date. */
    public static Instant dayStart(LocalDate workDate, ZoneId zone, LocalTime shiftStart, LocalTime shiftEnd) {
        return workDate.atStartOfDay().plus(dayStartOffset(shiftStart, shiftEnd)).atZone(zone).toInstant();
    }
}

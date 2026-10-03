package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.PunchChecks;

class PunchChecksTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NINE_THIRTY = LocalTime.of(9, 30);
    private static final LocalTime SIX_THIRTY_PM = LocalTime.of(18, 30);
    private static final LocalTime TEN_PM = LocalTime.of(22, 0);
    private static final LocalTime SIX_AM = LocalTime.of(6, 0);

    @Test
    void distanceBetweenTwoKnownPoints() {
        // Victoria Memorial to Howrah Bridge, Kolkata: about 4.7 km
        double metres = PunchChecks.distanceMeters(22.5448, 88.3426, 22.5851, 88.3468);
        assertTrue(metres > 4_300 && metres < 4_700, "was " + metres);
        assertEquals(0, PunchChecks.distanceMeters(22.5726, 88.3639, 22.5726, 88.3639), 0.001);
        // one thousandth of a degree of latitude is about 111 m
        assertEquals(111, PunchChecks.distanceMeters(22.5726, 88.3639, 22.5736, 88.3639), 1.5);
    }

    @Test
    void aShiftEndingAtOrBeforeItsStartCrossesMidnight() {
        assertFalse(PunchChecks.crossesMidnight(NINE_THIRTY, SIX_THIRTY_PM));
        assertTrue(PunchChecks.crossesMidnight(TEN_PM, SIX_AM));
    }

    @Test
    void anOrdinaryShiftUsesTheCalendarDateOfTheLocation() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        // 00:30 in India is still the previous day in UTC
        assertEquals(day, PunchChecks.workDate(at(day, "00:30"), IST, NINE_THIRTY, SIX_THIRTY_PM));
        assertEquals(day, PunchChecks.workDate(at(day, "23:59"), IST, NINE_THIRTY, SIX_THIRTY_PM));
        assertEquals(at(day, "00:00"), PunchChecks.dayStart(day, IST, NINE_THIRTY, SIX_THIRTY_PM));
    }

    @Test
    void aNightShiftBelongsToTheDayItStarted() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertEquals(day, PunchChecks.workDate(at(day, "21:50"), IST, TEN_PM, SIX_AM));
        assertEquals(day, PunchChecks.workDate(at(day.plusDays(1), "06:20"), IST, TEN_PM, SIX_AM));
        assertEquals(day, PunchChecks.workDate(at(day.plusDays(1), "09:59"), IST, TEN_PM, SIX_AM));
        // four hours after the shift's end the next work date begins
        assertEquals(day.plusDays(1), PunchChecks.workDate(at(day.plusDays(1), "10:00"), IST, TEN_PM, SIX_AM));
        assertEquals(at(day, "10:00"), PunchChecks.dayStart(day, IST, TEN_PM, SIX_AM));
    }

    private static Instant at(LocalDate date, String time) {
        return LocalDateTime.of(date, LocalTime.parse(time)).atZone(IST).toInstant();
    }
}

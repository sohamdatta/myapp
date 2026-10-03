package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.DayCalculator;
import com.example.loginapp.core.DayCalculator.Input;
import com.example.loginapp.core.DayCalculator.Punch;
import com.example.loginapp.core.DayCalculator.Result;
import com.example.loginapp.core.DayCalculator.Schedule;

/** The day calculation is a pure function, so every rule can be checked without a database. */
class DayCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);
    private static final Schedule GENERAL = new Schedule("General", LocalTime.of(9, 30), LocalTime.of(18, 30),
            10, 480, 240, Set.of(0), IST);
    private static final Schedule NIGHT = new Schedule("Night", LocalTime.of(22, 0), LocalTime.of(6, 0),
            10, 480, 240, Set.of(0), IST);

    @Test
    void noAssignmentMeansNotAssigned() {
        Result r = DayCalculator.calculate(new Input(THURSDAY, null, null, null, List.of()));
        assertEquals("not_assigned", r.status());
    }

    @Test
    void aFullDayIsPresentAndOnTimeWithinGrace() {
        Result r = day(THURSDAY, GENERAL, "09:40 in", "18:30 out");
        assertEquals("present", r.status());
        assertEquals(530, r.workedMinutes());
        assertEquals(0, r.lateMinutes());
        assertEquals(0, r.earlyMinutes());
        assertEquals(50, r.overtimeMinutes());
        assertFalse(r.incomplete());
        assertEquals("Result: present", r.trail().get(r.trail().size() - 1));
    }

    @Test
    void latenessIsCountedFromTheEndOfGrace() {
        Result r = day(THURSDAY, GENERAL, "10:00 in", "18:30 out");
        assertEquals(20, r.lateMinutes());
        assertTrue(r.trail().stream().anyMatch(t -> t.contains("20 min late")), r.trail().toString());
    }

    @Test
    void shortHoursGiveAHalfDayThenAbsent() {
        Result half = day(THURSDAY, GENERAL, "09:30 in", "14:00 out");
        assertEquals("half_day", half.status());
        assertEquals(270, half.earlyMinutes());
        Result absent = day(THURSDAY, GENERAL, "09:30 in", "11:00 out");
        assertEquals("absent", absent.status());
        assertEquals(90, absent.workedMinutes());
    }

    @Test
    void breaksAreNotCountedAsWork() {
        Result r = day(THURSDAY, GENERAL, "09:30 in", "13:00 out", "14:00 in", "18:30 out");
        assertEquals(480, r.workedMinutes());
        assertEquals("present", r.status());
        assertEquals(0, r.overtimeMinutes());
    }

    @Test
    void noPunchesIsAbsent() {
        Result r = day(THURSDAY, GENERAL);
        assertEquals("absent", r.status());
        assertNull(r.firstIn());
    }

    @Test
    void aMissingOutIsFlaggedAndNotCounted() {
        Result r = day(THURSDAY, GENERAL, "09:30 in");
        assertTrue(r.incomplete());
        assertEquals(0, r.workedMinutes());
        assertEquals("absent", r.status());
        Result outOnly = day(THURSDAY, GENERAL, "18:30 out");
        assertTrue(outOnly.incomplete());
    }

    @Test
    void weeklyOffAndHolidayWinOverAbsence() {
        assertEquals("weekly_off", day(SUNDAY, GENERAL).status());
        Result holiday = DayCalculator.calculate(new Input(THURSDAY, GENERAL, "Gandhi Jayanti", null, List.of()));
        assertEquals("holiday", holiday.status());
        assertTrue(holiday.trail().contains("Holiday: Gandhi Jayanti"));
    }

    @Test
    void workOnADayOffIsAllOvertime() {
        Result r = day(SUNDAY, GENERAL, "10:00 in", "14:00 out");
        assertEquals("weekly_off", r.status());
        assertEquals(240, r.overtimeMinutes());
    }

    @Test
    void anApprovedAwayRequestCountsAsAFullDay() {
        Result r = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "work_from_home", List.of()));
        assertEquals("work_from_home", r.status());
        Result onDuty = DayCalculator.calculate(new Input(SUNDAY, GENERAL, null, "on_duty", List.of()));
        assertEquals("on_duty", onDuty.status());
    }

    @Test
    void anApprovedCorrectionReplacesTheDaysOwnPunches() {
        List<Punch> punches = new ArrayList<>(punches(THURSDAY, GENERAL, "11:45 in"));
        punches.add(new Punch(at(THURSDAY, "09:30"), "in", "request"));
        punches.add(new Punch(at(THURSDAY, "18:30"), "out", "request"));
        Result r = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, punches));
        assertEquals("present", r.status());
        assertEquals(0, r.lateMinutes());
        assertFalse(r.incomplete());
        assertTrue(r.trail().contains("Times taken from an approved correction request"));
    }

    @Test
    void aNightShiftIsMeasuredAgainstTheNextMorning() {
        List<Punch> punches = List.of(
                new Punch(at(THURSDAY, "21:55"), "in", "web"),
                new Punch(at(THURSDAY.plusDays(1), "06:05"), "out", "web"));
        Result r = DayCalculator.calculate(new Input(THURSDAY, NIGHT, null, null, punches));
        assertEquals("present", r.status());
        assertEquals(490, r.workedMinutes());
        assertEquals(0, r.lateMinutes());
        assertEquals(0, r.earlyMinutes());
    }

    @Test
    void theSameInputAlwaysGivesTheSameResult() {
        assertEquals(day(THURSDAY, GENERAL, "09:50 in", "17:00 out"), day(THURSDAY, GENERAL, "09:50 in", "17:00 out"));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Result day(LocalDate date, Schedule schedule, String... punches) {
        return DayCalculator.calculate(new Input(date, schedule, null, null, punches(date, schedule, punches)));
    }

    /** Each punch is written as "HH:mm in" or "HH:mm out". */
    private static List<Punch> punches(LocalDate date, Schedule schedule, String... punches) {
        List<Punch> out = new ArrayList<>();
        for (String p : punches) {
            String[] parts = p.split(" ");
            out.add(new Punch(at(date, parts[0]), parts[1], "web"));
        }
        return out;
    }

    private static Instant at(LocalDate date, String time) {
        return LocalDateTime.of(date, LocalTime.parse(time)).atZone(IST).toInstant();
    }
}

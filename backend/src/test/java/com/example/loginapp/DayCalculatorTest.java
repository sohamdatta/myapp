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
import com.example.loginapp.core.DayCalculator.Leave;
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

    // ── leave and remote days ───────────────────────────────────────────

    @Test
    void fullDayLeaveDecidesTheDayWhateverElseHappened() {
        Leave earned = new Leave("Earned leave", null, false);
        Result r = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "work_from_home",
                punches(THURSDAY, GENERAL, "09:30 in", "18:30 out"), earned, null, true, false));
        assertEquals("on_leave", r.status());
        assertEquals(1.0, r.leaveDays());
        assertFalse(r.leaveUnpaid());
        assertEquals(0, r.overtimeMinutes());
        assertTrue(r.trail().contains("Earned leave, approved"));
        assertTrue(r.trail().contains("Punched on a leave day; the punches are recorded but not counted"));

        Result unpaid = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, List.of(),
                new Leave("Unpaid leave", null, true), null, false, false));
        assertEquals("on_leave", unpaid.status());
        assertTrue(unpaid.leaveUnpaid());
    }

    @Test
    void halfDayLeaveNeedsHalfADaysHoursInTheOtherHalf() {
        Leave morning = new Leave("Casual leave", "first", false);
        Result enough = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null,
                punches(THURSDAY, GENERAL, "14:00 in", "18:30 out"), morning, null, false, false));
        assertEquals("present", enough.status());
        assertEquals(0.5, enough.leaveDays());
        assertEquals(0, enough.lateMinutes());                 // the morning is leave, so arriving at 14:00 is not late

        Result tooFew = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null,
                punches(THURSDAY, GENERAL, "15:00 in", "17:00 out"), morning, null, false, false));
        assertEquals("half_day", tooFew.status());
        assertEquals(90, tooFew.earlyMinutes());

        Leave afternoon = new Leave("Casual leave", "second", false);
        Result early = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null,
                punches(THURSDAY, GENERAL, "09:30 in", "13:45 out"), afternoon, null, false, false));
        assertEquals("present", early.status());
        assertEquals(0, early.earlyMinutes());

        Result nothing = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, List.of(), morning, null, false, false));
        assertEquals("half_day", nothing.status());
    }

    @Test
    void aRemoteDayIsAFullDayUnlessPunchesAreRequired() {
        Result regular = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, List.of(), null, null, true, false));
        assertEquals("work_from_home", regular.status());
        assertTrue(regular.remote());
        assertTrue(regular.trail().contains("Regular remote day"));

        Result noPunches = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "work_from_home", List.of(), null, null, true, true));
        assertEquals("absent", noPunches.status());
        assertTrue(noPunches.remote());

        Result punched = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "work_from_home",
                punches(THURSDAY, GENERAL, "10:00 in", "18:30 out"), null, null, true, true));
        assertEquals("present", punched.status());
        assertEquals(20, punched.lateMinutes());
        assertTrue(punched.remote());
    }

    @Test
    void onDutyBeatsRemoteAndAHolidayBeatsPunchRequiredRemote() {
        assertEquals("on_duty", DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "on_duty", List.of(),
                null, null, true, false)).status());
        assertEquals("holiday", DayCalculator.calculate(new Input(THURSDAY, GENERAL, "Gandhi Jayanti", null, List.of(),
                null, null, true, true)).status());
        // a half day at home that needs no punches counts that half
        Result r = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, "work_from_home",
                punches(THURSDAY, GENERAL, "14:00 in", "18:30 out"), null, "first", true, false));
        assertEquals("present", r.status());
        assertEquals(0, r.lateMinutes());
    }

    @Test
    void theSameInputAlwaysGivesTheSameResult() {
        assertEquals(day(THURSDAY, GENERAL, "09:50 in", "17:00 out"), day(THURSDAY, GENERAL, "09:50 in", "17:00 out"));
    }

    // ── present by default, and device punches ──────────────────────────

    private static Result present(LocalDate date, Leave leave, DayCalculator.Mark mark, List<Punch> punches) {
        return DayCalculator.calculate(new Input(date, GENERAL, null, null, punches, leave, null, false, false, true, mark));
    }

    @Test
    void aPresentByDefaultDayIsPresentWithoutPunches() {
        Result r = present(THURSDAY, null, null, List.of());
        assertEquals("present", r.status());
        assertEquals(0, r.lateMinutes());
        assertTrue(r.trail().contains("Present by default; no exception marked"));
    }

    @Test
    void aMarkDecidesThePresentByDefaultDay() {
        DayCalculator.Mark absent = new DayCalculator.Mark("absent", "Did not come in", "Priya Shah");
        Result r = present(THURSDAY, null, absent, List.of());
        assertEquals("absent", r.status());
        assertTrue(r.trail().contains("Marked absent by Priya Shah: Did not come in"));
        assertEquals("half_day", present(THURSDAY, null, new DayCalculator.Mark("half_day", "Left at lunch", "HR"), List.of()).status());
    }

    @Test
    void halfALeaveDayAndAMarkMeetInTheMiddle() {
        Leave morning = new Leave("Casual leave", "first", false);
        assertEquals("present", present(THURSDAY, morning, null, List.of()).status());
        Result absentAfter = present(THURSDAY, morning, new DayCalculator.Mark("absent", "Not back after leave", "HR"), List.of());
        assertEquals("half_day", absentAfter.status());
        assertEquals(0.5, absentAfter.leaveDays());
        // whole-day leave, holidays and weekly offs come first, as at any location
        assertEquals("on_leave", present(THURSDAY, new Leave("Casual leave", null, false), null, List.of()).status());
        assertEquals("weekly_off", present(THURSDAY.plusDays(3), null, null, List.of()).status());
    }

    @Test
    void punchesAtAPresentByDefaultLocationAreKeptButDoNotDecide() {
        Result r = present(THURSDAY, null, null, punches(THURSDAY, GENERAL, "11:00 in", "12:00 out"));
        assertEquals("present", r.status());
        assertEquals(60, r.workedMinutes());
        assertEquals(0, r.lateMinutes());
    }

    @Test
    void devicePunchesGoInAndOutByTheirOrder() {
        // The device sent every punch as an "in": nobody pressed its keys.
        List<Punch> raw = List.of(new Punch(at(THURSDAY, "09:31"), "in", "device"), new Punch(at(THURSDAY, "13:00"), "in", "device"),
                new Punch(at(THURSDAY, "13:45"), "in", "device"), new Punch(at(THURSDAY, "18:40"), "in", "device"));
        Result r = DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, raw));
        assertEquals("present", r.status());
        assertEquals(3 * 60 + 29 + 4 * 60 + 55, r.workedMinutes());
        assertFalse(r.incomplete());
        assertTrue(r.trail().contains("Device punches taken as in and out by their order"));
        // HR's manual punch keeps its direction; the device carries on from it
        List<Punch> mixed = List.of(new Punch(at(THURSDAY, "09:30"), "in", "manual"), new Punch(at(THURSDAY, "18:30"), "in", "device"));
        assertEquals(540, DayCalculator.calculate(new Input(THURSDAY, GENERAL, null, null, mixed)).workedMinutes());
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

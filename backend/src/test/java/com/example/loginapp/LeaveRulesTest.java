package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.LeaveAccrual;
import com.example.loginapp.core.LeaveAccrual.Entry;
import com.example.loginapp.core.LeaveDays;

/** The two pure leave calculations: how many days a request uses, and which accruals are due. */
class LeaveRulesTest {

    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final Set<LocalDate> HOLIDAYS = Set.of(LocalDate.of(2026, 10, 2));
    /** Sundays, and the holiday on Friday 2 October. */
    private static final Predicate<LocalDate> OFF = d -> d.getDayOfWeek() == DayOfWeek.SUNDAY || HOLIDAYS.contains(d);

    // ── counting days ───────────────────────────────────────────────────

    @Test
    void daysOffInsideALeaveAreSkipped() {
        // Thu 1 to Tue 6 October: Friday is a holiday and Sunday a weekly off
        LeaveDays.Result r = LeaveDays.count(THURSDAY, THURSDAY.plusDays(5), null, false, OFF);
        assertEquals(0, r.days().compareTo(BigDecimal.valueOf(4)));
        assertEquals(List.of(THURSDAY, THURSDAY.plusDays(2), THURSDAY.plusDays(4), THURSDAY.plusDays(5)), r.counted());
    }

    @Test
    void daysOffInsideALeaveCountWhenTheTypeSaysSo() {
        LeaveDays.Result r = LeaveDays.count(THURSDAY, THURSDAY.plusDays(5), null, true, OFF);
        assertEquals(0, r.days().compareTo(BigDecimal.valueOf(6)));
        // but a day off at either end is never leave
        LeaveDays.Result ends = LeaveDays.count(THURSDAY.plusDays(1), THURSDAY.plusDays(3), null, true, OFF);   // Fri (holiday) to Sun
        assertEquals(List.of(THURSDAY.plusDays(2)), ends.counted());
    }

    @Test
    void aHalfDayIsHalfAndOnlyForOneDate() {
        assertEquals(0, LeaveDays.count(THURSDAY, THURSDAY, "first", false, OFF).days().compareTo(new BigDecimal("0.5")));
        assertThrows(IllegalArgumentException.class, () -> LeaveDays.count(THURSDAY, THURSDAY.plusDays(1), "first", false, OFF));
        assertThrows(IllegalArgumentException.class, () -> LeaveDays.count(THURSDAY, THURSDAY.minusDays(1), null, false, OFF));
    }

    @Test
    void aRangeOfDaysOffCountsNothing() {
        LeaveDays.Result r = LeaveDays.count(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4), null, false, OFF);   // a Sunday
        assertEquals(0, r.days().signum());
        assertTrue(r.counted().isEmpty());
        assertEquals(0, LeaveDays.count(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), "second", false, OFF).days().signum());
    }

    // ── accrual ─────────────────────────────────────────────────────────

    @Test
    void monthlyAccrualIsATwelfthOnTheFirstOfEachMonth() {
        List<Entry> due = LeaveAccrual.due("monthly", new BigDecimal("18"), true, 1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 20));
        assertEquals(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)),
                due.stream().map(Entry::onDate).toList());
        assertTrue(due.stream().allMatch(e -> e.days().compareTo(new BigDecimal("1.50")) == 0));
        // a quota that does not divide by twelve is rounded to two decimals
        assertEquals(new BigDecimal("1.25"), LeaveAccrual.due("monthly", new BigDecimal("15"), true, 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)).get(0).days());
    }

    @Test
    void theMonthOfJoiningCountsWhenJoinedByTheFifteenth() {
        List<Entry> early = LeaveAccrual.due("monthly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 30));
        assertEquals(List.of(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 1)), early.stream().map(Entry::onDate).toList());
        List<Entry> late = LeaveAccrual.due("monthly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 3, 16), LocalDate.of(2026, 4, 30));
        assertEquals(List.of(LocalDate.of(2026, 4, 1)), late.stream().map(Entry::onDate).toList());
    }

    @Test
    void yearlyAccrualIsTheQuotaOnTheFirstDayOfTheLeaveYear() {
        List<Entry> due = LeaveAccrual.due("yearly", new BigDecimal("12"), true, 1, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 6, 1));
        assertEquals(List.of(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1)), due.stream().map(Entry::onDate).toList());
        assertEquals(0, due.get(1).days().compareTo(BigDecimal.valueOf(12)));
        // next year's quota is not due until the year starts
        assertEquals(1, LeaveAccrual.due("yearly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31)).size());
    }

    @Test
    void aJoinerGetsAShareOfTheYearlyQuota() {
        // joined 10 July: July to December is six months of twelve
        List<Entry> due = LeaveAccrual.due("yearly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 1));
        assertEquals(List.of(new Entry(LocalDate.of(2026, 7, 10), new BigDecimal("6.00"))), due);
        // joined 20 July: August to December is five months
        assertEquals(new BigDecimal("5.00"), LeaveAccrual.due("yearly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 1)).get(0).days());
        // without proration the joiner gets the whole quota
        assertEquals(new BigDecimal("12.00"), LeaveAccrual.due("yearly", new BigDecimal("12"), false, 1, LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 1)).get(0).days());
    }

    @Test
    void aLeaveYearCanStartInApril() {
        assertEquals(LocalDate.of(2025, 4, 1), LeaveAccrual.yearStart(LocalDate.of(2026, 2, 10), 4));
        assertEquals(LocalDate.of(2026, 3, 31), LeaveAccrual.yearEnd(LocalDate.of(2026, 2, 10), 4));
        assertEquals(2025, LeaveAccrual.yearLabel(LocalDate.of(2026, 2, 10), 4));
        assertEquals(2026, LeaveAccrual.yearLabel(LocalDate.of(2026, 4, 1), 4));
        List<Entry> due = LeaveAccrual.due("yearly", new BigDecimal("12"), true, 4, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 4, 1));
        assertEquals(List.of(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 4, 1)), due.stream().map(Entry::onDate).toList());
    }

    @Test
    void nothingIsDueWithoutAccrualOrBeforeTheStart() {
        assertTrue(LeaveAccrual.due("none", new BigDecimal("12"), true, 1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 1)).isEmpty());
        assertTrue(LeaveAccrual.due("monthly", BigDecimal.ZERO, true, 1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 1)).isEmpty());
        assertTrue(LeaveAccrual.due("monthly", new BigDecimal("12"), true, 1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)).isEmpty());
    }
}

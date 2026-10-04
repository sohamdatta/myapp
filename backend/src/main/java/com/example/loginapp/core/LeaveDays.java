package com.example.loginapp.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Counts the days of a leave request. A pure function: it is told which dates
 * are days off for the user and reads nothing else.
 */
public final class LeaveDays {

    private LeaveDays() {}

    /**
     * @param days    how many days the request uses
     * @param counted the dates that count, in order
     */
    public record Result(BigDecimal days, List<LocalDate> counted) {}

    /**
     * @param half         null for whole days, otherwise first or second; only for a single date
     * @param countDaysOff true when weekly offs and holidays inside the leave count as leave
     * @param isDayOff     whether a date is a weekly off or a holiday for the user
     */
    public static Result count(LocalDate from, LocalDate to, String half, boolean countDaysOff, Predicate<LocalDate> isDayOff) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The leave ends before it starts");
        }
        if (half != null && !from.equals(to)) {
            throw new IllegalArgumentException("A half day is for a single date");
        }
        List<LocalDate> counted = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            // A day off counts only when the leave type says so and the leave runs on both sides of it.
            boolean inside = d.isAfter(from) && d.isBefore(to);
            if (!isDayOff.test(d) || (countDaysOff && inside)) {
                counted.add(d);
            }
        }
        BigDecimal days = half != null && !counted.isEmpty() ? new BigDecimal("0.5") : BigDecimal.valueOf(counted.size());
        return new Result(days, counted);
    }
}

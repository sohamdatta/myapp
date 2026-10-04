package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which accrual entries are due. A pure function, so the service can
 * call it on every read: an entry is identified by its date, and writing the
 * same one twice is refused by the database.
 */
public final class LeaveAccrual {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private LeaveAccrual() {}

    public record Entry(LocalDate onDate, BigDecimal days) {}

    /** The first day of the leave year that contains the date. startMonth is 1 (January) or 4 (April). */
    public static LocalDate yearStart(LocalDate date, int startMonth) {
        LocalDate start = LocalDate.of(date.getYear(), startMonth, 1);
        return date.isBefore(start) ? start.minusYears(1) : start;
    }

    public static LocalDate yearEnd(LocalDate date, int startMonth) {
        return yearStart(date, startMonth).plusYears(1).minusDays(1);
    }

    /** The year a leave year is known by: the calendar year in which it starts. */
    public static int yearLabel(LocalDate date, int startMonth) {
        return yearStart(date, startMonth).getYear();
    }

    /**
     * Every entry due from the day the user began earning this leave up to a date, oldest first.
     *
     * @param accrual none, monthly or yearly
     * @param since   the later of the user's joining date and the date the leave type was created
     */
    public static List<Entry> due(String accrual, BigDecimal yearlyQuota, boolean prorateJoiners, int startMonth,
                                  LocalDate since, LocalDate upTo) {
        List<Entry> out = new ArrayList<>();
        if (accrual.equals("none") || yearlyQuota.signum() <= 0 || upTo.isBefore(since)) {
            return out;
        }
        if (accrual.equals("monthly")) {
            BigDecimal perMonth = yearlyQuota.divide(TWELVE, 2, RoundingMode.HALF_UP);
            LocalDate month = since.withDayOfMonth(1);
            if (since.getDayOfMonth() <= 15) {
                out.add(new Entry(since, perMonth));     // the month of joining counts when joined by the 15th
            }
            for (month = month.plusMonths(1); !month.isAfter(upTo); month = month.plusMonths(1)) {
                out.add(new Entry(month, perMonth));
            }
            return out;
        }
        for (LocalDate start = yearStart(since, startMonth); !start.isAfter(upTo); start = start.plusYears(1)) {
            if (!start.isBefore(since)) {
                out.add(new Entry(start, yearlyQuota.setScale(2, RoundingMode.HALF_UP)));
            } else if (!prorateJoiners) {
                out.add(new Entry(since, yearlyQuota.setScale(2, RoundingMode.HALF_UP)));
            } else {
                LocalDate firstCounted = since.getDayOfMonth() <= 15 ? since.withDayOfMonth(1) : since.withDayOfMonth(1).plusMonths(1);
                LocalDate nextYear = start.plusYears(1);
                int months = 0;
                for (LocalDate m = firstCounted; m.isBefore(nextYear); m = m.plusMonths(1)) {
                    months++;
                }
                BigDecimal days = yearlyQuota.multiply(BigDecimal.valueOf(months)).divide(TWELVE, 2, RoundingMode.HALF_UP);
                if (days.signum() > 0) {
                    out.add(new Entry(since, days));
                }
            }
        }
        return out;
    }
}

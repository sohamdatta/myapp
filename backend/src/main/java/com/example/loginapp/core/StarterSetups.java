package com.example.loginapp.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * The starting setups a new Organization can begin from: shifts, leave types and holidays for a
 * kind of business. They are suggestions to change afterwards, not compliance advice: the minimum
 * leave a business owes depends on its state's Shops and Establishments rules. Only dates that
 * never move are added as holidays; each state's own list is for the Organization to add.
 */
public final class StarterSetups {

    /** What happens to leave left on the last day of the leave year. */
    public enum Carry {
        NONE(BigDecimal.ZERO, "lapse"),
        UP_TO_15(BigDecimal.valueOf(15), "lapse"),
        UP_TO_30(BigDecimal.valueOf(30), "lapse"),
        UP_TO_30_PAY_REST(BigDecimal.valueOf(30), "encash");

        private final BigDecimal cap;
        private final String excess;

        Carry(BigDecimal cap, String excess) {
            this.cap = cap;
            this.excess = excess;
        }

        /** The most days that move into the next year. */
        public BigDecimal cap() {
            return cap;
        }

        /** What happens to days over the cap: lapse or encash, as app.leave_type stores it. */
        public String excess() {
            return excess;
        }
    }

    public record ShiftPlan(String name, LocalTime start, LocalTime end, int graceMinutes) {}

    /** A kind of leave. The code is what a request names when it changes the days or the carry rule. */
    public record LeavePlan(String code, String name, BigDecimal days, Carry carry) {}

    public record Starter(String id, String title, String who, List<ShiftPlan> shifts, List<LeavePlan> leave) {}

    /** A holiday that falls on the same date every year. */
    public record FixedHoliday(int month, int day, String name) {}

    /** The three national holidays whose dates never move. */
    public static final List<FixedHoliday> NATIONAL_HOLIDAYS = List.of(
            new FixedHoliday(1, 26, "Republic Day"),
            new FixedHoliday(8, 15, "Independence Day"),
            new FixedHoliday(10, 2, "Gandhi Jayanti"));

    static final int FULL_DAY_MINUTES = 480;
    static final int HALF_DAY_MINUTES = 240;

    private static final List<Starter> ALL = List.of(
            new Starter("office", "Office", "Services, software, accounts and other back-office teams",
                    List.of(new ShiftPlan("General", LocalTime.of(9, 30), LocalTime.of(18, 30), 15)),
                    List.of(leave("casual", "Casual leave", 12, Carry.NONE),
                            leave("sick", "Sick leave", 6, Carry.NONE),
                            leave("earned", "Earned leave", 15, Carry.UP_TO_30))),
            new Starter("factory", "Factory with shifts", "Mills, plants and workshops with floor staff on shifts",
                    List.of(new ShiftPlan("General", LocalTime.of(9, 0), LocalTime.of(18, 0), 10),
                            new ShiftPlan("Early", LocalTime.of(6, 0), LocalTime.of(14, 0), 10),
                            new ShiftPlan("Late", LocalTime.of(14, 0), LocalTime.of(22, 0), 10)),
                    List.of(leave("casual", "Casual leave", 8, Carry.NONE),
                            leave("sick", "Sick leave", 7, Carry.NONE),
                            leave("earned", "Earned leave", 15, Carry.UP_TO_30))),
            new Starter("retail", "Retail and hospitality", "Shops, restaurants and hotels with a rotating weekly off",
                    List.of(new ShiftPlan("Opening", LocalTime.of(9, 0), LocalTime.of(17, 30), 10),
                            new ShiftPlan("Closing", LocalTime.of(13, 0), LocalTime.of(21, 30), 10)),
                    List.of(leave("casual", "Casual leave", 10, Carry.NONE),
                            leave("sick", "Sick leave", 7, Carry.NONE),
                            leave("earned", "Earned leave", 12, Carry.UP_TO_15))));

    private StarterSetups() {}

    private static LeavePlan leave(String code, String name, int days, Carry carry) {
        return new LeavePlan(code, name, BigDecimal.valueOf(days), carry);
    }

    public static List<Starter> all() {
        return ALL;
    }

    public static Optional<Starter> find(String id) {
        return ALL.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** The fixed holidays from the given day up to, not including, the same day a year later. */
    public static List<LocalDate> holidayDates(FixedHoliday holiday, LocalDate from) {
        LocalDate until = from.plusYears(1);
        return List.of(from.getYear(), from.getYear() + 1).stream()
                .map(year -> LocalDate.of(year, holiday.month(), holiday.day()))
                .filter(date -> !date.isBefore(from) && date.isBefore(until))
                .toList();
    }
}

package com.example.loginapp.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Works out one user's attendance result for one day. A pure function: it is
 * given everything it needs, reads nothing else and has no clock, so the same
 * inputs always give the same result and the same trail.
 */
public final class DayCalculator {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private DayCalculator() {}

    /** The shift and weekly offs in force for the user on the day. */
    public record Schedule(String shiftName, LocalTime start, LocalTime end, int graceMinutes,
                           int fullDayMinutes, int halfDayMinutes, Set<Integer> weeklyOffs, ZoneId zone) {}

    /** @param source web, import, manual, device, or request (added by an approved correction) */
    public record Punch(Instant at, String direction, String source) {}

    /**
     * An exception marked on a present-by-default day: absent or half_day. A later
     * "present" mark takes it back, so it never reaches the calculation.
     */
    public record Mark(String status, String reason, String markedBy) {}

    /**
     * @param schedule    null when the user has no assignment in force
     * @param holidayName null when the day is not a holiday
     * @param awayRequest on_duty or work_from_home when such a request is approved, otherwise null
     * @param punches     the day's punches, oldest first
     * @param presentByDefault true when the location counts working days as present unless marked otherwise
     * @param mark        the exception marked for the day, or null
     */
    public record Input(LocalDate date, Schedule schedule, String holidayName, String awayRequest, List<Punch> punches,
                        Leave leave, String awayHalf, boolean remote, boolean remoteNeedsPunches,
                        boolean presentByDefault, Mark mark) {

        /** A day at a location where people punch. */
        public Input(LocalDate date, Schedule schedule, String holidayName, String awayRequest, List<Punch> punches,
                     Leave leave, String awayHalf, boolean remote, boolean remoteNeedsPunches) {
            this(date, schedule, holidayName, awayRequest, punches, leave, awayHalf, remote, remoteNeedsPunches, false, null);
        }

        /** A day with no leave and no remote working, as before those existed. */
        public Input(LocalDate date, Schedule schedule, String holidayName, String awayRequest, List<Punch> punches) {
            this(date, schedule, holidayName, awayRequest, punches, null, null, "work_from_home".equals(awayRequest), false);
        }
    }

    /**
     * Approved leave that counts on the day.
     *
     * @param half null for the whole day, otherwise first or second
     */
    public record Leave(String typeName, String half, boolean unpaid) {}

    /**
     * @param leaveDays 0, 0.5 or 1: how much of the day is leave
     * @param remote    true when the day was worked, or counted, away from the office by arrangement
     */
    public record Result(String status, Instant firstIn, Instant lastOut, int workedMinutes, int lateMinutes,
                         int earlyMinutes, int overtimeMinutes, boolean incomplete, List<String> trail,
                         double leaveDays, boolean leaveUnpaid, boolean remote) {

        Result(String status, Instant firstIn, Instant lastOut, int workedMinutes, int lateMinutes,
               int earlyMinutes, int overtimeMinutes, boolean incomplete, List<String> trail) {
            this(status, firstIn, lastOut, workedMinutes, lateMinutes, earlyMinutes, overtimeMinutes, incomplete, trail, 0, false, false);
        }
    }

    public static Result calculate(Input in) {
        List<String> trail = new ArrayList<>();
        Schedule s = in.schedule();
        if (s == null) {
            trail.add("No work location and shift assigned for this day");
            return new Result("not_assigned", null, null, 0, 0, 0, 0, false, trail);
        }
        trail.add("Shift " + s.shiftName() + " " + HM.format(s.start()) + " to " + HM.format(s.end())
                + ", grace " + s.graceMinutes() + " min");

        // An approved correction replaces the day's own punches for the calculation; they stay on record.
        List<Punch> punches = in.punches();
        if (punches.stream().anyMatch(p -> p.source().equals("request"))) {
            punches = punches.stream().filter(p -> p.source().equals("request")).toList();
            trail.add("Times taken from an approved correction request");
        } else if (punches.stream().anyMatch(p -> p.source().equals("device"))) {
            punches = byOrder(punches);
            trail.add("Device punches taken as in and out by their order");
        }

        Instant firstIn = null;
        Instant lastOut = null;
        Instant open = null;
        long workedSeconds = 0;
        boolean incomplete = false;
        for (Punch p : punches) {
            if (p.direction().equals("in")) {
                if (open != null) {
                    incomplete = true;   // two ins in a row: the earlier one keeps the clock running
                } else {
                    open = p.at();
                }
                if (firstIn == null) {
                    firstIn = p.at();
                }
            } else {
                if (open == null) {
                    incomplete = true;   // an out with no in before it
                } else {
                    workedSeconds += Duration.between(open, p.at()).getSeconds();
                    open = null;
                    lastOut = p.at();
                }
            }
        }
        if (open != null) {
            incomplete = true;           // still punched in
        }
        int worked = (int) (workedSeconds / 60);

        // Leave for the whole day decides the day, whatever else happened on it.
        Leave leave = in.leave();
        if (leave != null && leave.half() == null) {
            trail.add(leave.typeName() + ", approved" + (leave.unpaid() ? " as unpaid" : ""));
            if (!punches.isEmpty()) {
                trail.add("Punched on a leave day; the punches are recorded but not counted");
            }
            trail.add("Result: on leave");
            return new Result("on_leave", firstIn, lastOut, worked, 0, 0, 0, false, trail, 1, leave.unpaid(), false);
        }
        if ("on_duty".equals(in.awayRequest())) {
            trail.add("Approved on-duty request");
            trail.add("Result: on duty, counted as a full day");
            return new Result("on_duty", firstIn, lastOut, worked, 0, 0, 0, false, trail);
        }
        boolean wholeDayRemote = in.remote() && in.awayHalf() == null;
        if (wholeDayRemote && !in.remoteNeedsPunches()) {
            trail.add("work_from_home".equals(in.awayRequest()) ? "Approved work-from-home request" : "Regular remote day");
            trail.add("Result: work from home, counted as a full day");
            return new Result("work_from_home", firstIn, lastOut, worked, 0, 0, 0, false, trail, 0, false, true);
        }
        boolean weeklyOff = s.weeklyOffs().contains(in.date().getDayOfWeek().getValue() % 7);
        if (in.holidayName() != null || weeklyOff) {
            trail.add(in.holidayName() != null ? "Holiday: " + in.holidayName() : "Weekly off");
            if (worked > 0) {
                trail.add("Worked " + hours(worked) + " on a day off, recorded as overtime");
            }
            String status = in.holidayName() != null ? "holiday" : "weekly_off";
            trail.add("Result: " + label(status));
            return new Result(status, firstIn, lastOut, worked, 0, 0, worked, incomplete, trail);
        }

        // Half the day may already be accounted for: by leave, or by a half day at home that needs no punches.
        String creditedHalf = null;
        double leaveDays = 0;
        boolean leaveUnpaid = false;
        if (leave != null) {
            creditedHalf = leave.half();
            leaveDays = 0.5;
            leaveUnpaid = leave.unpaid();
            trail.add(leave.typeName() + " for the " + leave.half() + " half, approved" + (leave.unpaid() ? " as unpaid" : ""));
        } else if (in.remote() && in.awayHalf() != null && !in.remoteNeedsPunches()) {
            creditedHalf = in.awayHalf();
            trail.add("Working from home for the " + in.awayHalf() + " half, approved");
        } else if (in.remote()) {
            trail.add(wholeDayRemote ? "Remote day, judged on its punches" : "Working from home for the " + in.awayHalf() + " half");
        }

        if (in.presentByDefault()) {
            return presentByDefault(in, trail, punches, firstIn, lastOut, worked, creditedHalf, leaveDays, leaveUnpaid);
        }

        if (punches.isEmpty()) {
            trail.add("No punches");
            String status = creditedHalf != null ? "half_day" : "absent";
            trail.add("Result: " + label(status) + (creditedHalf != null ? ", only the " + creditedHalf + " half counts" : ""));
            return new Result(status, null, null, 0, 0, 0, 0, false, trail, leaveDays, leaveUnpaid, in.remote());
        }

        Instant shiftStart = in.date().atTime(s.start()).atZone(s.zone()).toInstant();
        LocalDate endDate = PunchChecks.crossesMidnight(s.start(), s.end()) ? in.date().plusDays(1) : in.date();
        Instant shiftEnd = endDate.atTime(s.end()).atZone(s.zone()).toInstant();

        int late = 0;
        if (firstIn != null) {
            long afterGrace = Duration.between(shiftStart.plus(Duration.ofMinutes(s.graceMinutes())), firstIn).toMinutes();
            late = "first".equals(creditedHalf) ? 0 : (int) Math.max(0, afterGrace);
            trail.add("First in " + local(firstIn, s.zone()) + (late > 0 ? ", " + late + " min late" : ", on time"));
        }
        int early = 0;
        if (lastOut != null && !incomplete) {
            early = "second".equals(creditedHalf) ? 0 : (int) Math.max(0, Duration.between(lastOut, shiftEnd).toMinutes());
            trail.add("Last out " + local(lastOut, s.zone()) + (early > 0 ? ", " + early + " min before shift end" : ""));
        }
        if (incomplete) {
            trail.add("A punch is missing its pair, so some time could not be counted");
        }

        String status;
        if (creditedHalf != null) {
            if (worked >= s.halfDayMinutes()) {
                status = "present";
                trail.add("Worked " + hours(worked) + " in the other half, a half day is " + hours(s.halfDayMinutes()));
            } else {
                status = "half_day";
                trail.add("Worked " + hours(worked) + " in the other half, below a half day of " + hours(s.halfDayMinutes()));
            }
        } else if (worked >= s.fullDayMinutes()) {
            status = "present";
            trail.add("Worked " + hours(worked) + ", a full day is " + hours(s.fullDayMinutes()));
        } else if (worked >= s.halfDayMinutes()) {
            status = "half_day";
            trail.add("Worked " + hours(worked) + ", below a full day of " + hours(s.fullDayMinutes()));
        } else {
            status = "absent";
            trail.add("Worked " + hours(worked) + ", below a half day of " + hours(s.halfDayMinutes()));
        }
        int overtime = Math.max(0, worked - s.fullDayMinutes());
        if (overtime > 0) {
            trail.add("Overtime " + hours(overtime));
        }
        trail.add("Result: " + label(status));
        return new Result(status, firstIn, lastOut, worked, late, early, overtime, incomplete, trail, leaveDays, leaveUnpaid, in.remote());
    }

    /**
     * A working day where nobody punches: present, unless someone marked it otherwise.
     * Punches, if any, are kept on the day for reference but do not decide it.
     */
    private static Result presentByDefault(Input in, List<String> trail, List<Punch> punches, Instant firstIn, Instant lastOut,
                                           int worked, String creditedHalf, double leaveDays, boolean leaveUnpaid) {
        Mark mark = in.mark();
        String status;
        if (mark == null) {
            trail.add("Present by default; no exception marked");
            status = "present";
        } else {
            trail.add("Marked " + label(mark.status()) + " by " + mark.markedBy() + ": " + mark.reason());
            boolean absent = mark.status().equals("absent");
            if (creditedHalf != null) {
                // the other half is the one marked: absent leaves half a day, a half day fills it
                status = absent ? "half_day" : "present";
            } else {
                status = absent ? "absent" : "half_day";
            }
        }
        if (!punches.isEmpty()) {
            trail.add("Punches are recorded for reference; this location does not count them");
        }
        trail.add("Result: " + label(status) + (status.equals("half_day") && creditedHalf != null ? ", only the " + creditedHalf + " half counts" : ""));
        return new Result(status, firstIn, lastOut, worked, 0, 0, 0, false, trail, leaveDays, leaveUnpaid, in.remote());
    }

    /**
     * A device's In and Out keys are rarely pressed, so its punches alternate: the first of the day
     * is an in, the next an out, and so on. A punch entered with a direction (by HR, in the app) keeps it.
     */
    static List<Punch> byOrder(List<Punch> punches) {
        List<Punch> out = new ArrayList<>();
        String last = null;
        for (Punch p : punches) {
            String direction = p.direction();
            if (p.source().equals("device")) {
                direction = "in".equals(last) ? "out" : "in";
            }
            out.add(new Punch(p.at(), direction, p.source()));
            last = direction;
        }
        return out;
    }

    private static String local(Instant at, ZoneId zone) {
        return HM.format(at.atZone(zone));
    }

    private static String hours(int minutes) {
        return (minutes / 60) + " h " + String.format("%02d", minutes % 60) + " min";
    }

    private static String label(String status) {
        return status.replace('_', ' ');
    }
}

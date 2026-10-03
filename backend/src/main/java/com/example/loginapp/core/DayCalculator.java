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

    /** @param source web, import, manual, or request (added by an approved correction) */
    public record Punch(Instant at, String direction, String source) {}

    /**
     * @param schedule    null when the user has no assignment in force
     * @param holidayName null when the day is not a holiday
     * @param awayRequest on_duty or work_from_home when such a request is approved, otherwise null
     * @param punches     the day's punches, oldest first
     */
    public record Input(LocalDate date, Schedule schedule, String holidayName, String awayRequest, List<Punch> punches) {}

    public record Result(String status, Instant firstIn, Instant lastOut, int workedMinutes, int lateMinutes,
                         int earlyMinutes, int overtimeMinutes, boolean incomplete, List<String> trail) {}

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

        if (in.awayRequest() != null) {
            trail.add(in.awayRequest().equals("on_duty") ? "Approved on-duty request" : "Approved work-from-home request");
            trail.add("Result: " + label(in.awayRequest()) + ", counted as a full day");
            return new Result(in.awayRequest(), firstIn, lastOut, worked, 0, 0, 0, false, trail);
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
        if (punches.isEmpty()) {
            trail.add("No punches");
            trail.add("Result: absent");
            return new Result("absent", null, null, 0, 0, 0, 0, false, trail);
        }

        Instant shiftStart = in.date().atTime(s.start()).atZone(s.zone()).toInstant();
        LocalDate endDate = PunchChecks.crossesMidnight(s.start(), s.end()) ? in.date().plusDays(1) : in.date();
        Instant shiftEnd = endDate.atTime(s.end()).atZone(s.zone()).toInstant();

        int late = 0;
        if (firstIn != null) {
            long afterGrace = Duration.between(shiftStart.plus(Duration.ofMinutes(s.graceMinutes())), firstIn).toMinutes();
            late = (int) Math.max(0, afterGrace);
            trail.add("First in " + local(firstIn, s.zone()) + (late > 0 ? ", " + late + " min late" : ", on time"));
        }
        int early = 0;
        if (lastOut != null && !incomplete) {
            early = (int) Math.max(0, Duration.between(lastOut, shiftEnd).toMinutes());
            trail.add("Last out " + local(lastOut, s.zone()) + (early > 0 ? ", " + early + " min before shift end" : ""));
        }
        if (incomplete) {
            trail.add("A punch is missing its pair, so some time could not be counted");
        }

        String status;
        if (worked >= s.fullDayMinutes()) {
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
        return new Result(status, firstIn, lastOut, worked, late, early, overtime, incomplete, trail);
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

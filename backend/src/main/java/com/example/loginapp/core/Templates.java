package com.example.loginapp.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wording of letters and messages, with placeholders such as
 * {@code {{person.legal_name}}}. Only placeholders from a fixed list may be
 * used; a template naming any other is refused when it is saved, so a typo
 * never reaches a joiner. Rendering only substitutes text: a value is never
 * read as a placeholder or as markup, whatever it contains.
 */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}");

    /** What letters can say: the Organization, the person, the job and the employment. */
    public static final Map<String, String> LETTER = Map.ofEntries(
            Map.entry("organization.name", "The Organization's name"),
            Map.entry("person.legal_name", "The full name as on PAN"),
            Map.entry("person.first_name", "The first name, or the preferred name"),
            Map.entry("job.designation", "Designation"),
            Map.entry("job.department", "Department"),
            Map.entry("job.department_with_in", "\", in the X department\", or nothing"),
            Map.entry("job.manager_name", "The reporting manager's name"),
            Map.entry("job.work_location", "Work location"),
            Map.entry("employment.joining_date", "Joining date, as 12 October 2026"),
            Map.entry("employment.type", "Type of employment, in words"),
            Map.entry("employment.probation_text", "Probation in words, or \"none\""),
            Map.entry("employment.contract_end", "When a fixed-term contract ends"),
            Map.entry("employment.code", "Employee code, once known"),
            Map.entry("statutory.uan_or_pending", "UAN, or \"to be generated\""),
            Map.entry("offer.expires_on", "The date an offer must be answered by"),
            Map.entry("today", "Today's date"));

    /** What exit letters can say: everything other letters can, and the exit's own details. */
    public static final Map<String, String> EXIT_LETTER;

    /** What performance letters can say: everything other letters can, and the probation's and review's own details. */
    public static final Map<String, String> PERFORMANCE_LETTER;

    /** What messages can say: everything letters can, and the event's own details. */
    public static final Map<String, String> MESSAGE;

    static {
        java.util.Map<String, String> p = new java.util.TreeMap<>(LETTER);
        p.put("probation.end_date", "The end of the probation the letter is about");
        p.put("probation.confirmed_from", "The day a confirmation takes effect");
        p.put("probation.new_end_date", "The new end of an extended probation");
        p.put("probation.extension_months", "How many months the probation is extended by, as 3 months");
        p.put("employment.tenure", "Length of service, as 3 years 2 months");
        p.put("review.cycle", "The review cycle, as Annual review 2026-27");
        p.put("review.rating", "The final rating, in words");
        p.put("outcome.increment_pct", "The increment, as 8%");
        p.put("outcome.new_annual", "The new yearly pay, as ₹12,96,000");
        p.put("outcome.new_designation", "The designation of a promotion");
        p.put("outcome.bonus", "The bonus, as ₹50,000, or nothing");
        p.put("outcome.effective_from", "The day the outcome takes effect");
        p.put("plan.start_date", "The day an improvement plan starts");
        p.put("plan.end_date", "The day an improvement plan ends");
        p.put("plan.objectives", "The plan's objectives and how each is measured, one to a line");
        p.put("plan.checkin_every", "How often the manager checks in, as every 2 weeks");
        PERFORMANCE_LETTER = java.util.Collections.unmodifiableMap(p);

        java.util.Map<String, String> x = new java.util.TreeMap<>(LETTER);
        x.put("exit.kind", "The kind of exit, in words: resignation, retirement and so on");
        x.put("exit.submitted_on", "When the resignation was given, or the exit started");
        x.put("exit.last_working_day", "The last working day");
        x.put("exit.notice_days", "The notice owed, in days");
        x.put("exit.notice_end", "When the notice would end");
        x.put("exit.response_by", "The date a reply is due by (show-cause and final notice)");
        x.put("exit.absent_since", "The first day of absence without leave");
        x.put("employment.tenure", "Length of service, as 3 years 2 months");
        x.put("employment.designations", "Every designation held, with dates");
        EXIT_LETTER = java.util.Collections.unmodifiableMap(x);

        java.util.Map<String, String> m = new java.util.TreeMap<>(EXIT_LETTER);
        m.put("link", "The link the message is about");
        m.put("note", "HR's note");
        m.put("section.title", "The section's title");
        m.put("task.title", "The task");
        m.put("task.due_date", "The task's due date");
        m.put("joiner.name", "The joiner's name");
        m.put("employee.name", "The leaving employee's name");
        m.put("exit.access_until", "When a former employee's access ends");
        m.put("letter.title", "The letter's title");
        m.put("settlement.line", "A settlement line, such as Gratuity");
        m.put("settlement.due_date", "When a settlement line is due");
        m.putAll(PERFORMANCE_LETTER);
        m.put("probation.checkin_day", "The check-in, as day 30");
        m.put("probation.checkin_due", "When the check-in is due");
        m.put("probation.recommendation", "The manager's recommendation, in words");
        m.put("probation.decision", "HR's decision, in words");
        m.put("goals.period", "The goal period, as FY 2026-27");
        m.put("goal.title", "The goal's title");
        m.put("giver.name", "Who gave the feedback");
        m.put("review.cycle", "The review cycle, as Annual review FY 2026-27");
        m.put("review.due", "When the review is due");
        m.put("plan.decision", "HR's decision on an improvement plan, in words");
        m.put("plan.checkin_due", "When a plan check-in is due");
        m.put("requester.name", "Who asked for the feedback");
        MESSAGE = java.util.Collections.unmodifiableMap(m);
    }

    private Templates() {}

    /** Placeholders a text uses, in order of first use. */
    public static List<String> placeholders(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** Refuses a template that uses a placeholder not in {@code allowed}, naming each unknown one. */
    public static void check(String text, Map<String, String> allowed) {
        List<String> unknown = placeholders(text).stream().filter(p -> !allowed.containsKey(p)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(400, "template.unknown_placeholder", "Unknown placeholder" + (unknown.size() > 1 ? "s" : "") + ": "
                    + String.join(", ", unknown.stream().map(p -> "{{" + p + "}}").toList())
                    + ". The ones available are listed beside the editor");
        }
        if (text != null && countOpen(text) > countMatches(text)) {
            throw new ApiException(400, "template.unknown_placeholder",
                    "Something in {{ }} is not a placeholder, or is not closed: write them like {{person.legal_name}}");
        }
    }

    /** Fills in the placeholders. One with no value becomes empty; the values are inserted as plain text, once. */
    public static String render(String text, Map<String, String> values) {
        if (text == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int countOpen(String text) {
        int n = 0;
        for (int i = text.indexOf("{{"); i >= 0; i = text.indexOf("{{", i + 2)) {
            n++;
        }
        return n;
    }

    private static int countMatches(String text) {
        int n = 0;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }
}

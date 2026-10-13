package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The exit interview. HR sends the Organization's questions, as they stand that day, to the
 * leaving employee, who answers them in My exit or declines; or HR holds it in person and writes
 * notes. Answers and notes are encrypted, and only HR (exit.manage) reads them: not the manager,
 * whose team the answers are often about.
 */
public final class ExitInterview {

    public record AnswersInput(Map<String, Object> answers) {}

    public record NotesInput(String notes) {}

    private static final Set<String> TYPES = Set.of("choice", "rating", "text");

    private final Db db;
    private final Crypto crypto;

    public ExitInterview(Db db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    // ── HR ──────────────────────────────────────────────────────────────

    /** Sends the questions to the employee. */
    public Map<String, Object> send(SessionCtx ctx, UUID caseId) {
        requireHr(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, ctx, caseId);
            if (!"accepted".equals(x.get("status"))) {
                throw new ApiException(409, "exit.not_accepted", "The interview can be sent once the exit is accepted");
            }
            if (x.get("membershipId") == null) {
                throw new ApiException(409, "interview.no_login", "The employee has no login to answer it; hold it in person instead");
            }
            Map<String, Object> s = Sql.one(c, "select interview_version, interview_questions::text as q from app.exit_settings");
            if (((List<?>) Json.parse((String) s.get("q"))).isEmpty()) {
                throw new ApiException(409, "interview.no_questions", "There are no interview questions; add them in Setup → Exits");
            }
            if (Sql.update(c, "insert into app.exit_interview (tenant_id, exit_case_id, version, questions, updated_by) "
                    + "values (?, ?, ?, ?::jsonb, ?) on conflict (tenant_id, exit_case_id) do nothing", ctx.tenantId(), caseId,
                    s.get("interviewVersion"), s.get("q"), ctx.membershipId()) == 0) {
                throw new ApiException(409, "interview.exists", "The interview has already been sent or held");
            }
            UserService.audit(c, ctx, "exit.interview_sent", "exit", caseId, Map.of());
            Notifications.send(c, ctx.tenantId(), AuthService.uuid(x.get("membershipId")), "interview_sent", ExitValues.forCase(c, caseId),
                    "my-exit");
            return read(c, ctx, caseId);
        });
    }

    /** HR's notes from an interview held in person; they replace earlier notes, never the employee's own answers. */
    public Map<String, Object> notes(SessionCtx ctx, UUID caseId, NotesInput in) {
        requireHr(ctx);
        String notes = in == null || in.notes() == null ? "" : in.notes().strip();
        if (notes.length() < 5 || notes.length() > 10000) {
            throw ApiException.badRequest("Write the notes, from 5 to 10,000 characters");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = lockCase(c, ctx, caseId);
            if (!Set.of("accepted", "closed").contains((String) x.get("status"))) {
                throw new ApiException(409, "exit.not_accepted", "The interview can be recorded once the exit is accepted");
            }
            byte[] sealed = crypto.encrypt(ctx.tenantId(), notes);
            Map<String, Object> s = Sql.one(c, "select interview_version, interview_questions::text as q from app.exit_settings");
            Sql.update(c, "insert into app.exit_interview (tenant_id, exit_case_id, version, questions, status, notes_enc, updated_by) "
                    + "values (?, ?, ?, ?::jsonb, 'in_person', ?, ?) on conflict (tenant_id, exit_case_id) do update set notes_enc = excluded.notes_enc, "
                    + "status = case when app.exit_interview.status = 'submitted' then 'submitted' else 'in_person' end, "
                    + "updated_by = excluded.updated_by, updated_at = now()", ctx.tenantId(), caseId, s.get("interviewVersion"), s.get("q"),
                    sealed, ctx.membershipId());
            UserService.audit(c, ctx, "exit.interview_notes", "exit", caseId, Map.of());
            return read(c, ctx, caseId);
        });
    }

    /** The interview with its answers and notes, for HR. */
    public Map<String, Object> get(SessionCtx ctx, UUID caseId) {
        requireHr(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> x = load(c, caseId);
            if (ctx.membershipId().toString().equals(x.get("membershipId"))) {
                throw ApiException.notFound("The interview");
            }
            return read(c, ctx, caseId);
        });
    }

    private Map<String, Object> read(Connection c, SessionCtx ctx, UUID caseId) throws SQLException {
        Map<String, Object> out = summary(c, caseId);
        if (out == null) {
            return Map.of("status", "none");
        }
        try (PreparedStatement ps = c.prepareStatement("select answers_enc, notes_enc from app.exit_interview where exit_case_id = ?")) {
            ps.setObject(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                byte[] answers = rs.getBytes(1);
                byte[] notes = rs.getBytes(2);
                out.put("answers", answers == null ? null : Json.parse(crypto.decryptText(ctx.tenantId(), answers)));
                out.put("notes", notes == null ? null : crypto.decryptText(ctx.tenantId(), notes));
            }
        }
        return out;
    }

    // ── the employee ────────────────────────────────────────────────────

    /** The employee answers. Every question may be left out; what is given must fit the question. */
    public Map<String, Object> answer(SessionCtx ctx, AnswersInput in) {
        Map<String, Object> given = in == null || in.answers() == null ? Map.of() : in.answers();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> i = ownOpen(c, ctx);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) Json.parse((String) i.get("questions"));
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map<String, Object> q : questions) {
                Object a = given.get((String) q.get("id"));
                if (a == null || (a instanceof String s && s.isBlank())) {
                    continue;
                }
                clean.put((String) q.get("id"), checkAnswer(q, a));
            }
            if (clean.isEmpty()) {
                throw ApiException.badRequest("Answer at least one question, or decline the interview");
            }
            Sql.update(c, "update app.exit_interview set status = 'submitted', answers_enc = ?, submitted_at = now(), updated_by = ?, "
                    + "updated_at = now() where id = ?::uuid", crypto.encrypt(ctx.tenantId(), Json.write(clean)), ctx.membershipId(), i.get("id"));
            UserService.audit(c, ctx, "exit.interview_answered", "exit", AuthService.uuid(i.get("exitCaseId")), Map.of());
            return Map.of("status", "submitted");
        });
    }

    /** The employee would rather not answer. */
    public Map<String, Object> decline(SessionCtx ctx) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> i = ownOpen(c, ctx);
            Sql.update(c, "update app.exit_interview set status = 'declined', updated_by = ?, updated_at = now() where id = ?::uuid",
                    ctx.membershipId(), i.get("id"));
            UserService.audit(c, ctx, "exit.interview_declined", "exit", AuthService.uuid(i.get("exitCaseId")), Map.of());
            return Map.of("status", "declined");
        });
    }

    /** What the exit page shows of the interview: its state, and the questions to the employee while it waits for them. */
    static Map<String, Object> summary(Connection c, UUID caseId) throws SQLException {
        Map<String, Object> i = Sql.one(c, "select status, version, questions::text as questions, submitted_at, updated_at "
                + "from app.exit_interview where exit_case_id = ?", caseId);
        if (i == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", i.get("status"));
        out.put("version", i.get("version"));
        out.put("questions", Json.parse((String) i.get("questions")));
        out.put("submittedAt", i.get("submittedAt"));
        out.put("updatedAt", i.get("updatedAt"));
        return out;
    }

    // ── questions, for setup ────────────────────────────────────────────

    /** Checks questions as HR writes them in setup, and returns them as JSON. */
    static String checkQuestions(List<Map<String, Object>> questions) {
        if (questions == null || questions.size() > 30) {
            throw ApiException.badRequest("Give up to 30 interview questions");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int n = 0;
        for (Map<String, Object> q : questions) {
            n++;
            String text = q.get("text") == null ? "" : String.valueOf(q.get("text")).strip();
            if (text.isEmpty() || text.length() > 200) {
                throw ApiException.badRequest("Question " + n + ": write the question, in at most 200 characters");
            }
            String type = q.get("type") == null ? "text" : String.valueOf(q.get("type"));
            if (!TYPES.contains(type)) {
                throw ApiException.badRequest("Question " + n + ": the answer is a choice, a rating or text");
            }
            String id = q.get("id") == null || String.valueOf(q.get("id")).isBlank() ? "q" + n : String.valueOf(q.get("id")).strip();
            if (!id.matches("[a-z0-9_]{1,30}") || !ids.add(id)) {
                id = "q" + n + "_" + UUID.randomUUID().toString().substring(0, 4);
                ids.add(id);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type);
            m.put("text", text);
            if ("choice".equals(type)) {
                List<String> choices = new ArrayList<>();
                if (q.get("choices") instanceof List<?> list) {
                    for (Object o : list) {
                        String ch = o == null ? "" : String.valueOf(o).strip();
                        if (!ch.isEmpty() && ch.length() <= 60 && !choices.contains(ch)) {
                            choices.add(ch);
                        }
                    }
                }
                if (choices.size() < 2 || choices.size() > 12) {
                    throw ApiException.badRequest("Question " + n + ": give from 2 to 12 choices");
                }
                m.put("choices", choices);
            }
            out.add(m);
        }
        return Json.write(out);
    }

    // ── internals ───────────────────────────────────────────────────────

    private static Object checkAnswer(Map<String, Object> q, Object a) {
        String type = (String) q.get("type");
        switch (type) {
            case "rating" -> {
                int r;
                try {
                    r = a instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(a).trim());
                } catch (NumberFormatException e) {
                    r = 0;
                }
                if (r < 1 || r > 5) {
                    throw ApiException.badRequest("Rate \"" + q.get("text") + "\" from 1 to 5");
                }
                return r;
            }
            case "choice" -> {
                String s = String.valueOf(a);
                if (!(q.get("choices") instanceof List<?> choices) || !choices.contains(s)) {
                    throw ApiException.badRequest("Choose one of the answers to \"" + q.get("text") + "\"");
                }
                return s;
            }
            default -> {
                String s = String.valueOf(a).strip();
                if (s.length() > 2000) {
                    throw ApiException.badRequest("Keep each answer to 2,000 characters");
                }
                return s;
            }
        }
    }

    private static Map<String, Object> ownOpen(Connection c, SessionCtx ctx) throws SQLException {
        if (ctx.membershipId() == null) {
            throw ApiException.notFound("The interview");
        }
        Map<String, Object> i = Sql.one(c, "select i.id, i.exit_case_id, i.status, i.questions::text as questions from app.exit_interview i "
                + "join app.exit_case x on x.id = i.exit_case_id join app.employment e on e.id = x.employment_id "
                + "where e.membership_id = ? and x.status in ('accepted','closed') order by x.started_at desc limit 1 for update of i",
                ctx.membershipId());
        if (i == null) {
            throw ApiException.notFound("The interview");
        }
        if (!"sent".equals(i.get("status"))) {
            throw new ApiException(409, "interview.done", "The interview is already answered or closed");
        }
        return i;
    }

    private static Map<String, Object> lockCase(Connection c, SessionCtx ctx, UUID caseId) throws SQLException {
        Sql.one(c, "select id from app.exit_case where id = ? for update", caseId);
        Map<String, Object> x = load(c, caseId);
        if (ctx.membershipId().toString().equals(x.get("membershipId"))) {
            throw new ApiException(409, "request.refused", "Nobody handles their own exit interview");
        }
        return x;
    }

    private static Map<String, Object> load(Connection c, UUID caseId) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.id, x.status, e.membership_id from app.exit_case x join app.employment e on e.id = x.employment_id "
                + "where x.id = ?", caseId);
        if (x == null) {
            throw ApiException.notFound("The exit");
        }
        return x;
    }

    private static void requireHr(SessionCtx ctx) {
        if (!ExitService.canManage(ctx)) {
            throw ApiException.denied("exit.manage");
        }
    }
}

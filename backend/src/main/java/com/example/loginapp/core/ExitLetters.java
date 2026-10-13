package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The letters of an exit: acceptance of a resignation (issued on acceptance), termination,
 * retirement and end of contract, the show-cause and final notices for absence without leave, and
 * the relieving and experience letters. Each is filled from the exit, made a PDF in the employee's
 * documents, and the employee is told. The relieving letter waits until the last working day has
 * come and the required clearance is done; the experience letter waits only for the last day.
 */
public final class ExitLetters {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The letter kinds that belong to exits. */
    public static final Set<String> KINDS = Set.of("exit_acceptance", "relieving", "experience", "termination", "retirement", "contract_end",
            "show_cause", "final_notice");

    /** Which exit each kind is for; a kind not listed suits every exit. */
    private static final Map<String, String> FOR_KIND = Map.of("exit_acceptance", "resignation", "termination", "termination",
            "retirement", "retirement", "contract_end", "end_of_contract", "show_cause", "absconding", "final_notice", "absconding");

    private final Db db;
    private final DocumentService documents;
    private final Clock clock;

    public ExitLetters(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public ExitLetters(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.documents = new DocumentService(db, crypto);
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    /** The letter filled for this exit, as text, to read before issuing it. */
    public Map<String, Object> preview(SessionCtx ctx, UUID caseId, UUID templateId) {
        requireHr(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> t = template(c, templateId);
            return Map.of("text", Templates.render((String) t.get("body"), values(c, caseId, today())));
        });
    }

    /** HR issues one of the exit letters. */
    public Map<String, Object> issue(SessionCtx ctx, UUID caseId, UUID templateId) {
        requireHr(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Sql.one(c, "select id from app.exit_case where id = ? for update", caseId);
            Map<String, Object> x = load(c, caseId);
            if (ctx.membershipId().toString().equals(x.get("membershipId"))) {
                throw new ApiException(409, "request.refused", "Nobody issues letters about their own exit");
            }
            Map<String, Object> t = template(c, templateId);
            String waiting = waitingFor(c, x, (String) t.get("kind"), today);
            if (waiting != null) {
                throw new ApiException(409, "exit.letter_not_ready", waiting);
            }
            UUID letter = issue(c, ctx, documents, x, t, today);
            return Map.of("id", letter.toString(), "letters", issued(c, caseId));
        });
    }

    /**
     * Issues the Organization's acceptance letter, if it has an active one, when a resignation is
     * accepted. Nothing happens for other exits, or when the letter has been turned off.
     */
    void onAccepted(Connection c, SessionCtx ctx, UUID caseId, LocalDate today) throws SQLException {
        Map<String, Object> x = load(c, caseId);
        if (!"resignation".equals(x.get("kind"))) {
            return;
        }
        Map<String, Object> t = Sql.one(c, "select * from app.letter_template where kind = 'exit_acceptance' and active "
                + "order by created_at limit 1");
        if (t != null) {
            issue(c, ctx, documents, x, t, today);
        }
    }

    private static UUID issue(Connection c, SessionCtx ctx, DocumentService documents, Map<String, Object> x, Map<String, Object> t,
                              LocalDate today) throws SQLException {
        UUID caseId = AuthService.uuid(x.get("id"));
        Map<String, String> v = values(c, caseId, today);
        String title = (String) t.get("name");
        byte[] pdf = Pdf.letter(title, Templates.render((String) t.get("body"), v));
        FileSniffer.Cleaned file = FileSniffer.clean(pdf, title + ".pdf");
        UUID document = documents.insert(c, ctx, ctx.tenantId(), AuthService.uuid(x.get("personId")), null, null, file);
        UUID letter = AuthService.uuid(Sql.one(c, "insert into app.issued_letter (tenant_id, person_id, template_id, template_version, "
                + "document_id, issued_by, kind, title, response_needed, exit_case_id) values (?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, 'none', ?) "
                + "returning id", ctx.tenantId(), x.get("personId"), t.get("id"), t.get("version"), document, ctx.membershipId(), t.get("kind"),
                title, caseId).get("id"));
        UserService.audit(c, ctx, "exit.letter_issued", "exit", caseId, Map.of("letter", title, "kind", t.get("kind")));
        UUID employee = AuthService.uuid(x.get("membershipId"));
        if (employee != null && !"death".equals(x.get("kind"))) {
            v.put("letter.title", title.toLowerCase(java.util.Locale.ENGLISH));
            Notifications.send(c, ctx.tenantId(), employee, "exit_letter_issued", v, "my-exit");
        }
        return letter;
    }

    // ── reading, for the exit's page ────────────────────────────────────

    /** The letters issued for this exit, newest first; the settlement statement is shown with the settlement. */
    static List<Map<String, Object>> issued(Connection c, UUID caseId) throws SQLException {
        return Sql.query(c, "select il.id, il.kind, il.title, il.document_id, il.issued_at, d.email as issued_by from app.issued_letter il "
                + "left join app.member_directory() d on d.membership_id = il.issued_by where il.exit_case_id = ? and il.kind <> 'settlement' "
                + "order by il.issued_at desc", caseId);
    }

    /** The exit letters HR may issue for this exit, each with what it still waits for. */
    static List<Map<String, Object>> available(Connection c, Map<String, Object> x, LocalDate today) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, "select id, kind, name, version from app.letter_template where active and kind = any(?) "
                + "order by kind, lower(name)", (Object) KINDS.toArray(String[]::new))) {
            String kind = (String) t.get("kind");
            String suits = FOR_KIND.get(kind);
            if ((suits != null && !suits.equals(x.get("kind"))) || ("death".equals(x.get("kind")) && suits == null)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("waitingFor", waitingFor(c, x, kind, today));
            out.add(m);
        }
        return out;
    }

    /** Why the letter cannot go yet, in words; null when it can. */
    static String waitingFor(Connection c, Map<String, Object> x, String kind, LocalDate today) throws SQLException {
        String status = (String) x.get("status");
        String suits = FOR_KIND.get(kind);
        if (!KINDS.contains(kind)) {
            return "This is not an exit letter";
        }
        if (suits != null && !suits.equals(x.get("kind"))) {
            return "This letter is for another kind of exit";
        }
        if (Set.of("withdrawn", "cancelled").contains(status)) {
            return "The exit is " + status;
        }
        boolean absconding = Set.of("show_cause", "final_notice").contains(kind);
        if (!absconding && !Set.of("accepted", "closed").contains(status)) {
            return "The exit is not accepted yet";
        }
        if ("final_notice".equals(kind)) {
            Map<String, Object> d = Sql.one(c, "select (select min(issued_at at time zone 'Asia/Kolkata')::date from app.issued_letter "
                    + "where exit_case_id = ?::uuid and kind = 'show_cause') as sent, (select response_days from app.exit_settings) as days",
                    x.get("id"));
            if (d.get("sent") == null) {
                return "Send the show-cause notice first";
            }
            LocalDate until = LocalDate.parse((String) d.get("sent")).plusDays(((Number) d.get("days")).intValue());
            if (!today.isAfter(until)) {
                return "The show-cause notice can be answered until " + TemplateValues.DATE.format(until);
            }
        }
        LocalDate last = x.get("lastWorkingDay") == null ? null : LocalDate.parse((String) x.get("lastWorkingDay"));
        if (Set.of("relieving", "experience").contains(kind) && (last == null || today.isBefore(last))) {
            return "Waits for the last working day" + (last == null ? "" : ", " + TemplateValues.DATE.format(last));
        }
        if ("relieving".equals(kind)) {
            Map<String, Object> open = Sql.one(c, "select count(*) as n from app.exit_task where exit_case_id = ?::uuid and required "
                    + "and done_at is null", x.get("id"));
            long n = ((Number) open.get("n")).longValue();
            if (n > 0) {
                return "Waits for clearance: " + n + (n == 1 ? " required task" : " required tasks") + " still open";
            }
        }
        return null;
    }

    // ── internals ───────────────────────────────────────────────────────

    private static Map<String, String> values(Connection c, UUID caseId, LocalDate today) throws SQLException {
        Map<String, String> v = ExitValues.forCase(c, caseId);
        Map<String, Object> s = Sql.one(c, "select response_days from app.exit_settings");
        int days = s == null ? 7 : ((Number) s.get("responseDays")).intValue();
        v.put("exit.response_by", TemplateValues.DATE.format(today.plusDays(days)));
        return v;
    }

    private static Map<String, Object> template(Connection c, UUID templateId) throws SQLException {
        Map<String, Object> t = Sql.one(c, "select * from app.letter_template where id = ?", templateId);
        if (t == null || !KINDS.contains((String) t.get("kind"))) {
            throw ApiException.notFound("The letter");
        }
        return t;
    }

    private static Map<String, Object> load(Connection c, UUID caseId) throws SQLException {
        Map<String, Object> x = Sql.one(c, "select x.*, e.membership_id from app.exit_case x join app.employment e on e.id = x.employment_id "
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

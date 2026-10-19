package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feedback between colleagues. Praise goes to the person, and to their manager when the giver
 * chooses; private feedback goes only to the person's manager (and HR), never to the person. It
 * can be changed for a day and is never deleted: the giver can withdraw it, which keeps its date
 * but not its text, so the record cannot be quietly rewritten before a review.
 */
public final class Feedback {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record GiveInput(String employmentId, String kind, String text, Boolean shareWithManager, String goalId, String competencyId,
                            String requestId) {

        /** Feedback nobody asked for. */
        public GiveInput(String employmentId, String kind, String text, Boolean shareWithManager, String goalId, String competencyId) {
            this(employmentId, kind, text, shareWithManager, goalId, competencyId, null);
        }
    }

    public record RequestInput(String aboutId, List<String> giverIds, String note) {}

    public record TextInput(String text) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public Feedback(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public Feedback(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    public Map<String, Object> give(SessionCtx ctx, GiveInput in) {
        Perf.requireMember(ctx);
        if (ctx.scopeOf("performance.read") == null) {
            throw ApiException.denied("performance.read");
        }
        UUID receiver = Perf.id(in == null ? null : in.employmentId(), "person");
        String kind = Perf.oneOf(in.kind(), List.of("praise", "private"), "Choose praise, which they read, or private feedback for their manager");
        String text = Perf.text(in.text(), "the feedback", 3, 1000);
        UUID goal = in.goalId() == null || in.goalId().isBlank() ? null : Perf.id(in.goalId(), "goal");
        UUID competency = in.competencyId() == null || in.competencyId().isBlank() ? null : Perf.id(in.competencyId(), "competency");
        UUID request = in.requestId() == null || in.requestId().isBlank() ? null : Perf.id(in.requestId(), "request");
        return db.inTenant(ctx.tenantId(), c -> {
            if (request != null) {
                Map<String, Object> q = Sql.one(c, "select q.* from app.feedback_request q join app.employment e on e.id = q.giver_id "
                        + "where q.id = ? and e.membership_id = ? for update of q", request, ctx.membershipId());
                if (q == null || !receiver.toString().equals(q.get("aboutId"))) {
                    throw ApiException.notFound("The request");
                }
                if (!"open".equals(q.get("status"))) {
                    throw new ApiException(409, "request.refused", "That request has already been answered");
                }
            }
            PerformanceDefaults.ensure(c, ctx.tenantId());
            if (Sql.one(c, "select 1 from app.employment where id = ? and status = 'active'", receiver) == null) {
                throw ApiException.notFound("The person");
            }
            if (Perf.own(c, ctx, receiver)) {
                throw new ApiException(409, "request.refused", "Nobody gives feedback to themselves");
            }
            if (goal != null && Sql.one(c, "select 1 from app.goal where id = ? and employment_id = ?", goal, receiver) == null) {
                throw ApiException.badRequest("That goal is not theirs");
            }
            if (competency != null && Sql.one(c, "select 1 from app.competency where id = ? and active", competency) == null) {
                throw ApiException.badRequest("Choose an active competency");
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.feedback (tenant_id, giver_id, receiver_id, kind, share_with_manager, text_enc, "
                    + "goal_id, competency_id) values (?, ?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), ctx.membershipId(), receiver, kind,
                    "praise".equals(kind) && Boolean.TRUE.equals(in.shareWithManager()), Perf.seal(crypto, ctx, text), goal, competency).get("id"));
            UserService.audit(c, ctx, "performance.feedback_given", "feedback", id, Map.of("kind", kind));
            if (request != null) {
                Sql.update(c, "update app.feedback_request set status = 'given', feedback_id = ?, answered_at = now() where id = ?", id, request);
            }
            if (kind.equals("praise")) {
                Map<String, String> v = ExitValues.forEmployment(c, receiver, null);
                v.put("giver.name", (String) Sql.one(c, "select " + Perf.who("x.m") + " as who from (select ?::uuid as m) x", ctx.membershipId())
                        .get("who"));
                Notifications.send(c, ctx.tenantId(), membership(c, receiver), "feedback_received", v, "my-performance");
            }
            return item(c, ctx, id);
        });
    }

    /** The giver changes their feedback, within a day of giving it. */
    public Map<String, Object> edit(SessionCtx ctx, UUID id, TextInput in) {
        Perf.requireMember(ctx);
        String text = Perf.text(in == null ? null : in.text(), "the feedback", 3, 1000);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = ownFeedback(c, ctx, id);
            if (f.get("withdrawnAt") != null) {
                throw new ApiException(409, "request.refused", "This feedback was withdrawn");
            }
            if (Sql.one(c, "select 1 from app.feedback where id = ? and created_at < now() - interval '24 hours'", id) != null) {
                throw new ApiException(409, "feedback.too_late", "Feedback can be changed only within a day of giving it; withdraw it instead");
            }
            Sql.update(c, "update app.feedback set text_enc = ?, edited_at = now() where id = ?", Perf.seal(crypto, ctx, text), id);
            return item(c, ctx, id);
        });
    }

    /** The giver withdraws their feedback. Its date stays; its words do not. */
    public Map<String, Object> withdraw(SessionCtx ctx, UUID id) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = ownFeedback(c, ctx, id);
            if (f.get("withdrawnAt") == null) {
                Sql.update(c, "update app.feedback set text_enc = null, withdrawn_at = now() where id = ?", id);
                UserService.audit(c, ctx, "performance.feedback_withdrawn", "feedback", id, Map.of());
            }
            return item(c, ctx, id);
        });
    }

    /** Feedback about one person that the caller may read: as the person, praise; as their manager, also what was shared or private; as HR, all. */
    public List<Map<String, Object>> about(SessionCtx ctx, UUID employment) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> about(c, ctx, employment, today));
    }

    List<Map<String, Object>> about(Connection c, SessionCtx ctx, UUID employment, LocalDate today) throws SQLException {
        String where;
        if (Perf.own(c, ctx, employment)) {
            where = "f.kind = 'praise'";
        } else if (ctx.reachesEveryone("performance.read")) {
            where = "true";
        } else if (Perf.reaches(c, ctx, "performance.read", employment, today)) {
            where = "(f.kind = 'private' or f.share_with_manager or f.giver_id = ?::uuid)";
        } else {
            where = "f.giver_id = ?::uuid";   // anyone else sees only what they gave
        }
        List<Object> params = new ArrayList<>(List.of(employment));
        if (where.contains("?")) {
            params.add(ctx.membershipId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : Sql.query(c, SELECT + "where f.receiver_id = ? and " + where.replace("?::uuid", "?::uuid")
                + " order by f.created_at desc limit 100", params.toArray())) {
            out.add(decorate(c, ctx, f));
        }
        return out;
    }

    /** What the caller has given, newest first. */
    public List<Map<String, Object>> given(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> f : Sql.query(c, SELECT + "where f.giver_id = ? order by f.created_at desc limit 100", ctx.membershipId())) {
                out.add(decorate(c, ctx, f));
            }
            return out;
        });
    }

    /** What the caller has received, as praise, newest first. */
    public List<Map<String, Object>> received(SessionCtx ctx) {
        Perf.requireMember(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = Perf.ownEmployment(c, ctx);
            return e == null ? List.<Map<String, Object>>of() : about(c, ctx, AuthService.uuid(e.get("id")), today);
        });
    }

    /** Colleagues the caller can give feedback to: everyone working here, but themselves. */
    public List<Map<String, Object>> colleagues(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, "select e.id, " + Perf.NAME + " as name, j.designation, d.name as department "
                + "from app.employment e join app.person p on p.id = e.person_id " + Perf.CURRENT_JOB
                + "where e.status = 'active' and e.membership_id is distinct from ? order by " + Perf.NAME, ctx.membershipId()));
    }

    // ── asking for feedback ─────────────────────────────────────────────

    /**
     * Asks up to five colleagues for feedback: about the caller, or (as their manager, or HR) about
     * someone in their team. Each colleague is told.
     */
    public List<Map<String, Object>> request(SessionCtx ctx, RequestInput in) {
        Perf.requireMember(ctx);
        if (ctx.scopeOf("performance.read") == null) {
            throw ApiException.denied("performance.read");
        }
        String note = Perf.text(in == null ? null : in.note(), "a note", 0, 300);
        List<UUID> givers = new ArrayList<>();
        for (String g : in.giverIds() == null ? List.<String>of() : in.giverIds()) {
            UUID id = Perf.id(g, "colleague");
            if (!givers.contains(id)) {
                givers.add(id);
            }
        }
        if (givers.isEmpty() || givers.size() > 5) {
            throw ApiException.badRequest("Ask one to five colleagues");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PerformanceDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> me = Perf.ownEmployment(c, ctx);
            UUID about = in.aboutId() == null || in.aboutId().isBlank() ? (me == null ? null : AuthService.uuid(me.get("id")))
                    : Perf.id(in.aboutId(), "person");
            if (about == null) {
                throw ApiException.badRequest("Choose whom the feedback is about");
            }
            if (!Perf.own(c, ctx, about) && (!Perf.reaches(c, ctx, "performance.write", about, today) || ctx.scopeOf("performance.write") == null)) {
                throw ApiException.notFound("The person");
            }
            Map<String, String> v = ExitValues.forEmployment(c, about, null);
            v.put("requester.name", (String) Sql.one(c, "select " + Perf.who("x.m") + " as who from (select ?::uuid as m) x", ctx.membershipId())
                    .get("who"));
            for (UUID g : givers) {
                if (g.equals(about)) {
                    throw ApiException.badRequest("Ask someone other than the person the feedback is about");
                }
                if (Sql.one(c, "select 1 from app.employment where id = ? and status = 'active' and membership_id is not null", g) == null) {
                    throw ApiException.badRequest("Ask colleagues who work here and can sign in");
                }
                if (Sql.one(c, "select 1 from app.feedback_request where requested_by = ? and about_id = ? and giver_id = ? and status = 'open'",
                        ctx.membershipId(), about, g) != null) {
                    continue;   // already asked and waiting
                }
                Sql.update(c, "insert into app.feedback_request (tenant_id, requested_by, about_id, giver_id, note) values (?, ?, ?, ?, ?)",
                        ctx.tenantId(), ctx.membershipId(), about, g, note);
                Notifications.send(c, ctx.tenantId(), membership(c, g), "feedback_requested", v, "my-performance");
            }
            UserService.audit(c, ctx, "performance.feedback_requested", "employment", about, Map.of("count", givers.size()));
            return asked(c, ctx);
        });
    }

    /** What the caller has asked for, newest first, with whether each was given. */
    public List<Map<String, Object>> asked(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> asked(c, ctx));
    }

    private static List<Map<String, Object>> asked(Connection c, SessionCtx ctx) throws SQLException {
        return Sql.query(c, "select q.id, q.status, q.note, q.declined_reason, q.created_at, q.answered_at, "
                + "coalesce(ap.preferred_name, ap.legal_name) as about, coalesce(gp.preferred_name, gp.legal_name) as giver "
                + "from app.feedback_request q join app.employment ae on ae.id = q.about_id join app.person ap on ap.id = ae.person_id "
                + "join app.employment ge on ge.id = q.giver_id join app.person gp on gp.id = ge.person_id "
                + "where q.requested_by = ? order by q.created_at desc limit 50", ctx.membershipId());
    }

    /** Requests for feedback waiting for the caller. */
    public List<Map<String, Object>> askedOfMe(SessionCtx ctx) {
        Perf.requireMember(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, "select q.id, q.note, q.created_at, q.about_id, "
                + "coalesce(ap.preferred_name, ap.legal_name) as about, " + Perf.who("q.requested_by") + " as requested_by_name, "
                + "(ae.membership_id = q.requested_by) as about_themselves from app.feedback_request q "
                + "join app.employment ae on ae.id = q.about_id join app.person ap on ap.id = ae.person_id "
                + "join app.employment ge on ge.id = q.giver_id where ge.membership_id = ? and q.status = 'open' and ae.status = 'active' "
                + "order by q.created_at", ctx.membershipId()));
    }

    /** The colleague declines a request, with a reason the person who asked reads. */
    public List<Map<String, Object>> decline(SessionCtx ctx, UUID id, TextInput in) {
        Perf.requireMember(ctx);
        String reason = Perf.text(in == null ? null : in.text(), "why you are declining", 3, 300);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> q = Sql.one(c, "select q.* from app.feedback_request q join app.employment e on e.id = q.giver_id "
                    + "where q.id = ? and e.membership_id = ? for update of q", id, ctx.membershipId());
            if (q == null) {
                throw ApiException.notFound("The request");
            }
            if (!"open".equals(q.get("status"))) {
                throw new ApiException(409, "request.refused", "That request has already been answered");
            }
            Sql.update(c, "update app.feedback_request set status = 'declined', declined_reason = ?, answered_at = now() where id = ?", reason, id);
            return List.<Map<String, Object>>of();
        });
    }

    // ── internals ───────────────────────────────────────────────────────

    private static final String SELECT = "select f.id, f.kind, f.share_with_manager, f.created_at, f.edited_at, f.withdrawn_at, f.giver_id, "
            + "f.receiver_id, " + Perf.who("f.giver_id") + " as giver, coalesce(rp.preferred_name, rp.legal_name) as receiver, "
            + "g.title as goal, k.name as competency from app.feedback f join app.employment re on re.id = f.receiver_id "
            + "join app.person rp on rp.id = re.person_id left join app.goal g on g.id = f.goal_id left join app.competency k on k.id = f.competency_id ";

    private Map<String, Object> decorate(Connection c, SessionCtx ctx, Map<String, Object> f) throws SQLException {
        boolean mine = ctx.membershipId() != null && ctx.membershipId().toString().equals(f.get("giverId"));
        f.put("text", f.get("withdrawnAt") != null || ctx.isSupport() ? null : Perf.open(c, crypto, ctx.tenantId(), "feedback", "text_enc", f.get("id")));
        f.put("mine", mine);
        f.put("canEdit", mine && f.get("withdrawnAt") == null
                && java.time.Instant.parse((String) f.get("createdAt")).isAfter(java.time.Instant.now().minusSeconds(24 * 3600)));
        f.put("canWithdraw", mine && f.get("withdrawnAt") == null);
        f.remove("giverId");
        return f;
    }

    private Map<String, Object> item(Connection c, SessionCtx ctx, UUID id) throws SQLException {
        return decorate(c, ctx, Sql.one(c, SELECT + "where f.id = ?", id));
    }

    private static Map<String, Object> ownFeedback(Connection c, SessionCtx ctx, UUID id) throws SQLException {
        Map<String, Object> f = Sql.one(c, "select * from app.feedback where id = ? for update", id);
        if (f == null || !ctx.membershipId().toString().equals(f.get("giverId"))) {
            throw ApiException.notFound("The feedback");
        }
        return f;
    }

    private static UUID membership(Connection c, UUID employment) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select membership_id from app.employment where id = ?", employment);
        return e == null ? null : AuthService.uuid(e.get("membershipId"));
    }
}

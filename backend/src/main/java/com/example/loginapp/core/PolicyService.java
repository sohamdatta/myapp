package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Policies a joiner reads and acknowledges: the code of conduct, leave, IT
 * use, prevention of sexual harassment. Each has rules for whom it applies to
 * and is due before activation or within some days of joining. A change to
 * its text is a new version, and an acknowledgement is of one version.
 */
public final class PolicyService {

    public record PolicyInput(String name, String body, String due, Integer withinDays, Map<String, Object> appliesTo, Boolean active) {}

    private final Db db;

    public PolicyService(Db db) {
        this.db = db;
    }

    // ── setup ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> policies(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> p : Sql.query(c, "select * from app.policy order by lower(name)")) {
                p.put("appliesTo", AppliesTo.parse((String) p.get("appliesTo")).rules());
                out.add(p);
            }
            return out;
        });
    }

    public Map<String, Object> save(SessionCtx ctx, UUID id, PolicyInput in) {
        ctx.require("onboarding.configure");
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.policy where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The policy");
                }
            }
            String name = in.name() != null ? in.name().trim() : current == null ? "" : (String) current.get("name");
            if (name.isEmpty() || name.length() > 80) {
                throw ApiException.badRequest("Give the policy a name of at most 80 characters");
            }
            String body = in.body() != null ? in.body().strip() : current == null ? null : (String) current.get("body");
            if (body == null || body.length() < 20 || body.length() > 50000) {
                throw ApiException.badRequest("Write the policy, from 20 to 50,000 characters");
            }
            String due = in.due() != null ? in.due().trim() : current == null ? "before_activation" : (String) current.get("due");
            if (!Set.of("before_activation", "within_days").contains(due)) {
                throw ApiException.badRequest("Due must be before_activation or within_days");
            }
            Integer within = null;
            if (due.equals("within_days")) {
                within = in.withinDays() != null ? in.withinDays()
                        : current == null || current.get("withinDays") == null ? null : ((Number) current.get("withinDays")).intValue();
                if (within == null || within < 1 || within > 365) {
                    throw ApiException.badRequest("Within days must be from 1 to 365");
                }
            }
            AppliesTo rules = in.appliesTo() != null ? AppliesTo.from(in.appliesTo())
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            UUID saved;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.policy (tenant_id, name, body, due, within_days, applies_to, active) "
                        + "values (?, ?, ?, ?, ?, ?::jsonb, ?) returning id", ctx.tenantId(), name, body, due,
                        within == null ? null : within.shortValue(), rules.toJson(), active).get("id"));
            } else {
                saved = id;
                boolean reworded = !body.equals(current.get("body"));
                Sql.update(c, "update app.policy set name = ?, body = ?, due = ?, within_days = ?, applies_to = ?::jsonb, active = ?, "
                        + "version = version + ?, published_at = case when ? then now() else published_at end where id = ?", name, body, due,
                        within == null ? null : within.shortValue(), rules.toJson(), active, reworded ? 1 : 0, reworded, id);
            }
            Map<String, Object> after = Sql.one(c, "select * from app.policy where id = ?", saved);
            UserService.audit(c, ctx, id == null ? "onboarding.policy_created" : "onboarding.policy_changed", "policy", saved,
                    Map.of("name", name, "version", after.get("version")));
            after.put("appliesTo", rules.rules());
            return after;
        });
    }

    // ── a joiner's policies ─────────────────────────────────────────────

    public List<Map<String, Object>> forJoiner(SessionCtx ctx, UUID onboardingId) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireSee(c, ctx, l);
            return list(c, l);
        });
    }

    public List<Map<String, Object>> mine(SessionCtx ctx) {
        return db.inTenant(ctx.tenantId(), c -> list(c, JoinerService.mineLoaded(c, ctx)));
    }

    /** The joiner acknowledges the version they read. If the policy changed meanwhile, they are asked to read it again. */
    public List<Map<String, Object>> acknowledge(SessionCtx ctx, UUID policyId, Integer version) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.mineLoaded(c, ctx);
            if (!Set.of("invited", "in_progress", "active", "draft").contains((String) l.row().get("status"))) {
                throw new ApiException(409, "onboarding.closed", "There is nothing to acknowledge now");
            }
            if (l.row().get("noticeId") == null) {
                throw new ApiException(409, "onboarding.locked", "Accept the notice first");
            }
            Map<String, Object> p = applicable(c, l).stream().filter(x -> policyId.toString().equals(x.get("id"))).findFirst()
                    .orElseThrow(() -> ApiException.notFound("The policy"));
            if (version == null || version != ((Number) p.get("version")).intValue()) {
                throw new ApiException(409, "policy.changed", "This policy has changed since you opened it. Read the new version, then acknowledge it");
            }
            Sql.update(c, "insert into app.policy_acknowledgement (tenant_id, policy_id, person_id, version) values (?, ?, ?, ?) "
                    + "on conflict do nothing", l.tenantId(), policyId, l.personId(), version);
            UserService.audit(c, ctx, "joiner.policy_acknowledged", "onboarding", l.id(), Map.of("policy", p.get("name"), "version", version));
            JoinerService.noteIfReady(c, l.id());
            return list(c, l);
        });
    }

    // ── shared ──────────────────────────────────────────────────────────

    static List<Map<String, Object>> applicable(Connection c, JoinerService.Loaded l) throws SQLException {
        AppliesTo.Subject subject = JoinerService.subject(c, l);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : Sql.query(c, "select id, name, version, body, due, within_days, published_at, applies_to from app.policy "
                + "where active order by lower(name)")) {
            if (AppliesTo.parse((String) p.remove("appliesTo")).matches(subject)) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> list(Connection c, JoinerService.Loaded l) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : applicable(c, l)) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            Map<String, Object> ack = Sql.one(c, "select version, acknowledged_at from app.policy_acknowledgement where policy_id = ?::uuid "
                    + "and person_id = ? order by version desc limit 1", p.get("id"), l.personId());
            m.put("acknowledgedVersion", ack == null ? null : ack.get("version"));
            m.put("acknowledgedAt", ack == null ? null : ack.get("acknowledgedAt"));
            m.put("current", ack != null && ((Number) ack.get("version")).intValue() == ((Number) p.get("version")).intValue());
            if ("within_days".equals(p.get("due"))) {
                m.put("dueOn", l.joiningDate().plusDays(((Number) p.get("withinDays")).longValue()).toString());
            }
            out.add(m);
        }
        return out;
    }

    /** Policies due before activation that the joiner has not acknowledged at their current version. */
    static List<String> blockers(Connection c, JoinerService.Loaded l) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : list(c, l)) {
            if ("before_activation".equals(m.get("due")) && !Boolean.TRUE.equals(m.get("current"))) {
                out.add("Policy: " + m.get("name") + " not acknowledged");
            }
        }
        return out;
    }
}

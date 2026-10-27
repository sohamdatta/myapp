package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.loginapp.core.SessionCtx.Perm;

/** Sign-in, sessions and choosing an Organization. */
public final class AuthService {


    /** One answer for every reason sign-in is refused for trying too much, so it does not say whether an account exists. */
    static ApiException tooManyFailures() {
        return new ApiException(429, "auth.locked", "Too many failed sign-ins. Try again in a few minutes");
    }

    private final Db db;
    private final RateLimits limits;

    public AuthService(Db db) {
        this(db, new RateLimits());
    }

    public AuthService(Db db, RateLimits limits) {
        this.db = db;
        this.limits = limits;
    }

    /** Checks the email and password and returns a token for a session with no Organization chosen yet. */
    public String login(String email, String password) {
        return login(email, password, null);   // no address: callers inside the process, which are not limited by one
    }

    /** The result of the password step: a session token, or (when the login has two-step sign-in) a challenge to answer with a code. */
    public record SignIn(String token, boolean mfaRequired) {}

    /**
     * As {@link #login(String, String)}, limited by the email and by the address the request came from. For a login with
     * two-step sign-in set up this refuses: use {@link #signIn}, then answer the challenge.
     */
    public String login(String email, String password, String address) {
        SignIn result = signIn(email, password, address);
        if (result.mfaRequired()) {
            throw new ApiException(403, "auth.mfa_required", "This login needs the code from its authenticator app");
        }
        return result.token();
    }

    /**
     * The password step of signing in. Returns a session, or a challenge when the login has two-step sign-in: nothing is
     * granted, and the run of failures is not cleared, until the code is also right (see {@link MfaService#complete}).
     */
    public SignIn signIn(String email, String password, String address) {
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throw new ApiException(400, "request.invalid", "Enter your email and password");
        }
        if (address != null && !limits.allowed(RateLimits.LOGIN_FAILURES_PER_ADDRESS, address)) {
            throw tooManyFailures();
        }
        String token = Secrets.newToken();
        String outcome = db.inAuth(c -> {
            lockEmail(c, email);   // one attempt per email at a time, so parallel guesses are counted, not raced past the limit
            // The lock is kept per email address, account or not, so "locked" does not say "exists".
            if (lockedOut(c, email)) {
                return "locked";
            }
            Map<String, Object> identity = Sql.one(c,
                    "select id, password_hash, status, mfa_enrolled_at is not null as mfa from auth.identity where email = ?::citext for update",
                    email.trim());
            if (identity == null) {
                Secrets.verifyPassword(password, DUMMY_HASH);   // same work as a real check
                recordFailure(c, email);
                return "invalid";
            }
            UUID id = UUID.fromString((String) identity.get("id"));
            // the password is checked whatever the status, so a suspended account takes as long to refuse as any other
            boolean passwordOk = Secrets.verifyPassword(password, (String) identity.get("passwordHash"));
            if (!passwordOk || !"active".equals(identity.get("status"))) {
                recordFailure(c, email);
                return "invalid";
            }
            if (Boolean.TRUE.equals(identity.get("mfa"))) {
                // not signed in yet, and the failures stay: guessing codes after a right password must still lock
                Sql.update(c, "insert into auth.mfa_challenge (token_hash, identity_id, expires_at) values (?, ?, now() + interval '5 minutes')",
                        Secrets.sha256(token), id);
                return "mfa";
            }
            clearFailures(c, email);
            Sql.update(c, "update auth.identity set last_login_at = now() where id = ?", id);
            insertSession(c, token, id, "identity", null, null, null, "30 minutes");
            return "ok";
        });
        if (outcome.equals("locked")) {
            throw tooManyFailures();   // the same answer as for an address that has failed too often but has no account
        }
        if (!outcome.equals("ok") && !outcome.equals("mfa")) {
            if (address != null) {
                limits.hit(RateLimits.LOGIN_FAILURES_PER_ADDRESS, address);
            }
            throw new ApiException(401, "auth.invalid_credentials", "Incorrect email or password");
        }
        return new SignIn(token, outcome.equals("mfa"));
    }

    private static final String DUMMY_HASH = "pbkdf2-sha256$210000$AAAAAAAAAAAAAAAAAAAAAA==$AAAA";

    /** Makes the rest of this transaction the only one working on this email address. Released at commit. */
    static void lockEmail(Connection c, String email) throws SQLException {
        Sql.query(c, "select pg_advisory_xact_lock(hashtext(lower(?)))", email.trim());
    }

    /** Whether this email address has failed as many times as the platform allows, within the platform's lock time. */
    static boolean lockedOut(Connection c, String email) throws SQLException {
        int after = Settings.platform(c, Settings.LOCKOUT_AFTER).asInt();
        int minutes = Settings.platform(c, Settings.LOCKOUT_MINUTES).asInt();
        Number recent = (Number) Sql.one(c, "select count(*) as n from auth.login_failure "
                + "where email = ?::citext and at > now() - make_interval(mins => ?)", email.trim(), minutes).get("n");
        return recent.longValue() >= after;
    }

    /** Counts a wrong password against this email address, account or not; also clears rows too old to matter. */
    static void recordFailure(Connection c, String email) throws SQLException {
        Sql.update(c, "insert into auth.login_failure (email) values (?::citext)", email.trim());
        Sql.update(c, "delete from auth.login_failure where email = ?::citext and at < now() - interval '1 day'", email.trim());
    }

    /** A correct password ends the run of failures. */
    static void clearFailures(Connection c, String email) throws SQLException {
        Sql.update(c, "delete from auth.login_failure where email = ?::citext", email.trim());
    }

    /** Loads the session for a token, with its current permissions. Throws when it is missing, expired or revoked. */
    public SessionCtx resolve(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.notSignedIn();
        }
        Map<String, Object> s = db.inAuth(c -> {
            Map<String, Object> row = Sql.one(c,
                    "select s.id, s.identity_id, s.kind, s.tenant_id, s.membership_id, s.impersonation_grant_id, i.email, "
                            + "t.name as tenant_name, t.status as tenant_status, "
                            + "s.last_seen_at < now() - interval '1 minute' as seen_stale, "
                            + "g.mode as grant_mode, g.expires_at as grant_expires_at, "
                            + "(g.status = 'approved' and g.revoked_at is null and g.expires_at > now()) as grant_live, "
                            + "p.id as platform_user_id, p.role as platform_role, p.status as platform_status, "
                            + "array(select m.module from platform.tenant_module m where m.tenant_id = s.tenant_id and m.status = 'on' "
                            + "  and (m.trial_ends_on is null or m.trial_ends_on >= (now() at time zone 'Asia/Kolkata')::date) "
                            + "  order by m.module) as modules "
                            + "from auth.session s join auth.identity i on i.id = s.identity_id "
                            + "left join platform.tenant t on t.id = s.tenant_id "
                            + "left join platform.impersonation_grant g on g.id = s.impersonation_grant_id "
                            + "left join platform.platform_user p on p.identity_id = s.identity_id "
                            + "where s.refresh_token_hash = ? and s.revoked_at is null and s.expires_at > now() "
                            + "and i.status = 'active' "
                            + "and s.last_seen_at > now() - case s.kind when 'platform' then interval '15 minutes' "
                            + "when 'identity' then interval '30 minutes' else interval '60 minutes' end",
                    Secrets.sha256(token));
            // The idle limits are 15, 30 and 60 minutes, so a minute's resolution is enough: write the
            // time at most once a minute per session, not on every request.
            if (row != null && Boolean.TRUE.equals(row.get("seenStale"))) {
                Sql.update(c, "update auth.session set last_seen_at = now() where id = ?::uuid", row.get("id"));
            }
            return row;
        });
        if (s == null) {
            throw ApiException.notSignedIn();
        }
        String kind = (String) s.get("kind");
        UUID tenantId = uuid(s.get("tenantId"));
        UUID membershipId = uuid(s.get("membershipId"));
        List<Perm> permissions = List.of();

        if (kind.equals("platform") && !"active".equals(s.get("platformStatus"))) {
            throw ApiException.notSignedIn();
        }
        if (kind.equals("tenant")) {
            if (!"active".equals(s.get("tenantStatus"))) {
                throw new ApiException(403, "tenant.unavailable", "This Organization is suspended or closed");
            }
            try {
                // An employee whose last working day has passed loses their roles now, keeping Ex-employee.
                permissions = db.inTenant(tenantId, c -> {
                    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                    EmployeeService.endIfLeft(c, tenantId, membershipId, today);
                    EmployeeService.linkIfInvited(c, membershipId);
                    // A joiner whose joining date has come, with nothing blocking, becomes an employee now.
                    JoinerService.activateIfDue(c, tenantId, membershipId, today);
                    return permissions(c, "select permission_code, scope from app.effective_permissions(?)", membershipId);
                });
            } catch (ApiException e) {
                if (!e.code().equals("membership.last_owner")) {
                    throw e;
                }
                permissions = db.inTenant(tenantId, c -> permissions(c,
                        "select permission_code, scope from app.effective_permissions(?)", membershipId));
            }
            if (permissions.isEmpty()) {
                throw ApiException.notSignedIn();   // suspended, or ended with no roles left
            }
        }
        if (kind.equals("support")) {
            if (!Boolean.TRUE.equals(s.get("grantLive")) || "closed".equals(s.get("tenantStatus"))) {
                throw new ApiException(401, "support.expired", "The support access grant has expired or was revoked");
            }
            String role = "write".equals(s.get("grantMode")) ? "support_write" : "support_read";
            permissions = db.inTenant(tenantId, c -> permissions(c,
                    "select rp.permission_code, rp.scope from app.role r "
                            + "join app.role_permission rp on rp.role_id = r.id where r.code = ? and r.tenant_id is null", role));
        }
        return new SessionCtx(
                uuid(s.get("id")), uuid(s.get("identityId")), (String) s.get("email"), kind,
                tenantId, (String) s.get("tenantName"), membershipId,
                uuid(s.get("impersonationGrantId")), (String) s.get("grantMode"), (String) s.get("grantExpiresAt"),
                kind.equals("platform") ? uuid(s.get("platformUserId")) : null,
                kind.equals("platform") ? (String) s.get("platformRole") : null,
                permissions,
                modules(s.get("modules")));
    }

    private static Set<String> modules(Object value) {
        Set<String> out = new java.util.TreeSet<>();
        if (value instanceof List<?> list) {
            list.forEach(m -> out.add(String.valueOf(m)));
        }
        return Set.copyOf(out);
    }

    /** What the signed-in person sees about themselves, and what they can enter. */
    public Map<String, Object> me(SessionCtx ctx) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("email", ctx.email());
        me.put("kind", ctx.kind());
        me.put("organization", ctx.inOrganization() ? Map.of("id", ctx.tenantId().toString(), "name", ctx.tenantName(),
                "logoVersion", db.inTenant(ctx.tenantId(), Branding::version)) : null);
        me.put("membershipId", ctx.membershipId() == null ? null : ctx.membershipId().toString());
        me.put("platformRole", ctx.platformRole());
        List<String> codes = ctx.permissions().stream().map(Perm::code).distinct().sorted().toList();
        me.put("permissions", codes);
        Map<String, Object> scopes = new LinkedHashMap<>();
        for (String code : codes) {
            String scope = ctx.scopeOf(code);
            if (scope != null) {
                scopes.put(code, scope);
            }
        }
        me.put("scopes", scopes);   // the widest scope held for each permission
        me.put("modules", ctx.inOrganization() ? ctx.modules().stream().sorted().toList() : List.of());
        // What the Organization has bought, with any trial end, for its Org Admin to see.
        if (ctx.inOrganization() && ctx.can("tenant.manage")) {
            me.put("subscription", db.inAuth(c -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (String module : Modules.ALL) {
                    Map<String, Object> m = Sql.one(c, "select status, trial_ends_on from platform.tenant_module where tenant_id = ? and module = ?",
                            ctx.tenantId(), module);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("module", module);
                    row.put("name", Modules.name(module));
                    row.put("active", ctx.hasModule(module));
                    row.put("trialEndsOn", m == null || !"on".equals(m.get("status")) ? null : m.get("trialEndsOn"));
                    out.add(row);
                }
                return out;
            }));
        }
        me.put("openTasks", ctx.membershipId() == null ? 0 : db.inTenant(ctx.tenantId(), c -> ChecklistService.openCount(c, ctx.membershipId())
                + ExitTasks.openCount(c, ctx.membershipId())));
        // Whether the person has an employment record (so My exit can be offered), and their exit if one is open.
        me.put("employment", ctx.membershipId() == null || ctx.isSupport() ? null : db.inTenant(ctx.tenantId(), c -> Sql.one(c,
                "select e.id, e.status, x.status as exit_status, x.last_working_day from app.employment e "
                        + "left join lateral (select * from app.exit_case x where x.employment_id = e.id "
                        + "  and x.status in ('submitted','recommended','accepted','closed') order by x.started_at desc limit 1) x on true "
                        + "where e.membership_id = ? and e.status in ('active','exited') order by (e.status = 'active') desc limit 1",
                ctx.membershipId())));
        me.put("roles", ctx.membershipId() == null ? List.of() : db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select r.code from app.membership_role mr join app.role r on r.id = mr.role_id where mr.membership_id = ? order by r.code",
                ctx.membershipId()).stream().map(r -> (String) r.get("code")).toList()));
        if (ctx.isSupport()) {
            me.put("support", Map.of("mode", ctx.grantMode(), "expiresAt", ctx.grantExpiresAt()));
        }
        me.putAll(destinations(ctx));
        return me;
    }

    /** The Organizations this login can enter, and whether it can open the console. */
    public Map<String, Object> destinations(SessionCtx ctx) {
        return db.inAuth(c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("organizations", Sql.query(c,
                    "select tenant_id, tenant_name, tenant_slug, membership_id, status "
                            + "from auth.memberships_for_identity(?) order by tenant_name", ctx.identityId()));
            out.put("suspendedIn", Sql.query(c, "select tenant_name from auth.suspended_memberships_for_identity(?) order by tenant_name",
                    ctx.identityId()).stream().map(r -> r.get("tenantName")).toList());
            Map<String, Object> platform = Sql.one(c,
                    "select role from platform.platform_user where identity_id = ? and status = 'active'", ctx.identityId());
            out.put("console", platform == null ? null : platform.get("role"));
            return out;
        });
    }

    /** Starts a session inside one Organization and ends the current one. Switching always issues a new token. */
    public String enterOrganization(SessionCtx ctx, UUID tenantId) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "End the support session first");
        }
        String token = Secrets.newToken();
        db.inAuth(c -> {
            Map<String, Object> m = Sql.one(c,
                    "select membership_id from auth.memberships_for_identity(?) where tenant_id = ?",
                    ctx.identityId(), tenantId);
            if (m == null) {
                throw new ApiException(403, "tenant.unavailable", "You do not have access to that Organization");
            }
            insertSession(c, token, ctx.identityId(), "tenant", tenantId, uuid(m.get("membershipId")), null, "12 hours");
            carryMfa(c, ctx.sessionId(), token);
            revoke(c, ctx.sessionId());
            return null;
        });
        return token;
    }

    /** Starts a console session, which carries no Organization at all. */
    public String enterConsole(SessionCtx ctx) {
        if (ctx.isSupport()) {
            throw new ApiException(403, "permission.denied", "End the support session first");
        }
        String token = Secrets.newToken();
        db.inAuth(c -> {
            if (Sql.one(c, "select 1 from platform.platform_user where identity_id = ? and status = 'active'",
                    ctx.identityId()) == null) {
                throw new ApiException(403, "permission.denied", "This login has no platform role");
            }
            insertSession(c, token, ctx.identityId(), "platform", null, null, null, "8 hours");
            carryMfa(c, ctx.sessionId(), token);
            revoke(c, ctx.sessionId());
            return null;
        });
        return token;
    }

    public void logout(SessionCtx ctx) {
        int ended = db.inAuth(c -> revoke(c, ctx.sessionId()));
        if (ended > 0 && ctx.isSupport()) {
            // The Organization saw support come in; it sees them leave too.
            db.inTenant(ctx.tenantId(), c -> Sql.update(c,
                    "insert into app.audit_event (tenant_id, actor_kind, actor_label, impersonation_grant_id, action, target_type, detail) "
                            + "values (?, 'support', ?, ?, 'support.session_ended', 'support', '{}'::jsonb)",
                    ctx.tenantId(), ctx.email(), ctx.grantId()));
        }
    }

    // ── shared with the other services ──────────────────────────────────

    static void insertSession(Connection c, String token, UUID identityId, String kind, UUID tenantId,
                              UUID membershipId, UUID grantId, String lifetime) throws SQLException {
        Sql.update(c, "insert into auth.session (identity_id, kind, tenant_id, membership_id, impersonation_grant_id, "
                        + "refresh_token_hash, expires_at) values (?, ?, ?, ?, ?, ?, now() + ?::interval)",
                identityId, kind, tenantId, membershipId, grantId, Secrets.sha256(token), lifetime);
    }

    /** A new session made from an old one (switching Organization) keeps the old one's proof of a recent code. */
    static void carryMfa(Connection c, UUID fromSession, String newToken) throws SQLException {
        Sql.update(c, "update auth.session set mfa_at = (select mfa_at from auth.session where id = ?) where refresh_token_hash = ?",
                fromSession, Secrets.sha256(newToken));
    }

    /** Ends a session; returns 1 when it was still open, 0 when it had already ended. */
    static int revoke(Connection c, UUID sessionId) throws SQLException {
        return Sql.update(c, "update auth.session set revoked_at = now() where id = ? and revoked_at is null", sessionId);
    }

    /** Ends every open session of a person, except the one named (if any). */
    static int revokeAll(Connection c, UUID identityId, UUID except) throws SQLException {
        return Sql.update(c, "update auth.session set revoked_at = now() where identity_id = ? and revoked_at is null "
                + "and (?::uuid is null or id <> ?::uuid)", identityId, except, except);
    }

    static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static List<Perm> permissions(Connection c, String sql, Object param) throws SQLException {
        List<Perm> out = new ArrayList<>();
        for (Map<String, Object> row : Sql.query(c, sql, param)) {
            out.add(new Perm((String) row.get("permissionCode"), (String) row.get("scope")));
        }
        return out;
    }
}

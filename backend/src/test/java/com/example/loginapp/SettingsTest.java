package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveAdminService.TypeInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Setting;
import com.example.loginapp.core.Setting.Approval;
import com.example.loginapp.core.Setting.Scope;
import com.example.loginapp.core.Setting.Weakens;
import com.example.loginapp.core.Settings;
import com.example.loginapp.core.Settings.Context;
import com.example.loginapp.core.Settings.Platform;
import com.example.loginapp.core.Settings.Row;
import com.example.loginapp.core.SettingsService;
import com.example.loginapp.core.SettingsService.BoundInput;
import com.example.loginapp.core.SettingsService.ChangeInput;
import com.example.loginapp.core.SettingsService.DecisionInput;
import com.example.loginapp.core.SettingsService.PlatformInput;
import com.example.loginapp.core.Sql;
import com.example.loginapp.core.UserService;

/**
 * The configuration system: how a value is resolved (scopes, dates, platform defaults, bounds, locks)
 * without a database; then against PostgreSQL the change flow (ranges, password for a weakening change,
 * a second person, undo, history, notices) and the first settings taking effect in sign-in, invitations,
 * leave and attendance.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class SettingsTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    // Keys only these tests declare: a second-person key, and a dated key that may vary by location.
    private static final Setting SECOND = Settings.define(Setting.integer("test.second_person.limit").group("security")
            .label("A limit two people decide").defaultValue(5).range(1, 20).weakens(Weakens.UP, Approval.SECOND_PERSON));
    private static final Setting DATED = Settings.define(Setting.integer("test.dated.limit").group("security")
            .label("A limit that changes from a date").defaultValue(1).range(0, 9).dated().scopes(Scope.ORGANIZATION, Scope.LOCATION));

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static SettingsService settings;
    private static LeaveService leave;
    private static LeaveAdminService leaveAdmin;
    private static AttendanceSettingsService attendanceSettings;
    private static SessionCtx superAdmin;
    private static boolean reachable;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_settings with (force)");
            st.execute("create database hrms_test_settings");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        if (!reachable) {
            return;
        }
        String url = "jdbc:postgresql://" + host + "/hrms_test_settings";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        settings = new SettingsService(db);
        leave = new LeaveService(db);
        leaveAdmin = new LeaveAdminService(db);
        attendanceSettings = new AttendanceSettingsService(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── resolving, without a database ───────────────────────────────────

    @Test
    void theNarrowestScopeInForceOnTheDateWins() {
        UUID pune = UUID.randomUUID();
        UUID change = UUID.randomUUID();
        List<Row> rows = List.of(
                new Row(Scope.ORGANIZATION, null, LocalDate.of(2026, 1, 1), 3, change),
                new Row(Scope.ORGANIZATION, null, LocalDate.of(2026, 6, 1), 4, change),
                new Row(Scope.LOCATION, pune, LocalDate.of(2026, 9, 1), 7, change));
        assertEquals(1, Settings.resolve(DATED, rows, Platform.NONE, Context.ORGANIZATION, LocalDate.of(2025, 12, 31)).value());
        assertEquals(3, Settings.resolve(DATED, rows, Platform.NONE, Context.ORGANIZATION, LocalDate.of(2026, 3, 1)).value());
        assertEquals(4, Settings.resolve(DATED, rows, Platform.NONE, Context.ORGANIZATION, LocalDate.of(2026, 10, 1)).value());
        // Pune before its own value starts follows the Organization; from 1 Sep its own.
        assertEquals(4, Settings.resolve(DATED, rows, Platform.NONE, Context.location(pune), LocalDate.of(2026, 8, 31)).value());
        Settings.Resolved r = Settings.resolve(DATED, rows, Platform.NONE, Context.location(pune), LocalDate.of(2026, 9, 1));
        assertEquals(7, r.value());
        assertEquals("location from 2026-09-01", r.source());
        // Another location has nothing of its own.
        assertEquals(4, Settings.resolve(DATED, rows, Platform.NONE, Context.location(UUID.randomUUID()), LocalDate.of(2026, 9, 1)).value());
    }

    @Test
    void thePlatformGivesDefaultsBoundsAndLocks() {
        assertEquals(5, Settings.resolve(SECOND, List.of(), Platform.NONE, Context.ORGANIZATION, TODAY).value());
        assertEquals(8, Settings.resolve(SECOND, List.of(), new Platform(8, null, null, null, false, null), Context.ORGANIZATION, TODAY).value());
        List<Row> high = List.of(new Row(Scope.ORGANIZATION, null, null, 15, UUID.randomUUID()));
        Settings.Resolved clamped = Settings.resolve(SECOND, high, new Platform(null, null, 10, null, false, null), Context.ORGANIZATION, TODAY);
        assertEquals(10, clamped.value());
        assertTrue(clamped.clamped());
        assertEquals(2, Settings.resolve(SECOND, high, new Platform(null, null, null, null, true, 2), Context.ORGANIZATION, TODAY).value());
    }

    @Test
    void definitionsAreCheckedWhenDeclared() {
        assertThrows(IllegalStateException.class, () -> Setting.integer("test.both.scopes").label("x").defaultValue(1).range(0, 2)
                .scopes(Scope.LOCATION, Scope.PAY_GROUP).build());
        assertThrows(ApiException.class, () -> Setting.integer("test.bad.default").label("x").defaultValue(5).range(0, 2).build());
        assertThrows(IllegalArgumentException.class, () -> Setting.integer("NotDotted").label("x").defaultValue(1).build());
        assertThrows(IllegalStateException.class, () -> Settings.define(Setting.integer("test.second_person.limit").label("again").defaultValue(1)));
    }

    // ── changing, against the database ──────────────────────────────────

    @Test
    void aWeakeningChangeAsksForThePasswordAndCanBeUndone() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx hr = join(org, "hr", List.of("hr_admin", "employee"));
        SessionCtx asha = join(org, "asha", List.of("employee"));

        // The Org Admin may change Security; HR, who reads the audit trail, sees it but may not; an employee sees nothing.
        assertEquals(true, setting(org.admin(), "security", "auth.invitation.valid_days").get("mayChange"));
        assertEquals(false, setting(hr, "security", "auth.invitation.valid_days").get("mayChange"));
        assertTrue(((List<?>) settings.list(asha, null).get("groups")).isEmpty());
        assertError(403, "permission.denied", () -> settings.change(hr, "auth.invitation.valid_days", input(3, null)));

        // Shorter links are safer: applied at once, and the next invitation uses it.
        Map<String, Object> shorter = settings.change(org.admin(), "auth.invitation.valid_days", input(3, null));
        assertEquals("applied", ((Map<?, ?>) shorter.get("change")).get("status"));
        assertEquals(3, shorter.get("value"));
        Instant expires = Instant.parse((String) users.invite(org.admin(), "guest@" + org.slug() + ".test", List.of("employee")).get("expiresAt"));
        assertTrue(Math.abs(ChronoUnit.HOURS.between(Instant.now().plus(3, ChronoUnit.DAYS), expires)) <= 1, expires.toString());

        // Out of range, unchanged, unknown.
        assertError(400, "setting.out_of_range", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(31, null)));
        assertError(409, "setting.unchanged", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(3, null)));
        assertError(404, "setting.unknown", () -> settings.change(org.admin(), "auth.no.such", input(3, null)));

        // Longer links are weaker: the password is asked for, and checked.
        assertError(403, "setting.password_required", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(14, null)));
        assertError(403, "setting.password_wrong", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(14, "wrong-password")));
        Map<String, Object> longer = settings.change(org.admin(), "auth.invitation.valid_days", input(14, PASSWORD));
        assertEquals(true, ((Map<?, ?>) longer.get("change")).get("weakening"));
        assertTrue(events(org, org.admin()).contains("setting_weakened"));

        // The platform narrows the range: the stored 14 is held at 10, and 12 is refused.
        settings.setBound(superAdmin, "auth.invitation.valid_days", new BoundInput(null, 10, null, false, null, "Platform policy"));
        Map<String, Object> view = setting(org.admin(), "security", "auth.invitation.valid_days");
        assertEquals(10, view.get("value"));
        assertEquals(true, view.get("clamped"));
        assertError(400, "setting.out_of_range", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(12, PASSWORD)));
        settings.setBound(superAdmin, "auth.invitation.valid_days", new BoundInput(null, null, null, true, 7, "Fixed for now"));
        assertError(409, "setting.locked", () -> settings.change(org.admin(), "auth.invitation.valid_days", input(5, null)));
        settings.setBound(superAdmin, "auth.invitation.valid_days", new BoundInput(null, null, null, false, null, "Back to normal"));

        // Undo the weakening change: back to 3, through the same checks; the original is marked undone.
        UUID weaker = UUID.fromString((String) ((Map<?, ?>) longer.get("change")).get("id"));
        Map<String, Object> undone = settings.undo(org.admin(), weaker, null);
        assertEquals(3, undone.get("value"));
        assertError(409, "setting.not_applied", () -> settings.undo(org.admin(), weaker, null));
        List<Map<String, Object>> log = settings.changes(org.admin(), "auth.invitation.valid_days");
        assertEquals(List.of("applied", "undone", "applied"), log.stream().map(r -> (String) r.get("status")).toList());

        // Reset to the default: the stored value goes, and resetting again changes nothing.
        settings.change(org.admin(), "auth.invitation.valid_days", new ChangeInput(null, null, null, null, true, "Default is fine", PASSWORD));
        assertEquals("default", setting(org.admin(), "security", "auth.invitation.valid_days").get("source"));
    }

    @Test
    void aSecondPersonDecidesTheRiskiestChanges() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx other = join(org, "admin2", List.of("owner"));
        // Lower is safer: applied at once. Higher waits for someone else.
        settings.change(org.admin(), SECOND.key(), input(3, null));
        Map<String, Object> asked = settings.change(org.admin(), SECOND.key(), new ChangeInput(null, null, null, 9, null, "Busy season", null));
        Map<?, ?> change = (Map<?, ?>) asked.get("change");
        assertEquals("pending", change.get("status"));
        assertEquals(3, asked.get("value"));
        assertTrue(events(org, other).contains("setting_pending"));
        UUID id = UUID.fromString((String) change.get("id"));
        assertError(409, "setting.pending", () -> settings.change(org.admin(), SECOND.key(), input(4, null)));
        assertError(409, "setting.own_change", () -> settings.approve(org.admin(), id, null));
        assertError(400, "request.invalid", () -> settings.reject(other, id, new DecisionInput("", null)));
        Map<String, Object> approved = settings.approve(other, id, new DecisionInput("Agreed for October", null));
        assertEquals(9, approved.get("value"));
        assertTrue(events(org, org.admin()).contains("setting_decided"));
        assertError(409, "setting.decided", () -> settings.approve(other, id, null));

        // A second request, withdrawn by its requester before anyone decides.
        settings.change(org.admin(), SECOND.key(), input(1, null));
        UUID again = UUID.fromString((String) ((Map<?, ?>) settings.change(org.admin(), SECOND.key(), input(10, null)).get("change")).get("id"));
        assertError(403, "permission.denied", () -> settings.withdraw(other, again));
        settings.withdraw(org.admin(), again);
        assertEquals(1, setting(org.admin(), "security", SECOND.key()).get("value"));
    }

    @Test
    void datedValuesPerLocationAreReadForTheirDay() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        String pune = (String) attendanceSettings.saveLocation(org.admin(), null,
                new LocationInput("Pune office", "Asia/Kolkata", "MH", null, null, null, null, "none", true)).get("id");
        LocalDate start = TODAY.withDayOfMonth(1);
        assertError(400, "request.invalid", () -> settings.change(org.admin(), DATED.key(), input(3, null)));
        settings.change(org.admin(), DATED.key(), new ChangeInput("organization", null, start.toString(), 3, null, null, null));
        settings.change(org.admin(), DATED.key(), new ChangeInput("location", pune, start.plusMonths(1).toString(), 6, null, null, null));
        assertError(404, "not_found", () -> settings.change(org.admin(), DATED.key(),
                new ChangeInput("location", UUID.randomUUID().toString(), start.toString(), 2, null, null, null)));
        assertEquals(1, settings.explain(org.admin(), DATED.key(), null, null, start.minusDays(1).toString()).get("value"));
        assertEquals(3, settings.explain(org.admin(), DATED.key(), "location", pune, start.toString()).get("value"));
        Map<String, Object> later = settings.explain(org.admin(), DATED.key(), "location", pune, start.plusMonths(1).toString());
        assertEquals(6, later.get("value"));
        assertEquals("location from " + start.plusMonths(1), later.get("source"));
        assertEquals(2, ((List<?>) setting(org.admin(), "security", DATED.key()).get("values")).size());
    }

    // ── the first settings, in use ──────────────────────────────────────

    @Test
    void theLeaveWindowIsTheOrganizationsChoice() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        SessionCtx asha = join(org, "asha", List.of("employee"));
        UUID casual = UUID.fromString((String) leaveAdmin.saveType(org.admin(), null, new TypeInput("Casual leave", "regular", true, "yearly",
                new java.math.BigDecimal("12"), true, null, "lapse", "refuse", false, null, true)).get("id"));
        settings.change(org.admin(), "leave.apply.back_days", input(0, null));
        ApiException e = assertThrows(ApiException.class, () -> leave.apply(asha, casual, TODAY.minusDays(1).toString(), null, null, "Was unwell"));
        assertEquals("leave.window", e.code());
        assertTrue(e.getMessage().contains("last 0 days"), e.getMessage());
    }

    @Test
    void signInLocksAfterThePlatformsNumberOfFailures() throws Exception {
        assumeTrue(reachable, "PostgreSQL is not reachable");
        Org org = newOrg();
        join(org, "ravi", List.of("employee"));
        String ravi = "ravi@" + org.slug() + ".test";
        settings.setPlatformValue(superAdmin, "auth.lockout.after_failures", new PlatformInput(3, null, "Tighter for the audit"));
        settings.setPlatformValue(superAdmin, "auth.lockout.minutes", new PlatformInput(20, null, null));
        try {
            for (int i = 0; i < 3; i++) {
                assertError(401, "auth.invalid_credentials", () -> auth.login(ravi, "not-the-password"));
            }
            ApiException locked = assertThrows(ApiException.class, () -> auth.login(ravi, PASSWORD));
            assertEquals("auth.locked", locked.code());
            assertTrue(locked.getMessage().contains("20 minutes"), locked.getMessage());
        } finally {
            settings.setPlatformValue(superAdmin, "auth.lockout.after_failures", new PlatformInput(null, true, null));
            settings.setPlatformValue(superAdmin, "auth.lockout.minutes", new PlatformInput(null, true, null));
        }
        // Organizations cannot change a platform setting.
        assertError(404, "setting.unknown", () -> settings.explain(org.admin(), "auth.lockout.after_failures", null, null, null));
        assertError(403, "permission.denied", () -> settings.setPlatformValue(org.admin(), "auth.lockout.minutes", new PlatformInput(5, null, null)));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ChangeInput input(Object value, String password) {
        return new ChangeInput(null, null, null, value, null, null, password);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> setting(SessionCtx ctx, String group, String key) {
        return ((List<Map<String, Object>>) settings.list(ctx, group).get("settings")).stream()
                .filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    private static List<String> events(Org org, SessionCtx who) {
        return db.inTenant(org.id(), c -> Sql.query(c, "select event from app.notification where membership_id = ?", who.membershipId())
                .stream().map(r -> (String) r.get("event")).toList());
    }

    private record Org(UUID id, String slug, SessionCtx admin) {}

    private static Org newOrg() {
        String slug = "settings-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        return new Org(UUID.fromString((String) created.get("id")), slug, auth.resolve(token));
    }

    private static SessionCtx join(Org org, String name, List<String> roles) {
        String invitation = (String) users.invite(org.admin(), name + "@" + org.slug() + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(code, e.code(), e.getMessage());
        assertEquals(status, e.status(), e.getMessage());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}

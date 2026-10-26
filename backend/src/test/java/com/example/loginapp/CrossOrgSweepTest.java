package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AttendanceSettingsService.LocationInput;
import com.example.loginapp.core.AttendanceSettingsService.ShiftInput;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveAdminService.TypeInput;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/**
 * Every GET route the application serves, called as an administrator of one Organization with the
 * identifiers of another Organization in the path, and with none. Whatever the status, the body must
 * not contain anything that belongs to the other Organization: no row id from any tenant table, and
 * none of its names. Checking the body, not just for 403/404, is the point: a 200 that quietly
 * returns the wrong tenant's rows is the failure that matters.
 *
 * <p>The routes come from the running application's handler mapping, so a new endpoint is swept
 * without anyone remembering to add it. The data is seeded through the services (users, locations,
 * shifts, leave types, balances, requests) in both Organizations.
 *
 * <p>Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossOrgSweepTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String DATABASE = "hrms_test_crossorg";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    private static String host;
    private static String ownerUser;
    private static String ownerPassword;

    @LocalServerPort
    private int port;
    @Autowired
    private RequestMappingHandlerMapping mappings;
    @Autowired
    private AuthService auth;
    @Autowired
    private PlatformService platform;
    @Autowired
    private InvitationService invitations;
    @Autowired
    private UserService users;
    @Autowired
    private AttendanceSettingsService settings;
    @Autowired
    private LeaveAdminService leaveAdmin;
    @Autowired
    private LeaveService leave;

    @BeforeAll
    static void createDatabase() throws Exception {
        host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists " + DATABASE + " with (force)");
            st.execute("create database " + DATABASE);
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the cross-Organization sweep");
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("app.db.url", () -> "jdbc:postgresql://" + host + "/" + DATABASE);
        registry.add("app.db.owner.user", () -> ownerUser);
        registry.add("app.db.owner.password", () -> ownerPassword);
        registry.add("app.db.role-password", () -> ROLE_PASSWORD);
        registry.add("app.superadmin.email", () -> "root@platform.test");
        registry.add("app.superadmin.password", () -> PASSWORD);
    }

    private record Org(UUID id, String slug, String token, SessionCtx admin) {}

    @Test
    void noRouteShowsOneOrganizationTheOthersData() throws Exception {
        SessionCtx superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
        Org a = seed(superAdmin, "alpha");
        Org b = seed(superAdmin, "bravo");

        Set<String> idsOfA = idsOf(a);
        Set<String> idsOfB = idsOf(b);
        assertTrue(idsOfA.size() >= 12 && idsOfB.size() >= 12, "the seed should leave real rows behind, found " + idsOfA.size() + " and " + idsOfB.size());

        Map<String, List<String>> routes = getRoutes();
        assertTrue(routes.size() > 50, "expected the application's GET routes, found " + routes.size());

        // the sweep must be able to see data at all: an Organization's own ids do appear in its own responses
        Set<String> seenOwn = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        sweep(a, b, idsOfB, routes, failures, null);
        sweep(b, a, idsOfA, routes, failures, null);
        sweep(a, a, idsOfA, routes, new ArrayList<>(), seenOwn);
        assertTrue(seenOwn.size() >= 6, "own-Organization responses showed only " + seenOwn.size() + " of its own ids, so the sweep may be blind");

        assertTrue(failures.isEmpty(), failures.size() + " responses leaked or failed:\n" + String.join("\n", failures.subList(0, Math.min(failures.size(), 25))));
    }

    /**
     * Calls every route as {@code caller}'s administrator, substituting {@code subject}'s ids into
     * path variables. When subject is another Organization, nothing of it may appear in a body.
     */
    private void sweep(Org caller, Org subject, Set<String> forbidden, Map<String, List<String>> routes, List<String> failures, Set<String> seen)
            throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boolean cross = caller != subject;
        List<String> ids = new ArrayList<>(new TreeSet<>(subject == caller ? Set.of(caller.admin().membershipId().toString()) : forbidden));
        for (Map.Entry<String, List<String>> entry : routes.entrySet()) {
            String route = entry.getKey();
            Matcher variables = PATH_VARIABLE.matcher(route);
            List<String> names = new ArrayList<>();
            while (variables.find()) {
                names.add(variables.group(1));
            }
            List<String> candidates = new ArrayList<>();
            if (names.isEmpty()) {
                candidates.add(route);
            } else {
                for (String id : ids) {
                    candidates.add(fill(route, id));
                }
            }
            for (String path : candidates) {
                String query = query(entry.getValue(), path);
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + query))
                        .header("Authorization", "Bearer " + caller.token()).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                // the framework's own error body repeats the request path; that is the caller's input, not data
                String body = (response.body() == null ? "" : response.body()).replace("\"path\":\"" + path + "\"", "");
                if (response.statusCode() >= 500) {
                    failures.add(response.statusCode() + " GET " + path + " as " + caller.slug());
                    continue;
                }
                Matcher found = UUID_TEXT.matcher(body);
                while (found.find()) {
                    String id = found.group();
                    if (cross && forbidden.contains(id)) {
                        failures.add("GET " + path + " as " + caller.slug() + " returned " + subject.slug() + "'s id " + id
                                + " in " + response.statusCode() + " " + body.substring(0, Math.min(body.length(), 160)));
                    } else if (seen != null && forbidden.contains(id)) {
                        seen.add(id);
                    }
                }
                if (cross && (body.contains(subject.slug()) || body.contains("@" + subject.slug()))) {
                    failures.add("GET " + path + " as " + caller.slug() + " contained the name " + subject.slug());
                }
            }
        }
    }

    private static String fill(String route, String id) {
        Matcher m = PATH_VARIABLE.matcher(route);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            boolean isId = name.equals("id") || name.endsWith("Id");
            m.appendReplacement(out, Matcher.quoteReplacement(isId ? id : "x"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Required query parameters get a plausible value so the request reaches the handler: an id, a date, a month or a year. */
    private static String query(List<String> required, String path) {
        if (required.isEmpty()) {
            return "";
        }
        String id = UUID_TEXT.matcher(path).results().map(r -> r.group()).findFirst().orElse(UUID.randomUUID().toString());
        StringBuilder out = new StringBuilder("?");
        for (String name : required) {
            String lower = name.toLowerCase();
            String value = lower.contains("date") || lower.equals("from") || lower.equals("to") ? java.time.LocalDate.now().toString()
                    : lower.contains("month") ? java.time.YearMonth.now().toString()
                    : lower.contains("year") ? String.valueOf(java.time.LocalDate.now().getYear())
                    : id;
            out.append(out.length() > 1 ? "&" : "").append(name).append('=').append(value);
        }
        return out.toString();
    }

    private Map<String, List<String>> getRoutes() {
        Map<String, List<String>> routes = new java.util.TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET) || info.getPathPatternsCondition() == null) {
                continue;
            }
            String pattern = info.getPathPatternsCondition().getPatterns().iterator().next().getPatternString();
            if (pattern.startsWith("/api/") && !pattern.startsWith("/api/platform")) {
                List<String> required = new ArrayList<>();
                for (org.springframework.core.MethodParameter parameter : entry.getValue().getMethodParameters()) {
                    org.springframework.web.bind.annotation.RequestParam annotation =
                            parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
                    if (annotation != null && annotation.required() && annotation.defaultValue().equals(org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE)
                            && !annotation.value().isEmpty()) {
                        required.add(annotation.value());
                    }
                }
                routes.merge(pattern, required, (x, y) -> x);
            }
        }
        return routes;
    }

    // ── the data ───────────────────────────────────────────────────────

    private Org seed(SessionCtx superAdmin, String slug) {
        String adminEmail = "admin@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", adminEmail);
        String token = invitations.accept((String) created.get("invitationToken"), PASSWORD, null);
        SessionCtx admin = auth.resolve(token);
        UUID tenant = UUID.fromString((String) created.get("id"));

        SessionCtx hr = join(admin, "hr-" + slug, List.of("hr_admin", "employee"));
        SessionCtx emp = join(admin, "emp-" + slug, List.of("employee"));
        join(admin, "second-" + slug, List.of("employee"));

        UUID location = id(settings.saveLocation(admin, null,
                new LocationInput("Office " + slug, "Asia/Kolkata", "WB", null, null, null, null, "none", true)));
        UUID shift = id(settings.saveShift(admin, null, new ShiftInput("Shift " + slug, "09:30", "18:30", 10, 480, 240, true)));
        settings.assign(admin, List.of(hr.membershipId().toString(), emp.membershipId().toString()), location, shift, List.of(0),
                java.time.LocalDate.now().minusDays(90).toString());

        UUID casual = id(leaveAdmin.saveType(admin, null, new TypeInput("Casual " + slug, "regular", true, "none", new java.math.BigDecimal("0"),
                true, null, "lapse", "refuse", false, null, true)));
        leaveAdmin.adjust(hr, emp.membershipId(), casual, new java.math.BigDecimal("6"), null, "Opening balance " + slug);
        java.time.LocalDate monday = java.time.LocalDate.now().plusDays(40).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        leave.apply(emp, casual, monday.toString(), null, null, "Leave of " + slug);
        return new Org(tenant, slug, token, admin);
    }

    private SessionCtx join(SessionCtx admin, String name, List<String> roles) {
        String email = name + "@" + name.substring(name.lastIndexOf('-') + 1) + ".test";
        String invitation = (String) users.invite(admin, email, roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    /** Every id in every tenant table of the Organization, read as the database owner. */
    private Set<String> idsOf(Org org) throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(org.id().toString());
        String url = "jdbc:postgresql://" + host + "/" + DATABASE;
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("""
                    select t.table_name from information_schema.tables t
                    join information_schema.columns i on i.table_schema = t.table_schema and i.table_name = t.table_name and i.column_name = 'id' and i.data_type = 'uuid'
                    join information_schema.columns k on k.table_schema = t.table_schema and k.table_name = t.table_name and k.column_name = 'tenant_id'
                    where t.table_schema = 'app' and t.table_type = 'BASE TABLE'""")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet rs = st.executeQuery("select id from app." + table + " where tenant_id = '" + org.id() + "'")) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
        }
        return ids;
    }

    private static UUID id(Map<String, Object> row) {
        return UUID.fromString((String) row.get("id"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

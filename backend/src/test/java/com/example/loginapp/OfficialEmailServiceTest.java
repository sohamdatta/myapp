package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Crypto;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.OfficialEmailService;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInSettingsService;
import com.example.loginapp.core.UserService;

/**
 * Moving people from the personal address they were invited at to their company address: the Organization's email domain, HR's
 * proposal, and what the person sees. Needs PostgreSQL as described in {@link UserModuleTest}.
 */
class OfficialEmailServiceTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static Db db;
    private static PlatformService platform;
    private static MailOutbox outbox;
    private static SessionCtx superAdmin;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    @BeforeAll
    static void startDatabase() throws Exception {
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        ownerUser = env("APP_DB_OWNER_USER", "postgres");
        ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_official with (force)");
            st.execute("create database hrms_test_official");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the official email tests");
        url = "jdbc:postgresql://" + host + "/hrms_test_official";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        platform = new PlatformService(db);
        outbox = new MailOutbox(db, Crypto.withKey(Crypto.DEVELOPMENT_KEY));
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        AuthService plain = new AuthService(db, new RateLimits());
        superAdmin = plain.resolve(plain.enterConsole(plain.resolve(plain.login("root@platform.test", PASSWORD))));
    }

    private final RateLimits limits = new RateLimits();
    private final AuthService auth = new AuthService(db, limits);
    private final UserService users = new UserService(db);
    /** What the fake DNS says: the TXT records of each name. */
    private final Map<String, List<String>> dns = new java.util.concurrent.ConcurrentHashMap<>();
    private final SignInSettingsService settings = new SignInSettingsService(db, name -> dns.getOrDefault(name, List.of()));
    private final OfficialEmailService official = new OfficialEmailService(db, outbox, limits);

    /** Sets the Organization's email domain and publishes its DNS record, so the domain is verified. */
    private void verifiedDomain(SessionCtx admin, String domain) {
        SignInSettingsService.View pending = settings.setOfficialEmailDomain(admin, domain, PASSWORD);
        dns.put(pending.officialEmailRecordName(), List.of(pending.officialEmailRecordValue()));
        settings.checkEmailDomain(admin);
    }

    private String owner() {
        String slug = "official-" + SEQ.incrementAndGet();
        String email = "owner@" + slug + ".test";
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, email);
        new InvitationService(db, limits).accept((String) created.get("invitationToken"), PASSWORD, null);
        return email;
    }

    private SessionCtx insideOrganization(String email) {
        SessionCtx identity = auth.resolve(auth.login(email, PASSWORD));
        UUID tenant = UUID.fromString(((Map<?, ?>) ((List<?>) auth.destinations(identity).get("organizations")).get(0)).get("tenantId").toString());
        return auth.resolve(auth.enterOrganization(identity, tenant));
    }

    private void join(SessionCtx admin, String email, String role) {
        new InvitationService(db, limits).accept((String) users.invite(admin, email, List.of(role)).get("token"), PASSWORD, null);
    }

    private UUID membershipOf(SessionCtx admin, String email) {
        return UUID.fromString(users.users(admin).stream().filter(u -> email.equalsIgnoreCase((String) u.get("email")))
                .map(u -> (String) u.get("id")).findFirst().orElseThrow());
    }

    private static String query(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static ApiException refusal(org.junit.jupiter.api.function.Executable work) {
        return assertThrows(ApiException.class, work);
    }

    /** A domain no other test uses: a domain can be verified by one Organization only. */
    private static String freshDomain() {
        return "company" + SEQ.incrementAndGet() + ".example.com";
    }

    private String slugOf(SessionCtx admin) {
        return admin.tenantName().toLowerCase().replace(' ', '-');
    }

    private static String mails(String template, String to) throws Exception {
        return query("select count(*) from mail.outbox where template = '" + template + "' and to_email = '" + to + "'");
    }

    @Test
    void theDomainMustBeProvenWithADnsRecordBeforeAnythingCanBeProposed() throws Exception {
        String email = owner();
        SessionCtx admin = insideOrganization(email);
        String personal = "ravi.personal@" + slugOf(admin) + ".test";
        join(admin, personal, "employee");
        UUID member = membershipOf(admin, personal);
        String domain = freshDomain();

        assertEquals("official_email.no_domain", refusal(() -> official.propose(admin, member, "ravi@" + domain)).code());
        assertEquals(403, refusal(() -> settings.setOfficialEmailDomain(admin, domain, "wrong-password-here")).status());
        assertEquals(400, refusal(() -> settings.setOfficialEmailDomain(admin, "not a domain", PASSWORD)).status());
        assertEquals(400, refusal(() -> settings.setOfficialEmailDomain(admin, "gmail.com", PASSWORD)).status(), "a public mail service");
        assertEquals(400, refusal(() -> settings.setOfficialEmailDomain(admin, "co.in", PASSWORD)).status(), "a shared ending");

        SignInSettingsService.View pending = settings.setOfficialEmailDomain(admin, " " + domain.toUpperCase(), PASSWORD);
        assertEquals(domain, pending.officialEmailDomain());
        assertEquals(false, pending.officialEmailVerified());
        assertEquals("_nexvance-verify." + domain, pending.officialEmailRecordName());
        assertEquals("official_email.unverified", refusal(() -> official.propose(admin, member, "ravi@" + domain)).code());
        assertEquals("official_email.record_not_found", refusal(() -> settings.checkEmailDomain(admin)).code());
        dns.put(pending.officialEmailRecordName(), List.of("v=spf1 -all", "nexvance-verify=wrong"));
        assertEquals("official_email.record_not_found", refusal(() -> settings.checkEmailDomain(admin)).code(), "the right value, not just a record");
        dns.put(pending.officialEmailRecordName(), List.of("v=spf1 -all", pending.officialEmailRecordValue()));
        assertEquals(true, settings.checkEmailDomain(admin).officialEmailVerified());
        assertEquals(1, Integer.parseInt(query("select count(*) from app.audit_event where action = 'email_domain.verified' and target_id = '" + admin.tenantId() + "'")));

        assertEquals(400, refusal(() -> official.propose(admin, member, "ravi@evil.com")).status(), "another domain");
        assertEquals(400, refusal(() -> official.propose(admin, member, "ravi@mail." + domain)).status(), "a sub-domain is not the domain");
        assertEquals(400, refusal(() -> official.propose(admin, member, "ravi\nBcc: x@evil.com@" + domain)).status());
        assertEquals(400, refusal(() -> official.propose(admin, member, "https://evil.example/x@" + domain)).status(), "no links in the local part");
        assertEquals(400, refusal(() -> official.propose(admin, member, "not an address")).status());

        official.propose(admin, member, "Ravi@" + domain);
        assertEquals("ravi@" + domain, query("select proposed_email from app.membership where id = '" + member + "'"));
        assertEquals("1", mails("official-email", personal));
        assertEquals("ravi@" + domain, users.users(admin).stream().filter(u -> personal.equals(u.get("email"))).findFirst().orElseThrow().get("proposedEmail"));
    }

    @Test
    void aDomainCanBeVerifiedByOneOrganizationOnly() throws Exception {
        String domain = freshDomain();
        SessionCtx first = insideOrganization(owner());
        verifiedDomain(first, domain);
        SessionCtx second = insideOrganization(owner());
        SignInSettingsService.View pending = settings.setOfficialEmailDomain(second, domain, PASSWORD);
        dns.put(pending.officialEmailRecordName(), List.of(pending.officialEmailRecordValue()));
        assertEquals("official_email.domain_taken", refusal(() -> settings.checkEmailDomain(second)).code());
        assertEquals(false, settings.get(second).officialEmailVerified());
    }

    @Test
    void changingOrClearingTheDomainWithdrawsProposalsOutsideIt() throws Exception {
        SessionCtx admin = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(admin, domain);
        String personal = "a.personal@" + slugOf(admin) + ".test";
        join(admin, personal, "employee");
        UUID member = membershipOf(admin, personal);
        official.propose(admin, member, "a@" + domain);
        assertEquals("a@" + domain, query("select proposed_email from app.membership where id = '" + member + "'"));
        assertEquals(domain, settings.setOfficialEmailDomain(admin, domain, PASSWORD).officialEmailDomain(), "the same domain again changes nothing");
        assertEquals(true, settings.get(admin).officialEmailVerified(), "and it stays verified");
        assertEquals("a@" + domain, query("select proposed_email from app.membership where id = '" + member + "'"));
        String other = freshDomain();
        settings.setOfficialEmailDomain(admin, other, PASSWORD);
        assertEquals(null, query("select proposed_email from app.membership where id = '" + member + "'"));
        assertEquals(false, settings.get(admin).officialEmailVerified(), "a new domain has to be proven again");
        assertEquals(1, Integer.parseInt(query("select count(*) from app.audit_event where action = 'email_domain.set' and detail->>'previous' = '" + domain + "'")));
    }

    @Test
    void thePersonSeesTheProposalUntilTheirAddressIsTheProposedOneInAnyCase() throws Exception {
        SessionCtx admin = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(admin, domain);
        String personal = "asha.personal@" + slugOf(admin) + ".test";
        join(admin, personal, "employee");
        official.propose(admin, membershipOf(admin, personal), "asha@" + domain);

        SessionCtx asha = auth.resolve(auth.login(personal, PASSWORD));
        List<OfficialEmailService.Proposal> seen = official.mine(asha);
        assertEquals(1, seen.size());
        assertEquals("asha@" + domain, seen.get(0).email());
        assertEquals(admin.tenantName(), seen.get(0).organization());

        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute("update auth.identity set email = 'Asha@" + domain + "' where email = '" + personal + "'");
        }
        assertEquals(List.of(), official.mine(asha), "done, whatever the case: nothing left to ask");
        assertEquals(null, users.users(admin).stream().filter(u -> u.get("email").toString().equalsIgnoreCase("asha@" + domain)).findFirst().orElseThrow().get("proposedEmail"));
    }

    @Test
    void aProposalEndsAfterThirtyDaysAndWithTheMembership() throws Exception {
        SessionCtx admin = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(admin, domain);
        String personal = "old.personal@" + slugOf(admin) + ".test";
        join(admin, personal, "employee");
        UUID member = membershipOf(admin, personal);
        official.propose(admin, member, "old@" + domain);
        SessionCtx person = auth.resolve(auth.login(personal, PASSWORD));
        try (Connection c = DriverManager.getConnection(url, ownerUser, ownerPassword); Statement st = c.createStatement()) {
            st.execute("update app.membership set proposed_at = now() - interval '31 days' where id = '" + member + "'");
            assertEquals(List.of(), official.mine(person), "too old");
            st.execute("update app.membership set proposed_at = now() where id = '" + member + "'");
            assertEquals(1, official.mine(person).size());
            st.execute("update app.membership set status = 'ended', ended_at = now() where id = '" + member + "'");
        }
        assertEquals(null, query("select proposed_email from app.membership where id = '" + member + "'"), "a rehire does not bring it back");
        assertEquals(List.of(), official.mine(person));
    }

    @Test
    void aLoginSharedWithAnotherOrganizationOrAPlatformLoginIsNotMovedByAnAdministrator() throws Exception {
        SessionCtx mine = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(mine, domain);
        String otherOwner = owner();
        SessionCtx theirs = insideOrganization(otherOwner);
        String shared = "shared@" + slugOf(mine) + ".test";
        join(mine, shared, "employee");
        new InvitationService(db, limits).accept((String) users.invite(theirs, shared, List.of("employee")).get("token"), PASSWORD, null);
        assertEquals("request.refused", refusal(() -> official.propose(mine, membershipOf(mine, shared), "shared@" + domain)).code());
        assertEquals("0", query("select count(*) from app.membership where proposed_email is not null and tenant_id = '" + mine.tenantId() + "'"));
    }

    @Test
    void aPersonCannotBeFloodedWithRequests() throws Exception {
        SessionCtx admin = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(admin, domain);
        String personal = "flood.personal@" + slugOf(admin) + ".test";
        join(admin, personal, "employee");
        UUID member = membershipOf(admin, personal);
        for (int i = 0; i < 5; i++) {
            official.propose(admin, member, "flood" + i + "@" + domain);
        }
        assertEquals(429, refusal(() -> official.propose(admin, member, "flood9@" + domain)).status());
        assertEquals("5", mails("official-email", personal));
    }

    @Test
    void onlyAdministratorsWithReachCanProposeAndWithdrawingClearsIt() throws Exception {
        SessionCtx admin = insideOrganization(owner());
        String domain = freshDomain();
        verifiedDomain(admin, domain);
        String slug = slugOf(admin);
        String employee = "emp@" + slug + ".test";
        String colleague = "col@" + slug + ".test";
        join(admin, employee, "employee");
        join(admin, colleague, "employee");
        UUID target = membershipOf(admin, colleague);
        SessionCtx emp = insideOrganization(employee);
        assertEquals(403, refusal(() -> official.propose(emp, target, "col@" + domain)).status());
        assertEquals(403, refusal(() -> settings.setOfficialEmailDomain(emp, domain, PASSWORD)).status());
        assertEquals(403, refusal(() -> settings.checkEmailDomain(emp)).status());

        official.propose(admin, target, "col@" + domain);
        official.propose(admin, target, "col2@" + domain);
        assertEquals(1, Integer.parseInt(query("select count(*) from app.audit_event where action = 'user.official_email_proposed' "
                + "and detail->>'replaced' = 'col@" + domain + "'")), "an overwritten proposal is recorded");
        official.withdraw(admin, target);
        assertEquals(null, query("select proposed_email from app.membership where id = '" + target + "'"));
        assertEquals(List.of(), official.mine(auth.resolve(auth.login(colleague, PASSWORD))));
        assertEquals(1, Integer.parseInt(query("select count(*) from app.audit_event where action = 'user.official_email_withdrawn' and target_id = '" + target + "'")));

        String ended = "gone@" + slug + ".test";
        join(admin, ended, "employee");
        UUID gone = membershipOf(admin, ended);
        users.end(admin, gone);
        assertEquals(409, refusal(() -> official.propose(admin, gone, "gone@" + domain)).status(), "someone who has left");
    }

    @Test
    void aMembershipOfAnotherOrganizationIsNotFound() throws Exception {
        SessionCtx mine = insideOrganization(owner());
        verifiedDomain(mine, freshDomain());
        String otherOwner = owner();
        SessionCtx theirs = insideOrganization(otherOwner);
        UUID foreign = membershipOf(theirs, otherOwner);
        assertEquals(404, refusal(() -> official.propose(mine, foreign, "x@" + settings.get(mine).officialEmailDomain())).status());
        assertEquals(404, refusal(() -> official.withdraw(mine, foreign)).status());
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

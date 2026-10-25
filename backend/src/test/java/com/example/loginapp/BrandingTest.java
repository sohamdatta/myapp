package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Branding;
import com.example.loginapp.core.Db;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.Migrator;
import com.example.loginapp.core.PlatformService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/**
 * An Organization's logo against a real PostgreSQL: only the Org Admin sets it, it is redrawn as a
 * PNG that fits, it is served by the Organization's id, and the signed-in person is told its
 * version. Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class BrandingTest {
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static SessionCtx superAdmin;
    private static Branding branding;

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_branding with (force)");
            st.execute("create database hrms_test_branding");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the branding tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_branding";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        branding = new Branding(db);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    @Test
    void theOrgAdminSetsALogoThatIsRedrawnServedAndRemoved() throws Exception {
        String slug = "brand-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "growth", "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        UUID tenant = UUID.fromString((String) created.get("id"));
        String invitation = (String) users.invite(admin, "hr@" + slug + ".test", List.of("hr_admin", "employee")).get("token");
        SessionCtx hr = auth.resolve(invitations.accept(invitation, PASSWORD, null));

        assertEquals(0, ((Map<?, ?>) auth.me(admin).get("organization")).get("logoVersion"), "no logo: the product's brand shows");
        assertNull(branding.logo(tenant));
        assertError(403, "permission.denied", () -> branding.saveLogo(hr, image(400, 100, "png")));
        assertError(415, "logo.type", () -> branding.saveLogo(admin, "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertError(400, "logo.too_small", () -> branding.saveLogo(admin, image(10, 10, "png")));

        // A large JPEG is drawn again as a PNG within 1200 by 400.
        Map<String, Object> saved = branding.saveLogo(admin, image(2400, 600, "jpg"));
        assertEquals(1, saved.get("logoVersion"));
        assertEquals(1200, saved.get("logoWidth"));
        assertEquals(300, saved.get("logoHeight"));
        Branding.Logo logo = branding.logo(tenant);
        assertNotNull(logo);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(logo.bytes()));
        assertEquals(1200, read.getWidth());
        assertEquals((byte) 0x89, logo.bytes()[0], "stored as a PNG");
        assertEquals(1, ((Map<?, ?>) auth.me(hr).get("organization")).get("logoVersion"), "everyone in the Organization is told");

        assertEquals(2, branding.saveLogo(admin, image(300, 120, "png")).get("logoVersion"), "a new logo, a new version");
        assertError(403, "permission.denied", () -> branding.removeLogo(hr));
        assertEquals(0, branding.removeLogo(admin).get("logoVersion"));
        assertNull(branding.logo(tenant));
        assertNull(branding.logo(UUID.randomUUID()), "an unknown Organization has no logo");
    }

    private static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(14, 107, 99));
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static void assertError(int status, String code, org.junit.jupiter.api.function.Executable work) {
        ApiException e = assertThrows(ApiException.class, work);
        assertEquals(status, e.status(), e.getMessage());
        assertEquals(code, e.code(), e.getMessage());
    }

    private static PGSimpleDataSource source(String url, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

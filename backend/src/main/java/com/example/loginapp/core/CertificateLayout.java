package com.example.loginapp.core;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How an Organization's Form 130 Part B certificate looks: an optional header line, footer text, a signatory block, and
 * whether its logo (the one set under Organization settings) is shown. Every save is a new numbered version and versions are
 * never changed, so a certificate records the layout it was built with and a released one never changes when the layout does.
 * A draft is rebuilt with the layout current at the time.
 *
 * The figures and the statutory wording are not part of the layout. Text is one line each and must be something the
 * certificate's standard PDF font can print (Western European letters), and must fit on the page in that font, so a name that
 * would come out as question marks or run off the edge is refused when it is saved, not discovered on a released certificate.
 */
public final class CertificateLayout {

    private static final Charset PRINTABLE = Charset.forName("windows-1252");
    private static final int HEADER_MAX = 120;
    private static final int FOOTER_MAX = 300;
    private static final int NAME_MAX = 80;
    /** Points a line may take: the header and footer span the page's text width, the signatory block is narrower. */
    private static final float PAGE_TEXT = 499f;
    private static final float SIGNATORY = 300f;

    /** One saved version. Version 0 is the layout of an Organization that has never saved one: nothing extra, logo shown. */
    public record Layout(int version, String headerLine, String footerText, String signatoryName, String signatoryDesignation,
                         boolean showLogo, String savedAt) {
        static Layout none() {
            return new Layout(0, null, null, null, null, true, null);
        }
    }

    /** What is typed: blank fields are left out; the logo is shown unless this says otherwise. */
    public record Input(String headerLine, String footerText, String signatoryName, String signatoryDesignation, Boolean showLogo) {}

    /** The layout now in force, the versions before it (newest first), and whether the Organization has a logo to show. */
    public record View(Layout current, List<Layout> history, boolean hasLogo) {}

    private final Db db;

    public CertificateLayout(Db db) {
        this.db = db;
    }

    private static void requireAdmin(SessionCtx ctx) {
        if (!ctx.inOrganization() || ctx.isSupport() || !ctx.canAcrossOrganization("tenant.manage")) {
            throw ApiException.denied("tenant.manage");
        }
    }

    private static void requireReader(SessionCtx ctx) {
        boolean admin = ctx.inOrganization() && !ctx.isSupport() && ctx.canAcrossOrganization("tenant.manage");
        if (!admin) {
            PayrollConfig.need(ctx, "payroll.prepare");
        }
    }

    public View get(SessionCtx ctx) {
        requireReader(ctx);
        return db.inTenant(ctx.tenantId(), c -> view(c));
    }

    /** Saves what is typed as the next version. Saving what is already in force changes nothing and adds no version. */
    public View save(SessionCtx ctx, Input in) {
        requireAdmin(ctx);
        Layout wanted = checked(in);
        return db.inTenant(ctx.tenantId(), c -> {
            Sql.one(c, "select 1 from (select pg_advisory_xact_lock(hashtext(?))) locked", "certificate_layout|" + ctx.tenantId());
            Layout now = current(c);
            if (same(now, wanted)) {
                return view(c);
            }
            int next = now.version() + 1;
            Sql.update(c, "insert into app.certificate_layout (tenant_id, version, header_line, footer_text, signatory_name, signatory_designation, "
                    + "show_logo, created_by) values (?, ?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), next, wanted.headerLine(), wanted.footerText(),
                    wanted.signatoryName(), wanted.signatoryDesignation(), wanted.showLogo(), ctx.membershipId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("version", next);
            detail.put("before", Json.write(describe(now)));
            detail.put("after", Json.write(describe(wanted)));
            UserService.audit(c, ctx, "organization.certificate_layout_changed", "organization", ctx.tenantId(), detail);
            return view(c);
        });
    }

    /** A sample certificate with made-up figures in the typed layout (or the one in force), to look at before saving. Nothing is stored. */
    public DocumentService.Content preview(SessionCtx ctx, Input in) {
        requireReader(ctx);
        Layout layout = in == null ? null : checked(in);
        return db.inTenant(ctx.tenantId(), c -> {
            Layout use = layout == null ? current(c) : layout;
            Map<String, Object> settings = Sql.one(c, "select entity_name, pan, tan, address from app.payroll_settings");
            byte[] pdf = TaxCertificatePdf.render(sample(settings, ctx), true, use, TaxCertificatePdf.logo(logo(c), use));
            return new DocumentService.Content("application/pdf", "Form 130 Part B sample.pdf", pdf);
        });
    }

    // ── for building certificates ───────────────────────────────────────

    /** The layout in force: the highest version, or the plain one when none was ever saved. */
    static Layout current(Connection c) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select version, header_line, footer_text, signatory_name, signatory_designation, show_logo, created_at "
                + "from app.certificate_layout order by version desc limit 1");
        return r == null ? Layout.none() : layout(r);
    }

    /** The Organization's logo as a PNG, or null. */
    static byte[] logo(Connection c) throws SQLException {
        try (var st = c.prepareStatement("select logo from app.organization_branding")) {
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    // ── steps ───────────────────────────────────────────────────────────

    private View view(Connection c) throws SQLException {
        List<Layout> all = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select version, header_line, footer_text, signatory_name, signatory_designation, show_logo, created_at "
                + "from app.certificate_layout order by version desc limit 20")) {
            all.add(layout(r));
        }
        Layout now = all.isEmpty() ? Layout.none() : all.getFirst();
        return new View(now, all.isEmpty() ? List.of() : all.subList(1, all.size()), logo(c) != null);
    }

    private static Layout layout(Map<String, Object> r) {
        return new Layout(((Number) r.get("version")).intValue(), (String) r.get("headerLine"), (String) r.get("footerText"),
                (String) r.get("signatoryName"), (String) r.get("signatoryDesignation"), Boolean.TRUE.equals(r.get("showLogo")),
                r.get("createdAt") == null ? null : String.valueOf(r.get("createdAt")));
    }

    private static boolean same(Layout a, Layout b) {
        return java.util.Objects.equals(a.headerLine(), b.headerLine()) && java.util.Objects.equals(a.footerText(), b.footerText())
                && java.util.Objects.equals(a.signatoryName(), b.signatoryName())
                && java.util.Objects.equals(a.signatoryDesignation(), b.signatoryDesignation()) && a.showLogo() == b.showLogo();
    }

    private static Map<String, Object> describe(Layout l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headerLine", l.headerLine());
        m.put("footerText", l.footerText());
        m.put("signatoryName", l.signatoryName());
        m.put("signatoryDesignation", l.signatoryDesignation());
        m.put("showLogo", l.showLogo());
        return m;
    }

    private static Layout checked(Input in) {
        if (in == null) {
            throw ApiException.badRequest("Give the layout");
        }
        String header = text(in.headerLine(), HEADER_MAX, "The header line");
        String footer = text(in.footerText(), FOOTER_MAX, "The footer text");
        String name = text(in.signatoryName(), NAME_MAX, "The signatory's name");
        String designation = text(in.signatoryDesignation(), NAME_MAX, "The signatory's designation");
        fits(header, 8, false, PAGE_TEXT, "The header line");
        fits(name, 10, true, SIGNATORY, "The signatory's name");
        fits(designation, 9, false, SIGNATORY, "The signatory's designation");
        if (footer != null) {   // the footer wraps, but a single word wider than the page cannot
            for (String word : footer.split(" +")) {
                fits(word, 8, false, PAGE_TEXT, "A word in the footer text");
            }
        }
        return new Layout(0, header, footer, name, designation, in.showLogo() == null || in.showLogo(), null);
    }

    /** Refuses text that would run past {@code limit} points in the size and weight it is printed in (5% slack for unmeasured letters). */
    private static void fits(String text, float size, boolean bold, float limit, String what) {
        if (text != null && Pdf.width(text, size, bold) > limit * 0.95f) {
            throw ApiException.badRequest(what + " is too long to fit on the certificate. Shorten it.");
        }
    }

    /** One line the certificate's font can print: trimmed, blank left out, within its length, no control characters. */
    private static String text(String value, int max, String what) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > max) {
            throw ApiException.badRequest(what + " can be at most " + max + " characters");
        }
        CharsetEncoder enc = PRINTABLE.newEncoder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isISOControl(ch)) {
                throw ApiException.badRequest(what + " is one line of plain text");
            }
            if (!enc.canEncode(ch)) {
                throw ApiException.badRequest(what + " has a character the certificate cannot print (" + ch
                        + "). Use Western European letters, digits and punctuation.");
            }
        }
        return s;
    }

    private static Map<String, Object> sample(Map<String, Object> settings, SessionCtx ctx) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sample", true);
        b.put("taxYear", "2026-27");
        b.put("employer", settings != null && settings.get("entityName") != null ? settings.get("entityName") : ctx.tenantName());
        b.put("employerAddress", settings == null ? null : settings.get("address"));
        b.put("tan", settings != null && settings.get("tan") != null ? settings.get("tan") : "ABCD12345E");
        b.put("employerPan", settings == null ? null : settings.get("pan"));
        b.put("name", "Sample Employee");
        b.put("code", "E000");
        b.put("designation", "Analyst");
        b.put("panLast4", "234F");
        b.put("joinedOn", "2024-06-03");
        b.put("leavingOn", null);
        b.put("regime", "new");
        b.put("salaryThisEmployer", new java.math.BigDecimal("1850000.00"));
        b.put("incomeOtherEmployers", java.math.BigDecimal.ZERO);
        b.put("totalIncome", new java.math.BigDecimal("1850000.00"));
        b.put("deductions", new java.math.BigDecimal("75000.00"));
        b.put("taxableIncome", new java.math.BigDecimal("1775000.00"));
        b.put("annualTax", new java.math.BigDecimal("210000.00"));
        b.put("taxDeducted", new java.math.BigDecimal("210000.00"));
        b.put("balance", java.math.BigDecimal.ZERO);
        return b;
    }
}

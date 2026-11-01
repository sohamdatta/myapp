package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * State professional tax and labour welfare fund rules as versioned data (release R5), managed from the platform console.
 * A new version starts on the first of a month and closes the one in force that day; it is a draft until the payroll
 * consultant's sign-off is recorded, and payroll says so on every payslip that used it. A rule is entered as typed slabs and
 * amounts, never as free text, and is checked against what the payroll engine reads: slabs that start at zero, run on without
 * overlap or gap and end open, non-negative amounts, and LWF months between 1 and 12. A signed-off version cannot be edited;
 * a change is a new version.
 */
public final class PayrollRules {

    private static final BigDecimal PENNY = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");

    /** One slab: the gross it starts and ends at (end open when null), the monthly amount, and the February amount where it differs. */
    public record Slab(BigDecimal from, BigDecimal to, BigDecimal monthly, BigDecimal february) {}

    /** Professional tax on gross pay: slabs for men (and for everyone where there are none for women), and a yearly ceiling where one applies. */
    public record Pt(List<Slab> men, List<Slab> women, BigDecimal yearlyCeiling) {}

    /** Labour welfare fund: the amounts taken from the employee and paid by the employer, in the months it falls due. */
    public record Lwf(BigDecimal employee, BigDecimal employer, List<Integer> months) {}

    public record Rule(String id, String statute, String jurisdiction, String validFrom, String validTo, String status, String source,
                       String signedOffBy, String signedOffOn, Pt pt, Lwf lwf) {}

    /** What is entered: {@code pt} for a professional tax rule, {@code lwf} for a labour welfare fund rule. */
    public record Input(String statute, String jurisdiction, String validFrom, String source, Pt pt, Lwf lwf) {}

    /** The consultant's sign-off: who, and the date they gave it. */
    public record SignOff(String signedBy, String signedOn) {}

    private final Db db;
    private final java.time.Clock clock;

    public PayrollRules(Db db) {
        this(db, java.time.Clock.system(java.time.ZoneId.of("Asia/Kolkata")));
    }

    public PayrollRules(Db db, java.time.Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public List<Rule> list(SessionCtx ctx) {
        ctx.requirePlatform("super_admin", "support");
        return db.inPlatform(c -> {
            List<Rule> out = new ArrayList<>();
            for (Map<String, Object> r : Sql.query(c, "select id, statute, jurisdiction, valid_from, valid_to, status, source, signed_off_by, signed_off_on, data "
                    + "from app.payroll_rule where statute in ('pt','lwf') order by statute, jurisdiction, valid_from desc")) {
                out.add(rule(r));
            }
            return out;
        });
    }

    /** Adds a draft version from the first of a month; the version in force that day is closed there. */
    public Rule add(SessionCtx ctx, Input in) {
        ctx.requirePlatform("super_admin");
        String statute = statute(in);
        String state = state(in.jurisdiction());
        LocalDate from = firstOfMonth(in.validFrom());
        Map<String, Object> data = data(statute, in);
        String source = source(in.source());
        return db.inPlatform(c -> {
            Sql.one(c, "select 1 from (select pg_advisory_xact_lock(hashtext(?))) locked", "payroll_rule|" + statute + "|" + state);
            Map<String, Object> latest = Sql.one(c, "select id, valid_from, valid_to, status from app.payroll_rule where statute = ? and jurisdiction = ? "
                    + "order by valid_from desc limit 1", statute, state);
            if (latest != null) {
                if (from.isBefore(LocalDate.now(clock).withDayOfMonth(1))) {
                    throw new ApiException(409, "rule.back_dated", "A new version starts this month or later. Months already past keep the rule they were paid under.");
                }
                if (!LocalDate.parse(String.valueOf(latest.get("validFrom"))).isBefore(from)) {
                    throw new ApiException(409, "rule.version_order", "A version from " + latest.get("validFrom") + " already exists. A new one must start after it.");
                }
                if ("draft".equals(latest.get("status"))) {
                    throw new ApiException(409, "rule.draft_exists", "The latest version is still a draft. Edit it, or have it signed off, before adding another.");
                }
                if (latest.get("validTo") == null) {
                    Sql.update(c, "update app.payroll_rule set valid_to = ? where id = ?::uuid", from, String.valueOf(latest.get("id")));
                } else if (LocalDate.parse(String.valueOf(latest.get("validTo"))).isAfter(from)) {
                    throw new ApiException(409, "rule.version_order", "The previous version runs to " + latest.get("validTo") + ", after that start date");
                }
            }
            UUID id = AuthService.uuid(Sql.one(c, "insert into app.payroll_rule (statute, jurisdiction, valid_from, data, source) values (?, ?, ?, ?::jsonb, ?) returning id",
                    statute, state, from, Json.write(data), source).get("id"));
            audit(c, ctx, "payroll_rule.drafted", id, Map.of("statute", statute, "state", state, "validFrom", from.toString(), "data", Json.write(data), "source", source));
            return rule(row(c, id));
        });
    }

    /** Changes the figures and source of a draft. A signed-off version is never edited; add a new one. */
    public Rule edit(SessionCtx ctx, UUID id, Input in) {
        ctx.requirePlatform("super_admin");
        String source = source(in.source());
        return db.inPlatform(c -> {
            Map<String, Object> r = row(c, id);
            if (!"draft".equals(r.get("status"))) {
                throw new ApiException(409, "rule.signed_off", "A signed-off version is not edited. Add a new version instead.");
            }
            Map<String, Object> data = data((String) r.get("statute"), new Input((String) r.get("statute"), (String) r.get("jurisdiction"),
                    String.valueOf(r.get("validFrom")), in.source(), in.pt(), in.lwf()));
            if (Sql.update(c, "update app.payroll_rule set data = ?::jsonb, source = ? where id = ?::uuid and status = 'draft'", Json.write(data), source, id.toString()) == 0) {
                throw new ApiException(409, "rule.signed_off", "This version was signed off while you were editing it");
            }
            // Payroll runs freeze their own copy of a rule, but the audit trail keeps what the figures were and what they became.
            audit(c, ctx, "payroll_rule.edited", id, Map.of("statute", String.valueOf(r.get("statute")), "state", String.valueOf(r.get("jurisdiction")),
                    "before", Json.write(Json.parse(String.valueOf(r.get("data")))), "beforeSource", String.valueOf(r.get("source")), "after", Json.write(data), "afterSource", source));
            return rule(row(c, id));
        });
    }

    /** Records the payroll consultant's sign-off of a draft. */
    public Rule signOff(SessionCtx ctx, UUID id, SignOff in) {
        ctx.requirePlatform("super_admin");
        String by = in == null || in.signedBy() == null ? "" : in.signedBy().trim();
        if (by.length() < 3 || by.length() > 120) {
            throw ApiException.badRequest("Give the name of the consultant who signed this off");
        }
        LocalDate on;
        try {
            on = LocalDate.parse(in.signedOn());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the date of the sign-off as yyyy-mm-dd");
        }
        if (on.isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("The sign-off date cannot be in the future");
        }
        return db.inPlatform(c -> {
            Map<String, Object> r = row(c, id);
            if (!"draft".equals(r.get("status"))) {
                throw new ApiException(409, "rule.signed_off", "This version is already signed off");
            }
            if (Sql.update(c, "update app.payroll_rule set status = 'signed_off', signed_off_by = ?, signed_off_on = ? where id = ?::uuid and status = 'draft'", by, on, id.toString()) == 0) {
                throw new ApiException(409, "rule.signed_off", "This version is already signed off");
            }
            audit(c, ctx, "payroll_rule.signed_off", id, Map.of("statute", String.valueOf(r.get("statute")), "state", String.valueOf(r.get("jurisdiction")),
                    "signedBy", by, "signedOn", on.toString(), "data", Json.write(Json.parse(String.valueOf(r.get("data"))))));
            return rule(row(c, id));
        });
    }

    // ── checking what is entered ────────────────────────────────────────

    private static String statute(Input in) {
        if (in == null || in.statute() == null || !Set.of("pt", "lwf").contains(in.statute())) {
            throw ApiException.badRequest("The rule is for professional tax (pt) or labour welfare fund (lwf)");
        }
        return in.statute();
    }

    private static String state(String code) {
        if (code == null || !IdChecks.STATES.containsKey(code)) {
            throw ApiException.badRequest("Choose a state");
        }
        return code;
    }

    private static LocalDate firstOfMonth(String value) {
        LocalDate d;
        try {
            d = LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Give the start date as yyyy-mm-dd");
        }
        if (d.getDayOfMonth() != 1) {
            throw ApiException.badRequest("A rule starts on the first of a month");
        }
        return d;
    }

    private static String source(String value) {
        String s = value == null ? "" : value.trim();
        if (s.length() < 5 || s.length() > 300) {
            throw ApiException.badRequest("Say where the figures come from (5 to 300 characters)");
        }
        return s;
    }

    /** The JSON the engine reads, after checking it. */
    static Map<String, Object> data(String statute, Input in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (statute.equals("pt")) {
            if (in.pt() == null || in.lwf() != null) {
                throw ApiException.badRequest("A professional tax rule carries slabs, and nothing for labour welfare fund");
            }
            out.put("basis", "gross");
            out.put("men", slabs(in.pt().men(), "slabs", true));
            if (in.pt().women() != null && !in.pt().women().isEmpty()) {
                out.put("women", slabs(in.pt().women(), "women's slabs", false));
            }
            if (in.pt().yearlyCeiling() != null) {
                amount(in.pt().yearlyCeiling(), "The yearly ceiling");
                if (in.pt().yearlyCeiling().signum() == 0) {
                    throw ApiException.badRequest("The yearly ceiling must be more than 0, or left out");
                }
                out.put("yearlyCeiling", in.pt().yearlyCeiling());
            }
        } else {
            if (in.lwf() == null || in.pt() != null) {
                throw ApiException.badRequest("A labour welfare fund rule carries amounts and months, and nothing for professional tax");
            }
            Lwf l = in.lwf();
            out.put("employee", amount(l.employee(), "The employee amount"));
            out.put("employer", amount(l.employer(), "The employer amount"));
            if (l.months() == null || l.months().isEmpty() || new HashSet<>(l.months()).size() != l.months().size()
                    || l.months().stream().anyMatch(m -> m == null || m < 1 || m > 12)) {
                throw ApiException.badRequest("Give the months it falls due, each once, as numbers from 1 to 12");
            }
            out.put("months", l.months());
        }
        return out;
    }

    /** Slabs that start at 0, run on with no overlap and at most a paisa between them, and end open: the engine matches by range. */
    private static List<Map<String, Object>> slabs(List<Slab> slabs, String what, boolean required) {
        if (slabs == null || slabs.isEmpty()) {
            if (required) {
                throw ApiException.badRequest("Give the " + what);
            }
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Slab previous = null;
        for (int i = 0; i < slabs.size(); i++) {
            Slab s = slabs.get(i);
            if (s == null || s.from() == null || s.from().signum() < 0) {
                throw ApiException.badRequest("Each of the " + what + " needs a start of 0 or more");
            }
            paise(s.from(), "A slab start");
            if (s.to() != null) {
                paise(s.to(), "A slab end");
            }
            if (previous == null && s.from().signum() != 0) {
                throw ApiException.badRequest("The first of the " + what + " must start at 0");
            }
            if (previous != null) {
                if (previous.to() == null) {
                    throw ApiException.badRequest("Only the last of the " + what + " can be open-ended");
                }
                BigDecimal gap = s.from().subtract(previous.to());
                if (gap.signum() <= 0 || gap.compareTo(PENNY) > 0) {
                    throw ApiException.badRequest("The " + what + " must follow on without overlap or gap: " + previous.to().toPlainString() + " then " + s.from().toPlainString());
                }
            }
            if (s.to() != null && s.to().compareTo(s.from()) < 0) {
                throw ApiException.badRequest("A slab cannot end before it starts (" + s.from().toPlainString() + ")");
            }
            if (i == slabs.size() - 1 && s.to() != null) {
                throw ApiException.badRequest("The last of the " + what + " must be open-ended, or pay above it matches nothing");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", s.from());
            m.put("to", s.to());
            m.put("monthly", amount(s.monthly(), "The monthly amount"));
            if (s.february() != null) {
                m.put("february", amount(s.february(), "The February amount"));
            }
            out.add(m);
            previous = s;
        }
        return out;
    }

    private static BigDecimal amount(BigDecimal v, String what) {
        if (v == null || v.signum() < 0 || v.compareTo(MAX_AMOUNT) > 0) {
            throw ApiException.badRequest(what + " must be between 0 and " + MAX_AMOUNT.toPlainString());
        }
        return paise(v, what);
    }

    /** Pay is in rupees and paise, so a figure with more decimals could fall between two slabs. */
    private static BigDecimal paise(BigDecimal v, String what) {
        if (v.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest(what + " can have at most 2 decimal places");
        }
        return v;
    }

    // ── reading back ────────────────────────────────────────────────────

    private static Map<String, Object> row(Connection c, UUID id) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select id, statute, jurisdiction, valid_from, valid_to, status, source, signed_off_by, signed_off_on, data "
                + "from app.payroll_rule where id = ?::uuid and statute in ('pt','lwf')", id.toString());
        if (r == null) {
            throw ApiException.notFound("The rule");
        }
        return r;
    }

    private static void audit(Connection c, SessionCtx ctx, String action, UUID id, Map<String, ?> detail) throws SQLException {
        Sql.update(c, "insert into platform.platform_audit (platform_user_id, action, target_type, target_id, detail) values (?, ?, 'payroll_rule', ?, ?::jsonb)",
                ctx.platformUserId(), action, id, Json.object(detail));
    }

    @SuppressWarnings("unchecked")
    static Rule rule(Map<String, Object> r) {
        Map<String, Object> d = (Map<String, Object>) Json.parse(String.valueOf(r.get("data")));
        boolean pt = "pt".equals(r.get("statute"));
        return new Rule(String.valueOf(r.get("id")), (String) r.get("statute"), (String) r.get("jurisdiction"), String.valueOf(r.get("validFrom")),
                r.get("validTo") == null ? null : String.valueOf(r.get("validTo")), (String) r.get("status"), (String) r.get("source"),
                (String) r.get("signedOffBy"), r.get("signedOffOn") == null ? null : String.valueOf(r.get("signedOffOn")),
                pt ? new Pt(slabsOf(d.get("men")), slabsOf(d.get("women")), d.get("yearlyCeiling") == null ? null : num(d.get("yearlyCeiling"))) : null,
                pt ? null : new Lwf(num(d.get("employee")), num(d.get("employer")), months(d.get("months"))));
    }

    private static List<Slab> slabsOf(Object o) {
        List<Slab> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                Map<?, ?> m = (Map<?, ?>) x;
                out.add(new Slab(num(m.get("from")), m.get("to") == null ? null : num(m.get("to")), num(m.get("monthly")),
                        m.get("february") == null ? null : num(m.get("february"))));
            }
        }
        return out;
    }

    private static List<Integer> months(Object o) {
        List<Integer> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                out.add(new BigDecimal(String.valueOf(x)).intValue());
            }
        }
        return out;
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }
}

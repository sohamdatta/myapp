package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Income tax declarations (Form 124) and their proofs.
 *
 * HR opens a window for each tax year: when employees may declare, when they may send proofs, and
 * the first payroll month that counts verified amounts only. An employee declares claims, certifies
 * them by submitting, and later uploads proofs for each; HR accepts an amount (no more than declared)
 * or returns the proof with a reason. Payroll reads only a submitted declaration: the declared amounts
 * until the cut-over month, the accepted ones from it. Only the old regime takes these claims.
 */
public final class TaxDeclarations {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-[0-9]{2}$");
    private static final Set<String> HEADS = Set.of("rent", "home_loan", "sec80c", "nps", "health_self", "health_parents", "edu_loan");
    private static final Set<String> KINDS = Set.of("life_insurance", "ppf", "elss", "nsc", "ssy", "tuition", "home_loan_principal",
            "tax_saver_fd", "epf_voluntary", "other");
    private static final int MAX_ITEMS = 40;
    private static final int MAX_PROOFS = 10;

    public record ItemInput(String head, String kind, BigDecimal amount, Map<String, Object> details) {}

    public record WindowInput(String taxYear, String declareFrom, String declareUntil, String proofsFrom, String proofsUntil,
                              String verifiedFrom) {}

    public record AcceptInput(BigDecimal amount) {}

    public record NoteInput(String note) {}

    private final Db db;
    private final DocumentService documents;
    private final Clock clock;

    public TaxDeclarations(Db db, DocumentService documents) {
        this(db, documents, Clock.systemUTC());
    }

    public TaxDeclarations(Db db, DocumentService documents, Clock clock) {
        this.db = db;
        this.documents = documents;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── the tax year ────────────────────────────────────────────────────

    /** The tax year a date falls in, as 2026-27. */
    static String taxYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    static LocalDate yearStart(String taxYear) {
        return LocalDate.of(Integer.parseInt(taxYear.substring(0, 4)), 4, 1);
    }

    private static String year(String value, LocalDate today) {
        if (value == null || value.isBlank()) {
            return taxYear(today);
        }
        if (!value.matches("^[0-9]{4}-[0-9]{2}$") || (Integer.parseInt(value.substring(0, 4)) + 1) % 100 != Integer.parseInt(value.substring(5))) {
            throw ApiException.badRequest("The tax year must look like 2026-27");
        }
        return value;
    }

    // ── what payroll reads ──────────────────────────────────────────────

    /**
     * The claims payroll counts for a month: empty unless the employee submitted a declaration for that
     * tax year. Declared amounts until the window's cut-over month, accepted amounts from it.
     */
    static Map<String, Object> forPayroll(Connection c, UUID employmentId, LocalDate period) throws SQLException {
        String ty = taxYear(period);
        Map<String, Object> d = Sql.one(c, "select id from app.tax_declaration where employment_id = ? and tax_year = ? and submitted_at is not null",
                employmentId, ty);
        if (d == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> w = Sql.one(c, "select verified_from from app.tax_window where tax_year = ?", ty);
        boolean verified = w != null && w.get("verifiedFrom") != null && !period.isBefore(LocalDate.parse((String) w.get("verifiedFrom")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basis", verified ? "verified" : "declared");
        List<Map<String, Object>> rent = new ArrayList<>();
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Map<String, Object> i : Sql.query(c, "select head, amount, accepted_amount, details from app.tax_declaration_item "
                + "where declaration_id = ?::uuid order by position, created_at", d.get("id"))) {
            BigDecimal amount = verified ? (i.get("acceptedAmount") == null ? BigDecimal.ZERO : (BigDecimal) i.get("acceptedAmount"))
                    : (BigDecimal) i.get("amount");
            if (amount.signum() == 0) {
                continue;
            }
            Map<String, Object> details = PayrollRuns.parse(i.get("details"));
            String head = (String) i.get("head");
            switch (head) {
                case "rent" -> {
                    Map<String, Object> span = new LinkedHashMap<>();
                    span.put("monthly", amount);
                    span.put("months", rentMonths(details, ty));
                    span.put("city", details.get("city"));
                    rent.add(span);
                }
                case "health_self", "health_parents" -> {
                    String key = head.equals("health_self") ? "healthSelf" : "healthParents";
                    sums.merge(key, amount, BigDecimal::add);
                    if (Boolean.TRUE.equals(details.get("senior"))) {
                        out.put(key + "Senior", true);
                    }
                }
                default -> sums.merge(switch (head) {
                    case "home_loan" -> "homeLoan";
                    case "edu_loan" -> "eduLoan";
                    default -> head;
                }, amount, BigDecimal::add);
            }
        }
        out.putAll(sums);
        if (!rent.isEmpty()) {
            out.put("rent", rent);
        }
        return out.size() == 1 ? new LinkedHashMap<>() : out;
    }

    /** Months of rent within the tax year, from the span the employee gave. */
    static int rentMonths(Map<String, Object> details, String taxYear) {
        LocalDate start = yearStart(taxYear);
        YearMonth first = YearMonth.from(start);
        YearMonth last = first.plusMonths(11);
        YearMonth from = YearMonth.parse(String.valueOf(details.get("from")));
        YearMonth to = YearMonth.parse(String.valueOf(details.get("to")));
        if (from.isBefore(first)) {
            from = first;
        }
        if (to.isAfter(last)) {
            to = last;
        }
        return to.isBefore(from) ? 0 : (int) (from.until(to, java.time.temporal.ChronoUnit.MONTHS) + 1);
    }

    // ── the employee's own ──────────────────────────────────────────────

    /** The signed-in employee's declaration for a tax year (this one unless named), with the window and limits. */
    public Map<String, Object> mine(SessionCtx ctx, String taxYear) {
        LocalDate today = today();
        String ty = year(taxYear, today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    public Map<String, Object> addItem(SessionCtx ctx, ItemInput in) {
        return saveItem(ctx, null, in);
    }

    public Map<String, Object> updateItem(SessionCtx ctx, UUID itemId, ItemInput in) {
        return saveItem(ctx, itemId, in);
    }

    private Map<String, Object> saveItem(SessionCtx ctx, UUID itemId, ItemInput in) {
        LocalDate today = today();
        String ty = taxYear(today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            requireDeclareOpen(c, ty, today);
            Clean clean = clean(in, ty, oldRules(c, ty));
            UUID declaration = declaration(c, ctx, me.employmentId(), ty);
            if (itemId == null) {
                if (((Number) Sql.one(c, "select count(*) as n from app.tax_declaration_item where declaration_id = ?", declaration).get("n"))
                        .intValue() >= MAX_ITEMS) {
                    throw new ApiException(409, "tax.too_many", "A declaration holds at most " + MAX_ITEMS + " claims");
                }
                Sql.update(c, "insert into app.tax_declaration_item (tenant_id, declaration_id, head, kind, amount, details, position) "
                        + "values (?, ?, ?, ?, ?, ?::jsonb, (select coalesce(max(position), 0) + 1 from app.tax_declaration_item where declaration_id = ?))",
                        ctx.tenantId(), declaration, clean.head(), clean.kind(), clean.amount(), Json.write(clean.details()), declaration);
            } else {
                Map<String, Object> item = ownItem(c, itemId, declaration);
                if (!item.get("head").equals(clean.head())) {
                    throw ApiException.badRequest("A claim keeps its kind; remove it and add another instead");
                }
                boolean files = Sql.one(c, "select 1 from app.tax_proof where item_id = ? limit 1", itemId) != null;
                // A changed claim is checked again: its earlier decision was about the earlier amount.
                Sql.update(c, "update app.tax_declaration_item set kind = ?, amount = ?, details = ?::jsonb, updated_at = now(), "
                        + "proof_status = case when proof_status in ('accepted','rejected') then ? else proof_status end, "
                        + "accepted_amount = null, review_note = null, reviewed_by = null, reviewed_at = null where id = ?",
                        clean.kind(), clean.amount(), Json.write(clean.details()), files ? "submitted" : "none", itemId);
            }
            UserService.audit(c, ctx, itemId == null ? "tax.claim_added" : "tax.claim_changed", "employment", me.employmentId(),
                    Map.of("taxYear", ty, "claim", label(clean.head(), clean.kind()), "amount", clean.amount()));
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    public Map<String, Object> removeItem(SessionCtx ctx, UUID itemId) {
        LocalDate today = today();
        String ty = taxYear(today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            requireDeclareOpen(c, ty, today);
            UUID declaration = declaration(c, ctx, me.employmentId(), ty);
            Map<String, Object> item = ownItem(c, itemId, declaration);
            if ("accepted".equals(item.get("proofStatus"))) {
                throw new ApiException(409, "tax.accepted", "HR has accepted the proof for this claim. Ask HR before removing it");
            }
            Sql.update(c, "delete from app.tax_proof where item_id = ?", itemId);
            Sql.update(c, "delete from app.tax_declaration_item where id = ?", itemId);
            UserService.audit(c, ctx, "tax.claim_removed", "employment", me.employmentId(),
                    Map.of("taxYear", ty, "claim", label((String) item.get("head"), (String) item.get("kind"))));
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    /** The employee certifies the declaration; from now on payroll counts it. Changes afterwards count as soon as they are saved. */
    public Map<String, Object> submit(SessionCtx ctx) {
        LocalDate today = today();
        String ty = taxYear(today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            requireDeclareOpen(c, ty, today);
            UUID declaration = declaration(c, ctx, me.employmentId(), ty);
            Sql.update(c, "update app.tax_declaration set submitted_at = now(), submitted_by = ? where id = ?", ctx.membershipId(), declaration);
            Map<String, Object> n = Sql.one(c, "select count(*) as n from app.tax_declaration_item where declaration_id = ?", declaration);
            UserService.audit(c, ctx, "tax.declaration_submitted", "employment", me.employmentId(),
                    Map.of("taxYear", ty, "claims", n.get("n")));
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    public Map<String, Object> addProof(SessionCtx ctx, UUID itemId, String fileName, byte[] bytes) {
        FileSniffer.Cleaned file = FileSniffer.clean(bytes, fileName);
        LocalDate today = today();
        String ty = taxYear(today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            Map<String, Object> w = window(c, ty);
            if (!open(w, "proofsFrom", "proofsUntil", today)) {
                throw new ApiException(409, "tax.proofs_closed", w == null || w.get("proofsFrom") == null
                        ? "Proofs for " + ty + " are not being collected yet" : "Proofs for " + ty + " are collected from "
                        + w.get("proofsFrom") + " to " + w.get("proofsUntil"));
            }
            Map<String, Object> d = Sql.one(c, "select id, submitted_at from app.tax_declaration where employment_id = ? and tax_year = ?",
                    me.employmentId(), ty);
            if (d == null || d.get("submittedAt") == null) {
                throw new ApiException(409, "tax.not_submitted", "Submit your declaration first; proofs back up what you declared");
            }
            Map<String, Object> item = ownItem(c, itemId, AuthService.uuid(d.get("id")));
            if ("accepted".equals(item.get("proofStatus"))) {
                throw new ApiException(409, "tax.accepted", "This claim's proof is accepted already");
            }
            if (((Number) Sql.one(c, "select count(*) as n from app.tax_proof where item_id = ?", itemId).get("n")).intValue() >= MAX_PROOFS) {
                throw new ApiException(409, "tax.too_many", "A claim takes at most " + MAX_PROOFS + " files");
            }
            UUID document = documents.insert(c, ctx, ctx.tenantId(), me.personId(), null, null, file);
            Sql.update(c, "insert into app.tax_proof (item_id, tenant_id, document_id) values (?, ?, ?)", itemId, ctx.tenantId(), document);
            Sql.update(c, "update app.tax_declaration_item set proof_status = 'submitted', review_note = null, reviewed_by = null, "
                    + "reviewed_at = null, updated_at = now() where id = ?", itemId);
            UserService.audit(c, ctx, "tax.proof_added", "employment", me.employmentId(),
                    Map.of("taxYear", ty, "claim", label((String) item.get("head"), (String) item.get("kind")), "file", file.fileName()));
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    public Map<String, Object> removeProof(SessionCtx ctx, UUID itemId, UUID documentId) {
        LocalDate today = today();
        String ty = taxYear(today);
        return db.inTenant(ctx.tenantId(), c -> {
            Me me = me(c, ctx);
            Map<String, Object> d = Sql.one(c, "select id from app.tax_declaration where employment_id = ? and tax_year = ?", me.employmentId(), ty);
            if (d == null) {
                throw ApiException.notFound("The claim");
            }
            Map<String, Object> item = ownItem(c, itemId, AuthService.uuid(d.get("id")));
            if ("accepted".equals(item.get("proofStatus"))) {
                throw new ApiException(409, "tax.accepted", "This claim's proof is accepted already");
            }
            if (Sql.update(c, "delete from app.tax_proof where item_id = ? and document_id = ?", itemId, documentId) == 0) {
                throw ApiException.notFound("The file");
            }
            Sql.update(c, "update app.document set status = 'superseded' where id = ?", documentId);
            if (Sql.one(c, "select 1 from app.tax_proof where item_id = ? limit 1", itemId) == null) {
                Sql.update(c, "update app.tax_declaration_item set proof_status = case when proof_status = 'rejected' then 'rejected' else 'none' end, "
                        + "updated_at = now() where id = ?", itemId);
            }
            return view(c, me.employmentId(), ty, today, false);
        });
    }

    /** A proof file, for the employee it belongs to or for someone who reviews declarations. */
    public DocumentService.Content proof(SessionCtx ctx, UUID documentId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> p = Sql.one(c, "select e.membership_id from app.tax_proof p join app.tax_declaration_item i on i.id = p.item_id "
                    + "join app.tax_declaration d on d.id = i.declaration_id join app.employment e on e.id = d.employment_id "
                    + "where p.document_id = ?", documentId);
            boolean own = p != null && ctx.membershipId() != null && ctx.membershipId().toString().equals(String.valueOf(p.get("membershipId")));
            if (p == null || (!own && !canRead(ctx))) {
                throw ApiException.notFound("The file");
            }
            return documents.readFile(c, ctx, documentId);
        });
    }

    // ── HR ──────────────────────────────────────────────────────────────

    /** Everyone employed now, with where their declaration stands, and the window. */
    public Map<String, Object> overview(SessionCtx ctx, String taxYear) {
        needRead(ctx);
        LocalDate today = today();
        String ty = year(taxYear, today);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taxYear", ty);
            out.put("window", window(c, ty));
            putOpen(out, window(c, ty), today);
            out.put("canManage", canManage(ctx));
            List<Map<String, Object>> people = Sql.query(c, "select e.id as employment_id, coalesce(p.preferred_name, p.legal_name) as name, "
                    + "e.employee_code, coalesce(pp.tax_regime, 'new') as regime, d.submitted_at, "
                    + "(select count(*) from app.tax_declaration_item i where i.declaration_id = d.id) as claims, "
                    + "(select count(*) from app.tax_declaration_item i where i.declaration_id = d.id and i.proof_status = 'submitted') as to_check, "
                    + "(select count(*) from app.tax_declaration_item i where i.declaration_id = d.id and i.proof_status = 'accepted') as accepted, "
                    + "(select count(*) from app.tax_declaration_item i where i.declaration_id = d.id and i.proof_status = 'rejected') as returned "
                    + "from app.employment e join app.person p on p.id = e.person_id "
                    + "left join app.pay_profile pp on pp.employment_id = e.id "
                    + "left join app.tax_declaration d on d.employment_id = e.id and d.tax_year = ? "
                    + "where e.status = 'active' order by (select count(*) from app.tax_declaration_item i where i.declaration_id = d.id "
                    + "and i.proof_status = 'submitted') desc, lower(coalesce(p.preferred_name, p.legal_name))", ty);
            for (Map<String, Object> r : people) {
                long claims = ((Number) r.get("claims")).longValue();
                r.put("state", r.get("submittedAt") == null ? (claims > 0 ? "draft" : "not_started")
                        : ((Number) r.get("toCheck")).longValue() > 0 ? "to_check" : "submitted");
            }
            out.put("people", people);
            Map<String, Object> counts = new LinkedHashMap<>();
            for (String s : List.of("not_started", "draft", "submitted", "to_check")) {
                counts.put(s, people.stream().filter(r -> s.equals(r.get("state"))).count());
            }
            out.put("counts", counts);
            return out;
        });
    }

    /** One employee's declaration, for HR. */
    public Map<String, Object> employee(SessionCtx ctx, UUID employmentId, String taxYear) {
        needRead(ctx);
        LocalDate today = today();
        String ty = year(taxYear, today);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = Sql.one(c, "select e.membership_id, coalesce(p.preferred_name, p.legal_name) as name, e.employee_code "
                    + "from app.employment e join app.person p on p.id = e.person_id where e.id = ?", employmentId);
            if (e == null) {
                throw ApiException.notFound("The employee");
            }
            Map<String, Object> out = view(c, employmentId, ty, today, true);
            out.put("name", e.get("name"));
            out.put("employeeCode", e.get("employeeCode"));
            out.put("canReview", canManage(ctx) && !own(ctx, e));
            out.put("own", own(ctx, e));
            return out;
        });
    }

    public Map<String, Object> saveWindow(SessionCtx ctx, WindowInput in) {
        needManage(ctx);
        LocalDate today = today();
        String ty = year(in.taxYear(), today);
        LocalDate declareFrom = date(in.declareFrom(), "When declarations open");
        LocalDate declareUntil = date(in.declareUntil(), "When declarations close");
        LocalDate proofsFrom = optionalDate(in.proofsFrom(), "When proofs open");
        LocalDate proofsUntil = optionalDate(in.proofsUntil(), "When proofs close");
        LocalDate verifiedFrom = null;
        if (in.verifiedFrom() != null && !in.verifiedFrom().isBlank()) {
            if (!MONTH.matcher(in.verifiedFrom()).matches()) {
                throw ApiException.badRequest("The month from which only verified amounts count must look like 2027-02");
            }
            verifiedFrom = LocalDate.parse(in.verifiedFrom() + "-01");
        }
        LocalDate start = yearStart(ty);
        LocalDate end = start.plusYears(1).minusDays(1);
        if (declareUntil.isBefore(declareFrom)) {
            throw ApiException.badRequest("Declarations must close on or after the day they open");
        }
        if ((proofsFrom == null) != (proofsUntil == null)) {
            throw ApiException.badRequest("Give both proof dates, or neither");
        }
        if (proofsFrom != null && proofsUntil.isBefore(proofsFrom)) {
            throw ApiException.badRequest("Proofs must close on or after the day they open");
        }
        if (declareUntil.isAfter(end) || (proofsUntil != null && proofsUntil.isAfter(end.plusMonths(1)))) {
            throw ApiException.badRequest("The window must fall within the tax year " + ty + " (proofs may run into April)");
        }
        if (verifiedFrom != null && (verifiedFrom.isBefore(start) || verifiedFrom.isAfter(end))) {
            throw ApiException.badRequest("The month from which only verified amounts count must be within " + ty);
        }
        LocalDate verified = verifiedFrom;
        return db.inTenant(ctx.tenantId(), c -> {
            boolean first = Sql.one(c, "select 1 from app.tax_window where tax_year = ?", ty) == null;
            Sql.update(c, "insert into app.tax_window (tenant_id, tax_year, declare_from, declare_until, proofs_from, proofs_until, verified_from, "
                    + "updated_by) values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, tax_year) do update set declare_from = excluded.declare_from, "
                    + "declare_until = excluded.declare_until, proofs_from = excluded.proofs_from, proofs_until = excluded.proofs_until, "
                    + "verified_from = excluded.verified_from, updated_by = excluded.updated_by, updated_at = now()",
                    ctx.tenantId(), ty, declareFrom, declareUntil, proofsFrom, proofsUntil, verified, ctx.membershipId());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("taxYear", ty);
            detail.put("declare", declareFrom + " to " + declareUntil);
            detail.put("proofs", proofsFrom == null ? "not set" : proofsFrom + " to " + proofsUntil);
            detail.put("verifiedFrom", verified == null ? "not set" : verified.toString().substring(0, 7));
            UserService.audit(c, ctx, "tax.window_saved", "tenant", ctx.tenantId(), detail);
            if (first) {
                // Everyone employed hears once that they can declare.
                for (Map<String, Object> m : Sql.query(c, "select e.membership_id, coalesce(p.preferred_name, split_part(p.legal_name, ' ', 1)) as first "
                        + "from app.employment e join app.person p on p.id = e.person_id where e.status = 'active' and e.membership_id is not null")) {
                    Map<String, String> v = new LinkedHashMap<>();
                    v.put("person.first_name", String.valueOf(m.get("first")));
                    v.put("tax_year", ty);
                    v.put("until", declareUntil.toString());
                    Notifications.send(c, ctx.tenantId(), AuthService.uuid(m.get("membershipId")), "tax_window_open", v, "my-pay");
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("window", window(c, ty));
            putOpen(out, window(c, ty), today);
            return out;
        });
    }

    /** HR accepts the proof of a claim, at the declared amount or less. */
    public Map<String, Object> accept(SessionCtx ctx, UUID itemId, BigDecimal amount) {
        needManage(ctx);
        if (amount == null || amount.signum() < 0 || amount.scale() > 2) {
            throw ApiException.badRequest("Give the amount the proofs support, in rupees");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> i = reviewable(c, ctx, itemId);
            if (amount.compareTo((BigDecimal) i.get("amount")) > 0) {
                throw ApiException.badRequest("No more than declared: " + PayrollEngine.rupees((BigDecimal) i.get("amount"))
                        + ". The employee changes the claim if it should be higher");
            }
            Sql.update(c, "update app.tax_declaration_item set proof_status = 'accepted', accepted_amount = ?, review_note = null, "
                    + "reviewed_by = ?, reviewed_at = now() where id = ?", amount, ctx.membershipId(), itemId);
            UUID employment = AuthService.uuid(i.get("employmentId"));
            UserService.audit(c, ctx, "tax.proof_accepted", "employment", employment,
                    Map.of("taxYear", i.get("taxYear"), "claim", label((String) i.get("head"), (String) i.get("kind")), "amount", amount));
            Map<String, Object> out = view(c, employment, (String) i.get("taxYear"), today(), true);
            out.put("canReview", true);
            return out;
        });
    }

    /** HR returns a proof with what is wrong; the employee hears of it and may send another. */
    public Map<String, Object> returnProof(SessionCtx ctx, UUID itemId, String note) {
        needManage(ctx);
        String n = note == null ? "" : note.trim();
        if (n.length() < 3 || n.length() > 400) {
            throw ApiException.badRequest("Say what is wrong with the proof, in a few words");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> i = reviewable(c, ctx, itemId);
            Sql.update(c, "update app.tax_declaration_item set proof_status = 'rejected', accepted_amount = null, review_note = ?, "
                    + "reviewed_by = ?, reviewed_at = now() where id = ?", n, ctx.membershipId(), itemId);
            UUID employment = AuthService.uuid(i.get("employmentId"));
            String claim = label((String) i.get("head"), (String) i.get("kind"));
            UserService.audit(c, ctx, "tax.proof_returned", "employment", employment, Map.of("taxYear", i.get("taxYear"), "claim", claim, "note", n));
            Map<String, String> v = new LinkedHashMap<>();
            v.put("person.first_name", String.valueOf(i.get("first")));
            v.put("claim", claim.toLowerCase());
            v.put("note", n);
            Notifications.send(c, ctx.tenantId(), i.get("membershipId") == null ? null : AuthService.uuid(i.get("membershipId")),
                    "tax_proof_returned", v, "my-pay");
            Map<String, Object> out = view(c, employment, (String) i.get("taxYear"), today(), true);
            out.put("canReview", true);
            return out;
        });
    }

    private static Map<String, Object> reviewable(Connection c, SessionCtx ctx, UUID itemId) throws SQLException {
        Map<String, Object> i = Sql.one(c, "select i.head, i.kind, i.amount, i.proof_status, d.tax_year, d.employment_id, e.membership_id, "
                + "coalesce(p.preferred_name, split_part(p.legal_name, ' ', 1)) as first from app.tax_declaration_item i "
                + "join app.tax_declaration d on d.id = i.declaration_id join app.employment e on e.id = d.employment_id "
                + "join app.person p on p.id = e.person_id where i.id = ? for update of i", itemId);
        if (i == null) {
            throw ApiException.notFound("The claim");
        }
        if (own(ctx, i)) {
            throw new ApiException(409, "tax.own", "Nobody checks their own proofs");
        }
        if (!"submitted".equals(i.get("proofStatus"))) {
            throw new ApiException(409, "tax.not_waiting", "This claim has no proof waiting to be checked");
        }
        return i;
    }

    // ── the view both sides see ─────────────────────────────────────────

    private static Map<String, Object> view(Connection c, UUID employmentId, String ty, LocalDate today, boolean forHr) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taxYear", ty);
        Map<String, Object> w = window(c, ty);
        out.put("window", w);
        putOpen(out, w, today);
        Map<String, Object> profile = Sql.one(c, "select tax_regime from app.pay_profile where employment_id = ?", employmentId);
        out.put("regime", profile == null ? "new" : profile.get("taxRegime"));
        Map<String, Object> d = Sql.one(c, "select d.id, d.submitted_at from app.tax_declaration d where d.employment_id = ? and d.tax_year = ?",
                employmentId, ty);
        out.put("submittedAt", d == null ? null : d.get("submittedAt"));
        List<Map<String, Object>> items = d == null ? new ArrayList<>() : Sql.query(c, "select i.id, i.head, i.kind, i.amount, i.details, "
                + "i.proof_status, i.accepted_amount, i.review_note, i.reviewed_at, coalesce(" + Names.of("i.reviewed_by")
                + ", rd.email) as reviewed_by from app.tax_declaration_item i "
                + "left join app.member_directory() rd on rd.membership_id = i.reviewed_by "
                + "where i.declaration_id = ?::uuid order by i.position, i.created_at", d.get("id"));
        for (Map<String, Object> i : items) {
            Map<String, Object> details = PayrollRuns.parse(i.get("details"));
            i.put("details", details);
            i.put("label", label((String) i.get("head"), (String) i.get("kind")));
            if ("rent".equals(i.get("head"))) {
                int months = rentMonths(details, ty);
                i.put("months", months);
                i.put("yearAmount", ((BigDecimal) i.get("amount")).multiply(BigDecimal.valueOf(months)));
            }
            i.put("proofs", Sql.query(c, "select d.id, d.file_name, d.content_type, d.size_bytes, p.created_at from app.tax_proof p "
                    + "join app.document d on d.id = p.document_id where p.item_id = ?::uuid order by p.created_at", i.get("id")));
        }
        out.put("items", items);
        out.put("limits", limits(c, ty));
        if (!forHr) {
            // What the employee may do now, on every answer, so the screen stays right after each change.
            out.put("canEdit", out.get("declareOpen"));
            out.put("canSendProofs", Boolean.TRUE.equals(out.get("proofsOpen")) && out.get("submittedAt") != null);
        } else {
            out.put("counted", d == null || d.get("submittedAt") == null ? null : forPayroll(c, employmentId, today.isBefore(yearStart(ty))
                    ? yearStart(ty) : today.isAfter(yearStart(ty).plusYears(1).minusDays(1)) ? yearStart(ty).plusMonths(11) : today.withDayOfMonth(1)));
        }
        return out;
    }

    /** The old regime's limits in force at the start of the tax year, for the screens to show. */
    private static Map<String, Object> limits(Connection c, String ty) throws SQLException {
        LocalDate asOf = yearStart(ty);
        Map<String, Object> r = Sql.one(c, "select data, status from app.payroll_rule where statute = 'income_tax' and jurisdiction = 'IN' "
                + "and valid_from <= ? and (valid_to is null or valid_to > ?) order by valid_from desc limit 1", asOf, asOf);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) {
            return out;
        }
        Map<String, Object> old = PayrollEngine.map(PayrollRuns.parse(r.get("data")), "old");
        for (String k : List.of("section80cMax", "npsMax", "healthSelfMax", "healthSelfSeniorMax", "healthParentsMax", "healthParentsSeniorMax",
                "homeLoanInterestMax", "hraMetroPct", "hraOtherPct", "hraRentFloorPct", "hraMetros", "landlordPanAbove")) {
            out.put(k, old.get(k));
        }
        out.put("draft", "draft".equals(r.get("status")));
        return out;
    }

    private static Map<String, Object> window(Connection c, String ty) throws SQLException {
        return Sql.one(c, "select tax_year, declare_from, declare_until, proofs_from, proofs_until, verified_from, updated_at "
                + "from app.tax_window where tax_year = ?", ty);
    }

    private static void putOpen(Map<String, Object> out, Map<String, Object> w, LocalDate today) {
        out.put("declareOpen", open(w, "declareFrom", "declareUntil", today));
        out.put("proofsOpen", open(w, "proofsFrom", "proofsUntil", today));
    }

    private static boolean open(Map<String, Object> w, String from, String until, LocalDate today) {
        return w != null && w.get(from) != null && !today.isBefore(LocalDate.parse((String) w.get(from)))
                && !today.isAfter(LocalDate.parse((String) w.get(until)));
    }

    private static void requireDeclareOpen(Connection c, String ty, LocalDate today) throws SQLException {
        Map<String, Object> w = window(c, ty);
        if (!open(w, "declareFrom", "declareUntil", today)) {
            throw new ApiException(409, "tax.declarations_closed", w == null ? "Declarations for " + ty + " are not open yet"
                    : "Declarations for " + ty + " are open from " + w.get("declareFrom") + " to " + w.get("declareUntil")
                    + ". Ask HR if something needs changing");
        }
    }

    // ── cleaning what the employee sends ────────────────────────────────

    private record Clean(String head, String kind, BigDecimal amount, Map<String, Object> details) {}

    private static Clean clean(ItemInput in, String ty, Map<String, Object> rules) {
        String head = in.head();
        if (head == null || !HEADS.contains(head)) {
            throw ApiException.badRequest("Choose what the claim is for");
        }
        BigDecimal amount = in.amount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(new BigDecimal("99999999")) > 0) {
            throw ApiException.badRequest(head.equals("rent") ? "Give the rent you pay each month, in rupees" : "Give the amount for the year, in rupees");
        }
        String kind = null;
        if (head.equals("sec80c")) {
            kind = in.kind();
            if (kind == null || !KINDS.contains(kind)) {
                throw ApiException.badRequest("Choose which investment or payment this is");
            }
        }
        Map<String, Object> raw = in.details() == null ? Map.of() : in.details();
        Map<String, Object> d = new LinkedHashMap<>();
        switch (head) {
            case "rent" -> {
                String from = text(raw, "from", 7, true, "The first month of rent");
                String to = text(raw, "to", 7, true, "The last month of rent");
                if (!MONTH.matcher(from).matches() || !MONTH.matcher(to).matches()) {
                    throw ApiException.badRequest("Months must look like 2026-04");
                }
                YearMonth first = YearMonth.from(yearStart(ty));
                YearMonth f;
                YearMonth t;
                try {
                    f = YearMonth.parse(from);
                    t = YearMonth.parse(to);
                } catch (DateTimeException e) {
                    throw ApiException.badRequest("Months must look like 2026-04");
                }
                if (t.isBefore(f) || f.isBefore(first) || t.isAfter(first.plusMonths(11))) {
                    throw ApiException.badRequest("The months must run forwards, within April " + first.getYear() + " to March " + (first.getYear() + 1));
                }
                d.put("from", from);
                d.put("to", to);
                d.put("city", text(raw, "city", 60, true, "The city of the rented home"));
                d.put("landlordName", text(raw, "landlordName", 120, true, "The landlord's name"));
                d.put("landlordAddress", text(raw, "landlordAddress", 300, true, "The address of the rented home"));
                String pan = text(raw, "landlordPan", 10, false, "The landlord's PAN");
                pan = pan == null ? null : pan.toUpperCase();
                if (pan != null && !PAN.matcher(pan).matches()) {
                    throw ApiException.badRequest("The landlord's PAN must look like ABCDE1234F");
                }
                BigDecimal threshold = PayrollEngine.dec(rules, "landlordPanAbove");
                int months = (int) (f.until(t, java.time.temporal.ChronoUnit.MONTHS) + 1);
                if (pan == null && threshold.signum() > 0 && amount.multiply(BigDecimal.valueOf(months)).compareTo(threshold) > 0) {
                    throw ApiException.badRequest("Rent above " + PayrollEngine.rupees(threshold) + " a year needs the landlord's PAN");
                }
                d.put("landlordPan", pan);
                d.put("relationship", text(raw, "relationship", 60, false, "How you are related to the landlord"));
            }
            case "home_loan" -> {
                d.put("lenderName", text(raw, "lenderName", 120, true, "The lender's name"));
                d.put("lenderAddress", text(raw, "lenderAddress", 300, false, "The lender's address"));
                String pan = text(raw, "lenderPan", 10, false, "The lender's PAN");
                if (pan != null && !PAN.matcher(pan.toUpperCase()).matches()) {
                    throw ApiException.badRequest("The lender's PAN must look like ABCDE1234F");
                }
                d.put("lenderPan", pan == null ? null : pan.toUpperCase());
            }
            case "health_self", "health_parents" -> {
                d.put("senior", Boolean.TRUE.equals(raw.get("senior")));
                d.put("insurer", text(raw, "insurer", 120, false, "The insurer"));
            }
            case "edu_loan" -> d.put("lenderName", text(raw, "lenderName", 120, false, "The lender's name"));
            default -> d.put("note", text(raw, "note", 200, false, "The note"));
        }
        d.values().removeIf(v -> v == null);
        return new Clean(head, kind, amount, d);
    }

    private static String text(Map<String, Object> raw, String key, int max, boolean required, String what) {
        Object v = raw.get(key);
        String s = v == null ? null : String.valueOf(v).trim();
        if (s == null || s.isEmpty()) {
            if (required) {
                throw ApiException.badRequest(what + " is needed");
            }
            return null;
        }
        if (s.length() > max) {
            throw ApiException.badRequest(what + " is too long (at most " + max + " characters)");
        }
        return s;
    }

    /** The old regime's rule data in force at the start of the tax year. */
    private static Map<String, Object> oldRules(Connection c, String ty) throws SQLException {
        LocalDate asOf = yearStart(ty);
        Map<String, Object> r = Sql.one(c, "select data from app.payroll_rule where statute = 'income_tax' and jurisdiction = 'IN' "
                + "and valid_from <= ? and (valid_to is null or valid_to > ?) order by valid_from desc limit 1", asOf, asOf);
        return r == null ? new LinkedHashMap<>() : PayrollEngine.map(PayrollRuns.parse(r.get("data")), "old");
    }

    /** A claim in words, as the screens and the audit log name it. */
    static String label(String head, String kind) {
        return switch (head) {
            case "rent" -> "Rent (HRA)";
            case "home_loan" -> "Home-loan interest";
            case "nps" -> "NPS, your own contribution";
            case "health_self" -> "Health insurance: you and family";
            case "health_parents" -> "Health insurance: parents";
            case "edu_loan" -> "Education-loan interest";
            default -> switch (kind == null ? "" : kind) {
                case "life_insurance" -> "Life insurance premium";
                case "ppf" -> "Public Provident Fund";
                case "elss" -> "Tax-saving mutual fund (ELSS)";
                case "nsc" -> "National Savings Certificate";
                case "ssy" -> "Sukanya Samriddhi";
                case "tuition" -> "Children's tuition fees";
                case "home_loan_principal" -> "Home-loan principal";
                case "tax_saver_fd" -> "Five-year tax-saving deposit";
                case "epf_voluntary" -> "Voluntary PF";
                default -> "Other in the ₹1.5 lakh group";
            };
        };
    }

    // ── who is asking ───────────────────────────────────────────────────

    private record Me(UUID employmentId, UUID personId) {}

    private static Me me(Connection c, SessionCtx ctx) throws SQLException {
        if (ctx.membershipId() == null || !"tenant".equals(ctx.kind())) {
            throw new ApiException(403, "tenant.required", "Tax declarations are for employees");
        }
        Map<String, Object> e = Sql.one(c, "select id, person_id from app.employment where membership_id = ? and status = 'active' "
                + "order by joining_date desc limit 1", ctx.membershipId());
        if (e == null) {
            throw new ApiException(409, "tax.not_employed", "Tax declarations are for people employed now");
        }
        return new Me(AuthService.uuid(e.get("id")), AuthService.uuid(e.get("personId")));
    }

    private static UUID declaration(Connection c, SessionCtx ctx, UUID employmentId, String ty) throws SQLException {
        Sql.update(c, "insert into app.tax_declaration (tenant_id, employment_id, tax_year) values (?, ?, ?) on conflict (employment_id, tax_year) do nothing",
                ctx.tenantId(), employmentId, ty);
        return AuthService.uuid(Sql.one(c, "select id from app.tax_declaration where employment_id = ? and tax_year = ?", employmentId, ty).get("id"));
    }

    private static Map<String, Object> ownItem(Connection c, UUID itemId, UUID declarationId) throws SQLException {
        Map<String, Object> i = Sql.one(c, "select head, kind, proof_status from app.tax_declaration_item where id = ? and declaration_id = ? "
                + "for update", itemId, declarationId);
        if (i == null) {
            throw ApiException.notFound("The claim");
        }
        return i;
    }

    private static boolean own(SessionCtx ctx, Map<String, Object> row) {
        return ctx.membershipId() != null && ctx.membershipId().toString().equals(String.valueOf(row.get("membershipId")));
    }

    private static boolean canRead(SessionCtx ctx) {
        return ctx.reachesEveryone("compensation.read") || ctx.reachesEveryone("payroll.read");
    }

    private static boolean canManage(SessionCtx ctx) {
        return ctx.reachesEveryone("compensation.write") && ctx.membershipId() != null && !ctx.isSupport();
    }

    private static void needRead(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!canRead(ctx)) {
            throw ApiException.denied("compensation.read");
        }
    }

    private static void needManage(SessionCtx ctx) {
        needRead(ctx);
        if (!canManage(ctx)) {
            throw ApiException.denied("compensation.write");
        }
    }

    private static LocalDate date(String value, String what) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException | NullPointerException e) {
            throw ApiException.badRequest(what + " must be a date like 2026-04-01");
        }
    }

    private static LocalDate optionalDate(String value, String what) {
        return value == null || value.isBlank() ? null : date(value, what);
    }
}

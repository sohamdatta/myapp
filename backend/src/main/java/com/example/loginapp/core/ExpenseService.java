package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Expense claims inside one Organization (docs/expenses/design.md, stages X1 and X2): categories with policy,
 * claims of lines, receipts and other evidence checked line by line, a manager's decision, and finance's
 * verification. Paying a verified claim and advances and loans come in later stages.
 *
 * <p>The status machine lives here; the database holds the invariants that must never break (nobody
 * decides their own claim, a cut or a rejection says why, an approved amount never exceeds the claim).
 * Every change takes the version the caller last read and is refused with 409 when it has moved.
 */
public final class ExpenseService {

    /** What finance may do with a line's evidence. */
    private static final Set<String> REVIEWS = Set.of("accepted", "rejected", "more_needed");
    private static final Set<String> KINDS = Set.of("receipt", "travel_proof", "pre_approval", "missing_receipt_declaration");
    /** A missing-receipt declaration stands in for a receipt up to this amount. */
    static final BigDecimal DECLARATION_UP_TO = new BigDecimal("2000");
    /** More warnings than this, or a declaration, means the finance verifier must differ from the manager who passed it. */
    static final int MANY_WARNINGS = 2;

    public record CategoryInput(String name, BigDecimal limitPerExpense, BigDecimal limitPerMonth, BigDecimal receiptAbove,
                                BigDecimal perKmRate, Boolean needsTravelProof, Boolean needsApprovalFirst, String ledger, Boolean active) {}

    public record LineInput(String categoryId, String spentOn, BigDecimal amount, BigDecimal km, String merchant, String note,
                            String overLimitReason) {}

    public record Saved(UUID id) {}

    public record Created(UUID id, String number, String status, int version) {}

    public record LineAdded(UUID id, BigDecimal amount) {}

    public record FileAdded(UUID id, String kind, String fileName) {}

    public record Submitted(String status, int warnings, int version) {}

    /** One claim with its lines, each with its files and the latest review. */
    public record ClaimView(UUID id, UUID membershipId, String number, String title, String status, Object submittedAt,
                            String decisionNote, String managerNote, String financeNote, Object limits, int version,
                            String claimant, BigDecimal total, BigDecimal payable, List<Map<String, Object>> lines) {}

    /** A reviewer's change to one line: the amount approved and why it is lower. */
    public record Cut(String lineId, BigDecimal amount, String reason) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public ExpenseService(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    /** The clock is a parameter so tests can choose the date. */
    public ExpenseService(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(AttendanceDays.DEFAULT_ZONE));
    }

    // ── categories ──────────────────────────────────────────────────────

    /** The categories a claimant can pick (active ones), or all of them for setup. */
    public List<Map<String, Object>> categories(SessionCtx ctx, boolean all) {
        if (all) {
            ctx.require("expense.setup");
        } else if (!ctx.can("expense.claim") && !ctx.can("expense.setup") && !ctx.can("expense.finance")) {
            throw ApiException.denied("expense.claim");
        }
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select id, name, limit_per_expense, limit_per_month, receipt_above, per_km_rate, needs_travel_proof, "
                        + "needs_approval_first, ledger, active from app.expense_category "
                        + (all ? "" : "where active ") + "order by active desc, lower(name)"));
    }

    /** Creates a category, or changes one when an id is given. */
    public Saved saveCategory(SessionCtx ctx, UUID id, CategoryInput in) {
        ctx.require("expense.setup");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiException.badRequest("Give the category a name of up to 60 characters");
        }
        positive(in.limitPerExpense(), "The limit per expense");
        positive(in.limitPerMonth(), "The limit per month");
        positive(in.perKmRate(), "The rate per km");
        BigDecimal receiptAbove = in.receiptAbove() == null ? new BigDecimal("500") : in.receiptAbove();
        if (receiptAbove.signum() < 0) {
            throw ApiException.badRequest("The receipt amount cannot be negative");
        }
        // A category that needs no receipt and is not taxed as pay has no check on it at all (design, Tax treatment).
        if (in.perKmRate() == null && in.limitPerExpense() == null && receiptAbove.compareTo(new BigDecimal("100000")) > 0) {
            throw ApiException.badRequest("A receipt must be needed above some amount, or the category needs a limit per expense");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Object[] values = {name, in.limitPerExpense(), in.limitPerMonth(), receiptAbove, in.perKmRate(),
                    Boolean.TRUE.equals(in.needsTravelProof()), Boolean.TRUE.equals(in.needsApprovalFirst()),
                    blankToNull(in.ledger()), in.active() == null || in.active()};
            if (Sql.exists(c, "select 1 from app.expense_category where lower(name) = lower(?) and (?::uuid is null or id <> ?::uuid)", name, id, id)) {
                throw new ApiException(409, "expense.category_exists", "A category called " + name + " already exists");
            }
            Map<String, Object> saved;
            if (id == null) {
                saved = Sql.one(c, "insert into app.expense_category (tenant_id, name, limit_per_expense, limit_per_month, receipt_above, "
                        + "per_km_rate, needs_travel_proof, needs_approval_first, ledger, active) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id",
                        concat(ctx.tenantId(), values));
            } else {
                saved = Sql.one(c, "update app.expense_category set name = ?, limit_per_expense = ?, limit_per_month = ?, receipt_above = ?, "
                        + "per_km_rate = ?, needs_travel_proof = ?, needs_approval_first = ?, ledger = ?, active = ? where id = ? returning id",
                        concat(null, values, id));
                if (saved == null) {
                    throw ApiException.notFound("The category");
                }
            }
            UserService.audit(c, ctx, id == null ? "expense.category_created" : "expense.category_changed", "expense_category",
                    AuthService.uuid(saved.get("id")), Map.of("name", name));
            return new Saved(AuthService.uuid(saved.get("id")));
        });
    }

    // ── the claimant's own claims ───────────────────────────────────────

    public Created createClaim(SessionCtx ctx, String title) {
        requireClaim(ctx);
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty() || clean.length() > 120) {
            throw ApiException.badRequest("Give the claim a title of up to 120 characters");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            int year = today().getYear();
            Map<String, Object> n = Sql.one(c, "insert into app.expense_counter (tenant_id, year, last) values (?, ?, 1) "
                    + "on conflict (tenant_id, year) do update set last = app.expense_counter.last + 1 returning last",
                    ctx.tenantId(), year);
            String number = String.format("EXP-%02d-%05d", year % 100, ((Number) n.get("last")).intValue());
            Map<String, Object> made = Sql.one(c, "insert into app.expense_claim (tenant_id, membership_id, number, title) values (?, ?, ?, ?) "
                    + "returning id, number, status, version", ctx.tenantId(), ctx.membershipId(), number, clean);
            return new Created(AuthService.uuid(made.get("id")), number, (String) made.get("status"), ((Number) made.get("version")).intValue());
        });
    }

    public List<Map<String, Object>> myClaims(SessionCtx ctx) {
        requireClaim(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, CLAIM_LIST + "where k.membership_id = ? order by k.created_at desc limit 200",
                ctx.membershipId()));
    }

    private static final String CLAIM_LIST = "select k.id, k.number, k.title, k.status, k.submitted_at, k.decision_note, k.version, "
            + "k.membership_id, " + Names.orEmail("k.membership_id") + " as claimant, "
            + "(select count(*) from app.expense_line l where l.claim_id = k.id) as lines, "
            + "(select coalesce(sum(l.amount), 0) from app.expense_line l where l.claim_id = k.id) as total, "
            + "(select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) from app.expense_line l where l.claim_id = k.id) as payable "
            + "from app.expense_claim k ";

    /** One claim with its lines, files and the latest review of each line; for its owner, the approver who reaches them, or finance. */
    public ClaimView claim(SessionCtx ctx, UUID claimId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = Sql.one(c, CLAIM_LIST.replace("(select count(*) from app.expense_line l where l.claim_id = k.id) as lines, ", "")
                    .replace("k.decision_note,", "k.decision_note, k.manager_note, k.finance_note, k.limits::text as limits, ")
                    + "where k.id = ?", claimId);
            if (k == null || !canSee(c, ctx, AuthService.uuid(k.get("membershipId")))) {
                throw ApiException.notFound("The claim");
            }
            List<Map<String, Object>> lines = Sql.query(c,
                    "select l.id, l.category_id, g.name as category, l.spent_on, l.amount, l.approved_amount, l.cut_reason, l.km, l.merchant, "
                            + "l.note, l.over_limit_reason, l.flags::text as flags, "
                            + "(select r.status from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1) as review, "
                            + "(select r.note from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1) as review_note "
                            + "from app.expense_line l join app.expense_category g on g.id = l.category_id "
                            + "where l.claim_id = ? order by l.spent_on, l.created_at", claimId);
            for (Map<String, Object> l : lines) {
                l.put("files", Sql.query(c, "select id, kind, file_name, content_type, size_bytes, declaration, created_at "
                        + "from app.evidence_file where line_id = ? order by created_at", AuthService.uuid(l.get("id"))));
                l.put("flags", Json.parse((String) l.get("flags")));
            }
            return new ClaimView(AuthService.uuid(k.get("id")), AuthService.uuid(k.get("membershipId")), (String) k.get("number"),
                    (String) k.get("title"), (String) k.get("status"), k.get("submittedAt"), (String) k.get("decisionNote"),
                    (String) k.get("managerNote"), (String) k.get("financeNote"), Json.parse((String) k.get("limits")),
                    ((Number) k.get("version")).intValue(), (String) k.get("claimant"), (BigDecimal) k.get("total"),
                    (BigDecimal) k.get("payable"), lines);
        });
    }

    /** Adds a line to the caller's draft claim. A distance-based category works the amount out from the km. */
    public LineAdded addLine(SessionCtx ctx, UUID claimId, LineInput in) {
        requireClaim(ctx);
        LocalDate spent = AttendanceDays.date(in.spentOn());
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            requireStatus(k, "draft");
            Map<String, Object> cat = category(c, in.categoryId());
            BigDecimal amount = in.amount();
            BigDecimal km = in.km();
            if (cat.get("perKmRate") != null) {
                if (km == null || km.signum() <= 0) {
                    throw ApiException.badRequest(cat.get("name") + " is paid by distance: enter the km");
                }
                if (km.scale() > 1 || km.compareTo(new BigDecimal("99999.9")) > 0) {
                    throw ApiException.badRequest("Enter the distance in km to one decimal place, up to 99999.9");
                }
                amount = km.multiply((BigDecimal) cat.get("perKmRate")).setScale(2, RoundingMode.HALF_UP);
            } else {
                km = null;
            }
            if (amount == null || amount.signum() <= 0) {
                throw ApiException.badRequest("Enter an amount above zero");
            }
            if (amount.scale() > 2 || amount.compareTo(new BigDecimal("99999999.99")) > 0) {
                throw ApiException.badRequest("Enter an amount in rupees and paise");
            }
            Map<String, Object> line = Sql.one(c, "insert into app.expense_line (tenant_id, claim_id, category_id, spent_on, amount, km, "
                    + "merchant, note, over_limit_reason) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id, amount",
                    ctx.tenantId(), claimId, AuthService.uuid(cat.get("id")), spent, amount, km, blankToNull(in.merchant()),
                    blankToNull(in.note()), blankToNull(in.overLimitReason()));
            touch(c, claimId);
            return new LineAdded(AuthService.uuid(line.get("id")), (BigDecimal) line.get("amount"));
        });
    }

    /** Removes a line of a draft claim, with its files. */
    public void deleteLine(SessionCtx ctx, UUID lineId) {
        requireClaim(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwnByLine(c, ctx, lineId);
            requireStatus(k, "draft");
            Sql.update(c, "delete from app.expense_line where id = ?", lineId);
            touch(c, AuthService.uuid(k.get("id")));
            return null;
        });
    }

    /** Deletes a draft claim. A submitted claim is withdrawn instead and kept. */
    public void deleteClaim(SessionCtx ctx, UUID claimId) {
        requireClaim(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            requireStatus(k, "draft");
            Sql.update(c, "delete from app.expense_claim where id = ?", claimId);
            return null;
        });
    }

    /**
     * Stores a receipt or other file on a line. Allowed on the caller's own draft claim, and on a claim finance has
     * paused for more evidence. The bytes are checked by {@link FileSniffer} and sealed like onboarding documents.
     */
    public FileAdded uploadEvidence(SessionCtx ctx, UUID lineId, String kind, String fileName, byte[] bytes, String declaration) {
        requireClaim(ctx);
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("Unknown kind of evidence: " + kind);
        }
        boolean isDeclaration = "missing_receipt_declaration".equals(kind);
        FileSniffer.Cleaned file = isDeclaration && (bytes == null || bytes.length == 0) ? null : FileSniffer.clean(bytes, fileName);
        String statement = declaration == null ? null : declaration.trim();
        if (isDeclaration && (statement == null || statement.length() < 10)) {
            throw ApiException.badRequest("Say in a sentence why the receipt is missing");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwnByLine(c, ctx, lineId);
            if (!List.of("draft", "evidence_requested").contains((String) k.get("status"))) {
                throw new ApiException(409, "expense.locked", "Files can be added while the claim is a draft or finance has asked for more");
            }
            if ("evidence_requested".equals(k.get("status")) && !"more_needed".equals(latestReview(c, lineId))) {
                throw new ApiException(409, "expense.not_requested", "Finance has not asked for more on this line");
            }
            if (isDeclaration) {
                Map<String, Object> amount = Sql.one(c, "select amount from app.expense_line where id = ?", lineId);
                if (((BigDecimal) amount.get("amount")).compareTo(DECLARATION_UP_TO) > 0) {
                    throw new ApiException(409, "expense.declaration_limit",
                            "A missing-receipt declaration is accepted up to ₹" + DECLARATION_UP_TO.toPlainString() + " a line");
                }
            }
            byte[] data = file == null ? new byte[] {0} : file.bytes();
            byte[] digest = sha256(file == null ? statement.getBytes(java.nio.charset.StandardCharsets.UTF_8) : data);
            Map<String, Object> saved = Sql.one(c, "insert into app.evidence_file (tenant_id, line_id, kind, file_name, content_type, size_bytes, "
                    + "sha256, sealed, declaration, uploaded_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id, kind, file_name",
                    ctx.tenantId(), lineId, kind, file == null ? "declaration.txt" : file.fileName(),
                    file == null ? "application/pdf" : file.contentType(), data.length, digest,
                    crypto.encrypt(ctx.tenantId(), data), isDeclaration ? statement : null, ctx.membershipId());
            touch(c, AuthService.uuid(k.get("id")));
            return new FileAdded(AuthService.uuid(saved.get("id")), (String) saved.get("kind"), (String) saved.get("fileName"));
        });
    }

    /** The file itself, for the claimant, the approver who reaches them, or finance. Every view is recorded. */
    public DocumentService.Content file(SessionCtx ctx, UUID fileId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> f = Sql.one(c, "select f.file_name, f.content_type, k.membership_id from app.evidence_file f "
                    + "join app.expense_line l on l.id = f.line_id join app.expense_claim k on k.id = l.claim_id where f.id = ?", fileId);
            if (f == null || !canSee(c, ctx, AuthService.uuid(f.get("membershipId")))) {
                throw ApiException.notFound("The file");
            }
            // Sql hides byte columns from every row, so the sealed bytes are read directly, as documents are.
            byte[] sealed;
            try (PreparedStatement ps = c.prepareStatement("select sealed from app.evidence_file where id = ?")) {
                ps.setObject(1, fileId);
                try (ResultSet rs = ps.executeQuery()) {
                    sealed = rs.next() ? rs.getBytes(1) : null;
                }
            }
            if (sealed == null) {
                throw ApiException.notFound("The file");
            }
            UserService.audit(c, ctx, "expense.file_viewed", "evidence_file", fileId, Map.of());
            return new DocumentService.Content((String) f.get("contentType"), (String) f.get("fileName"), crypto.decrypt(ctx.tenantId(), sealed));
        });
    }

    // ── submit and withdraw ─────────────────────────────────────────────

    /**
     * Checks the claim against policy and sends it on. Blocking problems stop it, naming each one; warnings go ahead and
     * are kept on the line for every approver. With no manager the claim goes straight to finance.
     */
    public Submitted submit(SessionCtx ctx, UUID claimId, int version) {
        requireClaim(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            requireStatus(k, "draft");
            requireVersion(k, version);
            int window = Settings.get(c, Settings.EXPENSE_SUBMIT_DAYS).asInt();
            // A claim sent back for changes is not held to the window again: the clock stopped when it was first submitted.
            boolean resubmitted = Sql.one(c, "select submitted_at from app.expense_claim where id = ?", claimId).get("submittedAt") != null;
            LocalDate today = today();
            List<Map<String, Object>> lines = Sql.query(c,
                    "select l.id, l.category_id, l.spent_on, l.amount, l.over_limit_reason, g.name as category, g.limit_per_expense, "
                            + "g.limit_per_month, g.receipt_above, g.needs_travel_proof, g.needs_approval_first "
                            + "from app.expense_line l join app.expense_category g on g.id = l.category_id where l.claim_id = ? order by l.spent_on", claimId);
            if (lines.isEmpty()) {
                throw ApiException.badRequest("Add at least one expense line");
            }
            List<String> blocks = new ArrayList<>();
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("submitWithinDays", window);
            Map<String, Object> cats = new LinkedHashMap<>();
            int warnings = 0;
            boolean declaration = false;
            Set<String> monthFlagged = new java.util.HashSet<>();
            for (Map<String, Object> l : lines) {
                UUID lineId = AuthService.uuid(l.get("id"));
                LocalDate spent = LocalDate.parse((String) l.get("spentOn"));
                BigDecimal amount = (BigDecimal) l.get("amount");
                String label = l.get("category") + " on " + spent + " (₹" + amount.toPlainString() + ")";
                List<Map<String, Object>> flags = new ArrayList<>();
                if (spent.isAfter(today)) {
                    blocks.add(label + " is dated in the future");
                } else if (!resubmitted && spent.isBefore(today.minusDays(window))) {
                    blocks.add(label + " is older than " + window + " days, so it can no longer be claimed");
                }
                Set<String> kinds = new java.util.HashSet<>();
                Sql.query(c, "select kind from app.evidence_file where line_id = ?", lineId).forEach(r -> kinds.add((String) r.get("kind")));
                boolean hasReceipt = kinds.contains("receipt");
                boolean hasDeclaration = kinds.contains("missing_receipt_declaration");
                declaration |= hasDeclaration;
                BigDecimal receiptAbove = (BigDecimal) l.get("receiptAbove");
                if (amount.compareTo(receiptAbove) > 0 && !hasReceipt && !hasDeclaration) {
                    blocks.add(label + " needs a receipt, or a signed statement of why it is missing");
                }
                if (Boolean.TRUE.equals(l.get("needsTravelProof")) && !kinds.contains("travel_proof")) {
                    blocks.add(label + " needs proof of travel, such as a ticket or boarding pass");
                }
                BigDecimal perExpense = (BigDecimal) l.get("limitPerExpense");
                if (perExpense != null && amount.compareTo(perExpense) > 0) {
                    if (l.get("overLimitReason") == null) {
                        blocks.add(label + " is over the limit of ₹" + perExpense.toPlainString() + ": say why");
                    } else {
                        flags.add(flag("over_limit", "Over the limit of ₹" + perExpense.toPlainString()));
                    }
                    if (Boolean.TRUE.equals(l.get("needsApprovalFirst")) && !kinds.contains("pre_approval")) {
                        blocks.add(label + " is over the limit and needs the manager's approval attached first");
                    }
                }
                BigDecimal perMonth = (BigDecimal) l.get("limitPerMonth");
                if (perMonth != null && monthFlagged.add(l.get("categoryId") + "|" + spent.withDayOfMonth(1))) {
                    BigDecimal used = (BigDecimal) Sql.one(c, "select coalesce(sum(coalesce(l.approved_amount, l.amount)), 0) as s from app.expense_line l "
                            + "join app.expense_claim k on k.id = l.claim_id where k.membership_id = ? and l.category_id = ? "
                            + "and date_trunc('month', l.spent_on) = date_trunc('month', ?::date) "
                            + "and (k.id = ? or k.status not in ('draft','withdrawn','rejected','returned'))",
                            ctx.membershipId(), AuthService.uuid(l.get("categoryId")), spent, claimId).get("s");
                    if (used.compareTo(perMonth) > 0) {
                        flags.add(flag("over_month_limit", "The month's " + l.get("category") + " claims come to ₹" + used.toPlainString()
                                + ", over ₹" + perMonth.toPlainString()));
                    }
                }
                if (Sql.exists(c, "select 1 from app.expense_line o join app.expense_claim ok on ok.id = o.claim_id "
                        + "where ok.membership_id = ? and o.id <> ? and ok.id <> ? and o.category_id = ? and o.spent_on = ? and o.amount = ? "
                        + "and ok.status not in ('draft','withdrawn','rejected','returned')",
                        ctx.membershipId(), lineId, claimId, AuthService.uuid(l.get("categoryId")), spent, amount)) {
                    flags.add(flag("possible_duplicate", "Same category, date and amount as a line in another claim"));
                }
                if (Sql.exists(c, "select 1 from app.evidence_file a join app.evidence_file b on b.sha256 = a.sha256 and b.id <> a.id "
                        + "and b.kind = 'receipt' join app.expense_line bl on bl.id = b.line_id join app.expense_claim bk on bk.id = bl.claim_id "
                        + "where a.line_id = ? and a.kind = 'receipt' and bk.id <> ? and bk.status not in ('draft','withdrawn','rejected','returned')", lineId, claimId)) {
                    flags.add(flag("duplicate_receipt", "This receipt file is already attached to another line"));
                }
                warnings += flags.size();
                boolean needsEvidence = amount.compareTo(receiptAbove) > 0 || Boolean.TRUE.equals(l.get("needsTravelProof"));
                Sql.update(c, "update app.expense_line set flags = ?::jsonb, approved_amount = null, cut_reason = null, evidence_required = ? where id = ?",
                        Json.write(flags), needsEvidence, lineId);
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("limitPerExpense", perExpense);
                rule.put("limitPerMonth", perMonth);
                rule.put("receiptAbove", receiptAbove);
                cats.put(String.valueOf(l.get("categoryId")), rule);
            }
            if (!blocks.isEmpty()) {
                throw new ApiException(409, "expense.cannot_submit", "This claim cannot be submitted yet: " + String.join("; ", blocks));
            }
            snapshot.put("categories", cats);
            snapshot.put("warnings", warnings);
            snapshot.put("declaration", declaration);
            UUID manager = managerOf(c, ctx.membershipId());
            String next = manager == null ? "approved" : "submitted";
            Sql.update(c, "update app.expense_claim set status = ?, submitted_at = now(), limits = ?::jsonb, decision_note = null, "
                    + "version = version + 1, updated_at = now() where id = ?", next, Json.write(snapshot), claimId);
            UserService.audit(c, ctx, "expense.submitted", "expense_claim", claimId,
                    Map.of("number", k.get("number"), "warnings", warnings, "route", manager == null ? "finance" : "manager"));
            return new Submitted(next, warnings, version + 1);
        });
    }

    /** The claimant takes a claim back before the manager has acted. After that they ask the approver to return it. */
    public void withdraw(SessionCtx ctx, UUID claimId, int version) {
        requireClaim(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            if (!List.of("draft", "submitted").contains((String) k.get("status"))) {
                throw new ApiException(409, "expense.cannot_withdraw", "Once the manager has acted, ask them to return the claim");
            }
            requireVersion(k, version);
            Sql.update(c, "update app.expense_claim set status = 'withdrawn', version = version + 1, updated_at = now() where id = ?", claimId);
            UserService.audit(c, ctx, "expense.withdrawn", "expense_claim", claimId, Map.of("number", k.get("number")));
            return null;
        });
    }

    /** After finance paused a claim for more evidence: sends it back once each requested line has a newer file. */
    public void sendBack(SessionCtx ctx, UUID claimId, int version) {
        requireClaim(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            requireStatus(k, "evidence_requested");
            requireVersion(k, version);
            long waiting = Sql.count(c, "select 1 from app.expense_line l where l.claim_id = ? "
                    + "and (select r.status from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1) = 'more_needed' "
                    + "and not exists (select 1 from app.evidence_file f where f.line_id = l.id and f.created_at > "
                    + "  (select r.reviewed_at from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1))", claimId);
            if (waiting > 0) {
                throw new ApiException(409, "expense.evidence_missing", "Add the file finance asked for on each marked line first");
            }
            Sql.update(c, "update app.expense_claim set status = 'approved', version = version + 1, updated_at = now() where id = ?", claimId);
            UserService.audit(c, ctx, "expense.evidence_sent", "expense_claim", claimId, Map.of("number", k.get("number")));
            return null;
        });
    }

    // ── the manager ─────────────────────────────────────────────────────

    /** Submitted claims the caller may decide: their reports', or everyone's when the permission reaches everyone. */
    public List<Map<String, Object>> approvals(SessionCtx ctx) {
        String scope = ctx.scopeOf("expense.approve");
        if (scope == null || scope.equals("self")) {
            throw ApiException.denied("expense.approve");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            UUID[] team = Team.reach(c, ctx, "expense.approve", today());
            return Sql.query(c, CLAIM_LIST + "where k.status = 'submitted' and k.membership_id <> ? "
                    + (team == null ? "" : "and k.membership_id = any(?) ") + "order by k.submitted_at limit 200",
                    team == null ? new Object[] {ctx.membershipId()} : new Object[] {ctx.membershipId(), team});
        });
    }

    /**
     * The manager's decision: approve (optionally cutting lines), return for changes, or reject. A reason is required for
     * return and reject, for every cut, and for approving a claim that carries warnings.
     */
    public void decide(SessionCtx ctx, UUID claimId, String action, String note, int version, List<Cut> cuts) {
        String scope = ctx.scopeOf("expense.approve");
        if (scope == null || scope.equals("self") || ctx.membershipId() == null) {
            throw ApiException.denied("expense.approve");
        }
        String why = blankToNull(note);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lock(c, claimId);
            UUID owner = k == null ? null : AuthService.uuid(k.get("membershipId"));
            if (k == null || !"submitted".equals(k.get("status")) || !Team.reaches(c, ctx, "expense.approve", owner, today())) {
                throw ApiException.notFound("The submitted claim");
            }
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(409, "expense.own_claim", "You cannot decide your own claim");
            }
            requireVersion(k, version);
            switch (action) {
                case "approve" -> {
                    if (hasWarnings(c, claimId) && why == null) {
                        throw ApiException.badRequest("This claim has policy warnings: write a comment to approve it");
                    }
                    applyCuts(c, claimId, cuts);
                    Sql.update(c, "update app.expense_line set approved_amount = amount where claim_id = ? and approved_amount is null", claimId);
                    Sql.update(c, "update app.expense_claim set status = 'approved', manager_by = ?, manager_at = now(), manager_note = ?, "
                            + "version = version + 1, updated_at = now() where id = ?", ctx.membershipId(), why, claimId);
                }
                case "return", "reject" -> {
                    if (why == null) {
                        throw ApiException.badRequest("Give a reason, so the employee knows what to do");
                    }
                    boolean back = action.equals("return");
                    Sql.update(c, "update app.expense_claim set status = ?, manager_by = ?, manager_at = now(), decision_note = ?, "
                            + "version = version + 1, updated_at = now() where id = ?", back ? "returned" : "rejected", ctx.membershipId(), why, claimId);
                }
                default -> throw ApiException.badRequest("Unknown decision: " + action);
            }
            UserService.audit(c, ctx, "expense." + action, "expense_claim", claimId, decisionDetail(k, why, cuts));
            return null;
        });
    }

    /** A returned claim becomes a draft again, for the employee to edit and resubmit. */
    public void reopen(SessionCtx ctx, UUID claimId, int version) {
        requireClaim(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lockOwn(c, ctx, claimId);
            requireStatus(k, "returned");
            requireVersion(k, version);
            Sql.update(c, "update app.expense_claim set status = 'draft', version = version + 1, updated_at = now() where id = ?", claimId);
            return null;
        });
    }

    // ── finance ─────────────────────────────────────────────────────────

    /** Claims waiting for finance: approved by the manager, or paused for evidence. */
    public List<Map<String, Object>> verifyQueue(SessionCtx ctx) {
        ctx.require("expense.finance");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, CLAIM_LIST
                + "where k.status in ('approved','evidence_requested') order by k.submitted_at limit 200"));
    }

    /**
     * Records finance's check of one line's evidence. Asking for more pauses the claim; rejecting cuts the line to zero.
     * The claimant never reviews their own evidence.
     */
    public void reviewEvidence(SessionCtx ctx, UUID lineId, String status, String note, int version) {
        ctx.require("expense.finance");
        if (!REVIEWS.contains(status)) {
            throw ApiException.badRequest("Choose accepted, rejected or more needed");
        }
        String why = blankToNull(note);
        if (!status.equals("accepted") && (why == null || why.length() < 3)) {
            throw ApiException.badRequest("Say what is wrong or what is needed");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = Sql.one(c, "select k.id, k.membership_id, k.status, k.number, k.version from app.expense_line l "
                    + "join app.expense_claim k on k.id = l.claim_id where l.id = ? for update of k", lineId);
            if (k == null) {
                throw ApiException.notFound("The line");
            }
            UUID claimId = AuthService.uuid(k.get("id"));
            if (AuthService.uuid(k.get("membershipId")).equals(ctx.membershipId())) {
                throw new ApiException(409, "expense.own_claim", "You cannot review the evidence of your own claim");
            }
            if (!List.of("approved", "evidence_requested").contains((String) k.get("status"))) {
                throw new ApiException(409, "expense.not_reviewable", "Evidence is reviewed once the manager has approved the claim");
            }
            requireVersion(k, version);
            Sql.update(c, "insert into app.evidence_review (tenant_id, line_id, status, note, reviewed_by) values (?, ?, ?, ?, ?)",
                    ctx.tenantId(), lineId, status, why, ctx.membershipId());
            if (status.equals("rejected")) {
                Sql.update(c, "update app.expense_line set approved_amount = 0, cut_reason = ? where id = ?", "Evidence rejected: " + why, lineId);
            }
            Sql.update(c, "update app.expense_claim set status = ?, version = version + 1, updated_at = now() where id = ?",
                    status.equals("more_needed") ? "evidence_requested" : k.get("status"), claimId);
            UserService.audit(c, ctx, "expense.evidence_" + status, "expense_line", lineId, Map.of("claim", k.get("number")));
            return null;
        });
    }

    /**
     * Verifies an approved claim: every line that needs evidence has it accepted, or has been cut to zero. A claim with many
     * warnings, or one that relies on a missing-receipt declaration, cannot be verified by the manager who passed it.
     */
    public void verify(SessionCtx ctx, UUID claimId, int version, String note) {
        ctx.require("expense.finance");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lock(c, claimId);
            if (k == null) {
                throw ApiException.notFound("The claim");
            }
            requireStatus(k, "approved");
            UUID owner = AuthService.uuid(k.get("membershipId"));
            if (owner.equals(ctx.membershipId())) {
                throw new ApiException(409, "expense.own_claim", "You cannot verify your own claim");
            }
            requireVersion(k, version);
            Map<String, Object> limits = asMap(Json.parse((String) Sql.one(c, "select limits::text as l from app.expense_claim where id = ?", claimId).get("l")));
            boolean usesDeclaration = Sql.exists(c, "select 1 from app.evidence_file f join app.expense_line l on l.id = f.line_id "
                    + "where l.claim_id = ? and f.kind = 'missing_receipt_declaration'", claimId);
            boolean risky = ((Number) limits.getOrDefault("warnings", 0)).intValue() > MANY_WARNINGS || usesDeclaration;
            if (risky && ctx.membershipId().equals(k.get("managerBy") == null ? null : AuthService.uuid(k.get("managerBy")))) {
                throw new ApiException(409, "expense.same_person", "This claim carries several warnings or a missing-receipt statement: "
                        + "someone other than the manager who approved it must verify it");
            }
            // A line is open when it needs evidence and the latest review is not an acceptance of what is on file now.
            List<Map<String, Object>> open = Sql.query(c,
                    "select l.id from app.expense_line l where l.claim_id = ? and coalesce(l.approved_amount, l.amount) > 0 and l.evidence_required "
                            + "and (coalesce((select r.status from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1), '') <> 'accepted' "
                            + "  or exists (select 1 from app.evidence_file f where f.line_id = l.id and f.created_at > "
                            + "    (select r.reviewed_at from app.evidence_review r where r.line_id = l.id order by r.reviewed_at desc, r.id desc limit 1)))", claimId);
            if (!open.isEmpty()) {
                throw new ApiException(409, "expense.evidence_open", open.size() + " line(s) still need their evidence accepted before the claim can be verified");
            }
            BigDecimal payable = (BigDecimal) Sql.one(c, "select coalesce(sum(coalesce(approved_amount, amount)), 0) as s from app.expense_line where claim_id = ?", claimId).get("s");
            if (payable.signum() <= 0) {
                throw new ApiException(409, "expense.nothing_payable", "Every line is cut to zero: reject the claim instead");
            }
            Sql.update(c, "update app.expense_claim set status = 'verified', finance_by = ?, finance_at = now(), finance_note = ?, "
                    + "version = version + 1, updated_at = now() where id = ?", ctx.membershipId(), blankToNull(note), claimId);
            UserService.audit(c, ctx, "expense.verified", "expense_claim", claimId, Map.of("number", k.get("number"), "payable", payable.toPlainString()));
            return null;
        });
    }

    /** Finance rejects an approved claim, with the reason. */
    public void financeReject(SessionCtx ctx, UUID claimId, int version, String note) {
        ctx.require("expense.finance");
        String why = blankToNull(note);
        if (why == null) {
            throw ApiException.badRequest("Give a reason, so the employee knows why");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> k = lock(c, claimId);
            if (k == null) {
                throw ApiException.notFound("The claim");
            }
            if (!List.of("approved", "evidence_requested").contains((String) k.get("status"))) {
                throw new ApiException(409, "expense.bad_state", "Only a claim waiting for finance can be rejected here");
            }
            if (AuthService.uuid(k.get("membershipId")).equals(ctx.membershipId())) {
                throw new ApiException(409, "expense.own_claim", "You cannot decide your own claim");
            }
            requireVersion(k, version);
            Sql.update(c, "update app.expense_claim set status = 'rejected', finance_by = ?, finance_at = now(), decision_note = ?, "
                    + "version = version + 1, updated_at = now() where id = ?", ctx.membershipId(), why, claimId);
            UserService.audit(c, ctx, "expense.finance_rejected", "expense_claim", claimId, Map.of("number", k.get("number"), "reason", why));
            return null;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static final String LOCK = "select id, membership_id, status, version, number, manager_by from app.expense_claim where id = ? for update";

    private static Map<String, Object> lock(Connection c, UUID claimId) throws SQLException {
        return Sql.one(c, LOCK, claimId);
    }

    private static Map<String, Object> lockOwn(Connection c, SessionCtx ctx, UUID claimId) throws SQLException {
        Map<String, Object> k = lock(c, claimId);
        if (k == null || !ctx.membershipId().equals(AuthService.uuid(k.get("membershipId")))) {
            throw ApiException.notFound("The claim");
        }
        return k;
    }

    private static Map<String, Object> lockOwnByLine(Connection c, SessionCtx ctx, UUID lineId) throws SQLException {
        Map<String, Object> l = Sql.one(c, "select claim_id from app.expense_line where id = ?", lineId);
        if (l == null) {
            throw ApiException.notFound("The line");
        }
        return lockOwn(c, ctx, AuthService.uuid(l.get("claimId")));
    }

    private static void requireStatus(Map<String, Object> k, String status) {
        if (!status.equals(k.get("status"))) {
            throw new ApiException(409, "expense.bad_state", "This claim is " + k.get("status").toString().replace('_', ' ')
                    + ", so that cannot be done now");
        }
    }

    private static void requireVersion(Map<String, Object> k, int version) {
        if (((Number) k.get("version")).intValue() != version) {
            throw new ApiException(409, "expense.stale", "The claim changed since you opened it. Reload and try again");
        }
    }

    private static void requireClaim(SessionCtx ctx) {
        if (!ctx.can("expense.claim") || ctx.membershipId() == null) {
            throw ApiException.denied("expense.claim");
        }
    }

    /** The claimant, an approver who reaches them, or finance. Anyone else gets "not found". */
    private boolean canSee(Connection c, SessionCtx ctx, UUID owner) throws SQLException {
        if (owner.equals(ctx.membershipId()) && ctx.can("expense.claim")) {
            return true;
        }
        if (ctx.can("expense.finance")) {
            return true;
        }
        String scope = ctx.scopeOf("expense.approve");
        return scope != null && !scope.equals("self") && Team.reaches(c, ctx, "expense.approve", owner, today());
    }

    private static Map<String, Object> category(Connection c, String id) throws SQLException {
        Map<String, Object> cat = id == null ? null : Sql.one(c, "select id, name, per_km_rate, active from app.expense_category where id = ?", Requests0.id(id));
        if (cat == null || !Boolean.TRUE.equals(cat.get("active"))) {
            throw ApiException.badRequest("Choose an expense category");
        }
        return cat;
    }

    /** The manager of a membership's active employment, or null when there is none. */
    private UUID managerOf(Connection c, UUID membershipId) throws SQLException {
        Map<String, Object> m = Sql.one(c,
                "select boss.membership_id from app.employment e "
                        + "join lateral (select j.manager_id from app.job_assignment j where j.employment_id = e.id "
                        + "  and j.effective_from <= ? order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                        + "join app.employment boss on boss.id = cur.manager_id "
                        + "where e.membership_id = ? and e.status = 'active' and boss.status = 'active' and boss.membership_id is not null limit 1",
                today(), membershipId);
        return m == null ? null : AuthService.uuid(m.get("membershipId"));
    }

    private static boolean hasWarnings(Connection c, UUID claimId) throws SQLException {
        return Sql.exists(c, "select 1 from app.expense_line where claim_id = ? and jsonb_array_length(flags) > 0", claimId);
    }

    private static void applyCuts(Connection c, UUID claimId, List<Cut> cuts) throws SQLException {
        if (cuts == null) {
            return;
        }
        for (Cut cut : cuts) {
            if (cut.amount() == null || cut.amount().signum() < 0 || cut.amount().scale() > 2) {
                throw ApiException.badRequest("A cut amount is zero or more, in rupees and paise");
            }
            if (blankToNull(cut.reason()) == null) {
                throw ApiException.badRequest("Say why a line is approved for less");
            }
            Map<String, Object> l = Sql.one(c, "select amount from app.expense_line where id = ? and claim_id = ?", Requests0.id(cut.lineId()), claimId);
            if (l == null) {
                throw ApiException.notFound("The line");
            }
            if (cut.amount().compareTo((BigDecimal) l.get("amount")) > 0) {
                throw ApiException.badRequest("A line cannot be approved for more than was claimed");
            }
            Sql.update(c, "update app.expense_line set approved_amount = ?, cut_reason = ? where id = ?",
                    cut.amount(), cut.reason().trim(), Requests0.id(cut.lineId()));
        }
    }

    private static Map<String, Object> decisionDetail(Map<String, Object> k, String why, List<Cut> cuts) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("number", k.get("number"));
        d.put("note", why);
        d.put("cuts", cuts == null ? 0 : cuts.size());
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String latestReview(Connection c, UUID lineId) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select status from app.evidence_review where line_id = ? order by reviewed_at desc, id desc limit 1", lineId);
        return r == null ? null : (String) r.get("status");
    }

    private static void touch(Connection c, UUID claimId) throws SQLException {
        Sql.update(c, "update app.expense_claim set updated_at = now() where id = ?", claimId);
    }

    private static Map<String, Object> flag(String code, String message) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("message", message);
        return f;
    }

    private static void positive(BigDecimal value, String what) {
        if (value != null && value.signum() <= 0) {
            throw ApiException.badRequest(what + " must be above zero, or left empty for none");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Object[] concat(Object first, Object[] middle, Object... last) {
        List<Object> all = new ArrayList<>();
        if (first != null) {
            all.add(first);
        }
        all.addAll(java.util.Arrays.asList(middle));
        all.addAll(java.util.Arrays.asList(last));
        return all.toArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** Parses an id from a request, 400 when it is not one. */
    static final class Requests0 {
        private Requests0() {}

        static UUID id(String s) {
            try {
                return UUID.fromString(s);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("That is not a valid id");
            }
        }
    }
}

package com.nexvance.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import com.nexvance.loginapp.core.ApiException;
import com.nexvance.loginapp.core.AuthService;
import com.nexvance.loginapp.core.Crypto;
import com.nexvance.loginapp.core.Db;
import com.nexvance.loginapp.core.EmployeeService;
import com.nexvance.loginapp.core.EmployeeService.NewEmployee;
import com.nexvance.loginapp.core.ExpensePayouts;
import com.nexvance.loginapp.core.ExpenseService;
import com.nexvance.loginapp.core.LoanService;
import com.nexvance.loginapp.core.ExpenseService.CategoryInput;
import com.nexvance.loginapp.core.ExpenseService.Cut;
import com.nexvance.loginapp.core.ExpenseService.LineInput;
import com.nexvance.loginapp.core.InvitationService;
import com.nexvance.loginapp.core.Migrator;
import com.nexvance.loginapp.core.PlatformService;
import com.nexvance.loginapp.core.SessionCtx;
import com.nexvance.loginapp.core.Sql;
import com.nexvance.loginapp.core.UserService;

/**
 * Expense claims against a real PostgreSQL (docs/expenses/design.md, stages X1 and X2): the status machine,
 * policy checks at submission, per-item evidence review, and who may see and decide what.
 *
 * Needs PostgreSQL as described in {@link UserModuleTest}; skipped when it cannot be reached.
 */
class ExpensesTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ROLE_PASSWORD = "test-role-password";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static Db db;
    private static AuthService auth;
    private static UserService users;
    private static InvitationService invitations;
    private static PlatformService platform;
    private static EmployeeService employees;
    private static ExpenseService expenses;
    private static ExpensePayouts payouts;
    private static LoanService loans;
    private static Crypto crypto;
    private static SessionCtx superAdmin;

    /** One Organization: a manager with one report, a finance user, and someone with no manager. */
    private record Setup(UUID orgId, SessionCtx admin, SessionCtx finance, SessionCtx manager, SessionCtx emp, SessionCtx solo,
                         UUID meals, UUID travel, UUID conveyance) {}

    @BeforeAll
    static void startDatabase() throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));   // as the application does
        String host = env("APP_TEST_DB_HOST", "localhost:5432");
        String ownerUser = env("APP_DB_OWNER_USER", "postgres");
        String ownerPassword = env("APP_DB_OWNER_PASSWORD", "postgres");
        boolean reachable;
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + host + "/postgres", ownerUser, ownerPassword);
             Statement st = c.createStatement()) {
            st.execute("drop database if exists hrms_test_expenses with (force)");
            st.execute("create database hrms_test_expenses");
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        if (!reachable && "true".equals(System.getenv("APP_TEST_DB_REQUIRED"))) {
            throw new IllegalStateException("PostgreSQL is not reachable on " + host + " and APP_TEST_DB_REQUIRED is set");
        }
        assumeTrue(reachable, "PostgreSQL is not reachable on " + host + "; skipping the expense tests");
        String url = "jdbc:postgresql://" + host + "/hrms_test_expenses";
        db = new Db(source(url, ownerUser, ownerPassword), source(url, "app_platform", ROLE_PASSWORD),
                source(url, "app_auth", ROLE_PASSWORD), source(url, "app_tenant", ROLE_PASSWORD));
        Migrator.migrate(db, ROLE_PASSWORD);
        Migrator.migrate(db, ROLE_PASSWORD);   // a second run must change nothing
        crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        auth = new AuthService(db);
        users = new UserService(db);
        invitations = new InvitationService(db);
        platform = new PlatformService(db);
        employees = new EmployeeService(db, crypto);
        expenses = new ExpenseService(db, crypto);
        payouts = new ExpensePayouts(db, crypto);
        loans = new LoanService(db, expenses);
        platform.ensureFirstSuperAdmin("root@platform.test", PASSWORD);
        superAdmin = auth.resolve(auth.enterConsole(auth.resolve(auth.login("root@platform.test", PASSWORD))));
    }

    // ── the whole route ─────────────────────────────────────────────────

    @Test
    void aClaimGoesFromDraftThroughManagerAndFinanceToVerified() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Client visit");
        line(s.emp, claim, s.meals, 3, "300.00");
        UUID train = line(s.emp, claim, s.travel, 3, "3200.00");

        // The train fare needs a receipt and proof of travel; the meal is under the receipt amount.
        ApiException blocked = assertThrows(ApiException.class, () -> expenses.submit(s.emp, claim, version(s.emp, claim)));
        assertEquals("expense.cannot_submit", blocked.code());
        assertTrue(blocked.getMessage().contains("needs a receipt"), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("proof of travel"), blocked.getMessage());
        expenses.uploadEvidence(s.emp, train, "receipt", "ticket.pdf", pdf("train"), null);
        expenses.uploadEvidence(s.emp, train, "travel_proof", "boarding.pdf", pdf("boarding"), null);

        ExpenseService.Submitted sent = expenses.submit(s.emp, claim, version(s.emp, claim));
        assertEquals("submitted", sent.status());
        assertEquals(1, expenses.approvals(s.manager).size());
        assertTrue(expenses.approvals(s.manager).get(0).get("claimant").toString().contains("Esha"));

        // Only the manager (not finance, not the claimant) decides this step.
        assertError(403, "permission.denied", () -> expenses.approvals(s.emp));
        assertError(403, "permission.denied", () -> expenses.decide(s.finance, claim, "approve", null, version(s.emp, claim), null));
        expenses.decide(s.manager, claim, "approve", "Agreed", version(s.emp, claim), null);
        assertEquals("approved", status(s.emp, claim));

        // Finance cannot verify until the train fare's evidence is accepted.
        assertEquals(1, expenses.verifyQueue(s.finance).size());
        assertError(409, "expense.evidence_open", () -> expenses.verify(s.finance, claim, version(s.emp, claim), null));
        expenses.reviewEvidence(s.finance, train, "accepted", null, version(s.emp, claim));
        expenses.verify(s.finance, claim, version(s.emp, claim), "Checked against the ticket");
        assertEquals("verified", status(s.emp, claim));
        assertEquals(0, new BigDecimal("3500.00").compareTo((BigDecimal) expenses.myClaims(s.emp).get(0).get("payable")));
        assertTrue(expenses.verifyQueue(s.finance).isEmpty());

        // A verified claim is closed to its owner, and every step was audited.
        assertError(409, "expense.bad_state", () -> line(s.emp, claim, s.meals, 2, "100.00"));
        assertError(409, "expense.cannot_withdraw", () -> expenses.withdraw(s.emp, claim, version(s.emp, claim)));
        List<String> actions = actions(s);
        assertTrue(actions.containsAll(List.of("expense.submitted", "expense.approve", "expense.evidence_accepted", "expense.verified")), actions.toString());
    }

    @Test
    void aNewOrganizationStartsWithFiveCategoriesAndOnlyOnce() {
        String slug = "exp-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        List<Map<String, Object>> first = expenses.categories(admin, true);
        assertEquals(List.of("Internet and phone", "Local conveyance", "Meals", "Other", "Travel"),
                first.stream().map(c -> (String) c.get("name")).toList());
        UUID meals = UUID.fromString(String.valueOf(first.stream().filter(c -> "Meals".equals(c.get("name"))).findFirst().orElseThrow().get("id")));
        expenses.saveCategory(admin, meals, new CategoryInput("Meals", null, null, new BigDecimal("300"), null, false, false, "Staff welfare", false));
        assertEquals(5, expenses.categories(admin, true).size(), "turning one off does not bring it back, or add a sixth");
        assertEquals(4, expenses.categories(admin, false).size());
    }

    @Test
    void aClaimWithNoManagerGoesStraightToFinance() {
        Setup s = setup();
        UUID claim = claim(s.solo, "Stationery");
        line(s.solo, claim, s.meals, 1, "200.00");
        assertEquals("approved", expenses.submit(s.solo, claim, version(s.solo, claim)).status());
        assertEquals(1, expenses.verifyQueue(s.finance).size());
        expenses.verify(s.finance, claim, version(s.solo, claim), null);
        assertEquals("verified", status(s.solo, claim));
    }

    // ── policy at submission ────────────────────────────────────────────

    @Test
    void theSubmissionWindowAndFutureDatesAreRefusedWithTheReason() {
        Setup s = setup();
        UUID old = claim(s.emp, "Old");
        line(s.emp, old, s.meals, 90, "200.00");
        ApiException e = assertThrows(ApiException.class, () -> expenses.submit(s.emp, old, version(s.emp, old)));
        assertTrue(e.getMessage().contains("older than 60 days"), e.getMessage());
        UUID future = claim(s.emp, "Future");
        line(s.emp, future, s.meals, -2, "200.00");
        ApiException f = assertThrows(ApiException.class, () -> expenses.submit(s.emp, future, version(s.emp, future)));
        assertTrue(f.getMessage().contains("future"), f.getMessage());
        // the sixtieth day is still inside
        UUID edge = claim(s.emp, "Edge");
        line(s.emp, edge, s.meals, 60, "200.00");
        assertEquals("submitted", expenses.submit(s.emp, edge, version(s.emp, edge)).status());
    }

    @Test
    void overTheLimitNeedsAReasonAndRaisesAWarningThatTheApproverMustAnswer() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Team lunch");
        UUID big = expenses.addLine(s.emp, claim, new LineInput(s.meals.toString(), TODAY.minusDays(2).toString(),
                new BigDecimal("1400.00"), null, "Cafe", null, null)).id();
        expenses.uploadEvidence(s.emp, big, "receipt", "bill.pdf", pdf("lunch"), null);
        ApiException e = assertThrows(ApiException.class, () -> expenses.submit(s.emp, claim, version(s.emp, claim)));
        assertTrue(e.getMessage().contains("over the limit of ₹1000"), e.getMessage());
        db.inTenant(s.orgId, c -> Sql.update(c, "update app.expense_line set over_limit_reason = 'Client was with us' where id = ?", big));
        expenses.submit(s.emp, claim, version(s.emp, claim));

        ExpenseService.ClaimView shown = expenses.claim(s.manager, claim);
        List<?> flags = (List<?>) ((Map<?, ?>) ((List<?>) shown.lines()).get(0)).get("flags");
        assertEquals(1, flags.size());
        // approving a flagged claim needs a comment; a cut needs its own reason and can never exceed the claim
        assertError(400, "request.invalid", () -> expenses.decide(s.manager, claim, "approve", null, version(s.emp, claim), null));
        assertError(400, "request.invalid", () -> expenses.decide(s.manager, claim, "approve", "Fine", version(s.emp, claim),
                List.of(new Cut(big.toString(), new BigDecimal("1000"), " "))));
        assertError(400, "request.invalid", () -> expenses.decide(s.manager, claim, "approve", "Fine", version(s.emp, claim),
                List.of(new Cut(big.toString(), new BigDecimal("1500"), "More?"))));
        expenses.decide(s.manager, claim, "approve", "Client lunch, fine", version(s.emp, claim),
                List.of(new Cut(big.toString(), new BigDecimal("1000"), "Limit is 1000")));
        ExpenseService.ClaimView done = expenses.claim(s.emp, claim);
        assertEquals(0, new BigDecimal("1000").compareTo((BigDecimal) ((Map<?, ?>) ((List<?>) done.lines()).get(0)).get("approvedAmount")));
    }

    @Test
    void distanceIsPaidByTheKmAndTheMonthAndDuplicatesAreFlagged() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Site visits");
        ExpenseService.LineAdded km = expenses.addLine(s.emp, claim, new LineInput(s.conveyance.toString(), TODAY.minusDays(1).toString(),
                new BigDecimal("1.00"), new BigDecimal("10.0"), null, null, null));
        assertEquals(0, new BigDecimal("120.00").compareTo(km.amount()), "the typed amount is ignored: 10 km x 12");
        assertError(400, "request.invalid", () -> expenses.addLine(s.emp, claim, new LineInput(s.conveyance.toString(),
                TODAY.toString(), new BigDecimal("50"), null, null, null, null)));

        // the same meal twice in two claims, and the month's total over the monthly limit of 3000
        UUID first = claim(s.emp, "First");
        for (int i = 0; i < 4; i++) {
            line(s.emp, first, s.meals, 5, "800.00");
            line(s.emp, first, s.meals, 6, "800.00");
        }
        // receipts are needed above 500; use declarations (allowed up to 2000 a line)
        attachDeclarations(s.emp, first);
        expenses.submit(s.emp, first, version(s.emp, first));
        UUID second = claim(s.emp, "Second");
        UUID again = line(s.emp, second, s.meals, 5, "800.00");
        expenses.uploadEvidence(s.emp, again, "missing_receipt_declaration", null, null, "Lost the bill at the airport");
        expenses.submit(s.emp, second, version(s.emp, second));
        ExpenseService.ClaimView shown = expenses.claim(s.manager, second);
        String codes = ((List<?>) ((Map<?, ?>) ((List<?>) shown.lines()).get(0)).get("flags")).toString();
        assertTrue(codes.contains("possible_duplicate"), codes);
        assertTrue(codes.contains("over_month_limit"), codes);
    }

    // ── evidence, line by line ──────────────────────────────────────────

    @Test
    void moreEvidencePausesTheClaimAndRejectedEvidenceCutsTheLineToZero() {
        Setup s = setup();
        UUID claim = claim(s.solo, "Conference");
        UUID good = line(s.solo, claim, s.travel, 4, "2000.00");
        UUID bad = line(s.solo, claim, s.travel, 4, "900.00");
        for (UUID l : List.of(good, bad)) {
            expenses.uploadEvidence(s.solo, l, "receipt", "r.pdf", pdf("r" + l), null);
            expenses.uploadEvidence(s.solo, l, "travel_proof", "p.pdf", pdf("p" + l), null);
        }
        expenses.submit(s.solo, claim, version(s.solo, claim));
        expenses.reviewEvidence(s.finance, good, "accepted", null, version(s.solo, claim));
        assertError(400, "request.invalid", () -> expenses.reviewEvidence(s.finance, bad, "more_needed", "", version(s.solo, claim)));
        expenses.reviewEvidence(s.finance, bad, "more_needed", "The date is cut off", version(s.solo, claim));
        assertEquals("evidence_requested", status(s.solo, claim));

        // the employee must add a file to the marked line before sending it back; the other line stays accepted
        assertError(409, "expense.evidence_missing", () -> expenses.sendBack(s.solo, claim, version(s.solo, claim)));
        expenses.uploadEvidence(s.solo, bad, "receipt", "r2.pdf", pdf("again"), null);
        expenses.sendBack(s.solo, claim, version(s.solo, claim));
        assertEquals("approved", status(s.solo, claim));

        expenses.reviewEvidence(s.finance, bad, "rejected", "Not a bill for this trip", version(s.solo, claim));
        ExpenseService.ClaimView shown = expenses.claim(s.solo, claim);
        Map<?, ?> cut = ((List<?>) shown.lines()).stream().map(x -> (Map<?, ?>) x)
                .filter(m -> m.get("id").toString().equals(bad.toString())).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) cut.get("approvedAmount")));
        assertTrue(cut.get("cutReason").toString().contains("Evidence rejected"));
        expenses.verify(s.finance, claim, version(s.solo, claim), null);
        assertEquals(0, new BigDecimal("2000.00").compareTo((BigDecimal) expenses.myClaims(s.solo).get(0).get("payable")));
    }

    @Test
    void aMissingReceiptStatementIsAcceptedOnlyUpToTheLimitAndNeedsSomeoneOtherThanTheManagerToVerify() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Taxi");
        UUID big = line(s.emp, claim, s.meals, 2, "2500.00");
        assertError(409, "expense.declaration_limit", () -> expenses.uploadEvidence(s.emp, big, "missing_receipt_declaration", null, null, "Lost it on the train"));
        assertError(400, "request.invalid", () -> expenses.uploadEvidence(s.emp, big, "missing_receipt_declaration", null, null, "lost"));
        UUID small = line(s.emp, claim, s.meals, 2, "900.00");
        expenses.uploadEvidence(s.emp, small, "missing_receipt_declaration", null, null, "Lost it on the train");
        db.inTenant(s.orgId, c -> Sql.update(c, "delete from app.expense_line where id = ?", big));
        expenses.submit(s.emp, claim, version(s.emp, claim));
        expenses.decide(s.manager, claim, "approve", "Known to me", version(s.emp, claim), null);
        // the finance user is not the manager here, so this passes; the rule bites when one person holds both roles
        db.inTenant(s.orgId, c -> Sql.update(c, "update app.expense_claim set manager_by = ? where id = ?", s.finance.membershipId(), claim));
        expenses.reviewEvidence(s.finance, small, "accepted", null, version(s.emp, claim));
        assertError(409, "expense.same_person", () -> expenses.verify(s.finance, claim, version(s.emp, claim), null));
        expenses.verify(s.admin, claim, version(s.emp, claim), null);
        assertEquals("verified", status(s.emp, claim));
    }


    @Test
    void aReceiptCanBeDownloadedAndASubmittedClaimCannotBeDeleted() {
        Setup s = setup();
        UUID claim = claim(s.solo, "Download");
        UUID l = line(s.solo, claim, s.travel, 2, "800.00");
        byte[] bytes = pdf("the receipt");
        UUID file = expenses.uploadEvidence(s.solo, l, "receipt", "bill.pdf", bytes, null).id();
        expenses.uploadEvidence(s.solo, l, "travel_proof", "p.pdf", pdf("proof"), null);
        expenses.submit(s.solo, claim, version(s.solo, claim));
        assertTrue(java.util.Arrays.equals(bytes, expenses.file(s.finance, file).bytes()));
        assertEquals("bill.pdf", expenses.file(s.solo, file).fileName());
        assertError(404, "not_found", () -> expenses.file(s.emp, file));
        // the database keeps a submitted claim, its lines and its files, whoever asks
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c, "delete from app.expense_claim where id = ?", claim)));
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c, "delete from app.expense_line where id = ?", l)));
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c, "delete from app.evidence_file where id = ?", file)));
        // reviews are a history: never changed
        expenses.reviewEvidence(s.finance, l, "accepted", null, version(s.solo, claim));
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c, "update app.evidence_review set status = 'rejected'")));
        // a draft deletes with everything under it
        UUID draft = claim(s.solo, "Draft");
        UUID dl = line(s.solo, draft, s.meals, 1, "100.00");
        expenses.uploadEvidence(s.solo, dl, "missing_receipt_declaration", null, null, "Lost it in the rain");
        expenses.deleteClaim(s.solo, draft);
        assertEquals(0L, db.inTenant(s.orgId, c -> Sql.count(c, "select 1 from app.evidence_file where line_id = ?", dl)).longValue());
    }

    @Test
    void newEvidenceAfterAcceptanceReopensTheLineAndStaleReviewsAreRefused() {
        Setup s = setup();
        UUID claim = claim(s.solo, "Swap");
        UUID a = line(s.solo, claim, s.travel, 3, "900.00");
        UUID b = line(s.solo, claim, s.travel, 3, "700.00");
        for (UUID l : List.of(a, b)) {
            expenses.uploadEvidence(s.solo, l, "receipt", "r.pdf", pdf("r" + l), null);
            expenses.uploadEvidence(s.solo, l, "travel_proof", "p.pdf", pdf("p" + l), null);
        }
        expenses.submit(s.solo, claim, version(s.solo, claim));
        int seen = version(s.solo, claim);
        expenses.reviewEvidence(s.finance, a, "accepted", null, seen);
        assertError(409, "expense.stale", () -> expenses.reviewEvidence(s.finance, b, "accepted", null, seen));
        expenses.reviewEvidence(s.finance, b, "more_needed", "Blurred", version(s.solo, claim));
        // files go only on the line finance asked about
        assertError(409, "expense.not_requested", () -> expenses.uploadEvidence(s.solo, a, "receipt", "x.pdf", pdf("swapped"), null));
        expenses.uploadEvidence(s.solo, b, "receipt", "r3.pdf", pdf("clear"), null);
        expenses.sendBack(s.solo, claim, version(s.solo, claim));
        // b has a newer file than its review, so it is open until finance looks again
        assertError(409, "expense.evidence_open", () -> expenses.verify(s.finance, claim, version(s.solo, claim), null));
        expenses.reviewEvidence(s.finance, b, "accepted", null, version(s.solo, claim));
        expenses.verify(s.finance, claim, version(s.solo, claim), null);
    }

    @Test
    void whatTheCategoryRequiredWhenSubmittedHoldsEvenIfSetupChangesLater() {
        Setup s = setup();
        UUID claim = claim(s.solo, "Frozen rules");
        UUID l = line(s.solo, claim, s.travel, 2, "3200.00");
        expenses.uploadEvidence(s.solo, l, "receipt", "r.pdf", pdf("fare"), null);
        expenses.uploadEvidence(s.solo, l, "travel_proof", "p.pdf", pdf("pass"), null);
        expenses.submit(s.solo, claim, version(s.solo, claim));
        expenses.saveCategory(s.finance, s.travel, new CategoryInput("Travel", null, null, new BigDecimal("50000"), null, false, false, "Travel", true));
        assertError(409, "expense.evidence_open", () -> expenses.verify(s.finance, claim, version(s.solo, claim), null));
    }

    @Test
    void distanceMustBeInTenthsOfAKm() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Km");
        assertError(400, "request.invalid", () -> expenses.addLine(s.emp, claim, new LineInput(s.conveyance.toString(), TODAY.toString(),
                BigDecimal.ONE, new BigDecimal("10.25"), null, null, null)));
    }


    @Test
    void aDesignatedApproverReplacesTheManagerAndTheManagerStepCanBeSwitchedOff() {
        Setup s = setup();
        SessionCtx other = join(s.admin, "other-" + SEQUENCE.get(), "other", List.of("manager", "employee"));
        // an approver must be able to approve; an employee cannot be one
        assertError(409, "expense.approver_cannot", () -> expenses.saveApprover(s.finance, s.emp.membershipId(), null, s.solo.membershipId()));
        assertError(409, "expense.own_claim", () -> expenses.saveApprover(s.finance, s.emp.membershipId(), null, s.emp.membershipId()));
        assertError(403, "permission.denied", () -> expenses.saveApprover(s.emp, s.emp.membershipId(), null, other.membershipId()));
        UUID rule = expenses.saveApprover(s.finance, s.emp.membershipId(), null, other.membershipId()).id();
        assertEquals(1, expenses.approvers(s.finance).rules().size());

        UUID claim = claim(s.emp, "Routed");
        line(s.emp, claim, s.meals, 1, "200.00");
        expenses.submit(s.emp, claim, version(s.emp, claim));
        // the usual manager no longer sees it; the designated person and the Org Admin do
        assertTrue(expenses.approvals(s.manager).isEmpty());
        assertEquals(1, expenses.approvals(other).size());
        assertEquals(1, expenses.approvals(s.admin).size());
        assertError(404, "not_found", () -> expenses.decide(s.manager, claim, "approve", null, version(s.emp, claim), null));
        expenses.decide(other, claim, "approve", null, version(s.emp, claim), null);
        assertEquals("approved", status(s.emp, claim));

        // removing the rule sends the next claim to the manager again
        expenses.removeApprover(s.finance, rule);
        UUID next = claim(s.emp, "Back to manager");
        line(s.emp, next, s.meals, 1, "200.00");
        expenses.submit(s.emp, next, version(s.emp, next));
        assertEquals(1, expenses.approvals(s.manager).size());
    }

    @Test
    void aClaimOverTheSetAmountNeedsTwoDifferentFinanceApprovers() {
        Setup s = setup();
        SessionCtx second = join(s.admin, "second-" + SEQUENCE.get(), "fin2", List.of("finance"));
        new com.nexvance.loginapp.core.SettingsService(db).change(s.admin, "expenses.second_finance_above",
                new com.nexvance.loginapp.core.SettingsService.ChangeInput("organization", null, null, 1000, false, "Large claims need two", PASSWORD));
        UUID claim = claim(s.solo, "Large");
        UUID l = line(s.solo, claim, s.meals, 1, "900.00");
        expenses.uploadEvidence(s.solo, l, "receipt", "r.pdf", pdf("big"), null);
        UUID l2 = line(s.solo, claim, s.travel, 1, "400.00");
        expenses.uploadEvidence(s.solo, l2, "receipt", "r2.pdf", pdf("big2"), null);
        expenses.uploadEvidence(s.solo, l2, "travel_proof", "p2.pdf", pdf("big3"), null);
        expenses.submit(s.solo, claim, version(s.solo, claim));
        assertEquals(2, expenses.claim(s.solo, claim).financeApprovals());
        expenses.reviewEvidence(s.finance, l, "accepted", null, version(s.solo, claim));
        expenses.reviewEvidence(s.finance, l2, "accepted", null, version(s.solo, claim));
        expenses.verify(s.finance, claim, version(s.solo, claim), null);
        assertEquals("approved", status(s.solo, claim));
        assertTrue(expenses.claim(s.solo, claim).firstVerified());
        assertError(409, "expense.second_person", () -> expenses.verify(s.finance, claim, version(s.solo, claim), null));
        expenses.verify(second, claim, version(s.solo, claim), null);
        assertEquals("verified", status(s.solo, claim));
    }

    // ── who may do what ─────────────────────────────────────────────────

    @Test
    void nobodyDecidesOrVerifiesTheirOwnClaimAndTheDatabaseAgrees() {
        Setup s = setup();
        UUID claim = claim(s.finance, "Finance lead's own");
        line(s.finance, claim, s.meals, 1, "200.00");
        expenses.submit(s.finance, claim, version(s.finance, claim));
        assertError(409, "expense.own_claim", () -> expenses.verify(s.finance, claim, version(s.finance, claim), null));
        assertError(409, "expense.own_claim", () -> expenses.financeReject(s.finance, claim, version(s.finance, claim), "No"));
        // the database refuses it even if the application did not
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c,
                "update app.expense_claim set finance_by = membership_id where id = ?", claim)));
        expenses.verify(s.admin, claim, version(s.finance, claim), null);
    }

    @Test
    void claimsAreInvisibleToOthersAndStaleVersionsAreRefused() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Private");
        line(s.emp, claim, s.meals, 1, "100.00");
        // a colleague cannot see it; the manager of the claimant and finance can
        assertError(404, "not_found", () -> expenses.claim(s.solo, claim));
        assertEquals("draft", expenses.claim(s.manager, claim).status());
        assertEquals("draft", expenses.claim(s.finance, claim).status());
        // a draft is not in anyone's queue, and a manager of nobody sees no one's claims
        assertTrue(expenses.approvals(s.manager).isEmpty());
        assertTrue(expenses.approvals(s.admin).isEmpty());
        assertError(403, "permission.denied", () -> expenses.verifyQueue(s.emp));
        // a change to a claim someone else holds is "not found"
        assertError(404, "not_found", () -> expenses.deleteClaim(s.solo, claim));
        assertError(404, "not_found", () -> expenses.submit(s.manager, claim, 1));
    }

    @Test
    void anotherOrganizationNeverSeesAClaimOrACategory() {
        Setup a = setup();
        Setup b = setup();
        UUID claim = claim(a.emp, "Org A only");
        assertError(404, "not_found", () -> expenses.claim(b.admin, claim));
        assertError(404, "not_found", () -> expenses.claim(b.finance, claim));
        assertEquals(0L, db.inTenant(b.orgId, c -> Sql.count(c, "select 1 from app.expense_claim where id = ?", claim)).longValue());
        assertError(404, "not_found", () -> expenses.addLine(b.emp, claim, new LineInput(a.meals.toString(), TODAY.toString(),
                BigDecimal.TEN, null, null, null, null)));
        assertError(400, "request.invalid", () -> expenses.addLine(b.emp, claim(b.emp, "B"), new LineInput(a.meals.toString(),
                TODAY.toString(), BigDecimal.TEN, null, null, null, null)));   // A's category does not exist in B
    }

    @Test
    void withdrawReturnReopenAndTheVersionGuard() {
        Setup s = setup();
        UUID claim = claim(s.emp, "Changing my mind");
        line(s.emp, claim, s.meals, 1, "100.00");
        int v = version(s.emp, claim);
        expenses.submit(s.emp, claim, v);
        assertError(409, "expense.stale", () -> expenses.withdraw(s.emp, claim, v));         // the version moved on submit
        expenses.decide(s.manager, claim, "return", "Add what it was for", version(s.emp, claim), null);
        assertEquals("returned", status(s.emp, claim));
        assertEquals("Add what it was for", expenses.myClaims(s.emp).get(0).get("decisionNote"));
        assertError(404, "not_found", () -> expenses.decide(s.manager, claim, "reject", "x", version(s.emp, claim), null));   // no longer submitted
        expenses.reopen(s.emp, claim, version(s.emp, claim));
        assertEquals("draft", status(s.emp, claim));
        expenses.submit(s.emp, claim, version(s.emp, claim));
        expenses.withdraw(s.emp, claim, version(s.emp, claim));
        assertEquals("withdrawn", status(s.emp, claim));
        // a rejected or returned claim must say why, in the database too
        assertThrows(RuntimeException.class, () -> db.inTenant(s.orgId, c -> Sql.update(c,
                "update app.expense_claim set status = 'rejected', decision_note = null where id = ?", claim)));
    }

    @Test
    void categoriesAreSetUpByFinanceAndNamesAreUnique() {
        Setup s = setup();
        assertError(403, "permission.denied", () -> expenses.saveCategory(s.emp, null, cat("Gifts", null)));
        assertError(409, "expense.category_exists", () -> expenses.saveCategory(s.finance, null, cat("meals", null)));
        assertError(400, "request.invalid", () -> expenses.saveCategory(s.finance, null, cat(" ", null)));
        UUID gifts = expenses.saveCategory(s.finance, null, cat("Gifts", new BigDecimal("2500"))).id();
        expenses.saveCategory(s.finance, gifts, new CategoryInput("Gifts", new BigDecimal("2500"), null, new BigDecimal("500"), null,
                false, false, null, false));
        assertFalse(expenses.categories(s.emp, false).stream().anyMatch(c -> "Gifts".equals(c.get("name"))), "inactive categories are not offered");
        assertTrue(expenses.categories(s.finance, true).stream().anyMatch(c -> "Gifts".equals(c.get("name"))));
        assertError(403, "permission.denied", () -> expenses.categories(s.emp, true));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    // ── payout by bank file ─────────────────────────────────────────────

    @Test
    void verifiedClaimsArePaidByABatchAndItsBankFile() {
        Setup s = setup();
        UUID claim = verified(s, s.solo, "Stationery", "450.00");
        UUID mine = claim(s.finance, "Finance's own");
        line(s.finance, mine, s.meals, 1, "200.00");
        expenses.submit(s.finance, mine, version(s.finance, mine));
        expenses.verify(s.admin, mine, version(s.finance, mine), null);

        assertEquals(2, payouts.payable(s.finance).size());
        assertFalse((Boolean) payouts.payable(s.finance).stream().filter(p -> claim.equals(UUID.fromString(p.get("id").toString()))).findFirst().orElseThrow().get("hasBank"));
        assertError(403, "permission.denied", () -> payouts.payable(s.emp));
        assertError(409, "expense.no_bank", () -> payouts.createBatch(s.finance, List.of(claim)));
        assertError(403, "expense.own_claim", () -> payouts.createBatch(s.finance, List.of(mine)));
        assertError(400, "request.invalid", () -> payouts.createBatch(s.finance, List.of()));

        bank(s, "Sol Solo", "123456789012", "HDFC0001234");
        ExpensePayouts.BatchMade batch = payouts.createBatch(s.finance, List.of(claim));
        assertTrue(batch.number().startsWith("EXB-"), batch.number());
        assertEquals(0, new BigDecimal("450.00").compareTo(batch.total()));
        assertEquals("scheduled", status(s.solo, claim));
        assertError(409, "expense.not_payable", () -> payouts.createBatch(s.finance, List.of(claim)));
        assertEquals(1, payouts.payable(s.finance).size());

        String file = new String(payouts.bankFile(s.finance, batch.id()).bytes(), StandardCharsets.UTF_8);
        assertTrue(file.contains("123456789012") && file.contains("HDFC0001234") && file.contains("450.00"), file);
        assertTrue(file.contains("Expense claim " + expenses.claim(s.solo, claim).number()), file);

        assertError(400, "request.invalid", () -> payouts.markPaid(s.finance, batch.id(), new ExpensePayouts.PaidInput(null, " ")));
        payouts.markPaid(s.finance, batch.id(), new ExpensePayouts.PaidInput(null, "UTR123456"));
        ExpenseService.ClaimView seen = expenses.claim(s.solo, claim);
        assertEquals("paid", seen.status());
        assertEquals(batch.number(), seen.batchNumber());
        assertEquals("UTR123456", seen.bankReference());
        assertError(409, "expense.batch_state", () -> payouts.cancel(s.finance, batch.id()));
        assertError(409, "expense.batch_state", () -> payouts.markPaid(s.finance, batch.id(), new ExpensePayouts.PaidInput(null, "UTR999999")));
        assertTrue(actions(s).containsAll(List.of("expense.batch_created", "expense.bank_file_downloaded", "expense.batch_paid")), actions(s).toString());
    }

    @Test
    void cancellingABatchReturnsItsClaimsToVerified() {
        Setup s = setup();
        UUID claim = verified(s, s.solo, "Courier", "300.00");
        bank(s, "Sol Solo", "222233334444", "SBIN0004321");
        ExpensePayouts.BatchMade batch = payouts.createBatch(s.finance, List.of(claim));
        payouts.cancel(s.finance, batch.id());
        assertEquals("verified", status(s.solo, claim));
        assertError(409, "expense.batch_state", () -> payouts.bankFile(s.finance, batch.id()));
        ExpensePayouts.BatchMade again = payouts.createBatch(s.finance, List.of(claim));
        assertEquals("scheduled", status(s.solo, claim));
        assertEquals(1, payouts.batches(s.finance).stream().filter(b -> "open".equals(b.get("status"))).count());
        assertEquals(1, payouts.batchClaims(s.finance, again.id()).size());
    }

    @Test
    void aPersonWithNoApproverCanBeRefusedInsteadOfGoingToFinance() {
        Setup s = setup();
        UUID claim = claim(s.solo, "No boss");
        line(s.solo, claim, s.meals, 1, "200.00");
        new com.nexvance.loginapp.core.SettingsService(db).change(s.admin, "expenses.when_no_approver",
                new com.nexvance.loginapp.core.SettingsService.ChangeInput("organization", null, null, "refuse", false, "Leavers need an approver", PASSWORD));
        assertError(409, "expense.no_approver", () -> expenses.submit(s.solo, claim, version(s.solo, claim)));
        // a person who has a manager is unaffected
        UUID other = claim(s.emp, "Has boss");
        line(s.emp, other, s.meals, 1, "200.00");
        assertEquals("submitted", expenses.submit(s.emp, other, version(s.emp, other)).status());
    }

    // ── advances and loans ──────────────────────────────────────────────

    @Test
    void switchingAdvancesAndLoansOffStopsNewRequestsButNotRunningOnes() {
        Setup s = setup();
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Personal loan", "loan", new BigDecimal("100000"), 12,
                BigDecimal.ZERO, 0, 3, true)).id();
        LoanService.Created before = loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("9000"), 3, "Fees"));
        new com.nexvance.loginapp.core.SettingsService(db).change(s.admin, "loans.enabled",
                new com.nexvance.loginapp.core.SettingsService.ChangeInput("organization", null, null, false, false, "No loans this year", PASSWORD));
        assertError(409, "loan.off", () -> loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("9000"), 3, "More fees")));
        assertTrue(loans.types(s.solo, false).isEmpty(), "people see no types to choose from");
        assertEquals(1, loans.types(s.finance, true).size(), "finance still sees and manages the types");
        loans.financeDecide(s.finance, before.id(), "approve", null, 1);
        assertEquals("approved", loans.loan(s.solo, before.id()).status(), "a request already made can still be decided");
    }

    @Test
    void aLoanAboveTheLimitNeedsTwoDifferentPeopleInFinance() {
        Setup s = setup();
        new com.nexvance.loginapp.core.SettingsService(db).change(s.admin, "loans.second_finance_above",
                new com.nexvance.loginapp.core.SettingsService.ChangeInput("organization", null, null, 10000, false, "Large loans need two", PASSWORD));
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Personal loan", "loan", new BigDecimal("100000"), 12,
                BigDecimal.ZERO, 0, 3, true)).id();
        LoanService.Created small = loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("9000"), 3, "Fees"));
        loans.financeDecide(s.finance, small.id(), "approve", null, 1);
        assertEquals("approved", loans.loan(s.solo, small.id()).status(), "under the limit one approval is enough");

        LoanService.Created big = loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("20000"), 4, "Deposit"));
        assertEquals(2, loans.loan(s.solo, big.id()).financeApprovals());
        loans.financeDecide(s.finance, big.id(), "approve", null, 1);
        LoanService.LoanView afterFirst = loans.loan(s.admin, big.id());
        assertEquals("manager_approved", afterFirst.status(), "still with finance after the first approval");
        assertTrue(afterFirst.firstApproved());
        assertError(409, "loan.second_person", () -> loans.financeDecide(s.finance, big.id(), "approve", null, 2));
        assertError(403, "permission.denied", () -> loans.financeDecide(s.solo, big.id(), "approve", null, 2));
        assertError(409, "loan.state", () -> loans.disburse(s.admin, big.id(), TODAY.toString(), null, "UTR1234", 2));
        loans.financeDecide(s.admin, big.id(), "approve", null, 2);
        assertEquals("approved", loans.loan(s.solo, big.id()).status());

        // The first approver may still reject, with a reason.
        LoanService.Created other = loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("30000"), 4, "Trip"));
        loans.financeDecide(s.finance, other.id(), "approve", null, 1);
        loans.financeDecide(s.finance, other.id(), "reject", "Not this year", 2);
        assertEquals("rejected", loans.loan(s.solo, other.id()).status());
    }

    @Test
    void financeIsToldOfLoansWithInstalmentsCarriedForTwoMonths() {
        Setup s = setup();
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Advance", "salary_advance", new BigDecimal("9000"), 3,
                BigDecimal.ZERO, 0, 2, true)).id();
        UUID worker = s.emp.membershipId();
        LocalDate thisMonth = TODAY.withDayOfMonth(1);
        db.inTenant(s.orgId, c -> {
            for (int k = 0; k < 2; k++) {   // one with instalments from the last three months, one with this month and next only
                UUID loan = UUID.fromString(String.valueOf(Sql.one(c, "insert into app.loan (tenant_id, number, membership_id, type_id, amount, months, annual_rate, purpose, status, disbursed_on, first_month) "
                        + "values (?, ?, ?, ?, 3000, 3, 0, 'Medical', 'active', ?, ?) returning id", s.orgId, "LN-2026-80" + k, worker, type, thisMonth.minusMonths(4), thisMonth.minusMonths(3)).get("id")));
                for (int m = 3 - 3 * k; m >= 1 - 2 * k; m--) {
                    Sql.update(c, "insert into app.loan_instalment (tenant_id, loan_id, month, principal) values (?, ?, ?, 1000)", s.orgId, loan, thisMonth.minusMonths(m));
                }
            }
            return null;
        });
        int told = db.inTenant(s.orgId, c -> LoanService.alertCarried(c, s.orgId, null, thisMonth));
        assertEquals(1, told, "only the loan with an instalment from two or more months back");
        long mine = db.inTenant(s.orgId, c -> Sql.count(c, "select 1 from app.notification where membership_id = ? and event = 'loan_instalment_carried'", s.finance.membershipId()));
        assertEquals(1, mine);
        assertEquals(0, ((Number) db.inTenant(s.orgId, c -> Sql.count(c, "select 1 from app.notification where membership_id = ?", s.emp.membershipId()))).intValue(), "the borrower is not sent this");
    }

    @Test
    void peopleAreToldWhenSomethingWaitsForThemAndWhenTheirRequestMoves() {
        Setup s = setup();
        bank(s, "Esha Emp", "223456789012", "HDFC0001234");
        // A claim: manager hears, then finance, then the employee at verification and at payment.
        UUID claim = claim(s.emp, "Client visit");
        line(s.emp, claim, s.meals, 3, "300.00");
        expenses.submit(s.emp, claim, version(s.emp, claim));
        assertEquals(List.of("expense_waiting"), told(s, s.manager));
        expenses.decide(s.manager, claim, "approve", "Agreed", version(s.emp, claim), null);
        assertEquals(List.of("expense_finance_waiting"), told(s, s.finance));
        expenses.verify(s.finance, claim, version(s.emp, claim), null);
        assertEquals(List.of("expense_verified"), told(s, s.emp));
        ExpensePayouts.BatchMade batch = payouts.createBatch(s.finance, List.of(claim));
        payouts.markPaid(s.finance, batch.id(), new ExpensePayouts.PaidInput(null, "UTR123456"));
        assertEquals(List.of("expense_verified", "expense_paid"), told(s, s.emp));
        assertTrue(notes(s, s.emp).get(1).contains("EXP-"), "the text names the claim");

        // A returned claim says why.
        UUID again = claim(s.emp, "Taxi");
        line(s.emp, again, s.meals, 2, "150.00");
        expenses.submit(s.emp, again, version(s.emp, again));
        expenses.decide(s.manager, again, "return", "Attach the bill", version(s.emp, again), null);
        assertTrue(notes(s, s.emp).stream().anyMatch(n -> n.contains("Attach the bill")));

        // A loan: the manager, finance, then the employee at the decision and the payout.
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Advance", "salary_advance", new BigDecimal("9000"), 3,
                BigDecimal.ZERO, 0, 2, true)).id();
        LoanService.Created ln = loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("3000"), 3, "Rent"));
        assertTrue(told(s, s.manager).contains("loan_waiting"));
        loans.managerDecide(s.manager, ln.id(), "approve", null, 1);
        assertTrue(told(s, s.finance).contains("loan_finance_waiting"));
        loans.financeDecide(s.finance, ln.id(), "approve", null, 2);
        assertTrue(told(s, s.emp).contains("loan_decided"));
        loans.disburse(s.finance, ln.id(), TODAY.toString(), null, "UTR778899", 3);
        assertTrue(told(s, s.emp).contains("loan_paid_out"));
        assertTrue(notes(s, s.emp).stream().anyMatch(n -> n.contains("approved")));
    }

    @Test
    void everyExpenseMessageCanBeSavedAsItIsInSetup() {
        Setup s = setup();
        // The messages are written the first time one is sent; a claim sent and approved writes the claim ones, a request the loan ones.
        UUID claim = claim(s.emp, "Messages");
        line(s.emp, claim, s.meals, 1, "200.00");
        expenses.submit(s.emp, claim, version(s.emp, claim));
        com.nexvance.loginapp.core.Notifications notifications = new com.nexvance.loginapp.core.Notifications(db);
        List<Map<String, Object>> expenseMessages = notifications.messages(s.admin).stream()
                .filter(m -> String.valueOf(m.get("event")).startsWith("expense_") || String.valueOf(m.get("event")).startsWith("loan_")).toList();
        assertTrue(expenseMessages.size() >= 5, "the expense messages are listed in Setup");
        for (Map<String, Object> m : expenseMessages) {
            notifications.saveMessage(s.admin, UUID.fromString(String.valueOf(m.get("id"))),
                    new com.nexvance.loginapp.core.Notifications.MessageInput((String) m.get("subject"), (String) m.get("body"), false));
        }
    }

    private static List<String> told(Setup s, SessionCtx who) {
        return db.inTenant(s.orgId, c -> Sql.query(c, "select event from app.notification where membership_id = ? order by created_at, id", who.membershipId()))
                .stream().map(r -> (String) r.get("event")).toList();
    }

    private static List<String> notes(Setup s, SessionCtx who) {
        return db.inTenant(s.orgId, c -> Sql.query(c, "select body from app.notification where membership_id = ? order by created_at, id", who.membershipId()))
                .stream().map(r -> (String) r.get("body")).toList();
    }

    @Test
    void financeMarksALoanExemptFromThePerquisiteAndCanTakeTheMarkOff() {
        Setup s = setup();
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Medical loan", "loan", new BigDecimal("500000"), 12, BigDecimal.ZERO, 0, 1, true)).id();
        LoanService.Created ln = loans.request(s.solo, new LoanService.RequestInput(type.toString(), new BigDecimal("300000"), 12, "Surgery"));
        loans.financeDecide(s.finance, ln.id(), "approve", null, 1);
        assertError(403, "permission.denied", () -> loans.exempt(s.solo, ln.id(), "Treatment of a specified illness"));
        assertError(400, "request.invalid", () -> loans.exempt(s.finance, ln.id(), "short"));
        loans.exempt(s.finance, ln.id(), "Treatment of a specified illness; hospital letter in the loan file");
        assertTrue(loans.loan(s.finance, ln.id()).perqExemptReason().startsWith("Treatment"));
        loans.exempt(s.finance, ln.id(), "");
        assertEquals(null, loans.loan(s.finance, ln.id()).perqExemptReason());
    }

    @Test
    void theDailyDigestTellsWhoeverHasKeptAClaimOrLoanWaitingThreeDaysAndOnlyOnceADay() {
        Setup s = setup();
        com.nexvance.loginapp.core.ExpenseDigest digest = new com.nexvance.loginapp.core.ExpenseDigest(db, expenses);
        UUID claim = claim(s.emp, "Client visit");
        line(s.emp, claim, s.meals, 3, "300.00");
        expenses.submit(s.emp, claim, version(s.emp, claim));
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Advance", "salary_advance", new BigDecimal("9000"), 3, BigDecimal.ZERO, 0, 2, true)).id();
        loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("3000"), 3, "Rent"));
        assertEquals(0, digest.runFor(s.orgId), "nothing has waited three days yet");

        db.inTenant(s.orgId, c -> Sql.update(c, "update app.expense_claim set submitted_at = now() - interval '4 days' where id = ?", claim)
                + Sql.update(c, "update app.loan set created_at = now() - interval '5 days'"));
        assertEquals(1, digest.runFor(s.orgId));
        String body = notes(s, s.manager).get(notes(s, s.manager).size() - 1);
        assertTrue(body.contains("1 expense claim and 1 loan request have been waiting"), body);
        assertEquals(0, digest.runFor(s.orgId), "once a day");

        // With finance for three days: everyone in finance hears, the manager no longer does.
        expenses.decide(s.manager, claim, "approve", "Agreed", version(s.emp, claim), null);
        db.inTenant(s.orgId, c -> Sql.update(c, "update app.expense_claim set manager_at = now() - interval '3 days 1 hour' where id = ?", claim));
        db.inOwner(c -> Sql.update(c, "delete from app.notification where event = 'expense_digest'"));   // as if a day had passed
        digest.runFor(s.orgId);
        assertTrue(told(s, s.finance).contains("expense_digest"));
        assertTrue(notes(s, s.finance).stream().anyMatch(n -> n.contains("1 expense claim has been waiting")));
    }

    @Test
    void anExpenseAdvanceIsSettledByClaimsAndOnlyTheRestOfAClaimIsPaid() {
        Setup s = setup();
        bank(s, "Esha Emp", "223456789012", "HDFC0001234");
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Travel advance", "expense_advance", new BigDecimal("30000"), 3, BigDecimal.ZERO, 0, 2, true)).id();
        assertError(400, "request.invalid", () -> loans.saveType(s.finance, null,
                new LoanService.TypeInput("Bad", "expense_advance", new BigDecimal("30000"), 3, new BigDecimal("5"), 0, 2, true)));
        LoanService.Created adv = loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("1000"), 2, "Trip to Nashik"));
        loans.managerDecide(s.manager, adv.id(), "approve", null, 1);
        loans.financeDecide(s.finance, adv.id(), "approve", null, 2);
        loans.disburse(s.finance, adv.id(), TODAY.toString(), null, "UTR445566", 3);
        LoanService.LoanView paidOut = loans.loan(s.emp, adv.id());
        assertEquals(1, paidOut.schedule().size(), "whatever claims do not cover is taken in one deduction");
        assertEquals(TODAY.withDayOfMonth(1).plusMonths(2).toString(), paidOut.schedule().get(0).get("month"), "in the last of the months asked for");

        // The first claim is covered in full: nothing is paid again, and the advance drops.
        UUID first = claim(s.emp, "Nashik, day one");
        for (int d = 1; d <= 3; d++) {
            line(s.emp, first, s.meals, d, "300.00");
        }
        assertError(400, "request.invalid", () -> expenses.setAdvance(s.solo, claim(s.solo, "Mine"), adv.id()));   // someone else's advance
        expenses.setAdvance(s.emp, first, adv.id());
        expenses.submit(s.emp, first, version(s.emp, first));
        assertError(409, "expense.locked", () -> expenses.setAdvance(s.emp, first, null));
        expenses.decide(s.manager, first, "approve", "Agreed", version(s.emp, first), null);
        expenses.verify(s.finance, first, version(s.emp, first), null);
        ExpenseService.ClaimView covered = expenses.claim(s.emp, first);
        assertEquals("paid", covered.status());
        assertEquals(0, new BigDecimal("900.00").compareTo(covered.advanceApplied()));
        assertEquals(0, covered.payable().signum());
        assertEquals(0, new BigDecimal("100.00").compareTo(loans.loan(s.emp, adv.id()).outstanding()));

        // The second is bigger than what is left: the advance closes and only the rest goes to payment.
        UUID second = claim(s.emp, "Nashik, day two");
        line(s.emp, second, s.meals, 4, "300.00");
        expenses.setAdvance(s.emp, second, adv.id());
        expenses.submit(s.emp, second, version(s.emp, second));
        expenses.decide(s.manager, second, "approve", "Agreed", version(s.emp, second), null);
        expenses.verify(s.finance, second, version(s.emp, second), null);
        ExpenseService.ClaimView part = expenses.claim(s.emp, second);
        assertEquals("verified", part.status());
        assertEquals(0, new BigDecimal("200.00").compareTo(part.payable()));
        assertEquals("closed", loans.loan(s.emp, adv.id()).status());
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) payouts.payable(s.finance).get(0).get("payable")));
        ExpensePayouts.BatchMade batch = payouts.createBatch(s.finance, List.of(second));
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) db.inTenant(s.orgId, c -> Sql.one(c, "select amount from app.expense_payment where batch_id = ?", batch.id()).get("amount"))));
    }

    @Test
    void aLoanGoesThroughManagerAndFinanceThenRepaysWithSkipPrepayAndWriteOff() {
        Setup s = setup();
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Personal loan", "loan", new BigDecimal("100000"), 12,
                new BigDecimal("12"), 0, 1, true)).id();
        assertError(409, "loan.type_exists", () -> loans.saveType(s.finance, null, new LoanService.TypeInput("personal LOAN", "loan",
                BigDecimal.TEN, 1, BigDecimal.ZERO, 0, 1, true)));
        assertEquals(1, loans.types(s.emp, false).size());
        assertError(403, "permission.denied", () -> loans.types(s.emp, true));

        LoanService.Created asked = loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("12000"), 4, "Laptop"));
        assertEquals("requested", asked.status());
        assertError(409, "loan.state", () -> loans.financeDecide(s.finance, asked.id(), "approve", null, 1));
        assertError(404, "not_found", () -> loans.managerDecide(s.solo, asked.id(), "approve", null, 1));
        assertEquals(1, loans.approvals(s.manager).size());
        assertError(400, "request.invalid", () -> loans.managerDecide(s.manager, asked.id(), "reject", " ", 1));
        loans.managerDecide(s.manager, asked.id(), "approve", null, 1);
        assertError(409, "loan.stale", () -> loans.financeDecide(s.finance, asked.id(), "approve", null, 1));
        loans.financeDecide(s.finance, asked.id(), "approve", null, 2);
        assertError(400, "request.invalid", () -> loans.disburse(s.finance, asked.id(), TODAY.toString(), null, "x", 3));
        loans.disburse(s.finance, asked.id(), TODAY.toString(), null, "UTR778899", 3);
        assertError(409, "loan.state", () -> loans.withdraw(s.emp, asked.id(), 4));

        LoanService.LoanView view = loans.loan(s.emp, asked.id());
        assertEquals(4, view.schedule().size());
        assertEquals(0, new BigDecimal("12000.00").compareTo(view.outstanding()));
        // 12% a year is 1% a month on the balance: 120 in the first month
        assertEquals(0, new BigDecimal("120.00").compareTo((BigDecimal) view.schedule().get(0).get("interest")));
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) view.schedule().get(1).get("interest")));

        // Skipping a month pushes the schedule out by one
        UUID first = UUID.fromString((String) view.schedule().get(0).get("id"));
        assertError(400, "request.invalid", () -> loans.skip(s.finance, first, " "));
        loans.skip(s.finance, first, "Month of unpaid leave");
        assertEquals(5, loans.loan(s.emp, asked.id()).schedule().size());
        assertEquals(0, new BigDecimal("12000.00").compareTo(loans.loan(s.emp, asked.id()).outstanding()));

        // A prepayment works the rest out again over the same months
        assertError(400, "request.invalid", () -> loans.prepay(s.finance, asked.id(), new BigDecimal("12000.01"), "UTR1"));
        loans.prepay(s.finance, asked.id(), new BigDecimal("6000.00"), "Bank transfer UTR5544");
        assertEquals(0, new BigDecimal("6000.00").compareTo(loans.loan(s.emp, asked.id()).outstanding()));
        assertEquals(1, loans.loan(s.emp, asked.id()).settlements().size());

        // The person who paid it out cannot write it off; someone else can, with a reason
        assertError(403, "loan.same_person", () -> loans.writeOff(s.finance, asked.id(), "Employee has left, unrecoverable"));
        assertError(400, "request.invalid", () -> loans.writeOff(s.admin, asked.id(), "short"));
        loans.writeOff(s.admin, asked.id(), "Employee has left, unrecoverable");
        LoanService.LoanView done = loans.loan(s.emp, asked.id());
        assertEquals("written_off", done.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(done.outstanding()));
        assertEquals(2, done.settlements().size());
        List<String> audited = db.inTenant(s.orgId, c -> Sql.query(c, "select distinct action from app.audit_event where action like 'loan.%'")).stream()
                .map(r -> (String) r.get("action")).toList();
        assertTrue(audited.containsAll(List.of("loan.requested", "loan.approve", "loan.disbursed", "loan.instalment_skipped", "loan.prepaid", "loan.written_off")),
                audited.toString());
    }

    @Test
    void loanRulesRefuseWhatTheTypeDoesNotAllow() {
        Setup s = setup();
        UUID type = loans.saveType(s.finance, null, new LoanService.TypeInput("Advance", "salary_advance", new BigDecimal("5000"), 2,
                BigDecimal.ZERO, 0, 1, true)).id();
        UUID veteran = loans.saveType(s.finance, null, new LoanService.TypeInput("Long service loan", "loan", new BigDecimal("5000"), 2,
                BigDecimal.ZERO, 120, 1, true)).id();
        assertError(400, "request.invalid", () -> loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("5000.01"), 1, "Too much")));
        assertError(400, "request.invalid", () -> loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("100"), 3, "Too long")));
        assertError(400, "request.invalid", () -> loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("100"), 1, "x")));
        assertError(409, "loan.too_soon", () -> loans.request(s.emp, new LoanService.RequestInput(veteran.toString(), new BigDecimal("100"), 1, "Soon enough")));
        LoanService.Created one = loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("1000"), 1, "Rent deposit"));
        assertError(409, "loan.too_many", () -> loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("1000"), 1, "Again")));
        // a rejection needs a reason and ends the request; withdrawing frees the place for another
        loans.managerDecide(s.manager, one.id(), "reject", "Not this month", 1);
        assertEquals("rejected", loans.loan(s.emp, one.id()).status());
        LoanService.Created two = loans.request(s.emp, new LoanService.RequestInput(type.toString(), new BigDecimal("1000"), 1, "Rent deposit"));
        loans.withdraw(s.emp, two.id(), 1);
        assertEquals("withdrawn", loans.loan(s.emp, two.id()).status());
        // nobody else sees it; another Organization has no such loan
        assertError(404, "not_found", () -> loans.loan(s.solo, one.id()));
        Setup other = setup();
        assertError(404, "not_found", () -> loans.loan(other.admin, one.id()));
        assertEquals(0, loans.queue(other.finance).size());
        // someone with a loan cannot decide it for themselves
        UUID own = loans.request(s.finance, new LoanService.RequestInput(type.toString(), new BigDecimal("500"), 1, "Own advance")).id();
        assertError(403, "loan.own", () -> loans.financeDecide(s.finance, own, "approve", null, 1));
    }

    private static UUID verified(Setup s, SessionCtx who, String title, String amount) {
        UUID claim = claim(who, title);
        line(who, claim, s.meals, 1, amount);
        String next = expenses.submit(who, claim, version(who, claim)).status();
        if ("submitted".equals(next)) {
            expenses.decide(s.manager, claim, "approve", "ok", version(who, claim), null);
        }
        expenses.verify(s.finance, claim, version(who, claim), null);
        return claim;
    }

    /** Gives the person with this name a bank account, as the employee record would. */
    private static void bank(Setup s, String name, String account, String ifsc) {
        db.inOwner(c -> {
            Map<String, Object> person = Sql.one(c, "select p.id from app.person p where p.tenant_id = ? and p.legal_name = ?", s.orgId, name);
            Sql.update(c, "insert into app.bank_account (tenant_id, person_id, holder_name, account_enc, account_last4, ifsc, effective_from) values (?, ?, ?, ?, ?, ?, ?)",
                    s.orgId, UUID.fromString(person.get("id").toString()), name, crypto.encrypt(s.orgId, account), account.substring(account.length() - 4), ifsc, TODAY.minusYears(1));
            return null;
        });
    }

    private static Setup setup() {
        String slug = "exp-" + SEQUENCE.incrementAndGet();
        Map<String, Object> created = platform.createOrganization(superAdmin, "Org " + slug, slug, "admin@" + slug + ".test");
        SessionCtx admin = auth.resolve(invitations.accept((String) created.get("invitationToken"), PASSWORD, null));
        UUID orgId = UUID.fromString((String) created.get("id"));
        SessionCtx finance = join(admin, slug, "finance", List.of("finance"));
        SessionCtx manager = join(admin, slug, "manager", List.of("manager", "employee"));
        SessionCtx emp = join(admin, slug, "emp", List.of("employee"));
        SessionCtx solo = join(admin, slug, "solo", List.of("employee"));
        UUID bossRecord = add(admin, "Bina Boss", manager, null, 1);
        add(admin, "Esha Emp", emp, bossRecord, 2);
        add(admin, "Sol Solo", solo, null, 3);
        add(admin, "Fin Finance", finance, null, 4);
        UUID meals = expenses.saveCategory(finance, null, new CategoryInput("Meals", new BigDecimal("1000"),
                new BigDecimal("3000"), new BigDecimal("500"), null, false, false, "Staff welfare", true)).id();
        UUID travel = expenses.saveCategory(finance, null, new CategoryInput("Travel", null, null, new BigDecimal("500"),
                null, true, false, "Travel", true)).id();
        UUID conveyance = expenses.saveCategory(finance, null, new CategoryInput("Local conveyance", null, null,
                new BigDecimal("5000"), new BigDecimal("12"), false, false, null, true)).id();
        return new Setup(orgId, admin, finance, manager, emp, solo, meals, travel, conveyance);
    }

    private static SessionCtx join(SessionCtx admin, String slug, String name, List<String> roles) {
        String invitation = (String) users.invite(admin, name + "@" + slug + ".test", roles).get("token");
        return auth.resolve(invitations.accept(invitation, PASSWORD, null));
    }

    private static UUID add(SessionCtx by, String name, SessionCtx login, UUID manager, int code) {
        return UUID.fromString((String) employees.add(by, new NewEmployee(name, null, "1990-01-01", "female", null, "9876543210",
                "permanent", TODAY.minusYears(3).toString(), null, null, "E" + code + "-" + SEQUENCE.get(), "Analyst", null,
                manager == null ? null : manager.toString(), login.membershipId().toString(), null, null, null, null, null, null, null,
                null, null)).get("id"));
    }

    private static UUID claim(SessionCtx who, String title) {
        return expenses.createClaim(who, title).id();
    }

    private static UUID line(SessionCtx who, UUID claim, UUID category, int daysAgo, String amount) {
        return expenses.addLine(who, claim, new LineInput(category.toString(), TODAY.minusDays(daysAgo).toString(),
                new BigDecimal(amount), null, null, null, null)).id();
    }

    private static void attachDeclarations(SessionCtx who, UUID claim) {
        for (Object l : (List<?>) expenses.claim(who, claim).lines()) {
            UUID id = UUID.fromString(((Map<?, ?>) l).get("id").toString());
            expenses.uploadEvidence(who, id, "missing_receipt_declaration", null, null, "Receipts were lost in transit");
        }
    }

    private static int version(SessionCtx who, UUID claim) {
        return expenses.claim(who, claim).version();
    }

    private static String status(SessionCtx who, UUID claim) {
        return expenses.claim(who, claim).status();
    }

    private static List<String> actions(Setup s) {
        return db.inTenant(s.orgId, c -> Sql.query(c, "select action from app.audit_event where action like 'expense.%'").stream()
                .map(r -> (String) r.get("action")).toList());
    }

    private static CategoryInput cat(String name, BigDecimal limit) {
        return new CategoryInput(name, limit, null, new BigDecimal("500"), null, false, false, null, true);
    }

    private static byte[] pdf(String tag) {
        return ("%PDF-1.4\n%" + tag + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
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
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

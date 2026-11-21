package com.nexvance.loginapp.web;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nexvance.loginapp.core.ExpensePayouts;
import com.nexvance.loginapp.core.ExpenseService;
import com.nexvance.loginapp.core.Modules;
import com.nexvance.loginapp.core.SessionCtx;

/** Expense claims: the employee's own, the manager's queue, and finance's verification (docs/expenses/design.md). */
@RestController
@RequestMapping("/api/org/expenses")
@RequiresModule(Modules.EXPENSES)
public class ExpenseController {

    public record NewClaim(String title) {}

    public record Versioned(Integer version) {}

    public record Decision(String action, String note, Integer version, List<ExpenseService.Cut> cuts) {}

    public record Review(String status, String note, Integer version) {}

    public record Verify(Integer version, String note) {}

    public record NewBatch(List<String> claims) {}

    private final ExpenseService expenses;
    private final ExpensePayouts payouts;

    public ExpenseController(ExpenseService expenses, ExpensePayouts payouts) {
        this.expenses = expenses;
        this.payouts = payouts;
    }

    // ── payment ─────────────────────────────────────────────────────────

    @GetMapping("/payable")
    public List<Map<String, Object>> payable(SessionCtx ctx) {
        return payouts.payable(ctx);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(SessionCtx ctx) {
        return payouts.batches(ctx);
    }

    @GetMapping("/batches/{id}/claims")
    public List<Map<String, Object>> batchClaims(SessionCtx ctx, @PathVariable("id") String id) {
        return payouts.batchClaims(ctx, Requests.id(id));
    }

    @PostMapping("/batches")
    public ExpensePayouts.BatchMade createBatch(SessionCtx ctx, @RequestBody NewBatch body) {
        return payouts.createBatch(ctx, (body.claims() == null ? List.<String>of() : body.claims()).stream().map(Requests::id).toList());
    }

    @GetMapping("/batches/{id}/bank-file")
    public ResponseEntity<byte[]> bankFile(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(payouts.bankFile(ctx, Requests.id(id)));
    }

    @PostMapping("/batches/{id}/paid")
    public ResponseEntity<Void> markPaid(SessionCtx ctx, @PathVariable("id") String id, @RequestBody ExpensePayouts.PaidInput body) {
        payouts.markPaid(ctx, Requests.id(id), body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batches/{id}/cancel")
    public ResponseEntity<Void> cancelBatch(SessionCtx ctx, @PathVariable("id") String id) {
        payouts.cancel(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    // ── setup ───────────────────────────────────────────────────────────

    /** Active categories for anyone who can claim; every category with all = true, for setup. */
    @GetMapping("/categories")
    public List<Map<String, Object>> categories(SessionCtx ctx, @RequestParam(value = "all", defaultValue = "false") boolean all) {
        return expenses.categories(ctx, all);
    }

    @PostMapping("/categories")
    public ExpenseService.Saved createCategory(SessionCtx ctx, @RequestBody ExpenseService.CategoryInput body) {
        return expenses.saveCategory(ctx, null, body);
    }

    @PutMapping("/categories/{id}")
    public ExpenseService.Saved changeCategory(SessionCtx ctx, @PathVariable("id") String id, @RequestBody ExpenseService.CategoryInput body) {
        return expenses.saveCategory(ctx, Requests.id(id), body);
    }

    // ── who approves ────────────────────────────────────────────────────

    public record ApproverRule(String membershipId, String departmentId, String approverId) {}

    @GetMapping("/approvers")
    public ExpenseService.ApproverSetup approvers(SessionCtx ctx) {
        return expenses.approvers(ctx);
    }

    @PostMapping("/approvers")
    public ExpenseService.Saved saveApprover(SessionCtx ctx, @RequestBody ApproverRule body) {
        return expenses.saveApprover(ctx, body.membershipId() == null ? null : Requests.id(body.membershipId()),
                body.departmentId() == null ? null : Requests.id(body.departmentId()), body.approverId() == null ? null : Requests.id(body.approverId()));
    }

    @DeleteMapping("/approvers/{id}")
    public ResponseEntity<Void> removeApprover(SessionCtx ctx, @PathVariable("id") String id) {
        expenses.removeApprover(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    // ── the caller's own claims ─────────────────────────────────────────

    @GetMapping("/claims")
    public List<Map<String, Object>> mine(SessionCtx ctx) {
        return expenses.myClaims(ctx);
    }

    @PostMapping("/claims")
    public ExpenseService.Created create(SessionCtx ctx, @RequestBody NewClaim body) {
        return expenses.createClaim(ctx, body.title());
    }

    @GetMapping("/claims/{id}")
    public ExpenseService.ClaimView claim(SessionCtx ctx, @PathVariable("id") String id) {
        return expenses.claim(ctx, Requests.id(id));
    }

    @DeleteMapping("/claims/{id}")
    public ResponseEntity<Void> delete(SessionCtx ctx, @PathVariable("id") String id) {
        expenses.deleteClaim(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/lines")
    public ExpenseService.LineAdded addLine(SessionCtx ctx, @PathVariable("id") String id, @RequestBody ExpenseService.LineInput body) {
        return expenses.addLine(ctx, Requests.id(id), body);
    }

    @DeleteMapping("/lines/{id}")
    public ResponseEntity<Void> deleteLine(SessionCtx ctx, @PathVariable("id") String id) {
        expenses.deleteLine(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    /** The file is the request body; its kind and name are in the query string, as for onboarding documents. */
    @PostMapping("/lines/{id}/files")
    public ExpenseService.FileAdded upload(SessionCtx ctx, @PathVariable("id") String id, @RequestParam("kind") String kind,
                                      @RequestParam(value = "name", required = false) String name,
                                      @RequestParam(value = "statement", required = false) String statement, InputStream body) {
        return expenses.uploadEvidence(ctx, Requests.id(id), kind, name, DocumentController.read(body), statement);
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<byte[]> file(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(expenses.file(ctx, Requests.id(id)));
    }

    @PostMapping("/claims/{id}/submit")
    public ExpenseService.Submitted submit(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Versioned body) {
        return expenses.submit(ctx, Requests.id(id), version(body.version()));
    }

    @PostMapping("/claims/{id}/withdraw")
    public ResponseEntity<Void> withdraw(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Versioned body) {
        expenses.withdraw(ctx, Requests.id(id), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    /** Sets the claim against one of the owner's expense advances; no advance takes it off. */
    @PutMapping("/claims/{id}/advance")
    public ResponseEntity<Void> advance(SessionCtx ctx, @PathVariable("id") String id, @RequestBody AdvanceChoice body) {
        expenses.setAdvance(ctx, Requests.id(id), body.loanId() == null || body.loanId().isBlank() ? null : Requests.id(body.loanId()));
        return ResponseEntity.noContent().build();
    }

    public record AdvanceChoice(String loanId) {}

    @PostMapping("/claims/{id}/reopen")
    public ResponseEntity<Void> reopen(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Versioned body) {
        expenses.reopen(ctx, Requests.id(id), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/send-back")
    public ResponseEntity<Void> sendBack(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Versioned body) {
        expenses.sendBack(ctx, Requests.id(id), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    // ── the manager ─────────────────────────────────────────────────────

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(SessionCtx ctx) {
        return expenses.approvals(ctx);
    }

    /** action is approve, return or reject. */
    @PostMapping("/claims/{id}/decision")
    public ResponseEntity<Void> decide(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Decision body) {
        expenses.decide(ctx, Requests.id(id), body.action(), body.note(), version(body.version()), body.cuts());
        return ResponseEntity.noContent().build();
    }

    // ── finance ─────────────────────────────────────────────────────────

    @GetMapping("/verify")
    public List<Map<String, Object>> verifyQueue(SessionCtx ctx) {
        return expenses.verifyQueue(ctx);
    }

    @PostMapping("/lines/{id}/review")
    public ResponseEntity<Void> review(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Review body) {
        expenses.reviewEvidence(ctx, Requests.id(id), body.status(), body.note(), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/verify")
    public ResponseEntity<Void> verify(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Verify body) {
        expenses.verify(ctx, Requests.id(id), version(body.version()), body.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/claims/{id}/finance-reject")
    public ResponseEntity<Void> financeReject(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Verify body) {
        expenses.financeReject(ctx, Requests.id(id), version(body.version()), body.note());
        return ResponseEntity.noContent().build();
    }

    private static int version(Integer v) {
        if (v == null) {
            throw com.nexvance.loginapp.core.ApiException.badRequest("Send the version of the claim you are looking at");
        }
        return v;
    }
}

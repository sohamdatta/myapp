package com.example.loginapp.web;

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

import com.example.loginapp.core.ExpenseService;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;

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

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
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
            throw com.example.loginapp.core.ApiException.badRequest("Send the version of the claim you are looking at");
        }
        return v;
    }
}

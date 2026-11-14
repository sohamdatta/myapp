package com.example.loginapp.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.LoanService;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;

/** Salary advances and loans: asking, approving, paying out and repaying (docs/expenses/design.md, stage X4). */
@RestController
@RequestMapping("/api/org/loans")
@RequiresModule(Modules.EXPENSES)
public class LoanController {

    public record Versioned(Integer version) {}

    public record Decision(String action, String note, Integer version) {}

    public record Payout(String paidOn, String firstMonth, String reference, Integer version) {}

    public record Note(String note) {}

    public record Prepayment(BigDecimal amount, String note) {}

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    @GetMapping("/types")
    public List<Map<String, Object>> types(SessionCtx ctx, @RequestParam(value = "all", required = false) String all) {
        return loans.types(ctx, "true".equals(all));
    }

    @PostMapping("/types")
    public LoanService.Saved createType(SessionCtx ctx, @RequestBody LoanService.TypeInput body) {
        return loans.saveType(ctx, null, body);
    }

    @PutMapping("/types/{id}")
    public LoanService.Saved changeType(SessionCtx ctx, @PathVariable("id") String id, @RequestBody LoanService.TypeInput body) {
        return loans.saveType(ctx, Requests.id(id), body);
    }

    @GetMapping("/preview")
    public List<LoanService.Row> preview(SessionCtx ctx, @RequestParam("type") String type, @RequestParam("amount") BigDecimal amount,
                                         @RequestParam("months") int months) {
        return loans.preview(ctx, Requests.id(type), amount, months);
    }

    @GetMapping
    public List<Map<String, Object>> mine(SessionCtx ctx) {
        return loans.mine(ctx);
    }

    @PostMapping
    public LoanService.Created request(SessionCtx ctx, @RequestBody LoanService.RequestInput body) {
        return loans.request(ctx, body);
    }

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(SessionCtx ctx) {
        return loans.approvals(ctx);
    }

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(SessionCtx ctx) {
        return loans.queue(ctx);
    }

    @GetMapping("/{id}")
    public LoanService.LoanView loan(SessionCtx ctx, @PathVariable("id") String id) {
        return loans.loan(ctx, Requests.id(id));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Void> withdraw(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Versioned body) {
        loans.withdraw(ctx, Requests.id(id), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/manager")
    public ResponseEntity<Void> manager(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Decision body) {
        loans.managerDecide(ctx, Requests.id(id), body.action(), body.note(), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/finance")
    public ResponseEntity<Void> finance(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Decision body) {
        loans.financeDecide(ctx, Requests.id(id), body.action(), body.note(), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disburse")
    public ResponseEntity<Void> disburse(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Payout body) {
        loans.disburse(ctx, Requests.id(id), body.paidOn(), body.firstMonth(), body.reference(), version(body.version()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/instalments/{id}/skip")
    public ResponseEntity<Void> skip(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Note body) {
        loans.skip(ctx, Requests.id(id), body.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/prepay")
    public ResponseEntity<Void> prepay(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Prepayment body) {
        loans.prepay(ctx, Requests.id(id), body.amount(), body.note());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/write-off")
    public ResponseEntity<Void> writeOff(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Note body) {
        loans.writeOff(ctx, Requests.id(id), body.note());
        return ResponseEntity.noContent().build();
    }

    /** Marks a loan's interest benefit as not taxed (a loan for treating a specified illness); an empty note takes the mark off. */
    @PostMapping("/{id}/exempt")
    public ResponseEntity<Void> exempt(SessionCtx ctx, @PathVariable("id") String id, @RequestBody Note body) {
        loans.exempt(ctx, Requests.id(id), body.note());
        return ResponseEntity.noContent().build();
    }

    private static int version(Integer v) {
        if (v == null) {
            throw com.example.loginapp.core.ApiException.badRequest("Send the version you are looking at");
        }
        return v;
    }
}

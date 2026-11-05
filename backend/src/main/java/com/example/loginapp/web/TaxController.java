package com.example.loginapp.web;

import java.io.InputStream;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.TaxDeclarations;

/**
 * Income tax declarations and proofs: the employee's own under /me/tax, HR's under /payroll/tax.
 * Part of Payroll. A proof upload is the file itself as the request body, as for other documents.
 */
@RestController
@RequestMapping("/api/org")
public class TaxController {

    private final AuthService auth;
    private final TaxDeclarations tax;

    public TaxController(AuthService auth, TaxDeclarations tax) {
        this.auth = auth;
        this.tax = tax;
    }

    // ── the employee's own ──────────────────────────────────────────────

    @GetMapping("/me/tax")
    public Map<String, Object> mine(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                    @RequestParam(value = "year", required = false) String year) {
        return tax.mine(ctx, year);
    }

    @PostMapping("/me/tax/items")
    public Map<String, Object> addItem(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @RequestBody TaxDeclarations.ItemInput request) {
        return tax.addItem(ctx, request);
    }

    @PutMapping("/me/tax/items/{id}")
    public Map<String, Object> updateItem(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                          @PathVariable("id") String id, @RequestBody TaxDeclarations.ItemInput request) {
        return tax.updateItem(ctx, Requests.id(id), request);
    }

    @DeleteMapping("/me/tax/items/{id}")
    public Map<String, Object> removeItem(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                          @PathVariable("id") String id) {
        return tax.removeItem(ctx, Requests.id(id));
    }

    @PostMapping("/me/tax/submit")
    public Map<String, Object> submit(@RequiresModule(Modules.PAYROLL) SessionCtx ctx) {
        return tax.submit(ctx);
    }

    @PostMapping("/me/tax/items/{id}/proofs")
    public Map<String, Object> addProof(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id,
                                        @RequestParam(value = "name", required = false) String name, InputStream body) {
        var ctx = Requests.session(auth, authorization, Modules.PAYROLL);
        return tax.addProof(ctx, Requests.id(id), name, DocumentController.read(body));
    }

    @DeleteMapping("/me/tax/items/{id}/proofs/{document}")
    public Map<String, Object> removeProof(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                           @PathVariable("id") String id, @PathVariable("document") String document) {
        return tax.removeProof(ctx, Requests.id(id), Requests.id(document));
    }

    // ── a proof file, for its owner or a reviewer ───────────────────────

    @GetMapping("/tax/proofs/{document}")
    public ResponseEntity<byte[]> proof(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("document") String document) {
        return DocumentController.file(tax.proof(ctx, Requests.id(document)));
    }

    // ── HR ──────────────────────────────────────────────────────────────

    @GetMapping("/payroll/tax")
    public Map<String, Object> overview(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @RequestParam(value = "year", required = false) String year) {
        return tax.overview(ctx, year);
    }

    @PutMapping("/payroll/tax/window")
    public Map<String, Object> saveWindow(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                          @RequestBody TaxDeclarations.WindowInput request) {
        return tax.saveWindow(ctx, request);
    }

    @GetMapping("/payroll/tax/employees/{id}")
    public Map<String, Object> employee(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("id") String id, @RequestParam(value = "year", required = false) String year) {
        return tax.employee(ctx, Requests.id(id), year);
    }

    @PostMapping("/payroll/tax/items/{id}/accept")
    public Map<String, Object> accept(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody TaxDeclarations.AcceptInput request) {
        return tax.accept(ctx, Requests.id(id), request.amount());
    }

    @PostMapping("/payroll/tax/items/{id}/return")
    public Map<String, Object> returnProof(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody TaxDeclarations.NoteInput request) {
        return tax.returnProof(ctx, Requests.id(id), request.note());
    }
}

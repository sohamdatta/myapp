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
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(value = "year", required = false) String year) {
        return tax.mine(Requests.session(auth, authorization, Modules.PAYROLL), year);
    }

    @PostMapping("/me/tax/items")
    public Map<String, Object> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody TaxDeclarations.ItemInput request) {
        return tax.addItem(Requests.session(auth, authorization, Modules.PAYROLL), request);
    }

    @PutMapping("/me/tax/items/{id}")
    public Map<String, Object> updateItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @RequestBody TaxDeclarations.ItemInput request) {
        return tax.updateItem(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id), request);
    }

    @DeleteMapping("/me/tax/items/{id}")
    public Map<String, Object> removeItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id) {
        return tax.removeItem(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id));
    }

    @PostMapping("/me/tax/submit")
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return tax.submit(Requests.session(auth, authorization, Modules.PAYROLL));
    }

    @PostMapping("/me/tax/items/{id}/proofs")
    public Map<String, Object> addProof(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id,
                                        @RequestParam(value = "name", required = false) String name, InputStream body) {
        var ctx = Requests.session(auth, authorization, Modules.PAYROLL);
        return tax.addProof(ctx, Requests.id(id), name, DocumentController.read(body));
    }

    @DeleteMapping("/me/tax/items/{id}/proofs/{document}")
    public Map<String, Object> removeProof(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @PathVariable("document") String document) {
        return tax.removeProof(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id), Requests.id(document));
    }

    // ── a proof file, for its owner or a reviewer ───────────────────────

    @GetMapping("/tax/proofs/{document}")
    public ResponseEntity<byte[]> proof(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("document") String document) {
        return DocumentController.file(tax.proof(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(document)));
    }

    // ── HR ──────────────────────────────────────────────────────────────

    @GetMapping("/payroll/tax")
    public Map<String, Object> overview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam(value = "year", required = false) String year) {
        return tax.overview(Requests.session(auth, authorization, Modules.PAYROLL), year);
    }

    @PutMapping("/payroll/tax/window")
    public Map<String, Object> saveWindow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody TaxDeclarations.WindowInput request) {
        return tax.saveWindow(Requests.session(auth, authorization, Modules.PAYROLL), request);
    }

    @GetMapping("/payroll/tax/employees/{id}")
    public Map<String, Object> employee(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @RequestParam(value = "year", required = false) String year) {
        return tax.employee(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id), year);
    }

    @PostMapping("/payroll/tax/items/{id}/accept")
    public Map<String, Object> accept(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody TaxDeclarations.AcceptInput request) {
        return tax.accept(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id), request.amount());
    }

    @PostMapping("/payroll/tax/items/{id}/return")
    public Map<String, Object> returnProof(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody TaxDeclarations.NoteInput request) {
        return tax.returnProof(Requests.session(auth, authorization, Modules.PAYROLL), Requests.id(id), request.note());
    }
}

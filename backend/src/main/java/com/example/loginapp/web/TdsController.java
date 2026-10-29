package com.example.loginapp.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.TdsChallans;
import com.example.loginapp.core.TdsStatements;

/** Payroll → Tax filing: the monthly TDS deposits (challans) and the quarterly TDS statements. */
@RestController
@RequestMapping("/api/org/payroll/tds")
@RequiresModule(Modules.PAYROLL)
public class TdsController {

    private final TdsChallans challans;
    private final TdsStatements statements;

    public TdsController(TdsChallans challans, TdsStatements statements) {
        this.challans = challans;
        this.statements = statements;
    }

    @GetMapping("/challans")
    public TdsChallans.YearView year(SessionCtx ctx, @RequestParam(value = "taxYear", required = false) String taxYear) {
        return challans.year(ctx, taxYear);
    }

    @PostMapping("/challans")
    public TdsChallans.Challan record(SessionCtx ctx, @RequestBody TdsChallans.ChallanInput request) {
        return challans.record(ctx, request);
    }

    @PostMapping("/challans/{id}/approve")
    public TdsChallans.Status approve(SessionCtx ctx, @PathVariable("id") String id) {
        return challans.approve(ctx, Requests.id(id));
    }

    @DeleteMapping("/challans/{id}")
    public void remove(SessionCtx ctx, @PathVariable("id") String id) {
        challans.remove(ctx, Requests.id(id));
    }

    @GetMapping("/statements")
    public TdsStatements.QuarterView quarter(SessionCtx ctx, @RequestParam(value = "taxYear", required = false) String taxYear,
                                             @RequestParam("quarter") int quarter) {
        return statements.quarter(ctx, taxYear, quarter);
    }

    @PostMapping("/statements/{taxYear}/{quarter}")
    public TdsStatements.BuildResult build(SessionCtx ctx, @PathVariable("taxYear") String taxYear, @PathVariable("quarter") int quarter) {
        return statements.build(ctx, taxYear, quarter);
    }

    @PostMapping("/statements/{id}/accept")
    public TdsStatements.Status accept(SessionCtx ctx, @PathVariable("id") String id, @RequestBody TdsStatements.AcceptInput request) {
        return statements.accept(ctx, Requests.id(id), request);
    }

    @GetMapping("/statements/{id}/file")
    public ResponseEntity<byte[]> file(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(statements.download(ctx, Requests.id(id)));
    }

    @PostMapping("/statements/{id}/filed")
    public TdsStatements.Status filed(SessionCtx ctx, @PathVariable("id") String id, @RequestBody TdsStatements.FiledInput request) {
        return statements.filed(ctx, Requests.id(id), request);
    }
}

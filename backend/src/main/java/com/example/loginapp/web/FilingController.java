package com.example.loginapp.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.StatutoryFilings;

/** Payroll → Statutory filing: the PF ECR and ESI files for a month, and the log of what was filed. */
@RestController
@RequestMapping("/api/org/payroll/filings")
@RequiresModule(Modules.PAYROLL)
public class FilingController {

    public record ReasonInput(int code) {}

    private final StatutoryFilings filings;

    public FilingController(StatutoryFilings filings) {
        this.filings = filings;
    }

    @GetMapping
    public StatutoryFilings.MonthView month(SessionCtx ctx, @org.springframework.web.bind.annotation.RequestParam("month") String month) {
        return filings.month(ctx, month);
    }

    @PostMapping("/{kind}/{month}")
    public StatutoryFilings.BuildResult build(SessionCtx ctx, @PathVariable("kind") String kind, @PathVariable("month") String month) {
        return filings.build(ctx, kind, month);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(SessionCtx ctx, @PathVariable("id") String id) {
        return DocumentController.file(filings.download(ctx, Requests.id(id)));
    }

    @PostMapping("/{id}/filed")
    public StatutoryFilings.Status record(SessionCtx ctx, @PathVariable("id") String id, @RequestBody StatutoryFilings.RecordInput request) {
        return filings.record(ctx, Requests.id(id), request);
    }

    @PostMapping("/{id}/approve")
    public StatutoryFilings.Status approve(SessionCtx ctx, @PathVariable("id") String id) {
        return filings.approve(ctx, Requests.id(id));
    }

    @GetMapping("/esi-reasons")
    public List<StatutoryFilings.ReasonRow> reasons(SessionCtx ctx) {
        return filings.reasonMap(ctx);
    }

    @PutMapping("/esi-reasons/{exitKind}")
    public List<StatutoryFilings.ReasonRow> setReason(SessionCtx ctx, @PathVariable("exitKind") String exitKind, @RequestBody ReasonInput request) {
        return filings.setReason(ctx, exitKind, request.code());
    }
}

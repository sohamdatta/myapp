package com.example.loginapp.web;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.OtherEmployerIncome;
import com.example.loginapp.core.SessionCtx;

/** Form 122, Part B: salary from a previous employer this tax year, declared by the employee and reviewed by HR. */
@RestController
@RequestMapping("/api/org")
@RequiresModule(Modules.PAYROLL)
public class OtherEmployerController {

    private final OtherEmployerIncome income;

    public OtherEmployerController(OtherEmployerIncome income) {
        this.income = income;
    }

    @GetMapping("/tax/other-employers")
    public List<OtherEmployerIncome.Entry> mine(SessionCtx ctx, @RequestParam(value = "taxYear", required = false) String taxYear) {
        return income.mine(ctx, taxYear);
    }

    @PostMapping("/tax/other-employers")
    public OtherEmployerIncome.Entry add(SessionCtx ctx, @RequestBody OtherEmployerIncome.Input request) {
        return income.add(ctx, request);
    }

    @PutMapping("/tax/other-employers/{id}")
    public OtherEmployerIncome.Entry update(SessionCtx ctx, @PathVariable("id") String id, @RequestBody OtherEmployerIncome.Input request) {
        return income.update(ctx, Requests.id(id), request);
    }

    @DeleteMapping("/tax/other-employers/{id}")
    public void remove(SessionCtx ctx, @PathVariable("id") String id) {
        income.remove(ctx, Requests.id(id));
    }

    @GetMapping("/people/employees/{id}/tax/other-employers")
    public List<OtherEmployerIncome.Entry> employee(SessionCtx ctx, @PathVariable("id") String id,
                                                    @RequestParam(value = "taxYear", required = false) String taxYear) {
        return income.employee(ctx, Requests.id(id), taxYear);
    }

    @PostMapping("/tax/other-employers/{id}/accept")
    public OtherEmployerIncome.Entry accept(SessionCtx ctx, @PathVariable("id") String id, @RequestBody OtherEmployerIncome.ReviewInput request) {
        return income.accept(ctx, Requests.id(id), request.updatedAt());
    }

    @PostMapping("/tax/other-employers/{id}/return")
    public OtherEmployerIncome.Entry returnEntry(SessionCtx ctx, @PathVariable("id") String id, @RequestBody OtherEmployerIncome.NoteInput request) {
        return income.returnEntry(ctx, Requests.id(id), request.note(), request.updatedAt());
    }
}

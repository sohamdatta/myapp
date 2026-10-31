package com.example.loginapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.WageCheck;
import com.example.loginapp.core.WageSimulator;

/** Payroll → Wage check: the Labour Codes 50% wage test, per person, for a month. */
@RestController
@RequestMapping("/api/org/payroll/wage-check")
@RequiresModule(Modules.PAYROLL)
public class WageCheckController {

    private final WageCheck check;
    private final WageSimulator simulator;

    public WageCheckController(WageCheck check, WageSimulator simulator) {
        this.check = check;
        this.simulator = simulator;
    }

    @GetMapping
    public WageCheck.View view(SessionCtx ctx, @RequestParam(value = "month", required = false) String month) {
        return check.check(ctx, month);
    }

    /** What-if for one person: the amounts to try, and the month (this month when omitted). Nothing is saved. */
    @PostMapping("/simulate/{employmentId}")
    public WageSimulator.Result simulate(SessionCtx ctx, @PathVariable("employmentId") String employmentId, @RequestBody WageSimulator.Input input) {
        return simulator.simulate(ctx, Requests.id(employmentId), input);
    }
}

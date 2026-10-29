package com.example.loginapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.WageCheck;

/** Payroll → Wage check: the Labour Codes 50% wage test, per person, for a month. */
@RestController
@RequestMapping("/api/org/payroll/wage-check")
@RequiresModule(Modules.PAYROLL)
public class WageCheckController {

    private final WageCheck check;

    public WageCheckController(WageCheck check) {
        this.check = check;
    }

    @GetMapping
    public WageCheck.View view(SessionCtx ctx, @RequestParam(value = "month", required = false) String month) {
        return check.check(ctx, month);
    }
}

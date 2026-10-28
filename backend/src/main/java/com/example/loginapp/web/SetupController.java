package com.example.loginapp.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SetupService;

/**
 * Getting the caller's Organization started: what is set up so far, and filling an empty one from a
 * starting setup. It spans Core HR, Attendance, Leave and Payroll, so it belongs to no one module.
 */
@RestController
@RequestMapping("/api/org/setup")
public class SetupController {

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping
    public SetupService.Status status(SessionCtx ctx) {
        return setup.status(ctx);
    }

    @PostMapping("/starter")
    public SetupService.Applied apply(SessionCtx ctx, @RequestBody SetupService.Choice choice) {
        return setup.apply(ctx, choice);
    }
}

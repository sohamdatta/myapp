package com.example.loginapp.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ClientErrors;

import jakarta.servlet.http.HttpServletRequest;

/** Where the browser reports a crash. Open to signed-out pages too; limited and cleaned in {@link ClientErrors}. */
@RestController
@RequestMapping("/api/client-errors")
public class ClientErrorController {

    private final ClientErrors errors;
    private final boolean trustForwardedFor;

    public ClientErrorController(ClientErrors errors, @Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.errors = errors;
        this.trustForwardedFor = trustForwardedFor;
    }

    /** Always answers 204, whether the report was kept or dropped: the browser has nothing to do about it. */
    @PostMapping
    public ResponseEntity<Void> report(@RequestBody ClientErrors.Report report, HttpServletRequest http) {
        errors.record(Requests.clientIp(http, trustForwardedFor), report);
        return ResponseEntity.noContent().build();
    }
}

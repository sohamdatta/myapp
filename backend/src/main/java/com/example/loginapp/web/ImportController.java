package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.EmployeeImport;
import com.example.loginapp.core.EmployeeImport.Invite;
import com.example.loginapp.core.EmployeeImport.Request;

/** Importing existing employees from a CSV file, and inviting them to sign in. */
@RestController
@RequestMapping("/api/org/people/import")
public class ImportController {

    public record Content(String content) {}

    private final AuthService auth;
    private final EmployeeImport imports;

    public ImportController(AuthService auth, EmployeeImport imports) {
        this.auth = auth;
        this.imports = imports;
    }

    @PostMapping("/read")
    public Map<String, Object> read(SessionCtx ctx,
                                    @RequestBody Content request) {
        return imports.read(ctx, request.content());
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(SessionCtx ctx,
                                       @RequestBody Request request) {
        return imports.preview(ctx, request);
    }

    @PostMapping("/run")
    public Map<String, Object> run(SessionCtx ctx,
                                   @RequestBody Request request) {
        return imports.run(ctx, request);
    }

    @GetMapping("/uninvited")
    public List<Map<String, Object>> uninvited(SessionCtx ctx) {
        return imports.uninvited(ctx);
    }

    @PostMapping("/invite")
    public List<Map<String, Object>> invite(SessionCtx ctx,
                                            @RequestBody Invite request) {
        return imports.invite(ctx, request);
    }
}

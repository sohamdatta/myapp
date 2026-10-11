package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
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
    public Map<String, Object> read(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody Content request) {
        return imports.read(Requests.session(auth, authorization), request.content());
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Request request) {
        return imports.preview(Requests.session(auth, authorization), request);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody Request request) {
        return imports.run(Requests.session(auth, authorization), request);
    }

    @GetMapping("/uninvited")
    public List<Map<String, Object>> uninvited(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return imports.uninvited(Requests.session(auth, authorization));
    }

    @PostMapping("/invite")
    public List<Map<String, Object>> invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Invite request) {
        return imports.invite(Requests.session(auth, authorization), request);
    }
}

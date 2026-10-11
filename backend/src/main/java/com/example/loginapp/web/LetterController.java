package com.example.loginapp.web;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

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
import com.example.loginapp.core.LetterService;
import com.example.loginapp.core.LetterService.IssueInput;
import com.example.loginapp.core.LetterService.TemplateInput;
import com.example.loginapp.core.PolicyService;
import com.example.loginapp.core.PolicyService.PolicyInput;

/** Letters and policies: their setup, a joiner's as HR sees them, and the joiner's own. */
@RestController
@RequestMapping("/api/org")
public class LetterController {

    public record Version(Integer version) {}

    private final AuthService auth;
    private final LetterService letters;
    private final PolicyService policies;

    public LetterController(AuthService auth, LetterService letters, PolicyService policies) {
        this.auth = auth;
        this.letters = letters;
        this.policies = policies;
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/people/setup/letters")
    public List<Map<String, Object>> templates(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return letters.templates(Requests.session(auth, authorization));
    }

    @PostMapping("/people/setup/letters")
    public Map<String, Object> createTemplate(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody TemplateInput request) {
        return letters.saveTemplate(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/people/setup/letters/{id}")
    public Map<String, Object> saveTemplate(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id, @RequestBody TemplateInput request) {
        return letters.saveTemplate(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/people/setup/policies")
    public List<Map<String, Object>> policies(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return policies.policies(Requests.session(auth, authorization));
    }

    @PostMapping("/people/setup/policies")
    public Map<String, Object> createPolicy(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody PolicyInput request) {
        return policies.save(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/people/setup/policies/{id}")
    public Map<String, Object> savePolicy(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @RequestBody PolicyInput request) {
        return policies.save(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── one joiner ──────────────────────────────────────────────────────

    @GetMapping("/people/joiners/{id}/letters")
    public Map<String, Object> joinerLetters(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id) {
        return letters.forJoiner(Requests.session(auth, authorization), Requests.id(id));
    }

    @GetMapping("/people/joiners/{id}/letters/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestParam("template") String template) {
        return letters.preview(Requests.session(auth, authorization), Requests.id(id), Requests.id(template));
    }

    @PostMapping("/people/joiners/{id}/letters")
    public Map<String, Object> issue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody IssueInput request) {
        return letters.issue(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/joiners/{id}/letters/upload")
    public Map<String, Object> upload(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id,
                                      @RequestParam(value = "title", required = false) String title,
                                      @RequestParam(value = "kind", required = false) String kind,
                                      @RequestParam(value = "response", required = false) String response,
                                      @RequestParam(value = "expires", required = false) String expires, InputStream body) {
        var ctx = Requests.session(auth, authorization);
        Integer days;
        try {
            days = expires == null || expires.isBlank() ? null : Integer.valueOf(expires.trim());
        } catch (NumberFormatException e) {
            throw com.example.loginapp.core.ApiException.badRequest("Expires is a number of days");
        }
        return letters.upload(ctx, Requests.id(id), title, kind, response, days, DocumentController.read(body));
    }

    @GetMapping("/people/joiners/{id}/policies")
    public List<Map<String, Object>> joinerPolicies(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable("id") String id) {
        return policies.forJoiner(Requests.session(auth, authorization), Requests.id(id));
    }

    // ── the joiner's own ────────────────────────────────────────────────

    @GetMapping("/onboarding/me/letters")
    public List<Map<String, Object>> myLetters(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return letters.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/onboarding/me/letters/{id}/accept")
    public Map<String, Object> accept(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id) {
        return letters.respond(Requests.session(auth, authorization), Requests.id(id), true);
    }

    @PostMapping("/onboarding/me/letters/{id}/decline")
    public Map<String, Object> decline(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id) {
        return letters.respond(Requests.session(auth, authorization), Requests.id(id), false);
    }

    @GetMapping("/onboarding/me/policies")
    public List<Map<String, Object>> myPolicies(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return policies.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/onboarding/me/policies/{id}/acknowledge")
    public List<Map<String, Object>> acknowledge(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable("id") String id, @RequestBody Version request) {
        return policies.acknowledge(Requests.session(auth, authorization), Requests.id(id), request.version());
    }
}

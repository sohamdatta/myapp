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
import com.example.loginapp.core.SessionCtx;
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
    public List<Map<String, Object>> templates(SessionCtx ctx) {
        return letters.templates(ctx);
    }

    @PostMapping("/people/setup/letters")
    public Map<String, Object> createTemplate(SessionCtx ctx,
                                              @RequestBody TemplateInput request) {
        return letters.saveTemplate(ctx, null, request);
    }

    @PutMapping("/people/setup/letters/{id}")
    public Map<String, Object> saveTemplate(SessionCtx ctx,
                                            @PathVariable("id") String id, @RequestBody TemplateInput request) {
        return letters.saveTemplate(ctx, Requests.id(id), request);
    }

    @GetMapping("/people/setup/policies")
    public List<Map<String, Object>> policies(SessionCtx ctx) {
        return policies.policies(ctx);
    }

    @PostMapping("/people/setup/policies")
    public Map<String, Object> createPolicy(SessionCtx ctx,
                                            @RequestBody PolicyInput request) {
        return policies.save(ctx, null, request);
    }

    @PutMapping("/people/setup/policies/{id}")
    public Map<String, Object> savePolicy(SessionCtx ctx,
                                          @PathVariable("id") String id, @RequestBody PolicyInput request) {
        return policies.save(ctx, Requests.id(id), request);
    }

    // ── one joiner ──────────────────────────────────────────────────────

    @GetMapping("/people/joiners/{id}/letters")
    public Map<String, Object> joinerLetters(SessionCtx ctx,
                                             @PathVariable("id") String id) {
        return letters.forJoiner(ctx, Requests.id(id));
    }

    @GetMapping("/people/joiners/{id}/letters/preview")
    public Map<String, Object> preview(SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestParam("template") String template) {
        return letters.preview(ctx, Requests.id(id), Requests.id(template));
    }

    @PostMapping("/people/joiners/{id}/letters")
    public Map<String, Object> issue(SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody IssueInput request) {
        return letters.issue(ctx, Requests.id(id), request);
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
    public List<Map<String, Object>> joinerPolicies(SessionCtx ctx,
                                                    @PathVariable("id") String id) {
        return policies.forJoiner(ctx, Requests.id(id));
    }

    // ── the joiner's own ────────────────────────────────────────────────

    @GetMapping("/onboarding/me/letters")
    public List<Map<String, Object>> myLetters(SessionCtx ctx) {
        return letters.mine(ctx);
    }

    @PostMapping("/onboarding/me/letters/{id}/accept")
    public Map<String, Object> accept(SessionCtx ctx,
                                      @PathVariable("id") String id) {
        return letters.respond(ctx, Requests.id(id), true);
    }

    @PostMapping("/onboarding/me/letters/{id}/decline")
    public Map<String, Object> decline(SessionCtx ctx,
                                       @PathVariable("id") String id) {
        return letters.respond(ctx, Requests.id(id), false);
    }

    @GetMapping("/onboarding/me/policies")
    public List<Map<String, Object>> myPolicies(SessionCtx ctx) {
        return policies.mine(ctx);
    }

    @PostMapping("/onboarding/me/policies/{id}/acknowledge")
    public List<Map<String, Object>> acknowledge(SessionCtx ctx,
                                                 @PathVariable("id") String id, @RequestBody Version request) {
        return policies.acknowledge(ctx, Requests.id(id), request.version());
    }
}

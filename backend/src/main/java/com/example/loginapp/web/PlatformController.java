package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.PayrollRules;
import com.example.loginapp.core.PlatformService;

/** The platform console. Every call needs a console session, which carries no Organization. */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    /** modules: the add-ons bought (attendance, payroll); left out, the Organization gets all of them. */
    public record CreateOrganizationRequest(String name, String slug, String adminEmail, List<String> modules,
                                            Integer licensedEmployees) {}

    public record LicenceRequest(Integer licensedEmployees) {}

    public record ModuleRequest(String module, Boolean on, String trialEndsOn) {}

    public record InviteAdminRequest(String adminEmail) {}

    public record PlatformUserRequest(String email, String role, String password) {}

    public record GrantRequest(String organizationId, String mode, String reason) {}

    private final AuthService auth;
    private final PlatformService platform;
    private final PayrollRules rules;

    public PlatformController(AuthService auth, PlatformService platform, PayrollRules rules) {
        this.auth = auth;
        this.platform = platform;
        this.rules = rules;
    }

    /** State professional tax and labour welfare fund rules: every version, newest first. */
    @GetMapping("/payroll-rules")
    public List<PayrollRules.Rule> rules(SessionCtx ctx) {
        return rules.list(ctx);
    }

    /** Adds a draft version, closing the one in force on its start date. */
    @PostMapping("/payroll-rules")
    public PayrollRules.Rule addRule(SessionCtx ctx, @RequestBody PayrollRules.Input request) {
        return rules.add(ctx, request);
    }

    /** Changes the figures of a draft. */
    @org.springframework.web.bind.annotation.PutMapping("/payroll-rules/{id}")
    public PayrollRules.Rule editRule(SessionCtx ctx, @PathVariable("id") String id,
                                      @RequestBody PayrollRules.Input request) {
        return rules.edit(ctx, Requests.id(id), request);
    }

    /** Records the payroll consultant's sign-off of a draft. */
    @PostMapping("/payroll-rules/{id}/sign-off")
    public PayrollRules.Rule signOffRule(SessionCtx ctx, @PathVariable("id") String id,
                                         @RequestBody PayrollRules.SignOff request) {
        return rules.signOff(ctx, Requests.id(id), request);
    }

    @GetMapping("/organizations")
    public List<Map<String, Object>> organizations(SessionCtx ctx) {
        return platform.organizations(ctx);
    }

    @PostMapping("/organizations")
    public Map<String, Object> createOrganization(SessionCtx ctx,
                                                  @RequestBody CreateOrganizationRequest request) {
        return platform.createOrganization(ctx,
                request.name(), request.slug(), request.adminEmail(), request.modules(), request.licensedEmployees());
    }

    /** Switches Attendance or Payroll on (until a trial end, if given) or off for an Organization. */
    @PostMapping("/organizations/{id}/modules")
    public Map<String, Object> setModule(SessionCtx ctx,
                                         @PathVariable("id") String id, @RequestBody ModuleRequest request) {
        if (request.on() == null) {
            throw com.example.loginapp.core.ApiException.badRequest("Say whether the module is on or off");
        }
        return platform.setModule(ctx, Requests.id(id), request.module(), request.on(), request.trialEndsOn());
    }

    /** Sets the employee licences the Organization has bought; an empty number means no limit. */
    @PostMapping("/organizations/{id}/licences")
    public PlatformService.Licences setLicences(SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody LicenceRequest request) {
        return platform.setLicences(ctx, Requests.id(id), request.licensedEmployees());
    }

    @PostMapping("/organizations/{id}/invite-admin")
    public Map<String, Object> inviteAdmin(SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody InviteAdminRequest request) {
        return platform.inviteFirstAdmin(ctx, Requests.id(id), request.adminEmail());
    }

    /** action is suspend, reactivate or close. */
    @PostMapping("/organizations/{id}/{action}")
    public ResponseEntity<Void> changeOrganization(SessionCtx ctx,
                                                   @PathVariable("id") String id, @PathVariable("action") String action) {
        platform.changeOrganizationStatus(ctx, Requests.id(id), action);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> platformUsers(SessionCtx ctx) {
        return platform.platformUsers(ctx);
    }

    @PostMapping("/users")
    public ResponseEntity<Void> addPlatformUser(SessionCtx ctx,
                                                @RequestBody PlatformUserRequest request) {
        platform.addPlatformUser(ctx, request.email(), request.role(), request.password());
        return ResponseEntity.noContent().build();
    }

    /** action is suspend or reactivate. */
    @PostMapping("/users/{id}/{action}")
    public ResponseEntity<Void> changePlatformUser(SessionCtx ctx,
                                                   @PathVariable("id") String id, @PathVariable("action") String action) {
        if (!action.equals("suspend") && !action.equals("reactivate")) {
            throw ApiException.badRequest("Unknown action: " + action);
        }
        platform.setPlatformUserStatus(ctx, Requests.id(id), action.equals("reactivate"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> grants(SessionCtx ctx) {
        return platform.grants(ctx);
    }

    @PostMapping("/grants")
    public Map<String, Object> requestGrant(SessionCtx ctx,
                                            @RequestBody GrantRequest request) {
        return platform.requestGrant(ctx,
                Requests.id(request.organizationId()), request.mode(), request.reason());
    }

    /** Starts a support session inside the Organization and returns its token. */
    @PostMapping("/grants/{id}/start")
    public Map<String, Object> startSupport(SessionCtx ctx,
                                            @PathVariable("id") String id) {
        String token = platform.startSupportSession(ctx, Requests.id(id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.putAll(auth.me(auth.resolve(token)));
        return body;
    }

    /** action is approve, reject or revoke. */
    @PostMapping("/grants/{id}/{action}")
    public ResponseEntity<Void> decideGrant(SessionCtx ctx,
                                            @PathVariable("id") String id, @PathVariable("action") String action) {
        platform.decideGrant(ctx, Requests.id(id), action);
        return ResponseEntity.noContent().build();
    }
}

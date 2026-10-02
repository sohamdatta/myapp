package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.PlatformService;

/** The platform console. Every call needs a console session, which carries no Organization. */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    public record CreateOrganizationRequest(String name, String slug, String plan, String adminEmail) {}

    public record InviteAdminRequest(String adminEmail) {}

    public record PlatformUserRequest(String email, String role, String password) {}

    public record GrantRequest(String organizationId, String mode, String reason) {}

    private final AuthService auth;
    private final PlatformService platform;

    public PlatformController(AuthService auth, PlatformService platform) {
        this.auth = auth;
        this.platform = platform;
    }

    @GetMapping("/organizations")
    public List<Map<String, Object>> organizations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return platform.organizations(Requests.session(auth, authorization));
    }

    @PostMapping("/organizations")
    public Map<String, Object> createOrganization(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestBody CreateOrganizationRequest request) {
        return platform.createOrganization(Requests.session(auth, authorization),
                request.name(), request.slug(), request.plan(), request.adminEmail());
    }

    @PostMapping("/organizations/{id}/invite-admin")
    public Map<String, Object> inviteAdmin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody InviteAdminRequest request) {
        return platform.inviteFirstAdmin(Requests.session(auth, authorization), Requests.id(id), request.adminEmail());
    }

    /** action is suspend, reactivate or close. */
    @PostMapping("/organizations/{id}/{action}")
    public ResponseEntity<Void> changeOrganization(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable("id") String id, @PathVariable("action") String action) {
        platform.changeOrganizationStatus(Requests.session(auth, authorization), Requests.id(id), action);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> platformUsers(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return platform.platformUsers(Requests.session(auth, authorization));
    }

    @PostMapping("/users")
    public ResponseEntity<Void> addPlatformUser(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody PlatformUserRequest request) {
        platform.addPlatformUser(Requests.session(auth, authorization), request.email(), request.role(), request.password());
        return ResponseEntity.noContent().build();
    }

    /** action is suspend or reactivate. */
    @PostMapping("/users/{id}/{action}")
    public ResponseEntity<Void> changePlatformUser(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable("id") String id, @PathVariable("action") String action) {
        if (!action.equals("suspend") && !action.equals("reactivate")) {
            throw ApiException.badRequest("Unknown action: " + action);
        }
        platform.setPlatformUserStatus(Requests.session(auth, authorization), Requests.id(id), action.equals("reactivate"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> grants(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return platform.grants(Requests.session(auth, authorization));
    }

    @PostMapping("/grants")
    public Map<String, Object> requestGrant(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody GrantRequest request) {
        return platform.requestGrant(Requests.session(auth, authorization),
                Requests.id(request.organizationId()), request.mode(), request.reason());
    }

    /** Starts a support session inside the Organization and returns its token. */
    @PostMapping("/grants/{id}/start")
    public Map<String, Object> startSupport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id) {
        String token = platform.startSupportSession(Requests.session(auth, authorization), Requests.id(id));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.putAll(auth.me(auth.resolve(token)));
        return body;
    }

    /** action is approve, reject or revoke. */
    @PostMapping("/grants/{id}/{action}")
    public ResponseEntity<Void> decideGrant(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id, @PathVariable("action") String action) {
        platform.decideGrant(Requests.session(auth, authorization), Requests.id(id), action);
        return ResponseEntity.noContent().build();
    }
}

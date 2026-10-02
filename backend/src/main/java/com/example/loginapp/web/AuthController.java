package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.SessionCtx;

/** Sign-in, choosing where to work, and accepting an invitation. */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(String email, String password) {}

    public record EnterRequest(String organizationId, Boolean console) {}

    public record AcceptRequest(String token, String password) {}

    private final AuthService auth;
    private final InvitationService invitations;

    public AuthController(AuthService auth, InvitationService invitations) {
        this.auth = auth;
        this.invitations = invitations;
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return withToken(auth.login(request.email(), request.password()));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.me(Requests.session(auth, authorization));
    }

    /** Enters an Organization or the console. The old token stops working and a new one is returned. */
    @PostMapping("/auth/enter")
    public Map<String, Object> enter(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody EnterRequest request) {
        SessionCtx ctx = Requests.session(auth, authorization);
        if (Boolean.TRUE.equals(request.console())) {
            return withToken(auth.enterConsole(ctx));
        }
        if (request.organizationId() == null) {
            throw ApiException.badRequest("Choose an Organization");
        }
        return withToken(auth.enterOrganization(ctx, Requests.id(request.organizationId())));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        SessionCtx ctx = Requests.optionalSession(auth, authorization);
        if (ctx != null) {
            auth.logout(ctx);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations/preview")
    public Map<String, Object> previewInvitation(@RequestParam("token") String token) {
        return invitations.preview(token);
    }

    @PostMapping("/invitations/accept")
    public Map<String, Object> acceptInvitation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AcceptRequest request) {
        SessionCtx signedIn = Requests.optionalSession(auth, authorization);
        return withToken(invitations.accept(request.token(), request.password(), signedIn));
    }

    private Map<String, Object> withToken(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.putAll(auth.me(auth.resolve(token)));
        return body;
    }
}

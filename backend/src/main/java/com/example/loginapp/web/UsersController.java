package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/** Users, roles, invitations and the audit log of the caller's Organization. */
@RestController
@RequestMapping("/api/org")
public class UsersController {

    public record InviteRequest(String email, List<String> roles) {}

    public record RolesRequest(List<String> roles) {}

    private final AuthService auth;
    private final UserService users;

    public UsersController(AuthService auth, UserService users) {
        this.auth = auth;
        this.users = users;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(SessionCtx ctx) {
        return users.users(ctx);
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles(SessionCtx ctx) {
        return users.roles(ctx);
    }

    @PutMapping("/users/{id}/roles")
    public ResponseEntity<Void> changeRoles(SessionCtx ctx,
                                            @PathVariable("id") String id, @RequestBody RolesRequest request) {
        users.changeRoles(ctx, Requests.id(id), request.roles());
        return ResponseEntity.noContent().build();
    }

    /** action is suspend, reactivate or end. */
    @PostMapping("/users/{id}/{action}")
    public ResponseEntity<Void> changeStatus(SessionCtx ctx,
                                             @PathVariable("id") String id, @PathVariable("action") String action) {
        switch (action) {
            case "suspend" -> users.suspend(ctx, Requests.id(id));
            case "reactivate" -> users.reactivate(ctx, Requests.id(id));
            case "end" -> users.end(ctx, Requests.id(id));
            default -> throw ApiException.badRequest("Unknown action: " + action);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations(SessionCtx ctx) {
        return users.invitations(ctx);
    }

    @PostMapping("/invitations")
    public Map<String, Object> invite(SessionCtx ctx,
                                      @RequestBody InviteRequest request) {
        return users.invite(ctx, request.email(), request.roles());
    }

    @PostMapping("/invitations/{id}/resend")
    public Map<String, Object> resend(SessionCtx ctx,
                                      @PathVariable("id") String id) {
        return users.resendInvitation(ctx, Requests.id(id));
    }

    @PostMapping("/invitations/{id}/revoke")
    public ResponseEntity<Void> revoke(SessionCtx ctx,
                                       @PathVariable("id") String id) {
        users.revokeInvitation(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(SessionCtx ctx) {
        return users.auditLog(ctx);
    }
}

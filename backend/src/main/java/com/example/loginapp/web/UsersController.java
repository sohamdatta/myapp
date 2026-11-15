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
import com.example.loginapp.core.MfaService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UserService;

/** Users, roles, invitations and the audit log of the caller's Organization. */
@RestController
@RequestMapping("/api/org")
public class UsersController {

    public record InviteRequest(String email, List<String> roles) {}

    public record RolesRequest(List<String> roles) {}

    public record ResetTwoStepRequest(String reason) {}

    private final AuthService auth;
    private final UserService users;
    private final MfaService mfa;

    private final com.example.loginapp.core.RoleService roleService;

    public UsersController(AuthService auth, UserService users, MfaService mfa, com.example.loginapp.core.RoleService roleService) {
        this.auth = auth;
        this.users = users;
        this.mfa = mfa;
        this.roleService = roleService;
    }

    /** The permissions that can go in a role of the Organization's own. */
    @GetMapping("/roles/permissions")
    public List<Map<String, Object>> rolePermissions(SessionCtx ctx) {
        return roleService.catalogue(ctx);
    }

    @org.springframework.web.bind.annotation.PostMapping("/roles")
    public com.example.loginapp.core.RoleService.RoleSaved createRole(SessionCtx ctx,
            @org.springframework.web.bind.annotation.RequestBody com.example.loginapp.core.RoleService.RoleInput body) {
        return roleService.create(ctx, body);
    }

    @org.springframework.web.bind.annotation.PutMapping("/roles/{id}")
    public com.example.loginapp.core.RoleService.RoleSaved changeRole(SessionCtx ctx, @PathVariable("id") String id,
            @org.springframework.web.bind.annotation.RequestBody com.example.loginapp.core.RoleService.RoleInput body) {
        return roleService.update(ctx, Requests.id(id), body);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> deleteRole(SessionCtx ctx, @PathVariable("id") String id) {
        roleService.delete(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
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

    /** For someone who lost their phone and their recovery codes: turns off their two-step sign-in, with a reason that is audited. */
    @PostMapping("/users/{id}/two-step/reset")
    public ResponseEntity<Void> resetTwoStep(SessionCtx ctx,
                                             @PathVariable("id") String id, @RequestBody ResetTwoStepRequest request) {
        mfa.adminReset(ctx, Requests.id(id), request.reason());
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

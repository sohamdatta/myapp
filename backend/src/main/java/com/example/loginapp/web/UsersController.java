package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public List<Map<String, Object>> users(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.users(Requests.session(auth, authorization));
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.roles(Requests.session(auth, authorization));
    }

    @PutMapping("/users/{id}/roles")
    public ResponseEntity<Void> changeRoles(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id, @RequestBody RolesRequest request) {
        users.changeRoles(Requests.session(auth, authorization), Requests.id(id), request.roles());
        return ResponseEntity.noContent().build();
    }

    /** action is suspend, reactivate or end. */
    @PostMapping("/users/{id}/{action}")
    public ResponseEntity<Void> changeStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @PathVariable("action") String action) {
        SessionCtx ctx = Requests.session(auth, authorization);
        switch (action) {
            case "suspend" -> users.suspend(ctx, Requests.id(id));
            case "reactivate" -> users.reactivate(ctx, Requests.id(id));
            case "end" -> users.end(ctx, Requests.id(id));
            default -> throw ApiException.badRequest("Unknown action: " + action);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.invitations(Requests.session(auth, authorization));
    }

    @PostMapping("/invitations")
    public Map<String, Object> invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody InviteRequest request) {
        return users.invite(Requests.session(auth, authorization), request.email(), request.roles());
    }

    @PostMapping("/invitations/{id}/resend")
    public Map<String, Object> resend(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id) {
        return users.resendInvitation(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/invitations/{id}/revoke")
    public ResponseEntity<Void> revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id) {
        users.revokeInvitation(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return users.auditLog(Requests.session(auth, authorization));
    }
}

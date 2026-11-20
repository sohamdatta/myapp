package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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
import com.example.loginapp.core.EmailChangeService;
import com.example.loginapp.core.InvitationService;
import com.example.loginapp.core.MfaService;
import com.example.loginapp.core.PasswordService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInPage;

import jakarta.servlet.http.HttpServletRequest;

/** Sign-in, choosing where to work, and accepting an invitation. */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record LoginRequest(String email, String password) {}

    public record EnterRequest(String organizationId, Boolean console) {}

    public record AcceptRequest(String token, String password) {}

    public record MfaRequest(String challenge, String code) {}

    public record CodeRequest(String code) {}

    public record RecoveryCodes(java.util.List<String> recoveryCodes) {}

    public record DisableMfaRequest(String password, String code) {}

    public record EmailChangeRequest(String newEmail, String password) {}

    public record TokenRequest(String token) {}

    public record ForgotRequest(String email) {}

    public record ResetRequest(String token, String password) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private final AuthService auth;
    private final InvitationService invitations;
    private final PasswordService passwords;
    private final MfaService mfa;
    private final EmailChangeService emailChanges;
    private final SignInPage pages;
    private final boolean trustForwardedFor;

    public AuthController(AuthService auth, InvitationService invitations, PasswordService passwords, MfaService mfa,
                          EmailChangeService emailChanges, SignInPage pages,
                          @Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.auth = auth;
        this.invitations = invitations;
        this.passwords = passwords;
        this.mfa = mfa;
        this.emailChanges = emailChanges;
        this.pages = pages;
        this.trustForwardedFor = trustForwardedFor;
    }

    /**
     * On an Organization's own sign-in address (see {@link SignInPage}) only its members get a session: anyone else is
     * refused once the password is right, and on an address that leads to no Organization nobody is let in.
     */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        SignInPage.Page page = page(http);
        if (page.kind() == SignInPage.Kind.UNKNOWN) {
            pages.requireEnterable(page, null);   // refuses: no Organization signs in here
        }
        AuthService.SignIn result = auth.signIn(request.email(), request.password(), Requests.clientIp(http, trustForwardedFor));
        if (result.mfaRequired()) {
            return new LinkedHashMap<>(Map.of("mfaRequired", true, "challenge", result.token()));
        }
        pages.requireMember(auth, page, result.token());
        return withToken(result.token());
    }

    /** Answers the challenge a right password earned, with a code from the authenticator app or a recovery code. */
    @PostMapping("/auth/mfa")
    public Map<String, Object> mfa(@RequestBody MfaRequest request, HttpServletRequest http) {
        String token = mfa.complete(request.challenge(), request.code(), Requests.clientIp(http, trustForwardedFor));
        pages.requireMember(auth, page(http), token);
        return withToken(token);
    }

    private SignInPage.Page page(HttpServletRequest http) {
        return pages.forHost(Requests.host(http));
    }

    @GetMapping("/auth/mfa")
    public MfaService.Status mfaStatus(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return mfa.status(Requests.session(auth, authorization));
    }

    @PostMapping("/auth/email-change")
    public ResponseEntity<Void> emailChange(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody EmailChangeRequest request) {
        emailChanges.request(Requests.session(auth, authorization), request.newEmail(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/email-change/confirm")
    public ResponseEntity<Void> emailChangeConfirm(@RequestBody TokenRequest request, HttpServletRequest http) {
        emailChanges.confirm(request.token(), Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/email-change/undo")
    public ResponseEntity<Void> emailChangeUndo(@RequestBody TokenRequest request, HttpServletRequest http) {
        emailChanges.undo(request.token(), Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/step-up")
    public ResponseEntity<Void> stepUp(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody CodeRequest request) {
        mfa.stepUp(Requests.session(auth, authorization), request.code());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/mfa/start")
    public MfaService.Enrolment mfaStart(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return mfa.start(Requests.session(auth, authorization));
    }

    @PostMapping("/auth/mfa/confirm")
    public RecoveryCodes mfaConfirm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody DisableMfaRequest request) {
        return new RecoveryCodes(mfa.confirm(Requests.session(auth, authorization), request.password(), request.code()));
    }

    @PostMapping("/auth/mfa/recovery")
    public RecoveryCodes mfaRecovery(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody CodeRequest request) {
        return new RecoveryCodes(mfa.regenerate(Requests.session(auth, authorization), request.code()));
    }

    @PostMapping("/auth/mfa/disable")
    public ResponseEntity<Void> mfaDisable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody DisableMfaRequest request) {
        mfa.disable(Requests.session(auth, authorization), request.password(), request.code());
        return ResponseEntity.noContent().build();
    }

    /** Always 202 with the same body, whether or not the address has an account. */
    @PostMapping("/auth/forgot")
    public ResponseEntity<Map<String, Object>> forgot(@RequestBody ForgotRequest request, HttpServletRequest http) {
        passwords.requestReset(request.email(), Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.accepted().body(Map.of("message", "If that address has an account, a reset link is on its way"));
    }

    @PostMapping("/auth/reset")
    public ResponseEntity<Void> reset(@RequestBody ResetRequest request, HttpServletRequest http) {
        passwords.reset(request.token(), request.password(), Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Void> changePassword(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody ChangePasswordRequest request) {
        passwords.change(Requests.session(auth, authorization), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return auth.me(Requests.session(auth, authorization));
    }

    /** Enters an Organization or the console. The old token stops working and a new one is returned. */
    @PostMapping("/auth/enter")
    public Map<String, Object> enter(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody EnterRequest request, HttpServletRequest http) {
        SessionCtx ctx = Requests.session(auth, authorization);
        SignInPage.Page page = page(http);
        if (Boolean.TRUE.equals(request.console())) {
            pages.requireEnterable(page, null);   // the console opens only from the shared address
            return withToken(auth.enterConsole(ctx));
        }
        if (request.organizationId() == null) {
            throw ApiException.badRequest("Choose an Organization");
        }
        java.util.UUID tenantId = Requests.id(request.organizationId());
        pages.requireEnterable(page, tenantId);
        return withToken(auth.enterOrganization(ctx, tenantId));
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
    public Map<String, Object> previewInvitation(@RequestParam("token") String token, HttpServletRequest http) {
        invitations.checkPreviewAllowed(Requests.clientIp(http, trustForwardedFor));
        return invitations.preview(token);
    }

    @PostMapping("/invitations/accept")
    public Map<String, Object> acceptInvitation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AcceptRequest request, HttpServletRequest http) {
        SessionCtx signedIn = Requests.optionalSession(auth, authorization);
        return withToken(invitations.accept(request.token(), request.password(), signedIn, Requests.clientIp(http, trustForwardedFor)));
    }

    private Map<String, Object> withToken(String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.putAll(auth.me(auth.resolve(token)));
        return body;
    }
}

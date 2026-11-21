package com.example.loginapp.web;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.OfficialEmailService;
import com.example.loginapp.core.OidcService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInSettingsService;

import jakarta.servlet.http.HttpServletRequest;

/** Sign in with a Google account, and connecting one to a login. */
@RestController
@RequestMapping("/api")
public class OidcController {

    public record Providers(List<String> providers) {}

    public record GoogleDomainRequest(String domain, String password) {}

    public record ProposalRequest(String email) {}

    private static final String COOKIE_PATH = "/api/auth/oidc";

    private final OidcService oidc;
    private final SignInSettingsService settings;
    private final OfficialEmailService officialEmail;
    private final AuthService auth;
    private final com.example.loginapp.core.SignInPage pages;
    private final boolean trustForwardedFor;
    private final boolean secureCookie;

    public OidcController(OidcService oidc, SignInSettingsService settings, OfficialEmailService officialEmail, AuthService auth,
                          com.example.loginapp.core.SignInPage pages, @Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor,
                          @Value("${app.public-url}") String publicUrl) {
        this.oidc = oidc;
        this.settings = settings;
        this.officialEmail = officialEmail;
        this.auth = auth;
        this.pages = pages;
        this.trustForwardedFor = trustForwardedFor;
        this.secureCookie = publicUrl.startsWith("https://");
    }

    /**
     * Which buttons the sign-in page shows. None on an Organization's own address: the provider sends people back to the
     * shared one, where the Organization's page could not hold them to its members.
     */
    @GetMapping("/auth/providers")
    public Providers providers(HttpServletRequest http) {
        return new Providers(pages.forHost(Requests.host(http)).shared() ? oidc.available() : List.of());
    }

    /** The sign-in page sends the browser here; it goes on to the provider. */
    @GetMapping("/auth/oidc/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider, HttpServletRequest http) {
        OidcService.Started started = oidc.startSignIn(provider, Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE, cookie(started.cookie(), OidcService.COOKIE_SECONDS).toString())
                .location(URI.create(started.url())).build();
    }

    /** From the Security page: returns the address to go to, and sets the cookie that ties the round trip to this browser. */
    @PostMapping("/auth/oidc/{provider}/link")
    public ResponseEntity<Object> link(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable String provider, HttpServletRequest http) {
        OidcService.Started started = oidc.startLink(Requests.session(auth, authorization), provider, Requests.clientIp(http, trustForwardedFor));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(started.cookie(), OidcService.COOKIE_SECONDS).toString())
                .body(Map.of("url", started.url()));
    }

    /** The provider sends the browser back here. Always a redirect to a page on this site. */
    @GetMapping("/auth/oidc/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider, @RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         @CookieValue(value = OidcService.COOKIE, required = false) String cookie, HttpServletRequest http) {
        OidcService.Finished done = oidc.callback(provider, code, state, error, cookie, Requests.clientIp(http, trustForwardedFor));
        // The cookie that held the flow is replaced by one that holds the one-time code, when there is one: the code is never in
        // an address, so a link someone else made cannot sign this browser in as them.
        ResponseCookie next = done.handoff() == null ? cookie("", 0) : cookie(done.handoff(), OidcService.HANDOFF_SECONDS);
        return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE, next.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").location(URI.create(done.redirect())).build();
    }

    /** The page trades the one-time code in the cookie for a session (or a challenge, when the login has two-step sign-in). */
    @PostMapping("/auth/oidc/exchange")
    public ResponseEntity<Object> exchange(@CookieValue(value = OidcService.COOKIE, required = false) String code, HttpServletRequest http) {
        return signedIn(oidc.exchange(code, Requests.clientIp(http, trustForwardedFor)), http);
    }

    /**
     * The answer for a Google sign-in that worked. On an Organization's own address the login must belong to it (as with a
     * password); a login that wants a code is held to that when the code is given, as with a password.
     */
    public ResponseEntity<Object> signedIn(AuthService.SignIn result, HttpServletRequest http) {
        if (!result.mfaRequired()) {
            pages.requireMember(auth, pages.forHost(Requests.host(http)), result.token());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (result.mfaRequired()) {
            body.put("mfaRequired", true);
            body.put("challenge", result.token());
        } else {
            body.put("token", result.token());
            body.putAll(auth.me(auth.resolve(result.token())));
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @GetMapping("/auth/external-logins")
    public List<OidcService.Linked> linked(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return oidc.linked(Requests.session(auth, authorization));
    }

    @DeleteMapping("/auth/external-logins/{provider}")
    public ResponseEntity<Void> unlink(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String provider) {
        oidc.unlink(Requests.session(auth, authorization), provider);
        return ResponseEntity.noContent().build();
    }

    /** The Organization's Google Workspace domain (null when Google sign-in is off). */
    @GetMapping("/org/sign-in")
    public SignInSettingsService.View signInSettings(SessionCtx ctx) {
        return settings.get(ctx);
    }

    @PutMapping("/org/sign-in/google")
    public SignInSettingsService.View setGoogleDomain(SessionCtx ctx, @RequestBody GoogleDomainRequest request) {
        return settings.setGoogleDomain(ctx, request.domain(), request.password());
    }

    /** The email domain the Organization says is its own. */
    @PutMapping("/org/sign-in/email-domain")
    public SignInSettingsService.View setEmailDomain(SessionCtx ctx, @RequestBody GoogleDomainRequest request) {
        return settings.setOfficialEmailDomain(ctx, request.domain(), request.password());
    }

    /** Looks for the DNS record that shows the Organization controls its email domain. */
    @PostMapping("/org/sign-in/email-domain/check")
    public SignInSettingsService.View checkEmailDomain(SessionCtx ctx) {
        return settings.checkEmailDomain(ctx);
    }

    /** HR proposes a company address for a member; the member switches from Security. */
    @PutMapping("/org/users/{id}/official-email")
    public ResponseEntity<Void> propose(SessionCtx ctx, @PathVariable UUID id, @RequestBody ProposalRequest request) {
        officialEmail.propose(ctx, id, request.email());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/org/users/{id}/official-email")
    public ResponseEntity<Void> withdraw(SessionCtx ctx, @PathVariable UUID id) {
        officialEmail.withdraw(ctx, id);
        return ResponseEntity.noContent().build();
    }

    /** What the signed-in person has been asked to switch to. */
    @GetMapping("/auth/official-email")
    public List<OfficialEmailService.Proposal> proposals(SessionCtx ctx) {
        return officialEmail.mine(ctx);
    }

    private ResponseCookie cookie(String value, int seconds) {
        return ResponseCookie.from(OidcService.COOKIE, value).httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path(COOKIE_PATH).maxAge(Duration.ofSeconds(seconds)).build();
    }
}

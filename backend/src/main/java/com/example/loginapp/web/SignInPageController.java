package com.example.loginapp.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.SignInPage;

import jakarta.servlet.http.HttpServletRequest;

/** Each Organization's own sign-in address, such as acme.hr.example.com: what the page shows, and the Org Admin's setting. */
@RestController
public class SignInPageController {

    public record SlugRequest(String slug) {}

    /**
     * What the sign-in page at an address shows: organization is null on the shared page and on an Organization address
     * that leads nowhere, which unknown tells apart. sharedAddress is where everyone else signs in.
     */
    public record PageView(SignInPage.Organization organization, boolean unknown, String sharedAddress) {}

    private final SignInPage pages;
    private final String publicUrl;

    public SignInPageController(SignInPage pages, @Value("${app.public-url}") String publicUrl) {
        this.pages = pages;
        this.publicUrl = publicUrl;
    }

    /**
     * What the sign-in page at this address shows: the shared page (organization null, unknown false), an Organization's
     * (its name, slug and logo version), or an Organization address that leads nowhere (unknown true). Needs no sign-in.
     */
    @GetMapping("/api/public/sign-in-page")
    public ResponseEntity<PageView> page(HttpServletRequest http) {
        SignInPage.Page page = pages.forHost(Requests.host(http));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new PageView(page.organization(), page.kind() == SignInPage.Kind.UNKNOWN, pages.addressOf(null, publicUrl)));
    }

    /**
     * Asked by Caddy before it fetches a certificate for a host it has not seen (on-demand TLS): 200 for the shared host and
     * an active Organization's, 404 for anything else, so nobody can make it fetch certificates for names that lead nowhere.
     */
    @GetMapping("/api/public/tls-check")
    public ResponseEntity<Void> tlsCheck(@RequestParam(value = "domain", required = false) String domain) {
        return pages.servesHost(domain) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/org/setup/sign-in-address")
    public SignInPage.Address get(SessionCtx ctx) {
        return pages.get(ctx, publicUrl);
    }

    /** Changes the Organization's short name, and with it its sign-in address. The old address stops working at once. */
    @PutMapping("/api/org/setup/sign-in-address")
    public SignInPage.Address set(SessionCtx ctx, @RequestBody SlugRequest request) {
        return pages.setSlug(ctx, request.slug(), publicUrl);
    }
}

package com.example.loginapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Branding;

/** The Organization's logo: set by the Org Admin, shown to everyone in the Organization. */
@RestController
public class BrandingController {

    private final AuthService auth;
    private final Branding branding;

    public BrandingController(AuthService auth, Branding branding) {
        this.auth = auth;
        this.branding = branding;
    }

    @GetMapping("/api/org/setup/branding")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return branding.get(Requests.session(auth, authorization));
    }

    /** The logo file as the request body (PNG or JPEG, at most 2 MB). */
    @PostMapping("/api/org/setup/branding/logo")
    public Map<String, Object> saveLogo(@RequestHeader(value = "Authorization", required = false) String authorization, InputStream body) {
        var ctx = Requests.session(auth, authorization);
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readNBytes(Branding.MAX_BYTES + 1);
        } catch (IOException e) {
            throw ApiException.badRequest("The upload was interrupted. Try again");
        }
        return branding.saveLogo(ctx, bytes);
    }

    @DeleteMapping("/api/org/setup/branding/logo")
    public Map<String, Object> removeLogo(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return branding.removeLogo(Requests.session(auth, authorization));
    }

    /**
     * The logo itself, for an img tag, so it needs no sign-in: a logo is public. The version in the
     * query changes with each new logo, so it can be cached for long.
     */
    @GetMapping("/api/branding/{tenantId}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String tenantId) {
        Branding.Logo logo = branding.logo(Requests.id(tenantId));
        if (logo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "image/png");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CACHE_CONTROL, "public, max-age=86400");
        headers.set("Content-Security-Policy", "default-src 'none'");
        return new ResponseEntity<>(logo.bytes(), headers, HttpStatus.OK);
    }
}

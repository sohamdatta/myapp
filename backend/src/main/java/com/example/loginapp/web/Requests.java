package com.example.loginapp.web;

import java.util.UUID;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;

/** Reads the caller's session from the Authorization header. */
final class Requests {

    private Requests() {}

    static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    static SessionCtx session(AuthService auth, String authorization) {
        return auth.resolve(bearer(authorization));
    }

    /** The session when the header carries a valid one, otherwise null. */
    static SessionCtx optionalSession(AuthService auth, String authorization) {
        String token = bearer(authorization);
        if (token == null) {
            return null;
        }
        try {
            return auth.resolve(token);
        } catch (ApiException e) {
            return null;
        }
    }

    /**
     * The address the request came from. X-Forwarded-For is used only when the application runs
     * behind a proxy that sets it (app.trust-forwarded-for); otherwise anyone could claim an office address.
     */
    static String clientIp(jakarta.servlet.http.HttpServletRequest http, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = http.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                return hops[hops.length - 1].trim();   // the entry added by the nearest proxy
            }
        }
        return http.getRemoteAddr();
    }

    static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("Not a valid id: " + value);
        }
    }
}

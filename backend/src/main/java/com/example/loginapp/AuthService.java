package com.example.loginapp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Checks credentials against a single demo account and keeps issued
 * tokens in memory. Tokens are lost when the server restarts.
 * Replace with a database and hashed passwords for a real application.
 */
@Service
public class AuthService {

    private final String demoUsername;
    private final String demoPassword;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthService(@Value("${app.demo.username}") String demoUsername,
                       @Value("${app.demo.password}") String demoPassword) {
        this.demoUsername = demoUsername;
        this.demoPassword = demoPassword;
    }

    /** Returns a new token when the credentials are correct. */
    public Optional<String> login(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        boolean userOk = constantTimeEquals(username.trim(), demoUsername);
        boolean passwordOk = constantTimeEquals(password, demoPassword);
        if (!(userOk & passwordOk)) {
            return Optional.empty();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, demoUsername);
        return Optional.of(token);
    }

    /** Returns the username that owns the token, if the token is valid. */
    public Optional<String> usernameFor(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokens.get(token));
    }

    public void logout(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}

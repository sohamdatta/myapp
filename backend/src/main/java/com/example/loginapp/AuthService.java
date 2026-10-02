package com.example.loginapp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks credentials against the users table (passwords are stored as
 * BCrypt hashes) and keeps issued tokens in memory. Tokens are lost when
 * the server restarts, so users sign in again after a restart.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    /** Compared against when the username is unknown, so both cases take similar time. */
    private final String placeholderHash;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.placeholderHash = encoder.encode("placeholder-" + System.nanoTime());
    }

    /** Returns a new token when the credentials are correct. */
    public Optional<String> login(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        String name = username.trim();
        Optional<String> storedHash = users.findPasswordHash(name);
        boolean passwordOk = encoder.matches(password, storedHash.orElse(placeholderHash));
        if (storedHash.isEmpty() || !passwordOk) {
            return Optional.empty();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, name);
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
}

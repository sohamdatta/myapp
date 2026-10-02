package com.example.loginapp.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password hashing and random tokens, using only the JDK. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    public static final int MIN_PASSWORD_LENGTH = 12;

    private Secrets() {}

    /** A 32-byte random token, safe to put in a link or a header. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Only this hash of a token is stored. */
    public static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2-sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void checkPasswordPolicy(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(400, "auth.weak_password",
                    "Choose a password of at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

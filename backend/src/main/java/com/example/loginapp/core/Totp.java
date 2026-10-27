package com.example.loginapp.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 on top of RFC 4226. Authenticator apps use SHA-1, 6 digits and 30-second steps;
 * the other algorithms and 8 digits exist so the RFC's own test vectors can be checked.
 */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    public static final int DIGITS = 6;
    /** How many steps either side of now a code is still accepted, for clocks that disagree a little. */
    public static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    /** The 30-second step a moment falls in. */
    public static long stepAt(Instant moment) {
        return Math.floorDiv(moment.getEpochSecond(), PERIOD_SECONDS);
    }

    /** The code for a step. algorithm is HmacSHA1, HmacSHA256 or HmacSHA512. */
    public static String code(byte[] secret, long step, int digits, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            long modulus = 1;
            for (int i = 0; i < digits; i++) {
                modulus *= 10;
            }
            return String.format("%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS, "HmacSHA1");
    }

    /**
     * The step the code is right for, or -1 when it is wrong or was already used. Only steps after {@code lastUsedStep} count,
     * which is what stops a code (even one still inside its window) being used a second time.
     */
    public static long verify(byte[] secret, String submitted, Instant now, long lastUsedStep) {
        String clean = submitted == null ? "" : submitted.replaceAll("[\\s-]", "");
        if (!clean.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        long current = stepAt(now);
        long found = -1;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            // every step is checked and compared in constant time, so timing says nothing about which one matched
            boolean same = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), clean.getBytes(StandardCharsets.US_ASCII));
            if (same && step > lastUsedStep) {
                found = step;
            }
        }
        return found;
    }

    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        String clean = text.replaceAll("[\\s=-]", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char ch : clean.toCharArray()) {
            int value = BASE32.indexOf(ch);
            if (value < 0) {
                throw new IllegalArgumentException("Not base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** The address an authenticator app's QR code carries. */
    public static String otpauthUri(String issuer, String account, String secretBase32) {
        String label = java.net.URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer="
                + java.net.URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }
}

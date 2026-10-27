package com.example.loginapp.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of sensitive values (PAN, bank account numbers, document bytes)
 * before they reach PostgreSQL, so a database dump or a support session reading
 * tables never sees them in clear.
 *
 * <p>Each value is AES-256-GCM with a fresh 12-byte nonce, laid out as
 * key id (1 byte) || nonce (12) || ciphertext || tag (16). The Organization's id
 * is bound in as associated data, so a value copied into another Organization's
 * row does not decrypt. The key id lets a new key be introduced while values
 * written under the old one can still be read.
 *
 * <p>{@link #fingerprint} is an HMAC of a value, stored beside it to find
 * duplicates (the same PAN on two people) without decrypting anything.
 */
public final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** A fixed key for development and tests only. Never used when a key is configured. */
    public static final String DEVELOPMENT_KEY = "ZGV2ZWxvcG1lbnQtb25seS1rZXktMzItYnl0ZXMhISE=";

    private final byte primaryId;
    private final Map<Byte, SecretKeySpec> keys;
    private final SecretKeySpec hmacKey;

    /**
     * @param primaryId the id written with every new value, 1 to 255
     * @param keys      every key that may still be needed to read, by id; each 32 bytes, base64
     */
    public Crypto(int primaryId, Map<Integer, String> keys) {
        if (primaryId < 1 || primaryId > 255 || !keys.containsKey(primaryId)) {
            throw new IllegalArgumentException("The primary data key id must be 1 to 255 and have a key");
        }
        this.primaryId = (byte) primaryId;
        this.keys = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : keys.entrySet()) {
            this.keys.put((byte) e.getKey().intValue(), new SecretKeySpec(decodeKey(e.getValue()), "AES"));
        }
        // A separate key for fingerprints, derived from the primary key, so the two uses never share a key.
        this.hmacKey = new SecretKeySpec(hmac(new SecretKeySpec(decodeKey(keys.get(primaryId)), "HmacSHA256"),
                "hrms fingerprint key".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    /** One key, id 1. */
    public static Crypto withKey(String base64Key) {
        return new Crypto(1, Map.of(1, base64Key));
    }

    public byte[] encrypt(UUID tenantId, byte[] plain) {
        return seal(tenantId.toString(), plain);
    }

    /**
     * Seals a value for a purpose that is not an Organization ("mail", "totp:" plus an identity id). The purpose
     * is bound in as associated data, so a value made for one purpose does not open as another's, nor as any
     * Organization's.
     */
    public byte[] seal(String purpose, byte[] plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(primaryId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(primaryId).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] encrypt(UUID tenantId, String plain) {
        return encrypt(tenantId, plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Throws when the value was not made for this Organization, was changed, or its key is not configured. */
    public byte[] decrypt(UUID tenantId, byte[] sealed) {
        return open(tenantId.toString(), sealed);
    }

    /** The reverse of {@link #seal}; throws when the purpose differs, the value was changed, or its key is not configured. */
    public byte[] open(String purpose, byte[] sealed) {
        if (sealed == null || sealed.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("The stored value is not an encrypted value");
        }
        SecretKeySpec key = keys.get(sealed[0]);
        if (key == null) {
            throw new IllegalStateException("The stored value was encrypted with data key id " + (sealed[0] & 0xff)
                    + ", which is not configured");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.length - 1 - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The stored value could not be decrypted for this Organization", e);
        }
    }

    public String decryptText(UUID tenantId, byte[] sealed) {
        return new String(decrypt(tenantId, sealed), StandardCharsets.UTF_8);
    }

    /** True when the value was written under an older key and should be re-encrypted. */
    public boolean needsRotation(byte[] sealed) {
        return sealed != null && sealed.length > 0 && sealed[0] != primaryId;
    }

    /** HMAC-SHA256 of a value within one Organization, for finding duplicates without decrypting. */
    public byte[] fingerprint(UUID tenantId, String kind, String value) {
        return hmac(hmacKey, (tenantId + "|" + kind + "|" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decodeKey(String base64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The data key must be base64", e);
        }
        if (key.length != 32) {
            throw new IllegalArgumentException("The data key must be 32 bytes, base64 encoded (openssl rand -base64 32)");
        }
        return key;
    }
}

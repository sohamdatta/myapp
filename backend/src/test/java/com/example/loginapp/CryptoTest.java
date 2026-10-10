package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.Crypto;

class CryptoTest {

    private static final String KEY_1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_2 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    @Test
    void roundTrip() {
        Crypto crypto = Crypto.withKey(KEY_1);
        byte[] sealed = crypto.encrypt(ORG_A, "ABCPE1234F");
        assertEquals("ABCPE1234F", crypto.decryptText(ORG_A, sealed));
        assertEquals(1, sealed[0]);                        // key id
        assertEquals(1 + 12 + 10 + 16, sealed.length);     // id, nonce, text, tag
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("ABCPE1234F"));
    }

    @Test
    void theSameValueEncryptsDifferentlyEachTime() {
        Crypto crypto = Crypto.withKey(KEY_1);
        assertNotEquals(Base64.getEncoder().encodeToString(crypto.encrypt(ORG_A, "123456789")),
                Base64.getEncoder().encodeToString(crypto.encrypt(ORG_A, "123456789")));
    }

    @Test
    void aValueCopiedToAnotherOrganizationDoesNotDecrypt() {
        Crypto crypto = Crypto.withKey(KEY_1);
        byte[] sealed = crypto.encrypt(ORG_A, "ABCPE1234F");
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(ORG_B, sealed));
    }

    @Test
    void aChangedByteIsDetected() {
        Crypto crypto = Crypto.withKey(KEY_1);
        byte[] sealed = crypto.encrypt(ORG_A, "ABCPE1234F");
        sealed[20] ^= 1;
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(ORG_A, sealed));
    }

    @Test
    void aWrongKeyFails() {
        byte[] sealed = Crypto.withKey(KEY_1).encrypt(ORG_A, "ABCPE1234F");
        assertThrows(IllegalStateException.class, () -> Crypto.withKey(KEY_2).decrypt(ORG_A, sealed));
    }

    @Test
    void anUnknownKeyIdSaysSo() {
        byte[] sealed = new Crypto(2, Map.of(2, KEY_2)).encrypt(ORG_A, "x");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Crypto.withKey(KEY_1).decrypt(ORG_A, sealed));
        assertTrue(e.getMessage().contains("key id 2"));
    }

    @Test
    void rotationReadsOldValuesAndWritesWithTheNewKey() {
        byte[] old = Crypto.withKey(KEY_1).encrypt(ORG_A, "old value");
        Crypto rotated = new Crypto(2, Map.of(1, KEY_1, 2, KEY_2));
        assertEquals("old value", rotated.decryptText(ORG_A, old));
        assertTrue(rotated.needsRotation(old));
        byte[] fresh = rotated.encrypt(ORG_A, "new value");
        assertEquals(2, fresh[0]);
        assertFalse(rotated.needsRotation(fresh));
    }

    @Test
    void fingerprintsAreStableAndSeparateByOrganizationAndKind() {
        Crypto crypto = Crypto.withKey(KEY_1);
        assertArrayEquals(crypto.fingerprint(ORG_A, "pan", "ABCPE1234F"), crypto.fingerprint(ORG_A, "pan", "ABCPE1234F"));
        assertFalse(java.util.Arrays.equals(crypto.fingerprint(ORG_A, "pan", "ABCPE1234F"), crypto.fingerprint(ORG_B, "pan", "ABCPE1234F")));
        assertFalse(java.util.Arrays.equals(crypto.fingerprint(ORG_A, "pan", "X"), crypto.fingerprint(ORG_A, "uan", "X")));
    }

    @Test
    void badKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Crypto.withKey("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> Crypto.withKey(Base64.getEncoder().encodeToString(new byte[16])));
        assertThrows(IllegalArgumentException.class, () -> new Crypto(3, Map.of(1, KEY_1)));
    }

    @Test
    void theDevelopmentKeyIsUsable() {
        Crypto crypto = Crypto.withKey(Crypto.DEVELOPMENT_KEY);
        assertEquals("x", crypto.decryptText(ORG_A, crypto.encrypt(ORG_A, "x")));
    }
}

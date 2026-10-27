package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.Totp;

/** RFC 6238 Appendix B (all three algorithms, 8 digits) and RFC 4226 Appendix D, plus the replay and window rules. */
class TotpTest {

    private static final byte[] SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256 = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA512 = "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    /** time, SHA-1, SHA-256, SHA-512 codes from RFC 6238 Appendix B. */
    private static final Object[][] VECTORS = {
        {59L, "94287082", "46119246", "90693936"},
        {1111111109L, "07081804", "68084774", "25091201"},
        {1111111111L, "14050471", "67062674", "99943326"},
        {1234567890L, "89005924", "91819424", "93441116"},
        {2000000000L, "69279037", "90698825", "38618901"},
        {20000000000L, "65353130", "77737706", "47863826"},
    };

    @Test
    void everyRfc6238VectorMatches() {
        for (Object[] v : VECTORS) {
            long step = Totp.stepAt(Instant.ofEpochSecond((Long) v[0]));
            assertEquals(v[1], Totp.code(SHA1, step, 8, "HmacSHA1"), "SHA-1 at " + v[0]);
            assertEquals(v[2], Totp.code(SHA256, step, 8, "HmacSHA256"), "SHA-256 at " + v[0]);
            assertEquals(v[3], Totp.code(SHA512, step, 8, "HmacSHA512"), "SHA-512 at " + v[0]);
        }
    }

    @Test
    void theRfc4226CounterVectorsMatch() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int counter = 0; counter < expected.length; counter++) {
            assertEquals(expected[counter], Totp.code(SHA1, counter), "counter " + counter);
        }
    }

    @Test
    void aCodeIsRightForItsStepAndOneEitherSideAndNoOther() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = Totp.stepAt(now);
        assertEquals(step, Totp.verify(SHA1, Totp.code(SHA1, step), now, -1));
        assertEquals(step - 1, Totp.verify(SHA1, Totp.code(SHA1, step - 1), now, -1), "a slow clock");
        assertEquals(step + 1, Totp.verify(SHA1, Totp.code(SHA1, step + 1), now, -1), "a fast clock");
        assertEquals(-1, Totp.verify(SHA1, Totp.code(SHA1, step - 2), now, -1));
        assertEquals(-1, Totp.verify(SHA1, Totp.code(SHA1, step + 2), now, -1));
        assertEquals(-1, Totp.verify(SHA1, "12345", now, -1), "five digits");
        assertEquals(-1, Totp.verify(SHA1, "abcdef", now, -1));
        assertEquals(-1, Totp.verify(SHA1, null, now, -1));
        String spaced = Totp.code(SHA1, step);
        assertEquals(step, Totp.verify(SHA1, spaced.substring(0, 3) + " " + spaced.substring(3), now, -1), "apps show a space in the middle");
    }

    @Test
    void aCodeIsRefusedOnceItsStepOrALaterOneHasBeenUsed() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = Totp.stepAt(now);
        String code = Totp.code(SHA1, step);
        assertEquals(step, Totp.verify(SHA1, code, now, -1));
        assertEquals(-1, Totp.verify(SHA1, code, now, step), "the same code again");
        assertEquals(-1, Totp.verify(SHA1, Totp.code(SHA1, step - 1), now, step), "an earlier step after a later one was used");
        assertEquals(step + 1, Totp.verify(SHA1, Totp.code(SHA1, step + 1), now, step), "the next step is still fine");
    }

    @Test
    void base32AndTheAuthenticatorAddressAreRight() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(SHA1.length == 20 ? "12345678901234567890".getBytes(StandardCharsets.US_ASCII) : SHA1));
        assertArrayEquals(SHA1, Totp.fromBase32("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq"));
        for (int length = 1; length < 40; length++) {
            byte[] data = new byte[length];
            new java.util.Random(length).nextBytes(data);
            assertArrayEquals(data, Totp.fromBase32(Totp.base32(data)), "length " + length);
        }
        String uri = Totp.otpauthUri("Nexvance HRMS", "a.b@x.test", "ABCDEFGH");
        assertTrue(uri.startsWith("otpauth://totp/Nexvance%20HRMS%3Aa.b%40x.test?secret=ABCDEFGH&issuer=Nexvance%20HRMS"), uri);
        assertTrue(uri.endsWith("&algorithm=SHA1&digits=6&period=30"), uri);
    }
}

package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.IdChecks;

class IdChecksTest {

    private static String validAadhaar() {
        String first11 = "23456789012";
        return first11 + IdChecks.verhoeffDigit(first11);
    }

    @Test
    void panOfAPersonIsAccepted() {
        assertEquals("ABCPE1234F", IdChecks.pan(" abcpe 1234f "));
    }

    @Test
    void panWithAnotherFourthLetterIsRefused() {
        ApiException e = assertThrows(ApiException.class, () -> IdChecks.pan("ABCCE1234F"));   // C = company
        assertTrue(e.getMessage().contains("fourth letter"));
    }

    @Test
    void malformedPanIsRefused() {
        for (String bad : new String[] {"", "ABCPE123F", "ABCPE12345", "1BCPE1234F", "ABCPE1234FF"}) {
            assertThrows(ApiException.class, () -> IdChecks.pan(bad), bad);
        }
    }

    @Test
    void verhoeffKnownExample() {
        assertEquals(3, IdChecks.verhoeffDigit("236"));
        assertTrue(IdChecks.verhoeffValid("2363"));
        assertFalse(IdChecks.verhoeffValid("2364"));
    }

    @Test
    void aadhaarWithAValidCheckDigitIsAccepted() {
        String a = validAadhaar();
        assertEquals(a, IdChecks.aadhaar(a.substring(0, 4) + " " + a.substring(4, 8) + " " + a.substring(8)));
    }

    @Test
    void aadhaarWithOneWrongDigitIsRefused() {
        String a = validAadhaar();
        char last = a.charAt(11);
        String wrong = a.substring(0, 11) + (char) ('0' + ((last - '0' + 1) % 10));
        assertThrows(ApiException.class, () -> IdChecks.aadhaar(wrong));
        // swapping two neighbouring digits is also caught: the point of Verhoeff
        String swapped = a.charAt(0) + "" + a.charAt(2) + a.charAt(1) + a.substring(3);
        if (!swapped.equals(a)) {
            assertThrows(ApiException.class, () -> IdChecks.aadhaar(swapped));
        }
    }

    @Test
    void aadhaarStartingWithZeroOrOneOrTooShortIsRefused() {
        assertThrows(ApiException.class, () -> IdChecks.aadhaar("0" + validAadhaar().substring(1)));
        assertThrows(ApiException.class, () -> IdChecks.aadhaar("1" + validAadhaar().substring(1)));
        assertThrows(ApiException.class, () -> IdChecks.aadhaar("23456789012"));
    }

    @Test
    void ifsc() {
        assertEquals("SBIN0001234", IdChecks.ifsc("sbin0001234"));
        assertEquals("HDFC0ABC123", IdChecks.ifsc("HDFC0ABC123"));
        assertThrows(ApiException.class, () -> IdChecks.ifsc("SBIN1001234"));   // fifth character must be 0
        assertThrows(ApiException.class, () -> IdChecks.ifsc("SBI00001234"));
        assertThrows(ApiException.class, () -> IdChecks.ifsc("SBIN000123"));
    }

    @Test
    void uanEsiAndPfMember() {
        assertEquals("100200300400", IdChecks.uan("1002 0030 0400"));
        assertThrows(ApiException.class, () -> IdChecks.uan("10020030040"));
        assertEquals("3100123456", IdChecks.esiIp("3100123456"));
        assertThrows(ApiException.class, () -> IdChecks.esiIp("31001234"));
        assertEquals("WBCAL00123450000012345", IdChecks.pfMember("WB/CAL/0012345/000/0012345"));
        assertThrows(ApiException.class, () -> IdChecks.pfMember("WBCAL123"));
    }

    @Test
    void bankAccountPinAndState() {
        assertEquals("123456789", IdChecks.bankAccount("123 456 789"));
        assertThrows(ApiException.class, () -> IdChecks.bankAccount("12345678"));
        assertThrows(ApiException.class, () -> IdChecks.bankAccount("1234567890123456789"));
        assertThrows(ApiException.class, () -> IdChecks.bankAccount("12345678A"));
        assertEquals("700001", IdChecks.pin("700 001"));
        assertThrows(ApiException.class, () -> IdChecks.pin("070001"));
        assertEquals("WB", IdChecks.state("wb"));
        assertThrows(ApiException.class, () -> IdChecks.state("XX"));
        assertEquals(36, IdChecks.STATES.size());
    }

    @Test
    void mobileAcceptsCommonWaysOfWritingIt() {
        for (String ok : new String[] {"9876543210", "+91 98765 43210", "919876543210", "09876543210", "98765-43210"}) {
            assertEquals("9876543210", IdChecks.mobile(ok), ok);
        }
        for (String bad : new String[] {"5876543210", "987654321", "98765432101", "abcdefghij"}) {
            assertThrows(ApiException.class, () -> IdChecks.mobile(bad), bad);
        }
    }

    @Test
    void emailAndLastFour() {
        assertEquals("asha@example.in", IdChecks.email(" Asha@Example.IN "));
        assertThrows(ApiException.class, () -> IdChecks.email("asha@example"));
        assertThrows(ApiException.class, () -> IdChecks.email("asha example.in"));
        assertEquals("234F", IdChecks.last4("ABCPE1234F"));
        assertEquals("12", IdChecks.last4("12"));
    }
}

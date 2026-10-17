package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.LeaveAdminService;

/** Input rules the negative browser tests found missing, checked without a database. */
class InputRulesTest {

    @Test
    void officeNetworksAreAddressesOrPrefixes() {
        for (String ok : new String[] {"203.0.113.7", "203.0.113.0/24", "10.0.0.0/8", "::1", "2001:db8::/32", "0.0.0.0/0"}) {
            assertTrue(AttendanceSettingsService.isNetwork(ok), ok);
        }
        for (String bad : new String[] {"not-a-network", "10.0.0.0/33", "256.1.1.1", "1.2.3", "1.2.3.4/", "1.2.3.4/x", "2001:db8::/129", "a/b/c"}) {
            assertFalse(AttendanceSettingsService.isNetwork(bad), bad);
        }
    }

    @Test
    void leaveIsInWholeOrHalfDays() {
        for (String ok : new String[] {"0", "1", "1.5", "12", "12.50", "366"}) {
            assertTrue(LeaveAdminService.wholeOrHalf(new BigDecimal(ok)), ok);
        }
        for (String bad : new String[] {"2.3", "0.25", "1.75", "10.1"}) {
            assertFalse(LeaveAdminService.wholeOrHalf(new BigDecimal(bad)), bad);
        }
    }

    @Test
    void anImportIsText() {
        assertTrue(AttendanceImportService.looksLikeText("Code,Name\nE001,Harish Rao\n"));
        assertTrue(AttendanceImportService.looksLikeText("Code\tName\r\nE001\tமீரா\r\n"));
        assertFalse(AttendanceImportService.looksLikeText("\u0089PNG\r\n\u001a\n\u0000\u0000\u0000\rIHDR"));
        assertFalse(AttendanceImportService.looksLikeText("����PK\u0003\u0004��"));
    }
}

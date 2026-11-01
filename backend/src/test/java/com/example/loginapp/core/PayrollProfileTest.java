package com.example.loginapp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a new pay profile starts as, by employment type. HR can change any of it for a person. */
class PayrollProfileTest {

    @Test
    void staffAreInPfAndPayProfessionalTaxAndWelfareFund() {
        for (String type : new String[] {"permanent", "probation", "fixed_term"}) {
            Map<String, Object> p = PayrollPay.defaultProfile(type);
            assertEquals(true, p.get("pfMember"), type);
            assertEquals(false, p.get("esiExempt"), type);
            assertEquals(false, p.get("ptExempt"), type);
            assertEquals(false, p.get("lwfExempt"), type);
        }
    }

    @Test
    void aConsultantIsNotEmployedSoNoProfessionalTaxOrWelfareFund() {
        Map<String, Object> p = PayrollPay.defaultProfile("consultant");
        assertEquals(false, p.get("pfMember"));
        assertEquals(true, p.get("ptExempt"));
        assertEquals(true, p.get("lwfExempt"));
    }

    @Test
    void anInternKeepsTheDefaultsUntilTheConsultantConfirmsTheRule() {
        Map<String, Object> p = PayrollPay.defaultProfile("intern");
        assertEquals(false, p.get("pfMember"));
        assertEquals(false, p.get("ptExempt"));
        assertEquals(false, p.get("lwfExempt"));
    }
}

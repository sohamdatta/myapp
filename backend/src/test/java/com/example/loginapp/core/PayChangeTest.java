package com.example.loginapp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Why one month's pay differs from the month before, from the two payslips alone. */
class PayChangeTest {

    private static PayChange.Line line(String code, String name, String amount, boolean oneTime) {
        return new PayChange.Line(code, name, new BigDecimal(amount), oneTime);
    }

    private static PayChange.Slip slip(int paid, int inPeriod, String annual, PayChange.Line... lines) {
        return new PayChange.Slip(new BigDecimal(paid), new BigDecimal(inPeriod), new BigDecimal(annual), "2025-04-01", List.of(lines));
    }

    @Test
    void theLargestCauseComesFirstAndAMissingLineIsNamed() {
        List<PayChange.Cause> causes = PayChange.diff(
                slip(31, 31, "900000", line("BASIC", "Basic", "37500", false), line("BONUS", "Bonus", "5000", false)),
                slip(30, 30, "900000", line("BASIC", "Basic", "36250", false)));
        assertEquals(2, causes.size());
        assertEquals("Bonus last month, not this month", causes.get(0).text());
        assertEquals(0, new BigDecimal("-5000").compareTo(causes.get(0).amount()));
        assertTrue(causes.get(1).text().contains("30 of 30 days paid"), causes.get(1).text());
    }

    @Test
    void aRevisionOfPayAndAOneTimeAmountAreToldApart() {
        List<PayChange.Cause> causes = PayChange.diff(
                slip(31, 31, "900000", line("BASIC", "Basic", "37500", false)),
                slip(31, 31, "960000", line("BASIC", "Basic", "40000", false), line("BONUS", "Bonus", "10000", true)));
        assertEquals("Bonus this month", causes.get(0).text());
        assertEquals("Pay revised from 2025-04-01", causes.get(1).text());
    }

    @Test
    void nothingMovedIsNoCauseAndTiesReadTheSameEveryTime() {
        assertEquals(List.of(), PayChange.diff(slip(31, 31, "900000", line("BASIC", "Basic", "37500", false)),
                slip(31, 31, "900000", line("BASIC", "Basic", "37500", false))));
        List<PayChange.Cause> tie = PayChange.diff(slip(31, 31, "900000", line("A", "Alpha", "100", false), line("B", "Beta", "100", false)),
                slip(31, 31, "900000", line("A", "Alpha", "200", false), line("B", "Beta", "200", false)));
        assertEquals("Alpha changed", tie.get(0).text());
        assertEquals("Beta changed", tie.get(1).text());
    }
}

package com.example.loginapp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Why one month's pay differs from the month before, from the two payslips alone. */
class PayChangeTest {

    private static PayChange.Line line(String code, String name, String amount, boolean oneTime) {
        return new PayChange.Line(code, name, new BigDecimal(amount), oneTime);
    }

    private static PayChange.Slip slip(int paid, int inPeriod, String annual, PayChange.Line... lines) {
        return new PayChange.Slip(new BigDecimal(paid), new BigDecimal(inPeriod), new BigDecimal(annual), "2025-04-01", List.of(lines), null, null);
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

    @Test
    void lossOfPayIsNamedByItsDatesWhenTheMonthHasThem() {
        PayChange.Slip before = new PayChange.Slip(new BigDecimal("31"), new BigDecimal("31"), new BigDecimal("900000"), "2025-04-01",
                List.of(line("BASIC", "Basic", "37500", false)), null, BigDecimal.ZERO);
        PayChange.Slip now = new PayChange.Slip(new BigDecimal("30"), new BigDecimal("31"), new BigDecimal("900000"), "2025-04-01",
                List.of(line("BASIC", "Basic", "36290", false)), PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-14", "days", BigDecimal.ONE, "source", "leave")), null),
                BigDecimal.ONE);
        assertEquals("1 day of loss of pay on 14 Mar", PayChange.diff(before, now).get(0).text());
    }

    @Test
    void lossOfPayWordsGroupDatesHalfDaysAndDaysHrEntered() {
        assertEquals(null, PayChange.lossOfPayWords(List.of(), null));
        assertEquals("2 days of loss of pay (14 Mar, 15 Mar)", PayChange.lossOfPayWords(List.of(
                Map.of("date", "2026-03-14", "days", BigDecimal.ONE), Map.of("date", "2026-03-15", "days", BigDecimal.ONE)), null));
        assertEquals("0.5 days of loss of pay on 14 Mar (half day)", PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-14", "days", new BigDecimal("0.5"))), null));
        assertEquals("2 days of loss of pay entered by HR", PayChange.lossOfPayWords(List.of(), new BigDecimal("2")));
        assertEquals("1 day of loss of pay on 14 Mar, and 1.5 days of loss of pay entered by HR",
                PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-14", "days", BigDecimal.ONE)), new BigDecimal("1.5")));
    }

    private static PayChange.Slip withLoss(int paid, int inPeriod, String lop, String lines, String words) {
        return new PayChange.Slip(new BigDecimal(paid), new BigDecimal(inPeriod), new BigDecimal("900000"), "2025-04-01",
                List.of(line("BASIC", "Basic", lines, false)), words, new BigDecimal(lop));
    }

    @Test
    void aLeaverIsNotBlamedOnOneDayOfLossOfPay() {
        // Left on the 10th (10 of 31 days) with one day of loss of pay as well: the drop is mostly the leaving.
        String words = PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-04", "days", BigDecimal.ONE)), null);
        PayChange.Slip now = withLoss(9, 31, "1", "10000", words);
        PayChange.Slip before = withLoss(31, 31, "0", "37500", null);
        assertTrue(PayChange.diff(before, now).get(0).text().contains("days paid"), PayChange.diff(before, now).get(0).text());
    }

    @Test
    void fallingLossOfPayIsNotCalledLossOfPay() {
        PayChange.Slip before = withLoss(28, 31, "3", "33900", PayChange.lossOfPayWords(List.of(Map.of("date", "2026-02-02", "days", new BigDecimal("3"))), null));
        PayChange.Slip now = withLoss(30, 31, "1", "36300", PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-14", "days", BigDecimal.ONE)), null));
        assertEquals("1 day of loss of pay on 14 Mar (last month 3 days)", PayChange.diff(before, now).get(0).text());
        PayChange.Slip none = withLoss(31, 31, "0", "37500", null);
        assertEquals("No loss of pay this month (last month 3 days)", PayChange.diff(before, none).get(0).text());
    }

    @Test
    void theSameLossOfPayInMonthsOfDifferentLengthIsNotBlamedOnIt() {
        PayChange.Slip feb = withLoss(27, 28, "1", "36160", PayChange.lossOfPayWords(List.of(Map.of("date", "2026-02-10", "days", BigDecimal.ONE)), null));
        PayChange.Slip mar = withLoss(30, 31, "1", "36290", PayChange.lossOfPayWords(List.of(Map.of("date", "2026-03-14", "days", BigDecimal.ONE)), null));
        assertTrue(PayChange.diff(feb, mar).get(0).text().contains("days paid"), PayChange.diff(feb, mar).get(0).text());
    }
}

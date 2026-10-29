package com.example.loginapp.core;

import java.time.LocalDate;

/** Indian tax years (April to March), written 2026-27. */
final class TaxYears {

    private TaxYears() {}

    /** The tax year a date falls in. */
    static String of(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** The first day of a tax year. */
    static LocalDate start(String taxYear) {
        return LocalDate.of(Integer.parseInt(taxYear.substring(0, 4)), 4, 1);
    }

    /** The given tax year if it is well formed, the fallback if it is blank. */
    static String check(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (!value.matches("^[0-9]{4}-[0-9]{2}$") || (Integer.parseInt(value.substring(0, 4)) + 1) % 100 != Integer.parseInt(value.substring(5))) {
            throw ApiException.badRequest("The tax year must look like 2026-27");
        }
        return value;
    }

    /** The first month of a quarter of the tax year: quarter 1 is April to June, quarter 4 is January to March. */
    static LocalDate quarterStart(String taxYear, int quarter) {
        return start(taxYear).plusMonths(3L * (quarter - 1));
    }
}

package com.example.loginapp.core;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Format checks for Indian identifiers and contact details. Each method returns
 * the value in its stored form (upper case, spaces and dashes removed) or throws
 * a 400 that says what is wrong. Pure functions: no database, no clock.
 */
public final class IdChecks {

    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
    private static final Pattern PIN = Pattern.compile("^[1-9][0-9]{5}$");
    private static final Pattern MOBILE = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    /** States and union territories, ISO 3166-2:IN codes. Professional tax and holidays follow the state. */
    public static final Map<String, String> STATES;

    static {
        Map<String, String> s = new TreeMap<>();
        String[][] list = {
            {"AN", "Andaman and Nicobar Islands"}, {"AP", "Andhra Pradesh"}, {"AR", "Arunachal Pradesh"},
            {"AS", "Assam"}, {"BR", "Bihar"}, {"CH", "Chandigarh"}, {"CT", "Chhattisgarh"},
            {"DH", "Dadra and Nagar Haveli and Daman and Diu"}, {"DL", "Delhi"}, {"GA", "Goa"},
            {"GJ", "Gujarat"}, {"HP", "Himachal Pradesh"}, {"HR", "Haryana"}, {"JH", "Jharkhand"},
            {"JK", "Jammu and Kashmir"}, {"KA", "Karnataka"}, {"KL", "Kerala"}, {"LA", "Ladakh"},
            {"LD", "Lakshadweep"}, {"MH", "Maharashtra"}, {"ML", "Meghalaya"}, {"MN", "Manipur"},
            {"MP", "Madhya Pradesh"}, {"MZ", "Mizoram"}, {"NL", "Nagaland"}, {"OR", "Odisha"},
            {"PB", "Punjab"}, {"PY", "Puducherry"}, {"RJ", "Rajasthan"}, {"SK", "Sikkim"},
            {"TG", "Telangana"}, {"TN", "Tamil Nadu"}, {"TR", "Tripura"}, {"UP", "Uttar Pradesh"},
            {"UT", "Uttarakhand"}, {"WB", "West Bengal"},
        };
        for (String[] row : list) {
            s.put(row[0], row[1]);
        }
        STATES = java.util.Collections.unmodifiableMap(s);
    }

    private IdChecks() {}

    /** PAN of a person: five letters, four digits, a letter, with P as the fourth letter. */
    public static String pan(String value) {
        String v = compact(value);
        if (!PAN.matcher(v).matches()) {
            throw invalid("pan", "A PAN has five letters, four digits and a letter, like ABCPE1234F");
        }
        if (v.charAt(3) != 'P') {
            throw invalid("pan", "This PAN is not an individual's: the fourth letter of a person's PAN is P");
        }
        return v;
    }

    /**
     * Aadhaar: 12 digits, not starting with 0 or 1, with a valid Verhoeff check digit.
     * Only the last four digits are ever stored; the caller discards the rest.
     */
    public static String aadhaar(String value) {
        String v = compact(value);
        if (v.length() != 12 || !DIGITS.matcher(v).matches() || v.charAt(0) == '0' || v.charAt(0) == '1') {
            throw invalid("aadhaar", "An Aadhaar number has 12 digits and does not start with 0 or 1");
        }
        if (!verhoeffValid(v)) {
            throw invalid("aadhaar", "This Aadhaar number is not valid; check the digits");
        }
        return v;
    }

    public static String uan(String value) {
        String v = compact(value);
        if (v.length() != 12 || !DIGITS.matcher(v).matches()) {
            throw invalid("uan", "A UAN has 12 digits");
        }
        return v;
    }

    /** ESI insurance number: 10 digits. */
    public static String esiIp(String value) {
        String v = compact(value);
        if (v.length() != 10 || !DIGITS.matcher(v).matches()) {
            throw invalid("esi_ip", "An ESI insurance number has 10 digits");
        }
        return v;
    }

    /** Previous PF member id, like WBCAL00123450000012345: region, office, establishment, extension, account. */
    public static String pfMember(String value) {
        String v = compact(value).replace("/", "");
        if (!v.matches("^[A-Z]{5}[0-9]{7}[0-9A-Z]{3}[0-9]{7}$")) {
            throw invalid("pf_member", "A PF member id looks like WBCAL00123450000012345");
        }
        return v;
    }

    public static String ifsc(String value) {
        String v = compact(value);
        if (!IFSC.matcher(v).matches()) {
            throw invalid("ifsc", "An IFSC has 11 characters: four letters, a zero, then six letters or digits");
        }
        return v;
    }

    public static String bankAccount(String value) {
        String v = compact(value);
        if (v.length() < 9 || v.length() > 18 || !DIGITS.matcher(v).matches()) {
            throw invalid("bank_account", "A bank account number has 9 to 18 digits");
        }
        return v;
    }

    public static String pin(String value) {
        String v = compact(value);
        if (!PIN.matcher(v).matches()) {
            throw invalid("pin", "A PIN code has 6 digits and does not start with 0");
        }
        return v;
    }

    /** An Indian mobile number, stored as its 10 digits; +91 or a leading 0 is accepted and dropped. */
    public static String mobile(String value) {
        String v = compact(value);
        if (v.startsWith("+91")) {
            v = v.substring(3);
        } else if (v.length() == 12 && v.startsWith("91")) {
            v = v.substring(2);
        } else if (v.length() == 11 && v.startsWith("0")) {
            v = v.substring(1);
        }
        if (!MOBILE.matcher(v).matches()) {
            throw invalid("mobile", "An Indian mobile number has 10 digits and starts with 6, 7, 8 or 9");
        }
        return v;
    }

    public static String email(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.length() > 254 || !EMAIL.matcher(v).matches()) {
            throw invalid("email", "That does not look like an email address");
        }
        return v;
    }

    public static String state(String value) {
        String v = compact(value);
        if (!STATES.containsKey(v)) {
            throw invalid("state", "Choose a state or union territory");
        }
        return v;
    }

    /** The last four characters, which is all that lists and most screens ever show. */
    public static String last4(String value) {
        return value.substring(Math.max(0, value.length() - 4));
    }

    /** "XXXXXX1234" style masking for display. */
    public static String masked(String last4) {
        return last4 == null ? null : "••••" + last4;
    }

    // ── Verhoeff ────────────────────────────────────────────────────────

    private static final int[][] D = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
        {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
        {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] P = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
        {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};
    private static final int[] INV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    /** True when the last digit is the Verhoeff check digit of the others. */
    public static boolean verhoeffValid(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][d]];
        }
        return c == 0;
    }

    /** The check digit to append to the digits given. */
    public static int verhoeffDigit(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[(i + 1) % 8][d]];
        }
        return INV[c];
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(400, "employee.invalid_" + field, message);
    }
}

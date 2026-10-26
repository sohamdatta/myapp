package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.example.loginapp.core.Json;
import com.example.loginapp.core.PayrollEngine;

/**
 * Golden payslips: whole payslips for fixed inputs, kept as files in
 * {@code src/test/resources/golden/payslips}, so that a change to any payroll figure shows up as a
 * reviewed diff to those files and never as a surprise in a customer's pay run.
 *
 * <p>Each {@code NN-name.json} holds one scenario: why it exists, the month, the employee (changes to
 * a standard employee), optional changes to the shared {@code config.json}, and the {@code expected}
 * payslip: gross, deductions, net, employer cost, every line in order, and the exceptions raised.
 * The pure engine runs on exactly that; there is no database and no clock.
 *
 * <p>The standard employee has been paid nothing earlier in this tax year (as someone starting on
 * 1 October), so the engine projects six months of income; scenarios that need a full year give the
 * year's history in {@code ytd}, and say so.
 *
 * <p><b>Status: provisional.</b> The expected figures were produced by the engine and checked by
 * hand against the formulas in each file's {@code why}, but they have not yet been compared with the
 * payroll consultant's spreadsheet. When that is done, replace the figures with the consultant's, set
 * {@code "status": "signed-off"} and name the sign-off date; a scenario that stays provisional says so
 * in the build log below.
 *
 * <p>To change a figure on purpose: run {@code mvn test -Dtest=GoldenPayslipsTest -Dgolden.update=true},
 * read the diff, and explain it in the pull request. Updating refuses to run in CI.
 */
class GoldenPayslipsTest {

    private static final Path DIR = Path.of("src/test/resources/golden/payslips");
    private static final boolean UPDATE = "true".equals(System.getProperty("golden.update"));
    /** Fewer than this many scenarios means one was deleted rather than changed. */
    private static final int AT_LEAST = 18;

    @TestFactory
    Stream<DynamicTest> everyScenarioGivesItsGoldenPayslip() throws IOException {
        if (UPDATE) {
            assertTrue(System.getenv("CI") == null, "golden.update is for a developer's machine; CI must compare, not rewrite");
        }
        return scenarios().map(file -> DynamicTest.dynamicTest(file.getFileName().toString().replace(".json", ""), () -> check(file)));
    }

    @Test
    void noScenarioHasBeenDeleted() throws IOException {
        List<Path> found = scenarios().toList();
        assertTrue(found.size() >= AT_LEAST, "expected at least " + AT_LEAST + " golden scenarios, found " + found.size());
    }

    @Test
    void everyScenarioSaysWhyItExistsAndWhetherItIsSignedOff() throws IOException {
        for (Path file : scenarios().toList()) {
            Map<String, Object> doc = read(file);
            assertFalse(String.valueOf(doc.get("why")).isBlank() || doc.get("why") == null, file + " needs a \"why\"");
            assertTrue(List.of("provisional", "signed-off").contains(doc.get("status")), file + " needs \"status\": provisional or signed-off");
        }
    }

    private static Stream<Path> scenarios() throws IOException {
        try (Stream<Path> all = Files.list(DIR)) {
            return all.filter(p -> p.getFileName().toString().matches("\\d\\d-.*\\.json")).sorted().toList().stream();
        }
    }

    // ── one scenario ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void check(Path file) throws IOException {
        Map<String, Object> doc = read(file);
        Map<String, Object> config = (Map<String, Object>) merge(read(DIR.resolve("config.json")), (Map<String, Object>) doc.get("configPatch"));
        Map<String, Object> employee = employee((Map<String, Object>) doc.get("employee"));
        LocalDate period = LocalDate.parse((String) doc.get("period"));

        PayrollEngine.Calc calc = PayrollEngine.calculate(config, employee, period);
        Map<String, Object> actual = expectedOf(calc);

        if (UPDATE) {
            doc.put("expected", actual);
            Files.writeString(file, pretty(doc, 0) + "\n", StandardCharsets.UTF_8);
            return;
        }
        Map<String, Object> expected = (Map<String, Object>) doc.get("expected");
        assertTrue(expected != null && !expected.isEmpty(), file + " has no expected payslip; run with -Dgolden.update=true");
        String name = file.getFileName().toString();
        for (String total : List.of("gross", "deductions", "net", "employerCost")) {
            assertEquals(0, new BigDecimal((String) expected.get(total)).compareTo(new BigDecimal((String) actual.get(total))),
                    name + ": " + total + " changed from " + expected.get(total) + " to " + actual.get(total));
        }
        assertLines(name, (List<Map<String, Object>>) expected.get("lines"), (List<Map<String, Object>>) actual.get("lines"));
        assertEquals(exceptionsOf((List<Map<String, Object>>) expected.get("exceptions")),
                exceptionsOf((List<Map<String, Object>>) actual.get("exceptions")), name + ": exceptions changed");
    }

    private static void assertLines(String name, List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        List<String> want = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (Map<String, Object> l : expected) {
            want.add(l.get("code") + " " + new BigDecimal((String) l.get("amount")).stripTrailingZeros().toPlainString());
        }
        for (Map<String, Object> l : actual) {
            got.add(l.get("code") + " " + new BigDecimal((String) l.get("amount")).stripTrailingZeros().toPlainString());
        }
        assertEquals(want, got, name + ": payslip lines changed (code, amount, in order)");
    }

    private static List<String> exceptionsOf(List<Map<String, Object>> list) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> x : list == null ? List.<Map<String, Object>>of() : list) {
            out.add(x.get("level") + " " + x.get("code"));
        }
        return out;
    }

    private static Map<String, Object> expectedOf(PayrollEngine.Calc calc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gross", calc.gross().toPlainString());
        out.put("deductions", calc.deductions().toPlainString());
        out.put("net", calc.net().toPlainString());
        out.put("employerCost", calc.employer().toPlainString());
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> l : calc.lines()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("code", l.get("code"));
            line.put("kind", l.get("kind"));
            line.put("amount", ((BigDecimal) l.get("amount")).toPlainString());
            lines.add(line);
        }
        out.put("lines", lines);
        List<Object> exceptions = new ArrayList<>();
        for (Map<String, Object> x : calc.exceptions()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("level", x.get("level"));
            e.put("code", x.get("code"));
            exceptions.add(e);
        }
        out.put("exceptions", exceptions);
        return out;
    }

    // ── building the inputs ─────────────────────────────────────────────

    /** A standard employee (Asha, 9 lakh, Karnataka, new regime) with the scenario's changes laid over it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> employee(Map<String, Object> patch) {
        Map<String, Object> emp = new LinkedHashMap<>();
        emp.put("employmentId", UUID.nameUUIDFromBytes("golden".getBytes(StandardCharsets.UTF_8)).toString());
        emp.put("name", "Asha");
        emp.put("type", "permanent");
        emp.put("gender", "male");
        emp.put("state", "KA");
        emp.put("metro", false);
        emp.put("structureId", "s1");
        emp.put("annual", new BigDecimal("900000"));
        emp.put("overrides", new LinkedHashMap<>());
        emp.put("inputs", new LinkedHashMap<>());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("pfMember", true);
        profile.put("epsEligible", true);
        profile.put("taxRegime", "new");
        emp.put("profile", profile);
        emp.put("days", new LinkedHashMap<>(Map.of("base", new BigDecimal("31"), "paid", new BigDecimal("31"), "lop", BigDecimal.ZERO)));
        emp.put("ytd", Map.of("taxable", BigDecimal.ZERO, "tds", BigDecimal.ZERO, "pt", BigDecimal.ZERO, "pfEe", BigDecimal.ZERO));
        emp.put("hasBank", true);
        emp.put("hasPan", true);
        emp.put("hasUan", true);
        return (Map<String, Object>) merge(emp, patch);
    }

    /** Lays {@code patch} over {@code base}: maps are merged key by key, anything else is replaced. */
    @SuppressWarnings("unchecked")
    private static Object merge(Map<String, Object> base, Map<String, Object> patch) {
        if (patch == null) {
            return base;
        }
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object current = out.get(e.getKey());
            if (current instanceof Map<?, ?> m && e.getValue() instanceof Map<?, ?> p) {
                out.put(e.getKey(), merge((Map<String, Object>) m, (Map<String, Object>) p));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ── writing a scenario back (update mode): stable, readable, one line per payslip line ──

    private static String pretty(Object value, int depth) {
        String pad = "  ".repeat(depth);
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            if (depth >= 3 && map.values().stream().noneMatch(v -> v instanceof Map || v instanceof Collection)) {
                List<String> parts = new ArrayList<>();
                map.forEach((k, v) -> parts.add(quote(String.valueOf(k)) + ": " + pretty(v, depth + 1)));
                return "{" + String.join(", ", parts) + "}";
            }
            List<String> parts = new ArrayList<>();
            map.forEach((k, v) -> parts.add(pad + "  " + quote(String.valueOf(k)) + ": " + pretty(v, depth + 1)));
            return "{\n" + String.join(",\n", parts) + "\n" + pad + "}";
        }
        if (value instanceof Collection<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            if (list.stream().allMatch(v -> !(v instanceof Map) && !(v instanceof Collection))) {
                return "[" + String.join(", ", list.stream().map(v -> pretty(v, depth + 1)).toList()) + "]";
            }
            List<String> parts = new ArrayList<>();
            list.forEach(v -> parts.add(pad + "  " + pretty(v, depth + 1)));
            return "[\n" + String.join(",\n", parts) + "\n" + pad + "]";
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Boolean || value == null) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

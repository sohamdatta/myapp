package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.NumberSeries;
import com.example.loginapp.core.Pdf;
import com.example.loginapp.core.Templates;

/** The template engine, the PDF writer and employee code patterns, without a database. */
class TemplatesTest {

    @Test
    void everyPlaceholderFillsAndUnknownOnesAreRefused() {
        Map<String, String> values = new java.util.HashMap<>();
        for (String key : Templates.MESSAGE.keySet()) {
            values.put(key, "<" + key + ">");
        }
        String all = String.join(" ", Templates.MESSAGE.keySet().stream().map(k -> "{{" + k + "}}").toList());
        Templates.check(all, Templates.MESSAGE);
        String filled = Templates.render(all, values);
        for (String key : Templates.MESSAGE.keySet()) {
            assertTrue(filled.contains("<" + key + ">"), key);
        }
        assertEquals("Dear Asha,", Templates.render("Dear {{ person.first_name }},", Map.of("person.first_name", "Asha")));
        assertEquals("Hello .", Templates.render("Hello {{person.first_name}}.", Map.of()));

        ApiException e = assertThrows(ApiException.class, () -> Templates.check("Dear {{person.firstname}} {{salary}}", Templates.LETTER));
        assertEquals("template.unknown_placeholder", e.code());
        assertTrue(e.getMessage().contains("{{person.firstname}}") && e.getMessage().contains("{{salary}}"));
        assertThrows(ApiException.class, () -> Templates.check("Dear {{person.legal_name}", Templates.LETTER));
        // Message-only placeholders are not for letters.
        assertThrows(ApiException.class, () -> Templates.check("{{link}}", Templates.LETTER));
        assertEquals(List.of("a.b", "c"), Templates.placeholders("{{a.b}} {{c}} {{a.b}}"));
    }

    @Test
    void aValueIsInsertedAsTextOnly() {
        // A name that looks like a placeholder, or like markup, is put in as it is and not read again.
        String out = Templates.render("To {{person.legal_name}} at {{organization.name}}",
                Map.of("person.legal_name", "{{organization.name}} <script>x</script> $1 \\", "organization.name", "Globex"));
        assertEquals("To {{organization.name}} <script>x</script> $1 \\ at Globex", out);
    }

    @Test
    void aLetterBecomesAValidPdfWithTheTextEscaped() {
        byte[] pdf = Pdf.letter("Offer (draft)", "Globex Ltd\n\nDear Asha (Sen) \\ ₹ 50,000 – नमस्ते\n\n" + "word ".repeat(2000));
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(raw.startsWith("%PDF-1.4"));
        assertTrue(raw.trim().endsWith("%%EOF"));
        assertTrue(raw.contains("(Dear Asha \\(Sen\\) \\\\ Rs. 50,000 \u0096 ??????)"), "escaped text");
        assertTrue(raw.contains("/Count 2") || raw.contains("/Count 3"), "long text runs onto more pages");
        assertTrue(raw.contains("(Page 1 of "));
        // The cross-reference table points at each object.
        int xref = Integer.parseInt(raw.substring(raw.lastIndexOf("startxref") + 10, raw.lastIndexOf("%%EOF")).trim());
        assertTrue(raw.startsWith("xref", xref));
        String first = raw.substring(xref).split("\n")[3];
        assertTrue(raw.startsWith("1 0 obj", Integer.parseInt(first.substring(0, 10))));
        assertFalse(raw.contains("नमस्ते"));
    }

    @Test
    void codesAreMadeFromTheirPattern() {
        Map<String, String> t = NumberSeries.tokens(LocalDate.of(2026, 3, 9), "Bengaluru office", "Engineering", "intern");
        assertEquals("GLX-26-", NumberSeries.expand("GLX-{YY}-", t));
        assertEquals("BEN/ENG/IN/2026/03/", NumberSeries.expand("{LOC}/{DEPT}/{TYPE}/{YYYY}/{MM}/", t));
        assertEquals("GLX-26-0007", NumberSeries.format("GLX-26-", 7, 4, ""));
        assertEquals("E12345-B", NumberSeries.format("E", 12345, 3, "-B"));
        assertEquals("request.invalid", assertThrows(ApiException.class, () -> NumberSeries.expand("{YEAR}", t)).code());
        assertEquals("XXX", NumberSeries.tokens(LocalDate.of(2026, 1, 1), null, "", "permanent").get("LOC"));
    }
}

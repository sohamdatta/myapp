package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AppliesTo;
import com.example.loginapp.core.CustomValues;
import com.example.loginapp.core.FieldCatalog;
import com.example.loginapp.core.FieldCatalog.CustomField;
import com.example.loginapp.core.FieldCatalog.FieldSetting;
import com.example.loginapp.core.FieldCatalog.SectionSetting;

class FieldCatalogTest {

    private static List<SectionSetting> allSections() {
        List<SectionSetting> out = new ArrayList<>();
        for (int i = 0; i < FieldCatalog.SECTIONS.size(); i++) {
            out.add(new SectionSetting(FieldCatalog.SECTIONS.get(i), true, i + 1, null, null, true));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(List<Map<String, Object>> form, String key) {
        for (Map<String, Object> s : form) {
            for (Map<String, Object> f : (List<Map<String, Object>>) s.get("fields")) {
                if (f.get("key").equals(key)) {
                    return f;
                }
            }
        }
        return null;
    }

    @Test
    void theDefaultFormHasEverySectionAndTheFieldsOfTheDesign() {
        List<Map<String, Object>> form = FieldCatalog.form(allSections(), Map.of(), Map.of(), List.of(), AppliesTo.Subject.UNKNOWN);
        assertEquals(FieldCatalog.SECTIONS, form.stream().map(s -> s.get("section")).toList());
        assertEquals("required", field(form, "person.legal_name").get("visibility"));
        assertEquals("optional", field(form, "statutory.aadhaar").get("visibility"));
        assertEquals("hr", field(form, "employment.designation").get("editableBy"));
        assertEquals("joiner", field(form, "bank.ifsc").get("editableBy"));
    }

    @Test
    void aFlowOverrideBeatsTheOrganizationWhichBeatsTheCatalogue() {
        Map<String, FieldSetting> org = Map.of("person.blood_group", new FieldSetting("person.blood_group", "required", "Blood type", null, null));
        Map<String, FieldSetting> flow = Map.of("person.blood_group", new FieldSetting("person.blood_group", "hidden", null, null, null));
        List<Map<String, Object>> orgForm = FieldCatalog.form(allSections(), org, Map.of(), List.of(), AppliesTo.Subject.UNKNOWN);
        assertEquals("required", field(orgForm, "person.blood_group").get("visibility"));
        assertEquals("Blood type", field(orgForm, "person.blood_group").get("label"));
        assertNull(field(FieldCatalog.form(allSections(), org, flow, List.of(), AppliesTo.Subject.UNKNOWN), "person.blood_group"));
    }

    @Test
    void fixedFieldsCannotBeHiddenOrMadeOptional() {
        for (String key : List.of("person.legal_name", "person.date_of_birth", "employment.joining_date", "statutory.pan")) {
            for (String v : List.of("hidden", "optional")) {
                ApiException e = assertThrows(ApiException.class, () -> FieldCatalog.check(new FieldSetting(key, v, null, null, null)));
                assertEquals("field.fixed", e.code(), key);
            }
            FieldCatalog.check(new FieldSetting(key, "required", "Another label", null, null));   // relabelling is fine
        }
        // Even a setting that slipped into the database cannot hide them.
        Map<String, FieldSetting> sneaky = Map.of("statutory.pan", new FieldSetting("statutory.pan", "hidden", null, null, null));
        assertEquals("required", field(FieldCatalog.form(allSections(), sneaky, Map.of(), List.of(), AppliesTo.Subject.UNKNOWN),
                "statutory.pan").get("visibility"));
    }

    @Test
    void employmentDetailsStayWithHrAndUnknownFieldsAreRefused() {
        assertThrows(ApiException.class, () -> FieldCatalog.check(new FieldSetting("employment.designation", null, null, null, "joiner")));
        assertThrows(ApiException.class, () -> FieldCatalog.check(new FieldSetting("person.shoe_size", "optional", null, null, null)));
        assertThrows(ApiException.class, () -> FieldCatalog.check(new FieldSetting("person.gender", "sometimes", null, null, null)));
        assertEquals("hr", FieldCatalog.check(new FieldSetting("bank.ifsc", null, null, null, "hr")).editableBy());
    }

    @Test
    void sectionsCanBeSwitchedOffExceptTheOnesTheRecordNeeds() {
        List<SectionSetting> sections = new ArrayList<>();
        for (SectionSetting s : allSections()) {
            sections.add(new SectionSetting(s.section(), false, s.position(), s.section().equals("bank") ? "Salary account" : null, null, false));
        }
        List<Map<String, Object>> form = FieldCatalog.form(sections, Map.of(), Map.of(), List.of(), AppliesTo.Subject.UNKNOWN);
        assertEquals(List.of("employment", "personal", "statutory"), form.stream().map(s -> s.get("section")).toList());
    }

    @Test
    void customFieldsAppearOnlyForJoinersTheirRulesMatch() {
        CustomField college = custom("college", "personal", "text", null, AppliesTo.from(Map.of("employment_type", List.of("intern"))));
        CustomField tshirt = custom("tshirt", "personal", "select", List.of("S", "M", "L"), AppliesTo.EVERYONE);
        AppliesTo.Subject intern = new AppliesTo.Subject(null, null, "intern", null);
        AppliesTo.Subject permanent = new AppliesTo.Subject(null, null, "permanent", null);
        assertEquals(true, field(FieldCatalog.form(allSections(), Map.of(), Map.of(), List.of(college, tshirt), intern), "custom.college").get("custom"));
        assertNull(field(FieldCatalog.form(allSections(), Map.of(), Map.of(), List.of(college, tshirt), permanent), "custom.college"));
        assertEquals(List.of("S", "M", "L"),
                field(FieldCatalog.form(allSections(), Map.of(), Map.of(), List.of(college, tshirt), permanent), "custom.tshirt").get("options"));
    }

    // ── custom values ───────────────────────────────────────────────────

    @Test
    void definitionsAreChecked() {
        CustomValues.checkDefinition("select", List.of("A", "B"), null, null, null);
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("select", List.of(), null, null, null));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("select", List.of("A", "a"), null, null, null));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("text", List.of("A"), null, null, null));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("text", null, "([a-z", null, null));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("number", null, null, BigDecimal.TEN, BigDecimal.ONE));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("date", null, null, BigDecimal.ONE, null));
        assertThrows(ApiException.class, () -> CustomValues.checkDefinition("colour", null, null, null, null));
    }

    @Test
    void valuesAreCheckedAgainstTheirField() {
        CustomField roll = custom("roll", "personal", "text", null, AppliesTo.EVERYONE, "^[A-Z]{2}[0-9]{4}$", null, null);
        assertEquals("CS1234", CustomValues.normalize(roll, " CS1234 "));
        assertThrows(ApiException.class, () -> CustomValues.normalize(roll, "cs-12"));
        assertNull(CustomValues.normalize(roll, "  "));

        CustomField years = custom("years", "employment", "number", null, AppliesTo.EVERYONE, null, BigDecimal.ZERO, new BigDecimal("50"));
        assertEquals(new BigDecimal("3.5"), CustomValues.normalize(years, "3.5"));
        assertThrows(ApiException.class, () -> CustomValues.normalize(years, 51));
        assertThrows(ApiException.class, () -> CustomValues.normalize(years, "three"));

        CustomField langs = custom("langs", "personal", "multi_select", List.of("Bengali", "Hindi", "English"), AppliesTo.EVERYONE);
        assertEquals(List.of("Bengali", "English"), CustomValues.normalize(langs, List.of("English", "Bengali")));
        assertThrows(ApiException.class, () -> CustomValues.normalize(langs, List.of("French")));

        CustomField size = custom("size", "personal", "select", List.of("S", "M"), AppliesTo.EVERYONE);
        assertEquals("M", CustomValues.normalize(size, "M"));
        assertThrows(ApiException.class, () -> CustomValues.normalize(size, "XL"));

        assertEquals(Boolean.TRUE, CustomValues.normalize(custom("veg", "personal", "yes_no", null, AppliesTo.EVERYONE), "yes"));
        assertEquals("2026-01-31", CustomValues.normalize(custom("d", "personal", "date", null, AppliesTo.EVERYONE), "2026-01-31"));
        assertThrows(ApiException.class, () -> CustomValues.normalize(custom("d", "personal", "date", null, AppliesTo.EVERYONE), "31/01/2026"));
        assertEquals("a@b.in", CustomValues.normalize(custom("e", "contact", "email", null, AppliesTo.EVERYONE), "A@B.in"));
        assertEquals("+919876543210", CustomValues.normalize(custom("p", "contact", "phone", null, AppliesTo.EVERYONE), "+91 98765 43210"));
        assertFalse(CustomValues.TYPES.contains("file"));
    }

    private static CustomField custom(String key, String section, String type, List<String> options, AppliesTo rules) {
        return custom(key, section, type, options, rules, null, null, null);
    }

    private static CustomField custom(String key, String section, String type, List<String> options, AppliesTo rules, String pattern,
                                      BigDecimal min, BigDecimal max) {
        return new CustomField(UUID.randomUUID(), key, key.toUpperCase(), null, section, type, options, pattern, min, max, false, false,
                rules, "optional", "joiner", 0, true);
    }
}

package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AppliesTo;
import com.example.loginapp.core.AppliesTo.Subject;
import com.example.loginapp.core.Json;

class AppliesToTest {

    private static final UUID SALES = UUID.randomUUID();
    private static final UUID SUPPORT = UUID.randomUUID();
    private static final UUID KOLKATA = UUID.randomUUID();
    private static final Subject INTERN_IN_SALES = new Subject(SALES, KOLKATA, "intern", "Sales Associate");

    @Test
    void emptyRulesApplyToEveryoneEvenWhenNothingIsKnown() {
        assertTrue(AppliesTo.parse("{}").appliesToEveryone());
        assertTrue(AppliesTo.parse(null).matches(Subject.UNKNOWN));
        assertTrue(AppliesTo.from(Map.of()).matches(INTERN_IN_SALES));
        assertEquals("Everyone", AppliesTo.EVERYONE.describe(null));
    }

    @Test
    void eachAttributeMatches() {
        assertTrue(rules(Map.of("department", List.of(SALES.toString()))).matches(INTERN_IN_SALES));
        assertFalse(rules(Map.of("department", List.of(SUPPORT.toString()))).matches(INTERN_IN_SALES));
        assertTrue(rules(Map.of("work_location", List.of(KOLKATA.toString()))).matches(INTERN_IN_SALES));
        assertTrue(rules(Map.of("employment_type", List.of("intern", "probation"))).matches(INTERN_IN_SALES));
        assertFalse(rules(Map.of("employment_type", List.of("permanent"))).matches(INTERN_IN_SALES));
        assertTrue(rules(Map.of("designation", List.of("sales associate"))).matches(INTERN_IN_SALES));   // case does not matter
        assertFalse(rules(Map.of("designation", List.of("Sales"))).matches(INTERN_IN_SALES));            // whole words, not parts
    }

    @Test
    void everyListedAttributeMustMatch() {
        AppliesTo both = rules(Map.of("employment_type", List.of("intern"), "department", List.of(SUPPORT.toString(), SALES.toString())));
        assertTrue(both.matches(INTERN_IN_SALES));
        assertFalse(both.matches(new Subject(SALES, KOLKATA, "permanent", "Sales Associate")));
        assertFalse(both.matches(new Subject(UUID.randomUUID(), KOLKATA, "intern", "Sales Associate")));
    }

    @Test
    void anUnknownAttributeOfTheSubjectDoesNotMatchARuleForIt() {
        assertFalse(rules(Map.of("department", List.of(SALES.toString()))).matches(new Subject(null, null, "intern", null)));
    }

    @Test
    void badRulesAreRefused() {
        assertThrows(ApiException.class, () -> AppliesTo.parse("{\"grade\": [\"A\"]}"));            // unknown attribute
        assertThrows(ApiException.class, () -> AppliesTo.parse("{\"department\": []}"));             // no values
        assertThrows(ApiException.class, () -> AppliesTo.parse("{\"department\": [\"sales\"]}"));    // not an id
        assertThrows(ApiException.class, () -> AppliesTo.parse("{\"employment_type\": [\"gig\"]}"));
        assertThrows(ApiException.class, () -> AppliesTo.parse("{\"designation\": [3]}"));
        assertThrows(ApiException.class, () -> AppliesTo.parse("[\"intern\"]"));
        assertThrows(ApiException.class, () -> AppliesTo.parse("{not json"));
        ApiException e = assertThrows(ApiException.class, () -> AppliesTo.parse("{\"grade\": [\"A\"]}"));
        assertEquals("rules.invalid", e.code());
    }

    @Test
    void firstMatchByPositionWinsAndTheCatchAllCatchesTheRest() {
        record Flow(String name, AppliesTo rules) {}
        List<Flow> flows = List.of(
                new Flow("Interns", rules(Map.of("employment_type", List.of("intern")))),
                new Flow("Sales", rules(Map.of("department", List.of(SALES.toString())))),
                new Flow("Everyone", AppliesTo.EVERYONE));
        assertEquals("Interns", AppliesTo.firstMatch(flows, Flow::rules, INTERN_IN_SALES).name());
        assertEquals("Sales", AppliesTo.firstMatch(flows, Flow::rules, new Subject(SALES, null, "permanent", null)).name());
        assertEquals("Everyone", AppliesTo.firstMatch(flows, Flow::rules, Subject.UNKNOWN).name());
        assertNull(AppliesTo.firstMatch(flows.subList(0, 2), Flow::rules, Subject.UNKNOWN));
    }

    @Test
    void rulesReadInWordsAndSurviveStorage() {
        AppliesTo r = rules(Map.of("employment_type", List.of("fixed_term"), "department", List.of(SALES.toString())));
        assertEquals("Employment type fixed term; Department Sales", r.describe(id -> id.equals(SALES) ? "Sales" : null));
        assertEquals(r.rules(), AppliesTo.parse(r.toJson()).rules());
        assertEquals("{}", AppliesTo.EVERYONE.toJson());
    }

    @Test
    void jsonReadsWhatItWrites() {
        Object value = Json.parse("{\"a\": [1, 2.5, \"x\\\"y\", true, null], \"b\": {\"c\": \"\\u00e9\"}}");
        assertEquals(value, Json.parse(Json.write(value)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} extra"));
    }

    private static AppliesTo rules(Map<String, ?> map) {
        return AppliesTo.from(map);
    }
}

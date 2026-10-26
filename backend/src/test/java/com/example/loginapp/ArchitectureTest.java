package com.example.loginapp;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;

/**
 * The structural rules of docs/engineering-standards.md, checked on the compiled classes. A rule
 * that old code already breaks is <em>frozen</em> (only the untyped-Map rule today): the violations
 * that exist today are listed in {@code src/test/resources/archunit-store}, and only a new violation
 * fails the build. Remove a line from the store when you fix it; never add one by hand to excuse new code.
 */
class ArchitectureTest {

    private static JavaClasses app;

    @BeforeAll
    static void importClasses() {
        app = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.loginapp");
    }

    // ── layering ────────────────────────────────────────────────────────

    @Test
    void theCoreKnowsNothingOfTheWebLayer() {
        noClasses().that().resideInAPackage("..core..")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "org.springframework.web..", "jakarta.servlet..")
                .because("business rules must run, and be tested, without a web server (standards, section 3)")
                .check(app);
    }

    @Test
    void theWebLayerNeverTouchesTheDatabaseDirectly() {
        noClasses().that().resideInAPackage("..web..").and().doNotHaveSimpleName("AppConfig")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "com.example.loginapp.core.Sql")
                .because("controllers call services; SQL lives in the core, inside a tenant transaction (standards, section 5)")
                .check(app);
    }

    // ── purity of the money engines (non-negotiable 7) ──────────────────

    @Test
    void theFormulaAndPayrollEnginesAreFreeOfDatabaseNetworkAndClock() {
        ArchRule rule = noClasses().that().haveSimpleName("Formula").or().haveSimpleName("PayrollEngine").or().haveSimpleNameStartingWith("Formula$").or()
                .haveSimpleNameStartingWith("PayrollEngine$")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "java.net..", "org.springframework..", "jakarta..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("java.time.Clock")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.loginapp.core.Db")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.loginapp.core.Sql")
                .because("the engines take inputs and return a result and a trace; the same inputs must give the same payslip");
        rule.check(app);
    }

    @Test
    void theFormulaAndPayrollEnginesNeverReadTheClock() {
        noClasses().that().haveSimpleName("Formula").or().haveSimpleName("PayrollEngine")
                .should(callMethodWhere(target(nameMatching("now|currentTimeMillis|nanoTime"))))
                .because("the month being paid is passed in, so a run can be recalculated and explained later")
                .check(app);
    }

    @Test
    void moneyInThePayrollAndTaxClassesIsNeverADoubleOrFloat() {
        fields().that().areDeclaredInClassesThat().haveSimpleNameStartingWith("Payroll")
                .or().areDeclaredInClassesThat().haveSimpleNameStartingWith("TaxDeclarations")
                .or().areDeclaredInClassesThat().haveSimpleNameStartingWith("ExitSettlement")
                .should().notHaveRawType(double.class).andShould().notHaveRawType(float.class)
                .because("money is BigDecimal and is rounded once, at the step the component declares (standards, section 1)")
                .check(app);
        noMethods().that().areDeclaredInClassesThat().haveSimpleNameStartingWith("Payroll")
                .or().areDeclaredInClassesThat().haveSimpleNameStartingWith("TaxDeclarations")
                .or().areDeclaredInClassesThat().haveSimpleNameStartingWith("ExitSettlement")
                .should().haveRawReturnType(double.class).orShould().haveRawReturnType(float.class)
                .because("money is BigDecimal")
                .check(app);
    }

    // ── habits to retire, frozen at today's violations ──────────────────

    @Test
    void noNewPublicMethodReturnsAnUntypedMap() {
        freeze(methods().that().arePublic().and().haveRawReturnType(Map.class)
                .and().areDeclaredInClassesThat().resideInAnyPackage("..core..", "..web..")
                .should().bePrivate()
                .because("new requests, responses and rows are records, not Map<String, Object> (standards, section 2)"))
                .check(app);
    }

    @Test
    void classesDoNotWriteToTheStandardStreams() {
        noClasses().that().resideInAnyPackage("..core..", "..web..")
                .should(com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS)
                .because("use the logger; standard streams are not collected, searched or kept for 180 days")
                .check(app);
    }

    @Test
    void noGenericExceptionsAreThrown() {
        noClasses().that().resideInAnyPackage("..core..", "..web..")
                .should(com.tngtech.archunit.library.GeneralCodingRules.THROW_GENERIC_EXCEPTIONS)
                .because("say what is wrong and what to do: ApiException for the person, a specific type for the program")
                .check(app);
    }

    @Test
    void thereAreClassesToCheck() {
        // A rule over an empty set passes silently; this fails if the package names above stop matching.
        classes().that().resideInAPackage("..core..").should().bePublic().orShould().notBePublic().check(app);
        org.junit.jupiter.api.Assertions.assertTrue(app.size() > 100, "only " + app.size() + " classes imported");
    }
}

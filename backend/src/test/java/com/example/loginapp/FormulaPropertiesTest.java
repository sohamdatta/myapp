package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.loginapp.core.Formula;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.StringArbitrary;

/**
 * Property tests for the payroll formula language (docs/engineering-standards.md, section 6).
 * Hand-written examples (PayrollTest) show that the cases someone thought of work; these generate
 * thousands of inputs to show that the ones nobody thought of cannot corrupt a payslip:
 *
 * <ul>
 *   <li>whatever text is typed, the only failure is Formula.Error, with a position inside the text;
 *   <li>a well-formed formula evaluates to what an independent reference evaluator says it should;
 *   <li>the usual precedence and left-to-right order hold;
 *   <li>rounding lands on a multiple of the step, in the direction the function names;
 *   <li>the length and nesting limits hold, and division by zero is flagged, never thrown.
 * </ul>
 *
 * The reference evaluator below is deliberately separate from Formula: it works on a tree built by
 * the generator, not on the text, so a parsing mistake cannot cancel out an evaluation mistake.
 */
class FormulaPropertiesTest {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private static final BigDecimal BASIC = new BigDecimal("30000");
    private static final BigDecimal HRA = new BigDecimal("12000.50");
    private static final BigDecimal DAYS_PAID = new BigDecimal("26");

    private static final Set<String> COMPONENTS = Set.of("BASIC", "HRA", "BONUS");
    private static final Map<String, Formula.Type> FACTS = Map.of(
            "days.paid", Formula.Type.NUMBER,
            "location.metro", Formula.Type.BOOL,
            "designation", Formula.Type.TEXT);

    /** Answers every name the checker accepts, so evaluating a checked formula cannot fail on lookup. */
    private static final Formula.Env ENV = new Formula.Env() {
        @Override
        public Object get(String name) {
            return switch (name) {
                case "BASIC" -> BASIC;
                case "HRA" -> HRA;
                case "BONUS" -> new BigDecimal("2500");
                case "days.paid" -> DAYS_PAID;
                case "location.metro" -> Boolean.TRUE;
                case "designation" -> "Analyst";
                default -> throw new IllegalStateException("Checked formula read unknown name " + name);
            };
        }

        @Override
        public BigDecimal table(String name, String key) {
            return new BigDecimal("1600");
        }

        @Override
        public BigDecimal ytd(String code) {
            return new BigDecimal("500");
        }
    };

    // ── whatever is typed ───────────────────────────────────────────────

    @Property(tries = 5000)
    void anyTextIsParsedOrRefusedWithAPosition(@ForAll String source) {
        parseCheckAndEvaluate(source);
    }

    @Property(tries = 5000)
    void anyArrangementOfFormulaPartsIsParsedOrRefusedWithAPosition(@ForAll("tokenSoup") String source) {
        parseCheckAndEvaluate(source);
    }

    /** Runs a text through every stage; anything but Formula.Error escaping fails the property. */
    private static void parseCheckAndEvaluate(String source) {
        try {
            Formula f = Formula.parse(source);
            f.components();
            f.facts();
            f.check(COMPONENTS, FACTS);
            Formula.Result r = f.evaluate(ENV);
            assertTrue(r.value() != null, "a result always has a value");
        } catch (Formula.Error e) {
            assertTrue(e.position() >= -1 && e.position() <= Math.max(source.length(), 2000),
                    "position " + e.position() + " is outside the text of length " + source.length());
            assertFalse(e.getMessage() == null || e.getMessage().isBlank(), "an error always says what is wrong");
        }
    }

    @Provide
    Arbitrary<String> tokenSoup() {
        Arbitrary<String> token = Arbitraries.of(
                "BASIC", "HRA", "BONUS", "NOPE", "days.paid", "location.metro", "designation", "metro.",
                "0", "1", "26", "0.5", "12000.50", ".", "1.2.3", "\"Analyst\"", "\"open",
                "+", "-", "*", "/", "(", ")", ",", "=", "<>", "<", "<=", ">", ">=",
                "and", "or", "not", "yes", "no", "true", "false",
                "if(", "case(", "min(", "max(", "abs(", "round(", "round_up(", "round_down(", "table(", "ytd(",
                "table(\"meal_card\",", "ytd(BASIC)", "ytd(HRA", "frobnicate(");
        return token.list().ofMinSize(1).ofMaxSize(24).map(parts -> String.join(" ", parts));
    }

    // ── the same answer as an independent evaluator ─────────────────────

    @Property(tries = 3000)
    void aWellFormedFormulaEvaluatesToTheReferenceAnswer(@ForAll("amounts") Amount tree) {
        String source = tree.render();
        Formula f = Formula.parse(source);
        f.check(COMPONENTS, FACTS);

        Run expected = new Run();
        BigDecimal want = tree.eval(expected);
        Formula.Result got = f.evaluate(ENV);

        assertEquals(0, want.compareTo(got.value()), source + " gave " + got.value() + ", reference " + want);
        assertEquals(expected.dividedByZero, got.dividedByZero(), "division by zero flag for " + source);
        assertEquals(tree.names(true), f.components(), "components read by " + source);
        assertEquals(tree.names(false), f.facts(), "facts read by " + source);
    }

    @Property(tries = 2000)
    void multiplicationAndDivisionBindTighterThanAdditionAndAreLeftToRight(
            @ForAll("smallAmount") BigDecimal a, @ForAll("smallAmount") BigDecimal b,
            @ForAll("smallAmount") BigDecimal c, @ForAll("smallAmount") BigDecimal d,
            @ForAll("smallAmount") BigDecimal e) {
        String source = a.toPlainString() + " + " + b.toPlainString() + " * " + c.toPlainString()
                + " - " + d.toPlainString() + " / " + e.toPlainString() + " - " + a.toPlainString();
        BigDecimal quotient = e.signum() == 0 ? BigDecimal.ZERO : d.divide(e, MC);
        BigDecimal want = a.add(b.multiply(c, MC)).subtract(quotient).subtract(a);
        assertEquals(0, want.compareTo(Formula.parse(source).evaluate(ENV).value()), source);
    }

    @Property(tries = 1000)
    void subtractionAndDivisionAssociateToTheLeft(
            @ForAll("smallAmount") BigDecimal a, @ForAll("smallAmount") BigDecimal b, @ForAll("smallAmount") BigDecimal c) {
        assertEquals(0, a.subtract(b).subtract(c).compareTo(
                Formula.parse(a.toPlainString() + " - " + b.toPlainString() + " - " + c.toPlainString()).evaluate(ENV).value()));
        if (b.signum() != 0 && c.signum() != 0) {
            assertEquals(0, a.divide(b, MC).divide(c, MC).compareTo(
                    Formula.parse(a.toPlainString() + " / " + b.toPlainString() + " / " + c.toPlainString()).evaluate(ENV).value()));
        }
    }

    @Property(tries = 1000)
    void aMinusSignAppliesToWhatFollowsIt(@ForAll("smallAmount") BigDecimal a, @ForAll("smallAmount") BigDecimal b) {
        assertEquals(0, a.negate().add(b).compareTo(Formula.parse("-" + a.toPlainString() + " + " + b.toPlainString()).evaluate(ENV).value()));
        assertEquals(0, a.add(b.negate()).compareTo(Formula.parse(a.toPlainString() + " + -" + b.toPlainString()).evaluate(ENV).value()));
        assertEquals(0, a.compareTo(Formula.parse("--" + a.toPlainString()).evaluate(ENV).value()));
    }

    // ── rounding ────────────────────────────────────────────────────────

    @Property(tries = 3000)
    void roundingLandsOnAMultipleOfTheStepInTheDirectionTheFunctionNames(
            @ForAll("smallAmount") BigDecimal value, @ForAll("steps") BigDecimal step) {
        BigDecimal nearest = evaluate("round(" + value.toPlainString() + ", " + step.toPlainString() + ")");
        BigDecimal up = evaluate("round_up(" + value.toPlainString() + ", " + step.toPlainString() + ")");
        BigDecimal down = evaluate("round_down(" + value.toPlainString() + ", " + step.toPlainString() + ")");

        for (BigDecimal r : List.of(nearest, up, down)) {
            assertEquals(0, r.remainder(step).signum(), r + " is not a multiple of " + step);
        }
        assertTrue(up.compareTo(value) >= 0 && up.subtract(value).compareTo(step) < 0, "round_up(" + value + ", " + step + ") = " + up);
        assertTrue(down.compareTo(value) <= 0 && value.subtract(down).compareTo(step) < 0, "round_down(" + value + ", " + step + ") = " + down);
        assertTrue(nearest.subtract(value).abs().multiply(BigDecimal.TWO).compareTo(step) <= 0,
                "round(" + value + ", " + step + ") = " + nearest + " is not the nearest multiple");
        assertTrue(down.compareTo(nearest) <= 0 && nearest.compareTo(up) <= 0, "down <= nearest <= up");
    }

    @Property(tries = 1000)
    void roundingToWholeRupeesIsTheDefault(@ForAll("smallAmount") BigDecimal value) {
        assertEquals(0, evaluate("round(" + value.toPlainString() + ")").compareTo(
                evaluate("round(" + value.toPlainString() + ", 1)")));
    }

    @Property(tries = 1000)
    void aStepOfZeroOrLessLeavesTheValueAlone(@ForAll("smallAmount") BigDecimal value, @ForAll("smallAmount") BigDecimal stepSize) {
        assertEquals(0, value.compareTo(evaluate("round(" + value.toPlainString() + ", -" + stepSize.toPlainString() + ")")));
        assertEquals(0, value.compareTo(evaluate("round_up(" + value.toPlainString() + ", 0)")));
        assertEquals(0, value.compareTo(evaluate("round_down(" + value.toPlainString() + ", 0)")));
    }

    @Property(tries = 1000)
    void minMaxAndAbsAgreeWithBigDecimal(@ForAll("smallAmount") BigDecimal a, @ForAll("smallAmount") BigDecimal b, @ForAll("smallAmount") BigDecimal c) {
        String args = a.toPlainString() + ", " + b.toPlainString() + ", " + c.toPlainString();
        assertEquals(0, a.min(b).min(c).compareTo(evaluate("min(" + args + ")")));
        assertEquals(0, a.max(b).max(c).compareTo(evaluate("max(" + args + ")")));
        assertEquals(0, a.subtract(b).abs().compareTo(evaluate("abs(" + a.toPlainString() + " - " + b.toPlainString() + ")")));
    }

    // ── division by zero, length and nesting ────────────────────────────

    @Property(tries = 500)
    void dividingByZeroGivesZeroAndSaysSo(@ForAll("smallAmount") BigDecimal a) {
        Formula.Result r = Formula.parse(a.toPlainString() + " / 0").evaluate(ENV);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.value()));
        assertTrue(r.dividedByZero());
        assertFalse(Formula.parse(a.toPlainString() + " / 1").evaluate(ENV).dividedByZero());
    }

    @Example
    void aFormulaOverTwoThousandCharactersIsRefusedAtTwoThousand() {
        String within = "1" + " + 1".repeat(499);                     // 1,997 characters
        assertTrue(within.length() <= 2000);
        assertEquals(0, new BigDecimal("500").compareTo(Formula.parse(within).evaluate(ENV).value()));

        Formula.Error e = assertThrows(Formula.Error.class, () -> Formula.parse("1" + " + 1".repeat(600)));
        assertEquals(2000, e.position());
    }

    @Example
    void deepNestingIsRefusedBeforeItCanExhaustTheStack() {
        assertEquals(0, BigDecimal.ONE.compareTo(Formula.parse("(".repeat(5) + "1" + ")".repeat(5)).evaluate(ENV).value()));
        Formula.Error e = assertThrows(Formula.Error.class,
                () -> Formula.parse("(".repeat(60) + "1" + ")".repeat(60)));
        assertTrue(e.getMessage().contains("nested too deeply"), e.getMessage());

        // Prefix operators nest without brackets. Thousands of them used to be left to the stack (the 2,000
        // character limit was the only guard) and overflowed on a slower JVM; now they are refused in the parser.
        for (String source : List.of("not ".repeat(450) + "yes", "-".repeat(1990) + "1", "- ".repeat(300) + "1", "not ".repeat(30) + "- ".repeat(30) + "1")) {
            Formula.Error refused = assertThrows(Formula.Error.class, () -> Formula.parse(source), source.substring(0, 12));
            assertTrue(refused.getMessage().contains("in a row"), refused.getMessage());
        }
        // a sensible run of them is still fine
        assertEquals(0, BigDecimal.ONE.compareTo(Formula.parse("-".repeat(40) + "1").evaluate(ENV).value()));
        assertDoesNotThrow(() -> Formula.parse("not ".repeat(40) + "yes"));
    }

    @Example
    void anEmptyOrMissingFormulaIsRefusedAtTheStart() {
        for (String blank : new String[] {null, "", "   ", "\n\t"}) {
            Formula.Error e = assertThrows(Formula.Error.class, () -> Formula.parse(blank));
            assertEquals(0, e.position());
        }
    }

    // ── generators ──────────────────────────────────────────────────────

    @Provide
    Arbitrary<BigDecimal> smallAmount() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("100000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> steps() {
        return Arbitraries.of(new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("10"),
                new BigDecimal("0.5"), new BigDecimal("0.01"), new BigDecimal("100"));
    }

    @Provide
    Arbitrary<Amount> amounts() {
        return Arbitraries.recursive(
                () -> Arbitraries.oneOf(literal(), reference()),
                this::combine,
                0, 3);
    }

    private Arbitrary<Amount> literal() {
        return smallAmount().map(Lit::new);
    }

    private Arbitrary<Amount> reference() {
        return Arbitraries.of(
                new Ref("BASIC", true, BASIC), new Ref("HRA", true, HRA),
                new Ref("days.paid", false, DAYS_PAID), new Ref("BONUS", true, new BigDecimal("2500")));
    }

    private Arbitrary<Amount> combine(Arbitrary<Amount> inner) {
        Arbitrary<Amount> arithmetic = net.jqwik.api.Combinators.combine(
                Arbitraries.of("+", "-", "*", "/"), inner, inner).as(Arith::new);
        Arbitrary<Amount> negated = inner.map(Neg::new);
        Arbitrary<Amount> smallest = net.jqwik.api.Combinators.combine(Arbitraries.of("min", "max"), inner, inner, inner)
                .as((fn, x, y, z) -> new Pick(fn, List.of(x, y, z)));
        Arbitrary<Amount> absolute = inner.map(Abs::new);
        Arbitrary<Amount> rounded = net.jqwik.api.Combinators.combine(
                Arbitraries.of("round", "round_up", "round_down"), inner, steps()).as(Rounded::new);
        Arbitrary<Amount> conditional = net.jqwik.api.Combinators.combine(condition(inner), inner, inner).as(If::new);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, arithmetic), net.jqwik.api.Tuple.of(1, negated),
                net.jqwik.api.Tuple.of(2, smallest), net.jqwik.api.Tuple.of(1, absolute),
                net.jqwik.api.Tuple.of(2, rounded), net.jqwik.api.Tuple.of(2, conditional));
    }

    private Arbitrary<Cond> condition(Arbitrary<Amount> inner) {
        Arbitrary<Cond> compare = net.jqwik.api.Combinators.combine(
                inner, Arbitraries.of("<", "<=", ">", ">=", "=", "<>"), inner).as(Compare::new);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, compare),
                net.jqwik.api.Tuple.of(1, net.jqwik.api.Combinators.combine(compare, Arbitraries.of("and", "or"), compare).as(Logic::new)),
                net.jqwik.api.Tuple.of(1, compare.map(Negation::new)));
    }

    private static BigDecimal evaluate(String source) {
        return Formula.parse(source).evaluate(ENV).value();
    }

    // ── the reference evaluator: a tree that prints itself and computes itself ──

    /** Mutable state for one reference run. */
    private static final class Run {
        boolean dividedByZero;
    }

    private sealed interface Amount permits Lit, Ref, Arith, Neg, Pick, Abs, Rounded, If {
        String render();

        BigDecimal eval(Run run);

        void collect(Set<String> names, boolean components);

        default Set<String> names(boolean components) {
            Set<String> out = new LinkedHashSet<>();
            collect(out, components);
            return out;
        }
    }

    private sealed interface Cond permits Compare, Logic, Negation {
        String render();

        boolean eval(Run run);

        void collect(Set<String> names, boolean components);
    }

    private record Lit(BigDecimal value) implements Amount {
        public String render() { return value.toPlainString(); }
        public BigDecimal eval(Run run) { return value; }
        public void collect(Set<String> names, boolean components) { }
    }

    private record Ref(String name, boolean component, BigDecimal value) implements Amount {
        public String render() { return name; }
        public BigDecimal eval(Run run) { return value; }
        public void collect(Set<String> names, boolean components) {
            if (component == components) {
                names.add(name);
            }
        }
    }

    private record Arith(String op, Amount left, Amount right) implements Amount {
        public String render() { return "(" + left.render() + " " + op + " " + right.render() + ")"; }
        public BigDecimal eval(Run run) {
            BigDecimal x = left.eval(run);
            BigDecimal y = right.eval(run);
            return switch (op) {
                case "+" -> x.add(y);
                case "-" -> x.subtract(y);
                case "*" -> x.multiply(y, MC);
                default -> {
                    if (y.signum() == 0) {
                        run.dividedByZero = true;
                        yield BigDecimal.ZERO;
                    }
                    yield x.divide(y, MC);
                }
            };
        }
        public void collect(Set<String> names, boolean components) {
            left.collect(names, components);
            right.collect(names, components);
        }
    }

    private record Neg(Amount inner) implements Amount {
        public String render() { return "-(" + inner.render() + ")"; }
        public BigDecimal eval(Run run) { return inner.eval(run).negate(); }
        public void collect(Set<String> names, boolean components) { inner.collect(names, components); }
    }

    private record Pick(String fn, List<Amount> args) implements Amount {
        public String render() {
            return fn + "(" + String.join(", ", args.stream().map(Amount::render).toList()) + ")";
        }
        public BigDecimal eval(Run run) {
            BigDecimal best = null;
            for (Amount a : args) {
                BigDecimal v = a.eval(run);
                if (best == null || (fn.equals("min") ? v.compareTo(best) < 0 : v.compareTo(best) > 0)) {
                    best = v;
                }
            }
            return best;
        }
        public void collect(Set<String> names, boolean components) {
            args.forEach(a -> a.collect(names, components));
        }
    }

    private record Abs(Amount inner) implements Amount {
        public String render() { return "abs(" + inner.render() + ")"; }
        public BigDecimal eval(Run run) { return inner.eval(run).abs(); }
        public void collect(Set<String> names, boolean components) { inner.collect(names, components); }
    }

    private record Rounded(String fn, Amount inner, BigDecimal step) implements Amount {
        public String render() { return fn + "(" + inner.render() + ", " + step.toPlainString() + ")"; }
        public BigDecimal eval(Run run) {
            RoundingMode mode = fn.equals("round") ? RoundingMode.HALF_UP
                    : fn.equals("round_up") ? RoundingMode.CEILING : RoundingMode.FLOOR;
            return inner.eval(run).divide(step, 0, mode).multiply(step);
        }
        public void collect(Set<String> names, boolean components) { inner.collect(names, components); }
    }

    private record If(Cond condition, Amount then, Amount otherwise) implements Amount {
        public String render() { return "if(" + condition.render() + ", " + then.render() + ", " + otherwise.render() + ")"; }
        public BigDecimal eval(Run run) { return condition.eval(run) ? then.eval(run) : otherwise.eval(run); }
        public void collect(Set<String> names, boolean components) {
            condition.collect(names, components);
            then.collect(names, components);
            otherwise.collect(names, components);
        }
    }

    private record Compare(Amount left, String op, Amount right) implements Cond {
        public String render() { return "(" + left.render() + " " + op + " " + right.render() + ")"; }
        public boolean eval(Run run) {
            int c = left.eval(run).compareTo(right.eval(run));
            return switch (op) {
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "=" -> c == 0;
                default -> c != 0;
            };
        }
        public void collect(Set<String> names, boolean components) {
            left.collect(names, components);
            right.collect(names, components);
        }
    }

    /** "and" and "or" stop at the first side that decides the answer, so a division by zero on the other side is never run. */
    private record Logic(Cond left, String op, Cond right) implements Cond {
        public String render() { return "(" + left.render() + " " + op + " " + right.render() + ")"; }
        public boolean eval(Run run) {
            return op.equals("and") ? left.eval(run) && right.eval(run) : left.eval(run) || right.eval(run);
        }
        public void collect(Set<String> names, boolean components) {
            left.collect(names, components);
            right.collect(names, components);
        }
    }

    private record Negation(Cond inner) implements Cond {
        public String render() { return "(not " + inner.render() + ")"; }
        public boolean eval(Run run) { return !inner.eval(run); }
        public void collect(Set<String> names, boolean components) { inner.collect(names, components); }
    }
}

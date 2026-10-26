package com.example.loginapp.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The payroll formula language: a small expression language that payroll teams write and that this
 * class parses, checks and evaluates itself. It has no loops, no clock and no way to reach anything
 * but the values it is given, so a formula cannot hang a run or read data it should not.
 *
 * <pre>
 *   BASIC * if(location.metro, 0.5, 0.4)
 *   CTC_MONTHLY - BASIC - HRA - PF_ER
 *   case(designation = "Analyst", 1600, designation = "Manager", 2400, 0)
 *   table("meal_card", department)
 * </pre>
 *
 * Names in capitals are other components (BASIC, HRA). Lower-case dotted names are facts about the
 * employee or the month (location.metro, days.paid). Numbers are exact decimals.
 */
public final class Formula {

    /** Why a formula cannot be used, with the position of the problem (0-based, -1 when not tied to one). */
    public static final class Error extends RuntimeException {
        private final int position;

        Error(String message, int position) {
            super(message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    /** What a formula may read while it is evaluated. */
    public interface Env {
        /** A component's amount or a named fact; throws Error when it does not exist. */
        Object get(String name);

        /** A value from the Organization's table; throws Error when the table or row is missing. */
        BigDecimal table(String name, String key);

        /** Paid so far this tax year for a component, before this month. */
        BigDecimal ytd(String code);
    }

    public enum Type { NUMBER, BOOL, TEXT, ANY }

    // ── the tree ────────────────────────────────────────────────────────

    sealed interface Node permits Num, Str, Bool, Ref, Call, Bin, Neg, Not {
        int pos();
    }

    record Num(BigDecimal value, int pos) implements Node {}

    record Str(String value, int pos) implements Node {}

    record Bool(boolean value, int pos) implements Node {}

    record Ref(String name, int pos) implements Node {}

    record Call(String fn, List<Node> args, int pos) implements Node {}

    record Bin(String op, Node left, Node right, int pos) implements Node {}

    record Neg(Node inner, int pos) implements Node {}

    record Not(Node inner, int pos) implements Node {}

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final Set<String> FUNCTIONS = Set.of("if", "case", "min", "max", "round", "round_up", "round_down", "table", "ytd", "abs");

    private final String source;
    private final Node root;

    private Formula(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public String source() {
        return source;
    }

    /** Parses a formula; throws Error saying what is wrong and where. */
    public static Formula parse(String source) {
        if (source == null || source.isBlank()) {
            throw new Error("The formula is empty", 0);
        }
        if (source.length() > 2000) {
            throw new Error("A formula can be at most 2,000 characters", 2000);
        }
        Parser p = new Parser(source);
        Node n = p.expression(0);
        p.skip();
        if (p.i < source.length()) {
            throw new Error("Unexpected \"" + source.charAt(p.i) + "\"", p.i);
        }
        return new Formula(source, n);
    }

    /** The component codes the formula reads, in the order they appear. */
    public Set<String> components() {
        Set<String> out = new LinkedHashSet<>();
        collect(root, out, true);
        return out;
    }

    /** The lower-case facts the formula reads. */
    public Set<String> facts() {
        Set<String> out = new LinkedHashSet<>();
        collect(root, out, false);
        return out;
    }

    private static void collect(Node n, Set<String> out, boolean components) {
        switch (n) {
            case Ref r -> {
                if (isComponent(r.name()) == components) {
                    out.add(r.name());
                }
            }
            case Call c -> {
                for (Node a : c.args()) {
                    collect(a, out, components);
                }
                // ytd(BASIC) reads earlier months, not this month's BASIC: not a dependency of the order.
                if (components && c.fn().equals("ytd")) {
                    out.removeIf(x -> c.args().size() == 1 && c.args().get(0) instanceof Ref r && r.name().equals(x));
                }
            }
            case Bin b -> {
                collect(b.left(), out, components);
                collect(b.right(), out, components);
            }
            case Neg g -> collect(g.inner(), out, components);
            case Not t -> collect(t.inner(), out, components);
            default -> { }
        }
    }

    static boolean isComponent(String name) {
        return name.matches("^[A-Z][A-Z0-9_]*$");
    }

    /**
     * Checks names and types: every component and fact must be known, arithmetic only on numbers,
     * conditions only on yes/no. The result must be a number.
     */
    public void check(Set<String> components, Map<String, Type> facts) {
        Type t = type(root, components, facts);
        if (t != Type.NUMBER && t != Type.ANY) {
            throw new Error("The formula must give an amount, not " + (t == Type.BOOL ? "yes or no" : "text"), 0);
        }
    }

    private static Type type(Node n, Set<String> components, Map<String, Type> facts) {
        return switch (n) {
            case Num x -> Type.NUMBER;
            case Str x -> Type.TEXT;
            case Bool x -> Type.BOOL;
            case Ref r -> {
                if (isComponent(r.name())) {
                    if (!components.contains(r.name())) {
                        throw new Error("No component called " + r.name(), r.pos());
                    }
                    yield Type.NUMBER;
                }
                Type t = facts.get(r.name());
                if (t == null) {
                    throw new Error("Unknown name " + r.name(), r.pos());
                }
                yield t;
            }
            case Neg g -> {
                expect(type(g.inner(), components, facts), Type.NUMBER, g.pos(), "Only a number can be negative");
                yield Type.NUMBER;
            }
            case Not t -> {
                expect(type(t.inner(), components, facts), Type.BOOL, t.pos(), "not needs a yes-or-no value");
                yield Type.BOOL;
            }
            case Bin b -> {
                Type l = type(b.left(), components, facts);
                Type r = type(b.right(), components, facts);
                switch (b.op()) {
                    case "+", "-", "*", "/" -> {
                        expect(l, Type.NUMBER, b.pos(), "\"" + b.op() + "\" works on numbers only");
                        expect(r, Type.NUMBER, b.pos(), "\"" + b.op() + "\" works on numbers only");
                        yield Type.NUMBER;
                    }
                    case "and", "or" -> {
                        expect(l, Type.BOOL, b.pos(), b.op() + " needs yes-or-no values on both sides");
                        expect(r, Type.BOOL, b.pos(), b.op() + " needs yes-or-no values on both sides");
                        yield Type.BOOL;
                    }
                    case "<", "<=", ">", ">=" -> {
                        expect(l, Type.NUMBER, b.pos(), "\"" + b.op() + "\" compares numbers only");
                        expect(r, Type.NUMBER, b.pos(), "\"" + b.op() + "\" compares numbers only");
                        yield Type.BOOL;
                    }
                    default -> {   // = and <>
                        if (l != Type.ANY && r != Type.ANY && l != r) {
                            throw new Error("Cannot compare " + words(l) + " with " + words(r), b.pos());
                        }
                        yield Type.BOOL;
                    }
                }
            }
            case Call c -> callType(c, components, facts);
        };
    }

    private static Type callType(Call c, Set<String> components, Map<String, Type> facts) {
        List<Node> a = c.args();
        switch (c.fn()) {
            case "if" -> {
                arity(c, 3, 3);
                expect(type(a.get(0), components, facts), Type.BOOL, a.get(0).pos(), "if needs a yes-or-no condition first");
                Type x = type(a.get(1), components, facts);
                Type y = type(a.get(2), components, facts);
                if (x != y && x != Type.ANY && y != Type.ANY) {
                    throw new Error("Both results of if must be the same kind of value", c.pos());
                }
                return x == Type.ANY ? y : x;
            }
            case "case" -> {
                if (a.size() < 3 || a.size() % 2 == 0) {
                    throw new Error("case needs pairs of condition and value, then a value for everything else", c.pos());
                }
                Type result = Type.ANY;
                for (int i = 0; i < a.size() - 1; i += 2) {
                    expect(type(a.get(i), components, facts), Type.BOOL, a.get(i).pos(), "Each case starts with a yes-or-no condition");
                    result = merge(result, type(a.get(i + 1), components, facts), c.pos());
                }
                return merge(result, type(a.get(a.size() - 1), components, facts), c.pos());
            }
            case "min", "max" -> {
                arity(c, 2, 20);
                for (Node x : a) {
                    expect(type(x, components, facts), Type.NUMBER, x.pos(), c.fn() + " works on numbers");
                }
                return Type.NUMBER;
            }
            case "abs" -> {
                arity(c, 1, 1);
                expect(type(a.get(0), components, facts), Type.NUMBER, c.pos(), "abs works on a number");
                return Type.NUMBER;
            }
            case "round", "round_up", "round_down" -> {
                arity(c, 1, 2);
                for (Node x : a) {
                    expect(type(x, components, facts), Type.NUMBER, x.pos(), c.fn() + " works on numbers");
                }
                return Type.NUMBER;
            }
            case "table" -> {
                arity(c, 2, 2);
                if (!(a.get(0) instanceof Str)) {
                    throw new Error("table needs the table's name in quotes first, as in table(\"meal_card\", designation)", a.get(0).pos());
                }
                type(a.get(1), components, facts);
                return Type.NUMBER;
            }
            case "ytd" -> {
                arity(c, 1, 1);
                if (!(a.get(0) instanceof Ref r) || !isComponent(r.name())) {
                    throw new Error("ytd needs a component, as in ytd(BONUS)", c.pos());
                }
                if (!components.contains(r.name())) {
                    throw new Error("No component called " + r.name(), r.pos());
                }
                return Type.NUMBER;
            }
            default -> throw new Error("Unknown function " + c.fn(), c.pos());
        }
    }

    private static Type merge(Type a, Type b, int pos) {
        if (a == Type.ANY) {
            return b;
        }
        if (b != Type.ANY && a != b) {
            throw new Error("Every result of case must be the same kind of value", pos);
        }
        return a;
    }

    private static void arity(Call c, int min, int max) {
        if (c.args().size() < min || c.args().size() > max) {
            String count = min == max ? String.valueOf(min) : min + " to " + max;
            throw new Error(c.fn() + " takes " + count + " values, not " + c.args().size(), c.pos());
        }
    }

    private static void expect(Type got, Type want, int pos, String message) {
        if (got != want && got != Type.ANY) {
            throw new Error(message, pos);
        }
    }

    private static String words(Type t) {
        return switch (t) {
            case NUMBER -> "a number";
            case BOOL -> "yes or no";
            case TEXT -> "text";
            default -> "a value";
        };
    }

    // ── evaluating ──────────────────────────────────────────────────────

    /** The result, and every name read with its value, for the line's explanation. */
    public record Result(BigDecimal value, Map<String, Object> read, boolean dividedByZero) {}

    public Result evaluate(Env env) {
        Map<String, Object> read = new LinkedHashMap<>();
        boolean[] zero = {false};
        Object v = eval(root, env, read, zero);
        if (!(v instanceof BigDecimal d)) {
            throw new Error("The formula did not give an amount", 0);
        }
        return new Result(d, read, zero[0]);
    }

    private static Object eval(Node n, Env env, Map<String, Object> read, boolean[] zero) {
        return switch (n) {
            case Num x -> x.value();
            case Str x -> x.value();
            case Bool x -> x.value();
            case Ref r -> {
                Object v = env.get(r.name());
                read.put(r.name(), v);
                yield v;
            }
            case Neg g -> num(eval(g.inner(), env, read, zero), g.pos()).negate();
            case Not t -> !bool(eval(t.inner(), env, read, zero), t.pos());
            case Bin b -> bin(b, env, read, zero);
            case Call c -> call(c, env, read, zero);
        };
    }

    private static Object bin(Bin b, Env env, Map<String, Object> read, boolean[] zero) {
        if (b.op().equals("and")) {
            return bool(eval(b.left(), env, read, zero), b.pos()) && bool(eval(b.right(), env, read, zero), b.pos());
        }
        if (b.op().equals("or")) {
            return bool(eval(b.left(), env, read, zero), b.pos()) || bool(eval(b.right(), env, read, zero), b.pos());
        }
        Object l = eval(b.left(), env, read, zero);
        Object r = eval(b.right(), env, read, zero);
        switch (b.op()) {
            case "=":
                return same(l, r);
            case "<>":
                return !same(l, r);
            default:
                break;
        }
        BigDecimal x = num(l, b.pos());
        BigDecimal y = num(r, b.pos());
        return switch (b.op()) {
            case "+" -> x.add(y);
            case "-" -> x.subtract(y);
            case "*" -> x.multiply(y, MC);
            case "/" -> {
                if (y.signum() == 0) {
                    zero[0] = true;
                    yield BigDecimal.ZERO;
                }
                yield x.divide(y, MC);
            }
            case "<" -> x.compareTo(y) < 0;
            case "<=" -> x.compareTo(y) <= 0;
            case ">" -> x.compareTo(y) > 0;
            case ">=" -> x.compareTo(y) >= 0;
            default -> throw new Error("Unknown operator " + b.op(), b.pos());
        };
    }

    private static boolean same(Object l, Object r) {
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (l instanceof String a && r instanceof String b) {
            return a.equalsIgnoreCase(b);
        }
        return java.util.Objects.equals(l, r);
    }

    private static Object call(Call c, Env env, Map<String, Object> read, boolean[] zero) {
        List<Node> a = c.args();
        switch (c.fn()) {
            case "if":
                return bool(eval(a.get(0), env, read, zero), c.pos()) ? eval(a.get(1), env, read, zero) : eval(a.get(2), env, read, zero);
            case "case":
                for (int i = 0; i < a.size() - 1; i += 2) {
                    if (bool(eval(a.get(i), env, read, zero), c.pos())) {
                        return eval(a.get(i + 1), env, read, zero);
                    }
                }
                return eval(a.get(a.size() - 1), env, read, zero);
            case "min":
            case "max": {
                BigDecimal best = null;
                for (Node x : a) {
                    BigDecimal v = num(eval(x, env, read, zero), x.pos());
                    if (best == null || (c.fn().equals("min") ? v.compareTo(best) < 0 : v.compareTo(best) > 0)) {
                        best = v;
                    }
                }
                return best;
            }
            case "abs":
                return num(eval(a.get(0), env, read, zero), c.pos()).abs();
            case "round":
            case "round_up":
            case "round_down": {
                BigDecimal v = num(eval(a.get(0), env, read, zero), c.pos());
                BigDecimal step = a.size() > 1 ? num(eval(a.get(1), env, read, zero), c.pos()) : BigDecimal.ONE;
                RoundingMode mode = c.fn().equals("round") ? RoundingMode.HALF_UP
                        : c.fn().equals("round_up") ? RoundingMode.CEILING : RoundingMode.FLOOR;
                return roundTo(v, step, mode);
            }
            case "table": {
                String name = ((Str) a.get(0)).value();
                Object key = eval(a.get(1), env, read, zero);
                String k = key instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(key);
                BigDecimal v = env.table(name, k);
                read.put("table(\"" + name + "\", " + k + ")", v);
                return v;
            }
            case "ytd": {
                String code = ((Ref) a.get(0)).name();
                BigDecimal v = env.ytd(code);
                read.put("ytd(" + code + ")", v);
                return v;
            }
            default:
                throw new Error("Unknown function " + c.fn(), c.pos());
        }
    }

    /** Rounds to a multiple of the step (1 for a rupee, 10 for tens). */
    static BigDecimal roundTo(BigDecimal v, BigDecimal step, RoundingMode mode) {
        if (step.signum() <= 0) {
            return v;
        }
        return v.divide(step, 0, mode).multiply(step);
    }

    private static BigDecimal num(Object v, int pos) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        throw new Error("Expected a number here", pos);
    }

    private static boolean bool(Object v, int pos) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new Error("Expected yes or no here", pos);
    }

    // ── parsing ─────────────────────────────────────────────────────────

    /** The most "not" or "-" that may follow one another; far more than any real formula needs. */
    private static final int MAX_PREFIX = 50;

    private static final class Parser {
        private final String s;
        private int i;
        private int depth;
        private int prefix;   // prefix operators in a row (not, -): they nest without brackets

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean word(String w) {
            skip();
            int end = i + w.length();
            if (s.regionMatches(true, i, w, 0, w.length()) && (end >= s.length() || !Character.isLetterOrDigit(s.charAt(end)) && s.charAt(end) != '_')) {
                i = end;
                return true;
            }
            return false;
        }

        boolean symbol(String sym) {
            skip();
            if (s.startsWith(sym, i)) {
                i += sym.length();
                return true;
            }
            return false;
        }

        Node expression(int unused) {
            if (++depth > 20) {
                throw new Error("The formula is nested too deeply", i);
            }
            Node n = or();
            depth--;
            return n;
        }

        Node or() {
            Node n = and();
            while (true) {
                int at = i;
                if (word("or")) {
                    n = new Bin("or", n, and(), at);
                } else {
                    return n;
                }
            }
        }

        Node and() {
            Node n = not();
            while (true) {
                int at = i;
                if (word("and")) {
                    n = new Bin("and", n, not(), at);
                } else {
                    return n;
                }
            }
        }

        /** Counts one more prefix operator in a row. Without a limit, 2,000 characters of them recurse deeper than the stack allows. */
        private void enterPrefix() {
            if (++prefix > MAX_PREFIX) {
                throw new Error("Too many \"not\" or \"-\" in a row", i);
            }
        }

        Node not() {
            int at = i;
            if (word("not")) {
                enterPrefix();
                Node operand = not();
                prefix--;
                return new Not(operand, at);
            }
            return comparison();
        }

        Node comparison() {
            Node n = sum();
            skip();
            int at = i;
            for (String op : List.of("<=", ">=", "<>", "=", "<", ">")) {
                if (symbol(op)) {
                    return new Bin(op, n, sum(), at);
                }
            }
            return n;
        }

        Node sum() {
            Node n = product();
            while (true) {
                skip();
                int at = i;
                if (symbol("+")) {
                    n = new Bin("+", n, product(), at);
                } else if (symbol("-")) {
                    n = new Bin("-", n, product(), at);
                } else {
                    return n;
                }
            }
        }

        Node product() {
            Node n = unary();
            while (true) {
                skip();
                int at = i;
                if (symbol("*")) {
                    n = new Bin("*", n, unary(), at);
                } else if (symbol("/")) {
                    n = new Bin("/", n, unary(), at);
                } else {
                    return n;
                }
            }
        }

        Node unary() {
            skip();
            int at = i;
            if (symbol("-")) {
                enterPrefix();
                Node operand = unary();
                prefix--;
                return new Neg(operand, at);
            }
            return primary();
        }

        Node primary() {
            skip();
            int at = i;
            if (i >= s.length()) {
                throw new Error("The formula ends too soon", i);
            }
            char ch = s.charAt(i);
            if (ch == '(') {
                i++;
                Node n = expression(0);
                if (!symbol(")")) {
                    throw new Error("A bracket is not closed", at);
                }
                return n;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String text = s.substring(start, i);
                try {
                    return new Num(new BigDecimal(text), start);
                } catch (NumberFormatException e) {
                    throw new Error("\"" + text + "\" is not a number", start);
                }
            }
            if (ch == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) {
                    throw new Error("Text in quotes is not closed", i);
                }
                String text = s.substring(i + 1, end);
                i = end + 1;
                return new Str(text, at);
            }
            if (Character.isLetter(ch)) {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                    i++;
                }
                String name = s.substring(start, i);
                if (name.endsWith(".")) {
                    throw new Error("A name cannot end with a dot", i - 1);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("true") || lower.equals("yes")) {
                    return new Bool(true, start);
                }
                if (lower.equals("false") || lower.equals("no")) {
                    return new Bool(false, start);
                }
                skip();
                if (i < s.length() && s.charAt(i) == '(') {
                    if (!FUNCTIONS.contains(lower)) {
                        throw new Error("Unknown function " + name, start);
                    }
                    i++;
                    List<Node> args = new ArrayList<>();
                    skip();
                    if (!symbol(")")) {
                        do {
                            args.add(expression(0));
                        } while (symbol(","));
                        if (!symbol(")")) {
                            throw new Error("Expected \",\" or \")\" in " + lower, i);
                        }
                    }
                    return new Call(lower, args, start);
                }
                if (!isComponent(name) && !name.equals(lower)) {
                    throw new Error("Component codes are in capitals (" + name.toUpperCase(Locale.ROOT)
                            + "); facts are in lower case (location.metro)", start);
                }
                return new Ref(name, start);
            }
            throw new Error("Unexpected \"" + ch + "\"", i);
        }
    }
}

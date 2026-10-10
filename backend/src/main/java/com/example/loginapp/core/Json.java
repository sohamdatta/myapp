package com.example.loginapp.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reading and writing for jsonb columns and audit details, so the
 * core needs no JSON library. Objects read back as ordered maps, arrays as
 * lists, numbers as BigDecimal.
 */
public final class Json {

    private Json() {}

    /** A flat map with every value written as a string: the shape of most audit details. */
    static String object(Map<String, ?> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(e.getKey())).append(':')
                    .append(e.getValue() == null ? "null" : quote(String.valueOf(e.getValue())));
        }
        return out.append('}').toString();
    }

    /** Any value made of maps, collections, strings, numbers, booleans and nulls. Other values are written as strings. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(String.valueOf(e.getKey()))).append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof BigDecimal d) {
            out.append(d.toPlainString());
        } else if (value instanceof Number n) {
            out.append(n);
        } else {
            out.append(quote(value.toString()));
        }
    }

    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Reads one JSON value. Throws IllegalArgumentException when the text is not JSON. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.space();
        Object value = p.value(0);
        p.space();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("Unexpected text after the JSON value at " + p.i);
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        Object value(int depth) {
            if (depth > 32) {
                throw new IllegalArgumentException("JSON nested too deeply");
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    i++;
                    Map<String, Object> map = new LinkedHashMap<>();
                    space();
                    if (peek('}')) {
                        i++;
                        return map;
                    }
                    while (true) {
                        space();
                        String key = string();
                        space();
                        expect(':');
                        space();
                        map.put(key, value(depth + 1));
                        space();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect('}');
                        return map;
                    }
                }
                case '[' -> {
                    i++;
                    List<Object> list = new ArrayList<>();
                    space();
                    if (peek(']')) {
                        i++;
                        return list;
                    }
                    while (true) {
                        space();
                        list.add(value(depth + 1));
                        space();
                        if (peek(',')) {
                            i++;
                            continue;
                        }
                        expect(']');
                        return list;
                    }
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    word("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    word("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    word("null");
                    return null;
                }
                default -> {
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    if (start == i) {
                        throw new IllegalArgumentException("Unexpected character in JSON at " + i);
                    }
                    return new BigDecimal(s.substring(start, i));
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Bad escape in JSON");
                            }
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> out.append(e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in JSON");
        }

        void word(String w) {
            if (!s.startsWith(w, i)) {
                throw new IllegalArgumentException("Unexpected word in JSON at " + i);
            }
            i += w.length();
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("Expected '" + c + "' in JSON at " + i);
            }
            i++;
        }

        void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}

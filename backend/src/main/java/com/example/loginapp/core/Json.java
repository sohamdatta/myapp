package com.example.loginapp.core;

import java.util.Map;

/** Writes a flat map of strings as a JSON object, for audit details. */
final class Json {

    private Json() {}

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
}

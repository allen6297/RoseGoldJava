package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DapJson {

    private DapJson() {
    }

    static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    static Object decode(String text) {
        Parser p = new Parser(text == null ? "" : text);
        Object value = p.value();
        p.skip();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("trailing json at " + p.i);
        }
        return value;
    }

    static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return List.of();
    }

    static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static int integer(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
            return;
        }
        switch (value) {
            case String s -> {
                out.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (c < 32) {
                                out.append(String.format("\\u%04x", (int) c));
                            } else {
                                out.append(c);
                            }
                        }
                    }
                }
                out.append('"');
                return;
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, String.valueOf(e.getKey()));
                    out.append(':');
                    write(out, e.getValue());
                }
                out.append('}');
                return;
            }
            case Iterable<?> it -> {
                out.append('[');
                boolean first = true;
                for (Object item : it) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, item);
                }
                out.append(']');
                return;
            }
            default -> {
            }
        }
        write(out, String.valueOf(value));
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException("empty json");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't' || c == 'f') {
                return bool();
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return number();
        }

        Map<String, Object> object() {
            expect("{");
            Map<String, Object> out = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                i++;
                return out;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(":");
                out.put(key, value());
                skip();
                if (peek('}')) {
                    i++;
                    return out;
                }
                expect(",");
            }
        }

        List<Object> array() {
            expect("[");
            List<Object> out = new ArrayList<>();
            skip();
            if (peek(']')) {
                i++;
                return out;
            }
            while (true) {
                out.add(value());
                skip();
                if (peek(']')) {
                    i++;
                    return out;
                }
                expect(",");
            }
        }

        String string() {
            expect("\"");
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                out.append(switch (e) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> e;
                });
            }
            throw new IllegalArgumentException("unterminated string");
        }

        char unicode() {
            if (i + 4 > s.length()) {
                throw new IllegalArgumentException("bad unicode");
            }
            int v = Integer.parseInt(s.substring(i, i + 4), 16);
            i += 4;
            return (char) v;
        }

        Object number() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean frac = false;
            if (peek('.')) {
                frac = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                frac = true;
                i++;
                if (peek('+') || peek('-')) {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String raw = s.substring(start, i);
            if (frac) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        boolean bool() {
            if (peek('t')) {
                expect("true");
                return true;
            }
            expect("false");
            return false;
        }

        void expect(String token) {
            skip();
            if (!s.startsWith(token, i)) {
                throw new IllegalArgumentException("expected " + token + " at " + i);
            }
            i += token.length();
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void skip() {
            while (i < s.length() && s.charAt(i) <= ' ') {
                i++;
            }
        }
    }
}

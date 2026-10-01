package com.rosegoldc.lang;

import java.util.ArrayList;

final class Json {

    private static final int MAX_DEPTH = 64;
    private static final String HEX = "0123456789abcdef";

    private Json() {
    }

    static Value parse(String src, int line, int col) {
        return new Parser(src, line, col).parse();
    }

    static String stringify(Value v, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            return "json nesting too deep";
        }
        switch (v.kind) {
            case Bool -> out.append(Boolean.toString(v.b));
            case Int -> out.append(v.i);
            case Float -> {
                if (!Double.isFinite(v.real)) {
                    return "cannot stringify non-finite Float";
                }
                out.append(jsonFloat(v.real));
            }
            case String -> {
                out.append('"');
                escape(v.s, out);
                out.append('"');
            }
            case Array -> {
                out.append('[');
                if (v.items != null) {
                    for (int n = 0; n < v.items.size(); n++) {
                        if (n > 0) {
                            out.append(',');
                        }
                        String err = stringify(v.items.get(n), out, depth + 1);
                        if (err != null) {
                            return err;
                        }
                    }
                }
                out.append(']');
            }
            case Map -> {
                out.append('{');
                if (v.dict != null) {
                    boolean first = true;
                    for (String k : v.dict.order) {
                        Value val = v.dict.fields.get(k);
                        if (val == null) {
                            continue;
                        }
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        out.append('"');
                        escape(k, out);
                        out.append("\":");
                        String err = stringify(val, out, depth + 1);
                        if (err != null) {
                            return err;
                        }
                    }
                }
                out.append('}');
            }
            case Struct -> {
                out.append('{');
                if (v.rec != null) {
                    boolean first = true;
                    for (String k : v.rec.order) {
                        Value val = v.rec.fields.get(k);
                        if (val == null) {
                            continue;
                        }
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        out.append('"');
                        escape(k, out);
                        out.append("\":");
                        String err = stringify(val, out, depth + 1);
                        if (err != null) {
                            return err;
                        }
                    }
                }
                out.append('}');
            }
            case Enum -> {
                out.append('"');
                escape(v.variant, out);
                out.append('"');
            }
            default -> {
                return "cannot stringify value";
            }
        }
        return null;
    }

    private static String jsonFloat(double n) {
        if (n == 0.0) {
            return Double.doubleToRawLongBits(n) < 0 ? "-0" : "0";
        }
        String s = Double.toString(n);
        if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            return s + ".0";
        }
        return s;
    }

    private static void escape(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00");
                        out.append(HEX.charAt(c >> 4));
                        out.append(HEX.charAt(c & 0xf));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }

    private static final class Parser {
        private final String src;
        private final int errLine;
        private final int errCol;
        private int i;

        Parser(String src, int line, int col) {
            this.src = src == null ? "" : src;
            this.errLine = line;
            this.errCol = col;
        }

        Value parse() {
            Value v = parseValue(0);
            skip();
            if (i != src.length()) {
                fail("invalid JSON");
            }
            return v;
        }

        private Value parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                fail("JSON nesting too deep");
            }
            skip();
            char c = peek();
            if (c == '"') {
                return Value.makeString(parseString());
            }
            if (c == '{') {
                getc();
                Value.MapData data = new Value.MapData();
                skip();
                if (peek() == '}') {
                    getc();
                    return Value.makeMap(data);
                }
                while (true) {
                    skip();
                    if (peek() != '"') {
                        fail("invalid JSON");
                    }
                    String key = parseString();
                    skip();
                    if (getc() != ':') {
                        fail("invalid JSON");
                    }
                    Value val = parseValue(depth + 1);
                    if (!data.fields.containsKey(key)) {
                        data.order.add(key);
                    }
                    data.fields.put(key, val);
                    skip();
                    char sep = getc();
                    if (sep == '}') {
                        return Value.makeMap(data);
                    }
                    if (sep != ',') {
                        fail("invalid JSON");
                    }
                }
            }
            if (c == '[') {
                getc();
                ArrayList<Value> items = new ArrayList<>();
                skip();
                if (peek() == ']') {
                    getc();
                    return Value.makeArray(items);
                }
                while (true) {
                    items.add(parseValue(depth + 1));
                    skip();
                    char sep = getc();
                    if (sep == ']') {
                        return Value.makeArray(items);
                    }
                    if (sep != ',') {
                        fail("invalid JSON");
                    }
                }
            }
            if (c == 't') {
                if (!matchLit("true")) {
                    fail("invalid JSON");
                }
                return Value.makeBool(true);
            }
            if (c == 'f') {
                if (!matchLit("false")) {
                    fail("invalid JSON");
                }
                return Value.makeBool(false);
            }
            if (c == 'n') {
                if (!matchLit("null")) {
                    fail("invalid JSON");
                }
                fail("json null is not supported");
            }
            if (c == '-' || isDigit(c)) {
                return parseNumber();
            }
            fail("invalid JSON");
            return Value.makeVoid();
        }

        private Value parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            if (peek() == '0') {
                i++;
                if (isDigit(peek())) {
                    fail("invalid JSON");
                }
            } else if (isDigit(peek())) {
                while (isDigit(peek())) {
                    i++;
                }
            } else {
                fail("invalid JSON");
            }
            boolean frac = false;
            if (peek() == '.') {
                frac = true;
                i++;
                if (!isDigit(peek())) {
                    fail("invalid JSON");
                }
                while (isDigit(peek())) {
                    i++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                frac = true;
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (!isDigit(peek())) {
                    fail("invalid JSON");
                }
                while (isDigit(peek())) {
                    i++;
                }
            }
            String tok = src.substring(start, i);
            if (!frac) {
                try {
                    return Value.makeInt(Long.parseLong(tok, 10));
                } catch (NumberFormatException ignored) {
                }
            }
            try {
                double n = Double.parseDouble(tok);
                if (!Double.isFinite(n)) {
                    fail("invalid JSON");
                }
                return Value.makeFloat(n);
            } catch (NumberFormatException ex) {
                fail("invalid JSON");
                return Value.makeFloat(0);
            }
        }

        private String parseString() {
            if (getc() != '"') {
                fail("invalid JSON");
            }
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = getc();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = getc();
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> appendUtf16(out, hex4());
                        default -> fail("invalid JSON");
                    }
                } else if (c < 0x20) {
                    fail("invalid JSON");
                } else {
                    out.append(c);
                }
            }
        }

        private int hex4() {
            int n = 0;
            for (int k = 0; k < 4; k++) {
                char c = getc();
                n <<= 4;
                if (c >= '0' && c <= '9') {
                    n += c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    n += c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    n += c - 'A' + 10;
                } else {
                    fail("invalid JSON");
                }
            }
            return n;
        }

        private static void appendUtf16(StringBuilder out, int cp) {
            if (cp <= 0xFFFF) {
                out.append((char) cp);
            } else if (Character.isValidCodePoint(cp)) {
                out.append(Character.toChars(cp));
            } else {
                out.append((char) cp);
            }
        }

        private boolean matchLit(String lit) {
            if (!src.startsWith(lit, i)) {
                return false;
            }
            i += lit.length();
            return true;
        }

        private void skip() {
            while (i < src.length() && isSpace(src.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            return i < src.length() ? src.charAt(i) : '\0';
        }

        private char getc() {
            if (i >= src.length()) {
                fail("invalid JSON");
            }
            return src.charAt(i++);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
        }

        private void fail(String msg) {
            throw new Interp.ThrowEscape(Value.makeString(msg), errLine, errCol);
        }
    }
}

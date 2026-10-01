package com.rosegoldc.lang;

import java.util.LinkedHashMap;
import java.util.Map;

final class Toml {

    private Toml() {
    }

    static final class Value {
        enum Kind {
            STRING, BOOL, TABLE
        }

        final Kind kind;
        final String s;
        final boolean b;
        final Map<String, Value> table;
        final int line;
        final int col;

        private Value(Kind kind, String s, boolean b, Map<String, Value> table, int line, int col) {
            this.kind = kind;
            this.s = s;
            this.b = b;
            this.table = table;
            this.line = line;
            this.col = col;
        }

        static Value ofString(String s, int line, int col) {
            return new Value(Kind.STRING, s, false, null, line, col);
        }

        static Value ofBool(boolean b, int line, int col) {
            return new Value(Kind.BOOL, null, b, null, line, col);
        }

        static Value ofTable(int line, int col) {
            return new Value(Kind.TABLE, null, false, new LinkedHashMap<>(), line, col);
        }

        boolean isTable() {
            return kind == Kind.TABLE;
        }

        boolean isString() {
            return kind == Kind.STRING;
        }

        Value get(String key) {
            return table == null ? null : table.get(key);
        }
    }

    static Value parse(String source, String file) {
        return new Parser(source == null ? "" : source, file == null ? "" : file).parse();
    }

    private static final class Parser {
        final String src;
        final String file;
        int i;
        int line = 1;
        int col = 1;

        Parser(String src, String file) {
            this.src = src;
            this.file = file;
        }

        Value parse() {
            Value root = Value.ofTable(1, 1);
            Value current = root;
            skip();
            while (i < src.length()) {
                if (src.charAt(i) == '[') {
                    current = header(root);
                    skip();
                    continue;
                }
                int keyLine = line;
                int keyCol = col;
                String key = key();
                skip();
                if (i >= src.length() || src.charAt(i) != '=') {
                    fail(keyLine, keyCol, "expected '=' after key");
                }
                bump();
                skip();
                Value val = value();
                put(current, key, val, keyLine, keyCol);
                skip();
            }
            return root;
        }

        Value header(Value root) {
            int startLine = line;
            int startCol = col;
            bump();
            if (i < src.length() && src.charAt(i) == '[') {
                fail(startLine, startCol, "array of tables is not supported");
            }
            skipSpace();
            java.util.List<String> parts = new java.util.ArrayList<>();
            parts.add(key());
            skipSpace();
            while (i < src.length() && src.charAt(i) == '.') {
                bump();
                skipSpace();
                parts.add(key());
                skipSpace();
            }
            if (i >= src.length() || src.charAt(i) != ']') {
                fail(startLine, startCol, "expected ']'");
            }
            bump();
            Value cur = root;
            for (int p = 0; p < parts.size(); p++) {
                String part = parts.get(p);
                Value next = cur.get(part);
                if (next == null) {
                    next = Value.ofTable(startLine, startCol);
                    cur.table.put(part, next);
                } else if (!next.isTable()) {
                    fail(startLine, startCol, "key '" + part + "' is not a table");
                }
                cur = next;
            }
            return cur;
        }

        String key() {
            skipSpace();
            if (i >= src.length()) {
                fail(line, col, "expected key");
            }
            if (src.charAt(i) == '"') {
                return string().s;
            }
            int startLine = line;
            int startCol = col;
            if (!isBare(src.charAt(i))) {
                fail(startLine, startCol, "expected key");
            }
            int start = i;
            while (i < src.length() && isBare(src.charAt(i))) {
                bump();
            }
            return src.substring(start, i);
        }

        Value value() {
            if (i >= src.length()) {
                fail(line, col, "expected value");
            }
            char c = src.charAt(i);
            if (c == '"') {
                return string();
            }
            if (c == 't' || c == 'f') {
                return bool();
            }
            if (c == '{') {
                fail(line, col, "inline tables are not supported");
            }
            fail(line, col, "expected string or boolean");
            return null;
        }

        Value string() {
            int startLine = line;
            int startCol = col;
            bump();
            StringBuilder out = new StringBuilder();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '"') {
                    bump();
                    return Value.ofString(out.toString(), startLine, startCol);
                }
                if (c == '\n') {
                    fail(startLine, startCol, "unterminated string");
                }
                if (c == '\\') {
                    bump();
                    if (i >= src.length()) {
                        fail(startLine, startCol, "unterminated string");
                    }
                    char e = src.charAt(i);
                    bump();
                    switch (e) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        default -> fail(startLine, startCol, "unknown string escape");
                    }
                    continue;
                }
                out.append(c);
                bump();
            }
            fail(startLine, startCol, "unterminated string");
            return null;
        }

        Value bool() {
            int startLine = line;
            int startCol = col;
            if (match("true")) {
                return Value.ofBool(true, startLine, startCol);
            }
            if (match("false")) {
                return Value.ofBool(false, startLine, startCol);
            }
            fail(startLine, startCol, "expected string or boolean");
            return null;
        }

        boolean match(String word) {
            if (i + word.length() > src.length()) {
                return false;
            }
            if (!src.regionMatches(i, word, 0, word.length())) {
                return false;
            }
            int after = i + word.length();
            if (after < src.length() && isBare(src.charAt(after))) {
                return false;
            }
            for (int n = 0; n < word.length(); n++) {
                bump();
            }
            return true;
        }

        void put(Value table, String key, Value val, int keyLine, int keyCol) {
            if (table.table.containsKey(key)) {
                fail(keyLine, keyCol, "duplicate key '" + key + "'");
            }
            table.table.put(key, val);
        }

        void skip() {
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    bump();
                    continue;
                }
                if (c == '#') {
                    while (i < src.length() && src.charAt(i) != '\n') {
                        bump();
                    }
                    continue;
                }
                break;
            }
        }

        void skipSpace() {
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') {
                    bump();
                    continue;
                }
                break;
            }
        }

        void bump() {
            if (i < src.length() && src.charAt(i) == '\n') {
                line++;
                col = 1;
                i++;
                return;
            }
            i++;
            col++;
        }

        void fail(int atLine, int atCol, String message) {
            Diagnostic d = new Diagnostic(file, atLine, atCol, message);
            d.kind = "error";
            throw new LangException(d.toHuman(), d);
        }

        static boolean isBare(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }
    }
}

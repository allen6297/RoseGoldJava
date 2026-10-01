package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

public final class Tests {

    public static final class Fn {
        public final String name;
        public final int line;
        public final int col;
        public final int start;
        public final int end;

        Fn(String name, int line, int col, int start, int end) {
            this.name = name;
            this.line = line;
            this.col = col;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Result {
        public final String name;
        public final boolean passed;
        public final String message;

        Result(String name, boolean passed, String message) {
            this.name = name;
            this.passed = passed;
            this.message = message == null ? "" : message;
        }
    }

    private Tests() {
    }

    public static List<Fn> inFile(String source) {
        if (source == null) {
            source = "";
        }
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return List.of();
        }
        List<Fn> out = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).kind != Tok.Function) {
                continue;
            }
            Token name = tokens.get(i + 1);
            if (name.kind != Tok.Identifier) {
                continue;
            }
            if (!hasTestAttr(tokens, i)) {
                continue;
            }
            int start = SourcePos.offset(source, name.line, name.col);
            int end = start + name.text.length();
            out.add(new Fn(name.text, name.line, name.col, start, end));
        }
        return out;
    }

    public static boolean hasAny(String source) {
        return !inFile(source).isEmpty();
    }

    public static Fn at(String source, int offset) {
        if (source == null) {
            source = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        for (Fn fn : inFile(source)) {
            if (offset >= fn.start && offset <= fn.end) {
                return fn;
            }
        }
        return null;
    }

    public static Result parseLine(String line) {
        if (line == null) {
            return null;
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.startsWith("ok ")) {
            String name = line.substring(3).trim();
            if (name.isEmpty()) {
                return null;
            }
            return new Result(name, true, "");
        }
        if (line.startsWith("FAIL ")) {
            String rest = line.substring(5);
            int colon = rest.indexOf(": ");
            String name;
            String message;
            if (colon >= 0) {
                name = rest.substring(0, colon).trim();
                message = rest.substring(colon + 2);
            } else {
                name = rest.trim();
                message = "";
            }
            if (name.isEmpty()) {
                return null;
            }
            return new Result(name, false, message);
        }
        return null;
    }

    private static boolean hasTestAttr(List<Token> tokens, int fnIndex) {
        int k = fnIndex;
        boolean test = false;
        while (k > 0) {
            Token prev = tokens.get(k - 1);
            if (prev.kind == Tok.Pub || prev.kind == Tok.Private || prev.kind == Tok.Protected
                    || prev.kind == Tok.Abstract || prev.kind == Tok.Final || prev.kind == Tok.Async
                    || prev.kind == Tok.LineComment || prev.kind == Tok.BlockComment) {
                k--;
                continue;
            }
            if (k >= 2 && tokens.get(k - 2).kind == Tok.At && prev.kind == Tok.Identifier) {
                if ("test".equals(prev.text)) {
                    test = true;
                }
                k -= 2;
                continue;
            }
            break;
        }
        return test;
    }
}

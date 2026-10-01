package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

final class Idents {

    static final class Ident {
        String name = "";
        String qualifier = "";
        int start;
        int end;
    }

    private Idents() {
    }

    static Ident at(String source, int offset) {
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return null;
        }
        int[] lc = SourcePos.lineCol(source, offset);
        int line1 = lc[0];
        int col1 = lc[1];
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Tok.Identifier && t.kind != Tok.True && t.kind != Tok.False) {
                continue;
            }
            if (t.line != line1) {
                continue;
            }
            int start = t.col;
            int end = t.col + t.text.length();
            if (col1 < start || col1 > end) {
                continue;
            }
            Ident ident = new Ident();
            ident.name = t.text;
            ident.start = SourcePos.offset(source, t.line, t.col);
            ident.end = ident.start + t.text.length();
            if (i >= 2 && tokens.get(i - 1).kind == Tok.Dot && tokens.get(i - 2).kind == Tok.Identifier) {
                ident.qualifier = tokens.get(i - 2).text;
            }
            return ident;
        }
        return null;
    }

    static boolean identChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static String ltrim(String s) {
        if (s == null) {
            return "";
        }
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    static boolean afterImportOrFrom(String prefix) {
        String line = ltrim(prefix);
        return line.startsWith("import ") || line.equals("import")
                || line.startsWith("from ") || line.equals("from");
    }

    static String fromImportCrate(String prefix) {
        String line = ltrim(prefix);
        if (!line.startsWith("from ")) {
            return "";
        }
        String rest = ltrim(line.substring(5));
        int i = 0;
        while (i < rest.length()) {
            char c = rest.charAt(i);
            if (identChar(c)) {
                i++;
            } else if (c == '.' && i + 1 < rest.length() && identChar(rest.charAt(i + 1))) {
                i++;
            } else {
                break;
            }
        }
        if (i == 0) {
            return "";
        }
        int j = i;
        while (j < rest.length() && Character.isWhitespace(rest.charAt(j))) {
            j++;
        }
        if (j + 6 > rest.length() || !rest.startsWith("import", j)) {
            return "";
        }
        if (j + 6 < rest.length() && identChar(rest.charAt(j + 6))) {
            return "";
        }
        return rest.substring(0, i);
    }

    static Token nameToken(String source, int line, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return null;
        }
        for (Token t : tokens) {
            if (t.line == line && t.kind == Tok.Identifier && name.equals(t.text)) {
                return t;
            }
        }
        return null;
    }
}

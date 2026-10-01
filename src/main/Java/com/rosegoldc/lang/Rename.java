package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class Rename {

    private static final Set<String> KEYWORDS = Set.of(
            "fn", "var", "const", "struct", "data", "class", "trait", "extends",
            "enum", "mod", "impl", "pub", "private", "protected", "abstract", "final",
            "match", "switch", "for", "in", "super", "signal", "import", "from", "as",
            "return", "pass", "break", "continue", "if", "elif", "else", "while",
            "self", "true", "false", "none",
            "try", "do", "throws", "throw", "catch",
            "async", "await", "spawn"
    );

    private Rename() {
    }

    public static boolean isIdent(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    public static boolean isKeyword(String name) {
        return name != null && KEYWORDS.contains(name);
    }

    public static boolean isValidName(String name) {
        return isIdent(name) && !isKeyword(name);
    }

    public static boolean pathHasBuiltin(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String n = path.replace('\\', '/');
        return n.contains("/builtin/") || n.startsWith("builtin/");
    }

    public static boolean isStdlibFile(String path) {
        if (pathHasBuiltin(path)) {
            return true;
        }
        return path != null && !path.isEmpty() && !Stdlib.crateNameOfFile(path).isEmpty();
    }

    public static boolean skipFile(String originPath, String filePath) {
        return isStdlibFile(filePath) && !isStdlibFile(originPath);
    }

    public static List<Usages.Hit> rewriteHits(String source, String path, int offset) {
        List<Usages.Hit> out = new ArrayList<>();
        boolean alias = fromImportAlias(source, path, offset);
        for (Usages.Hit hit : Usages.includingCrates(source, path, offset)) {
            if (skipFile(path, hit.path)) {
                continue;
            }
            if (alias && !Stdlib.sameRgFile(hit.path, path)) {
                continue;
            }
            out.add(hit);
        }
        return out;
    }

    private static boolean fromImportAlias(String source, String path, int offset) {
        try {
            Idents.Ident ident = Idents.at(source, offset);
            if (ident == null || ident.name.isEmpty()) {
                return false;
            }
            Program program = Parser.parseSource(source, path, new ArrayList<>());
            String exported = Stdlib.fromBindExported(program, ident.name);
            return !exported.isEmpty() && !exported.equals(ident.name);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static String apply(String source, List<Usages.Hit> hits, String newName) {
        if (source == null) {
            source = "";
        }
        if (newName == null || hits == null || hits.isEmpty()) {
            return source;
        }
        List<Usages.Hit> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparingInt((Usages.Hit h) -> h.start).reversed());
        StringBuilder out = new StringBuilder(source);
        for (Usages.Hit hit : ordered) {
            if (hit.start < 0 || hit.end > out.length() || hit.start > hit.end) {
                continue;
            }
            out.replace(hit.start, hit.end, newName);
        }
        return out.toString();
    }
}

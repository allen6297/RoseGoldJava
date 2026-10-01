package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class UnusedImports {

    private UnusedImports() {
    }

    static void check(String source, String path, Program program, List<Diagnostic> diags) {
        if (program == null || source == null) {
            return;
        }
        Set<Integer> importLines = new HashSet<>();
        List<ImportDecl> imports = new ArrayList<>();
        collect(program, imports, importLines);
        if (imports.isEmpty()) {
            return;
        }
        Set<String> used = usedIdents(source, path, importLines);
        for (ImportDecl im : imports) {
            String bind = bindName(im);
            if (bind.isEmpty() || used.contains(bind) || hasErrorOnLine(diags, im.line)) {
                continue;
            }
            Diagnostic d = new Diagnostic(path, im.line, im.col, "unused import '" + bind + "'");
            d.severity = "warning";
            d.kind = "unused";
            diags.add(d);
        }
    }

    static String bindName(ImportDecl im) {
        if (im == null || im.path.isEmpty()) {
            return "";
        }
        if (im.isFrom) {
            if (im.path.size() < 2) {
                return "";
            }
            return im.alias == null || im.alias.isEmpty() ? im.path.get(1) : im.alias;
        }
        return im.alias == null || im.alias.isEmpty() ? im.path.getLast() : im.alias;
    }

    private static void collect(Program program, List<ImportDecl> out, Set<Integer> lines) {
        add(program.imports, out, lines);
        for (ModDecl m : program.mods) {
            collectMod(m, out, lines);
        }
    }

    private static void collectMod(ModDecl mod, List<ImportDecl> out, Set<Integer> lines) {
        add(mod.imports, out, lines);
        for (ModDecl nested : mod.mods) {
            collectMod(nested, out, lines);
        }
    }

    private static void add(List<ImportDecl> imports, List<ImportDecl> out, Set<Integer> lines) {
        for (ImportDecl im : imports) {
            out.add(im);
            lines.add(im.line);
        }
    }

    private static Set<String> usedIdents(String source, String path, Set<Integer> importLines) {
        Set<String> used = new HashSet<>();
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return used;
        }
        for (Token t : tokens) {
            if (t.kind != Tok.Identifier || importLines.contains(t.line)) {
                continue;
            }
            used.add(t.text);
        }
        return used;
    }

    private static boolean hasErrorOnLine(List<Diagnostic> diags, int line) {
        for (Diagnostic d : diags) {
            if (d.line == line && d.severity != null && !d.severity.equals("warning")
                    && !d.severity.equals("warn") && !d.severity.equals("info")) {
                return true;
            }
        }
        return false;
    }
}

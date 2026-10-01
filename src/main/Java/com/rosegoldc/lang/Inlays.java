package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Inlays {

    public enum Kind {
        Param,
        Type
    }

    public static final class Hint {
        public final Kind kind;
        public final String label;
        public final int line;
        public final int col;

        Hint(Kind kind, String label, int line, int col) {
            this.kind = kind;
            this.label = label;
            this.line = line;
            this.col = col;
        }
    }

    private Inlays() {
    }

    public static List<Hint> collect(String source, String path) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        Program program;
        try {
            program = Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return List.of();
        }
        Map<String, List<String>> fnParams = new LinkedHashMap<>();
        fnParams.put("len", List.of("xs"));
        fnParams.put("assert", List.of("cond"));
        collectParams(program, fnParams);
        collectCrateParams(program, path, fnParams);
        List<Hint> hints = new ArrayList<>();
        walkProgram(program, fnParams, hints);
        return hints;
    }

    private static void collectParams(Program program, Map<String, List<String>> fnParams) {
        walkItems(program, fnParams);
        for (ModDecl m : program.mods) {
            walkModItems(m, fnParams);
        }
    }

    private static void collectCrateParams(Program program, String path, Map<String, List<String>> fnParams) {
        Set<String> crates = new LinkedHashSet<>();
        for (ImportDecl im : program.imports) {
            if (!im.path.isEmpty()) {
                crates.add(im.path.getFirst());
            }
        }
        addCrateNamesFromProgram(program, crates);
        Set<String> resolved = new LinkedHashSet<>();
        for (String crate : crates) {
            String hit = Stdlib.crateOf(crate, program, path);
            resolved.add(hit.isEmpty() ? crate : hit);
        }
        for (String crate : resolved) {
            loadCrateParams(crate, path, fnParams);
        }
        for (ImportDecl im : program.imports) {
            if (!im.isFrom || im.path.size() < 2) {
                continue;
            }
            if (im.alias == null || im.alias.isEmpty()) {
                continue;
            }
            String exported = im.path.get(1);
            List<String> params = fnParams.get(exported);
            if (params != null) {
                putParamsIfAbsent(fnParams, im.alias, params);
            }
        }
    }

    private static void addCrateNamesFromProgram(Program program, Set<String> crates) {
        addCrateNamesFromFns(program.fns, crates);
        for (StructDecl st : program.structs) {
            addCrateNamesFromFns(st.methods, crates);
        }
        for (ClassDecl c : program.classes) {
            addCrateNamesFromFns(c.methods, crates);
            for (NestedImpl ti : c.traitImpls) {
                addCrateNamesFromFns(ti.methods, crates);
            }
        }
        for (ImplDecl im : program.impls) {
            addCrateNamesFromFns(im.methods, crates);
        }
        for (ModDecl m : program.mods) {
            addCrateNamesFromMod(m, crates);
        }
    }

    private static void addCrateNamesFromMod(ModDecl m, Set<String> crates) {
        addCrateNamesFromFns(m.fns, crates);
        for (StructDecl st : m.structs) {
            addCrateNamesFromFns(st.methods, crates);
        }
        for (ClassDecl c : m.classes) {
            addCrateNamesFromFns(c.methods, crates);
            for (NestedImpl ti : c.traitImpls) {
                addCrateNamesFromFns(ti.methods, crates);
            }
        }
        for (ImplDecl im : m.impls) {
            addCrateNamesFromFns(im.methods, crates);
        }
        for (ModDecl nested : m.mods) {
            addCrateNamesFromMod(nested, crates);
        }
    }

    private static void addCrateNamesFromFns(List<FnDecl> fns, Set<String> crates) {
        for (FnDecl fn : fns) {
            addCrateNamesFromStmts(fn.body, crates);
        }
    }

    private static void addCrateNamesFromStmts(List<Stmt> stmts, Set<String> crates) {
        for (Stmt s : stmts) {
            addCrateNamesFromExpr(s.expr, crates);
            addCrateNamesFromExpr(s.target, crates);
            addCrateNamesFromStmts(s.body, crates);
            addCrateNamesFromStmts(s.elseBody, crates);
            for (MatchArm arm : s.arms) {
                addCrateNamesFromStmts(arm.body, crates);
            }
        }
    }

    private static void addCrateNamesFromExpr(Expr e, Set<String> crates) {
        if (e == null) {
            return;
        }
        if (e.kind == Expr.Kind.MethodCall && !e.kids.isEmpty()) {
            Expr recv = e.kids.getFirst();
            if (recv.kind == Expr.Kind.Var && recv.text != null && !recv.text.isEmpty()) {
                crates.add(recv.text);
            }
        }
        for (Expr k : e.kids) {
            addCrateNamesFromExpr(k, crates);
        }
        if (e.lambda != null) {
            addCrateNamesFromStmts(e.lambda.body, crates);
        }
    }

    private static void loadCrateParams(String crate, String fromFile, Map<String, List<String>> fnParams) {
        for (Path file : Stdlib.filesForCrate(crate, fromFile)) {
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            Program program;
            try {
                program = Parser.parseSource(src, file.toString(), new ArrayList<>());
            } catch (RuntimeException ex) {
                continue;
            }
            collectCrateItems(program, fnParams);
        }
    }

    private static void collectCrateItems(Program program, Map<String, List<String>> fnParams) {
        for (FnDecl fn : program.fns) {
            putParamsIfAbsent(fnParams, fn.name, fn.params);
        }
        for (StructDecl st : program.structs) {
            for (FnDecl m : st.methods) {
                putParamsIfAbsent(fnParams, m.name, m.params);
            }
        }
        for (ClassDecl c : program.classes) {
            for (FnDecl m : c.methods) {
                putParamsIfAbsent(fnParams, m.name, m.params);
            }
        }
        for (ImplDecl im : program.impls) {
            for (FnDecl m : im.methods) {
                putParamsIfAbsent(fnParams, m.name, m.params);
            }
        }
        for (ModDecl m : program.mods) {
            collectCrateMod(m, fnParams);
        }
    }

    private static void collectCrateMod(ModDecl mod, Map<String, List<String>> fnParams) {
        for (FnDecl fn : mod.fns) {
            putParamsIfAbsent(fnParams, fn.name, fn.params);
        }
        for (ModDecl nested : mod.mods) {
            collectCrateMod(nested, fnParams);
        }
    }

    private static void putParamsIfAbsent(Map<String, List<String>> fnParams, String name, List<String> params) {
        if (name == null || name.isEmpty() || fnParams.containsKey(name)) {
            return;
        }
        putParams(fnParams, name, params);
    }

    private static void walkItems(Program program, Map<String, List<String>> fnParams) {
        for (FnDecl fn : program.fns) {
            putParams(fnParams, fn.name, fn.params);
        }
        for (SignalDecl s : program.signals) {
            putParams(fnParams, s.name, s.params);
        }
        for (StructDecl st : program.structs) {
            for (FnDecl m : st.methods) {
                putParams(fnParams, m.name, m.params);
            }
            for (SignalDecl s : st.signals) {
                putParams(fnParams, s.name, s.params);
            }
        }
        for (ClassDecl c : program.classes) {
            for (FnDecl m : c.methods) {
                putParams(fnParams, m.name, m.params);
            }
            for (NestedImpl ti : c.traitImpls) {
                for (FnDecl m : ti.methods) {
                    putParams(fnParams, m.name, m.params);
                }
            }
            for (SignalDecl s : c.signals) {
                putParams(fnParams, s.name, s.params);
            }
        }
        for (TraitDecl t : program.traits) {
            for (TraitMethod m : t.methods) {
                putParams(fnParams, m.name, m.params);
            }
            for (SignalDecl s : t.signals) {
                putParams(fnParams, s.name, s.params);
            }
        }
        for (ImplDecl im : program.impls) {
            for (FnDecl m : im.methods) {
                putParams(fnParams, m.name, m.params);
            }
        }
    }

    private static void walkModItems(ModDecl mod, Map<String, List<String>> fnParams) {
        for (FnDecl fn : mod.fns) {
            putParams(fnParams, fn.name, fn.params);
        }
        for (SignalDecl s : mod.signals) {
            putParams(fnParams, s.name, s.params);
        }
        for (StructDecl st : mod.structs) {
            for (FnDecl m : st.methods) {
                putParams(fnParams, m.name, m.params);
            }
        }
        for (ClassDecl c : mod.classes) {
            for (FnDecl m : c.methods) {
                putParams(fnParams, m.name, m.params);
            }
            for (NestedImpl ti : c.traitImpls) {
                for (FnDecl m : ti.methods) {
                    putParams(fnParams, m.name, m.params);
                }
            }
        }
        for (TraitDecl t : mod.traits) {
            for (TraitMethod m : t.methods) {
                putParams(fnParams, m.name, m.params);
            }
        }
        for (ImplDecl im : mod.impls) {
            for (FnDecl m : im.methods) {
                putParams(fnParams, m.name, m.params);
            }
        }
        for (ModDecl nested : mod.mods) {
            walkModItems(nested, fnParams);
        }
    }

    private static void putParams(Map<String, List<String>> fnParams, String name, List<String> params) {
        if (name == null || name.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (String p : params) {
            String n = paramName(p);
            if (n.isEmpty() || n.equals("self") || n.equals("...")) {
                continue;
            }
            names.add(n);
        }
        if (!names.isEmpty()) {
            fnParams.put(name, names);
        }
    }

    private static String paramName(String p) {
        if (p == null) {
            return "";
        }
        int c = p.indexOf(':');
        String name = c < 0 ? p : p.substring(0, c);
        return name.trim();
    }

    private static void walkProgram(Program program, Map<String, List<String>> fnParams, List<Hint> hints) {
        walkFns(program.fns, fnParams, hints);
        for (StructDecl st : program.structs) {
            walkFns(st.methods, fnParams, hints);
        }
        for (ClassDecl c : program.classes) {
            walkFns(c.methods, fnParams, hints);
            for (NestedImpl ti : c.traitImpls) {
                walkFns(ti.methods, fnParams, hints);
            }
        }
        for (ImplDecl im : program.impls) {
            walkFns(im.methods, fnParams, hints);
        }
        for (ModDecl m : program.mods) {
            walkMod(m, fnParams, hints);
        }
    }

    private static void walkMod(ModDecl m, Map<String, List<String>> fnParams, List<Hint> hints) {
        walkFns(m.fns, fnParams, hints);
        for (StructDecl st : m.structs) {
            walkFns(st.methods, fnParams, hints);
        }
        for (ClassDecl c : m.classes) {
            walkFns(c.methods, fnParams, hints);
            for (NestedImpl ti : c.traitImpls) {
                walkFns(ti.methods, fnParams, hints);
            }
        }
        for (ImplDecl im : m.impls) {
            walkFns(im.methods, fnParams, hints);
        }
        for (ModDecl nested : m.mods) {
            walkMod(nested, fnParams, hints);
        }
    }

    private static void walkFns(List<FnDecl> fns, Map<String, List<String>> fnParams, List<Hint> hints) {
        for (FnDecl fn : fns) {
            walkStmts(fn.body, fnParams, hints);
        }
    }

    private static void walkStmts(List<Stmt> stmts, Map<String, List<String>> fnParams, List<Hint> hints) {
        for (Stmt s : stmts) {
            if (s.kind == Stmt.Kind.Comment) {
                continue;
            }
            if ((s.kind == Stmt.Kind.Var || s.kind == Stmt.Kind.Const) && s.typeName.isEmpty()) {
                String ty = guessExprType(s.expr);
                if (!ty.isEmpty()) {
                    hints.add(new Hint(Kind.Type, ": " + ty, s.line, s.col + s.name.length()));
                }
            }
            walkExpr(s.expr, fnParams, hints);
            walkExpr(s.target, fnParams, hints);
            walkStmts(s.body, fnParams, hints);
            walkStmts(s.elseBody, fnParams, hints);
            for (MatchArm arm : s.arms) {
                walkStmts(arm.body, fnParams, hints);
            }
        }
    }

    private static void walkExpr(Expr e, Map<String, List<String>> fnParams, List<Hint> hints) {
        if (e == null) {
            return;
        }
        if (e.kind == Expr.Kind.Call) {
            String name = e.text;
            int start = 0;
            if (name.isEmpty() && !e.kids.isEmpty()) {
                Expr callee = e.kids.getFirst();
                if (callee.kind == Expr.Kind.Var || callee.kind == Expr.Kind.Member) {
                    name = callee.text;
                }
                start = 1;
            }
            for (int i = start; i < e.kids.size(); i++) {
                pushParam(fnParams, name, i - start, e.kids.get(i), hints);
            }
        } else if (e.kind == Expr.Kind.MethodCall) {
            for (int i = 1; i < e.kids.size(); i++) {
                pushParam(fnParams, e.text, i - 1, e.kids.get(i), hints);
            }
        }
        for (Expr k : e.kids) {
            walkExpr(k, fnParams, hints);
        }
        if (e.lambda != null) {
            walkStmts(e.lambda.body, fnParams, hints);
        }
    }

    private static void pushParam(
            Map<String, List<String>> fnParams,
            String fn,
            int argIndex,
            Expr arg,
            List<Hint> hints
    ) {
        if (fn == null || arg == null) {
            return;
        }
        List<String> params = fnParams.get(fn);
        if (params == null || argIndex >= params.size()) {
            return;
        }
        String name = params.get(argIndex);
        if (name.isEmpty() || name.equals("...")) {
            return;
        }
        hints.add(new Hint(Kind.Param, name + ":", arg.line, arg.col));
    }

    private static String guessExprType(Expr e) {
        if (e == null) {
            return "";
        }
        return switch (e.kind) {
            case Int -> "Int";
            case Float -> "Float";
            case String -> "String";
            case Bool -> "Bool";
            default -> "";
        };
    }
}

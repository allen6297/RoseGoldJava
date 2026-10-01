package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Hover {

    public static final class Info {
        public final String name;
        public final String kind;
        public final String signature;
        public final String doc;
        public final boolean deprecated;
        public final String crate;
        public final int start;
        public final int end;

        Info(
                String name,
                String kind,
                String signature,
                String doc,
                boolean deprecated,
                String crate,
                int start,
                int end
        ) {
            this.name = name;
            this.kind = kind;
            this.signature = signature;
            this.doc = doc == null ? "" : doc;
            this.deprecated = deprecated;
            this.crate = crate == null ? "" : crate;
            this.start = start;
            this.end = end;
        }
    }

    private static final Set<String> HOST = Set.of("checks", "process");

    private static final String[] TYPES = {
            "Int", "Float", "String", "Bool", "Void", "Array", "Map", "Range",
            "UUID", "Vec2", "Vec3", "Window"
    };

    private static final String[][] BUILTINS = {
            {"print", "print(...)"},
            {"assert", "assert(cond)"},
            {"len", "len(xs) — Array, String, or Map"},
            {"argv", "argv(i) — script path at 0"},
            {"argv_len", "argv_len() — argc"},
    };

    private Hover() {
    }

    public static Info at(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        try {
            Idents.Ident ident = Idents.at(source, offset);
            if (ident == null) {
                return null;
            }
            if (!ident.qualifier.isEmpty() && HOST.contains(ident.qualifier)) {
                return moduleMember(source, path, ident);
            }
            Info crate = crateInfo(source, path, ident, offset);
            if (crate != null) {
                return crate;
            }
            if (ident.qualifier.isEmpty()) {
                for (String[] b : BUILTINS) {
                    if (b[0].equals(ident.name)) {
                        return info(ident, "function", b[1], "", false, "");
                    }
                }
            }
            Program program = parse(source, path);
            Info fromFile = fromProgram(program, ident, source, offset);
            if (fromFile != null) {
                return fromFile;
            }
            if (ident.qualifier.isEmpty()) {
                String exported = Stdlib.fromBindExported(program, ident.name);
                String bindCrate = Stdlib.fromBindCrate(program, ident.name);
                if (!exported.isEmpty() && !bindCrate.isEmpty()) {
                    Info from = nameInCrate(bindCrate, exported, path, ident);
                    if (from != null) {
                        return from;
                    }
                }
                String aliased = Stdlib.crateFromImports(program, ident.name);
                if (!aliased.isEmpty()) {
                    Info mod = crateModule(ident, aliased, path);
                    if (mod != null) {
                        return mod;
                    }
                }
                for (String t : TYPES) {
                    if (t.equals(ident.name)) {
                        return info(ident, "type", "type " + t, "", false, "");
                    }
                }
                if (ident.name.equals("std")) {
                    return info(ident, "module", "module std", "", false, "std");
                }
                if (HOST.contains(ident.name)) {
                    return info(ident, "module", "module " + ident.name, "host module", false, ident.name);
                }
                Types.StdlibExport ex = Stdlib.lookup(ident.name, path);
                if (ex != null && ex.crate != null && !ex.crate.isEmpty()) {
                    Info hit = nameInCrate(ex.crate, ident.name, path, ident);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Info moduleMember(String source, String path, Idents.Ident ident) {
        String signature = ident.qualifier + "." + ident.name;
        String kind = "function";
        for (Completions.Item item : Completions.suggest(source, path, ident.start)) {
            if (item.label.equals(ident.name)) {
                if (item.detail != null && !item.detail.isEmpty()) {
                    signature = item.detail;
                }
                kind = item.kind;
                break;
            }
        }
        return info(ident, kind, signature, "", false, ident.qualifier);
    }

    private static Info crateInfo(String source, String path, Idents.Ident ident, int offset) {
        String prefix = linePrefix(source, offset);
        String fromCrate = Idents.fromImportCrate(prefix);
        if (!fromCrate.isEmpty()) {
            String look = ident.name;
            String exported = Stdlib.fromBindExported(parse(source, path), ident.name);
            if (!exported.isEmpty()) {
                look = exported;
            }
            return nameInCrate(fromCrate, look, path, ident);
        }
        if (Idents.afterImportOrFrom(prefix) && (ident.qualifier.isEmpty() || ident.qualifier.equals("std"))) {
            String name = ident.name;
            String aliased = Stdlib.crateFromImports(parse(source, path), ident.name);
            if (!aliased.isEmpty()) {
                name = aliased;
            }
            return crateModule(ident, name, path);
        }
        if (!ident.qualifier.isEmpty() && !HOST.contains(ident.qualifier)) {
            String crate = Stdlib.crateOf(ident.qualifier, source, path);
            Info member = nameInCrate(crate.isEmpty() ? ident.qualifier : crate, ident.name, path, ident);
            if (member != null) {
                return member;
            }
            if (ident.qualifier.equals("std") || crate.equals("std")) {
                return crateModule(ident, ident.name, path);
            }
        }
        return null;
    }

    private static Info crateModule(Idents.Ident ident, String name, String fromFile) {
        if (HOST.contains(name)) {
            return info(ident, "module", "module " + name, "host module", false, name);
        }
        List<Path> files = Stdlib.filesForCrate(name, fromFile);
        if (files.isEmpty()) {
            return null;
        }
        boolean stdlib = Stdlib.crateDir(name, fromFile) != null;
        String doc = stdlib ? "stdlib" : "project module";
        return info(ident, "module", "module " + name, doc, false, name);
    }

    private static Info nameInCrate(String crate, String name, String fromFile, Idents.Ident at) {
        Idents.Ident look = new Idents.Ident();
        look.name = name;
        for (Path file : Stdlib.filesForCrate(crate, fromFile)) {
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            Info hit = fromProgram(parse(src, file.toString()), look, src, 0);
            if (hit != null) {
                return new Info(at.name, hit.kind, hit.signature, hit.doc, hit.deprecated, crate, at.start, at.end);
            }
        }
        return null;
    }

    private static String linePrefix(String source, int offset) {
        int start = offset;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return source.substring(start, Math.min(offset, source.length()));
    }

    private static Info fromProgram(Program program, Idents.Ident ident, String source, int offset) {
        int line = SourcePos.lineCol(source, offset)[0];
        if (!ident.qualifier.isEmpty()) {
            Info member = typeMember(program, ident, source, line);
            if (member != null) {
                return member;
            }
        }
        if (ident.qualifier.isEmpty()) {
            Info local = local(program, ident, line);
            if (local != null) {
                return local;
            }
        }
        final Info[] hit = {null};
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                if (fn.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = fnInfo(ident, fn, source);
                }
            }

            @Override
            void cls(ClassDecl c) {
                if (c.name.equals(ident.name) && hit[0] == null) {
                    StringBuilder sig = new StringBuilder();
                    if (c.isAbstract) {
                        sig.append("abstract ");
                    }
                    if (c.isFinal) {
                        sig.append("final ");
                    }
                    sig.append("class ").append(c.name);
                    if (c.parent != null && !c.parent.isEmpty()) {
                        sig.append(" extends ").append(c.parent);
                    }
                    hit[0] = info(ident, "class", sig.toString(), docsAbove(source, c.line), false, "");
                }
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
                for (ClassField f : c.fields) {
                    if (f.name.equals(ident.name) && hit[0] == null) {
                        String ty = f.type == null || f.type.isEmpty() ? "field" : f.type;
                        hit[0] = info(ident, "field", ty, "", false, "");
                    }
                }
            }

            @Override
            void str(StructDecl s) {
                if (s.name.equals(ident.name) && hit[0] == null) {
                    String kind = s.isData ? "data" : "struct";
                    hit[0] = info(ident, "struct", kind + " " + s.name, docsAbove(source, s.line), false, "");
                }
                for (FnDecl m : s.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
            }

            @Override
            void trait(TraitDecl t) {
                if (t.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = info(ident, "trait", "trait " + t.name, docsAbove(source, t.line), false, "");
                }
            }

            @Override
            void enm(EnumDecl e) {
                if (e.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = info(ident, "enum", "enum " + e.name, docsAbove(source, e.line), false, "");
                }
            }

            @Override
            void sig(SignalDecl s) {
                if (s.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = info(ident, "function", "signal " + s.name, docsAbove(source, s.line), false, "");
                }
            }

            @Override
            void mod(ModDecl m) {
                if (m.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = info(ident, "module", "mod " + m.name, docsAbove(source, m.line), false, "");
                }
            }
        });
        return hit[0];
    }

    private static Info typeMember(Program program, Idents.Ident ident, String source, int line) {
        String typeName = ident.qualifier;
        if (!hasNamedType(program, typeName)) {
            if (ident.qualifier.equals("self") || ident.qualifier.equals("super")) {
                ClassDecl cls = enclosingClass(program, line);
                typeName = cls == null ? "" : cls.name;
                if (ident.qualifier.equals("super") && cls != null) {
                    typeName = cls.parent;
                }
            } else {
                typeName = guessType(program, ident.qualifier, line);
            }
        }
        if (typeName == null || typeName.isEmpty()) {
            return namedMethod(program, ident, source);
        }
        String head = Types.typeHead(typeName);
        final Info[] hit = {null};
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                if (!c.name.equals(head)) {
                    return;
                }
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name)) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
                for (ClassField f : c.fields) {
                    if (f.name.equals(ident.name) && hit[0] == null) {
                        String ty = f.type == null || f.type.isEmpty() ? "field" : f.type;
                        hit[0] = info(ident, "field", ty, "", false, "");
                    }
                }
            }

            @Override
            void str(StructDecl s) {
                if (!s.name.equals(head)) {
                    return;
                }
                for (FnDecl m : s.methods) {
                    if (m.name.equals(ident.name)) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
            }
        });
        return hit[0] != null ? hit[0] : namedMethod(program, ident, source);
    }

    private static Info namedMethod(Program program, Idents.Ident ident, String source) {
        final Info[] hit = {null};
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                if (fn.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = fnInfo(ident, fn, source);
                }
            }

            @Override
            void cls(ClassDecl c) {
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
            }

            @Override
            void str(StructDecl s) {
                for (FnDecl m : s.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = fnInfo(ident, m, source);
                    }
                }
            }
        });
        return hit[0];
    }

    private static Info local(Program program, Idents.Ident ident, int line) {
        FnDecl fn = enclosingFn(program, line);
        if (fn == null) {
            return null;
        }
        for (int i = 0; i < fn.params.size(); i++) {
            if (fn.params.get(i).equals(ident.name)) {
                String ty = i < fn.paramTypes.size() ? fn.paramTypes.get(i) : "";
                String sig = ty.isEmpty() ? "param " + ident.name : ident.name + ": " + ty;
                return info(ident, "variable", sig, "", false, "");
            }
        }
        return local(fn.body, ident, line);
    }

    private static Info local(List<Stmt> stmts, Idents.Ident ident, int line) {
        Info found = null;
        for (Stmt s : stmts) {
            if (s.line > line) {
                continue;
            }
            if ((s.kind == Stmt.Kind.Var || s.kind == Stmt.Kind.Const || s.kind == Stmt.Kind.For)
                    && ident.name.equals(s.name)) {
                String kind = s.kind == Stmt.Kind.Const ? "const" : "var";
                String sig = kind + " " + s.name;
                if (s.typeName != null && !s.typeName.isEmpty()) {
                    sig += ": " + s.typeName;
                }
                found = info(ident, "variable", sig, "", false, "");
            }
            Info inner = local(s.body, ident, line);
            if (inner != null) {
                found = inner;
            }
            inner = local(s.elseBody, ident, line);
            if (inner != null) {
                found = inner;
            }
            for (MatchArm arm : s.arms) {
                inner = local(arm.body, ident, line);
                if (inner != null) {
                    found = inner;
                }
            }
        }
        return found;
    }

    private static Info fnInfo(Idents.Ident ident, FnDecl fn, String source) {
        StringBuilder sig = new StringBuilder("fn ").append(fn.name).append('(');
        boolean first = true;
        for (int i = 0; i < fn.params.size(); i++) {
            if (fn.params.get(i).equals("self")) {
                continue;
            }
            if (!first) {
                sig.append(", ");
            }
            first = false;
            sig.append(fn.params.get(i));
            if (i < fn.paramTypes.size() && !fn.paramTypes.get(i).isEmpty()) {
                sig.append(": ").append(fn.paramTypes.get(i));
            }
        }
        sig.append(')');
        if (fn.returnType != null && !fn.returnType.isEmpty()) {
            sig.append(": ").append(fn.returnType);
        }
        if (fn.throwsEx && !sig.toString().contains("throws")) {
            sig.append(" throws");
        }
        return info(ident, "function", sig.toString(), docsAbove(source, fn.line), fn.isDeprecated, "");
    }

    private static Info info(Idents.Ident ident, String kind, String signature, String doc, boolean deprecated, String crate) {
        return new Info(ident.name, kind, signature, doc, deprecated, crate, ident.start, ident.end);
    }

    private static Program parse(String source, String path) {
        try {
            return Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return new Program();
        }
    }

    static String docsAbove(String source, int line1) {
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return "";
        }
        int i = 0;
        while (i < tokens.size() && tokens.get(i).kind != Tok.Eof) {
            Token t = tokens.get(i);
            if (t.line >= line1 && t.kind != Tok.LineComment && t.kind != Tok.BlockComment) {
                break;
            }
            i++;
        }
        List<String> docs = new ArrayList<>();
        for (int j = i - 1; j >= 0; j--) {
            Token t = tokens.get(j);
            if (t.kind == Tok.LineComment || t.kind == Tok.BlockComment) {
                String text = stripComment(t);
                if (!text.isEmpty()) {
                    docs.addFirst(text);
                }
                continue;
            }
            if (t.kind == Tok.Identifier && j > 0 && tokens.get(j - 1).kind == Tok.At) {
                j--;
                continue;
            }
            if (t.kind == Tok.At) {
                continue;
            }
            break;
        }
        return String.join("\n", docs);
    }

    private static String stripComment(Token t) {
        String s = t.text == null ? "" : t.text;
        if (t.kind == Tok.BlockComment) {
            if (s.startsWith("/#")) {
                s = s.substring(2);
            }
            if (s.endsWith("#/")) {
                s = s.substring(0, s.length() - 2);
            }
            return trimCommentBlock(s);
        }
        if (s.startsWith("///")) {
            s = s.substring(3);
        } else if (s.startsWith("//")) {
            s = s.substring(2);
        } else if (s.startsWith("##")) {
            s = s.substring(2);
        } else if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return s.trim();
    }

    private static String trimCommentBlock(String s) {
        String[] lines = s.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() && kept.isEmpty()) {
                continue;
            }
            kept.add(trimmed);
        }
        while (!kept.isEmpty() && kept.getLast().isEmpty()) {
            kept.removeLast();
        }
        return String.join("\n", kept);
    }

    private abstract static class Walk {
        void fn(FnDecl fn) {
        }

        void cls(ClassDecl c) {
        }

        void str(StructDecl s) {
        }

        void trait(TraitDecl t) {
        }

        void enm(EnumDecl e) {
        }

        void sig(SignalDecl s) {
        }

        void mod(ModDecl m) {
        }
    }

    private static void walk(Program program, Walk w) {
        walkMod(program.fns, program.structs, program.classes, program.traits, program.enums,
                program.signals, program.mods, w);
    }

    private static void walkMod(
            List<FnDecl> fns,
            List<StructDecl> structs,
            List<ClassDecl> classes,
            List<TraitDecl> traits,
            List<EnumDecl> enums,
            List<SignalDecl> signals,
            List<ModDecl> mods,
            Walk w
    ) {
        for (FnDecl fn : fns) {
            w.fn(fn);
        }
        for (StructDecl s : structs) {
            w.str(s);
        }
        for (ClassDecl c : classes) {
            w.cls(c);
        }
        for (TraitDecl t : traits) {
            w.trait(t);
        }
        for (EnumDecl e : enums) {
            w.enm(e);
        }
        for (SignalDecl s : signals) {
            w.sig(s);
        }
        for (ModDecl m : mods) {
            w.mod(m);
            walkMod(m.fns, m.structs, m.classes, m.traits, m.enums, m.signals, m.mods, w);
        }
    }

    private static boolean hasNamedType(Program program, String name) {
        final boolean[] hit = {false};
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                if (c.name.equals(name)) {
                    hit[0] = true;
                }
            }

            @Override
            void str(StructDecl s) {
                if (s.name.equals(name)) {
                    hit[0] = true;
                }
            }
        });
        return hit[0];
    }

    private static FnDecl enclosingFn(Program program, int line) {
        final FnDecl[] best = {null};
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                consider(fn);
            }

            @Override
            void cls(ClassDecl c) {
                for (FnDecl m : c.methods) {
                    consider(m);
                }
            }

            @Override
            void str(StructDecl s) {
                for (FnDecl m : s.methods) {
                    consider(m);
                }
            }

            void consider(FnDecl fn) {
                if (fn.line <= line && (best[0] == null || fn.line >= best[0].line)) {
                    best[0] = fn;
                }
            }
        });
        return best[0];
    }

    private static ClassDecl enclosingClass(Program program, int line) {
        FnDecl fn = enclosingFn(program, line);
        if (fn == null) {
            return null;
        }
        final ClassDecl[] found = {null};
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                for (FnDecl m : c.methods) {
                    if (m == fn) {
                        found[0] = c;
                        break;
                    }
                }
            }
        });
        return found[0];
    }

    private static String guessType(Program program, String recv, int line) {
        FnDecl fn = enclosingFn(program, line);
        if (fn == null) {
            return "";
        }
        for (int i = 0; i < fn.params.size(); i++) {
            if (fn.params.get(i).equals(recv) && i < fn.paramTypes.size()) {
                return Types.typeHead(fn.paramTypes.get(i));
            }
        }
        return guessType(fn.body, recv, line);
    }

    private static String guessType(List<Stmt> stmts, String recv, int line) {
        String found = "";
        for (Stmt s : stmts) {
            if (s.line > line) {
                continue;
            }
            if ((s.kind == Stmt.Kind.Var || s.kind == Stmt.Kind.Const) && recv.equals(s.name)) {
                if (s.typeName != null && !s.typeName.isEmpty()) {
                    found = Types.typeHead(s.typeName);
                } else if (s.expr != null && s.expr.kind == Expr.Kind.StructLit && s.expr.text != null) {
                    found = Types.typeHead(s.expr.text);
                }
            }
            String inner = guessType(s.body, recv, line);
            if (!inner.isEmpty()) {
                found = inner;
            }
            inner = guessType(s.elseBody, recv, line);
            if (!inner.isEmpty()) {
                found = inner;
            }
            for (MatchArm arm : s.arms) {
                inner = guessType(arm.body, recv, line);
                if (!inner.isEmpty()) {
                    found = inner;
                }
            }
        }
        return found;
    }
}

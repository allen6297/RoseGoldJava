package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Goto {

    public static final class Loc {
        public final String path;
        public final String name;
        public final int line;
        public final int col;
        public final int start;
        public final int end;

        Loc(String path, String name, int line, int col, int start, int end) {
            this.path = path == null ? "" : path;
            this.name = name;
            this.line = line;
            this.col = col;
            this.start = start;
            this.end = end;
        }
    }

    private static final Set<String> HOST = Set.of("checks", "process");

    private Goto() {
    }

    public static Loc at(String source, String path, int offset) {
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
            Loc external = external(source, path, ident, offset);
            if (external != null) {
                return external;
            }
            if (!ident.qualifier.isEmpty() && HOST.contains(ident.qualifier)) {
                return null;
            }
            Program program = parse(source, path);
            Loc hit = fromProgram(program, ident, source, path, offset);
            if (hit != null) {
                return hit;
            }
            if (ident.qualifier.isEmpty()) {
                String exported = Stdlib.fromBindExported(program, ident.name);
                String crate = Stdlib.fromBindCrate(program, ident.name);
                if (!exported.isEmpty() && !crate.isEmpty()) {
                    Loc from = nameInCrate(crate, exported, path);
                    if (from != null) {
                        return from;
                    }
                }
                String aliased = Stdlib.crateFromImports(program, ident.name);
                if (!aliased.isEmpty()) {
                    return crateRoot(aliased, path);
                }
                return exportedName(ident.name, path);
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Loc external(String source, String path, Idents.Ident ident, int offset) {
        String prefix = linePrefix(source, offset);
        String fromCrate = Idents.fromImportCrate(prefix);
        if (!fromCrate.isEmpty()) {
            String look = ident.name;
            String exported = Stdlib.fromBindExported(parse(source, path), ident.name);
            if (!exported.isEmpty()) {
                look = exported;
            }
            return nameInCrate(fromCrate, look, path);
        }
        if (Idents.afterImportOrFrom(prefix)) {
            if (ident.qualifier.equals("std") || ident.qualifier.isEmpty()) {
                String name = ident.name;
                String aliased = Stdlib.crateFromImports(parse(source, path), ident.name);
                if (!aliased.isEmpty()) {
                    name = aliased;
                }
                return crateRoot(name, path);
            }
        }
        if (!ident.qualifier.isEmpty() && !HOST.contains(ident.qualifier)) {
            String crate = Stdlib.crateOf(ident.qualifier, source, path);
            Loc member = nameInCrate(crate.isEmpty() ? ident.qualifier : crate, ident.name, path);
            if (member != null) {
                return member;
            }
            if (ident.qualifier.equals("std") || crate.equals("std")) {
                return crateRoot(ident.name, path);
            }
        }
        return null;
    }

    private static Loc exportedName(String name, String path) {
        Types.StdlibExport ex = Stdlib.lookup(name, path);
        if (ex == null || ex.crate == null || ex.crate.isEmpty()) {
            return null;
        }
        return nameInCrate(ex.crate, name, path);
    }

    private static Loc crateRoot(String name, String fromFile) {
        List<Path> files = Stdlib.filesForCrate(name, fromFile);
        if (files.isEmpty()) {
            return null;
        }
        Path lib = null;
        for (Path file : files) {
            if (file.getFileName().toString().equals("lib.rg")) {
                lib = file;
                break;
            }
        }
        if (lib == null) {
            lib = files.getFirst();
        }
        return fileLoc(lib, name);
    }

    private static Loc nameInCrate(String crate, String name, String fromFile) {
        Idents.Ident ident = new Idents.Ident();
        ident.name = name;
        for (Path file : Stdlib.filesForCrate(crate, fromFile)) {
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            Loc hit = fromProgram(parse(src, file.toString()), ident, src, file.toString(), 0);
            if (hit != null) {
                return hit;
            }
        }
        return crateRoot(crate, fromFile);
    }

    private static Loc fileLoc(Path file, String name) {
        String src;
        try {
            src = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
        String path = file.toAbsolutePath().normalize().toString();
        Token t = Idents.nameToken(src, 1, name);
        if (t != null) {
            return loc(path, name, src, 1, t.col);
        }
        return loc(path, name, src, 1, 1);
    }

    private static String linePrefix(String source, int offset) {
        int start = offset;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return source.substring(start, Math.min(offset, source.length()));
    }

    private static Loc fromProgram(Program program, Idents.Ident ident, String source, String path, int offset) {
        int line = SourcePos.lineCol(source, offset)[0];
        if (!ident.qualifier.isEmpty()) {
            Loc member = typeMember(program, ident, source, path, line);
            if (member != null) {
                return member;
            }
        }
        if (ident.qualifier.isEmpty()) {
            Loc local = local(program, ident, source, path, line);
            if (local != null) {
                return local;
            }
        }
        final Loc[] hit = {null};
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                if (fn.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, fn.line);
                }
            }

            @Override
            void cls(ClassDecl c) {
                if (c.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, c.line);
                }
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
                for (ClassField f : c.fields) {
                    if (f.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, f.line, f.col);
                    }
                }
            }

            @Override
            void str(StructDecl s) {
                if (s.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, s.line);
                }
                for (FnDecl m : s.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
            }

            @Override
            void trait(TraitDecl t) {
                if (t.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, t.line);
                }
            }

            @Override
            void enm(EnumDecl e) {
                if (e.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, e.line);
                }
            }

            @Override
            void sig(SignalDecl s) {
                if (s.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, s.line);
                }
            }

            @Override
            void mod(ModDecl m) {
                if (m.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, m.line);
                }
            }
        });
        return hit[0];
    }

    private static Loc typeMember(Program program, Idents.Ident ident, String source, String path, int line) {
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
            return namedMethod(program, ident, source, path);
        }
        String head = Types.typeHead(typeName);
        final Loc[] hit = {null};
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                if (!c.name.equals(head)) {
                    return;
                }
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name)) {
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
                for (ClassField f : c.fields) {
                    if (f.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, f.line, f.col);
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
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
            }
        });
        return hit[0] != null ? hit[0] : namedMethod(program, ident, source, path);
    }

    private static Loc namedMethod(Program program, Idents.Ident ident, String source, String path) {
        final Loc[] hit = {null};
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                if (fn.name.equals(ident.name) && hit[0] == null) {
                    hit[0] = loc(path, ident.name, source, fn.line);
                }
            }

            @Override
            void cls(ClassDecl c) {
                for (FnDecl m : c.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
            }

            @Override
            void str(StructDecl s) {
                for (FnDecl m : s.methods) {
                    if (m.name.equals(ident.name) && hit[0] == null) {
                        hit[0] = loc(path, ident.name, source, m.line);
                    }
                }
            }
        });
        return hit[0];
    }

    private static Loc local(Program program, Idents.Ident ident, String source, String path, int line) {
        FnDecl fn = enclosingFn(program, line);
        if (fn == null) {
            return null;
        }
        for (String param : fn.params) {
            if (param.equals(ident.name) && !param.equals("self")) {
                return loc(path, ident.name, source, fn.line);
            }
        }
        return local(fn.body, ident, source, path, line);
    }

    private static Loc local(List<Stmt> stmts, Idents.Ident ident, String source, String path, int line) {
        Loc found = null;
        for (Stmt s : stmts) {
            if (s.line > line) {
                continue;
            }
            if ((s.kind == Stmt.Kind.Var || s.kind == Stmt.Kind.Const || s.kind == Stmt.Kind.For)
                    && ident.name.equals(s.name)) {
                found = loc(path, ident.name, source, s.line, s.col);
            }
            Loc inner = local(s.body, ident, source, path, line);
            if (inner != null) {
                found = inner;
            }
            inner = local(s.elseBody, ident, source, path, line);
            if (inner != null) {
                found = inner;
            }
            for (MatchArm arm : s.arms) {
                inner = local(arm.body, ident, source, path, line);
                if (inner != null) {
                    found = inner;
                }
            }
        }
        return found;
    }

    private static Loc loc(String path, String name, String source, int line) {
        Token t = Idents.nameToken(source, line, name);
        int col = t == null ? 1 : t.col;
        return loc(path, name, source, line, col);
    }

    private static Loc loc(String path, String name, String source, int line, int col) {
        int start = SourcePos.offset(source, line, col);
        return new Loc(path, name, line, col, start, start + name.length());
    }

    private static Program parse(String source, String path) {
        try {
            return Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return new Program();
        }
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

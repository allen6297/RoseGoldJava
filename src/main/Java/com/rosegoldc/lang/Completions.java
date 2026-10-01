package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Completions {

    public static final class Item {
        public final String label;
        public final String kind;
        public final String detail;

        Item(String label, String kind, String detail) {
            this.label = label;
            this.kind = kind;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final String[] KEYWORDS = {
            "fn", "var", "const", "struct", "data", "class", "trait", "extends",
            "enum", "mod", "impl", "pub", "private", "protected", "abstract", "final",
            "match", "switch", "for", "in", "super", "signal", "import", "from", "as",
            "return", "pass", "break", "continue", "if", "elif", "else", "while",
            "self", "true", "false", "try", "do", "throws", "throw", "catch",
            "async", "await", "spawn"
    };

    private static final String[] TYPES = {
            "Int", "Float", "String", "Bool", "Void", "Array", "Map", "Range",
            "UUID", "Vec2", "Vec3", "Window", "Color"
    };

    private static final String[][] COLOR_VARIANTS = {
            {"Black", "Color.Black"},
            {"Red", "Color.Red"},
            {"Green", "Color.Green"},
            {"Yellow", "Color.Yellow"},
            {"Blue", "Color.Blue"},
            {"Magenta", "Color.Magenta"},
            {"Cyan", "Color.Cyan"},
            {"White", "Color.White"},
            {"Rgb", "Color.Rgb(r, g, b)"},
            {"Argb", "Color.Argb(a, r, g, b)"},
    };

    private static final String[][] BUILTINS = {
            {"print", "print(...)"},
            {"assert", "assert(cond)"},
            {"len", "len(xs) — Array, String, or Map"},
            {"checks.eq", "checks.eq(a, b)"},
            {"checks.neq", "checks.neq(a, b)"},
            {"checks.eq_string", "checks.eq_string(a, b)"},
            {"checks.that", "checks.that(cond)"},
            {"argv", "argv(i) — script path at 0"},
            {"argv_len", "argv_len() — argc"},
    };

    private Completions() {
    }

    public static List<Item> suggest(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        List<Item> out = new ArrayList<>();
        try {
            if (inCommentOrString(source, path, offset)) {
                return List.of();
            }
            String prefix = linePrefix(source, offset);
            if (afterAt(prefix)) {
                add(out, "test", "keyword", "@test");
                add(out, "deprecated", "keyword", "@deprecated");
                add(out, "constexpr", "keyword", "@constexpr");
                add(out, "ufcs", "keyword", "@ufcs");
                add(out, "optional", "keyword", "@optional");
                return unique(out);
            }
            String fromCrate = Idents.fromImportCrate(prefix);
            if (!fromCrate.isEmpty()) {
                addFromImportNames(out, fromCrate, path);
                return unique(out);
            }
            if (Idents.afterImportOrFrom(prefix)) {
                String recv = memberRecv(prefix);
                if (recv.equals("std")) {
                    addStdlibChildren(out);
                } else {
                    addImportCrates(out, path);
                }
                return unique(out);
            }
            String recv = memberRecv(prefix);
            Program program = parse(source, path);
            if (!recv.isEmpty()) {
                addMember(out, recv, program, source, path, offset);
                return unique(out);
            }
            if (afterColonType(prefix)) {
                addTypes(out, path);
                addFileTypes(out, program);
                return unique(out);
            }
            for (String kw : KEYWORDS) {
                add(out, kw, "keyword", "keyword");
            }
            for (String t : TYPES) {
                add(out, t, "type", "type");
            }
            for (String[] b : BUILTINS) {
                add(out, b[0], "function", b[1]);
            }
            add(out, "std", "module", "stdlib");
            addFileSymbols(out, program);
            addLocals(out, program, SourcePos.lineCol(source, offset)[0]);
        } catch (RuntimeException ignored) {
            return unique(out);
        }
        return unique(out);
    }

    private static Program parse(String source, String path) {
        try {
            return Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return new Program();
        }
    }

    private static void addMember(List<Item> out, String recv, Program program, String source, String path, int offset) {
        String crate = Stdlib.crateOf(recv, program, path);
        String key = crate.isEmpty() ? recv : crate;
        switch (key) {
            case "std" -> {
                add(out, "math", "module", "std.math");
                add(out, "str", "module", "std.str");
                add(out, "io", "module", "std.io");
                add(out, "vec", "module", "std.vec");
                add(out, "time", "module", "std.time");
                add(out, "path", "module", "std.path");
                add(out, "json", "module", "std.json");
                add(out, "regex", "module", "std.regex");
                add(out, "ui", "module", "std.ui");
                add(out, "v4", "function", "std.v4()");
                add(out, "nil", "function", "std.nil()");
                add(out, "parse", "function", "std.parse(s) throws");
                add(out, "valid", "function", "std.valid(s)");
                add(out, "UUID", "struct", "data UUID");
            }
            case "math" -> addRows(out, "method", new String[][]{
                    {"abs", "math.abs(n)"}, {"sign", "math.sign(n)"}, {"min", "math.min(a, b)"},
                    {"max", "math.max(a, b)"}, {"clamp", "math.clamp(v, lo, hi)"},
                    {"gcd", "math.gcd(a, b)"}, {"pow", "math.pow(a, b) — Int"},
                    {"rand_int", "math.rand_int(n)"}, {"sin", "math.sin(n)"}, {"cos", "math.cos(n)"},
                    {"atan2", "math.atan2(y, x)"}, {"sqrt", "math.sqrt(n)"},
                    {"powf", "math.powf(a, b) — Float"}, {"to_int", "math.to_int(n)"},
                    {"to_float", "math.to_float(n)"}, {"floor", "math.floor(n)"},
                    {"ceil", "math.ceil(n)"}, {"random", "math.random() — 0..1"},
                    {"lerp", "math.lerp(a, b, t)"},
                    {"move_toward", "math.move_toward(current, target, delta)"},
            });
            case "str" -> addRows(out, "method", new String[][]{
                    {"contains", "str.contains(s, sub)"}, {"starts_with", "str.starts_with(s, prefix)"},
                    {"ends_with", "str.ends_with(s, suffix)"}, {"length", "str.length(s)"},
                    {"is_empty", "str.is_empty(s)"}, {"repeat", "str.repeat(s, n)"},
                    {"upper", "str.upper(s)"}, {"lower", "str.lower(s)"}, {"trim", "str.trim(s)"},
                    {"slice", "str.slice(s, start, end)"}, {"split", "str.split(s, sep)"},
                    {"replace", "str.replace(s, old, with)"}, {"find", "str.find(s, sub) — index or -1"},
            });
            case "io" -> addRows(out, "method", new String[][]{
                    {"exists", "io.exists(path)"}, {"remove", "io.remove(path)"},
                    {"read_text", "io.read_text(path) throws"}, {"read_lines", "io.read_lines(path) throws"},
                    {"write_text", "io.write_text(path, content) throws"},
            });
            case "time" -> addRows(out, "method", new String[][]{
                    {"now", "time.now()"}, {"sleep", "time.sleep(ms)"},
            });
            case "path" -> addRows(out, "method", new String[][]{
                    {"join", "path.join(a, b)"}, {"parent", "path.parent(p)"}, {"stem", "path.stem(p)"},
            });
            case "json" -> addRows(out, "method", new String[][]{
                    {"parse", "json.parse(s) throws"}, {"stringify", "json.stringify(v)"},
                    {"valid", "json.valid(s)"},
            });
            case "regex" -> addRows(out, "method", new String[][]{
                    {"valid", "regex.valid(pattern)"},
                    {"is_match", "regex.is_match(pattern, text) — search anywhere"},
                    {"find", "regex.find(pattern, text) — start index or -1"},
                    {"find_match", "regex.find_match(pattern, text) — matched text or \"\""},
                    {"captures", "regex.captures(pattern, text) — [full, g1, …] or []"},
                    {"findall", "regex.findall(pattern, text) — non-overlapping matches"},
                    {"replace", "regex.replace(pattern, text, with) — $1 backrefs"},
                    {"split", "regex.split(pattern, text)"},
            });
            case "vec" -> {
                add(out, "Vec2", "class", "class Vec2");
                add(out, "Vec3", "class", "class Vec3");
            }
            case "ui" -> {
                addRows(out, "function", new String[][]{
                        {"open", "ui.open(title, width, height) throws"},
                        {"open_hidden", "ui.open_hidden(title, width, height) throws"},
                        {"run", "ui.run()"}, {"count", "ui.count()"},
                        {"backend", "ui.backend()"}, {"platform", "ui.platform()"},
                        {"kind", "ui.kind()"}, {"font_height", "ui.font_height()"},
                        {"text_width", "ui.text_width(s)"}, {"rgb", "ui.rgb(r, g, b)"},
                        {"padding", "w.padding(n)"}, {"background", "w.background(color)"},
                        {"style", "w.style(Style { fill, pad })"},
                });
                add(out, "Color", "enum", "enum Color");
                add(out, "Window", "class", "class Window");
                add(out, "Label", "class", "class Label");
                add(out, "Button", "class", "class Button");
                add(out, "VStack", "class", "class VStack");
                add(out, "HStack", "class", "class HStack");
                add(out, "Style", "class", "class Style");
                add(out, "Widget", "trait", "trait Widget");
            }
            case "checks" -> addRows(out, "method", new String[][]{
                    {"eq", "checks.eq(a, b)"}, {"neq", "checks.neq(a, b)"},
                    {"eq_string", "checks.eq_string(a, b)"}, {"that", "checks.that(cond)"},
            });
            case "process" -> addRows(out, "method", new String[][]{
                    {"argv", "process.argv(i)"}, {"argc", "process.argc()"},
            });
            default -> addUnknownRecv(out, recv, program, source, offset);
        }
        if (!key.equals("self") && !key.equals("super")) {
            addCrateMembers(out, key, path);
        }
    }

    private static void addCrateMembers(List<Item> out, String crate, String path) {
        for (Path file : Stdlib.filesForCrate(crate, path)) {
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            addFileSymbols(out, parse(src, file.toString()));
        }
    }

    private static void addUnknownRecv(List<Item> out, String recv, Program program, String source, int offset) {
        EnumDecl en = findEnum(program, recv);
        if (en != null) {
            addEnumVariants(out, en);
            addTypeMembers(out, program, en.name, true);
            return;
        }
        if (recv.equals("Color")) {
            addColorMembers(out);
            return;
        }
        if (hasSignal(program, recv)) {
            add(out, "connect", "method", recv + ".connect(fn)");
            add(out, "emit", "method", recv + ".emit()");
            add(out, "emit_deferred", "method", recv + ".emit_deferred()");
            add(out, "disconnect", "method", recv + ".disconnect(fn)");
            return;
        }
        int line = SourcePos.lineCol(source, offset)[0];
        String typeName = recv;
        if (!hasNamedType(program, recv)) {
            if (recv.equals("self") || recv.equals("super")) {
                ClassDecl cls = enclosingClass(program, line);
                typeName = cls == null ? "" : cls.name;
                if (recv.equals("super") && cls != null) {
                    typeName = cls.parent;
                }
            } else {
                typeName = guessType(program, recv, line);
            }
        }
        addTypeMembers(out, program, typeName, recv.equals("super"));
    }

    private static void addTypes(List<Item> out, String path) {
        for (String t : TYPES) {
            String detail = "type";
            Types.StdlibExport ex = Stdlib.lookup(t, path);
            if (ex != null && ex.crate != null && !ex.crate.isEmpty()) {
                detail = "crate " + ex.crate;
            }
            add(out, t, "type", detail);
        }
        for (Map.Entry<String, List<Types.StdlibExport>> kv : Stdlib.exportIndex(path).entrySet()) {
            for (Types.StdlibExport ex : kv.getValue()) {
                if ("fn".equals(ex.kind)) {
                    continue;
                }
                add(out, kv.getKey(), "type", "crate " + ex.crate);
                break;
            }
        }
    }

    private static void addFileTypes(List<Item> out, Program program) {
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                add(out, c.name, "class", "class");
            }

            @Override
            void str(StructDecl s) {
                add(out, s.name, "struct", s.isData ? "data" : "struct");
            }

            @Override
            void trait(TraitDecl t) {
                add(out, t.name, "trait", "trait");
            }

            @Override
            void enm(EnumDecl e) {
                add(out, e.name, "enum", "enum");
            }
        });
    }

    private static void addFileSymbols(List<Item> out, Program program) {
        walk(program, new Walk() {
            @Override
            void fn(FnDecl fn) {
                addFn(out, fn);
            }

            @Override
            void cls(ClassDecl c) {
                add(out, c.name, "class", "class");
                for (FnDecl m : c.methods) {
                    addFn(out, m);
                }
            }

            @Override
            void str(StructDecl s) {
                add(out, s.name, "struct", s.isData ? "data" : "struct");
                for (FnDecl m : s.methods) {
                    addFn(out, m);
                }
            }

            @Override
            void trait(TraitDecl t) {
                add(out, t.name, "trait", "trait");
            }

            @Override
            void enm(EnumDecl e) {
                add(out, e.name, "enum", "enum");
            }

            @Override
            void sig(SignalDecl s) {
                add(out, s.name, "function", "signal");
            }

            @Override
            void mod(ModDecl m) {
                add(out, m.name, "module", "mod");
            }
        });
    }

    private static void addFn(List<Item> out, FnDecl fn) {
        StringBuilder detail = new StringBuilder("fn ").append(fn.name).append('(');
        for (int i = 0; i < fn.params.size(); i++) {
            if (i > 0) {
                detail.append(", ");
            }
            detail.append(fn.params.get(i));
            if (i < fn.paramTypes.size() && !fn.paramTypes.get(i).isEmpty()) {
                detail.append(": ").append(fn.paramTypes.get(i));
            }
        }
        detail.append(')');
        if (fn.returnType != null && !fn.returnType.isEmpty()) {
            detail.append(": ").append(fn.returnType);
        }
        add(out, fn.name, "function", detail.toString());
    }

    private static void addLocals(List<Item> out, Program program, int line) {
        FnDecl fn = enclosingFn(program, line);
        if (fn == null) {
            return;
        }
        for (int i = 0; i < fn.params.size(); i++) {
            String name = fn.params.get(i);
            if (name.equals("self")) {
                continue;
            }
            String ty = i < fn.paramTypes.size() ? fn.paramTypes.get(i) : "";
            add(out, name, "variable", ty.isEmpty() ? "param" : ty);
        }
        collectLocals(fn.body, line, out);
    }

    private static void collectLocals(List<Stmt> stmts, int line, List<Item> out) {
        for (Stmt s : stmts) {
            if (s.line > line) {
                continue;
            }
            if ((s.kind == Stmt.Kind.Var || s.kind == Stmt.Kind.Const || s.kind == Stmt.Kind.For)
                    && s.name != null && !s.name.isEmpty()) {
                String detail = s.kind == Stmt.Kind.Const ? "const" : s.kind == Stmt.Kind.For ? "for" : "var";
                if (s.typeName != null && !s.typeName.isEmpty()) {
                    detail = s.typeName;
                }
                add(out, s.name, "variable", detail);
            }
            collectLocals(s.body, line, out);
            collectLocals(s.elseBody, line, out);
            for (MatchArm arm : s.arms) {
                for (String bind : arm.binds) {
                    add(out, bind, "variable", "match");
                }
                collectLocals(arm.body, line, out);
            }
        }
    }

    private static void addTypeMembers(List<Item> out, Program program, String typeName, boolean skipFields) {
        if (typeName == null || typeName.isEmpty()) {
            return;
        }
        String head = Types.typeHead(typeName);
        walk(program, new Walk() {
            @Override
            void cls(ClassDecl c) {
                if (!c.name.equals(head)) {
                    return;
                }
                if (!skipFields) {
                    for (ClassField f : c.fields) {
                        add(out, f.name, "field", f.type);
                    }
                }
                for (FnDecl m : c.methods) {
                    addFn(out, m);
                }
                for (SignalDecl s : c.signals) {
                    add(out, s.name, "function", "signal");
                }
                addTypeMembers(out, program, c.parent, true);
            }

            @Override
            void str(StructDecl s) {
                if (!s.name.equals(head)) {
                    return;
                }
                if (!skipFields) {
                    for (int i = 0; i < s.fields.size(); i++) {
                        String ty = i < s.fieldTypes.size() ? s.fieldTypes.get(i) : "";
                        add(out, s.fields.get(i), "field", ty);
                    }
                }
                for (FnDecl m : s.methods) {
                    addFn(out, m);
                }
            }
        });
        for (ImplDecl impl : program.impls) {
            if (head.equals(Types.typeHead(impl.typeName))) {
                for (FnDecl m : impl.methods) {
                    addFn(out, m);
                }
            }
        }
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

    private static boolean hasSignal(Program program, String name) {
        final boolean[] hit = {false};
        walk(program, new Walk() {
            @Override
            void sig(SignalDecl s) {
                if (s.name.equals(name)) {
                    hit[0] = true;
                }
            }
        });
        return hit[0];
    }

    private static void addEnumVariants(List<Item> out, EnumDecl en) {
        for (EnumVariant v : en.variants) {
            add(out, v.name, "enum", variantDetail(en, v));
        }
    }

    private static void addColorMembers(List<Item> out) {
        for (String[] row : COLOR_VARIANTS) {
            add(out, row[0], "enum", row[1]);
        }
    }

    private static String variantDetail(EnumDecl en, EnumVariant v) {
        StringBuilder detail = new StringBuilder(en.name).append('.').append(v.name);
        if (v.arity <= 0) {
            return detail.toString();
        }
        detail.append('(');
        for (int i = 0; i < v.arity; i++) {
            if (i > 0) {
                detail.append(", ");
            }
            String field = i < v.fieldNames.size() ? v.fieldNames.get(i) : "";
            detail.append(field.isEmpty() ? "_" : field);
        }
        detail.append(')');
        return detail.toString();
    }

    private static EnumDecl findEnum(Program program, String name) {
        final EnumDecl[] hit = {null};
        walk(program, new Walk() {
            @Override
            void enm(EnumDecl e) {
                if (e.name.equals(name)) {
                    hit[0] = e;
                }
            }
        });
        return hit[0];
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

            @Override
            void enm(EnumDecl e) {
                if (e.name.equals(name)) {
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

    private static boolean inCommentOrString(String source, String path, int offset) {
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return false;
        }
        int probe = Math.max(0, offset - 1);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Tok.LineComment && t.kind != Tok.BlockComment && t.kind != Tok.String) {
                continue;
            }
            int start = SourcePos.offset(source, t.line, t.col);
            int end = tokenEnd(source, t, start);
            if (probe >= start && probe < end) {
                return true;
            }
        }
        return false;
    }

    private static int tokenEnd(String source, Token t, int start) {
        if (t.kind == Tok.String) {
            int i = start;
            if (i < source.length() && source.charAt(i) == '"') {
                i++;
            }
            while (i < source.length()) {
                char c = source.charAt(i++);
                if (c == '\\' && i < source.length()) {
                    i++;
                } else if (c == '"') {
                    break;
                }
            }
            return i;
        }
        int len = t.text == null ? 0 : t.text.length();
        return Math.min(source.length(), start + Math.max(1, len));
    }

    private static String linePrefix(String source, int offset) {
        int start = offset;
        while (start > 0 && source.charAt(start - 1) != '\n') {
            start--;
        }
        return source.substring(start, offset);
    }

    private static boolean afterAt(String prefix) {
        int i = skipIdent(prefix);
        return i > 0 && prefix.charAt(i - 1) == '@';
    }

    private static void addStdlibChildren(List<Item> out) {
        for (String child : Stdlib.stdlibChildren()) {
            add(out, child, "module", "std." + child);
        }
    }

    private static void addImportCrates(List<Item> out, String path) {
        add(out, "std", "module", "stdlib");
        addStdlibChildren(out);
        try {
            Path start = path == null || path.isEmpty() ? Path.of(".") : Path.of(path);
            Project project = Project.find(start);
            if (project == null) {
                return;
            }
            for (String name : project.modules.keySet()) {
                add(out, name, "module", "project module");
            }
        } catch (Exception ignored) {
        }
    }

    private static void addFromImportNames(List<Item> out, String crate, String path) {
        String want = crate;
        if (want.length() > 4 && want.startsWith("std.")) {
            want = want.substring(4);
        }
        for (Map.Entry<String, List<Types.StdlibExport>> e : Stdlib.exportIndex(path).entrySet()) {
            for (Types.StdlibExport ex : e.getValue()) {
                if (crateEquals(ex.crate, crate, want)) {
                    add(out, e.getKey(), ex.kind.isEmpty() ? "value" : ex.kind, ex.crate);
                    break;
                }
            }
        }
        addProjectModuleNames(out, crate, path);
    }

    private static boolean crateEquals(String got, String crate, String want) {
        return got.equals(crate) || got.equals(want) || got.equals("std." + want);
    }

    private static void addProjectModuleNames(List<Item> out, String crate, String path) {
        try {
            Path start = path == null || path.isEmpty() ? Path.of(".") : Path.of(path);
            Project project = Project.find(start);
            if (project == null) {
                return;
            }
            Path mapped = project.modulePath(crate);
            if (mapped == null) {
                return;
            }
            List<Path> files = Files.isDirectory(mapped) ? Stdlib.listRgFiles(mapped) : List.of(mapped);
            for (Path file : files) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Program program = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
                addFileSymbols(out, program);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean afterColonType(String prefix) {
        int i = skipIdent(prefix);
        while (i > 0 && Character.isWhitespace(prefix.charAt(i - 1))) {
            i--;
        }
        return i > 0 && prefix.charAt(i - 1) == ':';
    }

    private static String memberRecv(String prefix) {
        int i = skipIdent(prefix);
        if (i == 0 || prefix.charAt(i - 1) != '.') {
            return "";
        }
        int end = i - 1;
        int start = end;
        while (start > 0 && Idents.identChar(prefix.charAt(start - 1))) {
            start--;
        }
        return prefix.substring(start, end);
    }

    private static int skipIdent(String prefix) {
        int i = prefix.length();
        while (i > 0 && Idents.identChar(prefix.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static void addRows(List<Item> out, String kind, String[][] rows) {
        for (String[] row : rows) {
            add(out, row[0], kind, row[1]);
        }
    }

    private static void add(List<Item> out, String label, String kind, String detail) {
        if (label == null || label.isEmpty()) {
            return;
        }
        out.add(new Item(label, kind, detail));
    }

    private static List<Item> unique(List<Item> items) {
        Map<String, Item> seen = new LinkedHashMap<>();
        for (Item item : items) {
            seen.putIfAbsent(item.kind + ":" + item.label, item);
        }
        return new ArrayList<>(seen.values());
    }
}

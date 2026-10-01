package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Fixes {

    public enum Kind {
        WrapTry,
        MatchArms,
        ImplementTrait,
        AddMethod,
        ImportCrate,
        AwaitFuture,
        RemoveImport
    }

    public static final class Edit {
        public final int start;
        public final int end;
        public final String text;

        Edit(int start, int end, String text) {
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
            this.text = text == null ? "" : text;
        }
    }

    public static final class Action {
        public final Kind kind;
        public final String title;
        public final boolean preferred;
        public final List<Edit> edits;

        Action(Kind kind, String title, boolean preferred, List<Edit> edits) {
            this.kind = kind;
            this.title = title;
            this.preferred = preferred;
            this.edits = List.copyOf(edits);
        }
    }

    private Fixes() {
    }

    public static List<Action> suggest(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        int[] lc = SourcePos.lineCol(source, offset);
        int sl = Math.max(0, lc[0] - 1);
        int sc = Math.max(0, lc[1] - 1);
        return suggestRange(source, path, sl, sc, sl, sc);
    }

    public static List<Action> forDiagnostic(String source, String path, Diagnostic d) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        if (d == null) {
            return List.of();
        }
        int sl = Math.max(0, d.line - 1);
        int sc = Math.max(0, d.col - 1);
        return suggestRange(source, path, sl, sc, sl, sc);
    }

    public static String apply(String source, Action action) {
        if (source == null) {
            source = "";
        }
        if (action == null || action.edits.isEmpty()) {
            return source;
        }
        List<Edit> edits = new ArrayList<>(action.edits);
        edits.sort((a, b) -> Integer.compare(b.start, a.start));
        StringBuilder out = new StringBuilder(source);
        for (Edit e : edits) {
            int start = Math.min(e.start, out.length());
            int end = Math.min(e.end, out.length());
            if (end < start) {
                continue;
            }
            out.replace(start, end, e.text);
        }
        return out.toString();
    }

    private static List<Action> suggestRange(String source, String path, int sl, int sc, int el, int ec) {
        List<Diagnostic> diags = Check.checkSource(source, path);
        Program program;
        try {
            program = Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            program = new Program();
        }
        List<Diagnostic> hit = new ArrayList<>();
        for (Diagnostic d : diags) {
            int[] range = tokenRange(source, d.line, d.col);
            boolean keep = rangesOverlap(range[0], range[1], range[2], range[3], sl, sc, el, ec)
                    || (d.line > 0 && d.line - 1 >= sl && d.line - 1 <= el);
            if (!keep && d.message.contains(" is missing '") && d.message.contains(" for trait '")) {
                List<String> qs = quotedIdents(d.message);
                if (!qs.isEmpty() && typeBodyContains(source, qs.getFirst(), sl, sc)) {
                    keep = true;
                }
            }
            if (!keep && d.message.contains("match of ") && d.message.contains("missing variant")) {
                if (matchBodyContains(source, d.line, d.col, sl, sc)) {
                    keep = true;
                }
            }
            if (keep) {
                hit.add(d);
            }
        }
        List<Action> actions = new ArrayList<>();
        String nl = lineEnding(source);
        addWrapTry(actions, source, program, hit);
        addMatchArms(actions, source, program, hit, nl);
        addTraitStubs(actions, source, program, hit, nl);
        addImportCrates(actions, source, path, diags, nl);
        addAwait(actions, source, program, hit);
        addRemoveImport(actions, source, hit);
        return actions;
    }

    private static void addWrapTry(List<Action> actions, String source, Program program, List<Diagnostic> hit) {
        for (Diagnostic d : hit) {
            if (!d.message.contains("requires 'try'")) {
                continue;
            }
            Expr call = findCallInProgram(program, d.line, d.col);
            if (call == null) {
                continue;
            }
            int[] pos = {call.line, call.col};
            leftmostExpr(call, pos);
            int offset = SourcePos.offset(source, pos[0], pos[1]);
            actions.add(new Action(Kind.WrapTry, "Wrap with try", true, List.of(insert(offset, "try "))));
        }
    }

    private static void addMatchArms(
            List<Action> actions,
            String source,
            Program program,
            List<Diagnostic> hit,
            String nl
    ) {
        for (Diagnostic d : hit) {
            if (!d.message.contains("match of ") || !d.message.contains("missing variant")) {
                continue;
            }
            String prefix = "match of ";
            int a = d.message.indexOf(prefix);
            int b = d.message.indexOf(" is missing", a);
            if (a < 0 || b < 0) {
                continue;
            }
            String enumName = d.message.substring(a + prefix.length(), b);
            EnumDecl en = findEnumInProgram(program, enumName);
            List<String> missing = quotedIdents(d.message);
            if (missing.isEmpty()) {
                continue;
            }
            int[] close = findKeywordClose(source, d.line, d.col);
            if (close == null) {
                continue;
            }
            String indent = lineIndent(source, close[0]);
            String inner = indent + "    ";
            StringBuilder insert = new StringBuilder();
            for (String name : missing) {
                EnumVariant var = new EnumVariant();
                var.name = name;
                if (en != null) {
                    for (EnumVariant v : en.variants) {
                        if (v.name.equals(name)) {
                            var = v;
                            break;
                        }
                    }
                }
                insert.append(inner).append(variantArmText(var)).append(nl);
            }
            int offset = SourcePos.offset(source, close[0] + 1, close[1]);
            actions.add(new Action(
                    Kind.MatchArms,
                    "Add missing match arms",
                    true,
                    List.of(insert(offset, insert.toString()))
            ));
        }
    }

    private static void addTraitStubs(
            List<Action> actions,
            String source,
            Program program,
            List<Diagnostic> hit,
            String nl
    ) {
        Map<String, MissingTrait> missingTraits = new LinkedHashMap<>();
        for (Diagnostic d : hit) {
            String msg = d.message;
            if (!msg.contains(" is missing '") || !msg.contains(" for trait '")) {
                continue;
            }
            List<String> qs = quotedIdents(msg);
            if (qs.size() < 3) {
                continue;
            }
            String key = qs.get(0) + "\0" + qs.get(2);
            MissingTrait m = missingTraits.get(key);
            if (m == null) {
                m = new MissingTrait(qs.get(0), qs.get(2));
                missingTraits.put(key, m);
            }
            if (!m.methods.contains(qs.get(1))) {
                m.methods.add(qs.get(1));
            }
        }
        for (MissingTrait m : missingTraits.values()) {
            TraitDecl tr = findTraitInProgram(program, m.traitName);
            int[] close = findImplForClose(source, m.traitName, m.typeName);
            if (close == null) {
                close = findTypeBodyClose(source, m.typeName);
            }
            if (close == null) {
                continue;
            }
            String indent = lineIndent(source, close[0]) + "    ";
            int offset = SourcePos.offset(source, close[0] + 1, close[1]);
            if (m.methods.size() > 1) {
                StringBuilder insert = new StringBuilder();
                for (String name : m.methods) {
                    insert.append(stubTraitMethod(methodOf(tr, name), indent, nl));
                }
                actions.add(new Action(
                        Kind.ImplementTrait,
                        "Implement missing methods for " + m.traitName,
                        true,
                        List.of(insert(offset, insert.toString()))
                ));
            }
            for (String name : m.methods) {
                actions.add(new Action(
                        Kind.AddMethod,
                        "Add method '" + name + "'",
                        false,
                        List.of(insert(offset, stubTraitMethod(methodOf(tr, name), indent, nl)))
                ));
            }
        }
    }

    private static void addImportCrates(
            List<Action> actions,
            String source,
            String path,
            List<Diagnostic> diags,
            String nl
    ) {
        Set<String> crates = new LinkedHashSet<>();
        for (Diagnostic d : diags) {
            String crate = "";
            List<String> qs = quotedIdents(d.message);
            if (!qs.isEmpty()) {
                Types.StdlibExport ex = Types.lookupStdlibExport(qs.getFirst(), path);
                if (ex != null) {
                    crate = ex.crate;
                }
            }
            if (crate.isEmpty()) {
                crate = crateFromDiag(d.message);
            }
            if (crate.isEmpty() || !crates.add(crate)) {
                continue;
            }
            if (fileHasCrateImport(source, crate)) {
                continue;
            }
            int il = importInsertLine(source);
            int offset = SourcePos.offset(source, il + 1, 1);
            actions.add(new Action(
                    Kind.ImportCrate,
                    "Import crate " + crate,
                    true,
                    List.of(insert(offset, "import " + crate + ";" + nl))
            ));
        }
    }

    private static void addAwait(List<Action> actions, String source, Program program, List<Diagnostic> hit) {
        for (Diagnostic d : hit) {
            if (!d.message.contains("unused Future")) {
                continue;
            }
            Stmt stmt = findExprStmtInProgram(program, d.line);
            if (stmt == null || stmt.expr == null || stmt.expr.kind == Expr.Kind.Await) {
                continue;
            }
            int[] pos = {stmt.expr.line, stmt.expr.col};
            leftmostExpr(stmt.expr, pos);
            int offset = SourcePos.offset(source, pos[0], pos[1]);
            actions.add(new Action(Kind.AwaitFuture, "Await Future", true, List.of(insert(offset, "await "))));
        }
    }

    private static void addRemoveImport(List<Action> actions, String source, List<Diagnostic> hit) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (Diagnostic d : hit) {
            if (!d.message.contains("unused import")) {
                continue;
            }
            if (!seen.add(d.line)) {
                continue;
            }
            int start = SourcePos.offset(source, d.line, 1);
            int end = start;
            while (end < source.length() && source.charAt(end) != '\n') {
                end++;
            }
            if (end < source.length() && source.charAt(end) == '\n') {
                end++;
            }
            List<String> qs = quotedIdents(d.message);
            String title = qs.isEmpty() ? "Remove unused import" : "Remove unused import " + qs.getFirst();
            actions.add(new Action(Kind.RemoveImport, title, true, List.of(new Edit(start, end, ""))));
        }
    }

    private static Edit insert(int offset, String text) {
        return new Edit(offset, offset, text);
    }

    private static final class MissingTrait {
        final String typeName;
        final String traitName;
        final List<String> methods = new ArrayList<>();

        MissingTrait(String typeName, String traitName) {
            this.typeName = typeName;
            this.traitName = traitName;
        }
    }

    private static List<String> quotedIdents(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '\'') {
                continue;
            }
            int j = i + 1;
            while (j < s.length() && s.charAt(j) != '\'') {
                j++;
            }
            if (j >= s.length()) {
                break;
            }
            out.add(s.substring(i + 1, j));
            i = j;
        }
        return out;
    }

    private static String crateFromDiag(String msg) {
        String p = "(in crate ";
        int a = msg.indexOf(p);
        if (a < 0) {
            return "";
        }
        int start = a + p.length();
        int b = start;
        while (b < msg.length() && msg.charAt(b) != ';' && msg.charAt(b) != ')') {
            b++;
        }
        if (b >= msg.length()) {
            return "";
        }
        String c = msg.substring(start, b).trim();
        int orPos = c.indexOf(" or ");
        if (orPos >= 0) {
            c = c.substring(0, orPos).trim();
        }
        return c;
    }

    private static String lineEnding(String source) {
        return source.contains("\r\n") ? "\r\n" : "\n";
    }

    private static List<String> splitLines(String source) {
        List<String> lines = new ArrayList<>();
        int i = 0;
        while (i <= source.length()) {
            int n = source.indexOf('\n', i);
            if (n < 0) {
                lines.add(source.substring(i));
                break;
            }
            String line = source.substring(i, n);
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            lines.add(line);
            i = n + 1;
        }
        return lines;
    }

    private static String lineIndent(String source, int line0) {
        List<String> lines = splitLines(source);
        if (line0 < 0 || line0 >= lines.size()) {
            return "    ";
        }
        String line = lines.get(line0);
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i == 0) {
            return "    ";
        }
        return line.substring(0, i);
    }

    private static String trimCopy(String s) {
        return s == null ? "" : s.trim();
    }

    private static int importInsertLine(String text) {
        List<String> lines = splitLines(text);
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = trimCopy(lines.get(i));
            if (t.startsWith("import ") || t.startsWith("from ")) {
                last = i;
            }
        }
        return last + 1;
    }

    private static boolean fileHasCrateImport(String text, String crate) {
        for (String line : splitLines(text)) {
            String t = trimCopy(line);
            if (t.equals("import " + crate + ";") || t.equals("import std." + crate + ";")) {
                return true;
            }
            if (crate.equals("std") && t.startsWith("import std")) {
                return true;
            }
        }
        return false;
    }

    private static void leftmostExpr(Expr e, int[] pos) {
        if (e != null && !e.kids.isEmpty()
                && (e.kind == Expr.Kind.MethodCall || e.kind == Expr.Kind.Member || e.kind == Expr.Kind.Index)) {
            leftmostExpr(e.kids.getFirst(), pos);
            return;
        }
        if (e != null) {
            pos[0] = e.line;
            pos[1] = e.col;
        }
    }

    private static Expr findCallAt(Expr e, int line, int col) {
        if (e == null) {
            return null;
        }
        if ((e.kind == Expr.Kind.Call || e.kind == Expr.Kind.MethodCall) && e.line == line && e.col == col) {
            return e;
        }
        for (Expr k : e.kids) {
            Expr hit = findCallAt(k, line, col);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Expr findCallInStmts(List<Stmt> stmts, int line, int col) {
        for (Stmt s : stmts) {
            Expr hit = findCallAt(s.expr, line, col);
            if (hit == null) {
                hit = findCallAt(s.target, line, col);
            }
            if (hit != null) {
                return hit;
            }
            hit = findCallInStmts(s.body, line, col);
            if (hit != null) {
                return hit;
            }
            hit = findCallInStmts(s.elseBody, line, col);
            if (hit != null) {
                return hit;
            }
            for (MatchArm arm : s.arms) {
                hit = findCallInStmts(arm.body, line, col);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Expr findCallInFns(List<FnDecl> fns, int line, int col) {
        for (FnDecl fn : fns) {
            Expr hit = findCallInStmts(fn.body, line, col);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Expr findCallInMod(ModDecl m, int line, int col) {
        Expr hit = findCallInFns(m.fns, line, col);
        if (hit != null) {
            return hit;
        }
        for (StructDecl st : m.structs) {
            hit = findCallInFns(st.methods, line, col);
            if (hit != null) {
                return hit;
            }
        }
        for (ClassDecl c : m.classes) {
            hit = findCallInFns(c.methods, line, col);
            if (hit != null) {
                return hit;
            }
            for (NestedImpl ti : c.traitImpls) {
                hit = findCallInFns(ti.methods, line, col);
                if (hit != null) {
                    return hit;
                }
            }
        }
        for (ImplDecl im : m.impls) {
            hit = findCallInFns(im.methods, line, col);
            if (hit != null) {
                return hit;
            }
        }
        for (ModDecl nested : m.mods) {
            hit = findCallInMod(nested, line, col);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Expr findCallInProgram(Program p, int line, int col) {
        Expr hit = findCallInFns(p.fns, line, col);
        if (hit != null) {
            return hit;
        }
        for (StructDecl st : p.structs) {
            hit = findCallInFns(st.methods, line, col);
            if (hit != null) {
                return hit;
            }
        }
        for (ClassDecl c : p.classes) {
            hit = findCallInFns(c.methods, line, col);
            if (hit != null) {
                return hit;
            }
            for (NestedImpl ti : c.traitImpls) {
                hit = findCallInFns(ti.methods, line, col);
                if (hit != null) {
                    return hit;
                }
            }
        }
        for (ImplDecl im : p.impls) {
            hit = findCallInFns(im.methods, line, col);
            if (hit != null) {
                return hit;
            }
        }
        for (ModDecl m : p.mods) {
            hit = findCallInMod(m, line, col);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Stmt findExprStmtInStmts(List<Stmt> stmts, int line) {
        for (Stmt s : stmts) {
            if (s.kind == Stmt.Kind.Comment) {
                continue;
            }
            if (s.kind == Stmt.Kind.Expr && s.line == line) {
                return s;
            }
            Stmt hit = findExprStmtInStmts(s.body, line);
            if (hit != null) {
                return hit;
            }
            hit = findExprStmtInStmts(s.elseBody, line);
            if (hit != null) {
                return hit;
            }
            for (MatchArm arm : s.arms) {
                hit = findExprStmtInStmts(arm.body, line);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Stmt findExprStmtInFns(List<FnDecl> fns, int line) {
        for (FnDecl fn : fns) {
            Stmt hit = findExprStmtInStmts(fn.body, line);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Stmt findExprStmtInMod(ModDecl m, int line) {
        Stmt hit = findExprStmtInFns(m.fns, line);
        if (hit != null) {
            return hit;
        }
        for (StructDecl st : m.structs) {
            hit = findExprStmtInFns(st.methods, line);
            if (hit != null) {
                return hit;
            }
        }
        for (ClassDecl c : m.classes) {
            hit = findExprStmtInFns(c.methods, line);
            if (hit != null) {
                return hit;
            }
            for (NestedImpl ti : c.traitImpls) {
                hit = findExprStmtInFns(ti.methods, line);
                if (hit != null) {
                    return hit;
                }
            }
        }
        for (ImplDecl im : m.impls) {
            hit = findExprStmtInFns(im.methods, line);
            if (hit != null) {
                return hit;
            }
        }
        for (ModDecl nested : m.mods) {
            hit = findExprStmtInMod(nested, line);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Stmt findExprStmtInProgram(Program p, int line) {
        Stmt hit = findExprStmtInFns(p.fns, line);
        if (hit != null) {
            return hit;
        }
        for (StructDecl st : p.structs) {
            hit = findExprStmtInFns(st.methods, line);
            if (hit != null) {
                return hit;
            }
        }
        for (ClassDecl c : p.classes) {
            hit = findExprStmtInFns(c.methods, line);
            if (hit != null) {
                return hit;
            }
            for (NestedImpl ti : c.traitImpls) {
                hit = findExprStmtInFns(ti.methods, line);
                if (hit != null) {
                    return hit;
                }
            }
        }
        for (ImplDecl im : p.impls) {
            hit = findExprStmtInFns(im.methods, line);
            if (hit != null) {
                return hit;
            }
        }
        for (ModDecl m : p.mods) {
            hit = findExprStmtInMod(m, line);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static EnumDecl findEnumInMod(ModDecl m, String name) {
        for (EnumDecl e : m.enums) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        for (ModDecl nested : m.mods) {
            EnumDecl hit = findEnumInMod(nested, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static EnumDecl findEnumInProgram(Program p, String name) {
        for (EnumDecl e : p.enums) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        for (ModDecl m : p.mods) {
            EnumDecl hit = findEnumInMod(m, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static TraitDecl findTraitInMod(ModDecl m, String name) {
        String head = Types.typeHead(name);
        for (TraitDecl t : m.traits) {
            if (t.name.equals(head)) {
                return t;
            }
        }
        for (ModDecl nested : m.mods) {
            TraitDecl hit = findTraitInMod(nested, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static TraitDecl findTraitInProgram(Program p, String name) {
        String head = Types.typeHead(name);
        for (TraitDecl t : p.traits) {
            if (t.name.equals(head)) {
                return t;
            }
        }
        for (ModDecl m : p.mods) {
            TraitDecl hit = findTraitInMod(m, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static TraitMethod methodOf(TraitDecl tr, String name) {
        if (tr != null) {
            for (TraitMethod tm : tr.methods) {
                if (tm.name.equals(name)) {
                    return tm;
                }
            }
        }
        TraitMethod fake = new TraitMethod();
        fake.name = name;
        fake.params.add("self");
        fake.paramTypes.add("");
        return fake;
    }

    private static String variantArmText(EnumVariant v) {
        if (v.arity <= 0) {
            return v.name + " { pass; }";
        }
        StringBuilder s = new StringBuilder(v.name).append('(');
        for (int i = 0; i < v.arity; i++) {
            if (i > 0) {
                s.append(", ");
            }
            String bind = "p" + i;
            if (i < v.fieldNames.size() && !v.fieldNames.get(i).isEmpty()) {
                s.append(v.fieldNames.get(i)).append(": ");
                bind = v.fieldNames.get(i);
            }
            s.append(bind);
        }
        s.append(") { pass; }");
        return s.toString();
    }

    private static String defaultTraitBody(TraitMethod m) {
        if (m.throwsEx) {
            return "throw \"todo\";";
        }
        String rt = m.returnType;
        if (rt == null || rt.isEmpty() || rt.equals("Void")) {
            return "pass;";
        }
        switch (rt) {
            case "String", "Str" -> {
                return "return \"\";";
            }
            case "Int" -> {
                return "return 0;";
            }
            case "Float" -> {
                return "return 0.0;";
            }
            case "Bool" -> {
                return "return false;";
            }
        }
        if (rt.equals("Array") || (rt.length() > 6 && rt.startsWith("Array["))) {
            return "return [];";
        }
        if (rt.equals("Map") || (rt.length() > 4 && rt.startsWith("Map["))) {
            return "return {};";
        }
        return "pass;";
    }

    private static String stubTraitMethod(TraitMethod m, String indent, String nl) {
        StringBuilder s = new StringBuilder(indent).append("fn ").append(m.name).append('(');
        for (int i = 0; i < m.params.size(); i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(m.params.get(i));
            if (i < m.paramTypes.size() && !m.paramTypes.get(i).isEmpty() && !m.params.get(i).equals("self")) {
                s.append(": ").append(m.paramTypes.get(i));
            }
        }
        s.append(')');
        if (m.throwsEx) {
            s.append(" throws");
        }
        if (m.returnType != null && !m.returnType.isEmpty()) {
            s.append(": ").append(m.returnType);
        }
        s.append(" {").append(nl);
        s.append(indent).append("    ").append(defaultTraitBody(m)).append(nl);
        s.append(indent).append('}').append(nl);
        return s.toString();
    }

    private static List<Token> tokenizeOk(String source) {
        try {
            return Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static int[] spanToClose(List<Token> tokens, int from) {
        int depth = 0;
        boolean inBrace = false;
        for (int i = from; i < tokens.size(); i++) {
            Tok k = tokens.get(i).kind;
            if (k == Tok.LBrace) {
                depth++;
                inBrace = true;
            } else if (k == Tok.RBrace) {
                if (depth > 0) {
                    depth--;
                }
                if (inBrace && depth == 0) {
                    Token t = tokens.get(i);
                    return new int[]{t.line > 0 ? t.line - 1 : 0, Math.max(t.col, 0)};
                }
            } else if (!inBrace && k == Tok.Semi) {
                Token t = tokens.get(i);
                return new int[]{t.line > 0 ? t.line - 1 : 0, Math.max(t.col, 0)};
            }
        }
        return null;
    }

    private static int[] findKeywordClose(String source, int line1, int col1) {
        List<Token> tokens = tokenizeOk(source);
        if (tokens.isEmpty()) {
            return null;
        }
        int[] hit = findKeywordClose(tokens, line1, col1, Tok.Match);
        if (hit == null) {
            hit = findKeywordClose(tokens, line1, col1, Tok.Switch);
        }
        return hit;
    }

    private static int[] findKeywordClose(List<Token> tokens, int line1, int col1, Tok kind) {
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != kind || t.line != line1) {
                continue;
            }
            if (col1 > 0 && t.col != col1) {
                continue;
            }
            return spanToClose(tokens, i);
        }
        if (col1 > 0) {
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.kind == kind && t.line == line1) {
                    return spanToClose(tokens, i);
                }
            }
        }
        return null;
    }

    private static int[] findTypeBodyClose(String source, String typeName) {
        List<Token> tokens = tokenizeOk(source);
        if (tokens.isEmpty()) {
            return null;
        }
        String head = Types.typeHead(typeName);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Tok k = tokens.get(i).kind;
            if (k != Tok.Class && k != Tok.Struct && k != Tok.Data && k != Tok.Trait) {
                continue;
            }
            if (tokens.get(i + 1).kind == Tok.Identifier && tokens.get(i + 1).text.equals(head)) {
                return spanToClose(tokens, i);
            }
        }
        return null;
    }

    private static int[] findImplForClose(String source, String traitName, String typeName) {
        List<Token> tokens = tokenizeOk(source);
        if (tokens.isEmpty()) {
            return null;
        }
        String trait = Types.typeHead(traitName);
        String type = Types.typeHead(typeName);
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind != Tok.Implements) {
                continue;
            }
            String seenTrait = "";
            String seenType = "";
            boolean sawFor = false;
            int j = i + 1;
            while (j < tokens.size() && tokens.get(j).kind != Tok.LBrace
                    && tokens.get(j).kind != Tok.Semi && tokens.get(j).kind != Tok.Eof) {
                if (tokens.get(j).kind == Tok.For) {
                    sawFor = true;
                } else if (tokens.get(j).kind == Tok.Identifier) {
                    if (sawFor) {
                        seenType = tokens.get(j).text;
                    } else if (seenTrait.isEmpty()) {
                        seenTrait = tokens.get(j).text;
                    }
                }
                j++;
            }
            if (sawFor && seenTrait.equals(trait) && seenType.equals(type)) {
                return spanToClose(tokens, i);
            }
        }
        return null;
    }

    private static boolean typeBodyContains(String source, String typeName, int line0, int col0) {
        List<Token> tokens = tokenizeOk(source);
        if (tokens.isEmpty()) {
            return false;
        }
        String head = Types.typeHead(typeName);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Tok k = tokens.get(i).kind;
            if (k != Tok.Class && k != Tok.Struct && k != Tok.Data && k != Tok.Trait) {
                continue;
            }
            if (tokens.get(i + 1).kind != Tok.Identifier || !tokens.get(i + 1).text.equals(head)) {
                continue;
            }
            int[] close = spanToClose(tokens, i);
            if (close == null) {
                continue;
            }
            int sl = tokens.get(i).line > 0 ? tokens.get(i).line - 1 : 0;
            int sc = tokens.get(i).col > 0 ? tokens.get(i).col - 1 : 0;
            if (posInSpan(line0, col0, sl, sc, close[0], close[1])) {
                return true;
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind != Tok.Implements) {
                continue;
            }
            String seenType = "";
            boolean sawFor = false;
            int j = i + 1;
            while (j < tokens.size() && tokens.get(j).kind != Tok.LBrace
                    && tokens.get(j).kind != Tok.Semi && tokens.get(j).kind != Tok.Eof) {
                if (tokens.get(j).kind == Tok.For) {
                    sawFor = true;
                } else if (tokens.get(j).kind == Tok.Identifier && sawFor) {
                    seenType = tokens.get(j).text;
                }
                j++;
            }
            if (!sawFor || !seenType.equals(head)) {
                continue;
            }
            int[] close = spanToClose(tokens, i);
            if (close == null) {
                continue;
            }
            int sl = tokens.get(i).line > 0 ? tokens.get(i).line - 1 : 0;
            int sc = tokens.get(i).col > 0 ? tokens.get(i).col - 1 : 0;
            if (posInSpan(line0, col0, sl, sc, close[0], close[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchBodyContains(String source, int matchLine1, int matchCol1, int line0, int col0) {
        List<Token> tokens = tokenizeOk(source);
        if (tokens.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Tok.Match && t.kind != Tok.Switch) {
                continue;
            }
            if (matchLine1 > 0 && t.line != matchLine1) {
                continue;
            }
            int[] close = spanToClose(tokens, i);
            if (close == null) {
                continue;
            }
            int sl = t.line > 0 ? t.line - 1 : 0;
            int sc = t.col > 0 ? t.col - 1 : 0;
            if (posInSpan(line0, col0, sl, sc, close[0], close[1])) {
                return true;
            }
        }
        return false;
    }

    private static int[] tokenRange(String text, int line1, int col1) {
        int sl = line1 > 0 ? line1 - 1 : 0;
        int sc = col1 > 0 ? col1 - 1 : 0;
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            return new int[]{0, 0, 0, 0};
        }
        if (sl >= lines.size()) {
            sl = lines.size() - 1;
        }
        String line = lines.get(sl);
        if (sc > line.length()) {
            sc = line.length();
        }
        int el = sl;
        int ec = sc;
        if (sc < line.length() && Idents.identChar(line.charAt(sc))) {
            while (ec < line.length() && Idents.identChar(line.charAt(ec))) {
                ec++;
            }
        } else if (sc < line.length()) {
            ec = sc + 1;
        }
        return new int[]{sl, sc, el, ec};
    }

    private static boolean rangesOverlap(int asl, int asc, int ael, int aec, int bsl, int bsc, int bel, int bec) {
        if (ael < bsl || bel < asl) {
            return false;
        }
        if (ael == bsl && aec < bsc) {
            return false;
        }
        return bel != asl || bec >= asc;
    }

    private static boolean posInSpan(int line, int col, int sl, int sc, int el, int ec) {
        if (line < sl || line > el) {
            return false;
        }
        if (line == sl && col < sc) {
            return false;
        }
        return line != el || col <= ec;
    }
}

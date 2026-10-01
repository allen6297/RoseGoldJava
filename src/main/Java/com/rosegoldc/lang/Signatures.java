package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Signatures {

    public static final class Info {
        public final String name;
        public final String qualifier;
        public final String label;
        public final List<String> params;
        public final int activeParam;
        public final int parenOffset;
        public final int[] paramStarts;
        public final int[] paramEnds;

        Info(
                String name,
                String qualifier,
                String label,
                List<String> params,
                int activeParam,
                int parenOffset,
                int[] paramStarts,
                int[] paramEnds
        ) {
            this.name = name;
            this.qualifier = qualifier == null ? "" : qualifier;
            this.label = label;
            this.params = List.copyOf(params);
            this.activeParam = activeParam;
            this.parenOffset = Math.max(0, parenOffset);
            this.paramStarts = paramStarts;
            this.paramEnds = paramEnds;
        }

        public int highlightStart(int index) {
            if (index < 0 || index >= paramStarts.length) {
                return 0;
            }
            return paramStarts[index];
        }

        public int highlightEnd(int index) {
            if (index < 0 || index >= paramEnds.length) {
                return 0;
            }
            return paramEnds[index];
        }
    }

    private static final class Site {
        String name = "";
        String qualifier = "";
        int activeParam;
        int parenOffset;
    }

    private static final class Sig {
        final List<String> params = new ArrayList<>();
        boolean throwsEx;
        String returnType = "";
    }

    private static final Set<String> HOST = Set.of("checks", "process");

    private Signatures() {
    }

    public static Info at(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        Site site = callSiteAt(source, offset);
        if (site == null) {
            return null;
        }
        Sig sig = resolve(source, path, site);
        List<String> params = sig.params;
        int active = site.activeParam;
        if (active < 0) {
            active = 0;
        }
        if (!params.isEmpty() && active >= params.size()) {
            active = params.size() - 1;
        }
        StringBuilder label = new StringBuilder(site.name).append('(');
        int[] starts = new int[params.size()];
        int[] ends = new int[params.size()];
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                label.append(", ");
            }
            starts[i] = label.length();
            label.append(params.get(i));
            ends[i] = label.length();
        }
        label.append(')');
        if (sig.throwsEx) {
            label.append(" throws");
        }
        if (sig.returnType != null && !sig.returnType.isEmpty()) {
            label.append(": ").append(sig.returnType);
        }
        return new Info(site.name, site.qualifier, label.toString(), params, active, site.parenOffset, starts, ends);
    }

    private static Site callSiteAt(String source, int offset) {
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, "", new ArrayList<>());
        } catch (RuntimeException ex) {
            return null;
        }
        int[] lc = SourcePos.lineCol(source, offset);
        int line1 = lc[0];
        int col1 = lc[1];
        List<Site> stack = new ArrayList<>();
        String pending = "";
        String pendingQual = "";
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Tok.Eof) {
                break;
            }
            if (t.line > line1 || (t.line == line1 && t.col > col1)) {
                break;
            }
            if (t.kind == Tok.Identifier) {
                pending = t.text;
                pendingQual = "";
                if (i >= 2 && tokens.get(i - 1).kind == Tok.Dot && tokens.get(i - 2).kind == Tok.Identifier) {
                    pendingQual = tokens.get(i - 2).text;
                }
            } else if (t.kind == Tok.LParen) {
                Site frame = new Site();
                frame.name = pending;
                frame.qualifier = pendingQual;
                frame.parenOffset = SourcePos.offset(source, t.line, t.col);
                stack.add(frame);
                pending = "";
                pendingQual = "";
            } else if (t.kind == Tok.RParen) {
                boolean closed = t.line < line1 || (t.line == line1 && t.col < col1);
                if (closed && !stack.isEmpty()) {
                    stack.removeLast();
                }
                pending = "";
                pendingQual = "";
            } else if (t.kind == Tok.Comma && !stack.isEmpty()) {
                stack.getLast().activeParam++;
            } else if (t.kind != Tok.LineComment && t.kind != Tok.BlockComment) {
                pending = "";
                pendingQual = "";
            }
        }
        if (stack.isEmpty() || stack.getLast().name.isEmpty()) {
            return null;
        }
        return stack.getLast();
    }

    private static Sig resolve(String source, String path, Site site) {
        Sig builtin = builtin(site);
        if (builtin != null) {
            return builtin;
        }
        Program program = parse(source, path);
        if (!site.qualifier.isEmpty()) {
            if (!HOST.contains(site.qualifier)) {
                String crate = Stdlib.crateOf(site.qualifier, program, path);
                Sig hit = fromCrate(crate.isEmpty() ? site.qualifier : crate, site.name, path);
                if (hit != null) {
                    return hit;
                }
            }
            Sig method = findMethod(program, site.name);
            if (method != null) {
                return method;
            }
        }
        Sig fn = findFn(program, site.name);
        if (fn != null) {
            return fn;
        }
        if (site.qualifier.isEmpty()) {
            Sig imported = fromImport(program, site.name, path);
            if (imported != null) {
                return imported;
            }
        }
        return new Sig();
    }

    private static Sig builtin(Site site) {
        if (!site.qualifier.isEmpty()) {
            if (site.qualifier.equals("checks")) {
                return switch (site.name) {
                    case "eq", "neq", "eq_string" -> params("a", "b");
                    case "that", "truthy" -> params("cond");
                    default -> null;
                };
            }
            if (site.qualifier.equals("process")) {
                return switch (site.name) {
                    case "argv" -> params("i");
                    case "argc" -> params();
                    default -> null;
                };
            }
            return null;
        }
        return switch (site.name) {
            case "print" -> params("...");
            case "len" -> params("xs");
            case "assert" -> params("cond");
            case "argv" -> params("i");
            case "argv_len" -> params();
            default -> null;
        };
    }

    private static Sig params(String... names) {
        Sig s = new Sig();
        s.params.addAll(List.of(names));
        return s;
    }

    private static Sig fromImport(Program program, String name, String path) {
        for (ImportDecl im : program.imports) {
            if (!im.isFrom || im.path.size() < 2) {
                continue;
            }
            String crate = im.path.getFirst();
            String imported = im.path.get(1);
            String local = im.alias == null || im.alias.isEmpty() ? imported : im.alias;
            if (!local.equals(name)) {
                continue;
            }
            Sig hit = fromCrate(crate, imported, path);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Sig fromCrate(String crate, String name, String path) {
        for (Path file : Stdlib.filesForCrate(crate, path)) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            Sig hit = findFn(parse(text, file.toString()), name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Sig findFn(Program program, String name) {
        Sig[] hit = {null};
        walk(program, fn -> {
            if (hit[0] == null && fn.name.equals(name)) {
                hit[0] = fromFn(fn, false);
            }
        }, method -> {
            if (hit[0] == null && method.name.equals(name)) {
                hit[0] = fromFn(method, true);
            }
        }, signal -> {
            if (hit[0] == null && signal.name.equals(name)) {
                hit[0] = fromSignal(signal);
            }
        });
        return hit[0];
    }

    private static Sig findMethod(Program program, String name) {
        Sig[] hit = {null};
        walk(program, fn -> {
        }, method -> {
            if (hit[0] == null && method.name.equals(name)) {
                hit[0] = fromFn(method, true);
            }
        }, signal -> {
            if (hit[0] == null && signal.name.equals(name)) {
                hit[0] = fromSignal(signal);
            }
        });
        return hit[0];
    }

    private static Sig fromFn(FnDecl fn, boolean method) {
        Sig s = new Sig();
        s.throwsEx = fn.throwsEx;
        s.returnType = fn.returnType == null ? "" : fn.returnType;
        for (int i = 0; i < fn.params.size(); i++) {
            String name = fn.params.get(i);
            if (name.equals("self") || name.equals("...")) {
                continue;
            }
            if (method && i == 0 && name.equals("self")) {
                continue;
            }
            String p = name;
            if (i < fn.paramTypes.size() && !fn.paramTypes.get(i).isEmpty()) {
                p += ": " + fn.paramTypes.get(i);
            }
            s.params.add(p);
        }
        return s;
    }

    private static Sig fromSignal(SignalDecl signal) {
        Sig s = new Sig();
        for (String name : signal.params) {
            if (name.equals("self") || name.equals("...")) {
                continue;
            }
            s.params.add(name);
        }
        return s;
    }

    private interface FnWalk {
        void fn(FnDecl fn);
    }

    private interface MethodWalk {
        void method(FnDecl fn);
    }

    private interface SignalWalk {
        void signal(SignalDecl s);
    }

    private static void walk(Program program, FnWalk fns, MethodWalk methods, SignalWalk signals) {
        walkItems(program.fns, program.signals, program.structs, program.classes, program.traits, program.impls,
                fns, methods, signals);
        for (ModDecl m : program.mods) {
            walkMod(m, fns, methods, signals);
        }
    }

    private static void walkMod(ModDecl m, FnWalk fns, MethodWalk methods, SignalWalk signals) {
        walkItems(m.fns, m.signals, m.structs, m.classes, m.traits, m.impls, fns, methods, signals);
        for (ModDecl nested : m.mods) {
            walkMod(nested, fns, methods, signals);
        }
    }

    private static void walkItems(
            List<FnDecl> fnList,
            List<SignalDecl> signalList,
            List<StructDecl> structs,
            List<ClassDecl> classes,
            List<TraitDecl> traits,
            List<ImplDecl> impls,
            FnWalk fns,
            MethodWalk methods,
            SignalWalk signals
    ) {
        for (FnDecl fn : fnList) {
            fns.fn(fn);
        }
        for (SignalDecl s : signalList) {
            signals.signal(s);
        }
        for (StructDecl st : structs) {
            for (FnDecl m : st.methods) {
                methods.method(m);
            }
            for (SignalDecl s : st.signals) {
                signals.signal(s);
            }
        }
        for (ClassDecl c : classes) {
            for (FnDecl m : c.methods) {
                methods.method(m);
            }
            for (NestedImpl ti : c.traitImpls) {
                for (FnDecl m : ti.methods) {
                    methods.method(m);
                }
            }
            for (SignalDecl s : c.signals) {
                signals.signal(s);
            }
        }
        for (TraitDecl t : traits) {
            for (TraitMethod tm : t.methods) {
                FnDecl fake = new FnDecl();
                fake.name = tm.name;
                fake.params.addAll(tm.params);
                fake.paramTypes.addAll(tm.paramTypes);
                fake.returnType = tm.returnType;
                fake.throwsEx = tm.throwsEx;
                methods.method(fake);
            }
            for (SignalDecl s : t.signals) {
                signals.signal(s);
            }
        }
        for (ImplDecl im : impls) {
            for (FnDecl m : im.methods) {
                methods.method(m);
            }
        }
    }

    private static Program parse(String source, String path) {
        try {
            return Parser.parseSource(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return new Program();
        }
    }
}

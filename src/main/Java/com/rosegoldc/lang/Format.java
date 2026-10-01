package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class Format {

    public static final class Options {
        public boolean blankBetweenItems = true;
        public boolean keepComments = true;
    }

    public static final class Result {
        public boolean ok = true;
        public String out = "";
        public String message = "";
        public int exitCode = 0;
    }

    private Format() {
    }

    public static String formatProgram(Program program, Options opts) {
        Printer printer = new Printer(opts == null ? new Options() : opts);
        printer.program(program);
        String s = printer.out.toString();
        if (!s.isEmpty() && !s.endsWith("\n")) {
            s += "\n";
        }
        return s;
    }

    public static Result formatSource(String source, String path, Options opts) {
        Result result = new Result();
        List<Diagnostic> diags = new java.util.ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, path, diags);
        } catch (LangException ex) {
            result.ok = false;
            result.exitCode = 1;
            result.message = ex.getMessage() == null ? "parse error" : ex.getMessage();
            return result;
        }
        if (!diags.isEmpty()) {
            result.ok = false;
            result.exitCode = 1;
            result.message = diags.getFirst().message;
            return result;
        }
        result.out = formatProgram(program, opts == null ? new Options() : opts);
        return result;
    }

    public static Result formatFile(Path path, Options opts) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            Result result = new Result();
            result.ok = false;
            result.exitCode = 2;
            result.message = "cannot open " + path;
            return result;
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        return formatSource(source, path.toString(), opts);
    }

    public static List<Path> listRgFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rg"))
                    .filter(p -> !skippedFmtPath(dir, p))
                    .forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static boolean skippedFmtPath(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("build")) {
                return true;
            }
        }
        return false;
    }

    private static final class Printer {
        final Options opts;
        final StringBuilder out = new StringBuilder();
        int indent;

        Printer(Options opts) {
            this.opts = opts;
        }

        void line() {
            out.append('\n');
        }

        void pad() {
            out.append("    ".repeat(Math.max(0, indent)));
        }

        void write(String s) {
            out.append(s);
        }

        void writeln(String s) {
            pad();
            out.append(s).append('\n');
        }

        static String escapeString(String s) {
            StringBuilder o = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    o.append("\\\\");
                } else if (c == '"') {
                    o.append("\\\"");
                } else if (c == '\n') {
                    o.append("\\n");
                } else if (c == '\r') {
                    o.append("\\r");
                } else if (c == '\t') {
                    o.append("\\t");
                } else if (c < 32) {
                    o.append(String.format("\\x%02x", (int) c));
                } else {
                    o.append(c);
                }
            }
            o.append('"');
            return o.toString();
        }

        static int binPrec(String op) {
            return switch (op) {
                case "||" -> 1;
                case "&&" -> 2;
                case "==", "!=", "<", "<=", ">", ">=" -> 3;
                case "+", "-" -> 4;
                case "*", "/", "%" -> 5;
                default -> 0;
            };
        }

        void typeArgs(List<String> args) {
            if (args == null || args.isEmpty()) {
                return;
            }
            write("[");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    write(", ");
                }
                write(args.get(i));
            }
            write("]");
        }

        void typeParams(List<TypeParam> params) {
            if (params == null || params.isEmpty()) {
                return;
            }
            write("[");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) {
                    write(", ");
                }
                TypeParam p = params.get(i);
                write(p.name);
                if (!p.bounds.isEmpty()) {
                    write(": ");
                    for (int b = 0; b < p.bounds.size(); b++) {
                        if (b > 0) {
                            write(" + ");
                        }
                        write(p.bounds.get(b));
                    }
                }
            }
            write("]");
        }

        void expr(Expr e, int parentPrec) {
            if (e == null || e.kind == null) {
                return;
            }
            switch (e.kind) {
                case Int -> write(Long.toString(e.number));
                case Float -> {
                    if (!e.text.isEmpty()) {
                        write(e.text);
                    } else {
                        write(Double.toString(e.real).replaceAll("\\.0$", ""));
                    }
                }
                case String -> write(escapeString(e.text));
                case Bool -> write(e.booleanValue ? "true" : "false");
                case Var -> {
                    if (!e.module.isEmpty()) {
                        write(e.module);
                        write(".");
                    }
                    write(e.text);
                    typeArgs(e.typeArgs);
                }
                case Unary -> {
                    write(e.text);
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 6);
                    }
                }
                case Binary -> {
                    int prec = binPrec(e.text);
                    boolean wrap = prec < parentPrec;
                    if (wrap) {
                        write("(");
                    }
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), prec);
                    }
                    write(" ");
                    write(e.text);
                    write(" ");
                    if (e.kids.size() >= 2) {
                        expr(e.kids.get(1), prec + 1);
                    }
                    if (wrap) {
                        write(")");
                    }
                }
                case Call -> {
                    int start = 0;
                    if (!e.text.isEmpty()) {
                        write(e.text);
                    } else if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                        start = 1;
                    }
                    typeArgs(e.typeArgs);
                    write("(");
                    for (int i = start; i < e.kids.size(); i++) {
                        if (i > start) {
                            write(", ");
                        }
                        expr(e.kids.get(i), 0);
                    }
                    write(")");
                }
                case MethodCall -> {
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                    }
                    write(".");
                    write(e.text);
                    typeArgs(e.typeArgs);
                    write("(");
                    for (int i = 1; i < e.kids.size(); i++) {
                        if (i > 1) {
                            write(", ");
                        }
                        expr(e.kids.get(i), 0);
                    }
                    write(")");
                }
                case Member -> {
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                    }
                    write(".");
                    write(e.text);
                }
                case Index -> {
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                    }
                    write("[");
                    if (e.kids.size() >= 2) {
                        expr(e.kids.get(1), 0);
                    }
                    write("]");
                }
                case Array -> {
                    write("[");
                    for (int i = 0; i < e.kids.size(); i++) {
                        if (i > 0) {
                            write(", ");
                        }
                        expr(e.kids.get(i), 0);
                    }
                    write("]");
                }
                case Map -> {
                    write("{");
                    for (int i = 0; i + 1 < e.kids.size(); i += 2) {
                        if (i > 0) {
                            write(", ");
                        }
                        expr(e.kids.get(i), 0);
                        write(": ");
                        expr(e.kids.get(i + 1), 0);
                    }
                    write("}");
                }
                case StructLit -> {
                    write(e.text);
                    typeArgs(e.typeArgs);
                    write(" {");
                    if (!e.names.isEmpty()) {
                        write(" ");
                        for (int i = 0; i < e.names.size(); i++) {
                            if (i > 0) {
                                write(", ");
                            }
                            write(e.names.get(i));
                            write(": ");
                            if (i < e.kids.size()) {
                                expr(e.kids.get(i), 0);
                            }
                        }
                        write(" ");
                    }
                    write("}");
                }
                case Range -> {
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 0);
                    }
                    write(e.text.isEmpty() ? (e.booleanValue ? "..=" : "..") : e.text);
                    if (e.kids.size() >= 2) {
                        expr(e.kids.get(1), 0);
                    }
                }
                case Try -> {
                    write("try ");
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                    }
                }
                case Await -> {
                    write("await ");
                    if (!e.kids.isEmpty()) {
                        expr(e.kids.getFirst(), 7);
                    }
                }
                case Lambda -> {
                    if (e.lambda != null) {
                        fnInline(e.lambda);
                    } else {
                        write("fn () {}");
                    }
                }
            }
        }

        void blockOpen() {
            write("{\n");
            indent++;
        }

        void blockClose(boolean newlineAfter) {
            indent--;
            pad();
            write("}");
            if (newlineAfter) {
                line();
            }
        }

        void stmts(List<Stmt> body) {
            for (Stmt s : body) {
                stmt(s);
            }
        }

        void endStmt(Stmt s) {
            write(";");
            if (opts.keepComments && s.trailingComment != null && !s.trailingComment.isEmpty()) {
                write(" ");
                write(s.trailingComment);
            }
            write("\n");
        }

        void stmt(Stmt s) {
            if (opts.keepComments) {
                emitComments(s.leadingComments);
            }
            switch (s.kind) {
                case Comment -> {
                }
                case Expr -> {
                    pad();
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case Var, Const -> {
                    pad();
                    write(s.kind == Stmt.Kind.Const ? "const " : "var ");
                    write(s.name);
                    if (!s.typeName.isEmpty()) {
                        write(": ");
                        write(s.typeName);
                    }
                    write(" = ");
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case Assign -> {
                    pad();
                    write(s.name);
                    write(" ");
                    write(s.op);
                    write(" ");
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case FieldAssign -> {
                    pad();
                    expr(s.target, 0);
                    write(".");
                    write(s.name);
                    write(" ");
                    write(s.op);
                    write(" ");
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case IndexAssign -> {
                    pad();
                    if (s.target != null && s.target.kind == Expr.Kind.Index && s.target.kids.size() >= 2) {
                        expr(s.target.kids.getFirst(), 7);
                        write("[");
                        expr(s.target.kids.get(1), 0);
                        write("]");
                    } else {
                        expr(s.target, 0);
                    }
                    write(" ");
                    write(s.op);
                    write(" ");
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case Return -> {
                    pad();
                    write("return");
                    if (s.hasExpr) {
                        write(" ");
                        expr(s.expr, 0);
                    }
                    endStmt(s);
                }
                case If -> {
                    pad();
                    write("if (");
                    expr(s.expr, 0);
                    write(") ");
                    blockOpen();
                    stmts(s.body);
                    blockClose(false);
                    if (!s.elseBody.isEmpty()) {
                        if (s.elseBody.size() == 1 && s.elseBody.getFirst().kind == Stmt.Kind.If) {
                            Stmt cur = s.elseBody.getFirst();
                            while (true) {
                                write(" elif (");
                                expr(cur.expr, 0);
                                write(") ");
                                blockOpen();
                                stmts(cur.body);
                                blockClose(false);
                                if (cur.elseBody.size() == 1 && cur.elseBody.getFirst().kind == Stmt.Kind.If) {
                                    cur = cur.elseBody.getFirst();
                                    continue;
                                }
                                if (!cur.elseBody.isEmpty()) {
                                    write(" else ");
                                    blockOpen();
                                    stmts(cur.elseBody);
                                    blockClose(false);
                                }
                                break;
                            }
                        } else {
                            write(" else ");
                            blockOpen();
                            stmts(s.elseBody);
                            blockClose(false);
                        }
                    }
                    line();
                }
                case While -> {
                    pad();
                    write("while (");
                    expr(s.expr, 0);
                    write(") ");
                    blockOpen();
                    stmts(s.body);
                    blockClose(true);
                }
                case For -> {
                    pad();
                    write("for (");
                    write(s.name);
                    write(" in ");
                    expr(s.expr, 0);
                    write(") ");
                    blockOpen();
                    stmts(s.body);
                    blockClose(true);
                }
                case Match -> {
                    pad();
                    write("match ");
                    expr(s.expr, 0);
                    write(" {\n");
                    indent++;
                    for (MatchArm arm : s.arms) {
                        pad();
                        switch (arm.pat) {
                            case Wildcard -> write("_");
                            case Int -> write(Long.toString(arm.number));
                            case Float -> {
                                if (!arm.text.isEmpty()) {
                                    write(arm.text);
                                } else {
                                    write(Double.toString(arm.real).replaceAll("\\.0$", ""));
                                }
                            }
                            case String -> write(escapeString(arm.text));
                            case Bool -> write(Boolean.toString(arm.booleanValue));
                            case Variant -> {
                                write(arm.name);
                                if (!arm.fieldNames.isEmpty()) {
                                    write("(");
                                    for (int i = 0; i < arm.fieldNames.size(); i++) {
                                        if (i > 0) {
                                            write(", ");
                                        }
                                        write(arm.fieldNames.get(i));
                                        if (i < arm.binds.size() && !arm.binds.get(i).isEmpty()) {
                                            write(": ");
                                            write(arm.binds.get(i));
                                        }
                                    }
                                    write(")");
                                } else if (!arm.binds.isEmpty()) {
                                    write("(");
                                    for (int i = 0; i < arm.binds.size(); i++) {
                                        if (i > 0) {
                                            write(", ");
                                        }
                                        write(arm.binds.get(i));
                                    }
                                    write(")");
                                }
                            }
                        }
                        write(" {\n");
                        indent++;
                        stmts(arm.body);
                        indent--;
                        writeln("}");
                    }
                    indent--;
                    writeln("}");
                }
                case Pass -> {
                    pad();
                    write("pass");
                    endStmt(s);
                }
                case Break -> {
                    pad();
                    write("break");
                    endStmt(s);
                }
                case Continue -> {
                    pad();
                    write("continue");
                    endStmt(s);
                }
                case Throw -> {
                    pad();
                    write("throw ");
                    expr(s.expr, 0);
                    endStmt(s);
                }
                case Do -> {
                    pad();
                    write("do ");
                    blockOpen();
                    stmts(s.body);
                    blockClose(false);
                    if (!s.name.isEmpty()) {
                        write(" catch ");
                        write(s.name);
                        write(" ");
                        blockOpen();
                        stmts(s.elseBody);
                        blockClose(false);
                    }
                    line();
                }
            }
        }

        void fnInline(FnDecl fn) {
            if (fn.isAsync) {
                write("async ");
            }
            write("fn (");
            for (int i = 0; i < fn.params.size(); i++) {
                if (i > 0) {
                    write(", ");
                }
                write(fn.params.get(i));
                if (i < fn.paramTypes.size() && !fn.paramTypes.get(i).isEmpty()) {
                    write(": ");
                    write(fn.paramTypes.get(i));
                }
            }
            write(")");
            if (fn.throwsEx) {
                write(" throws");
            }
            if (!fn.returnType.isEmpty()) {
                write(": ");
                write(fn.returnType);
            }
            write(" ");
            blockOpen();
            stmts(fn.body);
            blockClose(false);
        }

        void visPrefix(Vis v, boolean isPub) {
            if (v == Vis.Private || !isPub) {
                write("private ");
            } else if (v == Vis.Protected) {
                write("protected ");
            }
        }

        void fn(FnDecl fn) {
            if (fn.isTest) {
                writeln("@test");
            }
            if (fn.isDeprecated) {
                writeln("@deprecated");
            }
            if (fn.isConstexpr) {
                writeln("@constexpr");
            }
            if (fn.isUfcs) {
                writeln("@ufcs");
            }
            pad();
            if (fn.isAbstract) {
                write("abstract ");
            }
            if (fn.isFinal) {
                write("final ");
            }
            visPrefix(fn.vis, fn.isPub);
            if (fn.isAsync) {
                write("async ");
            }
            write("fn ");
            write(fn.name);
            typeParams(fn.typeParams);
            write("(");
            for (int i = 0; i < fn.params.size(); i++) {
                if (i > 0) {
                    write(", ");
                }
                write(fn.params.get(i));
                if (i < fn.paramTypes.size() && !fn.paramTypes.get(i).isEmpty()) {
                    write(": ");
                    write(fn.paramTypes.get(i));
                }
            }
            write(")");
            if (fn.throwsEx) {
                write(" throws");
            }
            if (!fn.returnType.isEmpty()) {
                write(": ");
                write(fn.returnType);
            }
            if (fn.isAbstract) {
                write(";\n");
                return;
            }
            write(" ");
            blockOpen();
            stmts(fn.body);
            blockClose(true);
        }

        void signal(SignalDecl sig) {
            pad();
            if (!sig.isPub) {
                write("private ");
            }
            write("signal ");
            write(sig.name);
            write("(");
            for (int i = 0; i < sig.params.size(); i++) {
                if (i > 0) {
                    write(", ");
                }
                write(sig.params.get(i));
            }
            write(");\n");
        }

        void importDecl(ImportDecl im) {
            pad();
            if (im.isFrom) {
                write("from ");
                if (!im.path.isEmpty()) {
                    write(im.path.getFirst());
                }
                write(" import ");
                for (int i = 1; i < im.path.size(); i++) {
                    if (i > 1) {
                        write(".");
                    }
                    write(im.path.get(i));
                }
            } else {
                write("import ");
                for (int i = 0; i < im.path.size(); i++) {
                    if (i > 0) {
                        write(".");
                    }
                    write(im.path.get(i));
                }
            }
            if (!im.alias.isEmpty()) {
                write(" as ");
                write(im.alias);
            }
            write(";\n");
        }

        void structDeclPrint(StructDecl s) {
            pad();
            if (!s.isPub) {
                write("private ");
            }
            write(s.isData ? "data " : "struct ");
            write(s.name);
            typeParams(s.typeParams);
            if (!s.implTraits.isEmpty()) {
                write(" impl ");
                for (int i = 0; i < s.implTraits.size(); i++) {
                    if (i > 0) {
                        write(", ");
                    }
                    write(s.implTraits.get(i));
                }
            }
            write(" {\n");
            indent++;
            for (int i = 0; i < s.fields.size(); i++) {
                pad();
                if (i < s.fieldVis.size()) {
                    Vis v = s.fieldVis.get(i);
                    if (v == Vis.Private) {
                        write("private ");
                    } else if (v == Vis.Protected) {
                        write("protected ");
                    }
                }
                if (i < s.fieldOptional.size() && Boolean.TRUE.equals(s.fieldOptional.get(i))) {
                    write("@optional ");
                }
                write(s.fields.get(i));
                if (i < s.fieldTypes.size() && !s.fieldTypes.get(i).isEmpty()) {
                    write(": ");
                    write(s.fieldTypes.get(i));
                }
                write(";\n");
            }
            for (SignalDecl sig : s.signals) {
                signal(sig);
            }
            for (FnDecl m : s.methods) {
                line();
                fn(m);
            }
            indent--;
            writeln("}");
        }

        void classDeclPrint(ClassDecl c) {
            pad();
            if (c.isAbstract) {
                write("abstract ");
            }
            if (c.isFinal) {
                write("final ");
            }
            if (!c.isPub) {
                write("private ");
            }
            write("class ");
            write(c.name);
            typeParams(c.typeParams);
            if (!c.parent.isEmpty()) {
                write(" extends ");
                write(c.parent);
            }
            if (!c.implTraits.isEmpty()) {
                write(" impl ");
                for (int i = 0; i < c.implTraits.size(); i++) {
                    if (i > 0) {
                        write(", ");
                    }
                    write(c.implTraits.get(i));
                }
            }
            write(" {\n");
            indent++;
            for (ClassField f : c.fields) {
                pad();
                if (f.vis == Vis.Private) {
                    write("private ");
                } else if (f.vis == Vis.Protected) {
                    write("protected ");
                }
                if (f.optional) {
                    write("@optional ");
                }
                write("var ");
                write(f.name);
                if (!f.type.isEmpty()) {
                    write(": ");
                    write(f.type);
                }
                if (f.hasDefault) {
                    write(" = ");
                    expr(f.defaultValue, 0);
                }
                write(";\n");
            }
            for (SignalDecl sig : c.signals) {
                signal(sig);
            }
            for (FnDecl m : c.methods) {
                line();
                fn(m);
            }
            for (NestedImpl ti : c.traitImpls) {
                line();
                pad();
                write("impl ");
                write(ti.traitName);
                typeParams(ti.typeParams);
                write(" {\n");
                indent++;
                for (FnDecl m : ti.methods) {
                    fn(m);
                }
                indent--;
                writeln("}");
            }
            indent--;
            writeln("}");
        }

        void traitDeclPrint(TraitDecl t) {
            pad();
            if (!t.isPub) {
                write("private ");
            }
            write("trait ");
            write(t.name);
            typeParams(t.typeParams);
            write(" {\n");
            indent++;
            for (TraitMethod m : t.methods) {
                pad();
                write("fn ");
                write(m.name);
                write("(");
                for (int i = 0; i < m.params.size(); i++) {
                    if (i > 0) {
                        write(", ");
                    }
                    write(m.params.get(i));
                    if (i < m.paramTypes.size() && !m.paramTypes.get(i).isEmpty()) {
                        write(": ");
                        write(m.paramTypes.get(i));
                    }
                }
                write(")");
                if (m.throwsEx) {
                    write(" throws");
                }
                if (!m.returnType.isEmpty()) {
                    write(": ");
                    write(m.returnType);
                }
                write(";\n");
            }
            for (SignalDecl sig : t.signals) {
                signal(sig);
            }
            indent--;
            writeln("}");
        }

        void enumDeclPrint(EnumDecl e) {
            pad();
            if (!e.isPub) {
                write("private ");
            }
            write("enum ");
            write(e.name);
            write(" {\n");
            indent++;
            for (int i = 0; i < e.variants.size(); i++) {
                pad();
                EnumVariant v = e.variants.get(i);
                write(v.name);
                if (!v.fieldNames.isEmpty()) {
                    write("(");
                    for (int f = 0; f < v.fieldNames.size(); f++) {
                        if (f > 0) {
                            write(", ");
                        }
                        write(v.fieldNames.get(f));
                    }
                    write(")");
                } else if (v.arity > 0) {
                    write("(");
                    for (int a = 0; a < v.arity; a++) {
                        if (a > 0) {
                            write(", ");
                        }
                        write("_");
                    }
                    write(")");
                }
                if (i + 1 < e.variants.size()) {
                    write(",");
                }
                line();
            }
            indent--;
            writeln("}");
        }

        void implDeclPrint(ImplDecl im) {
            pad();
            write("impl");
            typeParams(im.typeParams);
            write(" ");
            if (!im.traitName.isEmpty()) {
                write(im.traitName);
                write(" for ");
                write(im.typeName);
            } else {
                write(im.typeName);
            }
            write(" {\n");
            indent++;
            for (FnDecl m : im.methods) {
                fn(m);
            }
            indent--;
            writeln("}");
        }

        void emitComments(List<String> comments) {
            if (!opts.keepComments || comments == null) {
                return;
            }
            for (String c : comments) {
                pad();
                write(c);
                write("\n");
            }
        }

        void emitItems(
                List<OrderedItem> order,
                List<ImportDecl> imports,
                List<FnDecl> fns,
                List<StructDecl> structs,
                List<ClassDecl> classes,
                List<TraitDecl> traits,
                List<EnumDecl> enums,
                List<ImplDecl> impls,
                List<SignalDecl> signals,
                List<ModDecl> mods,
                List<String> trailingComments
        ) {
            ItemKind prev = ItemKind.Import;
            boolean first = true;
            for (OrderedItem it : order) {
                boolean blank = opts.blankBetweenItems && !first
                        && !(prev == ItemKind.Import && it.kind == ItemKind.Import);
                if (blank) {
                    line();
                }
                first = false;
                prev = it.kind;
                emitComments(it.leadingComments);
                switch (it.kind) {
                    case Import -> {
                        if (it.index < imports.size()) {
                            importDecl(imports.get(it.index));
                        }
                    }
                    case Fn -> {
                        if (it.index < fns.size()) {
                            fn(fns.get(it.index));
                        }
                    }
                    case Struct -> {
                        if (it.index < structs.size()) {
                            structDeclPrint(structs.get(it.index));
                        }
                    }
                    case Class -> {
                        if (it.index < classes.size()) {
                            classDeclPrint(classes.get(it.index));
                        }
                    }
                    case Trait -> {
                        if (it.index < traits.size()) {
                            traitDeclPrint(traits.get(it.index));
                        }
                    }
                    case Enum -> {
                        if (it.index < enums.size()) {
                            enumDeclPrint(enums.get(it.index));
                        }
                    }
                    case Impl -> {
                        if (it.index < impls.size()) {
                            implDeclPrint(impls.get(it.index));
                        }
                    }
                    case Signal -> {
                        if (it.index < signals.size()) {
                            signal(signals.get(it.index));
                        }
                    }
                    case Mod -> {
                        if (it.index < mods.size()) {
                            modDeclPrint(mods.get(it.index));
                        }
                    }
                }
            }
            if (opts.keepComments && trailingComments != null && !trailingComments.isEmpty()) {
                if (!first) {
                    line();
                }
                emitComments(trailingComments);
            }
        }

        void program(Program p) {
            emitItems(p.items, p.imports, p.fns, p.structs, p.classes, p.traits, p.enums,
                    p.impls, p.signals, p.mods, p.trailingComments);
        }

        void modDeclPrint(ModDecl m) {
            pad();
            if (!m.isPub) {
                write("private ");
            }
            write("mod ");
            write(m.name);
            write(" {\n");
            indent++;
            emitItems(m.items, m.imports, m.fns, m.structs, m.classes, m.traits, m.enums,
                    m.impls, m.signals, m.mods, m.trailingComments);
            indent--;
            writeln("}");
        }
    }
}

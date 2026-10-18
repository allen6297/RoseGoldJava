package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GodotScript {

    static final class Param {
        String name = "";
        String type = "";
    }

    static final class Member {
        String name = "";
        String type = "";
        String returns = "";
        String literal = "";
        String structName = "";
        boolean exported;
        boolean data;
        final List<Param> params = new ArrayList<>();
    }

    static final class Model {
        String className = "";
        String base = "";
        String source = "";
        final List<Member> methods = new ArrayList<>();
        final List<Member> vars = new ArrayList<>();
        final List<Member> signals = new ArrayList<>();
        final List<Member> structs = new ArrayList<>();
    }

    private GodotScript() {
    }

    static Model model(Checker checker) {
        if (checker == null || checker.program == null) {
            return null;
        }
        List<ClassDecl> hits = new ArrayList<>();
        for (ClassDecl c : checker.program.classes) {
            if (engineBase(checker, c.name) != null) {
                hits.add(c);
            }
        }
        List<ClassDecl> leaves = new ArrayList<>();
        for (ClassDecl c : hits) {
            boolean parent = false;
            for (ClassDecl other : hits) {
                if (other == c) {
                    continue;
                }
                if (extendsType(checker, other.name, c.name)) {
                    parent = true;
                    break;
                }
            }
            if (!parent) {
                leaves.add(c);
            }
        }
        if (leaves.size() != 1) {
            return null;
        }
        ClassDecl script = leaves.getFirst();
        String base = engineBase(checker, script.name);
        if (base == null) {
            return null;
        }
        Model model = new Model();
        model.className = script.name;
        model.base = base;
        model.source = checker.file == null ? "" : checker.file;
        for (FnDecl fn : script.methods) {
            if (fn.isAbstract || fn.isStatic) {
                continue;
            }
            Member m = new Member();
            m.name = fn.name;
            m.returns = fn.returnType == null ? "" : fn.returnType;
            for (int i = 0; i < fn.params.size(); i++) {
                String name = fn.params.get(i);
                if ("self".equals(name)) {
                    continue;
                }
                Param p = new Param();
                p.name = name;
                p.type = i < fn.paramTypes.size() && fn.paramTypes.get(i) != null ? fn.paramTypes.get(i) : "";
                m.params.add(p);
            }
            model.methods.add(m);
        }
        for (ClassField f : script.fields) {
            Member v = new Member();
            v.name = f.name;
            v.type = f.type == null ? "" : f.type;
            v.literal = jsonLiteral(f.defaultValue);
            v.exported = f.exported;
            model.vars.add(v);
        }
        for (StructDecl st : checker.program.structs) {
            if (st.fields.isEmpty()) {
                Member row = new Member();
                row.structName = st.name;
                row.data = st.isData;
                model.structs.add(row);
                continue;
            }
            for (int i = 0; i < st.fields.size(); i++) {
                Member row = new Member();
                row.structName = st.name;
                row.data = st.isData;
                row.name = st.fields.get(i);
                row.type = i < st.fieldTypes.size() && st.fieldTypes.get(i) != null ? st.fieldTypes.get(i) : "";
                model.structs.add(row);
            }
        }
        for (SignalDecl sig : script.signals) {
            Member s = new Member();
            s.name = sig.name;
            for (String name : sig.params) {
                Param p = new Param();
                p.name = name == null ? "" : name;
                s.params.add(p);
            }
            model.signals.add(s);
        }
        return model;
    }

    static String engineBase(Checker checker, String name) {
        String cur = name;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && !cur.isEmpty() && seen.add(cur)) {
            String parent = checker.classParents.get(cur);
            if (parent == null || parent.isEmpty()) {
                return null;
            }
            parent = Types.typeHead(parent);
            if (reachesObject(checker, parent)) {
                return parent;
            }
            cur = parent;
        }
        return null;
    }

    private static boolean reachesObject(Checker checker, String name) {
        String cur = name;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && !cur.isEmpty() && seen.add(cur)) {
            if ("Object".equals(cur)) {
                return true;
            }
            String parent = checker.classParents.get(cur);
            if (parent == null || parent.isEmpty()) {
                return false;
            }
            cur = Types.typeHead(parent);
        }
        return false;
    }

    static boolean extendsType(Checker checker, String name, String ancestor) {
        String cur = name;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && !cur.isEmpty() && seen.add(cur)) {
            String parent = checker.classParents.get(cur);
            if (parent == null || parent.isEmpty()) {
                return false;
            }
            parent = Types.typeHead(parent);
            if (parent.equals(ancestor)) {
                return true;
            }
            cur = parent;
        }
        return false;
    }

    static String manifest(Model model, String library) {
        return "{\n" +
                "  \"source\": " + jsonString(model.source) + ",\n" +
                "  \"class\": " + jsonString(model.className) + ",\n" +
                "  \"base\": " + jsonString(model.base) + ",\n" +
                "  \"library\": " + jsonString(library == null ? "" : library) + ",\n" +
                "  \"methods\": " + members(model.methods, true) + ",\n" +
                "  \"vars\": " + members(model.vars, false) + ",\n" +
                "  \"signals\": " + members(model.signals, true) + ",\n" +
                "  \"structs\": " + structRows(model.structs) + "\n" +
                "}\n";
    }

    private static String structRows(List<Member> items) {
        StringBuilder out = new StringBuilder();
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            Member m = items.get(i);
            out.append("    {\"struct\": ").append(jsonString(m.structName));
            out.append(", \"name\": ").append(jsonString(m.name));
            out.append(", \"type\": ").append(jsonString(m.type));
            if (m.data) {
                out.append(", \"data\": true");
            }
            out.append('}');
            if (i + 1 < items.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ]");
        return out.toString();
    }

    private static String members(List<Member> items, boolean withParams) {
        StringBuilder out = new StringBuilder();
        out.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            Member m = items.get(i);
            out.append("    {\"name\": ").append(jsonString(m.name));
            if (!m.type.isEmpty()) {
                out.append(", \"type\": ").append(jsonString(m.type));
            }
            if (!m.returns.isEmpty()) {
                out.append(", \"returns\": ").append(jsonString(m.returns));
            }
            if (m.literal != null && !m.literal.isEmpty()) {
                out.append(", \"default\": ").append(m.literal);
            }
            if (m.exported) {
                out.append(", \"export\": true");
            }
            if (withParams) {
                out.append(", \"params\": [");
                for (int p = 0; p < m.params.size(); p++) {
                    if (p > 0) {
                        out.append(", ");
                    }
                    Param param = m.params.get(p);
                    out.append("{\"name\": ").append(jsonString(param.name));
                    if (!param.type.isEmpty()) {
                        out.append(", \"type\": ").append(jsonString(param.type));
                    }
                    out.append('}');
                }
                out.append(']');
            }
            out.append('}');
            if (i + 1 < items.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append("  ]");
        return out.toString();
    }

    static String jsonLiteral(Expr e) {
        if (e == null) {
            return "";
        }
        return switch (e.kind) {
            case Int -> Long.toString(e.number);
            case Float -> Double.toString(e.real);
            case Bool -> Boolean.toString(e.booleanValue);
            case String -> jsonString(e.text);
            default -> "";
        };
    }

    static String jsonString(String s) {
        StringBuilder out = new StringBuilder();
        out.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    static void internDefaults(Checker checker, String className, List<String> strings, Set<String> types) {
        if (className == null || className.isEmpty() || !types.add(className)) {
            return;
        }
        intern(strings, className);
        StructDecl st = checker.allTypes.get(className);
        if (st != null) {
            for (String field : st.fields) {
                intern(strings, field);
            }
        }
        Map<String, Expr> defs = checker.fieldDefaults.get(className);
        if (defs == null) {
            return;
        }
        for (Expr e : defs.values()) {
            internExpr(checker, e, strings, types);
        }
    }

    private static void internExpr(Checker checker, Expr e, List<String> strings, Set<String> types) {
        if (e == null) {
            return;
        }
        if (e.kind == Expr.Kind.String) {
            intern(strings, e.text);
        }
        if (e.kind == Expr.Kind.StructLit) {
            internDefaults(checker, e.text, strings, types);
        }
        for (Expr kid : e.kids) {
            internExpr(checker, kid, strings, types);
        }
    }

    private static void intern(List<String> strings, String s) {
        if (s == null) {
            s = "";
        }
        if (!strings.contains(s)) {
            strings.add(s);
        }
    }
}

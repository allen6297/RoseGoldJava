package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Types {

    private Types() {
    }

    static String typeHead(String ty) {
        if (ty == null) {
            return "";
        }
        int p = ty.indexOf('[');
        return p < 0 ? ty : ty.substring(0, p);
    }

    static List<String> typeArgList(String ty) {
        if (ty == null) {
            return List.of();
        }
        int p = ty.indexOf('[');
        if (p < 0 || ty.charAt(ty.length() - 1) != ']') {
            return List.of();
        }
        String inner = ty.substring(p + 1, ty.length() - 1);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        Runnable flush = () -> {
            String s = cur.toString();
            int a = -1;
            int b = -1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) != ' ') {
                    a = i;
                    break;
                }
            }
            for (int i = s.length() - 1; i >= 0; i--) {
                if (s.charAt(i) != ' ') {
                    b = i;
                    break;
                }
            }
            if (a >= 0) {
                out.add(s.substring(a, b + 1));
            }
            cur.setLength(0);
        };
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                flush.run();
                continue;
            }
            cur.append(c);
        }
        if (!cur.isEmpty()) {
            flush.run();
        }
        return out;
    }

    static String typeApply(String name, List<String> args) {
        if (args == null || args.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('[');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(args.get(i));
        }
        out.append(']');
        return out.toString();
    }

    static String substType(String ty, Map<String, String> env) {
        if (ty == null || ty.isEmpty()) {
            return ty == null ? "" : ty;
        }
        List<String> args = typeArgList(ty);
        String head = typeHead(ty);
        if (args.isEmpty()) {
            String mapped = env.get(head);
            return mapped == null ? head : mapped;
        }
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            out.add(substType(a, env));
        }
        String mapped = env.get(head);
        if (mapped != null) {
            head = mapped;
        }
        return typeApply(head, out);
    }

    static Map<String, String> typeEnvFrom(List<TypeParam> params, List<String> args) {
        Map<String, String> env = new LinkedHashMap<>();
        if (args.size() == params.size()) {
            for (int i = 0; i < params.size(); i++) {
                env.put(params.get(i).name, args.get(i));
            }
        } else {
            for (TypeParam p : params) {
                env.put(p.name, p.name);
            }
        }
        return env;
    }

    static String selfApplied(String name, List<TypeParam> params) {
        if (params.isEmpty()) {
            return name;
        }
        List<String> args = new ArrayList<>(params.size());
        for (TypeParam p : params) {
            args.add(p.name);
        }
        return typeApply(name, args);
    }

    static String appliedParent(Map<String, String> classParents, Map<String, StructDecl> allTypes, String applied) {
        String pit = classParents.get(typeHead(applied));
        if (pit == null) {
            return "";
        }
        List<TypeParam> params = List.of();
        StructDecl st = allTypes.get(typeHead(applied));
        if (st != null) {
            params = st.typeParams;
        }
        return substType(pit, typeEnvFrom(params, typeArgList(applied)));
    }

    static boolean isHostModule(String name) {
        return name.equals("checks") || name.equals("process") || name.equals("__math")
                || name.equals("__str") || name.equals("__io") || name.equals("__uuid")
                || name.equals("__time") || name.equals("__path") || name.equals("__json")
                || name.equals("__regex") || name.equals("__ui");
    }

    static boolean isStdlibChild(String name) {
        return Stdlib.isStdlibChild(name);
    }

    static boolean isCrateStdlib(String name) {
        return Stdlib.isCrateStdlib(name);
    }

    static String canonicalStdlibName(String name) {
        return Stdlib.canonicalStdlibName(name);
    }

    static String stdlibImportHint(String name, String fromFile) {
        return Stdlib.importHint(name, fromFile);
    }

    static StdlibExport lookupStdlibExport(String name, String fromFile) {
        return Stdlib.lookup(name, fromFile);
    }

    static final class StdlibExport {
        String crate = "";
        String kind = "";
    }
}

package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TypeChecker {
    private final Checker env;
    private final List<Map<String, String>> scopes = new ArrayList<>();
    private String currentReturn = "";
    private String currentSelf = "";
    private String currentSuper = "";
    private boolean currentThrows;
    private boolean currentAsync;
    private boolean inTry;
    private boolean throwingTry;
    private Set<String> genericParams = new LinkedHashSet<>();
    private Map<String, List<String>> genericBounds = new LinkedHashMap<>();

    TypeChecker(Checker env) {
        this.env = env;
    }

    private static final class BoundHit {
        TraitMethod m;
        String trait = "";

        boolean ok() {
            return m != null;
        }
    }

    private void fail(int line, int col, String msg) {
        env.recordDiag("type error", env.file, line, col, msg);
    }

    private boolean isLocal(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSubclassOf(String child, String ancestor) {
        if (child.equals(ancestor)) {
            return true;
        }
        String current = Types.typeHead(child);
        String want = Types.typeHead(ancestor);
        if (current.equals(want)) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            String pit = env.classParents.get(current);
            if (pit == null) {
                break;
            }
            if (Types.typeHead(pit).equals(want)) {
                return true;
            }
            current = Types.typeHead(pit);
        }
        return false;
    }

    private void checkAccess(Vis vis, String kind, String name, String definedOn, int line, int col) {
        if (vis == Vis.Pub) {
            return;
        }
        boolean okSame = !currentSelf.isEmpty() && currentSelf.equals(definedOn);
        boolean okProt = vis == Vis.Protected && !currentSelf.isEmpty() && isSubclassOf(currentSelf, definedOn);
        if (okSame || okProt) {
            return;
        }
        fail(line, col, kind + " '" + name + "' is " + vis.visName());
    }

    private void requireTry(boolean throwsEx, String name, int line, int col) {
        if (!throwsEx) {
            return;
        }
        if (inTry) {
            throwingTry = true;
        } else {
            fail(line, col, "call to throwing function '" + name + "' requires 'try'");
        }
    }

    private static boolean known(String ty) {
        return ty != null && !ty.isEmpty() && !ty.equals("None") && !ty.equals("Self") && !ty.equals("Void");
    }

    private static boolean builtinHead(String head) {
        return head.equals("Int") || head.equals("Float") || head.equals("String") || head.equals("Str")
                || head.equals("Bool") || head.equals("Void") || head.equals("Array") || head.equals("Map")
                || head.equals("Range") || head.equals("None") || head.equals("Self") || head.equals("Future");
    }

    private void checkTypeName(String ty, int line, int col) {
        if (ty == null || ty.isEmpty()) {
            return;
        }
        if (isFnType(ty)) {
            List<String> params = new ArrayList<>();
            String[] ret = {""};
            if (!parseFnType(ty, params, ret)) {
                return;
            }
            for (String p : params) {
                checkTypeName(p, line, col);
            }
            checkTypeName(ret[0], line, col);
            return;
        }
        List<String> args = Types.typeArgList(ty);
        for (String a : args) {
            checkTypeName(a, line, col);
        }
        String head = Types.typeHead(ty);
        if (head.isEmpty() || head.equals("_") || genericParams.contains(head) || builtinHead(head) || head.equals("Fn")) {
            return;
        }
        if (env.findStruct(head) != null || env.findTrait(head) != null || env.findEnum(head) != null
                || env.allTypes.containsKey(head) || env.traits.containsKey(head)) {
            return;
        }
        Types.StdlibExport ex = Types.lookupStdlibExport(head, env.file);
        if (ex != null) {
            String word = "type";
            if (ex.kind.equals("trait")) {
                word = "trait";
            } else if (ex.kind.equals("fn")) {
                word = "function";
            }
            fail(line, col, "undefined " + word + " '" + head + "'" + Types.stdlibImportHint(head, env.file));
            return;
        }
        fail(line, col, "undefined type '" + head + "'");
    }

    private static boolean isArrayTy(String ty) {
        return ty.equals("Array") || (ty.length() > 6 && ty.startsWith("Array[") && ty.endsWith("]"));
    }

    private static String arrayElem(String ty) {
        if (ty.length() > 6 && ty.startsWith("Array[") && ty.endsWith("]")) {
            return ty.substring(6, ty.length() - 1);
        }
        return "";
    }

    private static boolean isFutureTy(String ty) {
        return ty.equals("Future") || (ty.length() > 7 && ty.startsWith("Future[") && ty.endsWith("]"));
    }

    private static String futureElem(String ty) {
        if (ty.length() > 7 && ty.startsWith("Future[") && ty.endsWith("]")) {
            return ty.substring(7, ty.length() - 1);
        }
        return "";
    }

    private static String wrapAsyncReturn(FnDecl fn, String inner) {
        String t = inner == null || inner.isEmpty() ? "Void" : inner;
        if (fn.isAsync) {
            return "Future[" + t + "]";
        }
        return t;
    }

    private static boolean isMapTy(String ty) {
        return ty.equals("Map") || (ty.length() > 4 && ty.startsWith("Map[") && ty.endsWith("]"));
    }

    private static boolean isNumeric(String ty) {
        return ty.equals("Int") || ty.equals("Float");
    }

    private static boolean isString(String ty) {
        return ty.equals("String") || ty.equals("Str");
    }

    private static boolean isFnType(String ty) {
        return ty.startsWith("fn(");
    }

    private static boolean isCallable(String ty) {
        return ty.equals("Fn") || isFnType(ty);
    }

    private static boolean parseFnType(String ty, List<String> params, String[] ret) {
        params.clear();
        ret[0] = "";
        if (!isFnType(ty)) {
            return false;
        }
        int i = 3;
        int depth = 1;
        StringBuilder cur = new StringBuilder();
        Runnable flush = () -> {
            String s = cur.toString();
            int a = -1;
            int b = -1;
            for (int n = 0; n < s.length(); n++) {
                if (s.charAt(n) != ' ') {
                    a = n;
                    break;
                }
            }
            for (int n = s.length() - 1; n >= 0; n--) {
                if (s.charAt(n) != ' ') {
                    b = n;
                    break;
                }
            }
            if (a >= 0) {
                params.add(s.substring(a, b + 1));
            }
            cur.setLength(0);
        };
        while (i < ty.length()) {
            char c = ty.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
                cur.append(c);
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0 && c == ')') {
                    flush.run();
                    i++;
                    if (i < ty.length() && ty.charAt(i) == ':') {
                        i++;
                        String r = ty.substring(i);
                        int a = -1;
                        int b = -1;
                        for (int n = 0; n < r.length(); n++) {
                            if (r.charAt(n) != ' ') {
                                a = n;
                                break;
                            }
                        }
                        for (int n = r.length() - 1; n >= 0; n--) {
                            if (r.charAt(n) != ' ') {
                                b = n;
                                break;
                            }
                        }
                        ret[0] = a < 0 ? "Void" : r.substring(a, b + 1);
                    } else {
                        ret[0] = "Void";
                    }
                    return true;
                }
                cur.append(c);
            } else if (c == ',' && depth == 1) {
                flush.run();
            } else {
                cur.append(c);
            }
            i++;
        }
        return false;
    }

    private static String encodeFnType(List<String> params, String ret) {
        StringBuilder t = new StringBuilder("fn(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                t.append(", ");
            }
            t.append(params.get(i));
        }
        t.append("):");
        t.append(ret == null || ret.isEmpty() ? "Void" : ret);
        return t.toString();
    }

    private static String encodeFnDecl(FnDecl fn) {
        for (String p : fn.paramTypes) {
            if (p.isEmpty()) {
                return "Fn";
            }
        }
        return encodeFnType(fn.paramTypes, wrapAsyncReturn(fn, fn.returnType));
    }

    private boolean isTraitType(String ty) {
        return env.findTrait(ty) != null;
    }

    private boolean compatible(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if ((a.equals("String") && b.equals("Str")) || (a.equals("Str") && b.equals("String"))) {
            return true;
        }
        if ((a.equals("Int") && b.equals("Float")) || (a.equals("Float") && b.equals("Int"))) {
            return true;
        }
        if ((a.equals("Fn") && isFnType(b)) || (b.equals("Fn") && isFnType(a))) {
            return true;
        }
        if (isFnType(a) && isFnType(b)) {
            List<String> pa = new ArrayList<>();
            List<String> pb = new ArrayList<>();
            String[] ra = {""};
            String[] rb = {""};
            if (!parseFnType(a, pa, ra) || !parseFnType(b, pb, rb)) {
                return false;
            }
            if (pa.size() != pb.size()) {
                return false;
            }
            for (int i = 0; i < pa.size(); i++) {
                if (!compatible(pa.get(i), pb.get(i))) {
                    return false;
                }
            }
            return compatible(ra[0], rb[0]);
        }
        if (isArrayTy(a) && isArrayTy(b)) {
            if (a.equals("Array")) {
                return true;
            }
            if (b.equals("Array")) {
                return false;
            }
            return compatible(arrayElem(a), arrayElem(b));
        }
        if (isFutureTy(a) && isFutureTy(b)) {
            if (a.equals("Future")) {
                return true;
            }
            if (b.equals("Future")) {
                return false;
            }
            return compatible(futureElem(a), futureElem(b));
        }
        if (isMapTy(a) && isMapTy(b)) {
            List<String> aa = Types.typeArgList(a);
            List<String> ab = Types.typeArgList(b);
            if (aa.isEmpty()) {
                return true;
            }
            if (ab.isEmpty()) {
                return false;
            }
            if (aa.size() != 2 || ab.size() != 2) {
                return false;
            }
            return compatible(aa.get(0), ab.get(0)) && compatible(aa.get(1), ab.get(1));
        }
        if (isTraitType(a)) {
            if (isTraitType(b) && compatibleTrait(a, b)) {
                return true;
            }
            return implementsBound(b, a);
        }
        String ha = Types.typeHead(a);
        String hb = Types.typeHead(b);
        if (ha.equals(hb) && (a.indexOf('[') >= 0 || b.indexOf('[') >= 0)) {
            List<String> aa = Types.typeArgList(a);
            List<String> ab = Types.typeArgList(b);
            if (aa.isEmpty()) {
                return true;
            }
            if (ab.isEmpty()) {
                return false;
            }
            if (aa.size() != ab.size()) {
                return false;
            }
            for (int i = 0; i < aa.size(); i++) {
                if (!compatible(aa.get(i), ab.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean assignable(String expect, String got, Expr e) {
        if (compatible(expect, got)) {
            return true;
        }
        if (e == null) {
            return false;
        }
        if (isArrayTy(expect) && !expect.equals("Array") && got.equals("Array") && e.kind == Expr.Kind.Array) {
            return true;
        }
        return isMapTy(expect) && !expect.equals("Map") && got.equals("Map") && e.kind == Expr.Kind.Map;
    }

    private static String numericResult(String l, String r) {
        return (l.equals("Float") || r.equals("Float")) ? "Float" : "Int";
    }

    private void bind(String name, String ty) {
        if (!scopes.isEmpty()) {
            scopes.getLast().put(name, ty == null ? "" : ty);
        }
    }

    private String lookup(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String ty = scopes.get(i).get(name);
            if (ty != null) {
                return ty;
            }
        }
        if (!name.equals("self") && !currentSelf.isEmpty()) {
            String ft = fieldType(currentSelf, name);
            if (!ft.isEmpty() || hasField(currentSelf, name)) {
                return ft;
            }
            if (env.lookupTypeSignal(currentSelf, name) != null) {
                return "Signal";
            }
        }
        return "";
    }

    private boolean hasField(String typeName, String name) {
        String current = Types.typeHead(typeName);
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            StructDecl tit = env.allTypes.get(current);
            if (tit != null) {
                for (String f : tit.fields) {
                    if (f.equals(name)) {
                        return true;
                    }
                }
            }
            String pit = env.classParents.get(current);
            if (pit == null) {
                break;
            }
            current = Types.typeHead(pit);
        }
        return false;
    }

    private String fieldType(String typeName, String name) {
        String applied = typeName;
        Set<String> seen = new HashSet<>();
        while (!applied.isEmpty()) {
            String current = Types.typeHead(applied);
            if (!seen.add(current)) {
                break;
            }
            StructDecl st = env.allTypes.get(current);
            if (st != null) {
                for (int i = 0; i < st.fields.size(); i++) {
                    if (!st.fields.get(i).equals(name)) {
                        continue;
                    }
                    if (i < st.fieldTypes.size()) {
                        Map<String, String> tenv = Types.typeEnvFrom(st.typeParams, Types.typeArgList(applied));
                        return Types.substType(st.fieldTypes.get(i), tenv);
                    }
                    return "";
                }
            }
            applied = Types.appliedParent(env.classParents, env.allTypes, applied);
        }
        return "";
    }

    private boolean defined(String name) {
        if (name.equals("self") || name.equals("super")) {
            return true;
        }
        if (!lookup(name).isEmpty() || (!currentSelf.isEmpty()
                && (hasField(currentSelf, name) || env.lookupTypeSignal(currentSelf, name) != null))) {
            return true;
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            if (scopes.get(i).containsKey(name)) {
                return true;
            }
        }
        if (env.findLocalFn(name) != null || env.fns.containsKey(name)) {
            return true;
        }
        if (name.equals("print") || name.equals("len") || name.equals("assert") || name.equals("argv")
                || name.equals("argv_len")) {
            return true;
        }
        if (env.allTypes.containsKey(name) || env.structs.containsKey(name) || env.enums.containsKey(name)
                || env.traits.containsKey(name) || env.signalArity.containsKey(name)) {
            return true;
        }
        if (env.findEnum(name) != null || env.findStruct(name) != null) {
            return true;
        }
        return env.findModuleBind(name) != null || Types.isHostModule(name);
    }

    private String tryModulePath(Expr e) {
        if (e.kind == Expr.Kind.Var) {
            return env.findModuleBind(e.text);
        }
        if (e.kind != Expr.Kind.Member || e.kids.isEmpty()) {
            return null;
        }
        String parent = tryModulePath(e.kids.getFirst());
        if (parent == null) {
            return null;
        }
        Checker.LoadedMod lit = env.loaded.get(parent);
        if (lit == null) {
            return null;
        }
        boolean internal = env.currentModule.equals(parent);
        Map<String, String> map = internal ? lit.modules : lit.exportMods;
        return map.get(e.text);
    }

    private String inferArray(Expr e) {
        if (e.kids.isEmpty()) {
            return "Array";
        }
        String elem = "";
        for (Expr kid : e.kids) {
            String t = infer(kid);
            if (!known(t)) {
                return "Array";
            }
            if (elem.isEmpty()) {
                elem = t;
            } else if (elem.equals("Int") && t.equals("Float")) {
                elem = t;
            } else if (!(elem.equals(t) || compatible(elem, t))) {
                return "Array";
            }
        }
        return "Array[" + elem + "]";
    }

    private String infer(Expr e) {
        if (e == null || e.kind == null) {
            return "";
        }
        switch (e.kind) {
            case Int:
                return "Int";
            case Float:
                return "Float";
            case String:
                return "String";
            case Bool:
                return "Bool";
            case Var: {
                String ty = lookup(e.text);
                if (known(ty)) {
                    return ty;
                }
                FnDecl fn = env.findLocalFn(e.text);
                if (fn != null) {
                    return encodeFnDecl(fn);
                }
                FnDecl top = env.fns.get(e.text);
                if (top != null) {
                    return encodeFnDecl(top);
                }
                EnumDecl en = env.findEnum(e.text);
                if (en != null) {
                    return en.name;
                }
                return "";
            }
            case Unary:
                if (e.text.equals("!")) {
                    return "Bool";
                }
                if (e.text.equals("-")) {
                    String ty = infer(e.kids.getFirst());
                    return isNumeric(ty) ? ty : "";
                }
                return "";
            case Binary: {
                String l = infer(e.kids.get(0));
                String r = infer(e.kids.get(1));
                if (!known(l) || !known(r)) {
                    return "";
                }
                if (e.text.equals("+") && isNumeric(l) && isNumeric(r)) {
                    return numericResult(l, r);
                }
                if (e.text.equals("+") && isString(l) && isString(r)) {
                    return "String";
                }
                if ((e.text.equals("-") || e.text.equals("*") || e.text.equals("/") || e.text.equals("%"))
                        && isNumeric(l) && isNumeric(r)) {
                    return numericResult(l, r);
                }
                if (e.text.equals("==") || e.text.equals("!=") || e.text.equals("<") || e.text.equals(">")
                        || e.text.equals("<=") || e.text.equals(">=") || e.text.equals("&&") || e.text.equals("||")) {
                    return "Bool";
                }
                return "";
            }
            case Call:
                return inferCallExpr(e);
            case Lambda:
                if (e.lambda != null) {
                    return encodeFnDecl(e.lambda);
                }
                return "Fn";
            case MethodCall:
                return inferMethod(e);
            case Member: {
                if (!e.kids.isEmpty() && e.kids.getFirst().kind == Expr.Kind.Var
                        && env.findEnum(e.kids.getFirst().text) != null) {
                    return e.kids.getFirst().text;
                }
                String obj = e.kids.isEmpty() ? "" : infer(e.kids.getFirst());
                if (isArrayTy(obj) || isMapTy(obj) || obj.equals("String") || obj.equals("Str")) {
                    if (e.text.equals("len")) {
                        return "Int";
                    }
                    return "";
                }
                if (known(obj)) {
                    if (env.lookupTypeSignal(obj, e.text) != null) {
                        return "Signal";
                    }
                    return fieldType(obj, e.text);
                }
                return "";
            }
            case Index: {
                String obj = infer(e.kids.getFirst());
                if (isString(obj)) {
                    return "String";
                }
                if (isArrayTy(obj)) {
                    return arrayElem(obj);
                }
                return "";
            }
            case Array:
                return inferArray(e);
            case Map:
                return "Map";
            case Range:
                return "Range";
            case StructLit:
                return inferStructLit(e);
            case Try:
                return e.kids.isEmpty() ? "" : infer(e.kids.getFirst());
            case Await: {
                if (e.kids.isEmpty()) {
                    return "";
                }
                String ty = infer(e.kids.getFirst());
                if (!known(ty) || !isFutureTy(ty)) {
                    return "";
                }
                return futureElem(ty);
            }
            default:
                return "";
        }
    }

    private String inferStructLit(Expr e) {
        StructDecl st = env.findStruct(e.text);
        if (st == null) {
            return e.text;
        }
        if (st.typeParams.isEmpty()) {
            return e.text;
        }
        if (!e.typeArgs.isEmpty()) {
            return Types.typeApply(e.text, e.typeArgs);
        }
        Map<String, String> tenv = new LinkedHashMap<>();
        Set<String> prev = genericParams;
        genericParams = new LinkedHashSet<>(prev);
        for (TypeParam p : st.typeParams) {
            genericParams.add(p.name);
        }
        for (int i = 0; i < e.names.size() && i < e.kids.size(); i++) {
            unifyType(st.typeOfField(e.names.get(i)), infer(e.kids.get(i)), tenv);
        }
        genericParams = prev;
        List<String> args = new ArrayList<>(st.typeParams.size());
        for (TypeParam p : st.typeParams) {
            String it = tenv.get(p.name);
            if (it == null || it.equals(p.name) || !known(it)) {
                return e.text;
            }
            args.add(it);
        }
        return Types.typeApply(e.text, args);
    }

    private String inferCallExpr(Expr e) {
        if (e.text.isEmpty() && !e.kids.isEmpty()) {
            if (e.kids.getFirst().kind == Expr.Kind.Lambda && e.kids.getFirst().lambda != null) {
                FnDecl fn = e.kids.getFirst().lambda;
                if (fn.typeParams.isEmpty()) {
                    return wrapAsyncReturn(fn, fn.returnType);
                }
                List<Expr> args = rest(e.kids);
                Map<String, String> tenv = inferEnv(fn.typeParams, e.typeArgs, fn.paramTypes, args);
                return wrapAsyncReturn(fn, Types.substType(fn.returnType, tenv));
            }
            String ty = infer(e.kids.getFirst());
            if (isFnType(ty)) {
                List<String> params = new ArrayList<>();
                String[] ret = {""};
                if (parseFnType(ty, params, ret)) {
                    return ret[0];
                }
            }
            return "";
        }
        {
            String ty = lookup(e.text);
            if (isFnType(ty)) {
                List<String> params = new ArrayList<>();
                String[] ret = {""};
                if (parseFnType(ty, params, ret)) {
                    return ret[0];
                }
            }
        }
        switch (e.text) {
            case "len", "argv_len" -> {
                return "Int";
            }
            case "argv" -> {
                return "String";
            }
            case "print", "assert" -> {
                return "Void";
            }
        }
        FnDecl fn = env.findLocalFn(e.text);
        if (fn != null) {
            if (fn.typeParams.isEmpty()) {
                return wrapAsyncReturn(fn, fn.returnType);
            }
            Map<String, String> tenv = inferEnv(fn.typeParams, e.typeArgs, fn.paramTypes, e.kids);
            return wrapAsyncReturn(fn, Types.substType(fn.returnType, tenv));
        }
        return inferCall(e.text);
    }

    private String inferCall(String name) {
        switch (name) {
            case "len", "argv_len" -> {
                return "Int";
            }
            case "argv" -> {
                return "String";
            }
            case "print", "assert" -> {
                return "Void";
            }
        }
        FnDecl fn = env.findLocalFn(name);
        if (fn != null) {
            return wrapAsyncReturn(fn, fn.returnType);
        }
        if (!currentSelf.isEmpty()) {
            Checker.MethodHit found = env.lookupMethod(currentSelf, name);
            if (found.fn != null) {
                return wrapAsyncReturn(found.fn, found.fn.returnType);
            }
        }
        return "";
    }

    private String inferMethod(Expr e) {
        Expr recv = e.kids.getFirst();
        if (recv.kind == Expr.Kind.Var && recv.text.equals("Future")) {
            if (e.text.equals("all")) {
                if (e.kids.size() < 2) {
                    return "Future[Array]";
                }
                String argTy = infer(e.kids.get(1));
                if (isArrayTy(argTy)) {
                    String elem = arrayElem(argTy);
                    if (isFutureTy(elem)) {
                        String inner = futureElem(elem);
                        if (inner.isEmpty()) {
                            return "Future[Array]";
                        }
                        return "Future[Array[" + inner + "]]";
                    }
                    if (elem.isEmpty() || argTy.equals("Array")) {
                        return "Future[Array]";
                    }
                }
                return "Future[Array]";
            }
            if (e.text.equals("race")) {
                if (e.kids.size() < 2) {
                    return "Future";
                }
                String argTy = infer(e.kids.get(1));
                if (isArrayTy(argTy)) {
                    String elem = arrayElem(argTy);
                    if (isFutureTy(elem)) {
                        String inner = futureElem(elem);
                        return inner.isEmpty() ? "Future" : ("Future[" + inner + "]");
                    }
                }
                return "Future";
            }
        }
        if (recv.kind == Expr.Kind.Var && Types.isHostModule(recv.text)) {
            if (recv.text.equals("process") && e.text.equals("argv")) {
                return "String";
            }
            if (recv.text.equals("process") && e.text.equals("argc")) {
                return "Int";
            }
            switch (recv.text) {
                case "__math" -> {
                    if (e.text.equals("sin") || e.text.equals("cos") || e.text.equals("atan2") || e.text.equals("sqrt")
                            || e.text.equals("powf") || e.text.equals("to_float") || e.text.equals("floor")
                            || e.text.equals("ceil") || e.text.equals("random")) {
                        return "Float";
                    }
                    return "Int";
                }
                case "__str" -> {
                    return switch (e.text) {
                        case "length", "find" -> "Int";
                        case "contains", "starts_with", "ends_with", "is_empty" -> "Bool";
                        case "split" -> "Array[String]";
                        default -> "String";
                    };
                }
                case "__io" -> {
                    return switch (e.text) {
                        case "exists", "remove" -> "Bool";
                        case "read_text" -> "String";
                        case "read_lines" -> "Array[String]";
                        default -> "Void";
                    };
                }
                case "__uuid" -> {
                    if (e.text.equals("valid")) {
                        return "Bool";
                    }
                    return "String";
                }
                case "__time" -> {
                    if (e.text.equals("now")) {
                        return "Int";
                    }
                    if (e.text.equals("delay")) {
                        return "Future[Void]";
                    }
                    return "Void";
                }
                case "__path" -> {
                    return "String";
                }
                case "__json" -> {
                    if (e.text.equals("valid")) {
                        return "Bool";
                    }
                    if (e.text.equals("stringify")) {
                        return "String";
                    }
                    return "";
                }
                case "__regex" -> {
                    return switch (e.text) {
                        case "valid", "is_match" -> "Bool";
                        case "find" -> "Int";
                        case "find_match", "replace" -> "String";
                        case "captures", "findall", "split" -> "Array[String]";
                        default -> "Void";
                    };
                }
                case "__ui" -> {
                    return switch (e.text) {
                        case "open" -> "Int";
                        case "title", "backend", "platform", "key_text", "clipboard_get" -> "String";
                        case "alive", "poll", "mouse_down", "take_click", "take_right_click", "take_key",
                             "take_scroll" -> "Bool";
                        case "next_frame" -> "Future[Bool]";
                        case "width", "height", "count", "mouse_x", "mouse_y", "key_code", "scroll_dx", "scroll_dy",
                             "text_width", "font_height", "image_width", "image_height" -> "Int";
                        default -> "Void";
                    };
                }
            }
            return "Void";
        }
        if (recv.kind == Expr.Kind.Var && env.signalArity.containsKey(recv.text)) {
            return "Void";
        }
        if (recv.kind == Expr.Kind.Var && !currentSelf.isEmpty() && env.lookupTypeSignal(currentSelf, recv.text) != null) {
            return "Void";
        }
        String recvTy = infer(recv);
        if (recvTy.equals("Signal")) {
            return "Void";
        }
        if (recv.kind == Expr.Kind.Var) {
            String mod = env.findModuleBind(recv.text);
            if (mod != null) {
                Checker.LoadedMod lit = env.loaded.get(mod);
                if (lit != null) {
                    FnDecl eit = lit.exports.get(e.text);
                    if (eit != null) {
                        return wrapAsyncReturn(eit, eit.returnType);
                    }
                }
            }
            if (env.findEnum(recv.text) != null) {
                return recv.text;
            }
        }
        String modPath = tryModulePath(recv);
        if (modPath != null) {
            Checker.LoadedMod lit = env.loaded.get(modPath);
            if (lit != null) {
                FnDecl eit = lit.exports.get(e.text);
                if (eit != null) {
                    return wrapAsyncReturn(eit, eit.returnType);
                }
            }
        }
        String obj = infer(recv);
        if (isArrayTy(obj)) {
            switch (e.text) {
                case "len" -> {
                    return "Int";
                }
                case "pop" -> {
                    return arrayElem(obj);
                }
                case "push" -> {
                    return "Void";
                }
            }
        } else if (isMapTy(obj)) {
            switch (e.text) {
                case "len" -> {
                    return "Int";
                }
                case "has" -> {
                    return "Bool";
                }
                case "keys" -> {
                    return "Array[String]";
                }
                case "insert" -> {
                    return "Void";
                }
                case "remove" -> {
                    return "";
                }
            }
        } else if (isString(obj) && e.text.equals("len")) {
            return "Int";
        } else if (isFutureTy(obj) && e.text.equals("cancel")) {
            return "Void";
        }
        if (known(obj)) {
            BoundHit hit = traitObjectMethod(obj, e.text);
            if (hit.ok()) {
                return Types.substType(hit.m.returnType, traitEnv(hit.trait));
            }
            hit = boundMethod(obj, e.text);
            if (hit.ok()) {
                return Types.substType(hit.m.returnType, traitEnv(hit.trait));
            }
            Checker.MethodHit found = env.lookupMethod(obj, e.text);
            if (found.fn != null) {
                Map<String, String> tenv = envForApplied(recvApplied(obj, found.definedOn));
                if (!found.fn.typeParams.isEmpty()) {
                    List<String> patterns = new ArrayList<>();
                    for (int i = 1; i < found.fn.paramTypes.size(); i++) {
                        patterns.add(found.fn.paramTypes.get(i));
                    }
                    List<Expr> args = rest(e.kids);
                    tenv.putAll(inferEnv(found.fn.typeParams, e.typeArgs, patterns, args));
                }
                return wrapAsyncReturn(found.fn, Types.substType(found.fn.returnType, tenv));
            }
        }
        FnDecl ufcs = env.findUfcs(e.text, recvTy);
        if (ufcs != null) {
            if (ufcs.typeParams.isEmpty()) {
                return wrapAsyncReturn(ufcs, ufcs.returnType);
            }
            List<Expr> vals = new ArrayList<>();
            vals.add(e.kids.isEmpty() ? new Expr() : e.kids.getFirst());
            for (int i = 1; i < e.kids.size(); i++) {
                vals.add(e.kids.get(i));
            }
            Map<String, String> tenv = inferEnv(ufcs.typeParams, e.typeArgs, ufcs.paramTypes, vals);
            return wrapAsyncReturn(ufcs, Types.substType(ufcs.returnType, tenv));
        }
        return "";
    }

    private void checkBinop(Expr e) {
        String l = infer(e.kids.get(0));
        String r = infer(e.kids.get(1));
        if (!known(l) || !known(r)) {
            return;
        }
        switch (e.text) {
            case "+" -> {
                if ((isNumeric(l) && isNumeric(r)) || (isString(l) && isString(r))) {
                    return;
                }
                fail(e.line, e.col, "cannot add " + l + " and " + r);
            }
            case "-", "*", "/", "%" -> {
                if (isNumeric(l) && isNumeric(r)) {
                    return;
                }
                String verb = e.text.equals("-") ? "subtract" : e.text.equals("*") ? "multiply"
                        : e.text.equals("/") ? "divide" : "modulo";
                fail(e.line, e.col, "cannot " + verb + " " + l + " and " + r);
            }
            case "<", ">", "<=", ">=" -> {
                if (isNumeric(l) && isNumeric(r)) {
                    return;
                }
                fail(e.line, e.col, "cannot compare " + l + " and " + r);
            }
        }
    }

    private void checkCompound(String op, String lt, String rt, int line, int col, Expr rhs) {
        if (op == null || op.isEmpty() || op.equals("=")) {
            if (known(lt) && known(rt)) {
                boolean ok = rhs != null ? assignable(lt, rt, rhs) : compatible(lt, rt);
                if (!ok) {
                    fail(line, col, "cannot assign " + rt + " to " + lt);
                }
            }
            return;
        }
        if (!known(lt) || !known(rt)) {
            return;
        }
        String bin = op.substring(0, op.length() - 1);
        if (bin.equals("+")) {
            if ((isNumeric(lt) && isNumeric(rt)) || (isString(lt) && isString(rt))) {
                return;
            }
            fail(line, col, "cannot add " + lt + " and " + rt);
            return;
        }
        if (bin.equals("-") || bin.equals("*") || bin.equals("/")) {
            if (isNumeric(lt) && isNumeric(rt)) {
                return;
            }
            String verb = bin.equals("-") ? "subtract" : bin.equals("*") ? "multiply" : "divide";
            fail(line, col, "cannot " + verb + " " + lt + " and " + rt);
        }
    }

    private void checkIndex(Expr e) {
        String obj = infer(e.kids.get(0));
        String idx = infer(e.kids.get(1));
        if (!known(obj)) {
            return;
        }
        if (isArrayTy(obj) || isString(obj)) {
            if (known(idx) && !idx.equals("Int")) {
                fail(e.line, e.col, "index must be Int");
            }
            return;
        }
        if (isMapTy(obj)) {
            if (known(idx) && !isString(idx)) {
                fail(e.line, e.col, "map key must be String");
            }
            return;
        }
        String with = known(idx) ? idx : "unknown";
        fail(e.line, e.col, "cannot index " + obj + " with " + with);
    }

    private void checkArgTypes(FnDecl fn, List<Expr> args, boolean isMethod, int line, int col) {
        int off = isMethod ? 1 : 0;
        for (int i = 0; i < args.size(); i++) {
            if (i + off >= fn.paramTypes.size()) {
                break;
            }
            String expect = fn.paramTypes.get(i + off);
            String got = infer(args.get(i));
            if (known(expect) && known(got) && !assignable(expect, got, args.get(i))) {
                fail(line, col, "cannot pass " + got + " to '" + fn.name + "', expected " + expect);
            }
            checkArrayElems(expect, args.get(i), line, col);
        }
    }

    private void checkArity(String label, int expected, int got, int line, int col) {
        if (expected != got) {
            fail(line, col, label + " expected " + expected + " args, got " + got);
        }
    }

    private boolean unifyType(String pattern, String got, Map<String, String> tenv) {
        if (pattern.isEmpty() || !known(got)) {
            return true;
        }
        String ph = Types.typeHead(pattern);
        List<String> pa = Types.typeArgList(pattern);
        if (pa.isEmpty() && genericParams.contains(ph)) {
            String it = tenv.get(ph);
            if (it == null || it.equals(ph)) {
                tenv.put(ph, got);
                return true;
            }
            return compatible(it, got);
        }
        if (isArrayTy(pattern) && isArrayTy(got)) {
            String pe = arrayElem(pattern);
            String ge = arrayElem(got);
            if (pe.isEmpty() || ge.isEmpty()) {
                return true;
            }
            return unifyType(pe, ge, tenv);
        }
        if (isMapTy(pattern) && isMapTy(got)) {
            return true;
        }
        String gh = Types.typeHead(got);
        List<String> ga = Types.typeArgList(got);
        if (!ph.equals(gh)) {
            return compatible(pattern, got);
        }
        if (pa.isEmpty() || ga.isEmpty()) {
            return true;
        }
        if (pa.size() != ga.size()) {
            return false;
        }
        for (int i = 0; i < pa.size(); i++) {
            if (!unifyType(pa.get(i), ga.get(i), tenv)) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> inferEnv(List<TypeParam> params, List<String> explicitArgs, List<String> patterns,
            List<Expr> vals) {
        Map<String, String> tenv = new LinkedHashMap<>();
        Set<String> prevParams = genericParams;
        genericParams = new LinkedHashSet<>(prevParams);
        for (TypeParam p : params) {
            genericParams.add(p.name);
        }
        if (!explicitArgs.isEmpty()) {
            tenv = Types.typeEnvFrom(params, explicitArgs);
        }
        int n = Math.min(patterns.size(), vals.size());
        for (int i = 0; i < n; i++) {
            unifyType(patterns.get(i), infer(vals.get(i)), tenv);
        }
        genericParams = prevParams;
        return tenv;
    }

    private String recvApplied(String obj, String definedOn) {
        String applied = obj;
        Set<String> seen = new HashSet<>();
        while (!applied.isEmpty() && seen.add(Types.typeHead(applied))) {
            if (Types.typeHead(applied).equals(Types.typeHead(definedOn))) {
                return applied;
            }
            applied = Types.appliedParent(env.classParents, env.allTypes, applied);
        }
        return definedOn;
    }

    private Map<String, String> envForApplied(String applied) {
        StructDecl st = env.findStruct(applied);
        if (st == null) {
            st = env.allTypes.get(Types.typeHead(applied));
        }
        return Types.typeEnvFrom(st != null ? st.typeParams : List.of(), Types.typeArgList(applied));
    }

    private Map<String, String> traitEnv(String applied) {
        TraitDecl tr = env.findTrait(applied);
        return Types.typeEnvFrom(tr != null ? tr.typeParams : List.of(), Types.typeArgList(applied));
    }

    private boolean compatibleTrait(String impl, String bound) {
        if (!Types.typeHead(impl).equals(Types.typeHead(bound))) {
            return false;
        }
        List<String> ia = Types.typeArgList(impl);
        List<String> ba = Types.typeArgList(bound);
        if (ia.isEmpty() || ba.isEmpty()) {
            return true;
        }
        if (ia.size() != ba.size()) {
            return false;
        }
        for (int i = 0; i < ia.size(); i++) {
            if (!compatible(ia.get(i), ba.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean implementsBound(String concrete, String bound) {
        if (isTraitType(concrete) && compatibleTrait(concrete, bound)) {
            return true;
        }
        String applied = concrete;
        Set<String> seen = new HashSet<>();
        while (!applied.isEmpty() && seen.add(Types.typeHead(applied))) {
            String head = Types.typeHead(applied);
            for (Checker.TraitImplInfo im : env.traitImpls) {
                if (!Types.typeHead(im.typeName).equals(head)) {
                    continue;
                }
                Map<String, String> tenv = envForApplied(applied);
                Set<String> prev = genericParams;
                genericParams = new LinkedHashSet<>(prev);
                for (TypeParam p : im.typeParams) {
                    genericParams.add(p.name);
                }
                unifyType(im.typeName, applied, tenv);
                genericParams = prev;
                if (compatibleTrait(Types.substType(im.traitName, tenv), bound)) {
                    return true;
                }
            }
            applied = Types.appliedParent(env.classParents, env.allTypes, applied);
        }
        return false;
    }

    private void checkTraitBound(String bound, int line, int col) {
        String head = Types.typeHead(bound);
        TraitDecl tr = env.findTrait(head);
        if (tr == null) {
            fail(line, col, "undefined trait '" + head + "'" + Types.stdlibImportHint(head, env.file));
            return;
        }
        List<String> args = Types.typeArgList(bound);
        if (args.isEmpty()) {
            return;
        }
        if (tr.typeParams.isEmpty()) {
            fail(line, col, "'" + head + "' does not take type arguments");
        } else if (args.size() != tr.typeParams.size()) {
            fail(line, col, "'" + head + "' expected " + tr.typeParams.size() + " type argument(s), got " + args.size());
        }
    }

    private void checkTypeArgCount(List<TypeParam> params, List<String> args, int line, int col, String who) {
        if (args.isEmpty()) {
            return;
        }
        if (params.isEmpty()) {
            fail(line, col, "'" + who + "' does not take type arguments");
            return;
        }
        if (args.size() != params.size()) {
            fail(line, col, "'" + who + "' expected " + params.size() + " type argument(s), got " + args.size());
        }
    }

    private void checkBounds(List<TypeParam> params, Map<String, String> tenv, int line, int col) {
        for (TypeParam p : params) {
            String eit = tenv.get(p.name);
            if (eit == null || eit.equals(p.name) || !known(eit)) {
                continue;
            }
            for (String bound : p.bounds) {
                if (implementsBound(eit, bound)) {
                    continue;
                }
                if (genericParams.contains(Types.typeHead(eit))) {
                    List<String> git = genericBounds.get(Types.typeHead(eit));
                    boolean ok = false;
                    if (git != null) {
                        for (String b : git) {
                            if (compatibleTrait(b, bound)) {
                                ok = true;
                                break;
                            }
                        }
                    }
                    if (ok) {
                        continue;
                    }
                }
                fail(line, col, "type " + eit + " does not implement " + bound);
            }
        }
    }

    private BoundHit boundMethod(String typeName, String name) {
        List<String> it = genericBounds.get(Types.typeHead(typeName));
        if (it == null) {
            return new BoundHit();
        }
        for (String traitName : it) {
            TraitDecl tr = env.findTrait(traitName);
            if (tr == null) {
                continue;
            }
            for (TraitMethod m : tr.methods) {
                if (m.name.equals(name)) {
                    BoundHit hit = new BoundHit();
                    hit.m = m;
                    hit.trait = traitName;
                    return hit;
                }
            }
        }
        return new BoundHit();
    }

    private BoundHit traitObjectMethod(String typeName, String name) {
        TraitDecl tr = env.findTrait(typeName);
        if (tr == null) {
            return new BoundHit();
        }
        for (TraitMethod m : tr.methods) {
            if (m.name.equals(name)) {
                BoundHit hit = new BoundHit();
                hit.m = m;
                hit.trait = typeName;
                return hit;
            }
        }
        return new BoundHit();
    }

    private void checkArrayElems(String expectTy, Expr e, int line, int col) {
        if (e == null || !isArrayTy(expectTy) || e.kind != Expr.Kind.Array) {
            return;
        }
        String elem = arrayElem(expectTy);
        if (!known(elem)) {
            return;
        }
        for (Expr kid : e.kids) {
            String got = infer(kid);
            if (known(got) && !assignable(elem, got, kid)) {
                fail(line, col, "cannot pass " + got + " to " + expectTy + ", expected " + elem);
            }
        }
    }

    private void checkBoundArgs(BoundHit hit, String recv, List<Expr> args, int line, int col) {
        int expect = hit.m.params.isEmpty() ? 0 : hit.m.params.size() - 1;
        checkArity(recv + "." + hit.m.name, expect, args.size(), line, col);
        Map<String, String> tenv = traitEnv(hit.trait);
        for (int i = 0; i < args.size(); i++) {
            if (i + 1 >= hit.m.paramTypes.size()) {
                break;
            }
            String expectTy = Types.substType(hit.m.paramTypes.get(i + 1), tenv);
            String got = infer(args.get(i));
            if (known(expectTy) && known(got) && !assignable(expectTy, got, args.get(i))) {
                fail(line, col, "cannot pass " + got + " to '" + hit.m.name + "', expected " + expectTy);
            }
        }
        requireTry(hit.m.throwsEx, hit.m.name, line, col);
    }

    private void mergeMethodEnv(FnDecl fn, Expr e, List<Expr> args, Map<String, String> tenv) {
        if (fn.typeParams.isEmpty()) {
            if (!e.typeArgs.isEmpty()) {
                fail(e.line, e.col, "'" + fn.name + "' does not take type arguments");
            }
            return;
        }
        checkTypeArgCount(fn.typeParams, e.typeArgs, e.line, e.col, fn.name);
        List<String> patterns = new ArrayList<>();
        for (int i = 1; i < fn.paramTypes.size(); i++) {
            patterns.add(fn.paramTypes.get(i));
        }
        tenv.putAll(inferEnv(fn.typeParams, e.typeArgs, patterns, args));
        checkBounds(fn.typeParams, tenv, e.line, e.col);
    }

    private void checkArgTypesEnv(FnDecl fn, List<Expr> args, boolean isMethod, int line, int col,
            Map<String, String> tenv) {
        int off = isMethod ? 1 : 0;
        for (int i = 0; i < args.size(); i++) {
            if (i + off >= fn.paramTypes.size()) {
                break;
            }
            String expect = Types.substType(fn.paramTypes.get(i + off), tenv);
            String got = infer(args.get(i));
            if (known(expect) && known(got) && !assignable(expect, got, args.get(i))) {
                fail(line, col, "cannot pass " + got + " to '" + fn.name + "', expected " + expect);
            }
            checkArrayElems(expect, args.get(i), line, col);
        }
    }

    private void checkFnTypeCall(String ty, List<Expr> args, int line, int col, String label) {
        List<String> params = new ArrayList<>();
        String[] ret = {""};
        if (!parseFnType(ty, params, ret)) {
            return;
        }
        checkArity(label, params.size(), args.size(), line, col);
        for (int i = 0; i < args.size() && i < params.size(); i++) {
            String got = infer(args.get(i));
            if (known(params.get(i)) && known(got) && !assignable(params.get(i), got, args.get(i))) {
                fail(line, col, "cannot pass " + got + " to '" + label + "', expected " + params.get(i));
            }
            checkArrayElems(params.get(i), args.get(i), line, col);
        }
    }

    private static List<Expr> rest(List<Expr> kids) {
        if (kids.size() <= 1) {
            return List.of();
        }
        return new ArrayList<>(kids.subList(1, kids.size()));
    }

    private void checkCall(Expr e) {
        String name = e.text;
        if (e.text.isEmpty()) {
            if (!e.kids.isEmpty()) {
                if (e.kids.getFirst().kind == Expr.Kind.Lambda && e.kids.getFirst().lambda != null) {
                    FnDecl fn = e.kids.getFirst().lambda;
                    List<Expr> args = rest(e.kids);
                    checkArity("<fn>", fn.params.size(), args.size(), e.line, e.col);
                    checkTypeArgCount(fn.typeParams, e.typeArgs, e.line, e.col, "<fn>");
                    if (!fn.typeParams.isEmpty()) {
                        Map<String, String> tenv = inferEnv(fn.typeParams, e.typeArgs, fn.paramTypes, args);
                        checkBounds(fn.typeParams, tenv, e.line, e.col);
                        checkArgTypesEnv(fn, args, false, e.line, e.col, tenv);
                    } else {
                        if (!e.typeArgs.isEmpty()) {
                            fail(e.line, e.col, "'<fn>' does not take type arguments");
                        }
                        checkArgTypes(fn, args, false, e.line, e.col);
                    }
                    requireTry(fn.throwsEx, "<fn>", e.line, e.col);
                    return;
                }
                String ty = infer(e.kids.getFirst());
                if (isFnType(ty)) {
                    checkFnTypeCall(ty, rest(e.kids), e.line, e.col, "<fn>");
                    return;
                }
                if (known(ty) && !isCallable(ty)) {
                    fail(e.line, e.col, "can only call a function");
                }
            }
            return;
        }
        {
            String ty = lookup(name);
            if (isFnType(ty)) {
                checkFnTypeCall(ty, e.kids, e.line, e.col, name);
                return;
            }
            if (ty.equals("Fn")) {
                return;
            }
            if (known(ty) && !isCallable(ty)) {
                fail(e.line, e.col, "can only call a function");
                return;
            }
        }
        int n = e.kids.size();
        switch (name) {
            case "print" -> {
                return;
            }
            case "len" -> {
                checkArity("len", 1, n, e.line, e.col);
                if (n == 1) {
                    String ty = infer(e.kids.getFirst());
                    if (known(ty) && !isArrayTy(ty) && !isMapTy(ty) && !isString(ty)) {
                        fail(e.line, e.col, "len expects Array, String, or Map");
                    }
                }
                return;
            }
            case "assert" -> {
                checkArity("assert", 1, n, e.line, e.col);
                return;
            }
            case "argv" -> {
                checkArity("argv", 1, n, e.line, e.col);
                return;
            }
            case "argv_len" -> {
                checkArity("argv_len", 0, n, e.line, e.col);
                return;
            }
        }
        FnDecl fn = env.findLocalFn(name);
        if (fn != null) {
            checkArity(name, fn.params.size(), n, e.line, e.col);
            checkTypeArgCount(fn.typeParams, e.typeArgs, e.line, e.col, name);
            if (!fn.typeParams.isEmpty()) {
                Map<String, String> tenv = inferEnv(fn.typeParams, e.typeArgs, fn.paramTypes, e.kids);
                checkBounds(fn.typeParams, tenv, e.line, e.col);
                checkArgTypesEnv(fn, e.kids, false, e.line, e.col, tenv);
            } else {
                if (!e.typeArgs.isEmpty()) {
                    fail(e.line, e.col, "'" + name + "' does not take type arguments");
                }
                checkArgTypes(fn, e.kids, false, e.line, e.col);
            }
            requireTry(fn.throwsEx, name, e.line, e.col);
            return;
        }
        if (!currentSelf.isEmpty()) {
            BoundHit hit = boundMethod(currentSelf, name);
            if (hit.ok()) {
                checkBoundArgs(hit, currentSelf, e.kids, e.line, e.col);
                return;
            }
            Checker.MethodHit found = env.lookupMethod(currentSelf, name);
            if (found.fn != null) {
                checkAccess(found.fn.vis, "method", name, found.definedOn, e.line, e.col);
                int expect = found.fn.params.isEmpty() ? 0 : found.fn.params.size() - 1;
                checkArity(currentSelf + "." + name, expect, n, e.line, e.col);
                Map<String, String> tenv = envForApplied(recvApplied(currentSelf, found.definedOn));
                mergeMethodEnv(found.fn, e, e.kids, tenv);
                checkArgTypesEnv(found.fn, e.kids, true, e.line, e.col, tenv);
                requireTry(found.fn.throwsEx, name, e.line, e.col);
                return;
            }
        }
        fail(e.line, e.col, "unknown function '" + name + "'" + Types.stdlibImportHint(name, env.file));
    }

    private void checkHostCall(String mod, String name, int n, int line, int col) {
        switch (mod) {
            case "checks" -> {
                if (name.equals("eq") || name.equals("neq") || name.equals("eq_string")) {
                    checkArity(mod + "." + name, 2, n, line, col);
                    return;
                }
                if (name.equals("that") || name.equals("truthy")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    return;
                }
                fail(line, col, "unknown function checks." + name);
            }
            case "process" -> {
                if (name.equals("argv")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    return;
                }
                if (name.equals("argc")) {
                    checkArity(mod + "." + name, 0, n, line, col);
                    return;
                }
                fail(line, col, "unknown function process." + name);
            }
            case "__math" -> {
                switch (name) {
                    case "pow", "powf", "atan2" -> {
                        checkArity(mod + "." + name, 2, n, line, col);
                        return;
                    }
                    case "random" -> {
                        checkArity(mod + "." + name, 0, n, line, col);
                        return;
                    }
                    case "rand_int", "sin", "cos", "sqrt", "to_int", "to_float", "floor", "ceil" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __math." + name);
            }
            case "__str" -> {
                switch (name) {
                    case "slice", "replace" -> {
                        checkArity(mod + "." + name, 3, n, line, col);
                        return;
                    }
                    case "repeat", "contains", "starts_with", "ends_with", "split", "find" -> {
                        checkArity(mod + "." + name, 2, n, line, col);
                        return;
                    }
                    case "length", "is_empty", "upper", "lower", "trim" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __str." + name);
                return;
            }
            case "__io" -> {
                switch (name) {
                    case "write_text" -> {
                        checkArity(mod + "." + name, 2, n, line, col);
                        requireTry(true, name, line, col);
                        return;
                    }
                    case "read_text", "read_lines" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        requireTry(true, name, line, col);
                        return;
                    }
                    case "exists", "remove" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __io." + name);
            }
            case "__uuid" -> {
                switch (name) {
                    case "v4" -> {
                        checkArity(mod + "." + name, 0, n, line, col);
                        return;
                    }
                    case "parse" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        requireTry(true, name, line, col);
                        return;
                    }
                    case "valid" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __uuid." + name);
            }
            case "__time" -> {
                if (name.equals("now")) {
                    checkArity(mod + "." + name, 0, n, line, col);
                    return;
                }
                if (name.equals("sleep") || name.equals("delay")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    return;
                }
                fail(line, col, "unknown function __time." + name);
            }
            case "__path" -> {
                if (name.equals("join")) {
                    checkArity(mod + "." + name, 2, n, line, col);
                    return;
                }
                if (name.equals("parent") || name.equals("stem")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    return;
                }
                fail(line, col, "unknown function __path." + name);
            }
            case "__json" -> {
                if (name.equals("parse")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    requireTry(true, name, line, col);
                    return;
                }
                if (name.equals("valid") || name.equals("stringify")) {
                    checkArity(mod + "." + name, 1, n, line, col);
                    return;
                }
                fail(line, col, "unknown function __json." + name);
            }
            case "__regex" -> {
                switch (name) {
                    case "replace" -> {
                        checkArity(mod + "." + name, 3, n, line, col);
                        return;
                    }
                    case "is_match", "find", "find_match", "captures", "findall", "split" -> {
                        checkArity(mod + "." + name, 2, n, line, col);
                        return;
                    }
                    case "valid" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __regex." + name);
            }
            case "__ui" -> {
                switch (name) {
                    case "open" -> {
                        checkArity(mod + "." + name, 4, n, line, col);
                        requireTry(true, name, line, col);
                        return;
                    }
                    case "fill", "line", "stroke_rect", "image_rgb" -> {
                        checkArity(mod + "." + name, 6, n, line, col);
                        return;
                    }
                    case "fill_round", "stroke_round" -> {
                        checkArity(mod + "." + name, 7, n, line, col);
                        return;
                    }
                    case "clip_push", "text" -> {
                        checkArity(mod + "." + name, 5, n, line, col);
                        return;
                    }
                    case "image" -> {
                        checkArity(mod + "." + name, 4, n, line, col);
                        return;
                    }
                    case "text_width", "image_width", "image_height", "clipboard_set", "close", "show", "hide", "poll",
                         "next_frame", "alive", "title", "width", "height", "present", "mouse_x", "mouse_y",
                         "mouse_down",
                         "take_click", "take_right_click", "take_key", "key_code", "key_text", "take_scroll",
                         "scroll_dx",
                         "scroll_dy", "clip_pop" -> {
                        checkArity(mod + "." + name, 1, n, line, col);
                        return;
                    }
                    case "set_size", "feed_click", "feed_right_click", "feed_key", "feed_scroll", "feed_mouse" -> {
                        checkArity(mod + "." + name, 3, n, line, col);
                        return;
                    }
                    case "set_title", "clear", "set_frame", "feed_down", "cursor" -> {
                        checkArity(mod + "." + name, 2, n, line, col);
                        return;
                    }
                    case "run", "count", "backend", "platform", "wait", "font_height", "clipboard_get" -> {
                        checkArity(mod + "." + name, 0, n, line, col);
                        return;
                    }
                }
                fail(line, col, "unknown function __ui." + name);
            }
        }
    }

    private boolean checkUfcs(Expr e, int n) {
        String recvTy = e.kids.isEmpty() ? "" : infer(e.kids.getFirst());
        FnDecl fn = env.findUfcs(e.text, recvTy);
        if (fn == null) {
            return false;
        }
        List<Expr> args = rest(e.kids);
        checkArity(e.text, fn.params.size(), n + 1, e.line, e.col);
        checkTypeArgCount(fn.typeParams, e.typeArgs, e.line, e.col, fn.name);
        if (!fn.typeParams.isEmpty()) {
            List<Expr> vals = new ArrayList<>();
            if (!e.kids.isEmpty()) {
                vals.add(e.kids.getFirst());
            }
            vals.addAll(args);
            Map<String, String> tenv = inferEnv(fn.typeParams, e.typeArgs, fn.paramTypes, vals);
            checkBounds(fn.typeParams, tenv, e.line, e.col);
            if (!fn.paramTypes.isEmpty() && !e.kids.isEmpty()) {
                String expect = Types.substType(fn.paramTypes.getFirst(), tenv);
                String got = infer(e.kids.getFirst());
                if (known(expect) && known(got) && !assignable(expect, got, e.kids.getFirst())) {
                    fail(e.line, e.col, "cannot pass " + got + " to '" + fn.name + "', expected " + expect);
                }
            }
            checkArgTypesEnv(fn, args, true, e.line, e.col, tenv);
        } else {
            if (!fn.paramTypes.isEmpty()) {
                String expect = fn.paramTypes.getFirst();
                String got = infer(e.kids.getFirst());
                if (known(expect) && known(got) && !assignable(expect, got, e.kids.getFirst())) {
                    fail(e.line, e.col, "cannot pass " + got + " to '" + fn.name + "', expected " + expect);
                }
            }
            checkArgTypes(fn, args, true, e.line, e.col);
        }
        requireTry(fn.throwsEx, fn.name, e.line, e.col);
        return true;
    }

    private void checkValueMethod(String obj, Expr e, int n) {
        if (isArrayTy(obj)) {
            if (e.text.equals("len") || e.text.equals("pop")) {
                checkArity(obj + "." + e.text, 0, n, e.line, e.col);
                return;
            }
            if (e.text.equals("push")) {
                checkArity(obj + "." + e.text, 1, n, e.line, e.col);
                if (n == 1 && e.kids.size() >= 2) {
                    String elem = arrayElem(obj);
                    String got = infer(e.kids.get(1));
                    if (known(elem) && known(got) && !assignable(elem, got, e.kids.get(1))) {
                        fail(e.line, e.col, "cannot pass " + got + " to 'push', expected " + elem);
                    }
                    checkArrayElems(elem, e.kids.get(1), e.line, e.col);
                }
                return;
            }
            if (checkUfcs(e, n)) {
                return;
            }
            fail(e.line, e.col, "Array has no method '" + e.text + "'");
            return;
        }
        if (isMapTy(obj)) {
            switch (e.text) {
                case "len", "keys" -> {
                    checkArity(obj + "." + e.text, 0, n, e.line, e.col);
                    return;
                }
                case "has", "remove" -> {
                    checkArity(obj + "." + e.text, 1, n, e.line, e.col);
                    return;
                }
                case "insert" -> {
                    checkArity(obj + "." + e.text, 2, n, e.line, e.col);
                    return;
                }
            }
            if (checkUfcs(e, n)) {
                return;
            }
            fail(e.line, e.col, "Map has no method '" + e.text + "'");
            return;
        }
        if (isString(obj)) {
            if (e.text.equals("len")) {
                checkArity(obj + "." + e.text, 0, n, e.line, e.col);
                return;
            }
            if (checkUfcs(e, n)) {
                return;
            }
            fail(e.line, e.col, "String has no method '" + e.text + "'");
            return;
        }
        if (isFutureTy(obj)) {
            if (e.text.equals("cancel")) {
                checkArity(obj + "." + e.text, 0, n, e.line, e.col);
                return;
            }
            fail(e.line, e.col, "Future has no method '" + e.text + "'");
        }
    }

    private void checkMethod(Expr e) {
        Expr recv = e.kids.getFirst();
        int n = e.kids.isEmpty() ? 0 : e.kids.size() - 1;
        List<Expr> args = rest(e.kids);
        if (recv.kind == Expr.Kind.Var && recv.text.equals("Future")) {
            if (!e.text.equals("all") && !e.text.equals("race")) {
                fail(e.line, e.col, "Future has no method '" + e.text + "'");
                return;
            }
            checkArity("Future." + e.text, 1, n, e.line, e.col);
            if (n == 1) {
                String argTy = infer(e.kids.get(1));
                if (known(argTy) && !isArrayTy(argTy)) {
                    fail(e.line, e.col, "Future." + e.text + " expects Array of Future, got " + argTy);
                } else if (known(argTy) && isArrayTy(argTy)) {
                    String elem = arrayElem(argTy);
                    if (known(elem) && !isFutureTy(elem)) {
                        fail(e.line, e.col, "Future." + e.text + " expects Array of Future, got Array[" + elem + "]");
                    }
                }
            }
            return;
        }
        if (recv.kind == Expr.Kind.Var && recv.text.equals("super")) {
            if (currentSuper.isEmpty()) {
                fail(e.line, e.col, "super is only valid in a method of a class that extends another");
                return;
            }
            Checker.MethodHit found = env.lookupMethod(currentSuper, e.text);
            if (found.fn == null) {
                fail(e.line, e.col, "struct " + currentSuper + " has no method '" + e.text + "'");
                return;
            }
            if (found.fn.isAbstract) {
                fail(e.line, e.col, "cannot call abstract method '" + e.text + "'");
                return;
            }
            checkAccess(found.fn.vis, "method", e.text, found.definedOn, e.line, e.col);
            int expect = found.fn.params.isEmpty() ? 0 : found.fn.params.size() - 1;
            checkArity(currentSuper + "." + e.text, expect, n, e.line, e.col);
            Map<String, String> tenv = envForApplied(recvApplied(currentSuper, found.definedOn));
            mergeMethodEnv(found.fn, e, args, tenv);
            checkArgTypesEnv(found.fn, args, true, e.line, e.col, tenv);
            requireTry(found.fn.throwsEx, e.text, e.line, e.col);
            return;
        }
        if (recv.kind == Expr.Kind.Var) {
            if (!currentSelf.isEmpty()) {
                Integer arity = env.lookupTypeSignal(currentSelf, recv.text);
                if (arity != null) {
                    checkSig(recv.text, arity, e, n);
                    return;
                }
            }
            if (env.signalArity.containsKey(recv.text)) {
                checkSig(recv.text, env.signalArity.get(recv.text), e, n);
                return;
            }
        }
        if (recv.kind == Expr.Kind.Member && !recv.kids.isEmpty()) {
            String objTy = infer(recv.kids.getFirst());
            if (known(objTy)) {
                Integer arity = env.lookupTypeSignal(objTy, recv.text);
                if (arity != null) {
                    checkSig(recv.text, arity, e, n);
                    return;
                }
            }
        }
        if (infer(recv).equals("Signal")) {
            if (e.text.equals("connect") || e.text.equals("disconnect")) {
                checkArity("signal " + e.text, 1, n, e.line, e.col);
                return;
            }
            if (e.text.equals("emit") || e.text.equals("emit_deferred")) {
                return;
            }
            fail(e.line, e.col, "signal has no method '" + e.text + "'");
            return;
        }
        if (recv.kind == Expr.Kind.Var && Types.isHostModule(recv.text)) {
            checkHostCall(recv.text, e.text, n, e.line, e.col);
            return;
        }
        if (recv.kind == Expr.Kind.Var) {
            String mod = env.findModuleBind(recv.text);
            if (mod != null) {
                Checker.LoadedMod lit = env.loaded.get(mod);
                if (lit == null) {
                    fail(e.line, e.col, "unknown module '" + mod + "'");
                    return;
                }
                FnDecl eit = lit.exports.get(e.text);
                if (eit == null) {
                    fail(e.line, e.col, "module '" + recv.text + "' has no export '" + e.text + "'");
                    return;
                }
                checkArity(recv.text + "." + e.text, eit.params.size(), n, e.line, e.col);
                checkArgTypes(eit, args, false, e.line, e.col);
                requireTry(eit.throwsEx, e.text, e.line, e.col);
                return;
            }
            EnumDecl en = env.findEnum(recv.text);
            if (en != null) {
                EnumVariant found = null;
                for (EnumVariant v : en.variants) {
                    if (v.name.equals(e.text)) {
                        found = v;
                        break;
                    }
                }
                if (found == null) {
                    fail(e.line, e.col, "enum " + en.name + " has no variant '" + e.text + "'");
                    return;
                }
                if (n != found.arity) {
                    fail(e.line, e.col, en.name + "." + e.text + " takes " + found.arity + " argument(s)");
                }
                return;
            }
        }
        String modPath = tryModulePath(recv);
        if (modPath != null) {
            Checker.LoadedMod lit = env.loaded.get(modPath);
            if (lit != null) {
                FnDecl eit = lit.exports.get(e.text);
                if (eit == null) {
                    fail(e.line, e.col, "module '" + modPath + "' has no export '" + e.text + "'");
                    return;
                }
                checkArity(modPath + "." + e.text, eit.params.size(), n, e.line, e.col);
                checkArgTypes(eit, args, false, e.line, e.col);
                requireTry(eit.throwsEx, e.text, e.line, e.col);
                return;
            }
        }
        String obj = infer(recv);
        if (isArrayTy(obj) || isMapTy(obj) || isString(obj) || isFutureTy(obj)) {
            checkValueMethod(isString(obj) ? "String" : obj, e, n);
            return;
        }
        if (known(obj)) {
            BoundHit hit = traitObjectMethod(obj, e.text);
            if (hit.ok()) {
                checkBoundArgs(hit, obj, args, e.line, e.col);
                return;
            }
            hit = boundMethod(obj, e.text);
            if (hit.ok()) {
                checkBoundArgs(hit, obj, args, e.line, e.col);
                return;
            }
            Checker.MethodHit found = env.lookupMethod(obj, e.text);
            if (found.fn != null) {
                checkAccess(found.fn.vis, "method", e.text, found.definedOn, e.line, e.col);
                int expect = found.fn.params.isEmpty() ? 0 : found.fn.params.size() - 1;
                checkArity(obj + "." + e.text, expect, n, e.line, e.col);
                Map<String, String> tenv = envForApplied(recvApplied(obj, found.definedOn));
                mergeMethodEnv(found.fn, e, args, tenv);
                checkArgTypesEnv(found.fn, args, true, e.line, e.col, tenv);
                requireTry(found.fn.throwsEx, e.text, e.line, e.col);
                return;
            }
            if (checkUfcs(e, n)) {
                return;
            }
            fail(e.line, e.col, (isTraitType(obj) ? "trait " : "struct ") + obj + " has no method '" + e.text + "'");
            return;
        }
        checkUfcs(e, n);
    }

    private void checkSig(String signal, int expect, Expr e, int n) {
        if (e.text.equals("connect") || e.text.equals("disconnect")) {
            checkArity("signal '" + signal + "' " + e.text, 1, n, e.line, e.col);
            if (n == 1) {
                String ty = infer(e.kids.get(1));
                if (known(ty) && !isCallable(ty)) {
                    fail(e.line, e.col, "signal '" + signal + "' " + e.text + " expects a function");
                }
            }
            return;
        }
        if (e.text.equals("emit") || e.text.equals("emit_deferred")) {
            if (expect != n) {
                fail(e.line, e.col, "signal '" + signal + "' expected " + expect + " argument(s), got " + n);
            }
            return;
        }
        fail(e.line, e.col, "signal '" + signal + "' has no method '" + e.text + "'");
    }

    private void walkExpr(Expr e) {
        if (e == null || e.kind == null) {
            return;
        }
        if (e.kind == Expr.Kind.Try) {
            if (e.kids.isEmpty()) {
                return;
            }
            boolean prev = inTry;
            inTry = true;
            walkExpr(e.kids.getFirst());
            inTry = prev;
            return;
        }
        if (e.kind == Expr.Kind.Await) {
            if (!currentAsync) {
                fail(e.line, e.col, "'await' is only allowed in async functions");
            }
            if (e.kids.isEmpty()) {
                return;
            }
            walkExpr(e.kids.getFirst());
            String ty = infer(e.kids.getFirst());
            if (known(ty) && !isFutureTy(ty)) {
                fail(e.line, e.col, "can only await Future, got " + ty);
            }
            return;
        }
        if (e.kind == Expr.Kind.Lambda) {
            if (e.lambda != null) {
                checkFn(e.lambda, currentSelf);
            }
            return;
        }
        if (e.kind == Expr.Kind.Var) {
            if (!defined(e.text)) {
                fail(e.line, e.col, "undefined variable '" + e.text + "'" + Types.stdlibImportHint(e.text, env.file));
            } else if (!isLocal(e.text) && !currentSelf.isEmpty()) {
                Checker.FieldHit found = env.lookupField(currentSelf, e.text);
                if (!found.definedOn.isEmpty()) {
                    checkAccess(found.vis, "field", e.text, found.definedOn, e.line, e.col);
                }
            }
            return;
        }
        if (e.kind == Expr.Kind.Call) {
            for (Expr kid : e.kids) {
                walkExpr(kid);
            }
            checkCall(e);
            return;
        }
        if (e.kind == Expr.Kind.MethodCall) {
            for (int i = 0; i < e.kids.size(); i++) {
                if (i == 0 && e.kids.getFirst().kind == Expr.Kind.Var && e.kids.getFirst().text.equals("Future")) {
                    continue;
                }
                walkExpr(e.kids.get(i));
            }
            checkMethod(e);
            return;
        }
        if (e.kind == Expr.Kind.Member) {
            for (Expr kid : e.kids) {
                walkExpr(kid);
            }
            if (!e.kids.isEmpty() && e.kids.getFirst().kind == Expr.Kind.Var) {
                String recv = e.kids.getFirst().text;
                EnumDecl en = env.findEnum(recv);
                if (en != null) {
                    boolean ok = false;
                    for (EnumVariant v : en.variants) {
                        if (v.name.equals(e.text)) {
                            ok = true;
                            break;
                        }
                    }
                    if (!ok) {
                        fail(e.line, e.col, "enum " + en.name + " has no variant '" + e.text + "'");
                    }
                    return;
                }
                if (tryModulePath(e) != null || env.findModuleBind(recv) != null || Types.isHostModule(recv)
                        || env.signalArity.containsKey(recv)) {
                    return;
                }
            }
            if (tryModulePath(e) != null) {
                return;
            }
            String obj = e.kids.isEmpty() ? "" : infer(e.kids.getFirst());
            if (isArrayTy(obj) || isMapTy(obj) || isString(obj)) {
                if (!e.text.equals("len")) {
                    fail(e.line, e.col, (isArrayTy(obj) ? "Array" : isMapTy(obj) ? "Map" : obj) + " has no member '"
                            + e.text + "'");
                }
                return;
            }
            if (known(obj) && env.lookupTypeSignal(obj, e.text) != null) {
                return;
            }
            if (known(obj) && isTraitType(obj)) {
                fail(e.line, e.col, "trait " + obj + " has no field '" + e.text + "'");
                return;
            }
            if (known(obj) && !hasField(obj, e.text)) {
                fail(e.line, e.col, "struct " + obj + " has no field '" + e.text + "'");
            } else if (known(obj)) {
                Checker.FieldHit found = env.lookupField(obj, e.text);
                if (!found.definedOn.isEmpty()) {
                    checkAccess(found.vis, "field", e.text, found.definedOn, e.line, e.col);
                }
            }
            return;
        }
        if (e.kind == Expr.Kind.Index) {
            for (Expr kid : e.kids) {
                walkExpr(kid);
            }
            checkIndex(e);
            return;
        }
        if (e.kind == Expr.Kind.Range) {
            for (Expr kid : e.kids) {
                walkExpr(kid);
            }
            if (e.kids.size() >= 2) {
                String start = infer(e.kids.get(0));
                String end = infer(e.kids.get(1));
                if (known(start) && !start.equals("Int")) {
                    fail(e.kids.getFirst().line, e.kids.getFirst().col, "range start must be Int");
                }
                if (known(end) && !end.equals("Int")) {
                    fail(e.kids.get(1).line, e.kids.get(1).col, "range end must be Int");
                }
            }
            return;
        }
        if (e.kind == Expr.Kind.StructLit) {
            for (Expr kid : e.kids) {
                walkExpr(kid);
            }
            StructDecl st = env.findStruct(e.text);
            if (st == null) {
                if (env.findTrait(e.text) != null) {
                    fail(e.line, e.col, "cannot construct trait '" + e.text + "'");
                } else {
                    fail(e.line, e.col, "undefined struct '" + e.text + "'" + Types.stdlibImportHint(e.text, env.file));
                }
                return;
            }
            if (Boolean.TRUE.equals(env.classAbstract.get(e.text))) {
                fail(e.line, e.col, "cannot construct abstract class '" + e.text + "'");
                return;
            }
            checkTypeArgCount(st.typeParams, e.typeArgs, e.line, e.col, st.name);
            Map<String, String> tenv = new LinkedHashMap<>();
            if (!st.typeParams.isEmpty()) {
                Set<String> prev = genericParams;
                genericParams = new LinkedHashSet<>(prev);
                for (TypeParam p : st.typeParams) {
                    genericParams.add(p.name);
                }
                if (!e.typeArgs.isEmpty()) {
                    tenv = Types.typeEnvFrom(st.typeParams, e.typeArgs);
                }
                for (int i = 0; i < e.names.size() && i < e.kids.size(); i++) {
                    unifyType(st.typeOfField(e.names.get(i)), infer(e.kids.get(i)), tenv);
                }
                genericParams = prev;
                checkBounds(st.typeParams, tenv, e.line, e.col);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < e.names.size(); i++) {
                String field = e.names.get(i);
                if (!st.fields.contains(field)) {
                    fail(e.line, e.col, "unknown field '" + field + "' on " + st.name);
                }
                if (!seen.add(field)) {
                    fail(e.line, e.col, "duplicate field '" + field + "' on " + st.name);
                }
                if (i < e.kids.size()) {
                    String expect = Types.substType(st.typeOfField(field), tenv);
                    String got = infer(e.kids.get(i));
                    if (known(expect) && known(got) && !assignable(expect, got, e.kids.get(i))) {
                        fail(e.line, e.col, "cannot assign " + got + " to field '" + field + "', expected " + expect);
                    }
                    checkArrayElems(expect, e.kids.get(i), e.line, e.col);
                }
            }
            Map<String, Expr> defIt = env.fieldDefaults.get(st.name);
            for (String field : e.names) {
                Checker.FieldHit found = env.lookupField(st.name, field);
                if (!found.definedOn.isEmpty() && !found.definedOn.equals(st.name)) {
                    checkAccess(found.vis, "field", field, found.definedOn, e.line, e.col);
                }
            }
            for (String field : st.fields) {
                if (e.names.contains(field)) {
                    continue;
                }
                boolean hasDef = defIt != null && defIt.containsKey(field);
                if (!hasDef && !st.isOptionalField(field)) {
                    fail(e.line, e.col, "missing field '" + field + "' on " + st.name);
                }
            }
            return;
        }
        for (Expr kid : e.kids) {
            walkExpr(kid);
        }
        if (e.kind == Expr.Kind.Binary) {
            checkBinop(e);
        }
        if (e.kind == Expr.Kind.Unary && e.text.equals("-") && !e.kids.isEmpty()) {
            String ty = infer(e.kids.getFirst());
            if (known(ty) && !isNumeric(ty)) {
                fail(e.line, e.col, "cannot negate " + ty);
            }
        }
    }

    private void walkStmt(Stmt stmt) {
        switch (stmt.kind) {
            case Pass, Break, Continue, Comment:
                return;
            case Throw:
                if (!currentThrows) {
                    fail(stmt.line, stmt.col, "throw outside throwing function");
                }
                walkExpr(stmt.expr);
                return;
            case Do:
                for (Stmt s : stmt.body) {
                    walkStmt(s);
                }
                if (!stmt.name.isEmpty()) {
                    scopes.add(new LinkedHashMap<>());
                    bind(stmt.name, "");
                    for (Stmt s : stmt.elseBody) {
                        walkStmt(s);
                    }
                    scopes.removeLast();
                }
                return;
            case Expr: {
                walkExpr(stmt.expr);
                String ty = infer(stmt.expr);
                if (known(ty) && isFutureTy(ty)) {
                    fail(stmt.line, stmt.col, "unused Future; await or bind it");
                }
                return;
            }
            case Var, Const: {
                walkExpr(stmt.expr);
                String got = infer(stmt.expr);
                if (!stmt.typeName.isEmpty()) {
                    checkTypeName(stmt.typeName, stmt.line, stmt.col);
                    if (!isFnType(stmt.typeName)) {
                        StructDecl st = env.findStruct(stmt.typeName);
                        if (st != null) {
                            checkTypeArgCount(st.typeParams, Types.typeArgList(stmt.typeName), stmt.line, stmt.col,
                                    Types.typeHead(stmt.typeName));
                        } else {
                            TraitDecl tr = env.findTrait(stmt.typeName);
                            if (tr != null) {
                                checkTypeArgCount(tr.typeParams, Types.typeArgList(stmt.typeName), stmt.line, stmt.col,
                                        Types.typeHead(stmt.typeName));
                            }
                        }
                    }
                }
                if (known(stmt.typeName) && known(got) && !assignable(stmt.typeName, got, stmt.expr)) {
                    fail(stmt.line, stmt.col, "variable annotated as " + stmt.typeName
                            + " but initializer looks like " + got);
                }
                if (!stmt.typeName.isEmpty()) {
                    checkArrayElems(stmt.typeName, stmt.expr, stmt.line, stmt.col);
                }
                bind(stmt.name, known(stmt.typeName) ? stmt.typeName : got);
                return;
            }
            case Assign: {
                walkExpr(stmt.expr);
                if (!defined(stmt.name)) {
                    fail(stmt.line, stmt.col,
                            "undefined variable '" + stmt.name + "'" + Types.stdlibImportHint(stmt.name, env.file));
                    return;
                }
                if (!isLocal(stmt.name) && !currentSelf.isEmpty()) {
                    Checker.FieldHit found = env.lookupField(currentSelf, stmt.name);
                    if (!found.definedOn.isEmpty()) {
                        checkAccess(found.vis, "field", stmt.name, found.definedOn, stmt.line, stmt.col);
                        if (env.isDataType(found.definedOn) || env.isDataType(currentSelf)) {
                            fail(stmt.line, stmt.col, "cannot assign to data field '" + stmt.name + "'");
                        }
                    }
                }
                String lt = lookup(stmt.name);
                String rt = infer(stmt.expr);
                checkCompound(stmt.op, lt, rt, stmt.line, stmt.col, stmt.expr);
                if (stmt.op.isEmpty() || stmt.op.equals("=")) {
                    checkArrayElems(lt, stmt.expr, stmt.line, stmt.col);
                }
                return;
            }
            case FieldAssign: {
                walkExpr(stmt.target);
                walkExpr(stmt.expr);
                String obj = infer(stmt.target);
                if (known(obj) && isTraitType(obj)) {
                    fail(stmt.line, stmt.col, "trait " + obj + " has no field '" + stmt.name + "'");
                } else if (known(obj) && !hasField(obj, stmt.name)) {
                    fail(stmt.line, stmt.col, "struct " + obj + " has no field '" + stmt.name + "'");
                } else if (known(obj)) {
                    Checker.FieldHit found = env.lookupField(obj, stmt.name);
                    if (!found.definedOn.isEmpty()) {
                        checkAccess(found.vis, "field", stmt.name, found.definedOn, stmt.line, stmt.col);
                    }
                    if (env.isDataType(obj) || (!found.definedOn.isEmpty() && env.isDataType(found.definedOn))) {
                        fail(stmt.line, stmt.col, "cannot assign to data field '" + stmt.name + "'");
                    }
                }
                String lt = known(obj) ? fieldType(obj, stmt.name) : "";
                String rt = infer(stmt.expr);
                checkCompound(stmt.op, lt, rt, stmt.line, stmt.col, stmt.expr);
                if (stmt.op.isEmpty() || stmt.op.equals("=")) {
                    checkArrayElems(lt, stmt.expr, stmt.line, stmt.col);
                }
                return;
            }
            case IndexAssign: {
                walkExpr(stmt.target);
                walkExpr(stmt.expr);
                if (stmt.target != null && stmt.target.kids.size() >= 2) {
                    checkIndex(stmt.target);
                }
                if (stmt.target != null && stmt.target.kind == Expr.Kind.Index && stmt.target.kids.size() >= 2) {
                    String obj = infer(stmt.target.kids.getFirst());
                    String elem = arrayElem(obj);
                    String rt = infer(stmt.expr);
                    if (known(elem) && known(rt) && !assignable(elem, rt, stmt.expr)) {
                        fail(stmt.line, stmt.col, "cannot assign " + rt + " to " + elem);
                    }
                    checkArrayElems(elem, stmt.expr, stmt.line, stmt.col);
                }
                return;
            }
            case Return: {
                boolean prevThrowing = throwingTry;
                throwingTry = false;
                walkExpr(stmt.expr);
                if (throwingTry && !currentThrows) {
                    fail(stmt.line, stmt.col, "returning a throwing call requires 'throws'");
                }
                throwingTry = prevThrowing || throwingTry;
                String got = infer(stmt.expr);
                if (known(currentReturn) && known(got) && !assignable(currentReturn, got, stmt.expr)) {
                    fail(stmt.line, stmt.col, "cannot return " + got + " from " + currentReturn + " function");
                }
                checkArrayElems(currentReturn, stmt.expr, stmt.line, stmt.col);
                return;
            }
            case If:
                walkExpr(stmt.expr);
                for (Stmt s : stmt.body) {
                    walkStmt(s);
                }
                for (Stmt s : stmt.elseBody) {
                    walkStmt(s);
                }
                return;
            case While:
                walkExpr(stmt.expr);
                for (Stmt s : stmt.body) {
                    walkStmt(s);
                }
                return;
            case For: {
                walkExpr(stmt.expr);
                scopes.add(new LinkedHashMap<>());
                String item = "";
                String it = infer(stmt.expr);
                if (isArrayTy(it)) {
                    item = arrayElem(it);
                } else if (isMapTy(it) || isString(it)) {
                    item = "String";
                } else if (it.equals("Int") || it.equals("Range")) {
                    item = "Int";
                }
                bind(stmt.name, item);
                for (Stmt s : stmt.body) {
                    walkStmt(s);
                }
                scopes.removeLast();
                return;
            }
            case Match: {
                walkExpr(stmt.expr);
                String ty = infer(stmt.expr);
                EnumDecl en = known(ty) ? env.findEnum(ty) : null;
                Set<String> variants = new HashSet<>();
                if (en != null) {
                    for (EnumVariant v : en.variants) {
                        variants.add(v.name);
                    }
                }
                Set<String> covered = new HashSet<>();
                boolean wild = false;
                for (MatchArm arm : stmt.arms) {
                    if (arm.pat == MatchArm.Pat.Wildcard) {
                        wild = true;
                    } else if (arm.pat == MatchArm.Pat.Variant && en != null) {
                        if (!variants.contains(arm.name)) {
                            fail(arm.line, arm.col, "enum " + en.name + " has no variant '" + arm.name + "'");
                        } else {
                            covered.add(arm.name);
                        }
                    }
                    scopes.add(new LinkedHashMap<>());
                    for (String b : arm.binds) {
                        if (!b.isEmpty()) {
                            bind(b, "");
                        }
                    }
                    for (Stmt s : arm.body) {
                        walkStmt(s);
                    }
                    scopes.removeLast();
                }
                if (en != null && !wild) {
                    List<String> missing = new ArrayList<>();
                    for (EnumVariant v : en.variants) {
                        if (!covered.contains(v.name)) {
                            missing.add(v.name);
                        }
                    }
                    if (!missing.isEmpty()) {
                        StringBuilder msg = new StringBuilder("match of " + en.name + " is missing variant");
                        if (missing.size() > 1) {
                            msg.append('s');
                        }
                        for (int i = 0; i < missing.size(); i++) {
                            msg.append(i == 0 ? " '" : ", '");
                            msg.append(missing.get(i)).append('\'');
                        }
                        fail(stmt.line, stmt.col, msg.toString());
                    }
                }
            }
        }
    }

    private void checkFn(FnDecl fn, String selfType) {
        if (fn.isAbstract) {
            return;
        }
        String prevRet = currentReturn;
        String prevSelf = currentSelf;
        String prevSuper = currentSuper;
        boolean prevThrows = currentThrows;
        boolean prevAsync = currentAsync;
        currentReturn = fn.returnType;
        currentSelf = selfType;
        currentSuper = "";
        currentThrows = fn.throwsEx;
        currentAsync = fn.isAsync;
        if (fn.isConstexpr && fn.throwsEx) {
            fail(fn.line, 1, "constexpr function cannot throw");
        }
        if (fn.isConstexpr && fn.isAsync) {
            fail(fn.line, 1, "constexpr function cannot be async");
        }
        Set<String> prevParams = genericParams;
        Map<String, List<String>> prevBounds = genericBounds;
        genericParams = new LinkedHashSet<>(prevParams);
        genericBounds = new LinkedHashMap<>(prevBounds);
        pushParams(fn.typeParams, fn.line);
        if (!selfType.isEmpty()) {
            StructDecl st = env.findStruct(selfType);
            if (st != null) {
                pushParams(st.typeParams, fn.line);
            }
            for (Checker.TraitImplInfo im : env.traitImpls) {
                if (Types.typeHead(im.typeName).equals(Types.typeHead(selfType))) {
                    pushParams(im.typeParams, fn.line);
                }
            }
            String pit = env.classParents.get(Types.typeHead(selfType));
            if (pit != null) {
                currentSuper = pit;
            }
        }
        checkTypeName(fn.returnType, fn.line, 1);
        scopes.add(new LinkedHashMap<>());
        for (int i = 0; i < fn.params.size(); i++) {
            String ty = i < fn.paramTypes.size() ? fn.paramTypes.get(i) : "";
            if (fn.params.get(i).equals("self") && !selfType.isEmpty()) {
                ty = selfType;
            }
            if (!ty.isEmpty()) {
                checkTypeName(ty, fn.line, 1);
                if (!isFnType(ty)) {
                    StructDecl st = env.findStruct(ty);
                    if (st != null) {
                        checkTypeArgCount(st.typeParams, Types.typeArgList(ty), fn.line, 1, Types.typeHead(ty));
                    } else {
                        TraitDecl tr = env.findTrait(ty);
                        if (tr != null) {
                            checkTypeArgCount(tr.typeParams, Types.typeArgList(ty), fn.line, 1, Types.typeHead(ty));
                        }
                    }
                }
            }
            bind(fn.params.get(i), ty);
        }
        for (Stmt stmt : fn.body) {
            walkStmt(stmt);
        }
        scopes.removeLast();
        genericParams = prevParams;
        genericBounds = prevBounds;
        currentReturn = prevRet;
        currentSelf = prevSelf;
        currentSuper = prevSuper;
        currentThrows = prevThrows;
        currentAsync = prevAsync;
    }

    private void pushParams(List<TypeParam> params, int line) {
        for (TypeParam p : params) {
            genericParams.add(p.name);
            if (!p.bounds.isEmpty()) {
                genericBounds.put(p.name, p.bounds);
            }
            for (String b : p.bounds) {
                checkTraitBound(b, line, 1);
            }
        }
    }

    void run() {
        for (StructDecl st : env.program.structs) {
            Set<String> prev = genericParams;
            genericParams = new LinkedHashSet<>(prev);
            for (TypeParam p : st.typeParams) {
                genericParams.add(p.name);
            }
            for (String ty : st.fieldTypes) {
                checkTypeName(ty, st.line, 1);
            }
            genericParams = prev;
        }
        for (ClassDecl c : env.program.classes) {
            Set<String> prev = genericParams;
            genericParams = new LinkedHashSet<>(prev);
            for (TypeParam p : c.typeParams) {
                genericParams.add(p.name);
            }
            for (ClassField f : c.fields) {
                checkTypeName(f.type, c.line, 1);
            }
            genericParams = prev;
        }
        String crate = env.crateNameOfFile(env.file);
        String prevEntry = env.currentModule;
        if (!crate.isEmpty()) {
            env.currentModule = crate;
        }
        for (FnDecl fn : env.program.fns) {
            checkFn(fn, "");
        }
        env.currentModule = prevEntry;
        for (Map.Entry<String, Map<String, FnDecl>> type : env.typeMethods.entrySet()) {
            for (Map.Entry<String, FnDecl> m : type.getValue().entrySet()) {
                String prevMod = env.currentModule;
                env.currentModule = m.getValue().module;
                checkFn(m.getValue(), type.getKey());
                env.currentModule = prevMod;
            }
        }
        String prev = env.currentModule;
        for (Map.Entry<String, Checker.LoadedMod> mod : env.loaded.entrySet()) {
            env.currentModule = mod.getKey();
            for (FnDecl fn : mod.getValue().fns.values()) {
                checkFn(fn, "");
            }
        }
        env.currentModule = prev;
    }
}

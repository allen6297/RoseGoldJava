package com.rosegoldc.lang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Llvm {

    private Llvm() {
    }

    static String emit(Checker checker) {
        return emit(checker, false);
    }

    static String emit(Checker checker, boolean test) {
        StringBuilder ir = new StringBuilder();
        ir.append("; RoseGold LLVM snapshot - RGValue is { i8 kind, i64 bits, ptr }\n");
        ir.append("; kind: 0 void, 1 bool, 2 int, 3 float, 4 string, 5 array, 6 range, 7 map, 8 struct, 9 enum type, 10 enum, 11 fn, 12 future, 13 signal\n");
        ir.append("; link with native/runtime.c\n\n");
        ir.append("%RGValue = type { i8, i64, ptr }\n\n");
        ir.append("declare void @rg_set_void(ptr)\n");
        ir.append("declare void @rg_set_bool(ptr, i32)\n");
        ir.append("declare void @rg_set_int(ptr, i64)\n");
        ir.append("declare void @rg_set_float(ptr, double)\n");
        ir.append("declare void @rg_set_string(ptr, ptr)\n");
        ir.append("declare void @rg_copy(ptr, ptr)\n");
        ir.append("declare void @rg_print(ptr, i32)\n");
        ir.append("declare i64 @rg_to_exit(ptr)\n");
        ir.append("declare i32 @rg_truthy(ptr)\n");
        ir.append("declare void @rg_bin(ptr, ptr, ptr, i32)\n");
        ir.append("declare void @rg_unary(ptr, ptr, i32)\n");
        ir.append("declare void @rg_array(ptr, ptr, i32)\n");
        ir.append("declare void @rg_range(ptr, ptr, ptr, i32)\n");
        ir.append("declare void @rg_index_get(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_index_set(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_iter_items(ptr, ptr)\n");
        ir.append("declare void @rg_len(ptr, ptr)\n");
        ir.append("declare void @rg_assert(ptr, ptr, i32)\n");
        ir.append("declare void @rg_test_ok(ptr)\n");
        ir.append("declare void @rg_test_fail(ptr, ptr)\n");
        ir.append("declare void @rg_test_summary(i32, i32)\n");
        ir.append("declare void @rg_map(ptr, ptr, i32)\n");
        ir.append("declare void @rg_method(ptr, ptr, i32, i32)\n");
        ir.append("declare void @rg_struct(ptr, ptr, ptr, i32, ptr, i32)\n");
        ir.append("declare void @rg_member(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_field_set(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_register_method(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_register_parent(ptr, ptr)\n");
        ir.append("declare void @rg_call_method(ptr, ptr, i32, ptr)\n");
        ir.append("declare void @rg_enum_type(ptr, ptr)\n");
        ir.append("declare void @rg_register_enum(ptr, ptr, i32, ptr)\n");
        ir.append("declare void @rg_match_lit(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_match_var(ptr, ptr, ptr)\n");
        ir.append("declare void @rg_payload(ptr, ptr, i32, ptr)\n");
        ir.append("declare void @rg_fn_ref(ptr, ptr, i32)\n");
        ir.append("declare void @rg_closure(ptr, ptr, i32, ptr, i32)\n");
        ir.append("declare void @rg_call_val(ptr, ptr, i32)\n");
        ir.append("declare void @rg_throw(ptr)\n");
        ir.append("declare i32 @rg_has_throw()\n");
        ir.append("declare void @rg_catch(ptr)\n");
        ir.append("declare void @rg_spawn(ptr, ptr, ptr, i32)\n");
        ir.append("declare void @rg_await(ptr, ptr)\n");
        ir.append("declare void @rg_signal(ptr, ptr, i32)\n");
        ir.append("declare void @rg_register_type_signal(ptr, ptr, i32)\n");
        ir.append("declare void @rg_flush_deferred()\n");
        ir.append("declare void @rg_host_call(ptr, ptr, ptr, ptr, i32)\n");
        ir.append("declare void @rg_set_argv(i32, ptr)\n\n");
        Selection sel = select(checker);
        Map<String, List<String>> layouts = layouts(checker);
        List<String> strings = new ArrayList<>();
        for (FnDecl fn : checker.fns.values()) {
            if (fn.code == null || !sel.fns.contains(fn.name)) {
                continue;
            }
            collectStrings(fn.code, strings);
        }
        for (String key : sel.methods) {
            FnDecl fn = typeMethod(checker, key);
            if (fn != null && fn.code != null) {
                collectStrings(fn.code, strings);
            }
            internString(strings, methodType(key));
            internString(strings, methodName(key));
        }
        for (String key : sel.modFns) {
            FnDecl fn = moduleFnKey(checker, key);
            if (fn != null && fn.code != null) {
                collectStrings(fn.code, strings);
            }
        }
        for (Map.Entry<String, List<String>> e : layouts.entrySet()) {
            internString(strings, e.getKey());
            for (String field : e.getValue()) {
                internString(strings, field);
            }
        }
        for (Map.Entry<String, String> e : checker.classParents.entrySet()) {
            internString(strings, e.getKey());
            internString(strings, Types.typeHead(e.getValue() == null ? "" : e.getValue()));
        }
        internTypeSignals(checker, strings);
        internEnums(checker, strings);
        if (test) {
            for (String name : testFns(checker, sel)) {
                internString(strings, name);
            }
        }
        Lambdas lambdas = collectLambdas(checker, sel);
        Dbg dbg = new Dbg(checker.file);
        for (FnDecl lam : lambdas.order) {
            if (lam.code != null) {
                collectStrings(lam.code, strings);
            }
        }
        for (int i = 0; i < strings.size(); i++) {
            emitStringGlobal(ir, i, strings.get(i));
        }
        if (!strings.isEmpty()) {
            ir.append('\n');
        }
        Set<String> usedStructs = new LinkedHashSet<>();
        for (FnDecl fn : checker.fns.values()) {
            if (fn.code != null && sel.fns.contains(fn.name)) {
                collectStructTypes(fn.code, usedStructs);
            }
        }
        for (String key : sel.methods) {
            FnDecl fn = typeMethod(checker, key);
            if (fn != null && fn.code != null) {
                collectStructTypes(fn.code, usedStructs);
            }
        }
        for (String key : sel.modFns) {
            FnDecl fn = moduleFnKey(checker, key);
            if (fn != null && fn.code != null) {
                collectStructTypes(fn.code, usedStructs);
            }
        }
        for (FnDecl lam : lambdas.order) {
            if (lam.code != null) {
                collectStructTypes(lam.code, usedStructs);
            }
        }
        for (String type : usedStructs) {
            emitFieldTable(ir, type, layouts.getOrDefault(type, List.of()), strings);
        }
        if (!usedStructs.isEmpty()) {
            ir.append('\n');
        }
        for (EnumDecl en : checker.enums.values()) {
            emitEnumFieldTables(ir, en, strings);
        }
        for (FnDecl fn : checker.fns.values()) {
            if (fn.module != null && !fn.module.isEmpty()) {
                continue;
            }
            if (fn.code == null || !sel.fns.contains(fn.name)) {
                if (fn.code == null) {
                    ir.append("; fn ").append(fn.name).append(" { tree-walk }\n\n");
                } else {
                    ir.append("; fn ").append(fn.name).append(" { not lowered to llvm }\n\n");
                }
                continue;
            }
            emitFn(ir, fn.code, strings, mangle(fn.name), checker, layouts, lambdas, fn.code.arity, dbg, fn);
            ir.append('\n');
        }
        for (String key : sel.modFns) {
            FnDecl fn = moduleFnKey(checker, key);
            if (fn == null || fn.code == null) {
                continue;
            }
            int split = key.lastIndexOf('.');
            String module = split < 0 ? "" : key.substring(0, split);
            String name = split < 0 ? key : key.substring(split + 1);
            emitFn(ir, fn.code, strings, mangleMod(module, name), checker, layouts, lambdas, fn.code.arity, dbg, fn);
            ir.append('\n');
        }
        for (String key : sel.methods) {
            FnDecl fn = typeMethod(checker, key);
            if (fn == null || fn.code == null) {
                continue;
            }
            emitFn(ir, fn.code, strings, mangleMethod(methodType(key), methodName(key)), checker, layouts, lambdas,
                    fn.code.arity, dbg, fn);
            ir.append('\n');
        }
        for (FnDecl lam : lambdas.order) {
            if (lam.code == null) {
                continue;
            }
            emitFn(ir, lam.code, strings, lambdas.names.get(lam), checker, layouts, lambdas,
                    lambdas.ncopy.getOrDefault(lam, lam.code.arity), dbg, lam);
            ir.append('\n');
        }
        boolean needInit = !sel.methods.isEmpty() || !checker.enums.isEmpty() || !checker.classParents.isEmpty()
                || hasTypeSignals(checker);
        if (needInit) {
            ir.append("define void @rg_init_methods() {\n");
            ir.append("entry:\n");
            for (String key : sel.methods) {
                int t = internString(strings, methodType(key));
                int m = internString(strings, methodName(key));
                ir.append("  call void @rg_register_method(ptr @str").append(t).append(", ptr @str").append(m)
                        .append(", ptr @").append(mangleMethod(methodType(key), methodName(key))).append(")\n");
            }
            for (Map.Entry<String, String> e : checker.classParents.entrySet()) {
                String parent = Types.typeHead(e.getValue() == null ? "" : e.getValue());
                if (parent.isEmpty()) {
                    continue;
                }
                int childId = internString(strings, e.getKey());
                int parentId = internString(strings, parent);
                ir.append("  call void @rg_register_parent(ptr @str").append(childId).append(", ptr @str")
                        .append(parentId).append(")\n");
            }
            emitTypeSignals(ir, checker, strings);
            for (EnumDecl en : checker.enums.values()) {
                emitEnumRegisters(ir, en, strings);
            }
            ir.append("  ret void\n");
            ir.append("}\n\n");
        }
        if (test) {
            emitTestMain(ir, checker, sel, strings, needInit);
        } else if (sel.fns.contains("main")) {
            ir.append("define i32 @main(i32 %argc, ptr %argv) {\n");
            ir.append("entry:\n");
            ir.append("  call void @rg_set_argv(i32 %argc, ptr %argv)\n");
            ir.append("  %ret = alloca %RGValue\n");
            ir.append("  call void @rg_set_void(ptr %ret)\n");
            if (needInit) {
                ir.append("  call void @rg_init_methods()\n");
            }
            if (isAsyncFn(checker, "main")) {
                ir.append("  %fut = alloca %RGValue\n");
                ir.append("  call void @rg_set_void(ptr %fut)\n");
                ir.append("  call void @rg_spawn(ptr %fut, ptr @rg_fn_main, ptr null, i32 0)\n");
                ir.append("  call void @rg_await(ptr %ret, ptr %fut)\n");
            } else {
                ir.append("  call void @rg_fn_main(ptr %ret, ptr null, i32 0)\n");
            }
            ir.append("  call void @rg_flush_deferred()\n");
            ir.append("  %code = call i64 @rg_to_exit(ptr %ret)\n");
            ir.append("  %e = trunc i64 %code to i32\n");
            ir.append("  ret i32 %e\n");
            ir.append("}\n");
        }
        dbg.emit(ir);
        return ir.toString();
    }

    private static List<String> testFns(Checker checker, Selection sel) {
        List<String> tests = new ArrayList<>();
        for (FnDecl fn : checker.fns.values()) {
            if (!fn.isTest) {
                continue;
            }
            if (fn.module != null && !fn.module.isEmpty()) {
                continue;
            }
            if (fn.code != null && sel.fns.contains(fn.name)) {
                tests.add(fn.name);
            }
        }
        return tests;
    }

    private static void emitTestMain(StringBuilder ir, Checker checker, Selection sel, List<String> strings,
            boolean needInit) {
        List<String> tests = testFns(checker, sel);
        if (tests.isEmpty()) {
            return;
        }
        boolean anyAsync = false;
        for (String name : tests) {
            if (isAsyncFn(checker, name)) {
                anyAsync = true;
                break;
            }
        }
        ir.append("define i32 @main(i32 %argc, ptr %argv) {\n");
        ir.append("entry:\n");
        ir.append("  call void @rg_set_argv(i32 %argc, ptr %argv)\n");
        ir.append("  %ret = alloca %RGValue\n");
        ir.append("  %err = alloca %RGValue\n");
        ir.append("  %failed = alloca i32\n");
        ir.append("  call void @rg_set_void(ptr %ret)\n");
        ir.append("  store i32 0, ptr %failed\n");
        if (needInit) {
            ir.append("  call void @rg_init_methods()\n");
        }
        if (anyAsync) {
            ir.append("  %fut = alloca %RGValue\n");
            ir.append("  call void @rg_set_void(ptr %fut)\n");
        }
        for (int i = 0; i < tests.size(); i++) {
            String name = tests.get(i);
            int sid = internString(strings, name);
            ir.append("  call void @rg_set_void(ptr %ret)\n");
            if (isAsyncFn(checker, name)) {
                ir.append("  call void @rg_spawn(ptr %fut, ptr @").append(mangle(name))
                        .append(", ptr null, i32 0)\n");
                ir.append("  call void @rg_await(ptr %ret, ptr %fut)\n");
            } else {
                ir.append("  call void @").append(mangle(name)).append("(ptr %ret, ptr null, i32 0)\n");
            }
            ir.append("  %h").append(i).append(" = call i32 @rg_has_throw()\n");
            ir.append("  %c").append(i).append(" = icmp ne i32 %h").append(i).append(", 0\n");
            ir.append("  br i1 %c").append(i).append(", label %fail").append(i).append(", label %ok").append(i)
                    .append('\n');
            ir.append("ok").append(i).append(":\n");
            ir.append("  call void @rg_test_ok(ptr @str").append(sid).append(")\n");
            ir.append("  br label %next").append(i).append('\n');
            ir.append("fail").append(i).append(":\n");
            ir.append("  call void @rg_catch(ptr %err)\n");
            ir.append("  call void @rg_test_fail(ptr @str").append(sid).append(", ptr %err)\n");
            ir.append("  %f").append(i).append(" = load i32, ptr %failed\n");
            ir.append("  %fn").append(i).append(" = add i32 %f").append(i).append(", 1\n");
            ir.append("  store i32 %fn").append(i).append(", ptr %failed\n");
            ir.append("  br label %next").append(i).append('\n');
            ir.append("next").append(i).append(":\n");
        }
        ir.append("  %fend = load i32, ptr %failed\n");
        ir.append("  %passed = sub i32 ").append(tests.size()).append(", %fend\n");
        ir.append("  call void @rg_test_summary(i32 %passed, i32 ").append(tests.size()).append(")\n");
        ir.append("  %any = icmp ne i32 %fend, 0\n");
        ir.append("  %code = zext i1 %any to i32\n");
        ir.append("  ret i32 %code\n");
        ir.append("}\n");
    }

    private static final class Selection {
        final Set<String> fns = new LinkedHashSet<>();
        final Set<String> methods = new LinkedHashSet<>();
        final Set<String> modFns = new LinkedHashSet<>();
    }

    private static final class Lambdas {
        final List<FnDecl> order = new ArrayList<>();
        final Map<FnDecl, String> names = new IdentityHashMap<>();
        final Map<FnDecl, Integer> ncopy = new IdentityHashMap<>();
    }

    private static Lambdas collectLambdas(Checker checker, Selection sel) {
        Lambdas out = new Lambdas();
        int[] next = {0};
        for (FnDecl fn : checker.fns.values()) {
            if (fn.code != null && sel.fns.contains(fn.name)) {
                collectLambdasFrom(fn.code, out, next);
            }
        }
        for (String key : sel.methods) {
            FnDecl fn = typeMethod(checker, key);
            if (fn != null && fn.code != null) {
                collectLambdasFrom(fn.code, out, next);
            }
        }
        for (String key : sel.modFns) {
            FnDecl fn = moduleFnKey(checker, key);
            if (fn != null && fn.code != null) {
                collectLambdasFrom(fn.code, out, next);
            }
        }
        return out;
    }

    private static void collectLambdasFrom(Bytecode.Fn fn, Lambdas out, int[] next) {
        if (fn == null) {
            return;
        }
        for (Bytecode.Inst in : fn.insts) {
            if (in.op != Bytecode.Op.CLOSURE || in.proto == null || in.proto.code == null) {
                continue;
            }
            if (out.names.containsKey(in.proto)) {
                continue;
            }
            out.names.put(in.proto, "rg_fn_lam" + next[0]++);
            out.ncopy.put(in.proto, in.proto.code.arity + Math.max(in.c, 0));
            out.order.add(in.proto);
            collectLambdasFrom(in.proto.code, out, next);
        }
    }

    private static Selection select(Checker checker) {
        Selection sel = new Selection();
        for (Map.Entry<String, FnDecl> e : checker.fns.entrySet()) {
            if (e.getValue().code != null && opsOk(e.getValue().code, checker)) {
                sel.fns.add(e.getKey());
            }
        }
        for (Map.Entry<String, Map<String, FnDecl>> type : checker.typeMethods.entrySet()) {
            for (Map.Entry<String, FnDecl> m : type.getValue().entrySet()) {
                if (m.getValue().code != null && opsOk(m.getValue().code, checker)) {
                    sel.methods.add(type.getKey() + "." + m.getKey());
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            int beforeMods = sel.modFns.size();
            for (String name : sel.fns) {
                FnDecl fn = checker.fns.get(name);
                if (fn != null && fn.code != null) {
                    addModuleRefs(fn.code, sel, checker);
                }
            }
            for (String key : sel.methods) {
                FnDecl fn = typeMethod(checker, key);
                if (fn != null && fn.code != null) {
                    addModuleRefs(fn.code, sel, checker);
                }
            }
            for (String key : List.copyOf(sel.modFns)) {
                FnDecl fn = moduleFnKey(checker, key);
                if (fn != null && fn.code != null) {
                    addModuleRefs(fn.code, sel, checker);
                }
            }
            if (sel.modFns.size() != beforeMods) {
                changed = true;
            }
            Set<String> nextFns = new LinkedHashSet<>();
            for (String name : sel.fns) {
                if (callsOk(checker.fns.get(name).code, sel, checker)) {
                    nextFns.add(name);
                } else {
                    changed = true;
                }
            }
            Set<String> nextMethods = new LinkedHashSet<>();
            for (String key : sel.methods) {
                FnDecl fn = typeMethod(checker, key);
                if (fn != null && fn.code != null && callsOk(fn.code, sel, checker)) {
                    nextMethods.add(key);
                } else {
                    changed = true;
                }
            }
            Set<String> nextMods = new LinkedHashSet<>();
            for (String key : sel.modFns) {
                FnDecl fn = moduleFnKey(checker, key);
                if (fn != null && fn.code != null && callsOk(fn.code, sel, checker)) {
                    nextMods.add(key);
                } else {
                    changed = true;
                }
            }
            sel.fns.clear();
            sel.fns.addAll(nextFns);
            sel.methods.clear();
            sel.methods.addAll(nextMethods);
            sel.modFns.clear();
            sel.modFns.addAll(nextMods);
        }
        return sel;
    }

    private static boolean opsOk(Bytecode.Fn fn, Checker checker) {
        int n = fn.insts.size();
        for (Bytecode.Inst in : fn.insts) {
            switch (in.op) {
                case LOAD_CONST, MOVE, CALL, RET, RET_VOID, DEBUG, BIN, UNARY, TRUTHY, JUMP, JUMP_F, JUMP_T,
                     ARRAY, RANGE, INDEX_GET, INDEX_SET, ITER_ITEMS, LEN, MAP, METHOD, STRUCT, MEMBER, FIELD_SET,
                     LOAD_ENUM, MATCH_LIT, MATCH_VAR, PAYLOAD, CLOSURE, CALL_VAL, LOAD_FN, THROW, AWAIT, SUPER,
                     LOAD_SIGNAL -> {
                }
                default -> {
                    return false;
                }
            }
            if (in.op == Bytecode.Op.LOAD_CONST) {
                if (in.b < 0 || in.b >= fn.constants.size()) {
                    return false;
                }
                Value k = fn.constants.get(in.b);
                if (k.kind != Value.Kind.Int && k.kind != Value.Kind.Float && k.kind != Value.Kind.String
                        && k.kind != Value.Kind.Bool && k.kind != Value.Kind.Void) {
                    return false;
                }
            }
            if (in.op == Bytecode.Op.CALL && in.module != null && !in.module.isEmpty()) {
                if (!isHostCall(in.module, in.s) && moduleFn(checker, in.module, in.s) == null) {
                    return false;
                }
            }
            if (in.op == Bytecode.Op.BIN && binOpId(in.s) < 0) {
                return false;
            }
            if (in.op == Bytecode.Op.UNARY && unaryOpId(in.s) < 0) {
                return false;
            }
            if (in.op == Bytecode.Op.STRUCT && (in.s == null || in.s.isEmpty() || checker.allTypes.get(in.s) == null)) {
                return false;
            }
            if (in.op == Bytecode.Op.LOAD_ENUM && (in.s == null || in.s.isEmpty())) {
                return false;
            }
            if (in.op == Bytecode.Op.LOAD_FN && (in.s == null || in.s.isEmpty())) {
                return false;
            }
            if (in.op == Bytecode.Op.LOAD_SIGNAL
                    && (in.s == null || in.s.isEmpty() || !checker.signalArity.containsKey(in.s))) {
                return false;
            }
            if (in.op == Bytecode.Op.CLOSURE) {
                if (in.proto == null || in.proto.code == null || !opsOk(in.proto.code, checker)) {
                    return false;
                }
            }
            if (in.op == Bytecode.Op.JUMP && (in.a < 0 || in.a > n)) {
                return false;
            }
            if ((in.op == Bytecode.Op.JUMP_F || in.op == Bytecode.Op.JUMP_T) && (in.b < 0 || in.b > n)) {
                return false;
            }
            if (in.op == Bytecode.Op.SUPER && superHit(checker, in) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean callsOk(Bytecode.Fn fn, Selection sel, Checker checker) {
        for (Bytecode.Inst in : fn.insts) {
            if (in.op == Bytecode.Op.CALL) {
                if (isBuiltinCall(in.module, in.s)) {
                    continue;
                }
                String mod = in.module == null ? "" : in.module;
                if (isHostCall(mod, in.s)) {
                    continue;
                }
                if (!mod.isEmpty()) {
                    if (sel.modFns.contains(mod + "." + in.s)) {
                        continue;
                    }
                    return false;
                }
                if (!sel.fns.contains(in.s)) {
                    return false;
                }
            } else if (in.op == Bytecode.Op.LOAD_FN) {
                if (!sel.fns.contains(in.s)) {
                    return false;
                }
            } else if (in.op == Bytecode.Op.METHOD) {
                if (methodOpId(in.s) >= 0 || hasMethod(sel, in.s) || isEnumVariant(checker, in.s)
                        || isSignalMethod(in.s) || isFutureMethod(in.s)) {
                    continue;
                }
                return false;
            } else if (in.op == Bytecode.Op.SUPER) {
                Checker.MethodHit hit = superHit(checker, in);
                if (hit == null || !sel.methods.contains(hit.definedOn + "." + in.s)) {
                    return false;
                }
            } else if (in.op == Bytecode.Op.CLOSURE && in.proto != null && in.proto.code != null) {
                if (!callsOk(in.proto.code, sel, checker)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSignalMethod(String name) {
        return "connect".equals(name) || "disconnect".equals(name) || "emit".equals(name)
                || "emit_deferred".equals(name);
    }

    private static boolean isFutureMethod(String name) {
        return "cancel".equals(name);
    }

    private static boolean isBuiltinCall(String module, String name) {
        if (module != null && !module.isEmpty()) {
            return false;
        }
        return "print".equals(name) || "len".equals(name) || "assert".equals(name);
    }

    private static boolean isHostCall(String module, String name) {
        if (module == null) {
            module = "";
        }
        if (name == null) {
            name = "";
        }
        if (module.isEmpty()) {
            return "argv".equals(name) || "argv_len".equals(name);
        }
        if ("Future".equals(module)) {
            return "all".equals(name) || "race".equals(name);
        }
        if ("process".equals(module)) {
            return "argv".equals(name) || "argc".equals(name);
        }
        if ("checks".equals(module)) {
            return CHECK_HOST.contains(name);
        }
        if ("__time".equals(module)) {
            return "now".equals(name) || "sleep".equals(name) || "delay".equals(name);
        }
        if ("__math".equals(module)) {
            return MATH_HOST.contains(name);
        }
        if ("__str".equals(module)) {
            return STR_HOST.contains(name);
        }
        if ("__uuid".equals(module)) {
            return "v4".equals(name) || "parse".equals(name) || "valid".equals(name);
        }
        if ("__path".equals(module)) {
            return "join".equals(name) || "parent".equals(name) || "stem".equals(name);
        }
        if ("__io".equals(module)) {
            return IO_HOST.contains(name);
        }
        if ("__json".equals(module)) {
            return JSON_HOST.contains(name);
        }
        if ("__regex".equals(module)) {
            return REGEX_HOST.contains(name);
        }
        if ("__ui".equals(module)) {
            return UI_HOST.contains(name);
        }
        return false;
    }

    private static final Set<String> CHECK_HOST = Set.of("eq", "neq", "eq_string", "that", "truthy");
    private static final Set<String> MATH_HOST = Set.of(
            "pow", "powf", "atan2", "random", "rand_int", "sin", "cos", "sqrt", "to_int", "to_float", "floor", "ceil");
    private static final Set<String> STR_HOST = Set.of(
            "slice", "replace", "repeat", "contains", "starts_with", "ends_with", "split", "find",
            "length", "is_empty", "upper", "lower", "trim");
    private static final Set<String> IO_HOST = Set.of("exists", "remove", "read_text", "read_lines", "write_text");
    private static final Set<String> JSON_HOST = Set.of("parse", "valid", "stringify");
    private static final Set<String> REGEX_HOST = Set.of(
            "valid", "is_match", "find", "find_match", "captures", "findall", "replace", "split");

    private static final java.util.Set<String> UI_HOST = java.util.Set.of(
            "backend", "platform", "open", "close", "alive", "show", "hide", "title", "set_title",
            "width", "height", "set_size", "clear", "fill", "line", "stroke_rect", "fill_round",
            "stroke_round", "image_rgb", "image", "image_width", "image_height", "clip_push",
            "clip_pop", "text", "text_width", "font_height", "present", "wait", "run", "cursor",
            "mouse_x", "mouse_y", "mouse_down", "take_click", "take_right_click", "feed_click",
            "feed_right_click", "feed_mouse", "feed_down", "take_key", "key_code", "key_text",
            "feed_key", "take_scroll", "scroll_dx", "scroll_dy", "feed_scroll", "set_frame",
            "clipboard_get", "clipboard_set", "poll", "next_frame", "count");

    private static FnDecl moduleFn(Checker checker, String module, String name) {
        if (module == null || module.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }
        Checker.LoadedMod mod = checker.loaded.get(module);
        if (mod == null) {
            return null;
        }
        FnDecl fn = mod.exports.get(name);
        return fn != null ? fn : mod.fns.get(name);
    }

    private static FnDecl moduleFnKey(Checker checker, String key) {
        int split = key.lastIndexOf('.');
        if (split < 0) {
            return null;
        }
        return moduleFn(checker, key.substring(0, split), key.substring(split + 1));
    }

    private static void addModuleRefs(Bytecode.Fn fn, Selection sel, Checker checker) {
        if (fn == null) {
            return;
        }
        for (Bytecode.Inst in : fn.insts) {
            if (in.op == Bytecode.Op.CALL && in.module != null && !in.module.isEmpty()
                    && !isHostCall(in.module, in.s)) {
                FnDecl target = moduleFn(checker, in.module, in.s);
                if (target != null && target.code != null && opsOk(target.code, checker)
                        && sel.modFns.add(in.module + "." + in.s)) {
                    addModuleRefs(target.code, sel, checker);
                }
            }
            if (in.op == Bytecode.Op.CLOSURE && in.proto != null && in.proto.code != null) {
                addModuleRefs(in.proto.code, sel, checker);
            }
        }
    }

    private static boolean calleeAsync(Checker checker, String module, String name) {
        if (module != null && !module.isEmpty()) {
            FnDecl fn = moduleFn(checker, module, name);
            return fn != null && fn.isAsync;
        }
        return isAsyncFn(checker, name);
    }

    private static boolean hasTypeSignals(Checker checker) {
        for (Map<String, Integer> slot : checker.typeSignals.values()) {
            if (slot != null && !slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void internTypeSignals(Checker checker, List<String> strings) {
        for (Map.Entry<String, Map<String, Integer>> type : checker.typeSignals.entrySet()) {
            internString(strings, type.getKey());
            if (type.getValue() == null) {
                continue;
            }
            for (String name : type.getValue().keySet()) {
                internString(strings, name);
            }
        }
    }

    private static void emitTypeSignals(StringBuilder ir, Checker checker, List<String> strings) {
        for (Map.Entry<String, Map<String, Integer>> type : checker.typeSignals.entrySet()) {
            if (type.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, Integer> sig : type.getValue().entrySet()) {
                int t = internString(strings, type.getKey());
                int n = internString(strings, sig.getKey());
                int arity = sig.getValue() == null ? 0 : sig.getValue();
                ir.append("  call void @rg_register_type_signal(ptr @str").append(t).append(", ptr @str").append(n)
                        .append(", i32 ").append(arity).append(")\n");
            }
        }
    }

    private static Checker.MethodHit superHit(Checker checker, Bytecode.Inst in) {
        if (in.module == null || in.module.isEmpty() || in.s == null || in.s.isEmpty()) {
            return null;
        }
        Checker.MethodHit hit = checker.lookupMethod(in.module, in.s);
        if (hit.fn == null || hit.fn.isAbstract || hit.definedOn.isEmpty()) {
            return null;
        }
        return hit;
    }

    private static boolean isAsyncFn(Checker checker, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        FnDecl fn = checker.fns.get(name);
        return fn != null && fn.isAsync;
    }

    private static boolean isEnumVariant(Checker checker, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (EnumDecl en : checker.enums.values()) {
            for (EnumVariant v : en.variants) {
                if (name.equals(v.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMethod(Selection sel, String name) {
        for (String key : sel.methods) {
            if (methodName(key).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static FnDecl typeMethod(Checker checker, String key) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            return null;
        }
        Map<String, FnDecl> methods = checker.typeMethods.get(key.substring(0, dot));
        return methods == null ? null : methods.get(key.substring(dot + 1));
    }

    private static String methodType(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    private static String methodName(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    private static Map<String, List<String>> layouts(Checker checker) {
        Map<String, List<String>> layouts = new LinkedHashMap<>();
        for (Map.Entry<String, StructDecl> e : checker.allTypes.entrySet()) {
            layouts.put(e.getKey(), List.copyOf(e.getValue().fields));
        }
        return layouts;
    }

    private static void collectStructTypes(Bytecode.Fn fn, Set<String> types) {
        for (Bytecode.Inst in : fn.insts) {
            if (in.op == Bytecode.Op.STRUCT && in.s != null && !in.s.isEmpty()) {
                types.add(in.s);
            }
        }
    }

    private static void emitFieldTable(StringBuilder ir, String type, List<String> fields, List<String> strings) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        ir.append("@fields_").append(ident(type)).append(" = private unnamed_addr constant [")
                .append(fields.size()).append(" x ptr] [");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                ir.append(", ");
            }
            ir.append("ptr @str").append(internString(strings, fields.get(i)));
        }
        ir.append("]\n");
    }

    private static void internEnums(Checker checker, List<String> strings) {
        for (EnumDecl en : checker.enums.values()) {
            internString(strings, en.name);
            for (EnumVariant v : en.variants) {
                internString(strings, v.name);
                if (variantHasNames(v)) {
                    for (int i = 0; i < Math.max(v.arity, v.fieldNames.size()); i++) {
                        String field = i < v.fieldNames.size() && v.fieldNames.get(i) != null ? v.fieldNames.get(i) : "";
                        internString(strings, field);
                    }
                }
            }
        }
    }

    private static boolean variantHasNames(EnumVariant v) {
        for (String field : v.fieldNames) {
            if (field != null && !field.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String enumFieldsName(String type, String variant) {
        return "enumfields_" + ident(type) + "_" + ident(variant);
    }

    private static void emitEnumFieldTables(StringBuilder ir, EnumDecl en, List<String> strings) {
        boolean any = false;
        for (EnumVariant v : en.variants) {
            if (!variantHasNames(v) || v.arity <= 0) {
                continue;
            }
            any = true;
            ir.append("@").append(enumFieldsName(en.name, v.name)).append(" = private unnamed_addr constant [")
                    .append(v.arity).append(" x ptr] [");
            for (int i = 0; i < v.arity; i++) {
                if (i > 0) {
                    ir.append(", ");
                }
                String field = i < v.fieldNames.size() && v.fieldNames.get(i) != null ? v.fieldNames.get(i) : "";
                ir.append("ptr @str").append(internString(strings, field));
            }
            ir.append("]\n");
        }
        if (any) {
            ir.append('\n');
        }
    }

    private static void emitEnumRegisters(StringBuilder ir, EnumDecl en, List<String> strings) {
        int typeId = internString(strings, en.name);
        for (EnumVariant v : en.variants) {
            int varId = internString(strings, v.name);
            String fields = variantHasNames(v) && v.arity > 0 ? "@" + enumFieldsName(en.name, v.name) : "null";
            ir.append("  call void @rg_register_enum(ptr @str").append(typeId).append(", ptr @str").append(varId)
                    .append(", i32 ").append(v.arity).append(", ptr ").append(fields).append(")\n");
        }
    }

    private static void emitFn(StringBuilder ir, Bytecode.Fn fn, List<String> strings, String llvmName, Checker checker,
            Map<String, List<String>> layouts, Lambdas lambdas, int ncopy, Dbg dbg, FnDecl src) {
        int nregs = Math.max(fn.nregs, 1);
        int n = fn.insts.size();
        boolean[] start = blockStarts(fn);
        int sp = dbg.subprogram(src == null ? llvmName : src.name, src == null ? checker.file : src.file,
                src == null ? 1 : src.line);
        int srcLine = src == null || src.line < 1 ? 1 : src.line;
        ir.append("define void @").append(llvmName).append("(ptr %out, ptr %args, i32 %argc) !dbg !").append(sp)
                .append(" {\n");
        ir.append("entry:\n");
        ir.append("  %regs = alloca [").append(nregs).append(" x %RGValue]\n");
        attachDbg(ir, dbg, sp, srcLine, 1);
        int copy = Math.max(ncopy, 0);
        if (copy > nregs) {
            copy = nregs;
        }
        for (int i = 0; i < copy; i++) {
            ir.append("  %arg").append(i).append(" = getelementptr %RGValue, ptr %args, i32 ").append(i).append('\n');
            attachDbg(ir, dbg, sp, srcLine, 1);
            ir.append("  %dst").append(i).append(" = getelementptr inbounds [").append(nregs)
                    .append(" x %RGValue], ptr %regs, i32 0, i32 ").append(i).append('\n');
            attachDbg(ir, dbg, sp, srcLine, 1);
            ir.append("  call void @rg_copy(ptr %dst").append(i).append(", ptr %arg").append(i).append(")\n");
            attachDbg(ir, dbg, sp, srcLine, 1);
        }
        ir.append("  br label %b0\n");
        attachDbg(ir, dbg, sp, srcLine, 1);
        int[] tmp = {0};
        boolean[] flags = {false};
        boolean terminated = true;
        for (int i = 0; i < n; i++) {
            if (start[i]) {
                if (!terminated) {
                    ir.append("  br label %b").append(i).append('\n');
                    attachDbg(ir, dbg, sp, srcLine, 1);
                }
                ir.append("b").append(i).append(":\n");
                terminated = false;
            }
            if (terminated) {
                continue;
            }
            Bytecode.Inst in = fn.insts.get(i);
            if (in.op == Bytecode.Op.DEBUG) {
                continue;
            }
            int from = ir.length();
            terminated = emitInst(ir, fn, in, nregs, tmp, strings, i, checker, layouts, lambdas, flags);
            attachNewLines(ir, from, dbg, sp, in.line, in.col);
            if (!terminated && mayThrow(in.op)) {
                from = ir.length();
                terminated = emitThrowEdge(ir, fn, i, nregs, tmp, flags);
                attachNewLines(ir, from, dbg, sp, in.line, in.col);
            }
        }
        if (start[n]) {
            if (!terminated) {
                ir.append("  br label %b").append(n).append('\n');
                attachDbg(ir, dbg, sp, srcLine, 1);
            }
            ir.append("b").append(n).append(":\n");
            terminated = false;
        }
        if (!terminated) {
            ir.append("  call void @rg_set_void(ptr %out)\n");
            attachDbg(ir, dbg, sp, srcLine, 1);
            ir.append("  ret void\n");
            attachDbg(ir, dbg, sp, srcLine, 1);
        }
        emitCatchPads(ir, fn, nregs, tmp, flags[0], dbg, sp, srcLine);
        ir.append("}\n");
    }

    private static boolean[] blockStarts(Bytecode.Fn fn) {
        int n = fn.insts.size();
        boolean[] start = new boolean[n + 1];
        start[0] = true;
        for (int i = 0; i < n; i++) {
            Bytecode.Inst in = fn.insts.get(i);
            if (in.op == Bytecode.Op.JUMP) {
                mark(start, in.a);
            } else if (in.op == Bytecode.Op.JUMP_F || in.op == Bytecode.Op.JUMP_T) {
                mark(start, in.b);
                mark(start, i + 1);
            } else if (mayThrow(in.op)) {
                mark(start, i + 1);
            }
        }
        if (fn.handlers != null) {
            for (Bytecode.Fn.Handler h : fn.handlers) {
                mark(start, h.handler);
            }
        }
        return start;
    }

    private static void mark(boolean[] start, int pc) {
        if (pc >= 0 && pc < start.length) {
            start[pc] = true;
        }
    }

    private static boolean mayThrow(Bytecode.Op op) {
        return op == Bytecode.Op.CALL || op == Bytecode.Op.CALL_VAL || op == Bytecode.Op.METHOD
                || op == Bytecode.Op.AWAIT || op == Bytecode.Op.SUPER;
    }

    private static int handlerIndex(Bytecode.Fn fn, int pc) {
        if (fn.handlers == null) {
            return -1;
        }
        for (int i = 0; i < fn.handlers.size(); i++) {
            Bytecode.Fn.Handler h = fn.handlers.get(i);
            if (pc >= h.start && pc < h.end) {
                return i;
            }
        }
        return -1;
    }

    private static String throwTarget(Bytecode.Fn fn, int pc, boolean[] flags) {
        int h = handlerIndex(fn, pc);
        if (h >= 0) {
            return "%catch" + h;
        }
        flags[0] = true;
        return "%propagate";
    }

    private static boolean emitThrowEdge(StringBuilder ir, Bytecode.Fn fn, int pc, int nregs, int[] tmp, boolean[] flags) {
        int t = tmp[0]++;
        int c = tmp[0]++;
        ir.append("  %t").append(t).append(" = call i32 @rg_has_throw()\n");
        ir.append("  %t").append(c).append(" = icmp ne i32 %t").append(t).append(", 0\n");
        ir.append("  br i1 %t").append(c).append(", label ").append(throwTarget(fn, pc, flags)).append(", label %b")
                .append(pc + 1).append('\n');
        return true;
    }

    private static void emitCatchPads(StringBuilder ir, Bytecode.Fn fn, int nregs, int[] tmp, boolean propagate,
            Dbg dbg, int sp, int srcLine) {
        if (fn.handlers != null) {
            for (int i = 0; i < fn.handlers.size(); i++) {
                Bytecode.Fn.Handler h = fn.handlers.get(i);
                ir.append("catch").append(i).append(":\n");
                if (h.dest >= 0) {
                    int from = ir.length();
                    ir.append(slot(nregs, h.dest, tmp));
                    ir.append("  call void @rg_catch(ptr ").append(last(tmp)).append(")\n");
                    attachNewLines(ir, from, dbg, sp, srcLine, 1);
                }
                ir.append("  br label %b").append(h.handler).append('\n');
                attachDbg(ir, dbg, sp, srcLine, 1);
            }
        }
        if (propagate) {
            ir.append("propagate:\n");
            ir.append("  ret void\n");
            attachDbg(ir, dbg, sp, srcLine, 1);
        }
    }

    private static boolean emitInst(StringBuilder ir, Bytecode.Fn fn, Bytecode.Inst in, int nregs, int[] tmp,
            List<String> strings, int pc, Checker checker, Map<String, List<String>> layouts, Lambdas lambdas,
            boolean[] flags) {
        switch (in.op) {
            case LOAD_CONST -> {
                String dest = slot(nregs, in.a, tmp);
                ir.append(dest);
                Value k = fn.constants.get(in.b);
                String sp = last(tmp);
                switch (k.kind) {
                    case Bool -> ir.append("  call void @rg_set_bool(ptr ").append(sp).append(", i32 ")
                            .append(k.b ? 1 : 0).append(")\n");
                    case Int -> ir.append("  call void @rg_set_int(ptr ").append(sp).append(", i64 ")
                            .append(k.i).append(")\n");
                    case Float -> ir.append("  call void @rg_set_float(ptr ").append(sp).append(", double ")
                            .append(llvmDouble(k.real)).append(")\n");
                    case String -> {
                        int id = internString(strings, k.s == null ? "" : k.s);
                        ir.append("  call void @rg_set_string(ptr ").append(sp).append(", ptr @str")
                                .append(id).append(")\n");
                    }
                    default -> ir.append("  call void @rg_set_void(ptr ").append(sp).append(")\n");
                }
                return false;
            }
            case MOVE -> {
                String dst = slot(nregs, in.a, tmp);
                String src = slot(nregs, in.b, tmp);
                ir.append(dst);
                ir.append(src);
                ir.append("  call void @rg_copy(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(")\n");
                return false;
            }
            case BIN -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append(slot(nregs, in.c, tmp));
                ir.append("  call void @rg_bin(ptr %slot").append(tmp[0] - 3).append(", ptr %slot")
                        .append(tmp[0] - 2).append(", ptr %slot").append(tmp[0] - 1).append(", i32 ")
                        .append(binOpId(in.s)).append(")\n");
                return false;
            }
            case UNARY -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append("  call void @rg_unary(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(", i32 ").append(unaryOpId(in.s)).append(")\n");
                return false;
            }
            case TRUTHY -> {
                ir.append(slot(nregs, in.b, tmp));
                int src = tmp[0] - 1;
                int t = tmp[0]++;
                ir.append("  %t").append(t).append(" = call i32 @rg_truthy(ptr %slot").append(src).append(")\n");
                ir.append(slot(nregs, in.a, tmp));
                ir.append("  call void @rg_set_bool(ptr ").append(last(tmp)).append(", i32 %t").append(t).append(")\n");
                return false;
            }
            case CALL -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                String dp = "%slot" + (tmp[0] - 2);
                String bp = "%slot" + (tmp[0] - 1);
                if ("print".equals(in.s)) {
                    ir.append("  call void @rg_print(ptr ").append(bp).append(", i32 ").append(in.c).append(")\n");
                    ir.append("  call void @rg_set_void(ptr ").append(dp).append(")\n");
                } else if ("len".equals(in.s)) {
                    ir.append("  call void @rg_len(ptr ").append(dp).append(", ptr ").append(bp).append(")\n");
                } else if ("assert".equals(in.s)) {
                    ir.append("  call void @rg_assert(ptr ").append(dp).append(", ptr ").append(bp).append(", i32 ")
                            .append(in.c).append(")\n");
                } else if (isHostCall(in.module == null ? "" : in.module, in.s)) {
                    int mid = internString(strings, in.module);
                    int nid = internString(strings, in.s == null ? "" : in.s);
                    ir.append("  call void @rg_host_call(ptr ").append(dp).append(", ptr @str").append(mid)
                            .append(", ptr @str").append(nid).append(", ptr ").append(bp).append(", i32 ")
                            .append(in.c).append(")\n");
                } else if (in.module != null && !in.module.isEmpty()) {
                    String llvmName = mangleMod(in.module, in.s);
                    if (calleeAsync(checker, in.module, in.s)) {
                        ir.append("  call void @rg_spawn(ptr ").append(dp).append(", ptr @").append(llvmName)
                                .append(", ptr ").append(bp).append(", i32 ").append(in.c).append(")\n");
                    } else {
                        ir.append("  call void @").append(llvmName).append("(ptr ").append(dp).append(", ptr ")
                                .append(bp).append(", i32 ").append(in.c).append(")\n");
                    }
                } else if (calleeAsync(checker, "", in.s)) {
                    ir.append("  call void @rg_spawn(ptr ").append(dp).append(", ptr @").append(mangle(in.s))
                            .append(", ptr ").append(bp).append(", i32 ").append(in.c).append(")\n");
                } else {
                    ir.append("  call void @").append(mangle(in.s)).append("(ptr ").append(dp).append(", ptr ")
                            .append(bp).append(", i32 ").append(in.c).append(")\n");
                }
                return false;
            }
            case ARRAY -> {
                ir.append(slot(nregs, in.a, tmp));
                if (in.c <= 0) {
                    ir.append("  call void @rg_array(ptr ").append(last(tmp)).append(", ptr null, i32 0)\n");
                } else {
                    ir.append(slot(nregs, in.b, tmp));
                    ir.append("  call void @rg_array(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                            .append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                }
                return false;
            }
            case RANGE -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append(slot(nregs, in.c, tmp));
                ir.append("  call void @rg_range(ptr %slot").append(tmp[0] - 3).append(", ptr %slot")
                        .append(tmp[0] - 2).append(", ptr %slot").append(tmp[0] - 1).append(", i32 ")
                        .append("..=".equals(in.s) ? 1 : 0).append(")\n");
                return false;
            }
            case INDEX_GET -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append(slot(nregs, in.c, tmp));
                ir.append("  call void @rg_index_get(ptr %slot").append(tmp[0] - 3).append(", ptr %slot")
                        .append(tmp[0] - 2).append(", ptr %slot").append(tmp[0] - 1).append(")\n");
                return false;
            }
            case INDEX_SET -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append(slot(nregs, in.c, tmp));
                ir.append("  call void @rg_index_set(ptr %slot").append(tmp[0] - 3).append(", ptr %slot")
                        .append(tmp[0] - 2).append(", ptr %slot").append(tmp[0] - 1).append(")\n");
                return false;
            }
            case ITER_ITEMS -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append("  call void @rg_iter_items(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(")\n");
                return false;
            }
            case LEN -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append("  call void @rg_len(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(")\n");
                return false;
            }
            case MAP -> {
                ir.append(slot(nregs, in.a, tmp));
                if (in.c <= 0) {
                    ir.append("  call void @rg_map(ptr ").append(last(tmp)).append(", ptr null, i32 0)\n");
                } else {
                    ir.append(slot(nregs, in.b, tmp));
                    ir.append("  call void @rg_map(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                            .append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                }
                return false;
            }
            case METHOD -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                int op = methodOpId(in.s);
                if (op >= 0) {
                    ir.append("  call void @rg_method(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                            .append(tmp[0] - 1).append(", i32 ").append(in.c).append(", i32 ").append(op).append(")\n");
                } else {
                    int id = internString(strings, in.s == null ? "" : in.s);
                    ir.append("  call void @rg_call_method(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                            .append(tmp[0] - 1).append(", i32 ").append(in.c).append(", ptr @str").append(id)
                            .append(")\n");
                }
                return false;
            }
            case SUPER -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                Checker.MethodHit hit = superHit(checker, in);
                String definedOn = hit != null ? hit.definedOn : (in.module == null ? "" : in.module);
                String llvmName = mangleMethod(definedOn, in.s == null ? "" : in.s);
                if (hit != null && hit.fn != null && hit.fn.isAsync) {
                    ir.append("  call void @rg_spawn(ptr %slot").append(tmp[0] - 2).append(", ptr @").append(llvmName)
                            .append(", ptr %slot").append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                } else {
                    ir.append("  call void @").append(llvmName).append("(ptr %slot").append(tmp[0] - 2)
                            .append(", ptr %slot").append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                }
                return false;
            }
            case STRUCT -> {
                ir.append(slot(nregs, in.a, tmp));
                int typeId = internString(strings, in.s == null ? "" : in.s);
                int isData = checker.isDataType(in.s) ? 1 : 0;
                List<String> fields = layouts.getOrDefault(in.s, List.of());
                String fieldPtr = fields.isEmpty() ? "null" : "@fields_" + ident(in.s);
                if (in.c <= 0) {
                    ir.append("  call void @rg_struct(ptr ").append(last(tmp)).append(", ptr @str").append(typeId)
                            .append(", ptr null, i32 0, ptr ").append(fieldPtr).append(", i32 ").append(isData)
                            .append(")\n");
                } else {
                    ir.append(slot(nregs, in.b, tmp));
                    ir.append("  call void @rg_struct(ptr %slot").append(tmp[0] - 2).append(", ptr @str").append(typeId)
                            .append(", ptr %slot").append(tmp[0] - 1).append(", i32 ").append(in.c).append(", ptr ")
                            .append(fieldPtr).append(", i32 ").append(isData).append(")\n");
                }
                return false;
            }
            case MEMBER -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                int id = internString(strings, in.s == null ? "" : in.s);
                ir.append("  call void @rg_member(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(", ptr @str").append(id).append(")\n");
                return false;
            }
            case FIELD_SET -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                int id = internString(strings, in.s == null ? "" : in.s);
                ir.append("  call void @rg_field_set(ptr %slot").append(tmp[0] - 2).append(", ptr @str").append(id)
                        .append(", ptr %slot").append(tmp[0] - 1).append(")\n");
                return false;
            }
            case LOAD_ENUM -> {
                ir.append(slot(nregs, in.a, tmp));
                int id = internString(strings, in.s == null ? "" : in.s);
                ir.append("  call void @rg_enum_type(ptr ").append(last(tmp)).append(", ptr @str").append(id)
                        .append(")\n");
                return false;
            }
            case LOAD_SIGNAL -> {
                ir.append(slot(nregs, in.a, tmp));
                int id = internString(strings, in.s == null ? "" : in.s);
                Integer arity = checker.signalArity.get(in.s);
                ir.append("  call void @rg_signal(ptr ").append(last(tmp)).append(", ptr @str").append(id)
                        .append(", i32 ").append(arity == null ? 0 : arity).append(")\n");
                return false;
            }
            case MATCH_LIT -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append(slot(nregs, in.c, tmp));
                ir.append("  call void @rg_match_lit(ptr %slot").append(tmp[0] - 3).append(", ptr %slot")
                        .append(tmp[0] - 2).append(", ptr %slot").append(tmp[0] - 1).append(")\n");
                return false;
            }
            case MATCH_VAR -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                int id = internString(strings, in.s == null ? "" : in.s);
                ir.append("  call void @rg_match_var(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(", ptr @str").append(id).append(")\n");
                return false;
            }
            case PAYLOAD -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                String field = in.s == null || in.s.isEmpty() ? "null" : "@str" + internString(strings, in.s);
                ir.append("  call void @rg_payload(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(", i32 ").append(in.c).append(", ptr ").append(field).append(")\n");
                return false;
            }
            case LOAD_FN -> {
                ir.append(slot(nregs, in.a, tmp));
                FnDecl target = checker.fns.get(in.s);
                int arity = target == null ? 0 : target.params.size();
                ir.append("  call void @rg_fn_ref(ptr ").append(last(tmp)).append(", ptr @").append(mangle(in.s))
                        .append(", i32 ").append(arity).append(")\n");
                return false;
            }
            case CLOSURE -> {
                ir.append(slot(nregs, in.a, tmp));
                String llvmName = lambdas.names.get(in.proto);
                int arity = in.proto != null && in.proto.code != null ? in.proto.code.arity : 0;
                if (llvmName == null) {
                    llvmName = "rg_fn_lam0";
                }
                if (in.c <= 0) {
                    ir.append("  call void @rg_closure(ptr ").append(last(tmp)).append(", ptr @").append(llvmName)
                            .append(", i32 ").append(arity).append(", ptr null, i32 0)\n");
                } else {
                    ir.append(slot(nregs, in.b, tmp));
                    ir.append("  call void @rg_closure(ptr %slot").append(tmp[0] - 2).append(", ptr @")
                            .append(llvmName).append(", i32 ").append(arity).append(", ptr %slot")
                            .append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                }
                return false;
            }
            case CALL_VAL -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append("  call void @rg_call_val(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(", i32 ").append(in.c).append(")\n");
                return false;
            }
            case AWAIT -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append(slot(nregs, in.b, tmp));
                ir.append("  call void @rg_await(ptr %slot").append(tmp[0] - 2).append(", ptr %slot")
                        .append(tmp[0] - 1).append(")\n");
                return false;
            }
            case THROW -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append("  call void @rg_throw(ptr ").append(last(tmp)).append(")\n");
                ir.append("  br label ").append(throwTarget(fn, pc, flags)).append('\n');
                return true;
            }
            case JUMP -> {
                ir.append("  br label %b").append(in.a).append('\n');
                return true;
            }
            case JUMP_F, JUMP_T -> {
                ir.append(slot(nregs, in.a, tmp));
                int cond = tmp[0] - 1;
                int t = tmp[0]++;
                int c = tmp[0]++;
                ir.append("  %t").append(t).append(" = call i32 @rg_truthy(ptr %slot").append(cond).append(")\n");
                ir.append("  %t").append(c).append(" = icmp ne i32 %t").append(t).append(", 0\n");
                int fall = pc + 1;
                if (in.op == Bytecode.Op.JUMP_F) {
                    ir.append("  br i1 %t").append(c).append(", label %b").append(fall).append(", label %b")
                            .append(in.b).append('\n');
                } else {
                    ir.append("  br i1 %t").append(c).append(", label %b").append(in.b).append(", label %b")
                            .append(fall).append('\n');
                }
                return true;
            }
            case RET -> {
                ir.append(slot(nregs, in.a, tmp));
                ir.append("  call void @rg_copy(ptr %out, ptr ").append(last(tmp)).append(")\n");
                ir.append("  ret void\n");
                return true;
            }
            case RET_VOID -> {
                ir.append("  call void @rg_set_void(ptr %out)\n");
                ir.append("  ret void\n");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static String slot(int nregs, int index, int[] tmp) {
        int id = tmp[0]++;
        return "  %slot" + id + " = getelementptr inbounds [" + nregs + " x %RGValue], ptr %regs, i32 0, i32 "
                + index + "\n";
    }

    private static String last(int[] tmp) {
        return "%slot" + (tmp[0] - 1);
    }

    static int binOpId(String op) {
        if (op == null) {
            return -1;
        }
        return switch (op) {
            case "+" -> 1;
            case "-" -> 2;
            case "*" -> 3;
            case "/" -> 4;
            case "%" -> 5;
            case "==" -> 6;
            case "!=" -> 7;
            case "<" -> 8;
            case ">" -> 9;
            case "<=" -> 10;
            case ">=" -> 11;
            default -> -1;
        };
    }

    static int unaryOpId(String op) {
        if ("-".equals(op)) {
            return 1;
        }
        if ("!".equals(op)) {
            return 2;
        }
        return -1;
    }

    static int methodOpId(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name) {
            case "len" -> 1;
            case "push" -> 2;
            case "pop" -> 3;
            case "has" -> 4;
            case "keys" -> 5;
            case "remove" -> 6;
            case "insert" -> 7;
            default -> -1;
        };
    }

    private static void collectStrings(Bytecode.Fn fn, List<String> strings) {
        for (Bytecode.Inst in : fn.insts) {
            if (in.op == Bytecode.Op.STRUCT || in.op == Bytecode.Op.MEMBER || in.op == Bytecode.Op.FIELD_SET
                    || in.op == Bytecode.Op.METHOD || in.op == Bytecode.Op.LOAD_ENUM
                    || in.op == Bytecode.Op.MATCH_VAR || in.op == Bytecode.Op.PAYLOAD
                    || in.op == Bytecode.Op.LOAD_SIGNAL || in.op == Bytecode.Op.CALL) {
                if (in.s != null && !in.s.isEmpty()) {
                    internString(strings, in.s);
                }
                if (in.op == Bytecode.Op.CALL) {
                    internString(strings, in.module == null ? "" : in.module);
                }
            }
            if (in.op != Bytecode.Op.LOAD_CONST || in.b < 0 || in.b >= fn.constants.size()) {
                continue;
            }
            Value k = fn.constants.get(in.b);
            if (k.kind == Value.Kind.String) {
                internString(strings, k.s == null ? "" : k.s);
            }
        }
    }

    private static int internString(List<String> strings, String s) {
        int idx = strings.indexOf(s);
        if (idx >= 0) {
            return idx;
        }
        strings.add(s);
        return strings.size() - 1;
    }

    private static void emitStringGlobal(StringBuilder ir, int id, String s) {
        String marker = "@str" + id + " =";
        if (ir.indexOf(marker) >= 0) {
            return;
        }
        byte[] raw = (s + "\0").getBytes(StandardCharsets.UTF_8);
        ir.append("@str").append(id).append(" = private unnamed_addr constant [").append(raw.length)
                .append(" x i8] c\"").append(llvmBytes(raw)).append("\"\n");
    }

    private static String llvmBytes(byte[] raw) {
        StringBuilder ss = new StringBuilder();
        for (byte b : raw) {
            int v = b & 0xff;
            if (v >= 32 && v < 127 && v != '"' && v != '\\') {
                ss.append((char) v);
            } else {
                ss.append('\\');
                ss.append("0123456789ABCDEF".charAt(v >> 4));
                ss.append("0123456789ABCDEF".charAt(v & 15));
            }
        }
        return ss.toString();
    }

    private static String llvmDouble(double x) {
        if (Double.isNaN(x)) {
            return "0x7FF8000000000000";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "0x7FF0000000000000" : "0xFFF0000000000000";
        }
        String hex = Long.toHexString(Double.doubleToRawLongBits(x)).toUpperCase();
        while (hex.length() < 16) {
            hex = "0" + hex;
        }
        return "0x" + hex;
    }

    static String ident(String name) {
        StringBuilder ss = new StringBuilder();
        if (name == null || name.isEmpty()) {
            return "anon";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_') {
                ss.append(c);
            } else {
                ss.append('_');
            }
        }
        return ss.toString();
    }

    static String mangle(String name) {
        return "rg_fn_" + ident(name);
    }

    static String mangleMod(String module, String name) {
        return "rg_fn_" + ident(module == null ? "" : module) + "_" + ident(name == null ? "" : name);
    }

    static String mangleMethod(String type, String method) {
        return "rg_fn_" + ident(type) + "_" + ident(method);
    }

    private static void attachDbg(StringBuilder ir, Dbg dbg, int sp, int line, int col) {
        if (dbg == null) {
            return;
        }
        int nl = ir.lastIndexOf("\n");
        if (nl < 0) {
            return;
        }
        String last = ir.substring(0, nl);
        int prev = last.lastIndexOf('\n');
        String text = last.substring(prev + 1).strip();
        if (text.isEmpty() || text.endsWith(":") || text.startsWith(";")) {
            return;
        }
        ir.insert(nl, ", !dbg !" + dbg.location(sp, line, col));
    }

    private static void attachNewLines(StringBuilder ir, int from, Dbg dbg, int sp, int line, int col) {
        if (dbg == null) {
            return;
        }
        String tag = ", !dbg !" + dbg.location(sp, line, col);
        int i = Math.max(from, 0);
        while (i < ir.length()) {
            int nl = ir.indexOf("\n", i);
            if (nl < 0) {
                break;
            }
            String text = ir.substring(i, nl).strip();
            if (!text.isEmpty() && !text.endsWith(":") && !text.startsWith(";") && !text.contains("!dbg")) {
                ir.insert(nl, tag);
                nl += tag.length();
            }
            i = nl + 1;
        }
    }

    private static final class Dbg {
        private final List<String> nodes = new ArrayList<>();
        private final Map<String, Integer> files = new LinkedHashMap<>();
        private final Map<String, Integer> locs = new LinkedHashMap<>();
        private final int empty;
        private final int subTy;
        private final int cu;
        private final int dwarfVer;
        private final int dbgVer;

        Dbg(String path) {
            empty = add("!{}");
            int file = fileId(path);
            cu = add("distinct !DICompileUnit(language: DW_LANG_C99, file: !" + file
                    + ", producer: \"RoseGold\", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug, enums: !"
                    + empty + ")");
            subTy = add("!DISubroutineType(types: !" + empty + ")");
            dwarfVer = add("!{i32 7, !\"Dwarf Version\", i32 4}");
            dbgVer = add("!{i32 2, !\"Debug Info Version\", i32 3}");
        }

        int subprogram(String name, String path, int line) {
            int file = fileId(path);
            String nm = name == null || name.isEmpty() ? "fn" : name;
            return add("distinct !DISubprogram(name: \"" + esc(nm) + "\", scope: !" + file + ", file: !" + file
                    + ", line: " + Math.max(line, 1) + ", type: !" + subTy
                    + ", spFlags: DISPFlagDefinition, unit: !" + cu + ")");
        }

        int location(int sp, int line, int col) {
            String key = sp + ":" + Math.max(line, 1) + ":" + Math.max(col, 1);
            Integer hit = locs.get(key);
            if (hit != null) {
                return hit;
            }
            int id = add("!DILocation(line: " + Math.max(line, 1) + ", column: " + Math.max(col, 1)
                    + ", scope: !" + sp + ")");
            locs.put(key, id);
            return id;
        }

        void emit(StringBuilder ir) {
            ir.append("\n!llvm.dbg.cu = !{!").append(cu).append("}\n");
            ir.append("!llvm.module.flags = !{!").append(dwarfVer).append(", !").append(dbgVer).append("}\n");
            for (String node : nodes) {
                ir.append(node).append('\n');
            }
        }

        private int fileId(String path) {
            String raw = path == null || path.isEmpty() ? "unknown.rg" : path.replace('\\', '/');
            Integer hit = files.get(raw);
            if (hit != null) {
                return hit;
            }
            Path p = Path.of(raw);
            String fileName = p.getFileName() == null ? raw : p.getFileName().toString();
            String dir = p.getParent() == null ? "." : p.getParent().toString().replace('\\', '/');
            int id = add("!DIFile(filename: \"" + esc(fileName) + "\", directory: \"" + esc(dir) + "\")");
            files.put(raw, id);
            return id;
        }

        private int add(String body) {
            int id = nodes.size();
            nodes.add("!" + id + " = " + body);
            return id;
        }

        private static String esc(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}

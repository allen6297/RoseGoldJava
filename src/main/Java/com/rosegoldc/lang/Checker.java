package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Checker {

    final Program program;
    final String file;
    final List<Diagnostic> diagnostics;
    final Map<String, FnDecl> fns = new LinkedHashMap<>();
    final Map<String, StructDecl> structs = new LinkedHashMap<>();
    final Map<String, StructDecl> allTypes = new LinkedHashMap<>();
    final Map<String, TraitDecl> traits = new LinkedHashMap<>();
    final Map<String, EnumDecl> enums = new LinkedHashMap<>();
    final Map<String, String> classParents = new LinkedHashMap<>();
    final Map<String, Boolean> classAbstract = new LinkedHashMap<>();
    final Map<String, Boolean> classFinal = new LinkedHashMap<>();
    final Map<String, Map<String, Vis>> fieldAccess = new LinkedHashMap<>();
    final Map<String, Map<String, Expr>> fieldDefaults = new LinkedHashMap<>();
    final Map<String, List<String>> typeTraits = new LinkedHashMap<>();
    final List<TraitImplInfo> traitImpls = new ArrayList<>();
    final Map<String, Map<String, FnDecl>> typeMethods = new LinkedHashMap<>();
    final Map<String, Map<String, Integer>> typeSignals = new LinkedHashMap<>();
    final Map<String, Integer> signalArity = new LinkedHashMap<>();
    final Map<String, LoadedMod> loaded = new LinkedHashMap<>();
    final Map<String, String> moduleBinds = new LinkedHashMap<>();
    final List<Program> extras = new ArrayList<>();
    final List<String> loading = new ArrayList<>();
    String currentModule = "";
    private Project project;
    private boolean projectLoaded;

    static final class TraitImplInfo {
        List<TypeParam> typeParams = new ArrayList<>();
        String typeName = "";
        String traitName = "";
        int line = 1;
    }

    static final class LoadedMod {
        final Map<String, FnDecl> fns = new LinkedHashMap<>();
        final Map<String, FnDecl> exports = new LinkedHashMap<>();
        final Map<String, List<FnDecl>> ufcsFns = new LinkedHashMap<>();
        final Map<String, StructDecl> structs = new LinkedHashMap<>();
        final Map<String, StructDecl> exportStructs = new LinkedHashMap<>();
        final Map<String, EnumDecl> enums = new LinkedHashMap<>();
        final Map<String, EnumDecl> exportEnums = new LinkedHashMap<>();
        final Map<String, String> modules = new LinkedHashMap<>();
        final Map<String, String> exportMods = new LinkedHashMap<>();
        final Map<String, FnDecl> fromFns = new LinkedHashMap<>();
    }

    static final class MethodHit {
        final String definedOn;
        final FnDecl fn;

        MethodHit(String definedOn, FnDecl fn) {
            this.definedOn = definedOn == null ? "" : definedOn;
            this.fn = fn;
        }
    }

    static final class FieldHit {
        final String definedOn;
        final Vis vis;

        FieldHit(String definedOn, Vis vis) {
            this.definedOn = definedOn == null ? "" : definedOn;
            this.vis = vis == null ? Vis.Pub : vis;
        }
    }

    private Checker(Program program, String file, List<Diagnostic> diagnostics) {
        this.program = program;
        this.file = file == null ? "" : file;
        this.diagnostics = diagnostics;
    }

    static Checker check(Program program, String file, List<Diagnostic> diagnostics) {
        Checker env = new Checker(program, file, diagnostics);
        env.ingestEntry();
        env.loadImports(program.imports, file, null);
        env.applyInheritance();
        env.checkTraitImpls();
        env.checkAbstractFinal();
        env.bindTraitSignals();
        env.checkConstexprFns();
        new TypeChecker(env).run();
        boolean errors = false;
        for (Diagnostic d : diagnostics) {
            if ("error".equals(d.severity)) {
                errors = true;
                break;
            }
        }
        if (!errors) {
            Compile.attach(env);
        }
        return env;
    }

    void recordDiag(String kind, String atFile, int line, int col, String msg) {
        Diagnostic d = new Diagnostic();
        d.file = atFile == null || atFile.isEmpty() ? file : atFile;
        d.line = line > 0 ? line : 1;
        d.col = col > 0 ? col : 1;
        d.severity = "error";
        d.message = msg;
        d.kind = kind;
        for (Diagnostic prev : diagnostics) {
            if (prev.file.equals(d.file) && prev.line == d.line && prev.col == d.col
                    && prev.message.equals(d.message)) {
                return;
            }
        }
        diagnostics.add(d);
    }

    void loadFail(String atFile, String msg, int line, int col) {
        recordDiag("runtime error", atFile, line, col, msg);
    }

    String crateNameOfFile(String path) {
        return Stdlib.crateNameOfFile(path);
    }

    String findModuleBind(String name) {
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit == null) {
                return null;
            }
            return lit.modules.get(name);
        }
        return moduleBinds.get(name);
    }

    StructDecl findStruct(String name) {
        String head = Types.typeHead(name);
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit != null) {
                StructDecl st = lit.structs.get(head);
                if (st != null) {
                    return st;
                }
                st = importedStruct(lit.modules, head, false);
                if (st != null) {
                    return st;
                }
            }
        }
        StructDecl st = structs.get(head);
        if (st != null) {
            return st;
        }
        return importedStruct(moduleBinds, head, true);
    }

    TraitDecl findTrait(String name) {
        return traits.get(Types.typeHead(name));
    }

    boolean isDataType(String name) {
        String head = Types.typeHead(name);
        StructDecl st = allTypes.get(head);
        if (st != null) {
            return st.isData;
        }
        st = findStruct(head);
        return st != null && st.isData;
    }

    EnumDecl findEnum(String name) {
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit != null) {
                EnumDecl en = lit.enums.get(name);
                if (en != null) {
                    return en;
                }
                en = importedEnum(lit.modules, name, false);
                if (en != null) {
                    return en;
                }
            }
        }
        EnumDecl en = enums.get(name);
        if (en != null) {
            return en;
        }
        return importedEnum(moduleBinds, name, true);
    }

    private EnumDecl importedEnum(Map<String, String> binds, String name, boolean cratesOnly) {
        for (String target : importedTargets(binds, cratesOnly)) {
            LoadedMod m = loaded.get(target);
            if (m == null) {
                continue;
            }
            EnumDecl en = m.exportEnums.get(name);
            if (en == null) {
                en = m.enums.get(name);
            }
            if (en != null) {
                return en;
            }
        }
        return null;
    }

    private StructDecl importedStruct(Map<String, String> binds, String name, boolean cratesOnly) {
        for (String target : importedTargets(binds, cratesOnly)) {
            LoadedMod m = loaded.get(target);
            if (m == null) {
                continue;
            }
            StructDecl st = m.exportStructs.get(name);
            if (st == null) {
                st = m.structs.get(name);
            }
            if (st != null) {
                return st;
            }
        }
        return null;
    }

    private List<String> importedTargets(Map<String, String> binds, boolean cratesOnly) {
        List<String> queue = new ArrayList<>();
        if (binds == null || binds.isEmpty()) {
            return queue;
        }
        Set<String> seen = new HashSet<>();
        for (String target : binds.values()) {
            if (target == null || target.isEmpty() || !seen.add(target)) {
                continue;
            }
            if (cratesOnly && !Types.isCrateStdlib(target)) {
                continue;
            }
            queue.add(target);
        }
        for (int i = 0; i < queue.size(); i++) {
            LoadedMod m = loaded.get(queue.get(i));
            if (m == null) {
                continue;
            }
            for (String target : m.exportMods.values()) {
                if (target != null && !target.isEmpty() && seen.add(target)
                        && (!cratesOnly || Types.isCrateStdlib(target))) {
                    queue.add(target);
                }
            }
            if (!cratesOnly) {
                for (String target : m.modules.values()) {
                    if (target != null && !target.isEmpty() && seen.add(target)) {
                        queue.add(target);
                    }
                }
            }
        }
        return queue;
    }

    FnDecl findLocalFn(String name) {
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit != null) {
                FnDecl fn = lit.fns.get(name);
                if (fn != null) {
                    return fn;
                }
                return lit.fromFns.get(name);
            }
            return null;
        }
        return fns.get(name);
    }

    FnDecl findUfcs(String name, String recvTy) {
        List<FnDecl> candidates = new ArrayList<>();
        java.util.function.Consumer<FnDecl> addCand = fn -> {
            if (fn == null || !fn.isUfcs) {
                return;
            }
            if (!candidates.contains(fn)) {
                candidates.add(fn);
            }
        };
        java.util.function.Consumer<LoadedMod> addFromMod = m -> {
            List<FnDecl> ufcs = m.ufcsFns.get(name);
            if (ufcs != null) {
                for (FnDecl fn : ufcs) {
                    addCand.accept(fn);
                }
                return;
            }
            FnDecl eit = m.exports.get(name);
            if (eit != null) {
                addCand.accept(eit);
            }
            FnDecl fit = m.fns.get(name);
            if (fit != null) {
                addCand.accept(fit);
            }
        };
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit != null) {
                addFromMod.accept(lit);
                FnDecl from = lit.fromFns.get(name);
                if (from != null) {
                    addCand.accept(from);
                }
            }
        } else {
            addCand.accept(findLocalFn(name));
        }
        List<String> queue = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (!currentModule.isEmpty()) {
            LoadedMod lit = loaded.get(currentModule);
            if (lit != null) {
                queue.addAll(lit.modules.values());
            }
        } else {
            queue.addAll(moduleBinds.values());
        }
        for (int i = 0; i < queue.size(); i++) {
            String modName = queue.get(i);
            if (!seen.add(modName)) {
                continue;
            }
            LoadedMod lit = loaded.get(modName);
            if (lit == null) {
                continue;
            }
            addFromMod.accept(lit);
            queue.addAll(lit.exportMods.values());
            queue.addAll(lit.modules.values());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (recvTy == null || recvTy.isEmpty() || candidates.size() == 1) {
            return candidates.getFirst();
        }
        String want = Types.typeHead(recvTy);
        FnDecl exact = null;
        FnDecl fallback = null;
        for (FnDecl fn : candidates) {
            if (fn.paramTypes.isEmpty() || fn.paramTypes.getFirst().isEmpty()) {
                if (fallback == null) {
                    fallback = fn;
                }
                continue;
            }
            if (Types.typeHead(fn.paramTypes.getFirst()).equals(want)) {
                exact = fn;
                break;
            }
        }
        if (exact != null) {
            return exact;
        }
        return fallback != null ? fallback : candidates.getFirst();
    }

    Integer lookupTypeSignal(String typeName, String name) {
        String current = Types.typeHead(typeName);
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            Map<String, Integer> it = typeSignals.get(current);
            if (it != null && it.containsKey(name)) {
                return it.get(name);
            }
            String pit = classParents.get(current);
            if (pit == null) {
                break;
            }
            current = Types.typeHead(pit);
        }
        return null;
    }

    MethodHit lookupMethod(String typeName, String name) {
        String current = Types.typeHead(typeName);
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            Map<String, FnDecl> typeIt = typeMethods.get(current);
            if (typeIt != null && typeIt.containsKey(name)) {
                return new MethodHit(current, typeIt.get(name));
            }
            String pit = classParents.get(current);
            if (pit == null) {
                break;
            }
            current = Types.typeHead(pit);
        }
        return new MethodHit("", null);
    }

    FieldHit lookupField(String typeName, String name) {
        String current = Types.typeHead(typeName);
        Set<String> seen = new HashSet<>();
        while (!current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            Map<String, Vis> typeIt = fieldAccess.get(current);
            if (typeIt != null && typeIt.containsKey(name)) {
                return new FieldHit(current, typeIt.get(name));
            }
            String pit = classParents.get(current);
            if (pit == null) {
                break;
            }
            current = Types.typeHead(pit);
        }
        return new FieldHit("", Vis.Pub);
    }

    private void ingestEntry() {
        String crate = crateNameOfFile(file);
        if (!crate.isEmpty()) {
            ingestCrateEntry(crate);
            return;
        }
        for (FnDecl fn : program.fns) {
            fns.put(fn.name, fn);
        }
        for (StructDecl st : program.structs) {
            if (structs.containsKey(st.name)) {
                loadFail(file, "duplicate struct '" + st.name + "'", st.line, 1);
                continue;
            }
            if (fns.containsKey(st.name)) {
                loadFail(file, "struct '" + st.name + "' conflicts with a function", st.line, 1);
                continue;
            }
            structs.put(st.name, st);
            allTypes.put(st.name, st);
            recordFieldAccess(st.name, st.fields, st.fieldVis);
            for (FnDecl m : st.methods) {
                typeMethods.computeIfAbsent(st.name, k -> new LinkedHashMap<>()).put(m.name, m);
            }
            for (String t : st.implTraits) {
                recordTraitImpl(Types.selfApplied(st.name, st.typeParams), t, st.typeParams, st.line);
            }
            registerTypeSignals(st.name, st.signals, file);
        }
        ingestClasses(null, program.classes, false, "", file);
        ingestTraits(program.traits, file);
        ingestEnums(null, program.enums, false, "", file);
        ingestImpls(program.impls, file);
        for (SignalDecl sig : program.signals) {
            if (signalArity.containsKey(sig.name)) {
                loadFail(file, "duplicate signal '" + sig.name + "'", sig.line, 1);
                continue;
            }
            if (fns.containsKey(sig.name)) {
                loadFail(file, "signal '" + sig.name + "' conflicts with a function", sig.line, 1);
                continue;
            }
            if (structs.containsKey(sig.name) || allTypes.containsKey(sig.name)) {
                loadFail(file, "signal '" + sig.name + "' conflicts with a struct", sig.line, 1);
                continue;
            }
            signalArity.put(sig.name, sig.params.size());
        }
        for (ModDecl m : program.mods) {
            if (fns.containsKey(m.name) || structs.containsKey(m.name) || allTypes.containsKey(m.name)
                    || enums.containsKey(m.name) || signalArity.containsKey(m.name)) {
                loadFail(file, "module '" + m.name + "' conflicts with an existing name", m.line, 1);
                continue;
            }
            registerModShells(m, m.name);
        }
        for (ModDecl m : program.mods) {
            ingestLoadedMod(m, m.name, file);
            moduleBinds.put(m.name, m.name);
        }
    }

    private void stampFnFile(FnDecl fn, String atFile) {
        if (fn.file.isEmpty()) {
            fn.file = atFile;
        }
    }

    private void ingestTraits(List<TraitDecl> items, String atFile) {
        for (TraitDecl t : items) {
            if (traits.containsKey(t.name)) {
                loadFail(atFile, "duplicate trait '" + t.name + "'", t.line, 1);
                continue;
            }
            traits.put(t.name, t);
            registerTypeSignals(t.name, t.signals, atFile);
        }
    }

    private void ingestEnums(LoadedMod m, List<EnumDecl> items, boolean fromMod, String modName, String atFile) {
        ingestEnums(m, items, fromMod, modName, atFile, true);
    }

    private void ingestEnums(LoadedMod m, List<EnumDecl> items, boolean fromMod, String modName, String atFile,
            boolean publishGlobal) {
        for (EnumDecl e : items) {
            if (m != null) {
                if (m.enums.containsKey(e.name)) {
                    loadFail(atFile, "duplicate export '" + e.name + "' in module '" + modName + "'", e.line, 1);
                }
                m.enums.put(e.name, e);
            }
            if (fns.containsKey(e.name)) {
                loadFail(atFile, "enum '" + e.name + "' conflicts with a function", e.line, 1);
            }
            if (structs.containsKey(e.name) || allTypes.containsKey(e.name)) {
                loadFail(atFile, "enum '" + e.name + "' conflicts with a type", e.line, 1);
            }
            if (m == null || !fromMod || e.isPub) {
                if (m != null) {
                    m.exportEnums.put(e.name, e);
                }
                if (publishGlobal) {
                    if (enums.containsKey(e.name)) {
                        loadFail(atFile, "duplicate enum '" + e.name + "'", e.line, 1);
                    }
                    enums.put(e.name, e);
                }
            }
        }
    }

    private void recordTypeTrait(String typeName, String traitName) {
        List<String> list = typeTraits.computeIfAbsent(Types.typeHead(typeName), k -> new ArrayList<>());
        if (!list.contains(traitName)) {
            list.add(traitName);
        }
    }

    private void recordTraitImpl(String typeName, String traitName, List<TypeParam> params, int line) {
        recordTypeTrait(typeName, traitName);
        TraitImplInfo info = new TraitImplInfo();
        info.typeParams = params;
        info.typeName = typeName;
        info.traitName = traitName;
        info.line = line;
        traitImpls.add(info);
    }

    private void registerTypeSignals(String typeName, List<SignalDecl> sigs, String atFile) {
        StructDecl st = allTypes.get(typeName);
        Map<String, FnDecl> mit = typeMethods.get(typeName);
        for (SignalDecl sig : sigs) {
            if (st != null) {
                for (String f : st.fields) {
                    if (f.equals(sig.name)) {
                        loadFail(atFile, "signal '" + sig.name + "' conflicts with field '" + sig.name
                                + "' on " + typeName, sig.line, 1);
                    }
                }
            }
            if (mit != null && mit.containsKey(sig.name)) {
                loadFail(atFile, "signal '" + sig.name + "' conflicts with method '" + sig.name
                        + "' on " + typeName, sig.line, 1);
            }
            Map<String, Integer> slot = typeSignals.computeIfAbsent(typeName, k -> new LinkedHashMap<>());
            if (slot.containsKey(sig.name)) {
                loadFail(atFile, "duplicate signal '" + sig.name + "' on " + typeName, sig.line, 1);
                continue;
            }
            slot.put(sig.name, sig.params.size());
        }
    }

    private void ingestClasses(LoadedMod m, List<ClassDecl> items, boolean fromMod, String modName, String atFile) {
        for (ClassDecl c : items) {
            c.shape.name = c.name;
            c.shape.isPub = c.isPub;
            c.shape.line = c.line;
            c.shape.typeParams.clear();
            c.shape.typeParams.addAll(c.typeParams);
            c.shape.fields.clear();
            c.shape.fieldTypes.clear();
            c.shape.fieldOptional.clear();
            for (ClassField f : c.fields) {
                c.shape.fields.add(f.name);
                c.shape.fieldTypes.add(f.type);
                c.shape.fieldOptional.add(f.optional);
            }
            if (m != null) {
                if (m.structs.containsKey(c.name)) {
                    loadFail(atFile, "duplicate export '" + c.name + "' in module '" + modName + "'", c.line, 1);
                }
                m.structs.put(c.name, c.shape);
            }
            if (allTypes.containsKey(c.name)) {
                loadFail(atFile, "duplicate class '" + c.name + "'", c.line, 1);
                continue;
            }
            if (fns.containsKey(c.name)) {
                loadFail(atFile, "class '" + c.name + "' conflicts with a function", c.line, 1);
                continue;
            }
            allTypes.put(c.name, c.shape);
            for (ClassField f : c.fields) {
                if (f.hasDefault) {
                    fieldDefaults.computeIfAbsent(c.name, k -> new LinkedHashMap<>()).put(f.name, f.defaultValue);
                }
                fieldAccess.computeIfAbsent(c.name, k -> new LinkedHashMap<>()).put(f.name, f.vis);
            }
            if (!c.parent.isEmpty()) {
                classParents.put(c.name, c.parent);
            }
            classAbstract.put(c.name, c.isAbstract);
            classFinal.put(c.name, c.isFinal);
            Map<String, FnDecl> slot = typeMethods.computeIfAbsent(c.name, k -> new LinkedHashMap<>());
            for (FnDecl method : c.methods) {
                method.module = modName;
                stampFnFile(method, atFile);
                if (slot.containsKey(method.name)) {
                    loadFail(atFile, "duplicate method '" + method.name + "' on " + c.name, method.line, 1);
                }
                slot.put(method.name, method);
            }
            for (NestedImpl block : c.traitImpls) {
                List<TypeParam> params = new ArrayList<>(c.typeParams);
                params.addAll(block.typeParams);
                recordTraitImpl(Types.selfApplied(c.name, c.typeParams), block.traitName, params, c.line);
                for (FnDecl method : block.methods) {
                    method.module = modName;
                    stampFnFile(method, atFile);
                    if (slot.containsKey(method.name)) {
                        loadFail(atFile, "duplicate method '" + method.name + "' on " + c.name, method.line, 1);
                    }
                    slot.put(method.name, method);
                }
            }
            for (String t : c.implTraits) {
                recordTraitImpl(Types.selfApplied(c.name, c.typeParams), t, c.typeParams, c.line);
            }
            c.shape.signals.clear();
            c.shape.signals.addAll(c.signals);
            registerTypeSignals(c.name, c.signals, atFile);
            if (m == null || !fromMod || c.isPub) {
                if (m != null) {
                    m.exportStructs.put(c.name, c.shape);
                }
                if (structs.containsKey(c.name)) {
                    loadFail(atFile, "duplicate class '" + c.name + "'", c.line, 1);
                }
                structs.put(c.name, c.shape);
            }
        }
    }

    private void ingestImpls(List<ImplDecl> impls, String atFile) {
        for (ImplDecl im : impls) {
            String head = Types.typeHead(im.typeName);
            Map<String, FnDecl> slot = typeMethods.computeIfAbsent(head, k -> new LinkedHashMap<>());
            StructDecl st = allTypes.get(head);
            if (st == null) {
                st = structs.get(head);
            }
            if (st == null) {
                loadFail(atFile, "undefined struct '" + head + "'" + Types.stdlibImportHint(head, atFile),
                        im.line, 1);
                continue;
            }
            if (!im.traitName.isEmpty()) {
                if (!traits.containsKey(Types.typeHead(im.traitName))) {
                    loadFail(atFile, "undefined trait '" + Types.typeHead(im.traitName) + "'"
                            + Types.stdlibImportHint(Types.typeHead(im.traitName), atFile), im.line, 1);
                } else {
                    List<TypeParam> params = im.typeParams;
                    if (params.isEmpty()) {
                        params = st.typeParams;
                    }
                    recordTraitImpl(im.typeName, im.traitName, params, im.line);
                }
            }
            for (FnDecl method : im.methods) {
                stampFnFile(method, atFile);
                for (String fld : st.fields) {
                    if (fld.equals(method.name)) {
                        loadFail(atFile, "method '" + method.name + "' conflicts with field '" + method.name
                                + "' on " + head, method.line, 1);
                    }
                }
                if (slot.containsKey(method.name)) {
                    loadFail(atFile, "duplicate method '" + method.name + "' on " + head, method.line, 1);
                }
                if (method.vis == Vis.Protected && !classAbstract.containsKey(head)) {
                    loadFail(atFile, "protected cannot apply to " + (st.isData ? "data" : "struct") + " method",
                            method.line, 1);
                }
                slot.put(method.name, method);
            }
        }
    }

    private void ingestFns(LoadedMod m, List<FnDecl> fnsList, boolean fromMod, String modName, String atFile) {
        for (FnDecl fn : fnsList) {
            fn.module = modName;
            stampFnFile(fn, atFile);
            if (m.fns.containsKey(fn.name)) {
                FnDecl prev = m.fns.get(fn.name);
                if (fn.isUfcs && prev != null && prev.isUfcs) {
                    List<FnDecl> overloads = m.ufcsFns.computeIfAbsent(fn.name, k -> new ArrayList<>());
                    if (overloads.isEmpty()) {
                        overloads.add(prev);
                    }
                    overloads.add(fn);
                    continue;
                }
                loadFail(atFile, "duplicate export '" + fn.name + "' in module '" + modName + "'", fn.line, 1);
                continue;
            }
            m.fns.put(fn.name, fn);
            if (fn.isUfcs) {
                m.ufcsFns.computeIfAbsent(fn.name, k -> new ArrayList<>()).add(fn);
            }
            if (!fromMod || fn.isPub) {
                m.exports.put(fn.name, fn);
            }
        }
    }

    private void ingestStructs(LoadedMod m, List<StructDecl> items, boolean fromMod, String modName, String atFile) {
        for (StructDecl st : items) {
            if (m.structs.containsKey(st.name)) {
                loadFail(atFile, "duplicate export '" + st.name + "' in module '" + modName + "'", st.line, 1);
                continue;
            }
            m.structs.put(st.name, st);
            allTypes.put(st.name, st);
            recordFieldAccess(st.name, st.fields, st.fieldVis);
            for (FnDecl method : st.methods) {
                method.module = modName;
                stampFnFile(method, atFile);
                typeMethods.computeIfAbsent(st.name, k -> new LinkedHashMap<>()).put(method.name, method);
            }
            for (String t : st.implTraits) {
                recordTraitImpl(Types.selfApplied(st.name, st.typeParams), t, st.typeParams, st.line);
            }
            registerTypeSignals(st.name, st.signals, atFile);
            if (!fromMod || st.isPub) {
                m.exportStructs.put(st.name, st);
                if (structs.containsKey(st.name)) {
                    loadFail(atFile, "duplicate struct '" + st.name + "'", st.line, 1);
                }
                if (fns.containsKey(st.name)) {
                    loadFail(atFile, "struct '" + st.name + "' conflicts with a function", st.line, 1);
                }
                structs.put(st.name, st);
            }
        }
    }

    private void registerModShells(ModDecl block, String fullName) {
        loaded.computeIfAbsent(fullName, k -> new LoadedMod());
        for (ModDecl child : block.mods) {
            registerModShells(child, fullName + "." + child.name);
        }
    }

    private void loadImports(List<ImportDecl> imports, String fromFile, LoadedMod owner) {
        for (ImportDecl im : imports) {
            evalImport(im, fromFile, owner);
        }
    }

    private void ingestNestedMods(LoadedMod parent, List<ModDecl> mods, String parentName, String atFile) {
        for (ModDecl child : mods) {
            String full = parentName + "." + child.name;
            ingestLoadedMod(child, full, atFile);
            parent.modules.put(child.name, full);
            if (child.isPub) {
                parent.exportMods.put(child.name, full);
            }
        }
    }

    private void ingestLoadedMod(ModDecl block, String fullName, String atFile) {
        LoadedMod dest = loaded.containsKey(fullName) ? loaded.get(fullName) : new LoadedMod();
        ingestFns(dest, block.fns, true, fullName, atFile);
        ingestStructs(dest, block.structs, true, fullName, atFile);
        ingestClasses(dest, block.classes, true, fullName, atFile);
        ingestTraits(block.traits, atFile);
        ingestEnums(dest, block.enums, true, fullName, atFile);
        ingestImpls(block.impls, atFile);
        ingestNestedMods(dest, block.mods, fullName, atFile);
        loadImports(block.imports, atFile, dest);
        loaded.put(fullName, dest);
    }

    private ModDecl pickMod(Program p, String name) {
        ModDecl named = null;
        ModDecl only = null;
        int modCount = 0;
        boolean hasOther = !p.fns.isEmpty() || !p.structs.isEmpty() || !p.classes.isEmpty() || !p.traits.isEmpty()
                || !p.enums.isEmpty() || !p.impls.isEmpty() || !p.signals.isEmpty();
        for (ModDecl m : p.mods) {
            modCount++;
            only = m;
            if (m.name.equals(name)) {
                named = m;
            }
        }
        if (named != null) {
            return named;
        }
        if (modCount == 1 && !hasOther) {
            return only;
        }
        return null;
    }

    private void ingestModule(Program p, String modName, LoadedMod m, String atFile) {
        ingestModule(p, modName, m, atFile, true);
    }

    private void ingestModule(Program p, String modName, LoadedMod m, String atFile, boolean publishGlobal) {
        ModDecl block = pickMod(p, modName);
        boolean fromMod = block != null;
        if (block != null) {
            ingestFns(m, block.fns, fromMod, modName, atFile);
            ingestStructs(m, block.structs, fromMod, modName, atFile);
            ingestClasses(m, block.classes, fromMod, modName, atFile);
            ingestTraits(block.traits, atFile);
            ingestEnums(m, block.enums, fromMod, modName, atFile, publishGlobal);
            ingestImpls(block.impls, atFile);
            ingestNestedMods(m, block.mods, modName, atFile);
            loadImports(block.imports, atFile, m);
        } else {
            ingestFns(m, p.fns, false, modName, atFile);
            ingestStructs(m, p.structs, false, modName, atFile);
            ingestClasses(m, p.classes, false, modName, atFile);
            ingestTraits(p.traits, atFile);
            ingestEnums(m, p.enums, false, modName, atFile, publishGlobal);
            ingestImpls(p.impls, atFile);
            loadImports(p.imports, atFile, m);
        }
    }

    private Program keep(Program p) {
        extras.add(p);
        return p;
    }

    private String readFile(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private boolean fileHasMod(String source, String name) {
        try {
            List<Token> tokens = Lexer.tokenize(source, "", new ArrayList<>());
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (tokens.get(i).kind == Tok.Module && tokens.get(i + 1).kind == Tok.Identifier
                        && tokens.get(i + 1).text.equals(name)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private Path projectModule(String stem, String fromFile) {
        Project proj = projectOf(fromFile);
        if (proj == null) {
            return null;
        }
        return proj.modulePath(stem);
    }

    private Project projectOf(String fromFile) {
        if (projectLoaded) {
            return project;
        }
        projectLoaded = true;
        String start = !file.isEmpty() ? file : fromFile;
        if (start == null || start.isEmpty()) {
            return null;
        }
        try {
            project = Project.find(Path.of(start));
        } catch (LangException ex) {
            diagnostics.add(ex.diagnostic);
        } catch (IOException ignored) {
        }
        return project;
    }

    private List<String> resolveModule(String name, String fromFile) {
        String stem = name;
        if (stem.length() > 3 && stem.endsWith(".rg")) {
            stem = stem.substring(0, stem.length() - 3);
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.function.Consumer<Path> add = p -> {
            if (!Files.isRegularFile(p)) {
                return;
            }
            String id = Stdlib.generic(Stdlib.weaklyCanonical(p));
            if (!seen.add(id)) {
                return;
            }
            out.add(Stdlib.generic(p));
        };
        java.util.function.Consumer<Path> addDir = dir -> {
            for (Path path : Stdlib.listRgFiles(dir)) {
                add.accept(path);
            }
        };
        if (Types.isCrateStdlib(stem)) {
            Path root = Stdlib.findStdlibRoot(file);
            if (root == null) {
                root = Stdlib.findStdlibRoot(fromFile);
            }
            if (root != null) {
                Path dir = root;
                if (!stem.equals("std")) {
                    String child = stem;
                    if (child.length() > 4 && child.startsWith("std.")) {
                        child = child.substring(4);
                    }
                    dir = Stdlib.stdlibChildDir(root, child);
                }
                addDir.accept(dir);
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        Path mapped = projectModule(stem, fromFile);
        if (mapped != null) {
            if (Files.isDirectory(mapped)) {
                addDir.accept(mapped);
            } else {
                add.accept(mapped);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        String dotted = stem.replace('.', java.io.File.separatorChar);
        List<Path> bases = new ArrayList<>();
        java.util.function.Consumer<Path> pushBase = base -> {
            Path b = base == null ? Path.of(".") : base;
            String id = Stdlib.generic(Stdlib.weaklyCanonical(b));
            for (Path existing : bases) {
                if (Stdlib.generic(Stdlib.weaklyCanonical(existing)).equals(id)) {
                    return;
                }
            }
            bases.add(b);
        };
        if (fromFile != null && !fromFile.isEmpty()) {
            Path parent = Path.of(fromFile).getParent();
            pushBase.accept(parent == null ? Path.of(".") : parent);
        }
        Path entryParent = file.isEmpty() ? Path.of(".") : Path.of(file).getParent();
        pushBase.accept(entryParent == null ? Path.of(".") : entryParent);
        for (Path base : bases) {
            int before = out.size();
            addDir.accept(base.resolve(stem));
            if (!dotted.equals(stem)) {
                addDir.accept(base.resolve(dotted));
            }
            if (Files.isDirectory(base)) {
                for (Path path : Stdlib.listRgFiles(base)) {
                    try {
                        if (fileHasMod(readFile(Stdlib.generic(path)), stem)) {
                            add.accept(path);
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            if (out.size() > before) {
                return out;
            }
        }
        return out;
    }

    private void ingestCrateEntry(String crate) {
        loading.add(crate);
        LoadedMod m = new LoadedMod();
        try {
            for (String path : resolveModule(crate, file)) {
                if (Stdlib.sameRgFile(path, file)) {
                    continue;
                }
                List<Diagnostic> parseErrs = new ArrayList<>();
                Program kept = keep(Parser.parseSource(readFile(path), path, parseErrs));
                for (Diagnostic d : parseErrs) {
                    recordDiag(d.kind, d.file, d.line, d.col, d.message);
                }
                ingestModule(kept, crate, m, path);
            }
            ingestModule(program, crate, m, file);
        } catch (IOException ex) {
            loading.removeLast();
            loadFail(file, ex.getMessage() == null ? "cannot read module" : ex.getMessage(), 1, 1);
            return;
        }
        loading.removeLast();
        loaded.put(crate, m);
        attachCrateChildren(crate);
        int dot = crate.lastIndexOf('.');
        String bind = dot < 0 ? crate : crate.substring(dot + 1);
        moduleBinds.put(bind, crate);
    }

    private void attachCrateChildren(String parent) {
        attachCrateChildren(parent, true);
    }

    private void attachCrateChildren(String parent, boolean publishGlobal) {
        LoadedMod it = loaded.get(parent);
        if (it == null) {
            return;
        }
        Path dir = Stdlib.crateDir(parent, file);
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        for (String kid : Stdlib.listChildDirs(dir)) {
            String full = parent + "." + kid;
            if (resolveModule(full, file).isEmpty()) {
                continue;
            }
            loadModule(full, file, 1, 1, publishGlobal);
            it = loaded.get(parent);
            if (it == null) {
                return;
            }
            if (!loaded.containsKey(full)) {
                continue;
            }
            it.modules.put(kid, full);
            it.exportMods.put(kid, full);
        }
    }

    private void loadModule(String raw, String fromFile, int line, int col) {
        loadModule(raw, fromFile, line, col, true);
    }

    private void loadModule(String raw, String fromFile, int line, int col, boolean publishGlobal) {
        String name = Types.canonicalStdlibName(raw);
        if (loaded.containsKey(name) || Types.isHostModule(name)) {
            return;
        }
        for (String cur : loading) {
            if (cur.equals(name)) {
                loadFail(fromFile, "cyclic import of module '" + name + "'", line, col);
                return;
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String parent = name.substring(0, dot);
            if (!loaded.containsKey(parent) && !Types.isHostModule(parent)
                    && !resolveModule(parent, fromFile).isEmpty()) {
                loadModule(parent, fromFile, line, col, publishGlobal);
            }
            if (loaded.containsKey(name)) {
                return;
            }
        }
        List<String> files = resolveModule(name, fromFile);
        if (files.isEmpty()) {
            loadFail(fromFile, "module '" + name + "' not found (tried " + name + "/ or mod " + name + ")", line, col);
            return;
        }
        loading.add(name);
        LoadedMod m = new LoadedMod();
        try {
            for (String path : files) {
                List<Diagnostic> parseErrs = new ArrayList<>();
                Program kept = keep(Parser.parseSource(readFile(path), path, parseErrs));
                for (Diagnostic d : parseErrs) {
                    recordDiag(d.kind, d.file, d.line, d.col, d.message);
                }
                ingestModule(kept, name, m, path, publishGlobal);
            }
        } catch (IOException ex) {
            loading.removeLast();
            loadFail(fromFile, ex.getMessage() == null ? "cannot read module" : ex.getMessage(), line, col);
            return;
        }
        loading.removeLast();
        loaded.put(name, m);
        attachCrateChildren(name, publishGlobal);
    }

    private void bindFromImport(ImportDecl im, LoadedMod mod, LoadedMod owner, String fromFile) {
        if (im.path.size() != 2) {
            loadFail(fromFile, "nested from-imports longer than 2 segments are not supported", im.line, im.col);
            return;
        }
        String item = im.path.get(1);
        String alias = im.alias.isEmpty() ? item : im.alias;
        FnDecl fn = mod.exports.get(item);
        if (fn != null) {
            if (owner != null) {
                if (owner.fromFns.containsKey(alias) || owner.fns.containsKey(alias)) {
                    loadFail(fromFile, "duplicate import '" + alias + "'", im.line, im.col);
                    return;
                }
                owner.fromFns.put(alias, fn);
            } else {
                if (fns.containsKey(alias)) {
                    loadFail(fromFile, "duplicate import '" + alias + "'", im.line, im.col);
                    return;
                }
                fns.put(alias, fn);
            }
            return;
        }
        StructDecl st = mod.exportStructs.get(item);
        if (st != null) {
            if (owner != null) {
                owner.structs.put(alias, st);
                owner.exportStructs.put(alias, st);
            } else {
                if (structs.containsKey(alias) && structs.get(alias) != st) {
                    loadFail(fromFile, "duplicate struct '" + alias + "'", im.line, im.col);
                    return;
                }
                structs.put(alias, st);
            }
            return;
        }
        EnumDecl en = mod.exportEnums.get(item);
        if (en != null) {
            if (owner != null) {
                owner.enums.put(alias, en);
                owner.exportEnums.put(alias, en);
            } else {
                if (enums.containsKey(alias) && enums.get(alias) != en) {
                    loadFail(fromFile, "duplicate enum '" + alias + "'", im.line, im.col);
                    return;
                }
                enums.put(alias, en);
            }
            return;
        }
        if (mod.exportMods.containsKey(item)) {
            String full = mod.exportMods.get(item);
            if (owner != null) {
                owner.modules.put(alias, full);
            } else {
                moduleBinds.put(alias, full);
            }
            return;
        }
        loadFail(fromFile, "module '" + im.path.getFirst() + "' has no export '" + item + "'", im.line, im.col);
    }

    private void checkDottedExport(ImportDecl im, String fromFile) {
        if (im.path.size() < 2) {
            return;
        }
        String acc = im.path.getFirst();
        for (int i = 1; i < im.path.size(); i++) {
            LoadedMod pit = loaded.get(acc);
            if (pit == null) {
                return;
            }
            String seg = im.path.get(i);
            if (pit.modules.containsKey(seg)) {
                if (!pit.exportMods.containsKey(seg)) {
                    loadFail(fromFile, "module '" + acc + "' has no export '" + seg + "'", im.line, im.col);
                    return;
                }
            } else {
                return;
            }
            acc = acc + "." + seg;
        }
    }

    private void evalImport(ImportDecl im, String fromFile, LoadedMod owner) {
        if (im.path.isEmpty()) {
            loadFail(fromFile, "empty import", im.line, im.col);
            return;
        }
        if (Types.isHostModule(im.path.getFirst())) {
            return;
        }
        if (im.isFrom) {
            String key = Types.canonicalStdlibName(im.path.getFirst());
            loadModule(key, fromFile, im.line, im.col, owner == null);
            LoadedMod it = loaded.get(key);
            if (it == null) {
                return;
            }
            bindFromImport(im, it, owner, fromFile);
            return;
        }
        StringBuilder full = new StringBuilder(im.path.getFirst());
        for (int i = 1; i < im.path.size(); i++) {
            full.append('.').append(im.path.get(i));
        }
        String key = Types.canonicalStdlibName(full.toString());
        loadModule(key, fromFile, im.line, im.col, owner == null);
        checkDottedExport(im, fromFile);
        String bind = im.alias.isEmpty() ? im.path.getLast() : im.alias;
        java.util.function.BiConsumer<String, String> setBind = (name, target) -> {
            if (owner != null) {
                owner.modules.put(name, target);
            } else {
                moduleBinds.put(name, target);
            }
        };
        setBind.accept(bind, key);
        if (im.path.getFirst().equals("std") || key.equals("std")
                || (key.length() > 4 && key.startsWith("std."))) {
            setBind.accept("std", "std");
        }
    }

    private void recordFieldAccess(String typeName, List<String> fields, List<Vis> vis) {
        Map<String, Vis> slot = fieldAccess.computeIfAbsent(typeName, k -> new LinkedHashMap<>());
        for (int i = 0; i < fields.size(); i++) {
            Vis v = i < vis.size() ? vis.get(i) : Vis.Pub;
            slot.put(fields.get(i), v);
        }
    }

    private void flattenType(String name, List<String> stack, Set<String> done) {
        if (done.contains(name)) {
            return;
        }
        int typeLine = 1;
        StructDecl typeDecl = allTypes.get(name);
        if (typeDecl != null) {
            typeLine = typeDecl.line;
        }
        if (stack.contains(name)) {
            loadFail(file, "cycle in class inheritance at '" + name + "'", typeLine, 1);
            return;
        }
        String parentApplied = classParents.get(name);
        if (parentApplied == null) {
            done.add(name);
            return;
        }
        String parent = Types.typeHead(parentApplied);
        if (!allTypes.containsKey(parent)) {
            if (typeMethods.containsKey(parent)) {
                done.add(name);
                return;
            }
            loadFail(file, "class '" + name + "' extends unknown type '" + parentApplied + "'"
                    + Types.stdlibImportHint(parent, file), typeLine, 1);
            return;
        }
        if (allTypes.get(parent).isData) {
            loadFail(file, "class '" + name + "' cannot extend data type '" + parent + "'", typeLine, 1);
            return;
        }
        stack.add(name);
        flattenType(parent, stack, done);
        stack.removeLast();
        StructDecl child = allTypes.get(name);
        StructDecl parentDef = allTypes.get(parent);
        Map<String, String> env = Types.typeEnvFrom(parentDef.typeParams, Types.typeArgList(parentApplied));
        List<String> fields = new ArrayList<>(parentDef.fields);
        List<String> types = new ArrayList<>(parentDef.fieldTypes);
        List<Boolean> opts = new ArrayList<>(parentDef.fieldOptional);
        while (types.size() < fields.size()) {
            types.add("");
        }
        while (opts.size() < fields.size()) {
            opts.add(false);
        }
        if (types.size() > fields.size()) {
            types = new ArrayList<>(types.subList(0, fields.size()));
        }
        if (opts.size() > fields.size()) {
            opts = new ArrayList<>(opts.subList(0, fields.size()));
        }
        for (int i = 0; i < types.size(); i++) {
            types.set(i, Types.substType(types.get(i), env));
        }
        for (int i = 0; i < child.fields.size(); i++) {
            String f = child.fields.get(i);
            if (fields.contains(f)) {
                continue;
            }
            fields.add(f);
            types.add(i < child.fieldTypes.size() ? child.fieldTypes.get(i) : "");
            opts.add(i < child.fieldOptional.size() ? child.fieldOptional.get(i) : false);
        }
        child.fields.clear();
        child.fields.addAll(fields);
        child.fieldTypes.clear();
        child.fieldTypes.addAll(types);
        child.fieldOptional.clear();
        child.fieldOptional.addAll(opts);
        Map<String, Expr> merged = new LinkedHashMap<>();
        Map<String, Expr> parentDefs = fieldDefaults.get(parent);
        if (parentDefs != null) {
            merged.putAll(parentDefs);
        }
        Map<String, Expr> childDefs = fieldDefaults.get(name);
        if (childDefs != null) {
            merged.putAll(childDefs);
        }
        fieldDefaults.put(name, merged);
        done.add(name);
    }

    private void applyInheritance() {
        Set<String> done = new HashSet<>();
        List<String> stack = new ArrayList<>();
        List<String> names = new ArrayList<>(classParents.keySet());
        for (String name : names) {
            flattenType(name, stack, done);
        }
    }

    private void checkTraitImpls() {
        for (Map.Entry<String, List<String>> kv : typeTraits.entrySet()) {
            int typeLine = 1;
            StructDecl titType = allTypes.get(kv.getKey());
            if (titType != null) {
                typeLine = titType.line;
            }
            for (String traitName : kv.getValue()) {
                TraitDecl tit = traits.get(Types.typeHead(traitName));
                if (tit == null) {
                    loadFail(file, "undefined trait '" + Types.typeHead(traitName) + "'"
                            + Types.stdlibImportHint(Types.typeHead(traitName), file), typeLine, 1);
                    continue;
                }
                List<String> targs = Types.typeArgList(traitName);
                if (!targs.isEmpty()) {
                    if (tit.typeParams.isEmpty()) {
                        loadFail(file, "'" + Types.typeHead(traitName) + "' does not take type arguments",
                                typeLine, 1);
                    } else if (targs.size() != tit.typeParams.size()) {
                        loadFail(file, "'" + Types.typeHead(traitName) + "' expected " + tit.typeParams.size()
                                + " type argument(s), got " + targs.size(), typeLine, 1);
                    }
                }
                for (TraitMethod m : tit.methods) {
                    if (lookupMethod(kv.getKey(), m.name).fn == null) {
                        loadFail(file, "type '" + kv.getKey() + "' is missing '" + m.name + "' for trait '"
                                + traitName + "'", m.line > 0 ? m.line : typeLine, 1);
                    }
                }
            }
        }
    }

    private void checkAbstractFinal() {
        for (Map.Entry<String, String> kv : classParents.entrySet()) {
            Boolean fin = classFinal.get(Types.typeHead(kv.getValue()));
            if (Boolean.TRUE.equals(fin)) {
                loadFail(file, "class '" + kv.getKey() + "' extends final class '"
                        + Types.typeHead(kv.getValue()) + "'", typeLine(kv.getKey()), 1);
            }
        }
        for (Map.Entry<String, Boolean> kv : classAbstract.entrySet()) {
            String name = kv.getKey();
            boolean absClass = Boolean.TRUE.equals(kv.getValue());
            boolean finClass = Boolean.TRUE.equals(classFinal.get(name));
            int line = typeLine(name);
            if (absClass && finClass) {
                loadFail(file, "class '" + name + "' cannot be both abstract and final", line, 1);
            }
            Map<String, FnDecl> mit = typeMethods.get(name);
            if (mit != null) {
                for (Map.Entry<String, FnDecl> m : mit.entrySet()) {
                    if (m.getValue().isAbstract && m.getValue().isFinal) {
                        loadFail(file, "method '" + m.getKey() + "' cannot be both abstract and final",
                                m.getValue().line, 1);
                    }
                    if (m.getValue().isAbstract && !absClass) {
                        loadFail(file, "class '" + name + "' has abstract method '" + m.getKey()
                                + "' but is not abstract", m.getValue().line, 1);
                    }
                    String current = classParents.containsKey(name) ? Types.typeHead(classParents.get(name)) : "";
                    Set<String> walked = new HashSet<>();
                    while (!current.isEmpty() && walked.add(current)) {
                        Map<String, FnDecl> tmit = typeMethods.get(current);
                        if (tmit != null && tmit.containsKey(m.getKey())) {
                            FnDecl parentM = tmit.get(m.getKey());
                            if (parentM.isFinal) {
                                loadFail(file, "cannot override final method '" + m.getKey() + "'",
                                        m.getValue().line, 1);
                            }
                            break;
                        }
                        String pit = classParents.get(current);
                        if (pit == null) {
                            break;
                        }
                        current = Types.typeHead(pit);
                    }
                }
            }
            if (absClass) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            String current = name;
            Set<String> walked = new HashSet<>();
            while (!current.isEmpty() && walked.add(current)) {
                Map<String, FnDecl> tmit = typeMethods.get(current);
                if (tmit != null) {
                    for (Map.Entry<String, FnDecl> m : tmit.entrySet()) {
                        if (!seen.add(m.getKey())) {
                            continue;
                        }
                        if (m.getValue().isAbstract) {
                            loadFail(file, "type '" + name + "' is missing abstract method '" + m.getKey() + "'",
                                    line, 1);
                        }
                    }
                }
                String pit = classParents.get(current);
                if (pit == null) {
                    break;
                }
                current = Types.typeHead(pit);
            }
        }
    }

    private int typeLine(String name) {
        StructDecl it = allTypes.get(name);
        return it != null ? it.line : 1;
    }

    private void bindTraitSignals() {
        for (Map.Entry<String, List<String>> kv : typeTraits.entrySet()) {
            int typeLine = 1;
            StructDecl titType = allTypes.get(kv.getKey());
            if (titType != null) {
                typeLine = titType.line;
            }
            for (String traitName : kv.getValue()) {
                TraitDecl tit = traits.get(Types.typeHead(traitName));
                if (tit == null) {
                    continue;
                }
                for (SignalDecl sig : tit.signals) {
                    Map<String, Integer> slot = typeSignals.computeIfAbsent(kv.getKey(), k -> new LinkedHashMap<>());
                    if (slot.containsKey(sig.name)) {
                        if (slot.get(sig.name) != sig.params.size()) {
                            loadFail(file, "signal '" + sig.name + "' on " + kv.getKey()
                                    + " conflicts with trait '" + traitName + "'", sig.line, 1);
                        }
                        continue;
                    }
                    Map<String, FnDecl> mit = typeMethods.get(kv.getKey());
                    if (mit != null && mit.containsKey(sig.name)) {
                        loadFail(file, "signal '" + sig.name + "' conflicts with method '" + sig.name
                                + "' on " + kv.getKey(), typeLine, 1);
                    }
                    StructDecl st = titType;
                    if (st != null) {
                        for (String f : st.fields) {
                            if (f.equals(sig.name)) {
                                loadFail(file, "signal '" + sig.name + "' conflicts with field '" + sig.name
                                        + "' on " + kv.getKey(), typeLine, 1);
                            }
                        }
                    }
                    slot.put(sig.name, sig.params.size());
                }
            }
        }
    }

    private void constexprFail(int line, int col, String msg) {
        recordDiag("constexpr error", file, line, col, msg);
    }

    private void checkConstexprCall(String name, int line, int col) {
        if (name.equals("len")) {
            return;
        }
        if (name.equals("print") || name.equals("assert") || name.equals("argv") || name.equals("argv_len")) {
            constexprFail(line, col, "constexpr function cannot call '" + name + "'");
            return;
        }
        FnDecl fn = findLocalFn(name);
        if (fn == null) {
            constexprFail(line, col,
                    "constexpr function can only call constexpr functions ('" + name + "' is unknown)");
            return;
        }
        if (!fn.isConstexpr) {
            constexprFail(line, col,
                    "constexpr function can only call constexpr functions ('" + name + "' is not constexpr)");
        }
    }

    private void checkConstexprExpr(Expr e) {
        if (e == null || e.kind == null) {
            return;
        }
        if (e.kind == Expr.Kind.Try) {
            constexprFail(e.line, e.col, "constexpr function cannot use 'try'");
        }
        if (e.kind == Expr.Kind.Lambda) {
            constexprFail(e.line, e.col, "constexpr function cannot use closures");
        }
        if (e.kind == Expr.Kind.Var && e.text.equals("super")) {
            constexprFail(e.line, e.col, "constexpr function cannot use super");
        }
        if (e.kind == Expr.Kind.Call) {
            if (e.text.isEmpty()) {
                constexprFail(e.line, e.col, "constexpr function cannot call a closure");
            } else {
                checkConstexprCall(e.text, e.line, e.col);
            }
        }
        if (e.kind == Expr.Kind.MethodCall && !e.kids.isEmpty()) {
            Expr recv = e.kids.getFirst();
            boolean allowed = false;
            if (recv.kind == Expr.Kind.Var) {
                if (signalArity.containsKey(recv.text)) {
                    constexprFail(e.line, e.col, "constexpr function cannot use signals");
                }
                String modName = findModuleBind(recv.text);
                if (modName != null) {
                    LoadedMod lit = loaded.get(modName);
                    if (lit == null) {
                        constexprFail(e.line, e.col, "unknown module '" + modName + "'");
                    } else {
                        FnDecl eit = lit.exports.get(e.text);
                        if (eit == null || !eit.isConstexpr) {
                            constexprFail(e.line, e.col, "constexpr function can only call constexpr functions ('"
                                    + recv.text + "." + e.text + "' is not constexpr)");
                        }
                        allowed = true;
                    }
                } else if (findEnum(recv.text) != null) {
                    allowed = true;
                }
            }
            if (!allowed) {
                FnDecl fn = findUfcs(e.text, "");
                if (fn != null && fn.isConstexpr) {
                    allowed = true;
                }
            }
            if (!allowed && !e.text.equals("len") && !e.text.equals("has") && !e.text.equals("keys")) {
                constexprFail(e.line, e.col, "constexpr function cannot call method '" + e.text + "'");
            }
        }
        for (Expr kid : e.kids) {
            checkConstexprExpr(kid);
        }
    }

    private void checkConstexprStmt(Stmt stmt) {
        switch (stmt.kind) {
            case Var:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'var'");
                return;
            case Assign, FieldAssign, IndexAssign:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot assign");
                return;
            case While:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'while'");
                return;
            case For:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'for'");
                return;
            case Break:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'break'");
                return;
            case Continue:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'continue'");
                return;
            case Throw:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot throw");
                return;
            case Do:
                constexprFail(stmt.line, stmt.col, "constexpr function cannot use 'do'");
                return;
            case Pass, Comment:
                return;
            case Expr, Const, Return:
                checkConstexprExpr(stmt.expr);
                return;
            case If:
                checkConstexprExpr(stmt.expr);
                for (Stmt s : stmt.body) {
                    checkConstexprStmt(s);
                }
                for (Stmt s : stmt.elseBody) {
                    checkConstexprStmt(s);
                }
                return;
            case Match:
                checkConstexprExpr(stmt.expr);
                for (MatchArm arm : stmt.arms) {
                    for (Stmt s : arm.body) {
                        checkConstexprStmt(s);
                    }
                }
        }
    }

    private void checkConstexprFn(FnDecl fn) {
        for (Stmt stmt : fn.body) {
            checkConstexprStmt(stmt);
        }
    }

    private void checkConstexprFns() {
        for (FnDecl fn : program.fns) {
            if (fn.isConstexpr) {
                checkConstexprFn(fn);
            }
        }
        for (Map<String, FnDecl> methods : typeMethods.values()) {
            for (FnDecl m : methods.values()) {
                if (m.isConstexpr) {
                    checkConstexprFn(m);
                }
            }
        }
        String prev = currentModule;
        for (Map.Entry<String, LoadedMod> mod : loaded.entrySet()) {
            currentModule = mod.getKey();
            for (FnDecl fn : mod.getValue().fns.values()) {
                if (fn.isConstexpr) {
                    checkConstexprFn(fn);
                }
            }
        }
        currentModule = prev;
    }
}

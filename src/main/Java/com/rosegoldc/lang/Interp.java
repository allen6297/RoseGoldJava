package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class Interp {

    private static final long MAX_RANGE = 1_000_000L;
    private static final int MAX_CALL_DEPTH = 256;
    private static final int MAX_SYNC_EMIT_DEPTH = 64;
    private static final long MAX_REPEAT_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_SLEEP_MS = 60_000L;
    private static final int MAX_EVENT_LOOP = 100_000;
    private static final String UUID_DIGITS = "0123456789abcdef";

    private final String file;
    private final Checker checker;
    private final List<String> argv;
    private final Map<String, FnDecl> fns = new LinkedHashMap<>();
    private final Map<String, List<Value>> listeners = new LinkedHashMap<>();
    private final List<DeferredEmit> deferred = new ArrayList<>();
    private final List<Map<String, Value.Binding>> env = new ArrayList<>();
    private int loopDepth;
    private int callDepth;
    private int syncEmitDepth;
    private String superType = "";
    private final Random rng = new Random();
    private final HostUi ui = new HostUi();
    private final ArrayDeque<Runnable> microtasks = new ArrayDeque<>();
    private final List<TimerJob> timers = new ArrayList<>();
    final List<FrameJob> frameJobs = new ArrayList<>();
    String out = "";
    Path sandboxRoot = Path.of("").toAbsolutePath().normalize();
    Consumer<String> output;
    final DebugState debug = new DebugState();
    private final Object pauseLock = new Object();
    private boolean debugWaiting;

    Interp(Checker checker, List<String> argv) {
        this.checker = checker;
        this.file = checker.file == null ? "" : checker.file;
        this.argv = new ArrayList<>(argv);
        if (this.argv.isEmpty() && !this.file.isEmpty()) {
            this.argv.add(this.file);
        }
        this.fns.putAll(checker.fns);
        for (String signal : checker.signalArity.keySet()) {
            listeners.put(signal, new ArrayList<>());
        }
        env.add(new LinkedHashMap<>());
    }

    void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        out += text;
        if (output != null) {
            output.accept(text);
        }
    }

    Value callNamed(String name) {
        FnDecl fn = fns.get(name);
        if (fn == null) {
            throw runtime("unknown function '" + name + "'", 0, 0);
        }
        try {
            Value ret = callUser(fn, List.of(), fn.line, 1);
            if (ret.kind == Value.Kind.Future && ret.fut != null) {
                ret = awaitFuture(ret.fut, fn.line, 1);
            }
            flushDeferred();
            drainEventLoop();
            return ret;
        } catch (ThrowEscape ex) {
            throw runtime("uncaught throw: " + ex.value.toPrintString(), ex.line, ex.col);
        }
    }

    private Value callUser(FnDecl fn, List<Value> args, int line, int col) {
        return callUser(fn, args, line, col, null);
    }

    private Value callUser(FnDecl fn, List<Value> args, int line, int col, Map<String, Value.Binding> caps) {
        if (fn.isAsync) {
            Value.FutureData fut = new Value.FutureData();
            List<Value> capturedArgs = new ArrayList<>(args);
            Map<String, Value.Binding> capturedCaps = null;
            if (caps != null) {
                capturedCaps = new LinkedHashMap<>();
                for (Map.Entry<String, Value.Binding> cap : caps.entrySet()) {
                    capturedCaps.put(cap.getKey(), new Value.Binding(cap.getValue().value, cap.getValue().isConst));
                }
            }
            Map<String, Value.Binding> capsForTask = capturedCaps;
            enqueueMicrotask(() -> {
                try {
                    settleFuture(fut, runUserBody(fn, capturedArgs, line, col, capsForTask));
                } catch (ThrowEscape ex) {
                    failFuture(fut, ex.value, ex.line, ex.col);
                }
            });
            return Value.makeFuture(fut);
        }
        return runUserBody(fn, args, line, col, caps);
    }

    private Value runUserBody(FnDecl fn, List<Value> args, int line, int col, Map<String, Value.Binding> caps) {
        if (callDepth >= MAX_CALL_DEPTH) {
            throw runtime("call stack overflow", line, col);
        }
        if (args.size() != fn.params.size()) {
            throw runtime(fn.name + " takes " + fn.params.size() + " argument(s)", line, col);
        }
        if (fn.isDeprecated) {
            emit("warning: '" + fn.name + "' is deprecated\n");
        }
        callDepth++;
        String prevModule = checker.currentModule;
        checker.currentModule = fn.module == null ? "" : fn.module;
        env.add(new LinkedHashMap<>());
        DebugFrame frame = new DebugFrame();
        frame.name = fn.name;
        frame.path = fn.file != null && !fn.file.isEmpty() ? fn.file : file;
        frame.line = fn.line > 0 ? fn.line : 1;
        frame.col = 1;
        frame.envIndex = env.size() - 1;
        debug.stack.add(frame);
        try {
            if (caps != null) {
                for (Map.Entry<String, Value.Binding> cap : caps.entrySet()) {
                    env.getLast().put(cap.getKey(), new Value.Binding(cap.getValue().value, cap.getValue().isConst));
                }
            }
            for (int i = 0; i < fn.params.size(); i++) {
                env.getLast().put(fn.params.get(i), new Value.Binding(args.get(i), false));
            }
            if (fn.code != null) {
                try {
                    return Vm.exec(this, fn.code, args, caps);
                } catch (ThrowEscape ex) {
                    throw new ThrowEscape(ex.value, line, col);
                }
            }
            Flow f = execBlock(fn.body);
            if (f.kind == Flow.Kind.Throw) {
                throw new ThrowEscape(f.value, line, col);
            }
            if (f.kind == Flow.Kind.Return) {
                return f.value;
            }
            if (f.kind == Flow.Kind.Break) {
                throw runtime("break outside loop", fn.line, 1);
            }
            if (f.kind == Flow.Kind.Continue) {
                throw runtime("continue outside loop", fn.line, 1);
            }
            return Value.makeVoid();
        } finally {
            if (!debug.stack.isEmpty()) {
                debug.stack.removeLast();
            }
            env.removeLast();
            checker.currentModule = prevModule;
            callDepth--;
        }
    }

    private Flow execBlock(List<Stmt> stmts) {
        for (Stmt stmt : stmts) {
            Flow f = execStmt(stmt);
            if (f.kind != Flow.Kind.Next) {
                return f;
            }
        }
        return Flow.next();
    }

    private Flow execStmt(Stmt stmt) {
        debugCheck(stmt);
        return switch (stmt.kind) {
            case Expr -> {
                eval(stmt.expr);
                yield Flow.next();
            }
            case Var -> {
                env.getLast().put(stmt.name, new Value.Binding(eval(stmt.expr), false));
                yield Flow.next();
            }
            case Const -> {
                env.getLast().put(stmt.name, new Value.Binding(eval(stmt.expr), true));
                yield Flow.next();
            }
            case Assign -> {
                Value rhs = eval(stmt.expr);
                Value.Binding local = findLocalBinding(stmt.name);
                if (local != null) {
                    if (local.isConst) {
                        throw runtime("cannot assign to const '" + stmt.name + "'", stmt.line, stmt.col);
                    }
                    local.value = applyAssignOp(stmt.op, local.value, rhs, stmt.line, stmt.col);
                    yield Flow.next();
                }
                Value.StructData rec = selfRec();
                if (rec != null && rec.fields.containsKey(stmt.name)) {
                    if (checker.isDataType(rec.name)) {
                        throw runtime("cannot assign to data field '" + stmt.name + "'", stmt.line, stmt.col);
                    }
                    rec.fields.put(stmt.name, applyAssignOp(stmt.op, rec.fields.get(stmt.name), rhs, stmt.line, stmt.col));
                    yield Flow.next();
                }
                Value.Binding slot = findGlobalBinding(stmt.name);
                if (slot == null) {
                    throw runtime("undefined variable '" + stmt.name + "'", stmt.line, stmt.col);
                }
                if (slot.isConst) {
                    throw runtime("cannot assign to const '" + stmt.name + "'", stmt.line, stmt.col);
                }
                slot.value = applyAssignOp(stmt.op, slot.value, rhs, stmt.line, stmt.col);
                yield Flow.next();
            }
            case IndexAssign -> {
                if (stmt.target.kids.size() < 2) {
                    throw runtime("invalid index assignment", stmt.line, stmt.col);
                }
                Value obj = eval(stmt.target.kids.get(0));
                Value idx = eval(stmt.target.kids.get(1));
                Value rhs = eval(stmt.expr);
                if (!stmt.op.isEmpty() && !stmt.op.equals("=")) {
                    rhs = applyBinop(stmt.op.substring(0, stmt.op.length() - 1), indexGet(obj, idx, stmt.line, stmt.col), rhs, stmt.line, stmt.col);
                }
                indexSet(obj, idx, rhs, stmt.line, stmt.col);
                yield Flow.next();
            }
            case Return -> Flow.ret(stmt.hasExpr && stmt.expr != null ? eval(stmt.expr) : Value.makeVoid());
            case If -> eval(stmt.expr).truthy() ? execBlock(stmt.body) : execBlock(stmt.elseBody);
            case While -> {
                loopDepth++;
                try {
                    while (eval(stmt.expr).truthy()) {
                        Flow f = execBlock(stmt.body);
                        if (f.kind == Flow.Kind.Break) {
                            break;
                        }
                        if (f.kind == Flow.Kind.Continue) {
                            continue;
                        }
                        if (f.kind == Flow.Kind.Return || f.kind == Flow.Kind.Throw) {
                            yield f;
                        }
                    }
                } finally {
                    loopDepth--;
                }
                yield Flow.next();
            }
            case For -> {
                List<Value> items = iterItems(eval(stmt.expr), stmt.line, stmt.col);
                boolean existed = env.getLast().containsKey(stmt.name);
                Value.Binding saved = existed ? env.getLast().get(stmt.name) : null;
                loopDepth++;
                Flow result = Flow.next();
                try {
                    for (Value item : items) {
                        env.getLast().put(stmt.name, new Value.Binding(item, false));
                        Flow f = execBlock(stmt.body);
                        if (f.kind == Flow.Kind.Break) {
                            break;
                        }
                        if (f.kind == Flow.Kind.Continue) {
                            continue;
                        }
                        if (f.kind == Flow.Kind.Return || f.kind == Flow.Kind.Throw) {
                            result = f;
                            break;
                        }
                    }
                } finally {
                    loopDepth--;
                    if (existed) {
                        env.getLast().put(stmt.name, saved);
                    } else {
                        env.getLast().remove(stmt.name);
                    }
                }
                yield result;
            }
            case Match -> execMatch(stmt);
            case Pass, Comment -> Flow.next();
            case Break -> {
                if (loopDepth == 0) {
                    throw runtime("break outside loop", stmt.line, stmt.col);
                }
                yield Flow.brk();
            }
            case Continue -> {
                if (loopDepth == 0) {
                    throw runtime("continue outside loop", stmt.line, stmt.col);
                }
                yield Flow.cont();
            }
            case Throw -> Flow.thr(eval(stmt.expr));
            case Do -> {
                try {
                    Flow f = execBlock(stmt.body);
                    if (f.kind == Flow.Kind.Throw) {
                        if (stmt.elseBody.isEmpty()) {
                            yield f;
                        }
                        env.getLast().put(stmt.name, new Value.Binding(f.value, false));
                        yield execBlock(stmt.elseBody);
                    }
                    yield f;
                } catch (ThrowEscape ex) {
                    if (stmt.elseBody.isEmpty()) {
                        throw ex;
                    }
                    env.getLast().put(stmt.name, new Value.Binding(ex.value, false));
                    yield execBlock(stmt.elseBody);
                } catch (RuntimeException ex) {
                    if (stmt.elseBody.isEmpty()) {
                        throw ex;
                    }
                    env.getLast().put(stmt.name, new Value.Binding(Value.makeString(ex.getMessage()), false));
                    yield execBlock(stmt.elseBody);
                }
            }
            case FieldAssign -> {
                Value obj;
                if (stmt.target.kind == Expr.Kind.Var && "super".equals(stmt.target.text)) {
                    obj = selfObject(stmt.line, stmt.col);
                } else {
                    obj = eval(stmt.target);
                }
                if (obj.kind != Value.Kind.Struct || obj.rec == null) {
                    throw runtime("cannot assign field on " + obj.toPrintString(), stmt.line, stmt.col);
                }
                if (!obj.rec.fields.containsKey(stmt.name)) {
                    throw runtime("struct " + obj.rec.name + " has no field '" + stmt.name + "'", stmt.line, stmt.col);
                }
                if (checker.isDataType(obj.rec.name)) {
                    throw runtime("cannot assign to data field '" + stmt.name + "'", stmt.line, stmt.col);
                }
                Value rhs = eval(stmt.expr);
                obj.rec.fields.put(stmt.name, applyAssignOp(stmt.op, obj.rec.fields.get(stmt.name), rhs, stmt.line, stmt.col));
                yield Flow.next();
            }
        };
    }

    private Flow execMatch(Stmt stmt) {
        Value value = eval(stmt.expr);
        for (MatchArm arm : stmt.arms) {
            boolean hit = switch (arm.pat) {
                case Wildcard -> true;
                case Int -> matchLit(value, Value.makeInt(arm.number));
                case Float -> matchLit(value, Value.makeFloat(arm.real));
                case String -> matchLit(value, Value.makeString(arm.text));
                case Bool -> matchLit(value, Value.makeBool(arm.booleanValue));
                case Variant -> matchVariant(value, arm.name, stmt.line, stmt.col);
            };
            if (!hit) {
                continue;
            }
            Map<String, Value.Binding> saved = new LinkedHashMap<>();
            List<String> added = new ArrayList<>();
            try {
                if (arm.pat == MatchArm.Pat.Variant) {
                    bindVariant(value, arm, saved, added, stmt.line, stmt.col);
                }
                return execBlock(arm.body);
            } finally {
                for (Map.Entry<String, Value.Binding> kv : saved.entrySet()) {
                    env.getLast().put(kv.getKey(), kv.getValue());
                }
                for (String n : added) {
                    env.getLast().remove(n);
                }
            }
        }
        return Flow.next();
    }

    boolean matchLit(Value value, Value pat) {
        return switch (pat.kind) {
            case Int -> value.kind == Value.Kind.Int && value.i == pat.i;
            case Float -> value.isNumeric() && Math.abs(value.asF64() - pat.real) < 1e-9;
            case String -> value.kind == Value.Kind.String && value.s.equals(pat.s);
            case Bool -> value.kind == Value.Kind.Bool && value.b == pat.b;
            default -> false;
        };
    }

    boolean matchVariant(Value value, String name, int line, int col) {
        if (value.kind != Value.Kind.Enum) {
            throw runtime("match pattern '" + name + "' does not match value of type " + value.toPrintString(), line, col);
        }
        return value.variant.equals(name);
    }

    Value matchBind(Value value, int index, String field, int line, int col) {
        List<Value> payload = value.payload == null ? List.of() : value.payload;
        if (field != null && !field.isEmpty()) {
            EnumDecl en = checker.findEnum(value.s);
            EnumVariant var = null;
            if (en != null) {
                for (EnumVariant v : en.variants) {
                    if (v.name.equals(value.variant)) {
                        var = v;
                        break;
                    }
                }
            }
            if (var == null) {
                throw runtime("undefined enum '" + value.s + "'", line, col);
            }
            int idx = -1;
            for (int fi = 0; fi < var.fieldNames.size(); fi++) {
                if (var.fieldNames.get(fi).equals(field)) {
                    idx = fi;
                    break;
                }
            }
            if (idx < 0) {
                throw runtime(value.s + "." + value.variant + " has no field '" + field + "'", line, col);
            }
            if (idx >= payload.size()) {
                throw runtime(value.s + "." + value.variant + " field '" + field + "' is missing", line, col);
            }
            return payload.get(idx);
        }
        if (index < 0) {
            if (payload.size() == 1) {
                return payload.getFirst();
            }
            return Value.makeArray(payload);
        }
        if (index >= payload.size()) {
            return null;
        }
        return payload.get(index);
    }

    private void bindVariant(Value value, MatchArm arm, Map<String, Value.Binding> saved, List<String> added, int line, int col) {
        boolean named = false;
        for (String f : arm.fieldNames) {
            if (f != null && !f.isEmpty()) {
                named = true;
                break;
            }
        }
        if (named) {
            for (int i = 0; i < arm.binds.size(); i++) {
                String field = i < arm.fieldNames.size() ? arm.fieldNames.get(i) : "";
                if (field == null || field.isEmpty()) {
                    continue;
                }
                bindMatchName(arm.binds.get(i), matchBind(value, 0, field, line, col), saved, added);
            }
        } else if (arm.binds.size() == 1) {
            bindMatchName(arm.binds.getFirst(), matchBind(value, -1, "", line, col), saved, added);
        } else {
            for (int i = 0; i < arm.binds.size(); i++) {
                Value bound = matchBind(value, i, "", line, col);
                if (bound != null) {
                    bindMatchName(arm.binds.get(i), bound, saved, added);
                }
            }
        }
    }

    private void bindMatchName(String name, Value v, Map<String, Value.Binding> saved, List<String> added) {
        if (name == null || name.isEmpty() || "_".equals(name)) {
            return;
        }
        if (env.getLast().containsKey(name)) {
            saved.put(name, env.getLast().get(name));
        } else {
            added.add(name);
        }
        env.getLast().put(name, new Value.Binding(v, false));
    }

    private Value eval(Expr e) {
        return switch (e.kind) {
            case Int -> Value.makeInt(e.number);
            case Float -> Value.makeFloat(e.real);
            case String -> Value.makeString(e.text);
            case Bool -> Value.makeBool(e.booleanValue);
            case Var -> resolveValue(e.text, e.line, e.col);
            case Unary -> applyUnary(e.text, eval(e.kids.getFirst()), e.line, e.col);
            case Binary -> {
                if ("&&".equals(e.text)) {
                    Value a = eval(e.kids.get(0));
                    yield Value.makeBool(a.truthy() && eval(e.kids.get(1)).truthy());
                }
                if ("||".equals(e.text)) {
                    Value a = eval(e.kids.get(0));
                    yield Value.makeBool(a.truthy() || eval(e.kids.get(1)).truthy());
                }
                yield applyBinop(e.text, eval(e.kids.get(0)), eval(e.kids.get(1)), e.line, e.col);
            }
            case Call -> evalCall(e);
            case Array -> {
                List<Value> elems = new ArrayList<>();
                for (Expr kid : e.kids) {
                    elems.add(eval(kid));
                }
                yield Value.makeArray(elems);
            }
            case Range -> {
                Value a = eval(e.kids.get(0));
                Value b = eval(e.kids.get(1));
                if (a.kind != Value.Kind.Int) {
                    throw runtime("range start must be Int", e.kids.getFirst().line, e.kids.getFirst().col);
                }
                if (b.kind != Value.Kind.Int) {
                    throw runtime("range end must be Int", e.kids.get(1).line, e.kids.get(1).col);
                }
                yield Value.makeRange(a.i, b.i, e.booleanValue);
            }
            case Index -> {
                if (e.kids.size() < 2) {
                    throw runtime("invalid index", e.line, e.col);
                }
                yield indexGet(eval(e.kids.get(0)), eval(e.kids.get(1)), e.line, e.col);
            }
            case Try -> eval(e.kids.getFirst());
            case Member -> evalMember(e);
            case MethodCall -> evalMethodCall(e);
            case StructLit -> evalStructLit(e);
            case Map -> evalMapLit(e);
            case Lambda -> evalLambda(e);
            case Await -> {
                if (e.kids.isEmpty()) {
                    throw runtime("invalid await", e.line, e.col);
                }
                Value f = eval(e.kids.getFirst());
                if (f.kind != Value.Kind.Future || f.fut == null) {
                    throw runtime("can only await a Future", e.line, e.col);
                }
                yield awaitFuture(f.fut, e.line, e.col);
            }
        };
    }

    private Value evalMember(Expr e) {
        Value obj;
        if (!e.kids.isEmpty() && e.kids.getFirst().kind == Expr.Kind.Var && "super".equals(e.kids.getFirst().text)) {
            obj = selfObject(e.line, e.col);
        } else {
            obj = eval(e.kids.getFirst());
        }
        return readMember(obj, e.text, e.line, e.col);
    }

    Value readMember(Value obj, String name, int line, int col) {
        if (obj.kind == Value.Kind.EnumType) {
            EnumDecl en = checker.findEnum(obj.s);
            if (en == null) {
                throw runtime("undefined enum '" + obj.s + "'", line, col);
            }
            return constructEnum(en, name, List.of(), line, col);
        }
        if (obj.kind == Value.Kind.Array || obj.kind == Value.Kind.String || obj.kind == Value.Kind.Map) {
            return readValueMember(obj, name, line, col);
        }
        if (obj.kind != Value.Kind.Struct || obj.rec == null) {
            throw runtime("cannot read field on " + obj.toPrintString(), line, col);
        }
        Value field = obj.rec.fields.get(name);
        if (field == null) {
            if (checker.lookupTypeSignal(obj.rec.name, name) != null) {
                return Value.makeSignalRef(name, obj.rec);
            }
            throw runtime("struct " + obj.rec.name + " has no field '" + name + "'", line, col);
        }
        return field;
    }

    private Value evalMethodCall(Expr e) {
        List<Value> args = new ArrayList<>();
        for (int i = 1; i < e.kids.size(); i++) {
            args.add(eval(e.kids.get(i)));
        }
        Expr recv = e.kids.getFirst();
        if (recv.kind == Expr.Kind.Var && "super".equals(recv.text)) {
            return callSuper(e.text, args, e.line, e.col);
        }
        if (recv.kind == Expr.Kind.Var) {
            Value.Binding self = findLocalBinding("self");
            if (self != null && self.value.kind == Value.Kind.Struct && self.value.rec != null
                    && checker.lookupTypeSignal(self.value.rec.name, recv.text) != null) {
                return dispatchSignal(Value.makeSignalRef(recv.text, self.value.rec), e.text, args, e.line, e.col);
            }
            if (checker.signalArity.containsKey(recv.text)) {
                return callSignal(recv.text, e.text, args, e.line, e.col);
            }
            String boundMod = findModuleBind(recv.text);
            if (boundMod != null) {
                return callModule(boundMod, e.text, args, e.line, e.col);
            }
            if (findLocalBinding(recv.text) != null || fieldOnSelf(recv.text) != null
                    || findGlobalBinding(recv.text) != null || checker.findEnum(recv.text) != null) {
                Value obj = resolveValue(recv.text, recv.line, recv.col);
                if (obj.kind == Value.Kind.SignalRef) {
                    return dispatchSignal(obj, e.text, args, e.line, e.col);
                }
                if (obj.kind == Value.Kind.EnumType) {
                    EnumDecl en = checker.findEnum(obj.s);
                    if (en == null) {
                        en = checker.findEnum(recv.text);
                    }
                    if (en == null) {
                        throw runtime("undefined enum '" + obj.s + "'", e.line, e.col);
                    }
                    return constructEnum(en, e.text, args, e.line, e.col);
                }
                if (obj.kind == Value.Kind.Struct && obj.rec != null) {
                    return callTypeMethod(obj, e.text, args, e.line, e.col);
                }
                if (obj.kind == Value.Kind.Array || obj.kind == Value.Kind.String || obj.kind == Value.Kind.Map
                        || obj.kind == Value.Kind.Future) {
                    return callValueMethod(obj, e.text, args, e.line, e.col);
                }
                FnDecl ufcs = checker.findUfcs(e.text, typeNameOf(obj));
                if (ufcs != null) {
                    return callUfcs(ufcs, obj, args, e.line, e.col);
                }
                throw runtime("cannot call method '" + e.text + "' on " + obj.toPrintString(), e.line, e.col);
            }
            return callBuiltin(recv.text, e.text, args, e.line, e.col);
        }
        String nestedMod = modulePath(recv);
        if (nestedMod != null) {
            return callModule(nestedMod, e.text, args, e.line, e.col);
        }
        return callMethod(eval(recv), e.text, args, e.line, e.col);
    }

    Value callMethod(Value obj, String name, List<Value> args, int line, int col) {
        if (obj.kind == Value.Kind.SignalRef) {
            return dispatchSignal(obj, name, args, line, col);
        }
        if (obj.kind == Value.Kind.EnumType) {
            EnumDecl en = checker.findEnum(obj.s);
            if (en == null) {
                throw runtime("undefined enum '" + obj.s + "'", line, col);
            }
            return constructEnum(en, name, args, line, col);
        }
        if (obj.kind == Value.Kind.Struct && obj.rec != null) {
            return callTypeMethod(obj, name, args, line, col);
        }
        if (obj.kind == Value.Kind.Array || obj.kind == Value.Kind.String || obj.kind == Value.Kind.Map
                || obj.kind == Value.Kind.Future) {
            return callValueMethod(obj, name, args, line, col);
        }
        FnDecl ufcs = checker.findUfcs(name, typeNameOf(obj));
        if (ufcs != null) {
            return callUfcs(ufcs, obj, args, line, col);
        }
        throw runtime("cannot call method '" + name + "' on " + obj.toPrintString(), line, col);
    }

    private Value evalStructLit(Expr e) {
        List<Value> provided = new ArrayList<>();
        for (Expr kid : e.kids) {
            provided.add(eval(kid));
        }
        return structLit(e.text, e.names, provided, e.line, e.col);
    }

    Value structLit(String typeName, List<String> names, List<Value> provided, int line, int col) {
        if (Boolean.TRUE.equals(checker.classAbstract.get(typeName))) {
            throw runtime("cannot construct abstract class '" + typeName + "'", line, col);
        }
        StructDecl decl = checker.findStruct(typeName);
        if (decl == null) {
            throw runtime("undefined struct '" + typeName + "'", line, col);
        }
        Value.StructData data = new Value.StructData();
        data.name = decl.name;
        data.order.addAll(decl.fields);
        for (int i = 0; i < names.size(); i++) {
            String field = names.get(i);
            if (!decl.fields.contains(field)) {
                throw runtime("unknown field '" + field + "' on " + decl.name, line, col);
            }
            if (data.fields.containsKey(field)) {
                throw runtime("duplicate field '" + field + "' on " + decl.name, line, col);
            }
            data.fields.put(field, i < provided.size() ? provided.get(i) : Value.makeVoid());
        }
        Map<String, Expr> defaults = checker.fieldDefaults.get(decl.name);
        for (String field : decl.fields) {
            if (data.fields.containsKey(field)) {
                continue;
            }
            if (defaults != null && defaults.containsKey(field)) {
                data.fields.put(field, eval(defaults.get(field)));
                continue;
            }
            if (decl.isOptionalField(field)) {
                data.fields.put(field, zeroOfType(decl.typeOfField(field)));
                continue;
            }
            throw runtime("missing field '" + field + "' on " + decl.name, line, col);
        }
        initInstanceSignals(data);
        return Value.makeStruct(data);
    }

    Value structLitFilled(String typeName, List<Value> fields, int line, int col) {
        if (Boolean.TRUE.equals(checker.classAbstract.get(typeName))) {
            throw runtime("cannot construct abstract class '" + typeName + "'", line, col);
        }
        StructDecl decl = checker.findStruct(typeName);
        if (decl == null) {
            throw runtime("undefined struct '" + typeName + "'", line, col);
        }
        if (fields.size() != decl.fields.size()) {
            throw runtime("missing field on " + decl.name, line, col);
        }
        Value.StructData data = new Value.StructData();
        data.name = decl.name;
        data.order.addAll(decl.fields);
        for (int i = 0; i < decl.fields.size(); i++) {
            data.fields.put(decl.fields.get(i), fields.get(i));
        }
        initInstanceSignals(data);
        return Value.makeStruct(data);
    }

    void setField(Value obj, String name, Value rhs, String op, int line, int col) {
        if (obj.kind != Value.Kind.Struct || obj.rec == null) {
            throw runtime("cannot assign field on " + obj.toPrintString(), line, col);
        }
        if (!obj.rec.fields.containsKey(name)) {
            throw runtime("struct " + obj.rec.name + " has no field '" + name + "'", line, col);
        }
        if (checker.isDataType(obj.rec.name)) {
            throw runtime("cannot assign to data field '" + name + "'", line, col);
        }
        obj.rec.fields.put(name, applyAssignOp(op, obj.rec.fields.get(name), rhs, line, col));
    }

    private Value evalMapLit(Expr e) {
        if (e.kids.size() % 2 != 0) {
            throw runtime("invalid map literal", e.line, e.col);
        }
        Value.MapData data = new Value.MapData();
        for (int i = 0; i < e.kids.size(); i += 2) {
            Value key = eval(e.kids.get(i));
            if (key.kind != Value.Kind.String) {
                throw runtime("map key must be String", e.kids.get(i).line, e.kids.get(i).col);
            }
            if (!data.fields.containsKey(key.s)) {
                data.order.add(key.s);
            }
            data.fields.put(key.s, eval(e.kids.get(i + 1)));
        }
        return Value.makeMap(data);
    }

    Value mapLit(List<Value> kids, int line, int col) {
        if (kids.size() % 2 != 0) {
            throw runtime("invalid map literal", line, col);
        }
        Value.MapData data = new Value.MapData();
        for (int i = 0; i < kids.size(); i += 2) {
            Value key = kids.get(i);
            if (key.kind != Value.Kind.String) {
                throw runtime("map key must be String", line, col);
            }
            if (!data.fields.containsKey(key.s)) {
                data.order.add(key.s);
            }
            data.fields.put(key.s, kids.get(i + 1));
        }
        return Value.makeMap(data);
    }

    private Value evalLambda(Expr e) {
        if (e.lambda == null) {
            throw runtime("invalid closure", e.line, e.col);
        }
        Set<String> bound = new HashSet<>(e.lambda.params);
        Set<String> free = new HashSet<>();
        for (Stmt st : e.lambda.body) {
            collectFreeStmt(st, bound, free);
        }
        Value.ClosureData clo = new Value.ClosureData();
        clo.fn = e.lambda;
        boolean capturedSelf = false;
        for (String name : free) {
            if (skipCaptureName(name) || checker.findLocalFn(name) != null || fns.containsKey(name)
                    || checker.structs.containsKey(name) || checker.allTypes.containsKey(name)
                    || checker.enums.containsKey(name) || checker.traits.containsKey(name)
                    || checker.signalArity.containsKey(name) || checker.moduleBinds.containsKey(name)
                    || Types.isHostModule(name)) {
                continue;
            }
            Value.Binding b = findLocalBinding(name);
            if (b != null) {
                clo.caps.put(name, new Value.Binding(b.value, b.isConst));
                if ("self".equals(name)) {
                    capturedSelf = true;
                }
                continue;
            }
            if (fieldOnSelf(name) != null || isSignalOnSelf(name)) {
                if (!capturedSelf) {
                    Value.Binding self = findLocalBinding("self");
                    if (self != null) {
                        clo.caps.put("self", new Value.Binding(self.value, self.isConst));
                        capturedSelf = true;
                    }
                }
            }
        }
        return Value.makeClosure(clo);
    }

    Value closure(FnDecl fn, List<String> names, List<Value> vals, List<Boolean> consts) {
        Value.ClosureData clo = new Value.ClosureData();
        clo.fn = fn;
        int n = names == null ? 0 : names.size();
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            if (name == null || name.isEmpty()) {
                continue;
            }
            Value v = vals != null && i < vals.size() ? vals.get(i) : Value.makeVoid();
            boolean isConst = consts != null && i < consts.size() && Boolean.TRUE.equals(consts.get(i));
            clo.caps.put(name, new Value.Binding(v, isConst));
        }
        return Value.makeClosure(clo);
    }

    static boolean skipCaptureName(String name) {
        return "print".equals(name) || "len".equals(name) || "assert".equals(name)
                || "argv".equals(name) || "argv_len".equals(name) || "super".equals(name);
    }

    private static void collectFreeExpr(Expr e, Set<String> bound, Set<String> free) {
        if (e == null) {
            return;
        }
        if (e.kind == Expr.Kind.Lambda && e.lambda != null) {
            Set<String> inner = new HashSet<>(bound);
            inner.addAll(e.lambda.params);
            for (Stmt st : e.lambda.body) {
                collectFreeStmt(st, inner, free);
            }
            return;
        }
        if (e.kind == Expr.Kind.Var) {
            if (!bound.contains(e.text) && !skipCaptureName(e.text)) {
                free.add(e.text);
            }
            return;
        }
        if (e.kind == Expr.Kind.Call && (e.module == null || e.module.isEmpty()) && !e.text.isEmpty()) {
            if (!bound.contains(e.text) && !skipCaptureName(e.text)) {
                free.add(e.text);
            }
        }
        for (Expr kid : e.kids) {
            collectFreeExpr(kid, bound, free);
        }
    }

    static void collectFreeStmt(Stmt s, Set<String> bound, Set<String> free) {
        switch (s.kind) {
            case Var, Const -> {
                collectFreeExpr(s.expr, bound, free);
                bound.add(s.name);
            }
            case Assign -> {
                collectFreeExpr(s.expr, bound, free);
                if (!bound.contains(s.name) && !skipCaptureName(s.name)) {
                    free.add(s.name);
                }
            }
            case FieldAssign, IndexAssign -> {
                collectFreeExpr(s.target, bound, free);
                collectFreeExpr(s.expr, bound, free);
            }
            case Return, Expr, Throw -> collectFreeExpr(s.expr, bound, free);
            case If -> {
                collectFreeExpr(s.expr, bound, free);
                for (Stmt st : s.body) {
                    collectFreeStmt(st, bound, free);
                }
                for (Stmt st : s.elseBody) {
                    collectFreeStmt(st, bound, free);
                }
            }
            case While -> {
                collectFreeExpr(s.expr, bound, free);
                for (Stmt st : s.body) {
                    collectFreeStmt(st, bound, free);
                }
            }
            case For -> {
                collectFreeExpr(s.expr, bound, free);
                Set<String> inner = new HashSet<>(bound);
                inner.add(s.name);
                for (Stmt st : s.body) {
                    collectFreeStmt(st, inner, free);
                }
            }
            case Match -> {
                collectFreeExpr(s.expr, bound, free);
                for (MatchArm arm : s.arms) {
                    Set<String> inner = new HashSet<>(bound);
                    inner.addAll(arm.binds);
                    for (Stmt st : arm.body) {
                        collectFreeStmt(st, inner, free);
                    }
                }
            }
            case Do -> {
                for (Stmt st : s.body) {
                    collectFreeStmt(st, bound, free);
                }
                Set<String> inner = new HashSet<>(bound);
                if (s.name != null && !s.name.isEmpty()) {
                    inner.add(s.name);
                }
                for (Stmt st : s.elseBody) {
                    collectFreeStmt(st, inner, free);
                }
            }
            default -> {
            }
        }
    }

    Value applyUnary(String op, Value v, int line, int col) {
        if ("-".equals(op)) {
            if (v.kind == Value.Kind.Float) {
                return Value.makeFloat(-v.real);
            }
            if (v.kind != Value.Kind.Int) {
                throw runtime("unary '-' expects a number", line, col);
            }
            if (v.i == Long.MIN_VALUE) {
                throw runtime("integer overflow", line, col);
            }
            return Value.makeInt(-v.i);
        }
        return Value.makeBool(!v.truthy());
    }

    private Value evalCall(Expr e) {
        List<Value> args = new ArrayList<>();
        if (e.text.isEmpty()) {
            if (e.kids.isEmpty()) {
                throw runtime("can only call a function", e.line, e.col);
            }
            Value fn = eval(e.kids.getFirst());
            for (int i = 1; i < e.kids.size(); i++) {
                args.add(eval(e.kids.get(i)));
            }
            return callFnValue(fn, args, e.line, e.col);
        }
        for (Expr kid : e.kids) {
            args.add(eval(kid));
        }
        return call(e.module, e.text, args, e.line, e.col);
    }

    Value callQualified(String module, String name, List<Value> args, int line, int col) {
        if (module == null || module.isEmpty()) {
            return call("", name, args, line, col);
        }
        if (checker.loaded.containsKey(module)) {
            return callModule(module, name, args, line, col);
        }
        return call(module, name, args, line, col);
    }

    Value call(String module, String name, List<Value> args, int line, int col) {
        if (module == null || module.isEmpty()) {
            FnDecl fn = checker.findLocalFn(name);
            if (fn != null) {
                return callUser(fn, args, line, col);
            }
            Value.Binding local = findVar(name);
            if (local != null && local.value.kind == Value.Kind.FnRef) {
                return callFnValue(local.value, args, line, col);
            }
            Value.Binding self = findLocalBinding("self");
            if (self != null && self.value.kind == Value.Kind.Struct && self.value.rec != null
                    && checker.lookupMethod(self.value.rec.name, name).fn != null) {
                return invokeTypeMethod(self.value, self.value.rec.name, name, args, line, col);
            }
        } else if (checker.signalArity.containsKey(module)) {
            return callSignal(module, name, args, line, col);
        }
        return callBuiltin(module == null ? "" : module, name, args, line, col);
    }

    private String findModuleBind(String name) {
        if (!checker.currentModule.isEmpty()) {
            Checker.LoadedMod lit = checker.loaded.get(checker.currentModule);
            if (lit == null) {
                return null;
            }
            return lit.modules.get(name);
        }
        return checker.moduleBinds.get(name);
    }

    private String modulePath(Expr e) {
        if (e.kind == Expr.Kind.Var) {
            return findModuleBind(e.text);
        }
        if (e.kind == Expr.Kind.Member && !e.kids.isEmpty()) {
            String parent = modulePath(e.kids.getFirst());
            if (parent == null) {
                return null;
            }
            Checker.LoadedMod lit = checker.loaded.get(parent);
            if (lit == null) {
                return null;
            }
            boolean internal = checker.currentModule.equals(parent);
            Map<String, String> map = internal ? lit.modules : lit.exportMods;
            String hit = map.get(e.text);
            if (hit != null) {
                return hit;
            }
            throw runtime("module '" + parent + "' has no export '" + e.text + "'", e.line, e.col);
        }
        return null;
    }

    private Value callModule(String modName, String name, List<Value> args, int line, int col) {
        Checker.LoadedMod mod = checker.loaded.get(modName);
        if (mod == null) {
            throw runtime("unknown function " + modName + "." + name, line, col);
        }
        boolean internal = checker.currentModule.equals(modName);
        FnDecl fn = internal ? mod.fns.get(name) : mod.exports.get(name);
        if (fn == null && internal) {
            fn = mod.fromFns.get(name);
        }
        if (fn == null) {
            throw runtime("module '" + modName + "' has no export '" + name + "'", line, col);
        }
        return callUser(fn, args, line, col);
    }

    Value callFnValue(Value fn, List<Value> args, int line, int col) {
        if (fn.kind != Value.Kind.FnRef) {
            throw runtime("can only call a function", line, col);
        }
        if (fn.clo != null) {
            if (fn.clo.fn == null) {
                throw runtime("invalid closure", line, col);
            }
            return callUser(fn.clo.fn, args, line, col, fn.clo.caps);
        }
        FnDecl decl = fns.get(fn.s);
        if (decl == null) {
            throw runtime("unknown function '" + fn.s + "'", line, col);
        }
        return callUser(decl, args, line, col);
    }

    private Value callBuiltin(String module, String name, List<Value> args, int line, int col) {
        if (module.isEmpty() && name.equals("print")) {
            StringBuilder parts = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    parts.append(' ');
                }
                parts.append(args.get(i).toPrintString());
            }
            emit(parts + "\n");
            return Value.makeVoid();
        }
        if (module.isEmpty() && name.equals("assert")) {
            if (args.size() != 1) {
                throw runtime("assert takes 1 argument", line, col);
            }
            if (!args.getFirst().truthy()) {
                throw runtime("assertion failed", line, col);
            }
            return Value.makeVoid();
        }
        if (module.isEmpty() && name.equals("len")) {
            if (args.size() != 1) {
                throw runtime("len takes 1 argument", line, col);
            }
            Value v = args.getFirst();
            if (v.kind == Value.Kind.Array) {
                return Value.makeInt(v.items == null ? 0 : v.items.size());
            }
            if (v.kind == Value.Kind.String) {
                return Value.makeInt(v.s.length());
            }
            if (v.kind == Value.Kind.Map) {
                return Value.makeInt(v.dict == null ? 0 : v.dict.fields.size());
            }
            throw runtime("len expects Array, String, or Map", line, col);
        }
        if ((module.isEmpty() && name.equals("argv")) || (module.equals("process") && name.equals("argv"))) {
            if (args.size() != 1) {
                throw runtime("argv takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.Int) {
                throw runtime("argv index must be Int", line, col);
            }
            long i = args.getFirst().i;
            if (i < 0 || i >= argv.size()) {
                throw runtime("argv index out of range", line, col);
            }
            return Value.makeString(argv.get((int) i));
        }
        if ((module.isEmpty() && name.equals("argv_len")) || (module.equals("process") && name.equals("argc"))) {
            if (!args.isEmpty()) {
                throw runtime(module.isEmpty() ? "argv_len takes 0 arguments" : "process.argc takes 0 arguments", line, col);
            }
            return Value.makeInt(argv.size());
        }
        if ("checks".equals(module)) {
            return callChecks(name, args, line, col);
        }
        if ("__math".equals(module)) {
            return callMath(name, args, line, col);
        }
        if ("__str".equals(module)) {
            return callStr(name, args, line, col);
        }
        if ("__io".equals(module)) {
            return callIo(name, args, line, col);
        }
        if ("__uuid".equals(module)) {
            return callUuid(name, args, line, col);
        }
        if ("__time".equals(module)) {
            return callTime(name, args, line, col);
        }
        if ("__path".equals(module)) {
            return callPath(name, args, line, col);
        }
        if ("__json".equals(module)) {
            return callJson(name, args, line, col);
        }
        if ("__regex".equals(module)) {
            return callRegex(name, args, line, col);
        }
        if ("__ui".equals(module)) {
            return ui.call(this, name, args, line, col);
        }
        if ("Future".equals(module)) {
            return callFuture(name, args, line, col);
        }
        throw runtime(module.isEmpty() ? "unknown function '" + name + "'" : "unknown function " + module + "." + name, line, col);
    }

    private Value callFuture(String name, List<Value> args, int line, int col) {
        if (!"all".equals(name) && !"race".equals(name)) {
            throw runtime("unknown function Future." + name, line, col);
        }
        if (args.size() != 1) {
            throw runtime("Future." + name + " takes 1 argument", line, col);
        }
        if (args.getFirst().kind != Value.Kind.Array || args.getFirst().items == null) {
            throw runtime("Future." + name + " expects Array of Future", line, col);
        }
        List<Value> items = args.getFirst().items;
        for (Value item : items) {
            if (item.kind != Value.Kind.Future || item.fut == null) {
                throw runtime("Future." + name + " expects Array of Future", line, col);
            }
        }
        Value.FutureData out = new Value.FutureData();
        if (items.isEmpty()) {
            if ("all".equals(name)) {
                settleFuture(out, Value.makeArray(List.of()));
            } else {
                failFuture(out, Value.makeString("Future.race on empty Array"), line, col);
            }
            return Value.makeFuture(out);
        }
        List<Value.FutureData> futs = new ArrayList<>(items.size());
        for (Value item : items) {
            futs.add(item.fut);
        }
        if ("all".equals(name)) {
            AllState state = new AllState();
            state.out = out;
            state.results = new ArrayList<>(futs.size());
            for (int i = 0; i < futs.size(); i++) {
                state.results.add(Value.makeVoid());
            }
            state.remaining = futs.size();
            for (int i = 0; i < futs.size(); i++) {
                int index = i;
                Value.FutureData fut = futs.get(i);
                watchFuture(fut, () -> {
                    if (state.done) {
                        return;
                    }
                    if (fut.state == Value.FutureData.State.Failed) {
                        state.done = true;
                        failFuture(state.out, fut.error, fut.errLine, fut.errCol);
                        return;
                    }
                    if (fut.state != Value.FutureData.State.Ready) {
                        return;
                    }
                    state.results.set(index, fut.result);
                    state.remaining--;
                    if (state.remaining == 0) {
                        state.done = true;
                        settleFuture(state.out, Value.makeArray(state.results));
                    }
                });
            }
        } else {
            RaceState state = new RaceState();
            state.out = out;
            for (Value.FutureData fut : futs) {
                watchFuture(fut, () -> {
                    if (state.done) {
                        return;
                    }
                    if (fut.state == Value.FutureData.State.Failed) {
                        state.done = true;
                        failFuture(state.out, fut.error, fut.errLine, fut.errCol);
                        return;
                    }
                    if (fut.state != Value.FutureData.State.Ready) {
                        return;
                    }
                    state.done = true;
                    settleFuture(state.out, fut.result);
                });
            }
        }
        return Value.makeFuture(out);
    }

    private void enqueueMicrotask(Runnable fn) {
        microtasks.addLast(fn);
    }

    private void settleFuture(Value.FutureData fut, Value value) {
        if (fut == null || fut.state != Value.FutureData.State.Pending) {
            return;
        }
        fut.state = Value.FutureData.State.Ready;
        fut.result = value;
        List<Runnable> waiters = new ArrayList<>(fut.waiters);
        fut.waiters.clear();
        for (Runnable fn : waiters) {
            enqueueMicrotask(fn);
        }
    }

    private void failFuture(Value.FutureData fut, Value error, int line, int col) {
        if (fut == null || fut.state != Value.FutureData.State.Pending) {
            return;
        }
        fut.state = Value.FutureData.State.Failed;
        fut.error = error == null ? Value.makeVoid() : error;
        fut.errLine = line;
        fut.errCol = col;
        List<Runnable> waiters = new ArrayList<>(fut.waiters);
        fut.waiters.clear();
        for (Runnable fn : waiters) {
            enqueueMicrotask(fn);
        }
    }

    private void watchFuture(Value.FutureData fut, Runnable fn) {
        if (fut == null || fut.state != Value.FutureData.State.Pending) {
            enqueueMicrotask(fn);
            return;
        }
        fut.waiters.add(fn);
    }

    private void cancelFuture(Value.FutureData fut, int line, int col) {
        if (fut == null || fut.state != Value.FutureData.State.Pending) {
            return;
        }
        timers.removeIf(job -> job.fut == fut);
        frameJobs.removeIf(job -> job.fut == fut);
        failFuture(fut, Value.makeString("cancelled"), line, col);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private boolean pumpEventLoopOnce(boolean mayWait) {
        flushDeferred();
        boolean progress = false;
        while (!microtasks.isEmpty()) {
            progress = true;
            microtasks.removeFirst().run();
            flushDeferred();
        }
        long now = nowMs();
        final long dueAt = now;
        List<Value.FutureData> due = new ArrayList<>();
        timers.removeIf(job -> {
            if (job.deadlineMs <= dueAt) {
                due.add(job.fut);
                return true;
            }
            return false;
        });
        for (Value.FutureData fut : due) {
            progress = true;
            settleFuture(fut, Value.makeVoid());
        }
        if (pumpFrameJobs()) {
            progress = true;
        }
        if (progress) {
            return true;
        }
        if (!mayWait || timers.isEmpty()) {
            return false;
        }
        long next = timers.getFirst().deadlineMs;
        for (TimerJob job : timers) {
            if (job.deadlineMs < next) {
                next = job.deadlineMs;
            }
        }
        now = nowMs();
        if (next > now) {
            try {
                Thread.sleep(next - now);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw runtime("event loop interrupted", 1, 1);
            }
        }
        now = nowMs();
        java.util.Iterator<TimerJob> it = timers.iterator();
        while (it.hasNext()) {
            TimerJob job = it.next();
            if (job.deadlineMs <= now) {
                settleFuture(job.fut, Value.makeVoid());
                it.remove();
                progress = true;
            }
        }
        return progress || !microtasks.isEmpty();
    }

    private boolean pumpFrameJobs() {
        if (frameJobs.isEmpty()) {
            return false;
        }
        List<FrameJob> jobs = new ArrayList<>(frameJobs);
        frameJobs.clear();
        for (FrameJob job : jobs) {
            settleFuture(job.fut, Value.makeBool(ui.isAlive(job.winId)));
        }
        return true;
    }

    private void drainEventLoop() {
        for (int i = 0; i < MAX_EVENT_LOOP; i++) {
            if (!pumpEventLoopOnce(false)) {
                if (microtasks.isEmpty() && timers.isEmpty() && frameJobs.isEmpty()) {
                    return;
                }
                if (!pumpEventLoopOnce(true)) {
                    return;
                }
            }
        }
        throw runtime("event loop exceeded iteration limit", 1, 1);
    }

    Value awaitFuture(Value.FutureData fut, int line, int col) {
        if (fut == null) {
            throw runtime("can only await a Future", line, col);
        }
        while (fut.state == Value.FutureData.State.Pending) {
            if (!pumpEventLoopOnce(true)) {
                throw runtime("await deadlock: Future never settles", line, col);
            }
        }
        if (fut.state == Value.FutureData.State.Failed) {
            throw new ThrowEscape(fut.error, fut.errLine, fut.errCol);
        }
        return fut.result;
    }

    private Value callChecks(String name, List<Value> args, int line, int col) {
        if (name.equals("eq") || name.equals("neq")) {
            if (args.size() != 2) {
                throw runtime("checks." + name + " takes 2 argument(s)", line, col);
            }
            if (!args.get(0).isNumeric() || !args.get(1).isNumeric()) {
                throw runtime("checks." + name + " expects numbers", line, col);
            }
            boolean same = args.get(0).equalsValue(args.get(1));
            if (name.equals("eq") ? !same : same) {
                throw runtime("assertion failed", line, col);
            }
            return Value.makeVoid();
        }
        if (name.equals("eq_string")) {
            if (args.size() != 2) {
                throw runtime("checks.eq_string takes 2 argument(s)", line, col);
            }
            if (args.get(0).kind != Value.Kind.String || args.get(1).kind != Value.Kind.String) {
                throw runtime("checks.eq_string expects String, String", line, col);
            }
            if (!args.get(0).s.equals(args.get(1).s)) {
                throw runtime("assertion failed", line, col);
            }
            return Value.makeVoid();
        }
        if (name.equals("that") || name.equals("truthy")) {
            if (args.size() != 1) {
                throw runtime("checks." + name + " takes 1 argument(s)", line, col);
            }
            if (!args.getFirst().truthy()) {
                throw runtime("assertion failed", line, col);
            }
            return Value.makeVoid();
        }
        throw runtime("unknown function checks." + name, line, col);
    }

    private Value callMath(String name, List<Value> args, int line, int col) {
        if ("pow".equals(name)) {
            if (args.size() != 2) {
                throw runtime("__math.pow takes 2 arguments", line, col);
            }
            if (args.get(0).kind != Value.Kind.Int || args.get(1).kind != Value.Kind.Int) {
                throw runtime("__math.pow expects Int, Int", line, col);
            }
            if (args.get(1).i < 0) {
                return Value.makeInt(0);
            }
            long r = 1;
            long base = args.get(0).i;
            long exp = args.get(1).i;
            while (exp != 0) {
                if ((exp & 1) != 0) {
                    r *= base;
                }
                exp >>>= 1;
                if (exp != 0) {
                    base *= base;
                }
            }
            return Value.makeInt(r);
        }
        if ("rand_int".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__math.rand_int takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.Int) {
                throw runtime("__math.rand_int expects Int", line, col);
            }
            if (args.getFirst().i <= 0) {
                throw runtime("__math.rand_int expects n > 0", line, col);
            }
            long n = args.getFirst().i;
            if (n > Integer.MAX_VALUE) {
                n = Integer.MAX_VALUE;
            }
            return Value.makeInt(rng.nextInt((int) n));
        }
        if ("random".equals(name)) {
            if (!args.isEmpty()) {
                throw runtime("__math.random takes 0 arguments", line, col);
            }
            return Value.makeFloat(rng.nextDouble());
        }
        if ("sin".equals(name) || "cos".equals(name) || "sqrt".equals(name) || "floor".equals(name)
                || "ceil".equals(name) || "to_int".equals(name) || "to_float".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__math." + name + " takes 1 argument", line, col);
            }
            if ("to_float".equals(name)) {
                if (args.getFirst().kind != Value.Kind.Int) {
                    throw runtime("__math.to_float expects Int", line, col);
                }
                return Value.makeFloat((double) args.getFirst().i);
            }
            if (!args.getFirst().isNumeric()) {
                throw runtime("__math." + name + " expects Float", line, col);
            }
            double n = args.getFirst().asF64();
            return switch (name) {
                case "sin" -> Value.makeFloat(Math.sin(n));
                case "cos" -> Value.makeFloat(Math.cos(n));
                case "sqrt" -> {
                    if (n < 0.0) {
                        throw runtime("__math.sqrt expects n >= 0", line, col);
                    }
                    yield Value.makeFloat(Math.sqrt(n));
                }
                case "floor" -> Value.makeFloat(Math.floor(n));
                case "ceil" -> Value.makeFloat(Math.ceil(n));
                default -> Value.makeInt((long) n);
            };
        }
        if ("atan2".equals(name) || "powf".equals(name)) {
            if (args.size() != 2) {
                throw runtime("__math." + name + " takes 2 arguments", line, col);
            }
            if (!args.get(0).isNumeric() || !args.get(1).isNumeric()) {
                throw runtime("__math." + name + " expects Float", line, col);
            }
            double a = args.get(0).asF64();
            double b = args.get(1).asF64();
            if ("atan2".equals(name)) {
                return Value.makeFloat(Math.atan2(a, b));
            }
            return Value.makeFloat(Math.pow(a, b));
        }
        throw runtime("unknown function __math." + name, line, col);
    }

    private Value callStr(String name, List<Value> args, int line, int col) {
        java.util.function.IntFunction<String> needStr = i -> {
            if (args.get(i).kind != Value.Kind.String) {
                throw runtime("__str." + name + " expects String", line, col);
            }
            return args.get(i).s;
        };
        java.util.function.IntFunction<Long> needInt = i -> {
            if (args.get(i).kind != Value.Kind.Int) {
                throw runtime("__str." + name + " expects Int", line, col);
            }
            return args.get(i).i;
        };
        if ("contains".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.contains takes 2 arguments", line, col);
            }
            return Value.makeBool(needStr.apply(0).contains(needStr.apply(1)));
        }
        if ("starts_with".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.starts_with takes 2 arguments", line, col);
            }
            return Value.makeBool(needStr.apply(0).startsWith(needStr.apply(1)));
        }
        if ("ends_with".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.ends_with takes 2 arguments", line, col);
            }
            return Value.makeBool(needStr.apply(0).endsWith(needStr.apply(1)));
        }
        if ("length".equals(name)) {
            if (args.size() != 1) {
                throw runtime("str.length takes 1 argument", line, col);
            }
            return Value.makeInt(needStr.apply(0).length());
        }
        if ("is_empty".equals(name)) {
            if (args.size() != 1) {
                throw runtime("str.is_empty takes 1 argument", line, col);
            }
            return Value.makeBool(needStr.apply(0).isEmpty());
        }
        if ("repeat".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.repeat takes 2 arguments", line, col);
            }
            String s = needStr.apply(0);
            long n = needInt.apply(1);
            if (n < 0) {
                n = 0;
            }
            if (n > 0 && !s.isEmpty() && (long) s.length() * n > MAX_REPEAT_BYTES) {
                throw runtime("str.repeat result too large", line, col);
            }
            return Value.makeString(s.repeat((int) Math.min(n, Integer.MAX_VALUE)));
        }
        if ("upper".equals(name) || "lower".equals(name)) {
            if (args.size() != 1) {
                throw runtime("str." + name + " takes 1 argument", line, col);
            }
            String s = needStr.apply(0);
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                out.append("upper".equals(name) ? Character.toUpperCase(c) : Character.toLowerCase(c));
            }
            return Value.makeString(out.toString());
        }
        if ("trim".equals(name)) {
            if (args.size() != 1) {
                throw runtime("str.trim takes 1 argument", line, col);
            }
            return Value.makeString(needStr.apply(0).trim());
        }
        if ("slice".equals(name)) {
            if (args.size() != 3) {
                throw runtime("str.slice takes 3 arguments", line, col);
            }
            String s = needStr.apply(0);
            long start = needInt.apply(1);
            long end = needInt.apply(2);
            long len = s.length();
            if (start < 0) {
                start = 0;
            }
            if (start > len) {
                start = len;
            }
            if (end < 0) {
                end = 0;
            }
            if (end > len) {
                end = len;
            }
            if (end < start) {
                end = start;
            }
            return Value.makeString(s.substring((int) start, (int) end));
        }
        if ("split".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.split takes 2 arguments", line, col);
            }
            String s = needStr.apply(0);
            String sep = needStr.apply(1);
            List<Value> parts = new ArrayList<>();
            if (sep.isEmpty()) {
                for (int i = 0; i < s.length(); i++) {
                    parts.add(Value.makeString(String.valueOf(s.charAt(i))));
                }
            } else {
                int start = 0;
                while (true) {
                    int pos = s.indexOf(sep, start);
                    if (pos < 0) {
                        parts.add(Value.makeString(s.substring(start)));
                        break;
                    }
                    parts.add(Value.makeString(s.substring(start, pos)));
                    start = pos + sep.length();
                }
            }
            return Value.makeArray(parts);
        }
        if ("replace".equals(name)) {
            if (args.size() != 3) {
                throw runtime("str.replace takes 3 arguments", line, col);
            }
            String s = needStr.apply(0);
            String from = needStr.apply(1);
            String to = needStr.apply(2);
            if (!from.isEmpty()) {
                StringBuilder out = new StringBuilder();
                int pos = 0;
                while (true) {
                    int hit = s.indexOf(from, pos);
                    if (hit < 0) {
                        out.append(s.substring(pos));
                        break;
                    }
                    out.append(s, pos, hit).append(to);
                    pos = hit + from.length();
                }
                s = out.toString();
            }
            return Value.makeString(s);
        }
        if ("find".equals(name)) {
            if (args.size() != 2) {
                throw runtime("str.find takes 2 arguments", line, col);
            }
            int pos = needStr.apply(0).indexOf(needStr.apply(1));
            return Value.makeInt(pos);
        }
        throw runtime("unknown function __str." + name, line, col);
    }

    private Value callIo(String name, List<Value> args, int line, int col) {
        if ("exists".equals(name)) {
            if (args.size() != 1) {
                throw runtime("io.exists takes 1 argument", line, col);
            }
            return Value.makeBool(Files.exists(sandboxPath(needIoString(args, 0, name, line, col), line, col)));
        }
        if ("remove".equals(name)) {
            if (args.size() != 1) {
                throw runtime("io.remove takes 1 argument", line, col);
            }
            try {
                return Value.makeBool(Files.deleteIfExists(sandboxPath(needIoString(args, 0, name, line, col), line, col)));
            } catch (IOException ex) {
                return Value.makeBool(false);
            }
        }
        if ("read_text".equals(name)) {
            if (args.size() != 1) {
                throw runtime("io.read_text takes 1 argument", line, col);
            }
            Path path = sandboxPath(needIoString(args, 0, name, line, col), line, col);
            try {
                return Value.makeString(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw ioThrow("cannot read '" + path.toString().replace('\\', '/') + "'", line, col);
            }
        }
        if ("read_lines".equals(name)) {
            if (args.size() != 1) {
                throw runtime("io.read_lines takes 1 argument", line, col);
            }
            Path path = sandboxPath(needIoString(args, 0, name, line, col), line, col);
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw ioThrow("cannot read '" + path.toString().replace('\\', '/') + "'", line, col);
            }
            List<Value> lines = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i != text.length() && text.charAt(i) != '\n') {
                    continue;
                }
                if (i == text.length() && start == i) {
                    break;
                }
                String row = text.substring(start, i);
                if (row.endsWith("\r")) {
                    row = row.substring(0, row.length() - 1);
                }
                lines.add(Value.makeString(row));
                start = i + 1;
            }
            return Value.makeArray(lines);
        }
        if ("write_text".equals(name)) {
            if (args.size() != 2) {
                throw runtime("io.write_text takes 2 arguments", line, col);
            }
            if (args.get(1).kind != Value.Kind.String) {
                throw runtime("io.write_text expects String", line, col);
            }
            Path path = sandboxPath(needIoString(args, 0, name, line, col), line, col);
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(path, args.get(1).s, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw ioThrow("cannot write '" + path.toString().replace('\\', '/') + "'", line, col);
            }
            return Value.makeVoid();
        }
        throw runtime("unknown function __io." + name, line, col);
    }

    private Value callUuid(String name, List<Value> args, int line, int col) {
        if ("v4".equals(name)) {
            if (!args.isEmpty()) {
                throw runtime("__uuid.v4 takes 0 arguments", line, col);
            }
            char[] hex = new char[32];
            for (int i = 0; i < 32; i++) {
                int n = rng.nextInt(16);
                if (i == 12) {
                    n = 4;
                } else if (i == 16) {
                    n = (n & 0x3) | 0x8;
                }
                hex[i] = UUID_DIGITS.charAt(n);
            }
            return Value.makeString(dashedUuid(new String(hex)));
        }
        if ("parse".equals(name) || "valid".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__uuid." + name + " takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.String) {
                throw runtime("__uuid." + name + " expects String", line, col);
            }
            String hex = uuidHex32(args.getFirst().s);
            if (hex == null) {
                if ("valid".equals(name)) {
                    return Value.makeBool(false);
                }
                throw new ThrowEscape(Value.makeString("invalid UUID"), line, col);
            }
            if ("valid".equals(name)) {
                return Value.makeBool(true);
            }
            return Value.makeString(dashedUuid(hex));
        }
        throw runtime("unknown function __uuid." + name, line, col);
    }

    private static String uuidHex32(String s) {
        StringBuilder hex = new StringBuilder(32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                continue;
            }
            if (!isHexDigit(c)) {
                return null;
            }
            hex.append(c >= 'A' && c <= 'F' ? (char) (c + 32) : c);
        }
        return hex.length() == 32 ? hex.toString() : null;
    }

    private static boolean isHexDigit(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static String dashedUuid(String hex) {
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    private Value callTime(String name, List<Value> args, int line, int col) {
        if ("now".equals(name)) {
            if (!args.isEmpty()) {
                throw runtime("__time.now takes 0 arguments", line, col);
            }
            return Value.makeInt(System.currentTimeMillis());
        }
        if ("sleep".equals(name) || "delay".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__time." + name + " takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.Int) {
                throw runtime("__time." + name + " expects Int", line, col);
            }
            long ms = args.getFirst().i;
            if (ms < 0) {
                ms = 0;
            }
            if (ms > MAX_SLEEP_MS) {
                throw runtime("time." + name + " duration too large", line, col);
            }
            if ("delay".equals(name)) {
                Value.FutureData fut = new Value.FutureData();
                timers.add(new TimerJob(nowMs() + ms, fut));
                return Value.makeFuture(fut);
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw runtime("time.sleep interrupted", line, col);
            }
            return Value.makeVoid();
        }
        throw runtime("unknown function __time." + name, line, col);
    }

    private Value callPath(String name, List<Value> args, int line, int col) {
        if ("join".equals(name)) {
            if (args.size() != 2) {
                throw runtime("__path.join takes 2 arguments", line, col);
            }
            return Value.makeString(genericPath(Path.of(needPathString(args, 0, name, line, col))
                    .resolve(needPathString(args, 1, name, line, col))));
        }
        if ("parent".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__path.parent takes 1 argument", line, col);
            }
            Path parent = Path.of(needPathString(args, 0, name, line, col)).getParent();
            return Value.makeString(parent == null ? "" : genericPath(parent));
        }
        if ("stem".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__path.stem takes 1 argument", line, col);
            }
            Path file = Path.of(needPathString(args, 0, name, line, col)).getFileName();
            String leaf = file == null ? "" : file.toString();
            int dot = leaf.lastIndexOf('.');
            if (dot > 0) {
                leaf = leaf.substring(0, dot);
            }
            return Value.makeString(leaf);
        }
        throw runtime("unknown function __path." + name, line, col);
    }

    private String needPathString(List<Value> args, int i, String name, int line, int col) {
        if (args.get(i).kind != Value.Kind.String) {
            throw runtime("__path." + name + " expects String", line, col);
        }
        return args.get(i).s;
    }

    private static String genericPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private Value callJson(String name, List<Value> args, int line, int col) {
        if ("parse".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__json.parse takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.String) {
                throw runtime("__json.parse expects String", line, col);
            }
            return Json.parse(args.getFirst().s, line, col);
        }
        if ("valid".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__json.valid takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.String) {
                throw runtime("__json.valid expects String", line, col);
            }
            try {
                Json.parse(args.getFirst().s, line, col);
                return Value.makeBool(true);
            } catch (ThrowEscape ignored) {
                return Value.makeBool(false);
            }
        }
        if ("stringify".equals(name)) {
            if (args.size() != 1) {
                throw runtime("__json.stringify takes 1 argument", line, col);
            }
            StringBuilder out = new StringBuilder();
            String err = Json.stringify(args.getFirst(), out, 0);
            if (err != null) {
                throw runtime(err, line, col);
            }
            return Value.makeString(out.toString());
        }
        throw runtime("unknown function __json." + name, line, col);
    }

    private Value callRegex(String name, List<Value> args, int line, int col) {
        if ("valid".equals(name)) {
            if (args.size() != 1) {
                throw runtime("regex.valid takes 1 argument", line, col);
            }
            try {
                Pattern.compile(needRegexString(args, 0, name, line, col));
                return Value.makeBool(true);
            } catch (PatternSyntaxException ignored) {
                return Value.makeBool(false);
            }
        }
        if ("is_match".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.is_match takes 2 arguments", line, col);
            }
            Pattern re = compileRegex(needRegexString(args, 0, name, line, col), line, col);
            return Value.makeBool(re.matcher(needRegexString(args, 1, name, line, col)).find());
        }
        if ("find".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.find takes 2 arguments", line, col);
            }
            Matcher m = compileRegex(needRegexString(args, 0, name, line, col), line, col)
                    .matcher(needRegexString(args, 1, name, line, col));
            if (!m.find()) {
                return Value.makeInt(-1);
            }
            return Value.makeInt(m.start());
        }
        if ("find_match".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.find_match takes 2 arguments", line, col);
            }
            Matcher m = compileRegex(needRegexString(args, 0, name, line, col), line, col)
                    .matcher(needRegexString(args, 1, name, line, col));
            if (!m.find()) {
                return Value.makeString("");
            }
            return Value.makeString(m.group());
        }
        if ("captures".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.captures takes 2 arguments", line, col);
            }
            Matcher m = compileRegex(needRegexString(args, 0, name, line, col), line, col)
                    .matcher(needRegexString(args, 1, name, line, col));
            if (!m.find()) {
                return Value.makeArray(List.of());
            }
            List<Value> parts = new ArrayList<>(m.groupCount() + 1);
            for (int n = 0; n <= m.groupCount(); n++) {
                String g = m.group(n);
                parts.add(Value.makeString(g == null ? "" : g));
            }
            return Value.makeArray(parts);
        }
        if ("findall".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.findall takes 2 arguments", line, col);
            }
            Matcher m = compileRegex(needRegexString(args, 0, name, line, col), line, col)
                    .matcher(needRegexString(args, 1, name, line, col));
            List<Value> parts = new ArrayList<>();
            while (m.find()) {
                parts.add(Value.makeString(m.group()));
            }
            return Value.makeArray(parts);
        }
        if ("replace".equals(name)) {
            if (args.size() != 3) {
                throw runtime("regex.replace takes 3 arguments", line, col);
            }
            Pattern re = compileRegex(needRegexString(args, 0, name, line, col), line, col);
            String text = needRegexString(args, 1, name, line, col);
            String with = needRegexString(args, 2, name, line, col);
            try {
                return Value.makeString(re.matcher(text).replaceAll(with));
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                throw runtime("invalid regex replacement", line, col);
            }
        }
        if ("split".equals(name)) {
            if (args.size() != 2) {
                throw runtime("regex.split takes 2 arguments", line, col);
            }
            Matcher m = compileRegex(needRegexString(args, 0, name, line, col), line, col)
                    .matcher(needRegexString(args, 1, name, line, col));
            String text = needRegexString(args, 1, name, line, col);
            List<Value> parts = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                parts.add(Value.makeString(text.substring(last, m.start())));
                last = m.end();
            }
            parts.add(Value.makeString(text.substring(last)));
            if (parts.isEmpty()) {
                parts.add(Value.makeString(text));
            }
            return Value.makeArray(parts);
        }
        throw runtime("unknown function __regex." + name, line, col);
    }

    private String needRegexString(List<Value> args, int i, String name, int line, int col) {
        if (args.get(i).kind != Value.Kind.String) {
            throw runtime("__regex." + name + " expects String", line, col);
        }
        return args.get(i).s;
    }

    private Pattern compileRegex(String pattern, int line, int col) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            String detail = ex.getDescription() == null ? ex.getMessage() : ex.getDescription();
            throw runtime("invalid regex: " + detail, line, col);
        }
    }

    private String needIoString(List<Value> args, int i, String name, int line, int col) {
        if (args.get(i).kind != Value.Kind.String) {
            throw runtime("__io." + name + " expects String", line, col);
        }
        return args.get(i).s;
    }

    Path sandboxPath(String raw, int line, int col) {
        if (raw == null || raw.isEmpty()) {
            throw runtime("invalid path '" + raw + "'", line, col);
        }
        Path root = sandboxRoot == null
                ? Path.of("").toAbsolutePath().normalize()
                : sandboxRoot.toAbsolutePath().normalize();
        Path req = Path.of(raw);
        if (!req.isAbsolute()) {
            req = root.resolve(req);
        }
        Path canon = req.toAbsolutePath().normalize();
        Path rel = root.relativize(canon);
        if (rel.startsWith("..")) {
            throw runtime("path outside sandbox '" + raw + "'", line, col);
        }
        return canon;
    }

    private ThrowEscape ioThrow(String msg, int line, int col) {
        return new ThrowEscape(Value.makeString(msg), line, col);
    }

    Value applyBinop(String op, Value a, Value b, int line, int col) {
        if ("+".equals(op) && a.kind == Value.Kind.String && b.kind == Value.Kind.String) {
            return Value.makeString(a.s + b.s);
        }
        if ("==".equals(op)) {
            return Value.makeBool(a.equalsValue(b));
        }
        if ("!=".equals(op)) {
            return Value.makeBool(!a.equalsValue(b));
        }
        if (!a.isNumeric() || !b.isNumeric()) {
            throw runtime("operands must be numbers", line, col);
        }
        boolean bothInt = a.kind == Value.Kind.Int && b.kind == Value.Kind.Int;
        try {
            return switch (op) {
                case "+" -> bothInt ? Value.makeInt(Math.addExact(a.i, b.i)) : Value.makeFloat(a.asF64() + b.asF64());
                case "-" -> bothInt ? Value.makeInt(Math.subtractExact(a.i, b.i)) : Value.makeFloat(a.asF64() - b.asF64());
                case "*" -> bothInt ? Value.makeInt(Math.multiplyExact(a.i, b.i)) : Value.makeFloat(a.asF64() * b.asF64());
                case "/" -> {
                    if (bothInt) {
                        if (b.i == 0) {
                            throw runtime("division by zero", line, col);
                        }
                        if (a.i == Long.MIN_VALUE && b.i == -1) {
                            throw runtime("integer overflow", line, col);
                        }
                        yield Value.makeInt(a.i / b.i);
                    }
                    if (b.asF64() == 0.0) {
                        throw runtime("division by zero", line, col);
                    }
                    yield Value.makeFloat(a.asF64() / b.asF64());
                }
                case "%" -> {
                    if (bothInt) {
                        if (b.i == 0) {
                            throw runtime("modulo by zero", line, col);
                        }
                        if (a.i == Long.MIN_VALUE && b.i == -1) {
                            throw runtime("integer overflow", line, col);
                        }
                        yield Value.makeInt(a.i % b.i);
                    }
                    if (b.asF64() == 0.0) {
                        throw runtime("modulo by zero", line, col);
                    }
                    yield Value.makeFloat(a.asF64() % b.asF64());
                }
                case "<" -> Value.makeBool(bothInt ? a.i < b.i : a.asF64() < b.asF64());
                case ">" -> Value.makeBool(bothInt ? a.i > b.i : a.asF64() > b.asF64());
                case "<=" -> Value.makeBool(bothInt ? a.i <= b.i : a.asF64() <= b.asF64());
                case ">=" -> Value.makeBool(bothInt ? a.i >= b.i : a.asF64() >= b.asF64());
                default -> throw runtime("unknown operator", line, col);
            };
        } catch (ArithmeticException ex) {
            throw runtime("integer overflow", line, col);
        }
    }

    private Value resolveValue(String name, int line, int col) {
        Value.Binding local = findLocalBinding(name);
        if (local != null) {
            return local.value;
        }
        Value field = fieldOnSelf(name);
        if (field != null) {
            return field;
        }
        Value.Binding self = findLocalBinding("self");
        if (self != null && self.value.kind == Value.Kind.Struct && self.value.rec != null
                && checker.lookupTypeSignal(self.value.rec.name, name) != null) {
            return Value.makeSignalRef(name, self.value.rec);
        }
        Value.Binding global = findGlobalBinding(name);
        if (global != null) {
            return global.value;
        }
        if (fns.containsKey(name)) {
            return Value.makeFnRef(name);
        }
        EnumDecl en = checker.findEnum(name);
        if (en != null) {
            return Value.makeEnumType(en.name);
        }
        throw runtime("undefined variable '" + name + "'", line, col);
    }

    private Value.Binding findLocalBinding(String name) {
        return env.getLast().get(name);
    }

    private Value.Binding findGlobalBinding(String name) {
        return env.getFirst().get(name);
    }

    private Value.Binding findVar(String name) {
        Value.Binding local = findLocalBinding(name);
        if (local != null) {
            return local;
        }
        return findGlobalBinding(name);
    }

    private Value.StructData selfRec() {
        Value.Binding self = findLocalBinding("self");
        if (self == null || self.value.kind != Value.Kind.Struct || self.value.rec == null) {
            return null;
        }
        return self.value.rec;
    }

    private Value fieldOnSelf(String name) {
        Value.StructData rec = selfRec();
        if (rec == null) {
            return null;
        }
        return rec.fields.get(name);
    }

    private boolean isSignalOnSelf(String name) {
        Value.Binding self = findLocalBinding("self");
        return self != null && self.value.kind == Value.Kind.Struct && self.value.rec != null
                && checker.lookupTypeSignal(self.value.rec.name, name) != null;
    }

    private void initInstanceSignals(Value.StructData data) {
        String current = data.name;
        Set<String> seen = new HashSet<>();
        while (current != null && !current.isEmpty()) {
            if (!seen.add(current)) {
                break;
            }
            Map<String, Integer> slot = checker.typeSignals.get(current);
            if (slot != null) {
                for (String signal : slot.keySet()) {
                    data.listeners.computeIfAbsent(signal, k -> new ArrayList<>());
                }
            }
            String parent = checker.classParents.get(current);
            if (parent == null) {
                break;
            }
            current = Types.typeHead(parent);
        }
    }

    Value globalSignal(String name, int line, int col) {
        if (!checker.signalArity.containsKey(name)) {
            throw runtime("undefined signal '" + name + "'", line, col);
        }
        return Value.makeSignalRef(name, null);
    }

    private Value callSignal(String signal, String name, List<Value> args, int line, int col) {
        Integer arity = checker.signalArity.get(signal);
        if (arity == null) {
            throw runtime("undefined signal '" + signal + "'", line, col);
        }
        return callSignalList(signal, arity, listeners.computeIfAbsent(signal, k -> new ArrayList<>()), null, name, args, line, col);
    }

    private Value dispatchSignal(Value sig, String name, List<Value> args, int line, int col) {
        if (sig.kind != Value.Kind.SignalRef) {
            throw runtime("can only call signal methods on a signal", line, col);
        }
        if (sig.rec == null) {
            if (!checker.signalArity.containsKey(sig.s)) {
                throw runtime("undefined signal '" + sig.s + "'", line, col);
            }
            return callSignalList(sig.s, checker.signalArity.get(sig.s),
                    listeners.computeIfAbsent(sig.s, k -> new ArrayList<>()), null, name, args, line, col);
        }
        Integer arity = checker.lookupTypeSignal(sig.rec.name, sig.s);
        if (arity == null) {
            throw runtime("struct " + sig.rec.name + " has no signal '" + sig.s + "'", line, col);
        }
        return callSignalList(sig.s, arity, sig.rec.listeners.computeIfAbsent(sig.s, k -> new ArrayList<>()),
                sig.rec, name, args, line, col);
    }

    private Value callSignalList(String signal, int arity, List<Value> list, Value.StructData rec,
                                 String name, List<Value> args, int line, int col) {
        if ("connect".equals(name)) {
            if (args.size() != 1) {
                throw runtime("signal '" + signal + "' connect takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.FnRef) {
                throw runtime("signal '" + signal + "' connect expects a function", line, col);
            }
            if (signalFnArity(args.getFirst(), signal, line, col) != arity) {
                throw runtime("signal '" + signal + "' connect expected " + arity + " parameter(s)", line, col);
            }
            boolean found = false;
            for (Value fn : list) {
                if (sameFn(fn, args.getFirst())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                list.add(args.getFirst());
            }
            return Value.makeVoid();
        }
        if ("disconnect".equals(name)) {
            if (args.size() != 1) {
                throw runtime("signal '" + signal + "' disconnect takes 1 argument", line, col);
            }
            if (args.getFirst().kind != Value.Kind.FnRef) {
                throw runtime("signal '" + signal + "' disconnect expects a function", line, col);
            }
            if (args.getFirst().clo == null && fns.get(args.getFirst().s) == null && checker.findLocalFn(args.getFirst().s) == null) {
                throw runtime("undefined function '" + args.getFirst().s + "'", line, col);
            }
            list.removeIf(fn -> sameFn(fn, args.getFirst()));
            return Value.makeVoid();
        }
        if ("emit".equals(name) || "emit_deferred".equals(name)) {
            if (args.size() != arity) {
                throw runtime("signal '" + signal + "' expected " + arity + " argument(s), got " + args.size(), line, col);
            }
            if ("emit_deferred".equals(name)) {
                DeferredEmit item = new DeferredEmit();
                item.signal = signal;
                item.rec = rec;
                item.args = new ArrayList<>(args);
                item.line = line;
                item.col = col;
                deferred.add(item);
                return Value.makeVoid();
            }
            if (syncEmitDepth >= MAX_SYNC_EMIT_DEPTH) {
                throw runtime("signal emit nested too deeply", line, col);
            }
            syncEmitDepth++;
            try {
                for (Value fn : new ArrayList<>(list)) {
                    callFnValue(fn, args, line, col);
                }
            } finally {
                syncEmitDepth--;
            }
            return Value.makeVoid();
        }
        throw runtime("signal '" + signal + "' has no method '" + name + "'", line, col);
    }

    private void flushDeferred() {
        int waves = 0;
        while (!deferred.isEmpty()) {
            if (++waves > 64) {
                DeferredEmit first = deferred.getFirst();
                throw runtime("deferred emit nested too deeply", first.line, first.col);
            }
            List<DeferredEmit> batch = new ArrayList<>(deferred);
            deferred.clear();
            for (DeferredEmit item : batch) {
                List<Value> list;
                if (item.rec != null) {
                    Integer arity = checker.lookupTypeSignal(item.rec.name, item.signal);
                    if (arity == null) {
                        throw runtime("struct " + item.rec.name + " has no signal '" + item.signal + "'", item.line, item.col);
                    }
                    list = item.rec.listeners.getOrDefault(item.signal, List.of());
                } else {
                    list = listeners.getOrDefault(item.signal, List.of());
                }
                for (Value fn : new ArrayList<>(list)) {
                    callFnValue(fn, item.args, item.line, item.col);
                }
            }
        }
    }

    private int signalFnArity(Value fn, String signal, int line, int col) {
        if (fn.kind != Value.Kind.FnRef) {
            throw runtime("signal '" + signal + "' connect expects a function", line, col);
        }
        if (fn.clo != null) {
            if (fn.clo.fn == null) {
                throw runtime("invalid closure", line, col);
            }
            return fn.clo.fn.params.size();
        }
        FnDecl decl = fns.get(fn.s);
        if (decl == null) {
            decl = checker.findLocalFn(fn.s);
        }
        if (decl == null) {
            throw runtime("undefined function '" + fn.s + "'", line, col);
        }
        return decl.params.size();
    }

    private static boolean sameFn(Value a, Value b) {
        if (a.kind != Value.Kind.FnRef || b.kind != Value.Kind.FnRef) {
            return false;
        }
        if (a.clo != null || b.clo != null) {
            return a.clo == b.clo;
        }
        return Objects.equals(a.s, b.s);
    }

    private Value selfObject(int line, int col) {
        Value.Binding self = findVar("self");
        if (self == null || self.value.kind != Value.Kind.Struct || self.value.rec == null) {
            throw runtime("super requires self", line, col);
        }
        return self.value;
    }

    private Value callSuper(String name, List<Value> args, int line, int col) {
        return callSuperFrom(selfObject(line, col), superType, name, args, line, col);
    }

    Value callSuperFrom(Value self, String startType, String name, List<Value> args, int line, int col) {
        if (startType == null || startType.isEmpty()) {
            throw runtime("super is only valid in a method of a class that extends another", line, col);
        }
        return invokeTypeMethod(self, startType, name, args, line, col);
    }

    private Value callTypeMethod(Value obj, String name, List<Value> args, int line, int col) {
        return invokeTypeMethod(obj, obj.rec.name, name, args, line, col);
    }

    private Value callUfcs(FnDecl fn, Value obj, List<Value> args, int line, int col) {
        List<Value> all = new ArrayList<>(args.size() + 1);
        all.add(obj);
        all.addAll(args);
        return callUser(fn, all, line, col);
    }

    private static String typeNameOf(Value obj) {
        return switch (obj.kind) {
            case Struct -> obj.rec == null ? "" : obj.rec.name;
            case Enum, EnumType -> obj.s;
            case Array -> "Array";
            case Map -> "Map";
            case String -> "String";
            case Future -> "Future";
            case Int -> "Int";
            case Float -> "Float";
            case Bool -> "Bool";
            default -> "";
        };
    }

    private Value constructEnum(EnumDecl en, String variant, List<Value> args, int line, int col) {
        EnumVariant found = null;
        for (EnumVariant v : en.variants) {
            if (v.name.equals(variant)) {
                found = v;
                break;
            }
        }
        if (found == null) {
            throw runtime("enum " + en.name + " has no variant '" + variant + "'", line, col);
        }
        if (args.size() != found.arity) {
            throw runtime(en.name + "." + variant + " takes " + found.arity + " argument(s)", line, col);
        }
        return Value.makeEnum(en.name, variant, args);
    }

    private Value invokeTypeMethod(Value obj, String startType, String name, List<Value> args, int line, int col) {
        Checker.MethodHit found = checker.lookupMethod(startType, name);
        if (found.fn == null) {
            FnDecl ufcs = checker.findUfcs(name, startType);
            if (ufcs != null) {
                return callUfcs(ufcs, obj, args, line, col);
            }
            throw runtime("struct " + startType + " has no method '" + name + "'", line, col);
        }
        FnDecl fn = found.fn;
        if (fn.isAbstract) {
            throw runtime("cannot call abstract method '" + name + "'", line, col);
        }
        if (args.size() != fn.params.size() - 1) {
            throw runtime(startType + "." + name + " takes " + (fn.params.size() - 1) + " argument(s)", line, col);
        }
        String prev = superType;
        String parent = checker.classParents.get(found.definedOn);
        superType = parent == null ? "" : Types.typeHead(parent);
        try {
            List<Value> all = new ArrayList<>(args.size() + 1);
            all.add(obj);
            all.addAll(args);
            return callUser(fn, all, line, col);
        } finally {
            superType = prev;
        }
    }

    private Value callValueMethod(Value obj, String name, List<Value> args, int line, int col) {
        if (obj.kind == Value.Kind.Array) {
            if ("len".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("Array.len takes 0 arguments", line, col);
                }
                return Value.makeInt(obj.items == null ? 0 : obj.items.size());
            }
            if ("push".equals(name)) {
                if (args.size() != 1) {
                    throw runtime("Array.push takes 1 argument", line, col);
                }
                if (obj.items == null) {
                    throw runtime("cannot push on array", line, col);
                }
                obj.items.add(args.getFirst());
                return Value.makeVoid();
            }
            if ("pop".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("Array.pop takes 0 arguments", line, col);
                }
                if (obj.items == null || obj.items.isEmpty()) {
                    throw runtime("pop from empty array", line, col);
                }
                return obj.items.removeLast();
            }
            FnDecl ufcs = checker.findUfcs(name, "Array");
            if (ufcs != null) {
                return callUfcs(ufcs, obj, args, line, col);
            }
            throw runtime("Array has no method '" + name + "'", line, col);
        }
        if (obj.kind == Value.Kind.Map) {
            if (obj.dict == null) {
                throw runtime("cannot call method '" + name + "' on " + obj.toPrintString(), line, col);
            }
            if ("len".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("Map.len takes 0 arguments", line, col);
                }
                return Value.makeInt(obj.dict.fields.size());
            }
            if ("has".equals(name)) {
                if (args.size() != 1) {
                    throw runtime("Map.has takes 1 argument", line, col);
                }
                return Value.makeBool(obj.dict.fields.containsKey(mapKey(args.getFirst(), line, col)));
            }
            if ("keys".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("Map.keys takes 0 arguments", line, col);
                }
                List<Value> keys = new ArrayList<>();
                for (String k : obj.dict.order) {
                    keys.add(Value.makeString(k));
                }
                return Value.makeArray(keys);
            }
            if ("remove".equals(name)) {
                if (args.size() != 1) {
                    throw runtime("Map.remove takes 1 argument", line, col);
                }
                String key = mapKey(args.getFirst(), line, col);
                Value removed = obj.dict.fields.remove(key);
                if (removed == null) {
                    throw runtime("key '" + key + "' not found", line, col);
                }
                obj.dict.order.remove(key);
                return removed;
            }
            if ("insert".equals(name)) {
                if (args.size() != 2) {
                    throw runtime("Map.insert takes 2 arguments", line, col);
                }
                String key = mapKey(args.getFirst(), line, col);
                if (!obj.dict.fields.containsKey(key)) {
                    obj.dict.order.add(key);
                }
                obj.dict.fields.put(key, args.get(1));
                return Value.makeVoid();
            }
            FnDecl ufcs = checker.findUfcs(name, "Map");
            if (ufcs != null) {
                return callUfcs(ufcs, obj, args, line, col);
            }
            throw runtime("Map has no method '" + name + "'", line, col);
        }
        if (obj.kind == Value.Kind.String) {
            if ("len".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("String.len takes 0 arguments", line, col);
                }
                return Value.makeInt(obj.s.length());
            }
            FnDecl ufcs = checker.findUfcs(name, "String");
            if (ufcs != null) {
                return callUfcs(ufcs, obj, args, line, col);
            }
            throw runtime("String has no method '" + name + "'", line, col);
        }
        if (obj.kind == Value.Kind.Future) {
            if ("cancel".equals(name)) {
                if (!args.isEmpty()) {
                    throw runtime("Future.cancel takes 0 arguments", line, col);
                }
                cancelFuture(obj.fut, line, col);
                return Value.makeVoid();
            }
            throw runtime("Future has no method '" + name + "'", line, col);
        }
        FnDecl ufcs = checker.findUfcs(name, typeNameOf(obj));
        if (ufcs != null) {
            return callUfcs(ufcs, obj, args, line, col);
        }
        throw runtime("cannot call method '" + name + "' on " + obj.toPrintString(), line, col);
    }

    private Value readValueMember(Value obj, String name, int line, int col) {
        if (obj.kind == Value.Kind.Array) {
            if ("len".equals(name)) {
                return Value.makeInt(obj.items == null ? 0 : obj.items.size());
            }
            throw runtime("Array has no member '" + name + "'", line, col);
        }
        if (obj.kind == Value.Kind.Map) {
            if ("len".equals(name)) {
                return Value.makeInt(obj.dict == null ? 0 : obj.dict.fields.size());
            }
            throw runtime("Map has no member '" + name + "'", line, col);
        }
        if (obj.kind == Value.Kind.String) {
            if ("len".equals(name)) {
                return Value.makeInt(obj.s.length());
            }
            throw runtime("String has no member '" + name + "'", line, col);
        }
        throw runtime("cannot read field on " + obj.toPrintString(), line, col);
    }

    private Value applyAssignOp(String op, Value lhs, Value rhs, int line, int col) {
        if (op != null && !op.isEmpty() && !op.equals("=")) {
            return applyBinop(op.substring(0, op.length() - 1), lhs, rhs, line, col);
        }
        return rhs;
    }

    static Value zeroOfType(String ty) {
        String head = Types.typeHead(ty);
        return switch (head) {
            case "Int" -> Value.makeInt(0);
            case "Float" -> Value.makeFloat(0);
            case "String", "Str" -> Value.makeString("");
            case "Bool" -> Value.makeBool(false);
            case "Array" -> Value.makeArray(List.of());
            case "Map" -> Value.makeMap(new Value.MapData());
            default -> Value.makeVoid();
        };
    }

    private String mapKey(Value v, int line, int col) {
        if (v.kind == Value.Kind.String) {
            return v.s;
        }
        if (v.kind == Value.Kind.Int) {
            return Long.toString(v.i);
        }
        if (v.kind == Value.Kind.Bool) {
            return Boolean.toString(v.b);
        }
        throw runtime("map key must be String", line, col);
    }

    Value indexGet(Value obj, Value idx, int line, int col) {
        if (obj.kind == Value.Kind.Map) {
            if (idx.kind != Value.Kind.String) {
                throw runtime("map key must be String", line, col);
            }
            if (obj.dict == null) {
                throw runtime("key '" + idx.s + "' not found", line, col);
            }
            Value found = obj.dict.fields.get(idx.s);
            if (found == null) {
                throw runtime("key '" + idx.s + "' not found", line, col);
            }
            return found;
        }
        if (idx.kind != Value.Kind.Int) {
            throw runtime("index must be Int", line, col);
        }
        if (idx.i < 0) {
            throw runtime("index " + idx.i + " out of bounds", line, col);
        }
        int i = (int) idx.i;
        if (obj.kind == Value.Kind.Array) {
            if (obj.items == null || i >= obj.items.size()) {
                throw runtime("index " + i + " out of bounds", line, col);
            }
            return obj.items.get(i);
        }
        if (obj.kind == Value.Kind.String) {
            if (i >= obj.s.length()) {
                throw runtime("index " + i + " out of bounds", line, col);
            }
            return Value.makeString(String.valueOf(obj.s.charAt(i)));
        }
        throw runtime("cannot index " + obj.toPrintString(), line, col);
    }

    void indexSet(Value obj, Value idx, Value value, int line, int col) {
        if (obj.kind == Value.Kind.Map) {
            if (obj.dict == null) {
                throw runtime("cannot assign to " + obj.toPrintString(), line, col);
            }
            if (idx.kind != Value.Kind.String) {
                throw runtime("map key must be String", line, col);
            }
            if (!obj.dict.fields.containsKey(idx.s)) {
                obj.dict.order.add(idx.s);
            }
            obj.dict.fields.put(idx.s, value);
            return;
        }
        if (obj.kind != Value.Kind.Array || obj.items == null) {
            throw runtime("cannot assign to " + obj.toPrintString(), line, col);
        }
        if (idx.kind != Value.Kind.Int || idx.i < 0 || idx.i >= obj.items.size()) {
            throw runtime("index " + (idx.kind == Value.Kind.Int ? idx.i : idx.toPrintString()) + " out of bounds", line, col);
        }
        obj.items.set((int) idx.i, value);
    }

    List<Value> iterItems(Value iter, int line, int col) {
        if (iter.kind == Value.Kind.Array) {
            return iter.items == null ? List.of() : new ArrayList<>(iter.items);
        }
        if (iter.kind == Value.Kind.Map) {
            List<Value> keys = new ArrayList<>();
            if (iter.dict == null) {
                return keys;
            }
            for (String k : iter.dict.order) {
                keys.add(Value.makeString(k));
            }
            return keys;
        }
        if (iter.kind == Value.Kind.String) {
            List<Value> chars = new ArrayList<>();
            for (int i = 0; i < iter.s.length(); i++) {
                chars.add(Value.makeString(String.valueOf(iter.s.charAt(i))));
            }
            return chars;
        }
        if (iter.kind == Value.Kind.Int) {
            if (iter.i <= 0) {
                return List.of();
            }
            if (iter.i > MAX_RANGE) {
                throw runtime("range too large", line, col);
            }
            List<Value> nums = new ArrayList<>((int) iter.i);
            for (long n = 0; n < iter.i; n++) {
                nums.add(Value.makeInt(n));
            }
            return nums;
        }
        if (iter.kind == Value.Kind.Range) {
            List<Value> nums = new ArrayList<>();
            if (iter.inclusive) {
                for (long n = iter.i; n <= iter.rangeEnd; n++) {
                    if (nums.size() >= MAX_RANGE) {
                        throw runtime("range too large", line, col);
                    }
                    nums.add(Value.makeInt(n));
                    if (n == Long.MAX_VALUE) {
                        break;
                    }
                }
            } else {
                for (long n = iter.i; n < iter.rangeEnd; n++) {
                    if (nums.size() >= MAX_RANGE) {
                        throw runtime("range too large", line, col);
                    }
                    nums.add(Value.makeInt(n));
                }
            }
            return nums;
        }
        throw runtime("cannot iterate over " + iter.toPrintString(), line, col);
    }

    void syncBcLocals(Bytecode.Fn fn, Value[] regs) {
        Map<String, Value.Binding> frame = env.getLast();
        for (int i = 0; i < fn.locals.size(); i++) {
            String name = fn.locals.get(i);
            if (name == null || name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            boolean isConst = i < fn.localConst.size() && Boolean.TRUE.equals(fn.localConst.get(i));
            frame.put(name, new Value.Binding(regs[i], isConst));
        }
    }

    void loadBcLocals(Bytecode.Fn fn, Value[] regs) {
        Map<String, Value.Binding> frame = env.getLast();
        for (int i = 0; i < fn.locals.size(); i++) {
            String name = fn.locals.get(i);
            if (name == null || name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            Value.Binding b = frame.get(name);
            if (b != null) {
                regs[i] = b.value;
            }
        }
    }

    void debugCheck(Stmt stmt) {
        if (stmt.kind == Stmt.Kind.Comment) {
            return;
        }
        debugCheckAt(stmt.line, stmt.col);
    }

    void debugCheckAt(int line, int col) {
        if (!debug.enabled) {
            return;
        }
        if (debug.abort) {
            throw new DebugAbort();
        }
        if (debug.stack.isEmpty()) {
            return;
        }
        DebugFrame top = debug.stack.getLast();
        if (line > 0) {
            top.line = line;
        }
        if (col > 0) {
            top.col = col;
        }
        String path = debugNormPath(top.path.isEmpty() ? file : top.path);
        boolean stop = false;
        String reason = "";
        List<DebugBreakpoint> bps = debug.breakpoints.get(path);
        if (bps != null) {
            for (DebugBreakpoint bp : bps) {
                if (bp.line != top.line) {
                    continue;
                }
                bp.hits++;
                if (!debugHitMatches(bp)) {
                    continue;
                }
                if (bp.condition.isEmpty()) {
                    stop = true;
                    reason = "breakpoint";
                    break;
                }
                String[] err = {""};
                Value cond = debugEval(bp.condition, err);
                if (err[0].isEmpty() && cond.truthy()) {
                    stop = true;
                    reason = "breakpoint";
                    break;
                }
            }
        }
        if (!stop && debug.stopOnEntry && !debug.entrySeen) {
            debug.entrySeen = true;
            stop = true;
            reason = "entry";
        }
        if (!stop) {
            switch (debug.mode) {
                case Next -> {
                    if (debug.stack.size() <= debug.stepDepth) {
                        stop = true;
                        reason = "step";
                    }
                }
                case StepIn -> {
                    stop = true;
                    reason = "step";
                }
                case StepOut -> {
                    if (debug.stack.size() < debug.stepDepth) {
                        stop = true;
                        reason = "step";
                    }
                }
                case Run -> {
                }
            }
        }
        if (!stop) {
            return;
        }
        debug.stopPath = top.path.isEmpty() ? file : top.path;
        debug.stopLine = top.line;
        debug.stopCol = top.col;
        if (debug.pauseAndWait != null) {
            debug.pauseAndWait.accept(reason);
        }
        if (debug.abort) {
            throw new DebugAbort();
        }
    }

    void debugPauseAndWait(String reason) {
        synchronized (pauseLock) {
            debugWaiting = true;
            if (debug.onPause != null) {
                debug.onPause.accept(reason);
            }
            while (debugWaiting && !debug.abort) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    debug.abort = true;
                }
            }
        }
    }

    void debugResume(DebugState.Mode mode) {
        synchronized (pauseLock) {
            debug.mode = mode;
            if (mode == DebugState.Mode.Next || mode == DebugState.Mode.StepOut) {
                debug.stepDepth = debug.stack.size();
            }
            debugWaiting = false;
            pauseLock.notifyAll();
        }
    }

    void debugAbort() {
        debug.abort = true;
        synchronized (pauseLock) {
            debugWaiting = false;
            pauseLock.notifyAll();
        }
    }

    static String debugNormPath(String p) {
        if (p == null) {
            return "";
        }
        String s = p.replace('/', '\\');
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase(Locale.ROOT).contains("win")) {
            s = s.toLowerCase(Locale.ROOT);
        }
        return s;
    }

    static boolean parseDebugHit(String s, DebugBreakpoint bp) {
        bp.hitCondition = s == null ? "" : s;
        bp.hitOp = DebugBreakpoint.HitOp.Always;
        bp.hitN = 0;
        if (s == null) {
            return true;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return true;
        }
        DebugBreakpoint.HitOp op = DebugBreakpoint.HitOp.Eq;
        int i = 0;
        if (t.startsWith(">=")) {
            op = DebugBreakpoint.HitOp.Ge;
            i = 2;
        } else if (t.startsWith("<=")) {
            op = DebugBreakpoint.HitOp.Le;
            i = 2;
        } else if (t.startsWith("==")) {
            op = DebugBreakpoint.HitOp.Eq;
            i = 2;
        } else if (t.charAt(0) == '>') {
            op = DebugBreakpoint.HitOp.Gt;
            i = 1;
        } else if (t.charAt(0) == '<') {
            op = DebugBreakpoint.HitOp.Lt;
            i = 1;
        } else if (t.charAt(0) == '=') {
            op = DebugBreakpoint.HitOp.Eq;
            i = 1;
        } else if (t.charAt(0) == '%') {
            op = DebugBreakpoint.HitOp.Mod;
            i = 1;
        }
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        if (i >= t.length() || !Character.isDigit(t.charAt(i))) {
            bp.hitOp = DebugBreakpoint.HitOp.Never;
            return false;
        }
        int n = 0;
        int start = i;
        while (i < t.length() && Character.isDigit(t.charAt(i))) {
            i++;
        }
        try {
            n = Integer.parseInt(t.substring(start, i));
        } catch (NumberFormatException ex) {
            bp.hitOp = DebugBreakpoint.HitOp.Never;
            return false;
        }
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        if (i != t.length() || n < 0) {
            bp.hitOp = DebugBreakpoint.HitOp.Never;
            return false;
        }
        if (op == DebugBreakpoint.HitOp.Mod && n == 0) {
            bp.hitOp = DebugBreakpoint.HitOp.Never;
            return false;
        }
        bp.hitOp = op;
        bp.hitN = n;
        return true;
    }

    static boolean debugHitMatches(DebugBreakpoint bp) {
        return switch (bp.hitOp) {
            case Always -> true;
            case Eq -> bp.hits == bp.hitN;
            case Gt -> bp.hits > bp.hitN;
            case Ge -> bp.hits >= bp.hitN;
            case Lt -> bp.hits < bp.hitN;
            case Le -> bp.hits <= bp.hitN;
            case Mod -> bp.hitN > 0 && bp.hits % bp.hitN == 0;
            case Never -> false;
        };
    }

    Value debugEval(String source, String[] err) {
        err[0] = "";
        List<Diagnostic> diags = new ArrayList<>();
        Expr e = Parser.parseExprSource(source, "<watch>", diags);
        if (!diags.isEmpty()) {
            err[0] = diags.getFirst().message;
            return Value.makeVoid();
        }
        try {
            return eval(e);
        } catch (ThrowEscape ex) {
            err[0] = "throw: " + ex.value.toPrintString();
            return Value.makeVoid();
        } catch (RuntimeException ex) {
            err[0] = ex.getMessage() == null ? "runtime error" : ex.getMessage();
            return Value.makeVoid();
        }
    }

    boolean debugSetVariable(int envIndex, String name, String source, String[] err) {
        err[0] = "";
        if (envIndex < 0 || envIndex >= env.size()) {
            err[0] = "invalid scope";
            return false;
        }
        Value.Binding slot = env.get(envIndex).get(name);
        if (slot == null) {
            err[0] = "undefined variable '" + name + "'";
            return false;
        }
        if (slot.isConst) {
            err[0] = "cannot assign to const '" + name + "'";
            return false;
        }
        Value v = debugEval(source, err);
        if (!err[0].isEmpty()) {
            return false;
        }
        slot.value = v;
        return true;
    }

    List<Map.Entry<String, Value.Binding>> localsAt(int envIndex) {
        if (envIndex < 0 || envIndex >= env.size()) {
            return List.of();
        }
        return new ArrayList<>(env.get(envIndex).entrySet());
    }

    RuntimeException runtime(String msg, int line, int col) {
        return new RuntimeException(Diagnostic.locatedError("runtime error", file, line, col, msg));
    }

    static final class DebugFrame {
        String name = "";
        String path = "";
        int line = 1;
        int col = 1;
        int envIndex = 0;
    }

    static final class DebugBreakpoint {
        enum HitOp { Always, Eq, Gt, Ge, Lt, Le, Mod, Never }

        int line = 0;
        String condition = "";
        String hitCondition = "";
        int hits = 0;
        HitOp hitOp = HitOp.Always;
        int hitN = 0;
    }

    static final class DebugState {
        enum Mode { Run, Next, StepIn, StepOut }

        boolean enabled = false;
        boolean stopOnEntry = false;
        boolean entrySeen = false;
        boolean abort = false;
        Mode mode = Mode.Run;
        int stepDepth = 0;
        final Map<String, List<DebugBreakpoint>> breakpoints = new LinkedHashMap<>();
        final List<DebugFrame> stack = new ArrayList<>();
        Consumer<String> pauseAndWait;
        Consumer<String> onPause;
        String stopPath = "";
        int stopLine = 1;
        int stopCol = 1;
    }

    static final class DebugAbort extends RuntimeException {
        DebugAbort() {
            super("debug session ended");
        }
    }

    static final class FrameJob {
        final long winId;
        final Value.FutureData fut;

        FrameJob(long winId, Value.FutureData fut) {
            this.winId = winId;
            this.fut = fut;
        }
    }

    private static final class TimerJob {
        final long deadlineMs;
        final Value.FutureData fut;

        TimerJob(long deadlineMs, Value.FutureData fut) {
            this.deadlineMs = deadlineMs;
            this.fut = fut;
        }
    }

    private static final class AllState {
        Value.FutureData out;
        List<Value> results;
        int remaining;
        boolean done;
    }

    private static final class RaceState {
        Value.FutureData out;
        boolean done;
    }

    static final class ThrowEscape extends RuntimeException {
        final Value value;
        final int line;
        final int col;

        ThrowEscape(Value value, int line, int col) {
            super(value == null ? "" : value.toPrintString());
            this.value = value == null ? Value.makeVoid() : value;
            this.line = line;
            this.col = col;
        }
    }

    private static final class DeferredEmit {
        String signal = "";
        Value.StructData rec;
        List<Value> args = new ArrayList<>();
        int line = 1;
        int col = 1;
    }

    private static final class Flow {
        enum Kind { Next, Return, Break, Continue, Throw }

        final Kind kind;
        final Value value;

        private Flow(Kind kind, Value value) {
            this.kind = kind;
            this.value = value;
        }

        static Flow next() {
            return new Flow(Kind.Next, Value.makeVoid());
        }

        static Flow ret(Value v) {
            return new Flow(Kind.Return, v);
        }

        static Flow brk() {
            return new Flow(Kind.Break, Value.makeVoid());
        }

        static Flow cont() {
            return new Flow(Kind.Continue, Value.makeVoid());
        }

        static Flow thr(Value v) {
            return new Flow(Kind.Throw, v);
        }
    }
}

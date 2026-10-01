package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class Vm {

    private Vm() {
    }

    static Value exec(Interp interp, Bytecode.Fn fn, List<Value> args) {
        return exec(interp, fn, args, null);
    }

    static Value exec(Interp interp, Bytecode.Fn fn, List<Value> args, Map<String, Value.Binding> caps) {
        Value[] regs = new Value[Math.max(fn.nregs, 1)];
        for (int i = 0; i < regs.length; i++) {
            regs[i] = Value.makeVoid();
        }
        if (caps != null) {
            for (int i = 0; i < fn.locals.size(); i++) {
                String name = fn.locals.get(i);
                if (name == null || name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                Value.Binding b = caps.get(name);
                if (b != null) {
                    regs[i] = b.value;
                }
            }
        }
        for (int i = 0; i < fn.arity; i++) {
            regs[i] = args.get(i);
        }
        List<Bytecode.Inst> insts = fn.insts;
        for (int pc = 0; pc < insts.size(); ) {
            Bytecode.Inst in = insts.get(pc);
            try {
                switch (in.op) {
                case LOAD_CONST -> {
                    regs[in.a] = fn.constants.get(in.b);
                    pc++;
                }
                case LOAD_FN -> {
                    regs[in.a] = Value.makeFnRef(in.s);
                    pc++;
                }
                case LOAD_ENUM -> {
                    regs[in.a] = Value.makeEnumType(in.s);
                    pc++;
                }
                case LOAD_SIGNAL -> {
                    regs[in.a] = interp.globalSignal(in.s, in.line, in.col);
                    pc++;
                }
                case MOVE -> {
                    regs[in.a] = regs[in.b];
                    pc++;
                }
                case BIN -> {
                    regs[in.a] = interp.applyBinop(in.s, regs[in.b], regs[in.c], in.line, in.col);
                    pc++;
                }
                case UNARY -> {
                    regs[in.a] = interp.applyUnary(in.s, regs[in.b], in.line, in.col);
                    pc++;
                }
                case TRUTHY -> {
                    regs[in.a] = Value.makeBool(regs[in.b].truthy());
                    pc++;
                }
                case JUMP -> pc = in.a;
                case JUMP_F -> {
                    if (!regs[in.a].truthy()) {
                        pc = in.b;
                    } else {
                        pc++;
                    }
                }
                case JUMP_T -> {
                    if (regs[in.a].truthy()) {
                        pc = in.b;
                    } else {
                        pc++;
                    }
                }
                case CALL -> {
                    List<Value> callArgs = new ArrayList<>(in.c);
                    callArgs.addAll(Arrays.asList(regs).subList(in.b, in.c + in.b));
                    regs[in.a] = interp.callQualified(in.module, in.s, callArgs, in.line, in.col);
                    pc++;
                }
                case CALL_VAL -> {
                    if (in.c < 1) {
                        throw interp.runtime("can only call a function", in.line, in.col);
                    }
                    Value callee = regs[in.b];
                    List<Value> valArgs = in.c <= 1
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b + 1, in.b + in.c));
                    regs[in.a] = interp.callFnValue(callee, valArgs, in.line, in.col);
                    pc++;
                }
                case ARRAY -> {
                    List<Value> elems = in.c == 0
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b, in.b + in.c));
                    regs[in.a] = Value.makeArray(elems);
                    pc++;
                }
                case RANGE -> {
                    Value start = regs[in.b];
                    Value end = regs[in.c];
                    if (start.kind != Value.Kind.Int) {
                        throw interp.runtime("range start must be Int", in.line, in.col);
                    }
                    if (end.kind != Value.Kind.Int) {
                        throw interp.runtime("range end must be Int", in.line, in.col);
                    }
                    regs[in.a] = Value.makeRange(start.i, end.i, "..=".equals(in.s));
                    pc++;
                }
                case INDEX_GET -> {
                    regs[in.a] = interp.indexGet(regs[in.b], regs[in.c], in.line, in.col);
                    pc++;
                }
                case INDEX_SET -> {
                    interp.indexSet(regs[in.a], regs[in.b], regs[in.c], in.line, in.col);
                    pc++;
                }
                case ITER_ITEMS -> {
                    regs[in.a] = Value.makeArray(interp.iterItems(regs[in.b], in.line, in.col));
                    pc++;
                }
                case LEN -> {
                    regs[in.a] = interp.call("", "len", List.of(regs[in.b]), in.line, in.col);
                    pc++;
                }
                case MAP -> {
                    List<Value> kids = in.c == 0
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b, in.b + in.c));
                    regs[in.a] = interp.mapLit(kids, in.line, in.col);
                    pc++;
                }
                case METHOD -> {
                    Value recv = regs[in.b];
                    List<Value> methodArgs = in.c <= 1
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b + 1, in.b + in.c));
                    regs[in.a] = interp.callMethod(recv, in.s, methodArgs, in.line, in.col);
                    pc++;
                }
                case SUPER -> {
                    Value recv = regs[in.b];
                    List<Value> superArgs = in.c <= 1
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b + 1, in.b + in.c));
                    regs[in.a] = interp.callSuperFrom(recv, in.module, in.s, superArgs, in.line, in.col);
                    pc++;
                }
                case STRUCT -> {
                    List<Value> fields = in.c == 0
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b, in.b + in.c));
                    regs[in.a] = interp.structLitFilled(in.s, fields, in.line, in.col);
                    pc++;
                }
                case MEMBER -> {
                    regs[in.a] = interp.readMember(regs[in.b], in.s, in.line, in.col);
                    pc++;
                }
                case FIELD_SET -> {
                    interp.setField(regs[in.a], in.s, regs[in.b], "=", in.line, in.col);
                    pc++;
                }
                case MATCH_LIT -> {
                    regs[in.a] = Value.makeBool(interp.matchLit(regs[in.b], regs[in.c]));
                    pc++;
                }
                case MATCH_VAR -> {
                    regs[in.a] = Value.makeBool(interp.matchVariant(regs[in.b], in.s, in.line, in.col));
                    pc++;
                }
                case PAYLOAD -> {
                    Value bound = interp.matchBind(regs[in.b], in.c, in.s, in.line, in.col);
                    if (bound != null) {
                        regs[in.a] = bound;
                    }
                    pc++;
                }
                case THROW -> {
                    throw new Interp.ThrowEscape(regs[in.a], in.line, in.col);
                }
                case CLOSURE -> {
                    List<Value> capVals = in.c == 0
                            ? List.of()
                            : new ArrayList<>(Arrays.asList(regs).subList(in.b, in.b + in.c));
                    regs[in.a] = interp.closure(in.proto, in.names, capVals, in.flags);
                    pc++;
                }
                case AWAIT -> {
                    Value fut = regs[in.b];
                    if (fut.kind != Value.Kind.Future || fut.fut == null) {
                        throw interp.runtime("can only await a Future", in.line, in.col);
                    }
                    regs[in.a] = interp.awaitFuture(fut.fut, in.line, in.col);
                    pc++;
                }
                case RET -> {
                    return regs[in.a];
                }
                case RET_VOID -> {
                    return Value.makeVoid();
                }
                case DEBUG -> {
                    if (interp.debug.enabled) {
                        interp.syncBcLocals(fn, regs);
                        interp.debugCheckAt(in.line, in.col);
                        interp.loadBcLocals(fn, regs);
                    }
                    pc++;
                }
                }
            } catch (Interp.ThrowEscape ex) {
                Integer next = unwind(fn, pc, regs, ex.value);
                if (next == null) {
                    throw ex;
                }
                pc = next;
            } catch (RuntimeException ex) {
                Integer next = unwind(fn, pc, regs, Value.makeString(ex.getMessage()));
                if (next == null) {
                    throw ex;
                }
                pc = next;
            }
        }
        return Value.makeVoid();
    }

    private static Integer unwind(Bytecode.Fn fn, int pc, Value[] regs, Value thrown) {
        for (Bytecode.Fn.Handler h : fn.handlers) {
            if (pc >= h.start && pc < h.end) {
                regs[h.dest] = thrown == null ? Value.makeVoid() : thrown;
                return h.handler;
            }
        }
        return null;
    }
}

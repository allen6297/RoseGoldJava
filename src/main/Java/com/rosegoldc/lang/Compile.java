package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Compile {

    private static final class Skip extends RuntimeException {
        Skip() {
            super(null, null, false, false);
        }
    }

    private final Checker checker;
    private final FnDecl fn;
    private final Bytecode.Fn out = new Bytecode.Fn();
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final List<Integer> loopContinue = new ArrayList<>();
    private final List<List<Integer>> loopContinueFix = new ArrayList<>();
    private final List<List<Integer>> loopBreaks = new ArrayList<>();
    private int ntemps;
    private int nregs;
    private int hidden;
    private List<String> extraCaptures = List.of();
    private String selfTy;

    private Compile(Checker checker, FnDecl fn) {
        this.checker = checker;
        this.fn = fn;
    }

    static void attach(Checker checker) {
        Set<FnDecl> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (FnDecl fn : checker.fns.values()) {
            compileOne(checker, fn, seen);
        }
        for (Map<String, FnDecl> methods : checker.typeMethods.values()) {
            for (FnDecl fn : methods.values()) {
                compileOne(checker, fn, seen);
            }
        }
        for (Checker.LoadedMod mod : checker.loaded.values()) {
            for (FnDecl fn : mod.fns.values()) {
                compileOne(checker, fn, seen);
            }
            for (FnDecl fn : mod.exports.values()) {
                compileOne(checker, fn, seen);
            }
            for (FnDecl fn : mod.fromFns.values()) {
                compileOne(checker, fn, seen);
            }
            for (List<FnDecl> ufcs : mod.ufcsFns.values()) {
                for (FnDecl fn : ufcs) {
                    compileOne(checker, fn, seen);
                }
            }
        }
    }

    static String dumpEntry(Checker checker) {
        StringBuilder ss = new StringBuilder();
        for (FnDecl fn : checker.fns.values()) {
            if (fn.code != null) {
                ss.append(fn.code.dump());
            } else {
                ss.append("fn ").append(fn.name).append(" { tree-walk }\n");
            }
            ss.append('\n');
        }
        return ss.toString();
    }

    private static void compileOne(Checker checker, FnDecl fn, Set<FnDecl> seen) {
        if (fn == null || !seen.add(fn) || fn.code != null) {
            return;
        }
        if (fn.isAbstract) {
            return;
        }
        String prev = checker.currentModule;
        checker.currentModule = fn.module == null ? "" : fn.module;
        try {
            fn.code = new Compile(checker, fn).run();
        } catch (Skip ex) {
            fn.code = null;
        } finally {
            checker.currentModule = prev;
        }
    }

    private Bytecode.Fn run() {
        out.name = fn.name;
        out.arity = fn.params.size();
        for (String param : fn.params) {
            allocNamed(param, false);
        }
        for (String cap : extraCaptures) {
            allocNamed(cap, false);
        }
        compileBlock(fn.body);
        emit(Bytecode.Op.RET_VOID, 0, 0, 0, "", fn.line, 1);
        out.nregs = Math.max(nregs, out.locals.size());
        return out;
    }

    private void compileBlock(List<Stmt> stmts) {
        for (Stmt stmt : stmts) {
            compileStmt(stmt);
        }
    }

    private void compileStmt(Stmt stmt) {
        ntemps = 0;
        switch (stmt.kind) {
            case Comment -> {
            }
            case Pass -> emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
            case Expr -> {
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                if (stmt.expr != null) {
                    compileExpr(stmt.expr);
                }
            }
            case Var, Const -> {
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                int rhs = compileExpr(stmt.expr);
                int slot = allocNamed(stmt.name, stmt.kind == Stmt.Kind.Const);
                emit(Bytecode.Op.MOVE, slot, rhs, 0, "", stmt.line, stmt.col);
            }
            case Assign -> {
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                Integer slot = slots.get(stmt.name);
                if (slot == null) {
                    Integer self = slots.get("self");
                    if (self == null || !isSelfField(stmt.name)) {
                        throw skip();
                    }
                    int rhs = compileExpr(stmt.expr);
                    String op = stmt.op == null ? "=" : stmt.op;
                    if (!op.isEmpty() && !op.equals("=")) {
                        int cur = allocTemp();
                        emit(Bytecode.Op.MEMBER, cur, self, 0, stmt.name, stmt.line, stmt.col);
                        int dest = allocTemp();
                        emit(Bytecode.Op.BIN, dest, cur, rhs, op.substring(0, op.length() - 1), stmt.line, stmt.col);
                        rhs = dest;
                    }
                    emit(Bytecode.Op.FIELD_SET, self, rhs, 0, stmt.name, stmt.line, stmt.col);
                    return;
                }
                if (slot < out.localConst.size() && Boolean.TRUE.equals(out.localConst.get(slot))) {
                    throw skip();
                }
                int rhs = compileExpr(stmt.expr);
                String op = stmt.op == null ? "=" : stmt.op;
                if (!op.isEmpty() && !op.equals("=")) {
                    int dest = allocTemp();
                    emit(Bytecode.Op.BIN, dest, slot, rhs, op.substring(0, op.length() - 1), stmt.line, stmt.col);
                    rhs = dest;
                }
                emit(Bytecode.Op.MOVE, slot, rhs, 0, "", stmt.line, stmt.col);
            }
            case Return -> {
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                if (stmt.hasExpr && stmt.expr != null) {
                    int src = compileExpr(stmt.expr);
                    emit(Bytecode.Op.RET, src, 0, 0, "", stmt.line, stmt.col);
                } else {
                    emit(Bytecode.Op.RET_VOID, 0, 0, 0, "", stmt.line, stmt.col);
                }
            }
            case If -> compileIf(stmt);
            case While -> compileWhile(stmt);
            case For -> compileFor(stmt);
            case Match -> compileMatch(stmt);
            case IndexAssign -> compileIndexAssign(stmt);
            case FieldAssign -> compileFieldAssign(stmt);
            case Break -> {
                if (loopBreaks.isEmpty()) {
                    throw skip();
                }
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                loopBreaks.getLast().add(emit(Bytecode.Op.JUMP, 0, 0, 0, "", stmt.line, stmt.col));
            }
            case Continue -> {
                if (loopContinue.isEmpty()) {
                    throw skip();
                }
                emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
                int target = loopContinue.getLast();
                if (target >= 0) {
                    emit(Bytecode.Op.JUMP, target, 0, 0, "", stmt.line, stmt.col);
                } else {
                    loopContinueFix.getLast().add(emit(Bytecode.Op.JUMP, 0, 0, 0, "", stmt.line, stmt.col));
                }
            }
            case Throw -> compileThrow(stmt);
            case Do -> compileDo(stmt);
            default -> throw skip();
        }
    }

    private void compileIf(Stmt stmt) {
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int cond = compileExpr(stmt.expr);
        int jumpElse = emit(Bytecode.Op.JUMP_F, cond, 0, 0, "", stmt.line, stmt.col);
        compileBlock(stmt.body);
        if (stmt.elseBody.isEmpty()) {
            patch(jumpElse, out.insts.size());
            return;
        }
        int jumpEnd = emit(Bytecode.Op.JUMP, 0, 0, 0, "", stmt.line, stmt.col);
        patch(jumpElse, out.insts.size());
        compileBlock(stmt.elseBody);
        patch(jumpEnd, out.insts.size());
    }

    private void compileWhile(Stmt stmt) {
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int loop = out.insts.size();
        loopContinue.add(loop);
        loopContinueFix.add(new ArrayList<>());
        loopBreaks.add(new ArrayList<>());
        int cond = compileExpr(stmt.expr);
        int jumpEnd = emit(Bytecode.Op.JUMP_F, cond, 0, 0, "", stmt.line, stmt.col);
        compileBlock(stmt.body);
        emit(Bytecode.Op.JUMP, loop, 0, 0, "", stmt.line, stmt.col);
        int end = out.insts.size();
        patch(jumpEnd, end);
        for (int br : loopBreaks.removeLast()) {
            patch(br, end);
        }
        loopContinueFix.removeLast();
        loopContinue.removeLast();
    }

    private void compileFor(Stmt stmt) {
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        boolean existed = slots.containsKey(stmt.name);
        int saved = existed ? pin() : -1;
        int items = pin();
        int idx = pin();
        int len = pin();
        int itemSlot = allocNamed(stmt.name, false);
        int iter = compileExpr(stmt.expr);
        emit(Bytecode.Op.ITER_ITEMS, items, iter, 0, "", stmt.line, stmt.col);
        if (existed) {
            emit(Bytecode.Op.MOVE, saved, itemSlot, 0, "", stmt.line, stmt.col);
        }
        int zero = loadConst(Value.makeInt(0), stmt.line, stmt.col);
        emit(Bytecode.Op.MOVE, idx, zero, 0, "", stmt.line, stmt.col);
        emit(Bytecode.Op.LEN, len, items, 0, "", stmt.line, stmt.col);
        int loop = out.insts.size();
        loopContinue.add(-1);
        loopContinueFix.add(new ArrayList<>());
        loopBreaks.add(new ArrayList<>());
        int cond = allocTemp();
        emit(Bytecode.Op.BIN, cond, idx, len, "<", stmt.line, stmt.col);
        int jumpEnd = emit(Bytecode.Op.JUMP_F, cond, 0, 0, "", stmt.line, stmt.col);
        emit(Bytecode.Op.INDEX_GET, itemSlot, items, idx, "", stmt.line, stmt.col);
        compileBlock(stmt.body);
        int cont = out.insts.size();
        for (int j : loopContinueFix.removeLast()) {
            patch(j, cont);
        }
        loopContinue.removeLast();
        ntemps = 0;
        int one = loadConst(Value.makeInt(1), stmt.line, stmt.col);
        int next = allocTemp();
        emit(Bytecode.Op.BIN, next, idx, one, "+", stmt.line, stmt.col);
        emit(Bytecode.Op.MOVE, idx, next, 0, "", stmt.line, stmt.col);
        emit(Bytecode.Op.JUMP, loop, 0, 0, "", stmt.line, stmt.col);
        int end = out.insts.size();
        patch(jumpEnd, end);
        if (existed) {
            emit(Bytecode.Op.MOVE, itemSlot, saved, 0, "", stmt.line, stmt.col);
        }
        for (int br : loopBreaks.removeLast()) {
            patch(br, end);
        }
    }

    private void compileThrow(Stmt stmt) {
        if (stmt.expr == null) {
            throw skip();
        }
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int src = compileExpr(stmt.expr);
        emit(Bytecode.Op.THROW, src, 0, 0, "", stmt.line, stmt.col);
    }

    private void compileDo(Stmt stmt) {
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int dest = -1;
        if (!stmt.elseBody.isEmpty()) {
            dest = allocNamed(stmt.name, false);
        }
        int start = out.insts.size();
        compileBlock(stmt.body);
        int end = out.insts.size();
        if (stmt.elseBody.isEmpty()) {
            return;
        }
        int jumpEnd = emit(Bytecode.Op.JUMP, 0, 0, 0, "", stmt.line, stmt.col);
        int handler = out.insts.size();
        Bytecode.Fn.Handler h = new Bytecode.Fn.Handler();
        h.start = start;
        h.end = end;
        h.handler = handler;
        h.dest = dest;
        out.handlers.add(h);
        compileBlock(stmt.elseBody);
        patch(jumpEnd, out.insts.size());
    }

    private void compileMatch(Stmt stmt) {
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int scrut = pin();
        int src = compileExpr(stmt.expr);
        emit(Bytecode.Op.MOVE, scrut, src, 0, "", stmt.line, stmt.col);
        List<Integer> ends = new ArrayList<>();
        for (MatchArm arm : stmt.arms) {
            if (hasMatchBinds(arm) && stmtsEscape(arm.body, 0)) {
                throw skip();
            }
            ntemps = 0;
            int next = -1;
            switch (arm.pat) {
                case Wildcard -> {
                }
                case Int -> next = emitMatchLit(scrut, Value.makeInt(arm.number), arm.line, arm.col);
                case Float -> next = emitMatchLit(scrut, Value.makeFloat(arm.real), arm.line, arm.col);
                case String -> next = emitMatchLit(scrut, Value.makeString(arm.text), arm.line, arm.col);
                case Bool -> next = emitMatchLit(scrut, Value.makeBool(arm.booleanValue), arm.line, arm.col);
                case Variant -> {
                    int hit = allocTemp();
                    emit(Bytecode.Op.MATCH_VAR, hit, scrut, 0, arm.name, arm.line, arm.col);
                    next = emit(Bytecode.Op.JUMP_F, hit, 0, 0, "", arm.line, arm.col);
                }
            }
            List<int[]> restores = bindMatchArm(arm, scrut, stmt.line, stmt.col);
            compileBlock(arm.body);
            for (int[] restore : restores) {
                emit(Bytecode.Op.MOVE, restore[0], restore[1], 0, "", stmt.line, stmt.col);
            }
            ends.add(emit(Bytecode.Op.JUMP, 0, 0, 0, "", stmt.line, stmt.col));
            if (next >= 0) {
                patch(next, out.insts.size());
            }
        }
        int end = out.insts.size();
        for (int jump : ends) {
            patch(jump, end);
        }
    }

    private int emitMatchLit(int scrut, Value pat, int line, int col) {
        int k = loadConst(pat, line, col);
        int hit = allocTemp();
        emit(Bytecode.Op.MATCH_LIT, hit, scrut, k, "", line, col);
        return emit(Bytecode.Op.JUMP_F, hit, 0, 0, "", line, col);
    }

    private List<int[]> bindMatchArm(MatchArm arm, int scrut, int line, int col) {
        List<int[]> restores = new ArrayList<>();
        if (arm.pat != MatchArm.Pat.Variant) {
            return restores;
        }
        boolean named = false;
        for (String field : arm.fieldNames) {
            if (field != null && !field.isEmpty()) {
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
                bindPayload(arm.binds.get(i), scrut, 0, field, line, col, restores);
            }
            return restores;
        }
        if (arm.binds.size() == 1) {
            bindPayload(arm.binds.getFirst(), scrut, -1, "", line, col, restores);
            return restores;
        }
        for (int i = 0; i < arm.binds.size(); i++) {
            bindPayload(arm.binds.get(i), scrut, i, "", line, col, restores);
        }
        return restores;
    }

    private void bindPayload(String name, int scrut, int index, String field, int line, int col, List<int[]> restores) {
        if (name == null || name.isEmpty() || "_".equals(name)) {
            return;
        }
        boolean existed = slots.containsKey(name);
        int saved = existed ? pin() : -1;
        if (existed) {
            emit(Bytecode.Op.MOVE, saved, slots.get(name), 0, "", line, col);
        }
        int slot = allocNamed(name, false);
        emit(Bytecode.Op.PAYLOAD, slot, scrut, index, field, line, col);
        if (existed) {
            restores.add(new int[]{slot, saved});
        }
    }

    private static boolean hasMatchBinds(MatchArm arm) {
        if (arm.pat != MatchArm.Pat.Variant) {
            return false;
        }
        for (String name : arm.binds) {
            if (name != null && !name.isEmpty() && !"_".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stmtsEscape(List<Stmt> stmts, int loops) {
        for (Stmt stmt : stmts) {
            switch (stmt.kind) {
                case Break, Continue -> {
                    if (loops == 0) {
                        return true;
                    }
                }
                case While, For -> {
                    if (stmtsEscape(stmt.body, loops + 1) || stmtsEscape(stmt.elseBody, loops + 1)) {
                        return true;
                    }
                }
                case If, Do -> {
                    if (stmtsEscape(stmt.body, loops) || stmtsEscape(stmt.elseBody, loops)) {
                        return true;
                    }
                }
                case Match -> {
                    for (MatchArm nested : stmt.arms) {
                        if (stmtsEscape(nested.body, loops)) {
                            return true;
                        }
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    private void compileIndexAssign(Stmt stmt) {
        if (stmt.target == null || stmt.target.kids.size() < 2) {
            throw skip();
        }
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int obj = compileExpr(stmt.target.kids.get(0));
        int index = compileExpr(stmt.target.kids.get(1));
        int rhs = compileExpr(stmt.expr);
        String op = stmt.op == null ? "=" : stmt.op;
        if (!op.isEmpty() && !op.equals("=")) {
            int cur = allocTemp();
            emit(Bytecode.Op.INDEX_GET, cur, obj, index, "", stmt.line, stmt.col);
            int dest = allocTemp();
            emit(Bytecode.Op.BIN, dest, cur, rhs, op.substring(0, op.length() - 1), stmt.line, stmt.col);
            rhs = dest;
        }
        emit(Bytecode.Op.INDEX_SET, obj, index, rhs, "", stmt.line, stmt.col);
    }

    private void compileFieldAssign(Stmt stmt) {
        if (stmt.target == null) {
            throw skip();
        }
        emit(Bytecode.Op.DEBUG, 0, 0, 0, "", stmt.line, stmt.col);
        int obj = compileRecv(stmt.target);
        int rhs = compileExpr(stmt.expr);
        String op = stmt.op == null ? "=" : stmt.op;
        if (!op.isEmpty() && !op.equals("=")) {
            int cur = allocTemp();
            emit(Bytecode.Op.MEMBER, cur, obj, 0, stmt.name, stmt.line, stmt.col);
            int dest = allocTemp();
            emit(Bytecode.Op.BIN, dest, cur, rhs, op.substring(0, op.length() - 1), stmt.line, stmt.col);
            rhs = dest;
        }
        emit(Bytecode.Op.FIELD_SET, obj, rhs, 0, stmt.name, stmt.line, stmt.col);
    }

    private int compileExpr(Expr e) {
        if (e == null) {
            throw skip();
        }
        return switch (e.kind) {
            case Int -> loadConst(Value.makeInt(e.number), e.line, e.col);
            case Float -> loadConst(Value.makeFloat(e.real), e.line, e.col);
            case String -> loadConst(Value.makeString(e.text), e.line, e.col);
            case Bool -> loadConst(Value.makeBool(e.booleanValue), e.line, e.col);
            case Var -> {
                Integer slot = slots.get(e.text);
                if (slot != null) {
                    yield slot;
                }
                Integer self = slots.get("self");
                if (self != null && (isSelfField(e.text) || isSelfSignal(e.text))) {
                    int dest = allocTemp();
                    emit(Bytecode.Op.MEMBER, dest, self, 0, e.text, e.line, e.col);
                    yield dest;
                }
                if (isFnName(e.text)) {
                    int dest = allocTemp();
                    emit(Bytecode.Op.LOAD_FN, dest, 0, 0, e.text, e.line, e.col);
                    yield dest;
                }
                if (checker.findEnum(e.text) != null) {
                    int dest = allocTemp();
                    emit(Bytecode.Op.LOAD_ENUM, dest, 0, 0, e.text, e.line, e.col);
                    yield dest;
                }
                if (checker.signalArity.containsKey(e.text)) {
                    int dest = allocTemp();
                    emit(Bytecode.Op.LOAD_SIGNAL, dest, 0, 0, e.text, e.line, e.col);
                    yield dest;
                }
                throw skip();
            }
            case Unary -> {
                int src = compileExpr(e.kids.getFirst());
                int dest = allocTemp();
                emit(Bytecode.Op.UNARY, dest, src, 0, e.text, e.line, e.col);
                yield dest;
            }
            case Binary -> compileBinary(e);
            case Call -> compileCall(e);
            case Lambda -> compileLambda(e);
            case MethodCall -> compileMethodCall(e);
            case StructLit -> compileStructLit(e);
            case Member -> compileMember(e);
            case Array -> compileArray(e);
            case Map -> compileMap(e);
            case Range -> compileRange(e);
            case Index -> compileIndex(e);
            case Try -> compileExpr(e.kids.getFirst());
            case Await -> compileAwait(e);
            default -> throw skip();
        };
    }

    private int compileBinary(Expr e) {
        if ("&&".equals(e.text) || "||".equals(e.text)) {
            boolean and = "&&".equals(e.text);
            int dest = allocTemp();
            int left = compileExpr(e.kids.getFirst());
            emit(Bytecode.Op.TRUTHY, dest, left, 0, "", e.line, e.col);
            int skip = emit(and ? Bytecode.Op.JUMP_F : Bytecode.Op.JUMP_T, dest, 0, 0, "", e.line, e.col);
            int right = compileExpr(e.kids.get(1));
            emit(Bytecode.Op.TRUTHY, dest, right, 0, "", e.line, e.col);
            patch(skip, out.insts.size());
            return dest;
        }
        int lhs = compileExpr(e.kids.get(0));
        int rhs = compileExpr(e.kids.get(1));
        int dest = allocTemp();
        emit(Bytecode.Op.BIN, dest, lhs, rhs, e.text, e.line, e.col);
        return dest;
    }

    private int compileCall(Expr e) {
        if (e.module != null && !e.module.isEmpty()) {
            throw skip();
        }
        if (e.text.isEmpty()) {
            if (e.kids.isEmpty()) {
                throw skip();
            }
            int[] packed = new int[e.kids.size()];
            for (int i = 0; i < e.kids.size(); i++) {
                packed[i] = compileExpr(e.kids.get(i));
            }
            int base = pack(packed, e.line, e.col);
            int dest = allocTemp();
            emit(Bytecode.Op.CALL_VAL, dest, base, packed.length, "", e.line, e.col);
            return dest;
        }
        if (slots.containsKey(e.text) && !isFnName(e.text)) {
            int[] packed = new int[e.kids.size() + 1];
            packed[0] = slots.get(e.text);
            for (int i = 0; i < e.kids.size(); i++) {
                packed[i + 1] = compileExpr(e.kids.get(i));
            }
            int base = pack(packed, e.line, e.col);
            int dest = allocTemp();
            emit(Bytecode.Op.CALL_VAL, dest, base, packed.length, "", e.line, e.col);
            return dest;
        }
        FnDecl callee = checker.findLocalFn(e.text);
        if (callee == null) {
            callee = checker.fns.get(e.text);
        }
        Integer self = slots.get("self");
        String ty = selfType();
        if (callee == null && self != null && ty != null && checker.lookupMethod(ty, e.text).fn != null) {
            int[] packed = new int[e.kids.size() + 1];
            packed[0] = self;
            for (int i = 0; i < e.kids.size(); i++) {
                packed[i + 1] = compileExpr(e.kids.get(i));
            }
            int base = pack(packed, e.line, e.col);
            int dest = allocTemp();
            emit(Bytecode.Op.METHOD, dest, base, packed.length, e.text, e.line, e.col);
            return dest;
        }
        int[] args = new int[e.kids.size()];
        for (int i = 0; i < e.kids.size(); i++) {
            args[i] = compileExpr(e.kids.get(i));
        }
        int base = pack(args, e.line, e.col);
        int dest = allocTemp();
        int idx = emit(Bytecode.Op.CALL, dest, base, args.length, e.text, e.line, e.col);
        if (callee != null) {
            Bytecode.Inst inst = out.insts.get(idx);
            inst.s = callee.name;
            inst.module = callee.module == null ? "" : callee.module;
        }
        return dest;
    }

    private int compileLambda(Expr e) {
        if (e.lambda == null) {
            throw skip();
        }
        List<String> capNames = lambdaCaptures(e.lambda);
        lowerLambda(e.lambda, capNames);
        int[] caps = new int[capNames.size()];
        Bytecode.Inst inst = new Bytecode.Inst();
        for (int i = 0; i < capNames.size(); i++) {
            String name = capNames.get(i);
            Integer slot = slots.get(name);
            if (slot == null) {
                throw skip();
            }
            caps[i] = slot;
            inst.names.add(name);
            inst.flags.add(slot < out.localConst.size() && Boolean.TRUE.equals(out.localConst.get(slot)));
        }
        int base = pack(caps, e.line, e.col);
        int dest = allocTemp();
        int idx = emit(Bytecode.Op.CLOSURE, dest, base, caps.length, "", e.line, e.col);
        Bytecode.Inst outInst = out.insts.get(idx);
        outInst.proto = e.lambda;
        outInst.names.addAll(inst.names);
        outInst.flags.addAll(inst.flags);
        return dest;
    }

    private void lowerLambda(FnDecl lambda, List<String> captures) {
        if (lambda.code != null || lambda.isAbstract) {
            return;
        }
        Compile inner = new Compile(checker, lambda);
        inner.extraCaptures = captures;
        inner.selfTy = selfType();
        try {
            lambda.code = inner.run();
        } catch (Skip ex) {
            lambda.code = null;
        }
    }

    private List<String> lambdaCaptures(FnDecl lambda) {
        Set<String> bound = new HashSet<>(lambda.params);
        Set<String> free = new HashSet<>();
        for (Stmt st : lambda.body) {
            Interp.collectFreeStmt(st, bound, free);
        }
        List<String> capNames = new ArrayList<>();
        boolean capturedSelf = false;
        for (String name : free) {
            if (skipLambdaCapture(name)) {
                continue;
            }
            if (slots.containsKey(name)) {
                capNames.add(name);
                if ("self".equals(name)) {
                    capturedSelf = true;
                }
                continue;
            }
            if ((isSelfField(name) || isSelfSignal(name)) && slots.containsKey("self") && !capturedSelf) {
                capNames.add("self");
                capturedSelf = true;
            }
        }
        return capNames;
    }

    private boolean skipLambdaCapture(String name) {
        return Interp.skipCaptureName(name) || checker.findLocalFn(name) != null || checker.fns.containsKey(name)
                || checker.structs.containsKey(name) || checker.allTypes.containsKey(name)
                || checker.enums.containsKey(name) || checker.traits.containsKey(name)
                || checker.signalArity.containsKey(name) || checker.moduleBinds.containsKey(name)
                || Types.isHostModule(name);
    }

    private boolean isSelfSignal(String name) {
        String ty = selfType();
        return ty != null && checker.lookupTypeSignal(ty, name) != null;
    }

    private int compileMethodCall(Expr e) {
        if (e.kids.isEmpty()) {
            throw skip();
        }
        Expr recv = e.kids.getFirst();
        if (recv.kind == Expr.Kind.Var) {
            if ("super".equals(recv.text)) {
                return compileSuperCall(e);
            }
            if (slots.get(recv.text) == null) {
                String bound = checker.findModuleBind(recv.text);
                if (bound != null) {
                    return compileQualifiedCall(bound, e.text, e.kids, 1, e.line, e.col);
                }
                if (Types.isHostModule(recv.text) || "Future".equals(recv.text)) {
                    return compileQualifiedCall(recv.text, e.text, e.kids, 1, e.line, e.col);
                }
            }
        }
        String nested = modulePath(recv);
        if (nested != null) {
            return compileQualifiedCall(nested, e.text, e.kids, 1, e.line, e.col);
        }
        String recvTy = recvTypeHint(recv);
        FnDecl ufcs = checker.findUfcs(e.text, recvTy == null ? "" : recvTy);
        boolean typeMethod = recvTy != null && checker.lookupMethod(recvTy, e.text).fn != null;
        boolean useUfcs = ufcs != null && Llvm.methodOpId(e.text) < 0 && !typeMethod;
        if (useUfcs && recv.kind == Expr.Kind.Var) {
            useUfcs = !isTypeMethodName(e.text);
        }
        if (useUfcs) {
            int[] packed = new int[e.kids.size()];
            for (int i = 0; i < e.kids.size(); i++) {
                packed[i] = compileExpr(e.kids.get(i));
            }
            int base = pack(packed, e.line, e.col);
            int dest = allocTemp();
            int idx = emit(Bytecode.Op.CALL, dest, base, packed.length, ufcs.name, e.line, e.col);
            out.insts.get(idx).module = ufcs.module == null ? "" : ufcs.module;
            return dest;
        }
        int[] packed = new int[e.kids.size()];
        for (int i = 0; i < e.kids.size(); i++) {
            packed[i] = compileExpr(e.kids.get(i));
        }
        int base = pack(packed, e.line, e.col);
        int dest = allocTemp();
        emit(Bytecode.Op.METHOD, dest, base, packed.length, e.text, e.line, e.col);
        return dest;
    }

    private int compileSuperCall(Expr e) {
        Integer self = slots.get("self");
        if (self == null) {
            throw skip();
        }
        int[] packed = new int[e.kids.size()];
        packed[0] = self;
        for (int i = 1; i < e.kids.size(); i++) {
            packed[i] = compileExpr(e.kids.get(i));
        }
        int base = pack(packed, e.line, e.col);
        int dest = allocTemp();
        int idx = emit(Bytecode.Op.SUPER, dest, base, packed.length, e.text, e.line, e.col);
        out.insts.get(idx).module = superStartType();
        return dest;
    }

    private int compileQualifiedCall(String module, String name, List<Expr> kids, int argStart, int line, int col) {
        int n = kids.size() - argStart;
        int[] args = new int[Math.max(n, 0)];
        for (int i = 0; i < args.length; i++) {
            args[i] = compileExpr(kids.get(argStart + i));
        }
        int base = pack(args, line, col);
        int dest = allocTemp();
        int idx = emit(Bytecode.Op.CALL, dest, base, args.length, name, line, col);
        out.insts.get(idx).module = module == null ? "" : module;
        return dest;
    }

    private int compileAwait(Expr e) {
        if (e.kids.isEmpty()) {
            throw skip();
        }
        int src = compileExpr(e.kids.getFirst());
        int dest = allocTemp();
        emit(Bytecode.Op.AWAIT, dest, src, 0, "", e.line, e.col);
        return dest;
    }

    private int compileArray(Expr e) {
        int[] elems = new int[e.kids.size()];
        for (int i = 0; i < e.kids.size(); i++) {
            elems[i] = compileExpr(e.kids.get(i));
        }
        int base = pack(elems, e.line, e.col);
        int dest = allocTemp();
        emit(Bytecode.Op.ARRAY, dest, base, elems.length, "", e.line, e.col);
        return dest;
    }

    private int compileMap(Expr e) {
        if (e.kids.size() % 2 != 0) {
            throw skip();
        }
        int[] kids = new int[e.kids.size()];
        for (int i = 0; i < e.kids.size(); i++) {
            kids[i] = compileExpr(e.kids.get(i));
        }
        int base = pack(kids, e.line, e.col);
        int dest = allocTemp();
        emit(Bytecode.Op.MAP, dest, base, kids.length, "", e.line, e.col);
        return dest;
    }

    private int compileStructLit(Expr e) {
        StructDecl decl = checker.findStruct(e.text);
        if (decl == null) {
            throw skip();
        }
        LinkedHashMap<String, Integer> provided = new LinkedHashMap<>();
        for (int i = 0; i < e.names.size(); i++) {
            String field = e.names.get(i);
            if (!decl.fields.contains(field) || provided.containsKey(field)) {
                throw skip();
            }
            provided.put(field, compileExpr(e.kids.get(i)));
        }
        int[] vals = new int[decl.fields.size()];
        Map<String, Expr> defaults = checker.fieldDefaults.get(decl.name);
        for (int i = 0; i < decl.fields.size(); i++) {
            String field = decl.fields.get(i);
            Integer reg = provided.get(field);
            if (reg != null) {
                vals[i] = reg;
                continue;
            }
            if (defaults != null && defaults.containsKey(field)) {
                vals[i] = compileExpr(defaults.get(field));
                continue;
            }
            if (decl.isOptionalField(field)) {
                vals[i] = loadZero(decl.typeOfField(field), e.line, e.col);
                continue;
            }
            throw skip();
        }
        int base = pack(vals, e.line, e.col);
        int dest = allocTemp();
        emit(Bytecode.Op.STRUCT, dest, base, vals.length, e.text, e.line, e.col);
        return dest;
    }

    private int compileMember(Expr e) {
        if (e.kids.isEmpty()) {
            throw skip();
        }
        int obj = compileRecv(e.kids.getFirst());
        int dest = allocTemp();
        emit(Bytecode.Op.MEMBER, dest, obj, 0, e.text, e.line, e.col);
        return dest;
    }

    private int compileRange(Expr e) {
        if (e.kids.size() < 2) {
            throw skip();
        }
        int start = compileExpr(e.kids.get(0));
        int end = compileExpr(e.kids.get(1));
        int dest = allocTemp();
        emit(Bytecode.Op.RANGE, dest, start, end, e.booleanValue ? "..=" : "..", e.line, e.col);
        return dest;
    }

    private int compileIndex(Expr e) {
        if (e.kids.size() < 2) {
            throw skip();
        }
        int obj = compileExpr(e.kids.get(0));
        int idx = compileExpr(e.kids.get(1));
        int dest = allocTemp();
        emit(Bytecode.Op.INDEX_GET, dest, obj, idx, "", e.line, e.col);
        return dest;
    }

    private int pin() {
        hidden++;
        return allocNamed("#" + hidden, false);
    }

    private int pack(int[] srcs, int line, int col) {
        if (srcs.length == 0) {
            return 0;
        }
        int base = allocTemp();
        emit(Bytecode.Op.MOVE, base, srcs[0], 0, "", line, col);
        for (int i = 1; i < srcs.length; i++) {
            emit(Bytecode.Op.MOVE, allocTemp(), srcs[i], 0, "", line, col);
        }
        return base;
    }

    private boolean isFnName(String name) {
        return checker.findLocalFn(name) != null || checker.fns.containsKey(name);
    }

    private boolean isSelfField(String name) {
        String ty = selfType();
        if (ty == null) {
            return false;
        }
        StructDecl st = checker.findStruct(ty);
        return st != null && st.fields.contains(name);
    }

    private boolean isTypeMethodName(String name) {
        for (Map<String, FnDecl> methods : checker.typeMethods.values()) {
            if (methods.containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    private String recvTypeHint(Expr recv) {
        if (recv == null) {
            return null;
        }
        if (recv.kind == Expr.Kind.StructLit) {
            return recv.text;
        }
        if (recv.kind == Expr.Kind.Var) {
            if ("self".equals(recv.text)) {
                return selfType();
            }
            if (slots.get(recv.text) == null && isSelfField(recv.text)) {
                String ty = selfType();
                StructDecl st = ty == null ? null : checker.findStruct(ty);
                if (st != null) {
                    String fieldTy = st.typeOfField(recv.text);
                    return fieldTy == null || fieldTy.isEmpty() ? null : Types.typeHead(fieldTy);
                }
            }
        }
        return null;
    }

    private String modulePath(Expr e) {
        if (e == null) {
            return null;
        }
        if (e.kind == Expr.Kind.Var) {
            if (slots.get(e.text) != null) {
                return null;
            }
            return checker.findModuleBind(e.text);
        }
        if (e.kind == Expr.Kind.Member && !e.kids.isEmpty()) {
            String parent = modulePath(e.kids.getFirst());
            if (parent == null) {
                return null;
            }
            Checker.LoadedMod lit = checker.loaded.get(parent);
            if (lit == null) {
                throw skip();
            }
            boolean internal = checker.currentModule.equals(parent);
            java.util.Map<String, String> map = internal ? lit.modules : lit.exportMods;
            String hit = map.get(e.text);
            if (hit != null) {
                return hit;
            }
            throw skip();
        }
        return null;
    }

    private int compileRecv(Expr e) {
        if (e != null && e.kind == Expr.Kind.Var && "super".equals(e.text)) {
            Integer self = slots.get("self");
            if (self == null) {
                throw skip();
            }
            return self;
        }
        return compileExpr(e);
    }

    private String superStartType() {
        String ty = selfType();
        if (ty == null) {
            return "";
        }
        String parent = checker.classParents.get(ty);
        return parent == null ? "" : Types.typeHead(parent);
    }

    private String selfType() {
        if (selfTy != null) {
            return selfTy.isEmpty() ? null : selfTy;
        }
        if (!fn.params.contains("self") && !slots.containsKey("self")) {
            return null;
        }
        for (Map.Entry<String, Map<String, FnDecl>> e : checker.typeMethods.entrySet()) {
            if (e.getValue().containsValue(fn)) {
                return e.getKey();
            }
        }
        return null;
    }

    private int loadConst(Value v, int line, int col) {
        int dest = allocTemp();
        emit(Bytecode.Op.LOAD_CONST, dest, intern(v), 0, "", line, col);
        return dest;
    }

    private int loadZero(String ty, int line, int col) {
        String head = Types.typeHead(ty);
        if ("Array".equals(head)) {
            int dest = allocTemp();
            emit(Bytecode.Op.ARRAY, dest, 0, 0, "", line, col);
            return dest;
        }
        if ("Map".equals(head)) {
            int dest = allocTemp();
            emit(Bytecode.Op.MAP, dest, 0, 0, "", line, col);
            return dest;
        }
        return loadConst(Interp.zeroOfType(ty), line, col);
    }

    private int intern(Value v) {
        if (internable(v)) {
            for (int i = 0; i < out.constants.size(); i++) {
                Value c = out.constants.get(i);
                if (c.kind == v.kind && c.equalsValue(v)) {
                    return i;
                }
            }
        }
        out.constants.add(v);
        return out.constants.size() - 1;
    }

    private static boolean internable(Value v) {
        return v.kind == Value.Kind.Int || v.kind == Value.Kind.Float || v.kind == Value.Kind.String
                || v.kind == Value.Kind.Bool || v.kind == Value.Kind.Void;
    }

    private int allocNamed(String name, boolean isConst) {
        Integer existing = slots.get(name);
        if (existing != null) {
            out.localConst.set(existing, isConst);
            touch(existing);
            return existing;
        }
        int r = out.locals.size();
        slots.put(name, r);
        out.locals.add(name);
        out.localConst.add(isConst);
        touch(r);
        return r;
    }

    private int allocTemp() {
        int r = out.locals.size() + ntemps;
        ntemps++;
        touch(r);
        return r;
    }

    private void touch(int r) {
        nregs = Math.max(nregs, r + 1);
    }

    private int emit(Bytecode.Op op, int a, int b, int c, String s, int line, int col) {
        Bytecode.Inst inst = new Bytecode.Inst();
        inst.op = op;
        inst.a = a;
        inst.b = b;
        inst.c = c;
        inst.s = s == null ? "" : s;
        inst.line = line > 0 ? line : 1;
        inst.col = col > 0 ? col : 1;
        out.insts.add(inst);
        return out.insts.size() - 1;
    }

    private void patch(int index, int target) {
        Bytecode.Inst inst = out.insts.get(index);
        if (inst.op == Bytecode.Op.JUMP) {
            inst.a = target;
        } else {
            inst.b = target;
        }
    }

    private static Skip skip() {
        return new Skip();
    }
}

package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

final class Bytecode {

    enum Op {
        LOAD_CONST,
        LOAD_FN,
        LOAD_ENUM,
        LOAD_SIGNAL,
        MOVE,
        BIN,
        UNARY,
        TRUTHY,
        JUMP,
        JUMP_F,
        JUMP_T,
        CALL,
        CALL_VAL,
        ARRAY,
        RANGE,
        INDEX_GET,
        INDEX_SET,
        ITER_ITEMS,
        LEN,
        MAP,
        METHOD,
        SUPER,
        STRUCT,
        MEMBER,
        FIELD_SET,
        MATCH_LIT,
        MATCH_VAR,
        PAYLOAD,
        THROW,
        CLOSURE,
        AWAIT,
        RET,
        RET_VOID,
        DEBUG
    }

    static final class Inst {
        Op op;
        int a;
        int b;
        int c;
        String s = "";
        String module = "";
        FnDecl proto;
        final List<String> names = new ArrayList<>();
        final List<Boolean> flags = new ArrayList<>();
        int line = 1;
        int col = 1;
    }

    static final class Fn {
        String name = "";
        int arity;
        int nregs;
        final List<Inst> insts = new ArrayList<>();
        final List<Handler> handlers = new ArrayList<>();
        final List<Value> constants = new ArrayList<>();
        final List<String> locals = new ArrayList<>();
        final List<Boolean> localConst = new ArrayList<>();

        String dump() {
            StringBuilder ss = new StringBuilder();
            ss.append("fn ").append(name).append("(");
            for (int i = 0; i < arity; i++) {
                if (i > 0) {
                    ss.append(", ");
                }
                ss.append(i < locals.size() && !locals.get(i).isEmpty() ? locals.get(i) : ("r" + i));
            }
            ss.append(") regs=").append(nregs).append('\n');
            for (int i = 0; i < insts.size(); i++) {
                ss.append("  ").append(i).append("  ").append(format(insts.get(i))).append('\n');
            }
            for (Handler h : handlers) {
                ss.append("  handler ").append(h.start).append("..").append(h.end)
                        .append(" -> ").append(h.handler).append(" r").append(h.dest).append('\n');
            }
            for (int i = 0; i < constants.size(); i++) {
                Value v = constants.get(i);
                ss.append("  const ").append(i).append("  ").append(v.kind);
                switch (v.kind) {
                    case Int -> ss.append(' ').append(v.i);
                    case Float -> ss.append(' ').append(v.real);
                    case String -> ss.append(' ').append(v.s);
                    case Bool -> ss.append(' ').append(v.b);
                    default -> {
                    }
                }
                ss.append('\n');
            }
            return ss.toString();
        }

        static final class Handler {
            int start;
            int end;
            int handler;
            int dest;
        }

        private static String format(Inst in) {
            return switch (in.op) {
                case LOAD_CONST -> "LOAD_CONST r" + in.a + " k" + in.b;
                case LOAD_FN -> "LOAD_FN r" + in.a + " " + in.s;
                case LOAD_ENUM -> "LOAD_ENUM r" + in.a + " " + in.s;
                case LOAD_SIGNAL -> "LOAD_SIGNAL r" + in.a + " " + in.s;
                case MOVE -> "MOVE r" + in.a + " r" + in.b;
                case BIN -> "BIN " + in.s + " r" + in.a + " r" + in.b + " r" + in.c;
                case UNARY -> "UNARY " + in.s + " r" + in.a + " r" + in.b;
                case TRUTHY -> "TRUTHY r" + in.a + " r" + in.b;
                case JUMP -> "JUMP " + in.a;
                case JUMP_F -> "JUMP_F r" + in.a + " " + in.b;
                case JUMP_T -> "JUMP_T r" + in.a + " " + in.b;
                case CALL -> "CALL "
                        + ((in.module == null || in.module.isEmpty()) ? in.s : in.module + "." + in.s)
                        + " r" + in.a + " base=" + in.b + " argc=" + in.c;
                case CALL_VAL -> "CALL_VAL r" + in.a + " base=" + in.b + " argc=" + in.c;
                case ARRAY -> "ARRAY r" + in.a + " base=" + in.b + " argc=" + in.c;
                case RANGE -> "RANGE" + ("..=".equals(in.s) ? "_IN" : "") + " r" + in.a + " r" + in.b + " r" + in.c;
                case INDEX_GET -> "INDEX_GET r" + in.a + " r" + in.b + " r" + in.c;
                case INDEX_SET -> "INDEX_SET r" + in.a + " r" + in.b + " r" + in.c;
                case ITER_ITEMS -> "ITER_ITEMS r" + in.a + " r" + in.b;
                case LEN -> "LEN r" + in.a + " r" + in.b;
                case MAP -> "MAP r" + in.a + " base=" + in.b + " argc=" + in.c;
                case METHOD -> "METHOD " + in.s + " r" + in.a + " base=" + in.b + " argc=" + in.c;
                case SUPER -> {
                    String start = in.module == null || in.module.isEmpty() ? "" : in.module + ".";
                    yield "SUPER " + start + in.s + " r" + in.a + " base=" + in.b + " argc=" + in.c;
                }
                case STRUCT -> "STRUCT " + in.s + " r" + in.a + " base=" + in.b + " argc=" + in.c;
                case MEMBER -> "MEMBER " + in.s + " r" + in.a + " r" + in.b;
                case FIELD_SET -> "FIELD_SET " + in.s + " r" + in.a + " r" + in.b;
                case MATCH_LIT -> "MATCH_LIT r" + in.a + " r" + in.b + " r" + in.c;
                case MATCH_VAR -> "MATCH_VAR r" + in.a + " r" + in.b + " " + in.s;
                case PAYLOAD -> "PAYLOAD r" + in.a + " r" + in.b
                        + (in.s == null || in.s.isEmpty() ? (" " + in.c) : (" " + in.s));
                case THROW -> "THROW r" + in.a;
                case CLOSURE -> "CLOSURE r" + in.a + " base=" + in.b + " argc=" + in.c;
                case AWAIT -> "AWAIT r" + in.a + " r" + in.b;
                case RET -> "RET r" + in.a;
                case RET_VOID -> "RET_VOID";
                case DEBUG -> "DEBUG " + in.line + ":" + in.col;
            };
        }
    }

    private Bytecode() {
    }
}

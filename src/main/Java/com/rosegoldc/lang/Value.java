package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Value {

    enum Kind { Void, Bool, Int, Float, String, FnRef, Array, Range, Struct, Map, SignalRef, EnumType, Enum, Future }

    static final class StructData {
        String name = "";
        List<String> order = new ArrayList<>();
        Map<String, Value> fields = new java.util.LinkedHashMap<>();
        Map<String, List<Value>> listeners = new java.util.LinkedHashMap<>();
    }

    static final class MapData {
        List<String> order = new ArrayList<>();
        Map<String, Value> fields = new java.util.LinkedHashMap<>();
    }

    static final class Binding {
        Value value;
        final boolean isConst;

        Binding(Value value, boolean isConst) {
            this.value = value;
            this.isConst = isConst;
        }
    }

    static final class ClosureData {
        FnDecl fn;
        final Map<String, Binding> caps = new java.util.LinkedHashMap<>();
    }

    static final class FutureData {
        enum State { Pending, Ready, Failed }

        State state = State.Pending;
        Value result = Value.makeVoid();
        Value error = Value.makeVoid();
        int errLine = 1;
        int errCol = 1;
        final List<Runnable> waiters = new ArrayList<>();
    }

    Kind kind = Kind.Void;
    boolean b;
    long i;
    double real;
    String s = "";
    List<Value> items;
    long rangeEnd;
    boolean inclusive;
    StructData rec;
    MapData dict;
    ClosureData clo;
    String variant = "";
    List<Value> payload;
    FutureData fut;

    static Value makeVoid() {
        return new Value();
    }

    static Value makeBool(boolean v) {
        Value x = new Value();
        x.kind = Kind.Bool;
        x.b = v;
        return x;
    }

    static Value makeInt(long v) {
        Value x = new Value();
        x.kind = Kind.Int;
        x.i = v;
        return x;
    }

    static Value makeFloat(double v) {
        Value x = new Value();
        x.kind = Kind.Float;
        x.real = v;
        return x;
    }

    static Value makeString(String v) {
        Value x = new Value();
        x.kind = Kind.String;
        x.s = v == null ? "" : v;
        return x;
    }

    static Value makeFnRef(String name) {
        Value x = new Value();
        x.kind = Kind.FnRef;
        x.s = name == null ? "" : name;
        return x;
    }

    static Value makeClosure(ClosureData data) {
        Value x = new Value();
        x.kind = Kind.FnRef;
        x.s = "<fn>";
        x.clo = data;
        return x;
    }

    static Value makeArray(List<Value> elems) {
        Value x = new Value();
        x.kind = Kind.Array;
        x.items = new ArrayList<>(elems);
        return x;
    }

    static Value makeRange(long start, long end, boolean inclusive) {
        Value x = new Value();
        x.kind = Kind.Range;
        x.i = start;
        x.rangeEnd = end;
        x.inclusive = inclusive;
        return x;
    }

    static Value makeStruct(StructData data) {
        Value x = new Value();
        x.kind = Kind.Struct;
        x.rec = data;
        return x;
    }

    static Value makeMap(MapData data) {
        Value x = new Value();
        x.kind = Kind.Map;
        x.dict = data;
        return x;
    }

    static Value makeSignalRef(String name, StructData owner) {
        Value x = new Value();
        x.kind = Kind.SignalRef;
        x.s = name == null ? "" : name;
        x.rec = owner;
        return x;
    }

    static Value makeEnumType(String name) {
        Value x = new Value();
        x.kind = Kind.EnumType;
        x.s = name == null ? "" : name;
        return x;
    }

    static Value makeEnum(String type, String variant, List<Value> payload) {
        Value x = new Value();
        x.kind = Kind.Enum;
        x.s = type == null ? "" : type;
        x.variant = variant == null ? "" : variant;
        x.payload = payload == null ? new ArrayList<>() : new ArrayList<>(payload);
        return x;
    }

    static Value makeFuture(FutureData data) {
        Value x = new Value();
        x.kind = Kind.Future;
        x.fut = data;
        return x;
    }

    boolean isNumeric() {
        return kind == Kind.Int || kind == Kind.Float;
    }

    double asF64() {
        return kind == Kind.Float ? real : (double) i;
    }

    boolean truthy() {
        return switch (kind) {
            case Bool -> b;
            case Int -> i != 0;
            case Float -> real != 0.0;
            case String -> !s.isEmpty();
            case FnRef, Array, Range, Struct, Map, SignalRef, EnumType, Enum, Future -> true;
            default -> false;
        };
    }

    boolean equalsValue(Value other) {
        return equalsRec(other, new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    private boolean equalsRec(Value other, IdentityHashMap<Object, Boolean> seenA, IdentityHashMap<Object, Boolean> seenB) {
        if (kind != other.kind) {
            if (isNumeric() && other.isNumeric()) {
                return numericEq(asF64(), other.asF64());
            }
            return false;
        }
        return switch (kind) {
            case Void -> true;
            case Bool -> b == other.b;
            case Int -> i == other.i;
            case Float -> numericEq(real, other.real);
            case String, EnumType -> Objects.equals(s, other.s);
            case FnRef -> {
                if (clo != null || other.clo != null) {
                    yield clo == other.clo;
                }
                yield Objects.equals(s, other.s);
            }
            case Range -> i == other.i && rangeEnd == other.rangeEnd && inclusive == other.inclusive;
            case Array -> {
                if (items == other.items) {
                    yield true;
                }
                if (items == null || other.items == null || items.size() != other.items.size()) {
                    yield false;
                }
                if (seenA.put(items, Boolean.TRUE) != null || seenB.put(other.items, Boolean.TRUE) != null) {
                    yield false;
                }
                for (int n = 0; n < items.size(); n++) {
                    if (!items.get(n).equalsRec(other.items.get(n), seenA, seenB)) {
                        yield false;
                    }
                }
                yield true;
            }
            case Struct -> {
                if (rec == other.rec) {
                    yield true;
                }
                if (rec == null || other.rec == null || !Objects.equals(rec.name, other.rec.name)
                        || rec.fields.size() != other.rec.fields.size()) {
                    yield false;
                }
                if (seenA.put(rec, Boolean.TRUE) != null || seenB.put(other.rec, Boolean.TRUE) != null) {
                    yield false;
                }
                for (Map.Entry<String, Value> kv : rec.fields.entrySet()) {
                    Value rhs = other.rec.fields.get(kv.getKey());
                    if (rhs == null || !kv.getValue().equalsRec(rhs, seenA, seenB)) {
                        yield false;
                    }
                }
                yield true;
            }
            case Map -> {
                if (dict == other.dict) {
                    yield true;
                }
                if (dict == null || other.dict == null || dict.fields.size() != other.dict.fields.size()) {
                    yield false;
                }
                if (seenA.put(dict, Boolean.TRUE) != null || seenB.put(other.dict, Boolean.TRUE) != null) {
                    yield false;
                }
                for (Map.Entry<String, Value> kv : dict.fields.entrySet()) {
                    Value rhs = other.dict.fields.get(kv.getKey());
                    if (rhs == null || !kv.getValue().equalsRec(rhs, seenA, seenB)) {
                        yield false;
                    }
                }
                yield true;
            }
            case SignalRef -> Objects.equals(s, other.s) && rec == other.rec;
            case Future -> fut == other.fut;
            case Enum -> {
                if (!Objects.equals(s, other.s) || !Objects.equals(variant, other.variant)) {
                    yield false;
                }
                int n = payload == null ? 0 : payload.size();
                int m = other.payload == null ? 0 : other.payload.size();
                if (n != m) {
                    yield false;
                }
                for (int i = 0; i < n; i++) {
                    if (!payload.get(i).equalsRec(other.payload.get(i), seenA, seenB)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    String toPrintString() {
        return toStringRec(new IdentityHashMap<>());
    }

    private String toStringRec(IdentityHashMap<Object, Boolean> seen) {
        return switch (kind) {
            case Void -> "";
            case Bool -> Boolean.toString(b);
            case Int -> Long.toString(i);
            case Float -> {
                if (real == Math.rint(real) && !Double.isInfinite(real)) {
                    yield Long.toString((long) real);
                }
                yield Double.toString(real).replaceAll("\\.0$", "");
            }
            case String -> s;
            case FnRef -> clo != null || s.isEmpty() ? "<fn>" : s;
            case Range -> i + (inclusive ? "..=" : "..") + rangeEnd;
            case Array -> {
                if (items == null) {
                    yield "[]";
                }
                if (seen.put(items, Boolean.TRUE) != null) {
                    yield "[...]";
                }
                StringBuilder out = new StringBuilder("[");
                for (int n = 0; n < items.size(); n++) {
                    if (n > 0) {
                        out.append(", ");
                    }
                    out.append(items.get(n).toStringRec(seen));
                }
                out.append("]");
                yield out.toString();
            }
            case Struct -> {
                if (rec == null) {
                    yield "{}";
                }
                if (seen.put(rec, Boolean.TRUE) != null) {
                    yield rec.name + " { ... }";
                }
                StringBuilder parts = new StringBuilder();
                for (String field : rec.order) {
                    Value v = rec.fields.get(field);
                    if (v == null) {
                        continue;
                    }
                    if (!parts.isEmpty()) {
                        parts.append(", ");
                    }
                    parts.append(field).append(": ").append(v.toStringRec(seen));
                }
                yield rec.name + " { " + parts + " }";
            }
            case Map -> {
                if (dict == null) {
                    yield "{}";
                }
                if (seen.put(dict, Boolean.TRUE) != null) {
                    yield "{...}";
                }
                StringBuilder out = new StringBuilder("{");
                for (int n = 0; n < dict.order.size(); n++) {
                    String k = dict.order.get(n);
                    Value v = dict.fields.get(k);
                    if (v == null) {
                        continue;
                    }
                    if (n > 0) {
                        out.append(", ");
                    }
                    out.append("\"").append(k).append("\": ").append(v.toStringRec(seen));
                }
                out.append("}");
                yield out.toString();
            }
            case SignalRef -> rec != null ? rec.name + "." + s : s;
            case Future -> "<Future>";
            case EnumType -> "enum " + s;
            case Enum -> {
                String out = s + "." + variant;
                if (payload == null || payload.isEmpty()) {
                    yield out;
                }
                StringBuilder parts = new StringBuilder(out).append("(");
                for (int i = 0; i < payload.size(); i++) {
                    if (i > 0) {
                        parts.append(", ");
                    }
                    parts.append(payload.get(i).toStringRec(seen));
                }
                yield parts.append(")").toString();
            }
        };
    }

    private static boolean numericEq(double a, double b) {
        return a == b || Math.abs(a - b) < 1e-9;
    }
}

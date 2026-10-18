package com.rosegoldc.lang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Godot {@code extension_api.json} into RoseGold class stubs.
 * Vector2 is emitted as {@code Vec2}. Godot {@code Array} and {@code Dictionary}
 * become {@code Array} and {@code Map}. {@code Signal} is a value with {@code name} and
 * {@code object_id}. {@code Variant} is left untyped so the held value passes through.
 * Engine singletons are emitted as {@code singleton Name} values.
 * The extension copies {@code Node2D.position},
 * {@code rotation}, and {@code scale}. Engine methods forward to the live node.
 */
public final class GodotApi {

    private static final Set<String> KEYWORDS = Set.of(
            "fn", "import", "from", "as", "pub", "abstract", "final", "private", "protected",
            "mod", "class", "trait", "extends", "for", "in", "super", "enum", "match", "switch",
            "var", "const", "export", "struct", "data", "impl", "signal", "return", "pass", "break",
            "continue", "if", "elif", "else", "while", "try", "do", "throws", "throw", "catch",
            "async", "await", "true", "false", "null", "self", "static");

    private static final Set<String> SKIP_BUILTIN = Set.of(
            "Nil", "void", "bool", "int", "float", "String", "StringName", "NodePath",
            "RID", "Callable", "Signal", "Variant", "Array", "Dictionary",
            "PackedByteArray", "PackedInt32Array", "PackedInt64Array",
            "PackedFloat32Array", "PackedFloat64Array", "PackedStringArray",
            "PackedVector2Array", "PackedVector3Array", "PackedColorArray", "PackedVector4Array");

    private GodotApi() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: GodotApi extension_api.json lib.rg");
            System.exit(2);
        }
        String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        Files.writeString(Path.of(args[1]), generate(json), StandardCharsets.UTF_8);
    }

    static String generate(String json) {
        Map<String, Object> root = Json.parseObject(json);
        Map<String, String> types = new LinkedHashMap<>();
        types.put("bool", "Bool");
        types.put("int", "Int");
        types.put("float", "Float");
        types.put("String", "String");
        types.put("StringName", "String");
        types.put("NodePath", "String");
        Map<String, List<Field>> shapes = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        out.append("# Generated from Godot extension_api.json.\n");
        out.append("# Node2D.position, rotation, and scale are copied to and from the live node.\n");
        out.append("# Array and Dictionary values pass through engine calls as Array and Map.\n");
        out.append("# Packed arrays pass through as Array[Int], Array[Float], Array[String], Array[Vec2], Array[Vector3], Array[Color], or Array[Vector4].\n");
        out.append("# RID values pass through as Int. Callable values pass through as functions.\n");
        out.append("# Signal values pass through as Signal. Variant values pass through as the value they hold.\n");
        out.append("# Engine singletons such as Input, Time, and Engine are values of those classes.\n");
        out.append("# Engine methods forward to that node.\n");
        out.append("# Static methods are called on the class, as in Image.create_empty(...).\n");
        out.append("# A method ending in ... accepts extra arguments, as in call(\"set_meta\", \"n\", 7).\n");
        out.append("# Callable methods such as bind are called on a function, and a Callable from the engine stays a function.\n");
        out.append("# Value types such as Vec2 and Color keep their engine methods, as in position.lerp(target, 0.5).\n\n");
        emitSingletons(out, listOf(root.get("singletons")));
        out.append("class Signal {\n");
        out.append("    var name: String = \"\";\n");
        out.append("    var object_id: Int = 0;\n");
        out.append("}\n\n");
        types.put("Signal", "Signal");
        shapes.put("Signal", List.of(new Field("name", "String"), new Field("object_id", "Int")));
        emitBuiltins(out, listOf(root.get("builtin_classes")), types, shapes);
        emitClasses(out, listOf(root.get("classes")), types, shapes);
        return out.toString();
    }

    private static void emitSingletons(StringBuilder out, List<Object> singletons) {
        boolean any = false;
        for (Object item : singletons) {
            Map<String, Object> row = mapOf(item);
            if (row == null) {
                continue;
            }
            String name = safe(str(row.get("name")));
            if (name.isEmpty()) {
                continue;
            }
            out.append("singleton ").append(name).append(";\n");
            any = true;
        }
        if (any) {
            out.append('\n');
        }
    }

    private static void emitFnClass(StringBuilder out, Map<String, Object> callable, Map<String, String> types, Map<String, List<Field>> shapes) {
        List<Object> methods = normalizeBuiltinMethods(callable.get("methods"));
        if (methods.isEmpty()) {
            return;
        }
        Map<String, Object> cls = new LinkedHashMap<>();
        cls.put("methods", methods);
        emitEngine(out, "Fn", "", cls, types, shapes);
    }

    private static void emitBuiltins(StringBuilder out, List<Object> builtins, Map<String, String> types, Map<String, List<Field>> shapes) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Object item : builtins) {
            Map<String, Object> cls = mapOf(item);
            if (cls == null) {
                continue;
            }
            String name = str(cls.get("name"));
            if ("Callable".equals(name)) {
                emitFnClass(out, cls, types, shapes);
                continue;
            }
            if (name.isEmpty() || SKIP_BUILTIN.contains(name)) {
                continue;
            }
            byName.put(name, cls);
        }
        Set<String> emitted = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Map<String, Object>> e : byName.entrySet()) {
                if (emitted.contains(e.getKey())) {
                    continue;
                }
                List<Field> fields = fieldsOf(e.getValue(), types);
                if (fields == null) {
                    continue;
                }
                String rg = "Vector2".equals(e.getKey()) ? "Vec2" : e.getKey();
                shapes.put(rg, fields);
                types.put(e.getKey(), rg);
                emitted.add(e.getKey());
                progress = true;
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : byName.entrySet()) {
            if (!emitted.contains(e.getKey())) {
                continue;
            }
            String rg = "Vector2".equals(e.getKey()) ? "Vec2" : e.getKey();
            emitValue(out, rg, e.getValue(), shapes.get(rg), types, shapes);
        }
    }

    private static void emitClasses(StringBuilder out, List<Object> classes, Map<String, String> types, Map<String, List<Field>> shapes) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Object item : classes) {
            Map<String, Object> cls = mapOf(item);
            if (cls == null) {
                continue;
            }
            String name = str(cls.get("name"));
            if (!ident(name) || KEYWORDS.contains(name)) {
                continue;
            }
            byName.put(name, cls);
            types.put(name, name);
        }
        Set<String> emitted = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Map<String, Object>> e : byName.entrySet()) {
                if (emitted.contains(e.getKey())) {
                    continue;
                }
                String parent = str(e.getValue().get("inherits"));
                if (!parent.isEmpty() && !emitted.contains(parent)) {
                    continue;
                }
                emitEngine(out, e.getKey(), parent, e.getValue(), types, shapes);
                emitted.add(e.getKey());
                progress = true;
            }
        }
    }

    private static void emitValue(StringBuilder out, String name, Map<String, Object> cls, List<Field> fields, Map<String, String> types, Map<String, List<Field>> shapes) {
        out.append("class ").append(name).append(" {\n");
        Set<String> taken = new LinkedHashSet<>();
        for (Field f : fields) {
            out.append("    var ").append(f.name).append(": ").append(f.type).append(" = ").append(literal(f.type, shapes)).append(";\n");
            taken.add(f.name);
        }
        Map<String, Object> methods = new LinkedHashMap<>();
        methods.put("methods", normalizeBuiltinMethods(cls.get("methods")));
        writeMethods(out, methods, types, shapes, taken);
        out.append("}\n\n");
    }

    private static void emitEngine(StringBuilder out, String name, String parent, Map<String, Object> cls, Map<String, String> types, Map<String, List<Field>> shapes) {
        out.append("class ").append(name);
        if (!parent.isEmpty()) {
            out.append(" extends ").append(parent);
        }
        out.append(" {\n");
        Set<String> taken = new LinkedHashSet<>();
        List<Field> fields = new ArrayList<>();
        for (Object item : listOf(cls.get("properties"))) {
            Map<String, Object> prop = mapOf(item);
            if (prop == null) {
                continue;
            }
            String propName = safe(str(prop.get("name")));
            String type = mapType(str(prop.get("type")), types);
            if (propName.isEmpty() || type == null || type.isEmpty() || !taken.add(propName)) {
                continue;
            }
            if ("Fn".equals(type)) {
                taken.remove(propName);
                continue;
            }
            fields.add(new Field(propName, type));
        }
        List<Sig> signals = new ArrayList<>();
        for (Object item : listOf(cls.get("signals"))) {
            Map<String, Object> sig = mapOf(item);
            if (sig == null) {
                continue;
            }
            String sigName = safe(str(sig.get("name")));
            if (sigName.isEmpty() || !taken.add(sigName)) {
                continue;
            }
            List<Field> args = argsOf(sig.get("arguments"), types);
            if (args == null || hasFn(args)) {
                taken.remove(sigName);
                continue;
            }
            signals.add(new Sig(sigName, args));
        }
        for (Field f : fields) {
            if (shapes.containsKey(f.type) || isPrimitive(f.type) || isCollection(f.type)) {
                out.append("    var ").append(f.name).append(": ").append(f.type).append(" = ").append(literal(f.type, shapes)).append(";\n");
            } else {
                out.append("    @optional var ").append(f.name).append(": ").append(f.type).append(";\n");
            }
        }
        for (Sig s : signals) {
            out.append("    signal ").append(s.name).append('(');
            writeArgs(out, s.args);
            out.append(");\n");
        }
        writeMethods(out, cls, types, shapes, taken);
        out.append("}\n\n");
    }

    private static void writeMethods(StringBuilder out, Map<String, Object> cls, Map<String, String> types, Map<String, List<Field>> shapes, Set<String> taken) {
        List<Meth> methods = new ArrayList<>();
        for (Object item : listOf(cls.get("methods"))) {
            Map<String, Object> method = mapOf(item);
            if (method == null || bool(method.get("is_virtual")) || bool(method.get("is_static"))) {
                continue;
            }
            String methodName = safe(str(method.get("name")));
            if (methodName.isEmpty() || !taken.add(methodName)) {
                continue;
            }
            List<Field> args = argsOf(method.get("arguments"), types);
            String ret = method.get("return_value") == null ? "" : mapType(str(mapOf(method.get("return_value")).get("type")), types);
            if (args == null || (method.get("return_value") != null && ret == null)) {
                taken.remove(methodName);
                continue;
            }
            methods.add(new Meth(methodName, args, ret == null ? "" : ret, false, bool(method.get("is_vararg"))));
        }
        for (Object item : listOf(cls.get("methods"))) {
            Map<String, Object> method = mapOf(item);
            if (method == null || !bool(method.get("is_static")) || bool(method.get("is_virtual"))) {
                continue;
            }
            String methodName = safe(str(method.get("name")));
            if (methodName.isEmpty() || !taken.add(methodName)) {
                continue;
            }
            List<Field> args = argsOf(method.get("arguments"), types);
            String ret = method.get("return_value") == null ? "" : mapType(str(mapOf(method.get("return_value")).get("type")), types);
            if (args == null || (method.get("return_value") != null && ret == null)) {
                taken.remove(methodName);
                continue;
            }
            methods.add(new Meth(methodName, args, ret == null ? "" : ret, true, bool(method.get("is_vararg"))));
        }
        for (Meth m : methods) {
            out.append("    ");
            if (m.isStatic()) {
                out.append("static ");
            }
            out.append("fn ").append(m.name).append('(');
            writeArgs(out, m.args);
            if (m.vararg()) {
                if (!m.args().isEmpty()) {
                    out.append(", ");
                }
                out.append("...");
            }
            out.append(')');
            if (!m.returns.isEmpty()) {
                out.append(": ").append(m.returns).append(" { return ").append(literal(m.returns, shapes)).append("; }");
            } else {
                out.append(" {}");
            }
            out.append('\n');
        }
    }

    private static List<Object> normalizeBuiltinMethods(Object raw) {
        List<Object> methods = new ArrayList<>();
        for (Object item : listOf(raw)) {
            Map<String, Object> method = mapOf(item);
            if (method == null) {
                continue;
            }
            if (method.get("return_value") != null || method.get("return_type") == null) {
                methods.add(method);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(method);
            String ret = str(method.get("return_type"));
            if (!ret.isEmpty() && !"Nil".equals(ret) && !"void".equals(ret)) {
                copy.put("return_value", Map.of("type", ret));
            }
            methods.add(copy);
        }
        return methods;
    }

    private static void writeArgs(StringBuilder out, List<Field> args) {
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(args.get(i).name);
            if (!args.get(i).type.isEmpty()) {
                out.append(": ").append(args.get(i).type);
            }
            if (args.get(i).literal != null && !args.get(i).literal.isEmpty()) {
                out.append(" = ").append(args.get(i).literal);
            }
        }
    }

    private static List<Field> fieldsOf(Map<String, Object> cls, Map<String, String> types) {
        List<Object> members = listOf(cls.get("members"));
        if (members.isEmpty()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (Object item : members) {
            Map<String, Object> member = mapOf(item);
            if (member == null) {
                return null;
            }
            String name = safe(str(member.get("name")));
            String type = mapType(str(member.get("type")), types);
            if (name.isEmpty() || type == null || !taken.add(name)) {
                return null;
            }
            fields.add(new Field(name, type));
        }
        return fields;
    }

    private static String roseDefault(String raw, String rgType) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.equals("null")) {
            return "null";
        }
        if (s.equals("true") || s.equals("false") || s.equals("[]") || s.equals("{}")) {
            return s;
        }
        if (s.equals("&\"\"") || s.equals("\"\"")) {
            return "\"\"";
        }
        if (s.startsWith("&\"") && s.endsWith("\"")) {
            return s.substring(1);
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s;
        }
        if (s.equals("RID()")) {
            return "0";
        }
        if (isNumber(s)) {
            if (s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
                try {
                    String plain = new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
                    if (plain.indexOf('.') < 0) {
                        plain = plain + ".0";
                    }
                    return plain;
                } catch (NumberFormatException ex) {
                    return "";
                }
            }
            if ("Float".equals(rgType) && s.indexOf('.') < 0) {
                return s + ".0";
            }
            return s;
        }
        int paren = s.indexOf('(');
        if (paren <= 0 || !s.endsWith(")")) {
            return "";
        }
        String ctor = s.substring(0, paren);
        String inner = s.substring(paren + 1, s.length() - 1).trim();
        if (ctor.startsWith("Packed") && ctor.endsWith("Array") && inner.isEmpty()) {
            return "[]";
        }
        if (ctor.startsWith("Array[") && (inner.isEmpty() || inner.equals("[]"))) {
            return "[]";
        }
        if ((ctor.equals("Array") || ctor.equals("Dictionary")) && (inner.isEmpty() || inner.equals("[]") || inner.equals("{}"))) {
            return ctor.equals("Dictionary") ? "{}" : "[]";
        }
        if (ctor.equals("NodePath") || ctor.equals("StringName")) {
            if (inner.equals("&\"\"") || inner.equals("\"\"") || inner.isEmpty()) {
                return "\"\"";
            }
            if (inner.startsWith("\"") && inner.endsWith("\"")) {
                return inner;
            }
            return "";
        }
        List<String> parts = splitArgs(inner);
        boolean floating = !ctor.endsWith("i");
        return switch (ctor) {
            case "Vector2" -> vec("Vec2", parts, true, "x", "y");
            case "Vector2i" -> vec("Vector2i", parts, false, "x", "y");
            case "Vector3" -> vec("Vector3", parts, true, "x", "y", "z");
            case "Vector3i" -> vec("Vector3i", parts, false, "x", "y", "z");
            case "Vector4" -> vec("Vector4", parts, true, "x", "y", "z", "w");
            case "Vector4i" -> vec("Vector4i", parts, false, "x", "y", "z", "w");
            case "Quaternion" -> vec("Quaternion", parts.isEmpty() ? List.of("0", "0", "0", "1") : parts, true, "x", "y", "z", "w");
            case "Color" -> colorDefault(parts);
            case "Rect2" -> rectDefault(parts);
            case "Transform2D" -> transform2dDefault(parts);
            case "Plane" -> vec("Plane", parts, true, "x", "y", "z", "d");
            default -> "";
        };
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') {
            i = 1;
        }
        if (i >= s.length()) {
            return false;
        }
        boolean digit = false;
        boolean dot = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c == '.' && !dot) {
                dot = true;
            } else if (c == 'e' || c == 'E') {
                return digit && i + 1 < s.length();
            } else {
                return false;
            }
        }
        return digit;
    }

    private static List<String> splitArgs(String inner) {
        List<String> out = new ArrayList<>();
        if (inner.isEmpty()) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(inner.substring(start).trim());
        return out;
    }

    private static String num(String s, boolean floating) {
        s = s.trim();
        if (floating && isNumber(s) && s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            return s + ".0";
        }
        return s;
    }

    private static String vec(String type, List<String> parts, boolean floating, String... names) {
        if (parts.size() != names.length) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(type).append(" { ");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            if (!isNumber(parts.get(i).trim())) {
                return "";
            }
            b.append(names[i]).append(": ").append(num(parts.get(i), floating));
        }
        b.append(" }");
        return b.toString();
    }

    private static String colorDefault(List<String> parts) {
        if (parts.size() == 3) {
            parts = new ArrayList<>(parts);
            parts.add("1");
        }
        return vec("Color", parts, true, "r", "g", "b", "a");
    }

    private static String rectDefault(List<String> parts) {
        if (parts.size() != 4) {
            return "";
        }
        String pos = vec("Vec2", List.of(parts.get(0), parts.get(1)), true, "x", "y");
        String size = vec("Vec2", List.of(parts.get(2), parts.get(3)), true, "x", "y");
        if (pos.isEmpty() || size.isEmpty()) {
            return "";
        }
        return "Rect2 { position: " + pos + ", size: " + size + " }";
    }

    private static String transform2dDefault(List<String> parts) {
        if (parts.size() != 6) {
            return "";
        }
        String x = vec("Vec2", List.of(parts.get(0), parts.get(1)), true, "x", "y");
        String y = vec("Vec2", List.of(parts.get(2), parts.get(3)), true, "x", "y");
        String origin = vec("Vec2", List.of(parts.get(4), parts.get(5)), true, "x", "y");
        if (x.isEmpty() || y.isEmpty() || origin.isEmpty()) {
            return "";
        }
        return "Transform2D { x: " + x + ", y: " + y + ", origin: " + origin + " }";
    }

    private static List<Field> argsOf(Object raw, Map<String, String> types) {
        List<Field> args = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        int n = 0;
        for (Object item : listOf(raw)) {
            Map<String, Object> arg = mapOf(item);
            if (arg == null) {
                return null;
            }
            String type = mapType(str(arg.get("type")), types);
            if (type == null) {
                return null;
            }
            String name = safe(str(arg.get("name")));
            if (name.isEmpty()) {
                name = "a" + n;
            }
            while (!taken.add(name)) {
                name = name + "_";
            }
            args.add(new Field(name, type, ""));
            n++;
        }
        String[] lits = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Map<String, Object> arg = mapOf(listOf(raw).get(i));
            lits[i] = roseDefault(str(arg.get("default_value")), args.get(i).type);
        }
        int tail = lits.length;
        while (tail > 0 && !lits[tail - 1].isEmpty()) {
            tail--;
        }
        for (int i = 0; i < tail; i++) {
            lits[i] = "";
        }
        List<Field> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            out.add(new Field(args.get(i).name, args.get(i).type, lits[i]));
        }
        return out;
    }

    private static String mapType(String godot, Map<String, String> types) {
        if (godot == null || godot.isEmpty() || "Nil".equals(godot) || "void".equals(godot)) {
            return null;
        }
        if (godot.startsWith("enum::") || godot.startsWith("bitfield::")) {
            return "Int";
        }
        if ("Array".equals(godot)) {
            return "Array";
        }
        if ("Dictionary".equals(godot)) {
            return "Map";
        }
        if ("PackedByteArray".equals(godot) || "PackedInt32Array".equals(godot) || "PackedInt64Array".equals(godot)) {
            return "Array[Int]";
        }
        if ("PackedFloat32Array".equals(godot) || "PackedFloat64Array".equals(godot)) {
            return "Array[Float]";
        }
        if ("PackedStringArray".equals(godot)) {
            return "Array[String]";
        }
        if ("PackedVector2Array".equals(godot)) {
            return "Array[Vec2]";
        }
        if ("PackedVector3Array".equals(godot)) {
            return "Array[Vector3]";
        }
        if ("PackedColorArray".equals(godot)) {
            return "Array[Color]";
        }
        if ("PackedVector4Array".equals(godot)) {
            return "Array[Vector4]";
        }
        if ("RID".equals(godot)) {
            return "Int";
        }
        if ("Callable".equals(godot)) {
            return "Fn";
        }
        if ("Signal".equals(godot)) {
            return "Signal";
        }
        if ("Variant".equals(godot)) {
            return "";
        }
        if (godot.startsWith("typedarray::")) {
            return arrayType(mapType(godot.substring("typedarray::".length()), types));
        }
        if (godot.endsWith("[]")) {
            return arrayType(mapType(godot.substring(0, godot.length() - 2), types));
        }
        return types.get(godot);
    }

    private static String arrayType(String inner) {
        if (inner == null || inner.isEmpty() || inner.indexOf('[') >= 0 || inner.indexOf(' ') >= 0) {
            return "Array";
        }
        return "Array[" + inner + "]";
    }

    private static boolean hasFn(List<Field> args) {
        for (Field arg : args) {
            if ("Fn".equals(arg.type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrimitive(String type) {
        return "Bool".equals(type) || "Int".equals(type) || "Float".equals(type) || "String".equals(type);
    }

    private static boolean isCollection(String type) {
        return "Array".equals(type) || "Map".equals(type)
                || (type.startsWith("Array[") && type.endsWith("]"))
                || (type.startsWith("Map[") && type.endsWith("]"));
    }

    private static String literal(String type, Map<String, List<Field>> shapes) {
        return literal(type, shapes, 0);
    }

    private static String literal(String type, Map<String, List<Field>> shapes, int depth) {
        if (isCollection(type)) {
            return type.startsWith("Map") ? "{}" : "[]";
        }
        switch (type) {
            case "Bool" -> {
                return "false";
            }
            case "Int" -> {
                return "0";
            }
            case "Float" -> {
                return "0.0";
            }
            case "String" -> {
                return "\"\"";
            }
            case "Fn" -> {
                return "fn () {}";
            }
            default -> {
                List<Field> fields = shapes.get(type);
                if (fields == null || depth > 3) {
                    return type + " {}";
                }
                StringBuilder b = new StringBuilder();
                b.append(type).append(" { ");
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) {
                        b.append(", ");
                    }
                    b.append(fields.get(i).name).append(": ").append(literal(fields.get(i).type, shapes, depth + 1));
                }
                b.append(" }");
                return b.toString();
            }
        }
    }

    private static String safe(String name) {
        if (!ident(name) || KEYWORDS.contains(name)) {
            return "";
        }
        return name;
    }

    private static boolean ident(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char c = name.charAt(0);
        if (!(c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char d = name.charAt(i);
            if (!(d == '_' || (d >= 'A' && d <= 'Z') || (d >= 'a' && d <= 'z') || (d >= '0' && d <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : "";
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b && b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object v) {
        return v instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private record Field(String name, String type, String literal) {
        Field(String name, String type) {
            this(name, type, "");
        }
    }

    private record Sig(String name, List<Field> args) {
    }

    private record Meth(String name, List<Field> args, String returns, boolean isStatic, boolean vararg) {
        Meth(String name, List<Field> args, String returns) {
            this(name, args, returns, false, false);
        }
    }

    private static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Map<String, Object> parseObject(String s) {
            Json json = new Json(s);
            Object v = json.value();
            if (!(v instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("extension_api.json must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            return map;
        }

        private Object value() {
            skip();
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of json");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't' || c == 'f') {
                return bool();
            }
            if (c == 'n') {
                i += 4;
                return null;
            }
            return number();
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("expected ':'");
                }
                i++;
                map.put(key, value());
                skip();
                if (peek('}')) {
                    i++;
                    return map;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("expected ',' or '}'");
                }
                i++;
            }
        }

        private List<Object> array() {
            i++;
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                if (peek(']')) {
                    i++;
                    return list;
                }
                if (!peek(',')) {
                    throw new IllegalArgumentException("expected ',' or ']'");
                }
                i++;
            }
        }

        private String string() {
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected string");
            }
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                        b.append((char) cp);
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Boolean bool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("expected bool");
        }

        private String number() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    i++;
                } else {
                    break;
                }
            }
            return s.substring(start, i);
        }

        private void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    return;
                }
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }
    }
}

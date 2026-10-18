package com.rosegoldc.lang;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GodotApiTest {

    @Test
    public void emitsMappedClassesAndSkipsUnmappedMembers() {
        String json = """
                {
                  "builtin_classes": [
                    {"name": "Callable", "methods": [
                      {"name": "bind", "is_vararg": true, "return_type": "Callable"},
                      {"name": "unbind", "return_type": "Callable", "arguments": [{"name": "argcount", "type": "int"}]}
                    ]},
                    {"name": "Vector2", "members": [{"name": "x", "type": "float"}, {"name": "y", "type": "float"}], "methods": [
                      {"name": "lerp", "return_type": "Vector2", "arguments": [
                        {"name": "to", "type": "Vector2"},
                        {"name": "weight", "type": "float"}
                      ]},
                      {"name": "from_angle", "is_static": true, "return_type": "Vector2", "arguments": [
                        {"name": "angle", "type": "float"}
                      ]}
                    ]},
                    {"name": "String", "members": []}
                  ],
                  "singletons": [
                    {"name": "Input", "type": "Input"},
                    {"name": "Time", "type": "Time"}
                  ],
                  "classes": [
                    {"name": "Object", "methods": [
                      {"name": "get_class", "return_value": {"type": "String"}},
                      {"name": "call", "is_vararg": true, "return_value": {"type": "Variant"}, "arguments": [
                        {"name": "method", "type": "StringName"}
                      ]},
                      {"name": "_notification", "is_virtual": true, "arguments": [{"name": "what", "type": "int"}]}
                    ]},
                    {"name": "Node", "inherits": "Object", "methods": [
                      {"name": "queue_free"},
                      {"name": "make_callback", "return_value": {"type": "Callable"}},
                      {"name": "get_child", "return_value": {"type": "PackedByteArray"}},
                      {"name": "get_names", "return_value": {"type": "PackedStringArray"}},
                      {"name": "set_polygon", "arguments": [{"name": "polygon", "type": "PackedVector2Array"}]},
                      {"name": "get_children", "return_value": {"type": "typedarray::Node"}},
                      {"name": "get_canvas_item", "return_value": {"type": "RID"}},
                      {"name": "connect", "return_value": {"type": "enum::Error"}, "arguments": [
                        {"name": "signal", "type": "StringName"},
                        {"name": "callable", "type": "Callable"},
                        {"name": "flags", "type": "int"}
                      ]},
                      {"name": "rpc_config", "arguments": [
                        {"name": "method", "type": "StringName"},
                        {"name": "config", "type": "Dictionary"}
                      ]},
                      {"name": "get_meta", "return_value": {"type": "Variant"}, "arguments": [
                        {"name": "name", "type": "StringName"},
                        {"name": "default", "type": "Variant", "default_value": "null"}
                      ]},
                      {"name": "is_action_pressed", "return_value": {"type": "bool"}, "arguments": [
                        {"name": "action", "type": "StringName"},
                        {"name": "exact_match", "type": "bool", "default_value": "false"}
                      ]},
                      {"name": "held_signal", "return_value": {"type": "Signal"}}
                    ], "signals": [{"name": "ready"}]},
                    {"name": "CanvasItem", "inherits": "Node"},
                    {"name": "Image", "inherits": "Object", "methods": [
                      {"name": "get_width", "return_value": {"type": "int"}},
                      {"name": "create_empty", "is_static": true, "return_value": {"type": "Image"}, "arguments": [
                        {"name": "width", "type": "int"},
                        {"name": "height", "type": "int"},
                        {"name": "use_mipmaps", "type": "bool"},
                        {"name": "format", "type": "int"}
                      ]},
                      {"name": "load_from_file", "is_static": true, "is_vararg": true, "return_value": {"type": "int"}}
                    ]},
                    {"name": "Node2D", "inherits": "CanvasItem", "properties": [
                      {"name": "position", "type": "Vector2"},
                      {"name": "texture", "type": "Texture2D"},
                      {"name": "meta", "type": "Dictionary"}
                    ]}
                  ]
                }
                """;
        String rg = GodotApi.generate(json);
        assertTrue(rg, rg.contains("singleton Input;"));
        assertTrue(rg, rg.contains("singleton Time;"));
        assertTrue(rg, rg.contains("class Vec2 {"));
        assertTrue(rg, rg.contains("var x: Float = 0.0;"));
        assertTrue(rg, rg.contains("fn lerp(to: Vec2, weight: Float): Vec2 { return Vec2 { x: 0.0, y: 0.0 }; }"));
        assertTrue(rg, rg.contains("static fn from_angle(angle: Float): Vec2 { return Vec2 { x: 0.0, y: 0.0 }; }"));
        assertTrue(rg, rg.contains("class Node2D extends CanvasItem {"));
        assertTrue(rg, rg.contains("var position: Vec2 = Vec2 { x: 0.0, y: 0.0 };"));
        assertTrue(rg, rg.contains("static fn create_empty(width: Int, height: Int, use_mipmaps: Bool, format: Int): Image { return Image {}; }"));
        assertTrue(rg, rg.contains("fn get_width(): Int { return 0; }"));
        assertTrue(rg, rg.contains("static fn load_from_file(...): Int { return 0; }"));
        assertTrue(rg, rg.contains("fn queue_free() {}"));
        assertTrue(rg, rg.contains("fn get_class(): String { return \"\"; }"));
        assertTrue(rg, rg.contains("fn call(method: String, ...) {}"));
        assertTrue(rg, rg.contains("class Fn {"));
        assertTrue(rg, rg.contains("fn bind(...): Fn { return fn () {}; }"));
        assertTrue(rg, rg.contains("fn unbind(argcount: Int): Fn { return fn () {}; }"));
        assertTrue(rg, rg.contains("fn make_callback(): Fn { return fn () {}; }"));
        assertTrue(rg, rg.contains("fn get_child(): Array[Int] { return []; }"));
        assertTrue(rg, rg.contains("fn get_names(): Array[String] { return []; }"));
        assertTrue(rg, rg.contains("fn set_polygon(polygon: Array[Vec2]) {}"));
        assertTrue(rg, rg.contains("fn get_children(): Array[Node] { return []; }"));
        assertTrue(rg, rg.contains("fn get_canvas_item(): Int { return 0; }"));
        assertTrue(rg, rg.contains("fn connect(a0: String, callable: Fn, flags: Int): Int { return 0; }"));
        assertTrue(rg, rg.contains("class Signal {"));
        assertTrue(rg, rg.contains("var object_id: Int = 0;"));
        assertTrue(rg, rg.contains("fn rpc_config(method: String, config: Map) {}"));
        assertTrue(rg, rg.contains("fn get_meta(name: String, default = null) {}"));
        assertTrue(rg, rg.contains("fn is_action_pressed(action: String, exact_match: Bool = false): Bool { return false; }"));
        assertTrue(rg, rg.contains("fn held_signal(): Signal { return Signal { name: \"\", object_id: 0 }; }"));
        assertTrue(rg, rg.contains("var meta: Map = {};"));
        assertTrue(rg, rg.contains("signal ready();"));
        assertFalse(rg, rg.contains("_notification"));
        assertFalse(rg, rg.contains("texture"));
        assertFalse(rg, rg.contains("class String"));
        assertFalse(rg, rg.contains("class Array"));
        assertFalse(rg, rg.contains("class Map"));
        List<Diagnostic> diags = new ArrayList<>();
        Parser.parseSource(rg + "\nclass Player extends Node2D {\n    var speed: Float = 200.0;\n    fn _process(delta: Float) { position.x = position.x + speed * delta; }\n}\n", "gen.rg", diags);
        assertTrue(diags.toString(), diags.isEmpty());
    }
}

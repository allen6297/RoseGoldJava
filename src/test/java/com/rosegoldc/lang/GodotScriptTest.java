package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GodotScriptTest {

    @Test
    public void playerManifestAndIr() throws Exception {
        String source = Files.readString(Path.of("examples/godot_player.rg"), StandardCharsets.UTF_8);
        Checker checker = check(source, "examples/godot_player.rg");
        GodotScript.Model model = GodotScript.model(checker);
        assertNotNull(model);
        assertEquals("Player", model.className);
        assertEquals("Node2D", model.base);
        assertEquals(2, model.methods.size());
        assertEquals("_ready", model.methods.get(0).name);
        assertTrue(model.methods.get(0).params.isEmpty());
        assertEquals("_process", model.methods.get(1).name);
        assertEquals(1, model.methods.get(1).params.size());
        assertEquals("delta", model.methods.get(1).params.get(0).name);
        assertEquals("Float", model.methods.get(1).params.get(0).type);
        assertEquals(1, model.vars.size());
        assertEquals("speed", model.vars.get(0).name);
        assertEquals("Float", model.vars.get(0).type);
        assertEquals("200.0", model.vars.get(0).literal);
        assertEquals(1, model.signals.size());
        assertEquals("died", model.signals.get(0).name);
        String json = GodotScript.manifest(model, "player.dll");
        assertTrue(json, json.contains("\"class\": \"Player\""));
        assertTrue(json, json.contains("\"base\": \"Node2D\""));
        assertTrue(json, json.contains("\"name\": \"speed\""));
        assertTrue(json, json.contains("\"default\": 200.0"));
        assertTrue(json, json.contains("\"name\": \"died\""));
        assertTrue(json, json.contains("\"name\": \"_process\""));
        assertFalse(json, json.contains("\"name\": \"position\""));
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, LlvmLink.godotLowered(ir));
        assertTrue(ir, ir.contains("define void @rg_godot_new("));
        assertTrue(ir, ir.contains("call void @rg_godot_bind("));
        assertTrue(ir, ir.contains("define void @rg_init_methods("));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__process("));
        assertTrue(ir, ir.contains("14 object"));
        assertFalse(ir, ir.contains("define i32 @main("));
    }

    @Test
    public void engineMethodsForward() throws Exception {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        print(get_name());\n"
                + "        var child = get_node(\"Child\");\n"
                + "        child.queue_free();\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_Node_get_name(ptr %out, ptr %args, i32 %argc)"));
        assertTrue(ir, ir.contains("define void @rg_fn_Node_get_node(ptr %out, ptr %args, i32 %argc)"));
        assertTrue(ir, ir.contains("define void @rg_fn_Node_queue_free(ptr %out, ptr %args, i32 %argc)"));
        assertTrue(ir, ir.contains("call void @rg_godot_call(ptr %out, ptr @str"));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void engineNodeProperties() throws Exception {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var child = get_node(\"Child\");\n"
                + "        child.position = Vec2 { x: 40.0, y: 6.0 };\n"
                + "        child.position.x = 41.0;\n"
                + "        child.rotation = 0.25;\n"
                + "        child.scale = Vec2 { x: 2.0, y: 3.0 };\n"
                + "        print(child.position.x);\n"
                + "        print(child.rotation);\n"
                + "        print(child.scale.y);\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_member("));
        assertTrue(ir, ir.contains("call void @rg_field_set("));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void staticMethodOmitsSelf() {
        String source = ""
                + "class Box {\n"
                + "    static fn make(n: Int): Int {\n"
                + "        return n;\n"
                + "    }\n"
                + "}\n"
                + "fn main() {\n"
                + "    print(Box.make(3));\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_Box_make("));
        assertTrue(ir, ir.contains("call void @rg_fn_Box_make("));
    }

    @Test
    public void imageCreateEmptyTypechecks() {
        String source = ""
                + "class Probe extends Node {\n"
                + "    fn _ready() {\n"
                + "        var image = Image.create_empty(8, 4, false, 5);\n"
                + "        print(image.get_width());\n"
                + "        print(image.get_height());\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_godot_static("));
    }

    @Test
    public void varargCallPassesExtraArgs() {
        String source = ""
                + "class Probe extends Node {\n"
                + "    fn _ready() {\n"
                + "        call(\"set_meta\", \"n\", 7);\n"
                + "        print(get_meta(\"n\"));\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_call_method("));
    }

    @Test
    public void bindReturnsCallable() {
        String source = ""
                + "class Probe extends Node {\n"
                + "    signal ping();\n"
                + "    fn _ready() {\n"
                + "        var handler = fn (n: Int) { print(n); };\n"
                + "        ping.connect(handler.bind(7));\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_call_method("));
    }

    @Test
    public void vec2LerpTypechecks() {
        String source = ""
                + "class Probe extends Node {\n"
                + "    fn _ready() {\n"
                + "        var v = Vec2 { x: 0.0, y: 0.0 };\n"
                + "        var w = v.lerp(Vec2 { x: 10.0, y: 0.0 }, 0.5);\n"
                + "        print(w.x);\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_call_method("));
    }

    @Test
    public void defaultArgumentIsPassed() {
        String source = ""
                + "fn greet(name: String, loud: Bool = false) {\n"
                + "    print(name);\n"
                + "    print(loud);\n"
                + "}\n"
                + "fn main() {\n"
                + "    greet(\"hi\");\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_set_bool("));
        assertTrue(ir, ir.contains("call void @rg_fn_greet("));
    }

    @Test
    public void singletonCallTypechecks() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var down = Input.is_action_pressed(\"ui_right\");\n"
                + "        var ticks = Time.get_ticks_msec();\n"
                + "        var meta = get_meta(\"name\");\n"
                + "        print(down);\n"
                + "        print(ticks);\n"
                + "        print(Engine.is_editor_hint());\n"
                + "        print(Input.mouse_mode);\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "godot/demo/player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("call void @rg_godot_singleton("));
    }

    @Test
    public void editorCheckReportsUnknownFunction() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        nope();\n"
                + "    }\n"
                + "}\n";
        List<Diagnostic> diags = Check.checkSource(source, "godot/demo/player.rg");
        boolean found = false;
        for (Diagnostic d : diags) {
            if ("error".equals(d.severity) && d.message != null && d.message.contains("unknown function 'nope'")) {
                found = true;
            }
        }
        assertTrue(diags.toString(), found);
    }

    @Test
    public void colorRectAndTransformForward() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var c = get_modulate();\n"
                + "        set_modulate(c);\n"
                + "        var rect = get_viewport_rect();\n"
                + "        var xf = get_global_transform();\n"
                + "        print(c.r);\n"
                + "        print(rect.size.x);\n"
                + "        print(rect.end.x);\n"
                + "        print(xf.x.x);\n"
                + "        print(xf.origin.x);\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_CanvasItem_get_modulate("));
        assertTrue(ir, ir.contains("define void @rg_fn_CanvasItem_set_modulate("));
        assertTrue(ir, ir.contains("define void @rg_fn_CanvasItem_get_viewport_rect("));
        assertTrue(ir, ir.contains("define void @rg_fn_CanvasItem_get_global_transform("));
        assertTrue(ir, ir.contains("call void @rg_godot_call(ptr %out, ptr @str"));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void arrayAndMapForward() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var kids = get_children(false);\n"
                + "        print(kids.len);\n"
                + "        print(kids[0].get_name());\n"
                + "        var props = get_property_list();\n"
                + "        print(props.len);\n"
                + "        print(props[0][\"name\"]);\n"
                + "        add_user_signal(\"pinged\", [{ \"name\": \"amount\", \"type\": 2 }]);\n"
                + "        print(has_user_signal(\"pinged\"));\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_Node_get_children("));
        assertTrue(ir, ir.contains("define void @rg_fn_Object_get_property_list("));
        assertTrue(ir, ir.contains("define void @rg_fn_Object_add_user_signal("));
        assertTrue(ir, ir.contains("call void @rg_godot_call(ptr %out, ptr @str"));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void packedArrayForward() {
        String source = ""
                + "class Player extends Polygon2D {\n"
                + "    fn _ready() {\n"
                + "        set_polygon([Vec2 { x: 1.0, y: 2.0 }, Vec2 { x: 3.0, y: 4.0 }]);\n"
                + "        var pts = get_polygon();\n"
                + "        print(pts.len);\n"
                + "        print(pts[0].x);\n"
                + "        var tint = get_color();\n"
                + "        set_vertex_colors([tint]);\n"
                + "        var cols = get_vertex_colors();\n"
                + "        print(cols.len);\n"
                + "        print(cols[0].r);\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_Polygon2D_set_polygon("));
        assertTrue(ir, ir.contains("define void @rg_fn_Polygon2D_get_polygon("));
        assertTrue(ir, ir.contains("define void @rg_fn_Polygon2D_set_vertex_colors("));
        assertTrue(ir, ir.contains("define void @rg_fn_Polygon2D_get_vertex_colors("));
        assertTrue(ir, ir.contains("call void @rg_godot_call(ptr %out, ptr @str"));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void ridAndCallableForward() {
        String source = ""
                + "fn hit() {\n"
                + "    print(\"hit\");\n"
                + "}\n"
                + "class Player extends NavigationAgent2D {\n"
                + "    fn _ready() {\n"
                + "        var id = get_rid();\n"
                + "        set_navigation_map(id);\n"
                + "        print(get_navigation_map());\n"
                + "        var clock = get_node(\"Timer\");\n"
                + "        print(clock.connect(\"timeout\", hit, 0));\n"
                + "        print(clock.is_connected(\"timeout\", hit));\n"
                + "        clock.disconnect(\"timeout\", hit);\n"
                + "        print(clock.is_connected(\"timeout\", hit));\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_NavigationAgent2D_get_rid("));
        assertTrue(ir, ir.contains("define void @rg_fn_NavigationAgent2D_set_navigation_map("));
        assertTrue(ir, ir.contains("define void @rg_fn_NavigationAgent2D_get_navigation_map("));
        assertTrue(ir, ir.contains("define void @rg_fn_Object_connect("));
        assertTrue(ir, ir.contains("define void @rg_fn_Object_is_connected("));
        assertTrue(ir, ir.contains("define void @rg_fn_Object_disconnect("));
        assertTrue(ir, ir.contains("call void @rg_godot_call(ptr %out, ptr @str"));
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
    }

    @Test
    public void linksSharedLibrary() throws Exception {
        if (!LlvmLink.canRunClang()) {
            return;
        }
        String source = Files.readString(Path.of("examples/godot_player.rg"), StandardCharsets.UTF_8);
        Checker checker = check(source, "examples/godot_player.rg");
        Path nativeDir = LlvmLink.resolveNativeDir(Path.of("").toAbsolutePath());
        Path out = Path.of("build", "godot-player");
        LlvmLink.Result result = LlvmLink.linkGodot(checker, nativeDir, out);
        assertTrue(result.message, result.ok);
        assertTrue(String.valueOf(result.output), Files.isRegularFile(result.output));
        Path manifest = LlvmLink.manifestBeside(result.output);
        assertTrue(manifest.toString(), Files.isRegularFile(manifest));
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"class\": \"Player\""));
        assertTrue(json, json.contains(result.output.getFileName().toString()));
    }

    @Test
    public void ideCheckLoadsStubsInsideGodotProject() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        print(get_name());\n"
                + "    }\n"
                + "}\n";
        List<Diagnostic> inProject = Check.checkSource(source, "godot/demo/player.rg");
        assertTrue(inProject.toString(), inProject.isEmpty());
        List<Diagnostic> outside = Check.checkSource(source, "examples/godot_player.rg");
        assertFalse(outside.toString(), outside.isEmpty());
    }

    @Test
    public void structIsListedInManifest() {
        String source = ""
                + "struct Hit {\n"
                + "    damage: Float;\n"
                + "    point: Vec2;\n"
                + "}\n"
                + "class Player extends Node2D {\n"
                + "    var hit: Hit;\n"
                + "    fn _ready() {}\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        GodotScript.Model model = GodotScript.model(checker);
        assertNotNull(model);
        String json = GodotScript.manifest(model, "player.dll");
        assertTrue(json, json.contains("\"struct\": \"Hit\""));
        assertTrue(json, json.contains("\"name\": \"damage\""));
        assertTrue(json, json.contains("\"type\": \"Float\""));
        assertTrue(json, json.contains("\"name\": \"point\""));
        assertTrue(json, json.contains("\"type\": \"Vec2\""));
        assertTrue(json, json.contains("\"type\": \"Hit\""));
        assertFalse(json, json.contains("\"data\": true"));
    }

    @Test
    public void dataIsListedInManifest() {
        String source = ""
                + "data Item {\n"
                + "    name: String;\n"
                + "}\n"
                + "struct Hit {\n"
                + "    damage: Float;\n"
                + "}\n"
                + "class Player extends Node2D {\n"
                + "    export var item: Item;\n"
                + "    fn _ready() {}\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        GodotScript.Model model = GodotScript.model(checker);
        assertNotNull(model);
        String json = GodotScript.manifest(model, "player.dll");
        assertTrue(json, json.contains("\"struct\": \"Item\""));
        assertTrue(json, json.contains("\"name\": \"name\""));
        assertTrue(json, json.contains("\"type\": \"String\""));
        assertTrue(json, json.contains("\"data\": true"));
        assertTrue(json, json.contains("\"struct\": \"Hit\""));
        assertEquals(1, json.split("\"data\": true", -1).length - 1);
    }

    @Test
    public void castNodeToEngineType() {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var clock = get_node(\"Timer\") as Timer;\n"
                + "        clock.start(1.0);\n"
                + "        var body = get_node(\"Body\") as Node2D;\n"
                + "        body.position.x = body.position.x + 1.0;\n"
                + "    }\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        String ir = Llvm.emit(checker, false, true);
        assertTrue(ir, ir.contains("define void @rg_fn_Player__ready("));
        assertTrue(ir, ir.contains("rg_call_method"));
        assertTrue(ir, ir.contains("start\\00"));
    }

    @Test
    public void castRejectsNonEngineType() {
        String source = ""
                + "class Actor extends Node2D {\n"
                + "    fn _ready() {}\n"
                + "}\n"
                + "class Player extends Node2D {\n"
                + "    fn _ready() {\n"
                + "        var n = get_node(\"A\") as Actor;\n"
                + "        var v = get_node(\"A\") as Vec2;\n"
                + "        var bad = 1 as Timer;\n"
                + "    }\n"
                + "}\n";
        List<Diagnostic> diags = new ArrayList<>();
        Program program = Parser.parseSource(source, "examples/godot_player.rg", diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Checker.check(program, "examples/godot_player.rg", diags, true);
        String text = diags.toString();
        assertTrue(text, text.contains("can only cast to an engine type"));
        assertTrue(text, text.contains("cannot cast Int to Timer"));
    }

    @Test
    public void exportVarIsListedInManifest() throws Exception {
        String source = ""
                + "class Player extends Node2D {\n"
                + "    export var speed: Float = 200.0;\n"
                + "    var hidden: Int = 1;\n"
                + "    fn _ready() {}\n"
                + "}\n";
        Checker checker = check(source, "examples/godot_player.rg");
        GodotScript.Model model = GodotScript.model(checker);
        assertNotNull(model);
        assertEquals(2, model.vars.size());
        assertTrue(model.vars.get(0).exported);
        assertFalse(model.vars.get(1).exported);
        String json = GodotScript.manifest(model, "player.dll");
        assertEquals(1, json.split("\"export\": true", -1).length - 1);
        List<Diagnostic> diags = new ArrayList<>();
        Parser.parseSource("class Player extends Node2D {\n    export fn _ready() {}\n}\n", "examples/godot_player.rg", diags);
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("export applies to var")));
    }

    @Test
    public void projectScriptSeesSiblingClass() {
        String source = ""
                + "class Player extends Actor {\n"
                + "    fn _process(delta: Float) {\n"
                + "        position.x = position.x + 1.0;\n"
                + "    }\n"
                + "}\n";
        List<Diagnostic> diags = Check.checkSource(source, "godot/demo/player.rg");
        assertTrue(diags.toString(), diags.isEmpty());
    }

    private static Checker check(String source, String path) {
        List<Diagnostic> diags = new ArrayList<>();
        Program program = Parser.parseSource(source, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Checker checker = Checker.check(program, path, diags, true);
        assertTrue(diags.toString(), diags.isEmpty());
        return checker;
    }
}

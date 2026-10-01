package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LlvmTest {

    @Test
    public void helloEmitsNativeMainAndPrint() {
        Checker checker = check("""
                fn main(): Int {
                    print("hello from RoseGold");
                    return 0;
                }
                """, "hello.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("%RGValue = type { i8, i64, ptr }"));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_set_argv("));
        assertTrue(ir, ir.contains("call void @rg_print("));
        assertTrue(ir, ir.contains("call void @rg_set_int("));
        assertTrue(ir, ir.contains("hello from RoseGold"));
        assertTrue(ir, ir.contains("call i64 @rg_to_exit("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void arithmeticAndIfLower() {
        Checker checker = check("""
                fn sign(n: Int): Int {
                    if n > 0 {
                        return 1;
                    }
                    return 0;
                }
                fn main(): Int {
                    print(sign(4));
                    print(1 + 2 * 3);
                    return 0;
                }
                """, "sign.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_sign("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_bin("));
        assertTrue(ir, ir.contains("br i1"));
        assertTrue(ir, ir.contains("call i32 @rg_truthy("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void arraysRangesAndForLower() {
        Checker checker = check("""
                fn main(): Int {
                    for x in 1..3 {
                        print(x);
                    }
                    var xs = [1, 2, 3];
                    xs[1] = 9;
                    print(xs[0]);
                    print(len(xs));
                    return 0;
                }
                """, "loop.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_array("));
        assertTrue(ir, ir.contains("call void @rg_range("));
        assertTrue(ir, ir.contains("call void @rg_iter_items("));
        assertTrue(ir, ir.contains("call void @rg_index_get("));
        assertTrue(ir, ir.contains("call void @rg_index_set("));
        assertTrue(ir, ir.contains("call void @rg_len("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void mapsAndMethodsLower() {
        Checker checker = check("""
                fn main(): Int {
                    var xs = [1, 2, 3];
                    xs.push(4);
                    print(xs.pop());
                    var scores = {"ada": 10, "grace": 12};
                    scores["linus"] = 9;
                    print(scores["grace"]);
                    print(scores.has("ada"));
                    print(len(scores));
                    print(scores.keys().len());
                    return 0;
                }
                """, "map.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_map("));
        assertTrue(ir, ir.contains("call void @rg_method("));
        assertTrue(ir, ir.contains("call void @rg_index_get("));
        assertTrue(ir, ir.contains("call void @rg_index_set("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void structsFieldsAndSelfLower() {
        Checker checker = check("""
                struct Point {
                    x: Int;
                    y: Int;

                    fn mag2(): Int {
                        return x * x + y * y;
                    }
                }
                fn main(): Int {
                    var p = Point { x: 3, y: 4 };
                    print(p.x);
                    p.y = 5;
                    print(p.mag2());
                    return 0;
                }
                """, "point.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define void @rg_fn_Point_mag2("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_struct("));
        assertTrue(ir, ir.contains("call void @rg_member("));
        assertTrue(ir, ir.contains("call void @rg_field_set("));
        assertTrue(ir, ir.contains("call void @rg_call_method("));
        assertTrue(ir, ir.contains("call void @rg_register_method("));
        assertTrue(ir, ir.contains("call void @rg_init_methods()"));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void matchAndEnumsLower() {
        Checker checker = check("""
                enum Shape {
                    Circle,
                    Rect(width: Int, height: Int),
                }
                fn main(): Int {
                    var s = Shape.Rect(4, 5);
                    match s {
                        Rect(width: w, height: h) { print(w); print(h); }
                        Circle { print(0); }
                    }
                    switch 2 {
                        1 { print("one"); }
                        2 { print("two"); }
                        _ { print("other"); }
                    }
                    var c = Shape.Circle;
                    match c {
                        Circle { return 1; }
                        Rect { return 2; }
                    }
                    return 0;
                }
                """, "match.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_enum_type("));
        assertTrue(ir, ir.contains("call void @rg_match_var("));
        assertTrue(ir, ir.contains("call void @rg_match_lit("));
        assertTrue(ir, ir.contains("call void @rg_payload("));
        assertTrue(ir, ir.contains("call void @rg_register_enum("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void lambdasAndClosuresLower() {
        Checker checker = check("""
                fn main(): Int {
                    print((fn (x: Int): Int { return x * 2; })(4));
                    var n = 1;
                    var add = fn (x: Int): Int { return n + x; };
                    print(add(2));
                    return 0;
                }
                """, "lambda.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define void @rg_fn_lam0("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_closure("));
        assertTrue(ir, ir.contains("call void @rg_call_val("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void throwAndCatchLower() {
        Checker checker = check("""
                fn boom() throws: String {
                    throw "nope";
                }
                fn main(): Int {
                    do {
                        try boom();
                    } catch e {
                        print(e);
                    }
                    return 0;
                }
                """, "throw.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_boom("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_throw("));
        assertTrue(ir, ir.contains("call i32 @rg_has_throw()"));
        assertTrue(ir, ir.contains("call void @rg_catch("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void asyncAwaitLower() {
        Checker checker = check("""
                async fn work(): Int {
                    return 1;
                }
                async fn main(): Int {
                    print(await work());
                    return 0;
                }
                """, "async.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_work("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_spawn("));
        assertTrue(ir, ir.contains("call void @rg_await("));
        assertTrue(ir, ir.contains("call void @rg_spawn(ptr %fut, ptr @rg_fn_main, ptr null, i32 0)"));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void superAndInheritanceLower() {
        Checker checker = check("""
                class Enemy {
                    var hp: Int = 10;
                    fn hurt(self, dmg: Int): Int {
                        self.hp = self.hp - dmg;
                        return self.hp;
                    }
                }
                class Slime extends Enemy {
                    fn hurt(self, dmg: Int): Int {
                        return super.hurt(dmg / 2);
                    }
                }
                fn main(): Int {
                    var s = Slime { hp: 10 };
                    print(s.hurt(4));
                    return 0;
                }
                """, "class_super.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_Enemy_hurt("));
        assertTrue(ir, ir.contains("define void @rg_fn_Slime_hurt("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_fn_Enemy_hurt("));
        assertTrue(ir, ir.contains("call void @rg_call_method("));
        assertTrue(ir, ir.contains("call void @rg_register_parent("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void signalsLower() {
        Checker checker = check("""
                signal collected(amount: Int);

                struct Coin {
                    signal grabbed(amount: Int);

                    fn grab(n: Int) {
                        grabbed.emit(n);
                    }
                }

                fn log_coin(amount: Int) {
                    print(amount);
                }

                fn main(): Int {
                    collected.connect(log_coin);
                    collected.emit(5);

                    var c = Coin {};
                    c.grabbed.connect(fn (amount: Int) { print(amount); });
                    c.grab(3);
                    return 0;
                }
                """, "signals.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define void @rg_fn_log_coin("));
        assertTrue(ir, ir.contains("define void @rg_fn_Coin_grab("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_signal("));
        assertTrue(ir, ir.contains("call void @rg_register_type_signal("));
        assertTrue(ir, ir.contains("call void @rg_call_method("));
        assertTrue(ir, ir.contains("call void @rg_flush_deferred()"));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void hostAndModuleCallsLower() {
        Checker checker = check("""
                async fn work(): Int {
                    await __time.delay(1);
                    return 42;
                }
                async fn main(): Int {
                    print(await work());
                    var xs = await Future.all([work(), work()]);
                    print(xs[0]);
                    print(await Future.race([work(), work()]));
                    var f = __time.delay(1000);
                    f.cancel();
                    do {
                        await f;
                    } catch e {
                        print(e);
                    }
                    print(__time.now());
                    return 0;
                }
                """, "host.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_work("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertTrue(ir, ir.contains("call void @rg_await("));
        assertTrue(ir, ir.contains("call void @rg_call_method("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void stdTimeModuleCallLowers() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("examples/async.rg"));
        Checker checker = check(source, "examples/async.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_std_time_delay("));
        assertTrue(ir, ir.contains("call void @rg_spawn(ptr "));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertFalse(ir, ir.contains("; fn main { not lowered to llvm }"));
    }

    @Test
    public void uiHostLowersToLlvm() {
        Checker checker = check("""
                fn main(): Int {
                    var id = try __ui.open("rg", 320, 240, false);
                    print(__ui.alive(id));
                    print(__ui.width(id));
                    print(__ui.backend());
                    __ui.close(id);
                    print(__ui.alive(id));
                    return 0;
                }
                """, "ui.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void stdlibHostsLowerToLlvm() {
        Checker checker = check("""
                fn main(): Int {
                    print(__math.pow(2, 10));
                    print(__math.to_int(__math.sqrt(9.0)));
                    print(__str.upper("hi"));
                    print(__str.split("a,b", ",")[0]);
                    print(__path.stem("a/b/c.txt"));
                    print(__uuid.valid("550e8400-e29b-41d4-a716-446655440000"));
                    var id = try __uuid.parse("550e8400-e29b-41d4-a716-446655440000");
                    print(id);
                    checks.eq(2, 2);
                    checks.eq_string("a", "a");
                    checks.that(true);
                    print(process.argc());
                    print(argv_len());
                    return 0;
                }
                """, "hosts.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_set_argv("));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void stdMathModuleCallLowers() {
        Checker checker = check("""
                import std.math;
                import std.str;
                import std.path;
                fn main(): Int {
                    print(math.pow(2, 8));
                    print(str.upper("ok"));
                    print(path.stem("README.md"));
                    return 0;
                }
                """, "host.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_std_math_pow("));
        assertTrue(ir, ir.contains("define void @rg_fn_std_str_upper("));
        assertTrue(ir, ir.contains("define void @rg_fn_std_path_stem("));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertFalse(ir, ir.contains("; fn main { not lowered to llvm }"));
    }

    @Test
    public void ioJsonRegexLowerToLlvm() {
        Checker checker = check("""
                fn main(): Int {
                    try __io.write_text("build/tmp-rg-llvm.txt", "ok");
                    print(__io.exists("build/tmp-rg-llvm.txt"));
                    print(try __io.read_text("build/tmp-rg-llvm.txt"));
                    print(__json.valid("{\\"a\\":1}"));
                    print(__json.stringify({"a": 1}));
                    var obj = try __json.parse("{\\"n\\": 3}");
                    print(obj["n"]);
                    print(__regex.is_match("\\\\d+", "a42b"));
                    print(__regex.find("\\\\d+", "ab12cd"));
                    print(__regex.replace("\\\\d+", "x1y22", "#"));
                    return 0;
                }
                """, "rest.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
    }

    @Test
    public void stdIoJsonRegexModuleCallLowers() {
        Checker checker = check("""
                import std.io;
                import std.json;
                import std.regex;
                fn main(): Int {
                    print(io.exists("README.md"));
                    print(json.stringify({"ok": true}));
                    print(regex.is_match("\\\\d+", "42"));
                    return 0;
                }
                """, "host.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_std_io_exists("));
        assertTrue(ir, ir.contains("define void @rg_fn_std_json_stringify("));
        assertTrue(ir, ir.contains("define void @rg_fn_std_regex_is_match("));
        assertTrue(ir, ir.contains("call void @rg_host_call("));
        assertTrue(ir, ir.contains("define void @rg_fn_main("));
        assertFalse(ir, ir.contains("; fn main { not lowered to llvm }"));
    }

    @Test
    public void implicitSelfMethodLowers() {
        Checker checker = check("""
                class Counter {
                    var n: Int = 0;
                    fn bump() {
                        n = n + 1;
                    }
                    fn twice() {
                        bump();
                        bump();
                    }
                }
                fn main(): Int {
                    var c = Counter {};
                    c.twice();
                    print(c.n);
                    return 0;
                }
                """, "selfcall.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, ir.contains("define void @rg_fn_Counter_twice("));
        assertTrue(ir, ir.contains("define void @rg_fn_Counter_bump("));
        assertTrue(ir, ir.contains("call void @rg_call_method("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
        assertTrue(ir, LlvmLink.fullyLowered(ir));
    }

    @Test
    public void testHarnessLowersAssert() {
        Checker checker = check("""
                @test
                fn add() {
                    assert(2 + 2 == 4);
                }
                fn main(): Int {
                    return 0;
                }
                """, "tests.rg");
        String ir = Llvm.emit(checker, true);
        assertTrue(ir, ir.contains("define void @rg_fn_add("));
        assertTrue(ir, ir.contains("call void @rg_assert("));
        assertTrue(ir, ir.contains("call void @rg_test_ok("));
        assertTrue(ir, ir.contains("call void @rg_test_fail("));
        assertTrue(ir, ir.contains("call void @rg_test_summary("));
        assertTrue(ir, ir.contains("define i32 @main(i32 %argc, ptr %argv)"));
        assertFalse(ir, ir.contains("call void @rg_fn_main("));
        assertFalse(ir, ir.contains("{ not lowered to llvm }"));
        assertTrue(ir, LlvmLink.fullyLowered(ir));
    }

    @Test
    public void clangLinkCommandUsesRuntime() {
        List<String> cmd = LlvmLink.clangCommand(
                Path.of("clang"),
                Path.of("program.ll"),
                Path.of("native", "runtime.c"),
                Path.of("out"));
        assertTrue(cmd.toString(), cmd.contains("-std=c11"));
        assertTrue(cmd.toString(), cmd.contains("-Wno-override-module"));
        assertTrue(cmd.toString(), cmd.stream().anyMatch(s -> s.replace('\\', '/').endsWith("native/runtime.c")));
        if (!LlvmLink.isWindows()) {
            assertTrue(cmd.toString(), cmd.contains("-lm"));
        }
    }

    @Test
    public void helloIrIsLinkable() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("examples/hello.rg"));
        Checker checker = check(source, "examples/hello.rg");
        String ir = Llvm.emit(checker);
        assertTrue(ir, LlvmLink.fullyLowered(ir));
        Path nativeDir = LlvmLink.resolveNativeDir(Path.of("").toAbsolutePath());
        assertTrue(String.valueOf(nativeDir), LlvmLink.isNativeDir(nativeDir));
        if (LlvmLink.findClang() == null) {
            LlvmLink.Result result = LlvmLink.link(checker, nativeDir, Path.of("build", "skip-no-clang"));
            assertTrue(result.message, !result.ok);
            assertTrue(result.message, result.message.contains("clang"));
        }
    }

    @Test
    public void skipsMismatchedClangArch() {
        Path installed = Path.of("C:\\Program Files\\LLVM\\bin\\clang.exe");
        if (!LlvmLink.isWindows() || !java.nio.file.Files.isRegularFile(installed)) {
            return;
        }
        if (LlvmLink.peMachine(installed) == LlvmLink.osMachine()) {
            return;
        }
        assertTrue(LlvmLink.clangUnusable(installed), LlvmLink.clangUnusable(installed) != null);
        assertTrue(LlvmLink.findClang() == null);
        assertTrue(LlvmLink.clangSkipReason, LlvmLink.clangSkipReason.contains("ARM64")
                || LlvmLink.clangSkipReason.contains("x64"));
    }

    private static Checker check(String source, String path) {
        List<Diagnostic> diags = new ArrayList<>();
        Program program = Parser.parseSource(source, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Checker checker = Checker.check(program, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        return checker;
    }
}

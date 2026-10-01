package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BytecodeTest {

    @Test
    public void helloLowersAndRuns() {
        Checker checker = check("""
                fn main(): Int {
                    print("hello from RoseGold");
                    return 0;
                }
                """, "hello.rg");
        FnDecl main = checker.fns.get("main");
        assertNotNull(main.code);
        String dump = main.code.dump();
        assertTrue(dump, dump.contains("CALL print"));
        assertTrue(dump, dump.contains("RET r"));
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    print("hello from RoseGold");
                    return 0;
                }
                """, "hello.rg", List.of("hello.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("hello from RoseGold\n", result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void arithmeticAndIf() {
        Run.Result result = Run.runSource("""
                fn sign(n: Int): Int {
                    if n > 0 {
                        return 1;
                    } elif n < 0 {
                        return -1;
                    } else {
                        return 0;
                    }
                }
                fn main(): Int {
                    print(sign(4));
                    print(sign(-2));
                    print(sign(0));
                    print(1 + 2 * 3);
                    return 0;
                }
                """, "arith.rg", List.of("arith.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                -1
                0
                7
                """, result.out);
        Checker checker = check("""
                fn sign(n: Int): Int {
                    if n > 0 {
                        return 1;
                    } elif n < 0 {
                        return -1;
                    } else {
                        return 0;
                    }
                }
                fn main(): Int {
                    print(sign(4));
                    return 0;
                }
                """, "arith.rg");
        assertNotNull(checker.fns.get("sign").code);
        assertNotNull(checker.fns.get("main").code);
    }

    @Test
    public void whileAndShortCircuit() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    var i = 0;
                    var s = 0;
                    while i < 4 {
                        s = s + i;
                        i = i + 1;
                    }
                    print(s);
                    print(false && true);
                    print(true || false);
                    return 0;
                }
                """, "loop.rg", List.of("loop.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                6
                false
                true
                """, result.out);
        Checker checker = check("""
                fn main(): Int {
                    var i = 0;
                    var s = 0;
                    while i < 4 {
                        s = s + i;
                        i = i + 1;
                    }
                    return s;
                }
                """, "loop.rg");
        assertNotNull(checker.fns.get("main").code);
        assertTrue(checker.fns.get("main").code.dump(), checker.fns.get("main").code.dump().contains("JUMP_F"));
    }

    @Test
    public void forArrayAndRangeLower() {
        Checker checker = check("""
                fn main(): Int {
                    var sum = 0;
                    for x in [1, 2, 3] {
                        sum = sum + x;
                    }
                    for n in 0..3 {
                        sum = sum + n;
                    }
                    for n in 1..=2 {
                        sum = sum + n;
                    }
                    return sum;
                }
                """, "for.rg");
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("ARRAY"));
        assertTrue(dump, dump.contains("RANGE"));
        assertTrue(dump, dump.contains("ITER_ITEMS"));
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    var sum = 0;
                    for x in [1, 2, 3] {
                        sum = sum + x;
                    }
                    print(sum);
                    for n in 0..3 {
                        print(n);
                    }
                    for n in 1..=2 {
                        print(n);
                    }
                    return 0;
                }
                """, "for.rg", List.of("for.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                6
                0
                1
                2
                1
                2
                """, result.out);
    }

    @Test
    public void indexGetAndSet() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    var xs = [1, 2, 3];
                    print(xs[0]);
                    xs[1] = 9;
                    xs[2] += 1;
                    print(xs[1]);
                    print(xs[2]);
                    print(len(xs));
                    return 0;
                }
                """, "idx.rg", List.of("idx.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                9
                4
                3
                """, result.out);
        Checker checker = check("""
                fn main(): Int {
                    var xs = [1, 2, 3];
                    xs[1] = 9;
                    return xs[1];
                }
                """, "idx.rg");
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("INDEX_SET"));
        assertTrue(dump, dump.contains("INDEX_GET"));
    }

    @Test
    public void forBreakContinueAndRestore() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    var x = 9;
                    for x in [1, 2, 3, 4] {
                        if x == 2 {
                            continue;
                        }
                        if x == 4 {
                            break;
                        }
                        print(x);
                    }
                    print(x);
                    return 0;
                }
                """, "forctl.rg", List.of("forctl.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                3
                9
                """, result.out);
    }

    @Test
    public void controlExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/control.rg"));
        assertNotNull(checker.fns.get("sign").code);
        assertNotNull(checker.fns.get("main").code);
        Run.Result result = Run.runFile(Path.of("examples/control.rg"), List.of("examples/control.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                -1
                0
                6
                6
                0
                1
                2
                1
                2
                """, result.out);
    }

    @Test
    public void collectionsExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/collections.rg"));
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("MAP"));
        assertTrue(dump, dump.contains("METHOD push"));
        assertTrue(dump, dump.contains("METHOD pop"));
        assertTrue(dump, dump.contains("METHOD has"));
        Run.Result result = Run.runFile(Path.of("examples/collections.rg"), List.of("examples/collections.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                4
                4
                12
                true
                3
                """, result.out);
    }

    @Test
    public void mapForAndRemove() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    var scores = {"ada": 10, "grace": 12};
                    scores.insert("linus", 9);
                    print(scores.remove("ada"));
                    for k in scores {
                        print(k);
                    }
                    print(scores.keys().len());
                    return 0;
                }
                """, "map.rg", List.of("map.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                10
                grace
                linus
                2
                """, result.out);
    }

    @Test
    public void pointExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/point.rg"));
        assertNotNull(checker.fns.get("main").code);
        assertNotNull(checker.typeMethods.get("Point").get("mag2").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("STRUCT Point"));
        assertTrue(dump, dump.contains("MEMBER x"));
        assertTrue(dump, dump.contains("FIELD_SET y"));
        assertTrue(dump, dump.contains("METHOD mag2"));
        String mag = checker.typeMethods.get("Point").get("mag2").code.dump();
        assertTrue(mag, mag.contains("MEMBER x"));
        assertTrue(mag, mag.contains("MEMBER y"));
        Run.Result result = Run.runFile(Path.of("examples/point.rg"), List.of("examples/point.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                0
                25
                eq
                """, result.out);
    }

    @Test
    public void classExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/class.rg"));
        assertNotNull(checker.fns.get("main").code);
        assertNotNull(checker.typeMethods.get("Dog").get("speak").code);
        assertNotNull(checker.typeMethods.get("Animal").get("hit").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("STRUCT Dog"));
        assertTrue(dump, dump.contains("STRUCT Cat"));
        assertTrue(dump, dump.contains("METHOD speak"));
        assertTrue(dump, dump.contains("METHOD hit"));
        Run.Result result = Run.runFile(Path.of("examples/class.rg"), List.of("examples/class.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                woof
                1
                meow
                """, result.out);
    }

    @Test
    public void classSuperLowers() throws Exception {
        Checker checker = checkFile(Path.of("tests/pass/class_super.rg"));
        assertNotNull(checker.typeMethods.get("Slime").get("hurt").code);
        String dump = checker.typeMethods.get("Slime").get("hurt").code.dump();
        assertTrue(dump, dump.contains("SUPER Enemy.hurt"));
        Run.Result result = Run.runFile(Path.of("tests/pass/class_super.rg"), List.of("tests/pass/class_super.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("8\n", result.out);
    }

    @Test
    public void superFieldAndCatch() {
        Run.Result result = Run.runSource("""
                class Enemy {
                    var hp: Int = 10;
                    fn boom() throws {
                        throw "hit";
                    }
                    fn ok(): Int {
                        return self.hp;
                    }
                }
                class Slime extends Enemy {
                    fn bump(): Int {
                        super.hp = super.hp + 1;
                        return super.hp;
                    }
                    fn run(): Int {
                        do {
                            try super.boom();
                        } catch e {
                            print(e);
                        }
                        return super.ok();
                    }
                }
                fn main(): Int {
                    var s = Slime { hp: 10 };
                    print(s.bump());
                    print(s.run());
                    return 0;
                }
                """, "super_more.rg", List.of("super_more.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                11
                hit
                11
                """, result.out);
        Checker checker = check("""
                class Enemy {
                    var hp: Int = 10;
                    fn boom() throws {
                        throw "hit";
                    }
                    fn ok(): Int {
                        return self.hp;
                    }
                }
                class Slime extends Enemy {
                    fn bump(): Int {
                        super.hp = super.hp + 1;
                        return super.hp;
                    }
                    fn run(): Int {
                        do {
                            try super.boom();
                        } catch e {
                            print(e);
                        }
                        return super.ok();
                    }
                }
                fn main(): Int {
                    var s = Slime { hp: 10 };
                    return s.run();
                }
                """, "super_more.rg");
        assertNotNull(checker.typeMethods.get("Slime").get("run").code);
        assertNotNull(checker.typeMethods.get("Slime").get("bump").code);
        String run = checker.typeMethods.get("Slime").get("run").code.dump();
        assertTrue(run, run.contains("SUPER Enemy.boom"));
        assertTrue(run, run.contains("SUPER Enemy.ok"));
        String bump = checker.typeMethods.get("Slime").get("bump").code.dump();
        assertTrue(bump, bump.contains("MEMBER hp"));
        assertTrue(bump, bump.contains("FIELD_SET hp"));
    }

    @Test
    public void methodCallsPeerAndField() {
        Run.Result result = Run.runSource("""
                class Box {
                    var open: Bool = false;
                    var items: Array[Int] = [];
                    fn closed_h(): Int {
                        return 32;
                    }
                    fn height(w: Int): Int {
                        var h = closed_h();
                        if (open) {
                            h = h + len(items) * 10;
                        }
                        return h;
                    }
                }
                fn main(): Int {
                    var d = Box { items: [1, 2, 3] };
                    print(d.height(100));
                    print(d.closed_h());
                    return 0;
                }
                """, "box.rg", List.of("box.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                32
                32
                """, result.out);
        Checker checker = check("""
                class Box {
                    var open: Bool = false;
                    var items: Array[Int] = [];
                    fn closed_h(): Int {
                        return 32;
                    }
                    fn height(w: Int): Int {
                        var h = closed_h();
                        if (open) {
                            h = h + len(items) * 10;
                        }
                        return h;
                    }
                }
                fn main(): Int {
                    var d = Box { items: [1, 2, 3] };
                    return d.height(100);
                }
                """, "box.rg");
        assertNotNull(checker.typeMethods.get("Box").get("height").code);
        assertNotNull(checker.typeMethods.get("Box").get("closed_h").code);
        assertNotNull(checker.fns.get("main").code);
    }

    @Test
    public void overflowMatchesTreeWalk() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    print(9223372036854775807 + 1);
                    return 0;
                }
                """, "ovf.rg", List.of("ovf.rg"));
        assertTrue(result.message, !result.ok);
        assertTrue(result.message, result.message.contains("integer overflow"));
    }

    @Test
    public void enumExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/enum.rg"));
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("LOAD_ENUM r"));
        assertTrue(dump, dump.contains("MATCH_VAR"));
        assertTrue(dump, dump.contains("MATCH_LIT"));
        assertTrue(dump, dump.contains("PAYLOAD"));
        assertTrue(dump, dump.contains("METHOD Rect"));
        Run.Result result = Run.runFile(Path.of("examples/enum.rg"), List.of("examples/enum.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                green
                two
                2
                3
                """, result.out);
    }

    @Test
    public void typeExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/type.rg"));
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("LOAD_ENUM r"));
        assertTrue(dump, dump.contains("MATCH_VAR"));
        Run.Result result = Run.runFile(Path.of("examples/type.rg"), List.of("examples/type.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("two\n", result.out);
    }

    @Test
    public void matchNamedPayloadAndLits() {
        Run.Result result = Run.runSource("""
                enum Shape {
                    Circle(Int),
                    Rect(width: Int, height: Int),
                }
                fn main(): Int {
                    var s = Shape.Rect(4, 5);
                    match s {
                        Rect(width: w, height: h) { print(w); print(h); }
                        Circle(r) { print(r); }
                    }
                    switch "ok" {
                        "no" { print("no"); }
                        "ok" { print("yes"); }
                        _ { print("other"); }
                    }
                    switch true {
                        false { print("f"); }
                        true { print("t"); }
                    }
                    return 0;
                }
                """, "match.rg", List.of("match.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                4
                5
                yes
                t
                """, result.out);
        Checker checker = check("""
                enum Shape {
                    Circle(Int),
                    Rect(width: Int, height: Int),
                }
                fn main(): Int {
                    var s = Shape.Rect(4, 5);
                    match s {
                        Rect(width: w, height: h) { print(w); print(h); }
                        Circle(r) { print(r); }
                    }
                    return 0;
                }
                """, "match.rg");
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("PAYLOAD r"));
        assertTrue(dump, dump.contains("width"));
        assertTrue(dump, dump.contains("height"));
    }

    @Test
    public void tryExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/try.rg"));
        assertNotNull(checker.fns.get("boom").code);
        assertNotNull(checker.fns.get("ok").code);
        assertNotNull(checker.fns.get("main").code);
        String boom = checker.fns.get("boom").code.dump();
        assertTrue(boom, boom.contains("THROW r"));
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("handler "));
        assertTrue(dump, dump.contains("CALL boom"));
        assertTrue(dump, dump.contains("CALL ok"));
        Run.Result result = Run.runFile(Path.of("examples/try.rg"), List.of("examples/try.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                nope
                7
                """, result.out);
    }

    @Test
    public void doCatchNestedAndRuntime() {
        Run.Result result = Run.runSource("""
                fn inner() throws: Int {
                    throw 3;
                }
                fn wrap() throws: String {
                    do {
                        print(try inner());
                        print("miss");
                    } catch e {
                        print(e);
                        throw "outer";
                    }
                    return "";
                }
                fn main(): Int {
                    do {
                        try wrap();
                    } catch e {
                        print(e);
                    }
                    do {
                        print(1 / 0);
                    } catch e {
                        print("div");
                    }
                    return 0;
                }
                """, "try2.rg", List.of("try2.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                outer
                div
                """, result.out);
        Checker checker = check("""
                fn inner() throws: Int {
                    throw 3;
                }
                fn main(): Int {
                    do {
                        print(try inner());
                    } catch e {
                        print(e);
                    }
                    return 0;
                }
                """, "try2.rg");
        assertNotNull(checker.fns.get("inner").code);
        assertNotNull(checker.fns.get("main").code);
    }

    @Test
    public void lambdaLowersAndCaptures() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    print((fn (x: Int): Int { return x * 2; })(4));
                    var n = 1;
                    var add = fn (x: Int): Int { return n + x; };
                    print(add(2));
                    n = 9;
                    print(add(2));
                    var double = fn (x: Int): Int { return x * 2; };
                    print(double(5));
                    return 0;
                }
                """, "lambda.rg", List.of("lambda.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                8
                3
                3
                10
                """, result.out);
        Checker checker = check("""
                fn main(): Int {
                    print((fn (x: Int): Int { return x * 2; })(4));
                    var n = 1;
                    var add = fn (x: Int): Int { return n + x; };
                    print(add(2));
                    return 0;
                }
                """, "lambda.rg");
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("CLOSURE"));
        assertTrue(dump, dump.contains("CALL_VAL"));
    }

    @Test
    public void lambdaCapturesSelfField() {
        Run.Result result = Run.runSource("""
                class Box {
                    var n: Int = 1;
                    fn adder(): fn (Int): Int {
                        return fn (x: Int): Int { return n + x; };
                    }
                }
                fn main(): Int {
                    var b = Box {};
                    var add = b.adder();
                    print(add(2));
                    b.n = 9;
                    print(add(2));
                    return 0;
                }
                """, "lamself.rg", List.of("lamself.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                11
                """, result.out);
        Checker checker = check("""
                class Box {
                    var n: Int = 1;
                    fn adder(): fn (Int): Int {
                        return fn (x: Int): Int { return n + x; };
                    }
                }
                fn main(): Int {
                    var b = Box {};
                    return b.adder()(2);
                }
                """, "lamself.rg");
        assertNotNull(checker.typeMethods.get("Box").get("adder").code);
        String dump = checker.typeMethods.get("Box").get("adder").code.dump();
        assertTrue(dump, dump.contains("CLOSURE"));
    }

    @Test
    public void asyncExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/async.rg"));
        assertNotNull(checker.fns.get("work").code);
        assertNotNull(checker.fns.get("boom").code);
        assertNotNull(checker.fns.get("main").code);
        String work = checker.fns.get("work").code.dump();
        assertTrue(work, work.contains("AWAIT"));
        assertTrue(work, work.contains("delay"));
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("AWAIT"));
        assertTrue(dump, dump.contains("Future.all"));
        assertTrue(dump, dump.contains("Future.race"));
        assertTrue(dump, dump.contains("METHOD cancel"));
        String boom = checker.fns.get("boom").code.dump();
        assertTrue(boom, boom.contains("THROW"));
        Run.Result result = Run.runFile(Path.of("examples/async.rg"), List.of("examples/async.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                42
                42
                42
                42
                cancelled
                nope
                """, result.out);
    }

    @Test
    public void signalsExampleLowers() throws Exception {
        Checker checker = checkFile(Path.of("examples/signals.rg"));
        assertNotNull(checker.fns.get("main").code);
        assertNotNull(checker.typeMethods.get("Coin").get("grab").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("LOAD_SIGNAL r"));
        assertTrue(dump, dump.contains("collected"));
        assertTrue(dump, dump.contains("METHOD connect"));
        assertTrue(dump, dump.contains("METHOD emit"));
        assertTrue(dump, dump.contains("MEMBER grabbed"));
        String grab = checker.typeMethods.get("Coin").get("grab").code.dump();
        assertTrue(grab, grab.contains("MEMBER grabbed"));
        assertTrue(grab, grab.contains("METHOD emit"));
        Run.Result result = Run.runFile(Path.of("examples/signals.rg"), List.of("examples/signals.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                5
                3
                """, result.out);
    }

    @Test
    public void signalsConnectDisconnectAndDeferred() throws Exception {
        Checker checker = checkFile(Path.of("tests/pass/signals_ok.rg"));
        assertNotNull(checker.fns.get("main").code);
        String dump = checker.fns.get("main").code.dump();
        assertTrue(dump, dump.contains("LOAD_SIGNAL"));
        assertTrue(dump, dump.contains("METHOD emit_deferred"));
        assertTrue(dump, dump.contains("MEMBER hit"));
        Run.Result result = Run.runFile(Path.of("tests/pass/signals_ok.rg"), List.of("tests/pass/signals_ok.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                pong
                5
                hit
                pong
                """, result.out);
        Run.Result disc = Run.runSource("""
                signal ping();
                fn a() {
                    print("a");
                }
                fn b() {
                    print("b");
                }
                fn main(): Int {
                    ping.connect(a);
                    ping.connect(b);
                    ping.disconnect(a);
                    ping.emit();
                    return 0;
                }
                """, "sigdisc.rg", List.of("sigdisc.rg"));
        assertTrue(disc.message, disc.ok);
        assertEquals("b\n", disc.out);
        Checker discCheck = check("""
                signal ping();
                fn a() {
                    print("a");
                }
                fn main(): Int {
                    ping.connect(a);
                    ping.disconnect(a);
                    ping.emit();
                    return 0;
                }
                """, "sigdisc.rg");
        assertNotNull(discCheck.fns.get("main").code);
        String discDump = discCheck.fns.get("main").code.dump();
        assertTrue(discDump, discDump.contains("METHOD disconnect"));
    }

    @Test
    public void uiHostLowers() throws Exception {
        Checker checker = check("""
                import ui;
                fn main(): Int {
                    var w = try ui.open_hidden("rg-bc-ui", 320, 240);
                    w.click_at(10, 10);
                    w.close();
                    return 0;
                }
                """, "ui_bc.rg");
        assertNotNull(checker.typeMethods.get("Window"));
        assertNotNull(checker.typeMethods.get("Window").get("click_at").code);
        String click = checker.typeMethods.get("Window").get("click_at").code.dump();
        assertTrue(click, click.contains("CALL __ui.feed_click"));
        assertNotNull(checker.typeMethods.get("Window").get("tick").code);
        String tick = checker.typeMethods.get("Window").get("tick").code.dump();
        assertTrue(tick, tick.contains("CALL __ui.take_click"));
        assertTrue(tick, tick.contains("METHOD handle_click"));
        FnDecl padHeight = checker.typeMethods.get("Pad").get("height");
        assertNotNull(padHeight);
        assertNotNull(padHeight.code);
        FnDecl btnHeight = checker.typeMethods.get("Button").get("height");
        assertNotNull(btnHeight);
        assertNotNull(btnHeight.code);
        String btnH = btnHeight.code.dump();
        assertTrue(btnH, btnH.contains("control_height"));
        FnDecl openHidden = null;
        for (Checker.LoadedMod mod : checker.loaded.values()) {
            if (mod.fns.containsKey("open_hidden")) {
                openHidden = mod.fns.get("open_hidden");
                break;
            }
        }
        assertNotNull(openHidden);
        assertNotNull(openHidden.code);
        String openDump = openHidden.code.dump();
        assertTrue(openDump, openDump.contains("CALL __ui.open"));
        Run.Result result = Run.runSource("""
                import ui;
                fn main(): Int {
                    var w = try ui.open_hidden("rg-bc-ui", 320, 240);
                    print(w.alive());
                    print(w.width);
                    w.click_at(10, 10);
                    w.close();
                    print(w.alive());
                    return 0;
                }
                """, "ui_bc.rg", List.of("ui_bc.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                320
                false
                """, result.out);
        Run.Result fresh = Run.runSource("""
                class Box {
                    @optional
                    var items: Array[Int];
                    fn add(n: Int) {
                        items.push(n);
                    }
                }
                fn main(): Int {
                    var a = Box {};
                    a.add(1);
                    var b = Box {};
                    print(len(b.items));
                    b.add(2);
                    print(len(a.items));
                    print(len(b.items));
                    return 0;
                }
                """, "fresharr.rg", List.of("fresharr.rg"));
        assertTrue(fresh.message, fresh.ok);
        assertEquals("""
                0
                1
                1
                """, fresh.out);
        Run.Result widgetsRun = Run.runFile(Path.of("tests/pass/stdlib_ui_widgets.rg"),
                List.of("tests/pass/stdlib_ui_widgets.rg"));
        assertTrue(widgetsRun.message + "\n" + widgetsRun.out, widgetsRun.ok);
    }

    private static Checker check(String source, String path) {
        List<Diagnostic> diags = new ArrayList<>();
        Program program = Parser.parseSource(source, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Checker checker = Checker.check(program, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        return checker;
    }

    private static Checker checkFile(Path path) throws Exception {
        String source = java.nio.file.Files.readString(path);
        return check(source, path.toString());
    }
}

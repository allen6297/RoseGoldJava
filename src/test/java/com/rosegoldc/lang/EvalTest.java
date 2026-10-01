package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EvalTest {

    @Test
    public void helloPrints() {
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
    public void controlExample() throws Exception {
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
        assertEquals(0, result.exitCode);
    }

    @Test
    public void undefinedFunctionFails() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    nope();
                    return 0;
                }
                """, "nope.rg", List.of("nope.rg"));
        assertTrue(result.message, !result.ok);
        assertTrue(result.message, result.message.contains("unknown function") || result.message.contains("undefined"));
    }

    @Test
    public void exitCodeFromMain() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    return 7;
                }
                """, "exit.rg", List.of("exit.rg"));
        assertTrue(result.message, result.ok);
        assertEquals(7, result.exitCode);
    }

    @Test
    public void classExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/class.rg"), List.of("examples/class.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                woof
                1
                meow
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void typeExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/type.rg"), List.of("examples/type.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("two\n", result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void pointExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/point.rg"), List.of("examples/point.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                0
                25
                eq
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void abstractClassCannotConstruct() {
        Run.Result result = Run.runSource("""
                abstract class Animal {
                    abstract fn speak(): String;
                }
                fn main(): Int {
                    var a = Animal {};
                    return 0;
                }
                """, "abs.rg", List.of("abs.rg"));
        assertTrue(result.message, !result.ok);
        assertTrue(result.message, result.message.contains("abstract"));
    }

    @Test
    public void collectionsExample() throws Exception {
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
        assertEquals(0, result.exitCode);
    }

    @Test
    public void lambdaCallAndCapture() {
        Run.Result result = Run.runSource("""
                fn main(): Int {
                    print((fn (x: Int): Int { return x * 2; })(4));
                    var n = 1;
                    var add = fn (x: Int): Int { return n + x; };
                    print(add(2));
                    n = 9;
                    print(add(2));
                    return 0;
                }
                """, "lambda.rg", List.of("lambda.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                8
                3
                3
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void signalsExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/signals.rg"), List.of("examples/signals.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                5
                3
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void enumVariantsAndMatch() {
        Run.Result result = Run.runSource("""
                enum te {
                    one,
                    three,
                    two
                }
                enum Color {
                    Green,
                    Rgb(r: Int, g: Int, b: Int)
                }
                fn main(): Int {
                    var a: te = te.one;
                    a = te.two;
                    switch a {
                        one { print("one"); }
                        two { print("two"); }
                        _ { pass; }
                    }
                    var c = Color.Rgb(1, 2, 3);
                    match c {
                        Rgb(r, g, b) { print(r); print(g); print(b); }
                        _ { pass; }
                    }
                    print(Color.Green);
                    return 0;
                }
                """, "enum.rg", List.of("enum.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                two
                1
                2
                3
                Color.Green
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void modulesExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/modules.rg"), List.of("examples/modules.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                5
                8
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void testsExample() throws Exception {
        Run.Result result = Run.testFile(Path.of("examples/tests.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                ok add
                ok len_array
                2/2 tests passed
                """, result.out);
        assertEquals("2/2 tests passed", result.message);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void argvExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/argv.rg"), List.of("examples/argv.rg", "hello"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                2
                examples/argv.rg
                hello
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void enumExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/enum.rg"), List.of("examples/enum.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                green
                two
                2
                3
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void tryExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/try.rg"), List.of("examples/try.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                nope
                7
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void ufcsExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/ufcs.rg"), List.of("examples/ufcs.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                6
                6
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void arrayExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/array.rg"), List.of("examples/array.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                4
                1.5
                3
                1
                4
                4
                h
                12
                true
                3
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void importCalcExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/import.rg"), List.of("examples/import.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                5
                8
                5
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void genericsExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/generics.rg"), List.of("examples/generics.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                hi
                9
                ok
                1
                ok
                4
                3
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void traitObjectsExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/trait_objects.rg"), List.of("examples/trait_objects.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                ada
                r2
                a
                b
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdlibExample() throws Exception {
        Run.Result result = Run.runFile(Path.of("examples/stdlib.rg"), List.of("examples/stdlib.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                7
                3
                HI
                a
                true
                true
                README
                {"ok":true}
                true
                00000000-0000-0000-0000-000000000000
                5
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void asyncExample() throws Exception {
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
        assertEquals(0, result.exitCode);
    }

    @Test
    public void failingTestReports() {
        Run.Result result = Run.testSource("""
                @test
                fn boom() {
                    assert(1 == 2);
                }
                @test
                fn ok() {
                    assert(true);
                }
                """, "fail.rg");
        assertTrue(result.message, !result.ok);
        assertTrue(result.out, result.out.contains("FAIL boom:"));
        assertTrue(result.out, result.out.contains("ok ok"));
        assertTrue(result.out, result.out.contains("1/2 tests passed"));
        assertEquals(1, result.exitCode);
    }

    @Test
    public void noTestsMessage() {
        Run.Result result = Run.testSource("""
                fn main(): Int {
                    return 0;
                }
                """, "none.rg");
        assertTrue(result.message, result.ok);
        assertEquals("no @test functions", result.message);
        assertEquals("", result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdMathAndStr() {
        Run.Result result = Run.runSource("""
                import std.math;
                import std.str;
                fn main(): Int {
                    print(math.abs(-4));
                    print(math.pow(2, 10));
                    print(math.min(3, 9));
                    print(str.upper("hi"));
                    print(str.contains("hello", "ell"));
                    print(str.split("a,b,c", ",")[1]);
                    return 0;
                }
                """, "host.rg", List.of("host.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                4
                1024
                3
                HI
                true
                b
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdIoRoundTrip() {
        Run.Result result = Run.runSource("""
                import std.io;
                fn main(): Int {
                    try io.write_text("build/tmp-rg-io.txt", "alpha\\nbeta");
                    print(io.exists("build/tmp-rg-io.txt"));
                    print(try io.read_text("build/tmp-rg-io.txt"));
                    print(len(try io.read_lines("build/tmp-rg-io.txt")));
                    print(io.remove("build/tmp-rg-io.txt"));
                    return 0;
                }
                """, "io.rg", List.of("io.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                alpha
                beta
                2
                true
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdUuidTimeAndPath() {
        Run.Result result = Run.runSource("""
                import std;
                import std.time;
                import std.path;
                fn main(): Int {
                    print(std.valid("00000000-0000-0000-0000-000000000000"));
                    print(std.valid("not-a-uuid"));
                    print((try std.parse("ABCDEFAB-CDEF-1234-ABCD-ABCDEFABCDEF")).value);
                    print(std.nil().is_nil());
                    var u = std.v4();
                    print(std.valid(u.value));
                    print(len(u.hex()));
                    do {
                        try std.parse("nope");
                        print("ok");
                    } catch e {
                        print(e);
                    }
                    print(time.now() > 0);
                    time.sleep(0);
                    print(path.join("a", "b"));
                    print(path.parent("a/b/c.txt"));
                    print(path.stem("a/b/c.txt"));
                    return 0;
                }
                """, "host2.rg", List.of("host2.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                false
                abcdefab-cdef-1234-abcd-abcdefabcdef
                true
                true
                32
                invalid UUID
                true
                a/b
                a/b
                c
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdJsonParseAndStringify() {
        Run.Result result = Run.runSource("""
                import std.json;
                fn main(): Int {
                    var obj = try json.parse("{\\\"n\\\": 3, \\\"ok\\\": true, \\\"s\\\": \\\"hi\\\"}");
                    print(obj["n"]);
                    print(obj["ok"]);
                    print(obj["s"]);
                    print(json.stringify(obj));
                    var xs = try json.parse("[1, 2, 3]");
                    print(len(xs));
                    print(xs[1]);
                    print(try json.parse("7"));
                    print(try json.parse("1.5"));
                    print(try json.parse("true"));
                    print(json.stringify(try json.parse("\\\"x\\\\ny\\\"")));
                    print(json.valid("{\\\"a\\\":1}"));
                    print(json.valid("{"));
                    print(json.valid("null"));
                    print(json.stringify([1, 2]));
                    print(json.stringify({"a": 1}));
                    do {
                        try json.parse("null");
                        print("ok");
                    } catch e {
                        print(e);
                    }
                    return 0;
                }
                """, "json.rg", List.of("json.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                3
                true
                hi
                {"n":3,"ok":true,"s":"hi"}
                3
                2
                7
                1.5
                true
                "x\\ny"
                true
                false
                false
                [1,2]
                {"a":1}
                json null is not supported
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdRegexSearchAndReplace() {
        Run.Result result = Run.runSource("""
                import std.regex;
                fn main(): Int {
                    print(regex.valid("\\\\d+"));
                    print(regex.valid("("));
                    print(regex.is_match("\\\\d+", "a42b"));
                    print(regex.is_match("^\\\\d+$", "a42b"));
                    print(regex.find("\\\\d+", "ab12cd"));
                    print(regex.find("z+", "ab12cd"));
                    print(regex.find_match("\\\\d+", "ab12cd"));
                    print(len(regex.find_match("z+", "ab12cd")));
                    var caps = regex.captures("(\\\\w+)@(\\\\w+)", "hi user@host ok");
                    print(len(caps));
                    print(caps[0]);
                    print(caps[1]);
                    print(caps[2]);
                    print(len(regex.captures("z+", "none")));
                    var all = regex.findall("\\\\d+", "a1b22c333");
                    print(len(all));
                    print(all[0]);
                    print(all[1]);
                    print(all[2]);
                    print(regex.replace("\\\\d+", "x1y22", "#"));
                    print(regex.replace("(\\\\w+)=(\\\\w+)", "a=1 b=2", "$2:$1"));
                    var parts = regex.split("[,;]", "a,b;c");
                    print(len(parts));
                    print(parts[0]);
                    print(parts[1]);
                    print(parts[2]);
                    return 0;
                }
                """, "regex.rg", List.of("regex.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                false
                true
                false
                2
                -1
                12
                0
                3
                user@host
                user
                host
                0
                3
                1
                22
                333
                x#y#
                1:a 2:b
                3
                a
                b
                c
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdRegexInvalidPatternFails() {
        Run.Result result = Run.runSource("""
                import std.regex;
                fn main(): Int {
                    regex.is_match("(", "x");
                    return 0;
                }
                """, "regex-bad.rg", List.of("regex-bad.rg"));
        assertTrue(result.message, !result.ok);
        assertTrue(result.message, result.message.contains("invalid regex"));
    }

    @Test
    public void stdUiOpenHidden() {
        Run.Result result = Run.runSource("""
                import ui;
                fn main(): Int {
                    print(ui.backend() == "win32" || ui.backend() == "cocoa" || ui.backend() == "x11" || ui.backend() == "wayland" || ui.backend() == "none");
                    print(ui.kind() == "desktop");
                    print(ui.font_height() > 0);
                    print(ui.text_width("Hi") > ui.text_width("H"));
                    print(ui.text_width("WW") > ui.text_width("ii"));
                    print(ui.count());
                    var w = try ui.open_hidden("rg-ui-test", 320, 240);
                    print(w.alive());
                    print(ui.count());
                    print(w.title);
                    print(w.width);
                    print(w.height);
                    w.set_title("renamed");
                    print(w.title);
                    w.set_size(400, 300);
                    print(w.width);
                    print(w.height);
                    print(w.poll());
                    w.close();
                    print(w.alive());
                    print(ui.count());
                    var made = Window { title: "literal", width: 200, height: 100, visible: false };
                    print(made.alive());
                    try made.show();
                    print(made.alive());
                    print(made.title);
                    print(made.width);
                    made.close();
                    print(made.alive());
                    print(Color.Black.value());
                    print(Color.Red.value());
                    return 0;
                }
                """, "ui.rg", List.of("ui.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                true
                true
                true
                true
                0
                true
                1
                rg-ui-test
                320
                240
                renamed
                400
                300
                true
                false
                0
                false
                true
                literal
                200
                false
                0
                16711680
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdUiBitmapFontFallback() {
        HostUi.forceBitmapFont = true;
        try {
            Run.Result result = Run.runSource("""
                    import ui;
                    fn main(): Int {
                        print(ui.font_height());
                        print(ui.text_width("A"));
                        print(ui.text_width("Hi"));
                        print(ui.text_width("WW") == ui.text_width("ii"));
                        return 0;
                    }
                    """, "ui-font.rg", List.of("ui-font.rg"));
            assertTrue(result.message, result.ok);
            assertEquals("""
                    16
                    16
                    32
                    true
                    """, result.out);
        } finally {
            HostUi.forceBitmapFont = false;
        }
    }

    @Test
    public void stdUiWidgetClick() {
        Run.Result result = Run.runSource("""
                import ui;
                struct Hits {
                    n: Int;
                }
                fn main(): Int {
                    var w = try ui.open_hidden("rg-widgets", 320, 240);
                    var hits = Hits { n: 0 };
                    var hello = Label { text: "Hi" };
                    var go = Button { text: "OK" };
                    go.clicked.connect(fn () { hits.n = hits.n + 1; });
                    w.add(hello);
                    w.add(go);
                    var inner = 320 - 24;
                    var y_btn = 12 + hello.height(inner) + 8 + go.height(inner) / 2;
                    w.click_at(20, 12 + hello.height(inner) / 2);
                    print(hits.n);
                    w.click_at(20, y_btn);
                    print(hits.n);
                    print(Color.Black.value());
                    var wrapped = hello.padding(5);
                    print(wrapped.height(100) == hello.height(100) + 10);
                    w.close();
                    return 0;
                }
                """, "ui-widgets.rg", List.of("ui-widgets.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                0
                1
                0
                true
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void asyncDelayAndChain() {
        Run.Result result = Run.runSource("""
                import std.time;
                async fn inner(): Int {
                    await time.delay(5);
                    return 7;
                }
                async fn main(): Int {
                    await time.delay(5);
                    print(await inner());
                    return 0;
                }
                """, "async.rg", List.of("async.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("7\n", result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void asyncAllRaceCancelAndThrow() {
        Run.Result result = Run.runSource("""
                import std.time;
                async fn left(): Int {
                    await time.delay(5);
                    return 1;
                }
                async fn right(): Int {
                    await time.delay(5);
                    return 2;
                }
                async fn slow(): Int {
                    await time.delay(40);
                    return 1;
                }
                async fn fast(): Int {
                    await time.delay(5);
                    return 2;
                }
                async fn boom() throws: Int {
                    throw "nope";
                }
                async fn main(): Int {
                    var xs = await Future.all([left(), right()]);
                    print(xs[0]);
                    print(xs[1]);
                    print(len(await Future.all([])));
                    print(await Future.race([slow(), fast()]));
                    var f = time.delay(20);
                    f.cancel();
                    do {
                        await f;
                        print("ok");
                    } catch e {
                        print(e);
                    }
                    do {
                        print(await try boom());
                        print("miss");
                    } catch e {
                        print(e);
                    }
                    return 0;
                }
                """, "async2.rg", List.of("async2.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                1
                2
                0
                2
                cancelled
                nope
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void asyncUiNextFrame() {
        Run.Result result = Run.runSource("""
                import ui;
                async fn main(): Int {
                    var w = try ui.open_hidden("rg-async-frame", 320, 240);
                    print(await w.next_frame());
                    w.close();
                    print(await w.next_frame());
                    return 0;
                }
                """, "async-ui.rg", List.of("async-ui.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                true
                false
                """, result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void stdUiSvgImage() throws Exception {
        Path svg = Path.of("build", "rg-dot.svg");
        Files.createDirectories(svg.getParent());
        Files.writeString(svg, """
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
                  <rect width="16" height="16" fill="#2266cc"/>
                  <circle cx="8" cy="8" r="5" fill="#ffffff"/>
                </svg>
                """, StandardCharsets.UTF_8);
        Run.Result result = Run.runSource("""
                import ui;
                fn main(): Int {
                    var img = ui.load_image("build/rg-dot.svg");
                    print(img.img_w);
                    print(img.img_h);
                    var w = try ui.open_hidden("rg-svg", 16, 16);
                    img.paint(w.id, 0, 0, 16);
                    print(w.alive());
                    w.close();
                    return 0;
                }
                """, "svg.rg", List.of("svg.rg"));
        assertTrue(result.message, result.ok);
        assertEquals("""
                16
                16
                true
                """, result.out);
        assertEquals(0, result.exitCode);
    }
}

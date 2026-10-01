package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LlvmNativeTest {

    @Test
    public void hello() throws Exception {
        assertNative("examples/hello.rg", "hello from RoseGold\n");
    }

    @Test
    public void control() throws Exception {
        assertNative("examples/control.rg", """
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
                """);
    }

    @Test
    public void collections() throws Exception {
        assertNative("examples/collections.rg", """
                1
                4
                4
                12
                true
                3
                """);
    }

    @Test
    public void point() throws Exception {
        assertNative("examples/point.rg", """
                3
                0
                25
                eq
                """);
    }

    @Test
    public void tryCatch() throws Exception {
        assertNative("examples/try.rg", """
                nope
                7
                """);
    }

    @Test
    public void async() throws Exception {
        assertNative("examples/async.rg", """
                42
                42
                42
                42
                cancelled
                nope
                """);
    }

    @Test
    public void klass() throws Exception {
        assertNative("examples/class.rg", """
                woof
                1
                meow
                """);
    }

    @Test
    public void enums() throws Exception {
        assertNative("examples/enum.rg", """
                green
                two
                2
                3
                """);
    }

    @Test
    public void ufcs() throws Exception {
        assertNative("examples/ufcs.rg", """
                6
                6
                """);
    }

    @Test
    public void imports() throws Exception {
        assertNative("examples/import.rg", """
                5
                8
                5
                """);
    }

    @Test
    public void array() throws Exception {
        assertNative("examples/array.rg", """
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
                """);
    }

    @Test
    public void signals() throws Exception {
        assertNative("examples/signals.rg", """
                5
                3
                """);
    }

    @Test
    public void argv() throws Exception {
        assertNative("examples/argv.rg", """
                2
                examples/argv.rg
                hello
                """, List.of("examples/argv.rg", "hello"));
    }

    @Test
    public void modules() throws Exception {
        assertNative("examples/modules.rg", """
                5
                8
                """);
    }

    @Test
    public void stdlib() throws Exception {
        assertNative("examples/stdlib.rg", """
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
                """);
    }

    @Test
    public void typeExample() throws Exception {
        assertNative("examples/type.rg", "two\n");
    }

    @Test
    public void generics() throws Exception {
        assertNative("examples/generics.rg", """
                3
                hi
                9
                ok
                1
                ok
                4
                3
                """);
    }

    @Test
    public void traitObjects() throws Exception {
        assertNative("examples/trait_objects.rg", """
                ada
                r2
                a
                b
                """);
    }

    @Test
    public void constexprExample() throws Exception {
        assertNative("examples/constexpr.rg", """
                42
                3
                """);
    }

    @Test
    public void testsExample() throws Exception {
        assertNativeTest("examples/tests.rg", """
                ok add
                ok len_array
                2/2 tests passed
                """, 0);
    }

    @Test
    public void implicitSelfMethod() throws Exception {
        assertNativeSource("""
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
                """, "selfcall.rg", "2\n");
    }

    @Test
    public void windowExample() throws Exception {
        assertNative("examples/window.rg", "");
    }

    @Test
    public void widgetsExample() throws Exception {
        assertNative("examples/widgets.rg", "");
    }

    @Test
    public void todoExample() throws Exception {
        assertNative("examples/todo.rg", "");
    }

    @Test
    public void contactsExample() throws Exception {
        assertNative("examples/contacts.rg", "");
    }

    @Test
    public void hiddenWindowDraws() throws Exception {
        assertNativeSource("""
                fn main(): Int {
                    var id = try __ui.open("rg", 80, 40, false);
                    print(__ui.backend());
                    print(__ui.width(id));
                    __ui.fill(id, 0, 0, 10, 10, 255);
                    __ui.text(id, 2, 2, "Hi", 0);
                    __ui.present(id);
                    __ui.close(id);
                    print(__ui.alive(id));
                    return 0;
                }
                """, "ui-draw.rg", LlvmLink.isWindows() ? "win32\n80\nfalse\n" : "headless\n80\nfalse\n");
    }

    @Test
    public void failingTestReports() throws Exception {
        assertNativeTestSource("""
                @test
                fn boom() {
                    assert(1 == 2);
                }
                @test
                fn ok() {
                    assert(true);
                }
                """, "fail.rg", """
                FAIL boom: assertion failed
                ok ok
                1/2 tests passed
                """, 1);
    }

    private static void assertNative(String file, String expected) throws Exception {
        assertNative(file, expected, List.of(file.replace('\\', '/')));
    }

    private static void assertNative(String file, String expected, List<String> argv) throws Exception {
        Path path = Path.of(file);
        String source = Files.readString(path, StandardCharsets.UTF_8);
        assertNativeSource(source, path.toString().replace('\\', '/'), expected, argv);
    }

    private static void assertNativeSource(String source, String path, String expected) throws Exception {
        assertNativeSource(source, path, expected, List.of(path.replace('\\', '/')));
    }

    private static void assertNativeSource(String source, String path, String expected, List<String> argv)
            throws Exception {
        Checker checker = check(source, path);
        String ir = Llvm.emit(checker);
        assertTrue(ir, LlvmLink.fullyLowered(ir));
        Path nativeDir = LlvmLink.resolveNativeDir(Path.of("").toAbsolutePath());
        assertTrue(String.valueOf(nativeDir), LlvmLink.isNativeDir(nativeDir));
        String stem = Path.of(path).getFileName().toString().replace(".rg", "");
        Path out = nativeOut(stem);
        if (LlvmLink.findClang() == null) {
            LlvmLink.Result result = LlvmLink.link(checker, nativeDir, out);
            assertTrue(result.message, !result.ok);
            assertTrue(result.message, result.message.contains("clang"));
            return;
        }
        LlvmLink.Result ran = execNative(checker, nativeDir, stem, argv, false);
        assertTrue("exit=" + ran.exitCode + " out=" + ran.stdout + " msg=" + ran.message, ran.ok);
        assertEquals(0, ran.exitCode);
        assertEquals(expected, ran.stdout.replace("\r\n", "\n"));
    }

    private static void assertNativeTest(String file, String expected, int exitCode) throws Exception {
        Path path = Path.of(file);
        String source = Files.readString(path, StandardCharsets.UTF_8);
        assertNativeTestSource(source, path.toString().replace('\\', '/'), expected, exitCode);
    }

    private static void assertNativeTestSource(String source, String path, String expected, int exitCode)
            throws Exception {
        Checker checker = check(source, path);
        String ir = Llvm.emit(checker, true);
        assertTrue(ir, LlvmLink.fullyLowered(ir));
        Path nativeDir = LlvmLink.resolveNativeDir(Path.of("").toAbsolutePath());
        assertTrue(String.valueOf(nativeDir), LlvmLink.isNativeDir(nativeDir));
        String stem = Path.of(path).getFileName().toString().replace(".rg", "");
        Path out = nativeOut("test-" + stem);
        if (LlvmLink.findClang() == null) {
            LlvmLink.Result result = LlvmLink.link(checker, nativeDir, out, true);
            assertTrue(result.message, !result.ok);
            assertTrue(result.message, result.message.contains("clang"));
            return;
        }
        LlvmLink.Result ran = execNative(checker, nativeDir, "test-" + stem,
                List.of(path.replace('\\', '/')), true);
        assertEquals(expected, ran.stdout.replace("\r\n", "\n"));
        assertEquals(exitCode, ran.exitCode);
        if (exitCode == 0) {
            assertTrue(ran.message, ran.ok);
        } else {
            assertTrue(ran.message, !ran.ok);
        }
    }

    private static LlvmLink.Result execNative(Checker checker, Path nativeDir, String stem, List<String> argv,
            boolean test) throws Exception {
        Path out = nativeOut(stem);
        LlvmLink.Result linked = LlvmLink.link(checker, nativeDir, out, test);
        assertTrue(linked.message, linked.ok);
        return LlvmLink.exec(linked.output, argv, linked, null, java.util.Map.of("RG_UI_HEADLESS", "1"));
    }

    private static Path nativeOut(String stem) throws Exception {
        Path dir = Files.createTempDirectory("rg-native-");
        dir.toFile().deleteOnExit();
        return dir.resolve(stem);
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

package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CheckTest {

    @Test
    public void helloHasNoParseErrors() {
        String src = """
                fn main(): Int {
                    print("hello from RoseGold");
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "hello.rg");
        assertEquals(diags.toString(), 0, diags.size());
    }

    @Test
    public void classExampleParses() {
        String src = """
                abstract class Animal {
                    pub var hp: Int = 1;
                    abstract fn speak(): String;
                    pub fn hit(): Int {
                        return hp;
                    }
                }
                class Dog extends Animal {
                    fn speak(): String {
                        return "woof";
                    }
                }
                fn main(): Int {
                    var d = Dog {};
                    print(d.speak());
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "class.rg");
        assertEquals(diags.toString(), 0, diags.size());
    }

    @Test
    public void expectedExpression() {
        String src = """
                fn main(): Int {
                    var x = ;
                    var y = ;
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "parse_multi.rg");
        assertTrue(diags.toString(), diags.stream().filter(d -> d.message.contains("expected expression")).count() >= 2);
    }

    @Test
    public void moduleNeedsName() {
        String src = """
                mod {
                    abstract class Animal {
                        abstract fn name(name: String): String;
                    }
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "ty.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("expected module name")));
    }

    @Test
    public void cannotAddIntAndString() {
        String src = """
                fn main(): Int {
                    print(1 + "hello");
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "type_add.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("cannot add")));
    }

    @Test
    public void cannotReturnStringFromInt() {
        String src = """
                fn main(): Int {
                    return "nope";
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "type_return.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("cannot return")));
    }

    @Test
    public void callArityMismatch() {
        String src = """
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(1);
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "type_arity.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("expected 2 args")));
    }

    @Test
    public void cannotAssignStringToInt() {
        String src = """
                fn main(): Int {
                    var n: Int = 1;
                    n = "x";
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "type_assign.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("cannot assign")));
    }

    @Test
    public void constexprCannotCallNonConstexpr() {
        String src = """
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                @constexpr
                fn twice(n: Int): Int {
                    return add(n, n);
                }
                fn main(): Int {
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "constexpr_call.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("not constexpr")));
    }

    @Test
    public void constexprCannotUseVar() {
        String src = """
                @constexpr
                fn bad(): Int {
                    var x = 1;
                    return x;
                }
                fn main(): Int {
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "constexpr_var.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("var")));
    }

    @Test
    public void constexprOk() {
        String src = """
                @constexpr
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                @constexpr
                fn count(): Int {
                    return len([1, 2, 3]);
                }
                fn main(): Int {
                    const n = add(2, 40);
                    return n;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "constexpr_ok.rg");
        assertEquals(diags.toString(), 0, diags.size());
    }

    @Test
    public void unexpectedCharFromFixtureStyle() {
        String src = """
                # expect: unexpected character
                fn main(): Int {
                    print(^);
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "unexpected_char.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.message.contains("unexpected character")));
    }

    @Test
    public void repoStdlibColorImportHint() {
        String src = """
                fn main() {
                    var b : Color = Color.Green;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "color_hint.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.message.contains("Color") && d.message.contains("crate") && d.message.contains("ui")));
    }

    @Test
    public void typeExampleImportsColor() throws Exception {
        Path file = Path.of("examples", "type.rg").toAbsolutePath();
        assertTrue("examples/type.rg", Files.isRegularFile(file));
        List<Diagnostic> diags = Check.checkFile(file);
        assertTrue(diags.toString(), diags.isEmpty());
    }

    @Test
    public void nestedStdUiImportKeepsColorInsideModule() {
        String src = """
                pub mod inner {
                    import std.ui;
                    fn use_color() {
                        var c: Color = Color.Green;
                    }
                }
                fn main() {
                    var b: Color = Color.Green;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "nested_color.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.line >= 7 && d.message.contains("Color") && d.message.contains("crate") && d.message.contains("ui")));
        assertTrue(diags.toString(), diags.stream().noneMatch(d ->
                d.line < 7 && d.message.contains("undefined") && d.message.contains("Color")));
    }

    @Test
    public void colorFromProjectBuiltinHasImportHint() throws Exception {
        Path tmp = Files.createTempDirectory("rg-color-std");
        Path color = tmp.resolve("builtin").resolve("std").resolve("ui").resolve("color.rg");
        Files.createDirectories(color.getParent());
        Files.writeString(color, """
                enum Color {
                    Red,
                    Rgb(r: Int, g: Int, b: Int)
                }
                """, StandardCharsets.UTF_8);
        Path file = tmp.resolve("app").resolve("main.rg");
        Files.createDirectories(file.getParent());
        String src = """
                fn main() {
                    var b : Color = Color.rgb();
                }
                """;
        Files.writeString(file, src, StandardCharsets.UTF_8);
        List<Diagnostic> diags = Check.checkSource(src, file.toString());
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.message.contains("Color") && d.message.contains("crate") && d.message.contains("ui")));
    }

    @Test
    public void projectModuleImportHint() throws Exception {
        Path dir = Files.createTempDirectory("rg-check-mod");
        Main.newProgram(List.of(), dir);
        Main.newProgram(List.of("module", "util"), dir);
        String src = """
                fn main(): Int {
                    return util.hello();
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, dir.resolve("main.rg").toString());
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.message.contains("util") && d.message.contains("crate") && d.message.contains("import util")));

        src = """
                fn main(): Int {
                    return hello();
                }
                """;
        diags = Check.checkSource(src, dir.resolve("main.rg").toString());
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.message.contains("hello") && d.message.contains("crate") && d.message.contains("util")));
    }

    @Test
    public void unusedImportIsWarning() {
        String src = """
                import math;
                fn main(): Int {
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "unused_import.rg");
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                "warning".equals(d.severity) && d.message.contains("unused import") && d.message.contains("math")));
        src = """
                import math;
                fn main(): Int {
                    return math.abs(-1);
                }
                """;
        diags = Check.checkSource(src, "used_import.rg");
        assertTrue(diags.toString(), diags.stream().noneMatch(d -> d.message.contains("unused import")));
    }

    @Test
    public void failFixturesMatchExpect() throws Exception {
        Path failDir = Path.of("tests", "fail");
        if (!Files.isDirectory(failDir)) {
            return;
        }
        for (String name : List.of(
                "parse_expr.rg", "parse_multi.rg", "unexpected_char.rg", "int_literal_large.rg",
                "type_add.rg", "type_return.rg", "type_arity.rg", "type_assign.rg", "type_multi.rg",
                "load_multi.rg", "std_missing.rg", "crate_math.rg", "crate_std.rg", "crate_uuid.rg",
                "import_cycle.rg", "import_dup.rg", "import_private.rg", "import_dotted_private.rg",
                "import_in_mod_missing.rg", "from_dup.rg", "private_nested_fn.rg",
                "constexpr_call.rg", "constexpr_multi.rg", "constexpr_lambda.rg",
                "constexpr_print.rg", "constexpr_var.rg")) {
            Path file = failDir.resolve(name);
            String src = Files.readString(file, StandardCharsets.UTF_8);
            String expect = expectLine(src);
            List<Diagnostic> diags = Check.checkSource(src, file.toString());
            assertTrue(name + " " + diags, diags.stream().anyMatch(d -> d.message.contains(expect)));
        }
    }

    @Test
    public void checkDirectoryReportsEachFile() throws Exception {
        Path dir = Files.createTempDirectory("rg-check-dir");
        Files.writeString(dir.resolve("ok.rg"), """
                fn ok(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bad.rg"), """
                fn bad(): Int {
                    return "no";
                }
                """, StandardCharsets.UTF_8);
        List<Diagnostic> diags = Check.checkDirectory(dir);
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.file.contains("bad.rg")));
        assertTrue(diags.toString(), diags.stream().noneMatch(d -> d.file.contains("ok.rg")));
        assertEquals(1, Main.checkProgram(List.of(dir.toString()), dir));

        Path good = Files.createTempDirectory("rg-check-good");
        Files.writeString(good.resolve("a.rg"), "fn a(): Int { return 1; }\n", StandardCharsets.UTF_8);
        Files.writeString(good.resolve("b.rg"), "fn b(): Int { return 2; }\n", StandardCharsets.UTF_8);
        assertEquals(0, Main.checkProgram(List.of(good.toString()), good));
    }

    private static String expectLine(String src) {
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("# expect:")) {
                return t.substring("# expect:".length()).trim();
            }
        }
        return "";
    }
}

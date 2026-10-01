package com.rosegoldc.lang;

import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompletionsTest {

    @Test
    public void attributesAfterAt() {
        Set<String> labels = labels("""
                @|
                fn t() {
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of(
                "test", "deprecated", "constexpr", "ufcs", "optional")));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void stdMembers() {
        Set<String> labels = labels("""
                fn main(): Int {
                    std.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of("math", "str", "io", "v4", "UUID")));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void mathMembers() {
        Set<String> labels = labels("""
                fn main(): Int {
                    math.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("abs"));
        assertTrue(labels.toString(), labels.contains("sqrt"));
    }

    @Test
    public void typesAfterColon() {
        Set<String> labels = labels("""
                fn main(): |
                """);
        assertTrue(labels.toString(), labels.contains("Int"));
        assertTrue(labels.toString(), labels.contains("String"));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void defaultKeywordsAndBuiltins() {
        Set<String> labels = labels("""
                fn main(): Int {
                    |
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("fn"));
        assertTrue(labels.toString(), labels.contains("print"));
        assertTrue(labels.toString(), labels.contains("main"));
        assertTrue(labels.toString(), labels.contains("Int"));
    }

    @Test
    public void localsAndParams() {
        Set<String> labels = labels("""
                fn add(a: Int, b: Int): Int {
                    var sum = a + b;
                    |
                    return sum;
                }
                """);
        assertTrue(labels.toString(), labels.contains("a"));
        assertTrue(labels.toString(), labels.contains("b"));
        assertTrue(labels.toString(), labels.contains("sum"));
    }

    @Test
    public void guessedTypeMembers() {
        Set<String> labels = labels("""
                class Dog {
                    pub var hp: Int = 1;
                    fn speak(): String {
                        return "woof";
                    }
                }
                fn main(): Int {
                    var d = Dog {};
                    d.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("speak"));
        assertTrue(labels.toString(), labels.contains("hp"));
    }

    @Test
    public void enumVariantsAfterDot() {
        Set<String> labels = labels("""
                enum te {
                    one,
                    two,
                    three
                }
                fn main(): Int {
                    var a: te = te.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of("one", "two", "three")));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void stdlibColorVariantsAfterDot() {
        Set<String> labels = labels("""
                fn main(): Int {
                    var b : Color = Color.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of("Red", "Rgb", "Argb", "Black", "White")));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void colorInTypePosition() {
        Set<String> labels = labels("""
                fn main(): Int {
                    var b : Co|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("Color"));
    }

    @Test
    public void enumPayloadVariantsAfterDot() {
        Set<String> labels = labels("""
                enum Shape {
                    Circle(Int),
                    Rect(width: Int, height: Int),
                }
                fn main(): Int {
                    var s = Shape.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("Circle"));
        assertTrue(labels.toString(), labels.contains("Rect"));
    }

    @Test
    public void signalMembers() {
        Set<String> labels = labels("""
                signal clicked();
                fn main(): Int {
                    clicked.|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of(
                "connect", "emit", "emit_deferred", "disconnect")));
    }

    @Test
    public void nothingInsideString() {
        Set<String> labels = labels("""
                fn main(): Int {
                    print("hel|lo");
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.isEmpty());
    }

    @Test
    public void nothingInsideComment() {
        Set<String> labels = labels("""
                fn main(): Int {
                    // pri|
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.isEmpty());
    }

    @Test
    public void cratesAfterImport() {
        Set<String> labels = labels("""
                import |
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of("std", "ui", "math", "str")));
        assertFalse(labels.toString(), labels.contains("fn"));
        assertFalse(labels.toString(), labels.contains("print"));
        labels = labels("""
                from |
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("ui"));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void stdChildrenAfterImportStdDot() {
        Set<String> labels = labels("""
                import std.|
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.containsAll(Set.of("ui", "math", "json")));
        assertFalse(labels.toString(), labels.contains("v4"));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void namesAfterFromImport() {
        Set<String> labels = labels("""
                from ui import |
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(labels.toString(), labels.contains("Color"));
        assertFalse(labels.toString(), labels.contains("fn"));
        assertFalse(labels.toString(), labels.contains("std"));
    }

    @Test
    public void projectModuleAfterImport() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-comp-mod");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import |
                fn main(): Int {
                    return 0;
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Set<String> labels = Completions.suggest(text, dir.resolve("main.rg").toString(), caret).stream()
                .map(item -> item.label)
                .collect(Collectors.toSet());
        assertTrue(labels.toString(), labels.contains("util"));
        assertTrue(labels.toString(), labels.contains("ui"));

        src = """
                from util import |
                fn main(): Int {
                    return 0;
                }
                """;
        caret = src.indexOf('|');
        text = src.substring(0, caret) + src.substring(caret + 1);
        labels = Completions.suggest(text, dir.resolve("main.rg").toString(), caret).stream()
                .map(item -> item.label)
                .collect(Collectors.toSet());
        assertTrue(labels.toString(), labels.contains("hello"));

        src = """
                import util;
                fn main(): Int {
                    return util.|
                }
                """;
        caret = src.indexOf('|');
        text = src.substring(0, caret) + src.substring(caret + 1);
        labels = Completions.suggest(text, dir.resolve("main.rg").toString(), caret).stream()
                .map(item -> item.label)
                .collect(Collectors.toSet());
        assertTrue(labels.toString(), labels.contains("hello"));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void projectModuleAfterAlias() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-comp-alias");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import util as u;
                fn main(): Int {
                    return u.|
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Set<String> labels = Completions.suggest(text, dir.resolve("main.rg").toString(), caret).stream()
                .map(item -> item.label)
                .collect(Collectors.toSet());
        assertTrue(labels.toString(), labels.contains("hello"));
        assertFalse(labels.toString(), labels.contains("fn"));
    }

    @Test
    public void mathAfterAlias() {
        Set<String> labels = labels("""
                import math as m;
                fn main(): Int {
                    return m.|
                }
                """);
        assertTrue(labels.toString(), labels.contains("abs"));
    }

    private static Set<String> labels(String src) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        String text = src.substring(0, caret) + src.substring(caret + 1);
        return Completions.suggest(text, "t.rg", caret).stream()
                .map(item -> item.label)
                .collect(Collectors.toSet());
    }
}

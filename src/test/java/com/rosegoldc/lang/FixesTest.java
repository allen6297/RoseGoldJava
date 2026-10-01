package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FixesTest {

    @Test
    public void wrapWithTry() {
        Applied a = at("""
                fn boom() throws {
                    throw "x";
                }
                fn main(): Int {
                    |boom();
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Wrap with try"));
        String out = Fixes.apply(a.text, a.first("Wrap with try"));
        assertTrue(out, out.contains("try boom();"));
    }

    @Test
    public void missingMatchArms() {
        Applied a = at("""
                enum Color {
                    Red,
                    Green,
                }
                fn main(): Int {
                    var c = Color.Red;
                    |match c {
                        Red { pass; }
                    }
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Add missing match arms"));
        String out = Fixes.apply(a.text, a.first("Add missing match arms"));
        assertTrue(out, out.contains("Green { pass; }"));
    }

    @Test
    public void missingMatchArmsInsideBody() {
        Applied a = at("""
                enum Color {
                    Red,
                    Green,
                }
                fn main(): Int {
                    var c = Color.Red;
                    match c {
                        Red { |pass; }
                    }
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Add missing match arms"));
    }

    @Test
    public void missingSwitchArms() {
        Applied a = at("""
                enum Color {
                    Red,
                    Green,
                }
                fn main(): Int {
                    var c = Color.Red;
                    |switch c {
                        Red { pass; }
                    }
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Add missing match arms"));
        String out = Fixes.apply(a.text, a.first("Add missing match arms"));
        assertTrue(out, out.contains("Green { pass; }"));
    }

    @Test
    public void payloadMatchArms() {
        Applied a = at("""
                enum Shape {
                    Circle(Int),
                    Rect(width: Int, height: Int),
                }
                fn main(): Int {
                    var s = Shape.Circle(1);
                    |match s {
                        Circle(r) { pass; }
                    }
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Add missing match arms"));
        String out = Fixes.apply(a.text, a.first("Add missing match arms"));
        assertTrue(out, out.contains("Rect(width: width, height: height) { pass; }"));
    }

    @Test
    public void addTraitMethod() {
        Applied a = at("""
                trait Named {
                    fn label(self): String;
                }
                class Point impl |Named {
                    var x: Int = 0;
                }
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Add method 'label'"));
        String out = Fixes.apply(a.text, a.first("Add method 'label'"));
        assertTrue(out, out.contains("fn label(self): String"));
        assertTrue(out, out.contains("return \"\";"));
    }

    @Test
    public void implementMissingMethods() {
        Applied a = at("""
                trait Named {
                    fn label(self): String;
                    fn tag(self): Int;
                }
                class Point impl |Named {
                    var x: Int = 0;
                }
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Implement missing methods for Named"));
        String out = Fixes.apply(a.text, a.first("Implement missing methods for Named"));
        assertTrue(out, out.contains("fn label(self): String"));
        assertTrue(out, out.contains("fn tag(self): Int"));
        assertTrue(out, out.contains("return 0;"));
    }

    @Test
    public void awaitFuture() {
        Applied a = at("""
                async fn work(): Int {
                    return 1;
                }
                async fn main(): Int {
                    |work();
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Await Future"));
        String out = Fixes.apply(a.text, a.first("Await Future"));
        assertTrue(out, out.contains("await work();"));
    }

    @Test
    public void importCrate() throws Exception {
        Path tmp = Files.createTempDirectory("rg-fixes-std");
        Path lib = tmp.resolve("builtin").resolve("std").resolve("lib.rg");
        Files.createDirectories(lib.getParent());
        Files.writeString(lib, """
                trait Identifiable {
                    fn id(self): String;
                }
                """, StandardCharsets.UTF_8);
        Path file = tmp.resolve("app.rg");
        String src = """
                class Block impl |Identifiable {
                    var x: Int = 0;
                }
                fn main(): Int {
                    return 0;
                }
                """;
        Applied a = at(src, file.toString());
        assertTrue(a.titles().toString(), a.titles().contains("Import crate std"));
        String out = Fixes.apply(a.text, a.first("Import crate std"));
        assertTrue(out, out.startsWith("import std;"));
    }

    @Test
    public void importCrateUiForColor() throws Exception {
        Path tmp = Files.createTempDirectory("rg-fixes-ui");
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
                    var b : |Color = Color.rgb();
                }
                """;
        Applied a = at(src, file.toString());
        assertTrue(a.titles().toString(), a.titles().contains("Import crate ui"));
        String out = Fixes.apply(a.text, a.first("Import crate ui"));
        assertTrue(out, out.startsWith("import ui;"));
    }

    @Test
    public void importProjectModule() throws Exception {
        Path dir = Files.createTempDirectory("rg-fixes-mod");
        Main.newProgram(List.of(), dir);
        Main.newProgram(List.of("module", "util"), dir);
        String src = """
                fn main(): Int {
                    return util.hel|lo();
                }
                """;
        Applied a = at(src, dir.resolve("main.rg").toString());
        assertTrue(a.titles().toString(), a.titles().contains("Import crate util"));
        String out = Fixes.apply(a.text, a.first("Import crate util"));
        assertTrue(out, out.startsWith("import util;"));

        src = """
                fn main(): Int {
                    return hel|lo();
                }
                """;
        a = at(src, dir.resolve("main.rg").toString());
        assertTrue(a.titles().toString(), a.titles().contains("Import crate util"));
        out = Fixes.apply(a.text, a.first("Import crate util"));
        assertTrue(out, out.startsWith("import util;"));
    }

    @Test
    public void removeUnusedImport() {
        Applied a = at("""
                import ma|th;
                fn main(): Int {
                    return 0;
                }
                """);
        assertTrue(a.titles().toString(), a.titles().contains("Remove unused import math"));
        String out = Fixes.apply(a.text, a.first("Remove unused import math"));
        assertFalse(out, out.contains("import math"));
        assertTrue(out, out.contains("fn main()"));
    }

    private static Applied at(String src) {
        return at(src, "fixes.rg");
    }

    private static Applied at(String src, String path) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        String text = src.substring(0, caret) + src.substring(caret + 1);
        return new Applied(text, Fixes.suggest(text, path, caret));
    }

    private static final class Applied {
        final String text;
        final List<Fixes.Action> actions;

        Applied(String text, List<Fixes.Action> actions) {
            this.text = text;
            this.actions = actions;
        }

        Set<String> titles() {
            return actions.stream().map(a -> a.title).collect(Collectors.toSet());
        }

        Fixes.Action first(String title) {
            return actions.stream().filter(a -> a.title.equals(title)).findFirst().orElseThrow();
        }
    }
}

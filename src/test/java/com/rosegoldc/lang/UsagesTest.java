package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UsagesTest {

    @Test
    public void functionDeclAndCall() {
        List<Usages.Hit> hits = usages("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return ad|d(1, 2);
                }
                """);
        assertEquals(2, hits.size());
        assertEquals("add", hits.get(0).name);
        assertTrue(hits.get(0).write);
        assertEquals(1, hits.get(0).line);
        assertFalse(hits.get(1).write);
        assertEquals(5, hits.get(1).line);
        assertEquals("add", slice(hits.get(0)));
        assertEquals("add", slice(hits.get(1)));
    }

    @Test
    public void localReadsAndWrite() {
        List<Usages.Hit> hits = usages("""
                fn main(): Int {
                    var sum: Int = 1;
                    sum = sum + 1;
                    return su|m;
                }
                """);
        assertEquals(4, hits.size());
        assertTrue(hits.get(0).write);
        assertTrue(hits.get(1).write);
        assertFalse(hits.get(2).write);
        assertFalse(hits.get(3).write);
    }

    @Test
    public void qualifiedMethodSkipsOtherSpeak() {
        List<Usages.Hit> hits = usages("""
                class Dog {
                    fn speak(): String {
                        return "woof";
                    }
                }
                class Cat {
                    fn speak(): String {
                        return "meow";
                    }
                }
                fn main(): Int {
                    var d = Dog {};
                    print(d.spe|ak());
                    print(Cat {}.speak());
                    return 0;
                }
                """);
        List<Integer> lines = hits.stream().map(h -> h.line).collect(Collectors.toList());
        assertTrue(lines.toString(), lines.contains(2));
        assertTrue(lines.toString(), lines.contains(13));
        assertFalse(lines.toString(), lines.contains(14));
    }

    @Test
    public void nothingInsideString() {
        List<Usages.Hit> hits = usages("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    print("ad|d");
                    return 0;
                }
                """);
        assertTrue(hits.isEmpty());
    }

    @Test
    public void stdlibMemberIncludesDefinition() {
        String src = """
                fn main(): Int {
                    return math.ab|s(-1);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> hits = Usages.includingCrates(text, "t.rg", caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.name.equals("abs") && h.path.replace('\\', '/').contains("builtin/std/math/")));
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                !h.write && h.qualifier.equals("math")));
    }

    @Test
    public void fromImportIncludesCrateDef() {
        String src = """
                from ui import Col|or;
                fn main(): Int {
                    return 0;
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> hits = Usages.includingCrates(text, "t.rg", caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.name.equals("Color") && h.path.replace('\\', '/').contains("builtin/std/ui/color.rg")));
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.path.equals("t.rg") && h.name.equals("Color")));
    }

    @Test
    public void projectModuleIncludesLib() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-usages-mod");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import util;
                fn main(): Int {
                    return util.hel|lo();
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> hits = Usages.includingCrates(text, dir.resolve("main.rg").toString(), caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.name.equals("hello") && h.path.replace('\\', '/').contains("util/lib.rg")));
        assertTrue(hits.toString(), hits.stream().anyMatch(h -> h.qualifier.equals("util")));
    }

    @Test
    public void projectModuleAliasIncludesLib() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-usages-alias");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import util as u;
                fn main(): Int {
                    return u.hel|lo();
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> hits = Usages.includingCrates(text, dir.resolve("main.rg").toString(), caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.name.equals("hello") && h.path.replace('\\', '/').contains("util/lib.rg")));
        assertTrue(hits.toString(), hits.stream().anyMatch(h -> h.qualifier.equals("u")));
    }

    @Test
    public void fromImportAliasIncludesLib() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-usages-from");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                from util import hello as hi;
                fn main(): Int {
                    return h|i();
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> hits = Usages.includingCrates(text, dir.resolve("main.rg").toString(), caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.name.equals("hello") && h.path.replace('\\', '/').contains("util/lib.rg")));
        assertTrue(hits.toString(), hits.stream().anyMatch(h -> h.name.equals("hi")));
    }

    private static String source;

    private static List<Usages.Hit> usages(String src) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        source = src.substring(0, caret) + src.substring(caret + 1);
        return Usages.inFile(source, "t.rg", caret);
    }

    private static String slice(Usages.Hit hit) {
        return source.substring(hit.start, hit.end);
    }
}

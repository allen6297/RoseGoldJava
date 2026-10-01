package com.rosegoldc.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GotoTest {

    @Test
    public void jumpsToFunction() {
        Goto.Loc loc = go("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return ad|d(1, 2);
                }
                """);
        assertNotNull(loc);
        assertEquals("add", loc.name);
        assertEquals(1, loc.line);
        assertEquals("add", slice(loc));
    }

    @Test
    public void jumpsToLocal() {
        Goto.Loc loc = go("""
                fn main(): Int {
                    var sum: Int = 1;
                    return su|m;
                }
                """);
        assertNotNull(loc);
        assertEquals("sum", loc.name);
        assertEquals(2, loc.line);
        assertEquals("sum", slice(loc));
    }

    @Test
    public void jumpsToParam() {
        Goto.Loc loc = go("""
                fn add(a: Int, b: Int): Int {
                    return a| + b;
                }
                """);
        assertNotNull(loc);
        assertEquals("a", loc.name);
        assertEquals(1, loc.line);
        assertEquals("a", slice(loc));
    }

    @Test
    public void jumpsToMethod() {
        Goto.Loc loc = go("""
                class Dog {
                    fn speak(): String {
                        return "woof";
                    }
                }
                fn main(): Int {
                    var d = Dog {};
                    print(d.spe|ak());
                    return 0;
                }
                """);
        assertNotNull(loc);
        assertEquals("speak", loc.name);
        assertEquals(2, loc.line);
        assertEquals("speak", slice(loc));
    }

    @Test
    public void jumpsToClass() {
        Goto.Loc loc = go("""
                class Dog {
                    fn speak(): String {
                        return "woof";
                    }
                }
                fn main(): Int {
                    var d = Do|g {};
                    return 0;
                }
                """);
        assertNotNull(loc);
        assertEquals("Dog", loc.name);
        assertEquals(1, loc.line);
        assertEquals("Dog", slice(loc));
    }

    @Test
    public void jumpsToField() {
        Goto.Loc loc = go("""
                class Dog {
                    pub var hp: Int = 1;
                    fn hit(): Int {
                        return h|p;
                    }
                }
                """);
        assertNotNull(loc);
        assertEquals("hp", loc.name);
        assertEquals(2, loc.line);
        assertEquals("hp", slice(loc));
    }

    @Test
    public void builtinHasNoTarget() {
        assertNull(go("""
                fn main(): Int {
                    pri|nt("hi");
                    return 0;
                }
                """));
    }

    @Test
    public void jumpsToImportCrate() {
        Goto.Loc loc = go("""
                import u|i;
                fn main(): Int {
                    return 0;
                }
                """);
        assertNotNull(loc);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("builtin/std/ui/lib.rg"));
    }

    @Test
    public void jumpsToFromImportName() {
        Goto.Loc loc = go("""
                from ui import Col|or;
                fn main(): Int {
                    return 0;
                }
                """);
        assertNotNull(loc);
        assertEquals("Color", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("builtin/std/ui/color.rg"));
    }

    @Test
    public void jumpsToStdlibMember() {
        Goto.Loc loc = go("""
                fn main(): Int {
                    return math.ab|s(-1);
                }
                """);
        assertNotNull(loc);
        assertEquals("abs", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("builtin/std/math/"));
    }

    @Test
    public void jumpsToProjectModule() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-goto-mod");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import ut|il;
                fn main(): Int {
                    return util.hello();
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Goto.Loc loc = Goto.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(loc);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("/util/lib.rg")
                || loc.path.replace('\\', '/').contains("\\util\\lib.rg")
                || loc.path.replace('\\', '/').endsWith("util/lib.rg"));

        src = """
                import util;
                fn main(): Int {
                    return util.hel|lo();
                }
                """;
        caret = src.indexOf('|');
        text = src.substring(0, caret) + src.substring(caret + 1);
        loc = Goto.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(loc);
        assertEquals("hello", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("util/lib.rg"));

        src = """
                import util as u;
                fn main(): Int {
                    return u.hel|lo();
                }
                """;
        caret = src.indexOf('|');
        text = src.substring(0, caret) + src.substring(caret + 1);
        loc = Goto.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(loc);
        assertEquals("hello", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("util/lib.rg"));
    }

    @Test
    public void jumpsToFromImportAlias() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-goto-from");
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
        Goto.Loc loc = Goto.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(loc);
        assertEquals("hello", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("util/lib.rg"));

        src = """
                from util import hello as h|i;
                fn main(): Int {
                    return hi();
                }
                """;
        caret = src.indexOf('|');
        text = src.substring(0, caret) + src.substring(caret + 1);
        loc = Goto.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(loc);
        assertEquals("hello", loc.name);
        assertTrue(loc.path, loc.path.replace('\\', '/').contains("util/lib.rg"));
    }

    @Test
    public void nothingInsideString() {
        assertNull(go("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    print("ad|d");
                    return 0;
                }
                """));
    }

    private static String source;
    private static Goto.Loc last;

    private static Goto.Loc go(String src) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        source = src.substring(0, caret) + src.substring(caret + 1);
        last = Goto.at(source, "t.rg", caret);
        return last;
    }

    private static String slice(Goto.Loc loc) {
        assertNotNull(loc);
        assertTrue(loc.start >= 0 && loc.end <= source.length() && loc.end >= loc.start);
        return source.substring(loc.start, loc.end);
    }
}

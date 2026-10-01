package com.rosegoldc.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HoverTest {

    @Test
    public void functionSignatureAndDocs() {
        Hover.Info info = hover("""
                /// add two numbers
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return ad|d(1, 2);
                }
                """);
        assertNotNull(info);
        assertEquals("add", info.name);
        assertEquals("function", info.kind);
        assertEquals("fn add(a: Int, b: Int): Int", info.signature);
        assertEquals("add two numbers", info.doc);
    }

    @Test
    public void deprecatedFn() {
        Hover.Info info = hover("""
                @deprecated
                fn boom() throws {
                    throw "x";
                }
                fn main(): Int {
                    bo|om();
                    return 0;
                }
                """);
        assertNotNull(info);
        assertTrue(info.deprecated);
        assertTrue(info.signature, info.signature.contains("throws"));
    }

    @Test
    public void builtinPrint() {
        Hover.Info info = hover("""
                fn main(): Int {
                    pri|nt("hi");
                    return 0;
                }
                """);
        assertNotNull(info);
        assertEquals("print(...)", info.signature);
    }

    @Test
    public void mathMember() {
        Hover.Info info = hover("""
                fn main(): Int {
                    return math.ab|s(-1);
                }
                """);
        assertNotNull(info);
        assertEquals("fn abs(n: Int): Int", info.signature);
        assertEquals("math", info.crate);
    }

    @Test
    public void importCrate() {
        Hover.Info info = hover("""
                import u|i;
                fn main(): Int {
                    return 0;
                }
                """);
        assertNotNull(info);
        assertEquals("module", info.kind);
        assertEquals("module ui", info.signature);
        assertEquals("ui", info.crate);
    }

    @Test
    public void fromImportEnum() {
        Hover.Info info = hover("""
                from ui import Col|or;
                fn main(): Int {
                    return 0;
                }
                """);
        assertNotNull(info);
        assertEquals("enum", info.kind);
        assertEquals("enum Color", info.signature);
        assertEquals("ui", info.crate);
    }

    @Test
    public void projectModuleMember() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-hover-mod");
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
        Hover.Info info = Hover.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hello", info.name);
        assertEquals("fn hello(): Int", info.signature);
        assertEquals("util", info.crate);
    }

    @Test
    public void projectModuleAlias() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-hover-alias");
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
        Hover.Info info = Hover.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hello", info.name);
        assertEquals("fn hello(): Int", info.signature);
        assertEquals("util", info.crate);
    }

    @Test
    public void fromImportAlias() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-hover-from");
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
        Hover.Info info = Hover.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("fn hello(): Int", info.signature);
        assertEquals("util", info.crate);
    }

    @Test
    public void localVar() {
        Hover.Info info = hover("""
                fn main(): Int {
                    var sum: Int = 1;
                    return su|m;
                }
                """);
        assertNotNull(info);
        assertEquals("var sum: Int", info.signature);
    }

    @Test
    public void methodOnGuessedType() {
        Hover.Info info = hover("""
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
        assertNotNull(info);
        assertEquals("fn speak(): String", info.signature);
    }

    @Test
    public void classDecl() {
        Hover.Info info = hover("""
                abstract class Ani|mal {
                    abstract fn speak(): String;
                }
                """);
        assertNotNull(info);
        assertEquals("abstract class Animal", info.signature);
    }

    @Test
    public void blockCommentDocs() {
        Hover.Info info = hover("""
                /#
                 this is a bird
                #/
                class bi|rd extends animal {
                }
                """);
        assertNotNull(info);
        assertEquals("class bird extends animal", info.signature);
        assertEquals("this is a bird", info.doc);
    }

    @Test
    public void nothingInsideString() {
        assertNull(hover("""
                fn main(): Int {
                    print("ad|d");
                    return 0;
                }
                """));
    }

    private static Hover.Info hover(String src) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        String text = src.substring(0, caret) + src.substring(caret + 1);
        return Hover.at(text, "t.rg", caret);
    }
}

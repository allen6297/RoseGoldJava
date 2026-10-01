package com.rosegoldc.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SignaturesTest {

    @Test
    public void localFnSecondArg() {
        Signatures.Info info = at("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(1, |2);
                }
                """);
        assertNotNull(info);
        assertEquals("add", info.name);
        assertEquals("add(a: Int, b: Int): Int", info.label);
        assertEquals(1, info.activeParam);
        assertEquals("a: Int", info.params.get(0));
        assertEquals("b: Int", info.params.get(1));
    }

    @Test
    public void incompleteCall() {
        Signatures.Info info = at("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(|
                }
                """);
        assertNotNull(info);
        assertEquals("add", info.name);
        assertEquals(0, info.activeParam);
        assertEquals("add(a: Int, b: Int): Int", info.label);
    }

    @Test
    public void afterComma() {
        Signatures.Info info = at("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(1,|
                }
                """);
        assertNotNull(info);
        assertEquals(1, info.activeParam);
    }

    @Test
    public void builtinLen() {
        Signatures.Info info = at("""
                fn main(): Int {
                    return len(|);
                }
                """);
        assertNotNull(info);
        assertEquals("len", info.name);
        assertEquals("len(xs)", info.label);
        assertEquals(0, info.activeParam);
    }

    @Test
    public void builtinPrint() {
        Signatures.Info info = at("""
                fn main(): Int {
                    print(|
                    return 0;
                }
                """);
        assertNotNull(info);
        assertEquals("print(...)", info.label);
    }

    @Test
    public void methodSkipsSelf() {
        Signatures.Info info = at("""
                class Cat {
                    fn speak(self, times: Int): Int {
                        return times;
                    }
                }
                fn main(): Int {
                    return Cat {}.speak(|);
                }
                """);
        assertNotNull(info);
        assertEquals("speak", info.name);
        assertEquals("speak(times: Int): Int", info.label);
        assertEquals(0, info.activeParam);
        assertEquals(1, info.params.size());
    }

    @Test
    public void nestedInnerCall() {
        Signatures.Info info = at("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(add(1, |2), 3);
                }
                """);
        assertNotNull(info);
        assertEquals("add", info.name);
        assertEquals(1, info.activeParam);
    }

    @Test
    public void ifIsNotACall() {
        Signatures.Info info = at("""
                fn main(): Int {
                    if (|true) {
                        return 1;
                    }
                    return 0;
                }
                """);
        assertNull(info);
    }

    @Test
    public void throwingFn() {
        Signatures.Info info = at("""
                fn boom(msg: String) throws {
                    throw msg;
                }
                fn main(): Int {
                    try boom(|);
                    return 0;
                }
                """);
        assertNotNull(info);
        assertTrue(info.label, info.label.contains("throws"));
        assertEquals("msg: String", info.params.getFirst());
    }

    @Test
    public void stdlibMathAbs() {
        Signatures.Info info = at("""
                fn main(): Int {
                    return math.abs(|-1);
                }
                """);
        assertNotNull(info);
        assertEquals("abs", info.name);
        assertEquals("abs(n: Int): Int", info.label);
        assertEquals(0, info.activeParam);
    }

    @Test
    public void projectModuleCall() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-sig-mod");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import util;
                fn main(): Int {
                    return util.hello(|);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Signatures.Info info = Signatures.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hello", info.name);
        assertEquals("hello(): Int", info.label);
    }

    @Test
    public void projectModuleAliasCall() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-sig-alias");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                import util as u;
                fn main(): Int {
                    return u.hello(|);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Signatures.Info info = Signatures.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hello", info.name);
        assertEquals("hello(): Int", info.label);
    }

    @Test
    public void fromImportCall() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-sig-from");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                from util import hello;
                fn main(): Int {
                    return hello(|);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Signatures.Info info = Signatures.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hello", info.name);
        assertEquals("hello(): Int", info.label);
    }

    @Test
    public void fromImportAliasCall() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-sig-from-alias");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        String src = """
                from util import hello as hi;
                fn main(): Int {
                    return hi(|);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        Signatures.Info info = Signatures.at(text, dir.resolve("main.rg").toString(), caret);
        assertNotNull(info);
        assertEquals("hi", info.name);
        assertEquals("hi(): Int", info.label);
    }

    @Test
    public void cppStyleOffset() {
        Signatures.Info info = Signatures.at("add(1, 2)", "t.rg", 7);
        assertNotNull(info);
        assertEquals("add", info.name);
        assertEquals(1, info.activeParam);
    }

    private static Signatures.Info at(String src) {
        int caret = src.indexOf('|');
        assertTrue("missing caret marker", caret >= 0);
        String text = src.substring(0, caret) + src.substring(caret + 1);
        return Signatures.at(text, "sig.rg", caret);
    }
}

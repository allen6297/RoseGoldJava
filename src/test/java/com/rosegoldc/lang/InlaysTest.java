package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InlaysTest {

    @Test
    public void paramNamesAtCall() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(1, 2);
                }
                """, "inlays.rg");
        List<Inlays.Hint> params = params(hints);
        assertEquals(2, params.size());
        assertEquals("a:", params.get(0).label);
        assertEquals("b:", params.get(1).label);
        assertEquals(5, params.get(0).line);
        assertEquals(5, params.get(1).line);
        assertTrue(params.get(0).col < params.get(1).col);
    }

    @Test
    public void skipsSelfOnMethod() {
        List<Inlays.Hint> hints = Inlays.collect("""
                class Cat {
                    fn speak(self, times: Int): Int {
                        return times;
                    }
                }
                fn main(): Int {
                    return Cat {}.speak(3);
                }
                """, "inlays.rg");
        List<Inlays.Hint> params = params(hints);
        assertEquals(1, params.size());
        assertEquals("times:", params.get(0).label);
    }

    @Test
    public void typeHintOnUntypedVar() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn main(): Int {
                    var n = 1;
                    return n;
                }
                """, "inlays.rg");
        List<Inlays.Hint> types = types(hints);
        assertEquals(1, types.size());
        assertEquals(": Int", types.get(0).label);
        assertEquals(2, types.get(0).line);
    }

    @Test
    public void noTypeHintWhenAnnotated() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn main(): Int {
                    var n: Int = 1;
                    return n;
                }
                """, "inlays.rg");
        assertTrue(types(hints).isEmpty());
    }

    @Test
    public void builtinAssert() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn main(): Int {
                    assert(true);
                    return 0;
                }
                """, "inlays.rg");
        List<Inlays.Hint> params = params(hints);
        assertEquals(1, params.size());
        assertEquals("cond:", params.get(0).label);
    }

    @Test
    public void stdlibMathAbs() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn main(): Int {
                    return math.abs(-1);
                }
                """, "inlays.rg");
        List<Inlays.Hint> params = params(hints);
        assertEquals(1, params.size());
        assertEquals("n:", params.get(0).label);
    }

    @Test
    public void projectModuleCall() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rg-inlay-mod");
        Main.newProgram(java.util.List.of(), dir);
        Main.newProgram(java.util.List.of("module", "util"), dir);
        java.nio.file.Files.writeString(dir.resolve("util").resolve("lib.rg"), """
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                """, java.nio.charset.StandardCharsets.UTF_8);
        String src = """
                import util;
                fn main(): Int {
                    return util.add(1, 2);
                }
                """;
        List<Inlays.Hint> params = params(Inlays.collect(src, dir.resolve("main.rg").toString()));
        assertEquals(2, params.size());
        assertEquals("a:", params.get(0).label);
        assertEquals("b:", params.get(1).label);

        src = """
                from util import add;
                fn main(): Int {
                    return add(1, 2);
                }
                """;
        params = params(Inlays.collect(src, dir.resolve("main.rg").toString()));
        assertEquals(2, params.size());
        assertEquals("a:", params.get(0).label);
        assertEquals("b:", params.get(1).label);

        src = """
                import util as u;
                fn main(): Int {
                    return u.add(1, 2);
                }
                """;
        params = params(Inlays.collect(src, dir.resolve("main.rg").toString()));
        assertEquals(2, params.size());
        assertEquals("a:", params.get(0).label);
        assertEquals("b:", params.get(1).label);

        src = """
                from util import add as plus;
                fn main(): Int {
                    return plus(1, 2);
                }
                """;
        params = params(Inlays.collect(src, dir.resolve("main.rg").toString()));
        assertEquals(2, params.size());
        assertEquals("a:", params.get(0).label);
        assertEquals("b:", params.get(1).label);
    }

    @Test
    public void printHasNoParamHint() {
        List<Inlays.Hint> hints = Inlays.collect("""
                fn main(): Int {
                    print("hi");
                    return 0;
                }
                """, "inlays.rg");
        assertTrue(params(hints).isEmpty());
    }

    private static List<Inlays.Hint> params(List<Inlays.Hint> hints) {
        return hints.stream().filter(h -> h.kind == Inlays.Kind.Param).collect(Collectors.toList());
    }

    private static List<Inlays.Hint> types(List<Inlays.Hint> hints) {
        return hints.stream().filter(h -> h.kind == Inlays.Kind.Type).collect(Collectors.toList());
    }
}

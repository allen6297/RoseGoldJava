package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenameTest {

    @Test
    public void validNames() {
        assertTrue(Rename.isValidName("add"));
        assertTrue(Rename.isValidName("Coin"));
        assertTrue(Rename.isValidName("_x"));
        assertFalse(Rename.isValidName(""));
        assertFalse(Rename.isValidName("1x"));
        assertFalse(Rename.isValidName("a-b"));
        assertFalse(Rename.isValidName("fn"));
        assertFalse(Rename.isValidName("self"));
        assertFalse(Rename.isValidName("async"));
    }

    @Test
    public void skipsBuiltinUnlessOriginIsBuiltin() {
        assertTrue(Rename.skipFile("src/app.rg", "C:/CODE/RoseGoldJava/builtin/std/io/lib.rg"));
        assertTrue(Rename.skipFile("app.rg", "builtin/std/io/lib.rg"));
        assertFalse(Rename.skipFile("builtin/std/io/lib.rg", "builtin/std/io/lib.rg"));
        assertFalse(Rename.skipFile("src/app.rg", "src/other.rg"));
    }

    @Test
    public void appliesHitsBackToFront() {
        String src = """
                fn add(a: Int, b: Int): Int {
                    return a + b;
                }
                fn main(): Int {
                    return add(1, 2);
                }
                """;
        List<Usages.Hit> hits = Usages.ofName(src, "t.rg", "add", "");
        assertEquals(2, hits.size());
        String out = Rename.apply(src, hits, "sum");
        assertTrue(out, out.contains("fn sum("));
        assertTrue(out, out.contains("return sum(1, 2);"));
        assertFalse(out.contains("add"));
    }

    @Test
    public void qualifiedRenameKeepsOtherSpeak() {
        String src = """
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
                    print(d.speak());
                    print(Cat {}.speak());
                    return 0;
                }
                """;
        int caret = src.indexOf("d.speak") + 2;
        List<Usages.Hit> hits = Usages.inFile(src, "t.rg", caret);
        String out = Rename.apply(src, hits, "bark");
        assertTrue(out, out.contains("d.bark()"));
        assertTrue(out, out.contains("Cat {}.speak()"));
        assertFalse(out.contains("d.speak()"));
    }

    @Test
    public void skipsStdlibCrateOnRewrite() {
        String src = """
                fn main(): Int {
                    return math.ab|s(-1);
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        List<Usages.Hit> all = Usages.includingCrates(text, "t.rg", caret);
        assertTrue(all.toString(), all.stream().anyMatch(h -> Rename.skipFile("t.rg", h.path)));
        List<Usages.Hit> rewrite = Rename.rewriteHits(text, "t.rg", caret);
        assertTrue(rewrite.toString(), rewrite.stream().anyMatch(h -> h.qualifier.equals("math")));
        assertTrue(rewrite.toString(), rewrite.stream().noneMatch(h -> Rename.isStdlibFile(h.path)));
    }

    @Test
    public void rewritesProjectModuleCrate() throws Exception {
        Path dir = Files.createTempDirectory("rg-rename-mod");
        Main.newProgram(List.of(), dir);
        Main.newProgram(List.of("module", "util"), dir);
        Path lib = dir.resolve("util").resolve("lib.rg");
        String src = """
                import util;
                fn main(): Int {
                    return util.hel|lo();
                }
                """;
        int caret = src.indexOf('|');
        String text = src.substring(0, caret) + src.substring(caret + 1);
        String mainPath = dir.resolve("main.rg").toString();
        assertFalse(lib.toString(), Rename.skipFile(mainPath, lib.toString()));
        List<Usages.Hit> hits = Rename.rewriteHits(text, mainPath, caret);
        assertTrue(hits.toString(), hits.stream().anyMatch(h ->
                h.write && h.path.replace('\\', '/').contains("util/lib.rg")));
        String newMain = Rename.apply(text, hits.stream()
                .filter(h -> Stdlib.sameRgFile(h.path, mainPath))
                .toList(), "greet");
        String newLib = Rename.apply(Files.readString(lib), hits.stream()
                .filter(h -> Stdlib.sameRgFile(h.path, lib.toString()))
                .toList(), "greet");
        assertTrue(newMain, newMain.contains("util.greet()"));
        assertTrue(newLib, newLib.contains("fn greet("));
        assertFalse(newLib, newLib.contains("fn hello("));
    }
}

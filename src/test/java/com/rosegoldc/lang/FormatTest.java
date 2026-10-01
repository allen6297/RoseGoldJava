package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FormatTest {

    @Test
    public void spacesAfterColon() {
        Format.Result f = Format.formatSource("fn main():Int{\nreturn 0;\n}\n", "fmt_check.rg", new Format.Options());
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("fn main(): Int"));
        Format.Result again = Format.formatSource(f.out, "fmt_check.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);
    }

    @Test
    public void keepsFloatLexemes() {
        Format.Result f = Format.formatSource(
                "fn main(): Float {\n  return 1.0 + 2.50;\n}\n",
                "fmt_float.rg",
                new Format.Options()
        );
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("1.0") && f.out.contains("2.50"));
        Format.Result again = Format.formatSource(f.out, "fmt_float.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);
    }

    @Test
    public void keepsLineCommentsBetweenItems() {
        Format.Result f = Format.formatSource(
                "fn a(): Int {\n  return 1;\n}\n\n// keep me\nfn b(): Int {\n  return 2;\n}\n",
                "fmt_comment.rg",
                new Format.Options()
        );
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("// keep me"));
        Format.Result again = Format.formatSource(f.out, "fmt_comment.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);
    }

    @Test
    public void keepsBlockComments() {
        Format.Result f = Format.formatSource(
                "/# block note #/\nfn main(): Int {\n  return 0;\n}\n",
                "fmt_block.rg",
                new Format.Options()
        );
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("/# block note #/"));
        Format.Result again = Format.formatSource(f.out, "fmt_block.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);
    }

    @Test
    public void trailingCommentsCompactAndStrip() {
        String src = "fn a(): Int {\n  return 1; // trail\n}\n\nfn b(): Int {\n  return 2;\n}\n";
        Format.Result f = Format.formatSource(src, "fmt_inline.rg", new Format.Options());
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("return 1; // trail"));
        Format.Result again = Format.formatSource(f.out, "fmt_inline.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);

        Format.Options compact = new Format.Options();
        compact.blankBetweenItems = false;
        Format.Result c = Format.formatSource(src, "fmt_compact.rg", compact);
        assertTrue(c.message, c.ok);
        assertTrue(c.out, c.out.contains("}\nfn b()"));
        assertFalse(c.out, c.out.contains("}\n\nfn b()"));

        Format.Options noc = new Format.Options();
        noc.keepComments = false;
        Format.Result n = Format.formatSource(src, "fmt_nocomment.rg", noc);
        assertTrue(n.message, n.ok);
        assertFalse(n.out, n.out.contains("// trail"));
        assertTrue(n.out, n.out.contains("return 1;"));
    }

    @Test
    public void bodyComments() {
        Format.Result f = Format.formatSource(
                "fn main(): Int {\n  // inside\n  return 0; // trail\n}\n",
                "fmt_body_comment.rg",
                new Format.Options()
        );
        assertTrue(f.message, f.ok);
        assertTrue(f.out, f.out.contains("// inside"));
        assertTrue(f.out, f.out.contains("return 0; // trail"));
        Format.Result again = Format.formatSource(f.out, "fmt_body_comment.rg", new Format.Options());
        assertTrue(again.message, again.ok);
        assertEquals(f.out, again.out);
    }

    @Test
    public void parseErrorFails() {
        Format.Result f = Format.formatSource("fn main( {\n", "bad.rg", new Format.Options());
        assertFalse(f.ok);
        assertEquals(1, f.exitCode);
        assertFalse(f.message.isEmpty());
    }

    @Test
    public void listRgFilesSkipsHiddenAndBuild() throws Exception {
        Path dir = Files.createTempDirectory("rg-fmt-list");
        Files.writeString(dir.resolve("a.rg"), "fn a(): Int { return 0; }\n", StandardCharsets.UTF_8);
        Path nested = dir.resolve("src");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("b.rg"), "fn b(): Int { return 0; }\n", StandardCharsets.UTF_8);
        Path hidden = dir.resolve(".cache");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("skip.rg"), "fn skip(): Int { return 0; }\n", StandardCharsets.UTF_8);
        Path build = dir.resolve("build");
        Files.createDirectories(build);
        Files.writeString(build.resolve("out.rg"), "fn out(): Int { return 0; }\n", StandardCharsets.UTF_8);
        List<Path> files = Format.listRgFiles(dir);
        assertEquals(2, files.size());
        assertTrue(files.get(0).endsWith("a.rg"));
        assertTrue(files.get(1).endsWith(Path.of("src", "b.rg")));
    }

    @Test
    public void fmtDirectoryCheckAndWrite() throws Exception {
        Path dir = Files.createTempDirectory("rg-fmt-dir");
        Path messy = dir.resolve("messy.rg");
        Path ok = dir.resolve("ok.rg");
        String messySrc = "fn main():Int{\nreturn 0;\n}\n";
        String expected = Format.formatSource(messySrc, "messy.rg", new Format.Options()).out;
        Files.writeString(messy, messySrc, StandardCharsets.UTF_8);
        Files.writeString(ok, expected, StandardCharsets.UTF_8);
        assertEquals(1, Main.fmtProgram(List.of("--check", dir.toString()), dir));
        assertEquals(0, Main.fmtProgram(List.of(dir.toString()), dir));
        assertEquals(expected, Files.readString(messy, StandardCharsets.UTF_8));
        assertEquals(0, Main.fmtProgram(List.of("--check", dir.toString()), dir));
    }
}

package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProjectTest {

    @Test
    public void examplesProjectLoads() throws Exception {
        Project project = Project.load(Path.of("examples", "project.toml"));
        assertEquals("RoseGold Examples", project.name);
        assertEquals("Kalob", project.author);
        assertEquals("1.0.0", project.version);
        assertEquals("1.0.0", project.rosegold);
        assertEquals("hello.rg", project.entry);
        assertEquals("tests.rg", project.testEntry);
        assertTrue(Files.isRegularFile(project.entryFile));
        assertTrue(Files.isRegularFile(project.testTarget()));
        assertTrue(project.modules.containsKey("calc"));
        assertTrue(Files.isDirectory(project.modules.get("calc")));
        assertEquals(project.modules.get("calc"), project.modulePath("calc"));
        assertEquals(0, Main.testProgram(List.of("project.toml"), Path.of("examples")));
    }

    @Test
    public void findWalksParents() throws Exception {
        Path nested = Path.of("examples", "calc", "lib.rg").toAbsolutePath();
        Project project = Project.find(nested);
        assertNotNull(project);
        assertEquals("RoseGold Examples", project.name);
        assertEquals("hello.rg", project.entry);
    }

    @Test
    public void missingNameFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-name");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                entry = "hello.rg"
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected missing name");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("name"));
        }
    }

    @Test
    public void missingEntryFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-entry");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected missing entry");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("entry"));
        }
    }

    @Test
    public void unknownProjectKeyFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-unk");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                entry = "hello.rg"
                extra = "nope"
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected unknown key");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("extra"));
        }
    }

    @Test
    public void unknownTopLevelFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-top");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                entry = "hello.rg"
                [build]
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected unknown table");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("build"));
        }
    }

    @Test
    public void arrayOfTablesFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-arr");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                entry = "hello.rg"
                [[build]]
                type = "executable"
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected array of tables error");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("array of tables"));
        }
    }

    @Test
    public void locateMissingIsNull() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-none");
        assertNull(Project.locate(dir));
        assertNull(Project.find(dir));
    }

    @Test
    public void checkUsesProjectEntry() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-check");
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "main.rg"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, Main.checkProgram(List.of(), dir));
        assertEquals(0, Main.checkProgram(List.of("project.toml"), dir));
    }

    @Test
    public void runUsesProjectEntry() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-run");
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "main.rg"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    print("from-entry");
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, Main.runProgram(List.of(), dir));
    }

    @Test
    public void fmtUsesProjectEntry() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-fmt");
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "main.rg"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, Main.fmtProgram(List.of("--check"), dir));
        assertEquals(0, Main.fmtProgram(List.of("--check", "project.toml"), dir));
    }

    @Test
    public void scaffoldCreatesLoadableProject() throws Exception {
        Path dir = Files.createTempDirectory("rg-new");
        assertEquals(0, Main.newProgram(List.of(), dir));
        Project project = Project.load(dir.resolve("project.toml"));
        assertEquals(dir.getFileName().toString(), project.name);
        assertEquals("main.rg", project.entry);
        assertEquals("tests.rg", project.testEntry);
        assertTrue(Files.isRegularFile(project.entryFile));
        assertTrue(Files.isRegularFile(project.testTarget()));
        assertEquals(0, Main.checkProgram(List.of(), dir));
        assertEquals(0, Main.testProgram(List.of(), dir));
        assertEquals(0, Main.runProgram(List.of(), dir));
        assertEquals(1, Main.newProgram(List.of(), dir));
    }

    @Test
    public void scaffoldAddsMappedModule() throws Exception {
        Path dir = Files.createTempDirectory("rg-new-mod");
        assertEquals(0, Main.newProgram(List.of(), dir));
        assertEquals(0, Main.newProgram(List.of("module", "util"), dir));
        Project project = Project.load(dir.resolve("project.toml"));
        assertTrue(project.modules.containsKey("util"));
        assertTrue(Files.isRegularFile(dir.resolve("util").resolve("lib.rg")));
        Files.writeString(dir.resolve("main.rg"), """
                import util;
                fn main(): Int {
                    print(util.hello());
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, Main.checkProgram(List.of(), dir));
        assertEquals(0, Main.runProgram(List.of(), dir));
        assertEquals(1, Main.newProgram(List.of("module", "util"), dir));
        assertEquals(2, Main.newProgram(List.of("module", "std"), dir));
        assertEquals(2, Main.newProgram(List.of("module"), dir));
    }

    @Test
    public void scaffoldKeepsExistingMain() throws Exception {
        Path dir = Files.createTempDirectory("rg-new-keep");
        String existing = "fn main(): Int { return 7; }\n";
        Files.writeString(dir.resolve("main.rg"), existing, StandardCharsets.UTF_8);
        Scaffold.Result result = Scaffold.create(dir);
        assertTrue(result.message, result.ok);
        assertEquals(existing, Files.readString(dir.resolve("main.rg"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(dir.resolve("project.toml")));
    }

    @Test
    public void mappedModuleImport() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-mod");
        Path lib = dir.resolve("hidden");
        Files.createDirectories(lib);
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "app.rg"
                [modules]
                secret = "hidden"
                """, StandardCharsets.UTF_8);
        Files.writeString(lib.resolve("lib.rg"), """
                fn n(): Int {
                    return 9;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("app.rg"), """
                import secret;
                fn main(): Int {
                    print(secret.n());
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Project project = Project.load(dir.resolve("project.toml"));
        assertEquals(lib.toAbsolutePath().normalize(), project.modulePath("secret"));
        Run.Result result = Run.runFile(dir.resolve("app.rg"), List.of(dir.resolve("app.rg").toString()));
        assertTrue(result.message, result.ok);
        assertEquals("9\n", result.out);
        assertEquals(0, result.exitCode);
    }

    @Test
    public void missingModulePathIsDiagnostic() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-mod-miss");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                entry = "main.rg"
                [modules]
                util = "nope"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Project project = Project.load(file);
        List<Diagnostic> diags = project.missingPathDiagnostics();
        assertTrue(diags.toString(), diags.stream().anyMatch(d ->
                d.message.contains("util") && d.message.contains("nope") && d.message.contains("not found")));
        assertTrue(diags.toString(), diags.stream().anyMatch(d -> d.line >= 5));
    }

    @Test
    public void existingModuleHasNoPathDiagnostic() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-mod-ok");
        assertEquals(0, Main.newProgram(List.of(), dir));
        assertEquals(0, Main.newProgram(List.of("module", "util"), dir));
        Project project = Project.load(dir.resolve("project.toml"));
        List<Diagnostic> diags = project.missingPathDiagnostics();
        assertTrue(diags.toString(), diags.stream().noneMatch(d -> d.message.contains("module")));
        List<Path> sources = project.moduleSourceFiles();
        assertTrue(sources.toString(), sources.stream().anyMatch(p ->
                p.toString().replace('\\', '/').contains("util/lib.rg")));
    }

    @Test
    public void gotoJumpsFromTomlPaths() throws Exception {
        Path toml = Path.of("examples", "project.toml").toAbsolutePath();
        String source = Files.readString(toml, StandardCharsets.UTF_8);
        Project project = Project.parse(toml, source);
        int entryAt = source.indexOf("hello.rg");
        assertTrue(entryAt >= 0);
        Path entry = project.targetAt(source, entryAt);
        assertNotNull(entry);
        assertTrue(entry.toString(), Files.isSameFile(entry, project.entryFile));

        int testAt = source.indexOf("tests.rg");
        assertTrue(testAt >= 0);
        Path tests = project.targetAt(source, testAt);
        assertNotNull(tests);
        assertTrue(tests.toString(), Files.isSameFile(tests, project.testTarget()));

        int modAt = source.indexOf("\"calc\"");
        assertTrue(modAt >= 0);
        Path calc = project.targetAt(source, modAt + 1);
        assertNotNull(calc);
        assertTrue(calc.toString(), calc.toString().replace('\\', '/').endsWith("calc/lib.rg"));

        assertNull(project.targetAt(source, source.indexOf("name")));
    }

    @Test
    public void gotoMissingModulePathIsNull() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-goto-miss");
        Path file = dir.resolve("project.toml");
        String source = """
                [project]
                name = "app"
                entry = "main.rg"
                [modules]
                util = "nope"
                """;
        Files.writeString(file, source, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), "fn main(): Int { return 0; }\n", StandardCharsets.UTF_8);
        Project project = Project.parse(file, source);
        int at = source.indexOf("nope");
        assertTrue(at >= 0);
        assertNull(project.targetAt(source, at));
        Path main = project.targetAt(source, source.indexOf("main.rg"));
        assertNotNull(main);
        assertTrue(Files.isSameFile(main, dir.resolve("main.rg")));
    }

    @Test
    public void isProjectFile() {
        assertTrue(Project.isProjectFile(Path.of("examples", "project.toml")));
        assertTrue(Project.isProjectFile(Path.of("project.toml")));
        assertFalse(Project.isProjectFile(Path.of("examples", "hello.rg")));
    }

    @Test
    public void parseUsesSourceBuffer() {
        Path file = Path.of("missing", "project.toml");
        Project project = Project.parse(file, """
                [project]
                name = "buf"
                entry = "app.rg"
                """);
        assertEquals("buf", project.name);
        assertEquals("app.rg", project.entry);
    }

    @Test
    public void unknownProfileTestKeyFails() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-ptest");
        Path file = dir.resolve("project.toml");
        Files.writeString(file, """
                [project]
                name = "app"
                entry = "hello.rg"
                [profile.test]
                extra = "nope"
                """, StandardCharsets.UTF_8);
        try {
            Project.load(file);
            fail("expected unknown profile.test key");
        } catch (LangException ex) {
            assertTrue(ex.diagnostic.message, ex.diagnostic.message.contains("extra"));
        }
    }

    @Test
    public void testUsesProfileEntry() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-test");
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "main.rg"
                [profile.test]
                entry = "unit.rg"
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("unit.rg"), """
                @test
                fn add() {
                    assert(1 + 1 == 2);
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, Main.testProgram(List.of(), dir));
        assertEquals(0, Main.testProgram(List.of("project.toml"), dir));
        Run.Result result = Run.testProject(Project.load(dir.resolve("project.toml")));
        assertTrue(result.message, result.ok);
        assertTrue(result.out, result.out.contains("ok add"));
    }

    @Test
    public void testDiscoversTestsRg() throws Exception {
        Path dir = Files.createTempDirectory("rg-proj-disc");
        Files.writeString(dir.resolve("project.toml"), """
                [project]
                name = "app"
                entry = "main.rg"
                [profile.test]
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("tests.rg"), """
                @test
                fn found() {
                    assert(true);
                }
                """, StandardCharsets.UTF_8);
        Project project = Project.load(dir.resolve("project.toml"));
        assertEquals("", project.testEntry);
        assertEquals(dir.resolve("tests.rg").toAbsolutePath().normalize(), project.testTarget());
        assertEquals(0, Main.testProgram(List.of(), dir));
    }
}

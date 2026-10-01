package com.rosegoldc.lang;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MainTest {

    @Test
    public void helpExitsZero() throws Exception {
        assertEquals(0, Main.run(new String[] {"help"}));
    }

    @Test
    public void versionExitsZero() throws Exception {
        assertEquals(0, Main.run(new String[] {"version"}));
        assertEquals(0, Main.run(new String[] {"-v"}));
    }

    @Test
    public void quitExitsZero() throws Exception {
        assertEquals(0, Main.run(new String[] {"quit"}));
        assertTrue(Main.dispatch(Main.parseLine("exit")).quit);
    }

    @Test
    public void replQuit() throws Exception {
        assertEquals(0, Main.repl(new BufferedReader(new StringReader("help\nquit\n"))));
    }

    @Test
    public void replQuotedRun() {
        Main.Invocation inv = Main.parseLine("run \"examples/hello.rg\"");
        assertEquals("run", inv.cmd);
        assertEquals(1, inv.args.size());
        assertEquals("examples/hello.rg", inv.args.getFirst());
    }

    @Test
    public void checkHello() throws Exception {
        assertEquals(0, Main.run(new String[] {"check", "examples/hello.rg"}));
    }

    @Test
    public void runHello() throws Exception {
        assertEquals(0, Main.run(new String[] {"run", "examples/hello.rg"}));
    }

    @Test
    public void irHello() throws Exception {
        assertEquals(0, Main.run(new String[] {"ir", "examples/hello.rg"}));
    }

    @Test
    public void llvmHello() throws Exception {
        assertEquals(0, Main.run(new String[] {"llvm", "examples/hello.rg"}));
    }

    @Test
    public void llvmLinkHello() throws Exception {
        Path clang = LlvmLink.findClang();
        Path out = Path.of("build", "test-hello-native");
        int code = Main.run(new String[] {"llvm", "--link", "examples/hello.rg", "-o", out.toString()});
        if (clang == null) {
            assertEquals(1, code);
            return;
        }
        assertEquals(0, code);
        Path exe = LlvmLink.withExeSuffix(out.toAbsolutePath());
        assertTrue(exe.toString(), Files.isRegularFile(exe));
        Process proc = new ProcessBuilder(exe.toString()).redirectErrorStream(true).start();
        String stdout = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor());
        assertTrue(stdout, stdout.contains("hello from RoseGold"));
    }

    @Test
    public void llvmRunHello() throws Exception {
        Path clang = LlvmLink.findClang();
        Path out = Path.of("build", "test-hello-run");
        int code = Main.run(new String[] {"llvm", "--run", "examples/hello.rg", "-o", out.toString()});
        if (clang == null) {
            assertEquals(1, code);
            return;
        }
        assertEquals(0, code);
        assertTrue(LlvmLink.withExeSuffix(out.toAbsolutePath()).toString(),
                Files.isRegularFile(LlvmLink.withExeSuffix(out.toAbsolutePath())));
    }

    @Test
    public void unknownCommand() throws Exception {
        int code = Main.run(new String[] {"lsp"});
        assertTrue("expected usage exit, got " + code, code != 0);
    }
}

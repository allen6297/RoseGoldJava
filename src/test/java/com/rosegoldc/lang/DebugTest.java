package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DebugTest {

    @Test
    public void hitCountParsing() {
        Interp.DebugBreakpoint bp = new Interp.DebugBreakpoint();
        assertTrue(Interp.parseDebugHit("", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Always, bp.hitOp);
        assertTrue(Interp.parseDebugHit("  3  ", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Eq, bp.hitOp);
        assertEquals(3, bp.hitN);
        assertTrue(Interp.parseDebugHit(">=2", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Ge, bp.hitOp);
        assertEquals(2, bp.hitN);
        assertTrue(Interp.parseDebugHit("> 2", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Gt, bp.hitOp);
        assertTrue(Interp.parseDebugHit("<=1", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Le, bp.hitOp);
        assertTrue(Interp.parseDebugHit("% 2", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Mod, bp.hitOp);
        assertEquals(2, bp.hitN);
        assertFalse(Interp.parseDebugHit("bogus", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Never, bp.hitOp);
        assertFalse(Interp.parseDebugHit("%0", bp));
        assertEquals(Interp.DebugBreakpoint.HitOp.Never, bp.hitOp);

        bp.hitOp = Interp.DebugBreakpoint.HitOp.Eq;
        bp.hitN = 3;
        bp.hits = 3;
        assertTrue(Interp.debugHitMatches(bp));
        bp.hits = 2;
        assertFalse(Interp.debugHitMatches(bp));
        bp.hitOp = Interp.DebugBreakpoint.HitOp.Mod;
        bp.hitN = 2;
        bp.hits = 4;
        assertTrue(Interp.debugHitMatches(bp));
        bp.hits = 3;
        assertFalse(Interp.debugHitMatches(bp));
    }

    @Test
    public void hitCountEqStopsOnce() {
        String src = """
                fn main(): Int {
                    var i = 0;
                    while i < 5 {
                        i += 1;
                    }
                    return i;
                }
                """;
        int assignLine = assignLine(src, "dap-hit.rg");
        Interp.DebugBreakpoint hitEq = new Interp.DebugBreakpoint();
        hitEq.line = assignLine;
        assertTrue(Interp.parseDebugHit("3", hitEq));
        AtomicInteger stops = new AtomicInteger();
        Value ret = runPaused(src, "dap-hit-eq.rg", hitEq, stops, null);
        assertEquals(Value.Kind.Int, ret.kind);
        assertEquals(5, ret.i);
        assertEquals(1, stops.get());
    }

    @Test
    public void hitCountGeStopsThreeTimes() {
        String src = """
                fn main(): Int {
                    var i = 0;
                    while i < 5 {
                        i += 1;
                    }
                    return i;
                }
                """;
        int assignLine = assignLine(src, "dap-hit.rg");
        Interp.DebugBreakpoint hitGe = new Interp.DebugBreakpoint();
        hitGe.line = assignLine;
        assertTrue(Interp.parseDebugHit(">=3", hitGe));
        AtomicInteger stops = new AtomicInteger();
        Value ret = runPaused(src, "dap-hit-ge.rg", hitGe, stops, null);
        assertEquals(Value.Kind.Int, ret.kind);
        assertEquals(5, ret.i);
        assertEquals(3, stops.get());
    }

    @Test
    public void invalidHitCountNeverStops() {
        String src = """
                fn main(): Int {
                    var i = 0;
                    while i < 5 {
                        i += 1;
                    }
                    return i;
                }
                """;
        int assignLine = assignLine(src, "dap-hit.rg");
        Interp.DebugBreakpoint hitBad = new Interp.DebugBreakpoint();
        hitBad.line = assignLine;
        assertFalse(Interp.parseDebugHit("nope", hitBad));
        AtomicInteger stops = new AtomicInteger();
        runPaused(src, "dap-hit-bad.rg", hitBad, stops, null);
        assertEquals(0, stops.get());
    }

    @Test
    public void setVariableOnPause() {
        String src = """
                fn main(): Int {
                    var x = 1;
                    const n = 2;
                    return x;
                }
                """;
        int returnLine = returnLine(src, "dap-set.rg");
        Interp.DebugBreakpoint setBp = new Interp.DebugBreakpoint();
        setBp.line = returnLine;
        AtomicInteger stops = new AtomicInteger();
        boolean[] flags = {false, false, false};
        Value ret = runPaused(src, "dap-set.rg", setBp, stops, interp -> {
            if (interp.debug.stack.isEmpty()) {
                return;
            }
            int envIndex = interp.debug.stack.getLast().envIndex;
            String[] err = {""};
            flags[0] = interp.debugSetVariable(envIndex, "x", "40 + 1", err) && err[0].isEmpty();
            String[] cerr = {""};
            flags[1] = !interp.debugSetVariable(envIndex, "n", "9", cerr) && cerr[0].contains("const");
            String[] merr = {""};
            flags[2] = !interp.debugSetVariable(envIndex, "nope", "1", merr);
        });
        assertEquals(Value.Kind.Int, ret.kind);
        assertEquals(41, ret.i);
        assertEquals(1, stops.get());
        assertTrue(flags[0]);
        assertTrue(flags[1]);
        assertTrue(flags[2]);
    }

    @Test
    public void languageSuiteFindsExampleTests() {
        Run.Result result = Run.testLanguage(Path.of(""));
        assertTrue(result.out, result.ok);
        assertTrue(result.out, result.out.contains("ok add"));
        assertTrue(result.out, result.out.contains("tests passed"));
    }

    @Test
    public void fileSuitePassAndFail() throws Exception {
        Path root = Files.createTempDirectory("rg-suite");
        Path pass = root.resolve("pass");
        Path fail = root.resolve("fail");
        Files.createDirectories(pass);
        Files.createDirectories(fail);
        Files.writeString(pass.resolve("ok.rg"), """
                fn main(): Int {
                    print("ok");
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(fail.resolve("boom.rg"), """
                # expect: unknown function
                fn main(): Int {
                    nope();
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Run.Result result = Run.testSuite(root);
        assertTrue(result.out, result.ok);
        assertTrue(result.out, result.out.contains("ok   "));
        assertTrue(result.out, result.out.contains("2/2 file tests passed"));
    }

    @Test
    public void fileSuiteNativePassAndFail() throws Exception {
        if (LlvmLink.findClang() == null) {
            return;
        }
        Path root = Files.createTempDirectory("rg-native-suite");
        Path pass = root.resolve("pass");
        Path fail = root.resolve("fail");
        Files.createDirectories(pass);
        Files.createDirectories(fail);
        Files.writeString(pass.resolve("ok.rg"), """
                fn main(): Int {
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(fail.resolve("boom.rg"), """
                # expect: unknown function
                fn main(): Int {
                    nope();
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Run.Result result = Run.testSuite(root, null, true);
        assertTrue(result.out, result.ok);
        assertTrue(result.out, result.out.contains("ok   "));
        assertTrue(result.out, result.out.contains("2/2 file tests passed"));
    }

    private static int assignLine(String src, String path) {
        List<Diagnostic> diags = new java.util.ArrayList<>();
        Program program = Parser.parseSource(src, path, diags);
        assertTrue(diags.isEmpty());
        int line = 4;
        for (Stmt st : program.fns.getFirst().body) {
            if (st.kind == Stmt.Kind.While) {
                for (Stmt inner : st.body) {
                    if (inner.kind == Stmt.Kind.Assign) {
                        line = inner.line;
                    }
                }
            }
        }
        return line;
    }

    private static int returnLine(String src, String path) {
        List<Diagnostic> diags = new java.util.ArrayList<>();
        Program program = Parser.parseSource(src, path, diags);
        assertTrue(diags.isEmpty());
        int line = 4;
        for (Stmt st : program.fns.getFirst().body) {
            if (st.kind == Stmt.Kind.Return) {
                line = st.line;
            }
        }
        return line;
    }

    private static Value runPaused(
            String src,
            String path,
            Interp.DebugBreakpoint bpIn,
            AtomicInteger stops,
            java.util.function.Consumer<Interp> onPause
    ) {
        List<Diagnostic> diags = new java.util.ArrayList<>();
        Program program = Parser.parseSource(src, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Checker checker = Checker.check(program, path, diags);
        assertTrue(diags.toString(), diags.isEmpty());
        Interp interp = new Interp(checker, List.of(path));
        interp.debug.enabled = true;
        interp.debug.stopOnEntry = false;
        String norm = Interp.debugNormPath(path);
        interp.debug.breakpoints.put(norm, new java.util.ArrayList<>(List.of(bpIn)));
        interp.debug.pauseAndWait = reason -> {
            if (!"breakpoint".equals(reason)) {
                return;
            }
            stops.incrementAndGet();
            if (onPause != null) {
                onPause.accept(interp);
            }
            interp.debug.mode = Interp.DebugState.Mode.Run;
        };
        return interp.callNamed("main");
    }
}

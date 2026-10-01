package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class NativeRun {

    public static final class Result {
        public final boolean ok;
        public final int exitCode;
        public final String message;
        public final String out;
        public final Path exe;
        public final List<String> command;

        Result(boolean ok, int exitCode, String message, Path exe, List<String> command) {
            this(ok, exitCode, message, "", exe, command);
        }

        Result(boolean ok, int exitCode, String message, String out, Path exe, List<String> command) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.message = message == null ? "" : message;
            this.out = out == null ? "" : out;
            this.exe = exe;
            this.command = command == null ? List.of() : List.copyOf(command);
        }

        boolean notLowered() {
            return message.contains("not lowered");
        }
    }

    private NativeRun() {
    }

    public static Result linkFile(Path file, Path workDir) throws IOException, InterruptedException {
        return linkFile(file, workDir, false);
    }

    public static Result linkFile(Path file, Path workDir, boolean test) throws IOException, InterruptedException {
        if (file == null || !Files.isRegularFile(file)) {
            return new Result(false, 1, "RoseGold file not found", null, null);
        }
        file = file.toAbsolutePath().normalize();
        Path cwd = workDir != null ? workDir.toAbsolutePath().normalize() : file.getParent();
        if (cwd == null) {
            cwd = Path.of("").toAbsolutePath();
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<Diagnostic> diags = new ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, file.toString(), diags);
        } catch (LangException ex) {
            return new Result(false, 1, ex.diagnostic.toHuman(), null, null);
        }
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return new Result(false, 1, d.toHuman(), null, null);
        }
        if (test) {
            boolean anyTest = false;
            for (FnDecl fn : program.fns) {
                if (fn.isTest) {
                    anyTest = true;
                    break;
                }
            }
            if (!anyTest) {
                return new Result(true, 0, "no @test functions", null, null);
            }
        }
        Checker checker = Checker.check(program, file.toString(), diags);
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return new Result(false, 1, d.toHuman(), null, null);
        }
        Path nativeDir = LlvmLink.resolveNativeDir(cwd);
        String stem = file.getFileName() == null ? "a" : file.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        Path tmp = LlvmLink.runCacheDir();
        String prefix = test ? "test-" : "";
        LlvmLink.Result linked = LlvmLink.link(checker, nativeDir, tmp.resolve(prefix + stem + "-" + System.nanoTime()),
                test);
        if (!linked.ok || linked.output == null) {
            return new Result(linked.ok, linked.exitCode, linked.message, linked.output, linked.command);
        }
        Path exe = LlvmLink.stageExe(linked.output);
        return new Result(true, linked.exitCode, linked.message, exe, linked.command);
    }

    public static Result execFile(Path file, List<String> argv, Path workDir, boolean test)
            throws IOException, InterruptedException {
        Result linked = linkFile(file, workDir, test);
        if (!linked.ok || linked.exe == null) {
            return linked;
        }
        List<String> args = argv == null ? List.of() : argv;
        LlvmLink.Result ran = LlvmLink.exec(
                linked.exe,
                args,
                new LlvmLink.Result(true, linked.exitCode, linked.message, linked.exe, null, linked.command),
                workDir,
                java.util.Map.of("RG_UI_HEADLESS", "1"));
        String out = ran.stdout == null ? "" : ran.stdout;
        String message = ran.ok ? linked.message : (out.isEmpty() ? ran.message : out);
        return new Result(ran.ok, ran.exitCode, message, out, ran.output, ran.command == null ? linked.command : ran.command);
    }
}

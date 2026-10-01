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
        public final Path exe;
        public final List<String> command;

        Result(boolean ok, int exitCode, String message, Path exe, List<String> command) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.message = message == null ? "" : message;
            this.exe = exe;
            this.command = command == null ? List.of() : List.copyOf(command);
        }
    }

    private NativeRun() {
    }

    public static Result linkFile(Path file, Path workDir) throws IOException, InterruptedException {
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
        LlvmLink.Result linked = LlvmLink.link(checker, nativeDir, tmp.resolve(stem + "-" + System.nanoTime()), false);
        if (!linked.ok || linked.output == null) {
            return new Result(linked.ok, linked.exitCode, linked.message, linked.output, linked.command);
        }
        Path exe = LlvmLink.stageExe(linked.output);
        return new Result(true, linked.exitCode, linked.message, exe, linked.command);
    }
}

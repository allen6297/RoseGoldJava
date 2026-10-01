package com.rosegoldc.lang;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class LlvmLink {

    static final class Result {
        final boolean ok;
        final int exitCode;
        final String message;
        final Path output;
        final Path clang;
        final List<String> command;
        final String stdout;

        Result(boolean ok, int exitCode, String message, Path output, Path clang, List<String> command) {
            this(ok, exitCode, message, output, clang, command, "");
        }

        Result(boolean ok, int exitCode, String message, Path output, Path clang, List<String> command, String stdout) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.message = message == null ? "" : message;
            this.output = output;
            this.clang = clang;
            this.command = command == null ? List.of() : List.copyOf(command);
            this.stdout = stdout == null ? "" : stdout;
        }
    }

    private LlvmLink() {
    }

    static boolean fullyLowered(String ir) {
        return ir != null && !ir.contains("{ not lowered to llvm }") && ir.contains("define i32 @main(");
    }

    static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    static Path withExeSuffix(Path out) {
        if (out == null) {
            return null;
        }
        String name = out.getFileName() == null ? "" : out.getFileName().toString();
        if (isWindows() && !name.toLowerCase().endsWith(".exe")) {
            return out.resolveSibling(name + ".exe");
        }
        return out;
    }

    static String clangSkipReason;

    static Path findClang() {
        clangSkipReason = null;
        Path mismatch = null;
        String mismatchWhy = null;
        for (Path cand : clangCandidates()) {
            String why = clangUnusable(cand);
            if (why == null) {
                return cand;
            }
            if (mismatch == null) {
                mismatch = cand;
                mismatchWhy = why;
            }
        }
        if (mismatch != null) {
            clangSkipReason = mismatch + " is " + mismatchWhy;
        }
        return null;
    }

    static List<Path> clangCandidates() {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("CLANG");
        if (env != null && !env.isBlank()) {
            Path p = Path.of(env);
            if (Files.isRegularFile(p)) {
                out.add(p.toAbsolutePath().normalize());
            }
        }
        List<String> names = isWindows() ? List.of("clang.exe", "clang") : List.of("clang");
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path folder = Path.of(dir);
                for (String name : names) {
                    Path cand = folder.resolve(name);
                    if (Files.isRegularFile(cand)) {
                        out.add(cand.toAbsolutePath().normalize());
                    }
                }
            }
        }
        if (isWindows()) {
            for (String extra : List.of(
                    "C:\\Program Files\\LLVM\\bin\\clang.exe",
                    "C:\\Program Files (x86)\\LLVM\\bin\\clang.exe")) {
                Path cand = Path.of(extra);
                if (Files.isRegularFile(cand)) {
                    out.add(cand.toAbsolutePath().normalize());
                }
            }
        }
        return out;
    }

    static String clangUnusable(Path clang) {
        if (clang == null || !Files.isRegularFile(clang)) {
            return "missing";
        }
        if (!isWindows()) {
            return null;
        }
        int exe = peMachine(clang);
        int os = osMachine();
        if (exe != 0 && os != 0 && exe != os) {
            return peName(exe) + " on " + peName(os) + " Windows; install the matching LLVM build";
        }
        return null;
    }

    static int osMachine() {
        String arch = System.getProperty("os.arch");
        if (arch == null) {
            return 0;
        }
        String a = arch.toLowerCase();
        if (a.equals("amd64") || a.equals("x86_64")) {
            return 0x8664;
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return 0xAA64;
        }
        if (a.equals("x86") || a.equals("i386") || a.equals("i686")) {
            return 0x14C;
        }
        return 0;
    }

    static String peName(int machine) {
        return switch (machine) {
            case 0x8664 -> "x64";
            case 0xAA64 -> "ARM64";
            case 0x14C -> "x86";
            default -> "unknown";
        };
    }

    static int peMachine(Path exe) {
        try (java.io.InputStream in = Files.newInputStream(exe)) {
            byte[] buf = in.readNBytes(4096);
            if (buf.length < 64 || buf[0] != 'M' || buf[1] != 'Z') {
                return 0;
            }
            int pe = (buf[0x3C] & 0xff) | ((buf[0x3D] & 0xff) << 8)
                    | ((buf[0x3E] & 0xff) << 16) | ((buf[0x3F] & 0xff) << 24);
            if (pe < 0 || pe + 6 > buf.length) {
                return 0;
            }
            if (buf[pe] != 'P' || buf[pe + 1] != 'E' || buf[pe + 2] != 0 || buf[pe + 3] != 0) {
                return 0;
            }
            return (buf[pe + 4] & 0xff) | ((buf[pe + 5] & 0xff) << 8);
        } catch (IOException ex) {
            return 0;
        }
    }

    static Path resolveNativeDir(Path cwd) {
        Path root = cwd == null ? Path.of("").toAbsolutePath() : cwd.toAbsolutePath();
        Path local = root.resolve("native");
        if (isNativeDir(local)) {
            return local.normalize();
        }
        return bundledNative();
    }

    static boolean isNativeDir(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("runtime.c"))
                && Files.isRegularFile(dir.resolve("rg_value.h"));
    }

    static List<String> clangCommand(Path clang, Path irFile, Path runtimeC, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(clang.toString());
        cmd.add("-std=c11");
        cmd.add("-O0");
        cmd.add("-Wno-override-module");
        cmd.add("-o");
        cmd.add(output.toString());
        cmd.add(irFile.toString());
        cmd.add(runtimeC.toString());
        if (!isWindows()) {
            cmd.add("-lm");
        }
        return cmd;
    }

    static Result link(Checker checker, Path nativeDir, Path output) throws IOException, InterruptedException {
        return link(checker, nativeDir, output, false);
    }

    static Result link(Checker checker, Path nativeDir, Path output, boolean test)
            throws IOException, InterruptedException {
        if (checker == null) {
            return new Result(false, 1, "no program", null, null, null);
        }
        String ir = Llvm.emit(checker, test);
        if (!fullyLowered(ir)) {
            return new Result(false, 1, "cannot link: some functions were not lowered to llvm", null, null, null);
        }
        Path clang = findClang();
        if (clang == null) {
            if (clangSkipReason != null) {
                return new Result(false, 1, "llvm --link cannot run clang: " + clangSkipReason, null, null, null);
            }
            return new Result(false, 1, "llvm --link needs clang on PATH (LLVM 15+)", null, null, null);
        }
        if (!isNativeDir(nativeDir)) {
            return new Result(false, 1, "native/runtime.c not found", null, clang, null);
        }
        Path out = withExeSuffix(output.toAbsolutePath().normalize());
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempDirectory("rg-llvm-");
        Path ll = tmp.resolve("program.ll");
        Files.writeString(ll, ir, StandardCharsets.UTF_8);
        Path runtimeC = nativeDir.resolve("runtime.c").toAbsolutePath().normalize();
        List<String> cmd = clangCommand(clang, ll.toAbsolutePath().normalize(), runtimeC, out);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(nativeDir.toFile());
        Process proc = pb.start();
        String log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return new Result(false, 1, "clang timed out\n" + log, out, clang, cmd);
        }
        int code = proc.exitValue();
        if (code != 0) {
            return new Result(false, 1, log.isEmpty() ? "clang failed" : log, out, clang, cmd);
        }
        if (!Files.isRegularFile(out)) {
            return new Result(false, 1, "clang produced no output", out, clang, cmd);
        }
        return new Result(true, 0, log, out, clang, cmd);
    }

    static Result run(Checker checker, Path nativeDir, Path output) throws IOException, InterruptedException {
        Result linked = link(checker, nativeDir, output);
        if (!linked.ok) {
            return linked;
        }
        return exec(linked.output, List.of(), linked);
    }

    static Result exec(Path exe, List<String> argv, Result linked) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        if (argv != null) {
            cmd.addAll(argv);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return new Result(false, 1, "native program timed out\n" + out, exe, linked.clang, linked.command, out);
        }
        int code = proc.exitValue();
        return new Result(code == 0, code, out, exe, linked.clang, linked.command, out);
    }

    private static Path bundledNative() {
        URL marker = LlvmLink.class.getResource("/native/runtime.c");
        if (marker == null) {
            return null;
        }
        try {
            if ("file".equals(marker.getProtocol())) {
                Path file = Path.of(marker.toURI());
                Path dir = file.getParent();
                if (isNativeDir(dir)) {
                    return dir;
                }
            }
            if ("jar".equals(marker.getProtocol())) {
                String spec = marker.toString();
                int bang = spec.indexOf("!/");
                if (bang < 0) {
                    return null;
                }
                Path jar = Path.of(new URI(spec.substring(4, bang)));
                Path dest = Path.of(System.getProperty("java.io.tmpdir"), "rosegold-native-" + Main.VERSION);
                Stdlib.unpackJarCached(jar, "native/", dest, "runtime.c");
                if (isNativeDir(dest)) {
                    return dest;
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }
}

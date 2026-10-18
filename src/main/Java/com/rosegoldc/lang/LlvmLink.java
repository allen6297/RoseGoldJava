package com.rosegoldc.lang;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    private static Boolean clangCanRun;

    static boolean canRunClang() {
        if (clangCanRun != null) {
            return clangCanRun;
        }
        Path clang = findClang();
        if (clang == null) {
            clangCanRun = false;
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(clang.toString(), "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            clangCanRun = proc.waitFor(15, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception ex) {
            clangCanRun = false;
        }
        return clangCanRun;
    }

    static Path findLldbDap() {
        Path clang = findClang();
        if (clang != null) {
            Path hit = lldbDapIn(clang.getParent());
            if (hit != null) {
                return hit;
            }
        }
        List<String> names = isWindows()
                ? List.of("lldb-dap.exe", "lldb-vscode.exe")
                : List.of("lldb-dap", "lldb-vscode");
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
                        return cand.toAbsolutePath().normalize();
                    }
                }
            }
        }
        if (isWindows()) {
            for (String extra : List.of(
                    "C:\\Program Files\\LLVM\\bin",
                    "C:\\Program Files (x86)\\LLVM\\bin")) {
                Path hit = lldbDapIn(Path.of(extra));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Path lldbDapIn(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        List<String> names = isWindows()
                ? List.of("lldb-dap.exe", "lldb-vscode.exe")
                : List.of("lldb-dap", "lldb-vscode");
        for (String name : names) {
            Path cand = dir.resolve(name);
            if (Files.isRegularFile(cand)) {
                return cand.toAbsolutePath().normalize();
            }
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
        Path start = cwd == null ? Path.of("").toAbsolutePath() : cwd.toAbsolutePath().normalize();
        Path p = start;
        while (true) {
            Path local = p.resolve("native");
            if (isNativeDir(local)) {
                return local.normalize();
            }
            Path parent = p.getParent();
            if (parent == null || parent.equals(p)) {
                break;
            }
            p = parent;
        }
        return bundledNative();
    }

    static boolean isNativeDir(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("runtime.c"))
                && Files.isRegularFile(dir.resolve("host_ui.c"))
                && Files.isRegularFile(dir.resolve("host_img.c"))
                && Files.isRegularFile(dir.resolve("rg_value.h"));
    }

    static List<String> clangCommand(Path clang, Path irFile, Path runtimeC, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(clang.toString());
            cmd.add("-std=c11");
            cmd.add("-O0");
            cmd.add("-g");
            cmd.add("-Wno-override-module");
        cmd.add("-I");
        cmd.add(runtimeC.getParent() == null ? "." : runtimeC.getParent().toString());
        cmd.add("-o");
        cmd.add(output.toString());
        cmd.add(irFile.toString());
        cmd.add(runtimeC.toString());
        if (isWindows()) {
            cmd.add("-luser32");
            cmd.add("-lgdi32");
            cmd.add("-lole32");
            cmd.add("-luuid");
            cmd.add("-lwindowscodecs");
        } else {
            cmd.add("-lm");
        }
        return cmd;
    }

    static Path withSharedSuffix(Path out) {
        if (out == null) {
            return null;
        }
        String name = out.getFileName() == null ? "" : out.getFileName().toString();
        String lower = name.toLowerCase();
        if (isWindows()) {
            if (!lower.endsWith(".dll")) {
                return out.resolveSibling(name + ".dll");
            }
            return out;
        }
        String os = System.getProperty("os.name");
        boolean mac = os != null && os.toLowerCase().contains("mac");
        String suffix = mac ? ".dylib" : ".so";
        if (!lower.endsWith(suffix)) {
            return out.resolveSibling(name + suffix);
        }
        return out;
    }

    static boolean godotLowered(String ir) {
        return ir != null && !ir.contains("{ not lowered to llvm }") && ir.contains("define void @rg_godot_new(");
    }

    static List<String> clangSharedCommand(Path clang, Path irFile, Path runtimeC, Path hostGodot, Path output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(clang.toString());
        cmd.add("-std=c11");
        cmd.add("-O0");
        cmd.add("-g");
        cmd.add("-shared");
        cmd.add("-Wno-override-module");
        if (!isWindows()) {
            cmd.add("-fPIC");
        }
        cmd.add("-I");
        cmd.add(runtimeC.getParent() == null ? "." : runtimeC.getParent().toString());
        cmd.add("-o");
        cmd.add(output.toString());
        cmd.add(irFile.toString());
        cmd.add(runtimeC.toString());
        cmd.add(hostGodot.toString());
        if (isWindows()) {
            cmd.add("-luser32");
            cmd.add("-lgdi32");
            cmd.add("-lole32");
            cmd.add("-luuid");
            cmd.add("-lwindowscodecs");
        } else {
            cmd.add("-lm");
        }
        return cmd;
    }

    static Result linkGodot(Checker checker, Path nativeDir, Path output) throws IOException, InterruptedException {
        if (checker == null) {
            return new Result(false, 1, "no program", null, null, null);
        }
        GodotScript.Model model = GodotScript.model(checker);
        if (model == null) {
            return new Result(false, 1, "no class extends Node, Node2D, Node3D, or Control", null, null, null);
        }
        String ir = Llvm.emit(checker, false, true);
        if (!godotLowered(ir)) {
            return new Result(false, 1, "cannot link: some functions were not lowered to llvm", null, null, null);
        }
        for (GodotScript.Member method : model.methods) {
            String sym = Llvm.mangleMethod(model.className, method.name);
            if (!ir.contains("define void @" + sym + "(")) {
                return new Result(false, 1,
                        "cannot link: " + model.className + "." + method.name + " was not lowered to llvm",
                        null, null, null);
            }
        }
        Path clang = findClang();
        if (clang == null) {
            if (clangSkipReason != null) {
                return new Result(false, 1, "llvm --godot cannot run clang: " + clangSkipReason, null, null, null);
            }
            return new Result(false, 1, "llvm --godot needs clang on PATH (LLVM 15+)", null, null, null);
        }
        if (!isNativeDir(nativeDir)) {
            return new Result(false, 1, "native/runtime.c not found", null, clang, null);
        }
        Path host = nativeDir.resolve("host_godot.c").toAbsolutePath().normalize();
        if (!Files.isRegularFile(host)) {
            return new Result(false, 1, "native/host_godot.c not found", null, clang, null);
        }
        String stamp = linkStamp(ir, nativeDir, clang, false, true);
        Path cached = withSharedSuffix(runCacheDir().resolve("g" + stamp));
        Path out = output == null ? cached : withSharedSuffix(output.toAbsolutePath().normalize());
        Path manifest = manifestBeside(out);
        if (!(Files.isRegularFile(cached) && Files.size(cached) > 0)) {
            Path parent = cached.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempDirectory("rg-llvm-");
            Path ll = tmp.resolve("program.ll");
            Files.writeString(ll, ir, StandardCharsets.UTF_8);
            Path runtimeC = nativeDir.resolve("runtime.c").toAbsolutePath().normalize();
            List<String> cmd = clangSharedCommand(clang, ll.toAbsolutePath().normalize(), runtimeC, host, cached);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.directory(runCacheDir().toFile());
                Process proc;
                try {
                    proc = pb.start();
                } catch (IOException ex) {
                    Files.deleteIfExists(cached);
                    return new Result(false, 1, ex.getMessage() == null ? "clang failed" : ex.getMessage(), out, clang, cmd);
                }
                String log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    Files.deleteIfExists(cached);
                    return new Result(false, 1, "clang timed out\n" + log, out, clang, cmd);
                }
                int code = proc.exitValue();
                if (code != 0) {
                    Files.deleteIfExists(cached);
                    return new Result(false, 1, log.isEmpty() ? "clang failed" : log, out, clang, cmd);
                }
                if (!Files.isRegularFile(cached)) {
                    return new Result(false, 1, "clang produced no output", out, clang, cmd);
                }
            } finally {
                try {
                    Files.deleteIfExists(ll);
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
        copyIfNeeded(cached, out);
        Files.writeString(manifest, GodotScript.manifest(model, out.toString()), StandardCharsets.UTF_8);
        return new Result(true, 0, "", out, clang, List.of("(godot)"));
    }

    static Path manifestBeside(Path library) {
        String name = library.getFileName() == null ? "script" : library.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return library.resolveSibling(name + ".json");
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
        String stamp = linkStamp(ir, nativeDir, clang, test, false);
        Path cached = withExeSuffix(runCacheDir().resolve("l" + stamp));
        Path out = output == null ? cached : withExeSuffix(output.toAbsolutePath().normalize());
        if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
            copyIfNeeded(cached, out);
            return new Result(true, 0, "", out, clang, List.of("(cached)"));
        }
        Path parent = cached.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempDirectory("rg-llvm-");
        Path ll = tmp.resolve("program.ll");
        Files.writeString(ll, ir, StandardCharsets.UTF_8);
        Path runtimeC = nativeDir.resolve("runtime.c").toAbsolutePath().normalize();
        List<String> cmd = clangCommand(clang, ll.toAbsolutePath().normalize(), runtimeC, cached);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(runCacheDir().toFile());
            Process proc;
            try {
                proc = pb.start();
            } catch (IOException ex) {
                Files.deleteIfExists(cached);
                return new Result(false, 1, ex.getMessage() == null ? "clang failed" : ex.getMessage(), out, clang, cmd);
            }
            String log = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                Files.deleteIfExists(cached);
                return new Result(false, 1, "clang timed out\n" + log, out, clang, cmd);
            }
            int code = proc.exitValue();
            if (code != 0) {
                Files.deleteIfExists(cached);
                return new Result(false, 1, log.isEmpty() ? "clang failed" : log, out, clang, cmd);
            }
            if (!Files.isRegularFile(cached)) {
                return new Result(false, 1, "clang produced no output", out, clang, cmd);
            }
            copyIfNeeded(cached, out);
            return new Result(true, 0, log, out, clang, cmd);
        } finally {
            try {
                Files.deleteIfExists(ll);
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    private static void copyIfNeeded(Path src, Path dest) throws IOException {
        if (src == null || dest == null || src.equals(dest)) {
            return;
        }
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    static String linkStamp(String ir, Path nativeDir, Path clang, boolean test) throws IOException {
        return linkStamp(ir, nativeDir, clang, test, false);
    }

    static String linkStamp(String ir, Path nativeDir, Path clang, boolean test, boolean godot) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("rg-link-1|" + Main.VERSION + "|" + (godot ? "g" : test ? "t" : "r")).getBytes(StandardCharsets.UTF_8));
            md.update((ir == null ? "" : ir).getBytes(StandardCharsets.UTF_8));
            List<String> names = godot
                    ? List.of("runtime.c", "host_ui.c", "host_img.c", "host_godot.c", "rg_value.h")
                    : List.of("runtime.c", "host_ui.c", "host_img.c", "rg_value.h");
            for (String name : names) {
                stampFile(md, nativeDir == null ? null : nativeDir.resolve(name));
            }
            stampFile(md, clang);
            return HexFormat.of().formatHex(md.digest()).substring(0, 20);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    private static void stampFile(MessageDigest md, Path file) throws IOException {
        String path = file == null ? "" : file.toAbsolutePath().normalize().toString();
        md.update(path.getBytes(StandardCharsets.UTF_8));
        if (file != null && Files.isRegularFile(file)) {
            md.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
            md.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
        }
    }

    static Result run(Checker checker, Path nativeDir, Path output) throws IOException, InterruptedException {
        Result linked = link(checker, nativeDir, output);
        if (!linked.ok) {
            return linked;
        }
        return exec(linked.output, List.of(), linked);
    }

    static Result exec(Path exe, List<String> argv, Result linked) throws IOException, InterruptedException {
        return exec(exe, argv, linked, null, null);
    }

    static Result exec(Path exe, List<String> argv, Result linked, Path workDir, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        IOException blocked = null;
        Path current = exe;
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                current = copyForWdac(exe, i);
                Thread.sleep(250L * i);
            } else {
                Thread.sleep(150L);
            }
            try {
                return start(current, argv, linked, workDir, extraEnv);
            } catch (IOException ex) {
                if (!isWdacBlock(ex)) {
                    throw ex;
                }
                blocked = ex;
            }
        }
        String where = current == null ? String.valueOf(exe) : current.toString();
        String detail = blocked == null ? "" : blocked.getMessage();
        return new Result(false, 1,
                "Windows Application Control blocked the native exe (error 4551). Allow "
                        + where
                        + " in Smart App Control / WDAC, or run llvm --link and start the exe yourself.\n"
                        + detail,
                exe, linked == null ? null : linked.clang, linked == null ? null : linked.command, "");
    }

    static Path runCacheDir() throws IOException {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = local != null && !local.isBlank()
                ? Path.of(local, "RoseGold", "run")
                : Path.of(System.getProperty("user.home"), ".rosegold", "run");
        Files.createDirectories(dir);
        return dir;
    }

    static boolean isWdacBlock(IOException ex) {
        String m = ex.getMessage();
        return m != null && (m.contains("4551") || m.contains("Application Control"));
    }

    private static Path copyForWdac(Path src, int attempt) throws IOException {
        Path dir = runCacheDir();
        String name = src.getFileName() == null ? "rg" : src.getFileName().toString();
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        Path dest = dir.resolve(name + "-" + ProcessHandle.current().pid() + "-" + attempt
                + (isWindows() ? ".exe" : ""));
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        dest.toFile().deleteOnExit();
        return dest;
    }

    static Path stageExe(Path exe) throws IOException, InterruptedException {
        Path staged = copyForWdac(exe, 0);
        Thread.sleep(250L);
        return staged;
    }

    private static Result start(Path exe, List<String> argv, Result linked, Path workDir, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        if (argv != null) {
            cmd.addAll(argv);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (workDir != null && Files.isDirectory(workDir)) {
            pb.directory(workDir.toFile());
        }
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }
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

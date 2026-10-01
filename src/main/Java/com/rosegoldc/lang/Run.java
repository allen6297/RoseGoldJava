package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class Run {

    public static final class Result {
        public boolean ok = true;
        public String out = "";
        public String message = "";
        public int exitCode = 0;
    }

    private Run() {
    }

    public static Result runFile(Path path, List<String> argv) throws IOException {
        return runFile(path, argv, null, null);
    }

    public static Result runFile(Path path, List<String> argv, Path workDir, Consumer<String> onOut) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>();
        if (argv == null || argv.isEmpty()) {
            args.add(path.toString());
        } else {
            args.addAll(argv);
        }
        return runSource(source, path.toString(), args, workDir, onOut);
    }

    public static Result runSource(String source, String path, List<String> argv) {
        return runSource(source, path, argv, null, null);
    }

    public static Result runSource(String source, String path, List<String> argv, Path workDir, Consumer<String> onOut) {
        List<Diagnostic> diags = new ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, path, diags);
        } catch (LangException ex) {
            return fail(ex.getMessage());
        }
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return fail(Diagnostic.locatedError(d.kind.isEmpty() ? "parse error" : d.kind, d.file.isEmpty() ? path : d.file, d.line, d.col, d.message));
        }
        Checker checker = Checker.check(program, path, diags);
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return fail(Diagnostic.locatedError(d.kind.isEmpty() ? "error" : d.kind, d.file.isEmpty() ? path : d.file, d.line, d.col, d.message));
        }
        try {
            Interp interp = new Interp(checker, argv == null ? List.of() : argv);
            if (workDir != null) {
                interp.sandboxRoot = workDir.toAbsolutePath().normalize();
            }
            interp.output = onOut;
            Result result = new Result();
            if (program.fns.stream().anyMatch(fn -> fn.name.equals("main"))) {
                Value ret = interp.callNamed("main");
                if (ret.kind == Value.Kind.Int) {
                    result.exitCode = (int) ret.i;
                }
            }
            result.out = interp.out;
            return result;
        } catch (RuntimeException ex) {
            return fail(ex.getMessage() == null ? "runtime error" : ex.getMessage());
        }
    }

    public static Result testFile(Path path) throws IOException {
        return testFile(path, null, null);
    }

    public static Result testFile(Path path, Path workDir, Consumer<String> onOut) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        return testSource(source, path.toString(), workDir, onOut);
    }

    public static Result testSource(String source, String path) {
        return testSource(source, path, null, null);
    }

    public static Result testSource(String source, String path, Path workDir, Consumer<String> onOut) {
        List<Diagnostic> diags = new ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, path, diags);
        } catch (LangException ex) {
            return fail(ex.getMessage());
        }
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return fail(Diagnostic.locatedError(d.kind.isEmpty() ? "parse error" : d.kind, d.file.isEmpty() ? path : d.file, d.line, d.col, d.message));
        }
        Checker checker = Checker.check(program, path, diags);
        if (!diags.isEmpty()) {
            Diagnostic d = diags.getFirst();
            return fail(Diagnostic.locatedError(d.kind.isEmpty() ? "error" : d.kind, d.file.isEmpty() ? path : d.file, d.line, d.col, d.message));
        }
        List<String> tests = new ArrayList<>();
        for (FnDecl fn : program.fns) {
            if (fn.isTest) {
                tests.add(fn.name);
            }
        }
        if (tests.isEmpty()) {
            Result result = new Result();
            result.message = "no @test functions";
            return result;
        }
        try {
            Interp interp = new Interp(checker, List.of(path));
            if (workDir != null) {
                interp.sandboxRoot = workDir.toAbsolutePath().normalize();
            }
            interp.output = onOut;
            int failed = 0;
            for (String name : tests) {
                try {
                    interp.callNamed(name);
                    interp.emit("ok " + name + "\n");
                } catch (RuntimeException ex) {
                    failed++;
                    String msg = ex.getMessage() == null ? "runtime error" : ex.getMessage();
                    interp.emit("FAIL " + name + ": " + msg + "\n");
                }
            }
            int total = tests.size();
            int passed = total - failed;
            String summary = passed + "/" + total + " tests passed";
            interp.emit(summary + "\n");
            Result result = new Result();
            result.ok = failed == 0;
            result.out = interp.out;
            result.message = summary;
            result.exitCode = failed == 0 ? 0 : 1;
            return result;
        } catch (RuntimeException ex) {
            return fail(ex.getMessage() == null ? "runtime error" : ex.getMessage());
        }
    }

    public static Result testPath(Path path, Path workDir, Consumer<String> onOut) throws IOException {
        if (path == null) {
            Path root = workDir != null ? workDir : Path.of("");
            Path toml = root.resolve("project.toml");
            if (Files.isRegularFile(toml)) {
                return testProject(Project.load(toml), onOut);
            }
            return testLanguage(root, onOut);
        }
        if (Files.isRegularFile(path) && Project.isProjectFile(path)) {
            return testProject(Project.load(path), onOut);
        }
        if (Files.isDirectory(path)) {
            Path toml = path.resolve("project.toml");
            boolean suite = Files.isDirectory(path.resolve("pass")) || Files.isDirectory(path.resolve("fail"));
            if (!suite && Files.isRegularFile(toml)) {
                return testProject(Project.load(toml), onOut);
            }
            return testSuite(path, onOut);
        }
        return testFile(path, workDir, onOut);
    }

    public static Result testProject(Project project) throws IOException {
        return testProject(project, null);
    }

    public static Result testProject(Project project, Consumer<String> onOut) throws IOException {
        if (project == null) {
            return fail("no project.toml");
        }
        Path target = project.testTarget();
        if (!Files.exists(target)) {
            if (project.testEntry != null && !project.testEntry.isEmpty()) {
                return fail("profile.test.entry '" + project.testEntry + "' not found");
            }
            return fail("no tests found (tests.rg, tests/, or @test in " + project.entry + ")");
        }
        if (Files.isDirectory(target)) {
            return testSuite(target, onOut);
        }
        return testFile(target, project.dir, onOut);
    }

    public static Result testLanguage(Path root) {
        return testLanguage(root, null);
    }

    public static Result testLanguage(Path root, Consumer<String> onOut) {
        Path base = root == null ? Path.of("") : root;
        Path unit = base.resolve("examples").resolve("tests.rg");
        Path suite = base.resolve("tests");
        boolean hasUnit = Files.isRegularFile(unit);
        boolean hasSuite = Files.isDirectory(suite);
        if (!hasUnit && !hasSuite) {
            return fail("no tests found (examples/tests.rg or tests/)");
        }
        Result result = new Result();
        result.ok = true;
        StringBuilder out = new StringBuilder();
        if (hasUnit) {
            try {
                Result unitResult = testFile(unit, base, onOut);
                out.append(unitResult.out);
                if (!unitResult.ok) {
                    result.ok = false;
                }
            } catch (IOException ex) {
                result.ok = false;
                String line = "FAIL " + unit + ": " + ex.getMessage() + "\n";
                out.append(line);
                if (onOut != null) {
                    onOut.accept(line);
                }
            }
        }
        if (hasSuite) {
            Result files = testSuite(suite, onOut);
            out.append(files.out);
            if (!files.ok) {
                result.ok = false;
            }
        }
        result.out = out.toString();
        result.exitCode = result.ok ? 0 : 1;
        if (!result.ok) {
            result.message = "tests failed";
        }
        return result;
    }

    public static Result testSuite(Path root) {
        return testSuite(root, null);
    }

    public static Result testSuite(Path root, Consumer<String> onOut) {
        if (root == null || !Files.isDirectory(root)) {
            return fail("no test directory " + root);
        }
        int failed = 0;
        int total = 0;
        StringBuilder out = new StringBuilder();
        for (Path path : listRgFiles(root.resolve("pass"))) {
            PassCheck check = checkPass(path);
            if (check.skipped) {
                continue;
            }
            total++;
            appendOut(out, onOut, check.line);
            if (!check.ok) {
                failed++;
            }
        }
        for (Path path : listRgFiles(root.resolve("fail"))) {
            PassCheck check = checkFail(path);
            if (check.skipped) {
                continue;
            }
            total++;
            appendOut(out, onOut, check.line);
            if (!check.ok) {
                failed++;
            }
        }
        if (total == 0) {
            return fail("no .rg files in " + root + "/pass or " + root + "/fail");
        }
        int passed = total - failed;
        String summary = passed + "/" + total + " file tests passed";
        appendOut(out, onOut, summary + "\n");
        Result result = new Result();
        result.ok = failed == 0;
        result.out = out.toString();
        result.message = summary;
        result.exitCode = failed == 0 ? 0 : 1;
        return result;
    }

    private static void appendOut(StringBuilder out, Consumer<String> onOut, String line) {
        out.append(line);
        if (onOut != null) {
            onOut.accept(line);
        }
    }

    private static PassCheck checkPass(Path path) {
        String name = path.toString().replace('\\', '/');
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            List<Diagnostic> parseErrs = new ArrayList<>();
            Program program = Parser.parseSource(source, name, parseErrs);
            if (parseErrs.isEmpty() && program.fns.stream().noneMatch(fn -> fn.name.equals("main"))) {
                PassCheck skip = new PassCheck();
                skip.skipped = true;
                return skip;
            }
        } catch (Exception ignored) {
        }
        PassCheck check = new PassCheck();
        try {
            Result r = runFile(path, List.of(path.toString()), Path.of("").toAbsolutePath(), null);
            if (r.ok && r.exitCode == 0) {
                check.ok = true;
                check.line = "ok   " + name + "\n";
                return check;
            }
            String got = r.message.isEmpty() ? r.out : r.message;
            if (got.isEmpty()) {
                got = "exit " + r.exitCode;
            }
            check.line = "FAIL " + name + ": expected pass, got " + got + "\n";
            return check;
        } catch (IOException ex) {
            check.line = "FAIL " + name + ": " + ex.getMessage() + "\n";
            return check;
        }
    }

    private static PassCheck checkFail(Path path) {
        String name = path.toString().replace('\\', '/');
        PassCheck check = new PassCheck();
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            check.line = "FAIL " + name + ": " + ex.getMessage() + "\n";
            return check;
        }
        String expect = readExpect(source);
        if (expect.isEmpty()) {
            check.skipped = true;
            return check;
        }
        try {
            Result r = runFile(path, List.of(path.toString()), Path.of("").toAbsolutePath(), null);
            boolean didFail = !r.ok || r.exitCode != 0;
            String got = r.message.isEmpty() ? r.out : r.message;
            if (!didFail) {
                check.line = "FAIL " + name + ": expected error, program succeeded\n";
                return check;
            }
            if (!got.contains(expect)) {
                check.line = "FAIL " + name + ": expected '" + expect + "', got " + got + "\n";
                return check;
            }
            check.ok = true;
            check.line = "ok   " + name + "\n";
            return check;
        } catch (IOException ex) {
            check.line = "FAIL " + name + ": " + ex.getMessage() + "\n";
            return check;
        }
    }

    private static String readExpect(String source) {
        for (String raw : source.split("\n", -1)) {
            String line = raw.strip();
            String[] prefixes = {"# expect:", "// expect:", "/// expect:"};
            for (String p : prefixes) {
                if (line.startsWith(p)) {
                    return line.substring(p.length()).strip();
                }
            }
        }
        return "";
    }

    private static List<Path> listRgFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.rg")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    out.add(path);
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparing(path -> path.toString().replace('\\', '/')));
        return out;
    }

    private static Result fail(String message) {
        Result result = new Result();
        result.ok = false;
        result.message = message == null ? "" : message;
        result.exitCode = 1;
        return result;
    }

    private static final class PassCheck {
        boolean ok;
        boolean skipped;
        String line = "";
    }
}

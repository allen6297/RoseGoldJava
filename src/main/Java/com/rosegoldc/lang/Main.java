package com.rosegoldc.lang;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    static final String VERSION = "1.0.0-SNAPSHOT";

    static final class Dispatch {
        boolean quit;
        int exitCode;
    }

    static final class Invocation {
        String cmd = "";
        List<String> args = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0) {
            return repl(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        }
        return dispatch(parseArgv(args)).exitCode;
    }

    static int repl(BufferedReader in) throws Exception {
        System.out.println("Type a command (help, quit)");
        while (true) {
            System.out.print("rosegold ");
            String line = in.readLine();
            if (line == null) {
                break;
            }
            Dispatch result = dispatch(parseLine(line));
            if (result.quit) {
                break;
            }
        }
        return 0;
    }

    static Invocation parseArgv(String[] args) {
        Invocation inv = new Invocation();
        if (args.length > 0) {
            inv.cmd = args[0];
        }
        for (int i = 1; i < args.length; i++) {
            inv.args.add(args[i]);
        }
        return inv;
    }

    static Invocation parseLine(String line) {
        List<String> tokens = splitLine(line);
        Invocation inv = new Invocation();
        if (!tokens.isEmpty()) {
            inv.cmd = tokens.getFirst();
            inv.args.addAll(tokens.subList(1, tokens.size()));
        }
        return inv;
    }

    static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    static Dispatch dispatch(Invocation inv) throws Exception {
        Dispatch result = new Dispatch();
        if (inv.cmd.isEmpty()) {
            return result;
        }
        if (isCommand(inv.cmd, "help")) {
            printHelp();
            return result;
        }
        if (isCommand(inv.cmd, "quit") || inv.cmd.equals("exit")) {
            result.quit = true;
            return result;
        }
        if (isCommand(inv.cmd, "run")) {
            result.exitCode = runProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "check")) {
            result.exitCode = checkProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "fmt")) {
            result.exitCode = fmtProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "test")) {
            result.exitCode = testProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "new")) {
            result.exitCode = newProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "ir")) {
            result.exitCode = irProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "llvm")) {
            result.exitCode = llvmProgram(inv.args);
            return result;
        }
        if (isCommand(inv.cmd, "version")) {
            System.out.println("version : " + VERSION);
            return result;
        }
        System.err.println("Unknown command: " + inv.cmd);
        System.err.println("Commands: check, run, test, fmt, new, ir, llvm, version, help, quit");
        result.exitCode = 2;
        return result;
    }

    private static void printHelp() {
        System.out.println("Commands:");
        printAliases("help");
        printAliases("quit");
        printAliases("run");
        System.out.println("      run [file] [args...]");
        printAliases("check");
        System.out.println("      check [--json] [--stdin] [file|dir]");
        printAliases("fmt");
        System.out.println("      fmt [--write|-w] [--check] [--compact] [--no-comments] [file|dir]");
        printAliases("test");
        System.out.println("      test            project tests, or language suite");
        System.out.println("      test <file>     @test functions, or project.toml");
        System.out.println("      test <dir>      pass/ and fail/, or project.toml");
        printAliases("new");
        System.out.println("      new [dir]");
        System.out.println("      new module <name>");
        printAliases("ir");
        System.out.println("      ir [file]");
        printAliases("llvm");
        System.out.println("      llvm [file]");
        System.out.println("      llvm --link [file] [-o out]");
        System.out.println("      llvm --run [file] [args...]");
        System.out.println("      llvm --test [file]");
        System.out.println("      llvm --test --run [file]");
        printAliases("version");
        System.out.println("      version");
    }

    private static void printAliases(String name) {
        String letter = name.substring(0, 1);
        System.out.println("  " + name + " : " + letter + ", -" + letter + ", --" + letter);
    }

    private static int irProgram(List<String> args) throws Exception {
        return irProgram(args, Path.of("").toAbsolutePath());
    }

    static int irProgram(List<String> args, Path cwd) throws Exception {
        if (args.size() > 1) {
            System.err.println("Usage: ir [file]");
            return 2;
        }
        Path file;
        try {
            file = resolveEntry(args.isEmpty() ? "" : args.getFirst(), cwd);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        if (file == null) {
            System.err.println("Usage: ir [file]");
            return 2;
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<Diagnostic> diags = new ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, file.toString(), diags);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        if (!diags.isEmpty()) {
            for (Diagnostic d : diags) {
                System.err.println(d.toHuman());
            }
            return hasError(diags) ? 1 : 0;
        }
        Checker checker = Checker.check(program, file.toString(), diags);
        if (hasError(diags)) {
            for (Diagnostic d : diags) {
                System.err.println(d.toHuman());
            }
            return 1;
        }
        System.out.print(Compile.dumpEntry(checker));
        return 0;
    }

    private static int llvmProgram(List<String> args) throws Exception {
        return llvmProgram(args, Path.of("").toAbsolutePath());
    }

    static int llvmProgram(List<String> args, Path cwd) throws Exception {
        boolean link = false;
        boolean run = false;
        boolean test = false;
        String outArg = "";
        String path = "";
        List<String> extra = new ArrayList<>();
        String llvmUsage = "Usage: llvm [file] | llvm --link [file] [-o out] | llvm --run [file] [args...] | llvm --test [file] | llvm --test --run [file]";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--link")) {
                link = true;
            } else if (a.equals("--run")) {
                run = true;
                link = true;
            } else if (a.equals("--test")) {
                test = true;
            } else if (a.equals("-o")) {
                if (i + 1 >= args.size()) {
                    System.err.println(llvmUsage);
                    return 2;
                }
                outArg = args.get(++i);
            } else if (path.isEmpty()) {
                path = a;
            } else if (run) {
                extra.add(a);
            } else {
                System.err.println(llvmUsage);
                return 2;
            }
        }
        Path file;
        try {
            file = resolveEntry(path, cwd);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        if (file == null) {
            System.err.println(llvmUsage);
            return 2;
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        List<Diagnostic> diags = new ArrayList<>();
        Program program;
        try {
            program = Parser.parseSource(source, file.toString(), diags);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        if (!diags.isEmpty()) {
            for (Diagnostic d : diags) {
                System.err.println(d.toHuman());
            }
            return hasError(diags) ? 1 : 0;
        }
        Checker checker = Checker.check(program, file.toString(), diags);
        if (hasError(diags)) {
            for (Diagnostic d : diags) {
                System.err.println(d.toHuman());
            }
            return 1;
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
                System.err.println("no @test functions");
                return 0;
            }
        }
        if (!link) {
            System.out.print(Llvm.emit(checker, test));
            return 0;
        }
        Path nativeDir = LlvmLink.resolveNativeDir(cwd);
        String stem = file.getFileName() == null ? "a" : file.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        Path out;
        if (!outArg.isEmpty()) {
            out = cwd.resolve(outArg);
        } else if (run) {
            Path tmp = Files.createTempDirectory("rg-llvm-run-");
            tmp.toFile().deleteOnExit();
            out = tmp.resolve(stem);
        } else {
            out = cwd.resolve("build").resolve(stem);
        }
        LlvmLink.Result result = LlvmLink.link(checker, nativeDir, out, test);
        if (!result.ok) {
            System.err.println(result.message.isEmpty() ? "link failed" : result.message.trim());
            return result.exitCode == 0 ? 1 : result.exitCode;
        }
        if (!run) {
            System.out.println(result.output);
            return 0;
        }
        List<String> argv = new ArrayList<>();
        argv.add(file.toString());
        argv.addAll(extra);
        result = LlvmLink.exec(result.output, argv, result);
        System.out.print(result.stdout);
        if (!result.stdout.isEmpty() && !result.stdout.endsWith("\n") && result.exitCode != 0) {
            System.out.println();
        }
        return result.exitCode;
    }

    private static int runProgram(List<String> args) throws Exception {
        return runProgram(args, Path.of("").toAbsolutePath());
    }

    static int runProgram(List<String> args, Path cwd) throws Exception {
        Path file;
        List<String> argv = new ArrayList<>(args);
        try {
            file = resolveEntry(args.isEmpty() ? "" : args.getFirst(), cwd);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        if (file == null) {
            System.err.println("Usage: run [file] [args...]");
            return 2;
        }
        if (!args.isEmpty() && isProjectToml(args.getFirst())) {
            argv = new ArrayList<>();
            argv.add(file.toString());
            argv.addAll(args.subList(1, args.size()));
        } else if (args.isEmpty()) {
            argv = new ArrayList<>();
            argv.add(file.toString());
        }
        Run.Result result = Run.runFile(file, argv);
        printRunResult(result);
        return result.exitCode;
    }

    private static int checkProgram(List<String> args) throws Exception {
        return checkProgram(args, Path.of("").toAbsolutePath());
    }

    static int checkProgram(List<String> args, Path cwd) throws Exception {
        boolean json = false;
        boolean fromStdin = false;
        String path = "";
        for (String a : args) {
            if (a.equals("--json") || a.equals("-j")) {
                json = true;
            } else if (a.equals("--stdin")) {
                fromStdin = true;
            } else if (path.isEmpty()) {
                path = a;
            }
        }
        Path file;
        try {
            file = fromStdin ? null : resolveEntry(path, cwd);
        } catch (LangException ex) {
            Diagnostic d = ex.diagnostic;
            if (json) {
                System.out.print(Check.toJson(List.of(d)));
            } else {
                System.err.println(d.toHuman());
            }
            return 1;
        }
        if (!fromStdin && file == null && path.isEmpty()) {
            System.err.println("Usage: check [--json] [--stdin] [file|dir]");
            return 2;
        }
        Path dir = null;
        if (!fromStdin && file == null && path != null && !path.isEmpty()) {
            Path target = cwd.resolve(path).normalize();
            if (Files.isDirectory(target)) {
                dir = target;
            }
        }
        if (!fromStdin && file == null && dir == null) {
            System.err.println("cannot open " + path);
            return 2;
        }
        List<Diagnostic> diags;
        if (fromStdin) {
            if (path.isEmpty()) {
                System.err.println("Usage: check [--json] [--stdin] <file>");
                return 2;
            }
            String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            diags = Check.checkSource(source, path);
        } else if (dir != null) {
            diags = Check.checkDirectory(dir);
        } else {
            diags = Check.checkFile(file);
        }
        if (json) {
            System.out.print(Check.toJson(diags));
        } else {
            for (Diagnostic d : diags) {
                System.err.println(d.toHuman());
            }
        }
        return hasError(diags) ? 1 : 0;
    }

    private static boolean hasError(List<Diagnostic> diags) {
        for (Diagnostic d : diags) {
            String s = d.severity == null ? "error" : d.severity.toLowerCase();
            if (!s.equals("warning") && !s.equals("warn") && !s.equals("info")
                    && !s.equals("information") && !s.equals("hint")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveEntry(String path, Path cwd) throws Exception {
        if (path != null && !path.isEmpty()) {
            Path file = cwd.resolve(path).normalize();
            if (Files.isRegularFile(file) && Project.isProjectFile(file)) {
                Project project = Project.load(file);
                Path entry = project.entryFile;
                if (!Files.isRegularFile(entry)) {
                    Diagnostic d = new Diagnostic(file.toString(), 1, 1, "entry '" + project.entry + "' not found");
                    d.kind = "error";
                    throw new LangException(d.toHuman(), d);
                }
                return entry;
            }
            if (Files.isRegularFile(file)) {
                return file;
            }
            return null;
        }
        Project project = Project.find(cwd);
        if (project == null) {
            return null;
        }
        if (!Files.isRegularFile(project.entryFile)) {
            Diagnostic d = new Diagnostic(project.file.toString(), 1, 1, "entry '" + project.entry + "' not found");
            d.kind = "error";
            throw new LangException(d.toHuman(), d);
        }
        return project.entryFile;
    }

    private static boolean isProjectToml(String name) {
        return name != null && Project.isProjectFile(Path.of(name));
    }

    private static int testProgram(List<String> args) throws Exception {
        return testProgram(args, Path.of("").toAbsolutePath());
    }

    static int testProgram(List<String> args, Path cwd) throws Exception {
        if (args.size() > 1) {
            System.err.println("Usage: test [file|dir]");
            return 2;
        }
        Run.Result result;
        if (args.isEmpty()) {
            Path toml = cwd.resolve("project.toml");
            if (Files.isRegularFile(toml)) {
                result = Run.testProject(Project.load(toml));
            } else {
                result = Run.testLanguage(cwd);
            }
        } else {
            Path target = cwd.resolve(args.getFirst()).normalize();
            if (!Files.exists(target)) {
                System.err.println("cannot open " + args.getFirst());
                return 2;
            }
            result = Run.testPath(target, cwd, null);
        }
        printRunResult(result);
        return result.exitCode;
    }

    private static int newProgram(List<String> args) throws Exception {
        return newProgram(args, Path.of("").toAbsolutePath());
    }

    static int newProgram(List<String> args, Path cwd) throws Exception {
        if (!args.isEmpty() && args.getFirst().equals("module")) {
            if (args.size() != 2) {
                System.err.println("Usage: new module <name>");
                return 2;
            }
            Scaffold.Result result = Scaffold.addModule(cwd, args.get(1));
            return printScaffold(result);
        }
        if (args.size() > 1) {
            System.err.println("Usage: new [dir]");
            return 2;
        }
        Path dir = args.isEmpty() ? cwd : cwd.resolve(args.getFirst());
        return printScaffold(Scaffold.create(dir));
    }

    private static int printScaffold(Scaffold.Result result) {
        if (!result.message.isEmpty()) {
            if (result.ok) {
                System.out.println(result.message);
            } else {
                System.err.println(result.message);
            }
        }
        return result.exitCode;
    }

    private static int fmtProgram(List<String> args) throws Exception {
        return fmtProgram(args, Path.of("").toAbsolutePath());
    }

    static int fmtProgram(List<String> args, Path cwd) throws Exception {
        boolean write = false;
        boolean checkOnly = false;
        Format.Options opts = new Format.Options();
        String path = "";
        for (String a : args) {
            if (a.equals("--write") || a.equals("-w")) {
                write = true;
            } else if (a.equals("--check")) {
                checkOnly = true;
            } else if (a.equals("--compact")) {
                opts.blankBetweenItems = false;
            } else if (a.equals("--no-comments")) {
                opts.keepComments = false;
            } else if (path.isEmpty()) {
                path = a;
            }
        }
        Path file;
        try {
            file = resolveEntry(path, cwd);
        } catch (LangException ex) {
            System.err.println(ex.diagnostic.toHuman());
            return 1;
        }
        Path dir = null;
        if (file == null && path != null && !path.isEmpty()) {
            Path target = cwd.resolve(path).normalize();
            if (Files.isDirectory(target)) {
                dir = target;
            }
        }
        if (dir != null) {
            return fmtDirectory(dir, opts, write, checkOnly);
        }
        if (file == null) {
            if (path.isEmpty()) {
                System.err.println("Usage: fmt [--write|-w] [--check] [--compact] [--no-comments] [file|dir]");
            } else {
                System.err.println("cannot open " + path);
            }
            return 2;
        }
        return fmtFile(file, opts, write, checkOnly);
    }

    private static int fmtDirectory(Path dir, Format.Options opts, boolean write, boolean checkOnly)
            throws Exception {
        List<Path> files = Format.listRgFiles(dir);
        int code = 0;
        boolean mutate = !checkOnly;
        for (Path file : files) {
            int next = fmtFile(file, opts, mutate || write, checkOnly);
            if (next > code) {
                code = next;
            }
        }
        return code;
    }

    private static int fmtFile(Path file, Format.Options opts, boolean write, boolean checkOnly)
            throws Exception {
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Format.Result fmt = Format.formatSource(original, file.toString(), opts);
        if (!fmt.ok) {
            System.err.println(file + ": " + fmt.message);
            return fmt.exitCode != 0 ? fmt.exitCode : 1;
        }
        if (checkOnly) {
            if (!fmt.out.equals(original)) {
                System.err.println(file + " needs formatting");
                return 1;
            }
            return 0;
        }
        if (write) {
            if (!fmt.out.equals(original)) {
                Path tmp = file.resolveSibling(file.getFileName().toString() + ".rgfmt.tmp");
                Files.writeString(tmp, fmt.out, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    Files.deleteIfExists(tmp);
                    System.err.println("cannot write " + file);
                    return 2;
                }
            }
            return 0;
        }
        System.out.print(fmt.out);
        return 0;
    }

    private static void printRunResult(Run.Result result) {
        System.out.print(result.out);
        if (result.message.isEmpty()) {
            return;
        }
        if (!result.ok && !result.out.contains(result.message)) {
            System.err.println(result.message);
        } else if (result.ok && result.out.isEmpty()) {
            System.err.println(result.message);
        }
    }

    private static boolean isCommand(String actual, String name) {
        String letter = name.substring(0, 1);
        return actual.equals(name) || actual.equals(letter) || actual.equals("-" + letter)
                || actual.equals("--" + letter);
    }
}

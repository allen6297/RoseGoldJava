package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Scaffold {

    public static final class Result {
        public boolean ok = true;
        public int exitCode = 0;
        public String message = "";
        public Path dir;
        public Path toml;
        public Path entry;
        public Path tests;
        public Path module;
    }

    private Scaffold() {
    }

    public static Result create(Path dir) throws IOException {
        Result result = new Result();
        if (dir == null) {
            result.ok = false;
            result.exitCode = 2;
            result.message = "missing directory";
            return result;
        }
        Path target = dir.toAbsolutePath().normalize();
        if (Files.exists(target) && !Files.isDirectory(target)) {
            result.ok = false;
            result.exitCode = 2;
            result.message = "not a directory: " + target;
            return result;
        }
        Files.createDirectories(target);
        Path toml = target.resolve("project.toml");
        if (Files.exists(toml)) {
            result.ok = false;
            result.exitCode = 1;
            result.message = "project.toml already exists";
            result.dir = target;
            result.toml = toml;
            return result;
        }
        String name = projectName(target);
        Files.writeString(toml, projectToml(name), StandardCharsets.UTF_8);
        Path entry = target.resolve("main.rg");
        if (!Files.exists(entry)) {
            Files.writeString(entry, MAIN_RG, StandardCharsets.UTF_8);
        }
        Path tests = target.resolve("tests.rg");
        if (!Files.exists(tests)) {
            Files.writeString(tests, TESTS_RG, StandardCharsets.UTF_8);
        }
        result.dir = target;
        result.toml = toml;
        result.entry = entry;
        result.tests = tests;
        result.message = "created " + toml.getFileName() + ", " + entry.getFileName() + ", " + tests.getFileName();
        return result;
    }

    public static Result addModule(Path start, String name) throws IOException {
        Result result = new Result();
        String ident = name == null ? "" : name.trim();
        if (!Rename.isValidName(ident)) {
            result.ok = false;
            result.exitCode = 2;
            result.message = "invalid module name";
            return result;
        }
        if (Stdlib.isCrateStdlib(ident)) {
            result.ok = false;
            result.exitCode = 2;
            result.message = "module name '" + ident + "' is reserved";
            return result;
        }
        Path from = start == null ? Path.of("").toAbsolutePath() : start.toAbsolutePath().normalize();
        Path tomlFile = Project.locate(from);
        if (tomlFile == null) {
            result.ok = false;
            result.exitCode = 1;
            result.message = "no project.toml here";
            return result;
        }
        Project project = Project.load(tomlFile);
        result.dir = project.dir;
        result.toml = project.file;
        if (project.modules.containsKey(ident)) {
            result.ok = false;
            result.exitCode = 1;
            result.message = "module '" + ident + "' already exists";
            return result;
        }
        Path modDir = project.dir.resolve(ident);
        if (Files.exists(modDir) && !Files.isDirectory(modDir)) {
            result.ok = false;
            result.exitCode = 1;
            result.message = "not a directory: " + modDir;
            return result;
        }
        Files.createDirectories(modDir);
        Path lib = modDir.resolve("lib.rg");
        if (!Files.exists(lib)) {
            Files.writeString(lib, MODULE_LIB, StandardCharsets.UTF_8);
        }
        String source = Files.readString(tomlFile, StandardCharsets.UTF_8);
        Files.writeString(tomlFile, insertModuleLine(source, ident, ident), StandardCharsets.UTF_8);
        result.module = lib;
        result.message = "created module " + ident;
        return result;
    }

    static String insertModuleLine(String source, String name, String rel) {
        if (source == null) {
            source = "";
        }
        boolean crlf = source.contains("\r\n");
        String norm = source.replace("\r\n", "\n");
        String mapping = name + " = " + tomlString(rel);
        String[] lines = norm.split("\n", -1);
        int modules = -1;
        int nextHeader = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.equals("[modules]")) {
                modules = i;
            } else if (modules >= 0 && nextHeader < 0 && t.startsWith("[") && t.endsWith("]")) {
                nextHeader = i;
                break;
            }
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>(java.util.Arrays.asList(lines));
        if (modules < 0) {
            if (!out.isEmpty() && !out.getLast().isEmpty()) {
                out.add("");
            }
            out.add("[modules]");
            out.add(mapping);
        } else {
            int insert = nextHeader < 0 ? out.size() : nextHeader;
            while (insert > modules + 1 && out.get(insert - 1).trim().isEmpty()) {
                insert--;
            }
            out.add(insert, mapping);
        }
        String joined = String.join("\n", out);
        if (crlf) {
            joined = joined.replace("\n", "\r\n");
        }
        return joined;
    }

    static final String MODULE_LIB = """
            fn hello(): Int {
                return 1;
            }
            """;

    static final String MAIN_RG = """
            fn main(): Int {
                print("hello from RoseGold");
                return 0;
            }
            """;

    static final String TESTS_RG = """
            @test
            fn ok() {
                assert(true);
            }

            fn main(): Int {
                return 0;
            }
            """;

    static String projectName(Path dir) {
        Path name = dir.getFileName();
        String s = name == null ? "" : name.toString().trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "app";
        }
        return s;
    }

    static String projectToml(String name) {
        return "[project]\n"
                + "name = " + tomlString(name) + "\n"
                + "entry = \"main.rg\"\n"
                + "\n"
                + "[modules]\n"
                + "\n"
                + "[profile.debug]\n"
                + "\n"
                + "[profile.release]\n"
                + "\n"
                + "[profile.test]\n"
                + "entry = \"tests.rg\"\n";
    }

    private static String tomlString(String s) {
        if (s == null) {
            s = "";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

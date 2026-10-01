package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Project {

    private static final Set<String> PROJECT_KEYS = Set.of("name", "author", "version", "rosegold", "entry");
    private static final Set<String> TOP_KEYS = Set.of("project", "modules", "profile");
    private static final Set<String> PROFILE_KEYS = Set.of("debug", "release", "test");
    private static final Set<String> PROFILE_TEST_KEYS = Set.of("entry");

    public final Path file;
    public final Path dir;
    public final String name;
    public final String author;
    public final String version;
    public final String rosegold;
    public final String entry;
    public final Path entryFile;
    public final Map<String, Path> modules;
    public final String testEntry;
    public final int entryLine;
    public final int entryCol;
    public final int testEntryLine;
    public final int testEntryCol;
    private final Map<String, int[]> moduleLocs;
    private final Map<String, String> moduleRaws;

    private Project(
            Path file,
            Path dir,
            String name,
            String author,
            String version,
            String rosegold,
            String entry,
            Path entryFile,
            Map<String, Path> modules,
            String testEntry,
            int entryLine,
            int entryCol,
            int testEntryLine,
            int testEntryCol,
            Map<String, int[]> moduleLocs,
            Map<String, String> moduleRaws
    ) {
        this.file = file;
        this.dir = dir;
        this.name = name;
        this.author = author;
        this.version = version;
        this.rosegold = rosegold;
        this.entry = entry;
        this.entryFile = entryFile;
        this.modules = modules;
        this.testEntry = testEntry;
        this.entryLine = entryLine;
        this.entryCol = entryCol;
        this.testEntryLine = testEntryLine;
        this.testEntryCol = testEntryCol;
        this.moduleLocs = moduleLocs;
        this.moduleRaws = moduleRaws;
    }

    public static Project load(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        String source = Files.readString(file, StandardCharsets.UTF_8);
        return fromToml(file, source);
    }

    public static Project parse(Path file, String source) {
        return fromToml(file, source);
    }

    static Project fromToml(Path file, String source) {
        Path abs = file.toAbsolutePath().normalize();
        Toml.Value root = Toml.parse(source, abs.toString());
        if (!root.isTable()) {
            fail(abs, 1, 1, "project.toml root must be a table");
        }
        for (String key : root.table.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                Toml.Value v = root.get(key);
                fail(abs, v.line, v.col, "unknown table '" + key + "'");
            }
        }
        Toml.Value project = root.get("project");
        if (project == null) {
            fail(abs, 1, 1, "missing [project] table");
        }
        if (!project.isTable()) {
            fail(abs, project.line, project.col, "[project] must be a table");
        }
        for (String key : project.table.keySet()) {
            if (!PROJECT_KEYS.contains(key)) {
                Toml.Value v = project.get(key);
                fail(abs, v.line, v.col, "unknown project key '" + key + "'");
            }
        }
        String name = requireString(abs, project, "name");
        Toml.Value entryVal = project.get("entry");
        String entry = requireString(abs, project, "entry");
        int entryLine = entryVal == null ? 1 : entryVal.line;
        int entryCol = entryVal == null ? 1 : entryVal.col;
        String author = optionalString(abs, project, "author");
        String version = optionalString(abs, project, "version");
        String rosegold = optionalString(abs, project, "rosegold");
        Path dir = abs.getParent() == null ? Path.of(".").toAbsolutePath().normalize() : abs.getParent();
        Path entryFile = dir.resolve(entry).normalize();
        Map<String, Path> modules = new LinkedHashMap<>();
        Map<String, int[]> moduleLocs = new LinkedHashMap<>();
        Map<String, String> moduleRaws = new LinkedHashMap<>();
        Toml.Value mods = root.get("modules");
        if (mods != null) {
            if (!mods.isTable()) {
                fail(abs, mods.line, mods.col, "[modules] must be a table");
            }
            for (Map.Entry<String, Toml.Value> e : mods.table.entrySet()) {
                if (!e.getValue().isString()) {
                    fail(abs, e.getValue().line, e.getValue().col,
                            "module '" + e.getKey() + "' must be a string path");
                }
                String raw = e.getValue().s;
                modules.put(e.getKey(), dir.resolve(raw).normalize());
                moduleLocs.put(e.getKey(), new int[]{e.getValue().line, e.getValue().col});
                moduleRaws.put(e.getKey(), raw);
            }
        }
        String testEntry = "";
        int testEntryLine = 1;
        int testEntryCol = 1;
        Toml.Value profile = root.get("profile");
        if (profile != null) {
            if (!profile.isTable()) {
                fail(abs, profile.line, profile.col, "[profile] must be a table");
            }
            for (Map.Entry<String, Toml.Value> e : profile.table.entrySet()) {
                if (!PROFILE_KEYS.contains(e.getKey())) {
                    fail(abs, e.getValue().line, e.getValue().col, "unknown profile '" + e.getKey() + "'");
                }
                if (!e.getValue().isTable()) {
                    fail(abs, e.getValue().line, e.getValue().col, "[profile." + e.getKey() + "] must be a table");
                }
                Set<String> allowed = "test".equals(e.getKey()) ? PROFILE_TEST_KEYS : Set.of();
                for (Map.Entry<String, Toml.Value> inner : e.getValue().table.entrySet()) {
                    if (!allowed.contains(inner.getKey())) {
                        fail(abs, inner.getValue().line, inner.getValue().col,
                                "unknown profile." + e.getKey() + " key '" + inner.getKey() + "'");
                    }
                }
            }
            Toml.Value test = profile.get("test");
            if (test != null) {
                Toml.Value testVal = test.get("entry");
                testEntry = optionalString(abs, test, "entry", "profile.test.entry");
                if (testVal != null) {
                    testEntryLine = testVal.line;
                    testEntryCol = testVal.col;
                }
            }
        }
        return new Project(abs, dir, name, author, version, rosegold, entry, entryFile, modules, testEntry,
                entryLine, entryCol, testEntryLine, testEntryCol, moduleLocs, moduleRaws);
    }

    public static Path locate(Path start) {
        Path p = start == null ? Path.of("").toAbsolutePath() : start.toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) {
            if (p.getFileName().toString().equals("project.toml")) {
                return p;
            }
            p = p.getParent();
        }
        while (p != null) {
            Path cand = p.resolve("project.toml");
            if (Files.isRegularFile(cand)) {
                return cand.toAbsolutePath().normalize();
            }
            p = p.getParent();
        }
        return null;
    }

    public static Project find(Path start) throws IOException {
        Path file = locate(start);
        if (file == null) {
            return null;
        }
        return load(file);
    }

    public static boolean isProjectFile(Path path) {
        return path != null && "project.toml".equals(path.getFileName().toString());
    }

    public Path testTarget() {
        if (testEntry != null && !testEntry.isEmpty()) {
            return dir.resolve(testEntry).normalize();
        }
        Path unit = dir.resolve("tests.rg");
        if (Files.isRegularFile(unit)) {
            return unit;
        }
        Path suite = dir.resolve("tests");
        if (Files.isDirectory(suite)) {
            return suite;
        }
        return entryFile;
    }

    public Path modulePath(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Path hit = modules.get(name);
        if (hit != null) {
            return hit;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return modules.get(name.substring(0, dot));
        }
        return null;
    }

    public List<Diagnostic> missingPathDiagnostics() {
        List<Diagnostic> out = new ArrayList<>();
        if (!Files.isRegularFile(entryFile)) {
            out.add(pathDiag(entryLine, entryCol, "entry '" + entry + "' not found"));
        }
        if (testEntry != null && !testEntry.isEmpty() && !Files.exists(testTarget())) {
            out.add(pathDiag(testEntryLine, testEntryCol,
                    "profile.test.entry '" + testEntry + "' not found"));
        }
        for (Map.Entry<String, Path> e : modules.entrySet()) {
            Path mapped = e.getValue();
            if (Files.exists(mapped)) {
                continue;
            }
            int[] loc = moduleLocs.get(e.getKey());
            int line = loc == null ? 1 : loc[0];
            int col = loc == null ? 1 : loc[1];
            String raw = moduleRaws.getOrDefault(e.getKey(), mapped.toString());
            out.add(pathDiag(line, col, "module '" + e.getKey() + "' path '" + raw + "' not found"));
        }
        return out;
    }

    public List<Path> moduleSourceFiles() {
        List<Path> out = new ArrayList<>();
        for (String crate : modules.keySet()) {
            out.addAll(Stdlib.filesForCrate(crate, file.toString()));
        }
        return out;
    }

    public static List<Path> stdlibSourcesNear(String fromFile) {
        return Stdlib.stdlibSourceFiles(Stdlib.findStdlibRoot(fromFile));
    }

    public Path targetAt(String source, int offset) {
        if (source == null) {
            source = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        if (covers(source, offset, entryLine, entryCol) && Files.isRegularFile(entryFile)) {
            return entryFile;
        }
        if (testEntry != null && !testEntry.isEmpty()
                && covers(source, offset, testEntryLine, testEntryCol)) {
            Path test = testTarget();
            if (Files.exists(test)) {
                return preferRg(test);
            }
        }
        for (Map.Entry<String, Path> e : modules.entrySet()) {
            int[] loc = moduleLocs.get(e.getKey());
            if (loc == null || !covers(source, offset, loc[0], loc[1])) {
                continue;
            }
            Path mapped = e.getValue();
            if (!Files.exists(mapped)) {
                return null;
            }
            return preferRg(mapped);
        }
        return null;
    }

    private static Path preferRg(Path mapped) {
        if (Files.isRegularFile(mapped)) {
            return mapped;
        }
        if (!Files.isDirectory(mapped)) {
            return mapped;
        }
        Path lib = mapped.resolve("lib.rg");
        if (Files.isRegularFile(lib)) {
            return lib;
        }
        List<Path> files = Stdlib.listRgFiles(mapped);
        if (!files.isEmpty()) {
            return files.getFirst();
        }
        return mapped;
    }

    private static boolean covers(String source, int offset, int line, int col) {
        int start = SourcePos.offset(source, line, col);
        int end = SourcePos.tokenEnd(source, start);
        return offset >= start && offset <= end;
    }

    private Diagnostic pathDiag(int line, int col, String message) {
        Diagnostic d = new Diagnostic(file.toString(), line, col, message);
        d.kind = "error";
        return d;
    }

    private static String requireString(Path file, Toml.Value table, String key) {
        Toml.Value v = table.get(key);
        if (v == null) {
            fail(file, table.line, table.col, "missing project." + key);
        }
        if (!v.isString()) {
            fail(file, v.line, v.col, "project." + key + " must be a string");
        }
        if (v.s.isEmpty()) {
            fail(file, v.line, v.col, "project." + key + " must not be empty");
        }
        return v.s;
    }

    private static String optionalString(Path file, Toml.Value table, String key) {
        return optionalString(file, table, key, "project." + key);
    }

    private static String optionalString(Path file, Toml.Value table, String key, String label) {
        Toml.Value v = table.get(key);
        if (v == null) {
            return "";
        }
        if (!v.isString()) {
            fail(file, v.line, v.col, label + " must be a string");
        }
        if (v.s.isEmpty()) {
            fail(file, v.line, v.col, label + " must not be empty");
        }
        return v.s;
    }

    private static void fail(Path file, int line, int col, String message) {
        Diagnostic d = new Diagnostic(file.toString(), line, col, message);
        d.kind = "error";
        throw new LangException(d.toHuman(), d);
    }
}

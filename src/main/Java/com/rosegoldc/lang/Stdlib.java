package com.rosegoldc.lang;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class Stdlib {

    private static final String[] CHILDREN = {
            "math", "str", "io", "vec", "time", "path", "json", "regex", "ui"
    };
    private static final Map<String, Map<String, List<Types.StdlibExport>>> INDEX =
            new ConcurrentHashMap<>();
    private static final Map<String, Map<String, List<Types.StdlibExport>>> PROJECT_INDEX =
            new ConcurrentHashMap<>();

    private static volatile Path bundledRoot;

    private Stdlib() {
    }

    static String[] stdlibChildren() {
        return CHILDREN;
    }

    static boolean isStdlibChild(String name) {
        for (String child : CHILDREN) {
            if (child.equals(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean isCrateStdlib(String name) {
        if (name.equals("std") || isStdlibChild(name)) {
            return true;
        }
        return name.length() > 4 && name.startsWith("std.");
    }

    static String canonicalStdlibName(String name) {
        if (isStdlibChild(name)) {
            return "std." + name;
        }
        return name;
    }

    static Path findStdlibRoot(String fromFile) {
        List<Path> starts = new ArrayList<>();
        if (fromFile != null && !fromFile.isEmpty()) {
            Path p = Path.of(fromFile);
            Path parent = p.getParent();
            starts.add(parent == null ? Path.of(".") : parent);
        }
        starts.add(Path.of("."));
        for (Path start : starts) {
            Path p = start.toAbsolutePath().normalize();
            while (true) {
                Path cand = stdlibDirAt(p);
                if (cand != null) {
                    return cand;
                }
                Path parent = p.getParent();
                if (parent == null || parent.equals(p)) {
                    break;
                }
                p = parent;
            }
        }
        return bundledStdlibRoot();
    }

    private static Path stdlibDirAt(Path dir) {
        Path cand = dir.resolve("builtin").resolve("std");
        if (Files.isDirectory(cand)) {
            return cand;
        }
        return null;
    }

    static Path bundledStdlibRoot() {
        Path cached = bundledRoot;
        if (cached != null && Files.isRegularFile(cached.resolve("lib.rg"))) {
            return cached;
        }
        synchronized (Stdlib.class) {
            cached = bundledRoot;
            if (cached != null && Files.isRegularFile(cached.resolve("lib.rg"))) {
                return cached;
            }
            Path unpacked = unpackBundledStdlib();
            if (unpacked != null) {
                bundledRoot = unpacked;
            }
            return unpacked;
        }
    }

    private static Path unpackBundledStdlib() {
        URL marker = Stdlib.class.getResource("/builtin/std/lib.rg");
        if (marker == null) {
            return null;
        }
        try {
            if ("file".equals(marker.getProtocol())) {
                Path lib = Path.of(marker.toURI());
                Path std = lib.getParent();
                if (std != null && Files.isDirectory(std)) {
                    return std;
                }
            }
            if ("jar".equals(marker.getProtocol())) {
                String spec = marker.toString();
                int bang = spec.indexOf("!/");
                if (bang < 0) {
                    return null;
                }
                Path jar = Path.of(new URI(spec.substring(4, bang)));
                Path dest = Path.of(System.getProperty("java.io.tmpdir"), "rosegold-stdlib-" + Main.VERSION);
                unpackJarCached(jar, "builtin/std/", dest, "lib.rg");
                if (Files.isRegularFile(dest.resolve("lib.rg"))) {
                    return dest;
                }
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    static void unpackJarCached(Path jar, String prefix, Path dest, String readyFile) throws IOException {
        dest = dest.toAbsolutePath().normalize();
        Path stampFile = dest.resolve(".rg-unpack-stamp");
        String token = Files.getLastModifiedTime(jar).toMillis() + "\n"
                + jar.toAbsolutePath().normalize();
        if (Files.isRegularFile(dest.resolve(readyFile)) && Files.isRegularFile(stampFile)
                && token.equals(Files.readString(stampFile, StandardCharsets.UTF_8))) {
            return;
        }
        unpackJarPrefix(jar, prefix, dest);
        Files.writeString(stampFile, token, StandardCharsets.UTF_8);
    }

    private static void unpackJarPrefix(Path jar, String prefix, Path dest) throws IOException {
        dest = dest.toAbsolutePath().normalize();
        Files.createDirectories(dest);
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) {
                    continue;
                }
                Path out = dest.resolve(rel).normalize();
                if (!out.startsWith(dest)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = jf.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static Path stdlibChildDir(Path root, String child) {
        Path dir = root;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < child.length(); i++) {
            char c = child.charAt(i);
            if (c == '.') {
                if (!part.isEmpty()) {
                    dir = dir.resolve(part.toString());
                    part.setLength(0);
                }
            } else {
                part.append(c);
            }
        }
        if (!part.isEmpty()) {
            dir = dir.resolve(part.toString());
        }
        return dir;
    }

    static Path crateDir(String name, String fromFile) {
        Path root = findStdlibRoot(fromFile);
        if (root == null || name.equals("std")) {
            return root;
        }
        String child = name;
        if (child.length() > 4 && child.startsWith("std.")) {
            child = child.substring(4);
        } else if (!isStdlibChild(child)) {
            return null;
        }
        return stdlibChildDir(root, child);
    }

    static Path projectModule(String name, String fromFile) {
        try {
            Path start = fromFile == null || fromFile.isEmpty() ? Path.of(".") : Path.of(fromFile);
            Project project = Project.find(start);
            if (project == null) {
                return null;
            }
            return project.modulePath(name);
        } catch (Exception ex) {
            return null;
        }
    }

    static List<Path> filesForCrate(String crate, String fromFile) {
        List<Path> files = new ArrayList<>();
        Path dir = crateDir(crate, fromFile);
        if (dir == null) {
            dir = projectModule(crate, fromFile);
        }
        if (dir == null) {
            return files;
        }
        if (Files.isRegularFile(dir)) {
            files.add(dir.toAbsolutePath().normalize());
            return files;
        }
        for (Path file : listRgFiles(dir)) {
            files.add(file.toAbsolutePath().normalize());
        }
        return files;
    }

    static String crateOf(String local, String source, String fromFile) {
        return crateOf(local, parseQuiet(source, fromFile), fromFile);
    }

    static String crateOf(String local, Program program, String fromFile) {
        if (local == null || local.isEmpty()) {
            return "";
        }
        if (!filesForCrate(local, fromFile).isEmpty()) {
            return local;
        }
        String aliased = crateFromImports(program, local);
        if (!aliased.isEmpty() && !filesForCrate(aliased, fromFile).isEmpty()) {
            return aliased;
        }
        return "";
    }

    static String crateFromImports(Program program, String local) {
        if (program == null || local == null || local.isEmpty()) {
            return "";
        }
        for (ImportDecl im : program.imports) {
            if (im.isFrom || im.path.isEmpty()) {
                continue;
            }
            String bind = im.alias == null || im.alias.isEmpty() ? im.path.getLast() : im.alias;
            if (local.equals(bind)) {
                return String.join(".", im.path);
            }
        }
        return "";
    }

    static String fromBindCrate(Program program, String local) {
        ImportDecl im = fromBind(program, local);
        return im == null ? "" : im.path.getFirst();
    }

    static String fromBindExported(Program program, String local) {
        ImportDecl im = fromBind(program, local);
        return im == null ? "" : im.path.get(1);
    }

    private static ImportDecl fromBind(Program program, String local) {
        if (program == null || local == null || local.isEmpty()) {
            return null;
        }
        for (ImportDecl im : program.imports) {
            if (!im.isFrom || im.path.size() < 2) {
                continue;
            }
            String exported = im.path.get(1);
            String bind = im.alias == null || im.alias.isEmpty() ? exported : im.alias;
            if (local.equals(bind)) {
                return im;
            }
        }
        return null;
    }

    private static Program parseQuiet(String source, String path) {
        try {
            return Parser.parseSource(source == null ? "" : source, path == null ? "" : path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return new Program();
        }
    }

    static String crateNameOfFile(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        Path root = findStdlibRoot(path);
        if (root == null) {
            return "";
        }
        Path filePath = weaklyCanonical(Path.of(path));
        Path rootPath = weaklyCanonical(root);
        Path rel;
        try {
            rel = rootPath.relativize(filePath);
        } catch (IllegalArgumentException ex) {
            return "";
        }
        String s = generic(rel);
        if (s.isEmpty() || s.equals(".")) {
            return "";
        }
        if (s.startsWith("..")) {
            return "";
        }
        Path parent = rel.getParent();
        if (parent == null || parent.toString().isEmpty() || parent.toString().equals(".")) {
            return "std";
        }
        StringBuilder crate = new StringBuilder("std");
        for (Path part : parent) {
            crate.append('.').append(part.toString().replace('\\', '/'));
        }
        return crate.toString();
    }

    static Map<String, List<Types.StdlibExport>> exportIndex(String fromFile) {
        Path root = findStdlibRoot(fromFile);
        String key = root == null ? "" : generic(root);
        return INDEX.computeIfAbsent(key, k -> {
            Map<String, List<Types.StdlibExport>> idx = new LinkedHashMap<>();
            if (root == null) {
                return idx;
            }
            indexDir(root, "std", idx);
            for (String child : CHILDREN) {
                indexDir(root.resolve(child), child, idx);
            }
            return idx;
        });
    }

    static Types.StdlibExport lookup(String name, String fromFile) {
        List<Types.StdlibExport> hits = exportIndex(fromFile).get(name);
        if (hits == null || hits.isEmpty()) {
            hits = projectExportIndex(fromFile).get(name);
        }
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return hits.getFirst();
    }

    static String importHint(String name, String fromFile) {
        List<Types.StdlibExport> hits = exportIndex(fromFile).get(name);
        if (hits == null || hits.isEmpty()) {
            hits = projectExportIndex(fromFile).get(name);
        }
        if (hits == null || hits.isEmpty()) {
            if (crateDir(name, fromFile) == null && projectModule(name, fromFile) != null) {
                return " (in crate " + name + "; try 'import " + name + "')";
            }
            return "";
        }
        StringBuilder crates = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                crates.append(" or ");
            }
            crates.append(hits.get(i).crate);
        }
        String tryMsg;
        if (hits.size() == 1) {
            Types.StdlibExport h = hits.getFirst();
            if (h.kind.equals("fn")) {
                if (h.crate.equals("std")) {
                    tryMsg = "; try 'import std' then 'std." + name + "'";
                } else {
                    tryMsg = "; try 'import " + h.crate + "' then '" + h.crate + "." + name + "'";
                }
            } else if (h.crate.equals("std")) {
                tryMsg = "; try 'import std'";
            } else {
                tryMsg = "; try 'from " + h.crate + " import " + name + "' or 'import " + h.crate + "'";
            }
        } else {
            tryMsg = "; try importing " + crates;
        }
        return " (in crate " + crates + tryMsg + ")";
    }

    static Map<String, List<Types.StdlibExport>> projectExportIndex(String fromFile) {
        try {
            Path start = fromFile == null || fromFile.isEmpty() ? Path.of(".") : Path.of(fromFile);
            Project project = Project.find(start);
            if (project == null) {
                return Map.of();
            }
            String key = generic(weaklyCanonical(project.file));
            return PROJECT_INDEX.computeIfAbsent(key, k -> {
                Map<String, List<Types.StdlibExport>> idx = new LinkedHashMap<>();
                for (String crate : project.modules.keySet()) {
                    Path mapped = project.modulePath(crate);
                    if (mapped == null) {
                        continue;
                    }
                    if (Files.isRegularFile(mapped)) {
                        try {
                            indexSource(Files.readString(mapped, StandardCharsets.UTF_8), crate, idx);
                        } catch (IOException ignored) {
                        }
                    } else {
                        indexDir(mapped, crate, idx);
                    }
                }
                return idx;
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    static Path weaklyCanonical(Path p) {
        try {
            return p.toAbsolutePath().normalize();
        } catch (Exception ex) {
            return p;
        }
    }

    static String generic(Path p) {
        return p.toString().replace('\\', '/');
    }

    static boolean sameRgFile(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        try {
            if (Files.isRegularFile(Path.of(a)) && Files.isRegularFile(Path.of(b))
                    && Files.isSameFile(Path.of(a), Path.of(b))) {
                return true;
            }
        } catch (IOException ignored) {
        }
        String na = generic(weaklyCanonical(Path.of(a))).toLowerCase(Locale.ROOT);
        String nb = generic(weaklyCanonical(Path.of(b))).toLowerCase(Locale.ROOT);
        return na.equals(nb);
    }

    static List<Path> listRgFiles(Path dir) {
        List<Path> paths = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return paths;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".rg")) {
                    paths.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        paths.sort(Comparator.comparing(Stdlib::generic));
        return paths;
    }

    static List<Path> stdlibSourceFiles(Path root) {
        LinkedHashMap<String, Path> out = new LinkedHashMap<>();
        if (root == null) {
            return List.of();
        }
        addRgFiles(out, root);
        for (String child : CHILDREN) {
            addRgFiles(out, stdlibChildDir(root, child));
        }
        return new ArrayList<>(out.values());
    }

    private static void addRgFiles(LinkedHashMap<String, Path> out, Path dir) {
        for (Path file : listRgFiles(dir)) {
            out.put(generic(file.toAbsolutePath().normalize()), file.toAbsolutePath().normalize());
        }
    }

    static List<String> listChildDirs(Path dir) {
        List<String> kids = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return kids;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String kid = entry.getFileName().toString();
                if (kid.isEmpty() || kid.charAt(0) == '.') {
                    continue;
                }
                kids.add(kid);
            }
        } catch (IOException ignored) {
        }
        kids.sort(String::compareTo);
        return kids;
    }

    private static void indexDir(Path dir, String crate, Map<String, List<Types.StdlibExport>> out) {
        for (Path path : listRgFiles(dir)) {
            try {
                indexSource(Files.readString(path, StandardCharsets.UTF_8), crate, out);
            } catch (IOException ignored) {
            }
        }
    }

    private static void indexSource(String source, String crate, Map<String, List<Types.StdlibExport>> out) {
        List<Token> tokens = Lexer.tokenize(source, "", new ArrayList<>());
        int depth = 0;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Tok k = tokens.get(i).kind;
            if (k == Tok.LBrace) {
                depth++;
                continue;
            }
            if (k == Tok.RBrace) {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth != 0) {
                continue;
            }
            String kind = switch (k) {
                case Function -> "fn";
                case Struct -> "struct";
                case Data -> "data";
                case Class -> "class";
                case Trait -> "trait";
                case Enum -> "enum";
                default -> null;
            };
            if (kind == null || tokens.get(i + 1).kind != Tok.Identifier) {
                continue;
            }
            String name = tokens.get(i + 1).text;
            List<Types.StdlibExport> list = out.computeIfAbsent(name, n -> new ArrayList<>());
            boolean have = false;
            for (Types.StdlibExport e : list) {
                if (e.crate.equals(crate)) {
                    have = true;
                    break;
                }
            }
            if (!have) {
                Types.StdlibExport ex = new Types.StdlibExport();
                ex.crate = crate;
                ex.kind = kind;
                list.add(ex);
            }
        }
    }
}

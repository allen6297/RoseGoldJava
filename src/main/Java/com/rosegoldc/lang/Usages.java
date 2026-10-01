package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Usages {

    public static final class Hit {
        public final String path;
        public final String name;
        public final String qualifier;
        public final boolean write;
        public final int line;
        public final int col;
        public final int start;
        public final int end;

        Hit(String path, String name, String qualifier, boolean write, int line, int col, int start, int end) {
            this.path = path == null ? "" : path;
            this.name = name;
            this.qualifier = qualifier == null ? "" : qualifier;
            this.write = write;
            this.line = line;
            this.col = col;
            this.start = start;
            this.end = end;
        }
    }

    private Usages() {
    }

    public static List<Hit> inFile(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        try {
            Idents.Ident ident = Idents.at(source, offset);
            if (ident == null) {
                return List.of();
            }
            return ofName(source, path, ident.name, ident.qualifier);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    public static List<Hit> includingCrates(String source, String path, int offset) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        Idents.Ident ident = identAt(source, offset);
        if (ident == null) {
            return List.of();
        }
        List<Hit> out = new ArrayList<>(ofName(source, path, ident.name, ident.qualifier));
        Set<String> seen = new HashSet<>();
        seen.add(fileId(path));
        String crateName = ident.name;
        try {
            Program program = Parser.parseSource(source, path, new ArrayList<>());
            String exported = Stdlib.fromBindExported(program, ident.name);
            if (!exported.isEmpty()) {
                crateName = exported;
            }
        } catch (RuntimeException ignored) {
        }
        for (Path file : crateSourceFiles(source, path, ident)) {
            String id = fileId(file.toString());
            if (!seen.add(id)) {
                continue;
            }
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            out.addAll(ofName(src, file.toString(), crateName, ""));
        }
        return out;
    }

    public static List<Path> crateSourceFiles(String source, String path, int offset) {
        Idents.Ident ident = identAt(source, offset);
        if (ident == null) {
            return List.of();
        }
        return crateSourceFiles(source, path, ident);
    }

    private static List<Path> crateSourceFiles(String source, String path, Idents.Ident ident) {
        String crate = crateOf(source, path, ident);
        if (crate.isEmpty()) {
            return List.of();
        }
        return Stdlib.filesForCrate(crate, path);
    }

    private static String crateOf(String source, String path, Idents.Ident ident) {
        if (!ident.qualifier.isEmpty()) {
            return Stdlib.crateOf(ident.qualifier, source, path);
        }
        try {
            Program program = Parser.parseSource(source, path, new ArrayList<>());
            String crate = Stdlib.fromBindCrate(program, ident.name);
            if (!crate.isEmpty()) {
                return crate;
            }
        } catch (RuntimeException ignored) {
        }
        Types.StdlibExport ex = Stdlib.lookup(ident.name, path);
        if (ex != null && ex.crate != null && !ex.crate.isEmpty()) {
            return ex.crate;
        }
        return "";
    }

    private static String fileId(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return Stdlib.generic(Stdlib.weaklyCanonical(Path.of(path)));
    }

    public static String crateSearchName(String source, String path, int offset) {
        Idents.Ident ident = identAt(source, offset);
        if (ident == null) {
            return "";
        }
        try {
            Program program = Parser.parseSource(source, path, new ArrayList<>());
            String exported = Stdlib.fromBindExported(program, ident.name);
            if (!exported.isEmpty()) {
                return exported;
            }
        } catch (RuntimeException ignored) {
        }
        return ident.name;
    }

    public static String nameAt(String source, int offset) {
        Idents.Ident ident = identAt(source, offset);
        return ident == null ? "" : ident.name;
    }

    public static String qualifierAt(String source, int offset) {
        Idents.Ident ident = identAt(source, offset);
        return ident == null ? "" : ident.qualifier;
    }

    private static Idents.Ident identAt(String source, int offset) {
        if (source == null) {
            source = "";
        }
        offset = Math.clamp(offset, 0, source.length());
        try {
            return Idents.at(source, offset);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static List<Hit> ofName(String source, String path, String name, String qualifier) {
        if (source == null) {
            source = "";
        }
        if (path == null) {
            path = "";
        }
        if (name == null || name.isEmpty()) {
            return List.of();
        }
        String qual = qualifier == null ? "" : qualifier;
        List<Hit> out = new ArrayList<>();
        List<Token> tokens;
        try {
            tokens = Lexer.tokenize(source, path, new ArrayList<>());
        } catch (RuntimeException ex) {
            return List.of();
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind != Tok.Identifier || !name.equals(t.text)) {
                continue;
            }
            String hitQual = "";
            if (i >= 2 && tokens.get(i - 1).kind == Tok.Dot && tokens.get(i - 2).kind == Tok.Identifier) {
                hitQual = tokens.get(i - 2).text;
            }
            boolean write = i > 0 && isDeclTok(tokens.get(i - 1).kind);
            if (i + 1 < tokens.size() && isAssignTok(tokens.get(i + 1).kind)) {
                write = true;
            }
            if (!qual.isEmpty() && !hitQual.equals(qual) && !write) {
                continue;
            }
            int start = SourcePos.offset(source, t.line, t.col);
            out.add(new Hit(path, name, hitQual, write, t.line, t.col, start, start + t.text.length()));
        }
        return out;
    }

    private static boolean isDeclTok(Tok k) {
        return k == Tok.Function || k == Tok.Struct || k == Tok.Data || k == Tok.Class
                || k == Tok.Trait || k == Tok.Enum || k == Tok.Module || k == Tok.Variable
                || k == Tok.Constant || k == Tok.Signal;
    }

    private static boolean isAssignTok(Tok k) {
        return k == Tok.Eq || k == Tok.PlusEq || k == Tok.MinusEq || k == Tok.StarEq || k == Tok.SlashEq;
    }
}

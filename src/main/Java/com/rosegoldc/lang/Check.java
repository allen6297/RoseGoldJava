package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Check {

    private Check() {
    }

    public static List<Diagnostic> checkSource(String source, String path) {
        List<Diagnostic> diags = new ArrayList<>();
        try {
            Program program = Parser.parseSource(source, path, diags);
            Checker.check(program, path, diags);
            UnusedImports.check(source, path, program, diags);
        } catch (LangException ex) {
            diags.add(ex.diagnostic);
        }
        return diags;
    }

    public static List<Diagnostic> checkFile(Path path) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        return checkSource(source, path.toString());
    }

    public static List<Diagnostic> checkDirectory(Path dir) throws IOException {
        List<Diagnostic> diags = new ArrayList<>();
        for (Path file : Format.listRgFiles(dir)) {
            diags.addAll(checkFile(file));
        }
        return diags;
    }

    public static String toJson(List<Diagnostic> diags) {
        if (diags.isEmpty()) {
            return "[]\n";
        }
        StringBuilder ss = new StringBuilder("[\n");
        for (int i = 0; i < diags.size(); i++) {
            Diagnostic d = diags.get(i);
            if (i > 0) {
                ss.append(",\n");
            }
            ss.append("  {\n");
            ss.append("    \"file\": \"").append(jsonEscape(d.file)).append("\",\n");
            ss.append("    \"line\": ").append(d.line).append(",\n");
            ss.append("    \"col\": ").append(d.col).append(",\n");
            ss.append("    \"severity\": \"").append(jsonEscape(d.severity)).append("\",\n");
            ss.append("    \"message\": \"").append(jsonEscape(d.message)).append("\"\n");
            ss.append("  }");
        }
        ss.append("\n]\n");
        return ss.toString();
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

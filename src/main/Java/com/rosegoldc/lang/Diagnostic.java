package com.rosegoldc.lang;

public final class Diagnostic {

    public String file = "";
    public int line = 1;
    public int col = 1;
    public String severity = "error";
    public String message = "";
    public String kind = "parse error";

    public Diagnostic() {
    }

    public Diagnostic(String file, int line, int col, String message) {
        this.file = file == null ? "" : file;
        this.line = line > 0 ? line : 1;
        this.col = col > 0 ? col : 1;
        this.message = message;
    }

    @Override
    public String toString() {
        return toHuman();
    }

    public String toHuman() {
        StringBuilder ss = new StringBuilder();
        if (!file.isEmpty()) {
            ss.append(file).append(':');
        }
        ss.append(line).append(':').append(col).append(": ").append(severity).append(": ").append(message);
        return ss.toString();
    }

    public static String locatedError(String kind, String file, int line, int col, String msg) {
        StringBuilder s = new StringBuilder(kind);
        if (file != null && !file.isEmpty()) {
            s.append(" in ").append(file);
        }
        if (line > 0) {
            s.append(" at ").append(line).append(':').append(col);
        }
        s.append(": ").append(msg);
        return s.toString();
    }
}

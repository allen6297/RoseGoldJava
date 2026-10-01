package com.rosegoldc.lang;

public final class SourcePos {

    private SourcePos() {
    }

    public static int[] lineCol(String text, int offset) {
        int line = 1;
        int col = 1;
        if (text == null || text.isEmpty() || offset <= 0) {
            return new int[]{1, 1};
        }
        int n = Math.min(offset, text.length());
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    public static int offset(String text, int line, int col) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lineIndex = Math.max(0, line - 1);
        int i = 0;
        int current = 0;
        while (i < text.length() && current < lineIndex) {
            if (text.charAt(i) == '\n') {
                current++;
            }
            i++;
        }
        int lineEnd = i;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        if (lineEnd > i && text.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }
        int colOff = Math.max(0, col - 1);
        return Math.min(i + colOff, lineEnd);
    }

    public static int tokenEnd(String text, int start) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int i = Math.max(0, Math.min(start, text.length()));
        if (i >= text.length()) {
            return text.length();
        }
        int end = i;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '\n' || c == '\r' || Character.isWhitespace(c)) {
                break;
            }
            end++;
        }
        if (end == i) {
            end = Math.min(i + 1, text.length());
        }
        return end;
    }
}

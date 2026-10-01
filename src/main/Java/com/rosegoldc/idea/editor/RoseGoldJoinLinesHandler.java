package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.editorActions.JoinRawLinesHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldJoinLinesHandler implements JoinRawLinesHandlerDelegate {

    @Override
    public int tryJoinRawLines(@NotNull Document document, @NotNull PsiFile file, int start, int end) {
        if (!(file instanceof RoseGoldFile) || start < 0 || end > document.getTextLength() || start > end) {
            return CANNOT_JOIN;
        }
        CharSequence text = document.getCharsSequence();
        int line1 = document.getLineNumber(Math.min(start, text.length()));
        int line2 = document.getLineNumber(Math.min(end, Math.max(0, text.length() - 1)));
        if (line2 <= line1) {
            return CANNOT_JOIN;
        }
        int line1Start = document.getLineStartOffset(line1);
        int line1End = document.getLineEndOffset(line1);
        int line2End = document.getLineEndOffset(line2);
        String first = text.subSequence(line1Start, line1End).toString();
        String second = text.subSequence(end, line2End).toString();
        Comment left = lineComment(first);
        Comment right = lineComment(second);
        if (left != null && left.wholeLine && right != null && right.wholeLine) {
            String glued = " " + right.body.stripLeading();
            document.replaceString(start, end + right.prefixLength, glued);
            return start + 1;
        }
        if (left != null && left.wholeLine && (right == null || !right.wholeLine)) {
            String indent = leading(first);
            String code = second.strip();
            String replacement = indent + code + " " + left.prefix + " " + left.body.strip();
            document.replaceString(line1Start, line2End, replacement);
            return line1Start + indent.length() + code.length();
        }
        if (left != null && !left.wholeLine && (right == null || !right.wholeLine)) {
            String code1 = first.substring(0, left.at).stripTrailing();
            String code2 = second.strip();
            String indent = leading(first);
            String replacement = indent + code1.strip() + glue(lastChar(code1), firstChar(code2)) + code2
                    + " " + left.prefix + " " + left.body.strip();
            document.replaceString(line1Start, line2End, replacement);
            return line1Start + indent.length() + code1.strip().length() + 1;
        }
        return CANNOT_JOIN;
    }

    @Override
    public int tryJoinLines(@NotNull Document document, @NotNull PsiFile file, int start, int end) {
        if (!(file instanceof RoseGoldFile) || start < 0 || end > document.getTextLength() || start > end) {
            return CANNOT_JOIN;
        }
        CharSequence text = document.getCharsSequence();
        if (insideString(text, start)) {
            return CANNOT_JOIN;
        }
        char left = start > 0 ? text.charAt(start - 1) : 0;
        char right = end < text.length() ? text.charAt(end) : 0;
        String glued = glue(left, right);
        document.replaceString(start, end, glued);
        return start + glued.length();
    }

    static String glue(char left, char right) {
        if (left == 0 || right == 0) {
            return "";
        }
        if (left == '{' || right == '}') {
            return " ";
        }
        if ((right == '(' || right == '[') && isWord(left)) {
            return "";
        }
        if (".([:".indexOf(left) >= 0 || ")]},.;.".indexOf(right) >= 0) {
            return "";
        }
        return " ";
    }

    private static Comment lineComment(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i >= line.length()) {
            return null;
        }
        int at = indexOfLineComment(line);
        if (at < 0) {
            return null;
        }
        boolean whole = at == i;
        String prefix = line.startsWith("//", at) ? "//" : "#";
        int bodyAt = at + prefix.length();
        String body = bodyAt <= line.length() ? line.substring(bodyAt) : "";
        return new Comment(at, prefix.length(), prefix, body, whole);
    }

    private static int indexOfLineComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return i;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '#') {
                return -1;
            }
            if (c == '#') {
                return i;
            }
        }
        return -1;
    }

    private static boolean insideString(CharSequence text, int offset) {
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        boolean inString = false;
        for (int i = lineStart; i < offset && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == lineStart || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
        }
        return inString;
    }

    private static String leading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static char lastChar(String s) {
        String t = s.stripTrailing();
        return t.isEmpty() ? 0 : t.charAt(t.length() - 1);
    }

    private static char firstChar(String s) {
        String t = s.stripLeading();
        return t.isEmpty() ? 0 : t.charAt(0);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private record Comment(int at, int prefixLength, String prefix, String body, boolean wholeLine) {
    }
}

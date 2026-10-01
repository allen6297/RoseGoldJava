package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldBackspaceHandler extends BackspaceHandlerDelegate {

    @Override
    public void beforeCharDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
    }

    @Override
    public boolean charDeleted(char c, @NotNull PsiFile file, @NotNull Editor editor) {
        if (!(file instanceof RoseGoldFile)) {
            return false;
        }
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        CharSequence text = document.getCharsSequence();
        if (c == '\n') {
            return collapseEmptyBlock(document, text, offset);
        }
        char closer = closer(c);
        if (closer == 0 || offset >= text.length()) {
            return false;
        }
        int i = offset;
        while (i < text.length() && isSpace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && text.charAt(i) == closer) {
            document.deleteString(offset, i + 1);
            return true;
        }
        return false;
    }

    private static boolean collapseEmptyBlock(Document document, CharSequence text, int offset) {
        int left = offset - 1;
        while (left >= 0 && isSpace(text.charAt(left))) {
            left--;
        }
        if (left < 0) {
            return false;
        }
        char closer = closer(text.charAt(left));
        if (closer == 0) {
            return false;
        }
        int right = offset;
        while (right < text.length() && (isSpace(text.charAt(right)) || text.charAt(right) == '\n' || text.charAt(right) == '\r')) {
            right++;
        }
        if (right < text.length() && text.charAt(right) == closer) {
            document.deleteString(offset, right);
            return true;
        }
        return false;
    }

    private static char closer(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"' -> '"';
            default -> 0;
        };
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }
}

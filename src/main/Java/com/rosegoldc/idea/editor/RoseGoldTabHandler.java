package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldTabHandler extends EditorActionHandler {

    private final EditorActionHandler original;

    public RoseGoldTabHandler(EditorActionHandler original) {
        this.original = original;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        return true;
    }

    @Override
    protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        if (canTabOut(editor)) {
            tabOut(editor);
            return;
        }
        original.execute(editor, caret, dataContext);
    }

    private static boolean canTabOut(Editor editor) {
        if (editor.getProject() == null || LookupManager.getActiveLookup(editor) != null) {
            return false;
        }
        if (TemplateManager.getInstance(editor.getProject()).getActiveTemplate(editor) != null) {
            return false;
        }
        PsiFile file = PsiDocumentManager.getInstance(editor.getProject()).getPsiFile(editor.getDocument());
        if (!(file instanceof RoseGoldFile)) {
            return false;
        }
        return closerOffset(editor) >= 0;
    }

    private static void tabOut(Editor editor) {
        int closer = closerOffset(editor);
        if (closer >= 0) {
            editor.getCaretModel().moveToOffset(closer + 1);
        }
    }

    private static int closerOffset(Editor editor) {
        int offset = editor.getCaretModel().getOffset();
        CharSequence text = editor.getDocument().getCharsSequence();
        int i = offset;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        if (i < text.length() && isCloser(text.charAt(i))) {
            return i;
        }
        return -1;
    }

    private static boolean isCloser(char c) {
        return c == ')' || c == ']' || c == '}' || c == '"';
    }
}

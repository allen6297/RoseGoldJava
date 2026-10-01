package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import com.rosegoldc.lang.Fixes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoseGoldQuickFix implements IntentionAction, HighPriorityAction, DumbAware {

    private final String title;

    public RoseGoldQuickFix(@NotNull String title) {
        this.title = title;
    }

    @Override
    public @NotNull String getText() {
        return title;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "RoseGold";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return file instanceof RoseGoldFile && editor != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (!(file instanceof RoseGoldFile rg) || editor == null) {
            return;
        }
        Document document = editor.getDocument();
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        String text = document.getText();
        int offset = Math.clamp(editor.getCaretModel().getOffset(), 0, text.length());
        Fixes.Action action = null;
        for (Fixes.Action candidate : Fixes.suggest(text, path, offset)) {
            if (candidate.title.equals(title)) {
                action = candidate;
                break;
            }
        }
        if (action == null) {
            return;
        }
        List<Fixes.Edit> edits = new ArrayList<>(action.edits);
        edits.sort(Comparator.comparingInt((Fixes.Edit e) -> e.start).reversed());
        for (Fixes.Edit edit : edits) {
            int start = Math.clamp(edit.start, 0, document.getTextLength());
            int end = Math.clamp(edit.end, start, document.getTextLength());
            document.replaceString(start, end, edit.text);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}

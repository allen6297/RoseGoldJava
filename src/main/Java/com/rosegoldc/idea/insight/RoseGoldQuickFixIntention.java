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

public abstract class RoseGoldQuickFixIntention implements IntentionAction, HighPriorityAction, DumbAware {

    private final String fallbackTitle;
    private Fixes.Action cached;

    RoseGoldQuickFixIntention(String fallbackTitle) {
        this.fallbackTitle = fallbackTitle;
    }

    abstract boolean accept(Fixes.Action action);

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        cached = null;
        if (!(file instanceof RoseGoldFile rg) || editor == null) {
            return false;
        }
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        String text = rg.getText();
        int offset = Math.clamp(editor.getCaretModel().getOffset(), 0, text.length());
        for (Fixes.Action action : Fixes.suggest(text, path, offset)) {
            if (accept(action)) {
                cached = action;
                return true;
            }
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        if (cached == null && !isAvailable(project, editor, file)) {
            return;
        }
        if (cached == null || editor == null) {
            return;
        }
        Document document = editor.getDocument();
        List<Fixes.Edit> edits = new ArrayList<>(cached.edits);
        edits.sort(Comparator.comparingInt((Fixes.Edit e) -> e.start).reversed());
        for (Fixes.Edit edit : edits) {
            int start = Math.clamp(edit.start, 0, document.getTextLength());
            int end = Math.clamp(edit.end, start, document.getTextLength());
            document.replaceString(start, end, edit.text);
        }
    }

    @Override
    public @NotNull String getText() {
        return cached == null ? fallbackTitle : cached.title;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "RoseGold";
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    public static final class WrapTry extends RoseGoldQuickFixIntention {
        public WrapTry() {
            super("Wrap with try");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.WrapTry;
        }
    }

    public static final class MatchArms extends RoseGoldQuickFixIntention {
        public MatchArms() {
            super("Add missing match arms");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.MatchArms;
        }
    }

    public static final class ImplementTrait extends RoseGoldQuickFixIntention {
        public ImplementTrait() {
            super("Implement missing trait methods");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.ImplementTrait;
        }
    }

    public static final class AddMethod extends RoseGoldQuickFixIntention {
        public AddMethod() {
            super("Add missing method");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.AddMethod;
        }
    }

    public static final class ImportCrate extends RoseGoldQuickFixIntention {
        public ImportCrate() {
            super("Import crate");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.ImportCrate;
        }
    }

    public static final class AwaitFuture extends RoseGoldQuickFixIntention {
        public AwaitFuture() {
            super("Await Future");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.AwaitFuture;
        }
    }

    public static final class RemoveImport extends RoseGoldQuickFixIntention {
        public RemoveImport() {
            super("Remove unused import");
        }

        @Override
        boolean accept(Fixes.Action action) {
            return action.kind == Fixes.Kind.RemoveImport;
        }
    }
}

package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class RoseGoldExtendWordSelectionHandler implements ExtendWordSelectionHandler {

    @Override
    public boolean canSelect(@NotNull PsiElement e) {
        return e.getContainingFile() instanceof RoseGoldFile;
    }

    @Override
    public @Nullable List<TextRange> select(
            @NotNull PsiElement e,
            @NotNull CharSequence editorText,
            int cursorOffset,
            @NotNull Editor editor
    ) {
        List<TextRange> ranges = RoseGoldSelect.ranges(editorText, cursorOffset);
        return ranges.isEmpty() ? null : ranges;
    }
}

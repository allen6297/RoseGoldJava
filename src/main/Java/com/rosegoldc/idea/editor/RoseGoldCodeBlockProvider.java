package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.editorActions.CodeBlockProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldCodeBlockProvider implements CodeBlockProvider {

    @Override
    public @Nullable TextRange getCodeBlockRange(Editor editor, PsiFile psiFile) {
        if (!(psiFile instanceof RoseGoldFile) || editor == null) {
            return null;
        }
        return RoseGoldSelect.innermostBraces(editor.getDocument().getCharsSequence(), editor.getCaretModel().getOffset());
    }
}

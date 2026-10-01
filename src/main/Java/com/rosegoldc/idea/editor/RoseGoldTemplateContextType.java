package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldTemplateContextType extends TemplateContextType {

    public RoseGoldTemplateContextType() {
        super("RoseGold");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext templateActionContext) {
        if (!templateActionContext.getFile().getLanguage().isKindOf(RoseGold.INSTANCE)) {
            return false;
        }
        PsiElement at = templateActionContext.getFile().findElementAt(templateActionContext.getStartOffset());
        if (at == null) {
            return true;
        }
        IElementType type = at.getNode().getElementType();
        return type != RoseGoldTokenTypes.STRING && type != RoseGoldTokenTypes.COMMENT;
    }

    @Override
    public @NotNull SyntaxHighlighter createHighlighter() {
        return new RoseGoldSyntaxHighlighter();
    }
}

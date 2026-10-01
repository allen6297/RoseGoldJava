package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldSpellcheckingStrategy extends SpellcheckingStrategy implements DumbAware {

    @Override
    public @NotNull Tokenizer<?> getTokenizer(PsiElement element) {
        if (element == null || element.getNode() == null) {
            return EMPTY_TOKENIZER;
        }
        IElementType type = element.getNode().getElementType();
        if (type == RoseGoldTokenTypes.COMMENT) {
            return myCommentTokenizer;
        }
        if (type == RoseGoldTokenTypes.STRING) {
            return TEXT_TOKENIZER;
        }
        return EMPTY_TOKENIZER;
    }
}

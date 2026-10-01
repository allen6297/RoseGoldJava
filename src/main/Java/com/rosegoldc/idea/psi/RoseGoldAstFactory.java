package com.rosegoldc.idea.psi;

import com.intellij.lang.ASTFactory;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldAstFactory extends ASTFactory {

    @Override
    public @Nullable LeafElement createLeaf(@NotNull IElementType type, @NotNull CharSequence text) {
        if (type == RoseGoldTokenTypes.IDENTIFIER || type == RoseGoldTokenTypes.TYPE) {
            return new RoseGoldIdent(type, text);
        }
        return null;
    }
}

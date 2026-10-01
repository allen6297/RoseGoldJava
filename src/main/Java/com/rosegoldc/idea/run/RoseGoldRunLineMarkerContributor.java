package com.rosegoldc.idea.run;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldRunLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement leaf)) {
            return null;
        }
        if (leaf.getElementType() != RoseGoldTokenTypes.IDENTIFIER) {
            return null;
        }
        if (!"main".equals(leaf.getText())) {
            return null;
        }
        if (!(leaf.getContainingFile() instanceof RoseGoldFile)) {
            return null;
        }
        PsiElement prev = prevLeaf(leaf);
        if (prev == null || !"fn".equals(prev.getText())) {
            return null;
        }
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run);
    }

    private static @Nullable PsiElement prevLeaf(@NotNull PsiElement element) {
        PsiElement prev = element.getPrevSibling();
        while (prev instanceof PsiWhiteSpace) {
            prev = prev.getPrevSibling();
        }
        return prev;
    }
}

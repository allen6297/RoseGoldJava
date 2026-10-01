package com.rosegoldc.idea.run;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.rosegoldc.lang.Tests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldTestLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        if (!(element instanceof LeafPsiElement leaf)) {
            return null;
        }
        if (leaf.getElementType() != RoseGoldTokenTypes.IDENTIFIER) {
            return null;
        }
        if (!(leaf.getContainingFile() instanceof RoseGoldFile file)) {
            return null;
        }
        Tests.Fn fn = Tests.at(file.getText(), leaf.getTextOffset());
        if (fn == null || !fn.name.equals(leaf.getText())) {
            return null;
        }
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run);
    }
}

package com.rosegoldc.idea.refactor;

import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldRefactoringSupportProvider extends RefactoringSupportProvider {

    @Override
    public boolean isAvailable(@NotNull PsiElement context) {
        return context.getContainingFile() instanceof RoseGoldFile;
    }

    @Override
    public boolean isInplaceRenameAvailable(@NotNull PsiElement element, PsiElement context) {
        return canRename(element);
    }

    @Override
    public boolean isMemberInplaceRenameAvailable(@NotNull PsiElement element, @Nullable PsiElement context) {
        return canRename(element);
    }

    private static boolean canRename(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            return decl.getNameIdentifier() != null;
        }
        return RoseGoldUsages.isSymbolLeaf(element);
    }
}

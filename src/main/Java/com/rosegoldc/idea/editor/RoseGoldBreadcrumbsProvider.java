package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldTypes;
import com.intellij.lang.Language;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

public final class RoseGoldBreadcrumbsProvider implements BreadcrumbsProvider {

    @Override
    public Language[] getLanguages() {
        return new Language[]{RoseGold.INSTANCE};
    }

    @Override
    public boolean acceptElement(@NotNull PsiElement element) {
        if (!(element instanceof RoseGoldDecl decl)) {
            return false;
        }
        IElementType kind = decl.getNode().getElementType();
        if (kind == RoseGoldTypes.IMPORT || kind == RoseGoldTypes.IMPL) {
            return false;
        }
        String name = decl.getName();
        return name != null && !name.isEmpty();
    }

    @Override
    public @NotNull @NlsSafe String getElementInfo(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            String name = decl.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return element.getText();
    }

    @Override
    public @Nullable Icon getElementIcon(@NotNull PsiElement element) {
        return element.getIcon(0);
    }

    @Override
    public @Nullable String getElementTooltip(@NotNull PsiElement element) {
        return element instanceof RoseGoldDecl decl ? decl.kindLabel() : null;
    }
}

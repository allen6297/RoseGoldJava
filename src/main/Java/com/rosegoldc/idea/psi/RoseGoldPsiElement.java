package com.rosegoldc.idea.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class RoseGoldPsiElement extends ASTWrapperPsiElement {

    public RoseGoldPsiElement(@NotNull ASTNode node) {
        super(node);
    }
}

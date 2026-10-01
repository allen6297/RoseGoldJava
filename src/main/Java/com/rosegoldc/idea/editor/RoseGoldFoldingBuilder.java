package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class RoseGoldFoldingBuilder extends FoldingBuilderEx implements DumbAware {

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root,
            @NotNull Document document,
            boolean quick
    ) {
        List<FoldingDescriptor> out = new ArrayList<>();
        for (RoseGoldDecl decl : PsiTreeUtil.findChildrenOfType(root, RoseGoldDecl.class)) {
            TextRange range = bodyRange(decl, document);
            if (range != null) {
                out.add(new FoldingDescriptor(decl.getNode(), range));
            }
        }
        return out.toArray(FoldingDescriptor[]::new);
    }

    @Override
    public @NotNull String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    @Nullable
    private static TextRange bodyRange(@NotNull RoseGoldDecl decl, @NotNull Document document) {
        PsiElement lbrace = null;
        PsiElement rbrace = null;
        int depth = 0;
        for (PsiElement child = decl.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNode().getElementType() == RoseGoldTokenTypes.LBRACE) {
                if (lbrace == null) {
                    lbrace = child;
                }
                depth++;
            } else if (child.getNode().getElementType() == RoseGoldTokenTypes.RBRACE && lbrace != null) {
                depth--;
                if (depth == 0) {
                    rbrace = child;
                    break;
                }
            }
        }
        if (lbrace == null || rbrace == null) {
            return null;
        }
        int start = lbrace.getTextOffset() + lbrace.getTextLength();
        int end = rbrace.getTextOffset();
        if (end - start < 1) {
            return null;
        }
        if (document.getLineNumber(lbrace.getTextOffset()) == document.getLineNumber(rbrace.getTextOffset())) {
            return null;
        }
        return TextRange.create(start, end);
    }
}

package com.rosegoldc.idea.refactor;

import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldFindUsagesProvider implements FindUsagesProvider {

    private static final TokenSet IDENTIFIERS = TokenSet.create(
            RoseGoldTokenTypes.IDENTIFIER,
            RoseGoldTokenTypes.TYPE
    );

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            return decl.getNameIdentifier() != null;
        }
        return RoseGoldUsages.isSymbolLeaf(element);
    }

    @Override
    public @NotNull WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
                new RoseGoldLexer(),
                IDENTIFIERS,
                TokenSet.create(RoseGoldTokenTypes.COMMENT),
                TokenSet.create(RoseGoldTokenTypes.STRING)
        );
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        RoseGoldDecl decl = PsiTreeUtil.getParentOfType(element, RoseGoldDecl.class, false);
        if (decl != null && (element.equals(decl) || element.equals(decl.getNameIdentifier()))) {
            return decl.kindLabel();
        }
        return "symbol";
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            String name = decl.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        String text = element.getText();
        return text == null ? "" : text;
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return getDescriptiveName(element);
    }
}

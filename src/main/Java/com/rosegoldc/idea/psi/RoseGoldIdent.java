package com.rosegoldc.idea.psi;

import com.rosegoldc.idea.RoseGold;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.IncorrectOperationException;
import com.rosegoldc.lang.Goto;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldIdent extends LeafPsiElement implements PsiNamedElement, PsiReference {

    public RoseGoldIdent(@NotNull IElementType type, CharSequence text) {
        super(type, text);
    }

    @Override
    public String getName() {
        return getText();
    }

    @Override
    public PsiElement setName(@NonNls @NotNull String name) throws IncorrectOperationException {
        return (PsiElement) replaceWithText(name);
    }

    @Override
    public PsiReference getReference() {
        return this;
    }

    @Override
    public @NotNull PsiElement getElement() {
        return this;
    }

    @Override
    public @NotNull TextRange getRangeInElement() {
        return new TextRange(0, getTextLength());
    }

    @Override
    public @Nullable PsiElement resolve() {
        PsiFile file = getContainingFile();
        if (!(file instanceof RoseGoldFile rg)) {
            return null;
        }
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        Goto.Loc loc = Goto.at(rg.getText(), path, getTextOffset());
        if (loc == null) {
            return null;
        }
        return rg.findElementAt(loc.start);
    }

    @Override
    public @NotNull String getCanonicalText() {
        return getText();
    }

    @Override
    public PsiElement handleElementRename(@NotNull String newElementName) throws IncorrectOperationException {
        return setName(newElementName);
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
        throw new IncorrectOperationException("cannot bind RoseGold identifier");
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        PsiElement target = element instanceof RoseGoldDecl decl ? decl.getNameIdentifier() : element;
        PsiElement resolved = resolve();
        return resolved != null && resolved.equals(target);
    }

    @Override
    public boolean isSoft() {
        return false;
    }
}

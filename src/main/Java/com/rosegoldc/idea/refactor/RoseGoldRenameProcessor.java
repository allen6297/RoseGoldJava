package com.rosegoldc.idea.refactor;

import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.SearchScope;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.rosegoldc.lang.Rename;
import com.rosegoldc.lang.Usages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Map;

public final class RoseGoldRenameProcessor extends RenamePsiElementProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            return decl.getNameIdentifier() != null;
        }
        return RoseGoldUsages.isSymbolLeaf(element);
    }

    @Override
    public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element, Editor editor) {
        if (element instanceof RoseGoldDecl decl && decl.getNameIdentifier() != null) {
            return decl.getNameIdentifier();
        }
        return element;
    }

    @Override
    public boolean isInplaceRenameSupported() {
        return true;
    }

    @Override
    public boolean isToSearchInComments(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isToSearchForTextOccurrences(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public void prepareRenaming(
            @NotNull PsiElement element,
            @NotNull String newName,
            @NotNull Map<PsiElement, String> allRenames,
            @NotNull SearchScope scope
    ) {
        if (!Rename.isValidName(newName)) {
            return;
        }
        PsiElement origin = element instanceof RoseGoldDecl decl && decl.getNameIdentifier() != null
                ? decl.getNameIdentifier()
                : element;
        PsiElement file = origin.getContainingFile();
        if (!(file instanceof RoseGoldFile rg)) {
            return;
        }
        String text = rg.getText();
        int offset = origin.getTextOffset();
        String name = Usages.nameAt(text, offset);
        if (name.isEmpty() || name.equals(newName) || Rename.isKeyword(name)) {
            return;
        }
        String qualifier = Usages.qualifierAt(text, offset);
        String originPath = RoseGoldUsages.pathOf(rg);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (PsiFile psi : RoseGoldUsages.filesInScope(rg, scope)) {
            String path = RoseGoldUsages.pathOf(psi);
            seen.add(RoseGoldUsages.norm(path));
            if (Rename.skipFile(originPath, path)) {
                continue;
            }
            addHits(allRenames, psi, name, qualifier, newName);
        }
        for (PsiFile psi : RoseGoldUsages.extraCrateFiles(rg, offset, seen)) {
            String path = RoseGoldUsages.pathOf(psi);
            if (Rename.skipFile(originPath, path)) {
                continue;
            }
            addHits(allRenames, psi, name, "", newName);
        }
    }

    private static void addHits(
            Map<PsiElement, String> allRenames,
            PsiFile psi,
            String name,
            String qualifier,
            String newName
    ) {
        for (Usages.Hit hit : Usages.ofName(psi.getText(), RoseGoldUsages.pathOf(psi), name, qualifier)) {
            PsiElement at = psi.findElementAt(hit.start);
            if (at != null) {
                allRenames.put(at, newName);
            }
        }
    }
}

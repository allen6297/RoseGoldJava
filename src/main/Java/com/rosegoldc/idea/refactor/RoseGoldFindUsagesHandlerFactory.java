package com.rosegoldc.idea.refactor;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import com.rosegoldc.idea.psi.RoseGoldDecl;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.lang.Usages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;

public final class RoseGoldFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        if (element instanceof RoseGoldDecl decl) {
            return decl.getNameIdentifier() != null;
        }
        return RoseGoldUsages.isSymbolLeaf(element);
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(
            @NotNull PsiElement element,
            boolean forHighlightUsages
    ) {
        if (!canFindUsages(element)) {
            return null;
        }
        return new Handler(element);
    }

    private static final class Handler extends FindUsagesHandler {

        private Handler(@NotNull PsiElement element) {
            super(element);
        }

        @Override
        public boolean processElementUsages(
                @NotNull PsiElement element,
                @NotNull Processor<? super UsageInfo> processor,
                @NotNull FindUsagesOptions options
        ) {
            PsiFile origin = element.getContainingFile();
            if (!(origin instanceof RoseGoldFile)) {
                return true;
            }
            if (element instanceof RoseGoldDecl decl && decl.getNameIdentifier() != null) {
                element = decl.getNameIdentifier();
            }
            String text = origin.getText();
            int offset = element.getTextOffset();
            String name = Usages.nameAt(text, offset);
            if (name.isEmpty()) {
                return true;
            }
            String qualifier = Usages.qualifierAt(text, offset);
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (PsiFile file : RoseGoldUsages.filesInScope(origin, options.searchScope)) {
                String path = RoseGoldUsages.pathOf(file);
                seen.add(RoseGoldUsages.norm(path));
                for (Usages.Hit hit : Usages.ofName(file.getText(), path, name, qualifier)) {
                    PsiElement at = file.findElementAt(hit.start);
                    if (at == null) {
                        continue;
                    }
                    if (!processor.process(new UsageInfo(at))) {
                        return false;
                    }
                }
            }
            for (PsiFile file : RoseGoldUsages.extraCrateFiles(origin, offset, seen)) {
                String path = RoseGoldUsages.pathOf(file);
                String crateName = Usages.crateSearchName(text, RoseGoldUsages.pathOf(origin), offset);
                for (Usages.Hit hit : Usages.ofName(file.getText(), path, crateName, "")) {
                    PsiElement at = file.findElementAt(hit.start);
                    if (at == null) {
                        continue;
                    }
                    if (!processor.process(new UsageInfo(at))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}

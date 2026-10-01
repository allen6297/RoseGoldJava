package com.rosegoldc.idea.refactor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase;
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.Consumer;
import com.rosegoldc.lang.Usages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class RoseGoldHighlightUsagesHandlerFactory implements HighlightUsagesHandlerFactory {

    @Override
    public @Nullable HighlightUsagesHandlerBase<?> createHighlightUsagesHandler(
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        if (!(file instanceof RoseGoldFile)) {
            return null;
        }
        int offset = editor.getCaretModel().getOffset();
        String path = file.getVirtualFile() != null ? file.getVirtualFile().getPath() : file.getName();
        List<Usages.Hit> hits = Usages.inFile(file.getText(), path, offset);
        if (hits.isEmpty()) {
            return null;
        }
        return new Handler(editor, file, hits);
    }

    private static final class Handler extends HighlightUsagesHandlerBase<PsiElement> {
        private final List<Usages.Hit> hits;

        private Handler(@NotNull Editor editor, @NotNull PsiFile file, @NotNull List<Usages.Hit> hits) {
            super(editor, file);
            this.hits = hits;
        }

        @Override
        public @NotNull List<PsiElement> getTargets() {
            return List.of(myFile);
        }

        @Override
        protected void selectTargets(
                @NotNull List<? extends PsiElement> targets,
                @NotNull Consumer<? super List<? extends PsiElement>> selectionConsumer
        ) {
            selectionConsumer.consume(targets);
        }

        @Override
        public void computeUsages(@NotNull List<? extends PsiElement> targets) {
            for (Usages.Hit hit : hits) {
                TextRange range = TextRange.create(hit.start, hit.end);
                if (hit.write) {
                    myWriteUsages.add(range);
                } else {
                    myReadUsages.add(range);
                }
            }
        }
    }
}

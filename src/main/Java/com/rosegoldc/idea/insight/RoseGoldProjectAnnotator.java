package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGoldProjects;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Diagnostic;
import com.rosegoldc.lang.LangException;
import com.rosegoldc.lang.Project;
import com.rosegoldc.lang.SourcePos;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class RoseGoldProjectAnnotator implements Annotator {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return;
        }
        PsiElement target = file.getFirstChild();
        if (target == null) {
            if (element != file) {
                return;
            }
        } else if (element != target) {
            return;
        }
        VirtualFile vf = file.getVirtualFile();
        if (!RoseGoldProjects.isProjectToml(vf)) {
            return;
        }
        String text = file.getText();
        Path path = Path.of(vf.getPath());
        try {
            Project project = Project.parse(path, text);
            for (Diagnostic d : project.missingPathDiagnostics()) {
                holder.newAnnotation(HighlightSeverity.ERROR, d.message)
                        .range(highlight(text, d.line, d.col))
                        .create();
            }
        } catch (LangException ex) {
            holder.newAnnotation(HighlightSeverity.ERROR, ex.diagnostic.message)
                    .range(highlight(text, ex.diagnostic.line, ex.diagnostic.col))
                    .create();
        }
    }

    private static TextRange highlight(String text, int line, int col) {
        if (text == null || text.isEmpty()) {
            return TextRange.create(0, 0);
        }
        int start = SourcePos.offset(text, line, col);
        int end = SourcePos.tokenEnd(text, start);
        start = Math.max(0, Math.min(start, text.length() - 1));
        end = Math.max(start + 1, Math.min(end, text.length()));
        return TextRange.create(start, end);
    }
}

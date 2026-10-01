package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Check;
import com.rosegoldc.lang.Diagnostic;
import com.rosegoldc.lang.Fixes;
import com.rosegoldc.lang.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class RoseGoldExternalAnnotator
        extends ExternalAnnotator<RoseGoldExternalAnnotator.Info, List<Diagnostic>>
        implements DumbAware {

    static final class Info {
        final String path;
        final String text;

        Info(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    @Override
    public @Nullable Info collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof RoseGoldFile)) {
            return null;
        }
        String path = file.getVirtualFile() != null ? file.getVirtualFile().getPath() : file.getName();
        return new Info(path, file.getText());
    }

    @Override
    public @NotNull List<Diagnostic> doAnnotate(@Nullable Info info) {
        if (info == null) {
            return List.of();
        }
        return Check.checkSource(info.text, info.path);
    }

    @Override
    public void apply(
            @NotNull PsiFile file,
            @Nullable List<Diagnostic> diagnostics,
            @NotNull AnnotationHolder holder
    ) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        String text = file.getText();
        String path = file.getVirtualFile() != null ? file.getVirtualFile().getPath() : file.getName();
        for (Diagnostic d : diagnostics) {
            int start = SourcePos.offset(text, d.line, d.col);
            int end = SourcePos.tokenEnd(text, start);
            if (end <= start) {
                continue;
            }
            var annotation = holder.newAnnotation(severity(d), d.message)
                    .range(TextRange.create(start, end));
            for (var action : Fixes.forDiagnostic(text, path, d)) {
                annotation = annotation.withFix(new RoseGoldQuickFix(action.title));
            }
            annotation.create();
        }
    }

    static HighlightSeverity severity(Diagnostic d) {
        String s = d.severity == null ? "" : d.severity.toLowerCase();
        if (s.equals("warning") || s.equals("warn")) {
            return HighlightSeverity.WARNING;
        }
        if (s.equals("info") || s.equals("information") || s.equals("hint")) {
            return HighlightSeverity.INFORMATION;
        }
        return HighlightSeverity.ERROR;
    }
}

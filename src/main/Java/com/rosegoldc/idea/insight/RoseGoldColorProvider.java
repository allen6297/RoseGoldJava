package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.ElementColorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Colors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;

public final class RoseGoldColorProvider implements ElementColorProvider, DumbAware {

    @Override
    public @Nullable Color getColorFrom(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (!(file instanceof RoseGoldFile)) {
            return null;
        }
        Colors.Span span = Colors.at(file.getText(), element.getTextOffset());
        if (span == null || element.getTextOffset() != span.start) {
            return null;
        }
        return new Color(span.r, span.g, span.b, span.a);
    }

    @Override
    public void setColorTo(@NotNull PsiElement element, @NotNull Color color) {
        PsiFile file = element.getContainingFile();
        if (!(file instanceof RoseGoldFile)) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        if (document == null) {
            return;
        }
        String text = document.getText();
        Colors.Span span = Colors.at(text, element.getTextOffset());
        if (span == null) {
            return;
        }
        String next = Colors.rewrite(span, color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
        int start = Math.clamp(span.start, 0, document.getTextLength());
        int end = Math.clamp(span.end, start, document.getTextLength());
        document.replaceString(start, end, next);
    }
}

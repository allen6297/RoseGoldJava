package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector;
import com.intellij.codeInsight.hints.ImmediateConfigurable;
import com.intellij.codeInsight.hints.InlayGroup;
import com.intellij.codeInsight.hints.InlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsProvider;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.NoSettings;
import com.intellij.codeInsight.hints.SettingsKey;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Inlays;
import com.rosegoldc.lang.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;

public final class RoseGoldInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("rosegold.inlays");
    private static final String PREVIEW = """
            fn add(a: Int, b: Int): Int {
                return a + b;
            }
            fn main(): Int {
                var n = 1;
                return add(n, 2);
            }
            """;

    @Override
    public @Nullable InlayHintsCollector getCollectorFor(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull NoSettings settings,
            @NotNull InlayHintsSink sink
    ) {
        if (!(file instanceof RoseGoldFile)) {
            return null;
        }
        return new FactoryInlayHintsCollector(editor) {
            @Override
            public boolean collect(@NotNull PsiElement element, @NotNull Editor editor, @NotNull InlayHintsSink sink) {
                if (!(element instanceof RoseGoldFile rg)) {
                    return true;
                }
                String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
                String text = rg.getText();
                PresentationFactory factory = getFactory();
                for (Inlays.Hint hint : Inlays.collect(text, path)) {
                    int offset = SourcePos.offset(text, hint.line, hint.col);
                    if (offset < 0 || offset > text.length()) {
                        continue;
                    }
                    boolean after = hint.kind == Inlays.Kind.Type;
                    InlayPresentation presentation = factory.roundWithBackgroundAndSmallInset(factory.smallText(hint.label));
                    sink.addInlineElement(offset, after, presentation, false);
                }
                return false;
            }
        };
    }

    @Override
    public @NotNull NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    public @NotNull String getName() {
        return "RoseGold hints";
    }

    @Override
    public @NotNull InlayGroup getGroup() {
        return InlayGroup.PARAMETERS_GROUP;
    }

    @Override
    public @NotNull SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    public @NotNull String getPreviewText() {
        return PREVIEW;
    }

    @Override
    public @NotNull ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return listener -> new JPanel();
    }

    @Override
    public boolean isLanguageSupported(@NotNull Language language) {
        return language.is(RoseGold.INSTANCE);
    }
}

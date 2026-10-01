package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import com.rosegoldc.lang.Completions;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldCompletionContributor extends CompletionContributor implements DumbAware {

    public RoseGoldCompletionContributor() {
        extend(
                CompletionType.BASIC,
                PlatformPatterns.psiElement().withLanguage(RoseGold.INSTANCE),
                new CompletionProvider<>() {
                    @Override
                    protected void addCompletions(
                            @NotNull CompletionParameters parameters,
                            @NotNull ProcessingContext context,
                            @NotNull CompletionResultSet result
                    ) {
                        if (!(parameters.getOriginalFile() instanceof RoseGoldFile file)) {
                            return;
                        }
                        String path = file.getVirtualFile() != null
                                ? file.getVirtualFile().getPath()
                                : file.getName();
                        String text = file.getText();
                        int offset = Math.max(0, Math.min(parameters.getOffset(), text.length()));
                        for (Completions.Item item : Completions.suggest(text, path, offset)) {
                            result.addElement(
                                    LookupElementBuilder.create(item.label)
                                            .withTypeText(item.detail)
                                            .withPresentableText(item.label)
                            );
                        }
                    }
                }
        );
    }
}

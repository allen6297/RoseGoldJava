package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.intellij.lexer.Lexer;
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer;
import com.intellij.psi.impl.cache.impl.id.LexerBasedIdIndexer;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldIdIndexer extends LexerBasedIdIndexer {

    @Override
    public @NotNull Lexer createLexer(@NotNull OccurrenceConsumer consumer) {
        return new RoseGoldFilterLexer(new RoseGoldLexer(), consumer);
    }
}

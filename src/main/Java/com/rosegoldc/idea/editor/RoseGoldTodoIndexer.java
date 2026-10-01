package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.intellij.lexer.Lexer;
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer;
import com.intellij.psi.impl.cache.impl.todo.LexerBasedTodoIndexer;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldTodoIndexer extends LexerBasedTodoIndexer {

    @Override
    public @NotNull Lexer createLexer(@NotNull OccurrenceConsumer consumer) {
        return new RoseGoldFilterLexer(new RoseGoldLexer(), consumer);
    }
}

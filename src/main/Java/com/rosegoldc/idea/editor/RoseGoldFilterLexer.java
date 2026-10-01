package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.lexer.Lexer;
import com.intellij.psi.impl.cache.impl.BaseFilterLexer;
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.tree.IElementType;

final class RoseGoldFilterLexer extends BaseFilterLexer {

    RoseGoldFilterLexer(Lexer original, OccurrenceConsumer consumer) {
        super(original, consumer);
    }

    @Override
    public void advance() {
        IElementType type = getDelegate().getTokenType();
        if (type == RoseGoldTokenTypes.COMMENT) {
            scanWordsInToken(UsageSearchContext.IN_COMMENTS, false, false);
            advanceTodoItemCountsInToken();
        } else if (type == RoseGoldTokenTypes.STRING) {
            scanWordsInToken(UsageSearchContext.IN_STRINGS, false, false);
        } else if (type == RoseGoldTokenTypes.IDENTIFIER || type == RoseGoldTokenTypes.TYPE) {
            scanWordsInToken(UsageSearchContext.IN_CODE, false, false);
        }
        getDelegate().advance();
    }
}

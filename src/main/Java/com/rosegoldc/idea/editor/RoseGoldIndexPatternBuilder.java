package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.lexer.Lexer;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.search.IndexPatternBuilder;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldIndexPatternBuilder implements IndexPatternBuilder {

    private static final TokenSet COMMENTS = TokenSet.create(RoseGoldTokenTypes.COMMENT);

    @Override
    public @Nullable Lexer getIndexingLexer(@NotNull PsiFile file) {
        return file instanceof RoseGoldFile ? new RoseGoldLexer() : null;
    }

    @Override
    public @Nullable TokenSet getCommentTokenSet(@NotNull PsiFile file) {
        return file instanceof RoseGoldFile ? COMMENTS : null;
    }

    @Override
    public int getCommentStartDelta(IElementType tokenType) {
        return 0;
    }

    @Override
    public int getCommentStartDelta(@NotNull IElementType tokenType, @NotNull CharSequence tokenText) {
        if (tokenType != RoseGoldTokenTypes.COMMENT || tokenText.isEmpty()) {
            return 0;
        }
        if (startsWith(tokenText, "//") || startsWith(tokenText, "/#")) {
            return 2;
        }
        if (tokenText.charAt(0) == '#') {
            return 1;
        }
        return 0;
    }

    @Override
    public int getCommentEndDelta(IElementType tokenType) {
        return 0;
    }

    @Override
    public @NotNull String getCharsAllowedInContinuationPrefix(@NotNull IElementType tokenType) {
        return tokenType == RoseGoldTokenTypes.COMMENT ? "#/" : "";
    }

    private static boolean startsWith(CharSequence text, String prefix) {
        if (text.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}

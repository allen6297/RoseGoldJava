package com.rosegoldc.idea.psi;

import com.rosegoldc.idea.RoseGold;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldParserDefinition implements ParserDefinition {

    public static final IFileElementType FILE = new IFileElementType(RoseGold.INSTANCE);
    private static final TokenSet COMMENTS = TokenSet.create(RoseGoldTokenTypes.COMMENT);
    private static final TokenSet STRINGS = TokenSet.create(RoseGoldTokenTypes.STRING);
    private static final TokenSet WHITESPACE = TokenSet.create(TokenType.WHITE_SPACE);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new RoseGoldLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new RoseGoldPsiParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return WHITESPACE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return STRINGS;
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new RoseGoldFile(viewProvider);
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        IElementType type = node.getElementType();
        if (type == RoseGoldTypes.FN || type == RoseGoldTypes.STRUCT || type == RoseGoldTypes.CLASS
                || type == RoseGoldTypes.TRAIT || type == RoseGoldTypes.ENUM || type == RoseGoldTypes.IMPL
                || type == RoseGoldTypes.MOD || type == RoseGoldTypes.SIGNAL || type == RoseGoldTypes.IMPORT) {
            return new RoseGoldDecl(node);
        }
        throw new UnsupportedOperationException(String.valueOf(type));
    }
}

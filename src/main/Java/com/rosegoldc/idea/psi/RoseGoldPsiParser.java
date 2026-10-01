package com.rosegoldc.idea.psi;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldPsiParser implements PsiParser {

    @Override
    public @NotNull com.intellij.lang.ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        PsiBuilder.Marker file = builder.mark();
        while (!builder.eof()) {
            if (!parseItem(builder)) {
                builder.advanceLexer();
            }
        }
        file.done(root);
        return builder.getTreeBuilt();
    }

    private static boolean parseItem(@NotNull PsiBuilder builder) {
        PsiBuilder.Marker item = builder.mark();
        skipMods(builder);
        IElementType kind = itemKind(builder);
        if (kind == null) {
            item.rollbackTo();
            return false;
        }
        if (kind == RoseGoldTypes.IMPORT || kind == RoseGoldTypes.SIGNAL) {
            skipToSemiOrBrace(builder, false);
            if (isText(builder, ";")) {
                builder.advanceLexer();
            }
            item.done(kind);
            return true;
        }
        builder.advanceLexer();
        if (isName(builder)) {
            builder.advanceLexer();
        }
        skipHeader(builder);
        if (builder.getTokenType() == RoseGoldTokenTypes.LBRACE) {
            parseMemberBlock(builder, kind != RoseGoldTypes.FN);
        } else if (isText(builder, ";")) {
            builder.advanceLexer();
        }
        item.done(kind);
        return true;
    }

    private static void skipMods(@NotNull PsiBuilder builder) {
        while (!builder.eof()) {
            IElementType type = builder.getTokenType();
            if (type == RoseGoldTokenTypes.DECORATOR) {
                builder.advanceLexer();
                continue;
            }
            if (type == RoseGoldTokenTypes.KEYWORD && isMod(builder.getTokenText())) {
                builder.advanceLexer();
                continue;
            }
            break;
        }
    }

    @Nullable
    private static IElementType itemKind(@NotNull PsiBuilder builder) {
        if (builder.getTokenType() != RoseGoldTokenTypes.KEYWORD) {
            return null;
        }
        String word = builder.getTokenText();
        if ("fn".equals(word)) {
            return RoseGoldTypes.FN;
        }
        if ("struct".equals(word) || "data".equals(word)) {
            return RoseGoldTypes.STRUCT;
        }
        if ("class".equals(word)) {
            return RoseGoldTypes.CLASS;
        }
        if ("trait".equals(word)) {
            return RoseGoldTypes.TRAIT;
        }
        if ("enum".equals(word)) {
            return RoseGoldTypes.ENUM;
        }
        if ("impl".equals(word)) {
            return RoseGoldTypes.IMPL;
        }
        if ("mod".equals(word)) {
            return RoseGoldTypes.MOD;
        }
        if ("signal".equals(word)) {
            return RoseGoldTypes.SIGNAL;
        }
        if ("import".equals(word) || "from".equals(word)) {
            return RoseGoldTypes.IMPORT;
        }
        return null;
    }

    private static void skipHeader(@NotNull PsiBuilder builder) {
        int paren = 0;
        int brack = 0;
        while (!builder.eof()) {
            IElementType type = builder.getTokenType();
            if (paren == 0 && brack == 0) {
                if (type == RoseGoldTokenTypes.LBRACE || isText(builder, ";")) {
                    return;
                }
            }
            if (type == RoseGoldTokenTypes.LPAREN) {
                paren++;
            } else if (type == RoseGoldTokenTypes.RPAREN && paren > 0) {
                paren--;
            } else if (type == RoseGoldTokenTypes.LBRACKET) {
                brack++;
            } else if (type == RoseGoldTokenTypes.RBRACKET && brack > 0) {
                brack--;
            }
            builder.advanceLexer();
        }
    }

    private static void skipToSemiOrBrace(@NotNull PsiBuilder builder, boolean stopAtBrace) {
        int paren = 0;
        int brack = 0;
        while (!builder.eof()) {
            IElementType type = builder.getTokenType();
            if (paren == 0 && brack == 0) {
                if (isText(builder, ";") || (stopAtBrace && type == RoseGoldTokenTypes.LBRACE)) {
                    return;
                }
            }
            if (type == RoseGoldTokenTypes.LPAREN) {
                paren++;
            } else if (type == RoseGoldTokenTypes.RPAREN && paren > 0) {
                paren--;
            } else if (type == RoseGoldTokenTypes.LBRACKET) {
                brack++;
            } else if (type == RoseGoldTokenTypes.RBRACKET && brack > 0) {
                brack--;
            }
            builder.advanceLexer();
        }
    }

    private static void parseMemberBlock(@NotNull PsiBuilder builder, boolean nestedItems) {
        if (builder.getTokenType() != RoseGoldTokenTypes.LBRACE) {
            return;
        }
        builder.advanceLexer();
        while (!builder.eof() && builder.getTokenType() != RoseGoldTokenTypes.RBRACE) {
            if (nestedItems && parseItem(builder)) {
                continue;
            }
            if (builder.getTokenType() == RoseGoldTokenTypes.LBRACE) {
                parseBalancedBrace(builder);
            } else {
                builder.advanceLexer();
            }
        }
        if (builder.getTokenType() == RoseGoldTokenTypes.RBRACE) {
            builder.advanceLexer();
        } else {
            builder.error("missing '}'");
        }
    }

    private static void parseBalancedBrace(@NotNull PsiBuilder builder) {
        if (builder.getTokenType() != RoseGoldTokenTypes.LBRACE) {
            return;
        }
        builder.advanceLexer();
        while (!builder.eof() && builder.getTokenType() != RoseGoldTokenTypes.RBRACE) {
            if (builder.getTokenType() == RoseGoldTokenTypes.LBRACE) {
                parseBalancedBrace(builder);
            } else {
                builder.advanceLexer();
            }
        }
        if (builder.getTokenType() == RoseGoldTokenTypes.RBRACE) {
            builder.advanceLexer();
        }
    }

    private static boolean isName(@NotNull PsiBuilder builder) {
        IElementType type = builder.getTokenType();
        return type == RoseGoldTokenTypes.IDENTIFIER || type == RoseGoldTokenTypes.TYPE;
    }

    private static boolean isText(@NotNull PsiBuilder builder, @NotNull String text) {
        return text.equals(builder.getTokenText());
    }

    private static boolean isMod(@Nullable String word) {
        return "pub".equals(word) || "private".equals(word) || "protected".equals(word)
                || "abstract".equals(word) || "final".equals(word) || "async".equals(word);
    }
}

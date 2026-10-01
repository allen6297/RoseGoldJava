package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public final class RoseGoldSyntaxHighlighter extends SyntaxHighlighterBase {

    public static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TYPE = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey IDENTIFIER = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
    public static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey COMMENT = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey DECORATOR = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_DECORATOR", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey OPERATOR = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);
    public static final TextAttributesKey BRACES = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey PARENTHESES = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES);
    public static final TextAttributesKey BRACKETS = TextAttributesKey.createTextAttributesKey(
            "ROSEGOLD_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);

    private static final Map<IElementType, TextAttributesKey> KEYS = new HashMap<>();

    static {
        KEYS.put(RoseGoldTokenTypes.KEYWORD, KEYWORD);
        KEYS.put(RoseGoldTokenTypes.TYPE, TYPE);
        KEYS.put(RoseGoldTokenTypes.IDENTIFIER, IDENTIFIER);
        KEYS.put(RoseGoldTokenTypes.NUMBER, NUMBER);
        KEYS.put(RoseGoldTokenTypes.STRING, STRING);
        KEYS.put(RoseGoldTokenTypes.COMMENT, COMMENT);
        KEYS.put(RoseGoldTokenTypes.DECORATOR, DECORATOR);
        KEYS.put(RoseGoldTokenTypes.OPERATOR, OPERATOR);
        KEYS.put(RoseGoldTokenTypes.LBRACE, BRACES);
        KEYS.put(RoseGoldTokenTypes.RBRACE, BRACES);
        KEYS.put(RoseGoldTokenTypes.LPAREN, PARENTHESES);
        KEYS.put(RoseGoldTokenTypes.RPAREN, PARENTHESES);
        KEYS.put(RoseGoldTokenTypes.LBRACKET, BRACKETS);
        KEYS.put(RoseGoldTokenTypes.RBRACKET, BRACKETS);
    }

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new RoseGoldLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        TextAttributesKey key = KEYS.get(tokenType);
        return key == null ? TextAttributesKey.EMPTY_ARRAY : pack(key);
    }
}

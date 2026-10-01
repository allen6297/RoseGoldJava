package com.rosegoldc.idea.psi;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class RoseGoldLexer extends LexerBase {

    private static final Set<String> KEYWORDS = Set.of(
            "import", "from", "as",
            "if", "elif", "else", "while", "for", "in", "match", "switch",
            "return", "break", "continue", "pass",
            "spawn", "await", "try", "do", "throws", "throw", "catch",
            "struct", "data", "class", "trait", "extends", "enum", "mod", "impl",
            "fn", "var", "const", "signal",
            "pub", "private", "protected", "async", "abstract", "final",
            "self", "super"
    );

    private static final Set<String> TYPES = Set.of(
            "Void", "Bool", "Int", "Float", "String", "Str", "Array", "Map",
            "Range", "Option", "Result", "UUID", "Vec2", "Vec3", "Mutex",
            "Channel", "Task", "Fn"
    );

    private static final Set<String> CONSTANTS = Set.of("true", "false", "none");

    private CharSequence buffer = "";
    private int start;
    private int end;
    private int bufferEnd;
    private IElementType type;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.start = startOffset;
        this.end = startOffset;
        this.bufferEnd = endOffset;
        this.type = null;
        advance();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return type;
    }

    @Override
    public int getTokenStart() {
        return start;
    }

    @Override
    public int getTokenEnd() {
        return end;
    }

    @Override
    public void advance() {
        start = end;
        if (start >= bufferEnd) {
            type = null;
            return;
        }
        char c = charAt(start);
        if (isWhitespace(c)) {
            type = TokenType.WHITE_SPACE;
            end = start + 1;
            while (end < bufferEnd && isWhitespace(charAt(end))) {
                end++;
            }
            return;
        }
        if (c == '/' && peek(start + 1) == '#') {
            scanBlockComment();
            return;
        }
        if (c == '/' && peek(start + 1) == '/') {
            type = RoseGoldTokenTypes.COMMENT;
            end = lineEnd(start + 2);
            return;
        }
        if (c == '#') {
            type = RoseGoldTokenTypes.COMMENT;
            end = lineEnd(start + 1);
            return;
        }
        if (c == '@') {
            type = RoseGoldTokenTypes.DECORATOR;
            end = start + 1;
            while (end < bufferEnd && isIdentPart(charAt(end))) {
                end++;
            }
            return;
        }
        if (c == 'f' && peek(start + 1) == '"') {
            scanString(start + 1);
            return;
        }
        if (c == '"') {
            scanString(start);
            return;
        }
        if (Character.isDigit(c)) {
            type = RoseGoldTokenTypes.NUMBER;
            end = start + 1;
            while (end < bufferEnd && Character.isDigit(charAt(end))) {
                end++;
            }
            if (end < bufferEnd && charAt(end) == '.' && end + 1 < bufferEnd && Character.isDigit(charAt(end + 1))) {
                end++;
                while (end < bufferEnd && Character.isDigit(charAt(end))) {
                    end++;
                }
            }
            return;
        }
        if (isIdentStart(c)) {
            end = start + 1;
            while (end < bufferEnd && isIdentPart(charAt(end))) {
                end++;
            }
            String word = buffer.subSequence(start, end).toString();
            if (KEYWORDS.contains(word) || CONSTANTS.contains(word)) {
                type = RoseGoldTokenTypes.KEYWORD;
            } else if (TYPES.contains(word) || (Character.isUpperCase(word.charAt(0)) && word.length() > 1)) {
                type = RoseGoldTokenTypes.TYPE;
            } else {
                type = RoseGoldTokenTypes.IDENTIFIER;
            }
            return;
        }
        switch (c) {
            case '{' -> {
                type = RoseGoldTokenTypes.LBRACE;
                end = start + 1;
            }
            case '}' -> {
                type = RoseGoldTokenTypes.RBRACE;
                end = start + 1;
            }
            case '(' -> {
                type = RoseGoldTokenTypes.LPAREN;
                end = start + 1;
            }
            case ')' -> {
                type = RoseGoldTokenTypes.RPAREN;
                end = start + 1;
            }
            case '[' -> {
                type = RoseGoldTokenTypes.LBRACKET;
                end = start + 1;
            }
            case ']' -> {
                type = RoseGoldTokenTypes.RBRACKET;
                end = start + 1;
            }
            default -> {
                type = RoseGoldTokenTypes.OPERATOR;
                end = start + 1;
                if (end < bufferEnd) {
                    char n = charAt(end);
                    if ((c == '-' && n == '>') || (c == '=' && n == '=') || (c == '!' && n == '=')
                            || (c == '<' && n == '=') || (c == '>' && n == '=') || (c == '&' && n == '&')
                            || (c == '|' && n == '|') || (c == ':' && n == ':')) {
                        end++;
                    }
                }
            }
        }
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }

    private void scanBlockComment() {
        type = RoseGoldTokenTypes.COMMENT;
        end = start + 2;
        while (end < bufferEnd) {
            if (charAt(end) == '#' && peek(end + 1) == '/') {
                end += 2;
                return;
            }
            end++;
        }
    }

    private void scanString(int quoteAt) {
        type = RoseGoldTokenTypes.STRING;
        end = quoteAt + 1;
        while (end < bufferEnd) {
            char c = charAt(end);
            if (c == '\\' && end + 1 < bufferEnd) {
                end += 2;
                continue;
            }
            if (c == '"') {
                end++;
                return;
            }
            if (c == '\n') {
                return;
            }
            end++;
        }
    }

    private int lineEnd(int from) {
        int i = from;
        while (i < bufferEnd) {
            char c = charAt(i);
            if (c == '\n' || c == '\r') {
                break;
            }
            i++;
        }
        return i;
    }

    private char charAt(int i) {
        return buffer.charAt(i);
    }

    private char peek(int i) {
        return i < bufferEnd ? buffer.charAt(i) : 0;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}

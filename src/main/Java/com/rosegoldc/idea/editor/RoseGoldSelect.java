package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RoseGoldSelect {

    private static final Set<String> BLOCKS = Set.of(
            "if", "elif", "else", "while", "for", "match", "switch",
            "try", "catch", "do", "fn", "class", "struct", "data",
            "enum", "trait", "impl", "mod"
    );

    static List<TextRange> ranges(CharSequence text, int offset) {
        offset = Math.clamp(offset, 0, text.length());
        ArrayList<Tok> tokens = lex(text);
        LinkedHashSet<TextRange> out = new LinkedHashSet<>();
        int at = tokenAt(tokens, offset);
        if (at >= 0) {
            Tok tok = tokens.get(at);
            if (tok.type != TokenType.WHITE_SPACE && tok.type != RoseGoldTokenTypes.COMMENT) {
                add(out, tok.start, tok.end, offset);
                if (tok.type == RoseGoldTokenTypes.STRING) {
                    int innerStart = tok.text.startsWith("f\"") ? tok.start + 2 : tok.start + 1;
                    int innerEnd = tok.text.endsWith("\"") ? tok.end - 1 : tok.end;
                    if (innerEnd > innerStart) {
                        add(out, innerStart, innerEnd, offset);
                    }
                }
            }
            TextRange expr = growExpr(tokens, at);
            if (expr != null) {
                add(out, expr.getStartOffset(), expr.getEndOffset(), offset);
            }
        }
        addPairs(out, tokens, offset, RoseGoldTokenTypes.LPAREN, RoseGoldTokenTypes.RPAREN);
        addPairs(out, tokens, offset, RoseGoldTokenTypes.LBRACKET, RoseGoldTokenTypes.RBRACKET);
        addPairs(out, tokens, offset, RoseGoldTokenTypes.LBRACE, RoseGoldTokenTypes.RBRACE);
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.type != RoseGoldTokenTypes.KEYWORD || !BLOCKS.contains(tok.text)) {
                continue;
            }
            int lbrace = findOpen(tokens, i + 1, RoseGoldTokenTypes.LBRACE, RoseGoldTokenTypes.RBRACE, true);
            if (lbrace < 0) {
                continue;
            }
            int rbrace = match(tokens, lbrace, RoseGoldTokenTypes.LBRACE, RoseGoldTokenTypes.RBRACE);
            if (rbrace < 0) {
                continue;
            }
            add(out, tok.start, tokens.get(rbrace).end, offset);
        }
        List<TextRange> list = new ArrayList<>(out);
        list.sort((a, b) -> Integer.compare(a.getLength(), b.getLength()));
        return list;
    }

    static TextRange innermostBraces(CharSequence text, int offset) {
        TextRange best = null;
        for (TextRange range : ranges(text, offset)) {
            CharSequence slice = range.subSequence(text);
            if (slice.length() >= 2
                    && slice.charAt(0) == '{'
                    && slice.charAt(slice.length() - 1) == '}') {
                if (best == null || range.getLength() < best.getLength()) {
                    best = range;
                }
            }
        }
        return best;
    }

    private static TextRange growExpr(ArrayList<Tok> tokens, int at) {
        int left = at;
        int right = at;
        while (true) {
            int prev = skipWs(tokens, left - 1, -1);
            if (prev < 0 || !isDot(tokens.get(prev))) {
                break;
            }
            int name = skipWs(tokens, prev - 1, -1);
            if (name < 0 || !isName(tokens.get(name))) {
                break;
            }
            left = name;
        }
        while (true) {
            int next = skipWs(tokens, right + 1, 1);
            if (next < 0) {
                break;
            }
            Tok tok = tokens.get(next);
            if (tok.type == RoseGoldTokenTypes.LPAREN) {
                int close = match(tokens, next, RoseGoldTokenTypes.LPAREN, RoseGoldTokenTypes.RPAREN);
                if (close < 0) {
                    break;
                }
                right = close;
                continue;
            }
            if (tok.type == RoseGoldTokenTypes.LBRACKET) {
                int close = match(tokens, next, RoseGoldTokenTypes.LBRACKET, RoseGoldTokenTypes.RBRACKET);
                if (close < 0) {
                    break;
                }
                right = close;
                continue;
            }
            if (isDot(tok)) {
                int name = skipWs(tokens, next + 1, 1);
                if (name < 0 || !isName(tokens.get(name))) {
                    break;
                }
                right = name;
                continue;
            }
            break;
        }
        if (left == right && !isName(tokens.get(at)) && tokens.get(at).type != RoseGoldTokenTypes.NUMBER) {
            return null;
        }
        return TextRange.create(tokens.get(left).start, tokens.get(right).end);
    }

    private static void addPairs(
            LinkedHashSet<TextRange> out,
            ArrayList<Tok> tokens,
            int offset,
            IElementType open,
            IElementType close
    ) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type != open) {
                continue;
            }
            int j = match(tokens, i, open, close);
            if (j < 0) {
                continue;
            }
            add(out, tokens.get(i).start, tokens.get(j).end, offset);
            int innerStart = tokens.get(i).end;
            int innerEnd = tokens.get(j).start;
            if (innerEnd > innerStart) {
                add(out, innerStart, innerEnd, offset);
            }
        }
    }

    private static int findOpen(ArrayList<Tok> tokens, int from, IElementType open, IElementType close, boolean stopOnSemi) {
        int paren = 0;
        int bracket = 0;
        for (int i = from; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            IElementType type = tok.type;
            if (type == TokenType.WHITE_SPACE || type == RoseGoldTokenTypes.COMMENT) {
                continue;
            }
            if (type == RoseGoldTokenTypes.LPAREN) {
                paren++;
                continue;
            }
            if (type == RoseGoldTokenTypes.RPAREN) {
                paren = Math.max(0, paren - 1);
                continue;
            }
            if (type == RoseGoldTokenTypes.LBRACKET) {
                bracket++;
                continue;
            }
            if (type == RoseGoldTokenTypes.RBRACKET) {
                bracket = Math.max(0, bracket - 1);
                continue;
            }
            if (paren > 0 || bracket > 0) {
                continue;
            }
            if (type == open) {
                return i;
            }
            if (type == close) {
                return -1;
            }
            if (stopOnSemi && type == RoseGoldTokenTypes.OPERATOR && ";".equals(tok.text)) {
                return -1;
            }
        }
        return -1;
    }

    private static int match(ArrayList<Tok> tokens, int openIdx, IElementType open, IElementType close) {
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            IElementType type = tokens.get(i).type;
            if (type == open) {
                depth++;
            } else if (type == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int tokenAt(ArrayList<Tok> tokens, int offset) {
        int found = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.start <= offset && offset <= tok.end) {
                if (tok.type != TokenType.WHITE_SPACE) {
                    return i;
                }
                found = i;
            }
            if (tok.start > offset) {
                break;
            }
        }
        return found;
    }

    private static int skipWs(ArrayList<Tok> tokens, int i, int dir) {
        while (i >= 0 && i < tokens.size()) {
            IElementType type = tokens.get(i).type;
            if (type != TokenType.WHITE_SPACE && type != RoseGoldTokenTypes.COMMENT) {
                return i;
            }
            i += dir;
        }
        return -1;
    }

    private static boolean isName(Tok tok) {
        return tok.type == RoseGoldTokenTypes.IDENTIFIER || tok.type == RoseGoldTokenTypes.TYPE;
    }

    private static boolean isDot(Tok tok) {
        return tok.type == RoseGoldTokenTypes.OPERATOR && ".".equals(tok.text);
    }

    private static void add(LinkedHashSet<TextRange> out, int start, int end, int offset) {
        if (end > start && start <= offset && offset <= end) {
            out.add(TextRange.create(start, end));
        }
    }

    private static ArrayList<Tok> lex(CharSequence text) {
        ArrayList<Tok> tokens = new ArrayList<>();
        RoseGoldLexer lexer = new RoseGoldLexer();
        lexer.start(text, 0, text.length());
        while (lexer.getTokenType() != null) {
            tokens.add(new Tok(
                    lexer.getTokenType(),
                    lexer.getTokenStart(),
                    lexer.getTokenEnd(),
                    text.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString()
            ));
            lexer.advance();
        }
        return tokens;
    }

    private record Tok(IElementType type, int start, int end, String text) {
    }
}

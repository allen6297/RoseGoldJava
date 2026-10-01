package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.codeInsight.editorActions.moveUpDown.LineRange;
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoseGoldStatementMover extends StatementUpDownMover {

    private static final Set<String> BLOCKS = Set.of(
            "if", "elif", "else", "while", "for", "match", "switch",
            "try", "catch", "do", "fn", "class", "struct", "data",
            "enum", "trait", "impl", "mod"
    );
    private static final Set<String> IF_CHAIN = Set.of("elif", "else");

    @Override
    public boolean checkAvailable(
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @NotNull MoveInfo info,
            boolean down
    ) {
        if (!(file instanceof RoseGoldFile)) {
            return false;
        }
        Document document = editor.getDocument();
        List<Stmt> stmts = collect(file.getText(), document);
        if (stmts.isEmpty()) {
            return false;
        }
        int line = editor.getCaretModel().getLogicalPosition().line;
        if (editor.getSelectionModel().hasSelection()) {
            line = editor.offsetToLogicalPosition(editor.getSelectionModel().getSelectionStart()).line;
        }
        Stmt current = innermost(stmts, line);
        if (current == null) {
            return false;
        }
        Stmt sibling = sibling(stmts, current, down);
        if (sibling == null) {
            if (current.endLine - current.startLine > 1) {
                return info.prohibitMove();
            }
            return false;
        }
        info.toMove = new LineRange(current.startLine, current.endLine);
        info.toMove2 = new LineRange(sibling.startLine, sibling.endLine);
        info.indentSource = false;
        info.indentTarget = false;
        return true;
    }

    private static @Nullable Stmt innermost(List<Stmt> stmts, int line) {
        Stmt best = null;
        for (Stmt stmt : stmts) {
            if (line < stmt.startLine || line >= stmt.endLine) {
                continue;
            }
            if (best == null
                    || stmt.endLine - stmt.startLine < best.endLine - best.startLine
                    || (stmt.endLine - stmt.startLine == best.endLine - best.startLine && stmt.depth > best.depth)) {
                best = stmt;
            }
        }
        return best;
    }

    private static @Nullable Stmt sibling(List<Stmt> stmts, Stmt current, boolean down) {
        Stmt parent = parentOf(stmts, current);
        Stmt best = null;
        for (Stmt stmt : stmts) {
            if (stmt.depth != current.depth || stmt.startOffset == current.startOffset) {
                continue;
            }
            if (!sameParent(parent, parentOf(stmts, stmt))) {
                continue;
            }
            if (down) {
                if (stmt.startLine < current.endLine) {
                    continue;
                }
                if (best == null || stmt.startLine < best.startLine) {
                    best = stmt;
                }
            } else {
                if (stmt.endLine > current.startLine) {
                    continue;
                }
                if (best == null || stmt.endLine > best.endLine) {
                    best = stmt;
                }
            }
        }
        return best;
    }

    private static boolean sameParent(@Nullable Stmt a, @Nullable Stmt b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.startOffset == b.startOffset && a.endOffset == b.endOffset && a.depth == b.depth;
    }

    private static @Nullable Stmt parentOf(List<Stmt> stmts, Stmt child) {
        Stmt best = null;
        for (Stmt stmt : stmts) {
            if (stmt.depth >= child.depth) {
                continue;
            }
            if (stmt.startOffset <= child.startOffset && stmt.endOffset >= child.endOffset) {
                if (best == null || stmt.depth > best.depth) {
                    best = stmt;
                }
            }
        }
        return best;
    }

    private static List<Stmt> collect(CharSequence text, Document document) {
        ArrayList<Tok> tokens = lex(text);
        List<Raw> blocks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.type != RoseGoldTokenTypes.KEYWORD || !BLOCKS.contains(tok.text)) {
                continue;
            }
            int lbrace = findLBrace(tokens, i + 1);
            if (lbrace < 0) {
                continue;
            }
            int rbrace = findRBrace(tokens, lbrace);
            if (rbrace < 0) {
                continue;
            }
            blocks.add(new Raw(tok.text, tok.start, tokens.get(rbrace).end, braceDepth(tokens, i), i, rbrace));
        }
        Set<Integer> absorbed = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            Raw block = blocks.get(i);
            if (!"if".equals(block.keyword) && !"try".equals(block.keyword)) {
                continue;
            }
            int end = block.end;
            for (int j = 0; j < blocks.size(); j++) {
                if (j == i || absorbed.contains(j)) {
                    continue;
                }
                Raw next = blocks.get(j);
                if (next.depth != block.depth || next.start < end) {
                    continue;
                }
                boolean chain = "if".equals(block.keyword) && IF_CHAIN.contains(next.keyword)
                        || "try".equals(block.keyword) && "catch".equals(next.keyword);
                if (!chain || !onlyTrivia(text, end, next.start)) {
                    continue;
                }
                end = next.end;
                absorbed.add(j);
                blocks.set(i, new Raw(block.keyword, block.start, end, block.depth, block.kwIdx, next.rbraceIdx));
            }
        }
        List<Stmt> stmts = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (absorbed.contains(i)) {
                continue;
            }
            stmts.add(toStmt(blocks.get(i).start, blocks.get(i).end, blocks.get(i).depth, document));
        }
        addSemis(tokens, document, stmts);
        stmts.removeIf(stmt -> stmt == null || stmt.endLine <= stmt.startLine);
        return stmts;
    }

    private static void addSemis(ArrayList<Tok> tokens, Document document, List<Stmt> stmts) {
        int depth = 0;
        int paren = 0;
        int bracket = 0;
        int stmtStart = -1;
        int stmtDepth = 0;
        for (Tok tok : tokens) {
            IElementType type = tok.type;
            if (type == TokenType.WHITE_SPACE || type == RoseGoldTokenTypes.COMMENT) {
                continue;
            }
            if (stmtStart < 0) {
                stmtStart = tok.start;
                stmtDepth = depth;
            }
            if (type == RoseGoldTokenTypes.LPAREN) {
                paren++;
            } else if (type == RoseGoldTokenTypes.RPAREN) {
                paren = Math.max(0, paren - 1);
            } else if (type == RoseGoldTokenTypes.LBRACKET) {
                bracket++;
            } else if (type == RoseGoldTokenTypes.RBRACKET) {
                bracket = Math.max(0, bracket - 1);
            } else if (type == RoseGoldTokenTypes.LBRACE) {
                depth++;
            } else if (type == RoseGoldTokenTypes.RBRACE) {
                depth = Math.max(0, depth - 1);
                stmtStart = -1;
            } else if (type == RoseGoldTokenTypes.OPERATOR && ";".equals(tok.text)
                    && paren == 0 && bracket == 0) {
                if (stmtStart >= 0 && !covered(stmts, stmtStart, tok.end)) {
                    stmts.add(toStmt(stmtStart, tok.end, stmtDepth, document));
                }
                stmtStart = -1;
            }
        }
    }

    private static boolean covered(List<Stmt> stmts, int start, int end) {
        for (Stmt stmt : stmts) {
            if (stmt.startOffset == start && stmt.endOffset == end) {
                return true;
            }
        }
        return false;
    }

    private static Stmt toStmt(int start, int end, int depth, Document document) {
        int startLine = document.getLineNumber(Math.max(0, start));
        int last = Math.max(start, end - 1);
        int endLine = document.getLineNumber(Math.min(last, document.getTextLength() - 1)) + 1;
        endLine = Math.min(endLine, document.getLineCount());
        return new Stmt(startLine, endLine, depth, start, end);
    }

    private static int braceDepth(ArrayList<Tok> tokens, int before) {
        int depth = 0;
        for (int i = 0; i < before; i++) {
            IElementType type = tokens.get(i).type;
            if (type == RoseGoldTokenTypes.LBRACE) {
                depth++;
            } else if (type == RoseGoldTokenTypes.RBRACE) {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth;
    }

    private static boolean onlyTrivia(CharSequence text, int from, int to) {
        for (int i = from; i < to && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c == '/' && i + 1 < to && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '#')) {
                while (i < to && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '#') {
                while (i < to && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            return false;
        }
        return true;
    }

    private static int findLBrace(ArrayList<Tok> tokens, int from) {
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
            if (type == RoseGoldTokenTypes.LBRACE) {
                return i;
            }
            if (type == RoseGoldTokenTypes.RBRACE || type == RoseGoldTokenTypes.OPERATOR && ";".equals(tok.text)) {
                return -1;
            }
        }
        return -1;
    }

    private static int findRBrace(ArrayList<Tok> tokens, int lbraceIdx) {
        int depth = 0;
        for (int i = lbraceIdx; i < tokens.size(); i++) {
            IElementType type = tokens.get(i).type;
            if (type == RoseGoldTokenTypes.LBRACE) {
                depth++;
            } else if (type == RoseGoldTokenTypes.RBRACE) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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

    private record Stmt(int startLine, int endLine, int depth, int startOffset, int endOffset) {
    }

    private record Raw(String keyword, int start, int end, int depth, int kwIdx, int rbraceIdx) {
    }

    private record Tok(IElementType type, int start, int end, String text) {
    }
}

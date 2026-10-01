package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.unwrap.UnwrapDescriptor;
import com.intellij.codeInsight.unwrap.Unwrapper;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RoseGoldUnwrapDescriptor implements UnwrapDescriptor {

    private static final Set<String> KEYWORDS = Set.of(
            "if", "elif", "else", "while", "for", "try", "catch", "match", "switch", "do"
    );

    @Override
    public @NotNull List<Pair<PsiElement, Unwrapper>> collectUnwrappers(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        if (!(file instanceof RoseGoldFile)) {
            return Collections.emptyList();
        }
        int caret = editor.getCaretModel().getOffset();
        List<Block> blocks = blocksContaining(file.getText(), caret);
        List<Pair<PsiElement, Unwrapper>> out = new ArrayList<>();
        for (Block block : blocks) {
            out.add(Pair.create(file, new BlockUnwrapper(block)));
        }
        return out;
    }

    @Override
    public boolean showOptionsDialog() {
        return true;
    }

    @Override
    public boolean shouldTryToRestoreCaretPosition() {
        return true;
    }

    static List<Block> blocksContaining(CharSequence text, int caret) {
        ArrayList<Tok> tokens = lex(text);
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.type != RoseGoldTokenTypes.KEYWORD || !KEYWORDS.contains(tok.text)) {
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
            int start = tok.start;
            int end = tokens.get(rbrace).end;
            if (caret < start || caret > end) {
                continue;
            }
            blocks.add(new Block(tok.text, start, tokens.get(lbrace).start, tokens.get(lbrace).end, end));
        }
        blocks.sort((a, b) -> Integer.compare(a.rbraceEnd - a.keywordStart, b.rbraceEnd - b.keywordStart));
        return blocks;
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
            if (type == RoseGoldTokenTypes.RBRACE) {
                return -1;
            }
            if (type == RoseGoldTokenTypes.OPERATOR && ";".equals(tok.text)) {
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

    record Block(String keyword, int keywordStart, int lbraceStart, int lbraceEnd, int rbraceEnd) {
    }

    private record Tok(IElementType type, int start, int end, String text) {
    }

    private static final class BlockUnwrapper implements Unwrapper {
        private final Block block;

        private BlockUnwrapper(Block block) {
            this.block = block;
        }

        @Override
        public boolean isApplicableTo(@NotNull PsiElement e) {
            return e instanceof RoseGoldFile;
        }

        @Override
        public void collectElementsToIgnore(@NotNull PsiElement element, @NotNull Set<PsiElement> toIgnore) {
        }

        @Override
        public @NotNull String getDescription(@NotNull PsiElement e) {
            return "Unwrap " + block.keyword;
        }

        @Override
        public PsiElement collectAffectedElements(@NotNull PsiElement e, @NotNull List<? super PsiElement> toExtract) {
            return e;
        }

        @Override
        public @NotNull List<PsiElement> unwrap(@NotNull Editor editor, @NotNull PsiElement element)
                throws IncorrectOperationException {
            Document document = editor.getDocument();
            CharSequence text = document.getCharsSequence();
            int indentSize = 4;
            PsiFile file = element.getContainingFile();
            if (file != null) {
                indentSize = Math.max(CodeStyle.getIndentSize(file), 1);
            }
            int from;
            int lineStart = document.getLineStartOffset(document.getLineNumber(block.keywordStart));
            boolean atLineStart = isBlank(text, lineStart, block.keywordStart);
            if (atLineStart) {
                from = lineStart;
            } else {
                from = block.keywordStart;
            }
            int to = extendPastClosingBrace(text, block.rbraceEnd);
            String inner = text.subSequence(block.lbraceEnd, block.rbraceEnd - 1).toString();
            String body = unwrapBody(inner, indentSize);
            if (!atLineStart) {
                body = stripLeadingNewlines(body);
            }
            document.replaceString(from, to, body);
            int caret = Math.min(from + Math.max(body.length(), 0), document.getTextLength());
            editor.getCaretModel().moveToOffset(caret);
            return Collections.emptyList();
        }
    }

    private static int extendPastClosingBrace(CharSequence text, int rbraceEnd) {
        int lineEnd = rbraceEnd;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
            if (text.charAt(lineEnd) != ' ' && text.charAt(lineEnd) != '\t') {
                return rbraceEnd;
            }
            lineEnd++;
        }
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\r') {
            lineEnd++;
        }
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\n') {
            lineEnd++;
        }
        return lineEnd;
    }

    static String unwrapBody(String inner, int indentSize) {
        String s = stripLeadingNewlines(inner);
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
            if (end > 0 && s.charAt(end - 1) == '\r') {
                end--;
            }
        }
        s = s.substring(0, end);
        String pad = " ".repeat(Math.max(indentSize, 1));
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(unindentLine(lines[i], pad));
        }
        return out.toString();
    }

    private static String stripLeadingNewlines(String s) {
        if (s.startsWith("\r\n")) {
            return s.substring(2);
        }
        if (s.startsWith("\n") || s.startsWith("\r")) {
            return s.substring(1);
        }
        return s;
    }

    private static String unindentLine(String line, String pad) {
        if (line.startsWith(pad)) {
            return line.substring(pad.length());
        }
        if (line.startsWith("\t")) {
            return line.substring(1);
        }
        int i = 0;
        while (i < line.length() && i < pad.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private static boolean isBlank(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }
}

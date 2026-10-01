package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RoseGoldPostfixTemplateProvider implements PostfixTemplateProvider {

    @Override
    public @NotNull Set<PostfixTemplate> getTemplates() {
        Set<PostfixTemplate> templates = new LinkedHashSet<>();
        templates.add(new Wrap(this, "if", "if (expr) {}", "if (", ") {"));
        templates.add(new Wrap(this, "while", "while (expr) {}", "while (", ") {"));
        templates.add(new Wrap(this, "match", "match expr {}", "match ", " {"));
        templates.add(new Wrap(this, "switch", "switch expr {}", "switch ", " {"));
        templates.add(new Wrap(this, "for", "for x in expr {}", "for x in ", " {"));
        templates.add(new Wrap(this, "try", "try { expr }", "try {", ""));
        templates.add(new Inline(this, "not", "!(expr)", "!(", ")"));
        templates.add(new Inline(this, "paren", "(expr)", "(", ")"));
        templates.add(new Inline(this, "return", "return expr;", "return ", ";"));
        templates.add(new Inline(this, "await", "await expr", "await ", ""));
        templates.add(new Inline(this, "var", "var name = expr;", "var name = ", ";"));
        return templates;
    }

    @Override
    public boolean isTerminalSymbol(char currentChar) {
        return currentChar == '.';
    }

    @Override
    public void preExpand(@NotNull PsiFile file, @NotNull Editor editor) {
    }

    @Override
    public void afterExpand(@NotNull PsiFile file, @NotNull Editor editor) {
    }

    @Override
    public @NotNull PsiFile preCheck(@NotNull PsiFile copyFile, @NotNull Editor realEditor, int currentOffset) {
        return copyFile;
    }

    static @Nullable TextRange expressionBefore(@NotNull CharSequence text, int offset) {
        offset = Math.clamp(offset, 0, text.length());
        ArrayList<Tok> tokens = lex(text, offset);
        if (tokens.isEmpty()) {
            return null;
        }
        Walk walk = new Walk(tokens);
        int i = walk.skipPrimary(tokens.size() - 1);
        if (i == Walk.FAIL) {
            return null;
        }
        while (true) {
            int j = walk.skipWs(i);
            if (j < 0 || !walk.isDot(tokens.get(j))) {
                break;
            }
            int before = walk.skipPrimary(j - 1);
            if (before == Walk.FAIL) {
                break;
            }
            i = before;
        }
        if (walk.exprStart >= offset) {
            return null;
        }
        return TextRange.create(walk.exprStart, offset);
    }

    private static ArrayList<Tok> lex(CharSequence text, int offset) {
        ArrayList<Tok> tokens = new ArrayList<>();
        RoseGoldLexer lexer = new RoseGoldLexer();
        lexer.start(text, 0, offset);
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

    private static final class Walk {
        static final int FAIL = Integer.MIN_VALUE;
        private static final Set<String> ATOMS = Set.of("true", "false", "none", "self");

        private final ArrayList<Tok> tokens;
        int exprStart = Integer.MAX_VALUE;

        private Walk(ArrayList<Tok> tokens) {
            this.tokens = tokens;
        }

        int skipWs(int i) {
            while (i >= 0 && tokens.get(i).type == TokenType.WHITE_SPACE) {
                i--;
            }
            return i;
        }

        boolean isDot(Tok tok) {
            return tok.type == RoseGoldTokenTypes.OPERATOR && ".".equals(tok.text);
        }

        int skipPrimary(int i) {
            i = skipWs(i);
            if (i < 0) {
                return FAIL;
            }
            Tok t = tokens.get(i);
            if (t.type == RoseGoldTokenTypes.RPAREN) {
                return skipCallOrGroup(i, RoseGoldTokenTypes.LPAREN, RoseGoldTokenTypes.RPAREN);
            }
            if (t.type == RoseGoldTokenTypes.RBRACKET) {
                return skipCallOrGroup(i, RoseGoldTokenTypes.LBRACKET, RoseGoldTokenTypes.RBRACKET);
            }
            if (isAtom(t)) {
                cover(i);
                return i - 1;
            }
            return FAIL;
        }

        private int skipCallOrGroup(int closeIdx, IElementType open, IElementType close) {
            int openIdx = findOpen(closeIdx, open, close);
            if (openIdx < 0) {
                return FAIL;
            }
            cover(openIdx);
            cover(closeIdx);
            int j = skipWs(openIdx - 1);
            if (j >= 0 && isName(tokens.get(j))) {
                cover(j);
                return j - 1;
            }
            return openIdx - 1;
        }

        private int findOpen(int closeIdx, IElementType open, IElementType close) {
            int depth = 1;
            for (int i = closeIdx - 1; i >= 0; i--) {
                IElementType type = tokens.get(i).type;
                if (type == close) {
                    depth++;
                } else if (type == open) {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private void cover(int idx) {
            exprStart = Math.min(exprStart, tokens.get(idx).start);
        }

        private static boolean isName(Tok tok) {
            return tok.type == RoseGoldTokenTypes.IDENTIFIER || tok.type == RoseGoldTokenTypes.TYPE;
        }

        private static boolean isAtom(Tok tok) {
            if (isName(tok)
                    || tok.type == RoseGoldTokenTypes.NUMBER
                    || tok.type == RoseGoldTokenTypes.STRING) {
                return true;
            }
            return tok.type == RoseGoldTokenTypes.KEYWORD && ATOMS.contains(tok.text);
        }
    }

    private abstract static class Base extends PostfixTemplate {
        private Base(String name, String example, PostfixTemplateProvider provider) {
            super(null, name, example, provider);
        }

        @Override
        public boolean isApplicable(@NotNull PsiElement context, @NotNull Document copyDocument, int newOffset) {
            return expressionBefore(copyDocument.getCharsSequence(), newOffset) != null;
        }

        static @Nullable TextRange rangeAtCaret(Editor editor) {
            int offset = editor.getCaretModel().getOffset();
            return expressionBefore(editor.getDocument().getCharsSequence(), offset);
        }

        static String indentOf(Document document, int offset) {
            int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
            String line = document.getText(TextRange.create(lineStart, offset));
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            return line.substring(0, i);
        }

        static int indentSize(PsiElement context) {
            PsiFile file = context.getContainingFile();
            return file != null ? CodeStyle.getIndentSize(file) : 4;
        }
    }

    private static final class Wrap extends Base {
        private final String prefix;
        private final String afterExpr;

        private Wrap(PostfixTemplateProvider provider, String name, String example, String prefix, String afterExpr) {
            super(name, example, provider);
            this.prefix = prefix;
            this.afterExpr = afterExpr;
        }

        @Override
        public void expand(@NotNull PsiElement context, @NotNull Editor editor) {
            Document document = editor.getDocument();
            TextRange range = rangeAtCaret(editor);
            if (range == null) {
                return;
            }
            String expr = document.getText(range);
            String indent = indentOf(document, range.getStartOffset());
            String pad = " ".repeat(Math.max(indentSize(context), 1));
            String replacement;
            int caret;
            if ("try {".equals(prefix)) {
                String body = expr.endsWith(";") ? expr : expr + ";";
                replacement = prefix + "\n" + indent + pad + body + "\n" + indent + "}";
                caret = range.getStartOffset() + replacement.length() - (indent.length() + 1);
            } else {
                replacement = prefix + expr + afterExpr + "\n" + indent + pad + "\n" + indent + "}";
                caret = range.getStartOffset() + (prefix + expr + afterExpr + "\n" + indent + pad).length();
            }
            document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
            editor.getCaretModel().moveToOffset(caret);
            if ("for x in ".equals(prefix)) {
                int x = range.getStartOffset() + "for ".length();
                editor.getSelectionModel().setSelection(x, x + 1);
                editor.getCaretModel().moveToOffset(x + 1);
            }
        }
    }

    private static final class Inline extends Base {
        private final String prefix;
        private final String suffix;

        private Inline(PostfixTemplateProvider provider, String name, String example, String prefix, String suffix) {
            super(name, example, provider);
            this.prefix = prefix;
            this.suffix = suffix;
        }

        @Override
        public void expand(@NotNull PsiElement context, @NotNull Editor editor) {
            Document document = editor.getDocument();
            TextRange range = rangeAtCaret(editor);
            if (range == null) {
                return;
            }
            String expr = document.getText(range);
            String replacement = prefix + expr + suffix;
            document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
            if ("var name = ".equals(prefix)) {
                int name = range.getStartOffset() + "var ".length();
                editor.getSelectionModel().setSelection(name, name + 4);
                editor.getCaretModel().moveToOffset(name + 4);
            } else {
                editor.getCaretModel().moveToOffset(range.getStartOffset() + replacement.length());
            }
        }
    }
}

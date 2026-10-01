package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Set;

public final class RoseGoldSmartEnterProcessor extends SmartEnterProcessor {

    private static final Set<String> MODS = Set.of(
            "pub", "private", "protected", "async", "abstract", "final"
    );
    private static final Set<String> BLOCKS = Set.of(
            "if", "elif", "else", "while", "for", "match", "switch",
            "try", "catch", "do", "fn", "class", "struct", "data",
            "enum", "trait", "impl", "mod"
    );
    private static final Set<String> SEMI_DECLS = Set.of("signal", "import");
    private static final Set<String> CONTINUE_OPS = Set.of(
            "+", "-", "*", "/", "=", "==", "!=", "<", ">", "<=", ">=",
            "&&", "||", ",", ".", ":", "::", "->"
    );

    @Override
    public boolean process(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile psiFile) {
        if (!(psiFile instanceof RoseGoldFile)) {
            return false;
        }
        Document document = editor.getDocument();
        int caret = editor.getCaretModel().getOffset();
        CharSequence text = document.getCharsSequence();
        if (insideCompleteString(text, caret) || insideComment(text, caret)) {
            return false;
        }
        if (closeUnterminatedString(editor, document, caret)) {
            caret = editor.getCaretModel().getOffset();
        }
        if (closePairs(editor, document, caret)) {
            caret = editor.getCaretModel().getOffset();
        }
        int line = document.getLineNumber(Math.min(caret, document.getTextLength()));
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        ArrayList<Tok> tokens = lex(text, lineStart, lineEnd);
        Kind kind = classify(tokens);
        int insertAt = trimEnd(text, lineStart, lineEnd);
        int indentSize = Math.max(CodeStyle.getIndentSize(psiFile), 1);
        String indent = leading(text, lineStart, insertAt);
        String pad = " ".repeat(indentSize);

        if (kind == Kind.BLOCK) {
            Brace braces = bracesOnLine(tokens);
            if (braces == null) {
                document.insertString(insertAt, " {\n" + indent + pad + "\n" + indent + "}");
                editor.getCaretModel().moveToOffset(insertAt + (" {\n" + indent + pad).length());
                return true;
            }
            if (isBlank(text, braces.openEnd, braces.closeStart)) {
                String inner = "\n" + indent + pad + "\n" + indent;
                document.replaceString(braces.openEnd, braces.closeStart, inner);
                editor.getCaretModel().moveToOffset(braces.openEnd + ("\n" + indent + pad).length());
                return true;
            }
        }

        if (kind == Kind.SEMI || kind == Kind.STMT) {
            if (insertAt > lineStart && text.charAt(insertAt - 1) != ';') {
                document.insertString(insertAt, ";");
                insertAt++;
            }
            document.insertString(insertAt, "\n" + indent);
            editor.getCaretModel().moveToOffset(insertAt + 1 + indent.length());
            return true;
        }

        document.insertString(insertAt, "\n" + indent);
        editor.getCaretModel().moveToOffset(insertAt + 1 + indent.length());
        return true;
    }

    @Override
    public boolean processAfterCompletion(@NotNull Editor editor, @NotNull PsiFile psiFile) {
        return process(psiFile.getProject(), editor, psiFile);
    }

    private static boolean closeUnterminatedString(Editor editor, Document document, int caret) {
        CharSequence text = document.getCharsSequence();
        int lineStart = document.getLineStartOffset(document.getLineNumber(Math.min(caret, text.length())));
        ArrayList<Tok> tokens = lex(text, lineStart, caret);
        if (tokens.isEmpty()) {
            return false;
        }
        Tok last = tokens.getLast();
        if (last.type != RoseGoldTokenTypes.STRING) {
            return false;
        }
        if (last.end == caret && !last.text.endsWith("\"")) {
            document.insertString(caret, "\"");
            editor.getCaretModel().moveToOffset(caret + 1);
            return true;
        }
        return false;
    }

    private static boolean closePairs(Editor editor, Document document, int caret) {
        CharSequence text = document.getCharsSequence();
        int lineStart = document.getLineStartOffset(document.getLineNumber(Math.min(caret, text.length())));
        int lineEnd = document.getLineEndOffset(document.getLineNumber(Math.min(caret, text.length())));
        ArrayList<Tok> tokens = lex(text, lineStart, lineEnd);
        ArrayDeque<Character> stack = new ArrayDeque<>();
        for (Tok tok : tokens) {
            if (tok.start >= caret) {
                break;
            }
            applyPair(stack, tok, true);
        }
        for (Tok tok : tokens) {
            if (tok.end <= caret) {
                continue;
            }
            applyPair(stack, tok, false);
        }
        if (stack.isEmpty()) {
            return false;
        }
        StringBuilder closers = new StringBuilder();
        while (!stack.isEmpty()) {
            closers.append(stack.removeLast() == '(' ? ')' : ']');
        }
        document.insertString(caret, closers.toString());
        editor.getCaretModel().moveToOffset(caret + closers.length());
        return true;
    }

    private static void applyPair(ArrayDeque<Character> stack, Tok tok, boolean beforeCaret) {
        if (tok.type == RoseGoldTokenTypes.LPAREN) {
            if (beforeCaret) {
                stack.addLast('(');
            }
        } else if (tok.type == RoseGoldTokenTypes.LBRACKET) {
            if (beforeCaret) {
                stack.addLast('[');
            }
        } else if (tok.type == RoseGoldTokenTypes.RPAREN) {
            if (!stack.isEmpty() && stack.peekLast() == '(') {
                stack.removeLast();
            }
        } else if (tok.type == RoseGoldTokenTypes.RBRACKET) {
            if (!stack.isEmpty() && stack.peekLast() == '[') {
                stack.removeLast();
            }
        }
    }

    private static Kind classify(ArrayList<Tok> tokens) {
        boolean abstr = false;
        for (Tok tok : tokens) {
            if (tok.type == TokenType.WHITE_SPACE || tok.type == RoseGoldTokenTypes.COMMENT) {
                continue;
            }
            if (tok.type == RoseGoldTokenTypes.RBRACE || tok.type == RoseGoldTokenTypes.OPERATOR && "}".equals(tok.text)) {
                continue;
            }
            if (tok.type == RoseGoldTokenTypes.DECORATOR) {
                continue;
            }
            if (tok.type == RoseGoldTokenTypes.KEYWORD && MODS.contains(tok.text)) {
                if ("abstract".equals(tok.text)) {
                    abstr = true;
                }
                continue;
            }
            if (tok.type == RoseGoldTokenTypes.KEYWORD && SEMI_DECLS.contains(tok.text)) {
                return Kind.SEMI;
            }
            if (tok.type == RoseGoldTokenTypes.KEYWORD && BLOCKS.contains(tok.text)) {
                if (abstr && "fn".equals(tok.text)) {
                    return Kind.SEMI;
                }
                return Kind.BLOCK;
            }
            break;
        }
        if (tokens.isEmpty() || lastCode(tokens) == null) {
            return Kind.OTHER;
        }
        Tok last = lastCode(tokens);
        if (last.type == RoseGoldTokenTypes.OPERATOR && CONTINUE_OPS.contains(last.text)) {
            return Kind.OTHER;
        }
        if (last.type == RoseGoldTokenTypes.LBRACE || last.type == RoseGoldTokenTypes.RBRACE) {
            return Kind.OTHER;
        }
        if (last.type == RoseGoldTokenTypes.OPERATOR && ";".equals(last.text)) {
            return Kind.OTHER;
        }
        return Kind.STMT;
    }

    private static Brace bracesOnLine(ArrayList<Tok> tokens) {
        int open = -1;
        int close = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type == RoseGoldTokenTypes.LBRACE && open < 0) {
                open = i;
            }
            if (tokens.get(i).type == RoseGoldTokenTypes.RBRACE) {
                close = i;
            }
        }
        if (open < 0) {
            return null;
        }
        Tok o = tokens.get(open);
        int closeStart = close < 0 ? o.end : tokens.get(close).start;
        return new Brace(o.end, closeStart);
    }

    private static Tok lastCode(ArrayList<Tok> tokens) {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Tok tok = tokens.get(i);
            if (tok.type != TokenType.WHITE_SPACE && tok.type != RoseGoldTokenTypes.COMMENT) {
                return tok;
            }
        }
        return null;
    }

    private static boolean insideComment(CharSequence text, int caret) {
        if (caret <= 0) {
            return false;
        }
        ArrayList<Tok> tokens = lex(text, 0, Math.min(caret, text.length()));
        if (tokens.isEmpty()) {
            return false;
        }
        Tok last = tokens.getLast();
        return last.type == RoseGoldTokenTypes.COMMENT && last.start < caret && caret < last.end;
    }

    private static boolean insideCompleteString(CharSequence text, int caret) {
        if (caret <= 0) {
            return false;
        }
        int lineStart = caret;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        ArrayList<Tok> tokens = lex(text, lineStart, Math.min(caret, text.length()));
        if (tokens.isEmpty()) {
            return false;
        }
        Tok last = tokens.getLast();
        return last.type == RoseGoldTokenTypes.STRING
                && last.text.endsWith("\"")
                && last.start < caret
                && caret < last.end;
    }

    private static int trimEnd(CharSequence text, int lineStart, int lineEnd) {
        int i = lineEnd;
        while (i > lineStart && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) {
            i--;
        }
        return i;
    }

    private static String leading(CharSequence text, int start, int end) {
        int i = start;
        while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.subSequence(start, i).toString();
    }

    private static boolean isBlank(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<Tok> lex(CharSequence text, int start, int end) {
        ArrayList<Tok> tokens = new ArrayList<>();
        if (end <= start) {
            return tokens;
        }
        RoseGoldLexer lexer = new RoseGoldLexer();
        lexer.start(text, start, end);
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

    private enum Kind { BLOCK, SEMI, STMT, OTHER }

    private record Tok(IElementType type, int start, int end, String text) {
    }

    private record Brace(int openEnd, int closeStart) {
    }
}

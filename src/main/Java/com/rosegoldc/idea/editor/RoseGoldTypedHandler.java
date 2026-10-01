package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.rosegoldc.idea.psi.RoseGoldLexer;
import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result beforeCharTyped(
            char c,
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @NotNull FileType fileType
    ) {
        if (!(file instanceof RoseGoldFile)) {
            return Result.CONTINUE;
        }
        if (c != ')' && c != ']' && c != '}' && c != '"') {
            return Result.CONTINUE;
        }
        int offset = editor.getCaretModel().getOffset();
        CharSequence text = editor.getDocument().getCharsSequence();
        if (offset < text.length() && text.charAt(offset) == c) {
            editor.getCaretModel().moveToOffset(offset + 1);
            return Result.STOP;
        }
        return Result.CONTINUE;
    }

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!(file instanceof RoseGoldFile)) {
            return Result.CONTINUE;
        }
        char closer = closer(c);
        if (closer == 0) {
            return Result.CONTINUE;
        }
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        CharSequence text = document.getCharsSequence();
        if (inCommentOrString(text, offset)) {
            return Result.CONTINUE;
        }
        if (offset < text.length() && text.charAt(offset) == closer) {
            return Result.CONTINUE;
        }
        if (offset < text.length() && !allowedBefore(text.charAt(offset))) {
            return Result.CONTINUE;
        }
        document.insertString(offset, String.valueOf(closer));
        editor.getCaretModel().moveToOffset(offset);
        return Result.STOP;
    }

    private static char closer(char open) {
        return switch (open) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            default -> 0;
        };
    }

    private static boolean allowedBefore(char next) {
        return next == ' ' || next == '\t' || next == '\n' || next == '\r'
                || next == ')' || next == ']' || next == '}'
                || next == ',' || next == ';' || next == ':';
    }

    private static boolean inCommentOrString(CharSequence text, int offset) {
        int inspect = Math.max(0, offset - 1);
        RoseGoldLexer lexer = new RoseGoldLexer();
        lexer.start(text, 0, Math.min(offset, text.length()));
        IElementType type = null;
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenStart() <= inspect && inspect < lexer.getTokenEnd()) {
                type = lexer.getTokenType();
            }
            lexer.advance();
        }
        return type == RoseGoldTokenTypes.COMMENT || type == RoseGoldTokenTypes.STRING;
    }
}

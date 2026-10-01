package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.application.options.CodeStyle;
import com.intellij.lang.surroundWith.SurroundDescriptor;
import com.intellij.lang.surroundWith.Surrounder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldSurroundDescriptor implements SurroundDescriptor {

    private static final Surrounder[] SURROUNDERS = {
            new Wrap("try { }", "try {", "}", -1, 0),
            new Wrap("if () { }", "if (true) {", "}", 4, 4),
            new Wrap("while () { }", "while (true) {", "}", 7, 4),
            new Wrap("match { }", "match x {", "}", 6, 1),
            new Wrap("switch { }", "switch x {", "}", 7, 1),
    };

    @Override
    public PsiElement @NotNull [] getElementsToSurround(PsiFile file, int startOffset, int endOffset) {
        if (!(file instanceof RoseGoldFile) || startOffset >= endOffset) {
            return PsiElement.EMPTY_ARRAY;
        }
        return new PsiElement[]{file};
    }

    @Override
    public Surrounder @NotNull [] getSurrounders() {
        return SURROUNDERS;
    }

    @Override
    public boolean isExclusive() {
        return false;
    }

    private static final class Wrap implements Surrounder {
        private final String description;
        private final String header;
        private final String footer;
        private final int selectOffset;
        private final int selectLength;

        private Wrap(String description, String header, String footer, int selectOffset, int selectLength) {
            this.description = description;
            this.header = header;
            this.footer = footer;
            this.selectOffset = selectOffset;
            this.selectLength = selectLength;
        }

        @Override
        public String getTemplateDescription() {
            return description;
        }

        @Override
        public boolean isApplicable(PsiElement @NotNull [] elements) {
            return elements.length == 1 && elements[0] instanceof RoseGoldFile;
        }

        @Override
        public @Nullable TextRange surroundElements(
                @NotNull Project project,
                @NotNull Editor editor,
                PsiElement @NotNull [] elements
        ) {
            Document document = editor.getDocument();
            int start = editor.getSelectionModel().getSelectionStart();
            int end = editor.getSelectionModel().getSelectionEnd();
            if (end <= start) {
                start = elements[0].getTextRange().getStartOffset();
                end = elements[0].getTextRange().getEndOffset();
            }
            if (end <= start) {
                return null;
            }
            int lineStart = document.getLineStartOffset(document.getLineNumber(start));
            int last = Math.max(start, end - 1);
            int lineEnd = document.getLineEndOffset(document.getLineNumber(last));
            String selected = document.getText(TextRange.create(lineStart, lineEnd));
            String indent = leading(selected);
            int indentSize = CodeStyle.getIndentSize(elements[0].getContainingFile());
            String pad = " ".repeat(Math.max(indentSize, 1));
            String inner = indentBlock(selected, pad);
            String replacement = indent + header + "\n" + inner + "\n" + indent + footer;
            document.replaceString(lineStart, lineEnd, replacement);
            if (selectLength > 0 && selectOffset >= 0) {
                int at = lineStart + indent.length() + selectOffset;
                return TextRange.from(at, selectLength);
            }
            return TextRange.from(lineStart + indent.length() + header.length() + 1, 0);
        }

        private static String leading(String text) {
            int i = 0;
            while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                i++;
            }
            return text.substring(0, i);
        }

        private static String indentBlock(String selected, String pad) {
            String[] lines = selected.split("\n", -1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                if (!lines[i].isBlank()) {
                    out.append(pad).append(lines[i]);
                } else {
                    out.append(lines[i]);
                }
            }
            return out.toString();
        }
    }
}

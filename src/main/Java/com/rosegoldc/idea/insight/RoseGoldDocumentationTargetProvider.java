package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldIcons;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.model.Pointer;
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Hover;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class RoseGoldDocumentationTargetProvider implements DocumentationTargetProvider {

    @Override
    public @NotNull List<? extends @NotNull DocumentationTarget> documentationTargets(
            @NotNull PsiFile file,
            int offset
    ) {
        if (!(file instanceof RoseGoldFile rg)) {
            return List.of();
        }
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        Hover.Info info = Hover.at(rg.getText(), path, offset);
        if (info == null) {
            return List.of();
        }
        return List.of(new Target(info, rg.getName(), rg.getProject()));
    }

    static final class Target implements DocumentationTarget {
        private final Hover.Info info;
        private final String fileName;
        private final Project project;

        Target(Hover.Info info, String fileName, Project project) {
            this.info = info;
            this.fileName = fileName == null ? "" : fileName;
            this.project = project;
        }

        @Override
        public @NotNull Pointer<? extends DocumentationTarget> createPointer() {
            Hover.Info info = this.info;
            String fileName = this.fileName;
            Project project = this.project;
            return () -> project.isDisposed() ? null : new Target(info, fileName, project);
        }

        @Override
        public @NotNull TargetPresentation computePresentation() {
            var builder = TargetPresentation.builder(info.name)
                    .icon(RoseGoldIcons.FILE);
            if (!fileName.isEmpty()) {
                builder = builder.locationText(fileName);
            }
            return builder.presentation();
        }

        @Override
        public @NotNull String computeDocumentationHint() {
            return highlighted(project, info.signature);
        }

        @Override
        public @NotNull DocumentationResult computeDocumentation() {
            return DocumentationResult.documentation(html(info, project));
        }
    }

    static String html(Hover.Info info, Project project) {
        StringBuilder sb = new StringBuilder();
        sb.append(DocumentationMarkup.DEFINITION_START);
        if (info.deprecated) {
            sb.append(DocumentationMarkup.GRAYED_START);
            sb.append("@deprecated");
            sb.append(DocumentationMarkup.GRAYED_END);
            sb.append("\n");
        }
        sb.append(highlighted(project, info.signature));
        sb.append(DocumentationMarkup.DEFINITION_END);
        if (!info.doc.isEmpty()) {
            sb.append(DocumentationMarkup.CONTENT_START);
            sb.append(docHtml(info.doc));
            sb.append(DocumentationMarkup.CONTENT_END);
        }
        if (!info.crate.isEmpty()) {
            sb.append(DocumentationMarkup.SECTIONS_START);
            sb.append(DocumentationMarkup.SECTION_HEADER_START);
            sb.append("Crate");
            sb.append(DocumentationMarkup.SECTION_SEPARATOR);
            sb.append(StringUtil.escapeXmlEntities(info.crate));
            sb.append(DocumentationMarkup.SECTION_END);
            sb.append(DocumentationMarkup.SECTIONS_END);
        }
        return sb.toString();
    }

    private static String highlighted(Project project, String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                buf, project, RoseGold.INSTANCE, code, 1.0f);
        return buf.toString();
    }

    private static String docHtml(String doc) {
        String escaped = StringUtil.escapeXmlEntities(doc);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < escaped.length()) {
            int tick = escaped.indexOf('`', i);
            if (tick < 0) {
                out.append(escaped.substring(i).replace("\n", "<br>"));
                break;
            }
            out.append(escaped.substring(i, tick).replace("\n", "<br>"));
            int end = escaped.indexOf('`', tick + 1);
            if (end < 0) {
                out.append(escaped.substring(tick).replace("\n", "<br>"));
                break;
            }
            out.append("<code>").append(escaped, tick + 1, end).append("</code>");
            i = end + 1;
        }
        return out.toString();
    }
}

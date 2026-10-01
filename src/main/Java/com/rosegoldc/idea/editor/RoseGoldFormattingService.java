package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldSettings;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.formatting.service.FormattingService;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Format;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

public final class RoseGoldFormattingService extends AsyncDocumentFormattingService {

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return file instanceof RoseGoldFile;
    }

    @Override
    public @NotNull Set<FormattingService.Feature> getFeatures() {
        return EnumSet.noneOf(FormattingService.Feature.class);
    }

    @Override
    protected @NotNull FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
        FormattingContext context = request.getContext();
        String source = request.getDocumentText();
        return new FormattingTask() {
            private volatile boolean cancelled;

            @Override
            public void run() {
                if (cancelled) {
                    return;
                }
                try {
                    Format.Options opts = new Format.Options();
                    RoseGoldSettings settings = RoseGoldSettings.getInstance();
                    opts.blankBetweenItems = !settings.isFormatCompact();
                    opts.keepComments = !settings.isFormatStripComments();
                    String path = context.getVirtualFile() != null
                            ? context.getVirtualFile().getPath()
                            : "<editor>";
                    Format.Result result = Format.formatSource(source, path, opts);
                    if (!result.ok) {
                        request.onError("RoseGold format", result.message.isEmpty() ? "fmt failed" : result.message);
                        return;
                    }
                    String formatted = result.out;
                    if (source.contains("\r\n") && !formatted.contains("\r\n")) {
                        formatted = formatted.replace("\n", "\r\n");
                    }
                    if (!cancelled) {
                        request.onTextReady(formatted);
                    }
                } catch (Exception ex) {
                    String message = ex.getMessage();
                    request.onError("RoseGold format", message == null || message.isBlank() ? "fmt failed" : message);
                }
            }

            @Override
            public boolean cancel() {
                cancelled = true;
                return true;
            }

            @Override
            public boolean isRunUnderProgress() {
                return true;
            }
        };
    }

    @Override
    protected @NotNull String getNotificationGroupId() {
        return "RoseGold";
    }

    @Override
    protected @NotNull String getName() {
        return "RoseGold fmt";
    }
}

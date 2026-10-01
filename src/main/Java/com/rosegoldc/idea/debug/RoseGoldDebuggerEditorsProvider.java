package com.rosegoldc.idea.debug;

import com.rosegoldc.idea.RoseGoldFileType;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class RoseGoldDebuggerEditorsProvider extends XDebuggerEditorsProvider {

    static final RoseGoldDebuggerEditorsProvider INSTANCE = new RoseGoldDebuggerEditorsProvider();

    private RoseGoldDebuggerEditorsProvider() {
    }

    @Override
    public @NotNull FileType getFileType() {
        return RoseGoldFileType.INSTANCE;
    }

    @Override
    public @NotNull Document createDocument(
            @NotNull Project project,
            @NotNull String text,
            @Nullable XSourcePosition sourcePosition,
            @NotNull EvaluationMode mode
    ) {
        return EditorFactory.getInstance().createDocument(text);
    }
}

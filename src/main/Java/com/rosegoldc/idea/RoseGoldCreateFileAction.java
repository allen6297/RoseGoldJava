package com.rosegoldc.idea;

import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldCreateFileAction extends CreateFileFromTemplateAction implements DumbAware {

    public RoseGoldCreateFileAction() {
        super("RoseGold File", "Create a new RoseGold source file", RoseGoldIcons.FILE);
    }

    @Override
    protected void buildDialog(
            @NotNull Project project,
            @NotNull PsiDirectory directory,
            @NotNull CreateFileFromTemplateDialog.Builder builder
    ) {
        builder.setTitle("New RoseGold File")
                .addKind("File", RoseGoldIcons.FILE, "RoseGold File")
                .addKind("Test", RoseGoldIcons.FILE, "RoseGold Test");
    }

    @Override
    protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
        return "Create RoseGold File " + newName;
    }
}

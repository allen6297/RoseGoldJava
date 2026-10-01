package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldFileType;
import com.rosegoldc.idea.RoseGoldProjects;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class RoseGoldTestSettingsEditor extends SettingsEditor<RoseGoldTestConfiguration> {

    private final TextFieldWithBrowseButton fileField = new TextFieldWithBrowseButton();

    RoseGoldTestSettingsEditor(@NotNull Project project) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, true, false, false, false, false)
                .withTitle("RoseGold Tests")
                .withDescription("Select a .rg file, project.toml, a tests directory with pass/fail, or leave empty for the language suite")
                .withFileFilter(file -> file.isDirectory() || RoseGoldFileType.isRoseGoldFile(file)
                        || RoseGoldProjects.isProjectToml(file));
        fileField.addBrowseFolderListener(new TextBrowseFolderListener(descriptor, project));
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("File or directory:", fileField)
                .addComponent(new JLabel("Leave empty to run the language suite (examples/tests.rg and tests/)."))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull RoseGoldTestConfiguration configuration) {
        fileField.setText(configuration.getFilePath());
    }

    @Override
    protected void applyEditorTo(@NotNull RoseGoldTestConfiguration configuration) {
        configuration.setFilePath(fileField.getText().trim());
    }
}

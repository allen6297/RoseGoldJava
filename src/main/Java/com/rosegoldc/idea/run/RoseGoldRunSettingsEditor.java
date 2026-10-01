package com.rosegoldc.idea.run;

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

public final class RoseGoldRunSettingsEditor extends SettingsEditor<RoseGoldRunConfiguration> {

    private final TextFieldWithBrowseButton fileField = new TextFieldWithBrowseButton();
    private final JTextField argsField = new JTextField();
    private final JCheckBox stopOnEntryBox = new JCheckBox("Stop on entry");
    private final JCheckBox nativeBox = new JCheckBox("Run natively (LLVM)");

    RoseGoldRunSettingsEditor(@NotNull Project project) {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("RoseGold File")
                .withDescription("Select a .rg file or project.toml to run")
                .withFileFilter(file -> RoseGoldFileType.isRoseGoldFile(file) || RoseGoldProjects.isProjectToml(file));
        fileField.addBrowseFolderListener(new TextBrowseFolderListener(descriptor, project));
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("File:", fileField)
                .addLabeledComponent("Arguments:", argsField)
                .addComponent(nativeBox)
                .addComponent(stopOnEntryBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    protected void resetEditorFrom(@NotNull RoseGoldRunConfiguration configuration) {
        fileField.setText(configuration.getFilePath());
        argsField.setText(configuration.getProgramArguments());
        nativeBox.setSelected(configuration.isRunNative());
        stopOnEntryBox.setSelected(configuration.isStopOnEntry());
    }

    @Override
    protected void applyEditorTo(@NotNull RoseGoldRunConfiguration configuration) {
        configuration.setFilePath(fileField.getText().trim());
        configuration.setProgramArguments(argsField.getText());
        configuration.setRunNative(nativeBox.isSelected());
        configuration.setStopOnEntry(stopOnEntryBox.isSelected());
    }
}

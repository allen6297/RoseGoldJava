package com.rosegoldc.idea;

import com.intellij.openapi.options.Configurable;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class RoseGoldConfigurable implements Configurable {

    private JCheckBox compactBox;
    private JCheckBox stripCommentsBox;
    private JCheckBox nativeBox;

    @Nls
    @Override
    public String getDisplayName() {
        return "RoseGold";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compactBox = new JCheckBox("Compact (no blank lines between top-level items)");
        stripCommentsBox = new JCheckBox("Strip comments when formatting");
        nativeBox = new JCheckBox("Run and test natively (LLVM / clang)");
        return FormBuilder.createFormBuilder()
                .addComponent(compactBox)
                .addComponent(stripCommentsBox)
                .addComponent(nativeBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        if (compactBox == null || stripCommentsBox == null || nativeBox == null) {
            return false;
        }
        RoseGoldSettings settings = RoseGoldSettings.getInstance();
        return compactBox.isSelected() != settings.isFormatCompact()
                || stripCommentsBox.isSelected() != settings.isFormatStripComments()
                || nativeBox.isSelected() != settings.isRunNative();
    }

    @Override
    public void apply() {
        RoseGoldSettings settings = RoseGoldSettings.getInstance();
        if (compactBox != null) {
            settings.setFormatCompact(compactBox.isSelected());
        }
        if (stripCommentsBox != null) {
            settings.setFormatStripComments(stripCommentsBox.isSelected());
        }
        if (nativeBox != null) {
            settings.setRunNative(nativeBox.isSelected());
        }
    }

    @Override
    public void reset() {
        RoseGoldSettings settings = RoseGoldSettings.getInstance();
        if (compactBox != null) {
            compactBox.setSelected(settings.isFormatCompact());
        }
        if (stripCommentsBox != null) {
            stripCommentsBox.setSelected(settings.isFormatStripComments());
        }
        if (nativeBox != null) {
            nativeBox.setSelected(settings.isRunNative());
        }
    }

    @Override
    public void disposeUIResources() {
        compactBox = null;
        stripCommentsBox = null;
        nativeBox = null;
    }
}

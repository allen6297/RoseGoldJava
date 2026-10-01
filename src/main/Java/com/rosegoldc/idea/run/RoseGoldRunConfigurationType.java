package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldIcons;
import com.intellij.execution.configurations.SimpleConfigurationType;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldRunConfigurationType extends SimpleConfigurationType implements DumbAware {

    public static final String ID = "RoseGoldRun";

    public RoseGoldRunConfigurationType() {
        super(
                ID,
                "RoseGold",
                "Run a RoseGold (.rg) file",
                NotNullLazyValue.createValue(() -> RoseGoldIcons.FILE)
        );
    }

    @Override
    public @NotNull RoseGoldRunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new RoseGoldRunConfiguration(project, this, "RoseGold");
    }

    @Override
    public @NotNull Class<? extends BaseState> getOptionsClass() {
        return RoseGoldRunConfigurationOptions.class;
    }

    @Override
    public boolean isEditableInDumbMode() {
        return true;
    }
}

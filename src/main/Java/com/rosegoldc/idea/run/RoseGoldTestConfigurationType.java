package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGold;
import com.intellij.execution.configurations.SimpleConfigurationType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldTestConfigurationType extends SimpleConfigurationType implements DumbAware {

    public static final String ID = "RoseGoldTest";

    public RoseGoldTestConfigurationType() {
        super(
                ID,
                "RoseGold Test",
                "Run RoseGold @test functions",
                NotNullLazyValue.createValue(() -> AllIcons.RunConfigurations.TestState.Run)
        );
    }

    @Override
    public @NotNull RoseGoldTestConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new RoseGoldTestConfiguration(project, this, "RoseGold Test");
    }

    @Override
    public @NotNull Class<? extends BaseState> getOptionsClass() {
        return RoseGoldTestConfigurationOptions.class;
    }

    @Override
    public boolean isEditableInDumbMode() {
        return true;
    }
}

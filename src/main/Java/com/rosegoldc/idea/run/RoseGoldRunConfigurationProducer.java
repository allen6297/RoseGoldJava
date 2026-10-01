package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGoldProjects;
import com.rosegoldc.idea.RoseGoldSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldRunConfigurationProducer extends LazyRunConfigurationProducer<RoseGoldRunConfiguration> {

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ConfigurationTypeUtil.findConfigurationType(RoseGoldRunConfigurationType.class);
    }

    @Override
    protected boolean setupConfigurationFromContext(
            @NotNull RoseGoldRunConfiguration configuration,
            @NotNull ConfigurationContext context,
            @NotNull Ref<PsiElement> sourceElement
    ) {
        VirtualFile file = fileFrom(context);
        if (file == null) {
            return false;
        }
        configuration.setFilePath(file.getPath());
        configuration.setRunNative(RoseGoldSettings.getInstance().isRunNative());
        configuration.setGeneratedName();
        PsiElement location = context.getPsiLocation();
        if (location != null) {
            sourceElement.set(location);
        }
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(
            @NotNull RoseGoldRunConfiguration configuration,
            @NotNull ConfigurationContext context
    ) {
        VirtualFile file = fileFrom(context);
        return file != null && FileUtil.pathsEqual(file.getPath(), configuration.getFilePath());
    }

    private static VirtualFile fileFrom(@NotNull ConfigurationContext context) {
        VirtualFile file = context.getLocation() != null ? context.getLocation().getVirtualFile() : null;
        return RoseGoldProjects.runTarget(file);
    }
}

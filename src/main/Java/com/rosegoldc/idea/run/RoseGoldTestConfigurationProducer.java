package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGoldFileType;
import com.rosegoldc.idea.RoseGoldProjects;
import com.rosegoldc.idea.RoseGoldSettings;
import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Tests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldTestConfigurationProducer extends LazyRunConfigurationProducer<RoseGoldTestConfiguration> {

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ConfigurationTypeUtil.findConfigurationType(RoseGoldTestConfigurationType.class);
    }

    @Override
    protected boolean setupConfigurationFromContext(
            @NotNull RoseGoldTestConfiguration configuration,
            @NotNull ConfigurationContext context,
            @NotNull Ref<PsiElement> sourceElement
    ) {
        VirtualFile file = targetFrom(context);
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
            @NotNull RoseGoldTestConfiguration configuration,
            @NotNull ConfigurationContext context
    ) {
        VirtualFile file = targetFrom(context);
        return file != null && FileUtil.pathsEqual(file.getPath(), configuration.getFilePath());
    }

    @Override
    public boolean shouldReplace(@NotNull ConfigurationFromContext self, @NotNull ConfigurationFromContext other) {
        if (!(other.getConfiguration() instanceof RoseGoldRunConfiguration)) {
            return false;
        }
        PsiElement location = self.getSourceElement();
        if (location == null) {
            return false;
        }
        PsiFile file = location.getContainingFile();
        return file instanceof RoseGoldFile && Tests.at(file.getText(), location.getTextOffset()) != null;
    }

    @Nullable
    private static VirtualFile targetFrom(@NotNull ConfigurationContext context) {
        VirtualFile file = context.getLocation() != null ? context.getLocation().getVirtualFile() : null;
        if (file == null || !file.isInLocalFileSystem()) {
            return null;
        }
        if (file.isDirectory()) {
            if (file.findChild("pass") != null || file.findChild("fail") != null) {
                return file;
            }
            return file.findChild("project.toml");
        }
        if (RoseGoldProjects.isProjectToml(file)) {
            return file;
        }
        if (!RoseGoldFileType.isRoseGoldFile(file)) {
            return null;
        }
        PsiElement location = context.getPsiLocation();
        PsiFile psi = location != null ? location.getContainingFile() : null;
        String text = psi instanceof RoseGoldFile ? psi.getText() : null;
        if (text == null || !Tests.hasAny(text)) {
            return null;
        }
        return file;
    }
}

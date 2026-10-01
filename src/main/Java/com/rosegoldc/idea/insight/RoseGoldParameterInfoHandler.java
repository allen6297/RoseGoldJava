package com.rosegoldc.idea.insight;

import com.rosegoldc.idea.psi.RoseGoldFile;
import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.ParameterInfoHandler;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiFile;
import com.rosegoldc.lang.Signatures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldParameterInfoHandler
        implements ParameterInfoHandler<RoseGoldFile, Signatures.Info>, DumbAware {

    @Override
    public @Nullable RoseGoldFile findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        Signatures.Info info = infoAt(context.getFile(), context.getOffset());
        if (info == null) {
            return null;
        }
        context.setItemsToShow(new Object[]{info});
        return file(context.getFile());
    }

    @Override
    public void showParameterInfo(@NotNull RoseGoldFile element, @NotNull CreateParameterInfoContext context) {
        Signatures.Info info = infoAt(element, context.getOffset());
        int offset = info != null ? info.parenOffset : context.getOffset();
        context.showHint(element, offset, this);
    }

    @Override
    public @Nullable RoseGoldFile findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        if (infoAt(context.getFile(), context.getOffset()) == null) {
            return null;
        }
        RoseGoldFile file = file(context.getFile());
        if (file == null) {
            return null;
        }
        if (context.getParameterOwner() != null && context.getParameterOwner() != file) {
            return null;
        }
        return file;
    }

    @Override
    public void updateParameterInfo(@NotNull RoseGoldFile parameterOwner, @NotNull UpdateParameterInfoContext context) {
        Signatures.Info info = infoAt(parameterOwner, context.getOffset());
        if (info == null) {
            context.removeHint();
            return;
        }
        context.setParameterOwner(parameterOwner);
        context.setCurrentParameter(info.activeParam);
    }

    @Override
    public void updateUI(Signatures.Info p, @NotNull ParameterInfoUIContext context) {
        if (p == null) {
            context.setUIComponentEnabled(false);
            return;
        }
        int index = context.getCurrentParameterIndex();
        if (index < 0) {
            index = p.activeParam;
        }
        int start = p.highlightStart(index);
        int end = p.highlightEnd(index);
        context.setupUIComponentPresentation(
                p.label,
                start,
                end,
                false,
                false,
                false,
                context.getDefaultParameterColor()
        );
    }

    private static @Nullable Signatures.Info infoAt(PsiFile file, int offset) {
        RoseGoldFile rg = file(file);
        if (rg == null) {
            return null;
        }
        String path = rg.getVirtualFile() != null ? rg.getVirtualFile().getPath() : rg.getName();
        String text = rg.getText();
        return Signatures.at(text, path, Math.clamp(offset, 0, text.length()));
    }

    private static @Nullable RoseGoldFile file(PsiFile file) {
        return file instanceof RoseGoldFile rg ? rg : null;
    }
}

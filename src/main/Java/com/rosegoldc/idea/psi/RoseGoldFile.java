package com.rosegoldc.idea.psi;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldFileType;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldFile extends PsiFileBase {

    public RoseGoldFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, RoseGold.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return RoseGoldFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "RoseGoldFile:" + getName();
    }
}

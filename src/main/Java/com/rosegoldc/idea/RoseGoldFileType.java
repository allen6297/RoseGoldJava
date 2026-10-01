package com.rosegoldc.idea;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class RoseGoldFileType extends LanguageFileType {

    public static final RoseGoldFileType INSTANCE = new RoseGoldFileType();

    private RoseGoldFileType() {
        super(RoseGold.INSTANCE);
    }

    public static boolean isRoseGoldFile(@Nullable VirtualFile file) {
        return file != null && (file.getFileType() == INSTANCE || "rg".equalsIgnoreCase(file.getExtension()));
    }

    @NotNull
    @Override
    public String getName() {
        return "RoseGold";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "RoseGold source file";
    }

    @NotNull
    @Override
    public String getDefaultExtension() {
        return "rg";
    }

    @Override
    public Icon getIcon() {
        return RoseGoldIcons.FILE;
    }
}

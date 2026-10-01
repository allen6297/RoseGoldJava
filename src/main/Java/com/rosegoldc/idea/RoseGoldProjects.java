package com.rosegoldc.idea;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldProjects {

    private RoseGoldProjects() {
    }

    public static boolean isProjectToml(@Nullable VirtualFile file) {
        return file != null && !file.isDirectory() && "project.toml".equals(file.getName());
    }

    @Nullable
    public static VirtualFile runTarget(@Nullable VirtualFile file) {
        if (file == null || !file.isInLocalFileSystem()) {
            return null;
        }
        if (file.isDirectory()) {
            return file.findChild("project.toml");
        }
        if (isProjectToml(file) || RoseGoldFileType.isRoseGoldFile(file)) {
            return file;
        }
        return null;
    }
}

package com.rosegoldc.idea.editor;

import com.intellij.lang.Commenter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldCommenter implements Commenter {

    @Override
    public @NotNull String getLineCommentPrefix() {
        return "//";
    }

    @Override
    public @NotNull String getBlockCommentPrefix() {
        return "/#";
    }

    @Override
    public @NotNull String getBlockCommentSuffix() {
        return "#/";
    }

    @Override
    public @Nullable String getCommentedBlockCommentPrefix() {
        return null;
    }

    @Override
    public @Nullable String getCommentedBlockCommentSuffix() {
        return null;
    }
}

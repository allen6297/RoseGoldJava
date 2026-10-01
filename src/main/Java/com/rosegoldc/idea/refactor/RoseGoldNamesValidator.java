package com.rosegoldc.idea.refactor;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import com.rosegoldc.lang.Rename;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldNamesValidator implements NamesValidator {

    @Override
    public boolean isKeyword(@NotNull String name, Project project) {
        return Rename.isKeyword(name);
    }

    @Override
    public boolean isIdentifier(@NotNull String name, Project project) {
        return Rename.isValidName(name);
    }
}

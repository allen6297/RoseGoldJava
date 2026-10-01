package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.RoseGold;
import com.intellij.application.options.IndentOptionsEditor;
import com.intellij.application.options.SmartIndentOptionsEditor;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    @Override
    public @NotNull Language getLanguage() {
        return RoseGold.INSTANCE;
    }

    @Override
    public @Nullable IndentOptionsEditor getIndentOptionsEditor() {
        return new SmartIndentOptionsEditor();
    }

    @Override
    protected void customizeDefaults(
            @NotNull CommonCodeStyleSettings commonSettings,
            @NotNull CommonCodeStyleSettings.IndentOptions indentOptions
    ) {
        indentOptions.INDENT_SIZE = 4;
        indentOptions.TAB_SIZE = 4;
        indentOptions.CONTINUATION_INDENT_SIZE = 4;
        indentOptions.USE_TAB_CHARACTER = false;
    }

    @Override
    public @NotNull String getCodeSample(@NotNull SettingsType settingsType) {
        return """
                fn paint(fill: Color): Int {
                    var c = Color.Rgb(47, 111, 196);
                    if c == Color.Red {
                        print("red");
                    }
                    return 0;
                }
                """;
    }
}

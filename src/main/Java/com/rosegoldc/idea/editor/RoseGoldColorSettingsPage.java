package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldIcons;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public final class RoseGoldColorSettingsPage implements ColorSettingsPage {

    private static final AttributesDescriptor[] DESCRIPTORS = {
            new AttributesDescriptor("Keyword", RoseGoldSyntaxHighlighter.KEYWORD),
            new AttributesDescriptor("Type", RoseGoldSyntaxHighlighter.TYPE),
            new AttributesDescriptor("Identifier", RoseGoldSyntaxHighlighter.IDENTIFIER),
            new AttributesDescriptor("Number", RoseGoldSyntaxHighlighter.NUMBER),
            new AttributesDescriptor("String", RoseGoldSyntaxHighlighter.STRING),
            new AttributesDescriptor("Comment", RoseGoldSyntaxHighlighter.COMMENT),
            new AttributesDescriptor("Decorator", RoseGoldSyntaxHighlighter.DECORATOR),
            new AttributesDescriptor("Operator", RoseGoldSyntaxHighlighter.OPERATOR),
            new AttributesDescriptor("Braces", RoseGoldSyntaxHighlighter.BRACES),
            new AttributesDescriptor("Parentheses", RoseGoldSyntaxHighlighter.PARENTHESES),
            new AttributesDescriptor("Brackets", RoseGoldSyntaxHighlighter.BRACKETS),
    };

    @Override
    public @Nullable Icon getIcon() {
        return RoseGoldIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new RoseGoldSyntaxHighlighter();
    }

    @Override
    public @NonNls @NotNull String getDemoText() {
        return """
                import ui;

                enum Color {
                    Red,
                    Rgb(r: Int, g: Int, b: Int)
                }

                @test
                fn paint(fill: Color): Int {
                    // named + rgb
                    var c = Color.Rgb(47, 111, 196);
                    var xs: Array[Int] = [1, 2, 3];
                    if c == Color.Red {
                        print("red");
                    }
                    return 0;
                }
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return null;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "RoseGold";
    }
}

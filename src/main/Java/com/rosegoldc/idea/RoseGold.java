package com.rosegoldc.idea;

import com.intellij.lang.Language;

public final class RoseGold extends Language {

    public static final RoseGold INSTANCE = new RoseGold();

    private RoseGold() {
        super("RoseGold");
    }
}

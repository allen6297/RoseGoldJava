package com.rosegoldc.idea.psi;

import com.rosegoldc.idea.RoseGold;
import com.intellij.psi.tree.IElementType;

public final class RoseGoldTypes {

    public static final IElementType IMPORT = new IElementType("ROSEGOLD_IMPORT", RoseGold.INSTANCE);
    public static final IElementType FN = new IElementType("ROSEGOLD_FN", RoseGold.INSTANCE);
    public static final IElementType STRUCT = new IElementType("ROSEGOLD_STRUCT", RoseGold.INSTANCE);
    public static final IElementType CLASS = new IElementType("ROSEGOLD_CLASS", RoseGold.INSTANCE);
    public static final IElementType TRAIT = new IElementType("ROSEGOLD_TRAIT", RoseGold.INSTANCE);
    public static final IElementType ENUM = new IElementType("ROSEGOLD_ENUM", RoseGold.INSTANCE);
    public static final IElementType IMPL = new IElementType("ROSEGOLD_IMPL", RoseGold.INSTANCE);
    public static final IElementType MOD = new IElementType("ROSEGOLD_MOD", RoseGold.INSTANCE);
    public static final IElementType SIGNAL = new IElementType("ROSEGOLD_SIGNAL", RoseGold.INSTANCE);

    private RoseGoldTypes() {
    }
}

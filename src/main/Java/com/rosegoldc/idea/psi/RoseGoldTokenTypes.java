package com.rosegoldc.idea.psi;

import com.rosegoldc.idea.RoseGold;
import com.intellij.psi.tree.IElementType;

public final class RoseGoldTokenTypes {

    public static final IElementType KEYWORD = new IElementType("ROSEGOLD_KEYWORD", RoseGold.INSTANCE);
    public static final IElementType TYPE = new IElementType("ROSEGOLD_TYPE", RoseGold.INSTANCE);
    public static final IElementType IDENTIFIER = new IElementType("ROSEGOLD_IDENTIFIER", RoseGold.INSTANCE);
    public static final IElementType NUMBER = new IElementType("ROSEGOLD_NUMBER", RoseGold.INSTANCE);
    public static final IElementType STRING = new IElementType("ROSEGOLD_STRING", RoseGold.INSTANCE);
    public static final IElementType COMMENT = new IElementType("ROSEGOLD_COMMENT", RoseGold.INSTANCE);
    public static final IElementType DECORATOR = new IElementType("ROSEGOLD_DECORATOR", RoseGold.INSTANCE);
    public static final IElementType OPERATOR = new IElementType("ROSEGOLD_OPERATOR", RoseGold.INSTANCE);
    public static final IElementType LBRACE = new IElementType("ROSEGOLD_LBRACE", RoseGold.INSTANCE);
    public static final IElementType RBRACE = new IElementType("ROSEGOLD_RBRACE", RoseGold.INSTANCE);
    public static final IElementType LPAREN = new IElementType("ROSEGOLD_LPAREN", RoseGold.INSTANCE);
    public static final IElementType RPAREN = new IElementType("ROSEGOLD_RPAREN", RoseGold.INSTANCE);
    public static final IElementType LBRACKET = new IElementType("ROSEGOLD_LBRACKET", RoseGold.INSTANCE);
    public static final IElementType RBRACKET = new IElementType("ROSEGOLD_RBRACKET", RoseGold.INSTANCE);

    private RoseGoldTokenTypes() {
    }
}

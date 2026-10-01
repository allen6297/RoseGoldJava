package com.rosegoldc.idea.editor;

import com.rosegoldc.idea.psi.RoseGoldTokenTypes;
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler;

public final class RoseGoldQuoteHandler extends SimpleTokenSetQuoteHandler {

    public RoseGoldQuoteHandler() {
        super(RoseGoldTokenTypes.STRING);
    }
}

package com.rosegoldc.idea.editor;

import com.intellij.codeInsight.editorActions.enter.EnterBetweenBracesDelegate;

public final class RoseGoldEnterBetweenBracesDelegate extends EnterBetweenBracesDelegate {

    @Override
    protected boolean isBracePair(char lBrace, char rBrace) {
        return lBrace == '{' && rBrace == '}'
                || lBrace == '(' && rBrace == ')'
                || lBrace == '[' && rBrace == ']';
    }
}

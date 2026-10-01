package com.rosegoldc.idea.insight;

public final class RoseGoldGotoSymbolContributor extends RoseGoldGotoContributor {

    public RoseGoldGotoSymbolContributor() {
        super(RoseGoldGotoContributor::isSymbol);
    }
}

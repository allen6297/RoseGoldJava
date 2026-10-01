package com.rosegoldc.idea.insight;

public final class RoseGoldGotoClassContributor extends RoseGoldGotoContributor {

    public RoseGoldGotoClassContributor() {
        super(RoseGoldGotoContributor::isType);
    }
}

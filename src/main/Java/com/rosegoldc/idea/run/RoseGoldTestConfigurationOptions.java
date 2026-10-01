package com.rosegoldc.idea.run;

import com.intellij.execution.configurations.LocatableRunConfigurationOptions;
import com.intellij.util.xmlb.annotations.Attribute;

public final class RoseGoldTestConfigurationOptions extends LocatableRunConfigurationOptions {

    @Attribute("filePath")
    public String filePath = "";
}

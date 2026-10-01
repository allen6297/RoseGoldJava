package com.rosegoldc.idea.run;

import com.intellij.execution.configurations.LocatableRunConfigurationOptions;
import com.intellij.util.xmlb.annotations.Attribute;

public final class RoseGoldRunConfigurationOptions extends LocatableRunConfigurationOptions {

    @Attribute("filePath")
    public String filePath = "";

    @Attribute("programArguments")
    public String programArguments = "";

    @Attribute("stopOnEntry")
    public boolean stopOnEntry = true;

    @Attribute("runNative")
    public boolean runNative = true;
}

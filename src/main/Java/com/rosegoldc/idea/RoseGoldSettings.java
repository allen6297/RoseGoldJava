package com.rosegoldc.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@State(name = "RoseGoldSettings", storages = @Storage("rosegold.xml"))
public final class RoseGoldSettings implements PersistentStateComponent<RoseGoldSettings.State> {

    public static final class State {
        public boolean formatCompact = false;
        public boolean formatStripComments = false;
        public boolean runNative = true;
    }

    private State state = new State();

    public static RoseGoldSettings getInstance() {
        return ApplicationManager.getApplication().getService(RoseGoldSettings.class);
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public boolean isFormatCompact() {
        return state.formatCompact;
    }

    public void setFormatCompact(boolean compact) {
        state.formatCompact = compact;
    }

    public boolean isFormatStripComments() {
        return state.formatStripComments;
    }

    public void setFormatStripComments(boolean strip) {
        state.formatStripComments = strip;
    }

    public boolean isRunNative() {
        return state.runNative;
    }

    public void setRunNative(boolean runNative) {
        state.runNative = runNative;
    }
}

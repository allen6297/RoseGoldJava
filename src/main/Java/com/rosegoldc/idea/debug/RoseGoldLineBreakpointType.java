package com.rosegoldc.idea.debug;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.RoseGoldFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RoseGoldLineBreakpointType extends XLineBreakpointType<XBreakpointProperties<?>> {

    public static final String ID = "rose-gold-line";

    public RoseGoldLineBreakpointType() {
        super(ID, "RoseGold line breakpoints");
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        return RoseGoldFileType.isRoseGoldFile(file);
    }

    @Override
    public @Nullable XBreakpointProperties<?> createBreakpointProperties(@NotNull VirtualFile file, int line) {
        return null;
    }

    @Override
    public XDebuggerEditorsProvider getEditorsProvider(
            @NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint,
            @NotNull Project project
    ) {
        return RoseGoldDebuggerEditorsProvider.INSTANCE;
    }
}

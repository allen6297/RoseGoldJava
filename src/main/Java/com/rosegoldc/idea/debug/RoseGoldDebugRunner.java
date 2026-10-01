package com.rosegoldc.idea.debug;

import com.rosegoldc.idea.run.RoseGoldRunConfiguration;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;

public final class RoseGoldDebugRunner extends GenericProgramRunner<RunnerSettings> {

    public static final String ID = "RoseGoldDebug";

    @Override
    public @NotNull String getRunnerId() {
        return ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)
                && profile instanceof RoseGoldRunConfiguration;
    }

    @Override
    protected @NotNull RunContentDescriptor doExecute(
            @NotNull RunProfileState state,
            @NotNull ExecutionEnvironment environment
    ) throws ExecutionException {
        XDebugSession session = XDebuggerManager.getInstance(environment.getProject())
                .startSession(environment, new XDebugProcessStarter() {
                    @Override
                    public @NotNull XDebugProcess start(@NotNull XDebugSession session) throws ExecutionException {
                        return RoseGoldDebugProcess.create(
                                session,
                                (RoseGoldRunConfiguration) environment.getRunProfile()
                        );
                    }
                });
        return session.getRunContentDescriptor();
    }
}

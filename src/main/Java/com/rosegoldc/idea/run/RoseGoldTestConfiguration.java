package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGold;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.KillableColoredProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties;
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsActions;
import com.intellij.openapi.util.text.StringUtil;
import com.rosegoldc.lang.LangException;
import com.rosegoldc.lang.NativeRun;
import com.rosegoldc.lang.Run;
import com.rosegoldc.lang.Tests;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

public final class RoseGoldTestConfiguration extends LocatableConfigurationBase<RoseGoldTestConfigurationOptions> {

    private static final String FRAMEWORK = "RoseGold";

    RoseGoldTestConfiguration(
            @NotNull Project project,
            @NotNull RoseGoldTestConfigurationType type,
            @Nullable String name
    ) {
        super(project, type, name);
    }

    @Override
    protected @NotNull RoseGoldTestConfigurationOptions getOptions() {
        return (RoseGoldTestConfigurationOptions) super.getOptions();
    }

    @NotNull
    String getFilePath() {
        String path = getOptions().filePath;
        return path == null ? "" : path;
    }

    void setFilePath(@Nullable String path) {
        getOptions().filePath = path == null ? "" : path;
    }

    boolean isRunNative() {
        return getOptions().runNative;
    }

    void setRunNative(boolean runNative) {
        getOptions().runNative = runNative;
    }

    @Override
    public @NotNull String suggestedName() {
        String path = getFilePath();
        if (path.isEmpty()) {
            return "RoseGold Tests";
        }
        Path name = Path.of(path).getFileName();
        if (com.rosegoldc.lang.Project.isProjectFile(Path.of(path))) {
            try {
                String projectName = com.rosegoldc.lang.Project.load(Path.of(path)).name;
                if (!projectName.isEmpty()) {
                    return projectName + " Tests";
                }
            } catch (Exception ignored) {
            }
            return "RoseGold Tests";
        }
        return name == null ? "RoseGold Tests" : name.toString();
    }

    @Override
    public @NlsActions.ActionText @NotNull String getActionName() {
        return "Test " + suggestedName();
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        String path = getFilePath();
        if (StringUtil.isEmptyOrSpaces(path)) {
            if (getProject().getBasePath() == null) {
                throw new RuntimeConfigurationError("No project directory for the language suite");
            }
            return;
        }
        Path target = Path.of(path);
        if (!Files.exists(target)) {
            throw new RuntimeConfigurationError("Not found: " + path);
        }
        if (Files.isRegularFile(target) && com.rosegoldc.lang.Project.isProjectFile(target)) {
            try {
                com.rosegoldc.lang.Project project = com.rosegoldc.lang.Project.load(target);
                Path tests = project.testTarget();
                if (!Files.exists(tests)) {
                    String which = project.testEntry.isEmpty() ? "tests" : project.testEntry;
                    throw new RuntimeConfigurationError("profile.test.entry '" + which + "' not found");
                }
            } catch (LangException ex) {
                throw new RuntimeConfigurationError(ex.diagnostic.toHuman());
            } catch (IOException ex) {
                throw new RuntimeConfigurationError("cannot read " + path);
            }
            return;
        }
        if (Files.isRegularFile(target) && !path.toLowerCase().endsWith(".rg")) {
            throw new RuntimeConfigurationError("Not a RoseGold file: " + path);
        }
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new RoseGoldTestSettingsEditor(getProject());
    }

    @Override
    public @NotNull RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new CommandLineState(environment) {
            @Override
            protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                Path target = resolvedTarget();
                Path work = workDirectory(target);
                if (isRunNative() && DefaultRunExecutor.EXECUTOR_ID.equals(executor.getId())) {
                    Path nativeFile = nativeTestFile(target);
                    if (nativeFile != null) {
                        return startNative(nativeFile, work);
                    }
                    return startNativeSuite(target, work);
                }
                String label = target == null ? "language suite" : target.toString();
                RoseGoldInterpProcessHandler handler = new RoseGoldInterpProcessHandler(
                        "RoseGold test " + label,
                        h -> {
                            Run.Result result = Run.testPath(target, work, text ->
                                    h.notifyTextAvailable(text, ProcessOutputTypes.STDOUT));
                            if (!result.ok && !result.message.isEmpty() && !result.out.contains(result.message)) {
                                h.notifyTextAvailable(result.message + "\n", ProcessOutputTypes.STDERR);
                            }
                            if (result.ok && result.out.isEmpty() && !result.message.isEmpty()) {
                                h.notifyTextAvailable(result.message + "\n", ProcessOutputTypes.SYSTEM);
                            }
                            return result.exitCode;
                        }
                );
                ProcessTerminatedListener.attach(handler);
                return handler;
            }

            @Override
            protected @NotNull ConsoleView createConsole(@NotNull Executor exec) throws ExecutionException {
                Properties properties = new Properties(RoseGoldTestConfiguration.this, exec);
                return SMTestRunnerConnectionUtil.createConsole(FRAMEWORK, properties);
            }
        };
    }

    @Nullable
    Path resolvedTarget() throws ExecutionException {
        String path = getFilePath();
        if (StringUtil.isEmptyOrSpaces(path)) {
            return null;
        }
        Path target = Path.of(path);
        if (!Files.exists(target)) {
            throw new ExecutionException("Not found: " + path);
        }
        return target.toAbsolutePath().normalize();
    }

    @Nullable
    private Path workDirectory(@Nullable Path target) {
        String configured = getFilePath();
        if (!StringUtil.isEmptyOrSpaces(configured) && com.rosegoldc.lang.Project.isProjectFile(Path.of(configured))) {
            try {
                return com.rosegoldc.lang.Project.load(Path.of(configured)).dir;
            } catch (Exception ignored) {
            }
        }
        String base = getProject().getBasePath();
        if (base != null) {
            return Path.of(base);
        }
        if (target == null) {
            return null;
        }
        if (Files.isDirectory(target)) {
            return target;
        }
        return target.getParent();
    }

    @Nullable
    private Path nativeTestFile(@Nullable Path target) throws ExecutionException {
        if (target == null || Files.isDirectory(target)) {
            return null;
        }
        if (com.rosegoldc.lang.Project.isProjectFile(target)) {
            try {
                Path tests = com.rosegoldc.lang.Project.load(target).testTarget();
                if (Files.isRegularFile(tests) && tests.toString().toLowerCase().endsWith(".rg")) {
                    return tests.toAbsolutePath().normalize();
                }
            } catch (LangException ex) {
                throw new ExecutionException(ex.diagnostic.toHuman(), ex);
            } catch (IOException ex) {
                throw new ExecutionException("cannot read " + target, ex);
            }
            return null;
        }
        if (target.toString().toLowerCase().endsWith(".rg")) {
            return target;
        }
        return null;
    }

    @NotNull
    private ProcessHandler startNative(@NotNull Path file, @Nullable Path workDir) throws ExecutionException {
        NativeRun.Result linked;
        try {
            linked = NativeRun.linkFile(file, workDir, true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("native link interrupted", ex);
        } catch (IOException ex) {
            throw new ExecutionException(ex.getMessage() == null ? "native link failed" : ex.getMessage(), ex);
        }
        if (linked.exe == null) {
            String msg = linked.message.isEmpty() ? "llvm --test --run failed" : linked.message.trim();
            if (linked.ok) {
                RoseGoldInterpProcessHandler handler = new RoseGoldInterpProcessHandler(
                        "RoseGold test " + file,
                        h -> {
                            h.notifyTextAvailable(msg + "\n", ProcessOutputTypes.SYSTEM);
                            return 0;
                        }
                );
                ProcessTerminatedListener.attach(handler);
                return handler;
            }
            throw new ExecutionException(msg);
        }
        GeneralCommandLine cmd = new GeneralCommandLine(linked.exe.toAbsolutePath().toString());
        cmd.addParameter(file.toAbsolutePath().toString());
        if (workDir != null) {
            cmd.setWorkDirectory(workDir.toString());
        }
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(cmd);
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    @NotNull
    private ProcessHandler startNativeSuite(@Nullable Path target, @Nullable Path workDir) {
        String label = target == null ? "language suite" : target.toString();
        RoseGoldInterpProcessHandler handler = new RoseGoldInterpProcessHandler(
                "RoseGold native test " + label,
                h -> {
                    try {
                        Run.Result result = Run.testPath(target, workDir, text ->
                                h.notifyTextAvailable(text, ProcessOutputTypes.STDOUT), true);
                        if (!result.ok && !result.message.isEmpty() && !result.out.contains(result.message)) {
                            h.notifyTextAvailable(result.message + "\n", ProcessOutputTypes.STDERR);
                        }
                        if (result.ok && result.out.isEmpty() && !result.message.isEmpty()) {
                            h.notifyTextAvailable(result.message + "\n", ProcessOutputTypes.SYSTEM);
                        }
                        return result.exitCode;
                    } catch (IOException ex) {
                        h.notifyTextAvailable(ex.getMessage() + "\n", ProcessOutputTypes.STDERR);
                        return 1;
                    }
                }
        );
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    static final class Properties extends SMTRunnerConsoleProperties implements SMCustomMessagesParsing {
        Properties(@NotNull RoseGoldTestConfiguration configuration, @NotNull Executor executor) {
            super(configuration, FRAMEWORK, executor);
        }

        @Override
        public OutputToGeneralTestEventsConverter createTestEventsConverter(
                @NotNull String testFrameworkName,
                @NotNull TestConsoleProperties consoleProperties
        ) {
            String filePath = "";
            if (getConfiguration() instanceof RoseGoldTestConfiguration config) {
                filePath = config.getFilePath();
                if (!filePath.isEmpty() && com.rosegoldc.lang.Project.isProjectFile(Path.of(filePath))) {
                    try {
                        filePath = com.rosegoldc.lang.Project.load(Path.of(filePath)).testTarget().toString();
                    } catch (Exception ignored) {
                    }
                }
            }
            return new Converter(testFrameworkName, consoleProperties, filePath);
        }
    }

    static final class Converter extends OutputToGeneralTestEventsConverter {
        private final String filePath;

        Converter(
                @NotNull String testFrameworkName,
                @NotNull TestConsoleProperties consoleProperties,
                @NotNull String filePath
        ) {
            super(testFrameworkName, consoleProperties);
            this.filePath = filePath;
        }

        @Override
        protected boolean processServiceMessages(
                @NotNull String text,
                @NotNull Key<?> outputType,
                @NotNull ServiceMessageVisitor visitor
        ) throws ParseException {
            Tests.Result result = Tests.parseLine(text.stripTrailing());
            if (result == null) {
                return super.processServiceMessages(text, outputType, visitor);
            }
            var processor = getProcessor();
            if (processor != null) {
                String location = locationUrl(result.name);
                processor.onTestStarted(new TestStartedEvent(result.name, location));
                if (!result.passed) {
                    String message = result.message.isEmpty() ? "failed" : result.message;
                    processor.onTestFailure(new TestFailedEvent(result.name, message, null, true, null, null));
                }
                processor.onTestFinished(new TestFinishedEvent(result.name, 0L));
            }
            return false;
        }

        @Nullable
        private String locationUrl(@NotNull String name) {
            if (StringUtil.isEmptyOrSpaces(filePath) || !Files.isRegularFile(Path.of(filePath))) {
                if (Files.isRegularFile(Path.of(name))) {
                    return "file://" + Path.of(name).toAbsolutePath();
                }
                return null;
            }
            return "file://" + Path.of(filePath).toAbsolutePath() + "#" + name;
        }
    }
}

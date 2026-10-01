package com.rosegoldc.idea.run;

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
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsActions;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import com.rosegoldc.lang.LangException;
import com.rosegoldc.lang.NativeRun;
import com.rosegoldc.lang.Run;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RoseGoldRunConfiguration extends LocatableConfigurationBase<RoseGoldRunConfigurationOptions> {

    RoseGoldRunConfiguration(
            @NotNull Project project,
            @NotNull RoseGoldRunConfigurationType type,
            @Nullable String name
    ) {
        super(project, type, name);
    }

    @Override
    protected @NotNull RoseGoldRunConfigurationOptions getOptions() {
        return (RoseGoldRunConfigurationOptions) super.getOptions();
    }

    @NotNull
    String getFilePath() {
        String path = getOptions().filePath;
        return path == null ? "" : path;
    }

    void setFilePath(@Nullable String path) {
        getOptions().filePath = path == null ? "" : path;
    }

    @NotNull
    String getProgramArguments() {
        String args = getOptions().programArguments;
        return args == null ? "" : args;
    }

    void setProgramArguments(@Nullable String args) {
        getOptions().programArguments = args == null ? "" : args;
    }

    public boolean isStopOnEntry() {
        return getOptions().stopOnEntry;
    }

    void setStopOnEntry(boolean stopOnEntry) {
        getOptions().stopOnEntry = stopOnEntry;
    }

    public boolean isRunNative() {
        return getOptions().runNative;
    }

    void setRunNative(boolean runNative) {
        getOptions().runNative = runNative;
    }

    @Override
    public @NotNull String suggestedName() {
        String path = getFilePath();
        if (path.isEmpty()) {
            return "RoseGold";
        }
        Path file = Path.of(path);
        if (com.rosegoldc.lang.Project.isProjectFile(file)) {
            try {
                String name = com.rosegoldc.lang.Project.load(file).name;
                if (!name.isEmpty()) {
                    return name;
                }
            } catch (IOException | LangException ignored) {
            }
        }
        Path name = file.getFileName();
        return name == null ? "RoseGold" : name.toString();
    }

    @Override
    public @NlsActions.ActionText @NotNull String getActionName() {
        return "Run " + suggestedName();
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        String path = getFilePath();
        if (StringUtil.isEmptyOrSpaces(path)) {
            throw new RuntimeConfigurationError("No RoseGold file specified");
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new RuntimeConfigurationError("File not found: " + path);
        }
        if (com.rosegoldc.lang.Project.isProjectFile(file)) {
            try {
                com.rosegoldc.lang.Project project = com.rosegoldc.lang.Project.load(file);
                if (!Files.isRegularFile(project.entryFile)) {
                    throw new RuntimeConfigurationError("entry '" + project.entry + "' not found");
                }
            } catch (LangException ex) {
                throw new RuntimeConfigurationError(ex.diagnostic.toHuman());
            } catch (IOException ex) {
                throw new RuntimeConfigurationError("cannot read " + path);
            }
        }
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new RoseGoldRunSettingsEditor(getProject());
    }

    @Override
    public @NotNull RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new CommandLineState(environment) {
            @Override
            protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                Launch launch = launch();
                List<String> argv = programArgv(launch.file);
                if (isRunNative() && DefaultRunExecutor.EXECUTOR_ID.equals(executor.getId())) {
                    return startNative(launch, argv);
                }
                RoseGoldInterpProcessHandler handler = new RoseGoldInterpProcessHandler(
                        "RoseGold run " + launch.file,
                        h -> {
                            Run.Result result = Run.runFile(launch.file, argv, launch.workDir, text ->
                                    h.notifyTextAvailable(text, ProcessOutputTypes.STDOUT));
                            if (!result.ok && !result.message.isEmpty() && !result.out.contains(result.message)) {
                                h.notifyTextAvailable(result.message + "\n", ProcessOutputTypes.STDERR);
                            }
                            return result.exitCode;
                        }
                );
                ProcessTerminatedListener.attach(handler);
                return handler;
            }
        };
    }

    @NotNull
    public Path resolvedFile() throws ExecutionException {
        return launch().file;
    }

    @NotNull
    Launch launch() throws ExecutionException {
        String file = getFilePath();
        Path path = Path.of(file);
        if (StringUtil.isEmptyOrSpaces(file) || !Files.isRegularFile(path)) {
            throw new ExecutionException("RoseGold file not found: " + file);
        }
        path = path.toAbsolutePath().normalize();
        if (com.rosegoldc.lang.Project.isProjectFile(path)) {
            try {
                com.rosegoldc.lang.Project project = com.rosegoldc.lang.Project.load(path);
                if (!Files.isRegularFile(project.entryFile)) {
                    throw new ExecutionException("entry '" + project.entry + "' not found");
                }
                return new Launch(project.entryFile, project.dir);
            } catch (LangException ex) {
                throw new ExecutionException(ex.diagnostic.toHuman(), ex);
            } catch (IOException ex) {
                throw new ExecutionException("cannot read " + file, ex);
            }
        }
        return new Launch(path, workDirFor(path));
    }

    @NotNull
    public List<String> programArgv(@NotNull Path path) {
        List<String> argv = new ArrayList<>();
        argv.add(path.toString());
        String extra = getProgramArguments().trim();
        if (!extra.isEmpty()) {
            argv.addAll(ParametersListUtil.parse(extra, false, true));
        }
        return argv;
    }

    @Nullable
    public Path workDirectory(@NotNull Path path) {
        try {
            return launch().workDir;
        } catch (ExecutionException ignored) {
            return workDirFor(path);
        }
    }

    private Path workDirFor(@NotNull Path path) {
        Path parent = path.getParent();
        if (parent != null) {
            return parent;
        }
        String base = getProject().getBasePath();
        return base == null ? null : Path.of(base);
    }

    @NotNull
    private ProcessHandler startNative(@NotNull Launch launch, @NotNull List<String> argv) throws ExecutionException {
        NativeRun.Result linked;
        try {
            linked = NativeRun.linkFile(launch.file, launch.workDir);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("native link interrupted", ex);
        } catch (IOException ex) {
            throw new ExecutionException(ex.getMessage() == null ? "native link failed" : ex.getMessage(), ex);
        }
        if (!linked.ok || linked.exe == null) {
            String msg = linked.message.isEmpty() ? "llvm --link failed" : linked.message.trim();
            throw new ExecutionException(msg);
        }
        GeneralCommandLine cmd = new GeneralCommandLine(linked.exe.toAbsolutePath().toString());
        cmd.addParameters(argv);
        if (launch.workDir != null) {
            cmd.setWorkDirectory(launch.workDir.toString());
        }
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(cmd);
        ProcessTerminatedListener.attach(handler);
        return handler;
    }

    static final class Launch {
        final Path file;
        final Path workDir;

        Launch(Path file, Path workDir) {
            this.file = file;
            this.workDir = workDir;
        }
    }
}

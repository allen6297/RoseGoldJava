package com.rosegoldc.idea.debug;

import com.rosegoldc.idea.RoseGold;
import com.rosegoldc.idea.run.RoseGoldRunConfiguration;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.rosegoldc.lang.Debug;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

final class RoseGoldDebugProcess extends XDebugProcess {

    private final Handler processHandler;
    private final Debug debug;
    private final Map<String, List<XLineBreakpoint<XBreakpointProperties<?>>>> lineBreaks = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile ConsoleView console;

    static @NotNull RoseGoldDebugProcess create(
            @NotNull XDebugSession session,
            @NotNull RoseGoldRunConfiguration configuration
    ) throws ExecutionException {
        Path file = configuration.resolvedFile();
        return new RoseGoldDebugProcess(session, configuration, file);
    }

    private RoseGoldDebugProcess(
            @NotNull XDebugSession session,
            @NotNull RoseGoldRunConfiguration configuration,
            @NotNull Path file
    ) {
        super(session);
        this.processHandler = new Handler("RoseGold debug " + file);
        this.debug = new Debug(
                file,
                configuration.programArgv(file),
                configuration.workDirectory(file),
                new Debug.Listener() {
                    @Override
                    public void onPaused(String reason) {
                        List<Debug.Frame> frames = debug.stack();
                        XSuspendContext context = new SuspendContext(toXFrames(frames));
                        ApplicationManager.getApplication().invokeLater(() -> getSession().positionReached(context));
                    }

                    @Override
                    public void onOutput(String text, boolean error) {
                        print(text, error);
                    }

                    @Override
                    public void onExited(int code, String message) {
                        if (message != null && !message.isEmpty()) {
                            print(message.endsWith("\n") ? message : message + "\n", code != 0);
                        }
                        processHandler.terminated(code);
                    }
                }
        );
        debug.setStopOnEntry(configuration.isStopOnEntry());
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return RoseGoldDebuggerEditorsProvider.INSTANCE;
    }

    @Override
    protected @Nullable ProcessHandler doGetProcessHandler() {
        return processHandler;
    }

    @Override
    public @NotNull ExecutionConsole createConsole() {
        ExecutionConsole created = super.createConsole();
        if (created instanceof ConsoleView view) {
            console = view;
        }
        return created;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return new XBreakpointHandler<?>[]{new BreakpointHandler()};
    }

    @Override
    public void sessionInitialized() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                sendAllBreakpoints();
                debug.start();
            } catch (Exception ex) {
                print(messageOf(ex), true);
                getSession().stop();
            }
        });
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        debug.resume(Debug.Mode.Next);
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        debug.resume(Debug.Mode.StepIn);
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        debug.resume(Debug.Mode.StepOut);
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        debug.resume(Debug.Mode.Run);
    }

    @Override
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        debug.abort();
    }

    private void sendAllBreakpoints() {
        for (Map.Entry<String, List<XLineBreakpoint<XBreakpointProperties<?>>>> entry : copyBreakpoints().entrySet()) {
            sendBreakpoints(entry.getKey(), entry.getValue());
        }
    }

    private void sendBreakpoints(
            @NotNull String path,
            @NotNull List<XLineBreakpoint<XBreakpointProperties<?>>> list
    ) {
        List<Debug.Breakpoint> requested = new ArrayList<>();
        for (XLineBreakpoint<XBreakpointProperties<?>> breakpoint : list) {
            Debug.Breakpoint bp = new Debug.Breakpoint();
            bp.line = breakpoint.getLine() + 1;
            if (breakpoint.getConditionExpression() != null) {
                String condition = breakpoint.getConditionExpression().getExpression();
                if (!condition.isBlank()) {
                    bp.condition = condition;
                }
            }
            requested.add(bp);
        }
        debug.setBreakpoints(path, requested);
    }

    private @NotNull Map<String, List<XLineBreakpoint<XBreakpointProperties<?>>>> copyBreakpoints() {
        Map<String, List<XLineBreakpoint<XBreakpointProperties<?>>>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<XLineBreakpoint<XBreakpointProperties<?>>>> entry : lineBreaks.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private List<XStackFrame> toXFrames(List<Debug.Frame> frames) {
        List<XStackFrame> out = new ArrayList<>();
        for (Debug.Frame frame : frames) {
            out.add(new Frame(frame));
        }
        return out;
    }

    private void print(@NotNull String text, boolean error) {
        if (text.isEmpty()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            ConsoleView view = console;
            if (view != null) {
                view.print(text, error ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
            } else {
                processHandler.notifyTextAvailable(text, error ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT);
            }
        });
    }

    private static @NotNull String messageOf(@NotNull Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank() ? cause.toString() : message) + "\n";
    }

    private final class BreakpointHandler extends XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<?>>> {
        private BreakpointHandler() {
            super(RoseGoldLineBreakpointType.class);
        }

        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
            String path = pathOf(breakpoint);
            if (path == null) {
                return;
            }
            lineBreaks.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).add(breakpoint);
            sync(path);
        }

        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint, boolean temporary) {
            String path = pathOf(breakpoint);
            if (path == null) {
                return;
            }
            List<XLineBreakpoint<XBreakpointProperties<?>>> list = lineBreaks.get(path);
            if (list != null) {
                list.remove(breakpoint);
            }
            sync(path);
        }

        private void sync(@NotNull String path) {
            sendBreakpoints(path, lineBreaks.getOrDefault(path, List.of()));
        }
    }

    private static @Nullable String pathOf(@NotNull XLineBreakpoint<XBreakpointProperties<?>> breakpoint) {
        XSourcePosition position = breakpoint.getSourcePosition();
        VirtualFile file = position == null ? null : position.getFile();
        String path = file != null ? file.getPath() : breakpoint.getPresentableFilePath();
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return path;
        }
    }

    private static final class SuspendContext extends XSuspendContext {
        private final ExecutionStack stack;

        private SuspendContext(@NotNull List<XStackFrame> frames) {
            this.stack = new ExecutionStack(frames);
        }

        @Override
        public @NotNull XExecutionStack getActiveExecutionStack() {
            return stack;
        }

        @Override
        public XExecutionStack @NotNull [] getExecutionStacks() {
            return new XExecutionStack[]{stack};
        }
    }

    private static final class ExecutionStack extends XExecutionStack {
        private final List<XStackFrame> frames;

        private ExecutionStack(@NotNull List<XStackFrame> frames) {
            super("main");
            this.frames = frames;
        }

        @Override
        public @Nullable XStackFrame getTopFrame() {
            return frames.isEmpty() ? null : frames.getFirst();
        }

        @Override
        public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
            if (firstFrameIndex >= frames.size()) {
                container.addStackFrames(List.of(), true);
                return;
            }
            container.addStackFrames(frames.subList(firstFrameIndex, frames.size()), true);
        }
    }

    private final class Frame extends XStackFrame {
        private final int id;
        private final String name;
        private final XSourcePosition position;

        private Frame(@NotNull Debug.Frame frame) {
            this.id = frame.id;
            this.name = frame.name;
            String path = frame.path.replace('\\', '/');
            int line = Math.max(0, frame.line - 1);
            VirtualFile file = path.isEmpty() ? null : LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
            this.position = file == null ? null : XDebuggerUtil.getInstance().createPosition(file, line);
        }

        @Override
        public @Nullable XSourcePosition getSourcePosition() {
            return position;
        }

        @Override
        public void customizePresentation(@NotNull com.intellij.ui.ColoredTextContainer component) {
            component.append(name.isEmpty() ? "frame" : name, com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (position != null) {
                component.append(
                        ":" + (position.getLine() + 1),
                        com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES
                );
            }
        }

        @Override
        public @NotNull XDebuggerEvaluator getEvaluator() {
            return new XDebuggerEvaluator() {
                @Override
                public void evaluate(
                        @NotNull String expression,
                        @NotNull XEvaluationCallback callback,
                        @Nullable XSourcePosition expressionPosition
                ) {
                    callback.evaluated(new Value(expression, debug.evaluate(expression), "Value", id, true));
                }
            };
        }

        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            XValueChildrenList children = new XValueChildrenList();
            for (Debug.Variable variable : debug.locals(id)) {
                children.add(new Value(variable.name, variable.value, variable.type, id, variable.readOnly));
            }
            node.addChildren(children, true);
        }
    }

    private final class Value extends XNamedValue {
        private final String value;
        private final String type;
        private final int frameId;
        private final boolean readOnly;

        private Value(
                @NotNull String name,
                @NotNull String value,
                @NotNull String type,
                int frameId,
                boolean readOnly
        ) {
            super(name);
            this.value = value;
            this.type = type;
            this.frameId = frameId;
            this.readOnly = readOnly;
        }

        @Override
        public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
            node.setPresentation(
                    readOnly ? AllIcons.Nodes.Constant : AllIcons.Nodes.Variable,
                    type.isEmpty() ? null : type,
                    value,
                    false
            );
        }

        @Override
        public @NotNull String getEvaluationExpression() {
            return getName();
        }

        @Override
        public @Nullable XValueModifier getModifier() {
            if (readOnly) {
                return null;
            }
            return new XValueModifier() {
                @Override
                public void setValue(@NotNull XExpression expression, @NotNull XModificationCallback callback) {
                    String err = debug.setVariable(frameId, getName(), expression.getExpression());
                    if (err.isEmpty()) {
                        callback.valueModified();
                    } else {
                        callback.errorOccurred(err);
                    }
                }

                @Override
                public void calculateInitialValueEditorText(XInitialValueCallback callback) {
                    callback.setValue(value);
                }
            };
        }
    }

    private final class Handler extends ProcessHandler {
        private final String commandLine;
        private final AtomicBoolean notified = new AtomicBoolean();

        private Handler(@NotNull String commandLine) {
            this.commandLine = commandLine;
        }

        @Override
        public void startNotify() {
            notifyTextAvailable(commandLine + "\n", ProcessOutputTypes.SYSTEM);
            super.startNotify();
        }

        @Override
        protected void destroyProcessImpl() {
            debug.abort();
            terminated(1);
        }

        @Override
        protected void detachProcessImpl() {
            destroyProcessImpl();
        }

        @Override
        public boolean detachIsDefault() {
            return false;
        }

        @Override
        public @Nullable OutputStream getProcessInput() {
            return null;
        }

        void terminated(int code) {
            if (notified.compareAndSet(false, true)) {
                notifyProcessTerminated(code);
            }
        }
    }
}

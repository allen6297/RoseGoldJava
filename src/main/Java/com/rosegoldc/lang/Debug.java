package com.rosegoldc.lang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Debug {

    public enum Mode { Run, Next, StepIn, StepOut }

    public static final class Frame {
        public final int id;
        public final String name;
        public final String path;
        public final int line;
        public final int col;

        Frame(int id, String name, String path, int line, int col) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
            this.line = line;
            this.col = col;
        }
    }

    public static final class Variable {
        public final String name;
        public final String value;
        public final String type;
        public final boolean readOnly;

        Variable(String name, String value, String type, boolean readOnly) {
            this.name = name;
            this.value = value;
            this.type = type;
            this.readOnly = readOnly;
        }
    }

    public static final class Breakpoint {
        public int line;
        public String condition = "";
        public String hitCondition = "";
        public boolean verified = true;
        public String message = "";
    }

    public interface Listener {
        void onPaused(String reason);

        void onOutput(String text, boolean error);

        void onExited(int code, String message);
    }

    private final Path file;
    private final List<String> argv;
    private final Path workDir;
    private final Listener listener;
    private boolean stopOnEntry = true;
    private volatile Interp interp;
    private final Object interpLock = new Object();

    public Debug(Path file, List<String> argv, Path workDir, Listener listener) {
        this.file = file;
        this.argv = argv == null ? List.of() : new ArrayList<>(argv);
        this.workDir = workDir;
        this.listener = listener;
    }

    public void setStopOnEntry(boolean stopOnEntry) {
        this.stopOnEntry = stopOnEntry;
    }

    public List<Breakpoint> setBreakpoints(String path, List<Breakpoint> requested) {
        List<Interp.DebugBreakpoint> bps = new ArrayList<>();
        List<Breakpoint> out = new ArrayList<>();
        if (requested != null) {
            for (Breakpoint req : requested) {
                Interp.DebugBreakpoint db = new Interp.DebugBreakpoint();
                db.line = req.line;
                db.condition = req.condition == null ? "" : req.condition;
                boolean hitOk = Interp.parseDebugHit(req.hitCondition == null ? "" : req.hitCondition, db);
                Breakpoint result = new Breakpoint();
                result.line = req.line;
                result.condition = db.condition;
                result.hitCondition = db.hitCondition;
                result.verified = req.line > 0 && hitOk;
                if (!hitOk) {
                    result.message = "invalid hit count";
                } else if (!db.condition.isEmpty() || !db.hitCondition.isEmpty()) {
                    String msg = "";
                    if (!db.condition.isEmpty()) {
                        msg = "condition: " + db.condition;
                    }
                    if (!db.hitCondition.isEmpty()) {
                        if (!msg.isEmpty()) {
                            msg += "; ";
                        }
                        msg += "hit: " + db.hitCondition;
                    }
                    result.message = msg;
                }
                if (db.line > 0) {
                    bps.add(db);
                }
                out.add(result);
            }
        }
        String norm = Interp.debugNormPath(abs(path));
        synchronized (interpLock) {
            Interp current = interp;
            List<Interp.DebugBreakpoint> old = current != null
                    ? current.debug.breakpoints.get(norm)
                    : pending.get(norm);
            if (old != null) {
                for (Interp.DebugBreakpoint db : bps) {
                    for (Interp.DebugBreakpoint prev : old) {
                        if (prev.line == db.line) {
                            db.hits = prev.hits;
                            break;
                        }
                    }
                }
            }
            if (current != null) {
                current.debug.breakpoints.put(norm, bps);
            } else {
                pending.put(norm, bps);
            }
        }
        return out;
    }

    private final Map<String, List<Interp.DebugBreakpoint>> pending = new LinkedHashMap<>();

    public void start() {
        String program = file.toAbsolutePath().normalize().toString();
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            List<Diagnostic> diags = new ArrayList<>();
            Program parsed = Parser.parseSource(source, program, diags);
            if (!diags.isEmpty()) {
                Diagnostic d = diags.getFirst();
                fail(Diagnostic.locatedError(d.kind.isEmpty() ? "parse error" : d.kind,
                        d.file.isEmpty() ? program : d.file, d.line, d.col, d.message));
                return;
            }
            Checker checker = Checker.check(parsed, program, diags);
            if (!diags.isEmpty()) {
                Diagnostic d = diags.getFirst();
                fail(Diagnostic.locatedError(d.kind.isEmpty() ? "error" : d.kind,
                        d.file.isEmpty() ? program : d.file, d.line, d.col, d.message));
                return;
            }
            Interp local = new Interp(checker, argv.isEmpty() ? List.of(program) : argv);
            if (workDir != null) {
                local.sandboxRoot = workDir.toAbsolutePath().normalize();
            }
            local.output = text -> listener.onOutput(text, false);
            local.debug.enabled = true;
            local.debug.stopOnEntry = stopOnEntry;
            synchronized (interpLock) {
                local.debug.breakpoints.putAll(pending);
                pending.clear();
                interp = local;
            }
            local.debug.pauseAndWait = local::debugPauseAndWait;
            local.debug.onPause = listener::onPaused;
            if (parsed.fns.stream().anyMatch(fn -> fn.name.equals("main"))) {
                local.callNamed("main");
            }
            listener.onExited(0, "");
        } catch (Interp.DebugAbort ignored) {
            listener.onExited(0, "");
        } catch (Exception ex) {
            fail(ex.getMessage() == null ? "runtime error" : ex.getMessage());
        } finally {
            synchronized (interpLock) {
                interp = null;
            }
        }
    }

    public void resume(Mode mode) {
        Interp current = interp;
        if (current == null) {
            return;
        }
        Interp.DebugState.Mode nativeMode = switch (mode) {
            case Next -> Interp.DebugState.Mode.Next;
            case StepIn -> Interp.DebugState.Mode.StepIn;
            case StepOut -> Interp.DebugState.Mode.StepOut;
            case Run -> Interp.DebugState.Mode.Run;
        };
        current.debugResume(nativeMode);
    }

    public void abort() {
        Interp current = interp;
        if (current != null) {
            current.debugAbort();
        }
    }

    public List<Frame> stack() {
        Interp current = interp;
        if (current == null) {
            return List.of();
        }
        List<Frame> frames = new ArrayList<>();
        List<Interp.DebugFrame> stack = current.debug.stack;
        for (int i = 0; i < stack.size(); i++) {
            int idx = stack.size() - 1 - i;
            Interp.DebugFrame fr = stack.get(idx);
            frames.add(new Frame(idx + 1, fr.name, fr.path, fr.line, fr.col));
        }
        return frames;
    }

    public List<Variable> locals(int frameId) {
        Interp current = interp;
        if (current == null || frameId < 1 || frameId > current.debug.stack.size()) {
            return List.of();
        }
        Interp.DebugFrame fr = current.debug.stack.get(frameId - 1);
        List<Variable> vars = new ArrayList<>();
        for (Map.Entry<String, Value.Binding> kv : current.localsAt(fr.envIndex)) {
            vars.add(new Variable(
                    kv.getKey(),
                    kv.getValue().value.toPrintString(),
                    "Value",
                    kv.getValue().isConst
            ));
        }
        return vars;
    }

    public String evaluate(String expression) {
        Interp current = interp;
        if (current == null) {
            return "";
        }
        String[] err = {""};
        Value v = current.debugEval(expression == null ? "" : expression, err);
        if (!err[0].isEmpty()) {
            return err[0];
        }
        return v.toPrintString();
    }

    public String setVariable(int frameId, String name, String value) {
        Interp current = interp;
        if (current == null) {
            return "not paused";
        }
        if (frameId < 1 || frameId > current.debug.stack.size()) {
            return "invalid frame";
        }
        Interp.DebugFrame fr = current.debug.stack.get(frameId - 1);
        String[] err = {""};
        if (!current.debugSetVariable(fr.envIndex, name, value, err)) {
            return err[0].isEmpty() ? "cannot set variable" : err[0];
        }
        return "";
    }

    private void fail(String message) {
        String text = message == null || message.isEmpty() ? "runtime error" : message;
        listener.onOutput(text + "\n", true);
        listener.onExited(1, text);
    }

    private static String abs(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            return path;
        }
    }
}

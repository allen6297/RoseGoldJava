package com.rosegoldc.lang;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NativeDebug implements DebugEngine {

    private final Path file;
    private final List<String> argv;
    private final Path workDir;
    private final Debug.Listener listener;
    private boolean stopOnEntry = true;
    private final Map<String, List<Debug.Breakpoint>> pending = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final CountDownLatch done = new CountDownLatch(1);
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rg-native-debug");
        t.setDaemon(true);
        return t;
    });
    private volatile DapClient client;
    private volatile int threadId = 1;
    private volatile List<Debug.Frame> frames = List.of();
    private volatile int exitCode = 0;
    private final AtomicInteger exiting = new AtomicInteger();

    public NativeDebug(Path file, List<String> argv, Path workDir, Debug.Listener listener) {
        this.file = file;
        this.argv = argv == null ? List.of() : new ArrayList<>(argv);
        this.workDir = workDir;
        this.listener = listener;
    }

    public static boolean available() {
        return missingReason().isEmpty();
    }

    public static String missingReason() {
        if (LlvmLink.findClang() == null) {
            return "native Debug needs clang. Uncheck Run natively or install LLVM.";
        }
        if (!LlvmLink.canRunClang()) {
            return "native Debug cannot launch clang (Application Control / WDAC). Uncheck Run natively or allow clang.";
        }
        if (LlvmLink.findLldbDap() == null) {
            return "native Debug needs lldb-dap next to clang. Uncheck Run natively or install LLVM with LLDB.";
        }
        if (!canRunLldbDap()) {
            return "native Debug: lldb-dap failed to start (install Python 3 so python3.dll is on PATH, or uncheck Run natively).";
        }
        return "";
    }

    private static Boolean lldbDapCanRun;

    static boolean canRunLldbDap() {
        if (lldbDapCanRun != null) {
            return lldbDapCanRun;
        }
        Path adapter = LlvmLink.findLldbDap();
        if (adapter == null) {
            lldbDapCanRun = false;
            return false;
        }
        DapClient client = null;
        try {
            client = new DapClient(adapter);
            Map<String, Object> init = new LinkedHashMap<>();
            init.put("clientID", "rosegold");
            init.put("adapterID", "lldb-dap");
            init.put("pathFormat", "path");
            init.put("linesStartAt1", true);
            init.put("columnsStartAt1", true);
            client.request("initialize", init, 8);
            lldbDapCanRun = true;
        } catch (Exception ex) {
            lldbDapCanRun = false;
        } finally {
            if (client != null) {
                client.close();
            }
        }
        return lldbDapCanRun;
    }

    @Override
    public void setStopOnEntry(boolean stopOnEntry) {
        this.stopOnEntry = stopOnEntry;
    }

    @Override
    public List<Debug.Breakpoint> setBreakpoints(String path, List<Debug.Breakpoint> requested) {
        List<Debug.Breakpoint> out = requested == null ? List.of() : new ArrayList<>(requested);
        String abs = abs(path);
        synchronized (lock) {
            pending.put(abs, out);
            DapClient dap = client;
            if (dap != null) {
                try {
                    return sendBreakpoints(dap, abs, out);
                } catch (Exception ex) {
                    for (Debug.Breakpoint bp : out) {
                        bp.verified = false;
                        bp.message = ex.getMessage() == null ? "setBreakpoints failed" : ex.getMessage();
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void start() {
        try {
            NativeRun.Result linked = NativeRun.linkFile(file, workDir);
            if (!linked.ok || linked.exe == null) {
                fail(linked.message.isEmpty() ? "llvm --link failed" : linked.message);
                return;
            }
            Path adapter = LlvmLink.findLldbDap();
            if (adapter == null) {
                fail("native Debug needs lldb-dap next to clang. Uncheck Run natively or install LLVM with LLDB.");
                return;
            }
            DapClient dap = new DapClient(adapter);
            dap.setOnEvent(this::onEvent);
            synchronized (lock) {
                client = dap;
            }
            Map<String, Object> init = new LinkedHashMap<>();
            init.put("clientID", "rosegold");
            init.put("clientName", "RoseGold");
            init.put("adapterID", "lldb-dap");
            init.put("pathFormat", "path");
            init.put("linesStartAt1", true);
            init.put("columnsStartAt1", true);
            init.put("supportsRunInTerminalRequest", false);
            dap.request("initialize", init);
            Map<String, Object> launch = new LinkedHashMap<>();
            launch.put("program", linked.exe.toAbsolutePath().normalize().toString());
            launch.put("args", argv);
            if (workDir != null) {
                launch.put("cwd", workDir.toAbsolutePath().normalize().toString());
            }
            launch.put("stopOnEntry", stopOnEntry);
            dap.request("launch", launch, 60);
            try {
                dap.waitEvent();
            } catch (IOException ignored) {
            }
            Map<String, List<Debug.Breakpoint>> snapshot;
            synchronized (lock) {
                snapshot = new LinkedHashMap<>(pending);
            }
            for (Map.Entry<String, List<Debug.Breakpoint>> entry : snapshot.entrySet()) {
                sendBreakpoints(dap, entry.getKey(), entry.getValue());
            }
            dap.request("configurationDone", Map.of());
            if (!done.await(10, TimeUnit.MINUTES)) {
                fail("native debug timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("native debug interrupted");
        } catch (Exception ex) {
            fail(ex.getMessage() == null ? "native debug failed" : ex.getMessage());
        } finally {
            closeClient();
            events.shutdownNow();
        }
    }

    @Override
    public void resume(Debug.Mode mode) {
        DapClient dap = client;
        if (dap == null) {
            return;
        }
        String command = switch (mode) {
            case Next -> "next";
            case StepIn -> "stepIn";
            case StepOut -> "stepOut";
            case Run -> "continue";
        };
        events.execute(() -> {
            try {
                dap.request(command, Map.of("threadId", threadId));
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void abort() {
        finish(0, "");
    }

    @Override
    public List<Debug.Frame> stack() {
        return frames;
    }

    @Override
    public List<Debug.Variable> locals(int frameId) {
        DapClient dap = client;
        if (dap == null) {
            return List.of();
        }
        try {
            Map<String, Object> body = dap.request("scopes", Map.of("frameId", frameId));
            int ref = 0;
            for (Object raw : DapJson.array(body.get("scopes"))) {
                Map<String, Object> scope = DapJson.object(raw);
                int next = DapJson.integer(scope.get("variablesReference"), 0);
                if (next <= 0) {
                    continue;
                }
                String name = DapJson.str(scope.get("name")).toLowerCase();
                if (name.contains("local") || ref == 0) {
                    ref = next;
                }
                if (name.contains("local")) {
                    break;
                }
            }
            if (ref <= 0) {
                return List.of();
            }
            Map<String, Object> vars = dap.request("variables", Map.of("variablesReference", ref));
            List<Debug.Variable> out = new ArrayList<>();
            for (Object raw : DapJson.array(vars.get("variables"))) {
                Map<String, Object> v = DapJson.object(raw);
                out.add(new Debug.Variable(
                        DapJson.str(v.get("name")),
                        DapJson.str(v.get("value")),
                        DapJson.str(v.get("type")),
                        DapJson.bool(v.get("readOnly"))
                ));
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    @Override
    public String evaluate(String expression) {
        DapClient dap = client;
        if (dap == null) {
            return "";
        }
        int frameId = frames.isEmpty() ? 0 : frames.getFirst().id;
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("expression", expression == null ? "" : expression);
            args.put("context", "watch");
            if (frameId > 0) {
                args.put("frameId", frameId);
            }
            Map<String, Object> body = dap.request("evaluate", args);
            String result = DapJson.str(body.get("result"));
            return result.isEmpty() ? DapJson.str(body.get("message")) : result;
        } catch (Exception ex) {
            return ex.getMessage() == null ? "evaluate failed" : ex.getMessage();
        }
    }

    @Override
    public String setVariable(int frameId, String name, String value) {
        DapClient dap = client;
        if (dap == null) {
            return "not paused";
        }
        try {
            Map<String, Object> body = dap.request("scopes", Map.of("frameId", frameId));
            int ref = 0;
            for (Object raw : DapJson.array(body.get("scopes"))) {
                Map<String, Object> scope = DapJson.object(raw);
                int next = DapJson.integer(scope.get("variablesReference"), 0);
                if (next > 0) {
                    ref = next;
                    break;
                }
            }
            if (ref <= 0) {
                return "no locals";
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("variablesReference", ref);
            args.put("name", name == null ? "" : name);
            args.put("value", value == null ? "" : value);
            dap.request("setVariable", args);
            return "";
        } catch (Exception ex) {
            return ex.getMessage() == null ? "cannot set variable" : ex.getMessage();
        }
    }

    private void onEvent(Map<String, Object> msg) {
        String event = DapJson.str(msg.get("event"));
        Map<String, Object> body = DapJson.object(msg.get("body"));
        if ("stopped".equals(event)) {
            int tid = DapJson.integer(body.get("threadId"), threadId);
            if (tid > 0) {
                threadId = tid;
            }
            String reason = DapJson.str(body.get("reason"));
            events.execute(() -> {
                refreshStack();
                listener.onPaused(reason.isEmpty() ? "pause" : reason);
            });
        } else if ("output".equals(event)) {
            String text = DapJson.str(body.get("output"));
            String category = DapJson.str(body.get("category"));
            listener.onOutput(text, "stderr".equals(category) || "important".equals(category));
        } else if ("exited".equals(event)) {
            exitCode = DapJson.integer(body.get("exitCode"), 0);
            int code = exitCode;
            events.execute(() -> finish(code, ""));
        } else if ("terminated".equals(event)) {
            int code = exitCode;
            events.execute(() -> finish(code, ""));
        }
    }

    private void refreshStack() {
        DapClient dap = client;
        if (dap == null) {
            frames = List.of();
            return;
        }
        try {
            Map<String, Object> body = dap.request("stackTrace", Map.of(
                    "threadId", threadId,
                    "startFrame", 0,
                    "levels", 64
            ));
            List<Debug.Frame> all = new ArrayList<>();
            List<Debug.Frame> rg = new ArrayList<>();
            for (Object raw : DapJson.array(body.get("stackFrames"))) {
                Map<String, Object> fr = DapJson.object(raw);
                Map<String, Object> source = DapJson.object(fr.get("source"));
                String path = DapJson.str(source.get("path"));
                String name = DapJson.str(fr.get("name"));
                Debug.Frame frame = new Debug.Frame(
                        DapJson.integer(fr.get("id"), 0),
                        name,
                        path,
                        DapJson.integer(fr.get("line"), 0),
                        DapJson.integer(fr.get("column"), 1)
                );
                all.add(frame);
                String lower = path.toLowerCase();
                if (lower.endsWith(".rg") || name.contains("rg_fn_")) {
                    rg.add(frame);
                }
            }
            frames = rg.isEmpty() ? List.copyOf(all) : List.copyOf(rg);
        } catch (Exception ex) {
            frames = List.of();
        }
    }

    private List<Debug.Breakpoint> sendBreakpoints(DapClient dap, String path, List<Debug.Breakpoint> requested)
            throws IOException, InterruptedException {
        List<Map<String, Object>> bps = new ArrayList<>();
        if (requested != null) {
            for (Debug.Breakpoint bp : requested) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("line", bp.line);
                if (bp.condition != null && !bp.condition.isEmpty()) {
                    item.put("condition", bp.condition);
                }
                if (bp.hitCondition != null && !bp.hitCondition.isEmpty()) {
                    item.put("hitCondition", bp.hitCondition);
                }
                bps.add(item);
            }
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("path", path);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("source", source);
        args.put("breakpoints", bps);
        Map<String, Object> body = dap.request("setBreakpoints", args);
        List<Object> result = DapJson.array(body.get("breakpoints"));
        List<Debug.Breakpoint> out = new ArrayList<>();
        for (int i = 0; i < (requested == null ? 0 : requested.size()); i++) {
            Debug.Breakpoint bp = requested.get(i);
            if (i < result.size()) {
                Map<String, Object> got = DapJson.object(result.get(i));
                bp.verified = DapJson.bool(got.get("verified"));
                int line = DapJson.integer(got.get("line"), bp.line);
                if (line > 0) {
                    bp.line = line;
                }
                String message = DapJson.str(got.get("message"));
                if (!message.isEmpty()) {
                    bp.message = message;
                }
            }
            out.add(bp);
        }
        return out;
    }

    private void fail(String message) {
        String text = message == null || message.isEmpty() ? "native debug failed" : message;
        listener.onOutput(text.endsWith("\n") ? text : text + "\n", true);
        finish(1, text);
    }

    private void finish(int code, String message) {
        if (!exiting.compareAndSet(0, 1)) {
            done.countDown();
            return;
        }
        try {
            listener.onExited(code, message);
        } finally {
            done.countDown();
            closeClient();
        }
    }

    private void closeClient() {
        DapClient dap;
        synchronized (lock) {
            dap = client;
            client = null;
        }
        if (dap != null) {
            try {
                dap.request("disconnect", Map.of("terminateDebuggee", true), 3);
            } catch (Exception ignored) {
            }
            dap.close();
        }
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

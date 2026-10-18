package com.rosegoldc.lang;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class DapClient {

    private final Process proc;
    private final OutputStream stdin;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<Integer, Waiter> pending = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> events = new ConcurrentHashMap<>();
    private volatile Consumer<Map<String, Object>> onEvent = ignored -> {
    };
    private volatile String lastError = "";

    private final StringBuilder stderr = new StringBuilder();

    DapClient(Path adapter) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(adapter.toString());
        pb.redirectErrorStream(false);
        if (adapter.getParent() != null) {
            pb.directory(adapter.getParent().toFile());
        }
        this.proc = pb.start();
        this.stdin = proc.getOutputStream();
        Thread reader = new Thread(this::readLoop, "rg-dap");
        Thread err = new Thread(() -> drainErr(proc.getErrorStream()), "rg-dap-err");
        reader.setDaemon(true);
        err.setDaemon(true);
        reader.start();
        err.start();
    }

    void setOnEvent(Consumer<Map<String, Object>> onEvent) {
        this.onEvent = onEvent == null ? ignored -> {
        } : onEvent;
    }

    Map<String, Object> request(String command, Map<String, Object> arguments) throws IOException, InterruptedException {
        return request(command, arguments, 30);
    }

    Map<String, Object> request(String command, Map<String, Object> arguments, int timeoutSec)
            throws IOException, InterruptedException {
        int id = seq.getAndIncrement();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("seq", id);
        msg.put("type", "request");
        msg.put("command", command);
        if (arguments != null) {
            msg.put("arguments", arguments);
        }
        Waiter waiter = new Waiter();
        pending.put(id, waiter);
        send(msg);
        if (!waiter.latch.await(timeoutSec, TimeUnit.SECONDS)) {
            pending.remove(id);
            throw new IOException("dap " + command + " timed out" + stderrSuffix());
        }
        if (waiter.error != null) {
            throw new IOException(waiter.error + stderrSuffix());
        }
        return waiter.body == null ? Map.of() : waiter.body;
    }

    void waitEvent() throws InterruptedException, IOException {
        CountDownLatch latch = events.computeIfAbsent("initialized", ignored -> new CountDownLatch(1));
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IOException("dap event " + "initialized" + " timed out");
        }
    }

    void close() {
        proc.destroyForcibly();
        try {
            proc.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(Map<String, Object> msg) throws IOException {
        byte[] body = DapJson.encode(msg).getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        synchronized (stdin) {
            stdin.write(header.getBytes(StandardCharsets.US_ASCII));
            stdin.write(body);
            stdin.flush();
        }
    }

    private void readLoop() {
        try {
            InputStream in = proc.getInputStream();
            while (true) {
                String raw = readMessage(in);
                if (raw == null) {
                    return;
                }
                Object decoded = DapJson.decode(raw);
                Map<String, Object> msg = DapJson.object(decoded);
                String type = DapJson.str(msg.get("type"));
                if ("response".equals(type)) {
                    int id = DapJson.integer(msg.get("request_seq"), 0);
                    Waiter waiter = pending.remove(id);
                    if (waiter == null) {
                        continue;
                    }
                    if (!DapJson.bool(msg.get("success"))) {
                        waiter.error = DapJson.str(msg.get("message"));
                        if (waiter.error.isEmpty()) {
                            waiter.error = "dap " + DapJson.str(msg.get("command")) + " failed";
                        }
                    } else {
                        Object body = msg.get("body");
                        waiter.body = body instanceof Map<?, ?> ? DapJson.object(body) : Map.of("value", body);
                    }
                    waiter.latch.countDown();
                } else if ("event".equals(type)) {
                    String event = DapJson.str(msg.get("event"));
                    events.computeIfAbsent(event, ignored -> new CountDownLatch(1)).countDown();
                    onEvent.accept(msg);
                } else if ("request".equals(type)) {
                    replyReverse(msg);
                }
            }
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? "dap read failed" : ex.getMessage();
            for (Waiter waiter : pending.values()) {
                waiter.error = lastError;
                waiter.latch.countDown();
            }
        }
    }

    private static String readMessage(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (length < 0) {
            return null;
        }
        byte[] buf = in.readNBytes(length);
        if (buf.length < length) {
            return null;
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) {
                return buf.size() == 0 ? null : buf.toString(StandardCharsets.US_ASCII);
            }
            if (c == '\n') {
                byte[] bytes = buf.toByteArray();
                int n = bytes.length;
                if (n > 0 && bytes[n - 1] == '\r') {
                    n--;
                }
                return new String(bytes, 0, n, StandardCharsets.US_ASCII);
            }
            buf.write(c);
        }
    }

    private void replyReverse(Map<String, Object> msg) {
        String command = DapJson.str(msg.get("command"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("seq", seq.getAndIncrement());
        resp.put("type", "response");
        resp.put("request_seq", DapJson.integer(msg.get("seq"), 0));
        resp.put("command", command);
        if ("runInTerminal".equals(command)) {
            resp.put("success", false);
            resp.put("message", "runInTerminal not supported");
        } else {
            resp.put("success", true);
            resp.put("body", Map.of());
        }
        try {
            send(resp);
        } catch (IOException ignored) {
        }
    }

    private void drainErr(InputStream in) {
        try {
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) >= 0) {
                synchronized (stderr) {
                    stderr.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String stderrSuffix() {
        String extra;
        synchronized (stderr) {
            extra = stderr.toString().trim();
        }
        if (lastError != null && !lastError.isBlank() && extra.isEmpty()) {
            extra = lastError;
        }
        return extra.isEmpty() ? "" : " (" + extra + ")";
    }

    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        Map<String, Object> body;
        String error;
    }
}

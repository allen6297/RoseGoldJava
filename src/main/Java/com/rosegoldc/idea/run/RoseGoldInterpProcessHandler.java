package com.rosegoldc.idea.run;

import com.rosegoldc.idea.RoseGold;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class RoseGoldInterpProcessHandler extends ProcessHandler {

    interface Job {
        int run(@NotNull RoseGoldInterpProcessHandler handler) throws Exception;
    }

    private final String commandLine;
    private final Job job;
    private final AtomicBoolean notified = new AtomicBoolean();
    private volatile Thread worker;
    private volatile boolean destroyed;

    RoseGoldInterpProcessHandler(@NotNull String commandLine, @NotNull Job job) {
        this.commandLine = commandLine;
        this.job = job;
    }

    @Override
    public void startNotify() {
        notifyTextAvailable(commandLine + "\n", ProcessOutputTypes.SYSTEM);
        super.startNotify();
        Thread thread = new Thread(() -> {
            int code = 1;
            try {
                if (!destroyed) {
                    code = job.run(this);
                } else {
                    code = 1;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                code = 1;
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                notifyTextAvailable(message + "\n", ProcessOutputTypes.STDERR);
                code = 1;
            } finally {
                terminated(code);
            }
        }, "RoseGold");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    protected void destroyProcessImpl() {
        destroyed = true;
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
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

package com.rosegoldc.lang;

import java.util.List;

public interface DebugEngine {
    void setStopOnEntry(boolean stopOnEntry);

    List<Debug.Breakpoint> setBreakpoints(String path, List<Debug.Breakpoint> requested);

    void start();

    void resume(Debug.Mode mode);

    void abort();

    List<Debug.Frame> stack();

    List<Debug.Variable> locals(int frameId);

    String evaluate(String expression);

    String setVariable(int frameId, String name, String value);
}

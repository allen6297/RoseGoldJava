package com.rosegoldc.lang;

public final class LangException extends RuntimeException {

    public final Diagnostic diagnostic;

    public LangException(String message, Diagnostic diagnostic) {
        super(message);
        this.diagnostic = diagnostic;
    }
}

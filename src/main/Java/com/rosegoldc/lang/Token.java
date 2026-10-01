package com.rosegoldc.lang;

public final class Token {

    public Tok kind = Tok.Eof;
    public String text = "";
    public long number = 0;
    public int line = 1;
    public int col = 1;
    public double real = 0;

    public Token() {
    }

    public Token(Tok kind, String text, int line, int col) {
        this.kind = kind;
        this.text = text;
        this.line = line;
        this.col = col;
    }
}

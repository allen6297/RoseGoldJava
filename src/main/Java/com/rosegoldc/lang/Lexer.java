package com.rosegoldc.lang;

import java.util.ArrayList;
import java.util.List;

public final class Lexer {

    private final String src;
    private final String file;
    private final List<Diagnostic> errors;
    private int i = 0;
    private int line = 1;
    private int col = 1;

    private Lexer(String src, String file, List<Diagnostic> errors) {
        this.src = src;
        this.file = file == null ? "" : file;
        this.errors = errors;
    }

    public static List<Token> tokenize(String source, String file, List<Diagnostic> errors) {
        Lexer lexer = new Lexer(source, file, errors);
        List<Token> tokens = new ArrayList<>();
        while (true) {
            Token t = lexer.next();
            boolean done = t.kind == Tok.Eof;
            tokens.add(t);
            if (done) {
                break;
            }
        }
        return tokens;
    }

    private void record(String msg, int atLine, int atCol) {
        Diagnostic d = new Diagnostic(file, atLine, atCol, msg);
        d.kind = "parse error";
        if (errors != null) {
            for (Diagnostic prev : errors) {
                if (prev.file.equals(d.file) && prev.line == d.line && prev.col == d.col
                        && prev.message.equals(d.message)) {
                    return;
                }
            }
            errors.add(d);
            return;
        }
        throw new LangException(Diagnostic.locatedError("parse error", file, atLine, atCol, msg), d);
    }

    private void error(String msg, int atLine, int atCol) {
        Diagnostic d = new Diagnostic(file, atLine, atCol, msg);
        d.kind = "parse error";
        throw new LangException(Diagnostic.locatedError("parse error", file, atLine, atCol, msg), d);
    }

    private char peek(int off) {
        int n = i + off;
        if (n >= src.length()) {
            return 0;
        }
        return src.charAt(n);
    }

    private char peek() {
        return peek(0);
    }

    private char advance() {
        char c = src.charAt(i++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private void skip() {
        while (true) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                advance();
                continue;
            }
            break;
        }
    }

    private Token lineComment(int startLine, int startCol) {
        StringBuilder text = new StringBuilder();
        text.append(advance());
        do {
            text.append(advance());
        } while (peek() != 0 && peek() != '\n');
        return comment(Tok.LineComment, text.toString(), startLine, startCol);
    }

    private Token hashLineComment(int startLine, int startCol) {
        StringBuilder text = new StringBuilder();
        do {
            text.append(advance());
        } while (peek() != 0 && peek() != '\n');
        return comment(Tok.LineComment, text.toString(), startLine, startCol);
    }

    private Token blockComment(int startLine, int startCol) {
        StringBuilder text = new StringBuilder();
        text.append(advance());
        text.append(advance());
        while (true) {
            if (peek() == 0) {
                error("unterminated block comment", startLine, startCol);
            }
            if (peek() == '#' && peek(1) == '/') {
                text.append(advance());
                text.append(advance());
                break;
            }
            text.append(advance());
        }
        return comment(Tok.BlockComment, text.toString(), startLine, startCol);
    }

    private static Token comment(Tok kind, String text, int line, int col) {
        return new Token(kind, text, line, col);
    }

    private Token identOrKw(int startLine, int startCol) {
        StringBuilder text = new StringBuilder();
        while (isAlnum(peek()) || peek() == '_') {
            text.append(advance());
        }
        String s = text.toString();
        Token t = new Token();
        t.text = s;
        t.line = startLine;
        t.col = startCol;
        t.kind = switch (s) {
            case "fn" -> Tok.Function;
            case "import" -> Tok.Import;
            case "from" -> Tok.From;
            case "as" -> Tok.As;
            case "pub" -> Tok.Pub;
            case "abstract" -> Tok.Abstract;
            case "final" -> Tok.Final;
            case "private" -> Tok.Private;
            case "protected" -> Tok.Protected;
            case "mod" -> Tok.Module;
            case "class" -> Tok.Class;
            case "trait" -> Tok.Trait;
            case "extends" -> Tok.Extends;
            case "for" -> Tok.For;
            case "in" -> Tok.In;
            case "super" -> Tok.Super;
            case "enum" -> Tok.Enum;
            case "match" -> Tok.Match;
            case "switch" -> Tok.Switch;
            case "var" -> Tok.Variable;
            case "const" -> Tok.Constant;
            case "struct" -> Tok.Struct;
            case "data" -> Tok.Data;
            case "impl" -> Tok.Implements;
            case "signal" -> Tok.Signal;
            case "return" -> Tok.Return;
            case "pass" -> Tok.Pass;
            case "break" -> Tok.Break;
            case "continue" -> Tok.Continue;
            case "if" -> Tok.If;
            case "elif" -> Tok.Elif;
            case "else" -> Tok.Else;
            case "while" -> Tok.While;
            case "try" -> Tok.Try;
            case "do" -> Tok.Do;
            case "throws" -> Tok.Throws;
            case "throw" -> Tok.Throw;
            case "catch" -> Tok.Catch;
            case "async" -> Tok.Async;
            case "await" -> Tok.Await;
            case "true" -> Tok.True;
            case "false" -> Tok.False;
            default -> Tok.Identifier;
        };
        return t;
    }

    private Token stringLit(int startLine, int startCol) {
        advance();
        StringBuilder text = new StringBuilder();
        while (true) {
            char c = peek();
            if (c == 0) {
                error("unterminated string", startLine, startCol);
            }
            if (c == '"') {
                advance();
                break;
            }
            if (c == '\\') {
                advance();
                char e = peek();
                if (e == 0) {
                    error("unterminated string", startLine, startCol);
                }
                advance();
                if (e == 'n') {
                    text.append('\n');
                } else if (e == 't') {
                    text.append('\t');
                } else if (e == 'r') {
                    text.append('\r');
                } else if (e == 'x') {
                    int d1 = hex(peek());
                    if (d1 < 0) {
                        error("invalid \\x escape", startLine, startCol);
                    }
                    advance();
                    int d2 = hex(peek());
                    if (d2 < 0) {
                        error("invalid \\x escape", startLine, startCol);
                    }
                    advance();
                    text.append((char) ((d1 << 4) | d2));
                } else {
                    text.append(e);
                }
            } else {
                text.append(advance());
            }
        }
        return new Token(Tok.String, text.toString(), startLine, startCol);
    }

    private static int hex(char h) {
        if (h >= '0' && h <= '9') {
            return h - '0';
        }
        if (h >= 'a' && h <= 'f') {
            return h - 'a' + 10;
        }
        if (h >= 'A' && h <= 'F') {
            return h - 'A' + 10;
        }
        return -1;
    }

    private Token next() {
        skip();
        int startLine = line;
        int startCol = col;
        char c = peek();
        if (c == 0) {
            return new Token(Tok.Eof, "", startLine, startCol);
        }
        if (c == '/' && peek(1) == '/') {
            return lineComment(startLine, startCol);
        }
        if (c == '#') {
            return hashLineComment(startLine, startCol);
        }
        if (c == '/' && peek(1) == '#') {
            return blockComment(startLine, startCol);
        }
        if (isAlpha(c) || c == '_') {
            return identOrKw(startLine, startCol);
        }
        if (isDigit(c)) {
            StringBuilder digits = new StringBuilder();
            boolean overflowed = false;
            overflowed = takeDigits(digits, overflowed);
            if (peek() == '.' && isDigit(peek(1))) {
                digits.append(advance());
                overflowed = takeDigits(digits, overflowed);
                Token t = new Token(Tok.Float, digits.toString(), startLine, startCol);
                try {
                    if (overflowed) {
                        throw new NumberFormatException("float literal too large");
                    }
                    t.real = Double.parseDouble(digits.toString());
                    if (Double.isInfinite(t.real)) {
                        throw new NumberFormatException("float literal too large");
                    }
                } catch (NumberFormatException ex) {
                    record("float literal too large", startLine, startCol);
                    t.real = 0;
                }
                return t;
            }
            Token t = new Token(Tok.Integer, digits.toString(), startLine, startCol);
            try {
                if (overflowed || digits.length() > 19) {
                    throw new NumberFormatException("integer literal too large");
                }
                t.number = Long.parseLong(digits.toString());
            } catch (NumberFormatException ex) {
                record("integer literal too large", startLine, startCol);
                t.number = 0;
            }
            return t;
        }
        if (c == '"') {
            return stringLit(startLine, startCol);
        }
        advance();
        char n = peek();
        if (c == '=' && n == '=') {
            advance();
            return make(Tok.EqEq, "==", startLine, startCol);
        }
        if (c == '!' && n == '=') {
            advance();
            return make(Tok.NotEq, "!=", startLine, startCol);
        }
        if (c == '<' && n == '=') {
            advance();
            return make(Tok.LtEq, "<=", startLine, startCol);
        }
        if (c == '>' && n == '=') {
            advance();
            return make(Tok.GtEq, ">=", startLine, startCol);
        }
        if (c == '&' && n == '&') {
            advance();
            return make(Tok.AndAnd, "&&", startLine, startCol);
        }
        if (c == '|' && n == '|') {
            advance();
            return make(Tok.OrOr, "||", startLine, startCol);
        }
        if (c == '+' && n == '=') {
            advance();
            return make(Tok.PlusEq, "+=", startLine, startCol);
        }
        if (c == '-' && n == '=') {
            advance();
            return make(Tok.MinusEq, "-=", startLine, startCol);
        }
        if (c == '*' && n == '=') {
            advance();
            return make(Tok.StarEq, "*=", startLine, startCol);
        }
        if (c == '/' && n == '=') {
            advance();
            return make(Tok.SlashEq, "/=", startLine, startCol);
        }
        if (c == '.' && n == '.') {
            advance();
            if (peek() == '=') {
                advance();
                return make(Tok.DotDotEq, "..=", startLine, startCol);
            }
            return make(Tok.DotDot, "..", startLine, startCol);
        }
        return switch (c) {
            case '@' -> make(Tok.At, "@", startLine, startCol);
            case ':' -> make(Tok.Colon, ":", startLine, startCol);
            case '.' -> make(Tok.Dot, ".", startLine, startCol);
            case ',' -> make(Tok.Comma, ",", startLine, startCol);
            case ';' -> make(Tok.Semi, ";", startLine, startCol);
            case '(' -> make(Tok.LParen, "(", startLine, startCol);
            case ')' -> make(Tok.RParen, ")", startLine, startCol);
            case '{' -> make(Tok.LBrace, "{", startLine, startCol);
            case '}' -> make(Tok.RBrace, "}", startLine, startCol);
            case '[' -> make(Tok.LBracket, "[", startLine, startCol);
            case ']' -> make(Tok.RBracket, "]", startLine, startCol);
            case '+' -> make(Tok.Plus, "+", startLine, startCol);
            case '-' -> make(Tok.Minus, "-", startLine, startCol);
            case '*' -> make(Tok.Star, "*", startLine, startCol);
            case '/' -> make(Tok.Slash, "/", startLine, startCol);
            case '%' -> make(Tok.Percent, "%", startLine, startCol);
            case '=' -> make(Tok.Eq, "=", startLine, startCol);
            case '<' -> make(Tok.LArrow, "<", startLine, startCol);
            case '>' -> make(Tok.RArrow, ">", startLine, startCol);
            case '!' -> make(Tok.Bang, "!", startLine, startCol);
            default -> {
                record("unexpected character '" + c + "'", startLine, startCol);
                if (i < src.length()) {
                    advance();
                }
                yield next();
            }
        };
    }

    private boolean takeDigits(StringBuilder digits, boolean overflowed) {
        while (isDigit(peek())) {
            if (digits.length() >= 256) {
                overflowed = true;
                while (isDigit(peek())) {
                    advance();
                }
                break;
            }
            digits.append(advance());
        }
        return overflowed;
    }

    private static Token make(Tok k, String text, int line, int col) {
        return new Token(k, text, line, col);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAlnum(char c) {
        return isAlpha(c) || isDigit(c);
    }
}

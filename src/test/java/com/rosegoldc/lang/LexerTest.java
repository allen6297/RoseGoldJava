package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LexerTest {

    @Test
    public void keywordsAndSymbols() {
        List<Token> tokens = Lexer.tokenize("fn main(): Int { return 1 + 2; }", "t.rg", null);
        assertEquals(Tok.Function, tokens.get(0).kind);
        assertEquals(Tok.Identifier, tokens.get(1).kind);
        assertEquals("main", tokens.get(1).text);
        assertEquals(Tok.Integer, tokens.stream().filter(t -> t.kind == Tok.Integer).findFirst().orElseThrow().kind);
        assertEquals(Tok.Eof, tokens.getLast().kind);
    }

    @Test
    public void unexpectedCharacter() {
        List<Diagnostic> errors = new java.util.ArrayList<>();
        Lexer.tokenize("fn main(): Int { print(^); return 0; }", "t.rg", errors);
        assertFalse(errors.isEmpty());
        assertTrue(errors.getFirst().message.contains("unexpected character"));
    }

    @Test
    public void integerTooLarge() {
        List<Diagnostic> errors = new java.util.ArrayList<>();
        Lexer.tokenize("99999999999999999999", "t.rg", errors);
        assertEquals("integer literal too large", errors.getFirst().message);
    }
}

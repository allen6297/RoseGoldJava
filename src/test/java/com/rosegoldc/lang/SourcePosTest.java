package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourcePosTest {

    @Test
    public void mapsAddErrorOntoPlus() {
        String src = """
                fn main(): Int {
                    print(1 + "hello");
                    return 0;
                }
                """;
        List<Diagnostic> diags = Check.checkSource(src, "type_add.rg");
        Diagnostic d = diags.stream().filter(x -> x.message.contains("cannot add")).findFirst().orElseThrow();
        int start = SourcePos.offset(src, d.line, d.col);
        int end = SourcePos.tokenEnd(src, start);
        assertTrue(start + ".." + end + " " + src.substring(start, end), src.substring(start, end).contains("+"));
    }

    @Test
    public void lineColIsOneBased() {
        String src = "ab\ncd";
        assertEquals(1, SourcePos.lineCol(src, 0)[0]);
        assertEquals(1, SourcePos.lineCol(src, 0)[1]);
        assertEquals(1, SourcePos.lineCol(src, 1)[0]);
        assertEquals(2, SourcePos.lineCol(src, 1)[1]);
        assertEquals(2, SourcePos.lineCol(src, 3)[0]);
        assertEquals(1, SourcePos.lineCol(src, 3)[1]);
        assertEquals(2, SourcePos.lineCol(src, 4)[0]);
        assertEquals(2, SourcePos.lineCol(src, 4)[1]);
    }

    @Test
    public void offsetIsOneBased() {
        String src = "ab\ncd";
        assertEquals(0, SourcePos.offset(src, 1, 1));
        assertEquals(1, SourcePos.offset(src, 1, 2));
        assertEquals(3, SourcePos.offset(src, 2, 1));
        assertEquals(4, SourcePos.offset(src, 2, 2));
        assertEquals(5, SourcePos.tokenEnd(src, 4));
    }
}

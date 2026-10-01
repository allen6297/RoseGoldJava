package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestsTest {

    @Test
    public void findsFileLevelTestsNotMain() {
        String src = """
                @test
                fn add() {
                    assert(2 + 2 == 4);
                }
                fn main(): Int {
                    return 0;
                }
                @test
                fn concat() {
                    assert(1 == 1);
                }
                """;
        List<Tests.Fn> fns = Tests.inFile(src);
        assertEquals(2, fns.size());
        assertEquals("add", fns.get(0).name);
        assertEquals(2, fns.get(0).line);
        assertEquals("concat", fns.get(1).name);
        assertEquals(9, fns.get(1).line);
        assertEquals("add", src.substring(fns.get(0).start, fns.get(0).end));
        assertTrue(Tests.hasAny("""
                @test
                fn add() {}
                """));
        assertFalse(Tests.hasAny("fn main(): Int { return 0; }"));
    }

    @Test
    public void stackedAttrsAndComments() {
        List<Tests.Fn> fns = Tests.inFile("""
                @deprecated
                @test
                // note
                fn boom() {
                }
                """);
        assertEquals(1, fns.size());
        assertEquals("boom", fns.get(0).name);
    }

    @Test
    public void atCaretOnName() {
        String src = """
                @test
                fn add() {
                }
                """;
        Tests.Fn fn = Tests.at(src, src.indexOf("add") + 1);
        assertNotNull(fn);
        assertEquals("add", fn.name);
        assertNull(Tests.at("fn main(): Int { return 0; }", 3));
    }

    @Test
    public void nothingInsideString() {
        assertTrue(Tests.inFile("""
                fn main(): Int {
                    print("@test");
                    return 0;
                }
                """).isEmpty());
    }

    @Test
    public void parseOkAndFailLines() {
        Tests.Result ok = Tests.parseLine("ok add");
        assertNotNull(ok);
        assertTrue(ok.passed);
        assertEquals("add", ok.name);

        Tests.Result suiteOk = Tests.parseLine("ok   tests/pass/foo.rg");
        assertNotNull(suiteOk);
        assertTrue(suiteOk.passed);
        assertEquals("tests/pass/foo.rg", suiteOk.name);

        Tests.Result fail = Tests.parseLine("FAIL concat: assert failed at 3:5");
        assertNotNull(fail);
        assertFalse(fail.passed);
        assertEquals("concat", fail.name);
        assertEquals("assert failed at 3:5", fail.message);

        assertNull(Tests.parseLine("2/2 tests passed"));
        assertNull(Tests.parseLine("no @test functions"));
    }
}

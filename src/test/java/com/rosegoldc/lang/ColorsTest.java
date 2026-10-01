package com.rosegoldc.lang;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ColorsTest {

    @Test
    public void rgbCtorAndNamedAndFreeFn() {
        List<Colors.Span> spans = Colors.collect("""
                fn main(): Int {
                    var c = Color.Rgb(245, 245, 248);
                    var d = Color.Red;
                    var e = rgb(10, 20, 30);
                    return 0;
                }
                """);
        assertEquals(spans.toString(), 3, spans.size());
        assertEquals(245, spans.get(0).r);
        assertEquals(245, spans.get(0).g);
        assertEquals(248, spans.get(0).b);
        assertEquals(255, spans.get(0).a);
        assertEquals(255, spans.get(1).r);
        assertEquals(0, spans.get(1).g);
        assertEquals(0, spans.get(1).b);
        assertEquals(10, spans.get(2).r);
        assertEquals(20, spans.get(2).g);
        assertEquals(30, spans.get(2).b);
    }

    @Test
    public void argbAndUiRgb() {
        List<Colors.Span> spans = Colors.collect("""
                fn main(): Int {
                    var a = Color.Argb(128, 1, 2, 3);
                    var b = ui.rgb(4, 5, 6);
                    return 0;
                }
                """);
        assertEquals(2, spans.size());
        assertEquals(128, spans.get(0).a);
        assertEquals(1, spans.get(0).r);
        assertEquals(4, spans.get(1).r);
        assertEquals(5, spans.get(1).g);
        assertEquals(6, spans.get(1).b);
    }

    @Test
    public void rewriteKeepsForm() {
        Colors.Span rgb = Colors.at("var c = Color.Rgb(1, 2, 3);", 10);
        assertNotNull(rgb);
        assertEquals("Color.Rgb(9, 8, 7)", Colors.rewrite(rgb, 9, 8, 7, 255));
        assertEquals("Color.Argb(10, 9, 8, 7)", Colors.rewrite(rgb, 9, 8, 7, 10));

        Colors.Span named = Colors.at("var d = Color.Red;", 10);
        assertNotNull(named);
        assertEquals("Color.Rgb(0, 255, 0)", Colors.rewrite(named, 0, 255, 0, 255));

        Colors.Span fn = Colors.at("var e = rgb(10, 20, 30);", 10);
        assertNotNull(fn);
        assertEquals("rgb(1, 2, 3)", Colors.rewrite(fn, 1, 2, 3, 255));
    }

    @Test
    public void lowercaseRgbAndEmptyParens() {
        List<Colors.Span> empty = Colors.collect("var b : Color = Color.rgb();");
        assertEquals(empty.toString(), 1, empty.size());
        assertEquals(0, empty.get(0).r);
        assertEquals(0, empty.get(0).g);
        assertEquals(0, empty.get(0).b);
        assertEquals("Color.Rgb(1, 2, 3)", Colors.rewrite(empty.get(0), 1, 2, 3, 255));

        List<Colors.Span> filled = Colors.collect("var c = Color.rgb(10, 20, 30);");
        assertEquals(1, filled.size());
        assertEquals(10, filled.get(0).r);
        assertEquals(20, filled.get(0).g);
        assertEquals(30, filled.get(0).b);
    }

    @Test
    public void atOffsetHitsSpan() {
        String src = "var c = Color.Rgb(245, 245, 248);";
        int rgb = src.indexOf("Rgb");
        Colors.Span span = Colors.at(src, rgb);
        assertNotNull(span);
        assertTrue(span.contains(src.indexOf("Color")));
        assertTrue(span.contains(src.indexOf(')')));
    }
}

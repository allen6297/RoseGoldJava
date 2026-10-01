package com.rosegoldc.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HostFontTest {

    @Test
    public void cellIsCxxScale() {
        assertEquals(16, HostFont.CELL);
        assertEquals(16, HostFont.height());
        assertEquals(16, HostFont.width("A"));
        assertEquals(32, HostFont.width("Hi"));
        assertEquals(32, HostFont.width("Hi\nX"));
        assertEquals(0, HostFont.width(""));
    }

    @Test
    public void spaceIsEmptyBangHasPixels() {
        assertEquals(0, HostFont.pixelCount(' '));
        assertTrue(HostFont.pixelCount('!') > 0);
        assertTrue(HostFont.pixelCount('A') > HostFont.pixelCount('.'));
    }

    @Test
    public void outOfRangeUsesQuestionMark() {
        assertEquals(HostFont.pixelCount('?'), HostFont.pixelCount('\u2603'));
    }

    @Test
    public void drawScalesGlyphBits() {
        int[] plots = {0};
        HostFont.draw(" ", 0, 0, 0xFF0000, (x, y, rgb) -> plots[0]++);
        assertEquals(0, plots[0]);
        HostFont.draw("A", 0, 0, 0xFF0000, (x, y, rgb) -> {
            plots[0]++;
            assertEquals(0xFF0000, rgb);
        });
        assertEquals(HostFont.pixelCount('A') * HostFont.SCALE * HostFont.SCALE, plots[0]);
    }
}

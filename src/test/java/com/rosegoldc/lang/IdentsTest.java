package com.rosegoldc.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IdentsTest {

    @Test
    public void afterImportOrFromSeesImportAndFrom() {
        assertTrue(Idents.afterImportOrFrom("import"));
        assertTrue(Idents.afterImportOrFrom("  import std"));
        assertTrue(Idents.afterImportOrFrom("from"));
        assertTrue(Idents.afterImportOrFrom("from ui import"));
        assertFalse(Idents.afterImportOrFrom("fn main"));
        assertFalse(Idents.afterImportOrFrom("imported"));
        assertFalse(Idents.afterImportOrFrom("fromage"));
    }

    @Test
    public void fromImportCrateReadsCrateBeforeImport() {
        assertEquals("ui", Idents.fromImportCrate("from ui import "));
        assertEquals("std.ui", Idents.fromImportCrate("from std.ui import Button"));
        assertEquals("", Idents.fromImportCrate("from ui"));
        assertEquals("", Idents.fromImportCrate("import ui"));
        assertEquals("", Idents.fromImportCrate("from important"));
    }
}

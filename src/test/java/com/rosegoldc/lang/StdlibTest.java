package com.rosegoldc.lang;

import org.junit.Test;

import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StdlibTest {

    @Test
    public void bundledStdlibIsOnClasspath() {
        assertNotNull(Stdlib.class.getResource("/builtin/std/lib.rg"));
        assertNotNull(Stdlib.class.getResource("/builtin/std/ui/color.rg"));
        assertNotNull(Stdlib.class.getResource("/builtin/std/math/lib.rg"));
    }

    @Test
    public void bundledStdlibRootHasLib() {
        var root = Stdlib.bundledStdlibRoot();
        assertNotNull(root);
        assertTrue(Files.isRegularFile(root.resolve("lib.rg")));
        assertTrue(Files.isRegularFile(root.resolve("ui").resolve("color.rg")));
    }

    @Test
    public void stdlibSourceFilesIncludeChildren() {
        var root = Stdlib.bundledStdlibRoot();
        assertNotNull(root);
        var files = Stdlib.stdlibSourceFiles(root);
        assertTrue(files.toString(), files.stream().anyMatch(p ->
                p.toString().replace('\\', '/').endsWith("math/lib.rg")));
        assertTrue(files.toString(), files.stream().anyMatch(p ->
                p.toString().replace('\\', '/').endsWith("vec/lib.rg")));
        assertTrue(files.toString(), files.stream().anyMatch(p ->
                p.toString().replace('\\', '/').endsWith("ui/color.rg")));
    }
}

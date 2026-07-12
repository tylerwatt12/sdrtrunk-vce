/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.jmbe;

import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmbeCreatorTest
{
    @TempDir
    Path mTempDirectory;

    @Test
    void restoresOnlyCreatorExecutableFiles() throws Exception
    {
        Assumptions.assumeTrue(Files.getFileStore(mTempDirectory).supportsFileAttributeView("posix"));
        Path java = createFile("creator/bin/java");
        Path processHelper = createFile("creator/lib/jspawnhelper");
        Path library = createFile("creator/lib/library.dylib");

        JmbeCreator.restoreCreatorExecutables(mTempDirectory);

        assertExecutable(java);
        assertExecutable(processHelper);
        assertFalse(Files.getPosixFilePermissions(library).contains(OWNER_EXECUTE));
    }

    private Path createFile(String relativePath) throws Exception
    {
        Path path = mTempDirectory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "test");
        Files.setPosixFilePermissions(path, Set.of(OWNER_READ, OWNER_WRITE));
        return path;
    }

    private void assertExecutable(Path path) throws Exception
    {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        assertTrue(permissions.contains(OWNER_EXECUTE));
        assertTrue(permissions.contains(GROUP_EXECUTE));
        assertTrue(permissions.contains(OTHERS_EXECUTE));
    }
}

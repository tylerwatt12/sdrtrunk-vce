/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAccessAttributeSnapshotTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void copiesPosixPermissionsOwnerAndGroup() throws Exception
    {
        Path source = Files.writeString(mTemporaryFolder.resolve("posix-source"), "source");
        Path target = Files.writeString(mTemporaryFolder.resolve("posix-target"), "target");
        PosixFileAttributeView sourceView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        Assumptions.assumeTrue(sourceView != null, "POSIX file attributes are unavailable");
        sourceView.setPermissions(PosixFilePermissions.fromString("rw-r-----"));
        PosixFileAttributes expected = sourceView.readAttributes();

        FileAccessAttributeSnapshot.capture(source).applyTo(target);

        PosixFileAttributes actual = Files.readAttributes(target, PosixFileAttributes.class);
        assertEquals(expected.permissions(), actual.permissions());
        assertEquals(expected.owner(), actual.owner());
        assertEquals(expected.group(), actual.group());
    }

    @Test
    void copiesAccessControlListAndOwnerWhenAvailable() throws Exception
    {
        Path source = Files.writeString(mTemporaryFolder.resolve("acl-source"), "source");
        Path target = Files.writeString(mTemporaryFolder.resolve("acl-target"), "target");
        AclFileAttributeView sourceView = Files.getFileAttributeView(source, AclFileAttributeView.class);
        AclFileAttributeView targetView = Files.getFileAttributeView(target, AclFileAttributeView.class);
        Assumptions.assumeTrue(sourceView != null && targetView != null,
            "Access-control-list file attributes are unavailable");

        FileAccessAttributeSnapshot.capture(source).applyTo(target);

        assertEquals(sourceView.getOwner(), targetView.getOwner());
        assertEquals(sourceView.getAcl(), targetView.getAcl());
    }

    @Test
    void copiesDosFlagsWhenAvailable() throws Exception
    {
        Path source = Files.writeString(mTemporaryFolder.resolve("dos-source"), "source");
        Path target = Files.writeString(mTemporaryFolder.resolve("dos-target"), "target");
        DosFileAttributeView sourceView = Files.getFileAttributeView(source, DosFileAttributeView.class);
        DosFileAttributeView targetView = Files.getFileAttributeView(target, DosFileAttributeView.class);
        Assumptions.assumeTrue(sourceView != null && targetView != null, "DOS file attributes are unavailable");
        sourceView.setArchive(true);
        sourceView.setHidden(true);
        sourceView.setReadOnly(false);
        sourceView.setSystem(false);

        FileAccessAttributeSnapshot.capture(source).applyTo(target);

        DosFileAttributes actual = targetView.readAttributes();
        assertTrue(actual.isArchive());
        assertTrue(actual.isHidden());
        assertFalse(actual.isReadOnly());
        assertFalse(actual.isSystem());
    }

    @Test
    void copiesUserDefinedAttributesWhenAvailable() throws Exception
    {
        Path source = Files.writeString(mTemporaryFolder.resolve("attribute-source"), "source");
        Path target = Files.writeString(mTemporaryFolder.resolve("attribute-target"), "target");
        UserDefinedFileAttributeView sourceView =
            Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        UserDefinedFileAttributeView targetView =
            Files.getFileAttributeView(target, UserDefinedFileAttributeView.class);
        Assumptions.assumeTrue(sourceView != null && targetView != null,
            "User-defined file attributes are unavailable");
        byte[] expected = "protected migration metadata".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try
        {
            sourceView.write("sdrtrunk.migration-test", ByteBuffer.wrap(expected));
        }
        catch(IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "User-defined file attributes are not writable: " + e.getMessage());
        }

        FileAccessAttributeSnapshot.capture(source).applyTo(target);

        int size = targetView.size("sdrtrunk.migration-test");
        ByteBuffer actualBuffer = ByteBuffer.allocate(size);
        targetView.read("sdrtrunk.migration-test", actualBuffer);
        actualBuffer.flip();
        byte[] actual = new byte[actualBuffer.remaining()];
        actualBuffer.get(actual);
        assertArrayEquals(expected, actual);
    }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.portable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PortableFileIdentityTest
{
    @TempDir Path temporary;

    @Test
    void identifiesFilesAcrossRenameButRejectsReplacementWithSameContents() throws Exception
    {
        Path source = Files.writeString(temporary.resolve("source"), "same bytes");
        Path target = temporary.resolve("target");
        String original = PortableFileIdentity.read(source);
        assertNotNull(original);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(original, PortableFileIdentity.read(target));
        Path replacement = Files.writeString(source, "same bytes");
        assertNotEquals(original, PortableFileIdentity.read(replacement));
    }

    @Test
    void identifiesDirectoriesAcrossRenameWithoutReadingContents() throws Exception
    {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("unchanged"), "payload");
        String original = PortableFileIdentity.read(source);
        assertNotNull(original);
        Path target = temporary.resolve("target");
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(original, PortableFileIdentity.read(target));
        assertNotEquals(original, PortableFileIdentity.read(Files.createDirectory(source)));
    }
}

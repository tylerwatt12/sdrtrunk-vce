/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipUtilityTest
{
    @TempDir
    Path mTempDirectory;

    @Test
    void extractsNestedFiles() throws Exception
    {
        Path archive = createArchive("creator/bin/creator", "#!/bin/sh\n");

        assertEquals(mTempDirectory, ZipUtility.unzip(archive));
        assertEquals("#!/bin/sh\n", Files.readString(mTempDirectory.resolve("creator/bin/creator")));
    }

    @Test
    void rejectsEntriesOutsideDestination() throws Exception
    {
        Path archive = createArchive("../outside.txt", "unsafe");

        assertThrows(IOException.class, () -> ZipUtility.unzip(archive));
    }

    private Path createArchive(String entryName, String content) throws IOException
    {
        Path archive = mTempDirectory.resolve("creator.zip");

        try(ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            outputStream.putNextEntry(new ZipEntry(entryName));
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.closeEntry();
        }

        return archive;
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugReportManualSaveTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void savesCompletedBundleAndReplacesApprovedDestination() throws Exception
    {
        Path source = mTemporaryDirectory.resolve("temporary-bundle.zip");
        Path destination = mTemporaryDirectory.resolve("manual-bundle.zip");
        Files.writeString(source, "complete diagnostic bundle");
        Files.writeString(destination, "old content");

        BugReportDialog.saveBundleAtomically(source, destination);

        assertEquals("complete diagnostic bundle", Files.readString(destination));

        try(var files = Files.list(mTemporaryDirectory))
        {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}

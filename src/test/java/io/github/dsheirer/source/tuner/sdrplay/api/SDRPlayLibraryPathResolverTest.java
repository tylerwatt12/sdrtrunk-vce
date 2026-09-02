/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SDRPlayLibraryPathResolverTest
{
    @TempDir
    Path mProgramFiles;

    @Test
    void resolvesMappedWindowsDllFilename()
    {
        Path expected = mProgramFiles.resolve("SDRplay/API/x64/sdrplay_api.dll").toAbsolutePath().normalize();
        Path resolved = SDRPlayLibraryPathResolver.resolveWindows(mProgramFiles.toString(), libraryName ->
        {
            assertEquals("sdrplay_api", libraryName);
            return "sdrplay_api.dll";
        }).orElseThrow();

        assertEquals(expected, resolved);
        assertEquals("sdrplay_api.dll", resolved.getFileName().toString());
    }

    @Test
    void rejectsMissingWindowsProgramFilesWithoutMappingLibraryName()
    {
        AtomicBoolean mapperInvoked = new AtomicBoolean();

        assertFalse(SDRPlayLibraryPathResolver.resolveWindows(null, libraryName ->
        {
            mapperInvoked.set(true);
            return "sdrplay_api.dll";
        }).isPresent());
        assertFalse(mapperInvoked.get());
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.api;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Resolves platform-specific SDRplay API native library paths without loading the library.
 */
final class SDRPlayLibraryPathResolver
{
    private static final String SDRPLAY_API_LIBRARY_NAME = "sdrplay_api";

    private SDRPlayLibraryPathResolver()
    {
    }

    /**
     * Resolves the installed 64-bit Windows SDRplay API library.
     *
     * @param programFiles Windows Program Files directory
     * @param libraryNameMapper maps a base native library name to its platform filename
     * @return resolved absolute library path, or empty when the installation root is unavailable
     */
    static Optional<Path> resolveWindows(String programFiles, UnaryOperator<String> libraryNameMapper)
    {
        if(programFiles == null || programFiles.isBlank())
        {
            return Optional.empty();
        }

        String libraryFileName = libraryNameMapper.apply(SDRPLAY_API_LIBRARY_NAME);

        if(libraryFileName == null || libraryFileName.isBlank())
        {
            return Optional.empty();
        }

        return Optional.of(Path.of(programFiles, "SDRplay", "API", "x64", libraryFileName)
                .toAbsolutePath().normalize());
    }
}

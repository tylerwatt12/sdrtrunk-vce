/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.jmbe;

import io.github.dsheirer.jmbe.github.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Reads and validates the metadata embedded in a JMBE library.
 */
public final class JmbeLibraryMetadata
{
    private static final String ENTRY_POINT = "jmbe/JMBEAudioLibrary.class";

    private JmbeLibraryMetadata()
    {
    }

    public static Version getVersion(Path library)
    {
        try(JarFile jarFile = new JarFile(library.toFile()))
        {
            return getVersion(jarFile);
        }
        catch(IOException ignored)
        {
            //Invalid or unavailable libraries are reported by the caller as an unknown version.
        }

        return null;
    }

    public static void verify(Path library, Version expectedVersion) throws IOException
    {
        if(!Files.isRegularFile(library) || Files.size(library) == 0)
        {
            throw new IOException("JMBE library was not created");
        }

        try(JarFile jarFile = new JarFile(library.toFile()))
        {
            Version actualVersion = getVersion(jarFile);

            if(!expectedVersion.equals(actualVersion))
            {
                throw new IOException("Created JMBE version " + actualVersion +
                    " does not match requested version " + expectedVersion);
            }

            if(jarFile.getJarEntry(ENTRY_POINT) == null)
            {
                throw new IOException("Created JMBE library is missing its entry point");
            }
        }
    }

    private static Version getVersion(JarFile jarFile) throws IOException
    {
        if(jarFile.getManifest() != null)
        {
            String version = jarFile.getManifest().getMainAttributes().getValue("Version");

            if(version == null)
            {
                version = jarFile.getManifest().getMainAttributes().getValue("Implementation-Version");
            }

            return Version.fromString(version);
        }

        return null;
    }
}

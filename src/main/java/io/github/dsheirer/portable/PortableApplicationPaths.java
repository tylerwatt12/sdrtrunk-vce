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

package io.github.dsheirer.portable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Resolves installation and writable data paths for a portable sdrtrunk-vce distribution.
 */
public final class PortableApplicationPaths
{
    public static final String DATA_ROOT_PROPERTY = "sdrtrunk.vce.data.root";
    private static final String DATA_DIRECTORY = "data";
    private static Path sInstallRoot;
    private static Path sDataRoot;

    private PortableApplicationPaths()
    {
    }

    public static synchronized Path getInstallRoot()
    {
        if(sInstallRoot == null)
        {
            sInstallRoot = resolveInstallRoot();
        }

        return sInstallRoot;
    }

    public static synchronized Path getDataRoot()
    {
        if(sDataRoot == null)
        {
            String override = System.getProperty(DATA_ROOT_PROPERTY);

            if(override != null && !override.isBlank())
            {
                sDataRoot = Path.of(override).toAbsolutePath().normalize();
            }
            else
            {
                sDataRoot = resolveDefaultDataRoot();
            }
        }

        return sDataRoot;
    }

    public static Path getLegacyApplicationRoot()
    {
        return Path.of(System.getProperty("user.home"), "SDRTrunk").toAbsolutePath().normalize();
    }

    public static Path copyIntoDataDirectory(Path source, String directory) throws java.io.IOException
    {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path targetDirectory = getDataRoot().resolve(directory).toAbsolutePath().normalize();
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(normalizedSource.getFileName()).normalize();

        if(!normalizedSource.equals(target))
        {
            Files.copy(normalizedSource, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return target;
    }

    public static String toPortablePath(Path path)
    {
        Path normalized = path.toAbsolutePath().normalize();
        Path dataRoot = getDataRoot();
        return normalized.startsWith(dataRoot) ? dataRoot.relativize(normalized).toString() : normalized.toString();
    }

    public static Path resolvePortablePath(String path)
    {
        Path candidate = Path.of(path);
        return candidate.isAbsolute() ? candidate.normalize() : getDataRoot().resolve(candidate).normalize();
    }

    static synchronized void resetForTest()
    {
        sInstallRoot = null;
        sDataRoot = null;
    }

    private static Path resolveDefaultDataRoot()
    {
        return getInstallRoot().resolve(DATA_DIRECTORY).toAbsolutePath().normalize();
    }

    private static Path resolveInstallRoot()
    {
        try
        {
            URI location = PortableApplicationPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codePath = Path.of(location).toAbsolutePath().normalize();

            if(Files.isRegularFile(codePath) && codePath.getParent() != null)
            {
                Path parent = codePath.getParent();
                return parent.getFileName() != null && "lib".equalsIgnoreCase(parent.getFileName().toString()) ?
                    parent.getParent() : parent;
            }
        }
        catch(Exception e)
        {
            // Fall through to the launch directory.
        }

        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }
}

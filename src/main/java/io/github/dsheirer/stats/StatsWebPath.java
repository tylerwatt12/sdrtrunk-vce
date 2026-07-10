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

package io.github.dsheirer.stats;

import io.github.dsheirer.portable.PortableApplicationPaths;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem path helper for editable Stats Server web assets.
 */
public final class StatsWebPath
{
    public static final String ASSETS_DIRECTORY = "stats-web";
    public static final String ROOT_OVERRIDE_PROPERTY = "sdrtrunk.stats.web.root";

    private StatsWebPath()
    {
    }

    /**
     * Web assets live on disk and are never served from inside the application jar.
     */
    public static Path getAssetsPath()
    {
        String override = System.getProperty(ROOT_OVERRIDE_PROPERTY);

        if(override != null && !override.isBlank())
        {
            return Path.of(override).toAbsolutePath().normalize();
        }

        Path applicationRoot = PortableApplicationPaths.getInstallRoot()
            .resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();

        if(isUsableAssetRoot(applicationRoot))
        {
            return applicationRoot;
        }

        Path launchRoot = Path.of(System.getProperty("user.dir", "."))
            .resolve(ASSETS_DIRECTORY).toAbsolutePath().normalize();

        if(isUsableAssetRoot(launchRoot))
        {
            return launchRoot;
        }

        return applicationRoot;
    }

    private static boolean isUsableAssetRoot(Path path)
    {
        return Files.isRegularFile(path.resolve("index.html"));
    }
}

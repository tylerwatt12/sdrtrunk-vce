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

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.portable.PortableApplicationPaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Locates portable data from a previous sdrtrunk-vce build without searching outside the current installation's
 * immediate surroundings.
 *
 * <p>Discovery examines only the direct children beside the current installation and data roots. A selected path
 * may be an installation root, a portable data root, a macOS application bundle, or the global SQLite database
 * itself. A candidate is returned only when it contains {@code database/sdrtrunk.sqlite}.</p>
 */
public final class PreviousBuildLocator
{
    private static final String DATA_DIRECTORY = "data";
    private static final String MAC_APPLICATION_SUFFIX = ".app";
    private static final String MAC_DATA_SUFFIX = "-data";
    private static final Pattern INTERNAL_STAGING_DIRECTORY =
        Pattern.compile("^\\..+\\.(?:upgrade|migration)-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Comparator<Path> PATH_COMPARATOR =
        Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER).thenComparing(Path::toString);

    private PreviousBuildLocator()
    {
    }

    /**
     * Discovers previous portable data roots adjacent to the running installation.
     */
    public static List<Path> discover()
    {
        return discover(PortableApplicationPaths.getInstallRoot(), PortableApplicationPaths.getDataRoot());
    }

    /**
     * Discovers previous portable data roots adjacent to the supplied installation and data roots.
     *
     * @param currentInstallRoot current installation directory or macOS application bundle
     * @param currentDataRoot current portable data root
     * @return normalized, de-duplicated, deterministically sorted previous data roots
     */
    public static List<Path> discover(Path currentInstallRoot, Path currentDataRoot)
    {
        Path normalizedInstallRoot = normalize(currentInstallRoot);
        Path normalizedDataRoot = normalize(currentDataRoot);
        Set<Path> searchDirectories = new LinkedHashSet<>();
        addParent(searchDirectories, normalizedInstallRoot);
        addParent(searchDirectories, normalizedDataRoot);

        Set<Path> candidates = new LinkedHashSet<>();

        for(Path searchDirectory : searchDirectories)
        {
            inspectDirectChildren(searchDirectory, candidates);
        }

        candidates.remove(normalizedDataRoot);
        return candidates.stream().sorted(PATH_COMPARATOR).toList();
    }

    /**
     * Resolves a user selection to its portable data root.
     *
     * @param selected installation root, data root, macOS application bundle, or sdrtrunk.sqlite file
     * @return the normalized portable data root when the selection identifies one
     */
    public static Optional<Path> resolveSelection(Path selected)
    {
        if(selected == null)
        {
            return Optional.empty();
        }

        Path normalized = normalize(selected);

        if(isGlobalDatabase(normalized))
        {
            Path dataRoot = normalize(normalized.getParent().getParent());
            return isInternalStagingDirectory(dataRoot) ? Optional.empty() : Optional.of(dataRoot);
        }

        if(!Files.isDirectory(normalized))
        {
            return Optional.empty();
        }

        if(isMacApplication(normalized))
        {
            Path dataRoot = macDataRoot(normalized);

            if(dataRoot != null && containsGlobalDatabase(dataRoot))
            {
                return Optional.of(normalize(dataRoot));
            }
        }

        if(!isInternalStagingDirectory(normalized) && containsGlobalDatabase(normalized))
        {
            return Optional.of(normalized);
        }

        Path nestedDataRoot = normalized.resolve(DATA_DIRECTORY);
        return !isInternalStagingDirectory(nestedDataRoot) && containsGlobalDatabase(nestedDataRoot) ?
            Optional.of(normalize(nestedDataRoot)) : Optional.empty();
    }

    private static void inspectDirectChildren(Path directory, Set<Path> candidates)
    {
        if(directory == null || !Files.isDirectory(directory))
        {
            return;
        }

        try(DirectoryStream<Path> children = Files.newDirectoryStream(directory))
        {
            for(Path child : children)
            {
                resolveSelection(child).ifPresent(candidates::add);
            }
        }
        catch(IOException | SecurityException e)
        {
            // Automatic discovery is best-effort. The setup workflow can still let the user choose a location.
        }
    }

    private static void addParent(Set<Path> directories, Path path)
    {
        if(path != null && path.getParent() != null)
        {
            directories.add(path.getParent());
        }
    }

    private static boolean containsGlobalDatabase(Path dataRoot)
    {
        return dataRoot != null && Files.isRegularFile(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
    }

    private static boolean isGlobalDatabase(Path path)
    {
        Path parent = path.getParent();
        return Files.isRegularFile(path) && path.getFileName() != null && parent != null && parent.getParent() != null &&
            SdrTrunkDatabasePath.DATABASE_FILENAME.equalsIgnoreCase(path.getFileName().toString()) &&
            SdrTrunkDatabasePath.DATABASE_DIRECTORY.equalsIgnoreCase(parent.getFileName().toString());
    }

    private static boolean isMacApplication(Path path)
    {
        return path.getFileName() != null &&
            path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MAC_APPLICATION_SUFFIX);
    }

    private static boolean isInternalStagingDirectory(Path path)
    {
        return path != null && path.getFileName() != null &&
            INTERNAL_STAGING_DIRECTORY.matcher(path.getFileName().toString()).matches();
    }

    private static Path macDataRoot(Path application)
    {
        if(application.getParent() == null || application.getFileName() == null)
        {
            return null;
        }

        String filename = application.getFileName().toString();
        String basename = filename.substring(0, filename.length() - MAC_APPLICATION_SUFFIX.length());
        return application.resolveSibling(basename + MAC_DATA_SUFFIX);
    }

    private static Path normalize(Path path)
    {
        if(path == null)
        {
            return null;
        }

        Path normalized = path.toAbsolutePath().normalize();

        try
        {
            return normalized.toRealPath();
        }
        catch(IOException | SecurityException e)
        {
            return normalized;
        }
    }
}

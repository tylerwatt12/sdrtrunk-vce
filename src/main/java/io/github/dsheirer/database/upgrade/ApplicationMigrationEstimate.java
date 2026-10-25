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

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.OptionalLong;

/** A deliberately broad planning hint; no database reads, benchmark, or progress prediction. */
public final class ApplicationMigrationEstimate
{
    private static final long SMALL_DATABASE_BYTES = 512L * 1024 * 1024;
    // One approximately 3 GiB format-60-to-62 update took eight minutes. This is a broad allowance around
    // that observation, not a measured per-format/storage throughput or a completion deadline.
    private static final long REFERENCE_BYTES = 3_135_460L * 1024;
    private static final String UNKNOWN = "Time estimate unavailable. Large databases can take a long time.";
    private static final String LIMITATION = " It may take longer.";

    private ApplicationMigrationEstimate()
    {
    }

    /** Uses only file sizes. Failure to obtain a hint must never prevent migration review. */
    public static String fullMigrationWarning(Path database)
    {
        OptionalLong footprint = databaseFootprint(database);
        return footprint.isPresent() ? fullMigrationWarning(footprint.getAsLong()) : UNKNOWN;
    }

    static String fullMigrationWarning(long bytes)
    {
        if(bytes <= 0)
        {
            return UNKNOWN;
        }
        if(bytes < SMALL_DATABASE_BYTES)
        {
            return "Allow a few minutes." + LIMITATION;
        }

        double ratio = bytes / (double)REFERENCE_BYTES;
        long low = Math.max(1, (long)Math.floor(4 * ratio));
        long high = Math.max(5, (long)Math.ceil(20 * ratio));
        if(low >= 5) low = low / 5 * 5;
        high = (high + 4) / 5 * 5;
        String range = high < 60 ? low + "–" + high + " minutes" :
            lowerDuration(low) + "–" + upperDuration(high);
        return "Rough estimate: " + range + "." + LIMITATION;
    }

    private static String lowerDuration(long minutes)
    {
        if(minutes < 60) return minutes + " minutes";
        if(minutes < 24 * 60) return quantity(minutes / 60, "hour");
        return quantity(minutes / (24 * 60), "day");
    }

    private static String upperDuration(long minutes)
    {
        if(minutes < 24 * 60) return quantity((minutes + 59) / 60, "hour");
        return quantity((minutes + 24 * 60 - 1) / (24 * 60), "day");
    }

    private static String quantity(long value, String unit)
    {
        return value + " " + unit + (value == 1 ? "" : "s");
    }

    static OptionalLong databaseFootprint(Path database)
    {
        if(database == null) return OptionalLong.empty();
        try
        {
            long bytes = regularFileSize(database);
            for(String suffix: new String[]{"-wal", "-journal"})
            {
                Path sidecar = database.resolveSibling(database.getFileName() + suffix);
                try
                {
                    bytes = Math.addExact(bytes, regularFileSize(sidecar));
                }
                catch(NoSuchFileException absent)
                {
                    //No active journal of this type.
                }
                catch(ArithmeticException enormous)
                {
                    bytes = Long.MAX_VALUE;
                }
            }
            return OptionalLong.of(bytes);
        }
        catch(IOException | SecurityException unavailable)
        {
            return OptionalLong.empty();
        }
    }

    private static long regularFileSize(Path path) throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile()) throw new IOException("Database size is unavailable");
        return attributes.size();
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.configuration;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Selects the next SQLite identities for detached administration candidates. The configuration manager serializes
 * these commands for the process, and SQLite AUTOINCREMENT remains the durable high-water mark. The following Alias
 * transaction inserts the selected IDs and all dependent membership rows before anything reaches an active model.
 */
public final class ConfigurationIdentityAllocator
{
    private static final long JSON_SAFE_INTEGER_MAXIMUM = (1L << 53) - 1L;
    private final Path mDatabasePath;

    public ConfigurationIdentityAllocator(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public List<Long> nextAliasIds(Collection<Long> candidateIds, int count)
    {
        return nextIds(Entity.ALIAS, candidateIds, count);
    }

    public List<Long> nextAliasListIds(Collection<Long> candidateIds, int count)
    {
        return nextIds(Entity.ALIAS_LIST, candidateIds, count);
    }

    public List<Long> nextScanListIds(Collection<Long> candidateIds, int count)
    {
        return nextIds(Entity.SCAN_LIST, candidateIds, count);
    }

    private List<Long> nextIds(Entity entity, Collection<Long> candidateIds, int count)
    {
        if(count < 0)
        {
            throw new IllegalArgumentException("Identity count cannot be negative");
        }
        if(count == 0)
        {
            return List.of();
        }

        long highest = candidateIds != null ? candidateIds.stream().filter(id -> id != null && id > 0L)
            .mapToLong(Long::longValue).max().orElse(0L) : 0L;

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            try(Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + entity.table()))
            {
                if(result.next())
                {
                    highest = Math.max(highest, result.getLong(1));
                }
            }

            try(PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(seq, 0) FROM sqlite_sequence WHERE name = ?"))
            {
                statement.setString(1, entity.table());
                try(ResultSet result = statement.executeQuery())
                {
                    if(result.next())
                    {
                        highest = Math.max(highest, result.getLong(1));
                    }
                }
            }
        }
        catch(IOException | SQLException exception)
        {
            throw new AllocationException("Unable to allocate durable " + entity.label() + " identities",
                exception);
        }

        if(highest > JSON_SAFE_INTEGER_MAXIMUM - count)
        {
            throw new IllegalStateException(entity.label() + " identities have reached the JSON-safe range");
        }

        List<Long> identities = new ArrayList<>(count);
        for(int offset = 1; offset <= count; offset++)
        {
            identities.add(highest + offset);
        }
        return List.copyOf(identities);
    }

    /** Indicates that SQLite's durable identity high-water mark could not be read. */
    public static final class AllocationException extends RuntimeException
    {
        public AllocationException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private enum Entity
    {
        ALIAS("alias", "Alias"),
        ALIAS_LIST("alias_list", "Alias List"),
        SCAN_LIST("scan_list", "scan-list");

        private final String mTable;
        private final String mLabel;

        Entity(String table, String label)
        {
            mTable = table;
            mLabel = label;
        }

        private String table()
        {
            return mTable;
        }

        private String label()
        {
            return mLabel;
        }
    }
}

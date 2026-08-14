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

package io.github.dsheirer.database.scanlist;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite persistence for scan-list definitions and normalized Alias and unmatched-talkgroup Alias List membership.
 */
public final class ScanListDatabaseStore
{
    private final Path mDatabasePath;

    public ScanListDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public ScanListConfiguration loadConfiguration() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return loadConfiguration(connection);
        }
    }

    /**
     * Loads the complete configuration using a caller-owned connection.
     */
    public ScanListConfiguration loadConfiguration(Connection connection) throws SQLException
    {
        if(connection == null)
        {
            throw new IllegalArgumentException("Connection cannot be null");
        }

        List<ScanList> scanLists = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order, name, description, published, is_default
            FROM scan_list
            ORDER BY sort_order, name COLLATE NOCASE, id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                if(resultSet.wasNull() || id <= ScanList.UNASSIGNED_ID)
                {
                    throw new SQLException("Persisted scan-list IDs must be greater than zero");
                }

                try
                {
                    scanLists.add(new ScanList(id, resultSet.getInt("sort_order"), resultSet.getString("name"),
                        resultSet.getString("description"), booleanValue(resultSet, "published"),
                        booleanValue(resultSet, "is_default")));
                }
                catch(IllegalArgumentException e)
                {
                    throw new SQLException("Scan list [" + id + "] is invalid", e);
                }
            }
        }

        Map<Long,Set<Long>> aliasMemberships = loadMemberships(connection, "alias_scan_list_membership",
            "alias_id");
        requireKnownOwners(aliasMemberships.keySet(), loadIds(connection, "alias"), "Alias");
        Map<Long,Set<Long>> unmatchedAliasListMemberships = loadMemberships(connection,
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id");
        requireKnownOwners(unmatchedAliasListMemberships.keySet(), loadIds(connection, "alias_list"), "Alias List");

        try
        {
            return new ScanListConfiguration(scanLists, aliasMemberships, unmatchedAliasListMemberships);
        }
        catch(IllegalArgumentException e)
        {
            throw new SQLException("Persisted scan-list configuration is invalid", e);
        }
    }

    /**
     * Replaces the complete scan-list configuration using the same transaction as Alias and channel configuration.
     */
    public void replaceConfiguration(Connection connection, ScanListConfiguration configuration) throws SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Scan-list snapshot writes require a caller-owned transaction");
        }
        if(configuration == null)
        {
            throw new IllegalArgumentException("Scan-list configuration cannot be null");
        }

        Set<Long> aliasIds = loadIds(connection, "alias");
        requireKnownOwners(configuration.aliasMemberships().keySet(), aliasIds, "Alias");
        Set<Long> aliasListIds = loadIds(connection, "alias_list");
        requireKnownOwners(configuration.unmatchedAliasListMemberships().keySet(), aliasListIds, "Alias List");

        clearConfiguration(connection);
        saveScanLists(connection, configuration.scanLists());
        saveMemberships(connection, "alias_scan_list_membership", "alias_id",
            configuration.aliasMemberships());
        saveMemberships(connection, "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id",
            configuration.unmatchedAliasListMemberships());
    }

    private static void clearConfiguration(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_scan_list_membership");
            statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DELETE FROM scan_list");
        }
    }

    private static void saveScanLists(Connection connection, Collection<ScanList> scanLists) throws SQLException
    {
        for(ScanList scanList: scanLists)
        {
            if(scanList.getId() == ScanList.UNASSIGNED_ID)
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scan_list (sort_order, name, description, published, is_default)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS))
                {
                    bindScanList(statement, scanList, 1);
                    statement.executeUpdate();
                    try(ResultSet keys = statement.getGeneratedKeys())
                    {
                        if(!keys.next())
                        {
                            throw new SQLException("SQLite did not return a scan-list ID");
                        }
                        scanList.assignId(keys.getLong(1));
                    }
                }
            }
            else
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO scan_list (id, sort_order, name, description, published, is_default)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """))
                {
                    statement.setLong(1, scanList.getId());
                    bindScanList(statement, scanList, 2);
                    statement.executeUpdate();
                }
            }
        }
    }

    private static void bindScanList(PreparedStatement statement, ScanList scanList, int offset) throws SQLException
    {
        statement.setInt(offset, scanList.getSortOrder());
        statement.setString(offset + 1, scanList.getName());
        statement.setString(offset + 2, scanList.getDescription());
        statement.setInt(offset + 3, scanList.isPublished() ? 1 : 0);
        statement.setInt(offset + 4, scanList.isDefault() ? 1 : 0);
    }

    private static Map<Long,Set<Long>> loadMemberships(Connection connection, String table, String ownerColumn)
        throws SQLException
    {
        Map<Long,Set<Long>> memberships = new LinkedHashMap<>();
        String sql = "SELECT " + ownerColumn + ", scan_list_id FROM " + table +
            " ORDER BY " + ownerColumn + ", scan_list_id";
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            while(resultSet.next())
            {
                long ownerId = resultSet.getLong(ownerColumn);
                long scanListId = resultSet.getLong("scan_list_id");
                if(ownerId <= 0 || scanListId <= 0)
                {
                    throw new SQLException("Persisted scan-list memberships require positive durable IDs");
                }
                memberships.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(scanListId);
            }
        }
        return memberships;
    }

    private static void saveMemberships(Connection connection, String table, String ownerColumn,
                                        Map<Long,Set<Long>> memberships) throws SQLException
    {
        String sql = "INSERT INTO " + table + " (" + ownerColumn + ", scan_list_id) VALUES (?, ?)";
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            for(Map.Entry<Long,Set<Long>> entry: memberships.entrySet())
            {
                for(Long scanListId: entry.getValue())
                {
                    statement.setLong(1, entry.getKey());
                    statement.setLong(2, scanListId);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static Set<Long> loadIds(Connection connection, String table) throws SQLException
    {
        Set<Long> ids = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT id FROM " + table))
        {
            while(resultSet.next())
            {
                ids.add(resultSet.getLong(1));
            }
        }
        return ids;
    }

    private static void requireKnownOwners(Collection<Long> ownerIds, Set<Long> knownIds, String label)
        throws SQLException
    {
        for(Long ownerId: ownerIds)
        {
            if(!knownIds.contains(ownerId))
            {
                throw new SQLException(label + " scan-list membership references unknown ID [" + ownerId + "]");
            }
        }
    }

    private static boolean booleanValue(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        if(resultSet.wasNull() || value != 0 && value != 1)
        {
            throw new SQLException("Column [" + column + "] must contain 0 or 1");
        }
        return value == 1;
    }
}

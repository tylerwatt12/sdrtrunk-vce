/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Preserves retained evidence while storing its existing member observation channel for covering lookups. */
final class Format38To39DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-38-to-39"; }
    @Override public String description() { return "Speed up retained channel-local identity name lookups"; }
    @Override public int sourceVersion() { return 38; }
    @Override public int targetVersion() { return 39; }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        requireMemberParents(connection);
        return effects(memberCount(connection));
    }

    @Override
    public void migrateSource(Connection connection) throws SQLException
    {
        requireMemberParents(connection);
        long members = memberCount(connection);
        ReceiverActivitySchema.rebuildStoredMemberChannelEvidence(connection);
        if(memberCount(connection) != members)
        {
            throw new SQLException("Format 39 must preserve every retained Activity member");
        }
    }


    private static void requireMemberParents(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("""
            SELECT 1 FROM activity_event_identity_member member
            LEFT JOIN receiver_activity_event event INDEXED BY sqlite_autoindex_receiver_activity_event_1
              ON event.id = member.event_id AND event.radio_system_id = member.radio_system_id
            WHERE event.id IS NULL LIMIT 1
            """))
        {
            if(rows.next())
            {
                throw new SQLException("Activity member has no matching parent event and owner");
            }
        }
    }

    private static long memberCount(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT count(*) FROM activity_event_identity_member"))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static List<DatabaseMigrationEffect> effects(long members)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "member observation channels", members,
            "Copy each existing parent event channel while preserving every member key and previous field"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "member and P25 local-address lookup indexes", 2,
                "Add two covering lookups while preserving existing Activity order and retention indexes"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "event/channel parent key", 1,
                "Replace the existing narrow event/channel index with a unique parent key for the new foreign key"));
    }
}

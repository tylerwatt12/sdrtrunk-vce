/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Preserves custom Alias Lists that occupy a factory name by moving them to a unique, user-editable name. */
final class FactoryAliasListCollisionRepair
{
    private static final int MAXIMUM_ALIAS_LIST_NAME_LENGTH = 25;
    private static final List<String> HISTORICAL_REFERENCE_TABLES = List.of(
        "receiver_context", "p25_site_snapshot", "trunked_site_snapshot");

    private FactoryAliasListCollisionRepair()
    {
    }

    static List<Collision> plan(Connection connection, List<Target> targets) throws SQLException
    {
        Set<String> names = new HashSet<>();
        try(var statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT name FROM alias_list ORDER BY id"))
        {
            while(rows.next())
            {
                names.add(normalize(rows.getString(1)));
            }
        }

        List<Collision> collisions = new ArrayList<>();
        try(PreparedStatement lookup = connection.prepareStatement(
            "SELECT id, name, family FROM alias_list WHERE name=? COLLATE NOCASE"))
        {
            for(Target target: targets)
            {
                lookup.setString(1, target.name());
                try(ResultSet row = lookup.executeQuery())
                {
                    if(row.next() && !target.family().equals(row.getString("family")))
                    {
                        String sourceName = row.getString("name");
                        String family = row.getString("family");
                        String replacement = uniqueName(sourceName + " (" + family + ")", names);
                        names.add(normalize(replacement));
                        collisions.add(new Collision(row.getLong("id"), sourceName, replacement, family,
                            referenceCount(connection, sourceName)));
                    }
                }
            }
        }
        return List.copyOf(collisions);
    }

    static void apply(Connection connection, List<Collision> collisions) throws SQLException
    {
        for(Collision collision: collisions)
        {
            try(PreparedStatement rename = connection.prepareStatement(
                "UPDATE alias_list SET name=? WHERE id=? AND name=? COLLATE NOCASE"))
            {
                rename.setString(1, collision.targetName());
                rename.setLong(2, collision.id());
                rename.setString(3, collision.sourceName());
                if(rename.executeUpdate() != 1)
                {
                    throw new SQLException("Custom Alias List changed after migration preflight: " +
                        collision.sourceName());
                }
            }

            try(PreparedStatement update = connection.prepareStatement("""
                UPDATE configuration_channel
                SET alias_list_name=?, config_json=json_set(config_json, '$.aliasListName', ?)
                WHERE alias_list_name=? COLLATE NOCASE
                """))
            {
                update.setString(1, collision.targetName());
                update.setString(2, collision.targetName());
                update.setString(3, collision.sourceName());
                update.executeUpdate();
            }

            for(String table: HISTORICAL_REFERENCE_TABLES)
            {
                try(PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + table + " SET alias_list_name=? WHERE alias_list_name=? COLLATE NOCASE"))
                {
                    update.setString(1, collision.targetName());
                    update.setString(2, collision.sourceName());
                    update.executeUpdate();
                }
            }
        }
    }

    static long referenceCount(List<Collision> collisions)
    {
        return collisions.stream().mapToLong(Collision::referenceCount).sum();
    }

    private static long referenceCount(Connection connection, String name) throws SQLException
    {
        long count = count(connection, "configuration_channel", name);
        for(String table: HISTORICAL_REFERENCE_TABLES)
        {
            count += count(connection, table, name);
        }
        return count;
    }

    private static long count(Connection connection, String table, String name) throws SQLException
    {
        try(PreparedStatement query = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table + " WHERE alias_list_name=? COLLATE NOCASE"))
        {
            query.setString(1, name);
            try(ResultSet row = query.executeQuery())
            {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private static String uniqueName(String requested, Set<String> names)
    {
        for(int sequence = 1; ; sequence++)
        {
            String suffix = sequence == 1 ? "" : " " + sequence;
            int prefixLength = Math.min(requested.length(), MAXIMUM_ALIAS_LIST_NAME_LENGTH - suffix.length());
            String candidate = requested.substring(0, prefixLength).stripTrailing() + suffix;
            if(!names.contains(normalize(candidate)))
            {
                return candidate;
            }
        }
    }

    private static String normalize(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    record Target(String name, String family) {}
    record Collision(long id, String sourceName, String targetName, String family, long referenceCount) {}
}

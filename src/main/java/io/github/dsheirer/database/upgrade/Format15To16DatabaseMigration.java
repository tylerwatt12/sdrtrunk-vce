/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Makes a compatible Alias List a required part of every persisted channel. */
final class Format15To16DatabaseMigration implements DatabaseMigrationStep
{
    private static final int MAXIMUM_ALIAS_LIST_NAME_LENGTH = 25;
    private static final List<Family> FAMILIES = List.of(
        new Family("P25", "Default P25", List.of("P25_CONVENTIONAL", "P25_PHASE1", "P25_PHASE2")),
        new Family("DMR", "Default DMR", List.of("DMR")),
        new Family("NXDN", "Default NXDN", List.of("NXDN")),
        new Family("NBFM", "Default Analog", List.of("AM", "NBFM")));
    private static final List<String> CHANNEL_INDEXES = List.of(
        "idx_configuration_channel_sort", "idx_configuration_channel_alias_list",
        "idx_configuration_channel_decoder", "idx_configuration_channel_frequency",
        "idx_configuration_channel_unique_radioresolve_id");

    @Override
    public String id()
    {
        return "format-15-to-16";
    }

    @Override
    public String description()
    {
        return "Require a compatible Alias List for every saved channel";
    }

    @Override
    public int sourceVersion()
    {
        return 15;
    }

    @Override
    public int targetVersion()
    {
        return 16;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT,
            DatabaseMigrationEffect.UNKNOWN_COUNT);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        long assignments = 0;
        long createdLists = 0;

        for(Family family: FAMILIES)
        {
            long invalid = invalidAssignmentCount(connection, family);
            assignments += invalid;
            if(invalid > 0 && preferredAliasListId(connection, family) == null)
            {
                createdLists++;
            }
        }

        return effects(assignments, createdLists, scalar(connection,
            "SELECT COUNT(*) FROM configuration_channel"));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        requireSource(connection);

        for(Family family: FAMILIES)
        {
            if(invalidAssignmentCount(connection, family) == 0)
            {
                continue;
            }

            Long aliasListId = preferredAliasListId(connection, family);
            if(aliasListId == null)
            {
                aliasListId = createAliasList(connection, family);
            }
            assignInvalidChannels(connection, family, aliasListId);
        }

        rebuildChannelTable(connection);
    }

    private static long invalidAssignmentCount(Connection connection, Family family) throws SQLException
    {
        String decoderPlaceholders = String.join(",", java.util.Collections.nCopies(family.decoders().size(), "?"));
        String sql = """
            SELECT COUNT(*)
            FROM configuration_channel AS channel
            LEFT JOIN alias_list AS list ON list.id=channel.alias_list_id
            WHERE channel.decoder_type IN (%s)
              AND (list.id IS NULL OR list.family<>?)
            """.formatted(decoderPlaceholders);
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            int index = bindDecoders(statement, family);
            statement.setString(index, family.name());
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /** Prefers the visible factory list, then the oldest compatible administrator-owned list. */
    private static Long preferredAliasListId(Connection connection, Family family) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id
            FROM alias_list
            WHERE family=?
            ORDER BY CASE WHEN name=? COLLATE NOCASE THEN 0 ELSE 1 END, id
            LIMIT 1
            """))
        {
            statement.setString(1, family.name());
            statement.setString(2, family.defaultName());
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private static long createAliasList(Connection connection, Family family) throws SQLException
    {
        String name = uniqueName(connection, family.defaultName());
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list(name, family, unmatched_talkgroup_record_enabled)
            VALUES (?, ?, 0)
            """, Statement.RETURN_GENERATED_KEYS))
        {
            statement.setString(1, name);
            statement.setString(2, family.name());
            statement.executeUpdate();
            try(ResultSet keys = statement.getGeneratedKeys())
            {
                if(!keys.next())
                {
                    throw new SQLException("Unable to resolve created Alias List [" + name + "]");
                }
                long aliasListId = keys.getLong(1);
                routeToDefaultScanList(connection, aliasListId);
                return aliasListId;
            }
        }
    }

    private static void routeToDefaultScanList(Connection connection, long aliasListId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership(alias_list_id, scan_list_id)
            SELECT ?, id FROM scan_list WHERE is_default=1
            """))
        {
            statement.setLong(1, aliasListId);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("A recovered Alias List requires exactly one Default scan list");
            }
        }
    }

    private static String uniqueName(Connection connection, String requested) throws SQLException
    {
        Set<String> names = new HashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT name FROM alias_list"))
        {
            while(rows.next())
            {
                names.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }

        for(int sequence = 1; ; sequence++)
        {
            String suffix = sequence == 1 ? "" : " " + sequence;
            int prefixLength = Math.min(requested.length(), MAXIMUM_ALIAS_LIST_NAME_LENGTH - suffix.length());
            String candidate = requested.substring(0, prefixLength).stripTrailing() + suffix;
            if(!names.contains(candidate.toLowerCase(Locale.ROOT)))
            {
                return candidate;
            }
        }
    }

    private static void assignInvalidChannels(Connection connection, Family family, long aliasListId)
        throws SQLException
    {
        String decoderPlaceholders = String.join(",", java.util.Collections.nCopies(family.decoders().size(), "?"));
        String sql = """
            UPDATE configuration_channel AS channel
            SET alias_list_id=?
            WHERE channel.decoder_type IN (%s)
              AND NOT EXISTS (
                  SELECT 1 FROM alias_list AS list
                  WHERE list.id=channel.alias_list_id AND list.family=?
              )
            """.formatted(decoderPlaceholders);
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, aliasListId);
            int index = 2;
            for(String decoder: family.decoders())
            {
                statement.setString(index++, decoder);
            }
            statement.setString(index, family.name());
            statement.executeUpdate();
        }
    }

    private static int bindDecoders(PreparedStatement statement, Family family) throws SQLException
    {
        int index = 1;
        for(String decoder: family.decoders())
        {
            statement.setString(index++, decoder);
        }
        return index;
    }

    private static void rebuildChannelTable(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(String index: CHANNEL_INDEXES)
            {
                statement.executeUpdate("DROP INDEX " + index);
            }
            statement.execute("PRAGMA legacy_alter_table=ON");
            try
            {
                SdrTrunkDatabaseSchema.createConfigurationChannelMigrationTable(connection);
                statement.executeUpdate("""
                    INSERT INTO configuration_channel_format16 (
                        id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                        radioresolve_id, auto_start, auto_start_order, decoder_type, address_domain_code,
                        primary_frequency_hz, config_json
                    )
                    SELECT id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                           radioresolve_id, auto_start, auto_start_order, decoder_type, address_domain_code,
                           primary_frequency_hz, config_json
                    FROM configuration_channel
                    """);
                statement.executeUpdate("DROP TABLE configuration_channel");
                statement.executeUpdate(
                    "ALTER TABLE configuration_channel_format16 RENAME TO configuration_channel");
                SdrTrunkDatabaseSchema.createConfigurationChannelIndexes(connection);
            }
            finally
            {
                statement.execute("PRAGMA legacy_alter_table=OFF");
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 15)
        {
            throw new SQLException("Migration step format-15-to-16 requires exact source format 15");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long assignments, long createdLists, long rebuiltChannels)
    {
        List<DatabaseMigrationEffect> effects = new ArrayList<>();
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "saved channel Alias List assignments", assignments,
            "Preserve compatible assignments and select the preferred compatible list only when one is missing or incompatible"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
            "compatible Alias Lists", createdLists,
            "Create and route a safe family-specific list only when an affected decoder family has no compatible list"));
        effects.add(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "saved channel Alias List constraint", rebuiltChannels,
            "Require every persisted channel to reference an existing Alias List and prevent deletion while channels use it"));
        return List.copyOf(effects);
    }

    private record Family(String name, String defaultName, List<String> decoders)
    {
        private Family
        {
            decoders = List.copyOf(decoders);
        }
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** One-time, staged recovery of factory lists; normal startup never recreates deleted lists. */
final class Format10To11DatabaseMigration implements DatabaseMigrationStep
{
    private static final String OLD_ANALOG_NAME = "Default NBFM";
    private static final String ANALOG_NAME = "Default Analog";
    // Freeze this step's names and decoder mapping independently of future factory defaults.
    private static final List<FactoryList> LISTS = List.of(
        new FactoryList("Default P25", "P25", List.of("P25_CONVENTIONAL", "P25_PHASE1", "P25_PHASE2")),
        new FactoryList("Default DMR", "DMR", List.of("DMR")),
        new FactoryList("Default NXDN", "NXDN", List.of("NXDN")),
        new FactoryList(ANALOG_NAME, "NBFM", List.of("AM", "NBFM")));

    @Override
    public String id()
    {
        return "format-10-to-11";
    }

    @Override
    public String description()
    {
        return "Restore factory Alias Lists and name the analog list clearly";
    }

    @Override
    public int sourceVersion()
    {
        return 10;
    }

    @Override
    public int targetVersion()
    {
        return 11;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(-1, -1, -1, -1);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        long missing = 0;
        long unassigned = 0;
        StoredList rename = renameCandidate(connection);
        for(FactoryList factory: LISTS)
        {
            StoredList stored = lookup(connection, factory.name());
            if(stored != null && !factory.family().equals(stored.family()))
            {
                throw new SQLException("Factory Alias List [" + factory.name() +
                    "] belongs to an incompatible family. Rename that custom list in the previous build first.");
            }
            if(stored == null && !(factory.name().equals(ANALOG_NAME) && rename != null))
            {
                missing++;
            }
            for(String decoder: factory.decoders())
            {
                unassigned += count(connection, """
                    SELECT COUNT(*) FROM configuration_channel
                    WHERE decoder_type = ? AND (alias_list_name IS NULL OR trim(alias_list_name) = '')
                    """, decoder);
            }
        }
        // Never choose between contradictory administrator-facing list references.
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT COUNT(*) FROM configuration_channel
            WHERE coalesce(trim(alias_list_name), '') COLLATE NOCASE <>
                  coalesce(trim(json_extract(config_json, '$.aliasListName')), '')
            """))
        {
            if(rows.next() && rows.getLong(1) != 0)
            {
                throw new SQLException("Saved channel Alias List references disagree with their configuration. " +
                    "Resave the affected channels in the previous build before migrating.");
            }
        }
        long references = rename == null ? 0 : count(connection, """
            SELECT COUNT(*) FROM configuration_channel WHERE alias_list_name = ? COLLATE NOCASE
            """, rename.name());
        return effects(rename == null ? 0 : 1, references, missing, unassigned);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        validateSource(connection);
        StoredList rename = renameCandidate(connection);
        if(rename != null)
        {
            try(var update = connection.prepareStatement("UPDATE alias_list SET name = ? WHERE id = ?"))
            {
                update.setString(1, ANALOG_NAME);
                update.setLong(2, rename.id());
                update.executeUpdate();
            }
            try(var update = connection.prepareStatement("""
                UPDATE configuration_channel
                SET alias_list_name = ?, config_json = json_set(config_json, '$.aliasListName', ?)
                WHERE alias_list_name = ? COLLATE NOCASE
                """))
            {
                update.setString(1, ANALOG_NAME);
                update.setString(2, ANALOG_NAME);
                update.setString(3, rename.name());
                update.executeUpdate();
            }
        }
        for(FactoryList factory: LISTS)
        {
            StoredList stored = lookup(connection, factory.name());
            if(stored == null)
            {
                try(var insert = connection.prepareStatement("""
                    INSERT INTO alias_list(name, family, unmatched_talkgroup_record_enabled) VALUES (?, ?, 0)
                    """))
                {
                    insert.setString(1, factory.name());
                    insert.setString(2, factory.family());
                    insert.executeUpdate();
                }
                stored = lookup(connection, factory.name());
                try(var route = connection.prepareStatement("""
                    INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership(alias_list_id, scan_list_id)
                    SELECT ?, id FROM scan_list WHERE is_default = 1
                    """))
                {
                    route.setLong(1, stored.id());
                    route.executeUpdate();
                }
            }
            // Existing/renamed lists keep their exact routing and recording policy.
            try(var assign = connection.prepareStatement("""
                UPDATE configuration_channel
                SET alias_list_name = ?, config_json = json_set(config_json, '$.aliasListName', ?)
                WHERE decoder_type = ? AND (alias_list_name IS NULL OR trim(alias_list_name) = '')
                """))
            {
                for(String decoder: factory.decoders())
                {
                    assign.setString(1, stored.name());
                    assign.setString(2, stored.name());
                    assign.setString(3, decoder);
                    assign.addBatch();
                }
                assign.executeBatch();
            }
        }
    }

    private static StoredList renameCandidate(Connection connection) throws SQLException
    {
        StoredList old = lookup(connection, OLD_ANALOG_NAME);
        return old != null && "NBFM".equals(old.family()) && lookup(connection, ANALOG_NAME) == null ? old : null;
    }

    private static StoredList lookup(Connection connection, String name) throws SQLException
    {
        try(var query = connection.prepareStatement("SELECT id, name, family FROM alias_list WHERE name = ? COLLATE NOCASE"))
        {
            query.setString(1, name);
            try(ResultSet row = query.executeQuery())
            {
                return row.next() ? new StoredList(row.getLong(1), row.getString(2), row.getString(3)) : null;
            }
        }
    }

    private static long count(Connection connection, String sql, String value) throws SQLException
    {
        try(var query = connection.prepareStatement(sql))
        {
            query.setString(1, value);
            try(ResultSet row = query.executeQuery())
            {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private static void requireSource(Connection connection) throws SQLException
    {
        if(DatabaseFormatCatalog.inspect(connection).version() != 10)
        {
            throw new SQLException("Migration step format-10-to-11 requires exact source format 10");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long renamed, long references, long created, long assigned)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "factory analog Alias List name",
                renamed, "Rename same-family Default NBFM to Default Analog only when the target name is free; " +
                    "preserve both lists if Default Analog already exists"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "renamed Alias List channel references",
                references, "Update saved channel references while preserving their other configuration"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "missing factory Alias Lists",
                created, "Restore missing Default P25, Default DMR, Default NXDN, and Default Analog lists, " +
                    "including previously deleted factory names"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "new factory list browser routes",
                created, "Route only newly created lists to the Default scan list, with recording disabled"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "unassigned channel Alias Lists",
                assigned, "Assign compatible factory lists only to channels with no selected Alias List"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE, "existing administrator configuration",
                DatabaseMigrationEffect.UNKNOWN_COUNT, "Preserve list and alias IDs, custom names, existing routes, " +
                    "recording settings, streams, credentials, and already assigned channels"));
    }

    private record FactoryList(String name, String family, List<String> decoders) {}
    private record StoredList(long id, String name, String family) {}
}

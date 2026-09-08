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
    private static final List<FactoryAliasListCollisionRepair.Target> FACTORY_TARGETS = LISTS.stream()
        .map(list -> new FactoryAliasListCollisionRepair.Target(list.name(), list.family())).toList();

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
        return effects(-1, -1, -1, -1, -1, -1, -1, -1);
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        requireSource(connection);
        long missing = 0;
        long unassigned = 0;
        List<FactoryAliasListCollisionRepair.Collision> collisions =
            FactoryAliasListCollisionRepair.plan(connection, FACTORY_TARGETS);
        StoredList rename = renameCandidate(connection, collisions);
        for(FactoryList factory: LISTS)
        {
            StoredList stored = lookup(connection, factory.name());
            boolean missingOrMoved = stored == null || !factory.family().equals(stored.family());
            if(missingOrMoved && !(factory.name().equals(ANALOG_NAME) && rename != null))
            {
                missing++;
            }
            for(String decoder: factory.decoders())
            {
                unassigned += count(connection, """
                    SELECT COUNT(*) FROM configuration_channel
                    WHERE decoder_type = ? AND (alias_list_name IS NULL OR trim(alias_list_name) = '')
                      AND NOT EXISTS (
                          SELECT 1 FROM alias_list AS recovered_list
                          WHERE recovered_list.name=(
                              CASE WHEN json_valid(configuration_channel.config_json)=1 THEN
                                  CASE WHEN json_type(configuration_channel.config_json, '$.aliasListName')='text'
                                      THEN nullif(trim(json_extract(configuration_channel.config_json,
                                          '$.aliasListName')), '') END
                              END
                          ) COLLATE NOCASE
                      )
                    """, decoder);
            }
        }
        long projectionRepairs = channelAliasListProjectionRepairCount(connection);
        long references = rename == null ? 0 :
            FactoryAliasListCollisionRepair.channelReferenceCount(connection, rename.name());
        return effects(rename == null ? 0 : 1, references, collisions.size(),
            FactoryAliasListCollisionRepair.referenceCount(collisions), projectionRepairs,
            LegacyDefaultScanListRepair.repairCount(connection), missing, unassigned);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        validateSource(connection);
        LegacyDefaultScanListRepair.ensureOneDefault(connection);
        FactoryAliasListCollisionRepair.recoverJsonOwnedChannelProjections(connection);
        List<FactoryAliasListCollisionRepair.Collision> collisions =
            FactoryAliasListCollisionRepair.plan(connection, FACTORY_TARGETS);
        FactoryAliasListCollisionRepair.apply(connection, collisions);
        repairChannelAliasListProjection(connection);
        StoredList rename = renameCandidate(connection, List.of());
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
                SET alias_list_name = ?, config_json = CASE WHEN json_valid(config_json)=1
                    THEN json_set(config_json, '$.aliasListName', ?) ELSE config_json END
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
                SET alias_list_name = ?, config_json = CASE WHEN json_valid(config_json)=1
                    THEN json_set(config_json, '$.aliasListName', ?) ELSE config_json END
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

    private static StoredList renameCandidate(Connection connection,
                                              List<FactoryAliasListCollisionRepair.Collision> collisions)
        throws SQLException
    {
        StoredList old = lookup(connection, OLD_ANALOG_NAME);
        StoredList target = lookup(connection, ANALOG_NAME);
        boolean targetAvailable = target == null || collisions.stream().anyMatch(collision -> collision.id() == target.id());
        return old != null && "NBFM".equals(old.family()) && targetAvailable ? old : null;
    }

    private static long channelAliasListProjectionRepairCount(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT COUNT(*) FROM configuration_channel
            WHERE json_valid(config_json)=1 AND coalesce(trim(alias_list_name), '') COLLATE NOCASE <>
                  coalesce(trim(json_extract(config_json, '$.aliasListName')), '')
            """))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static void repairChannelAliasListProjection(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE configuration_channel
                SET config_json = CASE
                    WHEN alias_list_name IS NULL OR trim(alias_list_name) = ''
                        THEN json_remove(config_json, '$.aliasListName')
                    ELSE json_set(config_json, '$.aliasListName', alias_list_name)
                END
                WHERE json_valid(config_json)=1 AND coalesce(trim(alias_list_name), '') COLLATE NOCASE <>
                      coalesce(trim(json_extract(config_json, '$.aliasListName')), '')
                """);
        }
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
        if(DatabaseFormatCatalog.inspectForMigration(connection).version() != 10)
        {
            throw new SQLException("Migration step format-10-to-11 requires exact source format 10");
        }
    }

    private static List<DatabaseMigrationEffect> effects(long renamed, long references, long collisionLists,
                                                          long collisionReferences, long projectionRepairs,
                                                          long defaultScanListRepair, long created, long assigned)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "custom Alias Lists using factory names", collisionLists,
                "Move wrong-family custom lists to unique names and preserve " +
                    (collisionReferences == DatabaseMigrationEffect.UNKNOWN_COUNT ? "their saved references" :
                        collisionReferences + " saved reference(s)")),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "saved channel Alias List projections", projectionRepairs,
                "Recover an existing JSON-selected list when the relational projection is unusable, then make both " +
                    "saved projections agree"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "factory analog Alias List name",
                renamed, "Rename same-family Default NBFM to Default Analog only when the target name is free; " +
                    "preserve both lists if Default Analog already exists"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "renamed Alias List channel references",
                references, "Update saved channel references while preserving their other configuration"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "Default scan list",
                defaultScanListRepair,
                "Restore one deterministic published Default selection before adding factory-list routes"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "missing factory Alias Lists",
                created, "Restore missing Default P25, Default DMR, Default NXDN, and Default Analog lists, " +
                    "including previously deleted factory names"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "new factory list browser routes",
                created, "Route only newly created lists to the Default scan list, with recording disabled"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "unassigned channel Alias Lists",
                assigned, "Assign compatible factory lists only to channels with no selected Alias List"));
    }

    private record FactoryList(String name, String family, List<String> decoders) {}
    private record StoredList(long id, String name, String family) {}
}

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rekeys format-5 conventional receiver contexts from the former RadioResolve GUID identity to the configured
 * channel identity used by format 6. Receiver-context row identifiers remain unchanged so every dependent activity
 * row continues to reference the same context.
 */
final class Format5To6DatabaseMigration implements DatabaseMigrationStep
{
    private static final String P25_SCHEMA_VERSION_KEY = "p25_activity_schema_version";
    private static final String SOURCE_P25_SCHEMA_VERSION = "28";
    private static final String TARGET_P25_SCHEMA_VERSION = "29";

    @Override
    public String id()
    {
        return "format-5-to-6";
    }

    @Override
    public String description()
    {
        return "Normalize configured conventional receiver-context identities without rewriting activity history";
    }

    @Override
    public int sourceVersion()
    {
        return 5;
    }

    @Override
    public int targetVersion()
    {
        return 6;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(effect(DatabaseMigrationEffect.UNKNOWN_COUNT));
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        return List.of(effect(input.rekeys().size()));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE receiver_context
            SET context_key = ?
            WHERE id = ? AND context_key = ? AND guid = ? AND kind_code = ?
            """))
        {
            for(ContextRekey rekey: input.rekeys())
            {
                statement.setString(1, rekey.targetContextKey());
                statement.setLong(2, rekey.contextId());
                statement.setString(3, rekey.sourceContextKey());
                statement.setString(4, rekey.guid());
                statement.setInt(5, rekey.kindCode());

                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Configured conventional receiver context changed after preflight: row " +
                        rekey.contextId());
                }
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE database_metadata
            SET value = ?, updated_at_ms = ?
            WHERE key = ? AND value = ?
            """))
        {
            statement.setString(1, TARGET_P25_SCHEMA_VERSION);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, P25_SCHEMA_VERSION_KEY);
            statement.setString(4, SOURCE_P25_SCHEMA_VERSION);

            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Required format-5 metadata changed after preflight: " +
                    P25_SCHEMA_VERSION_KEY);
            }
        }
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        requireSourceFormat(connection);
        Map<Long,ConfiguredContextMatch> matches = new LinkedHashMap<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT config.id AS configuration_row_id, config.configuration_id, config.radres_guid,
                   candidate.id AS candidate_id, candidate.context_key AS candidate_context_key,
                   candidate.guid AS candidate_guid, candidate.kind_code AS candidate_kind_code,
                   target.id AS target_id
            FROM configuration_channel AS config
            LEFT JOIN receiver_context AS candidate
              ON lower(candidate.guid) = config.radres_guid
            LEFT JOIN receiver_context AS target
              ON target.context_key = 'CONFIGURATION:' || config.configuration_id
            WHERE config.channel_kind = 'CONVENTIONAL'
              AND config.radres_guid IS NOT NULL
              AND length(trim(config.radres_guid)) > 0
            ORDER BY config.id, candidate.id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long configurationRowId = resultSet.getLong("configuration_row_id");
                ConfiguredContextMatch match = matches.get(configurationRowId);

                if(match == null)
                {
                    match = new ConfiguredContextMatch(configurationRowId,
                        resultSet.getString("configuration_id"), resultSet.getString("radres_guid"),
                        nullableLong(resultSet, "target_id"));
                    matches.put(configurationRowId, match);
                }

                Long candidateId = nullableLong(resultSet, "candidate_id");

                if(candidateId != null)
                {
                    match.candidates().add(new ContextCandidate(candidateId,
                        resultSet.getString("candidate_context_key"), resultSet.getString("candidate_guid"),
                        resultSet.getInt("candidate_kind_code")));
                }
            }
        }

        List<ContextRekey> rekeys = new ArrayList<>();

        for(ConfiguredContextMatch match: matches.values())
        {
            inspectMatch(match, rekeys);
        }

        return new MigrationInput(List.copyOf(rekeys));
    }

    private static void inspectMatch(ConfiguredContextMatch match, List<ContextRekey> rekeys) throws SQLException
    {
        String expectedContextKey = "CONFIGURATION:" + match.configurationId();

        if(match.candidates().isEmpty())
        {
            if(match.targetId() != null)
            {
                throw refusal(match, "the configured target key is owned by a context without the exact GUID");
            }

            return;
        }

        if(match.candidates().size() != 1)
        {
            throw refusal(match, "more than one receiver context matches the GUID case-insensitively");
        }

        ContextCandidate candidate = match.candidates().getFirst();

        if(!match.guid().equals(candidate.guid()))
        {
            throw refusal(match, "the receiver-context GUID is not the exact canonical configured GUID");
        }

        if(!isConventionalKind(candidate.kindCode()))
        {
            throw refusal(match, "the matching receiver context is not a recognized conventional context");
        }

        if(match.targetId() != null && match.targetId() != candidate.id())
        {
            throw refusal(match, "the configured target key is already owned by another receiver context");
        }

        if(expectedContextKey.equals(candidate.contextKey()))
        {
            return;
        }

        String legacyContextKey = "GUID:" + match.guid();

        if(!legacyContextKey.equals(candidate.contextKey()))
        {
            throw refusal(match, "the matching receiver context has an unexpected identity key");
        }

        rekeys.add(new ContextRekey(candidate.id(), candidate.contextKey(), expectedContextKey,
            match.guid(), candidate.kindCode()));
    }

    private static boolean isConventionalKind(int kindCode)
    {
        return kindCode == 2 || kindCode == 3 || kindCode == 4 || kindCode == 10;
    }

    private static SQLException refusal(ConfiguredContextMatch match, String reason)
    {
        return new SQLException("Refusing format-5-to-6 migration for conventional configuration row " +
            match.configurationRowId() + ": " + reason);
    }

    private static DatabaseMigrationEffect effect(long affectedRows)
    {
        return new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "configured conventional receiver-context identities", affectedRows,
            "Replace exact legacy GUID keys with configuration UUID keys while preserving receiver-context IDs " +
                "and all dependent activity history");
    }

    private static void requireSourceFormat(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);

        if(detected.version() != 5)
        {
            throw new SQLException("Migration step format-5-to-6 requires exact source format 5; found " +
                detected.version() + " [" + detected.id() + "]");
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException
    {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record MigrationInput(List<ContextRekey> rekeys)
    {
    }

    private record ContextRekey(long contextId, String sourceContextKey, String targetContextKey,
                                String guid, int kindCode)
    {
    }

    private record ContextCandidate(long id, String contextKey, String guid, int kindCode)
    {
    }

    private record ConfiguredContextMatch(long configurationRowId, String configurationId, String guid,
                                          Long targetId, List<ContextCandidate> candidates)
    {
        private ConfiguredContextMatch(long configurationRowId, String configurationId, String guid, Long targetId)
        {
            this(configurationRowId, configurationId, guid, targetId, new ArrayList<>());
        }
    }
}

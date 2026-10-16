/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Retires settings whose processing is now owned solely by the selected JMBE library. */
final class Format55To56DatabaseMigration implements DatabaseMigrationStep
{
    private static final String VOICE_KEY = "audio.voice_enhancement";
    private static final String PORTABLE_KEY = "portable_java_preferences_v1";
    private static final String AUDIO_NODE = "user/io/github/dsheirer/preference/audio";
    private static final String AGC_KEY = "audio.output.continuous.agc.enabled";
    // Match the existing portable preference reader and bounded component repair (last duplicate value wins).
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String id() { return "format-55-to-56"; }
    @Override public String description() { return "Remove retired voice-enhancement and continuous-gain settings"; }
    @Override public int sourceVersion() { return 55; }
    @Override public int targetVersion() { return 56; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return effects(DatabaseMigrationEffect.UNKNOWN_COUNT, DatabaseMigrationEffect.UNKNOWN_COUNT);
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return effects(voiceRows(connection), portableUpdate(connection).removed());
    }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        PortableUpdate portable = portableUpdate(connection);
        try(var remove = connection.prepareStatement("DELETE FROM application_settings WHERE key=?"))
        {
            remove.setString(1, VOICE_KEY);
            remove.executeUpdate();
        }
        if(portable.removed() > 0)
        {
            try(var update = connection.prepareStatement("UPDATE application_settings SET settings_json=? WHERE key=?"))
            {
                update.setString(1, portable.json());
                update.setString(2, PORTABLE_KEY);
                if(update.executeUpdate() != 1) throw new SQLException("Portable preference retirement could not be saved");
            }
        }
    }

    /** Strict current admission detects partial retirement without changing saved data during startup. */
    static void validateRetirement(Connection connection) throws SQLException
    {
        if(voiceRows(connection) != 0 || portableUpdate(connection).removed() != 0)
            throw new SQLException("Retired audio settings remain in the current database; restore a valid database before starting");
    }

    private static int voiceRows(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("SELECT COUNT(*) FROM application_settings WHERE key=?"))
        {
            query.setString(1, VOICE_KEY);
            try(var row = query.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    private static PortableUpdate portableUpdate(Connection connection) throws SQLException
    {
        try(var query = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text' AND length(CAST(settings_json AS BLOB))<=4194304
                        THEN settings_json END AS settings_json
            FROM application_settings WHERE key=?
            """))
        {
            query.setString(1, PORTABLE_KEY);
            try(var row = query.executeQuery())
            {
                if(!row.next()) return new PortableUpdate(null, 0);
                String source = row.getString(1);
                try
                {
                    if(source != null && JSON.readTree(source) instanceof ObjectNode root &&
                        root.get(AUDIO_NODE) instanceof ObjectNode audio && audio.has(AGC_KEY))
                    {
                        audio.remove(AGC_KEY);
                        if(audio.isEmpty()) root.remove(AUDIO_NODE);
                        return new PortableUpdate(JSON.writeValueAsString(root), 1);
                    }
                }
                catch(java.io.IOException | RuntimeException malformed)
                {
                    // Existing bounded component repair owns unreadable portable settings and reports its resets.
                    // Retirement never discards unrelated preferences or adds a new parser-based refusal.
                }
                return new PortableUpdate(null, 0);
            }
        }
    }

    private static List<DatabaseMigrationEffect> effects(long voice, long agc)
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "active settings, configuration and receiver history", 0,
                "Preserve Remote Links, paging-tone detection, recording and streaming choices, credentials, history and allocators"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired voice-enhancement setting", voice,
                "Remove the former application-managed voice-enhancement choice; the selected library owns audio processing"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired continuous-gain preference", agc,
                "Remove only the former audio-output continuous-gain key; preserve unrelated portable settings and timestamps"));
    }

    private record PortableUpdate(String json, int removed) {}
}

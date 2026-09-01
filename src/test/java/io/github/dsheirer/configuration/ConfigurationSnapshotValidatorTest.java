/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.util.List;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSnapshotValidatorTest
{
    private static final String DUPLICATE_ID = "11111111-2222-4333-8444-555555555555";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void startupRejectsDuplicateChannelIdentitiesWithoutRewritingThem()
    {
        Channel first = new Channel("First");
        Channel second = new Channel("Second");
        first.setConfigurationId(DUPLICATE_ID);
        second.setConfigurationId(DUPLICATE_ID);
        ConfigurationState state = state(List.of(), List.of(first, second), List.of());

        assertThrows(IllegalArgumentException.class,
            () -> ConfigurationSnapshotValidator.validateForStartup(state));
        assertEquals(DUPLICATE_ID, first.getConfigurationId());
        assertEquals(DUPLICATE_ID, second.getConfigurationId());
    }

    @Test
    void startupRejectsDuplicateBroadcastIdentitiesWithoutRewritingThem()
    {
        BroadcastConfiguration first = new OpenMHzConfiguration(BroadcastFormat.MP3);
        BroadcastConfiguration second = new OpenMHzConfiguration(BroadcastFormat.MP3);
        first.setConfigurationId(DUPLICATE_ID);
        second.setConfigurationId(DUPLICATE_ID);
        ConfigurationState state = state(List.of(), List.of(), List.of(first, second));

        assertThrows(IllegalArgumentException.class,
            () -> ConfigurationSnapshotValidator.validateForStartup(state));
        assertEquals(DUPLICATE_ID, first.getConfigurationId());
        assertEquals(DUPLICATE_ID, second.getConfigurationId());
    }

    @Test
    void startupAcceptsSameFamilyAndRejectsCrossFamilyAliasListAssignments()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Channel p25 = new Channel("P25");
        p25.setAliasListName("County");
        p25.setDecodeConfiguration(new DecodeConfigP25Phase1());
        p25.setConfigurationId(DUPLICATE_ID);
        assertDoesNotThrow(() -> ConfigurationSnapshotValidator.validateForStartup(
            state(List.of(definition), List.of(p25), List.of())));

        Channel dmr = new Channel("DMR");
        dmr.setAliasListName("County");
        dmr.setDecodeConfiguration(new DecodeConfigDMR());
        dmr.setConfigurationId("21111111-2222-4333-8444-555555555555");
        assertThrows(IllegalArgumentException.class, () -> ConfigurationSnapshotValidator.validateForStartup(
            state(List.of(definition), List.of(dmr), List.of())));
    }

    @Test
    void startupRejectsGeneratedIdentityThatWouldRequireSilentRepair()
    {
        Channel channel = new Channel("Unsaved Identity");

        assertThrows(IllegalArgumentException.class, () -> ConfigurationSnapshotValidator.validateForStartup(
            state(List.of(), List.of(channel), List.of())));
    }

    @Test
    void rejectsMalformedJsonInEveryPersistedJsonColumn() throws Exception
    {
        List<String> definitions = List.of(
            "configuration_channel(config_json TEXT NOT NULL)",
            "configuration_channel_map(config_json TEXT NOT NULL)",
            "configuration_broadcast_stream(config_json TEXT NOT NULL)",
            "application_settings(settings_json TEXT NOT NULL)",
            "application_icons(icons_json TEXT NOT NULL)");

        for(String definition: definitions)
        {
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement())
            {
                statement.executeUpdate("CREATE TABLE configuration_channel(config_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE configuration_channel_map(config_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE configuration_broadcast_stream(config_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE application_settings(settings_json TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE application_icons(icons_json TEXT NOT NULL)");

                String table = definition.substring(0, definition.indexOf('('));
                String column = definition.substring(definition.indexOf('(') + 1, definition.indexOf(' '));
                statement.executeUpdate("INSERT INTO " + table + "(" + column + ") VALUES ('not-json')");

                SQLException error = assertThrows(SQLException.class,
                    () -> ConfigurationSnapshotValidator.validatePersistedJson(connection));
                org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains(table + "." + column));
            }
        }
    }

    @Test
    void acceptsValidJsonAcrossAllPersistedJsonColumns() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE configuration_channel(config_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE configuration_channel_map(config_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE configuration_broadcast_stream(config_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE application_settings(settings_json TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE application_icons(icons_json TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO configuration_channel VALUES ('{}')");
            statement.executeUpdate("INSERT INTO configuration_channel_map VALUES ('[]')");
            statement.executeUpdate("INSERT INTO configuration_broadcast_stream VALUES ('null')");
            statement.executeUpdate("INSERT INTO application_settings VALUES ('true')");
            statement.executeUpdate("INSERT INTO application_icons VALUES ('\"icon-set\"')");

            assertDoesNotThrow(() -> ConfigurationSnapshotValidator.validatePersistedJson(connection));
        }
    }

    @Test
    void materializesEveryKnownRuntimeSettingsAndIconPayload() throws Exception
    {
        Path database = createKnownPayloadDatabase();
        assertDoesNotThrow(() -> ConfigurationSnapshotValidator.validateKnownRuntimePayloads(database));
    }

    @Test
    void rejectsSyntacticallyValidButTypeInvalidUiSettings() throws Exception
    {
        Path database = createKnownPayloadDatabase();
        updatePayload(database, "application_settings", "key", "ui.settings", "settings_json",
            "{\"settings\":{}}");
        assertThrows(java.io.IOException.class,
            () -> ConfigurationSnapshotValidator.validateKnownRuntimePayloads(database));
    }

    @Test
    void rejectsSyntacticallyValidButTypeInvalidTunerSettings() throws Exception
    {
        Path database = createKnownPayloadDatabase();
        updatePayload(database, "application_settings", "key", "tuner.settings", "settings_json", "[]");
        assertThrows(java.io.IOException.class,
            () -> ConfigurationSnapshotValidator.validateKnownRuntimePayloads(database));
    }

    @Test
    void rejectsSyntacticallyValidButTypeInvalidDefaultIconSet() throws Exception
    {
        Path database = createKnownPayloadDatabase();
        updatePayload(database, "application_icons", "key", "default", "icons_json", "{\"icons\":{}}");
        assertThrows(java.io.IOException.class,
            () -> ConfigurationSnapshotValidator.validateKnownRuntimePayloads(database));
    }

    private Path createKnownPayloadDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve("known-payloads-" + java.util.UUID.randomUUID() + ".sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE application_settings(
                    key TEXT PRIMARY KEY,
                    settings_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0)
                """);
            statement.executeUpdate("""
                CREATE TABLE application_icons(
                    key TEXT PRIMARY KEY,
                    icons_json TEXT NOT NULL,
                    updated_at_ms INTEGER NOT NULL DEFAULT 0)
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json) VALUES
                    ('ui.settings', '{"settings":[]}'),
                    ('tuner.settings', '{"disabledTuners":[],"tunerConfigurations":[]}')
                """);
            statement.executeUpdate("""
                INSERT INTO application_icons(key, icons_json) VALUES('default', '{"icons":[]}')
                """);
        }
        return database;
    }

    private static void updatePayload(Path database, String table, String keyColumn, String key,
                                      String valueColumn, String value) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "UPDATE " + table + " SET " + valueColumn + "=? WHERE " + keyColumn + "=?"))
        {
            statement.setString(1, value);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private static ConfigurationState state(List<AliasListDefinition> definitions, List<Channel> channels,
                                            List<BroadcastConfiguration> broadcasts)
    {
        ConfigurationState state = new ConfigurationState();
        state.setAliasListDefinitions(definitions);
        state.setChannels(channels);
        state.setBroadcastConfigurations(broadcasts);
        return state;
    }
}

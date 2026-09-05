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

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebConfiguredEntityRepositoryTest
{
    private static final String TRUNKED_ID = "4b75217f-2555-4c38-aafc-5d17bc0faf71";
    private static final String CONVENTIONAL_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";

    @Test
    void savedConfigurationOwnsExistenceAndActivityIsOptional() throws Exception
    {
        try(Connection connection = database())
        {
            insertConfiguration(connection, 1, TRUNKED_ID, "TRUNKED", "P25_PHASE1");
            insertConfiguration(connection, 2, CONVENTIONAL_ID, "CONVENTIONAL", "NBFM");
            WebConfiguredEntityRepository repository = new WebConfiguredEntityRepository();

            WebConfiguredEntityRepository.ConfiguredChannel trunked =
                repository.requireChannel(connection, TRUNKED_ID);
            assertNull(trunked.channelId());
            assertEquals(Map.of("kind", "channel", "key", TRUNKED_ID),
                trunked.toApiMap().get("entity_ref"));

            WebConfiguredEntityRepository.ConfiguredChannel conventional =
                repository.requireChannel(connection, CONVENTIONAL_ID);
            assertNull(conventional.channelId());
            assertEquals(Map.of("kind", "channel", "key", CONVENTIONAL_ID),
                conventional.toApiMap().get("entity_ref"));
            StatsApiException uppercase = assertThrows(StatsApiException.class,
                () -> repository.requireChannel(connection, TRUNKED_ID.toUpperCase()));
            assertEquals(400, uppercase.status());
        }
    }

    @Test
    void deletedConfigurationIsNotResurrectedByRetainedReceiverActivity() throws Exception
    {
        try(Connection connection = database(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                VALUES (10, '%s', 1000, 2000)
                """.formatted(TRUNKED_ID));
            StatsApiException exception = assertThrows(StatsApiException.class,
                () -> new WebConfiguredEntityRepository().requireChannel(connection, TRUNKED_ID));
            assertEquals(404, exception.status());
        }
    }

    @Test
    void resolvesOnlyTheExactConfiguredContext() throws Exception
    {
        try(Connection connection = database(); Statement statement = connection.createStatement())
        {
            insertConfiguration(connection, 2, CONVENTIONAL_ID, "CONVENTIONAL", "DMR");
            statement.executeUpdate("""
                INSERT INTO radio_system(id, system_key, configuration_id, protocol_code,
                    address_domain_code, first_seen_ms, last_seen_ms)
                VALUES (7, 'dmr:channel:728d2d66-de4e-476b-a696-919f32dd4d12',
                    '728d2d66-de4e-476b-a696-919f32dd4d12', 3, 0, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id)
                VALUES (10, '%s', 1000, 2000, 7),
                       (12, '00000000-0000-0000-0000-000000000001', 1000, 4000, NULL)
                """.formatted(CONVENTIONAL_ID));
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                new WebConfiguredEntityRepository().requireChannel(connection, CONVENTIONAL_ID);
            assertEquals(10L, configured.channelId());
            assertEquals(851012500L, configured.primaryFrequencyHz());
            assertEquals("dmr:channel:728d2d66-de4e-476b-a696-919f32dd4d12", configured.radioSystemKey());
        }
    }

    private static Connection database() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE configuration_channel (
                    id INTEGER PRIMARY KEY,
                    configuration_id TEXT NOT NULL UNIQUE,
                    channel_kind TEXT NOT NULL,
                    sort_order INTEGER NOT NULL,
                    system_name TEXT,
                    site_name TEXT,
                    name TEXT,
                    alias_list_id INTEGER,
                    decoder_type TEXT,
                    primary_frequency_hz INTEGER,
                    address_domain_code INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.executeUpdate("CREATE TABLE alias_list (id INTEGER PRIMARY KEY, name TEXT)");
            statement.executeUpdate("""
                CREATE TABLE radio_system (
                    id INTEGER PRIMARY KEY,
                    system_key TEXT NOT NULL UNIQUE,
                    configuration_id TEXT,
                    protocol_code INTEGER NOT NULL,
                    address_domain_code INTEGER NOT NULL,
                    p25_wacn INTEGER,
                    p25_system_id INTEGER,
                    dmr_model_code INTEGER,
                    dmr_network_id INTEGER,
                    nxdn_location_category_code INTEGER,
                    nxdn_system_id INTEGER,
                    first_seen_ms INTEGER,
                    last_seen_ms INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE receiver_channel (
                    id INTEGER PRIMARY KEY,
                    configuration_id TEXT NOT NULL UNIQUE,
                    first_seen_ms INTEGER,
                    last_seen_ms INTEGER,
                    radio_system_id INTEGER
                )
                """);
        }

        return connection;
    }

    private static void insertConfiguration(Connection connection, int id, String configurationId,
                                            String kind, String decoder) throws Exception
    {
        try(var statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(id, configuration_id, channel_kind, sort_order, system_name,
                site_name, name, alias_list_id, decoder_type, primary_frequency_hz)
            VALUES (?, ?, ?, ?, 'County', 'Downtown', 'Primary', NULL, ?, 851012500)
            """))
        {
            statement.setInt(1, id);
            statement.setString(2, configurationId);
            statement.setString(3, kind);
            statement.setInt(4, id);
            statement.setString(5, decoder);
            statement.executeUpdate();
        }
    }
}

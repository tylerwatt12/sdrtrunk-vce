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
    private static final String SITE_ID = "4b75217f-2555-4c38-aafc-5d17bc0faf71";
    private static final String CONVENTIONAL_ID = "728d2d66-de4e-476b-a696-919f32dd4d12";
    private static final String CONVENTIONAL_RADRES_GUID = "a1b2c3d4-e5f6-4789-8abc-def012345678";

    @Test
    void savedConfigurationOwnsExistenceAndActivityIsOptional() throws Exception
    {
        try(Connection connection = database())
        {
            insertConfiguration(connection, 1, SITE_ID, "TRUNKED", SITE_ID, "P25_PHASE1");
            insertConfiguration(connection, 2, CONVENTIONAL_ID, "CONVENTIONAL", null, "NBFM");
            WebConfiguredEntityRepository repository = new WebConfiguredEntityRepository();

            WebConfiguredEntityRepository.ConfiguredChannel site = repository.requireSite(connection, SITE_ID);
            assertNull(site.contextId());
            assertEquals(Map.of("kind", "site", "key", SITE_ID), site.toApiMap().get("entity_ref"));
            assertFalse(site.toApiMap().containsKey("alias_list_id"),
                "internal exact-scope ownership must not leak into the configured-channel DTO");

            WebConfiguredEntityRepository.ConfiguredChannel conventional =
                repository.requireConventional(connection, CONVENTIONAL_ID);
            assertNull(conventional.contextId());
            assertEquals(Map.of("kind", "conventional", "key", CONVENTIONAL_ID),
                conventional.toApiMap().get("entity_ref"));
            StatsApiException uppercase = assertThrows(StatsApiException.class,
                () -> repository.requireSite(connection, SITE_ID.toUpperCase()));
            assertEquals(400, uppercase.status());
        }
    }

    @Test
    void deletedConfigurationIsNotResurrectedByRetainedReceiverActivity() throws Exception
    {
        try(Connection connection = database(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_context(id, context_key, first_seen_ms, last_seen_ms, nac,
                    primary_frequency_hz)
                VALUES (10, 'GUID:%s', 1000, 2000, 0x293, 851012500)
                """.formatted(SITE_ID));
            StatsApiException exception = assertThrows(StatsApiException.class,
                () -> new WebConfiguredEntityRepository().requireSite(connection, SITE_ID));
            assertEquals(404, exception.status());
        }
    }

    @Test
    void resolvesOnlyTheExactConfiguredContext() throws Exception
    {
        try(Connection connection = database(); Statement statement = connection.createStatement())
        {
            insertConfiguration(connection, 2, CONVENTIONAL_ID, "CONVENTIONAL", CONVENTIONAL_RADRES_GUID, "DMR");
            statement.executeUpdate("""
                INSERT INTO receiver_context(id, context_key, first_seen_ms, last_seen_ms, nac,
                    primary_frequency_hz)
                VALUES (10, 'CONFIGURATION:%s', 1000, 2000, NULL, 451012500),
                       (11, 'GUID:%s', 1000, 3000, NULL, 452012500),
                       (12, 'CONFIGURATION:00000000-0000-0000-0000-000000000001', 1000, 4000,
                        NULL, 453012500)
                """.formatted(CONVENTIONAL_ID, CONVENTIONAL_RADRES_GUID));
            WebConfiguredEntityRepository.ConfiguredChannel configured =
                new WebConfiguredEntityRepository().requireConventional(connection, CONVENTIONAL_ID);
            assertEquals(10L, configured.contextId());
            assertEquals(451012500L, configured.observedPrimaryFrequencyHz());
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
                    alias_list_name TEXT,
                    radres_guid TEXT,
                    decoder_type TEXT,
                    primary_frequency_hz INTEGER
                )
                """);
            statement.executeUpdate("CREATE TABLE alias_list (id INTEGER PRIMARY KEY, name TEXT)");
            statement.executeUpdate("""
                CREATE TABLE receiver_context (
                    id INTEGER PRIMARY KEY,
                    context_key TEXT NOT NULL UNIQUE,
                    first_seen_ms INTEGER,
                    last_seen_ms INTEGER,
                    nac INTEGER,
                    primary_frequency_hz INTEGER
                )
                """);
        }

        return connection;
    }

    private static void insertConfiguration(Connection connection, int id, String configurationId,
                                            String kind, String guid, String decoder) throws Exception
    {
        try(var statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(id, configuration_id, channel_kind, sort_order, system_name,
                site_name, name, alias_list_name, radres_guid, decoder_type, primary_frequency_hz)
            VALUES (?, ?, ?, ?, 'County', 'Downtown', 'Primary', 'County', ?, ?, 851012500)
            """))
        {
            statement.setInt(1, id);
            statement.setString(2, configurationId);
            statement.setString(3, kind);
            statement.setInt(4, id);
            statement.setString(5, guid);
            statement.setString(6, decoder);
            statement.executeUpdate();
        }
    }
}

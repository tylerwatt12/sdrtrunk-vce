/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.upgrade.ApplicationMigrationService;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicitly opt-in: this harness changes only a marked disposable profile and never launches a receiver. */
@EnabledIfEnvironmentVariable(named = "SDRTRUNK_ISOLATED_TEST_PROFILE", matches = ".+")
class ConfigurationHistoryProfileTest
{
    @Test
    void copiedProfileMigratesAndBulkConfigurationDeletionDoesNotWaitForHistory() throws Exception
    {
        Path data = Path.of(System.getenv("SDRTRUNK_ISOLATED_TEST_PROFILE")).toRealPath();
        assertTrue(Files.isRegularFile(data.resolve(".isolated-test-profile")),
            "An explicit disposable-profile marker is required; never select a running receiver profile");
        Path configuration = data.resolve("database/sdrtrunk.sqlite");
        Map<String,Object> evidence = new LinkedHashMap<>();
        long migrationStart = System.nanoTime();
        new ApplicationMigrationService().migrateCurrent(data, false, ignored -> {});
        evidence.put("migration_ms", elapsed(migrationStart));
        SdrTrunkDatabaseStartup.validateGlobalDatabase(configuration);
        evidence.put("configuration_bytes", Files.size(configuration));
        evidence.put("history_bytes", Files.size(ActivityDatabasePath.forConfiguration(configuration)));

        ConfigurationRepository repository = new ConfigurationRepository(configuration);
        ChannelConfigurationSnapshot original = repository.loadChannelConfiguration();
        Set<String> removed = new HashSet<>();
        long eventCount;
        long removedChannelEvents = 0;
        try(Connection reader = SdrTrunkDatabase.openHistoryRead(configuration))
        {
            eventCount = scalar(reader, "SELECT count(*) FROM receiver_activity_event");
            try(var rows = reader.createStatement().executeQuery("""
                SELECT receiver.configuration_id,receiver.id FROM receiver_channel receiver
                JOIN configuration.configuration_channel configured
                  ON configured.configuration_id=receiver.configuration_id
                ORDER BY receiver.last_seen_ms DESC,receiver.id LIMIT 20
                """))
            {
                while(rows.next())
                {
                    removed.add(rows.getString(1));
                    removedChannelEvents += scalar(reader,
                        "SELECT count(*) FROM receiver_activity_event WHERE channel_id=" + rows.getLong(2));
                }
            }
        }
        assertFalse(removed.isEmpty(), "The profile must contain configured channels with retained observations");
        assertTrue(removedChannelEvents > 0);
        evidence.put("events_before", eventCount);
        evidence.put("bulk_channels", removed.size());
        evidence.put("bulk_channel_events", removedChannelEvents);
        try(Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            history.createStatement().execute("BEGIN IMMEDIATE");
            try
            {
                long start = System.nanoTime();
                repository.commitChannelConfiguration(new ChannelConfigurationSnapshot(original.channels().stream()
                    .filter(channel -> !removed.contains(channel.getConfigurationId())).toList()));
                long deletionMs = elapsed(start);
                evidence.put("bulk_delete_while_history_writer_held_ms", deletionMs);
                assertTrue(deletionMs < 5000, "Configuration deletion must not wait for the 30-second history lock");
                assertEquals(original.channels().size() - removed.size(), repository.loadChannelConfiguration().channels().size());
                assertEquals(eventCount, scalar(history, "SELECT count(*) FROM receiver_activity_event"));

                StatsWebDatabase web = new StatsWebDatabase(new UserPreferences(), configuration);
                start = System.nanoTime();
                web.status();
                web.webEntityNavigationSnapshot();
                web.channelDirectory(new StatsRequest(Map.of()));
                long readsMs = elapsed(start);
                evidence.put("status_navigation_directory_while_history_writer_held_ms", readsMs);
                assertTrue(readsMs < 5000, "Shared navigation and directory reads must remain available");
            }
            finally
            {
                repository.commitChannelConfiguration(original);
                history.createStatement().execute("ROLLBACK");
            }
        }
        evidence.put("events_after", eventCount);
        evidence.put("receivers_started", 0);
        evidence.put("receivers_stopped", 0);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
            data.resolveSibling("configuration-history-profile-results.json").toFile(), evidence);
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var rows = connection.createStatement().executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}

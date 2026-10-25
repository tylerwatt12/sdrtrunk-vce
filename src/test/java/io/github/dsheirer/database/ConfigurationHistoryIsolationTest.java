/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationHistoryIsolationTest
{
    @Test
    void retainedHistoryWriterCannotReserveConfigurationAndReadersRemainAvailable(@TempDir Path directory)
        throws Exception
    {
        Path configuration = directory.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        try(var history = SdrTrunkDatabase.openHistory(configuration))
        {
            history.createStatement().execute("BEGIN IMMEDIATE");
            history.createStatement().execute("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES('123e4567-e89b-42d3-a456-426614174009',1,1)");
            assertThrows(SQLException.class, () -> history.createStatement().execute(
                "UPDATE configuration.alias_list SET name='Forbidden'"), "history attaches configuration read-only");
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            {
                try(var config = SdrTrunkDatabase.open(configuration))
                {
                    config.createStatement().execute("BEGIN IMMEDIATE");
                    config.createStatement().execute("UPDATE alias_list SET name=name || ' renamed'");
                    config.createStatement().execute("COMMIT");
                }
                try(var reader = SdrTrunkDatabase.openHistoryRead(configuration);
                    var rows = reader.createStatement().executeQuery("SELECT count(*) FROM receiver_channel"))
                {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1), "reader sees the previous committed history snapshot");
                }
            });
            history.createStatement().execute("ROLLBACK");
        }
    }
}

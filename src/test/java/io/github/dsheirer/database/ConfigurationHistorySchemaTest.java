/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationHistorySchemaTest
{
    @TempDir Path directory;
    @Test void currentRoleDefinitionsAreExact() throws Exception
    {
        try(var configuration=DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("config.sqlite"));
            var history=DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.sqlite")))
        {
            ConfigurationHistorySchema.createConfiguration(configuration);
            ConfigurationHistorySchema.createHistory(history);
            assertAll(
                () -> assertEquals(ConfigurationHistoryDatabase.CONFIGURATION_FINGERPRINT,SqliteSchemaValidator.fingerprint(configuration)),
                () -> assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,SqliteSchemaValidator.fingerprint(history)));
            try(var query=history.createStatement();var rows=query.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
    }
}

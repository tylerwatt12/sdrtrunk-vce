/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format46To47DatabaseMigrationTest
{
    @TempDir Path mDirectory;

    @Test void authoritativeAlertSchemaHasTheExactRegisteredFingerprint() throws Exception
    {
        Path path = Format46TestDatabase.create(mDirectory.resolve("fingerprint.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path))
        {
            TopicAlertSchema.create(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void preservesPopulatedPriorConfigurationAndRollsBackBeforeSafeRetry() throws Exception
    {
        Path path = Format46TestDatabase.create(mDirectory.resolve("source.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path); Statement sql = connection.createStatement())
        {
            String channels = scalar(connection, "SELECT group_concat(id||':'||config_json,'|') FROM configuration_channel");
            String aliases = scalar(connection, "SELECT count(*) FROM alias");
            connection.setAutoCommit(false);
            new Format46To47DatabaseMigration().migrate(connection);
            connection.rollback();
            assertEquals(46, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("0", scalar(connection, "SELECT count(*) FROM sqlite_master WHERE name='topic_alert_topic'"));
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals("format-46-to-47", report.steps().getFirst().id());
            assertEquals(channels, scalar(connection, "SELECT group_concat(id||':'||config_json,'|') FROM configuration_channel"));
            assertEquals(aliases, scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals("0:1", scalar(connection, "SELECT visible||':'||enabled FROM topic_alert_topic WHERE id='administrator-alerts'"));
            assertEquals("0", scalar(connection, "SELECT json_extract(settings_json,'$.push.enabled') FROM application_settings WHERE key='alert.configuration'"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals(47, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(path);
    }

    private static String scalar(Connection connection, String query) throws Exception
    {
        try(Statement sql = connection.createStatement(); var rows = sql.executeQuery(query)) { return rows.next() ? rows.getString(1) : null; }
    }
}

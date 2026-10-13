/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alerts.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format47To48DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void authoritativeCurrentSchemaHasExactRegisteredFingerprint() throws Exception
    {
        Path path = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(path);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
        {
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void retiresSavedSuppressionPreservesOwnershipSchedulesAndHistoryAndRetriesAfterRollback() throws Exception
    {
        Path path = Format47TestDatabase.create(directory.resolve("prior.sqlite"));
        String ruleJson;
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path); Statement sql = connection.createStatement())
        {
            var rule = new AlertRule("fixture-rule", "Scheduled activity", "fixture-alert", true, "call", "", "", "", "",
                null, null, new AlertRule.Schedule("custom", List.of(0, 1), "22:00", "06:00", "America/New_York"),
                0, "rule", false, "Activity", "Scheduled activity", "details");
            var legacy = new ObjectMapper().valueToTree(rule); ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove(java.util.List.of("protocol_family", "action_button_enabled"));
            ruleJson = legacy.toString();
            try(var insert = connection.prepareStatement("INSERT INTO topic_alert_rule VALUES('fixture-rule','fixture-alert',?)"))
            { insert.setString(1, ruleJson); insert.executeUpdate(); }
            sql.execute("INSERT INTO topic_alert_message VALUES('fixture-message','fixture-rule','fixture-alert','Fixture alert','Scheduled activity','Activity','Scheduled activity','details','call',1,3600001)");
            sql.execute("INSERT INTO topic_alert_delivery VALUES(10,'fixture-message','fixture-phone','fixture-remote','proxy','queued',1,1,'remote-alert','',1)");
            connection.setAutoCommit(false);
            var effects = new Format47To48DatabaseMigration().migrateAndReport(connection);
            assertEquals(2, effects.stream().filter(e -> e.kind() == DatabaseMigrationEffect.Kind.DROP).findFirst().orElseThrow().affectedRows());
            connection.rollback();
            assertEquals(47, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("1", scalar(connection, "SELECT paused FROM topic_alert_device WHERE id='fixture-phone'"));
            var report = DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals("format-47-to-48", report.steps().getFirst().id());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            for(String table: List.of("topic_alert_device", "topic_alert_enrollment"))
                assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_table_info('" + table + "') WHERE name IN ('paused','quiet_start','quiet_end','time_zone')"));
            assertEquals(new ObjectMapper().readTree(ruleJson), new ObjectMapper().readTree(scalar(connection, "SELECT json_remove(rule_json,'$.action_button_enabled') FROM topic_alert_rule")), "Subsequent popup setting migration preserves every original rule field");
            assertEquals("queued:fixture-remote", scalar(connection, "SELECT state||':'||remote_device_id FROM topic_alert_delivery WHERE id=10"));
            assertEquals("Activity:Scheduled activity", scalar(connection, "SELECT title||':'||body FROM topic_alert_message"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertNull(scalar(connection, "PRAGMA foreign_key_check"));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(path);
        var store = new TopicAlertStore(path);
        assertEquals("fixture-phone", store.findDeviceByTokenHash("a".repeat(64)).orElseThrow().id());
        assertEquals(List.of("fixture-alert"), store.device("fixture-phone").orElseThrow().topicIds(), "Formerly paused phone receives normally");
        assertTrue(store.device("expired-phone").orElseThrow().subscriptionExpired());
        var saved = store.configuration().rules().getFirst();
        assertEquals("", saved.protocolFamily()); assertEquals("America/New_York", saved.schedule().timeZone());
        assertEquals(List.of("fixture-alert"), store.enrollment("fixture-pair").orElseThrow().topicIds());
    }
    private static String scalar(Connection connection, String query) throws Exception
    {
        try(var sql = connection.createStatement(); var row = sql.executeQuery(query)) { return row.next() ? row.getString(1) : null; }
    }
}

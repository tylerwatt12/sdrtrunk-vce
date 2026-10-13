/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format48To49DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void freshCurrentAndFrozenPriorCreatorsHaveTheirExactRegisteredSignatures() throws Exception
    {
        for(boolean current: new boolean[]{false, true})
        {
            Path path = Format46TestDatabase.create(directory.resolve(current ? "current.sqlite" : "frozen48.sqlite"));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
            {
                if(current) TopicAlertSchema.create(connection); else Format48TopicAlertSchema.create(connection);
                assertEquals(current ? DatabaseFormatCatalog.current().fingerprint() : DatabaseFormatCatalog.requireVersion(48).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
            }
        }
    }

    @Test void preservesPopulatedOwnershipHistoryTapActionsAndDeliveryThroughRollbackAndRetry() throws Exception
    {
        Path path = Format48TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
        {
            String messages = originalMessages(connection), deliveries = deliveryState(connection);
            String metadata = scalar(connection, "SELECT settings_json FROM application_settings WHERE key='alert.configuration'");
            String listenRule = scalar(connection, "SELECT rule_json FROM topic_alert_rule WHERE id='fixture-listen'");
            String detailsRule = scalar(connection, "SELECT rule_json FROM topic_alert_rule WHERE id='fixture-details'");
            String channelData = scalar(connection, "SELECT group_concat(id||':'||config_json,'|') FROM configuration_channel");
            String allocator = scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='topic_alert_delivery'");
            assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(path), "Startup never migrates the prior format");
            connection.setAutoCommit(false);
            var effects = new Format48To49DatabaseMigration().migrateAndReport(connection);
            assertEffect(effects, "alert rule popup settings", 2); assertEffect(effects, "prior Listen popup actions", 1);
            assertEffect(effects, "built-in alert popup setting", 1);
            DatabaseFormatCatalog.stamp(connection, 49); connection.rollback();
            assertEquals(48, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_table_info('topic_alert_message') WHERE name LIKE 'action_%'"));
            assertEquals(metadata, scalar(connection, "SELECT settings_json FROM application_settings WHERE key='alert.configuration'"));
            assertEquals(listenRule, scalar(connection, "SELECT rule_json FROM topic_alert_rule WHERE id='fixture-listen'"));
            var report = DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals("format-48-to-49", report.steps().getFirst().id()); assertEquals(49, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(messages, originalMessages(connection)); assertEquals(deliveries, deliveryState(connection));
            assertEquals(channelData, scalar(connection, "SELECT group_concat(id||':'||config_json,'|') FROM configuration_channel"));
            assertEquals(allocator, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='topic_alert_delivery'"));
            assertEquals(JSON.readTree(metadata), JSON.readTree(scalar(connection, "SELECT json_remove(settings_json,'$.builtin_action_button_enabled') FROM application_settings WHERE key='alert.configuration'")));
            assertEquals(JSON.readTree(listenRule), JSON.readTree(scalar(connection, "SELECT json_remove(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-listen'")));
            assertEquals(JSON.readTree(detailsRule), JSON.readTree(scalar(connection, "SELECT json_remove(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-details'")));
            assertEquals("true", scalar(connection, "SELECT json_type(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-listen'"));
            assertEquals("false", scalar(connection, "SELECT json_type(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-details'"));
            assertEquals("false", scalar(connection, "SELECT json_type(settings_json,'$.builtin_action_button_enabled') FROM application_settings WHERE key='alert.configuration'"));
            assertEquals("live:listen", scalar(connection, "SELECT action_kind||':'||click_action FROM topic_alert_message WHERE id='message-listen'"));
            assertEquals("none:details", scalar(connection, "SELECT action_kind||':'||click_action FROM topic_alert_message WHERE id='message-details'"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM topic_alert_message WHERE action_entity_ref_json IS NOT NULL"), "Old event context is not guessed from names or receiver history");
            assertEquals("2", scalar(connection, "SELECT count(*) FROM topic_alert_device"));
            assertEquals("2", scalar(connection, "SELECT count(*) FROM topic_alert_membership"));
            assertEquals("pending:fixture-alert", scalar(connection, "SELECT e.status||':'||t.topic_id FROM topic_alert_enrollment e JOIN topic_alert_enrollment_topic t ON t.enrollment_id=e.id"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check")); assertNull(scalar(connection, "PRAGMA foreign_key_check"));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(path);
    }

    @Test void existingValidBooleanChoicesRemainExplicitAndDoNotFollowTheirTapSetting() throws Exception
    {
        Path path = Format48TestDatabase.create(directory.resolve("premature-valid.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.executeUpdate("UPDATE topic_alert_rule SET rule_json=json_set(rule_json,'$.action_button_enabled',json(CASE WHEN id='fixture-listen' THEN 'false' ELSE 'true' END))");
            sql.executeUpdate("UPDATE application_settings SET settings_json=json_set(settings_json,'$.builtin_action_button_enabled',json('true'),'$.revision',43),updated_at_ms=1234 WHERE key='alert.configuration'");
            connection.setAutoCommit(false); var effects = new Format48To49DatabaseMigration().migrateAndReport(connection);
            assertEffect(effects, "alert rule popup settings", 0); assertEffect(effects, "built-in alert popup setting", 0);
            DatabaseFormatCatalog.stamp(connection, 49); connection.commit();
            assertEquals("false", scalar(connection, "SELECT json_type(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-listen'"));
            assertEquals("true", scalar(connection, "SELECT json_type(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-details'"));
            assertEquals("true:43:1234", scalar(connection, "SELECT json_type(settings_json,'$.builtin_action_button_enabled')||':'||json_extract(settings_json,'$.revision')||':'||updated_at_ms FROM application_settings WHERE key='alert.configuration'"));
        }
    }

    @Test void invalidPrematureFlagsRefuseWithoutAddingColumnsOrChangingData() throws Exception
    {
        for(String value: new String[]{"1", "'true'", "NULL"}) for(boolean builtin: new boolean[]{false, true})
        {
            Path path = Format48TestDatabase.create(directory.resolve("invalid-" + builtin + "-" + Math.abs(value.hashCode()) + ".sqlite"));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
            {
                String table = builtin ? "application_settings" : "topic_alert_rule", column = builtin ? "settings_json" : "rule_json";
                String where = builtin ? "key='alert.configuration'" : "id='fixture-listen'", field = builtin ? "builtin_action_button_enabled" : "action_button_enabled";
                sql.executeUpdate("UPDATE " + table + " SET " + column + "=json_set(" + column + ",'$." + field + "'," + value + ") WHERE " + where);
                String before = scalar(connection, "SELECT " + column + " FROM " + table + " WHERE " + where);
                connection.setAutoCommit(false);
                assertTrue(assertThrows(SQLException.class, () -> new Format48To49DatabaseMigration().migrate(connection)).getMessage().contains("boolean")); connection.rollback();
                assertEquals(48, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(before, scalar(connection, "SELECT " + column + " FROM " + table + " WHERE " + where));
                assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_table_info('topic_alert_message') WHERE name LIKE 'action_%'"));
            }
        }
    }

    @Test void actionSnapshotConstraintsAndRequiredSettingsValidateWithoutRuntimeRepair() throws Exception
    {
        Path path = Format49TestDatabase.create(directory.resolve("target-constraints.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE topic_alert_message SET action_kind='arbitrary_url' WHERE id='message-details'"));
            for(String json: new String[]{"[]", "{", "{\"padding\":\"" + "x".repeat(2048) + "\"}"})
                try(var update = connection.prepareStatement("UPDATE topic_alert_message SET action_entity_ref_json=? WHERE id='message-details'"))
                { update.setString(1, json); assertThrows(SQLException.class, update::executeUpdate); }
            sql.executeUpdate("UPDATE topic_alert_message SET action_kind='channel',action_entity_ref_json='{\"kind\":\"channel\",\"key\":\"00000000-0000-4000-8000-000000000001\"}' WHERE id='message-details'");
            assertEquals("channel", scalar(connection, "SELECT json_extract(action_entity_ref_json,'$.kind') FROM topic_alert_message WHERE id='message-details'"));
            sql.executeUpdate("UPDATE application_settings SET settings_json=json_remove(settings_json,'$.builtin_action_button_enabled') WHERE key='alert.configuration'");
            assertThrows(SQLException.class, () -> TopicAlertSchema.validate(connection));
            assertNull(scalar(connection, "SELECT json_type(settings_json,'$.builtin_action_button_enabled') FROM application_settings WHERE key='alert.configuration'"));
            sql.executeUpdate("UPDATE application_settings SET settings_json=json_set(settings_json,'$.builtin_action_button_enabled',json('false')) WHERE key='alert.configuration'");
            sql.executeUpdate("UPDATE topic_alert_rule SET rule_json=json_remove(rule_json,'$.action_button_enabled') WHERE id='fixture-listen'");
            assertThrows(SQLException.class, () -> TopicAlertSchema.validate(connection));
            assertNull(scalar(connection, "SELECT json_type(rule_json,'$.action_button_enabled') FROM topic_alert_rule WHERE id='fixture-listen'"));
        }
    }

    private static String originalMessages(Connection connection) throws Exception
    {
        return scalar(connection, "SELECT group_concat(id||':'||rule_id||':'||topic_id||':'||topic_name||':'||alert_name||':'||title||':'||body||':'||click_action||':'||event_type||':'||created_at_ms||':'||expires_at_ms,'|') FROM (SELECT * FROM topic_alert_message ORDER BY id)");
    }
    private static String deliveryState(Connection connection) throws Exception
    {
        return scalar(connection, "SELECT group_concat(id||':'||alert_id||':'||device_id||':'||remote_device_id||':'||method||':'||state||':'||attempts||':'||next_at_ms||':'||remote_id||':'||error||':'||updated_at_ms,'|') FROM (SELECT * FROM topic_alert_delivery ORDER BY id)");
    }
    private static String scalar(Connection connection, String query) throws Exception
    {
        try(Statement sql = connection.createStatement(); var row = sql.executeQuery(query)) { return row.next() ? row.getString(1) : null; }
    }
    private static void assertEffect(java.util.List<DatabaseMigrationEffect> effects, String subject, long rows)
    {
        assertEquals(rows, effects.stream().filter(effect -> subject.equals(effect.subject())).findFirst().orElseThrow().affectedRows());
    }
}

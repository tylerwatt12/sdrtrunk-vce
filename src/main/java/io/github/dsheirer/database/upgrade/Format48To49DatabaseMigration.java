/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Explicit, bounded popup destinations; notification tap behavior remains its own unchanged fact. */
final class Format48To49DatabaseMigration implements DatabaseMigrationStep
{
    @Override public String id() { return "format-48-to-49"; }
    @Override public String description() { return "Save optional alert popup actions separately from notification taps"; }
    @Override public int sourceVersion() { return 48; }
    @Override public int targetVersion() { return 49; }
    @Override public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
            "configuration, ownership, history and notification taps", 0,
            "Preserve existing rules, schedules, topic choices, guest ownership, credentials, message text, IDs, tap actions and delivery lifecycle"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "alert rule popup settings", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Fill absent action-button flags from each rule's existing Listen tap setting; preserve valid boolean flags and refuse malformed flags or oversized rules"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "prior Listen popup actions", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Retain existing Listen buttons as live actions; other history starts with no action and no historical entity identity is guessed"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "built-in alert popup setting", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Add a disabled built-in action-button setting when absent; preserve any existing valid boolean choice"));
    }
    @Override public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        requireCompatibleSettings(connection);
        List<DatabaseMigrationEffect> policy = declaredEffects();
        return List.of(policy.get(0), counted(policy.get(1), count(connection,
            "SELECT count(*) FROM topic_alert_rule WHERE json_type(rule_json,'$.action_button_enabled') IS NULL")),
            counted(policy.get(2), count(connection, "SELECT count(*) FROM topic_alert_message WHERE click_action='listen'")),
            counted(policy.get(3), count(connection,
                "SELECT count(*) FROM application_settings WHERE key='alert.configuration' AND json_type(settings_json,'$.builtin_action_button_enabled') IS NULL")));
    }
    @Override public void migrateSource(Connection connection) throws SQLException
    {
        requireCompatibleSettings(connection);
        try(Statement sql = connection.createStatement())
        {
            sql.execute("ALTER TABLE topic_alert_message ADD COLUMN action_kind TEXT NOT NULL DEFAULT 'none' CHECK(action_kind IN ('none','live','channel','radio','talkgroup','patch_group','radio_system'))");
            sql.execute("ALTER TABLE topic_alert_message ADD COLUMN action_entity_ref_json TEXT CHECK(action_entity_ref_json IS NULL OR CASE WHEN json_valid(action_entity_ref_json) THEN json_type(action_entity_ref_json)='object' AND length(action_entity_ref_json)<=2048 ELSE 0 END)");
            sql.executeUpdate("UPDATE topic_alert_message SET action_kind='live' WHERE click_action='listen'");
            sql.executeUpdate("UPDATE topic_alert_rule SET rule_json=json_set(rule_json,'$.action_button_enabled',json(CASE WHEN json_extract(rule_json,'$.click_action')='listen' THEN 'true' ELSE 'false' END)) WHERE json_type(rule_json,'$.action_button_enabled') IS NULL");
            sql.executeUpdate("UPDATE application_settings SET settings_json=json_set(settings_json,'$.builtin_action_button_enabled',json('false')) WHERE key='alert.configuration' AND json_type(settings_json,'$.builtin_action_button_enabled') IS NULL");
        }
    }
    private static void requireCompatibleSettings(Connection connection) throws SQLException
    {
        if(count(connection, "SELECT count(*) FROM topic_alert_rule WHERE json_type(rule_json)<>'object' OR (json_type(rule_json,'$.action_button_enabled') IS NOT NULL AND json_type(rule_json,'$.action_button_enabled') NOT IN ('true','false'))") > 0)
            throw new SQLException("An alert rule has an invalid action-button setting; use a boolean value before updating");
        if(count(connection, "SELECT count(*) FROM application_settings WHERE key='alert.configuration' AND json_type(settings_json,'$.builtin_action_button_enabled') IS NOT NULL AND json_type(settings_json,'$.builtin_action_button_enabled') NOT IN ('true','false')") > 0)
            throw new SQLException("The built-in alert action-button setting must be a boolean before updating");
        if(count(connection, "SELECT count(*) FROM topic_alert_rule WHERE json_type(rule_json,'$.action_button_enabled') IS NULL AND length(json_set(rule_json,'$.action_button_enabled',json(CASE WHEN json_extract(rule_json,'$.click_action')='listen' THEN 'true' ELSE 'false' END)))>65536") > 0)
            throw new SQLException("An alert rule is too large to add its popup setting; shorten its conditions or notification text before updating");
    }
    private static long count(Connection connection, String query) throws SQLException
    {
        try(Statement sql = connection.createStatement(); var row = sql.executeQuery(query)) { row.next(); return row.getLong(1); }
    }
    private static DatabaseMigrationEffect counted(DatabaseMigrationEffect effect, long rows)
    {
        return new DatabaseMigrationEffect(effect.kind(), effect.subject(), rows, effect.detail());
    }
}

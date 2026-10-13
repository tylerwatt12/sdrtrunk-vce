/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Fresh creation and the adjacent Application Migrator are the only callers allowed to create these tables. */
public final class TopicAlertSchema
{
    private TopicAlertSchema() {}

    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TABLE topic_alert_topic (
                    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 80),
                    name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(name) BETWEEN 1 AND 80),
                    description TEXT NOT NULL CHECK(length(description)<=240),
                    visible INTEGER NOT NULL CHECK(visible IN (0,1)),
                    enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
                    built_in INTEGER NOT NULL CHECK(built_in IN (0,1)),
                    CHECK(built_in=0 OR (id='administrator-alerts' AND name='Administrator alerts'))
                )
                """);
            statement.execute("""
                CREATE TABLE topic_alert_rule (
                    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 80),
                    topic_id TEXT NOT NULL REFERENCES topic_alert_topic(id) ON DELETE RESTRICT,
                    rule_json TEXT NOT NULL CHECK(json_valid(rule_json) AND length(rule_json)<=65536)
                )
                """);
            statement.execute("CREATE INDEX idx_topic_alert_rule_topic ON topic_alert_rule(topic_id)");
            statement.execute("""
                CREATE TABLE topic_alert_device (
                    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 80),
                    name TEXT NOT NULL CHECK(length(name)<=80),
                    method TEXT NOT NULL CHECK(method IN ('direct','proxy')),
                    remote_device_id TEXT NOT NULL CHECK(length(remote_device_id)<=240),
                    endpoint TEXT NOT NULL CHECK(length(endpoint)<=2048),
                    p256dh TEXT NOT NULL CHECK(length(p256dh)<=160),
                    auth TEXT NOT NULL CHECK(length(auth)<=160),
                    manage_token_hash TEXT NOT NULL UNIQUE CHECK(length(manage_token_hash)=64),
                    subscription_expired INTEGER NOT NULL DEFAULT 0 CHECK(subscription_expired IN (0,1)),
                    CHECK((method='proxy' AND length(remote_device_id)>0) OR
                        (method='direct' AND length(endpoint)>0 AND length(p256dh)>0 AND length(auth)>0))
                )
                """);
            statement.execute("CREATE UNIQUE INDEX idx_topic_alert_device_remote ON topic_alert_device(remote_device_id) WHERE remote_device_id<>''");
            statement.execute("""
                CREATE TABLE topic_alert_membership (
                    device_id TEXT NOT NULL REFERENCES topic_alert_device(id) ON DELETE CASCADE,
                    topic_id TEXT NOT NULL REFERENCES topic_alert_topic(id) ON DELETE CASCADE,
                    PRIMARY KEY(device_id,topic_id)
                ) WITHOUT ROWID
                """);
            statement.execute("CREATE INDEX idx_topic_alert_membership_topic ON topic_alert_membership(topic_id,device_id)");
            statement.execute("""
                CREATE TABLE topic_alert_enrollment (
                    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 80),
                    remote_enrollment_id TEXT NOT NULL UNIQUE CHECK(length(remote_enrollment_id) BETWEEN 1 AND 240),
                    enrollment_url TEXT NOT NULL CHECK(length(enrollment_url)<=2048),
                    code TEXT NOT NULL CHECK(length(code)<=240),
                    manage_token_hash TEXT NOT NULL UNIQUE CHECK(length(manage_token_hash)=64),
                    created_at_ms INTEGER NOT NULL CHECK(created_at_ms>0),
                    expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>created_at_ms),
                    status TEXT NOT NULL CHECK(status IN ('pending','completed','expired','cancelled')),
                    device_id TEXT REFERENCES topic_alert_device(id) ON DELETE SET NULL
                )
                """);
            statement.execute("CREATE INDEX idx_topic_alert_enrollment_expiry ON topic_alert_enrollment(expires_at_ms,id)");
            statement.execute("""
                CREATE TABLE topic_alert_enrollment_topic (
                    enrollment_id TEXT NOT NULL REFERENCES topic_alert_enrollment(id) ON DELETE CASCADE,
                    topic_id TEXT NOT NULL REFERENCES topic_alert_topic(id) ON DELETE CASCADE,
                    PRIMARY KEY(enrollment_id,topic_id)
                ) WITHOUT ROWID
                """);
            statement.execute("""
                CREATE TABLE topic_alert_message (
                    id TEXT PRIMARY KEY NOT NULL CHECK(length(id) BETWEEN 1 AND 80),
                    rule_id TEXT NOT NULL CHECK(length(rule_id)<=80),
                    topic_id TEXT NOT NULL REFERENCES topic_alert_topic(id) ON DELETE CASCADE,
                    topic_name TEXT NOT NULL CHECK(length(topic_name)<=80),
                    alert_name TEXT NOT NULL CHECK(length(alert_name) BETWEEN 1 AND 100),
                    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 128),
                    body TEXT NOT NULL CHECK(length(body) BETWEEN 1 AND 2048),
                    click_action TEXT NOT NULL CHECK(click_action IN ('details','listen')),
                    event_type TEXT NOT NULL CHECK(length(event_type)<=80),
                    created_at_ms INTEGER NOT NULL CHECK(created_at_ms>0),
                    expires_at_ms INTEGER NOT NULL CHECK(expires_at_ms>created_at_ms),
                    action_kind TEXT NOT NULL DEFAULT 'none' CHECK(action_kind IN ('none','live','channel','radio','talkgroup','patch_group','radio_system')),
                    action_entity_ref_json TEXT CHECK(action_entity_ref_json IS NULL OR CASE WHEN json_valid(action_entity_ref_json)
                        THEN json_type(action_entity_ref_json)='object' AND length(action_entity_ref_json)<=2048 ELSE 0 END)
                )
                """);
            statement.execute("CREATE INDEX idx_topic_alert_message_topic_time ON topic_alert_message(topic_id,created_at_ms DESC,id)");
            statement.execute("CREATE INDEX idx_topic_alert_message_time ON topic_alert_message(created_at_ms DESC,id)");
            statement.execute("""
                CREATE TABLE topic_alert_delivery (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alert_id TEXT NOT NULL REFERENCES topic_alert_message(id) ON DELETE CASCADE,
                    device_id TEXT REFERENCES topic_alert_device(id) ON DELETE SET NULL,
                    remote_device_id TEXT NOT NULL CHECK(length(remote_device_id)<=240),
                    method TEXT NOT NULL CHECK(method IN ('direct','proxy')),
                    state TEXT NOT NULL CHECK(state IN ('pending','retry','queued','push_accepted','failed','expired','cancelled','opened')),
                    attempts INTEGER NOT NULL CHECK(attempts BETWEEN 0 AND 100),
                    next_at_ms INTEGER NOT NULL CHECK(next_at_ms>=0),
                    remote_id TEXT NOT NULL CHECK(length(remote_id)<=240),
                    error TEXT NOT NULL CHECK(length(error)<=512),
                    updated_at_ms INTEGER NOT NULL CHECK(updated_at_ms>0),
                    UNIQUE(alert_id,device_id)
                )
                """);
            statement.execute("CREATE INDEX idx_topic_alert_delivery_pending ON topic_alert_delivery(next_at_ms,id) WHERE state IN ('pending','retry')");
            statement.execute("CREATE INDEX idx_topic_alert_delivery_device ON topic_alert_delivery(device_id,id)");
            statement.execute("CREATE UNIQUE INDEX idx_topic_alert_delivery_remote ON topic_alert_delivery(alert_id,remote_device_id) WHERE remote_device_id<>''");
            statement.execute("INSERT INTO topic_alert_topic VALUES('administrator-alerts','Administrator alerts','Tuner, USB, channel, computer, recording, and streaming issues and recovery updates.',0,1,1)");
            statement.execute("""
                INSERT INTO application_settings(key,settings_json,updated_at_ms)
                VALUES('alert.configuration','{"revision":1,"builtin_action_button_enabled":false,"push":{"enabled":false,"mode":"proxy","site_url":"","sender_label":"VCE installation"}}',1)
                """);
        }
    }

    public static void validate(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,built_in FROM topic_alert_topic WHERE id='administrator-alerts'"))
        {
            if(!rows.next() || !"Administrator alerts".equals(rows.getString(1)) || rows.getInt(2) != 1)
                throw new SQLException("Missing or invalid built-in alert topic");
        }
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT json_extract(settings_json,'$.revision'),json_type(settings_json,'$.builtin_action_button_enabled') FROM application_settings WHERE key='alert.configuration'"))
        {
            if(!rows.next() || rows.getLong(1) < 1 || !("true".equals(rows.getString(2)) || "false".equals(rows.getString(2))))
                throw new SQLException("Missing or invalid alert configuration action-button setting");
        }
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT 1 FROM topic_alert_rule WHERE json_type(rule_json,'$.action_button_enabled') IS NULL OR json_type(rule_json,'$.action_button_enabled') NOT IN ('true','false') LIMIT 1"))
        {
            if(rows.next()) throw new SQLException("Missing or invalid alert rule action-button setting");
        }

    }
}

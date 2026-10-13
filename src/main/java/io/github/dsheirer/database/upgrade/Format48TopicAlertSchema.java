/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Frozen published alert layout and invariants, independent of future popup settings. */
final class Format48TopicAlertSchema
{
    private Format48TopicAlertSchema() { }
    static void create(Connection connection) throws SQLException
    {
        Format47TopicAlertSchema.create(connection);
        new Format47To48DatabaseMigration().migrateSource(connection);
    }

    static void validate(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,built_in FROM topic_alert_topic WHERE id='administrator-alerts'"))
        {
            if(!rows.next() || !"Administrator alerts".equals(rows.getString(1)) || rows.getInt(2) != 1)
                throw new SQLException("Missing or invalid built-in alert topic");
        }
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT json_extract(settings_json,'$.revision') FROM application_settings WHERE key='alert.configuration'"))
        {
            if(!rows.next() || rows.getLong(1) < 1) throw new SQLException("Missing or invalid alert configuration");
        }
    }
}

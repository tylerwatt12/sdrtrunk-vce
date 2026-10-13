/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated published format-48 fixture, independent of the current action snapshot contract. */
public final class Format48TestDatabase
{
    private Format48TestDatabase() { }
    public static Path create(Path database) throws Exception
    {
        Format47TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format47To48DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 48); connection.commit(); }
            catch(Exception exception) { connection.rollback(); throw exception; }
            try(var sql = connection.createStatement())
            {
                for(String tap: java.util.List.of("details", "listen"))
                {
                    String ruleId = "fixture-" + tap;
                    String ruleJson = """
                        {"id":"%s","name":"Fixture %s","topic_id":"fixture-alert","enabled":true,
                        "trigger":"call","system_key":"","site_id":"","channel_id":"","protocol_family":"P25","protocol":"",
                        "conditions":{"op":"all","items":[],"field":"","operator":"","value":""},
                        "timing":{"mode":"immediate","minutes":0,"count":0,"window_minutes":0},
                        "schedule":{"mode":"custom","days":[0,1],"start":"22:00","end":"06:00","time_zone":"America/New_York"},
                        "cooldown_minutes":3,"repeat_key":"radio","notify_recovery":false,
                        "title_template":"Radio activity","body_template":"Activity on {talkgroup}","click_action":"%s"}
                        """.formatted(ruleId, tap, tap);
                    try(var insert = connection.prepareStatement("INSERT INTO topic_alert_rule VALUES(?, 'fixture-alert', ?)"))
                    { insert.setString(1, ruleId); insert.setString(2, ruleJson); insert.executeUpdate(); }
                    try(var insert = connection.prepareStatement("INSERT INTO topic_alert_message VALUES(?,?,'fixture-alert','Fixture alert',?,'Radio activity','Full notification text',?,'call',1000,3601000)"))
                    { insert.setString(1, "message-" + tap); insert.setString(2, ruleId); insert.setString(3, "Fixture " + tap); insert.setString(4, tap); insert.executeUpdate(); }
                }
                sql.execute("INSERT INTO topic_alert_delivery VALUES(17,'message-details','fixture-phone','fixture-remote','proxy','queued',1,1000,'remote-alert-details','',1000)");
                sql.execute("INSERT INTO topic_alert_delivery VALUES(18,'message-listen','fixture-phone','fixture-remote','proxy','retry',2,2000,'remote-alert-listen','Retry fixture',2000)");
            }
            connection.commit();
            var detected = DatabaseFormatCatalog.inspect(connection);
            if(detected.version() != 48 || !DatabaseFormatCatalog.requireVersion(48).fingerprint().equals(
                io.github.dsheirer.database.SqliteSchemaValidator.fingerprint(connection)))
                throw new IllegalStateException("Frozen format-48 fixture signature mismatch");
        }
        return database;
    }
}

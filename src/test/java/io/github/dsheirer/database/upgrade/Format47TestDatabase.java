/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Populated fixture retaining exact prior-format content and the new default notification configuration. */
public final class Format47TestDatabase
{
    private Format47TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format46TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format46To47DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 47); connection.commit(); }
            catch(Exception exception) { connection.rollback(); throw exception; }
            try(var sql = connection.createStatement())
            {
                sql.execute("INSERT INTO topic_alert_topic VALUES('fixture-alert','Fixture alert','',1,1,0)");
                sql.execute("INSERT INTO topic_alert_device VALUES('fixture-phone','Phone','proxy','fixture-remote','','','','" + "a".repeat(64) + "',1,'22:00','07:00','America/New_York',0)");
                sql.execute("INSERT INTO topic_alert_membership VALUES('fixture-phone','fixture-alert')");
                sql.execute("INSERT INTO topic_alert_device VALUES('expired-phone','Expired phone','proxy','expired-remote','','','','" + "c".repeat(64) + "',0,'','','UTC',1)");
                sql.execute("INSERT INTO topic_alert_membership VALUES('expired-phone','fixture-alert')");
                sql.execute("INSERT INTO topic_alert_enrollment VALUES('fixture-pair','fixture-remote-pair','https://example.test/pair','fixture-code','" + "b".repeat(64) + "',1,'21:00','06:00','Europe/London',1,900001,'pending',NULL)");
                sql.execute("INSERT INTO topic_alert_enrollment_topic VALUES('fixture-pair','fixture-alert')");
            }
            connection.commit();
            if(DatabaseFormatCatalog.inspect(connection).version() != 47) throw new IllegalStateException("Frozen format-47 fixture drifted");
        }
        return database;
    }
}

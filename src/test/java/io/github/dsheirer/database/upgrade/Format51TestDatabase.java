/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.nio.file.Path;
import java.sql.DriverManager;

/** Exact populated predecessor plus prospective analog Alias activity and legacy raw/encoded tone evidence. */
public final class Format51TestDatabase
{
    private Format51TestDatabase() {}
    public static Path create(Path database) throws Exception
    {
        Format50TestDatabase.create(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try { new Format50To51DatabaseMigration().migrate(connection); DatabaseFormatCatalog.stamp(connection, 51); connection.commit(); }
            catch(Exception error) { connection.rollback(); throw error; }
            if(DatabaseFormatCatalog.inspect(connection).version() != 51) throw new IllegalStateException("Expected exact format 51");
            try(var sql = connection.createStatement())
            {
                for(int source = 1; source <= 2; source++)
                {
                    sql.executeUpdate("INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,event_type_code) " +
                        "SELECT channel_id,radio_system_id," + (1000 + source) + ",24,62 FROM receiver_activity_event ORDER BY id LIMIT 1");
                    long id;
                    try(var row = sql.executeQuery("SELECT last_insert_rowid()")) { row.next(); id = row.getLong(1); }
                    sql.executeUpdate("INSERT INTO receiver_activity_tone_sequence VALUES(" + id + "," + source + "," + source + "," + source + ",1000,1000,2000,1)");
                    sql.executeUpdate("INSERT INTO receiver_activity_tone VALUES(" + id + ",0," +
                        (source == 1 ? "343300,NULL,NULL,125,800,900000" : "812500,NULL,'HZ_812_50',250,500,NULL") + ")");
                }
            }
            connection.commit();
        }
        return database;
    }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact populated format-46 fixture produced only through the adjacent format-45 migration. */
public final class Format46TestDatabase
{
    private Format46TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format45TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            try
            {
                new Format45To46DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 46);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }
            if(DatabaseFormatCatalog.inspect(connection).version() != 46 ||
                !DatabaseFormatCatalog.requireVersion(46).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 46 fixture signature mismatch");
            }
        }
        return database;
    }

    /** Prior-format site facts and descendants exercise parent replacement without inventing new-format fields. */
    public static Path createWithLeaseSiteTopology(Path database) throws Exception
    {
        create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(channel_id,snapshot_hash,first_seen_ms,last_seen_ms,observation_count,
                    protocol,nac,rfss,site,lra,mfid,broadcast_clock_ms,micro_slots,data_service,data_access,
                    wuid_lease_minutes,registration_service,tdma,voice_service,primary_frequency_hz,current_control_hz,
                    active_rfss_network_connection)
                VALUES(5000000000,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1000,9000,7,
                    'APCO25',291,1,2,3,144,10000,12,1,'Request Only',270,1,1,1,851000000,851000000,1),
                    (5000000001,NULL,2000,9000,3,'APCO25',292,1,3,4,0,11000,13,0,'Autonomous',7860,0,0,1,
                        852000000,852000000,0),
                    (5000000002,NULL,3000,9000,2,'APCO25',293,2,1,5,144,12000,14,1,
                        'Autonomous and by Request',NULL,1,1,0,853000000,853000000,1)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel(channel_id,channel_key,descriptor,downlink_hz,uplink_hz,tdma,timeslots,
                    callsign,confirmed_at_ms)
                VALUES(5000000000,'1-12','1-12',851000000,806000000,1,2,'TEST',8000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_tag(channel_id,channel_key,tag,confirmed_at_ms)
                VALUES(5000000000,'1-12','primary_control',8000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_frequency_band(channel_id,band,tdma,base_hz,bandwidth,spacing_hz,
                    transmit_offset_hz,timeslots,confirmed_at_ms)
                VALUES(5000000000,1,1,851000000,12500,12500,-45000000,2,8000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_site_neighbor(channel_id,neighbor_key,system_id,rfss,site,lra,channel_descriptor,
                    downlink_hz,uplink_hz,status,confirmed_at_ms)
                VALUES(5000000000,'neighbor-1',937,1,3,4,'1-13',852000000,807000000,'CONNECTED',8000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_foreign_system_band(channel_id,foreign_wacn,foreign_system_id,band,channel_type,
                    base_hz,spacing_hz,transmit_offset_hz,confirmed_at_ms)
                VALUES(5000000000,703710,291,2,4,762006250,12500,30000000,8000)
                """);
            if(!DatabaseFormatCatalog.requireVersion(46).fingerprint().equals(SqliteSchemaValidator.fingerprint(connection)))
                throw new IllegalStateException("Populated site topology changed the frozen format-46 signature");
        }
        return database;
    }
}

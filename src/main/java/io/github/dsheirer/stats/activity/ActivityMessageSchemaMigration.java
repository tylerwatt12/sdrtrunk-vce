/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.upgrade.ApplicationMigrationTransaction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** The format-60 category expansion retains existing rows, indexes, statistics and event allocator. */
final class ActivityMessageSchemaMigration
{
    private ActivityMessageSchemaMigration() {}

    static boolean requirePopulatedBoundary(Connection connection, String table) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT (SELECT foreign_keys FROM pragma_foreign_keys), EXISTS(SELECT 1 FROM " + table + " LIMIT 1)"))
        {
            row.next();
            boolean populated = row.getInt(2) != 0;
            if(populated)
            {
                ApplicationMigrationTransaction.requireActive(connection);
                if(row.getInt(1) != 0)
                    throw new SQLException("Populated message-category update requires the Application Migrator boundary");
            }
            return populated;
        }
    }

    /** Only the admitted format-59 event CHECK domains change; its records and index b-trees stay untouched. */
    static void relaxEventConstraints(Connection connection, String previous, String current) throws SQLException
    {
        String table = "receiver_activity_event";
        SqliteSchemaValidator.validateDefinitions(connection,
            List.of(new SqliteSchemaValidator.Definition("table", table, previous)));
        if(!requirePopulatedBoundary(connection, table))
        {
            rebuild(connection, table, current);
            return;
        }
        // Parse the authoritative target independently and prove identical physical column layout before
        // using SQLite's documented schema-only procedure for relaxing CHECK constraints.
        try(Connection validation = DriverManager.getConnection("jdbc:sqlite::memory:");
            var statement = validation.createStatement())
        {
            statement.executeUpdate(current);
            if(!physicalColumns(connection, table).equals(physicalColumns(validation, table)))
                throw new SQLException("Message-category update cannot change the event record layout");
        }
        try(var statement = connection.createStatement())
        {
            int version;
            try(var row = statement.executeQuery("PRAGMA schema_version")) { row.next(); version = row.getInt(1); }
            if(version < 0 || version == Integer.MAX_VALUE)
                throw new SQLException("Message-category update cannot advance the SQLite schema version");
            try(var row = statement.executeQuery("PRAGMA writable_schema"))
            {
                row.next();
                if(row.getInt(1) != 0) throw new SQLException("Message-category update requires schema writing disabled");
            }
            try
            {
                statement.execute("PRAGMA writable_schema=ON");
                try(var update = connection.prepareStatement("UPDATE sqlite_schema SET sql=? WHERE type='table' AND name=?"))
                {
                    update.setString(1, current);
                    update.setString(2, table);
                    if(update.executeUpdate() != 1) throw new SQLException("Message-category event definition was not updated");
                }
                statement.execute("PRAGMA schema_version=" + (version + 1));
            }
            finally
            {
                statement.execute("PRAGMA writable_schema=OFF");
                statement.execute("PRAGMA writable_schema=RESET");
            }
        }
        SqliteSchemaValidator.validateDefinitions(connection,
            List.of(new SqliteSchemaValidator.Definition("table", table, current)));
    }

    private static List<String> physicalColumns(Connection connection, String table) throws SQLException
    {
        List<String> columns = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_xinfo(" + table + ")"))
        {
            while(rows.next()) columns.add(rows.getInt("cid") + "|" + rows.getString("name") + "|" +
                rows.getString("type") + "|" + rows.getInt("notnull") + "|" + rows.getString("dflt_value") +
                "|" + rows.getInt("pk") + "|" + rows.getInt("hidden"));
        }
        return columns;
    }

    static void rebuild(Connection connection, String table, String definition) throws SQLException
    {
        rebuild(connection, table, definition, List.of());
    }

    static void rebuild(Connection connection, String table, String definition,
                        List<String> appendedDefinitions) throws SQLException
    {
        List<String> indexes = new ArrayList<>();
        List<String[]> statistics = new ArrayList<>();
        List<Sample> samples = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        Long allocator = null;
        requirePopulatedBoundary(connection, table);
        try(Statement statement = connection.createStatement())
        {
            try(var rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
            { while(rows.next()) columns.add(rows.getString("name")); }
            // A composite PRIMARY KEY does not replace the hidden rowid on ordinary SQLite tables.
            // Preserve gaps and insertion identity as well as the explicit fields during a bulk rebuild.
            try(var rows = statement.executeQuery("PRAGMA table_list(" + table + ")"))
            {
                if(!rows.next()) throw new SQLException("Missing message-category migration table: " + table);
                if(rows.getInt("wr") == 0) columns.addFirst("rowid");
            }
            try(var rows = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE type='index' AND tbl_name='" +
                table + "' AND sql IS NOT NULL ORDER BY rowid"))
            { while(rows.next()) indexes.add(rows.getString(1)); }
            try(var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_stat1'"))
            {
                if(exists.next()) try(Statement stats = connection.createStatement();
                    var rows = stats.executeQuery("SELECT idx,stat FROM sqlite_stat1 WHERE tbl='" + table + "'"))
                { while(rows.next()) statistics.add(new String[]{rows.getString(1), rows.getString(2)}); }
            }
            try(var exists = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name='sqlite_stat4'"))
            {
                if(exists.next()) try(Statement stats = connection.createStatement(); var rows = stats.executeQuery(
                    "SELECT idx,neq,nlt,ndlt,sample FROM sqlite_stat4 WHERE tbl='" + table + "'"))
                {
                    while(rows.next()) samples.add(new Sample(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getBytes(5)));
                }
            }
            try(var row = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='" + table + "'"))
            { if(row.next()) allocator = row.getLong(1); }
            String temporary = table + "_format60";
            statement.executeUpdate(definition.replace(table + " (", temporary + " ("));
            // ADD COLUMN with CHECK validates existing rows. Append all counters while the replacement
            // is empty, then visit retained rows once rather than rescanning them for every counter.
            for(String appended: appendedDefinitions)
                statement.executeUpdate("ALTER TABLE " + temporary + " ADD COLUMN " + appended);
            String fields = String.join(",", columns);
            statement.executeUpdate("INSERT INTO " + temporary + "(" + fields + ") SELECT " + fields + " FROM " + table);
            statement.executeUpdate("DROP TABLE " + table);
            statement.executeUpdate("ALTER TABLE " + temporary + " RENAME TO " + table);
            for(String index: indexes) statement.executeUpdate(index);
            if(!statistics.isEmpty()) try(PreparedStatement restore = connection.prepareStatement(
                "INSERT INTO sqlite_stat1(tbl,idx,stat) VALUES(?,?,?)"))
            {
                for(String[] row: statistics)
                { restore.setString(1, table); restore.setString(2, row[0]); restore.setString(3, row[1]); restore.addBatch(); }
                restore.executeBatch();
            }
            if(!samples.isEmpty()) try(PreparedStatement restore = connection.prepareStatement(
                "INSERT INTO sqlite_stat4(tbl,idx,neq,nlt,ndlt,sample) VALUES(?,?,?,?,?,?)"))
            {
                for(Sample row: samples)
                {
                    restore.setString(1, table); restore.setString(2, row.index()); restore.setString(3, row.equal());
                    restore.setString(4, row.less()); restore.setString(5, row.distinct());
                    restore.setBytes(6, row.sample()); restore.addBatch();
                }
                restore.executeBatch();
            }
            if(allocator != null)
            {
                int restored = statement.executeUpdate("UPDATE sqlite_sequence SET seq=max(seq," + allocator +
                    ") WHERE name='" + table + "'");
                if(restored == 0) statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) VALUES('" + table +
                    "'," + allocator + ")");
            }
            else statement.executeUpdate("DELETE FROM sqlite_sequence WHERE name='" + table +
                "' AND NOT EXISTS(SELECT 1 FROM " + table + ")");
            if(!statistics.isEmpty() || !samples.isEmpty()) statement.execute("ANALYZE sqlite_schema");
        }
    }

    private record Sample(String index, String equal, String less, String distinct, byte[] sample) {}
}

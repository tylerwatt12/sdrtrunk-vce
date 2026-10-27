/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.sqlite.SQLiteConfig;

/**
 * Extracts configuration into a small private database without copying, counting or validating discarded history.
 * The caller must own the source portable-profile lock and must have stopped all source database services. This
 * owned-profile operation uses SQLite's WAL-aware read-only connections: database and WAL contents stay unchanged,
 * while SQLite may maintain shared-memory lock bookkeeping. User-selected immutable external imports must continue
 * to use {@link SqliteDatabaseSnapshot#createExternal(Path, Path)} instead.
 *
 * <p>The extracted monolith retains its exact admitted historical schema, with receiver-history tables empty, so
 * the ordinary adjacent migration chain remains authoritative. A separated profile gets a fresh empty activity
 * companion; only the old companion's schema and profile identity are inspected.</p>
 */
public final class ConfigurationOnlyDatabaseSnapshot
{
    private static final int BATCH_SIZE = 256;
    private static final Set<String> DISCARDED_TABLES = discardedTables();

    private ConfigurationOnlyDatabaseSnapshot() { }

    public static DatabaseFormatCatalog.DetectedFormat create(Path source, Path destination)
        throws IOException, SQLException
    {
        return create(source, destination, ignored -> { });
    }

    public static DatabaseFormatCatalog.DetectedFormat create(Path source, Path destination,
                                                               Consumer<String> progress)
        throws IOException, SQLException
    {
        Path normalizedSource = Objects.requireNonNull(source).toAbsolutePath().normalize();
        Path normalizedDestination = Objects.requireNonNull(destination).toAbsolutePath().normalize();
        Consumer<String> listener = progress == null ? ignored -> { } : progress;
        requireSource(normalizedSource);
        requireUnused(normalizedDestination);
        Path destinationActivity = ActivityDatabasePath.forConfiguration(normalizedDestination);
        requireUnused(destinationActivity);
        if(normalizedDestination.equals(normalizedSource))
            throw new IOException("Settings extraction requires a separate destination database.");
        Path parent = normalizedDestination.getParent();
        if(parent == null) throw new IOException("Settings extraction requires a destination directory.");
        Files.createDirectories(parent);
        Path scratch = Files.createTempDirectory(parent, ".configuration-extraction-");
        Path extracted = scratch.resolve(normalizedDestination.getFileName());
        Path extractedActivity = ActivityDatabasePath.forConfiguration(extracted);
        boolean configurationPublished = false;
        boolean activityPublished = false;
        Throwable failure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(scratch);
            SourceIdentity identity = SourceIdentity.capture(normalizedSource);
            SourceIdentity activityIdentity = null;
            DatabaseFormatCatalog.DetectedFormat sourceFormat;
            boolean paired;
            listener.accept("Reading saved configuration without receiver history");
            try(Connection original = openSource(normalizedSource))
            {
                //Pin one WAL-aware read view for schema admission and every copied configuration table.
                original.setAutoCommit(false);
                SdrTrunkDatabaseStartup.requireMainTrackDatabase(original);
                sourceFormat = DatabaseFormatCatalog.inspectForMigration(original);
                paired = sourceFormat.version() >= 63;
                if(paired)
                {
                    if(!ConfigurationHistoryDatabase.isPairedConfiguration(original))
                        throw new IOException("Select the configuration database, not its activity companion.");
                    Path history = ActivityDatabasePath.forConfiguration(normalizedSource);
                    requireSource(history);
                    ConfigurationHistoryDatabase.requireNoPendingSplit(normalizedSource);
                    activityIdentity = SourceIdentity.capture(history);
                    validateSourcePair(original, history, sourceFormat.version());
                }
                try(Connection target = openTarget(extracted))
                {
                    List<SchemaObject> schema = schema(original);
                    createObjects(target, schema, true);
                    Set<String> copiedTables = new LinkedHashSet<>();
                    Set<String> sourceTables = new LinkedHashSet<>();
                    for(SchemaObject object: schema)
                        if(object.type().equals("table")) sourceTables.add(object.name());
                    for(String table: sourceTables)
                    {
                        if(DISCARDED_TABLES.contains(table)) continue;
                        copyTable(original, target, table, sourceTables);
                        copiedTables.add(table);
                    }
                    copySequences(original, target, copiedTables);
                    //Triggers are installed only after copying, so extraction never executes configuration writes
                    //against other tables as a side effect. Indexes are built only over the selected small content.
                    createObjects(target, schema, false);
                    if(!SqliteSchemaValidator.fingerprint(original).equals(SqliteSchemaValidator.fingerprint(target)))
                        throw new SQLException("Extracted settings did not retain the exact source schema.");
                    if(paired)
                        createEmptyActivity(extractedActivity, target);
                    target.commit();
                }
                original.rollback();
            }
            identity.requireUnchanged(normalizedSource);
            if(activityIdentity != null)
                activityIdentity.requireUnchanged(ActivityDatabasePath.forConfiguration(normalizedSource));
            FileAccessAttributeSnapshot.restrictSensitiveFile(extracted);
            if(paired) FileAccessAttributeSnapshot.restrictSensitiveFile(extractedActivity);
            requireUnused(normalizedDestination);
            requireUnused(destinationActivity);
            Files.move(extracted, normalizedDestination);
            configurationPublished = true;
            if(paired)
            {
                Files.move(extractedActivity, destinationActivity);
                activityPublished = true;
            }
            listener.accept("Saved configuration prepared; receiver history was not copied");
            return sourceFormat;
        }
        catch(IOException | SQLException | RuntimeException | Error exception)
        {
            failure = exception;
            if(activityPublished) deleteOwned(destinationActivity, exception);
            if(configurationPublished) deleteOwned(normalizedDestination, exception);
            throw exception;
        }
        finally
        {
            try
            {
                try(var files = Files.list(scratch))
                {
                    for(Path file: files.toList()) Files.delete(file);
                }
                Files.delete(scratch);
            }
            catch(IOException cleanup)
            {
                if(failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private static Set<String> discardedTables()
    {
        Set<String> result = new LinkedHashSet<>(LegacyActivityReset.PRE_LOGICAL_CALL_TABLES);
        result.addAll(LegacyActivityReset.LOGICAL_CALL_TABLES);
        result.addAll(CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES);
        result.addAll(List.of("alias_list_analog_activity_summary", "topic_alert_message", "topic_alert_delivery"));
        return Set.copyOf(result);
    }

    private static void requireSource(Path source) throws IOException
    {
        SqliteDatabaseSnapshot.requireSourceUsable(source);
        Path journal = Path.of(source + "-journal");
        if(Files.exists(journal, LinkOption.NOFOLLOW_LINKS) && Files.size(journal) > 0)
            throw new IOException("The previous VCE database has an unfinished rollback journal. Close the previous " +
                "installation cleanly, or recover it with its matching VCE build, before bringing its settings.");
    }

    private static void requireUnused(Path destination) throws IOException
    {
        for(String suffix: List.of("", "-journal", "-wal", "-shm"))
            if(Files.exists(Path.of(destination + suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Refusing to overwrite an existing settings-extraction destination.");
    }

    private static Connection openSource(Path path) throws IOException, SQLException
    {
        //A closed WAL-mode database with no committed WAL needs no new SQLite sidecars. This immutable read is
        //safe only at this caller-owned stopped-profile boundary; a nonempty WAL always uses the WAL-aware path.
        Path wal = Path.of(path + "-wal");
        if(!Files.exists(wal, LinkOption.NOFOLLOW_LINKS) || Files.size(wal) == 0)
            return SqliteDatabaseSnapshot.openImmutable(path);
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(10_000);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path, options.toProperties());
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA query_only=ON"); }
        catch(SQLException exception) { connection.close(); throw exception; }
        return connection;
    }

    private static Connection openTarget(Path path) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA foreign_keys=OFF");
            //Keep recoverable malformed configuration available to the same ordinary migration repair policy.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            connection.setAutoCommit(false);
        }
        catch(SQLException exception) { connection.close(); throw exception; }
        return connection;
    }

    private static void validateSourcePair(Connection configuration, Path history, int version)
        throws IOException, SQLException
    {
        ConfigurationHistoryDatabase.validateRole(configuration, ConfigurationHistorySchema.CONFIGURATION_ROLE);
        try(Connection activity = openSource(history))
        {
            activity.setAutoCommit(false);
            SdrTrunkDatabaseStartup.requireMainTrackDatabase(activity);
            ConfigurationHistoryDatabase.validateRole(activity, ConfigurationHistorySchema.HISTORY_ROLE);
            if(!Objects.equals(ConfigurationHistorySchema.metadata(configuration, ConfigurationHistorySchema.PAIR_ID_KEY),
                ConfigurationHistorySchema.metadata(activity, ConfigurationHistorySchema.PAIR_ID_KEY)))
                throw new SQLException("The configuration and activity databases belong to different profiles.");
            if(version == 63 && "64".equals(ConfigurationHistorySchema.metadata(activity,
                DatabaseFormatCatalog.FORMAT_VERSION_KEY)))
                throw new SQLException("The configuration and activity database versions are incompatible.");
            activity.rollback();
        }
    }

    private static List<SchemaObject> schema(Connection connection) throws SQLException
    {
        List<SchemaObject> result = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT type,name,sql FROM sqlite_schema
            WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY rowid
            """))
        {
            while(rows.next()) result.add(new SchemaObject(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
        return List.copyOf(result);
    }

    private static void createObjects(Connection target, List<SchemaObject> schema, boolean tables) throws SQLException
    {
        try(Statement statement = target.createStatement())
        {
            for(SchemaObject object: schema)
                if(object.type().equals("table") == tables) statement.execute(object.sql());
        }
    }

    private static void copyTable(Connection source, Connection target, String table, Set<String> sourceTables)
        throws SQLException
    {
        List<String> columns = new ArrayList<>();
        try(Statement statement = source.createStatement();
            ResultSet rows = statement.executeQuery("PRAGMA table_xinfo(" + identifier(table) + ")"))
        {
            while(rows.next()) if(rows.getInt("hidden") == 0) columns.add(identifier(rows.getString("name")));
        }
        String names = String.join(",", columns);
        String where = table.equals("p25_subscriber_identity") && sourceTables.contains("alias_p25_subscriber_identity") ?
            " WHERE id IN (SELECT p25_subscriber_identity_id FROM alias_p25_subscriber_identity)" : "";
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        try(Statement select = source.createStatement();
            ResultSet rows = select.executeQuery("SELECT " + names + " FROM " + identifier(table) + where);
            PreparedStatement insert = target.prepareStatement("INSERT INTO " + identifier(table) + "(" + names +
                ") VALUES(" + placeholders + ")"))
        {
            int pending = 0;
            while(rows.next())
            {
                for(int index = 1; index <= columns.size(); index++) insert.setObject(index, rows.getObject(index));
                insert.addBatch();
                if(++pending == BATCH_SIZE) { insert.executeBatch(); pending = 0; }
            }
            if(pending > 0) insert.executeBatch();
        }
    }

    private static void copySequences(Connection source, Connection target, Set<String> copiedTables) throws SQLException
    {
        try(Statement statement = source.createStatement(); ResultSet present = statement.executeQuery(
            "SELECT 1 FROM sqlite_schema WHERE type='table' AND name='sqlite_sequence'"))
        {
            if(!present.next()) return;
        }
        try(Statement clear = target.createStatement()) { clear.executeUpdate("DELETE FROM sqlite_sequence"); }
        try(Statement select = source.createStatement(); ResultSet rows = select.executeQuery("SELECT name,seq FROM sqlite_sequence");
            PreparedStatement insert = target.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)"))
        {
            while(rows.next())
            {
                if(!copiedTables.contains(rows.getString(1))) continue;
                insert.setObject(1, rows.getObject(1)); insert.setObject(2, rows.getObject(2)); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void createEmptyActivity(Path path, Connection configuration) throws SQLException
    {
        String pairId = ConfigurationHistorySchema.metadata(configuration, ConfigurationHistorySchema.PAIR_ID_KEY);
        try(Connection activity = openTarget(path))
        {
            ConfigurationHistorySchema.createHistory(activity);
            ConfigurationHistorySchema.setIdentity(activity, ConfigurationHistorySchema.HISTORY_ROLE, pairId);
            SdrTrunkDatabaseStartup.setMetadata(activity, DatabaseFormatCatalog.FORMAT_VERSION_KEY, "63");
            ConfigurationHistoryDatabase.validateRole(activity, ConfigurationHistorySchema.HISTORY_ROLE);
            activity.commit();
        }
    }

    private static String identifier(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }

    private static void deleteOwned(Path path, Throwable failure)
    {
        try { Files.delete(path); }
        catch(IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    private record SchemaObject(String type, String name, String sql) { }

    /** Detect accidental concurrent source use without reading or hashing history pages. */
    private record SourceIdentity(long size, java.nio.file.attribute.FileTime modified, Object fileKey,
                                  long walSize, java.nio.file.attribute.FileTime walModified)
    {
        static SourceIdentity capture(Path source) throws IOException
        {
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path wal = Path.of(source + "-wal");
            BasicFileAttributes journal = Files.exists(wal, LinkOption.NOFOLLOW_LINKS) ?
                Files.readAttributes(wal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS) : null;
            return new SourceIdentity(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(),
                journal == null ? -1 : journal.size(), journal == null ? null : journal.lastModifiedTime());
        }

        void requireUnchanged(Path source) throws IOException
        {
            if(!equals(capture(source)))
                throw new IOException("The previous VCE database changed while settings were read. Close it and try again.");
        }
    }
}

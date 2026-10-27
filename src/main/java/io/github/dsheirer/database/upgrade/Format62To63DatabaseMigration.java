/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Preserves the large source file as history and durably extracts configuration before committing its new role. */
final class Format62To63DatabaseMigration implements DatabaseMigrationStep
{
    @FunctionalInterface
    interface ActivationObserver
    {
        void phase(String phase) throws IOException;
    }

    private static final ThreadLocal<ActivationObserver> ACTIVATION_OBSERVER = new ThreadLocal<>();

    static AutoCloseable observeActivation(ActivationObserver observer)
    {
        ACTIVATION_OBSERVER.set(observer);
        return ACTIVATION_OBSERVER::remove;
    }
    private static void activationPhase(String phase) throws IOException
    {
        ActivationObserver observer = ACTIVATION_OBSERVER.get();
        if(observer != null)
        {
            observer.phase(phase);
        }
    }

    public String id()
    {
        return "configuration-history-separation-v1";
    }

    public String description()
    {
        return "Separate configuration from retained receiver activity";
    }

    public int sourceVersion()
    {
        return 62;
    }

    public int targetVersion()
    {
        return 63;
    }

    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "configuration and receiver activity ownership", DatabaseMigrationEffect.UNKNOWN_COUNT,
            "Preserve configuration, every historical row, counters and identities in a matching database pair; " +
                "configuration deletion leaves retained activity to scheduled retention"));
    }
    public List<DatabaseMigrationEffect> inspectSource(Connection connection) throws SQLException
    {
        return List.of(new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
            "configuration and receiver activity ownership", 1,
            "Preserve the original history file and extract configuration into its independent companion"));
    }

    public void migrateSource(Connection connection) throws SQLException
    {
        Path source = databasePath(connection);
        Path stage = ActivityDatabasePath.configurationStage(source);
        String pairId = UUID.randomUUID().toString();
        try
        {
            if(Files.exists(stage) || Files.exists(ActivityDatabasePath.splitReceipt(source)) ||
                Files.exists(ActivityDatabasePath.forConfiguration(source)))
            {
                throw new IOException("A configuration/activity transition or companion already exists; " +
                    "complete or recover it with the Application Migrator first");
            }
            writeReceipt(source, pairId);
            preserveRecoveredChannelBindings(connection, source);
            ConfigurationHistorySchema.addAliasActivityIdentity(connection);
            // This connection commits independently BEFORE changing the source. WAL never needs to promise an
            // atomic commit across attached files: either the source stays monolithic or its complete config is ready.
            try(Connection target = DriverManager.getConnection("jdbc:sqlite:" + stage))
            {
                FileAccessAttributeSnapshot.restrictSensitiveFile(stage);
                try(Statement statement = target.createStatement())
                {
                    statement.execute("PRAGMA journal_mode=DELETE");
                    statement.execute("PRAGMA synchronous=FULL");
                    statement.execute("PRAGMA foreign_keys=OFF");
                }
                target.setAutoCommit(false);
                ConfigurationHistorySchema.createConfiguration(target);
                ConfigurationHistorySchema.copyConfiguration(connection, target);
                ConfigurationHistorySchema.setIdentity(target, ConfigurationHistorySchema.CONFIGURATION_ROLE, pairId);
                SdrTrunkDatabaseStartup.setMetadata(target, DatabaseFormatCatalog.FORMAT_VERSION_KEY, "63");
                ConfigurationHistoryDatabase.validateRole(target, ConfigurationHistorySchema.CONFIGURATION_ROLE);
                // This adjacent step retains its exact format-63 contract when later targets are introduced.
                DatabaseFormatCatalog.inspect(target);
                try(Statement query = target.createStatement();
                    ResultSet rows = query.executeQuery("PRAGMA foreign_key_check"))
                {
                    if(rows.next())
                    {
                        throw new SQLException("Extracted configuration contains an invalid relationship");
                    }
                }
                target.commit();
            }
            forceFile(stage);
            ConfigurationHistorySchema.separateHistoryOwners(connection);
            ConfigurationHistorySchema.removeConfiguration(connection);
            ConfigurationHistorySchema.setIdentity(connection, ConfigurationHistorySchema.HISTORY_ROLE, pairId);
            // Migration validation uses the already durable extracted configuration. Runtime never attaches a
            // writable configuration file to an activity writer.
            try(PreparedStatement attach = connection.prepareStatement("ATTACH DATABASE ? AS configuration"))
            {
                attach.setString(1, stage.toUri() + "?mode=ro");
                attach.execute();
            }
            io.github.dsheirer.stats.StatsHistoryContext.installSubscriberContext(connection);
        }
        catch(IOException failure)
        {
            throw new SQLException("Unable to prepare the configuration/activity transition", failure);
        }
    }

    private static void preserveRecoveredChannelBindings(Connection connection, Path source) throws IOException, SQLException
    {
        // The old loader can recover an interrupted delayed P25 save only from a verified complete site projection.
        // Persist that same result once before removing the history join from ordinary configuration loads.
        var channels = new io.github.dsheirer.database.configuration.ConfigurationDatabaseStore(source).loadChannels(connection);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try(PreparedStatement update = connection.prepareStatement("""
            UPDATE configuration_channel SET config_json=json_set(config_json,'$.p25SiteIdentity',json(?))
            WHERE configuration_id=? AND (json_type(config_json,'$.p25SiteIdentity') IS NULL
                OR json_type(config_json,'$.p25SiteIdentity')='null')
            """))
        {
            for(var channel: channels)
            {
                if(channel.getP25SiteIdentity() != null)
                {
                    update.setString(1, mapper.writeValueAsString(channel.getP25SiteIdentity()));
                    update.setString(2, channel.getConfigurationId());
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }

    static Path databasePath(Connection connection) throws SQLException
    {
        try(Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("PRAGMA database_list"))
        {
            while(rows.next())
            {
                if("main".equals(rows.getString("name")))
                {
                    String file = rows.getString("file");
                    if(file != null && !file.isBlank())
                    {
                        return Path.of(file).toAbsolutePath().normalize();
                    }
                }
            }
        }
        throw new SQLException("The format-63 transition requires a file-backed application database");
    }

    /** Application Migrator only: finish the committed transition or discard its uncommitted private extraction. */
    static void recover(Path configuration) throws IOException, SQLException
    {
        Path receipt = ActivityDatabasePath.splitReceipt(configuration);
        if(!Files.exists(receipt))
        {
            return;
        }
        if(Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt))
        {
            throw new IOException("The database transition receipt is not an ordinary file");
        }
        Properties properties = new Properties();
        try(InputStream input = Files.newInputStream(receipt))
        {
            properties.load(input);
        }
        String pairId = properties.getProperty("pair_id");
        if(!configuration.getFileName().toString().equals(properties.getProperty("configuration")) ||
            pairId == null || !pairId.matches("[0-9a-f-]{36}"))
        {
            throw new IOException("The database transition receipt is invalid; files were preserved");
        }
        Path stage = ActivityDatabasePath.configurationStage(configuration);
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        boolean moveHistory = false;
        if(Files.isRegularFile(configuration))
        {
            recoverOriginalJournal(configuration, properties);
            try(Connection source = ConfigurationHistoryDatabase.openReadOnly(configuration))
            {
                String role = ConfigurationHistorySchema.metadata(source, ConfigurationHistorySchema.ROLE_KEY);
                if(ConfigurationHistorySchema.HISTORY_ROLE.equals(role))
                {
                    requireOriginalFile(configuration, properties);
                    requireIdentity(source, pairId, ConfigurationHistorySchema.HISTORY_ROLE);
                    requireStage(stage, pairId);
                    if(Files.exists(history))
                    {
                        throw new IOException("Activity companion already exists; files were preserved");
                    }
                    moveHistory = true;
                }
                else if(ConfigurationHistorySchema.CONFIGURATION_ROLE.equals(role))
                {
                    requireIdentity(source, pairId, ConfigurationHistorySchema.CONFIGURATION_ROLE);
                    requireOriginalFile(history, properties);
                    try(Connection activity = ConfigurationHistoryDatabase.openReadOnly(history))
                    {
                        requireIdentity(activity, pairId, ConfigurationHistorySchema.HISTORY_ROLE);
                    }
                    if(Files.exists(stage))
                    {
                        throw new IOException("Unexpected configuration stage after activation; files were preserved");
                    }
                    Files.delete(receipt);
                    return;
                }
                else
                {
                    requireOriginalFile(configuration, properties);
                    // The history transaction rolled back. Only our receipt-bound extracted config is disposable;
                    // the original supported monolith has not been changed by the split.
                    DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(source);
                    if(detected.version() > 62)
                    {
                        throw new IOException("Unexpected source role during transition recovery");
                    }
                    if(Files.exists(history))
                    {
                        throw new IOException("Unexpected activity companion; files were preserved");
                    }
                    if(Files.exists(stage))
                    {
                        requireDiscardableStage(stage, pairId);
                        Files.delete(stage);
                    }
                    Files.delete(receipt);
                    return;
                }
            }
        }
        // Windows refuses a rename while any SQLite handle is open. Every validation handle above is closed first.
        if(moveHistory)
        {
            checkpoint(configuration);
            activationPhase("before-history-rename");
            move(configuration, history);
            activationPhase("after-history-rename");
        }
        if(!Files.isRegularFile(history))
        {
            throw new IOException("The interrupted transition has no retained activity file");
        }
        requireOriginalFile(history, properties);
        try(Connection activity = ConfigurationHistoryDatabase.openReadOnly(history))
        {
            requireIdentity(activity, pairId, ConfigurationHistorySchema.HISTORY_ROLE);
        }
        requireStage(stage, pairId);
        if(Files.exists(configuration))
        {
            throw new IOException("Configuration destination is occupied; files were preserved");
        }
        activationPhase("before-configuration-rename");
        move(stage, configuration);
        activationPhase("after-configuration-rename");
        // Validation without the still-present receipt proves both committed roles before removing recovery evidence.
        try(Connection config = ConfigurationHistoryDatabase.openReadOnly(configuration))
        {
            requireIdentity(config, pairId, ConfigurationHistorySchema.CONFIGURATION_ROLE);
        }
        Files.delete(receipt);
        // Activation also serves frozen historical fixture/recovery callers; runtime current admission follows it.
        ConfigurationHistoryDatabase.validatePairIdentity(configuration);
    }

    /** A crash before the source COMMIT can leave a hot journal beside a durable extraction receipt. */
    private static void recoverOriginalJournal(Path source, Properties receipt) throws IOException, SQLException
    {
        Path journal = Path.of(source + "-journal");
        if(!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return;
        for(Path file: List.of(source, journal))
        {
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The interrupted database and its journal must be ordinary files; files were preserved");
            ApplicationDatabaseMigrator.requireSingleFilesystemLinkWhenSupported(file);
        }
        if(Files.size(journal) == 0) return;
        // Only the receipt's original source can have an uncommitted split transaction. Prove that identity
        // before permitting SQLite to recover it. mode=rw never creates a replacement database, and SQLite
        // alone decides whether this journal needs replay; no sidecar is manually discarded.
        if(receipt.getProperty("source_file_key") == null)
            throw new IOException("The interrupted upgrade lacks a source file identity. Preserve both installations " +
                "and restore the complete backup before retrying.");
        requireOriginalFile(source, receipt);
        try(Connection recovery = DriverManager.getConnection("jdbc:sqlite:" + source.toUri() + "?mode=rw");
            Statement query = recovery.createStatement();
            ResultSet rows = query.executeQuery("SELECT 1 FROM sqlite_schema LIMIT 1"))
        {
            rows.next();
        }
    }

    private static void requireStage(Path stage, String pairId) throws IOException, SQLException
    {
        if(Files.isSymbolicLink(stage) || !Files.isRegularFile(stage))
        {
            throw new IOException("The committed configuration extraction is missing; files were preserved");
        }
        try(Connection target = ConfigurationHistoryDatabase.openReadOnly(stage))
        {
            requireIdentity(target, pairId, ConfigurationHistorySchema.CONFIGURATION_ROLE);
        }
    }

    private static void requireDiscardableStage(Path stage, String pairId) throws IOException, SQLException
    {
        if(Files.isSymbolicLink(stage) || !Files.isRegularFile(stage))
        {
            throw new IOException("Unexpected transition stage; files were preserved");
        }
        // A process exit before the extraction commit leaves an empty SQLite file. Recover its own journal only
        // here in the Application Migrator; an unrecognized populated file is never removed.
        try(Connection target = DriverManager.getConnection("jdbc:sqlite:" + stage);
            Statement query = target.createStatement();
            ResultSet rows = query.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"))
        {
            if(rows.next() && rows.getLong(1) == 0)
            {
                return;
            }
        }
        requireStage(stage, pairId);
    }

    private static void requireIdentity(Connection connection, String pairId, String role) throws SQLException
    {
        ConfigurationHistoryDatabase.validateRole(connection, role);
        if(!pairId.equals(ConfigurationHistorySchema.metadata(connection, ConfigurationHistorySchema.PAIR_ID_KEY)))
        {
            throw new SQLException("Database transition pair identity mismatch; files were preserved");
        }
    }

    private static void writeReceipt(Path source, String pairId) throws IOException
    {
        Properties properties = new Properties();
        properties.setProperty("pair_id", pairId);
        properties.setProperty("configuration", source.getFileName().toString());
        String sourceKey = PortableFileIdentity.read(source);
        if(sourceKey != null)
        {
            properties.setProperty("source_file_key", sourceKey);
        }
        Path receipt = ActivityDatabasePath.splitReceipt(source);
        try(OutputStream output = Files.newOutputStream(receipt, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            properties.store(output, "Application Migrator format-63 activation receipt");
        }
        FileAccessAttributeSnapshot.restrictSensitiveFile(receipt);
        forceFile(receipt);
    }
    private static void requireOriginalFile(Path file, Properties receipt) throws IOException
    {
        String expected = receipt.getProperty("source_file_key");
        String actual = expected == null ? null : PortableFileIdentity.read(file);
        if(expected != null && !expected.equals(actual))
        {
            throw new IOException("The transition source file was replaced; files were preserved");
        }
    }

    private static void forceFile(Path file) throws IOException
    {
        try(FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
    }

    private static void checkpoint(Path file) throws SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            Statement statement = connection.createStatement())
        {
            try(ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(rows.next() && rows.getInt(1) != 0)
                {
                    throw new SQLException("The activity transition could not be checkpointed");
                }
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException
    {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }
}

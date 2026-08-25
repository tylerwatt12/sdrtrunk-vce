/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.database.configuration;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationSnapshotValidator;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Single persistence facade for Alias, scan-list, channel, and broadcast-stream playlist configuration.
 *
 * <p>Every multi-table write uses one SQLite transaction. Alias and complete-snapshot writes return canonical state
 * read back before that transaction commits; the existing delayed channel/stream saver remains a write-only path.</p>
 */
public final class ConfigurationRepository
{
    private static final List<FingerprintTable> OWNED_TABLES = List.of(
        new FingerprintTable("alias_list", "id"),
        new FingerprintTable("alias", "id"),
        new FingerprintTable("scan_list", "id"),
        new FingerprintTable("alias_scan_list_membership", "alias_id, scan_list_id"),
        new FingerprintTable("alias_list_unmatched_talkgroup_scan_list_membership",
            "alias_list_id, scan_list_id"),
        new FingerprintTable("alias_broadcast_channel", "id"),
        new FingerprintTable("alias_list_unmatched_talkgroup_stream", "id"),
        new FingerprintTable("configuration_channel", "id"),
        new FingerprintTable("configuration_broadcast_stream", "id"));
    private final Path mDatabasePath;
    private final AliasDatabaseStore mAliasStore;
    private final ScanListDatabaseStore mScanListStore;
    private final ConfigurationDatabaseStore mChannelAndBroadcastStore;
    private final ConfigurationIdentityAllocator mIdentityAllocator;

    public ConfigurationRepository(Path databasePath)
    {
        if(databasePath == null)
        {
            throw new IllegalArgumentException("Database path cannot be null");
        }
        mDatabasePath = databasePath.toAbsolutePath().normalize();
        mAliasStore = new AliasDatabaseStore(mDatabasePath);
        mScanListStore = new ScanListDatabaseStore(mDatabasePath);
        mChannelAndBroadcastStore = new ConfigurationDatabaseStore(mDatabasePath);
        mIdentityAllocator = new ConfigurationIdentityAllocator(mDatabasePath);
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    /** Loads one complete configuration from a single consistent database view. */
    public ConfigurationSnapshot load() throws IOException, SQLException
    {
        return inReadTransaction(this::load);
    }

    /** Loads a complete configuration and its stable database token from exactly the same SQLite read snapshot. */
    public FingerprintedSnapshot loadWithFingerprint() throws IOException, SQLException
    {
        return inReadTransaction(connection ->
            new FingerprintedSnapshot(load(connection), fingerprint(connection)));
    }

    /** Returns a stable token for every table owned by this repository. */
    public String fingerprint() throws IOException, SQLException
    {
        return inReadTransaction(this::fingerprint);
    }

    /** Atomically replaces the complete configuration and returns its canonical committed representation. */
    public synchronized ConfigurationSnapshot replace(ConfigurationSnapshot proposed)
        throws IOException, SQLException
    {
        if(proposed == null)
        {
            throw new IllegalArgumentException("Configuration snapshot cannot be null");
        }
        ConfigurationSnapshotValidator.validateForWrite(proposed);

        AliasConfigurationSnapshot detachedAliases =
            AliasConfigurationSnapshot.detachedCopyOf(proposed.aliasConfiguration());
        ConfigurationSnapshot detached = new ConfigurationSnapshot(detachedAliases.definitions(),
            detachedAliases.aliases(), detachedAliases.scanLists(), proposed.channels(),
            proposed.broadcastConfigurations());

        return inTransaction(connection ->
        {
            mAliasStore.replaceAliases(connection, detached.aliases(), detached.aliasListDefinitions());
            mScanListStore.replaceConfiguration(connection, detached.scanListConfiguration());
            mChannelAndBroadcastStore.replace(connection,
                new ChannelAndBroadcastConfiguration(detached.channels(), detached.broadcastConfigurations()));
            return load(connection);
        });
    }

    public AliasConfigurationSnapshot loadAliasConfiguration() throws IOException, SQLException
    {
        return inReadTransaction(this::loadAliasConfiguration);
    }

    /** Commits Alias-owned state without rewriting unrelated channel or broadcast rows. */
    public synchronized AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
                                                                             Collection<String> removedAliasListNames)
        throws IOException, SQLException
    {
        return commitAliasConfiguration(proposed, removedAliasListNames, null, null, null);
    }

    /** Commits Alias-owned state and one referenced broadcast-stream rename in the same transaction. */
    public synchronized AliasConfigurationSnapshot commitAliasConfigurationWithBroadcastRename(
        AliasConfigurationSnapshot proposed, Collection<String> removedAliasListNames,
        List<BroadcastConfiguration> broadcastConfigurations, String previousName, String updatedName)
        throws IOException, SQLException
    {
        if(broadcastConfigurations == null)
        {
            throw new IllegalArgumentException("Broadcast configurations cannot be null");
        }
        if(previousName == null || previousName.isBlank() || updatedName == null || updatedName.isBlank())
        {
            throw new IllegalArgumentException("Broadcast rename names must be nonblank");
        }
        return commitAliasConfiguration(proposed, removedAliasListNames, List.copyOf(broadcastConfigurations),
            previousName, updatedName);
    }

    private AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
                                                                 Collection<String> removedAliasListNames,
                                                                 List<BroadcastConfiguration> broadcastConfigurations,
                                                                 String previousBroadcastName,
                                                                 String updatedBroadcastName)
        throws IOException, SQLException
    {
        AliasConfigurationSnapshot detached = AliasConfigurationSnapshot.detachedCopyOf(proposed);
        return inTransaction(connection ->
        {
            mAliasStore.replaceAliases(connection, detached.aliases(), detached.definitions());
            mScanListStore.replaceConfiguration(connection, detached.scanLists());
            mChannelAndBroadcastStore.clearAliasListAssignments(connection, removedAliasListNames);
            if(broadcastConfigurations != null)
            {
                mChannelAndBroadcastStore.replaceBroadcastConfigurationsWithRename(connection,
                    broadcastConfigurations, previousBroadcastName, updatedBroadcastName);
            }
            return loadAliasConfiguration(connection);
        });
    }

    /** Replaces delayed desktop channel and stream edits in one transaction. */
    public synchronized void replaceChannelAndBroadcastConfiguration(
        Collection<Channel> channels, Collection<BroadcastConfiguration> broadcastConfigurations)
        throws IOException, SQLException
    {
        ChannelAndBroadcastConfiguration proposed = new ChannelAndBroadcastConfiguration(List.copyOf(channels),
            List.copyOf(broadcastConfigurations));
        inTransaction(connection ->
        {
            List<AliasListDefinition> definitions = mAliasStore.loadAliasListDefinitions(connection);
            ConfigurationSnapshotValidator.validateChannelAndBroadcastWrite(definitions, proposed.channels(),
                proposed.broadcastConfigurations());
            mChannelAndBroadcastStore.replace(connection, proposed);
            return null;
        });
    }

    public List<Long> nextAliasIds(Collection<Long> candidateIds, int count)
    {
        return mIdentityAllocator.nextAliasIds(candidateIds, count);
    }

    public List<Long> nextAliasListIds(Collection<Long> candidateIds, int count)
    {
        return mIdentityAllocator.nextAliasListIds(candidateIds, count);
    }

    public List<Long> nextScanListIds(Collection<Long> candidateIds, int count)
    {
        return mIdentityAllocator.nextScanListIds(candidateIds, count);
    }

    private ConfigurationSnapshot load(Connection connection) throws IOException, SQLException
    {
        AliasConfigurationSnapshot aliases = loadAliasConfiguration(connection);
        ChannelAndBroadcastConfiguration channels = mChannelAndBroadcastStore.load(connection);
        return new ConfigurationSnapshot(aliases.definitions(), aliases.aliases(), aliases.scanLists(),
            channels.channels(), channels.broadcastConfigurations());
    }

    private AliasConfigurationSnapshot loadAliasConfiguration(Connection connection) throws SQLException
    {
        List<AliasListDefinition> definitions = mAliasStore.loadAliasListDefinitions(connection);
        List<Alias> aliases = mAliasStore.loadAliases(connection, definitions);
        ScanListConfiguration scanLists = mScanListStore.loadConfiguration(connection);
        return new AliasConfigurationSnapshot(definitions, aliases, scanLists);
    }

    private String fingerprint(Connection connection) throws SQLException
    {
        MessageDigest digest = sha256();
        for(FingerprintTable table: OWNED_TABLES)
        {
            update(digest, table.name());
            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table.name() +
                    " ORDER BY " + table.orderBy()))
            {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columns = metadata.getColumnCount();
                for(int column = 1; column <= columns; column++)
                {
                    update(digest, metadata.getColumnName(column));
                }
                while(resultSet.next())
                {
                    for(int column = 1; column <= columns; column++)
                    {
                        Object value = resultSet.getObject(column);
                        update(digest, value != null ? value.getClass().getName() : null);
                        update(digest, value != null ? value.toString() : null);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value)
    {
        if(value == null)
        {
            digest.update((byte)0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)1);
        digest.update((byte)(bytes.length >>> 24));
        digest.update((byte)(bytes.length >>> 16));
        digest.update((byte)(bytes.length >>> 8));
        digest.update((byte)bytes.length);
        digest.update(bytes);
    }

    private <T> T inTransaction(TransactionOperation<T> operation) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            connection.setAutoCommit(false);
            try
            {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            }
            catch(IOException | SQLException | RuntimeException | Error exception)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackException)
                {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    private <T> T inReadTransaction(TransactionOperation<T> operation) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            connection.setAutoCommit(false);
            try
            {
                T result = operation.apply(connection);
                connection.rollback();
                return result;
            }
            catch(IOException | SQLException | RuntimeException | Error exception)
            {
                try
                {
                    connection.rollback();
                }
                catch(SQLException rollbackException)
                {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    @FunctionalInterface
    private interface TransactionOperation<T>
    {
        T apply(Connection connection) throws IOException, SQLException;
    }

    public record FingerprintedSnapshot(ConfigurationSnapshot snapshot, String fingerprint)
    {
    }

    private record FingerprintTable(String name, String orderBy)
    {
    }
}

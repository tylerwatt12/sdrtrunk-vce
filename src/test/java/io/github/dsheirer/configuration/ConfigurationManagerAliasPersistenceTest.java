/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasFactory;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that editor-style Alias mutations commit to the current schema before entering the live model. */
class ConfigurationManagerAliasPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void atomicallyCreatesReplacesAndDeletesByDurableIdentity() throws Exception
    {
        assertEquals(6, SdrTrunkDatabaseSchema.ALIAS_SCHEMA_VERSION);
        Path dataRoot = mTemporaryFolder.resolve("alias-persistence");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Alias first = alias("First", definition, 100);
        Alias second = alias("Second", definition, 200);
        Alias third = alias("Third", definition, 300);
        Channel sentinelChannel = new Channel("Sentinel Control");
        sentinelChannel.setSystem("Sentinel System");
        sentinelChannel.setAliasListName("County");
        sentinelChannel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        sentinelChannel.setSourceConfiguration(source);
        String sentinelChannelId = sentinelChannel.getConfigurationId();
        RadioResolveConfiguration sentinelStream = new RadioResolveConfiguration();
        sentinelStream.setName("Sentinel Stream");
        sentinelStream.setHost("https://example.invalid/upload");
        sentinelStream.setApiKey("test-api-key");
        sentinelStream.setNodeName("TEST-NODE");
        ConfigurationState seed = new ConfigurationState();
        seed.setAliasListDefinitions(List.of(definition));
        seed.setAliases(List.of(first, second, third));
        seed.setChannels(List.of(sentinelChannel));
        seed.setBroadcastConfigurations(List.of(sentinelStream));
        new ConfigurationSnapshotDatabaseStore(database).replace(seed);
        long firstId = first.getId();
        long secondId = second.getId();

        ConfigurationManager manager = null;
        String previousRoot = System.getProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY);

        try
        {
            System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, dataRoot.toString());
            resetPortablePaths();
            UserPreferences preferences = new TestUserPreferences(dataRoot);
            manager = new ConfigurationManager(preferences, null, new AliasModel(), null, new IconModel());
            manager.init();

            Alias liveFirst = manager.getAliasModel().getAlias(firstId);
            Alias replacement = AliasFactory.copyOf(liveFirst);
            replacement.setId(liveFirst.getId());
            replacement.setName("First Edited");
            Alias clone = AliasFactory.copyOf(liveFirst);
            clone.setName("First Clone");
            Alias liveSecond = manager.getAliasModel().getAlias(secondId);

            assertTrue(manager.commitAliasChanges(List.of(replacement, clone), List.of(liveSecond)));

            List<Alias> live = manager.getAliasModel().getAliases();
            List<Alias> stored = loadAliases(database);
            assertEquals(3, live.size());
            assertEquals(3, stored.size());
            assertEquals(3, new HashSet<>(live.stream().map(Alias::getId).toList()).size());
            assertEquals(3, new HashSet<>(stored.stream().map(Alias::getId).toList()).size());
            assertEquals("First Edited", manager.getAliasModel().getAlias(firstId).getName());
            assertTrue(stored.stream().noneMatch(alias -> alias.getId() == secondId));
            assertNotEquals(Alias.UNASSIGNED_ID, clone.getId());
            assertNotEquals(firstId, clone.getId());
            assertTrue(stored.stream().anyMatch(alias -> alias.getId() == clone.getId() &&
                "First Clone".equals(alias.getName())));

            Alias rejected = alias("Wrong Protocol", definition, 999);
            rejected.setMatchIdentifier(new Talkgroup(Protocol.DMR, 999));
            assertFalse(manager.commitAliasChanges(List.of(rejected), List.of()));
            assertEquals(Alias.UNASSIGNED_ID, rejected.getId());
            assertEquals(3, manager.getAliasModel().getAliases().size());
            assertEquals(3, loadAliases(database).size());

            Alias duplicateDraft = alias("Duplicate Input", definition, 400);
            ConfigurationManager activeManager = manager;
            assertThrows(IllegalArgumentException.class,
                () -> activeManager.commitAliasChanges(List.of(duplicateDraft, duplicateDraft), List.of()));
            assertEquals(Alias.UNASSIGNED_ID, duplicateDraft.getId());
            assertEquals(3, manager.getAliasModel().getAliases().size());
            assertEquals(3, loadAliases(database).size());

            AliasListDefinition liveDefinition = manager.getAliasModel().getAliasListDefinition("County");
            Alias imported = alias("RadioReference Import", liveDefinition, 401);
            setConfigurationLoading(manager, true);

            try
            {
                manager.getAliasModel().addAlias(imported);
            }
            finally
            {
                setConfigurationLoading(manager, false);
            }

            assertEquals(Alias.UNASSIGNED_ID, imported.getId());
            assertEquals(4, manager.getAliasModel().getAliases().size());
            assertEquals(3, loadAliases(database).size());

            Alias importedEdit = AliasFactory.copyOf(imported);
            importedEdit.setName("RadioReference Import Edited");
            assertTrue(manager.commitAliasReplacement(imported, importedEdit));
            assertTrue(importedEdit.getId() > Alias.UNASSIGNED_ID);
            List<Alias> importedLive = manager.getAliasModel().getAliases();
            List<Alias> importedStored = loadAliases(database);
            assertEquals(4, importedLive.size());
            assertEquals(4, importedStored.size());
            assertEquals(4, new HashSet<>(importedLive.stream().map(Alias::getId).toList()).size());
            assertEquals(4, new HashSet<>(importedStored.stream().map(Alias::getId).toList()).size());
            assertTrue(importedLive.stream().noneMatch(alias -> alias == imported));
            assertEquals(1, importedLive.stream()
                .filter(alias -> "RadioReference Import Edited".equals(alias.getName())).count());
            assertEquals(1, importedStored.stream()
                .filter(alias -> "RadioReference Import Edited".equals(alias.getName())).count());
            assertUnrelatedConfigurationPreserved(database, sentinelChannelId);

            Alias racedImport = alias("Raced RadioReference Import", liveDefinition, 402);
            manager.getAliasModel().addAlias(racedImport);
            Alias racedEdit = AliasFactory.copyOf(racedImport);
            racedEdit.setName("Raced RadioReference Import Edited");
            assertEquals(Alias.UNASSIGNED_ID, racedImport.getId());
            assertEquals(Alias.UNASSIGNED_ID, racedEdit.getId());

            //Deterministically simulate the pending delayed saver winning after the editor copied the live ID=0 row.
            manager.flushConfiguration();
            assertTrue(racedImport.getId() > Alias.UNASSIGNED_ID);
            assertEquals(Alias.UNASSIGNED_ID, racedEdit.getId());
            assertTrue(manager.commitAliasReplacement(racedImport, racedEdit));
            assertEquals(racedImport.getId(), racedEdit.getId());
            List<Alias> racedLive = manager.getAliasModel().getAliases();
            List<Alias> racedStored = loadAliases(database);
            assertEquals(5, racedLive.size());
            assertEquals(5, racedStored.size());
            assertEquals(5, new HashSet<>(racedLive.stream().map(Alias::getId).toList()).size());
            assertEquals(5, new HashSet<>(racedStored.stream().map(Alias::getId).toList()).size());
            assertEquals(1, racedLive.stream()
                .filter(alias -> "Raced RadioReference Import Edited".equals(alias.getName())).count());
            assertEquals(1, racedStored.stream()
                .filter(alias -> "Raced RadioReference Import Edited".equals(alias.getName())).count());

            assertUnrelatedConfigurationPreserved(database, sentinelChannelId);

            Alias beforeRollback = manager.getAliasModel().getAlias(firstId);
            Alias rolledBackEdit = AliasFactory.copyOf(beforeRollback);
            rolledBackEdit.setId(beforeRollback.getId());
            rolledBackEdit.setName("Must Roll Back");
            installFailingAliasInsertTrigger(database);

            try
            {
                assertFalse(manager.commitAliasReplacement(beforeRollback, rolledBackEdit));
                assertTrue(manager.getAliasModel().getAlias(firstId) == beforeRollback);
                assertEquals("First Edited", manager.getAliasModel().getAlias(firstId).getName());
                List<Alias> afterRollback = loadAliases(database);
                assertEquals(5, afterRollback.size());
                assertTrue(afterRollback.stream().noneMatch(alias -> "Must Roll Back".equals(alias.getName())));
                assertEquals(1, new AliasDatabaseStore(database).loadAliasListDefinitions().size());
                assertUnrelatedConfigurationPreserved(database, sentinelChannelId);
            }
            finally
            {
                removeFailingAliasInsertTrigger(database);
            }
        }
        finally
        {
            if(manager != null)
            {
                MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
            }

            if(previousRoot == null)
            {
                System.clearProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY);
            }
            else
            {
                System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, previousRoot);
            }

            resetPortablePaths();
        }
    }

    private static Alias alias(String name, AliasListDefinition definition, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static List<Alias> loadAliases(Path database) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        return store.loadAliases(definitions);
    }

    private static void assertUnrelatedConfigurationPreserved(Path database, String channelId) throws Exception
    {
        ConfigurationState preserved = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(1, preserved.getChannels().size());
        assertEquals(channelId, preserved.getChannels().getFirst().getConfigurationId());
        assertEquals("Sentinel Control", preserved.getChannels().getFirst().getName());
        assertEquals(1, preserved.getBroadcastConfigurations().size());
        assertEquals("Sentinel Stream", preserved.getBroadcastConfigurations().getFirst().getName());
    }

    private static void installFailingAliasInsertTrigger(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TRIGGER fail_test_alias_insert
                BEFORE INSERT ON alias
                BEGIN
                    SELECT RAISE(ABORT, 'forced alias insert failure');
                END
                """);
        }
    }

    private static void removeFailingAliasInsertTrigger(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TRIGGER IF EXISTS fail_test_alias_insert");
        }
    }

    private static void resetPortablePaths() throws Exception
    {
        Method reset = PortableApplicationPaths.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    private static void setConfigurationLoading(ConfigurationManager manager, boolean loading) throws Exception
    {
        java.lang.reflect.Field field = ConfigurationManager.class.getDeclaredField("mConfigurationLoading");
        field.setAccessible(true);
        field.setBoolean(manager, loading);
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }
}

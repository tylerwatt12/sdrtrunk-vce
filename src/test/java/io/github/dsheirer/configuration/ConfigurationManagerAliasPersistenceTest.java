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
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that editor-style Alias mutations commit to schema v4 before entering the live model. */
class ConfigurationManagerAliasPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void atomicallyCreatesReplacesAndDeletesByDurableIdentity() throws Exception
    {
        assertEquals(4, SdrTrunkDatabaseSchema.ALIAS_SCHEMA_VERSION);
        Path dataRoot = mTemporaryFolder.resolve("alias-persistence");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        Alias first = alias("First", definition, 100);
        Alias second = alias("Second", definition, 200);
        Alias third = alias("Third", definition, 300);
        ConfigurationState seed = new ConfigurationState();
        seed.setAliasListDefinitions(List.of(definition));
        seed.setAliases(List.of(first, second, third));
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

    private static void resetPortablePaths() throws Exception
    {
        Method reset = PortableApplicationPaths.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
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

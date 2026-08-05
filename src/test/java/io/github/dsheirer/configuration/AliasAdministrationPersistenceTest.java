/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdministrationPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void delayedSaveCannotPersistARejectedMultiAliasMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        BlockingConfigurationManager manager = new BlockingConfigurationManager(
            new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.catalog().revision());
            AliasAdministrationService.MutationResult first = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101), list.revision());
            AliasAdministrationService.MutationResult second = service.createAlias(
                alias("Operations", list.aliasListId(), 102), first.revision());
            List<Long> aliasIds = List.of(first.aliasIds().getFirst(), second.aliasIds().getFirst());
            manager.blockAndFailNextFlush();
            AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
            Thread mutation = Thread.ofPlatform().name("failed-alias-mutation").unstarted(() ->
            {
                try
                {
                    service.bulkEdit(new AliasAdministrationService.BulkEdit(aliasIds, null, 0x123456, null,
                        null, true, null, null, null, null, false), second.revision());
                }
                catch(Throwable throwable)
                {
                    mutationFailure.set(throwable);
                }
            });
            mutation.start();
            manager.awaitBlockedFlush();

            Thread delayedSave = Thread.ofPlatform().name("competing-delayed-save")
                .unstarted(manager.new ConfigurationSaveTask());
            delayedSave.start();

            try
            {
                awaitState(delayedSave, Thread.State.BLOCKED, Duration.ofSeconds(5));
                assertStoredDefaults(database, aliasIds);
            }
            finally
            {
                manager.releaseBlockedFlush();
            }

            mutation.join(10_000);
            delayedSave.join(10_000);
            assertFalse(mutation.isAlive(), "Alias mutation did not finish");
            assertFalse(delayedSave.isAlive(), "Delayed configuration save did not finish");
            assertInstanceOf(AliasAdministrationService.PersistenceException.class, mutationFailure.get());

            for(long aliasId: aliasIds)
            {
                Alias live = service.getAlias(aliasId).alias();
                assertEquals(0, live.getColor());
                assertFalse(live.isRecordable());
            }
            assertStoredDefaults(database, aliasIds);
        }
        finally
        {
            manager.releaseBlockedFlush();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static void assertStoredDefaults(Path database, List<Long> aliasIds) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        List<Alias> aliases = store.loadAliases(definitions).stream()
            .filter(alias -> aliasIds.contains(alias.getId())).toList();
        assertEquals(aliasIds.size(), aliases.size());
        assertEquals(List.of(0, 0), aliases.stream().map(Alias::getColor).toList());
        assertEquals(List.of(false, false), aliases.stream().map(Alias::isRecordable).toList());
    }

    private static void awaitState(Thread thread, Thread.State expected, Duration timeout) throws Exception
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(thread.getState() != expected && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertEquals(expected, thread.getState(), "Configuration saver did not wait for the mutation gate");
    }

    private static Alias alias(String name, long aliasListId, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListId(aliasListId);
        alias.setAliasListName("County P25");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static final class BlockingConfigurationManager extends ConfigurationManager
    {
        private final CountDownLatch mFlushBlocked = new CountDownLatch(1);
        private final CountDownLatch mReleaseFlush = new CountDownLatch(1);
        private final AtomicBoolean mFailExplicitSave = new AtomicBoolean();
        private volatile boolean mBlockFlush;
        private volatile Thread mExplicitSaveThread;

        private BlockingConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null, null);
        }

        private void blockAndFailNextFlush()
        {
            mBlockFlush = true;
            mFailExplicitSave.set(true);
        }

        private void awaitBlockedFlush() throws Exception
        {
            if(!mFlushBlocked.await(5, TimeUnit.SECONDS))
            {
                throw new AssertionError("Alias mutation did not reach its persistence boundary");
            }
        }

        private void releaseBlockedFlush()
        {
            mReleaseFlush.countDown();
        }

        @Override
        public void flushConfiguration()
        {
            if(mBlockFlush)
            {
                mBlockFlush = false;
                mExplicitSaveThread = Thread.currentThread();
                mFlushBlocked.countDown();

                try
                {
                    if(!mReleaseFlush.await(5, TimeUnit.SECONDS))
                    {
                        throw new IllegalStateException("Timed out waiting to release the test persistence boundary");
                    }
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted at the test persistence boundary", exception);
                }
            }

            super.flushConfiguration();
        }

        @Override
        boolean saveConfigurationSnapshotToDatabase()
        {
            if(Thread.currentThread() == mExplicitSaveThread && mFailExplicitSave.compareAndSet(true, false))
            {
                return false;
            }

            return super.saveConfigurationSnapshotToDatabase();
        }
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

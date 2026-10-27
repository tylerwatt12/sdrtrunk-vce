/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.portable.PortableFileIdentity;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class VceUpgradeServiceTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORIES = "user/io/github/dsheirer/preference/directory";
    @TempDir Path temporary;

    @Test
    void alpha12SettingsUpgradeTransfersRecordingsWithoutCopyingOrCarryingActivity() throws Exception
    {
        Path source = root("alpha12"), target = root("new-alpha");
        Format2TestDatabase.create(database(source));
        Path external = Files.createDirectories(temporary.resolve("external-streaming"));
        setDirectories(source, external);
        Map<String,Object> files = recordings(source, true);
        long channels = count(database(source), "configuration_channel"), aliases = count(database(source), "alias");
        List<String> progress = new ArrayList<>();

        var result = VceUpgradeService.upgrade(source, target, MigrationContent.CONFIGURATION_ONLY, true, null,
            progress::add);

        try(PortableDataRootLock lock = result.lock())
        {
            assertFalse(Files.exists(source));
            assertEquals(target, lock.getDataRoot());
            assertThrows(IOException.class, () -> PortableDataRootLock.acquire(target));
            assertCurrentPair(target);
            assertEquals(channels, count(database(target), "configuration_channel"));
            assertEquals(aliases, count(database(target), "alias"));
            assertEquals(0, count(history(target), "receiver_activity_event"));
            assertEquals(0, count(history(target), "radio_system"));
            assertPayload(target, files);
            assertEquals(target.resolve("recordings").toString(), directory(target, "directory.recording"));
            assertEquals(target.resolve("recordings-managed").toString(), directory(target, "directory.recording.managed"));
            assertEquals(external.toString(), directory(target, "directory.streaming"));
            assertTrue(result.migration().helperOutput().contains("Source history was not copied, counted or scanned"));
            assertTrue(progress.stream().anyMatch(label -> label.contains("without copying saved Activity")));
            assertFalse(VceUpgradeService.isPending(target));
            assertFalse(Files.exists(database(target).getParent().resolve("backups")));
        }
    }

    @Test
    void currentSettingsUpgradeTransfersCompleteProfileAndUsesFreshEmptyMatchingActivity() throws Exception
    {
        Path source = root("current"), target = root("new-current");
        Format64TestDatabase.create(database(source));
        assertTrue(count(history(source), "receiver_activity_event") > 0);
        Path external = Files.createDirectories(temporary.resolve("kept-external-output"));
        setDirectories(source, external);
        Map<String,Object> files = recordings(source, false);
        Object previousHistory = identity(history(source));

        var result = VceUpgradeService.upgrade(source, target, MigrationContent.CONFIGURATION_ONLY, true, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertCurrentPair(target);
            assertEquals(0, count(history(target), "receiver_activity_event"));
            assertNotEquals(previousHistory, identity(history(target)));
            assertPayload(target, files);
            assertEquals(target.resolve("recordings-managed").toString(), directory(target, "directory.recording.managed"));
            assertEquals(external.toString(), directory(target, "directory.streaming"));
        }
    }

    @Test
    void fullUpgradeKeepsHistoryAndAudioFileIdentitiesInsteadOfCopyingThem() throws Exception
    {
        for(int version: List.of(63, 64))
        {
            Path source = root("full-" + version), target = root("new-full-" + version);
            if(version == 63) Format63TestDatabase.create(database(source));
            else Format64TestDatabase.create(database(source));
            long events = count(history(source), "receiver_activity_event");
            long systems = count(history(source), "radio_system");
            assertTrue(events > 0);
            Object previousHistory = identity(history(source));
            Path external = Files.createDirectories(temporary.resolve("external-full-output-" + version));
            setDirectories(source, external);
            Map<String,Object> files = recordings(source, false);

            var result = VceUpgradeService.upgrade(source, target, MigrationContent.FULL, true, null, null);

            try(PortableDataRootLock ignored = result.lock())
            {
                assertCurrentPair(target);
                assertEquals(events, count(history(target), "receiver_activity_event"));
                assertEquals(systems, count(history(target), "radio_system"));
                assertEquals(previousHistory, identity(history(target)));
                assertPayload(target, files);
                assertEquals(target.resolve("recordings-managed").toString(), directory(target, "directory.recording.managed"));
                assertEquals(external.toString(), directory(target, "directory.streaming"));
                assertFalse(Files.exists(source));
                assertFalse(VceUpgradeService.isPending(target));
            }
        }
    }

    @Test
    void backupAcknowledgmentIsRequiredBeforeAnyLockReceiptOrPreparation() throws Exception
    {
        Path source = root("unconfirmed"), target = root("new-unconfirmed");
        Format64TestDatabase.create(database(source));
        byte[] configuration = Files.readAllBytes(database(source)), activity = Files.readAllBytes(history(source));
        List<String> progress = new ArrayList<>();

        IOException failure = assertThrows(IOException.class, () -> VceUpgradeService.upgrade(source, target,
            MigrationContent.CONFIGURATION_ONLY, false, null, progress::add));

        assertTrue(failure.getMessage().contains("confirm the backup"));
        assertArrayEquals(configuration, Files.readAllBytes(database(source)));
        assertArrayEquals(activity, Files.readAllBytes(history(source)));
        assertTrue(progress.isEmpty());
        assertFalse(Files.exists(source.resolve(PortableDataRootLock.LOCK_FILE_NAME)));
        assertFalse(Files.exists(target));
        assertFalse(VceUpgradeService.isPending(target));
        try(var entries = Files.list(target.getParent())) { assertEquals(0, entries.count()); }
    }

    @Test
    void inPlaceSettingsUpgradeKeepsBorrowedLockAndDoesNotClearOrCopyTheOriginalHistoryDuringPreparation() throws Exception
    {
        Path root = root("in-place");
        Format64TestDatabase.create(database(root));
        byte[] originalHistory = Files.readAllBytes(history(root));
        Object previousHistory = identity(history(root));
        Map<String,Object> files = recordings(root, false);
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(root);
            AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
                if(phase.equals("PREPARED"))
                {
                    assertEquals(previousHistory, identity(history(root)));
                    assertArrayEquals(originalHistory, Files.readAllBytes(history(root)));
                }
            }))
        {
            var result = VceUpgradeService.upgrade(root, root, MigrationContent.CONFIGURATION_ONLY, true, lock, null);
            assertSame(lock, result.lock());
            assertCurrentPair(root);
            assertEquals(0, count(history(root), "receiver_activity_event"));
            assertPayload(root, files);
            assertThrows(IOException.class, () -> PortableDataRootLock.acquire(root));
            assertFalse(VceUpgradeService.isPending(root));
        }
        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(root)) {}
    }

    @Test
    void refusesAnOccupiedDestinationWithoutWritingIntoIt() throws Exception
    {
        Path source = root("source-occupied"), target = root("occupied");
        Format64TestDatabase.create(database(source));
        Format64TestDatabase.create(database(target));
        byte[] targetConfiguration = Files.readAllBytes(database(target));
        byte[] targetActivity = Files.readAllBytes(history(target));
        List<String> before;
        try(var paths = Files.walk(target)) { before = paths.map(target::relativize).map(Path::toString).sorted().toList(); }

        assertThrows(IOException.class, () -> VceUpgradeService.upgrade(source, target, MigrationContent.FULL, true, null, null));

        List<String> after;
        try(var paths = Files.walk(target)) { after = paths.map(target::relativize).map(Path::toString).sorted().toList(); }
        assertEquals(before, after);
        assertArrayEquals(targetConfiguration, Files.readAllBytes(database(target)));
        assertArrayEquals(targetActivity, Files.readAllBytes(history(target)));
        assertTrue(Files.exists(database(source)));
    }

    @Test
    void resumesCrashesBeforeTransferAndBetweenSavingOriginalFiles() throws Exception
    {
        for(String phase: List.of("PREPARING", "PREPARED", "SAVE_CONFIGURATION", "SAVE_ACTIVITY"))
            assertResumesSettingsCrash(phase);
    }

    @Test
    void resumesCrashesBetweenInstallingPairAndAfterCommitWithoutResettingAgain() throws Exception
    {
        for(String phase: List.of("INSTALL_CONFIGURATION", "INSTALL_ACTIVITY", "VALIDATING", "COMMITTED"))
            assertResumesSettingsCrash(phase);
    }

    @Test
    void resumesPreparedUpgradeUsingConfigurationChangedBeforeTheTransfer() throws Exception
    {
        Path source = root("changed-prepared-source"), target = root("changed-prepared-target");
        Format64TestDatabase.create(database(source));
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("PREPARED")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.CONFIGURATION_ONLY, true, null, null));
        }
        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(source);
            Connection configuration = open(database(source)); var query = configuration.createStatement())
        {
            assertEquals(1, query.executeUpdate("UPDATE alias SET name='Changed after preparation' WHERE id=(SELECT min(id) FROM alias)"));
        }

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock(); Connection configuration = open(database(target));
            var query = configuration.createStatement(); var rows = query.executeQuery("SELECT name FROM alias WHERE id=(SELECT min(id) FROM alias)"))
        {
            assertTrue(rows.next());
            assertEquals("Changed after preparation", rows.getString(1));
            assertCurrentPair(target);
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void resumesInterruptedLegacySettingsPreparationWithoutReusingItsPrivateCompatibilityFiles() throws Exception
    {
        Path source = root("legacy-preparation-source"), target = root("legacy-preparation-target");
        Format52TestDatabase.create(database(source));
        Path paging = source.resolve("audio-analysis/paging-tones.json");
        Files.createDirectories(paging.getParent());
        Files.writeString(paging, "{\"version\":1,\"enabled\":false}");
        assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
            MigrationContent.CONFIGURATION_ONLY, true, null, label -> {
                if(label.equals("Updating the prepared settings")) throw new SimulatedCrash();
            }));
        assertTrue(VceUpgradeService.isPending(target));

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock(); Connection configuration = open(database(target));
            var query = configuration.prepareStatement("SELECT settings_json FROM application_settings WHERE key='audio.paging_tones'");
            var rows = query.executeQuery())
        {
            assertTrue(rows.next());
            assertFalse(JSON.readTree(rows.getString(1)).path("enabled").asBoolean(true));
            assertCurrentPair(target);
            assertEquals("{\"version\":1,\"enabled\":false}", Files.readString(target.resolve("audio-analysis/paging-tones.json")));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void settingsOnlyUpgradeDoesNotInspectDamagedDiscardedHistoryDuringSourceReview() throws Exception
    {
        for(int version: List.of(62, 64))
        {
            Path source = root("damaged-history-source-" + version), target = root("damaged-history-target-" + version);
            if(version == 62) Format62TestDatabase.create(database(source));
            else Format64TestDatabase.create(database(source));
            Path discarded = version == 62 ? database(source) : history(source);
            long page;
            int pageSize;
            try(Connection connection = open(discarded); var query = connection.createStatement())
            {
                try(var rows = query.executeQuery("PRAGMA page_size")) { assertTrue(rows.next()); pageSize = rows.getInt(1); }
                try(var rows = query.executeQuery("SELECT pageno FROM dbstat WHERE name='receiver_activity_event' AND pagetype='leaf' LIMIT 1"))
                { assertTrue(rows.next()); page = rows.getLong(1); }
            }
            try(RandomAccessFile bytes = new RandomAccessFile(discarded.toFile(), "rw"))
            { bytes.seek((page - 1) * pageSize); bytes.write(0); }
            assertThrows(SQLException.class, () -> {
                try(Connection connection = open(discarded); var query = connection.createStatement();
                    var rows = query.executeQuery("SELECT * FROM receiver_activity_event NOT INDEXED"))
                { while(rows.next()) rows.getLong(1); }
            });
            byte[] damagedOriginal = Files.readAllBytes(discarded);

            try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
                if(phase.equals("PREPARED")) assertArrayEquals(damagedOriginal, Files.readAllBytes(discarded));
            }))
            {
                var result = VceUpgradeService.upgrade(source, target, MigrationContent.CONFIGURATION_ONLY, true, null, null);
                try(PortableDataRootLock ignored = result.lock())
                {
                    assertCurrentPair(target);
                    assertTrue(count(database(target), "configuration_channel") > 0);
                    assertTrue(count(database(target), "alias") > 0);
                    assertEquals(0, count(history(target), "receiver_activity_event"));
                }
            }
        }
    }

    @Test
    void failedResumeWhileAnotherOwnerHoldsSourceLockPreservesThePendingOperation() throws Exception
    {
        Path source = root("locked-pending-source"), target = root("locked-pending-target");
        Format64TestDatabase.create(database(source));
        Map<String,Object> files = recordings(source, false);
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("PREPARED")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.CONFIGURATION_ONLY, true, null, null));
        }
        Path intent = intent(target);
        byte[] intentBytes = Files.readAllBytes(intent), sourceBytes = Files.readAllBytes(database(source));
        Path work = workspace(target);
        byte[] preparedBytes = Files.readAllBytes(database(work));
        Object preparedIdentity = identity(database(work));

        try(PortableDataRootLock otherOwner = PortableDataRootLock.acquire(source))
        {
            IOException failure = assertThrows(IOException.class, () -> VceUpgradeService.resume(target, null, null));
            assertTrue(failure.getMessage().contains("already in use"));
            assertTrue(VceUpgradeService.isPending(target));
            assertArrayEquals(intentBytes, Files.readAllBytes(intent));
            assertArrayEquals(sourceBytes, Files.readAllBytes(database(source)));
            assertArrayEquals(preparedBytes, Files.readAllBytes(database(work)));
            assertEquals(preparedIdentity, identity(database(work)));
            assertEquals(PortableProfileTransfer.Phase.PREPARED, PortableProfileTransfer.inspect(target).phase());
            assertFalse(Files.exists(target));
            assertThrows(IOException.class, () -> PortableDataRootLock.acquire(source));
        }

        var result = VceUpgradeService.resume(target, null, null);
        try(PortableDataRootLock lock = result.lock())
        {
            assertEquals(target, lock.getDataRoot());
            assertCurrentPair(target);
            assertEquals(0, count(history(target), "receiver_activity_event"));
            assertPayload(target, files);
            assertFalse(Files.exists(source));
            assertFalse(Files.exists(work));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void competingSourcesCannotReplaceOrCleanAnotherUpgradeClaim() throws Exception
    {
        Path first = root("racing-first"), second = root("racing-second"), target = root("racing-target");
        Format64TestDatabase.create(database(first));
        Format64TestDatabase.create(database(second));
        Map<Path,Map<String,Object>> files = Map.of(first, recordings(first, false), second, recordings(second, false));
        Map<Path,byte[]> configuration = Map.of(first, Files.readAllBytes(database(first)), second, Files.readAllBytes(database(second)));
        Map<Path,byte[]> activity = Map.of(first, Files.readAllBytes(history(first)), second, Files.readAllBytes(history(second)));
        Map<Path,Object> historyIdentity = Map.of(first, identity(history(first)), second, identity(history(second)));
        CyclicBarrier bothPassedInitialAdmission = new CyclicBarrier(2);
        CountDownLatch claimed = new CountDownLatch(1), continueWinner = new CountDownLatch(1);
        AtomicReference<Path> winner = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<UpgradeAttempt>> futures = new ArrayList<>();
        try
        {
            for(Path source: List.of(first, second))
            {
                futures.add(executor.submit(() -> {
                    try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
                        if(phase.equals("CLAIMING")) await(bothPassedInitialAdmission);
                        if(phase.equals("PREPARING"))
                        {
                            winner.set(source);
                            claimed.countDown();
                            await(continueWinner);
                        }
                    }))
                    {
                        return new UpgradeAttempt(source,
                            VceUpgradeService.upgrade(source, target, MigrationContent.FULL, true, null, null), null);
                    }
                    catch(Exception failure) { return new UpgradeAttempt(source, null, failure); }
                }));
            }
            assertTrue(claimed.await(15, TimeUnit.SECONDS), "one source must claim the blank destination");
            Path winnerSource = winner.get(), loserSource = winnerSource.equals(first) ? second : first;
            int winnerIndex = winnerSource.equals(first) ? 0 : 1, loserIndex = 1 - winnerIndex;
            Path intent = intent(target), work = workspace(target);
            byte[] claimedIntent = Files.readAllBytes(intent);
            UpgradeAttempt loser = futures.get(loserIndex).get(15, TimeUnit.SECONDS);
            assertEquals(loserSource, loser.source());
            assertNull(loser.result());
            assertInstanceOf(IOException.class, loser.failure());
            assertArrayEquals(claimedIntent, Files.readAllBytes(intent), "losing claim must not overwrite or remove the winner's intent");
            assertTrue(Files.isDirectory(work), "losing cleanup must preserve the winner's private workspace");
            assertTrue(VceUpgradeService.isPending(target));
            assertFalse(Files.exists(target));
            assertArrayEquals(configuration.get(loserSource), Files.readAllBytes(database(loserSource)));
            assertArrayEquals(activity.get(loserSource), Files.readAllBytes(history(loserSource)));
            assertPayload(loserSource, files.get(loserSource));
            try(PortableDataRootLock ignored = PortableDataRootLock.acquire(loserSource)) {}
            try(var entries = Files.list(target.getParent()))
            {
                assertEquals(1, entries.filter(path -> path.getFileName().toString().startsWith(".data.migration-")).count(),
                    "only the winner's workspace may survive the losing claim");
            }

            continueWinner.countDown();
            UpgradeAttempt completed = futures.get(winnerIndex).get(20, TimeUnit.SECONDS);
            assertNull(completed.failure());
            assertNotNull(completed.result());
            try(PortableDataRootLock lock = completed.result().lock())
            {
                assertEquals(target, lock.getDataRoot());
                assertCurrentPair(target);
                assertPayload(target, files.get(winnerSource));
                assertEquals(historyIdentity.get(winnerSource), identity(history(target)));
                assertFalse(Files.exists(winnerSource));
                assertTrue(Files.isRegularFile(database(loserSource)));
                assertFalse(VceUpgradeService.isPending(target));
                assertThrows(IOException.class, () -> PortableDataRootLock.acquire(target));
            }
            try(var entries = Files.list(target.getParent()))
            {
                assertEquals(List.of(target), entries.sorted().toList(), "both claims must clean only their own temporary work");
            }
        }
        finally
        {
            continueWinner.countDown();
            executor.shutdown();
            if(!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow();
            for(Future<UpgradeAttempt> future: futures)
            {
                if(future.isDone() && !future.isCancelled())
                {
                    UpgradeAttempt attempt = future.get();
                    if(attempt.result() != null) attempt.result().lock().close();
                }
            }
        }
    }

    private static void await(CountDownLatch latch) throws IOException
    {
        try { if(!latch.await(15, TimeUnit.SECONDS)) throw new IOException("Concurrent upgrade fixture timed out"); }
        catch(InterruptedException interrupted)
        { Thread.currentThread().interrupt(); throw new IOException("Concurrent upgrade fixture interrupted", interrupted); }
    }

    private static void await(CyclicBarrier barrier) throws IOException
    {
        try { barrier.await(15, TimeUnit.SECONDS); }
        catch(InterruptedException interrupted)
        { Thread.currentThread().interrupt(); throw new IOException("Concurrent upgrade fixture interrupted", interrupted); }
        catch(java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failure)
        { throw new IOException("Concurrent upgrade fixture could not reach both claims", failure); }
    }

    private static Path intent(Path target)
    { return target.resolveSibling("." + target.getFileName() + ".vce-upgrade").resolve("intent.properties"); }

    private static Path workspace(Path target) throws IOException
    {
        Properties properties = new Properties();
        try(var input = Files.newInputStream(intent(target))) { properties.load(input); }
        return Path.of(properties.getProperty("work"));
    }

    private record UpgradeAttempt(Path source, VceUpgradeService.Result result, Exception failure) {}

    private void assertResumesSettingsCrash(String phase) throws Exception
    {
        Path source = root("crash-source-" + phase), target = root("crash-target-" + phase);
        Format64TestDatabase.create(database(source));
        Map<String,Object> files = recordings(source, false);
        try(AutoCloseable observer = VceUpgradeService.observePhases(reached -> {
            if(reached.equals(phase)) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.CONFIGURATION_ONLY, true, null, null), phase);
        }
        assertTrue(VceUpgradeService.isPending(target), phase);
        var result = VceUpgradeService.resume(target, null, null);
        try(PortableDataRootLock lock = result.lock())
        {
            assertEquals(target, lock.getDataRoot(), phase);
            assertCurrentPair(target);
            assertEquals(0, count(history(target), "receiver_activity_event"), phase);
            assertPayload(target, files);
            assertFalse(Files.exists(source), phase);
            assertFalse(VceUpgradeService.isPending(target), phase);
            assertThrows(IOException.class, () -> PortableDataRootLock.acquire(target));
        }
    }

    private Map<String,Object> recordings(Path root, boolean largeOpaqueFile) throws Exception
    {
        Path managed = Files.createDirectories(root.resolve("recordings-managed"));
        Files.write(managed.resolve("saved.wav"), new byte[]{1, 2, 3, 4});
        Path catalog = database(root).resolveSibling("managed-recordings.sqlite");
        try(var ignored = new ManagedRecordingCatalog(catalog, managed)) {}
        try(Connection connection = open(catalog); var query = connection.createStatement())
        {
            query.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "protocol,call_type,voice_type) VALUES(1,1000,2000,1000,'saved.wav',4,1,1,1)");
        }
        Files.createDirectories(root.resolve("recordings"));
        Files.writeString(root.resolve("recordings/classic.wav"), "classic");
        Files.createDirectories(root.resolve("vault"));
        Files.writeString(root.resolve("vault/opaque.bin"), "opaque vault state");
        Files.createDirectories(root.resolve("jmbe"));
        Files.writeString(root.resolve("jmbe/library.jar"), "installed library");
        List<String> names = new ArrayList<>(List.of("recordings-managed/saved.wav", "recordings/classic.wav",
            "database/managed-recordings.sqlite", "vault/opaque.bin", "jmbe/library.jar"));
        if(largeOpaqueFile)
        {
            Path opaque = root.resolve("recordings/large-opaque.dat");
            try(RandomAccessFile sparse = new RandomAccessFile(opaque.toFile(), "rw")) { sparse.setLength(512L * 1024 * 1024); }
            names.add("recordings/large-opaque.dat");
        }
        Map<String,Object> files = new LinkedHashMap<>();
        for(String name: names) files.put(name, identity(root.resolve(name)));
        return files;
    }

    private void assertPayload(Path root, Map<String,Object> files) throws Exception
    {
        for(var file: files.entrySet()) assertEquals(file.getValue(), identity(root.resolve(file.getKey())), file.getKey());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(root.resolve("recordings-managed/saved.wav")));
        assertEquals("classic", Files.readString(root.resolve("recordings/classic.wav")));
        try(Connection catalog = open(database(root).resolveSibling("managed-recordings.sqlite"));
            var query = catalog.createStatement(); var rows = query.executeQuery("SELECT relative_path FROM recording_call WHERE id=1"))
        {
            assertTrue(rows.next());
            assertEquals("saved.wav", rows.getString(1));
            assertTrue(Files.isRegularFile(root.resolve("recordings-managed").resolve(rows.getString(1))));
        }
        if(files.containsKey("recordings/large-opaque.dat"))
            assertEquals(512L * 1024 * 1024, Files.size(root.resolve("recordings/large-opaque.dat")));
    }

    private void setDirectories(Path root, Path external) throws Exception
    {
        try(Connection connection = open(database(root)))
        {
            ObjectNode preferences = JSON.createObjectNode();
            try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'");
                var rows = query.executeQuery())
            {
                if(rows.next()) preferences = (ObjectNode)JSON.readTree(rows.getString(1));
            }
            ObjectNode directories = preferences.withObject("/" + DIRECTORIES.replace("~", "~0").replace("/", "~1"));
            directories.put("directory.recording", root.resolve("recordings").toString());
            directories.put("directory.recording.managed", root.resolve("recordings-managed").toString());
            directories.put("directory.streaming", external.toString());
            try(var write = connection.prepareStatement("INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,1) " +
                "ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json"))
            {
                write.setString(1, "portable_java_preferences_v1"); write.setString(2, JSON.writeValueAsString(preferences));
                write.executeUpdate();
            }
        }
    }

    private String directory(Path root, String key) throws Exception
    {
        try(Connection connection = open(database(root));
            var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'");
            var rows = query.executeQuery())
        {
            assertTrue(rows.next());
            return JSON.readTree(rows.getString(1)).path(DIRECTORIES).path(key).asText();
        }
    }

    private void assertCurrentPair(Path root) throws Exception
    {
        ConfigurationHistoryDatabase.validatePair(database(root));
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database(root));
        try(Connection configuration = open(database(root))) { DatabaseFormatCatalog.requireCurrent(configuration); }
    }

    private Path root(String installation) throws IOException
    {
        return Files.createDirectories(temporary.resolve(installation)).resolve("data").toAbsolutePath().normalize();
    }

    private static Path database(Path root) { return SdrTrunkDatabasePath.getDatabasePath(root); }
    private static Path history(Path root) { return ActivityDatabasePath.forConfiguration(database(root)); }
    private static Connection open(Path file) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + file); }
    private static Object identity(Path file) throws IOException { return PortableFileIdentity.read(file); }
    private static long count(Path file, String table) throws Exception
    {
        try(Connection connection = open(file); var query = connection.createStatement();
            var rows = query.executeQuery("SELECT count(*) FROM \"" + table + "\""))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private static final class SimulatedCrash extends Error { }
}

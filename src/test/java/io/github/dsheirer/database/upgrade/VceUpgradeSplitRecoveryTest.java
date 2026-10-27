/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.portable.PortableDataRootLock;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class VceUpgradeSplitRecoveryTest
{
    @TempDir Path temporary;

    @Test
    void resumesEveryCommittedSplitRenameWithoutLosingActivityOrRepeatingItsTransaction() throws Exception
    {
        for(String phase: List.of("before-history-rename", "after-history-rename",
            "before-configuration-rename", "after-configuration-rename"))
        {
            InterruptedUpgrade interrupted = interrupt(phase);
            Path retained = retainedHistory(interrupted);
            assertEquals(interrupted.historyIdentity(), identity(retained));
            assertEquals(interrupted.events(), number(retained, "SELECT count(*) FROM receiver_activity_event"));
            addPostCommitProof(retained);

            var result = VceUpgradeService.resume(interrupted.target(), null, null);

            try(PortableDataRootLock ignored = result.lock())
            {
                assertCurrent(interrupted.target());
                assertEquals(interrupted.historyIdentity(), identity(history(interrupted.target())));
                assertEquals(interrupted.events(), number(history(interrupted.target()),
                    "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(1, number(history(interrupted.target()),
                    "SELECT count(*) FROM statistics_status WHERE key='split-recovery-proof' AND value='retained'"));
                assertEquals(interrupted.channels(), number(database(interrupted.target()),
                    "SELECT count(*) FROM configuration_channel"));
                assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(database(interrupted.target()))));
                assertFalse(Files.exists(ActivityDatabasePath.configurationStage(database(interrupted.target()))));
            }
        }
    }

    @Test
    void resumesAnOwnedCompleteCopyWithoutRequiringItsTemporarilyAbsentMainFile() throws Exception
    {
        Path source = source("copied-source"), target = Files.createDirectories(target("copied-target"));
        Format60TestDatabase.create(database(source));
        byte[] original = Files.readAllBytes(database(source));
        Path stage = target.resolve("database/.sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.createDirectories(stage.getParent());
        Files.copy(database(source), stage);
        String[] arguments = {stage.toString(), source.toString(), target.toString(), "--owned-complete-profile-copy"};
        try(AutoCloseable observer = Format62To63DatabaseMigration.observeActivation(phase -> {
            if(phase.equals("after-history-rename")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> ApplicationDatabaseMigrator.run(arguments, System.out, System.err));
        }
        assertFalse(Files.exists(stage));
        assertTrue(Files.isRegularFile(ActivityDatabasePath.splitReceipt(stage)));
        try(var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream())
        {
            assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS,
                ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error)), error::toString);
        }
        ConfigurationHistoryDatabase.validatePair(stage);
        assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(stage)));
        assertArrayEquals(original, Files.readAllBytes(database(source)));
    }

    @Test
    void refusesLinkedDatabaseDirectoryBeforeRecoveringItsSplit() throws Exception
    {
        InterruptedUpgrade interrupted = interrupt("after-history-rename");
        Path directory = database(interrupted.target()).getParent();
        Path outside = temporary.resolve("substituted-database-directory");
        Files.move(directory, outside);
        try { Files.createSymbolicLink(directory, outside); }
        catch(UnsupportedOperationException exception) { Assumptions.abort("Symbolic links unavailable"); }
        byte[] configuration = Files.readAllBytes(ActivityDatabasePath.configurationStage(database(interrupted.target())));
        byte[] activity = Files.readAllBytes(history(interrupted.target()));

        assertThrows(IOException.class, () -> VceUpgradeService.resume(interrupted.target(), null, null));

        assertTrue(Files.isSymbolicLink(directory));
        assertFalse(Files.exists(database(interrupted.target())));
        assertArrayEquals(configuration, Files.readAllBytes(ActivityDatabasePath.configurationStage(database(interrupted.target()))));
        assertArrayEquals(activity, Files.readAllBytes(history(interrupted.target())));
    }

    @Test
    void resumesAfterUpgradeCommitWithoutRepeatingMigrationOrDroppingPostCommitActivity() throws Exception
    {
        Path source = source("after-commit"), target = target("after-commit");
        Format60TestDatabase.create(database(source));
        long events = number(database(source), "SELECT count(*) FROM receiver_activity_event");
        Object original = identity(database(source));
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("COMMITTED")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.FULL, true, null, null));
        }
        assertTrue(VceUpgradeService.isPending(target));
        addPostCommitProof(history(target));

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertCurrent(target);
            assertEquals(original, identity(history(target)));
            assertEquals(events, number(history(target), "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, number(history(target), "SELECT count(*) FROM statistics_status WHERE key='split-recovery-proof'"));
        }
    }

    @Test
    void refusesMalformedSplitReceiptWithoutTouchingRetainedFiles() throws Exception
    {
        InterruptedUpgrade interrupted = interrupt("after-history-rename");
        Files.writeString(ActivityDatabasePath.splitReceipt(database(interrupted.target())), "pair_id=invalid\n");
        Map<Path,byte[]> before = transitionBytes(interrupted.target());

        IOException failure = assertThrows(IOException.class,
            () -> VceUpgradeService.resume(interrupted.target(), null, null));

        assertTrue(failure.getMessage().contains("transition receipt is invalid"), failure::getMessage);
        assertTransitionBytes(before);
        assertFalse(Files.exists(database(interrupted.target())));
        assertTrue(VceUpgradeService.isPending(interrupted.target()));
    }

    @Test
    void refusesLinkedTransitionArtifactsWithoutActivatingOrWritingThroughThem() throws Exception
    {
        for(String artifact: List.of("history", "configuration-stage", "receipt"))
        {
            InterruptedUpgrade interrupted = interrupt("after-history-rename");
            Path path = switch(artifact)
            {
                case "history" -> history(interrupted.target());
                case "configuration-stage" -> ActivityDatabasePath.configurationStage(database(interrupted.target()));
                default -> ActivityDatabasePath.splitReceipt(database(interrupted.target()));
            };
            Path outside = temporary.resolve(artifact + "-outside-" + System.nanoTime());
            Files.move(path, outside);
            try { Files.createSymbolicLink(path, outside); }
            catch(UnsupportedOperationException exception) { Assumptions.abort("Symbolic links unavailable"); }
            byte[] original = Files.readAllBytes(outside);
            Map<Path,byte[]> before = transitionBytes(interrupted.target());

            assertThrows(IOException.class, () -> VceUpgradeService.resume(interrupted.target(), null, null));

            assertTrue(Files.isSymbolicLink(path));
            assertArrayEquals(original, Files.readAllBytes(outside));
            assertTransitionBytes(before);
            assertFalse(Files.exists(database(interrupted.target())));
            assertTrue(VceUpgradeService.isPending(interrupted.target()));
        }
    }

    @Test
    void refusesHardLinkedRetainedHistoryBeforeActivatingConfiguration() throws Exception
    {
        InterruptedUpgrade interrupted = interrupt("after-history-rename");
        Path retained = history(interrupted.target());
        Path outside = temporary.resolve("hard-linked-history.sqlite");
        try { Files.createLink(outside, retained); }
        catch(UnsupportedOperationException exception) { Assumptions.abort("Hard links unavailable"); }
        Assumptions.assumeTrue(Files.getFileStore(retained).supportsFileAttributeView("unix"));
        Map<Path,byte[]> before = transitionBytes(interrupted.target());

        IOException failure = assertThrows(IOException.class,
            () -> VceUpgradeService.resume(interrupted.target(), null, null));

        assertTrue(failure.getMessage().contains("multiple filesystem links"), failure::getMessage);
        assertTransitionBytes(before);
        assertFalse(Files.exists(database(interrupted.target())));
    }

    @Test
    void refusesUnownedLockAndWrongSourceBeforeRecoveringTheSplit() throws Exception
    {
        InterruptedUpgrade interrupted = interrupt("after-history-rename");
        Path other = source("wrong-lock");
        Map<Path,byte[]> before = transitionBytes(interrupted.target());
        try(PortableDataRootLock wrong = PortableDataRootLock.acquire(other))
        {
            assertThrows(IOException.class, () -> ApplicationDatabaseMigrator.migrateTransferred(
                database(interrupted.target()), interrupted.source(), interrupted.target(), wrong));
        }
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(interrupted.target()))
        {
            assertThrows(IOException.class, () -> ApplicationDatabaseMigrator.migrateTransferred(
                database(interrupted.target()), other, interrupted.target(), lock));
        }
        assertTransitionBytes(before);
        assertFalse(Files.exists(database(interrupted.target())));
    }

    private InterruptedUpgrade interrupt(String phase) throws Exception
    {
        String name = phase + "-" + System.nanoTime();
        Path source = source(name), target = target(name);
        Format60TestDatabase.create(database(source));
        Object original = identity(database(source));
        long events = number(database(source), "SELECT count(*) FROM receiver_activity_event");
        long channels = number(database(source), "SELECT count(*) FROM configuration_channel");
        assertTrue(events > 0);
        try(AutoCloseable observer = Format62To63DatabaseMigration.observeActivation(actual -> {
            if(actual.equals(phase)) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.FULL, true, null, null));
        }
        assertFalse(Files.exists(source));
        assertTrue(VceUpgradeService.isPending(target));
        assertTrue(Files.isRegularFile(ActivityDatabasePath.splitReceipt(database(target))));
        return new InterruptedUpgrade(source, target, original, events, channels);
    }

    private static Path retainedHistory(InterruptedUpgrade upgrade)
    {
        return Files.exists(history(upgrade.target())) ? history(upgrade.target()) : database(upgrade.target());
    }

    private static void addPostCommitProof(Path retained) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + retained); var query = connection.createStatement())
        {
            query.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES('split-recovery-proof','retained',1)");
        }
    }

    private static Map<Path,byte[]> transitionBytes(Path target) throws IOException
    {
        Map<Path,byte[]> bytes = new LinkedHashMap<>();
        for(Path path: List.of(database(target), history(target), ActivityDatabasePath.configurationStage(database(target)),
            ActivityDatabasePath.splitReceipt(database(target))))
        {
            if(Files.isRegularFile(path)) bytes.put(path, Files.readAllBytes(path));
        }
        return bytes;
    }

    private static void assertTransitionBytes(Map<Path,byte[]> expected) throws IOException
    {
        for(var entry: expected.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().getFileName().toString());
    }

    private static void assertCurrent(Path target) throws Exception
    {
        ConfigurationHistoryDatabase.validatePair(database(target));
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database(target));
        assertFalse(VceUpgradeService.isPending(target));
    }

    private Path source(String name) throws IOException { return Files.createDirectories(temporary.resolve(name + "-old/data")); }
    private Path target(String name) throws IOException { return Files.createDirectories(temporary.resolve(name + "-new")).resolve("data"); }
    private static Path database(Path root) { return SdrTrunkDatabasePath.getDatabasePath(root); }
    private static Path history(Path root) { return ActivityDatabasePath.forConfiguration(database(root)); }
    private static Object identity(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class).fileKey(); }
    private static long number(Path path, String sql) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var query = connection.createStatement(); var rows = query.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private record InterruptedUpgrade(Path source, Path target, Object historyIdentity, long events, long channels) {}
    private static final class SimulatedCrash extends Error {}
}

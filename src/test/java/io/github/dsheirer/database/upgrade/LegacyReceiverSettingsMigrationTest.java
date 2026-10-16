/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the profile boundary: legacy documents are inputs to the bundled migrator, never startup repairs. */
class LegacyReceiverSettingsMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYNTHETIC_SECRET = "synthetic-legacy-credential-do-not-report";
    private static final String REMOTE_FILE = "remote-links/settings.json";
    private static final String PAGING_FILE = "audio-analysis/paging-tones.json";
    private static final String VOICE_FILE = "audio-models/selection.json";
    @TempDir Path directory;

    @Test void ownedFormat52ImportsAllLegacyChoicesAndKeepsTheOriginalDocuments() throws Exception
    {
        Path root = profile("owned", 52);
        Settings expected = legacyFiles(root, 2);
        Map<Path,byte[]> originals = originalFiles(root);

        var result = new ApplicationMigrationService().migrateCurrent(root, false, null);

        assertImported(root, expected);
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, version(root));
        assertOriginals(originals);
        assertFalse(result.helperOutput().contains(SYNTHETIC_SECRET));
        assertTrue(new ApplicationMigrationService().migrateCurrent(root, false, null).sourcePlan().steps().isEmpty());
        assertImported(root, expected);
        assertOriginals(originals);
    }

    @Test void folderImportReadsOnlyTheSelectedProfilesLegacyFilesAndFileImportIgnoresNeighbors() throws Exception
    {
        Path source = profile("source", 52);
        Settings expected = legacyFiles(source, 1);
        Map<Path,byte[]> originals = originalFiles(source);
        byte[] sourceDatabase = Files.readAllBytes(database(source));
        var service = ApplicationMigrationServiceTestSupport.createInProcess();

        Path folderTarget = directory.resolve("folder-target");
        var folder = PreviousBuildLocator.resolveSelection(source).orElseThrow();
        service.importPrevious(folder, folderTarget, null);
        assertImported(folderTarget, expected);
        assertFalse(Files.exists(folderTarget.resolve(".legacy-receiver-settings")));
        assertOriginals(originals);
        assertArrayEquals(sourceDatabase, Files.readAllBytes(database(source)));

        //A directly selected database has no authority to read even malformed neighboring profile documents.
        String malformedNeighbor = "{\"secret\":\"" + SYNTHETIC_SECRET + "\",broken";
        Files.writeString(source.resolve(REMOTE_FILE), malformedNeighbor);
        Path fileTarget = directory.resolve("file-target");
        var file = PreviousBuildLocator.resolveSelection(database(source)).orElseThrow();
        var result = service.importPrevious(file, fileTarget, null);
        assertDefaults(fileTarget);
        assertFalse(result.helperOutput().contains(SYNTHETIC_SECRET));
        assertArrayEquals(sourceDatabase, Files.readAllBytes(database(source)));
        assertEquals(malformedNeighbor, Files.readString(source.resolve(REMOTE_FILE)));
        for(var original: originals.entrySet())
            if(!original.getKey().equals(source.resolve(REMOTE_FILE)))
                assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey()));
    }

    @Test void format53DefaultsArePreservedUnlessRecoveryExplicitlySelectsThem() throws Exception
    {
        Path root = profile("defaulted", 53);
        Settings expected = legacyFiles(root, 2);
        Map<Path,byte[]> originals = originalFiles(root);

        new ApplicationMigrationService().migrateCurrent(root, false, null);
        assertDefaults(root);
        assertOriginals(originals);

        //Recovery can also run after the ordinary forward update, but only for explicitly reviewed default rows.
        var result = new ApplicationMigrationService().migrateCurrent(root, false,
            Map.of(ReceiverSettingsSchema.REMOTE_LINKS_KEY,
                ReceiverSettingsSchema.defaultJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY)), null);
        assertEquals(expected, new RemoteLinkSettingsStore(database(root)).load());
        assertAudio(root, true, false);
        assertFalse(result.helperOutput().contains(SYNTHETIC_SECRET));
        assertOriginals(originals);
    }

    @Test void laterDatabaseChoicesWinAndAChangedRecoveryGuardCannotOverwriteThem() throws Exception
    {
        Path root = profile("changed", 53);
        legacyFiles(root, 2);
        Settings newer = new Settings(42, false, "127.0.0.1", 53_802, List.of(), Outbound.disabled());
        ApplicationSettingsStore store = new ApplicationSettingsStore(database(root));
        store.save(ReceiverSettingsSchema.REMOTE_LINKS_KEY, newer);
        store.save(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.of("enabled", true));
        store.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, Map.of("enabled", false));
        Map<Path,byte[]> originals = originalFiles(root);

        new ApplicationMigrationService().migrateCurrent(root, false, null);
        assertEquals(newer, new RemoteLinkSettingsStore(database(root)).load());
        assertAudio(root, true, false);
        assertThrows(Exception.class, () -> new ApplicationMigrationService().migrateCurrent(root, false,
            Map.of(ReceiverSettingsSchema.REMOTE_LINKS_KEY,
                ReceiverSettingsSchema.defaultJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY)), null));
        assertEquals(newer, new RemoteLinkSettingsStore(database(root)).load());
        assertAudio(root, true, false);
        assertOriginals(originals);
    }

    @Test void failedCommitRollsBackTheFormatAndEveryImportedChoiceBeforeAnUnchangedRetry() throws Exception
    {
        Path root = profile("rollback", 52);
        Settings expected = legacyFiles(root, 2);
        Map<Path,byte[]> originals = originalFiles(root);
        assertThrows(CancellationException.class, () -> new ApplicationMigrationService().migrateCurrent(root,
            false, message -> {
                if(message.equals("Committing the database update"))
                    throw new CancellationException("Synthetic pre-commit cancellation");
            }));
        assertEquals(52, version(root));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database(root)))
        {
            assertEquals(3, ReceiverSettingsSchema.countMissing(connection));
        }
        assertOriginals(originals);
        new ApplicationMigrationService().migrateCurrent(root, false, null);
        assertImported(root, expected);
        assertOriginals(originals);
    }

    @Test void malformedOrOversizedLegacySettingsFailWithoutLeakingCredentialsOrChangingSource() throws Exception
    {
        for(boolean oversized: List.of(false, true))
        {
            Path root = profile("invalid-" + oversized, 52);
            legacyFiles(root, 2);
            String invalid = oversized ? " ".repeat(256_001) :
                "{\"secret\":\"" + SYNTHETIC_SECRET + "\",broken";
            Files.writeString(root.resolve(REMOTE_FILE), invalid);
            Map<Path,byte[]> originals = originalFiles(root);
            Exception failure = assertThrows(Exception.class,
                () -> new ApplicationMigrationService().migrateCurrent(root, false, null));
            for(Throwable cause = failure; cause != null; cause = cause.getCause())
                assertFalse(cause.toString().contains(SYNTHETIC_SECRET));
            assertEquals(52, version(root));
            assertOriginals(originals);
        }
    }

    @Test void symbolicLinkLegacySettingsAreRefusedWithoutFollowingOrReplacingThem() throws Exception
    {
        Path root = profile("symlink", 52);
        legacyFiles(root, 2);
        Path settings = root.resolve(REMOTE_FILE);
        Path outside = directory.resolve("outside-settings.json");
        Files.move(settings, outside);
        byte[] original = Files.readAllBytes(outside);
        try
        {
            Files.createSymbolicLink(settings, outside);
        }
        catch(UnsupportedOperationException | IOException | SecurityException failure)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable on this host");
        }

        Exception failure = assertThrows(Exception.class,
            () -> new ApplicationMigrationService().migrateCurrent(root, false, null));

        assertFalse(failure.toString().contains(SYNTHETIC_SECRET));
        assertEquals(52, version(root));
        assertTrue(Files.isSymbolicLink(settings));
        assertArrayEquals(original, Files.readAllBytes(outside));
    }

    @Test void folderApprovalRejectsRewrittenAddedOrRemovedLegacyDocumentsBeforePromotion() throws Exception
    {
        for(String change: List.of("rewritten", "added", "removed"))
        {
            Path source = profile("review-" + change, 52);
            legacyFiles(source, 2);
            Path altered = source.resolve(change.equals("added") ? VOICE_FILE : REMOTE_FILE);
            if(change.equals("added")) Files.delete(altered);
            var selection = PreviousBuildLocator.resolveSelection(source).orElseThrow();
            var approval = ApplicationMigrationService.readMigrationApproval(selection, directory);
            byte[] sourceDatabase = Files.readAllBytes(database(source));

            switch(change)
            {
                //Even a semantically identical rewrite changes the source bytes approved by the operator.
                case "rewritten" -> Files.writeString(altered,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(Files.readAllBytes(altered))));
                case "added" -> write(altered, Map.of("version", 2, "enabled", true));
                case "removed" -> Files.delete(altered);
                default -> throw new AssertionError(change);
            }
            Map<Path,byte[]> reviewedSource = new LinkedHashMap<>();
            for(String relative: List.of(REMOTE_FILE, PAGING_FILE, VOICE_FILE))
            {
                Path file = source.resolve(relative);
                if(Files.exists(file)) reviewedSource.put(file, Files.readAllBytes(file));
            }
            Path target = directory.resolve("rejected-" + change);

            IOException failure = assertThrows(IOException.class,
                () -> ApplicationMigrationServiceTestSupport.createInProcess()
                    .importPrevious(selection, target, approval, null));

            assertFalse(failure.toString().contains(SYNTHETIC_SECRET));
            assertFalse(Files.exists(target));
            assertArrayEquals(sourceDatabase, Files.readAllBytes(database(source)));
            assertOriginals(reviewedSource);
            if(change.equals("removed")) assertFalse(Files.exists(altered));
        }
    }

    @Test void folderSourceChangedAfterCaptureCannotBePromoted() throws Exception
    {
        Path source = profile("changed-during-import", 52);
        legacyFiles(source, 2);
        var selection = PreviousBuildLocator.resolveSelection(source).orElseThrow();
        var approval = ApplicationMigrationService.readMigrationApproval(selection, directory);
        Path target = directory.resolve("changed-during-import-target");
        byte[] sourceDatabase = Files.readAllBytes(database(source));

        IOException failure = assertThrows(IOException.class,
            () -> ApplicationMigrationServiceTestSupport.createInProcess().importPrevious(selection, target,
                approval, message -> {
                    if(message.equals("Updating database"))
                    {
                        try
                        {
                            write(source.resolve(PAGING_FILE), Map.of("version", 1, "enabled", true));
                        }
                        catch(Exception writeFailure) { throw new IllegalStateException(writeFailure); }
                    }
                }));

        assertFalse(failure.toString().contains(SYNTHETIC_SECRET));
        assertFalse(Files.exists(target));
        assertArrayEquals(sourceDatabase, Files.readAllBytes(database(source)));
        assertTrue(JSON.readTree(Files.readAllBytes(source.resolve(PAGING_FILE))).path("enabled").booleanValue());
    }

    @Test void anUnselectedMalformedLegacyFileCannotBlockIndependentRecoveryChoicesOrCurrentStartup() throws Exception
    {
        Path root = profile("independent-recovery", 53);
        legacyFiles(root, 2);
        new ApplicationMigrationService().migrateCurrent(root, false, null);
        String malformed = "{\"secret\":\"" + SYNTHETIC_SECRET + "\",broken";
        Files.writeString(root.resolve(REMOTE_FILE), malformed);
        Files.delete(root.resolve(VOICE_FILE));

        assertEquals(Set.of(ReceiverSettingsSchema.PAGING_TONES_KEY),
            ApplicationMigrationService.availableRecoveryKeys(root));
        assertTrue(new ApplicationMigrationService().migrateCurrent(root, false, null).sourcePlan().steps().isEmpty());
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database(root));
        assertDefaults(root);
        assertEquals(malformed, Files.readString(root.resolve(REMOTE_FILE)));
        assertFalse(Files.exists(root.resolve(VOICE_FILE)));
    }

    private Path profile(String name, int version) throws Exception
    {
        Path root = directory.resolve(name);
        Format52TestDatabase.create(database(root));
        if(version == 53)
        {
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database(root)))
            {
                connection.setAutoCommit(false);
                new Format52To53DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 53);
                connection.commit();
            }
        }
        assertEquals(version, version(root));
        return root;
    }

    private static Settings legacyFiles(Path root, int voiceVersion) throws Exception
    {
        String sender = "11111111-1111-4111-8111-111111111111";
        Settings settings = new Settings(9, true, "127.0.0.1", 53_800,
            List.of(new TrustedSender(sender, "Synthetic legacy receiver", SYNTHETIC_SECRET, false, null,
                123, false)),
            new Outbound(true, "127.0.0.1", 53_801, sender, SYNTHETIC_SECRET, List.of()));
        write(root.resolve(REMOTE_FILE), settings);
        write(root.resolve(PAGING_FILE), Map.of("version", 1, "enabled", false));
        write(root.resolve(VOICE_FILE), voiceVersion == 1 ?
            Map.of("version", 1, "enabled", true, "bundle_sha256", "a".repeat(64)) :
            Map.of("version", 2, "enabled", true));
        return settings;
    }

    private static void write(Path path, Object value) throws Exception
    {
        Files.createDirectories(path.getParent());
        Files.write(path, JSON.writeValueAsBytes(value));
    }

    private static void assertImported(Path root, Settings expected) throws Exception
    {
        assertEquals(expected, new RemoteLinkSettingsStore(database(root)).load());
        assertAudio(root, false, true);
    }

    private static void assertDefaults(Path root) throws Exception
    {
        assertEquals(Settings.defaults(), new RemoteLinkSettingsStore(database(root)).load());
        assertAudio(root, true, false);
    }

    private static void assertAudio(Path root, boolean paging, boolean voice) throws Exception
    {
        ApplicationSettingsStore store = new ApplicationSettingsStore(database(root));
        assertEquals(JSON.valueToTree(Map.of("enabled", paging)),
            JSON.readTree(store.loadJson(ReceiverSettingsSchema.PAGING_TONES_KEY, 256).orElseThrow()));
        assertEquals(JSON.valueToTree(Map.of("enabled", voice)),
            JSON.readTree(store.loadJson(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, 256).orElseThrow()));
    }

    private static Map<Path,byte[]> originalFiles(Path root) throws Exception
    {
        Map<Path,byte[]> files = new LinkedHashMap<>();
        for(String relative: List.of(REMOTE_FILE, PAGING_FILE, VOICE_FILE))
        {
            Path path = root.resolve(relative);
            files.put(path, Files.readAllBytes(path));
        }
        return files;
    }

    private static void assertOriginals(Map<Path,byte[]> originals) throws Exception
    {
        for(var original: originals.entrySet())
            assertArrayEquals(original.getValue(), Files.readAllBytes(original.getKey()));
    }

    private static int version(Path root) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database(root)))
        {
            return DatabaseFormatCatalog.inspect(connection).version();
        }
    }

    private static Path database(Path root) { return SdrTrunkDatabasePath.getDatabasePath(root); }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationReceiverSettingsTest
{
    @TempDir Path directory;

    @Test void folderAndDatabaseOnlyImportsCarryPairingAndAudioChoicesWithoutChangingSource() throws Exception
    {
        for(boolean folder: List.of(false, true))
        {
            Path source = profile("source-" + folder);
            Settings expected = configure(source);
            byte[] original = Files.readAllBytes(database(source));
            Path target = directory.resolve("target-" + folder);
            var selection = PreviousBuildLocator.resolveSelection(folder ? source : database(source)).orElseThrow();

            var result = service().importPrevious(selection, target, null);

            assertSettings(target, expected);
            assertArrayEquals(original, Files.readAllBytes(database(source)));
            assertEquals(folder, result.importedPreviousProfile());
            assertFalse(result.helperOutput().contains("synthetic-pairing-key"));
            try(var files = Files.list(target))
            {
                assertEquals(List.of("database"), files.map(path -> path.getFileName().toString()).sorted().toList());
            }
        }
    }

    @Test void databaseReplacementUsesTheSelectedDatabasePairingAndAudioChoices() throws Exception
    {
        Path source = profile("source");
        Settings expected = configure(source);
        Path target = profile("target");
        configure(target);
        ApplicationSettingsStore targetStore = new ApplicationSettingsStore(database(target));
        targetStore.save(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.of("enabled", true));
        targetStore.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, Map.of("enabled", false));
        byte[] original = Files.readAllBytes(database(source));

        service().replaceCurrentDatabase(database(source), target, null);

        assertSettings(target, expected);
        assertArrayEquals(original, Files.readAllBytes(database(source)));
    }

    @Test void failedPromotionDiscardsStagedDatabaseAndPreservesSourcePairing() throws Exception
    {
        Path source = profile("source");
        Settings expected = configure(source);
        byte[] original = Files.readAllBytes(database(source));
        Path target = directory.resolve("target");
        var failing = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess, (stage, destination) -> {
                try
                {
                    assertSettings(stage, expected);
                }
                catch(SQLException failure)
                {
                    throw new IOException("Could not inspect staged synthetic settings", failure);
                }
                throw new IOException("Synthetic promotion failure");
            });

        assertThrows(IOException.class, () -> failing.importPrevious(source, target, null));

        assertArrayEquals(original, Files.readAllBytes(database(source)));
        assertFalse(Files.exists(target));
        try(var files = Files.list(directory))
        {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".migration-")));
        }
    }

    private Path profile(String name) throws Exception
    {
        Path root = directory.resolve(name);
        SdrTrunkTestDatabase.create(database(root));
        return root;
    }

    private static Settings configure(Path root) throws Exception
    {
        String sender = UUID.randomUUID().toString();
        Settings settings = new Settings(9, true, "127.0.0.1", 53_800,
            List.of(new TrustedSender(sender, "Synthetic receiver", "synthetic-pairing-key", false, null, 123, false)),
            new Outbound(true, "127.0.0.1", 53_801, sender, "synthetic-pairing-key",
                List.of(UUID.randomUUID().toString())));
        ApplicationSettingsStore store = new ApplicationSettingsStore(database(root));
        store.save(ReceiverSettingsSchema.REMOTE_LINKS_KEY, settings);
        store.save(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.of("enabled", false));
        store.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, Map.of("enabled", true));
        return settings;
    }

    private static void assertSettings(Path root, Settings expected) throws IOException, SQLException
    {
        ApplicationSettingsStore store = new ApplicationSettingsStore(database(root));
        assertEquals(expected, store.load(ReceiverSettingsSchema.REMOTE_LINKS_KEY, Settings.class).orElseThrow());
        assertEquals(Map.of("enabled", false), store.load(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.class).orElseThrow());
        assertEquals(Map.of("enabled", true), store.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, Map.class).orElseThrow());
    }

    private static ApplicationMigrationService service()
    {
        return ApplicationMigrationServiceTestSupport.createInProcess();
    }

    private static Path database(Path root) { return SdrTrunkDatabasePath.getDatabasePath(root); }
}

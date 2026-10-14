/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationRemoteLinksTest
{
    private static final String SYNTHETIC_SECRET = "synthetic-remote-pairing-key";

    @TempDir
    Path mTemp;

    @Test
    void folderImportPreservesPairingBytesAndSourcePermissions() throws Exception
    {
        Path source = profile("source");
        Settings expected = settings();
        new RemoteLinkSettingsStore(source).save(expected);
        Path sourceSettings = settingsFile(source);
        //Retain unknown fields opaquely as well as every currently understood credential and identity.
        String contents = Files.readString(sourceSettings);
        Files.writeString(sourceSettings, "{\"future_setting\":true," + contents.substring(1));
        PosixFileAttributeView posix = Files.getFileAttributeView(sourceSettings, PosixFileAttributeView.class);
        if(posix != null)
        {
            Files.setPosixFilePermissions(sourceSettings, PosixFilePermissions.fromString("r--r-----"));
            Files.setPosixFilePermissions(sourceSettings.getParent(), PosixFilePermissions.fromString("rwxr-x---"));
        }
        byte[] sourceBytes = Files.readAllBytes(sourceSettings);
        byte[] databaseBytes = Files.readAllBytes(database(source));
        Map<String,Object> fileAccess = access(sourceSettings);
        Map<String,Object> directoryAccess = access(sourceSettings.getParent());
        Path target = mTemp.resolve("target");
        List<String> progress = new ArrayList<>();

        var result = service().importPrevious(source, target, progress::add);

        assertPrivate(settingsFile(target));
        assertEquals(expected, new RemoteLinkSettingsStore(target).load());
        assertArrayEquals(sourceBytes, Files.readAllBytes(settingsFile(target)));
        assertArrayEquals(sourceBytes, Files.readAllBytes(sourceSettings));
        assertArrayEquals(databaseBytes, Files.readAllBytes(database(source)));
        assertEquals(fileAccess, access(sourceSettings));
        assertEquals(directoryAccess, access(sourceSettings.getParent()));
        assertTrue(result.helperOutput().contains("- Remote Links settings: copied."));
        assertFalse(result.helperOutput().contains(SYNTHETIC_SECRET));
        assertFalse(String.join("\n", progress).contains(SYNTHETIC_SECRET));
        assertNoStages();
    }

    @Test
    void folderImportWithoutPairingDoesNotCreateDefaultSettings() throws Exception
    {
        Path source = profile("source");
        Path target = mTemp.resolve("target");

        var result = service().importPrevious(source, target, null);

        assertFalse(Files.exists(settingsFile(target)));
        assertTrue(result.helperOutput().contains("- Remote Links settings: not present in the selected profile."));
    }

    @Test
    void malformedPairingRefusesImportWithoutCredentialsOrPartialDestination() throws Exception
    {
        Path source = profile("source");
        Path settings = settingsFile(source);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"outbound\":{\"secret\":{\"" + SYNTHETIC_SECRET + "\":1}}}");
        byte[] original = Files.readAllBytes(settings);
        List<String> progress = new ArrayList<>();
        Path target = mTemp.resolve("target");

        IOException error = assertThrows(IOException.class,
            () -> service().importPrevious(source, target, progress::add));

        assertTrue(error.getMessage().contains("Remote Links settings could not be copied safely"));
        assertFalse(error.toString().contains(SYNTHETIC_SECRET));
        assertFalse(String.join("\n", progress).contains(SYNTHETIC_SECRET));
        assertNull(error.getCause());
        assertArrayEquals(original, Files.readAllBytes(settings));
        assertFalse(Files.exists(target));
        assertNoStages();
    }

    @Test
    void oversizedPairingRefusesImport() throws Exception
    {
        Path source = profile("source");
        Path settings = settingsFile(source);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, " ".repeat(256_001));
        Path target = mTemp.resolve("target");

        assertThrows(IOException.class, () -> service().importPrevious(source, target, null));

        assertFalse(Files.exists(target));
        assertEquals(256_001, Files.size(settings));
        assertNoStages();
    }

    @Test
    void linkedPairingFileRefusesImport() throws Exception
    {
        assertLinkedPairingRefused(false);
    }

    @Test
    void linkedPairingDirectoryRefusesImport() throws Exception
    {
        assertLinkedPairingRefused(true);
    }

    private void assertLinkedPairingRefused(boolean linkDirectory) throws Exception
    {
        Path source = profile("source");
        Path outside = Files.createDirectory(mTemp.resolve("outside"));
        new RemoteLinkSettingsStore(outside).save(settings());
        byte[] original = Files.readAllBytes(settingsFile(outside));
        try
        {
            if(linkDirectory)
            {
                Files.createSymbolicLink(source.resolve("remote-links"), outside.resolve("remote-links"));
            }
            else
            {
                Files.createDirectories(settingsFile(source).getParent());
                Files.createSymbolicLink(settingsFile(source), settingsFile(outside));
            }
        }
        catch(IOException | UnsupportedOperationException | SecurityException unavailable)
        {
            Assumptions.abort("Symbolic links are unavailable in this environment");
        }
        Path target = mTemp.resolve("target");

        assertThrows(IOException.class, () -> service().importPrevious(source, target, null));

        assertArrayEquals(original, Files.readAllBytes(settingsFile(outside)));
        assertFalse(Files.exists(target));
        assertNoStages();
    }

    @Test
    void unreadablePairingRefusesImportInsteadOfTreatingItAsMissing() throws Exception
    {
        Path source = profile("source");
        new RemoteLinkSettingsStore(source).save(settings());
        Path settings = settingsFile(source);
        PosixFileAttributeView view = Files.getFileAttributeView(settings, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX permission controls are unavailable");
        var permissions = view.readAttributes().permissions();
        try
        {
            view.setPermissions(PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(settings), "This account bypasses file permissions");
            Path target = mTemp.resolve("target");

            assertThrows(IOException.class, () -> service().importPrevious(source, target, null));

            assertFalse(Files.exists(target));
            assertEquals(PosixFilePermissions.fromString("---------"), view.readAttributes().permissions());
            assertNoStages();
        }
        finally
        {
            view.setPermissions(permissions);
        }
    }

    @Test
    void unsearchablePairingDirectoryRefusesImportInsteadOfTreatingItAsMissing() throws Exception
    {
        Path source = profile("source");
        new RemoteLinkSettingsStore(source).save(settings());
        Path directory = settingsFile(source).getParent();
        PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX permission controls are unavailable");
        var permissions = view.readAttributes().permissions();
        try
        {
            view.setPermissions(PosixFilePermissions.fromString("r--------"));
            Assumptions.assumeFalse(Files.isReadable(settingsFile(source)),
                "This account bypasses directory search permissions");
            Path target = mTemp.resolve("target");

            assertThrows(IOException.class, () -> service().importPrevious(source, target, null));

            assertFalse(Files.exists(target));
            assertEquals(PosixFilePermissions.fromString("r--------"), view.readAttributes().permissions());
            assertNoStages();
        }
        finally
        {
            view.setPermissions(permissions);
        }
    }

    @Test
    void databaseFileImportDoesNotReadOrCopyNeighboringPairing() throws Exception
    {
        Path source = profile("source");
        Files.createDirectories(settingsFile(source).getParent());
        Files.writeString(settingsFile(source), "invalid " + SYNTHETIC_SECRET);
        Path target = mTemp.resolve("target");
        var selection = PreviousBuildLocator.resolveSelection(database(source)).orElseThrow();

        var result = service().importPrevious(selection, target, null);

        assertEquals(PreviousBuildLocator.InputScope.DATABASE_FILE, result.inputScope());
        assertFalse(Files.exists(settingsFile(target)));
        assertEquals("invalid " + SYNTHETIC_SECRET, Files.readString(settingsFile(source)));
        assertFalse(result.helperOutput().contains("Remote Links settings"));
    }

    @Test
    void databaseReplacementKeepsDestinationPairing() throws Exception
    {
        Path source = profile("source");
        Path target = profile("target");
        new RemoteLinkSettingsStore(source).save(settings());
        Settings retained = settings();
        new RemoteLinkSettingsStore(target).save(retained);
        byte[] originalSource = Files.readAllBytes(settingsFile(source));
        byte[] originalTarget = Files.readAllBytes(settingsFile(target));

        service().replaceCurrentDatabase(database(source), target, null);

        assertArrayEquals(originalSource, Files.readAllBytes(settingsFile(source)));
        assertArrayEquals(originalTarget, Files.readAllBytes(settingsFile(target)));
        assertEquals(retained, new RemoteLinkSettingsStore(target).load());
    }

    @Test
    void failedPromotionDiscardsStagedPairingAndPreservesSource() throws Exception
    {
        Path source = profile("source");
        new RemoteLinkSettingsStore(source).save(settings());
        byte[] original = Files.readAllBytes(settingsFile(source));
        Path target = mTemp.resolve("target");
        ApplicationMigrationService failing = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess, (stage, destination) -> {
                assertArrayEquals(original, Files.readAllBytes(settingsFile(stage)));
                assertPrivate(settingsFile(stage));
                throw new IOException("Synthetic promotion failure");
            });

        assertThrows(IOException.class, () -> failing.importPrevious(source, target, null));

        assertArrayEquals(original, Files.readAllBytes(settingsFile(source)));
        assertFalse(Files.exists(target));
        assertNoStages();
    }

    private Path profile(String name) throws Exception
    {
        Path root = mTemp.resolve(name);
        SdrTrunkTestDatabase.create(database(root));
        return root;
    }

    private static Settings settings()
    {
        String senderId = UUID.randomUUID().toString();
        return new Settings(9, true, "127.0.0.1", 53_800,
            List.of(new TrustedSender(senderId, "Synthetic receiver", SYNTHETIC_SECRET, false, null, 123, false),
                new TrustedSender(UUID.randomUUID().toString(), "Revoked receiver", null, false, null, 456, true)),
            new Outbound(true, "127.0.0.1", 53_801, senderId, SYNTHETIC_SECRET,
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString())));
    }

    private static ApplicationMigrationService service()
    {
        return ApplicationMigrationServiceTestSupport.createInProcess();
    }

    private static Path database(Path root)
    {
        return SdrTrunkDatabasePath.getDatabasePath(root);
    }

    private static Path settingsFile(Path root)
    {
        return root.resolve("remote-links/settings.json");
    }

    private static Map<String,Object> access(Path path) throws IOException
    {
        Map<String,Object> result = new HashMap<>();
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if(posix != null) result.put("posix", posix.readAttributes().permissions());
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if(acl != null) result.put("acl", acl.getAcl());
        return result;
    }

    private static void assertPrivate(Path file) throws IOException
    {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if(posix != null)
        {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(file.getParent()));
        }
        for(Path path: List.of(file, file.getParent()))
        {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if(acl != null)
            {
                assertFalse(acl.getAcl().isEmpty());
                var owner = acl.getOwner();
                assertTrue(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)));
            }
        }
    }

    private void assertNoStages() throws IOException
    {
        try(var paths = Files.list(mTemp))
        {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains(".migration-")));
        }
    }
}

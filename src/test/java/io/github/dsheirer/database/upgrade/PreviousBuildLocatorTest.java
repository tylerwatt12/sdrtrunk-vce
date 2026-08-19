/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviousBuildLocatorTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void resolvesInstallDataApplicationAndDatabaseSelections() throws Exception
    {
        Path installRoot = mTemporaryFolder.resolve("releases/sdrtrunk-vce-alpha-5");
        Path dataRoot = createPortableDataRoot(installRoot.resolve("data"));
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

        assertEquals(dataRoot, PreviousBuildLocator.resolveSelection(installRoot).orElseThrow());
        assertEquals(dataRoot, PreviousBuildLocator.resolveSelection(dataRoot).orElseThrow());
        assertEquals(dataRoot, PreviousBuildLocator.resolveSelection(database).orElseThrow());

        Path application = Files.createDirectories(mTemporaryFolder.resolve("Applications/Receiver Alpha 5.app"));
        Path applicationData = createPortableDataRoot(application.resolveSibling("Receiver Alpha 5-data"));
        assertEquals(applicationData, PreviousBuildLocator.resolveSelection(application).orElseThrow());
    }

    @Test
    void rejectsSelectionsThatDoNotIdentifyPortableData() throws Exception
    {
        Path emptyInstall = Files.createDirectories(mTemporaryFolder.resolve("empty-install"));
        Path arbitraryDatabase = mTemporaryFolder.resolve("database/other.sqlite");
        Files.createDirectories(arbitraryDatabase.getParent());
        Files.createFile(arbitraryDatabase);

        assertTrue(PreviousBuildLocator.resolveSelection(null).isEmpty());
        assertTrue(PreviousBuildLocator.resolveSelection(emptyInstall).isEmpty());
        assertTrue(PreviousBuildLocator.resolveSelection(arbitraryDatabase).isEmpty());
    }

    @Test
    void discoversOnlyAdjacentPortableBuildsAndExcludesCurrentData() throws Exception
    {
        Path releases = Files.createDirectories(mTemporaryFolder.resolve("releases"));
        Path currentInstall = Files.createDirectories(releases.resolve("sdrtrunk-vce-alpha-6"));
        Path currentData = createPortableDataRoot(currentInstall.resolve("data"));
        Path priorInstallData = createPortableDataRoot(releases.resolve("sdrtrunk-vce-alpha-5/data"));
        Path adjacentData = createPortableDataRoot(releases.resolve("receiver-alpha-4-data"));
        createPortableDataRoot(releases.resolve("archive/old/sdrtrunk-vce-alpha-3/data"));

        assertEquals(List.of(adjacentData, priorInstallData),
            PreviousBuildLocator.discover(currentInstall, currentData));
    }

    @Test
    void consoleInstallDiscoversLegacyMacDataOnceThroughApplicationAndDataSiblings() throws Exception
    {
        Path applications = Files.createDirectories(mTemporaryFolder.resolve("Applications"));
        Path currentInstall = Files.createDirectories(applications.resolve("sdrtrunk-vce"));
        Path currentData = createPortableDataRoot(currentInstall.resolve("data"));
        Files.createDirectories(applications.resolve("sdrtrunk-vce Alpha 5.app"));
        Path previousData = createPortableDataRoot(applications.resolve("sdrtrunk-vce Alpha 5-data"));

        assertEquals(List.of(previousData), PreviousBuildLocator.discover(currentInstall, currentData));
    }

    @Test
    void normalizesEquivalentInputPaths() throws Exception
    {
        Path installRoot = Files.createDirectories(mTemporaryFolder.resolve("release"));
        Path dataRoot = createPortableDataRoot(installRoot.resolve("data"));
        Path unnormalized = installRoot.resolve("unused/../data");

        assertEquals(dataRoot, PreviousBuildLocator.resolveSelection(unnormalized).orElseThrow());
    }

    @Test
    void ignoresCurrentAndLegacyInternalMigrationStagingDirectories() throws Exception
    {
        Path releases = Files.createDirectories(mTemporaryFolder.resolve("releases"));
        Path currentInstall = Files.createDirectories(releases.resolve("sdrtrunk-vce-alpha-6"));
        Path currentData = createPortableDataRoot(currentInstall.resolve("data"));
        Path staleStage = createPortableDataRoot(releases.resolve(
            ".sdrtrunk-vce-alpha-6-data.upgrade-12345678-1234-1234-1234-123456789abc"));
        Path currentStage = createPortableDataRoot(releases.resolve(
            ".sdrtrunk-vce-alpha-6-data.migration-12345678-1234-1234-1234-123456789abc"));

        assertTrue(PreviousBuildLocator.resolveSelection(staleStage).isEmpty());
        assertTrue(PreviousBuildLocator.resolveSelection(SdrTrunkDatabasePath.getDatabasePath(staleStage)).isEmpty());
        assertTrue(PreviousBuildLocator.resolveSelection(currentStage).isEmpty());
        assertTrue(PreviousBuildLocator.resolveSelection(SdrTrunkDatabasePath.getDatabasePath(currentStage)).isEmpty());
        assertEquals(List.of(), PreviousBuildLocator.discover(currentInstall, currentData));
    }

    private static Path createPortableDataRoot(Path dataRoot) throws Exception
    {
        Path normalized = dataRoot.toAbsolutePath().normalize();
        Path database = SdrTrunkDatabasePath.getDatabasePath(normalized);
        Files.createDirectories(database.getParent());
        Files.createFile(database);
        return normalized.toRealPath();
    }
}

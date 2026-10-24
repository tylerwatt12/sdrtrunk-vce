package io.github.dsheirer.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationLogTest
{
    @TempDir Path directory;

    @Test
    void readsBuildInformationWithCurrentPackageTitle() throws Exception
    {
        Path manifest = writeManifest("VCE project", "Implementation-Version: local-dev-test\n" +
            "Build-Timestamp: 2026-10-24T12:00:00Z\n");

        Attributes attributes = ApplicationLog.readManifestAttributes(manifest.toUri().toURL());

        assertNotNull(attributes);
        assertEquals("local-dev-test", attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        assertEquals("2026-10-24T12:00:00Z", attributes.getValue("Build-Timestamp"));
    }

    @Test
    void stillRecognizesLegacyPackageTitle() throws Exception
    {
        Path manifest = writeManifest("sdrtrunk-vce project", "Implementation-Version: legacy\n");

        assertNotNull(ApplicationLog.readManifestAttributes(manifest.toUri().toURL()));
    }

    @Test
    void doesNotMistakeDependencyMetadataForApplicationTitle() throws Exception
    {
        Path manifest = writeManifest("Dependency", "Implementation-Vendor: sdrtrunk-vce project\n");

        assertNull(ApplicationLog.readManifestAttributes(manifest.toUri().toURL()));
    }

    @Test
    void missingManifestLeavesBuildInformationUnavailable() throws Exception
    {
        assertNull(ApplicationLog.readManifestAttributes(directory.resolve("missing.mf").toUri().toURL()));
    }

    private Path writeManifest(String title, String additionalAttributes) throws Exception
    {
        return Files.writeString(directory.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\nImplementation-Title: " + title + "\n" + additionalAttributes + "\n");
    }
}

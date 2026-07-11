package io.github.dsheirer.jmbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.jmbe.github.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmbeLibraryMetadataTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void readsAndValidatesEmbeddedVersion() throws Exception
    {
        Path library = createLibrary("1.0.12", true);
        assertEquals(Version.fromString("1.0.12"), JmbeLibraryMetadata.getVersion(library));
        JmbeLibraryMetadata.verify(library, Version.fromString("1.0.12"));
    }

    @Test
    void rejectsWrongVersionAndMissingEntryPoint() throws Exception
    {
        Path wrongVersion = createLibrary("1.0.11", true);
        assertThrows(IOException.class,
            () -> JmbeLibraryMetadata.verify(wrongVersion, Version.fromString("1.0.12")));
        Path missingEntryPoint = createLibrary("1.0.12", false);
        assertThrows(IOException.class,
            () -> JmbeLibraryMetadata.verify(missingEntryPoint, Version.fromString("1.0.12")));
    }

    private Path createLibrary(String version, boolean includeEntryPoint) throws IOException
    {
        Path library = mTemporaryDirectory.resolve("jmbe-" + version + "-" + includeEntryPoint + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Version", version);

        try(JarOutputStream output = new JarOutputStream(Files.newOutputStream(library), manifest))
        {
            if(includeEntryPoint)
            {
                output.putNextEntry(new JarEntry("jmbe/JMBEAudioLibrary.class"));
                output.write(0);
                output.closeEntry();
            }
        }

        return library;
    }
}

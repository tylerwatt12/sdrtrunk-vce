package io.github.dsheirer.preference.decoder;

import io.github.dsheirer.jmbe.github.Version;
import io.github.dsheirer.portable.PortableApplicationPaths;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.prefs.AbstractPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JmbeLibraryPreferenceInstallTest
{
    @TempDir Path directory;
    private Field dataRootField;
    private Object originalDataRoot;
    private final MemoryPreferences stored = new MemoryPreferences(null, "");
    private final AtomicInteger notifications = new AtomicInteger();
    private JmbeLibraryPreference preference;

    @BeforeEach void isolatePortableDirectory() throws Exception
    {
        dataRootField = PortableApplicationPaths.class.getDeclaredField("sDataRoot");
        dataRootField.setAccessible(true);
        originalDataRoot = dataRootField.get(null);
        dataRootField.set(null, directory.resolve("data"));
        preference = new JmbeLibraryPreference(ignored -> notifications.incrementAndGet(), stored);
    }

    @AfterEach void restorePortableDirectory() throws Exception
    {
        dataRootField.set(null, originalDataRoot);
    }

    @Test void selectsNewerSameNamedJarWithoutResetAndPreservesTheLoadedJar() throws Exception
    {
        Path old = library("old/jmbe.jar", "1.0.14", 14);
        preference.installLibrary(old);
        Path loaded = preference.getPathJmbeLibrary();
        byte[] original = Files.readAllBytes(loaded);

        Path updated = library("new/jmbe.jar", "1.0.15", 15);
        preference.installLibrary(updated);

        assertNotEquals(loaded, preference.getPathJmbeLibrary());
        assertArrayEquals(original, Files.readAllBytes(loaded));
        assertArrayEquals(Files.readAllBytes(updated), Files.readAllBytes(preference.getPathJmbeLibrary()));
        assertEquals(Version.fromString("1.0.15"), preference.getCurrentVersion());
        assertEquals(preference.getPathJmbeLibrary(), new JmbeLibraryPreference(null, stored).getPathJmbeLibrary());
        assertEquals(2, notifications.get());
    }

    @Test void sameVersionDifferentContentsSelectsANewFileWithoutReplacingTheLoadedJar() throws Exception
    {
        preference.installLibrary(library("old/jmbe.jar", "1.0.15", 1));
        Path loaded = preference.getPathJmbeLibrary();
        byte[] original = Files.readAllBytes(loaded);
        preference.installLibrary(library("new/jmbe.jar", "1.0.15", 2));
        assertNotEquals(loaded, preference.getPathJmbeLibrary());
        assertArrayEquals(original, Files.readAllBytes(loaded));
    }

    @Test void invalidSelectionLeavesWorkingJarAndPreferenceUnchanged() throws Exception
    {
        preference.installLibrary(library("old/jmbe.jar", "1.0.14", 14));
        Path selected = preference.getPathJmbeLibrary();
        byte[] original = Files.readAllBytes(selected);
        Path invalid = directory.resolve("invalid.jar");
        Files.writeString(invalid, "not a JMBE library");
        assertThrows(IOException.class, () -> preference.installLibrary(invalid));
        assertEquals(selected, preference.getPathJmbeLibrary());
        assertEquals(selected, new JmbeLibraryPreference(null, stored).getPathJmbeLibrary());
        assertArrayEquals(original, Files.readAllBytes(selected));
        assertEquals(1, notifications.get());
    }

    @Test void storageFailureLeavesWorkingSelectionUnchanged() throws Exception
    {
        preference.installLibrary(library("old/jmbe.jar", "1.0.14", 14));
        Path selected = preference.getPathJmbeLibrary();
        Path updated = library("new/blocked.jar", "1.0.15", 15);
        Files.createDirectory(directory.resolve("data/jmbe/blocked.jar"));
        assertThrows(IOException.class, () -> preference.installLibrary(updated));
        assertEquals(selected, preference.getPathJmbeLibrary());
        assertEquals(Version.fromString("1.0.14"), preference.getCurrentVersion());
        assertEquals(1, notifications.get());
    }

    @Test void reselectingIdenticalContentsDoesNotRewriteTheLoadedJar() throws Exception
    {
        Path source = library("source/jmbe.jar", "1.0.15", 15);
        preference.installLibrary(source);
        Path selected = preference.getPathJmbeLibrary();
        var modified = Files.getLastModifiedTime(selected);
        preference.installLibrary(source);
        assertEquals(selected, preference.getPathJmbeLibrary());
        assertEquals(modified, Files.getLastModifiedTime(selected));
        assertEquals(2, notifications.get());
        try(var files = Files.list(selected.getParent())) { assertEquals(1, files.count()); }
    }

    @Test void preferenceWriteFailureLeavesWorkingSelectionUnchanged() throws Exception
    {
        preference.installLibrary(library("old/jmbe.jar", "1.0.14", 14));
        Path selected = preference.getPathJmbeLibrary();
        Path updated = library("new/jmbe.jar", "1.0.15", 15);
        stored.failWrites = true;
        assertThrows(IllegalStateException.class, () -> preference.installLibrary(updated));
        assertEquals(selected, preference.getPathJmbeLibrary());
        assertEquals(selected, new JmbeLibraryPreference(null, stored).getPathJmbeLibrary());
        assertEquals(1, notifications.get());
    }

    private Path library(String name, String version, int contents) throws IOException
    {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Version", version);
        try(var jar = new JarOutputStream(Files.newOutputStream(path), manifest))
        {
            jar.putNextEntry(new JarEntry("jmbe/JMBEAudioLibrary.class"));
            jar.write(contents);
            jar.closeEntry();
        }
        return path;
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String, String> values = new HashMap<>();
        private boolean failWrites;
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value)
        {
            if(failWrites) throw new IllegalStateException("Test preference storage failure");
            values.put(key, value);
        }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
}

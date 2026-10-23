package io.github.dsheirer.preference.decoder;

import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceDecryptionModule;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorProvider;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.prefs.AbstractPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

public class VoiceDecryptionModulePreferenceTest
{
    private static final String PATH_KEY = "path.voice.decryption.module.1";
    private static final String LEGACY_REQUEST_ID_KEY = "voice.module.request.id.1";
    private static final String LEGACY_MODULE_KEY = "voice.module.key.1";
    @TempDir Path directory;
    private Field dataRootField;
    private Object originalDataRoot;
    private final MemoryPreferences stored = new MemoryPreferences(null, "");
    private final AtomicInteger notifications = new AtomicInteger();
    private final List<VoiceDecryptionModulePreference> openedPreferences = new ArrayList<>();
    private VoiceDecryptionModulePreference preference;

    @BeforeEach void isolatePortableDirectory() throws Exception
    {
        dataRootField = PortableApplicationPaths.class.getDeclaredField("sDataRoot");
        dataRootField.setAccessible(true);
        originalDataRoot = dataRootField.get(null);
        dataRootField.set(null, directory.resolve("data"));
        preference = openPreference();
    }

    @AfterEach void restorePortableDirectory() throws Exception
    {
        for(VoiceDecryptionModulePreference opened: openedPreferences)
        {
            opened.getModuleManager().close();
        }

        dataRootField.set(null, originalDataRoot);
    }

    @Test void selectionLoadsImmediatelyAndRestoresOnRestartWithoutActivation() throws Exception
    {
        Path source = module("source/module.jar", TestModule.class);

        assertTrue(preference.setPath(source), preference.getModuleManager().getStatus());

        Path selected = directory.resolve("data/modules/module.jar");
        assertEquals(selected, preference.getPath());
        assertTrue(preference.getModuleManager().isLoaded());
        assertTrue(preference.getModuleManager().supports(VoiceEncryptionAlgorithm.APCO25_AES_256));
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(selected));
        assertEquals(Path.of("modules", "module.jar").toString(), stored.get(PATH_KEY, null));
        assertNull(stored.get(LEGACY_REQUEST_ID_KEY, null));
        assertNull(stored.get(LEGACY_MODULE_KEY, null));
        assertEquals(1, notifications.get());

        preference.getModuleManager().close();
        VoiceDecryptionModulePreference restarted = openPreference();

        assertEquals(selected, restarted.getPath());
        assertTrue(restarted.getModuleManager().isLoaded(), restarted.getModuleManager().getStatus());
        assertEquals("Test Module", restarted.getModuleManager().getModuleName());
        assertNull(stored.get(LEGACY_REQUEST_ID_KEY, null));
        assertNull(stored.get(LEGACY_MODULE_KEY, null));
    }

    @Test void restartRetiresOnlyObsoleteActivationSettingsAndIgnoresTheirContents() throws Exception
    {
        Path installed = module("data/modules/module.jar", TestModule.class);
        stored.put(PATH_KEY, PortableApplicationPaths.toPortablePath(installed));
        stored.put(LEGACY_REQUEST_ID_KEY, "obsolete request identifier");
        stored.put(LEGACY_MODULE_KEY, "obsolete activation code");
        stored.put("unrelated.setting", "preserved");

        VoiceDecryptionModulePreference restarted = openPreference();

        assertTrue(restarted.getModuleManager().isLoaded(), restarted.getModuleManager().getStatus());
        assertEquals(installed, restarted.getPath());
        assertEquals(PortableApplicationPaths.toPortablePath(installed), stored.get(PATH_KEY, null));
        assertNull(stored.get(LEGACY_REQUEST_ID_KEY, null));
        assertNull(stored.get(LEGACY_MODULE_KEY, null));
        assertEquals("preserved", stored.get("unrelated.setting", null));
        assertEquals(2, stored.keys().length);
    }

    @Test void incompatibleReplacementKeepsTheWorkingModulePathAndStoredJar() throws Exception
    {
        assertTrue(preference.setPath(module("working/module.jar", TestModule.class)));
        Path selected = preference.getPath();
        String savedPath = stored.get(PATH_KEY, null);
        byte[] installedContents = Files.readAllBytes(selected);

        assertFalse(preference.setPath(module("incompatible/module.jar", LegacyModule.class)));

        assertEquals(selected, preference.getPath());
        assertEquals(selected, preference.getModuleManager().getPath());
        assertEquals(savedPath, stored.get(PATH_KEY, null));
        assertTrue(preference.getModuleManager().isLoaded());
        assertEquals("Test Module", preference.getModuleManager().getModuleName());
        assertArrayEquals(installedContents, Files.readAllBytes(selected));
        assertTrue(preference.getModuleManager().getStatus().contains("API 2"),
            preference.getModuleManager().getStatus());

        VoiceDecryptionModulePreference restarted = openPreference();
        assertTrue(restarted.getModuleManager().isLoaded(), restarted.getModuleManager().getStatus());
        assertEquals(selected, restarted.getPath());
    }

    @Test void unusableSameNamedReplacementKeepsTheWorkingModuleAndRestartSelection() throws Exception
    {
        assertTrue(preference.setPath(module("working/module.jar", TestModule.class)));
        Path selected = preference.getPath();
        String savedPath = stored.get(PATH_KEY, null);
        byte[] installedContents = Files.readAllBytes(selected);

        assertFalse(preference.setPath(module("unusable/module.jar", EmptyModule.class)));

        assertEquals(selected, preference.getPath());
        assertEquals(selected, preference.getModuleManager().getPath());
        assertEquals(savedPath, stored.get(PATH_KEY, null));
        assertTrue(preference.getModuleManager().isLoaded());
        assertEquals("Test Module", preference.getModuleManager().getModuleName());
        assertArrayEquals(installedContents, Files.readAllBytes(selected));
        assertTrue(preference.getModuleManager().getStatus().contains("no usable providers"),
            preference.getModuleManager().getStatus());

        preference.getModuleManager().close();
        VoiceDecryptionModulePreference restarted = openPreference();

        assertTrue(restarted.getModuleManager().isLoaded(), restarted.getModuleManager().getStatus());
        assertEquals(selected, restarted.getPath());
        assertEquals("Test Module", restarted.getModuleManager().getModuleName());
    }

    @Test void validSameNamedReplacementKeepsThePreviousJarAndRestoresTheNewSelection() throws Exception
    {
        assertTrue(preference.setPath(module("working/module.jar", TestModule.class)));
        Path previous = preference.getPath();
        byte[] previousContents = Files.readAllBytes(previous);
        Path replacement = module("updated/module.jar", UpdatedModule.class);

        assertTrue(preference.setPath(replacement), preference.getModuleManager().getStatus());

        Path selected = preference.getPath();
        assertNotEquals(previous, selected);
        assertEquals(selected, preference.getModuleManager().getPath());
        assertEquals(PortableApplicationPaths.toPortablePath(selected), stored.get(PATH_KEY, null));
        assertTrue(preference.getModuleManager().isLoaded());
        assertEquals("Updated Test Module", preference.getModuleManager().getModuleName());
        assertArrayEquals(previousContents, Files.readAllBytes(previous));
        assertArrayEquals(Files.readAllBytes(replacement), Files.readAllBytes(selected));

        preference.getModuleManager().close();
        VoiceDecryptionModulePreference restarted = openPreference();

        assertTrue(restarted.getModuleManager().isLoaded(), restarted.getModuleManager().getStatus());
        assertEquals(selected, restarted.getPath());
        assertEquals("Updated Test Module", restarted.getModuleManager().getModuleName());
        assertArrayEquals(previousContents, Files.readAllBytes(previous));
    }

    @Test void startupKeepsAnIncompatibleSavedSelectionVisible() throws Exception
    {
        Path installed = module("data/modules/legacy-module.jar", LegacyModule.class);
        String savedPath = PortableApplicationPaths.toPortablePath(installed);
        stored.put(PATH_KEY, savedPath);

        VoiceDecryptionModulePreference restarted = openPreference();

        assertFalse(restarted.getModuleManager().isLoaded());
        assertEquals(installed, restarted.getPath());
        assertEquals(savedPath, stored.get(PATH_KEY, null));
        assertTrue(restarted.getModuleManager().getStatus().contains("API 2"),
            restarted.getModuleManager().getStatus());
    }

    @Test void resetClearsTheModuleSelectionAndUnloadsWithoutRemovingOtherSettings() throws Exception
    {
        assertTrue(preference.setPath(module("source/module.jar", TestModule.class)));
        Path installed = preference.getPath();
        stored.put("unrelated.setting", "preserved");

        preference.resetPath();

        assertNull(preference.getPath());
        assertFalse(preference.getModuleManager().isLoaded());
        assertNull(preference.getModuleManager().getPath());
        assertNull(stored.get(PATH_KEY, null));
        assertEquals("preserved", stored.get("unrelated.setting", null));
        assertTrue(Files.isRegularFile(installed));
        assertEquals(2, notifications.get());
        VoiceDecryptionModulePreference restarted = openPreference();
        assertNull(restarted.getPath());
        assertFalse(restarted.getModuleManager().isLoaded());
    }

    private VoiceDecryptionModulePreference openPreference()
    {
        VoiceDecryptionModulePreference opened = new VoiceDecryptionModulePreference(
            ignored -> notifications.incrementAndGet(), stored);
        openedPreferences.add(opened);
        return opened;
    }

    private Path module(String name, Class<? extends VoiceDecryptionModule> moduleClass) throws IOException
    {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());

        try(JarOutputStream output = new JarOutputStream(Files.newOutputStream(path)))
        {
            output.putNextEntry(new JarEntry("META-INF/services/" + VoiceDecryptionModule.class.getName()));
            output.write((moduleClass.getName() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        return path;
    }

    public static class TestModule implements VoiceDecryptionModule
    {
        @Override public int getApiVersion() { return API_VERSION; }
        @Override public String getName() { return "Test Module"; }
        @Override public String getVersion() { return "1"; }
        @Override public Collection<VoiceEncryptionAlgorithm> getSupportedAlgorithms()
        {
            return List.of(VoiceEncryptionAlgorithm.APCO25_AES_256);
        }
        @Override public Collection<VoiceFrameDecryptorProvider> getProviders()
        {
            return List.of(new TestProvider());
        }
    }

    public static class LegacyModule extends TestModule
    {
        @Override public int getApiVersion() { return 2; }
    }

    public static class EmptyModule extends TestModule
    {
        @Override public Collection<VoiceFrameDecryptorProvider> getProviders() { return List.of(); }
    }

    public static class UpdatedModule extends TestModule
    {
        @Override public String getName() { return "Updated Test Module"; }
    }

    private static class TestProvider implements VoiceFrameDecryptorProvider
    {
        @Override public boolean supports(VoiceEncryptionContext context) { return false; }
        @Override public VoiceFrameDecryptor create(VoiceEncryptionContext context, VoiceEncryptionKey key)
            throws VoiceFrameDecryptionException
        {
            throw new VoiceFrameDecryptionException("Test provider does not create decryptors");
        }
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String,String> values = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
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

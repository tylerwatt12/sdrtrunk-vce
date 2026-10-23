/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.codec.mbe.decrypt;

import io.github.dsheirer.preference.encryption.VoiceEncryptionAlgorithm;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class VoiceDecryptionModuleManagerTest
{
    @Test
    void publishesPlainStatusAfterRuntimeStateAndProvidersWithoutJavaFx(@TempDir Path tempDirectory) throws Exception
    {
        assertTrue(Modifier.isVolatile(VoiceDecryptionModuleManager.class.getDeclaredField("mRuntimeLoaded")
            .getModifiers()));

        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            List<Boolean> transitions = new ArrayList<>();
            BiConsumer<VoiceDecryptionModuleManager.Status,VoiceDecryptionModuleManager.Status> listener = (previous, current) -> {
                assertEquals(current, manager.statusSnapshot());
                assertEquals(current.loaded(), manager.isLoaded());
                if(current.loaded()) assertFalse(manager.getProviders().isEmpty());
                if(previous.loaded() != current.loaded()) transitions.add(current.loaded());
            };
            manager.addStatusListener(listener);
            assertFalse(manager.isLoaded());
            assertFalse(manager.statusSnapshot().loaded());
            assertTrue(manager.load(createTestModuleJar(tempDirectory)),
                manager.getStatus());
            assertTrue(manager.isLoaded());
            assertTrue(manager.statusSnapshot().loaded());

            manager.unload();

            assertFalse(manager.isLoaded());
            assertFalse(manager.statusSnapshot().loaded());
            assertEquals(List.of(true, false), transitions);
            manager.removeStatusListener(listener);
            assertTrue(manager.load(createTestModuleJar(tempDirectory)));
            manager.unload();
            assertEquals(List.of(true, false), transitions, "removed local observers do not receive later state");
        }
    }

    @Test
    void aPresentationObserverCannotTurnASuccessfulModuleLoadIntoAFailure(@TempDir Path tempDirectory) throws Exception
    {
        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            manager.addStatusListener((previous, current) -> { throw new IllegalStateException("observer unavailable"); });
            assertTrue(manager.load(createTestModuleJar(tempDirectory)));
            assertTrue(manager.isLoaded());
            assertFalse(manager.getProviders().isEmpty());
            assertFalse(manager.load(tempDirectory.resolve("missing.jar")));
            assertTrue(manager.isLoaded(), "rejecting another module selection keeps the active module");
            assertTrue(manager.statusSnapshot().loaded());
            manager.unload();
            assertFalse(manager.isLoaded());
        }
    }

    @Test
    void rejectsLegacyActivationModulesWithoutReplacingTheActiveModule(@TempDir Path tempDirectory) throws Exception
    {
        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            Path active = createTestModuleJar(tempDirectory);
            assertTrue(manager.load(active));
            Path legacy = createTestModuleJar(tempDirectory, LegacyVoiceDecryptionModule.class);

            assertFalse(manager.isCompatible(legacy));
            assertFalse(manager.load(legacy));
            assertTrue(manager.getStatus().contains("Module API 2 is incompatible with SDRTrunk API 3"));
            assertTrue(manager.isLoaded());
            assertEquals(active.toRealPath(), manager.getPath());
            assertFalse(manager.getProviders().isEmpty());
        }
    }

    @Test
    void rejectsModulesWithoutUsableProviders(@TempDir Path tempDirectory) throws Exception
    {
        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            assertFalse(manager.load(createTestModuleJar(tempDirectory, EmptyVoiceDecryptionModule.class)));
            assertFalse(manager.isLoaded());
            assertTrue(manager.getStatus().contains("Module contains no usable providers"));
            assertTrue(manager.getProviders().isEmpty());
        }
    }

    @Test
    void inspectsExternalModuleWhenTestJarIsProvided()
    {
        String moduleJar = System.getenv("SDRTRUNK_DECRYPTION_MODULE_TEST_JAR");
        Assumptions.assumeTrue(moduleJar != null && Files.isRegularFile(Path.of(moduleJar)));

        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            assertTrue(manager.isCompatible(Path.of(moduleJar)), manager.getStatus());
            assertFalse(manager.isLoaded());

            assertTrue(manager.load(Path.of(moduleJar)), manager.getStatus());
            assertFalse(manager.getProviders().isEmpty());
            assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.APCO25)
                .contains(VoiceEncryptionAlgorithm.APCO25_AES_256));
            assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.DMR)
                .contains(VoiceEncryptionAlgorithm.DMR_DMRA_AES_256));
        }
    }

    private static Path createTestModuleJar(Path directory) throws IOException
    {
        return createTestModuleJar(directory, TestVoiceDecryptionModule.class);
    }

    private static Path createTestModuleJar(Path directory, Class<? extends VoiceDecryptionModule> module)
        throws IOException
    {
        Path moduleJar = directory.resolve(module.getSimpleName() + ".jar");

        try(JarOutputStream output = new JarOutputStream(Files.newOutputStream(moduleJar)))
        {
            output.putNextEntry(new JarEntry("META-INF/services/" + VoiceDecryptionModule.class.getName()));
            output.write((module.getName() + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        return moduleJar;
    }

    public static class TestVoiceDecryptionModule implements VoiceDecryptionModule
    {
        public TestVoiceDecryptionModule()
        {
        }

        @Override
        public int getApiVersion()
        {
            return API_VERSION;
        }

        @Override
        public String getName()
        {
            return "Test Voice Decryption Module";
        }

        @Override
        public String getVersion()
        {
            return "1";
        }

        @Override
        public Collection<VoiceEncryptionAlgorithm> getSupportedAlgorithms()
        {
            return List.of(VoiceEncryptionAlgorithm.APCO25_AES_256);
        }

        @Override
        public Collection<VoiceFrameDecryptorProvider> getProviders()
        {
            return List.of(new TestVoiceFrameDecryptorProvider());
        }
    }

    public static class LegacyVoiceDecryptionModule extends TestVoiceDecryptionModule
    {
        @Override
        public int getApiVersion()
        {
            return 2;
        }
    }

    public static class EmptyVoiceDecryptionModule extends TestVoiceDecryptionModule
    {
        @Override
        public Collection<VoiceFrameDecryptorProvider> getProviders()
        {
            return List.of();
        }
    }

    private static class TestVoiceFrameDecryptorProvider implements VoiceFrameDecryptorProvider
    {
        @Override
        public boolean supports(VoiceEncryptionContext context)
        {
            return false;
        }

        @Override
        public VoiceFrameDecryptor create(VoiceEncryptionContext context, VoiceEncryptionKey key)
            throws VoiceFrameDecryptionException
        {
            throw new VoiceFrameDecryptionException("Test provider does not create decryptors");
        }
    }
}

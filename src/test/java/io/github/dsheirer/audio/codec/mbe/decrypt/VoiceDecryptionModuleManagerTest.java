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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoiceDecryptionModuleManagerTest
{
    @Test
    void usesVolatileRuntimeStateAndMirrorsObservableProperty(@TempDir Path tempDirectory) throws Exception
    {
        assertTrue(Modifier.isVolatile(VoiceDecryptionModuleManager.class.getDeclaredField("mRuntimeLoaded")
            .getModifiers()));

        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            assertFalse(manager.isLoaded());
            assertFalse(manager.loadedProperty().get());
            assertTrue(manager.load(createTestModuleJar(tempDirectory), "request", "accepted"),
                manager.getStatus());
            assertTrue(manager.isLoaded());
            assertTrue(manager.loadedProperty().get());

            manager.unload();

            assertFalse(manager.isLoaded());
            assertFalse(manager.loadedProperty().get());
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

            String requestId = System.getenv("SDRTRUNK_MODULE_TEST_REQUEST_ID");
            String key = System.getenv("SDRTRUNK_MODULE_TEST_KEY");

            if(requestId != null && !requestId.isBlank() && key != null && !key.isBlank())
            {
                assertFalse(manager.load(Path.of(moduleJar), requestId, key + "x"));
                assertFalse(manager.isLoaded());
                assertTrue(manager.load(Path.of(moduleJar), requestId, key), manager.getStatus());
                assertFalse(manager.getProviders().isEmpty());
                assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.APCO25)
                    .contains(VoiceEncryptionAlgorithm.APCO25_AES_256));
                assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.DMR)
                    .contains(VoiceEncryptionAlgorithm.DMR_DMRA_AES_256));
                assertFalse(manager.load(Path.of(moduleJar), requestId, key + "x"));
                assertTrue(manager.isLoaded());
            }
        }
    }

    private static Path createTestModuleJar(Path directory) throws IOException
    {
        Path moduleJar = directory.resolve("voice-decryption-test-module.jar");

        try(JarOutputStream output = new JarOutputStream(Files.newOutputStream(moduleJar)))
        {
            output.putNextEntry(new JarEntry("META-INF/services/" + VoiceDecryptionModule.class.getName()));
            output.write((TestVoiceDecryptionModule.class.getName() + System.lineSeparator())
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
        public boolean verifyKey(String requestId, String key)
        {
            return "accepted".equals(key);
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

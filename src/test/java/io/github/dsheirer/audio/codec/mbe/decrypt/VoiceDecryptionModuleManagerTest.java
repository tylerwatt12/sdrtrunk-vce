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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VoiceDecryptionModuleManagerTest
{
    @Test
    void loadsExternalModuleWhenTestJarIsProvided()
    {
        String moduleJar = System.getenv("SDRTRUNK_DECRYPTION_MODULE_TEST_JAR");
        Assumptions.assumeTrue(moduleJar != null && Files.isRegularFile(Path.of(moduleJar)));

        try(VoiceDecryptionModuleManager manager = new VoiceDecryptionModuleManager())
        {
            assertTrue(manager.load(Path.of(moduleJar)), manager.getStatus());
            assertFalse(manager.getProviders().isEmpty());
            assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.APCO25)
                .contains(VoiceEncryptionAlgorithm.APCO25_AES_256));
            assertTrue(manager.getSupportedAlgorithms(VoiceEncryptionProtocol.DMR)
                .contains(VoiceEncryptionAlgorithm.DMR_DMRA_AES_256));
        }
    }
}

/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.audio.codec.mbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.module.decode.dmr.audio.DMRAudioModule;
import io.github.dsheirer.module.decode.nxdn.audio.NXDNAudioModule;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.audio.P25P2AudioModule;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.decoder.JmbeLibraryPreference;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import jmbe.iface.IAudioCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmbeAudioModuleFingerprintScopeTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void onlyP25EnablesCorrectedEvidenceOnConstructionAndLibraryReplacement() throws Exception
    {
        Path firstLibrary = JmbeLibraryLoaderTest.createLibrary(mTemporaryDirectory, "1.0.16", 16.0f,
            true, true, true);
        Path replacementLibrary = JmbeLibraryLoaderTest.createLibrary(mTemporaryDirectory, "1.0.17", 17.0f,
            true, true, true);
        AtomicReference<Path> selectedLibrary = new AtomicReference<>(firstLibrary);
        JmbeLibraryPreference libraryPreference = new JmbeLibraryPreference(null)
        {
            @Override
            public Path getPathJmbeLibrary()
            {
                return selectedLibrary.get();
            }

            @Override
            public boolean getMuteDecodedAmbeToneFrames()
            {
                return false;
            }
        };
        UserPreferences preferences = new UserPreferences()
        {
            @Override
            public JmbeLibraryPreference getJmbeLibraryPreference()
            {
                return libraryPreference;
            }
        };
        Map<JmbeAudioModule,Boolean> modules = new LinkedHashMap<>();

        try
        {
            //These constructors have UNKNOWN call sources; protocol opt-in must not depend on saved-channel identity.
            modules.put(new P25P1AudioModule(preferences, AliasList.empty("scope")), true);
            modules.put(new P25P2AudioModule(preferences, 1, AliasList.empty("scope")), true);
            modules.put(new DMRAudioModule(preferences, AliasList.empty("scope"), 1), false);
            modules.put(new DMRAudioModule(preferences, AliasList.empty("scope"), 2), false);
            modules.put(new NXDNAudioModule(preferences, AliasList.empty("scope")), false);
            Map<JmbeAudioModule,IAudioCodec> previousCodecs = new LinkedHashMap<>();

            for(var entry: modules.entrySet())
            {
                previousCodecs.put(entry.getKey(), verifyEvidence(entry.getKey(), entry.getValue(), 16.0f));
            }

            selectedLibrary.set(replacementLibrary);

            for(var entry: modules.entrySet())
            {
                JmbeAudioModule module = entry.getKey();
                IAudioCodec previousCodec = previousCodecs.get(module);
                module.preferenceUpdated(PreferenceType.JMBE_LIBRARY);
                IAudioCodec replacement = verifyEvidence(module, entry.getValue(), 17.0f);
                assertNotSame(previousCodec, replacement);
                assertNotSame(previousCodec.getClass().getClassLoader(), replacement.getClass().getClassLoader());
                assertEquals(0L, module.getCorrectedVoiceFrameFingerprint(previousCodec),
                    "Replacement must never read evidence from the previous codec");
                replacement.reset();
                assertEquals(0L, module.getCorrectedVoiceFrameFingerprint(replacement));
            }
        }
        finally
        {
            modules.keySet().forEach(JmbeAudioModule::dispose);
            JmbeLibraryLoader.getInstance().close();
        }
    }

    private static IAudioCodec verifyEvidence(JmbeAudioModule module, boolean expected, float marker) throws Exception
    {
        IAudioCodec codec = module.getAudioCodec();
        assertNotNull(codec);
        assertEquals(marker, codec.getAudio(new byte[]{42})[0], "Audio and quality/tone setup remain available");
        assertEquals(expected ? 1 : 0, codec.getClass().getMethod("getFingerprintEnableCalls").invoke(codec),
            "Non-P25 codecs must avoid optional fingerprint work entirely");
        assertEquals(expected ? 43L : 0L, codec.getClass().getMethod("getCorrectedVoiceFrameFingerprintV1").invoke(codec));
        assertEquals(expected ? 43L : 0L, module.getCorrectedVoiceFrameFingerprint(codec));
        return codec;
    }
}

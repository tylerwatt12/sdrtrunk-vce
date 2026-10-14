/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.codec.mbe;

import io.github.dsheirer.dsp.gain.AudioOutputGain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JmbeVocoderAudioTest
{
    @Test
    void loadsOptionalCapabilityFromTheSelectedJarAndRetainsCapturedProvider(@TempDir Path directory) throws Exception
    {
        Path library = VocoderLibraryFixture.create(directory);
        try(var loader = new JmbeLibraryLoader(getClass().getClassLoader(), false))
        {
            JmbeVocoderAudio provider = loader.getVocoderAudioProcessor(library);
            assertNotNull(provider);
            assertEquals("embedded-test", provider.modelInfo().get("id"));
            float[] raw = {.1f,-.3f};
            assertArrayEquals(new float[]{.2f,-.6f}, provider.process(raw,"NONE",false,true,null,null));
            assertArrayEquals(new float[]{.1f,-.3f}, raw);
            assertEquals(4, provider.process(raw,"IMBE",true,false,null,null).length);
            Path legacy = JmbeLibraryLoaderTest.createLibrary(directory,"1.0.17",17,true,false,false);
            assertNull(loader.getVocoderAudioProcessor(legacy));
            assertArrayEquals(new float[]{.2f,-.6f}, provider.process(raw,"NONE",false,true,null,null));
        }
    }

    @Test
    void flattenDetachesBuffersAndKeepsTheirOrder()
    {
        float[] first = {.1f,.2f}, second = {-.3f};
        float[] flattened = AudioOutputGain.flatten(List.of(first, new float[0], second));
        assertArrayEquals(new float[]{.1f,.2f,-.3f}, flattened);
        flattened[0] = 0;
        assertEquals(.1f, first[0]);
    }
}

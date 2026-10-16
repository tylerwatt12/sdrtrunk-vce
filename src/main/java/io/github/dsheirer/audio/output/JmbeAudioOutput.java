/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.audio.output;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.codec.mbe.JmbeAudioOutputProcessor;
import io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Worker-only output view. Native call PCM, timing, duplicate evidence and tones always retain their 8 kHz clock. */
public final class JmbeAudioOutput
{
    public static final int NATIVE_SAMPLE_RATE = 8000;
    private JmbeAudioOutput() {}

    public static RenderedAudio render(CompletedAudioCall call, Path library)
    {
        if(codec(call).equals("NONE")) return nativeAudio(call);
        return renderWithProcessor(call, JmbeLibraryLoader.getInstance().getAudioOutputProcessor(library));
    }

    /** Also used by isolated output tests without loading a library or changing receiver preferences. */
    public static RenderedAudio renderWithProcessor(CompletedAudioCall call, JmbeAudioOutputProcessor processor)
    {
        if(processor == null) return nativeAudio(call);
        try
        {
            int count = 0;
            for(float[] buffer : call.audioBuffers())
            {
                count = Math.addExact(count, buffer.length);
                if(count > JmbeAudioOutputProcessor.MAXIMUM_SAMPLES) return nativeAudio(call);
            }
            if(count == 0) return nativeAudio(call);
            float[] input = new float[count];
            int offset = 0;
            for(float[] buffer : call.audioBuffers())
            {
                System.arraycopy(buffer, 0, input, offset, buffer.length);
                offset += buffer.length;
            }
            var result = processor.process(input, NATIVE_SAMPLE_RATE, codec(call));
            // Small output packets keep downstream resamplers bounded even when a library processes a whole call.
            int packetLength = Math.max(1, result.sampleRate() / 50);
            List<float[]> packets = new ArrayList<>();
            for(offset = 0; offset < result.samples().length; offset += packetLength)
                packets.add(Arrays.copyOfRange(result.samples(), offset,
                    Math.min(result.samples().length, offset + packetLength)));
            return new RenderedAudio(packets, result.sampleRate());
        }
        catch(RuntimeException | LinkageError unavailable)
        {
            // The provider received an independent copy. Its failure cannot corrupt the elected native call.
            return nativeAudio(call);
        }
    }

    public static RenderedAudio nativeAudio(CompletedAudioCall call)
    {
        return new RenderedAudio(call.audioBuffers(), NATIVE_SAMPLE_RATE);
    }

    private static String codec(CompletedAudioCall call)
    {
        return switch(call.nativeAudioSnapshot().callLegSource().decoderType())
        {
            case P25_PHASE1, P25_CONVENTIONAL -> "IMBE";
            case P25_PHASE2, DMR, NXDN -> "AMBE_PLUS_2";
            case null, default -> "NONE";
        };
    }

    public record RenderedAudio(List<float[]> buffers, int sampleRate)
    {
        public RenderedAudio
        {
            buffers = List.copyOf(buffers);
            if(sampleRate < 8000 || sampleRate > 96000) throw new IllegalArgumentException("Invalid audio sample rate");
        }
    }
}

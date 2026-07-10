/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.playback.PlaybackAudioFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import net.sourceforge.lame.lowlevel.LameEncoder;
import net.sourceforge.lame.mp3.MPEGMode;

/**
 * Continuous 8 kHz stereo MP3 encoder for the final local playback mix. Mono input is duplicated to both channels.
 */
final class StatsPlaybackAudioEncoder
{
    private static final int BIT_RATE = 32;
    private static final AudioFormat FORMAT = AudioFormats.PCM_SIGNED_8000_HZ_16BITS_STEREO;
    private final LameEncoder mEncoder =
        new LameEncoder(FORMAT, BIT_RATE, MPEGMode.STEREO, LameEncoder.DEFAULT_QUALITY, false);
    private final byte[] mOutputBuffer = new byte[mEncoder.getPCMBufferSize()];

    List<byte[]> encode(PlaybackAudioFrame frame)
    {
        if(frame == null || frame.pcm() == null || frame.pcm().length == 0)
        {
            return Collections.emptyList();
        }

        byte[] input = frame.channels() == 1 ? stereo(frame.pcm()) : frame.pcm();

        if(frame.channels() != 1 && frame.channels() != 2)
        {
            return Collections.emptyList();
        }

        List<byte[]> chunks = new ArrayList<>();
        int pointer = 0;

        while(pointer < input.length)
        {
            int inputLength = Math.min(mOutputBuffer.length, input.length - pointer);
            int outputLength = mEncoder.encodeBuffer(input, pointer, inputLength, mOutputBuffer);
            pointer += inputLength;

            if(outputLength > 0)
            {
                chunks.add(Arrays.copyOf(mOutputBuffer, outputLength));
            }
        }

        return chunks;
    }

    List<byte[]> finish()
    {
        byte[] output = new byte[mEncoder.getMP3BufferSize()];
        int length = mEncoder.encodeFinish(output);
        return length > 0 ? List.of(Arrays.copyOf(output, length)) : List.of();
    }

    private static byte[] stereo(byte[] mono)
    {
        byte[] stereo = new byte[mono.length * 2];

        for(int source = 0, target = 0; source + 1 < mono.length; source += 2)
        {
            stereo[target++] = mono[source];
            stereo[target++] = mono[source + 1];
            stereo[target++] = mono[source];
            stereo[target++] = mono[source + 1];
        }

        return stereo;
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.output.JmbeAudioOutput;
import io.github.dsheirer.audio.output.JmbeAudioOutput.RenderedAudio;
import io.github.dsheirer.dsp.gain.PeakAudioGain;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3AudioConverter;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import io.github.dsheirer.record.wave.WaveWriter;
import io.github.dsheirer.sample.ConversionUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import javax.sound.sampled.AudioFormat;

/**
 * Recording utility for completed immutable audio calls.
 */
public class AudioCallRecorder
{

    private AudioCallRecorder()
    {
    }

    public static void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                             UserPreferences userPreferences) throws IOException
    {
        write(completedAudioCall, path, recordFormat, userPreferences, completedAudioCall.snapshot().identifierCollection());
    }

    public static void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                             UserPreferences userPreferences, IdentifierCollection identifierCollection)
        throws IOException
    {
        write(completedAudioCall, path, recordFormat, userPreferences, identifierCollection,
            userPreferences.getMP3Preference().isNormalizeAudioBeforeEncode());
    }

    public static void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                             UserPreferences userPreferences, boolean normalizeAudio) throws IOException
    {
        write(completedAudioCall, path, recordFormat, userPreferences,
            completedAudioCall.snapshot().identifierCollection(), normalizeAudio);
    }

    /** Uses the worker's captured normalization choice, independent of later preference changes. */
    public static void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                             UserPreferences userPreferences, IdentifierCollection identifierCollection,
                             boolean normalizeAudio) throws IOException
    {
        writeRendered(completedAudioCall, render(completedAudioCall, userPreferences), path, recordFormat,
            userPreferences, identifierCollection, normalizeAudio);
    }

    /** Output-worker seam: metadata always comes from the native call, PCM and its clock from the output view. */
    public static void writeRendered(CompletedAudioCall call, RenderedAudio audio, Path path, RecordFormat format,
                                     UserPreferences preferences, IdentifierCollection identifiers,
                                     boolean normalizeAudio) throws IOException
    {
        switch(format)
        {
            case MP3 -> recordMP3(call, audio, path, preferences, identifiers, normalizeAudio);
            case WAVE -> recordWAVE(call, audio, path, identifiers);
            default -> throw new IllegalArgumentException("Unrecognized recording format [" + format.name() + "]");
        }
    }

    private static RenderedAudio render(CompletedAudioCall call, UserPreferences preferences)
    {
        RenderedAudio audio = JmbeAudioOutput.render(call, preferences.getJmbeLibraryPreference().getPathJmbeLibrary());
        var decoder = call.nativeAudioSnapshot().callLegSource().decoderType();
        if(decoder == io.github.dsheirer.module.decode.DecoderType.P25_PHASE1 ||
            decoder == io.github.dsheirer.module.decode.DecoderType.P25_CONVENTIONAL)
            return new RenderedAudio(new PeakAudioGain(5.0f, .95f).process(audio.buffers()), audio.sampleRate());
        // A supplied output may need attenuation; ordinary native Phase 2 PCM keeps its existing gain policy.
        if(audio.sampleRate() != JmbeAudioOutput.NATIVE_SAMPLE_RATE)
            return new RenderedAudio(new PeakAudioGain(1.0f, .95f).process(audio.buffers()), audio.sampleRate());
        return audio;
    }

    public static void recordMP3(CompletedAudioCall completedAudioCall, Path path, UserPreferences userPreferences,
                                 IdentifierCollection identifierCollection) throws IOException
    {
        recordMP3(completedAudioCall, path, userPreferences, identifierCollection,
            userPreferences.getMP3Preference().isNormalizeAudioBeforeEncode());
    }

    public static void recordMP3(CompletedAudioCall completedAudioCall, Path path, UserPreferences userPreferences,
                                 IdentifierCollection identifierCollection, boolean normalizeAudio) throws IOException
    {
        recordMP3(completedAudioCall, render(completedAudioCall, userPreferences), path, userPreferences,
            identifierCollection, normalizeAudio);
    }

    private static void recordMP3(CompletedAudioCall completedAudioCall, RenderedAudio audio, Path path,
                                  UserPreferences userPreferences, IdentifierCollection identifierCollection,
                                  boolean normalizeAudio) throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            try(OutputStream outputStream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
            {
                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getCapturedMetadataMap(identifierCollection,
                    completedAudioCall.snapshot().recordingMetadata());

                byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap,
                    VceRecordingMetadata.capture(completedAudioCall));
                outputStream.write(id3Bytes);

                InputAudioFormat inputAudioFormat = userPreferences.getMP3Preference().getAudioSampleRate();
                MP3Setting mp3Setting = userPreferences.getMP3Preference().getMP3Setting();
                // MP3 retains its explicit destination format. The converter resamples from the supplied clock.
                MP3AudioConverter converter = new MP3AudioConverter(inputAudioFormat, mp3Setting, normalizeAudio,
                    audio.sampleRate());

                for(byte[] mp3Frame: converter.convert(audio.buffers()))
                {
                    outputStream.write(mp3Frame);
                }


                outputStream.flush();
            }
        }
    }

    public static void recordWAVE(CompletedAudioCall completedAudioCall, Path path, IdentifierCollection identifierCollection)
        throws IOException
    {
        recordWAVE(completedAudioCall, JmbeAudioOutput.nativeAudio(completedAudioCall), path, identifierCollection);
    }

    private static void recordWAVE(CompletedAudioCall completedAudioCall, RenderedAudio audio, Path path,
                                   IdentifierCollection identifierCollection) throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            AudioFormat format = new AudioFormat(audio.sampleRate(), 16, 1, true, false);
            try(WaveWriter writer = new WaveWriter(format, path))
            {
                for(float[] audioBuffer: audio.buffers())
                {
                    writer.writeData(ConversionUtils.convertToSigned16BitSamples(audioBuffer));
                }

                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getCapturedMetadataMap(identifierCollection,
                    completedAudioCall.snapshot().recordingMetadata());

                ByteBuffer listChunk = AudioMetadataUtils.getLISTChunk(metadataMap);
                byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap,
                    VceRecordingMetadata.capture(completedAudioCall));
                ByteBuffer id3Chunk = AudioMetadataUtils.getID3Chunk(id3Bytes);
                writer.writeMetadata(listChunk, id3Chunk);
            }
        }
    }

}

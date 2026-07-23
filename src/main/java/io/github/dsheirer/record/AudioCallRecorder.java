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

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.call.CompletedAudioCall;
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
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Recording utility for completed immutable audio calls.
 */
public class AudioCallRecorder
{

    public static final int MP3_BIT_RATE = 16;
    public static final boolean CONSTANT_BIT_RATE = false;

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
        switch(recordFormat)
        {
            case MP3:
                recordMP3(completedAudioCall, path, userPreferences, identifierCollection);
                break;
            case WAVE:
                recordWAVE(completedAudioCall, path, identifierCollection);
                break;
            default:
                throw new IllegalArgumentException("Unrecognized recording format [" + recordFormat.name() + "]");
        }
    }

    /**
     * Writes a canonical retained-call file using only the frozen alias metadata in the manifest.
     */
    public static void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                             UserPreferences userPreferences, IdentifierCollection identifierCollection,
                             RecordedCallManifest manifest) throws IOException
    {
        switch(recordFormat)
        {
            case MP3:
                recordManagedMP3(completedAudioCall, path, userPreferences, identifierCollection, manifest);
                break;
            case WAVE:
                recordManagedWAVE(completedAudioCall, path, identifierCollection, manifest);
                break;
            default:
                throw new IllegalArgumentException("Unrecognized recording format [" + recordFormat.name() + "]");
        }
    }

    public static void recordMP3(CompletedAudioCall completedAudioCall, Path path, UserPreferences userPreferences,
                                 IdentifierCollection identifierCollection) throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            try(OutputStream outputStream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
            {
                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(identifierCollection,
                    completedAudioCall.snapshot().aliasList());

                byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap);
                outputStream.write(id3Bytes);

                InputAudioFormat inputAudioFormat = userPreferences.getMP3Preference().getAudioSampleRate();
                MP3Setting mp3Setting = userPreferences.getMP3Preference().getMP3Setting();
                boolean normalizeAudio = userPreferences.getMP3Preference().isNormalizeAudioBeforeEncode();

                MP3AudioConverter converter = new MP3AudioConverter(inputAudioFormat, mp3Setting, normalizeAudio);
                List<byte[]> mp3Frames = converter.convert(completedAudioCall.audioBuffers());

                for(byte[] mp3Frame: mp3Frames)
                {
                    outputStream.write(mp3Frame);
                }

                List<byte[]> lastFrames = converter.flush();

                for(byte[] lastFrame: lastFrames)
                {
                    outputStream.write(lastFrame);
                }

                outputStream.flush();
            }
        }
    }

    public static void recordWAVE(CompletedAudioCall completedAudioCall, Path path, IdentifierCollection identifierCollection)
        throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            try(WaveWriter writer = new WaveWriter(AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO, path))
            {
                for(float[] audioBuffer: completedAudioCall.audioBuffers())
                {
                    writer.writeData(ConversionUtils.convertToSigned16BitSamples(audioBuffer));
                }

                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(identifierCollection,
                    completedAudioCall.snapshot().aliasList());

                ByteBuffer listChunk = AudioMetadataUtils.getLISTChunk(metadataMap);
                byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap);
                ByteBuffer id3Chunk = AudioMetadataUtils.getID3Chunk(id3Bytes);
                writer.writeMetadata(listChunk, id3Chunk);
            }
        }
    }

    private static void recordManagedMP3(CompletedAudioCall completedAudioCall, Path path,
                                         UserPreferences userPreferences, IdentifierCollection identifierCollection,
                                         RecordedCallManifest manifest) throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            try(FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
                OutputStream outputStream = Channels.newOutputStream(channel))
            {
                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(identifierCollection,
                    manifest.metadata(), manifest);
                outputStream.write(AudioMetadataUtils.getMP3ID3(metadataMap));
                InputAudioFormat inputAudioFormat = userPreferences.getMP3Preference().getAudioSampleRate();
                MP3Setting mp3Setting = userPreferences.getMP3Preference().getMP3Setting();
                boolean normalizeAudio = userPreferences.getMP3Preference().isNormalizeAudioBeforeEncode();
                MP3AudioConverter converter = new MP3AudioConverter(inputAudioFormat, mp3Setting, normalizeAudio);

                for(byte[] frame: converter.convert(completedAudioCall.audioBuffers()))
                {
                    outputStream.write(frame);
                }

                for(byte[] frame: converter.flush())
                {
                    outputStream.write(frame);
                }

                outputStream.flush();
                channel.force(true);
            }
        }
    }

    private static void recordManagedWAVE(CompletedAudioCall completedAudioCall, Path path,
                                          IdentifierCollection identifierCollection,
                                          RecordedCallManifest manifest) throws IOException
    {
        if(completedAudioCall.hasAudio())
        {
            try(WaveWriter writer = new WaveWriter(AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO, path))
            {
                for(float[] audioBuffer: completedAudioCall.audioBuffers())
                {
                    writer.writeData(ConversionUtils.convertToSigned16BitSamples(audioBuffer));
                }

                Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(identifierCollection,
                    manifest.metadata(), manifest);
                ByteBuffer listChunk = AudioMetadataUtils.getLISTChunk(metadataMap);
                byte[] id3Bytes = AudioMetadataUtils.getMP3ID3(metadataMap);
                writer.writeMetadata(listChunk, AudioMetadataUtils.getID3Chunk(id3Bytes));
            }
        }
    }
}

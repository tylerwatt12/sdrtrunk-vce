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

package io.github.dsheirer.audio.codec.mbe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.audio.P25P1CallSequenceRecorder;
import io.github.dsheirer.module.decode.p25.audio.VoiceFrame;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioSegmentRecorder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import jmbe.iface.IAudioCodec;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for converting MBE call sequences (*.mbe) to PCM wave audio format
 */
public class MBECallSequenceConverter
{
    private static final Logger mLog = LoggerFactory.getLogger(MBECallSequenceConverter.class);

    /**
     * Converts the input MBE file to PCM audio and writes to the output wave file.
     * @param input path to the MBE file.
     * @param output path to write the WAVE recording.
     * @throws IOException if there is an error.
     */
    public static void convert(Path input, Path output) throws IOException
    {
        InputStream inputStream = Files.newInputStream(input);
        ObjectMapper mapper = new ObjectMapper();
        MBECallSequence sequence = mapper.readValue(inputStream, MBECallSequence.class);
        convert(sequence, output);
    }

    public static void convert(MBECallSequence callSequence, Path outputPath)
    {
        if(callSequence == null || callSequence.isEncrypted())
        {
            throw new IllegalArgumentException("Cannot decode null or encrypted call sequence");
        }

        if(callSequence.getProtocol().equals(P25P1CallSequenceRecorder.PROTOCOL))
        {
            P25P1AudioModule audioModule = new P25P1AudioModule(new UserPreferences(), new AliasList("mbe generator"));
            AtomicReference<CompletedAudioCall> completedAudioCall = new AtomicReference<>();
            AtomicReference<AudioCallSnapshot> latestSnapshot = new AtomicReference<>();
            List<float[]> audioBuffers = new ArrayList<>();
            audioModule.setAudioCallEventListener(event -> captureCompletedAudioCall(event, latestSnapshot,
                audioBuffers, completedAudioCall));
            audioModule.setRecordAudio(true);
            audioModule.start();

            if(callSequence.getFromIdentifier() != null)
            {
                int from = 0;

                try
                {
                    from = Integer.parseInt(callSequence.getFromIdentifier());
                    audioModule.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(APCO25RadioIdentifier.createFrom(from),
                        IdentifierUpdateNotification.Operation.ADD, 0));
                }
                catch(Exception e)
                {
                    mLog.error("Error parsing from identifier from value [" + callSequence.getFromIdentifier());
                }
            }

            if(callSequence.getToIdentifier() != null)
            {
                int to = 0;

                try
                {
                    to = Integer.parseInt(callSequence.getToIdentifier());
                    audioModule.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(APCO25Talkgroup.create(to),
                        IdentifierUpdateNotification.Operation.ADD, 0));
                }
                catch(Exception e)
                {
                    mLog.error("Error parsing from identifier from value [" + callSequence.getFromIdentifier());
                }
            }

            IAudioCodec codec = audioModule.getAudioCodec();

            for(VoiceFrame voiceFrame: callSequence.getVoiceFrames())
            {
                byte[] frameBytes = voiceFrame.getFrameBytes();
                float[] audio = codec.getAudio(frameBytes);
                audioModule.addAudio(audio);
            }

            audioModule.stop();
            CompletedAudioCall call = completedAudioCall.get();

            if(call != null)
            {
                try
                {
                    AudioSegmentRecorder.recordWAVE(call, outputPath, call.snapshot().identifierCollection());
                }
                catch(IOException ioe)
                {
                    mLog.error("Error writing completed audio call", ioe);
                }
            }
        }
    }

    private static void captureCompletedAudioCall(AudioCallEvent event, AtomicReference<AudioCallSnapshot> latestSnapshot,
                                                  List<float[]> audioBuffers,
                                                  AtomicReference<CompletedAudioCall> completedAudioCall)
    {
        if(event == null || event.snapshot() == null)
        {
            return;
        }

        latestSnapshot.set(event.snapshot());

        if(event.eventType() == AudioCallEventType.AUDIO_FRAME && event.audioFrame() != null)
        {
            audioBuffers.add(event.audioFrame());
        }
        else if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
        {
            completedAudioCall.set(new CompletedAudioCall(event.snapshot(), List.copyOf(audioBuffers)));
        }
    }

    public static void main(String[] args)
    {
        boolean all = true;

        String path = "/home/denny/SDRTrunk/recordings";
        Path input = Paths.get(path);

        if(all)
        {
            Collection<File> mbeFiles = FileUtils.listFiles(input.toFile(), new SuffixFileFilter(".mbe"), DirectoryFileFilter.DIRECTORY);

            for(File inputFile: mbeFiles)
            {
                Path output = Paths.get(inputFile.getAbsolutePath().replace(".mbe", ".wav"));
                mLog.info("Converting: " + inputFile);
                try
                {
                    MBECallSequenceConverter.convert(inputFile.toPath(), output);
                }
                catch(IOException ioe)
                {
                    mLog.error("Error", ioe);
                }
            }
        }
        else
        {
            Path output = Paths.get(path.replace(".mbe", ".wav"));
            mLog.info("Converting: " + path);

            try
            {
                MBECallSequenceConverter.convert(input, output);
            }
            catch(IOException ioe)
            {
                mLog.error("Error", ioe);
            }
        }
    }
}

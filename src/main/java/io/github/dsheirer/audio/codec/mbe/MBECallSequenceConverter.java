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
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.decode.dmr.audio.DMRAudioModule;
import io.github.dsheirer.module.decode.dmr.audio.DMRCallSequenceRecorder;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.audio.P25P1CallSequenceRecorder;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.record.AudioCallRecorder;
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
        ObjectMapper mapper = new ObjectMapper();

        try(InputStream inputStream = Files.newInputStream(input))
        {
            MBECallSequence sequence = mapper.readValue(inputStream, MBECallSequence.class);
            convert(sequence, output);
        }
    }

    public static void convert(MBECallSequence callSequence, Path outputPath)
    {
        convert(callSequence, outputPath, new UserPreferences());
    }

    public static void convert(MBECallSequence callSequence, Path outputPath, UserPreferences userPreferences)
    {
        if(callSequence == null)
        {
            throw new IllegalArgumentException("Cannot decode null call sequence");
        }

        if(P25P1CallSequenceRecorder.PROTOCOL.equals(callSequence.getProtocol()))
        {
            P25P1AudioModule audioModule = new P25P1AudioModule(userPreferences, new AliasList("mbe generator"));
            VoiceEncryptionKeyResolver keyResolver =
                new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
            VoiceFrameDecryptorFactory decryptorFactory = new VoiceFrameDecryptorFactory(userPreferences
                .getVoiceDecryptionModulePreference().getModuleManager());
            AtomicReference<CompletedAudioCall> completedAudioCall = new AtomicReference<>();
            AtomicReference<AudioCallSnapshot> latestSnapshot = new AtomicReference<>();
            List<float[]> audioBuffers = new ArrayList<>();
            audioModule.setAudioCallEventListener(event -> captureCompletedAudioCall(event, latestSnapshot,
                audioBuffers, completedAudioCall));
            audioModule.setRecordAudio(true);
            audioModule.start();
            boolean stopped = false;

            try
            {
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
                        mLog.error("Error parsing to identifier from value [" + callSequence.getToIdentifier());
                    }
                }

                IAudioCodec codec = audioModule.getAudioCodec();
                VoiceFrameDecryptor decryptor = null;

                for(VoiceFrame voiceFrame: callSequence.getVoiceFrames())
                {
                    if(hasEncryptionMetadata(voiceFrame))
                    {
                        decryptor = createDecryptor(voiceFrame, VoiceEncryptionProtocol.APCO25, 0,
                            audioModule.getIdentifierCollection(), keyResolver, decryptorFactory);
                    }

                    if(callSequence.isEncrypted() && decryptor == null)
                    {
                        continue;
                    }

                    byte[] frameBytes = getFrameBytes(voiceFrame, decryptor);
                    float[] audio = codec.getAudio(frameBytes);
                    audioModule.addAudio(audio);
                }

                audioModule.stop();
                stopped = true;
                CompletedAudioCall call = completedAudioCall.get();

                if(call != null)
                {
                    try
                    {
                        AudioCallRecorder.recordWAVE(call, outputPath, call.snapshot().identifierCollection());
                    }
                    catch(IOException ioe)
                    {
                        mLog.error("Error writing completed audio call", ioe);
                    }
                }
            }
            finally
            {
                if(!stopped)
                {
                    audioModule.stop();
                }
            }
        }
        else if(DMRCallSequenceRecorder.PROTOCOL.equals(callSequence.getProtocol()))
        {
            DMRAudioModule audioModule = new DMRAudioModule(userPreferences, new AliasList("mbe generator"), 1);
            VoiceEncryptionKeyResolver keyResolver =
                new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
            VoiceFrameDecryptorFactory decryptorFactory = new VoiceFrameDecryptorFactory(userPreferences
                .getVoiceDecryptionModulePreference().getModuleManager());
            AtomicReference<CompletedAudioCall> completedAudioCall = new AtomicReference<>();
            AtomicReference<AudioCallSnapshot> latestSnapshot = new AtomicReference<>();
            List<float[]> audioBuffers = new ArrayList<>();
            audioModule.setAudioCallEventListener(event -> captureCompletedAudioCall(event, latestSnapshot,
                audioBuffers, completedAudioCall));
            audioModule.setRecordAudio(true);
            audioModule.start();
            boolean stopped = false;

            try
            {
                IAudioCodec codec = audioModule.getAudioCodec();
                VoiceFrameDecryptor decryptor = null;

                for(VoiceFrame voiceFrame: callSequence.getVoiceFrames())
                {
                    if(hasEncryptionMetadata(voiceFrame))
                    {
                        decryptor = createDecryptor(voiceFrame, VoiceEncryptionProtocol.DMR, 1,
                            audioModule.getIdentifierCollection(), keyResolver, decryptorFactory);
                    }

                    if(callSequence.isEncrypted() && decryptor == null)
                    {
                        continue;
                    }

                    byte[] frameBytes = getFrameBytes(voiceFrame, decryptor);
                    float[] audio = codec.getAudio(frameBytes);
                    audioModule.addAudio(audio);
                }

                audioModule.stop();
                stopped = true;
                CompletedAudioCall call = completedAudioCall.get();

                if(call != null)
                {
                    try
                    {
                        AudioCallRecorder.recordWAVE(call, outputPath, call.snapshot().identifierCollection());
                    }
                    catch(IOException ioe)
                    {
                        mLog.error("Error writing completed audio call", ioe);
                    }
                }
            }
            finally
            {
                if(!stopped)
                {
                    audioModule.stop();
                }
            }
        }
    }

    private static byte[] getFrameBytes(VoiceFrame voiceFrame, VoiceFrameDecryptor decryptor)
    {
        byte[] frameBytes = voiceFrame.getFrameBytes();

        if(decryptor != null)
        {
            try
            {
                frameBytes = decryptor.decrypt(frameBytes);
            }
            catch(VoiceFrameDecryptionException e)
            {
                throw new IllegalArgumentException("Unable to decrypt encrypted MBE frame", e);
            }
        }

        return frameBytes;
    }

    private static boolean hasEncryptionMetadata(VoiceFrame voiceFrame)
    {
        return voiceFrame.getAlgorithm() != null || voiceFrame.getKeyId() != null;
    }

    private static VoiceFrameDecryptor createDecryptor(VoiceFrame voiceFrame, VoiceEncryptionProtocol protocol,
                                                       int timeslot, IdentifierCollection identifiers,
                                                       VoiceEncryptionKeyResolver keyResolver,
                                                       VoiceFrameDecryptorFactory decryptorFactory)
    {
        if(hasEncryptionMetadata(voiceFrame))
        {
            if(voiceFrame.getAlgorithm() == null || voiceFrame.getKeyId() == null)
            {
                throw new IllegalArgumentException("Encrypted MBE frame is missing algorithm or key ID metadata");
            }

            VoiceEncryptionContext context = new VoiceEncryptionContext(protocol, voiceFrame.getAlgorithm(),
                voiceFrame.getKeyId(), voiceFrame.getMessageIndicator(), timeslot, identifiers);
            VoiceFrameDecryptor decryptor = keyResolver.resolve(context)
                .flatMap(key -> decryptorFactory.create(context, key))
                .orElseThrow(() -> new IllegalArgumentException("No configured key for encrypted MBE frame"));

            if(!decryptor.isImplemented())
            {
                throw new IllegalArgumentException("Encrypted MBE frame decryption is not implemented");
            }

            return decryptor;
        }

        return null;
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

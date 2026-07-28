/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
package io.github.dsheirer.module.decode.dmr.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.codec.mbe.AmbeAudioModule;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateProvider;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.ToneIdentifierMessage;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageProvider;
import io.github.dsheirer.module.decode.dmr.identifier.DMRToneIdentifier;
import io.github.dsheirer.module.decode.dmr.message.data.header.PiHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.VoiceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.AbstractVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.EncryptionParameters;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.type.EncryptionAlgorithm;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.embedded.EmbeddedParameters;
import io.github.dsheirer.module.decode.dmr.message.voice.embedded.EmbeddedEncryptionParameters;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jmbe.iface.IAudioWithMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DMR Audio Module for converting transmitted AMBE audio frames to 8 kHz PCM audio
 */
public class DMRAudioModule extends AmbeAudioModule implements IdentifierUpdateProvider, IMessageProvider
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRAudioModule.class);
    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private ToneMetadataProcessor mToneMetadataProcessor = new ToneMetadataProcessor();
    private Listener<IdentifierUpdateNotification> mIdentifierUpdateNotificationListener;
    private List<byte[]> mQueuedAmbeFrames = new ArrayList<>();
    private boolean mEncryptedCallStateEstablished = false;
    private boolean mEncryptedCall = false;
    private Listener<IMessage> mMessageListener;
    private VoiceEncryptionKeyResolver mEncryptionKeyResolver;
    private VoiceFrameDecryptorFactory mVoiceFrameDecryptorFactory;
    private VoiceFrameDecryptor mVoiceFrameDecryptor;
    private VoiceEncryptionContext mPendingEncryptionContext;

    /**
     * Constructs an instance
     * @param userPreferences for JMBE library location
     * @param aliasList for audio
     * @param timeslot for this audio module
     */
    public DMRAudioModule(UserPreferences userPreferences, AliasList aliasList, int timeslot)
    {
        super(userPreferences, aliasList, timeslot);
        mEncryptionKeyResolver = new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
        mVoiceFrameDecryptorFactory = new VoiceFrameDecryptorFactory(userPreferences
            .getVoiceDecryptionModulePreference().getModuleManager());
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        //Explicitly clear FROM identifiers to ensure previous call TONE identifiers are cleared.
        mIdentifierCollection.remove(Role.FROM);

        mEncryptedCall = false;
        mEncryptedCallStateEstablished = false;
        mQueuedAmbeFrames.clear();
        mVoiceFrameDecryptor = null;
        mPendingEncryptionContext = null;
    }

    @Override
    public void start()
    {
        /* no action required */
    }

    /**
     * Processes DMR AMBE audio frames and signalling messages.
     */
    public void receive(IMessage message)
    {
        if(hasAudioCodec() && message.getTimeslot() == getTimeslot())
        {
            if(message instanceof PiHeader pi && pi.getLCMessage() instanceof EncryptionParameters ep &&
                ep.isValid())
            {
                mEncryptedCallStateEstablished = true;
                mEncryptedCall = true;
                mPendingEncryptionContext = null;
                mVoiceFrameDecryptor = createDecryptor(createContext(ep));
            }

            if(message instanceof VoiceMessage voiceMessage && isVoiceSuperframeStart(voiceMessage) &&
                mPendingEncryptionContext != null)
            {
                mVoiceFrameDecryptor = createDecryptor(mPendingEncryptionContext);
                mPendingEncryptionContext = null;
            }

            VoiceEncryptionContext embeddedContext = createEmbeddedContext(message);

            if(embeddedContext != null)
            {
                mEncryptedCallStateEstablished = true;
                mEncryptedCall = true;
                mPendingEncryptionContext = embeddedContext;
            }

            //Attempt to set the audio encryption state from certain types of messages
            if(!mEncryptedCallStateEstablished)
            {
                //DCDM doesn't provide FLCs or EMBs ... assume that the call is unencrypted.
                if(message instanceof VoiceMessage vm && vm.getSyncPattern().isDirect())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = false;
                }
                //Both Motorola and Hytera signal their Basic Privacy (BP) scrambling in some of the Voice B-F frames
                //in the EMB field.
                else if(message instanceof VoiceEMBMessage voice)
                {
                    if(voice.hasEmbeddedParameters() &&
                       voice.getEmbeddedParameters().getShortBurst() instanceof EmbeddedEncryptionParameters)
                    {
                        mEncryptedCallStateEstablished = true;
                        mEncryptedCall = true;
                    }
                    else if(voice.getEMB().isValid())
                    {
                        mEncryptedCallStateEstablished = true;
                        mEncryptedCall = voice.getEMB().isEncrypted();
                    }
                }
                else if(message instanceof VoiceHeader vh && vh.getLCMessage() instanceof AbstractVoiceChannelUser vcu &&
                        vcu.isValid())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = vcu.getServiceOptions().isEncrypted();
                }
                //Note: the DMRMessageProcessor extracts Full Link Control messages from Voice Frames B-C and sends them
                // independent of any DMR Burst messaging.  When encountered, it can be assumed that they are part of
                // an ongoing call and can be used to establish encryption state when the FLC is a voice channel user.
                else if(message instanceof AbstractVoiceChannelUser avcu && avcu.isValid())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = avcu.getServiceOptions().isEncrypted();
                }

                if(mEncryptedCall)
                {
                    mQueuedAmbeFrames.clear();
                }
            }

            //Queue or process audio frames.  Note: audio frames are held/queued until encrypted state is established
            //before any audio is generated for the audio segment.
            if(message instanceof VoiceMessage voiceMessage)
            {
                List<byte[]> frames = voiceMessage.getAMBEFrames();
                for(byte[] frame: frames)
                {
                    processAudio(frame, message.getTimestamp());
                }
            }
            else if(message instanceof Terminator)
            {
                reset();
            }
        }
    }

    /**
     * Processes the audio frame.  Queues the frame until encryption state is determined.  Once determined, the audio
     * frames are dequeued and audio is generated.
     */
    private void processAudio(byte[] frame, long timestamp)
    {
        if(mEncryptedCallStateEstablished)
        {
            //Process any ambe frames that were queued awaiting encryption state determination
            if(!mQueuedAmbeFrames.isEmpty())
            {
                List<byte[]> queuedFrames = List.copyOf(mQueuedAmbeFrames);
                mQueuedAmbeFrames.clear();

                for(byte[] queuedFrame: queuedFrames)
                {
                    produceAudioFrame(queuedFrame, timestamp);
                }
            }

            produceAudioFrame(frame, timestamp);
        }
        else
        {
            mQueuedAmbeFrames.add(frame);
        }
    }

    private void produceAudioFrame(byte[] frame, long timestamp)
    {
        if(mEncryptedCall)
        {
            if(mVoiceFrameDecryptor == null || !mVoiceFrameDecryptor.isImplemented())
            {
                mQueuedAmbeFrames.clear();
                return;
            }

            try
            {
                frame = mVoiceFrameDecryptor.decrypt(frame);
            }
            catch(VoiceFrameDecryptionException e)
            {
                mLog.debug("Error decrypting DMR AMBE audio", e);
                mQueuedAmbeFrames.clear();
                return;
            }
        }

        produceAudio(frame, timestamp);
    }

    private void produceAudio(byte[] frame, long timestamp)
    {
        try
        {
            IAudioWithMetadata audioWithMetadata = getAudioCodec().getAudioWithMetadata(frame);
            addAudio(audioWithMetadata.getAudio(), getVoiceFrameQuality(audioWithMetadata));
            processMetadata(audioWithMetadata, timestamp);
        }
        catch(Exception e)
        {
            mLog.error("Error synthesizing DMR AMBE audio - continuing [" + e.getMessage() + "]");
        }
    }

    private VoiceFrameDecryptor createDecryptor(VoiceEncryptionContext context)
    {
        if(context == null)
        {
            return null;
        }

        return mEncryptionKeyResolver.resolve(context)
            .flatMap(key -> mVoiceFrameDecryptorFactory.create(context, key))
            .filter(VoiceFrameDecryptor::isImplemented)
            .orElse(null);
    }

    private VoiceEncryptionContext createContext(EncryptionParameters parameters)
    {
        if(parameters == null || parameters.getAlgorithm() == EncryptionAlgorithm.UNKNOWN)
        {
            return null;
        }

        return new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR, parameters.getAlgorithm().getValue(),
            parameters.getKeyId(), parameters.getInitializationVector(), getTimeslot(), getIdentifierCollection());
    }

    private VoiceEncryptionContext createEmbeddedContext(IMessage message)
    {
        if(message instanceof VoiceEMBMessage voice && voice.hasEmbeddedParameters())
        {
            EmbeddedParameters parameters = voice.getEmbeddedParameters();

            if(parameters.getShortBurst() instanceof EmbeddedEncryptionParameters encryptionParameters)
            {
                mEncryptedCallStateEstablished = true;
                mEncryptedCall = true;

                if(parameters.hasIv() && encryptionParameters.getAlgorithm() != EncryptionAlgorithm.UNKNOWN)
                {
                    return new VoiceEncryptionContext(VoiceEncryptionProtocol.DMR,
                        encryptionParameters.getAlgorithm().getValue(), encryptionParameters.getKey(), parameters.getIv(),
                        getTimeslot(), getIdentifierCollection());
                }
            }
        }

        return null;
    }

    private boolean isVoiceSuperframeStart(VoiceMessage message)
    {
        DMRSyncPattern syncPattern = message.getSyncPattern();
        return syncPattern == DMRSyncPattern.BASE_STATION_VOICE || syncPattern == DMRSyncPattern.MOBILE_STATION_VOICE ||
            syncPattern == DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1 || syncPattern == DMRSyncPattern.DIRECT_VOICE_TIMESLOT_2;
    }

    /**
     * Processes optional metadata that can be included with decoded audio (ie dtmf, tones, knox, etc.) so that the
     * tone metadata can be converted into a FROM identifier and included with any call segment.
     */
    private void processMetadata(IAudioWithMetadata audioWithMetadata, long timestamp)
    {
        boolean hasToneMetadata = false;

        if(audioWithMetadata.hasMetadata())
        {
            for(Map.Entry<String,String> entry: audioWithMetadata.getMetadata().entrySet())
            {
                if(isVoiceFrameQualityMetadata(entry.getKey()))
                {
                    continue;
                }

                hasToneMetadata = true;
                //Each metadata map entry contains a tone-type (key) and tone (value)
                ToneIdentifier metadataIdentifier = mToneMetadataProcessor.process(entry.getKey(), entry.getValue());

                if(metadataIdentifier != null)
                {
                    broadcast(metadataIdentifier, timestamp);
                }
            }
        }

        if(!hasToneMetadata)
        {
            mToneMetadataProcessor.closeMetadata();
        }
    }

    /**
     * Broadcasts the identifier to a registered listener
     */
    private void broadcast(ToneIdentifier identifier, long timestamp)
    {
        if(mIdentifierUpdateNotificationListener != null)
        {
            mIdentifierUpdateNotificationListener.receive(new IdentifierUpdateNotification(identifier,
                IdentifierUpdateNotification.Operation.ADD, getTimeslot()));
        }

        if(mMessageListener != null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("DMR Timeslot ");
            sb.append(getTimeslot());
            sb.append("Audio Tone Sequence Decoded: ");
            sb.append(identifier.toString());

            mMessageListener.receive(new ToneIdentifierMessage(Protocol.DMR, getTimeslot(), timestamp,
                    identifier, sb.toString()));
        }
    }

    /**
     * Registers the listener to receive identifier updates
     */
    @Override
    public void setIdentifierUpdateListener(Listener<IdentifierUpdateNotification> listener)
    {
        mIdentifierUpdateNotificationListener = listener;
    }

    /**
     * Unregisters a listener from receiving identifier updates
     */
    @Override
    public void removeIdentifierUpdateListener()
    {
        mIdentifierUpdateNotificationListener = null;
    }

    /**
     * Registers the listener to receive tone identifier messages from this module.
     * @param listener to receive messages
     */
    @Override
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    /**
     * Unregisters the message listener
     */
    @Override
    public void removeMessageListener()
    {
        mMessageListener = null;
    }

    /**
     * Wrapper for squelch state to process end of call actions.  At call end the encrypted call state established
     * flag is reset so that the encrypted audio state for the next call can be properly detected and we send an
     * END audio packet so that downstream processors like the audio recorder can properly close out a call sequence.
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            if(event.getTimeslot() == getTimeslot() && event.getSquelchState() == SquelchState.SQUELCH)
            {
                closeAudioSegment();
            }
        }
    }

    /**
     * Process AMBE audio frame tone metadata.  Tracks the count of sequential frames containing tone metadata to
     * provide a list of each unique tone and a time duration (milliseconds) for the tone.  Tones are concatenated into
     * a comma separated list and included as call segment metadata.
     */
    public class ToneMetadataProcessor
    {
        private List<Tone> mTones = new ArrayList<>();
        private Tone mCurrentTone;

        /**
         * Resets or clears any accumulated call tone sequences to prepare for the next call.
         */
        public void reset()
        {
            mTones.clear();
        }

        /**
         * Process the tone metadata
         * @param type of tone
         * @param value of tone
         * @return an identifier with the accumulated tone metadata set
         */
        public ToneIdentifier process(String type, String value)
        {
            if(type == null || value == null)
            {
                return null;
            }

            AmbeTone tone = AmbeTone.fromValues(type, value);

            if(tone == AmbeTone.INVALID)
            {
                return null;
            }

            if(mCurrentTone == null || mCurrentTone.getAmbeTone() != tone)
            {
                mCurrentTone = new Tone(tone);
                mTones.add(mCurrentTone);
            }

            mCurrentTone.incrementDuration();

            return DMRToneIdentifier.create(new ToneSequence(new ArrayList<>(mTones)));
        }

        /**
         * Closes current tone metadata when there is no metadata for the current audio frame.
         */
        public void closeMetadata()
        {
            mCurrentTone = null;
        }
    }
}

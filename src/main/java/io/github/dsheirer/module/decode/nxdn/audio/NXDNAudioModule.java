/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.module.decode.nxdn.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.codec.mbe.AmbeAudioModule;
import io.github.dsheirer.audio.codec.mbe.VoiceFrame;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.Framing;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Disconnect;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallInitializationVector;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInfo;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart1;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart2;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.Structure;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import jmbe.iface.IAudioWithMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NXDN AMBE audio module
 */
public class NXDNAudioModule extends AmbeAudioModule
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NXDNAudioModule.class);
    static final int MAX_PENDING_AUDIO_MESSAGES = 64;
    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private final NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private final Deque<Audio> mCachedAudioMessages = new ArrayDeque<>(MAX_PENDING_AUDIO_MESSAGES);
    private final NXDNCallSequenceRecorder.EncryptionContextTracker mEncryptionContextTracker =
        new NXDNCallSequenceRecorder.EncryptionContextTracker();
    private final VoiceEncryptionKeyResolver mKeyResolver;
    private final VoiceFrameDecryptorFactory mDecryptorFactory;
    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;
    private AudioCodec mAudioCodec;
    private VoiceFrameDecryptor mDecryptor;
    private Structure mPreviousSACCHStructure;
    private boolean mCallIdentityEstablished;
    private int mCallSource;
    private int mCallDestination;
    private CallType mCallType;
    private long mLastInitialVoiceCallTimestamp = Long.MIN_VALUE;

    /**
     * Constructs an instance
     * @param userPreferences component
     * @param aliasList for the current channel
     */
    public NXDNAudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        this(userPreferences, aliasList, CallLegSource.UNKNOWN);
    }

    public NXDNAudioModule(UserPreferences userPreferences, AliasList aliasList, CallLegSource callLegSource)
    {
        super(userPreferences, aliasList, 0, callLegSource);
        mKeyResolver = new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
        mDecryptorFactory = new VoiceFrameDecryptorFactory(userPreferences.getVoiceDecryptionModulePreference()
            .getModuleManager());
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        getIdentifierCollection().clear();
        mCachedAudioMessages.clear();
        resetEncryptionState();
        resetCallBoundaryState();
    }

    @Override
    public void start()
    {
    }

    /**
     * Processes audio and layer 3 messages to decode audio and to determine the encrypted status of a call event.
     */
    public void receive(IMessage message)
    {
        if(message == null)
        {
            return;
        }

        if(message instanceof Audio audio)
        {
            observeVoiceActivity(audio.getTimestamp());

            if(mEncryptedCall)
            {
                markCurrentCallEncrypted(audio.getTimestamp());
            }

            if(hasAudioCodec())
            {
                if(mEncryptedCallStateEstablished)
                {
                    processAudio(audio);
                }
                else
                {
                    //Cache audio only when it can eventually be decoded.  No-codec metadata lifecycle stays bounded.
                    if(mCachedAudioMessages.size() >= MAX_PENDING_AUDIO_MESSAGES)
                    {
                        mCachedAudioMessages.pollFirst();
                    }

                    mCachedAudioMessages.offerLast(audio);
                }
            }

            return;
        }

        if(!message.isValid())
        {
            return;
        }

        if(message instanceof VoiceCall voiceCall && isTrafficVoiceCall(voiceCall))
        {
            processVoiceCallBoundary(voiceCall);
            observeVoiceActivity(message.getTimestamp());
            mAudioCodec = voiceCall.getCallOption().getCodec();
            processEncryption(voiceCall.getEncryptionKeyIdentifier().getValue(), message.getTimestamp());
            processCachedAudioIfCodecAvailable();
        }
        else if(message instanceof VoiceCallInitializationVector initializationVector)
        {
            mEncryptionContextTracker.observeFullInitializationVector(
                initializationVector.getInitializationVector(), initializationVector.getTimestamp());
        }
        else if(message instanceof CallInfo callInfo)
        {
            processEncryption(callInfo.getEncryptionKey(), message.getTimestamp());
            mEncryptionContextTracker.beginTypeDSuperframe();
        }
        else if(message instanceof InitializationVectorPart1 part1)
        {
            mEncryptionContextTracker.beginTypeDSuperframe();
            mEncryptionContextTracker.observeTypeDInitializationVectorPart1(part1.getIV(),
                part1.getLICH().isOutbound());
        }
        else if(message instanceof InitializationVectorPart2 part2)
        {
            mEncryptionContextTracker.observeTypeDInitializationVectorPart2(part2.getIV(),
                part2.getLICH().isOutbound());
        }
        else if(message instanceof Disconnect || message instanceof TransmissionRelease)
        {
            closeAudioSegment(message.getTimestamp());
            mCachedAudioMessages.clear();
            resetEncryptionState();
            resetCallBoundaryState();
        }
    }

    /**
     * NXDN repeats VCALL in the SACCH throughout a transmission, so a repeated VCALL alone cannot be treated as a new
     * call.  The non-superframe FACCH1 VCALL is the explicit initial call frame.  A changed source/destination/call
     * type tuple is also a safe boundary when that initial frame was only partly decoded.  Two FACCH1 copies decoded
     * from the same RF frame share a timestamp and are deliberately coalesced.
     */
    private void processVoiceCallBoundary(VoiceCall voiceCall)
    {
        long timestamp = voiceCall.getTimestamp();
        boolean initialVoiceCall = voiceCall.getLICH() != null &&
            voiceCall.getLICH().getFraming() == Framing.SINGLE;
        boolean duplicateInitialCopy = initialVoiceCall && timestamp == mLastInitialVoiceCallTimestamp;
        int source = voiceCall.getSource().getValue();
        int destination = voiceCall.getDestination().getValue();
        CallType callType = voiceCall.getCallType();
        boolean certainIdentity = source > 0 && destination > 0 && isVoiceCallType(callType);
        boolean identityChanged = certainIdentity && mCallIdentityEstablished &&
            (source != mCallSource || destination != mCallDestination || callType != mCallType);

        if(getCurrentAudioCall() != null && !duplicateInitialCopy && (initialVoiceCall || identityChanged))
        {
            closeAudioSegment(timestamp);
            mCachedAudioMessages.clear();
            resetEncryptionState();
            resetCallBoundaryState();
        }

        if(certainIdentity && (!duplicateInitialCopy || !mCallIdentityEstablished))
        {
            mCallSource = source;
            mCallDestination = destination;
            mCallType = callType;
            mCallIdentityEstablished = true;
        }

        if(initialVoiceCall)
        {
            mLastInitialVoiceCallTimestamp = timestamp;
        }
    }

    private static boolean isTrafficVoiceCall(VoiceCall voiceCall)
    {
        NXDNMessageType type = voiceCall.getMessageType();

        return type == NXDNMessageType.TRAFFIC_IN_01_CC_VOICE_CALL ||
            type == NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL ||
            type == NXDNMessageType.TYPE_D_IN_01_CC_VOICE_CALL ||
            type == NXDNMessageType.TYPE_D_OUT_01_CC_VOICE_CALL;
    }

    private static boolean isVoiceCallType(CallType callType)
    {
        return callType != null && callType != CallType.TRANSMISSION_RELEASE &&
            callType != CallType.RESERVED && callType != CallType.UNKNOWN;
    }

    private void resetCallBoundaryState()
    {
        mCallIdentityEstablished = false;
        mCallSource = 0;
        mCallDestination = 0;
        mCallType = null;
        mLastInitialVoiceCallTimestamp = Long.MIN_VALUE;
    }

    private void observeVoiceActivity(long timestamp)
    {
        if(getCurrentAudioCall() == null)
        {
            beginCurrentAudioSegment(timestamp);
            beginCurrentAudioBurst(timestamp);
        }
        else
        {
            touchCurrentAudioSegment(timestamp);
        }
    }

    /**
     * Processes any cached audio frames that were pending an encryption state determination.
     */
    private void processCachedAudio()
    {
        Audio audio;

        while((audio = mCachedAudioMessages.pollFirst()) != null)
        {
            processAudio(audio);
        }
    }

    private void processCachedAudioIfCodecAvailable()
    {
        if(hasAudioCodec())
        {
            processCachedAudio();
        }
        else
        {
            mCachedAudioMessages.clear();
        }
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(Audio audio)
    {
        if(mAudioCodec != null && mAudioCodec.equals(AudioCodec.HALF_RATE)) //Full rate not yet supported
        {
            skipMissingVoicePositions(audio);
            long timestamp = audio.getTimestamp();
            mEncryptionContextTracker.beginAudioFrame(timestamp);

            for(byte[] frame : audio.getAudioFrames())
            {
                VoiceFrame marker = mEncryptionContextTracker.createVoiceFrame(timestamp,
                    BinaryMessage.from(frame).toHexString());

                if(marker.getAlgorithm() != null || marker.getKeyId() != null)
                {
                    updateDecryptor(marker);
                }

                if(!mEncryptedCall || mDecryptor != null)
                {
                    try
                    {
                        byte[] decodedFrame = mEncryptedCall ? mDecryptor.decrypt(frame) : frame;
                        IAudioWithMetadata audioWithMetadata =
                            getAudioCodec().getAudioWithMetadata(decodedFrame);
                        float[] generatedAudio = mGain.apply(audioWithMetadata.getAudio());
                        addAudio(generatedAudio, getVoiceFrameQuality(audioWithMetadata), timestamp);
                    }
                    catch(VoiceFrameDecryptionException e)
                    {
                        LOGGER.warn("Unable to decrypt NXDN voice frame: {}", e.getMessage());
                        mDecryptor = null;
                    }
                }

                timestamp += 20;
            }
        }
    }

    /**
     * At 9600 bps/EHR, alternating RF frames carry FACCH1 instead of four VCH positions.  NXDN encryption advances
     * across those absent positions, so preserve alignment using the SACCH 1-of-4 sequence carried by voice frames.
     */
    private void skipMissingVoicePositions(Audio audio)
    {
        if(!audio.hasSACCHFragment())
        {
            mPreviousSACCHStructure = null;
            return;
        }

        Structure current = audio.getSACCHFragment().getStructure();
        int previousIndex = getSACCHIndex(mPreviousSACCHStructure);
        int currentIndex = getSACCHIndex(current);

        if(mDecryptor != null && previousIndex >= 0 && currentIndex >= 0)
        {
            int advance = (currentIndex - previousIndex + 4) % 4;
            int missingFrames = Math.max(0, advance - 1) * 4;

            if(missingFrames > 0)
            {
                try
                {
                    mDecryptor.skipVoiceFrames(missingFrames);
                }
                catch(VoiceFrameDecryptionException e)
                {
                    LOGGER.warn("Unable to align NXDN voice decryption: {}", e.getMessage());
                    mDecryptor = null;
                }
            }
        }

        mPreviousSACCHStructure = current;
    }

    private static int getSACCHIndex(Structure structure)
    {
        if(structure == null)
        {
            return -1;
        }

        return switch(structure)
        {
            case SACCH_1_OF_4 -> 0;
            case SACCH_2_OF_4 -> 1;
            case SACCH_3_OF_4 -> 2;
            case SACCH_4_OF_4_LAST_OR_SINGLE -> 3;
            default -> -1;
        };
    }

    private void processEncryption(EncryptionKey encryptionKey, long timestamp)
    {
        mEncryptionContextTracker.update(encryptionKey);
        mEncryptedCall = encryptionKey != null && encryptionKey.isEncrypted();
        mEncryptedCallStateEstablished = encryptionKey != null;

        if(mEncryptedCall && getCurrentAudioCall() != null)
        {
            markCurrentCallEncrypted(timestamp);
        }
        else
        {
            mDecryptor = null;
        }
    }

    private void updateDecryptor(VoiceFrame marker)
    {
        if(marker.getAlgorithm() == null || marker.getKeyId() == null)
        {
            mDecryptor = null;
            return;
        }

        VoiceEncryptionContext context = new VoiceEncryptionContext(VoiceEncryptionProtocol.NXDN,
            marker.getAlgorithm(), marker.getKeyId(), marker.getMessageIndicator(), 0,
            getIdentifierCollection());
        mDecryptor = mKeyResolver.resolve(context)
            .flatMap(key -> mDecryptorFactory.create(context, key))
            .filter(VoiceFrameDecryptor::isImplemented)
            .orElse(null);
    }

    private void resetEncryptionState()
    {
        mEncryptedCall = false;
        mEncryptedCallStateEstablished = false;
        mAudioCodec = null;
        mDecryptor = null;
        mPreviousSACCHStructure = null;
        mEncryptionContextTracker.reset();
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
            if(event.getSquelchState() == SquelchState.SQUELCH)
            {
                closeAudioSegment();
                mCachedAudioMessages.clear();
                resetEncryptionState();
                resetCallBoundaryState();
            }
        }
    }
}

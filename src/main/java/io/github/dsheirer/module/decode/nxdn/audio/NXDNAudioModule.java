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
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Disconnect;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallInitializationVector;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallWithOptionalLocation;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInfo;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart1;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart2;
import io.github.dsheirer.module.decode.nxdn.layer3.type.AudioCodec;
import io.github.dsheirer.module.decode.nxdn.layer3.type.Structure;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NXDN AMBE audio module
 */
public class NXDNAudioModule extends AmbeAudioModule
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NXDNAudioModule.class);
    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private final NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private final List<Audio> mCachedAudioMessages = new ArrayList<>();
    private final NXDNCallSequenceRecorder.EncryptionContextTracker mEncryptionContextTracker =
        new NXDNCallSequenceRecorder.EncryptionContextTracker();
    private final VoiceEncryptionKeyResolver mKeyResolver;
    private final VoiceFrameDecryptorFactory mDecryptorFactory;
    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;
    private AudioCodec mAudioCodec;
    private VoiceFrameDecryptor mDecryptor;
    private Structure mPreviousSACCHStructure;

    /**
     * Constructs an instance
     * @param userPreferences component
     * @param aliasList for the current channel
     */
    public NXDNAudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList, 0);
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
        if(hasAudioCodec())
        {
            if(message instanceof Audio audio)
            {
                if(mEncryptedCallStateEstablished)
                {
                    processAudio(audio);
                }
                else
                {
                    //Cache audio until we can determine the encryption state
                    mCachedAudioMessages.add(audio);
                }
            }
            else if(message.isValid())
            {
                if(message instanceof VoiceCall voiceCall)
                {
                    mEncryptedCall = voiceCall.getEncryptionKeyIdentifier().isEncrypted();
                    mEncryptedCallStateEstablished = true;
                    mAudioCodec = voiceCall.getCallOption().getCodec();
                    processEncryption(voiceCall.getEncryptionKeyIdentifier().getValue());
                    processCachedAudio();
                }
                else if(message instanceof VoiceCallWithOptionalLocation voiceCall)
                {
                    mEncryptedCall = voiceCall.getEncryptionKeyIdentifier().isEncrypted();
                    mEncryptedCallStateEstablished = true;
                    mAudioCodec = voiceCall.getCallOption().getCodec();
                    processEncryption(voiceCall.getEncryptionKeyIdentifier().getValue());
                    processCachedAudio();
                }
                else if(message instanceof VoiceCallInitializationVector initializationVector)
                {
                    mEncryptionContextTracker.observeFullInitializationVector(
                        initializationVector.getInitializationVector(), initializationVector.getTimestamp());
                }
                else if(message instanceof CallInfo callInfo)
                {
                    processEncryption(callInfo.getEncryptionKey());
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
                    closeAudioSegment();
                    mCachedAudioMessages.clear();
                    resetEncryptionState();
                }
            }
        }
    }

    /**
     * Processes any cached audio frames that were pending an encryption state determination.
     */
    private void processCachedAudio()
    {
        for(Audio audio : mCachedAudioMessages)
        {
            processAudio(audio);
        }

        mCachedAudioMessages.clear();
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
                        float[] generatedAudio = getAudioCodec().getAudio(decodedFrame);
                        generatedAudio = mGain.apply(generatedAudio);
                        addAudio(generatedAudio);
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

    private void processEncryption(EncryptionKey encryptionKey)
    {
        mEncryptionContextTracker.update(encryptionKey);

        if(!mEncryptionContextTracker.isEncrypted())
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
            }
        }
    }
}

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


import io.github.dsheirer.audio.codec.mbe.MBECallSequence;
import io.github.dsheirer.audio.codec.mbe.MBECallSequenceRecorder;
import io.github.dsheirer.audio.codec.mbe.VoiceFrame;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.nxdn.NXDNMessage;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNLayer3Message;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallInitializationVector;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInProgressDestinationInfo2;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInProgressDestinationInfo4;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInProgressSourceID;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.CallInfo;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart1;
import io.github.dsheirer.module.decode.nxdn.layer3.scch.InitializationVectorPart2;
import io.github.dsheirer.preference.UserPreferences;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NXDN AMBE+ Frame recorder generates call sequence recordings containing JSON representations of audio
 * frames, optional encryption and call identifiers.
 */
public class NXDNCallSequenceRecorder extends MBECallSequenceRecorder
{
    private final static Logger mLog = LoggerFactory.getLogger(NXDNCallSequenceRecorder.class);
    public static final String PROTOCOL = "NXDN";
    private final EncryptionContextTracker mEncryptionContextTracker = new EncryptionContextTracker();
    private MBECallSequence mCallSequence;

    /**
     * Constructs an instance
     *
     * @param userPreferences to obtain the recording directory
     * @param channelFrequency for the channel to record
     * @param system defined by the user
     * @param site defined by the user
     */
    public NXDNCallSequenceRecorder(UserPreferences userPreferences, long channelFrequency, String system, String site)
    {
        super(userPreferences, channelFrequency, system, site);
    }

    /**
     * Stops and flushes any partial frame sequence from the processors
     */
    @Override
    public void stop()
    {
        flush();
    }

    /**
     * Primary message interface for receiving frames and metadata messages to record
     */
    @Override
    public void receive(IMessage message)
    {
        if(message instanceof NXDNMessage nxdn)
        {
            if(nxdn.isValid())
            {
                if(nxdn instanceof Audio audio)
                {
                    process(audio);
                }
                else if(nxdn instanceof NXDNLayer3Message layer3)
                {
                    process(layer3);
                }
            }
        }
    }

    /**
     * Gets and optionally creates a new call sequence.
     */
    private MBECallSequence getCallSequence()
    {
        if(mCallSequence == null)
        {
            mCallSequence = new MBECallSequence(PROTOCOL);
        }

        return mCallSequence;
    }


    /**
     * Flushes any partial call sequence
     */
    public void flush()
    {
        if(mCallSequence != null)
        {
            writeCallSequence(mCallSequence);
            mCallSequence = null;
        }

        mEncryptionContextTracker.reset();
    }

    /**
     * Processes any NXDN layer 3 message
     */
    public void process(NXDNLayer3Message layer3)
    {
        switch(layer3.getMessageType())
        {
            case TRAFFIC_IN_01_CC_VOICE_CALL:
            case TRAFFIC_OUT_01_CC_VOICE_CALL:
            case TYPE_D_IN_01_CC_VOICE_CALL:
            case TYPE_D_OUT_01_CC_VOICE_CALL:
                if(layer3 instanceof VoiceCall vc)
                {
                    getCallSequence().setCallType(vc.getCallType().toString());
                    getCallSequence().setFromIdentifier(vc.getSource());
                    getCallSequence().setToIdentifier(vc.getDestination());
                    getCallSequence().setCodec(vc.getCallOption().getCodec().name());

                    processEncryption(vc.getEncryptionKeyIdentifier().getValue());
                }
                break;
            case TRAFFIC_IN_03_CC_VOICE_CALL_INITIALIZATION_VECTOR:
            case TRAFFIC_OUT_03_CC_VOICE_CALL_INITIALIZATION_VECTOR:
            case TYPE_D_IN_03_CC_VOICE_CALL_INITIALIZATION_VECTOR:
            case TYPE_D_OUT_03_CC_VOICE_CALL_INITIALIZATION_VECTOR:
                if(layer3 instanceof VoiceCallInitializationVector iv)
                {
                    mEncryptionContextTracker.observeFullInitializationVector(iv.getInitializationVector(),
                        iv.getTimestamp());
                }
                break;
            case TRAFFIC_OUT_07_CC_TRANSMISSION_RELEASE_EXTENSION:
            case TRAFFIC_OUT_08_CC_TRANSMISSION_RELEASE:
            case TYPE_D_OUT_07_CC_TRANSMISSION_RELEASE_EXTENSION:
            case TYPE_D_OUT_08_CC_TRANSMISSION_RELEASE:
                flush();
                break;
            case TYPE_D_SCCH_IN_INFO_4_CALL_IN_PROGRESS_DESTINATION:
            case TYPE_D_SCCH_OUT_INFO_4_CALL_IN_PROGRESS_DESTINATION:
                if(layer3 instanceof CallInProgressDestinationInfo4 info4)
                {
                    getCallSequence().setToIdentifier(info4.getDestination());
                }
                break;
            case TYPE_D_SCCH_IN_INFO_3_CALL_IN_PROGRESS_SOURCE:
            case TYPE_D_SCCH_OUT_INFO_3_CALL_IN_PROGRESS_SOURCE:
                if(layer3 instanceof CallInProgressSourceID source)
                {
                    getCallSequence().setFromIdentifier(source.getSource());
                }
                break;
            case TYPE_D_SCCH_IN_INFO_2_CALL_IN_PROGRESS_DESTINATION:
            case TYPE_D_SCCH_OUT_INFO_2_CALL_IN_PROGRESS_DESTINATION:
                if(layer3 instanceof CallInProgressDestinationInfo2 info2)
                {
                    getCallSequence().setToIdentifier(info2.getDestination());
                }
                break;
            case TYPE_D_SCCH_IN_INFO_1_CALL_INFO:
            case TYPE_D_SCCH_OUT_INFO_1_CALL_INFO:
                if(layer3 instanceof CallInfo ci)
                {
                    processEncryption(ci.getEncryptionKey());
                    mEncryptionContextTracker.beginTypeDSuperframe();
                }
            break;
            case TYPE_D_SCCH_IN_INFO_1_INITIALIZATION_VECTOR_PART1:
            case TYPE_D_SCCH_OUT_INFO_1_INITIALIZATION_VECTOR_PART1:
                if(layer3 instanceof InitializationVectorPart1 part1)
                {
                    mEncryptionContextTracker.beginTypeDSuperframe();
                    mEncryptionContextTracker.observeTypeDInitializationVectorPart1(part1.getIV(),
                        part1.getLICH().isOutbound());
                }
                break;
            case TYPE_D_SCCH_IN_INFO_3_INITIALIZATION_VECTOR_PART2:
            case TYPE_D_SCCH_OUT_INFO_3_INITIALIZATION_VECTOR_PART2:
                if(layer3 instanceof InitializationVectorPart2 part2)
                {
                    mEncryptionContextTracker.observeTypeDInitializationVectorPart2(part2.getIV(),
                        part2.getLICH().isOutbound());
                }
                break;
        }
    }

    /**
     * Captures the transmitted NXDN cipher type and key ID.  IV messages are handled separately so that each new IV
     * is aligned to its specification-defined voice-frame boundary.
     */
    private void processEncryption(EncryptionKey encryptionKey)
    {
        mEncryptionContextTracker.update(encryptionKey);

        if(mEncryptionContextTracker.isEncrypted())
        {
            getCallSequence().setEncrypted(true);
        }
    }

    /**
     * Process audio messages
     */
    private void process(Audio audio)
    {
        List<byte[]> voiceFrames = audio.getAudioFrames();
        long timestamp = audio.getTimestamp();
        mEncryptionContextTracker.beginAudioFrame(timestamp);

        for(int frame = 0; frame < voiceFrames.size(); frame++)
        {
            BinaryMessage frameBits = BinaryMessage.from(voiceFrames.get(frame));
            VoiceFrame voiceFrame = mEncryptionContextTracker.createVoiceFrame(timestamp, frameBits.toHexString());

            if(frame == 0)
            {
                if(audio.hasSACCHFragment())
                {
                    voiceFrame.setTag(audio.getSACCHFragment().getStructure().toString());
                }
            }

            getCallSequence().add(voiceFrame);
            //Voice frames are 20 milliseconds each, so we increment the timestamp by 20 for each one
            timestamp += 20;
        }
    }

    /**
     * Tracks signaling-derived NXDN encryption metadata and emits a context marker on the next voice frame whenever
     * the transmitted cipher type or key ID changes.
     */
    static class EncryptionContextTracker
    {
        private Integer mAlgorithm;
        private Integer mKeyId;
        private String mMessageIndicator;
        private String mPendingMessageIndicator;
        private long mPendingObservedTimestamp;
        private boolean mPendingRequiresTypeDSuperframe;
        private Integer mTypeDInitializationVectorPart1;
        private Boolean mTypeDInitializationVectorPart1Outbound;
        private boolean mDirty;

        void update(EncryptionKey encryptionKey)
        {
            if(encryptionKey != null && encryptionKey.isEncrypted())
            {
                if(!Objects.equals(mAlgorithm, encryptionKey.getAlgorithm()) ||
                    !Objects.equals(mKeyId, encryptionKey.getKey()))
                {
                    boolean replacingExistingContext = isEncrypted();
                    mAlgorithm = encryptionKey.getAlgorithm();
                    mKeyId = encryptionKey.getKey();

                    if(replacingExistingContext)
                    {
                        mMessageIndicator = null;
                        clearPendingInitializationVector();
                    }

                    mDirty = true;
                }
            }
            else
            {
                reset();
            }
        }

        boolean isEncrypted()
        {
            return mAlgorithm != null && mKeyId != null;
        }

        /**
         * Captures the full VCALL_IV carried in FACCH1 or an assembled SACCH message.  The specification applies this
         * IV starting with the RF frame after the frame that carried the completed message (NXDN TS 1-D 5.4.4.2 and
         * NXDN TS 1-F 16.4.4.2).
         */
        void observeFullInitializationVector(String messageIndicator, long timestamp)
        {
            if(messageIndicator != null)
            {
                mPendingMessageIndicator = messageIndicator;
                mPendingObservedTimestamp = timestamp;
                mPendingRequiresTypeDSuperframe = false;
            }
        }

        /**
         * Promotes a full VCALL_IV only after advancing beyond the RF frame that carried it.  This method is called
         * once per RF audio frame, before its individual vocoder frames are serialized.
         */
        void beginAudioFrame(long timestamp)
        {
            if(mPendingMessageIndicator != null && !mPendingRequiresTypeDSuperframe &&
                timestamp > mPendingObservedTimestamp)
            {
                promotePendingInitializationVector();
            }
        }

        /**
         * Marks the start of a Type-D superframe.  A 23-bit IV assembled during the preceding even-numbered
         * superframe becomes active here for the next two-superframe encryption session (NXDN TS 1-F 16.4.4.2).
         */
        void beginTypeDSuperframe()
        {
            if(mPendingMessageIndicator != null && mPendingRequiresTypeDSuperframe)
            {
                promotePendingInitializationVector();
            }

            mTypeDInitializationVectorPart1 = null;
            mTypeDInitializationVectorPart1Outbound = null;
        }

        void observeTypeDInitializationVectorPart1(int part1, boolean outbound)
        {
            mTypeDInitializationVectorPart1 = part1 & 0x7FF;
            mTypeDInitializationVectorPart1Outbound = outbound;
        }

        void observeTypeDInitializationVectorPart2(int part2, boolean outbound)
        {
            if(mTypeDInitializationVectorPart1 != null &&
                Objects.equals(mTypeDInitializationVectorPart1Outbound, outbound))
            {
                int initializationVector = (mTypeDInitializationVectorPart1 << 12) | (part2 & 0xFFF);
                mPendingMessageIndicator = String.format("%06X", initializationVector);
                mPendingRequiresTypeDSuperframe = true;
            }
        }

        VoiceFrame createVoiceFrame(long timestamp, String frame)
        {
            if(mDirty && isEncrypted())
            {
                mDirty = false;
                return new VoiceFrame(timestamp, frame, mAlgorithm, mKeyId, mMessageIndicator);
            }

            return new VoiceFrame(timestamp, frame);
        }

        void reset()
        {
            mAlgorithm = null;
            mKeyId = null;
            mMessageIndicator = null;
            clearPendingInitializationVector();
            mTypeDInitializationVectorPart1 = null;
            mTypeDInitializationVectorPart1Outbound = null;
            mDirty = false;
        }

        private void promotePendingInitializationVector()
        {
            if(!Objects.equals(mMessageIndicator, mPendingMessageIndicator))
            {
                mMessageIndicator = mPendingMessageIndicator;
                mDirty = true;
            }

            clearPendingInitializationVector();
        }

        private void clearPendingInitializationVector()
        {
            mPendingMessageIndicator = null;
            mPendingObservedTimestamp = 0;
            mPendingRequiresTypeDSuperframe = false;
        }
    }
}

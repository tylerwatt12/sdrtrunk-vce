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
package io.github.dsheirer.module.decode.p25.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.gain.NonClippingGain;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P25P1AudioModule extends ImbeAudioModule
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P1AudioModule.class);
    private boolean mEncryptedCall = false;
    private boolean mEncryptedCallStateEstablished = false;

    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private List<LDUMessage> mCachedLDUMessages = new ArrayList<>();

    public P25P1AudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        super(userPreferences, aliasList);
    }

    @Override
    protected int getTimeslot()
    {
        return 0;
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void reset()
    {
        AudioSegment currentAudioSegment = getCurrentAudioSegment();

        if(currentAudioSegment != null)
        {
            mLog.warn("P25P1 reset with open audio segment:{} buffers:{} complete:{} encryptedStateEstablished:{} encrypted:{} cachedLdus:{}",
                formatSegment(currentAudioSegment), currentAudioSegment.getAudioBufferCount(),
                currentAudioSegment.isComplete(), mEncryptedCallStateEstablished, mEncryptedCall,
                mCachedLDUMessages.size());
        }

        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
        // No startup work is required beyond base-class construction for this audio module.
    }

    @Override
    public void stop()
    {
        closeAudioSegment("stop");
    }

    /**
     * Processes call header (HDU) and voice frame (LDU1/LDU2) messages to decode audio and to determine the
     * encrypted audio status of a call event. Only the HDU and LDU2 messages convey encrypted call status. If an
     * LDU1 message is received without a preceding HDU message, then the LDU1 message is cached until the first
     * LDU2 message is received and the encryption state can be determined. Both the LDU1 and the LDU2 message are
     * then processed for audio if the call is unencrypted.
     */
    public void receive(IMessage message)
    {
        if(hasAudioCodec())
        {
            if(getCurrentAudioSegment() != null && shouldTouchSegment(message))
            {
                touchCurrentAudioSegment();
            }

            if(mEncryptedCallStateEstablished)
            {
                if(message instanceof LDUMessage ldu)
                {
                    processAudio(ldu);
                }
            }
            else
            {
                if(message instanceof HDUMessage hdu && hdu.isValid())
                {
                    mEncryptedCallStateEstablished = true;
                    mEncryptedCall = hdu.getHeaderData().isEncryptedAudio();

                    if(!mEncryptedCall)
                    {
                        beginCurrentAudioSegment();
                        beginCurrentAudioBurst();
                    }
                }
                else if(message instanceof LDU1Message ldu1)
                {
                    //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                    //until we can determine the encrypted call state from the next LDU2 message
                    mCachedLDUMessages.add(ldu1);
                }
                else if(message instanceof LDU2Message ldu2)
                {
                    if(ldu2.getEncryptionSyncParameters().isValid())
                    {
                        mEncryptedCallStateEstablished = true;
                        mEncryptedCall = ldu2.getEncryptionSyncParameters().isEncryptedAudio();

                        if(!mEncryptedCall)
                        {
                            beginCurrentAudioSegment();
                            beginCurrentAudioBurst();
                        }
                    }

                    if(mEncryptedCallStateEstablished)
                    {
                        for(LDUMessage cachedLdu : mCachedLDUMessages)
                        {
                            processAudio(cachedLdu);
                        }

                        mCachedLDUMessages.clear();
                        processAudio(ldu2);
                    }
                    else
                    {
                        mCachedLDUMessages.add(ldu2);
                    }
                }
            }
        }
    }

    /**
     * Indicates whether the message confirms an already-open segment is still intentionally alive.
     */
    private boolean shouldTouchSegment(IMessage message)
    {
        return (message instanceof HDUMessage hdu && hdu.isValid()) ||
            (message instanceof LDUMessage ldu && ldu.isValid());
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(!mEncryptedCall)
        {
            if(!mEncryptedCallStateEstablished)
            {
                mLog.warn("P25P1 processing clear audio without established encrypted state cachedLdus:{}",
                    mCachedLDUMessages.size());
            }

            for(byte[] frame : ldu.getIMBEFrames())
            {
                AudioSegment currentAudioSegment = getAudioSegment();

                if(!currentAudioSegment.isBurstActive())
                {
                    beginCurrentAudioBurst();
                }

                float[] audio = getAudioCodec().getAudio(frame);
                audio = mGain.apply(audio);
                addAudio(audio);
            }
        }
        else
        {
            //Encrypted audio processing not implemented
        }
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
                closeAudioSegment("squelch");
                mEncryptedCallStateEstablished = false;
                mEncryptedCall = false;
                mCachedLDUMessages.clear();
            }
        }
    }

    private void closeAudioSegment(String reason)
    {
        endCurrentAudioBurst();
        AudioSegment currentAudioSegment = getCurrentAudioSegment();

        if(currentAudioSegment != null)
        {
            mLog.debug("P25P1 closing audio segment reason:{} segment:{} buffers:{} complete:{} encryptedStateEstablished:{} encrypted:{} cachedLdus:{}",
                reason, formatSegment(currentAudioSegment), currentAudioSegment.getAudioBufferCount(),
                currentAudioSegment.isComplete(), mEncryptedCallStateEstablished, mEncryptedCall,
                mCachedLDUMessages.size());
        }

        super.closeAudioSegment();
    }

    private String formatSegment(AudioSegment audioSegment)
    {
        if(audioSegment == null)
        {
            return "null";
        }

        return audioSegment.getTimeslot() + ":" + audioSegment.getStartTimestamp() + ":" +
            System.identityHashCode(audioSegment);
    }
}

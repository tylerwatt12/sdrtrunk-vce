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
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventListener;
import io.github.dsheirer.channel.state.State;
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

public class P25P1AudioModule extends ImbeAudioModule implements IDecoderStateEventListener
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P1AudioModule.class);
    private static final long LONG_AUDIO_GAP_LOG_THRESHOLD_MS = 1000;
    private P25AudioEncryptionState mEncryptionState = P25AudioEncryptionState.UNKNOWN;
    private State mCurrentDecoderState = State.IDLE;

    private DecoderStateEventListener mDecoderStateEventListener = new DecoderStateEventListener();
    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private NonClippingGain mGain = new NonClippingGain(5.0f, 0.95f);
    private List<LDUMessage> mPendingEncryptionLdus = new ArrayList<>();
    private List<LDUMessage> mDeferredClearAudioLdus = new ArrayList<>();
    private long mLastAudioTimestamp = Long.MIN_VALUE;
    private String mLastAudioSegmentId;

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
    public Listener<DecoderStateEvent> getDecoderStateListener()
    {
        return mDecoderStateEventListener;
    }

    @Override
    public void reset()
    {
        MutableAudioCallBuilder currentAudioCall = getCurrentAudioCall();

        if(currentAudioCall != null)
        {
            mLog.warn("P25P1 reset with open audio segment:{} buffers:{} complete:{} encryptedStateEstablished:{} encrypted:{} cachedLdus:{}",
                formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                currentAudioCall.isComplete(), mEncryptionState.isEstablished(), mEncryptionState.isEncrypted(),
                getCachedLduCount());
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
        if(!hasAudioCodec())
        {
            return;
        }

        if(getCurrentAudioCall() != null && shouldTouchSegment(message))
        {
            touchCurrentAudioSegment();
        }

        if(mEncryptionState.isEstablished())
        {
            if(message instanceof LDUMessage ldu)
            {
                if(isCallActiveState(mCurrentDecoderState))
                {
                    processAudio(ldu);
                }
                else
                {
                    mLog.debug("P25P1 deferring LDU audio state:{}", mCurrentDecoderState);
                    mDeferredClearAudioLdus.add(ldu);
                }
            }
        }
        else
        {
            if(message instanceof HDUMessage hdu && hdu.isValid())
            {
                mEncryptionState = P25AudioEncryptionState.fromEncrypted(hdu.getHeaderData().isEncryptedAudio());

                if(mEncryptionState.isClear())
                {
                    beginAudioIfStateActive();
                }
            }
            else if(message instanceof LDU1Message ldu1)
            {
                //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                //until we can determine the encrypted call state from the next LDU2 message
                mPendingEncryptionLdus.add(ldu1);
            }
            else if(message instanceof LDU2Message ldu2)
            {
                if(ldu2.getEncryptionSyncParameters().isValid())
                {
                    mEncryptionState = P25AudioEncryptionState.fromEncrypted(ldu2.getEncryptionSyncParameters()
                        .isEncryptedAudio());

                    if(mEncryptionState.isClear())
                    {
                        beginAudioIfStateActive();
                    }
                }

                if(mEncryptionState.isEstablished())
                {
                    promotePendingEncryptionLdus();

                    if(isCallActiveState(mCurrentDecoderState))
                    {
                        tryActivateDeferredAudio();
                    }
                    else
                    {
                        mLog.debug("P25P1 deferring clear audio state:{} deferred:{}", mCurrentDecoderState,
                            mDeferredClearAudioLdus.size());
                    }
                }
                else
                {
                    mPendingEncryptionLdus.add(ldu2);
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

    private void beginAudioIfStateActive()
    {
        if(isCallActiveState(mCurrentDecoderState))
        {
            beginCurrentAudioSegment();
            beginCurrentAudioBurst();
        }
        else
        {
            mLog.debug("P25P1 deferring audio segment creation state:{}", mCurrentDecoderState);
        }
    }

    private void promotePendingEncryptionLdus()
    {
        if(mPendingEncryptionLdus.isEmpty())
        {
            return;
        }

        if(mEncryptionState.isClear())
        {
            mDeferredClearAudioLdus.addAll(mPendingEncryptionLdus);
        }

        mPendingEncryptionLdus.clear();
    }

    private void tryActivateDeferredAudio()
    {
        if(!mEncryptionState.isClear() || !isCallActiveState(mCurrentDecoderState) || mDeferredClearAudioLdus.isEmpty())
        {
            return;
        }

        beginAudioIfStateActive();

        for(LDUMessage deferredLdu : mDeferredClearAudioLdus)
        {
            processAudio(deferredLdu);
        }

        mDeferredClearAudioLdus.clear();
    }

    private int getCachedLduCount()
    {
        return mPendingEncryptionLdus.size() + mDeferredClearAudioLdus.size();
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(mEncryptionState.isClear())
        {
            if(!mEncryptionState.isEstablished())
            {
                mLog.warn("P25P1 processing clear audio without established encrypted state cachedLdus:{}",
                    getCachedLduCount());
            }

            for(byte[] frame : ldu.getIMBEFrames())
            {
                MutableAudioCallBuilder currentAudioCall = getAudioCall();
                String currentSegmentId = formatSegment(currentAudioCall);

                if(!currentAudioCall.isBurstActive())
                {
                    if(currentAudioCall.getAudioBufferCount() > 0)
                    {
                        mLog.warn("P25P1 audio resumed on inactive burst segment:{} buffers:{} complete:{} encryptedStateEstablished:{} cachedLdus:{}",
                            currentSegmentId, currentAudioCall.getAudioBufferCount(), currentAudioCall.isComplete(),
                            mEncryptionState.isEstablished(), getCachedLduCount());
                    }

                    beginCurrentAudioBurst();
                }

                long timestamp = ldu.getTimestamp();

                if(mLastAudioTimestamp != Long.MIN_VALUE && currentSegmentId.equals(mLastAudioSegmentId))
                {
                    long gap = timestamp - mLastAudioTimestamp;

                    if(gap >= LONG_AUDIO_GAP_LOG_THRESHOLD_MS)
                    {
                        mLog.warn("P25P1 audio resumed after long gap segment:{} gapMs:{} buffers:{} burstActive:{} encryptedStateEstablished:{} cachedLdus:{}",
                            currentSegmentId, gap, currentAudioCall.getAudioBufferCount(), currentAudioCall.isBurstActive(),
                            mEncryptionState.isEstablished(), getCachedLduCount());
                    }
                }

                float[] audio = getAudioCodec().getAudio(frame);
                audio = mGain.apply(audio);
                addAudio(audio);
                mLastAudioTimestamp = timestamp;
                mLastAudioSegmentId = currentSegmentId;
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
                mEncryptionState = P25AudioEncryptionState.UNKNOWN;
                mPendingEncryptionLdus.clear();
                mDeferredClearAudioLdus.clear();
            }
        }
    }

    private void closeAudioSegment(String reason)
    {
        endCurrentAudioBurst();
        MutableAudioCallBuilder currentAudioCall = getCurrentAudioCall();

        logAnomalousClose(reason, null, currentAudioCall);

        super.closeAudioSegment();
    }

    private String formatSegment(MutableAudioCallBuilder audioCall)
    {
        if(audioCall == null)
        {
            return "null";
        }

        return audioCall.getTimeslot() + ":" + audioCall.getStartTimestamp() + ":" +
            System.identityHashCode(audioCall);
    }

    private boolean isCallActiveState(State state)
    {
        return state == State.CALL || state == State.ENCRYPTED;
    }

    private void logAnomalousClose(String reason, State state, MutableAudioCallBuilder currentAudioCall)
    {
        if(currentAudioCall == null)
        {
            return;
        }

        boolean suspiciousControlAudio = state == State.CONTROL && currentAudioCall.getAudioBufferCount() > 0;
        boolean unresolvedEncryption = !mEncryptionState.isEstablished();
        boolean cachedLdusPresent = getCachedLduCount() > 0;

        if(suspiciousControlAudio || unresolvedEncryption || cachedLdusPresent)
        {
            if(state != null)
            {
                mLog.warn("P25P1 anomalous close reason:{} state:{} segment:{} buffers:{} bursts:{} burstActive:{} complete:{} encryptedStateEstablished:{} encrypted:{} cachedLdus:{}",
                    reason, state, formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                    currentAudioCall.getBurstCount(), currentAudioCall.isBurstActive(), currentAudioCall.isComplete(),
                    mEncryptionState.isEstablished(), mEncryptionState.isEncrypted(), getCachedLduCount());
            }
            else
            {
                mLog.warn("P25P1 anomalous close reason:{} segment:{} buffers:{} bursts:{} burstActive:{} complete:{} encryptedStateEstablished:{} encrypted:{} cachedLdus:{}",
                    reason, formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                    currentAudioCall.getBurstCount(), currentAudioCall.isBurstActive(), currentAudioCall.isComplete(),
                    mEncryptionState.isEstablished(), mEncryptionState.isEncrypted(), getCachedLduCount());
            }
        }
    }

    public class DecoderStateEventListener implements Listener<DecoderStateEvent>
    {
        /**
         * Closes the current call without emitting the normal close log. This is only used for the benign
         * control-state suppression case where no audio was ever committed.
         */
        private void closeAudioSegmentSilently()
        {
            endCurrentAudioBurst();
            P25P1AudioModule.super.closeAudioSegment();
        }

        private void closeAudioSegmentForDecoderState(String reason, State state)
        {
            MutableAudioCallBuilder currentAudioCall = getCurrentAudioCall();
            boolean benignControlSuppression = currentAudioCall != null && "channel state".equals(reason) &&
                state == State.CONTROL && currentAudioCall.getAudioBufferCount() == 0;

            logAnomalousClose(reason, state, currentAudioCall);

            if(benignControlSuppression)
            {
                closeAudioSegmentSilently();
            }
            else
            {
                closeAudioSegment(reason);
            }

            mEncryptionState = P25AudioEncryptionState.UNKNOWN;
            mPendingEncryptionLdus.clear();
            mDeferredClearAudioLdus.clear();
        }

        @Override
        public void receive(DecoderStateEvent event)
        {
            switch(event.getEvent())
            {
                case START, CONTINUATION ->
                {
                    mCurrentDecoderState = event.getState();

                    tryActivateDeferredAudio();
                }
                case END, DECODE ->
                {
                    mCurrentDecoderState = event.getState();

                    if(!isCallActiveState(event.getState()))
                    {
                        closeAudioSegmentForDecoderState(event.getEvent().name().toLowerCase(), event.getState());
                    }
                }
                case NOTIFICATION_CHANNEL_STATE ->
                {
                    mCurrentDecoderState = event.getState();

                    if(!isCallActiveState(event.getState()))
                    {
                        closeAudioSegmentForDecoderState("channel state", event.getState());
                    }
                }
                case REQUEST_RESET ->
                {
                    mCurrentDecoderState = State.RESET;
                    closeAudioSegmentForDecoderState("decoder reset", State.RESET);
                }
                default -> { /* no action */ }
            }
        }
    }
}

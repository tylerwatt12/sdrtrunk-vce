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
package io.github.dsheirer.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AnalogCallAttribution;
import io.github.dsheirer.alias.AnalogCallAttributionTracker;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.squelch.ISquelchStateListener;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.real.IRealBufferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packages demodulated analog audio buffers into mutable call state and emits immutable audio-call events.
 *
 * Incorporates audio squelch state listener to control if audio packets are broadcast or ignored.
 */
public class AudioModule extends AbstractAudioModule implements ISquelchStateListener, IRealBufferListener,
    Listener<float[]>
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioModule.class);
    private final boolean mAudioFilterEnable;
    private final IRealFilter mHighPassFilter = AudioFilterFactory.getAudioHighPassFilter();
    private final SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private SquelchState mSquelchState = SquelchState.SQUELCH;
    private AnalogCallAttributionTracker mCallAttributionTracker;
    private AnalogCallAttribution mCallAttribution;
    private final P25PagingToneService.Registration mToneRegistration = P25PagingToneService.getInstance().registerAnalogPcm();
    private volatile Listener<AudioCallEvent> mAudioCallListener;
    private float[] mPendingRawAudio;
    private boolean mRawAudioExpected;
    private static final int MAXIMUM_RAW_BLOCK_SAMPLES = 16_384;

    /** Borrowed pre-filter 8 kHz block, immediately followed by the normal processed block on this producer. */
    public void receiveRawAudio(float[] audio)
    {
        mRawAudioExpected = true;
        if(mSquelchState == SquelchState.UNSQUELCH && audio != null && audio.length <= MAXIMUM_RAW_BLOCK_SAMPLES &&
            P25PagingToneService.getInstance().isEnabled())
            mPendingRawAudio = audio.clone();
        else
            mPendingRawAudio = null;
    }

    private void observeAudioCallEvent(AudioCallEvent event)
    {
        if(event.eventType() != AudioCallEventType.AUDIO_FRAME) mToneRegistration.receive(event);
        Listener<AudioCallEvent> listener = mAudioCallListener;
        if(listener != null) listener.receive(event);
    }

    @Override protected void observeRawAudioFrame(AudioCallEvent event) { mToneRegistration.receive(event); }
    @Override public void setAudioCallEventListener(Listener<AudioCallEvent> listener) { mAudioCallListener = listener; }
    @Override public void removeAudioCallEventListener() { mAudioCallListener = null; }
    @Override public void dispose()
    {
        mToneRegistration.close();
        mPendingRawAudio = null;
        mAudioCallListener = null;
        super.removeAudioCallEventListener();
        super.dispose();
    }

    public void setCallAttributionTracker(AnalogCallAttributionTracker tracker)
    {
        mCallAttributionTracker = tracker;
    }

    @Override
    protected AnalogCallAttribution getAnalogCallAttribution()
    {
        return mCallAttribution;
    }

    /**
     * Creates an Audio Module.
     *
     * @param aliasList for aliasing identifiers
     * @param timeslot for this audio module
     * @param maxAudioSegmentLength in milliseconds
     * @param audioFilterEnable to enable or disable high-pass audio filter
     */
    public AudioModule(AliasList aliasList, int timeslot, long maxAudioSegmentLength, boolean audioFilterEnable)
    {
        this(aliasList, timeslot, maxAudioSegmentLength, audioFilterEnable, CallLegSource.UNKNOWN);
    }

    /**
     * Creates an Audio Module with the stable saved-channel identity attached to every emitted call.
     */
    public AudioModule(AliasList aliasList, int timeslot, long maxAudioSegmentLength, boolean audioFilterEnable,
                       CallLegSource callLegSource)
    {
        super(aliasList, timeslot, maxAudioSegmentLength, callLegSource);
        mAudioFilterEnable = audioFilterEnable;
        super.setAudioCallEventListener(this::observeAudioCallEvent);
    }

    /**
     * Creates an Audio Module.
     * @param aliasList for aliasing identifiers
     * @param audioFilterEnable to enable or disable high-pass audio filter
     */
    public AudioModule(AliasList aliasList, boolean audioFilterEnable)
    {
        super(aliasList);
        mAudioFilterEnable = audioFilterEnable;
        super.setAudioCallEventListener(this::observeAudioCallEvent);
    }

    @Override
    protected int getTimeslot()
    {
        return 0;
    }

    @Override
    public void reset()
    {
        mToneRegistration.resetUnfinishedCall();
        mPendingRawAudio = null;
        getIdentifierCollection().clear();
    }

    @Override
    public void start()
    {
        //No additional startup is required for buffered audio packaging.
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    @Override
    public void receive(float[] audioBuffer)
    {
        if(mSquelchState == SquelchState.UNSQUELCH)
        {
            MutableAudioCallBuilder currentAudioCall = beginCurrentAudioSegment();

            if(!currentAudioCall.isBurstActive())
            {
                if(currentAudioCall.getAudioBufferCount() > 0)
                {
                    mLog.warn("Analog audio resumed on inactive burst segment:{} buffers:{} complete:{}",
                        formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                        currentAudioCall.isComplete());
                }

                beginCurrentAudioBurst();
            }
            else
            {
                touchCurrentAudioSegment();
            }

            float[] rawAudio = mPendingRawAudio;
            mPendingRawAudio = null;
            if(!mRawAudioExpected && audioBuffer.length <= MAXIMUM_RAW_BLOCK_SAMPLES &&
                P25PagingToneService.getInstance().isEnabled()) rawAudio = audioBuffer.clone();
            if(rawAudio == null || rawAudio.length != audioBuffer.length) mToneRegistration.resetUnfinishedCall();

            if(mAudioFilterEnable)
            {
                audioBuffer = mHighPassFilter.filter(audioBuffer);
            }

            addAudioWithRawObservation(audioBuffer, rawAudio, System.currentTimeMillis());
        }
    }

    @Override
    public Listener<float[]> getBufferListener()
    {
        //Redirect received reusable buffers to the receive(buffer) method
        return this;
    }

    /**
     * Wrapper for squelch state listener
     */
    public class SquelchStateListener implements Listener<SquelchStateEvent>
    {
        @Override
        public void receive(SquelchStateEvent event)
        {
            SquelchState squelchState = event.getSquelchState();

            if(mSquelchState != squelchState)
            {
                MutableAudioCallBuilder currentAudioCall = getCurrentAudioCall();
                mSquelchState = squelchState;

                if(mSquelchState == SquelchState.SQUELCH)
                {
                    mPendingRawAudio = null;
                    endCurrentAudioBurst();
                    closeAudioSegment();
                }
                else
                {
                    mCallAttribution = mCallAttributionTracker != null ? mCallAttributionTracker.start() : null;
                    if(currentAudioCall != null)
                    {
                        mLog.warn("Analog audio unsquelch with open segment:{} buffers:{} bursts:{} burstActive:{}",
                            formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                            currentAudioCall.getBurstCount(), currentAudioCall.isBurstActive());
                    }

                    beginCurrentAudioSegment();
                    beginCurrentAudioBurst();
                }
            }
        }
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
}

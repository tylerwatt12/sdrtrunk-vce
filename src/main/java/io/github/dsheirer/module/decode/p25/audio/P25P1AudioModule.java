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
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.call.VoiceFrameFingerprint;
import io.github.dsheirer.audio.codec.mbe.IEncryptionSyncParameters;
import io.github.dsheirer.audio.codec.mbe.ImbeAudioModule;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.audio.tone.P25PagingToneService;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventListener;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageProvider;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.EncryptionSyncParameters;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDUMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;
import java.util.Deque;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P25P1AudioModule extends ImbeAudioModule implements IDecoderStateEventListener, IMessageProvider
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P1AudioModule.class);
    /** Each IMBE codeword represents 20 ms of speech (TIA-102-BAAA-A, 8.2.2). */
    private static final long VOICE_FRAME_DURATION_MILLISECONDS = 20L;
    private static final long LONG_AUDIO_GAP_LOG_THRESHOLD_MS = 1000;
    static final int MAX_PENDING_ENCRYPTION_LDUS = 32;
    static final int MAX_DEFERRED_CLEAR_LDUS = 32;
    private CallEncryptionState mEncryptionState = CallEncryptionState.UNKNOWN;
    private IEncryptionSyncParameters mEncryptionSyncParameters;
    private State mCurrentDecoderState = State.IDLE;

    private DecoderStateEventListener mDecoderStateEventListener = new DecoderStateEventListener();
    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private final Deque<LDUMessage> mPendingEncryptionLdus =
        new ArrayDeque<>(MAX_PENDING_ENCRYPTION_LDUS);
    private final Deque<LDUMessage> mDeferredClearAudioLdus =
        new ArrayDeque<>(MAX_DEFERRED_CLEAR_LDUS);
    private VoiceEncryptionKeyResolver mEncryptionKeyResolver;
    private VoiceFrameDecryptorFactory mVoiceFrameDecryptorFactory;
    private VoiceFrameDecryptor mVoiceFrameDecryptor;
    private long mLastCarrierTimestamp = Long.MIN_VALUE;
    private long mLastAudioTimestamp = Long.MIN_VALUE;
    private long mPendingEncryptedStartTimestamp = Long.MIN_VALUE;
    private long mLastHeaderTimestamp;
    private String mLastAudioSegmentId;
    private final P25PagingToneService.Registration mPagingToneRegistration =
        P25PagingToneService.getInstance().registerGlobalPcm();
    private volatile Listener<AudioCallEvent> mAudioCallListener;

    public P25P1AudioModule(UserPreferences userPreferences, AliasList aliasList)
    {
        this(userPreferences, aliasList, CallLegSource.UNKNOWN);
    }

    public P25P1AudioModule(UserPreferences userPreferences, AliasList aliasList, CallLegSource callLegSource)
    {
        super(userPreferences, aliasList, callLegSource);
        mEncryptionKeyResolver = new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
        mVoiceFrameDecryptorFactory = new VoiceFrameDecryptorFactory(userPreferences
            .getVoiceDecryptionModulePreference().getModuleManager());
        //One immutable event is shared with the normal audio owner and the bounded tone observer.
        super.setAudioCallEventListener(this::observeAudioCallEvent);
    }

    private void observeAudioCallEvent(AudioCallEvent event)
    {
        mPagingToneRegistration.receive(event);
        Listener<AudioCallEvent> listener = mAudioCallListener;
        if(listener != null) listener.receive(event);
    }

    @Override
    public void setAudioCallEventListener(Listener<AudioCallEvent> listener)
    {
        mAudioCallListener = listener;
    }

    @Override
    public void removeAudioCallEventListener()
    {
        mAudioCallListener = null;
    }

    @Override
    public void setMessageListener(Listener<IMessage> listener)
    {
        mPagingToneRegistration.setLegacyMessageListener(listener);
    }

    @Override
    public void removeMessageListener()
    {
        mPagingToneRegistration.setLegacyMessageListener(null);
    }

    @Override
    public void dispose()
    {
        mPagingToneRegistration.close();
        mAudioCallListener = null;
        super.removeAudioCallEventListener();
        super.dispose();
    }

    @Override
    protected boolean usesCorrectedVoiceFrameFingerprint()
    {
        return true;
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
        stop();
        mPagingToneRegistration.resetUnfinishedCall();
        getIdentifierCollection().clear();
        resetEncryptionTracking();
    }

    @Override
    public void start()
    {
        // No startup work is required beyond base-class construction for this audio module.
    }

    @Override
    public void stop()
    {
        closeAudioSegment();
        discardCallAttribution();
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
        if(message == null)
        {
            return;
        }

        prepareCallAttribution();

        if(shouldTouchSegment(message))
        {
            mLastCarrierTimestamp = Math.max(mLastCarrierTimestamp, message.getTimestamp());

            if(getCurrentAudioCall() != null && message instanceof LDUMessage ldu)
            {
                touchCurrentAudioSegment(message.getTimestamp(),
                    firstVoiceFrameTimestamp(message.getTimestamp(), ldu.getIMBEFrames().size()));
                setCurrentCallEncryptionState(mEncryptionState, message.getTimestamp());
            }
        }

        //A valid HDU is a strong new-call boundary.  Close a prior call whose terminator was missed before applying
        //the new encryption state and immutable evidence.
        if(message instanceof HDUMessage hdu && hdu.isValid())
        {
            if(getCurrentAudioCall() != null)
            {
                closeAudioSegment(message.getTimestamp());
            }

            resetEncryptionTracking();
            mLastHeaderTimestamp = message.getTimestamp();
            mLastCarrierTimestamp = message.getTimestamp();
            mEncryptionState = CallEncryptionState.fromEncrypted(hdu.getHeaderData().isEncryptedAudio());
            mEncryptionSyncParameters = mEncryptionState.isEncrypted() ?
                new Phase2EncryptionSyncParameters(hdu.getHeaderData().getEncryptionKey(),
                    hdu.getHeaderData().getMessageIndicator()) : null;
            mVoiceFrameDecryptor = null;

            if(mEncryptionState.isClear())
            {
                beginAudioIfStateActive(message.getTimestamp());
            }
            else
            {
                beginEncryptedCallIfStateActive(message.getTimestamp(), mEncryptionSyncParameters);
            }

            return;
        }

        if(mEncryptionState.isKnown())
        {
            if(message instanceof LDUMessage ldu)
            {
                if(isCallActiveState(mCurrentDecoderState))
                {
                    if(mEncryptionState.isClear() && getCurrentAudioCall() == null)
                        beginAudioIfStateActive(firstVoiceFrameTimestamp(ldu.getTimestamp(), ldu.getIMBEFrames().size()));
                    processEstablishedLDU(ldu);
                }
                else if(mEncryptionState.isClear())
                {
                    mLog.debug("P25P1 deferring LDU audio state:{}", mCurrentDecoderState);
                    cacheDeferredClearLdu(ldu);
                }
                else
                {
                    mLog.debug("P25P1 skipping encrypted LDU audio state:{}", mCurrentDecoderState);
                }
            }
        }
        else
        {
            if(message instanceof LDU1Message ldu1)
            {
                //When we receive an LDU1 message without first receiving the HDU message, cache the LDU1 Message
                //until we can determine the encrypted call state from the next LDU2 message
                cachePendingEncryptionLdu(ldu1);
            }
            else if(message instanceof LDU2Message ldu2)
            {
                EncryptionSyncParameters parameters = ldu2.getEncryptionSyncParameters();

                if(parameters.isValid())
                {
                    mEncryptionState = CallEncryptionState.fromEncrypted(parameters.isEncryptedAudio());
                    mEncryptionSyncParameters = mEncryptionState.isEncrypted() ? parameters : null;
                    mVoiceFrameDecryptor = null;

                    if(mEncryptionState.isClear())
                    {
                        beginAudioIfStateActive(getPendingStartTimestamp(message.getTimestamp()));
                    }
                    else
                    {
                        beginEncryptedCallIfStateActive(getPendingStartTimestamp(message.getTimestamp()),
                            parameters);
                    }
                }

                if(mEncryptionState.isKnown())
                {
                    if(mEncryptionState.isClear())
                    {
                        promotePendingEncryptionLdus();

                        if(isCallActiveState(mCurrentDecoderState))
                        {
                            tryActivateDeferredAudio();
                            processEstablishedLDU(ldu2);
                        }
                        else
                        {
                            mLog.debug("P25P1 deferring clear audio state:{} deferred:{}", mCurrentDecoderState,
                                mDeferredClearAudioLdus.size());
                        }
                    }
                    else if(parameters.isValid())
                    {
                        //The LDU2 ESS seeds the following LDU1/LDU2 pair, not the current late-entry frames.
                        mPendingEncryptionLdus.clear();
                        mDeferredClearAudioLdus.clear();
                        mEncryptionSyncParameters = parameters;
                        mVoiceFrameDecryptor = null;
                    }
                }
                else
                {
                    cachePendingEncryptionLdu(ldu2);
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

    private void beginAudioIfStateActive(long timestamp)
    {
        if(isCallActiveState(mCurrentDecoderState))
        {
            mPendingEncryptedStartTimestamp = Long.MIN_VALUE;
            beginCurrentAudioSegment(timestamp);
            beginCurrentAudioBurst(timestamp);
            setCurrentCallEncryptionState(CallEncryptionState.CLEAR, timestamp);
        }
        else
        {
            mLog.debug("P25P1 deferring audio segment creation state:{}", mCurrentDecoderState);
        }
    }

    private void beginEncryptedCallIfStateActive(long timestamp, IEncryptionSyncParameters parameters)
    {
        if(isCallActiveState(mCurrentDecoderState))
        {
            beginCurrentAudioSegment(timestamp);
            beginCurrentAudioBurst(timestamp);
            CallEncryptionEvidence evidence = CallEncryptionEvidence.capture(parameters);

            if(evidence != null)
            {
                setCurrentCallEncryptionEvidence(evidence,
                    parameters != null ? parameters.getEncryptionKey() : null, timestamp);
            }
            else
            {
                //The HDU/LDU signaling already established that this is encrypted.  Malformed or incomplete key
                //parameters must not make a metadata-only encrypted call look clear.
                setCurrentCallEncryptionState(CallEncryptionState.ENCRYPTED, timestamp);
            }

            mPendingEncryptedStartTimestamp = Long.MIN_VALUE;
        }
        else
        {
            if(timestamp > 0L)
            {
                mPendingEncryptedStartTimestamp = mPendingEncryptedStartTimestamp > 0L ?
                    Math.min(mPendingEncryptedStartTimestamp, timestamp) : timestamp;
            }

            mLog.debug("P25P1 deferring encrypted call segment creation state:{}", mCurrentDecoderState);
        }
    }

    private void tryActivateEncryptedCall()
    {
        if(mPendingEncryptedStartTimestamp > 0L && mEncryptionState.isEncrypted() && mEncryptionSyncParameters != null &&
            isCallActiveState(mCurrentDecoderState))
        {
            beginEncryptedCallIfStateActive(mPendingEncryptedStartTimestamp, mEncryptionSyncParameters);
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
            LDUMessage pending;

            while((pending = mPendingEncryptionLdus.pollFirst()) != null)
            {
                cacheDeferredClearLdu(pending);
            }
        }

        mPendingEncryptionLdus.clear();
    }

    private void cachePendingEncryptionLdu(LDUMessage ldu)
    {
        if(ldu != null)
        {
            if(mPendingEncryptionLdus.size() >= MAX_PENDING_ENCRYPTION_LDUS)
            {
                mPendingEncryptionLdus.pollFirst();
            }

            mPendingEncryptionLdus.offerLast(ldu);
        }
    }

    private void cacheDeferredClearLdu(LDUMessage ldu)
    {
        if(ldu != null)
        {
            if(mDeferredClearAudioLdus.size() >= MAX_DEFERRED_CLEAR_LDUS)
            {
                mDeferredClearAudioLdus.pollFirst();
            }

            mDeferredClearAudioLdus.offerLast(ldu);
        }
    }

    private void tryActivateDeferredAudio()
    {
        if(!mEncryptionState.isClear() || !isCallActiveState(mCurrentDecoderState) || mDeferredClearAudioLdus.isEmpty())
        {
            return;
        }

        LDUMessage first = mDeferredClearAudioLdus.getFirst();
        beginAudioIfStateActive(firstVoiceFrameTimestamp(first.getTimestamp(), first.getIMBEFrames().size()));

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

    private long getPendingStartTimestamp(long fallbackTimestamp)
    {
        if(!mPendingEncryptionLdus.isEmpty() && mPendingEncryptionLdus.getFirst().getTimestamp() > 0)
        {
            return mPendingEncryptionLdus.getFirst().getTimestamp();
        }

        return fallbackTimestamp;
    }

    /**
     * Processes an LDU after call encryption state has been established.  For encrypted Phase 1 calls, the LDU2 ESS
     * message indicator applies after the current LDU2 voice frames and initializes the next LDU1/LDU2 pair.
     */
    private void processEstablishedLDU(LDUMessage ldu)
    {
        processAudio(ldu);

        if(ldu instanceof LDU2Message ldu2)
        {
            promoteLDU2EncryptionSync(ldu2);
        }
    }

    private void promoteLDU2EncryptionSync(LDU2Message ldu2)
    {
        EncryptionSyncParameters parameters = ldu2.getEncryptionSyncParameters();

        if(parameters.isValid())
        {
            mEncryptionState = CallEncryptionState.fromEncrypted(parameters.isEncryptedAudio());
            mEncryptionSyncParameters = mEncryptionState.isEncrypted() ? parameters : null;
            mVoiceFrameDecryptor = null;

            if(mEncryptionState.isEncrypted())
            {
                beginEncryptedCallIfStateActive(ldu2.getTimestamp(), parameters);
            }
            else if(getCurrentAudioCall() != null)
            {
                setCurrentCallEncryptionState(CallEncryptionState.CLEAR, ldu2.getTimestamp());
            }
        }
        else if(mEncryptionState.isEncrypted())
        {
            //Without a valid ESS, the next encrypted LDU1 cannot be decrypted with confidence.
            mEncryptionSyncParameters = null;
            mVoiceFrameDecryptor = null;
        }
    }

    /**
     * Processes an audio packet by decoding the IMBE audio frames and rebroadcasting them as PCM audio packets.
     */
    private void processAudio(LDUMessage ldu)
    {
        if(!hasAudioCodec())
        {
            return;
        }

        if(mEncryptionState.isClear())
        {
            if(!mEncryptionState.isKnown())
            {
                mLog.warn("P25P1 processing clear audio without established encrypted state cachedLdus:{}",
                    getCachedLduCount());
            }

            long timestamp = firstVoiceFrameTimestamp(ldu.getTimestamp(), ldu.getIMBEFrames().size());

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
                            mEncryptionState.isKnown(), getCachedLduCount());
                    }

                    beginCurrentAudioBurst(timestamp);
                }

                if(mLastAudioTimestamp != Long.MIN_VALUE && currentSegmentId.equals(mLastAudioSegmentId))
                {
                    long gap = timestamp - mLastAudioTimestamp;

                    if(gap >= LONG_AUDIO_GAP_LOG_THRESHOLD_MS)
                    {
                        mLog.warn("P25P1 audio resumed after long gap segment:{} gapMs:{} buffers:{} burstActive:{} encryptedStateEstablished:{} cachedLdus:{}",
                            currentSegmentId, gap, currentAudioCall.getAudioBufferCount(), currentAudioCall.isBurstActive(),
                            mEncryptionState.isKnown(), getCachedLduCount());
                    }
                }

                long voiceFrameFingerprint = VoiceFrameFingerprint.compute(frame);
                IAudioCodec codec = getAudioCodec();
                IAudioWithMetadata audioWithMetadata = codec.getAudioWithMetadata(frame);
                long correctedFingerprint = getCorrectedVoiceFrameFingerprint(codec);
                //Preserve raw decoder levels for ML input and apply presentation gain only after call completion.
                float[] audio = audioWithMetadata.getAudio();
                addAudio(audio, getVoiceFrameQuality(audioWithMetadata), timestamp, voiceFrameFingerprint,
                    correctedFingerprint);
                mLastAudioTimestamp = timestamp;
                mLastAudioSegmentId = currentSegmentId;
                timestamp += VOICE_FRAME_DURATION_MILLISECONDS;
            }
        }
        else
        {
            processEncryptedAudio(ldu);
        }
    }

    private void processEncryptedAudio(LDUMessage ldu)
    {
        if(!prepareEncryptedAudioDecryptor())
        {
            return;
        }

        long timestamp = firstVoiceFrameTimestamp(ldu.getTimestamp(), ldu.getIMBEFrames().size());

        for(byte[] frame : ldu.getIMBEFrames())
        {
            MutableAudioCallBuilder currentAudioCall = getAudioCall();
            String currentSegmentId = formatSegment(currentAudioCall);

            if(!currentAudioCall.isBurstActive())
            {
                if(currentAudioCall.getAudioBufferCount() > 0)
                {
                    mLog.warn("P25P1 decrypted audio resumed on inactive burst segment:{} buffers:{} complete:{} encryptedStateEstablished:{} cachedLdus:{}",
                        currentSegmentId, currentAudioCall.getAudioBufferCount(), currentAudioCall.isComplete(),
                        mEncryptionState.isKnown(), getCachedLduCount());
                }

                beginCurrentAudioBurst(timestamp);
            }

            if(mLastAudioTimestamp != Long.MIN_VALUE && currentSegmentId.equals(mLastAudioSegmentId))
            {
                long gap = timestamp - mLastAudioTimestamp;

                if(gap >= LONG_AUDIO_GAP_LOG_THRESHOLD_MS)
                {
                    mLog.warn("P25P1 decrypted audio resumed after long gap segment:{} gapMs:{} buffers:{} burstActive:{} encryptedStateEstablished:{} cachedLdus:{}",
                        currentSegmentId, gap, currentAudioCall.getAudioBufferCount(), currentAudioCall.isBurstActive(),
                        mEncryptionState.isKnown(), getCachedLduCount());
                }
            }

            try
            {
                byte[] decryptedFrame = mVoiceFrameDecryptor.decrypt(frame);
                long voiceFrameFingerprint = VoiceFrameFingerprint.compute(decryptedFrame);
                IAudioCodec codec = getAudioCodec();
                IAudioWithMetadata audioWithMetadata = codec.getAudioWithMetadata(decryptedFrame);
                long correctedFingerprint = getCorrectedVoiceFrameFingerprint(codec);
                //Preserve raw decoder levels for ML input and apply presentation gain only after call completion.
                float[] audio = audioWithMetadata.getAudio();
                addAudio(audio, getVoiceFrameQuality(audioWithMetadata), timestamp, voiceFrameFingerprint,
                    correctedFingerprint);
                mLastAudioTimestamp = timestamp;
                mLastAudioSegmentId = currentSegmentId;
                timestamp += VOICE_FRAME_DURATION_MILLISECONDS;
            }
            catch(VoiceFrameDecryptionException e)
            {
                mLog.debug("Error decrypting P25 Phase 1 IMBE audio", e);
                closeAudioSegment(timestamp);
                return;
            }
        }
    }

    /**
     * LDU timestamps identify the final received bit.  Anchor the last of the nine 20 ms IMBE frames there and walk
     * backward so duplicate evidence has deterministic per-frame carrier time instead of nine identical timestamps.
     */
    private static long firstVoiceFrameTimestamp(long lduTimestamp, int frameCount)
    {
        long offset = Math.max(0, frameCount - 1) * VOICE_FRAME_DURATION_MILLISECONDS;
        return Math.max(1L, lduTimestamp - offset);
    }

    private boolean prepareEncryptedAudioDecryptor()
    {
        if(mVoiceFrameDecryptor != null)
        {
            return mVoiceFrameDecryptor.isImplemented();
        }

        VoiceEncryptionContext context = VoiceEncryptionContext.create(VoiceEncryptionProtocol.APCO25,
            mEncryptionSyncParameters != null ? mEncryptionSyncParameters.getEncryptionKey() : null,
            mEncryptionSyncParameters != null ? mEncryptionSyncParameters.getMessageIndicator() : null,
            getTimeslot(), getIdentifierCollection());

        mVoiceFrameDecryptor = mEncryptionKeyResolver.resolve(context)
            .flatMap(key -> mVoiceFrameDecryptorFactory.create(context, key))
            .orElse(null);

        return mVoiceFrameDecryptor != null && mVoiceFrameDecryptor.isImplemented();
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
                resetEncryptionTracking();
            }
        }
    }

    @Override
    protected void closeAudioSegment()
    {
        long timestamp = mLastCarrierTimestamp > 0 ? mLastCarrierTimestamp :
            mLastAudioTimestamp > 0 ? mLastAudioTimestamp : System.currentTimeMillis();
        closeAudioSegment(timestamp);
    }

    @Override
    protected void closeAudioSegment(long timestamp)
    {
        endCurrentAudioBurst(timestamp);
        super.closeAudioSegment(timestamp);
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

    public class DecoderStateEventListener implements Listener<DecoderStateEvent>
    {
        @Override
        public void receive(DecoderStateEvent event)
        {
            switch(event.getEvent())
            {
                case START, CONTINUATION ->
                {
                    mCurrentDecoderState = event.getState();
                    long headerTimestamp = event.getEvent() == DecoderStateEvent.Event.START ?
                        getPhysicalCallBoundaryTimestamp() : 0L;
                    boolean headerAlreadyProcessed = headerTimestamp > 0 && headerTimestamp == mLastHeaderTimestamp;
                    if(headerTimestamp > 0 && !headerAlreadyProcessed)
                        resetEncryptionTracking();
                    prepareCallAttribution(!headerAlreadyProcessed);

                    //Valid current-user LC can establish a clear call before an HDU or ESS is received.
                    if(!mEncryptionState.isKnown() && mCurrentDecoderState == State.CALL)
                    {
                        mEncryptionState = CallEncryptionState.CLEAR;
                        promotePendingEncryptionLdus();
                    }

                    tryActivateDeferredAudio();
                    tryActivateEncryptedCall();
                }
                case END, DECODE ->
                {
                    mCurrentDecoderState = event.getState();

                    if(!isCallActiveState(event.getState()))
                    {
                        closeAudioSegment();
                        resetEncryptionTracking();
                    }
                }
                case NOTIFICATION_CHANNEL_STATE ->
                {
                    mCurrentDecoderState = event.getState();

                    if(!isCallActiveState(event.getState()))
                    {
                        closeAudioSegment();
                        resetEncryptionTracking();
                    }
                }
                case REQUEST_RESET ->
                {
                    mCurrentDecoderState = State.RESET;
                    closeAudioSegment();
                    resetEncryptionTracking();
                    discardCallAttribution();
                }
                default -> { /* no action */ }
            }
        }
    }

    private void resetEncryptionTracking()
    {
        mEncryptionState = CallEncryptionState.UNKNOWN;
        mEncryptionSyncParameters = null;
        mVoiceFrameDecryptor = null;
        mPendingEncryptionLdus.clear();
        mDeferredClearAudioLdus.clear();
        mLastCarrierTimestamp = Long.MIN_VALUE;
        mLastAudioTimestamp = Long.MIN_VALUE;
        mPendingEncryptedStartTimestamp = Long.MIN_VALUE;
        mLastHeaderTimestamp = 0L;
        mLastAudioSegmentId = null;
    }

    @Override
    protected void callAttributionChanged()
    {
        mPendingEncryptionLdus.clear();
        mDeferredClearAudioLdus.clear();
        mEncryptionSyncParameters = null;
        mVoiceFrameDecryptor = null;
        mPendingEncryptedStartTimestamp = Long.MIN_VALUE;
        mEncryptionState = switch(mCurrentDecoderState)
        {
            case CALL -> CallEncryptionState.CLEAR;
            case ENCRYPTED -> CallEncryptionState.ENCRYPTED;
            default -> CallEncryptionState.UNKNOWN;
        };
    }
}

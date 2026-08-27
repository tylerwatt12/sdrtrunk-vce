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
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.call.VoiceFrameFingerprint;
import io.github.dsheirer.audio.codec.mbe.AmbeAudioModule;
import io.github.dsheirer.audio.codec.mbe.IEncryptionSyncParameters;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionContext;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceEncryptionKeyResolver;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptionException;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptor;
import io.github.dsheirer.audio.codec.mbe.decrypt.VoiceFrameDecryptorFactory;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateProvider;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.P25ToneIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.ToneIdentifierMessage;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.IMessageProvider;
import io.github.dsheirer.module.decode.p25.phase2.message.EncryptionSynchronizationSequence;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacPduType;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractVoiceTimeslot;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.encryption.VoiceEncryptionProtocol;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import jmbe.iface.IAudioWithMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P25P2AudioModule extends AmbeAudioModule implements IdentifierUpdateProvider, IMessageProvider
{
    private static final Logger mLog = LoggerFactory.getLogger(P25P2AudioModule.class);
    /** Half-rate vocoder frames are spaced 20 ms apart (TIA-102.BABA-1, clauses 3 and 4). */
    private static final long VOICE_FRAME_DURATION_MILLISECONDS = 20L;
    static final int MAX_PENDING_VOICE_TIMESLOTS = 32;
    private Listener<IdentifierUpdateNotification> mIdentifierUpdateNotificationListener;
    private SquelchStateListener mSquelchStateListener = new SquelchStateListener();
    private ToneMetadataProcessor mToneMetadataProcessor = new ToneMetadataProcessor();
    private Queue<AbstractVoiceTimeslot> mQueuedAudioTimeslots =
        new ArrayDeque<>(MAX_PENDING_VOICE_TIMESLOTS);
    private CallEncryptionState mEncryptionState = CallEncryptionState.UNKNOWN;
    private IEncryptionSyncParameters mEncryptionSyncParameters;
    private VoiceEncryptionKeyResolver mEncryptionKeyResolver;
    private VoiceFrameDecryptorFactory mVoiceFrameDecryptorFactory;
    private VoiceFrameDecryptor mVoiceFrameDecryptor;
    private Listener<IMessage> mMessageListener;

    private record DecodedVoiceFrame(IAudioWithMetadata audioWithMetadata, long carrierTimestamp,
                                     long voiceFrameFingerprint)
    {
    }

    public P25P2AudioModule(UserPreferences userPreferences, int timeslot, AliasList aliasList)
    {
        this(userPreferences, timeslot, aliasList, CallLegSource.UNKNOWN);
    }

    public P25P2AudioModule(UserPreferences userPreferences, int timeslot, AliasList aliasList,
                            CallLegSource callLegSource)
    {
        super(userPreferences, aliasList, timeslot, callLegSource);
        mEncryptionKeyResolver = new VoiceEncryptionKeyResolver(userPreferences.getEncryptionKeyPreference());
        mVoiceFrameDecryptorFactory = new VoiceFrameDecryptorFactory(userPreferences
            .getVoiceDecryptionModulePreference().getModuleManager());
    }

    @Override
    public Listener<SquelchStateEvent> getSquelchStateListener()
    {
        return mSquelchStateListener;
    }

    /**
     * Resets this audio module upon completion of an audio call to prepare for the next call.  This method is
     * controlled by the squelch state listener and squelch state is controlled by the P25P2DecoderState.
     */
    @Override
    public void reset()
    {
        MutableAudioCallBuilder currentAudioCall = getCurrentAudioCall();

        if(currentAudioCall != null)
        {
            mLog.warn("TS{} reset with open audio segment:{} buffers:{} complete:{} encryptedStateEstablished:{} encrypted:{} queued:{}",
                getTimeslot(), formatSegment(currentAudioCall), currentAudioCall.getAudioBufferCount(),
                currentAudioCall.isComplete(), mEncryptionState.isKnown(), mEncryptionState.isEncrypted(),
                mQueuedAudioTimeslots.size());
        }

        //Explicitly clear FROM identifiers to ensure previous call TONE identifiers are cleared.
        mIdentifierCollection.remove(Role.FROM);

        mToneMetadataProcessor.reset();
        mQueuedAudioTimeslots.clear();

        resetEncryptionTracking();
    }

    @Override
    public void start()
    {
        reset();
    }

    @Override
    public void stop()
    {
        closeCurrentAudioSegment();
    }

    /**
     * Primary message processing method for processing voice timeslots and Push-To-Talk MAC messages
     *
     * Audio timeslots are temporarily queued until a determination of the encrypted state of the call is determined
     * and then all queued audio is processed through to the end of the call.  Encryption state is determined either
     * by the PTT MAC message or by processing the ESS fragments from the Voice2 and Voice4 timeslots.
     *
     * @param message to process
     */
    @Override
    public void receive(IMessage message)
    {
        if(message.getTimeslot() == getTimeslot())
        {
            if(getCurrentAudioCall() != null && shouldTouchSegment(message))
            {
                touchCurrentAudioSegment(message.getTimestamp());
            }

            if(message instanceof AbstractVoiceTimeslot abstractVoiceTimeslot)
            {
                if(mEncryptionState.isKnown())
                {
                    if(mEncryptionState.isClear())
                    {
                        processAudio(abstractVoiceTimeslot.getVoiceFrames(), message.getTimestamp());
                    }
                    else
                    {
                        processEncryptedAudio(abstractVoiceTimeslot);
                    }
                }
                else
                {
                    //Queue audio timeslots until we can determine if the audio is encrypted or not
                    if(mQueuedAudioTimeslots.size() >= MAX_PENDING_VOICE_TIMESLOTS)
                    {
                        mQueuedAudioTimeslots.poll();
                    }

                    mQueuedAudioTimeslots.offer(abstractVoiceTimeslot);
                }
            }
            else if(message instanceof MacMessage macMessage && message.isValid())
            {
                MacStructure macStructure = macMessage.getMacStructure();

                if(macStructure instanceof PushToTalk pushToTalk)
                {
                    //A valid PTT is a strong new-call boundary.  Complete an old segment whose End PTT was missed
                    //before applying the new call's encryption state and evidence.
                    if(getCurrentAudioCall() != null)
                    {
                        closeCurrentAudioSegment(message.getTimestamp());
                    }

                    mToneMetadataProcessor.reset();
                    clearPendingVoiceTimeslots();
                    resetEncryptionTracking();
                    mEncryptionState = CallEncryptionState.fromEncrypted(pushToTalk.isEncrypted());
                    mEncryptionSyncParameters = mEncryptionState.isEncrypted() ?
                        pushToTalk.getEncryptionSyncParameters() : null;
                    mVoiceFrameDecryptor = null;
                    beginCurrentAudioSegment(message.getTimestamp());
                    beginCurrentAudioBurst(message.getTimestamp());

                    if(mEncryptionState.isEncrypted())
                    {
                        CallEncryptionEvidence evidence =
                            CallEncryptionEvidence.capture(mEncryptionSyncParameters);

                        if(evidence != null)
                        {
                            setCurrentCallEncryptionEvidence(evidence,
                                mEncryptionSyncParameters != null ? mEncryptionSyncParameters.getEncryptionKey() : null,
                                message.getTimestamp());
                        }
                        else
                        {
                            //The PTT encryption flag is authoritative even when its key/MI fields are malformed.
                            setCurrentCallEncryptionState(CallEncryptionState.ENCRYPTED, message.getTimestamp());
                        }
                    }
                    else
                    {
                        setCurrentCallEncryptionState(CallEncryptionState.CLEAR, message.getTimestamp());
                    }
                }
                else if(macMessage.getMacPduType() == MacPduType.MAC_2_END_PTT ||
                    macMessage.getMacPduType() == MacPduType.MAC_6_HANGTIME)
                {
                    //End PTT is the normal per-speaker boundary. Hangtime closes the call when End PTT was missed.
                    closeCurrentAudioSegment(message.getTimestamp());
                    mToneMetadataProcessor.reset();
                    clearPendingVoiceTimeslots();
                    resetEncryptionTracking();
                }
            }
            else if(message instanceof EncryptionSynchronizationSequence encryptionSynchronizationSequence && message.isValid())
            {
                mEncryptionState = CallEncryptionState.fromEncrypted(encryptionSynchronizationSequence.isEncrypted());
                mEncryptionSyncParameters = mEncryptionState.isEncrypted() ?
                    encryptionSynchronizationSequence : null;
                mVoiceFrameDecryptor = null;

                if(mEncryptionState.isClear())
                {
                    long startTimestamp = getPendingVoiceStartTimestamp(message.getTimestamp());
                    beginCurrentAudioSegment(startTimestamp);
                    beginCurrentAudioBurst(startTimestamp);
                    setCurrentCallEncryptionState(CallEncryptionState.CLEAR, message.getTimestamp());
                    processPendingVoiceTimeslots();
                }
                else
                {
                    //ESS-A completes after the 2V voice frames and seeds the following TDMA superframe.
                    long startTimestamp = getPendingVoiceStartTimestamp(message.getTimestamp());
                    beginCurrentAudioSegment(startTimestamp);
                    beginCurrentAudioBurst(startTimestamp);
                    CallEncryptionEvidence evidence =
                        CallEncryptionEvidence.capture(encryptionSynchronizationSequence);

                    if(evidence != null)
                    {
                        setCurrentCallEncryptionEvidence(evidence,
                            encryptionSynchronizationSequence.getEncryptionKey(), message.getTimestamp());
                    }
                    else
                    {
                        //Preserve the encrypted state even if a damaged ESS cannot yield bounded evidence.
                        setCurrentCallEncryptionState(CallEncryptionState.ENCRYPTED, message.getTimestamp());
                    }

                    clearPendingVoiceTimeslots();
                }
            }
        }
    }

    /**
     * Indicates whether the message is a same-timeslot signal that confirms the current segment is still intentionally
     * alive, even if no audio is being committed at this instant.
     */
    private boolean shouldTouchSegment(IMessage message)
    {
        return message instanceof AbstractVoiceTimeslot ||
            (message instanceof MacMessage macMessage && macMessage.isValid()) ||
            (message instanceof EncryptionSynchronizationSequence encryptionSynchronizationSequence &&
                encryptionSynchronizationSequence.isValid());
    }

    /**
     * Drains and processes any audio timeslots that have been queued pending determination of encrypted call status
     */
    private void processPendingVoiceTimeslots()
    {
        AbstractVoiceTimeslot timeslot = mQueuedAudioTimeslots.poll();

        while(timeslot != null)
        {
            receive(timeslot);
            timeslot = mQueuedAudioTimeslots.poll();
        }
    }

    /**
     * Clears/deletes any pending voice timeslots
     */
    private void clearPendingVoiceTimeslots()
    {
        mQueuedAudioTimeslots.clear();
    }

    private long getPendingVoiceStartTimestamp(long fallbackTimestamp)
    {
        AbstractVoiceTimeslot first = mQueuedAudioTimeslots.peek();
        return first != null && first.getTimestamp() > 0 ? first.getTimestamp() : fallbackTimestamp;
    }

    private void closeCurrentAudioSegment()
    {
        closeCurrentAudioSegment(System.currentTimeMillis());
    }

    private void closeCurrentAudioSegment(long timestamp)
    {
        endCurrentAudioBurst(timestamp);
        super.closeAudioSegment(timestamp);
    }

    private void resetEncryptionTracking()
    {
        //Reset encrypted call handling flags
        mEncryptionState = CallEncryptionState.UNKNOWN;
        mEncryptionSyncParameters = null;
        mVoiceFrameDecryptor = null;
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

    /**
     * Process the audio voice frames
     * @param voiceFrames to process
     * @param timestamp of the carrier message
     */
    private void processAudio(List<BinaryMessage> voiceFrames, long timestamp)
    {
        if(hasAudioCodec())
        {
            boolean audioCommitted = false;
            long voiceFrameTimestamp = firstVoiceFrameTimestamp(timestamp, voiceFrames.size());

            for(BinaryMessage voiceFrame: voiceFrames)
            {
                byte[] voiceFrameBytes = voiceFrame.getBytes();

                try
                {
                    long voiceFrameFingerprint = VoiceFrameFingerprint.compute(voiceFrameBytes);
                    IAudioWithMetadata audioWithMetadata = getAudioCodec().getAudioWithMetadata(voiceFrameBytes);
                    // Route audio through the tone processor so it can hold artifact frames during
                    // the holdoff window and discard them if the tone never reaches threshold.
                    // Returns a list of committed buffers — empty while in holdoff or if artifact
                    // was discarded; may contain previously held frames when threshold is first crossed.
                    List<DecodedVoiceFrame> committed = mToneMetadataProcessor.processAudio(audioWithMetadata,
                        voiceFrameTimestamp, voiceFrameFingerprint);

                    if(!committed.isEmpty())
                    {
                        // Defer segment creation until we have audio that will actually be committed,
                        // so suppressed artifact bursts do not create empty segments.
                        if(!audioCommitted)
                        {
                            MutableAudioCallBuilder currentAudioCall = getAudioCall();

                            if(!currentAudioCall.isBurstActive())
                            {
                                beginCurrentAudioBurst(committed.getFirst().carrierTimestamp());
                            }
                            audioCommitted = true;
                        }

                        for(DecodedVoiceFrame decodedVoiceFrame : committed)
                        {
                            IAudioWithMetadata audio = decodedVoiceFrame.audioWithMetadata();
                            addAudio(audio.getAudio(), getVoiceFrameQuality(audio),
                                decodedVoiceFrame.carrierTimestamp(), decodedVoiceFrame.voiceFrameFingerprint());
                        }
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error synthesizing AMBE audio - continuing [{}]", e.getLocalizedMessage());
                }

                voiceFrameTimestamp += VOICE_FRAME_DURATION_MILLISECONDS;
            }
        }
    }

    /** Anchors the final vocoder frame at the containing radio-slot timestamp. */
    private static long firstVoiceFrameTimestamp(long timeslotTimestamp, int frameCount)
    {
        long offset = Math.max(0, frameCount - 1) * VOICE_FRAME_DURATION_MILLISECONDS;
        return Math.max(1L, timeslotTimestamp - offset);
    }

    private void processEncryptedAudio(AbstractVoiceTimeslot timeslot)
    {
        if(!hasAudioCodec() || !prepareEncryptedAudioDecryptor())
        {
            return;
        }

        List<BinaryMessage> decrypted = new ArrayList<>(timeslot.getVoiceFrames().size());

        for(BinaryMessage voiceFrame: timeslot.getVoiceFrames())
        {
            try
            {
                decrypted.add(mVoiceFrameDecryptor.decrypt(voiceFrame));
            }
            catch(VoiceFrameDecryptionException e)
            {
                mLog.debug("Error decrypting P25 Phase 2 AMBE audio", e);
                closeCurrentAudioSegment(timeslot.getTimestamp());
                resetEncryptionTracking();
                return;
            }
        }

        processAudio(decrypted, timeslot.getTimestamp());
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
     * Registers a message listener to receive AMBE tone identifier messages.
     * @param listener to register
     */
    @Override
    public void setMessageListener(Listener<IMessage> listener)
    {
        mMessageListener = listener;
    }

    /**
     * Removes the message listener
     */
    @Override
    public void removeMessageListener()
    {
        mMessageListener = null;
    }

    /**
     * Process AMBE audio frame tone metadata.  Tracks the count of sequential frames containing tone metadata to
     * provide a list of each unique tone and a time duration (milliseconds) for the tone.  Tones are concatenated into
     * a comma separated list and included as call segment metadata.
     */
    public class ToneMetadataProcessor
    {
        // Minimum consecutive frames reporting the same tone before it is treated as real.
        // AMBE frames are ~20ms each; 3 frames = ~60ms holdoff. Real tones are sustained for
        // hundreds of milliseconds; single-frame artifacts from voice misclassification are suppressed.
        // Audio frames are held during the holdoff window and discarded if the tone never qualifies,
        // so the audible artifact is suppressed, not just the metadata identifier.
        private static final int MINIMUM_TONE_FRAME_COUNT = 3;

        private static final List<DecodedVoiceFrame> EMPTY = Collections.emptyList();

        private List<Tone> mTones = new ArrayList<>();
        private Tone mCurrentTone;
        private List<DecodedVoiceFrame> mHeldAudio = new ArrayList<>();

        /**
         * Resets or clears any accumulated call tone sequences to prepare for the next call.
         */
        public void reset()
        {
            mTones.clear();
            mCurrentTone = null;
            mHeldAudio.clear();
        }

        /**
         * Processes one decoded AMBE frame: evaluates tone metadata and gates audio output.
         *
         * Returns the audio buffers that should be committed to the audio segment.  During the
         * holdoff window the returned list is empty; once the threshold is crossed the previously
         * held frames plus the current frame are all returned together.  When there is no tone
         * metadata the single decoded frame is returned immediately.
         *
         * @param audioWithMetadata decoded AMBE frame with optional tone metadata
         * @param timestamp of the carrier message
         * @return decoded frames with their original carrier timestamps and fingerprints; never null, may be empty
         */
        private List<DecodedVoiceFrame> processAudio(IAudioWithMetadata audioWithMetadata, long timestamp,
                                                     long voiceFrameFingerprint)
        {
            float[] audio = audioWithMetadata.getAudio();
            DecodedVoiceFrame decodedVoiceFrame = new DecodedVoiceFrame(audioWithMetadata, timestamp,
                voiceFrameFingerprint);
            boolean hasToneMetadata = audioWithMetadata.getMetadata().entrySet().stream()
                .anyMatch(entry -> !isVoiceFrameQualityMetadata(entry.getKey()) &&
                    AmbeTone.fromValues(entry.getKey(), entry.getValue()) != AmbeTone.INVALID);

            if(!hasToneMetadata)
            {
                // No tone on this frame — flush any held audio and close any pending tone.
                if(mCurrentTone != null && mCurrentTone.getDuration() < MINIMUM_TONE_FRAME_COUNT)
                {
                    mHeldAudio.clear();
                }
                mCurrentTone = null;

                if(audio != null)
                {
                    return List.of(decodedVoiceFrame);
                }
                return EMPTY;
            }

            // Frame has tone metadata — process each entry (JMBE puts at most one).
            ToneIdentifier toneIdentifier = null;

            for(Map.Entry<String,String> entry : audioWithMetadata.getMetadata().entrySet())
            {
                if(isVoiceFrameQualityMetadata(entry.getKey()))
                {
                    continue;
                }

                AmbeTone tone = AmbeTone.fromValues(entry.getKey(), entry.getValue());

                if(tone == AmbeTone.INVALID)
                {
                    continue;
                }

                if(mCurrentTone == null || mCurrentTone.getAmbeTone() != tone)
                {
                    // New tone — discard any held audio from a previous sub-threshold tone
                    if(mCurrentTone != null && mCurrentTone.getDuration() < MINIMUM_TONE_FRAME_COUNT)
                    {
                        mHeldAudio.clear();
                    }
                    mCurrentTone = new Tone(tone);
                }

                mCurrentTone.incrementDuration();

                if(mCurrentTone.getDuration() < MINIMUM_TONE_FRAME_COUNT)
                {
                    // Still in holdoff — buffer audio, suppress output
                    if(audio != null)
                    {
                        mHeldAudio.add(decodedVoiceFrame);
                    }
                    return EMPTY;
                }

                if(mCurrentTone.getDuration() == MINIMUM_TONE_FRAME_COUNT)
                {
                    // Threshold just crossed — commit tone to sequence
                    mTones.add(mCurrentTone);
                }

                toneIdentifier = P25ToneIdentifier.create(new ToneSequence(new ArrayList<>(mTones)));
            }

            if(toneIdentifier != null)
            {
                broadcast(toneIdentifier, timestamp);
            }

            // Threshold met — flush any held frames plus the current frame
            if(!mHeldAudio.isEmpty())
            {
                List<DecodedVoiceFrame> toCommit = new ArrayList<>(mHeldAudio);
                mHeldAudio.clear();
                if(audio != null)
                {
                    toCommit.add(decodedVoiceFrame);
                }
                return toCommit;
            }

            if(audio != null)
            {
                return List.of(decodedVoiceFrame);
            }
            return EMPTY;
        }

        /**
         * Broadcasts the identifier to a registered listener and creates a new AMBE tone identifier message when tones
         * are present.
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
                sb.append("P25.2 Timeslot ");
                sb.append(getTimeslot());
                sb.append("Audio Tone Sequence Decoded: ");
                sb.append(identifier);

                mMessageListener.receive(new ToneIdentifierMessage(Protocol.APCO25_PHASE2, getTimeslot(), timestamp,
                    identifier, sb.toString()));
            }
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
            if(event.getTimeslot() == getTimeslot() && event.getSquelchState() == SquelchState.SQUELCH)
            {
                closeCurrentAudioSegment();
                reset();
            }
        }
    }
}

/*
 * Copyright (C) 2026
 * Licensed under the GNU General Public License, version 3 or later.
 */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One detached complete native-audio call outcome. Process-local ids are for bounded correlation, not storage. */
public record CompletedToneSequence(AudioCallSnapshot snapshot, long firstVoiceSampleTimestamp,
                                    Source source, Method method, List<Element> tones)
{
    public static final int MAXIMUM_TONES = 32;

    public enum Source
    {
        DECODED_AUDIO(1, "decoded_audio"), ENCODED(2, "encoded");
        private final int mCode;
        private final String mValue;
        Source(int code, String value) { mCode = code; mValue = value; }
        public int code() { return mCode; }
        public String value() { return mValue; }
        public String wireName() { return mValue; }
    }

    public enum Method
    {
        P25_PCM_TONE_V1(1, "p25_pcm_tone_v1"), AMBE_TONE_V1(2, "ambe_tone_v1"),
        P25_PCM_AMBE_TONE_V2(3, "p25_pcm_ambe_tone_v2"),
        ANALOG_PCM_TONE_V1(4, "analog_pcm_tone_v1");
        private final int mCode;
        private final String mValue;
        Method(int code, String value) { mCode = code; mValue = value; }
        public int code() { return mCode; }
        public String value() { return mValue; }
        public String wireName() { return mValue; }
    }

    /** Frequencies are explicit milliHz; an unavailable encoded frequency is empty, never invented. */
    public record Element(List<Integer> frequenciesMilliHertz, String codecSymbol, Long offsetMilliseconds,
                          long durationMilliseconds, Double confidence)
    {
        public Element
        {
            frequenciesMilliHertz = List.copyOf(frequenciesMilliHertz);
            if(frequenciesMilliHertz.size() > 2 || frequenciesMilliHertz.stream().anyMatch(f -> f <= 0 || f > 8_000_000) ||
                codecSymbol != null && (codecSymbol.isBlank() || codecSymbol.length() > 64) ||
                offsetMilliseconds != null && offsetMilliseconds < 0 || durationMilliseconds <= 0 ||
                confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1))
                throw new IllegalArgumentException("Invalid tone element");
            if(frequenciesMilliHertz.isEmpty() && codecSymbol == null)
                throw new IllegalArgumentException("Frequency or encoded symbol required");
        }
    }

    public CompletedToneSequence
    {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(source); Objects.requireNonNull(method);
        tones = List.copyOf(tones);
        if(snapshot.callId() == null || snapshot.callLegId() == null || !snapshot.complete() ||
            firstVoiceSampleTimestamp < 0 || tones.size() > MAXIMUM_TONES ||
            source == Source.DECODED_AUDIO && method != Method.P25_PCM_TONE_V1 && method != Method.P25_PCM_AMBE_TONE_V2 && method != Method.ANALOG_PCM_TONE_V1 ||
            source == Source.ENCODED && method != Method.AMBE_TONE_V1)
            throw new IllegalArgumentException("Complete bounded native-call context required");
        for(Element element: tones)
        {
            if(source == Source.ENCODED && (element.codecSymbol() == null || element.confidence() != null) ||
                source == Source.DECODED_AUDIO && method != Method.ANALOG_PCM_TONE_V1 &&
                    (element.codecSymbol() != null || element.frequenciesMilliHertz().size() != 1))
                throw new IllegalArgumentException("Tone source and fields differ");
            if(method == Method.P25_PCM_AMBE_TONE_V2) requireNominalPcmTone(element);
            if(method == Method.ANALOG_PCM_TONE_V1) requireAnalogPcmTone(element);
        }
        //Detach on the observer worker. Existing AMBE ToneIdentifiers contain mutable duration objects;
        //the explicit elements own that evidence, so do not retain those identifiers in captured call facts.
        IdentifierCollection identifiers = new DetachedIdentifiers(snapshot.identifierCollection(), snapshot.timeslot());
        snapshot = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(), null, identifiers, Set.of(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(), snapshot.burstGeneration(),
            snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(), snapshot.burstActive(), true,
            snapshot.encryptionState(), snapshot.recordAudio(), snapshot.recordingMetadata(), snapshot.voiceCallQuality(),
            snapshot.callLegId(), snapshot.callLegSource(), snapshot.callEncryptionEvidence(),
            snapshot.analogCallAttribution(), snapshot.activityCallToken());
    }

    /** New nominal PCM evidence must not be confused with legacy measured-frequency evidence. */
    static void requireNominalPcmTone(Element element)
    {
        if(element.codecSymbol() != null || element.frequenciesMilliHertz().size() != 1 ||
            AmbeToneFrequencyMapping.nearest(element.frequenciesMilliHertz().getFirst())
                .filter(match -> match.frequencyMilliHertz() == element.frequenciesMilliHertz().getFirst()).isEmpty())
        {
            throw new IllegalArgumentException("Nominal PCM tone method requires a predefined single frequency");
        }
    }

    /** Analog paging uses nominal singles; waveform DTMF keeps its standard symbol and frequency pair. */
    public static void requireAnalogPcmTone(Element element)
    {
        if(element.codecSymbol() == null)
        {
            if(element.frequenciesMilliHertz().size() != 1 ||
                PagingToneFrequencyMapping.nearest(element.frequenciesMilliHertz().getFirst())
                    .filter(match -> match.frequencyMilliHertz() == element.frequenciesMilliHertz().getFirst()).isEmpty())
                throw new IllegalArgumentException("Analog PCM singles require a predefined nominal frequency");
            if(element.frequenciesMilliHertz().getFirst() < 281_250)
                throw new IllegalArgumentException("Subaudible squelch tones are not call tone evidence");
            return;
        }
        AmbeTone tone;
        try { tone = AmbeTone.valueOf(element.codecSymbol()); }
        catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown analog tone symbol"); }
        if(!tone.name().startsWith("DTMF_") || !EncodedToneMetadata.frequencies(tone).equals(element.frequenciesMilliHertz()))
            throw new IllegalArgumentException("Analog waveform symbols require standard DTMF frequencies");
    }

    public AudioCallId callId() { return snapshot.callId(); }
    public CallLegId callLegId() { return snapshot.callLegId(); }
    public CallLegSource callLegSource() { return snapshot.callLegSource(); }
    public long callStartTimestamp() { return snapshot.startTimestamp(); }
    public long completedTimestamp() { return snapshot.lastActivityTimestamp(); }
    public Protocol protocol()
    {
        if(snapshot.callLegSource().decoderType() == io.github.dsheirer.module.decode.DecoderType.P25_PHASE2)
            return Protocol.APCO25_PHASE2;
        return snapshot.callLegSource().decoderType() != null ? snapshot.callLegSource().decoderType().getProtocol() :
            source == Source.DECODED_AUDIO ? Protocol.APCO25 : Protocol.UNKNOWN;
    }

    /** Only a matching captured physical call may reuse a cached result. */
    public boolean matches(AudioCallSnapshot other)
    {
        return other != null && other.complete() && callId().equals(other.callId()) && callLegId().equals(other.callLegId()) &&
            callLegSource().equals(other.callLegSource()) && callStartTimestamp() == other.startTimestamp() &&
            completedTimestamp() == other.lastActivityTimestamp();
    }

    private static final class DetachedIdentifiers extends IdentifierCollection
    {
        private DetachedIdentifiers(IdentifierCollection original, int timeslot)
        {
            super(original != null ? original.getIdentifiers().stream().filter(i -> !(i instanceof ToneIdentifier)).toList() : List.of());
            super.setTimeslot(timeslot);
        }
        @Override public void setTimeslot(int timeslot) { throw new UnsupportedOperationException("Detached tone context"); }
    }
}

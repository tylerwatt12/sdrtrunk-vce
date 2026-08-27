/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio;

import com.google.common.eventbus.EventBus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.VoiceFrameQualityObservation;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractAudioModuleTest
{
    @Test
    void defaultRolloverLengthIsOneMinuteInMilliseconds()
    {
        assertEquals(60_000L, AbstractAudioModule.DEFAULT_SEGMENT_AUDIO_LENGTH_MILLISECONDS);
    }

    @Test
    void distinguishesLinkedSegmentRolloverFromTerminalCompletion()
    {
        TestAudioModule module = new TestAudioModule();
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.appendFrame();
        module.appendFrame();
        module.stop();

        List<AudioCallEvent> completions = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .toList();
        assertEquals(2, completions.size());
        assertTrue(completions.get(0).continuationExpected());
        assertFalse(completions.get(1).continuationExpected());
        assertNotEquals(completions.get(0).callId(), completions.get(1).callId());
        assertEquals(completions.get(0).snapshot().callLegId(), completions.get(1).snapshot().callLegId(),
            "Forced chunks from one decoder call leg must retain one leg identity");

        module.appendFrame();
        module.stop();
        List<AudioCallEvent> allCompletions = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .toList();
        assertNotEquals(completions.get(1).snapshot().callLegId(), allCompletions.get(2).snapshot().callLegId(),
            "A new terminally separated call must receive a new leg identity");
    }

    @Test
    void metadataSnapshotContainsAliasPolicyFromSameIdentifierUpdate()
    {
        AliasListDefinition definition = new AliasListDefinition("Test", AliasListFamily.P25);
        AliasList aliasList = new AliasList(definition);
        Alias recorded = new Alias("Recorded");
        recorded.setAliasListDefinition(definition);
        recorded.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1200));
        recorded.setRecordable(true);
        aliasList.addAlias(recorded);
        TestAudioModule module = new TestAudioModule(aliasList);
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.appendFrame();
        module.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
            APCO25Talkgroup.create(1200), IdentifierUpdateNotification.Operation.ADD, 0));

        AudioCallEvent metadata = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.METADATA_UPDATED)
            .findFirst().orElseThrow();
        assertTrue(metadata.snapshot().recordAudio(),
            "The metadata event must include the alias policy for the identifier that triggered it");
        assertEquals(APCO25Talkgroup.create(1200), metadata.snapshot().identifierCollection().getToIdentifier());
    }

    @Test
    void reusesStableFrameSnapshotAndRefreshesQualityOncePerSecond()
    {
        TestAudioModule module = new TestAudioModule(AliasList.empty("Test"), 2_000);
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        for(int x = 0; x < 50; x++)
        {
            module.appendQualityFrame();
        }

        List<AudioCallEvent> frames = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
            .toList();
        assertEquals(50, frames.size());
        assertSame(frames.get(0).snapshot(), frames.get(1).snapshot(),
            "Ordinary frames should reuse the latest structural snapshot");
        assertSame(frames.get(0).snapshot(), frames.get(48).snapshot());
        assertNotSame(frames.get(48).snapshot(), frames.get(49).snapshot(),
            "The once-per-second quality sample should publish a fresh snapshot");
        assertEquals(50, frames.get(49).snapshot().voiceCallQuality().observedFrameCount());
    }

    @Test
    void carriesImmutableSourceCarrierTimestampsAndVoiceFingerprint()
    {
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE2, "configuration-id", "MARCS Site",
            "site-guid", 42, new P25SiteIdentity(0xBEE00, 0x348, 2, 19), true);
        TestAudioModule module = new TestAudioModule(AliasList.empty("Test"), 2_000, source);
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.beginAt(1_000);
        module.beginBurstAt(1_010);
        module.appendFrameAt(1_200, 0x1234ABCDL);
        module.endBurstAt(1_400);
        module.closeAt(1_500);

        AudioCallEvent frame = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
            .findFirst().orElseThrow();
        AudioCallEvent completion = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .findFirst().orElseThrow();
        assertEquals(0x1234ABCDL, frame.voiceFrameFingerprint());
        assertEquals(source, completion.snapshot().callLegSource());
        assertEquals(1_000, completion.snapshot().startTimestamp());
        assertEquals(1_400, completion.snapshot().lastActivityTimestamp());
        assertEquals(1_010, completion.snapshot().lastBurstStartTimestamp());
        assertEquals(1_400, completion.snapshot().lastBurstEndTimestamp());
        assertEquals(CallEncryptionState.CLEAR, completion.snapshot().encryptionState());
    }

    @Test
    void normalizesMissingCarrierTimeBeforeUpdatingBuilderAndPublishingEvent()
    {
        TestAudioModule module = new TestAudioModule();
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.appendFrameAt(0L, 0x1234L);

        AudioCallEvent frame = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME)
            .findFirst().orElseThrow();
        assertTrue(frame.voiceFrameTimestamp() > 0L);
        assertEquals(frame.voiceFrameTimestamp(), frame.snapshot().lastActivityTimestamp());
    }

    @Test
    void classifiesCallsAsTrunkedAfterDmrRestChannelConversion()
    {
        CallLegSource initialSource = new CallLegSource(DecoderType.DMR, "configuration-id", "DMR Site",
            "site-guid", 42, null, false);
        TestAudioModule module = new TestAudioModule(AliasList.empty("Test"), 2_000, initialSource);
        EventBus eventBus = new EventBus("audio-module-channel-conversion-test");
        module.setInterModuleEventBus(eventBus);
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.beginAt(1_000);
        eventBus.post(new ChannelConfigurationChangeNotification(
            new Channel("Converted rest channel", Channel.ChannelType.TRAFFIC)));
        module.closeAt(1_500);

        AudioCallEvent completion = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .findFirst().orElseThrow();
        assertTrue(completion.snapshot().callLegSource().trafficChannel(),
            "Calls completed after a rest-channel handoff must enter trunked logical-call statistics");
        assertEquals(CallEncryptionState.UNKNOWN, completion.snapshot().encryptionState(),
            "A non-P25 metadata-only call has no authoritative encryption observation");
    }

    @Test
    void repeatedEncryptionObservationsPublishOnlyStateChangesPerSegment()
    {
        TestAudioModule module = new TestAudioModule();
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);
        module.beginAt(1_000);

        for(int index = 0; index < 1_000; index++)
        {
            module.markEncryptedAt(1_100 + index);
        }

        assertEquals(1, metadataUpdateCount(events),
            "Repeated encrypted voice frames must not rebuild and offer the same metadata snapshot");

        CallEncryptionEvidence evidence = new CallEncryptionEvidence(0x84, 0x101, 123L);
        EncryptionKeyIdentifier key = EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x84, 0x101));

        for(int index = 0; index < 1_000; index++)
        {
            module.setEncryptionEvidenceAt(evidence, key, 2_100 + index);
        }

        assertEquals(2, metadataUpdateCount(events),
            "The first usable key/evidence update is structural; identical repeats are not");
        module.closeAt(3_500);
        module.beginAt(4_000);
        module.markEncryptedAt(4_100);
        assertEquals(3, metadataUpdateCount(events),
            "A new physical call segment must publish its own clear-to-encrypted transition");
    }

    private static long metadataUpdateCount(List<AudioCallEvent> events)
    {
        return events.stream().filter(event -> event.eventType() == AudioCallEventType.METADATA_UPDATED).count();
    }

    private static class TestAudioModule extends AbstractAudioModule
    {
        private TestAudioModule()
        {
            this(AliasList.empty("Test"));
        }

        private TestAudioModule(AliasList aliasList)
        {
            super(aliasList, 0, 20);
        }

        private TestAudioModule(AliasList aliasList, long maxSegmentAudioLength)
        {
            super(aliasList, 0, maxSegmentAudioLength);
        }

        private TestAudioModule(AliasList aliasList, long maxSegmentAudioLength, CallLegSource callLegSource)
        {
            super(aliasList, 0, maxSegmentAudioLength, callLegSource);
        }

        private void appendFrame()
        {
            addAudio(new float[160]);
        }

        private void appendQualityFrame()
        {
            addAudio(new float[160], new VoiceFrameQualityObservation(
                VoiceFrameQualityObservation.Outcome.DECODED, 0, 72));
        }

        private void beginAt(long timestamp)
        {
            beginCurrentAudioSegment(timestamp);
        }

        private void beginBurstAt(long timestamp)
        {
            beginCurrentAudioBurst(timestamp);
        }

        private void appendFrameAt(long timestamp, long voiceFrameFingerprint)
        {
            addAudio(new float[160], null, timestamp, voiceFrameFingerprint);
        }

        private void endBurstAt(long timestamp)
        {
            endCurrentAudioBurst(timestamp);
        }

        private void closeAt(long timestamp)
        {
            closeAudioSegment(timestamp);
        }

        private void markEncryptedAt(long timestamp)
        {
            markCurrentCallEncrypted(timestamp);
        }

        private void setEncryptionEvidenceAt(CallEncryptionEvidence evidence, EncryptionKeyIdentifier key,
                                             long timestamp)
        {
            setCurrentCallEncryptionEvidence(evidence, key, timestamp);
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }
    }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SourceCallReferenceTest
{
    @Test void sameExactProducerBindingHasOneDurableCanonicalReferenceAndUnknownStaysUnknown()
    {
        String first = SourceCallReference.fromToken("process-a:call-1");
        assertEquals(first, SourceCallReference.fromToken(new String("process-a:call-1")));
        assertEquals(first, UUID.fromString(first).toString());
        assertEquals(first, SourceCallReference.canonical(first));
        assertNotEquals(first, SourceCallReference.fromToken("process-a:call-2"));
        assertNotEquals(first, SourceCallReference.fromToken("process-b:call-1"));
        assertNull(SourceCallReference.fromToken(null));
        assertNull(SourceCallReference.fromToken(" \t"));
        assertNull(SourceCallReference.fromToken("a".repeat(241)));
        assertNull(SourceCallReference.canonical(null));
        for(String invalid: List.of("", "process-a:call-1", first.toUpperCase(), first.replace("-", ""),
            "1-1-1-1-1", "gggggggg-gggg-gggg-gggg-gggggggggggg"))
            assertThrows(IllegalArgumentException.class, () -> SourceCallReference.canonical(invalid));
    }

    @Test void decodeSnapshotsDetachTheExactBindingWithoutSerializingIt() throws Exception
    {
        DecodeEvent original = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000).build();
        original.setActivityCallToken("private-producer:call-1");
        DecodeEventSnapshot snapshot = new DecodeEventSnapshot(original);
        original.setActivityCallToken("later-call:2");
        assertEquals("private-producer:call-1", snapshot.getActivityCallToken());
        assertIgnoredByJackson(DecodeEventSnapshot.class,"activityCallToken");
    }

    @Test void completedCallsKeepAllReceiverReferencesAndFallBackToTheNativeWinnerWithoutGuessing() throws Exception
    {
        AudioCallSnapshot winner = snapshot(1, true, "winner:1", null);
        CompletedAudioCall single = new CompletedAudioCall(winner, List.of(new float[160]));
        assertEquals(List.of(SourceCallReference.fromToken("winner:1")), single.sourceCallReferences());

        CallLegSummary legacyWinner = summary(winner, true, null);
        CompletedAudioCall fallback = new CompletedAudioCall(single.logicalCallId(), winner, single.audioBuffers(),
            single.resolvedPolicy(), List.of(legacyWinner));
        assertEquals(single.sourceCallReferences(), fallback.sourceCallReferences());

        AudioCallSnapshot loser = snapshot(2, true, "loser:1", null);
        CompletedAudioCall merged = new CompletedAudioCall(single.logicalCallId(), winner, single.audioBuffers(),
            single.resolvedPolicy(), List.of(summary(winner, true, "winner:1"), summary(loser, false, "loser:1")));
        assertEquals(Set.of(SourceCallReference.fromToken("winner:1"), SourceCallReference.fromToken("loser:1")),
            Set.copyOf(merged.sourceCallReferences()));
        assertEquals(2, merged.sourceCallReferences().size());

        AudioCallSnapshot unknown = snapshot(3, true, null, null);
        assertTrue(new CompletedAudioCall(unknown, List.of(new float[160])).sourceCallReferences().isEmpty());
        assertIgnoredByJackson(AudioCallSnapshot.class,"activityCallToken");
        assertIgnoredByJackson(CallLegSummary.class,"activityCallToken");
        assertIgnoredByJackson(CompletedAudioCall.class,"sourceCallReferences");
    }

    @Test void coordinatorRetainsTheBindingsOfLosingReceiverCopies() throws Exception
    {
        var definition = new AliasListDefinition("Call link fixture", AliasListFamily.P25);
        definition.setId(71);
        AliasList aliases = new AliasList(definition);
        List<CompletedAudioCall> calls = new CopyOnWriteArrayList<>();
        var configuration = new AudioCallCoordinator.ResolverConfiguration(256,32,25,150,32,16,1_000_000,4_000_000);
        var coordinator = new AudioCallCoordinator(calls::add, null, null, null, configuration);
        try
        {
            for(int site = 1; site <= 3; site++)
            {
                AudioCallSnapshot active = snapshot(site, false, "receiver-" + site + ":1", aliases);
                coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED,active,null,false,0,0));
                for(int frame = 0; frame < 3; frame++)
                    coordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME,active,new float[160],false,
                        11L + frame, 1_000L + frame * 20L));
                coordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
                    snapshot(site,true,"receiver-" + site + ":1",aliases),null,false,0,0));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while(calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1,calls.size());
            CompletedAudioCall call = calls.getFirst();
            assertEquals(3,call.receiverLegCount());
            assertEquals(1,call.callLegSummaries().stream().filter(CallLegSummary::winner).count());
            assertEquals(Set.of("receiver-1:1","receiver-2:1","receiver-3:1"),
                Set.copyOf(call.callLegSummaries().stream().map(CallLegSummary::activityCallToken).toList()));
            assertEquals(Set.of(SourceCallReference.fromToken("receiver-1:1"),SourceCallReference.fromToken("receiver-2:1"),
                SourceCallReference.fromToken("receiver-3:1")),Set.copyOf(call.sourceCallReferences()));
        }
        finally { coordinator.dispose(); }
    }

    @Test void amAndNbfmSquelchCallsShareOneBindingAcrossDecodeEventsAndCompletedAudio()
    {
        for(boolean am: List.of(false,true))
        {
            var definition = new AliasListDefinition("Analog provenance",AliasListFamily.NBFM);
            definition.setId(71);
            AliasList aliases = new AliasList(definition);
            var group = am ? new io.github.dsheirer.module.decode.am.AMTalkgroup(1) :
                new io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup(1);
            var tracker = new io.github.dsheirer.alias.AnalogCallAttributionTracker(
                "00000000-0000-4000-8000-000000000001",aliases,group);
            io.github.dsheirer.module.decode.analog.AnalogDecoderState state = am ?
                new io.github.dsheirer.module.decode.am.AMDecoderState("AM fixture",new io.github.dsheirer.module.decode.am.DecodeConfigAM()) :
                new io.github.dsheirer.module.decode.nbfm.NBFMDecoderState("FM fixture",new io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM());
            state.setCallAttributionTracker(tracker);
            List<io.github.dsheirer.module.decode.analog.AnalogCallEvent> decoded = new java.util.ArrayList<>();
            state.addDecodeEventListener(event -> decoded.add((io.github.dsheirer.module.decode.analog.AnalogCallEvent)event));
            var audio = new io.github.dsheirer.audio.AudioModule(aliases,false);
            audio.setCallAttributionTracker(tracker);
            List<AudioCallEvent> audioEvents = new java.util.ArrayList<>(); audio.setAudioCallEventListener(audioEvents::add);
            try
            {
                state.receiveDecoderStateEvent(new io.github.dsheirer.channel.state.DecoderStateEvent(this,
                    io.github.dsheirer.channel.state.DecoderStateEvent.Event.START,io.github.dsheirer.channel.state.State.CALL));
                audio.getSquelchStateListener().receive(new io.github.dsheirer.audio.squelch.SquelchStateEvent(
                    io.github.dsheirer.audio.squelch.SquelchState.UNSQUELCH,0));
                audio.receive(new float[160]);
                audio.getSquelchStateListener().receive(new io.github.dsheirer.audio.squelch.SquelchStateEvent(
                    io.github.dsheirer.audio.squelch.SquelchState.SQUELCH,0));
                var completed = audioEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
                    .findFirst().orElseThrow();
                String token = decoded.getFirst().getActivityCallToken();
                assertNotNull(token); assertEquals(token,completed.snapshot().activityCallToken());
                assertEquals(List.of(SourceCallReference.fromToken(token)),
                    new CompletedAudioCall(completed.snapshot(),List.of(new float[160])).sourceCallReferences());
            }
            finally { audio.dispose(); }
        }
    }

    private static AudioCallSnapshot snapshot(long receiver, boolean complete, String token, AliasList aliases)
    {
        AudioCallId id = new AudioCallId(receiver,1,0);
        var identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(101),APCO25RadioIdentifier.createFrom(202)));
        var source = new CallLegSource(DecoderType.P25_PHASE1,"channel-" + receiver,"Site " + receiver,"",71L,
            new P25SiteIdentity(0xbee00,0x49f,1,(int)receiver),TrunkedIdentityDomain.STANDARD,ChannelKind.TRUNKED,true);
        return new AudioCallSnapshot(id,null,aliases,identifiers,Set.of(),1_000,4_000,1,1,1_000,4_000,false,complete,
            CallEncryptionState.CLEAR,true,null,VoiceCallQuality.EMPTY,CallLegId.from(id),source,null,null,token);
    }

    private static CallLegSummary summary(AudioCallSnapshot snapshot, boolean winner, String token)
    {
        return new CallLegSummary(snapshot.callLegId(),snapshot.callLegSource(),1_000,4_000,
            VoiceCallQuality.EMPTY,160,false,false,winner,null).withActivityCallToken(token);
    }

    /** Domain snapshots also contain non-JSON audio/identity objects; inspect the real serializer's property contract. */
    private static void assertIgnoredByJackson(Class<?> type, String property)
    {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(mapper.getSerializationConfig().introspect(mapper.constructType(type)).findProperties().stream()
            .noneMatch(value -> value.getName().equals(property) && value.couldSerialize()),
            type.getSimpleName() + " must not serialize " + property);
    }
}

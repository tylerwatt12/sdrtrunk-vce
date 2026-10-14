package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.AudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.squelch.SquelchState;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.analog.AnalogCallEvent;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nbfm.NBFMDecoderState;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalogCallAttributionTrackerTest
{
    private static final String CHANNEL = "223e4567-e89b-42d3-a456-426614174000";
    @Test
    void sharesFrozenExactWinnerUntilTheNextSquelchCall()
    {
        AliasListDefinition definition = new AliasListDefinition("Analog", AliasListFamily.NBFM);
        definition.setId(10);
        AliasList list = new AliasList(definition);
        Alias range = alias(definition, 20, new TalkgroupRange(Protocol.NBFM, 1, 10));
        Alias exact = alias(definition, 21, new Talkgroup(Protocol.NBFM, 1));
        list.addAlias(range);
        list.addAlias(exact);
        var tracker = new AnalogCallAttributionTracker(CHANNEL, list, new NBFMTalkgroup(1));
        var first = tracker.start();
        assertTrue(first.hasSavedScope());
        assertEquals(List.of(21L), first.matches().stream().map(AnalogCallAttribution.Match::aliasId).toList());
        exact.setMatchIdentifier(new Talkgroup(Protocol.NBFM, 2));
        assertSame(first, tracker.start(), "An alias edit cannot rewrite an active call");
        tracker.end(first);
        var second = tracker.start();
        assertEquals(List.of(20L), second.matches().stream().map(AnalogCallAttribution.Match::aliasId).toList());
        assertEquals("TALKGROUP", first.matches().getFirst().matcherType());
        assertEquals(1, first.matches().getFirst().minimum());
    }

    @Test
    void decoderCallCanCarryAttributionWithoutAudioAndAudioUsesTheSameDecision()
    {
        AliasListDefinition definition = new AliasListDefinition("Analog", AliasListFamily.NBFM);
        definition.setId(10);
        AliasList list = new AliasList(definition);
        list.addAlias(alias(definition, 20, new Talkgroup(Protocol.NBFM, 1)));
        var tracker = new AnalogCallAttributionTracker(CHANNEL, list, new NBFMTalkgroup(1));
        var state = new NBFMDecoderState("Repeater", new DecodeConfigNBFM());
        state.setCallAttributionTracker(tracker);
        List<AnalogCallEvent> events = new ArrayList<>();
        state.addDecodeEventListener(event -> events.add((AnalogCallEvent)event));
        state.receiveDecoderStateEvent(new DecoderStateEvent(this, DecoderStateEvent.Event.START, State.CALL));
        assertEquals(1, events.size(), "The call-count owner does not depend on audio buffers");
        var audio = new AudioModule(list, false);
        audio.setCallAttributionTracker(tracker);
        List<AudioCallEvent> audioEvents = new ArrayList<>();
        audio.setAudioCallEventListener(audioEvents::add);
        audio.getSquelchStateListener().receive(new SquelchStateEvent(SquelchState.UNSQUELCH, 0));
        audio.receive(new float[80]);
        audio.getSquelchStateListener().receive(new SquelchStateEvent(SquelchState.SQUELCH, 0));
        var completed = audioEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
            .findFirst().orElseThrow();
        assertSame(events.getFirst().getAttribution(), completed.snapshot().analogCallAttribution());
        state.receiveDecoderStateEvent(new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.CALL));
        assertNotSame(events.getFirst().getAttribution(), tracker.start());
    }

    @Test
    void protocolAndMissingPersistentIdsAreNotGuessed()
    {
        AliasListDefinition definition = new AliasListDefinition("Analog", AliasListFamily.NBFM);
        definition.setId(10);
        AliasList list = new AliasList(definition);
        list.addAlias(alias(definition, 20, new Talkgroup(Protocol.AM, 1)));
        var attribution = new AnalogCallAttributionTracker(CHANNEL, list, new NBFMTalkgroup(1)).start();
        assertTrue(attribution.matches().isEmpty(), "AM 1 does not identify NBFM 1");
        assertFalse(new AnalogCallAttributionTracker(null, list, new NBFMTalkgroup(1)).start().hasSavedScope());
        assertFalse(new AnalogCallAttributionTracker("invalid", list, new NBFMTalkgroup(1)).start().hasSavedScope());
    }

    private static Alias alias(AliasListDefinition definition, long id, io.github.dsheirer.alias.id.AliasID matcher)
    {
        Alias alias = new Alias("Alias " + id);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(matcher);
        return alias;
    }
}

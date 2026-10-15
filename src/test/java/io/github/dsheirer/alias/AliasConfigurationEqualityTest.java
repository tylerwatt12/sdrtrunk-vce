/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AliasConfigurationEqualityTest
{
    @Test
    void everySavedAliasFieldParticipatesWhileDerivedDisplayAndOverlapDoNot()
    {
        Alias source = alias();
        List<Consumer<Alias>> changes = List.of(a -> a.setName("New name"),
            a -> a.setAliasListId(2), a -> a.setDescription("Description"), a -> a.setGroup("Group"),
            a -> a.setColor(42), a -> a.setIconName("Icon"), a -> a.setRecordable(true),
            a -> a.setStreamTalkgroupAlias(new StreamAsTalkgroup(101)),
            a -> a.addBroadcastChannel(new BroadcastChannel("00000000-0000-0000-0000-000000000002", "Feed")),
            a -> a.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 101)),
            a -> a.setMatchIdentifier(new Talkgroup(Protocol.APCO25_PHASE2, 100)));
        for(Consumer<Alias> change: changes)
        {
            Alias copy = copy(source);
            assertTrue(AliasConfigurationEquality.sameAlias(source, copy));
            change.accept(copy);
            assertFalse(AliasConfigurationEquality.sameAlias(source, copy));
        }
        source.addBroadcastChannel(new BroadcastChannel("00000000-0000-0000-0000-000000000001", "Old name"));
        Alias copy = copy(source);
        copy.getBroadcastChannels().iterator().next().setChannelName("New provider name");
        copy.getMatchIdentifier().setOverlap(true);
        assertTrue(AliasConfigurationEquality.sameAlias(source, copy));
    }

    @Test
    void canonicalSubscriberAndToneDurationChangesAreRealEdits()
    {
        Alias source = alias();
        source.setMatchIdentifier(new P25Subscriber(1, 2, 3));
        Alias copy = copy(source);
        assertTrue(AliasConfigurationEquality.sameAlias(source, copy));
        ((P25Subscriber)copy.getMatchIdentifier()).setSubscriberId(4);
        assertFalse(AliasConfigurationEquality.sameAlias(source, copy));
        source.setMatchIdentifier(new TonesID(new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1, 5)))));
        copy = copy(source);
        assertTrue(AliasConfigurationEquality.sameAlias(source, copy));
        copy.setMatchIdentifier(new TonesID(new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1, 6)))));
        assertFalse(AliasConfigurationEquality.sameAlias(source, copy));
    }

    private static Alias alias()
    {
        Alias alias = new Alias("Dispatch");
        alias.setId(1);
        alias.setAliasListId(1);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        return alias;
    }

    private static Alias copy(Alias original)
    {
        Alias copy = AliasFactory.copyOf(original);
        copy.setId(original.getId());
        return copy;
    }
}

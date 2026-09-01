/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractAudioModuleTest
{
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
    }

    @Test
    void metadataSnapshotContainsAliasPolicyFromSameIdentifierUpdate()
    {
        AliasListDefinition definition = new AliasListDefinition("Test", AliasListFamily.P25);
        AliasList aliasList = new AliasList(definition);
        Alias muted = new Alias("Muted");
        muted.setAliasListDefinition(definition);
        muted.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1200));
        muted.setListen(false);
        aliasList.addAlias(muted);
        TestAudioModule module = new TestAudioModule(aliasList);
        List<AudioCallEvent> events = new ArrayList<>();
        module.setAudioCallEventListener(events::add);

        module.appendFrame();
        module.getIdentifierUpdateListener().receive(new IdentifierUpdateNotification(
            APCO25Talkgroup.create(1200), IdentifierUpdateNotification.Operation.ADD, 0));

        AudioCallEvent metadata = events.stream()
            .filter(event -> event.eventType() == AudioCallEventType.METADATA_UPDATED)
            .findFirst().orElseThrow();
        assertTrue(metadata.snapshot().isDoNotMonitor(),
            "The metadata event must include the alias policy for the identifier that triggered it");
        assertEquals(APCO25Talkgroup.create(1200), metadata.snapshot().identifierCollection().getToIdentifier());
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

        private void appendFrame()
        {
            addAudio(new float[160]);
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

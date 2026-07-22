/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.StuffBitsMessage;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveActivityMapperTest
{
    @Test
    void mapsEventToSmallNormalizedBrowserValue()
    {
        List<Identifier> identifiers = new ArrayList<>();

        for(int x = 0; x < LiveActivityMapper.MAXIMUM_IDENTIFIERS + 8; x++)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(10_000 + x));
        }

        identifiers.add(APCO25Talkgroup.create(1201));
        String details = "  Dispatch\n  update\t" + "x".repeat(800);
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .duration(250L)
            .channel(new TestChannelDescriptor(851_012_500L))
            .identifiers(new IdentifierCollection(identifiers))
            .details(details)
            .protocol(Protocol.APCO25)
            .timeslot(1)
            .build();

        LiveDecodeEventDto dto = LiveActivityMapper.event("event-1", 7, event);

        assertEquals("event-1", dto.id());
        assertEquals(7, dto.generation());
        assertEquals(1_000L, dto.timeStartMs());
        assertEquals(250L, dto.durationMs());
        assertEquals("voice", dto.category());
        assertEquals("APCO-25", dto.protocol());
        assertEquals(851_012_500L, dto.frequencyHz());
        assertEquals(1, dto.timeslot());
        assertEquals(LiveActivityMapper.MAXIMUM_IDENTIFIERS, dto.from().size());
        assertEquals(1, dto.to().size(), "each displayed role has its own strict limit");
        assertEquals("TO", dto.to().getFirst().role());
        assertFalse(dto.details().contains("\n"));
        assertFalse(dto.details().contains("\t"));
        assertTrue(dto.details().startsWith("Dispatch update"));
        assertTrue(dto.details().length() <= LiveActivityMapper.MAXIMUM_DETAILS_CHARACTERS);
    }

    @Test
    void groupsUnclassifiedEventsUnderOtherFilter()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.TEXT_MESSAGE, 1L)
            .protocol(Protocol.APCO25)
            .build();

        assertEquals("other", LiveActivityMapper.event("event-2", 1, event).category());
    }

    @Test
    void mapsMessagesWithOneBasedTimeslotAndBoundedContent()
    {
        List<Identifier> identifiers = new ArrayList<>();

        for(int x = 0; x < LiveActivityMapper.MAXIMUM_IDENTIFIERS + 12; x++)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(20_000 + x));
        }

        TestMessage message = new TestMessage(4_000L, true, Protocol.APCO25, 1, identifiers,
            "  network\nmessage\t" + "z".repeat(1_200), false);
        LiveMessageDto dto = LiveActivityMapper.message("message-1", 2, 33, message);

        assertEquals("message-1", dto.id());
        assertEquals(2, dto.generation());
        assertEquals(33, dto.sequence());
        assertEquals(4_000L, dto.timestampMs());
        assertTrue(dto.valid());
        assertEquals("APCO-25", dto.protocol());
        assertEquals(1, dto.timeslot());
        assertEquals("TestMessage", dto.category());
        assertEquals(LiveActivityMapper.MAXIMUM_IDENTIFIERS, dto.identifiers().size());
        assertTrue(dto.text().startsWith("network message"));
        assertTrue(dto.text().length() <= LiveActivityMapper.MAXIMUM_MESSAGE_CHARACTERS);
    }

    @Test
    void excludesNoiseMessagesAndContainsFaultyTextRenderer()
    {
        assertNull(LiveActivityMapper.message("noise", 1, 1,
            new StuffBitsMessage(5L, 80, Protocol.APCO25)));

        TestMessage faulty = new TestMessage(6L, false, Protocol.DMR, -1, List.of(), "ignored", true);
        LiveMessageDto dto = LiveActivityMapper.message("faulty", 1, 2, faulty);

        assertEquals("Message text unavailable", dto.text());
        assertNull(dto.timeslot(), "non-slotted decoder messages remain non-slotted in the browser");
    }

    @Test
    void textLimitNeverSplitsUnicodePair()
    {
        assertEquals("A", LiveText.normalize("A\uD83D\uDE80", 2));
        assertEquals("A\uD83D\uDE80", LiveText.normalize("A\uD83D\uDE80B", 3));
    }

    private record TestChannelDescriptor(long getDownlinkFrequency) implements IChannelDescriptor
    {
        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public int[] getFrequencyBandIdentifiers()
        {
            return new int[0];
        }

        @Override
        public void setFrequencyBand(IFrequencyBand bandIdentifier)
        {
        }

        @Override
        public boolean isTDMAChannel()
        {
            return true;
        }

        @Override
        public int getTimeslotCount()
        {
            return 2;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }

        @Override
        public String toString()
        {
            return "Channel 1";
        }
    }

    private record TestMessage(long getTimestamp, boolean isValid, Protocol getProtocol, int getTimeslot,
                               List<Identifier> getIdentifiers, String text, boolean failToRender) implements IMessage
    {
        @Override
        public String toString()
        {
            if(failToRender)
            {
                throw new IllegalStateException("synthetic renderer failure");
            }

            return text;
        }
    }
}

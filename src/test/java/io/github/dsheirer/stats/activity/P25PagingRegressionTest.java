/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.edac.CRCP25;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.P25P1Interleave;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessageFactory;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderState;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessageFactory;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.IndirectGroupPagingWithoutPriority;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.IndividualPagingWithPriority;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PowerControlSignalQuality;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.SNDCPDataPageRequest;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.TelephoneInterconnectAnswerRequest;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnitToUnitAnswerRequestAbbreviated;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.UnitToUnitAnswerRequestExtended;
import io.github.dsheirer.module.decode.p25.reference.Direction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Constructed messages; field expectations come from AABC-E 5.2.6 and BBAD-A 3.10/12/16/37/38. */
class P25PagingRegressionTest
{
    private static final int TARGET = 0x123456;
    private static final int SOURCE = 0x234567;
    private static final int FOLLOWING_TARGET = 0x345678;
    private static final int[] RADIOS = {0x123456, 0x800001, 0xFFFFFF, 0x234567};
    private static final int[] GROUPS = {0x1234, 0x8001, 0xFFFF, 0x4567};

    @Test
    void phase1DataPageExportsOnlyThePagedRadioRegardlessOfAccessControl()
    {
        for(int dac: new int[]{0, 1, 0xFFFF})
        {
            for(int radio: new int[]{1, TARGET, 0xFFFFFF})
            {
                TSBKMessage decoded = decodePhase1(phase1Page(dac, radio));
                assertTrue(decoded.isValid());
                var page = assertInstanceOf(io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.
                    SNDCPDataPageRequest.class, decoded);
                assertEquals(dac, page.getDataAccessControl());
                assertEquals(List.of(radio), values(decoded.getIdentifiers()));
                assertEquals(Role.TO, decoded.getIdentifiers().getFirst().getRole());
                assertFalse(decoded.toString().contains(" FM:"));
                assertTrue(decoded.toString().contains(" TO:" + radio));
                assertTrue(decoded.toString().contains("DATA ACCESS CONTROL:" + String.format("%04X", dac)));
                assertPhase1Projection(decoded, radio);
            }
        }
    }

    @Test
    void phase1InvalidChecksumAndProtectedArgumentsCannotCreatePagingEvents()
    {
        CorrectedBinaryMessage damaged = phase1Page(1, TARGET);
        damaged.flip(80);
        damaged.flip(81);
        TSBKMessage invalid = decodePhase1(damaged);
        assertFalse(invalid.isValid());
        assertPhase1Projection(invalid, null);

        CorrectedBinaryMessage protectedBits = phase1Page(1, TARGET);
        protectedBits.set(1);
        sealPhase1(protectedBits);
        TSBKMessage protectedMessage = decodePhase1(protectedBits);
        assertTrue(protectedMessage.isValid());
        assertTrue(protectedMessage.isEncrypted());
        assertPhase1Projection(protectedMessage, null);
    }

    @Test
    void phase2SndcpPageKeepsItsSoleRecipientAtNonzeroOffsets()
    {
        for(int dac: new int[]{0, 1, 0xFFFF})
        {
            CorrectedBinaryMessage bits = frame();
            appendPowerControl(bits, 8);
            int offset = 48;
            put(bits, offset, 8, 0x55);
            put(bits, offset + 8, 8, 0x11);
            put(bits, offset + 16, 16, dac);
            put(bits, offset + 32, 24, TARGET);
            List<MacMessage> messages = decodePhase2(bits);
            assertEquals(2, messages.size());
            SNDCPDataPageRequest page = assertInstanceOf(SNDCPDataPageRequest.class,
                messages.get(1).getMacStructure());
            assertEquals(offset, page.getOffset());
            assertEquals(List.of(TARGET), values(page.getIdentifiers()));
            assertPhase2Projection(messages, List.of(FOLLOWING_TARGET, TARGET),
                List.of(DecodeEventType.COMMAND, DecodeEventType.PAGE), null, false);
        }
    }

    @Test
    void telephoneAnswerRequestPreservesAllAddressBitsAndTheFollowingMessage()
    {
        for(int radio: new int[]{1, TARGET, 0x800001, 0xFFFFFF})
        {
            CorrectedBinaryMessage bits = frame();
            put(bits, 8, 8, 0x4A);
            for(int digit = 0; digit < 10; digit++) put(bits, 16 + digit * 4, 4, (digit + 1) % 10);
            put(bits, 56, 24, radio);
            appendPowerControl(bits, 80); // Nine octets, independently of the opcode length metadata.
            List<MacMessage> messages = decodePhase2(bits);
            assertFollowingMessage(messages, TelephoneInterconnectAnswerRequest.class, 80);
            TelephoneInterconnectAnswerRequest request =
                (TelephoneInterconnectAnswerRequest)messages.getFirst().getMacStructure();
            assertEquals(radio, request.getTargetAddress().getValue());
            assertEquals("1234567890", request.getTelephoneNumber().getValue());
            assertEquals(9, request.getOpcode().getLength());
            assertTrue(request.toString().contains(" TO:" + radio));
            assertPhase2Projection(messages, List.of(radio, FOLLOWING_TARGET),
                List.of(DecodeEventType.REQUEST, DecodeEventType.COMMAND), null, false);
        }
    }

    @Test
    void individualPagesPreserveEveryTargetAndFollowingMessageForEveryLengthCode()
    {
        assertMultipleTargets(false);
    }

    @Test
    void groupPagesPreserveEveryTargetAndFollowingMessageForEveryLengthCode()
    {
        assertMultipleTargets(true);
    }

    private static void assertMultipleTargets(boolean group)
    {
        for(int count = 1; count <= 4; count++)
        {
            int width = group ? 16 : 24;
            int octets = 2 + count * width / 8;
            CorrectedBinaryMessage bits = frame();
            put(bits, 8, 8, group ? 0x11 : 0x12);
            if(!group) put(bits, 16, 4, 0xA); // Priority flags must not affect LEN.
            put(bits, 22, 2, count - 1);
            List<Integer> expected = new ArrayList<>();
            for(int target = 0; target < count; target++)
            {
                int value = (group ? GROUPS : RADIOS)[target];
                expected.add(value);
                put(bits, 24 + target * width, width, value);
            }
            int followingOffset = 8 + octets * 8;
            appendPowerControl(bits, followingOffset);
            List<MacMessage> messages = decodePhase2(bits);
            assertFollowingMessage(messages,
                group ? IndirectGroupPagingWithoutPriority.class : IndividualPagingWithPriority.class,
                followingOffset);
            MacStructure structure = messages.getFirst().getMacStructure();
            assertEquals(expected, values(structure.getIdentifiers()), "Every address, without adjacent bytes");
            assertTrue(structure.getIdentifiers().stream().allMatch(id -> id.getRole() == Role.TO));
            assertTrue(structure.toString().contains(expected.getLast().toString()));
            if(group)
            {
                IndirectGroupPagingWithoutPriority page = (IndirectGroupPagingWithoutPriority)structure;
                assertEquals(count, page.getCount());
                assertEquals(octets, page.getLength());
                if(count < 4) assertNull(page.getTargetGroup4());
            }
            else
            {
                IndividualPagingWithPriority page = (IndividualPagingWithPriority)structure;
                assertEquals(count, page.getCount());
                assertEquals(octets, page.getLength());
                assertEquals(count, IndividualPagingWithPriority.getIdCount(bits, 8));
                assertEquals(octets, IndividualPagingWithPriority.getLength(bits, 8));
                assertEquals(octets, IndividualPagingWithPriority.getLength((BinaryMessage)bits, 8));
                assertTrue(page.isTalkgroupPriority1());
                assertFalse(page.isTalkgroupPriority2());
                if(count < 4) assertNull(page.getTargetAddress4());
            }
            List<DecodeEventType> types = new ArrayList<>();
            for(int target = 0; target < count; target++) types.add(DecodeEventType.PAGE);
            types.add(DecodeEventType.COMMAND);
            expected.add(FOLLOWING_TARGET);
            assertPhase2Projection(messages, expected, types, null, group);
        }
    }

    @Test
    void bothUnitAnswerRequestFormsKeepRealParticipantsAndTheFollowingMessage()
    {
        for(boolean extended: new boolean[]{false, true})
        {
            CorrectedBinaryMessage bits = frame();
            put(bits, 8, 8, extended ? 0xC5 : 0x45);
            put(bits, 16, 8, 0x11);
            put(bits, 24, 24, TARGET);
            if(extended)
            {
                put(bits, 48, 20, 0xABCDE);
                put(bits, 68, 12, 0x123);
                put(bits, 80, 24, SOURCE);
            }
            else put(bits, 48, 24, SOURCE);
            int octets = extended ? 12 : 8;
            int followingOffset = 8 + octets * 8;
            appendPowerControl(bits, followingOffset);
            List<MacMessage> messages = decodePhase2(bits);
            assertFollowingMessage(messages,
                extended ? UnitToUnitAnswerRequestExtended.class : UnitToUnitAnswerRequestAbbreviated.class,
                followingOffset);
            assertEquals(octets, messages.getFirst().getMacStructure().getOpcode().getLength());
            assertPhase2Projection(messages, List.of(TARGET, FOLLOWING_TARGET),
                List.of(DecodeEventType.REQUEST, DecodeEventType.COMMAND), SOURCE, false);
        }
    }

    @Test
    void invalidPhase2ChecksumCannotCreateEvents()
    {
        CorrectedBinaryMessage bits = frame();
        appendPowerControl(bits, 8);
        sealPhase2(bits);
        bits.flip(168);
        List<MacMessage> messages = MacMessageFactory.create(2, DataUnitID.UNSCRAMBLED_SACCH, bits, 1_100L, 168);
        assertFalse(messages.getFirst().isValid());
        assertPhase2Projection(messages, List.of(), List.of(), null, false);
    }

    private static void assertFollowingMessage(List<MacMessage> messages, Class<?> firstType, int offset)
    {
        assertEquals(2, messages.size(), "The next MAC structure must start after the complete first structure");
        assertTrue(firstType.isInstance(messages.getFirst().getMacStructure()));
        PowerControlSignalQuality following = assertInstanceOf(PowerControlSignalQuality.class,
            messages.getLast().getMacStructure());
        assertEquals(offset, following.getOffset());
        assertEquals(FOLLOWING_TARGET, following.getTargetAddress().getValue());
    }

    private static void assertPhase1Projection(TSBKMessage message, Integer radio)
    {
        Channel channel = channel(false);
        P25P1DecoderState state = new P25P1DecoderState(channel, new P25TrafficChannelManager(channel));
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(SOURCE));
        try
        {
            state.receive(message);
            assertEquals(radio == null ? 0 : 1, events.size());
            if(radio != null) assertActivity(channel, events.getFirst(), radio, DecodeEventType.PAGE, null, false);
        }
        finally { state.dispose(); }
    }

    private static void assertPhase2Projection(List<MacMessage> messages, List<Integer> targets,
        List<DecodeEventType> types, Integer source, boolean group)
    {
        Channel channel = channel(true);
        P25P2DecoderState state = new P25P2DecoderState(channel, 2, new P25TrafficChannelManager(channel),
            new PatchGroupManager());
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(999));
        try
        {
            messages.forEach(state::receive);
            assertEquals(targets.size(), events.size());
            for(int index = 0; index < targets.size(); index++)
            {
                IDecodeEvent event = events.get(index);
                assertEquals(2, event.getTimeslot());
                assertActivity(channel, event, targets.get(index), types.get(index), index == 0 ? source : null,
                    group && index < targets.size() - 1);
            }
        }
        finally { state.dispose(); }
    }

    private static void assertActivity(Channel channel, IDecodeEvent event, int target, DecodeEventType type,
        Integer source, boolean group)
    {
        assertEquals(type, event.getEventType());
        assertEquals(target, event.getIdentifierCollection().getToIdentifier().getValue());
        Identifier from = event.getIdentifierCollection().getFromIdentifier();
        assertEquals(source, from != null ? from.getValue() : null);
        ReceiverActivityRecords.ActivityEvent activity = new ReceiverActivityMapper().map(channel, event);
        assertNotNull(activity);
        // The existing generic power-control command has no specialized Activity action.
        assertEquals(type == DecodeEventType.COMMAND ? ReceiverActivityRecords.Action.UNKNOWN :
            ReceiverActivityRecords.Action.valueOf(type.name()), activity.action());
        assertEquals(Integer.toString(target), activity.targetId());
        assertEquals(group ? "TALKGROUP" : "RADIO", activity.targetKind());
        assertEquals(source != null ? source.toString() : null, activity.sourceRadioId());
    }

    private static Channel channel(boolean phase2)
    {
        Channel channel = new Channel("Paging regression",
            phase2 ? Channel.ChannelType.TRAFFIC : Channel.ChannelType.STANDARD);
        channel.setConfigurationId("223e4567-e89b-42d3-a456-426614174000");
        channel.setDecodeConfiguration(phase2 ? new DecodeConfigP25Phase2() : new DecodeConfigP25Phase1());
        return channel;
    }

    private static List<?> values(List<Identifier> identifiers)
    {
        return identifiers.stream().map(Identifier::getValue).toList();
    }

    private static CorrectedBinaryMessage frame() { return new CorrectedBinaryMessage(180); }

    private static void appendPowerControl(CorrectedBinaryMessage bits, int offset)
    {
        put(bits, offset, 8, 0x30);
        put(bits, offset + 8, 24, FOLLOWING_TARGET);
        put(bits, offset + 32, 8, 0x12);
    }

    private static void put(CorrectedBinaryMessage bits, int offset, int width, int value)
    {
        bits.setInt(value, IntField.range(offset, offset + width - 1));
    }

    private static List<MacMessage> decodePhase2(CorrectedBinaryMessage bits)
    {
        sealPhase2(bits);
        List<MacMessage> messages = MacMessageFactory.create(2, DataUnitID.UNSCRAMBLED_SACCH, bits, 1_100L, 168);
        assertTrue(messages.stream().allMatch(MacMessage::isValid));
        return messages;
    }

    /** Fixture checksum search uses the current validator; this is not a CRC conformance test. */
    private static void sealPhase2(CorrectedBinaryMessage bits)
    {
        for(int checksum = 0; checksum < 4096; checksum++)
        {
            put(bits, 168, 12, checksum);
            if(CRCP25.crc12Sacch(bits)) return;
        }
        fail("Unable to construct a valid SACCH checksum");
    }

    private static CorrectedBinaryMessage phase1Page(int dac, int radio)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        put(bits, 0, 8, 0x95);
        put(bits, 16, 8, 0x11);
        put(bits, 40, 16, dac);
        put(bits, 56, 24, radio);
        sealPhase1(bits);
        return bits;
    }

    private static void sealPhase1(CorrectedBinaryMessage bits)
    {
        int remainder = 0;
        for(int index = 0; index < 80; index++)
        {
            boolean feedback = ((remainder & 0x8000) != 0) ^ bits.get(index);
            remainder = (remainder << 1) & 0xFFFF;
            if(feedback) remainder ^= 0x1021;
        }
        put(bits, 80, 16, remainder ^ 0xFFFF);
    }

    /** Ideal-symbol transport wrapper for exercising the unchanged Phase 1 factory, not an RF capture. */
    private static TSBKMessage decodePhase1(CorrectedBinaryMessage bits)
    {
        int[][] transitions = {{2, 12, 1, 15}, {14, 0, 13, 3}, {9, 7, 10, 4}, {5, 11, 6, 8}};
        BinaryMessage coded = new BinaryMessage(196);
        int state = 0;
        for(int index = 0; index < 49; index++)
        {
            int next = index < 48 ? bits.getInt(index * 2, index * 2 + 1) : 0;
            int symbol = transitions[state][next];
            for(int bit = 0; bit < 4; bit++) if((symbol & (8 >> bit)) != 0) coded.set(index * 4 + bit);
            state = next;
        }
        P25P1Interleave.interleaveData(coded, 0, 196);
        SymbolMessage symbols = new SymbolMessage(98);
        for(int index = 0; index < 98; index++)
            symbols.add(Dibit.fromValue(coded.getInt(index * 2, index * 2 + 1)).getIdealPhase());
        return TSBKMessageFactory.create(Direction.OUTBOUND, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            symbols, 0x293, 1_100L);
    }
}

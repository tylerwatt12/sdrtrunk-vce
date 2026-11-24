/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.CRC16;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasAssembler;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasDataBlock;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.MotorolaTalkerAliasHeader;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class MotorolaTalkerAliasPayloadTest
{
    //Synthetic encoded names with independently recorded CRC-16/GSM fields; identities are sample data.
    private static final String CONTROL = "ABCDE11100000177C145478500508BBBEEFCA01383";
    private static final String ZERO_CRC_BYTE = "ABCDE1110000BA77C145478500508BBBEEFCA05900";
    private static final String ZERO_CRC_WORD = "ABCDE11100607A77C145478500508BBBEEFCA00000";
    private static final String UNICODE = "ABCDE1110000019D86752370ACE1E093D9"; //éR😀, including a surrogate pair

    @Test
    void exactCipherLengthPreservesZeroCipherAndChecksumBytesAcrossBothCarriers()
    {
        for(String vector: new String[]{CONTROL, ZERO_CRC_BYTE, ZERO_CRC_WORD})
        {
            var raw = bits(vector);
            assertTrue(CRC16.check(raw), vector);
            assertEquals(raw.size(), MotorolaTalkerAliasPayload.findPayloadEnd(raw));
            for(MotorolaTalkerAliasComplete complete: new MotorolaTalkerAliasComplete[]{phase1(raw), phase2(raw, 1), phase2(raw, 2)})
            {
                assertTrue(complete.isValid(), vector);
                assertEquals(raw.size(), complete.getMessage().size());
                assertEquals("SAMPLE", complete.getAlias().getValue());
                assertEquals(0xABCDE, complete.getRadio().getWacn());
                assertEquals(0x111, complete.getRadio().getSystem());
            }
        }
    }

    @Test
    void unicodeCodeUnitsAndOddNibbleFragmentCountsAreNotAsciiLengthHeuristics()
    {
        var raw = bits(UNICODE);
        for(MotorolaTalkerAliasComplete complete: new MotorolaTalkerAliasComplete[]{phase1(raw), phase2(raw, 2)})
        {
            assertTrue(complete.isValid());
            assertEquals("éR😀", complete.getAlias().getValue());
        }
    }

    @Test
    void invalidCrcTruncationAndNonzeroTransportFillerCannotSupplyAName()
    {
        var corrupt = bits(CONTROL);
        corrupt.flip(80);
        var truncated = bits(ZERO_CRC_BYTE).getSubMessage(0, 160);
        var filler = new CorrectedBinaryMessage(220);
        filler.load(0, bits(CONTROL));
        filler.set(219);
        for(var malformed: new CorrectedBinaryMessage[]{corrupt, truncated, filler, new CorrectedBinaryMessage(80),
            new CorrectedBinaryMessage(MotorolaTalkerAliasPayload.MAXIMUM_PAYLOAD_BITS + 101)})
        {
            assertEquals(-1, MotorolaTalkerAliasPayload.findPayloadEnd(malformed));
            var complete = new MotorolaTalkerAliasComplete(malformed, APCO25Talkgroup.create(101), 7, 0, 1_000,
                Protocol.APCO25);
            assertFalse(complete.isValid());
            assertNull(complete.getAlias());
            assertFalse(complete.toString().contains("SAMPLE"));
        }
        assertFalse(phase1(corrupt).isValid());
        assertFalse(phase2(corrupt, 1).isValid());
    }

    @Test
    void zeroTransportFillerAtEachBitAlignmentDoesNotChangeTheDecodedBoundary()
    {
        var raw = bits(ZERO_CRC_WORD);
        for(int padding = 0; padding <= 100; padding++)
        {
            var padded = new CorrectedBinaryMessage(raw.size() + padding);
            padded.load(0, raw);
            assertEquals(raw.size(), MotorolaTalkerAliasPayload.findPayloadEnd(padded));
        }
    }

    @Test
    void crcValidMalformedSurrogatesAreRejectedBeforeConsumerAcceptance()
    {
        for(String vector: new String[]{"ABCDE1110000010F240271", "ABCDE111000001059DDB48",
            "ABCDE1110000011A540CEECC43"}) //lone high, lone low, high followed by ordinary character
        {
            var raw = bits(vector);
            assertTrue(CRC16.check(raw));
            for(MotorolaTalkerAliasComplete complete: new MotorolaTalkerAliasComplete[]{phase1(raw), phase2(raw, 1)})
            {
                assertFalse(complete.isValid());
                assertNull(complete.getAlias());
            }
        }
    }

    private static MotorolaTalkerAliasComplete phase1(CorrectedBinaryMessage payload)
    {
        int count = (payload.size() + 43) / 44;
        var header = new CorrectedBinaryMessage(72);
        header.load(16, 16, 101);
        header.load(32, 8, count);
        header.load(40, 8, 1);
        header.load(56, 4, 7);
        var assembler = new LCMotorolaTalkerAliasAssembler();
        assertFalse(assembler.add(new LCMotorolaTalkerAliasHeader(header), 1_000));
        for(int index = count; index >= 1; index--)
        {
            var block = new CorrectedBinaryMessage(72);
            block.load(16, 8, index);
            block.load(24, 4, 7);
            copy(payload, (index - 1) * 44, block, 28, 44);
            assertEquals(index == 1, assembler.add(new LCMotorolaTalkerAliasDataBlock(block), 1_100 + count - index));
        }
        return assembler.assemble();
    }

    private static MotorolaTalkerAliasComplete phase2(CorrectedBinaryMessage payload, int timeslot)
    {
        int count = (payload.size() - 64 + 99) / 100;
        var header = new CorrectedBinaryMessage(136);
        header.load(24, 16, 101);
        header.load(40, 8, count);
        header.load(48, 8, 1);
        header.load(64, 4, 7);
        copy(payload, 0, header, 72, 64);
        var assembler = new MotorolaTalkerAliasAssembler(timeslot);
        assertFalse(assembler.add(new MotorolaTalkerAliasHeader(header, 0), 1_000));
        for(int index = count; index >= 1; index--)
        {
            var block = new CorrectedBinaryMessage(136);
            block.load(24, 8, index);
            block.load(32, 4, 7);
            copy(payload, 64 + (index - 1) * 100, block, 36, 100);
            assertEquals(index == 1, assembler.add(new MotorolaTalkerAliasDataBlock(block, 0), 1_100 + count - index));
        }
        MotorolaTalkerAliasComplete complete = assembler.assemble();
        assertEquals(timeslot, complete.getTimeslot());
        return complete;
    }

    private static void copy(CorrectedBinaryMessage source, int start, CorrectedBinaryMessage target, int to, int count)
    {
        for(int bit = 0; bit < count && start + bit < source.size(); bit++) target.set(to + bit, source.get(start + bit));
    }

    private static CorrectedBinaryMessage bits(String hex) { return CorrectedBinaryMessage.loadHex(hex); }
}

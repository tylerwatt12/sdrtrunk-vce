/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.CRCP25;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;

/** Decoded payload fixtures; synthetic variants include independently generated header and packet checksums. */
public final class GroupAffiliationTestMessages
{
    public static final int NAC = 0x951;
    public static final int WORKING_RADIO = 0xFFFAB2;
    public static final long TIMESTAMP = 1_000L;

    private GroupAffiliationTestMessages() {}

    /** Unmodified CRC-valid offline capture already used by the response regression. */
    public static PDUSequence capture()
    {
        return sequence(CorrectedBinaryMessage.loadHex("37FD00FFFAB28228BEE003AD"),
            CorrectedBinaryMessage.loadHex("03A93715FFA6FFC20000000000000000000000009FE2724E"));
    }

    /** Synthetic current-layout response: home BEE00.3A9, announcement FFA6. */
    public static PDUSequence response(int outcome, int homeGroup, int workingGroup)
    {
        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(192);
        payload.load(4, 12, 0x3A9);
        payload.load(16, 16, homeGroup);
        payload.load(32, 16, 0xFFA6);
        payload.load(48, 16, workingGroup);
        payload.load(70, 2, outcome);
        return response(payload);
    }

    /** Seals arbitrary first/last-block contents, including reserved-bit variations. */
    public static PDUSequence response(CorrectedBinaryMessage payload)
    {
        CorrectedBinaryMessage header = CorrectedBinaryMessage.loadHex("37FD00FFFAB28228BEE00000");
        seal(header, 16, 0x11021L, 0xFFFFL);
        seal(payload, 32, 0x104C11DB7L, 0xFFFFFFFFL);
        return sequence(header, payload);
    }

    public static PDUSequence request(int group)
    {
        CorrectedBinaryMessage header = CorrectedBinaryMessage.loadHex("17FD00FFFAB28128BEE00000");
        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(96);
        payload.load(4, 12, 0x3A9);
        payload.load(16, 16, group);
        payload.set(32); //Emergency flag must not become part of the radio address.
        seal(header, 16, 0x11021L, 0xFFFFL);
        seal(payload, 32, 0x104C11DB7L, 0xFFFFFFFFL);
        return sequence(header, payload);
    }

    private static PDUSequence sequence(CorrectedBinaryMessage header, CorrectedBinaryMessage payload)
    {
        assertEquals(0, CRCP25.correctCCITT80(header, 0, 80), "fixture header needs no correction");
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, true), TIMESTAMP, NAC);
        for(int start = 0; start < payload.size(); start += 96)
        {
            sequence.addDataBlock(new DecodedBlock(payload.getSubMessage(start, start + 96)));
        }
        return sequence;
    }

    /** Independent polynomial division; production validates with a shift register. */
    private static void seal(BinaryMessage message, int width, long polynomial, long inversion)
    {
        int dataBits = message.size() - width;
        boolean[] dividend = new boolean[message.size()];
        for(int bit = 0; bit < dataBits; bit++) dividend[bit] = message.get(bit);
        for(int bit = 0; bit < dataBits; bit++)
        {
            if(dividend[bit])
            {
                for(int shift = 0; shift <= width; shift++)
                {
                    if(((polynomial >>> (width - shift)) & 1L) != 0) dividend[bit + shift] ^= true;
                }
            }
        }
        long crc = 0;
        for(int bit = dataBits; bit < dividend.length; bit++) crc = (crc << 1) | (dividend[bit] ? 1 : 0);
        message.load(dataBits, width, crc ^ inversion);
    }

    private static final class DecodedBlock extends UnconfirmedDataBlock
    {
        private final CorrectedBinaryMessage mMessage;

        private DecodedBlock(CorrectedBinaryMessage message)
        {
            super(new SymbolMessage(98));
            mMessage = message;
        }

        @Override
        public CorrectedBinaryMessage getMessage()
        {
            return mMessage;
        }
    }
}

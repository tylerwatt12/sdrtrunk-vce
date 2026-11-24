/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.status.APCO25UnitStatus;
import io.github.dsheirer.module.decode.p25.identifier.status.APCO25UserStatus;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.isp.AMBTCStatusQueryResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.isp.AMBTCStatusUpdateRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCStatusUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.StatusQueryResponse;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.isp.StatusUpdateRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.StatusUpdate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Constructed decoded fields, not captured traffic or FEC/CRC vectors. TIA-102.AABC-E (April 2019), section
 * 2.3.29/Figure 2.3.29-1 defines User Status before Unit Status. Figures 6.1.14-1/-2, 6.1.15-1/-2 and
 * 6.2.18-1/-2 locate that field in these six Phase 1 control-message formats.
 */
class P25Phase1StatusMessageTest
{
    private static final int SOURCE = 0x010203;
    private static final int TARGET = 0x040506;
    private static final int[][] STATUS_PAIRS = {
        {0x12, 0x34}, {0x00, 0xFF}, {0xFF, 0x00}, {0xEF, 0xF0}, {0xF0, 0xEF}, {0x00, 0x00}, {0xFF, 0xFF}
    };

    @TestFactory
    Stream<DynamicTest> userOctetPrecedesUnitOctetInEveryControlMessageFormat()
    {
        return Arrays.stream(Format.values()).flatMap(format -> Arrays.stream(STATUS_PAIRS).map(pair ->
            DynamicTest.dynamicTest(format + " user=" + pair[0] + " unit=" + pair[1], () ->
            {
                StatusDecoder decoder = format.decode(pair[0], pair[1]);
                Identifier user = decoder.user().get();
                Identifier unit = decoder.unit().get();
                assertEquals(pair[0], assertInstanceOf(APCO25UserStatus.class, user).getValue());
                assertEquals(pair[1], assertInstanceOf(APCO25UnitStatus.class, unit).getValue());
                assertEquals(Role.STATUS, user.getRole());
                assertEquals(Role.STATUS, unit.getRole());
                assertSame(user, decoder.user().get());
                assertSame(unit, decoder.unit().get());

                String text = decoder.text().get();
                assertTrue(text.contains(" USER STATUS:" + pair[0]));
                assertTrue(text.contains(" UNIT STATUS:" + pair[1]));
                assertEquals(SOURCE, decoder.source().get().getValue());
                assertEquals(TARGET, decoder.target().get().getValue());

                List<Identifier> identifiers = decoder.identifiers().get();
                assertTrue(identifiers.contains(decoder.source().get()));
                assertTrue(identifiers.contains(decoder.target().get()));
                //Extended inbound events intentionally continue exporting only their source/target identities.
                boolean exportsStatus = format != Format.AMBTC_UPDATE_REQUEST && format != Format.AMBTC_QUERY_RESPONSE;
                assertEquals(exportsStatus, identifiers.contains(user));
                assertEquals(exportsStatus, identifiers.contains(unit));
                assertEquals(exportsStatus ? 4 : 2, identifiers.size());
            })));
    }

    private enum Format
    {
        TSBK_UPDATE, TSBK_UPDATE_REQUEST, TSBK_QUERY_RESPONSE,
        AMBTC_UPDATE, AMBTC_UPDATE_REQUEST, AMBTC_QUERY_RESPONSE;

        private StatusDecoder decode(int user, int unit)
        {
            return switch(this)
            {
                case TSBK_UPDATE ->
                {
                    var message = new StatusUpdate(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                        abbreviated(user, unit, false), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetAddress);
                }
                case TSBK_UPDATE_REQUEST ->
                {
                    var message = new StatusUpdateRequest(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                        abbreviated(user, unit, false), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetAddress);
                }
                case TSBK_QUERY_RESPONSE ->
                {
                    var message = new StatusQueryResponse(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                        abbreviated(user, unit, true), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetAddress);
                }
                case AMBTC_UPDATE ->
                {
                    var message = new AMBTCStatusUpdate(extended(user, unit, true, false), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetAddress);
                }
                case AMBTC_UPDATE_REQUEST ->
                {
                    var message = new AMBTCStatusUpdateRequest(extended(user, unit, false, false), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetId);
                }
                case AMBTC_QUERY_RESPONSE ->
                {
                    var message = new AMBTCStatusQueryResponse(extended(user, unit, false, true), 1, 1_000L);
                    yield new StatusDecoder(message::getUserStatus, message::getUnitStatus, message::getIdentifiers,
                        message::toString, message::getSourceAddress, message::getTargetId);
                }
            };
        }
    }

    private static CorrectedBinaryMessage abbreviated(int user, int unit, boolean query)
    {
        //Status occupies octets 2-3; adjacent target/source addresses have independent, unequal values.
        return octets(query ? 0x99 : 0x98, 0, user, unit, 4, 5, 6, 1, 2, 3, 0, 0);
    }

    private static PDUSequence extended(int user, int unit, boolean outbound, boolean query)
    {
        CorrectedBinaryMessage header = outbound ?
            octets(0x37, 0xFD, 0, 4, 5, 6, 0x82, 0x18, 0x12, 0x34, 0, 0) :
            octets(0x17, 0xFD, 0, 1, 2, 3, 0x81, query ? 0x19 : 0x18, user, unit, 0, 0);
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, false), 1_000L, 1);
        if(outbound)
        {
            //The extended OSP places Status in first data-block octets 5-6, not the header.
            sequence.addDataBlock(new TestBlock(octets(0x51, 0x23, 1, 2, 3, user, unit, 0, 0, 0, 1, 2)));
            sequence.addDataBlock(new TestBlock(octets(3, 0x12, 0x34, 0x51, 0x23, 4, 5, 6, 0, 0, 0, 0)));
        }
        else
        {
            //Extended ISPs place Status in header octets 8-9; this block holds the independent target identity.
            sequence.addDataBlock(new TestBlock(octets(0x12, 0x34, 0x51, 0x23, 4, 5, 6, 0, 0, 0, 0, 0)));
        }
        assertFalse(sequence.isValid(), "Constructed decoded fields do not claim packet admission");
        return sequence;
    }

    private static CorrectedBinaryMessage octets(int... values)
    {
        StringBuilder hex = new StringBuilder();
        for(int value : values)
        {
            hex.append(String.format("%02X", value));
        }
        return CorrectedBinaryMessage.loadHex(hex.toString());
    }

    private record StatusDecoder(Supplier<Identifier> user, Supplier<Identifier> unit,
                                 Supplier<List<Identifier>> identifiers, Supplier<String> text,
                                 Supplier<Identifier> source, Supplier<Identifier> target)
    {
    }

    private static class TestBlock extends UnconfirmedDataBlock
    {
        private final CorrectedBinaryMessage mMessage;

        private TestBlock(CorrectedBinaryMessage message)
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

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Full roaming-address layouts from TIA-102.AABC-B Figures 6.1.21-4 and 6.2.26-3.
 */
class AMBTCRoamingAddressBlockLayoutTest
{
    private static final int[] WACNS =
        {0x12345, 0x23456, 0x34567, 0x45678, 0x56789, 0x6789A, 0x789AB, 0x89ABC};
    private static final int[] SYSTEMS =
        {0x101, 0x202, 0x303, 0x404, 0x505, 0x606, 0x707, 0x808};
    private static final int RESPONSE_SOURCE_ID = 0x654321;
    private static final int UPDATE_SOURCE_ID = 0x765432;

    @Test
    void responseDecodesAllEightDistinctRoamingAddressesFromTheirOwningBlocks()
    {
        AMBTCRoamingAddressResponse response = new AMBTCRoamingAddressResponse(responseSequence(3), 0x951, 1_000L);
        List<APCO25FullyQualifiedRadioIdentifier> subscribers = List.of(
            response.getRoamingAddressA(), response.getRoamingAddressB(), response.getRoamingAddressC(),
            response.getRoamingAddressD(), response.getRoamingAddressE(), response.getRoamingAddressF(),
            response.getRoamingAddressG(), response.getRoamingAddressH());

        assertEquals(8, response.getIdentifiers().size());
        for(int x = 0; x < subscribers.size(); x++)
        {
            assertSubscriber(subscribers.get(x), WACNS[x], SYSTEMS[x], RESPONSE_SOURCE_ID);
            assertEquals(subscribers.get(x), response.getIdentifiers().get(x));
        }
    }

    @Test
    void twoBlockResponseDoesNotInterpretPacketCrcBitsAsASixthRoamingAddress()
    {
        AMBTCRoamingAddressResponse response =
            new AMBTCRoamingAddressResponse(responseSequence(2), 0x951, 1_000L);

        assertNull(response.getRoamingAddressF());
        assertNull(response.getRoamingAddressG());
        assertNull(response.getRoamingAddressH());
        assertEquals(5, response.getIdentifiers().size());
    }

    @Test
    void updateDecodesAllSevenDistinctRoamingAddressesFromTheirOwningBlocks()
    {
        AMBTCRoamingAddressUpdate update = new AMBTCRoamingAddressUpdate(updateSequence(3), 0x951, 1_000L);
        List<APCO25FullyQualifiedRadioIdentifier> subscribers = List.of(
            update.getRoamingAddressA(), update.getRoamingAddressB(), update.getRoamingAddressC(),
            update.getRoamingAddressD(), update.getRoamingAddressE(), update.getRoamingAddressF(),
            update.getRoamingAddressG());

        assertEquals(7, update.getIdentifiers().size());
        for(int x = 0; x < subscribers.size(); x++)
        {
            assertSubscriber(subscribers.get(x), WACNS[x], SYSTEMS[x], UPDATE_SOURCE_ID);
            assertEquals(subscribers.get(x), update.getIdentifiers().get(x));
        }
    }

    @Test
    void twoBlockUpdateDoesNotInterpretTheSourceIdAsAFifthRoamingAddress()
    {
        AMBTCRoamingAddressUpdate update = new AMBTCRoamingAddressUpdate(updateSequence(2), 0x951, 1_000L);

        assertNull(update.getRoamingAddressE());
        assertNull(update.getRoamingAddressF());
        assertNull(update.getRoamingAddressG());
        assertEquals(4, update.getIdentifiers().size());
    }

    private static PDUSequence responseSequence(int blockCount)
    {
        CorrectedBinaryMessage header = header(blockCount, RESPONSE_SOURCE_ID);
        CorrectedBinaryMessage block0 = firstBlock();
        CorrectedBinaryMessage block1 = middleBlock();

        if(blockCount == 2)
        {
            return sequence(header, block0, block1);
        }

        CorrectedBinaryMessage block2 = new CorrectedBinaryMessage(96);
        block2.setInt(WACNS[6] & 0xFFF, IntField.length12(0));
        block2.setInt(SYSTEMS[6], IntField.length12(12));
        block2.setInt(WACNS[7], IntField.length20(24));
        block2.setInt(SYSTEMS[7], IntField.length12(44));
        return sequence(header, block0, block1, block2);
    }

    private static PDUSequence updateSequence(int blockCount)
    {
        CorrectedBinaryMessage header = header(blockCount, 0xABCDEF);
        CorrectedBinaryMessage block0 = firstBlock();
        CorrectedBinaryMessage block1 = middleBlock();
        block1.setInt(UPDATE_SOURCE_ID, IntField.length24(24));

        if(blockCount == 2)
        {
            return sequence(header, block0, block1);
        }

        //In the three-block form, E/F occupy block 1 and the source ID moves to the final block.
        block1.setInt(WACNS[4], IntField.length20(24));
        block1.setInt(SYSTEMS[4], IntField.length12(44));
        CorrectedBinaryMessage block2 = new CorrectedBinaryMessage(96);
        block2.setInt(WACNS[6] & 0xFFF, IntField.length12(0));
        block2.setInt(SYSTEMS[6], IntField.length12(12));
        block2.setInt(UPDATE_SOURCE_ID, IntField.length24(24));
        return sequence(header, block0, block1, block2);
    }

    private static CorrectedBinaryMessage header(int blockCount, int address)
    {
        CorrectedBinaryMessage header = new CorrectedBinaryMessage(96);
        header.setInt(address, IntField.length24(24));
        header.setInt(blockCount, IntField.length7(49));
        header.setInt(WACNS[0] >>> 12, IntField.length8(72));
        return header;
    }

    private static CorrectedBinaryMessage firstBlock()
    {
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACNS[0] & 0xFFF, IntField.length12(0));
        block.setInt(SYSTEMS[0], IntField.length12(12));
        block.setInt(WACNS[1], IntField.length20(24));
        block.setInt(SYSTEMS[1], IntField.length12(44));
        block.setInt(WACNS[2], IntField.length20(56));
        block.setInt(SYSTEMS[2], IntField.length12(76));
        block.setInt(WACNS[3] >>> 12, IntField.length8(88));
        return block;
    }

    private static CorrectedBinaryMessage middleBlock()
    {
        CorrectedBinaryMessage block = new CorrectedBinaryMessage(96);
        block.setInt(WACNS[3] & 0xFFF, IntField.length12(0));
        block.setInt(SYSTEMS[3], IntField.length12(12));
        block.setInt(WACNS[4], IntField.length20(24));
        block.setInt(SYSTEMS[4], IntField.length12(44));
        block.setInt(WACNS[5], IntField.length20(56));
        block.setInt(SYSTEMS[5], IntField.length12(76));
        block.setInt(WACNS[6] >>> 12, IntField.length8(88));
        return block;
    }

    private static PDUSequence sequence(CorrectedBinaryMessage header, CorrectedBinaryMessage... blocks)
    {
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, true), 1_000L, 0x951);
        for(CorrectedBinaryMessage block : blocks)
        {
            sequence.addDataBlock(new TestUnconfirmedDataBlock(block));
        }
        return sequence;
    }

    private static void assertSubscriber(APCO25FullyQualifiedRadioIdentifier subscriber, int wacn, int system,
                                         int sourceId)
    {
        assertEquals(Role.FROM, subscriber.getRole());
        assertEquals(wacn, subscriber.getWacn());
        assertEquals(system, subscriber.getSystem());
        assertEquals(sourceId, subscriber.getRadio());
        assertEquals(sourceId, subscriber.getValue());
        assertFalse(subscriber.hasExplicitWorkingAddress());
    }

    private static class TestUnconfirmedDataBlock extends UnconfirmedDataBlock
    {
        private final CorrectedBinaryMessage mMessage;

        private TestUnconfirmedDataBlock(CorrectedBinaryMessage message)
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

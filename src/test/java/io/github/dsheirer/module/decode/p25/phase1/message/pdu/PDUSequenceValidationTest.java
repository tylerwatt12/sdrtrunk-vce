/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCGroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.DataBlock;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet.PacketHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet.PacketMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.response.ResponseHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.response.ResponseMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.umbtc.UMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.umbtc.isp.UMBTCTelephoneInterconnectRequestExplicitDialing;
import org.junit.jupiter.api.Test;

class PDUSequenceValidationTest
{
    private static final int NAC = 0x293;
    private static final long TIMESTAMP = 1_000L;

    @Test
    void validSequencesRemainTypedAfterCRC32Admission()
    {
        assertInstanceOf(PacketMessage.class, create(sequence(22, false, 1, true, false)));
        assertInstanceOf(AMBTCGroupVoiceChannelGrant.class, create(sequence(23, false, 2, true, false)));
        assertInstanceOf(UMBTCTelephoneInterconnectRequestExplicitDialing.class,
            create(sequence(21, false, 1, true, false)));
        assertInstanceOf(ResponseMessage.class, create(sequence(3, false, 1, true, false)));
    }

    @Test
    void corruptedCRC32IsRejectedBeforeSubtypeParsing()
    {
        assertRejectedBeforeSubtype(sequence(22, false, 1, true, true), PacketMessage.class);
        assertRejectedBeforeSubtype(sequence(23, false, 2, true, true), AMBTCGroupVoiceChannelGrant.class);
        assertRejectedBeforeSubtype(sequence(21, false, 1, true, true),
            UMBTCTelephoneInterconnectRequestExplicitDialing.class);
        assertRejectedBeforeSubtype(sequence(3, false, 1, true, true), ResponseMessage.class);
    }

    @Test
    void failedConfirmedBlockCRC9RejectsPacketEvenWhenFinalCRC32Passes()
    {
        PDUSequence sequence = sequence(22, true, 1, false, false);

        assertTrue(sequence.isComplete());
        assertTrue(sequence.passesPacketCRC());
        assertFalse(sequence.hasValidDataBlocks());
        assertFalse(sequence.isValid());
        assertRejectedBeforeSubtype(sequence, PacketMessage.class);
    }

    @Test
    void headerOnlyResponseDoesNotRequirePacketCRC32()
    {
        PDUSequence sequence = sequence(3, false, 0, true, false);

        assertTrue(sequence.isValid());
        assertInstanceOf(ResponseMessage.class, create(sequence));
    }

    private static void assertRejectedBeforeSubtype(PDUSequence sequence, Class<?> rejectedType)
    {
        assertFalse(sequence.isValid());
        P25P1Message message = create(sequence);
        assertInstanceOf(PDUSequenceMessage.class, message);
        assertFalse(rejectedType.isInstance(message));
        assertFalse(message.isValid());
    }

    private static P25P1Message create(PDUSequence sequence)
    {
        return PDUMessageFactory.create(sequence, NAC, TIMESTAMP);
    }

    private static PDUSequence sequence(int format, boolean confirmed, int blockCount, boolean blocksValid,
                                        boolean corruptPayload)
    {
        CorrectedBinaryMessage headerBits = new CorrectedBinaryMessage(96);

        if(confirmed)
        {
            headerBits.set(1);
        }

        if(format != 21)
        {
            headerBits.set(2); //Outbound; UMBTC test opcode is inbound.
        }

        headerBits.setInt(format, IntField.range(3, 7));
        headerBits.setInt(blockCount, IntField.range(49, 55));

        if(format == 23)
        {
            headerBits.setInt(0, IntField.length6(58)); //OSP Group Voice Channel Grant
        }

        PDUHeader header = switch(format)
        {
            case 3 -> new ResponseHeader(headerBits, true);
            case 21 -> new UMBTCHeader(headerBits, true);
            case 22 -> new PacketHeader(headerBits, true);
            case 23 -> new AMBTCHeader(headerBits, true);
            default -> new PDUHeader(headerBits, true);
        };

        PDUSequence sequence = new PDUSequence(header, TIMESTAMP, NAC);

        if(blockCount > 0)
        {
            int blockLength = confirmed ? 128 : 96;
            BinaryMessage payload = payload(blockLength * blockCount, format);

            if(corruptPayload)
            {
                payload.flip(Math.min(31, payload.size() - 33));
            }

            for(int block = 0; block < blockCount; block++)
            {
                sequence.addDataBlock(new TestDataBlock(
                    payload.getSubMessage(block * blockLength, (block + 1) * blockLength), blocksValid));
            }
        }

        return sequence;
    }

    private static BinaryMessage payload(int bitLength, int format)
    {
        int crcStart = bitLength - 32;
        BinaryMessage data = new BinaryMessage(crcStart);

        for(int bit = 0; bit < data.size(); bit += 11)
        {
            data.set(bit);
        }

        if(format == 21)
        {
            data.setInt(8, IntField.length6(2)); //ISP Telephone Interconnect Explicit Dial Request
        }

        long crc = independentCRC(data, 32, 0x104C11DB7L, 0xFFFFFFFFL);
        BinaryMessage payload = new BinaryMessage(bitLength);
        payload.load(0, data);
        payload.load(crcStart, 32, crc);
        return payload;
    }

    /** Independent polynomial long division; production uses a shift-register calculation. */
    private static long independentCRC(BinaryMessage message, int crcLength, long polynomial, long inversion)
    {
        boolean[] dividend = new boolean[message.size() + crcLength];

        for(int bit = 0; bit < message.size(); bit++)
        {
            dividend[bit] = message.get(bit);
        }

        for(int bit = 0; bit < message.size(); bit++)
        {
            if(dividend[bit])
            {
                for(int polynomialBit = 0; polynomialBit <= crcLength; polynomialBit++)
                {
                    int shift = crcLength - polynomialBit;

                    if(((polynomial >> shift) & 1L) == 1L)
                    {
                        dividend[bit + polynomialBit] = !dividend[bit + polynomialBit];
                    }
                }
            }
        }

        long remainder = 0;

        for(int bit = message.size(); bit < dividend.length; bit++)
        {
            remainder = (remainder << 1) | (dividend[bit] ? 1 : 0);
        }

        return remainder ^ inversion;
    }

    private static class TestDataBlock extends DataBlock
    {
        private final BinaryMessage mMessage;
        private final boolean mValid;

        private TestDataBlock(BinaryMessage message, boolean valid)
        {
            mMessage = message;
            mValid = valid;
        }

        @Override
        public BinaryMessage getMessage()
        {
            return mMessage;
        }

        @Override
        public int getBitErrorsCount()
        {
            return 0;
        }

        @Override
        public boolean isValid()
        {
            return mValid;
        }
    }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.dmr.message.data.lc.shorty;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.edac.CRCDMR;
import io.github.dsheirer.edac.Hamming17;
import io.github.dsheirer.module.decode.dmr.message.type.LCSS;
import io.github.dsheirer.module.decode.dmr.message.type.Model;
import org.junit.jupiter.api.Test;

/** Encoded four-fragment CACH inputs exercise FEC and CRC as separate validity requirements. */
class SLCAssemblerTest
{
    @Test void fecValidCodewordWithInvalidCrcCannotSupplyValidServingIdentity()
    {
        var payload = controlParameters();
        payload.flip(28); //Change only CRC, then encode a fully consistent FEC codeword around it.
        assertNotEquals(0, CRCDMR.crc8(payload, 36));
        var message = assemble(encode(payload));
        assertInstanceOf(ControlChannelSystemParameters.class, message);
        assertEquals(0, message.getMessage().getCorrectedBitCount());
        assertFalse(message.isValid(), "Successful FEC must not override a failed CRC8");
    }

    @Test void validCrcAndFecPreserveDecodedOwnSystemAndSite()
    {
        var message = assertInstanceOf(ControlChannelSystemParameters.class, assemble(encode(controlParameters())));
        assertTrue(message.isValid());
        assertEquals(0, CRCDMR.crc8(message.getMessage(), 36));
        assertEquals(Model.HUGE, message.getSystemIdentityCode().getModel());
        assertEquals(0, message.getSystemIdentityCode().getNetwork().getValue());
        assertEquals(784, message.getSystemIdentityCode().getSite().getValue());
        assertEquals(1_090, message.getTimestamp());
    }

    @Test void correctedChannelErrorRemainsValidWhenPayloadCrcPasses()
    {
        var encoded = encode(controlParameters());
        encoded.flip(0);
        var message = assertInstanceOf(ControlChannelSystemParameters.class, assemble(encoded));
        assertTrue(message.isValid());
        assertTrue(message.getMessage().getCorrectedBitCount() > 0);
        assertEquals(0, CRCDMR.crc8(message.getMessage(), 36));
        assertEquals(784, message.getSystemIdentityCode().getSite().getValue());
    }

    private static CorrectedBinaryMessage controlParameters()
    {
        var payload = new CorrectedBinaryMessage(36);
        payload.load(0, 4, 2); //C_SYS_Parms opcode, followed by the 14-bit SIC without PAR.
        payload.load(4, 2, 3); //HUGE model: 2 network bits and 10 site bits.
        payload.load(6, 2, 0);
        payload.load(8, 10, 784);
        payload.load(19, 9, 173);
        payload.load(28, 8, CRCDMR.crc8(payload, 28));
        assertEquals(0, CRCDMR.crc8(payload, 36));
        return payload;
    }

    /** ETSI TS 102 361-1 V2.7.1, B.2.3/Figure B.6: three Hamming rows and one even-parity row. */
    private static BinaryMessage encode(BinaryMessage payload)
    {
        var matrix = new BinaryMessage(68);
        for(int row = 0; row < 3; row++)
        {
            for(int column = 0; column < 12; column++)
                matrix.set(row * 17 + column, payload.get(row * 12 + column));
            boolean found = false;
            for(int parity = 0; parity < 32; parity++)
            {
                matrix.load(row * 17 + 12, 5, parity);
                if(Hamming17.getSyndrome(matrix, row * 17) == 0) { found = true; break; }
            }
            assertTrue(found);
        }
        for(int column = 0; column < 17; column++)
            matrix.set(51 + column, matrix.get(column) ^ matrix.get(17 + column) ^ matrix.get(34 + column));
        var interleaved = new BinaryMessage(68);
        for(int index = 0; index < 67; index++) interleaved.set(index, matrix.get(index * 17 % 67));
        interleaved.set(67, matrix.get(67));
        return interleaved;
    }

    private static ShortLCMessage assemble(BinaryMessage interleaved)
    {
        var assembler = new SLCAssembler();
        LCSS[] sequence = {LCSS.FIRST_FRAGMENT, LCSS.CONTINUATION_FRAGMENT,
            LCSS.CONTINUATION_FRAGMENT, LCSS.LAST_FRAGMENT};
        ShortLCMessage message = null;
        for(int fragment = 0; fragment < 4; fragment++)
        {
            message = assembler.process(sequence[fragment],
                interleaved.getSubMessage(fragment * 17, fragment * 17 + 17), 1_000 + fragment * 30);
            if(fragment < 3) assertNull(message);
        }
        assertNotNull(message);
        return message;
    }
}

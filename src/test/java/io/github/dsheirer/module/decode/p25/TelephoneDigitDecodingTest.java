package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCTelephoneInterconnectAnswerRequest;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.umbtc.UMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.umbtc.isp.UMBTCTelephoneInterconnectRequestExplicitDialing;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.TelephoneInterconnectAnswerRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Constructed digit fields exercise callers; these are not captured or CRC-admitted messages. */
class TelephoneDigitDecodingTest
{
    @Test
    void answerRequestsPublishTheDecodedNumberWithoutChangingItsRole()
    {
        assertAnswerRequests("503", List.of(15, 15, 5, 0, 3));
        assertAnswerRequests("503", List.of(5, 15, 15, 0, 3));
        assertAnswerRequests("503", List.of(5, 0, 3));
        assertAnswerRequests("A503", List.of(15, 15, 15, 0, 5, 0, 3));
        assertAnswerRequests("", List.of());
        assertAnswerRequests("1234567890", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 0));
    }

    @Test
    void explicitDialingPublishesNullsAndEscapedLettersCorrectly()
    {
        assertDialing("503", List.of(15, 15, 5, 0, 3));
        assertDialing("503", List.of(5, 15, 15, 0, 3));
        assertDialing("503", List.of(5, 0, 3, 15, 15));
        assertDialing("A503", List.of(15, 15, 15, 0, 5, 0, 3));
        assertDialing("503", List.of(5, 0, 3, 15));
        assertDialing("", List.of(15, 15));
    }

    @Test
    void explicitDialingPreservesNullAndLetterPairsAcrossTheDataBlockBoundary()
    {
        //The first block carries 18 encoded values. Put the null before, across and after that boundary.
        for(int prefixLength = 16; prefixLength <= 18; prefixLength++)
        {
            List<Integer> values = new ArrayList<>(Collections.nCopies(prefixLength, 1));
            values.addAll(List.of(15, 15, 5, 0, 3));
            assertDialing("1".repeat(prefixLength) + "503", values);
        }
        List<Integer> escaped = new ArrayList<>(Collections.nCopies(17, 1));
        escaped.addAll(List.of(15, 0, 5));
        assertDialing("1".repeat(17) + "A5", escaped);
    }

    @Test
    void explicitDialingHonorsTheCountAndMaximumLength()
    {
        for(int count : new int[]{1, 10, 18, 19, 34})
        {
            assertDialing("1".repeat(count), Collections.nCopies(count, 1));
        }

        List<Integer> values = new ArrayList<>(Collections.nCopies(17, 1));
        values.addAll(List.of(15, 0));
        //The 19th value is outside the declared count and must not complete the trailing escape.
        assertEquals("1".repeat(17), request(values, 18).getTelephoneNumber().getValue());

        values = new ArrayList<>(Collections.nCopies(31, 1));
        values.addAll(List.of(15, 15, 0));
        assertDialing("1".repeat(31) + "0", values);
    }

    private static void assertAnswerRequests(String expected, List<Integer> values)
    {
        List<Integer> padded = new ArrayList<>(values);
        padded.addAll(Collections.nCopies(10 - values.size(), 15));
        TelephoneInterconnectAnswerRequest tsbk = new TelephoneInterconnectAnswerRequest(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, digitMessage(16, padded), 1, 0L);
        assertNumber(expected, Role.FROM, tsbk.getTelephoneNumber(), tsbk.getIdentifiers(), tsbk.toString());
        assertSame(tsbk.getTelephoneNumber(), tsbk.getTelephoneNumber());

        LCTelephoneInterconnectAnswerRequest lc = new LCTelephoneInterconnectAnswerRequest(digitMessage(8, padded));
        assertNumber(expected, Role.FROM, lc.getTelephoneNumber(), lc.getIdentifiers(), lc.toString());

        for(int offset : new int[]{0, 8})
        {
            var mac = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.TelephoneInterconnectAnswerRequest(
                digitMessage(offset + 8, padded), offset);
            assertNumber(expected, Role.ANY, mac.getTelephoneNumber(), mac.getIdentifiers(), mac.toString());
        }
    }

    private static void assertDialing(String expected, List<Integer> values)
    {
        UMBTCTelephoneInterconnectRequestExplicitDialing request = request(values, values.size());
        assertNumber(expected, Role.TO, request.getTelephoneNumber(), request.getIdentifiers(), request.toString());
        assertSame(request.getTelephoneNumber(), request.getTelephoneNumber());
    }

    private static void assertNumber(String expected, Role role, Identifier<?> number, List<Identifier> identifiers,
                                     String messageText)
    {
        assertEquals(expected, number.getValue());
        assertEquals(role, number.getRole());
        assertTrue(identifiers.contains(number));
        assertTrue(messageText.contains(expected));
    }

    private static CorrectedBinaryMessage digitMessage(int start, List<Integer> values)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(112);
        for(int x = 0; x < values.size(); x++)
        {
            message.setInt(values.get(x), IntField.length4(start + x * 4));
        }
        return message;
    }

    private static UMBTCTelephoneInterconnectRequestExplicitDialing request(List<Integer> values, int count)
    {
        CorrectedBinaryMessage header = new CorrectedBinaryMessage(96);
        int blocks = values.size() > 18 ? 2 : 1;
        header.setInt(blocks, IntField.length7(49));
        PDUSequence sequence = new PDUSequence(new UMBTCHeader(header, true), 0L, 1);
        for(int block = 0; block < blocks; block++)
        {
            CorrectedBinaryMessage payload = new CorrectedBinaryMessage(96);
            if(block == 0)
            {
                payload.setInt(count, IntField.length8(8));
            }
            int from = block == 0 ? 0 : 18;
            int to = Math.min(values.size(), block == 0 ? 18 : 34);
            int start = block == 0 ? 24 : 0;
            for(int x = from; x < to; x++)
            {
                payload.setInt(values.get(x), IntField.length4(start + (x - from) * 4));
            }
            sequence.addDataBlock(new TestUnconfirmedDataBlock(payload));
        }
        return new UMBTCTelephoneInterconnectRequestExplicitDialing(sequence, 1, 0L);
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

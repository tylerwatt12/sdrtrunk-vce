package io.github.dsheirer.module.decode.dmr.message.data.lc.full;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

class TalkerAliasAssemblerTest
{
    @Test
    void blocksBeforeANewHeaderCannotCompleteItsAlias()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler();
        assertNull(assembler.process(block("OLD-ALIAS", 1, 100, 1)));
        assertNull(assembler.process(header("NEW-ALIAS", 200, 1)));
        assertEquals("NEW-ALIAS", assembler.process(block("NEW-ALIAS", 1, 300, 1)).getAliasValue());
        assertNull(assembler.process(block("OLD-ALIAS", 1, 400, 1)));
    }

    @Test
    void eachHeaderStartsFreshButItsContinuationBlocksMayArriveOutOfOrder()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler();
        assembler.process(header("OLD-ALIAS-ONE-TWO", 100, 1));
        assembler.process(block("OLD-ALIAS-ONE-TWO", 1, 200, 1));
        assertNull(assembler.process(header("NEW-ALIAS-ONE-TWO", 300, 1)));
        assertNull(assembler.process(block("NEW-ALIAS-ONE-TWO", 2, 400, 1)));
        assertEquals("NEW-ALIAS-ONE-TWO",
            assembler.process(block("NEW-ALIAS-ONE-TWO", 1, 500, 1)).getAliasValue());
    }

    @Test
    void repeatedCallContextRetainsFragmentsButNewSourceOrTargetDiscardsThem()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler();
        assembler.observeCall(voice(101, 91, 100, 1));
        assembler.process(header("FIRST-ALIAS", 200, 1));
        assembler.observeCall(voice(101, 91, 300, 1));
        assertEquals("FIRST-ALIAS", assembler.process(block("FIRST-ALIAS", 1, 400, 1)).getAliasValue());
        assembler.process(header("STALE-ALIAS", 500, 1));
        assembler.observeCall(voice(202, 91, 600, 1));
        assertNull(assembler.process(block("STALE-ALIAS", 1, 700, 1)));
        assembler.process(header("OTHER-ALIAS", 800, 1));
        assembler.observeCall(voice(202, 92, 900, 1));
        assertNull(assembler.process(block("OTHER-ALIAS", 1, 1_000, 1)));
    }

    @Test
    void resetAndTimeslotsKeepIndependentLifetimesAndValidRepeatsStillComplete()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler();
        assembler.process(header("FIRST-ALIAS", 100, 1));
        assembler.process(header("SECOND-ALIAS", 100, 2));
        assembler.reset(1);
        assertNull(assembler.process(block("FIRST-ALIAS", 1, 200, 1)));
        assertEquals("SECOND-ALIAS", assembler.process(block("SECOND-ALIAS", 1, 200, 2)).getAliasValue());
        assembler.process(header("SECOND-ALIAS", 300, 2));
        assertEquals("SECOND-ALIAS", assembler.process(block("SECOND-ALIAS", 1, 400, 2)).getAliasValue());
    }

    public static TalkerAliasHeader header(String text, long timestamp, int timeslot)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(2, 6, 4);
        message.load(16, 2, 1);
        message.load(18, 5, text.length());
        message.load(23, payload(text).getSubMessage(0, 49));
        return new TalkerAliasHeader(message, timestamp, timeslot);
    }

    public static FullLCMessage block(String text, int number, long timestamp, int timeslot)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(2, 6, 4 + number);
        int start = 49 + (number - 1) * 56;
        message.load(16, payload(text).getSubMessage(start, start + 56));
        return switch(number)
        {
            case 1 -> new TalkerAliasBlock1(message, timestamp, timeslot);
            case 2 -> new TalkerAliasBlock2(message, timestamp, timeslot);
            default -> new TalkerAliasBlock3(message, timestamp, timeslot);
        };
    }

    public static GroupVoiceChannelUser voice(int source, int target, long timestamp, int timeslot)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(24, 24, target);
        message.load(48, 24, source);
        return new GroupVoiceChannelUser(message, timestamp, timeslot);
    }

    private static CorrectedBinaryMessage payload(String text)
    {
        CorrectedBinaryMessage payload = new CorrectedBinaryMessage(217);
        for(int x = 0; x < text.length(); x++) payload.load(1 + x * 8, 8, text.charAt(x));
        return payload;
    }
}

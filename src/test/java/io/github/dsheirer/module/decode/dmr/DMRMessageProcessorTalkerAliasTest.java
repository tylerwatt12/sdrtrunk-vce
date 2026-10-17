package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.*;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRMessageProcessorTalkerAliasTest
{
    @Test
    void processorResetsAliasOnSourceChangeSyncLossAndTerminator()
    {
        DMRMessageProcessor processor = new DMRMessageProcessor(new DecodeConfigDMR(), new DMRCrcMaskManager(true));
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);
        processor.receive(voice(101, 100));
        processor.receive(header(200));
        processor.receive(voice(202, 300));
        processor.receive(block(400));
        assertEquals(0, completeCount(messages));
        processor.receive(header(500));
        processor.receive(new SyncLossMessage(600, 1_000, Protocol.DMR));
        processor.receive(header(650));
        processor.receive(block(700));
        assertEquals(0, completeCount(messages));
        processor.receive(voice(202, 750));
        processor.receive(header(800));
        processor.receive(new Terminator(DMRSyncPattern.MOBILE_STATION_DATA, new CorrectedBinaryMessage(196), null,
            new SlotType(new CorrectedBinaryMessage(20)), 900, 1, voice(202, 900)));
        processor.receive(block(1_000));
        assertEquals(0, completeCount(messages));
        processor.receive(voice(202, 1_100));
        processor.receive(header(1_200));
        processor.receive(block(1_300));
        assertEquals(1, completeCount(messages));
        processor.receive(header(1_400));
        processor.reset();
        processor.receive(block(1_500));
        assertEquals(1, completeCount(messages));
        processor.receive(voice(202, 1_550));
        processor.receive(header(1_600));
        processor.receive(block(1_700));
        assertEquals(2, completeCount(messages));
    }

    @Test
    void observerResetCannotResumeOldHeaderAssemblyInTheNewSameRadioReception()
    {
        DMRMessageProcessor processor = new DMRMessageProcessor(new DecodeConfigDMR(), new DMRCrcMaskManager(true));
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(message ->
        {
            messages.add(message);
            if(message instanceof TalkerAliasHeader && message.getTimestamp() == 200)
            {
                processor.reset();
                processor.receive(voice(101, 300));
            }
        });
        processor.receive(voice(101, 100));
        processor.receive(header(200));
        processor.receive(block(400));
        assertEquals(0, completeCount(messages));
        processor.receive(header(500));
        processor.receive(block(600));
        assertEquals(1, completeCount(messages));
    }

    private static GroupVoiceChannelUser voice(int source, long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(24, 24, 91);
        message.load(48, 24, source);
        return new GroupVoiceChannelUser(message, timestamp, 1);
    }

    private static TalkerAliasHeader header(long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(2, 6, 4);
        message.load(16, 2, 1);
        message.load(18, 5, 9);
        return new TalkerAliasHeader(message, timestamp, 1);
    }

    private static TalkerAliasBlock1 block(long timestamp)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(2, 6, 5);
        return new TalkerAliasBlock1(message, timestamp, 1);
    }

    private static long completeCount(List<IMessage> messages)
    {
        return messages.stream().filter(TalkerAliasComplete.class::isInstance).count();
    }
}

package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.broadcast.Idle;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Disconnect;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.Encoding;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.TalkerAlias;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.TalkerAliasAssembler;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.TalkerAliasComplete;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NXDNMessageProcessorTalkerAliasTest
{
    private static final LICH CHANNEL = LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1;

    @Test
    void observerResetCannotResumeOldFirstFragmentInTheNewSameRadioReception()
    {
        NXDNMessageProcessor processor = new NXDNMessageProcessor(new DecodeConfigNXDN());
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(message ->
        {
            messages.add(message);
            if(message instanceof TalkerAlias && message.getTimestamp() == 200)
            {
                processor.reset();
                processor.receive(voice(101, 91, 300));
            }
        });
        processor.receive(voice(101, 91, 100));
        processor.receive(fragment(1, "OLD-ALIAS", 200, 3));
        processor.receive(fragment(2, "OLD-ALIAS", 400, 3));
        processor.receive(fragment(3, "OLD-ALIAS", 500, 3));
        processor.receive(fragment(4, "OLD-ALIAS", 600, 3));
        assertEquals(0, messages.stream().filter(TalkerAliasComplete.class::isInstance).count());
        processor.receive(fragment(1, "NEW-ALIAS", 700, 3));
        processor.receive(fragment(2, "NEW-ALIAS", 800, 3));
        processor.receive(fragment(3, "NEW-ALIAS", 900, 3));
        processor.receive(fragment(4, "NEW-ALIAS", 1_000, 3));
        assertEquals(1, messages.stream().filter(TalkerAliasComplete.class::isInstance).count());
    }

    @Test
    void assemblerConsumesASequenceAndAcceptsReorderedMiddleFragmentsAndRepeats()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler(Encoding.UTF8);
        assertNull(assembler.process(fragment(2, "FIRST-ALIAS", 100, 3)));
        assembler.process(fragment(1, "FIRST-ALIAS", 200, 3));
        assembler.process(fragment(3, "FIRST-ALIAS", 300, 3));
        assembler.process(fragment(2, "FIRST-ALIAS", 400, 3));
        assertEquals("FIRST-ALIAS", assembler.process(fragment(4, "FIRST-ALIAS", 500, 3))
            .getTalkerAlias().getValue());
        assertNull(assembler.process(fragment(4, "FIRST-ALIAS", 600, 3)));
        assembler.process(fragment(1, "FIRST-ALIAS", 700, 3));
        assembler.process(fragment(2, "FIRST-ALIAS", 800, 3));
        assembler.process(fragment(3, "FIRST-ALIAS", 900, 3));
        assertNotNull(assembler.process(fragment(4, "FIRST-ALIAS", 1_000, 3)));
    }

    @Test
    void newFirstFragmentOrDifferentRanCannotReuseOldFragments()
    {
        TalkerAliasAssembler assembler = new TalkerAliasAssembler(Encoding.UTF8);
        assembler.process(fragment(1, "OLD-ALIAS", 100, 3));
        assembler.process(fragment(2, "OLD-ALIAS", 200, 3));
        assembler.process(fragment(1, "NEW-ALIAS", 300, 3));
        assembler.process(fragment(3, "NEW-ALIAS", 400, 3));
        assertNull(assembler.process(fragment(4, "NEW-ALIAS", 500, 3)));
        assertEquals("NEW-ALIAS", assembler.process(fragment(2, "NEW-ALIAS", 600, 3))
            .getTalkerAlias().getValue());
        assembler.process(fragment(1, "OLD-ALIAS", 700, 3));
        assertNull(assembler.process(fragment(2, "NEW-ALIAS", 800, 4)));
        assembler.process(fragment(3, "OLD-ALIAS", 900, 3));
        assertNull(assembler.process(fragment(4, "OLD-ALIAS", 1_000, 3)));
    }

    @Test
    void processorPreservesRepeatedOrIncompleteHeadersButClearsOnRealSourceOrTargetChanges()
    {
        for(boolean sourceChange: List.of(true, false))
        {
            NXDNMessageProcessor processor = new NXDNMessageProcessor(new DecodeConfigNXDN());
            List<IMessage> messages = new ArrayList<>();
            processor.setMessageListener(messages::add);
            processor.receive(voice(101, 91, 100));
            processor.receive(fragment(1, "FIRST-ALIAS", 200, 3));
            processor.receive(voice(0, 91, 250));
            processor.receive(fragment(2, "FIRST-ALIAS", 300, 3));
            processor.receive(voice(101, 91, 350));
            processor.receive(fragment(3, "FIRST-ALIAS", 400, 3));
            processor.receive(fragment(4, "FIRST-ALIAS", 500, 3));
            assertEquals(1, completeCount(messages));
            processor.receive(fragment(1, "STALE-ALIAS", 600, 3));
            processor.receive(fragment(2, "STALE-ALIAS", 700, 3));
            processor.receive(voice(sourceChange ? 202 : 101, sourceChange ? 91 : 92, 800));
            processor.receive(fragment(3, "STALE-ALIAS", 900, 3));
            processor.receive(fragment(4, "STALE-ALIAS", 1_000, 3));
            assertEquals(1, completeCount(messages));
        }
    }

    @Test
    void processorReuseCannotCarryFragmentsIntoTheSameParticipantsNextCall()
    {
        NXDNMessageProcessor processor = new NXDNMessageProcessor(new DecodeConfigNXDN());
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);
        processor.receive(voice(101, 91, 100));
        processor.receive(fragment(1, "STALE-ALIAS", 200, 3));
        processor.receive(fragment(2, "STALE-ALIAS", 300, 3));
        processor.reset();
        processor.receive(voice(101, 91, 400));
        processor.receive(fragment(3, "STALE-ALIAS", 500, 3));
        processor.receive(fragment(4, "STALE-ALIAS", 600, 3));
        assertEquals(0, completeCount(messages));
        for(int number = 1; number <= 4; number++)
            processor.receive(fragment(number, "NEW-ALIAS", 700 + number * 100, 3));
        assertEquals(1, completeCount(messages));
    }

    @Test
    void fragmentsOnAnotherRanCannotAttachToTheKnownVoiceSource()
    {
        NXDNMessageProcessor processor = new NXDNMessageProcessor(new DecodeConfigNXDN());
        List<IMessage> messages = new ArrayList<>();
        processor.setMessageListener(messages::add);
        processor.receive(voice(101, 91, 100));
        for(int number = 1; number <= 4; number++)
            processor.receive(fragment(number, "OTHER-SYSTEM", 200 + number * 100, 4));
        assertEquals(0, completeCount(messages));
        for(int number = 1; number <= 4; number++)
            processor.receive(fragment(number, "RIGHT-SYSTEM", 700 + number * 100, 3));
        assertEquals(1, completeCount(messages));
    }

    @Test
    void releaseDisconnectIdleAndSyncLossEndTheAliasAssemblyLifetime()
    {
        List<IMessage> boundaries = List.of(
            new TransmissionRelease(callBits(101, 91), 400,
                NXDNMessageType.TRAFFIC_OUT_08_CC_TRANSMISSION_RELEASE, 3, CHANNEL),
            new Disconnect(callBits(101, 91), 400, NXDNMessageType.TRAFFIC_OUT_17_CC_DISCONNECT, 3, CHANNEL),
            new Idle(new CorrectedBinaryMessage(72), 400, NXDNMessageType.TRAFFIC_OUT_16_CC_IDLE, 3, CHANNEL),
            new SyncLossMessage(400, 1_000, Protocol.NXDN));
        for(IMessage boundary: boundaries)
        {
            NXDNMessageProcessor processor = new NXDNMessageProcessor(new DecodeConfigNXDN());
            List<IMessage> messages = new ArrayList<>();
            processor.setMessageListener(messages::add);
            processor.receive(voice(101, 91, 100));
            processor.receive(fragment(1, "STALE-ALIAS", 200, 3));
            processor.receive(fragment(2, "STALE-ALIAS", 300, 3));
            processor.receive(boundary);
            for(int number = 1; number <= 4; number++)
                processor.receive(fragment(number, "ORPHAN-ALIAS", 410 + number, 3));
            assertEquals(0, completeCount(messages));
            processor.receive(voice(202, 91, 450));
            processor.receive(fragment(3, "STALE-ALIAS", 500, 3));
            processor.receive(fragment(4, "STALE-ALIAS", 600, 3));
            assertEquals(0, completeCount(messages), boundary.getClass().getSimpleName());
            for(int number = 1; number <= 4; number++)
                processor.receive(fragment(number, "NEW-ALIAS", 700 + number * 100, 3));
            assertEquals(1, completeCount(messages));
        }
    }

    private static TalkerAlias fragment(int number, String text, long timestamp, int ran)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(88);
        message.load(32, 4, number);
        message.load(36, 4, 4);
        for(int x = 0; x < 4; x++)
        {
            int index = (number - 1) * 4 + x;
            message.load(40 + x * 8, 8, index < text.length() ? text.charAt(index) : 0);
        }
        return new TalkerAlias(message, timestamp, NXDNMessageType.TALKER_ALIAS, ran, CHANNEL);
    }

    private static VoiceCall voice(int source, int target, long timestamp)
    {
        return new VoiceCall(callBits(source, target), timestamp,
            NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 3, CHANNEL);
    }

    private static CorrectedBinaryMessage callBits(int source, int target)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(16, 3, CallType.GROUP_BROADCAST.getValue());
        message.load(24, 16, source);
        message.load(40, 16, target);
        return message;
    }

    private static long completeCount(List<IMessage> messages)
    {
        return messages.stream().filter(TalkerAliasComplete.class::isInstance).count();
    }
}

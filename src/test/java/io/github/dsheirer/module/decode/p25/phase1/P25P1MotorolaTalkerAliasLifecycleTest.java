/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaTalkerAliasDataBlock;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaTalkerAliasHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.MotorolaTalkerAliasComplete;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1MotorolaTalkerAliasLifecycleTest
{
    private static final String HEADER = "15901BBD0701004955";
    private static final String[] BLOCKS = {
        "1790014BEE002AEE67", "179002485283ED1081", "1790034E33C03E9B3E",
        "179004435647DE0C00", "1790054C8A83E351E4", "1790064079F592CF37",
        "179007494B30000000"
    };

    @Test
    void listenerResetDiscardsTheOldCompletionAndItsRecursiveReceive()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = new ArrayList<>();
        LinkControlWord last = block(BLOCKS.length - 1, true);
        processor.setMessageListener(message -> {
            if(message instanceof TDULCMessage tdulc && tdulc.getLinkControlWord() == last)
            {
                processor.resetForSourceFrequencyChange();
                complete(processor, 2_000);
            }
            if(message instanceof MotorolaTalkerAliasComplete alias && alias.isValid()) aliases.add(alias);
        });
        receive(processor, header(true), 1_000);
        for(int number = 0; number < BLOCKS.length - 1; number++)
            receive(processor, block(number, true), 1_100 + number);
        receive(processor, last, 1_200);
        assertTrue(aliases.isEmpty());
        complete(processor, 3_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void freshHeaderAndSyncLossDiscardAPartialBodyButKeepFreshTerminatorAliases()
    {
        for(IMessage boundary: List.of(new HDUMessage(new CorrectedBinaryMessage(648), 1, 1_500),
            new SyncLossMessage(1_500, 1_000, Protocol.APCO25)))
        {
            P25P1MessageProcessor processor = new P25P1MessageProcessor();
            List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
            receive(processor, header(true), 1_000);
            receive(processor, block(0, true), 1_100);
            processor.receive(boundary);
            body(processor, 1, 2_000);
            assertTrue(aliases.isEmpty(), boundary.getClass().getSimpleName());
            complete(processor, 3_000);
            assertEquals(1, aliases.size());
            assertEquals(0xE67852, aliases.getFirst().getRadio().getRadio());
        }
    }

    @Test
    void retuneDiscardsThePreviousPartialBody()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(true), 1_000);
        receive(processor, block(0, true), 1_100);
        processor.resetForSourceFrequencyChange();
        body(processor, 1, 2_000);
        assertTrue(aliases.isEmpty());
        complete(processor, 3_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void invalidProtectedHeaderCannotBorrowACrcValidBody()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(false), 1_000);
        body(processor, 0, 1_100);
        assertTrue(aliases.isEmpty());
        complete(processor, 2_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void invalidProtectedBodyRequiresAFreshHeader()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receive(processor, header(true), 1_000);
        receive(processor, block(0, true), 1_100);
        receive(processor, block(1, false), 1_200);
        body(processor, 1, 1_300);
        assertTrue(aliases.isEmpty());
        complete(processor, 2_000);
        assertEquals(1, aliases.size());
    }

    @Test
    void protectedButFecValidTerminatorHeaderOrBodyCannotSupplyAliasFragments()
    {
        for(boolean protectedHeader: new boolean[]{true, false})
        {
            var processor = new P25P1MessageProcessor();
            var aliases = aliases(processor);
            LinkControlWord first = header(true);
            if(protectedHeader) first.getMessage().set(0);
            receive(processor, first, 1000);
            receive(processor, block(0, true), 1100);
            LinkControlWord second = block(1, true);
            if(!protectedHeader) second.getMessage().set(0);
            receive(processor, second, 1200);
            body(processor, 1, 1300);
            assertTrue(aliases.isEmpty(), "FEC validity does not authorize reading protected LC fields");
            complete(processor, 2000);
            assertEquals(1, aliases.size(), "a fresh unprotected header recovers normally");
        }
    }

    @Test
    void ldu1AliasesCompleteAfterTheirFinalParentMessageAndCanShareAValidTerminatorHeader()
    {
        for(boolean terminatorHeader: new boolean[]{false, true})
        {
            P25P1MessageProcessor processor = new P25P1MessageProcessor();
            List<IMessage> emitted = new ArrayList<>();
            processor.setMessageListener(emitted::add);
            if(terminatorHeader) receive(processor, header(true), 1_000);
            else receiveLdu(processor, header(true), 1_000);
            for(int number = 0; number < BLOCKS.length; number++)
                receiveLdu(processor, block(number, true), 1_100 + number);
            var aliases = emitted.stream().filter(MotorolaTalkerAliasComplete.class::isInstance)
                .map(MotorolaTalkerAliasComplete.class::cast).toList();
            assertEquals(1, aliases.size());
            assertTrue(aliases.getFirst().isValid());
            assertEquals(0xE67852, aliases.getFirst().getRadio().getRadio());
            assertTrue(emitted.indexOf(aliases.getFirst()) > emitted.lastIndexOf(emitted.stream()
                .filter(LDU1Message.class::isInstance).reduce((first, second) -> second).orElseThrow()));
        }
    }

    @Test
    void invalidLduLinkControlCannotContinueAnOldMotorolaAssembly()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = aliases(processor);
        receiveLdu(processor, header(true), 1_000);
        receiveLdu(processor, block(0, true), 1_100);
        receiveLdu(processor, block(1, false), 1_200);
        for(int number = 1; number < BLOCKS.length; number++)
            receiveLdu(processor, block(number, true), 1_300 + number);
        assertTrue(aliases.isEmpty());
        receiveLdu(processor, header(true), 2_000);
        for(int number = 0; number < BLOCKS.length; number++)
            receiveLdu(processor, block(number, true), 2_100 + number);
        assertEquals(1, aliases.size());
    }

    @Test
    void listenerRetuneDuringTheLastLduCannotPublishTheOldCompletion()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<MotorolaTalkerAliasComplete> aliases = new ArrayList<>();
        LinkControlWord last = block(BLOCKS.length - 1, true);
        processor.setMessageListener(message -> {
            if(message instanceof LDU1Message ldu && ldu.getLinkControlWord() == last)
                processor.resetForSourceFrequencyChange();
            if(message instanceof MotorolaTalkerAliasComplete alias && alias.isValid()) aliases.add(alias);
        });
        receiveLdu(processor, header(true), 1_000);
        for(int number = 0; number < BLOCKS.length - 1; number++)
            receiveLdu(processor, block(number, true), 1_100 + number);
        receiveLdu(processor, last, 1_200);
        assertTrue(aliases.isEmpty());
    }

    private static List<MotorolaTalkerAliasComplete> aliases(P25P1MessageProcessor processor)
    {
        List<MotorolaTalkerAliasComplete> aliases = new ArrayList<>();
        processor.setMessageListener(message -> {
            if(message instanceof MotorolaTalkerAliasComplete alias && alias.isValid()) aliases.add(alias);
        });
        return aliases;
    }

    private static void complete(P25P1MessageProcessor processor, long timestamp)
    {
        receive(processor, header(true), timestamp);
        body(processor, 0, timestamp + 1);
    }

    private static void body(P25P1MessageProcessor processor, int start, long timestamp)
    {
        for(int number = start; number < BLOCKS.length; number++)
            receive(processor, block(number, true), timestamp + number);
    }

    private static void receive(P25P1MessageProcessor processor, LinkControlWord lcw, long timestamp)
    {
        processor.receive(new TDULCMessage(new CorrectedBinaryMessage(288), 1, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lcw; }
            @Override public boolean isValid() { return true; }
        });
    }

    private static void receiveLdu(P25P1MessageProcessor processor, LinkControlWord lcw, long timestamp)
    {
        processor.receive(new LDU1Message(new CorrectedBinaryMessage(1_568), 1, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lcw; }
            @Override public boolean isValid() { return true; }
        });
    }

    private static LinkControlWord header(boolean valid)
    {
        var header = new LCMotorolaTalkerAliasHeader(CorrectedBinaryMessage.loadHex(HEADER));
        header.setValid(valid);
        return header;
    }

    private static LinkControlWord block(int number, boolean valid)
    {
        var block = new LCMotorolaTalkerAliasDataBlock(CorrectedBinaryMessage.loadHex(BLOCKS[number]));
        block.setValid(valid);
        return block;
    }
}

/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasBlock1;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasBlock2;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasComplete;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCGroupVoiceChannelUser;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1HarrisTalkerAliasLifecycleTest
{
    @Test
    void listenerResetCannotPublishTheOldCompletionOrRestoreItsSourceRecursively()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = new ArrayList<>();
        LinkControlWord last = block2();
        processor.setMessageListener(message -> {
            if(message instanceof LDU1Message ldu1 && ldu1.getLinkControlWord() == last)
            {
                processor.resetForSourceFrequencyChange();
                processor.receive(ldu(source(101), 2_000));
                processor.receive(ldu(block1(), 2_100));
                processor.receive(ldu(block2(), 2_200));
            }
            if(message instanceof LCHarrisTalkerAliasComplete alias) aliases.add(alias.getTalkerAlias().getValue());
        });
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(ldu(last, 1_100));
        assertTrue(aliases.isEmpty());
        processor.receive(ldu(block1(), 3_000));
        processor.receive(ldu(block2(), 3_100));
        assertTrue(aliases.isEmpty());
        processor.receive(ldu(source(202), 3_200));
        processor.receive(ldu(block1(), 3_300));
        processor.receive(ldu(block2(), 3_400));
        assertEquals(List.of("CDP #0231"), aliases);
    }

    @Test
    void syncLossRequiresAFreshSourceBeforeAnotherAliasSequence()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(new SyncLossMessage(1_100, 1_000, Protocol.APCO25));
        processor.receive(ldu(block1(), 1_200));
        processor.receive(ldu(block2(), 1_300));
        assertTrue(aliases.isEmpty());
        processor.receive(ldu(source(202), 1_400));
        processor.receive(ldu(block1(), 1_500));
        processor.receive(ldu(block2(), 1_600));
        assertEquals(List.of("CDP #0231"), aliases);
    }

    @Test
    void callEndingAndFreshHeaderDiscardIncompleteAlias()
    {
        for(IMessage boundary: List.of(new TDUMessage(new CorrectedBinaryMessage(144), 1, 1_500),
            terminator(1_500), new HDUMessage(new CorrectedBinaryMessage(648), 1, 1_500)))
        {
            P25P1MessageProcessor processor = new P25P1MessageProcessor();
            List<String> aliases = aliases(processor);
            processor.receive(ldu(source(101), 900));
            processor.receive(ldu(block1(), 1_000));
            processor.receive(boundary);
            processor.receive(ldu(block2(), 2_000));
            assertTrue(aliases.isEmpty(), boundary.getClass().getSimpleName());
            processor.receive(ldu(source(202), 2_050));
            processor.receive(ldu(block1(), 2_100));
            processor.receive(ldu(block2(), 2_200));
            assertEquals(List.of("CDP #0231"), aliases);
        }
    }

    @Test
    void changedRadioDiscardsPartialAliasWithoutATerminator()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(ldu(source(202), 1_100));
        processor.receive(ldu(block2(), 1_200));
        assertTrue(aliases.isEmpty());
        processor.receive(ldu(block1(), 1_300));
        processor.receive(ldu(block2(), 1_400));
        assertEquals(List.of("CDP #0231"), aliases);
    }

    @Test
    void repeatedRadioDoesNotDiscardAnActiveAssembly()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(ldu(source(101), 1_100));
        processor.receive(ldu(block2(), 1_200));
        processor.receive(ldu(block2(), 1_300));
        assertEquals(List.of("CDP #0231"), aliases);
    }

    @Test
    void frequencyResetDiscardsPartialAlias()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.resetForSourceFrequencyChange();
        processor.receive(ldu(block2(), 2_000));
        assertTrue(aliases.isEmpty());
    }

    @Test
    void differentNacCannotFinishThePriorRfSourcesAlias()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(new LDU1Message(new CorrectedBinaryMessage(1_568), 2, 1_100)
        {
            @Override public LinkControlWord getLinkControlWord() { return block2(); }
        });
        assertTrue(aliases.isEmpty());
    }

    @Test
    void aliasBeforeFreshSourceCannotReuseThePriorCallsRadio()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(ldu(block2(), 1_100));
        processor.receive(new TDUMessage(new CorrectedBinaryMessage(144), 1, 1_200));
        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), 1, 2_000));

        processor.receive(ldu(block1(), 2_100));
        processor.receive(ldu(block2(), 2_200));
        assertEquals(List.of("CDP #0231"), aliases, "no completed text is emitted under the prior radio anchor");

        processor.receive(ldu(source(202), 2_300));
        processor.receive(ldu(block2(), 2_400));
        assertEquals(List.of("CDP #0231"), aliases, "old orphan text cannot wait for the next radio");
        processor.receive(ldu(block1(), 2_500));
        processor.receive(ldu(block2(), 2_600));
        assertEquals(List.of("CDP #0231", "CDP #0231"), aliases);
    }

    @Test
    void heldSourceIsDispatchedBeforeTheFirstAliasFragment()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor();
        List<String> aliases = aliases(processor);
        var heldSource = new LCGroupVoiceChannelUser(new CorrectedBinaryMessage(72), 900, false)
        {
            @Override public boolean isExtensionRequired() { return true; }
            @Override public LinkControlOpcode getOpcode() { return LinkControlOpcode.GROUP_VOICE_CHANNEL_USER; }
            @Override public List<Identifier> getIdentifiers() { return List.of(APCO25RadioIdentifier.createFrom(101)); }
        };
        heldSource.setValid(true);
        processor.receive(ldu(heldSource, 900));
        processor.receive(ldu(block1(), 1_000));
        processor.receive(ldu(block2(), 1_100));

        assertEquals(List.of("CDP #0231"), aliases);
    }

    private static List<String> aliases(P25P1MessageProcessor processor)
    {
        List<String> aliases = new ArrayList<>();
        processor.setMessageListener(message -> {
            if(message instanceof LCHarrisTalkerAliasComplete complete)
            {
                aliases.add(complete.getTalkerAlias().getValue());
            }
        });
        return aliases;
    }

    private static LinkControlWord source(int radio)
    {
        LinkControlWord source = new LinkControlWord(new CorrectedBinaryMessage(72))
        {
            @Override public LinkControlOpcode getOpcode() { return LinkControlOpcode.GROUP_VOICE_CHANNEL_USER; }
            @Override public List<Identifier> getIdentifiers() { return List.of(APCO25RadioIdentifier.createFrom(radio)); }
        };
        source.setValid(true);
        return source;
    }

    private static LDU1Message ldu(LinkControlWord lcw, long timestamp)
    {
        return new LDU1Message(new CorrectedBinaryMessage(1_568), 1, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lcw; }
        };
    }

    private static TDULCMessage terminator(long timestamp)
    {
        return new TDULCMessage(new CorrectedBinaryMessage(288), 1, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return source(101); }
        };
    }

    private static LCHarrisTalkerAliasBlock1 block1()
    {
        var block = new LCHarrisTalkerAliasBlock1(CorrectedBinaryMessage.loadHex("32A443445020233032"));
        block.setValid(true);
        return block;
    }

    private static LCHarrisTalkerAliasBlock2 block2()
    {
        var block = new LCHarrisTalkerAliasBlock2(CorrectedBinaryMessage.loadHex("33A433312020202020"));
        block.setValid(true);
        return block;
    }
}

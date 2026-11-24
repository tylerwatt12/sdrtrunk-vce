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
    void explicitUnknownSourceCannotBorrowThePriorAnchorOrItsPendingHalf()
    {
        for(boolean even: new boolean[]{false, true})
        {
            var processor = new P25P1MessageProcessor();
            var aliases = aliases(processor);
            processor.receive(ldu(realGroupSource(101), 900));
            processor.receive(ldu(firstPart(even), 1000));
            processor.receive(ldu(secondPart(even), 1100));
            assertEquals(List.of("CDP #0231"), aliases);

            //Same TG and NAC, with no terminator: source zero is omitted entirely from this real LC's identifiers.
            processor.receive(ldu(realGroupSource(0), 1200));
            processor.receive(ldu(firstPart(even), 1300));
            processor.receive(ldu(secondPart(even), 1400));
            assertEquals(List.of("CDP #0231"), aliases);

            processor.receive(ldu(realGroupSource(101), 1500));
            processor.receive(ldu(firstPart(even), 1600));
            processor.receive(ldu(realGroupSource(0), 1700));
            processor.receive(ldu(secondPart(even), 1800));
            assertEquals(List.of("CDP #0231"), aliases, "a prior half cannot bridge an explicit unknown source");

            processor.receive(ldu(realGroupSource(202), 1900));
            processor.receive(ldu(firstPart(even), 2000));
            processor.receive(ldu(secondPart(even), 2100));
            assertEquals(List.of("CDP #0231", "CDP #0231"), aliases);
        }
    }

    @Test
    void resolvedHomeIdentityWithLocalZeroStillAnchorsEitherConveyance()
    {
        for(boolean even: new boolean[]{false, true})
        {
            var processor = new P25P1MessageProcessor();
            var aliases = aliases(processor);
            var source = realGroupSource(0);
            source.getMessage().set(31);
            processor.receive(ldu(source, 900));
            var bits = new CorrectedBinaryMessage(72);
            bits.load(0, 8, 9);
            bits.load(16, 20, 0xABCDE);
            bits.load(36, 12, 0x123);
            bits.load(48, 24, 101);
            var extension = new io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCSourceIDExtension(
                bits, 950, false);
            extension.setValid(true);
            processor.receive(ldu(extension, 950));
            processor.receive(ldu(firstPart(even), 1000));
            processor.receive(ldu(secondPart(even), 1100));
            assertEquals(List.of("CDP #0231"), aliases);
        }
    }

    private static LCGroupVoiceChannelUser realGroupSource(int radio)
    {
        var bits = new CorrectedBinaryMessage(72);
        bits.load(32, 16, 100);
        bits.load(48, 24, radio);
        var source = new LCGroupVoiceChannelUser(bits, 900, false);
        source.setValid(true);
        return source;
    }

    @Test
    void bothConveyancesNeedAFreshRadioAfterANewUnknownSourceCall()
    {
        for(boolean even: new boolean[]{false, true})
        {
            var processor = new P25P1MessageProcessor();
            var aliases = aliases(processor);
            processor.receive(ldu(source(101), 900));
            processor.receive(ldu(firstPart(even), 1000));
            processor.receive(ldu(secondPart(even), 1100));
            assertEquals(List.of("CDP #0231"), aliases);
            processor.receive(new TDUMessage(new CorrectedBinaryMessage(144), 1, 1200));
            processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), 1, 2000));
            processor.receive(ldu(source(0), 2100));
            processor.receive(ldu(firstPart(even), 2200));
            processor.receive(ldu(secondPart(even), 2300));
            assertEquals(List.of("CDP #0231"), aliases, "the new unknown caller cannot reuse the prior radio");
            processor.receive(ldu(source(202), 2400));
            processor.receive(ldu(firstPart(even), 2500));
            processor.receive(ldu(secondPart(even), 2600));
            assertEquals(List.of("CDP #0231", "CDP #0231"), aliases);
        }
    }

    @Test
    void aSourceChangeAlsoDiscardsAnEvenPairWithoutATerminator()
    {
        var processor = new P25P1MessageProcessor();
        var aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(firstPart(true), 1000));
        processor.receive(ldu(source(202), 1100));
        processor.receive(ldu(secondPart(true), 1200));
        assertTrue(aliases.isEmpty());
        processor.receive(ldu(firstPart(true), 1300));
        processor.receive(ldu(secondPart(true), 1400));
        assertEquals(List.of("CDP #0231"), aliases);
    }

    private static LinkControlWord firstPart(boolean even)
    {
        if(!even) return block1();
        var part = new io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasBlock3(
            CorrectedBinaryMessage.loadHex("34A443445020233032"));
        part.setValid(true);
        return part;
    }

    private static LinkControlWord secondPart(boolean even)
    {
        if(!even) return block2();
        var part = new io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerAliasBlock4(
            CorrectedBinaryMessage.loadHex("35A433312020202020"));
        part.setValid(true);
        return part;
    }

    @Test
    void carrierBsiPreservesAnInProgressAlias()
    {
        var processor = new P25P1MessageProcessor();
        var aliases = aliases(processor);
        processor.receive(ldu(source(101), 900));
        processor.receive(ldu(block1(), 1000));
        var bsi = new io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaBaseStationId(
            CorrectedBinaryMessage.loadHex("0590B20A1928820000"));
        bsi.setValid(true);
        processor.receive(new TDULCMessage(new CorrectedBinaryMessage(288), 1, 1050) {
            @Override public LinkControlWord getLinkControlWord() { return bsi; }
        });
        processor.receive(ldu(block2(), 1100));
        assertEquals(List.of("CDP #0231"), aliases);
    }

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

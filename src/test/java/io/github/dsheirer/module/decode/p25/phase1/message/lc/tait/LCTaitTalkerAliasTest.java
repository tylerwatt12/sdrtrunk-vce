/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase1.message.lc.tait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWordFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.UnknownLinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.standard.LCGroupVoiceChannelUser;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import org.junit.jupiter.api.Test;

class LCTaitTalkerAliasTest
{
    @Test
    void vendorDispatchCannotInterpretNameBitsAsStandardVoiceUsers()
    {
        var message = bits("TAITDEMO");
        LinkControlWord alias = LinkControlWordFactory.create(message, 1_000, false);
        assertInstanceOf(LCTaitTalkerAlias.class, alias);
        assertEquals(LinkControlOpcode.TAIT_TALKER_ALIAS, alias.getOpcode());
        assertTrue(alias.getIdentifiers().isEmpty());
        for(Vendor vendor: new Vendor[]{Vendor.V2, Vendor.RELM, Vendor.VUNK})
        {
            for(int opcode: new int[]{0, 3, 9, 63})
            {
                assertEquals(LinkControlOpcode.UNKNOWN, LinkControlOpcode.fromValue(opcode, vendor));
            }
        }
    }

    @Test
    void standardAndPreviouslySupportedVendorNamespacesRemainDistinct()
    {
        assertEquals(LinkControlOpcode.GROUP_VOICE_CHANNEL_USER,
            LinkControlOpcode.fromValue(0, Vendor.STANDARD));
        assertEquals(LinkControlOpcode.GROUP_VOICE_CHANNEL_USER,
            LinkControlOpcode.fromValue(0, Vendor.STANDARD_V1));
        assertEquals(LinkControlOpcode.MOTOROLA_GROUP_REGROUP_VOICE_CHANNEL_USER,
            LinkControlOpcode.fromValue(0, Vendor.MOTOROLA));
        assertEquals(LinkControlOpcode.L3HARRIS_TALKER_ALIAS_BLOCK_1,
            LinkControlOpcode.fromValue(0x32, Vendor.HARRIS));
        CorrectedBinaryMessage standard = bits("TAITDEMO");
        standard.set(1);
        assertInstanceOf(LCGroupVoiceChannelUser.class, LinkControlWordFactory.create(standard, 1_000, false));
        standard.clear(1);
        standard.load(8, 8, 1);
        assertInstanceOf(LCGroupVoiceChannelUser.class, LinkControlWordFactory.create(standard, 1_000, false));
    }

    @Test
    void companionIdentityCannotInventAStandardSourceOrSupplyAnAliasOwner()
    {
        CorrectedBinaryMessage companion = new CorrectedBinaryMessage(72);
        companion.load(0, 8, 1);
        companion.load(8, 8, 0xD8);
        companion.load(16, 20, 0xABCDE);
        companion.load(36, 12, 0x123);
        companion.load(48, 24, 101);
        LinkControlWord unknown = LinkControlWordFactory.create(companion, 1_000, false);
        assertInstanceOf(UnknownLinkControlWord.class, unknown);
        assertEquals(LinkControlOpcode.TAIT_UNKNOWN, unknown.getOpcode());
        assertTrue(unknown.getIdentifiers().isEmpty());
    }

    @Test
    void exactlyEightIso7CharactersPreserveCommasAndSpacesWithoutPublishingLooseNames()
    {
        LCTaitTalkerAlias alias = alias("UNIT,123");
        assertEquals("UNIT,123", alias.getTalkerAlias().getValue());
        assertTrue(alias.getIdentifiers().isEmpty());
        assertEquals(" A B ", alias(" A B ").getTalkerAlias().getValue());
        assertEquals("SHORT", alias("SHORT").getTalkerAlias().getValue());
    }

    @Test
    void failedFecProtectedAndMalformedTextCannotBecomeNames()
    {
        LCTaitTalkerAlias invalid = new LCTaitTalkerAlias(bits("UNIT 101"));
        assertNull(invalid.getTalkerAlias());
        LCTaitTalkerAlias protectedAlias = alias("UNIT 101");
        protectedAlias.getMessage().set(0);
        assertNull(protectedAlias.getTalkerAlias());
        for(String text: new String[]{"        ", "AB\u0000CD", "AB\u0001CD", "AB\u007FCD"})
        {
            assertNull(alias(text).getTalkerAlias());
        }
        var shortAlias = new LCTaitTalkerAlias(new CorrectedBinaryMessage(64));
        shortAlias.setValid(true);
        assertNull(shortAlias.getTalkerAlias());
        var longAlias = new LCTaitTalkerAlias(new CorrectedBinaryMessage(80));
        longAlias.setValid(true);
        assertNull(longAlias.getTalkerAlias());
    }

    private static LCTaitTalkerAlias alias(String text)
    {
        var alias = new LCTaitTalkerAlias(bits(text));
        alias.setValid(true);
        return alias;
    }

    private static CorrectedBinaryMessage bits(String text)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(72);
        message.load(8, 8, 0xD8);
        for(int index = 0; index < text.length(); index++)
        {
            message.load(16 + index * 7, 7, text.charAt(index));
        }
        return message;
    }
}

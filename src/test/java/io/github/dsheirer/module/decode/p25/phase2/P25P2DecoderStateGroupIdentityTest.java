/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.GroupAffiliationResponseExtended;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.GroupVoiceChannelUserAbbreviated;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P2DecoderStateGroupIdentityTest
{
    private static final P25SiteIdentity SITE = new P25SiteIdentity(0xBEE00, 0x348, 1, 1);

    @Test
    void parsesIndependentGroupFieldsAtNonzeroOffsetWithoutOverlappingTheTargetRadio()
    {
        var decoded = new GroupAffiliationResponseExtended(affiliationBits(19, 0), 19);
        var group = assertInstanceOf(APCO25FullyQualifiedTalkgroupIdentifier.class, decoded.getSourceGID());
        assertEquals(1_234, group.getLocalAddress());
        assertEquals(0xABCDE, group.getWacn());
        assertEquals(0x456, group.getSystem());
        assertEquals(5_678, group.getTalkgroup());
        assertEquals(Role.FROM, group.getRole());
        assertEquals(0x654321, decoded.getTargetAddress().getValue());
    }

    @Test
    void acceptedE8AffiliationResolvesLaterPlainVoiceDestinationAndSharedPhase1Manager()
    {
        var resolver = new P25IdentityResolver();
        var channel = channel("Control", new DecodeConfigP25Phase2());
        var manager = new P25TrafficChannelManager(channel, P25BandplanOverrideRegistry.empty(), resolver);
        var state = new P25P2DecoderState(channel, 0, manager, new PatchGroupManager());
        state.receive(message(1_000, new GroupAffiliationResponseExtended(affiliationBits(0, 0), 0)));
        var bits = new CorrectedBinaryMessage(64);
        bits.setInt(1, IntField.length8(0));
        bits.setInt(1_234, IntField.length16(16));
        bits.setInt(700_001, IntField.length24(32));
        state.receive(message(2_000, new GroupVoiceChannelUserAbbreviated(bits, 0)));
        var target = assertInstanceOf(APCO25FullyQualifiedTalkgroupIdentifier.class, state.getIdentifierCollection().getToIdentifier());
        assertEquals(Role.TO, target.getRole());
        assertEquals(5_678, target.getTalkgroup());
        assertEquals(1_234, target.getLocalAddress());
        assertInstanceOf(APCO25Talkgroup.class, target.getRecordingIdentifier());
        var phase1 = new P25TrafficChannelManager(channel("Phase1", new DecodeConfigP25Phase1()),
            P25BandplanOverrideRegistry.empty(), resolver);
        var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(1_234)));
        phase1.enrichMutableIdentifiers(identifiers, 2_001);
        assertEquals(5_678, assertInstanceOf(APCO25FullyQualifiedTalkgroupIdentifier.class, identifiers.getToIdentifier()).getTalkgroup());
    }

    @Test
    void rejectedAffiliationDoesNotTeachLaterCalls()
    {
        var resolver = new P25IdentityResolver();
        var channel = channel("Control", new DecodeConfigP25Phase2());
        var manager = new P25TrafficChannelManager(channel, P25BandplanOverrideRegistry.empty(), resolver);
        var state = new P25P2DecoderState(channel, 0, manager, new PatchGroupManager());
        state.receive(message(1_000, new GroupAffiliationResponseExtended(affiliationBits(0, 2), 0)));
        var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(1_234)));
        manager.enrichMutableIdentifiers(identifiers, 2_000);
        assertInstanceOf(APCO25Talkgroup.class, identifiers.getToIdentifier());
        assertTrue(resolver.snapshot(2_000).groupAssignments().isEmpty());
    }

    private static Channel channel(String name, io.github.dsheirer.module.decode.config.DecodeConfiguration configuration)
    {
        var channel = new Channel(name, Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(configuration);
        channel.setP25SiteIdentity(SITE);
        return channel;
    }

    private static MacMessage message(long at, io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure structure)
    {
        return new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH, new CorrectedBinaryMessage(180), at, structure);
    }

    private static CorrectedBinaryMessage affiliationBits(int offset, int response)
    {
        var bits = new CorrectedBinaryMessage(offset + 128);
        bits.setInt(0xE8, IntField.length8(offset));
        bits.setInt(response, IntField.length2(offset + 22));
        bits.setInt(0xCCDD, IntField.length16(offset + 24));
        bits.setInt(1_234, IntField.length16(offset + 40));
        bits.setInt(0xABCDE, IntField.length20(offset + 56));
        bits.setInt(0x456, IntField.length12(offset + 76));
        bits.setInt(5_678, IntField.length16(offset + 88));
        bits.setInt(0x654321, IntField.length24(offset + 104));
        return bits;
    }
}

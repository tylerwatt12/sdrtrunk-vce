/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.harris.osp.L3HarrisGroupRegroupExplicitEncryptionCommand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaGroupRegroupAddCommand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaGroupRegroupDeleteCommand;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.P25P2DecoderState;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25PatchHandlingTest
{
    private static final int NAC = 0x123;
    private static final int SUPERGROUP = 500;

    @Test
    void harrisPhaseOneUpdatesImmediatelyAndCancelsOnlyItsSequence()
    {
        P25P1DecoderState state = phaseOne();
        state.receive(harrisPhaseOne(1_000L, 9, true, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101);
        state.receive(harrisPhaseOne(1_100L, 9, true, false, 202));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101, 202);
        state.receive(harrisPhaseOne(2_000L, 9, true, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101, 202);
        assertEquals(2_000L, state.getP25NetworkConfigurationSnapshot().patchGroups().getFirst().observedAtMs());

        state.receive(harrisPhaseOne(2_100L, 10, true, true, 303));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 10, 2, 303);
        state.receive(harrisPhaseOne(2_200L, 9, false, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 10, 2, 303);
        state.receive(harrisPhaseOne(2_300L, 10, false, true, 303));
        assertTrue(state.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());
    }

    @Test
    void harrisPhaseTwoUpdatesImmediatelyAndCancelsOnlyItsSequence()
    {
        P25P2DecoderState state = phaseTwo();
        state.receive(harrisPhaseTwo(1_000L, 9, true, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101);
        state.receive(harrisPhaseTwo(1_100L, 9, true, false, 202));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101, 202);
        state.receive(harrisPhaseTwo(2_000L, 9, true, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 9, 1, 101, 202);
        assertEquals(2_000L, state.getP25NetworkConfigurationSnapshot().patchGroups().getFirst().observedAtMs());

        state.receive(harrisPhaseTwo(2_100L, 10, true, true, 303));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 10, 2, 303);
        state.receive(harrisPhaseTwo(2_200L, 9, false, false, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 10, 2, 303);
        state.receive(harrisPhaseTwo(2_300L, 10, false, true, 303));
        assertTrue(state.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());
    }

    @Test
    void invalidAndProtectedMessagesCannotCreatePatchState()
    {
        P25P1DecoderState phaseOne = phaseOne();
        TSBKMessage invalid = harrisPhaseOne(1_000L, 9, true, false, 101);
        invalid.setValid(false);
        phaseOne.receive(invalid);
        TSBKMessage protectedMessage = harrisPhaseOne(1_100L, 9, true, false, 101);
        protectedMessage.getMessage().set(1);
        phaseOne.receive(protectedMessage);
        assertTrue(phaseOne.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());

        P25P2DecoderState phaseTwo = phaseTwo();
        MacMessage invalidMac = harrisPhaseTwo(1_000L, 9, true, false, 101);
        invalidMac.setValid(false);
        phaseTwo.receive(invalidMac);
        assertTrue(phaseTwo.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());
    }

    @Test
    void motorolaPhaseOneRefreshesAndRemovesIndividualMembers()
    {
        P25P1DecoderState state = phaseOne();
        state.receive(motorolaPhaseOne(1_000L, false, 101, 202));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 0, 1, 101, 202);
        state.receive(motorolaPhaseOne(1_100L, false, 101, 202));
        assertEquals(1_100L, state.getP25NetworkConfigurationSnapshot().patchGroups().getFirst().observedAtMs());
        state.receive(motorolaPhaseOne(1_200L, true, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 0, 1, 202);
        state.receive(motorolaPhaseOne(1_300L, true, SUPERGROUP));
        assertTrue(state.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());
    }

    @Test
    void motorolaPhaseTwoRefreshesAndRemovesIndividualMembers()
    {
        P25P2DecoderState state = phaseTwo();
        state.receive(motorolaPhaseTwo(1_000L, false, 101, 202));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 0, 1, 101, 202);
        state.receive(motorolaPhaseTwo(1_100L, false, 101, 202));
        assertEquals(1_100L, state.getP25NetworkConfigurationSnapshot().patchGroups().getFirst().observedAtMs());
        state.receive(motorolaPhaseTwo(1_200L, true, 101));
        assertPatch(state.getP25NetworkConfigurationSnapshot(), 0, 1, 202);
        state.receive(motorolaPhaseTwo(1_300L, true, SUPERGROUP));
        assertTrue(state.getP25NetworkConfigurationSnapshot().patchGroups().isEmpty());
    }

    private static void assertPatch(P25NetworkConfigurationSnapshot snapshot, int version, int type,
                                    Integer... members)
    {
        assertEquals(1, snapshot.patchGroups().size());
        P25NetworkConfigurationSnapshot.PatchGroup patch = snapshot.patchGroups().getFirst();
        assertEquals(SUPERGROUP, patch.localPatchGroupId());
        assertEquals(version, patch.version());
        assertEquals(type, patch.regroupType());
        assertEquals(List.of(members), patch.localTalkgroupIds());
    }

    private static P25P1DecoderState phaseOne()
    {
        Channel channel = new Channel("Synthetic Phase 1 patch", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return new P25P1DecoderState(channel, null);
    }

    private static P25P2DecoderState phaseTwo()
    {
        Channel channel = new Channel("Synthetic Phase 2 patch", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase2());
        return new P25P2DecoderState(channel, 0, new P25TrafficChannelManager(channel), new PatchGroupManager());
    }

    private static TSBKMessage harrisPhaseOne(long timestamp, int sequence, boolean active,
                                              boolean simulselect, int member)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, 0x30);
        bits.load(8, 8, 0xA4);
        bits.load(16, 8, options(sequence, active, simulselect));
        bits.load(24, 16, SUPERGROUP);
        bits.load(64, 16, member);
        TSBKMessage message = new L3HarrisGroupRegroupExplicitEncryptionCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, NAC, timestamp);
        message.setValid(true);
        return message;
    }

    private static TSBKMessage motorolaPhaseOne(long timestamp, boolean delete, int... members)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, delete ? 1 : 0);
        bits.load(8, 8, 0x90);
        bits.load(16, 16, SUPERGROUP);
        for(int index = 0; index < members.length; index++)
        {
            bits.load(32 + 16 * index, 16, members[index]);
        }
        TSBKMessage message = delete ? new MotorolaGroupRegroupDeleteCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, NAC, timestamp) :
            new MotorolaGroupRegroupAddCommand(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, NAC, timestamp);
        message.setValid(true);
        return message;
    }

    private static MacMessage harrisPhaseTwo(long timestamp, int sequence, boolean active,
                                             boolean simulselect, int member)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(180);
        bits.load(8, 8, 0xB0);
        bits.load(16, 8, 0xA4);
        bits.load(24, 8, 11);
        bits.load(32, 8, options(sequence, active, simulselect));
        bits.load(40, 16, SUPERGROUP);
        bits.load(80, 16, member);
        return mac(timestamp, bits,
            new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris.
                L3HarrisGroupRegroupExplicitEncryptionCommand(bits, 8));
    }

    private static MacMessage motorolaPhaseTwo(long timestamp, boolean delete, int... members)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(180);
        bits.load(8, 8, delete ? 0x89 : 0x81);
        bits.load(16, 8, 0x90);
        bits.load(24, 8, 5 + members.length * 2);
        bits.load(32, 16, SUPERGROUP);
        for(int index = 0; index < members.length; index++)
        {
            bits.load(48 + 16 * index, 16, members[index]);
        }
        MacStructure structure = delete ?
            new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.
                MotorolaGroupRegroupDeleteCommand(bits, 8) :
            new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola.
                MotorolaGroupRegroupAddCommand(bits, 8);
        return mac(timestamp, bits, structure);
    }

    private static MacMessage mac(long timestamp, CorrectedBinaryMessage bits, MacStructure structure)
    {
        MacMessage message = new MacMessage(0, DataUnitID.UNSCRAMBLED_LCCH, bits, timestamp, structure);
        message.setNAC(NAC);
        return message;
    }

    private static int options(int sequence, boolean active, boolean simulselect)
    {
        return sequence | 0x40 | (active ? 0x20 : 0) | (simulselect ? 0x80 : 0);
    }
}

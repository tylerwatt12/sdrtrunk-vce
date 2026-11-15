/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaGroupRegroupAdd;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.harris.osp.L3HarrisGroupRegroupExplicitEncryptionCommand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaGroupRegroupAddCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25RegroupMessageTest
{
    @Test
    void harrisPhaseOneKeepsTypeSequenceAndTheProperTargetWidth()
    {
        //TIA-102.AABH (November 2014), 4.3.1.1 and 4.3.2.1 distinguish the group and unit layouts.
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(0x69, IntField.length8(16));
        bits.setInt(500, IntField.length16(24));
        bits.setInt(0x84, IntField.length8(56));
        bits.setInt(501, IntField.length16(64));
        var message = new L3HarrisGroupRegroupExplicitEncryptionCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x123, 1_000L);
        PatchGroup patch = message.getPatchGroup().getValue();
        assertEquals(PatchGroup.Type.PATCH, patch.getType());
        assertEquals(9, patch.getVersion());
        assertEquals(500, patch.getPatchGroup().getValue());
        assertEquals(List.of(501), talkgroups(patch));
        assertTrue(message.getRegroupOptions().isActivate());

        bits = new CorrectedBinaryMessage(96);
        bits.setInt(0xAA, IntField.length8(16));
        bits.setInt(500, IntField.length16(24));
        bits.setInt(0x010203, IntField.length24(56));
        message = new L3HarrisGroupRegroupExplicitEncryptionCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x123, 2_000L);
        assertEquals(PatchGroup.Type.SIMULSELECT, message.getPatchGroup().getValue().getType());
        assertEquals(List.of(0x010203), radios(message.getPatchGroup().getValue()));
        assertEquals(10, message.getPatchGroup().getValue().getVersion());
        assertFalse(message.getRegroupOptions().isTalkgroupAddress());
    }

    @Test
    void harrisPhaseTwoKeepsMembersAfterEmptySlotsAtARealStructureOffset()
    {
        var patch = harrisPhaseTwo(17, 17, 8, true, 0, 501, 0, 502).getPatchGroup().getValue();
        assertEquals(PatchGroup.Type.PATCH, patch.getType());
        assertEquals(9, patch.getVersion());
        assertEquals(List.of(501, 502), talkgroups(patch));

        var simulselect = harrisPhaseTwo(17, 17, 16, false, 0, 0x010203, 0).getPatchGroup().getValue();
        assertEquals(PatchGroup.Type.SIMULSELECT, simulselect.getType());
        assertEquals(List.of(0x010203), radios(simulselect));
    }

    @Test
    void harrisPhaseTwoUsesOnlyCompleteMembersInsideDeclaredAndAvailableData()
    {
        //Extra bytes can belong to a following MAC structure, and must not become members.
        assertEquals(List.of(501), talkgroups(
            harrisPhaseTwo(11, 17, 8, true, 501, 502, 503, 504).getPatchGroup().getValue()));
        assertEquals(List.of(501), talkgroups(
            harrisPhaseTwo(17, 12, 8, true, 501, 502, 503, 504).getPatchGroup().getValue()));
        assertEquals(List.of(), talkgroups(
            harrisPhaseTwo(10, 17, 8, true, 501, 502, 503, 504).getPatchGroup().getValue()));
        assertEquals(List.of(0x010203), radios(
            harrisPhaseTwo(17, 13, 8, false, 0x010203, 0x020304, 0).getPatchGroup().getValue()));
        assertEquals(List.of(0x010203), radios(
            harrisPhaseTwo(11, 17, 8, false, 0x010203, 0x020304, 0).getPatchGroup().getValue()));
    }

    @Test
    void motorolaAddFormsKeepTheirMembersAndPatchType()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(500, IntField.length16(16));
        bits.setInt(501, IntField.length16(32));
        bits.setInt(502, IntField.length16(48));
        bits.setInt(503, IntField.length16(64));
        var phaseOne = new MotorolaGroupRegroupAddCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x123, 1_000L).getPatchGroup().getValue();
        assertEquals(PatchGroup.Type.PATCH, phaseOne.getType());
        assertEquals(List.of(501, 502, 503), talkgroups(phaseOne));

        var linkControl = (PatchGroupIdentifier)new LCMotorolaGroupRegroupAdd(bits).getPatchGroup();
        assertEquals(PatchGroup.Type.PATCH, linkControl.getValue().getType());
        assertEquals(List.of(501, 502), talkgroups(linkControl.getValue()));

        bits = new CorrectedBinaryMessage(144);
        int offset = 8;
        bits.setInt(17, IntField.length6(offset + 18));
        bits.setInt(500, IntField.length16(offset + 24));
        bits.setInt(501, IntField.length16(offset + 40));
        bits.setInt(502, IntField.length16(offset + 72));
        bits.setInt(503, IntField.length16(offset + 120));
        var phaseTwo = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola
            .MotorolaGroupRegroupAddCommand(bits, offset).getPatchGroup().getValue();
        assertEquals(PatchGroup.Type.PATCH, phaseTwo.getType());
        assertEquals(List.of(501, 502, 503), talkgroups(phaseTwo));
    }

    @Test
    void motorolaDeleteFormsCarryMembersAndTheDissolutionTarget()
    {
        //TIA-102.AABH (November 2014), 4.4.2 uses the supergroup address to dissolve the grouping.
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(500, IntField.length16(16));
        bits.setInt(501, IntField.length16(32));
        bits.setInt(500, IntField.length16(64));
        var phaseOne = new io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp
            .MotorolaGroupRegroupDeleteCommand(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                bits, 0x123, 1_000L).getPatchGroup().getValue();
        assertEquals(List.of(501, 500), talkgroups(phaseOne));

        bits.setInt(500, IntField.length16(48));
        var linkControl = (PatchGroupIdentifier)new io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola
            .LCMotorolaGroupGroupDelete(bits).getSuperGroup();
        assertEquals(List.of(501, 500), talkgroups(linkControl.getValue()));

        bits = new CorrectedBinaryMessage(144);
        int offset = 8;
        bits.setInt(17, IntField.length6(offset + 18));
        bits.setInt(500, IntField.length16(offset + 24));
        bits.setInt(501, IntField.length16(offset + 40));
        bits.setInt(500, IntField.length16(offset + 120));
        var phaseTwo = new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola
            .MotorolaGroupRegroupDeleteCommand(bits, offset).getPatchGroup().getValue();
        assertEquals(List.of(501, 500), talkgroups(phaseTwo));
    }

    @Test
    void unusedMotorolaPhaseOneMembersDoNotBecomeGroupZero()
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(500, IntField.length16(16));
        bits.setInt(501, IntField.length16(64));
        var add = new MotorolaGroupRegroupAddCommand(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, 0x123, 1_000L).getPatchGroup().getValue();
        assertEquals(List.of(501), talkgroups(add));
        var remove = new io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp
            .MotorolaGroupRegroupDeleteCommand(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                bits, 0x123, 1_000L).getPatchGroup().getValue();
        assertEquals(List.of(501), talkgroups(remove));
    }

    private static io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris
        .L3HarrisGroupRegroupExplicitEncryptionCommand harrisPhaseTwo(int declaredLength, int availableLength,
            int offset, boolean talkgroups, int... members)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(offset + availableLength * 8);
        bits.setInt(declaredLength, IntField.length6(offset + 18));
        bits.setInt(talkgroups ? 0x69 : 0xA9, IntField.length8(offset + 24));
        bits.setInt(500, IntField.length16(offset + 32));
        int width = talkgroups ? 16 : 24;
        int start = talkgroups ? 72 : 64;
        for(int index = 0; index < members.length; index++)
        {
            int memberOffset = offset + start + index * width;
            if(memberOffset + width <= bits.size())
            {
                bits.setInt(members[index], new IntField(memberOffset, memberOffset + width - 1));
            }
            else if(memberOffset < bits.size())
            {
                int availableBits = bits.size() - memberOffset;
                bits.load(memberOffset, availableBits, members[index] >>> (width - availableBits));
            }
        }
        return new io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.l3harris
            .L3HarrisGroupRegroupExplicitEncryptionCommand(bits, offset);
    }

    private static List<Integer> talkgroups(PatchGroup group)
    {
        return group.getPatchedTalkgroupIdentifiers().stream().map(identifier -> identifier.getValue()).toList();
    }

    private static List<Integer> radios(PatchGroup group)
    {
        return group.getPatchedRadioIdentifiers().stream().map(identifier -> identifier.getValue()).toList();
    }
}

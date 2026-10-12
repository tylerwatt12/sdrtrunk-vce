/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class ExtendedVoiceChannelUserIdentityTest
{
    @Test
    void extendedGroupAndPrivateCallsRetainAnEligibleZeroLocalCanonicalTalker()
    {
        for(boolean privateCall: new boolean[]{false, true})
        {
            var message = message(privateCall, 0, 9_001);
            var source = message instanceof UnitToUnitVoiceChannelUserExtended unit ? unit.getSource() :
                ((GroupVoiceChannelUserExtended)message).getSource();

            assertEquals(Role.FROM, source.getRole());
            assertEquals(0xBEE00, source.getWacn());
            assertEquals(0x123, source.getSystem());
            assertEquals(9_001, source.getRadio());
            assertEquals(0, source.getValue());
            assertTrue(source.hasExplicitWorkingAddress());
            assertNull(source.getWorkingAddress());
            assertSame(source, message.getIdentifiers().get(1));
            assertTrue(TrunkedIdentityEligibility.isEligibleDecodedIdentifier(Protocol.APCO25_PHASE2,
                TrunkedIdentityDomain.STANDARD, source));
            ResolvedRadioIdentity identity = ResolvedRadioIdentity.from(source);
            assertEquals(9_001, identity.subscriber().subscriberId());
            assertNull(identity.observedWorkingId());
            assertEquals(ResolvedRadioIdentity.Evidence.DIRECT, identity.evidence());
        }
    }

    @Test
    void extendedVoiceEligibilityRetainsValidWorkingAddressesAndRejectsReservedValues()
    {
        for(boolean privateCall: new boolean[]{false, true})
        {
            for(int local: new int[]{1, 501, 0xFFFFFC, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF})
            {
                var message = message(privateCall, local, 9_001);
                var source = message.getIdentifiers().get(1);

                assertEquals(local <= 0xFFFFFC, TrunkedIdentityEligibility.isEligibleDecodedIdentifier(
                    Protocol.APCO25_PHASE2, TrunkedIdentityDomain.STANDARD, source));
            }
            for(int unit: new int[]{0, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF})
            {
                assertFalse(TrunkedIdentityEligibility.isEligibleDecodedIdentifier(Protocol.APCO25_PHASE2,
                    TrunkedIdentityDomain.STANDARD, message(privateCall, 0, unit).getIdentifiers().get(1)));
            }
        }
    }

    private static MacStructure message(boolean privateCall, int local, int unit)
    {
        //TIA-102.BBAC (December 2010), Figures 8-14 and 8-16: Source Address and Source SUID are separate fields.
        int sourceStart = privateCall ? 40 : 32;
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(sourceStart + 80);
        bits.setInt(privateCall ? 777 : 1001, privateCall ? IntField.length24(16) : IntField.length16(16));
        bits.setInt(local, IntField.length24(sourceStart));
        bits.setInt(0xBEE00, IntField.length20(sourceStart + 24));
        bits.setInt(0x123, IntField.length12(sourceStart + 44));
        bits.setInt(unit, IntField.length24(sourceStart + 56));
        return privateCall ? new UnitToUnitVoiceChannelUserExtended(bits, 0) :
            new GroupVoiceChannelUserExtended(bits, 0);
    }
}

/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.motorola;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import org.junit.jupiter.api.Test;

class MotorolaGroupRegroupVoiceChannelUserExtendedTest
{
    @Test
    void zeroLocalAddressRetainsTheCompleteCanonicalSourceWithoutAnAssignment()
    {
        var message = message(0, 0xBEE00, 0x123, 9_001, 8);
        var source = message.getSource();

        assertTrue(message.hasRadio());
        assertEquals(0, source.getValue());
        assertEquals(0xBEE00, source.getWacn());
        assertEquals(0x123, source.getSystem());
        assertEquals(9_001, source.getRadio());
        assertEquals(Role.FROM, source.getRole());
        assertTrue(source.hasExplicitWorkingAddress(), "The field was present, although zero assigns no address");
        assertNull(source.getWorkingAddress());
        assertFalse(source.isAliased());
        assertEquals(IdentityResolutionEvidence.DIRECT, source.getResolutionEvidence());
        assertEquals(2, message.getIdentifiers().size());
        assertSame(source, message.getIdentifiers().get(1));
    }

    @Test
    void assignableWorkingAddressesKeepTheirExplicitProtocolEvidence()
    {
        for(int local: new int[]{1, 501, 9_001, 0xFFFFFC})
        {
            var message = message(local, 0xBEE00, 0x123, 9_001, 0);

            assertTrue(message.hasRadio());
            assertEquals(local, message.getSource().getWorkingAddress());
            assertTrue(message.getSource().hasExplicitWorkingAddress());
            assertEquals(2, message.getIdentifiers().size());
        }
        assertTrue(message(0, 0xBEE00, 0x123, 1, 0).hasRadio());
        assertTrue(message(0, 0xBEE00, 0x123, 0xFFFFFC, 0).hasRadio());
    }

    @Test
    void absentAndReservedCanonicalUnitIdsDoNotPublishASubscriber()
    {
        //TIA-102.AABC-B (2005), Table 2.3.30-1: no unit and the three special unit addresses.
        for(int unit: new int[]{0, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF})
        {
            for(int local: new int[]{0, 501})
            {
                var message = message(local, 0xBEE00, 0x123, unit, 0);

                assertFalse(message.hasRadio());
                assertEquals(1, message.getIdentifiers().size());
                assertSame(message.getPatchgroup(), message.getIdentifiers().getFirst());
            }
        }
        assertFalse(message(0, 0, 0, 0, 0).hasRadio());
    }

    @Test
    void reservedWorkingAddressesCannotEstablishASubscriberAssignment()
    {
        for(int local: new int[]{0xFFFFFD, 0xFFFFFE, 0xFFFFFF})
        {
            var message = message(local, 0xBEE00, 0x123, 9_001, 0);

            assertFalse(message.hasRadio());
            assertNull(message.getSource().getWorkingAddress());
            assertEquals(1, message.getIdentifiers().size());
        }
    }

    private static MotorolaGroupRegroupVoiceChannelUserExtended message(int local, int wacn, int system,
                                                                        int unit, int offset)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(128 + offset);
        bits.setInt(1001, IntField.length16(32 + offset));
        bits.setInt(local, IntField.length24(48 + offset));
        bits.setInt(wacn, IntField.length20(72 + offset));
        bits.setInt(system, IntField.length12(92 + offset));
        bits.setInt(unit, IntField.length24(104 + offset));
        return new MotorolaGroupRegroupVoiceChannelUserExtended(bits, offset);
    }
}

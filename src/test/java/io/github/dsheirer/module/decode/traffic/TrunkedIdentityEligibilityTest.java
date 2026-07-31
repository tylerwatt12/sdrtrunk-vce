/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class TrunkedIdentityEligibilityTest
{
    @Test
    void appliesProtocolSpecialAddressesAndNxdnDomains()
    {
        assertTrue(TrunkedIdentityEligibility.isEligible(Protocol.APCO25, TrunkedIdentityDomain.STANDARD,
            Form.TALKGROUP, 0xFFFE));
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.APCO25, TrunkedIdentityDomain.STANDARD,
            Form.TALKGROUP, 0xFFFF));
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.APCO25, TrunkedIdentityDomain.STANDARD,
            Form.RADIO, 0xFFFFFC));
        assertTrue(TrunkedIdentityEligibility.isEligible(Protocol.APCO25_PHASE2,
            TrunkedIdentityDomain.STANDARD, Form.TALKGROUP, 0xFFFE));
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.APCO25_PHASE2,
            TrunkedIdentityDomain.STANDARD, Form.RADIO, 0xFFFFFC));

        assertTrue(TrunkedIdentityEligibility.isEligible(Protocol.DMR, TrunkedIdentityDomain.STANDARD,
            Form.TALKGROUP, 300_956));
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.DMR, TrunkedIdentityDomain.STANDARD,
            Form.RADIO, 0xFFFECA));

        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_C,
            Form.TALKGROUP, 0xFFF0));
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_C,
            Form.RADIO, 0xFFF1));
        assertTrue(TrunkedIdentityEligibility.isEligible(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_D,
            Form.TALKGROUP, 0xFFF0));
        assertTrue(TrunkedIdentityEligibility.isEligible(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_D,
            Form.RADIO, 0xFFF1));
    }
}

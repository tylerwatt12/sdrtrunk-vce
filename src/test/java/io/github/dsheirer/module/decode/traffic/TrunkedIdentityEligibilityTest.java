/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25IncompleteRadioIdentifier;
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

    @Test
    void separatesPermanentP25RadioIdsFromTemporaryWorkingAddresses()
    {
        assertFalse(TrunkedIdentityEligibility.isEligible(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD, Form.RADIO, 0xFFFD26));
        assertTrue(TrunkedIdentityEligibility.isEligibleDecodedIdentifier(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD,
            APCO25FullyQualifiedRadioIdentifier.createTo(0xFFFD26, 0xBEE00, 0x954, 831_102)));
        assertFalse(TrunkedIdentityEligibility.isEligibleDecodedIdentifier(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD,
            APCO25FullyQualifiedRadioIdentifier.createTo(0xFFFFFD, 0xBEE00, 0x954, 831_102)));
        assertFalse(TrunkedIdentityEligibility.isEligibleDecodedIdentifier(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD, APCO25IncompleteRadioIdentifier.createTo(831_102)));
        assertTrue(TrunkedIdentityEligibility.isObservedLocalEligible(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD, Form.RADIO, 0xFFFD26, true));
        assertFalse(TrunkedIdentityEligibility.isObservedLocalEligible(Protocol.APCO25,
            TrunkedIdentityDomain.STANDARD, Form.RADIO, 0xFFFFFD, true));
    }
}

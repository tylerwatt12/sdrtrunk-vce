/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.isp;

import static io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.GroupAffiliationTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUMessageFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TIA-102.AABC-E section 6.1.9.2, Figure 6.1.9-2. Requests remain observations, not assignments. */
class AMBTCGroupAffiliationRequestTest
{
    @Test
    void admittedRequestReadsRadioFromHeaderAndGroupFromPayload()
    {
        var sequence = request(0x3715);
        assertTrue(sequence.isValid());
        var decoded = assertInstanceOf(AMBTCGroupAffiliationRequest.class,
            PDUMessageFactory.create(sequence, NAC, TIMESTAMP));
        assertEquals(WORKING_RADIO, decoded.getSourceAddress().getValue());
        assertEquals(Role.FROM, decoded.getSourceAddress().getRole());
        assertEquals(0x3715, decoded.getGroupId().getValue());
        assertEquals(0xBEE00, decoded.getWacn().getValue());
        assertEquals(0x3A9, decoded.getSystem().getValue());
        assertEquals(List.of(decoded.getSourceAddress(), decoded.getWacn(), decoded.getSystem(), decoded.getGroupId()),
            decoded.getIdentifiers());
    }

    @Test
    void payloadGroupBoundaryValuesDoNotChangeTheHeaderRadio()
    {
        for(int group : new int[]{0, 1, 0xFFFE, 0xFFFF})
        {
            var sequence = request(group);
            assertTrue(sequence.isValid());
            var decoded = assertInstanceOf(AMBTCGroupAffiliationRequest.class,
                PDUMessageFactory.create(sequence, NAC, TIMESTAMP));
            assertEquals(group, decoded.getGroupId().getValue());
            assertEquals(WORKING_RADIO, decoded.getSourceAddress().getValue());
        }
    }

    @Test
    void headerOnlyRequestKeepsRadioWithoutInventingPayloadIdentities()
    {
        var full = request(0x3715);
        var decoded = new AMBTCGroupAffiliationRequest(new PDUSequence(full.getHeader(), TIMESTAMP, NAC), NAC, TIMESTAMP);
        assertEquals(WORKING_RADIO, decoded.getSourceAddress().getValue());
        assertNull(decoded.getGroupId());
        assertNull(decoded.getWacn());
        assertNull(decoded.getSystem());
        assertEquals(List.of(decoded.getSourceAddress()), decoded.getIdentifiers());
    }
}

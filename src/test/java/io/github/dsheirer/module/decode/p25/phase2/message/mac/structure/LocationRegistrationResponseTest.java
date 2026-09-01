/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase2.message.mac.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationRegistrationResponseTest
{
    @Test
    void includesRfssAndSiteInIdentifiers()
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96);
        message.setInt(56_133, IntField.length16(24));
        message.setInt(2, IntField.length8(40));
        message.setInt(7, IntField.length8(48));
        message.setInt(1_811_524, IntField.length24(56));
        LocationRegistrationResponse response = new LocationRegistrationResponse(message, 0);

        List<Identifier> identifiers = response.getIdentifiers();

        assertEquals(List.of(response.getGroupAddress(), response.getRFSS(), response.getSite(),
            response.getTargetAddress()), identifiers);
        assertEquals(2, ((Number)response.getRFSS().getValue()).intValue());
        assertEquals(7, ((Number)response.getSite().getValue()).intValue());
    }
}

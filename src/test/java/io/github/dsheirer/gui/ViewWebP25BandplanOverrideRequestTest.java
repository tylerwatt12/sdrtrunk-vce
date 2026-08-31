/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import org.junit.jupiter.api.Test;

class ViewWebP25BandplanOverrideRequestTest
{
    @Test
    void carriesOneCompleteP25SiteIdentity()
    {
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x49F, 1, 2);
        ViewWebP25BandplanOverrideRequest request = new ViewWebP25BandplanOverrideRequest(identity);

        assertEquals(identity, request.getIdentity());
        assertThrows(NullPointerException.class, () -> new ViewWebP25BandplanOverrideRequest(null));
    }
}

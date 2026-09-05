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
    private static final String RADIORESOLVE_ID = "abcdefab-cdef-abcd-efab-cdefabcdefab";

    @Test
    void carriesOneCompleteP25SiteIdentityAndRadioResolveId()
    {
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x49F, 1, 2);
        ViewWebP25BandplanOverrideRequest request =
            new ViewWebP25BandplanOverrideRequest(identity, RADIORESOLVE_ID);

        assertEquals(identity, request.getIdentity());
        assertEquals(RADIORESOLVE_ID, request.getRadioResolveId());
        assertThrows(NullPointerException.class,
            () -> new ViewWebP25BandplanOverrideRequest(null, RADIORESOLVE_ID));
        assertThrows(NullPointerException.class, () -> new ViewWebP25BandplanOverrideRequest(identity, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ViewWebP25BandplanOverrideRequest(identity, RADIORESOLVE_ID.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
            () -> new ViewWebP25BandplanOverrideRequest(identity, "not-an-id"));
    }
}

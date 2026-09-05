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
    private static final String CONFIGURATION_ID = "abcdefab-cdef-abcd-efab-cdefabcdefab";

    @Test
    void carriesOneCompleteP25SiteIdentityAndConfigurationId()
    {
        P25SiteIdentity identity = new P25SiteIdentity(0xBEE00, 0x49F, 1, 2);
        ViewWebP25BandplanOverrideRequest request =
            new ViewWebP25BandplanOverrideRequest(identity, CONFIGURATION_ID);

        assertEquals(identity, request.getIdentity());
        assertEquals(CONFIGURATION_ID, request.getConfigurationId());
        assertThrows(NullPointerException.class,
            () -> new ViewWebP25BandplanOverrideRequest(null, CONFIGURATION_ID));
        assertThrows(NullPointerException.class, () -> new ViewWebP25BandplanOverrideRequest(identity, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ViewWebP25BandplanOverrideRequest(identity, CONFIGURATION_ID.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
            () -> new ViewWebP25BandplanOverrideRequest(identity, "not-an-id"));
    }
}

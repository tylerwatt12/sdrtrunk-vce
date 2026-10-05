/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.identifier.radio.P25SubscriberIdentityFormatter;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import org.junit.jupiter.api.Test;

class P25SubscriberIdentityTest
{
    @Test
    void formatsTheCanonicalDisplayWithoutFlatteningItsComponents()
    {
        P25SubscriberIdentity identity = new P25SubscriberIdentity(0xBEE00, 0x4A2, 2_115_288);

        assertEquals(0xBEE00, identity.homeWacn());
        assertEquals(0x4A2, identity.homeSystemId());
        assertEquals(2_115_288, identity.subscriberId());
        assertEquals("BEE00.4A2.2115288", identity.display());
    }

    @Test
    void canonicalAdmissionUsesTheCompleteAssignableSubscriberRange()
    {
        assertEquals(0xFFFFFC,
            new P25SubscriberIdentity(0xFFFFF, 0xFFF, 0xFFFFFC).subscriberId());
        assertThrows(IllegalArgumentException.class,
            () -> new P25SubscriberIdentity(0xBEE00, 0x348, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new P25SubscriberIdentity(0xBEE00, 0x348, 0xFFFFFD));
    }

    @Test
    void diagnosticFormattingRemainsSafeForReservedDecodedAddresses()
    {
        assertEquals("BEE00.348.0", P25SubscriberIdentityFormatter.format(0xBEE00, 0x348, 0));
        assertEquals("BEE00.348.16777215",
            P25SubscriberIdentityFormatter.format(0xBEE00, 0x348, 0xFFFFFF));
        assertThrows(IllegalArgumentException.class,
            () -> P25SubscriberIdentityFormatter.format(0x100000, 0x348, 1));
        assertThrows(IllegalArgumentException.class,
            () -> P25SubscriberIdentityFormatter.format(0xBEE00, 0x1000, 1));
    }

    @Test
    void extractionRequiresAnExplicitCompleteAndAssignableSuid()
    {
        APCO25FullyQualifiedRadioIdentifier decoded =
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x348, 2_115_288);
        assertEquals(new P25SubscriberIdentity(0xBEE00, 0x348, 2_115_288),
            P25SubscriberIdentity.from(decoded));
        assertEquals("BEE00.348.2115288", decoded.getFullyQualifiedRadioAddress());
        assertEquals("BEE00.348.2115288 (Working ID 501)", decoded.toString());

        APCO25FullyQualifiedRadioIdentifier nativeSubscriber =
            APCO25FullyQualifiedRadioIdentifier.createFrom(2_115_288, 0xBEE00, 0x348, 2_115_288);
        assertEquals("BEE00.348.2115288", nativeSubscriber.toString());

        assertNull(P25SubscriberIdentity.from(APCO25RadioIdentifier.createFrom(501)),
            "a serving-system working address is not a canonical subscriber identity");
        assertNull(P25SubscriberIdentity.from(
            APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x348, 0xFFFFFD)),
            "reserved decoded values are displayable but are not admitted to the canonical directory");
    }
}

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
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import org.junit.jupiter.api.Test;

class P25SubscriberIdentityTest
{
    @Test
    void displayOmitsHomePrefixUnlessTheServingSystemPositivelyDiffers()
    {
        assertEquals("9001 (Working ID 501)",
            P25SubscriberIdentityFormatter.display(0xBEE00, 0x348, 9001, 501, "p25:bee00:348"));
        assertEquals("9001 (Working ID 501)",
            P25SubscriberIdentityFormatter.display(0xBEE00, 0x348, 9001, 501, null));
        assertEquals("BEE00.348.9001 (Working ID 501)",
            P25SubscriberIdentityFormatter.display(0xBEE00, 0x348, 9001, 501, "p25:bee00:349"));
        assertEquals("9001", P25SubscriberIdentityFormatter.display(0xBEE00, 0x348, 9001, 9001, null));
        assertEquals("9001", P25SubscriberIdentityFormatter.display(0xBEE00, 0x348, 9001, 0xFFFFFD, null));
        assertEquals("BEE00.348.9001", P25SubscriberIdentityFormatter.format(0xBEE00, 0x348, 9001),
            "Canonical storage stays complete regardless of display context");
    }

    @Test
    void qualifiedTalkgroupsRetainTheirExistingScalarAndForeignDecimalForms()
    {
        var mapped = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xBEE00, 0x348, 1001);
        var unmapped = APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xBEE00, 0x348, 1001);
        var identical = APCO25FullyQualifiedTalkgroupIdentifier.createTo(1001, 0xBEE00, 0x348, 1001);
        for(String servingKey: new String[]{null, "p25:bee00:348", "unavailable"})
        {
            assertEquals("77", P25SubscriberIdentityFormatter.display(mapped, servingKey));
            assertEquals("1001", P25SubscriberIdentityFormatter.display(unmapped, servingKey));
            assertEquals("1001", P25SubscriberIdentityFormatter.display(identical, servingKey));
        }
        for(String servingKey: new String[]{"p25:bee01:348", "p25:bee00:349"})
        {
            assertEquals("ISSI 77(781824.840.1001)", P25SubscriberIdentityFormatter.display(mapped, servingKey));
            assertEquals("ISSI 781824.840.1001", P25SubscriberIdentityFormatter.display(unmapped, servingKey));
            assertEquals("ISSI 781824.840.1001", P25SubscriberIdentityFormatter.display(identical, servingKey));
        }
        assertEquals("ISSI 77(781824.840.1001)", mapped.toString(),
            "Raw identifiers retain their complete decimal tuple for storage and matching");
    }

    @Test
    void patchGroupsAndRadioMembersShareTheDisplayRule()
    {
        var group = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xBEE00, 0x348, 1001);
        var patch = new PatchGroup(group);
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xBEE00, 0x348, 1002));
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(79, 0xBEE00, 0x348, 1003));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(501, 0xBEE00, 0x348, 9001));
        for(String servingKey: new String[]{null, "p25:bee00:348"})
        {
            assertEquals("P:77 [1002, 79] [9001 (Working ID 501)]",
                P25SubscriberIdentityFormatter.display(APCO25PatchGroup.create(patch), servingKey));
        }
        for(String servingKey: new String[]{"p25:bee01:348", "p25:bee00:349"})
        {
            assertEquals("P:ISSI 77(781824.840.1001) [ISSI 781824.840.1002, ISSI 79(781824.840.1003)] " +
                    "[BEE00.348.9001 (Working ID 501)]",
                P25SubscriberIdentityFormatter.display(APCO25PatchGroup.create(patch), servingKey));
        }
    }
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

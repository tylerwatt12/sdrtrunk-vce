package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import org.junit.jupiter.api.Test;

class BroadcastIdentifierFormatterTest
{
    @Test
    void equalExplicitGroupAddressesRetainTheLegacyForeignTitle()
    {
        var group = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(1001, 0xBEE00, 0x123, 1001);
        assertEquals("ISSI 781824.291.1001", BroadcastIdentifierFormatter.format(group, "p25:bee00:456"));
        assertEquals("1001", BroadcastIdentifierFormatter.format(group, "p25:bee00:123"));
    }

    private static final String HOME = RadioSystemKey.p25(0xBEE00, 0x123);
    private static final String FOREIGN = RadioSystemKey.p25(0xBEE00, 0x456);

    @Test
    void foreignRadioUsesThePriorDecimalStreamFormat()
    {
        assertEquals("ISSI 781824.291.1234567", BroadcastIdentifierFormatter.format(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 1234567), FOREIGN));
        assertEquals("ROAM 7654321(781824.291.1234567)", BroadcastIdentifierFormatter.format(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(7654321, 0xBEE00, 0x123, 1234567),
            FOREIGN));
        assertEquals("ISSI 781824.291.1234567", BroadcastIdentifierFormatter.format(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(1234567, 0xBEE00, 0x123, 1234567),
            FOREIGN));
    }

    @Test
    void matchingMissingAndInvalidSystemsCannotAddAPrefix()
    {
        var radio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(7654321, 0xBEE00, 0x123, 1234567);
        for(String key: new String[]{HOME, null, "p25:unknown", "dmr:tier3:small:1"})
        {
            assertEquals("7654321", BroadcastIdentifierFormatter.format(radio, key));
            assertEquals("1234567", BroadcastIdentifierFormatter.format(
                APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 1234567), key));
        }
        assertEquals("1234567", BroadcastIdentifierFormatter.format(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0x100000, 0x123, 1234567), FOREIGN));
    }

    @Test
    void formattingUsesAcceptedIdentityInsteadOfRestoringDecoderProvenance()
    {
        var inferred = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(7654321, 0xBEE00, 0x123,
            1234567, Role.FROM, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT);
        assertEquals("ROAM 7654321(781824.291.1234567)", BroadcastIdentifierFormatter.format(inferred, FOREIGN));
        var retained = APCO25FullyQualifiedRadioIdentifier.withRecordingIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 1234567),
            APCO25RadioIdentifier.createFrom(7654321));
        assertEquals("ISSI 781824.291.1234567", BroadcastIdentifierFormatter.format(retained, FOREIGN));
    }

    @Test
    void talkgroupsAndEveryPatchMemberFollowTheSameForeignSystemRule()
    {
        var group = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(2002, 0xBEE00, 0x123, 1001);
        var radio = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(7654321, 0xBEE00, 0x123, 1234567);
        var patch = new PatchGroup(group);
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(3003));
        patch.addPatchedRadio(radio);
        assertEquals("P:ISSI 2002(781824.291.1001) [3003] [ROAM 7654321(781824.291.1234567)]",
            BroadcastIdentifierFormatter.format(APCO25PatchGroup.create(patch), FOREIGN));
        assertEquals("P:2002 [3003] [7654321]", BroadcastIdentifierFormatter.format(APCO25PatchGroup.create(patch), HOME));
        assertEquals("P:2002 [3003] [7654321]", BroadcastIdentifierFormatter.format(APCO25PatchGroup.create(patch), null));
    }

    @Test
    void servingContextRequiresACompleteNetworkTupleAndRejectsConflictingCapturedEvidence()
    {
        var identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xBEE00));
        assertNull(BroadcastIdentifierFormatter.servingSystemKey(identifiers));
        identifiers.update(APCO25System.create(0x123));
        assertEquals(HOME, BroadcastIdentifierFormatter.servingSystemKey(identifiers));
        var site = new P25SiteIdentity(0xBEE00, 0x456, 1, 2);
        var source = new CallLegSource(DecoderType.P25_PHASE2, null, null, null, 0, site,
            TrunkedIdentityDomain.STANDARD, ChannelConfigurationPolicy.ChannelKind.TRUNKED, true, FOREIGN);
        assertNull(BroadcastIdentifierFormatter.servingSystemKey(source, identifiers));
        identifiers.update(APCO25System.create(0x456));
        assertEquals(FOREIGN, BroadcastIdentifierFormatter.servingSystemKey(source, identifiers));
        var conflicting = new CallLegSource(DecoderType.P25_PHASE2, null, null, null, 0, site,
            TrunkedIdentityDomain.STANDARD, ChannelConfigurationPolicy.ChannelKind.TRUNKED, true, HOME);
        assertNull(BroadcastIdentifierFormatter.servingSystemKey(conflicting, identifiers));
    }
}

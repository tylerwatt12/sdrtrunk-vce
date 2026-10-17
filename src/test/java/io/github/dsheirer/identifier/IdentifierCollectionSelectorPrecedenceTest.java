/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.audio.call.AudioActivityCallEvent;
import io.github.dsheirer.audio.call.CallAttribution;
import io.github.dsheirer.module.decode.dmr.identifier.P25Location;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentifierCollectionSelectorPrecedenceTest
{
    @Test
    void sourceRadioWinsBeforeTelephoneAndOtherFromFactsWithoutFilteredListCalls()
    {
        var alias = text(Form.TALKER_ALIAS, Role.FROM, "Alias");
        var telephone = text(Form.TELEPHONE_NUMBER, Role.TO, "123");
        var radio = APCO25RadioIdentifier.createFrom(101);
        assertSame(radio, collection(alias, telephone, radio).getFromIdentifier());
    }

    @Test
    void sourceTelephonePrecedenceAndLegitimateFromFallbackStayUnchanged()
    {
        var first = text(Form.USER_STATUS, Role.FROM, "Status");
        var second = text(Form.TALKER_ALIAS, Role.FROM, "Alias");
        var telephone = text(Form.TELEPHONE_NUMBER, Role.FROM, "123");
        assertSame(telephone, collection(first, second, telephone).getFromIdentifier());
        assertSame(first, collection(first, second).getFromIdentifier());
        assertNull(collection(second).getFromIdentifier(), "An OTA name alone is never a source identity");
        assertNull(collection(text(Form.SYSTEM, Role.ANY, "System")).getFromIdentifier());
    }

    @Test
    void auxiliaryFactsAndOtherRoleTelephoneNumbersCannotIdentifyTheSource()
    {
        var group = new APCO25Talkgroup(91, Role.FROM);
        for(Identifier<?> auxiliary: List.of(text(Form.TONE, Role.FROM, "DTMF 1"),
            text(Form.TALKER_ALIAS, Role.FROM, "Name"),
            text(Form.TELEPHONE_NUMBER, Role.TO, "123"),
            text(Form.TELEPHONE_NUMBER, Role.ANY, "123")))
        {
            assertNull(collection(auxiliary).getFromIdentifier());
            assertSame(group, collection(auxiliary, group).getFromIdentifier());
        }
    }

    @Test
    void acceptedSourceProjectionPreservesLocationWithoutTreatingItAsACaller()
    {
        var location = P25Location.createFrom(1.25, 2.5);
        assertNull(collection(location).getFromIdentifier());
        var cached = APCO25RadioIdentifier.createFrom(999);
        var context = collection(cached, location);
        var unbound = AudioActivityCallEvent.projectAcceptedSource(null, context);
        assertNull(unbound.getFromIdentifier());
        assertTrue(unbound.hasIdentifier(location));
        CallAttribution attribution = new CallAttribution("accepted");
        var canonical = io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier
            .createFrom(0, 0xBEE00, 0x123, 101);
        attribution.updateSource(canonical);
        attribution.complete(); //The exact selected update remains valid if completion races its projection.
        var projected = AudioActivityCallEvent.projectAcceptedSource(
            new AudioActivityCallEvent(attribution.token(), 1, attribution), context);
        assertSame(canonical, projected.getFromIdentifier());
        assertTrue(projected.hasIdentifier(location));
        assertSame(cached, context.getFromIdentifier());
    }

    @Test
    void sourceRequiresAValidUserEntityButCanonicalIdentityDoesNotRequireALocalAddress()
    {
        var invalidRadio = APCO25RadioIdentifier.createFrom(0);
        var configuration = new Identifier<>("Not a caller", IdentifierClass.CONFIGURATION, Form.RADIO, Role.FROM)
        {
            @Override public Protocol getProtocol() { return Protocol.APCO25; }
        };
        var radio = APCO25RadioIdentifier.createFrom(101);
        var canonical = io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier
            .createFrom(0, 0xBEE00, 0x123, 101);
        assertNull(collection(invalidRadio, configuration).getFromIdentifier());
        assertSame(radio, collection(invalidRadio, configuration, radio).getFromIdentifier());
        assertSame(canonical, collection(invalidRadio, canonical).getFromIdentifier());
    }

    @Test
    void destinationPatchThenTalkgroupThenRadioPrecedenceStaysUnchanged()
    {
        var generic = text(Form.TELEPHONE_NUMBER, Role.TO, "123");
        var radio = APCO25RadioIdentifier.createTo(101);
        var talkgroup = APCO25Talkgroup.create(91);
        var patch = APCO25PatchGroup.create(92);
        assertSame(patch, collection(generic, radio, talkgroup, patch).getToIdentifier());
        assertSame(talkgroup, collection(generic, radio, talkgroup).getToIdentifier());
        assertSame(radio, collection(generic, radio).getToIdentifier());
    }

    @Test
    void destinationFallbackIgnoresEncryptionAndOtherRolesWithoutFilteredListCalls()
    {
        var encryption = text(Form.ENCRYPTION_KEY, Role.TO, "Key");
        var source = text(Form.TELEPHONE_NUMBER, Role.FROM, "Source");
        var firstTarget = text(Form.TELEPHONE_NUMBER, Role.TO, "Target");
        var laterTarget = text(Form.USER_STATUS, Role.TO, "Later");
        assertSame(firstTarget, collection(encryption, source, firstTarget, laterTarget).getToIdentifier());
        assertNull(collection(encryption, source).getToIdentifier());
    }

    private static IdentifierCollection collection(Identifier<?>... identifiers)
    {
        return new IdentifierCollection(List.of(identifiers))
        {
            @Override
            public List<Identifier> getIdentifiers(Form form)
            {
                throw new AssertionError("Receiver selectors must not build a filtered identifier list");
            }

            @Override
            public List<Identifier> getIdentifiers(Role role)
            {
                throw new AssertionError("Receiver selectors must not build a filtered identifier list");
            }
        };
    }

    private static Identifier<String> text(Form form, Role role, String value)
    {
        return new Identifier<>(value, IdentifierClass.USER, form, role)
        {
            @Override
            public Protocol getProtocol()
            {
                return Protocol.APCO25;
            }
        };
    }
}

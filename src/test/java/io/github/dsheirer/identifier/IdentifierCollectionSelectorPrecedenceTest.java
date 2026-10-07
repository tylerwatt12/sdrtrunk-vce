/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier;

import static org.junit.jupiter.api.Assertions.*;

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
    void legacyTelephonePrecedenceAndFirstFromFallbackStayUnchanged()
    {
        var first = text(Form.USER_STATUS, Role.FROM, "Status");
        var second = text(Form.TALKER_ALIAS, Role.FROM, "Alias");
        var telephone = text(Form.TELEPHONE_NUMBER, Role.TO, "123");
        assertSame(telephone, collection(first, second, telephone).getFromIdentifier());
        assertSame(first, collection(first, second).getFromIdentifier());
        assertNull(collection(text(Form.SYSTEM, Role.ANY, "System")).getFromIdentifier());
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

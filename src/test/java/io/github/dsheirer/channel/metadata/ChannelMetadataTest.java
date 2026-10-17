/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateNotification.Operation;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import org.junit.jupiter.api.Test;

class ChannelMetadataTest
{
    @Test
    void exposesOnlyAnAliasOwnedByTheCurrentRadio()
    {
        ChannelMetadata metadata = new ChannelMetadata(new AliasModel());
        var first = APCO25RadioIdentifier.createFrom(1201);
        var next = APCO25RadioIdentifier.createFrom(1202);
        var alias = P25TalkerAliasIdentifier.create("Portable 12").bindTo(first);
        metadata.receive(new IdentifierUpdateNotification(first, Operation.ADD, 0));
        metadata.receive(new IdentifierUpdateNotification(P25TalkerAliasIdentifier.create("Loose name"), Operation.ADD, 0));
        assertNull(metadata.getTalkerAliasIdentifier());
        metadata.receive(new IdentifierUpdateNotification(alias, Operation.ADD, 0));
        assertSame(alias, metadata.getTalkerAliasIdentifier());
        metadata.receive(new IdentifierUpdateNotification(next, Operation.ADD, 0));
        assertNull(metadata.getTalkerAliasIdentifier());
        assertFalse(metadata.hasTalkerAliasIdentifier());
    }

    @Test
    void clearsTalkerAliasWhenIdentifierIsRemoved()
    {
        ChannelMetadata metadata = new ChannelMetadata(new AliasModel());
        var source = APCO25RadioIdentifier.createFrom(1201);
        var talkerAlias = P25TalkerAliasIdentifier.create("Portable 12").bindTo(source);
        metadata.receive(new IdentifierUpdateNotification(source, Operation.ADD, 0));

        metadata.receive(new IdentifierUpdateNotification(talkerAlias, Operation.ADD, 0));
        assertTrue(metadata.hasTalkerAliasIdentifier());
        assertSame(talkerAlias, metadata.getTalkerAliasIdentifier());

        metadata.receive(new IdentifierUpdateNotification(talkerAlias, Operation.REMOVE, 0));
        assertFalse(metadata.hasTalkerAliasIdentifier());
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateNotification.Operation;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import org.junit.jupiter.api.Test;

class ChannelMetadataTest
{
    @Test
    void clearsTalkerAliasWhenIdentifierIsRemoved()
    {
        ChannelMetadata metadata = new ChannelMetadata(new AliasModel());
        P25TalkerAliasIdentifier talkerAlias = P25TalkerAliasIdentifier.create("Portable 12");

        metadata.receive(new IdentifierUpdateNotification(talkerAlias, Operation.ADD, 0));
        assertTrue(metadata.hasTalkerAliasIdentifier());
        assertSame(talkerAlias, metadata.getTalkerAliasIdentifier());

        metadata.receive(new IdentifierUpdateNotification(talkerAlias, Operation.REMOVE, 0));
        assertFalse(metadata.hasTalkerAliasIdentifier());
    }
}

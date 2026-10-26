/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasListConfigurationIdentityTest
{
    @Test
    void capturedRecordingKeepsItsListWhenTheOldNameIsReused()
    {
        AliasListDefinition original = definition(1, "Original");
        Alias firstAlias = alias(10, original, "First");
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(original), List.of(firstAlias));
        AliasList cached = model.getAliasList(original);
        AudioRecording queued = new AudioRecording(Path.of("synthetic.mp3"), List.of(),
            new IdentifierCollection(List.of(AliasListConfigurationIdentifier.create(original.getId(),
                original.getName()))), 0, 100);

        AliasListDefinition renamed = definition(1, "Renamed");
        AliasListDefinition reusedName = definition(2, "Original");
        Alias secondAlias = alias(20, reusedName, "Second");
        model.publishCommittedConfiguration(List.of(renamed, reusedName), List.of(firstAlias, secondAlias),
            Set.of(secondAlias.getId()), true);

        AliasList captured = model.getAliasList(queued.getIdentifierCollection());
        assertSame(cached, captured);
        assertEquals("Renamed", captured.getName());
        assertSame(firstAlias, captured.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertEquals("Original", queued.getIdentifierCollection().getAliasListConfiguration().getValue(),
            "The captured display label stays historical while lookups use the ID");
        assertSame(secondAlias, model.getAliasList(AliasListConfigurationIdentifier.create("Original"))
            .getAliases(APCO25Talkgroup.create(100)).getFirst(), "Legacy name-only identifiers remain supported");
    }

    @Test
    void removedListDoesNotFallBackToAnotherListWithTheSameName()
    {
        AliasListDefinition reusedName = definition(2, "Original");
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(reusedName), List.of(alias(20, reusedName, "Second")));

        assertTrue(model.getAliasList(AliasListConfigurationIdentifier.create(1, "Original")).aliases().isEmpty());
    }

    @Test
    void identifierEqualityIncludesBothStableIdentityAndDisplayName()
    {
        var first = AliasListConfigurationIdentifier.create(1, "Original");
        assertEquals(first, AliasListConfigurationIdentifier.create(1, "Original"));
        assertEquals(first.hashCode(), AliasListConfigurationIdentifier.create(1, "Original").hashCode());
        assertNotEquals(first, AliasListConfigurationIdentifier.create(2, "Original"));
        assertNotEquals(first, AliasListConfigurationIdentifier.create(1, "Renamed"));
        assertNotEquals(first, AliasListConfigurationIdentifier.create("Original"));
    }

    private static AliasListDefinition definition(long id, String name)
    {
        AliasListDefinition definition = new AliasListDefinition(name, AliasListFamily.P25);
        definition.setId(id);
        return definition;
    }

    private static Alias alias(long id, AliasListDefinition definition, String name)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        return alias;
    }
}

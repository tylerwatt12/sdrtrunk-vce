/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards durable-ID replacement/removal without changing the schema-v4 Alias shape. */
class AliasModelIdentityTest
{
    @Test
    void detachedReplacementAndRemovalCannotCreateDuplicateDurableRows()
    {
        AliasModel model = new AliasModel();
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(7L);
        model.setAliasListDefinitions(List.of(definition));
        Alias original = alias("Original", definition, 1L);
        model.addAlias(original);

        Alias replacement = AliasFactory.copyOf(original);
        replacement.setId(original.getId());
        replacement.setName("Replacement");
        model.addAlias(replacement);

        assertEquals(1, model.getAliases().size());
        assertSame(replacement, model.getAlias(1L));

        Alias detachedRemoval = AliasFactory.copyOf(replacement);
        detachedRemoval.setId(replacement.getId());
        model.removeAliases(List.of(detachedRemoval));
        assertTrue(model.getAliases().isEmpty());
    }

    private static Alias alias(String name, AliasListDefinition definition, long id)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 100));
        return alias;
    }
}

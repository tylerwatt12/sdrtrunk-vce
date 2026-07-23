/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

class MutableAudioCallBuilderRecordingMetadataTest
{
    @Test
    void freezesAliasNamesAndRecordDecisionWhenIdentifiersJoinTheCall()
    {
        Alias destinationAlias = new Alias("Fire Dispatch");
        destinationAlias.addAliasID(new Talkgroup(Protocol.APCO25, 56138));
        destinationAlias.setRecordable(true);
        Alias sourceAlias = new Alias("Engine 12");
        sourceAlias.addAliasID(new Radio(Protocol.APCO25, 120012));
        AliasList aliasList = new AliasList("Primary");
        aliasList.addAlias(destinationAlias);
        aliasList.addAlias(sourceAlias);
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList, 1);

        builder.addIdentifiers(List.of(APCO25Talkgroup.create(56138),
            APCO25RadioIdentifier.createFrom(120012)));

        destinationAlias.setName("Renamed Dispatch");
        destinationAlias.setRecordable(false);
        sourceAlias.setName("Renamed Radio");
        AudioCallRecordingMetadata metadata = builder.getRecordingMetadata();

        assertEquals("Fire Dispatch", metadata.destinationAlias());
        assertEquals("Engine 12", metadata.sourceAlias());
        assertTrue(metadata.destinationTalkgroupRecordEnabled());
        assertTrue(builder.isRecordAudio());
        assertSame(metadata, builder.getRecordingMetadata());
    }
}

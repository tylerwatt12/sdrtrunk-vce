/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * ****************************************************************************
 */
package io.github.dsheirer.record.wave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AudioMetadataUtilsTest
{
    @Test
    void canonicalSubscriberIsPrimaryAndWorkingIdIsExplicitlyLabeled()
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x348, 2_115_288),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(777, 0xABCDE, 0x456, 9_001)));
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));

        Map<AudioMetadata, String> metadata = AudioMetadataUtils.getMetadataMap(identifiers, aliasList);

        assertEquals("BEE00.348.2115288 (Working ID 501)", metadata.get(AudioMetadata.ARTIST_NAME));
        assertEquals("ABCDE.456.9001 (Working ID 777)", metadata.get(AudioMetadata.TRACK_TITLE));
    }

    @Test
    void explicitEqualWorkingAndSubscriberNumbersRemainSeparatelyLabeled()
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                2_115_288, 0xBEE00, 0x348, 2_115_288)));
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));

        Map<AudioMetadata, String> metadata = AudioMetadataUtils.getMetadataMap(identifiers, aliasList);

        assertEquals("BEE00.348.2115288 (Working ID 2115288)", metadata.get(AudioMetadata.ARTIST_NAME));
    }

    @Test
    void identityOnlyFullyQualifiedRadioDoesNotInventWorkingId()
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFrom(2_115_288, 0xBEE00, 0x348, 2_115_288)));
        AliasList aliasList = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));

        String artist = AudioMetadataUtils.getMetadataMap(identifiers, aliasList).get(AudioMetadata.ARTIST_NAME);

        assertEquals("BEE00.348.2115288", artist);
        assertFalse(artist.contains("Working ID"));
    }
}

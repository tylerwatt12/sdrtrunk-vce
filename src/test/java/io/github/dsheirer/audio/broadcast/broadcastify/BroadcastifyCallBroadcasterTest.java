/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.broadcastify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import static io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures.recording;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class BroadcastifyCallBroadcasterTest
{
    @Test
    void canonicalRadioNameCannotBeAttachedToAnUnknownNumericSource()
    {
        var sample = TalkerAliasOwnershipFixtures.canonicalWithoutNumericSource();
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.owned())));
    }

    @Test
    void returnsOverTheAirTalkerAlias()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(),
                BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.owned())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.other(), sample.owned())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.raw())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.owned())));
        }
        assertEquals("srcId_alias", FormField.RADIO_ID_ALIAS.getHeader());
    }

    @Test
    void omitsMissingOrInvalidTalkerAlias()
    {
        AudioRecording missing = new AudioRecording(null, List.of(), new IdentifierCollection(), 0, 0);
        AudioRecording invalid = new AudioRecording(null, List.of(),
            new IdentifierCollection(List.of(DmrTalkerAliasIdentifier.create(""))), 0, 0);

        assertNull(BroadcastifyCallBroadcaster.getFromAlias(missing));
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(invalid));
    }

    @Test
    void sourceChangeOmitsPreviousRadioAliasFromUploadMetadata()
    {
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120001));
        builder.addIdentifier(P25TalkerAliasIdentifier.create("UNIT ONE")
            .bindTo(APCO25RadioIdentifier.createFrom(120001)));
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120002));
        AudioRecording recording = new AudioRecording(null, List.of(),
            builder.getIdentifierCollection(), 0, 0);

        assertEquals(120002, recording.getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording));
    }
}

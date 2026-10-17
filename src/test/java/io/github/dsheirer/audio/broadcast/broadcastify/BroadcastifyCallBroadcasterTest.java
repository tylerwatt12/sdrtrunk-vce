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
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class BroadcastifyCallBroadcasterTest
{
    @Test
    void returnsOverTheAirTalkerAlias()
    {
        AudioRecording dmr = new AudioRecording(null, List.of(),
            new IdentifierCollection(List.of(DmrTalkerAliasIdentifier.create("ENGINE 4"))), 0, 0);
        AudioRecording p25 = new AudioRecording(null, List.of(),
            new IdentifierCollection(List.of(P25TalkerAliasIdentifier.create("DISPATCH 2"))), 0, 0);
        AudioRecording nxdn = new AudioRecording(null, List.of(),
            new IdentifierCollection(List.of(new NXDNTalkerAliasIdentifier("UNIT 12"))), 0, 0);

        assertEquals("ENGINE 4", BroadcastifyCallBroadcaster.getFromAlias(dmr));
        assertEquals("DISPATCH 2", BroadcastifyCallBroadcaster.getFromAlias(p25));
        assertEquals("UNIT 12", BroadcastifyCallBroadcaster.getFromAlias(nxdn));
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
        builder.addIdentifier(P25TalkerAliasIdentifier.create("UNIT ONE"));
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120002));
        AudioRecording recording = new AudioRecording(null, List.of(),
            builder.getIdentifierCollection(), 0, 0);

        assertEquals(120002, recording.getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording));
    }
}

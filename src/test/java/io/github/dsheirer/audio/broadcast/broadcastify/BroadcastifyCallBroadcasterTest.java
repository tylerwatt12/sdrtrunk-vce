/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.broadcastify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
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
}

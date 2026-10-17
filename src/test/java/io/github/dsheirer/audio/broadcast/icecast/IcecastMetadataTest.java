package io.github.dsheirer.audio.broadcast.icecast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcecastMetadataTest
{
    @Test
    void unattachedTalkerNamesCannotBecomeTheSourceTitle()
    {
        var aliases = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return AliasList.empty("test"); }
        };
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals("TO:UNKNOWN FROM:101", IcecastMetadata.getTitle(
                new IdentifierCollection(List.of(sample.source(), sample.raw())), aliases));
            assertEquals("TO:UNKNOWN FROM:UNKNOWN", IcecastMetadata.getTitle(
                new IdentifierCollection(List.of(sample.raw())), aliases));
        }
    }

    @Test
    void icecastAndBroadcastifyFeedTitlesRestoreDecimalIdsWithForeignOnlyQualification()
    {
        var identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(1001),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(7654321, 0xBEE00, 0x123, 1234567)));
        var aliases = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return AliasList.empty("test"); }
        };
        assertEquals("TO:1001 FROM:ROAM 7654321(781824.291.1234567)",
            IcecastMetadata.getTitle(identifiers, aliases, RadioSystemKey.p25(0xBEE00, 0x456)));
        assertEquals("TO:1001 FROM:7654321",
            IcecastMetadata.getTitle(identifiers, aliases, RadioSystemKey.p25(0xBEE00, 0x123)));
        assertEquals("TO:1001 FROM:7654321", IcecastMetadata.getTitle(identifiers, aliases));
    }
}

package io.github.dsheirer.audio.broadcast.shoutcast.v1;

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

class ShoutcastV1BroadcastMetadataUpdaterTest
{
    @Test
    void nxdnZeroKeepsLegacySongGrammarAndStreamAsOverride()
    {
        AliasList aliases = new AliasList(new io.github.dsheirer.alias.AliasListDefinition("NXDN",
            io.github.dsheirer.alias.AliasListFamily.NXDN));
        var alias = new io.github.dsheirer.alias.Alias("Dispatch");
        alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(
            io.github.dsheirer.protocol.Protocol.NXDN, 0));
        aliases.addAlias(alias);
        var model = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return aliases; }
        };
        var identifiers = io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture
            .decodeGroupCall(aliases, 19, 0).audio().snapshot().identifierCollection();
        var updater = new ShoutcastV1BroadcastMetadataUpdater(new ShoutcastV1Configuration(), model);
        assertEquals("TO:0x0000 NULL GROUP Dispatch FROM:19", updater.getSong(identifiers, null));
        alias.setStreamTalkgroupAlias(new io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup(900));
        assertEquals("TO:900 Dispatch FROM:19", updater.getSong(identifiers, null));
        assertEquals(0, identifiers.getToIdentifier().getValue());
    }

    @Test
    void unattachedTalkerNamesCannotBecomeTheSongSource()
    {
        var aliases = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return AliasList.empty("test"); }
        };
        var updater = new ShoutcastV1BroadcastMetadataUpdater(new ShoutcastV1Configuration(), aliases);
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals("TO:UNKNOWN FROM:101", updater.getSong(
                new IdentifierCollection(List.of(sample.source(), sample.raw())), null));
            assertEquals("TO:UNKNOWN FROM:UNKNOWN", updater.getSong(
                new IdentifierCollection(List.of(sample.raw())), null));
        }
    }

    @Test
    void auxiliaryAndDestinationFactsCannotBecomeTheSongSource()
    {
        var aliases = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return AliasList.empty("test"); }
        };
        var updater = new ShoutcastV1BroadcastMetadataUpdater(new ShoutcastV1Configuration(), aliases);
        var tone = io.github.dsheirer.identifier.tone.P25ToneIdentifier.create(
            new io.github.dsheirer.identifier.tone.ToneSequence(List.of(
                new io.github.dsheirer.identifier.tone.Tone(io.github.dsheirer.identifier.tone.AmbeTone.DTMF_1, 5))));
        for(var auxiliary: List.of(tone,
            io.github.dsheirer.module.decode.p25.identifier.telephone.APCO25TelephoneNumber.createTo("123")))
        {
            org.junit.jupiter.api.Assertions.assertTrue(updater.getSong(
                new IdentifierCollection(List.of(auxiliary)), null).endsWith("FROM:UNKNOWN"));
        }
    }

    @Test
    void songMetadataUsesTheSameCompatibilityRuleWithoutSendingANetworkRequest()
    {
        var identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(1001),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(7654321, 0xBEE00, 0x123, 1234567)));
        var aliases = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection collection) { return AliasList.empty("test"); }
        };
        var updater = new ShoutcastV1BroadcastMetadataUpdater(new ShoutcastV1Configuration(), aliases);
        assertEquals("TO:1001 FROM:ROAM 7654321(781824.291.1234567)",
            updater.getSong(identifiers, RadioSystemKey.p25(0xBEE00, 0x456)));
        assertEquals("TO:1001 FROM:7654321", updater.getSong(identifiers, RadioSystemKey.p25(0xBEE00, 0x123)));
        assertEquals("TO:1001 FROM:7654321", updater.getSong(identifiers, null));
    }
}

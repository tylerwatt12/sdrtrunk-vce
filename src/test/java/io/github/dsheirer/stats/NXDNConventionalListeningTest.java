package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NXDNConventionalListeningTest
{
    @Test
    void decodedGroupZeroUsesTheUnmatchedRouteUntilItHasAnExactAlias()
    {
        AliasListDefinition definition = new AliasListDefinition("NXDN", AliasListFamily.NXDN);
        definition.setId(10);
        AliasList aliases = new AliasList(definition);
        ScanList defaultList = new ScanList(1, 0, "Default", null, true, true);
        ScanList matched = new ScanList(2, 1, "Dispatch", null, true, false);
        ScanList unmatched = new ScanList(3, 2, "Unmatched", null, true, false);
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(List.of(defaultList, matched, unmatched),
            Map.of(101L, Set.of(matched.getId())), Map.of(10L, Set.of(unmatched.getId()))));
        CompletedCallScanListMatcher matcher = new CompletedCallScanListMatcher(model);

        var before = NXDNConventionalCallFixture.decodeGroupCall(aliases, 101, 0).audio();
        assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED,
            before.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(unmatched.getId()), matcher.match(before));

        Alias alias = new Alias("Dispatch");
        alias.setId(101);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.NXDN, 0));
        alias.setRecordable(true);
        aliases.addAlias(alias);

        var after = NXDNConventionalCallFixture.decodeGroupCall(aliases, 101, 0).audio();
        assertEquals(AliasList.TalkgroupMatchStatus.MATCHED,
            after.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(matched.getId()), matcher.match(after));
        assertEquals("Dispatch", after.snapshot().recordingMetadata().destinationAlias());
        assertTrue(after.resolvedPolicy().recordAudio());
        assertEquals(Set.of(unmatched.getId()), matcher.match(before), "Completed output policy stays frozen");
    }
}

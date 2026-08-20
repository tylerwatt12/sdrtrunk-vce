/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.am.AMTalkgroup;
import io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletedCallScanListMatcherTest
{
    private static final ScanList DEFAULT = new ScanList(1, 0, "Default", null, true, true);
    private static final ScanList EXACT = new ScanList(2, 1, "Exact", null, true, false);
    private static final ScanList RANGE = new ScanList(3, 2, "Range", null, true, false);
    private static final ScanList PATCH = new ScanList(4, 3, "Patch", null, true, false);
    private static final ScanList HIDDEN = new ScanList(5, 4, "Hidden", null, false, false);
    private static final ScanList UNKNOWN = new ScanList(6, 5, "Unknown", null, true, false);

    @Test
    void resolvesExactRangeAndPatchAliasesAndExcludesUnpublishedLists()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(10);
        Alias exact = alias(101, "Exact", new Talkgroup(Protocol.APCO25, 100));
        Alias range = alias(102, "Range", new TalkgroupRange(Protocol.APCO25, 200, 299));
        Alias patchMember = alias(103, "Patch member", new Talkgroup(Protocol.APCO25, 350));
        AliasList aliasList = new AliasList(definition);
        aliasList.addAliases(List.of(exact, range, patchMember));
        CompletedCallScanListMatcher matcher = matcher(
            Map.of(101L, Set.of(EXACT.getId(), HIDDEN.getId()), 102L, Set.of(RANGE.getId()),
                103L, Set.of(PATCH.getId())));

        assertEquals(Set.of(EXACT.getId()), matcher.match(call(aliasList, APCO25Talkgroup.create(100))));
        assertEquals(Set.of(RANGE.getId()), matcher.match(call(aliasList, APCO25Talkgroup.create(250))));

        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(500));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(350));
        assertEquals(Set.of(PATCH.getId()),
            matcher.match(call(aliasList, APCO25PatchGroup.create(patchGroup))));
    }

    @Test
    void unionsDuplicateContextsAndDeduplicatesOverlappingScanLists()
    {
        CompletedCallScanListMatcher matcher = matcher(
            Map.of(101L, Set.of(EXACT.getId(), RANGE.getId()),
                102L, Set.of(RANGE.getId(), PATCH.getId())));
        ResolvedCallPolicy policy = new ResolvedCallPolicy(false, false, Set.of(), List.of(
            context(10, Set.of(101L)), context(20, Set.of(102L)), context(10, Set.of(101L))));

        assertEquals(Set.of(EXACT.getId(), RANGE.getId(), PATCH.getId()),
            matcher.match(call(policy)));
    }

    @Test
    void routesOnlyGenuinelyUnmatchedDestinationTalkgroups()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        definition.setId(10);
        Alias exact = alias(101, "Exact", new Talkgroup(Protocol.APCO25, 100));
        Alias range = alias(102, "Range", new TalkgroupRange(Protocol.APCO25, 200, 299));
        Alias patchMember = alias(103, "Patch member", new Talkgroup(Protocol.APCO25, 350));
        Alias radio = alias(104, "Console", new Radio(Protocol.APCO25, 9001));
        AliasList aliasList = new AliasList(definition);
        aliasList.addAliases(List.of(exact, range, patchMember, radio));
        CompletedCallScanListMatcher matcher = matcher(
            Map.of(101L, Set.of(EXACT.getId()), 102L, Set.of(RANGE.getId()),
                103L, Set.of(PATCH.getId())),
            Map.of(10L, Set.of(UNKNOWN.getId(), HIDDEN.getId())));

        CompletedAudioCall unknown = call(aliasList, APCO25Talkgroup.create(999));
        assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED,
            unknown.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(UNKNOWN.getId()), matcher.match(unknown));
        assertEquals(Set.of(EXACT.getId()), matcher.match(call(aliasList, APCO25Talkgroup.create(100))));
        assertEquals(Set.of(RANGE.getId()), matcher.match(call(aliasList, APCO25Talkgroup.create(250))));
        assertEquals(Set.of(), matcher.match(call(aliasList, APCO25Talkgroup.createAny(999))));
        CompletedAudioCall radioOnly = call(aliasList, APCO25RadioIdentifier.createFrom(9001));
        assertEquals(AliasList.TalkgroupMatchStatus.NOT_APPLICABLE,
            radioOnly.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(), matcher.match(radioOnly));

        CompletedAudioCall unknownWithKnownSource = call(aliasList, APCO25Talkgroup.create(999),
            APCO25RadioIdentifier.createFrom(9001));
        assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED,
            unknownWithKnownSource.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(UNKNOWN.getId()), matcher.match(unknownWithKnownSource));

        PatchGroup unknownPatch = new PatchGroup(APCO25Talkgroup.create(500));
        unknownPatch.addPatchedRadio(APCO25RadioIdentifier.createFrom(9001));
        assertEquals(Set.of(UNKNOWN.getId()),
            matcher.match(call(aliasList, APCO25PatchGroup.create(unknownPatch))));

        PatchGroup knownPatch = new PatchGroup(APCO25Talkgroup.create(500));
        knownPatch.addPatchedTalkgroup(APCO25Talkgroup.create(350));
        knownPatch.addPatchedRadio(APCO25RadioIdentifier.createFrom(9001));
        assertEquals(Set.of(PATCH.getId()),
            matcher.match(call(aliasList, APCO25PatchGroup.create(knownPatch))));
    }

    @Test
    void unionsAndDeduplicatesNormalAndUnmatchedDuplicateContexts()
    {
        CompletedCallScanListMatcher matcher = matcher(
            Map.of(101L, Set.of(EXACT.getId(), UNKNOWN.getId())),
            Map.of(20L, Set.of(UNKNOWN.getId(), RANGE.getId())));
        ResolvedCallPolicy policy = new ResolvedCallPolicy(false, false, Set.of(), List.of(
            context(10, Set.of(101L)),
            context(20, Set.of(), AliasList.TalkgroupMatchStatus.UNMATCHED),
            context(20, Set.of(), AliasList.TalkgroupMatchStatus.UNMATCHED)));

        assertEquals(Set.of(EXACT.getId(), UNKNOWN.getId(), RANGE.getId()), matcher.match(call(policy)));
    }

    @Test
    void routesKnownAndGloballyUnmatchedAnalogTalkgroupsThroughNbfmFamilyScanLists()
    {
        AliasListDefinition definition = new AliasListDefinition("Airband", AliasListFamily.NBFM);
        definition.setId(10);
        Alias tower = alias(101, "Tower", new Talkgroup(Protocol.AM, 1));
        Alias repeater = alias(102, "Repeater", new Talkgroup(Protocol.NBFM, 3));
        AliasList aliasList = new AliasList(definition);
        aliasList.addAliases(List.of(tower, repeater));
        CompletedCallScanListMatcher matcher = matcher(
            Map.of(101L, Set.of(EXACT.getId(), RANGE.getId()),
                102L, Set.of(RANGE.getId(), PATCH.getId())),
            Map.of(10L, Set.of(RANGE.getId(), UNKNOWN.getId())));

        CompletedAudioCall known = call(aliasList, new AMTalkgroup(1));
        assertEquals(AliasList.TalkgroupMatchStatus.MATCHED,
            known.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(EXACT.getId(), RANGE.getId()), matcher.match(known),
            "Overlapping AM memberships must still yield one delivery per scan list");

        CompletedAudioCall unmatched = call(aliasList, new AMTalkgroup(2));
        assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED,
            unmatched.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(RANGE.getId(), UNKNOWN.getId()), matcher.match(unmatched),
            "Unknown AM talkgroups must use the NBFM-family Alias List's global route");

        CompletedAudioCall knownNbfm = call(aliasList, new NBFMTalkgroup(3));
        assertEquals(AliasList.TalkgroupMatchStatus.MATCHED,
            knownNbfm.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(RANGE.getId(), PATCH.getId()), matcher.match(knownNbfm));

        CompletedAudioCall unmatchedNbfm = call(aliasList, new NBFMTalkgroup(4));
        assertEquals(AliasList.TalkgroupMatchStatus.UNMATCHED,
            unmatchedNbfm.resolvedPolicy().matchContexts().getFirst().talkgroupMatchStatus());
        assertEquals(Set.of(RANGE.getId(), UNKNOWN.getId()), matcher.match(unmatchedNbfm));
    }

    private static Alias alias(long id, String name, io.github.dsheirer.alias.id.AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static CompletedCallScanListMatcher matcher(Map<Long,Set<Long>> aliases)
    {
        return matcher(aliases, Map.of());
    }

    private static CompletedCallScanListMatcher matcher(Map<Long,Set<Long>> aliases,
                                                        Map<Long,Set<Long>> unmatchedAliasLists)
    {
        ScanListModel model = new ScanListModel();
        model.replaceConfiguration(new ScanListConfiguration(
            List.of(DEFAULT, EXACT, RANGE, PATCH, HIDDEN, UNKNOWN), aliases, unmatchedAliasLists));
        return new CompletedCallScanListMatcher(model);
    }

    private static CompletedAudioCall call(AliasList aliasList, Identifier<?>... suppliedIdentifiers)
    {
        long now = System.currentTimeMillis();
        List<Identifier> identifiers = new ArrayList<>();

        for(Identifier<?> identifier: suppliedIdentifiers)
        {
            identifiers.add(identifier);
        }

        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1, 1, 0), null, aliasList,
            new IdentifierCollection(identifiers), Set.of(), now, now + 100, 1, 1, now, now + 100,
            false, true, false, false, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static CompletedAudioCall call(ResolvedCallPolicy policy)
    {
        long now = System.currentTimeMillis();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(2, 1, 0), null, null,
            new IdentifierCollection(List.of(APCO25Talkgroup.create(100))), Set.of(), now, now + 100, 1, 1,
            now, now + 100, false, true, false, false, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]), policy);
    }

    private static ResolvedCallPolicy.MatchContext context(long aliasListId, Set<Long> aliasIds)
    {
        return context(aliasListId, aliasIds, AliasList.TalkgroupMatchStatus.NOT_APPLICABLE);
    }

    private static ResolvedCallPolicy.MatchContext context(long aliasListId, Set<Long> aliasIds,
                                                           AliasList.TalkgroupMatchStatus matchStatus)
    {
        return new ResolvedCallPolicy.MatchContext(null, aliasListId, "List " + aliasListId, "System",
            List.of(), aliasIds, matchStatus, false, false, Set.of());
    }
}

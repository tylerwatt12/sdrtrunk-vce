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

package io.github.dsheirer.database.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasListDefinitionResolverTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void splitsSharedLegacyNameAndPreservesEveryCompatibleAliasAndRoute()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel p25 = channel("Metro P25", "Shared", new DecodeConfigP25Phase1());
        Channel dmr = channel("Metro DMR", "Shared", new DecodeConfigDMR());
        state.setChannels(List.of(p25, dmr));

        Alias p25Source = alias("Dispatch", "Shared", new Talkgroup(Protocol.APCO25, 101));
        Alias dmrSource = alias("Dispatch", "Shared", new Radio(Protocol.DMR, 202));
        Alias sharedStatus = alias("Emergency", "Shared", unitStatus(3));
        sharedStatus.setRecordable(true);
        sharedStatus.addBroadcastChannel("Calls");
        state.setAliases(List.of(p25Source, dmrSource, sharedStatus));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(2, state.getAliasListDefinitions().size());
        assertEquals(4, state.getAliases().size());
        assertEquals("Shared [P25]", p25.getAliasListName());
        assertEquals("Shared [DMR]", dmr.getAliasListName());

        AliasListDefinition p25List = definition(state, p25.getAliasListName());
        AliasListDefinition dmrList = definition(state, dmr.getAliasListName());
        assertEquals(AliasListFamily.P25, p25List.getFamily());
        assertEquals(AliasListFamily.DMR, dmrList.getFamily());
        assertTrue(AliasMatchRegistry.allowed(p25List).stream()
            .noneMatch(descriptor -> descriptor.matches(new Talkgroup(Protocol.DMR, 1))));

        Alias p25Alias = aliases(state, "Dispatch").stream()
            .filter(alias -> alias.getMatchIdentifier() instanceof Talkgroup).findFirst().orElseThrow();
        assertEquals(p25List.getName(), p25Alias.getAliasListName());
        assertEquals(101, ((Talkgroup)p25Alias.getMatchIdentifier()).getValue());

        List<Alias> statusAliases = aliases(state, "Emergency");
        assertEquals(2, statusAliases.size());
        assertNotSame(statusAliases.get(0), statusAliases.get(1));
        assertEquals(List.of("Shared [P25]", "Shared [DMR]"),
            statusAliases.stream().map(Alias::getAliasListName).toList());
        assertTrue(statusAliases.stream().allMatch(Alias::isRecordable));
        assertTrue(statusAliases.stream().allMatch(alias -> alias.hasBroadcastChannel("Calls")));
        assertTrue(statusAliases.stream().allMatch(alias -> alias.getMatchIdentifier() instanceof UnitStatusID));
    }

    @Test
    void sameFamilySystemsShareLegacyListWithoutCreatingCopies()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel north = channel("North", "Regional", new DecodeConfigP25Phase1());
        Channel south = channel("South", "Regional", new DecodeConfigP25Phase1());
        state.setChannels(List.of(north, south));

        Alias alias = new Alias("Fire");
        alias.setId(42);
        alias.setAliasListName("Regional");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 303));
        alias.setRecordable(true);
        alias.addBroadcastChannel("Calls");
        state.setAliases(List.of(alias));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(1, state.getAliases().size());
        assertEquals(42, state.getAliases().get(0).getId());
        assertTrue(state.getAliases().getFirst().isRecordable());
        assertTrue(state.getAliases().getFirst().hasBroadcastChannel("Calls"));
        assertEquals("Regional", north.getAliasListName());
        assertEquals("Regional", south.getAliasListName());
    }

    @Test
    void genericMatcherUsesClaimedFamilyWithoutInventingAnotherProtocol()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel p25 = channel("Metro", "Regional", new DecodeConfigP25Phase1());
        state.setChannels(List.of(p25));
        state.setAliases(List.of(alias("Emergency", "Regional", unitStatus(4))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(AliasListFamily.P25, state.getAliasListDefinitions().getFirst().getFamily());
        assertEquals("Regional", p25.getAliasListName());
        assertEquals(1, state.getAliases().size());
    }

    @Test
    void ignoresAuxiliaryDecodersWhenResolvingListFamily()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel first = channel("Metro", "Shared", new DecodeConfigP25Phase1());
        Channel second = channel("Metro", "Shared", new DecodeConfigP25Phase1());
        first.getAuxDecodeConfiguration().addAuxDecoder(DecoderType.DCS);
        second.getAuxDecodeConfiguration().addAuxDecoder(DecoderType.MDC1200);
        state.setChannels(List.of(first, second));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(first.getAliasListName(), second.getAliasListName());
    }

    @Test
    void infersUnassignedListFamilyFromProtocolSpecificMatcher()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Alias alias = alias("Orphan", "Old List", new Talkgroup(Protocol.NXDN, 1));
        state.setAliases(List.of(alias));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliasListDefinitions().size());
        assertEquals(AliasListFamily.NXDN, state.getAliasListDefinitions().getFirst().getFamily());
        assertEquals(1, state.getAliases().size());
        assertEquals("Orphan", state.getAliases().getFirst().getName());
    }

    @Test
    void splitsNamedListInferredFromMixedMatchersWithoutChannels()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setAliases(List.of(
            alias("P25", "Mixed", new Talkgroup(Protocol.APCO25, 1)),
            alias("DMR", "Mixed", new Talkgroup(Protocol.DMR, 1))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(List.of("Mixed [P25]", "Mixed [DMR]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
        assertEquals(2, state.getAliases().size());
        assertEquals(List.of("Mixed [P25]", "Mixed [DMR]"),
            state.getAliases().stream().map(Alias::getAliasListName).toList());
    }

    @Test
    void matcherFamilyAddsASecondDefinitionInsteadOfBeingDropped()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel p25 = channel("Metro P25", "Metro Aliases", new DecodeConfigP25Phase1());
        state.setChannels(List.of(p25));
        state.setAliases(List.of(
            alias("Dispatch", "Metro Aliases", new Talkgroup(Protocol.APCO25, 101)),
            alias("DMR Dispatch", "Metro Aliases", new Talkgroup(Protocol.DMR, 202))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(List.of("Metro Aliases [P25]", "Metro Aliases [DMR]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
        assertEquals("Metro Aliases [P25]", p25.getAliasListName());
        assertEquals(2, state.getAliases().size());
        assertEquals(List.of("Dispatch", "DMR Dispatch"),
            state.getAliases().stream().map(Alias::getName).toList());
    }

    @Test
    void recoversNullBlankAndNoAliasListAliasesIntoFamilyOwnedLists()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setAliases(List.of(
            alias("P25", null, new Talkgroup(Protocol.APCO25, 1)),
            alias("DMR", "   ", new Talkgroup(Protocol.DMR, 2)),
            alias("NXDN", "(No Alias List)", new Talkgroup(Protocol.NXDN, 3))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(List.of("Imported Unassigned [P25]", "Imported Unassigned [DMR]",
                "Imported Unassigned [NXDN]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
        assertEquals(List.of("Imported Unassigned [P25]", "Imported Unassigned [DMR]",
                "Imported Unassigned [NXDN]"),
            state.getAliases().stream().map(Alias::getAliasListName).toList());
    }

    @Test
    void clonesAmbiguousUnassignedMatcherIntoEachCompatibleFamily()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Alias status = alias("Emergency", null, unitStatus(5));
        status.addBroadcastChannel("Calls");
        state.setAliases(List.of(status));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(List.of("Imported Unassigned [P25]", "Imported Unassigned [DMR]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
        assertEquals(2, state.getAliases().size());
        assertTrue(state.getAliases().stream().allMatch(alias -> alias.hasBroadcastChannel("Calls")));
    }

    @Test
    void preservesAmbiguousMatcherAlongsideIncompatibleSingletonMatcher()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Dcs dcs = new Dcs();
        dcs.setDCSCode(DCSCode.N023);
        state.setAliases(List.of(
            alias("DCS", "Mixed", dcs),
            alias("Unit Status", "Mixed", unitStatus(6))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(List.of(AliasListFamily.P25, AliasListFamily.NBFM),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getFamily).toList());
        assertEquals(2, state.getAliases().size());
        assertEquals("Mixed [NBFM]", aliases(state, "DCS").getFirst().getAliasListName());
        assertEquals("Mixed [P25]", aliases(state, "Unit Status").getFirst().getAliasListName());
    }

    @Test
    void preservesSingleFamilyOriginalNameWhenGeneratedFamilyNameWouldCollide()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Channel p25 = channel("Metro P25", "Shared", new DecodeConfigP25Phase1());
        Channel dmr = channel("Metro DMR", "Shared", new DecodeConfigDMR());
        Channel original = channel("Other P25", "Shared [P25]", new DecodeConfigP25Phase1());
        state.setChannels(List.of(p25, dmr, original));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals("Shared [P25] 2", p25.getAliasListName());
        assertEquals("Shared [DMR]", dmr.getAliasListName());
        assertEquals("Shared [P25]", original.getAliasListName());
        assertEquals(List.of("Shared [P25] 2", "Shared [DMR]", "Shared [P25]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
    }

    @Test
    void stockXmlImportSplitsMixedListWithoutChangingSource() throws Exception
    {
        Path source = mTemporaryFolder.resolve("mixed-alias-lists.xml");
        Files.writeString(source, """
            <playlist version="4">
              <alias name="Dispatch" list="Shared" recordable="true">
                <id type="talkgroup" protocol="APCO25" value="101"/>
                <id type="talkgroup" protocol="DMR" value="202"/>
                <id type="broadcastChannel" channel="Calls"/>
              </alias>
              <alias name="Orphan" list="(No Alias List)">
                <id type="talkgroup" protocol="NXDN" value="303"/>
              </alias>
              <channel system="Metro" site="P25" name="P25 Control">
                <alias_list_name>Shared</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="851000000"/>
                <decode_configuration type="decodeConfigP25Phase1" modulation="CQPSK"
                    ignore_data_calls="false"/>
              </channel>
              <channel system="Metro" site="DMR" name="DMR Control">
                <alias_list_name>Shared</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="452000000"/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
              </channel>
            </playlist>
            """);
        byte[] before = Files.readAllBytes(source);

        LegacyConfigurationState state = LegacyXmlConfigurationImporter.readConfigurationState(source);

        assertArrayEquals(before, Files.readAllBytes(source));
        assertEquals(List.of("Shared [P25]", "Shared [DMR]", "Imported Unassigned [NXDN]"),
            state.getAliasListDefinitions().stream().map(AliasListDefinition::getName).toList());
        assertEquals(List.of("Shared [P25]", "Shared [DMR]"),
            state.getChannels().stream().map(Channel::getAliasListName).toList());
        assertEquals(3, state.getAliases().size());
        assertTrue(aliases(state, "Dispatch").stream()
            .allMatch(alias -> alias.hasBroadcastChannel("Calls")));
        assertEquals("Imported Unassigned [NXDN]", aliases(state, "Orphan").getFirst().getAliasListName());
    }

    @Test
    void convertsSingleFullRangeAliasIntoUnmatchedTalkgroupPolicy()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setChannels(List.of(channel("Metro P25", "Metro", new DecodeConfigP25Phase1())));
        Alias catchAll = alias("Unknown Talkgroups", "Metro",
            new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
        catchAll.setRecordable(true);
        catchAll.addBroadcastChannel("Calls");
        state.setAliases(List.of(catchAll));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertTrue(state.getAliases().isEmpty());
        UnmatchedTalkgroupPolicy policy = state.getAliasListDefinitions().getFirst()
            .getUnmatchedTalkgroupPolicy();
        assertTrue(policy.isRecordEnabled());
        assertEquals(List.of("Calls"), policy.getStreamDestinationNames());
    }

    @Test
    void convertsDmrAndNxdnFullRangeAliases()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        Alias dmr = alias("Unknown DMR", "DMR List", new TalkgroupRange(Protocol.DMR, 1, 0xFFFFFF));
        dmr.setRecordable(true);
        Alias nxdn = alias("Unknown NXDN", "NXDN List", new TalkgroupRange(Protocol.NXDN, 1, 0xFFFF));
        state.setAliases(List.of(dmr, nxdn));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertTrue(state.getAliases().isEmpty());
        assertTrue(definition(state, "DMR List").getUnmatchedTalkgroupPolicy().isRecordEnabled());
        assertEquals(UnmatchedTalkgroupPolicy.DEFAULT,
            definition(state, "NXDN List").getUnmatchedTalkgroupPolicy());
    }

    @Test
    void preservesOrdinaryRangeAlias()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setChannels(List.of(channel("Metro P25", "Metro", new DecodeConfigP25Phase1())));
        state.setAliases(List.of(alias("Operations", "Metro",
            new TalkgroupRange(Protocol.APCO25, 100, 199))));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliases().size());
        assertEquals(UnmatchedTalkgroupPolicy.DEFAULT,
            state.getAliasListDefinitions().getFirst().getUnmatchedTalkgroupPolicy());
    }

    @Test
    void preservesFullRangeAliasWithFixedStreamAsIdentity()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setChannels(List.of(channel("Metro P25", "Metro", new DecodeConfigP25Phase1())));
        Alias catchAll = alias("Unknown Talkgroups", "Metro",
            new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
        catchAll.setStreamTalkgroupAlias(new StreamAsTalkgroup(123));
        state.setAliases(List.of(catchAll));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliases().size());
        assertEquals(123, state.getAliases().getFirst().getStreamTalkgroupAlias().getValue());
        assertEquals(UnmatchedTalkgroupPolicy.DEFAULT,
            state.getAliasListDefinitions().getFirst().getUnmatchedTalkgroupPolicy());
    }

    @Test
    void preservesStyledFullRangeAliasForManualReview()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setChannels(List.of(channel("Metro P25", "Metro", new DecodeConfigP25Phase1())));
        Alias catchAll = alias("Styled Unknown Talkgroups", "Metro",
            new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
        catchAll.setDescription("Keep this administrator note");
        catchAll.setGroup("Legacy Rules");
        catchAll.setColor(0x123456);
        catchAll.setIconName("Question");
        state.setAliases(List.of(catchAll));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(1, state.getAliases().size());
        Alias preserved = state.getAliases().getFirst();
        assertEquals("Keep this administrator note", preserved.getDescription());
        assertEquals("Legacy Rules", preserved.getGroup());
        assertEquals(0x123456, preserved.getColor());
        assertEquals("Question", preserved.getIconName());
        assertEquals(UnmatchedTalkgroupPolicy.DEFAULT,
            state.getAliasListDefinitions().getFirst().getUnmatchedTalkgroupPolicy());
    }

    @Test
    void preservesMultipleCatchAllAliasesRatherThanGuessing()
    {
        LegacyConfigurationState state = new LegacyConfigurationState();
        state.setChannels(List.of(channel("Metro P25", "Metro", new DecodeConfigP25Phase1())));
        Alias first = alias("Unknown One", "Metro", new TalkgroupRange(Protocol.APCO25, 0, 0xFFFF));
        first.setRecordable(true);
        Alias second = alias("Unknown Two", "Metro", new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
        state.setAliases(List.of(first, second));

        AliasListDefinitionResolver.normalizeLegacyState(state);

        assertEquals(2, state.getAliases().size());
        assertEquals(UnmatchedTalkgroupPolicy.DEFAULT,
            state.getAliasListDefinitions().getFirst().getUnmatchedTalkgroupPolicy());
        assertTrue(state.getAliases().stream().anyMatch(Alias::isRecordable));
    }

    private static Alias alias(String name, String aliasList, AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(aliasList);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static UnitStatusID unitStatus(int value)
    {
        UnitStatusID status = new UnitStatusID();
        status.setStatus(value);
        return status;
    }

    private static Channel channel(String system, String aliasList, Object decodeConfiguration)
    {
        Channel channel = new Channel(system + " Site");
        channel.setSystem(system);
        channel.setAliasListName(aliasList);

        if(decodeConfiguration instanceof DecodeConfigP25Phase1 p25)
        {
            channel.setDecodeConfiguration(p25);
        }
        else if(decodeConfiguration instanceof DecodeConfigDMR dmr)
        {
            channel.setDecodeConfiguration(dmr);
        }

        return channel;
    }

    private static AliasListDefinition definition(LegacyConfigurationState state, String name)
    {
        return state.getAliasListDefinitions().stream()
            .filter(definition -> name.equals(definition.getName())).findFirst().orElseThrow();
    }

    private static List<Alias> aliases(LegacyConfigurationState state, String name)
    {
        return state.getAliases().stream().filter(alias -> name.equals(alias.getName())).toList();
    }
}

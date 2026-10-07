/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKey;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelActivityCallFactsTest
{
    @Test
    void lateSourceAndTargetFillTheCurrentCallAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            f.observe(1, 1_000, 1_000, false);
            assertNull(f.row(1).getSource());
            assertNull(f.row(1).getTarget());
            f.observe(1, 1_000, 1_100, false, f.radio(101), f.group(91), f.talker("Portable 101"));
            assertEquals(101, f.row(1).getSource().getValue());
            assertEquals(91, f.row(1).getTarget().getValue());
            assertEquals("Portable 101", f.row(1).getTalkerAlias().getValue());
            assertEquals("Unit 101", f.row(1).getSourceAliases().getFirst().getName());
            assertEquals("Dispatch", f.row(1).getTargetAliases().getFirst().getName());
        });
    }

    @Test
    void changedTalkerOnTheSameGroupClearsOldNameAndEncryptionAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            f.observe(1, 1_000, 1_000, true, f.radio(101), f.group(91), f.talker("Portable 101"), f.encryption());
            assertEquals(State.ENCRYPTED, f.row(1).getState());
            assertNotNull(f.row(1).getEncryptionDetails());
            f.observe(1, 1_000, 1_100, false, f.radio(202), f.group(91), f.talker("Portable 202"));
            assertEquals(202, f.row(1).getSource().getValue());
            assertEquals("Portable 202", f.row(1).getTalkerAlias().getValue());
            assertEquals("Unit 202", f.row(1).getSourceAliases().getFirst().getName());
            assertNull(f.row(1).getEncryptionDetails());
            assertEquals(State.CALL, f.row(1).getState());
        });
    }

    @Test
    void currentConfiguredNamesAndOtaNameRefreshAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            f.observe(1, 1_000, 1_000, false, f.radio(101), f.group(91), f.talker("Portable"));
            f.aliases.replaceCommittedConfiguration(List.of(f.definition), List.of(
                f.alias("Renamed unit", 11, new Radio(f.variant.protocol, 101)),
                f.alias("Renamed dispatch", 12, new Talkgroup(f.variant.protocol, 91))));
            f.observe(1, 1_000, 1_100, false, f.radio(101), f.group(91), f.talker("Updated portable"));
            assertEquals("Renamed unit", f.row(1).getSourceAliases().getFirst().getName());
            assertEquals("Renamed dispatch", f.row(1).getTargetAliases().getFirst().getName());
            assertEquals("Updated portable", f.row(1).getTalkerAlias().getValue());
        });
    }

    @Test
    void incompleteRepeatsRetainKnownParticipantsAndStickyEncryptionAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            Identifier<?> source = f.radio(101);
            Identifier<?> target = f.group(91);
            Identifier<?> talker = f.talker("Portable 101");
            f.observe(1, 1_000, 1_000, true, source, target, talker, f.encryption());
            String encryption = f.row(1).getEncryptionDetails();
            f.observe(1, 0, 1_100, false);
            assertSame(source, f.row(1).getSource());
            assertSame(target, f.row(1).getTarget());
            assertSame(talker, f.row(1).getTalkerAlias());
            assertEquals("Unit 101", f.row(1).getSourceAliases().getFirst().getName());
            assertEquals("Dispatch", f.row(1).getTargetAliases().getFirst().getName());
            assertEquals(encryption, f.row(1).getEncryptionDetails());
            assertEquals(State.ENCRYPTED, f.row(1).getState());
        });
    }

    @Test
    void newCallFromTheSameRadioDoesNotInheritOldCallDetailsAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            f.observe(1, 1_000, 1_000, true, f.radio(101), f.group(91), f.talker("Old portable"), f.encryption());
            f.observe(1, 1_200, 1_200, false, f.radio(101), f.group(91));
            assertEquals(101, f.row(1).getSource().getValue());
            assertEquals(91, f.row(1).getTarget().getValue());
            assertNull(f.row(1).getTalkerAlias());
            assertNull(f.row(1).getEncryptionDetails());
            assertEquals(State.CALL, f.row(1).getState());
        });
    }

    @Test
    void staleCallOrObservationCannotReplaceCurrentParticipantsAcrossProtocols() throws Exception
    {
        eachProtocol(f -> {
            f.observe(1, 1_000, 1_000, true, f.radio(101), f.group(91), f.encryption());
            f.observe(1, 2_000, 2_100, false, f.radio(202), f.group(91), f.talker("Current portable"));
            f.observe(1, 1_000, 2_200, true, f.radio(101), f.group(92), f.talker("Old call"), f.encryption());
            f.observe(1, 2_000, 2_050, true, f.radio(303), f.group(93), f.talker("Old observation"), f.encryption());
            f.observe(1, 0, 2_050, true, f.radio(404), f.group(94), f.encryption());
            assertEquals(202, f.row(1).getSource().getValue());
            assertEquals(91, f.row(1).getTarget().getValue());
            assertEquals("Current portable", f.row(1).getTalkerAlias().getValue());
            assertNull(f.row(1).getEncryptionDetails());
            assertEquals(State.CALL, f.row(1).getState());
        });
    }

    @Test
    void simultaneousTdmaSlotsKeepIndependentParticipantsAndOrdering() throws Exception
    {
        for(Variant variant: List.of(Variant.P25_PHASE2, Variant.DMR))
        {
            try(Fixture f = new Fixture(variant))
            {
                f.observe(1, 1_000, 1_000, true, f.radio(101), f.group(91), f.talker("First slot"), f.encryption());
                f.observe(2, 1_000, 1_000, false, f.radio(202), f.group(92), f.talker("Second slot"));
                f.observe(1, 2_000, 2_000, false, f.radio(303), f.group(93));
                f.observe(1, 1_000, 2_100, true, f.radio(101), f.group(91), f.encryption());
                assertEquals(303, f.row(1).getSource().getValue());
                assertEquals(93, f.row(1).getTarget().getValue());
                assertNull(f.row(1).getEncryptionDetails());
                assertEquals(202, f.row(2).getSource().getValue());
                assertEquals(92, f.row(2).getTarget().getValue());
                assertEquals("Second slot", f.row(2).getTalkerAlias().getValue());
            }
        }
    }

    @Test
    void homeQualifiedPromotionRetainsTheCallButConflictingExplicitIdentitiesStartANewCall() throws Exception
    {
        for(Variant variant: List.of(Variant.P25_PHASE1, Variant.P25_PHASE2))
        {
            try(Fixture f = new Fixture(variant))
            {
                f.observe(1, 1_000, 1_000, true, f.radio(101), f.group(91), f.talker("Home portable"), f.encryption());
                var home = APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x123, 101);
                f.observe(1, 1_000, 1_100, false, home, f.group(91));
                assertSame(home, f.row(1).getSource());
                assertEquals("Home portable", f.row(1).getTalkerAlias().getValue());
                assertNotNull(f.row(1).getEncryptionDetails());
                assertEquals(State.ENCRYPTED, f.row(1).getState());

                var first = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(500, 0xBEE00, 0x123, 101);
                f.observe(1, 2_000, 2_000, true, first, f.group(91), f.talker("First home"), f.encryption());
                var foreign = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(500, 0xBEE01, 0x124, 101);
                f.observe(1, 2_000, 2_100, false, foreign, f.group(91));
                assertSame(foreign, f.row(1).getSource(), "Equal working numbers must not merge conflicting homes");
                assertNull(f.row(1).getTalkerAlias());
                assertNull(f.row(1).getEncryptionDetails());
                assertEquals(State.CALL, f.row(1).getState());
            }
        }
    }

    @Test
    void abbreviatedRepeatsRetainQualifiedSourceTargetAndCanonicalAliasesUntilANewCall() throws Exception
    {
        for(Variant variant: List.of(Variant.P25_PHASE1, Variant.P25_PHASE2))
        {
            try(Fixture f = new Fixture(variant))
            {
                f.aliases.replaceCommittedConfiguration(List.of(f.definition), List.of(
                    f.alias("Local source", 11, new Radio(Protocol.APCO25, 101)),
                    f.alias("Local target", 12, new Radio(Protocol.APCO25, 202)),
                    f.alias("Roaming source", 13, new P25Subscriber(0xBEE01, 0x124, 900)),
                    f.alias("Roaming target", 14, new P25Subscriber(0xBEE01, 0x124, 901))));
                var source = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE01, 0x124, 900);
                var target = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(202, 0xBEE01, 0x124, 901);
                Identifier<?> talker = f.talker("Roaming portable");
                f.observe(1, 1_000, 1_000, true, source, target, talker, f.encryption());

                f.observe(1, 1_000, 1_100, false, f.radio(101), APCO25RadioIdentifier.createTo(202));
                assertSame(source, f.row(1).getSource());
                assertSame(target, f.row(1).getTarget());
                assertEquals("Roaming source", f.row(1).getSourceAliases().getFirst().getName());
                assertEquals("Roaming target", f.row(1).getTargetAliases().getFirst().getName());
                assertSame(talker, f.row(1).getTalkerAlias());
                assertNotNull(f.row(1).getEncryptionDetails());
                assertEquals(State.ENCRYPTED, f.row(1).getState());

                Identifier<?> nextSource = f.radio(101);
                Identifier<?> nextTarget = APCO25RadioIdentifier.createTo(202);
                f.observe(1, 1_200, 1_200, false, nextSource, nextTarget);
                assertSame(nextSource, f.row(1).getSource(), "A new call cannot inherit the old qualified source");
                assertSame(nextTarget, f.row(1).getTarget(), "A new call cannot inherit the old qualified target");
                assertEquals("Local source", f.row(1).getSourceAliases().getFirst().getName());
                assertEquals("Local target", f.row(1).getTargetAliases().getFirst().getName());
                assertNull(f.row(1).getTalkerAlias());
                assertNull(f.row(1).getEncryptionDetails());
                assertEquals(State.CALL, f.row(1).getState());
            }
        }
    }

    @Test
    void identityOnlyRepeatsRetainKnownWorkingAddressesButNewExplicitAddressesRemainUsable() throws Exception
    {
        for(Variant variant: List.of(Variant.P25_PHASE1, Variant.P25_PHASE2))
        {
            try(Fixture f = new Fixture(variant))
            {
                f.aliases.replaceCommittedConfiguration(List.of(f.definition), List.of(
                    f.alias("Roaming source", 11, new P25Subscriber(0xBEE01, 0x124, 900)),
                    f.alias("Roaming target", 12, new P25Subscriber(0xBEE01, 0x124, 901))));
                var source = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(101, 0xBEE01, 0x124, 900);
                var target = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(202, 0xBEE01, 0x124, 901);
                f.observe(1, 1_000, 1_000, false, source, target);

                f.observe(1, 1_000, 1_100, false,
                    APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE01, 0x124, 900),
                    APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xBEE01, 0x124, 901));
                assertSame(source, f.row(1).getSource());
                assertSame(target, f.row(1).getTarget());
                assertEquals(101, source.getWorkingAddress());
                assertEquals(202, target.getWorkingAddress());
                assertEquals("Roaming source", f.row(1).getSourceAliases().getFirst().getName());
                assertEquals("Roaming target", f.row(1).getTargetAliases().getFirst().getName());

                var latestSource = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(303, 0xBEE01, 0x124, 900);
                var latestTarget = APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(404, 0xBEE01, 0x124, 901);
                f.observe(1, 1_000, 1_200, false, latestSource, latestTarget);
                assertSame(latestSource, f.row(1).getSource(), "A new explicit working address must not be frozen out");
                assertSame(latestTarget, f.row(1).getTarget(), "A new explicit working address must not be frozen out");
            }
        }
    }

    @Test
    void canonicalHomeRepeatsUseTheParentsLearnedServingIdentityForAnAllocatedTrafficChild() throws Exception
    {
        for(Variant variant: List.of(Variant.P25_PHASE1, Variant.P25_PHASE2))
        {
            try(Fixture f = new Fixture(variant))
            {
                f.parent.setP25SiteIdentity(null);
                f.aliases.replaceCommittedConfiguration(List.of(f.definition), List.of(
                    f.alias("Canonical home source", 11, new P25Subscriber(0xBEE02, 0x321, 101)),
                    f.alias("Canonical home target", 12, new P25Subscriber(0xBEE02, 0x321, 202))));
                var site = new P25NetworkConfigurationSnapshot(
                    variant == Variant.P25_PHASE1 ? "P25_PHASE_1" : "P25_PHASE_2",
                    new P25NetworkConfigurationSnapshot.Network(0xBEE02, 0x321, 0x343, null),
                    new P25NetworkConfigurationSnapshot.CurrentSite(0x321, 0x343, 1, 1, null, true),
                    List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
                f.model.receiveSiteMetadata(new SiteMetadataEvent(f.parent, site, 900));
                f.idle();

                Channel traffic = new Channel("Allocated traffic", Channel.ChannelType.TRAFFIC);
                traffic.setDecodeConfiguration(f.parent.getDecodeConfiguration());
                traffic.setAliasListId(f.definition.getId());
                traffic.setAliasListName(f.definition.getName());
                assertNull(traffic.getP25SiteIdentity());
                var source = APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE02, 0x321, 101);
                var target = APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xBEE02, 0x321, 202);
                f.model.trunkedTrafficEvent(f.parent, traffic, f.channel(1), variant.tdma ? 1 : null,
                    new IdentifierCollection(List.of(source, target)), DecodeEventType.CALL_UNIT_TO_UNIT,
                    0, 1_000, 1_000);
                f.idle();

                f.model.trunkedTrafficEvent(f.parent, traffic, f.channel(1), variant.tdma ? 1 : null,
                    new IdentifierCollection(List.of(f.radio(101), APCO25RadioIdentifier.createTo(202))),
                    DecodeEventType.CALL_UNIT_TO_UNIT, 0, 1_000, 1_100);
                f.idle();
                assertSame(source, f.row(1).getSource());
                assertSame(target, f.row(1).getTarget());
                assertEquals("Canonical home source", f.row(1).getSourceAliases().getFirst().getName());
                assertEquals("Canonical home target", f.row(1).getTargetAliases().getFirst().getName());
            }
        }
    }

    private static void eachProtocol(Scenario scenario) throws Exception
    {
        for(Variant variant: Variant.values())
        {
            try(Fixture fixture = new Fixture(variant))
            {
                scenario.verify(fixture);
            }
        }
    }

    @FunctionalInterface
    private interface Scenario { void verify(Fixture fixture) throws Exception; }

    private enum Variant
    {
        P25_PHASE1(Protocol.APCO25, AliasListFamily.P25, false),
        P25_PHASE2(Protocol.APCO25, AliasListFamily.P25, true),
        DMR(Protocol.DMR, AliasListFamily.DMR, true),
        NXDN(Protocol.NXDN, AliasListFamily.NXDN, false);

        final Protocol protocol;
        final AliasListFamily family;
        final boolean tdma;
        Variant(Protocol protocol, AliasListFamily family, boolean tdma)
        {
            this.protocol = protocol; this.family = family; this.tdma = tdma;
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        static final long FREQUENCY = 451_012_500L;
        final Variant variant;
        final Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        final AliasModel aliases = new AliasModel();
        final AliasListDefinition definition;
        final ChannelActivityModel model;

        Fixture(Variant variant) throws Exception
        {
            this.variant = variant;
            definition = new AliasListDefinition("Test aliases", variant.family);
            definition.setId(1);
            aliases.replaceCommittedConfiguration(List.of(definition), List.of(
                alias("Unit 101", 11, new Radio(variant.protocol, 101)),
                alias("Dispatch", 12, new Talkgroup(variant.protocol, 91)),
                alias("Unit 202", 13, new Radio(variant.protocol, 202))));
            parent.setSystem("Test system"); parent.setSite("Test site");
            parent.setAliasListId(definition.getId()); parent.setAliasListName(definition.getName());
            SourceConfigTuner source = new SourceConfigTuner(); source.setFrequency(452_000_000L);
            parent.setSourceConfiguration(source);
            switch(variant)
            {
                case P25_PHASE1 -> parent.setDecodeConfiguration(new DecodeConfigP25Phase1());
                case P25_PHASE2 -> parent.setDecodeConfiguration(new DecodeConfigP25Phase2());
                case DMR -> {
                    DecodeConfigDMR config = new DecodeConfigDMR(); config.setChannelMode(DMRChannelMode.TRUNKED);
                    parent.setDecodeConfiguration(config);
                }
                case NXDN -> {
                    DecodeConfigNXDN config = new DecodeConfigNXDN(); config.setChannelMode(NXDNChannelMode.TRUNKED);
                    parent.setDecodeConfiguration(config);
                }
            }
            if(variant.family == AliasListFamily.P25)
            {
                parent.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x123, 1, 1));
            }
            model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {}));
            model.channelStarted(parent, List.of());
            idle();
        }

        Alias alias(String name, long id, AliasID matcher)
        {
            Alias alias = new Alias(name); alias.setId(id);
            alias.setAliasListDefinition(definition); alias.setMatchIdentifier(matcher);
            return alias;
        }

        Identifier<?> radio(int id)
        {
            return switch(variant)
            {
                case P25_PHASE1, P25_PHASE2 -> APCO25RadioIdentifier.createFrom(id);
                case DMR -> DMRRadio.createFrom(id);
                case NXDN -> NXDNRadioIdentifier.createFrom(id);
            };
        }

        Identifier<?> group(int id)
        {
            return switch(variant)
            {
                case P25_PHASE1, P25_PHASE2 -> APCO25Talkgroup.create(id);
                case DMR -> DMRTalkgroup.create(id);
                case NXDN -> NXDNTalkgroupIdentifier.createTo(id);
            };
        }

        Identifier<?> talker(String text)
        {
            return switch(variant)
            {
                case P25_PHASE1, P25_PHASE2 -> P25TalkerAliasIdentifier.create(text);
                case DMR -> DmrTalkerAliasIdentifier.create(text);
                case NXDN -> new NXDNTalkerAliasIdentifier(text);
            };
        }

        Identifier<?> encryption()
        {
            EncryptionKey key = switch(variant)
            {
                case P25_PHASE1, P25_PHASE2 -> APCO25EncryptionKey.create(0x84, 7);
                case NXDN -> NXDNEncryptionKey.create(3, 7);
                case DMR -> new EncryptionKey(1, 7) {
                    @Override public boolean isEncrypted() { return true; }
                };
            };
            return EncryptionKeyIdentifier.create(variant.protocol, key);
        }

        void observe(int slot, long callStart, long observedAt, boolean encrypted, Identifier<?>... facts)
            throws Exception
        {
            IdentifierCollection identifiers = new IdentifierCollection(Arrays.stream(facts)
                .filter(Objects::nonNull).map(identifier -> (Identifier)identifier).toList());
            model.trunkedTrafficEvent(parent, null, channel(slot), variant.tdma ? slot : null, identifiers,
                encrypted ? DecodeEventType.CALL_GROUP_ENCRYPTED : DecodeEventType.CALL_GROUP,
                0, callStart, observedAt);
            idle();
        }

        IChannelDescriptor channel(int slot)
        {
            if(variant == Variant.DMR)
            {
                return new DMRAbsoluteChannel(12, slot, FREQUENCY, 0);
            }
            if(variant == Variant.NXDN)
            {
                NXDNChannelLookup channel = new NXDNChannelLookup(12);
                channel.receive(null, Map.of(12, new ChannelFrequency(12, FREQUENCY, 0)));
                return channel;
            }
            APCO25Channel channel = APCO25Channel.create(0, variant.tdma ? slot - 1 : 0);
            channel.setFrequencyBand(new P25FrequencyBand(0, FREQUENCY, 0, 12_500, 12_500, variant.tdma ? 2 : 1));
            return channel;
        }

        ChannelActivityRow row(int slot)
        {
            return model.getTables().stream().flatMap(table -> table.getRows().stream())
                .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC && row.getFrequency() == FREQUENCY &&
                    Objects.equals(row.getTimeslot(), variant.tdma ? slot : null)).findFirst().orElseThrow();
        }

        void idle() throws Exception
        {
            assertTrue(model.awaitIdle(5, TimeUnit.SECONDS), variant + " worker did not become idle");
        }

        @Override public void close() { model.close(); }
    }
}

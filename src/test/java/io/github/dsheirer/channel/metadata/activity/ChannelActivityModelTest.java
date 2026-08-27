/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateNotification.Operation;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataSnapshot;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.telemetry.NXDNNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChannelActivityModelTest
{
    @Test
    void capturesCallDetailsBeforeMutableMetadataAdvancesToIdle() throws Exception
    {
        AliasListDefinition definition = p25AliasList("County", 1);
        var source = APCO25RadioIdentifier.createFrom(1_201);
        var target = APCO25Talkgroup.create(4_400);
        var talker = P25TalkerAliasIdentifier.create("Portable 12");
        Alias sourceAlias = alias("Car 12", 11, definition, new Radio(Protocol.APCO25, 1_201));
        Alias targetAlias = alias("Dispatch", 12, definition, new Talkgroup(Protocol.APCO25, 4_400));
        AliasModel aliasModel = new AliasModel();
        aliasModel.replaceCommittedConfiguration(List.of(definition), List.of(sourceAlias, targetAlias));
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, retainingPreference());
        Channel channel = trunkedChannel("Dispatch", "County", "North", new DecodeConfigP25Conventional(),
            155_730_000L);
        channel.setAliasListName(definition.getName());
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        metadata.receive(new IdentifierUpdateNotification(AliasListConfigurationIdentifier.create(definition.getName()),
            Operation.ADD, 1));
        run(model, () -> model.channelStarted(channel, List.of(metadata)));

        //Inject the complete call lifecycle from the activity worker's own listener callback.  The worker cannot
        //drain these observations until metadata has already advanced to IDLE/null, which makes this a deterministic
        //regression test for queueing the mutable ChannelMetadata object instead of an observation snapshot.
        AtomicBoolean injectLifecycle = new AtomicBoolean();
        model.addActivityListener(event -> {
            if(injectLifecycle.compareAndSet(true, false))
            {
                metadata.receive(new IdentifierUpdateNotification(source, Operation.ADD, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
                metadata.receive(new IdentifierUpdateNotification(target, Operation.ADD, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_TO);
                metadata.receive(new IdentifierUpdateNotification(talker, Operation.ADD, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
                metadata.receive(new IdentifierUpdateNotification(source, Operation.REMOVE, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
                metadata.receive(new IdentifierUpdateNotification(talker, Operation.REMOVE, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
                metadata.receive(new IdentifierUpdateNotification(target, Operation.REMOVE, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_TO);
                metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.IDLE, Operation.ADD, 1));
                model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
            }
        });

        injectLifecycle.set(true);
        metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
        model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS), "channel activity worker did not become idle");

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        assertEquals(State.IDLE, row.getState());
        assertSame(source, row.getSource());
        assertEquals(List.of(sourceAlias), row.getSourceAliases());
        assertSame(talker, row.getTalkerAlias());
        assertSame(target, row.getTarget());
        assertEquals(List.of(targetAlias), row.getTargetAliases());
        model.close();
    }

    @Test
    void enrichesMatchingRetainedIdleCallWithoutReplacingItsIdentity() throws Exception
    {
        AliasListDefinition definition = p25AliasList("County", 1);
        AliasModel aliasModel = new AliasModel();
        aliasModel.replaceCommittedConfiguration(List.of(definition), List.of());
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, retainingPreference());
        Channel channel = trunkedChannel("Dispatch", "County", "North", new DecodeConfigP25Conventional(),
            155_730_000L);
        channel.setAliasListName(definition.getName());
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        metadata.receive(new IdentifierUpdateNotification(AliasListConfigurationIdentifier.create(definition.getName()),
            Operation.ADD, 1));
        var source = APCO25RadioIdentifier.createFrom(1_201);
        var target = APCO25Talkgroup.create(4_400);

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
            metadata.receive(new IdentifierUpdateNotification(source, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
            metadata.receive(new IdentifierUpdateNotification(target, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_TO);
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });
        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.IDLE, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        assertTrue(row.getSourceAliases().isEmpty());
        Alias sourceAlias = alias("Car 12", 11, definition, new Radio(Protocol.APCO25, 1_201));
        aliasModel.replaceCommittedConfiguration(List.of(definition), List.of(sourceAlias));
        var talker = P25TalkerAliasIdentifier.create("Portable 12");
        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(talker, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
        });

        assertEquals(State.IDLE, row.getState());
        assertSame(source, row.getSource());
        assertEquals(List.of(sourceAlias), row.getSourceAliases());
        assertSame(talker, row.getTalkerAlias());
        assertEquals("Car 12 · TA: Portable 12", row.getSourceAliasDisplay());
        assertSame(target, row.getTarget());

        var unrelatedSource = APCO25RadioIdentifier.createFrom(9_999);
        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(unrelatedSource, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
        });
        assertSame(source, row.getSource());
        assertEquals(List.of(sourceAlias), row.getSourceAliases());
        assertSame(talker, row.getTalkerAlias());
        model.close();
    }

    @Test
    void retainsCoherentTrunkedCallDetailsWhenSiteStops() throws Exception
    {
        AliasListDefinition definition = p25AliasList("County", 1);
        var source = APCO25RadioIdentifier.createFrom(1_201);
        var target = APCO25Talkgroup.create(4_400);
        var talker = P25TalkerAliasIdentifier.create("Portable 12");
        Alias sourceAlias = alias("Car 12", 11, definition, new Radio(Protocol.APCO25, 1_201));
        Alias targetAlias = alias("Dispatch", 12, definition, new Talkgroup(Protocol.APCO25, 4_400));
        AliasModel aliasModel = new AliasModel();
        aliasModel.replaceCommittedConfiguration(List.of(definition), List.of(sourceAlias, targetAlias));
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, retainingPreference());
        Channel parent = trunkedChannel("Primary", "County", "North", new DecodeConfigP25Phase1(),
            851_012_500L);
        parent.setAliasListName(definition.getName());
        APCO25Channel traffic = APCO25Channel.create(0, 459);
        traffic.setFrequencyBand(new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(source, target, talker));

        run(model, () -> {
            model.channelStarted(parent, List.of());
            model.p25TrafficGrant(parent, null, traffic, identifiers, DecodeEventType.CALL_GROUP);
        });
        ChannelActivityRow row = model.getTables().get(1).getRows().stream()
            .filter(candidate -> candidate.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .findFirst().orElseThrow();
        assertEquals(State.CALL, row.getState());

        run(model, () -> model.channelStopped(parent));
        assertEquals(State.IDLE, row.getState());
        assertSame(source, row.getSource());
        assertEquals(List.of(sourceAlias), row.getSourceAliases());
        assertSame(talker, row.getTalkerAlias());
        assertSame(target, row.getTarget());
        assertEquals(List.of(targetAlias), row.getTargetAliases());
        model.close();
    }

    @Test
    void retainsOneCoherentCompletedCallAndClearsItForTheNextCall() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isRetainIdleCallDetails()
            {
                return true;
            }
        };
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, preference);
        Channel channel = trunkedChannel("Dispatch", "County", "North", new DecodeConfigAM(),
            155_730_000L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        var firstSource = APCO25RadioIdentifier.createFrom(1_201);
        var firstTarget = APCO25Talkgroup.create(4_400);
        var firstTalker = P25TalkerAliasIdentifier.create("Portable 12");
        Alias firstSourceAlias = new Alias("Car 12");
        Alias firstTargetAlias = new Alias("Dispatch");

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
            metadata.receive(new IdentifierUpdateNotification(firstSource, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(firstTarget, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(firstTalker, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        row.setSourceAliases(List.of(firstSourceAlias));
        row.setTargetAliases(List.of(firstTargetAlias));

        //Call teardown can remove identifiers before it publishes the IDLE state.  Those partial removals must not
        //erase individual columns from the retained completed-call snapshot.
        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(firstSource, Operation.REMOVE, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
            metadata.receive(new IdentifierUpdateNotification(firstTalker, Operation.REMOVE, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_FROM);
            metadata.receive(new IdentifierUpdateNotification(firstTarget, Operation.REMOVE, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.USER_TO);
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.IDLE, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        assertEquals(State.IDLE, row.getState());
        assertSame(firstSource, row.getSource());
        assertEquals(List.of(firstSourceAlias), row.getSourceAliases());
        assertSame(firstTalker, row.getTalkerAlias());
        assertSame(firstTarget, row.getTarget());
        assertEquals(List.of(firstTargetAlias), row.getTargetAliases());

        var secondSource = APCO25RadioIdentifier.createFrom(1_202);
        var secondTarget = APCO25Talkgroup.create(4_401);
        var secondTalker = P25TalkerAliasIdentifier.create("Portable 13");
        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(secondSource, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(secondTarget, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(secondTalker, Operation.ADD, 1));
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        assertEquals(State.CALL, row.getState());
        assertSame(secondSource, row.getSource());
        assertTrue(row.getSourceAliases().isEmpty());
        assertSame(secondTalker, row.getTalkerAlias());
        assertSame(secondTarget, row.getTarget());
        assertTrue(row.getTargetAliases().isEmpty());
        model.close();
    }

    @Test
    void clearsCompletedCallDetailsWhenIdleRetentionIsDisabled() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isRetainIdleCallDetails()
            {
                return false;
            }
        });
        Channel channel = trunkedChannel("Dispatch", "County", "North", new DecodeConfigAM(),
            155_730_000L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        run(model, () -> model.channelStarted(channel, List.of(metadata)));
        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        row.setState(State.CALL);
        row.setSource(APCO25RadioIdentifier.createFrom(1_201));
        row.setSourceAliases(List.of(new Alias("Car 12")));
        row.setTalkerAlias(P25TalkerAliasIdentifier.create("Portable 12"));
        row.setTarget(APCO25Talkgroup.create(4_400));
        row.setTargetAliases(List.of(new Alias("Dispatch")));

        run(model, () -> model.updated(metadata,
            io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE));

        assertEquals(State.IDLE, row.getState());
        assertNull(row.getSource());
        assertTrue(row.getSourceAliases().isEmpty());
        assertNull(row.getTalkerAlias());
        assertNull(row.getTarget());
        assertTrue(row.getTargetAliases().isEmpty());
        model.close();
    }

    @Test
    void amSnapshotsRemainUnderConventionalAcrossIdleCallIdleTransitions() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel,
            new NowPlayingPreference(type -> {}));
        Channel channel = trunkedChannel("Airport Ground", "County Airport", "Tower", new DecodeConfigAM(),
            121_900_000L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        List<ChannelActivitySnapshot> snapshots = new ArrayList<>();
        model.addActivityListener(event -> {
            if(event.operation() == ChannelActivityEvent.Operation.UPSERT &&
                "conventional".equals(event.snapshot().tableId()) && !event.snapshot().rows().isEmpty())
            {
                snapshots.add(event.snapshot());
            }
        });

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
        });

        ChannelActivitySnapshot idle = snapshots.getLast();
        assertEquals("IDLE", idle.rows().getFirst().status());

        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        ChannelActivitySnapshot call = snapshots.getLast();
        assertEquals("CALL", call.rows().getFirst().status());

        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.IDLE, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        ChannelActivitySnapshot returnedToIdle = snapshots.getLast();
        assertEquals("IDLE", returnedToIdle.rows().getFirst().status());
        assertEquals(idle.tableId(), call.tableId());
        assertEquals(call.tableId(), returnedToIdle.tableId());
        assertEquals(idle.rows().getFirst().key(), call.rows().getFirst().key());
        assertEquals(call.rows().getFirst().key(), returnedToIdle.rows().getFirst().key());
    }

    @Test
    void startsConfiguredTrunkedDmrInSystemsImmediately() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("2.2", "Bus", "Site 5", trunkedDmrConfig(), 139_781_250L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);

        run(model, () -> {
            model.channelStarted(parent, List.of(metadata));
        });

        assertEquals(2, model.getTables().size());
        assertTrue(model.getConventionalTable().getRows().isEmpty());

        DMRAbsoluteChannel traffic = new DMRAbsoluteChannel(838, 1, 139_968_750L, 0);
        run(model, () -> model.trunkedTrafficEvent(parent, null, traffic, 1,
            new IdentifierCollection(), DecodeEventType.CALL_GROUP, 139_781_250L));

        assertEquals(2, model.getTables().size());
        ChannelActivityTableState table = model.getTables().get(1);
        assertSame(parent, table.getOwnerChannel());
        assertEquals("DMR: Bus / Site 5 / 2.2", table.getTitle());
        assertEquals(2, table.getRows().size());

        ChannelActivityRow control = table.getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
            .findFirst().orElseThrow();
        ChannelActivityRow call = table.getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .findFirst().orElseThrow();
        assertEquals(139_781_250L, control.getFrequency());
        assertEquals(State.CONTROL, control.getState());
        assertEquals(139_968_750L, call.getFrequency());
        assertEquals(1, call.getTimeslot());
        assertEquals(State.CALL, call.getState());
    }

    @Test
    void usesCompactFallbackWhenEncryptedCallHasNoAlgorithmDetails() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("2.2", "Bus", "Site 5", trunkedDmrConfig(), 139_781_250L);
        DMRAbsoluteChannel traffic = new DMRAbsoluteChannel(838, 1, 139_968_750L, 0);

        run(model, () -> {
            model.trunkedTrafficEvent(parent, null, traffic, 1, new IdentifierCollection(),
                DecodeEventType.CALL_GROUP_ENCRYPTED, 139_781_250L);
        });

        ChannelActivityRow call = model.getTables().get(1).getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .findFirst().orElseThrow();
        assertEquals(State.ENCRYPTED, call.getState());
        assertEquals("ENC", call.getEncryptionDetails());
    }

    @Test
    void retainsCompactEncryptionFallbackAcrossMetadataUpdates() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel,
            new NowPlayingPreference(type -> {}));
        Channel channel = trunkedChannel("Repeater", "Local", "Hill", new DecodeConfigDMR(), 451_012_500L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.ENCRYPTED,
                Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        assertEquals(State.ENCRYPTED, row.getState());
        assertEquals("ENC", row.getEncryptionDetails());

        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });

        assertEquals(State.ENCRYPTED, row.getState());
        assertEquals("ENC", row.getEncryptionDetails());
    }

    @Test
    void configuredConventionalDmrCannotBePromotedByTrunkedTrafficEvent() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("Repeater", "Local", "Hill", new DecodeConfigDMR(), 451_012_500L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        DMRAbsoluteChannel traffic = new DMRAbsoluteChannel(12, 1, 452_012_500L, 0);

        run(model, () -> {
            model.channelStarted(parent, List.of(metadata));
            model.trunkedTrafficEvent(parent, null, traffic, 1, new IdentifierCollection(),
                DecodeEventType.CALL_GROUP, 451_012_500L);
        });

        assertEquals(1, model.getTables().size());
        assertEquals(1, model.getConventionalTable().getRows().size());
    }

    @Test
    void qualityAloneDoesNotPromoteConventionalDmrOrNxdn() throws Exception
    {
        for(DecodeConfiguration config: List.of(new DecodeConfigDMR(), new DecodeConfigNXDN()))
        {
            AliasModel aliasModel = new AliasModel();
            ChannelActivityModel model = new ChannelActivityModel(aliasModel,
                new NowPlayingPreference(type -> {}));
            Channel parent = trunkedChannel("Test", "System", "Site", config, 451_012_500L);
            ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);

            run(model, () -> {
                model.channelStarted(parent, List.of(metadata));
                model.receiveControlChannelQuality(quality(parent, 451_012_500L, 1_000L, true));
            });

            assertEquals(1, model.getTables().size());
            assertEquals(1, model.getConventionalTable().getRows().size());
        }
    }

    @Test
    void knownDmrAndNxdnMetadataPromotesQuietControlAndAcceptsQuality() throws Exception
    {
        List<Map.Entry<DecodeConfiguration,SiteMetadataSnapshot>> cases = List.of(
            Map.entry(trunkedDmrConfig(), new DMRNetworkConfigurationSnapshot(
                "DMR", "TIER_III", 10, 20, "Tier III Trunking", "SMALL", null, "Control",
                1, 2, List.of(), List.of())),
            Map.entry(trunkedDmrConfig(), new DMRNetworkConfigurationSnapshot(
                "DMR", "CAPACITY_PLUS", null, 20, "Motorola Capacity+", null, null, "Control",
                1, 2, List.of(), List.of())),
            Map.entry(new DecodeConfigNXDN(), new NXDNNetworkConfigurationSnapshot(
                "NXDN", "TYPE-D", 5, new NXDNNetworkConfigurationSnapshot.Location(
                    "REGIONAL", 8, 9, null), null, null, null, null, List.of(), List.of(), null,
                List.of(), List.of(), null, null, List.of())));

        for(Map.Entry<DecodeConfiguration,SiteMetadataSnapshot> testCase: cases)
        {
            AliasModel aliasModel = new AliasModel();
            ChannelActivityModel model = new ChannelActivityModel(aliasModel,
                new NowPlayingPreference(type -> {}));
            Channel parent = trunkedChannel("Quiet", "System", "Site", testCase.getKey(), 451_012_500L);
            ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);

            run(model, () -> {
                model.channelStarted(parent, List.of(metadata));
                model.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(
                    parent, testCase.getValue(), 1_000L));
                model.receiveControlChannelQuality(quality(parent, 451_012_500L, 2_000L, true));
            });

            assertTrue(model.getConventionalTable().getRows().isEmpty());
            assertEquals(2, model.getTables().size());
            ChannelActivityRow control = model.getTables().get(1).getRows().stream()
                .filter(row -> row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
                .findFirst().orElseThrow();
            assertTrue(model.getTables().get(1).isControlActive());
            assertEquals(-20.5, control.getSignalDbfs());
            assertEquals(97.5, control.getDecodeHealthPercent());
            assertTrue(model.getTables().get(1).getLatestSnapshot().identifiers().stream()
                .anyMatch(identifier -> identifier.label().equals("Site ID")));
        }
    }

    @Test
    void publishesLearnedP25SystemAndSiteIdentifiers() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("Primary", "County", "Downtown", new DecodeConfigP25Phase1(),
            851_012_500L);
        P25NetworkConfigurationSnapshot snapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x343, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x343, 2, 1, null, true),
            List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());

        run(model, () -> {
            model.channelStarted(parent, List.of());
            model.receiveSiteMetadata(new SiteMetadataEvent(parent, snapshot, 1_000L));
        });

        Map<String,String> identifiers = model.getTables().get(1).getLatestSnapshot().identifiers().stream()
            .collect(Collectors.toMap(ChannelActivitySnapshot.IdentifierField::label,
                ChannelActivitySnapshot.IdentifierField::value));
        assertEquals("BEE00", identifiers.get("WACN"));
        assertEquals("348", identifiers.get("System ID"));
        assertEquals("02", identifiers.get("RFSS"));
        assertEquals("01", identifiers.get("Site ID"));
        assertEquals("343", identifiers.get("NAC"));
    }

    @Test
    void unknownDmrMetadataDoesNotPromoteConventionalChannel() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelActivityModel model = new ChannelActivityModel(aliasModel,
            new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("Conventional", "System", "Site", new DecodeConfigDMR(),
            451_012_500L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        DMRNetworkConfigurationSnapshot unknown = new DMRNetworkConfigurationSnapshot(
            "DMR", null, 10, 20, null, null, null, null, 1, 2, List.of(), List.of());

        run(model, () -> {
            model.channelStarted(parent, List.of(metadata));
            model.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(parent, unknown, 1_000L));
        });

        assertEquals(1, model.getTables().size());
        assertEquals(1, model.getConventionalTable().getRows().size());
    }

    @Test
    void attachesDmrQualityAfterTrafficPromotesSite() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("2.2", "Bus", "Site 5", trunkedDmrConfig(), 139_781_250L);
        DMRAbsoluteChannel traffic = new DMRAbsoluteChannel(838, 1, 139_968_750L, 0);

        run(model, () -> {
            model.trunkedTrafficEvent(parent, null, traffic, 1, new IdentifierCollection(),
                DecodeEventType.CALL_GROUP, 139_781_250L);
            model.receiveControlChannelQuality(quality(parent, 139_781_250L, 2_000L, true));
        });

        ChannelActivityRow control = model.getTables().get(1).getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.CURRENT_CONTROL)
            .findFirst().orElseThrow();
        assertEquals(-20.5, control.getSignalDbfs());
        assertEquals(97.5, control.getDecodeHealthPercent());
        assertEquals(2_000L, control.getQualityObservedAt());
    }

    @Test
    void retainsSeparateDmrTrafficRowsForSameFrequencyTimeslots() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("2.2", "Bus", "Site 5", trunkedDmrConfig(), 139_781_250L);
        DMRAbsoluteChannel timeslotOne = new DMRAbsoluteChannel(838, 1, 139_968_750L, 0);
        DMRAbsoluteChannel timeslotTwo = new DMRAbsoluteChannel(838, 2, 139_968_750L, 0);

        run(model, () -> {
            model.trunkedTrafficEvent(parent, null, timeslotOne, 1, new IdentifierCollection(),
                DecodeEventType.CALL_GROUP, 139_781_250L);
            model.trunkedTrafficEvent(parent, null, timeslotTwo, 2, new IdentifierCollection(),
                DecodeEventType.CALL_GROUP, 139_781_250L);
        });

        List<ChannelActivityRow> trafficRows = model.getTables().get(1).getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .toList();
        assertEquals(2, trafficRows.size());
        assertEquals(Set.of(1, 2),
            trafficRows.stream().map(ChannelActivityRow::getTimeslot).collect(Collectors.toSet()));
        assertEquals(1, trafficRows.stream().map(ChannelActivityRow::getFrequency).distinct().count());
        assertNotSame(trafficRows.get(0), trafficRows.get(1));
    }

    @Test
    void createsNxdnFdmaTrafficRowWithGenericTitle() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(),
            new NowPlayingPreference(type -> {}));
        Channel parent = trunkedChannel("North", "County", "Simulcast", new DecodeConfigNXDN(), 451_012_500L);
        NXDNChannelLookup traffic = new NXDNChannelLookup(42);
        traffic.receive(null, Map.of(42, new ChannelFrequency(42, 452_012_500L, 0)));

        run(model, () -> {
            model.trunkedTrafficEvent(parent, null, traffic, 0, new IdentifierCollection(),
                DecodeEventType.DATA_CALL, 451_012_500L);
        });

        ChannelActivityTableState table = model.getTables().get(1);
        assertEquals("NXDN: County / Simulcast / North", table.getTitle());
        List<ChannelActivityRow> trafficRows = table.getRows().stream()
            .filter(row -> row.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .toList();
        assertEquals(1, trafficRows.size());
        assertEquals(452_012_500L, trafficRows.getFirst().getFrequency());
        assertEquals("42", trafficRows.getFirst().getLcn());
        assertNull(trafficRows.getFirst().getTimeslot());
        assertEquals(State.DATA, trafficRows.getFirst().getState());
    }

    @Test
    void exposesExistingTablesWhenRendererAttachesAfterChannelStart() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(856_137_500L);
        channel.setSourceConfiguration(source);

        run(model, () -> {
            model.channelStarted(channel, List.of());
        });

        List<ChannelActivityTableState> tables = model.getTables();
        assertEquals(2, tables.size());
        assertSame(model.getConventionalTable(), tables.getFirst());
        assertSame(channel, tables.get(1).getOwnerChannel());
    }

    @Test
    void publishesStoppedLifecycleSeparatelyFromTemporaryControlLoss() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = trunkedChannel("Test Site", "County", "Downtown", new DecodeConfigP25Phase1(),
            856_137_500L);

        run(model, () -> {
            model.channelStarted(channel, List.of());
            model.receiveControlChannelQuality(quality(channel, 856_137_500L, 1_000L, true));
        });
        ChannelActivitySnapshot running = model.getSnapshotSet().tables().get(1);
        assertTrue(running.channelRunning());
        assertTrue(running.controlActive());

        run(model, () -> model.channelStopped(channel));
        ChannelActivitySnapshot stopped = model.getSnapshotSet().tables().get(1);
        assertFalse(stopped.channelRunning());
        assertFalse(stopped.controlActive());

        run(model, () -> model.channelStarted(channel, List.of()));
        ChannelActivitySnapshot restartedWithoutControl = model.getSnapshotSet().tables().get(1);
        assertTrue(restartedWithoutControl.channelRunning());
        assertFalse(restartedWithoutControl.controlActive());
        model.close();
    }

    @Test
    void appliesAndClearsControlChannelQualityInJavaActivityTable() throws Exception
    {
        ChannelActivityModel model = new ChannelActivityModel(new AliasModel(), new NowPlayingPreference(type -> {}));
        Channel channel = new Channel("Test Site", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        channel.setRadresGuid("123e4567-e89b-12d3-a456-426614174000");
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(856_137_500L);
        channel.setSourceConfiguration(source);

        run(model, () -> {
            model.channelStarted(channel, List.of());
            model.receiveControlChannelQuality(new ControlChannelQualitySnapshot(channel, channel.getRadresGuid(),
                856_137_500L, 1_000L, true, -20.5, -21.0, -25.0, -18.0, 97.5,
                100, 1, 3, 0, 0, 999L));
        });

        ChannelActivityRow row = model.getTables().get(1).getRows().getFirst();
        assertEquals(-20.5, row.getSignalDbfs());
        assertEquals(97.5, row.getDecodeHealthPercent());
        assertEquals(1_000L, row.getQualityObservedAt());
        assertEquals(100, row.getDecodeQuality().controlValidFrames());
        assertEquals(1, row.getDecodeQuality().controlInvalidFrames());
        assertEquals(3, row.getDecodeQuality().controlCorrectedBits());
        assertEquals(999L, row.getDecodeQuality().controlLastValidDecodeMs());
        assertEquals(999L, model.getSnapshotSet().tables().get(1).rows().getFirst()
            .controlLastValidDecodeMs());

        run(model, () -> model.receiveControlChannelQuality(new ControlChannelQualitySnapshot(
            channel, channel.getRadresGuid(), 856_137_500L, 2_000L, false, -20.5, -21.0, -25.0, -18.0,
            97.5, 100, 1, 3, 0, 0, 999L)));
        assertNull(row.getSignalDbfs());
        assertNull(row.getDecodeHealthPercent());
        assertEquals(0, row.getQualityObservedAt());
        assertEquals(0, model.getSnapshotSet().tables().get(1).rows().getFirst()
            .controlLastValidDecodeMs());
    }

    @Test
    void appliesVoiceQualityToMatchingConventionalTimeslot() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isClearVoiceDecodeQualityOnCallEnd()
            {
                return false;
            }
        };
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, preference);
        Channel channel = trunkedChannel("Repeater", "Local", "Hill", new DecodeConfigDMR(), 451_012_500L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        IdentifierCollection identifiers = new IdentifierCollection(
            List.of(FrequencyConfigurationIdentifier.create(451_012_500L)));
        identifiers.setTimeslot(1);
        VoiceCallQuality voiceQuality = new VoiceCallQuality(1, 0, 0, 0, 2, 47);
        AudioCallId callId = new AudioCallId(1, 1, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 1_000L, 1_020L, 1, 1, 1_000L, 1_020L,
            true, false, CallEncryptionState.CLEAR, false, null, voiceQuality,
            CallLegId.from(callId), null, null);

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
            model.receiveAudioCallEvent(channel, new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot,
                new float[160], false, 0L, snapshot.lastActivityTimestamp()));
        });

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        assertEquals(snapshot.callId(), row.getVoiceCallId());
        assertSame(voiceQuality, row.getVoiceCallQuality());
        assertEquals(100.0d, row.getDecodeQuality().voice().qualityPercent());
    }

    @Test
    void clearsTerminalVoiceQualityWithoutFlickeringAtSegmentCompletion() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isClearVoiceDecodeQualityOnCallEnd()
            {
                return true;
            }
        };
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, preference);
        Channel channel = trunkedChannel("Repeater", "Local", "Hill", new DecodeConfigDMR(), 451_012_500L);
        ChannelMetadata metadata = new ChannelMetadata(aliasModel, 1);
        IdentifierCollection identifiers = new IdentifierCollection(
            List.of(FrequencyConfigurationIdentifier.create(451_012_500L)));
        identifiers.setTimeslot(1);
        VoiceCallQuality voiceQuality = new VoiceCallQuality(50, 0, 0, 0, 2, 47);
        AudioCallId currentCallId = new AudioCallId(1, 1, 1);
        AudioCallSnapshot current = new AudioCallSnapshot(currentCallId, null, null, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, true, false, CallEncryptionState.CLEAR, false,
            null, voiceQuality, CallLegId.from(currentCallId), null, null);

        run(model, () -> {
            model.channelStarted(channel, List.of(metadata));
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
            model.receiveAudioCallEvent(channel, new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, current,
                new float[160], false, 0L, current.lastActivityTimestamp()));
        });

        ChannelActivityRow row = model.getConventionalTable().getRows().getFirst();
        row.setControlQuality(-40.0d, 95.0d, 100, 1, 2, 0, 0, 1_999L, 2_000L);
        assertEquals(currentCallId, row.getVoiceCallId());
        assertNotNull(row.getVoiceCallQuality());

        AudioCallId staleCallId = new AudioCallId(1, 2, 1);
        AudioCallSnapshot stale = new AudioCallSnapshot(staleCallId, null, null, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true, CallEncryptionState.CLEAR, false,
            null, voiceQuality, CallLegId.from(staleCallId), null, null);
        run(model, () -> model.receiveAudioCallEvent(channel,
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, stale, null, false, 0L, 0L)));
        assertEquals(currentCallId, row.getVoiceCallId());
        assertNotNull(row.getVoiceCallQuality());

        run(model, () -> model.receiveAudioCallEvent(channel,
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, current, null, true, 0L, 0L)));
        assertEquals(currentCallId, row.getVoiceCallId());
        assertNotNull(row.getVoiceCallQuality());

        AudioCallId linkedCallId = new AudioCallId(1, 3, 1);
        AudioCallSnapshot linkedWithoutMeasurements = new AudioCallSnapshot(linkedCallId, currentCallId, null,
            identifiers, Set.of(), 2_000L, 2_100L, 1, 1, 2_000L, 2_100L, true, false,
            CallEncryptionState.CLEAR, false, null, new VoiceCallQuality(0, 0, 0, 5, 0, 0),
            CallLegId.from(linkedCallId), null, null);
        run(model, () -> model.receiveAudioCallEvent(channel,
            new AudioCallEvent(AudioCallEventType.CALL_CREATED, linkedWithoutMeasurements, null,
                false, 0L, 0L)));
        assertEquals(linkedCallId, row.getVoiceCallId());
        assertEquals(voiceQuality, row.getVoiceCallQuality());

        run(model, () -> model.receiveAudioCallEvent(channel,
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, linkedWithoutMeasurements, null,
                false, 0L, 0L)));
        assertNull(row.getVoiceCallId());
        assertNull(row.getVoiceCallQuality());
        assertEquals(95.0d, row.getDecodeQuality().controlPercent());

        model.receiveAudioCallEvent(channel, new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, current,
            new float[160], false, 0L, current.lastActivityTimestamp()));
        run(model, () -> {});
        assertEquals(currentCallId, row.getVoiceCallId());

        run(model, () -> {
            metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.IDLE, Operation.ADD, 1));
            model.updated(metadata, io.github.dsheirer.channel.metadata.ChannelMetadataField.DECODER_STATE);
        });
        assertNull(row.getVoiceCallId());
        assertNull(row.getVoiceCallQuality());
        assertNull(ChannelActivitySnapshot.from(model.getConventionalTable()).rows().getFirst().voiceQuality());

        run(model, () -> model.receiveAudioCallEvent(channel,
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, current, null, false, 0L, 0L)));
        assertNull(row.getVoiceCallId());
        assertNull(row.getVoiceCallQuality());
    }

    @Test
    void clearsTrunkedVoiceQualityBeforeTrafficChannelRelease() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isClearVoiceDecodeQualityOnCallEnd()
            {
                return true;
            }
        };
        ChannelActivityModel model = new ChannelActivityModel(aliasModel, preference);
        Channel parent = trunkedChannel("2.2", "Bus", "Site 5", trunkedDmrConfig(), 139_781_250L);
        Channel trafficChannel = new Channel("T-2.2", ChannelType.TRAFFIC);
        trafficChannel.setSystem("Bus");
        trafficChannel.setSite("Site 5");
        trafficChannel.setDecodeConfiguration(new DecodeConfigDMR());
        SourceConfigTuner trafficSource = new SourceConfigTuner();
        trafficSource.setFrequency(139_968_750L);
        trafficChannel.setSourceConfiguration(trafficSource);
        DMRAbsoluteChannel traffic = new DMRAbsoluteChannel(838, 1, 139_968_750L, 0);
        IdentifierCollection identifiers = new IdentifierCollection(
            List.of(FrequencyConfigurationIdentifier.create(139_968_750L)));
        identifiers.setTimeslot(1);
        AudioCallId callId = new AudioCallId(1, 1, 1);
        VoiceCallQuality voiceQuality = new VoiceCallQuality(50, 0, 0, 0, 2, 47);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, true, false, CallEncryptionState.CLEAR, false,
            null, voiceQuality, CallLegId.from(callId), null, null);

        run(model, () -> {
            model.trunkedTrafficEvent(parent, trafficChannel, traffic, 1, identifiers,
                DecodeEventType.CALL_GROUP, 139_781_250L);
            model.receiveAudioCallEvent(trafficChannel, new AudioCallEvent(AudioCallEventType.AUDIO_FRAME,
                snapshot, new float[160], false, 0L, snapshot.lastActivityTimestamp()));
        });

        ChannelActivityRow row = model.getTables().get(1).getRows().stream()
            .filter(candidate -> candidate.getRole() == ChannelActivityRow.Role.TRAFFIC)
            .findFirst().orElseThrow();
        assertSame(trafficChannel, row.getChannel());
        assertEquals(callId, row.getVoiceCallId());
        assertNotNull(row.getVoiceCallQuality());

        run(model, () -> model.channelStopped(trafficChannel));
        assertSame(parent, row.getChannel());
        assertNull(row.getVoiceCallId());
        assertNull(row.getVoiceCallQuality());

        run(model, () -> model.receiveAudioCallEvent(trafficChannel,
            new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, snapshot, null, false, 0L, 0L)));
        assertNull(row.getVoiceCallId());
        assertNull(row.getVoiceCallQuality());
    }

    @Test
    void combinesControlVoiceAndDataTagsForTheSameFrequency()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableState table = new ChannelActivityTableState("Test Site", parent, null);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        long frequency = 856_137_500L;

        ChannelActivityRow control = session.currentControl(frequency, "0-821").current();
        ChannelActivityRow traffic = session.announcedData(frequency, "0-821");
        session.addTag(frequency, ChannelTag.VOICE);
        session.addTag(frequency, ChannelTag.DATA);
        traffic.setState(io.github.dsheirer.channel.state.State.ENCRYPTED);

        assertSame(control, traffic);
        assertEquals(1, table.getRows().size());

        for(ChannelActivityRow row: List.of(control, traffic))
        {
            assertTrue(row.hasTag(ChannelTag.CURRENT_CONTROL));
            assertTrue(row.hasTag(ChannelTag.DATA_ANNOUNCED));
            assertTrue(row.hasTag(ChannelTag.VOICE));
            assertTrue(row.hasTag(ChannelTag.DATA));
        }

        assertEquals(io.github.dsheirer.channel.state.State.ENCRYPTED, traffic.getState());
    }

    @Test
    void reusesAlternateControlRowForFdmaVoiceTraffic()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableState table = new ChannelActivityTableState("Test Site", parent, null);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        APCO25Channel channel = APCO25Channel.create(0, 459);
        channel.setFrequencyBand(new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1));

        ChannelActivityRow alternate = session.alternateControl(channel);
        ChannelActivityRow traffic = session.traffic(parent, channel);
        session.addTag(channel.getDownlinkFrequency(), ChannelTag.VOICE);
        traffic.setState(io.github.dsheirer.channel.state.State.CALL);
        traffic.setTrafficGrantExpiresAt(System.currentTimeMillis() + 5_000L);

        ChannelActivityRow refreshedAlternate = session.alternateControl(channel);

        assertSame(alternate, traffic);
        assertSame(traffic, refreshedAlternate);
        assertEquals(853_875_000L, traffic.getFrequency());
        assertEquals("ACC + VC", traffic.getTagsDisplay());
        assertEquals(io.github.dsheirer.channel.state.State.CALL, traffic.getState());
        assertEquals(1, table.getRows().size());
    }

    @Test
    void retainsSeparateRowsForTdmATimeslots()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableState table = new ChannelActivityTableState("Test Site", parent, null);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        P25FrequencyBand band = new P25FrequencyBand(1, 851_012_500L, -45_000_000L, 12_500L, 12_500, 2);
        APCO25Channel timeslotOne = APCO25Channel.create(1, 2);
        APCO25Channel timeslotTwo = APCO25Channel.create(1, 3);
        timeslotOne.setFrequencyBand(band);
        timeslotTwo.setFrequencyBand(band);

        ChannelActivityRow control = session.alternateControl(timeslotOne.getDownlinkFrequency(), "1-2");
        ChannelActivityRow trafficOne = session.traffic(parent, timeslotOne);
        ChannelActivityRow trafficTwo = session.traffic(parent, timeslotTwo);

        assertNotSame(control, trafficOne);
        assertNotSame(trafficOne, trafficTwo);
        assertEquals(trafficOne.getFrequency(), trafficTwo.getFrequency());
        assertEquals(3, table.getRows().size());
    }

    @Test
    void retainsFdmaTrafficRowWhenControlAnnouncementIsWithdrawn()
    {
        Channel parent = new Channel("Test Site", ChannelType.STANDARD);
        ChannelActivityTableState table = new ChannelActivityTableState("Test Site", parent, null);
        SiteActivitySession session = new SiteActivitySession(parent, table);
        long frequency = 853_875_000L;

        ChannelActivityRow alternate = session.alternateControl(frequency, "0-459");
        ChannelActivityRow traffic = session.announcedData(frequency, "0-459");
        session.addTag(frequency, ChannelTag.VOICE);

        assertTrue(session.reconcilePromotedControls(Set.of(), 852_400_000L).isEmpty());
        assertSame(alternate, traffic);
        assertSame(traffic, session.traffic(frequency, null));
        assertEquals(1, table.getRows().size());
    }

    private static void run(ChannelActivityModel model, Runnable runnable) throws Exception
    {
        SwingUtilities.invokeAndWait(runnable);
        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS), "channel activity worker did not become idle");
    }

    private static NowPlayingPreference retainingPreference()
    {
        return new NowPlayingPreference(type -> {})
        {
            @Override
            public boolean isRetainIdleCallDetails()
            {
                return true;
            }
        };
    }

    private static AliasListDefinition p25AliasList(String name, long id)
    {
        AliasListDefinition definition = new AliasListDefinition(name, AliasListFamily.P25);
        definition.setId(id);
        return definition;
    }

    private static Alias alias(String name, long id, AliasListDefinition definition, AliasID matcher)
    {
        Alias alias = new Alias(name);
        alias.setId(id);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(matcher);
        return alias;
    }

    private static Channel trunkedChannel(String name, String system, String site,
                                           DecodeConfiguration decodeConfig,
                                           long frequency)
    {
        Channel channel = new Channel(name, ChannelType.STANDARD);
        channel.setSystem(system);
        channel.setSite(site);
        channel.setDecodeConfiguration(decodeConfig);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static DecodeConfigDMR trunkedDmrConfig()
    {
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        return configuration;
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long frequency, long timestamp,
                                                         boolean active)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getRadresGuid(), frequency, timestamp, active,
            -20.5, -21.0, -25.0, -18.0, 97.5, 100, 1, 3, 0, 0, timestamp - 1);
    }

}

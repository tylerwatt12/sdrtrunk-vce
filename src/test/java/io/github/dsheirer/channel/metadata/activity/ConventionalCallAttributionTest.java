/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel.metadata.activity;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateNotification.Operation;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HeaderData;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWordFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.EncryptionSyncParameters;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConventionalCallAttributionTest
{
    private static final long FREQUENCY = 154_875_000L;

    @Test
    void exactTrackerFactsClearThePreviousCallerAndOwnLateUpdatesAcrossAudioChunks() throws Exception
    {
        var aliases = new AliasModel();
        try(var model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {})))
        {
            Channel channel = channel();
            ChannelMetadata metadata = new ChannelMetadata(aliases);
            model.channelStarted(channel, List.of(metadata));
            var first = call("first", 1_000, 101);
            model.p25ConventionalCall(channel, first, false);
            await(model);
            var row = model.getConventionalTable().getRows().getFirst();
            assertEquals(APCO25RadioIdentifier.createFrom(101), row.getSource());

            var next = call("next", 2_000, null);
            model.p25ConventionalCall(channel, next, false);
            var nextId = new AudioCallId(1, 2, 0);
            model.receiveAudioCallEvent(channel, audio(AudioCallEventType.CALL_CREATED, nextId, null, next));
            await(model);
            assertNull(row.getSource(), "A fresh same-talkgroup tracker is explicitly unknown");
            assertTrue(row.getSourceAliases().isEmpty());

            //Mutable decoder cleanup and an old terminal audio snapshot cannot reintroduce the prior caller.
            staleMetadata(model, metadata);
            model.receiveAudioCallEvent(channel,
                audio(AudioCallEventType.CALL_COMPLETED, new AudioCallId(1, 1, 0), null, first));
            model.p25ConventionalCall(channel, first, true);
            await(model);
            assertNull(row.getSource());

            var resolved = call("next", 2_000, 202);
            model.p25ConventionalCall(channel, resolved, false);
            await(model);
            assertEquals(APCO25RadioIdentifier.createFrom(202), row.getSource());
            var linkedId = new AudioCallId(1, 3, 0);
            model.receiveAudioCallEvent(channel, audio(AudioCallEventType.CALL_CREATED, linkedId, nextId, resolved));
            model.receiveAudioCallEvent(channel, audio(AudioCallEventType.CALL_COMPLETED, nextId, null, next));
            staleMetadata(model, metadata);
            await(model);
            assertEquals(APCO25RadioIdentifier.createFrom(202), row.getSource());
            assertEquals(CallLegId.from(nextId), row.getCallLegId());
        }
    }

    @Test
    void factoryConnectsAcceptedSourceChangesBeforeAnyAudioBuilderExists() throws Exception
    {
        var aliases = new AliasModel();
        try(var model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {})))
        {
            Channel channel = channel();
            ChannelMetadata metadata = new ChannelMetadata(aliases);
            model.channelStarted(channel, List.of(metadata));
            List<Module> modules = DecoderFactory.getPrimaryModules(channel, aliases, new UserPreferences(), null, null,
                48_000, model);
            try
            {
                P25P1DecoderState state = modules.stream().filter(P25P1DecoderState.class::isInstance)
                    .map(P25P1DecoderState.class::cast).findFirst().orElseThrow();
                state.setCurrentFrequency(FREQUENCY);
                state.receive(voice(1_000, 101));
                await(model);
                var row = model.getConventionalTable().getRows().getFirst();
                assertEquals(APCO25RadioIdentifier.createFrom(101), row.getSource());

                //No HDU, TDU, codec or audio callback intervenes. The accepted next tracker supplies its source.
                state.receive(voice(1_180, 202));
                staleMetadata(model, metadata);
                await(model);
                assertEquals(APCO25RadioIdentifier.createFrom(202), row.getSource());

                state.receive(header(1_360));
                staleMetadata(model, metadata);
                await(model);
                assertNull(row.getSource(), "The next header has no source and cannot inherit the prior radio");
                state.receive(voice(1_540, 303));
                await(model);
                assertEquals(APCO25RadioIdentifier.createFrom(303), row.getSource());
                assertNull(row.getCallLegId(), "All caller updates precede audio builder creation");
            }
            finally
            {
                modules.forEach(Module::dispose);
            }
        }
    }

    @Test
    void trackerlessUnknownAudioAfterARealEndCannotRetainTheCompletedCaller() throws Exception
    {
        var aliases = new AliasModel();
        try(var model = new ChannelActivityModel(aliases, new NowPlayingPreference(type -> {})))
        {
            Channel channel = channel();
            ChannelMetadata metadata = new ChannelMetadata(aliases);
            model.channelStarted(channel, List.of(metadata));
            List<Module> modules = DecoderFactory.getPrimaryModules(channel, aliases, new UserPreferences(), null,
                null, 48_000, model);
            try
            {
                P25P1DecoderState state = modules.stream().filter(P25P1DecoderState.class::isInstance)
                    .map(P25P1DecoderState.class::cast).findFirst().orElseThrow();
                P25P1AudioModule audio = modules.stream().filter(P25P1AudioModule.class::isInstance)
                    .map(P25P1AudioModule.class::cast).findFirst().orElseThrow();
                EventBus bus = new EventBus();
                state.setInterModuleEventBus(bus);
                audio.setInterModuleEventBus(bus);
                state.setIdentifierUpdateListener(audio.getIdentifierUpdateListener());
                state.setDecoderStateListener(audio.getDecoderStateListener());
                List<AudioCallEvent> events = new java.util.ArrayList<>();
                audio.setAudioCallEventListener(event -> {
                    events.add(event);
                    model.receiveAudioCallEvent(channel, event);
                });
                state.setCurrentFrequency(FREQUENCY);
                var known = voice(1_000, 101);
                state.receive(known);
                audio.receive(known);
                var end = new TDUMessage(new CorrectedBinaryMessage(28), 0x123, 1_100);
                state.receive(end);
                audio.receive(end);
                await(model);
                var row = model.getConventionalTable().getRows().getFirst();
                assertEquals(APCO25RadioIdentifier.createFrom(101), row.getSource());

                //Neither frame identifies a new caller or destination, so the manager must not invent a tracker.
                CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
                bits.setInt(2, IntField.length6(2));
                LinkControlWord nonuser = LinkControlWordFactory.create(bits, 1_200, false);
                nonuser.setValid(true);
                LDU1Message unknown = new LDU1Message(new CorrectedBinaryMessage(1568), 0x123, 1_200)
                {
                    @Override public LinkControlWord getLinkControlWord() { return nonuser; }
                };
                state.receive(unknown);
                audio.receive(unknown);
                BinaryMessage clearBits = new BinaryMessage(96);
                clearBits.setInt(0x80, IntField.length8(72));
                EncryptionSyncParameters clear = new EncryptionSyncParameters(clearBits);
                LDU2Message clearLdu = new LDU2Message(new CorrectedBinaryMessage(1568), 0x123, 1_380)
                {
                    @Override public EncryptionSyncParameters getEncryptionSyncParameters() { return clear; }
                };
                state.receive(clearLdu);
                audio.receive(clearLdu);
                staleMetadata(model, metadata);
                await(model);
                AudioCallSnapshot snapshot = events.stream()
                    .filter(event -> event.eventType() == AudioCallEventType.CALL_CREATED)
                    .map(AudioCallEvent::snapshot).reduce((first, second) -> second).orElseThrow();
                assertNull(snapshot.activityCallToken());
                assertNull(snapshot.identifierCollection().getFromIdentifier());
                assertEquals(snapshot.callLegId(), row.getCallLegId());
                assertNull(row.getSource(), "The new exact unknown audio leg must clear the ended caller");

                //A later accepted tracker still supplies its caller immediately; an old audio end cannot clear it.
                state.receive(voice(1_560, 202));
                model.receiveAudioCallEvent(channel, new AudioCallEvent(AudioCallEventType.CALL_COMPLETED,
                    snapshot, null, false, 0, 0));
                await(model);
                assertEquals(APCO25RadioIdentifier.createFrom(202), row.getSource());
            }
            finally
            {
                modules.forEach(Module::dispose);
            }
        }
    }

    private static void staleMetadata(ChannelActivityModel model, ChannelMetadata metadata)
    {
        metadata.receive(new IdentifierUpdateNotification(APCO25RadioIdentifier.createFrom(101), Operation.ADD, 0));
        metadata.receive(new IdentifierUpdateNotification(APCO25Talkgroup.create(1201), Operation.ADD, 0));
        metadata.receive(new IdentifierUpdateNotification(ChannelStateIdentifier.CALL, Operation.ADD, 0));
        model.updated(metadata, ChannelMetadataField.DECODER_STATE);
    }

    private static P25CallStartEvent call(String token, long startedAt, Integer source)
    {
        List<Identifier> identifiers = new java.util.ArrayList<>();
        identifiers.add(FrequencyConfigurationIdentifier.create(FREQUENCY));
        identifiers.add(APCO25Talkgroup.create(1201));
        if(source != null) identifiers.add(APCO25RadioIdentifier.createFrom(source));
        return new P25CallStartEvent(null, DecoderType.P25_CONVENTIONAL, DecodeEventType.CALL_GROUP, startedAt,
            identifiers, FREQUENCY, null, null, null, false, false, null, token);
    }

    private static AudioCallEvent audio(AudioCallEventType type, AudioCallId id, AudioCallId linked,
                                        P25CallStartEvent call)
    {
        CallLegId leg = CallLegId.from(linked != null ? linked : id);
        var snapshot = new AudioCallSnapshot(id, linked, null, call.identifierCollection(), Set.of(),
            call.startedAtEpochMilliseconds(), call.startedAtEpochMilliseconds() + 180, 1, 1,
            call.startedAtEpochMilliseconds(), 0, true, type == AudioCallEventType.CALL_COMPLETED,
            CallEncryptionState.CLEAR, false, null, null, leg, CallLegSource.UNKNOWN, null, null, call.callToken());
        return new AudioCallEvent(type, snapshot, null, false, 0, 0);
    }

    private static LDU1Message voice(long timestamp, int source)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.setInt(1201, IntField.length16(32));
        bits.setInt(source, IntField.length24(48));
        LinkControlWord lc = LinkControlWordFactory.create(bits, timestamp, false);
        lc.setValid(true);
        return new LDU1Message(new CorrectedBinaryMessage(1568), 0x123, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lc; }
        };
    }

    private static HDUMessage header(long timestamp)
    {
        BinaryMessage bits = new BinaryMessage(120);
        bits.setInt(0x80, IntField.length8(80));
        bits.setInt(1201, IntField.length16(104));
        HeaderData header = new HeaderData(bits);
        return new HDUMessage(new CorrectedBinaryMessage(648), 0x123, timestamp)
        {
            @Override public HeaderData getHeaderData() { return header; }
        };
    }

    private static Channel channel()
    {
        Channel channel = new Channel("Conventional");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void await(ChannelActivityModel model) throws Exception
    {
        assertTrue(model.awaitIdle(5, TimeUnit.SECONDS));
    }
}

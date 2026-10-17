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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioActivityCallEvent;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWord;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlOpcode;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.LinkControlWordFactory;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.LCMotorolaUnitGPS;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSBlock1;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSBlock2;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.l3harris.LCHarrisTalkerGPSComplete;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.LDU2Message;
import io.github.dsheirer.module.decode.p25.phase1.message.ldu.EncryptionSyncParameters;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HeaderData;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDULCMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tdu.TDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25CallUpdateEvent;
import io.github.dsheirer.module.decode.p25.audio.P25P1AudioModule;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateConventionalCallTest
{
    @Test
    void forwardsPrivatelyManagedConventionalCallEvents() throws Exception
    {
        long frequency = 154_875_000L;
        Channel channel = new Channel("LorainCountySO", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, null);
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        decoderState.addDecodeEventListener(events::add);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1_201));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        P25TrafficChannelManager manager = trafficChannelManager(decoderState);

        manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_000L, null);
        manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_100L, null);

        assertEquals(2, events.size());
        assertSame(events.get(0), events.get(1));
    }

    @Test
    void freshHeaderKeepsAcceptedGrantSourceWithoutReusingDecoderWorkingSource()
    {
        try(Pipeline pipeline = new Pipeline())
        {
            grant(pipeline.manager, 700_001);
            var grant = pipeline.attributions.starts.getFirst();
            assertFalse(pipeline.manager.processP1TrafficCallEnd(851_012_500L, 1_050L));
            pipeline.state.getIdentifierCollection().update(APCO25RadioIdentifier.createFrom(700_009));
            pipeline.receive(terminator(1_050L));
            pipeline.receive(header(1_100L));

            assertNull(pipeline.state.getIdentifierCollection().getFromIdentifier());
            assertEquals(grant.callToken(), pipeline.attributions.events.getLast().token());
            assertEquals(APCO25RadioIdentifier.createFrom(700_001),
                pipeline.attributions.events.getLast().attribution().source());
            assertTrue(pipeline.manager.processP1TrafficCallEnd(851_012_500L, 1_200L));
            assertEquals(1, pipeline.attributions.starts.size());
        }
    }

    @Test
    void headerAndTerminatorSeparateCallsEvenWhenFirstVoiceWordHasNoCurrentUser()
    {
        try(Pipeline pipeline = new Pipeline())
        {
            grant(pipeline.manager, null);
            var grant = pipeline.attributions.starts.getFirst();
            pipeline.receive(terminator(950L));
            pipeline.receive(header(1_000L));
            var first = pipeline.attributions.events.getLast();
            pipeline.receive(voice(1_171L, LinkControlOpcode.GROUP_VOICE_CHANNEL_UPDATE, List.of()));
            pipeline.receive(terminator(1_216L));
            pipeline.receive(header(1_422L));
            var second = pipeline.attributions.events.getLast();
            pipeline.receive(voice(2_673L, 700_001));
            pipeline.receive(terminator(3_422L));

            assertEquals(grant.callToken(), first.token());
            assertNotEquals(first.token(), second.token());
            assertNull(first.attribution().source());
            assertEquals(APCO25RadioIdentifier.createFrom(700_001), second.attribution().source());
            assertEquals(2, pipeline.attributions.starts.size());
            assertTrue(pipeline.attributions.updates.stream().anyMatch(update -> update.complete() &&
                update.call().callToken().equals(first.token()) &&
                update.call().identifierCollection().getFromIdentifier() == null));
            List<AudioCallEvent> completed = pipeline.audioEvents.stream()
                .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
            assertEquals(2, completed.size());
            assertEquals(first.token(), completed.getFirst().snapshot().activityCallToken());
            assertEquals(second.token(), completed.getLast().snapshot().activityCallToken());
            assertNull(completed.getFirst().snapshot().identifierCollection().getFromIdentifier());
            assertEquals(APCO25RadioIdentifier.createFrom(700_001),
                completed.getLast().snapshot().identifierCollection().getFromIdentifier());
        }
    }

    @Test
    void repeatedHeaderSeparatesCallsWithoutAnyCurrentUserOrTerminator()
    {
        try(Pipeline pipeline = new Pipeline())
        {
            pipeline.receive(header(1_000L));
            var first = pipeline.attributions.events.getLast();
            pipeline.receive(header(1_422L));
            var second = pipeline.attributions.events.getLast();
            pipeline.receive(voice(1_600L, 700_001));
            pipeline.receive(terminator(1_800L));

            assertNotEquals(first.token(), second.token());
            assertNull(first.attribution().source());
            assertEquals(APCO25RadioIdentifier.createFrom(700_001), second.attribution().source());
            assertEquals(2, pipeline.audioEvents.stream()
                .filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).count());
        }
    }

    @Test
    void metadataOnlySourceTransitionsUseTheSameBoundariesAsDecodedAudio()
    {
        for(boolean encrypted: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline())
            {
                pipeline.receive(header(1_000L, encrypted));
                pipeline.receive(voice(1_180L, 101, encrypted));
                pipeline.receive(voice(1_360L, 202, encrypted));
                pipeline.receive(terminator(1_400L));
                var completed = pipeline.completed();
                assertEquals(2, completed.size());
                assertNotEquals(completed.getFirst().snapshot().activityCallToken(),
                    completed.getLast().snapshot().activityCallToken());
                assertEquals(101, completed.getFirst().snapshot().identifierCollection().getFromIdentifier().getValue());
                assertEquals(202, completed.getLast().snapshot().identifierCollection().getFromIdentifier().getValue());
                assertEquals(1_200L, completed.getLast().snapshot().startTimestamp());
                assertEquals(encrypted ? CallEncryptionState.ENCRYPTED : CallEncryptionState.CLEAR,
                    completed.getLast().snapshot().encryptionState());
            }
        }
    }

    @Test
    void callEndingBeforeEncryptionIsKnownCannotLabelTheNextUnknownCall()
    {
        for(boolean linkControl: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline(true))
            {
                pipeline.receive(voice(1_000L, 101, true));
                pipeline.receive(linkControl ? terminator(1_100L) :
                    new TDUMessage(new CorrectedBinaryMessage(28), 0x123, 1_100L));
                pipeline.receive(voice(1_200L, LinkControlOpcode.GROUP_VOICE_CHANNEL_UPDATE, List.of()));
                pipeline.receive(clearLdu2(1_380L));
                pipeline.receive(terminator(1_500L));

                var completed = pipeline.completed();
                assertEquals(1, completed.size());
                assertNull(completed.getFirst().snapshot().activityCallToken());
                assertNull(completed.getFirst().snapshot().identifierCollection().getFromIdentifier());
                assertEquals(18, pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
            }
        }
    }

    @Test
    void plainTerminatorCannotTeachThePreviousCallerToANewSourceFreeGrant()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(header(1_000L));
            pipeline.receive(voice(1_180L, 101));
            String firstToken = pipeline.attributions.events.getLast().token();
            TDUMessage end = new TDUMessage(new CorrectedBinaryMessage(28), 0x123, 1_220L);
            assertTrue(end.isValid());
            pipeline.receive(end);
            pipeline.receive(voice(1_400L, LinkControlOpcode.GROUP_VOICE_CHANNEL_UPDATE, List.of()));

            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(APCO25Talkgroup.create(1201));
            pipeline.manager.processP1ControlAnnouncedTrafficUpdate(controlChannel(pipeline.manager),
                VoiceServiceOptions.createUnencrypted(), identifiers,
                Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 1_580L);
            var grant = pipeline.attributions.starts.getLast();
            assertNotEquals(firstToken, grant.callToken());
            assertNull(grant.identifierCollection().getFromIdentifier());

            pipeline.receive(voice(1_580L, 0));
            var second = pipeline.attributions.events.getLast();
            assertEquals(grant.callToken(), second.token());
            assertNull(second.attribution().source());
            assertNull(pipeline.audioEvents.getLast().snapshot().identifierCollection().getFromIdentifier());
            pipeline.receive(voice(1_760L, 202));
            assertEquals(second.token(), pipeline.attributions.events.getLast().token());
            pipeline.receive(terminator(1_900L));

            assertEquals(2, pipeline.completed().size());
            assertEquals(2, pipeline.attributions.starts.size());
            assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(202, pipeline.completed().getLast().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(27, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME &&
                    second.token().equals(event.snapshot().activityCallToken())).count());
        }
    }

    @Test
    void preHeaderPlainTerminatorAndSameCallZeroSourceVoicePreserveTheAcceptedGrant()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            grant(pipeline.manager, 101);
            String token = pipeline.attributions.starts.getFirst().callToken();
            pipeline.receive(new TDUMessage(new CorrectedBinaryMessage(28), 0x123, 950L));
            pipeline.receive(header(1_000L));
            pipeline.receive(voice(1_180L, 0));
            pipeline.receive(terminator(1_400L));

            assertEquals(1, pipeline.completed().size());
            assertEquals(token, pipeline.completed().getFirst().snapshot().activityCallToken());
            assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(9, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
        }
    }

    @Test
    void sourceFreeNewTalkgroupCannotInheritThePreviousCallsWorkingSource()
    {
        for(boolean grantUpdate: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline(true))
            {
                pipeline.receive(header(1_000L));
                pipeline.receive(voice(1_180L, 101));
                String firstToken = pipeline.attributions.events.getLast().token();
                if(grantUpdate)
                {
                    MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
                    identifiers.update(APCO25Talkgroup.create(1202));
                    pipeline.manager.processP1ControlAnnouncedTrafficUpdate(controlChannel(pipeline.manager),
                        VoiceServiceOptions.createUnencrypted(), identifiers,
                        Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 1_360L);
                    assertNull(pipeline.attributions.starts.getLast().identifierCollection().getFromIdentifier());
                }

                pipeline.receive(ldu(1_360L, user(1_360L, 0, false, false, 1202)));
                var second = pipeline.attributions.events.getLast();
                assertNotEquals(firstToken, second.token());
                assertNull(second.attribution().source());
                assertNull(pipeline.audioEvents.getLast().snapshot().identifierCollection().getFromIdentifier());
                pipeline.receive(ldu(1_540L, user(1_540L, 202, false, false, 1202)));
                assertEquals(second.token(), pipeline.attributions.events.getLast().token());
                pipeline.receive(new TDUMessage(new CorrectedBinaryMessage(28), 0x123, 1_700L));

                assertEquals(2, pipeline.completed().size());
                assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                    .getFromIdentifier().getValue());
                assertEquals(202, pipeline.completed().getLast().snapshot().identifierCollection()
                    .getFromIdentifier().getValue());
                assertEquals(18, pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.AUDIO_FRAME &&
                        second.token().equals(event.snapshot().activityCallToken())).count());
            }
        }
    }

    @Test
    void validEndSourceRecoversTheNewCallDespiteTheOldDecoderSource()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(header(1_000L));
            pipeline.receive(voice(1_180L, 101));
            pipeline.receive(ldu(1_360L, user(1_360L, 0, false, false, 1202)));
            var second = pipeline.attributions.events.getLast();
            assertNull(second.attribution().source());
            pipeline.receive(terminator(1_540L, 202, 1202));

            assertEquals(2, pipeline.completed().size());
            assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(second.token(), pipeline.completed().getLast().snapshot().activityCallToken());
            assertEquals(202, pipeline.completed().getLast().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
        }
    }

    @Test
    void gpsUsesAcceptedCallSourceAndPreservesItsParsedLocation()
    {
        for(boolean harris: List.of(false, true))
        for(boolean newTarget: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline())
            {
                List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
                pipeline.state.addDecodeEventListener(events::add);
                pipeline.receive(header(1_000L));
                pipeline.receive(voice(1_180L, 101));
                if(newTarget) pipeline.receive(ldu(1_360L, user(1_360L, 0, false, false, 1202)));
                else pipeline.state.getIdentifierCollection().remove(IdentifierClass.USER, Form.RADIO, Role.FROM);

                LinkControlWord gps;
                if(harris)
                {
                    gps = LCHarrisTalkerGPSComplete.create(
                        new LCHarrisTalkerGPSBlock1(new CorrectedBinaryMessage(72)),
                        new LCHarrisTalkerGPSBlock2(new CorrectedBinaryMessage(72)), 1_450L);
                }
                else
                {
                    CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
                    bits.setInt(LinkControlOpcode.MOTOROLA_UNIT_GPS.getCode(), IntField.length6(2));
                    bits.setInt(0x90, IntField.length8(8));
                    bits.setInt(1_234_567, IntField.range(25, 47));
                    bits.setInt(2_345_678, IntField.range(49, 71));
                    gps = new LCMotorolaUnitGPS(bits);
                }
                gps.setValid(true);
                pipeline.receive(gps instanceof IMessage message ? message : ldu(1_450L, gps));
                var event = (PlottableDecodeEvent)events.stream().filter(value ->
                    value.getEventType() == DecodeEventType.GPS).findFirst().orElseThrow();
                var location = harris ? ((LCHarrisTalkerGPSComplete)gps).getLocation() :
                    ((LCMotorolaUnitGPS)gps).getLocation();
                var position = harris ? ((LCHarrisTalkerGPSComplete)gps).getGeoPosition() :
                    ((LCMotorolaUnitGPS)gps).getGeoPosition();
                assertEquals(position, event.getLocation());
                assertTrue(event.getIdentifierCollection().hasIdentifier(location));
                if(newTarget) assertNull(event.getIdentifierCollection().getFromIdentifier());
                else assertEquals(101, event.getIdentifierCollection().getFromIdentifier().getValue());
            }
        }
    }

    @Test
    void actualDecoderResetRetiresPendingCallBeforeTheNextEncryptionObservation()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(voice(1_000L, 101, true));
            var reset = new DecoderStateEvent(this, DecoderStateEvent.Event.REQUEST_RESET, State.RESET);
            pipeline.state.receiveDecoderStateEvent(reset);
            pipeline.audio.getDecoderStateListener().receive(reset);
            pipeline.receive(clearLdu2(1_380L));
            pipeline.receive(terminator(1_500L));

            assertEquals(1, pipeline.completed().size());
            assertNull(pipeline.completed().getFirst().snapshot().activityCallToken());
            assertNull(pipeline.completed().getFirst().snapshot().identifierCollection().getFromIdentifier());
        }
    }

    @Test
    void missedHeadersRetainEachCallersClearVoice()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(voice(1_000L, 101));
            pipeline.receive(voice(1_180L, 202));
            pipeline.receive(clearLdu2(1_360L));
            pipeline.receive(terminator(1_400L));

            assertEquals(2, pipeline.completed().size());
            assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            var completed = pipeline.completed().getFirst();
            assertEquals(840L, completed.snapshot().startTimestamp());
            assertEquals(202, pipeline.completed().getLast().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(27, pipeline.audioEvents.stream()
                .filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
            assertEquals(9, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME &&
                    event.snapshot().identifierCollection().getFromIdentifier().getValue().equals(101)).count());
            assertEquals(18, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME &&
                    event.snapshot().identifierCollection().getFromIdentifier().getValue().equals(202)).count());
        }
    }

    @Test
    void lateClearUserRetainsVoiceQueuedBeforeItsFirstSource()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(voice(1_000L, LinkControlOpcode.GROUP_VOICE_CHANNEL_UPDATE, List.of()));
            pipeline.receive(voice(1_180L, 101));
            pipeline.receive(clearLdu2(1_360L));
            pipeline.receive(terminator(1_400L));

            assertEquals(1, pipeline.completed().size());
            assertEquals(840L, pipeline.completed().getFirst().snapshot().startTimestamp());
            assertEquals(101, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(27, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
        }
    }

    @Test
    void aHeaderDiscardsEarlierVoiceBeforeItsStateNotificationCanActivateAudio()
    {
        for(boolean previouslyClear: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline(true))
            {
                if(previouslyClear) pipeline.audio.receive(header(900L));
                pipeline.audio.receive(voice(1_000L, LinkControlOpcode.GROUP_VOICE_CHANNEL_UPDATE, List.of()));
                pipeline.receive(header(48_121L));
                String token = pipeline.attributions.events.getLast().token();
                pipeline.receive(voice(48_300L, 101));
                pipeline.receive(terminator(48_500L));

                assertEquals(1, pipeline.completed().size());
                var completed = pipeline.completed().getFirst().snapshot();
                assertEquals(token, completed.activityCallToken());
                assertEquals(48_121L, completed.startTimestamp());
                assertEquals(101, completed.identifierCollection().getFromIdentifier().getValue());
                var frames = pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.AUDIO_FRAME).toList();
                assertEquals(9, frames.size());
                assertTrue(frames.stream().allMatch(event -> event.voiceFrameTimestamp() >= 48_140L));
                assertEquals(48_121L, pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.CALL_CREATED).findFirst().orElseThrow()
                    .snapshot().startTimestamp());
            }
        }
    }

    @Test
    void aHeaderCannotActivateAnEarlierDeferredEncryptionContext()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.audio.receive(header(1_000L, true, 0x1111));
            pipeline.receive(header(48_121L, true, 0x2222));
            pipeline.receive(voice(48_300L, 101, true));
            pipeline.receive(terminator(48_500L));

            assertEquals(1, pipeline.completed().size());
            var completed = pipeline.completed().getFirst().snapshot();
            assertEquals(48_121L, completed.startTimestamp());
            assertEquals(0x2222, completed.callEncryptionEvidence().keyId());
            assertFalse(pipeline.audioEvents.stream().anyMatch(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME));
            assertEquals(48_121L, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.CALL_CREATED).findFirst().orElseThrow()
                .snapshot().startTimestamp());
        }
    }

    @Test
    void delayedHeaderStatePreservesThatHeadersOwnQueuedVoiceAndEncryption()
    {
        for(boolean encrypted: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline(true))
            {
                var old = pipeline.manager.processP1TrafficCallStart(851_012_500L, APCO25Talkgroup.create(1201),
                    APCO25RadioIdentifier.createFrom(909), header(1_000L).getHeaderData().getEncryptionKey(),
                    VoiceServiceOptions.createUnencrypted(),
                    new StandardChannel(851_012_500L), 1_000L);
                pipeline.audio.activityCallBound(old);
                var header = header(48_121L, encrypted, 0x2222);
                var firstVoice = voice(48_300L, 101, encrypted);
                pipeline.audio.receive(header);
                pipeline.audio.receive(firstVoice);
                pipeline.state.receive(header);
                String token = pipeline.attributions.events.getLast().token();
                assertNotEquals(old.token(), token);
                pipeline.state.receive(firstVoice);
                pipeline.receive(voice(48_480L, 101, encrypted));
                pipeline.receive(terminator(48_500L));

                assertEquals(1, pipeline.completed().size());
                var completed = pipeline.completed().getFirst().snapshot();
                assertEquals(token, completed.activityCallToken());
                assertEquals(101, completed.identifierCollection().getFromIdentifier().getValue());
                assertEquals(encrypted ? CallEncryptionState.ENCRYPTED : CallEncryptionState.CLEAR,
                    completed.encryptionState());
                assertEquals(encrypted ? 0 : 18, pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
                if(encrypted)
                {
                    assertEquals(48_121L, completed.startTimestamp());
                    assertEquals(0x2222, completed.callEncryptionEvidence().keyId());
                }
            }
        }
    }

    @Test
    void unresolvedProtectedVoiceCannotCarryIntoTheNextClearCaller()
    {
        try(Pipeline pipeline = new Pipeline(true))
        {
            pipeline.receive(voice(1_000L, 101, true));
            pipeline.receive(voice(1_180L, 202));
            pipeline.receive(clearLdu2(1_360L));
            pipeline.receive(terminator(1_400L));

            assertEquals(1, pipeline.completed().size());
            assertEquals(202, pipeline.completed().getFirst().snapshot().identifierCollection()
                .getFromIdentifier().getValue());
            assertEquals(18, pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME).count());
            assertTrue(pipeline.audioEvents.stream().filter(event ->
                event.eventType() == AudioCallEventType.AUDIO_FRAME).allMatch(event ->
                    event.voiceFrameTimestamp() >= 1_020L));
        }
    }

    @Test
    void aSourceBoundaryUsesItsOwnLinkControlEncryptionState()
    {
        for(boolean firstEncrypted: List.of(false, true))
        {
            try(Pipeline pipeline = new Pipeline(true))
            {
                pipeline.receive(header(1_000L, firstEncrypted));
                pipeline.receive(voice(1_180L, 101, firstEncrypted));
                pipeline.receive(voice(1_360L, 202, !firstEncrypted));
                pipeline.receive(terminator(1_400L));

                var completed = pipeline.completed();
                assertEquals(2, completed.size());
                assertEquals(firstEncrypted ? CallEncryptionState.ENCRYPTED : CallEncryptionState.CLEAR,
                    completed.getFirst().snapshot().encryptionState());
                assertEquals(firstEncrypted ? CallEncryptionState.CLEAR : CallEncryptionState.ENCRYPTED,
                    completed.getLast().snapshot().encryptionState());
                assertEquals(firstEncrypted ? DecodeEventType.CALL_GROUP : DecodeEventType.CALL_GROUP_ENCRYPTED,
                    pipeline.attributions.starts.getLast().eventType());
                var frames = pipeline.audioEvents.stream().filter(event ->
                    event.eventType() == AudioCallEventType.AUDIO_FRAME).toList();
                assertEquals(9, frames.size());
                assertTrue(frames.stream().allMatch(event -> event.snapshot().identifierCollection()
                    .getFromIdentifier().getValue().equals(firstEncrypted ? 202 : 101)));
            }
        }
    }

    @Test
    void freshConventionalHeaderStartsUnknownUntilItsOwnSourceArrives() throws Exception
    {
        Channel channel = new Channel("Conventional");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState state = new P25P1DecoderState(channel, null);
        long frequency = 154_875_000L;
        state.setCurrentFrequency(frequency);
        MutableIdentifierCollection previous = new MutableIdentifierCollection();
        previous.update(APCO25Talkgroup.create(1201));
        previous.update(APCO25RadioIdentifier.createFrom(700_001));
        var first = trafficChannelManager(state).processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), previous, 1_000L, null);
        trafficChannelManager(state).processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), previous, 1_050L, null);
        state.getIdentifierCollection().update(previous.getIdentifiers());
        Attributions attributions = listen(state);

        state.receive(header(1_100L));

        assertNull(state.getIdentifierCollection().getFromIdentifier());
        assertNotEquals(first.token(), attributions.events.getLast().token());
        assertNull(attributions.events.getLast().attribution().source());
        assertEquals(APCO25RadioIdentifier.createFrom(700_001), first.attribution().source());
    }

    @Test
    void decodedSourceTransitionStartsANewCallWhenHeaderAndTerminatorWereMissed()
    {
        Channel channel = new Channel("Conventional");
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25P1DecoderState state = new P25P1DecoderState(channel, null);
        state.setCurrentFrequency(154_875_000L);
        Attributions attributions = listen(state);
        state.receive(voice(1_000L, 101));
        var first = attributions.events.getLast();
        state.receive(voice(1_180L, 202));
        var second = attributions.events.getLast();
        assertNotEquals(first.token(), second.token());
        assertEquals(APCO25RadioIdentifier.createFrom(101), first.attribution().source());
        assertEquals(APCO25RadioIdentifier.createFrom(202), second.attribution().source());
    }

    private static LDU1Message voice(long timestamp, int source)
    {
        return voice(timestamp, source, false);
    }

    private static LDU1Message voice(long timestamp, int source, boolean encrypted)
    {
        return ldu(timestamp, user(timestamp, source, encrypted, false));
    }

    private static LDU1Message voice(long timestamp, LinkControlOpcode opcode, List<Identifier> identifiers)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.setInt(opcode.getCode(), IntField.length6(2));
        LinkControlWord lc = LinkControlWordFactory.create(bits, timestamp, false);
        lc.setValid(true);
        return ldu(timestamp, lc);
    }

    private static LDU1Message ldu(long timestamp, LinkControlWord lc)
    {
        return new LDU1Message(new CorrectedBinaryMessage(1568), 0x123, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lc; }
        };
    }

    private static Attributions listen(P25P1DecoderState state)
    {
        Attributions attributions = new Attributions();
        EventBus bus = new EventBus();
        bus.register(attributions);
        state.setInterModuleEventBus(bus);
        return attributions;
    }

    private static final class Attributions
    {
        private final List<AudioActivityCallEvent> events = new CopyOnWriteArrayList<>();
        private final List<P25CallStartEvent> starts = new CopyOnWriteArrayList<>();
        private final List<P25CallUpdateEvent> updates = new CopyOnWriteArrayList<>();
        @Subscribe public void receive(AudioActivityCallEvent event) { events.add(event); }
        @Subscribe public void receive(P25CallStartEvent event) { starts.add(event); }
        @Subscribe public void receive(P25CallUpdateEvent event) { updates.add(event); }
    }

    private static P25TrafficChannelManager controlManager()
    {
        Channel control = new Channel("Control");
        control.setDecodeConfiguration(new DecodeConfigP25Phase1());
        control.setSourceConfiguration(new SourceConfigRemote());
        return new P25TrafficChannelManager(control);
    }

    private static void grant(P25TrafficChannelManager manager, Integer source)
    {
        APCO25Channel channel = controlChannel(manager);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1201));
        if(source != null) identifiers.update(APCO25RadioIdentifier.createFrom(source));
        manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), identifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 900L);
    }

    private static APCO25Channel controlChannel(P25TrafficChannelManager manager)
    {
        P25FrequencyBand band = new P25FrequencyBand(0, 851_012_500L, -45_000_000L, 12_500L, 12_500, 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        APCO25Channel channel = APCO25Channel.create(0, 0);
        channel.setFrequencyBand(band);
        return channel;
    }

    private static TDULCMessage terminator(long timestamp)
    {
        return terminator(timestamp, 0, 1201);
    }

    private static TDULCMessage terminator(long timestamp, int source, int target)
    {
        LinkControlWord lc = user(timestamp, source, false, true, target);
        return new TDULCMessage(new CorrectedBinaryMessage(288), 0x123, timestamp)
        {
            @Override public LinkControlWord getLinkControlWord() { return lc; }
        };
    }

    private static LinkControlWord user(long timestamp, int source, boolean encrypted, boolean terminator)
    {
        return user(timestamp, source, encrypted, terminator, 1201);
    }

    private static LinkControlWord user(long timestamp, int source, boolean encrypted, boolean terminator, int target)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.setInt(encrypted ? 0x40 : 0, IntField.length8(16));
        bits.setInt(target, IntField.length16(32));
        bits.setInt(source, IntField.length24(48));
        LinkControlWord lc = LinkControlWordFactory.create(bits, timestamp, terminator);
        lc.setValid(true);
        return lc;
    }

    private static LDU2Message clearLdu2(long timestamp)
    {
        BinaryMessage bits = new BinaryMessage(96);
        bits.setInt(0x80, IntField.length8(72));
        EncryptionSyncParameters parameters = new EncryptionSyncParameters(bits);
        return new LDU2Message(new CorrectedBinaryMessage(1568), 0x123, timestamp)
        {
            @Override public EncryptionSyncParameters getEncryptionSyncParameters() { return parameters; }
        };
    }

    private static final class Pipeline implements AutoCloseable
    {
        private final boolean mHasCodec;
        private final P25TrafficChannelManager manager = controlManager();
        private final Attributions attributions = new Attributions();
        private final List<AudioCallEvent> audioEvents = new CopyOnWriteArrayList<>();
        private final P25P1DecoderState state;
        private final P25P1AudioModule audio = new P25P1AudioModule(new UserPreferences(), AliasList.empty("test"))
        {
            @Override protected boolean hasAudioCodec() { return mHasCodec; }
            @Override public IAudioCodec getAudioCodec() { return CODEC; }
        };

        private Pipeline()
        {
            this(false);
        }

        private Pipeline(boolean hasCodec)
        {
            mHasCodec = hasCodec;
            Channel traffic = new Channel("Traffic", ChannelType.TRAFFIC);
            traffic.setDecodeConfiguration(new DecodeConfigP25Phase1());
            state = new P25P1DecoderState(traffic, manager);
            state.setCurrentFrequency(851_012_500L);
            EventBus bus = new EventBus();
            bus.register(attributions);
            state.setInterModuleEventBus(bus);
            audio.setInterModuleEventBus(bus);
            state.setIdentifierUpdateListener(audio.getIdentifierUpdateListener());
            state.setDecoderStateListener(audio.getDecoderStateListener());
            audio.setAudioCallEventListener(audioEvents::add);
            MyEventBus.getGlobalEventBus().register(attributions);
        }

        private void receive(IMessage message)
        {
            state.receive(message);
            audio.receive(message);
        }

        private List<AudioCallEvent> completed()
        {
            return audioEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED).toList();
        }

        @Override public void close()
        {
            MyEventBus.getGlobalEventBus().unregister(attributions);
            audio.dispose();
        }
    }

    private static HDUMessage header(long timestamp)
    {
        return header(timestamp, false);
    }

    private static HDUMessage header(long timestamp, boolean encrypted)
    {
        return header(timestamp, encrypted, 0);
    }

    private static HDUMessage header(long timestamp, boolean encrypted, int keyId)
    {
        BinaryMessage bits = new BinaryMessage(120);
        bits.setInt(encrypted ? 0x84 : 0x80, IntField.length8(80));
        bits.setInt(keyId, IntField.length16(88));
        bits.setInt(1201, IntField.length16(104));
        HeaderData data = new HeaderData(bits);
        return new HDUMessage(new CorrectedBinaryMessage(648), 0x123, timestamp)
        {
            @Override public HeaderData getHeaderData() { return data; }
        };
    }

    private static final IAudioCodec CODEC = new IAudioCodec()
    {
        @Override public String getCodecName() { return "TEST IMBE"; }
        @Override public float[] getAudio(byte[] frame) { return new float[160]; }
        @Override public IAudioWithMetadata getAudioWithMetadata(byte[] frame)
        {
            return new IAudioWithMetadata()
            {
                @Override public float[] getAudio() { return new float[160]; }
                @Override public boolean hasMetadata() { return false; }
                @Override public Map<String,String> getMetadata() { return Map.of(); }
            };
        }
        @Override public void reset() { }
    };

    private static P25TrafficChannelManager trafficChannelManager(P25P1DecoderState decoderState) throws Exception
    {
        Field field = P25P1DecoderState.class.getDeclaredField("mTrafficChannelManager");
        field.setAccessible(true);
        return (P25TrafficChannelManager)field.get(decoderState);
    }
}

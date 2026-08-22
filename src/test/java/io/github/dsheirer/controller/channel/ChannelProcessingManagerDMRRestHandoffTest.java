/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.codec.mbe.JmbeAudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.IAudioCallProvider;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.MultiChannelState;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.ChannelEvent.Event;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.message.MessageProviderModule;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DMRChannelConfigurationTransitionNotification;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DMRDecoder;
import io.github.dsheirer.module.decode.dmr.DMRDecoderState;
import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.audio.DMRAudioModule;
import io.github.dsheirer.module.decode.dmr.channel.DmrRestLsn;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.type.DataType;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceAMessage;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitor;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorPauseRequest;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorResumeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import jmbe.iface.IAudioCodec;
import jmbe.iface.IAudioWithMetadata;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerDMRRestHandoffTest
{
    private static final long CURRENT_FREQUENCY = 451_000_000L;
    private static final long FIRST_REST_FREQUENCY = 452_000_000L;
    private static final long SECOND_REST_FREQUENCY = 453_000_000L;
    private static final String AUDIO_ALIAS_LIST_NAME = "DMR Audio Policy";
    private static final String AUDIO_STREAM_NAME = "Test Stream";

    @Test
    void retriesReplacementStartAndKeepsConvertedTrafficEventsOnOneRoute() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), null,
            new TestComplexSource(FIRST_REST_FREQUENCY), new TestComplexSource(SECOND_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(2);
        CountDownLatch parentStarts = new CountDownLatch(3);
        List<IDecodeEvent> deliveredEvents = new CopyOnWriteArrayList<>();
        manager.addChannelEventListener(event ->
        {
            if(event.getChannel() == parent && event.getEvent() == Event.NOTIFICATION_PROCESSING_START)
            {
                parentStarts.countDown();
            }
        });
        manager.addDecodeEventListener(deliveredEvents::add);

        try
        {
            manager.start(parent);
            ProcessingChain firstChain = manager.getProcessingChain(parent);
            assertNotNull(firstChain);
            List<IDecodeEvent> firstChainEvents = captureDecodeEvents(firstChain);
            DMRTrafficChannelManager trafficManager = trafficManager(firstChain);
            TestDecodeEventProvider trafficEventProvider = new TestDecodeEventProvider();
            firstChain.addModule(trafficEventProvider);
            DecodeEvent activeUpdate = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
                .channel(restChannel(1, CURRENT_FREQUENCY))
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .build();
            trafficEventProvider.receive(activeUpdate);
            trafficManager.receiveTrafficChannelEvent(activeUpdate);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.firstUnavailable.await(5, TimeUnit.SECONDS),
                "replacement source failure was not observed");
            assertTrue(tunerManager.firstRestSource.await(5, TimeUnit.SECONDS),
                "replacement source was not retried");
            assertTrue(awaitCount(parentStarts, 1, 5), "first replacement channel did not start");

            ProcessingChain firstReplacement = manager.getProcessingChain(parent);
            assertNotNull(firstReplacement);
            List<IDecodeEvent> firstReplacementEvents = captureDecodeEvents(firstReplacement);
            assertNotSame(firstChain, firstReplacement);
            assertSame(trafficManager, trafficManager(firstReplacement));
            assertFalse(firstChain.getModules().stream().anyMatch(module -> module == trafficManager));
            assertTrue(firstReplacementEvents.isEmpty());
            assertTrue(firstReplacement.getMessageHistory().getItems().isEmpty());
            DecodeEvent managerOwned = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_500L)
                .channel(restChannel(3, FIRST_REST_FREQUENCY))
                .identifiers(identifiers(102, 92))
                .timeslot(1)
                .details("manager-owned parent event")
                .build();
            trafficManager.broadcast(managerOwned);
            assertEquals(List.of(managerOwned), firstReplacementEvents);
            assertEquals(List.of(activeUpdate), firstChainEvents);

            activeUpdate.update(2_000L);
            trafficEventProvider.receive(activeUpdate);
            trafficManager.receiveTrafficChannelEvent(activeUpdate);

            assertEquals(List.of(activeUpdate, managerOwned, activeUpdate), deliveredEvents);
            assertEquals(List.of(activeUpdate, activeUpdate), firstChainEvents);
            assertEquals(List.of(managerOwned), firstReplacementEvents);

            trafficManager.requestRestChannelHandoff(parent, FIRST_REST_FREQUENCY,
                restChannel(5, SECOND_REST_FREQUENCY));
            assertTrue(tunerManager.secondRestSource.await(5, TimeUnit.SECONDS),
                "second rest-channel replacement source was not requested");
            assertTrue(parentStarts.await(5, TimeUnit.SECONDS), "second replacement channel did not start");
            assertEquals(3, manager.getProcessingChainsByConfiguration(parent.getConfigurationId(), null).size());
            assertSame(trafficManager, trafficManager(manager.getProcessingChain(parent)));
            assertEquals(4, tunerManager.getSourceRequests());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void convertedChainPublishesFunctionalObserverIdentityAndDropsControlOnlyModules() throws Exception
    {
        NowPlayingPreference nowPlayingPreference = new NowPlayingPreference(type -> {})
        {
            @Override
            public int getTrafficGrantAgeOutMilliseconds()
            {
                return MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
            }

            @Override
            public boolean isClearVoiceDecodeQualityOnCallEnd()
            {
                return true;
            }
        };
        UserPreferences preferences = new UserPreferences()
        {
            @Override
            public NowPlayingPreference getNowPlayingPreference()
            {
                return nowPlayingPreference;
            }
        };
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);
        List<ChannelDecodeObservation> observations = new CopyOnWriteArrayList<>();
        List<ControlChannelQualitySnapshot> qualitySnapshots = new CopyOnWriteArrayList<>();
        DecodeEventViewService eventViewService = new DecodeEventViewService(manager, aliasModel);
        Listener<DecodeEventViewService.EventView> eventDemand = event -> {};
        eventViewService.addListener(eventDemand);
        manager.addChannelDecodeEventListener((channel, event) ->
            observations.add(new ChannelDecodeObservation(channel, event)));
        manager.addChannelDecodeEventListener(eventViewService.getDecodeEventListener());
        manager.addControlChannelQualityListener(qualitySnapshots::add);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            assertNotNull(original);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            ControlChannelQualityMonitor oldQualityMonitor = modules(original,
                ControlChannelQualityMonitor.class).getFirst();
            ChannelRotationMonitor oldRotationMonitor = modules(original, ChannelRotationMonitor.class).getFirst();
            assertSame(oldQualityMonitor.getMessageListener(), oldQualityMonitor.getMessageListener());
            assertSame(oldQualityMonitor.getSourceEventListener(), oldQualityMonitor.getSourceEventListener());
            assertSame(oldQualityMonitor.getHeartbeatListener(), oldQualityMonitor.getHeartbeatListener());
            TestDecodeEventProvider decodeProvider = new TestDecodeEventProvider();
            TestAudioCallProvider audioProvider = new TestAudioCallProvider();
            original.addModule(decodeProvider);
            original.addModule(audioProvider);
            DecodeEvent activeCall = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
                .channel(restChannel(1, CURRENT_FREQUENCY))
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .build();
            decodeProvider.receive(activeCall);
            trafficManager.receiveTrafficChannelEvent(activeCall);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null && manager.getProcessingChain(parent) != original, 5));

            Channel converted = manager.getChannel(original);
            ProcessingChain replacement = manager.getProcessingChain(parent);
            assertNotNull(converted);
            assertNotNull(replacement);
            assertTrue(converted.isTrafficChannel());
            assertFalse(original.getModules().contains(oldQualityMonitor));
            assertFalse(original.getModules().contains(oldRotationMonitor));
            assertTrue(modules(replacement, ControlChannelQualityMonitor.class).size() == 1);
            assertTrue(modules(replacement, ChannelRotationMonitor.class).size() == 1);
            assertTrue(qualitySnapshots.stream().anyMatch(snapshot -> !snapshot.active() &&
                snapshot.channel() == parent && snapshot.frequencyHz() == CURRENT_FREQUENCY),
                "converted chain did not close its former control-channel quality publication");
            long oldMonitorClosureCount = qualitySnapshots.stream()
                .filter(snapshot -> !snapshot.active() && snapshot.channel() == parent &&
                    snapshot.frequencyHz() == CURRENT_FREQUENCY)
                .count();
            original.broadcast(SourceEvent.frequencyChange(original.getSource(), SECOND_REST_FREQUENCY));
            assertEquals(oldMonitorClosureCount, qualitySnapshots.stream()
                    .filter(snapshot -> !snapshot.active() && snapshot.channel() == parent &&
                        snapshot.frequencyHz() == CURRENT_FREQUENCY)
                    .count(),
                "removed quality monitor still received source events from the converted traffic chain");

            DecodeEvent replacementEvent = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_500L)
                .channel(restChannel(3, FIRST_REST_FREQUENCY))
                .identifiers(identifiers(102, 92))
                .timeslot(1)
                .build();
            trafficManager.broadcast(replacementEvent);
            activeCall.update(2_000L);
            decodeProvider.receive(activeCall);
            trafficManager.receiveTrafficChannelEvent(activeCall);
            DecodeEvent descriptorless = DecodeEvent.builder(DecodeEventType.STATUS, 2_100L)
                .details("descriptorless converted event")
                .timeslot(1)
                .build();
            decodeProvider.receive(descriptorless);

            assertEquals(4, observations.size());
            assertSame(parent, observations.get(0).channel());
            assertSame(parent, observations.get(1).channel());
            assertSame(converted, observations.get(2).channel());
            assertSame(converted, observations.get(3).channel());
            DecodeEventViewService.Scope oldFrequencyScope = new DecodeEventViewService.Scope(
                parent.getConfigurationId(), CURRENT_FREQUENCY, 1);
            assertTrue(awaitCondition(() -> eventViewService.snapshot(oldFrequencyScope).stream()
                .anyMatch(event -> "descriptorless converted event".equals(event.details()) &&
                    event.frequencyHz() == CURRENT_FREQUENCY), 5),
                "descriptorless converted event lost the old chain's source frequency");

            IdentifierCollection audioIdentifiers = new IdentifierCollection(
                List.of(FrequencyConfigurationIdentifier.create(CURRENT_FREQUENCY)));
            audioIdentifiers.setTimeslot(1);
            VoiceCallQuality voiceQuality = new VoiceCallQuality(50, 0, 0, 0, 2, 47);
            AudioCallSnapshot audioSnapshot = new AudioCallSnapshot(new AudioCallId(1, 1, 1), null, null,
                audioIdentifiers, Set.of(), 1_000L, 2_200L, 1, 1, 1_000L, 2_200L,
                true, false, false, false, false, null, voiceQuality);
            audioProvider.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, audioSnapshot,
                new float[160]));
            assertTrue(awaitCondition(() -> {
                ChannelActivitySnapshot.Row row = activityRow(manager, CURRENT_FREQUENCY, 1);
                return row != null && voiceQuality.equals(row.voiceQuality());
            }, 5), "converted traffic activity row did not receive voice quality");

            audioProvider.receive(new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, audioSnapshot, null));
            assertTrue(awaitCondition(() -> {
                ChannelActivitySnapshot.Row row = activityRow(manager, CURRENT_FREQUENCY, 1);
                return row != null && row.voiceQuality() == null;
            }, 5), "converted traffic activity row did not clear terminal voice quality");
        }
        finally
        {
            eventViewService.removeListener(eventDemand);
            eventViewService.close();
            manager.close();
        }
    }

    @Test
    void convertedCallPreservesAudioPolicyAndCanFinishDuringRetryBeforeTheRestFollowerStarts() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = aliasModelWithAudioPolicy();
        TestComplexSource failedReplacementSource = new TestComplexSource(FIRST_REST_FREQUENCY, true);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), null, failedReplacementSource,
            new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(1);
        parent.setAliasListName(AUDIO_ALIAS_LIST_NAME);
        List<AudioCallEvent> audioEvents = new CopyOnWriteArrayList<>();
        manager.addAudioCallListener(audioEvents::add);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            assertNotNull(original);
            List<IDecodeEvent> originalDecodeEvents = captureDecodeEvents(original);
            MultiChannelState originalChannelState =
                assertInstanceOf(MultiChannelState.class, original.getChannelState());
            Source originalSource = original.getSource();
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            List<Module> originalFunctionalModules = original.getModules().stream()
                .filter(module -> module != trafficManager)
                .filter(module -> !(module instanceof ControlChannelQualityMonitor))
                .filter(module -> !(module instanceof ChannelRotationMonitor))
                .toList();
            DMRDecoder originalDecoder = modules(original, DMRDecoder.class).getFirst();
            List<DMRDecoderState> originalDecoderStates = modules(original, DMRDecoderState.class);
            List<DMRAudioModule> originalAudioModules = modules(original, DMRAudioModule.class);
            DMRAudioModule originalAudioModule = originalAudioModules.getFirst();
            TestAudioCodec originalAudioCodec = new TestAudioCodec();
            setAudioCodec(originalAudioModule, originalAudioCodec);
            MessageProviderModule messageProvider = new MessageProviderModule();
            original.addModule(messageProvider);
            original.getChannelState().updateChannelStateIdentifiers(new IdentifierUpdateNotification(
                DMRRadio.createFrom(101), IdentifierUpdateNotification.Operation.ADD, 1));
            original.getChannelState().updateChannelStateIdentifiers(new IdentifierUpdateNotification(
                DMRTalkgroup.create(91), IdentifierUpdateNotification.Operation.ADD, 1));
            DMRDecoderState callState = originalDecoderStates.getFirst();
            DecodeEvent activeCall = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
                .channel(restChannel(1, CURRENT_FREQUENCY))
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .build();
            callState.setCurrentCallEvent((DecodeEvent)activeCall);
            HandoffSubscriber handoffSubscriber = new HandoffSubscriber();
            original.getEventBus().register(handoffSubscriber);
            originalChannelState.getDecoderStateListener().receive(new DecoderStateEvent(callState,
                DecoderStateEvent.Event.CONTINUATION, State.CALL, 1));
            originalAudioModule.receive(voiceMessage(1_000L));
            List<AudioCallEvent> firstAudioFrames = audioFrames(audioEvents);
            assertEquals(3, firstAudioFrames.size());
            AudioCallId preservedCallId = firstAudioFrames.getFirst().callId();
            assertNotNull(preservedCallId);
            assertAudioPolicy(firstAudioFrames.getFirst().snapshot());

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.firstUnavailable.await(5, TimeUnit.SECONDS),
                "replacement source failure was not observed");
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());

            DMRRestChannelHandoffRequest request = handoffSubscriber.requests.getFirst();
            Channel converted = manager.getChannel(original);
            assertNotNull(converted);
            assertTrue(converted.isTrafficChannel());
            assertNotSame(parent, converted);
            assertTrue(original.isProcessing(), "former REST call stopped when the replacement tuner was unavailable");
            assertSame(originalSource, original.getSource(), "former REST source was replaced during conversion");
            assertTrue(original.getModules().containsAll(originalFunctionalModules),
                "former REST decoder/audio/recorder modules were replaced during conversion");
            assertSame(originalDecoder, modules(original, DMRDecoder.class).getFirst());
            assertSameInstances(originalDecoderStates, modules(original, DMRDecoderState.class));
            assertSameInstances(originalAudioModules, modules(original, DMRAudioModule.class));
            assertSame(originalAudioModule, modules(original, DMRAudioModule.class).getFirst());
            assertSame(originalAudioCodec, originalAudioModule.getAudioCodec());

            originalAudioModule.receive(voiceMessage(1_500L));
            List<AudioCallEvent> allAudioFrames = audioFrames(audioEvents);
            assertEquals(6, allAudioFrames.size());
            assertTrue(allAudioFrames.stream().allMatch(event -> preservedCallId.equals(event.callId())),
                "the preserved audio call restarted after REST conversion");
            assertAudioPolicy(allAudioFrames.getLast().snapshot());

            messageProvider.receive(terminator(2_000L));
            assertEquals(List.of(activeCall), originalDecodeEvents,
                "the preserved former-REST call did not complete on its original chain");
            List<AudioCallEvent> completions = audioCompletions(audioEvents, preservedCallId);
            assertEquals(1, completions.size(), "the preserved audio call did not complete exactly once");
            assertTrue(completions.getFirst().snapshot().complete());
            assertFalse(completions.getFirst().continuationExpected());
            assertAudioPolicy(completions.getFirst().snapshot());

            //This represents the preserved former-REST call finishing naturally.  Its source is now free, but the
            //pending REST move must remain live so the replacement can use that newly available capacity.
            manager.stop(converted);
            assertFalse(original.isProcessing());
            assertEquals(1, audioCompletions(audioEvents, preservedCallId).size(),
                "stopping the converted chain duplicated the completed audio call");
            assertNull(decodeEventListener(trafficManager),
                "stopped former-REST chain retained the manager event route");
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount(),
                "finishing the converted call cancelled the REST follower");

            //A later candidate can fail after the former call has ended. It must not reinstall a route to the disposed
            //chain, and the pending REST move must survive for another retry.
            assertTrue(manager.retryDmrRestChannelHandoff(request));
            assertTrue(awaitCondition(() -> failedReplacementSource.getStopCount() == 1, 5));
            assertNull(decodeEventListener(trafficManager),
                "failed candidate reattached the manager to the disposed former-REST chain");
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());

            //Deterministically fire the next retry instead of waiting for the 10-second test delay.
            assertTrue(manager.retryDmrRestChannelHandoff(request));
            assertTrue(awaitCondition(() -> manager.getProcessingChain(parent) != null &&
                    manager.getProcessingChain(parent) != original &&
                    manager.getPendingDmrRestChannelAttemptCount() == 0, 5),
                "REST follower did not start after the former call released its source");

            ProcessingChain replacement = manager.getProcessingChain(parent);
            assertNotNull(replacement);
            assertNotSame(original, replacement);
            assertSame(trafficManager, trafficManager(replacement));
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
            assertFalse(trafficManager.isPendingRestHandoff(request));
            assertNotNull(decodeEventListener(trafficManager));
            assertEquals(4, tunerManager.getSourceRequests());
            assertEquals(1, manager.getChannelEventListenerCount(),
                "the temporary detached-manager route remained after replacement startup");
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void oneShotRestMoveSurvivesAnotherSitesBlockedLifecycleWork() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        BlockingReplacementTunerManager tunerManager = new BlockingReplacementTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(CURRENT_FREQUENCY),
            new TestComplexSource(FIRST_REST_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel firstParent = channel(1);
        Channel secondParent = channel(1);

        try
        {
            manager.start(firstParent);
            manager.start(secondParent);
            ProcessingChain firstOriginal = manager.getProcessingChain(firstParent);
            ProcessingChain secondOriginal = manager.getProcessingChain(secondParent);
            DMRTrafficChannelManager firstOwner = trafficManager(firstOriginal);
            DMRTrafficChannelManager secondOwner = trafficManager(secondOriginal);
            CountDownLatch restFollowersStarted = new CountDownLatch(2);
            manager.addChannelEventListener(event ->
            {
                if((event.getChannel() == firstParent || event.getChannel() == secondParent) &&
                    event.getEvent() == Event.NOTIFICATION_PROCESSING_START)
                {
                    restFollowersStarted.countDown();
                }
            });

            firstOwner.requestRestChannelHandoff(firstParent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.blockedReplacementEntered.await(5, TimeUnit.SECONDS),
                "first site's replacement startup did not block");

            CountDownLatch secondOfferReturned = new CountDownLatch(1);
            Thread producer = Thread.ofPlatform().name("test-second-site-rest-nomination").start(() ->
            {
                secondOwner.requestRestChannelHandoff(secondParent, CURRENT_FREQUENCY,
                    restChannel(3, FIRST_REST_FREQUENCY));
                secondOfferReturned.countDown();
            });
            assertTrue(secondOfferReturned.await(1, TimeUnit.SECONDS),
                "second site's decoder callback waited for blocked lifecycle work");
            producer.join(1_000);

            tunerManager.releaseBlockedReplacement.countDown();
            assertTrue(restFollowersStarted.await(15, TimeUnit.SECONDS),
                "one-shot second-site REST move was not retained after the worker unblocked");
            assertNotSame(firstOriginal, manager.getProcessingChain(firstParent));
            assertNotSame(secondOriginal, manager.getProcessingChain(secondParent));

            assertEquals(4, tunerManager.getSourceRequests());
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
        }
        finally
        {
            tunerManager.releaseBlockedReplacement.countDown();
            manager.close();
        }
    }

    @Test
    void stopDuringReplacementRetryCancelsAndReclaimsTheConvertedChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), null);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(1);
        List<IDecodeEvent> managerEvents = new CopyOnWriteArrayList<>();
        manager.addDecodeEventListener(managerEvents::add);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            List<IDecodeEvent> originalEvents = captureDecodeEvents(original);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber handoffSubscriber = new HandoffSubscriber();
            original.getEventBus().register(handoffSubscriber);
            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.firstUnavailable.await(5, TimeUnit.SECONDS));
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());
            DMRRestChannelHandoffRequest request = handoffSubscriber.requests.getFirst();
            DecodeEvent rejection = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_500L)
                .channel(restChannel(1, CURRENT_FREQUENCY))
                .identifiers(identifiers(101, 91))
                .timeslot(1)
                .details("manager-owned retry event")
                .build();
            trafficManager.broadcast(rejection);
            assertEquals(List.of(rejection), managerEvents);
            assertEquals(List.of(rejection), originalEvents);

            manager.stop(parent);

            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
            assertFalse(trafficManager.isPendingRestHandoff(request));
            assertFalse(manager.isProcessing());
            assertEquals(2, tunerManager.getSourceRequests());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void parentStopDuringReplacementRetryStopsAndReclaimsPreexistingSiblingTraffic() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(SECOND_REST_FREQUENCY), null);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(2);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber handoffSubscriber = new HandoffSubscriber();
            original.getEventBus().register(handoffSubscriber);
            trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            ProcessingChain sibling = manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), SECOND_REST_FREQUENCY).getFirst();
            Channel siblingChannel = manager.getChannel(sibling);
            assertNotNull(siblingChannel);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> tunerManager.getSourceRequests() == 3 &&
                manager.getPendingDmrRestChannelAttemptCount() == 1, 5));
            DMRRestChannelHandoffRequest request = handoffSubscriber.requests.getFirst();
            Channel convertedChannel = manager.getChannel(original);
            assertNotNull(convertedChannel);
            assertNotSame(siblingChannel, convertedChannel);
            assertTrue(sibling.isProcessing());
            assertEquals(3, manager.getChannelEventListenerCount(),
                "converted chain, sibling chain, and transient owner route should be attached during retry");

            manager.stop(parent);

            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
            assertFalse(trafficManager.isPendingRestHandoff(request));
            assertFalse(original.isProcessing());
            assertFalse(sibling.isProcessing());
            assertFalse(manager.isProcessing());
            assertEquals(0, manager.getChannelEventListenerCount(),
                "the detached owner route was retained after parent-stop cleanup");

            StartRequestSubscriber startSubscriber = new StartRequestSubscriber();
            EventBus accountingBus = new EventBus();
            accountingBus.register(startSubscriber);
            trafficManager.setInterModuleEventBus(accountingBus);

            try
            {
                trafficManager.processChannelGrant(restChannel(3, FIRST_REST_FREQUENCY), identifiers(102, 92),
                    Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);
                trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(103, 93),
                    Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 3_000L, false);

                assertEquals(2, startSubscriber.requests.size(),
                    "parent-stop cleanup did not reclaim both pooled traffic channels");
                List<Channel> reclaimed = startSubscriber.requests.stream()
                    .map(ChannelStartProcessingRequest::getChannel).toList();
                assertTrue(reclaimed.contains(siblingChannel));
                assertTrue(reclaimed.contains(convertedChannel));
            }
            finally
            {
                trafficManager.setInterModuleEventBus(null);
            }
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void teardownReadingOldStandardChannelDuringConversionIsReconciledAndPooledChannelIsReusable() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY),
            new TestComplexSource(CURRENT_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        BlockingStandardChannel parent = blockingChannel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            MultiChannelState channelState = assertInstanceOf(MultiChannelState.class, original.getChannelState());
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            AtomicInteger disableRequests = new AtomicInteger();
            AtomicReference<Channel> convertedChannel = new AtomicReference<>();
            original.addChannelEventListener(event ->
            {
                if(event.getEvent() == Event.REQUEST_DISABLE)
                {
                    disableRequests.incrementAndGet();
                    convertedChannel.set(event.getChannel());
                }
            });
            moveToFade(channelState, 1);

            Thread decoder = new Thread(() -> channelState.getDecoderStateListener().receive(
                new DecoderStateEvent(this, DecoderStateEvent.Event.END, State.TEARDOWN, 1)),
                BlockingStandardChannel.DECODER_THREAD_NAME);
            decoder.start();
            assertTrue(parent.mTrafficDecisionEntered.await(5, TimeUnit.SECONDS));

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null &&
                manager.getProcessingChain(parent).getSource().getFrequency() == FIRST_REST_FREQUENCY, 5),
                "the replacement rest channel did not start after reconciling teardown");

            parent.mReleaseTrafficDecision.countDown();
            decoder.join(5_000);
            assertFalse(decoder.isAlive());
            assertEquals(1, disableRequests.get());
            assertNotNull(convertedChannel.get());
            assertFalse(original.isProcessing());
            assertFalse(convertedChannel.get().isProcessing(),
                "conversion flag publication revived the concurrently torn-down traffic channel");

            trafficManager.processChannelGrant(restChannel(1, CURRENT_FREQUENCY), identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);
            assertTrue(awaitCondition(() -> manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), CURRENT_FREQUENCY).size() == 1, 5));
            ProcessingChain reused = manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), CURRENT_FREQUENCY).getFirst();
            assertSame(convertedChannel.get(), manager.getChannel(reused));
        }
        finally
        {
            parent.mReleaseTrafficDecision.countDown();
            manager.close();
        }
    }

    @Test
    void successfulHandoffWithNoActiveCallReclaimsIdleConvertedPooledChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY),
            new TestComplexSource(CURRENT_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            MultiChannelState channelState = assertInstanceOf(MultiChannelState.class, original.getChannelState());
            DMRTrafficChannelManager trafficManager = trafficManager(original);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null && manager.getProcessingChain(parent) != original, 5));

            Channel converted = manager.getChannel(original);
            assertNotNull(converted);
            assertTrue(original.isProcessing());
            assertFalse(channelState.isTeardownState());

            //There was no active call.  Exercise the normal idle heartbeat path after conversion and verify that the
            //converted traffic chain is reclaimed instead of remaining in the traffic pool indefinitely.
            Object heartbeat = new Object();
            channelState.getDecoderStateListener().receive(new DecoderStateEvent(heartbeat,
                DecoderStateEvent.Event.END, State.FADE, 1));
            channelState.getDecoderStateListener().receive(new DecoderStateEvent(heartbeat,
                DecoderStateEvent.Event.END, State.TEARDOWN, 1));
            assertTrue(awaitCondition(() -> !original.isProcessing(), 5));
            assertFalse(converted.isProcessing(),
                "conversion flag publication revived an idle traffic channel after teardown");

            trafficManager.processChannelGrant(restChannel(1, CURRENT_FREQUENCY), identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);
            assertTrue(awaitCondition(() -> manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), CURRENT_FREQUENCY).size() == 1, 5));
            ProcessingChain reused = manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), CURRENT_FREQUENCY).getFirst();
            assertSame(converted, manager.getChannel(reused));
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void successfulHandoffRemovesTransientRouteAndStoppingBothChainsRemovesAllListeners() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            assertEquals(1, manager.getChannelEventListenerCount());
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null && manager.getProcessingChain(parent) != original, 5));

            Channel converted = manager.getChannel(original);
            assertNotNull(converted);
            assertEquals(2, manager.getChannelEventListenerCount(),
                "only the converted and replacement chains should remain after successful handoff");

            manager.stop(converted);
            assertEquals(1, manager.getChannelEventListenerCount());
            manager.stop(parent);
            assertEquals(0, manager.getChannelEventListenerCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void closeDuringReplacementRetryClearsCoordinatorAttemptChainsAndListeners() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), null);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(manager.getProcessingChain(parent));
            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.firstUnavailable.await(5, TimeUnit.SECONDS));
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());
            assertEquals(2, manager.getChannelEventListenerCount());

            manager.close();

            assertTrue(manager.isDmrRestChannelHandoffCoordinatorClosed());
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
            assertFalse(manager.isProcessing());
            assertEquals(0, manager.getChannelEventListenerCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void siblingTrafficStopDuringReplacementRetryReturnsItsPooledChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(SECOND_REST_FREQUENCY), null,
            new TestComplexSource(FIRST_REST_FREQUENCY), new TestComplexSource(SECOND_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(2);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber handoffSubscriber = new HandoffSubscriber();
            original.getEventBus().register(handoffSubscriber);
            trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            ProcessingChain sibling = manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), SECOND_REST_FREQUENCY).getFirst();
            Channel pooledChannel = manager.getChannel(sibling);
            assertNotNull(pooledChannel);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> tunerManager.getSourceRequests() == 3 &&
                manager.getPendingDmrRestChannelAttemptCount() == 1, 5));

            manager.stop(pooledChannel);
            assertTrue(manager.retryDmrRestChannelHandoff(handoffSubscriber.requests.getFirst()));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null &&
                manager.getProcessingChain(parent).getSource().getFrequency() == FIRST_REST_FREQUENCY, 5),
                "the replacement rest channel did not start after the sibling stopped");
            trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);

            assertEquals(5, tunerManager.getSourceRequests(),
                "the sibling traffic channel was not returned to the pool during retry");
            ProcessingChain reused = manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), SECOND_REST_FREQUENCY).getFirst();
            assertSame(pooledChannel, manager.getChannel(reused));
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void abortDoesNotStopAReusedPooledChannelIncarnation() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), null, new TestComplexSource(CURRENT_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.firstUnavailable.await(5, TimeUnit.SECONDS));

            Channel converted = manager.getChannel(original);
            assertNotNull(converted);
            assertTrue(converted.isTrafficChannel());
            manager.stop(converted);

            //Reuse the same pooled Channel object with a different ProcessingChain incarnation while the replacement
            //attempt is still waiting.  Aborting the old attempt must key cleanup to the old chain, not the object.
            manager.start(converted);
            ProcessingChain reused = manager.getProcessingChain(converted);
            assertNotNull(reused);
            assertNotSame(original, reused);

            manager.stop(parent);

            assertSame(reused, manager.getProcessingChain(converted));
            assertTrue(reused.isProcessing());
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void stoppedQueuedRequestCannotConvertARestartedChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(CURRENT_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager oldOwner = trafficManager(original);

            //Holding the lifecycle monitor keeps the worker queued while stop invalidates the old owner's authority.
            synchronized(manager)
            {
                oldOwner.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                    restChannel(3, FIRST_REST_FREQUENCY));
                manager.stop(parent);
            }

            manager.start(parent);
            ProcessingChain restarted = manager.getProcessingChain(parent);
            assertNotNull(restarted);
            assertNotSame(oldOwner, trafficManager(restarted));
            assertEquals(2, tunerManager.getSourceRequests());
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void newerQueuedRestTargetSupersedesBeforeTheWorkerClaimsTheMove() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(SECOND_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(manager.getProcessingChain(parent));

            synchronized(manager)
            {
                trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                    restChannel(3, FIRST_REST_FREQUENCY));
                trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                    restChannel(5, SECOND_REST_FREQUENCY));
            }

            assertTrue(tunerManager.secondSourceRequest.await(5, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0, 5));
            ProcessingChain replacement = manager.getProcessingChain(parent);
            assertNotNull(replacement);
            assertEquals(SECOND_REST_FREQUENCY, replacement.getSource().getFrequency());
            assertEquals(2, tunerManager.getSourceRequests());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void inFlightGrantPreventsSuspensionAndManagerCommitUntilItsAuthorityLeaseIsReleased() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);
        CountDownLatch authorityAcquired = new CountDownLatch(1);
        CountDownLatch releaseGrant = new CountDownLatch(1);
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        Thread decoderThread = null;
        AccountingTransitionObserver observer = null;
        ProcessingChain original = null;

        try
        {
            manager.start(parent);
            original = manager.getProcessingChain(parent);
            assertNotNull(original);
            ProcessingChain expectedOriginal = original;
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            DMRDecoderState decoderState = firstDecoderState(original);
            observer = new AccountingTransitionObserver(parent, trafficManager);
            original.getEventBus().register(observer);
            setAfterChannelGrantAuthorityAcquired(decoderState, () ->
            {
                authorityAcquired.countDown();
                awaitOrFail(releaseGrant, "Timed out waiting to release in-flight DMR grant");
            });
            decoderThread = new Thread(() ->
            {
                try
                {
                    decoderState.receive(channelGrant(CURRENT_FREQUENCY));
                }
                catch(Throwable throwable)
                {
                    decoderFailure.set(throwable);
                }
            }, "test-cpm-in-flight-dmr-grant");
            decoderThread.start();
            assertTrue(authorityAcquired.await(5, TimeUnit.SECONDS));

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));

            assertTrue(observer.mSuspendObserved.await(5, TimeUnit.SECONDS));
            assertTrue(observer.mRollbackObserved.await(5, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0, 5));
            assertNull(observer.mFailure.get());
            assertEquals(CURRENT_FREQUENCY, observer.mPreferredAtSuspend);
            assertSame(parent, observer.mAllocationAtSuspend,
                "manager accounting was committed before both decoder states suspended");
            assertEquals(CURRENT_FREQUENCY, observer.mPreferredAtRollback);
            assertSame(parent, observer.mAllocationAtRollback,
                "the claimed pooled channel was not returned before authority rollback");
            assertNull(observer.mRestAllocationAtRollback,
                "the target-frequency reservation remained visible when authority reopened");
            assertSame(original, manager.getProcessingChain(parent));
            assertEquals(1, tunerManager.getSourceRequests());

            releaseGrant.countDown();
            decoderThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(decoderThread.isAlive());
            assertNull(decoderFailure.get());
            assertEquals(1, tunerManager.getSourceRequests(),
                "the current-frequency grant unexpectedly requested a traffic source");

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null && manager.getProcessingChain(parent) != expectedOriginal &&
                manager.getProcessingChain(parent).getSource().getFrequency() == FIRST_REST_FREQUENCY, 5),
                "the pooled channel was not reusable after the blocked-suspension abort");
            assertEquals(2, tunerManager.getSourceRequests());
        }
        finally
        {
            releaseGrant.countDown();

            if(decoderThread != null)
            {
                decoderThread.join(TimeUnit.SECONDS.toMillis(5));
            }

            if(original != null && observer != null)
            {
                original.getEventBus().unregister(observer);
            }

            manager.close();
        }
    }

    @Test
    void postCommitAbortRestoresAccountingBeforeRollbackAndRejectsAStaleGrant() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = trackingChannel(1);
        TrackingSourceConfig sourceConfig = (TrackingSourceConfig)parent.getSourceConfiguration();
        CountDownLatch authorityCheckReached = new CountDownLatch(1);
        CountDownLatch releaseGrant = new CountDownLatch(1);
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        Thread decoderThread = null;
        AccountingTransitionObserver observer = null;
        ProcessingChain original = null;
        io.github.dsheirer.channel.state.AbstractChannelState.ChannelConfigurationTransition blocker = null;

        try
        {
            manager.start(parent);
            original = manager.getProcessingChain(parent);
            assertNotNull(original);
            ProcessingChain expectedOriginal = original;
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            DMRDecoderState decoderState = firstDecoderState(original);
            observer = new AccountingTransitionObserver(parent, trafficManager);
            original.getEventBus().register(observer);
            blocker = original.beginChannelConfigurationTransition(
                new Channel("Injected Competing Transition", Channel.ChannelType.TRAFFIC));
            setBeforeChannelGrantAuthorityCheck(decoderState, () ->
            {
                authorityCheckReached.countDown();
                awaitOrFail(releaseGrant, "Timed out waiting to release stale DMR grant");
            });
            decoderThread = new Thread(() ->
            {
                try
                {
                    decoderState.receive(channelGrant(SECOND_REST_FREQUENCY));
                }
                catch(Throwable throwable)
                {
                    decoderFailure.set(throwable);
                }
            }, "test-cpm-stale-dmr-grant");
            decoderThread.start();
            assertTrue(authorityCheckReached.await(5, TimeUnit.SECONDS));

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));

            assertTrue(observer.mRollbackObserved.await(5, TimeUnit.SECONDS));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0, 5));
            assertNull(observer.mFailure.get());
            assertEquals(CURRENT_FREQUENCY, observer.mPreferredAtSuspend);
            assertSame(parent, observer.mAllocationAtSuspend);
            assertEquals(CURRENT_FREQUENCY, observer.mPreferredAtRollback);
            assertSame(parent, observer.mAllocationAtRollback,
                "decoder authority was reopened before manager accounting was restored");
            assertNull(observer.mRestAllocationAtRollback,
                "the target-frequency reservation remained visible when authority reopened");
            assertTrue(sourceConfig.mPreferredFrequencies.size() >= 3);
            assertEquals(FIRST_REST_FREQUENCY,
                sourceConfig.mPreferredFrequencies.get(sourceConfig.mPreferredFrequencies.size() - 2),
                "the forced failure did not occur after manager commit");
            assertEquals(CURRENT_FREQUENCY, sourceConfig.mPreferredFrequencies.getLast());

            original.rollbackChannelConfigurationTransition(blocker);
            blocker = null;
            releaseGrant.countDown();
            decoderThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(decoderThread.isAlive());
            assertNull(decoderFailure.get());
            assertEquals(1, tunerManager.getSourceRequests(),
                "a callback captured before suspension regained manager authority after rollback");
            assertTrue(manager
                .getProcessingChainsByConfiguration(parent.getConfigurationId(), SECOND_REST_FREQUENCY).isEmpty());

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                manager.getProcessingChain(parent) != null && manager.getProcessingChain(parent) != expectedOriginal &&
                manager.getProcessingChain(parent).getSource().getFrequency() == FIRST_REST_FREQUENCY, 5),
                "manager rollback did not make the pooled channel reusable for a retry");
            assertEquals(2, tunerManager.getSourceRequests());
        }
        finally
        {
            releaseGrant.countDown();

            if(decoderThread != null)
            {
                decoderThread.join(TimeUnit.SECONDS.toMillis(5));
            }

            if(original != null && blocker != null)
            {
                original.rollbackChannelConfigurationTransition(blocker);
            }

            if(original != null && observer != null)
            {
                original.getEventBus().unregister(observer);
            }

            manager.close();
        }
    }

    @Test
    void unstableSourceFenceAbortsBeforeConversionAndReleasesTheReservedTrafficChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource currentSource = new TestComplexSource(CURRENT_FREQUENCY);
        currentSource.setRotationPauseFrequency(SECOND_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences, currentSource,
            new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber subscriber = new HandoffSubscriber();
            original.getEventBus().register(subscriber);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                !trafficManager.isPendingRestHandoff(request), 5));

            assertSame(original, manager.getProcessingChain(parent));
            assertSame(trafficManager, trafficManager(original));
            assertEquals(1, tunerManager.getSourceRequests(),
                "an unstable source must abort before replacement-source allocation");
            assertEquals(1, currentSource.getRotationPauseCount());
            assertEquals(1, currentSource.getRotationResumeCount());

            trafficManager.processChannelGrant(restChannel(3, FIRST_REST_FREQUENCY), identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);

            assertEquals(2, tunerManager.getSourceRequests(),
                "the aborted handoff did not release its reserved pooled traffic channel");
            assertEquals(2, manager.getProcessingChainsByConfiguration(parent.getConfigurationId(), null).size());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void constructionFailurePreservesTheFormerRestCallAndRetries() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource failedReplacementSource = new TestComplexSource(FIRST_REST_FREQUENCY);
        TestComplexSource successfulReplacementSource = new TestComplexSource(FIRST_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), failedReplacementSource, successfulReplacementSource);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new FailOnSecondLogLookupEventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences,
            10_000);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber subscriber = new HandoffSubscriber();
            original.getEventBus().register(subscriber);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.secondSourceRequest.await(5, TimeUnit.SECONDS));
            DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
            assertTrue(awaitCondition(() -> failedReplacementSource.getDisposeCount() == 1, 5));

            Channel converted = manager.getChannel(original);
            assertNotNull(converted);
            assertTrue(converted.isTrafficChannel());
            assertTrue(original.isProcessing(), "replacement construction failure stopped the former REST call");
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());
            assertTrue(trafficManager.isPendingRestHandoff(request));
            assertEquals(0, failedReplacementSource.getStartCount());
            assertEquals(1, failedReplacementSource.getStopCount());
            assertEquals(1, failedReplacementSource.getDisposeCount());

            assertTrue(manager.retryDmrRestChannelHandoff(request));
            assertTrue(awaitCondition(() -> manager.getProcessingChain(parent) != null &&
                manager.getProcessingChain(parent) != original &&
                manager.getPendingDmrRestChannelAttemptCount() == 0, 5));

            assertEquals(3, tunerManager.getSourceRequests());
            assertEquals(1, successfulReplacementSource.getStartCount());
            assertTrue(original.isProcessing());
            assertTrue(manager.isProcessing());
            assertFalse(trafficManager.isPendingRestHandoff(request));
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void ordinaryTrafficConstructionFailureReturnsPooledChannelForNextGrant() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource failedTrafficSource = new TestComplexSource(FIRST_REST_FREQUENCY);
        TestComplexSource replacementTrafficSource = new TestComplexSource(SECOND_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), failedTrafficSource, replacementTrafficSource);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new FailOnSecondLogLookupEventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);
        AtomicInteger rejectedStarts = new AtomicInteger();
        manager.addChannelEventListener(event ->
        {
            if(event.getEvent() == Event.NOTIFICATION_PROCESSING_START_REJECTED)
            {
                rejectedStarts.incrementAndGet();
            }
        });

        try
        {
            manager.start(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(manager.getProcessingChain(parent));

            trafficManager.processChannelGrant(restChannel(3, FIRST_REST_FREQUENCY), identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);

            assertEquals(3, tunerManager.getSourceRequests(),
                "the second grant did not reuse the sole pooled traffic channel");
            assertEquals(2, manager.getProcessingChainsByConfiguration(parent.getConfigurationId(), null).size());
            assertEquals(1, rejectedStarts.get());
            assertEquals(0, failedTrafficSource.getStartCount());
            assertEquals(1, failedTrafficSource.getStopCount());
            assertEquals(1, failedTrafficSource.getDisposeCount());
            assertEquals(1, replacementTrafficSource.getStartCount());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void ordinaryTrafficRequiredSourceFailureClosesLoggerAndReturnsPooledChannel() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource failedTrafficSource = new TestComplexSource(FIRST_REST_FREQUENCY, true);
        TestComplexSource replacementTrafficSource = new TestComplexSource(SECOND_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), failedTrafficSource, replacementTrafficSource);
        LifecycleTrackingEventLogManager eventLogManager =
            new LifecycleTrackingEventLogManager(aliasModel, preferences);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            eventLogManager, tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);
        AtomicInteger rejectedStarts = new AtomicInteger();
        manager.addChannelEventListener(event ->
        {
            if(event.getEvent() == Event.NOTIFICATION_PROCESSING_START_REJECTED)
            {
                rejectedStarts.incrementAndGet();
            }
        });

        try
        {
            manager.start(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(manager.getProcessingChain(parent));

            trafficManager.processChannelGrant(restChannel(3, FIRST_REST_FREQUENCY), identifiers(101, 91),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 1_000L, false);
            trafficManager.processChannelGrant(restChannel(5, SECOND_REST_FREQUENCY), identifiers(102, 92),
                Opcode.STANDARD_TALKGROUP_VOICE_CHANNEL_GRANT, 2_000L, false);

            assertEquals(3, tunerManager.getSourceRequests(),
                "the required source-start failure did not release the pooled traffic channel");
            assertEquals(2, manager.getProcessingChainsByConfiguration(parent.getConfigurationId(), null).size());
            assertEquals(1, rejectedStarts.get());
            assertEquals(1, failedTrafficSource.getStartCount());
            assertEquals(1, failedTrafficSource.getStopCount());
            assertEquals(1, replacementTrafficSource.getStartCount());
            assertEquals(1, eventLogManager.mTrafficLogger.mStartCount.get());
            assertEquals(1, eventLogManager.mTrafficLogger.mStopCount.get());
            assertEquals(1, eventLogManager.mTrafficLogger.mDisposeCount.get());
        }
        finally
        {
            manager.close();
        }
    }

    @Test
    void requiredSourceStartFailurePreservesTheFormerRestCallAndRetries() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        BlockingFailStartSource failedReplacementSource = new BlockingFailStartSource(FIRST_REST_FREQUENCY);
        TestComplexSource successfulReplacementSource = new TestComplexSource(FIRST_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), failedReplacementSource, successfulReplacementSource);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10_000);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            List<IDecodeEvent> originalEvents = captureDecodeEvents(original);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber subscriber = new HandoffSubscriber();
            original.getEventBus().register(subscriber);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            assertTrue(tunerManager.secondSourceRequest.await(5, TimeUnit.SECONDS));
            assertTrue(failedReplacementSource.startEntered.await(5, TimeUnit.SECONDS));
            DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
            ProcessingChain failedCandidate = manager.getProcessingChain(parent);
            assertNotNull(failedCandidate);
            assertNotSame(original, failedCandidate);
            List<IDecodeEvent> failedCandidateEvents = captureDecodeEvents(failedCandidate);
            DecodeEvent inFlightEvent = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_500L)
                .channel(restChannel(5, SECOND_REST_FREQUENCY))
                .identifiers(identifiers(103, 93))
                .timeslot(1)
                .details("manager event during tentative replacement startup")
                .build();
            trafficManager.broadcast(inFlightEvent);
            assertEquals(List.of(inFlightEvent), originalEvents);
            assertTrue(failedCandidateEvents.isEmpty(),
                "tentative replacement stole the committed manager event route");
            failedReplacementSource.releaseStart.countDown();
            assertTrue(awaitCondition(() -> failedReplacementSource.getStopCount() == 1, 5));

            assertTrue(original.isProcessing(), "replacement source-start failure stopped the former REST call");
            assertEquals(1, manager.getPendingDmrRestChannelAttemptCount());
            assertTrue(trafficManager.isPendingRestHandoff(request));
            assertEquals(1, failedReplacementSource.getStartCount());
            assertEquals(1, failedReplacementSource.getStopCount());
            assertEquals(List.of(inFlightEvent), originalEvents,
                "failed replacement discarded the manager event delivered during startup");

            assertTrue(manager.retryDmrRestChannelHandoff(request));
            assertTrue(awaitCondition(() -> manager.getProcessingChain(parent) != null &&
                manager.getProcessingChain(parent) != original &&
                manager.getPendingDmrRestChannelAttemptCount() == 0, 5));

            ProcessingChain successfulReplacement = manager.getProcessingChain(parent);
            List<IDecodeEvent> successfulReplacementEvents = captureDecodeEvents(successfulReplacement);
            DecodeEvent replacementEvent = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 2_000L)
                .channel(restChannel(5, SECOND_REST_FREQUENCY))
                .identifiers(identifiers(104, 94))
                .timeslot(1)
                .details("manager event after replacement commit")
                .build();
            trafficManager.broadcast(replacementEvent);
            assertEquals(List.of(inFlightEvent), originalEvents);
            assertEquals(List.of(replacementEvent), successfulReplacementEvents,
                "successful replacement did not become the committed manager event route");

            assertEquals(3, tunerManager.getSourceRequests());
            assertEquals(1, successfulReplacementSource.getStartCount());
            assertTrue(original.isProcessing());
            assertTrue(manager.isProcessing());
            assertFalse(trafficManager.isPendingRestHandoff(request));
        }
        finally
        {
            failedReplacementSource.releaseStart.countDown();
            manager.close();
        }
    }

    @Test
    void handlerValidationFailureClearsPendingRequest() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource source = new TestComplexSource(CURRENT_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences, source);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        Channel parent = channel(1);

        try
        {
            manager.start(parent);
            ProcessingChain original = manager.getProcessingChain(parent);
            DMRTrafficChannelManager trafficManager = trafficManager(original);
            HandoffSubscriber subscriber = new HandoffSubscriber();
            original.getEventBus().register(subscriber);
            source.setFailFrequencyLookup(true);

            trafficManager.requestRestChannelHandoff(parent, CURRENT_FREQUENCY,
                restChannel(3, FIRST_REST_FREQUENCY));
            DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
            assertTrue(awaitCondition(() -> !trafficManager.isPendingRestHandoff(request), 5));
            assertEquals(0, manager.getPendingDmrRestChannelAttemptCount());
            assertEquals(1, tunerManager.getSourceRequests());
        }
        finally
        {
            source.setFailFrequencyLookup(false);
            manager.close();
        }
    }

    @Test
    void startAfterCloseIsRejectedBeforeRequestingASource()
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);

        manager.close();

        assertThrows(ChannelException.class, () -> manager.start(channel(1)));
        assertEquals(0, tunerManager.getSourceRequests());
    }

    @Test
    void startArrivingDuringShutdownIsRejectedWithoutWaitingForLifecycleMonitor() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource source = new TestComplexSource(CURRENT_FREQUENCY, false, true);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences, source,
            new TestComplexSource(FIRST_REST_FREQUENCY));
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new EventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
        manager.start(channel(1));
        Thread shutdownThread = new Thread(manager::shutdown, "test-channel-manager-shutdown");
        shutdownThread.start();

        try
        {
            assertTrue(source.stopEntered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> startFailure = new AtomicReference<>();
            CountDownLatch startCompleted = new CountDownLatch(1);
            Thread startThread = new Thread(() ->
            {
                try
                {
                    manager.start(channel(1));
                }
                catch(Throwable throwable)
                {
                    startFailure.set(throwable);
                }
                finally
                {
                    startCompleted.countDown();
                }
            }, "test-start-during-shutdown");
            startThread.start();

            assertTrue(startCompleted.await(1, TimeUnit.SECONDS),
                "start waited for the lifecycle monitor instead of rejecting shutdown");
            assertInstanceOf(ChannelException.class, startFailure.get());
            assertEquals(1, tunerManager.getSourceRequests());
        }
        finally
        {
            source.releaseStop.countDown();
            shutdownThread.join(5_000);
            manager.close();
        }
    }

    private static AliasModel aliasModelWithAudioPolicy()
    {
        AliasListDefinition definition = new AliasListDefinition(AUDIO_ALIAS_LIST_NAME, AliasListFamily.DMR);
        definition.setId(1L);
        Alias alias = new Alias("Dispatch");
        alias.setId(1L);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.DMR, 91));
        alias.setRecordable(true);
        alias.addBroadcastChannel(AUDIO_STREAM_NAME);
        AliasModel aliasModel = new AliasModel();
        aliasModel.replaceCommittedConfiguration(List.of(definition), List.of(alias));
        return aliasModel;
    }

    private static List<IDecodeEvent> captureDecodeEvents(ProcessingChain chain)
    {
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        chain.addDecodeEventListener(events::add);
        return events;
    }

    private static VoiceAMessage voiceMessage(long timestamp)
    {
        return new VoiceAMessage(DMRSyncPattern.DIRECT_VOICE_TIMESLOT_1, new CorrectedBinaryMessage(288), null,
            timestamp, 1);
    }

    private static Terminator terminator(long timestamp)
    {
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(24);
        slotBits.load(8, 4, DataType.TLC.getValue());
        return new Terminator(DMRSyncPattern.BASE_STATION_DATA, new CorrectedBinaryMessage(288), null,
            new SlotType(slotBits), timestamp, 1, null);
    }

    private static List<AudioCallEvent> audioFrames(List<AudioCallEvent> events)
    {
        return events.stream().filter(event -> event.eventType() == AudioCallEventType.AUDIO_FRAME).toList();
    }

    private static List<AudioCallEvent> audioCompletions(List<AudioCallEvent> events, AudioCallId callId)
    {
        return events.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED &&
            callId.equals(event.callId())).toList();
    }

    private static void assertAudioPolicy(AudioCallSnapshot snapshot)
    {
        assertTrue(snapshot.recordAudio());
        assertEquals(1, snapshot.broadcastChannels().size());
        assertTrue(snapshot.broadcastChannels().stream()
            .anyMatch(channel -> AUDIO_STREAM_NAME.equals(channel.getChannelName())));
    }

    private static void setAudioCodec(DMRAudioModule audioModule, IAudioCodec codec)
    {
        try
        {
            Field field = JmbeAudioModule.class.getDeclaredField("mAudioCodec");
            field.setAccessible(true);
            field.set(audioModule, codec);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to install deterministic JMBE codec", exception);
        }
    }

    private static Channel channel(int trafficPoolSize)
    {
        Channel channel = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(trafficPoolSize);
        channel.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(CURRENT_FREQUENCY, FIRST_REST_FREQUENCY, SECOND_REST_FREQUENCY));
        source.setPreferredFrequency(CURRENT_FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static Channel trackingChannel(int trafficPoolSize)
    {
        Channel channel = new Channel("Capacity Plus", Channel.ChannelType.STANDARD);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(trafficPoolSize);
        channel.setDecodeConfiguration(config);
        TrackingSourceConfig source = new TrackingSourceConfig();
        source.setFrequencies(List.of(CURRENT_FREQUENCY, FIRST_REST_FREQUENCY, SECOND_REST_FREQUENCY));
        source.setPreferredFrequency(CURRENT_FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static BlockingStandardChannel blockingChannel(int trafficPoolSize)
    {
        BlockingStandardChannel channel = new BlockingStandardChannel();
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        config.setTrafficChannelPoolSize(trafficPoolSize);
        channel.setDecodeConfiguration(config);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(List.of(CURRENT_FREQUENCY, FIRST_REST_FREQUENCY, SECOND_REST_FREQUENCY));
        source.setPreferredFrequency(CURRENT_FREQUENCY);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void moveToFade(MultiChannelState state, int timeslot)
    {
        Object source = new Object();
        state.getDecoderStateListener().receive(new DecoderStateEvent(source,
            DecoderStateEvent.Event.CONTINUATION, State.CALL, timeslot));
        state.getDecoderStateListener().receive(new DecoderStateEvent(source,
            DecoderStateEvent.Event.END, State.FADE, timeslot));
    }

    private static DmrRestLsn restChannel(int lsn, long frequency)
    {
        DmrRestLsn channel = new DmrRestLsn(lsn);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(channel.getChannelNumber());
        mapping.setDownlinkFrequency(frequency);
        channel.setTimeslotFrequency(mapping);
        return channel;
    }

    private static TalkgroupVoiceChannelGrant channelGrant(long frequency)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(80);
        bits.load(2, 6, 49);
        bits.load(16, 12, 4);
        bits.load(32, 24, 1200);
        bits.load(56, 24, 3400);
        CorrectedBinaryMessage slotBits = new CorrectedBinaryMessage(24);
        slotBits.load(8, 4, 3);
        TalkgroupVoiceChannelGrant grant = new TalkgroupVoiceChannelGrant(DMRSyncPattern.BASE_STATION_DATA, bits, null,
            new SlotType(slotBits), 1_000L, 1);
        TimeslotFrequency mapping = new TimeslotFrequency();
        mapping.setNumber(grant.getChannel().getChannelNumber());
        mapping.setDownlinkFrequency(frequency);
        grant.getChannel().setTimeslotFrequency(mapping);
        return grant;
    }

    private static MutableIdentifierCollection identifiers(int radio, int talkgroup)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(DMRRadio.createFrom(radio));
        identifiers.update(DMRTalkgroup.create(talkgroup));
        return identifiers;
    }

    private static DMRTrafficChannelManager trafficManager(ProcessingChain chain)
    {
        return chain.getModules().stream()
            .filter(DMRTrafficChannelManager.class::isInstance)
            .map(DMRTrafficChannelManager.class::cast)
            .findFirst()
            .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Listener<IDecodeEvent> decodeEventListener(DMRTrafficChannelManager manager)
    {
        try
        {
            Field field = DMRTrafficChannelManager.class.getDeclaredField("mDecodeEventListener");
            field.setAccessible(true);
            return ((AtomicReference<Listener<IDecodeEvent>>)field.get(manager)).get();
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect DMR manager decode-event route", exception);
        }
    }

    private static DMRDecoderState firstDecoderState(ProcessingChain chain)
    {
        return chain.getModules().stream()
            .filter(DMRDecoderState.class::isInstance)
            .map(DMRDecoderState.class::cast)
            .findFirst()
            .orElseThrow();
    }

    private static <T> List<T> modules(ProcessingChain chain, Class<T> moduleType)
    {
        return chain.getModules().stream()
            .filter(moduleType::isInstance)
            .map(moduleType::cast)
            .toList();
    }

    private static ChannelActivitySnapshot.Row activityRow(ChannelProcessingManager manager, long frequency,
                                                            int timeslot)
    {
        return manager.getChannelActivityModel().getSnapshotSet().tables().stream()
            .flatMap(table -> table.rows().stream())
            .filter(row -> row.frequencyHz() == frequency && Integer.valueOf(timeslot).equals(row.timeslot()))
            .findFirst()
            .orElse(null);
    }

    private static void assertSameInstances(List<?> expected, List<?> actual)
    {
        assertEquals(expected.size(), actual.size());

        for(int index = 0; index < expected.size(); index++)
        {
            assertSame(expected.get(index), actual.get(index));
        }
    }

    private static void setBeforeChannelGrantAuthorityCheck(DMRDecoderState decoderState, Runnable interleave)
    {
        invokeDmrTestMethod(decoderState, "setBeforeChannelGrantAuthorityCheckForTest", Runnable.class, interleave);
    }

    private static void setAfterChannelGrantAuthorityAcquired(DMRDecoderState decoderState, Runnable interleave)
    {
        invokeDmrTestMethod(decoderState, "setAfterChannelGrantAuthorityAcquiredForTest", Runnable.class, interleave);
    }

    private static Channel getAllocatedChannel(DMRTrafficChannelManager manager, long frequency)
    {
        try
        {
            Method method = DMRTrafficChannelManager.class.getDeclaredMethod("getAllocatedChannel", long.class);
            method.setAccessible(true);
            return (Channel)method.invoke(manager, frequency);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to inspect DMR allocation accounting", exception);
        }
    }

    private static void invokeDmrTestMethod(DMRDecoderState decoderState, String name, Class<?> parameterType,
                                            Object parameter)
    {
        try
        {
            Method method = DMRDecoderState.class.getDeclaredMethod(name, parameterType);
            method.setAccessible(true);
            method.invoke(decoderState, parameter);
        }
        catch(ReflectiveOperationException exception)
        {
            throw new AssertionError("Unable to install DMR decoder race seam", exception);
        }
    }

    private static void awaitOrFail(CountDownLatch latch, String message)
    {
        try
        {
            if(!latch.await(5, TimeUnit.SECONDS))
            {
                throw new AssertionError(message);
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static boolean awaitCount(CountDownLatch latch, long expectedRemaining, long timeoutSeconds)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while(latch.getCount() > expectedRemaining && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        return latch.getCount() <= expectedRemaining;
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutSeconds)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    private record ChannelDecodeObservation(Channel channel, IDecodeEvent event)
    {
    }

    private static final class AccountingTransitionObserver
    {
        private final SourceConfigTunerMultipleFrequency mSourceConfig;
        private final DMRTrafficChannelManager mTrafficManager;
        private final CountDownLatch mSuspendObserved = new CountDownLatch(1);
        private final CountDownLatch mRollbackObserved = new CountDownLatch(1);
        private final AtomicReference<Throwable> mFailure = new AtomicReference<>();
        private volatile long mPreferredAtSuspend;
        private volatile Channel mAllocationAtSuspend;
        private volatile long mPreferredAtRollback;
        private volatile Channel mAllocationAtRollback;
        private volatile Channel mRestAllocationAtRollback;

        private AccountingTransitionObserver(Channel parentChannel, DMRTrafficChannelManager trafficManager)
        {
            mSourceConfig = (SourceConfigTunerMultipleFrequency)parentChannel.getSourceConfiguration();
            mTrafficManager = trafficManager;
        }

        @Subscribe
        public void receive(DMRChannelConfigurationTransitionNotification.Suspend notification)
        {
            try
            {
                mPreferredAtSuspend = mSourceConfig.getPreferredFrequency();
                mAllocationAtSuspend = getAllocatedChannel(mTrafficManager, CURRENT_FREQUENCY);
            }
            catch(Throwable throwable)
            {
                mFailure.compareAndSet(null, throwable);
            }
            finally
            {
                mSuspendObserved.countDown();
            }
        }

        @Subscribe
        public void receive(DMRChannelConfigurationTransitionNotification.Rollback notification)
        {
            try
            {
                mPreferredAtRollback = mSourceConfig.getPreferredFrequency();
                mAllocationAtRollback = getAllocatedChannel(mTrafficManager, CURRENT_FREQUENCY);
                mRestAllocationAtRollback = getAllocatedChannel(mTrafficManager, FIRST_REST_FREQUENCY);
            }
            catch(Throwable throwable)
            {
                mFailure.compareAndSet(null, throwable);
            }
            finally
            {
                mRollbackObserved.countDown();
            }
        }
    }

    private static final class TrackingSourceConfig extends SourceConfigTunerMultipleFrequency
    {
        private final List<Long> mPreferredFrequencies = new CopyOnWriteArrayList<>();

        @Override
        public void setPreferredFrequency(long frequency)
        {
            super.setPreferredFrequency(frequency);
            mPreferredFrequencies.add(frequency);
        }
    }

    private static final class HandoffSubscriber
    {
        private final List<DMRRestChannelHandoffRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(DMRRestChannelHandoffRequest request)
        {
            requests.add(request);
        }
    }

    private static final class StartRequestSubscriber
    {
        private final List<ChannelStartProcessingRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            requests.add(request);
        }
    }

    private static final class SequencedTunerManager extends TunerManager
    {
        private final Source[] mSources;
        private final AtomicInteger mSourceRequests = new AtomicInteger();
        private final CountDownLatch secondSourceRequest = new CountDownLatch(1);
        private final CountDownLatch firstUnavailable = new CountDownLatch(1);
        private final CountDownLatch firstRestSource = new CountDownLatch(1);
        private final CountDownLatch secondRestSource = new CountDownLatch(1);

        private SequencedTunerManager(UserPreferences preferences, Source... sources)
        {
            super(preferences);
            mSources = sources;
        }

        @Override
        public Source getSource(SourceConfiguration configuration, ChannelSpecification channelSpecification,
                                String threadName) throws SourceException
        {
            int request = mSourceRequests.incrementAndGet();
            Source source = request <= mSources.length ? mSources[request - 1] : null;

            if(request == 2)
            {
                secondSourceRequest.countDown();
            }

            if(request == 2 && source == null)
            {
                firstUnavailable.countDown();
            }
            else if(request == 3 && source != null)
            {
                firstRestSource.countDown();
            }
            else if(request == 4 && source != null)
            {
                secondRestSource.countDown();
            }

            return source;
        }

        private int getSourceRequests()
        {
            return mSourceRequests.get();
        }
    }

    private static final class BlockingReplacementTunerManager extends TunerManager
    {
        private final Source[] mSources;
        private final AtomicInteger mSourceRequests = new AtomicInteger();
        private final CountDownLatch blockedReplacementEntered = new CountDownLatch(1);
        private final CountDownLatch releaseBlockedReplacement = new CountDownLatch(1);

        private BlockingReplacementTunerManager(UserPreferences preferences, Source... sources)
        {
            super(preferences);
            mSources = sources;
        }

        @Override
        public Source getSource(SourceConfiguration configuration, ChannelSpecification channelSpecification,
                                String threadName)
        {
            int request = mSourceRequests.incrementAndGet();

            if(request == 3)
            {
                blockedReplacementEntered.countDown();

                try
                {
                    releaseBlockedReplacement.await(30, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            return request <= mSources.length ? mSources[request - 1] : null;
        }

        private int getSourceRequests()
        {
            return mSourceRequests.get();
        }
    }

    private static final class BlockingStandardChannel extends Channel
    {
        private static final String DECODER_THREAD_NAME = "test-cpm-dmr-decoder-teardown";
        private final CountDownLatch mTrafficDecisionEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseTrafficDecision = new CountDownLatch(1);

        private BlockingStandardChannel()
        {
            super("Capacity Plus", ChannelType.STANDARD);
        }

        @Override
        public boolean isTrafficChannel()
        {
            if(Thread.currentThread().getName().equals(DECODER_THREAD_NAME))
            {
                mTrafficDecisionEntered.countDown();

                try
                {
                    mReleaseTrafficDecision.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return false;
        }
    }

    private static class TestComplexSource extends ComplexSource
    {
        private final long mFrequency;
        private final boolean mFailStart;
        private final boolean mBlockStop;
        private final AtomicInteger mStartCount = new AtomicInteger();
        private final AtomicInteger mStopCount = new AtomicInteger();
        private final AtomicInteger mDisposeCount = new AtomicInteger();
        private final AtomicInteger mRotationPauseCount = new AtomicInteger();
        private final AtomicInteger mRotationResumeCount = new AtomicInteger();
        private Listener<ComplexSamples> mListener;
        private Listener<SourceEvent> mSourceEventListener;
        private volatile boolean mFailFrequencyLookup;
        private volatile long mRotationPauseFrequency;
        private final CountDownLatch stopEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStop = new CountDownLatch(1);

        private TestComplexSource(long frequency)
        {
            this(frequency, false);
        }

        private TestComplexSource(long frequency, boolean failStart)
        {
            this(frequency, failStart, false);
        }

        private TestComplexSource(long frequency, boolean failStart, boolean blockStop)
        {
            mFrequency = frequency;
            mFailStart = failStart;
            mBlockStop = blockStop;
            mRotationPauseFrequency = frequency;
        }

        @Subscribe
        public void pauseRotation(ChannelRotationMonitorPauseRequest request)
        {
            mRotationPauseCount.incrementAndGet();
            request.acknowledgeSourcePaused(true, mRotationPauseFrequency);
        }

        @Subscribe
        public void resumeRotation(ChannelRotationMonitorResumeRequest request)
        {
            mRotationResumeCount.incrementAndGet();
        }

        @Override
        public void setListener(Listener<ComplexSamples> listener)
        {
            mListener = listener;
        }

        @Override
        public Listener<SourceEvent> getSourceEventListener()
        {
            return event -> { };
        }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener)
        {
            mSourceEventListener = listener;
        }

        @Override
        public void removeSourceEventListener()
        {
            mSourceEventListener = null;
        }

        @Override
        public double getSampleRate()
        {
            return 25_000;
        }

        @Override
        public long getFrequency()
        {
            if(mFailFrequencyLookup)
            {
                throw new IllegalStateException("Injected frequency lookup failure");
            }

            return mFrequency;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
            mStartCount.incrementAndGet();

            if(mFailStart)
            {
                throw new IllegalStateException("Injected source start failure");
            }
        }

        @Override
        public void stop()
        {
            mStopCount.incrementAndGet();

            if(mBlockStop)
            {
                stopEntered.countDown();

                try
                {
                    releaseStop.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            mListener = null;
            mSourceEventListener = null;
        }

        @Override
        public void dispose()
        {
            mDisposeCount.incrementAndGet();
            super.dispose();
        }

        private void setFailFrequencyLookup(boolean failFrequencyLookup)
        {
            mFailFrequencyLookup = failFrequencyLookup;
        }

        private void setRotationPauseFrequency(long rotationPauseFrequency)
        {
            mRotationPauseFrequency = rotationPauseFrequency;
        }

        int getStartCount()
        {
            return mStartCount.get();
        }

        int getStopCount()
        {
            return mStopCount.get();
        }

        int getDisposeCount()
        {
            return mDisposeCount.get();
        }

        private int getRotationPauseCount()
        {
            return mRotationPauseCount.get();
        }

        private int getRotationResumeCount()
        {
            return mRotationResumeCount.get();
        }
    }

    private static final class BlockingFailStartSource extends TestComplexSource
    {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStart = new CountDownLatch(1);

        private BlockingFailStartSource(long frequency)
        {
            super(frequency);
        }

        @Override
        public void start()
        {
            startEntered.countDown();

            try
            {
                releaseStart.await(30, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            super.start();
            throw new IllegalStateException("Injected source start failure");
        }
    }

    private static final class FailOnSecondLogLookupEventLogManager extends EventLogManager
    {
        private final AtomicInteger mLookupCount = new AtomicInteger();

        private FailOnSecondLogLookupEventLogManager(AliasModel aliasModel, UserPreferences userPreferences)
        {
            super(aliasModel, userPreferences);
        }

        @Override
        public List<Module> getLoggers(Channel channel)
        {
            if(mLookupCount.incrementAndGet() == 2)
            {
                throw new IllegalStateException("Injected logger construction failure");
            }

            return super.getLoggers(channel);
        }
    }

    private static final class LifecycleTrackingEventLogManager extends EventLogManager
    {
        private final LifecycleTrackingEventLogger mTrafficLogger = new LifecycleTrackingEventLogger();
        private final AtomicInteger mTrafficLookupCount = new AtomicInteger();

        private LifecycleTrackingEventLogManager(AliasModel aliasModel, UserPreferences userPreferences)
        {
            super(aliasModel, userPreferences);
        }

        @Override
        public List<Module> getLoggers(Channel channel)
        {
            if(channel.isTrafficChannel() && mTrafficLookupCount.incrementAndGet() == 1)
            {
                return List.of(mTrafficLogger);
            }

            return List.of();
        }
    }

    private static final class LifecycleTrackingEventLogger extends EventLogger
    {
        private final AtomicInteger mStartCount = new AtomicInteger();
        private final AtomicInteger mStopCount = new AtomicInteger();
        private final AtomicInteger mDisposeCount = new AtomicInteger();

        private LifecycleTrackingEventLogger()
        {
            super(Path.of("."), "lifecycle-test.log", FIRST_REST_FREQUENCY);
        }

        @Override
        public String getHeader()
        {
            return "";
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
            mStartCount.incrementAndGet();
        }

        @Override
        public void stop()
        {
            mStopCount.incrementAndGet();
        }

        @Override
        public void dispose()
        {
            mDisposeCount.incrementAndGet();
            super.dispose();
        }
    }

    private static final class TestAudioCodec implements IAudioCodec
    {
        @Override
        public String getCodecName()
        {
            return "TEST AMBE";
        }

        @Override
        public float[] getAudio(byte[] frame)
        {
            return new float[160];
        }

        @Override
        public IAudioWithMetadata getAudioWithMetadata(byte[] frame)
        {
            return new IAudioWithMetadata()
            {
                @Override
                public float[] getAudio()
                {
                    return new float[160];
                }

                @Override
                public boolean hasMetadata()
                {
                    return false;
                }

                @Override
                public Map<String,String> getMetadata()
                {
                    return Map.of();
                }
            };
        }

        @Override
        public void reset()
        {
        }
    }

    private static final class TestDecodeEventProvider extends Module implements IDecodeEventProvider
    {
        private Listener<IDecodeEvent> mListener;

        private void receive(IDecodeEvent event)
        {
            if(mListener != null)
            {
                mListener.receive(event);
            }
        }

        @Override
        public void addDecodeEventListener(Listener<IDecodeEvent> listener)
        {
            mListener = listener;
        }

        @Override
        public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
        {
            if(mListener == listener)
            {
                mListener = null;
            }
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }

    private static final class TestAudioCallProvider extends Module implements IAudioCallProvider
    {
        private Listener<AudioCallEvent> mListener;

        private void receive(AudioCallEvent event)
        {
            if(mListener != null)
            {
                mListener.receive(event);
            }
        }

        @Override
        public void setAudioCallEventListener(Listener<AudioCallEvent> listener)
        {
            mListener = listener;
        }

        @Override
        public void removeAudioCallEventListener()
        {
            mListener = null;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }
    }
}

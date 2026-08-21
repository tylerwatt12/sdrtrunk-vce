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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.MultiChannelState;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.ChannelEvent.Event;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DMRChannelConfigurationTransitionNotification;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DMRDecoderState;
import io.github.dsheirer.module.decode.dmr.DMRRestChannelHandoffRequest;
import io.github.dsheirer.module.decode.dmr.DMRTrafficChannelManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DmrRestLsn;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.SlotType;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorPauseRequest;
import io.github.dsheirer.source.tuner.channel.rotation.ChannelRotationMonitorResumeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ChannelProcessingManagerDMRRestHandoffTest
{
    private static final long CURRENT_FREQUENCY = 451_000_000L;
    private static final long FIRST_REST_FREQUENCY = 452_000_000L;
    private static final long SECOND_REST_FREQUENCY = 453_000_000L;

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
            assertNotSame(firstChain, firstReplacement);
            assertSame(trafficManager, trafficManager(firstReplacement));
            assertFalse(firstChain.getModules().stream().anyMatch(module -> module == trafficManager));
            assertTrue(firstReplacement.getDecodeEventHistory().getItems().isEmpty());
            assertTrue(firstReplacement.getMessageHistory().getItems().isEmpty());
            DecodeEvent managerOwned = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_500L)
                .channel(restChannel(3, FIRST_REST_FREQUENCY))
                .identifiers(identifiers(102, 92))
                .timeslot(1)
                .details("manager-owned parent event")
                .build();
            trafficManager.broadcast(managerOwned);
            assertEquals(List.of(managerOwned), firstReplacement.getDecodeEventHistory().getItems());
            assertEquals(List.of(activeUpdate), firstChain.getDecodeEventHistory().getItems());

            activeUpdate.update(2_000L);
            trafficEventProvider.receive(activeUpdate);
            trafficManager.receiveTrafficChannelEvent(activeUpdate);

            assertEquals(List.of(activeUpdate, managerOwned, activeUpdate), deliveredEvents);
            assertEquals(List.of(activeUpdate), firstChain.getDecodeEventHistory().getItems());
            assertEquals(List.of(managerOwned), firstReplacement.getDecodeEventHistory().getItems());

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
            assertEquals(List.of(rejection), original.getDecodeEventHistory().getItems());

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
            manager.requestDmrRestChannelHandoff(handoffSubscriber.requests.getFirst());
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
    void constructionFailureIsTerminalAndReleasesUnassignedSourceExactlyOnce() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource replacementSource = new TestComplexSource(FIRST_REST_FREQUENCY);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), replacementSource);
        ChannelProcessingManager manager = new ChannelProcessingManager(
            new FailOnSecondLogLookupEventLogManager(aliasModel, preferences), tunerManager, aliasModel, preferences, 10);
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
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                !trafficManager.isPendingRestHandoff(request), 5));

            assertEquals(2, tunerManager.getSourceRequests());
            assertEquals(0, replacementSource.getStartCount());
            assertEquals(1, replacementSource.getStopCount());
            assertEquals(1, replacementSource.getDisposeCount());
            assertFalse(manager.isProcessing());
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
    void requiredSourceStartFailureIsTerminalAndReleasesAssignedSourceExactlyOnce() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        AliasModel aliasModel = new AliasModel();
        TestComplexSource replacementSource = new TestComplexSource(FIRST_REST_FREQUENCY, true);
        SequencedTunerManager tunerManager = new SequencedTunerManager(preferences,
            new TestComplexSource(CURRENT_FREQUENCY), replacementSource);
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
            assertTrue(tunerManager.secondSourceRequest.await(5, TimeUnit.SECONDS));
            DMRRestChannelHandoffRequest request = subscriber.requests.getFirst();
            assertTrue(awaitCondition(() -> manager.getPendingDmrRestChannelAttemptCount() == 0 &&
                !trafficManager.isPendingRestHandoff(request), 5));

            assertEquals(2, tunerManager.getSourceRequests());
            assertEquals(1, replacementSource.getStartCount());
            assertEquals(1, replacementSource.getStopCount());
            assertFalse(manager.isProcessing());
        }
        finally
        {
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

    private static DMRDecoderState firstDecoderState(ProcessingChain chain)
    {
        return chain.getModules().stream()
            .filter(DMRDecoderState.class::isInstance)
            .map(DMRDecoderState.class::cast)
            .findFirst()
            .orElseThrow();
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

        private int getStartCount()
        {
            return mStartCount.get();
        }

        private int getStopCount()
        {
            return mStopCount.get();
        }

        private int getDisposeCount()
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
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallBroadcaster;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallSiteConfiguration;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDecisionOutcome;
import io.github.dsheirer.audio.call.diagnostic.LogicalCallDiagnosticDecision;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteGuidConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the completed-call delivery contract across duplicate resolution, temporary-recording creation, and provider
 * dispatch.  The fixtures model several trunked sites sharing one Alias List and deliberately keep provider startup
 * offline; only routing and pending-replay ownership are under test.
 */
class BroadcastifyCallSiteDeliveryWorkflowTest
{
    private static final long ALIAS_LIST_ID = 77L;
    private static final String ALIAS_LIST_NAME = "Regional P25";
    private static final String EAST_ROUTE = "East site calls";
    private static final String SITE_ROUTE = "West site calls";
    private static final String CENTRAL_ROUTE = "Central site calls";
    private static final String LEGACY_ROUTE = "Legacy calls";
    private static final String EAST_CHANNEL_ID = "00000000-0000-0000-0000-000000000071";
    private static final String WEST_CHANNEL_ID = "00000000-0000-0000-0000-000000000072";
    private static final String CENTRAL_CHANNEL_ID = "00000000-0000-0000-0000-000000000073";
    private static final String EAST_SITE_GUID = "00000000-0000-0000-0000-000000000081";
    private static final String WEST_SITE_GUID = "00000000-0000-0000-0000-000000000082";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void westOnlyObservationOnTheSharedAliasListReachesTheWestProvider() throws Exception
    {
        try(WorkflowHarness harness = harness("west-only"))
        {
            harness.addSiteProvider(SITE_ROUTE, WEST_CHANNEL_ID);
            AudioCallSnapshot west = harness.snapshot(1L, 1000, 9001, WEST_CHANNEL_ID, WEST_SITE_GUID,
                Set.of(SITE_ROUTE));

            harness.submit(west);

            BroadcastifyCallBroadcaster broadcaster = harness.broadcaster(SITE_ROUTE);
            AudioRecording recording = harness.onlyRecording();
            assertEquals(1, broadcaster.getAudioQueueSize());
            assertTrue(recording.getDeliveryEvidence().matches(SITE_ROUTE, ALIAS_LIST_ID, WEST_CHANNEL_ID));
            assertTrue(recording.hasPendingReplays(),
                "An accepted provider must own one pending replay before it queues the recording");

            harness.clearProviders();
            assertFalse(recording.hasPendingReplays(),
                "Provider disposal must release the accepted recording");
        }
    }

    @Test
    void selectedWestSiteStillReceivesWhenItsDuplicateCopyLosesTheElection() throws Exception
    {
        try(WorkflowHarness harness = harness("losing-west"))
        {
            harness.addSiteProvider(SITE_ROUTE, WEST_CHANNEL_ID);
            AudioCallSnapshot eastWinner = harness.snapshot(11L, 1000, 9001, EAST_CHANNEL_ID, EAST_SITE_GUID,
                Set.of(SITE_ROUTE));
            AudioCallSnapshot westLoser = harness.snapshot(12L, 1000, 9001, WEST_CHANNEL_ID, WEST_SITE_GUID,
                Set.of(SITE_ROUTE));

            //Equal-quality copies use the stable site identity as a deterministic final tie-breaker.
            harness.submit(eastWinner, westLoser);

            assertEquals(EAST_CHANNEL_ID, harness.electedChannelConfigurationId(),
                "The selected West site must genuinely be the losing receiver copy in this fixture");
            assertTrue(harness.onlyRecording().getDeliveryEvidence()
                .matches(SITE_ROUTE, ALIAS_LIST_ID, WEST_CHANNEL_ID),
                "The elected audio must retain the losing site's independent delivery observation");
            assertEquals(1, harness.broadcaster(SITE_ROUTE).getAudioQueueSize());
        }
    }

    @Test
    void oneDedupedCallDeliversOnceToEachSiteThatObservedItsOwnRoute() throws Exception
    {
        try(WorkflowHarness harness = harness("east-west-providers"))
        {
            harness.addSiteProvider(EAST_ROUTE, EAST_CHANNEL_ID);
            harness.addSiteProvider(SITE_ROUTE, WEST_CHANNEL_ID);
            harness.addSiteProvider(CENTRAL_ROUTE, CENTRAL_CHANNEL_ID);
            AudioCallSnapshot eastWinner = harness.snapshot(15L, 1000, 9050, EAST_CHANNEL_ID, EAST_SITE_GUID,
                Set.of(EAST_ROUTE, CENTRAL_ROUTE));
            AudioCallSnapshot westLoser = harness.snapshot(16L, 1000, 9050, WEST_CHANNEL_ID, WEST_SITE_GUID,
                Set.of(SITE_ROUTE));

            harness.submit(eastWinner, westLoser);

            AudioRecording recording = harness.onlyRecording();
            assertEquals(EAST_CHANNEL_ID, harness.electedChannelConfigurationId());
            assertTrue(recording.getDeliveryEvidence().matches(EAST_ROUTE, ALIAS_LIST_ID, EAST_CHANNEL_ID));
            assertTrue(recording.getDeliveryEvidence().matches(SITE_ROUTE, ALIAS_LIST_ID, WEST_CHANNEL_ID));
            assertFalse(recording.getDeliveryEvidence()
                .matches(CENTRAL_ROUTE, ALIAS_LIST_ID, CENTRAL_CHANNEL_ID));
            assertEquals(1, harness.broadcaster(EAST_ROUTE).getAudioQueueSize());
            assertEquals(1, harness.broadcaster(SITE_ROUTE).getAudioQueueSize());
            assertEquals(0, harness.broadcaster(CENTRAL_ROUTE).getAudioQueueSize(),
                "A routed third provider must remain empty without a same-context Central observation");
            assertEquals(1, harness.recordings().size(),
                "Duplicate copies must still create only one temporary recording");
        }
    }

    @Test
    void routeAndSelectedChannelFromDifferentDuplicateContextsAreRejected() throws Exception
    {
        try(WorkflowHarness harness = harness("cross-context"))
        {
            harness.addSiteProvider(SITE_ROUTE, WEST_CHANNEL_ID);
            AudioCallSnapshot eastRoute = harness.snapshot(21L, 1000, 9100, EAST_CHANNEL_ID, EAST_SITE_GUID,
                Set.of(SITE_ROUTE));
            AudioCallSnapshot westWithoutRoute = harness.snapshot(22L, 1000, 9100, WEST_CHANNEL_ID, WEST_SITE_GUID,
                Set.of());

            harness.submit(eastRoute, westWithoutRoute);

            AudioRecording recording = harness.onlyRecording();
            assertFalse(recording.getDeliveryEvidence().matches(SITE_ROUTE, ALIAS_LIST_ID, WEST_CHANNEL_ID),
                "Delivery must not combine a route from East with a selected channel observed on West");
            assertEquals(0, harness.broadcaster(SITE_ROUTE).getAudioQueueSize());
            assertFalse(recording.hasPendingReplays(),
                "A provider rejected before receive must never claim a pending replay");
        }
    }

    @Test
    void missingAndMismatchedChannelEvidenceFailClosedWithoutPendingReplay() throws Exception
    {
        try(WorkflowHarness harness = harness("missing-mismatch"))
        {
            harness.addSiteProvider(SITE_ROUTE, WEST_CHANNEL_ID);
            AudioCallSnapshot mismatched = harness.snapshot(31L, 1000, 9200, EAST_CHANNEL_ID, EAST_SITE_GUID,
                Set.of(SITE_ROUTE));
            harness.submit(mismatched);
            AudioCallSnapshot missing = harness.snapshot(32L, 1000, 9201, null, WEST_SITE_GUID,
                Set.of(SITE_ROUTE));
            harness.submit(missing);

            assertEquals(2, harness.recordings().size());
            assertEquals(0, harness.broadcaster(SITE_ROUTE).getAudioQueueSize());
            assertTrue(harness.recordings().stream().noneMatch(AudioRecording::hasPendingReplays),
                "Neither an absent nor a different saved-channel UUID may leak replay ownership");
        }
    }

    @Test
    void legacyBroadcastifyCallsProviderKeepsAcceptAllRouting() throws Exception
    {
        try(WorkflowHarness harness = harness("legacy"))
        {
            harness.addLegacyProvider(LEGACY_ROUTE);
            AudioCallSnapshot withoutSiteEvidence = harness.snapshot(41L, 1020, 9300, null, null,
                Set.of(LEGACY_ROUTE));

            harness.submit(withoutSiteEvidence);

            AudioRecording recording = harness.onlyRecording();
            assertEquals(1, harness.broadcaster(LEGACY_ROUTE).getAudioQueueSize());
            assertTrue(recording.hasPendingReplays(),
                "Legacy Broadcastify Calls must not become dependent on site evidence");
        }
    }

    private WorkflowHarness harness(String directoryName) throws IOException
    {
        return new WorkflowHarness(mTemporaryFolder.resolve(directoryName));
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return;
            }

            Thread.sleep(10L);
        }

        assertTrue(condition.getAsBoolean(), message);
    }

    private static String channelConfigurationId(CompletedAudioCall call)
    {
        Identifier<?> identifier = call.snapshot().identifierCollection().getIdentifier(
            IdentifierClass.CONFIGURATION, Form.UNIQUE_ID, Role.ANY);
        return identifier != null && identifier.getValue() != null ? identifier.getValue().toString() : null;
    }

    private static AudioCallEvent completionEvent(AudioCallSnapshot snapshot)
    {
        AudioCallSnapshot completed = new AudioCallSnapshot(snapshot.callId(), snapshot.linkedCallId(),
            snapshot.aliasList(), snapshot.identifierCollection(), snapshot.broadcastChannels(),
            snapshot.startTimestamp(), snapshot.lastActivityTimestamp(), snapshot.burstCount(),
            snapshot.burstGeneration(), snapshot.lastBurstStartTimestamp(), snapshot.lastBurstEndTimestamp(),
            false, true, snapshot.encryptionState(), snapshot.recordAudio(), snapshot.recordingMetadata(),
            snapshot.voiceCallQuality(), snapshot.callLegId(), snapshot.callLegSource(),
            snapshot.callEncryptionEvidence());
        return new AudioCallEvent(AudioCallEventType.CALL_COMPLETED, completed, null, false, 0L, 0L);
    }

    private final class WorkflowHarness implements AutoCloseable
    {
        private final AliasList mAliasList;
        private final WorkflowUserPreferences mPreferences;
        private final ManualStreamingScheduler mScheduler = new ManualStreamingScheduler();
        private final TestBroadcastModel mBroadcastModel;
        private final List<AudioRecording> mRecordings = new CopyOnWriteArrayList<>();
        private final List<CompletedAudioCall> mStreamedCalls = new CopyOnWriteArrayList<>();
        private final List<LogicalCallDiagnosticDecision> mDecisions = new CopyOnWriteArrayList<>();
        private final AudioStreamingManager mStreamingManager;
        private final AudioCallCoordinator mCoordinator;

        private WorkflowHarness(Path streamingDirectory) throws IOException
        {
            Files.createDirectories(streamingDirectory);
            AliasListDefinition definition = new AliasListDefinition(ALIAS_LIST_NAME, AliasListFamily.P25);
            definition.setId(ALIAS_LIST_ID);
            AliasModel aliasModel = new AliasModel();
            aliasModel.replaceCommittedConfiguration(List.of(definition), List.of());
            mAliasList = new AliasList(definition);
            mPreferences = new WorkflowUserPreferences(streamingDirectory);
            mBroadcastModel = new TestBroadcastModel(aliasModel, mPreferences);
            mStreamingManager = new AudioStreamingManager(recording -> {
                mRecordings.add(recording);
                mBroadcastModel.receive(recording);
            }, BroadcastFormat.MP3, mPreferences, mStreamedCalls::add, mScheduler,
                (call, path, preferences, identifiers) ->
                    Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW));
            mStreamingManager.start();
            mCoordinator = new AudioCallCoordinator(null, mStreamingManager, null, null, decision -> {
                mDecisions.add(decision);
                return true;
            });
        }

        private void addSiteProvider(String routeName, String channelConfigurationId)
        {
            BroadcastifyCallSiteConfiguration configuration = new BroadcastifyCallSiteConfiguration();
            configure(configuration, routeName);
            configuration.setAliasListId(ALIAS_LIST_ID);
            configuration.setAliasListName(ALIAS_LIST_NAME);
            configuration.setChannelConfigurationId(channelConfigurationId);
            mBroadcastModel.addBroadcastConfiguration(configuration);
            assertNotNull(mBroadcastModel.getBroadcaster(routeName));
        }

        private void addLegacyProvider(String routeName)
        {
            BroadcastifyCallConfiguration configuration = new BroadcastifyCallConfiguration();
            configure(configuration, routeName);
            mBroadcastModel.addBroadcastConfiguration(configuration);
            assertNotNull(mBroadcastModel.getBroadcaster(routeName));
        }

        private void configure(BroadcastifyCallConfiguration configuration, String routeName)
        {
            configuration.setName(routeName);
            configuration.setApiKey("test-key");
            configuration.setSystemID(1);
            configuration.setEnabled(true);
        }

        private AudioCallSnapshot snapshot(long producerId, int talkgroup, int radio,
                                           String channelConfigurationId, String siteGuid, Set<String> routes)
        {
            List<Identifier> identifiers = new ArrayList<>();
            identifiers.add(SystemConfigurationIdentifier.create("Regional System"));
            identifiers.add(AliasListConfigurationIdentifier.create(ALIAS_LIST_NAME));

            if(channelConfigurationId != null)
            {
                identifiers.add(ChannelConfigurationIdentifier.create(channelConfigurationId));
            }

            if(siteGuid != null)
            {
                identifiers.add(SiteGuidConfigurationIdentifier.create(siteGuid));
            }

            identifiers.add(APCO25Talkgroup.create(talkgroup));
            identifiers.add(APCO25RadioIdentifier.createFrom(radio));
            Set<BroadcastChannel> broadcastChannels = routes.stream().map(BroadcastChannel::new)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            long now = System.currentTimeMillis();
            long end = now + 1_000L;
            AudioCallId callId = new AudioCallId(producerId, 1L, 0);
            int site = EAST_CHANNEL_ID.equals(channelConfigurationId) ? 1 :
                WEST_CHANNEL_ID.equals(channelConfigurationId) ? 2 : (int)(producerId % 200L) + 1;
            CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1, channelConfigurationId,
                "Site " + site, siteGuid, ALIAS_LIST_ID, new P25SiteIdentity(0xBEE00, 0x348, 1, site), true);
            return new AudioCallSnapshot(callId, null, mAliasList, new IdentifierCollection(identifiers),
                broadcastChannels, now, end, 1, 1, now, end, false, false, CallEncryptionState.CLEAR,
                false, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), source, null);
        }

        private void submit(AudioCallSnapshot... snapshots) throws InterruptedException
        {
            int expectedRecordings = mRecordings.size() + 1;
            int expectedDecisions = mDecisions.size() + 1;
            long carrierTimestamp = System.currentTimeMillis();

            for(AudioCallSnapshot snapshot : snapshots)
            {
                mCoordinator.receive(new AudioCallEvent(AudioCallEventType.CALL_CREATED, snapshot, null,
                    false, 0L, 0L));
            }

            for(int index = 0; index < 3; index++)
            {
                long fingerprint = 10_000L + index;

                for(AudioCallSnapshot snapshot : snapshots)
                {
                    mCoordinator.receive(new AudioCallEvent(AudioCallEventType.AUDIO_FRAME, snapshot,
                        new float[160], false, fingerprint, carrierTimestamp + index * 20L));
                }
            }

            for(AudioCallSnapshot snapshot : snapshots)
            {
                mCoordinator.receive(completionEvent(snapshot));
            }

            awaitCondition(() -> mDecisions.size() >= expectedDecisions &&
                    mStreamingManager.getQueueStatus().retainedCalls() > 0,
                "Coordinator did not hand one resolved call to the streaming manager");
            assertEquals(expectedDecisions, mDecisions.size(),
                "Every submission must produce exactly one logical-call decision: " + mDecisions);
            assertEquals(1, mStreamingManager.getQueueStatus().retainedCalls(),
                "Every submission must hand exactly one logical call to streaming: " + mDecisions);

            if(snapshots.length > 1)
            {
                assertEquals(LogicalCallDecisionOutcome.MERGED,
                    mDecisions.get(expectedDecisions - 1).outcome(),
                    "The two physical site observations must resolve as one logical call: " + mDecisions);
            }

            mStreamingManager.new AudioSegmentProcessor().run();
            awaitCondition(() -> mRecordings.size() == expectedRecordings,
                "Streaming manager did not hand the recording to the broadcast model");
            assertEquals(0, mStreamingManager.getQueueStatus().retainedCalls());
        }

        private BroadcastifyCallBroadcaster broadcaster(String routeName)
        {
            return (BroadcastifyCallBroadcaster)mBroadcastModel.getBroadcaster(routeName);
        }

        private AudioRecording onlyRecording()
        {
            assertEquals(1, mRecordings.size());
            return mRecordings.getFirst();
        }

        private List<AudioRecording> recordings()
        {
            return mRecordings;
        }

        private String electedChannelConfigurationId()
        {
            assertEquals(1, mStreamedCalls.size());
            return channelConfigurationId(mStreamedCalls.getFirst());
        }

        private void clearProviders()
        {
            mBroadcastModel.clear();
        }

        @Override
        public void close()
        {
            mCoordinator.dispose();
            mStreamingManager.stop();
            mBroadcastModel.clear();
            mScheduler.shutdownNow();
        }
    }

    private static class TestBroadcastModel extends BroadcastModel
    {
        private final AliasModel mAliasModel;

        private TestBroadcastModel(AliasModel aliasModel, UserPreferences userPreferences)
        {
            super(aliasModel, null, userPreferences, false);
            mAliasModel = aliasModel;
        }

        @Override
        protected AbstractAudioBroadcaster<?> createAudioBroadcaster(BroadcastConfiguration configuration)
        {
            return new BroadcastifyCallBroadcaster((BroadcastifyCallConfiguration)configuration,
                null, null, mAliasModel);
        }

        @Override
        protected void executeBroadcasterStart(Runnable startTask)
        {
            //Provider startup performs network I/O. The broadcaster is already published for routing at this point.
        }
    }

    private static class WorkflowUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private WorkflowUserPreferences(Path streamingDirectory)
        {
            mDirectoryPreference = new DirectoryPreference(null)
            {
                @Override
                public Path getDirectoryStreaming()
                {
                    return streamingDirectory;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static class ManualStreamingScheduler extends ScheduledThreadPoolExecutor
    {
        private ManualStreamingScheduler()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                       TimeUnit unit)
        {
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }
    }
}

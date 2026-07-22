/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ApplyPolicy;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.AutoStartAction;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.AutoStartRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelConfigurationException;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelListRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelWriteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.DmrRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.FrequencyMapRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.NbfmRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.P25Phase1Request;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.P25Phase2Request;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RevisionRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RuntimeAction;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.SourceKind;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.SourceRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.TimeoutRequest;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelConfigurationServiceTest
{
    private FakeBackend mBackend;
    private ChannelConfigurationService mService;

    @BeforeEach
    void setUp()
    {
        mBackend = new FakeBackend();
        mService = new ChannelConfigurationService(mBackend, Runnable::run, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown()
    {
        if(mService != null)
        {
            mService.close();
        }
    }

    @Test
    void exposesOnlyRetainedProtocolsWithSavableApplicationDefaults() throws Exception
    {
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> protocols = (List<Map<String,Object>>)options(
            mService.list(ChannelListRequest.defaults()).get(2, TimeUnit.SECONDS)).get("supportedProtocols");
        assertEquals(List.of("P25_CONVENTIONAL", "P25_PHASE1", "P25_PHASE2", "DMR", "NBFM", "NXDN"),
            protocols.stream().map(option -> String.valueOf(option.get("id"))).toList());

        for(String protocol: protocols.stream().map(option -> String.valueOf(option.get("id"))).toList())
        {
            Map<String,Object> template = mService.template(protocol).get(2, TimeUnit.SECONDS);
            assertEquals(protocol, template.get("protocol"));
            assertEquals(Boolean.TRUE, template.get("template"));
            assertNull(template.get("id"));
            assertNotNull(template.get("guid"));
        }

        Map<String,Object> p25 = mService.template("P25_PHASE1").get(2, TimeUnit.SECONDS);
        assertEquals(500, source(p25).get("rotationDelayMs"));
        assertEquals("MULTIPLE", source(p25).get("kind"));
        assertEquals("C4FM", decoder(p25).get("modulation"));

        Map<String,Object> nbfm = mService.template("NBFM").get(2, TimeUnit.SECONDS);
        assertEquals("SINGLE", source(nbfm).get("kind"));
        assertEquals("BW_12_5", decoder(nbfm).get("bandwidth"));
        assertEquals(1.0, ((Number)decoder(nbfm).get("outputGain")).doubleValue(), 0.0001);
    }

    @Test
    void createsUpdatesClonesAndPreservesIndependentDecoderMaps() throws Exception
    {
        Map<String,Object> created = create(dmrWrite("Dispatch", null, ApplyPolicy.APPLY,
            List.of(new FrequencyMapRequest(12, 451_012_500L, 456_012_500L))));
        String id = String.valueOf(created.get("id"));
        assertTrue(id.matches("CHN_[0-9A-F]{28}"));
        assertEquals("Dispatch", created.get("name"));
        assertEquals(1, mBackend.channels.size());

        Map<String,Object> detail = mService.detail(id).get(2, TimeUnit.SECONDS);
        assertEquals(id, detail.get("id"));
        String firstRevision = String.valueOf(detail.get("revision"));

        Map<String,Object> updated = mService.update(id, preserveIdentity(
            dmrWrite("Dispatch Updated", firstRevision, ApplyPolicy.APPLY,
                List.of(new FrequencyMapRequest(12, 451_012_500L, null))), firstRevision,
                String.valueOf(detail.get("guid"))), () -> true)
            .get(2, TimeUnit.SECONDS);
        assertEquals("Dispatch Updated", updated.get("name"));
        DecodeConfigDMR updatedDecoder = (DecodeConfigDMR)mBackend.channels.get(0).getDecodeConfiguration();
        assertEquals(456_012_500L, updatedDecoder.getTimeslotMap().get(0).getUplinkFrequency(),
            "an omitted hidden uplink must round-trip unchanged");

        SourceConfigTunerMultipleFrequency originalSource =
            (SourceConfigTunerMultipleFrequency)mBackend.channels.get(0).getSourceConfiguration();
        originalSource.setFrequencies(List.of(451_012_500L, 452_012_500L));
        originalSource.setPreferredFrequency(452_012_500L);
        updated = mService.detail(id).get(2, TimeUnit.SECONDS);

        Map<String,Object> cloned = mService.cloneChannel(id,
            new RevisionRequest(String.valueOf(updated.get("revision"))), () -> true).get(2, TimeUnit.SECONDS);
        assertEquals(2, mBackend.channels.size());
        assertNotEquals(updated.get("guid"), cloned.get("guid"));
        assertNull(cloned.get("autoStartOrder"));
        DecodeConfigDMR originalDecoder = (DecodeConfigDMR)mBackend.channels.get(0).getDecodeConfiguration();
        DecodeConfigDMR clonedDecoder = (DecodeConfigDMR)mBackend.channels.get(1).getDecodeConfiguration();
        SourceConfigTunerMultipleFrequency clonedSource =
            (SourceConfigTunerMultipleFrequency)mBackend.channels.get(1).getSourceConfiguration();
        assertNotSame(originalDecoder, clonedDecoder);
        assertNotSame(originalDecoder.getTimeslotMap().get(0), clonedDecoder.getTimeslotMap().get(0));
        assertEquals(452_012_500L, clonedSource.getPreferredFrequency(),
            "the hidden preferred starting frequency must survive cloning");
        clonedDecoder.getTimeslotMap().get(0).setDownlinkFrequency(460_000_000L);
        assertEquals(451_012_500L, originalDecoder.getTimeslotMap().get(0).getDownlinkFrequency());
    }

    @Test
    void normalizesLegacySingleFrequencyTrunkedSourceOnSave() throws Exception
    {
        Channel channel = new Channel();
        channel.setSystem("County");
        channel.setSite("North");
        channel.setName("Legacy");
        channel.setRadresGuid(UUID.randomUUID().toString());
        channel.setAliasListName("Default");
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(DecoderType.P25_PHASE1));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        source.setPreferredTuner("Airspy");
        channel.setSourceConfiguration(source);
        mBackend.channels.add(channel);

        Map<String,Object> detail = firstItemDetail();
        assertEquals("MULTIPLE", source(detail).get("kind"));
        assertEquals(List.of(851_012_500L), source(detail).get("frequenciesHz"));
        assertEquals(500, source(detail).get("rotationDelayMs"));

        P25Phase1Request decoder = new P25Phase1Request("C4FM", 20, true, false);
        ChannelWriteRequest write = new ChannelWriteRequest(String.valueOf(detail.get("revision")), ApplyPolicy.APPLY,
            "County", "North", "Legacy", String.valueOf(detail.get("guid")), false, "Default",
            new SourceRequest(SourceKind.MULTIPLE, List.of(851_012_500L), "Airspy", 500), decoder,
            List.of(), List.of(), List.of());
        Map<String,Object> saved = mService.update(String.valueOf(detail.get("id")), write, () -> true)
            .get(2, TimeUnit.SECONDS);
        assertEquals("MULTIPLE", source(saved).get("kind"));
    }

    @Test
    void clearsOptionalManualP25Phase2ScrambleParameters() throws Exception
    {
        Map<String,Object> created = create(p25Phase2Write("Phase 2", null, ApplyPolicy.APPLY,
            new P25Phase2Request(20, true, false, false, "BEE00", "123", "456")));
        assertEquals("BEE00", decoder(created).get("wacn"));

        ChannelWriteRequest cleared = p25Phase2Write("Phase 2", String.valueOf(created.get("revision")),
            ApplyPolicy.APPLY, new P25Phase2Request(20, true, false, false, "", "", ""));
        Map<String,Object> saved = mService.update(String.valueOf(created.get("id")),
            preserveIdentity(cleared, String.valueOf(created.get("revision")), String.valueOf(created.get("guid"))),
            () -> true).get(2, TimeUnit.SECONDS);

        assertEquals("", decoder(saved).get("wacn"));
        assertEquals("", decoder(saved).get("p25System"));
        assertEquals("", decoder(saved).get("nac"));
        assertNull(((DecodeConfigP25Phase2)mBackend.channels.get(0).getDecodeConfiguration())
            .getScrambleParameters());
    }

    @Test
    void shiftsAutomaticStartAtomicallyAndLeavesInvalidCommandsUntouched() throws Exception
    {
        Map<String,Object> first = create(nbfmWrite("One", null, ApplyPolicy.APPLY, 155_100_000L));
        Map<String,Object> second = create(nbfmWrite("Two", null, ApplyPolicy.APPLY, 155_200_000L));
        Map<String,Object> third = create(nbfmWrite("Three", null, ApplyPolicy.APPLY, 155_300_000L));
        Map<String,Object> list = mService.list(ChannelListRequest.defaults()).get(2, TimeUnit.SECONDS);
        String queue = String.valueOf(list.get("queueRevision"));

        Map<String,Object> response = autoStart(first, queue, AutoStartAction.ENABLE);
        queue = String.valueOf(response.get("queueRevision"));
        first = mService.detail(String.valueOf(first.get("id"))).get(2, TimeUnit.SECONDS);
        response = autoStart(second, queue, AutoStartAction.ENABLE);
        queue = String.valueOf(response.get("queueRevision"));
        second = mService.detail(String.valueOf(second.get("id"))).get(2, TimeUnit.SECONDS);
        response = autoStart(third, queue, AutoStartAction.ENABLE);
        queue = String.valueOf(response.get("queueRevision"));

        assertEquals(1, mService.detail(String.valueOf(third.get("id"))).get().get("autoStartOrder"));
        assertEquals(2, mService.detail(String.valueOf(second.get("id"))).get().get("autoStartOrder"));
        assertEquals(3, mService.detail(String.valueOf(first.get("id"))).get().get("autoStartOrder"));

        Map<Channel,Integer> before = autoStartOrders();
        ChannelConfigurationException failure = failure(mService.autoStart(String.valueOf(first.get("id")),
            new AutoStartRequest(String.valueOf(mService.detail(String.valueOf(first.get("id"))).get().get("revision")),
                queue, AutoStartAction.LATER), () -> true));
        assertEquals(422, failure.status());
        assertEquals(before, autoStartOrders());
    }

    @Test
    void rollsBackFailedPersistenceAndRejectsStaleEdits() throws Exception
    {
        Map<String,Object> created = create(nbfmWrite("Stable", null, ApplyPolicy.APPLY, 155_100_000L));
        mBackend.failFlushes = 1;
        ChannelConfigurationException saveFailure = failure(mService.update(String.valueOf(created.get("id")),
            preserveIdentity(nbfmWrite("Must Roll Back", String.valueOf(created.get("revision")), ApplyPolicy.APPLY,
                155_100_000L), String.valueOf(created.get("revision")), String.valueOf(created.get("guid"))),
            () -> true));
        assertEquals("save_failed", saveFailure.code());
        assertEquals("Stable", mBackend.channels.get(0).getName());

        ChannelConfigurationException stale = failure(mService.update(String.valueOf(created.get("id")),
            preserveIdentity(nbfmWrite("Stale", "REV_00000000000000000000000000000000", ApplyPolicy.APPLY,
                155_100_000L), "REV_00000000000000000000000000000000", String.valueOf(created.get("guid"))),
            () -> true));
        assertEquals(409, stale.status());
        assertEquals("channel_changed", stale.code());
    }

    @Test
    void reportsIncompleteRollbackAndInvalidMissingFields() throws Exception
    {
        mBackend.failFlushes = 2;
        ChannelConfigurationException rollback = failure(mService.create(
            nbfmWrite("Rollback", null, ApplyPolicy.APPLY, 155_100_000L), () -> true));
        assertEquals(500, rollback.status());
        assertEquals("rollback_incomplete", rollback.code());
        assertTrue(mBackend.channels.isEmpty());

        assertEquals(422, failure(mService.create(null, () -> true)).status());
        ChannelWriteRequest valid = nbfmWrite("Nested validation", null, ApplyPolicy.APPLY, 155_100_000L);
        ChannelWriteRequest missingDecoder = new ChannelWriteRequest(valid.revision(), valid.applyPolicy(),
            valid.system(), valid.site(), valid.name(), valid.guid(), valid.confirmGuidChange(), valid.aliasList(),
            valid.source(), null, valid.auxiliaries(), valid.logging(), valid.recording());
        assertEquals(422, failure(mService.create(missingDecoder, () -> true)).status());
        ChannelWriteRequest missingSource = new ChannelWriteRequest(valid.revision(), valid.applyPolicy(),
            valid.system(), valid.site(), valid.name(), valid.guid(), valid.confirmGuidChange(), valid.aliasList(),
            null, valid.decoder(), valid.auxiliaries(), valid.logging(), valid.recording());
        assertEquals(422, failure(mService.create(missingSource, () -> true)).status());

        Map<String,Object> created = create(nbfmWrite("Validation", null, ApplyPolicy.APPLY, 155_100_000L));
        assertEquals(422, failure(mService.runtime(String.valueOf(created.get("id")),
            new RuntimeRequest(String.valueOf(created.get("revision")), null), () -> true)).status());
        assertEquals(422, failure(mService.delete(String.valueOf(created.get("id")), null, () -> true)).status());
    }

    @Test
    void closeCancelsDeferredConfigurationTasksAndCompletesQueuedCommands() throws Exception
    {
        LinkedBlockingQueue<Runnable> configurationTasks = new LinkedBlockingQueue<>();
        ChannelConfigurationService deferred = new ChannelConfigurationService(mBackend, configurationTasks::add,
            Duration.ofMillis(250));

        try
        {
            CompletableFuture<Map<String,Object>> first = deferred.create(
                nbfmWrite("Deferred", null, ApplyPolicy.APPLY, 155_100_000L), () -> true);
            CompletableFuture<Map<String,Object>> second = deferred.create(
                nbfmWrite("Queued", null, ApplyPolicy.APPLY, 155_200_000L), () -> true);
            Runnable delayed = configurationTasks.poll(1, TimeUnit.SECONDS);
            assertNotNull(delayed);

            deferred.close();
            assertEquals(503, failure(first).status());
            assertEquals(503, failure(second).status());

            delayed.run();
            assertTrue(mBackend.channels.isEmpty(), "a deferred JavaFX task must not mutate after close");
        }
        finally
        {
            deferred.close();
        }
    }

    @Test
    void closeWaitsForAnActuallyRunningConfigurationCallback() throws Exception
    {
        mBackend.aliasListsEntered = new CountDownLatch(1);
        mBackend.releaseAliasLists = new CountDownLatch(1);
        ChannelConfigurationService deferred = new ChannelConfigurationService(mBackend, task ->
        {
            Thread thread = new Thread(task, "test configuration thread");
            thread.setDaemon(true);
            thread.start();
        }, Duration.ofMillis(100));
        CompletableFuture<Map<String,Object>> completion = deferred.create(
            nbfmWrite("Running callback", null, ApplyPolicy.APPLY, 155_100_000L), () -> true);
        assertTrue(mBackend.aliasListsEntered.await(1, TimeUnit.SECONDS));

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() ->
        {
            try
            {
                deferred.close();
            }
            catch(Throwable throwable)
            {
                closeFailure.set(throwable);
            }
        }, "test channel service closer");
        closer.start();
        Thread.sleep(150);
        assertTrue(closer.isAlive(), "close must still be waiting for the running callback's real exit");
        assertFalse(mBackend.aliasListsExited);
        mBackend.releaseAliasLists.countDown();
        closer.join(1_000);

        assertFalse(closer.isAlive());
        assertNull(closeFailure.get());
        assertTrue(mBackend.aliasListsExited);
        assertEquals(1, mBackend.channels.size(), "the running command must exit before close returns");
        assertEquals(503, failure(completion).status());
    }

    @Test
    void keepsRuntimeCommandsAndRunningEditsExplicit() throws Exception
    {
        Map<String,Object> created = create(nbfmWrite("Runtime", null, ApplyPolicy.APPLY, 155_100_000L));
        String id = String.valueOf(created.get("id"));
        String revision = String.valueOf(created.get("revision"));
        Map<String,Object> running = mService.runtime(id, new RuntimeRequest(revision, RuntimeAction.START), () -> true)
            .get(2, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, running.get("processing"));

        ChannelConfigurationException choiceRequired = failure(mService.update(id,
            preserveIdentity(nbfmWrite("Runtime Renamed", String.valueOf(running.get("revision")), ApplyPolicy.APPLY,
                155_100_000L), String.valueOf(running.get("revision")), String.valueOf(running.get("guid"))),
            () -> true));
        assertEquals("running_policy_required", choiceRequired.code());

        Channel live = mBackend.channels.get(0);
        Object sourceBefore = live.getSourceConfiguration();
        Object decoderBefore = live.getDecodeConfiguration();
        Map<String,Object> nextStart = mService.update(id,
            preserveIdentity(nbfmWrite("Runtime Renamed", String.valueOf(running.get("revision")),
                ApplyPolicy.NEXT_START, 155_100_000L), String.valueOf(running.get("revision")),
                String.valueOf(running.get("guid"))), () -> true).get(2, TimeUnit.SECONDS);
        assertEquals("Runtime Renamed", nextStart.get("name"));
        assertSame(sourceBefore, live.getSourceConfiguration());
        assertSame(decoderBefore, live.getDecodeConfiguration());
        assertTrue(mBackend.isProcessing(live));

        ChannelConfigurationException stopRequired = failure(mService.update(id,
            preserveIdentity(nbfmWrite("Runtime Renamed", String.valueOf(nextStart.get("revision")),
                ApplyPolicy.NEXT_START, 155_200_000L), String.valueOf(nextStart.get("revision")),
                String.valueOf(nextStart.get("guid"))), () -> true));
        assertEquals("stop_required", stopRequired.code());

        Map<String,Object> restarted = mService.update(id,
            preserveIdentity(nbfmWrite("Runtime Renamed", String.valueOf(nextStart.get("revision")),
                ApplyPolicy.RESTART, 155_200_000L), String.valueOf(nextStart.get("revision")),
                String.valueOf(nextStart.get("guid"))), () -> true).get(2, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, restarted.get("processing"));
        assertEquals(2, mBackend.startCalls);
        assertEquals(1, mBackend.stopCalls);
    }

    @Test
    void validatesSessionTimeoutAndExistingAliasList() throws Exception
    {
        ChannelConfigurationException session = failure(mService.create(
            nbfmWrite("No Session", null, ApplyPolicy.APPLY, 155_100_000L), () -> false));
        assertEquals(401, session.status());
        assertTrue(mBackend.channels.isEmpty());

        ChannelWriteRequest missingAlias = nbfmWrite("Wrong Alias", null, ApplyPolicy.APPLY, 155_100_000L);
        missingAlias = new ChannelWriteRequest(missingAlias.revision(), missingAlias.applyPolicy(),
            missingAlias.system(), missingAlias.site(), missingAlias.name(), missingAlias.guid(), false, "Missing",
            missingAlias.source(), missingAlias.decoder(), missingAlias.auxiliaries(), missingAlias.logging(),
            missingAlias.recording());
        assertEquals(422, failure(mService.create(missingAlias, () -> true)).status());

        assertEquals(15, mService.setAutoStartTimeout(new TimeoutRequest(15), () -> true).get(2, TimeUnit.SECONDS)
            .get("autoStartTimeoutSeconds"));
        assertEquals(15, mBackend.timeoutSeconds);
        assertEquals(422, failure(mService.setAutoStartTimeout(new TimeoutRequest(31), () -> true)).status());
        assertEquals(422, failure(mService.setAutoStartTimeout(new TimeoutRequest(null), () -> true)).status());
    }

    @Test
    void gatesStartupAndRequiresExplicitStoppedGuidChanges() throws Exception
    {
        mBackend.ready = false;
        ChannelConfigurationException initializing = failure(mService.list(ChannelListRequest.defaults()));
        assertEquals(503, initializing.status());
        assertEquals("settings_initializing", initializing.code());
        assertTrue(mBackend.channels.isEmpty());
        mBackend.ready = true;

        Map<String,Object> created = create(nbfmWrite("Identity", null, ApplyPolicy.APPLY, 155_100_000L));
        String id = String.valueOf(created.get("id"));
        String revision = String.valueOf(created.get("revision"));
        String changedGuid = UUID.randomUUID().toString();
        ChannelWriteRequest changed = withGuid(nbfmWrite("Identity", revision, ApplyPolicy.APPLY, 155_100_000L),
            revision, changedGuid, false);
        ChannelConfigurationException confirmation = failure(mService.update(id, changed, () -> true));
        assertEquals("guid_confirmation_required", confirmation.code());

        Map<String,Object> saved = mService.update(id, withGuid(changed, revision, changedGuid, true), () -> true)
            .get(2, TimeUnit.SECONDS);
        assertEquals(changedGuid, saved.get("guid"));
        Map<String,Object> running = mService.runtime(id,
            new RuntimeRequest(String.valueOf(saved.get("revision")), RuntimeAction.START), () -> true)
            .get(2, TimeUnit.SECONDS);
        ChannelConfigurationException stop = failure(mService.update(id,
            withGuid(nbfmWrite("Identity", String.valueOf(running.get("revision")), ApplyPolicy.NEXT_START,
                155_100_000L), String.valueOf(running.get("revision")), UUID.randomUUID().toString(), true),
            () -> true));
        assertEquals("stop_required", stop.code());
    }

    private Map<String,Object> create(ChannelWriteRequest request) throws Exception
    {
        return mService.create(request, () -> true).get(2, TimeUnit.SECONDS);
    }

    private Map<String,Object> firstItemDetail() throws Exception
    {
        Map<String,Object> list = mService.list(ChannelListRequest.defaults()).get(2, TimeUnit.SECONDS);
        @SuppressWarnings("unchecked")
        Map<String,Object> item = ((List<Map<String,Object>>)list.get("items")).get(0);
        return mService.detail(String.valueOf(item.get("id"))).get(2, TimeUnit.SECONDS);
    }

    private Map<String,Object> autoStart(Map<String,Object> channel, String queue, AutoStartAction action)
        throws Exception
    {
        return mService.autoStart(String.valueOf(channel.get("id")),
            new AutoStartRequest(String.valueOf(channel.get("revision")), queue, action), () -> true)
            .get(2, TimeUnit.SECONDS);
    }

    private Map<Channel,Integer> autoStartOrders()
    {
        Map<Channel,Integer> result = new IdentityHashMap<>();
        mBackend.channels.forEach(channel -> result.put(channel, channel.getAutoStartOrder()));
        return result;
    }

    private static ChannelConfigurationException failure(java.util.concurrent.CompletableFuture<?> completion)
    {
        CompletionException failure = assertThrows(CompletionException.class, completion::join);
        assertTrue(failure.getCause() instanceof ChannelConfigurationException);
        return (ChannelConfigurationException)failure.getCause();
    }

    private static ChannelWriteRequest nbfmWrite(String name, String revision, ApplyPolicy policy, long frequency)
    {
        return new ChannelWriteRequest(revision, policy, "County", "North", name, UUID.randomUUID().toString(),
            false, "Default", new SourceRequest(SourceKind.SINGLE, List.of(frequency), "", null),
            new NbfmRequest("BW_12_5", 1, "NONE", 1.0f, true, false, 3400, 30.0f,
                false, 180, 0, 0.0f), List.of(), List.of(), List.of());
    }

    private static ChannelWriteRequest dmrWrite(String name, String revision, ApplyPolicy policy,
                                                 List<FrequencyMapRequest> map)
    {
        return new ChannelWriteRequest(revision, policy, "County", "North", name, UUID.randomUUID().toString(),
            false, "Default", new SourceRequest(SourceKind.MULTIPLE, List.of(451_012_500L), "", 500),
            new DmrRequest(20, true, false, false, map), List.of(), List.of(), List.of());
    }

    private static ChannelWriteRequest p25Phase2Write(String name, String revision, ApplyPolicy policy,
                                                       P25Phase2Request decoder)
    {
        return new ChannelWriteRequest(revision, policy, "County", "North", name, UUID.randomUUID().toString(),
            false, "Default", new SourceRequest(SourceKind.MULTIPLE, List.of(851_012_500L), "", 500), decoder,
            List.of(), List.of(), List.of());
    }

    private static ChannelWriteRequest preserveIdentity(ChannelWriteRequest write, String revision, String guid)
    {
        return new ChannelWriteRequest(revision, write.applyPolicy(), write.system(), write.site(), write.name(),
            guid, false, write.aliasList(), write.source(), write.decoder(), write.auxiliaries(), write.logging(),
            write.recording());
    }

    private static ChannelWriteRequest withGuid(ChannelWriteRequest write, String revision, String guid,
                                                 boolean confirmation)
    {
        return new ChannelWriteRequest(revision, write.applyPolicy(), write.system(), write.site(), write.name(),
            guid, confirmation, write.aliasList(), write.source(), write.decoder(), write.auxiliaries(),
            write.logging(), write.recording());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> source(Map<String,Object> response)
    {
        return (Map<String,Object>)response.get("source");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> decoder(Map<String,Object> response)
    {
        return (Map<String,Object>)response.get("decoder");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> options(Map<String,Object> response)
    {
        return (Map<String,Object>)response.get("options");
    }

    private static final class FakeBackend implements ChannelConfigurationService.Backend
    {
        private final List<Channel> channels = new ArrayList<>();
        private final Set<Channel> running = Collections.newSetFromMap(new IdentityHashMap<>());
        private int timeoutSeconds = 10;
        private int failFlushes;
        private int startCalls;
        private int stopCalls;
        private boolean ready = true;
        private CountDownLatch aliasListsEntered;
        private CountDownLatch releaseAliasLists;
        private volatile boolean aliasListsExited;

        @Override
        public boolean isReady()
        {
            return ready;
        }

        @Override
        public List<Channel> channels()
        {
            return channels;
        }

        @Override
        public List<String> aliasLists()
        {
            if(aliasListsEntered != null)
            {
                aliasListsEntered.countDown();

                try
                {
                    releaseAliasLists.await();
                    aliasListsExited = true;
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }

            return List.of("Default", "Fire");
        }

        @Override
        public List<String> preferredTunerNames()
        {
            return List.of("Airspy", "RTL-SDR");
        }

        @Override
        public int autoStartTimeoutSeconds()
        {
            return timeoutSeconds;
        }

        @Override
        public void setAutoStartTimeoutSeconds(int seconds)
        {
            timeoutSeconds = seconds;
        }

        @Override
        public boolean jmbeConfigured()
        {
            return true;
        }

        @Override
        public boolean isProcessing(Channel channel)
        {
            return running.contains(channel);
        }

        @Override
        public void add(Channel channel, int index)
        {
            channels.add(Math.max(0, Math.min(index, channels.size())), channel);
        }

        @Override
        public void remove(Channel channel)
        {
            channels.remove(channel);
            running.remove(channel);
        }

        @Override
        public void configurationChanged()
        {
        }

        @Override
        public void flushOrThrow()
        {
            if(failFlushes > 0)
            {
                failFlushes--;
                throw new IllegalStateException("simulated persistence failure");
            }
        }

        @Override
        public void start(Channel channel)
        {
            startCalls++;
            running.add(channel);
        }

        @Override
        public void stop(Channel channel)
        {
            stopCalls++;
            running.remove(channel);
        }
    }
}

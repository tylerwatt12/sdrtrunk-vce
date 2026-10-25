/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.UnknownOSPMessage;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the production handoff across persistence, processing chains, allocation, browse ownership and probe
 * holds. Only RF hardware is substituted. Repeated decoded broadcasts are supplied at the RF evidence boundary;
 * demodulation correctness belongs to decoder tests, rather than this lifecycle regression.
 */
@Timeout(15)
class SpectrumDiscoveryHandoffIntegrationTest
{
    private static final long OLD_CENTER = 155_000_000;
    private static final long BROWSE_CENTER = 857_000_000;
    private static final long CONTROL_FREQUENCY = 857_100_000;
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x123;
    private static final int NAC = 0x293;
    @TempDir Path root;

    @Test
    void takeoverOfTwoRunningChannelsStartsDetectedChannelBeforeRecoveringIncompatiblePreviousChannels()
        throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var ready = fixture.takeoverAndIdentify();
            assertEquals("p25-phase1", ready.protocolId());
            assertEquals(new P25SiteIdentity(WACN, SYSTEM, 2, 7), ready.trunkedEvidence().identity().p25());
            assertFalse(fixture.tuner.isAvailableForAllocation(), "Review must retain Setup ownership");
            assertThrows(TunerSettingsService.SettingUnavailableException.class,
                () -> fixture.settings.holdForProbe(fixture.tuner.getTuner()),
                "The actual probe reservation must remain held during Review");

            var complete = fixture.discovery.save(ready.sessionId(), request(ready));

            fixture.assertSavedAndRunning(complete);
            assertNull(complete.saved().browseLeaseId());
            assertEquals(Set.copyOf(fixture.previousIds), complete.saved().previousChannelsNotResumed().stream()
                .map(TunerSettingsService.ChannelInfo::id).collect(java.util.stream.Collectors.toSet()),
                "Completion must report the actual old channels that could not resume");
            assertEquals(Set.copyOf(fixture.previousIds), fixture.settings.stoppedChannels(fixture.tuner).stream()
                .map(TunerSettingsService.ChannelInfo::id).collect(java.util.stream.Collectors.toSet()),
                "Previous channels outside the new window remain available for explicit recovery");
            assertTrue(fixture.controller.centers.stream().allMatch(center -> center == BROWSE_CENTER),
                "Recovery must not retune away before the selected channel starts");
            assertFalse(fixture.settings.verifyBrowse(fixture.tuner, fixture.lease.leaseId()));
            assertEquals(DiscoveredTuner.OperatorState.LIVE, fixture.tuner.getOperatorState());
            try(var released = fixture.settings.holdForProbe(fixture.tuner.getTuner()))
            {
                assertTrue(released.valid(), "A completed handoff must release its probe hold");
            }
        }
    }

    @Test
    void takeoverResumesBothCompatibleChannelsWithoutRetuningTheNewChannel() throws Exception
    {
        try(Fixture fixture = new Fixture(root, BROWSE_CENTER))
        {
            var ready = fixture.takeoverAndIdentify();
            var complete = fixture.discovery.save(ready.sessionId(), request(ready));

            fixture.assertSavedAndRunning(complete);
            assertTrue(complete.saved().previousChannelsNotResumed().isEmpty());
            assertTrue(fixture.settings.stoppedChannels(fixture.tuner).isEmpty());
            assertTrue(fixture.previousIds.stream().allMatch(id -> fixture.channels.get(id).processingState() ==
                ChannelAdministrationService.ProcessingState.RUNNING));
            assertEquals(3, fixture.sources.getTunerChannelCount());
            assertTrue(fixture.controller.centers.stream().allMatch(center -> center == BROWSE_CENTER));
        }
    }

    @Test
    void failedRealAllocationThenRetryKeepsOnePersistedChannelAndItsAliasList() throws Exception
    {
        try(Fixture fixture = new Fixture(root))
        {
            var ready = fixture.takeoverAndIdentify();
            // A hardware admission failure, rather than a mocked start result: the real allocator rejects this
            // channel while its frequency lies outside the RF controller's temporarily available tuning range.
            fixture.controller.setMinimumFrequency(CONTROL_FREQUENCY + 1_000_000);
            var saved = fixture.discovery.save(ready.sessionId(), request(ready));
            assertNotNull(saved.saved());
            assertFalse(saved.saved().running());
            assertNotNull(saved.saved().startError());
            assertEquals(Set.copyOf(fixture.previousIds), saved.saved().previousChannelsNotResumed().stream()
                .map(TunerSettingsService.ChannelInfo::id).collect(java.util.stream.Collectors.toSet()));
            var diskBeforeRetry = fixture.repository.load();
            assertEquals(3, diskBeforeRetry.channels().size());
            assertEquals(fixture.initialAliasListCount + 1, diskBeforeRetry.aliasListDefinitions().size());
            String savedId = saved.saved().configurationId();
            long aliasId = saved.saved().aliasListId();
            var repeatedSave = fixture.discovery.save(saved.sessionId(), request(ready));
            assertEquals(savedId, repeatedSave.saved().configurationId());
            assertEquals(aliasId, repeatedSave.saved().aliasListId());
            fixture.discovery.cancel(saved.sessionId());
            assertThrows(IllegalStateException.class, () -> fixture.discovery.status(saved.sessionId()),
                "Retry must not depend on a live RF wizard");

            fixture.controller.setMinimumFrequency(1);
            var retried = fixture.channels.setProcessing(List.of(savedId), true).results().getFirst();
            assertTrue(retried.success(), retried.message());
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING, retried.state());
            fixture.assertPersistedAndRunning(savedId, aliasId);
            var diskAfterRetry = fixture.repository.load();
            assertEquals(diskBeforeRetry.channels().stream().map(channel -> channel.getConfigurationId()).toList(),
                diskAfterRetry.channels().stream().map(channel -> channel.getConfigurationId()).toList());
            assertEquals(diskBeforeRetry.aliasListDefinitions().stream().map(list -> list.getId()).toList(),
                diskAfterRetry.aliasListDefinitions().stream().map(list -> list.getId()).toList());
            assertEquals(aliasId, diskAfterRetry.channels().stream()
                .filter(channel -> savedId.equals(channel.getConfigurationId())).findFirst().orElseThrow().getAliasListId());
            assertFalse(fixture.settings.verifyBrowse(fixture.tuner, fixture.lease.leaseId()));
            try(var released = fixture.settings.holdForProbe(fixture.tuner.getTuner())) { assertTrue(released.valid()); }
        }
    }

    private static SpectrumDiscoveryService.SaveRequest request(SpectrumDiscoveryService.Snapshot ready)
    {
        return new SpectrumDiscoveryService.SaveRequest("County P25", "North", "County P25 · North", 0,
            "County P25", ready.review().template().settings(), ready.review().revision());
    }

    private static final class Fixture implements AutoCloseable
    {
        final QuietRfController controller = new QuietRfController();
        final PolyphaseChannelSourceManager sources = new PolyphaseChannelSourceManager(controller);
        final FakeRfTuner tuner = new FakeRfTuner(controller, sources);
        final TunerManager tuners;
        final ConfigurationManager configuration;
        final ConfigurationRepository repository;
        final ChannelAdministrationService channels;
        final TunerSettingsService settings;
        final DiagnosticFftScheduler fft = new DiagnosticFftScheduler();
        final TunerDiagnosticService diagnostics;
        final SpectrumDiscoveryService discovery;
        final List<String> previousIds = new ArrayList<>();
        final int initialAliasListCount;
        final long previousCenter;
        TunerSettingsService.BrowseLease lease;

        Fixture(Path root) throws Exception
        {
            this(root, OLD_CENTER);
        }

        Fixture(Path root, long previousCenter) throws Exception
        {
            this.previousCenter = previousCenter;
            Path database = SdrTrunkDatabasePath.getDatabasePath(root);
            SdrTrunkTestDatabase.create(database);
            UserPreferences preferences = new UserPreferences()
            {
                final DirectoryPreference directory = new DirectoryPreference(type -> {})
                { @Override public Path getDirectoryApplicationRoot() { return root; } };
                @Override public DirectoryPreference getDirectoryPreference() { return directory; }
            };
            // Use the existing dependency seams so neither tuner settings nor configuration touches a real profile.
            Constructor<TunerConfigurationManager> configurationConstructor = TunerConfigurationManager.class
                .getDeclaredConstructor(ApplicationSettingsStore.class, Executor.class);
            configurationConstructor.setAccessible(true);
            var tunerConfiguration = configurationConstructor.newInstance(new ApplicationSettingsStore(database),
                (Executor)Runnable::run);
            Constructor<TunerManager> tunerConstructor = TunerManager.class
                .getDeclaredConstructor(UserPreferences.class, TunerConfigurationManager.class);
            tunerConstructor.setAccessible(true);
            tuners = tunerConstructor.newInstance(preferences, tunerConfiguration);
            tuners.getDiscoveredTunerRegistry().add(tuner);
            AliasModel aliases = new AliasModel();
            configuration = new ConfigurationManager(preferences, tuners, aliases, new EventLogManager(aliases, preferences));
            configuration.init();
            repository = new ConfigurationRepository(database);
            channels = ChannelAdministrationServiceTestSupport.create(configuration);
            settings = new TunerSettingsService(tuners, configuration.getChannelProcessingManager());
            diagnostics = new TunerDiagnosticService(tuners, fft);
            discovery = new SpectrumDiscoveryService(channels,
                new TunerAdministrationService(tuners, diagnostics, settings), diagnostics, settings,
                new StatsWebDatabase(preferences, database));
            long aliasList = AliasAdministrationServiceTestSupport.create(configuration)
                .createAliasList("Existing analog", AliasListFamily.NBFM).aliasListId();
            initialAliasListCount = repository.load().aliasListDefinitions().size();
            for(int index = 0; index < 2; index++)
            {
                ChannelDefinition template = channels.template("nbfm");
                var definition = new ChannelDefinition(null, "nbfm", "Existing", "Old site", "Existing " + index,
                    null, aliasList, new ChannelDefinition.Source(List.of(previousCenter + 300_000L + index * 200_000L),
                    null, null, null, tuner.getTuner().getPreferredName(), null), template.settings(),
                    List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
                previousIds.add(channels.create(definition).configurationIds().getFirst());
            }
            var starts = channels.setProcessing(previousIds, true);
            assertTrue(starts.results().stream().allMatch(result -> result.success() &&
                result.state() == ChannelAdministrationService.ProcessingState.RUNNING), starts.toString());
            assertEquals(2, sources.getTunerChannelCount(), "Both pre-existing channels must have real live sources");
        }

        SpectrumDiscoveryService.Snapshot takeoverAndIdentify() throws Exception
        {
            lease = settings.browse(tuner, null, true).get(3, TimeUnit.SECONDS);
            assertEquals(Set.copyOf(previousIds), lease.stoppedChannels().stream()
                .map(TunerSettingsService.ChannelInfo::id).collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, sources.getTunerChannelCount());
            var tuning = settings.set(tuner, "frequency_mhz", 857.0, lease.leaseId());
            assertEquals(BROWSE_CENTER, controller.getFrequency(), tuning.toString());
            controller.centers.clear();
            var opened = discovery.open(TunerAdministrationService.opaqueId(tuner), CONTROL_FREQUENCY, "auto", lease.leaseId());
            assertEquals("identifying", opened.state());
            assertTrue(sources.getTunerChannelCount() > 0, "Detection must use a real within-window source");
            Object wizard = field(discovery, "mWizard");
            P25DiscoveryProbe.Session probe = (P25DiscoveryProbe.Session)field(wizard, "probe");
            supplyDecodedControlSignal(probe);
            await(() -> "ready".equals(discovery.status(opened.sessionId()).state()));
            return discovery.status(opened.sessionId());
        }

        void assertSavedAndRunning(SpectrumDiscoveryService.Snapshot complete) throws Exception
        {
            assertNotNull(complete.saved());
            assertTrue(complete.saved().running(), complete.saved().startError());
            assertEquals("running", complete.state());
            assertNull(complete.saved().startError());
            assertPersistedAndRunning(complete.saved().configurationId(), complete.saved().aliasListId());
        }

        void assertPersistedAndRunning(String configurationId, long aliasListId) throws Exception
        {
            assertEquals(BROWSE_CENTER, controller.getFrequency());
            assertEquals(previousCenter == BROWSE_CENTER ? 3 : 1, sources.getTunerChannelCount());
            assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                channels.get(configurationId).processingState());
            assertEquals(List.of(CONTROL_FREQUENCY),
                channels.get(configurationId).channel().source().frequenciesHz());
            var saved = repository.load().channels().stream().filter(channel ->
                configurationId.equals(channel.getConfigurationId())).findFirst().orElseThrow();
            assertEquals(aliasListId, saved.getAliasListId());
            assertEquals(new P25SiteIdentity(WACN, SYSTEM, 2, 7), saved.getP25SiteIdentity());
            assertEquals(3, repository.load().channels().size());
        }

        @Override public void close() throws Exception
        {
            discovery.close();
            // Recover through the public operator workflow. Stopping the selected channel directly first avoids
            // remembering it as another channel to resume alongside the old, incompatible receiver window.
            List<String> running = channels.currentCatalog().channels().stream().filter(channel ->
                channel.processingState() == ChannelAdministrationService.ProcessingState.RUNNING)
                .map(ChannelAdministrationService.ChannelSummary::configurationId).toList();
            if(!running.isEmpty())
            {
                var stopped = channels.setProcessing(running, false);
                assertTrue(stopped.results().stream().allMatch(result -> result.success() &&
                    result.state() == ChannelAdministrationService.ProcessingState.STOPPED), stopped.toString());
            }
            if(!settings.stoppedChannels(tuner).isEmpty())
            {
                controller.setMinimumFrequency(1);
                settings.requestState(tuner, DiscoveredTuner.OperatorState.SETUP);
                await(() -> settings.transition(tuner) == null);
                assertNull(settings.error(tuner));
                settings.restoreStoppedChannels(tuner);
                await(() -> settings.transition(tuner) == null && settings.stoppedChannels(tuner).isEmpty());
                assertTrue(channels.currentCatalog().channels().stream()
                    .filter(channel -> previousIds.contains(channel.configurationId()))
                    .allMatch(channel -> channel.processingState() == ChannelAdministrationService.ProcessingState.RUNNING),
                    "Cleanup must actually restore the previous channels before shutting down the services");
                assertEquals(2, sources.getTunerChannelCount());
            }
            settings.close();
            diagnostics.close();
            fft.close();
            configuration.getChannelProcessingManager().close();
            configuration.close();
            tuners.stop();
        }
    }

    private static void supplyDecodedControlSignal(P25DiscoveryProbe.Session probe) throws Exception
    {
        var evidence = (P25DiscoveryProbe.ModeEvidence)field(probe, "mC4fm");
        for(int index = 0; index < 3; index++)
        {
            long timestamp = 1_000L + index * 1_000L;
            CorrectedBinaryMessage networkBits = fields(Opcode.OSP_NETWORK_STATUS_BROADCAST);
            networkBits.setInt(WACN, IntField.length20(24));
            networkBits.setInt(SYSTEM, IntField.length12(44));
            var network = new NetworkStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                networkBits, NAC, timestamp);
            network.setValid(true);
            evidence.receive(network);
            CorrectedBinaryMessage siteBits = fields(Opcode.OSP_RFSS_STATUS_BROADCAST);
            siteBits.setInt(SYSTEM, IntField.length12(28));
            siteBits.setInt(2, IntField.length8(40));
            siteBits.setInt(7, IntField.length8(48));
            var site = new RFSSStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                siteBits, NAC, timestamp);
            site.setValid(true);
            site.getChannel().setFrequencyBand(new P25FrequencyBand(0, CONTROL_FREQUENCY,
                -30_000_000, 6250, 12500, 1));
            evidence.receive(site);
        }
        for(int index = 0; index < 20; index++)
        {
            var message = new UnknownOSPMessage(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
                new CorrectedBinaryMessage(96), NAC, 4_000L + index);
            message.setValid(true);
            evidence.receive(message);
        }
        // Publish through the real sample queue so its release/acquire handoff also makes the synthetic decoded
        // evidence visible to the probe worker. The worker, rather than this test, decides whether it is ready.
        float[] i = new float[128];
        java.util.Arrays.fill(i, 0.5f);
        probe.offer(new ComplexSamples(i, new float[128], 4_000));
    }

    private static CorrectedBinaryMessage fields(Opcode opcode)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(opcode.getCode(), IntField.length6(2));
        return bits;
    }

    private static Object field(Object object, String name) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for real discovery worker");
    }

    private static final class QuietRfController extends TestTunerController
    {
        final List<Long> centers = new java.util.concurrent.CopyOnWriteArrayList<>();

        QuietRfController() throws SourceException
        {
            setUsableBandwidthPercentage(0.90);
            setFrequency(OLD_CENTER);
        }

        @Override public void setFrequency(long frequency) throws SourceException
        {
            centers.add(frequency);
            super.setFrequency(frequency);
        }

        @Override public void addBufferListener(Listener<INativeBuffer> listener) { /* No physical RF producer. */ }
        @Override public void removeBufferListener(Listener<INativeBuffer> listener) { }
    }

    private static final class FakeRfTuner extends DiscoveredTuner
    {
        FakeRfTuner(QuietRfController controller, PolyphaseChannelSourceManager sources) throws SourceException
        {
            mTuner = new Tuner(controller, this, sources)
            {
                @Override public int getMaximumUSBBitsPerSecond() { return 0; }
                @Override public String getUniqueID() { return "handoff-integration"; }
                @Override public String getPreferredName() { return getUniqueID(); }
                @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
                @Override public TunerType getTunerType() { return TunerType.TEST; }
                @Override public double getSampleSize() { return 16; }
            };
            var saved = new AirspyTunerConfiguration(getId());
            saved.setFrequency(OLD_CENTER);
            saved.setMinimumFrequency(1);
            saved.setMaximumFrequency(1_000_000_000);
            saved.setSampleRate(TestTunerController.SAMPLE_RATE);
            setTunerConfiguration(saved);
            mTuner.start();
        }

        @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
        @Override public String getId() { return "handoff-integration"; }
        @Override public void start() { /* The fake hardware is installed once; normal Setup keeps it running. */ }
    }
}

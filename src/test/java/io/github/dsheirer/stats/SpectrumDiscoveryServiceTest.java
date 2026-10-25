/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class SpectrumDiscoveryServiceTest
{
    @Test
    void abandoningEachBrowseLifecycleCancelsItsDirectoryWorkAndLetsTheNextWizardFinish() throws Exception
    {
        for(String lifecycle: List.of("cancel", "receiver cleanup", "expiry"))
        {
            try(DirectoryFixture fixture = new DirectoryFixture())
            {
                DirectoryWizard old = fixture.begin();
                assertTrue(fixture.gateway.entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    if("cancel".equals(lifecycle)) fixture.service.cancel(old.id());
                    else if("receiver cleanup".equals(lifecycle)) fixture.service.closeActiveSession();
                    else
                    {
                        field(old.wizard(), "expiresAt").setLong(old.wizard(), 0);
                        assertThrows(IllegalStateException.class, () -> fixture.service.status(old.id()));
                    }
                });
                assertTrue(old.future().isCancelled(), lifecycle);
                assertTrue(fixture.gateway.interrupted.await(2, TimeUnit.SECONDS), lifecycle);

                DirectoryWizard current = fixture.begin();
                current.future().get(2, TimeUnit.SECONDS);
                assertEquals(2, fixture.gateway.calls.get(), lifecycle);
                assertEquals("no_match", fixture.result(current).state(), lifecycle);
                assertEquals("pending", fixture.result(old).state(),
                    "The cancelled lookup must not publish its late terminal result");
                assertSame(current.wizard(), field(fixture.service, "mWizard").get(fixture.service));
            }
        }
    }

    @Test
    void closingDiscoveryCancelsAnActiveDirectoryLookupWithoutWaitingForItsNetworkWork() throws Exception
    {
        try(DirectoryFixture fixture = new DirectoryFixture())
        {
            DirectoryWizard wizard = fixture.begin();
            assertTrue(fixture.gateway.entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), fixture.service::close);
            assertTrue(wizard.future().isCancelled());
            assertTrue(fixture.gateway.interrupted.await(2, TimeUnit.SECONDS));
            assertEquals("pending", fixture.result(wizard).state());
        }
    }

    @Test
    void completedDirectoryFailuresRemainSaveableAndOnlyPendingDefersTheSharedSaveBoundary()
    {
        for(String state: List.of("matched", "no_match", "ambiguous", "unavailable", "login_required",
            "premium_required", "location_required", "identity_required", "manual"))
            assertDoesNotThrow(() -> SpectrumSearchService.requireDirectoryReady(List.of(
                RadioReferenceDiscoveryResolver.Result.manual(state, "Terminal lookup result"))));
        var pending = assertThrows(SpectrumSearchService.DirectoryPendingException.class, () ->
            SpectrumSearchService.requireDirectoryReady(List.of(RadioReferenceDiscoveryResolver.Result.pending())));
        assertTrue(pending.getMessage().contains("Wait for it to finish"));
    }

    @Test
    void nullNewListNameRegistersTheActualSystemFallbackAndExplicitNamesRemainAuthoritative()
    {
        var definition = new ChannelDefinition(null, "p25-phase1", "County Public Safety", "North", "Control",
            null, 0, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
                "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, SpectrumDiscoveryService.savedAliasListName(null, definition), definition.system(),
            RadioReferenceDiscoveryResolver.Result.manual());
        assertEquals("County Public Safety", batch.snapshot().targets().getFirst().aliasListName());
        assertEquals("Custom imported names", SpectrumDiscoveryService.savedAliasListName(" Custom imported names ", definition));
    }

    @Test
    void lateDirectoryNamesUpdateTheNewListDefaultForEveryTrunkedProtocolWithoutChangingExistingListChoices()
    {
        for(String protocol: List.of("p25-phase1", "dmr", "nxdn"))
        {
            var template = new ChannelDefinition(null, protocol, "Example catalog suggestion", "Site 02-0C", "Control",
                null, 23, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
                "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            var aliases = List.of(new ChannelAdministrationService.DiscoveryAliasList(23, "Existing List", true));
            var original = new ChannelAdministrationService.DiscoveryReview(7, template, aliases, 23L, "Example catalog suggestion");
            assertSame(original, SpectrumDiscoveryService.directoryReview(original,
                RadioReferenceDiscoveryResolver.Result.pending()));
            assertSame(original, SpectrumDiscoveryService.directoryReview(original,
                RadioReferenceDiscoveryResolver.Result.manual("no_match", "No directory match")));
            var match = new RadioReferenceDiscoveryResolver.Match(100, 200,
                "Metropolitan Emergency Communications Network", "North", List.of(),
                "https://www.radioreference.com/db/sid/100", "https://www.radioreference.com/db/site/200");
            var resolved = SpectrumDiscoveryService.directoryReview(original,
                new RadioReferenceDiscoveryResolver.Result("matched", "Matched", match, "radioreference"));
            assertEquals("Metropolitan Emergency Communications Network", resolved.template().system());
            assertEquals("North", resolved.template().site());
            assertEquals("Metropolitan Emergency Communications Network · North", resolved.template().name());
            assertEquals("Metropolitan Emergency Communications Network", resolved.defaultNewAliasListName());
            assertEquals(23, resolved.template().aliasListId());
            assertEquals(23L, resolved.suggestedAliasListId());
            assertEquals(aliases, resolved.aliasLists());
            assertEquals(template.source(), resolved.template().source());
            assertEquals(7, resolved.revision());
        }
    }

    @Test
    void emptyDirectoryNamesKeepTheKnownFriendlySystemAndItsGeneratedListName()
    {
        var template = new ChannelDefinition(null, "p25-phase1", "County Public Safety", "North", "Control",
            null, 0, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
            "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var original = new ChannelAdministrationService.DiscoveryReview(7, template, List.of(), null, "P25 BEE00-49F");
        var match = new RadioReferenceDiscoveryResolver.Match(100, 200, "", "", List.of(), null, null);
        var resolved = SpectrumDiscoveryService.directoryReview(original,
            new RadioReferenceDiscoveryResolver.Result("matched", "Matched", match, "radioreference"));
        assertEquals("County Public Safety", resolved.template().system());
        assertEquals("North", resolved.template().site());
        assertEquals("County Public Safety · North", resolved.template().name());
        assertEquals("County Public Safety", resolved.defaultNewAliasListName());
    }

    @Test
    void directoryNameSuggestsAnEligibleExistingListWithoutClaimingAnUnknownSystemMatch()
    {
        var template = new ChannelDefinition(null, "p25-phase1", "P25 BEE00-49F", "North", "Control",
            null, 0, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
            "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var lists = List.of(new ChannelAdministrationService.DiscoveryAliasList(23, "Ohio MARCS", false));
        var original = new ChannelAdministrationService.DiscoveryReview(7, template, lists, null, "P25 BEE00-49F");
        var match = new RadioReferenceDiscoveryResolver.Match(100, 200, "Ohio MARCS", "North", List.of(), null, null);
        var resolved = SpectrumDiscoveryService.directoryReview(original,
            new RadioReferenceDiscoveryResolver.Result("matched", "Matched", match, "radioreference"));
        assertEquals(23L, resolved.suggestedAliasListId());
        assertEquals(23, resolved.template().aliasListId());
        assertFalse(resolved.aliasLists().getFirst().matched(), "A matching name is a suggestion, not native system proof");
    }

    @Test
    void eligibilityReasonNamesTheOwningChannel()
    {
        var configured = new ChannelAdministrationService.DiscoveryFrequencyMatch(
            "one", "County Control", "County P25", "North", "configured");
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured)));
        assertEquals("This frequency belongs to County Control and 1 other channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured,
                Map.of("configuration_id", "two", "name", "City Control"))));
    }

    @Test
    void eligibilityReasonDeduplicatesOwnersAndKeepsAGenericFallback()
    {
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(
                Map.of("configuration_id", "one", "name", "County Control"),
                Map.of("configuration_id", "one", "name", "County Control"))));
        assertEquals("This frequency belongs to a known channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(Map.of("configuration_id", "one"))));
    }

    /** Exercise the real wizard cancellation and resolver workers without tuning hardware or starting decoders. */
    private static final class DirectoryFixture implements AutoCloseable
    {
        final BlockingDirectoryGateway gateway = new BlockingDirectoryGateway();
        final RadioReferenceDirectoryService directory = new RadioReferenceDirectoryService((user, password) -> gateway);
        final TunerDiagnosticService diagnostics = new TunerDiagnosticService(List::of,
            (target, viewport, profile, consumer) -> { throw new AssertionError("No FFT or receiver should start"); });
        final TunerSettingsService settings;
        final SpectrumDiscoveryService service;
        final Tuner tuner = new Tuner(new TestTunerController(), null)
        {
            public int getMaximumUSBBitsPerSecond() { return 0; }
            public String getUniqueID() { return "directory-lifecycle"; }
            public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
            public String getPreferredName() { return "Directory lifecycle test"; }
            public double getSampleSize() { return 16; }
        };

        DirectoryFixture() throws Exception
        {
            Constructor<TunerSettingsService> constructor = TunerSettingsService.class.getDeclaredConstructor(
                Runnable.class, Predicate.class, Function.class);
            constructor.setAccessible(true);
            settings = constructor.newInstance((Runnable)() -> {}, (Predicate<DiscoveredTuner>)ignored -> false,
                (Function<String,DiscoveredTuner>)ignored -> null);
            service = new SpectrumDiscoveryService(null, null, diagnostics, settings, null);
            service.setRadioReferenceResolver(new RadioReferenceDiscoveryResolver(directory));
            assertEquals(RadioReferenceDirectoryService.AccountState.VALID_PREMIUM,
                directory.login("test", "test".toCharArray()).state());
        }

        DirectoryWizard begin() throws Exception
        {
            Class<?> type = Class.forName(SpectrumDiscoveryService.class.getName() + "$Wizard");
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, long.class,
                String.class, String.class, Tuner.class);
            constructor.setAccessible(true);
            Object wizard = constructor.newInstance("test", "test", 770_500_000L, "p25-phase1", null, tuner);
            var p25 = new io.github.dsheirer.module.decode.p25.P25SiteIdentity(0xBEE00, 0x49F, 1, 2);
            var evidence = new TrunkedDiscoveryEvidence("p25-phase1", "P25_PHASE_1",
                new TrunkedDiscoveryEvidence.Identity(p25, "P25 BEE00-49F", "P25 BEE00-49F:1:2",
                    null, p25.system(), p25.site(), null, null, null, null, null),
                Map.of("modulation", "C4FM"), List.of(), 100, 30, 30, 0, 1000, "Confirmed", 770_500_000L);
            field(wizard, "evidence").set(wizard, evidence);
            Method resolve = SpectrumDiscoveryService.class.getDeclaredMethod("resolveDirectory", type);
            resolve.setAccessible(true);
            synchronized(service)
            {
                field(service, "mWizard").set(service, wizard);
                resolve.invoke(service, wizard);
                return new DirectoryWizard(wizard, (String)field(wizard, "id").get(wizard),
                    (Future<?>)field(wizard, "directoryFuture").get(wizard));
            }
        }

        RadioReferenceDiscoveryResolver.Result result(DirectoryWizard wizard) throws Exception
        {
            synchronized(service)
            {
                return (RadioReferenceDiscoveryResolver.Result)field(wizard.wizard(), "directory").get(wizard.wizard());
            }
        }

        public void close()
        {
            gateway.release.countDown();
            service.close();
            directory.close();
            diagnostics.close();
            settings.close();
        }
    }

    private static final class BlockingDirectoryGateway implements RadioReferenceGateway
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        public Account account() { return new Account("test", "12-31-2099"); }
        public List<Country> countries() { return List.of(); }
        public CountryDirectory country(int id) { return null; }
        public StateDirectory state(int id) { return null; }
        public CountyDirectory county(int id) { return null; }
        public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz) { return List.of(); }
        public List<TrunkedSystem> p25SystemsBySystemId(int systemId)
        {
            if(calls.incrementAndGet() == 1)
            {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch(InterruptedException exception)
                {
                    interrupted.countDown();
                    // Return a late successful result after cancellation, as an upstream completion can do.
                    Thread.currentThread().interrupt();
                }
            }
            return List.of();
        }
        public void close() { release.countDown(); }
    }

    private static Field field(Object target, String name) throws NoSuchFieldException
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record DirectoryWizard(Object wizard, String id, Future<?> future) {}
}

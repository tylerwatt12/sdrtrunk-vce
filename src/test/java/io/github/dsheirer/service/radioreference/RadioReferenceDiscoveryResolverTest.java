/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DiscoverySystem;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Identity;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class RadioReferenceDiscoveryResolverTest
{
    private static final long FREQUENCY = 853_162_500L;
    private static final Identity P25 = new Identity("p25-phase1", "phase1", FREQUENCY,
        0xBEE00, 0x49F, 2, 12, null, null);

    @Test
    void matchesExactP25IdentityAndReturnsAuthoritativeSortedSiteChannels()
    {
        DiscoverySystem candidate = p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY);
        Result result = RadioReferenceDiscoveryResolver.match(P25, List.of(candidate, candidate));
        assertTrue(result.matched());
        assertEquals("State System", result.match().systemName());
        assertEquals("County Site", result.match().siteName());
        assertEquals(2001, result.match().rrSystemId());
        assertEquals(3001, result.match().rrSiteId());
        assertEquals(List.of(FREQUENCY, FREQUENCY + 25_000),
            result.match().channels().stream().map(TrunkedSiteChannel::frequencyHz).toList());
        assertEquals("https://www.radioreference.com/db/sid/2001", result.match().url());
        assertEquals("radioreference-exact-frequency-and-on-air-identity", result.provenance());
    }

    @Test
    void rejectsFrequencyOnlyWrongProtocolSystemWacnRfssSiteAndChannel()
    {
        List<DiscoverySystem> wrong = List.of(
            p25(2001, 3001, "BEE01", "49F", 2, 12, FREQUENCY),
            p25(2002, 3002, "BEE00", "49E", 2, 12, FREQUENCY),
            p25(2003, 3003, "BEE00", "49F", 1, 12, FREQUENCY),
            p25(2004, 3004, "BEE00", "49F", 2, 13, FREQUENCY),
            p25(2005, 3005, "BEE00", "49F", 2, 12, FREQUENCY + 100_000),
            system(2006, 3006, "Motorola", "Type II", "BEE00", "49F", 2, 12, FREQUENCY, "", 0));
        Result result = RadioReferenceDiscoveryResolver.match(P25, wrong);
        assertEquals("no_match", result.state());
        assertNull(result.match());
    }

    @Test
    void checksAllNetworksAndParsesP25NumbersAsHexadecimal()
    {
        DiscoverySystem candidate = p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY);
        TrunkedSystemDetails details = candidate.system();
        candidate = new DiscoverySystem(new TrunkedSystemDetails(details.id(), details.name(), details.city(),
            details.type(), details.flavor(), details.voice(), "BEE01", "001",
            List.of(new RadioNetworkIdentity("BEE01", "001"), new RadioNetworkIdentity("0xbee00", "0x49f"))),
            candidate.sites());
        assertTrue(RadioReferenceDiscoveryResolver.match(P25, List.of(candidate)).matched());
        Identity identity = new Identity("p25-phase1", "phase1", FREQUENCY, 0xBEE00, 0x123, 2, 12, null, null);
        assertTrue(RadioReferenceDiscoveryResolver.match(identity,
            List.of(p25(2001, 3001, "BEE00", "123", 2, 12, FREQUENCY))).matched());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(identity,
            List.of(p25(2001, 3001, "BEE00", "291", 2, 12, FREQUENCY))).state());
    }

    @Test
    void reusedIdentityAndFrequencyRequireManualSelectionAcrossSystemsOrSites()
    {
        assertEquals("ambiguous", RadioReferenceDiscoveryResolver.match(P25,
            List.of(p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY),
                p25(2002, 3002, "BEE00", "49F", 2, 12, FREQUENCY))).state());
        DiscoverySystem one = p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY);
        DiscoverySystem two = p25(2001, 3002, "BEE00", "49F", 2, 12, FREQUENCY);
        assertEquals("ambiguous", RadioReferenceDiscoveryResolver.match(P25,
            List.of(new DiscoverySystem(one.system(), List.of(one.sites().getFirst(), two.sites().getFirst())))).state());
    }

    @Test
    void matchesExplicitDmrIdsAndChecksFlavorColorAndExactFrequency()
    {
        Identity tier3 = new Identity("dmr", "tier3", FREQUENCY, null, 123, null, 12, 7, null);
        assertTrue(RadioReferenceDiscoveryResolver.match(tier3,
            List.of(system(2001, 3001, "DMR", "Tier III", "", "0x7B", 0, 12, FREQUENCY, "7", 0))).matched());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(tier3,
            List.of(system(2001, 3001, "DMR", "Tier III", "", "0x7B", 0, 12, FREQUENCY, "8", 0))).state());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(tier3,
            List.of(system(2001, 3001, "DMR", "Connect Plus", "", "0x7B", 0, 12, FREQUENCY, "7", 0))).state());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(tier3,
            List.of(system(2001, 3001, "DMR", "Tier III", "", "123", 0, 12, FREQUENCY, "7", 0))).state());
        Identity connect = new Identity("dmr", "connect-plus", FREQUENCY, null, 123, null, 12, 7, null);
        assertTrue(RadioReferenceDiscoveryResolver.match(connect,
            List.of(system(2001, 3001, "DMR", "Connect Plus", "", "7B", 0, 12, FREQUENCY, "7", 0))).matched());
    }

    @Test
    void matchesNxdnTypeCAndRejectsContradictoryRan()
    {
        Identity nxdn = new Identity("nxdn", "type-c", FREQUENCY, null, 7, null, 12, null, 3);
        assertTrue(RadioReferenceDiscoveryResolver.match(nxdn,
            List.of(system(2001, 3001, "NXDN", "NEXEDGE", "", "7", 0, 12, FREQUENCY, "", 3))).matched());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(nxdn,
            List.of(system(2001, 3001, "NXDN", "NEXEDGE", "", "7", 0, 12, FREQUENCY, "", 4))).state());
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(nxdn,
            List.of(system(2001, 3001, "NXDN", "Type D", "", "7", 0, 12, FREQUENCY, "", 3))).state());
    }

    @Test
    void unspecifiedNonP25RadixAndConflictingModelRemainManual()
    {
        Identity identity = new Identity("dmr", "TIER_III", FREQUENCY, null, 42, null, 12, 7, null, "SMALL");
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(identity,
            List.of(system(2001, 3001, "DMR", "Tier III", "", "42", 0, 12, FREQUENCY, "7", 0))).state());
        DiscoverySystem candidate = system(2001, 3001, "DMR", "Tier III", "", "0x2A", 0, 12, FREQUENCY, "7", 0);
        TrunkedSystemDetails system = candidate.system();
        candidate = new DiscoverySystem(new TrunkedSystemDetails(system.id(), system.name(), system.city(),
            system.type(), system.flavor(), system.voice(), system.wacn(), system.systemId(),
            List.of(new RadioNetworkIdentity("", "0x2A", "Small"))), candidate.sites());
        assertTrue(RadioReferenceDiscoveryResolver.match(identity, List.of(candidate)).matched());
        Identity wrongScope = new Identity("dmr", "TIER_III", FREQUENCY, null, 42, null, 12, 7, null, "LARGE");
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(wrongScope, List.of(candidate)).state());
        Identity absentScope = new Identity("dmr", "TIER_III", FREQUENCY, null, 42, null, 12, 7, null);
        assertEquals("no_match", RadioReferenceDiscoveryResolver.match(absentScope, List.of(candidate)).state());
    }

    @Test
    void conventionalOrFrequencyScopedVariantsCannotAcquireCatalogIdentity()
    {
        for(Identity identity: List.of(
            new Identity("p25-phase1", "phase1", FREQUENCY, null, 0x49F, 2, 12, null, null),
            new Identity("dmr", "capacity-plus", FREQUENCY, null, 123, null, 12, 7, null),
            new Identity("nxdn", "type-d", FREQUENCY, null, 123, null, 12, null, 3),
            new Identity("nbfm", "conventional", FREQUENCY, null, null, null, null, null, null)))
        {
            assertFalse(identity.stable());
            assertEquals("identity_required", RadioReferenceDiscoveryResolver.match(identity, List.of()).state());
        }
    }

    @Test
    void missingLoginLocationAndPremiumUseManualStatesWithoutNetwork() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("login_required", resolver.resolve(10, P25).state());
            assertEquals(0, gateway.searches.get());
            directory.login("test", "cleared-password".toCharArray());
            assertEquals("location_required", resolver.resolve(null,
                new Identity("dmr", "tier3", FREQUENCY, null, 123, null, 12, 7, null)).state());
            assertEquals(0, gateway.searches.get());
            assertTrue(gateway.p25SystemIds.isEmpty());
        }
        gateway.expiration = "01-01-2000";
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals("premium_required", new RadioReferenceDiscoveryResolver(directory).resolve(10, P25).state());
            assertEquals(0, gateway.searches.get());
            assertTrue(gateway.p25SystemIds.isEmpty());
        }
    }

    @Test
    void p25LookupUsesOnAirSystemIdWithoutStateAndIgnoresConfiguredState() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(0), catalog(2001), catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertTrue(resolver.resolve(null, P25).matched());
            assertTrue(resolver.resolve(999, P25).matched());
            assertEquals(List.of(2001, 2001), gateway.detailIds);
            assertEquals(List.of(0x49F, 0x49F), gateway.p25SystemIds);
            assertEquals(0, gateway.searches.get());
        }
    }

    @Test
    void missingGlobalP25IndexUsesTheSelectedStateWithoutWeakeningIdentityChecks() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("no_match", resolver.resolve(null, P25).state());
            assertEquals(0, gateway.searches.get());
            assertTrue(resolver.resolve(10, P25).matched());
            assertEquals(1, gateway.searches.get());
            gateway.systems.put(2001, p25(2001, 3001, "BEE01", "49F", 2, 12, FREQUENCY));
            assertEquals("no_match", resolver.resolve(10, P25).state());
            gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 1, 12, FREQUENCY));
            assertEquals("no_match", resolver.resolve(10, P25).state());
            gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 13, FREQUENCY));
            assertEquals("no_match", resolver.resolve(10, P25).state());
            gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY + 25_000));
            assertEquals("no_match", resolver.resolve(10, P25).state());
        }
    }

    @Test
    void incompleteGlobalSiteIndexUsesTheRegionButPreservesAmbiguity() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2000));
        gateway.systems.put(2000, p25(2000, 3000, "BEE00", "49F", 2, 13, FREQUENCY));
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertTrue(resolver.resolve(10, P25).matched());
            assertEquals(List.of(2000, 2001), gateway.detailIds);
            gateway.rows = List.of(row(2001, FREQUENCY), row(2002, FREQUENCY));
            gateway.systems.put(2002, p25(2002, 3002, "BEE00", "49F", 2, 12, FREQUENCY));
            assertEquals("ambiguous", resolver.resolve(10, P25).state());
            gateway.p25Candidates = List.of(catalog(2001), catalog(2002));
            int previousSearches = gateway.searches.get();
            assertEquals("ambiguous", resolver.resolve(10, P25).state());
            assertEquals(previousSearches, gateway.searches.get(), "a region must not hide a verified global ambiguity");
        }
    }

    @Test
    void failedOrIncompleteLookupNeverBecomesNoMatchOrARegionalFallback() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            gateway.failure = RadioReferenceGatewayException.Kind.HTTP_ERROR;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
            assertEquals(0, gateway.searches.get());
            gateway.failure = null;
            gateway.p25Candidates = null;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
            assertEquals(0, gateway.searches.get());
            gateway.p25Candidates = List.of();
            gateway.frequencyFailure = RadioReferenceGatewayException.Kind.TIMEOUT;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
            gateway.frequencyFailure = null;
            gateway.rows = null;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
            gateway.p25Candidates = List.of(catalog(2001));
            gateway.nullSites = true;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
        }
    }

    @Test
    void emptyGlobalP25FallbackStillSharesOneTotalDeadline() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        AtomicLong now = new AtomicLong();
        gateway.afterLookup = () -> now.addAndGet(Duration.ofMillis(100).toNanos());
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1), now::get))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class, () -> directory.p25DiscoverySystems(
                    0x49F, FREQUENCY, 10, system -> true, candidate -> true, Duration.ofMillis(150))).code());
            assertEquals(List.of(0x49F), gateway.p25SystemIds);
            assertEquals(1, gateway.searches.get());
        }
    }

    @Test
    void sharedP25SystemIdAsksForStateOnlyWhenGlobalCandidatesExceedTheBound() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = java.util.stream.IntStream.rangeClosed(1, 9)
            .mapToObj(RadioReferenceDiscoveryResolverTest::catalog).toList();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("location_required", resolver.resolve(null, P25).state());
            assertTrue(gateway.detailIds.isEmpty());
            assertEquals(0, gateway.searches.get());
            assertTrue(resolver.resolve(10, P25).matched());
            assertEquals(List.of(2001), gateway.detailIds);
            assertEquals(1, gateway.searches.get());
        }
    }

    @Test
    void p25StateFallbackKeepsTheGlobalLookupDeadline() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = java.util.stream.IntStream.rangeClosed(1, 9)
            .mapToObj(RadioReferenceDiscoveryResolverTest::catalog).toList();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        AtomicLong now = new AtomicLong();
        gateway.afterLookup = () -> now.addAndGet(Duration.ofMillis(100).toNanos());
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1), now::get))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class, () -> directory.p25DiscoverySystems(
                    0x49F, FREQUENCY, 10, system -> true, Duration.ofMillis(150))).code());
            assertEquals(List.of(0x49F), gateway.p25SystemIds);
            assertEquals(1, gateway.searches.get());
        }
    }

    @Test
    void nonP25LookupKeepsStateScopedFrequencySearch() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(0, FREQUENCY), row(2002, FREQUENCY + 100_000),
            row(2001, FREQUENCY), row(2001, FREQUENCY));
        gateway.systems.put(2001, system(2001, 3001, "DMR", "Tier III", "", "0x7B", 0, 12,
            FREQUENCY, "7", 0));
        Identity tier3 = new Identity("dmr", "tier3", FREQUENCY, null, 123, null, 12, 7, null);
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertTrue(new RadioReferenceDiscoveryResolver(directory).resolve(10, tier3).matched());
            assertEquals(List.of(2001), gateway.detailIds);
            assertEquals(1, gateway.searches.get());
            assertTrue(gateway.p25SystemIds.isEmpty());
        }
    }

    @Test
    void missingResultsNetworkFailureAndTimeoutRemainCredentialSafeAndRetryable() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofMillis(100)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("no_match", resolver.resolve(10, P25).state());
            gateway.failure = RadioReferenceGatewayException.Kind.HTTP_ERROR;
            Result failed = resolver.resolve(10, P25);
            assertEquals("unavailable", failed.state());
            assertNull(failed.match());
            assertFalse(failed.toString().contains("password"));
            gateway.failure = null;
            gateway.failure = RadioReferenceGatewayException.Kind.TIMEOUT;
            assertEquals("unavailable", resolver.resolve(10, P25).state());
            gateway.failure = null;
            assertEquals("no_match", resolver.resolve(10, P25).state());
        }
    }

    @Test
    void skipsWrongNativeNetworksAndProtocolsBeforeLoadingTheirSiteCatalogs() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001), catalog(2002), catalog(2003));
        gateway.systems.put(2001, p25(2001, 3001, "BEE01", "49F", 2, 12, FREQUENCY));
        gateway.systems.put(2002, system(2002, 3002, "Motorola", "Type II", "BEE00", "49F", 2, 12,
            FREQUENCY, "", 0));
        gateway.systems.put(2003, p25(2003, 3003, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertTrue(new RadioReferenceDiscoveryResolver(directory).resolve(10, P25).matched());
            assertEquals(List.of(2001, 2002, 2003), gateway.detailIds);
            assertEquals(List.of(2003), gateway.siteIds);
        }
    }

    @Test
    void searchAndDetailsShareOneTotalDeadlineInsteadOfRenewingItPerRemoteCall() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(2001, FREQUENCY));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.delayMillis = 100;
        gateway.siteDelayMillis = 100;
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> directory.discoverySystems(10, FREQUENCY, system -> true, Duration.ofMillis(150))).code());
        }
    }

    @Test
    void p25SystemLookupAndDetailsShareOneTotalDeadline() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.delayMillis = 100;
        gateway.siteDelayMillis = 100;
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> directory.p25DiscoverySystems(0x49F, FREQUENCY, null, system -> true, Duration.ofMillis(150))).code());
        }
    }

    @Test
    void boundsCandidateSystemsAndSiteChannelsWithoutReturningPartialMatches() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = java.util.stream.IntStream.rangeClosed(1, 9)
            .mapToObj(RadioReferenceDiscoveryResolverTest::catalog).toList();
        gateway.rows = java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(id -> row(id, FREQUENCY)).toList();
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            RadioReferenceDiscoveryResolver resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("ambiguous", resolver.resolve(10, P25).state());
            assertTrue(gateway.detailIds.isEmpty());
            gateway.p25Candidates = List.of(catalog(2001));
            DiscoverySystem candidate = p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY);
            TrunkedSiteDetails site = candidate.sites().getFirst();
            gateway.systems.put(2001, new DiscoverySystem(candidate.system(), List.of(new TrunkedSiteDetails(
                site.id(), site.systemId(), site.number(), site.name(), site.countyId(), site.zoneNumber(), site.rfss(),
                site.nac(), site.ran(), site.modulation(), site.tdmaControlChannel(),
                java.util.Collections.nCopies(10_001, site.channels().getFirst())))));
            assertEquals("ambiguous", resolver.resolve(10, P25).state());
        }
    }

    @Test
    void oneWorkflowLoadsTheP25CatalogOnceButVerifiesEverySiteAndFrequency() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        DiscoverySystem first = p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY);
        DiscoverySystem second = p25(2001, 3002, "BEE00", "49F", 2, 13, FREQUENCY + 100_000);
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, new DiscoverySystem(first.system(),
            List.of(first.sites().getFirst(), second.sites().getFirst())));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            Identity next = new Identity("p25-phase1", "phase1", FREQUENCY + 100_000,
                0xBEE00, 0x49F, 2, 13, null, null);
            Identity wrongFrequency = new Identity("p25-phase1", "phase1", FREQUENCY,
                0xBEE00, 0x49F, 2, 13, null, null);
            List<Identity> rows = List.of(P25, next, wrongFrequency);
            List<Result> uncached = rows.stream().map(identity -> resolver.resolve(null, identity)).toList();
            assertEquals(3, gateway.p25SystemIds.size());
            assertEquals(3, gateway.detailIds.size());
            assertEquals(3, gateway.siteIds.size());
            gateway.p25SystemIds.clear(); gateway.detailIds.clear(); gateway.siteIds.clear();
            List<Result> reused = rows.stream().map(identity -> resolver.resolve(null, identity, catalog)).toList();
            assertEquals(uncached, reused, "retention must preserve the complete match result for each row");
            assertEquals(3001, reused.get(0).match().rrSiteId());
            assertEquals(3002, reused.get(1).match().rrSiteId());
            assertEquals("no_match", reused.get(2).state());
            assertEquals(List.of(0x49F), gateway.p25SystemIds);
            assertEquals(List.of(2001), gateway.detailIds);
            assertEquals(List.of(2001), gateway.siteIds);
            assertEquals(0, gateway.searches.get());
        }
    }

    @Test
    void sharedDmrCatalogStillSearchesEachExactFrequencyAndRejectsWrongColor() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.rows = List.of(row(2001, FREQUENCY), row(2001, FREQUENCY + 25_000));
        gateway.systems.put(2001, system(2001, 3001, "DMR", "Tier III", "", "0x7B", 0, 12,
            FREQUENCY, "7", 0));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            assertTrue(resolver.resolve(10, new Identity("dmr", "tier3", FREQUENCY,
                null, 123, null, 12, 7, null), catalog).matched());
            assertTrue(resolver.resolve(10, new Identity("dmr", "tier3", FREQUENCY + 25_000,
                null, 123, null, 12, 7, null), catalog).matched());
            assertEquals("no_match", resolver.resolve(10, new Identity("dmr", "tier3", FREQUENCY,
                null, 123, null, 12, 8, null), catalog).state());
            assertEquals(3, gateway.searches.get());
            assertEquals(List.of(2001), gateway.detailIds);
            assertEquals(List.of(2001), gateway.siteIds);
        }
    }

    @Test
    void workflowRetainsAmbiguousCandidatesAndInvalidatesOnAccountChangeOrClose() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001), catalog(2002));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.systems.put(2002, p25(2002, 3002, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            assertEquals("ambiguous", resolver.resolve(10, P25, catalog).state());
            assertEquals("ambiguous", resolver.resolve(10, P25, catalog).state());
            assertEquals(2, gateway.siteIds.size());
            directory.logout();
            assertEquals("login_required", resolver.resolve(10, P25, catalog).state());
            gateway.p25Candidates = List.of(catalog(2001));
            directory.login("another-test", "cleared-password".toCharArray());
            assertTrue(resolver.resolve(10, P25, catalog).matched());
            assertEquals(3, gateway.siteIds.size());
            catalog.close();
            assertEquals("unavailable", resolver.resolve(10, P25, catalog).state());
            assertEquals(3, gateway.siteIds.size());
        }
    }

    @Test
    void workflowExpiresAndLoadsFreshCompleteCatalogs() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        var now = new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
        Clock clock = new Clock()
        {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now.get()); }
            @Override public long millis() { return now.get(); }
        };
        try(var directory = new RadioReferenceDirectoryService((user, password) -> gateway, 1, 2,
            Duration.ofSeconds(1), Duration.ofMillis(100), clock); var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            assertTrue(resolver.resolve(null, P25, catalog).matched());
            gateway.systems.put(2001, p25(2001, 3001, "BEE01", "49F", 2, 12, FREQUENCY));
            now.addAndGet(Duration.ofMinutes(5).toMillis());
            assertEquals("no_match", resolver.resolve(null, P25, catalog).state());
            assertEquals(2, gateway.p25SystemIds.size());
            assertEquals(2, gateway.detailIds.size());
        }
    }

    @Test
    void incompleteAndTimedOutWorkflowLoadsAreRetryable() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            gateway.nullSites = true;
            assertEquals("unavailable", resolver.resolve(null, P25, catalog).state());
            gateway.nullSites = false;
            gateway.siteDelayMillis = 200;
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class, () -> directory.p25DiscoverySystems(
                    0x49F, FREQUENCY, null, system -> true, candidate -> true, Duration.ofMillis(50), catalog)).code());
            gateway.siteDelayMillis = 0;
            assertTrue(resolver.resolve(null, P25, catalog).matched());
            assertEquals(3, gateway.p25SystemIds.size());
            assertEquals(3, gateway.siteIds.size());
        }
    }

    @Test
    void concurrentRowsShareOneCompleteCatalogLoad() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.siteDelayMillis = 100;
        try(var directory = new RadioReferenceDirectoryService((user, password) -> gateway, 2, 4,
            Duration.ofSeconds(1), Duration.ofMillis(100), Clock.systemUTC());
            var catalog = directory.newDiscoveryCatalog(); var callers = java.util.concurrent.Executors.newFixedThreadPool(2))
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            var first = callers.submit(() -> resolver.resolve(null, P25, catalog));
            var second = callers.submit(() -> resolver.resolve(null, P25, catalog));
            assertTrue(first.get(2, java.util.concurrent.TimeUnit.SECONDS).matched());
            assertTrue(second.get(2, java.util.concurrent.TimeUnit.SECONDS).matched());
            assertEquals(List.of(0x49F), gateway.p25SystemIds);
            assertEquals(List.of(2001), gateway.siteIds);
        }
    }

    @Test
    void waitingForTheSameCatalogSharesTheCallerDeadlineWithoutDiscardingTheActiveLoad() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.siteEntered = new java.util.concurrent.CountDownLatch(1);
        gateway.siteRelease = new java.util.concurrent.CountDownLatch(1);
        try(var directory = new RadioReferenceDirectoryService((user, password) -> gateway, 2, 4,
            Duration.ofSeconds(1), Duration.ofMillis(100), Clock.systemUTC());
            var catalog = directory.newDiscoveryCatalog(); var caller = java.util.concurrent.Executors.newSingleThreadExecutor())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            var active = caller.submit(() -> resolver.resolve(null, P25, catalog));
            assertTrue(gateway.siteEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class, () -> directory.p25DiscoverySystems(
                    0x49F, FREQUENCY, null, system -> true, candidate -> true, Duration.ofMillis(20), catalog)).code());
            gateway.siteRelease.countDown();
            assertTrue(active.get(2, java.util.concurrent.TimeUnit.SECONDS).matched());
            assertTrue(resolver.resolve(null, P25, catalog).matched());
            assertEquals(List.of(2001), gateway.siteIds);
        }
    }

    @Test
    void closingAWorkflowDiscardsAnInFlightLookup() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        gateway.p25Candidates = List.of(catalog(2001));
        gateway.systems.put(2001, p25(2001, 3001, "BEE00", "49F", 2, 12, FREQUENCY));
        gateway.siteEntered = new java.util.concurrent.CountDownLatch(1);
        gateway.siteRelease = new java.util.concurrent.CountDownLatch(1);
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog(); var caller = java.util.concurrent.Executors.newSingleThreadExecutor())
        {
            directory.login("test", "cleared-password".toCharArray());
            var resolver = new RadioReferenceDiscoveryResolver(directory);
            var active = caller.submit(() -> resolver.resolve(null, P25, catalog));
            assertTrue(gateway.siteEntered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            catalog.close();
            gateway.siteRelease.countDown();
            assertEquals("unavailable", active.get(2, java.util.concurrent.TimeUnit.SECONDS).state());
            assertEquals("unavailable", resolver.resolve(null, P25, catalog).state());
            assertEquals(List.of(2001), gateway.siteIds);
        }
    }

    @Test
    void catalogRetentionLimitNeverTruncatesResults() throws Exception
    {
        FakeGateway gateway = new FakeGateway();
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1));
            var catalog = directory.newDiscoveryCatalog())
        {
            directory.login("test", "cleared-password".toCharArray());
            for(int index = 1; index <= 33; index++)
            {
                gateway.p25Candidates = List.of(catalog(index));
                gateway.systems.put(index, p25(index, 3000 + index, "BEE00", "49F", 2, 12, FREQUENCY));
                assertEquals(index, directory.p25DiscoverySystems(index, FREQUENCY, null, system -> true,
                    candidate -> true, catalog).getFirst().system().id());
            }
            assertEquals(33, directory.p25DiscoverySystems(33, FREQUENCY, null, system -> true,
                candidate -> true, catalog).getFirst().system().id());
            assertEquals(34, gateway.siteIds.size(), "uncached entries load completely again");
        }
    }

    private static RadioReferenceDirectoryService directory(FakeGateway gateway, Duration deadline)
    {
        return directory(gateway, deadline, System::nanoTime);
    }

    private static RadioReferenceDirectoryService directory(FakeGateway gateway, Duration deadline, LongSupplier nanoTime)
    {
        return new RadioReferenceDirectoryService((user, password) -> gateway, 1, 2, deadline,
            Duration.ofMillis(100), Clock.systemUTC(), nanoTime);
    }

    private static DiscoverySystem p25(int rrSystem, int rrSite, String wacn, String system, int rfss,
                                       int site, long frequency)
    {
        return system(rrSystem, rrSite, "Project 25", "Phase I", wacn, system, rfss, site, frequency, "", 0);
    }

    private static DiscoverySystem system(int rrSystem, int rrSite, String type, String flavor, String wacn,
                                          String system, int rfss, int site, long frequency, String color, int ran)
    {
        TrunkedSiteChannel control = new TrunkedSiteChannel(frequency, 17, "1-17", "c", color, true, false);
        TrunkedSiteChannel alternate = new TrunkedSiteChannel(frequency + 25_000, 19, "1-19", "a", color, false, true);
        return new DiscoverySystem(new TrunkedSystemDetails(rrSystem, "State System", "", type, flavor, "", wacn, system),
            List.of(new TrunkedSiteDetails(rrSite, rrSystem, site, "County Site", 100, 1, rfss, "", ran, "C4FM", false,
                List.of(alternate, control, control))));
    }

    private static TrunkedSystem catalog(int rrSystem)
    {
        return new TrunkedSystem(rrSystem, "Untrusted catalog name", "", 1, 1, 1);
    }

    private static FrequencyResult row(int rrSystem, long frequency)
    {
        return new FrequencyResult(frequency / 1_000_000.0, 0, "", "Untrusted description", "", "", "", "", "",
            "", "", List.of(), 0, rrSystem, 0, 0);
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        String expiration = "Never - Feed Provider";
        List<FrequencyResult> rows = List.of();
        List<TrunkedSystem> p25Candidates = List.of();
        final List<Integer> p25SystemIds = new ArrayList<>();
        final Map<Integer,DiscoverySystem> systems = new java.util.LinkedHashMap<>();
        final List<Integer> detailIds = new ArrayList<>();
        final List<Integer> siteIds = new ArrayList<>();
        final AtomicInteger searches = new AtomicInteger();
        volatile long delayMillis;
        volatile long siteDelayMillis;
        Runnable afterLookup = () -> {};
        volatile RadioReferenceGatewayException.Kind failure;
        volatile RadioReferenceGatewayException.Kind frequencyFailure;
        volatile boolean nullSites;
        volatile java.util.concurrent.CountDownLatch siteEntered;
        volatile java.util.concurrent.CountDownLatch siteRelease;
        @Override public Account account() { return new Account("test", expiration); }
        @Override public List<Country> countries() { return List.of(); }
        @Override public CountryDirectory country(int id) { return null; }
        @Override public StateDirectory state(int id) { return null; }
        @Override public CountyDirectory county(int id) { return null; }
        @Override public List<FrequencyResult> searchStateFrequencies(int id, double frequency)
            throws RadioReferenceGatewayException
        {
            searches.incrementAndGet();
            if(failure != null) throw new RadioReferenceGatewayException(failure);
            if(frequencyFailure != null) throw new RadioReferenceGatewayException(frequencyFailure);
            try { if(delayMillis > 0) Thread.sleep(delayMillis); }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
            }
            afterLookup.run();
            return rows;
        }
        @Override public List<TrunkedSystem> p25SystemsBySystemId(int systemId)
            throws RadioReferenceGatewayException
        {
            p25SystemIds.add(systemId);
            if(failure != null) throw new RadioReferenceGatewayException(failure);
            try { if(delayMillis > 0) Thread.sleep(delayMillis); }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
            }
            afterLookup.run();
            return p25Candidates;
        }
        @Override public TrunkedSystemDetails trunkedSystemDetails(int id)
        {
            detailIds.add(id);
            return systems.containsKey(id) ? systems.get(id).system() : null;
        }
        @Override public List<TrunkedSiteDetails> trunkedSiteDetails(int id) throws RadioReferenceGatewayException
        {
            siteIds.add(id);
            if(siteEntered != null) siteEntered.countDown();
            if(nullSites) return null;
            try
            {
                if(siteRelease != null && !siteRelease.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.TIMEOUT);
                if(siteDelayMillis > 0) Thread.sleep(siteDelayMillis);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
            }
            return systems.containsKey(id) ? systems.get(id).sites() : List.of();
        }
        @Override public void close() { }
    }
}

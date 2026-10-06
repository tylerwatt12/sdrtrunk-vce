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
        gateway.delayMillis = 100;
        try(RadioReferenceDirectoryService directory = directory(gateway, Duration.ofSeconds(1)))
        {
            directory.login("test", "cleared-password".toCharArray());
            assertEquals(RadioReferenceDirectoryException.Code.TIMEOUT,
                assertThrows(RadioReferenceDirectoryException.class, () -> directory.p25DiscoverySystems(
                    0x49F, FREQUENCY, 10, system -> true, Duration.ofMillis(150))).code());
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

    private static RadioReferenceDirectoryService directory(FakeGateway gateway, Duration deadline)
    {
        return new RadioReferenceDirectoryService((user, password) -> gateway, 1, 2, deadline,
            Duration.ofMillis(100), Clock.systemUTC());
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
        volatile RadioReferenceGatewayException.Kind failure;
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
            try { if(delayMillis > 0) Thread.sleep(delayMillis); }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
            }
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
            try { if(siteDelayMillis > 0) Thread.sleep(siteDelayMillis); }
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

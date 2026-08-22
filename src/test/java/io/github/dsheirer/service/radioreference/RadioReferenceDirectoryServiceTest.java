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
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryException.Code;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.AccountState;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.BoundedPage;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DirectoryEntry;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.DirectoryOption;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.EntryGroup;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.EntryScope;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.EntryType;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.FrequencyMatch;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.FrequencyDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.LocationSelection;
import io.github.dsheirer.service.radioreference.RadioReferenceDirectoryService.ScopeFilter;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Account;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Agency;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Country;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountryDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.County;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.CountyDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.DetailKind;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyResult;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.FrequencyCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Mode;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.Site;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.SiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.State;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.StateDirectory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystem;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSystemDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteDetails;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroup;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.RemoteTalkgroupCategory;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.ConventionalFrequency;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RadioReferenceDirectoryServiceTest
{
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void loadsBoundedImportDetailsAndReloadsSelectedTalkgroups() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        gateway.systemDetails = new TrunkedSystemDetails(2001, "State P25", "Capital", "Project 25",
            "Phase II", "APCO-25 Common Air Interface", "BEE00", "49F");
        gateway.siteDetails = List.of(new TrunkedSiteDetails(3001, 2001, 12, "Franklin Simulcast", 100,
            1, 1, "491", 0, "LSM", false,
            List.of(new TrunkedSiteChannel(853_162_500L, 1, "1", "c", "", true, false))));
        gateway.remoteCategories = List.of(new RemoteTalkgroupCategory(9, 2001, "Dispatch"));
        gateway.remoteTalkgroups = List.of(
            new RemoteTalkgroup(101, 200, "Fire", "Fire dispatch", "D", 0, 9, List.of("Fire Dispatch")),
            new RemoteTalkgroup(100, 100, "Police", "Police dispatch", "D", 0, 9,
                List.of("Law Dispatch")));
        gateway.conventional = List.of(new ConventionalFrequency(77, 155_250_000L, null, "WQAB123",
            "County Fire", "Fire", "123.0 PL", "", "", "", "FMN", 0, "RM", List.of("Fire"), 444));

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            assertEquals("State P25", service.trunkedSystemDetails(2001).name());
            assertEquals(3001, service.trunkedSites(2001, 0, 10).items().getFirst().id());
            assertEquals(List.of(100, 200), service.talkgroups(2001, 9, "dispatch", 0, 10).items().stream()
                .map(RemoteTalkgroup::value).toList());
            assertEquals(List.of(101, 100), service.talkgroupsById(2001,
                new java.util.LinkedHashSet<>(List.of(101, 100))).stream().map(RemoteTalkgroup::id).toList());
            assertEquals("Dispatch", service.talkgroupCategories(2001, 0, 10).items().getFirst().name());
            assertEquals(155_250_000L,
                service.conventionalFrequencies(444, "fire", 0, 10).items().getFirst().downlinkHz());
        }
    }

    @Test
    void authenticatesReportsStatusLogsOutAndClearsPasswords() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        FakeFactory factory = new FakeFactory(gateway);
        char[] password = "credential-that-must-not-escape".toCharArray();
        String forbidden = new String(password);

        try(RadioReferenceDirectoryService service = service(factory))
        {
            RadioReferenceDirectoryService.AccountStatus status = service.login(" directory-user ", password);

            assertEquals(AccountState.VALID_PREMIUM, status.state());
            assertEquals("directory-user", status.userName());
            assertTrue(status.authenticated());
            assertTrue(status.available());
            assertTrue(status.premium());
            assertArrayEquals(new char[password.length], password);
            assertArrayEquals(new char[factory.passwordReference.length], factory.passwordReference);
            assertFalse(status.toString().contains(forbidden));
            assertFalse(service.status().toString().contains(forbidden));

            service.logout();

            assertEquals(AccountState.SIGNED_OUT, service.status().state());
            assertTrue(gateway.closed);
            assertEquals(Code.NOT_AUTHENTICATED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.countries("", 10)).code());
        }
    }

    @Test
    void distinguishesExpiredInvalidAndUnavailableAccounts() throws Exception
    {
        FakeGateway expired = populatedGateway();
        expired.account = new Account("expired-user", "07-20-2026");

        try(RadioReferenceDirectoryService service = service(new FakeFactory(expired)))
        {
            assertEquals(AccountState.EXPIRED_PREMIUM,
                service.login("expired-user", "secret".toCharArray()).state());
            assertTrue(service.status().available());
            assertFalse(service.status().premium());
            assertEquals(Code.PREMIUM_REQUIRED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.countries("", 10)).code());
        }

        FakeGateway invalid = populatedGateway();
        invalid.accountFailure = RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS;

        try(RadioReferenceDirectoryService service = service(new FakeFactory(invalid)))
        {
            assertEquals(AccountState.INVALID_CREDENTIALS,
                service.login("invalid-user", "do-not-echo".toCharArray()).state());
            assertEquals("", service.status().userName());
            assertTrue(invalid.closed);
        }

        FakeGateway unavailable = populatedGateway();
        unavailable.accountFailure = RadioReferenceGatewayException.Kind.UNAVAILABLE;

        try(RadioReferenceDirectoryService service = service(new FakeFactory(unavailable)))
        {
            assertEquals(AccountState.UNAVAILABLE,
                service.login("unavailable-user", "do-not-echo".toCharArray()).state());
            assertEquals("", service.status().accountExpires());
            assertTrue(unavailable.closed);
        }
    }

    @Test
    void browsesBoundedOptionsAndCombinesTaggedLocationResults() throws Exception
    {
        FakeGateway gateway = populatedGateway();

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());

            BoundedPage<DirectoryOption> countries = service.countries("united", 10);
            assertEquals(List.of("United States"),
                countries.items().stream().map(DirectoryOption::name).toList());
            assertFalse(countries.truncated());

            assertEquals(List.of("Alaska"), service.states(1, "ak", 10).items().stream()
                .map(DirectoryOption::name).toList());
            assertEquals(List.of("Franklin"), service.counties(10, "frank", 10).items().stream()
                .map(DirectoryOption::name).toList());

            LocationSelection selection = new LocationSelection(1, 10, 100);
            BoundedPage<DirectoryEntry> all =
                service.browse(selection, "", EntryGroup.ALL, ScopeFilter.ALL, 20);
            assertEquals(7, all.items().size());
            assertFalse(all.truncated());
            assertEquals(List.of(EntryScope.NATIONAL, EntryScope.STATE, EntryScope.STATE, EntryScope.STATE,
                    EntryScope.COUNTY, EntryScope.COUNTY, EntryScope.COUNTY),
                all.items().stream().map(DirectoryEntry::scope).toList());
            assertTrue(all.items().stream().anyMatch(entry ->
                entry.name().equals("Franklin County (All)") &&
                    entry.detail().kind() == DetailKind.COUNTY));
            assertTrue(all.items().stream().anyMatch(entry ->
                entry.name().equals("County P25") &&
                    entry.type() == EntryType.TRUNKED_SYSTEM &&
                    entry.detail().kind() == DetailKind.TRUNKED_SYSTEM));

            BoundedPage<DirectoryEntry> countyAgencies = service.browse(selection, "county",
                EntryGroup.CONVENTIONAL_AGENCIES, ScopeFilter.COUNTY, 20);
            assertEquals(List.of("County Fire", "Franklin County (All)"),
                countyAgencies.items().stream().map(DirectoryEntry::name).toList());

            BoundedPage<DirectoryEntry> limited =
                service.browse(selection, "", EntryGroup.ALL, ScopeFilter.ALL, 2);
            assertEquals(2, limited.items().size());
            assertTrue(limited.truncated());

            RadioReferenceDirectoryException invalidSelection =
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.browse(new LocationSelection(1, null, 100), "", EntryGroup.ALL,
                        ScopeFilter.ALL, 10));
            assertEquals(Code.INVALID_REQUEST, invalidSelection.code());
            assertEquals(Code.INVALID_REQUEST,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.countries("", RadioReferenceDirectoryService.MAXIMUM_RESULT_LIMIT + 1)).code());
            assertEquals(Code.INVALID_REQUEST,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.countries("x".repeat(
                        RadioReferenceDirectoryService.MAXIMUM_QUERY_LENGTH + 1), 10)).code());
        }
    }

    @Test
    void returnsBoundedConventionalAndTrunkedFrequencyMatchesWithStableLinks() throws Exception
    {
        FakeGateway gateway = populatedGateway();

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            BoundedPage<FrequencyMatch> page = service.searchStateFrequencies(10, 853_162_500L, 1);

            assertEquals(2, page.totalItems());
            assertEquals(1, page.items().size());
            assertEquals(1, page.nextOffset());
            FrequencyMatch trunked = page.items().getFirst();
            assertEquals("State P25 Site 012 Franklin Simulcast", trunked.description());
            assertEquals(2001, trunked.systemId());
            assertEquals("State P25", trunked.systemName());
            assertEquals(12, trunked.siteNumber());
            assertEquals("Franklin Simulcast", trunked.siteName());
            assertEquals("Trunked", trunked.channelUse());
            assertEquals("Mode 4", trunked.modeName());
            assertEquals("Franklin", trunked.countyName());
            assertEquals("https://www.radioreference.com/db/sid/2001", trunked.radioReferenceUrl());
            assertEquals(0, gateway.modeCalls.get());
            assertEquals(0, gateway.siteCalls.get());
            assertEquals(0, gateway.categoryCalls.get());

            FrequencyDetails trunkedDetails = service.frequencyDetails(853_162_500L, 2001, 12, 0, 0, 100, "4");
            assertEquals("Franklin Simulcast", trunkedDetails.site().siteName());
            assertEquals("Control", trunkedDetails.site().channelUse());
            assertEquals("https://www.radioreference.com/db/site/3001",
                trunkedDetails.site().radioReferenceUrl());

            FrequencyMatch conventional = service.searchStateFrequencies(10, 853_162_500L, 1, 1)
                .items().getFirst();
            assertEquals("County Dispatch", conventional.description());
            assertEquals("", conventional.category());
            assertEquals("", conventional.subCategory());
            assertEquals("Conventional", conventional.channelUse());
            assertEquals("https://www.radioreference.com/db/subcat/444", conventional.radioReferenceUrl());
            assertEquals("State Police", conventional.agencyName());

            FrequencyDetails conventionalDetails = service.frequencyDetails(853_162_500L, null, 0, 444, 1001, 100,
                "FMN");
            assertEquals("Public Safety", conventionalDetails.category());
            assertEquals("County Dispatch", conventionalDetails.subCategory());
            assertEquals("FMN", conventionalDetails.modeName());
        }
    }

    @Test
    void correlatesRealShapedMarcsResultsToOneExactSiteEach() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        gateway.state = new StateDirectory(new State(39, "Ohio", "OH"),
            List.of(new County(2057, "Cuyahoga", ""), new County(2062, "Fairfield", ""),
                new County(2081, "Knox", ""), new County(2126, "Wood", "")),
            List.of(new TrunkedSystem(6643, "Ohio MARCS-IP: Multi-Agency Radio Communications", "", 0, 0, 0)),
            List.of());
        gateway.frequencyResults = List.of(
            new FrequencyResult(773.83125, 0, "", "Ohio MARCS-IP: Multi-Agency Radio Communications " +
                "Site 001 Cuyahoga Co Simulcast", "", "", "", "", "", "", "", List.of(), 0, 6643, 0,
                2057),
            new FrequencyResult(773.83125, 0, "", "Ohio MARCS-IP: Multi-Agency Radio Communications " +
                "Site 011 SCI (Lancaster)", "", "", "", "", "", "", "", List.of(), 0, 6643, 0, 2062),
            new FrequencyResult(773.83125, 0, "", "Ohio MARCS-IP: Multi-Agency Radio Communications " +
                "Site 014 Bradner", "", "", "", "", "", "", "", List.of(), 0, 6643, 0, 2126));
        gateway.sites = List.of(
            new Site(16773, 6643, 1, "Cuyahoga Co Simulcast", 2057,
                List.of(new SiteChannel(773.83125, "c", true, false))),
            new Site(24539, 6643, 11, "SCI (Lancaster)", 2062,
                List.of(new SiteChannel(773.83125, "c", true, false))),
            new Site(21482, 6643, 14, "Bradner", 2126,
                List.of(new SiteChannel(773.83125, "c", true, false))));

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            BoundedPage<FrequencyMatch> page = service.searchStateFrequencies(39, 773_831_250L, 10);
            assertEquals(List.of(1, 11, 14), page.items().stream().map(FrequencyMatch::siteNumber).toList());

            FrequencyDetails center = service.frequencyDetails(773_831_250L, 6643, 11, 0, 0, 2062, "");
            assertEquals(24539, center.site().siteId());
            assertEquals("SCI (Lancaster)", center.site().siteName());
            assertEquals("Control", center.site().channelUse());
            assertEquals("https://www.radioreference.com/db/site/24539", center.site().radioReferenceUrl());

            gateway.frequencyResults = List.of(new FrequencyResult(772.98125, 0, "",
                "Ohio MARCS-IP: Multi-Agency Radio Communications Site 003 Centerburg", "", "", "", "", "",
                "", "", List.of(), 0, 6643, 0, 2081));
            gateway.sites = List.of(new Site(21338, 6643, 3, "Centerburg", 2081,
                List.of(new SiteChannel(772.98125, "a", false, true))));
            FrequencyMatch centerburgMatch = service.searchStateFrequencies(39, 772_981_250L, 10)
                .items().getFirst();
            assertEquals(3, centerburgMatch.siteNumber());
            assertEquals("Centerburg", centerburgMatch.siteName());
            FrequencyDetails centerburg = service.frequencyDetails(772_981_250L, 6643, 3, 0, 0, 2081, "");
            assertEquals("Alternate control", centerburg.site().channelUse());
            assertEquals("https://www.radioreference.com/db/site/21338",
                centerburg.site().radioReferenceUrl());
        }
    }

    @Test
    void usesFrequencyResultNamesWhenTrunkedSystemIsMissingFromStateDirectory() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        gateway.state = new StateDirectory(new State(39, "Ohio", "OH"),
            List.of(new County(2091, "Medina", "")), List.of(), List.of());
        gateway.frequencyResults = List.of(new FrequencyResult(771.50625, 0, "",
            "Parma / Medina County / Ottawa County", "Site 020 Medina County", "", "", "", "", "",
            "", List.of(), 0, 5133, 0, 2091));

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            FrequencyMatch match = service.searchStateFrequencies(39, 771_506_250L, 10).items().getFirst();

            assertEquals("Parma / Medina County / Ottawa County", match.systemName());
            assertEquals(20, match.siteNumber());
            assertEquals("Medina County", match.siteName());
            assertEquals("https://www.radioreference.com/db/sid/5133", match.radioReferenceUrl());
            assertEquals(0, gateway.siteCalls.get());
            assertEquals(0, gateway.categoryCalls.get());
        }
    }

    @Test
    void projectsUsefulFieldsFromRealShapedOhioConventionalResults() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        gateway.state = new StateDirectory(new State(39, "Ohio", "OH"), List.of(), List.of(),
            List.of(new Agency(3283, "Ohio MARCS Conventional Systems", 0),
                new Agency(620, "Ohio Department of Transportation (ODOT)", 0)));

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            gateway.frequencyResults = List.of(new FrequencyResult(853.1625, 0, "WPOG967",
                "Bucyrus (Crawford)", "MDN Bucyrus", "", "", "", "", "1", "BM", List.of(), 31716, 0,
                3283, 0));
            gateway.categories = List.of(new FrequencyCategory(31716, "Ohio MARCS DataNet",
                "MARCS Data Net Zone 2"));

            FrequencyMatch marcsData = service.searchStateFrequencies(39, 853_162_500L, 10).items().getFirst();
            assertEquals("MDN Bucyrus", marcsData.alphaTag());
            assertEquals("Bucyrus (Crawford)", marcsData.description());
            assertEquals("WPOG967", marcsData.callsign());
            assertEquals("BM", marcsData.classification());
            assertEquals("Ohio MARCS Conventional Systems", marcsData.agencyName());
            assertEquals("https://www.radioreference.com/db/subcat/31716", marcsData.radioReferenceUrl());
            FrequencyDetails marcsDetails = service.frequencyDetails(853_162_500L, null, 0, 31716, 3283, 0,
                "1");
            assertEquals("Ohio MARCS DataNet", marcsDetails.category());
            assertEquals("MARCS Data Net Zone 2", marcsDetails.subCategory());

            gateway.frequencyResults = List.of(new FrequencyResult(453.4, 0, "", "", "ODOT 1", "107.2 PL",
                "", "", "", "4", "BM", List.of(), 9505, 0, 620, 0));
            gateway.categories = List.of(new FrequencyCategory(9505, "Government and Safety Services",
                "Public Works"));

            FrequencyMatch odot = service.searchStateFrequencies(39, 453_400_000L, 10).items().getFirst();
            assertEquals("ODOT 1", odot.alphaTag());
            assertEquals("107.2 PL", odot.tone());
            assertEquals("Ohio Department of Transportation (ODOT)", odot.agencyName());
            assertEquals("https://www.radioreference.com/db/subcat/9505", odot.radioReferenceUrl());
            FrequencyDetails odotDetails = service.frequencyDetails(453_400_000L, null, 0, 9505, 620, 0, "4");
            assertEquals("Government and Safety Services", odotDetails.category());
            assertEquals("Public Works", odotDetails.subCategory());
        }
    }

    @Test
    void rejectsMismatchedLocationHierarchyBeforeLoadingTheChild() throws Exception
    {
        FakeGateway gateway = populatedGateway();

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            LocationSelection wrongState = new LocationSelection(1, 99, null);
            assertEquals(Code.INVALID_REQUEST,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.browse(wrongState, "", EntryGroup.ALL, ScopeFilter.ALL, 10)).code());
            assertEquals(0, gateway.stateCalls.get(),
                "a state that is not in the selected country must not be fetched");

            LocationSelection wrongCounty = new LocationSelection(1, 10, 999);
            assertEquals(Code.INVALID_REQUEST,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.browse(wrongCounty, "", EntryGroup.ALL, ScopeFilter.ALL, 10)).code());
            assertEquals(1, gateway.stateCalls.get());
            assertEquals(0, gateway.countyCalls.get(),
                "a county that is not in the selected state must not be fetched");
        }
    }

    @Test
    void enumeratesMoreThanFiveHundredCombinedResultsWithoutDiscardingTheTail() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        List<TrunkedSystem> systems = new ArrayList<>();

        for(int index = 0; index < 1_201; index++)
        {
            systems.add(new TrunkedSystem(10_000 + index, "Paged System %04d".formatted(index), "", 1, 2, 3));
        }

        gateway.county = new CountyDirectory(gateway.county.county(), systems, List.of());

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            LocationSelection selection = new LocationSelection(1, 10, 100);
            List<Integer> ids = new ArrayList<>();
            int offset = 0;
            int pageCount = 0;

            do
            {
                BoundedPage<DirectoryEntry> page = service.browse(selection, "paged system",
                    EntryGroup.TRUNKED_SYSTEMS, ScopeFilter.COUNTY, offset, 500);
                assertEquals(1_201, page.totalItems());
                assertEquals(offset, page.offset());
                ids.addAll(page.items().stream().map(entry -> entry.detail().id()).toList());
                pageCount++;

                if(page.nextOffset() == null)
                {
                    break;
                }

                offset = page.nextOffset();
            }
            while(true);

            assertEquals(3, pageCount);
            assertEquals(1_201, ids.size());
            assertEquals(1_201, ids.stream().distinct().count());
            assertEquals(10_000, ids.getFirst());
            assertEquals(11_200, ids.getLast());
            assertEquals(3, gateway.countyCalls.get(), "each page is a fresh bounded directory request");
        }
    }

    @Test
    void rejectsAnOversizedRelevantDirectoryInsteadOfSilentlyTruncatingIt() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        List<TrunkedSystem> systems = new ArrayList<>();

        for(int index = 0; index <= 10_000; index++)
        {
            systems.add(new TrunkedSystem(20_000 + index, "Oversized %05d".formatted(index), "", 1, 2, 3));
        }

        gateway.county = new CountyDirectory(gateway.county.county(), systems, List.of());

        try(RadioReferenceDirectoryService service = service(new FakeFactory(gateway)))
        {
            service.login("user", "secret".toCharArray());
            assertEquals(Code.RESULT_SET_TOO_LARGE,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.browse(new LocationSelection(1, 10, 100), "",
                        EntryGroup.TRUNKED_SYSTEMS, ScopeFilter.COUNTY, 0, 500)).code());
        }
    }

    @Test
    void rejectsExcessWaitingRequestsWithoutStartingMoreRemoteWork() throws Exception
    {
        FakeGateway gateway = populatedGateway();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService(new FakeFactory(gateway),
            1, 1, Duration.ofSeconds(5), Duration.ofMillis(100), CLOCK))
        {
            service.login("user", "secret".toCharArray());
            gateway.blockCountries(false);
            ExecutorService callers = Executors.newFixedThreadPool(2);

            try
            {
                Future<?> active = callers.submit(() -> countries(service));
                assertTrue(gateway.countriesEntered.await(1, TimeUnit.SECONDS));
                Future<?> waiting = callers.submit(() -> countries(service));
                waitFor(() -> service.runtimeStatus().waitingRequests() == 1);

                RadioReferenceDirectoryException busy =
                    assertThrows(RadioReferenceDirectoryException.class,
                        () -> service.countries("", 10));
                assertEquals(Code.BUSY, busy.code());
                assertEquals(1, service.runtimeStatus().activeRequests());
                assertEquals(1, service.runtimeStatus().waitingRequests());

                gateway.releaseCountries.countDown();
                active.get(1, TimeUnit.SECONDS);
                waiting.get(1, TimeUnit.SECONDS);
            }
            finally
            {
                gateway.releaseCountries.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test
    void timesOutRemoteWorkAndRecoversTheBoundedWorker() throws Exception
    {
        FakeGateway gateway = populatedGateway();

        try(RadioReferenceDirectoryService service = new RadioReferenceDirectoryService(new FakeFactory(gateway),
            1, 1, Duration.ofMillis(75), Duration.ofMillis(50), CLOCK))
        {
            service.login("user", "secret".toCharArray());
            gateway.blockCountries(true);

            RadioReferenceDirectoryException timeout =
                assertTimeoutPreemptively(Duration.ofMillis(500),
                    () -> assertThrows(RadioReferenceDirectoryException.class,
                        () -> service.countries("", 10)));
            assertEquals(Code.TIMEOUT, timeout.code());
            assertEquals(1, service.runtimeStatus().activeRequests(),
                "the deadline bounds the caller wait but cannot stop an upstream call that ignores interruption");

            gateway.releaseCountries.countDown();
            waitFor(() -> service.runtimeStatus().activeRequests() == 0);

            gateway.blockCountries = false;
            assertFalse(service.countries("", 10).items().isEmpty());
        }
    }

    @Test
    void closeReturnsOnTimeEvenWhenAnUpstreamCallIgnoresInterruption() throws Exception
    {
        FakeGateway gateway = populatedGateway();
        RadioReferenceDirectoryService service = new RadioReferenceDirectoryService(new FakeFactory(gateway),
            1, 1, Duration.ofSeconds(5), Duration.ofMillis(25), CLOCK);
        service.login("user", "secret".toCharArray());
        gateway.blockCountries(true);
        ExecutorService caller = Executors.newFixedThreadPool(2);

        try
        {
            Future<?> active = caller.submit(() -> countries(service));
            assertTrue(gateway.countriesEntered.await(1, TimeUnit.SECONDS));
            Future<?> waiting = caller.submit(() -> countries(service));
            waitFor(() -> service.runtimeStatus().waitingRequests() == 1);

            assertTimeoutPreemptively(Duration.ofMillis(500), service::close);
            active.get(500, TimeUnit.MILLISECONDS);
            waiting.get(500, TimeUnit.MILLISECONDS);
            assertEquals(AccountState.CLOSED, service.status().state());
            assertTrue(service.runtimeStatus().closed());
            assertFalse(service.runtimeStatus().remoteWorkerTerminated());
            assertEquals(1, service.runtimeStatus().activeRequests(),
                "logical close does not claim that an interruption-ignoring upstream call has stopped");
            assertEquals(0, service.runtimeStatus().waitingRequests());
            assertEquals(Code.CLOSED,
                assertThrows(RadioReferenceDirectoryException.class,
                    () -> service.countries("", 10)).code());

            gateway.releaseCountries.countDown();
            waitFor(() -> service.runtimeStatus().remoteWorkerTerminated());
        }
        finally
        {
            gateway.releaseCountries.countDown();
            service.close();
            caller.shutdownNow();
        }
    }

    private static void countries(RadioReferenceDirectoryService service)
    {
        try
        {
            service.countries("", 10);
        }
        catch(RadioReferenceDirectoryException exception)
        {
            if(exception.code() != Code.CLOSED && exception.code() != Code.NOT_AUTHENTICATED &&
                exception.code() != Code.UNAVAILABLE)
            {
                throw new AssertionError(exception);
            }
        }
    }

    private static RadioReferenceDirectoryService service(FakeFactory factory)
    {
        return new RadioReferenceDirectoryService(factory, 1, 4, Duration.ofSeconds(2),
            Duration.ofMillis(100), CLOCK);
    }

    private static FakeGateway populatedGateway()
    {
        FakeGateway gateway = new FakeGateway();
        gateway.account = new Account("directory-user", "Never - Test Account");
        gateway.countries = List.of(
            new Country(2, "Canada", "CA"),
            new Country(1, "United States", "US"),
            new Country(0, "Invalid", "XX"));
        gateway.country = new CountryDirectory(
            new Country(1, "United States", "US"),
            List.of(new State(11, "Alaska", "AK"), new State(10, "Test State", "TS")),
            List.of(new Agency(1000, "Federal Operations", 1)));
        gateway.state = new StateDirectory(
            new State(10, "Test State", "TS"),
            List.of(new County(101, "Adams", ""), new County(100, "Franklin", "")),
            List.of(
                new TrunkedSystem(2001, "State P25", "Capital", 1, 2, 3),
                new TrunkedSystem(2000, "Metro Radio", "Metro", 4, 5, 6)),
            List.of(new Agency(1001, "State Police", 2)));
        gateway.county = new CountyDirectory(
            new County(100, "Franklin", "Franklin County"),
            List.of(new TrunkedSystem(2002, "County P25", "Franklin", 1, 2, 3)),
            List.of(new Agency(1002, "County Fire", 3)));
        gateway.frequencyResults = List.of(
            new FrequencyResult(853.1625, 808.1625, "", "State P25 Site 012 Franklin Simulcast", "", "34C", "", "",
                "", "4", "", List.of("Law Dispatch"), 0, 2001, 0, 100),
            new FrequencyResult(853.1625, 0, "WQAB123", "County Dispatch", "Dispatch", "123.0 PL", "", "",
                "", "FMN", "RM", List.of("Law Dispatch"), 444, 0, 1001, 100));
        return gateway;
    }

    private static void waitFor(Condition condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(System.nanoTime() < deadline)
        {
            if(condition.evaluate())
            {
                return;
            }

            Thread.sleep(5);
        }

        throw new AssertionError("condition did not become true before timeout");
    }

    @FunctionalInterface
    private interface Condition
    {
        boolean evaluate() throws Exception;
    }

    private static final class FakeFactory implements RadioReferenceGatewayFactory
    {
        private final FakeGateway mGateway;
        private char[] passwordReference;

        private FakeFactory(FakeGateway gateway)
        {
            mGateway = gateway;
        }

        @Override
        public RadioReferenceGateway open(String userName, char[] password)
        {
            passwordReference = password;
            return mGateway;
        }
    }

    private static final class FakeGateway implements RadioReferenceGateway
    {
        private Account account;
        private RadioReferenceGatewayException.Kind accountFailure;
        private List<Country> countries = List.of();
        private CountryDirectory country =
            new CountryDirectory(new Country(1, "", ""), List.of(), List.of());
        private StateDirectory state =
            new StateDirectory(new State(1, "", ""), List.of(), List.of(), List.of());
        private CountyDirectory county =
            new CountyDirectory(new County(1, "", ""), List.of(), List.of());
        private List<FrequencyResult> frequencyResults = List.of();
        private List<Mode> modes = List.of(new Mode(4, "Project 25 Phase I"));
        private List<Site> sites = List.of(new Site(3001, 2001, 12, "Franklin Simulcast", 100,
            List.of(new SiteChannel(853.1625, "c", true, false))));
        private List<FrequencyCategory> categories =
            List.of(new FrequencyCategory(444, "Public Safety", "County Dispatch"));
        private TrunkedSystemDetails systemDetails;
        private List<TrunkedSiteDetails> siteDetails = List.of();
        private List<RemoteTalkgroup> remoteTalkgroups = List.of();
        private List<RemoteTalkgroupCategory> remoteCategories = List.of();
        private List<ConventionalFrequency> conventional = List.of();
        private volatile boolean closed;
        private volatile boolean blockCountries;
        private volatile boolean ignoreCountryInterrupt;
        private final AtomicInteger stateCalls = new AtomicInteger();
        private final AtomicInteger countyCalls = new AtomicInteger();
        private final AtomicInteger modeCalls = new AtomicInteger();
        private final AtomicInteger siteCalls = new AtomicInteger();
        private final AtomicInteger categoryCalls = new AtomicInteger();
        private CountDownLatch countriesEntered = new CountDownLatch(1);
        private CountDownLatch releaseCountries = new CountDownLatch(0);

        @Override
        public Account account() throws RadioReferenceGatewayException
        {
            if(accountFailure != null)
            {
                throw new RadioReferenceGatewayException(accountFailure);
            }

            return account;
        }

        @Override
        public List<Country> countries() throws RadioReferenceGatewayException
        {
            if(blockCountries)
            {
                countriesEntered.countDown();

                while(releaseCountries.getCount() > 0)
                {
                    try
                    {
                        releaseCountries.await(10, TimeUnit.MILLISECONDS);
                    }
                    catch(InterruptedException exception)
                    {
                        if(!ignoreCountryInterrupt)
                        {
                            Thread.currentThread().interrupt();
                            throw new RadioReferenceGatewayException(
                                RadioReferenceGatewayException.Kind.UNAVAILABLE);
                        }
                    }
                }
            }

            return countries;
        }

        private void blockCountries(boolean ignoreInterrupt)
        {
            blockCountries = true;
            ignoreCountryInterrupt = ignoreInterrupt;
            countriesEntered = new CountDownLatch(1);
            releaseCountries = new CountDownLatch(1);
        }

        @Override
        public CountryDirectory country(int countryId)
        {
            return country;
        }

        @Override
        public StateDirectory state(int stateId)
        {
            stateCalls.incrementAndGet();
            return state;
        }

        @Override
        public CountyDirectory county(int countyId)
        {
            countyCalls.incrementAndGet();
            return county;
        }

        @Override
        public List<FrequencyResult> searchStateFrequencies(int stateId, double frequencyMHz)
        {
            return frequencyResults;
        }

        @Override
        public List<Mode> modes()
        {
            modeCalls.incrementAndGet();
            return modes;
        }

        @Override
        public List<Site> sites(int systemId)
        {
            siteCalls.incrementAndGet();
            return sites.stream().filter(site -> site.systemId() == systemId).toList();
        }

        @Override
        public List<FrequencyCategory> agencyFrequencyCategories(int agencyId)
        {
            categoryCalls.incrementAndGet();
            return categories;
        }

        @Override
        public TrunkedSystemDetails trunkedSystemDetails(int systemId)
        {
            return systemDetails;
        }

        @Override
        public List<TrunkedSiteDetails> trunkedSiteDetails(int systemId)
        {
            return siteDetails;
        }

        @Override
        public List<RemoteTalkgroup> talkgroups(int systemId)
        {
            return remoteTalkgroups;
        }

        @Override
        public List<RemoteTalkgroupCategory> talkgroupCategories(int systemId)
        {
            return remoteCategories;
        }

        @Override
        public List<ConventionalFrequency> subcategoryFrequencies(int subCategoryId)
        {
            return conventional;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }
}

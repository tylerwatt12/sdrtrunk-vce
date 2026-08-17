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
            assertEquals("State P25", trunked.description());
            assertEquals(2001, trunked.systemId());
            assertEquals("State P25", trunked.systemName());
            assertEquals("Franklin Simulcast", trunked.siteName());
            assertEquals("Primary control", trunked.channelUse());
            assertEquals("Project 25 Phase I", trunked.modeName());
            assertEquals("Franklin", trunked.countyName());
            assertEquals("https://www.radioreference.com/db/sid/2001", trunked.radioReferenceUrl());

            FrequencyMatch conventional = service.searchStateFrequencies(10, 853_162_500L, 1, 1)
                .items().getFirst();
            assertEquals("County Dispatch", conventional.description());
            assertEquals("Public Safety", conventional.category());
            assertEquals("County Dispatch", conventional.subCategory());
            assertEquals("Conventional", conventional.channelUse());
            assertEquals("https://www.radioreference.com/db/subcat/444", conventional.radioReferenceUrl());
            assertEquals("State Police", conventional.agencyName());
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
            new FrequencyResult(853.1625, 808.1625, "", "State P25", "Franklin Simulcast", "34C", "", "",
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
        private volatile boolean closed;
        private volatile boolean blockCountries;
        private volatile boolean ignoreCountryInterrupt;
        private final AtomicInteger stateCalls = new AtomicInteger();
        private final AtomicInteger countyCalls = new AtomicInteger();
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
            return modes;
        }

        @Override
        public List<Site> sites(int systemId)
        {
            return List.of(new Site(3001, systemId, 12, "Franklin Simulcast", 100,
                List.of(new SiteChannel(853.1625, "c", true, false))));
        }

        @Override
        public List<FrequencyCategory> agencyFrequencyCategories(int agencyId)
        {
            return List.of(new FrequencyCategory(444, "Public Safety", "County Dispatch"));
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }
}

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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/**
 * Bounded session and directory service for RadioReference.
 *
 * <p>Every remote operation runs on a dedicated, bounded executor and has one total caller deadline that includes
 * queue time.  A timeout cancels the future and interrupts the worker, but cannot force an upstream library call that
 * ignores interruption to stop.  {@link #runtimeStatus()} exposes whether remote work remains.  Results are
 * request-local and are not cached or written to a database.</p>
 */
public final class RadioReferenceDirectoryService implements AutoCloseable
{
    public static final int MAXIMUM_RESULT_LIMIT = 500;
    public static final int MAXIMUM_QUERY_LENGTH = 128;
    private static final int MAXIMUM_REMOTE_ITEMS_SCANNED = 10_000;
    private static final int MAXIMUM_DISCOVERY_SYSTEMS = 8;
    private static final int DEFAULT_REMOTE_CONCURRENCY = 1;
    private static final int DEFAULT_WAITING_REQUESTS = 8;
    private static final Duration DEFAULT_REQUEST_DEADLINE = Duration.ofSeconds(10);
    private static final Duration TYPE_LABEL_REQUEST_DEADLINE = Duration.ofSeconds(3);
    private static final Duration DEFAULT_DETAIL_REQUEST_DEADLINE = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SHUTDOWN_WAIT = Duration.ofSeconds(1);
    private static final DateTimeFormatter EXPIRATION_FORMAT =
        DateTimeFormatter.ofPattern("MM-dd-uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern TRUNKED_SITE_DESCRIPTION =
        Pattern.compile("\\bSite\\s+(\\d+)\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Comparator<DirectoryEntry> ENTRY_ORDER =
        Comparator.comparing(DirectoryEntry::scope)
            .thenComparing(DirectoryEntry::type)
            .thenComparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.detail().kind())
            .thenComparingInt(entry -> entry.detail().id());
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Object mSessionLock = new Object();
    private final RadioReferenceGatewayFactory mGatewayFactory;
    private final ThreadPoolExecutor mExecutor;
    private final Set<Future<?>> mRequests = ConcurrentHashMap.newKeySet();
    private final int mMaximumRemoteConcurrency;
    private final int mMaximumWaitingRequests;
    private final long mRequestDeadlineNanos;
    private final long mShutdownWaitNanos;
    private final Clock mClock;
    private volatile AccountStatus mAccountStatus = AccountStatus.signedOut();
    private volatile boolean mClosed;
    private Session mSession;
    private long mSessionGeneration;

    public RadioReferenceDirectoryService()
    {
        this(RadioReferenceGatewayFactory.rrapi());
    }

    public RadioReferenceDirectoryService(RadioReferenceGatewayFactory gatewayFactory)
    {
        this(gatewayFactory, DEFAULT_REMOTE_CONCURRENCY, DEFAULT_WAITING_REQUESTS, DEFAULT_REQUEST_DEADLINE,
            DEFAULT_SHUTDOWN_WAIT, Clock.systemUTC());
    }

    RadioReferenceDirectoryService(RadioReferenceGatewayFactory gatewayFactory, int maximumRemoteConcurrency,
                                   int maximumWaitingRequests, Duration requestDeadline, Duration shutdownWait,
                                   Clock clock)
    {
        mGatewayFactory = Objects.requireNonNull(gatewayFactory);
        mMaximumRemoteConcurrency = positive(maximumRemoteConcurrency, "maximumRemoteConcurrency");
        mMaximumWaitingRequests = positive(maximumWaitingRequests, "maximumWaitingRequests");
        mRequestDeadlineNanos = positiveNanos(requestDeadline, "requestDeadline");
        mShutdownWaitNanos = positiveNanos(shutdownWait, "shutdownWait");
        mClock = Objects.requireNonNull(clock);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable,
                "sdrtrunk radioreference remote " + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        mExecutor = new ThreadPoolExecutor(mMaximumRemoteConcurrency, mMaximumRemoteConcurrency, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(mMaximumWaitingRequests), threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Replaces any existing session and verifies the supplied account.  Both the caller's password array and the
     * service's private working copy are cleared before this method returns.
     */
    public AccountStatus login(String userName, char[] password) throws RadioReferenceDirectoryException
    {
        char[] workingPassword = password == null ? null : Arrays.copyOf(password, password.length);

        if(password != null)
        {
            Arrays.fill(password, '\0');
        }

        try
        {
            String normalizedUserName = normalizedCredential(userName, 256);

            if(normalizedUserName == null || workingPassword == null || workingPassword.length == 0 ||
                workingPassword.length > 1_024)
            {
                throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
            }

            long generation;
            Session previous;

            synchronized(mSessionLock)
            {
                ensureOpen();
                previous = mSession;
                mSession = null;
                generation = ++mSessionGeneration;
                mAccountStatus = AccountStatus.checking();
            }

            close(previous);
            LoginAttempt loginAttempt = new LoginAttempt();

            try
            {
                RadioReferenceGateway.Account account = invoke(() -> {
                    RadioReferenceGateway gateway = mGatewayFactory.open(normalizedUserName, workingPassword);
                    loginAttempt.track(gateway);
                    return gateway.account();
                });
                RadioReferenceGateway gateway = loginAttempt.claim();
                AccountStatus accountStatus = classify(account);

                if(gateway == null || accountStatus.state() == AccountState.UNAVAILABLE)
                {
                    close(gateway);
                    AccountStatus unavailable = AccountStatus.unavailable();
                    setFailedLoginStatus(generation, unavailable);
                    return unavailable;
                }

                RadioReferenceDirectoryException.Code installationFailure = null;

                synchronized(mSessionLock)
                {
                    if(mClosed)
                    {
                        installationFailure = RadioReferenceDirectoryException.Code.CLOSED;
                    }
                    else if(generation != mSessionGeneration)
                    {
                        installationFailure = RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED;
                    }
                    else
                    {
                        mSession = new Session(gateway, accountStatus);
                        mAccountStatus = accountStatus;
                    }
                }

                if(installationFailure != null)
                {
                    close(gateway);
                    throw new RadioReferenceDirectoryException(installationFailure);
                }

                return accountStatus;
            }
            catch(RadioReferenceDirectoryException exception)
            {
                loginAttempt.abandon();

                if(exception.code() == RadioReferenceDirectoryException.Code.CLOSED ||
                    exception.code() == RadioReferenceDirectoryException.Code.INTERRUPTED)
                {
                    throw exception;
                }

                if(mClosed)
                {
                    throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.CLOSED);
                }

                AccountStatus failure = switch(exception.code())
                {
                    case INVALID_CREDENTIALS -> AccountStatus.invalidCredentials();
                    case INSECURE_TRANSPORT -> AccountStatus.secureTransportRequired();
                    default -> AccountStatus.unavailable();
                };
                setFailedLoginStatus(generation, failure);
                return failure;
            }
        }
        finally
        {
            if(workingPassword != null)
            {
                Arrays.fill(workingPassword, '\0');
            }
        }
    }

    public AccountStatus status()
    {
        return mAccountStatus;
    }

    /** Loads the authenticated user's Broadcastify feed assignments for stream configuration. */
    public List<RadioReferenceGateway.UserFeed> userFeeds() throws RadioReferenceDirectoryException
    {
        List<RadioReferenceGateway.UserFeed> source = invokePremium(RadioReferenceGateway::userFeeds);
        enforceImportBound(source == null ? 0 : source.size());
        List<RadioReferenceGateway.UserFeed> feeds = new ArrayList<>();

        if(source != null)
        {
            source.stream().filter(Objects::nonNull).filter(feed -> feed.id() > 0)
                .map(feed -> new RadioReferenceGateway.UserFeed(feed.id(), text(feed.description()),
                    text(feed.host()), text(feed.port()), text(feed.mount()),
                    feed.password() == null ? "" : feed.password()))
                .sorted(Comparator.comparing(RadioReferenceGateway.UserFeed::description,
                    String.CASE_INSENSITIVE_ORDER).thenComparingInt(RadioReferenceGateway.UserFeed::id))
                .forEach(feeds::add);
        }

        return List.copyOf(feeds);
    }

    public RuntimeStatus runtimeStatus()
    {
        return new RuntimeStatus(mExecutor.getActiveCount(), mExecutor.getQueue().size(),
            mMaximumRemoteConcurrency, mMaximumWaitingRequests, mClosed, mExecutor.isTerminated());
    }

    public void logout()
    {
        Session previous;

        synchronized(mSessionLock)
        {
            if(mClosed)
            {
                return;
            }

            previous = mSession;
            mSession = null;
            ++mSessionGeneration;
            mAccountStatus = AccountStatus.signedOut();
        }

        close(previous);
    }

    public BoundedPage<DirectoryOption> countries(String search, int limit)
        throws RadioReferenceDirectoryException
    {
        return countries(search, 0, limit);
    }

    public BoundedPage<DirectoryOption> countries(String search, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        String normalizedSearch = normalizedSearch(search);
        validatePage(offset, limit);
        List<RadioReferenceGateway.Country> countries = invokePremium(RadioReferenceGateway::countries);
        List<DirectoryOption> options = new ArrayList<>();
        int scanned = 0;

        if(countries != null)
        {
            for(RadioReferenceGateway.Country country: countries)
            {
                if(scanned++ >= MAXIMUM_REMOTE_ITEMS_SCANNED)
                {
                    throw tooLarge();
                }

                if(country != null && country.id() > 0 && matches(normalizedSearch, country.name(), country.code()))
                {
                    options.add(new DirectoryOption(country.id(), text(country.name()), text(country.code())));
                }
            }
        }

        return optionPage(options, offset, limit);
    }

    public BoundedPage<DirectoryOption> states(int countryId, String search, int limit)
        throws RadioReferenceDirectoryException
    {
        return states(countryId, search, 0, limit);
    }

    public BoundedPage<DirectoryOption> states(int countryId, String search, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(countryId);
        String normalizedSearch = normalizedSearch(search);
        validatePage(offset, limit);
        RadioReferenceGateway.CountryDirectory directory =
            invokePremium(gateway -> verifiedCountry(gateway.country(countryId), countryId));
        List<DirectoryOption> options = new ArrayList<>();
        int scanned = 0;

        for(RadioReferenceGateway.State state: directory.states())
        {
            if(scanned++ >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw tooLarge();
            }

            if(state != null && state.id() > 0 && matches(normalizedSearch, state.name(), state.code()))
            {
                options.add(new DirectoryOption(state.id(), text(state.name()), text(state.code())));
            }
        }

        return optionPage(options, offset, limit);
    }

    public BoundedPage<DirectoryOption> counties(int stateId, String search, int limit)
        throws RadioReferenceDirectoryException
    {
        return counties(stateId, search, 0, limit);
    }

    public BoundedPage<DirectoryOption> counties(int stateId, String search, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(stateId);
        String normalizedSearch = normalizedSearch(search);
        validatePage(offset, limit);
        RadioReferenceGateway.StateDirectory directory =
            invokePremium(gateway -> verifiedState(gateway.state(stateId), stateId));
        List<DirectoryOption> options = new ArrayList<>();
        int scanned = 0;

        for(RadioReferenceGateway.County county: directory.counties())
        {
            if(scanned++ >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw tooLarge();
            }

            if(county != null && county.id() > 0 && matches(normalizedSearch, county.name(), county.header()))
            {
                options.add(new DirectoryOption(county.id(), text(county.name()), text(county.header())));
            }
        }

        return optionPage(options, offset, limit);
    }

    /**
     * Loads the selected country, optional state and optional county in one bounded remote request and combines their
     * systems and conventional agencies into one tagged result list.
     */
    public BoundedPage<DirectoryEntry> browse(LocationSelection selection, String search, EntryGroup group,
                                               ScopeFilter scopeFilter, int limit)
        throws RadioReferenceDirectoryException
    {
        return browse(selection, search, group, scopeFilter, 0, limit);
    }

    /**
     * Returns one stable, bounded page.  Repeating the same filters with {@link BoundedPage#nextOffset()} enumerates
     * every accepted result when the upstream directory is unchanged between requests.  The service rejects a source
     * larger than its safety bound instead of silently making the unscanned tail inaccessible.
     */
    public BoundedPage<DirectoryEntry> browse(LocationSelection selection, String search, EntryGroup group,
                                               ScopeFilter scopeFilter, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validatePage(offset, limit);
        return page(browseEntries(selection, search, group, scopeFilter), offset, limit);
    }

    /** Returns the complete location result set for the browser's collapsible directory tree. */
    public List<DirectoryEntry> browseCatalog(LocationSelection selection) throws RadioReferenceDirectoryException
    {
        return browseEntries(selection, "", EntryGroup.ALL, ScopeFilter.ALL);
    }

    private List<DirectoryEntry> browseEntries(LocationSelection selection, String search, EntryGroup group,
                                                ScopeFilter scopeFilter) throws RadioReferenceDirectoryException
    {
        validateSelection(selection);
        String normalizedSearch = normalizedSearch(search);
        if(group == null || scopeFilter == null)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        LocationSnapshot snapshot = invokePremium(gateway -> locationSnapshot(gateway, selection));
        Map<Integer,String> systemTypes = Map.of();
        if((snapshot.state() != null && !snapshot.state().systems().isEmpty()) ||
            (snapshot.county() != null && !snapshot.county().systems().isEmpty()))
        {
            // Type metadata is needed to exclude unsupported trunked systems; do not show a partial directory.
            systemTypes = invokePremium(RadioReferenceGateway::systemTypes,
                Math.min(mRequestDeadlineNanos, TYPE_LABEL_REQUEST_DEADLINE.toNanos()));
            if(systemTypes.isEmpty())
            {
                throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.UNAVAILABLE);
            }
        }
        EntryAccumulator accumulator = new EntryAccumulator(normalizedSearch, group, scopeFilter, systemTypes);
        accumulator.addAgencies(snapshot.country().agencies(), EntryScope.NATIONAL);

        if(snapshot.state() != null)
        {
            accumulator.addSystems(snapshot.state().systems(), EntryScope.STATE);
            accumulator.addAgencies(snapshot.state().agencies(), EntryScope.STATE);
        }

        if(snapshot.county() != null)
        {
            accumulator.addSystems(snapshot.county().systems(), EntryScope.COUNTY);
            accumulator.addCounty(snapshot.county().county());
            accumulator.addAgencies(snapshot.county().agencies(), EntryScope.COUNTY);
        }

        if(accumulator.overflowed())
        {
            throw tooLarge();
        }

        List<DirectoryEntry> entries = new ArrayList<>(accumulator.entries().values());
        entries.sort(ENTRY_ORDER);
        return List.copyOf(entries);
    }

    /**
     * Searches one RadioReference state for an exact frequency.  RadioReference combines conventional records and
     * trunked-site frequency matches in this response.  One state-directory lookup enriches the compact upstream
     * rows with stable county, agency and unambiguous trunked-system links.
     */
    public BoundedPage<FrequencyMatch> searchStateFrequencies(int stateId, long frequencyHz, int limit)
        throws RadioReferenceDirectoryException
    {
        return searchStateFrequencies(stateId, frequencyHz, 0, limit);
    }

    public BoundedPage<FrequencyMatch> searchStateFrequencies(int stateId, long frequencyHz, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(stateId);
        validatePage(offset, limit);

        if(frequencyHz <= 0 || frequencyHz > 100_000_000_000L)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        FrequencySnapshot snapshot = invokePremium(gateway -> new FrequencySnapshot(
            verifiedState(gateway.state(stateId), stateId),
            gateway.searchStateFrequencies(stateId, frequencyHz / 1_000_000.0)));
        List<RadioReferenceGateway.FrequencyResult> source = snapshot.results() != null ? snapshot.results() : List.of();
        long enrichmentItems = (long)snapshot.state().counties().size() + snapshot.state().agencies().size() +
            snapshot.state().systems().size();

        if(source.size() > MAXIMUM_REMOTE_ITEMS_SCANNED || enrichmentItems > MAXIMUM_REMOTE_ITEMS_SCANNED)
        {
            throw tooLarge();
        }

        Map<Integer,String> counties = new LinkedHashMap<>();
        snapshot.state().counties().stream().filter(Objects::nonNull)
            .forEach(county -> counties.putIfAbsent(county.id(), text(county.name())));
        Map<Integer,String> agencies = new LinkedHashMap<>();
        snapshot.state().agencies().stream().filter(Objects::nonNull)
            .forEach(agency -> agencies.putIfAbsent(agency.id(), text(agency.name())));
        Map<Integer,RadioReferenceGateway.TrunkedSystem> systems = new LinkedHashMap<>();
        snapshot.state().systems().stream().filter(Objects::nonNull)
            .forEach(system -> systems.putIfAbsent(system.id(), system));
        Map<Integer,String> modes = Map.of();

        List<FrequencyMatch> matches = new ArrayList<>(source.size());

        for(RadioReferenceGateway.FrequencyResult result: source)
        {
            if(result == null || !Double.isFinite(result.downlinkMHz()) || result.downlinkMHz() <= 0)
            {
                continue;
            }

            Integer systemId = result.systemId() > 0 ? result.systemId() : null;
            RadioReferenceGateway.TrunkedSystem system = systemId == null ? null : systems.get(systemId);
            MatchType matchType = systemId == null ? MatchType.CONVENTIONAL : MatchType.TRUNKED;
            String url = radioReferenceUrl(systemId, result.subCategoryId(), result.agencyId(), result.countyId());
            if(matches.size() >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw tooLarge();
            }

            String alphaTag = matchType == MatchType.CONVENTIONAL ? text(result.alpha()) : "";
            TrunkedResultHint trunkedHint = matchType == MatchType.TRUNKED ?
                trunkedResultHint(result.description(), result.alpha(), system == null ? "" : system.name()) :
                TrunkedResultHint.NONE;
            matches.add(new FrequencyMatch(result.downlinkMHz(), positive(result.uplinkMHz()),
                text(result.tone()), text(result.callsign()), text(result.description()), alphaTag, "", "",
                text(result.mode()), resolveMode(result.mode(), modes), text(result.classification()),
                text(result.colorCode()), text(result.talkgroup()), text(result.slot()), result.tags(), matchType,
                channelUse(matchType, null), stateId, text(snapshot.state().state().name()), result.countyId(),
                counties.getOrDefault(result.countyId(), ""), result.agencyId(),
                agencies.getOrDefault(result.agencyId(), ""), result.subCategoryId(), systemId,
                trunkedHint.systemName(), null, trunkedHint.siteNumber(), trunkedHint.siteName(), url));
        }

        return page(matches, offset, limit);
    }

    /**
     * Loads exact-frequency trunked candidates for identity verification. The entire search and all detail requests
     * share the existing bounded detail deadline. Candidate and channel bounds prevent discovery from walking a
     * whole state catalog; no partial result is eligible for automatic matching.
     */
    public List<DiscoverySystem> discoverySystems(int stateId, long frequencyHz)
        throws RadioReferenceDirectoryException
    {
        return discoverySystems(stateId, frequencyHz, system -> true);
    }

    /** Rejects incompatible system identities before requesting their potentially large site catalogs. */
    public List<DiscoverySystem> discoverySystems(int stateId, long frequencyHz,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter)
        throws RadioReferenceDirectoryException
    {
        return discoverySystems(stateId, frequencyHz, identityFilter, DEFAULT_DETAIL_REQUEST_DEADLINE);
    }

    List<DiscoverySystem> discoverySystems(int stateId, long frequencyHz,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter, Duration deadline)
        throws RadioReferenceDirectoryException
    {
        validateId(stateId);
        Objects.requireNonNull(identityFilter);
        long deadlineNanos = Math.min(positiveNanos(deadline, "deadline"), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        if(frequencyHz <= 0 || frequencyHz > 100_000_000_000L)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }
        return invokePremium(gateway -> frequencyDiscoverySystems(gateway, stateId, frequencyHz, identityFilter),
            deadlineNanos);
    }

    /** Global P25 System ID lookup uses a state-frequency fallback only when the candidate set exceeds its bound. */
    public List<DiscoverySystem> p25DiscoverySystems(int systemId, long frequencyHz, Integer fallbackStateId,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter) throws RadioReferenceDirectoryException
    {
        return p25DiscoverySystems(systemId, frequencyHz, fallbackStateId, identityFilter, DEFAULT_DETAIL_REQUEST_DEADLINE);
    }

    List<DiscoverySystem> p25DiscoverySystems(int systemId, long frequencyHz, Integer fallbackStateId,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter, Duration deadline)
        throws RadioReferenceDirectoryException
    {
        if(systemId < 0 || systemId > 0xFFF || frequencyHz <= 0 || frequencyHz > 100_000_000_000L)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }
        if(fallbackStateId != null) validateId(fallbackStateId);
        Objects.requireNonNull(identityFilter);
        long deadlineNanos = Math.min(positiveNanos(deadline, "deadline"), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        return invokePremium(gateway -> {
            try
            {
                return p25SystemCandidates(gateway, systemId, identityFilter);
            }
            catch(RadioReferenceGatewayException exception)
            {
                if(exception.kind() != RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE || fallbackStateId == null)
                    throw exception;
                return frequencyDiscoverySystems(gateway, fallbackStateId, frequencyHz, identityFilter);
            }
        }, deadlineNanos);
    }

    private static List<DiscoverySystem> p25SystemCandidates(RadioReferenceGateway gateway, int systemId,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter) throws RadioReferenceGatewayException
    {
        List<RadioReferenceGateway.TrunkedSystem> candidates = gateway.p25SystemsBySystemId(systemId);
        if(candidates != null && candidates.size() > MAXIMUM_REMOTE_ITEMS_SCANNED)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
        }
        Set<Integer> systemIds = new LinkedHashSet<>();
        if(candidates != null)
        {
            for(RadioReferenceGateway.TrunkedSystem candidate: candidates)
            {
                if(candidate != null && candidate.id() > 0) systemIds.add(candidate.id());
            }
        }
        return discoverySystemDetails(gateway, systemIds, identityFilter);
    }

    private static List<DiscoverySystem> frequencyDiscoverySystems(RadioReferenceGateway gateway, int stateId,
        long frequencyHz, Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter)
        throws RadioReferenceGatewayException
    {
        List<RadioReferenceGateway.FrequencyResult> frequencies =
            gateway.searchStateFrequencies(stateId, frequencyHz / 1_000_000.0);
        if(frequencies != null && frequencies.size() > MAXIMUM_REMOTE_ITEMS_SCANNED)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
        }
        Set<Integer> systemIds = new LinkedHashSet<>();
        if(frequencies != null)
        {
            for(RadioReferenceGateway.FrequencyResult frequency: frequencies)
            {
                if(frequency != null && frequency.systemId() > 0 && Double.isFinite(frequency.downlinkMHz()) &&
                    Math.round(frequency.downlinkMHz() * 1_000_000.0) == frequencyHz)
                {
                    systemIds.add(frequency.systemId());
                }
            }
        }
        return discoverySystemDetails(gateway, systemIds, identityFilter);
    }

    private static List<DiscoverySystem> discoverySystemDetails(RadioReferenceGateway gateway, Set<Integer> systemIds,
        Predicate<RadioReferenceGateway.TrunkedSystemDetails> identityFilter) throws RadioReferenceGatewayException
    {
        if(systemIds.size() > MAXIMUM_DISCOVERY_SYSTEMS)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
        }
        List<DiscoverySystem> systems = new ArrayList<>();
        long detailItems = 0;
        for(int systemId: systemIds)
        {
            if(Thread.currentThread().isInterrupted())
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
            }
            RadioReferenceGateway.TrunkedSystemDetails system = gateway.trunkedSystemDetails(systemId);
            if(system == null || system.id() != systemId)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }
            if(!identityFilter.test(system)) continue;
            List<RadioReferenceGateway.TrunkedSiteDetails> sites = gateway.trunkedSiteDetails(systemId);
            if(sites != null && sites.size() > MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }
            List<RadioReferenceGateway.TrunkedSiteDetails> verified = new ArrayList<>();
            if(sites != null)
            {
                for(RadioReferenceGateway.TrunkedSiteDetails site: sites)
                {
                    if(site != null)
                    {
                        detailItems += 1L + site.channels().size();
                        if(detailItems > MAXIMUM_REMOTE_ITEMS_SCANNED)
                        {
                            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
                        }
                        if(site.systemId() == systemId)
                        {
                            verified.add(site);
                        }
                    }
                }
            }
            systems.add(new DiscoverySystem(system, verified));
        }
        return List.copyOf(systems);
    }

    /**
     * Loads optional detail for one explicitly selected frequency result.  The base exact-frequency search never
     * calls this method; site and category requests occur only after a user requests the drilldown.
     */
    public FrequencyDetails frequencyDetails(long frequencyHz, Integer systemId, int siteNumber, int subCategoryId,
                                             int agencyId, int countyId, String rawMode)
        throws RadioReferenceDirectoryException
    {
        if(frequencyHz <= 0 || frequencyHz > 100_000_000_000L || (systemId != null && systemId <= 0) ||
            siteNumber < 0 || subCategoryId < 0 || agencyId < 0 || countyId < 0 ||
            (rawMode != null && rawMode.length() > 32))
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        FrequencyDetailSnapshot snapshot = invokePremium(gateway -> frequencyDetailSnapshot(gateway, systemId,
            subCategoryId, agencyId, countyId), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        Map<Integer,String> modes = new LinkedHashMap<>();
        snapshot.modes().stream().filter(Objects::nonNull)
            .forEach(mode -> modes.putIfAbsent(mode.id(), text(mode.name())));
        RadioReferenceGateway.FrequencyCategory selectedCategory = snapshot.categories().stream()
            .filter(Objects::nonNull)
            .filter(category -> category.subCategoryId() == subCategoryId)
            .findFirst().orElse(null);
        SiteMatch siteMatch = selectSiteMatch(matchingSites(snapshot.sites(), frequencyHz), siteNumber, countyId);
        FrequencySiteDetail siteDetail = siteMatch == null ? null : new FrequencySiteDetail(siteMatch.siteId(),
            siteMatch.siteNumber(), siteMatch.siteName(), channelUse(MatchType.TRUNKED, siteMatch.channel()),
            radioReferenceSiteUrl(siteMatch.siteId()));

        return new FrequencyDetails(resolveMode(rawMode, modes),
            selectedCategory == null ? "" : selectedCategory.categoryName(),
            selectedCategory == null ? "" : selectedCategory.subCategoryName(), siteDetail);
    }

    /** Loads one trunked-system description for a preview/import workflow. */
    public RadioReferenceGateway.TrunkedSystemDetails trunkedSystemDetails(int systemId)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        RadioReferenceGateway.TrunkedSystemDetails details = invokePremium(
            gateway -> gateway.trunkedSystemDetails(systemId), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());

        if(details == null || details.id() != systemId)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.UNAVAILABLE);
        }

        return details;
    }

    /** Loads one stable, bounded page of complete sites for a selected trunked system. */
    public BoundedPage<RadioReferenceGateway.TrunkedSiteDetails> trunkedSites(int systemId, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validatePage(offset, limit);
        return page(allTrunkedSites(systemId), offset, limit);
    }

    /** Loads the complete bounded site catalog once for in-browser filtering and paging. */
    public List<RadioReferenceGateway.TrunkedSiteDetails> allTrunkedSites(int systemId)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        List<RadioReferenceGateway.TrunkedSiteDetails> source = invokePremium(
            gateway -> gateway.trunkedSiteDetails(systemId), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        enforceImportBound(source == null ? 0 : source.size());

        if(source != null)
        {
            long channelCount = source.stream().filter(Objects::nonNull)
                .mapToLong(site -> site.channels().size()).sum();

            if(channelCount > MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw tooLarge();
            }
        }

        List<RadioReferenceGateway.TrunkedSiteDetails> sites = source == null ? new ArrayList<>() :
            source.stream().filter(Objects::nonNull).filter(site -> site.systemId() == systemId)
                .sorted(Comparator.comparingInt(RadioReferenceGateway.TrunkedSiteDetails::number)
                    .thenComparing(RadioReferenceGateway.TrunkedSiteDetails::name,
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(RadioReferenceGateway.TrunkedSiteDetails::id))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        enforceImportBound(sites.size());
        return List.copyOf(sites);
    }

    /** Loads a filtered, stable page from the complete talkgroup catalog for a selected system. */
    public BoundedPage<RadioReferenceGateway.RemoteTalkgroup> talkgroups(int systemId, Integer categoryId,
                                                                         String search, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);

        if(categoryId != null && categoryId <= 0)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        String normalizedSearch = normalizedSearch(search);
        validatePage(offset, limit);
        List<RadioReferenceGateway.RemoteTalkgroup> source = loadTalkgroups(systemId);
        List<RadioReferenceGateway.RemoteTalkgroup> talkgroups = new ArrayList<>();

        source.stream().filter(talkgroup -> categoryId == null || talkgroup.categoryId() == categoryId)
            .filter(talkgroup -> matches(normalizedSearch, talkgroup.alphaTag(), talkgroup.description(),
                Integer.toString(talkgroup.value())))
            .sorted(Comparator.comparingInt(RadioReferenceGateway.RemoteTalkgroup::value)
                .thenComparingInt(RadioReferenceGateway.RemoteTalkgroup::id))
            .forEach(talkgroups::add);
        return page(talkgroups, offset, limit);
    }

    /** Loads the complete bounded catalog once for the explicit Import All operation. */
    public List<RadioReferenceGateway.RemoteTalkgroup> allTalkgroups(int systemId)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        return loadTalkgroups(systemId).stream()
            .sorted(Comparator.comparingInt(RadioReferenceGateway.RemoteTalkgroup::value)
                .thenComparingInt(RadioReferenceGateway.RemoteTalkgroup::id))
            .toList();
    }

    /** Reloads selected IDs so an import never trusts browser-supplied talkgroup fields. */
    public List<RadioReferenceGateway.RemoteTalkgroup> talkgroupsById(int systemId, Collection<Integer> talkgroupIds)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        LinkedHashSet<Integer> selectedIds = validatedIds(talkgroupIds);
        Map<Integer,RadioReferenceGateway.RemoteTalkgroup> indexed = new LinkedHashMap<>();

        loadTalkgroups(systemId).stream().filter(talkgroup -> selectedIds.contains(talkgroup.id()))
            .forEach(talkgroup -> indexed.putIfAbsent(talkgroup.id(), talkgroup));

        if(indexed.size() != selectedIds.size())
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        return selectedIds.stream().map(indexed::get).toList();
    }

    /** Loads the bounded category catalog for a selected trunked system. */
    public BoundedPage<RadioReferenceGateway.RemoteTalkgroupCategory> talkgroupCategories(int systemId,
                                                                                           int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        validatePage(offset, limit);
        return page(allTalkgroupCategories(systemId), offset, limit);
    }

    /** Returns category enrichment with one upstream read, regardless of the number of categories. */
    public List<RadioReferenceGateway.RemoteTalkgroupCategory> allTalkgroupCategories(int systemId)
        throws RadioReferenceDirectoryException
    {
        validateId(systemId);
        List<RadioReferenceGateway.RemoteTalkgroupCategory> source = invokePremium(
            gateway -> gateway.talkgroupCategories(systemId), DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        enforceImportBound(source == null ? 0 : source.size());
        List<RadioReferenceGateway.RemoteTalkgroupCategory> categories = source == null ? new ArrayList<>() :
            source.stream().filter(Objects::nonNull).filter(category -> category.systemId() == systemId)
                .sorted(Comparator.comparing(RadioReferenceGateway.RemoteTalkgroupCategory::name,
                    String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(RadioReferenceGateway.RemoteTalkgroupCategory::id))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        enforceImportBound(categories.size());
        return List.copyOf(categories);
    }

    /** Loads conventional frequency categories for one selected agency or county. */
    public BoundedPage<RadioReferenceGateway.FrequencyCategory> conventionalCategories(
        RadioReferenceGateway.DetailKind ownerKind, int ownerId, int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(ownerId);
        validatePage(offset, limit);

        if(ownerKind != RadioReferenceGateway.DetailKind.AGENCY &&
            ownerKind != RadioReferenceGateway.DetailKind.COUNTY)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        List<RadioReferenceGateway.FrequencyCategory> source = invokePremium(gateway ->
            ownerKind == RadioReferenceGateway.DetailKind.AGENCY ? gateway.agencyFrequencyCategories(ownerId) :
                gateway.countyFrequencyCategories(ownerId));
        enforceImportBound(source == null ? 0 : source.size());
        List<RadioReferenceGateway.FrequencyCategory> categories = source == null ? new ArrayList<>() :
            source.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(RadioReferenceGateway.FrequencyCategory::categoryName,
                    String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(RadioReferenceGateway.FrequencyCategory::subCategoryName,
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(RadioReferenceGateway.FrequencyCategory::subCategoryId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        enforceImportBound(categories.size());
        return page(categories, offset, limit);
    }

    /** Loads a filtered, stable page of conventional rows for one selected subcategory. */
    public BoundedPage<RadioReferenceGateway.ConventionalFrequency> conventionalFrequencies(int subCategoryId,
                                                                                             String search,
                                                                                             int offset, int limit)
        throws RadioReferenceDirectoryException
    {
        validateId(subCategoryId);
        String normalizedSearch = normalizedSearch(search);
        validatePage(offset, limit);
        List<RadioReferenceGateway.ConventionalFrequency> source = loadConventionalFrequencies(subCategoryId);
        List<RadioReferenceGateway.ConventionalFrequency> frequencies = new ArrayList<>();

        source.stream().filter(frequency -> frequency.subCategoryId() == subCategoryId)
            .filter(frequency -> matches(normalizedSearch, frequency.alphaTag(), frequency.description(),
                frequency.callsign(), frequency.mode(), Long.toString(frequency.downlinkHz())))
            .sorted(Comparator.comparingLong(RadioReferenceGateway.ConventionalFrequency::downlinkHz)
                .thenComparingInt(RadioReferenceGateway.ConventionalFrequency::id))
            .forEach(frequencies::add);
        return page(frequencies, offset, limit);
    }

    /** Reloads selected conventional IDs so an import never trusts browser-supplied frequency fields. */
    public List<RadioReferenceGateway.ConventionalFrequency> conventionalFrequenciesById(int subCategoryId,
                                                                                          Collection<Integer> ids)
        throws RadioReferenceDirectoryException
    {
        validateId(subCategoryId);
        LinkedHashSet<Integer> selectedIds = validatedIds(ids);
        Map<Integer,RadioReferenceGateway.ConventionalFrequency> indexed = new LinkedHashMap<>();

        loadConventionalFrequencies(subCategoryId).stream()
            .filter(frequency -> frequency.subCategoryId() == subCategoryId)
            .filter(frequency -> selectedIds.contains(frequency.id()))
            .forEach(frequency -> indexed.putIfAbsent(frequency.id(), frequency));

        if(indexed.size() != selectedIds.size())
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        return selectedIds.stream().map(indexed::get).toList();
    }

    private List<RadioReferenceGateway.RemoteTalkgroup> loadTalkgroups(int systemId)
        throws RadioReferenceDirectoryException
    {
        List<RadioReferenceGateway.RemoteTalkgroup> source = invokePremium(gateway -> gateway.talkgroups(systemId),
            DEFAULT_DETAIL_REQUEST_DEADLINE.toNanos());
        enforceImportBound(source == null ? 0 : source.size());
        return source == null ? List.of() : source.stream().filter(Objects::nonNull).toList();
    }

    private List<RadioReferenceGateway.ConventionalFrequency> loadConventionalFrequencies(int subCategoryId)
        throws RadioReferenceDirectoryException
    {
        List<RadioReferenceGateway.ConventionalFrequency> source = invokePremium(
            gateway -> gateway.subcategoryFrequencies(subCategoryId));
        enforceImportBound(source == null ? 0 : source.size());
        return source == null ? List.of() : source.stream().filter(Objects::nonNull).toList();
    }

    private static LinkedHashSet<Integer> validatedIds(Collection<Integer> ids)
        throws RadioReferenceDirectoryException
    {
        if(ids == null || ids.isEmpty() || ids.size() > MAXIMUM_REMOTE_ITEMS_SCANNED)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        LinkedHashSet<Integer> selectedIds = new LinkedHashSet<>();

        for(Integer id: ids)
        {
            if(id == null || id <= 0 || !selectedIds.add(id))
            {
                throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
            }
        }

        return selectedIds;
    }

    private static void enforceImportBound(int size) throws RadioReferenceDirectoryException
    {
        if(size > MAXIMUM_REMOTE_ITEMS_SCANNED)
        {
            throw tooLarge();
        }
    }

    private FrequencyDetailSnapshot frequencyDetailSnapshot(RadioReferenceGateway gateway, Integer systemId,
                                                             int subCategoryId, int agencyId, int countyId)
    {
        List<RadioReferenceGateway.Mode> modes = systemId == null ? optionalModes(gateway) : List.of();
        List<RadioReferenceGateway.Site> sites = systemId == null ? List.of() : optionalSites(gateway, systemId);
        List<RadioReferenceGateway.FrequencyCategory> categories = List.of();

        if(systemId == null && subCategoryId > 0)
        {
            categories = agencyId > 0 ? optionalAgencyCategories(gateway, agencyId) :
                optionalCountyCategories(gateway, countyId);
        }

        return new FrequencyDetailSnapshot(modes, sites, categories);
    }

    private static List<RadioReferenceGateway.Mode> optionalModes(RadioReferenceGateway gateway)
    {
        try
        {
            List<RadioReferenceGateway.Mode> modes = gateway.modes();
            return modes == null ? List.of() : modes;
        }
        catch(RadioReferenceGatewayException exception)
        {
            return List.of();
        }
    }

    private static List<RadioReferenceGateway.Site> optionalSites(RadioReferenceGateway gateway, int systemId)
    {
        try
        {
            List<RadioReferenceGateway.Site> sites = gateway.sites(systemId);
            return sites == null ? List.of() : sites;
        }
        catch(RadioReferenceGatewayException exception)
        {
            return List.of();
        }
    }

    private static List<RadioReferenceGateway.FrequencyCategory> optionalAgencyCategories(
        RadioReferenceGateway gateway, int agencyId)
    {
        try
        {
            List<RadioReferenceGateway.FrequencyCategory> categories =
                gateway.agencyFrequencyCategories(agencyId);
            return categories == null ? List.of() : categories;
        }
        catch(RadioReferenceGatewayException exception)
        {
            return List.of();
        }
    }

    private static List<RadioReferenceGateway.FrequencyCategory> optionalCountyCategories(
        RadioReferenceGateway gateway, int countyId)
    {
        try
        {
            List<RadioReferenceGateway.FrequencyCategory> categories = countyId > 0 ?
                gateway.countyFrequencyCategories(countyId) : List.of();
            return categories == null ? List.of() : categories;
        }
        catch(RadioReferenceGatewayException exception)
        {
            return List.of();
        }
    }

    private static List<SiteMatch> matchingSites(List<RadioReferenceGateway.Site> sites, long frequencyHz)
    {
        List<SiteMatch> matches = new ArrayList<>();

        if(sites != null)
        {
            for(RadioReferenceGateway.Site site: sites)
            {
                if(site != null)
                {
                    for(RadioReferenceGateway.SiteChannel channel: site.channels())
                    {
                        if(channel != null && Math.round(channel.frequencyMHz() * 1_000_000.0) == frequencyHz)
                        {
                            matches.add(new SiteMatch(site.id(), site.number(), text(site.name()), site.countyId(),
                                channel));
                        }
                    }
                }
            }
        }

        return matches;
    }

    private static SiteMatch selectSiteMatch(List<SiteMatch> matches, int siteNumber, int countyId)
    {
        List<SiteMatch> candidates = matches == null ? List.of() : matches;

        if(siteNumber > 0)
        {
            List<SiteMatch> numbered = candidates.stream()
                .filter(match -> match.siteNumber() != null && match.siteNumber().intValue() == siteNumber).toList();

            if(numbered.isEmpty())
            {
                return null;
            }

            candidates = numbered;
        }

        if(countyId > 0)
        {
            List<SiteMatch> countyMatches = candidates.stream()
                .filter(match -> match.countyId() == countyId).toList();

            if(countyMatches.isEmpty())
            {
                return null;
            }

            candidates = countyMatches;
        }

        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static SiteHint trunkedSiteHint(String rawDescription, String rawSystemName)
    {
        String description = text(rawDescription);
        String systemName = text(rawSystemName);
        String candidate = description;

        if(!systemName.isBlank() && description.regionMatches(true, 0, systemName, 0, systemName.length()))
        {
            candidate = description.substring(systemName.length()).strip();
        }

        Matcher matcher = TRUNKED_SITE_DESCRIPTION.matcher(candidate);

        if(matcher.find())
        {
            try
            {
                int siteNumber = Integer.parseInt(matcher.group(1));
                return new SiteHint(siteNumber, text(matcher.group(2)));
            }
            catch(NumberFormatException exception)
            {
                //Leave the result unmatched rather than guessing at a site identity.
            }
        }

        return SiteHint.NONE;
    }

    /**
     * State frequency results carry their own system description and site alpha tag.  Some valid trunked systems are
     * absent from the compact state directory, so retain those result fields as a no-network fallback instead of
     * leaving the system blank or forcing an automatic detail request.
     */
    private static TrunkedResultHint trunkedResultHint(String rawDescription, String rawAlpha,
                                                        String rawDirectorySystemName)
    {
        String description = text(rawDescription);
        String alpha = text(rawAlpha);
        String systemName = text(rawDirectorySystemName);
        SiteHint siteHint = trunkedSiteHint(description, systemName);

        if(systemName.isBlank())
        {
            Matcher matcher = TRUNKED_SITE_DESCRIPTION.matcher(description);

            if(matcher.find())
            {
                systemName = description.substring(0, matcher.start()).strip();
            }
            else
            {
                systemName = description;
            }
        }

        if(siteHint == SiteHint.NONE)
        {
            siteHint = trunkedSiteHint(alpha, "");
        }

        if(siteHint == SiteHint.NONE && !alpha.isBlank() && !alpha.equalsIgnoreCase(systemName))
        {
            siteHint = new SiteHint(null, alpha);
        }

        return new TrunkedResultHint(systemName, siteHint.siteNumber(), siteHint.siteName());
    }

    private static String radioReferenceSiteUrl(int siteId)
    {
        return siteId > 0 ? "https://www.radioreference.com/db/site/" + siteId : "";
    }

    private static String resolveMode(String rawMode, Map<Integer,String> modes)
    {
        String value = text(rawMode);

        try
        {
            return modes.getOrDefault(Integer.parseInt(value), "Mode " + value);
        }
        catch(NumberFormatException exception)
        {
            return value;
        }
    }

    private static String channelUse(MatchType matchType, RadioReferenceGateway.SiteChannel channel)
    {
        if(matchType == MatchType.CONVENTIONAL)
        {
            return "Conventional";
        }
        else if(channel == null)
        {
            return "Trunked";
        }
        else if(channel.primaryControl())
        {
            return "Control";
        }
        else if(channel.alternateControl())
        {
            return "Alternate control";
        }

        return "Voice / data";
    }

    private void setFailedLoginStatus(long generation, AccountStatus status)
    {
        synchronized(mSessionLock)
        {
            if(!mClosed && generation == mSessionGeneration)
            {
                mAccountStatus = status;
            }
        }
    }

    private AccountStatus classify(RadioReferenceGateway.Account account)
    {
        if(account == null || account.expiration() == null || account.expiration().isBlank())
        {
            return AccountStatus.unavailable();
        }

        String userName = text(account.userName());
        String expiration = account.expiration().strip();

        try
        {
            LocalDate expirationDate = LocalDate.parse(expiration, EXPIRATION_FORMAT);
            LocalDate graceBoundary = LocalDate.now(mClock).minusDays(2);
            return expirationDate.isAfter(graceBoundary) ?
                AccountStatus.validPremium(userName, expiration) :
                AccountStatus.expiredPremium(userName, expiration);
        }
        catch(DateTimeParseException exception)
        {
            // RadioReference uses text such as "Never - Feed Provider" for premium accounts without an expiry date.
            return AccountStatus.validPremium(userName, expiration);
        }
    }

    private <T> T invokePremium(GatewayRequest<T> request) throws RadioReferenceDirectoryException
    {
        return invokePremium(request, mRequestDeadlineNanos);
    }

    private <T> T invokePremium(GatewayRequest<T> request, long requestDeadlineNanos)
        throws RadioReferenceDirectoryException
    {
        Session session;

        synchronized(mSessionLock)
        {
            ensureOpen();
            session = mSession;

            if(session == null)
            {
                throw new RadioReferenceDirectoryException(
                    RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED);
            }

            if(session.status().state() != AccountState.VALID_PREMIUM)
            {
                throw new RadioReferenceDirectoryException(
                    RadioReferenceDirectoryException.Code.PREMIUM_REQUIRED);
            }
        }

        try
        {
            T result = invoke(() -> request.execute(session.gateway()), requestDeadlineNanos);

            synchronized(mSessionLock)
            {
                if(mClosed)
                {
                    throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.CLOSED);
                }

                if(mSession != session)
                {
                    throw new RadioReferenceDirectoryException(
                        RadioReferenceDirectoryException.Code.NOT_AUTHENTICATED);
                }
            }

            return result;
        }
        catch(RadioReferenceDirectoryException exception)
        {
            if(exception.code() == RadioReferenceDirectoryException.Code.INVALID_CREDENTIALS)
            {
                invalidate(session);
            }

            throw exception;
        }
    }

    private void invalidate(Session session)
    {
        boolean close = false;

        synchronized(mSessionLock)
        {
            if(mSession == session)
            {
                mSession = null;
                ++mSessionGeneration;
                mAccountStatus = AccountStatus.invalidCredentials();
                close = true;
            }
        }

        if(close)
        {
            close(session);
        }
    }

    private <T> T invoke(RemoteRequest<T> request) throws RadioReferenceDirectoryException
    {
        return invoke(request, mRequestDeadlineNanos);
    }

    private <T> T invoke(RemoteRequest<T> request, long requestDeadlineNanos)
        throws RadioReferenceDirectoryException
    {
        if(mClosed)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.CLOSED);
        }

        Future<T> future;

        try
        {
            future = mExecutor.submit(request::execute);
            mRequests.add(future);

            if(mClosed)
            {
                cancel(future);
            }
        }
        catch(RejectedExecutionException exception)
        {
            throw new RadioReferenceDirectoryException(mClosed ?
                RadioReferenceDirectoryException.Code.CLOSED :
                RadioReferenceDirectoryException.Code.BUSY);
        }

        try
        {
            return future.get(requestDeadlineNanos, TimeUnit.NANOSECONDS);
        }
        catch(TimeoutException exception)
        {
            cancel(future);
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.TIMEOUT);
        }
        catch(InterruptedException exception)
        {
            cancel(future);
            Thread.currentThread().interrupt();
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INTERRUPTED);
        }
        catch(CancellationException exception)
        {
            throw new RadioReferenceDirectoryException(mClosed ?
                RadioReferenceDirectoryException.Code.CLOSED :
                RadioReferenceDirectoryException.Code.INTERRUPTED);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RadioReferenceGatewayException gatewayException)
            {
                throw new RadioReferenceDirectoryException(directoryCode(gatewayException.kind()), gatewayException);
            }

            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.UNAVAILABLE);
        }
        finally
        {
            mRequests.remove(future);
        }
    }

    private void cancel(Future<?> future)
    {
        future.cancel(true);

        if(future instanceof Runnable runnable)
        {
            mExecutor.remove(runnable);
        }

        mExecutor.purge();
    }

    private static <T> T required(T value) throws RadioReferenceGatewayException
    {
        if(value == null)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }

        return value;
    }

    private static RadioReferenceDirectoryException.Code directoryCode(RadioReferenceGatewayException.Kind kind)
    {
        return switch(kind)
        {
            case INVALID_CREDENTIALS -> RadioReferenceDirectoryException.Code.INVALID_CREDENTIALS;
            case INVALID_LOCATION -> RadioReferenceDirectoryException.Code.INVALID_REQUEST;
            case RESULT_SET_TOO_LARGE -> RadioReferenceDirectoryException.Code.RESULT_SET_TOO_LARGE;
            case INSECURE_TRANSPORT -> RadioReferenceDirectoryException.Code.INSECURE_TRANSPORT;
            case TIMEOUT -> RadioReferenceDirectoryException.Code.TIMEOUT;
            case INTERRUPTED -> RadioReferenceDirectoryException.Code.INTERRUPTED;
            case HTTP_ERROR, REQUEST_ENCODING, INVALID_RESPONSE, UNAVAILABLE ->
                RadioReferenceDirectoryException.Code.UNAVAILABLE;
        };
    }

    private static LocationSnapshot locationSnapshot(RadioReferenceGateway gateway, LocationSelection selection)
        throws RadioReferenceGatewayException
    {
        RadioReferenceGateway.CountryDirectory country =
            verifiedCountry(gateway.country(selection.countryId()), selection.countryId());
        RadioReferenceGateway.StateDirectory state = null;
        RadioReferenceGateway.CountyDirectory county = null;

        if(selection.stateId() != null)
        {
            requireState(country.states(), selection.stateId());
            state = verifiedState(gateway.state(selection.stateId()), selection.stateId());
        }

        if(selection.countyId() != null)
        {
            requireCounty(state.counties(), selection.countyId());
            county = verifiedCounty(gateway.county(selection.countyId()), selection.countyId());
        }

        return new LocationSnapshot(country, state, county);
    }

    private static RadioReferenceGateway.CountryDirectory verifiedCountry(
        RadioReferenceGateway.CountryDirectory directory, int expectedId) throws RadioReferenceGatewayException
    {
        directory = required(directory);

        if(directory.country() == null || directory.country().id() != expectedId)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
        }

        return directory;
    }

    private static RadioReferenceGateway.StateDirectory verifiedState(
        RadioReferenceGateway.StateDirectory directory, int expectedId) throws RadioReferenceGatewayException
    {
        directory = required(directory);

        if(directory.state() == null || directory.state().id() != expectedId)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
        }

        return directory;
    }

    private static RadioReferenceGateway.CountyDirectory verifiedCounty(
        RadioReferenceGateway.CountyDirectory directory, int expectedId) throws RadioReferenceGatewayException
    {
        directory = required(directory);

        if(directory.county() == null || directory.county().id() != expectedId)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
        }

        return directory;
    }

    private static void requireState(List<RadioReferenceGateway.State> states, int expectedId)
        throws RadioReferenceGatewayException
    {
        int scanned = 0;

        for(RadioReferenceGateway.State state: states)
        {
            if(scanned++ >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            if(state != null && state.id() == expectedId)
            {
                return;
            }
        }

        throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
    }

    private static void requireCounty(List<RadioReferenceGateway.County> counties, int expectedId)
        throws RadioReferenceGatewayException
    {
        int scanned = 0;

        for(RadioReferenceGateway.County county: counties)
        {
            if(scanned++ >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            if(county != null && county.id() == expectedId)
            {
                return;
            }
        }

        throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_LOCATION);
    }

    private static BoundedPage<DirectoryOption> optionPage(List<DirectoryOption> options, int offset, int limit)
    {
        options.sort(Comparator.comparing(DirectoryOption::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(DirectoryOption::id));
        return page(options, offset, limit);
    }

    private static <T> BoundedPage<T> page(List<T> items, int offset, int limit)
    {
        int totalItems = items.size();
        int start = Math.min(offset, totalItems);
        int end = Math.min(start + limit, totalItems);
        Integer nextOffset = end < totalItems ? end : null;
        return new BoundedPage<>(items.subList(start, end), offset, nextOffset, totalItems);
    }

    private static RadioReferenceDirectoryException tooLarge()
    {
        return new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.RESULT_SET_TOO_LARGE);
    }

    private static String normalizedCredential(String value, int maximumLength)
    {
        if(value == null)
        {
            return null;
        }

        String normalized = value.strip();
        return normalized.isEmpty() || normalized.length() > maximumLength ? null : normalized;
    }

    private static String normalizedSearch(String value) throws RadioReferenceDirectoryException
    {
        if(value == null)
        {
            return "";
        }

        String normalized = value.strip();

        if(normalized.length() > MAXIMUM_QUERY_LENGTH)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String search, String... values)
    {
        if(search.isEmpty())
        {
            return true;
        }

        for(String value: values)
        {
            if(value != null && value.toLowerCase(Locale.ROOT).contains(search))
            {
                return true;
            }
        }

        return false;
    }

    private static void validateSelection(LocationSelection selection) throws RadioReferenceDirectoryException
    {
        if(selection == null || selection.countryId() <= 0 ||
            selection.stateId() != null && selection.stateId() <= 0 ||
            selection.countyId() != null && selection.countyId() <= 0 ||
            selection.countyId() != null && selection.stateId() == null)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }
    }

    private static void validateId(int id) throws RadioReferenceDirectoryException
    {
        if(id <= 0)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }
    }

    private static void validatePage(int offset, int limit) throws RadioReferenceDirectoryException
    {
        if(offset < 0 || offset > MAXIMUM_REMOTE_ITEMS_SCANNED ||
            limit <= 0 || limit > MAXIMUM_RESULT_LIMIT)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.INVALID_REQUEST);
        }
    }

    private static int positive(int value, String name)
    {
        if(value <= 0)
        {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return value;
    }

    private static long positiveNanos(Duration value, String name)
    {
        Objects.requireNonNull(value);

        if(value.isZero() || value.isNegative())
        {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return value.toNanos();
    }

    private static String text(String value)
    {
        return value == null ? "" : value.strip();
    }

    private static Double positive(double value)
    {
        return Double.isFinite(value) && value > 0 ? value : null;
    }

    private static String radioReferenceUrl(Integer systemId, int subCategoryId, int agencyId, int countyId)
    {
        if(systemId != null && systemId > 0)
        {
            return "https://www.radioreference.com/db/sid/" + systemId;
        }
        else if(subCategoryId > 0)
        {
            return "https://www.radioreference.com/db/subcat/" + subCategoryId;
        }
        else if(agencyId > 0)
        {
            return "https://www.radioreference.com/db/aid/" + agencyId;
        }
        else if(countyId > 0)
        {
            return "https://www.radioreference.com/db/browse/ctid/" + countyId;
        }

        return "https://www.radioreference.com/db/";
    }

    private void ensureOpen() throws RadioReferenceDirectoryException
    {
        if(mClosed)
        {
            throw new RadioReferenceDirectoryException(RadioReferenceDirectoryException.Code.CLOSED);
        }
    }

    private static void close(Session session)
    {
        if(session != null)
        {
            close(session.gateway());
        }
    }

    private static void close(RadioReferenceGateway gateway)
    {
        if(gateway != null)
        {
            try
            {
                gateway.close();
            }
            catch(RuntimeException exception)
            {
                // Closing is best-effort and must never expose upstream details.
            }
        }
    }

    /**
     * Stops accepting requests, cancels every known caller future, interrupts remote workers and waits no longer than
     * the configured shutdown interval.  An upstream call that ignores interruption can remain active afterward; its
     * worker is a daemon and {@link RuntimeStatus#remoteWorkerTerminated()} remains false until it actually exits.
     */
    @Override
    public void close()
    {
        Session previous;

        synchronized(mSessionLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            previous = mSession;
            mSession = null;
            ++mSessionGeneration;
            mAccountStatus = AccountStatus.closed();
        }

        close(previous);

        for(Future<?> request: mRequests)
        {
            request.cancel(true);
        }

        List<Runnable> neverStarted = mExecutor.shutdownNow();

        for(Runnable runnable: neverStarted)
        {
            if(runnable instanceof Future<?> future)
            {
                future.cancel(false);
            }
        }

        try
        {
            mExecutor.awaitTermination(mShutdownWaitNanos, TimeUnit.NANOSECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    private record Session(RadioReferenceGateway gateway, AccountStatus status)
    {
    }

    private record LocationSnapshot(RadioReferenceGateway.CountryDirectory country,
                                    RadioReferenceGateway.StateDirectory state,
                                    RadioReferenceGateway.CountyDirectory county)
    {
    }

    private record FrequencySnapshot(RadioReferenceGateway.StateDirectory state,
                                     List<RadioReferenceGateway.FrequencyResult> results)
    {
    }

    private record FrequencyDetailSnapshot(List<RadioReferenceGateway.Mode> modes,
                                           List<RadioReferenceGateway.Site> sites,
                                           List<RadioReferenceGateway.FrequencyCategory> categories)
    {
        private FrequencyDetailSnapshot
        {
            modes = modes == null ? List.of() : List.copyOf(modes);
            sites = sites == null ? List.of() : List.copyOf(sites);
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
    }

    private record SiteMatch(Integer siteId, Integer siteNumber, String siteName, int countyId,
                             RadioReferenceGateway.SiteChannel channel)
    {
    }

    private record SiteHint(Integer siteNumber, String siteName)
    {
        private static final SiteHint NONE = new SiteHint(null, "");
    }

    private record TrunkedResultHint(String systemName, Integer siteNumber, String siteName)
    {
        private static final TrunkedResultHint NONE = new TrunkedResultHint("", null, "");
    }

    private record EntryKey(EntryScope scope, EntryType type, RadioReferenceGateway.DetailKind kind, int id)
    {
    }

    private final class EntryAccumulator
    {
        private final String mSearch;
        private final EntryGroup mGroup;
        private final ScopeFilter mScopeFilter;
        private final Map<Integer,String> mSystemTypes;
        private final Map<EntryKey,DirectoryEntry> mEntries = new LinkedHashMap<>();
        private int mScanned;
        private boolean mOverflowed;

        private EntryAccumulator(String search, EntryGroup group, ScopeFilter scopeFilter,
                                 Map<Integer,String> systemTypes)
        {
            mSearch = search;
            mGroup = group;
            mScopeFilter = scopeFilter;
            mSystemTypes = systemTypes;
        }

        private void addSystems(List<RadioReferenceGateway.TrunkedSystem> systems, EntryScope scope)
        {
            if(mGroup == EntryGroup.CONVENTIONAL_AGENCIES || !accepts(scope))
            {
                return;
            }

            for(RadioReferenceGateway.TrunkedSystem system: systems)
            {
                if(!scan())
                {
                    return;
                }

                if(system != null && system.id() > 0 && matches(mSearch, system.name(), system.city()))
                {
                    String systemType = mSystemTypes.getOrDefault(system.typeId(), "");
                    // Only list trunked systems for which the import workflow can create a decoder.
                    if(!systemType.equalsIgnoreCase("Project 25") && !systemType.equalsIgnoreCase("DMR") &&
                        !systemType.equalsIgnoreCase("NXDN"))
                    {
                        continue;
                    }
                    RadioReferenceGateway.DetailReference detail = new RadioReferenceGateway.DetailReference(
                        RadioReferenceGateway.DetailKind.TRUNKED_SYSTEM, system.id());
                    put(new DirectoryEntry(text(system.name()), text(system.city()), EntryType.TRUNKED_SYSTEM,
                        scope, detail, system.typeId(), system.flavorId(), system.voiceId(),
                        systemType, system.lastUpdatedEpochMillis()));
                }
            }
        }

        private void addAgencies(List<RadioReferenceGateway.Agency> agencies, EntryScope scope)
        {
            if(mGroup == EntryGroup.TRUNKED_SYSTEMS || !accepts(scope))
            {
                return;
            }

            for(RadioReferenceGateway.Agency agency: agencies)
            {
                if(!scan())
                {
                    return;
                }

                if(agency != null && agency.id() > 0 && matches(mSearch, agency.name()))
                {
                    RadioReferenceGateway.DetailReference detail = new RadioReferenceGateway.DetailReference(
                        RadioReferenceGateway.DetailKind.AGENCY, agency.id());
                    put(new DirectoryEntry(text(agency.name()), "", EntryType.CONVENTIONAL_AGENCY, scope,
                        detail, agency.type(), 0, 0, "", 0));
                }
            }
        }

        private void addCounty(RadioReferenceGateway.County county)
        {
            if(mGroup == EntryGroup.TRUNKED_SYSTEMS || !accepts(EntryScope.COUNTY) || !scan() ||
                county == null || county.id() <= 0)
            {
                return;
            }

            String name = text(county.name());
            String allName = name.toLowerCase(Locale.ROOT).endsWith(" county") ?
                name + " (All)" : name + " County (All)";

            if(matches(mSearch, allName, county.header()))
            {
                RadioReferenceGateway.DetailReference detail = new RadioReferenceGateway.DetailReference(
                    RadioReferenceGateway.DetailKind.COUNTY, county.id());
                put(new DirectoryEntry(allName, "All county conventional frequencies",
                    EntryType.CONVENTIONAL_AGENCY, EntryScope.COUNTY, detail, 0, 0, 0, "", 0));
            }
        }

        private boolean scan()
        {
            if(mScanned >= MAXIMUM_REMOTE_ITEMS_SCANNED)
            {
                mOverflowed = true;
                return false;
            }

            ++mScanned;
            return true;
        }

        private boolean accepts(EntryScope scope)
        {
            return mScopeFilter == ScopeFilter.ALL || mScopeFilter.name().equals(scope.name());
        }

        private void put(DirectoryEntry entry)
        {
            EntryKey key = new EntryKey(entry.scope(), entry.type(), entry.detail().kind(), entry.detail().id());
            mEntries.putIfAbsent(key, entry);
        }

        private Map<EntryKey,DirectoryEntry> entries()
        {
            return mEntries;
        }

        private boolean overflowed()
        {
            return mOverflowed;
        }
    }

    private static final class LoginAttempt
    {
        private RadioReferenceGateway mGateway;
        private boolean mAbandoned;
        private boolean mClaimed;

        private synchronized void track(RadioReferenceGateway gateway)
        {
            if(mAbandoned)
            {
                close(gateway);
            }
            else
            {
                mGateway = gateway;
            }
        }

        private synchronized RadioReferenceGateway claim()
        {
            if(mAbandoned || mClaimed)
            {
                return null;
            }

            mClaimed = true;
            RadioReferenceGateway gateway = mGateway;
            mGateway = null;
            return gateway;
        }

        private synchronized void abandon()
        {
            mAbandoned = true;
            close(mGateway);
            mGateway = null;
        }
    }

    @FunctionalInterface
    private interface RemoteRequest<T>
    {
        T execute() throws RadioReferenceGatewayException;
    }

    @FunctionalInterface
    private interface GatewayRequest<T>
    {
        T execute(RadioReferenceGateway gateway) throws RadioReferenceGatewayException;
    }

    public enum AccountState
    {
        SIGNED_OUT,
        CHECKING,
        VALID_PREMIUM,
        EXPIRED_PREMIUM,
        INVALID_CREDENTIALS,
        SECURE_TRANSPORT_REQUIRED,
        UNAVAILABLE,
        CLOSED
    }

    public record AccountStatus(AccountState state, String userName, String accountExpires)
    {
        public AccountStatus
        {
            state = Objects.requireNonNull(state);
            userName = text(userName);
            accountExpires = text(accountExpires);
        }

        private static AccountStatus signedOut()
        {
            return new AccountStatus(AccountState.SIGNED_OUT, "", "");
        }

        private static AccountStatus checking()
        {
            return new AccountStatus(AccountState.CHECKING, "", "");
        }

        private static AccountStatus validPremium(String userName, String accountExpires)
        {
            return new AccountStatus(AccountState.VALID_PREMIUM, userName, accountExpires);
        }

        private static AccountStatus expiredPremium(String userName, String accountExpires)
        {
            return new AccountStatus(AccountState.EXPIRED_PREMIUM, userName, accountExpires);
        }

        private static AccountStatus invalidCredentials()
        {
            return new AccountStatus(AccountState.INVALID_CREDENTIALS, "", "");
        }

        private static AccountStatus unavailable()
        {
            return new AccountStatus(AccountState.UNAVAILABLE, "", "");
        }

        private static AccountStatus secureTransportRequired()
        {
            return new AccountStatus(AccountState.SECURE_TRANSPORT_REQUIRED, "", "");
        }

        private static AccountStatus closed()
        {
            return new AccountStatus(AccountState.CLOSED, "", "");
        }

        public boolean authenticated()
        {
            return state == AccountState.VALID_PREMIUM || state == AccountState.EXPIRED_PREMIUM;
        }

        public boolean available()
        {
            return authenticated();
        }

        public boolean premium()
        {
            return state == AccountState.VALID_PREMIUM;
        }
    }

    public record RuntimeStatus(int activeRequests, int waitingRequests, int maximumRemoteConcurrency,
                                int maximumWaitingRequests, boolean closed, boolean remoteWorkerTerminated)
    {
    }

    public record DirectoryOption(int id, String name, String abbreviation)
    {
    }

    public record DiscoverySystem(RadioReferenceGateway.TrunkedSystemDetails system,
                                  List<RadioReferenceGateway.TrunkedSiteDetails> sites)
    {
        public DiscoverySystem
        {
            system = Objects.requireNonNull(system);
            sites = List.copyOf(sites);
        }
    }

    public record BoundedPage<T>(List<T> items, int offset, Integer nextOffset, int totalItems)
    {
        public BoundedPage
        {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public boolean hasMore()
        {
            return nextOffset != null;
        }

        /**
         * Compatibility name for first-page callers.  A true value means another page is available, never that data
         * has been discarded.
         */
        public boolean truncated()
        {
            return hasMore();
        }
    }

    public record LocationSelection(int countryId, Integer stateId, Integer countyId)
    {
    }

    public record FrequencyMatch(double outputMhz, Double inputMhz, String tone, String callsign,
                                 String description, String alphaTag, String category, String subCategory,
                                 String modeCode, String modeName, String classification, String colorCode,
                                 String talkgroup, String slot, List<String> tags, MatchType matchType,
                                 String channelUse, int stateId,
                                 String stateName, int countyId, String countyName, int agencyId, String agencyName,
                                 int subCategoryId, Integer systemId, String systemName, Integer siteId,
                                 Integer siteNumber, String siteName, String radioReferenceUrl)
    {
        public FrequencyMatch
        {
            tone = text(tone);
            callsign = text(callsign);
            description = text(description);
            alphaTag = text(alphaTag);
            category = text(category);
            subCategory = text(subCategory);
            modeCode = text(modeCode);
            modeName = text(modeName);
            classification = text(classification);
            colorCode = text(colorCode);
            talkgroup = text(talkgroup);
            slot = text(slot);
            tags = tags == null ? List.of() : List.copyOf(tags);
            stateName = text(stateName);
            countyName = text(countyName);
            agencyName = text(agencyName);
            systemName = text(systemName);
            siteName = text(siteName);
            radioReferenceUrl = text(radioReferenceUrl);
        }
    }

    public record FrequencyDetails(String modeName, String category, String subCategory,
                                   FrequencySiteDetail site)
    {
        public FrequencyDetails
        {
            modeName = text(modeName);
            category = text(category);
            subCategory = text(subCategory);
        }
    }

    public record FrequencySiteDetail(Integer siteId, Integer siteNumber, String siteName, String channelUse,
                                      String radioReferenceUrl)
    {
        public FrequencySiteDetail
        {
            siteName = text(siteName);
            channelUse = text(channelUse);
            radioReferenceUrl = text(radioReferenceUrl);
        }
    }

    public enum MatchType
    {
        CONVENTIONAL,
        TRUNKED
    }

    public record DirectoryEntry(String name, String secondary, EntryType type, EntryScope scope,
                                 RadioReferenceGateway.DetailReference detail, int nativeTypeId,
                                 int nativeFlavorId, int nativeVoiceId, String systemType,
                                 long lastUpdatedEpochMillis)
    {
    }

    public enum EntryType
    {
        TRUNKED_SYSTEM,
        CONVENTIONAL_AGENCY
    }

    public enum EntryScope
    {
        NATIONAL,
        STATE,
        COUNTY
    }

    public enum EntryGroup
    {
        ALL,
        TRUNKED_SYSTEMS,
        CONVENTIONAL_AGENCIES
    }

    public enum ScopeFilter
    {
        ALL,
        NATIONAL,
        STATE,
        COUNTY
    }
}

/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.manager.TunerSettingCatalog;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One bounded, temporary P25 search. All tuning, FFT observation, probing and saves run off receiver callbacks. */
public final class SpectrumSearchService implements AutoCloseable
{
    static final int MAX_WINDOWS = 128;
    static final int MAX_CANDIDATES = 32;
    static final long MAX_TOTAL_HZ = 150_000_000;
    static final long MAX_SCAN_MS = 900_000;
    static final long RESULT_AGE_MS = 900_000;
    static final long IDLE_MS = 60_000;
    private final Object mLock = new Object();
    private final Channels mChannels;
    private final HardwareFactory mHardware;
    private final ProbeCheck mCheck;
    private final LongSupplier mClock;
    private final Supplier<Catalog> mCatalog;
    private final ScheduledExecutorService mExpiry;
    private Job mJob;
    private boolean mClosed;

    public SpectrumSearchService(ChannelAdministrationService channels, TunerAdministrationService tuners,
                                 TunerDiagnosticService diagnostics, TunerSettingsService settings,
                                 StatsWebDatabase database)
    {
        this(new Channels()
        {
            public long revision() { return channels.currentRevision(); }
            public KnownChannel known(long frequency)
            {
                var configured = channels.discoveryFrequencyMatches(frequency);
                if(!configured.isEmpty()) return new KnownChannel(configured.getFirst().configurationId(),
                    configured.getFirst().name());
                var historical = database.discoveryFrequencyOwners(frequency);
                return historical.stream().filter(row -> row.get("configuration_id") instanceof String id && !id.isBlank())
                    .map(row -> new KnownChannel((String)row.get("configuration_id"),
                        row.get("name") instanceof String name ? name : "Saved channel")).findFirst().orElse(null);
            }
            public KnownChannel knownSite(P25SiteIdentity identity)
            {
                var match = channels.discoverySiteMatch(identity);
                return match != null ? new KnownChannel(match.configurationId(), match.name()) : null;
            }
            public ChannelAdministrationService.DiscoveryReview review(long frequency, String preferred,
                                                                       P25SiteIdentity identity, String modulation)
            {
                return channels.discoveryReview("p25-phase1", frequency, preferred, identity, modulation);
            }
            public ChannelAdministrationService.DiscoveryCreated create(ChannelDefinition definition,
                P25SiteIdentity identity, String aliasName, long revision, boolean autoStart)
            {
                return channels.createDiscovered(definition, identity, aliasName, revision, autoStart);
            }
            public ChannelAdministrationService.LifecycleResult start(String id, String tunerId, Tuner runtime, boolean handoff)
            {
                var selected = tuners.find(tunerId);
                if(selected == null || selected.getTuner() != runtime)
                    throw new IllegalStateException("The selected receiver changed");
                if(handoff) return channels.startAtCurrentCenter(id, selected);
                try(var hold = settings.holdForProbe(runtime))
                {
                    return channels.startAtCurrentCenter(id, selected);
                }
            }
        }, new SpectrumSearchHardware(tuners, diagnostics, settings)::open, SpectrumSearchService::check,
            System::currentTimeMillis, () -> catalog(tuners));
    }

    SpectrumSearchService(Channels channels, HardwareFactory hardware, ProbeCheck check,
                          LongSupplier clock, Supplier<Catalog> catalog)
    {
        mChannels = Objects.requireNonNull(channels);
        mHardware = Objects.requireNonNull(hardware);
        mCheck = Objects.requireNonNull(check);
        mClock = Objects.requireNonNull(clock);
        mCatalog = Objects.requireNonNull(catalog);
        mExpiry = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "spectrum-search-expiry");
            thread.setDaemon(true);
            return thread;
        });
        mExpiry.scheduleWithFixedDelay(this::expire, 5, 5, TimeUnit.SECONDS);
    }

    public Catalog catalog() { return mCatalog.get(); }

    public Snapshot open(String tunerId, String browseLeaseId, List<Range> ranges, long dwellMs)
    {
        validateRanges(ranges);
        if(tunerId == null || tunerId.isBlank() || browseLeaseId == null || browseLeaseId.isBlank())
            throw new IllegalArgumentException("Choose an idle receiver and begin Spectrum browsing");
        if(dwellMs < 750 || dwellMs > 5000) throw new IllegalArgumentException("Choose a dwell between 750 and 5000 ms");
        synchronized(mLock)
        {
            expireLocked();
            if(mClosed) throw new IllegalStateException("Signal search is unavailable");
            if(mJob != null) throw new IllegalStateException("Another signal search is already in progress");
            var lease = mHardware.open(tunerId, browseLeaseId);
            try
            {
                Job job = new Job(tunerId, lease, List.copyOf(ranges), dwellMs, mClock.getAsLong());
                job.windows = windows(ranges, lease.usableBandwidthHz(), lease.minimumFrequencyHz(),
                    lease.maximumFrequencyHz());
                job.revision = mChannels.revision();
                job.worker = new Thread(() -> scan(job), "spectrum-search");
                job.worker.setDaemon(true);
                job.worker.setPriority(Thread.MIN_PRIORITY);
                mJob = job;
                job.worker.start();
                return snapshot(job);
            }
            catch(RuntimeException exception) { lease.close(); throw exception; }
        }
    }

    public Snapshot status(String id)
    {
        synchronized(mLock)
        {
            Job job = requireJob(id);
            job.expiresAt = Math.min(job.maximumExpiresAt, mClock.getAsLong() + IDLE_MS);
            if(!job.handedOff && !job.commandBusy && "complete".equals(job.phase) && !job.lease.valid())
            {
                job.phase = "failed";
                job.reason = "The search receiver changed. Run the search again.";
                cancelLocked(job);
            }
            return snapshot(job);
        }
    }

    private void scan(Job job)
    {
        try
        {
            Map<Long,SpectrumPeakDetector.Peak> first = new LinkedHashMap<>();
            List<SpectrumPeakDetector.Peak> persistent = new ArrayList<>();
            for(int pass = 0; pass < 2; pass++)
                for(long center: job.windows)
                {
                    requireScanning(job);
                    job.lease.tune(center);
                    synchronized(mLock) { job.currentFrequency = center; }
                    for(var peak: job.lease.observe(job.dwellMs, job.cancelled::get))
                    {
                        if(!inRanges(peak.frequencyHz(), job.ranges)) continue;
                        if(pass == 0)
                        {
                            if(find(first.values().stream().toList(), peak.frequencyHz()) == null)
                            {
                                if(first.size() < MAX_CANDIDATES * 4) first.put(peak.frequencyHz(), peak);
                                else job.truncatedReason = "The search found more peaks than its limit. Choose fewer bands to check additional signals.";
                            }
                        }
                        else if(find(first.values().stream().toList(), peak.frequencyHz()) != null &&
                            find(persistent, peak.frequencyHz()) == null)
                        {
                            if(persistent.size() < MAX_CANDIDATES) persistent.add(peak);
                            else job.truncatedReason = "Only 32 persistent signals were checked. Choose fewer bands to check additional signals.";
                        }
                    }
                    synchronized(mLock) { job.completedWindows++; }
                }
            persistent.sort(Comparator.comparingDouble(SpectrumPeakDetector.Peak::powerDbfs).reversed());
            synchronized(mLock) { job.phase = "checking"; job.totalSignals = persistent.size(); }
            Set<P25SiteIdentity> sites = new LinkedHashSet<>();
            for(var peak: persistent)
            {
                requireScanning(job);
                long frequency = peak.frequencyHz();
                job.lease.tune(probeCenter(frequency, job.lease));
                synchronized(mLock) { job.currentFrequency = frequency; }
                P25DiscoveryProbe.Status evidence = mCheck.check(job.lease, frequency, job.cancelled::get);
                requireScanning(job);
                P25SiteIdentity identity = verifiedIdentity(evidence);
                synchronized(mLock) { job.checkedSignals++; }
                if(identity == null || !sites.add(identity)) continue;
                var mode = "C4FM".equals(evidence.selectedModulation()) ? evidence.c4fm() : evidence.cqpsk();
                double strength = evidence.signal() != null && evidence.signal().meanPowerDbfs() != null ?
                    evidence.signal().meanPowerDbfs() : peak.powerDbfs();
                Row row = new Row(frequency, strength, identity, evidence.selectedModulation(),
                    new Health(mode.qualityPct(), mode.validMessages(), mode.validControlMessages(),
                        mode.invalidControlMessages(), mClock.getAsLong()));
                row.known = mChannels.knownSite(identity);
                if(row.known == null) row.known = mChannels.known(frequency);
                synchronized(mLock) { job.rows.put(row.id, row); }
            }
            refreshGroups(job);
            synchronized(mLock)
            {
                requireScanning(job);
                job.phase = "complete";
                job.resultsPublished = true;
                job.currentFrequency = null;
                job.revision = mChannels.revision();
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            synchronized(mLock) { job.phase = "cancelled"; job.reason = "Search cancelled"; }
        }
        catch(RuntimeException exception)
        {
            synchronized(mLock)
            {
                job.phase = job.cancelled.get() ? "cancelled" : "failed";
                job.reason = exception instanceof SearchLimitException ||
                    exception instanceof SpectrumSearchHardware.ObservationException ? exception.getMessage() :
                    "The receiver could not finish searching. Check its availability and try again.";
            }
        }
        finally
        {
            if(!"complete".equals(job.phase) || job.cancelled.get()) closeLease(job);
        }
    }

    public Snapshot save(String id, SaveRequest request)
    {
        Job job = beginCommand(id);
        try
        {
            List<Row> selected = selected(job, request.candidates().stream().map(SaveCandidate::candidateId).toList());
            Map<String,AliasChoice> choices = new LinkedHashMap<>();
            for(var choice: request.aliasGroups())
                if(!job.groups.containsKey(choice.groupId()) || choices.putIfAbsent(choice.groupId(), choice) != null)
                    throw new IllegalArgumentException("Choose listening settings once for each selected system");
            long revision = mChannels.revision();
            if(request.revision() != revision)
                throw new ChannelAdministrationService.StaleRevisionException(request.revision(), revision);
            for(int index = 0; index < selected.size(); index++)
            {
                Row row = selected.get(index);
                if(row.saved != null) continue;
                if(job.cancelled.get()) break;
                row.saveError = null;
                SaveCandidate edit = request.candidates().get(index);
                String name = edit.name() != null && !edit.name().isBlank() ? edit.name().strip() : row.name();
                try
                {
                    if(mClock.getAsLong() - row.health.checkedAtMs() > RESULT_AGE_MS)
                        throw new IllegalStateException("This result is too old. Run the search again");
                    row.known = mChannels.knownSite(row.identity);
                    if(row.known == null) row.known = mChannels.known(row.frequency);
                    if(row.known != null)
                        throw new IllegalStateException("This frequency already belongs to a saved channel");
                    var review = mChannels.review(row.frequency, preferred(job), row.identity, row.modulation);
                    AliasChoice choice = choices.get(row.groupId());
                    long aliasId = job.aliasIds.getOrDefault(row.groupId(), choice != null ? choice.aliasListId() :
                        review.suggestedAliasListId() != null ? review.suggestedAliasListId() : 0L);
                    if(aliasId == 0 && !review.aliasLists().isEmpty())
                        throw new IllegalArgumentException("Choose the matching listening settings for this system");
                    String aliasName = choice != null && choice.newAliasListName() != null ? choice.newAliasListName() :
                        review.defaultNewAliasListName();
                    var template = review.template();
                    ChannelDefinition definition = new ChannelDefinition(null, "p25-phase1", template.system(),
                        template.site(), name, null, aliasId, template.source(), template.settings(),
                        List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
                    row.saved = mChannels.create(definition, row.identity, aliasName, revision, edit.autoStart());
                    row.savedName = name;
                    row.autoStart = edit.autoStart();
                    job.aliasIds.put(row.groupId(), row.saved.aliasListId());
                    revision = mChannels.revision();
                }
                catch(ChannelAdministrationService.StaleRevisionException exception)
                {
                    row.saveError = "Saved choices changed. Review the listening settings and retry.";
                    for(int pending = index + 1; pending < selected.size(); pending++)
                        if(selected.get(pending).saved == null) selected.get(pending).saveError =
                            "Saved choices changed. Review the listening settings and retry.";
                    break;
                }
                catch(ConfigurationManager.ConfigurationPublicationException exception)
                {
                    if(exception.committedConfigurationId() != null)
                    {
                        row.saved = new ChannelAdministrationService.DiscoveryCreated(exception.committedConfigurationId(),
                            exception.committedAliasListId());
                        row.savedName = name;
                        row.autoStart = edit.autoStart();
                        job.aliasIds.put(row.groupId(), row.saved.aliasListId());
                    }
                    job.restartRequired = true;
                    job.reason = job.rows.values().stream().anyMatch(saved -> saved.saved != null) ?
                        "Restart VCE before adding or listening to more channels. Your added channels are saved." :
                        "Nothing was added by this search. Restart VCE before continuing.";
                    selected.stream().filter(pending -> pending.saved == null).forEach(pending ->
                        pending.saveError = "Not added. Restart VCE before continuing.");
                    throw exception;
                }
                catch(RuntimeException exception)
                {
                    row.saveError = exception instanceof IllegalArgumentException || exception instanceof IllegalStateException ?
                        exception.getMessage() : "This channel could not be saved. Retry this result.";
                }
            }
            refreshGroups(job);
            job.revision = mChannels.revision();
            synchronized(mLock) { return snapshot(job); }
        }
        finally { endCommand(job); }
    }

    public Snapshot start(String id, List<String> candidateIds, String firstCandidateId)
    {
        Job job = beginCommand(id);
        try
        {
            List<Row> rows = selected(job, candidateIds);
            if(rows.stream().anyMatch(row -> row.saved == null))
                throw new IllegalStateException("Save each selected channel before starting it");
            Row first = firstCandidateId == null ? rows.getFirst() : rows.stream().filter(row -> row.id.equals(firstCandidateId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose the first channel from the selected results"));
            List<Row> ordered = new ArrayList<>(rows.size());
            ordered.add(first);
            rows.stream().filter(row -> row != first).forEach(ordered::add);
            rows = ordered;
            if(rows.stream().allMatch(row -> row.running)) { synchronized(mLock) { return snapshot(job); } }
            if(!job.handedOff)
            {
                try { job.lease.handoff(probeCenter(first.frequency, job.lease), () -> { startRows(job, ordered); return null; }); }
                catch(RuntimeException exception)
                {
                    rows.stream().filter(row -> !row.running).forEach(row -> row.startError =
                        "The channel is saved. The receiver could not start it; open the channel to try later.");
                }
                finally { job.handedOff = !job.lease.valid(); }
            }
            else startRows(job, rows);
            synchronized(mLock) { return snapshot(job); }
        }
        finally { endCommand(job); }
    }

    private void startRows(Job job, List<Row> rows)
    {
        for(Row row: rows)
        {
            if(job.cancelled.get() || row.running) continue;
            row.startError = null;
            if(!FrequencyListenService.withinCurrentWindow(row.frequency, job.lease.centerFrequencyHz(), 12500,
                job.lease.usableBandwidthHz() / 2, job.lease.middleUnusableHalfBandwidthHz()))
            {
                row.startError = "Saved for later: this frequency is outside the selected receiver's current window.";
                continue;
            }
            try
            {
                var result = mChannels.start(row.saved.configurationId(), job.tunerId, job.lease.tuner(), !job.handedOff);
                row.running = result.success() && result.state() == ChannelAdministrationService.ProcessingState.RUNNING;
                if(!row.running) row.startError = "The channel is saved but could not start on this receiver.";
            }
            catch(RuntimeException exception) { row.startError = "The channel is saved but the receiver is unavailable."; }
        }
    }

    private void refreshGroups(Job job)
    {
        Map<String,AliasGroup> groups = new LinkedHashMap<>();
        for(Row row: job.rows.values())
        {
            var review = mChannels.review(row.frequency, preferred(job), row.identity, row.modulation);
            row.friendlySystem = review.template().system();
            row.friendlySite = review.template().site();
            if(groups.containsKey(row.groupId())) continue;
            groups.put(row.groupId(), new AliasGroup(row.groupId(), row.identity.wacn(), row.identity.system(),
                review.aliasLists(), job.aliasIds.getOrDefault(row.groupId(), review.suggestedAliasListId()),
                review.defaultNewAliasListName()));
        }
        synchronized(mLock) { job.groups = groups; }
    }

    public void cancel(String id)
    {
        Job job;
        synchronized(mLock) { job = requireJob(id); cancelLocked(job); }
        quiesce(job);
        synchronized(mLock) { if(mJob == job) mJob = null; }
    }
    public void closeActiveSession()
    {
        Job job;
        synchronized(mLock) { job = mJob; if(job != null) cancelLocked(job); }
        if(job != null) quiesce(job);
        synchronized(mLock) { if(mJob == job) mJob = null; }
    }
    @Override public void close()
    {
        synchronized(mLock) { mClosed = true; }
        try { closeActiveSession(); }
        finally { mExpiry.shutdownNow(); }
    }
    private void expire()
    {
        Job job;
        synchronized(mLock) { expireLocked(); job = mJob != null && mJob.cancelled.get() ? mJob : null; }
        if(job != null)
        {
            try
            {
                quiesce(job);
                synchronized(mLock) { if(mJob == job) mJob = null; }
            }
            catch(RuntimeException exception) { /* Keep ownership and retry cleanup on the next bounded expiry pass. */ }
        }
    }
    private void expireLocked()
    {
        if(mJob != null && mClock.getAsLong() > mJob.expiresAt &&
            (!mJob.commandBusy || mClock.getAsLong() >= mJob.maximumExpiresAt)) cancelLocked(mJob);
    }
    private void cancelLocked(Job job)
    {
        job.cancelled.set(true);
        job.worker.interrupt();
    }
    private void quiesce(Job job)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try
        {
            job.worker.join(5000);
            synchronized(mLock)
            {
                while(job.commandBusy && System.nanoTime() < deadline)
                    mLock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                if(job.worker.isAlive() || job.commandBusy)
                    throw new IllegalStateException("Signal search is still releasing its receiver; retry shortly");
            }
            closeLease(job);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Signal search cleanup was interrupted; retry shortly", exception);
        }
    }
    private Job requireJob(String id)
    {
        expireLocked();
        if(mClosed || mJob == null || mJob.cancelled.get() || !mJob.id.equals(id))
            throw new SearchExpiredException();
        return mJob;
    }
    private Job beginCommand(String id)
    {
        synchronized(mLock)
        {
            Job job = requireJob(id);
            if(job.restartRequired) throw new ConfigurationManager.ConfigurationPublicationException(
                "Restart VCE before adding or listening to more channels");
            if(!"complete".equals(job.phase)) throw new IllegalStateException("Finish searching before adding channels");
            if(job.commandBusy) throw new IllegalStateException("Another result operation is still running");
            if(!job.handedOff && !job.lease.valid()) throw new IllegalStateException("The search receiver changed; begin again");
            job.commandBusy = true;
            job.expiresAt = Math.min(job.maximumExpiresAt, mClock.getAsLong() + IDLE_MS);
            return job;
        }
    }
    private void endCommand(Job job)
    {
        try { if(job.cancelled.get()) closeLease(job); }
        finally
        {
            synchronized(mLock)
            {
                job.commandBusy = false;
                job.expiresAt = Math.min(job.maximumExpiresAt, mClock.getAsLong() + IDLE_MS);
                mLock.notifyAll();
            }
        }
    }
    private void requireScanning(Job job) throws InterruptedException
    {
        if(job.cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if(mClock.getAsLong() >= job.scanDeadline) throw new SearchLimitException("Search reached its 15 minute limit. Choose fewer bands and try again.");
        if(!job.lease.valid()) throw new IllegalStateException("Search receiver changed");
    }
    private static void closeLease(Job job)
    {
        synchronized(job.cleanupLock)
        {
            if(job.leaseReleased) return;
            try { job.lease.close(); }
            finally { job.leaseReleased = true; }
        }
    }
    private Snapshot snapshot(Job job)
    {
        // Once published, retain confirmed results and committed rows even if the receiver subsequently disappears.
        boolean complete = job.resultsPublished;
        List<Candidate> rows = complete ? job.rows.values().stream().map(row -> new Candidate(row.id,
            row.frequency, row.identity, row.modulation, row.strength, row.health,
            row.savedName != null ? row.savedName : row.name(), row.systemName(), row.siteName(), row.groupId(),
            row.known, row.known == null && row.saved == null, row.saved != null,
            row.saved != null ? row.saved.configurationId() : null, row.saved != null ? row.saved.aliasListId() : null,
            row.autoStart, row.running, row.saveError, row.startError)).toList() : List.of();
        return new Snapshot(job.id, job.tunerId, job.lease.targetId(), job.phase, job.reason, job.truncatedReason, job.restartRequired, job.revision,
            job.expiresAt, new Progress(job.completedWindows, job.windows.size() * 2, job.currentFrequency,
                job.checkedSignals, job.totalSignals), rows, complete ? List.copyOf(job.groups.values()) : List.of());
    }

    static List<Long> windows(List<Range> ranges, long usable, long minimum, long maximum)
    {
        if(usable < 100_000 || maximum <= minimum) throw new IllegalArgumentException("Receiver bandwidth is unavailable");
        LinkedHashSet<Long> centers = new LinkedHashSet<>();
        long step = usable * 2 / 5;
        for(Range range: ranges)
        {
            if(range.minimumHz() < minimum || range.maximumHz() > maximum)
                throw new IllegalArgumentException("A scan range is outside this receiver's frequency limits");
            for(long frequency = range.minimumHz(); frequency <= range.maximumHz() + step; frequency += step)
            {
                // Keep the last window's center below the hardware ceiling so its DC notch cannot hide the band edge.
                centers.add(Math.min(Math.max(minimum, maximum - usable / 4), Math.max(minimum, frequency + usable / 4)));
                if(centers.size() > MAX_WINDOWS) throw new IllegalArgumentException("These bands need too many receiver windows. Choose fewer bands or a wider receiver");
            }
        }
        return List.copyOf(centers);
    }
    static long probeCenter(long frequency, SpectrumSearchHardware.Lease lease)
    {
        long offset = Math.max(lease.middleUnusableHalfBandwidthHz() + 25000, Math.min(100000, lease.usableBandwidthHz() / 4));
        long center = frequency + offset;
        if(center > lease.maximumFrequencyHz()) center = frequency - offset;
        if(center < lease.minimumFrequencyHz() || center > lease.maximumFrequencyHz())
            throw new IllegalArgumentException("This signal cannot fit inside the receiver's usable window");
        if(!FrequencyListenService.withinCurrentWindow(frequency, center, 12500,
            lease.usableBandwidthHz() / 2, lease.middleUnusableHalfBandwidthHz()))
            throw new IllegalArgumentException("This signal cannot fit inside the receiver's usable window");
        return center;
    }
    public static void validateRanges(List<Range> ranges)
    {
        if(ranges == null || ranges.isEmpty() || ranges.size() > 8)
            throw new IllegalArgumentException("Choose between one and eight scan ranges");
        long total = 0;
        for(Range range: ranges)
        {
            if(range == null || range.minimumHz() <= 0 || range.maximumHz() <= range.minimumHz() ||
                range.maximumHz() > 6_000_000_000L) throw new IllegalArgumentException("Enter valid scan frequency limits");
            total += range.maximumHz() - range.minimumHz();
        }
        if(total > MAX_TOTAL_HZ) throw new IllegalArgumentException("Choose at most 150 MHz of scan ranges");
    }
    private static boolean inRanges(long frequency, List<Range> ranges)
    {
        return ranges.stream().anyMatch(range -> frequency >= range.minimumHz() && frequency <= range.maximumHz());
    }
    private static SpectrumPeakDetector.Peak find(List<SpectrumPeakDetector.Peak> peaks, long frequency)
    {
        return peaks.stream().filter(peak -> Math.abs(peak.frequencyHz() - frequency) <= 6250).findFirst().orElse(null);
    }
    private static String preferred(Job job) { return job.lease.tuner() != null ? job.lease.tuner().getPreferredName() : null; }
    private static P25SiteIdentity verifiedIdentity(P25DiscoveryProbe.Status status)
    {
        if(status == null || !"ready".equals(status.state()) || status.identity() == null) return null;
        var mode = "C4FM".equals(status.selectedModulation()) ? status.c4fm() :
            "CQPSK".equals(status.selectedModulation()) ? status.cqpsk() : null;
        if(mode == null || !mode.confirmed() || !Objects.equals(status.identity(), mode.identity())) return null;
        var identity = status.identity();
        return new P25SiteIdentity(identity.wacn(), identity.system(), identity.rfss(), identity.site());
    }
    private static P25DiscoveryProbe.Status check(SpectrumSearchHardware.Lease lease, long frequency,
                                                 BooleanSupplier cancelled) throws InterruptedException
    {
        try(var probe = lease.probe(frequency))
        {
            while(!cancelled.getAsBoolean())
            {
                var status = probe.status();
                if(!"running".equals(status.state())) return status;
                Thread.sleep(100);
            }
            throw new InterruptedException();
        }
    }
    private static List<Row> selected(Job job, List<String> ids)
    {
        if(ids == null || ids.isEmpty() || ids.size() > MAX_CANDIDATES || new LinkedHashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Choose unique results from this search");
        return ids.stream().map(id -> {
            Row row = job.rows.get(id);
            if(row == null) throw new IllegalArgumentException("A selected result does not belong to this search");
            return row;
        }).toList();
    }
    private static Catalog catalog(TunerAdministrationService tuners)
    {
        List<SearchTuner> targets = new ArrayList<>();
        var inventory = tuners.snapshot().tuners();
        for(var item: inventory)
        {
            var found = tuners.find(item.id());
            var runtime = found != null && found.hasTuner() ? found.getTuner() : null;
            var configuration = found != null ? found.getTunerConfiguration() : null;
            long bandwidth = item.usableBandwidthHz() != null ? item.usableBandwidthHz() :
                item.configuredSampleRateHz() != null ? item.configuredSampleRateHz() * 4 / 5 : 0;
            long minimum = item.minimumFrequencyHz() != null ? item.minimumFrequencyHz() : 0;
            long maximum = item.maximumFrequencyHz() != null ? item.maximumFrequencyHz() : 0;
            if(runtime == null && found != null)
                for(var setting: TunerSettingCatalog.describe(found))
                    if("frequency_mhz".equals(setting.id()) && setting.minimum() != null && setting.maximum() != null)
                    {
                        minimum = Math.round(setting.minimum().doubleValue() * 1_000_000);
                        maximum = Math.round(setting.maximum().doubleValue() * 1_000_000);
                    }
            List<TunerAdministrationService.Item> group = item.deviceGroup() == null ? List.of(item) :
                inventory.stream().filter(other -> other.deviceGroup() != null &&
                    other.deviceGroup().id().equals(item.deviceGroup().id())).toList();
            int channelCount = group.stream().mapToInt(TunerAdministrationService.Item::channelCount).sum();
            boolean groupTransition = group.stream().anyMatch(other -> other.transition() != null);
            boolean groupBusy = channelCount > 0 || groupTransition;
            boolean locked = configuration != null && configuration.isCenterFrequencyLocked();
            boolean supported = found != null && configuration != null && found.getTunerClass() != TunerClass.RECORDING_TUNER &&
                (found.getTunerStatus() == TunerStatus.ENABLED || found.getTunerStatus() == TunerStatus.DISABLED) &&
                bandwidth >= 100000 && maximum > minimum;
            boolean eligible = supported && item.channelCount() == 0 && item.transition() == null && !groupBusy && !locked &&
                (runtime == null || !runtime.getTunerController().isLockedSampleRate());
            boolean takeoverAllowed = supported && !groupTransition;
            String reason = eligible ? null : channelCount > 0 ?
                channelCount + " active channel" + (channelCount == 1 ? "" : "s") + " will stop" :
                locked ? "Center frequency is locked" : groupTransition ? "Receiver settings are changing" :
                    "Choose an available receiver";
            targets.add(new SearchTuner(item.id(), item.name(), eligible, takeoverAllowed, reason, channelCount,
                locked, item.operatorState(), bandwidth, minimum, maximum,
                item.frequencyHz() != null ? item.frequencyHz() : item.configuredFrequencyHz()));
        }
        String suggested = targets.stream().filter(SearchTuner::eligible)
            .max(Comparator.comparingLong(SearchTuner::usableBandwidthHz)).map(SearchTuner::id).orElse(null);
        return new Catalog(List.copyOf(targets), suggested, List.of(
            new Preset("vhf-high", "VHF high · 138–174 MHz", List.of(new Range(138000000,174000000))),
            new Preset("uhf", "UHF · 406–470 MHz", List.of(new Range(406000000,470000000))),
            new Preset("700mhz", "700 MHz · 769–775 MHz", List.of(new Range(769000000,775000000))),
            new Preset("800mhz", "800 MHz · 851–869 MHz", List.of(new Range(851000000,869000000)))),
            new Bounds(8, MAX_WINDOWS, MAX_CANDIDATES, MAX_TOTAL_HZ, 750, 5000, 1500, MAX_SCAN_MS));
    }

    interface HardwareFactory { SpectrumSearchHardware.Lease open(String tunerId, String browseLeaseId); }
    interface ProbeCheck { P25DiscoveryProbe.Status check(SpectrumSearchHardware.Lease lease, long frequency,
                                                         BooleanSupplier cancelled) throws InterruptedException; }
    interface Channels
    {
        long revision();
        KnownChannel known(long frequency);
        default KnownChannel knownSite(P25SiteIdentity identity) { return null; }
        ChannelAdministrationService.DiscoveryReview review(long frequency, String preferred, P25SiteIdentity identity, String modulation);
        ChannelAdministrationService.DiscoveryCreated create(ChannelDefinition definition, P25SiteIdentity identity,
            String aliasName, long revision, boolean autoStart);
        ChannelAdministrationService.LifecycleResult start(String id, String tunerId, Tuner runtime, boolean handoff);
    }
    private static final class Job
    {
        final String id = UUID.randomUUID().toString();
        final String tunerId;
        final SpectrumSearchHardware.Lease lease;
        final List<Range> ranges;
        final long dwellMs, scanDeadline, maximumExpiresAt;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Object cleanupLock = new Object();
        final Map<String,Row> rows = new LinkedHashMap<>();
        final Map<String,Long> aliasIds = new LinkedHashMap<>();
        Map<String,AliasGroup> groups = new LinkedHashMap<>();
        List<Long> windows = List.of();
        Thread worker;
        volatile String phase = "scanning";
        String reason;
        volatile String truncatedReason;
        long expiresAt;
        volatile long revision;
        int completedWindows, checkedSignals, totalSignals;
        Long currentFrequency;
        boolean commandBusy, resultsPublished;
        boolean leaseReleased;
        volatile boolean handedOff;
        volatile boolean restartRequired;
        Job(String tunerId, SpectrumSearchHardware.Lease lease, List<Range> ranges, long dwellMs, long now)
        {
            this.tunerId = tunerId; this.lease = lease; this.ranges = ranges; this.dwellMs = dwellMs;
            scanDeadline = now + MAX_SCAN_MS; maximumExpiresAt = scanDeadline + RESULT_AGE_MS; expiresAt = now + IDLE_MS;
        }
    }
    private static final class Row
    {
        final String id = UUID.randomUUID().toString();
        final long frequency;
        final double strength;
        final P25SiteIdentity identity;
        final String modulation;
        final Health health;
        volatile KnownChannel known;
        volatile ChannelAdministrationService.DiscoveryCreated saved;
        volatile String savedName, saveError, startError, friendlySystem, friendlySite;
        volatile boolean running;
        volatile Boolean autoStart;
        Row(long frequency, double strength, P25SiteIdentity identity, String modulation, Health health)
        { this.frequency = frequency; this.strength = strength; this.identity = identity; this.modulation = modulation; this.health = health; }
        String groupId() { return String.format(Locale.ROOT, "p25-%05X-%03X", identity.wacn(), identity.system()); }
        String systemName() { return friendlySystem != null && !friendlySystem.isBlank() ? friendlySystem :
            String.format(Locale.ROOT, "P25 %05X-%03X", identity.wacn(), identity.system()); }
        String siteName() { return friendlySite != null && !friendlySite.isBlank() ? friendlySite :
            "RFSS " + identity.rfss() + " · Site " + identity.site(); }
        String name() { return systemName() + " · " + siteName(); }
    }
    private static final class SearchLimitException extends RuntimeException
    { SearchLimitException(String message) { super(message); } }
    public static final class SearchExpiredException extends IllegalStateException
    { public SearchExpiredException() { super("Signal search expired; begin again"); } }
    public record Range(long minimumHz, long maximumHz) { }
    public record Bounds(int maximumRanges, int maximumWindows, int maximumCandidates, long maximumTotalHz,
                         long minimumDwellMs, long maximumDwellMs, long defaultDwellMs, long maximumScanMs) { }
    public record SearchTuner(String id, String name, boolean eligible, boolean takeoverAllowed, String reason,
                              int channelCount, boolean centerFrequencyLocked, String operatorState,
                              long usableBandwidthHz, long minimumFrequencyHz, long maximumFrequencyHz,
                              Long centerFrequencyHz) { }
    public record Preset(String id, String label, List<Range> ranges) { }
    public record Catalog(List<SearchTuner> tuners, String suggestedTunerId, List<Preset> presets, Bounds bounds) { }
    public record Health(double qualityPct, long validMessages, long validControlMessages,
                         long invalidControlMessages, long checkedAtMs) { }
    public record KnownChannel(String configurationId, String name) { }
    public record Candidate(String candidateId, long frequencyHz, P25SiteIdentity identity, String modulation,
                            double strengthDbfs, Health health, String name, String systemName, String siteName,
                            String aliasGroupId, KnownChannel knownChannel, boolean selectable, boolean saved,
                            String configurationId, Long aliasListId, Boolean autoStart, boolean running, String saveError, String startError) { }
    public record AliasGroup(String groupId, int wacn, int system,
                             List<ChannelAdministrationService.DiscoveryAliasList> aliasLists,
                             Long suggestedAliasListId, String defaultNewAliasListName) { }
    public record Progress(int completed, int total, Long currentFrequencyHz, int checked, int totalSignals) { }
    public record Snapshot(String jobId, String tunerId, String targetId, String phase, String reason, String truncatedReason, boolean restartRequired, long revision,
                           long expiresAtMs, Progress progress, List<Candidate> candidates, List<AliasGroup> aliasGroups) { }
    public record SaveCandidate(String candidateId, String name, boolean autoStart) { }
    public record AliasChoice(String groupId, long aliasListId, String newAliasListName) { }
    public record SaveRequest(long revision, List<SaveCandidate> candidates, List<AliasChoice> aliasGroups) { }
}

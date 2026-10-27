/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.tuner;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationDecision;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationDiagnosticService;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationKnownSite;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationRuntimeChannel;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerCoveragePlanner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shared current-state projection for administrator HTTP workers. The allocator only writes its fixed atomic history;
 * no receiving callback waits for this cache, labels, channel projection, or JSON. Concurrent clients reuse one view.
 */
public final class TunerAllocationMonitorService
{
    public static final int MAXIMUM_VISIBLE_DECISIONS = 100;
    private static final long CACHE_MS = 250;
    private final Supplier<List<DiscoveredTuner>> mInventory;
    private final Supplier<List<TunerAllocationKnownSite>> mKnownSites;
    private final Supplier<List<TunerAllocationRuntimeChannel>> mReceiving;
    private final TunerAllocationDiagnosticService mHistory;
    private final LongSupplier mClock;
    private final Supplier<Map<String,TunerAdministrationService.ReceiverReference>> mReceiverReferences;
    private final ReentrantLock mProjectionLock = new ReentrantLock();
    private final Map<String,String> mNames = new LinkedHashMap<>();
    private int mNextName;
    private volatile CachedSnapshot mSnapshot;

    private record CachedSnapshot(Snapshot snapshot,
        Map<String,TunerAdministrationService.ReceiverReference> receivers) { }

    public TunerAllocationMonitorService(TunerManager manager, ChannelProcessingManager channels)
    {
        this(manager, channels, Map::of);
    }

    public TunerAllocationMonitorService(TunerManager manager, ChannelProcessingManager channels,
        Supplier<Map<String,TunerAdministrationService.ReceiverReference>> receiverReferences)
    {
        this(manager.getDiscoveredTunerRegistry()::snapshot, manager::getKnownFrequencySites,
            channels != null ? channels::getActiveTunerAllocationChannels : List::of,
            manager.getTunerAllocationDiagnosticService(), System::currentTimeMillis, receiverReferences);
    }

    TunerAllocationMonitorService(Supplier<List<DiscoveredTuner>> inventory,
                                  Supplier<List<TunerAllocationKnownSite>> knownSites,
                                  Supplier<List<TunerAllocationRuntimeChannel>> receiving,
                                  TunerAllocationDiagnosticService history, LongSupplier clock)
    {
        this(inventory, knownSites, receiving, history, clock, Map::of);
    }

    TunerAllocationMonitorService(Supplier<List<DiscoveredTuner>> inventory,
                                  Supplier<List<TunerAllocationKnownSite>> knownSites,
                                  Supplier<List<TunerAllocationRuntimeChannel>> receiving,
                                  TunerAllocationDiagnosticService history, LongSupplier clock,
                                  Supplier<Map<String,TunerAdministrationService.ReceiverReference>> receiverReferences)
    {
        mInventory = inventory;
        mKnownSites = knownSites;
        mReceiving = receiving;
        mHistory = history;
        mClock = clock;
        mReceiverReferences = Objects.requireNonNull(receiverReferences);
    }

    /** A slow observer can hold its immutable result; it cannot hold any producer lock or block another observer. */
    public Snapshot snapshot()
    {
        long now = mClock.getAsLong();
        Map<String,TunerAdministrationService.ReceiverReference> receivers = mReceiverReferences.get();
        CachedSnapshot cached = mSnapshot;
        Snapshot previous = cached != null ? cached.snapshot() : null;
        if(previous != null && now - previous.generatedAtMs() < CACHE_MS &&
            cached.receivers().equals(receivers)) return previous;
        if(!mProjectionLock.tryLock()) return previous;
        try
        {
            cached = mSnapshot;
            previous = cached != null ? cached.snapshot() : null;
            if(previous != null && now - previous.generatedAtMs() < CACHE_MS &&
                cached.receivers().equals(receivers)) return previous;
            Snapshot projected = project(now, receivers);
            mSnapshot = new CachedSnapshot(projected, receivers);
            return projected;
        }
        finally { mProjectionLock.unlock(); }
    }

    private Snapshot project(long now, Map<String,TunerAdministrationService.ReceiverReference> receivers)
    {
        TunerAllocationDiagnosticService.Snapshot history = mHistory.snapshot();
        List<DiscoveredTuner> inventory = mInventory.get();
        List<TunerAllocationRuntimeChannel> receiving = mReceiving.get();
        List<TunerAllocationKnownSite> knownSites = mKnownSites.get();
        Set<String> retained = new LinkedHashSet<>();
        for(DiscoveredTuner tuner: inventory) retained.add(tuner.getId());
        for(TunerAllocationDiagnosticService.Entry entry: history.decisions())
        {
            if(entry.decision().tunerIdentity() != null) retained.add(entry.decision().tunerIdentity());
            for(TunerAllocationDecision.Candidate candidate: entry.decision().candidates())
                if(candidate.tunerIdentity() != null) retained.add(candidate.tunerIdentity());
        }
        mNames.keySet().retainAll(retained);
        List<TunerView> tuners = new ArrayList<>();
        for(DiscoveredTuner discovered: inventory)
        {
            try { tuners.add(tuner(discovered, receiving, knownSites, history, receivers)); }
            catch(RuntimeException ignored) { /* Hot unplug during observer projection; retry on the next poll. */ }
        }
        Map<String,SiteBuilder> sites = new LinkedHashMap<>();
        for(TunerAllocationKnownSite site: knownSites)
        {
            if(site.context() == null) continue;
            SiteBuilder builder = sites.computeIfAbsent(siteId(site.context()), ignored -> new SiteBuilder(site.context()));
            for(TunerAllocationKnownSite.Frequency frequency: site.frequencies())
                builder.add(frequency.frequencyHz(), frequency.bandwidthHz(), frequency.role());
        }
        for(TunerAllocationRuntimeChannel channel: receiving)
        {
            if(channel.context() == null) continue;
            SiteBuilder builder = sites.computeIfAbsent(siteId(channel.context()), ignored -> new SiteBuilder(channel.context()));
            if(channel.context().role() != TunerAllocationContext.Role.CALL) builder.context = channel.context();
            builder.add(channel.frequencyHz(), channel.bandwidthHz(), channel.context().role());
            builder.roles.add(role(channel.context().role()));
            builder.tuners.add(id(channel.tunerIdentity()));
        }
        List<SiteView> siteViews = new ArrayList<>();
        for(Map.Entry<String,SiteBuilder> entry: sites.entrySet()) siteViews.add(entry.getValue().view(entry.getKey(), tuners));
        List<DecisionView> decisions = history.decisions().stream().limit(MAXIMUM_VISIBLE_DECISIONS)
            .map(entry -> decision(entry, receivers)).toList();
        return new Snapshot(true, history.sessionId(), history.cursor(), now, List.copyOf(siteViews),
            List.copyOf(tuners), decisions);
    }

    private TunerView tuner(DiscoveredTuner discovered, List<TunerAllocationRuntimeChannel> receiving,
                            List<TunerAllocationKnownSite> knownSites,
                            TunerAllocationDiagnosticService.Snapshot history,
                            Map<String,TunerAdministrationService.ReceiverReference> receivers)
    {
        String identity = discovered.getId();
        Tuner tuner = discovered.hasTuner() ? discovered.getTuner() : null;
        boolean available = discovered.isAvailableForAllocation() && tuner != null;
        TunerController controller = tuner != null ? tuner.getTunerController() : null;
        TunerController.ReceivingGeometry geometry = controller != null ? controller.getReceivingGeometry() : null;
        List<ReceivingView> channels = receiving.stream().filter(channel -> identity.equals(channel.tunerIdentity()))
            .map(channel -> new ReceivingView(channel.frequencyHz(), channel.bandwidthHz(), role(channel.context().role()),
                safe(channel.context().channelName()), siteId(channel.context()))).toList();
        Set<String> preferredFor = new LinkedHashSet<>();
        if(tuner != null)
        {
            String preferred = discovered.getAllocationPreferredName();
            Map<String,TunerAllocationContext> contexts = new LinkedHashMap<>();
            for(TunerAllocationKnownSite site: knownSites)
                if(site.context() != null) contexts.put(siteId(site.context()), site.context());
            for(TunerAllocationRuntimeChannel channel: receiving)
                if(channel.context() != null && channel.context().role() != TunerAllocationContext.Role.CALL)
                    contexts.put(siteId(channel.context()), channel.context());
            for(TunerAllocationContext context: contexts.values())
                if(preferred != null && preferred.equalsIgnoreCase(context.preferredTuner()))
                    preferredFor.add(safe(context.channelName()) != null ? safe(context.channelName()) : "Channel");
        }
        Long center = geometry != null ? geometry.frequencyHz() : null;
        String reason = null;
        if(center != null)
            for(TunerAllocationDiagnosticService.Entry entry: history.decisions())
                if(identity.equals(entry.decision().tunerIdentity()) &&
                    center.equals(entry.decision().afterFrequencyHz()) &&
                    entry.decision().outcome() == TunerAllocationDecision.Outcome.MOVED)
                {
                    reason = reason(entry.decision().reason());
                    break;
                }
        String prefix = discovered.getTunerClass() == TunerClass.RTL2832 ? "RTL-SDR" :
            discovered.getTunerClass().toString();
        String name = mNames.computeIfAbsent(identity, ignored -> prefix + " " + ++mNextName);
        return new TunerView(id(identity), nickname(identity, receivers, name), center,
            geometry != null ? (long)geometry.usableBandwidthHz() : null,
            geometry != null ? 2L * geometry.middleUnusableHalfBandwidthHz() : null,
            discovered.getTunerStatus().name().toLowerCase(Locale.ROOT), available,
            geometry != null && geometry.centerLocked(),
            geometry != null ? geometry.minimumFrequencyHz() : null,
            geometry != null ? geometry.maximumFrequencyHz() : null,
            List.copyOf(preferredFor), channels, reason,
            tuner != null && tuner.getChannelSourceManager() instanceof PolyphaseChannelSourceManager);
    }

    private DecisionView decision(TunerAllocationDiagnosticService.Entry entry,
                                  Map<String,TunerAdministrationService.ReceiverReference> receivers)
    {
        TunerAllocationDecision decision = entry.decision();
        TunerAllocationContext context = decision.context();
        List<CandidateView> candidates = decision.candidates().stream().map(candidate ->
            new CandidateView(id(candidate.tunerIdentity()), name(candidate.tunerIdentity(), receivers),
                reason(candidate.reason()), candidate.minimumFrequencyHz(), candidate.maximumFrequencyHz())).toList();
        return new DecisionView(entry.decisionId(), entry.sequence(), entry.lastTimestamp(),
            context != null ? siteId(context) : null, context != null ? siteName(context) : null,
            id(decision.tunerIdentity()), name(decision.tunerIdentity(), receivers),
            decision.outcome() == TunerAllocationDecision.Outcome.MOVED ||
                decision.outcome() == TunerAllocationDecision.Outcome.MOVED_CALL_FAILED ? "move" : "failure",
            decision.outcome().name().toLowerCase(Locale.ROOT), reason(decision.reason()),
            decision.requestedFrequencyHz(), decision.requestedBandwidthHz(), decision.beforeFrequencyHz(),
            decision.requestedCenterFrequencyHz(), decision.afterFrequencyHz(), decision.usableBandwidthHz(),
            decision.middleUnusableHalfBandwidthHz() != null ? 2L * decision.middleUnusableHalfBandwidthHz() : null,
            decision.controlFrequencyHz(), entry.repeatCount(), entry.firstTimestamp(), entry.lastTimestamp(),
            decision.coveredBefore(), decision.coveredAfter(), candidates);
    }

    private String name(String identity, Map<String,TunerAdministrationService.ReceiverReference> receivers)
    {
        return identity == null ? null : nickname(identity, receivers,
            mNames.computeIfAbsent(identity, ignored -> "Tuner " + ++mNextName));
    }

    private static String nickname(String identity, Map<String,TunerAdministrationService.ReceiverReference> receivers,
                                   String fallback)
    {
        var receiver = receivers.get(identity);
        return receiver != null && receiver.nickname() != null && !receiver.nickname().isBlank() ?
            receiver.nickname() : fallback;
    }

    private String id(String identity)
    {
        if(identity == null) return null;
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                (mHistory.sessionId() + '\0' + identity).getBytes(StandardCharsets.UTF_8));
            return "tuner-" + HexFormat.of().formatHex(bytes, 0, 12);
        }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static String reason(TunerAllocationDecision.Reason reason)
    {
        return switch(reason)
        {
            case SPARE_TUNER -> "An unused tuner could receive this call";
            case KEEP_CURRENT_CALLS -> "The tuner could receive this call and keep its current channels";
            case COVERAGE_GAP -> "An unused tuner could cover more known frequencies";
            case CENTER_LOCKED -> "The center frequency is locked";
            case OUTSIDE_LIMITS -> "The frequency is outside the tuners' allowed range";
            case CONTROL_CHANNEL_PROTECTED -> "Moving would lose the control channel";
            case NO_TUNER_AVAILABLE -> "No tuner is available";
            case NO_ROOM -> "No tuner had room for this frequency and its current channels";
            case TUNER_BUSY -> "A tuner was busy changing channels";
            case TUNER_UNAVAILABLE -> "The tuner was unavailable";
            case SOURCE_START_FAILED -> "The receiving channel could not start";
            case HARDWARE_RETUNE_FAILED -> "The tuner could not change frequency";
        };
    }

    private static String safe(String value)
    {
        return value == null || value.isBlank() ? null : io.github.dsheirer.web.WebDiagnosticLabels.safeLabel(value);
    }

    private static String role(TunerAllocationContext.Role role)
    {
        return role != null ? role.name().toLowerCase(Locale.ROOT) : "call";
    }

    private static String siteId(TunerAllocationContext context)
    {
        return context.channelId() != null ? context.channelId() : "unsaved-" +
            Integer.toUnsignedString(java.util.Objects.hash(context.systemName(), context.siteName(), context.channelName()));
    }

    private static String siteName(TunerAllocationContext context)
    {
        String name = safe(context.siteName());
        return name != null ? name : safe(context.channelName()) != null ? safe(context.channelName()) : "Channel";
    }

    private static boolean covers(TunerView tuner, long frequency, long bandwidth)
    {
        if(!tuner.available() || tuner.frequencyHz() == null || tuner.usableBandwidthHz() == null ||
            tuner.minFrequencyHz() == null || tuner.maxFrequencyHz() == null || bandwidth <= 0) return false;
        if(!tuner.supportsSharedWindow() && frequency != tuner.frequencyHz()) return false;
        long halfGap = tuner.dcGapHz() != null ? tuner.dcGapHz() / 2 : 0;
        return TunerCoveragePlanner.covers(new TunerCoveragePlanner.Window(tuner.frequencyHz(),
            Math.toIntExact(tuner.usableBandwidthHz()), Math.toIntExact(halfGap), tuner.minFrequencyHz(),
            tuner.maxFrequencyHz()), new TunerChannel(frequency, Math.toIntExact(bandwidth)));
    }

    private static final class SiteBuilder
    {
        private TunerAllocationContext context;
        private final Map<Long,TunerAllocationKnownSite.Frequency> frequencies = new LinkedHashMap<>();
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<String> tuners = new LinkedHashSet<>();
        private SiteBuilder(TunerAllocationContext context) { this.context = context; }
        private void add(long frequency, long bandwidth, TunerAllocationContext.Role role)
        {
            if(frequency <= 0 || bandwidth <= 0) return;
            TunerAllocationKnownSite.Frequency previous = frequencies.get(frequency);
            long width = previous != null ? Math.max(previous.bandwidthHz(), bandwidth) : bandwidth;
            TunerAllocationContext.Role selected = previous != null && previous.role() == TunerAllocationContext.Role.CONTROL ?
                previous.role() : role;
            frequencies.put(frequency, new TunerAllocationKnownSite.Frequency(frequency, width, selected));
        }
        private SiteView view(String id, List<TunerView> windows)
        {
            List<FrequencyView> rows = frequencies.values().stream()
                .sorted(java.util.Comparator.comparingLong(TunerAllocationKnownSite.Frequency::frequencyHz))
                .map(frequency -> new FrequencyView(frequency.frequencyHz(), frequency.bandwidthHz(),
                    windows.stream().anyMatch(tuner -> covers(tuner, frequency.frequencyHz(), frequency.bandwidthHz())),
                    role(frequency.role()))).toList();
            for(TunerView window: windows)
                if(rows.stream().anyMatch(frequency -> covers(window, frequency.frequencyHz(), frequency.bandwidthHz())))
                    tuners.add(window.id());
            String status = roles.contains("control") ? roles.contains("call") ? "control_and_calls" : "control" :
                roles.contains("conventional") ? "conventional" : roles.contains("call") ? "calls" : "stopped";
            return new SiteView(id, siteName(context), safe(context.systemName()), safe(context.channelName()),
                context.role() == TunerAllocationContext.Role.CONVENTIONAL ? "conventional" : "trunked",
                rows.size(), (int)rows.stream().filter(FrequencyView::covered).count(), rows, List.copyOf(tuners), status);
        }
    }

    public record Snapshot(boolean available, String sessionId, long cursor, long generatedAtMs,
                           List<SiteView> sites, List<TunerView> tuners, List<DecisionView> decisions) { }
    public record SiteView(String id, String name, String systemName, String channelName, String kind,
                           int knownFrequencyCount, int coveredFrequencyCount, List<FrequencyView> frequencies,
                           List<String> tuners, String status) { }
    public record FrequencyView(long frequencyHz, long bandwidthHz, boolean covered, String role) { }
    public record TunerView(String id, String name, Long frequencyHz, Long usableBandwidthHz, Long dcGapHz,
                            String status, boolean available, boolean centerLocked, Long minFrequencyHz,
                            Long maxFrequencyHz, List<String> preferredFor, List<ReceivingView> receiving, String reason,
                            boolean supportsSharedWindow) { }
    public record ReceivingView(long frequencyHz, long bandwidthHz, String role, String channelName, String siteId) { }
    public record CandidateView(String tunerId, String tunerName, String reason, Long minFrequencyHz, Long maxFrequencyHz) { }
    public record DecisionView(long decisionId, long sequence, long timestamp, String siteId, String siteName,
                               String tunerId, String tunerName, String type, String outcome, String reason,
                               Long requestedFrequencyHz, Long requestedBandwidthHz, Long beforeFrequencyHz,
                               Long requestedCenterFrequencyHz, Long afterFrequencyHz, Long usableBandwidthHz,
                               Long dcGapHz, Long controlFrequencyHz, long repeatCount, long firstTimestamp,
                               long lastTimestamp, Integer coveredBefore, Integer coveredAfter,
                               List<CandidateView> candidates) { }
}

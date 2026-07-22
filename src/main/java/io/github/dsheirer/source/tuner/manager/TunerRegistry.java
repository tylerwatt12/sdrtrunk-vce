/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Neutral, read-only tuner registry.
 *
 * <p>The registry takes a small on-demand snapshot from {@link TunerManager}; it does not listen to USB callbacks,
 * retain history, mutate hardware, access configuration persistence, or create database state.  The resulting
 * {@link TunerSnapshot} records are immutable and contain stable opaque IDs that distinguish receivers of the same
 * class.  A bounded snapshot prevents an accidental discovery-source failure from creating unbounded web work.</p>
 */
public final class TunerRegistry
{
    public static final int MAXIMUM_SNAPSHOT_TUNERS = 128;
    private static final int OPAQUE_HASH_BYTES = 14;
    private static final int MAXIMUM_LABEL_LENGTH = 64;
    private static final int MAXIMUM_TEXT_LENGTH = 512;
    private final Supplier<List<DiscoveredTuner>> mDiscoveredTunerSupplier;
    private final Supplier<List<Tuner>> mLegacyTunerSupplier;

    /**
     * Creates a registry over the application tuner manager.
     */
    public TunerRegistry(TunerManager tunerManager)
    {
        TunerManager manager = Objects.requireNonNull(tunerManager, "Tuner manager cannot be null");
        mDiscoveredTunerSupplier = manager::getDiscoveredTunersForRegistry;
        mLegacyTunerSupplier = null;
    }

    /**
     * Test seam for deterministic discovery and disappearance coverage.
     */
    TunerRegistry(Supplier<List<DiscoveredTuner>> discoveredTunerSupplier)
    {
        mDiscoveredTunerSupplier = Objects.requireNonNull(discoveredTunerSupplier,
            "Discovered tuner supplier cannot be null");
        mLegacyTunerSupplier = null;
    }

    private TunerRegistry(Supplier<List<Tuner>> tunerSupplier, boolean legacyAdapter)
    {
        mDiscoveredTunerSupplier = null;
        mLegacyTunerSupplier = Objects.requireNonNull(tunerSupplier, "Tuner supplier cannot be null");
    }

    /**
     * Adapts the previous already-running tuner supplier used by spectrum tests and specialized embeddings.
     * Production web code should construct the registry with {@link TunerManager}.
     */
    public static TunerRegistry fromTuners(Supplier<List<Tuner>> tunerSupplier)
    {
        return new TunerRegistry(tunerSupplier, true);
    }

    /**
     * Returns an immutable, deterministically ordered snapshot of every currently discovered tuner.
     */
    public List<TunerSnapshot> snapshots()
    {
        return entries().stream().map(Entry::snapshot).toList();
    }

    /**
     * Resolves one current immutable snapshot by opaque ID.
     */
    public Optional<TunerSnapshot> findSnapshot(String id)
    {
        if(id == null)
        {
            return Optional.empty();
        }

        String requested = id.strip().toUpperCase(Locale.ROOT);
        return entries().stream().map(Entry::snapshot).filter(snapshot -> snapshot.id().equals(requested)).findFirst();
    }

    /**
     * Returns current initialized receiver targets for demand-owned DSP consumers.
     *
     * <p>The contained tuner is a runtime handle and must never be serialized or exposed through an HTTP response.
     * Web query handlers should use {@link #snapshots()} or {@link #findSnapshot(String)} only.</p>
     */
    public List<AvailableTunerTarget> availableTargets()
    {
        return entries().stream().filter(entry -> entry.tuner() != null && entry.snapshot().available())
            .map(entry -> new AvailableTunerTarget(entry.snapshot().id(), entry.snapshot().label(),
                entry.snapshot().tunerClass(), entry.tuner())).toList();
    }

    /**
     * Resolves one currently available runtime target by opaque ID.
     */
    public Optional<AvailableTunerTarget> findAvailableTarget(String id)
    {
        if(id == null)
        {
            return Optional.empty();
        }

        String requested = id.strip().toUpperCase(Locale.ROOT);
        return availableTargets().stream().filter(target -> target.id().equals(requested)).findFirst();
    }

    private List<Entry> entries()
    {
        List<Entry> entries = mDiscoveredTunerSupplier != null ? discoveredEntries() : legacyEntries();
        Map<String,Entry> distinct = new LinkedHashMap<>();

        for(Entry entry: entries)
        {
            distinct.putIfAbsent(entry.snapshot().id(), entry);
        }

        return disambiguateLabels(new ArrayList<>(distinct.values())).stream().sorted(Comparator
            .comparing((Entry entry) -> entry.snapshot().label(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.snapshot().id())).toList();
    }

    /**
     * Adds a small display-only ordinal when two receivers have the same passive class/type label.  This keeps
     * duplicate devices selectable without reading a serial number or preferred name from their hardware.
     */
    private static List<Entry> disambiguateLabels(List<Entry> entries)
    {
        Map<String,Integer> totals = new LinkedHashMap<>();

        for(Entry entry: entries)
        {
            totals.merge(entry.snapshot().label().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        Map<String,Integer> ordinals = new LinkedHashMap<>();
        List<Entry> labeled = new ArrayList<>(entries.size());

        for(Entry entry: entries)
        {
            TunerSnapshot snapshot = entry.snapshot();
            String key = snapshot.label().toLowerCase(Locale.ROOT);

            if(totals.getOrDefault(key, 0) > 1)
            {
                int ordinal = ordinals.merge(key, 1, Integer::sum);
                String suffix = " " + ordinal;
                int prefixLength = Math.max(1, MAXIMUM_LABEL_LENGTH - suffix.length());
                String prefix = snapshot.label().substring(0, Math.min(snapshot.label().length(), prefixLength));
                snapshot = withLabel(snapshot, prefix + suffix);
            }

            labeled.add(new Entry(snapshot, entry.tuner()));
        }

        return labeled;
    }

    private static TunerSnapshot withLabel(TunerSnapshot snapshot, String label)
    {
        return new TunerSnapshot(snapshot.id(), label, snapshot.tunerClass(), snapshot.tunerType(), snapshot.status(),
            snapshot.enabled(), snapshot.available(), snapshot.hardwareIdentifier(), snapshot.centerFrequencyHz(),
            snapshot.sampleRateHz(), snapshot.activeChannelCount(), snapshot.sampleRateLocked(),
            snapshot.centerFrequencyFixed(), snapshot.errorMessage());
    }

    private List<Entry> discoveredEntries()
    {
        List<DiscoveredTuner> discoveredTuners = safeList(mDiscoveredTunerSupplier);
        List<Entry> entries = new ArrayList<>(Math.min(discoveredTuners.size(), MAXIMUM_SNAPSHOT_TUNERS));

        for(int index = 0; index < discoveredTuners.size() && entries.size() < MAXIMUM_SNAPSHOT_TUNERS; index++)
        {
            DiscoveredTuner discoveredTuner = discoveredTuners.get(index);

            if(discoveredTuner != null)
            {
                entries.add(entry(discoveredTuner));
            }
        }

        return entries;
    }

    private List<Entry> legacyEntries()
    {
        List<Tuner> tuners = safeList(mLegacyTunerSupplier);
        List<Entry> entries = new ArrayList<>(Math.min(tuners.size(), MAXIMUM_SNAPSHOT_TUNERS));

        for(int index = 0; index < tuners.size() && entries.size() < MAXIMUM_SNAPSHOT_TUNERS; index++)
        {
            Tuner tuner = tuners.get(index);

            if(tuner != null)
            {
                entries.add(entry(tuner));
            }
        }

        return entries;
    }

    private Entry entry(DiscoveredTuner discoveredTuner)
    {
        TunerClass tunerClass = Objects.requireNonNullElse(discoveredTuner.getTunerClass(), TunerClass.UNKNOWN);
        String discoveryIdentity = bounded(discoveredTuner.getId());
        String id = opaqueId(tunerClass, discoveryIdentity);
        Tuner tuner = discoveredTuner.getTuner();
        TunerStatus status = Objects.requireNonNullElse(discoveredTuner.getTunerStatus(), TunerStatus.ERROR);
        boolean available = status.isAvailable() && tuner != null;
        TunerConfiguration configuration = discoveredTuner.getTunerConfiguration();
        TunerType tunerType = tunerType(configuration);
        String label = label(tunerClass, tunerType);
        String hardwareIdentifier = configuration != null ? safeText(configuration::getUniqueID) : null;
        Measurements measurements = measurements(tuner);
        Boolean centerFrequencyFixed = configuration != null ? configuration.isCenterFrequencyLocked() : null;
        String errorMessage = bounded(discoveredTuner.getErrorMessage());
        TunerSnapshot snapshot = new TunerSnapshot(id, label, tunerClass, tunerType, status,
            discoveredTuner.isEnabled(), available, hardwareIdentifier, measurements.centerFrequencyHz(),
            measurements.sampleRateHz(), measurements.activeChannelCount(), measurements.sampleRateLocked(),
            centerFrequencyFixed, errorMessage);
        return new Entry(snapshot, available ? tuner : null);
    }

    private Entry entry(Tuner tuner)
    {
        TunerClass tunerClass = Objects.requireNonNullElse(safe(tuner::getTunerClass), TunerClass.UNKNOWN);
        TunerType tunerType = TunerType.UNKNOWN;
        String id = opaqueId(tunerClass, "runtime:" + Integer.toUnsignedString(System.identityHashCode(tuner)));
        Measurements measurements = measurements(tuner);
        TunerSnapshot snapshot = new TunerSnapshot(id, label(tunerClass, tunerType), tunerClass, tunerType,
            TunerStatus.ENABLED, true, true, null, measurements.centerFrequencyHz(),
            measurements.sampleRateHz(), measurements.activeChannelCount(), measurements.sampleRateLocked(), null,
            null);
        return new Entry(snapshot, tuner);
    }

    private static Measurements measurements(Tuner tuner)
    {
        if(tuner == null)
        {
            return Measurements.EMPTY;
        }

        TunerController controller = safe(tuner::getTunerController);
        ChannelSourceManager channelSourceManager = safe(tuner::getChannelSourceManager);

        if(controller == null || channelSourceManager == null)
        {
            return Measurements.EMPTY;
        }

        Long centerFrequency = safe(controller::getFrequency);
        Double sampleRate = safe(controller::getSampleRate);
        Integer channelCount = safe(channelSourceManager::getTunerChannelCount);
        Boolean sampleRateLocked = safe(controller::isLockedSampleRate);
        return new Measurements(centerFrequency, sampleRate != null ? Math.round(sampleRate) : null, channelCount,
            sampleRateLocked);
    }

    private static TunerType tunerType(TunerConfiguration configuration)
    {
        return configuration != null ? Objects.requireNonNullElse(safe(configuration::getTunerType), TunerType.UNKNOWN) :
            TunerType.UNKNOWN;
    }

    private static String label(TunerClass tunerClass, TunerType tunerType)
    {
        String label;

        if(tunerType != TunerType.UNKNOWN && !tunerType.getLabel().equalsIgnoreCase(tunerClass.toString()))
        {
            label = tunerClass + " " + tunerType.getLabel();
        }
        else
        {
            label = tunerClass.toString();
        }

        label = label.strip();
        return label.substring(0, Math.min(label.length(), MAXIMUM_LABEL_LENGTH));
    }

    private static String opaqueId(TunerClass tunerClass, String stableIdentity)
    {
        String identity = stableIdentity != null && !stableIdentity.isBlank() ? stableIdentity : tunerClass.name();
        byte[] digest;

        try
        {
            digest = MessageDigest.getInstance("SHA-256").digest(
                ("sdrtrunk-vce:tuner:v1\u0000" + tunerClass.name() + "\u0000" + identity)
                    .getBytes(StandardCharsets.UTF_8));
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        StringBuilder id = new StringBuilder("TNR_");

        for(int index = 0; index < OPAQUE_HASH_BYTES; index++)
        {
            id.append(String.format(Locale.ROOT, "%02X", digest[index]));
        }

        return id.toString();
    }

    private static <T> List<T> safeList(Supplier<List<T>> supplier)
    {
        List<T> supplied = supplier != null ? supplier.get() : null;
        return supplied != null ? List.copyOf(supplied) : List.of();
    }

    private static String bounded(String value)
    {
        if(value == null)
        {
            return null;
        }

        String bounded = value.strip();

        if(bounded.isEmpty())
        {
            return null;
        }

        return bounded.length() <= MAXIMUM_TEXT_LENGTH ? bounded : bounded.substring(0, MAXIMUM_TEXT_LENGTH);
    }

    private static String safeText(Supplier<String> supplier)
    {
        return bounded(safe(supplier));
    }

    private static <T> T safe(Supplier<T> supplier)
    {
        try
        {
            return supplier.get();
        }
        catch(RuntimeException exception)
        {
            return null;
        }
    }

    /**
     * Internal runtime target for demand-owned sample consumers.  Never serialize this value.
     */
    public record AvailableTunerTarget(String id, String label, TunerClass tunerClass, Tuner tuner)
    {
        public AvailableTunerTarget
        {
            Objects.requireNonNull(id, "Available tuner target ID cannot be null");
            Objects.requireNonNull(label, "Available tuner target label cannot be null");
            Objects.requireNonNull(tunerClass, "Available tuner target class cannot be null");
            Objects.requireNonNull(tuner, "Available tuner target cannot be null");
        }
    }

    private record Entry(TunerSnapshot snapshot, Tuner tuner)
    {
    }

    private record Measurements(Long centerFrequencyHz, Long sampleRateHz, Integer activeChannelCount,
                                Boolean sampleRateLocked)
    {
        private static final Measurements EMPTY = new Measurements(null, null, null, null);
    }
}

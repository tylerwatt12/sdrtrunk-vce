/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import io.github.dsheirer.service.radioreference.RadioReferenceImportService;
import io.github.dsheirer.configuration.ConfigurationManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Imports normal talkgroup aliases into lists actually created by a wizard, independently of its RF lease. */
public final class DiscoveryAliasImportService
{
    private static final long RETENTION_MS = 900_000;
    private static final int MAXIMUM_BATCHES = 16;
    private final Operations mOperations;
    private final LongSupplier mClock;
    private final Map<String,RetainedBatch> mRetained = new LinkedHashMap<>();

    public DiscoveryAliasImportService(RadioReferenceImportService importer)
    {
        this(importer == null ? null : new Operations()
        {
            public Catalog catalog(int systemId) throws Exception
            {
                var catalog = importer.talkgroupCatalog(systemId, null, null);
                return new Catalog(catalog.catalogId(), catalog.totalItems());
            }
            public String preview(int systemId, long aliasListId, String catalogId) throws Exception
            {
                return importer.previewTalkgroups(new RadioReferenceImportService.TalkgroupImportRequest(
                    systemId, aliasListId, true, List.of(), catalogId)).previewId();
            }
            public Counts apply(String previewId)
            {
                var result = importer.applyTalkgroups(previewId);
                return new Counts(result.added(), result.updated(), result.unchanged());
            }
        });
    }

    DiscoveryAliasImportService(Operations operations) { this(operations, System::currentTimeMillis); }
    DiscoveryAliasImportService(Operations operations, LongSupplier clock)
    { mOperations = operations; mClock = Objects.requireNonNull(clock); }

    /** Saved import targets survive wizard cleanup without retaining a receiver, decoder or channel snapshot. */
    public synchronized void retain(String wizardId, Batch batch)
    {
        cleanup();
        if(mRetained.containsKey(wizardId)) return;
        if(mRetained.size() >= MAXIMUM_BATCHES) mRetained.remove(mRetained.keySet().iterator().next());
        mRetained.put(wizardId, new RetainedBatch(batch, mClock.getAsLong() + RETENTION_MS));
    }

    public Result importAliases(String wizardId, long aliasListId)
    {
        RetainedBatch retained;
        synchronized(this)
        {
            cleanup();
            retained = mRetained.get(wizardId);
            if(retained == null) throw new IllegalStateException("Alias import expired. Import aliases from RadioReference later.");
        }
        return importAliases(retained.batch(), aliasListId, () -> current(wizardId, retained));
    }

    public synchronized void clear() { mRetained.clear(); }
    private synchronized boolean current(String wizardId, RetainedBatch batch)
    { cleanup(); return mRetained.get(wizardId) == batch; }
    private void cleanup()
    { mRetained.values().removeIf(batch -> batch.expiresAtMs() <= mClock.getAsLong()); }
    private record RetainedBatch(Batch batch, long expiresAtMs) { }

    /** A native system group may use any confirmed site's directory match, but conflicting RR IDs are ambiguous. */
    static RadioReferenceDiscoveryResolver.Result groupDirectory(List<RadioReferenceDiscoveryResolver.Result> results)
    {
        Map<Integer,RadioReferenceDiscoveryResolver.Result> matched = new LinkedHashMap<>();
        results.stream().filter(Objects::nonNull).filter(RadioReferenceDiscoveryResolver.Result::matched)
            .filter(result -> result.match().rrSystemId() > 0)
            .forEach(result -> matched.putIfAbsent(result.match().rrSystemId(), result));
        if(matched.size() == 1) return matched.values().iterator().next();
        return matched.isEmpty() ? RadioReferenceDiscoveryResolver.Result.manual() :
            RadioReferenceDiscoveryResolver.Result.manual("ambiguous", "The system matched conflicting RadioReference records.");
    }

    public static final class Batch
    {
        private final Map<Long,Entry> mEntries = new LinkedHashMap<>();
        private final Map<Integer,Catalog> mCatalogs = new LinkedHashMap<>();
        private volatile boolean mRestartRequired;

        /** The directory result is receiver-verified; no browser-supplied system ID reaches this registry. */
        public synchronized void register(long aliasListId, String aliasListName, String systemName,
                                          RadioReferenceDiscoveryResolver.Result directory)
        {
            if(aliasListId <= 0) throw new IllegalArgumentException("Alias List ID must be positive");
            if(mEntries.containsKey(aliasListId)) return;
            if(mEntries.size() >= 32) throw new IllegalStateException("Too many discovery Alias Lists");
            Integer systemId = directory != null && directory.matched() && directory.match().rrSystemId() > 0 ?
                directory.match().rrSystemId() : null;
            String name = systemName == null ? "" : systemName;
            mEntries.put(aliasListId, new Entry(new Target(aliasListId,
                aliasListName == null ? "" : aliasListName.strip(), systemId, name,
                systemId != null ? "pending" : "unavailable", 0, 0, 0,
                systemId != null ? null : "No matching RadioReference system was found. Import aliases later.")));
        }

        public synchronized Result snapshot()
        {
            List<Target> targets = mEntries.values().stream().map(entry -> entry.target).toList();
            return new Result(targets, targets.stream().allMatch(target -> "imported".equals(target.state())));
        }

        private synchronized Entry entry(long aliasListId)
        {
            Entry entry = mEntries.get(aliasListId);
            if(entry == null) throw new IllegalArgumentException("Choose an Alias List created by this wizard");
            return entry;
        }

        private synchronized void restartRequired()
        {
            mRestartRequired = true;
            mEntries.values().forEach(entry -> {
                if(!List.of("imported", "unavailable").contains(entry.target.state()))
                    entry.target = entry.target.withState("restart_required",
                        "VCE needs to restart before aliases can be imported.");
            });
        }
    }

    /** A retry reuses successful results. Failed applies use a fresh revision-bound normal import plan. */
    public Result importAliases(Batch batch, long aliasListId, BooleanSupplier current)
    {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(current);
        Entry entry = batch.entry(aliasListId);
        synchronized(entry)
        {
            Target target = entry.target;
            if(List.of("imported", "restart_required").contains(target.state()) || target.systemId() == null)
                return batch.snapshot();
            entry.target = target.withState("importing", null);
            try
            {
                requireCurrent(() -> current.getAsBoolean() && !batch.mRestartRequired);
                if(mOperations == null) throw new IllegalStateException("RadioReference import is unavailable");
                Catalog catalog;
                synchronized(batch)
                {
                    catalog = batch.mCatalogs.get(target.systemId());
                }
                if(catalog == null)
                {
                    catalog = mOperations.catalog(target.systemId());
                    synchronized(batch) { batch.mCatalogs.put(target.systemId(), catalog); }
                }
                requireCurrent(() -> current.getAsBoolean() && !batch.mRestartRequired);
                Counts counts;
                if(catalog.rows() == 0) counts = new Counts(0, 0, 0);
                else
                {
                    String previewId = mOperations.preview(target.systemId(), aliasListId, catalog.id());
                    requireCurrent(() -> current.getAsBoolean() && !batch.mRestartRequired);
                    counts = mOperations.apply(previewId);
                }
                entry.target = new Target(aliasListId, target.aliasListName(), target.systemId(), target.systemName(), "imported",
                    counts.added(), counts.updated(), counts.unchanged(),
                    catalog.rows() == 0 ? "RadioReference has no talkgroups for this system." : null);
            }
            catch(ConfigurationManager.ConfigurationPublicationException exception)
            {
                batch.restartRequired();
                entry.target = target.withState("restart_required", exception.getCause() != null ?
                    "Aliases were saved, but VCE could not load the changes. Restart VCE before continuing." :
                    "VCE needs to restart before aliases can be imported.");
            }
            catch(Exception exception)
            {
                // Account changes invalidate catalog IDs, and a failed/committed apply consumes its preview.
                // Retrying from current local aliases avoids duplicates after a lost response or publication failure.
                synchronized(batch) { batch.mCatalogs.remove(target.systemId()); }
                entry.target = target.withState(batch.mRestartRequired ? "restart_required" : "failed",
                    batch.mRestartRequired ? "VCE needs to restart before aliases can be imported." :
                        "Aliases could not be imported. Retry, or import them later.");
            }
            return batch.snapshot();
        }
    }

    private static void requireCurrent(BooleanSupplier current)
    {
        if(!current.getAsBoolean()) throw new IllegalStateException("The discovery wizard was closed");
    }

    interface Operations
    {
        Catalog catalog(int systemId) throws Exception;
        String preview(int systemId, long aliasListId, String catalogId) throws Exception;
        Counts apply(String previewId) throws Exception;
    }
    record Catalog(String id, int rows) { }
    record Counts(int added, int updated, int unchanged) { }
    private static final class Entry
    {
        volatile Target target;
        Entry(Target target) { this.target = target; }
    }
    public record Target(long aliasListId, String aliasListName, Integer systemId, String systemName, String state,
                         int added, int updated, int unchanged, String message)
    {
        Target withState(String state, String message)
        { return new Target(aliasListId, aliasListName, systemId, systemName, state, added, updated, unchanged, message); }
    }
    public record Result(List<Target> targets, boolean complete)
    {
        public static final Result EMPTY = new Result(List.of(), true);
        public Result { targets = List.copyOf(targets); }
    }
}

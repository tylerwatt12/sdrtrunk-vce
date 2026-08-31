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

package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.configuration.ConfigurationIdentityAllocator;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.scanlist.ScanListModel;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;

/**
 * Command boundary shared by desktop and web Alias administration. Each command mutates a detached candidate,
 * commits one complete SQLite transaction, and then publishes only the committed rows to the runtime models.
 */
public final class AliasAdministrationService
{
    public static final int MAX_BULK_ALIASES = 10_000;
    public static final int MAX_ALIAS_LIST_NAME_LENGTH = 25;
    public static final int MAX_BROADCAST_CHANNELS = 64;
    public static final int MAX_BROADCAST_CHANNEL_NAME_LENGTH = 256;
    public static final int MAX_SCAN_LISTS = 100;
    public static final int MAX_SCAN_LIST_COVERAGE_ALIASES = 5_000;
    private static final long FX_QUEUE_TIMEOUT_SECONDS = 15L;
    private static final long JSON_SAFE_INTEGER_MASK = (1L << 53) - 1L;

    private final ConfigurationManager mConfigurationManager;
    private final boolean mUseDesktopThread;
    private MutationWorkspace mMutationWorkspace;

    public AliasAdministrationService(ConfigurationManager configurationManager)
    {
        this(configurationManager, !GraphicsEnvironment.isHeadless());
    }

    AliasAdministrationService(ConfigurationManager configurationManager, boolean useDesktopThread)
    {
        mConfigurationManager = Objects.requireNonNull(configurationManager);
        mUseDesktopThread = useDesktopThread;
    }

    /**
     * Returns the current Alias configuration revision on the configuration-model thread. Desktop editors can retain
     * this value while staging a detached form and use a revision-aware mutation when overwriting stale data matters.
     */
    public long currentRevision()
    {
        return onConfigurationThread(this::revision);
    }

    /**
     * Returns the current alias-list definitions and revision. Alias rows use the existing bounded catalog API.
     */
    public Catalog catalog()
    {
        return onConfigurationThread(this::catalogOnConfigurationThread);
    }

    /**
     * Returns one detached alias and the catalog revision at which it was read.
     */
    public AliasEntry getAlias(long aliasId)
    {
        return onConfigurationThread(() ->
        {
            Alias alias = requireAlias(aliasId);
            return new AliasEntry(revision(), copyAlias(alias),
                scanListModel().scanListIdsForAlias(alias.getId()));
        });
    }

    /**
     * Returns a bounded list of aliases whose matchers collide with the selected Alias under the same rules used by
     * the runtime Alias-list index. The exact total is retained so an administrator can tell when the display list
     * was truncated without materializing an unbounded HTTP response.
     */
    public AliasConflictEntry getAliasConflicts(long aliasId, int maximumConflicts)
    {
        if(maximumConflicts < 0)
        {
            throw new IllegalArgumentException("Maximum conflict count cannot be negative");
        }

        return onConfigurationThread(() ->
        {
            Alias source = requireAlias(aliasId);
            AliasID sourceMatcher = source.getMatchIdentifier();
            List<Alias> conflicts = new ArrayList<>(Math.min(maximumConflicts, 32));
            int conflictCount = 0;

            for(Alias candidate : aliasModel().getAliases())
            {
                if(candidate.getId() != source.getId() && candidate.getAliasListId() == source.getAliasListId() &&
                    matchersConflict(sourceMatcher, candidate.getMatchIdentifier()))
                {
                    conflictCount++;
                    if(conflicts.size() < maximumConflicts)
                    {
                        conflicts.add(copyAlias(candidate));
                    }
                }
            }

            return new AliasConflictEntry(revision(), copyAlias(source), conflicts, conflictCount,
                conflictCount > conflicts.size());
        });
    }

    /**
     * Returns matcher kinds supported by the selected protocol-owned alias list.
     */
    public Options options(long aliasListId)
    {
        return onConfigurationThread(() ->
        {
            AliasListDefinition definition = requireAliasList(aliasListId);
            ScanListConfiguration scanListConfiguration = scanListModel().configuration();
            List<String> icons = mConfigurationManager.getIconModel() != null ?
                mConfigurationManager.getIconModel().iconsProperty().stream()
                    .map(icon -> icon.getName()).filter(Objects::nonNull).sorted().toList() : List.of();
            List<String> streams = mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream()
                .map(configuration -> configuration.getName()).filter(Objects::nonNull).sorted().toList();
            return new Options(revision(), copyDefinition(definition), AliasMatchRegistry.allowed(definition), icons,
                streams, aliasModel().getGroupNames(), scanListConfiguration.scanLists(),
                scanListConfiguration.scanListIdsForUnmatchedTalkgroups(aliasListId));
        });
    }

    /** Returns one detached Alias List Defaults snapshot and the revision at which it was read. */
    public AliasListDefaultsEntry getAliasListDefaults(long aliasListId)
    {
        return onConfigurationThread(() ->
        {
            AliasListDefinition definition = requireAliasList(aliasListId);
            return new AliasListDefaultsEntry(revision(), definition.getId(), defaultsFor(definition));
        });
    }

    /** Returns every administrator-owned scan list and bounded membership counts. */
    public ScanListCatalog scanListCatalog()
    {
        return onConfigurationThread(() ->
        {
            ScanListConfiguration configuration = scanListModel().configuration();
            List<ScanListSummary> summaries = configuration.scanLists().stream().map(scanList ->
                new ScanListSummary(scanList,
                    Math.toIntExact(configuration.aliasMemberships().values().stream()
                        .filter(ids -> ids.contains(scanList.getId())).count()),
                    Math.toIntExact(configuration.unmatchedAliasListMemberships().values().stream()
                        .filter(ids -> ids.contains(scanList.getId())).count()))).toList();
            return new ScanListCatalog(revision(), summaries);
        });
    }

    /** Returns one scan list and its normalized owner IDs. */
    public ScanListEntry getScanList(long scanListId)
    {
        return onConfigurationThread(() ->
        {
            ScanList scanList = requireScanList(scanListId);
            ScanListConfiguration configuration = scanListModel().configuration();
            Set<Long> aliasIds = ownersFor(configuration.aliasMemberships(), scanListId);
            Set<Long> unmatchedAliasListIds = ownersFor(configuration.unmatchedAliasListMemberships(), scanListId);
            return new ScanListEntry(revision(), scanList, aliasIds, unmatchedAliasListIds);
        });
    }

    /**
     * Returns one bounded, detached listener-facing view of the aliases and unmatched Alias Lists routed to a scan
     * list.  The result contains display data only and cannot mutate the active Alias model.
     */
    public ScanListCoverage scanListCoverage(long scanListId)
    {
        return onConfigurationThread(() ->
        {
            ScanList scanList = requireScanList(scanListId);
            ScanListConfiguration configuration = scanListModel().configuration();
            Set<Long> aliasIds = ownersFor(configuration.aliasMemberships(), scanListId);
            Set<Long> unmatchedIds = ownersFor(configuration.unmatchedAliasListMemberships(), scanListId);
            Map<Long,AliasListDefinition> definitions = aliasModel().aliasListDefinitions().stream()
                .collect(java.util.stream.Collectors.toMap(AliasListDefinition::getId, definition -> definition));
            List<Alias> matched = new ArrayList<>(Math.min(aliasIds.size(), MAX_SCAN_LIST_COVERAGE_ALIASES));
            int matchedCount = 0;
            for(Alias alias : aliasModel().getAliases())
            {
                if(aliasIds.contains(alias.getId()))
                {
                    matchedCount++;
                    if(matched.size() < MAX_SCAN_LIST_COVERAGE_ALIASES)
                    {
                        matched.add(alias);
                    }
                }
            }
            matched.sort(Comparator.comparing((Alias alias) -> coverageText(alias.getAliasListName()),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(alias -> coverageText(alias.getGroup()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(alias -> coverageText(alias.getName()), String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(Alias::getId));
            boolean truncated = matchedCount > MAX_SCAN_LIST_COVERAGE_ALIASES;
            List<ScanListCoverageAlias> aliases = matched.stream()
                .map(alias ->
                {
                    AliasListDefinition definition = definitions.get(alias.getAliasListId());
                    AliasID matcher = alias.getMatchIdentifier();
                    return new ScanListCoverageAlias(alias.getId(), alias.getAliasListId(),
                        definition != null ? definition.getName() : alias.getAliasListName(), alias.getGroup(),
                        alias.getName(), alias.getDescription(), matcher != null ? matcher.getType().name() : null,
                        matcher != null ? matcher.toString() : null);
                }).toList();
            List<ScanListCoverageAliasList> unmatched = unmatchedIds.stream().sorted().map(definitions::get)
                .filter(Objects::nonNull).map(definition -> new ScanListCoverageAliasList(definition.getId(),
                    definition.getName(), definition.getFamily().name())).toList();
            return new ScanListCoverage(revision(), scanList, aliases, unmatched, matchedCount, truncated);
        });
    }

    private static String coverageText(String value)
    {
        return value != null ? value : "";
    }

    public ScanListMutationResult createScanList(ScanList scanList, long expectedRevision)
    {
        Objects.requireNonNull(scanList, "Scan list cannot be null");
        if(scanList.getId() != ScanList.UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("A new scan list cannot already have an ID");
        }

        return mutateScanLists(expectedRevision, () ->
        {
            if(scanListModel().scanLists().size() >= MAX_SCAN_LISTS)
            {
                throw new IllegalArgumentException("Scan lists cannot exceed " + MAX_SCAN_LISTS + " items");
            }
            ScanList prepared = new ScanList(scanList.getId(), scanList.getSortOrder(), scanList.getName(),
                scanList.getDescription(), scanList.isPublished(), scanList.isDefault());
            prepared.assignId(nextScanListId());
            scanListModel().addScanList(prepared);
            return new ScanListMutation(prepared, 1);
        });
    }

    public ScanListMutationResult updateScanList(long scanListId, ScanList replacement, long expectedRevision)
    {
        requirePositiveId(scanListId, "Scan-list ID");
        Objects.requireNonNull(replacement, "Scan list cannot be null");
        ScanList prepared = replacement.getId() == scanListId ? replacement :
            new ScanList(scanListId, replacement.getSortOrder(), replacement.getName(),
                replacement.getDescription(), replacement.isPublished(), replacement.isDefault());
        return mutateScanLists(expectedRevision, () ->
        {
            requireScanList(scanListId);
            scanListModel().updateScanList(prepared);
            return new ScanListMutation(prepared, 1);
        });
    }

    public ScanListMutationResult deleteScanList(long scanListId, long expectedRevision)
    {
        return mutateScanLists(expectedRevision, () ->
        {
            ScanList scanList = requireScanList(scanListId);
            scanListModel().removeScanList(scanListId);
            return new ScanListMutation(scanList, 1);
        });
    }

    /** Applies one bounded membership batch with one immutable model publication and one database transaction. */
    public ScanListMutationResult updateScanListMemberships(long scanListId, Collection<Long> aliasIds,
                                                             MembershipOperation operation,
                                                             long expectedRevision)
    {
        Objects.requireNonNull(operation, "Membership operation cannot be null");

        return mutateScanLists(expectedRevision, () ->
        {
            ScanList scanList = requireScanList(scanListId);
            Set<Long> aliases = validatedAliasOwners(aliasIds);
            ScanListConfiguration current = scanListModel().configuration();
            Map<Long,Set<Long>> aliasMemberships = mutableMemberships(current.aliasMemberships());
            applyMembershipOperation(aliasMemberships, scanListId, aliases, operation);
            scanListModel().replaceConfiguration(new ScanListConfiguration(current.scanLists(), aliasMemberships,
                current.unmatchedAliasListMemberships()));
            return new ScanListMutation(scanList, aliases.size());
        });
    }

    /**
     * Adds or removes every Alias in one optional Alias-list scope without requiring the browser to submit a large
     * ID collection. A null Alias-list ID means every Alias. In particular, REMOVE with a null scope removes every
     * current Alias member of the target scan list while leaving unmatched-talkgroup owners unchanged.
     */
    public ScanListMutationResult updateScanListMembershipsByScope(long scanListId, Long aliasListId,
                                                                    MembershipOperation operation,
                                                                    long expectedRevision)
    {
        Objects.requireNonNull(operation, "Membership operation cannot be null");
        if(operation == MembershipOperation.REPLACE)
        {
            throw new IllegalArgumentException("Scoped scan-list membership supports only add or remove");
        }

        return mutateScanLists(expectedRevision, () ->
        {
            ScanList scanList = requireScanList(scanListId);
            AliasListDefinition definition = aliasListId != null ? requireAliasList(aliasListId) : null;
            ScanListConfiguration current = scanListModel().configuration();
            Map<Long,Set<Long>> aliasMemberships = mutableMemberships(current.aliasMemberships());
            int affected = applyScopedMembershipOperation(aliasMemberships, scanListId, definition, operation);
            scanListModel().replaceConfiguration(new ScanListConfiguration(current.scanLists(), aliasMemberships,
                current.unmatchedAliasListMemberships()));
            return new ScanListMutation(scanList, affected);
        });
    }

    /**
     * Applies Alias and global unmatched-talkgroup owners as one scan-list membership transaction. A null owner
     * collection leaves that owner class unchanged; an empty collection participates normally and therefore clears
     * that owner class for {@link MembershipOperation#REPLACE}.
     */
    public ScanListMutationResult updateScanListMemberships(long scanListId, Collection<Long> aliasIds,
                                                             Collection<Long> unmatchedAliasListIds,
                                                             MembershipOperation operation,
                                                             long expectedRevision)
    {
        Objects.requireNonNull(operation, "Membership operation cannot be null");

        return mutateScanLists(expectedRevision, () ->
        {
            ScanList scanList = requireScanList(scanListId);
            Set<Long> aliases = validatedAliasOwners(aliasIds);
            Set<Long> unmatchedAliasLists = validatedUnmatchedAliasListOwners(unmatchedAliasListIds);
            ScanListConfiguration current = scanListModel().configuration();
            Map<Long,Set<Long>> aliasMemberships = mutableMemberships(current.aliasMemberships());
            Map<Long,Set<Long>> unmatchedMemberships =
                mutableMemberships(current.unmatchedAliasListMemberships());
            if(aliasIds != null)
            {
                applyMembershipOperation(aliasMemberships, scanListId, aliases, operation);
            }
            if(unmatchedAliasListIds != null)
            {
                applyMembershipOperation(unmatchedMemberships, scanListId, unmatchedAliasLists, operation);
            }
            scanListModel().replaceConfiguration(new ScanListConfiguration(current.scanLists(), aliasMemberships,
                unmatchedMemberships));
            return new ScanListMutation(scanList, aliases.size() + unmatchedAliasLists.size());
        });
    }

    public MutationResult createAliasList(String name, AliasListFamily family, long expectedRevision)
    {
        return createAliasList(name, family, Long.valueOf(expectedRevision));
    }

    /**
     * Creates an Alias list from a trusted desktop workflow using the current serialized model state.
     */
    public MutationResult createAliasList(String name, AliasListFamily family)
    {
        return createAliasList(name, family, null);
    }

    private MutationResult createAliasList(String name, AliasListFamily family, Long expectedRevision)
    {
        String preparedName = requireAliasListName(name);
        Objects.requireNonNull(family, "Alias-list family cannot be null");

        return mutate(expectedRevision, () ->
        {
            if(aliasModel().getAliasListDefinition(preparedName) != null)
            {
                throw new IllegalArgumentException("Alias list [" + preparedName + "] already exists");
            }

            AliasListDefinition definition = new AliasListDefinition(preparedName, family);
            definition.setId(nextAliasListId());
            aliasModel().addAliasListDefinition(definition);
            boolean factoryDefault = isFactoryDefaultAliasList(preparedName, family);
            if(factoryDefault)
            {
                scanListModel().replaceUnmatchedTalkgroupMemberships(definition.getId(),
                    Set.of(scanListModel().defaultScanList().getId()));
            }
            return new MutationTarget(definition, List.of(), 1, factoryDefault ?
                PublicationMode.SCAN_LISTS_THEN_ALIAS_LISTS : PublicationMode.ALIAS_LISTS);
        });
    }

    /** Replaces recording and streaming behavior without changing unmatched scan-list membership. */
    public MutationResult updateUnmatchedTalkgroupPolicy(long aliasListId, UnmatchedTalkgroupPolicy policy,
                                                          long expectedRevision)
    {
        return updateUnmatchedTalkgroupPolicy(aliasListId, policy, null, false, expectedRevision);
    }

    /**
     * Atomically replaces recording, streaming, and scan-list delivery used when a talkgroup or patch group has no
     * configured exact or range Alias.
     */
    public MutationResult updateUnmatchedTalkgroupPolicy(long aliasListId, UnmatchedTalkgroupPolicy policy,
                                                          Collection<Long> scanListIds, long expectedRevision)
    {
        return updateUnmatchedTalkgroupPolicy(aliasListId, policy, scanListIds, true, expectedRevision);
    }

    /** Atomically replaces all Alias List Defaults while retaining the compatibility API name. */
    public MutationResult updateAliasListDefaults(long aliasListId, AliasListDefaults defaults,
                                                   long expectedRevision)
    {
        Objects.requireNonNull(defaults, "Alias List Defaults cannot be null");
        return updateUnmatchedTalkgroupPolicy(aliasListId, defaults.unmatchedTalkgroupPolicy(),
            defaults.scanListIds(), expectedRevision);
    }

    private MutationResult updateUnmatchedTalkgroupPolicy(long aliasListId, UnmatchedTalkgroupPolicy policy,
                                                           Collection<Long> scanListIds,
                                                           boolean replaceScanListMemberships,
                                                           long expectedRevision)
    {
        Objects.requireNonNull(policy, "Unmatched talkgroup policy cannot be null");

        return mutate(expectedRevision, () ->
        {
            AliasListDefinition definition = requireAliasList(aliasListId);
            if(!supportsUnmatchedTalkgroups(definition.getFamily()))
            {
                throw new IllegalArgumentException("Unmatched talkgroup behavior is available only for P25, DMR, " +
                    "NXDN, and NBFM alias lists");
            }
            UnmatchedTalkgroupPolicy previous = definition.getUnmatchedTalkgroupPolicy();
            validatePolicyStreams(policy, previous);
            Set<Long> memberships = replaceScanListMemberships ? validatedScanListIds(scanListIds) : Set.of();
            definition.setUnmatchedTalkgroupPolicy(policy);
            if(replaceScanListMemberships)
            {
                scanListModel().replaceUnmatchedTalkgroupMemberships(aliasListId, memberships);
            }
            return new MutationTarget(definition, List.of(), 1, replaceScanListMemberships ?
                PublicationMode.SCAN_LISTS_THEN_ALIAS_LISTS : PublicationMode.ALIAS_LISTS);
        });
    }

    public DeleteImpact aliasListDeleteImpact(long aliasListId)
    {
        return onConfigurationThread(() -> deleteImpact(requireAliasList(aliasListId)));
    }

    /**
     * Returns a memory-bounded delete impact for remote administration.  Each count stops at one beyond the supplied
     * maximum so a caller can reject an oversized mutation without materializing the aliases that would be deleted.
     */
    public DeleteImpact aliasListDeleteImpact(long aliasListId, int maximumCount)
    {
        if(maximumCount < 0)
        {
            throw new IllegalArgumentException("Maximum delete-impact count cannot be negative");
        }

        return onConfigurationThread(() -> boundedDeleteImpact(requireAliasList(aliasListId), maximumCount));
    }

    public MutationResult deleteAliasList(long aliasListId, long expectedRevision, boolean confirmed)
    {
        return deleteAliasList(aliasListId, Long.valueOf(expectedRevision), confirmed);
    }

    /**
     * Deletes an Alias list from a trusted desktop workflow using the current serialized model state.
     */
    public MutationResult deleteAliasList(long aliasListId, boolean confirmed)
    {
        return deleteAliasList(aliasListId, null, confirmed);
    }

    private MutationResult deleteAliasList(long aliasListId, Long expectedRevision, boolean confirmed)
    {
        return mutate(expectedRevision, () ->
        {
            AliasListDefinition definition = requireAliasList(aliasListId);
            DeleteImpact impact = deleteImpact(definition);

            if(!confirmed)
            {
                throw new ConfirmationRequiredException(impact);
            }

            List<Alias> deletedAliases = aliasesForList(definition);
            List<Long> deletedAliasIds = deletedAliases.stream().map(Alias::getId).toList();
            aliasModel().removeAliases(deletedAliases);
            deletedAliasIds.forEach(scanListModel()::removeAlias);
            scanListModel().removeAliasList(definition.getId());
            removeAliasListDefinition(definition);

            return new MutationTarget(definition, deletedAliasIds, impact.aliasCount(), List.of(),
                PublicationMode.ALIAS_LIST_DELETE, mConfigurationManager::prepareForAliasListRefresh);
        });
    }

    public MutationResult createAlias(Alias alias, long expectedRevision)
    {
        return saveAliasRequests(List.of(AliasSaveRequest.inherit(alias)), Long.valueOf(expectedRevision));
    }

    /** Atomically creates one Alias with its initial scan-list memberships. */
    public MutationResult createAlias(Alias alias, Collection<Long> scanListIds, long expectedRevision)
    {
        return saveAliasRequests(List.of(AliasSaveRequest.explicit(alias, scanListIds)),
            Long.valueOf(expectedRevision));
    }

    /**
     * Creates an Alias from a trusted desktop workflow using the current serialized model state.
     */
    public MutationResult createAlias(Alias alias)
    {
        return saveAliasRequests(List.of(AliasSaveRequest.inherit(alias)), null);
    }

    public MutationResult replaceAlias(long aliasId, Alias replacement, long expectedRevision)
    {
        return savePreparedAliases(List.of(prepareReplacement(aliasId, replacement)),
            Long.valueOf(expectedRevision));
    }

    /** Atomically replaces one existing Alias and its scan-list memberships. */
    public MutationResult replaceAlias(long aliasId, Alias replacement, Collection<Long> scanListIds,
                                       long expectedRevision)
    {
        Alias prepared = prepareReplacement(aliasId, replacement);
        Set<Long> memberships = validatedScanListIds(scanListIds);
        return mutate(Long.valueOf(expectedRevision), () ->
        {
            MutationTarget target = saveAliasesTarget(List.of(prepared));
            scanListModel().replaceAliasMemberships(aliasId, memberships);
            return target.withPublication(PublicationMode.SCAN_LISTS_THEN_ALIASES);
        });
    }

    /**
     * Replaces an Alias from a trusted desktop workflow using the current serialized model state.
     */
    public MutationResult replaceAlias(long aliasId, Alias replacement)
    {
        return savePreparedAliases(List.of(prepareReplacement(aliasId, replacement)), null);
    }

    public MutationResult deleteAlias(long aliasId, long expectedRevision)
    {
        return deleteAliases(List.of(aliasId), Long.valueOf(expectedRevision));
    }

    /**
     * Deletes Aliases by durable identity from a trusted desktop workflow.
     */
    public MutationResult deleteAliases(List<Long> aliasIds)
    {
        return deleteAliases(aliasIds, null);
    }

    /**
     * Revision-aware batch deletion for a staged selection or confirmation dialog.
     */
    public MutationResult deleteAliases(List<Long> aliasIds, long expectedRevision)
    {
        return deleteAliases(aliasIds, Long.valueOf(expectedRevision));
    }

    /**
     * Atomically creates and replaces a mixed set of Aliases from a trusted desktop or import workflow. An ID of zero
     * creates a new row; a positive ID replaces the current row with that durable identity.
     */
    public MutationResult saveAliases(List<Alias> aliases)
    {
        return saveAliasRequests(inheritedRequests(aliases), null);
    }

    /**
     * Revision-aware form of {@link #saveAliases(List)}.
     */
    public MutationResult saveAliases(List<Alias> aliases, long expectedRevision)
    {
        return saveAliasRequests(inheritedRequests(aliases), Long.valueOf(expectedRevision));
    }

    /**
     * Saves a mixed create/update batch. New rows explicitly choose inherited or submitted routing; existing rows
     * always preserve their current scan-list memberships unless a separate replacement command is used.
     */
    public MutationResult saveAliasRequests(List<AliasSaveRequest> requests, long expectedRevision)
    {
        return saveAliasRequests(requests, Long.valueOf(expectedRevision));
    }

    public MutationResult saveAliasRequests(List<AliasSaveRequest> requests)
    {
        return saveAliasRequests(requests, null);
    }

    /**
     * Applies the fields supported by the desktop multiple-alias editor, plus compatible move and delete operations.
     */
    public MutationResult bulkEdit(BulkEdit edit, long expectedRevision)
    {
        return bulkEdit(edit, Long.valueOf(expectedRevision));
    }

    /**
     * Applies a bulk edit from a trusted desktop workflow without imposing the HTTP request-size limit.
     */
    public MutationResult bulkEdit(BulkEdit edit)
    {
        return bulkEdit(edit, null);
    }

    private MutationResult bulkEdit(BulkEdit edit, Long expectedRevision)
    {
        Objects.requireNonNull(edit, "Bulk edit cannot be null");
        List<Long> aliasIds = validatedAliasIds(edit.aliasIds());
        validateBulkFields(edit);

        return mutate(expectedRevision, () ->
        {
            List<Alias> aliases = aliasIds.stream().map(this::requireAlias).toList();

            if(edit.delete())
            {
                return deleteAliasesTarget(aliasIds, aliases);
            }

            AliasListDefinition target = edit.targetAliasListId() != null ?
                requireAliasList(edit.targetAliasListId()) : null;

            if(target != null)
            {
                for(Alias alias: aliases)
                {
                    if(!AliasMatchRegistry.isOperational(target, alias.getMatchIdentifier()))
                    {
                        throw new IllegalArgumentException("Alias [" + alias.getName() +
                            "] is not compatible with alias list [" + target.getName() + "]");
                    }

                    if(AliasMatchRegistry.isUnmatchedTalkgroupCatchAll(target, alias.getMatchIdentifier()) &&
                        !alias.belongsTo(target))
                    {
                        throw unmatchedTalkgroupCatchAllException(target);
                    }
                }
            }

            if(edit.iconName() != null)
            {
                validateIconName(edit.iconName());
            }

            validateBulkStreams(edit);

            List<Alias> replacements = aliases.stream().map(AliasAdministrationService::copyAlias).toList();

            for(Alias alias: replacements)
            {
                if(target != null)
                {
                    alias.setAliasListDefinition(target);
                }
                if(edit.color() != null)
                {
                    alias.setColor(edit.color());
                }
                if(edit.iconName() != null)
                {
                    alias.setIconName(edit.iconName());
                }
                if(edit.recordable() != null)
                {
                    alias.setRecordable(edit.recordable());
                }
                if(edit.groupOperation() == GroupOperation.SET)
                {
                    alias.setGroup(edit.group());
                }
                else if(edit.groupOperation() == GroupOperation.CLEAR)
                {
                    alias.setGroup(null);
                }

                applyStreamOperation(alias, edit.streamOperation(), edit.broadcastChannels());
            }

            MutationTarget replacementTarget = saveAliasesTarget(replacements);
            return replacementTarget.withAliasList(target);
        });
    }

    /**
     * Rewrites Alias and unmatched-talkgroup stream references after a broadcast destination is renamed.
     */
    public MutationResult renameBroadcastChannelReferences(String previousName, String updatedName)
    {
        String previous = requireName(previousName, "Previous broadcast channel name");
        String updated = requireName(updatedName, "Updated broadcast channel name");

        return mutate(null, () -> renameBroadcastChannelReferencesTarget(previous, updated));
    }

    private MutationResult mutate(Long expectedRevision, Supplier<MutationTarget> operation)
    {
        return onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
            {
                if(expectedRevision != null)
                {
                    requireRevision(expectedRevision);
                }

                MutationWorkspace workspace = beginMutationWorkspace();
                MutationTarget target;

                try
                {
                    target = operation.get();
                    mConfigurationManager.commitAndPublishAliasConfiguration(workspace.snapshot(), target.publication(),
                        target.beforePublication(), target.broadcastConfigurationRename());
                }
                catch(ConfigurationManager.ConfigurationCommitException |
                      ConfigurationIdentityAllocator.AllocationException exception)
                {
                    throw new PersistenceException("Unable to save alias configuration", exception);
                }
                finally
                {
                    mMutationWorkspace = null;
                }

                List<Long> ids = target.savedAliases().isEmpty() ? target.aliases() : target.savedAliases().stream()
                    .map(Alias::getId).toList();
                Long aliasListId = target.aliasList() != null ? target.aliasList().getId() : null;
                return new MutationResult(revision(), aliasListId, ids, target.affected());
            }));
    }

    private ScanListMutationResult mutateScanLists(long expectedRevision, Supplier<ScanListMutation> operation)
    {
        return onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision);
            MutationWorkspace workspace = beginMutationWorkspace();
            ScanListMutation mutation;

            try
            {
                mutation = operation.get();
                mConfigurationManager.commitAndPublishAliasConfiguration(workspace.snapshot(),
                    new ConfigurationManager.AliasConfigurationPublication(Set.of(), false, true, true, Set.of()),
                    null, null);
            }
            catch(ConfigurationManager.ConfigurationCommitException |
                  ConfigurationIdentityAllocator.AllocationException exception)
            {
                throw new PersistenceException("Unable to save scan-list configuration", exception);
            }
            finally
            {
                mMutationWorkspace = null;
            }

            return new ScanListMutationResult(revision(), mutation.scanList().getId(), mutation.affected());
        }));
    }

    private MutationResult savePreparedAliases(List<Alias> prepared, Long expectedRevision)
    {
        return mutate(expectedRevision, () -> saveAliasesTarget(prepared));
    }

    private MutationResult saveAliasRequests(List<AliasSaveRequest> requests, Long expectedRevision)
    {
        if(requests == null || requests.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one Alias to save");
        }

        List<PreparedAliasRequest> prepared = new ArrayList<>(requests.size());
        Set<Alias> instances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(AliasSaveRequest request: requests)
        {
            Objects.requireNonNull(request, "Alias save request cannot be null");
            if(!instances.add(request.alias()))
            {
                throw new IllegalArgumentException("Aliases to save must be unique instances");
            }
            Alias alias = request.alias().getId() == Alias.UNASSIGNED_ID ? prepareNewAlias(request.alias()) :
                prepareReplacement(request.alias().getId(), request.alias());
            prepared.add(new PreparedAliasRequest(alias, request.creationRouting(), request.scanListIds()));
        }

        return mutate(expectedRevision, () -> saveAliasRequestsTarget(prepared));
    }

    private MutationTarget saveAliasRequestsTarget(List<PreparedAliasRequest> requests)
    {
        for(PreparedAliasRequest request: requests)
        {
            Alias alias = request.alias();
            if(alias.getId() == Alias.UNASSIGNED_ID && request.creationRouting() == CreationRouting.INHERIT_DEFAULTS)
            {
                applyDefaults(alias);
            }
        }

        MutationTarget target = saveAliasesTarget(requests.stream().map(PreparedAliasRequest::alias).toList());
        boolean membershipsChanged = false;
        for(PreparedAliasRequest request: requests)
        {
            Alias alias = request.alias();
            if(request.wasNew())
            {
                Set<Long> memberships = request.creationRouting() == CreationRouting.INHERIT_DEFAULTS ?
                    inheritedScanListIds(alias) : validatedScanListIds(request.scanListIds());
                scanListModel().replaceAliasMemberships(alias.getId(), memberships);
                membershipsChanged |= !memberships.isEmpty();
            }
        }
        return membershipsChanged ? target.withPublication(PublicationMode.SCAN_LISTS_THEN_ALIASES) : target;
    }

    private void applyDefaults(Alias alias)
    {
        if(!inheritsAliasListDefaults(alias))
        {
            return;
        }

        AliasListDefaults defaults = defaultsFor(resolveAliasList(alias));
        alias.setRecordable(defaults.isRecordEnabled());
        alias.setBroadcastChannels(defaults.streamDestinationNames().stream().map(BroadcastChannel::new).toList());
    }

    private Set<Long> inheritedScanListIds(Alias alias)
    {
        return inheritsAliasListDefaults(alias) ? defaultsFor(resolveAliasList(alias)).scanListIds() : Set.of();
    }

    private AliasListDefaults defaultsFor(AliasListDefinition definition)
    {
        return new AliasListDefaults(definition.getUnmatchedTalkgroupPolicy(),
            scanListModel().scanListIdsForUnmatchedTalkgroups(definition.getId()));
    }

    private static boolean inheritsAliasListDefaults(Alias alias)
    {
        return alias != null && (alias.getMatchIdentifier() instanceof Talkgroup ||
            alias.getMatchIdentifier() instanceof TalkgroupRange);
    }

    private static List<AliasSaveRequest> inheritedRequests(List<Alias> aliases)
    {
        if(aliases == null)
        {
            return null;
        }
        return aliases.stream().map(alias -> alias != null && alias.getId() == Alias.UNASSIGNED_ID ?
            AliasSaveRequest.inherit(alias) : AliasSaveRequest.preserve(alias)).toList();
    }

    private long nextAliasListId()
    {
        return mConfigurationManager.nextAliasListIds(aliasModel().aliasListDefinitions().stream()
            .map(AliasListDefinition::getId).toList(), 1).getFirst();
    }

    private long nextScanListId()
    {
        return mConfigurationManager.nextScanListIds(scanListModel().scanLists().stream()
            .map(ScanList::getId).toList(), 1).getFirst();
    }

    /** Validates and installs a batch in the detached candidate by durable identity. */
    private MutationTarget saveAliasesTarget(List<Alias> prepared)
    {
        return saveAliasesTarget(prepared, Set.of());
    }

    private MutationTarget saveAliasesTarget(List<Alias> prepared, Set<String> additionalConfiguredStreams)
    {
        if(prepared == null || prepared.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one Alias to save");
        }

        Set<Alias> creates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        prepared.stream().filter(alias -> alias != null && alias.getId() == Alias.UNASSIGNED_ID)
            .forEach(creates::add);
        List<Long> allocatedIds = mConfigurationManager.nextAliasIds(aliasModel().getAliases().stream()
            .map(Alias::getId).toList(), creates.size());
        int allocatedIndex = 0;
        for(Alias alias: prepared)
        {
            if(creates.contains(alias))
            {
                alias.setId(allocatedIds.get(allocatedIndex++));
            }
        }

        Set<Long> persistedIds = new HashSet<>();
        for(Alias alias: prepared)
        {
            if(alias == null)
            {
                throw new IllegalArgumentException("Alias cannot be null");
            }

            Alias current = null;
            if(!creates.contains(alias))
            {
                if(!persistedIds.add(alias.getId()))
                {
                    throw new IllegalArgumentException("Alias IDs to save must be unique");
                }

                current = requireAlias(alias.getId());
            }

            AliasListDefinition definition = resolveAliasList(alias);
            validateAlias(alias, definition, current, additionalConfiguredStreams);
            alias.setAliasListDefinition(definition);
        }

        aliasModel().addAliases(prepared);
        if(mMutationWorkspace != null)
        {
            prepared.forEach(alias -> mMutationWorkspace.aliasesById().put(alias.getId(), alias));
        }
        return new MutationTarget(null, List.of(), prepared.size(), prepared, PublicationMode.ALIASES, null);
    }

    private MutationResult deleteAliases(List<Long> aliasIds, Long expectedRevision)
    {
        List<Long> validatedIds = validatedAliasIds(aliasIds);

        return mutate(expectedRevision, () -> deleteAliasesTarget(validatedIds,
            validatedIds.stream().map(this::requireAlias).toList()));
    }

    private MutationTarget deleteAliasesTarget(List<Long> aliasIds, List<Alias> aliases)
    {
        aliasModel().removeAliases(aliases);
        if(mMutationWorkspace != null)
        {
            aliasIds.forEach(mMutationWorkspace.aliasesById()::remove);
        }
        aliasIds.forEach(scanListModel()::removeAlias);
        return new MutationTarget(null, aliasIds, aliases.size(), PublicationMode.ALIASES_THEN_SCAN_LISTS);
    }

    private MutationTarget renameBroadcastChannelReferencesTarget(String previousName, String updatedName)
    {
        if(previousName.equals(updatedName))
        {
            return new MutationTarget(null, List.of(), 0, PublicationMode.ALIASES);
        }
        if(!isConfiguredStream(previousName))
        {
            throw new IllegalArgumentException("Broadcast channel [" + previousName + "] does not exist");
        }
        if(isConfiguredStream(updatedName))
        {
            throw new IllegalArgumentException("Broadcast channel [" + updatedName + "] already exists");
        }

        List<Alias> replacements = aliasModel().getAliases().stream()
            .filter(alias -> alias.hasBroadcastChannel(previousName))
            .map(AliasAdministrationService::copyAlias)
            .toList();

        for(Alias replacement: replacements)
        {
            replacement.removeBroadcastChannel(previousName);
            replacement.addBroadcastChannel(updatedName);
        }

        List<AliasListPolicyState> policyStates = new ArrayList<>();
        for(AliasListDefinition definition: aliasModel().aliasListDefinitions())
        {
            UnmatchedTalkgroupPolicy previous = definition.getUnmatchedTalkgroupPolicy();
            if(previous.getStreamDestinationNames().contains(previousName))
            {
                List<String> destinations = new ArrayList<>();
                for(String destination: previous.getStreamDestinationNames())
                {
                    String replacement = destination.equals(previousName) ? updatedName : destination;
                    if(!destinations.contains(replacement))
                    {
                        destinations.add(replacement);
                    }
                }

                UnmatchedTalkgroupPolicy updated = new UnmatchedTalkgroupPolicy(previous.isRecordEnabled(),
                    destinations);
                validatePolicyStreams(updated, previous, Set.of(updatedName));
                policyStates.add(new AliasListPolicyState(definition, updated));
            }
        }

        MutationTarget aliasesTarget = replacements.isEmpty() ?
            new MutationTarget(null, List.of(), 0, PublicationMode.ALIASES) :
            saveAliasesTarget(replacements, Set.of(updatedName));

        if(!policyStates.isEmpty())
        {
            policyStates.forEach(state -> state.definition().setUnmatchedTalkgroupPolicy(state.updated()));
        }

        PublicationMode publicationMode = policyStates.isEmpty() ? PublicationMode.ALIASES :
            PublicationMode.ALIAS_LISTS;
        return new MutationTarget(null, List.of(), replacements.size() + policyStates.size(),
            aliasesTarget.savedAliases(), publicationMode, null,
            new ConfigurationManager.BroadcastConfigurationRename(previousName, updatedName));
    }

    private Catalog catalogOnConfigurationThread()
    {
        List<AliasListDefinition> definitions = aliasModel().aliasListDefinitions().stream()
            .map(AliasAdministrationService::copyDefinition).toList();
        Map<Long,Integer> aliasCounts = new LinkedHashMap<>();
        Map<Long,Integer> channelCounts = new LinkedHashMap<>();
        Map<String,Long> aliasListIdsByName = new java.util.HashMap<>();
        for(AliasListDefinition definition : definitions)
        {
            aliasCounts.put(definition.getId(), 0);
            channelCounts.put(definition.getId(), 0);
            aliasListIdsByName.put(definition.getName().toLowerCase(Locale.ROOT), definition.getId());
        }
        for(Alias alias : aliasModel().getAliases())
        {
            if(aliasCounts.containsKey(alias.getAliasListId()))
            {
                aliasCounts.merge(alias.getAliasListId(), 1, Integer::sum);
            }
        }
        for(Channel channel : mConfigurationManager.getChannelModel().getChannels())
        {
            String aliasListName = channel != null ? channel.getAliasListName() : null;
            Long aliasListId = aliasListName != null ?
                aliasListIdsByName.get(aliasListName.toLowerCase(Locale.ROOT)) : null;
            if(aliasListId != null)
            {
                channelCounts.merge(aliasListId, 1, Integer::sum);
            }
        }
        ScanListConfiguration scanListConfiguration = scanListModel().configuration();
        return new Catalog(revision(), definitions, scanListConfiguration.scanLists(),
            scanListConfiguration.unmatchedAliasListMemberships(), aliasCounts, channelCounts);
    }

    private DeleteImpact deleteImpact(AliasListDefinition definition)
    {
        int aliasCount = Math.toIntExact(aliasModel().getAliases().stream()
            .filter(alias -> alias.belongsTo(definition)).count());
        int channelCount = Math.toIntExact(mConfigurationManager.getChannelModel().getChannels().stream()
            .filter(channel -> matchesList(channel, definition)).count());
        return new DeleteImpact(revision(), definition.getId(), definition.getName(), aliasCount, channelCount);
    }

    private DeleteImpact boundedDeleteImpact(AliasListDefinition definition, int maximumCount)
    {
        long limit = (long)maximumCount + 1L;
        int aliasCount = (int)aliasModel().getAliases().stream()
            .filter(alias -> alias.belongsTo(definition)).limit(limit).count();
        int channelCount = (int)mConfigurationManager.getChannelModel().getChannels().stream()
            .filter(channel -> matchesList(channel, definition)).limit(limit).count();
        return new DeleteImpact(revision(), definition.getId(), definition.getName(), aliasCount, channelCount);
    }

    private List<Alias> aliasesForList(AliasListDefinition definition)
    {
        return aliasModel().getAliases().stream().filter(alias -> alias.belongsTo(definition)).toList();
    }

    private static boolean matchesList(Channel channel, AliasListDefinition definition)
    {
        return channel != null && channel.getAliasListName() != null &&
            definition.getName().equalsIgnoreCase(channel.getAliasListName());
    }

    private Alias prepareNewAlias(Alias source)
    {
        Objects.requireNonNull(source, "Alias cannot be null");
        Alias prepared = AliasFactory.copyOf(source);
        prepared.setId(Alias.UNASSIGNED_ID);
        requireName(prepared.getName(), "Alias name");
        return prepared;
    }

    private Alias prepareReplacement(long aliasId, Alias source)
    {
        Alias prepared = prepareNewAlias(source);
        prepared.setId(requirePositiveId(aliasId, "Alias ID"));
        return prepared;
    }

    private AliasListDefinition resolveAliasList(Alias alias)
    {
        AliasListDefinition definition = alias.getAliasListId() > AliasListDefinition.UNASSIGNED_ID ?
            aliasModel().getAliasListDefinition(alias.getAliasListId()) :
            aliasModel().getAliasListDefinition(alias.getAliasListName());

        if(definition == null)
        {
            throw new NotFoundException("Alias list was not found");
        }

        return definition;
    }

    private void removeAliasListDefinition(AliasListDefinition definition)
    {
        aliasModel().removeAliasListDefinition(definition);
    }

    private void validateAlias(Alias alias, AliasListDefinition definition, Alias previous)
    {
        validateAlias(alias, definition, previous, Set.of());
    }

    private void validateAlias(Alias alias, AliasListDefinition definition, Alias previous,
                               Set<String> additionalConfiguredStreams)
    {
        requireName(alias.getName(), "Alias name");
        AliasID matcher = alias.getMatchIdentifier();
        if(!AliasMatchRegistry.isOperational(definition, matcher))
        {
            throw new IllegalArgumentException("Alias matcher [" + matcher + "] is not supported by alias list [" +
                definition.getName() + "]");
        }

        if(AliasMatchRegistry.isUnmatchedTalkgroupCatchAll(definition, matcher) &&
            !retainsExistingCatchAll(previous, definition, matcher))
        {
            throw unmatchedTalkgroupCatchAllException(definition);
        }

        Set<BroadcastChannel> broadcastChannels = alias.getBroadcastChannels();

        if(broadcastChannels.size() > MAX_BROADCAST_CHANNELS)
        {
            throw new IllegalArgumentException("An Alias cannot have more than " + MAX_BROADCAST_CHANNELS +
                " broadcast channels");
        }

        for(BroadcastChannel channel: broadcastChannels)
        {
            if(channel == null || channel.getChannelName() == null || channel.getChannelName().isBlank())
            {
                throw new IllegalArgumentException("Broadcast channel names cannot be blank");
            }

            if(channel.getChannelName().length() > MAX_BROADCAST_CHANNEL_NAME_LENGTH)
            {
                throw new IllegalArgumentException("Broadcast channel names cannot exceed " +
                    MAX_BROADCAST_CHANNEL_NAME_LENGTH + " characters");
            }

            if(!isConfiguredStream(channel.getChannelName()) &&
                !additionalConfiguredStreams.contains(channel.getChannelName()) &&
                (previous == null || !previous.hasBroadcastChannel(channel.getChannelName())))
            {
                throw new IllegalArgumentException("Broadcast channel [" + channel.getChannelName() +
                    "] does not exist");
            }
        }

        if(alias.getIconName() != null &&
            (previous == null || !Objects.equals(previous.getIconName(), alias.getIconName())))
        {
            validateIconName(alias.getIconName());
        }
    }

    private static boolean retainsExistingCatchAll(Alias previous, AliasListDefinition definition, AliasID matcher)
    {
        AliasID previousMatcher = previous != null ? previous.getMatchIdentifier() : null;
        return previousMatcher != null && previous.belongsTo(definition) && previousMatcher.matches(matcher);
    }

    private static IllegalArgumentException unmatchedTalkgroupCatchAllException(AliasListDefinition definition)
    {
        return new IllegalArgumentException("A full talkgroup range cannot be stored as a normal alias in [" +
            definition.getName() + "]. Use the alias list's Unmatched Talkgroups settings instead.");
    }

    private boolean isConfiguredStream(String channelName)
    {
        return channelName != null && mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream()
            .anyMatch(configuration -> channelName.equals(configuration.getName()));
    }

    private void validatePolicyStreams(UnmatchedTalkgroupPolicy policy, UnmatchedTalkgroupPolicy previous)
    {
        validatePolicyStreams(policy, previous, Set.of());
    }

    private void validatePolicyStreams(UnmatchedTalkgroupPolicy policy, UnmatchedTalkgroupPolicy previous,
                                       Set<String> additionalConfiguredStreams)
    {
        if(policy.getStreamDestinationNames().size() > MAX_BROADCAST_CHANNELS)
        {
            throw new IllegalArgumentException("Unmatched Talkgroups cannot have more than " +
                MAX_BROADCAST_CHANNELS + " broadcast channels");
        }

        Set<String> previousNames = previous != null ?
            Set.copyOf(previous.getStreamDestinationNames()) : Set.of();

        for(String channelName: policy.getStreamDestinationNames())
        {
            if(channelName == null || channelName.isBlank() ||
                channelName.length() > MAX_BROADCAST_CHANNEL_NAME_LENGTH)
            {
                throw new IllegalArgumentException("Broadcast channel names must contain between 1 and " +
                    MAX_BROADCAST_CHANNEL_NAME_LENGTH + " characters");
            }

            if(!isConfiguredStream(channelName) && !additionalConfiguredStreams.contains(channelName) &&
                !previousNames.contains(channelName))
            {
                throw new IllegalArgumentException("Broadcast channel [" + channelName + "] does not exist");
            }
        }
    }

    private static boolean supportsUnmatchedTalkgroups(AliasListFamily family)
    {
        return family == AliasListFamily.P25 || family == AliasListFamily.DMR || family == AliasListFamily.NXDN ||
            family == AliasListFamily.NBFM;
    }

    private static boolean isFactoryDefaultAliasList(String name, AliasListFamily family)
    {
        return family != null && family.getDefaultAliasListName().equalsIgnoreCase(name);
    }

    private void validateBulkStreams(BulkEdit edit)
    {
        if(edit.streamOperation() == null || edit.streamOperation() == StreamOperation.CLEAR ||
            edit.streamOperation() == StreamOperation.REMOVE)
        {
            return;
        }

        for(String channel: edit.broadcastChannels())
        {
            if(!isConfiguredStream(channel))
            {
                throw new IllegalArgumentException("Broadcast channel [" + channel + "] does not exist");
            }
        }
    }

    private static void applyStreamOperation(Alias alias, StreamOperation operation, List<String> channelNames)
    {
        if(operation == null)
        {
            return;
        }

        switch(operation)
        {
            case ADD -> channelNames.forEach(alias::addBroadcastChannel);
            case REMOVE -> channelNames.forEach(alias::removeBroadcastChannel);
            case REPLACE -> alias.setBroadcastChannels(channelNames.stream().map(BroadcastChannel::new).toList());
            case CLEAR -> alias.setBroadcastChannels(List.of());
        }
    }

    private void validateIconName(String iconName)
    {
        if(iconName == null)
        {
            return;
        }
        if(iconName.isBlank() || mConfigurationManager.getIconModel() == null ||
            mConfigurationManager.getIconModel().iconsProperty().stream()
            .noneMatch(icon -> iconName.equals(icon.getName())))
        {
            throw new IllegalArgumentException("Icon [" + iconName + "] does not exist");
        }
    }

    private static void validateBulkFields(BulkEdit edit)
    {
        boolean hasEdit = edit.targetAliasListId() != null || edit.color() != null || edit.iconName() != null ||
            edit.recordable() != null || edit.groupOperation() != null ||
            edit.streamOperation() != null;

        if(edit.delete() && hasEdit)
        {
            throw new IllegalArgumentException("Delete cannot be combined with other bulk changes");
        }
        if(!edit.delete() && !hasEdit)
        {
            throw new IllegalArgumentException("Bulk edit does not contain a change");
        }
        if(edit.targetAliasListId() != null)
        {
            requirePositiveId(edit.targetAliasListId(), "Alias-list ID");
        }
        if(edit.iconName() != null && edit.iconName().isBlank())
        {
            throw new IllegalArgumentException("Icon name cannot be blank");
        }
        if(edit.groupOperation() == GroupOperation.SET && (edit.group() == null || edit.group().isBlank()))
        {
            throw new IllegalArgumentException("Group is required for a SET operation");
        }
        if(edit.groupOperation() != GroupOperation.SET && edit.group() != null)
        {
            throw new IllegalArgumentException("Group can only be supplied for a SET operation");
        }
        if(edit.streamOperation() == null && edit.broadcastChannels() != null)
        {
            throw new IllegalArgumentException("Stream operation is required when broadcast channels are supplied");
        }
        if(edit.streamOperation() != null)
        {
            List<String> channels = edit.broadcastChannels();
            boolean requiresChannels = edit.streamOperation() != StreamOperation.CLEAR;

            if(requiresChannels && (channels == null || channels.isEmpty()))
            {
                throw new IllegalArgumentException("Select at least one broadcast channel");
            }
            if(!requiresChannels && channels != null && !channels.isEmpty())
            {
                throw new IllegalArgumentException("Broadcast channels cannot be supplied for a CLEAR operation");
            }
            if(channels != null)
            {
                Set<String> unique = new HashSet<>();
                for(String channel: channels)
                {
                    if(channel == null || channel.isBlank() || !unique.add(channel))
                    {
                        throw new IllegalArgumentException("Broadcast channel names must be nonblank and unique");
                    }
                }
            }
        }
    }

    private static List<Long> validatedAliasIds(List<Long> aliasIds)
    {
        if(aliasIds == null || aliasIds.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one alias");
        }

        Set<Long> unique = new HashSet<>();
        for(Long id: aliasIds)
        {
            if(id == null || id <= Alias.UNASSIGNED_ID || !unique.add(id))
            {
                throw new IllegalArgumentException("Alias IDs must be positive and unique");
            }
        }

        return List.copyOf(aliasIds);
    }

    private Set<Long> validatedAliasOwners(Collection<Long> aliasIds)
    {
        Set<Long> validated = new LinkedHashSet<>();
        if(aliasIds != null)
        {
            for(Long aliasId : aliasIds)
            {
                if(aliasId == null || aliasId <= Alias.UNASSIGNED_ID || !validated.add(aliasId))
                {
                    throw new IllegalArgumentException("Alias IDs must be positive and unique");
                }
                requireAlias(aliasId);
            }
        }
        return Set.copyOf(validated);
    }

    private Set<Long> validatedUnmatchedAliasListOwners(Collection<Long> aliasListIds)
    {
        Set<Long> validated = new LinkedHashSet<>();
        if(aliasListIds != null)
        {
            for(Long aliasListId : aliasListIds)
            {
                if(aliasListId == null || aliasListId <= AliasListDefinition.UNASSIGNED_ID ||
                    !validated.add(aliasListId))
                {
                    throw new IllegalArgumentException("Unmatched Alias-list IDs must be positive and unique");
                }
                AliasListDefinition definition = requireAliasList(aliasListId);
                if(!supportsUnmatchedTalkgroups(definition.getFamily()))
                {
                    throw new IllegalArgumentException("Unmatched talkgroup behavior is available only for P25, " +
                        "DMR, NXDN, and NBFM alias lists");
                }
            }
        }
        return Set.copyOf(validated);
    }

    private ScanList requireScanList(long scanListId)
    {
        requirePositiveId(scanListId, "Scan-list ID");
        ScanList scanList = scanListModel().scanList(scanListId);
        if(scanList == null)
        {
            throw new NotFoundException("Scan list [" + scanListId + "] was not found");
        }
        return scanList;
    }

    private static Set<Long> ownersFor(Map<Long,Set<Long>> memberships, long scanListId)
    {
        Set<Long> owners = new LinkedHashSet<>();
        memberships.forEach((ownerId, scanListIds) ->
        {
            if(scanListIds.contains(scanListId))
            {
                owners.add(ownerId);
            }
        });
        return Set.copyOf(owners);
    }

    private static Map<Long,Set<Long>> mutableMemberships(Map<Long,Set<Long>> source)
    {
        Map<Long,Set<Long>> copy = new LinkedHashMap<>();
        source.forEach((ownerId, scanListIds) -> copy.put(ownerId, new LinkedHashSet<>(scanListIds)));
        return copy;
    }

    private static void applyMembershipOperation(Map<Long,Set<Long>> memberships, long scanListId,
                                                  Set<Long> selectedOwners, MembershipOperation operation)
    {
        if(operation == MembershipOperation.REPLACE)
        {
            memberships.values().forEach(ids -> ids.remove(scanListId));
            memberships.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        for(Long ownerId : selectedOwners)
        {
            if(operation == MembershipOperation.REMOVE)
            {
                Set<Long> scanListIds = memberships.get(ownerId);
                if(scanListIds != null)
                {
                    scanListIds.remove(scanListId);
                    if(scanListIds.isEmpty())
                    {
                        memberships.remove(ownerId);
                    }
                }
            }
            else
            {
                memberships.computeIfAbsent(ownerId, ignored -> new LinkedHashSet<>()).add(scanListId);
            }
        }
    }

    private int applyScopedMembershipOperation(Map<Long,Set<Long>> memberships, long scanListId,
                                                AliasListDefinition definition, MembershipOperation operation)
    {
        int affected = 0;

        if(operation == MembershipOperation.REMOVE && definition == null)
        {
            for(var iterator = memberships.entrySet().iterator(); iterator.hasNext();)
            {
                Map.Entry<Long,Set<Long>> entry = iterator.next();
                if(entry.getValue().remove(scanListId))
                {
                    affected++;
                    if(entry.getValue().isEmpty())
                    {
                        iterator.remove();
                    }
                }
            }
            return affected;
        }

        for(Alias alias : aliasModel().getAliases())
        {
            if(definition != null && !alias.belongsTo(definition))
            {
                continue;
            }

            if(operation == MembershipOperation.ADD)
            {
                if(memberships.computeIfAbsent(alias.getId(), ignored -> new LinkedHashSet<>()).add(scanListId))
                {
                    affected++;
                }
            }
            else
            {
                Set<Long> scanListIds = memberships.get(alias.getId());
                if(scanListIds != null && scanListIds.remove(scanListId))
                {
                    affected++;
                    if(scanListIds.isEmpty())
                    {
                        memberships.remove(alias.getId());
                    }
                }
            }
        }

        return affected;
    }

    private static boolean matchersConflict(AliasID first, AliasID second)
    {
        if(first == null || second == null || first.getType() != second.getType())
        {
            return false;
        }

        return switch(first.getType())
        {
            case TALKGROUP -> first instanceof Talkgroup firstTalkgroup && second instanceof Talkgroup secondTalkgroup &&
                lookupProtocol(firstTalkgroup.getProtocol()) == lookupProtocol(secondTalkgroup.getProtocol()) &&
                firstTalkgroup.getValue() == secondTalkgroup.getValue();
            case TALKGROUP_RANGE -> first instanceof TalkgroupRange firstRange &&
                second instanceof TalkgroupRange secondRange &&
                lookupProtocol(firstRange.getProtocol()) == lookupProtocol(secondRange.getProtocol()) &&
                firstRange.getMinTalkgroup() <= secondRange.getMaxTalkgroup() &&
                secondRange.getMinTalkgroup() <= firstRange.getMaxTalkgroup();
            case RADIO_ID -> first instanceof Radio firstRadio && second instanceof Radio secondRadio &&
                lookupProtocol(firstRadio.getProtocol()) == lookupProtocol(secondRadio.getProtocol()) &&
                firstRadio.getValue() == secondRadio.getValue();
            case RADIO_ID_RANGE -> first instanceof RadioRange firstRange && second instanceof RadioRange secondRange &&
                lookupProtocol(firstRange.getProtocol()) == lookupProtocol(secondRange.getProtocol()) &&
                firstRange.getMinRadio() <= secondRange.getMaxRadio() &&
                secondRange.getMinRadio() <= firstRange.getMaxRadio();
            case STATUS -> first instanceof UserStatusID firstStatus && second instanceof UserStatusID secondStatus &&
                firstStatus.getStatus() == secondStatus.getStatus();
            case UNIT_STATUS -> first instanceof UnitStatusID firstStatus &&
                second instanceof UnitStatusID secondStatus && firstStatus.getStatus() == secondStatus.getStatus();
            case DCS -> first instanceof Dcs firstDcs && second instanceof Dcs secondDcs && firstDcs.isValid() &&
                secondDcs.isValid() && Objects.equals(firstDcs.getDCSCode(), secondDcs.getDCSCode());
            case TONES -> first instanceof TonesID firstTones && second instanceof TonesID secondTones &&
                toneSequencesEqual(firstTones, secondTones);
            default -> false;
        };
    }

    /** Matches the persisted overlap definition, where both tone order and duration are significant. */
    private static boolean toneSequencesEqual(TonesID first, TonesID second)
    {
        if(first.getToneSequence() == null || second.getToneSequence() == null)
        {
            return false;
        }

        List<Tone> firstTones = first.getToneSequence().getTones();
        List<Tone> secondTones = second.getToneSequence().getTones();
        if(firstTones.isEmpty() || firstTones.size() != secondTones.size())
        {
            return false;
        }

        for(int index = 0; index < firstTones.size(); index++)
        {
            Tone firstTone = firstTones.get(index);
            Tone secondTone = secondTones.get(index);
            if(firstTone == null || secondTone == null || firstTone.getAmbeTone() != secondTone.getAmbeTone() ||
                firstTone.getDuration() != secondTone.getDuration())
            {
                return false;
            }
        }

        return true;
    }

    private static Protocol lookupProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
    }

    private Set<Long> validatedScanListIds(Collection<Long> scanListIds)
    {
        if(scanListIds == null)
        {
            throw new IllegalArgumentException("Scan-list IDs cannot be null");
        }

        Set<Long> unique = new HashSet<>();
        for(Long id : scanListIds)
        {
            if(id == null || id <= ScanList.UNASSIGNED_ID || !unique.add(id))
            {
                throw new IllegalArgumentException("Scan-list IDs must be positive and unique");
            }
            if(scanListModel().scanList(id) == null)
            {
                throw new NotFoundException("Scan list [" + id + "] was not found");
            }
        }

        return Set.copyOf(unique);
    }

    private void requireRevision(long expectedRevision)
    {
        long current = revision();
        if(expectedRevision != current)
        {
            throw new StaleRevisionException(expectedRevision, current);
        }
    }

    private Alias requireAlias(long aliasId)
    {
        requirePositiveId(aliasId, "Alias ID");
        Alias alias = mMutationWorkspace != null ? mMutationWorkspace.aliasesById().get(aliasId) :
            aliasModel().getAlias(aliasId);
        if(alias == null)
        {
            throw new NotFoundException("Alias [" + aliasId + "] was not found");
        }
        return alias;
    }

    private AliasListDefinition requireAliasList(long aliasListId)
    {
        requirePositiveId(aliasListId, "Alias-list ID");
        AliasListDefinition definition = aliasModel().getAliasListDefinition(aliasListId);
        if(definition == null)
        {
            throw new NotFoundException("Alias list [" + aliasListId + "] was not found");
        }
        return definition;
    }

    private MutationWorkspace createMutationWorkspace()
    {
        AliasConfigurationSnapshot snapshot = mConfigurationManager.createDetachedAliasConfigurationSnapshot();
        AliasModel aliases = new AliasModel();
        aliases.setAliasListDefinitions(snapshot.definitions());
        aliases.addAliases(snapshot.aliases());
        Map<Long,Alias> aliasesById = new HashMap<>();
        aliases.getAliases().forEach(alias -> aliasesById.put(alias.getId(), alias));
        ScanListModel scanLists = new ScanListModel();
        scanLists.replaceConfiguration(snapshot.scanLists());
        return new MutationWorkspace(aliases, scanLists, aliasesById);
    }

    private MutationWorkspace beginMutationWorkspace()
    {
        if(mMutationWorkspace != null)
        {
            throw new IllegalStateException("Nested Alias configuration mutations are not supported");
        }

        mMutationWorkspace = createMutationWorkspace();
        return mMutationWorkspace;
    }

    private AliasModel aliasModel()
    {
        return mMutationWorkspace != null ? mMutationWorkspace.aliasModel() :
            mConfigurationManager.getAliasModel();
    }

    private ScanListModel scanListModel()
    {
        return mMutationWorkspace != null ? mMutationWorkspace.scanListModel() :
            mConfigurationManager.getScanListModel();
    }

    private <T> T onConfigurationThread(Supplier<T> operation)
    {
        Objects.requireNonNull(operation);
        requireInitialized();

        if(!mUseDesktopThread)
        {
            AtomicReference<T> result = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            mConfigurationManager.runHeadlessWebConfigurationTask(() ->
            {
                try
                {
                    requireInitialized();
                    result.set(operation.get());
                }
                catch(RuntimeException exception)
                {
                    failure.set(exception);
                }
            });
            if(failure.get() != null)
            {
                throw failure.get();
            }
            return result.get();
        }

        if(Platform.isFxApplicationThread())
        {
            requireInitialized();
            return operation.get();
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<DispatchState> state = new AtomicReference<>(DispatchState.QUEUED);
        Platform.runLater(() ->
        {
            if(!state.compareAndSet(DispatchState.QUEUED, DispatchState.RUNNING))
            {
                return;
            }

            try
            {
                requireInitialized();
                result.complete(operation.get());
            }
            catch(Throwable throwable)
            {
                result.completeExceptionally(throwable);
            }
        });

        try
        {
            return result.get(FX_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch(TimeoutException exception)
        {
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
            {
                throw new ConfigurationBusyException();
            }

            //The task started before the timeout. Wait for its unambiguous success or failure instead of returning
            //while a configuration transaction is still running.
            return joinResult(result);
        }
        catch(InterruptedException exception)
        {
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
            {
                Thread.currentThread().interrupt();
                throw new ConfigurationBusyException();
            }

            Thread.currentThread().interrupt();
            return joinResult(result);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }
            throw new CompletionException(exception.getCause());
        }
    }

    private static <T> T joinResult(CompletableFuture<T> result)
    {
        try
        {
            return result.join();
        }
        catch(CompletionException exception)
        {
            if(exception.getCause() instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private void requireInitialized()
    {
        if(!mConfigurationManager.isInitialized())
        {
            throw new NotInitializedException();
        }
    }

    private long revision()
    {
        //Browser JSON numbers preserve integer precision only through 53 bits.
        return mConfigurationManager.getAliasConfigurationRevision() & JSON_SAFE_INTEGER_MASK;
    }

    private static Alias copyAlias(Alias source)
    {
        Alias copy = AliasFactory.copyOf(source);
        copy.setId(source.getId());
        return copy;
    }

    private static AliasListDefinition copyDefinition(AliasListDefinition source)
    {
        AliasListDefinition copy = new AliasListDefinition(source.getName(), source.getFamily(),
            source.getUnmatchedTalkgroupPolicy());
        copy.setId(source.getId());
        return copy;
    }

    private static String requireName(String value, String label)
    {
        if(value == null || value.isBlank())
        {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private static String requireAliasListName(String value)
    {
        String name = requireName(value, "Alias-list name");
        if(name.length() > MAX_ALIAS_LIST_NAME_LENGTH)
        {
            throw new IllegalArgumentException("Alias-list name cannot exceed " + MAX_ALIAS_LIST_NAME_LENGTH +
                " characters");
        }
        return name;
    }

    private static long requirePositiveId(long value, String label)
    {
        if(value <= 0L)
        {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    public record Catalog(long revision, List<AliasListDefinition> aliasLists, List<ScanList> scanLists,
                          Map<Long,Set<Long>> unmatchedAliasListMemberships, Map<Long,Integer> aliasCounts,
                          Map<Long,Integer> assignedChannelCounts)
    {
        public Catalog
        {
            aliasLists = List.copyOf(aliasLists);
            scanLists = List.copyOf(scanLists);
            unmatchedAliasListMemberships = Map.copyOf(unmatchedAliasListMemberships);
            aliasCounts = Map.copyOf(aliasCounts);
            assignedChannelCounts = Map.copyOf(assignedChannelCounts);
        }
    }

    public record ScanListCatalog(long revision, List<ScanListSummary> scanLists)
    {
        public ScanListCatalog
        {
            scanLists = List.copyOf(scanLists);
        }
    }

    public record ScanListSummary(ScanList scanList, int aliasCount, int unmatchedAliasListCount)
    {
    }

    public record ScanListEntry(long revision, ScanList scanList, Set<Long> aliasIds,
                                Set<Long> unmatchedAliasListIds)
    {
        public ScanListEntry
        {
            aliasIds = Set.copyOf(aliasIds);
            unmatchedAliasListIds = Set.copyOf(unmatchedAliasListIds);
        }
    }

    public record ScanListCoverage(long revision, ScanList scanList, List<ScanListCoverageAlias> aliases,
                                   List<ScanListCoverageAliasList> unmatchedAliasLists, int aliasCount,
                                   boolean truncated)
    {
        public ScanListCoverage
        {
            aliases = List.copyOf(aliases);
            unmatchedAliasLists = List.copyOf(unmatchedAliasLists);
        }
    }

    public record ScanListCoverageAlias(long aliasId, long aliasListId, String aliasListName, String group,
                                        String name, String description, String matcherType, String matcher)
    {
    }

    public record ScanListCoverageAliasList(long aliasListId, String name, String family)
    {
    }

    public record ScanListMutationResult(long revision, long scanListId, int affected)
    {
    }

    public record AliasEntry(long revision, Alias alias, Set<Long> scanListIds)
    {
        public AliasEntry
        {
            scanListIds = scanListIds != null ? Set.copyOf(scanListIds) : Set.of();
        }
    }

    public record AliasConflictEntry(long revision, Alias alias, List<Alias> conflicts, int conflictCount,
                                     boolean truncated)
    {
        public AliasConflictEntry
        {
            conflicts = List.copyOf(conflicts);
        }
    }

    public record AliasListDefaultsEntry(long revision, long aliasListId, AliasListDefaults defaults)
    {
    }

    public enum CreationRouting
    {
        INHERIT_DEFAULTS,
        EXPLICIT,
        PRESERVE
    }

    public record AliasSaveRequest(Alias alias, CreationRouting creationRouting, Set<Long> scanListIds)
    {
        public AliasSaveRequest
        {
            Objects.requireNonNull(alias, "Alias cannot be null");
            Objects.requireNonNull(creationRouting, "Creation routing cannot be null");
            scanListIds = scanListIds != null ? Set.copyOf(new LinkedHashSet<>(scanListIds)) : Set.of();
        }

        public static AliasSaveRequest inherit(Alias alias)
        {
            return new AliasSaveRequest(alias, CreationRouting.INHERIT_DEFAULTS, Set.of());
        }

        public static AliasSaveRequest explicit(Alias alias, Collection<Long> scanListIds)
        {
            return new AliasSaveRequest(alias, CreationRouting.EXPLICIT,
                scanListIds != null ? new LinkedHashSet<>(scanListIds) : Set.of());
        }

        public static AliasSaveRequest preserve(Alias alias)
        {
            return new AliasSaveRequest(alias, CreationRouting.PRESERVE, Set.of());
        }
    }

    public record Options(long revision, AliasListDefinition aliasList, List<AliasMatchDescriptor> matchers,
                          List<String> iconNames, List<String> streamNames, List<String> groupNames,
                          List<ScanList> scanLists, Set<Long> unmatchedScanListIds)
    {
        public Options
        {
            matchers = List.copyOf(matchers);
            iconNames = List.copyOf(iconNames);
            streamNames = List.copyOf(streamNames);
            groupNames = List.copyOf(groupNames);
            scanLists = List.copyOf(scanLists);
            unmatchedScanListIds = Set.copyOf(unmatchedScanListIds);
        }
    }

    public record MutationResult(long revision, Long aliasListId, List<Long> aliasIds, int affected)
    {
        public MutationResult
        {
            aliasIds = List.copyOf(aliasIds);
        }
    }

    public record DeleteImpact(long revision, long aliasListId, String name, int aliasCount, int channelCount)
    {
    }

    public record BulkEdit(List<Long> aliasIds, Long targetAliasListId, Integer color, String iconName,
                           Boolean recordable, GroupOperation groupOperation, String group,
                           StreamOperation streamOperation, List<String> broadcastChannels, boolean delete)
    {
        public BulkEdit
        {
            aliasIds = aliasIds != null ? List.copyOf(aliasIds) : null;
            group = group != null ? group.trim() : null;
            broadcastChannels = broadcastChannels != null ? List.copyOf(broadcastChannels) : null;
        }
    }

    public enum GroupOperation
    {
        SET,
        CLEAR
    }

    public enum StreamOperation
    {
        ADD,
        REMOVE,
        REPLACE,
        CLEAR
    }

    public enum MembershipOperation
    {
        ADD,
        REMOVE,
        REPLACE
    }

    private record MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                                  List<Alias> savedAliases, PublicationMode publicationMode,
                                  Runnable beforePublication,
                                  ConfigurationManager.BroadcastConfigurationRename broadcastConfigurationRename)
    {
        private MutationTarget
        {
            aliases = List.copyOf(aliases);
            savedAliases = List.copyOf(savedAliases);
        }

        private MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                               PublicationMode publicationMode)
        {
            this(aliasList, aliases, affected, List.of(), publicationMode, null, null);
        }

        private MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                               List<Alias> savedAliases, PublicationMode publicationMode,
                               Runnable beforePublication)
        {
            this(aliasList, aliases, affected, savedAliases, publicationMode, beforePublication, null);
        }

        private MutationTarget withAliasList(AliasListDefinition definition)
        {
            return new MutationTarget(definition, aliases, affected, savedAliases, publicationMode,
                beforePublication, broadcastConfigurationRename);
        }

        private MutationTarget withPublication(PublicationMode mode)
        {
            return new MutationTarget(aliasList, aliases, affected, savedAliases, mode, beforePublication,
                broadcastConfigurationRename);
        }

        private ConfigurationManager.AliasConfigurationPublication publication()
        {
            Set<Long> changedAliasIds = new HashSet<>(aliases);
            savedAliases.stream().map(Alias::getId).forEach(changedAliasIds::add);
            Set<String> clearedChannelAliasListNames = publicationMode.clearsChannelAssignments() && aliasList != null ?
                Set.of(aliasList.getName()) : Set.of();
            return new ConfigurationManager.AliasConfigurationPublication(changedAliasIds,
                publicationMode.definitionsChanged(), publicationMode.scanListsChanged(),
                publicationMode.scanListsFirst(), clearedChannelAliasListNames);
        }
    }

    private enum PublicationMode
    {
        ALIASES(false, false, false, false),
        ALIAS_LISTS(true, false, false, false),
        SCAN_LISTS_THEN_ALIASES(false, true, true, false),
        SCAN_LISTS_THEN_ALIAS_LISTS(true, true, true, false),
        ALIASES_THEN_SCAN_LISTS(false, true, false, false),
        ALIAS_LIST_DELETE(true, true, false, true);

        private final boolean mDefinitionsChanged;
        private final boolean mScanListsChanged;
        private final boolean mScanListsFirst;
        private final boolean mClearsChannelAssignments;

        PublicationMode(boolean definitionsChanged, boolean scanListsChanged, boolean scanListsFirst,
                        boolean clearsChannelAssignments)
        {
            mDefinitionsChanged = definitionsChanged;
            mScanListsChanged = scanListsChanged;
            mScanListsFirst = scanListsFirst;
            mClearsChannelAssignments = clearsChannelAssignments;
        }

        private boolean definitionsChanged()
        {
            return mDefinitionsChanged;
        }

        private boolean scanListsChanged()
        {
            return mScanListsChanged;
        }

        private boolean scanListsFirst()
        {
            return mScanListsFirst;
        }

        private boolean clearsChannelAssignments()
        {
            return mClearsChannelAssignments;
        }

    }

    private record AliasListPolicyState(AliasListDefinition definition, UnmatchedTalkgroupPolicy updated)
    {
    }

    private record ScanListMutation(ScanList scanList, int affected)
    {
    }

    private record PreparedAliasRequest(Alias alias, CreationRouting creationRouting, Set<Long> scanListIds,
                                        boolean wasNew)
    {
        private PreparedAliasRequest(Alias alias, CreationRouting creationRouting, Set<Long> scanListIds)
        {
            this(alias, creationRouting, scanListIds, alias.getId() == Alias.UNASSIGNED_ID);
        }
    }

    private record MutationWorkspace(AliasModel aliasModel, ScanListModel scanListModel, Map<Long,Alias> aliasesById)
    {
        private AliasConfigurationSnapshot snapshot()
        {
            return new AliasConfigurationSnapshot(aliasModel.aliasListDefinitions(), aliasModel.getAliases(),
                scanListModel.configuration());
        }
    }

    private enum DispatchState
    {
        QUEUED,
        RUNNING,
        CANCELLED
    }

    public static class NotInitializedException extends IllegalStateException
    {
        public NotInitializedException()
        {
            super("Alias configuration is still loading");
        }
    }

    public static class ConfigurationBusyException extends IllegalStateException
    {
        public ConfigurationBusyException()
        {
            super("The desktop configuration thread is busy");
        }
    }

    public static class NotFoundException extends IllegalArgumentException
    {
        public NotFoundException(String message)
        {
            super(message);
        }
    }

    public static class StaleRevisionException extends IllegalStateException
    {
        private final long mExpected;
        private final long mCurrent;

        public StaleRevisionException(long expected, long current)
        {
            super("Alias catalog changed after it was loaded");
            mExpected = expected;
            mCurrent = current;
        }

        public long getExpected()
        {
            return mExpected;
        }

        public long getCurrent()
        {
            return mCurrent;
        }
    }

    public static class ConfirmationRequiredException extends IllegalStateException
    {
        private final DeleteImpact mImpact;

        public ConfirmationRequiredException(DeleteImpact impact)
        {
            super("Alias-list deletion requires confirmation");
            mImpact = impact;
        }

        public DeleteImpact getImpact()
        {
            return mImpact;
        }
    }

    public static class PersistenceException extends IllegalStateException
    {
        public PersistenceException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}

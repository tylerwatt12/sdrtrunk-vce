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
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Small command boundary shared by authenticated alias-management clients. Runtime aliases remain in the fast
 * {@link AliasModel}; successful commands synchronously flush the existing complete configuration transaction.
 */
public final class AliasAdministrationService
{
    public static final int MAX_BULK_ALIASES = 500;
    public static final int MAX_ALIAS_LIST_NAME_LENGTH = 25;
    private static final long FX_QUEUE_TIMEOUT_SECONDS = 15L;
    private static final long JSON_SAFE_INTEGER_MASK = (1L << 53) - 1L;

    private final ConfigurationManager mConfigurationManager;
    private final boolean mUseDesktopThread;

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
            return new AliasEntry(revision(), copyAlias(alias));
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
            List<String> icons = mConfigurationManager.getIconModel() != null ?
                mConfigurationManager.getIconModel().iconsProperty().stream()
                    .map(icon -> icon.getName()).filter(Objects::nonNull).sorted().toList() : List.of();
            List<String> streams = mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream()
                .map(configuration -> configuration.getName()).filter(Objects::nonNull).sorted().toList();
            List<Integer> priorities = new ArrayList<>();
            priorities.add(Priority.DO_NOT_MONITOR);
            for(int priority = Priority.MIN_PRIORITY; priority < Priority.MAX_PRIORITY; priority++)
            {
                priorities.add(priority);
            }
            priorities.add(Priority.DEFAULT_PRIORITY);
            return new Options(revision(), copyDefinition(definition), AliasMatchRegistry.allowed(definition), icons,
                streams, aliasModel().getGroupNames(), priorities);
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
            aliasModel().addAliasListDefinition(definition);
            return new MutationTarget(definition, List.of(), 1,
                () -> removeAliasListDefinition(definition), null);
        });
    }

    /**
     * Replaces the action-only policy used when a talkgroup or patch group has no configured exact or range Alias.
     */
    public MutationResult updateUnmatchedTalkgroupPolicy(long aliasListId, UnmatchedTalkgroupPolicy policy,
                                                          long expectedRevision)
    {
        Objects.requireNonNull(policy, "Unmatched talkgroup policy cannot be null");

        return mutate(expectedRevision, () ->
        {
            AliasListDefinition definition = requireAliasList(aliasListId);
            if(!supportsUnmatchedTalkgroups(definition.getFamily()))
            {
                throw new IllegalArgumentException("Unmatched talkgroup behavior is available only for P25, DMR, " +
                    "and NXDN alias lists");
            }
            UnmatchedTalkgroupPolicy previous = definition.getUnmatchedTalkgroupPolicy();
            validatePolicyStreams(policy, previous);
            definition.setUnmatchedTalkgroupPolicy(policy);
            mConfigurationManager.aliasListDefinitionChanged();
            return new MutationTarget(definition, List.of(), 1, () ->
            {
                definition.setUnmatchedTalkgroupPolicy(previous);
                mConfigurationManager.aliasListDefinitionChanged();
            }, null);
        });
    }

    public DeleteImpact aliasListDeleteImpact(long aliasListId)
    {
        return onConfigurationThread(() -> deleteImpact(requireAliasList(aliasListId)));
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
            List<Alias> previousAliases = List.copyOf(aliasModel().getAliases());
            List<AliasListDefinition> previousDefinitions = List.copyOf(aliasModel().aliasListDefinitions());
            List<ChannelAssignment> channels = mConfigurationManager.getChannelModel().getChannels().stream()
                .filter(channel -> matchesList(channel, definition))
                .map(channel -> new ChannelAssignment(channel, channel.getAliasListName())).toList();

            mConfigurationManager.prepareForAliasListRefresh();
            aliasModel().removeAliases(deletedAliases);
            removeAliasListDefinition(definition);
            mConfigurationManager.getChannelModel().deleteAliasList(definition.getName());

            return new MutationTarget(definition, deletedAliasIds, impact.aliasCount(), () ->
            {
                aliasModel().setAliasListDefinitions(previousDefinitions);
                aliasModel().restoreAliases(previousAliases);
                channels.forEach(assignment -> assignment.channel().setAliasListName(assignment.aliasListName()));
            }, () -> aliasModel().discardAliasListCache(definition.getName()));
        });
    }

    public MutationResult createAlias(Alias alias, long expectedRevision)
    {
        Alias prepared = prepareNewAlias(alias);
        return savePreparedAliases(List.of(prepared), Long.valueOf(expectedRevision));
    }

    /**
     * Creates an Alias from a trusted desktop workflow using the current serialized model state.
     */
    public MutationResult createAlias(Alias alias)
    {
        return savePreparedAliases(List.of(prepareNewAlias(alias)), null);
    }

    public MutationResult replaceAlias(long aliasId, Alias replacement, long expectedRevision)
    {
        return savePreparedAliases(List.of(prepareReplacement(aliasId, replacement)),
            Long.valueOf(expectedRevision));
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
        return savePreparedAliases(prepareAliases(aliases), null);
    }

    /**
     * Revision-aware form of {@link #saveAliases(List)}.
     */
    public MutationResult saveAliases(List<Alias> aliases, long expectedRevision)
    {
        return savePreparedAliases(prepareAliases(aliases), Long.valueOf(expectedRevision));
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
                if(edit.playbackPriority() != null)
                {
                    alias.setCallPriority(edit.playbackPriority());
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
                MutationTarget target = operation.get();

                try
                {
                    mConfigurationManager.flushConfiguration();
                }
                catch(RuntimeException exception)
                {
                    rollback(target, exception);
                    throw new PersistenceException("Unable to save alias configuration", exception);
                }

                if(target.afterCommit() != null)
                {
                    target.afterCommit().run();
                }

                List<Long> ids = target.savedAliases().isEmpty() ? target.aliases() :
                    target.savedAliases().stream().map(Alias::getId).toList();
                Long aliasListId = target.aliasList() != null ? target.aliasList().getId() : null;
                return new MutationResult(revision(), aliasListId, ids, target.affected());
            }));
    }

    private MutationResult savePreparedAliases(List<Alias> prepared, Long expectedRevision)
    {
        return mutate(expectedRevision, () -> saveAliasesTarget(prepared));
    }

    /**
     * Validates the complete batch before changing the live model, then installs each Alias by durable identity.
     */
    private MutationTarget saveAliasesTarget(List<Alias> prepared)
    {
        if(prepared == null || prepared.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one Alias to save");
        }

        Set<Long> persistedIds = new HashSet<>();
        List<Alias> previousAliases = List.copyOf(aliasModel().getAliases());

        for(Alias alias: prepared)
        {
            if(alias == null)
            {
                throw new IllegalArgumentException("Alias cannot be null");
            }

            Alias current = null;
            if(alias.getId() > Alias.UNASSIGNED_ID)
            {
                if(!persistedIds.add(alias.getId()))
                {
                    throw new IllegalArgumentException("Alias IDs to save must be unique");
                }

                current = requireAlias(alias.getId());
            }

            AliasListDefinition definition = resolveAliasList(alias);
            validateAlias(alias, definition, current);
            alias.setAliasListDefinition(definition);
        }

        aliasModel().addAliases(prepared);
        return new MutationTarget(null, List.of(), prepared.size(), prepared,
            () -> aliasModel().restoreAliases(previousAliases), null);
    }

    private MutationResult deleteAliases(List<Long> aliasIds, Long expectedRevision)
    {
        List<Long> validatedIds = validatedAliasIds(aliasIds);

        return mutate(expectedRevision, () -> deleteAliasesTarget(validatedIds,
            validatedIds.stream().map(this::requireAlias).toList()));
    }

    private MutationTarget deleteAliasesTarget(List<Long> aliasIds, List<Alias> aliases)
    {
        List<Alias> previousAliases = List.copyOf(aliasModel().getAliases());
        aliasModel().removeAliases(aliases);
        return new MutationTarget(null, aliasIds, aliases.size(),
            () -> aliasModel().restoreAliases(previousAliases), null);
    }

    private MutationTarget renameBroadcastChannelReferencesTarget(String previousName, String updatedName)
    {
        if(previousName.equals(updatedName))
        {
            return new MutationTarget(null, List.of(), 0, null, null);
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

                UnmatchedTalkgroupPolicy updated = new UnmatchedTalkgroupPolicy(previous.getPlaybackPriority(),
                    previous.isRecordEnabled(), destinations);
                validatePolicyStreams(updated, previous);
                policyStates.add(new AliasListPolicyState(definition, previous, updated));
            }
        }

        MutationTarget aliasesTarget = replacements.isEmpty() ?
            new MutationTarget(null, List.of(), 0, null, null) : saveAliasesTarget(replacements);

        if(!policyStates.isEmpty())
        {
            policyStates.forEach(state -> state.definition().setUnmatchedTalkgroupPolicy(state.updated()));
            mConfigurationManager.aliasListDefinitionChanged();
        }

        Runnable rollback = () ->
        {
            if(aliasesTarget.rollback() != null)
            {
                aliasesTarget.rollback().run();
            }
            if(!policyStates.isEmpty())
            {
                policyStates.forEach(state -> state.definition().setUnmatchedTalkgroupPolicy(state.previous()));
                mConfigurationManager.aliasListDefinitionChanged();
            }
        };

        return new MutationTarget(null, List.of(), replacements.size() + policyStates.size(),
            aliasesTarget.savedAliases(), rollback, null);
    }

    private Catalog catalogOnConfigurationThread()
    {
        List<AliasListDefinition> definitions = aliasModel().aliasListDefinitions().stream()
            .map(AliasAdministrationService::copyDefinition).toList();
        return new Catalog(revision(), definitions);
    }

    private DeleteImpact deleteImpact(AliasListDefinition definition)
    {
        int aliasCount = aliasesForList(definition).size();
        int channelCount = (int)mConfigurationManager.getChannelModel().getChannels().stream()
            .filter(channel -> matchesList(channel, definition)).count();
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

    private static List<Alias> prepareAliases(List<Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one Alias to save");
        }

        Set<Long> persistedIds = new HashSet<>();
        Set<Alias> instances = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Alias> prepared = new ArrayList<>(aliases.size());
        for(Alias source: aliases)
        {
            Objects.requireNonNull(source, "Alias cannot be null");
            if(!instances.add(source))
            {
                throw new IllegalArgumentException("Aliases to save must be unique instances");
            }
            Alias copy = copyAlias(source);
            requireName(copy.getName(), "Alias name");
            if(copy.getId() > Alias.UNASSIGNED_ID && !persistedIds.add(copy.getId()))
            {
                throw new IllegalArgumentException("Alias IDs to save must be unique");
            }
            prepared.add(copy);
        }

        return List.copyOf(prepared);
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

        for(BroadcastChannel channel: alias.getBroadcastChannels())
        {
            if(channel == null || channel.getChannelName() == null || channel.getChannelName().isBlank())
            {
                throw new IllegalArgumentException("Broadcast channel names cannot be blank");
            }

            if(!isConfiguredStream(channel.getChannelName()) &&
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
        Set<String> previousNames = previous != null ?
            Set.copyOf(previous.getStreamDestinationNames()) : Set.of();

        for(String channelName: policy.getStreamDestinationNames())
        {
            if(!isConfiguredStream(channelName) && !previousNames.contains(channelName))
            {
                throw new IllegalArgumentException("Broadcast channel [" + channelName + "] does not exist");
            }
        }
    }

    private static boolean supportsUnmatchedTalkgroups(AliasListFamily family)
    {
        return family == AliasListFamily.P25 || family == AliasListFamily.DMR || family == AliasListFamily.NXDN;
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
            edit.playbackPriority() != null || edit.recordable() != null || edit.groupOperation() != null ||
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
        if(edit.playbackPriority() != null && !isValidPriority(edit.playbackPriority()))
        {
            throw new IllegalArgumentException("Playback priority is invalid");
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

    private static boolean isValidPriority(int priority)
    {
        return priority == Priority.DO_NOT_MONITOR || priority == Priority.DEFAULT_PRIORITY ||
            Priority.MIN_PRIORITY <= priority && priority < Priority.MAX_PRIORITY;
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
        Alias alias = aliasModel().getAlias(aliasId);
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

    private AliasModel aliasModel()
    {
        return mConfigurationManager.getAliasModel();
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

    private static void rollback(MutationTarget target, RuntimeException failure)
    {
        if(target.rollback() == null)
        {
            return;
        }

        try
        {
            target.rollback().run();
        }
        catch(RuntimeException restoreFailure)
        {
            failure.addSuppressed(restoreFailure);
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

    public record Catalog(long revision, List<AliasListDefinition> aliasLists)
    {
        public Catalog
        {
            aliasLists = List.copyOf(aliasLists);
        }
    }

    public record AliasEntry(long revision, Alias alias)
    {
    }

    public record Options(long revision, AliasListDefinition aliasList, List<AliasMatchDescriptor> matchers,
                          List<String> iconNames, List<String> streamNames, List<String> groupNames,
                          List<Integer> playbackPriorities)
    {
        public Options
        {
            matchers = List.copyOf(matchers);
            iconNames = List.copyOf(iconNames);
            streamNames = List.copyOf(streamNames);
            groupNames = List.copyOf(groupNames);
            playbackPriorities = List.copyOf(playbackPriorities);
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
                           Integer playbackPriority, Boolean recordable, GroupOperation groupOperation, String group,
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

    private record MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                                  List<Alias> savedAliases, Runnable rollback, Runnable afterCommit)
    {
        private MutationTarget
        {
            aliases = List.copyOf(aliases);
            savedAliases = List.copyOf(savedAliases);
        }

        private MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                               Runnable rollback, Runnable afterCommit)
        {
            this(aliasList, aliases, affected, List.of(), rollback, afterCommit);
        }

        private MutationTarget withAliasList(AliasListDefinition definition)
        {
            return new MutationTarget(definition, aliases, affected, savedAliases, rollback, afterCommit);
        }
    }

    private record ChannelAssignment(Channel channel, String aliasListName)
    {
    }

    private record AliasListPolicyState(AliasListDefinition definition, UnmatchedTalkgroupPolicy previous,
                                        UnmatchedTalkgroupPolicy updated)
    {
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

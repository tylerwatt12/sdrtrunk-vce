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

    public DeleteImpact aliasListDeleteImpact(long aliasListId)
    {
        return onConfigurationThread(() -> deleteImpact(requireAliasList(aliasListId)));
    }

    public MutationResult deleteAliasList(long aliasListId, long expectedRevision, boolean confirmed)
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
            List<ChannelAssignment> channels = mConfigurationManager.getChannelModel().getChannels().stream()
                .filter(channel -> matchesList(channel, definition))
                .map(channel -> new ChannelAssignment(channel, channel.getAliasListName())).toList();

            mConfigurationManager.prepareForAliasListRefresh();
            aliasModel().removeAliases(deletedAliases);
            removeAliasListDefinition(definition);
            mConfigurationManager.getChannelModel().deleteAliasList(definition.getName());

            return new MutationTarget(definition, deletedAliasIds, impact.aliasCount(), () ->
            {
                aliasModel().addAliasListDefinition(definition);
                aliasModel().addAliases(deletedAliases);
                channels.forEach(assignment -> assignment.channel().setAliasListName(assignment.aliasListName()));
            }, () -> aliasModel().discardAliasListCache(definition.getName()));
        });
    }

    public MutationResult createAlias(Alias alias, long expectedRevision)
    {
        Alias prepared = prepareNewAlias(alias);

        return mutate(expectedRevision, () ->
        {
            AliasListDefinition definition = resolveAliasList(prepared);
            validateAlias(prepared, definition, null);
            prepared.setAliasListDefinition(definition);
            aliasModel().addAlias(prepared);
            return new MutationTarget(null, List.of(), 1, prepared,
                () -> aliasModel().removeAlias(prepared), null);
        });
    }

    public MutationResult replaceAlias(long aliasId, Alias replacement, long expectedRevision)
    {
        Alias prepared = prepareNewAlias(replacement);
        prepared.setId(requirePositiveId(aliasId, "Alias ID"));

        return mutate(expectedRevision, () ->
        {
            Alias current = requireAlias(aliasId);
            AliasListDefinition definition = resolveAliasList(prepared);
            validateAlias(prepared, definition, current);
            prepared.setAliasListDefinition(definition);
            aliasModel().removeAlias(current);
            aliasModel().addAlias(prepared);
            return new MutationTarget(null, List.of(aliasId), 1, prepared, () ->
            {
                aliasModel().removeAlias(prepared);
                aliasModel().addAlias(current);
            }, null);
        });
    }

    public MutationResult deleteAlias(long aliasId, long expectedRevision)
    {
        requirePositiveId(aliasId, "Alias ID");

        return mutate(expectedRevision, () ->
        {
            Alias alias = requireAlias(aliasId);
            aliasModel().removeAlias(alias);
            return new MutationTarget(null, List.of(aliasId), 1,
                () -> aliasModel().addAlias(alias), null);
        });
    }

    /**
     * Applies the fields supported by the desktop multiple-alias editor, plus compatible move and delete operations.
     */
    public MutationResult bulkEdit(BulkEdit edit, long expectedRevision)
    {
        Objects.requireNonNull(edit, "Bulk edit cannot be null");
        List<Long> aliasIds = validatedBulkIds(edit.aliasIds());
        validateBulkFields(edit);

        return mutate(expectedRevision, () ->
        {
            List<Alias> aliases = aliasIds.stream().map(this::requireAlias).toList();

            if(edit.delete())
            {
                aliasModel().removeAliases(aliases);
                return new MutationTarget(null, aliasIds, aliases.size(),
                    () -> aliasModel().addAliases(aliases), null);
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
                }
            }

            if(edit.iconName() != null)
            {
                validateIconName(edit.iconName());
            }

            validateBulkStreams(edit);

            List<BulkAliasState> previous = aliases.stream().map(alias -> new BulkAliasState(alias,
                aliasModel().getAliasListDefinition(alias), alias.getColor(), alias.getIconName(),
                alias.getPlaybackPriority(), alias.isRecordable(), alias.getGroup(),
                copyBroadcastChannels(alias))).toList();

            for(Alias alias: aliases)
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

            return new MutationTarget(target, aliasIds, aliases.size(), () -> previous.forEach(state ->
            {
                state.alias().setAliasListDefinition(state.aliasList());
                state.alias().setColor(state.color());
                state.alias().setIconName(state.iconName());
                state.alias().setCallPriority(state.playbackPriority());
                state.alias().setRecordable(state.recordable());
                state.alias().setGroup(state.group());
                state.alias().setBroadcastChannels(state.broadcastChannels());
            }), null);
        });
    }

    private MutationResult mutate(long expectedRevision, Supplier<MutationTarget> operation)
    {
        return onConfigurationThread(() ->
        {
            requireRevision(expectedRevision);
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

            List<Long> ids = target.aliases().isEmpty() && target.alias() != null ?
                List.of(target.alias().getId()) : target.aliases();
            Long aliasListId = target.aliasList() != null ? target.aliasList().getId() : null;
            return new MutationResult(revision(), aliasListId, ids, target.affected());
        });
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
        return aliasModel().getAliases().stream().filter(alias ->
            alias.getAliasListId() == definition.getId() ||
                alias.getAliasListId() == Alias.UNASSIGNED_ALIAS_LIST_ID &&
                    definition.getName().equalsIgnoreCase(alias.getAliasListName())).toList();
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
        aliasModel().aliasListDefinitions().remove(definition);
        aliasModel().refreshAliasListNames();
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

    private boolean isConfiguredStream(String channelName)
    {
        return channelName != null && mConfigurationManager.getBroadcastModel().getBroadcastConfigurations().stream()
            .anyMatch(configuration -> channelName.equals(configuration.getName()));
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

    private static List<BroadcastChannel> copyBroadcastChannels(Alias alias)
    {
        return alias.getBroadcastChannels().stream()
            .map(channel -> new BroadcastChannel(channel.getChannelName())).toList();
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

    private static List<Long> validatedBulkIds(List<Long> aliasIds)
    {
        if(aliasIds == null || aliasIds.isEmpty())
        {
            throw new IllegalArgumentException("Select at least one alias");
        }
        if(aliasIds.size() > MAX_BULK_ALIASES)
        {
            throw new IllegalArgumentException("Bulk operations are limited to " + MAX_BULK_ALIASES + " aliases");
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
        return aliasModel().getAliases().stream().filter(alias -> alias.getId() == aliasId).findFirst()
            .orElseThrow(() -> new NotFoundException("Alias [" + aliasId + "] was not found"));
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
        AliasListDefinition copy = new AliasListDefinition(source.getName(), source.getFamily());
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

    private record MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected, Alias alias,
                                  Runnable rollback, Runnable afterCommit)
    {
        private MutationTarget(AliasListDefinition aliasList, List<Long> aliases, int affected,
                               Runnable rollback, Runnable afterCommit)
        {
            this(aliasList, List.copyOf(aliases), affected, null, rollback, afterCommit);
        }
    }

    private record ChannelAssignment(Channel channel, String aliasListName)
    {
    }

    private record BulkAliasState(Alias alias, AliasListDefinition aliasList, int color, String iconName,
                                  int playbackPriority, boolean recordable, String group,
                                  List<BroadcastChannel> broadcastChannels)
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

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

package io.github.dsheirer.channel;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javafx.application.Platform;

/**
 * Headless command boundary for web-first channel administration. No JavaFX editor class participates in reads,
 * validation, persistence, cloning, ordering, or lifecycle commands.
 */
public final class ChannelAdministrationService
{
    public static final int MAXIMUM_BULK_CHANNELS = 100;
    private static final long FX_QUEUE_TIMEOUT_SECONDS = 15L;
    private static final long JSON_SAFE_INTEGER_MASK = (1L << 53) - 1L;
    private final ConfigurationManager mConfigurationManager;
    private final ChannelProtocolRegistry mProtocolRegistry;
    private final ChannelDefinitionCodec mCodec;
    private final boolean mUseDesktopThread;
    /** One web mutation or receiver lifecycle batch at a time; saturation fails without blocking request workers. */
    private final Semaphore mCommandAdmission = new Semaphore(1);

    public ChannelAdministrationService(ConfigurationManager configurationManager)
    {
        this(configurationManager, new ChannelProtocolRegistry(), !GraphicsEnvironment.isHeadless());
    }

    ChannelAdministrationService(ConfigurationManager configurationManager, ChannelProtocolRegistry protocolRegistry,
                                 boolean useDesktopThread)
    {
        mConfigurationManager = Objects.requireNonNull(configurationManager);
        mProtocolRegistry = Objects.requireNonNull(protocolRegistry);
        mCodec = new ChannelDefinitionCodec(mProtocolRegistry);
        mUseDesktopThread = useDesktopThread;
    }

    public ChannelProtocolRegistry protocolRegistry()
    {
        return mProtocolRegistry;
    }

    public long currentRevision()
    {
        return onConfigurationThread(this::revision);
    }

    public Catalog catalog()
    {
        return onConfigurationThread(() ->
        {
            List<Channel> channels = List.copyOf(mConfigurationManager.getChannelModel().getChannels());
            Map<String,Integer> order = effectiveAutoStartOrder(channels);
            List<ChannelSummary> summaries = channels.stream().map(channel -> summary(channel,
                order.get(channel.getConfigurationId()))).toList();
            return new Catalog(revision(), summaries);
        });
    }

    public Entry get(String configurationId)
    {
        String id = requireConfigurationId(configurationId);
        return onConfigurationThread(() ->
        {
            Channel channel = requireChannel(id);
            Integer order = effectiveAutoStartOrder(mConfigurationManager.getChannelModel().getChannels()).get(id);
            return new Entry(revision(), mCodec.fromChannel(channel), processingState(channel), order);
        });
    }

    public Options options()
    {
        return onConfigurationThread(() -> new Options(revision(),
            mConfigurationManager.getAliasModel().aliasListDefinitions().stream()
                .map(definition -> new AliasListOption(definition.getId(), definition.getName(),
                    definition.getFamily().name())).toList(),
            mConfigurationManager.getTunerManager() != null ?
                mConfigurationManager.getTunerManager().getPreferredTunerNames() : List.of()));
    }

    public ChannelDefinition template(String protocolId)
    {
        ChannelProtocolRegistry.Profile profile = mProtocolRegistry.require(protocolId);
        return onConfigurationThread(() ->
        {
            AliasListDefinition aliasList = mConfigurationManager.getAliasModel().aliasListDefinitions().stream()
                .filter(candidate -> candidate.getFamily() == profile.aliasFamily()).findFirst().orElse(null);
            return new ChannelDefinition(null, profile.id(), null, null, "Channel", null,
                aliasList != null ? aliasList.getId() : AliasListDefinition.UNASSIGNED_ID,
                new ChannelDefinition.Source(List.of(), null, null, null, null, null),
                profile.defaultSettings(), List.of(), List.of(), List.of(), List.of(),
                ChannelDefinition.Observed.EMPTY);
        });
    }

    public MutationResult create(ChannelDefinition definition, long expectedRevision)
    {
        return admitted(() -> mutate(expectedRevision, false, channels ->
        {
            AliasListDefinition aliasList = requireAliasList(definition.aliasListId());
            Channel created = mCodec.toChannel(definition, aliasList, null);
            channels.add(created);
            requireUniqueRadioResolveIds(channels);
            return new MutationTarget(Set.of(created.getConfigurationId()), List.of(created.getConfigurationId()));
        }));
    }

    public MutationResult update(String configurationId, ChannelDefinition definition, long expectedRevision)
    {
        String id = requireConfigurationId(configurationId);
        return admitted(() -> mutate(expectedRevision, false, channels ->
        {
            int index = requireChannelIndex(channels, id);
            Channel existing = channels.get(index);
            if(isProcessing(id)) throw new ChannelRunningException(id);
            ChannelDefinition normalized = new ChannelDefinition(id, definition.protocolId(), definition.system(),
                definition.site(), definition.name(), definition.radioResolveId(), definition.aliasListId(),
                definition.source(), definition.settings(), definition.frequencyMap(), definition.eventLogs(),
                definition.recorders(), definition.auxiliaryDecoders(), definition.observed());
            Channel replacement = mCodec.toChannel(normalized, requireAliasList(definition.aliasListId()), existing);
            channels.set(index, replacement);
            requireUniqueRadioResolveIds(channels);
            return new MutationTarget(Set.of(id), List.of(id));
        }));
    }

    public MutationResult cloneChannels(Collection<String> configurationIds, long expectedRevision)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() -> mutate(expectedRevision, false, channels ->
        {
            Map<String,Channel> byId = index(channels);
            List<String> createdIds = new ArrayList<>();
            for(String id: ids)
            {
                Channel source = requireChannel(byId, id);
                Channel clone = mCodec.cloneChannel(source, requireAliasList(source.getAliasListId()));
                channels.add(clone);
                createdIds.add(clone.getConfigurationId());
            }
            requireUniqueRadioResolveIds(channels);
            return new MutationTarget(Set.copyOf(createdIds), createdIds);
        }));
    }

    public MutationResult deleteChannels(Collection<String> configurationIds, long expectedRevision)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() ->
        {
            List<Channel> selected = onConfigurationThread(() ->
            {
                requireRevision(expectedRevision);
                return ids.stream().map(this::requireChannel).toList();
            });
            for(Channel channel: selected)
            {
                if(isProcessing(channel.getConfigurationId()))
                {
                    try
                    {
                        mConfigurationManager.getChannelProcessingManager().stop(channel);
                    }
                    catch(ChannelException exception)
                    {
                        throw new LifecycleException("Unable to stop channel before deletion", exception);
                    }
                }
            }
            return mutate(expectedRevision, false, channels ->
            {
                for(String id: ids)
                {
                    int index = requireChannelIndex(channels, id);
                    if(isProcessing(id)) throw new ChannelRunningException(id);
                    channels.remove(index);
                }
                return new MutationTarget(Set.copyOf(ids), ids);
            });
        });
    }

    public MutationResult moveAutoStart(String configurationId, Direction direction, long expectedRevision)
    {
        String id = requireConfigurationId(configurationId);
        Objects.requireNonNull(direction, "Auto-start direction cannot be null");
        return admitted(() -> mutate(expectedRevision, true, channels ->
        {
            requireChannel(channels, id);
            List<String> enabled = new ArrayList<>(effectiveAutoStartIds(channels));
            int position = enabled.indexOf(id);
            if(direction == Direction.EARLIER)
            {
                if(position < 0) enabled.add(id);
                else if(position > 0) java.util.Collections.swap(enabled, position, position - 1);
            }
            else if(position >= 0 && position < enabled.size() - 1)
            {
                java.util.Collections.swap(enabled, position, position + 1);
            }
            else if(position == enabled.size() - 1)
            {
                enabled.remove(position);
            }

            Set<String> changed = applyAutoStartOrder(channels, enabled);
            return new MutationTarget(changed, List.of(id));
        }));
    }

    public BatchResult setProcessing(Collection<String> configurationIds, boolean start)
    {
        List<String> ids = boundedConfigurationIds(configurationIds);
        return admitted(() ->
        {
            Map<String,Channel> channels = onConfigurationThread(() ->
            {
                Map<String,Channel> selected = new LinkedHashMap<>();
                ids.forEach(id -> selected.put(id, requireChannel(id)));
                return selected;
            });
            List<LifecycleResult> results = new ArrayList<>();
            for(String id: ids)
            {
                Channel channel = channels.get(id);
                try
                {
                    if(start)
                    {
                        if(!isProcessing(id)) mConfigurationManager.getChannelProcessingManager().start(channel);
                    }
                    else if(isProcessing(id))
                    {
                        mConfigurationManager.getChannelProcessingManager().stop(channel);
                    }
                    results.add(new LifecycleResult(id, true, processingState(channel), null));
                }
                catch(ChannelException exception)
                {
                    results.add(new LifecycleResult(id, false, processingState(channel), safeMessage(exception)));
                }
            }
            return new BatchResult(revision(), results);
        });
    }

    /** Clears observation and activity rows owned by one stopped saved channel. */
    public StatisticsResult clearStatistics(String configurationId)
    {
        String id = requireConfigurationId(configurationId);
        return admitted(() ->
        {
            onConfigurationThread(() ->
            {
                requireChannel(id);
                if(isProcessing(id)) throw new ChannelRunningException(id);
                return null;
            });
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.clearChannel(id);
            MyEventBus.getGlobalEventBus().post(request);
            try
            {
                ReceiverActivityMaintenance.Result result = request.result().get(30, TimeUnit.SECONDS);
                return new StatisticsResult(id, result.rowsDeleted(), result.summary());
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new StatisticsException("Channel statistics clearing was interrupted", exception);
            }
            catch(ExecutionException | TimeoutException exception)
            {
                throw new StatisticsException("Channel statistics could not be cleared", exception);
            }
        });
    }

    private MutationResult mutate(long expectedRevision, boolean autoStartOnly,
                                  java.util.function.Function<List<Channel>,MutationTarget> operation)
    {
        return onConfigurationThread(() -> mConfigurationManager.applyConfigurationMutation(() ->
        {
            requireRevision(expectedRevision);
            List<Channel> channels = detachedChannels();
            MutationTarget target = operation.apply(channels);
            if(target.changedIds().isEmpty())
            {
                return new MutationResult(revision(), target.resultIds(), 0);
            }
            try
            {
                mConfigurationManager.commitAndPublishChannelConfiguration(
                    new ChannelConfigurationSnapshot(channels), target.changedIds(), autoStartOnly);
            }
            catch(ConfigurationManager.ConfigurationCommitException exception)
            {
                throw new PersistenceException("Unable to save channel configuration", exception);
            }
            return new MutationResult(revision(), target.resultIds(), target.changedIds().size());
        }));
    }

    private List<Channel> detachedChannels()
    {
        List<Channel> copies = new ArrayList<>();
        for(Channel live: mConfigurationManager.getChannelModel().getChannels())
        {
            AliasListDefinition aliasList = requireAliasList(live.getAliasListId());
            copies.add(mCodec.toChannel(mCodec.fromChannel(live), aliasList, live));
        }
        return copies;
    }

    private ChannelSummary summary(Channel channel, Integer autoStartOrder)
    {
        ChannelDefinition definition = mCodec.fromChannel(channel);
        AliasListDefinition aliasList = mConfigurationManager.getAliasModel()
            .getAliasListDefinition(channel.getAliasListId());
        return new ChannelSummary(channel.getConfigurationId(), definition.protocolId(),
            mProtocolRegistry.require(definition.protocolId()).label(),
            ChannelConfigurationPolicy.requireChannelKind(channel).name(), channel.getSystem(), channel.getSite(),
            channel.getName(), definition.source().frequenciesHz(), processingState(channel), autoStartOrder,
            channel.getAliasListId(), aliasList != null ? aliasList.getName() : channel.getAliasListName());
    }

    private ProcessingState processingState(Channel channel)
    {
        return isProcessing(channel.getConfigurationId()) ? ProcessingState.RUNNING : ProcessingState.STOPPED;
    }

    private boolean isProcessing(String configurationId)
    {
        return !mConfigurationManager.getChannelProcessingManager()
            .getProcessingChainsByConfiguration(configurationId, null).isEmpty();
    }

    static Map<String,Integer> effectiveAutoStartOrder(List<Channel> channels)
    {
        List<String> enabled = effectiveAutoStartIds(channels);
        Map<String,Integer> positions = new HashMap<>();
        for(int index = 0; index < enabled.size(); index++) positions.put(enabled.get(index), index + 1);
        return Map.copyOf(positions);
    }

    static List<String> effectiveAutoStartIds(List<Channel> channels)
    {
        Map<String,Integer> tableOrder = new HashMap<>();
        for(int index = 0; index < channels.size(); index++)
        {
            tableOrder.put(channels.get(index).getConfigurationId(), index);
        }
        return channels.stream().filter(Channel::isAutoStart).sorted(Comparator
            .comparingInt((Channel channel) -> channel.getAutoStartOrder() != null &&
                channel.getAutoStartOrder() > 0 ? channel.getAutoStartOrder() : Integer.MAX_VALUE)
            .thenComparingInt(channel -> tableOrder.get(channel.getConfigurationId()))
            .thenComparing(Channel::getConfigurationId)).map(Channel::getConfigurationId).toList();
    }

    static Set<String> applyAutoStartOrder(List<Channel> channels, List<String> orderedIds)
    {
        Map<String,Integer> assigned = new HashMap<>();
        for(int index = 0; index < orderedIds.size(); index++) assigned.put(orderedIds.get(index), index + 1);
        Set<String> changed = new HashSet<>();
        for(Channel channel: channels)
        {
            Integer order = assigned.get(channel.getConfigurationId());
            boolean enabled = order != null;
            if(channel.getAutoStart() != enabled || !Objects.equals(channel.getAutoStartOrder(), order))
            {
                channel.setAutoStart(enabled);
                channel.setAutoStartOrder(order);
                changed.add(channel.getConfigurationId());
            }
        }
        return Set.copyOf(changed);
    }

    private AliasListDefinition requireAliasList(long aliasListId)
    {
        AliasListDefinition definition = mConfigurationManager.getAliasModel().getAliasListDefinition(aliasListId);
        if(definition == null) throw new NotFoundException("Alias List [" + aliasListId + "] was not found");
        return definition;
    }

    private Channel requireChannel(String configurationId)
    {
        return requireChannel(index(mConfigurationManager.getChannelModel().getChannels()), configurationId);
    }

    private static Channel requireChannel(List<Channel> channels, String configurationId)
    {
        return channels.stream().filter(channel -> configurationId.equals(channel.getConfigurationId()))
            .findFirst().orElseThrow(() -> new NotFoundException("Channel [" + configurationId + "] was not found"));
    }

    private static Channel requireChannel(Map<String,Channel> channels, String configurationId)
    {
        Channel channel = channels.get(configurationId);
        if(channel == null) throw new NotFoundException("Channel [" + configurationId + "] was not found");
        return channel;
    }

    private static int requireChannelIndex(List<Channel> channels, String configurationId)
    {
        for(int index = 0; index < channels.size(); index++)
        {
            if(configurationId.equals(channels.get(index).getConfigurationId())) return index;
        }
        throw new NotFoundException("Channel [" + configurationId + "] was not found");
    }

    private static Map<String,Channel> index(Collection<Channel> channels)
    {
        Map<String,Channel> result = new LinkedHashMap<>();
        channels.forEach(channel -> result.put(channel.getConfigurationId(), channel));
        return result;
    }

    private static void requireUniqueRadioResolveIds(List<Channel> channels)
    {
        Set<String> ids = new HashSet<>();
        for(Channel channel: channels)
        {
            String id = channel.getRadioResolveId();
            if(id != null && !ids.add(id))
            {
                throw new IllegalArgumentException("RadioResolve ID is already assigned to another channel");
            }
        }
    }

    private void requireRevision(long expectedRevision)
    {
        long actual = revision();
        if(expectedRevision != actual) throw new StaleRevisionException(expectedRevision, actual);
    }

    private long revision()
    {
        return mConfigurationManager.getChannelConfigurationRevision() & JSON_SAFE_INTEGER_MASK;
    }

    private static String requireConfigurationId(String value)
    {
        try
        {
            return UUID.fromString(value).toString();
        }
        catch(RuntimeException exception)
        {
            throw new IllegalArgumentException("Channel configuration ID must be a UUID");
        }
    }

    private static List<String> boundedConfigurationIds(Collection<String> values)
    {
        if(values == null || values.isEmpty() || values.size() > MAXIMUM_BULK_CHANNELS)
        {
            throw new IllegalArgumentException("Select between 1 and " + MAXIMUM_BULK_CHANNELS + " channels");
        }
        Set<String> unique = new LinkedHashSet<>();
        values.forEach(value -> unique.add(requireConfigurationId(value)));
        if(unique.size() != values.size()) throw new IllegalArgumentException("Channel selection contains duplicates");
        return List.copyOf(unique);
    }

    private <T> T admitted(Supplier<T> operation)
    {
        if(!mCommandAdmission.tryAcquire()) throw new ConfigurationBusyException();
        try
        {
            return operation.get();
        }
        finally
        {
            mCommandAdmission.release();
        }
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
            if(failure.get() != null) throw failure.get();
            return result.get();
        }
        if(Platform.isFxApplicationThread()) return operation.get();

        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<DispatchState> state = new AtomicReference<>(DispatchState.QUEUED);
        Platform.runLater(() ->
        {
            if(!state.compareAndSet(DispatchState.QUEUED, DispatchState.RUNNING)) return;
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
                throw new ConfigurationBusyException();
            return joinResult(result);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            if(state.compareAndSet(DispatchState.QUEUED, DispatchState.CANCELLED))
                throw new ConfigurationBusyException();
            return joinResult(result);
        }
        catch(ExecutionException exception)
        {
            if(exception.getCause() instanceof RuntimeException runtime) throw runtime;
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
            if(exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private void requireInitialized()
    {
        if(!mConfigurationManager.isInitialized()) throw new NotInitializedException();
    }

    private static String safeMessage(Exception exception)
    {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : "Channel lifecycle command failed";
    }

    public enum ProcessingState { STOPPED, RUNNING }
    public enum Direction { EARLIER, LATER }
    private enum DispatchState { QUEUED, RUNNING, CANCELLED }

    public record Catalog(long revision, List<ChannelSummary> channels)
    {
        public Catalog { channels = List.copyOf(channels); }
    }

    public record ChannelSummary(String configurationId, String protocolId, String protocolLabel, String channelKind,
                                 String system, String site, String name, List<Long> frequenciesHz,
                                 ProcessingState processingState, Integer autoStartOrder, long aliasListId,
                                 String aliasListName)
    {
        public ChannelSummary { frequenciesHz = List.copyOf(frequenciesHz); }
    }

    public record Entry(long revision, ChannelDefinition channel, ProcessingState processingState,
                        Integer autoStartOrder) {}
    public record AliasListOption(long id, String name, String family) {}
    public record Options(long revision, List<AliasListOption> aliasLists, List<String> tuners)
    {
        public Options
        {
            aliasLists = List.copyOf(aliasLists);
            tuners = List.copyOf(tuners);
        }
    }
    public record MutationResult(long revision, List<String> configurationIds, int affected)
    {
        public MutationResult { configurationIds = List.copyOf(configurationIds); }
    }
    public record LifecycleResult(String configurationId, boolean success, ProcessingState state, String message) {}
    public record BatchResult(long revision, List<LifecycleResult> results)
    {
        public BatchResult { results = List.copyOf(results); }
    }
    public record StatisticsResult(String configurationId, int rowsDeleted, String summary) {}
    private record MutationTarget(Set<String> changedIds, List<String> resultIds)
    {
        private MutationTarget
        {
            changedIds = Set.copyOf(changedIds);
            resultIds = List.copyOf(resultIds);
        }
    }

    public static class NotFoundException extends RuntimeException
    {
        public NotFoundException(String message) { super(message); }
    }
    public static class StaleRevisionException extends RuntimeException
    {
        public StaleRevisionException(long expected, long actual)
        {
            super("Expected channel revision " + expected + " but found " + actual);
        }
    }
    public static class ChannelRunningException extends RuntimeException
    {
        public ChannelRunningException(String id) { super("Channel [" + id + "] is running"); }
    }
    public static class ConfigurationBusyException extends RuntimeException {}
    public static class NotInitializedException extends RuntimeException {}
    public static class PersistenceException extends RuntimeException
    {
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
    public static class LifecycleException extends RuntimeException
    {
        public LifecycleException(String message, Throwable cause) { super(message, cause); }
    }
    public static class StatisticsException extends RuntimeException
    {
        public StatisticsException(String message, Throwable cause) { super(message, cause); }
    }
}

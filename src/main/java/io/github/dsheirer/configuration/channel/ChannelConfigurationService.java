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

package io.github.dsheirer.configuration.channel;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM.DeemphasisMode;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.Encoding;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, serialized administrator service for channel configuration and channel start/stop commands.
 *
 * <p>The service never runs on Jetty, tuner, sample, decoder, recorder, audio, or uploader threads.  Desktop builds
 * marshal model access to the JavaFX application thread so the temporary desktop editor and the web editor cannot
 * mutate the observable channel model concurrently.  Headless builds use this service's one low-priority worker.</p>
 */
public final class ChannelConfigurationService implements ChannelConfigurationOperations, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelConfigurationService.class);
    private static final int MAXIMUM_PENDING_COMMANDS = 16;
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final int MAXIMUM_SELECTION_SIZE = 100;
    private static final int MAXIMUM_FREQUENCIES = 64;
    private static final int MAXIMUM_MAP_ROWS = 256;
    private static final int MAXIMUM_TEXT_LENGTH = 160;
    private static final int MAXIMUM_ALIAS_LENGTH = 160;
    private static final int MAXIMUM_TUNER_NAME_LENGTH = 256;
    private static final long MINIMUM_FREQUENCY_HZ = 1_000_000L;
    private static final long MAXIMUM_FREQUENCY_HZ = 9_999_999_999L;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final Set<DecoderType> SUPPORTED_DECODERS = Set.copyOf(EnumSet.of(
        DecoderType.P25_CONVENTIONAL, DecoderType.P25_PHASE1, DecoderType.P25_PHASE2,
        DecoderType.DMR, DecoderType.NBFM, DecoderType.NXDN));
    private static final Set<DecoderType> NBFM_AUXILIARIES = Set.copyOf(EnumSet.of(
        DecoderType.DCS, DecoderType.FLEETSYNC2, DecoderType.LJ_1200, DecoderType.MDC1200,
        DecoderType.TAIT_1200));
    private static final ObjectMapper CONFIGURATION_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Map<DecoderType,Set<EventLogType>> ALLOWED_LOGGERS = allowedLoggers();
    private static final Map<DecoderType,Set<RecorderType>> ALLOWED_RECORDERS = allowedRecorders();

    private final Backend mBackend;
    private final ConfigurationThreadDispatcher mConfigurationDispatcher;
    private final ThreadPoolExecutor mExecutor;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final Set<FutureTask<?>> mConfigurationTasks = ConcurrentHashMap.newKeySet();
    private final IdentityHashMap<Channel,String> mIdsByChannel = new IdentityHashMap<>();
    private final Map<String,Channel> mChannelsById = new HashMap<>();
    private final SecureRandom mSecureRandom = new SecureRandom();
    private final String mGeneration = UUID.randomUUID().toString();

    public ChannelConfigurationService(ConfigurationManager configurationManager, UserPreferences userPreferences)
    {
        this(new ProductionBackend(configurationManager, userPreferences), productionDispatcher(), SHUTDOWN_TIMEOUT);
    }

    ChannelConfigurationService(Backend backend, ConfigurationThreadDispatcher configurationDispatcher,
                                Duration shutdownTimeout)
    {
        mBackend = Objects.requireNonNull(backend, "Channel backend cannot be null");
        mConfigurationDispatcher = Objects.requireNonNull(configurationDispatcher,
            "Configuration dispatcher cannot be null");
        Duration timeout = Objects.requireNonNull(shutdownTimeout, "Shutdown timeout cannot be null");
        mExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAXIMUM_PENDING_COMMANDS), runnable ->
            {
                Thread thread = new Thread(runnable, "web channel configuration");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        mShutdownTimeoutNanos = Math.max(1L, timeout.toNanos());
    }

    private final long mShutdownTimeoutNanos;

    @Override
    public CompletableFuture<Map<String,Object>> list(ChannelListRequest request)
    {
        ChannelListRequest validated = request != null ? request.validated() : ChannelListRequest.defaults();
        return submit(() -> onConfigurationThread(() -> listNow(validated)));
    }

    @Override
    public CompletableFuture<Map<String,Object>> template(String protocol)
    {
        return submit(() -> onConfigurationThread(() -> templateNow(protocol)));
    }

    @Override
    public CompletableFuture<Map<String,Object>> detail(String channelId)
    {
        return submit(() -> onConfigurationThread(() -> detailNow(requireId(channelId), true)));
    }

    @Override
    public CompletableFuture<Map<String,Object>> export(String channelId)
    {
        return submit(() -> onConfigurationThread(() -> exportNow(requireId(channelId))));
    }

    @Override
    public CompletableFuture<Map<String,Object>> create(ChannelWriteRequest request, BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return createNow(request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> update(String channelId, ChannelWriteRequest request,
                                                         BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return updateNow(requireId(channelId), request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> cloneChannel(String channelId, RevisionRequest request,
                                                               BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return cloneNow(requireId(channelId), request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> delete(String channelId, ChannelDeleteRequest request,
                                                         BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return deleteNow(requireId(channelId), request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> autoStart(String channelId, AutoStartRequest request,
                                                            BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return autoStartNow(requireId(channelId), request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> runtime(String channelId, RuntimeRequest request,
                                                          BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return runtimeNow(requireId(channelId), request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> bulkRuntime(BulkRuntimeRequest request,
                                                              BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return bulkRuntimeNow(request);
        }));
    }

    @Override
    public CompletableFuture<Map<String,Object>> setAutoStartTimeout(TimeoutRequest request,
                                                                      BooleanSupplier sessionIsValid)
    {
        return submitMutation(sessionIsValid, () -> onConfigurationThread(() ->
        {
            requireValidSession(sessionIsValid);
            return setAutoStartTimeoutNow(request);
        }));
    }

    private CompletableFuture<Map<String,Object>> submitMutation(BooleanSupplier sessionIsValid,
                                                                  Supplier<Map<String,Object>> command)
    {
        BooleanSupplier validator = Objects.requireNonNull(sessionIsValid, "Session validator cannot be null");
        return submit(() ->
        {
            if(!validator.getAsBoolean())
            {
                throw error(401, "session_expired", "Administrator sign-in expired before the command ran.");
            }

            return command.get();
        });
    }

    private static void requireValidSession(BooleanSupplier sessionIsValid)
    {
        if(!sessionIsValid.getAsBoolean())
        {
            throw error(401, "session_expired", "Administrator sign-in expired before the command ran.");
        }
    }

    private CompletableFuture<Map<String,Object>> submit(Supplier<Map<String,Object>> command)
    {
        CompletableFuture<Map<String,Object>> completion = new CompletableFuture<>();

        if(mClosed.get())
        {
            completion.completeExceptionally(unavailable());
            return completion;
        }

        try
        {
            mExecutor.execute(new SubmittedCommand(command, completion));
        }
        catch(RejectedExecutionException exception)
        {
            completion.completeExceptionally(error(503, "settings_busy",
                "Channel settings are busy. Wait a moment and try again."));
        }

        return completion;
    }

    private <T> T onConfigurationThread(Callable<T> callable)
    {
        ConfigurationTask<T> task = new ConfigurationTask<>(() ->
        {
            if(mClosed.get())
            {
                throw unavailable();
            }

            return callable.call();
        });

        synchronized(mConfigurationTasks)
        {
            if(mClosed.get())
            {
                throw unavailable();
            }

            mConfigurationTasks.add(task);
        }

        try
        {
            mConfigurationDispatcher.dispatch(task);
            return task.get();
        }
        catch(InterruptedException exception)
        {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        catch(ExecutionException exception)
        {
            Throwable cause = exception.getCause();

            if(cause instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }

            throw new IllegalStateException(cause);
        }
        catch(RuntimeException exception)
        {
            task.cancel(false);

            if(exception instanceof ChannelConfigurationException)
            {
                throw exception;
            }

            throw unavailable();
        }
    }

    private Map<String,Object> listNow(ChannelListRequest request)
    {
        List<Channel> channels = currentChannels();
        reconcileRegistry(channels);
        String search = request.query().toLowerCase(Locale.ROOT);
        List<Channel> filtered = channels.stream().filter(channel -> search.isBlank() || searchText(channel)
                .contains(search)).sorted(listComparator(request.sort(), request.direction())).toList();
        int start = Math.min(request.offset(), filtered.size());
        int end = Math.min(filtered.size(), start + request.limit());
        List<Map<String,Object>> items = new ArrayList<>(Math.max(0, end - start));

        for(int index = start; index < end; index++)
        {
            items.add(summary(filtered.get(index)));
        }

        Map<String,Object> response = new LinkedHashMap<>();
        response.put("generation", mGeneration);
        response.put("items", items);
        response.put("total", filtered.size());
        response.put("unfilteredTotal", channels.size());
        response.put("offset", start);
        response.put("limit", request.limit());
        response.put("sort", request.sort());
        response.put("direction", request.direction());
        response.put("queueRevision", queueRevision(channels));
        response.put("autoStartCount", channels.stream().filter(channel -> channel.getAutoStartOrder() != null &&
            channel.getAutoStartOrder() > 0).count());
        response.put("options", options());
        return response;
    }

    private Map<String,Object> templateNow(String protocol)
    {
        DecoderType decoderType = parseSupportedDecoder(protocol);
        Channel channel = new Channel();
        channel.setSystem("");
        channel.setSite("");
        channel.setName("");
        channel.setRadresGuid(UUID.randomUUID().toString());
        channel.setAutoStart(false);
        channel.setAutoStartOrder(null);
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoderType));
        channel.setSourceConfiguration(defaultSource(decoderType));
        Map<String,Object> detail = detail(channel, null, null, true);
        detail.put("template", true);
        return detail;
    }

    private Map<String,Object> detailNow(String channelId, boolean includeOptions)
    {
        Channel channel = channel(channelId);
        return mBackend.withChannelConfigurationLock(channel,
            () -> detail(channel, channelId, revision(channel), includeOptions));
    }

    private Map<String,Object> exportNow(String channelId)
    {
        Channel channel = channel(channelId);
        return mBackend.withChannelConfigurationLock(channel, () -> exportLocked(channel));
    }

    private Map<String,Object> exportLocked(Channel channel)
    {

        try
        {
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("fileName", safeFileName(channel) + ".json");
            response.put("configuration", CONFIGURATION_MAPPER.readTree(
                CONFIGURATION_MAPPER.writeValueAsBytes(channel)));
            return response;
        }
        catch(Exception exception)
        {
            throw error(500, "export_failed", "The channel configuration could not be exported.");
        }
    }

    private Map<String,Object> createNow(ChannelWriteRequest request)
    {
        ChannelWriteRequest write = requireWrite(request, false);
        DecoderType decoderType = write.decoder().decoderType();
        Channel channel = new Channel();
        channel.setRadresGuid(UUID.randomUUID().toString());
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoderType));
        channel.setSourceConfiguration(defaultSource(decoderType));
        applyWrite(channel, write, null);
        ensureUniqueGuid(channel.getRadresGuid(), null);
        channel.setAutoStart(false);
        channel.setAutoStartOrder(null);

        try
        {
            mBackend.add(channel, mBackend.channels().size());
            mBackend.flushOrThrow();
        }
        catch(RuntimeException exception)
        {
            boolean rollbackComplete = rollback(() -> mBackend.remove(channel), mBackend::flushOrThrow);
            throw persistenceFailure(exception, rollbackComplete, null);
        }

        reconcileRegistry(currentChannels());
        String id = id(channel);
        Map<String,Object> response = detail(channel, id, revision(channel), true);
        response.put("operation", operation("created", true, "Channel saved."));
        return response;
    }

    private Map<String,Object> updateNow(String channelId, ChannelWriteRequest request)
    {
        Channel live = channel(channelId);
        return mBackend.withChannelConfigurationLock(live, () -> updateLocked(channelId, live, request));
    }

    private Map<String,Object> updateLocked(String channelId, Channel live, ChannelWriteRequest request)
    {
        ChannelWriteRequest write = requireWrite(request, true);
        requireRevision(live, write.revision());
        requireSupported(live);

        if(live.getDecodeConfiguration().getDecoderType() != write.decoder().decoderType())
        {
            throw invalid("A saved channel's decoder type cannot be changed.");
        }

        Channel before = copy(live);
        Channel replacement = copy(live);
        //The Saved Channels queue owns these fields.  A normal editor save must never alter the channel's
        //automatic-start membership or position, even if a future copy/serialization change omits them.
        replacement.setAutoStart(live.isAutoStart());
        replacement.setAutoStartOrder(live.hasAutoStartOrder() ? live.getAutoStartOrder() : null);
        applyWrite(replacement, write, live);
        ensureUniqueGuid(replacement.getRadresGuid(), live);
        boolean running = isProcessing(live);
        ApplyPolicy policy = write.applyPolicy() != null ? write.applyPolicy() : ApplyPolicy.APPLY;

        if(running && policy == ApplyPolicy.APPLY)
        {
            throw error(409, "running_policy_required",
                "Choose whether to save for the next start, stop, or stop and restart this running channel.");
        }

        if(running && policy == ApplyPolicy.NEXT_START && changesRadioConfiguration(live, replacement))
        {
            throw error(409, "stop_required",
                "Source, decoder, or auxiliary-decoder changes require stopping the running channel first.");
        }

        boolean stopped = false;

        if(running && (policy == ApplyPolicy.STOP || policy == ApplyPolicy.RESTART))
        {
            stop(live);
            stopped = true;
        }

        if(running && policy == ApplyPolicy.NEXT_START)
        {
            //Keep the exact live radio objects attached to the processing chain.  NEXT_START is only available when
            //their content is unchanged, and replacing equivalent objects would still be an unsafe live mutation.
            replacement.setSourceConfiguration(live.getSourceConfiguration());
            replacement.setDecodeConfiguration(live.getDecodeConfiguration());
            replacement.setAuxDecodeConfiguration(live.getAuxDecodeConfiguration());
        }

        try
        {
            applyChannel(live, replacement);
            mBackend.flushOrThrow();
        }
        catch(RuntimeException exception)
        {
            boolean rollbackComplete = rollback(() -> applyChannel(live, before), mBackend::flushOrThrow);

            if(stopped)
            {
                rollbackComplete &= rollback(() -> start(live));
            }

            throw persistenceFailure(exception, rollbackComplete,
                "The channel is currently " + (isProcessing(live) ? "running." : "stopped."));
        }

        boolean restartAttempted = running && policy == ApplyPolicy.RESTART;
        boolean restartSucceeded = false;
        String message;

        if(restartAttempted)
        {
            try
            {
                start(live);
                restartSucceeded = true;
                message = "Channel saved and restarted.";
            }
            catch(ChannelConfigurationException exception)
            {
                message = "Channel was saved, but it could not restart: " + exception.getMessage();
            }
        }
        else if(running && policy == ApplyPolicy.NEXT_START)
        {
            message = "Channel saved for its next start. The running decoder was not interrupted.";
        }
        else if(stopped)
        {
            message = "Channel stopped and the saved configuration was applied.";
        }
        else
        {
            message = "Channel saved.";
        }

        Map<String,Object> response = detail(live, channelId, revision(live), true);
        Map<String,Object> operation = operation("updated", true, message);
        operation.put("restartAttempted", restartAttempted);
        operation.put("restartSucceeded", restartAttempted ? restartSucceeded : null);
        response.put("operation", operation);
        return response;
    }

    private Map<String,Object> cloneNow(String channelId, RevisionRequest request)
    {
        Channel source = channel(channelId);
        return mBackend.withChannelConfigurationLock(source, () -> cloneLocked(source, request));
    }

    private Map<String,Object> cloneLocked(Channel source, RevisionRequest request)
    {
        requireRevision(source, requireRevisionRequest(request).revision());
        requireSupported(source);
        Channel clone = copy(source);
        clone.setName(copyName(source.getName()));
        clone.setRadresGuid(UUID.randomUUID().toString());
        clone.setAutoStart(false);
        clone.setAutoStartOrder(null);

        try
        {
            mBackend.add(clone, mBackend.channels().size());
            mBackend.flushOrThrow();
        }
        catch(RuntimeException exception)
        {
            boolean rollbackComplete = rollback(() -> mBackend.remove(clone), mBackend::flushOrThrow);
            throw persistenceFailure(exception, rollbackComplete, null);
        }

        reconcileRegistry(currentChannels());
        String cloneId = id(clone);
        Map<String,Object> response = detail(clone, cloneId, revision(clone), true);
        response.put("operation", operation("cloned", true,
            "Channel cloned with a new Site GUID and no automatic-start position."));
        return response;
    }

    private Map<String,Object> deleteNow(String channelId, ChannelDeleteRequest request)
    {
        ChannelDeleteRequest delete = requiredValue(request, "A delete request is required.");

        if(!delete.confirm())
        {
            throw invalid("Deletion must be explicitly confirmed.");
        }

        Channel channel = channel(channelId);
        return mBackend.withChannelConfigurationLock(channel, () -> deleteLocked(channelId, channel, delete));
    }

    private Map<String,Object> deleteLocked(String channelId, Channel channel, ChannelDeleteRequest delete)
    {
        requireRevision(channel, required(delete.revision(), "A channel revision is required."));
        boolean wasRunning = isProcessing(channel);

        if(wasRunning)
        {
            stop(channel);
        }

        List<Channel> beforeOrder = currentChannels();
        int index = beforeOrder.indexOf(channel);
        Map<Channel,AutoStartState> queueBefore = captureAutoStart(beforeOrder);

        try
        {
            mBackend.remove(channel);
            normalizeAutoStart(currentChannels());
            mBackend.flushOrThrow();
        }
        catch(RuntimeException exception)
        {
            boolean rollbackComplete = rollback(() -> mBackend.add(channel, Math.max(0, index)),
                () -> restoreAutoStart(queueBefore), mBackend::flushOrThrow);

            if(wasRunning)
            {
                rollbackComplete &= rollback(() -> start(channel));
            }

            throw persistenceFailure(exception, rollbackComplete,
                "The channel is currently " + (isProcessing(channel) ? "running." : "stopped."));
        }

        unregister(channel);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("deleted", true);
        response.put("id", channelId);
        response.put("queueRevision", queueRevision(currentChannels()));
        response.put("operation", operation("deleted", true, "Channel deleted."));
        return response;
    }

    private Map<String,Object> autoStartNow(String channelId, AutoStartRequest request)
    {
        AutoStartRequest command = requiredValue(request, "An automatic-start request is required.");
        AutoStartAction action = requiredValue(command.action(), "An automatic-start action is required.");
        Channel target = channel(channelId);
        requireSupported(target);
        requireRevision(target, required(command.revision(), "A channel revision is required."));
        List<Channel> channels = currentChannels();
        String currentQueueRevision = queueRevision(channels);

        if(!Objects.equals(currentQueueRevision, required(command.queueRevision(),
            "The automatic-start queue revision is required.")))
        {
            throw error(409, "queue_changed", "The automatic-start order changed. Reload it before trying again.");
        }

        List<Channel> existingOrder = orderedAutoStart(channels);
        int existingIndex = existingOrder.indexOf(target);

        switch(action)
        {
            case ENABLE ->
            {
                if(existingIndex >= 0)
                {
                    throw invalid("The channel is already in the automatic-start order.");
                }
            }
            case DISABLE ->
            {
                if(existingIndex != 0)
                {
                    throw invalid("Move the channel to position 1 before removing it from automatic start.");
                }
            }
            case EARLIER -> validateAutoStartMove(existingOrder, existingIndex, existingIndex - 1);
            case LATER -> validateAutoStartMove(existingOrder, existingIndex, existingIndex + 1);
        }

        Map<Channel,AutoStartState> before = captureAutoStart(channels);

        try
        {
            List<Channel> ordered = normalizeAutoStart(channels);
            int index = ordered.indexOf(target);

            switch(action)
            {
                case ENABLE ->
                {
                    for(Channel channel: ordered)
                    {
                        channel.setAutoStartOrder(channel.getAutoStartOrder() + 1);
                    }

                    target.setAutoStart(true);
                    target.setAutoStartOrder(1);
                }
                case DISABLE ->
                {
                    target.setAutoStart(false);
                    target.setAutoStartOrder(null);
                    normalizeAutoStart(channels);
                }
                case EARLIER -> swapAutoStart(ordered, index, index - 1);
                case LATER -> swapAutoStart(ordered, index, index + 1);
            }

            mBackend.flushOrThrow();
        }
        catch(RuntimeException exception)
        {
            boolean rollbackComplete = rollback(() -> restoreAutoStart(before), mBackend::flushOrThrow);
            throw persistenceFailure(exception, rollbackComplete, null);
        }

        Map<String,Object> response = new LinkedHashMap<>();
        response.put("id", channelId);
        response.put("revision", revision(target));
        response.put("autoStartOrder", autoStartOrder(target));
        response.put("queueRevision", queueRevision(channels));
        response.put("orders", autoStartOrders(channels));
        response.put("message", autoStartMessage(target, action));
        return response;
    }

    private Map<String,Object> runtimeNow(String channelId, RuntimeRequest request)
    {
        RuntimeRequest command = requiredValue(request, "A runtime request is required.");
        RuntimeAction action = requiredValue(command.action(), "A runtime action is required.");
        Channel channel = channel(channelId);
        return mBackend.withChannelConfigurationLock(channel, () -> runtimeLocked(channelId, channel, command,
            action));
    }

    private Map<String,Object> runtimeLocked(String channelId, Channel channel, RuntimeRequest command,
                                              RuntimeAction action)
    {
        requireSupported(channel);
        requireRevision(channel, required(command.revision(), "A channel revision is required."));
        boolean changed = action == RuntimeAction.START ? start(channel) : stop(channel);
        Map<String,Object> response = detail(channel, channelId, revision(channel), true);
        response.put("operation", operation(action == RuntimeAction.START ? "started" : "stopped", changed,
            action == RuntimeAction.START ? (changed ? "Channel started." : "Channel was already running.") :
                (changed ? "Channel stopped." : "Channel was already stopped.")));
        return response;
    }

    private Map<String,Object> bulkRuntimeNow(BulkRuntimeRequest request)
    {
        BulkRuntimeRequest command = requiredValue(request, "A selected-channel request is required.");
        RuntimeAction action = requiredValue(command.action(), "A runtime action is required.");
        List<ChannelReference> references = command.channels() != null ? command.channels() : List.of();

        if(references.isEmpty() || references.size() > MAXIMUM_SELECTION_SIZE)
        {
            throw invalid("Select between 1 and " + MAXIMUM_SELECTION_SIZE + " channels.");
        }

        LinkedHashMap<String,ChannelReference> unique = new LinkedHashMap<>();

        for(ChannelReference reference: references)
        {
            if(reference == null)
            {
                throw invalid("A selected channel is invalid.");
            }

            String id = requireId(reference.id());

            if(unique.putIfAbsent(id, reference) != null)
            {
                throw invalid("A channel was selected more than once.");
            }
        }

        List<Map<String,Object>> results = new ArrayList<>();

        for(Map.Entry<String,ChannelReference> entry: unique.entrySet())
        {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("id", entry.getKey());

            try
            {
                Channel channel = channel(entry.getKey());
                mBackend.withChannelConfigurationLock(channel, () ->
                {
                    requireSupported(channel);
                    requireRevision(channel, required(entry.getValue().revision(),
                        "A channel revision is required."));
                    boolean changed = action == RuntimeAction.START ? start(channel) : stop(channel);
                    result.put("success", true);
                    result.put("changed", changed);
                    result.put("state", state(channel));
                    result.put("message", action == RuntimeAction.START ?
                        (changed ? "Started" : "Already running") :
                        (changed ? "Stopped" : "Already stopped"));
                    return null;
                });
            }
            catch(ChannelConfigurationException exception)
            {
                result.put("success", false);
                result.put("changed", false);
                result.put("code", exception.code());
                result.put("message", exception.getMessage());
            }

            results.add(result);
        }

        long successes = results.stream().filter(result -> Boolean.TRUE.equals(result.get("success"))).count();
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("action", action.name());
        response.put("selected", results.size());
        response.put("succeeded", successes);
        response.put("failed", results.size() - successes);
        response.put("results", results);
        return response;
    }

    private Map<String,Object> setAutoStartTimeoutNow(TimeoutRequest request)
    {
        TimeoutRequest update = requiredValue(request, "An automatic-start timeout request is required.");

        if(update.seconds() == null || update.seconds() < 0 || update.seconds() > 30)
        {
            throw invalid("Automatic-start timeout must be from 0 to 30 seconds.");
        }

        try
        {
            mBackend.setAutoStartTimeoutSeconds(update.seconds());
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to persist the automatic-start timeout", exception);
            boolean rollbackComplete = exception.getSuppressed().length == 0;
            throw error(500, rollbackComplete ? "save_failed" : "rollback_incomplete",
                rollbackComplete ? "The automatic-start timeout could not be saved and its prior value was restored." :
                    "The automatic-start timeout could not be saved, and storage could not confirm its prior value.");
        }

        return Map.of("autoStartTimeoutSeconds", update.seconds(),
            "message", "Automatic-start timeout saved.");
    }

    private Map<String,Object> summary(Channel channel)
    {
        return mBackend.withChannelConfigurationLock(channel, () -> summaryLocked(channel));
    }

    private Map<String,Object> summaryLocked(Channel channel)
    {
        String id = id(channel);
        List<Long> frequencies = frequencies(channel.getSourceConfiguration());
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("revision", revision(channel));
        item.put("system", text(channel.getSystem()));
        item.put("site", text(channel.getSite()));
        item.put("name", text(channel.getName()));
        item.put("protocol", protocol(channel));
        item.put("protocolLabel", protocolLabel(channel));
        item.put("frequenciesHz", frequencies);
        item.put("primaryFrequencyHz", highestFrequency(frequencies));
        item.put("processing", isProcessing(channel));
        item.put("state", state(channel));
        item.put("autoStartOrder", autoStartOrder(channel));
        item.put("supported", isSupported(channel));
        return item;
    }

    private Map<String,Object> detail(Channel channel, String id, String revision, boolean includeOptions)
    {
        return mBackend.withChannelConfigurationLock(channel,
            () -> detailLocked(channel, id, revision, includeOptions));
    }

    private Map<String,Object> detailLocked(Channel channel, String id, String revision, boolean includeOptions)
    {
        Map<String,Object> response = summaryWithoutRegistration(channel, id, revision);
        response.put("guid", channel.hasRadresGuid() ? channel.getRadresGuid() : null);
        response.put("aliasList", text(channel.getAliasListName()));
        response.put("source", sourceMap(channel.getSourceConfiguration(),
            channel.getDecodeConfiguration() != null ? channel.getDecodeConfiguration().getDecoderType() : null));
        response.put("decoder", decoderMap(channel.getDecodeConfiguration()));
        response.put("auxiliaries", channel.getAuxDecodeConfiguration() != null ?
            channel.getAuxDecodeConfiguration().getAuxDecoders().stream().map(Enum::name).toList() : List.of());
        response.put("logging", channel.getEventLogConfiguration() != null ?
            channel.getEventLogConfiguration().getLoggers().stream().map(Enum::name).toList() : List.of());
        response.put("recording", channel.getRecordConfiguration() != null ?
            channel.getRecordConfiguration().getRecorders().stream().map(Enum::name).toList() : List.of());
        response.put("queueRevision", queueRevision(currentChannels()));

        if(includeOptions)
        {
            response.put("options", options());
        }

        if(!isSupported(channel))
        {
            response.put("unsupportedReason", unsupportedReason(channel));
        }

        return response;
    }

    private Map<String,Object> summaryWithoutRegistration(Channel channel, String id, String revision)
    {
        List<Long> frequencies = frequencies(channel.getSourceConfiguration());
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("generation", mGeneration);
        response.put("id", id);
        response.put("revision", revision);
        response.put("system", text(channel.getSystem()));
        response.put("site", text(channel.getSite()));
        response.put("name", text(channel.getName()));
        response.put("protocol", protocol(channel));
        response.put("protocolLabel", protocolLabel(channel));
        response.put("frequenciesHz", frequencies);
        response.put("primaryFrequencyHz", highestFrequency(frequencies));
        response.put("processing", isProcessing(channel));
        response.put("state", state(channel));
        response.put("autoStartOrder", autoStartOrder(channel));
        response.put("supported", isSupported(channel));
        return response;
    }

    private Map<String,Object> sourceMap(SourceConfiguration source, DecoderType decoderType)
    {
        Map<String,Object> value = new LinkedHashMap<>();

        if(source instanceof SourceConfigTuner tuner && decoderType != null && isMultiFrequencyDecoder(decoderType))
        {
            value.put("kind", SourceKind.MULTIPLE.name());
            value.put("frequenciesHz", tuner.getFrequency() > 0 ? List.of(tuner.getFrequency()) : List.of());
            value.put("preferredTuner", text(tuner.getPreferredTuner()));
            value.put("rotationDelayMs", defaultRotationDelay(decoderType));
        }
        else if(source instanceof SourceConfigTuner tuner)
        {
            value.put("kind", SourceKind.SINGLE.name());
            value.put("frequenciesHz", tuner.getFrequency() > 0 ? List.of(tuner.getFrequency()) : List.of());
            value.put("preferredTuner", text(tuner.getPreferredTuner()));
            value.put("rotationDelayMs", null);
        }
        else if(source instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            value.put("kind", SourceKind.MULTIPLE.name());
            value.put("frequenciesHz", List.copyOf(multiple.getFrequencies()));
            value.put("preferredTuner", text(multiple.getPreferredTuner()));
            value.put("rotationDelayMs", multiple.getFrequencyRotationDelay());
        }
        else
        {
            value.put("kind", source != null && source.getSourceType() != null ? source.getSourceType().name() :
                SourceType.NONE.name());
            value.put("frequenciesHz", List.of());
            value.put("preferredTuner", "");
            value.put("rotationDelayMs", null);
        }

        return value;
    }

    private Map<String,Object> decoderMap(DecodeConfiguration decoder)
    {
        Map<String,Object> value = new LinkedHashMap<>();

        if(decoder == null)
        {
            value.put("type", "UNKNOWN");
            return value;
        }

        value.put("type", decoder.getDecoderType().name());

        switch(decoder.getDecoderType())
        {
            case P25_CONVENTIONAL -> { }
            case P25_PHASE1 ->
            {
                DecodeConfigP25Phase1 p25 = (DecodeConfigP25Phase1)decoder;
                addP25(value, p25);
                value.put("modulation", p25.getModulation() == Modulation.CQPSK ? "LSM" : "C4FM");
            }
            case P25_PHASE2 ->
            {
                DecodeConfigP25Phase2 p25 = (DecodeConfigP25Phase2)decoder;
                addP25(value, p25);
                value.put("autoDetectScrambleParameters", p25.isAutoDetectScrambleParameters());
                ScrambleParameters parameters = p25.getScrambleParameters();
                value.put("wacn", parameters != null ? hex(parameters.getWACN(), 5) : "");
                value.put("p25System", parameters != null ? hex(parameters.getSystem(), 3) : "");
                value.put("nac", parameters != null ? hex(parameters.getNAC(), 3) : "");
            }
            case DMR ->
            {
                DecodeConfigDMR dmr = (DecodeConfigDMR)decoder;
                value.put("maximumTrafficChannels", dmr.getTrafficChannelPoolSize());
                value.put("ignoreDataCalls", dmr.getIgnoreDataCalls());
                value.put("ignoreCrcChecksums", dmr.getIgnoreCRCChecksums());
                value.put("compressedTalkgroups", dmr.isUseCompressedTalkgroups());
                value.put("frequencyMap", dmr.getTimeslotMap().stream().map(mapping ->
                {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("number", mapping.getNumber());
                    row.put("downlinkHz", mapping.getDownlinkFrequency());
                    row.put("uplinkHz", mapping.getUplinkFrequency() > 0 ? mapping.getUplinkFrequency() : null);
                    return row;
                }).toList());
            }
            case NXDN ->
            {
                DecodeConfigNXDN nxdn = (DecodeConfigNXDN)decoder;
                value.put("maximumTrafficChannels", nxdn.getTrafficChannelPoolSize());
                value.put("ignoreDataCalls", nxdn.isIgnoreDataCalls());
                value.put("ignoreEncryptedCalls", nxdn.isIgnoreEncryptedCalls());
                value.put("transmissionMode", nxdn.getTransmissionMode().name());
                value.put("encoding", nxdn.getEncoding().name());
                value.put("frequencyMap", nxdn.getChannelMap().stream().map(mapping ->
                {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("number", mapping.getChannel());
                    row.put("downlinkHz", mapping.getDownlink());
                    row.put("uplinkHz", mapping.getUplink() > 0 ? mapping.getUplink() : null);
                    return row;
                }).toList());
            }
            case NBFM ->
            {
                DecodeConfigNBFM nbfm = (DecodeConfigNBFM)decoder;
                value.put("bandwidth", nbfm.getBandwidth().name());
                value.put("talkgroup", nbfm.getTalkgroup());
                value.put("deemphasis", nbfm.getDeemphasis().name());
                value.put("outputGain", nbfm.getOutputGain());
                value.put("highPassEnabled", nbfm.isAudioFilter());
                value.put("lowPassEnabled", nbfm.isLowPassEnabled());
                value.put("lowPassCutoffHz", nbfm.getLowPassCutoff());
                value.put("voiceEnhancePercent", nbfm.getVoiceEnhanceAmount());
                value.put("squelchTrimEnabled", nbfm.isSquelchTailRemovalEnabled());
                value.put("tailTrimMs", nbfm.getSquelchTailRemovalMs());
                value.put("headTrimMs", nbfm.getSquelchHeadRemovalMs());
                value.put("bassBoostDb", nbfm.getBassBoostDb());
            }
            default -> { }
        }

        return value;
    }

    private static void addP25(Map<String,Object> value, DecodeConfigP25 p25)
    {
        value.put("maximumTrafficChannels", p25.getTrafficChannelPoolSize());
        value.put("ignoreDataCalls", p25.getIgnoreDataCalls());
        value.put("learnAnnouncedControlChannels", p25.getLearnAnnouncedControlChannels());
    }

    private Map<String,Object> options()
    {
        Map<String,Object> options = new LinkedHashMap<>();
        List<String> aliasLists = new ArrayList<>(mBackend.aliasLists());
        aliasLists.removeIf(value -> value == null || AliasModel.NO_ALIAS_LIST.equals(value));
        aliasLists.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> tuners = new ArrayList<>(mBackend.preferredTunerNames());
        tuners.removeIf(Objects::isNull);
        tuners.sort(String.CASE_INSENSITIVE_ORDER);
        options.put("aliasLists", aliasLists.stream().distinct().toList());
        options.put("preferredTuners", tuners.stream().distinct().toList());
        options.put("supportedProtocols", List.of(
            protocolOption(DecoderType.P25_CONVENTIONAL, "P25 Conventional"),
            protocolOption(DecoderType.P25_PHASE1, "P25 Trunked Phase 1"),
            protocolOption(DecoderType.P25_PHASE2, "P25 Trunked Phase 2"),
            protocolOption(DecoderType.DMR, "DMR"),
            protocolOption(DecoderType.NBFM, "NBFM"),
            protocolOption(DecoderType.NXDN, "NXDN")));
        options.put("autoStartTimeoutSeconds", mBackend.autoStartTimeoutSeconds());
        options.put("jmbeConfigured", mBackend.jmbeConfigured());
        options.put("maximumPageSize", MAXIMUM_PAGE_SIZE);
        options.put("maximumSelectionSize", MAXIMUM_SELECTION_SIZE);
        options.put("maximumFrequencies", MAXIMUM_FREQUENCIES);
        options.put("maximumMapRows", MAXIMUM_MAP_ROWS);
        return options;
    }

    private static Map<String,Object> protocolOption(DecoderType type, String label)
    {
        return Map.of("id", type.name(), "label", label);
    }

    private void applyWrite(Channel target, ChannelWriteRequest request, Channel existing)
    {
        String system = boundedOptional(request.system(), MAXIMUM_TEXT_LENGTH, "System");
        String site = boundedOptional(request.site(), MAXIMUM_TEXT_LENGTH, "Site");
        String name = boundedOptional(request.name(), MAXIMUM_TEXT_LENGTH, "Name");
        String guid = required(request.guid(), "A Site GUID is required.").strip();

        try
        {
            guid = UUID.fromString(guid).toString();
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid("Site GUID must be a complete UUID.");
        }

        if(existing != null && !guid.equalsIgnoreCase(existing.getRadresGuid()))
        {
            if(isProcessing(existing))
            {
                throw error(409, "stop_required", "Stop the channel before changing its Site GUID.");
            }

            if(!Boolean.TRUE.equals(request.confirmGuidChange()))
            {
                throw error(409, "guid_confirmation_required",
                    "Unlock and confirm the Site GUID warning before saving this change.");
            }
        }

        String aliasList = boundedOptional(request.aliasList(), MAXIMUM_ALIAS_LENGTH, "Alias List");

        if(AliasModel.NO_ALIAS_LIST.equals(aliasList) ||
            !aliasList.isBlank() && mBackend.aliasLists().stream().noneMatch(aliasList::equals))
        {
            throw invalid("Select an existing alias list.");
        }

        SourceRequest sourceRequest = requiredValue(request.source(), "Source settings are required.");
        DecoderRequest decoderRequest = requiredValue(request.decoder(), "Decoder settings are required.");
        DecoderType decoderType = decoderRequest.decoderType();

        if(!SUPPORTED_DECODERS.contains(decoderType))
        {
            throw invalid("That decoder type is not supported by the web channel editor.");
        }

        target.setSystem(system);
        target.setSite(site);
        target.setName(name);
        target.setRadresGuid(guid);
        target.setAliasListName(aliasList.isBlank() ? null : aliasList);
        target.setSourceConfiguration(buildSource(sourceRequest, decoderType,
            existing != null ? existing.getSourceConfiguration() : null));
        target.setDecodeConfiguration(buildDecoder(decoderRequest,
            existing != null ? existing.getDecodeConfiguration() : target.getDecodeConfiguration()));
        target.setAuxDecodeConfiguration(buildAuxiliaries(request.auxiliaries(), decoderType,
            existing != null ? existing.getAuxDecodeConfiguration() : null));
        target.setEventLogConfiguration(buildLoggers(request.logging(), decoderType,
            existing != null ? existing.getEventLogConfiguration() : null));
        target.setRecordConfiguration(buildRecorders(request.recording(), decoderType,
            existing != null ? existing.getRecordConfiguration() : null));
    }

    private SourceConfiguration buildSource(SourceRequest request, DecoderType decoderType,
                                            SourceConfiguration existing)
    {
        requiredValue(request.kind(), "Source kind is required.");
        boolean multi = isMultiFrequencyDecoder(decoderType);
        SourceKind expected = multi ? SourceKind.MULTIPLE : SourceKind.SINGLE;

        if(request.kind() != expected)
        {
            throw invalid(protocolLabel(decoderType) + " requires a " +
                (multi ? "control-frequency list." : "single frequency."));
        }

        List<Long> frequencies = validatedFrequencies(request.frequenciesHz(), multi);
        String preferredTuner = boundedOptional(request.preferredTuner(), MAXIMUM_TUNER_NAME_LENGTH,
            "Preferred tuner");

        if(!multi)
        {
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(frequencies.get(0));
            source.setPreferredTuner(preferredTuner.isBlank() ? null : preferredTuner);
            return source;
        }

        int minimumDelay = decoderType == DecoderType.P25_PHASE1 ? 400 : 200;
        Integer requestedDelay = request.rotationDelayMs();

        if(requestedDelay == null || requestedDelay < minimumDelay || requestedDelay > 2000)
        {
            throw invalid("Frequency rotation delay must be from " + minimumDelay + " to 2000 milliseconds.");
        }

        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.setFrequencies(frequencies);
        source.setPreferredTuner(preferredTuner.isBlank() ? null : preferredTuner);
        source.setFrequencyRotationDelay(requestedDelay);

        if(existing instanceof SourceConfigTunerMultipleFrequency previous)
        {
            source.setMinimumFrequency(previous.getMinimumFrequency());
            source.setMaximumFrequency(previous.getMaximumFrequency());
            long preferred = previous.getPreferredFrequency();

            if(frequencies.contains(preferred))
            {
                source.setPreferredFrequency(preferred);
            }
        }

        return source;
    }

    private DecodeConfiguration buildDecoder(DecoderRequest request, DecodeConfiguration existing)
    {
        return switch(request)
        {
            case P25ConventionalRequest ignored -> new DecodeConfigP25Conventional();
            case P25Phase1Request p25 ->
            {
                DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
                setP25(config, p25.maximumTrafficChannels(), p25.ignoreDataCalls(),
                    p25.learnAnnouncedControlChannels());
                config.setModulation(parseP25Modulation(p25.modulation()));
                yield config;
            }
            case P25Phase2Request p25 ->
            {
                DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
                setP25(config, p25.maximumTrafficChannels(), p25.ignoreDataCalls(),
                    p25.learnAnnouncedControlChannels());
                config.setAutoDetectScrambleParameters(requiredBoolean(p25.autoDetectScrambleParameters(),
                    "Automatic parameter detection is required."));
                ScrambleParameters prior = existing instanceof DecodeConfigP25Phase2 old ?
                    old.getScrambleParameters() : null;
                config.setScrambleParameters(scrambleParameters(p25, prior));
                yield config;
            }
            case DmrRequest dmr ->
            {
                DecodeConfigDMR config = new DecodeConfigDMR();
                config.setTrafficChannelPoolSize(trafficLimit(dmr.maximumTrafficChannels()));
                config.setIgnoreDataCalls(requiredBoolean(dmr.ignoreDataCalls(), "Ignore data calls is required."));
                config.setIgnoreCRCChecksums(requiredBoolean(dmr.ignoreCrcChecksums(),
                    "Ignore CRC checksums is required."));
                config.setUseCompressedTalkgroups(requiredBoolean(dmr.compressedTalkgroups(),
                    "Compressed talkgroups is required."));
                config.setTimeslotMap(dmrMap(dmr.frequencyMap(),
                    existing instanceof DecodeConfigDMR old ? old : null));
                yield config;
            }
            case NxdnRequest nxdn ->
            {
                DecodeConfigNXDN config = new DecodeConfigNXDN();
                config.setTrafficChannelPoolSize(trafficLimit(nxdn.maximumTrafficChannels()));
                config.setIgnoreDataCalls(requiredBoolean(nxdn.ignoreDataCalls(), "Ignore data calls is required."));
                config.setIgnoreEncryptedCalls(requiredBoolean(nxdn.ignoreEncryptedCalls(),
                    "Ignore encrypted calls is required."));
                config.setTransmissionMode(parseEnum(TransmissionMode.class, nxdn.transmissionMode(), "NXDN mode"));
                config.setEncoding(parseEnum(Encoding.class, nxdn.encoding(), "Talker alias encoding"));
                config.setChannelMap(nxdnMap(nxdn.frequencyMap(),
                    existing instanceof DecodeConfigNXDN old ? old : null));
                yield config;
            }
            case NbfmRequest nbfm ->
            {
                DecodeConfigNBFM config = existing instanceof DecodeConfigNBFM old ?
                    (DecodeConfigNBFM)copyDecode(old) : new DecodeConfigNBFM();
                Bandwidth bandwidth = parseEnum(Bandwidth.class, nbfm.bandwidth(), "NBFM bandwidth");

                if(!bandwidth.isFM())
                {
                    throw invalid("NBFM bandwidth must be 7.5, 12.5, or 25 kHz.");
                }

                config.setBandwidth(bandwidth);
                config.setTalkgroup(boundedInt(nbfm.talkgroup(), 1, 65535, "Talkgroup"));
                config.setDeemphasis(parseEnum(DeemphasisMode.class, nbfm.deemphasis(), "De-emphasis"));
                config.setOutputGain(boundedFloat(nbfm.outputGain(), 0.25f, 4.0f, "Output gain"));
                config.setAudioFilter(requiredBoolean(nbfm.highPassEnabled(), "High-pass setting is required."));
                config.setLowPassEnabled(requiredBoolean(nbfm.lowPassEnabled(), "Low-pass setting is required."));
                config.setLowPassCutoff(boundedInt(nbfm.lowPassCutoffHz(), 2000, 4000, "Low-pass cutoff"));
                config.setVoiceEnhanceAmount(boundedFloat(nbfm.voiceEnhancePercent(), 0, 100,
                    "Voice enhance"));
                config.setSquelchTailRemovalEnabled(requiredBoolean(nbfm.squelchTrimEnabled(),
                    "Squelch trim setting is required."));
                config.setSquelchTailRemovalMs(boundedInt(nbfm.tailTrimMs(), 0, 300, "Tail trim"));
                config.setSquelchHeadRemovalMs(boundedInt(nbfm.headTrimMs(), 0, 150, "Head trim"));
                config.setBassBoostDb(boundedFloat(nbfm.bassBoostDb(), 0, 12, "Bass boost"));
                yield config;
            }
        };
    }

    private static void setP25(DecodeConfigP25 config, Integer maximumTrafficChannels, Boolean ignoreDataCalls,
                               Boolean learnControlChannels)
    {
        config.setTrafficChannelPoolSize(trafficLimit(maximumTrafficChannels));
        config.setIgnoreDataCalls(requiredBoolean(ignoreDataCalls, "Ignore data calls is required."));
        config.setLearnAnnouncedControlChannels(requiredBoolean(learnControlChannels,
            "Learn announced control channels is required."));
    }

    private static ScrambleParameters scrambleParameters(P25Phase2Request request, ScrambleParameters prior)
    {
        if(Boolean.TRUE.equals(request.autoDetectScrambleParameters()))
        {
            return prior != null ? prior.copy() : null;
        }

        String wacn = optional(request.wacn());
        String system = optional(request.p25System());
        String nac = optional(request.nac());

        if(wacn.isBlank() && system.isBlank() && nac.isBlank())
        {
            return null;
        }

        if(wacn.isBlank() || system.isBlank() || nac.isBlank())
        {
            throw invalid("WACN, P25 System, and NAC must all be supplied for manual parameters.");
        }

        return new ScrambleParameters(parseHex(wacn, 0xFFFFF, "WACN"),
            parseHex(system, 0xFFF, "P25 System"), parseHex(nac, 0xFFF, "NAC"));
    }

    private static List<TimeslotFrequency> dmrMap(List<FrequencyMapRequest> rows, DecodeConfigDMR existing)
    {
        List<FrequencyMapRequest> values = validatedMap(rows, 4095, "DMR LCN");
        List<TimeslotFrequency> mappings = new ArrayList<>(values.size());
        Map<Integer,Long> priorUplinks = new HashMap<>();

        if(existing != null)
        {
            existing.getTimeslotMap().forEach(mapping ->
                priorUplinks.put(mapping.getNumber(), mapping.getUplinkFrequency()));
        }

        for(FrequencyMapRequest value: values)
        {
            TimeslotFrequency mapping = new TimeslotFrequency();
            mapping.setNumber(value.number());
            mapping.setDownlinkFrequency(validFrequency(value.downlinkHz(), "DMR downlink frequency"));
            mapping.setUplinkFrequency(value.uplinkHz() == null ? priorUplinks.getOrDefault(value.number(), 0L) :
                optionalFrequency(value.uplinkHz(), "DMR uplink frequency"));
            mappings.add(mapping);
        }

        return mappings;
    }

    private static List<ChannelFrequency> nxdnMap(List<FrequencyMapRequest> rows, DecodeConfigNXDN existing)
    {
        List<FrequencyMapRequest> values = validatedMap(rows, 2048, "NXDN channel number");
        List<ChannelFrequency> mappings = new ArrayList<>(values.size());
        Map<Integer,Long> priorUplinks = new HashMap<>();

        if(existing != null)
        {
            existing.getChannelMap().forEach(mapping -> priorUplinks.put(mapping.getChannel(), mapping.getUplink()));
        }

        for(FrequencyMapRequest value: values)
        {
            long uplink = value.uplinkHz() == null ? priorUplinks.getOrDefault(value.number(), 0L) :
                optionalFrequency(value.uplinkHz(), "NXDN uplink frequency");
            mappings.add(new ChannelFrequency(value.number(),
                validFrequency(value.downlinkHz(), "NXDN downlink frequency"), uplink));
        }

        return mappings;
    }

    private static List<FrequencyMapRequest> validatedMap(List<FrequencyMapRequest> rows, int maximumNumber,
                                                          String label)
    {
        List<FrequencyMapRequest> values = rows != null ? rows : List.of();

        if(values.size() > MAXIMUM_MAP_ROWS)
        {
            throw invalid("A frequency map can contain at most " + MAXIMUM_MAP_ROWS + " rows.");
        }

        Set<Integer> numbers = new LinkedHashSet<>();

        for(FrequencyMapRequest row: values)
        {
            if(row == null || row.number() == null || row.number() < 1 || row.number() > maximumNumber)
            {
                throw invalid(label + " must be from 1 to " + maximumNumber + ".");
            }

            if(!numbers.add(row.number()))
            {
                throw invalid(label + " values must be unique.");
            }
        }

        return values;
    }

    private AuxDecodeConfiguration buildAuxiliaries(List<String> requested, DecoderType decoderType,
                                                     AuxDecodeConfiguration existing)
    {
        List<String> names = requested != null ? requested : List.of();
        AuxDecodeConfiguration result = new AuxDecodeConfiguration();

        if(decoderType != DecoderType.NBFM)
        {
            if(!names.isEmpty())
            {
                throw invalid("Auxiliary decoders are available only for NBFM channels.");
            }

            if(existing != null)
            {
                existing.getAuxDecoders().forEach(result::addAuxDecoder);
            }

            return result;
        }

        LinkedHashSet<DecoderType> values = new LinkedHashSet<>();

        for(String name: names)
        {
            DecoderType type = parseEnum(DecoderType.class, name, "Auxiliary decoder");

            if(!NBFM_AUXILIARIES.contains(type) || !values.add(type))
            {
                throw invalid("An auxiliary decoder is invalid or repeated.");
            }
        }

        values.forEach(result::addAuxDecoder);
        return result;
    }

    private EventLogConfiguration buildLoggers(List<String> requested, DecoderType decoderType,
                                                EventLogConfiguration existing)
    {
        Set<EventLogType> allowed = ALLOWED_LOGGERS.getOrDefault(decoderType, Set.of());
        LinkedHashSet<EventLogType> result = new LinkedHashSet<>();

        if(existing != null)
        {
            existing.getLoggers().stream().filter(type -> !allowed.contains(type)).forEach(result::add);
        }

        for(String name: requested != null ? requested : List.<String>of())
        {
            EventLogType type = parseEnum(EventLogType.class, name, "Logging option");

            if(!allowed.contains(type) || !result.add(type))
            {
                throw invalid("A logging option is invalid or repeated for this decoder.");
            }
        }

        EventLogConfiguration configuration = new EventLogConfiguration();
        result.forEach(configuration::addLogger);
        return configuration;
    }

    private RecordConfiguration buildRecorders(List<String> requested, DecoderType decoderType,
                                                RecordConfiguration existing)
    {
        Set<RecorderType> allowed = ALLOWED_RECORDERS.getOrDefault(decoderType, Set.of());
        LinkedHashSet<RecorderType> result = new LinkedHashSet<>();

        if(existing != null)
        {
            existing.getRecorders().stream().filter(type -> !allowed.contains(type)).forEach(result::add);
        }

        for(String name: requested != null ? requested : List.<String>of())
        {
            RecorderType type = parseEnum(RecorderType.class, name, "Recording option");

            if(!allowed.contains(type) || !result.add(type))
            {
                throw invalid("A recording option is invalid or repeated for this decoder.");
            }
        }

        RecordConfiguration configuration = new RecordConfiguration();
        configuration.setRecorders(new ArrayList<>(result));
        return configuration;
    }

    private static Map<DecoderType,Set<EventLogType>> allowedLoggers()
    {
        Map<DecoderType,Set<EventLogType>> values = new EnumMap<>(DecoderType.class);
        Set<EventLogType> standard = Set.copyOf(EnumSet.of(EventLogType.CALL_EVENT,
            EventLogType.DECODED_MESSAGE));
        Set<EventLogType> traffic = Set.copyOf(EnumSet.of(EventLogType.CALL_EVENT,
            EventLogType.DECODED_MESSAGE, EventLogType.TRAFFIC_CALL_EVENT,
            EventLogType.TRAFFIC_DECODED_MESSAGE));
        values.put(DecoderType.P25_CONVENTIONAL, standard);
        values.put(DecoderType.P25_PHASE1, traffic);
        values.put(DecoderType.P25_PHASE2, standard);
        values.put(DecoderType.DMR, traffic);
        values.put(DecoderType.NXDN, traffic);
        values.put(DecoderType.NBFM, standard);
        return Map.copyOf(values);
    }

    private static Map<DecoderType,Set<RecorderType>> allowedRecorders()
    {
        Map<DecoderType,Set<RecorderType>> values = new EnumMap<>(DecoderType.class);
        Set<RecorderType> conventional = Set.copyOf(EnumSet.of(RecorderType.BASEBAND,
            RecorderType.DEMODULATED_BIT_STREAM, RecorderType.MBE_CALL_SEQUENCE));
        Set<RecorderType> traffic = Set.copyOf(EnumSet.of(RecorderType.BASEBAND,
            RecorderType.DEMODULATED_BIT_STREAM, RecorderType.MBE_CALL_SEQUENCE,
            RecorderType.TRAFFIC_BASEBAND, RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM,
            RecorderType.TRAFFIC_MBE_CALL_SEQUENCE));
        values.put(DecoderType.P25_CONVENTIONAL, conventional);
        values.put(DecoderType.P25_PHASE1, traffic);
        values.put(DecoderType.P25_PHASE2, traffic);
        values.put(DecoderType.DMR, traffic);
        values.put(DecoderType.NXDN, traffic);
        values.put(DecoderType.NBFM, Set.of(RecorderType.BASEBAND));
        return Map.copyOf(values);
    }

    private void applyChannel(Channel target, Channel source)
    {
        target.setSystem(source.getSystem());
        target.setSite(source.getSite());
        target.setName(source.getName());
        target.setRadresGuid(source.getRadresGuid());
        target.setAliasListName(source.getAliasListName());
        target.setAutoStart(source.isAutoStart());
        target.setAutoStartOrder(source.hasAutoStartOrder() ? source.getAutoStartOrder() : null);
        target.setSourceConfiguration(source.getSourceConfiguration());
        target.setDecodeConfiguration(source.getDecodeConfiguration());
        target.setAuxDecodeConfiguration(source.getAuxDecodeConfiguration());
        target.setEventLogConfiguration(source.getEventLogConfiguration());
        target.setRecordConfiguration(source.getRecordConfiguration());
        mBackend.configurationChanged();
    }

    private boolean changesRadioConfiguration(Channel current, Channel replacement)
    {
        return !fingerprint(current.getSourceConfiguration()).equals(fingerprint(replacement.getSourceConfiguration())) ||
            !fingerprint(current.getDecodeConfiguration()).equals(fingerprint(replacement.getDecodeConfiguration())) ||
            !fingerprint(current.getAuxDecodeConfiguration()).equals(fingerprint(replacement.getAuxDecodeConfiguration()));
    }

    private Channel copy(Channel channel)
    {
        try
        {
            Channel copied = CONFIGURATION_MAPPER.readValue(CONFIGURATION_MAPPER.writeValueAsBytes(channel),
                Channel.class);

            if(channel.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency original &&
                copied.getSourceConfiguration() instanceof SourceConfigTunerMultipleFrequency replacement)
            {
                long preferredFrequency = original.getPreferredFrequency();

                if(preferredFrequency > 0)
                {
                    replacement.setPreferredFrequency(preferredFrequency);
                }
            }

            return copied;
        }
        catch(Exception exception)
        {
            throw error(500, "configuration_copy_failed", "The channel configuration could not be copied safely.");
        }
    }

    private DecodeConfiguration copyDecode(DecodeConfiguration configuration)
    {
        try
        {
            return CONFIGURATION_MAPPER.readValue(CONFIGURATION_MAPPER.writeValueAsBytes(configuration),
                DecodeConfiguration.class);
        }
        catch(Exception exception)
        {
            throw error(500, "configuration_copy_failed", "The decoder configuration could not be copied safely.");
        }
    }

    private boolean start(Channel channel)
    {
        if(isProcessing(channel))
        {
            return false;
        }

        try
        {
            mBackend.start(channel);
        }
        catch(Exception exception)
        {
            throw error(409, "start_failed", cleanRuntimeMessage(exception, "Channel could not start."));
        }

        if(!isProcessing(channel))
        {
            throw error(409, "start_failed", "Channel could not start because no compatible tuner capacity was available.");
        }

        return true;
    }

    private boolean stop(Channel channel)
    {
        if(!isProcessing(channel))
        {
            return false;
        }

        try
        {
            mBackend.stop(channel);
        }
        catch(Exception exception)
        {
            throw error(409, "stop_failed", cleanRuntimeMessage(exception, "Channel could not stop."));
        }

        if(isProcessing(channel))
        {
            throw error(409, "stop_failed", "Channel is still running and was not changed.");
        }

        return true;
    }

    private static String cleanRuntimeMessage(Exception exception, String fallback)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 240 ? fallback : message;
    }

    private ChannelWriteRequest requireWrite(ChannelWriteRequest request, boolean revisionRequired)
    {
        ChannelWriteRequest value = requiredValue(request, "A channel request is required.");
        SourceRequest source = requiredValue(value.source(), "Source settings are required.");
        DecoderRequest decoder = requiredValue(value.decoder(), "Decoder settings are required.");
        requiredValue(source.kind(), "Source kind is required.");
        requiredValue(decoder.decoderType(), "Decoder type is required.");

        if(revisionRequired)
        {
            required(value.revision(), "A channel revision is required.");
        }

        return value;
    }

    private static RevisionRequest requireRevisionRequest(RevisionRequest request)
    {
        if(request == null)
        {
            throw invalid("A channel revision is required.");
        }

        required(request.revision(), "A channel revision is required.");
        return request;
    }

    private void requireRevision(Channel channel, String supplied)
    {
        if(!revision(channel).equals(supplied))
        {
            throw error(409, "channel_changed",
                "The saved channel changed. Reload it before applying these edits.");
        }
    }

    private void requireSupported(Channel channel)
    {
        if(!isSupported(channel))
        {
            throw error(409, "unsupported_channel",
                "This imported channel can be viewed, exported, or deleted, but it cannot be edited or started.");
        }
    }

    private void ensureUniqueGuid(String guid, Channel except)
    {
        long matches = currentChannels().stream().filter(channel -> channel != except && channel.hasRadresGuid() &&
            channel.getRadresGuid().equalsIgnoreCase(guid)).count();

        if(matches > 0)
        {
            throw invalid("Site GUID is already used by another saved channel.");
        }
    }

    private List<Channel> currentChannels()
    {
        return new ArrayList<>(mBackend.channels());
    }

    private synchronized void reconcileRegistry(List<Channel> channels)
    {
        Set<Channel> current = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(channels);
        List<Channel> removed = mIdsByChannel.keySet().stream().filter(channel -> !current.contains(channel)).toList();
        removed.forEach(this::unregister);
        channels.forEach(this::id);
    }

    private synchronized String id(Channel channel)
    {
        String existing = mIdsByChannel.get(channel);

        if(existing != null)
        {
            return existing;
        }

        String id;

        do
        {
            byte[] bytes = new byte[14];
            mSecureRandom.nextBytes(bytes);
            id = ("CHN_" + hex(bytes)).toUpperCase(Locale.ROOT);
        }
        while(mChannelsById.containsKey(id));

        mIdsByChannel.put(channel, id);
        mChannelsById.put(id, channel);
        return id;
    }

    private synchronized void unregister(Channel channel)
    {
        String id = mIdsByChannel.remove(channel);

        if(id != null)
        {
            mChannelsById.remove(id, channel);
        }
    }

    private Channel channel(String id)
    {
        reconcileRegistry(currentChannels());
        Channel channel;

        synchronized(this)
        {
            channel = mChannelsById.get(id);
        }

        if(channel == null || !mBackend.channels().stream().anyMatch(candidate -> candidate == channel))
        {
            throw error(404, "channel_not_found", "The channel was not found. Reload the channel list.");
        }

        return channel;
    }

    private static String requireId(String id)
    {
        if(id == null || !id.matches("CHN_[0-9A-Fa-f]{28}"))
        {
            throw error(404, "channel_not_found", "The channel was not found. Reload the channel list.");
        }

        return id.toUpperCase(Locale.ROOT);
    }

    private String revision(Channel channel)
    {
        return mBackend.withChannelConfigurationLock(channel, () -> revisionLocked(channel));
    }

    private static String revisionLocked(Channel channel)
    {
        return "REV_" + fingerprint(channel).substring(0, 32).toUpperCase(Locale.ROOT);
    }

    private String queueRevision(List<Channel> channels)
    {
        reconcileRegistry(channels);
        List<Channel> ordered = orderedAutoStart(channels);
        StringBuilder value = new StringBuilder(mGeneration);

        for(Channel channel: ordered)
        {
            value.append('|').append(id(channel)).append(':').append(autoStartOrder(channel));
        }

        return "QUE_" + digest(value.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 32)
            .toUpperCase(Locale.ROOT);
    }

    private static String fingerprint(Object value)
    {
        try
        {
            return digest(CONFIGURATION_MAPPER.writeValueAsBytes(value));
        }
        catch(Exception exception)
        {
            throw error(500, "revision_failed", "The channel revision could not be calculated.");
        }
    }

    private static String digest(byte[] bytes)
    {
        try
        {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes)
    {
        StringBuilder value = new StringBuilder(bytes.length * 2);

        for(byte item: bytes)
        {
            value.append(Character.forDigit((item >>> 4) & 0xF, 16));
            value.append(Character.forDigit(item & 0xF, 16));
        }

        return value.toString();
    }

    private static String hex(int value, int width)
    {
        return String.format(Locale.ROOT, "%0" + width + "X", value);
    }

    private static List<Channel> orderedAutoStart(List<Channel> channels)
    {
        Map<Channel,Integer> positions = new IdentityHashMap<>();

        for(int index = 0; index < channels.size(); index++)
        {
            positions.put(channels.get(index), index);
        }

        return channels.stream().filter(channel -> channel.isAutoStart() && autoStartOrder(channel) != null)
            .sorted(Comparator.comparingInt((Channel channel) -> autoStartOrder(channel))
                .thenComparingInt(positions::get)).toList();
    }

    private static List<Channel> normalizeAutoStart(List<Channel> channels)
    {
        List<Channel> ordered = new ArrayList<>(orderedAutoStart(channels));

        for(int index = 0; index < ordered.size(); index++)
        {
            Channel channel = ordered.get(index);
            channel.setAutoStart(true);
            channel.setAutoStartOrder(index + 1);
        }

        for(Channel channel: channels)
        {
            if(!ordered.contains(channel))
            {
                channel.setAutoStart(false);
                channel.setAutoStartOrder(null);
            }
        }

        return ordered;
    }

    private static void swapAutoStart(List<Channel> ordered, int from, int to)
    {
        validateAutoStartMove(ordered, from, to);

        Channel first = ordered.get(from);
        Channel second = ordered.get(to);
        Integer firstOrder = first.getAutoStartOrder();
        first.setAutoStartOrder(second.getAutoStartOrder());
        second.setAutoStartOrder(firstOrder);
    }

    private static void validateAutoStartMove(List<Channel> ordered, int from, int to)
    {
        if(from < 0 || to < 0 || from >= ordered.size() || to >= ordered.size())
        {
            throw invalid("That channel cannot move farther in the automatic-start order.");
        }
    }

    private static Map<Channel,AutoStartState> captureAutoStart(List<Channel> channels)
    {
        Map<Channel,AutoStartState> state = new IdentityHashMap<>();
        channels.forEach(channel -> state.put(channel,
            new AutoStartState(channel.isAutoStart(), channel.hasAutoStartOrder() ? channel.getAutoStartOrder() : null)));
        return state;
    }

    private static void restoreAutoStart(Map<Channel,AutoStartState> state)
    {
        state.forEach((channel, value) ->
        {
            channel.setAutoStart(value.enabled());
            channel.setAutoStartOrder(value.order());
        });
    }

    private List<Map<String,Object>> autoStartOrders(List<Channel> channels)
    {
        reconcileRegistry(channels);
        List<Map<String,Object>> values = new ArrayList<>();

        for(Channel channel: channels)
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("id", id(channel));
            value.put("revision", revision(channel));
            value.put("autoStartOrder", autoStartOrder(channel));
            values.add(value);
        }

        return values;
    }

    private static String autoStartMessage(Channel channel, AutoStartAction action)
    {
        return switch(action)
        {
            case ENABLE -> channel.getName() + " joined automatic start at position 1.";
            case DISABLE -> channel.getName() + " will not start automatically.";
            case EARLIER -> channel.getName() + " moved earlier in automatic start order.";
            case LATER -> channel.getName() + " moved later in automatic start order.";
        };
    }

    private Comparator<Channel> listComparator(String sort, String direction)
    {
        boolean descending = "descending".equals(direction);
        Comparator<Channel> comparator = switch(sort)
        {
            case "system" -> comparingText(Channel::getSystem);
            case "site" -> comparingText(Channel::getSite);
            case "name" -> comparingText(Channel::getName);
            case "protocol" -> Comparator.comparing((Channel channel) -> protocolLabel(channel),
                String.CASE_INSENSITIVE_ORDER);
            case "state" -> Comparator.comparing((Channel channel) -> state(channel),
                String.CASE_INSENSITIVE_ORDER);
            case "frequency" -> Comparator.comparingLong(channel -> highestFrequency(
                frequencies(channel.getSourceConfiguration())));
            case "startOrder" -> defaultOrderComparator();
            default -> throw invalid("Channel sort is invalid.");
        };

        if(descending)
        {
            comparator = comparator.reversed();
        }

        return comparator.thenComparing(comparingText(Channel::getSystem))
            .thenComparing(comparingText(Channel::getSite)).thenComparing(comparingText(Channel::getName))
            .thenComparing(this::id);
    }

    private Comparator<Channel> defaultOrderComparator()
    {
        return Comparator.comparing((Channel channel) -> autoStartOrder(channel) == null)
            .thenComparing(channel -> autoStartOrder(channel) != null ? autoStartOrder(channel) : Integer.MAX_VALUE)
            .thenComparing(Comparator.comparingLong((Channel channel) -> highestFrequency(
                frequencies(channel.getSourceConfiguration()))).reversed());
    }

    private static Comparator<Channel> comparingText(java.util.function.Function<Channel,String> supplier)
    {
        return Comparator.comparing(channel -> text(supplier.apply(channel)), String.CASE_INSENSITIVE_ORDER);
    }

    private static String searchText(Channel channel)
    {
        return (text(channel.getSystem()) + ' ' + text(channel.getSite()) + ' ' + text(channel.getName()) + ' ' +
            protocolLabel(channel)).toLowerCase(Locale.ROOT);
    }

    private static List<Long> frequencies(SourceConfiguration source)
    {
        if(source instanceof SourceConfigTuner tuner)
        {
            return tuner.getFrequency() > 0 ? List.of(tuner.getFrequency()) : List.of();
        }
        else if(source instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            return List.copyOf(multiple.getFrequencies());
        }

        return List.of();
    }

    private static long highestFrequency(List<Long> frequencies)
    {
        return frequencies.stream().filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
    }

    private static Integer autoStartOrder(Channel channel)
    {
        if(!channel.isAutoStart() || !channel.hasAutoStartOrder() || channel.getAutoStartOrder() == null ||
            channel.getAutoStartOrder() < 1)
        {
            return null;
        }

        return channel.getAutoStartOrder();
    }

    private static boolean isMultiFrequencyDecoder(DecoderType decoderType)
    {
        return decoderType == DecoderType.P25_PHASE1 || decoderType == DecoderType.P25_PHASE2 ||
            decoderType == DecoderType.DMR || decoderType == DecoderType.NXDN;
    }

    private static SourceConfiguration defaultSource(DecoderType decoderType)
    {
        if(isMultiFrequencyDecoder(decoderType))
        {
            SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
            source.setFrequencyRotationDelay(defaultRotationDelay(decoderType));
            return source;
        }

        return new SourceConfigTuner();
    }

    private static int defaultRotationDelay(DecoderType decoderType)
    {
        return 500;
    }

    private static boolean isSupported(Channel channel)
    {
        if(channel == null || channel.getDecodeConfiguration() == null || channel.getSourceConfiguration() == null ||
            !SUPPORTED_DECODERS.contains(channel.getDecodeConfiguration().getDecoderType()))
        {
            return false;
        }

        SourceType sourceType = channel.getSourceConfiguration().getSourceType();
        DecoderType decoderType = channel.getDecodeConfiguration().getDecoderType();
        return isMultiFrequencyDecoder(decoderType) ?
            (sourceType == SourceType.TUNER_MULTIPLE_FREQUENCIES || sourceType == SourceType.TUNER) :
            sourceType == SourceType.TUNER;
    }

    private static String unsupportedReason(Channel channel)
    {
        if(channel.getDecodeConfiguration() == null ||
            !SUPPORTED_DECODERS.contains(channel.getDecodeConfiguration().getDecoderType()))
        {
            return "The saved decoder type is not part of the retained web radio configuration.";
        }

        return "The saved source type cannot be edited from the web channel editor.";
    }

    private static String protocol(Channel channel)
    {
        return channel.getDecodeConfiguration() != null && channel.getDecodeConfiguration().getDecoderType() != null ?
            channel.getDecodeConfiguration().getDecoderType().name() : "UNKNOWN";
    }

    private static String protocolLabel(Channel channel)
    {
        return channel.getDecodeConfiguration() != null && channel.getDecodeConfiguration().getDecoderType() != null ?
            protocolLabel(channel.getDecodeConfiguration().getDecoderType()) : "Unsupported";
    }

    private static String protocolLabel(DecoderType decoderType)
    {
        return switch(decoderType)
        {
            case P25_CONVENTIONAL -> "P25 Conventional";
            case P25_PHASE1 -> "P25 Trunked Phase 1";
            case P25_PHASE2 -> "P25 Trunked Phase 2";
            default -> decoderType.getDisplayString();
        };
    }

    private static DecoderType parseSupportedDecoder(String value)
    {
        DecoderType decoderType = parseEnum(DecoderType.class, value, "Decoder type");

        if(!SUPPORTED_DECODERS.contains(decoderType))
        {
            throw invalid("That decoder type is not available for a new channel.");
        }

        return decoderType;
    }

    private String state(Channel channel)
    {
        return isProcessing(channel) ? "Running" : "Stopped";
    }

    private boolean isProcessing(Channel channel)
    {
        return mBackend.isProcessing(channel);
    }

    private static String safeFileName(Channel channel)
    {
        String value = (text(channel.getSystem()) + '-' + text(channel.getSite()) + '-' + text(channel.getName()))
            .replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
        value = value.replaceAll("^-|-$", "");
        return value.isBlank() ? "channel-configuration" : value.substring(0, Math.min(96, value.length()));
    }

    private static String copyName(String name)
    {
        String base = text(name).isBlank() ? "Channel" : text(name);
        String suffix = " Copy";
        return base.substring(0, Math.min(base.length(), MAXIMUM_TEXT_LENGTH - suffix.length())) + suffix;
    }

    private static List<Long> validatedFrequencies(List<Long> requested, boolean multipleAllowed)
    {
        List<Long> values = requested != null ? requested : List.of();

        if(values.isEmpty() || values.size() > (multipleAllowed ? MAXIMUM_FREQUENCIES : 1))
        {
            throw invalid(multipleAllowed ? "Enter between 1 and " + MAXIMUM_FREQUENCIES + " frequencies." :
                "Enter one frequency.");
        }

        LinkedHashSet<Long> unique = new LinkedHashSet<>();

        for(Long value: values)
        {
            long frequency = validFrequency(value, "Channel frequency");

            if(!unique.add(frequency))
            {
                throw invalid("Channel frequencies must be unique.");
            }
        }

        return List.copyOf(unique);
    }

    private static long validFrequency(Long value, String label)
    {
        if(value == null || value < MINIMUM_FREQUENCY_HZ || value > MAXIMUM_FREQUENCY_HZ)
        {
            throw invalid(label + " must be from 1 to 9999.999999 MHz.");
        }

        return value;
    }

    private static long optionalFrequency(Long value, String label)
    {
        return value == null || value == 0 ? 0L : validFrequency(value, label);
    }

    private static int trafficLimit(Integer value)
    {
        return boundedInt(value, 0, 50, "Maximum traffic channels");
    }

    private static int boundedInt(Integer value, int minimum, int maximum, String label)
    {
        if(value == null || value < minimum || value > maximum)
        {
            throw invalid(label + " must be from " + minimum + " to " + maximum + ".");
        }

        return value;
    }

    private static float boundedFloat(Float value, float minimum, float maximum, String label)
    {
        if(value == null || !Float.isFinite(value) || value < minimum || value > maximum)
        {
            throw invalid(label + " must be from " + minimum + " to " + maximum + ".");
        }

        return value;
    }

    private static boolean requiredBoolean(Boolean value, String message)
    {
        if(value == null)
        {
            throw invalid(message);
        }

        return value;
    }

    private static Modulation parseP25Modulation(String value)
    {
        if("LSM".equalsIgnoreCase(optional(value)) || "CQPSK".equalsIgnoreCase(optional(value)))
        {
            return Modulation.CQPSK;
        }
        else if("C4FM".equalsIgnoreCase(optional(value)))
        {
            return Modulation.C4FM;
        }

        throw invalid("P25 modulation must be C4FM or LSM.");
    }

    private static int parseHex(String value, int maximum, String label)
    {
        try
        {
            int parsed = Integer.parseInt(value.strip(), 16);

            if(parsed < 0 || parsed > maximum)
            {
                throw new NumberFormatException();
            }

            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw invalid(label + " is outside its hexadecimal range.");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label)
    {
        String normalized = optional(value);

        if(normalized.isBlank())
        {
            throw invalid(label + " is required.");
        }

        try
        {
            return Enum.valueOf(type, normalized.toUpperCase(Locale.ROOT));
        }
        catch(IllegalArgumentException exception)
        {
            throw invalid(label + " is invalid.");
        }
    }

    private static String boundedOptional(String value, int maximum, String label)
    {
        String text = optional(value).strip();

        if(text.length() > maximum)
        {
            throw invalid(label + " is too long.");
        }

        return text;
    }

    private static String required(String value, String message)
    {
        if(value == null || value.isBlank())
        {
            throw invalid(message);
        }

        return value;
    }

    private static <T> T requiredValue(T value, String message)
    {
        if(value == null)
        {
            throw invalid(message);
        }

        return value;
    }

    private static String optional(String value)
    {
        return value != null ? value : "";
    }

    private static String text(String value)
    {
        return value != null ? value : "";
    }

    private static Map<String,Object> operation(String action, boolean changed, String message)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("action", action);
        value.put("changed", changed);
        value.put("message", message);
        return value;
    }

    private static ChannelConfigurationException persistenceFailure(RuntimeException exception,
                                                                     boolean rollbackComplete,
                                                                     String runtimeState)
    {
        mLog.warn("Unable to persist administrator channel configuration command", exception);
        String state = runtimeState != null && !runtimeState.isBlank() ? " " + runtimeState : "";
        return error(500, rollbackComplete ? "save_failed" : "rollback_incomplete",
            rollbackComplete ? "The channel change could not be saved and was rolled back." + state :
                "The channel change could not be saved, and rollback did not complete cleanly." + state);
    }

    private static boolean rollback(Runnable... actions)
    {
        boolean complete = true;

        for(Runnable action: actions)
        {
            try
            {
                action.run();
            }
            catch(RuntimeException exception)
            {
                complete = false;
                mLog.error("Unable to roll back a channel configuration command cleanly", exception);
            }
        }

        return complete;
    }

    private static ChannelConfigurationException invalid(String message)
    {
        return error(422, "invalid_channel", message);
    }

    private static ChannelConfigurationException unavailable()
    {
        return error(503, "settings_unavailable", "Channel settings are unavailable.");
    }

    private static ChannelConfigurationException error(int status, String code, String message)
    {
        return new ChannelConfigurationException(status, code, message);
    }

    private static ConfigurationThreadDispatcher productionDispatcher()
    {
        if(GraphicsEnvironment.isHeadless())
        {
            return Runnable::run;
        }

        return task ->
        {
            if(Platform.isFxApplicationThread())
            {
                task.run();
            }
            else
            {
                Platform.runLater(task);
            }
        };
    }

    @Override
    public void close()
    {
        long deadline = System.nanoTime() + mShutdownTimeoutNanos;

        if(mClosed.compareAndSet(false, true))
        {
            mExecutor.shutdown();
        }

        try
        {
            if(!awaitExecutor(deadline))
            {
                rejectQueued(mExecutor.shutdownNow());
                mConfigurationTasks.forEach(task -> task.cancel(false));
                deadline = System.nanoTime() + mShutdownTimeoutNanos;
            }

            if(!awaitExecutor(deadline) || !awaitConfigurationTasks(deadline))
            {
                throw new IllegalStateException("Channel settings worker did not stop");
            }
        }
        catch(InterruptedException exception)
        {
            rejectQueued(mExecutor.shutdownNow());
            mConfigurationTasks.forEach(task -> task.cancel(false));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping channel settings", exception);
        }
    }

    private boolean awaitExecutor(long deadline) throws InterruptedException
    {
        long remaining = deadline - System.nanoTime();
        return mExecutor.isTerminated() || remaining > 0L &&
            mExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
    }

    private boolean awaitConfigurationTasks(long deadline) throws InterruptedException
    {
        synchronized(mConfigurationTasks)
        {
            while(!mConfigurationTasks.isEmpty())
            {
                long remaining = deadline - System.nanoTime();

                if(remaining <= 0L)
                {
                    return false;
                }

                TimeUnit.NANOSECONDS.timedWait(mConfigurationTasks, remaining);
            }
        }

        return true;
    }

    private void rejectQueued(List<Runnable> queued)
    {
        for(Runnable runnable: queued)
        {
            if(runnable instanceof SubmittedCommand command)
            {
                command.reject();
            }
        }
    }

    private final class ConfigurationTask<T> extends FutureTask<T>
    {
        private boolean mRunning;

        private ConfigurationTask(Callable<T> callable)
        {
            super(callable);
        }

        @Override
        public void run()
        {
            synchronized(mConfigurationTasks)
            {
                if(isDone())
                {
                    mConfigurationTasks.remove(this);
                    mConfigurationTasks.notifyAll();
                    return;
                }

                mRunning = true;
            }

            try
            {
                super.run();
            }
            finally
            {
                synchronized(mConfigurationTasks)
                {
                    mRunning = false;
                    mConfigurationTasks.remove(this);
                    mConfigurationTasks.notifyAll();
                }
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            synchronized(mConfigurationTasks)
            {
                if(mRunning)
                {
                    return false;
                }
            }

            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        protected void done()
        {
            synchronized(mConfigurationTasks)
            {
                //cancel(false) calls done() even while the callable is still executing.  Keep a running callback
                //tracked until run()'s finally block confirms that its code has actually exited.
                if(!mRunning)
                {
                    mConfigurationTasks.remove(this);
                    mConfigurationTasks.notifyAll();
                }
            }
        }
    }

    private final class SubmittedCommand implements Runnable
    {
        private final Supplier<Map<String,Object>> mCommand;
        private final CompletableFuture<Map<String,Object>> mCompletion;

        private SubmittedCommand(Supplier<Map<String,Object>> command,
                                 CompletableFuture<Map<String,Object>> completion)
        {
            mCommand = command;
            mCompletion = completion;
        }

        @Override
        public void run()
        {
            try
            {
                if(mClosed.get())
                {
                    throw unavailable();
                }

                if(!mBackend.isReady())
                {
                    throw error(503, "settings_initializing",
                        "Channel settings are still loading. Wait a moment and try again.");
                }

                mCompletion.complete(mCommand.get());
            }
            catch(Throwable throwable)
            {
                mCompletion.completeExceptionally(throwable);
            }
        }

        private void reject()
        {
            mCompletion.completeExceptionally(unavailable());
        }
    }

    public record ChannelListRequest(String query, String sort, String direction, int offset, int limit)
    {
        public static ChannelListRequest defaults()
        {
            return new ChannelListRequest("", "startOrder", "ascending", 0, 50);
        }

        ChannelListRequest validated()
        {
            String validatedQuery = query != null ? query.strip() : "";
            String validatedSort = sort != null ? sort : "startOrder";
            String validatedDirection = direction != null ? direction.toLowerCase(Locale.ROOT) : "ascending";

            if(validatedQuery.length() > 80 || !Set.of("system", "site", "name", "frequency", "protocol",
                "state", "startOrder").contains(validatedSort) ||
                !Set.of("ascending", "descending").contains(validatedDirection) || offset < 0 ||
                limit < 1 || limit > MAXIMUM_PAGE_SIZE)
            {
                throw invalid("Channel list options are invalid.");
            }

            return new ChannelListRequest(validatedQuery, validatedSort, validatedDirection, offset, limit);
        }
    }

    public record ChannelWriteRequest(String revision, ApplyPolicy applyPolicy, String system, String site,
                                      String name, String guid, Boolean confirmGuidChange, String aliasList, SourceRequest source,
                                      DecoderRequest decoder, List<String> auxiliaries, List<String> logging,
                                      List<String> recording)
    {
    }

    public record SourceRequest(SourceKind kind, List<Long> frequenciesHz, String preferredTuner,
                                Integer rotationDelayMs)
    {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = P25ConventionalRequest.class, name = "P25_CONVENTIONAL"),
        @JsonSubTypes.Type(value = P25Phase1Request.class, name = "P25_PHASE1"),
        @JsonSubTypes.Type(value = P25Phase2Request.class, name = "P25_PHASE2"),
        @JsonSubTypes.Type(value = DmrRequest.class, name = "DMR"),
        @JsonSubTypes.Type(value = NbfmRequest.class, name = "NBFM"),
        @JsonSubTypes.Type(value = NxdnRequest.class, name = "NXDN")
    })
    public sealed interface DecoderRequest permits P25ConventionalRequest, P25Phase1Request, P25Phase2Request,
        DmrRequest, NbfmRequest, NxdnRequest
    {
        @JsonIgnore
        DecoderType decoderType();
    }

    @JsonTypeName("P25_CONVENTIONAL")
    public record P25ConventionalRequest() implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.P25_CONVENTIONAL;
        }
    }

    @JsonTypeName("P25_PHASE1")
    public record P25Phase1Request(String modulation, Integer maximumTrafficChannels, Boolean ignoreDataCalls,
                                   Boolean learnAnnouncedControlChannels) implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.P25_PHASE1;
        }
    }

    @JsonTypeName("P25_PHASE2")
    public record P25Phase2Request(Integer maximumTrafficChannels, Boolean ignoreDataCalls,
                                   Boolean learnAnnouncedControlChannels, Boolean autoDetectScrambleParameters,
                                   String wacn, String p25System, String nac) implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.P25_PHASE2;
        }
    }

    @JsonTypeName("DMR")
    public record DmrRequest(Integer maximumTrafficChannels, Boolean ignoreDataCalls, Boolean ignoreCrcChecksums,
                             Boolean compressedTalkgroups, List<FrequencyMapRequest> frequencyMap)
        implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.DMR;
        }
    }

    @JsonTypeName("NXDN")
    public record NxdnRequest(String transmissionMode, String encoding, Integer maximumTrafficChannels,
                              Boolean ignoreDataCalls, Boolean ignoreEncryptedCalls,
                              List<FrequencyMapRequest> frequencyMap) implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.NXDN;
        }
    }

    @JsonTypeName("NBFM")
    public record NbfmRequest(String bandwidth, Integer talkgroup, String deemphasis, Float outputGain,
                              Boolean highPassEnabled, Boolean lowPassEnabled, Integer lowPassCutoffHz,
                              Float voiceEnhancePercent, Boolean squelchTrimEnabled, Integer tailTrimMs,
                              Integer headTrimMs, Float bassBoostDb) implements DecoderRequest
    {
        @Override
        public DecoderType decoderType()
        {
            return DecoderType.NBFM;
        }
    }

    public record FrequencyMapRequest(Integer number, Long downlinkHz, Long uplinkHz)
    {
    }

    public record RevisionRequest(String revision)
    {
    }

    public record ChannelDeleteRequest(String revision, boolean confirm)
    {
    }

    public record AutoStartRequest(String revision, String queueRevision, AutoStartAction action)
    {
    }

    public record RuntimeRequest(String revision, RuntimeAction action)
    {
    }

    public record ChannelReference(String id, String revision)
    {
    }

    public record BulkRuntimeRequest(RuntimeAction action, List<ChannelReference> channels)
    {
    }

    public record TimeoutRequest(Integer seconds)
    {
    }

    public enum ApplyPolicy
    {
        APPLY, NEXT_START, STOP, RESTART
    }

    public enum SourceKind
    {
        SINGLE, MULTIPLE
    }

    public enum AutoStartAction
    {
        ENABLE, DISABLE, EARLIER, LATER
    }

    public enum RuntimeAction
    {
        START, STOP
    }

    public static final class ChannelConfigurationException extends RuntimeException
    {
        private final int mStatus;
        private final String mCode;

        private ChannelConfigurationException(int status, String code, String message)
        {
            super(message);
            mStatus = status;
            mCode = code;
        }

        public int status()
        {
            return mStatus;
        }

        public String code()
        {
            return mCode;
        }
    }

    @FunctionalInterface
    interface ConfigurationThreadDispatcher
    {
        void dispatch(Runnable task);
    }

    interface Backend
    {
        default <T> T withChannelConfigurationLock(Channel channel, Supplier<T> command)
        {
            synchronized(channel)
            {
                return command.get();
            }
        }

        boolean isReady();

        List<Channel> channels();

        List<String> aliasLists();

        List<String> preferredTunerNames();

        int autoStartTimeoutSeconds();

        void setAutoStartTimeoutSeconds(int seconds);

        boolean jmbeConfigured();

        boolean isProcessing(Channel channel);

        void add(Channel channel, int index);

        void remove(Channel channel);

        void configurationChanged();

        void flushOrThrow();

        void start(Channel channel) throws Exception;

        void stop(Channel channel) throws Exception;
    }

    private static final class ProductionBackend implements Backend
    {
        private final ConfigurationManager mConfigurationManager;
        private final UserPreferences mUserPreferences;
        private final ChannelProcessingManager mProcessingManager;
        private final TunerManager mTunerManager;

        private ProductionBackend(ConfigurationManager configurationManager, UserPreferences userPreferences)
        {
            mConfigurationManager = Objects.requireNonNull(configurationManager,
                "Configuration manager cannot be null");
            mUserPreferences = Objects.requireNonNull(userPreferences, "User preferences cannot be null");
            mProcessingManager = configurationManager.getChannelProcessingManager();
            mTunerManager = configurationManager.getTunerManager();
        }

        @Override
        public boolean isReady()
        {
            return mConfigurationManager.isInitialized();
        }

        @Override
        public List<Channel> channels()
        {
            return mConfigurationManager.getChannelModel().getChannels();
        }

        @Override
        public List<String> aliasLists()
        {
            return new ArrayList<>(mConfigurationManager.getAliasModel().aliasListNames());
        }

        @Override
        public List<String> preferredTunerNames()
        {
            return mTunerManager != null ? mTunerManager.getPreferredTunerNames() : List.of();
        }

        @Override
        public int autoStartTimeoutSeconds()
        {
            return mUserPreferences.getApplicationPreference().getChannelAutoStartTimeout();
        }

        @Override
        public void setAutoStartTimeoutSeconds(int seconds)
        {
            io.github.dsheirer.preference.application.ApplicationPreference preference =
                mUserPreferences.getApplicationPreference();
            int previous = preference.getChannelAutoStartTimeout();

            try
            {
                preference.setChannelAutoStartTimeout(seconds);
                preference.flush();
            }
            catch(RuntimeException exception)
            {
                try
                {
                    preference.setChannelAutoStartTimeout(previous);
                    preference.flush();
                }
                catch(RuntimeException rollbackFailure)
                {
                    exception.addSuppressed(rollbackFailure);
                }

                throw exception;
            }
        }

        @Override
        public boolean jmbeConfigured()
        {
            return mUserPreferences.getJmbeLibraryPreference().hasJmbeLibraryPath();
        }

        @Override
        public boolean isProcessing(Channel channel)
        {
            io.github.dsheirer.module.ProcessingChain processingChain =
                mProcessingManager.getProcessingChain(channel);
            return processingChain != null && processingChain.isProcessing();
        }

        @Override
        public void add(Channel channel, int index)
        {
            int safeIndex = Math.max(0, Math.min(index,
                mConfigurationManager.getChannelModel().channelList().size()));
            channel.getRadresGuid();
            mConfigurationManager.getChannelModel().channelList().add(safeIndex, channel);

            if(channel.getAliasListName() != null && !channel.getAliasListName().isBlank())
            {
                mConfigurationManager.getAliasModel().addAliasList(channel.getAliasListName());
            }
        }

        @Override
        public void remove(Channel channel)
        {
            mConfigurationManager.getChannelModel().removeChannel(channel);
        }

        @Override
        public void configurationChanged()
        {
            mConfigurationManager.scheduleConfigurationSave();
        }

        @Override
        public void flushOrThrow()
        {
            mConfigurationManager.flushConfigurationOrThrow();
        }

        @Override
        public void start(Channel channel) throws Exception
        {
            mProcessingManager.start(channel);
        }

        @Override
        public void stop(Channel channel) throws Exception
        {
            mProcessingManager.stop(channel);
        }
    }

    private record AutoStartState(boolean enabled, Integer order)
    {
    }
}

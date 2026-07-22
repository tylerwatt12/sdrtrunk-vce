/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.configuration.channel;

import io.github.dsheirer.configuration.channel.ChannelConfigurationService.AutoStartRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.BulkRuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelDeleteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelListRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.ChannelWriteRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RevisionRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.RuntimeRequest;
import io.github.dsheirer.configuration.channel.ChannelConfigurationService.TimeoutRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Narrow asynchronous transport boundary for administrator channel configuration commands.
 */
public interface ChannelConfigurationOperations
{
    CompletableFuture<Map<String,Object>> list(ChannelListRequest request);

    CompletableFuture<Map<String,Object>> template(String protocol);

    CompletableFuture<Map<String,Object>> detail(String channelId);

    CompletableFuture<Map<String,Object>> export(String channelId);

    CompletableFuture<Map<String,Object>> create(ChannelWriteRequest request, BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> update(String channelId, ChannelWriteRequest request,
                                                  BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> cloneChannel(String channelId, RevisionRequest request,
                                                        BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> delete(String channelId, ChannelDeleteRequest request,
                                                  BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> autoStart(String channelId, AutoStartRequest request,
                                                     BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> runtime(String channelId, RuntimeRequest request,
                                                   BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> bulkRuntime(BulkRuntimeRequest request,
                                                       BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> setAutoStartTimeout(TimeoutRequest request,
                                                               BooleanSupplier sessionIsValid);
}

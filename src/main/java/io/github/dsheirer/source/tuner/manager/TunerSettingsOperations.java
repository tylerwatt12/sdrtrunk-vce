/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.manager.TunerSettingsService.EnabledRequest;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService.UpdateRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Narrow transport boundary for serialized tuner-settings work.
 */
public interface TunerSettingsOperations
{
    CompletableFuture<Map<String,Object>> settings(String tunerId);

    CompletableFuture<Map<String,Object>> update(String tunerId, UpdateRequest request,
                                                  BooleanSupplier sessionIsValid);

    CompletableFuture<Map<String,Object>> setEnabled(String tunerId, EnabledRequest request,
                                                      BooleanSupplier sessionIsValid);
}

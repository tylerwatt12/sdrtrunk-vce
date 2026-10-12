/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.diagnostic;

/** Best-effort receiver source view, projected on an HTTP worker rather than a receiving callback. */
public record TunerAllocationRuntimeChannel(String tunerIdentity, TunerAllocationContext context,
                                            long frequencyHz, long bandwidthHz) { }

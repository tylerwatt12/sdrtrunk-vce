/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Requests database maintenance on the single statistics database writer.
 */
public final class StatsDatabaseMaintenanceRequest
{
    private final ReceiverActivityMaintenance.Operation mOperation;
    private final String mConfigurationId;
    private final DeletionTarget mDeletionTarget;
    private final CompletableFuture<ReceiverActivityMaintenance.Result> mResult = new CompletableFuture<>();

    private StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation operation, String configurationId,
                                            DeletionTarget deletionTarget)
    {
        mOperation = Objects.requireNonNull(operation, "Maintenance operation is required");
        mConfigurationId = configurationId;
        mDeletionTarget = deletionTarget;

        if(operation == ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS &&
            (configurationId == null || configurationId.isBlank()))
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }
        else if(operation != ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS && configurationId != null)
        {
            throw new IllegalArgumentException("Channel configuration ID is only valid for CLEAR_CHANNEL_STATS");
        }

        if((operation == ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS) != (deletionTarget != null))
        {
            throw new IllegalArgumentException("Deletion target is required only for DELETE_RETAINED_STATS");
        }
    }

    public static StatsDatabaseMaintenanceRequest forOperation(ReceiverActivityMaintenance.Operation operation)
    {
        return new StatsDatabaseMaintenanceRequest(operation, null, null);
    }

    public static StatsDatabaseMaintenanceRequest clearChannel(String configurationId)
    {
        return new StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS,
            configurationId, null);
    }

    public static StatsDatabaseMaintenanceRequest delete(DeletionTarget target)
    {
        return new StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS,
            null, Objects.requireNonNull(target, "Deletion target is required"));
    }

    public ReceiverActivityMaintenance.Operation operation()
    {
        return mOperation;
    }

    public String configurationId()
    {
        return mConfigurationId;
    }

    public DeletionTarget deletionTarget()
    {
        return mDeletionTarget;
    }

    public sealed interface DeletionTarget permits Frequency, Identity, ConventionalIdentity, LearnedSite,
        SavedSite, Channel, System
    {
    }

    public record Frequency(String configurationId, String expectedSiteKey, long frequencyHz)
        implements DeletionTarget
    {
        public Frequency
        {
            requireText(configurationId, "Channel configuration ID");
            requireText(expectedSiteKey, "Expected site key");
            if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
        }
    }

    public enum IdentityKind
    {
        RADIO, TALKGROUP
    }

    public record Identity(String radioSystemKey, String identityKey, IdentityKind kind) implements DeletionTarget
    {
        public Identity
        {
            requireText(radioSystemKey, "Radio system key");
            requireText(identityKey, "Identity key");
            Objects.requireNonNull(kind, "Identity kind is required");
        }
    }

    /** One row in a DMR conventional saved channel's lifetime identity summary. */
    public record ConventionalIdentity(String configurationId, long frequencyHz, int timeslot, int identityId,
                                       IdentityKind kind) implements DeletionTarget
    {
        public ConventionalIdentity
        {
            requireText(configurationId, "Channel configuration ID");
            if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
            if(timeslot != 1 && timeslot != 2) throw new IllegalArgumentException("Timeslot must be 1 or 2");
            if(identityId <= 0 || identityId > 16_777_215)
                throw new IllegalArgumentException("Identity ID is outside its supported range");
            Objects.requireNonNull(kind, "Identity kind is required");
        }
    }

    public record LearnedSite(String radioSystemKey, int rfss, int site, boolean includeChannelHistory)
        implements DeletionTarget
    {
        public LearnedSite
        {
            requireText(radioSystemKey, "Radio system key");
            if(rfss < 0 || rfss > 255 || site < 0 || site > 255)
                throw new IllegalArgumentException("RFSS and site must be between 0 and 255");
        }
    }

    public record SavedSite(String configurationId, String expectedSiteKey, boolean includeChannelHistory)
        implements DeletionTarget
    {
        public SavedSite
        {
            requireText(configurationId, "Channel configuration ID");
            requireText(expectedSiteKey, "Expected site key");
        }
    }

    public record Channel(String configurationId) implements DeletionTarget
    {
        public Channel
        {
            requireText(configurationId, "Channel configuration ID");
        }
    }

    public record System(String radioSystemKey, boolean includeChannelHistory) implements DeletionTarget
    {
        public System
        {
            requireText(radioSystemKey, "Radio system key");
        }
    }

    private static void requireText(String value, String name)
    {
        if(value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    public CompletableFuture<ReceiverActivityMaintenance.Result> result()
    {
        return mResult;
    }
}

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

package io.github.dsheirer.radioresolve.activitylog;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.util.List;

/**
 * Immutable records passed from decoder/UI threads to the SQLite writer.
 */
final class P25ActivityLogRecords
{
    private P25ActivityLogRecords()
    {
    }

    enum ContextKind
    {
        TRUNKED_SITE,
        CONVENTIONAL_P25,
        CONVENTIONAL_ANALOG
    }

    enum Action
    {
        ACKNOWLEDGE,
        ACTIVE,
        BUSY,
        CALL,
        CHECK,
        CHECK_ACK,
        CONTINUE,
        DATA,
        DENIAL,
        EMERGENCY,
        GPS,
        GRANT,
        JOIN,
        LOGOUT,
        PAGE,
        PATCH,
        PATCH_CANCEL,
        PATCH_CREATE,
        QUEUED,
        REGISTER,
        REQUEST,
        STATUS,
        UNKNOWN
    }

    record ActivityEvent(long observedAtEpochMilliseconds, String contextKey, String guid, ContextKind contextKind,
                         String protocol, Action action, String eventType, String sourceRadioId, String targetId,
                         String targetKind, Long frequencyHertz, String lcn, Integer timeslot, boolean encrypted,
                         Integer encryptionAlgorithmId, Integer encryptionKeyId, Integer wacn, Integer systemId,
                         Integer nac, Integer rfss, Integer site, String channelName, String decoder,
                         String talkerAlias, String dedupeKey)
        implements P25ActivityLogRecord
    {
    }

    record SiteSnapshot(long observedAtEpochMilliseconds, String guid, ContextKind contextKind, String snapshotHash,
                        String protocol, String channelName, String aliasListName, String decoder,
                        Integer wacn, Integer systemId, Integer nac, Integer rfss, Integer site,
                        Long primaryFrequencyHertz, Long currentControlHertz,
                        List<P25NetworkConfigurationSnapshot.Channel> channels,
                        List<P25NetworkConfigurationSnapshot.NeighborSite> neighborSites,
                        List<P25NetworkConfigurationSnapshot.FrequencyBand> frequencyBands,
                        List<P25NetworkConfigurationSnapshot.PatchGroup> patchGroups,
                        List<P25NetworkConfigurationSnapshot.TalkerAlias> talkerAliases)
        implements P25ActivityLogRecord
    {
    }
}

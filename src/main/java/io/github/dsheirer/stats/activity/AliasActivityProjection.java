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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prospective Alias Activity projection.  It runs only in the statistics database writer transaction and credits
 * each newly accepted observation to the alias that wins in the currently assigned, protocol-compatible Alias List.
 * Existing counters are never reprojected when a receiver channel is assigned to another list.
 */
final class AliasActivityProjection
{
    private static final int TALKGROUP = 1;
    private static final int RADIO = 2;
    private static final Set<ReceiverActivityRecords.Action> SIGNALING_ACTIONS = Set.of(
        ReceiverActivityRecords.Action.ACKNOWLEDGE, ReceiverActivityRecords.Action.ACTIVE,
        ReceiverActivityRecords.Action.BUSY, ReceiverActivityRecords.Action.CHECK,
        ReceiverActivityRecords.Action.CHECK_ACK, ReceiverActivityRecords.Action.DATA,
        ReceiverActivityRecords.Action.DENIAL, ReceiverActivityRecords.Action.EMERGENCY,
        ReceiverActivityRecords.Action.GPS, ReceiverActivityRecords.Action.GRANT,
        ReceiverActivityRecords.Action.JOIN, ReceiverActivityRecords.Action.LOGOUT,
        ReceiverActivityRecords.Action.PAGE, ReceiverActivityRecords.Action.PATCH,
        ReceiverActivityRecords.Action.PATCH_CANCEL, ReceiverActivityRecords.Action.PATCH_CREATE,
        ReceiverActivityRecords.Action.QUEUED, ReceiverActivityRecords.Action.REGISTER,
        ReceiverActivityRecords.Action.REQUEST, ReceiverActivityRecords.Action.STATUS);
    private static final Set<ReceiverActivityRecords.Action> OTHER_SIGNALING_ACTIONS = Set.of(
        ReceiverActivityRecords.Action.ACKNOWLEDGE, ReceiverActivityRecords.Action.ACTIVE,
        ReceiverActivityRecords.Action.BUSY, ReceiverActivityRecords.Action.CHECK,
        ReceiverActivityRecords.Action.CHECK_ACK,
        ReceiverActivityRecords.Action.GPS, ReceiverActivityRecords.Action.PAGE,
        ReceiverActivityRecords.Action.PATCH, ReceiverActivityRecords.Action.PATCH_CANCEL,
        ReceiverActivityRecords.Action.PATCH_CREATE, ReceiverActivityRecords.Action.QUEUED,
        ReceiverActivityRecords.Action.REQUEST, ReceiverActivityRecords.Action.STATUS,
        ReceiverActivityRecords.Action.CONTINUE,
        ReceiverActivityRecords.Action.UNKNOWN);

    private AliasActivityProjection()
    {
    }

    static void recordActivity(Connection connection, ReceiverActivityRecords.ActivityEvent activity)
        throws SQLException
    {
        if(activity == null || activity.action() == null)
        {
            return;
        }

        int protocol = protocolCode(activity.protocol());
        Resolver resolver = new Resolver(connection, protocol);
        Map<Long,Delta> deltas = new LinkedHashMap<>();

        if(activity.receiverKind() == ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE)
        {
            Delta signal = Delta.signal(activity.action(), protocol == 1);
            int targetKind = "RADIO".equals(activity.targetKind()) ? RADIO : TALKGROUP;
            addResolved(deltas, resolver.resolve(activity.configurationId(), targetKind,
                positive(activity.targetId())), signal);

            if("PATCH_GROUP".equals(activity.targetKind()))
            {
                for(Integer member: activity.patchMemberTalkgroupIds())
                {
                    addResolved(deltas, resolver.resolve(activity.configurationId(), TALKGROUP, positive(member)),
                        signal);
                }
            }
            if(!sameCanonicalRadioIdentity(activity, protocol))
            {
                addResolved(deltas, resolver.resolve(activity.configurationId(), RADIO,
                    positive(activity.sourceRadioId())), signal);
            }
        }
        else if(activity.countedCall())
        {
            Delta call = Delta.call(activity.encrypted(), false, false);
            int targetKind = "RADIO".equals(activity.targetKind()) ? RADIO : TALKGROUP;
            addResolved(deltas, resolver.resolve(activity.configurationId(), targetKind,
                positive(activity.targetId())), call);
            if("PATCH_GROUP".equals(activity.targetKind()))
            {
                for(Integer member: activity.patchMemberTalkgroupIds())
                {
                    addResolved(deltas, resolver.resolve(activity.configurationId(), TALKGROUP, positive(member)),
                        call);
                }
            }
            if(!sameCanonicalRadioIdentity(activity, protocol))
            {
                addResolved(deltas, resolver.resolve(activity.configurationId(), RADIO,
                    positive(activity.sourceRadioId())), call);
            }
        }

        apply(connection, deltas, activity.observedAtEpochMilliseconds(), protocol);
    }

    static void recordResolvedLogicalCall(Connection connection, ReceiverActivityRecords.ResolvedLogicalCall call)
        throws SQLException
    {
        int protocol = protocolCode(call.protocol());
        projectLogicalCall(connection, call, Delta.call(call.encrypted(), true, protocol == 1));
    }

    static void recordLogicalCallOutput(Connection connection, ReceiverActivityRecords.LogicalCallOutput output)
        throws SQLException
    {
        int protocol = protocolCode(output.call().protocol());
        Delta delta = output.output() == ReceiverActivityRecords.CallOutput.RECORDED ?
            Delta.recorded(true, protocol == 1) : Delta.streamed(true, protocol == 1);
        projectLogicalCall(connection, output.call(), delta);
    }

    static void recordConventionalCallOutput(Connection connection,
                                               ReceiverActivityRecords.ConventionalCallOutput output)
        throws SQLException
    {
        int protocol = protocolCode(output.protocol());
        Resolver resolver = new Resolver(connection, protocol);
        Map<Long,Delta> deltas = new LinkedHashMap<>();
        Delta delta = output.output() == ReceiverActivityRecords.CallOutput.RECORDED ?
            Delta.recorded(false, false) : Delta.streamed(false, false);
        if(!sameConventionalRadioIdentity(output))
        {
            addResolved(deltas, resolver.resolve(output.configurationId(), RADIO, output.sourceRadioId()), delta);
        }
        int targetKind = "RADIO".equals(output.targetKind()) ? RADIO : TALKGROUP;
        addResolved(deltas, resolver.resolve(output.configurationId(), targetKind,
            positive(output.destinationId())), delta);
        if("PATCH_GROUP".equals(output.targetKind()))
        {
            for(Integer member: output.patchMemberTalkgroupIds())
            {
                addResolved(deltas, resolver.resolve(output.configurationId(), TALKGROUP, positive(member)), delta);
            }
        }
        apply(connection, deltas, output.callStartEpochMilliseconds(), protocol);
    }

    private static boolean sameConventionalRadioIdentity(ReceiverActivityRecords.ConventionalCallOutput output)
    {
        return "RADIO".equals(output.targetKind()) && output.sourceRadioId() != null &&
            output.sourceRadioId() > 0 && output.destinationId() > 0 &&
            output.sourceRadioId() == output.destinationId();
    }

    static void recordDmrConventionalCall(Connection connection, ReceiverActivityRecords.DmrConventionalCall call)
        throws SQLException
    {
        int protocol = 3;
        Resolver resolver = new Resolver(connection, protocol);
        Map<Long,Delta> deltas = new LinkedHashMap<>();
        Delta delta = Delta.call(call.encrypted(), false, false);
        if(call.targetKind() != ReceiverActivityRecords.DmrTargetKind.PRIVATE || call.sourceRadioId() == null ||
            !call.sourceRadioId().equals(call.targetRadioId()))
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), RADIO, call.sourceRadioId()), delta);
        }
        if(call.targetKind() == ReceiverActivityRecords.DmrTargetKind.GROUP)
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), TALKGROUP, call.talkgroupId()), delta);
        }
        else if(call.targetKind() == ReceiverActivityRecords.DmrTargetKind.PRIVATE)
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), RADIO, call.targetRadioId()), delta);
        }
        apply(connection, deltas, call.callStartEpochMilliseconds(), protocol);
    }

    static void recordNxdnConventionalCall(Connection connection, ReceiverActivityRecords.NxdnConventionalCall call)
        throws SQLException
    {
        int protocol = 4;
        Resolver resolver = new Resolver(connection, protocol);
        Map<Long,Delta> deltas = new LinkedHashMap<>();
        Delta delta = Delta.call(call.encrypted(), false, false);
        if(call.targetKind() != ReceiverActivityRecords.NxdnTargetKind.PRIVATE || call.sourceRadioId() == null ||
            !call.sourceRadioId().equals(call.targetRadioId()))
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), RADIO, call.sourceRadioId()), delta);
        }
        if(call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.GROUP)
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), TALKGROUP, call.talkgroupId()), delta);
        }
        else if(call.targetKind() == ReceiverActivityRecords.NxdnTargetKind.PRIVATE)
        {
            addResolved(deltas, resolver.resolve(call.configurationId(), RADIO, call.targetRadioId()), delta);
        }
        apply(connection, deltas, call.callStartEpochMilliseconds(), protocol);
    }

    static void recordTrunkedAttribution(Connection connection,
                                          ReceiverActivityRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        int protocol = protocolCode(attribution.protocol());
        Resolver resolver = new Resolver(connection, protocol);
        Map<Long,Delta> deltas = new LinkedHashMap<>();
        Delta touch = Delta.touch(true, protocol == 1);
        if(attribution.sourceBecameKnown())
        {
            addResolved(deltas, resolver.resolve(attribution.configurationId(), RADIO,
                attribution.sourceRadioId()), touch);
        }
        if(attribution.destinationBecameKnown())
        {
            int kind = "RADIO".equals(attribution.destinationKind()) ? RADIO : TALKGROUP;
            addResolved(deltas, resolver.resolve(attribution.configurationId(), kind,
                positive(attribution.destinationId())), touch);
            if("PATCH_GROUP".equals(attribution.destinationKind()))
            {
                for(Integer member: attribution.patchMemberTalkgroupIds())
                {
                    addResolved(deltas, resolver.resolve(attribution.configurationId(), TALKGROUP,
                        positive(member)), touch);
                }
            }
        }
        apply(connection, deltas, attribution.callStartEpochMilliseconds(), protocol);
    }

    private static void projectLogicalCall(Connection connection, ReceiverActivityRecords.ResolvedLogicalCall call,
                                           Delta delta) throws SQLException
    {
        Resolver resolver = new Resolver(connection, protocolCode(call.protocol()));
        Map<Long,Delta> deltas = new LinkedHashMap<>();
        int protocol = protocolCode(call.protocol());
        List<ReceiverActivityRecords.P25SiteCallObservation> compatibleSiteObservations =
            new ArrayList<>();

        if(protocol == 1 && call.wacn() != null && call.systemId() != null)
        {
            for(ReceiverActivityRecords.P25SiteCallObservation observation: call.p25SiteObservations())
            {
                if(observation.site() != null && observation.site().wacn() == call.wacn() &&
                    observation.site().system() == call.systemId() &&
                    resolver.acceptsP25TrunkedObservation(observation.configurationId()))
                {
                    compatibleSiteObservations.add(observation);
                }
            }
        }

        if(protocol == 1 && !compatibleSiteObservations.isEmpty())
        {
            List<LocalIdentity> sources = new ArrayList<>();
            List<LocalIdentity> targets = new ArrayList<>();
            Map<String,List<LocalIdentity>> patchMembers = new LinkedHashMap<>();
            for(ReceiverActivityRecords.P25SiteCallObservation observation: compatibleSiteObservations)
            {
                if(positive(observation.sourceObservedLocalId()) != null)
                {
                    sources.add(new LocalIdentity(observation.configurationId(),
                        observation.sourceObservedLocalId()));
                }
                if(positive(observation.targetObservedLocalId()) != null)
                {
                    targets.add(new LocalIdentity(observation.configurationId(),
                        observation.targetObservedLocalId()));
                }
                Set<Integer> fullyQualifiedLocals = new LinkedHashSet<>();
                for(ReceiverActivityRecords.P25PatchMemberIdentity member:
                    observation.p25PatchMemberIdentities())
                {
                    if(member.targetIdentity().isStableFullyQualified() &&
                        positive(member.localTalkgroupId()) != null)
                    {
                        fullyQualifiedLocals.add(member.localTalkgroupId());
                    }
                }
                for(Integer member: observation.patchMemberTalkgroupIds())
                {
                    if(positive(member) != null && !fullyQualifiedLocals.contains(member))
                    {
                        patchMembers.computeIfAbsent("local:" + member, ignored -> new ArrayList<>())
                            .add(new LocalIdentity(observation.configurationId(), member));
                    }
                }
                for(ReceiverActivityRecords.P25PatchMemberIdentity member:
                    observation.p25PatchMemberIdentities())
                {
                    ReceiverActivityRecords.P25Identity identity = member.targetIdentity();
                    String key = identity.isStableFullyQualified() ?
                        "fq:" + identity.homeWacn() + ':' + identity.homeSystemId() + ':' +
                            identity.homeIdentityId() : "local:" + member.localTalkgroupId();
                    if(positive(member.localTalkgroupId()) != null)
                    {
                        patchMembers.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new LocalIdentity(observation.configurationId(), member.localTalkgroupId()));
                    }
                }
            }

            Long sourceAlias = resolver.consensus(sources, RADIO);
            Long targetAlias = resolver.consensus(targets,
                "RADIO".equals(call.destinationKind()) ? RADIO : TALKGROUP);
            addResolved(deltas, targetAlias, delta);
            if(!sameCanonicalRadioIdentity(call, protocol))
            {
                addResolved(deltas, sourceAlias, delta);
            }
            for(List<LocalIdentity> members: patchMembers.values())
            {
                addResolved(deltas, resolver.consensus(members, TALKGROUP), delta);
            }
        }
        else if(protocol == 1)
        {
            int targetKind = "RADIO".equals(call.destinationKind()) ? RADIO : TALKGROUP;
            addResolved(deltas, resolver.resolveSystemConsensus(call.radioSystemKey(), targetKind,
                positive(call.destinationId())), delta);
            if(!sameCanonicalRadioIdentity(call, protocol))
            {
                addResolved(deltas, resolver.resolveSystemConsensus(call.radioSystemKey(), RADIO,
                    call.sourceRadioId()), delta);
            }
            if("PATCH_GROUP".equals(call.destinationKind()))
            {
                for(Integer member: call.patchMemberTalkgroupIds())
                {
                    addResolved(deltas, resolver.resolveSystemConsensus(call.radioSystemKey(), TALKGROUP,
                        positive(member)), delta);
                }
            }
        }
        else
        {
            int targetKind = "RADIO".equals(call.destinationKind()) ? RADIO : TALKGROUP;
            addResolved(deltas, resolver.resolve(call.configurationId(), targetKind,
                positive(call.destinationId())), delta);
            if(!sameCanonicalRadioIdentity(call, protocol))
            {
                addResolved(deltas, resolver.resolve(call.configurationId(), RADIO, call.sourceRadioId()), delta);
            }
            if("PATCH_GROUP".equals(call.destinationKind()))
            {
                for(Integer member: call.patchMemberTalkgroupIds())
                {
                    addResolved(deltas, resolver.resolve(call.configurationId(), TALKGROUP, positive(member)), delta);
                }
            }
        }

        apply(connection, deltas, call.callStartEpochMilliseconds(), protocol);
    }

    private static boolean sameCanonicalRadioIdentity(ReceiverActivityRecords.ResolvedLogicalCall call,
                                                       int protocol)
    {
        if(!"RADIO".equals(call.destinationKind()) || call.sourceRadioId() == null)
        {
            return false;
        }
        if(protocol != 1)
        {
            return call.sourceRadioId() == call.destinationId();
        }

        CanonicalP25Identity source = canonicalP25Identity(call.sourceRadioId(), call.p25SourceIdentity(),
            call.wacn(), call.systemId());
        CanonicalP25Identity destination = canonicalP25Identity(call.destinationId(), call.p25TargetIdentity(),
            call.wacn(), call.systemId());
        return source != null && source.equals(destination);
    }

    private static boolean sameCanonicalRadioIdentity(ReceiverActivityRecords.ActivityEvent activity,
                                                       int protocol)
    {
        if(!"RADIO".equals(activity.targetKind()))
        {
            return false;
        }

        Integer sourceId = positive(activity.sourceRadioId());
        Integer targetId = positive(activity.targetId());
        if(sourceId == null || targetId == null)
        {
            return false;
        }
        if(protocol != 1)
        {
            return sourceId.equals(targetId);
        }

        CanonicalP25Identity source = canonicalP25Identity(sourceId, activity.p25SourceIdentity(),
            activity.wacn(), activity.systemId());
        CanonicalP25Identity destination = canonicalP25Identity(targetId, activity.p25TargetIdentity(),
            activity.wacn(), activity.systemId());
        return source != null && source.equals(destination);
    }

    private static CanonicalP25Identity canonicalP25Identity(Integer localId,
                                                              ReceiverActivityRecords.P25Identity identity,
                                                              Integer systemWacn, Integer systemId)
    {
        ReceiverActivityRecords.P25Identity evidence = identity != null ? identity :
            ReceiverActivityRecords.P25Identity.UNKNOWN;
        if(evidence.isStableFullyQualified())
        {
            return evidence.identityKindCode() == RADIO ? new CanonicalP25Identity(evidence.homeWacn(),
                evidence.homeSystemId(), evidence.homeIdentityId()) : null;
        }
        if(evidence.state() != ReceiverActivityRecords.P25IdentityState.ORDINARY || localId == null || localId <= 0)
        {
            return null;
        }
        return new CanonicalP25Identity(systemWacn, systemId, localId);
    }

    private static void addResolved(Map<Long,Delta> deltas, Long aliasId, Delta delta)
    {
        if(aliasId != null)
        {
            deltas.computeIfAbsent(aliasId, ignored -> new Delta()).add(delta);
        }
    }

    private static void apply(Connection connection, Map<Long,Delta> deltas, long observedAt, int protocol)
        throws SQLException
    {
        if(deltas.isEmpty() || observedAt <= 0 || protocol <= 0)
        {
            return;
        }

        long updatedAt = Math.max(1L, System.currentTimeMillis());
        LinkedHashSet<String> applicableFields = new LinkedHashSet<>();
        deltas.values().forEach(delta -> applicableFields.addAll(delta.applicableFields()));
        StringBuilder updateSql = new StringBuilder("""
            UPDATE alias_activity_summary
            SET metrics_state = 'observed',
                first_evidence_ms = CASE WHEN first_evidence_ms IS NULL THEN ?
                    ELSE min(first_evidence_ms, ?) END,
                last_evidence_ms = CASE WHEN last_evidence_ms IS NULL THEN ?
                    ELSE max(last_evidence_ms, ?) END,
                updated_at_ms = ?
            """);
        for(String field: applicableFields)
        {
            updateSql.append(", ").append(field).append(" = coalesce(")
                .append(field).append(", 0) + ?");
        }
        updateSql.append(" WHERE alias_id = ?");

        try(PreparedStatement ensure = connection.prepareStatement("""
            INSERT INTO alias_activity_summary(
                alias_id, alias_list_id, protocol_code, metrics_state, updated_at_ms
            )
            SELECT id, alias_list_id, ?, 'not_collected', ?
            FROM alias WHERE id = ?
            ON CONFLICT(alias_id) DO NOTHING
            """); PreparedStatement update = connection.prepareStatement(updateSql.toString()))
        {
            for(Map.Entry<Long,Delta> entry: deltas.entrySet())
            {
                long aliasId = entry.getKey();
                ensure.setInt(1, protocol);
                ensure.setLong(2, updatedAt);
                ensure.setLong(3, aliasId);
                ensure.executeUpdate();

                int parameter = 1;
                update.setLong(parameter++, observedAt);
                update.setLong(parameter++, observedAt);
                update.setLong(parameter++, observedAt);
                update.setLong(parameter++, observedAt);
                update.setLong(parameter++, updatedAt);
                Delta delta = entry.getValue();
                for(String field: applicableFields)
                {
                    update.setLong(parameter++, delta.counts.getOrDefault(field, 0L));
                }
                update.setLong(parameter, aliasId);
                update.executeUpdate();
            }
        }
    }

    private static int protocolCode(String protocol)
    {
        return switch(protocol != null ? protocol : "")
        {
            case "APCO25", "APCO25_PHASE2" -> 1;
            case "DMR" -> 3;
            case "NXDN" -> 4;
            default -> 0;
        };
    }

    private static Integer positive(String value)
    {
        try
        {
            return value != null ? positive(Integer.parseInt(value)) : null;
        }
        catch(NumberFormatException exception)
        {
            return null;
        }
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 ? value : null;
    }

    private static final class Resolver
    {
        private final Connection connection;
        private final int protocol;
        private final Map<String,Long> aliasLists = new HashMap<>();
        private final Map<String,Long> systemConsensusAliasLists = new HashMap<>();

        private Resolver(Connection connection, int protocol)
        {
            this.connection = connection;
            this.protocol = protocol;
        }

        private Long consensus(List<LocalIdentity> identities, int kind) throws SQLException
        {
            Long winner = null;
            for(LocalIdentity identity: new LinkedHashSet<>(identities))
            {
                Long candidate = resolve(identity.configurationId(), kind, identity.identifier());
                if(candidate == null || winner != null && !winner.equals(candidate))
                {
                    return null;
                }
                winner = candidate;
            }
            return winner;
        }

        private Long resolve(String configurationId, int kind, Integer identifier) throws SQLException
        {
            if(protocol <= 0 || configurationId == null || identifier == null)
            {
                return null;
            }
            Long aliasListId = aliasLists.get(configurationId);
            if(aliasListId == null && !aliasLists.containsKey(configurationId))
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    SELECT alias_list_id FROM configuration_channel WHERE configuration_id = ?
                    """))
                {
                    statement.setString(1, configurationId);
                    try(ResultSet resultSet = statement.executeQuery())
                    {
                        if(resultSet.next())
                        {
                            long value = resultSet.getLong(1);
                            aliasListId = resultSet.wasNull() ? null : value;
                        }
                    }
                }
                aliasLists.put(configurationId, aliasListId);
            }
            return aliasListId != null ? resolve(aliasListId, kind, identifier) : null;
        }

        private boolean acceptsP25TrunkedObservation(String configurationId) throws SQLException
        {
            if(protocol != 1 || configurationId == null)
            {
                return false;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM configuration_channel configuration
                JOIN receiver_channel channel
                  ON channel.configuration_id = configuration.configuration_id
                WHERE configuration.configuration_id = ?
                  AND configuration.channel_kind = 'TRUNKED'
                  AND configuration.decoder_type LIKE 'P25%'
                  AND configuration.alias_list_id IS NOT NULL
                LIMIT 1
                """))
            {
                statement.setString(1, configurationId);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    return resultSet.next();
                }
            }
        }

        private Long resolveSystemConsensus(String systemKey, int kind, Integer identifier) throws SQLException
        {
            if(protocol != 1 || systemKey == null || identifier == null)
            {
                return null;
            }

            Long aliasListId = systemConsensusAliasLists.get(systemKey);
            if(aliasListId == null && !systemConsensusAliasLists.containsKey(systemKey))
            {
                List<Long> assigned = new ArrayList<>(2);
                try(PreparedStatement statement = connection.prepareStatement("""
                    WITH selected AS (
                        SELECT id, configuration_id FROM radio_system WHERE system_key = ?
                    ), assigned(alias_list_id) AS (
                        SELECT configuration.alias_list_id
                        FROM selected
                        JOIN receiver_channel channel ON channel.radio_system_id = selected.id
                        JOIN configuration_channel configuration
                          ON configuration.configuration_id = channel.configuration_id
                        WHERE configuration.alias_list_id IS NOT NULL
                        UNION
                        SELECT configuration.alias_list_id
                        FROM selected
                        JOIN configuration_channel configuration
                          ON configuration.configuration_id = selected.configuration_id
                        WHERE configuration.alias_list_id IS NOT NULL
                    )
                    SELECT alias_list_id FROM assigned ORDER BY alias_list_id LIMIT 2
                    """))
                {
                    statement.setString(1, systemKey);
                    try(ResultSet resultSet = statement.executeQuery())
                    {
                        while(resultSet.next())
                        {
                            assigned.add(resultSet.getLong(1));
                        }
                    }
                }
                aliasListId = assigned.size() == 1 ? assigned.getFirst() : null;
                systemConsensusAliasLists.put(systemKey, aliasListId);
            }
            return aliasListId != null ? resolve(aliasListId, kind, identifier) : null;
        }

        private Long resolve(long aliasListId, int kind, int identifier) throws SQLException
        {
            try(PreparedStatement exact = connection.prepareStatement(exactResolverSql(protocol, kind)))
            {
                exact.setLong(1, aliasListId);
                exact.setInt(2, identifier);
                try(ResultSet resultSet = exact.executeQuery())
                {
                    if(resultSet.next())
                    {
                        return resultSet.getLong(1);
                    }
                }
            }

            try(PreparedStatement range = connection.prepareStatement(rangeResolverSql(protocol, kind)))
            {
                range.setLong(1, aliasListId);
                range.setInt(2, identifier);
                range.setInt(3, identifier);
                try(ResultSet resultSet = range.executeQuery())
                {
                    return resultSet.next() ? resultSet.getLong(1) : null;
                }
            }
        }
    }

    static String exactResolverSql(int protocol, int kind)
    {
        String matcher = kind == RADIO ? "RADIO_ID" : "TALKGROUP";
        return """
            SELECT id FROM alias
            WHERE alias_list_id = ? AND matcher_type = '%s' AND protocol IN %s AND value = ?
            ORDER BY id DESC LIMIT 1
            """.formatted(matcher, protocolSql(protocol));
    }

    static String rangeResolverSql(int protocol, int kind)
    {
        String matcher = kind == RADIO ? "RADIO_ID_RANGE" : "TALKGROUP_RANGE";
        return """
            SELECT id FROM alias
            WHERE alias_list_id = ? AND matcher_type = '%s' AND protocol IN %s
              AND min_value <= ? AND max_value >= ?
            ORDER BY min_value DESC, max_value DESC, id DESC LIMIT 1
            """.formatted(matcher, protocolSql(protocol));
    }

    private static String protocolSql(int protocol)
    {
        return protocol == 1 ? "('APCO25', 'APCO25_PHASE2')" :
            protocol == 3 ? "('DMR')" : "('NXDN')";
    }

    private static final class Delta
    {
        private static final List<String> BASE_FIELDS = List.of(
            "logical_call_count", "recorded_logical_call_count",
            "stream_submitted_logical_call_count", "encrypted_logical_call_count");
        private static final List<String> TRUNKED_FIELDS = List.of(
            "join_observation_count", "emergency_observation_count", "register_observation_count",
            "logout_observation_count", "denial_observation_count", "data_observation_count",
            "other_signaling_observation_count", "signaling_observation_count");
        private final Map<String,Long> counts = new LinkedHashMap<>();
        private boolean trunked;
        private boolean p25Trunked;

        private static Delta call(boolean encrypted, boolean trunked, boolean p25Trunked)
        {
            Delta delta = touch(trunked, p25Trunked);
            delta.add("logical_call_count", 1);
            if(encrypted)
            {
                delta.add("encrypted_logical_call_count", 1);
            }
            return delta;
        }

        private static Delta recorded(boolean trunked, boolean p25Trunked)
        {
            Delta delta = touch(trunked, p25Trunked);
            delta.add("recorded_logical_call_count", 1);
            return delta;
        }

        private static Delta streamed(boolean trunked, boolean p25Trunked)
        {
            Delta delta = touch(trunked, p25Trunked);
            delta.add("stream_submitted_logical_call_count", 1);
            return delta;
        }

        private static Delta signal(ReceiverActivityRecords.Action action, boolean p25)
        {
            Delta delta = touch(true, p25);
            if(SIGNALING_ACTIONS.contains(action))
            {
                delta.add("signaling_observation_count", 1);
            }
            if(OTHER_SIGNALING_ACTIONS.contains(action))
            {
                delta.add("other_signaling_observation_count", 1);
            }
            switch(action)
            {
                case GRANT ->
                {
                    if(p25)
                    {
                        delta.add("grant_observation_count", 1);
                    }
                }
                case JOIN -> delta.add("join_observation_count", 1);
                case EMERGENCY -> delta.add("emergency_observation_count", 1);
                case REGISTER -> delta.add("register_observation_count", 1);
                case LOGOUT -> delta.add("logout_observation_count", 1);
                case DENIAL -> delta.add("denial_observation_count", 1);
                case DATA -> delta.add("data_observation_count", 1);
                default -> { }
            }
            return delta;
        }

        private static Delta touch(boolean trunked, boolean p25Trunked)
        {
            Delta delta = new Delta();
            delta.trunked = trunked;
            delta.p25Trunked = trunked && p25Trunked;
            return delta;
        }

        private void add(Delta other)
        {
            trunked |= other.trunked;
            p25Trunked |= other.p25Trunked;
            other.counts.forEach(this::add);
        }

        private List<String> applicableFields()
        {
            List<String> fields = new ArrayList<>(BASE_FIELDS);
            if(p25Trunked)
            {
                fields.add("grant_observation_count");
            }
            if(trunked)
            {
                fields.addAll(TRUNKED_FIELDS);
            }
            return fields;
        }

        private void add(String field, long value)
        {
            if(value > 0)
            {
                counts.merge(field, value, Long::sum);
            }
        }
    }

    private record LocalIdentity(String configurationId, int identifier) {}

    private record CanonicalP25Identity(Integer homeWacn, Integer homeSystemId, int identityId) {}
}

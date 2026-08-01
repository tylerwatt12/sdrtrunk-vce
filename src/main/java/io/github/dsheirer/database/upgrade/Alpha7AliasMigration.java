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
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Alpha 7 Alias-v3 to final Alias-v4 phase owned by the bundled Application Migrator transaction. */
final class Alpha7AliasMigration
{
    private static final String NO_ALIAS_LIST = "(No Alias List)";
    private static final int P25_MAXIMUM_TALKGROUP = 0xFFFF;
    private static final Pattern LEGACY_P25_MATCHER = Pattern.compile("[A-Fa-f\\d]{4}|[A-Fa-f\\d]{6}");
    private static final Pattern LEGACY_FLEETSYNC_MATCHER = Pattern.compile("(\\d{3})-(\\d{4})");
    private static final Pattern LEGACY_MDC1200_MATCHER = Pattern.compile("[A-Fa-f\\d]{4}");
    private static final Set<String> RETIRED_PROTOCOLS = Set.of(
        "AM", "LTR", "LTR_NET", "PASSPORT");
    private static final Set<String> V3_TEXT_MATCHER_TYPES = Set.of(
        "DCS", "ESN", "FLEETSYNC", "LEGACY_TALKGROUP", "LOJACK", "LTR_NET_UID", "MDC1200", "MIN",
        "MPT1327", "SITE");
    private static final Set<String> SUPPORTED_MATCHER_TYPES = Set.of(
        "TALKGROUP", "TALKGROUP_RANGE", "P25_FULLY_QUALIFIED_TALKGROUP",
        "RADIO_ID", "RADIO_ID_RANGE", "P25_FULLY_QUALIFIED_RADIO_ID",
        "STATUS", "UNIT_STATUS", "TONES", "DCS", "ESN");
    private static final List<String> V3_ALIAS_TABLES = List.of(
        "alias", "alias_broadcast_channel", "alias_talkgroup", "alias_radio", "alias_status",
        "alias_tone_sequence", "alias_text_identifier", "alias_action");
    private static final List<String> V3_ALIAS_INDEXES = List.of(
        "idx_alias_sort", "idx_alias_list_name", "idx_alias_broadcast_channel_alias",
        "idx_alias_broadcast_channel_name", "idx_alias_talkgroup_alias", "idx_alias_talkgroup_value",
        "idx_alias_talkgroup_range", "idx_alias_radio_alias", "idx_alias_radio_value",
        "idx_alias_radio_range", "idx_alias_status_alias", "idx_alias_status_lookup",
        "idx_alias_tone_sequence_alias", "idx_alias_text_identifier_alias",
        "idx_alias_text_identifier_type", "idx_alias_action_alias");
    private Alpha7AliasMigration()
    {
    }

    static void validateSourceData(Connection connection) throws SQLException
    {
        List<SourceAlias> aliases = loadSourceAliases(connection);
        Map<Long,List<MatcherRow>> matchers = loadMatchers(connection);
        loadRoutes(connection);
        countAliasActions(connection);
        buildPlan(connection, aliases, matchers);
    }

    static Result migrate(Connection connection) throws SQLException
    {
        List<SourceAlias> aliases = loadSourceAliases(connection);
        Map<Long,List<MatcherRow>> matchers = loadMatchers(connection);
        RouteInventory routes = loadRoutes(connection);
        long discardedActions = countAliasActions(connection);
        long discardedLegacyMatcherDetailFields = countDiscardedLegacyMatcherDetailFields(connection);
        long removedRetiredChannels = removeRetiredChannels(connection);
        long targetConfigurationChannels = count(connection, "configuration_channel");
        MigrationPlan plan = buildPlan(connection, aliases, matchers);
        renameV3AliasTables(connection);
        dropV3AliasIndexes(connection);
        SdrTrunkDatabaseSchema.create(connection);
        Map<String,Long> definitionIds = insertDefinitions(connection, plan.definitions());
        MigrationCounts counts = copyAliases(connection, aliases, matchers, routes, plan, definitionIds,
            discardedActions, removedRetiredChannels, targetConfigurationChannels);
        rewireChannelAliasListNames(connection, plan);
        dropRenamedV3Tables(connection);

        if(count(connection, "alias") != counts.targetAliases() ||
            count(connection, "alias_list") != counts.aliasLists() ||
            count(connection, "alias_broadcast_channel") != counts.targetRoutes() ||
            count(connection, "configuration_channel") != counts.targetConfigurationChannels())
        {
            throw new SQLException("Alias row counts changed during v3 to v4 migration");
        }
        return new Result(counts.targetAliases(), counts.aliasLists(), counts.targetRoutes(),
            counts.discardedActions(), counts.removedRetiredChannels(),
            counts.removedNonRecordableFlags(), discardedLegacyMatcherDetailFields,
            counts.collapsedDuplicateMatchers(),
            counts.collapsedDuplicateRoutes(), counts.skippedBroadcastRoutes(),
            counts.skippedMatcherlessAliases(),
            counts.skippedInvalidMatchers(), counts.skippedRetiredMatchers(),
            counts.skippedUntypedMatchers(), counts.skippedIncompatibleMatchers(), counts.skippedAliases(),
            counts.detachedConflictingChannels());
    }

    private static List<SourceAlias> loadSourceAliases(Connection connection) throws SQLException
    {
        List<SourceAlias> aliases = new ArrayList<>();
        Set<Long> ids = new HashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, sort_order, name, description, alias_list_name, group_name, color, icon_name,
                       stream_as_talkgroup, record_enabled, non_recordable, priority
                FROM alias
                ORDER BY sort_order, id
                """))
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                if(id <= 0 || !ids.add(id))
                {
                    throw new SQLException("Alias schema v3 contains an invalid or duplicate alias ID: " + id);
                }
                aliases.add(new SourceAlias(id, resultSet.getInt("sort_order"), resultSet.getString("name"),
                    resultSet.getString("description"), resultSet.getString("alias_list_name"),
                    resultSet.getString("group_name"), resultSet.getInt("color"),
                    resultSet.getString("icon_name"), integer(resultSet, "stream_as_talkgroup"),
                    resultSet.getInt("record_enabled") != 0, resultSet.getInt("non_recordable") != 0,
                    integer(resultSet, "priority")));
            }
        }

        return aliases;
    }

    private static Map<Long,List<MatcherRow>> loadMatchers(Connection connection) throws SQLException
    {
        Map<Long,List<MatcherRow>> rows = new HashMap<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id,
                       fully_qualified, ranged
                FROM alias_talkgroup
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                boolean fullyQualified = resultSet.getInt("fully_qualified") != 0;
                boolean ranged = resultSet.getInt("ranged") != 0;
                String protocol = resultSet.getString("protocol");
                String reason = fullyQualified && ranged ?
                    "Alias schema v3 talkgroup row had both fully-qualified and ranged flags" : null;
                if(reason == null && fullyQualified && !"APCO25".equals(protocol) &&
                    !"APCO25_PHASE2".equals(protocol))
                {
                    reason = "Alias schema v3 fully-qualified talkgroup row used a non-P25 protocol";
                }
                String type = fullyQualified ? "P25_FULLY_QUALIFIED_TALKGROUP" :
                    ranged ? "TALKGROUP_RANGE" : "TALKGROUP";
                add(rows, new MatcherRow(0, resultSet.getLong("id"), resultSet.getLong("alias_id"),
                    resultSet.getInt("sort_order"), type, protocol,
                    integer(resultSet, "value"), integer(resultSet, "min_value"),
                    integer(resultSet, "max_value"), integer(resultSet, "wacn"),
                    integer(resultSet, "system_id"), null, null, null, null, reason));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id,
                       fully_qualified, ranged
                FROM alias_radio
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                boolean fullyQualified = resultSet.getInt("fully_qualified") != 0;
                boolean ranged = resultSet.getInt("ranged") != 0;
                String protocol = resultSet.getString("protocol");
                String reason = fullyQualified && ranged ?
                    "Alias schema v3 radio row had both fully-qualified and ranged flags" : null;
                if(reason == null && fullyQualified && !"APCO25".equals(protocol) &&
                    !"APCO25_PHASE2".equals(protocol))
                {
                    reason = "Alias schema v3 fully-qualified radio row used a non-P25 protocol";
                }
                String type = fullyQualified ? "P25_FULLY_QUALIFIED_RADIO_ID" :
                    ranged ? "RADIO_ID_RANGE" : "RADIO_ID";
                add(rows, new MatcherRow(1, resultSet.getLong("id"), resultSet.getLong("alias_id"),
                    resultSet.getInt("sort_order"), type, protocol,
                    integer(resultSet, "value"), integer(resultSet, "min_value"),
                    integer(resultSet, "max_value"), integer(resultSet, "wacn"),
                    integer(resultSet, "system_id"), null, null, null, null, reason));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_id, sort_order, status_kind, status
                FROM alias_status
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                String kind = resultSet.getString("status_kind");
                if(!"USER".equals(kind) && !"UNIT".equals(kind))
                {
                    throw new SQLException("Unrecognized Alias-v3 status kind: " + kind);
                }
                add(rows, new MatcherRow(2, resultSet.getLong("id"), resultSet.getLong("alias_id"),
                    resultSet.getInt("sort_order"), "UNIT".equals(kind) ? "UNIT_STATUS" : "STATUS", null,
                    null, null, null, null, null, null, null, resultSet.getInt("status"), null, null));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_id, sort_order, tone_sequence
                FROM alias_tone_sequence
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                add(rows, new MatcherRow(3, resultSet.getLong("id"), resultSet.getLong("alias_id"),
                    resultSet.getInt("sort_order"), "TONES", null, null, null, null, null, null, null, null, null,
                    resultSet.getString("tone_sequence"), null));
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_id, sort_order, identifier_type, text_value, text_value_2, numeric_value
                FROM alias_text_identifier
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                String type = resultSet.getString("identifier_type");
                if(!V3_TEXT_MATCHER_TYPES.contains(type))
                {
                    throw new SQLException("Unrecognized Alias-v3 text matcher type: " + type);
                }
                add(rows, new MatcherRow(4, resultSet.getLong("id"), resultSet.getLong("alias_id"),
                    resultSet.getInt("sort_order"), type, null, null, null, null, null, null,
                    resultSet.getString("text_value"), resultSet.getString("text_value_2"),
                    integer(resultSet, "numeric_value"), null, null));
            }
        }

        Comparator<MatcherRow> comparator = Comparator.comparingInt(MatcherRow::sortOrder)
            .thenComparingInt(MatcherRow::sourceRank).thenComparingLong(MatcherRow::sourceRowId);
        rows.replaceAll((ignored, values) -> {
            List<MatcherRow> canonical = new ArrayList<>();
            values.forEach(value -> canonical.addAll(canonicalizeMatchers(value)));
            canonical.sort(comparator);
            return canonical;
        });
        return rows;
    }

    private static long countAliasActions(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM alias_action"))
        {
            if(!resultSet.next())
            {
                throw new SQLException("Unable to count retired Alias-v3 actions");
            }
            return resultSet.getLong(1);
        }
    }

    private static long countDiscardedLegacyMatcherDetailFields(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT coalesce(sum(
                    CASE WHEN text_value_2 IS NOT NULL THEN 1 ELSE 0 END +
                    CASE WHEN numeric_value IS NOT NULL THEN 1 ELSE 0 END
                ), 0)
                FROM alias_text_identifier
                WHERE identifier_type IN ('FLEETSYNC', 'MDC1200', 'LEGACY_TALKGROUP', 'LOJACK')
                """))
        {
            if(!resultSet.next())
            {
                throw new SQLException("Unable to count legacy Alias-v3 matcher detail fields");
            }
            return resultSet.getLong(1);
        }
    }

    private static long removeRetiredChannels(Connection connection) throws SQLException
    {
        long removed;
        try(PreparedStatement count = connection.prepareStatement("""
            SELECT COUNT(*)
            FROM configuration_channel
            WHERE upper(trim(decoder_type)) IN (?, ?, ?, ?)
            """))
        {
            count.setString(1, "AM");
            count.setString(2, "LTR");
            count.setString(3, "LTR_NET");
            count.setString(4, "PASSPORT");
            try(ResultSet resultSet = count.executeQuery())
            {
                if(!resultSet.next())
                {
                    throw new SQLException("Unable to count retired configuration channels");
                }
                removed = resultSet.getLong(1);
            }
        }

        try(Statement statement = connection.createStatement())
        {
            int deleted = statement.executeUpdate("""
                DELETE FROM configuration_channel
                WHERE upper(trim(decoder_type)) IN ('AM', 'LTR', 'LTR_NET', 'PASSPORT')
                """);
            if(deleted != removed)
            {
                throw new SQLException("Retired configuration-channel count changed during deletion");
            }
        }
        return removed;
    }

    private static void add(Map<Long,List<MatcherRow>> rows, MatcherRow row)
    {
        rows.computeIfAbsent(row.sourceAliasId(), ignored -> new ArrayList<>()).add(row);
    }

    /** Applies the same unambiguous legacy matcher upgrades as the stock XML playlist importer. */
    private static List<MatcherRow> canonicalizeMatchers(MatcherRow source)
    {
        MatcherRow upgraded = switch(source.type())
        {
            case "FLEETSYNC" -> upgradeFleetsync(source);
            case "MDC1200" -> upgradeMdc1200(source);
            case "LEGACY_TALKGROUP" -> upgradeLegacyP25(source);
            case "LOJACK" -> matcher(source, "ESN", null, null, null, null, source.textValue());
            default -> canonicalizeMatcher(source);
        };

        if(upgraded == null)
        {
            return List.of(source.withRejectionReason(appendReason(source.rejectionReason(),
                "Legacy Alias-v3 matcher could not be converted safely")));
        }

        return upgradeP25Talkgroup(upgraded);
    }

    private static MatcherRow upgradeFleetsync(MatcherRow source)
    {
        Matcher matcher = source.textValue() != null ? LEGACY_FLEETSYNC_MATCHER.matcher(source.textValue()) : null;
        if(matcher == null || !matcher.matches())
        {
            return null;
        }

        int fleet = Integer.parseInt(matcher.group(1));
        int ident = Integer.parseInt(matcher.group(2));
        return matcher(source, "TALKGROUP", Protocol.FLEETSYNC.name(), (fleet << 12) + ident,
            null, null, null);
    }

    private static MatcherRow upgradeMdc1200(MatcherRow source)
    {
        String value = source.textValue();
        if(value == null || !LEGACY_MDC1200_MATCHER.matcher(value).matches())
        {
            return null;
        }
        return matcher(source, "TALKGROUP", Protocol.MDC1200.name(), Integer.parseInt(value, 16),
            null, null, null);
    }

    private static MatcherRow upgradeLegacyP25(MatcherRow source)
    {
        String value = source.textValue();
        if(value == null || !LEGACY_P25_MATCHER.matcher(value).matches())
        {
            return null;
        }
        return matcher(source, "TALKGROUP", Protocol.APCO25.name(), Integer.parseInt(value, 16),
            null, null, null);
    }

    private static List<MatcherRow> upgradeP25Talkgroup(MatcherRow source)
    {
        if(!Protocol.APCO25.name().equals(source.protocol()))
        {
            return List.of(source);
        }

        if("TALKGROUP".equals(source.type()) && orZero(source.value()) > P25_MAXIMUM_TALKGROUP)
        {
            return List.of(matcher(source, "RADIO_ID", source.protocol(), source.value(), null, null, null));
        }

        if("TALKGROUP_RANGE".equals(source.type()))
        {
            int minimum = orZero(source.minimum());
            int maximum = orZero(source.maximum());
            if(minimum > P25_MAXIMUM_TALKGROUP)
            {
                return List.of(rangeMatcher(source, "RADIO_ID", "RADIO_ID_RANGE", minimum, maximum));
            }
            if(maximum > P25_MAXIMUM_TALKGROUP)
            {
                return List.of(
                    rangeMatcher(source, "TALKGROUP", "TALKGROUP_RANGE", minimum, P25_MAXIMUM_TALKGROUP),
                    rangeMatcher(source, "RADIO_ID", "RADIO_ID_RANGE", P25_MAXIMUM_TALKGROUP + 1, maximum));
            }
        }

        return List.of(source);
    }

    private static MatcherRow rangeMatcher(MatcherRow source, String singleType, String rangeType,
                                           int minimum, int maximum)
    {
        return minimum == maximum ?
            matcher(source, singleType, source.protocol(), minimum, null, null, null) :
            matcher(source, rangeType, source.protocol(), null, minimum, maximum, null);
    }

    private static MatcherRow matcher(MatcherRow source, String type, String protocol, Integer value,
                                      Integer minimum, Integer maximum, String textValue)
    {
        return new MatcherRow(source.sourceRank(), source.sourceRowId(), source.sourceAliasId(), source.sortOrder(),
            type, protocol, value, minimum, maximum, null, null, textValue, null, null, null,
            source.rejectionReason());
    }

    private static MatcherRow canonicalizeMatcher(MatcherRow source)
    {
        String protocol = null;
        Integer value = null;
        Integer minimum = null;
        Integer maximum = null;
        Integer wacn = null;
        Integer p25SystemId = null;
        String textValue = null;
        String textValue2 = null;
        Integer numericValue = null;
        String toneSequence = null;

        switch(source.type())
        {
            case "TALKGROUP", "RADIO_ID" -> {
                protocol = canonicalProtocol(source.protocol());
                value = source.value() != null ? source.value() : 0;
            }
            case "TALKGROUP_RANGE", "RADIO_ID_RANGE" -> {
                protocol = canonicalProtocol(source.protocol());
                minimum = source.minimum() != null ? source.minimum() : 0;
                maximum = source.maximum() != null ? source.maximum() : 0;
            }
            case "P25_FULLY_QUALIFIED_TALKGROUP", "P25_FULLY_QUALIFIED_RADIO_ID" -> {
                protocol = Protocol.APCO25.name();
                value = source.value() != null ? source.value() : 0;
                wacn = source.wacn() != null ? source.wacn() : 0;
                p25SystemId = source.p25SystemId() != null ? source.p25SystemId() : 0;
            }
            case "STATUS", "UNIT_STATUS" ->
                numericValue = source.numericValue() != null ? source.numericValue() : 0;
            case "TONES" -> toneSequence = canonicalToneSequence(source.toneSequence());
            case "DCS" -> {
                DCSCode code = parseEnum(DCSCode.class, source.textValue(), null);
                textValue = code != null ? code.name() : null;
            }
            case "LTR_NET_UID" ->
                numericValue = source.numericValue() != null ? source.numericValue() : 0;
            case "ESN", "MIN", "MPT1327", "SITE" ->
                textValue = source.textValue();
            case "UNASSIGNED" -> {
                //No matcher payload.
            }
            default -> {
                //loadMatchers rejects unrecognized v3 types before canonicalization.
            }
        }

        MatcherRow canonical = new MatcherRow(source.sourceRank(), source.sourceRowId(),
            source.sourceAliasId(), source.sortOrder(), source.type(), protocol, value, minimum, maximum,
            wacn, p25SystemId, textValue, textValue2, numericValue, toneSequence,
            source.rejectionReason());

        if(!sameMatcherPayload(source, canonical) && !onlyFullyQualifiedProtocolNormalization(source, canonical))
        {
            canonical = canonical.withRejectionReason(appendReason(source.rejectionReason(),
                "Noncanonical Alias-v3 matcher payload was canonicalized; original evidence remains in " +
                    "the safety backup"));
        }
        return canonical;
    }

    private static String canonicalProtocol(String value)
    {
        if(value == null)
        {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if(RETIRED_PROTOCOLS.contains(normalized))
        {
            return normalized;
        }
        Protocol protocol = parseEnum(Protocol.class, normalized, null);
        return protocol != null ? protocol.name() : Protocol.UNKNOWN.name();
    }

    private static boolean isRetiredMatcher(MatcherRow matcher)
    {
        return (matcher.protocol() != null && RETIRED_PROTOCOLS.contains(matcher.protocol())) ||
            "LTR_NET_UID".equals(matcher.type()) ||
            "MIN".equals(matcher.type());
    }

    private static String canonicalToneSequence(String value)
    {
        ToneSequence sequence = parseToneSequence(value);
        if(sequence == null || !sequence.hasTones())
        {
            return null;
        }

        StringJoiner joiner = new StringJoiner(",");
        for(Tone tone: sequence.getTones())
        {
            joiner.add(tone.getAmbeTone().name() + ':' + tone.getDuration());
        }
        return joiner.toString();
    }

    private static boolean onlyFullyQualifiedProtocolNormalization(MatcherRow source, MatcherRow canonical)
    {
        boolean fullyQualified = "P25_FULLY_QUALIFIED_TALKGROUP".equals(source.type()) ||
            "P25_FULLY_QUALIFIED_RADIO_ID".equals(source.type());
        if(!fullyQualified || !"APCO25_PHASE2".equals(source.protocol()) ||
            !Protocol.APCO25.name().equals(canonical.protocol()))
        {
            return false;
        }

        MatcherRow normalizedSource = new MatcherRow(source.sourceRank(), source.sourceRowId(),
            source.sourceAliasId(), source.sortOrder(), source.type(), Protocol.APCO25.name(),
            source.value(), source.minimum(), source.maximum(), source.wacn(), source.p25SystemId(),
            source.textValue(), source.textValue2(), source.numericValue(), source.toneSequence(),
            source.rejectionReason());
        return sameMatcherPayload(normalizedSource, canonical);
    }

    private static boolean sameMatcherPayload(MatcherRow first, MatcherRow second)
    {
        return Objects.equals(first.type(), second.type()) &&
            Objects.equals(first.protocol(), second.protocol()) &&
            Objects.equals(first.value(), second.value()) &&
            Objects.equals(first.minimum(), second.minimum()) &&
            Objects.equals(first.maximum(), second.maximum()) &&
            Objects.equals(first.wacn(), second.wacn()) &&
            Objects.equals(first.p25SystemId(), second.p25SystemId()) &&
            Objects.equals(first.textValue(), second.textValue()) &&
            Objects.equals(first.textValue2(), second.textValue2()) &&
            Objects.equals(first.numericValue(), second.numericValue()) &&
            Objects.equals(first.toneSequence(), second.toneSequence());
    }

    private static String appendReason(String existing, String addition)
    {
        if(existing == null || existing.isBlank())
        {
            return addition;
        }
        if(existing.contains(addition))
        {
            return existing;
        }
        return existing + "; " + addition;
    }

    private static List<MatcherRow> uniqueMatchers(List<MatcherRow> matchers)
    {
        List<MatcherRow> unique = new ArrayList<>();
        for(MatcherRow matcher: matchers)
        {
            if(unique.stream().noneMatch(existing -> equivalent(existing, matcher)))
            {
                unique.add(matcher);
            }
        }
        return List.copyOf(unique);
    }

    private static boolean equivalent(MatcherRow first, MatcherRow second)
    {
        return sameMatcherPayload(first, second) &&
            Objects.equals(first.rejectionReason(), second.rejectionReason());
    }

    private static MigrationPlan buildPlan(Connection connection, List<SourceAlias> aliases,
                                           Map<Long,List<MatcherRow>> matchers)
        throws SQLException
    {
        Map<String,LegacyListGroup> groups = new LinkedHashMap<>();
        Map<Long,ChannelFamilyClaim> channelClaims = new LinkedHashMap<>();
        Map<Long,String> channelDefinitionKeys = new LinkedHashMap<>();
        Set<Long> detachedChannelIds = new LinkedHashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, alias_list_name, decoder_type
                FROM configuration_channel
                WHERE alias_list_name IS NOT NULL AND trim(alias_list_name) <> ''
                ORDER BY sort_order, id
                """))
        {
            while(resultSet.next())
            {
                String aliasListName = resultSet.getString("alias_list_name");
                if(isNoAliasList(aliasListName))
                {
                    continue;
                }

                LegacyListGroup group = group(groups, aliasListName);
                AliasListFamily claim = familyForDecoder(resultSet.getString("decoder_type"));
                long channelId = resultSet.getLong("id");

                if(claim == null)
                {
                    detachedChannelIds.add(channelId);
                }
                else
                {
                    group.mClaimedFamilies.add(claim);
                    channelClaims.put(channelId, new ChannelFamilyClaim(normalize(group.mName), claim));
                }
            }
        }

        for(SourceAlias alias: aliases)
        {
            group(groups, alias.aliasListName());
        }

        collectInferredListFamilies(groups, aliases, matchers);

        List<ListDefinitionRow> definitions = new ArrayList<>();
        Map<LegacyListGroup,EnumSet<AliasListFamily>> groupFamilies = new LinkedHashMap<>();
        Set<String> usedNames = new LinkedHashSet<>();

        //Preserve every original name that already belongs to one family before split groups claim generated names.
        for(LegacyListGroup group: groups.values())
        {
            EnumSet<AliasListFamily> families = group.families();
            groupFamilies.put(group, families);
            if(!isNoAliasList(group.mName) && families.size() == 1)
            {
                usedNames.add(normalize(group.mName));
            }
        }

        for(LegacyListGroup group: groups.values())
        {
            EnumSet<AliasListFamily> families = groupFamilies.get(group);
            boolean split = families.size() > 1 || isNoAliasList(group.mName);
            for(AliasListFamily family: families)
            {
                String baseName = isNoAliasList(group.mName) ? "Imported Unassigned" : group.mName;
                String definitionName = split ? uniqueDefinitionName(baseName, family, usedNames) : group.mName;
                DefinitionPlan definition = new DefinitionPlan(
                    normalize(group.mName) + '|' + family.name(), definitionName, family);
                group.mDefinitions.put(family, definition);
                definitions.add(definition.toRow());
            }
        }

        for(Map.Entry<Long,ChannelFamilyClaim> entry: channelClaims.entrySet())
        {
            LegacyListGroup group = groups.get(entry.getValue().groupKey());
            DefinitionPlan definition = group != null ?
                group.mDefinitions.get(entry.getValue().family()) : null;
            if(definition == null)
            {
                throw new SQLException("No migrated alias-list family for supported channel " + entry.getKey());
            }
            channelDefinitionKeys.put(entry.getKey(), definition.mKey);
        }
        return new MigrationPlan(List.copyOf(definitions), Map.copyOf(groups),
            Map.copyOf(channelDefinitionKeys), Set.copyOf(detachedChannelIds));
    }

    private static LegacyListGroup group(Map<String,LegacyListGroup> groups, String name)
    {
        String displayName = name == null || name.isBlank() ? NO_ALIAS_LIST : name.trim();
        return groups.computeIfAbsent(normalize(displayName), ignored -> new LegacyListGroup(displayName));
    }

    private static void collectInferredListFamilies(Map<String,LegacyListGroup> groups,
                                                    List<SourceAlias> aliases,
                                                    Map<Long,List<MatcherRow>> matchers)
    {
        for(SourceAlias alias: aliases)
        {
            LegacyListGroup group = groups.get(normalize(
                isNoAliasList(alias.aliasListName()) ? NO_ALIAS_LIST : alias.aliasListName()));
            if(group == null)
            {
                continue;
            }

            for(MatcherRow matcher: matchers.getOrDefault(alias.id(), List.of()))
            {
                EnumSet<AliasListFamily> matcherFamilies = supportedFamilies(matcher);
                if(matcherFamilies.isEmpty())
                {
                    continue;
                }

                if(matcherFamilies.size() == 1)
                {
                    group.mInferredFamilies.addAll(matcherFamilies);
                }
                else
                {
                    group.mAmbiguousMatcherFamilies.add(EnumSet.copyOf(matcherFamilies));
                }
            }
        }
    }

    private static String uniqueDefinitionName(String baseName, AliasListFamily family, Set<String> usedNames)
    {
        String stem = baseName + " [" + family.name() + ']';
        String candidate = stem;
        if(usedNames.add(normalize(candidate)))
        {
            return candidate;
        }
        int suffix = 2;
        do
        {
            candidate = stem + ' ' + suffix++;
        }
        while(!usedNames.add(normalize(candidate)));
        return candidate;
    }

    private static EnumSet<AliasListFamily> supportedFamilies(MatcherRow matcher)
    {
        EnumSet<AliasListFamily> families = EnumSet.noneOf(AliasListFamily.class);
        if(matcher.rejectionReason() != null || isRetiredMatcher(matcher) ||
            !SUPPORTED_MATCHER_TYPES.contains(matcher.type()))
        {
            return families;
        }

        AliasID identifier = matcherIdentifier(matcher);
        if(identifier == null || !identifier.isValid())
        {
            return families;
        }

        for(AliasListFamily family: AliasListFamily.values())
        {
            if(AliasMatchRegistry.isOperational(new AliasListDefinition("Legacy", family), identifier))
            {
                families.add(family);
            }
        }
        return families;
    }

    private static List<DefinitionPlan> compatibleTargets(LegacyListGroup group, MatcherRow matcher)
    {
        AliasID identifier = matcherIdentifier(matcher);
        if(matcher.rejectionReason() != null || identifier == null || !identifier.isValid())
        {
            return List.of();
        }

        return group.mDefinitions.values().stream()
            .filter(definition -> AliasMatchRegistry.isOperational(
                definition.toAliasListDefinition(), identifier))
            .toList();
    }

    private static AliasID matcherIdentifier(MatcherRow matcher)
    {
        try
        {
            Protocol protocol = parseEnum(Protocol.class, matcher.protocol(), Protocol.UNKNOWN);
            return switch(matcher.type())
            {
                case "TALKGROUP" -> new Talkgroup(protocol, orZero(matcher.value()));
                case "TALKGROUP_RANGE" ->
                    new TalkgroupRange(protocol, orZero(matcher.minimum()), orZero(matcher.maximum()));
                case "P25_FULLY_QUALIFIED_TALKGROUP" ->
                    new P25FullyQualifiedTalkgroup(orZero(matcher.wacn()),
                        orZero(matcher.p25SystemId()), orZero(matcher.value()));
                case "RADIO_ID" -> new Radio(protocol, orZero(matcher.value()));
                case "RADIO_ID_RANGE" ->
                    new RadioRange(protocol, orZero(matcher.minimum()), orZero(matcher.maximum()));
                case "P25_FULLY_QUALIFIED_RADIO_ID" ->
                    new P25FullyQualifiedRadio(orZero(matcher.wacn()),
                        orZero(matcher.p25SystemId()), orZero(matcher.value()));
                case "STATUS" -> {
                    UserStatusID status = new UserStatusID();
                    status.setStatus(orZero(matcher.numericValue()));
                    yield status;
                }
                case "UNIT_STATUS" -> {
                    UnitStatusID status = new UnitStatusID();
                    status.setStatus(orZero(matcher.numericValue()));
                    yield status;
                }
                case "TONES" -> new TonesID(parseToneSequence(matcher.toneSequence()));
                case "DCS" -> {
                    Dcs dcs = new Dcs();
                    dcs.setDCSCode(parseEnum(DCSCode.class, matcher.textValue(), null));
                    yield dcs;
                }
                case "ESN" -> {
                    Esn esn = new Esn();
                    esn.setEsn(matcher.textValue());
                    yield esn;
                }
                default -> null;
            };
        }
        catch(RuntimeException e)
        {
            return null;
        }
    }

    private static ToneSequence parseToneSequence(String value)
    {
        ToneSequence sequence = new ToneSequence();
        if(value == null || value.isBlank())
        {
            return sequence;
        }

        for(String encodedTone: value.split(","))
        {
            String[] parts = encodedTone.split(":", 2);
            if(parts.length == 2)
            {
                try
                {
                    sequence.addTone(new Tone(parseEnum(AmbeTone.class, parts[0], AmbeTone.INVALID),
                        Integer.parseInt(parts[1])));
                }
                catch(NumberFormatException ignored)
                {
                    //The store retains the malformed value but exposes no invalid duration as an operational tone.
                }
            }
        }
        return sequence;
    }

    private static void renameV3AliasTables(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(String table: V3_ALIAS_TABLES)
            {
                statement.executeUpdate("ALTER TABLE " + table + " RENAME TO " + table + "_v3");
            }
        }
    }

    private static void dropV3AliasIndexes(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(String index: V3_ALIAS_INDEXES)
            {
                statement.executeUpdate("DROP INDEX " + index);
            }
        }
    }

    private static Map<String,Long> insertDefinitions(Connection connection, List<ListDefinitionRow> definitions)
        throws SQLException
    {
        Map<String,Long> ids = new HashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list (
                name, family
            ) VALUES (?, ?)
            """, Statement.RETURN_GENERATED_KEYS))
        {
            for(ListDefinitionRow definition: definitions)
            {
                statement.setString(1, definition.name());
                statement.setString(2, definition.family().name());
                statement.executeUpdate();

                try(ResultSet keys = statement.getGeneratedKeys())
                {
                    if(!keys.next())
                    {
                        throw new SQLException("SQLite did not return a generated alias-list ID");
                    }
                    ids.put(definition.key(), keys.getLong(1));
                }
            }
        }
        return ids;
    }

    private static MigrationCounts copyAliases(Connection connection, List<SourceAlias> aliases,
                                               Map<Long,List<MatcherRow>> matchers,
                                               RouteInventory routes,
                                               MigrationPlan plan, Map<String,Long> definitionIds,
                                               long discardedActions, long removedRetiredChannels,
                                               long targetConfigurationChannels)
        throws SQLException
    {
        long maximumSourceId = aliases.stream().mapToLong(SourceAlias::id).max().orElse(0L);
        long cloneOffset = 0L;
        long sourceMatchers = 0L;
        long collapsedDuplicateMatchers = 0L;
        long targetAliases = 0L;
        long clonedAliases = 0L;
        long targetRoutes = 0L;
        long skippedMatcherlessAliases = 0L;
        long skippedInvalidMatchers = 0L;
        long skippedRetiredMatchers = 0L;
        long skippedUntypedMatchers = 0L;
        long skippedIncompatibleMatchers = 0L;
        long skippedAliases = 0L;
        long skippedBroadcastRoutes = 0L;
        long removedNonRecordableFlags = aliases.stream().filter(SourceAlias::nonRecordable).count();

        try(PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO alias (
                id, alias_list_id, name, description, group_name, color, icon_name,
                stream_as_talkgroup, record_enabled, priority, matcher_type,
                protocol, value, min_value, max_value, wacn, p25_system_id, text_value,
                numeric_value, tone_sequence
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(SourceAlias alias: aliases)
            {
                String groupKey = normalize(alias.aliasListName() == null || alias.aliasListName().isBlank() ?
                    NO_ALIAS_LIST : alias.aliasListName());
                LegacyListGroup group = plan.groups().get(groupKey);
                if(group == null)
                {
                    throw new SQLException("No migrated alias-list group for alias " + alias.id());
                }

                List<MatcherRow> sourceAliasMatchers = matchers.getOrDefault(alias.id(), List.of());
                sourceMatchers += sourceAliasMatchers.size();
                List<MatcherRow> aliasMatchers = uniqueMatchers(sourceAliasMatchers);
                collapsedDuplicateMatchers += sourceAliasMatchers.size() - aliasMatchers.size();
                if(aliasMatchers.isEmpty())
                {
                    skippedMatcherlessAliases++;
                    skippedAliases++;
                    skippedBroadcastRoutes += routes.canonicalRoutes()
                        .getOrDefault(alias.id(), List.of()).size();
                    continue;
                }

                int aliasOutputIndex = 0;
                for(MatcherRow matcher: aliasMatchers)
                {
                    if(matcher.rejectionReason() != null)
                    {
                        skippedInvalidMatchers++;
                        continue;
                    }

                    if(isRetiredMatcher(matcher) || !SUPPORTED_MATCHER_TYPES.contains(matcher.type()))
                    {
                        skippedRetiredMatchers++;
                        continue;
                    }

                    AliasID identifier = matcherIdentifier(matcher);
                    if(identifier == null || !identifier.isValid())
                    {
                        skippedInvalidMatchers++;
                        continue;
                    }
                    if(group.mDefinitions.isEmpty())
                    {
                        skippedUntypedMatchers++;
                        continue;
                    }

                    List<DefinitionPlan> targets = compatibleTargets(group, matcher);
                    if(targets.isEmpty())
                    {
                        skippedIncompatibleMatchers++;
                        continue;
                    }

                    for(DefinitionPlan definition: targets)
                    {
                        Long definitionId = definitionIds.get(definition.mKey);
                        if(definitionId == null)
                        {
                            throw new SQLException("No durable alias-list ID for " + definition.mName);
                        }

                        long targetId = alias.id();
                        if(aliasOutputIndex++ > 0)
                        {
                            try
                            {
                                cloneOffset = Math.incrementExact(cloneOffset);
                                targetId = Math.addExact(maximumSourceId, cloneOffset);
                            }
                            catch(ArithmeticException e)
                            {
                                throw new SQLException("Alias IDs have no remaining range for split clones", e);
                            }
                            clonedAliases++;
                        }

                        bindAlias(insert, targetId, definitionId, alias, matcher);
                        insert.executeUpdate();
                        targetRoutes += copyRoutes(connection,
                            routes.canonicalRoutes().getOrDefault(alias.id(), List.of()), targetId);
                        targetAliases++;
                    }
                }
                if(aliasOutputIndex == 0)
                {
                    skippedAliases++;
                    skippedBroadcastRoutes += routes.canonicalRoutes()
                        .getOrDefault(alias.id(), List.of()).size();
                }
            }
        }

        return new MigrationCounts(aliases.size(), sourceMatchers, collapsedDuplicateMatchers,
            routes.sourceRows(), routes.collapsedDuplicates(), targetAliases, clonedAliases,
            plan.definitions().size(), targetRoutes, discardedActions, removedRetiredChannels,
            targetConfigurationChannels, skippedMatcherlessAliases,
            skippedInvalidMatchers, skippedRetiredMatchers, skippedUntypedMatchers,
            skippedIncompatibleMatchers, skippedAliases, plan.detachedChannelIds().size(),
            removedNonRecordableFlags, skippedBroadcastRoutes);
    }

    private static void bindAlias(PreparedStatement statement, long targetId, long definitionId,
                                  SourceAlias alias, MatcherRow matcher)
        throws SQLException
    {
        statement.setLong(1, targetId);
        statement.setLong(2, definitionId);
        statement.setString(3, alias.name());
        statement.setString(4, alias.description());
        statement.setString(5, alias.groupName());
        statement.setInt(6, alias.color());
        statement.setString(7, alias.iconName());
        setInteger(statement, 8, alias.streamAsTalkgroup());
        statement.setInt(9, alias.recordEnabled() ? 1 : 0);
        setInteger(statement, 10, alias.priority());
        statement.setString(11, matcher.type());
        statement.setString(12, matcher.protocol());
        setInteger(statement, 13, matcher.value());
        setInteger(statement, 14, matcher.minimum());
        setInteger(statement, 15, matcher.maximum());
        setInteger(statement, 16, matcher.wacn());
        setInteger(statement, 17, matcher.p25SystemId());
        statement.setString(18, matcher.textValue());
        setInteger(statement, 19, matcher.numericValue());
        statement.setString(20, matcher.toneSequence());
    }

    private static int copyRoutes(Connection connection, List<String> routes, long targetAliasId)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_broadcast_channel(alias_id, channel_name)
            VALUES (?, ?)
            """))
        {
            for(String route: routes)
            {
                statement.setLong(1, targetAliasId);
                statement.setString(2, route);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        return routes.size();
    }

    private static void rewireChannelAliasListNames(Connection connection, MigrationPlan plan)
        throws SQLException
    {
        Map<String,String> definitionNames = new HashMap<>();
        plan.definitions().forEach(definition -> definitionNames.put(definition.key(), definition.name()));

        try(PreparedStatement update = connection.prepareStatement("""
            UPDATE configuration_channel
            SET alias_list_name=?, config_json=json_set(config_json, '$.aliasListName', ?)
            WHERE id=?
            """))
        {
            for(Map.Entry<Long,String> channel: plan.channelDefinitionKeys().entrySet())
            {
                String definitionName = definitionNames.get(channel.getValue());
                if(definitionName == null)
                {
                    throw new SQLException("No migrated alias-list definition for channel " + channel.getKey());
                }
                update.setString(1, definitionName);
                update.setString(2, definitionName);
                update.setLong(3, channel.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void dropRenamedV3Tables(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(String table: List.of("alias_broadcast_channel_v3", "alias_talkgroup_v3", "alias_radio_v3",
                "alias_status_v3", "alias_tone_sequence_v3", "alias_text_identifier_v3", "alias_action_v3",
                "alias_v3"))
            {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }
    }

    private static RouteInventory loadRoutes(Connection connection) throws SQLException
    {
        Map<Long,List<String>> sourceRoutes = new LinkedHashMap<>();
        long sourceRows = 0L;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT alias_id, channel_name
                FROM alias_broadcast_channel
                ORDER BY alias_id, sort_order, id
                """))
        {
            while(resultSet.next())
            {
                long aliasId = resultSet.getLong("alias_id");
                String route = resultSet.getString("channel_name");
                if(route == null || route.isBlank())
                {
                    throw new SQLException("Alias-v3 broadcast route for alias [" + aliasId +
                        "] has a blank channel name");
                }
                sourceRoutes.computeIfAbsent(aliasId, ignored -> new ArrayList<>()).add(route);
                sourceRows++;
            }
        }

        long collapsedDuplicates = 0L;
        Map<Long,List<String>> canonicalRoutes = new LinkedHashMap<>();
        for(Map.Entry<Long,List<String>> entry: sourceRoutes.entrySet())
        {
            List<String> canonical = List.copyOf(new TreeSet<>(entry.getValue()));
            collapsedDuplicates += entry.getValue().size() - canonical.size();
            canonicalRoutes.put(entry.getKey(), canonical);
        }

        return new RouteInventory(Map.copyOf(canonicalRoutes), sourceRows, collapsedDuplicates);
    }

    private static long count(Connection connection, String table) throws SQLException
    {
        return countWhere(connection, table, "1=1");
    }

    private static long countWhere(Connection connection, String table, String where) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where))
        {
            if(!resultSet.next())
            {
                throw new SQLException("No row count returned for " + table);
            }
            return resultSet.getLong(1);
        }
    }

    private static Integer integer(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value);
        }
        else
        {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNoAliasList(String value)
    {
        return value == null || value.isBlank() || NO_ALIAS_LIST.equalsIgnoreCase(value.trim());
    }

    private static AliasListFamily familyForDecoder(String decoder)
    {
        if(decoder == null)
        {
            return null;
        }
        return switch(decoder.trim().toUpperCase(Locale.ROOT))
        {
            case "P25_CONVENTIONAL", "P25_PHASE1", "P25_PHASE2" -> AliasListFamily.P25;
            case "DMR" -> AliasListFamily.DMR;
            case "NXDN" -> AliasListFamily.NXDN;
            case "NBFM" -> AliasListFamily.NBFM;
            default -> null;
        };
    }

    private static int orZero(Integer value)
    {
        return value != null ? value : 0;
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, T fallback)
    {
        if(value == null)
        {
            return fallback;
        }
        try
        {
            return Enum.valueOf(enumType, value);
        }
        catch(IllegalArgumentException e)
        {
            return fallback;
        }
    }

    private static final class LegacyListGroup
    {
        private final String mName;
        private final EnumSet<AliasListFamily> mClaimedFamilies = EnumSet.noneOf(AliasListFamily.class);
        private final EnumSet<AliasListFamily> mInferredFamilies = EnumSet.noneOf(AliasListFamily.class);
        private final List<EnumSet<AliasListFamily>> mAmbiguousMatcherFamilies = new ArrayList<>();
        private final Map<AliasListFamily,DefinitionPlan> mDefinitions = new EnumMap<>(AliasListFamily.class);

        private LegacyListGroup(String name)
        {
            mName = name;
        }

        private EnumSet<AliasListFamily> families()
        {
            EnumSet<AliasListFamily> families = EnumSet.noneOf(AliasListFamily.class);
            families.addAll(mClaimedFamilies);
            families.addAll(mInferredFamilies);

            if(families.isEmpty() && !mAmbiguousMatcherFamilies.isEmpty())
            {
                EnumSet<AliasListFamily> sharedFamilies = EnumSet.copyOf(mAmbiguousMatcherFamilies.getFirst());
                for(EnumSet<AliasListFamily> matcherFamilies: mAmbiguousMatcherFamilies)
                {
                    sharedFamilies.retainAll(matcherFamilies);
                }
                families.addAll(sharedFamilies);
            }

            for(EnumSet<AliasListFamily> matcherFamilies: mAmbiguousMatcherFamilies)
            {
                EnumSet<AliasListFamily> overlap = EnumSet.copyOf(matcherFamilies);
                overlap.retainAll(families);
                if(overlap.isEmpty())
                {
                    families.add(matcherFamilies.iterator().next());
                }
            }
            return families;
        }
    }

    private static final class DefinitionPlan
    {
        private final String mKey;
        private final String mName;
        private final AliasListFamily mFamily;

        private DefinitionPlan(String key, String name, AliasListFamily family)
        {
            mKey = key;
            mName = name;
            mFamily = family;
        }

        private AliasListDefinition toAliasListDefinition()
        {
            return new AliasListDefinition(mName, mFamily);
        }

        private ListDefinitionRow toRow()
        {
            return new ListDefinitionRow(mKey, mName, mFamily);
        }
    }

    private record SourceAlias(long id, int sortOrder, String name, String description, String aliasListName,
                               String groupName, int color, String iconName, Integer streamAsTalkgroup,
                               boolean recordEnabled, boolean nonRecordable, Integer priority)
    {
    }

    private record MatcherRow(int sourceRank, long sourceRowId, long sourceAliasId, int sortOrder, String type,
                              String protocol, Integer value, Integer minimum, Integer maximum, Integer wacn,
                              Integer p25SystemId, String textValue, String textValue2, Integer numericValue,
                              String toneSequence, String rejectionReason)
    {
        private MatcherRow withRejectionReason(String reason)
        {
            return new MatcherRow(sourceRank, sourceRowId, sourceAliasId, sortOrder, type, protocol, value,
                minimum, maximum, wacn, p25SystemId, textValue, textValue2, numericValue, toneSequence, reason);
        }
    }

    private record ListDefinitionRow(String key, String name, AliasListFamily family)
    {
    }

    private record ChannelFamilyClaim(String groupKey, AliasListFamily family)
    {
    }

    private record MigrationPlan(List<ListDefinitionRow> definitions,
                                 Map<String,LegacyListGroup> groups,
                                 Map<Long,String> channelDefinitionKeys,
                                 Set<Long> detachedChannelIds)
    {
    }

    private record RouteInventory(Map<Long,List<String>> canonicalRoutes, long sourceRows,
                                  long collapsedDuplicates)
    {
    }

    record Result(long targetAliases, long aliasLists, long targetRoutes, long discardedActions,
                  long removedRetiredChannels, long removedNonRecordableFlags,
                  long discardedLegacyMatcherDetailFields,
                  long collapsedDuplicateMatchers, long collapsedDuplicateRoutes,
                  long skippedBroadcastRoutes,
                  long skippedMatcherlessAliases, long skippedInvalidMatchers,
                  long skippedRetiredMatchers, long skippedUntypedMatchers,
                  long skippedIncompatibleMatchers, long skippedAliases,
                  long detachedUnsupportedChannels)
    {
    }

    record MigrationCounts(long sourceAliases, long sourceMatchers, long collapsedDuplicateMatchers,
                           long sourceRoutes, long collapsedDuplicateRoutes,
                           long targetAliases, long clonedAliases, long aliasLists, long targetRoutes,
                           long discardedActions, long removedRetiredChannels,
                           long targetConfigurationChannels, long skippedMatcherlessAliases,
                           long skippedInvalidMatchers, long skippedRetiredMatchers,
                           long skippedUntypedMatchers, long skippedIncompatibleMatchers,
                           long skippedAliases, long detachedConflictingChannels,
                           long removedNonRecordableFlags, long skippedBroadcastRoutes)
    {
    }
}

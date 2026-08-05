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

package io.github.dsheirer.database.alias;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * SQLite persistence for alias-list definitions and their aliases.
 *
 * <p>Alias schema v5 gives both lists and aliases durable identities. Each alias row contains exactly one operational
 * matcher. Recording, playback, and streaming routes remain behavior attached to that matcher. A list-level unmatched
 * talkgroup policy carries actions only and is never represented as an alias or match identifier.</p>
 */
public class AliasDatabaseStore
{
    private final Path mDatabasePath;

    public AliasDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    /**
     * Loads all durable alias-list definitions.
     */
    public List<AliasListDefinition> loadAliasListDefinitions() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return loadAliasListDefinitions(connection);
        }
    }

    /**
     * Loads aliases and attaches them to the supplied definition instances.
     */
    public List<Alias> loadAliases(List<AliasListDefinition> definitions) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return loadAliases(connection, definitions);
        }
    }

    /**
     * Saves a complete alias/list snapshot using a caller-owned transaction. This overload exists so import workflows
     * can commit aliases, list definitions, channels, and streams atomically.
     *
     * @param connection open connection with auto-commit disabled
     */
    public void replaceAliases(Connection connection, List<Alias> aliases, List<AliasListDefinition> definitions)
        throws SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Alias snapshot writes require a caller-owned transaction");
        }

        List<Alias> safeAliases = aliases != null ? aliases : List.of();
        List<AliasListDefinition> safeDefinitions = definitions != null ? new ArrayList<>(definitions) :
            new ArrayList<>();
        validateSnapshot(safeAliases, safeDefinitions);
        clearSnapshot(connection);
        saveDefinitions(connection, safeDefinitions);
        attachDefinitions(safeAliases, safeDefinitions);
        saveAliases(connection, safeAliases);
    }

    private void clearSnapshot(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias");
            statement.executeUpdate("DELETE FROM alias_list");
        }
    }

    private List<AliasListDefinition> loadAliasListDefinitions(Connection connection) throws SQLException
    {
        List<AliasListDefinition> definitions = new ArrayList<>();
        Map<Long,List<String>> streamDestinations = loadUnmatchedTalkgroupStreams(connection);
        Set<Long> loadedDefinitionIds = new HashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, name, family, unmatched_talkgroup_priority,
                   unmatched_talkgroup_record_enabled
            FROM alias_list
            ORDER BY id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long definitionId = resultSet.getLong("id");
                if(resultSet.wasNull() || definitionId <= AliasListDefinition.UNASSIGNED_ID)
                {
                    throw new SQLException("Persisted alias-list IDs must be greater than zero");
                }
                UnmatchedTalkgroupPolicy policy;
                try
                {
                    policy = new UnmatchedTalkgroupPolicy(
                        resultSet.getInt("unmatched_talkgroup_priority"),
                        getBoolean(resultSet, "unmatched_talkgroup_record_enabled"),
                        streamDestinations.getOrDefault(definitionId, List.of()));
                }
                catch(IllegalArgumentException e)
                {
                    throw new SQLException("Alias list [" + definitionId +
                        "] has an invalid unmatched talkgroup policy", e);
                }

                AliasListDefinition definition = new AliasListDefinition(resultSet.getString("name"),
                    requireEnum(AliasListFamily.class, resultSet.getString("family"), "alias_list.family"), policy);
                definition.setId(definitionId);
                definitions.add(definition);
                loadedDefinitionIds.add(definitionId);
            }
        }

        for(Long aliasListId: streamDestinations.keySet())
        {
            if(!loadedDefinitionIds.contains(aliasListId))
            {
                throw new SQLException("Unmatched talkgroup stream route references unknown alias_list_id [" +
                    aliasListId + "]");
            }
        }

        validateSnapshot(List.of(), definitions);
        return definitions;
    }

    private List<Alias> loadAliases(Connection connection, List<AliasListDefinition> definitions) throws SQLException
    {
        Map<Long,AliasListDefinition> definitionsById = new HashMap<>();
        if(definitions != null)
        {
            for(AliasListDefinition definition: definitions)
            {
                if(definition != null && definition.getId() != AliasListDefinition.UNASSIGNED_ID)
                {
                    definitionsById.put(definition.getId(), definition);
                }
            }
        }

        Map<Long,Alias> aliases = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias.id, alias.alias_list_id, alias.name, alias.description,
                   alias.group_name, alias.color, alias.icon_name, alias.stream_as_talkgroup,
                   alias.record_enabled, alias.priority,
                   alias.matcher_type, alias.protocol, alias.value,
                   alias.min_value, alias.max_value, alias.wacn, alias.p25_system_id,
                   alias.text_value, alias.numeric_value, alias.tone_sequence
            FROM alias
            ORDER BY alias.id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long aliasId = resultSet.getLong("id");
                if(resultSet.wasNull() || aliasId <= Alias.UNASSIGNED_ID)
                {
                    throw new SQLException("Persisted alias IDs must be greater than zero");
                }
                Alias alias = new Alias(resultSet.getString("name"));
                alias.setId(aliasId);
                alias.setDescription(resultSet.getString("description"));
                alias.setGroup(resultSet.getString("group_name"));
                alias.setColor(resultSet.getInt("color"));
                alias.setIconName(resultSet.getString("icon_name"));

                Long aliasListId = getLong(resultSet, "alias_list_id");
                if(aliasListId == null)
                {
                    throw new SQLException("Alias row [" + aliasId + "] has no alias_list_id");
                }
                AliasListDefinition definition = definitionsById.get(aliasListId);
                if(definition == null)
                {
                    throw new SQLException("Alias row [" + aliasId + "] references unknown alias_list_id [" +
                        aliasListId + "]");
                }
                alias.setAliasListDefinition(definition);

                Integer streamTalkgroup = getInteger(resultSet, "stream_as_talkgroup");
                if(streamTalkgroup != null)
                {
                    alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(streamTalkgroup));
                }

                AliasID matcher = toMatcher(resultSet);
                if(matcher != null)
                {
                    alias.setMatchIdentifier(matcher);
                }
                alias.setRecordable(getBoolean(resultSet, "record_enabled"));
                Integer priority = getInteger(resultSet, "priority");
                if(priority != null)
                {
                    if(!isValidPriority(priority))
                    {
                        throw new SQLException("Alias [" + alias.getName() +
                            "] has invalid stored priority [" + priority + "]");
                    }
                    alias.setCallPriority(priority);
                }
                aliases.put(aliasId, alias);
            }

            Map<Long,List<BroadcastChannel>> broadcastChannels =
                loadBroadcastChannels(connection, aliases.keySet());

            for(Map.Entry<Long,Alias> entry: aliases.entrySet())
            {
                Alias alias = entry.getValue();

                for(BroadcastChannel broadcastChannel:
                    broadcastChannels.getOrDefault(entry.getKey(), List.of()))
                {
                    alias.addBroadcastChannel(broadcastChannel);
                }
            }
        }

        List<Alias> loaded = new ArrayList<>(aliases.values());
        validateSnapshot(loaded, definitions != null ? definitions : List.of());
        return loaded;
    }

    private void validateSnapshot(List<Alias> aliases, List<AliasListDefinition> definitions) throws SQLException
    {
        Set<Long> definitionIds = new HashSet<>();
        Set<String> definitionNames = new HashSet<>();

        for(AliasListDefinition definition: definitions)
        {
            if(definition == null || definition.getName() == null || definition.getName().isBlank())
            {
                throw new SQLException("Alias-list definitions must have a name");
            }
            if(definition.getId() != AliasListDefinition.UNASSIGNED_ID && !definitionIds.add(definition.getId()))
            {
                throw new SQLException("Duplicate alias-list id [" + definition.getId() + "]");
            }
            if(!definitionNames.add(normalize(definition.getName())))
            {
                throw new SQLException("Duplicate alias-list name [" + definition.getName() + "]");
            }
            if(definition.getFamily() == null)
            {
                throw new SQLException("Alias list [" + definition.getName() +
                    "] must declare a protocol family");
            }
            UnmatchedTalkgroupPolicy policy = definition.getUnmatchedTalkgroupPolicy();
            if(policy == null || !UnmatchedTalkgroupPolicy.isValidPlaybackPriority(policy.getPlaybackPriority()))
            {
                throw new SQLException("Alias list [" + definition.getName() +
                    "] has an invalid unmatched talkgroup policy");
            }
            Set<String> streamDestinations = new HashSet<>();
            for(String destination: policy.getStreamDestinationNames())
            {
                if(destination == null || destination.isBlank() || !streamDestinations.add(destination))
                {
                    throw new SQLException("Alias list [" + definition.getName() +
                        "] has invalid unmatched talkgroup stream destinations");
                }
            }
        }

        Set<Long> aliasIds = new HashSet<>();
        Map<Long,AliasListDefinition> definitionsById = new HashMap<>();
        Map<String,AliasListDefinition> definitionsByName = new HashMap<>();
        for(AliasListDefinition definition: definitions)
        {
            if(definition.getId() != AliasListDefinition.UNASSIGNED_ID)
            {
                definitionsById.put(definition.getId(), definition);
            }
            definitionsByName.put(normalize(definition.getName()), definition);
        }

        for(Alias alias: aliases)
        {
            if(alias == null)
            {
                throw new SQLException("Alias collection contains a null entry");
            }
            if(alias.getId() != Alias.UNASSIGNED_ID && !aliasIds.add(alias.getId()))
            {
                throw new SQLException("Duplicate alias id [" + alias.getId() + "]");
            }

            AliasListDefinition definition = definitionsById.get(alias.getAliasListId());
            if(definition == null && alias.getAliasListId() == Alias.UNASSIGNED_ALIAS_LIST_ID)
            {
                AliasListDefinition namedDefinition =
                    definitionsByName.get(normalize(alias.getAliasListName()));
                if(alias.getId() == Alias.UNASSIGNED_ID ||
                    namedDefinition != null &&
                        namedDefinition.getId() == AliasListDefinition.UNASSIGNED_ID)
                {
                    definition = namedDefinition;
                }
            }
            if(definition == null)
            {
                throw new SQLException("Alias [" + alias.getName() +
                    "] must reference an existing alias-list definition");
            }
            AliasID matcher = alias.getMatchIdentifier();
            if(matcher == null)
            {
                throw new SQLException("Alias [" + alias.getName() + "] must have exactly one match identifier");
            }
            if(!AliasMatchRegistry.isOperational(definition, matcher))
            {
                throw new SQLException("Alias matcher [" + matcher + "] is not valid for alias list [" +
                    definition.getName() + "]");
            }
            for(BroadcastChannel broadcastChannel: alias.getBroadcastChannels())
            {
                if(broadcastChannel == null || broadcastChannel.getChannelName() == null ||
                    broadcastChannel.getChannelName().isBlank())
                {
                    throw new SQLException("Alias [" + alias.getName() +
                        "] contains a blank broadcast route");
                }
            }
        }
    }

    private void saveDefinitions(Connection connection, List<AliasListDefinition> definitions) throws SQLException
    {
        for(AliasListDefinition definition: definitions)
        {
            if(definition.getId() == AliasListDefinition.UNASSIGNED_ID)
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alias_list (
                        name, family, unmatched_talkgroup_priority,
                        unmatched_talkgroup_record_enabled
                    ) VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS))
                {
                    bindDefinition(statement, definition, 1);
                    statement.executeUpdate();
                    try(ResultSet keys = statement.getGeneratedKeys())
                    {
                        if(!keys.next())
                        {
                            throw new SQLException("SQLite did not return an alias-list id");
                        }
                        definition.setId(keys.getLong(1));
                    }
                }
            }
            else
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alias_list (
                        id, name, family, unmatched_talkgroup_priority,
                        unmatched_talkgroup_record_enabled
                    ) VALUES (?, ?, ?, ?, ?)
                    """))
                {
                    statement.setLong(1, definition.getId());
                    bindDefinition(statement, definition, 2);
                    statement.executeUpdate();
                }
            }

            insertUnmatchedTalkgroupStreams(connection, definition);
        }
    }

    private void bindDefinition(PreparedStatement statement, AliasListDefinition definition, int offset)
        throws SQLException
    {
        statement.setString(offset, definition.getName());
        statement.setString(offset + 1, definition.getFamily().name());
        UnmatchedTalkgroupPolicy policy = definition.getUnmatchedTalkgroupPolicy();
        statement.setInt(offset + 2, policy.getPlaybackPriority());
        statement.setInt(offset + 3, policy.isRecordEnabled() ? 1 : 0);
    }

    private void insertUnmatchedTalkgroupStreams(Connection connection, AliasListDefinition definition)
        throws SQLException
    {
        for(String destination: definition.getUnmatchedTalkgroupPolicy().getStreamDestinationNames())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO alias_list_unmatched_talkgroup_stream (alias_list_id, channel_name)
                VALUES (?, ?)
                """))
            {
                statement.setLong(1, definition.getId());
                statement.setString(2, destination);
                statement.executeUpdate();
            }
        }
    }

    private Map<Long,List<String>> loadUnmatchedTalkgroupStreams(Connection connection) throws SQLException
    {
        Map<Long,List<String>> destinations = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_list_id, channel_name
            FROM alias_list_unmatched_talkgroup_stream
            ORDER BY alias_list_id, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long aliasListId = resultSet.getLong("alias_list_id");
                if(resultSet.wasNull() || aliasListId <= AliasListDefinition.UNASSIGNED_ID)
                {
                    throw new SQLException("Unmatched talkgroup stream route has no valid alias_list_id");
                }

                String destination = resultSet.getString("channel_name");
                if(destination == null || destination.isBlank())
                {
                    throw new SQLException("Unmatched talkgroup stream route for alias list [" + aliasListId +
                        "] must have a nonblank name");
                }
                destinations.computeIfAbsent(aliasListId, ignored -> new ArrayList<>()).add(destination);
            }
        }
        return destinations;
    }

    private void attachDefinitions(List<Alias> aliases, List<AliasListDefinition> definitions) throws SQLException
    {
        Map<Long,AliasListDefinition> byId = new HashMap<>();
        Map<String,AliasListDefinition> byName = new HashMap<>();
        for(AliasListDefinition definition: definitions)
        {
            byId.put(definition.getId(), definition);
            byName.put(normalize(definition.getName()), definition);
        }

        for(Alias alias: aliases)
        {
            AliasListDefinition definition = byId.get(alias.getAliasListId());
            if(definition == null &&
                alias.getAliasListId() == Alias.UNASSIGNED_ALIAS_LIST_ID &&
                alias.getAliasListName() != null)
            {
                //validateSnapshot already limited persisted aliases in this state to a newly-created definition.
                definition = byName.get(normalize(alias.getAliasListName()));
            }
            if(definition != null)
            {
                alias.setAliasListDefinition(definition);
            }
            else
            {
                throw new SQLException("Alias [" + alias.getName() +
                    "] must reference an existing alias-list definition");
            }
        }
    }

    private void saveAliases(Connection connection, List<Alias> aliases) throws SQLException
    {
        for(Alias alias: aliases)
        {
            long aliasId;
            if(alias.getId() == Alias.UNASSIGNED_ID)
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alias (
                        alias_list_id, name, description, group_name, color, icon_name,
                        stream_as_talkgroup, record_enabled, priority, matcher_type,
                        protocol, value, min_value, max_value, wacn, p25_system_id, text_value,
                        numeric_value, tone_sequence
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS))
                {
                    bindAlias(statement, alias, 1);
                    statement.executeUpdate();
                    try(ResultSet keys = statement.getGeneratedKeys())
                    {
                        if(!keys.next())
                        {
                            throw new SQLException("SQLite did not return an alias id");
                        }
                        aliasId = keys.getLong(1);
                        alias.setId(aliasId);
                    }
                }
            }
            else
            {
                aliasId = alias.getId();
                try(PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alias (
                        id, alias_list_id, name, description, group_name, color, icon_name,
                        stream_as_talkgroup, record_enabled, priority, matcher_type,
                        protocol, value, min_value, max_value, wacn, p25_system_id, text_value,
                        numeric_value, tone_sequence
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """))
                {
                    statement.setLong(1, aliasId);
                    bindAlias(statement, alias, 2);
                    statement.executeUpdate();
                }
            }

            insertAliasChildren(connection, aliasId, alias);
        }
    }

    private void bindAlias(PreparedStatement statement, Alias alias, int offset) throws SQLException
    {
        MatcherData matcher = matcherData(alias);
        if(alias.getAliasListId() == Alias.UNASSIGNED_ALIAS_LIST_ID)
        {
            throw new SQLException("Alias [" + alias.getName() + "] has no durable alias-list assignment");
        }
        statement.setLong(offset, alias.getAliasListId());
        statement.setString(offset + 1, alias.getName());
        statement.setString(offset + 2, alias.getDescription());
        statement.setString(offset + 3, alias.getGroup());
        statement.setInt(offset + 4, alias.getColor());
        statement.setString(offset + 5, alias.getIconName());

        StreamAsTalkgroup streamAsTalkgroup = alias.getStreamTalkgroupAlias();
        setInteger(statement, offset + 6, streamAsTalkgroup != null ? streamAsTalkgroup.getValue() : null);
        statement.setInt(offset + 7, alias.isRecordable() ? 1 : 0);
        setInteger(statement, offset + 8, alias.hasCallPriority() ? alias.getPlaybackPriority() : null);
        statement.setString(offset + 9, matcher.type());
        statement.setString(offset + 10, matcher.protocol());
        setInteger(statement, offset + 11, matcher.value());
        setInteger(statement, offset + 12, matcher.minimum());
        setInteger(statement, offset + 13, matcher.maximum());
        setInteger(statement, offset + 14, matcher.wacn());
        setInteger(statement, offset + 15, matcher.p25SystemId());
        statement.setString(offset + 16, matcher.textValue());
        setInteger(statement, offset + 17, matcher.numericValue());
        statement.setString(offset + 18, matcher.toneSequence());
    }

    private MatcherData matcherData(Alias alias) throws SQLException
    {
        AliasID matcher = alias.getMatchIdentifier();
        if(matcher == null)
        {
            throw new SQLException("Alias [" + alias.getName() + "] has no match identifier");
        }

        String protocol = null;
        Integer value = null;
        Integer minimum = null;
        Integer maximum = null;
        Integer wacn = null;
        Integer p25SystemId = null;
        String textValue = null;
        Integer numericValue = null;
        String toneSequence = null;

        switch(matcher.getType())
        {
            case TALKGROUP -> {
                Talkgroup talkgroup = (Talkgroup)matcher;
                protocol = protocol(talkgroup.getProtocol());
                value = talkgroup.getValue();
            }
            case TALKGROUP_RANGE -> {
                TalkgroupRange range = (TalkgroupRange)matcher;
                protocol = protocol(range.getProtocol());
                minimum = range.getMinTalkgroup();
                maximum = range.getMaxTalkgroup();
            }
            case P25_FULLY_QUALIFIED_TALKGROUP -> {
                P25FullyQualifiedTalkgroup talkgroup = (P25FullyQualifiedTalkgroup)matcher;
                protocol = protocol(talkgroup.getProtocol());
                value = talkgroup.getValue();
                wacn = talkgroup.getWacn();
                p25SystemId = talkgroup.getSystem();
            }
            case RADIO_ID -> {
                Radio radio = (Radio)matcher;
                protocol = protocol(radio.getProtocol());
                value = radio.getValue();
            }
            case RADIO_ID_RANGE -> {
                RadioRange range = (RadioRange)matcher;
                protocol = protocol(range.getProtocol());
                minimum = range.getMinRadio();
                maximum = range.getMaxRadio();
            }
            case P25_FULLY_QUALIFIED_RADIO_ID -> {
                P25FullyQualifiedRadio radio = (P25FullyQualifiedRadio)matcher;
                protocol = protocol(radio.getProtocol());
                value = radio.getValue();
                wacn = radio.getWacn();
                p25SystemId = radio.getSystem();
            }
            case STATUS -> numericValue = ((UserStatusID)matcher).getStatus();
            case UNIT_STATUS -> numericValue = ((UnitStatusID)matcher).getStatus();
            case TONES -> toneSequence = serializeToneSequence(((TonesID)matcher).getToneSequence());
            case DCS -> {
                DCSCode dcsCode = ((Dcs)matcher).getDCSCode();
                textValue = dcsCode != null ? dcsCode.name() : null;
            }
            case ESN -> textValue = ((Esn)matcher).getEsn();
            default -> throw new SQLException("Unsupported alias matcher type [" + matcher.getType() + "]");
        }

        return new MatcherData(matcher.getType().name(), protocol, value, minimum, maximum, wacn,
            p25SystemId, textValue, numericValue, toneSequence);
    }

    private AliasID toMatcher(ResultSet resultSet) throws SQLException
    {
        String storedType = resultSet.getString("matcher_type");
        if(storedType == null || storedType.isBlank())
        {
            throw new SQLException("Alias row has no matcher_type");
        }

        Protocol protocol = parseOptionalEnum(Protocol.class, resultSet.getString("protocol"),
            "alias.protocol");
        Integer value = getInteger(resultSet, "value");
        Integer minimum = getInteger(resultSet, "min_value");
        Integer maximum = getInteger(resultSet, "max_value");
        Integer wacn = getInteger(resultSet, "wacn");
        Integer p25SystemId = getInteger(resultSet, "p25_system_id");
        Integer numericValue = getInteger(resultSet, "numeric_value");
        String textValue = resultSet.getString("text_value");
        String toneSequence = resultSet.getString("tone_sequence");

        AliasIDType type = requireEnum(AliasIDType.class, storedType, "alias.matcher_type");

        AliasID matcher = switch(type)
        {
            case TALKGROUP -> {
                requireNullPayload(type, minimum, maximum, wacn, p25SystemId, textValue,
                    numericValue, toneSequence);
                yield new Talkgroup(protocol, requireInteger(value, "alias.value", type));
            }
            case TALKGROUP_RANGE -> {
                requireNullPayload(type, value, wacn, p25SystemId, textValue, numericValue,
                    toneSequence);
                yield new TalkgroupRange(protocol, requireInteger(minimum, "alias.min_value", type),
                    requireInteger(maximum, "alias.max_value", type));
            }
            case P25_FULLY_QUALIFIED_TALKGROUP -> {
                requireP25Protocol(protocol, type);
                requireNullPayload(type, minimum, maximum, textValue, numericValue, toneSequence);
                yield new P25FullyQualifiedTalkgroup(requireInteger(wacn, "alias.wacn", type),
                    requireInteger(p25SystemId, "alias.p25_system_id", type),
                    requireInteger(value, "alias.value", type));
            }
            case RADIO_ID -> {
                requireNullPayload(type, minimum, maximum, wacn, p25SystemId, textValue,
                    numericValue, toneSequence);
                yield new Radio(protocol, requireInteger(value, "alias.value", type));
            }
            case RADIO_ID_RANGE -> {
                requireNullPayload(type, value, wacn, p25SystemId, textValue, numericValue,
                    toneSequence);
                yield new RadioRange(protocol, requireInteger(minimum, "alias.min_value", type),
                    requireInteger(maximum, "alias.max_value", type));
            }
            case P25_FULLY_QUALIFIED_RADIO_ID -> {
                requireP25Protocol(protocol, type);
                requireNullPayload(type, minimum, maximum, textValue, numericValue, toneSequence);
                yield new P25FullyQualifiedRadio(requireInteger(wacn, "alias.wacn", type),
                    requireInteger(p25SystemId, "alias.p25_system_id", type),
                    requireInteger(value, "alias.value", type));
            }
            case STATUS -> {
                requireNullPayload(type, protocol, value, minimum, maximum, wacn, p25SystemId, textValue,
                    toneSequence);
                UserStatusID status = new UserStatusID();
                status.setStatus(requireInteger(numericValue, "alias.numeric_value", type));
                yield status;
            }
            case UNIT_STATUS -> {
                requireNullPayload(type, protocol, value, minimum, maximum, wacn, p25SystemId, textValue,
                    toneSequence);
                UnitStatusID status = new UnitStatusID();
                status.setStatus(requireInteger(numericValue, "alias.numeric_value", type));
                yield status;
            }
            case TONES -> {
                requireNullPayload(type, protocol, value, minimum, maximum, wacn, p25SystemId, textValue,
                    numericValue);
                yield new TonesID(parseToneSequence(toneSequence));
            }
            case DCS -> {
                requireNullPayload(type, protocol, value, minimum, maximum, wacn, p25SystemId,
                    numericValue, toneSequence);
                Dcs dcs = new Dcs();
                dcs.setDCSCode(parseOptionalEnum(DCSCode.class, textValue, "alias.text_value"));
                yield dcs;
            }
            case ESN -> {
                requireNullPayload(type, protocol, value, minimum, maximum, wacn, p25SystemId,
                    numericValue, toneSequence);
                Esn esn = new Esn();
                esn.setEsn(textValue);
                yield esn;
            }
            default -> throw new SQLException("Unsupported stored alias matcher type [" + type + "]");
        };

        matcher.updateValueProperty();
        return matcher;
    }

    private void insertAliasChildren(Connection connection, long aliasId, Alias alias) throws SQLException
    {
        for(BroadcastChannel broadcastChannel: alias.getBroadcastChannels())
        {
            if(broadcastChannel.getChannelName() == null || broadcastChannel.getChannelName().isBlank())
            {
                throw new SQLException("Alias [" + alias.getName() + "] contains a blank broadcast route");
            }
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO alias_broadcast_channel (alias_id, channel_name)
                VALUES (?, ?)
                """))
            {
                statement.setLong(1, aliasId);
                statement.setString(2, broadcastChannel.getChannelName());
                statement.executeUpdate();
            }
        }
    }

    private Map<Long,List<BroadcastChannel>> loadBroadcastChannels(Connection connection, Set<Long> aliasIds)
        throws SQLException
    {
        Map<Long,List<BroadcastChannel>> channels = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, channel_name
            FROM alias_broadcast_channel
            ORDER BY alias_id, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long aliasId = resultSet.getLong("alias_id");
                if(resultSet.wasNull() || aliasId <= Alias.UNASSIGNED_ID || !aliasIds.contains(aliasId))
                {
                    throw new SQLException("Broadcast route references unknown alias_id [" + aliasId + "]");
                }

                String channelName = resultSet.getString("channel_name");
                if(channelName == null || channelName.isBlank())
                {
                    throw new SQLException("Broadcast route for alias [" + aliasId + "] must have a nonblank name");
                }
                channels.computeIfAbsent(aliasId, ignored -> new ArrayList<>())
                    .add(new BroadcastChannel(channelName));
            }
        }
        return channels;
    }

    private String serializeToneSequence(ToneSequence toneSequence)
    {
        if(toneSequence == null || !toneSequence.hasTones())
        {
            return null;
        }

        StringJoiner joiner = new StringJoiner(",");
        for(Tone tone: toneSequence.getTones())
        {
            joiner.add(tone.getAmbeTone().name() + ':' + tone.getDuration());
        }
        return joiner.toString();
    }

    private ToneSequence parseToneSequence(String value) throws SQLException
    {
        ToneSequence sequence = new ToneSequence();
        if(value == null)
        {
            return sequence;
        }

        if(value.isBlank())
        {
            throw new SQLException("alias.tone_sequence must be NULL or use the canonical TONE:duration format");
        }

        for(String encodedTone: value.split(",", -1))
        {
            String[] parts = encodedTone.split(":", -1);
            if(parts.length != 2)
            {
                throw new SQLException("Malformed alias.tone_sequence entry [" + encodedTone + "]");
            }

            int duration;
            try
            {
                duration = Integer.parseInt(parts[1]);
            }
            catch(NumberFormatException e)
            {
                throw new SQLException("Malformed alias.tone_sequence duration [" + parts[1] + "]", e);
            }

            sequence.addTone(new Tone(requireEnum(AmbeTone.class, parts[0], "alias.tone_sequence"), duration));
        }

        if(!value.equals(serializeToneSequence(sequence)))
        {
            throw new SQLException("alias.tone_sequence is not in canonical TONE:duration form");
        }

        return sequence;
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String protocol(Protocol protocol)
    {
        return protocol != null ? protocol.name() : null;
    }

    private static int requireInteger(Integer value, String column, AliasIDType type) throws SQLException
    {
        if(value == null)
        {
            throw new SQLException(column + " is required for alias matcher type [" + type + "]");
        }
        return value;
    }

    private static void requireNullPayload(AliasIDType type, Object... values) throws SQLException
    {
        for(Object value: values)
        {
            if(value != null)
            {
                throw new SQLException("Alias matcher type [" + type + "] contains unused payload");
            }
        }
    }

    private static void requireP25Protocol(Protocol protocol, AliasIDType type) throws SQLException
    {
        if(protocol != Protocol.APCO25)
        {
            throw new SQLException("Alias matcher type [" + type + "] requires protocol APCO25");
        }
    }

    private static Integer getInteger(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long getLong(ResultSet resultSet, String column) throws SQLException
    {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static boolean getBoolean(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        if(resultSet.wasNull() || (value != 0 && value != 1))
        {
            throw new SQLException(column + " must be stored as integer 0 or 1");
        }
        return value == 1;
    }

    private static boolean isValidPriority(int priority)
    {
        return priority == Priority.DO_NOT_MONITOR ||
            (Priority.MIN_PRIORITY <= priority && priority < Priority.MAX_PRIORITY);
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

    private static <T extends Enum<T>> T requireEnum(Class<T> enumType, String value, String column)
        throws SQLException
    {
        if(value == null || value.isBlank())
        {
            throw new SQLException(column + " must contain a " + enumType.getSimpleName() + " value");
        }
        try
        {
            return Enum.valueOf(enumType, value);
        }
        catch(IllegalArgumentException e)
        {
            throw new SQLException(column + " contains unknown " + enumType.getSimpleName() +
                " value [" + value + "]", e);
        }
    }

    private static <T extends Enum<T>> T parseOptionalEnum(Class<T> enumType, String value, String column)
        throws SQLException
    {
        return value == null ? null : requireEnum(enumType, value, column);
    }

    private record MatcherData(String type, String protocol, Integer value, Integer minimum,
                               Integer maximum, Integer wacn, Integer p25SystemId, String textValue,
                               Integer numericValue, String toneSequence)
    {
    }
}

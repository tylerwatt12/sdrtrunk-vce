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
import io.github.dsheirer.alias.action.AliasAction;
import io.github.dsheirer.alias.action.AliasActionType;
import io.github.dsheirer.alias.action.RecurringAction;
import io.github.dsheirer.alias.action.beep.BeepAction;
import io.github.dsheirer.alias.action.clip.ClipAction;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.legacy.fleetsync.FleetsyncID;
import io.github.dsheirer.alias.id.legacy.mdc.MDC1200ID;
import io.github.dsheirer.alias.id.legacy.mobileID.Min;
import io.github.dsheirer.alias.id.legacy.mpt1327.MPT1327ID;
import io.github.dsheirer.alias.id.legacy.nonrecordable.NonRecordable;
import io.github.dsheirer.alias.id.legacy.siteID.SiteID;
import io.github.dsheirer.alias.id.legacy.talkgroup.LegacyTalkgroupID;
import io.github.dsheirer.alias.id.legacy.uniqueID.UniqueID;
import io.github.dsheirer.alias.id.lojack.LoJackFunctionAndID;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.record.Record;
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
import io.github.dsheirer.module.decode.lj1200.LJ1200Message;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite persistence for aliases in the global SDRTrunk database.
 */
public class AliasDatabaseStore
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasDatabaseStore.class);
    private static final String ALIAS_INITIALIZED_KEY = "alias_config_initialized";
    private static final String TRUE = "true";
    private final Path mDatabasePath;

    public AliasDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public boolean hasAliases() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM alias"))
        {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    public boolean isInitialized() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return TRUE.equalsIgnoreCase(getMetadata(connection, ALIAS_INITIALIZED_KEY)) || hasAliases(connection);
        }
    }

    public List<Alias> loadAliases() throws IOException, SQLException
    {
        Map<Long,Alias> aliases = new LinkedHashMap<>();
        Map<Long,AliasOptions> aliasOptions = new LinkedHashMap<>();

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath);
            PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, alias_list_name, group_name, color, icon_name, stream_as_talkgroup,
                       record_enabled, non_recordable, priority
                FROM alias
                ORDER BY sort_order, id
                """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long aliasId = resultSet.getLong("id");
                Alias alias = new Alias(resultSet.getString("name"));
                alias.setAliasListName(resultSet.getString("alias_list_name"));
                alias.setGroup(resultSet.getString("group_name"));
                alias.setColor(resultSet.getInt("color"));
                alias.setIconName(resultSet.getString("icon_name"));

                Integer streamTalkgroup = getInteger(resultSet, "stream_as_talkgroup");
                if(streamTalkgroup != null)
                {
                    alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(streamTalkgroup));
                }

                aliases.put(aliasId, alias);
                aliasOptions.put(aliasId, new AliasOptions(resultSet.getInt("record_enabled") != 0,
                    resultSet.getInt("non_recordable") != 0, getInteger(resultSet, "priority")));
            }

            Map<Long,List<AliasID>> identifiers = loadIdentifiers(connection);
            Map<Long,List<AliasAction>> actions = loadActions(connection);

            for(Map.Entry<Long,Alias> entry: aliases.entrySet())
            {
                List<AliasID> aliasIdentifiers = new ArrayList<>(identifiers.getOrDefault(entry.getKey(),
                    Collections.emptyList()));
                AliasOptions options = aliasOptions.get(entry.getKey());

                if(options != null)
                {
                    if(options.recordEnabled())
                    {
                        aliasIdentifiers.add(new Record());
                    }

                    if(options.nonRecordable())
                    {
                        aliasIdentifiers.add(new NonRecordable());
                    }

                    if(options.priority() != null)
                    {
                        aliasIdentifiers.add(new Priority(options.priority()));
                    }
                }

                entry.getValue().setAliasIdentifiers(aliasIdentifiers);
                entry.getValue().setAliasActions(actions.getOrDefault(entry.getKey(), Collections.emptyList()));
            }
        }

        return new ArrayList<>(aliases.values());
    }

    public void replaceAliases(List<Alias> aliases) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try
            {
                clearAliases(connection);

                int sortOrder = 0;
                for(Alias alias: aliases)
                {
                    insertAlias(connection, alias, sortOrder++);
                }

                updateMetadata(connection, ALIAS_INITIALIZED_KEY, TRUE);
                connection.commit();
            }
            catch(SQLException e)
            {
                connection.rollback();
                throw e;
            }
            finally
            {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private String getMetadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT value FROM database_metadata WHERE key = ?
            """))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getString("value");
                }
            }
        }

        return null;
    }

    private boolean hasAliases(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM alias"))
        {
            return resultSet.next() && resultSet.getInt(1) > 0;
        }
    }

    private void updateMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void clearAliases(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_action");
            statement.executeUpdate("DELETE FROM alias_broadcast_channel");
            statement.executeUpdate("DELETE FROM alias_talkgroup");
            statement.executeUpdate("DELETE FROM alias_radio");
            statement.executeUpdate("DELETE FROM alias_status");
            statement.executeUpdate("DELETE FROM alias_tone_sequence");
            statement.executeUpdate("DELETE FROM alias_text_identifier");
            statement.executeUpdate("DELETE FROM alias");
        }
    }

    private void insertAlias(Connection connection, Alias alias, int sortOrder) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias (
                sort_order, name, alias_list_name, group_name, color, icon_name, stream_as_talkgroup,
                record_enabled, non_recordable, priority
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS))
        {
            List<AliasID> identifiers = alias.getAliasIdentifiers();
            statement.setInt(1, sortOrder);
            statement.setString(2, alias.getName());
            statement.setString(3, alias.getAliasListName());
            statement.setString(4, alias.getGroup());
            statement.setInt(5, alias.getColor());
            statement.setString(6, alias.getIconName());

            StreamAsTalkgroup streamAsTalkgroup = alias.getStreamTalkgroupAlias();
            setInteger(statement, 7, streamAsTalkgroup != null ? streamAsTalkgroup.getValue() : null);
            statement.setInt(8, hasIdentifier(identifiers, Record.class) ? 1 : 0);
            statement.setInt(9, hasIdentifier(identifiers, NonRecordable.class) ? 1 : 0);
            setInteger(statement, 10, getPriority(identifiers));
            statement.executeUpdate();

            try(ResultSet keys = statement.getGeneratedKeys())
            {
                if(keys.next())
                {
                    long aliasId = keys.getLong(1);
                    insertIdentifiers(connection, aliasId, identifiers);
                    insertActions(connection, aliasId, alias.getAliasActions());
                }
                else
                {
                    throw new SQLException("SQLite did not return an alias id");
                }
            }
        }
    }

    private void insertIdentifiers(Connection connection, long aliasId, List<AliasID> identifiers) throws SQLException
    {
        int sortOrder = 0;
        for(AliasID identifier: identifiers)
        {
            insertIdentifier(connection, aliasId, sortOrder++, identifier);
        }
    }

    private void insertIdentifier(Connection connection, long aliasId, int sortOrder, AliasID identifier)
        throws SQLException
    {
        switch(identifier.getType())
        {
            case BROADCAST_CHANNEL -> insertBroadcastChannel(connection, aliasId, sortOrder,
                ((BroadcastChannel)identifier).getChannelName());
            case TALKGROUP -> {
                Talkgroup talkgroup = (Talkgroup)identifier;
                insertTalkgroup(connection, aliasId, sortOrder, talkgroup.getProtocol(), talkgroup.getValue(),
                    null, null, null, null, false, false);
            }
            case TALKGROUP_RANGE -> {
                TalkgroupRange range = (TalkgroupRange)identifier;
                insertTalkgroup(connection, aliasId, sortOrder, range.getProtocol(), null, range.getMinTalkgroup(),
                    range.getMaxTalkgroup(), null, null, false, true);
            }
            case P25_FULLY_QUALIFIED_TALKGROUP -> {
                P25FullyQualifiedTalkgroup talkgroup = (P25FullyQualifiedTalkgroup)identifier;
                insertTalkgroup(connection, aliasId, sortOrder, talkgroup.getProtocol(), talkgroup.getValue(),
                    null, null, talkgroup.getWacn(), talkgroup.getSystem(), true, false);
            }
            case RADIO_ID -> {
                Radio radio = (Radio)identifier;
                insertRadio(connection, aliasId, sortOrder, radio.getProtocol(), radio.getValue(),
                    null, null, null, null, false, false);
            }
            case RADIO_ID_RANGE -> {
                RadioRange range = (RadioRange)identifier;
                insertRadio(connection, aliasId, sortOrder, range.getProtocol(), null, range.getMinRadio(),
                    range.getMaxRadio(), null, null, false, true);
            }
            case P25_FULLY_QUALIFIED_RADIO_ID -> {
                P25FullyQualifiedRadio radio = (P25FullyQualifiedRadio)identifier;
                insertRadio(connection, aliasId, sortOrder, radio.getProtocol(), radio.getValue(),
                    null, null, radio.getWacn(), radio.getSystem(), true, false);
            }
            case STATUS -> insertStatus(connection, aliasId, sortOrder, "USER", ((UserStatusID)identifier).getStatus());
            case UNIT_STATUS -> insertStatus(connection, aliasId, sortOrder, "UNIT", ((UnitStatusID)identifier).getStatus());
            case TONES -> insertToneSequence(connection, aliasId, sortOrder,
                serializeToneSequence(((TonesID)identifier).getToneSequence()));
            case DCS -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((Dcs)identifier).getDCSCode() != null ? ((Dcs)identifier).getDCSCode().name() : null, null, null);
            case ESN -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((Esn)identifier).getEsn(), null, null);
            case FLEETSYNC -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((FleetsyncID)identifier).getIdent(), null, null);
            case LEGACY_TALKGROUP -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((LegacyTalkgroupID)identifier).getTalkgroup(), null, null);
            case LOJACK -> {
                LoJackFunctionAndID lojack = (LoJackFunctionAndID)identifier;
                insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(), lojack.getID(),
                    lojack.getFunction() != null ? lojack.getFunction().name() : null, null);
            }
            case LTR_NET_UID -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                null, null, ((UniqueID)identifier).getUid());
            case MDC1200 -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((MDC1200ID)identifier).getIdent(), null, null);
            case MIN -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((Min)identifier).getMin(), null, null);
            case MPT1327 -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((MPT1327ID)identifier).getIdent(), null, null);
            case SITE -> insertTextIdentifier(connection, aliasId, sortOrder, identifier.getType(),
                ((SiteID)identifier).getSite(), null, null);
            case RECORD, NON_RECORDABLE, PRIORITY -> {
                // Stored as columns on the alias row.
            }
            default -> mLog.warn("Alias identifier type [{}] is not supported by SQLite alias persistence",
                identifier.getType());
        }
    }

    private void insertBroadcastChannel(Connection connection, long aliasId, int sortOrder, String channelName)
        throws SQLException
    {
        if(channelName == null)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_broadcast_channel (alias_id, sort_order, channel_name)
            VALUES (?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, channelName);
            statement.executeUpdate();
        }
    }

    private void insertTalkgroup(Connection connection, long aliasId, int sortOrder, Protocol protocol, Integer value,
                                 Integer minValue, Integer maxValue, Integer wacn, Integer systemId,
                                 boolean fullyQualified, boolean ranged) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_talkgroup (
                alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id, fully_qualified, ranged
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, protocol(protocol));
            setInteger(statement, 4, value);
            setInteger(statement, 5, minValue);
            setInteger(statement, 6, maxValue);
            setInteger(statement, 7, wacn);
            setInteger(statement, 8, systemId);
            statement.setInt(9, fullyQualified ? 1 : 0);
            statement.setInt(10, ranged ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertRadio(Connection connection, long aliasId, int sortOrder, Protocol protocol, Integer value,
                             Integer minValue, Integer maxValue, Integer wacn, Integer systemId,
                             boolean fullyQualified, boolean ranged) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_radio (
                alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id, fully_qualified, ranged
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, protocol(protocol));
            setInteger(statement, 4, value);
            setInteger(statement, 5, minValue);
            setInteger(statement, 6, maxValue);
            setInteger(statement, 7, wacn);
            setInteger(statement, 8, systemId);
            statement.setInt(9, fullyQualified ? 1 : 0);
            statement.setInt(10, ranged ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertStatus(Connection connection, long aliasId, int sortOrder, String statusKind, int status)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_status (alias_id, sort_order, status_kind, status)
            VALUES (?, ?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, statusKind);
            statement.setInt(4, status);
            statement.executeUpdate();
        }
    }

    private void insertToneSequence(Connection connection, long aliasId, int sortOrder, String toneSequence)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_tone_sequence (alias_id, sort_order, tone_sequence)
            VALUES (?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, toneSequence);
            statement.executeUpdate();
        }
    }

    private void insertTextIdentifier(Connection connection, long aliasId, int sortOrder, AliasIDType type,
                                      String textValue, String textValue2, Integer numericValue) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_text_identifier (
                alias_id, sort_order, identifier_type, text_value, text_value_2, numeric_value
            ) VALUES (?, ?, ?, ?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, type.name());
            statement.setString(4, textValue);
            statement.setString(5, textValue2);
            setInteger(statement, 6, numericValue);
            statement.executeUpdate();
        }
    }

    private void insertActions(Connection connection, long aliasId, List<AliasAction> actions) throws SQLException
    {
        int sortOrder = 0;
        for(AliasAction action: actions)
        {
            insertAction(connection, aliasId, sortOrder++, action);
        }
    }

    private void insertAction(Connection connection, long aliasId, int sortOrder, AliasAction action)
        throws SQLException
    {
        String interval = null;
        Integer period = null;
        String path = null;

        if(action instanceof RecurringAction recurringAction)
        {
            interval = recurringAction.getInterval() != null ? recurringAction.getInterval().name() : null;
            period = recurringAction.getPeriod();
        }

        if(action instanceof ClipAction clipAction)
        {
            path = clipAction.getPath();
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_action (
                alias_id, sort_order, type, interval, period, path, script
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """))
        {
            statement.setLong(1, aliasId);
            statement.setInt(2, sortOrder);
            statement.setString(3, action.getType().name());
            statement.setString(4, interval);
            setInteger(statement, 5, period);
            statement.setString(6, path);
            statement.setNull(7, Types.VARCHAR);
            statement.executeUpdate();
        }
    }

    private Map<Long,List<AliasID>> loadIdentifiers(Connection connection) throws SQLException
    {
        Map<Long,List<LoadedIdentifier>> loaded = new LinkedHashMap<>();
        loadBroadcastChannels(connection, loaded);
        loadTalkgroups(connection, loaded);
        loadRadios(connection, loaded);
        loadStatuses(connection, loaded);
        loadToneSequences(connection, loaded);
        loadTextIdentifiers(connection, loaded);

        Map<Long,List<AliasID>> identifiers = new LinkedHashMap<>();
        for(Map.Entry<Long,List<LoadedIdentifier>> entry: loaded.entrySet())
        {
            entry.getValue().sort((first, second) -> Integer.compare(first.sortOrder(), second.sortOrder()));

            List<AliasID> aliasIdentifiers = new ArrayList<>();
            for(LoadedIdentifier loadedIdentifier: entry.getValue())
            {
                aliasIdentifiers.add(loadedIdentifier.identifier());
            }

            identifiers.put(entry.getKey(), aliasIdentifiers);
        }

        return identifiers;
    }

    private void loadBroadcastChannels(Connection connection, Map<Long,List<LoadedIdentifier>> loaded)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, channel_name
            FROM alias_broadcast_channel
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"),
                    new BroadcastChannel(resultSet.getString("channel_name")));
            }
        }
    }

    private void loadTalkgroups(Connection connection, Map<Long,List<LoadedIdentifier>> loaded) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id, fully_qualified, ranged
            FROM alias_talkgroup
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                Protocol protocol = parseEnum(Protocol.class, resultSet.getString("protocol"), Protocol.UNKNOWN);
                AliasID identifier;

                if(resultSet.getInt("fully_qualified") != 0)
                {
                    identifier = new P25FullyQualifiedTalkgroup(orZero(getInteger(resultSet, "wacn")),
                        orZero(getInteger(resultSet, "system_id")), orZero(getInteger(resultSet, "value")));
                }
                else if(resultSet.getInt("ranged") != 0)
                {
                    identifier = new TalkgroupRange(protocol, orZero(getInteger(resultSet, "min_value")),
                        orZero(getInteger(resultSet, "max_value")));
                }
                else
                {
                    identifier = new Talkgroup(protocol, orZero(getInteger(resultSet, "value")));
                }

                addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"), identifier);
            }
        }
    }

    private void loadRadios(Connection connection, Map<Long,List<LoadedIdentifier>> loaded) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, protocol, value, min_value, max_value, wacn, system_id, fully_qualified, ranged
            FROM alias_radio
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                Protocol protocol = parseEnum(Protocol.class, resultSet.getString("protocol"), Protocol.UNKNOWN);
                AliasID identifier;

                if(resultSet.getInt("fully_qualified") != 0)
                {
                    identifier = new P25FullyQualifiedRadio(orZero(getInteger(resultSet, "wacn")),
                        orZero(getInteger(resultSet, "system_id")), orZero(getInteger(resultSet, "value")));
                }
                else if(resultSet.getInt("ranged") != 0)
                {
                    identifier = new RadioRange(protocol, orZero(getInteger(resultSet, "min_value")),
                        orZero(getInteger(resultSet, "max_value")));
                }
                else
                {
                    identifier = new Radio(protocol, orZero(getInteger(resultSet, "value")));
                }

                addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"), identifier);
            }
        }
    }

    private void loadStatuses(Connection connection, Map<Long,List<LoadedIdentifier>> loaded) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, status_kind, status
            FROM alias_status
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                AliasID identifier;
                if("UNIT".equalsIgnoreCase(resultSet.getString("status_kind")))
                {
                    UnitStatusID statusID = new UnitStatusID();
                    statusID.setStatus(resultSet.getInt("status"));
                    identifier = statusID;
                }
                else
                {
                    UserStatusID statusID = new UserStatusID();
                    statusID.setStatus(resultSet.getInt("status"));
                    identifier = statusID;
                }

                addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"), identifier);
            }
        }
    }

    private void loadToneSequences(Connection connection, Map<Long,List<LoadedIdentifier>> loaded) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, tone_sequence
            FROM alias_tone_sequence
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"),
                    new TonesID(parseToneSequence(resultSet.getString("tone_sequence"))));
            }
        }
    }

    private void loadTextIdentifiers(Connection connection, Map<Long,List<LoadedIdentifier>> loaded) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, sort_order, identifier_type, text_value, text_value_2, numeric_value
            FROM alias_text_identifier
            ORDER BY alias_id, sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                AliasID identifier = toTextIdentifier(resultSet);

                if(identifier != null)
                {
                    addLoadedIdentifier(loaded, resultSet.getLong("alias_id"), resultSet.getInt("sort_order"),
                        identifier);
                }
            }
        }
    }

    private AliasID toTextIdentifier(ResultSet resultSet) throws SQLException
    {
        AliasIDType type = parseEnum(AliasIDType.class, resultSet.getString("identifier_type"), null);
        if(type == null)
        {
            return null;
        }

        String textValue = resultSet.getString("text_value");
        String textValue2 = resultSet.getString("text_value_2");
        Integer numericValue = getInteger(resultSet, "numeric_value");

        AliasID identifier = switch(type)
        {
            case DCS -> {
                Dcs dcs = new Dcs();
                dcs.setDCSCode(parseEnum(DCSCode.class, textValue, null));
                yield dcs;
            }
            case ESN -> {
                Esn esn = new Esn();
                esn.setEsn(textValue);
                yield esn;
            }
            case FLEETSYNC -> {
                FleetsyncID fleetsyncID = new FleetsyncID();
                fleetsyncID.setIdent(textValue);
                yield fleetsyncID;
            }
            case LEGACY_TALKGROUP -> {
                LegacyTalkgroupID talkgroupID = new LegacyTalkgroupID();
                talkgroupID.setTalkgroup(textValue);
                yield talkgroupID;
            }
            case LOJACK -> {
                LoJackFunctionAndID lojack = new LoJackFunctionAndID();
                lojack.setID(textValue);
                lojack.setFunction(parseEnum(LJ1200Message.Function.class, textValue2,
                    LJ1200Message.Function.F0_UNKNOWN));
                yield lojack;
            }
            case LTR_NET_UID -> {
                UniqueID uniqueID = new UniqueID();
                uniqueID.setUid(orZero(numericValue));
                yield uniqueID;
            }
            case MDC1200 -> {
                MDC1200ID mdc1200ID = new MDC1200ID();
                mdc1200ID.setIdent(textValue);
                yield mdc1200ID;
            }
            case MIN -> {
                Min min = new Min();
                min.setMin(textValue);
                yield min;
            }
            case MPT1327 -> {
                MPT1327ID mpt1327ID = new MPT1327ID();
                mpt1327ID.setIdent(textValue);
                yield mpt1327ID;
            }
            case SITE -> {
                SiteID siteID = new SiteID();
                siteID.setSite(textValue);
                yield siteID;
            }
            default -> null;
        };

        if(identifier != null)
        {
            identifier.updateValueProperty();
        }

        return identifier;
    }

    private void addLoadedIdentifier(Map<Long,List<LoadedIdentifier>> loaded, long aliasId, int sortOrder,
                                     AliasID identifier)
    {
        identifier.updateValueProperty();
        loaded.computeIfAbsent(aliasId, key -> new ArrayList<>()).add(new LoadedIdentifier(sortOrder, identifier));
    }

    private Map<Long,List<AliasAction>> loadActions(Connection connection) throws SQLException
    {
        Map<Long,List<AliasAction>> actions = new LinkedHashMap<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias_id, type, interval, period, path, script
            FROM alias_action
            ORDER BY alias_id, sort_order, id
            """))
        {
            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    long aliasId = resultSet.getLong("alias_id");
                    AliasAction action = toAliasAction(resultSet);

                    if(action != null)
                    {
                        actions.computeIfAbsent(aliasId, key -> new ArrayList<>()).add(action);
                    }
                }
            }
        }

        return actions;
    }

    private AliasAction toAliasAction(ResultSet resultSet) throws SQLException
    {
        AliasActionType type = parseEnum(AliasActionType.class, resultSet.getString("type"), null);

        if(type == null)
        {
            return null;
        }

        AliasAction action = switch(type)
        {
            case BEEP -> new BeepAction();
            case CLIP -> {
                ClipAction clipAction = new ClipAction();
                clipAction.setPath(resultSet.getString("path"));
                yield clipAction;
            }
        };

        if(action instanceof RecurringAction recurringAction)
        {
            RecurringAction.Interval interval = parseEnum(RecurringAction.Interval.class,
                resultSet.getString("interval"), RecurringAction.Interval.ONCE);
            recurringAction.setInterval(interval);

            Integer period = getInteger(resultSet, "period");
            if(period != null)
            {
                recurringAction.setPeriod(period);
            }
        }

        action.updateValueProperty();
        return action;
    }

    private String serializeToneSequence(ToneSequence toneSequence)
    {
        if(toneSequence == null || !toneSequence.hasTones())
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for(Tone tone: toneSequence.getTones())
        {
            if(sb.length() > 0)
            {
                sb.append(',');
            }

            sb.append(tone.getAmbeTone().name());
            sb.append(':');
            sb.append(tone.getDuration());
        }

        return sb.toString();
    }

    private ToneSequence parseToneSequence(String value)
    {
        ToneSequence toneSequence = new ToneSequence();

        if(value == null || value.isBlank())
        {
            return toneSequence;
        }

        for(String encodedTone: value.split(","))
        {
            String[] parts = encodedTone.split(":", 2);

            if(parts.length == 2)
            {
                AmbeTone tone = parseEnum(AmbeTone.class, parts[0], AmbeTone.INVALID);

                try
                {
                    toneSequence.addTone(new Tone(tone, Integer.parseInt(parts[1])));
                }
                catch(NumberFormatException e)
                {
                    mLog.warn("Skipping malformed tone duration [{}]", encodedTone);
                }
            }
        }

        return toneSequence;
    }

    private static String protocol(Protocol protocol)
    {
        return protocol != null ? protocol.name() : Protocol.UNKNOWN.name();
    }

    private static boolean hasIdentifier(List<AliasID> identifiers, Class<? extends AliasID> type)
    {
        return identifiers.stream().anyMatch(type::isInstance);
    }

    private static Integer getPriority(List<AliasID> identifiers)
    {
        return identifiers.stream()
            .filter(Priority.class::isInstance)
            .map(Priority.class::cast)
            .map(Priority::getPriority)
            .findFirst()
            .orElse(null);
    }

    private static int orZero(Integer value)
    {
        return value != null ? value : 0;
    }

    private static Integer getInteger(ResultSet resultSet, String column) throws SQLException
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

    private static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, T fallback)
    {
        if(value == null || value.isBlank())
        {
            return fallback;
        }

        try
        {
            return Enum.valueOf(enumType, value);
        }
        catch(IllegalArgumentException e)
        {
            mLog.warn("Unknown {} value [{}]", enumType.getSimpleName(), value);
            return fallback;
        }
    }

    private record AliasOptions(boolean recordEnabled, boolean nonRecordable, Integer priority)
    {
    }

    private record LoadedIdentifier(int sortOrder, AliasID identifier)
    {
    }
}

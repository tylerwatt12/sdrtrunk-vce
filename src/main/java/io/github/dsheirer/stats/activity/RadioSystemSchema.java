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

import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.protocol.Protocol;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compact protocol-neutral directory projection for trunked P25, DMR and NXDN identities.
 *
 * <p>All methods are called by the single statistics writer while its batch transaction is open.  No decoder thread
 * accesses SQLite and no per-call rows are retained here.</p>
 */
final class RadioSystemSchema
{
    private static final int IDENTITY_DOMAIN_STANDARD = 0;
    private static final int IDENTITY_DOMAIN_NXDN_TYPE_C = 1;
    private static final int IDENTITY_DOMAIN_NXDN_TYPE_D = 2;
    private static final int DMR_VARIANT_TIER_III = 1;
    private static final int DMR_MODEL_TINY = 1;
    private static final int DMR_MODEL_SMALL = 2;
    private static final int DMR_MODEL_LARGE = 3;
    private static final int DMR_MODEL_HUGE = 4;
    private static final int NXDN_VARIANT_TYPE_C = 1;
    private static final int NXDN_LOCATION_GLOBAL = 1;
    private static final int NXDN_LOCATION_REGIONAL = 2;
    private static final int NXDN_LOCATION_LOCAL = 3;
    static final int MAX_IDENTITIES_PER_SYSTEM = 100_000;
    static final int MAX_RELATIONSHIPS_PER_SYSTEM = 500_000;
    static final int MAX_WUID_ASSIGNMENT_OBSERVATIONS_PER_SYSTEM = 500_000;
    static final int MAX_P25_WORKING_RADIO_ID = RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID;
    static final int MAX_TALKER_ALIAS_CHARACTERS = 160;

    private static final List<ReceiverActivityRecords.Action> ACTIONS = ReceiverActivityCodes.actionCodes().stream()
        .filter(action -> action != ReceiverActivityRecords.Action.CALL)
        .toList();
    static final List<String> ACTION_COUNT_COLUMNS = ACTIONS.stream()
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count")
        .toList();
    private static final String ACTION_COUNT_DEFINITIONS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> column + " INTEGER NOT NULL DEFAULT 0 CHECK(typeof(" + column +
            ") = 'integer' AND " + column + " >= 0)")
        .collect(Collectors.joining(",\n                    "));
    private static final String ACTION_INSERT_COLUMNS = String.join(", ", ACTION_COUNT_COLUMNS);
    private static final String ACTION_INSERT_PLACEHOLDERS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> "?")
        .collect(Collectors.joining(", "));
    private static final String IDENTITY_DELTA_UPDATE_SQL = """
        UPDATE radio_system_identity_summary
        SET first_seen_ms=min(first_seen_ms, ?),
            last_seen_ms=max(last_seen_ms, ?),
            %s
        WHERE id=?
        """.formatted(ACTION_COUNT_COLUMNS.stream()
        .map(column -> column + "=" + column + "+?")
        .collect(Collectors.joining(",\n            ")));

    private RadioSystemSchema()
    {
    }

    /** Transaction-local coalescing for consecutive receiver activity observations. */
    static final class ActivityBatch
    {
        private final Map<String,SystemDelta> mSystems = new LinkedHashMap<>();
        private final Map<IdentityKey,IdentityDelta> mIdentities = new LinkedHashMap<>();

        private RadioSystem observeSystem(String systemKey, long observedAt, boolean updateFirstSeen)
        {
            SystemDelta delta = mSystems.get(systemKey);
            return delta != null ? delta.observe(observedAt, updateFirstSeen) : null;
        }

        private void rememberSystem(RadioSystem radioSystem)
        {
            if(radioSystem != null)
            {
                mSystems.put(radioSystem.systemKey(), new SystemDelta(radioSystem));
            }
        }

        private IdentityDelta identity(int radioSystemId, Identity identity)
        {
            return mIdentities.get(IdentityKey.from(radioSystemId, identity));
        }

        private void rememberIdentity(int radioSystemId, Identity identity, int summaryId)
        {
            mIdentities.put(IdentityKey.from(radioSystemId, identity), new IdentityDelta(summaryId));
        }

        void flush(Connection connection) throws SQLException
        {
            flushSystems(connection);
            flushIdentities(connection);
        }

        private void flushSystems(Connection connection) throws SQLException
        {
            if(mSystems.isEmpty())
            {
                return;
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE radio_system
                SET first_seen_ms=min(first_seen_ms, coalesce(?, first_seen_ms)),
                    last_seen_ms=max(last_seen_ms, ?)
                WHERE id=?
                """))
            {
                for(SystemDelta delta: mSystems.values())
                {
                    if(delta.mLastSeen > 0)
                    {
                        setLong(statement, 1, delta.mFirstSeen);
                        statement.setLong(2, delta.mLastSeen);
                        statement.setInt(3, delta.mRadioSystem.radioSystemId());
                        statement.addBatch();
                    }
                }
                statement.executeBatch();
            }
            mSystems.clear();
        }

        private void flushIdentities(Connection connection) throws SQLException
        {
            if(mIdentities.isEmpty())
            {
                return;
            }

            try(PreparedStatement statement = connection.prepareStatement(IDENTITY_DELTA_UPDATE_SQL))
            {
                for(IdentityDelta delta: mIdentities.values())
                {
                    if(delta.mLastSeen <= 0)
                    {
                        continue;
                    }

                    int index = 1;
                    statement.setLong(index++, delta.mFirstSeen);
                    statement.setLong(index++, delta.mLastSeen);
                    for(int count: delta.mActionCounts)
                    {
                        statement.setInt(index++, count);
                    }
                    statement.setInt(index, delta.mSummaryId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            mIdentities.clear();
        }
    }

    static void create(Statement statement) throws SQLException
    {
        create(statement, true);
    }

    /** Creates the predecessor radio-system objects for exact format-15-through-31 fixtures. */
    static void createFormat31(Statement statement) throws SQLException
    {
        create(statement, false);
    }

    private static void create(Statement statement, boolean currentSchema) throws SQLException
    {
        for(SqliteSchemaValidator.Definition definition:
            currentSchema ? definitions() : format31Definitions())
        {
            statement.executeUpdate(definition.sql());
        }

        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_radio_system_identity_last_seen
            ON radio_system_identity_summary(
                radio_system_id, identity_kind_code, last_seen_ms DESC,
                home_wacn, home_system_id, identity_id
            )
            """);
        if(currentSchema)
        {
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_system_time
                ON p25_wuid_assignment_observation_summary(radio_system_id, last_observed_ms DESC, working_id,
                    p25_subscriber_identity_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_subscriber
                ON p25_wuid_assignment_observation_summary(p25_subscriber_identity_id, last_observed_ms DESC,
                    radio_system_id, working_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_retention
                ON p25_wuid_assignment_observation_summary(last_observed_ms, radio_system_id, working_id,
                    p25_subscriber_identity_id)
                """);
        }
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_radio_system_identity_retention
            ON radio_system_identity_summary(
                last_seen_ms, radio_system_id, identity_kind_code,
                home_wacn, home_system_id, identity_id
            )
            """);
        if(currentSchema)
        {
            createP25SubscriberIdentitySummaryIndex(statement);
        }
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_group_reverse
            ON trunked_radio_group_summary(
                radio_system_id, group_identity_id, group_kind_code, last_seen_ms DESC, radio_identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_group_retention
            ON trunked_radio_group_summary(
                last_seen_ms, radio_system_id, radio_identity_id, group_identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_talkgroup
            ON trunked_radio_affiliation(
                radio_system_id, talkgroup_identity_id, confirmed_at_ms DESC, channel_id, radio_identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_retention
            ON trunked_radio_affiliation(confirmed_at_ms, radio_system_id, radio_identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_channel_presence_channel
            ON trunked_radio_channel_presence(
                channel_id, confirmed_at_ms DESC, radio_system_id, radio_identity_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_channel_presence_retention
            ON trunked_radio_channel_presence(confirmed_at_ms, radio_system_id, radio_identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_channel_presence_clear_retention
            ON trunked_radio_channel_presence_clear(cleared_at_ms, radio_system_id, radio_identity_id, channel_id)
            """);
    }

    static List<SqliteSchemaValidator.Definition> definitions()
    {
        return List.of(
            new SqliteSchemaValidator.Definition("table", "radio_system", radioSystemSql()),
            new SqliteSchemaValidator.Definition("table", "p25_subscriber_identity", p25SubscriberIdentitySql()),
            new SqliteSchemaValidator.Definition("table", "p25_wuid_assignment_observation_summary",
                p25WuidAssignmentObservationSummarySql()),
            new SqliteSchemaValidator.Definition("table", "radio_system_identity_summary", identitySummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_group_summary",
                radioGroupSummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_affiliation", radioAffiliationSql(true)),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence",
                radioChannelPresenceSql(true)),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence_clear",
                radioChannelPresenceClearSql(true))
        );
    }

    private static List<SqliteSchemaValidator.Definition> format31Definitions()
    {
        return List.of(
            new SqliteSchemaValidator.Definition("table", "radio_system", radioSystemSql()),
            new SqliteSchemaValidator.Definition("table", "radio_system_identity_summary",
                format31IdentitySummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_group_summary",
                radioGroupSummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_affiliation", radioAffiliationSql(false)),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence",
                radioChannelPresenceSql(false)),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence_clear",
                radioChannelPresenceClearSql(false))
        );
    }

    /** Creates the format-32 canonical P25 identity and WUID observation tables for the adjacent staged migrator. */
    static void createP25SubscriberIdentityTables(Statement statement) throws SQLException
    {
        statement.executeUpdate(p25SubscriberIdentitySql());
        statement.executeUpdate(p25WuidAssignmentObservationSummarySql());
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_system_time
            ON p25_wuid_assignment_observation_summary(radio_system_id, last_observed_ms DESC, working_id,
                p25_subscriber_identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_subscriber
            ON p25_wuid_assignment_observation_summary(p25_subscriber_identity_id, last_observed_ms DESC,
                radio_system_id, working_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_wuid_assignment_observation_retention
            ON p25_wuid_assignment_observation_summary(last_observed_ms, radio_system_id, working_id,
                p25_subscriber_identity_id)
            """);
    }

    static void createP25SubscriberIdentitySummaryIndex(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_radio_system_identity_p25_subscriber
            ON radio_system_identity_summary(p25_subscriber_identity_id, radio_system_id, id)
            WHERE p25_subscriber_identity_id IS NOT NULL
            """);
    }

    private static String radioSystemSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS radio_system (
                id INTEGER PRIMARY KEY AUTOINCREMENT
                    CHECK(typeof(id) = 'integer' AND id > 0),
                system_key TEXT NOT NULL UNIQUE
                    CHECK(typeof(system_key) = 'text' AND length(trim(system_key)) > 0),
                configuration_id TEXT UNIQUE REFERENCES configuration_channel(configuration_id) ON DELETE CASCADE
                    CHECK(configuration_id IS NULL OR (
                        typeof(configuration_id) = 'text'
                        AND length(configuration_id) = 36 AND configuration_id = lower(configuration_id)
                        AND substr(configuration_id, 9, 1) = '-'
                        AND substr(configuration_id, 14, 1) = '-'
                        AND substr(configuration_id, 19, 1) = '-'
                        AND substr(configuration_id, 24, 1) = '-'
                        AND length(replace(configuration_id, '-', '')) = 32
                        AND replace(configuration_id, '-', '') NOT GLOB '*[^0-9a-f]*'
                    )),
                protocol_code INTEGER NOT NULL
                    CHECK(typeof(protocol_code) = 'integer' AND protocol_code IN (1, 3, 4)),
                address_domain_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(address_domain_code) = 'integer' AND address_domain_code IN (0, 1, 2)),
                p25_wacn INTEGER CHECK(p25_wacn IS NULL OR
                    (typeof(p25_wacn) = 'integer' AND p25_wacn BETWEEN 0 AND 1048575)),
                p25_system_id INTEGER CHECK(p25_system_id IS NULL OR
                    (typeof(p25_system_id) = 'integer' AND p25_system_id BETWEEN 0 AND 4095)),
                dmr_model_code INTEGER CHECK(dmr_model_code IS NULL OR
                    (typeof(dmr_model_code) = 'integer' AND dmr_model_code BETWEEN 1 AND 4)),
                dmr_network_id INTEGER CHECK(dmr_network_id IS NULL OR
                    (typeof(dmr_network_id) = 'integer' AND dmr_network_id >= 0)),
                nxdn_location_category_code INTEGER CHECK(nxdn_location_category_code IS NULL OR
                    (typeof(nxdn_location_category_code) = 'integer'
                        AND nxdn_location_category_code BETWEEN 1 AND 3)),
                nxdn_system_id INTEGER CHECK(nxdn_system_id IS NULL OR
                    (typeof(nxdn_system_id) = 'integer' AND nxdn_system_id >= 1)),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                UNIQUE(p25_wacn, p25_system_id),
                UNIQUE(dmr_model_code, dmr_network_id),
                UNIQUE(nxdn_location_category_code, nxdn_system_id),
                CHECK(
                    (protocol_code = 1 AND address_domain_code = 0
                        AND configuration_id IS NULL
                        AND p25_wacn IS NOT NULL AND p25_system_id IS NOT NULL
                        AND dmr_model_code IS NULL AND dmr_network_id IS NULL
                        AND nxdn_location_category_code IS NULL AND nxdn_system_id IS NULL
                        AND system_key = printf('p25:%05x:%03x', p25_wacn, p25_system_id))
                    OR
                    (protocol_code = 3 AND address_domain_code = 0
                        AND configuration_id IS NOT NULL
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND dmr_model_code IS NULL AND dmr_network_id IS NULL
                        AND nxdn_location_category_code IS NULL AND nxdn_system_id IS NULL
                        AND system_key = 'dmr:channel:' || configuration_id
                        AND length(system_key) = 48
                        AND substr(system_key, 1, 12) = 'dmr:channel:'
                        AND substr(system_key, 21, 1) = '-'
                        AND substr(system_key, 26, 1) = '-'
                        AND substr(system_key, 31, 1) = '-'
                        AND substr(system_key, 36, 1) = '-'
                        AND substr(system_key, 13) = lower(substr(system_key, 13))
                        AND length(replace(substr(system_key, 13), '-', '')) = 32
                        AND replace(substr(system_key, 13), '-', '') NOT GLOB '*[^0-9a-f]*')
                    OR
                    (protocol_code = 3 AND address_domain_code = 0
                        AND configuration_id IS NULL
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND dmr_model_code IS NOT NULL AND dmr_network_id IS NOT NULL
                        AND dmr_model_code BETWEEN 1 AND 4
                        AND dmr_network_id BETWEEN 0 AND CASE dmr_model_code
                            WHEN 1 THEN 511 WHEN 2 THEN 127 WHEN 3 THEN 15 WHEN 4 THEN 3 END
                        AND nxdn_location_category_code IS NULL AND nxdn_system_id IS NULL
                        AND system_key = 'dmr:tier3:' || CASE dmr_model_code
                            WHEN 1 THEN 'tiny' WHEN 2 THEN 'small'
                            WHEN 3 THEN 'large' WHEN 4 THEN 'huge' END || ':' || dmr_network_id)
                    OR
                    (protocol_code = 4 AND address_domain_code IN (1, 2)
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND configuration_id IS NOT NULL
                        AND dmr_model_code IS NULL AND dmr_network_id IS NULL
                        AND nxdn_location_category_code IS NULL AND nxdn_system_id IS NULL
                        AND system_key = CASE address_domain_code
                            WHEN 1 THEN 'nxdn-c:channel:' || configuration_id
                            WHEN 2 THEN 'nxdn-d:channel:' || configuration_id
                        END
                        AND length(system_key) = 51
                        AND substr(system_key, 1, 15) IN ('nxdn-c:channel:', 'nxdn-d:channel:')
                        AND substr(system_key, 24, 1) = '-'
                        AND substr(system_key, 29, 1) = '-'
                        AND substr(system_key, 34, 1) = '-'
                        AND substr(system_key, 39, 1) = '-'
                        AND substr(system_key, 16) = lower(substr(system_key, 16))
                        AND length(replace(substr(system_key, 16), '-', '')) = 32
                        AND replace(substr(system_key, 16), '-', '') NOT GLOB '*[^0-9a-f]*')
                    OR
                    (protocol_code = 4 AND address_domain_code = 1
                        AND configuration_id IS NULL
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND dmr_model_code IS NULL AND dmr_network_id IS NULL
                        AND nxdn_location_category_code IS NOT NULL AND nxdn_system_id IS NOT NULL
                        AND nxdn_location_category_code BETWEEN 1 AND 3
                        AND nxdn_system_id BETWEEN 1 AND CASE nxdn_location_category_code
                            WHEN 1 THEN 1022 WHEN 2 THEN 16382 WHEN 3 THEN 131070 END
                        AND system_key = 'nxdn-c:' || CASE nxdn_location_category_code
                            WHEN 1 THEN 'global' WHEN 2 THEN 'regional' WHEN 3 THEN 'local' END ||
                            ':' || nxdn_system_id)
                )
            )
            """;
    }

    private static String identitySummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS radio_system_identity_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT
                    CHECK(typeof(id) = 'integer' AND id > 0),
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                identity_kind_code INTEGER NOT NULL
                    CHECK(typeof(identity_kind_code) = 'integer' AND identity_kind_code IN (1, 2, 3)),
                home_wacn INTEGER NOT NULL DEFAULT -1
                    CHECK(typeof(home_wacn) = 'integer' AND (home_wacn = -1 OR home_wacn BETWEEN 0 AND 1048575)),
                home_system_id INTEGER NOT NULL DEFAULT -1
                    CHECK(typeof(home_system_id) = 'integer' AND (home_system_id = -1 OR home_system_id BETWEEN 0 AND 4095)),
                identity_id INTEGER NOT NULL CHECK(typeof(identity_id) = 'integer' AND identity_id > 0),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                %s,
                logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(logical_call_count) = 'integer' AND logical_call_count >= 0),
                source_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(source_logical_call_count) = 'integer' AND source_logical_call_count >= 0),
                target_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(target_logical_call_count) = 'integer' AND target_logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(encrypted_logical_call_count) = 'integer' AND encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(recorded_output_count) = 'integer' AND recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(streamed_output_count) = 'integer' AND streamed_output_count >= 0),
                last_encryption_algorithm_id INTEGER CHECK(last_encryption_algorithm_id IS NULL OR
                    (typeof(last_encryption_algorithm_id) = 'integer' AND last_encryption_algorithm_id >= 0)),
                last_encryption_key_id INTEGER CHECK(last_encryption_key_id IS NULL OR
                    (typeof(last_encryption_key_id) = 'integer' AND last_encryption_key_id >= 0)),
                last_talker_alias TEXT CHECK(last_talker_alias IS NULL OR
                    (typeof(last_talker_alias) = 'text'
                        AND length(trim(last_talker_alias)) BETWEEN 1 AND %d)),
                last_talker_alias_seen_ms INTEGER CHECK(last_talker_alias_seen_ms IS NULL OR
                    (typeof(last_talker_alias_seen_ms) = 'integer' AND last_talker_alias_seen_ms > 0)),
                p25_subscriber_identity_id INTEGER REFERENCES p25_subscriber_identity(id) ON DELETE RESTRICT
                    CHECK(p25_subscriber_identity_id IS NULL OR
                        (typeof(p25_subscriber_identity_id) = 'integer' AND p25_subscriber_identity_id > 0
                            AND identity_kind_code = 2 AND home_wacn >= 0 AND home_system_id >= 0)),
                UNIQUE(radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id),
                UNIQUE(id, radio_system_id),
                UNIQUE(id, radio_system_id, identity_kind_code),
                CHECK((home_wacn = -1 AND home_system_id = -1) OR
                    (home_wacn BETWEEN 0 AND 1048575 AND home_system_id BETWEEN 0 AND 4095)),
                CHECK((identity_kind_code = 1 AND identity_id BETWEEN 1 AND 16777215)
                    OR (identity_kind_code = 3 AND identity_id BETWEEN 1 AND 65534)
                    OR (identity_kind_code = 2 AND identity_id BETWEEN 1 AND 16777215)),
                CHECK((last_talker_alias IS NULL AND last_talker_alias_seen_ms IS NULL) OR
                    (last_talker_alias IS NOT NULL AND length(trim(last_talker_alias)) > 0
                        AND last_talker_alias_seen_ms IS NOT NULL))
            )
            """.formatted(ACTION_COUNT_DEFINITIONS, MAX_TALKER_ALIAS_CHARACTERS);
    }

    private static String format31IdentitySummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS radio_system_identity_summary (
                id INTEGER PRIMARY KEY AUTOINCREMENT
                    CHECK(typeof(id) = 'integer' AND id > 0),
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                identity_kind_code INTEGER NOT NULL
                    CHECK(typeof(identity_kind_code) = 'integer' AND identity_kind_code IN (1, 2, 3)),
                home_wacn INTEGER NOT NULL DEFAULT -1
                    CHECK(typeof(home_wacn) = 'integer' AND (home_wacn = -1 OR home_wacn BETWEEN 0 AND 1048575)),
                home_system_id INTEGER NOT NULL DEFAULT -1
                    CHECK(typeof(home_system_id) = 'integer' AND (home_system_id = -1 OR home_system_id BETWEEN 0 AND 4095)),
                identity_id INTEGER NOT NULL CHECK(typeof(identity_id) = 'integer' AND identity_id > 0),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                %s,
                logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(logical_call_count) = 'integer' AND logical_call_count >= 0),
                source_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(source_logical_call_count) = 'integer' AND source_logical_call_count >= 0),
                target_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(target_logical_call_count) = 'integer' AND target_logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(encrypted_logical_call_count) = 'integer' AND encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(recorded_output_count) = 'integer' AND recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(streamed_output_count) = 'integer' AND streamed_output_count >= 0),
                last_encryption_algorithm_id INTEGER CHECK(last_encryption_algorithm_id IS NULL OR
                    (typeof(last_encryption_algorithm_id) = 'integer' AND last_encryption_algorithm_id >= 0)),
                last_encryption_key_id INTEGER CHECK(last_encryption_key_id IS NULL OR
                    (typeof(last_encryption_key_id) = 'integer' AND last_encryption_key_id >= 0)),
                last_talker_alias TEXT CHECK(last_talker_alias IS NULL OR
                    (typeof(last_talker_alias) = 'text'
                        AND length(trim(last_talker_alias)) BETWEEN 1 AND %d)),
                last_talker_alias_seen_ms INTEGER CHECK(last_talker_alias_seen_ms IS NULL OR
                    (typeof(last_talker_alias_seen_ms) = 'integer' AND last_talker_alias_seen_ms > 0)),
                UNIQUE(radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id),
                UNIQUE(id, radio_system_id),
                UNIQUE(id, radio_system_id, identity_kind_code),
                CHECK((home_wacn = -1 AND home_system_id = -1) OR
                    (home_wacn BETWEEN 0 AND 1048575 AND home_system_id BETWEEN 0 AND 4095)),
                CHECK((identity_kind_code = 1 AND identity_id BETWEEN 1 AND 16777215)
                    OR (identity_kind_code = 3 AND identity_id BETWEEN 1 AND 65534)
                    OR (identity_kind_code = 2 AND identity_id BETWEEN 1 AND 16777215)),
                CHECK((last_talker_alias IS NULL AND last_talker_alias_seen_ms IS NULL) OR
                    (last_talker_alias IS NOT NULL AND length(trim(last_talker_alias)) > 0
                        AND last_talker_alias_seen_ms IS NOT NULL))
            )
            """.formatted(ACTION_COUNT_DEFINITIONS, MAX_TALKER_ALIAS_CHARACTERS);
    }

    private static String p25SubscriberIdentitySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS p25_subscriber_identity (
                id INTEGER PRIMARY KEY CHECK(typeof(id) = 'integer' AND id > 0),
                home_wacn INTEGER NOT NULL
                    CHECK(typeof(home_wacn) = 'integer' AND home_wacn BETWEEN 0 AND 1048575),
                home_system_id INTEGER NOT NULL
                    CHECK(typeof(home_system_id) = 'integer' AND home_system_id BETWEEN 0 AND 4095),
                subscriber_id INTEGER NOT NULL
                    CHECK(typeof(subscriber_id) = 'integer' AND subscriber_id BETWEEN 1 AND 16777212),
                UNIQUE(home_wacn, home_system_id, subscriber_id)
            )
            """;
    }

    private static String p25WuidAssignmentObservationSummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS p25_wuid_assignment_observation_summary (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                working_id INTEGER NOT NULL
                    CHECK(typeof(working_id) = 'integer' AND working_id BETWEEN 1 AND 16777212),
                p25_subscriber_identity_id INTEGER NOT NULL
                    REFERENCES p25_subscriber_identity(id) ON DELETE RESTRICT
                    CHECK(typeof(p25_subscriber_identity_id) = 'integer' AND p25_subscriber_identity_id > 0),
                first_observed_ms INTEGER NOT NULL
                    CHECK(typeof(first_observed_ms) = 'integer' AND first_observed_ms > 0),
                last_observed_ms INTEGER NOT NULL
                    CHECK(typeof(last_observed_ms) = 'integer' AND last_observed_ms >= first_observed_ms),
                last_registration_ms INTEGER CHECK(last_registration_ms IS NULL OR
                    (typeof(last_registration_ms) = 'integer'
                        AND last_registration_ms BETWEEN first_observed_ms AND last_observed_ms)),
                last_affiliation_ms INTEGER CHECK(last_affiliation_ms IS NULL OR
                    (typeof(last_affiliation_ms) = 'integer'
                        AND last_affiliation_ms BETWEEN first_observed_ms AND last_observed_ms)),
                registration_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(registration_count) = 'integer' AND registration_count >= 0),
                affiliation_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(affiliation_count) = 'integer' AND affiliation_count >= 0),
                last_evidence_code INTEGER NOT NULL
                    CHECK(typeof(last_evidence_code) = 'integer' AND last_evidence_code IN (1, 2)),
                last_channel_id INTEGER REFERENCES receiver_channel(id) ON DELETE SET NULL
                    CHECK(last_channel_id IS NULL OR
                        (typeof(last_channel_id) = 'integer' AND last_channel_id > 0)),
                PRIMARY KEY(radio_system_id, working_id, p25_subscriber_identity_id),
                CHECK(registration_count + affiliation_count > 0),
                CHECK((registration_count = 0) = (last_registration_ms IS NULL)),
                CHECK((affiliation_count = 0) = (last_affiliation_ms IS NULL))
            ) WITHOUT ROWID
            """;
    }

    private static String radioGroupSummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_group_summary (
                radio_system_id INTEGER NOT NULL
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                radio_kind_code INTEGER NOT NULL DEFAULT 2
                    CHECK(typeof(radio_kind_code) = 'integer' AND radio_kind_code = 2),
                radio_identity_id INTEGER NOT NULL
                    CHECK(typeof(radio_identity_id) = 'integer' AND radio_identity_id > 0),
                group_identity_id INTEGER NOT NULL
                    CHECK(typeof(group_identity_id) = 'integer' AND group_identity_id > 0),
                group_kind_code INTEGER NOT NULL
                    CHECK(typeof(group_kind_code) = 'integer' AND group_kind_code IN (1, 3)),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                %s,
                logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(logical_call_count) = 'integer' AND logical_call_count >= 0),
                encrypted_logical_call_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(encrypted_logical_call_count) = 'integer' AND encrypted_logical_call_count >= 0),
                recorded_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(recorded_output_count) = 'integer' AND recorded_output_count >= 0),
                streamed_output_count INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(streamed_output_count) = 'integer' AND streamed_output_count >= 0),
                last_encryption_algorithm_id INTEGER CHECK(last_encryption_algorithm_id IS NULL OR
                    (typeof(last_encryption_algorithm_id) = 'integer' AND last_encryption_algorithm_id >= 0)),
                last_encryption_key_id INTEGER CHECK(last_encryption_key_id IS NULL OR
                    (typeof(last_encryption_key_id) = 'integer' AND last_encryption_key_id >= 0)),
                PRIMARY KEY(radio_system_id, radio_identity_id, group_identity_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(group_identity_id, radio_system_id, group_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    private static String radioAffiliationSql(boolean explicitWorkingAddresses)
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_affiliation (
                radio_system_id INTEGER NOT NULL
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                radio_kind_code INTEGER NOT NULL DEFAULT 2
                    CHECK(typeof(radio_kind_code) = 'integer' AND radio_kind_code = 2),
                radio_identity_id INTEGER NOT NULL
                    CHECK(typeof(radio_identity_id) = 'integer' AND radio_identity_id > 0),
                talkgroup_kind_code INTEGER NOT NULL DEFAULT 1
                    CHECK(typeof(talkgroup_kind_code) = 'integer' AND talkgroup_kind_code = 1),
                talkgroup_identity_id INTEGER NOT NULL
                    CHECK(typeof(talkgroup_identity_id) = 'integer' AND talkgroup_identity_id > 0),
                channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                radio_observed_local_id INTEGER CHECK(radio_observed_local_id IS NULL OR
                    (typeof(radio_observed_local_id) = 'integer'
                        AND radio_observed_local_id BETWEEN 0 AND 16777215)),
                talkgroup_observed_local_id INTEGER CHECK(talkgroup_observed_local_id IS NULL OR
                    (typeof(talkgroup_observed_local_id) = 'integer'
                        AND talkgroup_observed_local_id BETWEEN 0 AND 16777215)),
                confirmed_at_ms INTEGER NOT NULL
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),%s
                PRIMARY KEY(radio_system_id, radio_identity_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(talkgroup_identity_id, radio_system_id, talkgroup_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.formatted(explicitWorkingAddresses ?
                "\n                " + workingAddressColumn("radio_observed_working_id") + "," : "");
    }

    private static String radioChannelPresenceSql(boolean explicitWorkingAddresses)
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_channel_presence (
                radio_system_id INTEGER NOT NULL
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                radio_kind_code INTEGER NOT NULL DEFAULT 2
                    CHECK(typeof(radio_kind_code) = 'integer' AND radio_kind_code = 2),
                radio_identity_id INTEGER NOT NULL
                    CHECK(typeof(radio_identity_id) = 'integer' AND radio_identity_id > 0),
                channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                observed_local_id INTEGER CHECK(observed_local_id IS NULL OR
                    (typeof(observed_local_id) = 'integer' AND observed_local_id BETWEEN 0 AND 16777212)),
                evidence_code INTEGER NOT NULL
                    CHECK(typeof(evidence_code) = 'integer' AND evidence_code IN (1, 2)),
                confirmed_at_ms INTEGER NOT NULL
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),%s
                PRIMARY KEY(radio_system_id, radio_identity_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.formatted(explicitWorkingAddresses ?
                "\n                " + workingAddressColumn("observed_working_id") + "," : "");
    }

    private static String radioChannelPresenceClearSql(boolean explicitWorkingAddresses)
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_channel_presence_clear (
                radio_system_id INTEGER NOT NULL
                    CHECK(typeof(radio_system_id) = 'integer' AND radio_system_id > 0),
                radio_kind_code INTEGER NOT NULL DEFAULT 2
                    CHECK(typeof(radio_kind_code) = 'integer' AND radio_kind_code = 2),
                radio_identity_id INTEGER NOT NULL
                    CHECK(typeof(radio_identity_id) = 'integer' AND radio_identity_id > 0),
                channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                observed_local_id INTEGER CHECK(observed_local_id IS NULL OR
                    (typeof(observed_local_id) = 'integer' AND observed_local_id BETWEEN 1 AND 16777212)),
                cleared_at_ms INTEGER NOT NULL CHECK(typeof(cleared_at_ms) = 'integer' AND cleared_at_ms > 0),%s
                PRIMARY KEY(radio_system_id, radio_identity_id, channel_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """.formatted(explicitWorkingAddresses ?
                "\n                " + workingAddressColumn("observed_working_id") + "," : "");
    }

    private static String workingAddressColumn(String name)
    {
        return name + " INTEGER CHECK(" + name + " IS NULL OR (typeof(" + name + ") = 'integer' AND " +
            name + " BETWEEN 1 AND 16777212))";
    }

    static void addFormat32ExplicitWorkingAddressProvenance(Statement statement) throws SQLException
    {
        statement.executeUpdate("ALTER TABLE trunked_radio_affiliation ADD COLUMN " +
            workingAddressColumn("radio_observed_working_id"));
        statement.executeUpdate("ALTER TABLE trunked_radio_channel_presence ADD COLUMN " +
            workingAddressColumn("observed_working_id"));
        statement.executeUpdate("ALTER TABLE trunked_radio_channel_presence_clear ADD COLUMN " +
            workingAddressColumn("observed_working_id"));
    }

    static List<SqliteSchemaValidator.Table> tables()
    {
        List<String> identityColumns = new ArrayList<>(List.of(
            "id", "radio_system_id", "identity_kind_code", "home_wacn", "home_system_id", "identity_id",
            "first_seen_ms", "last_seen_ms"));
        identityColumns.addAll(ACTION_COUNT_COLUMNS);
        identityColumns.addAll(List.of("logical_call_count", "source_logical_call_count",
            "target_logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count", "last_encryption_algorithm_id", "last_encryption_key_id", "last_talker_alias",
            "last_talker_alias_seen_ms", "p25_subscriber_identity_id"));

        List<String> relationshipColumns = new ArrayList<>(List.of(
            "radio_system_id", "radio_kind_code", "radio_identity_id", "group_identity_id", "group_kind_code",
            "first_seen_ms", "last_seen_ms"));
        relationshipColumns.addAll(ACTION_COUNT_COLUMNS);
        relationshipColumns.addAll(List.of("logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count",
            "last_encryption_algorithm_id", "last_encryption_key_id"));

        return List.of(
            new SqliteSchemaValidator.Table("radio_system", "id", "system_key", "configuration_id", "protocol_code",
                "address_domain_code", "p25_wacn", "p25_system_id", "dmr_model_code",
                "dmr_network_id", "nxdn_location_category_code", "nxdn_system_id", "first_seen_ms", "last_seen_ms"),
            new SqliteSchemaValidator.Table("p25_subscriber_identity", "id", "home_wacn", "home_system_id",
                "subscriber_id"),
            new SqliteSchemaValidator.Table("p25_wuid_assignment_observation_summary", "radio_system_id",
                "working_id", "p25_subscriber_identity_id", "first_observed_ms", "last_observed_ms",
                "last_registration_ms", "last_affiliation_ms", "registration_count", "affiliation_count",
                "last_evidence_code", "last_channel_id"),
            new SqliteSchemaValidator.Table("radio_system_identity_summary", identityColumns),
            new SqliteSchemaValidator.Table("trunked_radio_group_summary", relationshipColumns),
            new SqliteSchemaValidator.Table("trunked_radio_affiliation", "radio_system_id", "radio_kind_code",
                "radio_identity_id", "talkgroup_kind_code", "talkgroup_identity_id", "channel_id",
                "radio_observed_local_id", "talkgroup_observed_local_id", "confirmed_at_ms",
                "radio_observed_working_id"),
            new SqliteSchemaValidator.Table("trunked_radio_channel_presence", "radio_system_id", "radio_kind_code",
                "radio_identity_id", "channel_id", "observed_local_id", "evidence_code", "confirmed_at_ms",
                "observed_working_id"),
            new SqliteSchemaValidator.Table("trunked_radio_channel_presence_clear", "radio_system_id",
                "radio_kind_code", "radio_identity_id", "channel_id", "observed_local_id", "cleared_at_ms",
                "observed_working_id")
        );
    }

    static List<String> indexes()
    {
        return List.of("idx_radio_system_identity_last_seen", "idx_radio_system_identity_retention",
            "idx_radio_system_identity_p25_subscriber", "idx_p25_wuid_assignment_observation_system_time",
            "idx_p25_wuid_assignment_observation_subscriber",
            "idx_p25_wuid_assignment_observation_retention",
            "idx_trunked_radio_group_reverse", "idx_trunked_radio_group_retention",
            "idx_trunked_radio_affiliation_talkgroup", "idx_trunked_radio_affiliation_retention",
            "idx_trunked_radio_channel_presence_channel", "idx_trunked_radio_channel_presence_retention",
            "idx_trunked_radio_channel_presence_clear_retention");
    }

    static void validate(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "radio_system", List.of("id"));
        validatePrimaryKey(connection, "p25_subscriber_identity", List.of("id"));
        validatePrimaryKey(connection, "p25_wuid_assignment_observation_summary",
            List.of("radio_system_id", "working_id", "p25_subscriber_identity_id"));
        validatePrimaryKey(connection, "radio_system_identity_summary", List.of("id"));
        validatePrimaryKey(connection, "trunked_radio_group_summary",
            List.of("radio_system_id", "radio_identity_id", "group_identity_id"));
        validatePrimaryKey(connection, "trunked_radio_affiliation",
            List.of("radio_system_id", "radio_identity_id"));
        validatePrimaryKey(connection, "trunked_radio_channel_presence",
            List.of("radio_system_id", "radio_identity_id"));
        validatePrimaryKey(connection, "trunked_radio_channel_presence_clear",
            List.of("radio_system_id", "radio_identity_id", "channel_id"));

        validateForeignKeys(connection, "radio_system", Set.of(
            new ForeignKey("configuration_id", "configuration_channel", "configuration_id", "CASCADE")));
        validateForeignKeys(connection, "p25_subscriber_identity", Set.of());
        validateForeignKeys(connection, "p25_wuid_assignment_observation_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE"),
            new ForeignKey("p25_subscriber_identity_id", "p25_subscriber_identity", "id", "RESTRICT"),
            new ForeignKey("last_channel_id", "receiver_channel", "id", "SET NULL")));
        validateForeignKeys(connection, "radio_system_identity_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE"),
            new ForeignKey("p25_subscriber_identity_id", "p25_subscriber_identity", "id", "RESTRICT")));
        validateForeignKeys(connection, "trunked_radio_group_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system_identity_summary", "radio_system_id", "CASCADE"),
            new ForeignKey("radio_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("radio_identity_id", "radio_system_identity_summary", "id", "CASCADE"),
            new ForeignKey("group_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("group_identity_id", "radio_system_identity_summary", "id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_affiliation", Set.of(
            new ForeignKey("radio_system_id", "radio_system_identity_summary", "radio_system_id", "CASCADE"),
            new ForeignKey("radio_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("radio_identity_id", "radio_system_identity_summary", "id", "CASCADE"),
            new ForeignKey("talkgroup_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("talkgroup_identity_id", "radio_system_identity_summary", "id", "CASCADE"),
            new ForeignKey("channel_id", "receiver_channel", "id", "CASCADE"),
            new ForeignKey("radio_system_id", "receiver_channel", "radio_system_id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_channel_presence", Set.of(
            new ForeignKey("radio_system_id", "radio_system_identity_summary", "radio_system_id", "CASCADE"),
            new ForeignKey("radio_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("radio_identity_id", "radio_system_identity_summary", "id", "CASCADE"),
            new ForeignKey("channel_id", "receiver_channel", "id", "CASCADE"),
            new ForeignKey("radio_system_id", "receiver_channel", "radio_system_id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_channel_presence_clear", Set.of(
            new ForeignKey("radio_system_id", "radio_system_identity_summary", "radio_system_id", "CASCADE"),
            new ForeignKey("radio_kind_code", "radio_system_identity_summary", "identity_kind_code", "CASCADE"),
            new ForeignKey("radio_identity_id", "radio_system_identity_summary", "id", "CASCADE"),
            new ForeignKey("channel_id", "receiver_channel", "id", "CASCADE"),
            new ForeignKey("radio_system_id", "receiver_channel", "radio_system_id", "CASCADE")));

        validateIndex(connection, "idx_radio_system_identity_last_seen", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "identity_kind_code", false),
            new IndexColumn(2, "last_seen_ms", true),
            new IndexColumn(3, "home_wacn", false),
            new IndexColumn(4, "home_system_id", false),
            new IndexColumn(5, "identity_id", false)));
        validateIndex(connection, "idx_radio_system_identity_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "identity_kind_code", false),
            new IndexColumn(3, "home_wacn", false),
            new IndexColumn(4, "home_system_id", false),
            new IndexColumn(5, "identity_id", false)));
        validateIndex(connection, "idx_radio_system_identity_p25_subscriber", List.of(
            new IndexColumn(0, "p25_subscriber_identity_id", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "id", false)));
        validateIndex(connection, "idx_p25_wuid_assignment_observation_system_time", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "last_observed_ms", true),
            new IndexColumn(2, "working_id", false),
            new IndexColumn(3, "p25_subscriber_identity_id", false)));
        validateIndex(connection, "idx_p25_wuid_assignment_observation_subscriber", List.of(
            new IndexColumn(0, "p25_subscriber_identity_id", false),
            new IndexColumn(1, "last_observed_ms", true),
            new IndexColumn(2, "radio_system_id", false),
            new IndexColumn(3, "working_id", false)));
        validateIndex(connection, "idx_p25_wuid_assignment_observation_retention", List.of(
            new IndexColumn(0, "last_observed_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "working_id", false),
            new IndexColumn(3, "p25_subscriber_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_group_reverse", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "group_identity_id", false),
            new IndexColumn(2, "group_kind_code", false),
            new IndexColumn(3, "last_seen_ms", true),
            new IndexColumn(4, "radio_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_group_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_identity_id", false),
            new IndexColumn(3, "group_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_affiliation_talkgroup", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "talkgroup_identity_id", false),
            new IndexColumn(2, "confirmed_at_ms", true),
            new IndexColumn(3, "channel_id", false),
            new IndexColumn(4, "radio_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_affiliation_retention", List.of(
            new IndexColumn(0, "confirmed_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_channel_presence_channel", List.of(
            new IndexColumn(0, "channel_id", false),
            new IndexColumn(1, "confirmed_at_ms", true),
            new IndexColumn(2, "radio_system_id", false),
            new IndexColumn(3, "radio_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_channel_presence_retention", List.of(
            new IndexColumn(0, "confirmed_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_channel_presence_clear_retention", List.of(
            new IndexColumn(0, "cleared_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_identity_id", false),
            new IndexColumn(3, "channel_id", false)));
        validateRadioSystemKeys(connection);
    }

    static RadioSystem recordActivity(Connection connection, ReceiverActivityRecords.ActivityEvent activity,
                                      int channelId, ActivityBatch batch) throws SQLException
    {
        ReceiverActivityRecords.P25WuidObservation wuidObservation = activity.p25WuidObservation();
        Integer observedWacn = activity.wacn();
        Integer observedSystem = activity.systemId();
        if(wuidObservation != null &&
            (observedWacn == null || observedWacn == wuidObservation.servingWacn()) &&
            (observedSystem == null || observedSystem == wuidObservation.servingSystem()))
        {
            observedWacn = wuidObservation.servingWacn();
            observedSystem = wuidObservation.servingSystem();
        }

        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, activity.observedAtEpochMilliseconds(),
            activity.identityDomain(), observedWacn, observedSystem, activity.radioSystemKey(), batch);

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                activity.observedAtEpochMilliseconds() < radioSystem.firstSeenEpochMilliseconds()))
        {
            return null;
        }

        recordP25WuidObservation(connection, radioSystem, channelId,
            activity.observedAtEpochMilliseconds(), wuidObservation,
            MAX_WUID_ASSIGNMENT_OBSERVATIONS_PER_SYSTEM);

        if(activity.action() == null ||
            activity.action() == ReceiverActivityRecords.Action.UNKNOWN)
        {
            return radioSystem;
        }

        if(batch != null && (activity.encryptionAlgorithmId() != null || activity.encryptionKeyId() != null))
        {
            batch.flushIdentities(connection);
        }

        Identity sourceIdentity = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            integer(activity.sourceRadioId()), activity.p25SourceIdentity(),
            activity.observedAtEpochMilliseconds());
        List<Identity> destinations = destinationIdentities(connection, radioSystem, activity.targetId(), activity.targetKind(),
            activity.patchMemberTalkgroupIds(), activity.p25TargetIdentity(), activity.p25PatchMemberIdentities(),
            activity.observedAtEpochMilliseconds());
        //Receiver observations establish identity/signaling metadata only. Logical-call completion owns call and
        //encryption counters so copies heard on several receiver legs cannot inflate them.
        int encrypted = 0;

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem, destination, activity.observedAtEpochMilliseconds(),
                activity.action(), IdentityCounts.NONE, activity.encryptionAlgorithmId(), activity.encryptionKeyId(),
                null, null, batch);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyObserved = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            upsertIdentity(connection, radioSystem, sourceIdentity, activity.observedAtEpochMilliseconds(),
                alreadyObserved ? null : activity.action(), IdentityCounts.NONE,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null, batch);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), sourceIdentity, destination,
                    activity.observedAtEpochMilliseconds(), activity.action(), false, encrypted,
                    0, 0, activity.encryptionAlgorithmId(), activity.encryptionKeyId());
            }
        }

        if(isCurrentRadioSystem(connection, channelId, radioSystem.radioSystemId(),
            activity.observedAtEpochMilliseconds()))
        {
            updateRadioPresence(connection, radioSystem, channelId, activity, batch);
        }

        return radioSystem;
    }

    /**
     * Records positive WUID evidence as bounded observation history.  This table is never consulted to resolve a
     * later radio identity; only explicit identity evidence carried by that later event may do that.
     */
    static boolean recordP25WuidObservation(Connection connection, RadioSystem radioSystem, int channelId,
                                            long observedAt,
                                            ReceiverActivityRecords.P25WuidObservation observation,
                                            int maximumRows) throws SQLException
    {
        if(observation == null || radioSystem == null || observedAt <= 0 || maximumRows <= 0 ||
            radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 ||
            radioSystem.p25Wacn() == null || radioSystem.p25SystemId() == null ||
            radioSystem.p25Wacn() != observation.servingWacn() ||
            radioSystem.p25SystemId() != observation.servingSystem())
        {
            return false;
        }

        P25SubscriberIdentity subscriber = observation.subscriber();
        int subscriberIdentityId = p25SubscriberIdentityId(connection, subscriber);
        boolean existingObservation = subscriberIdentityId > 0 && p25WuidObservationExists(connection,
            radioSystem.radioSystemId(), observation.workingId(), subscriberIdentityId);

        if(!existingObservation && !hasSystemCapacity(connection, "p25_wuid_assignment_observation_summary",
            radioSystem.radioSystemId(), maximumRows))
        {
            return false;
        }

        if(subscriberIdentityId <= 0)
        {
            Identity identity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
                subscriber.subscriberId(), subscriber.homeWacn(), subscriber.homeSystemId(),
                observation.workingId(), true);
            subscriberIdentityId = ensureP25SubscriberIdentity(connection, identity);
        }

        int registration = observation.evidence() == ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION ?
            1 : 0;
        int affiliation = observation.evidence() == ReceiverActivityRecords.RadioPresenceEvidence.AFFILIATION ?
            1 : 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_wuid_assignment_observation_summary (
                radio_system_id, working_id, p25_subscriber_identity_id,
                first_observed_ms, last_observed_ms, last_registration_ms, last_affiliation_ms,
                registration_count, affiliation_count, last_evidence_code, last_channel_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, working_id, p25_subscriber_identity_id) DO UPDATE SET
                first_observed_ms = min(
                    p25_wuid_assignment_observation_summary.first_observed_ms,
                    excluded.first_observed_ms),
                last_observed_ms = max(
                    p25_wuid_assignment_observation_summary.last_observed_ms,
                    excluded.last_observed_ms),
                last_registration_ms = CASE
                    WHEN excluded.last_registration_ms IS NULL
                    THEN p25_wuid_assignment_observation_summary.last_registration_ms
                    ELSE max(coalesce(p25_wuid_assignment_observation_summary.last_registration_ms, 0),
                        excluded.last_registration_ms)
                END,
                last_affiliation_ms = CASE
                    WHEN excluded.last_affiliation_ms IS NULL
                    THEN p25_wuid_assignment_observation_summary.last_affiliation_ms
                    ELSE max(coalesce(p25_wuid_assignment_observation_summary.last_affiliation_ms, 0),
                        excluded.last_affiliation_ms)
                END,
                registration_count = p25_wuid_assignment_observation_summary.registration_count +
                    excluded.registration_count,
                affiliation_count = p25_wuid_assignment_observation_summary.affiliation_count +
                    excluded.affiliation_count,
                last_evidence_code = CASE
                    WHEN excluded.last_observed_ms > p25_wuid_assignment_observation_summary.last_observed_ms
                      OR (excluded.last_observed_ms = p25_wuid_assignment_observation_summary.last_observed_ms
                          AND excluded.last_evidence_code <
                              p25_wuid_assignment_observation_summary.last_evidence_code)
                    THEN excluded.last_evidence_code
                    ELSE p25_wuid_assignment_observation_summary.last_evidence_code
                END,
                last_channel_id = CASE
                    WHEN excluded.last_observed_ms > p25_wuid_assignment_observation_summary.last_observed_ms
                      OR (excluded.last_observed_ms = p25_wuid_assignment_observation_summary.last_observed_ms
                          AND (excluded.last_evidence_code <
                                  p25_wuid_assignment_observation_summary.last_evidence_code
                            OR (excluded.last_evidence_code =
                                    p25_wuid_assignment_observation_summary.last_evidence_code
                                AND excluded.last_channel_id <
                                    coalesce(p25_wuid_assignment_observation_summary.last_channel_id,
                                        excluded.last_channel_id + 1))))
                    THEN excluded.last_channel_id
                    ELSE p25_wuid_assignment_observation_summary.last_channel_id
                END
            """))
        {
            int index = 1;
            statement.setInt(index++, radioSystem.radioSystemId());
            statement.setInt(index++, observation.workingId());
            statement.setInt(index++, subscriberIdentityId);
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            setLong(statement, index++, registration != 0 ? observedAt : null);
            setLong(statement, index++, affiliation != 0 ? observedAt : null);
            statement.setInt(index++, registration);
            statement.setInt(index++, affiliation);
            statement.setInt(index++, observation.evidence().code());
            statement.setInt(index, channelId);
            statement.executeUpdate();
        }

        return true;
    }

    private static int p25SubscriberIdentityId(Connection connection, P25SubscriberIdentity subscriber)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM p25_subscriber_identity
            WHERE home_wacn = ? AND home_system_id = ? AND subscriber_id = ?
            """))
        {
            statement.setInt(1, subscriber.homeWacn());
            statement.setInt(2, subscriber.homeSystemId());
            statement.setInt(3, subscriber.subscriberId());
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static boolean p25WuidObservationExists(Connection connection, int radioSystemId, int workingId,
                                                     int subscriberIdentityId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM p25_wuid_assignment_observation_summary
            WHERE radio_system_id = ? AND working_id = ? AND p25_subscriber_identity_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, workingId);
            statement.setInt(3, subscriberIdentityId);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static void updateRadioPresence(Connection connection, RadioSystem radioSystem, int channelId,
                                            ReceiverActivityRecords.ActivityEvent activity, ActivityBatch batch)
        throws SQLException
    {
        ReceiverActivityRecords.RadioPresenceUpdate update = activity.radioPresenceUpdate();
        if(update == null)
        {
            return;
        }

        Integer explicitWorkingId = update.radioIdentity().isStableFullyQualified() ? positive(update.radioId()) : null;
        Integer matchLocalId = update.radioIdentity().isStableFullyQualified() ? explicitWorkingId :
            positive(update.radioId());

        Identity radio = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, update.radioId(),
            update.radioIdentity());
        if(radio == null)
        {
            return;
        }

        if(update.cleared())
        {
            upsertIdentity(connection, radioSystem, radio, activity.observedAtEpochMilliseconds(),
                null, IdentityCounts.NONE, null, null, null, null, null);
            int radioIdentityId = identitySummaryId(connection, radioSystem.radioSystemId(), radio);
            for(int currentIdentityId: currentRadioIdentityIds(connection, radioSystem.radioSystemId(), channelId,
                matchLocalId, radioIdentityId, activity.observedAtEpochMilliseconds()))
            {
                upsertPresenceClear(connection, radioSystem.radioSystemId(), currentIdentityId, channelId,
                    radio.localId(), explicitWorkingId, activity.observedAtEpochMilliseconds());
            }
            for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
            {
                try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE radio_system_id = ? AND channel_id = ? " +
                        "AND confirmed_at_ms <= ? AND (radio_identity_id = ? OR (" +
                        ("trunked_radio_affiliation".equals(table) ? "radio_observed_local_id" :
                            "observed_local_id") + " = ?))"))
                {
                    statement.setInt(1, radioSystem.radioSystemId());
                    statement.setInt(2, channelId);
                    statement.setLong(3, activity.observedAtEpochMilliseconds());
                    statement.setInt(4, radioIdentityId);
                    setInteger(statement, 5, matchLocalId);
                    statement.executeUpdate();
                }
            }
            return;
        }

        radio = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            update.radioId(), update.radioIdentity(), activity.observedAtEpochMilliseconds());
        if(radio == null)
        {
            return;
        }
        upsertIdentity(connection, radioSystem, radio, activity.observedAtEpochMilliseconds(),
            null, IdentityCounts.NONE, null, null, null, null, null);
        int radioIdentityId = identitySummaryId(connection, radioSystem.radioSystemId(), radio);
        if(radioIdentityId <= 0)
        {
            return;
        }
        removeConflictingCurrentRows(connection, radioSystem.radioSystemId(), matchLocalId, radioIdentityId);

        Identity talkgroup = update.talkgroupId() != null ? identity(radioSystem,
            TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, update.talkgroupId(), update.talkgroupIdentity()) : null;
        if(update.talkgroupId() != null && talkgroup == null || hasClearAtOrAfter(connection,
            radioSystem.radioSystemId(), radioIdentityId, channelId, matchLocalId,
            activity.observedAtEpochMilliseconds()))
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_channel_presence (
                radio_system_id, radio_identity_id, channel_id, observed_local_id, evidence_code, confirmed_at_ms,
                observed_working_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id) DO UPDATE SET
                channel_id = excluded.channel_id,
                observed_local_id = excluded.observed_local_id,
                evidence_code = excluded.evidence_code,
                confirmed_at_ms = excluded.confirmed_at_ms,
                observed_working_id = excluded.observed_working_id
            WHERE excluded.confirmed_at_ms > trunked_radio_channel_presence.confirmed_at_ms
               OR (excluded.confirmed_at_ms = trunked_radio_channel_presence.confirmed_at_ms
                   AND (excluded.evidence_code < trunked_radio_channel_presence.evidence_code
                       OR (excluded.evidence_code = trunked_radio_channel_presence.evidence_code
                           AND excluded.channel_id < trunked_radio_channel_presence.channel_id)))
            """))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, radioIdentityId);
            statement.setInt(3, channelId);
            setInteger(statement, 4, radio.localId());
            statement.setInt(5, update.evidence().code());
            statement.setLong(6, activity.observedAtEpochMilliseconds());
            setInteger(statement, 7, explicitWorkingId);
            statement.executeUpdate();
        }

        if(talkgroup == null)
        {
            return;
        }
        upsertIdentity(connection, radioSystem, talkgroup, activity.observedAtEpochMilliseconds(),
            null, IdentityCounts.NONE, null, null, null, null, batch);
        int talkgroupIdentityId = identitySummaryId(connection, radioSystem.radioSystemId(), talkgroup);
        if(talkgroupIdentityId <= 0)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_affiliation (
                radio_system_id, radio_identity_id, talkgroup_identity_id, channel_id,
                radio_observed_local_id, talkgroup_observed_local_id, confirmed_at_ms,
                radio_observed_working_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id) DO UPDATE SET
                talkgroup_identity_id = excluded.talkgroup_identity_id,
                channel_id = excluded.channel_id,
                radio_observed_local_id = excluded.radio_observed_local_id,
                talkgroup_observed_local_id = excluded.talkgroup_observed_local_id,
                confirmed_at_ms = excluded.confirmed_at_ms,
                radio_observed_working_id = excluded.radio_observed_working_id
            WHERE excluded.confirmed_at_ms > trunked_radio_affiliation.confirmed_at_ms
               OR (excluded.confirmed_at_ms = trunked_radio_affiliation.confirmed_at_ms
                   AND (excluded.channel_id < trunked_radio_affiliation.channel_id
                       OR (excluded.channel_id = trunked_radio_affiliation.channel_id
                           AND excluded.talkgroup_identity_id <
                               trunked_radio_affiliation.talkgroup_identity_id)))
            """))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, radioIdentityId);
            statement.setInt(3, talkgroupIdentityId);
            statement.setInt(4, channelId);
            setInteger(statement, 5, radio.localId());
            setInteger(statement, 6, talkgroup.localId());
            statement.setLong(7, activity.observedAtEpochMilliseconds());
            setInteger(statement, 8, explicitWorkingId);
            statement.executeUpdate();
        }
    }

    private static void removeConflictingCurrentRows(Connection connection, int radioSystemId,
                                                     Integer observedLocalId, int requestedIdentityId)
        throws SQLException
    {
        if(observedLocalId == null || observedLocalId < 1 || observedLocalId > MAX_P25_WORKING_RADIO_ID)
        {
            return;
        }
        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
        {
            String localColumn = "trunked_radio_affiliation".equals(table) ?
                "radio_observed_local_id" : "observed_local_id";
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE radio_system_id=? AND " + localColumn +
                    "=? AND radio_identity_id<>?"))
            {
                statement.setInt(1, radioSystemId);
                statement.setInt(2, observedLocalId);
                statement.setInt(3, requestedIdentityId);
                statement.executeUpdate();
            }
        }
    }

    private static java.util.Set<Integer> currentRadioIdentityIds(Connection connection, int radioSystemId,
                                                                  int channelId, Integer observedLocalId,
                                                                  int requestedIdentityId, long observedAt)
        throws SQLException
    {
        java.util.Set<Integer> identities = new java.util.LinkedHashSet<>();
        if(requestedIdentityId > 0)
        {
            identities.add(requestedIdentityId);
        }
        if(observedLocalId == null || observedLocalId <= 0)
        {
            return identities;
        }

        //A clear is scoped to the observing channel's presence rows. Assignment observations are never consulted
        //for identity resolution or current-state changes.
        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
        {
            String localColumn = "trunked_radio_affiliation".equals(table) ?
                "radio_observed_local_id" : "observed_local_id";
            try(PreparedStatement statement = connection.prepareStatement(
                "SELECT radio_identity_id FROM " + table +
                    " WHERE radio_system_id=? AND channel_id=? AND " + localColumn +
                    "=? AND confirmed_at_ms<=?"))
            {
                statement.setInt(1, radioSystemId);
                statement.setInt(2, channelId);
                statement.setInt(3, observedLocalId);
                statement.setLong(4, observedAt);
                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        identities.add(resultSet.getInt(1));
                    }
                }
            }
        }
        return identities;
    }

    private static void upsertPresenceClear(Connection connection, int radioSystemId, int radioIdentityId,
                                            int channelId, Integer observedLocalId, Integer observedWorkingId,
                                            long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_channel_presence_clear(
                radio_system_id, radio_identity_id, channel_id, observed_local_id, cleared_at_ms,
                observed_working_id
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id, channel_id) DO UPDATE SET
                observed_local_id = coalesce(excluded.observed_local_id,
                    trunked_radio_channel_presence_clear.observed_local_id),
                observed_working_id = CASE
                    WHEN excluded.cleared_at_ms >= trunked_radio_channel_presence_clear.cleared_at_ms
                    THEN excluded.observed_working_id
                    ELSE trunked_radio_channel_presence_clear.observed_working_id END,
                cleared_at_ms = max(trunked_radio_channel_presence_clear.cleared_at_ms, excluded.cleared_at_ms)
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, radioIdentityId);
            statement.setInt(3, channelId);
            setInteger(statement, 4, positive(observedLocalId));
            statement.setLong(5, observedAt);
            setInteger(statement, 6, positive(observedWorkingId));
            statement.executeUpdate();
        }
    }

    /**
     * A deregistration wins an equal-time tie. Retaining its bounded watermark prevents delayed confirmations from
     * recreating either current state after the visible rows have been removed.
     */
    private static boolean hasClearAtOrAfter(Connection connection, int radioSystemId, int radioIdentityId,
                                             int channelId, Integer observedLocalId, long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM trunked_radio_channel_presence_clear
            WHERE radio_system_id = ? AND channel_id = ? AND cleared_at_ms >= ?
              AND (radio_identity_id = ? OR (? > 0 AND observed_local_id = ?))
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, channelId);
            statement.setLong(3, observedAt);
            statement.setInt(4, radioIdentityId);
            setInteger(statement, 5, positive(observedLocalId));
            setInteger(statement, 6, positive(observedLocalId));

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    static boolean isCurrentRadioSystem(Connection connection, int channelId, int radioSystemId,
                                        long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            """
            SELECT 1 FROM receiver_channel
            WHERE id = ? AND radio_system_id = ?
              AND (radio_system_assigned_at_ms IS NULL OR ? >= radio_system_assigned_at_ms)
            """))
        {
            statement.setInt(1, channelId);
            statement.setInt(2, radioSystemId);
            statement.setLong(3, observedAt);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    /** Updates bounded lifetime identity and relationship summaries exactly once for a resolved logical call. */
    static void recordResolvedLogicalCall(Connection connection, RadioSystem radioSystem,
                                          ReceiverActivityRecords.ResolvedLogicalCall call) throws SQLException
    {
        if(radioSystem == null || call == null)
        {
            return;
        }

        List<Identity> destinations = destinationIdentities(connection, radioSystem,
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities(),
            call.callStartEpochMilliseconds());
        Identity sourceIdentity = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            call.sourceRadioId(), call.p25SourceIdentity(), call.callStartEpochMilliseconds());
        int encrypted = call.encrypted() ? 1 : 0;

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem, destination, call.callStartEpochMilliseconds(), null,
                IdentityCounts.targetResolvedCall(encrypted), call.encryptionAlgorithmId(), call.encryptionKeyId(),
                null, null, null);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyCounted = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            upsertIdentity(connection, radioSystem, sourceIdentity, call.callStartEpochMilliseconds(), null,
                IdentityCounts.sourceResolvedCall(alreadyCounted ? 0 : encrypted, !alreadyCounted),
                call.encryptionAlgorithmId(), call.encryptionKeyId(), null, null, null);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), sourceIdentity, destination,
                    call.callStartEpochMilliseconds(), null, true, encrypted, 0, 0,
                    call.encryptionAlgorithmId(), call.encryptionKeyId());
            }
        }
    }

    /** Updates output counters for the already-counted resolved logical call without adding another call. */
    static void applyLogicalCallOutput(Connection connection, RadioSystem radioSystem,
                                       ReceiverActivityRecords.LogicalCallOutput output, int recorded, int streamed)
        throws SQLException
    {
        if(radioSystem == null || output == null)
        {
            return;
        }

        ReceiverActivityRecords.ResolvedLogicalCall call = output.call();
        List<Identity> destinations = destinationIdentities(connection, radioSystem,
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities(),
            call.callStartEpochMilliseconds());
        Identity sourceIdentity = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            call.sourceRadioId(), call.p25SourceIdentity(), call.callStartEpochMilliseconds());

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem, destination, call.callStartEpochMilliseconds(), null,
                IdentityCounts.outputs(recorded, streamed), null, null, null, null, null);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyCounted = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            upsertIdentity(connection, radioSystem, sourceIdentity, call.callStartEpochMilliseconds(),
                null, IdentityCounts.outputs(alreadyCounted ? 0 : recorded, alreadyCounted ? 0 : streamed),
                null, null, null, null, null);
            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), sourceIdentity, destination,
                    call.callStartEpochMilliseconds(), null, false, 0, recorded, streamed, null, null);
            }
        }
    }

    static boolean applyAttribution(Connection connection, RadioSystem radioSystem,
                                    ReceiverActivityRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        if(radioSystem == null || attribution.callStartEpochMilliseconds() < radioSystem.firstSeenEpochMilliseconds())
        {
            return false;
        }

        List<Identity> destinations = destinationIdentities(connection, radioSystem,
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(),
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of(), attribution.callStartEpochMilliseconds());
        Identity sourceIdentity = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            attribution.sourceRadioId(), ReceiverActivityRecords.P25Identity.UNKNOWN,
            attribution.callStartEpochMilliseconds());
        boolean enrichDestination = attribution.destinationBecameKnown() ||
            attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();
        boolean enrichSource = attribution.sourceBecameKnown() ||
            attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();
        boolean applied = false;

        if(enrichDestination)
        {
            for(Identity destination: destinations)
            {
                applied |= upsertIdentity(connection, radioSystem, destination,
                    attribution.callStartEpochMilliseconds(), null, IdentityCounts.NONE,
                    attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null, null);
            }
        }

        if(enrichSource && sourceIdentity != null)
        {
            applied |= upsertIdentity(connection, radioSystem, sourceIdentity,
                attribution.callStartEpochMilliseconds(), null, IdentityCounts.NONE,
                attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null, null);
        }

        if(sourceIdentity != null && !destinations.isEmpty() &&
            (attribution.destinationBecameKnown() || attribution.sourceBecameKnown()))
        {
            for(Identity destination: groupDestinations(destinations))
            {
                applied |= upsertRelationship(connection, radioSystem.radioSystemId(), sourceIdentity, destination,
                    attribution.callStartEpochMilliseconds(), null, false, 0, 0, 0,
                    attribution.encryptionAlgorithmId(), attribution.encryptionKeyId());
            }
        }

        return applied;
    }

    /** Indicates whether delayed attribution still belongs to the channel's current identity generation. */
    static boolean isAttributionCompatible(Connection connection, int channelId,
                                           ReceiverActivityRecords.TrunkedCallAttribution attribution)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT system.protocol_code, system.address_domain_code, system.first_seen_ms
            FROM receiver_channel channel
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE channel.id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next() || resultSet.getObject("protocol_code") == null)
                {
                    return true;
                }

                int protocolCode = resultSet.getInt("protocol_code");
                if(protocolCode != TrunkedIdentityPolicy.PROTOCOL_NXDN)
                {
                    return true;
                }

                if(attribution.callStartEpochMilliseconds() < resultSet.getLong("first_seen_ms"))
                {
                    return false;
                }

                int observedDomain = addressDomainCode(TrunkedIdentityPolicy.PROTOCOL_NXDN,
                    attribution.identityDomain());
                return observedDomain == IDENTITY_DOMAIN_STANDARD ||
                    observedDomain == resultSet.getInt("address_domain_code");
            }
        }
    }

    static boolean updateTalkerAlias(Connection connection, int channelId, Integer radioId,
                                     ReceiverActivityRecords.P25Identity p25RadioIdentity, String talkerAlias,
                                     long observedAt, long callStart, TrunkedIdentityDomain identityDomain,
                                     Integer p25Wacn, Integer p25SystemId, String radioSystemKey)
        throws SQLException
    {
        if(talkerAlias == null || talkerAlias.isBlank())
        {
            return false;
        }

        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, callStart, identityDomain,
            p25Wacn, p25SystemId, radioSystemKey);
        Identity radio = resolvedIdentity(connection, radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId,
            p25RadioIdentity, observedAt);

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                callStart < radioSystem.firstSeenEpochMilliseconds()) ||
            radio == null)
        {
            return false;
        }

        return upsertIdentity(connection, radioSystem, radio, observedAt,
            null, IdentityCounts.NONE, null, null, talkerAlias, observedAt, null);
    }

    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             TrunkedIdentityDomain observationDomain) throws SQLException
    {
        return ensureRadioSystem(connection, channelId, observedAt, observationDomain, null, null);
    }

    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             TrunkedIdentityDomain observationDomain,
                             Integer p25Wacn, Integer p25SystemId) throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, null, null, null, null);
    }

    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                                         TrunkedIdentityDomain observationDomain,
                                         Integer p25Wacn, Integer p25SystemId, String radioSystemKey)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, null, radioSystemKey, null, null);
    }

    private static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                                                 TrunkedIdentityDomain observationDomain,
                                                 Integer p25Wacn, Integer p25SystemId, String radioSystemKey,
                                                 ActivityBatch batch) throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, null, radioSystemKey, null, batch);
    }

    /** Resolves a DMR/NXDN site using native identity only for the capture-proven protocol variants. */
    static RadioSystem ensureTrunkedSiteRadioSystem(Connection connection, int channelId, long observedAt,
                                                    TrunkedIdentityDomain observationDomain,
                                                    int variantCode, Integer modelCode, Integer networkId,
                                                    Integer locationCategoryCode, Integer systemId)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, null, null,
            new SiteSystemEvidence(variantCode, modelCode, networkId, locationCategoryCode, systemId), null, null,
            null);
    }

    /** True when this protocol/variant is one of the native identities supported by the profile-local model. */
    static boolean supportsNativeTrunkedSiteIdentity(int protocol, TrunkedIdentityDomain observationDomain,
                                                     int variantCode)
    {
        return new SiteSystemEvidence(variantCode, null, null, null, null).supportsNativeVariant(
            TrunkedIdentityPolicy.protocolFamilyCode(protocol),
            addressDomainCode(TrunkedIdentityPolicy.protocolFamilyCode(protocol), observationDomain));
    }

    /** Returns a complete canonical native key, or {@code null} while required native evidence is incomplete. */
    static String nativeTrunkedSiteSystemKey(int protocol, TrunkedIdentityDomain observationDomain,
                                             int variantCode, Integer modelCode, Integer networkId,
                                             Integer locationCategoryCode, Integer systemId)
    {
        int family = TrunkedIdentityPolicy.protocolFamilyCode(protocol);
        NativeSystemIdentity identity = nativeSystemIdentity(family,
            addressDomainCode(family, observationDomain),
            new SiteSystemEvidence(variantCode, modelCode, networkId, locationCategoryCode, systemId));
        return identity != null ? identity.systemKey() : null;
    }

    /** Resolves one completed receiver-local logical call. */
    static RadioSystem ensureReceiverRadioSystem(Connection connection, int channelId, long observedAt,
                                                TrunkedIdentityDomain observationDomain,
                                                Integer p25Wacn, Integer p25SystemId, String radioSystemKey)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, null, radioSystemKey, null, null);
    }

    /** Resolves the complete P25 site identity whose decoded source was verified as an advertised control. */
    static RadioSystem ensureVerifiedP25SiteRadioSystem(Connection connection, int channelId, long observedAt,
                                                        P25SiteIdentity siteIdentity) throws SQLException
    {
        if(siteIdentity == null)
        {
            return null;
        }

        return ensureRadioSystemInternal(connection, channelId, observedAt, TrunkedIdentityDomain.STANDARD,
            siteIdentity.wacn(), siteIdentity.system(), null, null, siteIdentity, null);
    }

    private static RadioSystem ensureRadioSystemInternal(Connection connection, int channelId, long observedAt,
                                                         TrunkedIdentityDomain observationDomain,
                                                         Integer observedP25Wacn,
                                                         Integer observedP25SystemId,
                                                         SiteSystemEvidence siteEvidence,
                                                         String capturedSystemKey,
                                                         P25SiteIdentity verifiedP25SiteIdentity,
                                                         ActivityBatch batch)
        throws SQLException
    {
        ReceiverChannel channel = receiverChannel(connection, channelId);

        if(channel == null || observedAt <= 0)
        {
            return null;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(channel.protocolCode());

        if(!TrunkedIdentityPolicy.isSupportedProtocol(protocol))
        {
            return null;
        }

        int addressDomainCode = addressDomainCode(protocol, observationDomain);

        if(capturedSystemKey != null)
        {
            try
            {
                capturedSystemKey = RadioSystemKey.validateForReceiver(protocol(protocol),
                    identityDomain(addressDomainCode), channel.configurationId(), capturedSystemKey);
            }
            catch(IllegalArgumentException exception)
            {
                return null;
            }
        }

        NativeSystemIdentity capturedNativeIdentity = nativeSystemIdentity(protocol, addressDomainCode,
            capturedSystemKey);
        Integer p25Wacn = protocol == TrunkedIdentityPolicy.PROTOCOL_P25 ? observedP25Wacn : null;
        Integer p25SystemId = protocol == TrunkedIdentityPolicy.PROTOCOL_P25 ? observedP25SystemId : null;

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && capturedNativeIdentity != null)
        {
            if(p25Wacn != null && !p25Wacn.equals(capturedNativeIdentity.p25Wacn()) ||
                p25SystemId != null && !p25SystemId.equals(capturedNativeIdentity.p25SystemId()))
            {
                return null;
            }

            p25Wacn = capturedNativeIdentity.p25Wacn();
            p25SystemId = capturedNativeIdentity.p25SystemId();
        }

        boolean observedCompleteP25Identity = p25Wacn != null && p25SystemId != null;
        boolean currentCompleteP25Identity = channel.currentP25Wacn() != null &&
            channel.currentP25SystemId() != null;

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && currentCompleteP25Identity &&
            !observedCompleteP25Identity)
        {
            boolean conflictsWithCurrent = p25Wacn != null && !p25Wacn.equals(channel.currentP25Wacn()) ||
                p25SystemId != null && !p25SystemId.equals(channel.currentP25SystemId());
            if(conflictsWithCurrent || channel.radioSystemAssignedAtEpochMilliseconds() != null &&
                observedAt < channel.radioSystemAssignedAtEpochMilliseconds())
            {
                //A delayed or partial record cannot safely be assigned to the channel's newer native generation.
                return null;
            }

            p25Wacn = channel.currentP25Wacn();
            p25SystemId = channel.currentP25SystemId();
        }

        //P25 radio-system identity is defined only by a complete native WACN and System ID. Before that identity is
        //known, detailed observations remain channel-owned and no synthetic system or system summary is created.
        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && (p25Wacn == null || p25SystemId == null))
        {
            return null;
        }

        NativeSystemIdentity nativeIdentity = capturedNativeIdentity != null ? capturedNativeIdentity :
            nativeSystemIdentity(protocol, addressDomainCode, siteEvidence);
        boolean capturedFallback = capturedSystemKey != null && capturedNativeIdentity == null;
        boolean incompleteCompatibleNativeEvidence = siteEvidence != null && nativeIdentity == null &&
            siteEvidence.supportsNativeVariant(protocol, addressDomainCode);

        if(protocol != TrunkedIdentityPolicy.PROTOCOL_P25 && capturedSystemKey == null &&
            channel.currentSystemKey() == null && channel.radioSystemAssignedAtEpochMilliseconds() != null &&
            (siteEvidence == null || incompleteCompatibleNativeEvidence))
        {
            //A prior site-generation change deliberately detached this channel. Untyped signaling and repeated
            //partial native evidence may advance their own observations, but cannot recreate a fallback system.
            return null;
        }

        if(protocol != TrunkedIdentityPolicy.PROTOCOL_P25 &&
            capturedSystemKey == null &&
            (siteEvidence == null || incompleteCompatibleNativeEvidence) &&
            channel.currentSystemKey() != null && channel.currentSystemConfigurationId() == null)
        {
            if(addressDomainCode != channel.currentSystemAddressDomainCode() ||
                channel.radioSystemAssignedAtEpochMilliseconds() != null &&
                    observedAt < channel.radioSystemAssignedAtEpochMilliseconds())
            {
                return null;
            }

            //Partial evidence advances only last_seen; lowering first_seen would admit delayed signaling.
            RadioSystem cached = batch != null ?
                batch.observeSystem(channel.currentSystemKey(), observedAt, false) : null;
            if(cached != null)
            {
                return cached;
            }

            touchRadioSystem(connection, channel.radioSystemId(), observedAt);
            RadioSystem touched = selectRadioSystem(connection, channel.radioSystemId());
            if(batch != null)
            {
                batch.rememberSystem(touched);
            }
            return touched;
        }

        String systemKey = capturedSystemKey != null ? capturedSystemKey :
            p25Wacn != null && p25SystemId != null ?
            RadioSystemKey.p25(p25Wacn, p25SystemId) : nativeIdentity != null ? nativeIdentity.systemKey() :
                RadioSystemKey.channelScoped(protocol(protocol), identityDomain(addressDomainCode),
                    channel.configurationId());
        if(systemKey == null)
        {
            return null;
        }

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN)
        {
            if(addressDomainCode == IDENTITY_DOMAIN_STANDARD ||
                addressDomainCode != channel.configuredAddressDomainCode())
            {
                //The persisted saved-channel mode is authoritative. A queued event from an older Channel object or
                //a contradictory decoded identifier must not reclassify the current system.
                return null;
            }
        }
        else if(channel.configuredAddressDomainCode() != IDENTITY_DOMAIN_STANDARD)
        {
            return null;
        }
        RadioSystem radioSystem = batch != null ? batch.observeSystem(systemKey, observedAt, true) : null;
        if(radioSystem == null)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO radio_system (
                    system_key, configuration_id, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    dmr_model_code, dmr_network_id,
                    nxdn_location_category_code, nxdn_system_id, first_seen_ms, last_seen_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(system_key) DO UPDATE SET
                    first_seen_ms = min(radio_system.first_seen_ms, excluded.first_seen_ms),
                    last_seen_ms = max(radio_system.last_seen_ms, excluded.last_seen_ms)
                """))
            {
                statement.setString(1, systemKey);
                statement.setString(2, RadioSystemKey.isChannelScoped(systemKey) ? channel.configurationId() : null);
                statement.setInt(3, protocol);
                statement.setInt(4, addressDomainCode);
                setInteger(statement, 5, p25Wacn);
                setInteger(statement, 6, p25SystemId);
                setInteger(statement, 7, nativeIdentity != null ? nativeIdentity.dmrModelCode() : null);
                setInteger(statement, 8, nativeIdentity != null ? nativeIdentity.dmrNetworkId() : null);
                setInteger(statement, 9, nativeIdentity != null ? nativeIdentity.nxdnLocationCategoryCode() : null);
                setInteger(statement, 10, nativeIdentity != null ? nativeIdentity.nxdnSystemId() : null);
                statement.setLong(11, observedAt);
                statement.setLong(12, observedAt);
                statement.executeUpdate();
            }

            radioSystem = selectRadioSystem(connection, systemKey);
            if(radioSystem == null)
            {
                throw new SQLException("Missing radio system [" + systemKey + "]");
            }
            if(batch != null)
            {
                batch.rememberSystem(radioSystem);
            }
        }

        boolean assignmentChanged = channel.radioSystemId() == null ||
            channel.radioSystemId() != radioSystem.radioSystemId();
        boolean assignToChannel = true;

        if(capturedFallback && channel.radioSystemAssignedAtEpochMilliseconds() != null &&
            (channel.radioSystemId() == null || channel.currentSystemConfigurationId() == null))
        {
            //An event that began before native identity was learned retains its exact fallback owner, but absence of
            //native evidence can never demote or resolve a newer native generation.
            assignToChannel = false;
        }

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && assignmentChanged)
        {
            boolean conflictsWithConfiguredBinding = channel.hasConflictingConfiguredP25Binding(p25Wacn,
                p25SystemId);
            boolean conflictsWithDerivedBinding = channel.hasConflictingDerivedP25Binding(p25Wacn, p25SystemId);
            boolean verifiedConfiguredReplacement = verifiedP25SiteIdentity != null &&
                channel.matchesConfiguredP25Binding(verifiedP25SiteIdentity);

            if(conflictsWithConfiguredBinding || conflictsWithDerivedBinding && !verifiedConfiguredReplacement)
            {
                //Calls and delayed metadata may retain historical attribution, but only a verified site snapshot
                //matching an explicit saved binding may replace the current learned site generation.
                assignToChannel = false;
            }
        }

        if(assignToChannel && assignmentChanged && channel.radioSystemAssignedAtEpochMilliseconds() != null)
        {
            long assignmentStartedAt = channel.radioSystemAssignedAtEpochMilliseconds();

            //Any system-generation change requires a strictly newer observation. Delayed completed work may still
            //be attributed to its historical system, but can never move this channel back to an older system.
            assignToChannel = observedAt > assignmentStartedAt;
        }

        if(!assignToChannel)
        {
            //A complete delayed native observation can still belong to its historical radio system. Return it for
            //historical aggregation without changing the receiver channel's monotonic current assignment.
            return radioSystem;
        }

        if(assignmentChanged && channel.radioSystemId() != null)
        {
            clearReceiverChannelIdentityState(connection, channelId);
        }

        if(assignmentChanged)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                UPDATE receiver_channel
                SET radio_system_id = ?, radio_system_assigned_at_ms = ?
                WHERE id = ?
                """))
            {
                statement.setInt(1, radioSystem.radioSystemId());
                statement.setLong(2, observedAt);
                statement.setInt(3, channelId);
                statement.executeUpdate();
            }
        }

        return radioSystem;
    }

    /** Resolves the native P25 radio-system identity carried by a completed logical call. */
    static RadioSystem ensureP25RadioSystem(Connection connection, int wacn, int systemId,
                                            long observedAt) throws SQLException
    {
        String radioSystemKey = RadioSystemKey.p25(wacn, systemId);
        if(radioSystemKey == null || observedAt <= 0)
        {
            return null;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system(system_key, protocol_code, address_domain_code, p25_wacn,
                p25_system_id, first_seen_ms, last_seen_ms)
            VALUES (?, 1, 0, ?, ?, ?, ?)
            ON CONFLICT(system_key) DO UPDATE SET
                first_seen_ms = min(radio_system.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(radio_system.last_seen_ms, excluded.last_seen_ms)
            """))
        {
            statement.setString(1, radioSystemKey);
            statement.setInt(2, wacn);
            statement.setInt(3, systemId);
            statement.setLong(4, observedAt);
            statement.setLong(5, observedAt);
            statement.executeUpdate();
        }

        return selectRadioSystem(connection, radioSystemKey);
    }

    private static RadioSystem selectRadioSystem(Connection connection, String systemKey) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                dmr_model_code, dmr_network_id, nxdn_location_category_code, nxdn_system_id,
                first_seen_ms
            FROM radio_system WHERE system_key = ?
            """))
        {
            statement.setString(1, systemKey);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? new RadioSystem(resultSet.getInt("id"),
                    resultSet.getInt("protocol_code"), identityDomain(resultSet.getInt("address_domain_code")),
                    systemKey, nullableInteger(resultSet, "p25_wacn"),
                    nullableInteger(resultSet, "p25_system_id"), nullableInteger(resultSet, "dmr_model_code"),
                    nullableInteger(resultSet, "dmr_network_id"),
                    nullableInteger(resultSet, "nxdn_location_category_code"),
                    nullableInteger(resultSet, "nxdn_system_id"), resultSet.getLong("first_seen_ms")) : null;
            }
        }
    }

    private static RadioSystem selectRadioSystem(Connection connection, Integer radioSystemId) throws SQLException
    {
        if(radioSystemId == null)
        {
            return null;
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT system_key FROM radio_system WHERE id = ?"))
        {
            statement.setInt(1, radioSystemId);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? selectRadioSystem(connection, resultSet.getString(1)) : null;
            }
        }
    }

    private static void touchRadioSystem(Connection connection, Integer radioSystemId, long observedAt)
        throws SQLException
    {
        if(radioSystemId == null)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE radio_system SET last_seen_ms=max(last_seen_ms, ?) WHERE id=?"))
        {
            statement.setLong(1, observedAt);
            statement.setInt(2, radioSystemId);
            statement.executeUpdate();
        }
    }

    private static NativeSystemIdentity nativeSystemIdentity(int protocol, int addressDomainCode,
                                                             SiteSystemEvidence evidence)
    {
        if(evidence == null)
        {
            return null;
        }

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_DMR && evidence.variantCode() == DMR_VARIANT_TIER_III)
        {
            String model = dmrModelName(evidence.modelCode());
            String key = RadioSystemKey.dmrTier3(model, evidence.networkId());
            return key != null ? new NativeSystemIdentity(key, null, null, evidence.modelCode(),
                evidence.networkId(), null, null) : null;
        }

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN &&
            addressDomainCode == IDENTITY_DOMAIN_NXDN_TYPE_C &&
                evidence.variantCode() == NXDN_VARIANT_TYPE_C)
        {
            String category = nxdnLocationCategoryName(evidence.locationCategoryCode());
            String key = RadioSystemKey.nxdnTypeC(category, evidence.systemId());
            return key != null ? new NativeSystemIdentity(key, null, null, null, null,
                evidence.locationCategoryCode(), evidence.systemId()) : null;
        }

        return null;
    }

    private static NativeSystemIdentity nativeSystemIdentity(int protocol, int addressDomainCode, String systemKey)
    {
        if(systemKey == null || RadioSystemKey.isChannelScoped(systemKey))
        {
            return null;
        }

        try
        {
            String canonical = RadioSystemKey.nativeFor(protocol(protocol), identityDomain(addressDomainCode),
                systemKey);
            String[] parts = canonical.split(":");
            if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && parts.length == 3)
            {
                return new NativeSystemIdentity(canonical, Integer.parseInt(parts[1], 16),
                    Integer.parseInt(parts[2], 16), null, null, null, null);
            }
            if(protocol == TrunkedIdentityPolicy.PROTOCOL_DMR && parts.length == 4)
            {
                Integer modelCode = dmrModelCode(parts[2]);
                return modelCode != null ? new NativeSystemIdentity(canonical, null, null, modelCode,
                    Integer.parseInt(parts[3]), null, null) : null;
            }
            if(protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN && parts.length == 3)
            {
                Integer locationCode = nxdnLocationCategoryCode(parts[1]);
                return locationCode != null ? new NativeSystemIdentity(canonical, null, null, null, null,
                    locationCode, Integer.parseInt(parts[2])) : null;
            }
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }

        return null;
    }

    private static Integer dmrModelCode(String model)
    {
        return switch(model != null ? model : "")
        {
            case "tiny" -> DMR_MODEL_TINY;
            case "small" -> DMR_MODEL_SMALL;
            case "large" -> DMR_MODEL_LARGE;
            case "huge" -> DMR_MODEL_HUGE;
            default -> null;
        };
    }

    private static Integer nxdnLocationCategoryCode(String category)
    {
        return switch(category != null ? category : "")
        {
            case "global" -> NXDN_LOCATION_GLOBAL;
            case "regional" -> NXDN_LOCATION_REGIONAL;
            case "local" -> NXDN_LOCATION_LOCAL;
            default -> null;
        };
    }

    private static String dmrModelName(Integer code)
    {
        return switch(code != null ? code : 0)
        {
            case DMR_MODEL_TINY -> "tiny";
            case DMR_MODEL_SMALL -> "small";
            case DMR_MODEL_LARGE -> "large";
            case DMR_MODEL_HUGE -> "huge";
            default -> null;
        };
    }

    private static String nxdnLocationCategoryName(Integer code)
    {
        return switch(code != null ? code : 0)
        {
            case NXDN_LOCATION_GLOBAL -> "global";
            case NXDN_LOCATION_REGIONAL -> "regional";
            case NXDN_LOCATION_LOCAL -> "local";
            default -> null;
        };
    }

    /**
     * Clears receiver-owned projections before moving a channel to a different radio system. These rows join
     * through the channel's current radio system and protocol, so retaining them would relabel old calls as belonging
     * to the new system or protocol.
     */
    private static void clearReceiverChannelIdentityState(Connection connection, int channelId) throws SQLException
    {
        for(String table: List.of("p25_site_snapshot", "trunked_site_snapshot"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE channel_id = ?"))
            {
                statement.setInt(1, channelId);
                statement.executeUpdate();
            }
        }
        clearReceiverChannelPresence(connection, channelId);
    }

    /** Clears only site-local current radio evidence when the physical site generation changes. */
    static void clearReceiverChannelPresence(Connection connection, int channelId) throws SQLException
    {
        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence",
            "trunked_radio_channel_presence_clear"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE channel_id = ?"))
            {
                statement.setInt(1, channelId);
                statement.executeUpdate();
            }
        }
    }

    /** Clears receiver-owned identity evidence and its radio-system mapping when a newer site generation is known but its
     * complete normalized system key is not yet available. */
    static void clearReceiverChannelGeneration(Connection connection, int channelId, long observedAt)
        throws SQLException
    {
        clearReceiverChannelIdentityState(connection, channelId);
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE receiver_channel
            SET radio_system_id = NULL,
                radio_system_assigned_at_ms = max(coalesce(radio_system_assigned_at_ms, 0), ?)
            WHERE id = ?
            """))
        {
            statement.setLong(1, observedAt);
            statement.setInt(2, channelId);
            statement.executeUpdate();
        }
    }

    /** Advances the current-site generation without detaching an unchanged native radio system. */
    static void advanceReceiverChannelGeneration(Connection connection, int channelId, long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE receiver_channel
            SET radio_system_assigned_at_ms = max(coalesce(radio_system_assigned_at_ms, 0), ?)
            WHERE id = ? AND radio_system_id IS NOT NULL
            """))
        {
            statement.setLong(1, observedAt);
            statement.setInt(2, channelId);
            statement.executeUpdate();
        }
    }

    static int reset(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "p25_wuid_assignment_observation_summary");
        deleted += deleteAll(connection, "trunked_radio_affiliation");
        deleted += deleteAll(connection, "trunked_radio_channel_presence");
        deleted += deleteAll(connection, "trunked_radio_channel_presence_clear");
        deleted += deleteAll(connection, "trunked_radio_group_summary");
        deleted += deleteAll(connection, "radio_system_identity_summary");
        try(Statement statement = connection.createStatement())
        {
            deleted += statement.executeUpdate("""
                DELETE FROM p25_subscriber_identity
                WHERE NOT EXISTS (
                    SELECT 1 FROM alias_p25_subscriber_identity alias_identity
                    WHERE alias_identity.p25_subscriber_identity_id=p25_subscriber_identity.id
                )
                """);
        }
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                UPDATE receiver_channel
                SET radio_system_id = NULL, radio_system_assigned_at_ms = NULL
                WHERE radio_system_id IS NOT NULL OR radio_system_assigned_at_ms IS NOT NULL
                """);
        }
        deleted += deleteAll(connection, "radio_system");
        return deleted;
    }

    static int detachReceiverChannel(Connection connection, int channelId) throws SQLException
    {
        clearReceiverChannelPresence(connection, channelId);
        int deleted;
        try(PreparedStatement statement = connection.prepareStatement(
            """
            UPDATE receiver_channel
            SET radio_system_id = NULL, radio_system_assigned_at_ms = NULL
            WHERE id = ?
            """))
        {
            statement.setInt(1, channelId);
            deleted = statement.executeUpdate();
        }

        return deleted;
    }

    private static boolean upsertIdentity(Connection connection, RadioSystem radioSystem, Identity identity,
                                          long observedAt,
                                          ReceiverActivityRecords.Action action, IdentityCounts counts,
                                          Integer encryptionAlgorithm, Integer encryptionKey, String talkerAlias,
                                          Long talkerAliasSeen, ActivityBatch batch)
        throws SQLException
    {
        int radioSystemId = radioSystem.radioSystemId();
        if(batch != null && counts.equals(IdentityCounts.NONE) && encryptionAlgorithm == null &&
            encryptionKey == null && talkerAlias == null)
        {
            IdentityDelta delta = batch.identity(radioSystemId, identity);
            if(delta != null)
            {
                delta.observe(observedAt, action);
                return delta.mSummaryId > 0;
            }
        }

        if(!identityExists(connection, radioSystemId, identity) &&
            !hasSystemCapacity(connection, "radio_system_identity_summary", radioSystemId, MAX_IDENTITIES_PER_SYSTEM))
        {
            if(batch != null)
            {
                batch.rememberIdentity(radioSystemId, identity, 0);
            }
            return false;
        }

        Integer p25SubscriberIdentityId = null;
        if(radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_RADIO &&
            identity.stableCanonicalSubscriber())
        {
            int canonicalId = ensureP25SubscriberIdentity(connection, identity);
            p25SubscriberIdentityId = canonicalId > 0 ? canonicalId : null;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system_identity_summary (
                radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                first_seen_ms, last_seen_ms, %s,
                logical_call_count, source_logical_call_count, target_logical_call_count,
                encrypted_logical_call_count, recorded_output_count, streamed_output_count,
                last_encryption_algorithm_id, last_encryption_key_id,
                last_talker_alias, last_talker_alias_seen_ms, p25_subscriber_identity_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id) DO UPDATE SET
                first_seen_ms = min(radio_system_identity_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(radio_system_identity_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                logical_call_count = radio_system_identity_summary.logical_call_count + excluded.logical_call_count,
                source_logical_call_count = radio_system_identity_summary.source_logical_call_count +
                    excluded.source_logical_call_count,
                target_logical_call_count = radio_system_identity_summary.target_logical_call_count +
                    excluded.target_logical_call_count,
                encrypted_logical_call_count = radio_system_identity_summary.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = radio_system_identity_summary.recorded_output_count +
                    excluded.recorded_output_count,
                streamed_output_count = radio_system_identity_summary.streamed_output_count +
                    excluded.streamed_output_count,
                last_encryption_algorithm_id = CASE
                    WHEN excluded.last_encryption_algorithm_id IS NOT NULL
                         AND excluded.last_seen_ms >= radio_system_identity_summary.last_seen_ms
                    THEN excluded.last_encryption_algorithm_id
                    ELSE radio_system_identity_summary.last_encryption_algorithm_id
                END,
                last_encryption_key_id = CASE
                    WHEN excluded.last_encryption_key_id IS NOT NULL
                         AND excluded.last_seen_ms >= radio_system_identity_summary.last_seen_ms
                    THEN excluded.last_encryption_key_id
                    ELSE radio_system_identity_summary.last_encryption_key_id
                END,
                last_talker_alias = CASE
                    WHEN excluded.last_talker_alias IS NOT NULL
                         AND excluded.last_talker_alias_seen_ms >=
                             coalesce(radio_system_identity_summary.last_talker_alias_seen_ms, 0)
                    THEN excluded.last_talker_alias
                    ELSE radio_system_identity_summary.last_talker_alias
                END,
                last_talker_alias_seen_ms = CASE
                    WHEN excluded.last_talker_alias IS NOT NULL
                         AND excluded.last_talker_alias_seen_ms >=
                             coalesce(radio_system_identity_summary.last_talker_alias_seen_ms, 0)
                    THEN excluded.last_talker_alias_seen_ms
                    ELSE radio_system_identity_summary.last_talker_alias_seen_ms
                END,
                p25_subscriber_identity_id = coalesce(
                    excluded.p25_subscriber_identity_id,
                    radio_system_identity_summary.p25_subscriber_identity_id)
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("radio_system_identity_summary"))))
        {
            int index = 1;
            statement.setInt(index++, radioSystemId);
            statement.setInt(index++, identity.kindCode());
            statement.setInt(index++, identity.homeWacn());
            statement.setInt(index++, identity.homeSystemId());
            statement.setInt(index++, identity.id());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, counts.logicalCalls() > 0);
            statement.setInt(index++, counts.logicalCalls());
            statement.setInt(index++, counts.sourceCalls());
            statement.setInt(index++, counts.targetCalls());
            statement.setInt(index++, counts.encryptedCalls());
            statement.setInt(index++, counts.recordedOutputs());
            statement.setInt(index++, counts.streamedOutputs());
            setInteger(statement, index++, encryptionAlgorithm);
            setInteger(statement, index++, encryptionKey);
            String normalizedTalkerAlias = normalizedAlias(talkerAlias);
            statement.setString(index++, normalizedTalkerAlias);
            setLong(statement, index++, normalizedTalkerAlias != null ? talkerAliasSeen : null);
            setInteger(statement, index, p25SubscriberIdentityId);
            statement.executeUpdate();
        }

        if(batch != null)
        {
            batch.rememberIdentity(radioSystemId, identity,
                identitySummaryId(connection, radioSystemId, identity));
        }

        return true;
    }

    private static int ensureP25SubscriberIdentity(Connection connection, Identity identity) throws SQLException
    {
        if(identity == null || identity.kindCode() != TrunkedIdentityPolicy.IDENTITY_KIND_RADIO ||
            !identity.stableCanonicalSubscriber() ||
            identity.homeWacn() < 0 || identity.homeSystemId() < 0 || identity.id() <= 0 ||
            identity.id() > RadioSystemIdentityKey.MAX_P25_RADIO_ID)
        {
            return 0;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_subscriber_identity(home_wacn, home_system_id, subscriber_id)
            VALUES (?, ?, ?)
            ON CONFLICT(home_wacn, home_system_id, subscriber_id) DO NOTHING
            """))
        {
            statement.setInt(1, identity.homeWacn());
            statement.setInt(2, identity.homeSystemId());
            statement.setInt(3, identity.id());
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM p25_subscriber_identity
            WHERE home_wacn = ? AND home_system_id = ? AND subscriber_id = ?
            """))
        {
            statement.setInt(1, identity.homeWacn());
            statement.setInt(2, identity.homeSystemId());
            statement.setInt(3, identity.id());
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getInt(1);
                }
            }
        }
        throw new SQLException("Unable to resolve canonical P25 subscriber identity");
    }

    private static boolean upsertRelationship(Connection connection, int radioSystemId, Identity radio,
                                              Identity destination,
                                              long observedAt, ReceiverActivityRecords.Action action,
                                              boolean countedCall, int encrypted, int recorded, int streamed,
                                              Integer encryptionAlgorithm, Integer encryptionKey) throws SQLException
    {
        if(!identityExists(connection, radioSystemId, radio) || !identityExists(connection, radioSystemId, destination) ||
            !relationshipExists(connection, radioSystemId, radio, destination) &&
                !hasSystemCapacity(connection, "trunked_radio_group_summary", radioSystemId,
                    MAX_RELATIONSHIPS_PER_SYSTEM))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_group_summary (
                radio_system_id, radio_identity_id, group_identity_id, group_kind_code,
                first_seen_ms, last_seen_ms, %s,
                logical_call_count, encrypted_logical_call_count, recorded_output_count, streamed_output_count,
                last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id, group_identity_id) DO UPDATE SET
                first_seen_ms = min(trunked_radio_group_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_radio_group_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                logical_call_count = trunked_radio_group_summary.logical_call_count + excluded.logical_call_count,
                encrypted_logical_call_count = trunked_radio_group_summary.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = trunked_radio_group_summary.recorded_output_count +
                    excluded.recorded_output_count,
                streamed_output_count = trunked_radio_group_summary.streamed_output_count +
                    excluded.streamed_output_count,
                last_encryption_algorithm_id = CASE
                    WHEN excluded.last_encryption_algorithm_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_radio_group_summary.last_seen_ms
                    THEN excluded.last_encryption_algorithm_id
                    ELSE trunked_radio_group_summary.last_encryption_algorithm_id
                END,
                last_encryption_key_id = CASE
                    WHEN excluded.last_encryption_key_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_radio_group_summary.last_seen_ms
                    THEN excluded.last_encryption_key_id
                    ELSE trunked_radio_group_summary.last_encryption_key_id
                END
        """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("trunked_radio_group_summary"))))
        {
            int radioIdentityId = identitySummaryId(connection, radioSystemId, radio);
            int groupIdentityId = identitySummaryId(connection, radioSystemId, destination);
            int index = 1;
            statement.setInt(index++, radioSystemId);
            statement.setInt(index++, radioIdentityId);
            statement.setInt(index++, groupIdentityId);
            statement.setInt(index++, destination.kindCode());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, countedCall);
            statement.setInt(index++, countedCall ? 1 : 0);
            statement.setInt(index++, encrypted);
            statement.setInt(index++, recorded);
            statement.setInt(index++, streamed);
            setInteger(statement, index++, encryptionAlgorithm);
            setInteger(statement, index, encryptionKey);
            statement.executeUpdate();
        }

        return true;
    }

    private static boolean identityExists(Connection connection, int radioSystemId, Identity identity) throws SQLException
    {
        return identitySummaryId(connection, radioSystemId, identity) > 0;
    }

    private static int identitySummaryId(Connection connection, int radioSystemId, Identity identity) throws SQLException
    {
        if(identity == null)
        {
            return 0;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM radio_system_identity_summary
            WHERE radio_system_id = ? AND identity_kind_code = ?
              AND home_wacn = ? AND home_system_id = ? AND identity_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, identity.kindCode());
            statement.setInt(3, identity.homeWacn());
            statement.setInt(4, identity.homeSystemId());
            statement.setInt(5, identity.id());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private static boolean relationshipExists(Connection connection, int radioSystemId, Identity radio,
                                              Identity destination) throws SQLException
    {
        int radioIdentityId = identitySummaryId(connection, radioSystemId, radio);
        int groupIdentityId = identitySummaryId(connection, radioSystemId, destination);
        if(radioIdentityId <= 0 || groupIdentityId <= 0)
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM trunked_radio_group_summary
            WHERE radio_system_id = ? AND radio_identity_id = ? AND group_identity_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, radioIdentityId);
            statement.setInt(3, groupIdentityId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    /**
     * Index-backed admission check. The production writer is single-threaded, so the check and following insert are
     * serialized within one transaction.
     */
    static boolean hasSystemCapacity(Connection connection, String table, int radioSystemId, int maximumRows)
        throws SQLException
    {
        if(maximumRows <= 0 || (!"radio_system_identity_summary".equals(table) &&
            !"trunked_radio_group_summary".equals(table) &&
            !"p25_wuid_assignment_observation_summary".equals(table)))
        {
            throw new IllegalArgumentException("Invalid trunked identity admission bound");
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM " + table + " WHERE radio_system_id = ? LIMIT 1 OFFSET ?"))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, maximumRows - 1);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return !resultSet.next();
            }
        }
    }

    private static List<Identity> destinationIdentities(Connection connection, RadioSystem radioSystem,
                                                        String targetId, String targetKind,
                                                        List<Integer> patchMembers,
                                                        ReceiverActivityRecords.P25Identity p25TargetIdentity,
                                                        List<ReceiverActivityRecords.P25PatchMemberIdentity>
                                                            p25PatchMemberIdentities,
                                                        long observedAt) throws SQLException
    {
        Integer localTarget = integer(targetId);
        Integer kind = TrunkedIdentityPolicy.identityKindCode(targetKind);
        Map<String,Identity> identities = new LinkedHashMap<>();

        Identity target = resolvedIdentity(connection, radioSystem, kind, localTarget, p25TargetIdentity,
            observedAt);
        if(target != null)
        {
            mergeIdentity(identities, target);
        }

        if(kind != null && kind == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP &&
            radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 && patchMembers != null)
        {
            for(Integer member: patchMembers)
            {
                ReceiverActivityRecords.P25Identity memberIdentity = p25PatchMemberIdentities == null ?
                    ReceiverActivityRecords.P25Identity.ORDINARY : p25PatchMemberIdentities.stream()
                        .filter(candidate -> candidate.localTalkgroupId() == member)
                        .map(ReceiverActivityRecords.P25PatchMemberIdentity::targetIdentity)
                        .findFirst()
                        .orElse(ReceiverActivityRecords.P25Identity.ORDINARY);
                Identity patchMember = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP,
                    member, memberIdentity);
                if(patchMember != null)
                {
                    mergeIdentity(identities, patchMember);
                }
            }

            if(p25PatchMemberIdentities != null)
            {
                for(ReceiverActivityRecords.P25PatchMemberIdentity evidence: p25PatchMemberIdentities)
                {
                    Identity patchMember = identity(radioSystem,
                        TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, evidence.localTalkgroupId(),
                        evidence.targetIdentity());
                    if(patchMember != null)
                    {
                        mergeIdentity(identities, patchMember);
                    }
                }
            }
        }

        return List.copyOf(identities.values());
    }

    private static void mergeIdentity(Map<String,Identity> identities, Identity candidate)
    {
        identities.merge(candidate.key(), candidate, (first, second) ->
        {
            Integer firstLocal = first.localId();
            Integer secondLocal = second.localId();
            if(firstLocal == null || secondLocal != null && secondLocal < firstLocal)
            {
                return second;
            }
            return first;
        });
    }

    /** Builds an identity only from evidence carried by this observation; retained WUID history is never authority. */
    private static Identity resolvedIdentity(Connection connection, RadioSystem radioSystem, Integer kindCode,
                                             Integer localId, ReceiverActivityRecords.P25Identity p25Identity,
                                             long observedAt)
    {
        return identity(radioSystem, kindCode, localId, p25Identity);
    }

    private static Identity identity(RadioSystem radioSystem, Integer kindCode, Integer localId,
                                     ReceiverActivityRecords.P25Identity p25Identity)
    {
        if(radioSystem == null || kindCode == null)
        {
            return null;
        }

        ReceiverActivityRecords.P25Identity evidence = p25Identity != null ? p25Identity :
            ReceiverActivityRecords.P25Identity.UNKNOWN;
        int canonicalId;
        int homeWacn = -1;
        int homeSystemId = -1;

        if(radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 && evidence.isStableFullyQualified())
        {
            boolean patchGroupPrimary = kindCode == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP &&
                evidence.identityKindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP;
            if(evidence.identityKindCode() != kindCode && !patchGroupPrimary)
            {
                return null;
            }
            canonicalId = evidence.homeIdentityId();
            homeWacn = evidence.homeWacn();
            homeSystemId = evidence.homeSystemId();
        }
        else
        {
            if(radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
                evidence.state() != ReceiverActivityRecords.P25IdentityState.ORDINARY)
            {
                //Missing or malformed fully-qualified evidence must never be downgraded to an ordinary local ID.
                return null;
            }
            if(localId == null || localId <= 0)
            {
                return null;
            }
            canonicalId = localId;
            if(radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
                kindCode != TrunkedIdentityPolicy.IDENTITY_KIND_RADIO &&
                radioSystem.p25Wacn() != null && radioSystem.p25SystemId() != null)
            {
                homeWacn = radioSystem.p25Wacn();
                homeSystemId = radioSystem.p25SystemId();
            }
        }

        if(!TrunkedIdentityPolicy.isDirectoryIdentity(radioSystem.protocolCode(), radioSystem.identityDomain(),
            kindCode, canonicalId))
        {
            return null;
        }

        if(localId != null && !TrunkedIdentityPolicy.isObservedLocalIdentity(radioSystem.protocolCode(),
            radioSystem.identityDomain(), kindCode, localId, evidence.isStableFullyQualified()))
        {
            return null;
        }

        boolean stableCanonicalSubscriber = radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            kindCode == TrunkedIdentityPolicy.IDENTITY_KIND_RADIO && evidence.isStableFullyQualified();
        return new Identity(kindCode, canonicalId, homeWacn, homeSystemId,
            localId != null && localId >= 0 ? localId : null, stableCanonicalSubscriber);
    }

    private static List<Identity> groupDestinations(List<Identity> destinations)
    {
        return destinations.stream()
            .filter(identity -> identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP ||
                identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP)
            .toList();
    }

    static IdentityReference identityReference(Connection connection, RadioSystem radioSystem, int kindCode,
                                               Integer observedLocalId,
                                               ReceiverActivityRecords.P25Identity p25Identity,
                                               long observedAt) throws SQLException
    {
        Identity identity = resolvedIdentity(connection, radioSystem, kindCode, observedLocalId, p25Identity,
            observedAt);
        int summaryId = identitySummaryId(connection, radioSystem != null ? radioSystem.radioSystemId() : 0,
            identity);
        return summaryId > 0 ? new IdentityReference(summaryId, identity.kindCode(), identity.localId(),
            identity.key()) : null;
    }

    static List<IdentityReference> destinationIdentityReferences(Connection connection, RadioSystem radioSystem,
                                                                 String targetId, String targetKind,
                                                                 List<Integer> patchMembers,
                                                                 ReceiverActivityRecords.P25Identity p25TargetIdentity,
                                                                 List<ReceiverActivityRecords.P25PatchMemberIdentity>
                                                                     p25PatchMemberIdentities,
                                                                 long observedAt) throws SQLException
    {
        List<IdentityReference> references = new ArrayList<>();
        for(Identity identity: destinationIdentities(connection, radioSystem, targetId, targetKind, patchMembers,
            p25TargetIdentity, p25PatchMemberIdentities, observedAt))
        {
            int summaryId = identitySummaryId(connection, radioSystem.radioSystemId(), identity);
            if(summaryId > 0)
            {
                references.add(new IdentityReference(summaryId, identity.kindCode(), identity.localId(),
                    identity.key()));
            }
        }
        return List.copyOf(references);
    }

    private static ReceiverChannel receiverChannel(Connection connection, int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.id, channel.configuration_id, channel.radio_system_id,
                channel.radio_system_assigned_at_ms,
                system.system_key, system.configuration_id AS system_configuration_id,
                system.address_domain_code AS system_address_domain_code,
                system.p25_wacn, system.p25_system_id, configured.decoder_type,
                configured.address_domain_code,
                CASE WHEN json_type(configured.config_json, '$.p25SiteIdentity.wacn')='integer'
                     THEN json_extract(configured.config_json, '$.p25SiteIdentity.wacn') END AS configured_p25_wacn,
                CASE WHEN json_type(configured.config_json, '$.p25SiteIdentity.system')='integer'
                     THEN json_extract(configured.config_json, '$.p25SiteIdentity.system') END AS configured_p25_system_id,
                CASE WHEN json_type(configured.config_json, '$.p25SiteIdentity.rfss')='integer'
                     THEN json_extract(configured.config_json, '$.p25SiteIdentity.rfss') END AS configured_p25_rfss,
                CASE WHEN json_type(configured.config_json, '$.p25SiteIdentity.site')='integer'
                     THEN json_extract(configured.config_json, '$.p25SiteIdentity.site') END AS configured_p25_site,
                EXISTS (
                    SELECT 1 FROM p25_site_snapshot site
                    WHERE site.channel_id=channel.id AND site.rfss IS NOT NULL AND site.site IS NOT NULL
                ) AS complete_p25_site_binding
            FROM receiver_channel channel
            JOIN configuration_channel configured
              ON configured.configuration_id = channel.configuration_id
            LEFT JOIN radio_system system ON system.id = channel.radio_system_id
            WHERE channel.id = ? AND configured.channel_kind = 'TRUNKED'
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return null;
                }

                return new ReceiverChannel(resultSet.getInt("id"), resultSet.getString("configuration_id"),
                    ReceiverActivitySchema.protocolCodeForDecoder(resultSet.getString("decoder_type")),
                    resultSet.getInt("address_domain_code"),
                    nullableInteger(resultSet, "radio_system_id"),
                    nullableLong(resultSet, "radio_system_assigned_at_ms"),
                    resultSet.getString("system_key"), resultSet.getString("system_configuration_id"),
                    resultSet.getObject("system_address_domain_code") != null ?
                        resultSet.getInt("system_address_domain_code") : IDENTITY_DOMAIN_STANDARD,
                    nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"),
                    nullableInteger(resultSet, "configured_p25_wacn"),
                    nullableInteger(resultSet, "configured_p25_system_id"),
                    nullableInteger(resultSet, "configured_p25_rfss"),
                    nullableInteger(resultSet, "configured_p25_site"),
                    resultSet.getInt("complete_p25_site_binding") != 0);
            }
        }
    }

    private static int addressDomainCode(int protocolCode, TrunkedIdentityDomain domain)
    {
        if(protocolCode != TrunkedIdentityPolicy.PROTOCOL_NXDN || domain == null)
        {
            return IDENTITY_DOMAIN_STANDARD;
        }

        return switch(domain)
        {
            case NXDN_TYPE_C -> IDENTITY_DOMAIN_NXDN_TYPE_C;
            case NXDN_TYPE_D -> IDENTITY_DOMAIN_NXDN_TYPE_D;
            default -> IDENTITY_DOMAIN_STANDARD;
        };
    }

    private static Protocol protocol(int protocolCode)
    {
        return switch(protocolCode)
        {
            case TrunkedIdentityPolicy.PROTOCOL_P25 -> Protocol.APCO25;
            case TrunkedIdentityPolicy.PROTOCOL_DMR -> Protocol.DMR;
            case TrunkedIdentityPolicy.PROTOCOL_NXDN -> Protocol.NXDN;
            default -> Protocol.UNKNOWN;
        };
    }

    private static TrunkedIdentityDomain identityDomain(int code)
    {
        return switch(code)
        {
            case IDENTITY_DOMAIN_NXDN_TYPE_C -> TrunkedIdentityDomain.NXDN_TYPE_C;
            case IDENTITY_DOMAIN_NXDN_TYPE_D -> TrunkedIdentityDomain.NXDN_TYPE_D;
            default -> TrunkedIdentityDomain.STANDARD;
        };
    }

    private static String actionUpdateSql(String table)
    {
        return ACTION_COUNT_COLUMNS.stream()
            .map(column -> column + " = " + table + "." + column + " + excluded." + column)
            .collect(Collectors.joining(",\n                "));
    }

    private static int setActionCounts(PreparedStatement statement, int index,
                                       ReceiverActivityRecords.Action activityAction, boolean countedCall)
        throws SQLException
    {
        for(ReceiverActivityRecords.Action action: ACTIONS)
        {
            boolean counted = activityAction == action;

            if(action == ReceiverActivityRecords.Action.CALL)
            {
                counted = countedCall;
            }

            if(action == ReceiverActivityRecords.Action.UNKNOWN)
            {
                counted = false;
            }

            statement.setInt(index++, counted ? 1 : 0);
        }

        return index;
    }

    private static void validateRadioSystemKeys(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, system_key, configuration_id, protocol_code, address_domain_code,
                       p25_wacn, p25_system_id, dmr_model_code, dmr_network_id,
                       nxdn_location_category_code, nxdn_system_id
                FROM radio_system
                ORDER BY id
                """))
        {
            while(resultSet.next())
            {
                String key = resultSet.getString("system_key");
                int protocolCode = resultSet.getInt("protocol_code");
                String configurationId = resultSet.getString("configuration_id");
                Integer wacn = nullableInteger(resultSet, "p25_wacn");
                Integer systemId = nullableInteger(resultSet, "p25_system_id");
                Integer dmrModelCode = nullableInteger(resultSet, "dmr_model_code");
                Integer dmrNetworkId = nullableInteger(resultSet, "dmr_network_id");
                Integer nxdnLocationCategoryCode = nullableInteger(resultSet, "nxdn_location_category_code");
                Integer nxdnSystemId = nullableInteger(resultSet, "nxdn_system_id");
                String expected;

                if(wacn != null && systemId != null)
                {
                    expected = RadioSystemKey.p25(wacn, systemId);
                }
                else if(dmrModelCode != null && dmrNetworkId != null)
                {
                    expected = RadioSystemKey.dmrTier3(dmrModelName(dmrModelCode), dmrNetworkId);
                }
                else if(nxdnLocationCategoryCode != null && nxdnSystemId != null)
                {
                    expected = RadioSystemKey.nxdnTypeC(
                        nxdnLocationCategoryName(nxdnLocationCategoryCode), nxdnSystemId);
                }
                else
                {
                    configurationId = ChannelConfigurationKey.canonical(configurationId);
                    expected = configurationId != null ? RadioSystemKey.channelScoped(protocol(protocolCode),
                        identityDomain(resultSet.getInt("address_domain_code")), configurationId) : null;
                }

                if(expected == null || !expected.equals(key))
                {
                    throw new SQLException("Radio system [" + resultSet.getInt("id") +
                        "] has a noncanonical key");
                }
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT channel.id
                FROM receiver_channel channel
                JOIN radio_system system ON system.id=channel.radio_system_id
                JOIN trunked_site_snapshot site ON site.channel_id=channel.id
                WHERE site.protocol_code<>system.protocol_code
                   OR (system.protocol_code=3 AND system.dmr_model_code IS NOT NULL AND
                       (site.variant_code<>1 OR site.observed_model_code IS NULL OR
                        site.observed_network_id IS NULL OR
                        site.observed_model_code<>system.dmr_model_code OR
                        site.observed_network_id<>system.dmr_network_id))
                   OR (system.protocol_code=4 AND system.nxdn_location_category_code IS NOT NULL AND
                       (site.variant_code<>1 OR site.observed_location_category_code=0 OR
                        site.observed_system_id IS NULL OR
                        site.observed_location_category_code<>system.nxdn_location_category_code OR
                        site.observed_system_id<>system.nxdn_system_id))
                   OR (system.configuration_id IS NOT NULL AND
                       ((site.protocol_code=3 AND site.variant_code=1 AND
                         site.observed_model_code IS NOT NULL AND site.observed_network_id IS NOT NULL) OR
                        (site.protocol_code=4 AND system.address_domain_code=1 AND site.variant_code=1 AND
                         site.observed_location_category_code BETWEEN 1 AND 3 AND
                         site.observed_system_id IS NOT NULL)))
                LIMIT 1
                """))
        {
            if(resultSet.next())
            {
                throw new SQLException("Receiver channel [" + resultSet.getInt(1) +
                    "] has site evidence that contradicts its radio system");
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                WITH configured_p25 AS (
                    SELECT configuration_id,
                        json_extract(config_json, '$.p25SiteIdentity.wacn') AS wacn,
                        json_extract(config_json, '$.p25SiteIdentity.system') AS system_id,
                        json_extract(config_json, '$.p25SiteIdentity.rfss') AS rfss,
                        json_extract(config_json, '$.p25SiteIdentity.site') AS site
                    FROM configuration_channel
                    WHERE channel_kind='TRUNKED' AND decoder_type IN ('P25_PHASE1', 'P25_PHASE2')
                      AND json_type(config_json, '$.p25SiteIdentity.wacn')='integer'
                      AND json_type(config_json, '$.p25SiteIdentity.system')='integer'
                      AND json_type(config_json, '$.p25SiteIdentity.rfss')='integer'
                      AND json_type(config_json, '$.p25SiteIdentity.site')='integer'
                )
                SELECT channel.id
                FROM configured_p25 configured
                JOIN receiver_channel channel ON channel.configuration_id=configured.configuration_id
                LEFT JOIN radio_system system ON system.id=channel.radio_system_id
                LEFT JOIN p25_site_snapshot site ON site.channel_id=channel.id
                WHERE (system.p25_wacn IS NOT NULL AND system.p25_wacn<>configured.wacn)
                   OR (system.p25_system_id IS NOT NULL AND system.p25_system_id<>configured.system_id)
                   OR (site.rfss IS NOT NULL AND site.rfss<>configured.rfss)
                   OR (site.site IS NOT NULL AND site.site<>configured.site)
                LIMIT 1
                """))
        {
            if(resultSet.next())
            {
                throw new SQLException("Receiver channel [" + resultSet.getInt(1) +
                    "] has a learned P25 site that contradicts its saved binding");
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT channel.id, channel.configuration_id, system.system_key, system.configuration_id AS
                    system_configuration_id, system.protocol_code,
                    system.address_domain_code AS system_address_domain_code,
                    configured.channel_kind, configured.decoder_type, configured.address_domain_code
                FROM receiver_channel channel
                JOIN radio_system system ON system.id = channel.radio_system_id
                JOIN configuration_channel configured
                  ON configured.configuration_id = channel.configuration_id
                ORDER BY channel.id
                """))
        {
            while(resultSet.next())
            {
                int configuredProtocol = TrunkedIdentityPolicy.protocolFamilyCode(
                    ReceiverActivitySchema.protocolCodeForDecoder(resultSet.getString("decoder_type")));
                int systemProtocol = resultSet.getInt("protocol_code");
                String key = resultSet.getString("system_key");
                String configurationId = resultSet.getString("configuration_id");
                String systemConfigurationId = resultSet.getString("system_configuration_id");
                boolean nativeSystem = systemConfigurationId == null;
                String configuredKey = RadioSystemKey.channelScoped(protocol(systemProtocol),
                    identityDomain(resultSet.getInt("address_domain_code")), configurationId);

                if(!"TRUNKED".equals(resultSet.getString("channel_kind")) ||
                    configuredProtocol != systemProtocol ||
                    resultSet.getInt("address_domain_code") != resultSet.getInt("system_address_domain_code") ||
                    (!nativeSystem && !configurationId.equals(systemConfigurationId)) ||
                    (!nativeSystem && !key.equals(configuredKey)) ||
                    (nativeSystem && !RadioSystemKey.isCanonical(key)))
                {
                    throw new SQLException("Receiver channel [" + resultSet.getInt("id") +
                        "] is attached to an incompatible radio system");
                }
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT issue FROM (
                    SELECT 'P25 home identity on a non-P25 radio system' AS issue
                    FROM radio_system_identity_summary summary
                    JOIN radio_system system ON system.id=summary.radio_system_id
                    WHERE system.protocol_code<>1
                      AND (summary.home_wacn<>-1 OR summary.home_system_id<>-1)
                    UNION ALL
                    SELECT 'unscoped identity on a native P25 radio system'
                    FROM radio_system_identity_summary summary
                    JOIN radio_system system ON system.id=summary.radio_system_id
                    WHERE system.protocol_code=1 AND system.p25_wacn IS NOT NULL
                      AND (summary.home_wacn=-1 OR summary.home_system_id=-1)
                      AND NOT (summary.identity_kind_code=2
                        AND summary.home_wacn=-1 AND summary.home_system_id=-1
                        AND summary.p25_subscriber_identity_id IS NULL)
                    UNION ALL
                    SELECT 'learned P25 site on a non-P25 radio system'
                    FROM p25_learned_site site
                    JOIN radio_system system ON system.id=site.radio_system_id
                    WHERE system.protocol_code<>1
                ) LIMIT 1
                """))
        {
            if(resultSet.next())
            {
                throw new SQLException(resultSet.getString("issue"));
            }
        }

        validateIdentitySemantics(connection);
        validateP25SubscriberIdentitySemantics(connection);
        validateObservedLocalIdentitySemantics(connection);
    }

    private static void validateP25SubscriberIdentitySemantics(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT issue FROM (
                    SELECT 'radio-system identity references a different canonical P25 subscriber' AS issue
                    FROM radio_system_identity_summary summary
                    JOIN radio_system system ON system.id=summary.radio_system_id
                    JOIN p25_subscriber_identity subscriber ON subscriber.id=summary.p25_subscriber_identity_id
                    WHERE system.protocol_code<>1 OR summary.identity_kind_code<>2
                       OR summary.home_wacn<>subscriber.home_wacn
                       OR summary.home_system_id<>subscriber.home_system_id
                       OR summary.identity_id<>subscriber.subscriber_id
                    UNION ALL
                    SELECT 'P25 WUID assignment observation belongs to a non-P25 radio system'
                    FROM p25_wuid_assignment_observation_summary assignment
                    JOIN radio_system system ON system.id=assignment.radio_system_id
                    WHERE system.protocol_code<>1
                ) LIMIT 1
                """))
        {
            if(resultSet.next())
            {
                throw new SQLException(resultSet.getString("issue"));
            }
        }
    }

    /** SQLite cannot make the child identity range depend on its parent protocol without duplicating protocol. */
    private static void validateIdentitySemantics(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT summary.id, summary.identity_kind_code, summary.home_wacn,
                    summary.home_system_id, summary.identity_id, summary.p25_subscriber_identity_id,
                    system.protocol_code, system.address_domain_code
                FROM radio_system_identity_summary summary
                JOIN radio_system system ON system.id = summary.radio_system_id
                ORDER BY summary.id
                """))
        {
            while(resultSet.next())
            {
                int summaryId = resultSet.getInt("id");
                int protocolCode = resultSet.getInt("protocol_code");
                int kindCode = resultSet.getInt("identity_kind_code");
                int homeWacn = resultSet.getInt("home_wacn");
                int homeSystemId = resultSet.getInt("home_system_id");
                int identityId = resultSet.getInt("identity_id");
                TrunkedIdentityDomain domain =
                    identityDomain(resultSet.getInt("address_domain_code"));
                boolean p25 = protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25;
                boolean ordinaryP25Radio = p25 && kindCode == TrunkedIdentityPolicy.IDENTITY_KIND_RADIO &&
                    homeWacn == RadioSystemIdentityKey.NO_HOME &&
                    homeSystemId == RadioSystemIdentityKey.NO_HOME &&
                    resultSet.getObject("p25_subscriber_identity_id") == null;
                boolean validHome = p25 ? ordinaryP25Radio || homeWacn >= 0 && homeSystemId >= 0 :
                    homeWacn == RadioSystemIdentityKey.NO_HOME &&
                        homeSystemId == RadioSystemIdentityKey.NO_HOME;
                boolean validCanonical = TrunkedIdentityPolicy.isDirectoryIdentity(protocolCode, domain,
                    kindCode, identityId);

                try
                {
                    new RadioSystemIdentityKey.Identity(kindCode, homeWacn, homeSystemId, identityId);
                }
                catch(IllegalArgumentException exception)
                {
                    validCanonical = false;
                }

                if(!validHome || !validCanonical)
                {
                    throw new SQLException("Radio-system identity summary [" + summaryId +
                        "] is incompatible with its protocol and address domain");
                }
            }
        }
    }

    /** Child evidence ranges depend on the protocol and, for NXDN, the saved channel address domain. */
    private static void validateObservedLocalIdentitySemantics(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT affiliation.radio_system_id, affiliation.radio_identity_id,
                    affiliation.radio_observed_local_id, affiliation.talkgroup_observed_local_id,
                    system.protocol_code, system.address_domain_code
                FROM trunked_radio_affiliation affiliation
                JOIN radio_system system ON system.id = affiliation.radio_system_id
                ORDER BY affiliation.radio_system_id, affiliation.radio_identity_id
                """))
        {
            while(resultSet.next())
            {
                int protocolCode = resultSet.getInt("protocol_code");
                TrunkedIdentityDomain domain = identityDomain(resultSet.getInt("address_domain_code"));
                Integer radioLocalId = nullableInteger(resultSet, "radio_observed_local_id");
                Integer talkgroupLocalId = nullableInteger(resultSet, "talkgroup_observed_local_id");

                if(!isValidObservedLocalIdentity(protocolCode, domain,
                    TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioLocalId) ||
                    !isValidObservedLocalIdentity(protocolCode, domain,
                        TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, talkgroupLocalId))
                {
                    throw new SQLException("Trunked radio affiliation [system=" +
                        resultSet.getInt("radio_system_id") + ", radio=" +
                        resultSet.getInt("radio_identity_id") +
                        "] has local identity evidence incompatible with its protocol and address domain");
                }
            }
        }

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT 'trunked_radio_channel_presence' AS child_table,
                    presence.radio_system_id, presence.radio_identity_id, presence.channel_id,
                    presence.observed_local_id, system.protocol_code, system.address_domain_code
                FROM trunked_radio_channel_presence presence
                JOIN radio_system system ON system.id = presence.radio_system_id
                UNION ALL
                SELECT 'trunked_radio_channel_presence_clear',
                    presence_clear.radio_system_id, presence_clear.radio_identity_id,
                    presence_clear.channel_id, presence_clear.observed_local_id,
                    system.protocol_code, system.address_domain_code
                FROM trunked_radio_channel_presence_clear presence_clear
                JOIN radio_system system ON system.id = presence_clear.radio_system_id
                ORDER BY child_table, radio_system_id, radio_identity_id, channel_id
                """))
        {
            while(resultSet.next())
            {
                int protocolCode = resultSet.getInt("protocol_code");
                TrunkedIdentityDomain domain = identityDomain(resultSet.getInt("address_domain_code"));
                Integer localId = nullableInteger(resultSet, "observed_local_id");

                if(!isValidObservedLocalIdentity(protocolCode, domain,
                    TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, localId))
                {
                    throw new SQLException("Trunked radio presence evidence in [" +
                        resultSet.getString("child_table") + "; system=" +
                        resultSet.getInt("radio_system_id") + ", radio=" +
                        resultSet.getInt("radio_identity_id") + ", channel=" +
                        resultSet.getInt("channel_id") +
                        "] is incompatible with its protocol and address domain");
                }
            }
        }
    }

    private static boolean isValidObservedLocalIdentity(int protocolCode, TrunkedIdentityDomain domain,
                                                        int identityKindCode, Integer localId)
    {
        return localId == null || TrunkedIdentityPolicy.isObservedLocalIdentity(protocolCode, domain,
            identityKindCode, localId, protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25);
    }

    private static void validatePrimaryKey(Connection connection, String table, List<String> expected)
        throws SQLException
    {
        List<KeyColumn> columns = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                int ordinal = resultSet.getInt("pk");

                if(ordinal > 0)
                {
                    columns.add(new KeyColumn(ordinal, resultSet.getString("name")));
                }
            }
        }

        columns.sort(Comparator.comparingInt(KeyColumn::ordinal));
        List<String> actual = columns.stream().map(KeyColumn::name).toList();

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite table [" + table + "] has primary key " + actual +
                "; expected exactly " + expected);
        }
    }

    private static void validateForeignKeys(Connection connection, String table, Set<ForeignKey> expected)
        throws SQLException
    {
        Set<ForeignKey> actual = new LinkedHashSet<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")"))
        {
            while(resultSet.next())
            {
                actual.add(new ForeignKey(resultSet.getString("from"), resultSet.getString("table"),
                    resultSet.getString("to"), resultSet.getString("on_delete").toUpperCase(Locale.ROOT)));
            }
        }

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite table [" + table + "] has foreign keys " + actual +
                "; expected exactly " + expected);
        }
    }

    private static void validateIndex(Connection connection, String index, List<IndexColumn> expected)
        throws SQLException
    {
        List<IndexColumn> actual = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA index_xinfo(" + index + ")"))
        {
            while(resultSet.next())
            {
                if(resultSet.getInt("key") == 1)
                {
                    actual.add(new IndexColumn(resultSet.getInt("seqno"), resultSet.getString("name"),
                        resultSet.getInt("desc") == 1));
                }
            }
        }

        actual.sort(Comparator.comparingInt(IndexColumn::ordinal));

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite index [" + index + "] has key columns " + actual +
                "; expected exactly " + expected);
        }
    }

    private static int deleteAll(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            return statement.executeUpdate("DELETE FROM " + table);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException
    {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer positive(String value)
    {
        if(value == null)
        {
            return null;
        }

        try
        {
            return positive(Integer.parseInt(value));
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private static Integer integer(String value)
    {
        if(value == null)
        {
            return null;
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }

    private static Integer positive(Integer value)
    {
        return value != null && value > 0 ? value : null;
    }

    private static String normalizedAlias(String alias)
    {
        if(alias == null || alias.isBlank())
        {
            return null;
        }

        String normalized = alias.strip();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if(codePointCount <= MAX_TALKER_ALIAS_CHARACTERS)
        {
            return normalized;
        }

        int end = normalized.offsetByCodePoints(0, MAX_TALKER_ALIAS_CHARACTERS);
        return normalized.substring(0, end);
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value);
        }
        else
        {
            statement.setNull(index, java.sql.Types.INTEGER);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(index, value);
        }
        else
        {
            statement.setNull(index, java.sql.Types.BIGINT);
        }
    }

    record RadioSystem(int radioSystemId, int protocolCode, TrunkedIdentityDomain identityDomain,
                 String systemKey, Integer p25Wacn, Integer p25SystemId, Integer dmrModelCode,
                 Integer dmrNetworkId, Integer nxdnLocationCategoryCode, Integer nxdnSystemId,
                 long firstSeenEpochMilliseconds)
    {
    }

    record IdentityReference(int summaryId, int kindCode, Integer observedLocalId, String identityKey)
    {
    }

    private static final class SystemDelta
    {
        private final RadioSystem mRadioSystem;
        private Long mFirstSeen;
        private long mLastSeen;

        private SystemDelta(RadioSystem radioSystem)
        {
            mRadioSystem = radioSystem;
        }

        private RadioSystem observe(long observedAt, boolean updateFirstSeen)
        {
            mLastSeen = Math.max(mLastSeen, observedAt);
            if(updateFirstSeen)
            {
                mFirstSeen = mFirstSeen == null ? observedAt : Math.min(mFirstSeen, observedAt);
            }

            long firstSeen = mFirstSeen != null ?
                Math.min(mRadioSystem.firstSeenEpochMilliseconds(), mFirstSeen) :
                mRadioSystem.firstSeenEpochMilliseconds();
            return new RadioSystem(mRadioSystem.radioSystemId(), mRadioSystem.protocolCode(),
                mRadioSystem.identityDomain(), mRadioSystem.systemKey(), mRadioSystem.p25Wacn(),
                mRadioSystem.p25SystemId(), mRadioSystem.dmrModelCode(), mRadioSystem.dmrNetworkId(),
                mRadioSystem.nxdnLocationCategoryCode(), mRadioSystem.nxdnSystemId(), firstSeen);
        }
    }

    private record IdentityKey(int radioSystemId, int kindCode, int homeWacn, int homeSystemId, int identityId)
    {
        private static IdentityKey from(int radioSystemId, Identity identity)
        {
            return new IdentityKey(radioSystemId, identity.kindCode(), identity.homeWacn(), identity.homeSystemId(),
                identity.id());
        }
    }

    private static final class IdentityDelta
    {
        private final int mSummaryId;
        private final int[] mActionCounts = new int[ACTIONS.size()];
        private long mFirstSeen;
        private long mLastSeen;

        private IdentityDelta(int summaryId)
        {
            mSummaryId = summaryId;
        }

        private void observe(long observedAt, ReceiverActivityRecords.Action action)
        {
            if(mSummaryId <= 0)
            {
                return;
            }

            if(mLastSeen <= 0)
            {
                mFirstSeen = observedAt;
                mLastSeen = observedAt;
            }
            else
            {
                mFirstSeen = Math.min(mFirstSeen, observedAt);
                mLastSeen = Math.max(mLastSeen, observedAt);
            }

            int actionIndex = action != ReceiverActivityRecords.Action.UNKNOWN ? ACTIONS.indexOf(action) : -1;
            if(actionIndex >= 0)
            {
                mActionCounts[actionIndex]++;
            }
        }
    }

    /** Names the only counters a directory upsert may own; metadata-only observations use {@link #NONE}. */
    private record IdentityCounts(int logicalCalls, int sourceCalls, int targetCalls, int encryptedCalls,
                                  int recordedOutputs, int streamedOutputs)
    {
        private static final IdentityCounts NONE = new IdentityCounts(0, 0, 0, 0, 0, 0);

        private static IdentityCounts targetResolvedCall(int encrypted)
        {
            return new IdentityCounts(1, 0, 1, encrypted, 0, 0);
        }

        private static IdentityCounts sourceResolvedCall(int encrypted, boolean ownsOverallCall)
        {
            return new IdentityCounts(ownsOverallCall ? 1 : 0, 1, 0, encrypted, 0, 0);
        }

        private static IdentityCounts outputs(int recorded, int streamed)
        {
            return new IdentityCounts(0, 0, 0, 0, recorded, streamed);
        }
    }

    private record ReceiverChannel(int id, String configurationId, Integer protocolCode,
                                   int configuredAddressDomainCode, Integer radioSystemId,
                                   Long radioSystemAssignedAtEpochMilliseconds,
                                   String currentSystemKey, String currentSystemConfigurationId,
                                   int currentSystemAddressDomainCode,
                                   Integer currentP25Wacn, Integer currentP25SystemId,
                                   Integer configuredP25Wacn, Integer configuredP25SystemId,
                                   Integer configuredP25Rfss, Integer configuredP25Site,
                                   boolean completeP25SiteSnapshot)
    {
        private boolean hasCompleteConfiguredP25SiteBinding()
        {
            return configuredP25Wacn != null && configuredP25SystemId != null && configuredP25Rfss != null &&
                configuredP25Site != null;
        }

        private boolean hasConflictingConfiguredP25Binding(Integer observedWacn, Integer observedSystemId)
        {
            return hasCompleteConfiguredP25SiteBinding() &&
                (!configuredP25Wacn.equals(observedWacn) || !configuredP25SystemId.equals(observedSystemId));
        }

        private boolean hasConflictingDerivedP25Binding(Integer observedWacn, Integer observedSystemId)
        {
            return completeP25SiteSnapshot && currentP25Wacn != null && currentP25SystemId != null &&
                (!currentP25Wacn.equals(observedWacn) || !currentP25SystemId.equals(observedSystemId));
        }

        private boolean matchesConfiguredP25Binding(P25SiteIdentity observed)
        {
            return hasCompleteConfiguredP25SiteBinding() && observed != null &&
                configuredP25Wacn == observed.wacn() && configuredP25SystemId == observed.system() &&
                configuredP25Rfss == observed.rfss() && configuredP25Site == observed.site();
        }
    }

    private record SiteSystemEvidence(int variantCode, Integer modelCode, Integer networkId,
                                      Integer locationCategoryCode, Integer systemId)
    {
        private boolean supportsNativeVariant(int protocolCode, int addressDomainCode)
        {
            return protocolCode == TrunkedIdentityPolicy.PROTOCOL_DMR && variantCode == DMR_VARIANT_TIER_III ||
                protocolCode == TrunkedIdentityPolicy.PROTOCOL_NXDN && variantCode == NXDN_VARIANT_TYPE_C &&
                    addressDomainCode == IDENTITY_DOMAIN_NXDN_TYPE_C;
        }
    }

    private record NativeSystemIdentity(String systemKey, Integer p25Wacn, Integer p25SystemId,
                                        Integer dmrModelCode, Integer dmrNetworkId,
                                        Integer nxdnLocationCategoryCode, Integer nxdnSystemId)
    {
    }

    private record Identity(int kindCode, int id, int homeWacn, int homeSystemId, Integer localId,
                            boolean stableCanonicalSubscriber)
    {
        private Identity(int kindCode, int id, int homeWacn, int homeSystemId, Integer localId)
        {
            this(kindCode, id, homeWacn, homeSystemId, localId, false);
        }

        private Identity
        {
            new RadioSystemIdentityKey.Identity(kindCode, homeWacn, homeSystemId, id);
            if(stableCanonicalSubscriber && kindCode != TrunkedIdentityPolicy.IDENTITY_KIND_RADIO)
            {
                throw new IllegalArgumentException("Only radio identities can be canonical P25 subscribers");
            }
            int localMaximum = kindCode == TrunkedIdentityPolicy.IDENTITY_KIND_RADIO ?
                RadioSystemIdentityKey.MAX_OTHER_RADIO_ID :
                kindCode == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP ?
                    RadioSystemIdentityKey.MAX_P25_GROUP_ID : RadioSystemIdentityKey.MAX_OTHER_GROUP_ID;
            if(localId != null && (localId < 0 || localId > localMaximum))
            {
                throw new IllegalArgumentException("Invalid observed local radio-system identity");
            }
        }

        String key()
        {
            return RadioSystemIdentityKey.format(kindCode, homeWacn, homeSystemId, id);
        }

        boolean sameCanonicalIdentity(Identity other)
        {
            return other != null && kindCode == other.kindCode && id == other.id &&
                homeWacn == other.homeWacn && homeSystemId == other.homeSystemId;
        }
    }

    private record KeyColumn(int ordinal, String name)
    {
    }

    private record ForeignKey(String column, String referencedTable, String referencedColumn, String onDelete)
    {
    }

    private record IndexColumn(int ordinal, String name, boolean descending)
    {
    }
}

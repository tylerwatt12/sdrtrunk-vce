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
    private static final int DELETE_BATCH_SIZE = 1_000;
    static final int MAX_IDENTITIES_PER_SYSTEM = 100_000;
    static final int MAX_RELATIONSHIPS_PER_SYSTEM = 500_000;
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

    private RadioSystemSchema()
    {
    }

    static void create(Statement statement) throws SQLException
    {
        for(SqliteSchemaValidator.Definition definition: definitions())
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
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_radio_system_identity_retention
            ON radio_system_identity_summary(
                last_seen_ms, radio_system_id, identity_kind_code,
                home_wacn, home_system_id, identity_id
            )
            """);
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
            new SqliteSchemaValidator.Definition("table", "radio_system_identity_summary", identitySummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_group_summary",
                radioGroupSummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_affiliation", radioAffiliationSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence",
                radioChannelPresenceSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_channel_presence_clear",
                radioChannelPresenceClearSql())
        );
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
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL
                    CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                UNIQUE(p25_wacn, p25_system_id),
                CHECK(
                    (protocol_code = 1 AND address_domain_code = 0
                        AND configuration_id IS NULL
                        AND p25_wacn IS NOT NULL AND p25_system_id IS NOT NULL
                        AND system_key = printf('p25:%05x:%03x', p25_wacn, p25_system_id))
                    OR
                    (protocol_code = 3 AND address_domain_code = 0
                        AND configuration_id IS NOT NULL
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
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
                    (protocol_code = 4 AND address_domain_code IN (1, 2)
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND configuration_id IS NOT NULL
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

    private static String radioAffiliationSql()
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
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),
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
            """;
    }

    private static String radioChannelPresenceSql()
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
                    (typeof(observed_local_id) = 'integer' AND observed_local_id BETWEEN 0 AND 16777215)),
                evidence_code INTEGER NOT NULL
                    CHECK(typeof(evidence_code) = 'integer' AND evidence_code IN (1, 2)),
                confirmed_at_ms INTEGER NOT NULL
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),
                PRIMARY KEY(radio_system_id, radio_identity_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static String radioChannelPresenceClearSql()
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
                    (typeof(observed_local_id) = 'integer' AND observed_local_id BETWEEN 1 AND 9999999)),
                cleared_at_ms INTEGER NOT NULL CHECK(typeof(cleared_at_ms) = 'integer' AND cleared_at_ms > 0),
                PRIMARY KEY(radio_system_id, radio_identity_id, channel_id),
                FOREIGN KEY(radio_identity_id, radio_system_id, radio_kind_code)
                    REFERENCES radio_system_identity_summary(
                        id, radio_system_id, identity_kind_code) ON DELETE CASCADE,
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
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
            "last_talker_alias_seen_ms"));

        List<String> relationshipColumns = new ArrayList<>(List.of(
            "radio_system_id", "radio_kind_code", "radio_identity_id", "group_identity_id", "group_kind_code",
            "first_seen_ms", "last_seen_ms"));
        relationshipColumns.addAll(ACTION_COUNT_COLUMNS);
        relationshipColumns.addAll(List.of("logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count",
            "last_encryption_algorithm_id", "last_encryption_key_id"));

        return List.of(
            new SqliteSchemaValidator.Table("radio_system", "id", "system_key", "configuration_id", "protocol_code",
                "address_domain_code", "p25_wacn", "p25_system_id", "first_seen_ms", "last_seen_ms"),
            new SqliteSchemaValidator.Table("radio_system_identity_summary", identityColumns),
            new SqliteSchemaValidator.Table("trunked_radio_group_summary", relationshipColumns),
            new SqliteSchemaValidator.Table("trunked_radio_affiliation", "radio_system_id", "radio_kind_code",
                "radio_identity_id", "talkgroup_kind_code", "talkgroup_identity_id", "channel_id",
                "radio_observed_local_id", "talkgroup_observed_local_id", "confirmed_at_ms"),
            new SqliteSchemaValidator.Table("trunked_radio_channel_presence", "radio_system_id", "radio_kind_code",
                "radio_identity_id", "channel_id", "observed_local_id", "evidence_code", "confirmed_at_ms"),
            new SqliteSchemaValidator.Table("trunked_radio_channel_presence_clear", "radio_system_id",
                "radio_kind_code", "radio_identity_id", "channel_id", "observed_local_id", "cleared_at_ms")
        );
    }

    static List<String> indexes()
    {
        return List.of("idx_radio_system_identity_last_seen", "idx_radio_system_identity_retention",
            "idx_trunked_radio_group_reverse", "idx_trunked_radio_group_retention",
            "idx_trunked_radio_affiliation_talkgroup", "idx_trunked_radio_affiliation_retention",
            "idx_trunked_radio_channel_presence_channel", "idx_trunked_radio_channel_presence_retention",
            "idx_trunked_radio_channel_presence_clear_retention");
    }

    static void validate(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "radio_system", List.of("id"));
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
        validateForeignKeys(connection, "radio_system_identity_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));
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

    static RadioSystem recordActivity(Connection connection, ReceiverActivityRecords.ActivityEvent activity, int channelId)
        throws SQLException
    {
        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, activity.observedAtEpochMilliseconds(),
            activity.identityDomain(), activity.wacn(), activity.systemId());

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                activity.observedAtEpochMilliseconds() < radioSystem.firstSeenEpochMilliseconds()))
        {
            return null;
        }

        if(activity.action() == null ||
            activity.action() == ReceiverActivityRecords.Action.UNKNOWN)
        {
            return radioSystem;
        }

        Identity sourceIdentity = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            integer(activity.sourceRadioId()), activity.p25SourceIdentity());
        List<Identity> destinations = destinationIdentities(radioSystem, activity.targetId(), activity.targetKind(),
            activity.patchMemberTalkgroupIds(), activity.p25TargetIdentity(), activity.p25PatchMemberIdentities());
        //Receiver observations establish identity/signaling metadata only. Logical-call completion owns call and
        //encryption counters so copies heard on several receiver legs cannot inflate them.
        int encrypted = 0;

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, activity.observedAtEpochMilliseconds(),
                activity.action(), IdentityCounts.NONE,
                sourceIdentity,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyObserved = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            Identity counterpart = destinations.isEmpty() ? null : destinations.get(0);
            upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity, activity.observedAtEpochMilliseconds(),
                alreadyObserved ? null : activity.action(), IdentityCounts.NONE, counterpart,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);

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
            updateRadioPresence(connection, radioSystem, channelId, activity);
        }

        return radioSystem;
    }

    private static void updateRadioPresence(Connection connection, RadioSystem radioSystem, int channelId,
                                            ReceiverActivityRecords.ActivityEvent activity) throws SQLException
    {
        ReceiverActivityRecords.RadioPresenceUpdate update = activity.radioPresenceUpdate();
        if(update == null)
        {
            return;
        }

        Identity radio = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, update.radioId(),
            update.radioIdentity());
        if(radio == null)
        {
            return;
        }
        upsertIdentity(connection, radioSystem.radioSystemId(), radio, activity.observedAtEpochMilliseconds(),
            null, IdentityCounts.NONE, null, null, null, null, null);
        int radioIdentityId = identitySummaryId(connection, radioSystem.radioSystemId(), radio);

        if(update.cleared())
        {
            for(int currentIdentityId: currentRadioIdentityIds(connection, radioSystem.radioSystemId(), channelId,
                radio.localId(), radioIdentityId))
            {
                upsertPresenceClear(connection, radioSystem.radioSystemId(), currentIdentityId, channelId,
                    radio.localId(), activity.observedAtEpochMilliseconds());
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
                    setInteger(statement, 5, positive(radio.localId()));
                    statement.executeUpdate();
                }
            }
            return;
        }

        if(radioIdentityId <= 0)
        {
            return;
        }

        CurrentRadioMapping reconciled = reconcileCurrentRadioIdentity(connection, radioSystem, channelId, radio,
            radioIdentityId, update.radioIdentity().isStableFullyQualified(),
            activity.observedAtEpochMilliseconds());
        if(reconciled == null)
        {
            return;
        }
        radio = reconciled.identity();
        radioIdentityId = reconciled.summaryId();

        Identity talkgroup = update.talkgroupId() != null ? identity(radioSystem,
            TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, update.talkgroupId(), update.talkgroupIdentity()) : null;
        if(update.talkgroupId() != null && talkgroup == null || hasClearAtOrAfter(connection,
            radioSystem.radioSystemId(), radioIdentityId, channelId, radio.localId(),
            activity.observedAtEpochMilliseconds()))
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_channel_presence (
                radio_system_id, radio_identity_id, channel_id, observed_local_id, evidence_code, confirmed_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id) DO UPDATE SET
                channel_id = excluded.channel_id,
                observed_local_id = excluded.observed_local_id,
                evidence_code = excluded.evidence_code,
                confirmed_at_ms = excluded.confirmed_at_ms
            WHERE excluded.confirmed_at_ms > trunked_radio_channel_presence.confirmed_at_ms
               OR (excluded.confirmed_at_ms = trunked_radio_channel_presence.confirmed_at_ms
                   AND (excluded.evidence_code > trunked_radio_channel_presence.evidence_code
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
            statement.executeUpdate();
        }

        if(talkgroup == null)
        {
            return;
        }
        upsertIdentity(connection, radioSystem.radioSystemId(), talkgroup, activity.observedAtEpochMilliseconds(),
            null, IdentityCounts.NONE, radio, null, null, null, null);
        int talkgroupIdentityId = identitySummaryId(connection, radioSystem.radioSystemId(), talkgroup);
        if(talkgroupIdentityId <= 0)
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_affiliation (
                radio_system_id, radio_identity_id, talkgroup_identity_id, channel_id,
                radio_observed_local_id, talkgroup_observed_local_id, confirmed_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id) DO UPDATE SET
                talkgroup_identity_id = excluded.talkgroup_identity_id,
                channel_id = excluded.channel_id,
                radio_observed_local_id = excluded.radio_observed_local_id,
                talkgroup_observed_local_id = excluded.talkgroup_observed_local_id,
                confirmed_at_ms = excluded.confirmed_at_ms
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
            statement.executeUpdate();
        }
    }

    /**
     * Keeps one authoritative current mapping for a positive site-local radio alias. Stronger fully-qualified
     * evidence replaces an ordinary serving-system mapping; a later ordinary observation updates but never
     * downgrades the established fully-qualified mapping. Local zero is deliberately excluded because it is not a
     * unique site-local alias.
     */
    private static CurrentRadioMapping reconcileCurrentRadioIdentity(Connection connection, RadioSystem radioSystem,
                                                                     int channelId, Identity observed,
                                                                     int observedIdentityId,
                                                                     boolean fullyQualifiedEvidence, long observedAt)
        throws SQLException
    {
        if(radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 || observed.localId() == null ||
            observed.localId() <= 0)
        {
            return new CurrentRadioMapping(observed, observedIdentityId, observedAt);
        }

        CurrentRadioMapping establishedFullyQualified = null;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT state.radio_identity_id, identity.home_wacn, identity.home_system_id, identity.identity_id,
                max(state.confirmed_at_ms) AS confirmed_at_ms
            FROM (
                SELECT radio_identity_id, confirmed_at_ms
                FROM trunked_radio_channel_presence
                WHERE radio_system_id = ? AND channel_id = ? AND observed_local_id = ?
                UNION ALL
                SELECT radio_identity_id, confirmed_at_ms
                FROM trunked_radio_affiliation
                WHERE radio_system_id = ? AND channel_id = ? AND radio_observed_local_id = ?
            ) state
            JOIN radio_system_identity_summary identity
              ON identity.radio_system_id = ? AND identity.id = state.radio_identity_id
            WHERE identity.home_wacn <> ? OR identity.home_system_id <> ? OR identity.identity_id <> ?
            GROUP BY state.radio_identity_id, identity.home_wacn, identity.home_system_id, identity.identity_id
            ORDER BY confirmed_at_ms DESC, identity.home_wacn, identity.home_system_id, identity.identity_id
            LIMIT 1
            """))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, channelId);
            statement.setInt(3, observed.localId());
            statement.setInt(4, radioSystem.radioSystemId());
            statement.setInt(5, channelId);
            statement.setInt(6, observed.localId());
            statement.setInt(7, radioSystem.radioSystemId());
            statement.setInt(8, radioSystem.p25Wacn());
            statement.setInt(9, radioSystem.p25SystemId());
            statement.setInt(10, observed.localId());
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    Identity identity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
                        resultSet.getInt("identity_id"), resultSet.getInt("home_wacn"),
                        resultSet.getInt("home_system_id"), observed.localId());
                    establishedFullyQualified = new CurrentRadioMapping(identity,
                        resultSet.getInt("radio_identity_id"), resultSet.getLong("confirmed_at_ms"));
                }
            }
        }

        if(!fullyQualifiedEvidence && establishedFullyQualified != null)
        {
            return establishedFullyQualified;
        }

        if(fullyQualifiedEvidence && establishedFullyQualified != null &&
            !observed.sameCanonicalIdentity(establishedFullyQualified.identity()))
        {
            int timeComparison = Long.compare(observedAt, establishedFullyQualified.confirmedAt());
            if(timeComparison < 0 || timeComparison == 0 &&
                compareCanonicalTuple(observed, establishedFullyQualified.identity()) > 0)
            {
                return null;
            }
        }

        if(fullyQualifiedEvidence)
        {
            for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
            {
                String localColumn = "trunked_radio_affiliation".equals(table) ?
                    "radio_observed_local_id" : "observed_local_id";
                try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE radio_system_id = ? AND channel_id = ? AND " +
                        localColumn + " = ? AND radio_identity_id <> ?"))
                {
                    statement.setInt(1, radioSystem.radioSystemId());
                    statement.setInt(2, channelId);
                    statement.setInt(3, observed.localId());
                    statement.setInt(4, observedIdentityId);
                    statement.executeUpdate();
                }
            }
        }

        return new CurrentRadioMapping(observed, observedIdentityId, observedAt);
    }

    private static int compareCanonicalTuple(Identity first, Identity second)
    {
        int comparison = Integer.compare(first.homeWacn(), second.homeWacn());
        if(comparison == 0)
        {
            comparison = Integer.compare(first.homeSystemId(), second.homeSystemId());
        }
        if(comparison == 0)
        {
            comparison = Integer.compare(first.id(), second.id());
        }
        return comparison;
    }

    private static java.util.Set<Integer> currentRadioIdentityIds(Connection connection, int radioSystemId,
                                                                  int channelId, Integer observedLocalId,
                                                                  int requestedIdentityId) throws SQLException
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

        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_channel_presence"))
        {
            String localColumn = "trunked_radio_affiliation".equals(table) ?
                "radio_observed_local_id" : "observed_local_id";
            try(PreparedStatement statement = connection.prepareStatement(
                "SELECT radio_identity_id FROM " + table +
                    " WHERE radio_system_id = ? AND channel_id = ? AND " + localColumn + " = ?"))
            {
                statement.setInt(1, radioSystemId);
                statement.setInt(2, channelId);
                statement.setInt(3, observedLocalId);
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
                                            int channelId, Integer observedLocalId, long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_channel_presence_clear(
                radio_system_id, radio_identity_id, channel_id, observed_local_id, cleared_at_ms
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_identity_id, channel_id) DO UPDATE SET
                observed_local_id = coalesce(excluded.observed_local_id,
                    trunked_radio_channel_presence_clear.observed_local_id),
                cleared_at_ms = max(trunked_radio_channel_presence_clear.cleared_at_ms, excluded.cleared_at_ms)
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, radioIdentityId);
            statement.setInt(3, channelId);
            setInteger(statement, 4, positive(observedLocalId));
            statement.setLong(5, observedAt);
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

        List<Identity> destinations = destinationIdentities(radioSystem,
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities());
        Identity sourceIdentity = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            call.sourceRadioId(), call.p25SourceIdentity());
        int encrypted = call.encrypted() ? 1 : 0;

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, call.callStartEpochMilliseconds(), null,
                IdentityCounts.targetResolvedCall(encrypted),
                sourceIdentity, call.encryptionAlgorithmId(), call.encryptionKeyId(), null, null);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyCounted = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity, call.callStartEpochMilliseconds(), null,
                IdentityCounts.sourceResolvedCall(alreadyCounted ? 0 : encrypted, !alreadyCounted),
                destinations.isEmpty() ? null : destinations.get(0),
                call.encryptionAlgorithmId(), call.encryptionKeyId(), null, null);

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
        List<Identity> destinations = destinationIdentities(radioSystem,
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities());
        Identity sourceIdentity = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            call.sourceRadioId(), call.p25SourceIdentity());

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, call.callStartEpochMilliseconds(), null,
                IdentityCounts.outputs(recorded, streamed),
                sourceIdentity, null, null, null, null);
        }

        if(sourceIdentity != null)
        {
            boolean alreadyCounted = destinations.stream().anyMatch(sourceIdentity::sameCanonicalIdentity);
            upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity, call.callStartEpochMilliseconds(),
                null, IdentityCounts.outputs(alreadyCounted ? 0 : recorded, alreadyCounted ? 0 : streamed),
                destinations.isEmpty() ? null : destinations.get(0), null, null, null, null);
            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), sourceIdentity, destination,
                    call.callStartEpochMilliseconds(), null, false, 0, recorded, streamed, null, null);
            }
        }
    }

    static boolean applyAttribution(Connection connection, int channelId,
                                    ReceiverActivityRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, attribution.callStartEpochMilliseconds(),
            attribution.identityDomain());

        if(radioSystem == null || attribution.callStartEpochMilliseconds() < radioSystem.firstSeenEpochMilliseconds())
        {
            return false;
        }

        List<Identity> destinations = destinationIdentities(radioSystem,
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(),
            ReceiverActivityRecords.P25Identity.UNKNOWN, List.of());
        Identity sourceIdentity = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO,
            attribution.sourceRadioId(), ReceiverActivityRecords.P25Identity.UNKNOWN);
        boolean enrichDestination = attribution.destinationBecameKnown() ||
            attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();
        boolean enrichSource = attribution.sourceBecameKnown() ||
            attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails();
        boolean applied = false;

        if(enrichDestination)
        {
            for(Identity destination: destinations)
            {
                applied |= upsertIdentity(connection, radioSystem.radioSystemId(), destination,
                    attribution.callStartEpochMilliseconds(), null, IdentityCounts.NONE, sourceIdentity,
                    attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null);
            }
        }

        if(enrichSource && sourceIdentity != null)
        {
            applied |= upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity,
                attribution.callStartEpochMilliseconds(), null, IdentityCounts.NONE,
                destinations.isEmpty() ? null : destinations.get(0),
                attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null);
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

    static boolean updateTalkerAlias(Connection connection, int channelId, int radioId,
                                     ReceiverActivityRecords.P25Identity p25RadioIdentity, String talkerAlias,
                                     long observedAt, TrunkedIdentityDomain identityDomain,
                                     Integer p25Wacn, Integer p25SystemId)
        throws SQLException
    {
        if(talkerAlias == null || talkerAlias.isBlank())
        {
            return false;
        }

        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, observedAt, identityDomain,
            p25Wacn, p25SystemId);
        Identity radio = identity(radioSystem, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId,
            p25RadioIdentity);

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                observedAt < radioSystem.firstSeenEpochMilliseconds()) ||
            radio == null)
        {
            return false;
        }

        return upsertIdentity(connection, radioSystem.radioSystemId(), radio, observedAt,
            null, IdentityCounts.NONE, null, null, null, talkerAlias, observedAt);
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
            p25SystemId);
    }

    /** Resolves one completed receiver-local logical call. */
    static RadioSystem ensureReceiverRadioSystem(Connection connection, int channelId, long observedAt,
                                                TrunkedIdentityDomain observationDomain,
                                                Integer p25Wacn, Integer p25SystemId)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId);
    }

    private static RadioSystem ensureRadioSystemInternal(Connection connection, int channelId, long observedAt,
                                                         TrunkedIdentityDomain observationDomain,
                                                         Integer observedP25Wacn,
                                                         Integer observedP25SystemId)
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

        Integer p25Wacn = protocol == TrunkedIdentityPolicy.PROTOCOL_P25 ? observedP25Wacn : null;
        Integer p25SystemId = protocol == TrunkedIdentityPolicy.PROTOCOL_P25 ? observedP25SystemId : null;
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

        int addressDomainCode = addressDomainCode(protocol, observationDomain);
        String systemKey = p25Wacn != null && p25SystemId != null ?
            RadioSystemKey.p25(p25Wacn, p25SystemId) :
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
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system (
                system_key, configuration_id, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(system_key) DO UPDATE SET
                first_seen_ms = min(radio_system.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(radio_system.last_seen_ms, excluded.last_seen_ms)
            """))
        {
            statement.setString(1, systemKey);
            statement.setString(2, protocol == TrunkedIdentityPolicy.PROTOCOL_P25 ? null :
                channel.configurationId());
            statement.setInt(3, protocol);
            statement.setInt(4, addressDomainCode);
            setInteger(statement, 5, p25Wacn);
            setInteger(statement, 6, p25SystemId);
            statement.setLong(7, observedAt);
            statement.setLong(8, observedAt);
            statement.executeUpdate();
        }

        RadioSystem radioSystem;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, protocol_code, address_domain_code, p25_wacn, p25_system_id, first_seen_ms
            FROM radio_system
            WHERE system_key = ?
            """))
        {
            statement.setString(1, systemKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    throw new SQLException("Missing radio system [" + systemKey + "]");
                }

                radioSystem = new RadioSystem(resultSet.getInt("id"), resultSet.getInt("protocol_code"),
                    identityDomain(resultSet.getInt("address_domain_code")), systemKey,
                    nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"),
                    resultSet.getLong("first_seen_ms"));
            }
        }

        boolean assignmentChanged = channel.radioSystemId() == null ||
            channel.radioSystemId() != radioSystem.radioSystemId();
        boolean assignToChannel = true;

        if(assignmentChanged && protocol == TrunkedIdentityPolicy.PROTOCOL_P25 &&
            channel.radioSystemAssignedAtEpochMilliseconds() != null)
        {
            long assignmentStartedAt = channel.radioSystemAssignedAtEpochMilliseconds();

            //A native-to-native change requires a strictly newer observation. Delayed completed work may still be
            //attributed to its historical native system, but can never move this channel back to an older system.
            assignToChannel = observedAt > assignmentStartedAt;
        }

        if(!assignToChannel)
        {
            //Complete delayed P25 calls still belong to their native radio system. Return it for historical
            //aggregation without changing the receiver channel's monotonic current assignment.
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
            SELECT id, protocol_code, address_domain_code, p25_wacn, p25_system_id, first_seen_ms
            FROM radio_system WHERE system_key = ?
            """))
        {
            statement.setString(1, systemKey);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? new RadioSystem(resultSet.getInt("id"),
                    resultSet.getInt("protocol_code"), identityDomain(resultSet.getInt("address_domain_code")),
                    systemKey, nullableInteger(resultSet, "p25_wacn"),
                    nullableInteger(resultSet, "p25_system_id"), resultSet.getLong("first_seen_ms")) : null;
            }
        }
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

    static int deleteOlderThan(Connection connection, long cutoff) throws SQLException
    {
        int deleted = 0;
        deleted += deleteIdentityBatches(connection, "trunked_radio_affiliation", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_channel_presence", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_channel_presence_clear", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_group_summary", cutoff);
        deleted += deleteIdentityBatches(connection, "radio_system_identity_summary", cutoff);
        return deleted;
    }

    /** Removes at most one bounded batch of systems after their channels and retained facts are gone. */
    static int pruneUnusedRadioSystems(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM radio_system
            WHERE id IN (
                SELECT system.id
                FROM radio_system system
                WHERE NOT EXISTS (SELECT 1 FROM receiver_channel WHERE radio_system_id = system.id)
                  AND NOT EXISTS (
                              SELECT 1 FROM radio_system_identity_summary WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_group_summary WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_affiliation WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_channel_presence WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_channel_presence_clear WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_logical_call_bucket WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_logical_call_identity_bucket WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM receiver_activity_event WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_signaling_activity_bucket WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM p25_learned_site WHERE radio_system_id = system.id
                          )
                ORDER BY system.id
                LIMIT ?
            )
            """))
        {
            statement.setInt(1, DELETE_BATCH_SIZE);
            return statement.executeUpdate();
        }
    }

    static int reset(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "trunked_radio_affiliation");
        deleted += deleteAll(connection, "trunked_radio_channel_presence");
        deleted += deleteAll(connection, "trunked_radio_channel_presence_clear");
        deleted += deleteAll(connection, "trunked_radio_group_summary");
        deleted += deleteAll(connection, "radio_system_identity_summary");
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

    private static boolean upsertIdentity(Connection connection, int radioSystemId, Identity identity, long observedAt,
                                          ReceiverActivityRecords.Action action, IdentityCounts counts,
                                          Identity counterpart, Integer encryptionAlgorithm,
                                          Integer encryptionKey, String talkerAlias, Long talkerAliasSeen)
        throws SQLException
    {
        if(!identityExists(connection, radioSystemId, identity) &&
            !hasSystemCapacity(connection, "radio_system_identity_summary", radioSystemId, MAX_IDENTITIES_PER_SYSTEM))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system_identity_summary (
                radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                first_seen_ms, last_seen_ms, %s,
                logical_call_count, source_logical_call_count, target_logical_call_count,
                encrypted_logical_call_count, recorded_output_count, streamed_output_count,
                last_encryption_algorithm_id, last_encryption_key_id,
                last_talker_alias, last_talker_alias_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                END
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
            setLong(statement, index, normalizedTalkerAlias != null ? talkerAliasSeen : null);
            statement.executeUpdate();
        }

        return true;
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
            !"trunked_radio_group_summary".equals(table)))
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

    private static List<Identity> destinationIdentities(RadioSystem radioSystem,
                                                        String targetId, String targetKind,
                                                        List<Integer> patchMembers,
                                                        ReceiverActivityRecords.P25Identity p25TargetIdentity,
                                                        List<ReceiverActivityRecords.P25PatchMemberIdentity>
                                                            p25PatchMemberIdentities)
    {
        Integer localTarget = integer(targetId);
        Integer kind = TrunkedIdentityPolicy.identityKindCode(targetKind);
        Map<String,Identity> identities = new LinkedHashMap<>();

        Identity target = identity(radioSystem, kind, localTarget, p25TargetIdentity);
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

        if(localId != null && (localId < 0 || localId == 0 &&
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 ||
                !evidence.isStableFullyQualified()) || localId > 0 &&
            !TrunkedIdentityPolicy.isDirectoryIdentity(radioSystem.protocolCode(), radioSystem.identityDomain(),
                kindCode, localId)))
        {
            return null;
        }

        return new Identity(kindCode, canonicalId, homeWacn, homeSystemId,
            localId != null && localId >= 0 ? localId : null);
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
                                               ReceiverActivityRecords.P25Identity p25Identity) throws SQLException
    {
        Identity identity = identity(radioSystem, kindCode, observedLocalId, p25Identity);
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
                                                                     p25PatchMemberIdentities) throws SQLException
    {
        List<IdentityReference> references = new ArrayList<>();
        for(Identity identity: destinationIdentities(radioSystem, targetId, targetKind, patchMembers,
            p25TargetIdentity, p25PatchMemberIdentities))
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
                system.p25_wacn, system.p25_system_id, configured.decoder_type,
                configured.address_domain_code
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
                    nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"));
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

    private static int deleteIdentityBatches(Connection connection, String table, long cutoff) throws SQLException
    {
        String sql;

        if("radio_system_identity_summary".equals(table))
        {
            sql = """
                DELETE FROM radio_system_identity_summary
                WHERE id IN (
                    SELECT identity.id
                    FROM radio_system_identity_summary identity INDEXED BY idx_radio_system_identity_retention
                    WHERE identity.last_seen_ms < ?
                      AND NOT EXISTS (SELECT 1 FROM trunked_radio_group_summary child
                          WHERE child.radio_identity_id=identity.id OR child.group_identity_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM trunked_radio_affiliation child
                          WHERE child.radio_identity_id=identity.id OR child.talkgroup_identity_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence child
                          WHERE child.radio_identity_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM trunked_radio_channel_presence_clear child
                          WHERE child.radio_identity_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM trunked_logical_call_identity_bucket child
                          WHERE child.identity_summary_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM p25_site_call_identity_bucket child
                          WHERE child.identity_summary_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM receiver_activity_event child
                          WHERE child.source_identity_summary_id=identity.id
                             OR child.target_identity_summary_id=identity.id)
                      AND NOT EXISTS (SELECT 1 FROM activity_event_identity_member child
                          WHERE child.identity_summary_id=identity.id)
                    ORDER BY identity.last_seen_ms, identity.radio_system_id, identity.identity_kind_code,
                        identity.home_wacn, identity.home_system_id, identity.identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_group_summary".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_group_summary
                WHERE (radio_system_id, radio_identity_id, group_identity_id) IN (
                    SELECT radio_system_id, radio_identity_id, group_identity_id
                    FROM trunked_radio_group_summary INDEXED BY idx_trunked_radio_group_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, radio_system_id, radio_identity_id, group_identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_affiliation".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_affiliation
                WHERE (radio_system_id, radio_identity_id) IN (
                    SELECT radio_system_id, radio_identity_id
                    FROM trunked_radio_affiliation INDEXED BY idx_trunked_radio_affiliation_retention
                    WHERE confirmed_at_ms < ?
                    ORDER BY confirmed_at_ms, radio_system_id, radio_identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_channel_presence".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_channel_presence
                WHERE (radio_system_id, radio_identity_id) IN (
                    SELECT radio_system_id, radio_identity_id
                    FROM trunked_radio_channel_presence INDEXED BY idx_trunked_radio_channel_presence_retention
                    WHERE confirmed_at_ms < ?
                    ORDER BY confirmed_at_ms, radio_system_id, radio_identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_channel_presence_clear".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_channel_presence_clear
                WHERE (radio_system_id, radio_identity_id, channel_id) IN (
                    SELECT radio_system_id, radio_identity_id, channel_id
                    FROM trunked_radio_channel_presence_clear
                        INDEXED BY idx_trunked_radio_channel_presence_clear_retention
                    WHERE cleared_at_ms < ?
                    ORDER BY cleared_at_ms, radio_system_id, radio_identity_id, channel_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported trunked identity retention table: " + table);
        }

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, cutoff);
            return statement.executeUpdate();
        }
    }

    private static void validateRadioSystemKeys(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, system_key, configuration_id, protocol_code, address_domain_code,
                       p25_wacn, p25_system_id
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
                String expected;

                if(wacn != null && systemId != null)
                {
                    expected = RadioSystemKey.p25(wacn, systemId);
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
                SELECT channel.id, channel.configuration_id, system.system_key, system.configuration_id AS
                    system_configuration_id, system.protocol_code,
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
                boolean nativeP25 = RadioSystemKey.isP25Native(key);
                String configuredKey = RadioSystemKey.channelScoped(protocol(systemProtocol),
                    identityDomain(resultSet.getInt("address_domain_code")), configurationId);

                if(!"TRUNKED".equals(resultSet.getString("channel_kind")) ||
                    configuredProtocol != systemProtocol ||
                    (!nativeP25 && !configurationId.equals(systemConfigurationId)) ||
                    (nativeP25 && systemConfigurationId != null) ||
                    (!nativeP25 && !key.equals(configuredKey)) ||
                    (nativeP25 && systemProtocol != TrunkedIdentityPolicy.PROTOCOL_P25))
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
    }

    /** SQLite cannot make the child identity range depend on its parent protocol without duplicating protocol. */
    private static void validateIdentitySemantics(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT summary.id, summary.identity_kind_code, summary.home_wacn,
                    summary.home_system_id, summary.identity_id,
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
                boolean validHome = p25 ? homeWacn >= 0 && homeSystemId >= 0 :
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
                 String systemKey, Integer p25Wacn, Integer p25SystemId, long firstSeenEpochMilliseconds)
    {
    }

    record IdentityReference(int summaryId, int kindCode, Integer observedLocalId, String identityKey)
    {
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
                                   Integer currentP25Wacn, Integer currentP25SystemId)
    {
    }

    private record CurrentRadioMapping(Identity identity, int summaryId, long confirmedAt)
    {
    }

    private record Identity(int kindCode, int id, int homeWacn, int homeSystemId, Integer localId)
    {
        private Identity
        {
            new RadioSystemIdentityKey.Identity(kindCode, homeWacn, homeSystemId, id);
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

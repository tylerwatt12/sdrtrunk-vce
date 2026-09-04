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
import io.github.dsheirer.protocol.Protocol;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    static final int P25_IDENTITY_STATE_UNKNOWN = 0;
    static final int P25_IDENTITY_STATE_ORDINARY = 1;
    static final int P25_IDENTITY_STATE_STABLE_FULLY_QUALIFIED = 2;
    static final int P25_IDENTITY_STATE_AMBIGUOUS = 3;
    private static final int DELETE_BATCH_SIZE = 1_000;
    static final int MAX_IDENTITIES_PER_SYSTEM = 100_000;
    static final int MAX_ZERO_LOCAL_FQ_TALKGROUPS_PER_SYSTEM = 100_000;
    static final int MAX_RELATIONSHIPS_PER_SYSTEM = 500_000;

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
            ON radio_system_identity_summary(radio_system_id, identity_kind_code, last_seen_ms DESC, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_radio_system_identity_retention
            ON radio_system_identity_summary(last_seen_ms, radio_system_id, identity_kind_code, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_zero_local_fq_system_last_seen
            ON p25_zero_local_fq_talkgroup_summary(
                radio_system_id, last_seen_ms DESC, home_wacn, home_system_id, home_talkgroup_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_p25_zero_local_fq_retention
            ON p25_zero_local_fq_talkgroup_summary(
                last_seen_ms, radio_system_id, home_wacn, home_system_id, home_talkgroup_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_reverse
            ON trunked_radio_talkgroup_summary(
                radio_system_id, talkgroup_id, target_kind_code, last_seen_ms DESC, radio_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_retention
            ON trunked_radio_talkgroup_summary(
                last_seen_ms, radio_system_id, radio_id, talkgroup_id, target_kind_code
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_talkgroup
            ON trunked_radio_affiliation(radio_system_id, talkgroup_id, confirmed_at_ms DESC, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_affiliation_retention
            ON trunked_radio_affiliation(confirmed_at_ms, radio_system_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_site_presence_channel
            ON trunked_radio_site_presence(channel_id, confirmed_at_ms DESC, radio_system_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_site_presence_retention
            ON trunked_radio_site_presence(confirmed_at_ms, radio_system_id, radio_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_presence_lifecycle_retention
            ON trunked_radio_presence_lifecycle(cleared_at_ms, radio_system_id, radio_id)
            """);
    }

    static List<SqliteSchemaValidator.Definition> definitions()
    {
        return List.of(
            new SqliteSchemaValidator.Definition("table", "radio_system", radioSystemSql()),
            new SqliteSchemaValidator.Definition("table", "radio_system_identity_summary", identitySummarySql()),
            new SqliteSchemaValidator.Definition("table", "p25_zero_local_fq_talkgroup_summary",
                zeroLocalFullyQualifiedTalkgroupSummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_talkgroup_summary",
                radioTalkgroupSummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_affiliation", radioAffiliationSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_site_presence", radioSitePresenceSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_presence_lifecycle",
                radioPresenceLifecycleSql()),
            new SqliteSchemaValidator.Definition("trigger", "prune_provisional_radio_system_after_channel_delete",
                pruneProvisionalAfterChannelDeleteSql()),
            new SqliteSchemaValidator.Definition("trigger", "prune_provisional_radio_system_after_channel_reassign",
                pruneProvisionalAfterChannelReassignSql())
        );
    }

    private static String radioSystemSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS radio_system (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                system_key TEXT NOT NULL UNIQUE CHECK(length(trim(system_key)) > 0),
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
                    (protocol_code = 1 AND address_domain_code = 0 AND (
                        (p25_wacn IS NOT NULL AND p25_system_id IS NOT NULL
                            AND system_key = printf('p25:%05x:%03x', p25_wacn, p25_system_id))
                        OR
                        (p25_wacn IS NULL AND p25_system_id IS NULL
                            AND length(system_key) = 48
                            AND substr(system_key, 1, 12) = 'p25:channel:'
                            AND substr(system_key, 21, 1) = '-'
                            AND substr(system_key, 26, 1) = '-'
                            AND substr(system_key, 31, 1) = '-'
                            AND substr(system_key, 36, 1) = '-'
                            AND substr(system_key, 13) = lower(substr(system_key, 13))
                            AND length(replace(substr(system_key, 13), '-', '')) = 32
                            AND replace(substr(system_key, 13), '-', '') NOT GLOB '*[^0-9a-f]*')
                    ))
                    OR
                    (protocol_code = 3 AND address_domain_code = 0
                        AND p25_wacn IS NULL AND p25_system_id IS NULL
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
                    (protocol_code = 4 AND p25_wacn IS NULL AND p25_system_id IS NULL
                        AND length(system_key) = 49
                        AND substr(system_key, 1, 13) = 'nxdn:channel:'
                        AND substr(system_key, 22, 1) = '-'
                        AND substr(system_key, 27, 1) = '-'
                        AND substr(system_key, 32, 1) = '-'
                        AND substr(system_key, 37, 1) = '-'
                        AND substr(system_key, 14) = lower(substr(system_key, 14))
                        AND length(replace(substr(system_key, 14), '-', '')) = 32
                        AND replace(substr(system_key, 14), '-', '') NOT GLOB '*[^0-9a-f]*')
                )
            )
            """;
    }

    private static String identitySummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS radio_system_identity_summary (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                identity_kind_code INTEGER NOT NULL
                    CHECK(typeof(identity_kind_code) = 'integer' AND identity_kind_code IN (1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(typeof(identity_id) = 'integer' AND identity_id > 0),
                p25_identity_state_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(p25_identity_state_code) = 'integer' AND p25_identity_state_code IN (0, 1, 2, 3)),
                p25_home_wacn INTEGER CHECK(p25_home_wacn IS NULL OR typeof(p25_home_wacn) = 'integer'),
                p25_home_system_id INTEGER
                    CHECK(p25_home_system_id IS NULL OR typeof(p25_home_system_id) = 'integer'),
                p25_home_talkgroup_id INTEGER
                    CHECK(p25_home_talkgroup_id IS NULL OR typeof(p25_home_talkgroup_id) = 'integer'),
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
                last_counterpart_kind_code INTEGER CHECK(last_counterpart_kind_code IS NULL OR
                    (typeof(last_counterpart_kind_code) = 'integer' AND last_counterpart_kind_code IN (1, 2, 3))),
                last_counterpart_id INTEGER CHECK(last_counterpart_id IS NULL OR
                    (typeof(last_counterpart_id) = 'integer' AND last_counterpart_id > 0)),
                last_encryption_algorithm_id INTEGER CHECK(last_encryption_algorithm_id IS NULL OR
                    (typeof(last_encryption_algorithm_id) = 'integer' AND last_encryption_algorithm_id >= 0)),
                last_encryption_key_id INTEGER CHECK(last_encryption_key_id IS NULL OR
                    (typeof(last_encryption_key_id) = 'integer' AND last_encryption_key_id >= 0)),
                last_talker_alias TEXT,
                last_talker_alias_seen_ms INTEGER CHECK(last_talker_alias_seen_ms IS NULL OR
                    (typeof(last_talker_alias_seen_ms) = 'integer' AND last_talker_alias_seen_ms > 0)),
                PRIMARY KEY(radio_system_id, identity_kind_code, identity_id),
                CHECK(
                    (last_counterpart_kind_code IS NULL AND last_counterpart_id IS NULL)
                    OR
                    (last_counterpart_kind_code IS NOT NULL AND last_counterpart_id IS NOT NULL)
                ),
                CHECK((last_talker_alias IS NULL AND last_talker_alias_seen_ms IS NULL) OR
                    (last_talker_alias IS NOT NULL AND length(trim(last_talker_alias)) > 0
                        AND last_talker_alias_seen_ms IS NOT NULL)),
                CHECK(
                    (p25_identity_state_code = 2
                        AND p25_home_wacn BETWEEN 0 AND 1048575
                        AND p25_home_system_id BETWEEN 0 AND 4095
                        AND p25_home_talkgroup_id BETWEEN 1 AND 65534)
                    OR
                    (p25_identity_state_code != 2
                        AND p25_home_wacn IS NULL
                        AND p25_home_system_id IS NULL
                        AND p25_home_talkgroup_id IS NULL)
                )
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    /**
     * Fully-qualified P25 talkgroups can legitimately use zero as their local ISSI alias.  They cannot share the
     * positive-integer directory key, so this compact tuple-keyed summary preserves them without inventing a local
     * identity or retaining detailed events.
     */
    private static String zeroLocalFullyQualifiedTalkgroupSummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS p25_zero_local_fq_talkgroup_summary (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                home_wacn INTEGER NOT NULL
                    CHECK(typeof(home_wacn) = 'integer' AND home_wacn BETWEEN 0 AND 1048575),
                home_system_id INTEGER NOT NULL
                    CHECK(typeof(home_system_id) = 'integer' AND home_system_id BETWEEN 0 AND 4095),
                home_talkgroup_id INTEGER NOT NULL
                    CHECK(typeof(home_talkgroup_id) = 'integer' AND home_talkgroup_id BETWEEN 1 AND 65534),
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
                PRIMARY KEY(radio_system_id, home_wacn, home_system_id, home_talkgroup_id)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    private static String radioTalkgroupSummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_talkgroup_summary (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(typeof(radio_id) = 'integer' AND radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(typeof(talkgroup_id) = 'integer' AND talkgroup_id > 0),
                target_kind_code INTEGER NOT NULL
                    CHECK(typeof(target_kind_code) = 'integer' AND target_kind_code IN (1, 3)),
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
                PRIMARY KEY(radio_system_id, radio_id, talkgroup_id, target_kind_code)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    private static String radioAffiliationSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_affiliation (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(typeof(radio_id) = 'integer' AND radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(typeof(talkgroup_id) = 'integer' AND talkgroup_id > 0),
                confirmed_at_ms INTEGER NOT NULL
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),
                PRIMARY KEY(radio_system_id, radio_id)
            ) WITHOUT ROWID
            """;
    }

    private static String radioSitePresenceSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_site_presence (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(typeof(radio_id) = 'integer' AND radio_id > 0),
                channel_id INTEGER NOT NULL,
                evidence_code INTEGER NOT NULL
                    CHECK(typeof(evidence_code) = 'integer' AND evidence_code IN (1, 2)),
                confirmed_at_ms INTEGER NOT NULL
                    CHECK(typeof(confirmed_at_ms) = 'integer' AND confirmed_at_ms > 0),
                PRIMARY KEY(radio_system_id, radio_id),
                FOREIGN KEY(channel_id, radio_system_id)
                    REFERENCES receiver_channel(id, radio_system_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static String radioPresenceLifecycleSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_presence_lifecycle (
                radio_system_id INTEGER NOT NULL REFERENCES radio_system(id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(typeof(radio_id) = 'integer' AND radio_id > 0),
                cleared_at_ms INTEGER NOT NULL CHECK(typeof(cleared_at_ms) = 'integer' AND cleared_at_ms > 0),
                PRIMARY KEY(radio_system_id, radio_id)
            ) WITHOUT ROWID
            """;
    }

    private static String pruneProvisionalAfterChannelDeleteSql()
    {
        return """
            CREATE TRIGGER IF NOT EXISTS prune_provisional_radio_system_after_channel_delete
            AFTER DELETE ON receiver_channel
            WHEN OLD.radio_system_id IS NOT NULL
            BEGIN
                DELETE FROM radio_system
                WHERE id = OLD.radio_system_id
                  AND system_key IN (
                      'p25:channel:' || OLD.configuration_id,
                      'dmr:channel:' || OLD.configuration_id,
                      'nxdn:channel:' || OLD.configuration_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM receiver_channel WHERE radio_system_id = OLD.radio_system_id
                  );
            END
            """;
    }

    private static String pruneProvisionalAfterChannelReassignSql()
    {
        return """
            CREATE TRIGGER IF NOT EXISTS prune_provisional_radio_system_after_channel_reassign
            AFTER UPDATE OF radio_system_id ON receiver_channel
            WHEN OLD.radio_system_id IS NOT NULL AND OLD.radio_system_id IS NOT NEW.radio_system_id
            BEGIN
                DELETE FROM radio_system
                WHERE id = OLD.radio_system_id
                  AND system_key IN (
                      'p25:channel:' || OLD.configuration_id,
                      'dmr:channel:' || OLD.configuration_id,
                      'nxdn:channel:' || OLD.configuration_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM receiver_channel WHERE radio_system_id = OLD.radio_system_id
                  );
            END
            """;
    }

    static List<SqliteSchemaValidator.Table> tables()
    {
        List<String> identityColumns = new ArrayList<>(List.of(
            "radio_system_id", "identity_kind_code", "identity_id", "p25_identity_state_code", "p25_home_wacn",
            "p25_home_system_id", "p25_home_talkgroup_id", "first_seen_ms", "last_seen_ms"));
        identityColumns.addAll(ACTION_COUNT_COLUMNS);
        identityColumns.addAll(List.of("logical_call_count", "source_logical_call_count",
            "target_logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count", "last_counterpart_kind_code", "last_counterpart_id",
            "last_encryption_algorithm_id", "last_encryption_key_id", "last_talker_alias",
            "last_talker_alias_seen_ms"));

        List<String> relationshipColumns = new ArrayList<>(List.of(
            "radio_system_id", "radio_id", "talkgroup_id", "target_kind_code", "first_seen_ms", "last_seen_ms"));
        relationshipColumns.addAll(ACTION_COUNT_COLUMNS);
        relationshipColumns.addAll(List.of("logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count",
            "last_encryption_algorithm_id", "last_encryption_key_id"));

        List<String> zeroLocalFullyQualifiedColumns = new ArrayList<>(List.of(
            "radio_system_id", "home_wacn", "home_system_id", "home_talkgroup_id", "first_seen_ms", "last_seen_ms"));
        zeroLocalFullyQualifiedColumns.addAll(ACTION_COUNT_COLUMNS);
        zeroLocalFullyQualifiedColumns.addAll(List.of("logical_call_count", "encrypted_logical_call_count",
            "recorded_output_count", "streamed_output_count"));

        return List.of(
            new SqliteSchemaValidator.Table("radio_system", "id", "system_key", "protocol_code",
                "address_domain_code", "p25_wacn", "p25_system_id", "first_seen_ms", "last_seen_ms"),
            new SqliteSchemaValidator.Table("radio_system_identity_summary", identityColumns),
            new SqliteSchemaValidator.Table("p25_zero_local_fq_talkgroup_summary",
                zeroLocalFullyQualifiedColumns),
            new SqliteSchemaValidator.Table("trunked_radio_talkgroup_summary", relationshipColumns),
            new SqliteSchemaValidator.Table("trunked_radio_affiliation", "radio_system_id", "radio_id", "talkgroup_id",
                "confirmed_at_ms"),
            new SqliteSchemaValidator.Table("trunked_radio_site_presence", "radio_system_id", "radio_id", "channel_id",
                "evidence_code", "confirmed_at_ms"),
            new SqliteSchemaValidator.Table("trunked_radio_presence_lifecycle", "radio_system_id", "radio_id",
                "cleared_at_ms")
        );
    }

    static List<String> indexes()
    {
        return List.of("idx_radio_system_identity_last_seen", "idx_radio_system_identity_retention",
            "idx_p25_zero_local_fq_system_last_seen", "idx_p25_zero_local_fq_retention",
            "idx_trunked_radio_talkgroup_reverse", "idx_trunked_radio_talkgroup_retention",
            "idx_trunked_radio_affiliation_talkgroup", "idx_trunked_radio_affiliation_retention",
            "idx_trunked_radio_site_presence_channel", "idx_trunked_radio_site_presence_retention",
            "idx_trunked_radio_presence_lifecycle_retention");
    }

    static void validate(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "radio_system", List.of("id"));
        validatePrimaryKey(connection, "radio_system_identity_summary",
            List.of("radio_system_id", "identity_kind_code", "identity_id"));
        validatePrimaryKey(connection, "p25_zero_local_fq_talkgroup_summary",
            List.of("radio_system_id", "home_wacn", "home_system_id", "home_talkgroup_id"));
        validatePrimaryKey(connection, "trunked_radio_talkgroup_summary",
            List.of("radio_system_id", "radio_id", "talkgroup_id", "target_kind_code"));
        validatePrimaryKey(connection, "trunked_radio_affiliation", List.of("radio_system_id", "radio_id"));
        validatePrimaryKey(connection, "trunked_radio_site_presence", List.of("radio_system_id", "radio_id"));
        validatePrimaryKey(connection, "trunked_radio_presence_lifecycle", List.of("radio_system_id", "radio_id"));

        validateForeignKeys(connection, "radio_system", Set.of());
        validateForeignKeys(connection, "radio_system_identity_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));
        validateForeignKeys(connection, "p25_zero_local_fq_talkgroup_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_talkgroup_summary", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_affiliation", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_site_presence", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE"),
            new ForeignKey("channel_id", "receiver_channel", "id", "CASCADE"),
            new ForeignKey("radio_system_id", "receiver_channel", "radio_system_id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_presence_lifecycle", Set.of(
            new ForeignKey("radio_system_id", "radio_system", "id", "CASCADE")));

        validateIndex(connection, "idx_radio_system_identity_last_seen", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "identity_kind_code", false),
            new IndexColumn(2, "last_seen_ms", true),
            new IndexColumn(3, "identity_id", false)));
        validateIndex(connection, "idx_radio_system_identity_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "identity_kind_code", false),
            new IndexColumn(3, "identity_id", false)));
        validateIndex(connection, "idx_p25_zero_local_fq_system_last_seen", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "last_seen_ms", true),
            new IndexColumn(2, "home_wacn", false),
            new IndexColumn(3, "home_system_id", false),
            new IndexColumn(4, "home_talkgroup_id", false)));
        validateIndex(connection, "idx_p25_zero_local_fq_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "home_wacn", false),
            new IndexColumn(3, "home_system_id", false),
            new IndexColumn(4, "home_talkgroup_id", false)));
        validateIndex(connection, "idx_trunked_radio_talkgroup_reverse", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "talkgroup_id", false),
            new IndexColumn(2, "target_kind_code", false),
            new IndexColumn(3, "last_seen_ms", true),
            new IndexColumn(4, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_talkgroup_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_id", false),
            new IndexColumn(3, "talkgroup_id", false),
            new IndexColumn(4, "target_kind_code", false)));
        validateIndex(connection, "idx_trunked_radio_affiliation_talkgroup", List.of(
            new IndexColumn(0, "radio_system_id", false),
            new IndexColumn(1, "talkgroup_id", false),
            new IndexColumn(2, "confirmed_at_ms", true),
            new IndexColumn(3, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_affiliation_retention", List.of(
            new IndexColumn(0, "confirmed_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_site_presence_channel", List.of(
            new IndexColumn(0, "channel_id", false),
            new IndexColumn(1, "confirmed_at_ms", true),
            new IndexColumn(2, "radio_system_id", false),
            new IndexColumn(3, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_site_presence_retention", List.of(
            new IndexColumn(0, "confirmed_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_presence_lifecycle_retention", List.of(
            new IndexColumn(0, "cleared_at_ms", false),
            new IndexColumn(1, "radio_system_id", false),
            new IndexColumn(2, "radio_id", false)));
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

        Integer source = positive(activity.sourceRadioId());
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(),
            source);
        //A P25 continuation belongs to the active call and can replace an initially ordinary local talkgroup with
        //its fully-qualified home identity.  The call-attribution update below owns that refinement because it also
        //carries the original call-start timestamp.  Treating the continuation as an independent identity witness
        //would make the ordinary start plus its later qualification look like two conflicting identities.
        ReceiverActivityRecords.P25TargetIdentity observedTargetIdentity = activity.p25TargetIdentity();
        ReceiverActivityRecords.P25TargetIdentity targetIdentity =
            radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
                activity.action() == ReceiverActivityRecords.Action.CONTINUE ?
                ReceiverActivityRecords.P25TargetIdentity.UNKNOWN : observedTargetIdentity;
        List<ReceiverActivityRecords.P25PatchMemberIdentity> patchMemberIdentities =
            radioSystem.protocolCode() == TrunkedIdentityPolicy.PROTOCOL_P25 &&
                activity.action() == ReceiverActivityRecords.Action.CONTINUE ?
                List.of() : activity.p25PatchMemberIdentities();
        List<Identity> destinations = destinationIdentities(radioSystem.protocolCode(), radioSystem.identityDomain(),
            activity.targetId(), activity.targetKind(), activity.patchMemberTalkgroupIds(), targetIdentity,
            patchMemberIdentities);
        //Receiver observations establish identity/signaling metadata only. Logical-call completion owns call and
        //encryption counters so copies heard on several receiver legs cannot inflate them.
        int encrypted = 0;

        if(isZeroLocalFullyQualifiedTalkgroup(radioSystem.protocolCode(), activity.targetId(), activity.targetKind(),
            observedTargetIdentity))
        {
            //Unlike a positive local identity, the tuple has no earlier local row that can be refined. Preserve
            //direct/untracked mid-call observations here; any later attribution adds the physical call count once.
            upsertZeroLocalFullyQualifiedTalkgroup(connection, radioSystem.radioSystemId(), observedTargetIdentity,
                activity.observedAtEpochMilliseconds(), activity.action(), false, encrypted, 0, 0);
        }

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, activity.observedAtEpochMilliseconds(),
                activity.action(), false, false, true, encrypted, 0, 0,
                validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);
        }

        if(validSource)
        {
            Identity sourceIdentity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source);
            Identity counterpart = destinations.isEmpty() ? null : destinations.get(0);
            upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity, activity.observedAtEpochMilliseconds(),
                activity.action(), false, true, false, encrypted, 0, 0, counterpart,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), source, destination,
                    activity.observedAtEpochMilliseconds(), activity.action(), false, encrypted,
                    0, 0, activity.encryptionAlgorithmId(), activity.encryptionKeyId());
            }
        }

        updateRadioPresence(connection, radioSystem, channelId, activity);

        return radioSystem;
    }

    private static void updateRadioPresence(Connection connection, RadioSystem radioSystem, int channelId,
                                            ReceiverActivityRecords.ActivityEvent activity) throws SQLException
    {
        ReceiverActivityRecords.RadioPresenceUpdate update = activity.radioPresenceUpdate();

        if(update == null || !TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(),
            update.radioId()) || !identityExists(connection, radioSystem.radioSystemId(),
            new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, update.radioId())))
        {
            return;
        }

        if(update.cleared())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trunked_radio_presence_lifecycle(radio_system_id, radio_id, cleared_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(radio_system_id, radio_id) DO UPDATE SET
                    cleared_at_ms = max(trunked_radio_presence_lifecycle.cleared_at_ms,
                        excluded.cleared_at_ms)
                """))
            {
                statement.setInt(1, radioSystem.radioSystemId());
                statement.setInt(2, update.radioId());
                statement.setLong(3, activity.observedAtEpochMilliseconds());
                statement.executeUpdate();
            }

            for(String table: List.of("trunked_radio_affiliation", "trunked_radio_site_presence"))
            {
                try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table +
                        " WHERE radio_system_id = ? AND radio_id = ? AND confirmed_at_ms <= ?"))
                {
                    statement.setInt(1, radioSystem.radioSystemId());
                    statement.setInt(2, update.radioId());
                    statement.setLong(3, activity.observedAtEpochMilliseconds());
                    statement.executeUpdate();
                }
            }
            return;
        }

        if((update.talkgroupId() != null && !TrunkedIdentityPolicy.isDirectoryTalkgroup(radioSystem.protocolCode(),
            radioSystem.identityDomain(), update.talkgroupId())) || hasClearAtOrAfter(connection, radioSystem.radioSystemId(),
            update.radioId(), activity.observedAtEpochMilliseconds()))
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_site_presence (
                radio_system_id, radio_id, channel_id, evidence_code, confirmed_at_ms
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_id) DO UPDATE SET
                channel_id = excluded.channel_id,
                evidence_code = excluded.evidence_code,
                confirmed_at_ms = excluded.confirmed_at_ms
            WHERE excluded.confirmed_at_ms > trunked_radio_site_presence.confirmed_at_ms
               OR (excluded.confirmed_at_ms = trunked_radio_site_presence.confirmed_at_ms
                   AND (excluded.evidence_code > trunked_radio_site_presence.evidence_code
                       OR (excluded.evidence_code = trunked_radio_site_presence.evidence_code
                           AND excluded.channel_id < trunked_radio_site_presence.channel_id)))
            """))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, update.radioId());
            statement.setInt(3, channelId);
            statement.setInt(4, update.evidence().code());
            statement.setLong(5, activity.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }

        if(update.talkgroupId() == null || !identityExists(connection, radioSystem.radioSystemId(),
            new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, update.talkgroupId())))
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_affiliation (
                radio_system_id, radio_id, talkgroup_id, confirmed_at_ms
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_id) DO UPDATE SET
                talkgroup_id = excluded.talkgroup_id,
                confirmed_at_ms = excluded.confirmed_at_ms
            WHERE excluded.confirmed_at_ms > trunked_radio_affiliation.confirmed_at_ms
               OR (excluded.confirmed_at_ms = trunked_radio_affiliation.confirmed_at_ms
                   AND excluded.talkgroup_id < trunked_radio_affiliation.talkgroup_id)
            """))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, update.radioId());
            statement.setInt(3, update.talkgroupId());
            statement.setLong(4, activity.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }
    }

    /**
     * A deregistration wins an equal-time tie. Retaining its bounded watermark prevents delayed confirmations from
     * recreating either current state after the visible rows have been removed.
     */
    private static boolean hasClearAtOrAfter(Connection connection, int radioSystemId, int radioId, long observedAt)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM trunked_radio_presence_lifecycle
            WHERE radio_system_id = ? AND radio_id = ? AND cleared_at_ms >= ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, radioId);
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

        List<Identity> destinations = destinationIdentities(radioSystem.protocolCode(), radioSystem.identityDomain(),
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities());
        Integer source = call.sourceRadioId();
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(),
            source);
        int encrypted = call.encrypted() ? 1 : 0;

        if(isZeroLocalFullyQualifiedTalkgroup(radioSystem.protocolCode(), call.destinationId(), call.destinationKind(),
            call.p25TargetIdentity()))
        {
            upsertZeroLocalFullyQualifiedTalkgroup(connection, radioSystem.radioSystemId(), call.p25TargetIdentity(),
                call.callStartEpochMilliseconds(), null, true, encrypted, 0, 0);
        }

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, call.callStartEpochMilliseconds(), null, true,
                false, true, encrypted, 0, 0,
                validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                call.encryptionAlgorithmId(), call.encryptionKeyId(), null, null,
                P25IdentityMerge.SAME_CALL_REFINEMENT);
        }

        if(validSource)
        {
            Identity sourceIdentity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source);
            upsertIdentity(connection, radioSystem.radioSystemId(), sourceIdentity, call.callStartEpochMilliseconds(), null,
                true, true, false, encrypted, 0, 0, destinations.isEmpty() ? null : destinations.get(0),
                call.encryptionAlgorithmId(), call.encryptionKeyId(), null, null);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), source, destination,
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
        List<Identity> destinations = destinationIdentities(radioSystem.protocolCode(), radioSystem.identityDomain(),
            call.destinationId() > 0 ? Integer.toString(call.destinationId()) : null, call.destinationKind(),
            call.patchMemberTalkgroupIds(), call.p25TargetIdentity(), call.p25PatchMemberIdentities());
        Integer source = call.sourceRadioId();
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(),
            source);

        if(isZeroLocalFullyQualifiedTalkgroup(radioSystem.protocolCode(), call.destinationId(), call.destinationKind(),
            call.p25TargetIdentity()))
        {
            upsertZeroLocalFullyQualifiedTalkgroup(connection, radioSystem.radioSystemId(), call.p25TargetIdentity(),
                call.callStartEpochMilliseconds(), null, false, 0, recorded, streamed);
        }

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(), destination, call.callStartEpochMilliseconds(), null, false,
                false, false, 0, recorded, streamed,
                validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                null, null, null, null, P25IdentityMerge.SAME_CALL_REFINEMENT);
        }

        if(validSource)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(),
                new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source), call.callStartEpochMilliseconds(),
                null, false, false, false, 0, recorded, streamed,
                destinations.isEmpty() ? null : destinations.get(0), null, null, null, null);
            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), source, destination,
                    call.callStartEpochMilliseconds(), null, false, 0, recorded, streamed, null, null);
            }
        }
    }

    static boolean applyAttribution(Connection connection, int channelId,
                                    ReceiverActivityRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, attribution.callStartEpochMilliseconds(),
            attribution.identityDomain(), false);

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                attribution.callStartEpochMilliseconds() < radioSystem.firstSeenEpochMilliseconds()))
        {
            return false;
        }

        List<Identity> destinations = destinationIdentities(radioSystem.protocolCode(), radioSystem.identityDomain(),
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds(),
            attribution.p25TargetIdentity(), attribution.p25PatchMemberIdentities());
        Integer source = attribution.sourceRadioId();
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(),
            source);
        int priorEncrypted = 0;
        boolean p25TargetIdentityApplied = false;
        boolean zeroLocalFullyQualified = isZeroLocalFullyQualifiedTalkgroup(radioSystem.protocolCode(),
            attribution.destinationId(), attribution.destinationKind(), attribution.p25TargetIdentity());

        if(zeroLocalFullyQualified &&
            (attribution.destinationBecameKnown() || attribution.hasP25TargetIdentity()))
        {
            p25TargetIdentityApplied = upsertZeroLocalFullyQualifiedTalkgroup(connection, radioSystem.radioSystemId(),
                attribution.p25TargetIdentity(), attribution.callStartEpochMilliseconds(),
                null, false, 0,
                0, 0);
        }

        if(attribution.hasP25TargetIdentity())
        {
            for(Identity destination: destinations)
            {
                if(destination.p25TargetIdentity().state() != ReceiverActivityRecords.P25IdentityState.UNKNOWN)
                {
                    p25TargetIdentityApplied |= upsertIdentity(connection, radioSystem.radioSystemId(), destination,
                        attribution.callStartEpochMilliseconds(), null, false, false, false, 0, 0, 0,
                        null, null, null, null, null, P25IdentityMerge.SAME_CALL_REFINEMENT);
                }
            }
        }

        if(attribution.destinationBecameKnown())
        {
            for(Identity destination: destinations)
            {
                upsertIdentity(connection, radioSystem.radioSystemId(), destination, attribution.callStartEpochMilliseconds(),
                    null, false, false, true, 0, 0, 0,
                    validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                    null, null, null, null);
            }
        }

        if(attribution.sourceBecameKnown() && validSource)
        {
            upsertIdentity(connection, radioSystem.radioSystemId(),
                new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                attribution.callStartEpochMilliseconds(), null, false,
                true, false, 0, 0, 0, destinations.isEmpty() ? null : destinations.get(0),
                null, null, null, null);
        }

        if(validSource && !destinations.isEmpty() &&
            (attribution.destinationBecameKnown() || attribution.sourceBecameKnown()))
        {
            if(attribution.destinationBecameKnown() && !attribution.sourceBecameKnown())
            {
                upsertIdentity(connection, radioSystem.radioSystemId(),
                    new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                    attribution.callStartEpochMilliseconds(), null, false, false, false, 0, 0, 0,
                    destinations.get(0), null, null, null, null);
            }
            else if(attribution.sourceBecameKnown() && !attribution.destinationBecameKnown())
            {
                for(Identity destination: destinations)
                {
                    upsertIdentity(connection, radioSystem.radioSystemId(), destination,
                        attribution.callStartEpochMilliseconds(), null, false, false, false, 0, 0, 0,
                        new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                        null, null, null, null);
                }
            }

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, radioSystem.radioSystemId(), source, destination,
                    attribution.callStartEpochMilliseconds(), null, false,
                    0, 0, 0, null, null);
            }
        }

        if(attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails())
        {
            int newlyEncrypted = 0;

            if(zeroLocalFullyQualified && attribution.encryptionBecameKnown())
            {
                p25TargetIdentityApplied |= upsertZeroLocalFullyQualifiedTalkgroup(connection, radioSystem.radioSystemId(),
                    attribution.p25TargetIdentity(), attribution.callStartEpochMilliseconds(), null, false,
                    newlyEncrypted, 0, 0);
            }

            for(Identity destination: destinations)
            {
                upsertIdentity(connection, radioSystem.radioSystemId(), destination,
                    attribution.callStartEpochMilliseconds(), null, false, false, false, newlyEncrypted, 0, 0,
                    validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                    attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null);
            }

            if(validSource)
            {
                upsertIdentity(connection, radioSystem.radioSystemId(),
                    new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                    attribution.callStartEpochMilliseconds(), null, false, false, false, newlyEncrypted, 0, 0,
                    destinations.isEmpty() ? null : destinations.get(0), attribution.encryptionAlgorithmId(),
                    attribution.encryptionKeyId(), null, null);

                for(Identity destination: groupDestinations(destinations))
                {
                    upsertRelationship(connection, radioSystem.radioSystemId(), source, destination,
                        attribution.callStartEpochMilliseconds(), null, false, newlyEncrypted, 0, 0,
                        attribution.encryptionAlgorithmId(), attribution.encryptionKeyId());
                }
            }
        }

        return attribution.destinationBecameKnown() && !destinations.isEmpty() ||
            attribution.sourceBecameKnown() && validSource || attribution.encryptionBecameKnown() ||
            attribution.hasEncryptionDetails() && (!destinations.isEmpty() || validSource) ||
            p25TargetIdentityApplied;
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
                if(!resultSet.next() || resultSet.getObject("protocol_code") == null ||
                    resultSet.getInt("protocol_code") != TrunkedIdentityPolicy.PROTOCOL_NXDN)
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

    static boolean updateTalkerAlias(Connection connection, int channelId, int radioId, String talkerAlias,
                                     long observedAt, ReceiverActivityRecords.IdentityDomain identityDomain)
        throws SQLException
    {
        if(talkerAlias == null || talkerAlias.isBlank())
        {
            return false;
        }

        RadioSystem radioSystem = ensureRadioSystem(connection, channelId, observedAt, identityDomain, false);

        if(radioSystem == null ||
            (radioSystem.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                observedAt < radioSystem.firstSeenEpochMilliseconds()) ||
            !TrunkedIdentityPolicy.isDirectoryRadio(radioSystem.protocolCode(), radioSystem.identityDomain(), radioId))
        {
            return false;
        }

        return upsertIdentity(connection, radioSystem.radioSystemId(),
            new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId), observedAt,
            null, false, false, false, 0, 0, 0, null, null, null, talkerAlias, observedAt);
    }

    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             ReceiverActivityRecords.IdentityDomain observationDomain) throws SQLException
    {
        return ensureRadioSystem(connection, channelId, observedAt, observationDomain, null, null, true);
    }

    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             ReceiverActivityRecords.IdentityDomain observationDomain,
                             Integer p25Wacn, Integer p25SystemId) throws SQLException
    {
        return ensureRadioSystem(connection, channelId, observedAt, observationDomain, p25Wacn, p25SystemId,
            true);
    }

    /**
     * Resolves the identity radioSystem for one writer record. Only primary activity/site observations may reclassify the
     * NXDN address domain. Completion, attribution and alias messages are delayed enrichments of an already accepted
     * observation and must never change the current generation themselves.
     */
    static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             ReceiverActivityRecords.IdentityDomain observationDomain,
                             boolean allowIdentityDomainChange) throws SQLException
    {
        return ensureRadioSystem(connection, channelId, observedAt, observationDomain, null, null,
            allowIdentityDomainChange);
    }

    private static RadioSystem ensureRadioSystem(Connection connection, int channelId, long observedAt,
                             ReceiverActivityRecords.IdentityDomain observationDomain,
                             Integer p25Wacn, Integer p25SystemId, boolean allowIdentityDomainChange)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, allowIdentityDomainChange);
    }

    /** Resolves one completed receiver-local logical call. */
    static RadioSystem ensureReceiverRadioSystem(Connection connection, int channelId, long observedAt,
                                                ReceiverActivityRecords.IdentityDomain observationDomain,
                                                Integer p25Wacn, Integer p25SystemId)
        throws SQLException
    {
        return ensureRadioSystemInternal(connection, channelId, observedAt, observationDomain, p25Wacn,
            p25SystemId, false);
    }

    private static RadioSystem ensureRadioSystemInternal(Connection connection, int channelId, long observedAt,
                                                         ReceiverActivityRecords.IdentityDomain observationDomain,
                                                         Integer observedP25Wacn,
                                                         Integer observedP25SystemId,
                                                         boolean allowIdentityDomainChange)
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
        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25 && (p25Wacn == null || p25SystemId == null) &&
            channel.currentP25Wacn() != null && channel.currentP25SystemId() != null)
        {
            p25Wacn = channel.currentP25Wacn();
            p25SystemId = channel.currentP25SystemId();
        }
        String systemKey = p25Wacn != null && p25SystemId != null ?
            RadioSystemKey.p25(p25Wacn, p25SystemId) :
            RadioSystemKey.configured(protocol(protocol), channel.configurationId());
        if(systemKey == null)
        {
            return null;
        }

        int addressDomainCode = addressDomainCode(protocol, observationDomain);
        ExistingRadioSystem existing = existingRadioSystem(connection, systemKey);
        boolean nxdnIdentityDomainChanged = existing != null &&
            protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN &&
            addressDomainCode != IDENTITY_DOMAIN_STANDARD &&
            existing.addressDomainCode() != addressDomainCode;

        if(nxdnIdentityDomainChanged && !allowIdentityDomainChange)
        {
            return null;
        }

        if(nxdnIdentityDomainChanged && observedAt < existing.lastSeenEpochMilliseconds())
        {
            return null;
        }

        if(nxdnIdentityDomainChanged)
        {
            clearNxdnIdentityDomainState(connection, existing.id());
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system (
                system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(system_key) DO UPDATE SET
                first_seen_ms = CASE
                    WHEN excluded.address_domain_code != 0
                     AND excluded.address_domain_code != radio_system.address_domain_code
                     AND excluded.last_seen_ms >= radio_system.last_seen_ms
                    THEN excluded.first_seen_ms
                    ELSE radio_system.first_seen_ms
                END,
                last_seen_ms = max(radio_system.last_seen_ms, excluded.last_seen_ms),
                address_domain_code = CASE
                    WHEN excluded.address_domain_code != 0
                     AND excluded.last_seen_ms >= radio_system.last_seen_ms
                    THEN excluded.address_domain_code
                    ELSE radio_system.address_domain_code
                END
            """))
        {
            statement.setString(1, systemKey);
            statement.setInt(2, protocol);
            statement.setInt(3, addressDomainCode);
            setInteger(statement, 4, p25Wacn);
            setInteger(statement, 5, p25SystemId);
            statement.setLong(6, observedAt);
            statement.setLong(7, observedAt);
            statement.executeUpdate();
        }

        RadioSystem radioSystem;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, protocol_code, address_domain_code, first_seen_ms
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
                    resultSet.getLong("first_seen_ms"));
            }
        }

        if(channel.radioSystemId() != null && channel.radioSystemId() != radioSystem.radioSystemId())
        {
            clearReceiverChannelIdentityState(connection, channelId);
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE receiver_channel SET radio_system_id = ? WHERE id = ?"))
        {
            statement.setInt(1, radioSystem.radioSystemId());
            statement.setInt(2, channelId);
            statement.executeUpdate();
        }

        if(channel.radioSystemId() != null && channel.radioSystemId() != radioSystem.radioSystemId())
        {
            deleteUnusedConfiguredSystem(connection, channel.radioSystemId());
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
            SELECT id, protocol_code, address_domain_code, first_seen_ms
            FROM radio_system WHERE system_key = ?
            """))
        {
            statement.setString(1, systemKey);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? new RadioSystem(resultSet.getInt("id"),
                    resultSet.getInt("protocol_code"), identityDomain(resultSet.getInt("address_domain_code")),
                    systemKey, resultSet.getLong("first_seen_ms")) : null;
            }
        }
    }

    private static ExistingRadioSystem existingRadioSystem(Connection connection, String systemKey) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, address_domain_code, last_seen_ms
            FROM radio_system
            WHERE system_key = ?
            """))
        {
            statement.setString(1, systemKey);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return new ExistingRadioSystem(resultSet.getInt("id"),
                        resultSet.getInt("address_domain_code"), resultSet.getLong("last_seen_ms"));
                }
            }
        }

        return null;
    }

    private static void deleteUnusedConfiguredSystem(Connection connection, int radioSystemId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM radio_system
            WHERE id = ? AND system_key LIKE '%:channel:%'
              AND NOT EXISTS (SELECT 1 FROM receiver_channel WHERE radio_system_id = radio_system.id)
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.executeUpdate();
        }
    }

    /**
     * NXDN Type-C and Type-D reuse portions of the same numeric address space with different meanings. If a channel
     * is reclassified, remove the prior generation before accepting the new domain.
     */
    private static void clearNxdnIdentityDomainState(Connection connection, int radioSystemId) throws SQLException
    {
        for(String table: List.of("trunked_radio_affiliation", "trunked_radio_site_presence",
            "trunked_radio_presence_lifecycle"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE radio_system_id = ?"))
            {
                statement.setInt(1, radioSystemId);
                statement.executeUpdate();
            }
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_radio_talkgroup_summary WHERE radio_system_id = ?"))
        {
            statement.setInt(1, radioSystemId);
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM radio_system_identity_summary WHERE radio_system_id = ?"))
        {
            statement.setInt(1, radioSystemId);
            statement.executeUpdate();
        }

        for(Integer channelId: receiverChannelIds(connection, radioSystemId))
        {
            clearReceiverChannelIdentityState(connection, channelId);
        }
    }

    private static List<Integer> receiverChannelIds(Connection connection, int radioSystemId) throws SQLException
    {
        List<Integer> channelIds = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM receiver_channel WHERE radio_system_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    channelIds.add(resultSet.getInt("id"));
                }
            }
        }

        return channelIds;
    }

    /**
     * Clears receiver-owned projections before moving a channel to a different identity radioSystem. These rows join
     * through the channel's current radioSystem/protocol, so retaining them would relabel old calls as belonging to the new
     * system or protocol.
     */
    private static void clearReceiverChannelIdentityState(Connection connection, int channelId) throws SQLException
    {
        for(String table: List.of("p25_site_snapshot", "trunked_control_channel_quality",
            "trunked_radio_site_presence", "trunked_signaling_activity_bucket", "receiver_activity_event"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE channel_id = ?"))
            {
                statement.setInt(1, channelId);
                statement.executeUpdate();
            }
        }
    }

    /** Clears receiver-owned identity evidence and its radioSystem mapping when a newer site generation is known but its
     * complete normalized system key is not yet available. */
    static void clearReceiverChannelGeneration(Connection connection, int channelId) throws SQLException
    {
        clearReceiverChannelIdentityState(connection, channelId);
        detachReceiverChannel(connection, channelId);
    }

    static int deleteOlderThan(Connection connection, long cutoff) throws SQLException
    {
        int deleted = 0;
        deleted += deleteIdentityBatches(connection, "trunked_radio_affiliation", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_site_presence", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_presence_lifecycle", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_radio_talkgroup_summary", cutoff);
        deleted += deleteIdentityBatches(connection, "p25_zero_local_fq_talkgroup_summary", cutoff);
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
                  AND (
                      system.system_key LIKE '%:channel:%'
                      OR (
                          NOT EXISTS (
                              SELECT 1 FROM radio_system_identity_summary WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM p25_zero_local_fq_talkgroup_summary WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_talkgroup_summary WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_affiliation WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_site_presence WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_radio_presence_lifecycle WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_logical_call_bucket WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM trunked_logical_call_identity_bucket WHERE radio_system_id = system.id
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM p25_learned_site WHERE radio_system_id = system.id
                          )
                      )
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
        deleted += deleteAll(connection, "trunked_radio_site_presence");
        deleted += deleteAll(connection, "trunked_radio_presence_lifecycle");
        deleted += deleteAll(connection, "trunked_radio_talkgroup_summary");
        deleted += deleteAll(connection, "p25_zero_local_fq_talkgroup_summary");
        deleted += deleteAll(connection, "radio_system_identity_summary");
        deleted += deleteAll(connection, "radio_system");
        return deleted;
    }

    static int detachReceiverChannel(Connection connection, int channelId) throws SQLException
    {
        int deleted;
        Integer previous = null;

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT radio_system_id FROM receiver_channel WHERE id = ?"))
        {
            statement.setInt(1, channelId);
            try(ResultSet resultSet = statement.executeQuery())
            {
                previous = resultSet.next() ? nullableInteger(resultSet, "radio_system_id") : null;
            }
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE receiver_channel SET radio_system_id = NULL WHERE id = ?"))
        {
            statement.setInt(1, channelId);
            deleted = statement.executeUpdate();
        }

        if(previous != null)
        {
            deleteUnusedConfiguredSystem(connection, previous);
        }

        return deleted;
    }

    private static boolean upsertZeroLocalFullyQualifiedTalkgroup(
        Connection connection, int radioSystemId, ReceiverActivityRecords.P25TargetIdentity targetIdentity,
        long observedAt, ReceiverActivityRecords.Action action, boolean countedCall, int encrypted, int recorded,
        int streamed) throws SQLException
    {
        if(targetIdentity == null || !targetIdentity.isStableFullyQualified())
        {
            return false;
        }

        if(!zeroLocalFullyQualifiedTalkgroupExists(connection, radioSystemId, targetIdentity) &&
            !hasSystemCapacity(connection, "p25_zero_local_fq_talkgroup_summary", radioSystemId,
                MAX_ZERO_LOCAL_FQ_TALKGROUPS_PER_SYSTEM))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO p25_zero_local_fq_talkgroup_summary (
                radio_system_id, home_wacn, home_system_id, home_talkgroup_id,
                first_seen_ms, last_seen_ms, %s, logical_call_count, encrypted_logical_call_count,
                recorded_output_count, streamed_output_count
            ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, home_wacn, home_system_id, home_talkgroup_id) DO UPDATE SET
                first_seen_ms = min(p25_zero_local_fq_talkgroup_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(p25_zero_local_fq_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                logical_call_count = p25_zero_local_fq_talkgroup_summary.logical_call_count +
                    excluded.logical_call_count,
                encrypted_logical_call_count = p25_zero_local_fq_talkgroup_summary.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = p25_zero_local_fq_talkgroup_summary.recorded_output_count +
                    excluded.recorded_output_count,
                streamed_output_count = p25_zero_local_fq_talkgroup_summary.streamed_output_count +
                    excluded.streamed_output_count
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("p25_zero_local_fq_talkgroup_summary"))))
        {
            int index = 1;
            statement.setInt(index++, radioSystemId);
            statement.setInt(index++, targetIdentity.homeWacn());
            statement.setInt(index++, targetIdentity.homeSystemId());
            statement.setInt(index++, targetIdentity.homeTalkgroupId());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, countedCall);
            statement.setInt(index++, countedCall ? 1 : 0);
            statement.setInt(index++, encrypted);
            statement.setInt(index++, recorded);
            statement.setInt(index, streamed);
            statement.executeUpdate();
        }

        return true;
    }

    private static boolean zeroLocalFullyQualifiedTalkgroupExists(
        Connection connection, int radioSystemId, ReceiverActivityRecords.P25TargetIdentity targetIdentity)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM p25_zero_local_fq_talkgroup_summary
            WHERE radio_system_id = ? AND home_wacn = ? AND home_system_id = ? AND home_talkgroup_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, targetIdentity.homeWacn());
            statement.setInt(3, targetIdentity.homeSystemId());
            statement.setInt(4, targetIdentity.homeTalkgroupId());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean upsertIdentity(Connection connection, int radioSystemId, Identity identity, long observedAt,
                                          ReceiverActivityRecords.Action action, boolean countedCall,
                                          boolean sourceCall, boolean targetCall, int encrypted, int recorded,
                                          int streamed, Identity counterpart, Integer encryptionAlgorithm,
                                          Integer encryptionKey, String talkerAlias, Long talkerAliasSeen)
        throws SQLException
    {
        return upsertIdentity(connection, radioSystemId, identity, observedAt, action, countedCall, sourceCall,
            targetCall, encrypted, recorded, streamed, counterpart, encryptionAlgorithm, encryptionKey,
            talkerAlias, talkerAliasSeen, P25IdentityMerge.AGGREGATE);
    }

    private static boolean upsertIdentity(Connection connection, int radioSystemId, Identity identity, long observedAt,
                                          ReceiverActivityRecords.Action action, boolean countedCall,
                                          boolean sourceCall, boolean targetCall, int encrypted, int recorded,
                                          int streamed, Identity counterpart, Integer encryptionAlgorithm,
                                          Integer encryptionKey, String talkerAlias, Long talkerAliasSeen,
                                          P25IdentityMerge p25IdentityMerge)
        throws SQLException
    {
        if(!identityExists(connection, radioSystemId, identity) &&
            !hasSystemCapacity(connection, "radio_system_identity_summary", radioSystemId, MAX_IDENTITIES_PER_SYSTEM))
        {
            return false;
        }

        String sameCallFullyQualifiedRefinement = p25IdentityMerge == P25IdentityMerge.SAME_CALL_REFINEMENT ?
            "radio_system_identity_summary.p25_identity_state_code = 1 " +
                "AND excluded.p25_identity_state_code = 2 " +
                "AND radio_system_identity_summary.first_seen_ms = excluded.first_seen_ms" : "0";

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO radio_system_identity_summary (
                radio_system_id, identity_kind_code, identity_id,
                p25_identity_state_code, p25_home_wacn, p25_home_system_id, p25_home_talkgroup_id,
                first_seen_ms, last_seen_ms, %s,
                logical_call_count, source_logical_call_count, target_logical_call_count,
                encrypted_logical_call_count, recorded_output_count, streamed_output_count,
                last_counterpart_kind_code, last_counterpart_id,
                last_encryption_algorithm_id, last_encryption_key_id,
                last_talker_alias, last_talker_alias_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, identity_kind_code, identity_id) DO UPDATE SET
                p25_identity_state_code = CASE
                    WHEN excluded.p25_identity_state_code = 0
                    THEN radio_system_identity_summary.p25_identity_state_code
                    WHEN radio_system_identity_summary.p25_identity_state_code = 0
                    THEN excluded.p25_identity_state_code
                    WHEN %s
                    THEN 2
                    WHEN radio_system_identity_summary.p25_identity_state_code = 3
                         OR excluded.p25_identity_state_code = 3
                    THEN 3
                    WHEN radio_system_identity_summary.p25_identity_state_code = 1
                         AND excluded.p25_identity_state_code = 1
                    THEN 1
                    WHEN radio_system_identity_summary.p25_identity_state_code = 2
                         AND excluded.p25_identity_state_code = 2
                         AND radio_system_identity_summary.p25_home_wacn = excluded.p25_home_wacn
                         AND radio_system_identity_summary.p25_home_system_id = excluded.p25_home_system_id
                         AND radio_system_identity_summary.p25_home_talkgroup_id = excluded.p25_home_talkgroup_id
                    THEN 2
                    ELSE 3
                END,
                p25_home_wacn = CASE
                    WHEN excluded.p25_identity_state_code = 0
                         AND radio_system_identity_summary.p25_identity_state_code = 2
                    THEN radio_system_identity_summary.p25_home_wacn
                    WHEN radio_system_identity_summary.p25_identity_state_code = 0
                         AND excluded.p25_identity_state_code = 2
                    THEN excluded.p25_home_wacn
                    WHEN %s
                    THEN excluded.p25_home_wacn
                    WHEN radio_system_identity_summary.p25_identity_state_code = 2
                         AND excluded.p25_identity_state_code = 2
                         AND radio_system_identity_summary.p25_home_wacn = excluded.p25_home_wacn
                         AND radio_system_identity_summary.p25_home_system_id = excluded.p25_home_system_id
                         AND radio_system_identity_summary.p25_home_talkgroup_id = excluded.p25_home_talkgroup_id
                    THEN radio_system_identity_summary.p25_home_wacn
                    ELSE NULL
                END,
                p25_home_system_id = CASE
                    WHEN excluded.p25_identity_state_code = 0
                         AND radio_system_identity_summary.p25_identity_state_code = 2
                    THEN radio_system_identity_summary.p25_home_system_id
                    WHEN radio_system_identity_summary.p25_identity_state_code = 0
                         AND excluded.p25_identity_state_code = 2
                    THEN excluded.p25_home_system_id
                    WHEN %s
                    THEN excluded.p25_home_system_id
                    WHEN radio_system_identity_summary.p25_identity_state_code = 2
                         AND excluded.p25_identity_state_code = 2
                         AND radio_system_identity_summary.p25_home_wacn = excluded.p25_home_wacn
                         AND radio_system_identity_summary.p25_home_system_id = excluded.p25_home_system_id
                         AND radio_system_identity_summary.p25_home_talkgroup_id = excluded.p25_home_talkgroup_id
                    THEN radio_system_identity_summary.p25_home_system_id
                    ELSE NULL
                END,
                p25_home_talkgroup_id = CASE
                    WHEN excluded.p25_identity_state_code = 0
                         AND radio_system_identity_summary.p25_identity_state_code = 2
                    THEN radio_system_identity_summary.p25_home_talkgroup_id
                    WHEN radio_system_identity_summary.p25_identity_state_code = 0
                         AND excluded.p25_identity_state_code = 2
                    THEN excluded.p25_home_talkgroup_id
                    WHEN %s
                    THEN excluded.p25_home_talkgroup_id
                    WHEN radio_system_identity_summary.p25_identity_state_code = 2
                         AND excluded.p25_identity_state_code = 2
                         AND radio_system_identity_summary.p25_home_wacn = excluded.p25_home_wacn
                         AND radio_system_identity_summary.p25_home_system_id = excluded.p25_home_system_id
                         AND radio_system_identity_summary.p25_home_talkgroup_id = excluded.p25_home_talkgroup_id
                    THEN radio_system_identity_summary.p25_home_talkgroup_id
                    ELSE NULL
                END,
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
                last_counterpart_kind_code = CASE
                    WHEN excluded.last_counterpart_id IS NOT NULL
                         AND excluded.last_seen_ms >= radio_system_identity_summary.last_seen_ms
                    THEN excluded.last_counterpart_kind_code
                    ELSE radio_system_identity_summary.last_counterpart_kind_code
                END,
                last_counterpart_id = CASE
                    WHEN excluded.last_counterpart_id IS NOT NULL
                         AND excluded.last_seen_ms >= radio_system_identity_summary.last_seen_ms
                    THEN excluded.last_counterpart_id
                    ELSE radio_system_identity_summary.last_counterpart_id
                END,
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
            sameCallFullyQualifiedRefinement, sameCallFullyQualifiedRefinement,
            sameCallFullyQualifiedRefinement, sameCallFullyQualifiedRefinement,
            actionUpdateSql("radio_system_identity_summary"))))
        {
            int index = 1;
            statement.setInt(index++, radioSystemId);
            statement.setInt(index++, identity.kindCode());
            statement.setInt(index++, identity.id());
            statement.setInt(index++, identity.p25TargetIdentity().stateCode());
            setInteger(statement, index++, identity.p25TargetIdentity().homeWacn());
            setInteger(statement, index++, identity.p25TargetIdentity().homeSystemId());
            setInteger(statement, index++, identity.p25TargetIdentity().homeTalkgroupId());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, countedCall);
            statement.setInt(index++, countedCall ? 1 : 0);
            statement.setInt(index++, sourceCall && countedCall ? 1 : 0);
            statement.setInt(index++, targetCall && countedCall ? 1 : 0);
            statement.setInt(index++, encrypted);
            statement.setInt(index++, recorded);
            statement.setInt(index++, streamed);
            setInteger(statement, index++, counterpart != null ? counterpart.kindCode() : null);
            setInteger(statement, index++, counterpart != null ? counterpart.id() : null);
            setInteger(statement, index++, encryptionAlgorithm);
            setInteger(statement, index++, encryptionKey);
            statement.setString(index++, normalizedAlias(talkerAlias));
            setLong(statement, index, normalizedAlias(talkerAlias) != null ? talkerAliasSeen : null);
            statement.executeUpdate();
        }

        return true;
    }

    private static boolean upsertRelationship(Connection connection, int radioSystemId, int radioId, Identity destination,
                                              long observedAt, ReceiverActivityRecords.Action action,
                                              boolean countedCall, int encrypted, int recorded, int streamed,
                                              Integer encryptionAlgorithm, Integer encryptionKey) throws SQLException
    {
        Identity radio = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId);

        if(!identityExists(connection, radioSystemId, radio) || !identityExists(connection, radioSystemId, destination) ||
            !relationshipExists(connection, radioSystemId, radioId, destination) &&
                !hasSystemCapacity(connection, "trunked_radio_talkgroup_summary", radioSystemId,
                    MAX_RELATIONSHIPS_PER_SYSTEM))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_talkgroup_summary (
                radio_system_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, %s,
                logical_call_count, encrypted_logical_call_count, recorded_output_count, streamed_output_count,
                last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(radio_system_id, radio_id, talkgroup_id, target_kind_code) DO UPDATE SET
                first_seen_ms = min(trunked_radio_talkgroup_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_radio_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                logical_call_count = trunked_radio_talkgroup_summary.logical_call_count + excluded.logical_call_count,
                encrypted_logical_call_count = trunked_radio_talkgroup_summary.encrypted_logical_call_count +
                    excluded.encrypted_logical_call_count,
                recorded_output_count = trunked_radio_talkgroup_summary.recorded_output_count +
                    excluded.recorded_output_count,
                streamed_output_count = trunked_radio_talkgroup_summary.streamed_output_count +
                    excluded.streamed_output_count,
                last_encryption_algorithm_id = CASE
                    WHEN excluded.last_encryption_algorithm_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_radio_talkgroup_summary.last_seen_ms
                    THEN excluded.last_encryption_algorithm_id
                    ELSE trunked_radio_talkgroup_summary.last_encryption_algorithm_id
                END,
                last_encryption_key_id = CASE
                    WHEN excluded.last_encryption_key_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_radio_talkgroup_summary.last_seen_ms
                    THEN excluded.last_encryption_key_id
                    ELSE trunked_radio_talkgroup_summary.last_encryption_key_id
                END
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("trunked_radio_talkgroup_summary"))))
        {
            int index = 1;
            statement.setInt(index++, radioSystemId);
            statement.setInt(index++, radioId);
            statement.setInt(index++, destination.id());
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
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM radio_system_identity_summary
            WHERE radio_system_id = ? AND identity_kind_code = ? AND identity_id = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, identity.kindCode());
            statement.setInt(3, identity.id());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean relationshipExists(Connection connection, int radioSystemId, int radioId,
                                              Identity destination) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM trunked_radio_talkgroup_summary
            WHERE radio_system_id = ? AND radio_id = ? AND talkgroup_id = ? AND target_kind_code = ?
            """))
        {
            statement.setInt(1, radioSystemId);
            statement.setInt(2, radioId);
            statement.setInt(3, destination.id());
            statement.setInt(4, destination.kindCode());

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
            !"p25_zero_local_fq_talkgroup_summary".equals(table) &&
            !"trunked_radio_talkgroup_summary".equals(table)))
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

    private static List<Identity> destinationIdentities(int protocolCode,
                                                        ReceiverActivityRecords.IdentityDomain identityDomain,
                                                        String targetId, String targetKind,
                                                        List<Integer> patchMembers,
                                                        ReceiverActivityRecords.P25TargetIdentity p25TargetIdentity,
                                                        List<ReceiverActivityRecords.P25PatchMemberIdentity>
                                                            p25PatchMemberIdentities)
    {
        Integer target = positive(targetId);
        Integer kind = TrunkedIdentityPolicy.identityKindCode(targetKind);
        Set<Identity> identities = new LinkedHashSet<>();

        if(kind != null && target != null &&
            TrunkedIdentityPolicy.isDirectoryIdentity(protocolCode, identityDomain, kind, target))
        {
            ReceiverActivityRecords.P25TargetIdentity projectedIdentity =
                protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25 && p25TargetIdentity != null ?
                    p25TargetIdentity : ReceiverActivityRecords.P25TargetIdentity.UNKNOWN;
            identities.add(new Identity(kind, target, projectedIdentity));
        }

        if(kind != null && kind == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP &&
            protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25 && patchMembers != null)
        {
            for(Integer member: patchMembers)
            {
                if(TrunkedIdentityPolicy.isDirectoryTalkgroup(protocolCode, identityDomain, member))
                {
                    ReceiverActivityRecords.P25TargetIdentity memberIdentity = p25PatchMemberIdentities == null ?
                        ReceiverActivityRecords.P25TargetIdentity.UNKNOWN : p25PatchMemberIdentities.stream()
                            .filter(candidate -> candidate.localTalkgroupId() == member)
                            .map(ReceiverActivityRecords.P25PatchMemberIdentity::targetIdentity)
                            .findFirst()
                            .orElse(ReceiverActivityRecords.P25TargetIdentity.UNKNOWN);
                    identities.add(new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, member,
                        memberIdentity));
                }
            }
        }

        return List.copyOf(identities);
    }

    private static boolean isZeroLocalFullyQualifiedTalkgroup(
        int protocolCode, String targetId, String targetKind,
        ReceiverActivityRecords.P25TargetIdentity targetIdentity)
    {
        Integer parsedTarget = integer(targetId);
        return isZeroLocalFullyQualifiedTalkgroup(protocolCode,
            parsedTarget != null ? parsedTarget : Integer.MIN_VALUE, targetKind, targetIdentity);
    }

    private static boolean isZeroLocalFullyQualifiedTalkgroup(
        int protocolCode, int targetId, String targetKind,
        ReceiverActivityRecords.P25TargetIdentity targetIdentity)
    {
        return protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25 && targetId == 0 &&
            Integer.valueOf(TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP).equals(
                TrunkedIdentityPolicy.identityKindCode(targetKind)) &&
            targetIdentity != null && targetIdentity.isStableFullyQualified();
    }

    private static List<Identity> groupDestinations(List<Identity> destinations)
    {
        return destinations.stream()
            .filter(identity -> identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP ||
                identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP)
            .toList();
    }

    private static ReceiverChannel receiverChannel(Connection connection, int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.id, channel.configuration_id, channel.radio_system_id,
                system.p25_wacn, system.p25_system_id, configured.decoder_type
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
                    nullableInteger(resultSet, "radio_system_id"),
                    nullableInteger(resultSet, "p25_wacn"), nullableInteger(resultSet, "p25_system_id"));
            }
        }
    }

    private static int addressDomainCode(int protocolCode, ReceiverActivityRecords.IdentityDomain domain)
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

    private static ReceiverActivityRecords.IdentityDomain identityDomain(int code)
    {
        return switch(code)
        {
            case IDENTITY_DOMAIN_NXDN_TYPE_C -> ReceiverActivityRecords.IdentityDomain.NXDN_TYPE_C;
            case IDENTITY_DOMAIN_NXDN_TYPE_D -> ReceiverActivityRecords.IdentityDomain.NXDN_TYPE_D;
            default -> ReceiverActivityRecords.IdentityDomain.STANDARD;
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
                WHERE (radio_system_id, identity_kind_code, identity_id) IN (
                    SELECT radio_system_id, identity_kind_code, identity_id
                    FROM radio_system_identity_summary INDEXED BY idx_radio_system_identity_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, radio_system_id, identity_kind_code, identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_talkgroup_summary".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_talkgroup_summary
                WHERE (radio_system_id, radio_id, talkgroup_id, target_kind_code) IN (
                    SELECT radio_system_id, radio_id, talkgroup_id, target_kind_code
                    FROM trunked_radio_talkgroup_summary INDEXED BY idx_trunked_radio_talkgroup_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, radio_system_id, radio_id, talkgroup_id, target_kind_code
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("p25_zero_local_fq_talkgroup_summary".equals(table))
        {
            sql = """
                DELETE FROM p25_zero_local_fq_talkgroup_summary
                WHERE (radio_system_id, home_wacn, home_system_id, home_talkgroup_id) IN (
                    SELECT radio_system_id, home_wacn, home_system_id, home_talkgroup_id
                    FROM p25_zero_local_fq_talkgroup_summary INDEXED BY idx_p25_zero_local_fq_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, radio_system_id, home_wacn, home_system_id, home_talkgroup_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_affiliation".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_affiliation
                WHERE (radio_system_id, radio_id) IN (
                    SELECT radio_system_id, radio_id
                    FROM trunked_radio_affiliation INDEXED BY idx_trunked_radio_affiliation_retention
                    WHERE confirmed_at_ms < ?
                    ORDER BY confirmed_at_ms, radio_system_id, radio_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_site_presence".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_site_presence
                WHERE (radio_system_id, radio_id) IN (
                    SELECT radio_system_id, radio_id
                    FROM trunked_radio_site_presence INDEXED BY idx_trunked_radio_site_presence_retention
                    WHERE confirmed_at_ms < ?
                    ORDER BY confirmed_at_ms, radio_system_id, radio_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_presence_lifecycle".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_presence_lifecycle
                WHERE (radio_system_id, radio_id) IN (
                    SELECT radio_system_id, radio_id
                    FROM trunked_radio_presence_lifecycle
                        INDEXED BY idx_trunked_radio_presence_lifecycle_retention
                    WHERE cleared_at_ms < ?
                    ORDER BY cleared_at_ms, radio_system_id, radio_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else
        {
            throw new IllegalArgumentException("Unsupported trunked identity retention table: " + table);
        }

        int total = 0;
        int deleted;

        do
        {
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                statement.setLong(1, cutoff);
                deleted = statement.executeUpdate();
                total = Math.addExact(total, deleted);
            }
        }
        while(deleted > 0);

        return total;
    }

    private static void validateRadioSystemKeys(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, system_key, protocol_code, p25_wacn, p25_system_id
                FROM radio_system
                ORDER BY id
                """))
        {
            while(resultSet.next())
            {
                String key = resultSet.getString("system_key");
                int protocolCode = resultSet.getInt("protocol_code");
                Integer wacn = nullableInteger(resultSet, "p25_wacn");
                Integer systemId = nullableInteger(resultSet, "p25_system_id");
                String expected;

                if(wacn != null && systemId != null)
                {
                    expected = RadioSystemKey.p25(wacn, systemId);
                }
                else
                {
                    int marker = key != null ? key.indexOf(":channel:") : -1;
                    String configurationId = marker >= 0 ? key.substring(marker + 9) : null;
                    configurationId = ChannelConfigurationKey.canonical(configurationId);
                    expected = configurationId != null ?
                        RadioSystemKey.configured(protocol(protocolCode), configurationId) : null;
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
                SELECT channel.id, channel.configuration_id, system.system_key, system.protocol_code,
                    configured.decoder_type
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
                boolean nativeP25 = RadioSystemKey.isP25Native(key);
                String configuredKey = RadioSystemKey.configured(protocol(systemProtocol), configurationId);

                if(configuredProtocol != systemProtocol ||
                    (!nativeP25 && !key.equals(configuredKey)) ||
                    (nativeP25 && systemProtocol != TrunkedIdentityPolicy.PROTOCOL_P25))
                {
                    throw new SQLException("Receiver channel [" + resultSet.getInt("id") +
                        "] is attached to an incompatible radio system");
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
        return alias != null && !alias.isBlank() ? alias.strip() : null;
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

    record RadioSystem(int radioSystemId, int protocolCode, ReceiverActivityRecords.IdentityDomain identityDomain,
                 String systemKey, long firstSeenEpochMilliseconds)
    {
    }

    private record ReceiverChannel(int id, String configurationId, Integer protocolCode, Integer radioSystemId,
                                   Integer currentP25Wacn, Integer currentP25SystemId)
    {
    }

    private record ExistingRadioSystem(int id, int addressDomainCode, long lastSeenEpochMilliseconds)
    {
    }

    private record Identity(int kindCode, int id, ReceiverActivityRecords.P25TargetIdentity p25TargetIdentity)
    {
        private Identity(int kindCode, int id)
        {
            this(kindCode, id, ReceiverActivityRecords.P25TargetIdentity.UNKNOWN);
        }

        private Identity
        {
            p25TargetIdentity = p25TargetIdentity != null ? p25TargetIdentity :
                ReceiverActivityRecords.P25TargetIdentity.UNKNOWN;
        }
    }

    private enum P25IdentityMerge
    {
        AGGREGATE,
        SAME_CALL_REFINEMENT
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

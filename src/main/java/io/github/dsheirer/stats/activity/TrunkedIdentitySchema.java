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

import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.identifier.Form;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
final class TrunkedIdentitySchema
{
    static final int SCOPE_KIND_LINKED_SYSTEM = 1;
    static final int SCOPE_KIND_CONTEXT = 2;
    private static final int IDENTITY_DOMAIN_STANDARD = 0;
    private static final int IDENTITY_DOMAIN_NXDN_TYPE_C = 1;
    private static final int IDENTITY_DOMAIN_NXDN_TYPE_D = 2;
    private static final int DELETE_BATCH_SIZE = 1_000;
    static final int MAX_IDENTITIES_PER_SCOPE = 100_000;
    static final int MAX_RELATIONSHIPS_PER_SCOPE = 500_000;

    private static final List<P25ActivityLogRecords.Action> ACTIONS =
        Arrays.asList(P25ActivityLogRecords.Action.values());
    static final List<String> ACTION_COUNT_COLUMNS = ACTIONS.stream()
        .map(action -> action.name().toLowerCase(Locale.ROOT) + "_count")
        .toList();
    private static final String ACTION_COUNT_DEFINITIONS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> column + " INTEGER NOT NULL DEFAULT 0 CHECK(" + column + " >= 0)")
        .collect(Collectors.joining(",\n                    "));
    private static final String ACTION_INSERT_COLUMNS = String.join(", ", ACTION_COUNT_COLUMNS);
    private static final String ACTION_INSERT_PLACEHOLDERS = ACTION_COUNT_COLUMNS.stream()
        .map(column -> "?")
        .collect(Collectors.joining(", "));

    private TrunkedIdentitySchema()
    {
    }

    static void create(Statement statement) throws SQLException
    {
        for(SqliteSchemaValidator.Definition definition: definitions())
        {
            statement.executeUpdate(definition.sql());
        }

        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_scope_context_scope
            ON trunked_identity_scope_context(scope_id, context_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_scope_kind_last_seen
            ON trunked_identity_summary(scope_id, identity_kind_code, last_seen_ms DESC, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_identity_retention
            ON trunked_identity_summary(last_seen_ms, scope_id, identity_kind_code, identity_id)
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_reverse
            ON trunked_radio_talkgroup_summary(
                scope_id, talkgroup_id, target_kind_code, last_seen_ms DESC, radio_id
            )
            """);
        statement.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_trunked_radio_talkgroup_retention
            ON trunked_radio_talkgroup_summary(
                last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code
            )
            """);
    }

    static List<SqliteSchemaValidator.Definition> definitions()
    {
        return List.of(
            new SqliteSchemaValidator.Definition("table", "trunked_identity_scope", scopeSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_identity_scope_context", scopeContextSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_identity_summary", identitySummarySql()),
            new SqliteSchemaValidator.Definition("table", "trunked_radio_talkgroup_summary",
                radioTalkgroupSummarySql())
        );
    }

    private static String scopeSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_identity_scope (
                scope_id INTEGER PRIMARY KEY AUTOINCREMENT,
                scope_token TEXT NOT NULL UNIQUE,
                protocol_code INTEGER NOT NULL CHECK(protocol_code IN (1, 3, 4)),
                scope_kind_code INTEGER NOT NULL CHECK(scope_kind_code IN (1, 2)),
                identity_domain_code INTEGER NOT NULL DEFAULT 0 CHECK(identity_domain_code IN (0, 1, 2)),
                p25_system_key INTEGER UNIQUE REFERENCES p25_system(system_key) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                CHECK(
                    (scope_kind_code = 1 AND protocol_code = 1 AND p25_system_key IS NOT NULL)
                    OR
                    (scope_kind_code = 2 AND protocol_code IN (3, 4) AND p25_system_key IS NULL)
                )
            )
            """;
    }

    private static String scopeContextSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_identity_scope_context (
                context_id INTEGER PRIMARY KEY REFERENCES receiver_context(id) ON DELETE CASCADE,
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """;
    }

    private static String identitySummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_identity_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                identity_kind_code INTEGER NOT NULL CHECK(identity_kind_code IN (1, 2, 3)),
                identity_id INTEGER NOT NULL CHECK(identity_id > 0),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                source_call_count INTEGER NOT NULL DEFAULT 0 CHECK(source_call_count >= 0),
                target_call_count INTEGER NOT NULL DEFAULT 0 CHECK(target_call_count >= 0),
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                last_counterpart_kind_code INTEGER CHECK(last_counterpart_kind_code IN (1, 2, 3)),
                last_counterpart_id INTEGER CHECK(last_counterpart_id > 0),
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                last_talker_alias TEXT,
                last_talker_alias_seen_ms INTEGER,
                PRIMARY KEY(scope_id, identity_kind_code, identity_id),
                CHECK(
                    (last_counterpart_kind_code IS NULL AND last_counterpart_id IS NULL)
                    OR
                    (last_counterpart_kind_code IS NOT NULL AND last_counterpart_id IS NOT NULL)
                )
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    private static String radioTalkgroupSummarySql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_radio_talkgroup_summary (
                scope_id INTEGER NOT NULL REFERENCES trunked_identity_scope(scope_id) ON DELETE CASCADE,
                radio_id INTEGER NOT NULL CHECK(radio_id > 0),
                talkgroup_id INTEGER NOT NULL CHECK(talkgroup_id > 0),
                target_kind_code INTEGER NOT NULL CHECK(target_kind_code IN (1, 3)),
                first_seen_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                %s,
                encrypted_count INTEGER NOT NULL DEFAULT 0 CHECK(encrypted_count >= 0),
                recorded_count INTEGER NOT NULL DEFAULT 0 CHECK(recorded_count >= 0),
                streamed_count INTEGER NOT NULL DEFAULT 0 CHECK(streamed_count >= 0),
                last_encryption_algorithm_id INTEGER,
                last_encryption_key_id INTEGER,
                PRIMARY KEY(scope_id, radio_id, talkgroup_id, target_kind_code)
            ) WITHOUT ROWID
            """.formatted(ACTION_COUNT_DEFINITIONS);
    }

    static List<SqliteSchemaValidator.Table> tables()
    {
        List<String> identityColumns = new ArrayList<>(List.of(
            "scope_id", "identity_kind_code", "identity_id", "first_seen_ms", "last_seen_ms"));
        identityColumns.addAll(ACTION_COUNT_COLUMNS);
        identityColumns.addAll(List.of("source_call_count", "target_call_count", "encrypted_count",
            "recorded_count", "streamed_count", "last_counterpart_kind_code", "last_counterpart_id",
            "last_encryption_algorithm_id", "last_encryption_key_id", "last_talker_alias",
            "last_talker_alias_seen_ms"));

        List<String> relationshipColumns = new ArrayList<>(List.of(
            "scope_id", "radio_id", "talkgroup_id", "target_kind_code", "first_seen_ms", "last_seen_ms"));
        relationshipColumns.addAll(ACTION_COUNT_COLUMNS);
        relationshipColumns.addAll(List.of("encrypted_count", "recorded_count", "streamed_count",
            "last_encryption_algorithm_id", "last_encryption_key_id"));

        return List.of(
            new SqliteSchemaValidator.Table("trunked_identity_scope", "scope_id", "scope_token", "protocol_code",
                "scope_kind_code", "identity_domain_code", "p25_system_key", "first_seen_ms", "last_seen_ms"),
            new SqliteSchemaValidator.Table("trunked_identity_scope_context", "context_id", "scope_id",
                "first_seen_ms", "last_seen_ms"),
            new SqliteSchemaValidator.Table("trunked_identity_summary", identityColumns),
            new SqliteSchemaValidator.Table("trunked_radio_talkgroup_summary", relationshipColumns)
        );
    }

    static List<String> indexes()
    {
        return List.of("idx_trunked_identity_scope_context_scope",
            "idx_trunked_identity_scope_kind_last_seen", "idx_trunked_identity_retention",
            "idx_trunked_radio_talkgroup_reverse", "idx_trunked_radio_talkgroup_retention");
    }

    static void validate(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "trunked_identity_scope", List.of("scope_id"));
        validatePrimaryKey(connection, "trunked_identity_scope_context", List.of("context_id"));
        validatePrimaryKey(connection, "trunked_identity_summary",
            List.of("scope_id", "identity_kind_code", "identity_id"));
        validatePrimaryKey(connection, "trunked_radio_talkgroup_summary",
            List.of("scope_id", "radio_id", "talkgroup_id", "target_kind_code"));

        validateForeignKeys(connection, "trunked_identity_scope", Set.of(
            new ForeignKey("p25_system_key", "p25_system", "system_key", "CASCADE")));
        validateForeignKeys(connection, "trunked_identity_scope_context", Set.of(
            new ForeignKey("context_id", "receiver_context", "id", "CASCADE"),
            new ForeignKey("scope_id", "trunked_identity_scope", "scope_id", "CASCADE")));
        validateForeignKeys(connection, "trunked_identity_summary", Set.of(
            new ForeignKey("scope_id", "trunked_identity_scope", "scope_id", "CASCADE")));
        validateForeignKeys(connection, "trunked_radio_talkgroup_summary", Set.of(
            new ForeignKey("scope_id", "trunked_identity_scope", "scope_id", "CASCADE")));

        validateIndex(connection, "idx_trunked_identity_scope_context_scope",
            List.of(new IndexColumn(0, "scope_id", false), new IndexColumn(1, "context_id", false)));
        validateIndex(connection, "idx_trunked_identity_scope_kind_last_seen", List.of(
            new IndexColumn(0, "scope_id", false),
            new IndexColumn(1, "identity_kind_code", false),
            new IndexColumn(2, "last_seen_ms", true),
            new IndexColumn(3, "identity_id", false)));
        validateIndex(connection, "idx_trunked_identity_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "scope_id", false),
            new IndexColumn(2, "identity_kind_code", false),
            new IndexColumn(3, "identity_id", false)));
        validateIndex(connection, "idx_trunked_radio_talkgroup_reverse", List.of(
            new IndexColumn(0, "scope_id", false),
            new IndexColumn(1, "talkgroup_id", false),
            new IndexColumn(2, "target_kind_code", false),
            new IndexColumn(3, "last_seen_ms", true),
            new IndexColumn(4, "radio_id", false)));
        validateIndex(connection, "idx_trunked_radio_talkgroup_retention", List.of(
            new IndexColumn(0, "last_seen_ms", false),
            new IndexColumn(1, "scope_id", false),
            new IndexColumn(2, "radio_id", false),
            new IndexColumn(3, "talkgroup_id", false),
            new IndexColumn(4, "target_kind_code", false)));
    }

    static Scope recordActivity(Connection connection, P25ActivityLogRecords.ActivityEvent activity, int contextId)
        throws SQLException
    {
        Scope scope = ensureScope(connection, contextId, activity.observedAtEpochMilliseconds(),
            activity.identityDomain());

        if(scope == null ||
            (scope.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                activity.observedAtEpochMilliseconds() < scope.firstSeenEpochMilliseconds()))
        {
            return null;
        }

        if(activity.action() == null ||
            activity.action() == P25ActivityLogRecords.Action.UNKNOWN)
        {
            return scope;
        }

        Integer source = positive(activity.sourceRadioId());
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(scope.protocolCode(), scope.identityDomain(),
            source);
        List<Identity> destinations = destinationIdentities(scope.protocolCode(), scope.identityDomain(),
            activity.targetId(), activity.targetKind(), activity.patchMemberTalkgroupIds());
        int encrypted = activity.encrypted() ? 1 : 0;

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, scope.scopeId(), destination, activity.observedAtEpochMilliseconds(),
                activity.action(), activity.countedCall(), false, true, encrypted, 0, 0,
                validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);
        }

        if(validSource)
        {
            Identity sourceIdentity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source);
            Identity counterpart = destinations.isEmpty() ? null : destinations.get(0);
            upsertIdentity(connection, scope.scopeId(), sourceIdentity, activity.observedAtEpochMilliseconds(),
                activity.action(), activity.countedCall(), true, false, encrypted, 0, 0, counterpart,
                activity.encryptionAlgorithmId(), activity.encryptionKeyId(), null, null);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, scope.scopeId(), source, destination,
                    activity.observedAtEpochMilliseconds(), activity.action(), activity.countedCall(), encrypted,
                    0, 0, activity.encryptionAlgorithmId(), activity.encryptionKeyId());
            }
        }

        return scope;
    }

    static void applyCompletedCallOutput(Connection connection, int contextId,
                                         P25ActivityLogRecords.CompletedCallOutput output,
                                         int recorded, int streamed) throws SQLException
    {
        Scope scope = ensureScope(connection, contextId, output.callStartEpochMilliseconds(),
            output.identityDomain(), false);

        if(scope == null ||
            (scope.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                output.callStartEpochMilliseconds() < scope.firstSeenEpochMilliseconds()))
        {
            return;
        }

        List<Identity> destinations = destinationIdentities(scope.protocolCode(), scope.identityDomain(),
            output.destinationId() > 0 ? Integer.toString(output.destinationId()) : null, output.targetKind(),
            output.patchMemberTalkgroupIds());
        Integer source = output.sourceRadioId();
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(scope.protocolCode(), scope.identityDomain(),
            source);

        for(Identity destination: destinations)
        {
            upsertIdentity(connection, scope.scopeId(), destination, output.callStartEpochMilliseconds(),
                null, false, false, false, 0, recorded, streamed,
                validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                null, null, null, null);
        }

        if(validSource)
        {
            Identity sourceIdentity = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source);
            upsertIdentity(connection, scope.scopeId(), sourceIdentity, output.callStartEpochMilliseconds(),
                null, false, false, false, 0, recorded, streamed,
                destinations.isEmpty() ? null : destinations.get(0), null, null, null, null);

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, scope.scopeId(), source, destination,
                    output.callStartEpochMilliseconds(), null, false, 0, recorded, streamed, null, null);
            }
        }
    }

    static boolean applyAttribution(Connection connection, int contextId,
                                    P25ActivityLogRecords.TrunkedCallAttribution attribution) throws SQLException
    {
        Scope scope = ensureScope(connection, contextId, attribution.callStartEpochMilliseconds(),
            attribution.identityDomain(), false);

        if(scope == null ||
            (scope.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                attribution.callStartEpochMilliseconds() < scope.firstSeenEpochMilliseconds()))
        {
            return false;
        }

        List<Identity> destinations = destinationIdentities(scope.protocolCode(), scope.identityDomain(),
            attribution.destinationId() > 0 ? Integer.toString(attribution.destinationId()) : null,
            attribution.destinationKind(), attribution.patchMemberTalkgroupIds());
        Integer source = attribution.sourceRadioId();
        boolean validSource = TrunkedIdentityPolicy.isDirectoryRadio(scope.protocolCode(), scope.identityDomain(),
            source);
        int priorEncrypted = attribution.encryptedBeforeObservation() ? 1 : 0;

        if(attribution.destinationBecameKnown())
        {
            for(Identity destination: destinations)
            {
                upsertIdentity(connection, scope.scopeId(), destination, attribution.callStartEpochMilliseconds(),
                    P25ActivityLogRecords.Action.CALL, true, false, true, priorEncrypted, 0, 0,
                    validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                    null, null, null, null);
            }
        }

        if(attribution.sourceBecameKnown() && validSource)
        {
            upsertIdentity(connection, scope.scopeId(),
                new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                attribution.callStartEpochMilliseconds(), P25ActivityLogRecords.Action.CALL, true,
                true, false, priorEncrypted, 0, 0, destinations.isEmpty() ? null : destinations.get(0),
                null, null, null, null);
        }

        if(validSource && !destinations.isEmpty() &&
            (attribution.destinationBecameKnown() || attribution.sourceBecameKnown()))
        {
            if(attribution.destinationBecameKnown() && !attribution.sourceBecameKnown())
            {
                upsertIdentity(connection, scope.scopeId(),
                    new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                    attribution.callStartEpochMilliseconds(), null, false, false, false, 0, 0, 0,
                    destinations.get(0), null, null, null, null);
            }
            else if(attribution.sourceBecameKnown() && !attribution.destinationBecameKnown())
            {
                for(Identity destination: destinations)
                {
                    upsertIdentity(connection, scope.scopeId(), destination,
                        attribution.callStartEpochMilliseconds(), null, false, false, false, 0, 0, 0,
                        new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                        null, null, null, null);
                }
            }

            for(Identity destination: groupDestinations(destinations))
            {
                upsertRelationship(connection, scope.scopeId(), source, destination,
                    attribution.callStartEpochMilliseconds(), P25ActivityLogRecords.Action.CALL, true,
                    priorEncrypted, 0, 0, null, null);
            }
        }

        if(attribution.encryptionBecameKnown() || attribution.hasEncryptionDetails())
        {
            int newlyEncrypted = attribution.encryptionBecameKnown() ? 1 : 0;

            for(Identity destination: destinations)
            {
                upsertIdentity(connection, scope.scopeId(), destination,
                    attribution.callStartEpochMilliseconds(), null, false, false, false, newlyEncrypted, 0, 0,
                    validSource ? new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source) : null,
                    attribution.encryptionAlgorithmId(), attribution.encryptionKeyId(), null, null);
            }

            if(validSource)
            {
                upsertIdentity(connection, scope.scopeId(),
                    new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, source),
                    attribution.callStartEpochMilliseconds(), null, false, false, false, newlyEncrypted, 0, 0,
                    destinations.isEmpty() ? null : destinations.get(0), attribution.encryptionAlgorithmId(),
                    attribution.encryptionKeyId(), null, null);

                for(Identity destination: groupDestinations(destinations))
                {
                    upsertRelationship(connection, scope.scopeId(), source, destination,
                        attribution.callStartEpochMilliseconds(), null, false, newlyEncrypted, 0, 0,
                        attribution.encryptionAlgorithmId(), attribution.encryptionKeyId());
                }
            }
        }

        return attribution.destinationBecameKnown() && !destinations.isEmpty() ||
            attribution.sourceBecameKnown() && validSource || attribution.encryptionBecameKnown() ||
            attribution.hasEncryptionDetails() && (!destinations.isEmpty() || validSource);
    }

    static boolean updateTalkerAlias(Connection connection, int contextId, int radioId, String talkerAlias,
                                     long observedAt, P25ActivityLogRecords.IdentityDomain identityDomain)
        throws SQLException
    {
        if(talkerAlias == null || talkerAlias.isBlank())
        {
            return false;
        }

        Scope scope = ensureScope(connection, contextId, observedAt, identityDomain, false);

        if(scope == null ||
            (scope.protocolCode() != TrunkedIdentityPolicy.PROTOCOL_P25 &&
                observedAt < scope.firstSeenEpochMilliseconds()) ||
            !TrunkedIdentityPolicy.isDirectoryRadio(scope.protocolCode(), scope.identityDomain(), radioId))
        {
            return false;
        }

        return upsertIdentity(connection, scope.scopeId(),
            new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId), observedAt,
            null, false, false, false, 0, 0, 0, null, null, null, talkerAlias, observedAt);
    }

    static Scope ensureScope(Connection connection, int contextId, long observedAt,
                             P25ActivityLogRecords.IdentityDomain observationDomain) throws SQLException
    {
        return ensureScope(connection, contextId, observedAt, observationDomain, true);
    }

    /**
     * Resolves the identity scope for one writer record. Only primary activity/site observations may reclassify the
     * NXDN address domain. Completion, attribution and alias messages are delayed enrichments of an already accepted
     * observation and must never change the current generation themselves.
     */
    static Scope ensureScope(Connection connection, int contextId, long observedAt,
                             P25ActivityLogRecords.IdentityDomain observationDomain,
                             boolean allowIdentityDomainChange) throws SQLException
    {
        Context context = context(connection, contextId);

        if(context == null)
        {
            return null;
        }

        int protocol = TrunkedIdentityPolicy.protocolFamilyCode(context.protocolCode());

        if(!TrunkedIdentityPolicy.isSupportedProtocol(protocol))
        {
            return null;
        }

        int scopeKind;
        String scopeToken;
        Integer p25SystemKey = null;

        if(protocol == TrunkedIdentityPolicy.PROTOCOL_P25)
        {
            if(context.systemKey() == null || context.wacn() == null || context.systemId() == null)
            {
                return null;
            }

            scopeKind = SCOPE_KIND_LINKED_SYSTEM;
            p25SystemKey = context.systemKey();
            scopeToken = String.format(Locale.ROOT, "p25:%05X:%03X", context.wacn(), context.systemId());
        }
        else
        {
            scopeKind = SCOPE_KIND_CONTEXT;
            String protocolName = protocol == TrunkedIdentityPolicy.PROTOCOL_DMR ? "dmr" : "nxdn";
            scopeToken = context.guid() != null && !context.guid().isBlank() ?
                protocolName + ":guid:" + context.guid() :
                protocolName + ":context:" + context.contextId();
        }

        int identityDomainCode = identityDomainCode(protocol, observationDomain);
        ExistingScope existingScope = existingScope(connection, scopeToken);
        Integer mappedScopeId = mappedScopeId(connection, contextId);
        boolean nxdnIdentityDomainChanged = existingScope != null &&
            protocol == TrunkedIdentityPolicy.PROTOCOL_NXDN &&
            existingScope.scopeKindCode() == SCOPE_KIND_CONTEXT &&
            identityDomainCode != IDENTITY_DOMAIN_STANDARD &&
            existingScope.identityDomainCode() != identityDomainCode;

        if(nxdnIdentityDomainChanged && !allowIdentityDomainChange)
        {
            return null;
        }

        if(nxdnIdentityDomainChanged && observedAt < existingScope.lastSeenEpochMilliseconds())
        {
            return null;
        }

        if(nxdnIdentityDomainChanged)
        {
            clearNxdnIdentityDomainState(connection, existingScope.scopeId());
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_identity_scope (
                scope_token, protocol_code, scope_kind_code, identity_domain_code, p25_system_key,
                first_seen_ms, last_seen_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(scope_token) DO UPDATE SET
                first_seen_ms = CASE
                    WHEN excluded.identity_domain_code != 0
                     AND excluded.identity_domain_code != trunked_identity_scope.identity_domain_code
                     AND excluded.last_seen_ms >= trunked_identity_scope.last_seen_ms
                    THEN excluded.first_seen_ms
                    ELSE trunked_identity_scope.first_seen_ms
                END,
                last_seen_ms = max(trunked_identity_scope.last_seen_ms, excluded.last_seen_ms),
                identity_domain_code = CASE
                    WHEN excluded.identity_domain_code != 0
                     AND excluded.last_seen_ms >= trunked_identity_scope.last_seen_ms
                    THEN excluded.identity_domain_code
                    ELSE trunked_identity_scope.identity_domain_code
                END
            """))
        {
            statement.setString(1, scopeToken);
            statement.setInt(2, protocol);
            statement.setInt(3, scopeKind);
            statement.setInt(4, identityDomainCode);
            setInteger(statement, 5, p25SystemKey);
            statement.setLong(6, observedAt);
            statement.setLong(7, observedAt);
            statement.executeUpdate();
        }

        Scope scope;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT scope_id, protocol_code, identity_domain_code, first_seen_ms
            FROM trunked_identity_scope
            WHERE scope_token = ?
            """))
        {
            statement.setString(1, scopeToken);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    throw new SQLException("Missing trunked identity scope [" + scopeToken + "]");
                }

                scope = new Scope(resultSet.getInt("scope_id"), resultSet.getInt("protocol_code"),
                    identityDomain(resultSet.getInt("identity_domain_code")), scopeToken,
                    resultSet.getLong("first_seen_ms"));
            }
        }

        if(mappedScopeId != null && mappedScopeId != scope.scopeId())
        {
            clearContextIdentityState(connection, contextId);
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_identity_scope_context (context_id, scope_id, first_seen_ms, last_seen_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(context_id) DO UPDATE SET
                scope_id = excluded.scope_id,
                first_seen_ms = CASE
                    WHEN trunked_identity_scope_context.scope_id = excluded.scope_id
                    THEN min(trunked_identity_scope_context.first_seen_ms, excluded.first_seen_ms)
                    ELSE excluded.first_seen_ms
                END,
                last_seen_ms = CASE
                    WHEN trunked_identity_scope_context.scope_id = excluded.scope_id
                    THEN max(trunked_identity_scope_context.last_seen_ms, excluded.last_seen_ms)
                    ELSE excluded.last_seen_ms
                END
            """))
        {
            statement.setInt(1, contextId);
            statement.setInt(2, scope.scopeId());
            statement.setLong(3, observedAt);
            statement.setLong(4, observedAt);
            statement.executeUpdate();
        }

        deleteOrphanContextScopes(connection);
        return scope;
    }

    private static ExistingScope existingScope(Connection connection, String scopeToken) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT scope_id, scope_kind_code, identity_domain_code, last_seen_ms
            FROM trunked_identity_scope
            WHERE scope_token = ?
            """))
        {
            statement.setString(1, scopeToken);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return new ExistingScope(resultSet.getInt("scope_id"), resultSet.getInt("scope_kind_code"),
                        resultSet.getInt("identity_domain_code"), resultSet.getLong("last_seen_ms"));
                }
            }
        }

        return null;
    }

    private static Integer mappedScopeId(Connection connection, int contextId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT scope_id FROM trunked_identity_scope_context WHERE context_id = ?
            """))
        {
            statement.setInt(1, contextId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getInt("scope_id") : null;
            }
        }
    }

    /**
     * NXDN Type-C and Type-D reuse portions of the same numeric address space with different meanings. If a channel
     * is reclassified, remove the prior generation before accepting the new domain.
     */
    private static void clearNxdnIdentityDomainState(Connection connection, int scopeId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_radio_talkgroup_summary WHERE scope_id = ?"))
        {
            statement.setInt(1, scopeId);
            statement.executeUpdate();
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_identity_summary WHERE scope_id = ?"))
        {
            statement.setInt(1, scopeId);
            statement.executeUpdate();
        }

        for(Integer contextId: mappedContextIds(connection, scopeId))
        {
            clearContextIdentityState(connection, contextId);
        }
    }

    private static List<Integer> mappedContextIds(Connection connection, int scopeId) throws SQLException
    {
        List<Integer> contextIds = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT context_id FROM trunked_identity_scope_context WHERE scope_id = ?
            """))
        {
            statement.setInt(1, scopeId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    contextIds.add(resultSet.getInt("context_id"));
                }
            }
        }

        return contextIds;
    }

    /**
     * Clears receiver-owned projections before moving a context to a different identity scope. These rows join
     * through the context's current scope/protocol, so retaining them would relabel old calls as belonging to the new
     * system or protocol.
     */
    private static void clearContextIdentityState(Connection connection, int contextId) throws SQLException
    {
        for(String table: List.of("call_identity_bucket", "p25_site_talkgroup_bucket",
            "p25_site_activity_bucket", "p25_site_frequency_summary", "p25_activity_event"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE context_id = ?"))
            {
                statement.setInt(1, contextId);
                statement.executeUpdate();
            }
        }
    }

    static int deleteOlderThan(Connection connection, long cutoff) throws SQLException
    {
        int deleted = 0;
        deleted += deleteIdentityBatches(connection, "trunked_radio_talkgroup_summary", cutoff);
        deleted += deleteIdentityBatches(connection, "trunked_identity_summary", cutoff);
        return deleted;
    }

    static int reset(Connection connection) throws SQLException
    {
        int deleted = 0;
        deleted += deleteAll(connection, "trunked_radio_talkgroup_summary");
        deleted += deleteAll(connection, "trunked_identity_summary");
        deleted += deleteAll(connection, "trunked_identity_scope_context");
        deleted += deleteAll(connection, "trunked_identity_scope");
        return deleted;
    }

    static int clearContext(Connection connection, int contextId) throws SQLException
    {
        Integer scopeId = null;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT scope.scope_id, scope.scope_kind_code
            FROM trunked_identity_scope_context mapping
            JOIN trunked_identity_scope scope ON scope.scope_id = mapping.scope_id
            WHERE mapping.context_id = ?
            """))
        {
            statement.setInt(1, contextId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    scopeId = resultSet.getInt("scope_id");
                }
            }
        }

        int deleted;

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_identity_scope_context WHERE context_id = ?"))
        {
            statement.setInt(1, contextId);
            deleted = statement.executeUpdate();
        }

        if(scopeId != null)
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM trunked_identity_scope
                WHERE scope_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM trunked_identity_scope_context mapping
                      WHERE mapping.scope_id = trunked_identity_scope.scope_id
                  )
                """))
            {
                statement.setInt(1, scopeId);
                deleted += statement.executeUpdate();
            }
        }

        return deleted;
    }

    private static boolean upsertIdentity(Connection connection, int scopeId, Identity identity, long observedAt,
                                          P25ActivityLogRecords.Action action, boolean countedCall,
                                          boolean sourceCall, boolean targetCall, int encrypted, int recorded,
                                          int streamed, Identity counterpart, Integer encryptionAlgorithm,
                                          Integer encryptionKey, String talkerAlias, Long talkerAliasSeen)
        throws SQLException
    {
        if(!identityExists(connection, scopeId, identity) &&
            !hasScopeCapacity(connection, "trunked_identity_summary", scopeId, MAX_IDENTITIES_PER_SCOPE))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_identity_summary (
                scope_id, identity_kind_code, identity_id, first_seen_ms, last_seen_ms, %s,
                source_call_count, target_call_count, encrypted_count, recorded_count, streamed_count,
                last_counterpart_kind_code, last_counterpart_id,
                last_encryption_algorithm_id, last_encryption_key_id,
                last_talker_alias, last_talker_alias_seen_ms
            ) VALUES (?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(scope_id, identity_kind_code, identity_id) DO UPDATE SET
                first_seen_ms = min(trunked_identity_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_identity_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                source_call_count = trunked_identity_summary.source_call_count + excluded.source_call_count,
                target_call_count = trunked_identity_summary.target_call_count + excluded.target_call_count,
                encrypted_count = trunked_identity_summary.encrypted_count + excluded.encrypted_count,
                recorded_count = trunked_identity_summary.recorded_count + excluded.recorded_count,
                streamed_count = trunked_identity_summary.streamed_count + excluded.streamed_count,
                last_counterpart_kind_code = CASE
                    WHEN excluded.last_counterpart_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_identity_summary.last_seen_ms
                    THEN excluded.last_counterpart_kind_code
                    ELSE trunked_identity_summary.last_counterpart_kind_code
                END,
                last_counterpart_id = CASE
                    WHEN excluded.last_counterpart_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_identity_summary.last_seen_ms
                    THEN excluded.last_counterpart_id
                    ELSE trunked_identity_summary.last_counterpart_id
                END,
                last_encryption_algorithm_id = CASE
                    WHEN excluded.last_encryption_algorithm_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_identity_summary.last_seen_ms
                    THEN excluded.last_encryption_algorithm_id
                    ELSE trunked_identity_summary.last_encryption_algorithm_id
                END,
                last_encryption_key_id = CASE
                    WHEN excluded.last_encryption_key_id IS NOT NULL
                         AND excluded.last_seen_ms >= trunked_identity_summary.last_seen_ms
                    THEN excluded.last_encryption_key_id
                    ELSE trunked_identity_summary.last_encryption_key_id
                END,
                last_talker_alias = CASE
                    WHEN excluded.last_talker_alias IS NOT NULL
                         AND excluded.last_talker_alias_seen_ms >=
                             coalesce(trunked_identity_summary.last_talker_alias_seen_ms, 0)
                    THEN excluded.last_talker_alias
                    ELSE trunked_identity_summary.last_talker_alias
                END,
                last_talker_alias_seen_ms = CASE
                    WHEN excluded.last_talker_alias IS NOT NULL
                         AND excluded.last_talker_alias_seen_ms >=
                             coalesce(trunked_identity_summary.last_talker_alias_seen_ms, 0)
                    THEN excluded.last_talker_alias_seen_ms
                    ELSE trunked_identity_summary.last_talker_alias_seen_ms
                END
            """.formatted(ACTION_INSERT_COLUMNS, ACTION_INSERT_PLACEHOLDERS,
            actionUpdateSql("trunked_identity_summary"))))
        {
            int index = 1;
            statement.setInt(index++, scopeId);
            statement.setInt(index++, identity.kindCode());
            statement.setInt(index++, identity.id());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, countedCall);
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

    private static boolean upsertRelationship(Connection connection, int scopeId, int radioId, Identity destination,
                                              long observedAt, P25ActivityLogRecords.Action action,
                                              boolean countedCall, int encrypted, int recorded, int streamed,
                                              Integer encryptionAlgorithm, Integer encryptionKey) throws SQLException
    {
        Identity radio = new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, radioId);

        if(!identityExists(connection, scopeId, radio) || !identityExists(connection, scopeId, destination) ||
            !relationshipExists(connection, scopeId, radioId, destination) &&
                !hasScopeCapacity(connection, "trunked_radio_talkgroup_summary", scopeId,
                    MAX_RELATIONSHIPS_PER_SCOPE))
        {
            return false;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_radio_talkgroup_summary (
                scope_id, radio_id, talkgroup_id, target_kind_code, first_seen_ms, last_seen_ms, %s,
                encrypted_count, recorded_count, streamed_count,
                last_encryption_algorithm_id, last_encryption_key_id
            ) VALUES (?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?)
            ON CONFLICT(scope_id, radio_id, talkgroup_id, target_kind_code) DO UPDATE SET
                first_seen_ms = min(trunked_radio_talkgroup_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_radio_talkgroup_summary.last_seen_ms, excluded.last_seen_ms),
                %s,
                encrypted_count = trunked_radio_talkgroup_summary.encrypted_count + excluded.encrypted_count,
                recorded_count = trunked_radio_talkgroup_summary.recorded_count + excluded.recorded_count,
                streamed_count = trunked_radio_talkgroup_summary.streamed_count + excluded.streamed_count,
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
            statement.setInt(index++, scopeId);
            statement.setInt(index++, radioId);
            statement.setInt(index++, destination.id());
            statement.setInt(index++, destination.kindCode());
            statement.setLong(index++, observedAt);
            statement.setLong(index++, observedAt);
            index = setActionCounts(statement, index, action, countedCall);
            statement.setInt(index++, encrypted);
            statement.setInt(index++, recorded);
            statement.setInt(index++, streamed);
            setInteger(statement, index++, encryptionAlgorithm);
            setInteger(statement, index, encryptionKey);
            statement.executeUpdate();
        }

        return true;
    }

    private static boolean identityExists(Connection connection, int scopeId, Identity identity) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM trunked_identity_summary
            WHERE scope_id = ? AND identity_kind_code = ? AND identity_id = ?
            """))
        {
            statement.setInt(1, scopeId);
            statement.setInt(2, identity.kindCode());
            statement.setInt(3, identity.id());

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean relationshipExists(Connection connection, int scopeId, int radioId,
                                              Identity destination) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM trunked_radio_talkgroup_summary
            WHERE scope_id = ? AND radio_id = ? AND talkgroup_id = ? AND target_kind_code = ?
            """))
        {
            statement.setInt(1, scopeId);
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
    static boolean hasScopeCapacity(Connection connection, String table, int scopeId, int maximumRows)
        throws SQLException
    {
        if(maximumRows <= 0 || (!"trunked_identity_summary".equals(table) &&
            !"trunked_radio_talkgroup_summary".equals(table)))
        {
            throw new IllegalArgumentException("Invalid trunked identity admission bound");
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM " + table + " WHERE scope_id = ? LIMIT 1 OFFSET ?"))
        {
            statement.setInt(1, scopeId);
            statement.setInt(2, maximumRows - 1);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return !resultSet.next();
            }
        }
    }

    private static List<Identity> destinationIdentities(int protocolCode,
                                                        P25ActivityLogRecords.IdentityDomain identityDomain,
                                                        String targetId, String targetKind,
                                                        List<Integer> patchMembers)
    {
        Integer target = positive(targetId);
        Integer kind = TrunkedIdentityPolicy.identityKindCode(targetKind);
        Set<Identity> identities = new LinkedHashSet<>();

        if(kind != null && target != null &&
            TrunkedIdentityPolicy.isDirectoryIdentity(protocolCode, identityDomain, kind, target))
        {
            identities.add(new Identity(kind, target));
        }

        if(kind != null && kind == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP &&
            protocolCode == TrunkedIdentityPolicy.PROTOCOL_P25 && patchMembers != null)
        {
            for(Integer member: patchMembers)
            {
                if(TrunkedIdentityPolicy.isDirectoryTalkgroup(protocolCode, identityDomain, member))
                {
                    identities.add(new Identity(TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP, member));
                }
            }
        }

        return List.copyOf(identities);
    }

    private static List<Identity> groupDestinations(List<Identity> destinations)
    {
        return destinations.stream()
            .filter(identity -> identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_TALKGROUP ||
                identity.kindCode() == TrunkedIdentityPolicy.IDENTITY_KIND_PATCH_GROUP)
            .toList();
    }

    private static Context context(Connection connection, int contextId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT context.id, context.guid, context.protocol_code, context.system_key,
                   system.wacn, system.system_id
            FROM receiver_context context
            LEFT JOIN p25_system system ON system.system_key = context.system_key
            WHERE context.id = ? AND context.kind_code = 1
            """))
        {
            statement.setInt(1, contextId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return null;
                }

                return new Context(resultSet.getInt("id"), resultSet.getString("guid"),
                    nullableInteger(resultSet, "protocol_code"), nullableInteger(resultSet, "system_key"),
                    nullableInteger(resultSet, "wacn"), nullableInteger(resultSet, "system_id"));
            }
        }
    }

    private static int identityDomainCode(int protocolCode, P25ActivityLogRecords.IdentityDomain domain)
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

    private static P25ActivityLogRecords.IdentityDomain identityDomain(int code)
    {
        return switch(code)
        {
            case IDENTITY_DOMAIN_NXDN_TYPE_C -> P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_C;
            case IDENTITY_DOMAIN_NXDN_TYPE_D -> P25ActivityLogRecords.IdentityDomain.NXDN_TYPE_D;
            default -> P25ActivityLogRecords.IdentityDomain.STANDARD;
        };
    }

    private static String actionUpdateSql(String table)
    {
        return ACTION_COUNT_COLUMNS.stream()
            .map(column -> column + " = " + table + "." + column + " + excluded." + column)
            .collect(Collectors.joining(",\n                "));
    }

    private static int setActionCounts(PreparedStatement statement, int index,
                                       P25ActivityLogRecords.Action activityAction, boolean countedCall)
        throws SQLException
    {
        for(P25ActivityLogRecords.Action action: ACTIONS)
        {
            boolean counted = activityAction == action;

            if(action == P25ActivityLogRecords.Action.CALL)
            {
                counted = countedCall;
            }

            if(action == P25ActivityLogRecords.Action.UNKNOWN)
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

        if("trunked_identity_summary".equals(table))
        {
            sql = """
                DELETE FROM trunked_identity_summary
                WHERE (scope_id, identity_kind_code, identity_id) IN (
                    SELECT scope_id, identity_kind_code, identity_id
                    FROM trunked_identity_summary INDEXED BY idx_trunked_identity_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, scope_id, identity_kind_code, identity_id
                    LIMIT %d
                )
                """.formatted(DELETE_BATCH_SIZE);
        }
        else if("trunked_radio_talkgroup_summary".equals(table))
        {
            sql = """
                DELETE FROM trunked_radio_talkgroup_summary
                WHERE (scope_id, radio_id, talkgroup_id, target_kind_code) IN (
                    SELECT scope_id, radio_id, talkgroup_id, target_kind_code
                    FROM trunked_radio_talkgroup_summary INDEXED BY idx_trunked_radio_talkgroup_retention
                    WHERE last_seen_ms < ?
                    ORDER BY last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code
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

    private static void deleteOrphanContextScopes(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                DELETE FROM trunked_identity_scope
                WHERE NOT EXISTS (
                      SELECT 1 FROM trunked_identity_scope_context mapping
                      WHERE mapping.scope_id = trunked_identity_scope.scope_id
                  )
                """);
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

    record Scope(int scopeId, int protocolCode, P25ActivityLogRecords.IdentityDomain identityDomain,
                 String scopeToken, long firstSeenEpochMilliseconds)
    {
    }

    private record Context(int contextId, String guid, Integer protocolCode, Integer systemKey, Integer wacn,
                           Integer systemId)
    {
    }

    private record ExistingScope(int scopeId, int scopeKindCode, int identityDomainCode,
                                 long lastSeenEpochMilliseconds)
    {
    }

    private record Identity(int kindCode, int id)
    {
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

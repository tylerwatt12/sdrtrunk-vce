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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.configuration.ConfigurationChannelProjection;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Replaces the overloaded format-14 channel/site identity model with the stable format-15 radio-system and saved
 * channel model. Administrator-owned configuration is preserved. Receiver observations are deliberately reset
 * because their former GUID, context, and scope keys cannot be converted without guessing.
 */
final class Format14To15DatabaseMigration implements DatabaseMigrationStep
{
    private static final int MAXIMUM_CONFIGURATION_JSON_BYTES = 4_194_304;
    private static final int MAXIMUM_WEB_PREFERENCES_CHARACTERS = 131_072;
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String NOW_PLAYING_NODE = "user/io/github/dsheirer/preference/nowplaying";
    private static final String SITE_SETTINGS_REVISION_KEY = "site.settings.revision";
    private static final String RECEIVER_SETTINGS_REVISION_KEY = "receiver.settings.revision";
    private static final String SITE_ACCESS_POLICY_ID = "site-access";
    private static final String WEB_ACCESS_POLICY_ID = "web-access";
    private static final String SYSTEMS_POLICY_ID = "systems";
    private static final String CONVENTIONAL_POLICY_ID = "conventional";
    private static final String RADIO_POLICY_ID = "radio";
    private static final String OLD_IDENTITY_BOUNDARY = "trunked_identity_metrics_started_at_ms";
    private static final String RADIO_SYSTEM_BOUNDARY = "radio_system_metrics_started_at_ms";
    private static final String CONVENTIONAL_CALL_BOUNDARY = "conventional_call_output_metrics_started_at_ms";
    private static final String TRUNKED_CALL_BOUNDARY = "trunked_logical_call_metrics_started_at_ms";
    private static final String RETIRED_NAMED_CHANNEL_MAP_TABLE = "configuration_channel_map";
    private static final List<String> PRESERVED_AUTOINCREMENT_TABLES = List.of(
        "alias_list", "alias", "scan_list", "alias_broadcast_channel",
        "alias_list_unmatched_talkgroup_stream", "configuration_channel",
        "configuration_broadcast_stream", "web_user");
    private static final List<String> SUBSYSTEM_VERSION_KEYS = List.of(
        "alias_schema_version", "configuration_schema_version", "settings_schema_version", "icon_schema_version",
        "p25_activity_schema_version", "trunked_site_schema_version", "dmr_activity_schema_version");
    private static final List<String> REPLACED_METADATA_KEYS = replacedMetadataKeys();
    private static final List<String> ACTIVITY_VIEWS = List.of("p25_activity_event_resolved");
    private static final List<String> CALL_HISTORY_TABLES = List.of(
        "activity_event_talkgroup_member",
        "conventional_activity_bucket",
        "conventional_activity_summary",
        "conventional_call_identity_bucket",
        "dmr_conventional_radio_summary",
        "dmr_conventional_talkgroup_summary",
        "logger_status",
        "p25_activity_event",
        "p25_site_call_bucket",
        "p25_site_call_identity_bucket",
        "p25_zero_local_fq_talkgroup_summary",
        "trunked_logical_call_bucket",
        "trunked_logical_call_identity_bucket",
        "trunked_radio_affiliation",
        "trunked_radio_presence_lifecycle",
        "trunked_radio_site_presence",
        "trunked_radio_talkgroup_summary",
        "trunked_signaling_activity_bucket");
    private static final List<String> SITE_TABLES = List.of(
        "p25_foreign_system_band",
        "p25_foreign_system_band_summary",
        "p25_learned_site",
        "p25_site_channel",
        "p25_site_channel_summary",
        "p25_site_channel_tag",
        "p25_site_channel_tag_summary",
        "p25_site_frequency_band",
        "p25_site_frequency_band_summary",
        "p25_site_neighbor",
        "p25_site_neighbor_summary",
        "p25_site_patch_group",
        "p25_site_patch_group_radio",
        "p25_site_patch_group_radio_summary",
        "p25_site_patch_group_summary",
        "p25_site_patch_group_talkgroup",
        "p25_site_patch_group_talkgroup_summary",
        "p25_site_snapshot",
        "trunked_site_channel_summary",
        "trunked_site_neighbor_summary",
        "trunked_site_snapshot");
    private static final List<String> QUALITY_TABLES = List.of("p25_control_channel_quality");
    private static final List<String> IDENTITY_TABLES = List.of(
        "p25_system",
        "receiver_context",
        "trunked_identity_scope",
        "trunked_identity_scope_context",
        "trunked_identity_summary");
    private static final List<String> ACTIVITY_TABLES = activityTables();
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    @Override
    public String id()
    {
        return "format-14-to-15";
    }

    @Override
    public String description()
    {
        return "Use stable radio-system, channel, Alias List, and broadcast-provider identities";
    }

    @Override
    public int sourceVersion()
    {
        return 14;
    }

    @Override
    public int targetVersion()
    {
        return 15;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator-owned configuration", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Preserve channels, Alias Lists, stream providers, users, credentials, settings, icons, and decoder channel maps"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "saved channel relationships", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Use the saved channel UUID and Alias List ID as the only durable internal relationships and make DMR/NXDN channel type explicit"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "broadcast provider relationships", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Assign each provider a stable UUID, replace name-based Alias routes, and keep site selections ID-only"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "browser playback preferences", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Rename conversation playback fields for stable targets and increment each user preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "web access policy names", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Preserve access levels while combining Systems and Conventional under Radio and renaming whole-site access"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-settings revision", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Rename the shared receiver-settings revision without changing its value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "receiver activity and call history", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Restart derived event, call, affiliation, presence, and identity summaries at a clean boundary"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "learned site observations", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Restart derived site, neighbor, channel, band, and patch observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "signal-quality observations", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Restart derived control-channel signal and decode-quality observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "radio-system and receiver-channel identity cache", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Rebuild stable radio systems and saved-channel links from live decoder observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "redundant subsystem version markers", SUBSYSTEM_VERSION_KEYS.size(),
                "Keep the one authoritative whole-database format version"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired named Channel Maps", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Remove unused legacy named Channel Maps; decoder channel maps saved inside channels are preserved"));
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        return effects(inspect(connection));
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);

        dropActivitySchema(connection);
        dropRetiredNamedChannelMaps(connection);
        rebuildApplicationSchema(connection, input);

        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        seedMetricBoundaries(connection);
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        Objects.requireNonNull(connection, "Connection cannot be null");
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 14)
        {
            throw new SQLException("Migration step format-14-to-15 requires exact source format 14; found " +
                detected.version() + " [" + detected.id() + "]");
        }

        try
        {
            validatePreservedCoreRows(connection);
            AliasAdminIndex aliases = inspectAliasesAndScanLists(connection);
            List<ChannelRow> channels = inspectChannels(connection);
            List<BroadcastRow> broadcasts = inspectBroadcasts(connection);
            Map<String,List<BroadcastRow>> broadcastsByName = broadcastsByName(broadcasts);
            List<AliasRoute> aliasRoutes = inspectAliasRoutes(connection, broadcastsByName, aliases.aliasIds());
            List<UnmatchedRoute> unmatchedRoutes = inspectUnmatchedRoutes(connection, broadcastsByName,
                aliases.aliasListIds());
            List<PreferenceRow> preferences = inspectPreferences(connection);
            PolicyInput policy = inspectPolicy(connection);
            PortablePreferencesUpdate portablePreferences = inspectPortablePreferences(connection);
            Map<String,Long> sequences = inspectPreservedSequences(connection);
            long callHistoryRows = countRows(connection, CALL_HISTORY_TABLES);
            long siteRows = countRows(connection, SITE_TABLES);
            long qualityRows = countRows(connection, QUALITY_TABLES);
            long identityRows = countRows(connection, IDENTITY_TABLES);
            long retiredNamedChannelMapRows = countRows(connection, List.of(RETIRED_NAMED_CHANNEL_MAP_TABLE));
            long preservedRows = countRows(connection, List.of("alias_list", "alias", "scan_list",
                "alias_scan_list_membership", "alias_list_unmatched_talkgroup_scan_list_membership",
                "configuration_channel", "configuration_broadcast_stream",
                "application_settings", "application_icons", "web_user", "web_access_policy"));
            return new MigrationInput(channels, broadcasts, aliasRoutes, unmatchedRoutes, preferences, policy,
                portablePreferences, sequences, callHistoryRows, siteRows, qualityRows, identityRows,
                retiredNamedChannelMapRows, preservedRows);
        }
        catch(IOException | IllegalArgumentException exception)
        {
            throw new SQLException("Format-14 configuration cannot be migrated exactly: " +
                exception.getMessage(), exception);
        }
    }

    private static List<DatabaseMigrationEffect> effects(MigrationInput input)
    {
        long broadcastRelationships = Math.addExact(input.broadcasts().size(),
            Math.addExact(input.aliasRoutes().size(), input.unmatchedRoutes().size()));
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "administrator-owned configuration", input.preservedRows(),
                "Preserve channels, Alias Lists, stream providers, users, credentials, settings, icons, and decoder channel maps"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "saved channel relationships", input.channels().size(),
                "Use the saved channel UUID and Alias List ID as the only durable internal relationships and make DMR/NXDN channel type explicit"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "broadcast provider relationships", broadcastRelationships,
                "Assign each provider a stable UUID, replace name-based Alias routes, and keep site selections ID-only"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "browser playback preferences", input.preferences().size(),
                "Rename conversation playback fields for stable targets and increment each user preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "web access policy names", input.policy().transformedRows(),
                "Preserve access levels while combining Systems and Conventional under Radio and renaming whole-site access"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-settings revision", input.portablePreferences().transformed() ? 1 : 0,
                "Rename the shared receiver-settings revision without changing its value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "receiver activity and call history", input.callHistoryRows(),
                "Restart derived event, call, affiliation, presence, and identity summaries at a clean boundary"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "learned site observations", input.siteRows(),
                "Restart derived site, neighbor, channel, band, and patch observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "signal-quality observations", input.qualityRows(),
                "Restart derived control-channel signal and decode-quality observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "radio-system and receiver-channel identity cache", input.identityRows(),
                "Rebuild stable radio systems and saved-channel links from live decoder observations"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "redundant subsystem version markers", SUBSYSTEM_VERSION_KEYS.size(),
                "Keep the one authoritative whole-database format version"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired named Channel Maps", input.retiredNamedChannelMapRows(),
                "Remove unused legacy named Channel Maps; decoder channel maps saved inside channels are preserved"));
    }

    /** Validates every unchanged core row against the format-15 storage contract before any schema is renamed. */
    private static void validatePreservedCoreRows(Connection connection) throws SQLException, IOException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT key, value, updated_at_ms
            FROM database_metadata
            ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                Object keyValue = rows.getObject("key");
                if(keyValue instanceof String key && REPLACED_METADATA_KEYS.contains(key))
                {
                    continue;
                }

                String key = requiredStoredText(rows, "key", "Database metadata key");
                String label = "Database metadata " + displayName(key);
                if(key.isBlank())
                {
                    throw new IOException(label + " has a blank key");
                }
                requiredStoredText(rows, "value", label + " value");
                requirePositiveInteger(rows, "updated_at_ms", label + " update time");
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT key, settings_json, updated_at_ms, json_valid(settings_json) AS valid_json
            FROM application_settings
            ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String key = requiredStoredText(rows, "key", "Application setting key");
                String label = "Application setting " + displayName(key);
                if(key.isBlank())
                {
                    throw new IOException(label + " has a blank key");
                }
                requiredStoredText(rows, "settings_json", label + " document");
                if(integer(rows, "valid_json", label) != 1)
                {
                    throw new IOException(label + " is not valid JSON");
                }
                requirePositiveInteger(rows, "updated_at_ms", label + " update time");
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT key, icons_json, updated_at_ms, json_valid(icons_json) AS valid_json,
                   CASE WHEN json_valid(icons_json) THEN json_type(icons_json, '$') END AS json_root_type
            FROM application_icons
            ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String key = requiredStoredText(rows, "key", "Icon-set key");
                String label = "Icon set " + displayName(key);
                if(key.isBlank())
                {
                    throw new IOException(label + " has a blank key");
                }
                requiredStoredText(rows, "icons_json", label + " document");
                if(integer(rows, "valid_json", label) != 1 ||
                    !"object".equals(optionalStoredText(rows, "json_root_type", label + " JSON type")))
                {
                    throw new IOException(label + " must be a JSON object");
                }
                requirePositiveInteger(rows, "updated_at_ms", label + " update time");
            }
        }
    }

    /**
     * Validates the format-14 Alias and scan-list rows which are copied byte-for-byte into stricter format-15
     * tables. Matcher semantics are frozen here so a malformed administrator row is refused by name instead of
     * surfacing a raw SQLite constraint error after the migration has begun.
     */
    private static AliasAdminIndex inspectAliasesAndScanLists(Connection connection) throws SQLException, IOException
    {
        Map<Long,AliasListFamily> aliasLists = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, name, family, unmatched_talkgroup_record_enabled,
                   length(trim(name)) AS trimmed_name_length
            FROM alias_list
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "Alias List");
                String name = requiredStoredText(rows, "name", "Alias List database row " + id);
                String label = "Alias List " + displayName(name) + " (database row " + id + ")";
                int trimmedNameLength = integer(rows, "trimmed_name_length", label);
                if(trimmedNameLength < 1 || trimmedNameLength > 25)
                {
                    throw new IOException(label + " has a name outside 1 through 25 characters");
                }
                AliasListFamily family;
                try
                {
                    family = AliasListFamily.valueOf(requiredStoredText(rows, "family", label + " family"));
                }
                catch(IllegalArgumentException exception)
                {
                    throw new IOException(label + " has an unknown protocol family");
                }
                booleanFlag(rows, "unmatched_talkgroup_record_enabled", label);
                aliasLists.put(id, family);
            }
        }

        Set<Long> scanLists = new LinkedHashSet<>();
        int defaultScanLists = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order, name, description, published, is_default,
                   length(trim(name)) AS trimmed_name_length,
                   CASE WHEN description IS NULL THEN NULL ELSE length(trim(description)) END
                       AS trimmed_description_length
            FROM scan_list
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "Scan list");
                String name = requiredStoredText(rows, "name", "Scan list database row " + id);
                String label = "Scan list " + displayName(name) + " (database row " + id + ")";
                int sortOrder = integer(rows, "sort_order", label);
                if(sortOrder < 0)
                {
                    throw new IOException(label + " has a negative sort order");
                }
                int trimmedNameLength = integer(rows, "trimmed_name_length", label);
                if(trimmedNameLength < 1 || trimmedNameLength > 100)
                {
                    throw new IOException(label + " has an invalid name length");
                }
                String description = optionalStoredText(rows, "description", label + " description");
                Integer trimmedDescriptionLength = nullableInteger(rows, "trimmed_description_length", label);
                if(description != null && (trimmedDescriptionLength == null || trimmedDescriptionLength < 1 ||
                    trimmedDescriptionLength > 1000))
                {
                    throw new IOException(label + " has an invalid description length");
                }
                boolean published = booleanFlag(rows, "published", label);
                boolean isDefault = booleanFlag(rows, "is_default", label);
                if(isDefault && !published)
                {
                    throw new IOException(label + " is the Default list but is not published");
                }
                if(isDefault)
                {
                    defaultScanLists++;
                }
                scanLists.add(id);
            }
        }
        if(defaultScanLists != 1)
        {
            throw new IOException("Saved scan lists must contain exactly one published Default list; found " +
                defaultScanLists);
        }

        Set<Long> aliases = new LinkedHashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, alias_list_id, name, description, group_name, color, icon_name,
                   stream_as_talkgroup, record_enabled, matcher_type, protocol, value, min_value,
                   max_value, text_value, numeric_value, tone_sequence,
                   length(trim(name)) AS trimmed_name_length,
                   CASE WHEN text_value IS NULL THEN NULL ELSE length(trim(text_value)) END
                       AS trimmed_text_value_length
            FROM alias
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "Alias");
                String name = requiredStoredText(rows, "name", "Alias database row " + id);
                String label = "Alias " + displayName(name) + " (database row " + id + ")";
                if(integer(rows, "trimmed_name_length", label) < 1)
                {
                    throw new IOException(label + " has a blank name");
                }
                long aliasListId = positiveId(rows, "alias_list_id", label);
                AliasListFamily family = aliasLists.get(aliasListId);
                if(family == null)
                {
                    throw new IOException(label + " refers to missing Alias List database row " + aliasListId);
                }
                optionalStoredText(rows, "description", label + " description");
                optionalStoredText(rows, "group_name", label + " group");
                integer(rows, "color", label);
                optionalStoredText(rows, "icon_name", label + " icon");
                Integer streamAsTalkgroup = nullableInteger(rows, "stream_as_talkgroup", label);
                if(streamAsTalkgroup != null && (streamAsTalkgroup < 1 || streamAsTalkgroup > 0xFFFFFF))
                {
                    throw new IOException(label + " has stream-as talkgroup outside 1 through 16,777,215");
                }
                booleanFlag(rows, "record_enabled", label);
                validateAliasMatcher(rows, family, label);
                aliases.add(id);
            }
        }

        validateScanListMemberships(connection, "alias_scan_list_membership", "alias_id", aliases, scanLists,
            "Alias scan-list membership");
        validateScanListMemberships(connection, "alias_list_unmatched_talkgroup_scan_list_membership",
            "alias_list_id", aliasLists.keySet(), scanLists, "Alias List scan-list membership");
        return new AliasAdminIndex(Set.copyOf(aliasLists.keySet()), Set.copyOf(aliases));
    }

    private static void validateAliasMatcher(ResultSet rows, AliasListFamily family, String label)
        throws SQLException, IOException
    {
        AliasIDType type;
        try
        {
            type = AliasIDType.valueOf(requiredStoredText(rows, "matcher_type", label + " matcher type"));
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException(label + " has an unknown matcher type");
        }

        String protocolText = optionalStoredText(rows, "protocol", label + " protocol");
        Integer value = nullableInteger(rows, "value", label);
        Integer minimum = nullableInteger(rows, "min_value", label);
        Integer maximum = nullableInteger(rows, "max_value", label);
        String textValue = optionalStoredText(rows, "text_value", label + " text value");
        Integer trimmedTextValueLength = nullableInteger(rows, "trimmed_text_value_length", label);
        Integer numericValue = nullableInteger(rows, "numeric_value", label);
        String toneSequence = optionalStoredText(rows, "tone_sequence", label + " tone sequence");

        switch(type)
        {
            case TALKGROUP, RADIO_ID -> {
                requireAliasPayload(label, protocolText != null && value != null && minimum == null && maximum == null &&
                    textValue == null && numericValue == null && toneSequence == null);
                validateProtocolMatcher(family, type, protocolText, value, value, label);
            }
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> {
                requireAliasPayload(label, protocolText != null && value == null && minimum != null && maximum != null &&
                    minimum < maximum && textValue == null && numericValue == null && toneSequence == null);
                validateProtocolMatcher(family, type, protocolText, minimum, maximum, label);
            }
            case STATUS, UNIT_STATUS -> {
                requireAliasPayload(label, protocolText == null && value == null && minimum == null && maximum == null &&
                    textValue == null && numericValue != null && toneSequence == null);
                if(numericValue < 0 || numericValue > 255)
                {
                    throw new IOException(label + " status value is outside 0 through 255");
                }
                boolean supported = type == AliasIDType.STATUS ?
                    family == AliasListFamily.P25 || family == AliasListFamily.NBFM :
                    family == AliasListFamily.P25 || family == AliasListFamily.DMR;
                if(!supported)
                {
                    throw new IOException(label + " matcher is not supported by its Alias List family");
                }
            }
            case TONES -> {
                requireAliasPayload(label, protocolText == null && value == null && minimum == null && maximum == null &&
                    textValue == null && numericValue == null && toneSequence != null && !toneSequence.isEmpty());
                if(family != AliasListFamily.P25 && family != AliasListFamily.DMR)
                {
                    throw new IOException(label + " tone matcher is not supported by its Alias List family");
                }
                validateToneSequence(toneSequence, label);
            }
            case DCS -> {
                requireAliasPayload(label, protocolText == null && value == null && minimum == null && maximum == null &&
                    textValue != null && numericValue == null && toneSequence == null);
                if(family != AliasListFamily.NBFM)
                {
                    throw new IOException(label + " DCS matcher is not supported by its Alias List family");
                }
                try
                {
                    if(DCSCode.valueOf(textValue) == DCSCode.UNKNOWN)
                    {
                        throw new IllegalArgumentException("unknown");
                    }
                }
                catch(IllegalArgumentException exception)
                {
                    throw new IOException(label + " has an unknown DCS code");
                }
            }
            case ESN -> {
                requireAliasPayload(label, protocolText == null && value == null && minimum == null && maximum == null &&
                    textValue != null && trimmedTextValueLength != null && trimmedTextValueLength > 0 &&
                    numericValue == null && toneSequence == null);
                if(family != AliasListFamily.NBFM)
                {
                    throw new IOException(label + " ESN matcher is not supported by its Alias List family");
                }
            }
            default -> throw new IOException(label + " has a matcher type that format 15 does not support");
        }
    }

    private static void validateProtocolMatcher(AliasListFamily family, AliasIDType type, String protocolText,
                                                int minimum, int maximum, String label) throws IOException
    {
        Protocol protocol;
        try
        {
            protocol = Protocol.valueOf(protocolText);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw new IOException(label + " has an unknown protocol");
        }

        boolean talkgroup = type == AliasIDType.TALKGROUP || type == AliasIDType.TALKGROUP_RANGE;
        boolean familyMatch = switch(family)
        {
            case P25 -> protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
            case DMR -> protocol == Protocol.DMR;
            case NXDN -> protocol == Protocol.NXDN;
            case NBFM -> talkgroup && Set.of(Protocol.AM, Protocol.NBFM, Protocol.FLEETSYNC,
                Protocol.MDC1200).contains(protocol);
        };
        if(!familyMatch)
        {
            throw new IOException(label + " protocol does not match its Alias List family");
        }

        int allowedMinimum = talkgroup ? TalkgroupFormat.get(protocol).getMinimumValidValue() :
            RadioFormat.get(protocol).getMinimumValidValue();
        int allowedMaximum = talkgroup ? TalkgroupFormat.get(protocol).getMaximumValidValue() :
            RadioFormat.get(protocol).getMaximumValidValue();
        if(minimum < allowedMinimum || maximum > allowedMaximum)
        {
            throw new IOException(label + " matcher value is outside the valid range for its protocol");
        }
    }

    private static void validateToneSequence(String value, String label) throws IOException
    {
        StringJoiner canonical = new StringJoiner(",");
        for(String encodedTone: value.split(",", -1))
        {
            String[] parts = encodedTone.split(":", -1);
            if(parts.length != 2)
            {
                throw new IOException(label + " has a malformed tone sequence");
            }
            AmbeTone tone;
            int duration;
            try
            {
                tone = AmbeTone.valueOf(parts[0]);
                duration = Integer.parseInt(parts[1]);
            }
            catch(IllegalArgumentException exception)
            {
                throw new IOException(label + " has a malformed tone sequence");
            }
            canonical.add(tone.name() + ':' + duration);
        }
        if(!value.equals(canonical.toString()))
        {
            throw new IOException(label + " tone sequence is not stored in canonical form");
        }
    }

    private static void requireAliasPayload(String label, boolean valid) throws IOException
    {
        if(!valid)
        {
            throw new IOException(label + " matcher fields do not match its type");
        }
    }

    private static void validateScanListMemberships(Connection connection, String table, String ownerColumn,
                                                     Set<Long> owners, Set<Long> scanLists, String description)
        throws SQLException, IOException
    {
        String sql = "SELECT " + identifier(ownerColumn) + ", scan_list_id FROM " + identifier(table) +
            " ORDER BY " + identifier(ownerColumn) + ", scan_list_id";
        try(PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long ownerId = positiveId(rows, ownerColumn, description);
                long scanListId = positiveId(rows, "scan_list_id", description);
                if(!owners.contains(ownerId))
                {
                    throw new IOException(description + " refers to missing owner database row " + ownerId);
                }
                if(!scanLists.contains(scanListId))
                {
                    throw new IOException(description + " refers to missing scan list database row " + scanListId);
                }
            }
        }
    }

    private static List<ChannelRow> inspectChannels(Connection connection) throws SQLException, IOException
    {
        List<ChannelRow> channels = new ArrayList<>();
        Map<String,String> configurationIds = new HashMap<>();
        Map<String,String> radioResolveIds = new HashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                   radres_guid, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                   frequency_count, recording_enabled, event_logging_enabled, config_json
            FROM configuration_channel
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long rowId = positiveId(rows, "id", "saved channel");
                String systemName = nullableText(rows, "system_name");
                String siteName = nullableText(rows, "site_name");
                String name = nullableText(rows, "name");
                String label = savedChannelLabel(rowId, systemName, siteName, name);
                String configurationId = canonicalUuid(text(rows, "configuration_id"),
                    label + " stable ID");
                String priorChannel = configurationIds.putIfAbsent(configurationId, label);
                if(priorChannel != null)
                {
                    throw new IOException(label + " and " + priorChannel + " use the same stable ID");
                }

                ObjectNode payload = parseObject(text(rows, "config_json"), label);
                normalizeLegacyChannelMode(payload, nullableText(rows, "decoder_type"), label);
                requireJsonText(payload, "configurationId", configurationId, label, false, true);
                boolean autoStart = booleanFlag(rows, "auto_start", label);
                Integer autoStartOrder = nullableInteger(rows, "auto_start_order", label);
                requireJsonBooleanProjection(payload, List.of("autoStart", "enabled"), autoStart, label,
                    "auto-start");
                requireJsonIntegerProjection(payload, List.of("autoStartOrder", "order"), autoStartOrder, label,
                    "auto-start order");
                Channel channel = decodeChannel(payload, label);
                if(channel.isConfigurationIdPersistenceRequired() || !configurationId.equals(channel.getConfigurationId()))
                {
                    throw new IOException(label + " changes its stable identity while decoding");
                }
                if(!ChannelConfigurationPolicy.isActive(channel))
                {
                    throw new IOException(label + " is not an active supported channel");
                }

                String channelKind = text(rows, "channel_kind");
                if(!ChannelConfigurationPolicy.requireChannelKind(channel).name().equals(channelKind))
                {
                    throw new IOException(label + " channel_kind does not match config_json");
                }

                requireJsonText(payload, "system", systemName, label, false, systemName != null);
                requireJsonText(payload, "site", siteName, label, false, siteName != null);
                requireJsonText(payload, "name", name, label, false, name != null);

                String aliasListName = normalizeBlank(nullableText(rows, "alias_list_name"));
                requireJsonText(payload, "aliasListName", aliasListName, label, true, aliasListName != null);
                Long aliasListId = resolveAliasList(connection, aliasListName, label);
                requireJsonLongProjection(payload, "aliasListId", aliasListId, label, "Alias List ID");

                String radioResolveId = normalizeBlank(nullableText(rows, "radres_guid"));
                requireRadioResolveJson(payload, radioResolveId, label);
                if(radioResolveId != null)
                {
                    radioResolveId = canonicalUuid(radioResolveId, label + " RadioResolve ID");
                    String priorChannelWithRadioResolveId = radioResolveIds.putIfAbsent(radioResolveId, label);
                    if(priorChannelWithRadioResolveId != null)
                    {
                        throw new IOException(label + " and " + priorChannelWithRadioResolveId +
                            " use the same RadioResolve ID");
                    }
                }

                if(channel.getAutoStart() != autoStart || !Objects.equals(channel.getAutoStartOrder(), autoStartOrder))
                {
                    throw new IOException(label + " auto-start fields do not match config_json");
                }
                ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);
                requireOldProjectionMatches(rows, channel, projection, label);

                int sortOrder = integer(rows, "sort_order", label);
                if(sortOrder < 0)
                {
                    throw new IOException(label + " has a negative sort order");
                }
                String decoderType = projection.decoderType();
                if(decoderType != null && decoderType.isBlank())
                {
                    throw new IOException(label + " has a blank decoder type");
                }
                Long primaryFrequency = projection.primaryFrequencyHz();
                if(primaryFrequency != null && primaryFrequency <= 0)
                {
                    throw new IOException(label + " has a nonpositive primary frequency");
                }

                payload.remove(List.of("configurationId", "system", "site", "name", "aliasListName", "aliasListId",
                    "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder",
                    "order", "channelType"));
                channels.add(new ChannelRow(rowId, configurationId, channelKind, sortOrder,
                    systemName, siteName, name, aliasListId, radioResolveId, autoStart, autoStartOrder,
                    decoderType, projection.addressDomainCode(), primaryFrequency,
                    MAPPER.writeValueAsString(payload)));
            }
        }
        return List.copyOf(channels);
    }

    private static void requireOldProjectionMatches(ResultSet rows, Channel channel,
                                                    ConfigurationChannelProjection projection, String label)
        throws SQLException, IOException
    {
        SourceConfiguration source = channel.getSourceConfiguration();
        String sourceType = source != null && source.getSourceType() != null ? source.getSourceType().name() : null;
        int frequencyCount = channel.getFrequencyList() != null ? channel.getFrequencyList().size() : 0;
        RecordConfiguration record = channel.getRecordConfiguration();
        EventLogConfiguration eventLog = channel.getEventLogConfiguration();
        boolean recording = record != null && record.getRecorders() != null && !record.getRecorders().isEmpty();
        boolean logging = eventLog != null && eventLog.getLoggers() != null && !eventLog.getLoggers().isEmpty();

        if(!Objects.equals(projection.decoderType(), nullableText(rows, "decoder_type")) ||
            !Objects.equals(sourceType, nullableText(rows, "source_type")) ||
            !Objects.equals(projection.primaryFrequencyHz(), nullableLong(rows, "primary_frequency_hz", label)) ||
            frequencyCount != integer(rows, "frequency_count", label) ||
            recording != booleanFlag(rows, "recording_enabled", label) ||
            logging != booleanFlag(rows, "event_logging_enabled", label))
        {
            throw new IOException(label + " query projection does not match config_json");
        }
    }

    private static Long resolveAliasList(Connection connection, String name, String label)
        throws SQLException, IOException
    {
        if(name == null)
        {
            return null;
        }

        List<Long> matches = new ArrayList<>(2);
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id FROM alias_list WHERE name = ? COLLATE NOCASE ORDER BY id LIMIT 2
            """))
        {
            statement.setString(1, name);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    matches.add(rows.getLong(1));
                }
            }
        }
        if(matches.isEmpty())
        {
            throw new IOException(label + " names missing Alias List [" + name + "]");
        }
        if(matches.size() != 1)
        {
            throw new IOException(label + " names ambiguous Alias List [" + name + "]");
        }
        return matches.getFirst();
    }

    private static List<BroadcastRow> inspectBroadcasts(Connection connection) throws SQLException, IOException
    {
        List<BroadcastCandidate> candidates = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order, name, server_type, enabled, host, port, delay_ms,
                   maximum_recording_age_ms, config_json
            FROM configuration_broadcast_stream
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long rowId = positiveId(rows, "id", "broadcast provider");
                String name = nullableText(rows, "name");
                String label = broadcastProviderLabel(rowId, name);
                int sortOrder = integer(rows, "sort_order", label);
                if(sortOrder < 0)
                {
                    throw new IOException(label + " has a negative sort_order");
                }
                ObjectNode payload = parseObject(text(rows, "config_json"), label);
                normalizeLegacyBroadcastType(payload);
                BroadcastConfiguration configuration = decodeBroadcast(payload, label);
                String serverType = nullableText(rows, "server_type");
                String expectedServerType = configuration.getBroadcastServerType() != null ?
                    configuration.getBroadcastServerType().name() : null;
                if(!Objects.equals(name, configuration.getName()) ||
                    !Objects.equals(serverType, expectedServerType) ||
                    booleanFlag(rows, "enabled", label) != configuration.isEnabled() ||
                    !Objects.equals(nullableText(rows, "host"), configuration.getHost()) ||
                    integer(rows, "port", label) != configuration.getPort() ||
                    longInteger(rows, "delay_ms", label) != configuration.getDelay() ||
                    longInteger(rows, "maximum_recording_age_ms", label) != configuration.getMaximumRecordingAge())
                {
                    throw new IOException(label + " query projection does not match config_json");
                }

                JsonNode storedId = payload.get("configurationId");
                payload.remove(List.of("configurationId", "aliasListName"));
                candidates.add(new BroadcastCandidate(rowId, tryCanonicalUuid(storedId), sortOrder, name,
                    MAPPER.writeValueAsString(payload)));
            }
        }

        Map<String,Integer> occurrences = new HashMap<>();
        for(BroadcastCandidate candidate: candidates)
        {
            if(candidate.candidateConfigurationId() != null)
            {
                occurrences.merge(candidate.candidateConfigurationId(), 1, Math::addExact);
            }
        }

        Set<String> unavailable = new HashSet<>();
        occurrences.forEach((id, count) ->
        {
            if(count == 1)
            {
                unavailable.add(id);
            }
        });

        List<BroadcastRow> broadcasts = new ArrayList<>(candidates.size());
        for(BroadcastCandidate candidate: candidates)
        {
            String candidateId = candidate.candidateConfigurationId();
            String configurationId = candidateId != null && occurrences.get(candidateId) == 1 ? candidateId :
                deterministicBroadcastId(candidate.id(), unavailable);
            unavailable.add(configurationId);
            broadcasts.add(new BroadcastRow(candidate.id(), configurationId, candidate.sortOrder(), candidate.name(),
                candidate.payload()));
        }
        return List.copyOf(broadcasts);
    }

    private static Map<String,List<BroadcastRow>> broadcastsByName(List<BroadcastRow> broadcasts)
    {
        Map<String,List<BroadcastRow>> byName = new LinkedHashMap<>();
        for(BroadcastRow broadcast: broadcasts)
        {
            if(broadcast.name() != null && !broadcast.name().isBlank())
            {
                byName.computeIfAbsent(broadcast.name(), ignored -> new ArrayList<>()).add(broadcast);
            }
        }
        byName.replaceAll((ignored, matches) -> List.copyOf(matches));
        return Map.copyOf(byName);
    }

    private static List<AliasRoute> inspectAliasRoutes(Connection connection,
                                                        Map<String,List<BroadcastRow>> broadcastsByName,
                                                        Set<Long> aliasIds)
        throws SQLException, IOException
    {
        List<AliasRoute> routes = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, alias_id, channel_name FROM alias_broadcast_channel ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "Alias broadcast route");
                String name = text(rows, "channel_name");
                long aliasId = positiveId(rows, "alias_id", "Alias broadcast route");
                if(!aliasIds.contains(aliasId))
                {
                    throw new IOException("Alias broadcast route database row " + id +
                        " refers to missing Alias database row " + aliasId);
                }
                routes.add(new AliasRoute(id, aliasId,
                    resolveBroadcast(name, broadcastsByName,
                        "Alias stream route for " + displayName(name) + " (database row " + id + ")")));
            }
        }
        return List.copyOf(routes);
    }

    private static List<UnmatchedRoute> inspectUnmatchedRoutes(Connection connection,
                                                                Map<String,List<BroadcastRow>> broadcastsByName,
                                                                Set<Long> aliasListIds)
        throws SQLException, IOException
    {
        List<UnmatchedRoute> routes = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, alias_list_id, channel_name FROM alias_list_unmatched_talkgroup_stream ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "unmatched-talkgroup broadcast route");
                String name = text(rows, "channel_name");
                long aliasListId = positiveId(rows, "alias_list_id", "unmatched-talkgroup broadcast route");
                if(!aliasListIds.contains(aliasListId))
                {
                    throw new IOException("Unmatched-talkgroup broadcast route database row " + id +
                        " refers to missing Alias List database row " + aliasListId);
                }
                routes.add(new UnmatchedRoute(id, aliasListId,
                    resolveBroadcast(name, broadcastsByName,
                        "Unmatched-talkgroup stream route for " + displayName(name) +
                            " (database row " + id + ")")));
            }
        }
        return List.copyOf(routes);
    }

    private static String resolveBroadcast(String name, Map<String,List<BroadcastRow>> broadcastsByName,
                                           String label) throws IOException
    {
        List<BroadcastRow> matches = broadcastsByName.getOrDefault(name, List.of());
        if(matches.isEmpty())
        {
            throw new IOException(label + " refers to a missing broadcast provider");
        }
        if(matches.size() != 1)
        {
            throw new IOException(label + " has an ambiguous broadcast provider name because it matches more " +
                "than one provider");
        }
        return matches.getFirst().configurationId();
    }

    private static List<PreferenceRow> inspectPreferences(Connection connection) throws SQLException, IOException
    {
        List<PreferenceRow> preferences = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                   password_iterations, password_derived_key_bits, password_salt, password_hash,
                   password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                   created_at_ms, updated_at_ms,
                   CASE WHEN username=lower(username) THEN 1 ELSE 0 END AS canonical_username,
                   length(preferences_json) AS preferences_length,
                   CASE WHEN json_valid(preferences_json) THEN json_type(preferences_json, '$') END
                       AS preferences_root_type
            FROM web_user
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                long id = positiveId(rows, "id", "web user");
                String username = requiredStoredText(rows, "username", "Web user database row " + id);
                String label = "Web user " + displayName(username) + " (database row " + id + ")";
                if(username.length() < 1 || username.length() > 64 ||
                    integer(rows, "canonical_username", label) != 1 ||
                    !username.substring(0, 1).matches("[a-z0-9]") ||
                    !username.matches("[a-z0-9][a-z0-9._-]*"))
                {
                    throw new IOException(label + " has an invalid username");
                }

                String tier = requiredStoredText(rows, "tier", label + " access tier");
                if(!Set.of("USER", "ADMIN").contains(tier))
                {
                    throw new IOException(label + " has an invalid access tier");
                }
                boolean primary = booleanFlag(rows, "primary_admin", label);
                if((primary && (!"admin".equals(username) || !"ADMIN".equals(tier))) ||
                    (!primary && "admin".equals(username)))
                {
                    throw new IOException(label + " has invalid primary-administrator settings");
                }
                if(longInteger(rows, "credential_version", label) != 1)
                {
                    throw new IOException(label + " has an unsupported credential version");
                }
                if(!"PBKDF2WithHmacSHA256".equals(requiredStoredText(rows, "password_algorithm", label)))
                {
                    throw new IOException(label + " has an unsupported password algorithm");
                }
                long iterations = longInteger(rows, "password_iterations", label);
                if(iterations < 600_000 || iterations > 5_000_000)
                {
                    throw new IOException(label + " has a password work factor outside the supported range");
                }
                if(longInteger(rows, "password_derived_key_bits", label) != 256)
                {
                    throw new IOException(label + " has an unsupported password verifier size");
                }
                byte[] salt = requiredStoredBlob(rows, "password_salt", label + " password salt");
                byte[] hash = requiredStoredBlob(rows, "password_hash", label + " password verifier");
                try
                {
                    if(salt.length < 16 || salt.length > 64)
                    {
                        throw new IOException(label + " has an invalid password salt size");
                    }
                    if(hash.length != 32)
                    {
                        throw new IOException(label + " has an invalid password verifier size");
                    }
                }
                finally
                {
                    Arrays.fill(salt, (byte)0);
                    Arrays.fill(hash, (byte)0);
                }
                requirePositiveInteger(rows, "password_changed_at_ms", label + " password-change time");
                requirePositiveInteger(rows, "auth_revision", label + " authentication revision");
                String preferencesJson = requiredStoredText(rows, "preferences_json", label + " preferences");
                if(integer(rows, "preferences_length", label) > MAXIMUM_WEB_PREFERENCES_CHARACTERS ||
                    !"object".equals(optionalStoredText(rows, "preferences_root_type", label)))
                {
                    throw new IOException(label + " preferences must be a bounded JSON object");
                }
                long revision = longInteger(rows, "preferences_revision", label);
                if(revision <= 0 || revision == Long.MAX_VALUE)
                {
                    throw new IOException(label + " has a preference revision outside the supported range");
                }
                requirePositiveInteger(rows, "created_at_ms", label + " creation time");
                long updatedAt = requirePositiveInteger(rows, "updated_at_ms", label + " update time");
                preferences.add(new PreferenceRow(id, Format14WebUserPreferencesCodec.migrate(preferencesJson),
                    revision + 1, updatedAt));
            }
        }
        return List.copyOf(preferences);
    }

    private static PortablePreferencesUpdate inspectPortablePreferences(Connection connection)
        throws SQLException, IOException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT settings_json, updated_at_ms
            FROM application_settings
            WHERE key=?
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);
            try(ResultSet rows = statement.executeQuery())
            {
                if(!rows.next())
                {
                    return new PortablePreferencesUpdate(null, 0, false);
                }

                String json = requiredStoredText(rows, "settings_json", "Portable preferences");
                long updatedAt = requirePositiveInteger(rows, "updated_at_ms", "Portable preference update time");
                JsonNode parsed = MAPPER.readTree(json);
                if(!(parsed instanceof ObjectNode root))
                {
                    throw new IOException("Portable preferences must be a JSON object");
                }

                JsonNode node = root.get(NOW_PLAYING_NODE);
                if(node == null)
                {
                    return new PortablePreferencesUpdate(null, updatedAt, false);
                }
                if(!(node instanceof ObjectNode nowPlaying))
                {
                    throw new IOException("Receiver preferences must be a JSON object");
                }
                if(nowPlaying.has(RECEIVER_SETTINGS_REVISION_KEY))
                {
                    throw new IOException("Receiver preferences already contain the future receiver-settings " +
                        "revision and are not an exact format-14 state");
                }

                JsonNode revision = nowPlaying.get(SITE_SETTINGS_REVISION_KEY);
                if(revision == null)
                {
                    return new PortablePreferencesUpdate(null, updatedAt, false);
                }
                if(!revision.isTextual())
                {
                    throw new IOException("The saved site-settings revision is not text");
                }
                long value;
                try
                {
                    value = Long.parseLong(revision.textValue());
                }
                catch(NumberFormatException exception)
                {
                    throw new IOException("The saved site-settings revision is not a positive whole number", exception);
                }
                if(value <= 0 || value == Long.MAX_VALUE || !Long.toString(value).equals(revision.textValue()))
                {
                    throw new IOException("The saved site-settings revision is not a positive whole number");
                }

                nowPlaying.remove(SITE_SETTINGS_REVISION_KEY);
                nowPlaying.set(RECEIVER_SETTINGS_REVISION_KEY, revision);
                return new PortablePreferencesUpdate(MAPPER.writeValueAsString(root), updatedAt, true);
            }
        }
    }

    private static PolicyInput inspectPolicy(Connection connection) throws SQLException, IOException
    {
        Map<String,PolicyRow> source = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT capability_id, required_tier, updated_at_ms
            FROM web_access_policy
            ORDER BY capability_id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String id = requiredStoredText(rows, "capability_id", "Web access policy capability");
                String label = "Web access policy " + displayName(id);
                if(id.length() < 1 || id.length() > 64 || !id.equals(id.toLowerCase(java.util.Locale.ROOT)))
                {
                    throw new IOException(label + " has an invalid capability name");
                }
                String tier = requiredStoredText(rows, "required_tier", label + " tier");
                if(!Set.of("PUBLIC", "USER", "ADMIN").contains(tier))
                {
                    throw new IOException(label + " has an invalid access tier");
                }
                long updatedAt = requirePositiveInteger(rows, "updated_at_ms", label + " update time");
                if("PUBLIC".equals(tier))
                {
                    throw new IOException(label + " redundantly stores its default PUBLIC access tier");
                }
                source.put(id, new PolicyRow(id, tier, updatedAt));
            }
        }

        PolicyRow systems = source.remove(SYSTEMS_POLICY_ID);
        PolicyRow conventional = source.remove(CONVENTIONAL_POLICY_ID);
        String tier = moreRestrictive(systems != null ? systems.tier() : "PUBLIC",
            conventional != null ? conventional.tier() : "PUBLIC");
        long updatedAt = Math.max(systems != null ? systems.updatedAtMs() : 0,
            conventional != null ? conventional.updatedAtMs() : 0);
        PolicyRow siteAccess = source.remove(SITE_ACCESS_POLICY_ID);

        List<PolicyRow> target = new ArrayList<>(source.values());
        if(siteAccess != null)
        {
            target.add(new PolicyRow(WEB_ACCESS_POLICY_ID, siteAccess.tier(), siteAccess.updatedAtMs()));
        }
        if(!"PUBLIC".equals(tier))
        {
            target.add(new PolicyRow(RADIO_POLICY_ID, tier, updatedAt));
        }
        target.sort(java.util.Comparator.comparing(PolicyRow::capabilityId));
        int transformed = (systems != null ? 1 : 0) + (conventional != null ? 1 : 0) +
            (siteAccess != null ? 1 : 0);
        return new PolicyInput(List.copyOf(target), transformed);
    }

    private static String moreRestrictive(String first, String second)
    {
        return accessRank(first) >= accessRank(second) ? first : second;
    }

    private static int accessRank(String tier)
    {
        return switch(tier)
        {
            case "PUBLIC" -> 0;
            case "USER" -> 1;
            case "ADMIN" -> 2;
            default -> throw new IllegalArgumentException("Unknown access tier " + tier);
        };
    }

    private static void dropActivitySchema(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            for(String view: ACTIVITY_VIEWS)
            {
                statement.executeUpdate("DROP VIEW " + identifier(view));
            }
        }

        Set<String> remaining = new LinkedHashSet<>(ACTIVITY_TABLES);
        Map<String,Set<String>> referencedParents = new HashMap<>();
        for(String table: remaining)
        {
            Set<String> parents = new HashSet<>();
            try(Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_list(" + identifier(table) + ")"))
            {
                while(rows.next())
                {
                    String parent = rows.getString("table");
                    if(remaining.contains(parent))
                    {
                        parents.add(parent);
                    }
                }
            }
            referencedParents.put(table, Set.copyOf(parents));
        }

        try(Statement statement = connection.createStatement())
        {
            while(!remaining.isEmpty())
            {
                String next = remaining.stream().filter(candidate -> remaining.stream().noneMatch(other ->
                    !candidate.equals(other) && referencedParents.getOrDefault(other, Set.of()).contains(candidate)))
                    .sorted().findFirst().orElseThrow(() ->
                        new SQLException("Receiver activity schema contains a foreign-key cycle"));
                statement.executeUpdate("DROP TABLE " + identifier(next));
                remaining.remove(next);
            }
        }
    }

    private static void dropRetiredNamedChannelMaps(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_configuration_channel_map_sort");
            statement.executeUpdate("DROP TABLE " + RETIRED_NAMED_CHANNEL_MAP_TABLE);
        }
    }

    /**
     * Rebuilds the complete connected administrator-owned application schema. Format 15 tightens the persisted
     * contracts for core, Alias, channel, stream, account, and access-policy rows, so leaving any format-14 table in
     * place would make a migrated database structurally different from a fresh one. All source tables stay inside
     * the caller-owned transaction until their target rows and planned transformations have succeeded.
     */
    private static void rebuildApplicationSchema(Connection connection, MigrationInput input) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW alias_talkgroup");
            statement.executeUpdate("DROP VIEW alias_radio");
            for(String index: List.of(
                "idx_alias_talkgroup_value", "idx_alias_talkgroup_range", "idx_alias_radio_value",
                "idx_alias_radio_range", "idx_alias_broadcast_channel_name", "idx_scan_list_one_default",
                "idx_alias_scan_list_by_list", "idx_alias_list_unmatched_talkgroup_scan_list_by_list",
                "idx_configuration_channel_sort", "idx_configuration_channel_alias_list",
                "idx_configuration_channel_decoder", "idx_configuration_channel_frequency",
                "idx_configuration_channel_unique_radres_guid", "idx_configuration_broadcast_sort",
                "idx_configuration_broadcast_type", "idx_web_user_one_primary_admin"))
            {
                statement.executeUpdate("DROP INDEX " + identifier(index));
            }
            statement.executeUpdate("ALTER TABLE database_metadata RENAME TO format14_database_metadata");
            statement.executeUpdate("ALTER TABLE application_settings RENAME TO format14_application_settings");
            statement.executeUpdate("ALTER TABLE application_icons RENAME TO format14_application_icons");
            statement.executeUpdate("ALTER TABLE web_user RENAME TO format14_web_user");
            statement.executeUpdate("ALTER TABLE web_access_policy RENAME TO format14_web_access_policy");
            statement.executeUpdate("ALTER TABLE alias_broadcast_channel RENAME TO format14_alias_broadcast_channel");
            statement.executeUpdate("ALTER TABLE alias_list_unmatched_talkgroup_stream " +
                "RENAME TO format14_alias_list_unmatched_talkgroup_stream");
            statement.executeUpdate("ALTER TABLE alias_scan_list_membership " +
                "RENAME TO format14_alias_scan_list_membership");
            statement.executeUpdate("ALTER TABLE alias_list_unmatched_talkgroup_scan_list_membership " +
                "RENAME TO format14_alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("ALTER TABLE configuration_channel RENAME TO format14_configuration_channel");
            statement.executeUpdate("ALTER TABLE configuration_broadcast_stream " +
                "RENAME TO format14_configuration_broadcast_stream");
            statement.executeUpdate("ALTER TABLE alias RENAME TO format14_alias");
            statement.executeUpdate("ALTER TABLE alias_list RENAME TO format14_alias_list");
            statement.executeUpdate("ALTER TABLE scan_list RENAME TO format14_scan_list");
        }

        SdrTrunkDatabaseSchema.create(connection);
        copyUnchangedApplicationRows(connection);
        insertChannels(connection, input.channels());
        insertBroadcasts(connection, input.broadcasts());
        insertAliasRoutes(connection, input.aliasRoutes());
        insertUnmatchedRoutes(connection, input.unmatchedRoutes());
        insertPolicies(connection, input.policy());
        applyPortablePreferencesUpdate(connection, input.portablePreferences());
        migratePreferences(connection, input.preferences());

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE format14_alias_broadcast_channel");
            statement.executeUpdate("DROP TABLE format14_alias_list_unmatched_talkgroup_stream");
            statement.executeUpdate("DROP TABLE format14_alias_scan_list_membership");
            statement.executeUpdate("DROP TABLE format14_alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DROP TABLE format14_configuration_channel");
            statement.executeUpdate("DROP TABLE format14_configuration_broadcast_stream");
            statement.executeUpdate("DROP TABLE format14_alias");
            statement.executeUpdate("DROP TABLE format14_alias_list");
            statement.executeUpdate("DROP TABLE format14_scan_list");
            statement.executeUpdate("DROP TABLE format14_web_access_policy");
            statement.executeUpdate("DROP TABLE format14_web_user");
            statement.executeUpdate("DROP TABLE format14_application_icons");
            statement.executeUpdate("DROP TABLE format14_application_settings");
            statement.executeUpdate("DROP TABLE format14_database_metadata");
        }
        restorePreservedSequences(connection, input.sequences());
    }

    private static Map<String,Long> inspectPreservedSequences(Connection connection) throws SQLException, IOException
    {
        Map<String,Long> sequences = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT name, seq FROM sqlite_sequence ORDER BY name
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String name = requiredStoredText(rows, "name", "SQLite sequence table name");
                if(!PRESERVED_AUTOINCREMENT_TABLES.contains(name))
                {
                    continue;
                }
                long sequence = longInteger(rows, "seq", "SQLite sequence for " + displayName(name));
                if(sequence < 0)
                {
                    throw new IOException("SQLite sequence for " + displayName(name) + " is negative");
                }
                if(sequences.putIfAbsent(name, sequence) != null)
                {
                    throw new IOException("SQLite has duplicate sequence state for " + displayName(name));
                }
            }
        }
        return Map.copyOf(sequences);
    }

    private static void restorePreservedSequences(Connection connection, Map<String,Long> sequences)
        throws SQLException
    {
        try(PreparedStatement delete = connection.prepareStatement("DELETE FROM sqlite_sequence WHERE name=?");
            PreparedStatement insert = connection.prepareStatement("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)"))
        {
            for(String table: PRESERVED_AUTOINCREMENT_TABLES)
            {
                long maximumId;
                try(Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT coalesce(max(id), 0) FROM " + identifier(table)))
                {
                    if(!rows.next())
                    {
                        throw new SQLException("Unable to inspect rebuilt sequence for " + table);
                    }
                    maximumId = rows.getLong(1);
                }
                long sequence = Math.max(maximumId, sequences.getOrDefault(table, 0L));
                delete.setString(1, table);
                delete.executeUpdate();
                if(sequence > 0)
                {
                    insert.setString(1, table);
                    insert.setLong(2, sequence);
                    insert.executeUpdate();
                }
            }
        }
    }

    /** Copies rows whose meaning is unchanged. Transformed channel, provider, and route rows are populated
     * separately from the preflight snapshot. */
    private static void copyUnchangedApplicationRows(Connection connection) throws SQLException
    {
        copyPreservedMetadata(connection);
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms)
                SELECT key, settings_json, updated_at_ms FROM format14_application_settings
                """);
            statement.executeUpdate("""
                INSERT INTO application_icons (key, icons_json, updated_at_ms)
                SELECT key, icons_json, updated_at_ms FROM format14_application_icons
                """);
            //Fresh creation seeds a Default scan list. The exact preserved format-14 rows replace that seed.
            statement.executeUpdate("DELETE FROM scan_list");
            statement.executeUpdate("""
                INSERT INTO alias_list (id, name, family, unmatched_talkgroup_record_enabled)
                SELECT id, name, family, unmatched_talkgroup_record_enabled FROM format14_alias_list
                """);
            statement.executeUpdate("""
                INSERT INTO scan_list (id, sort_order, name, description, published, is_default)
                SELECT id, sort_order, name, description, published, is_default FROM format14_scan_list
                """);
            statement.executeUpdate("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, color, icon_name,
                    stream_as_talkgroup, record_enabled, matcher_type, protocol, value, min_value,
                    max_value, text_value, numeric_value, tone_sequence
                )
                SELECT id, alias_list_id, name, description, group_name, color, icon_name,
                       stream_as_talkgroup, record_enabled, matcher_type, protocol, value, min_value,
                       max_value, text_value, numeric_value, tone_sequence
                FROM format14_alias
                """);
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership (alias_id, scan_list_id)
                SELECT alias_id, scan_list_id FROM format14_alias_scan_list_membership
                """);
            statement.executeUpdate("""
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership (alias_list_id, scan_list_id)
                SELECT alias_list_id, scan_list_id
                FROM format14_alias_list_unmatched_talkgroup_scan_list_membership
                """);
            statement.executeUpdate("""
                INSERT INTO web_user (
                    id, username, tier, primary_admin, credential_version, password_algorithm,
                    password_iterations, password_derived_key_bits, password_salt, password_hash,
                    password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                    created_at_ms, updated_at_ms
                )
                SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                       password_iterations, password_derived_key_bits, password_salt, password_hash,
                       password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                       created_at_ms, updated_at_ms
                FROM format14_web_user
                """);
        }
    }

    private static void insertPolicies(Connection connection, PolicyInput policy) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO web_access_policy (capability_id, required_tier, updated_at_ms)
            VALUES (?, ?, ?)
            """))
        {
            for(PolicyRow row: policy.rows())
            {
                statement.setString(1, row.capabilityId());
                statement.setString(2, row.tier());
                statement.setLong(3, row.updatedAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void applyPortablePreferencesUpdate(Connection connection, PortablePreferencesUpdate update)
        throws SQLException
    {
        if(!update.transformed())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE application_settings
            SET settings_json=?, updated_at_ms=?
            WHERE key=?
            """))
        {
            statement.setString(1, update.payload());
            statement.setLong(2, update.updatedAtMs());
            statement.setString(3, PORTABLE_PREFERENCES_KEY);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Portable preferences changed after format-15 preflight");
            }
        }
    }

    private static void copyPreservedMetadata(Connection connection) throws SQLException
    {
        String placeholders = String.join(",", java.util.Collections.nCopies(REPLACED_METADATA_KEYS.size(), "?"));
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            SELECT key, value, updated_at_ms
            FROM format14_database_metadata
            WHERE key NOT IN (%s)
            """.formatted(placeholders)))
        {
            for(int x = 0; x < REPLACED_METADATA_KEYS.size(); x++)
            {
                statement.setString(x + 1, REPLACED_METADATA_KEYS.get(x));
            }
            statement.executeUpdate();
        }
    }

    private static void insertChannels(Connection connection, List<ChannelRow> channels) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel (
                id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, auto_start_order, decoder_type, address_domain_code,
                primary_frequency_hz, config_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """))
        {
            for(ChannelRow channel: channels)
            {
                statement.setLong(1, channel.id());
                statement.setString(2, channel.configurationId());
                statement.setString(3, channel.channelKind());
                statement.setInt(4, channel.sortOrder());
                statement.setString(5, channel.systemName());
                statement.setString(6, channel.siteName());
                statement.setString(7, channel.name());
                setLong(statement, 8, channel.aliasListId());
                statement.setString(9, channel.radioResolveId());
                statement.setInt(10, channel.autoStart() ? 1 : 0);
                setInteger(statement, 11, channel.autoStartOrder());
                statement.setString(12, channel.decoderType());
                statement.setInt(13, channel.addressDomainCode());
                setLong(statement, 14, channel.primaryFrequencyHz());
                statement.setString(15, channel.payload());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertBroadcasts(Connection connection, List<BroadcastRow> broadcasts) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_broadcast_stream (id, configuration_id, sort_order, config_json)
            VALUES (?, ?, ?, ?)
            """))
        {
            for(BroadcastRow broadcast: broadcasts)
            {
                statement.setLong(1, broadcast.id());
                statement.setString(2, broadcast.configurationId());
                statement.setInt(3, broadcast.sortOrder());
                statement.setString(4, broadcast.payload());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertAliasRoutes(Connection connection, List<AliasRoute> routes) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_broadcast_channel (id, alias_id, broadcast_configuration_id) VALUES (?, ?, ?)
            """))
        {
            for(AliasRoute route: routes)
            {
                statement.setLong(1, route.id());
                statement.setLong(2, route.aliasId());
                statement.setString(3, route.broadcastConfigurationId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertUnmatchedRoutes(Connection connection, List<UnmatchedRoute> routes)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO alias_list_unmatched_talkgroup_stream (
                id, alias_list_id, broadcast_configuration_id
            ) VALUES (?, ?, ?)
            """))
        {
            for(UnmatchedRoute route: routes)
            {
                statement.setLong(1, route.id());
                statement.setLong(2, route.aliasListId());
                statement.setString(3, route.broadcastConfigurationId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void migratePreferences(Connection connection, List<PreferenceRow> preferences)
        throws SQLException
    {
        long now = Math.max(1, System.currentTimeMillis());
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE web_user
            SET preferences_json=?, preferences_revision=?, updated_at_ms=?
            WHERE id=?
            """))
        {
            for(PreferenceRow preference: preferences)
            {
                statement.setString(1, preference.payload());
                statement.setLong(2, preference.revision());
                statement.setLong(3, Math.max(now, preference.updatedAtMs()));
                statement.setLong(4, preference.id());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Web user changed after format-15 preflight: " + preference.id());
                }
            }
        }
    }

    private static void seedMetricBoundaries(Connection connection) throws SQLException
    {
        long now = Math.max(1, System.currentTimeMillis());
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            for(String key: List.of(CONVENTIONAL_CALL_BOUNDARY, TRUNKED_CALL_BOUNDARY, RADIO_SYSTEM_BOUNDARY))
            {
                statement.setString(1, key);
                statement.setString(2, Long.toString(now));
                statement.setLong(3, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<String> replacedMetadataKeys()
    {
        List<String> keys = new ArrayList<>(SUBSYSTEM_VERSION_KEYS.size() + 4);
        keys.addAll(SUBSYSTEM_VERSION_KEYS);
        keys.add(OLD_IDENTITY_BOUNDARY);
        keys.add(CONVENTIONAL_CALL_BOUNDARY);
        keys.add(TRUNKED_CALL_BOUNDARY);
        keys.add(RADIO_SYSTEM_BOUNDARY);
        return List.copyOf(keys);
    }

    private static long countRows(Connection connection, Collection<String> tables) throws SQLException
    {
        long total = 0;
        try(Statement statement = connection.createStatement())
        {
            for(String table: tables)
            {
                try(ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + identifier(table)))
                {
                    if(!rows.next())
                    {
                        throw new SQLException("Unable to count " + table);
                    }
                    total = Math.addExact(total, rows.getLong(1));
                }
            }
        }
        return total;
    }

    private static List<String> activityTables()
    {
        List<String> tables = new ArrayList<>(CALL_HISTORY_TABLES.size() + SITE_TABLES.size() +
            QUALITY_TABLES.size() + IDENTITY_TABLES.size());
        tables.addAll(CALL_HISTORY_TABLES);
        tables.addAll(SITE_TABLES);
        tables.addAll(QUALITY_TABLES);
        tables.addAll(IDENTITY_TABLES);
        if(new HashSet<>(tables).size() != tables.size())
        {
            throw new ExceptionInInitializerError("Duplicate format-14 activity table classification");
        }
        return List.copyOf(tables);
    }

    private static ObjectNode parseObject(String json, String label) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_CONFIGURATION_JSON_BYTES)
        {
            throw new IOException(label + " JSON is missing or exceeds the storage bound");
        }
        JsonNode parsed;
        try
        {
            parsed = MAPPER.readTree(json);
        }
        catch(IOException exception)
        {
            //Broadcast documents may contain credentials. Never include parser source excerpts in migration errors.
            throw new IOException(label + " JSON is malformed");
        }
        if(!(parsed instanceof ObjectNode object))
        {
            throw new IOException(label + " JSON is not an object");
        }
        return object;
    }

    /**
     * Makes the last implicit DMR/NXDN mode rules explicit at the format boundary. Format 14 could retain JSON from
     * before the mode field existed, so this frozen conversion must not depend on current decoder defaults.
     */
    private static void normalizeLegacyChannelMode(ObjectNode payload, String decoderType, String label)
        throws IOException
    {
        if(!"DMR".equals(decoderType) && !"NXDN".equals(decoderType))
        {
            return;
        }

        if(!(payload.get("decodeConfiguration") instanceof ObjectNode decoder))
        {
            throw new IOException(label + " has no decoder configuration object");
        }

        JsonNode storedMode = decoder.get("channelMode");
        if(storedMode == null || storedMode.isNull())
        {
            String mode = "NXDN".equals(decoderType) || hasUsableLegacyDmrChannelMap(decoder) ?
                "TRUNKED" : "CONVENTIONAL";
            decoder.put("channelMode", mode);
            return;
        }

        if(!storedMode.isTextual() || !Set.of("CONVENTIONAL", "TRUNKED").contains(storedMode.textValue()))
        {
            throw new IOException(label + " has an invalid explicit channel mode");
        }
    }

    /** Exact legacy DMR rule: any positive channel number paired with a positive downlink frequency meant trunked. */
    private static boolean hasUsableLegacyDmrChannelMap(ObjectNode decoder)
    {
        JsonNode map = decoder.get("timeslotMap");
        if(map == null || !map.isArray())
        {
            return false;
        }

        for(JsonNode entry: map)
        {
            if(entry != null && entry.isObject() && positiveFormat14WholeNumber(entry.get("number"), true) &&
                positiveFormat14WholeNumber(entry.get("downlinkFrequency"), false))
            {
                return true;
            }
        }

        return false;
    }

    /** Matches Jackson's format-14 integer/string scalar acceptance without accepting fractional values. */
    private static boolean positiveFormat14WholeNumber(JsonNode value, boolean requireIntRange)
    {
        if(value == null || value.isNull())
        {
            return false;
        }

        try
        {
            long parsed;
            if(value.isIntegralNumber() && value.canConvertToLong())
            {
                parsed = value.longValue();
            }
            else if(value.isTextual())
            {
                parsed = Long.parseLong(value.textValue().strip());
            }
            else
            {
                return false;
            }
            return parsed > 0 && (!requireIntRange || parsed <= Integer.MAX_VALUE);
        }
        catch(NumberFormatException exception)
        {
            return false;
        }
    }

    private static Channel decodeChannel(ObjectNode payload, String label) throws IOException
    {
        try
        {
            return MAPPER.treeToValue(payload, Channel.class);
        }
        catch(IOException | RuntimeException exception)
        {
            throw new IOException(label + " is not a supported saved channel document");
        }
    }

    private static BroadcastConfiguration decodeBroadcast(ObjectNode payload, String label) throws IOException
    {
        try
        {
            return MAPPER.treeToValue(payload, BroadcastConfiguration.class);
        }
        catch(IOException | RuntimeException exception)
        {
            //Do not attach the source exception: provider documents can hold API keys and passwords.
            throw new IOException(label + " is not a supported broadcast provider document");
        }
    }

    /** Normalizes the one legacy RadioResolve discriminator admitted by the format-14 schema. */
    private static void normalizeLegacyBroadcastType(ObjectNode payload)
    {
        JsonNode type = payload.get("type");
        if(type != null && type.isTextual() && "RADIORESOLVE".equals(type.textValue()))
        {
            payload.put("type", "RadioResolveConfiguration");
        }
    }

    private static void requireJsonText(ObjectNode payload, String field, String scalar, String label,
                                        boolean caseInsensitive, boolean required) throws IOException
    {
        JsonNode node = payload.get(field);
        if(node == null)
        {
            if(required)
            {
                throw new IOException(label + " is missing JSON field " + field);
            }
            return;
        }
        String value;
        if(node.isNull())
        {
            value = null;
        }
        else if(node.isTextual())
        {
            value = caseInsensitive ? normalizeBlank(node.textValue()) : node.textValue();
        }
        else
        {
            throw new IOException(label + " JSON field " + field + " is not text or null");
        }
        boolean matches = caseInsensitive ? equalsIgnoreCase(value, scalar) : Objects.equals(value, scalar);
        if(!matches)
        {
            throw new IOException(label + " JSON field " + field + " does not match its scalar value");
        }
    }

    private static void requireRadioResolveJson(ObjectNode payload, String scalar, String label) throws IOException
    {
        List<String> fields = List.of("radresGuid", "radioResolveId", "radres_guid");
        List<String> present = fields.stream().filter(payload::has).toList();
        if(present.size() > 1)
        {
            throw new IOException(label + " mixes RadioResolve JSON field names");
        }
        if(present.isEmpty())
        {
            if(scalar != null)
            {
                throw new IOException(label + " is missing its RadioResolve JSON value");
            }
            return;
        }
        JsonNode node = payload.get(present.getFirst());
        if(!node.isNull() && !node.isTextual())
        {
            throw new IOException(label + " RadioResolve JSON value is not text or null");
        }
        String jsonValue = node.isNull() ? null : normalizeBlank(node.textValue());
        if(!Objects.equals(jsonValue, scalar))
        {
            throw new IOException(label + " RadioResolve JSON value does not match its scalar value");
        }
    }

    private static void requireJsonBooleanProjection(ObjectNode payload, List<String> fields, boolean scalar,
                                                      String label, String description) throws IOException
    {
        for(String field: fields)
        {
            JsonNode node = payload.get(field);
            if(node != null && (!node.isBoolean() || node.booleanValue() != scalar))
            {
                throw new IOException(label + " JSON field " + field + " does not match its " + description +
                    " row value");
            }
        }
    }

    private static void requireJsonIntegerProjection(ObjectNode payload, List<String> fields, Integer scalar,
                                                      String label, String description) throws IOException
    {
        for(String field: fields)
        {
            JsonNode node = payload.get(field);
            if(node != null)
            {
                Integer value = node.isNull() ? null :
                    node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
                if((!node.isNull() && value == null) || !Objects.equals(value, scalar))
                {
                    throw new IOException(label + " JSON field " + field + " does not match its " + description +
                        " row value");
                }
            }
        }
    }

    private static void requireJsonLongProjection(ObjectNode payload, String field, Long scalar,
                                                   String label, String description) throws IOException
    {
        JsonNode node = payload.get(field);
        if(node != null)
        {
            Long value = node.isNull() ? null :
                node.isIntegralNumber() && node.canConvertToLong() ? node.longValue() : null;
            if((!node.isNull() && value == null) || !Objects.equals(value, scalar))
            {
                throw new IOException(label + " JSON field " + field + " does not match its " + description +
                    " row relationship");
            }
        }
    }

    private static boolean equalsIgnoreCase(String first, String second)
    {
        return first == null ? second == null : second != null && first.equalsIgnoreCase(second);
    }

    private static String normalizeBlank(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private static String savedChannelLabel(long rowId, String system, String site, String name)
    {
        List<String> parts = java.util.stream.Stream.of(system, site, name)
            .map(Format14To15DatabaseMigration::normalizeBlank).filter(Objects::nonNull).toList();
        String description = parts.isEmpty() ? "Saved channel" : "Saved channel " + displayName(String.join(" / ", parts));
        return description + " (database row " + rowId + ")";
    }

    private static String broadcastProviderLabel(long rowId, String name)
    {
        return (normalizeBlank(name) == null ? "Broadcast provider" :
            "Broadcast provider " + displayName(name)) + " (database row " + rowId + ")";
    }

    private static String displayName(String value)
    {
        String normalized = value == null ? "unnamed" : value.replaceAll("\\p{Cntrl}", " ").strip();
        if(normalized.length() > 96)
        {
            normalized = normalized.substring(0, 93) + "...";
        }
        return "\"" + normalized + "\"";
    }

    private static String tryCanonicalUuid(JsonNode value)
    {
        if(value == null || !value.isTextual())
        {
            return null;
        }
        try
        {
            String parsed = UUID.fromString(value.textValue()).toString();
            return parsed.equals(value.textValue()) ? parsed : null;
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return null;
        }
    }

    private static String deterministicBroadcastId(long rowId, Set<String> unavailable) throws IOException
    {
        for(int attempt = 0; attempt < Integer.MAX_VALUE; attempt++)
        {
            String seed = "sdrtrunk-vce:format-14:broadcast-provider:" + rowId +
                (attempt == 0 ? "" : ":" + attempt);
            String candidate = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
            if(!unavailable.contains(candidate))
            {
                return candidate;
            }
        }
        throw new IOException("Unable to allocate a unique stable ID for broadcast provider database row " + rowId);
    }

    private static String canonicalUuid(String value, String label) throws IOException
    {
        try
        {
            String canonical = UUID.fromString(value).toString();
            if(!canonical.equals(value))
            {
                throw new IllegalArgumentException("not canonical");
            }
            return canonical;
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw new IOException(label + " must be a canonical lowercase UUID", exception);
        }
    }

    private static String identifier(String value) throws SQLException
    {
        if(value == null || !value.matches("[a-z][a-z0-9_]*"))
        {
            throw new SQLException("Unsafe SQLite identifier");
        }
        return value;
    }

    private static long positiveId(ResultSet rows, String column, String label) throws SQLException, IOException
    {
        long value = longInteger(rows, column, label);
        if(value <= 0)
        {
            throw new IOException(label + " has a nonpositive " + column);
        }
        return value;
    }

    private static int integer(ResultSet rows, String column, String label) throws SQLException, IOException
    {
        long value = longInteger(rows, column, label);
        if(value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
        {
            throw new IOException(label + " " + column + " is outside the integer range");
        }
        return (int)value;
    }

    private static Integer nullableInteger(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        Long value = nullableLong(rows, column, label);
        if(value == null)
        {
            return null;
        }
        if(value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
        {
            throw new IOException(label + " " + column + " is outside the integer range");
        }
        return value.intValue();
    }

    private static long longInteger(ResultSet rows, String column, String label) throws SQLException, IOException
    {
        Long value = nullableLong(rows, column, label);
        if(value == null)
        {
            throw new IOException(label + " " + column + " cannot be null");
        }
        return value;
    }

    private static Long nullableLong(ResultSet rows, String column, String label) throws SQLException, IOException
    {
        Object value = rows.getObject(column);
        if(value == null)
        {
            return null;
        }
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
        {
            return ((Number)value).longValue();
        }
        throw new IOException(label + " " + column + " is not stored as an integer");
    }

    private static boolean booleanFlag(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        long value = longInteger(rows, column, label);
        if(value == 0)
        {
            return false;
        }
        if(value == 1)
        {
            return true;
        }
        throw new IOException(label + " " + column + " is not a boolean flag");
    }

    private static String text(ResultSet rows, String column) throws SQLException, IOException
    {
        String value = nullableText(rows, column);
        if(value == null)
        {
            throw new IOException(column + " cannot be null");
        }
        return value;
    }

    private static String nullableText(ResultSet rows, String column) throws SQLException, IOException
    {
        Object value = rows.getObject(column);
        if(value == null || value instanceof String)
        {
            return (String)value;
        }
        throw new IOException(column + " is not stored as text");
    }

    private static String requiredStoredText(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        String value = optionalStoredText(rows, column, label);
        if(value == null)
        {
            throw new IOException(label + " cannot be null");
        }
        return value;
    }

    private static String optionalStoredText(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        Object value = rows.getObject(column);
        if(value == null || value instanceof String)
        {
            return (String)value;
        }
        throw new IOException(label + " is not stored as text");
    }

    private static byte[] requiredStoredBlob(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        Object value = rows.getObject(column);
        if(value instanceof byte[] bytes)
        {
            return bytes;
        }
        throw new IOException(label + " is not stored as binary data");
    }

    private static long requirePositiveInteger(ResultSet rows, String column, String label)
        throws SQLException, IOException
    {
        long value = longInteger(rows, column, label);
        if(value <= 0)
        {
            throw new IOException(label + " must be a positive integer");
        }
        return value;
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value == null)
        {
            statement.setNull(index, Types.INTEGER);
        }
        else
        {
            statement.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if(value == null)
        {
            statement.setNull(index, Types.INTEGER);
        }
        else
        {
            statement.setLong(index, value);
        }
    }

    private record ChannelRow(long id, String configurationId, String channelKind, int sortOrder,
                              String systemName, String siteName, String name, Long aliasListId,
                              String radioResolveId, boolean autoStart, Integer autoStartOrder,
                              String decoderType, int addressDomainCode, Long primaryFrequencyHz, String payload)
    {
    }

    private record BroadcastRow(long id, String configurationId, int sortOrder, String name, String payload)
    {
    }

    private record BroadcastCandidate(long id, String candidateConfigurationId, int sortOrder, String name,
                                      String payload)
    {
    }

    private record AliasRoute(long id, long aliasId, String broadcastConfigurationId)
    {
    }

    private record UnmatchedRoute(long id, long aliasListId, String broadcastConfigurationId)
    {
    }

    private record PreferenceRow(long id, String payload, long revision, long updatedAtMs)
    {
    }

    private record PolicyRow(String capabilityId, String tier, long updatedAtMs)
    {
    }

    private record PolicyInput(List<PolicyRow> rows, int transformedRows)
    {
    }

    private record PortablePreferencesUpdate(String payload, long updatedAtMs, boolean transformed)
    {
    }

    private record AliasAdminIndex(Set<Long> aliasListIds, Set<Long> aliasIds)
    {
    }

    private record MigrationInput(List<ChannelRow> channels, List<BroadcastRow> broadcasts,
                                  List<AliasRoute> aliasRoutes, List<UnmatchedRoute> unmatchedRoutes,
                                  List<PreferenceRow> preferences, PolicyInput policy,
                                  PortablePreferencesUpdate portablePreferences, Map<String,Long> sequences,
                                  long callHistoryRows,
                                  long siteRows, long qualityRows, long identityRows,
                                  long retiredNamedChannelMapRows, long preservedRows)
    {
    }
}

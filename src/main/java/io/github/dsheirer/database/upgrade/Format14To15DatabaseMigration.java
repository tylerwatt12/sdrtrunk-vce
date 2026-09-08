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
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasMatchRegistry;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.configuration.ConfigurationChannelProjection;
import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.icon.IconSet;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.UUID;

/**
 * Replaces the overloaded format-14 channel/site identity model with the stable format-15 radio-system and saved
 * channel model. Independently usable administrator-owned rows are preserved while malformed rows and their
 * dependent relationships are isolated. Receiver observations are deliberately reset because their former GUID,
 * context, and scope keys cannot be converted without guessing.
 */
final class Format14To15DatabaseMigration implements DatabaseMigrationStep
{
    private static final int MAXIMUM_CONFIGURATION_JSON_BYTES = 4_194_304;
    private static final int MAXIMUM_RECOVERED_BROADCAST_NAME_CODE_POINTS = 256;
    private static final int MAXIMUM_ALIAS_TEXT_BYTES = 4_194_304;
    private static final int MAXIMUM_PORTABLE_PREFERENCES_BYTES = 4_194_304;
    private static final int MAXIMUM_WEB_PREFERENCES_CHARACTERS = 131_072;
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String DEFAULT_ICONS_KEY = "default";
    private static final String ICONS_INITIALIZED_KEY = "icon_config_initialized";
    private static final String INITIAL_ADMIN_SETUP_KEY = "initial_admin_setup";
    private static final String NOW_PLAYING_NODE = "user/io/github/dsheirer/preference/nowplaying";
    private static final String SITE_SETTINGS_REVISION_KEY = "site.settings.revision";
    private static final String RECEIVER_SETTINGS_REVISION_KEY = "receiver.settings.revision";
    private static final Set<String> RECEIVER_PREFERENCE_KEYS = Set.of(SITE_SETTINGS_REVISION_KEY,
        RECEIVER_SETTINGS_REVISION_KEY, "retain.idle.call.details", "clear.voice.decode.quality.on.call.end",
        "traffic.grant.age.out.milliseconds");
    private static final String SITE_ACCESS_POLICY_ID = "site-access";
    private static final String WEB_ACCESS_POLICY_ID = "web-access";
    private static final String SYSTEMS_POLICY_ID = "systems";
    private static final String CONVENTIONAL_POLICY_ID = "conventional";
    private static final Set<String> LEGACY_POLICY_IDS = Set.of(SITE_ACCESS_POLICY_ID, SYSTEMS_POLICY_ID,
        CONVENTIONAL_POLICY_ID);
    private static final String RADIO_POLICY_ID = "radio";
    private static final String OLD_IDENTITY_BOUNDARY =
        DatabaseFormatCatalog.RETIRED_TRUNKED_IDENTITY_BOUNDARY_KEY;
    private static final String RADIO_SYSTEM_BOUNDARY = "radio_system_metrics_started_at_ms";
    private static final String CONVENTIONAL_CALL_BOUNDARY = "conventional_call_output_metrics_started_at_ms";
    private static final String TRUNKED_CALL_BOUNDARY = "trunked_logical_call_metrics_started_at_ms";
    private static final List<String> REPLACED_METRIC_BOUNDARIES = List.of(OLD_IDENTITY_BOUNDARY,
        CONVENTIONAL_CALL_BOUNDARY, TRUNKED_CALL_BOUNDARY, RADIO_SYSTEM_BOUNDARY);
    private static final String RETIRED_NAMED_CHANNEL_MAP_TABLE = "configuration_channel_map";
    /**
     * Legacy JSON copies of fields whose format-14 load contract took from the relational channel row.  They must be
     * removed before decoding so an absent, stale, or malformed duplicate cannot override the administrator-owned row
     * value while crossing the one-owner format boundary. The configuration UUID is intentionally excluded so it can
     * be recovered or repaired separately before decoding and then removed from the target JSON.
     */
    private static final List<String> LEGACY_CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "system", "site", "name", "aliasListName", "aliasListId", "radioResolveId", "radresGuid",
        "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");
    private static final List<String> PRESERVED_AUTOINCREMENT_TABLES = List.of(
        "alias_list", "alias", "scan_list", "alias_broadcast_channel",
        "alias_list_unmatched_talkgroup_stream", "configuration_channel",
        "configuration_broadcast_stream", "web_user");
    private static final List<String> SUBSYSTEM_VERSION_KEYS =
        DatabaseFormatCatalog.RETIRED_SUBSYSTEM_VERSION_KEYS;
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
    private static final ObjectMapper LENIENT_PREFERENCE_MAPPER = new ObjectMapper()
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
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "scan-list memberships recovered into Default", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Keep routing intent for surviving Aliases and Alias Lists when every saved scan list is unusable"),
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
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "activity metric boundaries", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Start fresh conventional-call, trunked-call, and radio-system measurement windows"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Clear or remove saved relationships whose referenced Alias, Alias List, scan list, or stream provider no longer exists"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable application settings, icons, and metadata", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip only malformed administrative rows in this component"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable Alias, Alias List, and scan-list rows", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip malformed Alias data while preserving independent usable lists, Aliases, and memberships"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable saved channel rows", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip only channel documents that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable broadcast provider rows", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip only streamer configuration documents that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable web accounts and access policies", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip only unusable accounts and policy overrides while preserving independent credentials"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable application settings and metadata", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Default only unusable optional application values"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable Alias List and scan-list values", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Repair names, optional policies, and the required Default scan-list selection"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Keep each usable Alias and default only malformed display or action fields"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable saved channel values", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Repair only replaceable channel identities or projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable broadcast provider values", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Repair only replaceable provider identities or names"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Default unusable browser preferences or setup state without discarding usable credentials"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "SQLite identity high-water marks", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Normalize allocator state to retained safe row identities"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "web authentication", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Require administrator password setup only when the saved primary credential cannot be used safely"),
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

        migrate(connection, input);
    }

    @Override
    public List<DatabaseMigrationEffect> migrateAndReport(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        migrate(connection, input);
        return effects(input);
    }

    private static void migrate(Connection connection, MigrationInput input) throws SQLException
    {

        dropActivitySchema(connection);
        dropRetiredNamedChannelMaps(connection);
        rebuildApplicationSchema(connection, input);

        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        seedMetricBoundaries(connection, input.newMetricBoundary());
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        Objects.requireNonNull(connection, "Connection cannot be null");
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);
        if(detected.version() != 14)
        {
            throw new SQLException("Migration step format-14-to-15 requires exact source format 14; found " +
                detected.version() + " [" + detected.id() + "]");
        }

        try
        {
            CoreInspection core = inspectCoreRows(connection);
            AliasAdminIndex aliases = inspectAliasesAndScanLists(connection);
            RowInspection<ChannelRow> channels = inspectChannels(connection, aliases.aliasListsByExactName(),
                aliases.aliasListsByName(), aliases.aliasListFamilies());
            RowInspection<BroadcastRow> broadcastInspection = inspectBroadcasts(connection);
            List<BroadcastRow> broadcasts = broadcastInspection.rows();
            Map<String,List<BroadcastRow>> broadcastsByName = broadcastsByName(broadcasts);
            RelationshipInspection<AliasRoute> aliasRoutes = inspectAliasRoutes(connection, broadcastsByName,
                aliases.aliasIds());
            RelationshipInspection<UnmatchedRoute> unmatchedRoutes = inspectUnmatchedRoutes(connection,
                broadcastsByName,
                aliases.aliasListIds());
            UserInspection users = inspectUsers(connection);
            PolicyInput policy = inspectPolicy(connection);
            if(users.authenticationReset())
            {
                users = users.withAdditionalAuthenticationResetRows(policy.sourceRows());
                policy = new PolicyInput(List.of(), 0, 0, policy.sourceRows(), 0);
            }
            SequenceInspection sequences = inspectSequences(connection, retainedSequenceMaximums(aliases,
                channels.rows(), broadcasts, aliasRoutes.rows(), unmatchedRoutes.rows(), users.rows()));
            long callHistoryRows = countRows(connection, CALL_HISTORY_TABLES);
            long siteRows = countRows(connection, SITE_TABLES);
            long qualityRows = countRows(connection, QUALITY_TABLES);
            long identityRows = countRows(connection, IDENTITY_TABLES);
            long metricBoundaryRows = countMetadataRows(connection, REPLACED_METRIC_BOUNDARIES);
            long newMetricBoundary = deriveMetricBoundary(connection);
            long retiredNamedChannelMapRows = countRows(connection, List.of(RETIRED_NAMED_CHANNEL_MAP_TABLE));
            long preservedRows = sumCounts(core.preservedRows(), aliases.preservedRows(), channels.rows().size(),
                broadcasts.size(), users.rows().size(), policy.rows().size(), aliasRoutes.rows().size(),
                unmatchedRoutes.rows().size());
            long orphanedRelationships = Math.addExact(channels.relationshipsDropped(), aliases.orphanedRows());
            orphanedRelationships = Math.addExact(orphanedRelationships, aliasRoutes.orphanedRows());
            orphanedRelationships = Math.addExact(orphanedRelationships, unmatchedRoutes.orphanedRows());
            return new MigrationInput(core, aliases, channels.rows(), broadcasts, aliasRoutes.rows(),
                unmatchedRoutes.rows(), users, policy, sequences.values(), callHistoryRows, siteRows, qualityRows, identityRows,
                metricBoundaryRows, newMetricBoundary, retiredNamedChannelMapRows, orphanedRelationships,
                channels.droppedRows(), channels.defaultedRows(), broadcastInspection.droppedRows(),
                broadcastInspection.defaultedRows(), sequences.defaultedRows(), preservedRows,
                countMetadataRows(connection, SUBSYSTEM_VERSION_KEYS));
        }
        catch(IllegalArgumentException | ArithmeticException exception)
        {
            throw new SQLException("Format-14 configuration inventory could not be counted: " +
                exception.getMessage(), exception);
        }
    }

    private static List<DatabaseMigrationEffect> effects(MigrationInput input)
    {
        long broadcastRelationships = Math.addExact(input.broadcasts().size(),
            Math.addExact(input.aliasRoutes().size(), input.unmatchedRoutes().size()));
        boolean authenticationMarkerDefaulted = input.users().authenticationReset() && input.core().metadata().stream()
            .noneMatch(row -> INITIAL_ADMIN_SETUP_KEY.equals(row.key()) && "required".equals(row.value()));
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
                "browser playback preferences", input.users().transformedPreferences(),
                "Rename conversation playback fields for stable targets and increment each user preference revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "web access policy names", input.policy().transformedRows(),
                "Preserve access levels while combining Systems and Conventional under Radio and renaming whole-site access"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "receiver-settings revision", input.core().portablePreferencesTransformed(),
                "Rename the shared receiver-settings revision without changing its value"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM,
                "scan-list memberships recovered into Default",
                input.aliases().recoveredDefaultMemberships().sourceRows(),
                "Keep routing intent for surviving Aliases and Alias Lists when a referenced scan list is unusable; " +
                    "coalesce duplicate owner routes onto the selected Default"),
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
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "activity metric boundaries", input.metricBoundaryRows(),
                "Start fresh conventional-call, trunked-call, and radio-system measurement windows"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "orphaned legacy relationships", input.orphanedRelationshipRows(),
                "Clear or remove saved relationships whose target no longer exists, is ambiguous, or duplicates a " +
                    "relationship already retained for the same owner"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable application settings, icons, and metadata", input.core().droppedRows(),
                "Skip only malformed administrative rows in this component"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable Alias, Alias List, and scan-list rows", input.aliases().droppedRows(),
                "Skip malformed Alias data while preserving independent usable lists, Aliases, and memberships"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable saved channel rows", input.droppedChannelRows(),
                "Skip only channel documents that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable broadcast provider rows", input.droppedBroadcastRows(),
                "Skip only streamer configuration documents that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "unusable web accounts and access policies",
                Math.addExact(input.users().droppedRows(), input.policy().droppedRows()),
                "Skip only unusable accounts and policy overrides while preserving independent credentials"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable application settings and metadata", input.core().defaultedRows(),
                "Default only unusable optional application values"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable Alias List and scan-list values", input.aliases().defaultedRows(),
                "Repair names, optional policies, and the required Default scan-list selection"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "Aliases with defaulted optional fields", input.aliases().defaultedAliasRows(),
                "Keep each usable Alias and default only malformed description, group, color, icon, stream-as, " +
                    "or recording fields"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable saved channel values", input.defaultedChannelRows(),
                "Repair only replaceable channel identities or projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable broadcast provider values", input.defaultedBroadcastRows(),
                "Repair only replaceable provider identities or names"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web account values",
                sumCounts(input.users().defaultedRows(), input.policy().defaultedRows(),
                    authenticationMarkerDefaulted ? 1 : 0),
                "Default unusable browser preferences, policy timestamps, or setup state without discarding " +
                    "usable credentials and restrictions"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "SQLite identity high-water marks", input.defaultedSequenceRows(),
                "Normalize allocator state to retained safe row identities"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "web authentication", input.users().authenticationResetRows(),
                "Require administrator password setup only when the saved primary credential cannot be used safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "redundant subsystem version markers", input.redundantMetadataRows(),
                "Keep the one authoritative whole-database format version"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "retired named Channel Maps", input.retiredNamedChannelMapRows(),
                "Remove unused legacy named Channel Maps; decoder channel maps saved inside channels are preserved"));
    }

    /** Builds a row-by-row copy plan. Optional corrupt documents are omitted instead of blocking unrelated data. */
    private static CoreInspection inspectCoreRows(Connection connection) throws SQLException
    {
        List<MetadataRow> metadata = new ArrayList<>();
        List<SettingRow> settings = new ArrayList<>();
        List<IconRow> icons = new ArrayList<>();
        long dropped = 0;
        long defaulted = 0;
        long portableTransformed = 0;
        long preserved = 0;
        boolean setupProgressPresent = false;
        boolean spectrumSnapPresent = false;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256
                        THEN key END AS key,
                   CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB)) <= 65536
                        THEN value END AS value,
                   updated_at_ms
            FROM database_metadata ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String key = safeText(rows, "key");
                if(key != null && REPLACED_METADATA_KEYS.contains(key))
                {
                    continue;
                }
                String value = safeText(rows, "value");
                Long storedUpdatedAt = safePositiveLong(rows, "updated_at_ms");
                if(key == null || key.isBlank() || value == null ||
                    (INITIAL_ADMIN_SETUP_KEY.equals(key) && !Set.of("complete", "required").contains(value)))
                {
                    dropped++;
                }
                else
                {
                    long updatedAt = storedUpdatedAt != null ? storedUpdatedAt : 1;
                    defaulted += storedUpdatedAt == null ? 1 : 0;
                    metadata.add(new MetadataRow(key, value, updatedAt));
                    preserved++;
                }
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256
                        THEN key END AS key,
                   CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= 4194304
                        THEN settings_json END AS settings_json,
                   updated_at_ms
            FROM application_settings ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String key = safeText(rows, "key");
                String json = safeText(rows, "settings_json");
                Long storedUpdatedAt = safePositiveLong(rows, "updated_at_ms");
                if(key == null || key.isBlank() || json == null)
                {
                    dropped++;
                    continue;
                }
                if(PORTABLE_PREFERENCES_KEY.equals(key) &&
                    json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PORTABLE_PREFERENCES_BYTES)
                {
                    dropped++;
                    continue;
                }
                try
                {
                    boolean rowDefaulted = storedUpdatedAt == null;
                    JsonNode parsed;
                    boolean canonicalizePortablePreferences = false;
                    try
                    {
                        parsed = MAPPER.readTree(json);
                    }
                    catch(IOException strictFailure)
                    {
                        if(!PORTABLE_PREFERENCES_KEY.equals(key))
                        {
                            throw strictFailure;
                        }
                        parsed = LENIENT_PREFERENCE_MAPPER.readTree(json);
                        canonicalizePortablePreferences = true;
                    }
                    if(parsed == null)
                    {
                        throw new IOException("empty JSON");
                    }
                    String payload = json;
                    if(PORTABLE_PREFERENCES_KEY.equals(key))
                    {
                        PortablePreferenceRepair repair = repairPortablePreferences(parsed, json,
                            canonicalizePortablePreferences);
                        if(repair.drop())
                        {
                            dropped++;
                            continue;
                        }
                        payload = repair.payload();
                        portableTransformed += repair.transformed() ? 1 : 0;
                        rowDefaulted |= repair.defaulted();
                    }
                    else if(SetupProgress.KEY.equals(key))
                    {
                        try
                        {
                            SetupProgress.decode(json);
                        }
                        catch(SQLException exception)
                        {
                            throw new IOException("invalid setup progress");
                        }
                        setupProgressPresent = true;
                    }
                    else if(SpectrumSnapSettings.KEY.equals(key))
                    {
                        try
                        {
                            SpectrumSnapSettings.decode(json);
                        }
                        catch(SQLException exception)
                        {
                            throw new IOException("invalid spectrum-snap setting");
                        }
                        spectrumSnapPresent = true;
                    }
                    settings.add(new SettingRow(key, payload, storedUpdatedAt != null ? storedUpdatedAt : 1));
                    defaulted += rowDefaulted ? 1 : 0;
                    preserved++;
                }
                catch(IOException | RuntimeException exception)
                {
                    dropped++;
                }
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256
                        THEN key END AS key,
                   CASE WHEN typeof(icons_json)='text'
                              AND length(CAST(icons_json AS BLOB)) <= 4194304
                        THEN icons_json END AS icons_json,
                   updated_at_ms
            FROM application_icons ORDER BY key
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String key = safeText(rows, "key");
                String json = safeText(rows, "icons_json");
                Long storedUpdatedAt = safePositiveLong(rows, "updated_at_ms");
                try
                {
                    JsonNode parsed = json != null ? MAPPER.readTree(json) : null;
                    if(key == null || key.isBlank() || !(parsed instanceof ObjectNode))
                    {
                        dropped++;
                    }
                    else
                    {
                        if(DEFAULT_ICONS_KEY.equals(key))
                        {
                            MAPPER.treeToValue(parsed, IconSet.class);
                        }
                        icons.add(new IconRow(key, json, storedUpdatedAt != null ? storedUpdatedAt : 1));
                        defaulted += storedUpdatedAt == null ? 1 : 0;
                        preserved++;
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    dropped++;
                }
            }
        }

        boolean defaultIconsPreserved = icons.stream().anyMatch(row -> DEFAULT_ICONS_KEY.equals(row.key()));
        if(!defaultIconsPreserved && metadata.removeIf(row -> ICONS_INITIALIZED_KEY.equals(row.key()) &&
            "true".equalsIgnoreCase(row.value())))
        {
            preserved = Math.subtractExact(preserved, 1);
            dropped++;
        }

        if(!setupProgressPresent)
        {
            settings.add(new SettingRow(SetupProgress.KEY, SetupProgress.replacementReview().encode(), 1));
            defaulted++;
        }
        if(!spectrumSnapPresent)
        {
            settings.add(new SettingRow(SpectrumSnapSettings.KEY, SpectrumSnapSettings.defaults().encode(), 1));
            defaulted++;
        }
        return new CoreInspection(List.copyOf(metadata), List.copyOf(settings), List.copyOf(icons), dropped,
            defaulted, portableTransformed, preserved);
    }

    private static PortablePreferenceRepair repairPortablePreferences(JsonNode parsed, String original,
                                                                       boolean canonicalize)
        throws IOException
    {
        if(!(parsed instanceof ObjectNode root))
        {
            return new PortablePreferenceRepair(null, false, false, true);
        }
        boolean defaulted = canonicalize;
        var nodes = root.fields();
        while(nodes.hasNext())
        {
            Map.Entry<String,JsonNode> node = nodes.next();
            if(!(node.getValue() instanceof ObjectNode preferences))
            {
                nodes.remove();
                defaulted = true;
                continue;
            }
            var values = preferences.fields();
            while(values.hasNext())
            {
                Map.Entry<String,JsonNode> value = values.next();
                if(!value.getValue().isTextual() ||
                    Format6To7DatabaseMigration.RETIRED_WEB_AUDIO_KEYS.contains(value.getKey()))
                {
                    values.remove();
                    defaulted = true;
                }
            }
        }

        JsonNode node = root.get(NOW_PLAYING_NODE);
        if(!(node instanceof ObjectNode nowPlaying))
        {
            String payload = defaulted ? MAPPER.writeValueAsString(root) : original;
            return new PortablePreferenceRepair(payload, false, defaulted, false);
        }

        JsonNode oldRevision = nowPlaying.remove(SITE_SETTINGS_REVISION_KEY);
        JsonNode newRevision = nowPlaying.get(RECEIVER_SETTINGS_REVISION_KEY);
        boolean validNew = positiveCanonicalRevision(newRevision);
        boolean validOld = positiveCanonicalRevision(oldRevision);
        boolean transformed = false;
        if(validNew)
        {
            transformed = oldRevision != null;
        }
        else if(validOld)
        {
            nowPlaying.set(RECEIVER_SETTINGS_REVISION_KEY, oldRevision);
            transformed = true;
            defaulted |= newRevision != null;
        }
        else
        {
            if(newRevision != null)
            {
                nowPlaying.remove(RECEIVER_SETTINGS_REVISION_KEY);
            }
            defaulted |= oldRevision != null || newRevision != null;
        }

        defaulted |= nowPlaying.remove("retain.idle.call.details") != null;
        defaulted |= nowPlaying.remove("clear.voice.decode.quality.on.call.end") != null;
        if(!targetPortablePreferencesUsable(root))
        {
            for(String key: RECEIVER_PREFERENCE_KEYS)
            {
                defaulted |= nowPlaying.remove(key) != null;
            }
        }
        if(!targetPortablePreferencesUsable(root))
        {
            throw new IOException("portable preference repair did not produce current semantics");
        }
        String payload = transformed || defaulted || canonicalize ? MAPPER.writeValueAsString(root) : original;
        return new PortablePreferenceRepair(payload, transformed, defaulted, false);
    }

    private static boolean targetPortablePreferencesUsable(ObjectNode root)
    {
        var nodes = root.fields();
        while(nodes.hasNext())
        {
            var node = nodes.next();
            if(!(node.getValue() instanceof ObjectNode preferences))
            {
                return false;
            }
            var values = preferences.fields();
            while(values.hasNext())
            {
                var value = values.next();
                if(!value.getValue().isTextual() ||
                    Format6To7DatabaseMigration.RETIRED_WEB_AUDIO_KEYS.contains(value.getKey()))
                {
                    return false;
                }
            }
        }

        if(!(root.get(NOW_PLAYING_NODE) instanceof ObjectNode nowPlaying))
        {
            return true;
        }
        if(nowPlaying.has(SITE_SETTINGS_REVISION_KEY) ||
            nowPlaying.has("retain.idle.call.details") ||
            nowPlaying.has("clear.voice.decode.quality.on.call.end"))
        {
            return false;
        }
        JsonNode revision = nowPlaying.get(RECEIVER_SETTINGS_REVISION_KEY);
        JsonNode ageOut = nowPlaying.get("traffic.grant.age.out.milliseconds");
        if(revision == null && ageOut == null)
        {
            return true;
        }
        if(!positiveCanonicalRevision(revision))
        {
            return false;
        }
        if(ageOut != null)
        {
            if(!ageOut.isTextual())
            {
                return false;
            }
            try
            {
                long value = Long.parseLong(ageOut.textValue());
                if(value < 100 || value > 15_000 || !Long.toString(value).equals(ageOut.textValue()))
                {
                    return false;
                }
            }
            catch(NumberFormatException exception)
            {
                return false;
            }
        }
        return true;
    }

    private static boolean positiveCanonicalRevision(JsonNode revision)
    {
        if(revision == null || !revision.isTextual())
        {
            return false;
        }
        try
        {
            long value = Long.parseLong(revision.textValue());
            return value > 0 && value < Long.MAX_VALUE - 1 &&
                Long.toString(value).equals(revision.textValue());
        }
        catch(NumberFormatException exception)
        {
            return false;
        }
    }

    /** Selects independently usable Alias objects and repairs the required Default scan-list invariant. */
    private static AliasAdminIndex inspectAliasesAndScanLists(Connection connection) throws SQLException
    {
        Map<Long,AliasListFamily> aliasLists = new LinkedHashMap<>();
        Map<String,List<Long>> aliasListsByExactName = new LinkedHashMap<>();
        Map<String,List<Long>> aliasListsByName = new LinkedHashMap<>();
        List<ConfigurationNameRepair.Candidate> aliasListNameCandidates = new ArrayList<>();
        Map<Long,String> originalAliasListNames = new LinkedHashMap<>();
        Map<Long,Boolean> aliasListRecordPolicies = new LinkedHashMap<>();
        long defaultedAliasListPolicies = 0;
        long droppedRows = 0;
        Set<Long> defaultedScanListIds = new HashSet<>();
        long generatedDefaultRows = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, typeof(name) AS name_type, length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4096
                        THEN name END AS safe_name,
                   typeof(family) AS family_type,
                   CASE WHEN typeof(family)='text' AND length(CAST(family AS BLOB))<=32
                        THEN family END AS safe_family,
                   unmatched_talkgroup_record_enabled,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4096
                        THEN length(trim(name)) END AS trimmed_name_length
            FROM alias_list
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long id = positiveId(rows, "id", "Alias List");
                    String sourceName = "text".equals(rows.getString("name_type")) &&
                        rows.getLong("name_bytes") <= 4096 ? safeText(rows, "safe_name") : null;
                    String name = sourceName == null || sourceName.isBlank() ?
                        "Recovered Alias List " + id : sourceName;
                    String label = "Alias List " + displayName(name) + " (database row " + id + ")";
                    String preparedName = name.trim();
                    if(!"text".equals(rows.getString("family_type")))
                    {
                        throw new IOException(label + " has an invalid family");
                    }
                    AliasListFamily family = AliasListFamily.valueOf(
                        requiredStoredText(rows, "safe_family", label + " family"));
                    Integer recordPolicy = safeInteger(rows, "unmatched_talkgroup_record_enabled");
                    boolean validRecordPolicy = recordPolicy != null &&
                        (recordPolicy == 0 || recordPolicy == 1);
                    aliasListRecordPolicies.put(id, validRecordPolicy && recordPolicy == 1);
                    if(!validRecordPolicy)
                    {
                        defaultedAliasListPolicies++;
                    }
                    aliasLists.put(id, family);
                    aliasListNameCandidates.add(new ConfigurationNameRepair.Candidate(id, name));
                    originalAliasListNames.put(id, sourceName);
                    aliasListsByExactName.computeIfAbsent(ConfigurationNameRepair.normalize(name),
                            ignored -> new ArrayList<>())
                        .add(id);
                    aliasListsByName.computeIfAbsent(ConfigurationNameRepair.normalize(preparedName),
                            ignored -> new ArrayList<>())
                        .add(id);
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
            }
        }
        Map<Long,String> aliasListNames = ConfigurationNameRepair.plan(aliasListNameCandidates, 25, false);
        long normalizedAliasListNames = aliasListNames.entrySet().stream()
            .filter(entry -> !entry.getValue().equals(originalAliasListNames.get(entry.getKey()))).count();

        List<ScanListRow> scanListRows = new ArrayList<>();
        List<ConfigurationNameRepair.Candidate> scanListNameCandidates = new ArrayList<>();
        Map<Long,String> originalScanListNames = new LinkedHashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order,
                   typeof(name) AS name_type, length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4096
                        THEN name END AS safe_name,
                   typeof(description) AS description_type,
                   length(CAST(description AS BLOB)) AS description_bytes,
                   CASE WHEN typeof(description)='text' AND length(CAST(description AS BLOB))<=8192
                        THEN description END AS safe_description,
                   published, is_default,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4096
                        THEN length(trim(name)) END AS trimmed_name_length,
                   CASE WHEN typeof(description)='text' AND length(CAST(description AS BLOB))<=8192
                        THEN length(trim(description)) END AS trimmed_description_length
            FROM scan_list
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long id = positiveId(rows, "id", "Scan list");
                    String sourceName = "text".equals(rows.getString("name_type")) &&
                        rows.getLong("name_bytes") <= 4096 ? safeText(rows, "safe_name") : null;
                    String name = sourceName == null || sourceName.strip().isBlank() ?
                        "Recovered Scan List " + id : sourceName;
                    String label = "Scan list " + displayName(name) + " (database row " + id + ")";
                    Integer storedOrder = safeInteger(rows, "sort_order");
                    int sortOrder = storedOrder != null && storedOrder >= 0 ? storedOrder : scanListRows.size();
                    boolean repaired = sourceName == null || sourceName.strip().isBlank() ||
                        storedOrder == null || storedOrder < 0;
                    String description = safeText(rows, "safe_description");
                    Integer descriptionLength = safeInteger(rows, "trimmed_description_length");
                    String descriptionType = rows.getString("description_type");
                    long descriptionBytes = rows.getLong("description_bytes");
                    if(!"null".equals(descriptionType) &&
                        (!"text".equals(descriptionType) || descriptionBytes > 8192 || description == null ||
                            descriptionLength == null || descriptionLength < 1 || descriptionLength > 1000 ||
                            ConfigurationNameRepair.codePointLength(description.strip()) > 1000))
                    {
                        description = null;
                        repaired = true;
                    }
                    Integer publishedValue = safeInteger(rows, "published");
                    Integer defaultValue = safeInteger(rows, "is_default");
                    boolean validPublished = publishedValue != null &&
                        (publishedValue == 0 || publishedValue == 1);
                    boolean validDefault = defaultValue != null && (defaultValue == 0 || defaultValue == 1);
                    boolean published = !validPublished || publishedValue == 1;
                    boolean isDefault = validDefault && defaultValue == 1;
                    repaired |= !validPublished || !validDefault;
                    if(isDefault && !published)
                    {
                        published = true;
                        repaired = true;
                    }
                    scanListRows.add(new ScanListRow(id, sortOrder, name, description, published, isDefault));
                    scanListNameCandidates.add(new ConfigurationNameRepair.Candidate(id, name));
                    originalScanListNames.put(id, sourceName);
                    if(repaired)
                    {
                        defaultedScanListIds.add(id);
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
            }
        }

        Map<Long,String> scanListNames = ConfigurationNameRepair.plan(scanListNameCandidates, 100, true);
        for(int index = 0; index < scanListRows.size(); index++)
        {
            ScanListRow row = scanListRows.get(index);
            String preparedName = scanListNames.get(row.id());
            if(!preparedName.equals(originalScanListNames.get(row.id())))
            {
                defaultedScanListIds.add(row.id());
            }
            scanListRows.set(index, row.withName(preparedName));
        }

        List<Integer> defaults = new ArrayList<>();
        for(int index = 0; index < scanListRows.size(); index++)
        {
            if(scanListRows.get(index).isDefault())
            {
                defaults.add(index);
            }
        }
        if(defaults.isEmpty())
        {
            int namedDefault = -1;
            for(int index = 0; index < scanListRows.size(); index++)
            {
                if("Default".equalsIgnoreCase(scanListRows.get(index).name()))
                {
                    namedDefault = index;
                    break;
                }
            }
            if(namedDefault >= 0)
            {
                ScanListRow row = scanListRows.get(namedDefault);
                scanListRows.set(namedDefault, row.withDefault());
                defaultedScanListIds.add(row.id());
            }
            else
            {
                scanListRows.add(new ScanListRow(null, 0, uniqueDefaultScanListName(scanListRows), null, true, true));
                generatedDefaultRows++;
            }
        }
        else if(defaults.size() > 1)
        {
            int selectedDefault = defaults.stream()
                .filter(index -> "Default".equalsIgnoreCase(scanListRows.get(index).name()))
                .findFirst().orElse(defaults.getFirst());
            for(int rowIndex: defaults)
            {
                if(rowIndex != selectedDefault)
                {
                    ScanListRow row = scanListRows.get(rowIndex);
                    scanListRows.set(rowIndex, row.withoutDefault());
                    defaultedScanListIds.add(row.id());
                }
            }
        }

        Set<Long> scanLists = scanListRows.stream().map(ScanListRow::id).filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Set<Long> aliases = new LinkedHashSet<>();
        Map<Long,String> aliasNames = new LinkedHashMap<>();
        Map<Long,AliasMatcherFields> aliasMatchers = new LinkedHashMap<>();
        Map<Long,AliasOptionalFields> aliasOptionalFields = new LinkedHashMap<>();
        long defaultedAliasRows = 0;
        String aliasQuery = """
            SELECT id, alias_list_id,
                   typeof(name) AS name_type, length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=%1$d
                        THEN name END AS name,
                   typeof(description) AS description_type,
                   length(CAST(description AS BLOB)) AS description_bytes,
                   CASE WHEN typeof(description)='text' AND length(CAST(description AS BLOB))<=%1$d
                        THEN description END AS description,
                   typeof(group_name) AS group_name_type,
                   length(CAST(group_name AS BLOB)) AS group_name_bytes,
                   CASE WHEN typeof(group_name)='text' AND length(CAST(group_name AS BLOB))<=%1$d
                        THEN group_name END AS group_name,
                   typeof(color) AS color_type,
                   CASE WHEN typeof(color)='integer' THEN color END AS color,
                   typeof(icon_name) AS icon_name_type,
                   length(CAST(icon_name AS BLOB)) AS icon_name_bytes,
                   CASE WHEN typeof(icon_name)='text' AND length(CAST(icon_name AS BLOB))<=%1$d
                        THEN icon_name END AS icon_name,
                   typeof(stream_as_talkgroup) AS stream_as_talkgroup_type,
                   CASE WHEN typeof(stream_as_talkgroup)='integer' THEN stream_as_talkgroup END AS stream_as_talkgroup,
                   typeof(record_enabled) AS record_enabled_type,
                   CASE WHEN typeof(record_enabled)='integer' THEN record_enabled END AS record_enabled,
                   CASE WHEN typeof(matcher_type)='text' AND length(CAST(matcher_type AS BLOB))<=64
                        THEN matcher_type END AS matcher_type,
                   typeof(protocol) AS protocol_type, length(CAST(protocol AS BLOB)) AS protocol_bytes,
                   CASE WHEN typeof(protocol)='text' AND length(CAST(protocol AS BLOB))<=64
                        THEN protocol END AS protocol,
                   typeof(value) AS value_type,
                   CASE WHEN typeof(value)='integer' THEN value END AS value,
                   typeof(min_value) AS min_value_type,
                   CASE WHEN typeof(min_value)='integer' THEN min_value END AS min_value,
                   typeof(max_value) AS max_value_type,
                   CASE WHEN typeof(max_value)='integer' THEN max_value END AS max_value,
                   typeof(text_value) AS text_value_type,
                   length(CAST(text_value AS BLOB)) AS text_value_bytes,
                   CASE WHEN typeof(text_value)='text' AND length(CAST(text_value AS BLOB))<=%1$d
                        THEN text_value END AS text_value,
                   typeof(numeric_value) AS numeric_value_type,
                   CASE WHEN typeof(numeric_value)='integer' THEN numeric_value END AS numeric_value,
                   typeof(tone_sequence) AS tone_sequence_type,
                   length(CAST(tone_sequence AS BLOB)) AS tone_sequence_bytes,
                   CASE WHEN typeof(tone_sequence)='text' AND length(CAST(tone_sequence AS BLOB))<=%1$d
                        THEN tone_sequence END AS tone_sequence,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=%1$d
                        THEN length(trim(name)) END AS trimmed_name_length,
                   CASE WHEN typeof(text_value)='text' AND length(CAST(text_value AS BLOB))<=%1$d
                        THEN length(trim(text_value)) END AS trimmed_text_value_length
            FROM alias
            ORDER BY id
            """.formatted(MAXIMUM_ALIAS_TEXT_BYTES);
        try(PreparedStatement statement = connection.prepareStatement(aliasQuery);
            ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long id = positiveId(rows, "id", "Alias");
                    String name;
                    boolean defaultedName = false;
                    try
                    {
                        name = optionalBoundedStoredText(rows, "name", "Alias database row " + id,
                            MAXIMUM_ALIAS_TEXT_BYTES);
                    }
                    catch(IOException exception)
                    {
                        name = null;
                    }
                    if(name == null || name.trim().isBlank())
                    {
                        name = "Recovered Alias " + id;
                        defaultedName = true;
                    }
                    String label = "Alias " + displayName(name) + " (database row " + id + ")";
                    long aliasListId = positiveId(rows, "alias_list_id", label);
                    AliasListFamily family = aliasLists.get(aliasListId);
                    if(family == null)
                    {
                        throw new IOException(label + " refers to an unusable Alias List");
                    }
                    AliasMatcherFields matcher = inspectAliasMatcher(rows, family, label);
                    AliasOptionalFields optionalFields = inspectAliasOptionalFields(rows);
                    aliasNames.put(id, name);
                    aliasMatchers.put(id, matcher);
                    aliasOptionalFields.put(id, optionalFields);
                    if(defaultedName || matcher.defaultedFieldCount() > 0 ||
                        optionalFields.defaultedFieldCount() > 0)
                    {
                        defaultedAliasRows = Math.addExact(defaultedAliasRows, 1);
                    }
                    aliases.add(id);
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
            }
        }

        GeneratedDefaultMembershipRecovery recoveredDefaultMemberships =
            inspectGeneratedDefaultMemberships(connection, aliases, aliasLists.keySet(), scanLists);
        long orphanedRows = countOrphanedScanListMemberships(connection, "alias_scan_list_membership", "alias_id",
            aliases, scanLists,
            "Alias scan-list membership");
        orphanedRows = Math.addExact(orphanedRows, countOrphanedScanListMemberships(connection,
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id", aliasLists.keySet(), scanLists,
            "Alias List scan-list membership"));
        orphanedRows = Math.subtractExact(orphanedRows, recoveredDefaultMemberships.sourceRows());
        long preservedRows = Math.subtractExact(sumCounts(aliasLists.size(), scanLists.size(), aliases.size(),
            countRows(connection, List.of("alias_scan_list_membership",
                "alias_list_unmatched_talkgroup_scan_list_membership"))), orphanedRows);
        aliasListsByExactName.replaceAll((ignored, ids) -> List.copyOf(ids));
        aliasListsByName.replaceAll((ignored, ids) -> List.copyOf(ids));
        long defaultedRows = sumCounts(defaultedScanListIds.size(), generatedDefaultRows,
            normalizedAliasListNames, defaultedAliasListPolicies);
        return new AliasAdminIndex(Set.copyOf(aliasLists.keySet()), Set.copyOf(aliases),
            Map.copyOf(aliasListsByExactName), Map.copyOf(aliasListsByName), Map.copyOf(aliasLists),
            Map.copyOf(aliasListNames),
            Map.copyOf(aliasListRecordPolicies), Map.copyOf(aliasNames), Map.copyOf(aliasMatchers),
            Map.copyOf(aliasOptionalFields), List.copyOf(scanListRows),
            orphanedRows, droppedRows, defaultedRows, defaultedAliasRows, preservedRows,
            recoveredDefaultMemberships);
    }

    private static AliasOptionalFields inspectAliasOptionalFields(ResultSet rows) throws SQLException
    {
        boolean defaultDescription = storedInvalidBoundedText(rows, "description", MAXIMUM_ALIAS_TEXT_BYTES);
        boolean defaultGroupName = storedInvalidBoundedText(rows, "group_name", MAXIMUM_ALIAS_TEXT_BYTES);
        Integer storedColor = safeInteger(rows, "color");
        boolean defaultColor = storedColor == null;
        boolean defaultIconName = storedInvalidBoundedText(rows, "icon_name", MAXIMUM_ALIAS_TEXT_BYTES);
        Integer streamAsTalkgroup = safeInteger(rows, "stream_as_talkgroup");
        boolean defaultStreamAs = !"null".equals(rows.getString("stream_as_talkgroup_type")) &&
            (streamAsTalkgroup == null ||
            streamAsTalkgroup < 1 || streamAsTalkgroup > 0xFFFFFF);
        Boolean storedRecordEnabled = safeBoolean(rows, "record_enabled");
        boolean defaultRecordEnabled = storedRecordEnabled == null;
        long defaultedFields = (defaultDescription ? 1 : 0) + (defaultGroupName ? 1 : 0) +
            (defaultColor ? 1 : 0) + (defaultIconName ? 1 : 0) + (defaultStreamAs ? 1 : 0) +
            (defaultRecordEnabled ? 1 : 0);
        return new AliasOptionalFields(defaultDescription ? null : safeText(rows, "description"),
            defaultGroupName ? null : safeText(rows, "group_name"), defaultColor ? 0 : storedColor,
            defaultIconName ? null : safeText(rows, "icon_name"), defaultStreamAs ? null : streamAsTalkgroup,
            !defaultRecordEnabled && storedRecordEnabled, defaultedFields);
    }

    private static String uniqueDefaultScanListName(List<ScanListRow> rows)
    {
        Set<String> names = rows.stream().map(ScanListRow::name).map(String::strip)
            .map(ConfigurationNameRepair::normalize)
            .collect(java.util.stream.Collectors.toSet());
        if(!names.contains("default"))
        {
            return "Default";
        }
        int suffix = 2;
        while(names.contains(("Default (" + suffix + ")").toLowerCase(Locale.ROOT)))
        {
            suffix++;
        }
        return "Default (" + suffix + ")";
    }

    static AliasMatcherFields inspectAliasMatcher(ResultSet rows, AliasListFamily family, String label)
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

        String protocolText = null;
        Integer value = null;
        Integer minimum = null;
        Integer maximum = null;
        String textValue = null;
        Integer numericValue = null;
        String toneSequence = null;
        int retainedPayloadFields;

        switch(type)
        {
            case TALKGROUP, RADIO_ID -> {
                protocolText = requiredBoundedStoredText(rows, "protocol", label + " protocol", 64);
                value = nullableInteger(rows, "value", label);
                requireAliasPayload(label, value != null);
                validateProtocolMatcher(family, type, protocolText, value, value, label);
                retainedPayloadFields = 2;
            }
            case TALKGROUP_RANGE, RADIO_ID_RANGE -> {
                protocolText = requiredBoundedStoredText(rows, "protocol", label + " protocol", 64);
                minimum = nullableInteger(rows, "min_value", label);
                maximum = nullableInteger(rows, "max_value", label);
                requireAliasPayload(label, minimum != null && maximum != null && minimum < maximum);
                validateProtocolMatcher(family, type, protocolText, minimum, maximum, label);
                retainedPayloadFields = 3;
            }
            case STATUS, UNIT_STATUS -> {
                numericValue = nullableInteger(rows, "numeric_value", label);
                requireAliasPayload(label, numericValue != null);
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
                retainedPayloadFields = 1;
            }
            case TONES -> {
                toneSequence = requiredBoundedStoredText(rows, "tone_sequence", label + " tone sequence",
                    MAXIMUM_ALIAS_TEXT_BYTES);
                requireAliasPayload(label, !toneSequence.isEmpty());
                if(family != AliasListFamily.P25 && family != AliasListFamily.DMR)
                {
                    throw new IOException(label + " tone matcher is not supported by its Alias List family");
                }
                validateToneSequence(toneSequence, label);
                retainedPayloadFields = 1;
            }
            case DCS -> {
                textValue = requiredBoundedStoredText(rows, "text_value", label + " text value",
                    MAXIMUM_ALIAS_TEXT_BYTES);
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
                retainedPayloadFields = 1;
            }
            case ESN -> {
                textValue = requiredBoundedStoredText(rows, "text_value", label + " text value",
                    MAXIMUM_ALIAS_TEXT_BYTES);
                requireAliasPayload(label, !textValue.trim().isEmpty());
                if(family != AliasListFamily.NBFM)
                {
                    throw new IOException(label + " ESN matcher is not supported by its Alias List family");
                }
                retainedPayloadFields = 1;
            }
            default -> throw new IOException(label + " has a matcher type that format 15 does not support");
        }

        long populatedPayloadFields = storedPayloadFieldCount(rows);
        return new AliasMatcherFields(type.name(), protocolText, value, minimum, maximum, textValue, numericValue,
            toneSequence, Math.max(0, populatedPayloadFields - retainedPayloadFields));
    }

    static void validateAliasMatcher(ResultSet rows, AliasListFamily family, String label)
        throws SQLException, IOException
    {
        if(inspectAliasMatcher(rows, family, label).defaultedFieldCount() > 0)
        {
            throw new IOException(label + " has stale values outside its active matcher fields");
        }
    }

    private static long storedPayloadFieldCount(ResultSet rows) throws SQLException
    {
        long populated = 0;
        for(String column: List.of("protocol", "text_value", "tone_sequence"))
        {
            populated += "null".equals(rows.getString(column + "_type")) ? 0 : 1;
        }
        for(String column: List.of("value", "min_value", "max_value", "numeric_value"))
        {
            populated += "null".equals(rows.getString(column + "_type")) ? 0 : 1;
        }
        return populated;
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

    private static GeneratedDefaultMembershipRecovery inspectGeneratedDefaultMemberships(Connection connection,
                                                                                            Set<Long> aliases,
                                                                                            Set<Long> aliasLists,
                                                                                            Set<Long> retainedScanLists)
        throws SQLException
    {
        Set<Long> aliasIds = new LinkedHashSet<>();
        Set<Long> aliasListIds = new LinkedHashSet<>();
        long sourceRows = collectGeneratedDefaultMembershipOwners(connection, "alias_scan_list_membership",
            "alias_id", aliases, retainedScanLists, "Alias scan-list membership", aliasIds);
        sourceRows = Math.addExact(sourceRows, collectGeneratedDefaultMembershipOwners(connection,
            "alias_list_unmatched_talkgroup_scan_list_membership", "alias_list_id", aliasLists,
            retainedScanLists, "Alias List scan-list membership", aliasListIds));
        return new GeneratedDefaultMembershipRecovery(Set.copyOf(aliasIds), Set.copyOf(aliasListIds), sourceRows);
    }

    private static long collectGeneratedDefaultMembershipOwners(Connection connection, String table,
                                                                  String ownerColumn, Set<Long> validOwners,
                                                                  Set<Long> retainedScanLists, String description,
                                                                  Set<Long> recoveredOwners)
        throws SQLException
    {
        long sourceRows = 0;
        String sql = "SELECT " + identifier(ownerColumn) + ", scan_list_id FROM " + identifier(table) +
            " ORDER BY " + identifier(ownerColumn) + ", scan_list_id";
        try(PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long ownerId = positiveId(rows, ownerColumn, description);
                    long scanListId = positiveId(rows, "scan_list_id", description);
                    if(validOwners.contains(ownerId) && !retainedScanLists.contains(scanListId))
                    {
                        recoveredOwners.add(ownerId);
                        sourceRows = Math.addExact(sourceRows, 1);
                    }
                }
                catch(IOException | RuntimeException ignored)
                {
                    // A malformed owner or target carries no usable routing intent.
                }
            }
        }
        return sourceRows;
    }

    private static long countOrphanedScanListMemberships(Connection connection, String table, String ownerColumn,
                                                          Set<Long> owners, Set<Long> scanLists, String description)
        throws SQLException
    {
        long orphanedRows = 0;
        String sql = "SELECT " + identifier(ownerColumn) + ", scan_list_id FROM " + identifier(table) +
            " ORDER BY " + identifier(ownerColumn) + ", scan_list_id";
        try(PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long ownerId = positiveId(rows, ownerColumn, description);
                    long scanListId = positiveId(rows, "scan_list_id", description);
                    if(!owners.contains(ownerId) || !scanLists.contains(scanListId))
                    {
                        orphanedRows++;
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    orphanedRows++;
                }
            }
        }
        return orphanedRows;
    }

    private static RowInspection<ChannelRow> inspectChannels(Connection connection,
                                                              Map<String,List<Long>> aliasListsByExactName,
                                                              Map<String,List<Long>> aliasListsByName,
                                                              Map<Long,AliasListFamily> aliasListFamilies)
        throws SQLException
    {
        List<ChannelRow> channels = new ArrayList<>();
        Set<String> configurationIds = new HashSet<>();
        Set<String> radioResolveIds = new HashSet<>();
        long relationshipsDropped = 0;
        long droppedRows = 0;
        long defaultedRows = 0;
        int sourceOrder = 0;
        Set<String> reservedRowIds = new HashSet<>();
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(configuration_id)='text'
                              AND length(CAST(configuration_id AS BLOB))<=128
                        THEN configuration_id END AS configuration_id
            FROM configuration_channel
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String id = tryCanonicalUuid(safeText(rows, "configuration_id"));
                if(id != null)
                {
                    reservedRowIds.add(id);
                }
            }
        }
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id,
                   CASE WHEN typeof(configuration_id)='text'
                              AND length(CAST(configuration_id AS BLOB))<=128
                        THEN configuration_id END AS configuration_id,
                   sort_order,
                   typeof(system_name) AS system_name_type,
                   length(CAST(system_name AS BLOB)) AS system_name_bytes,
                   CASE WHEN typeof(system_name)='text'
                              AND length(CAST(system_name AS BLOB))<=4194304
                        THEN system_name END AS system_name,
                   typeof(site_name) AS site_name_type,
                   length(CAST(site_name AS BLOB)) AS site_name_bytes,
                   CASE WHEN typeof(site_name)='text'
                              AND length(CAST(site_name AS BLOB))<=4194304
                        THEN site_name END AS site_name,
                   typeof(name) AS name_type,
                   length(CAST(name AS BLOB)) AS name_bytes,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4194304
                        THEN name END AS name,
                   typeof(alias_list_name) AS alias_list_name_type,
                   length(CAST(alias_list_name AS BLOB)) AS alias_list_name_bytes,
                   CASE WHEN typeof(alias_list_name)='text'
                              AND length(CAST(alias_list_name AS BLOB))<=4194304
                        THEN alias_list_name END AS alias_list_name,
                   typeof(radres_guid) AS radres_guid_type,
                   length(CAST(radres_guid AS BLOB)) AS radres_guid_bytes,
                   CASE WHEN typeof(radres_guid)='text' AND length(CAST(radres_guid AS BLOB))<=128
                        THEN radres_guid END AS radres_guid,
                   auto_start, auto_start_order,
                   CASE WHEN typeof(decoder_type)='text' AND length(CAST(decoder_type AS BLOB))<=64
                        THEN decoder_type END AS decoder_type,
                   CASE WHEN typeof(config_json)='text'
                              AND length(CAST(config_json AS BLOB)) <= 4194304
                        THEN config_json END AS config_json
            FROM configuration_channel
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                sourceOrder++;
                try
                {
                    long rowId = positiveId(rows, "id", "saved channel");
                    String systemName = safeText(rows, "system_name");
                    String siteName = safeText(rows, "site_name");
                    String name = safeText(rows, "name");
                    String label = savedChannelLabel(rowId, systemName, siteName, name);
                    ObjectNode payload = parseObject(safeText(rows, "config_json"), label);
                    requireTypedChannelComponent(payload, "decodeConfiguration", label);
                    requireTypedChannelComponent(payload, "sourceConfiguration", label);

                    boolean repaired = storedInvalidBoundedText(rows, "system_name", 4_194_304) ||
                        storedInvalidBoundedText(rows, "site_name", 4_194_304) ||
                        storedInvalidBoundedText(rows, "name", 4_194_304) ||
                        storedInvalidBoundedText(rows, "alias_list_name", 4_194_304) ||
                        storedInvalidBoundedText(rows, "radres_guid", 128);
                    long rowRelationshipsDropped = 0;
                    String storedConfigurationId = safeText(rows, "configuration_id");
                    String configurationId = tryCanonicalUuid(storedConfigurationId);
                    repaired |= configurationId == null || !configurationId.equals(storedConfigurationId);
                    if(configurationId == null || configurationIds.contains(configurationId))
                    {
                        String jsonId = tryCanonicalUuid(payload.get("configurationId"));
                        if(jsonId != null && !reservedRowIds.contains(jsonId) && !configurationIds.contains(jsonId))
                        {
                            configurationId = jsonId;
                        }
                        else
                        {
                            Set<String> unavailable = new HashSet<>(reservedRowIds);
                            unavailable.addAll(configurationIds);
                            configurationId = deterministicChannelId(rowId, unavailable);
                        }
                        repaired = true;
                    }
                    payload.put("configurationId", configurationId);

                    String legacyDecoderType = inferLegacyDecoderType(payload, safeText(rows, "decoder_type"));
                    normalizeLegacyChannelMode(payload, legacyDecoderType, label);
                    Boolean storedAutoStart = safeBoolean(rows, "auto_start");
                    boolean autoStart = storedAutoStart != null && storedAutoStart;
                    if(storedAutoStart == null)
                    {
                        repaired = true;
                    }
                    Integer autoStartOrder = safeInteger(rows, "auto_start_order");
                    if(rows.getObject("auto_start_order") != null && autoStartOrder == null)
                    {
                        repaired = true;
                    }
                    String aliasListName = normalizeBlank(safeText(rows, "alias_list_name"));
                    Long aliasListId = resolveAliasList(aliasListName, aliasListsByExactName, aliasListsByName);
                    if(aliasListName != null && aliasListId == null)
                    {
                        aliasListName = null;
                        rowRelationshipsDropped++;
                    }

                    String storedRadioResolveId = normalizeBlank(safeText(rows, "radres_guid"));
                    String radioResolveId = tryCanonicalUuid(storedRadioResolveId);
                    if(storedRadioResolveId != null && !Objects.equals(storedRadioResolveId, radioResolveId))
                    {
                        repaired = true;
                    }
                    if(radioResolveId != null && radioResolveIds.contains(radioResolveId))
                    {
                        radioResolveId = null;
                        rowRelationshipsDropped++;
                        repaired = true;
                    }

                    //Format 14 loaded these values from the relational row after decoding. Keep those values, but
                    //derive every query projection and the channel kind from the decodable JSON itself.
                    payload.remove(LEGACY_CHANNEL_ROW_OWNED_JSON_FIELDS);
                    payload.put("configurationId", configurationId);
                    ChannelConfigurationSalvage.Result channelSalvage =
                        ChannelConfigurationSalvage.decode(MAPPER, payload, label);
                    Channel channel = channelSalvage.channel();
                    payload = channelSalvage.payload();
                    repaired |= channelSalvage.defaultedComponents() > 0;
                    channel.setSystem(systemName);
                    channel.setSite(siteName);
                    channel.setName(name);
                    channel.setAliasListName(aliasListName);
                    channel.setAliasListId(aliasListId != null ? aliasListId : AliasListDefinition.UNASSIGNED_ID);
                    channel.setRadioResolveId(radioResolveId);
                    channel.setAutoStart(autoStart);
                    channel.setAutoStartOrder(autoStartOrder);
                    if(!ChannelConfigurationPolicy.isActive(channel))
                    {
                        throw new IOException(label + " is not an active supported channel");
                    }
                    if(aliasListId != null && AliasMatchRegistry.familyFor(
                        channel.getDecodeConfiguration().getDecoderType()) != aliasListFamilies.get(aliasListId))
                    {
                        aliasListId = null;
                        aliasListName = null;
                        channel.setAliasListName(null);
                        channel.setAliasListId(AliasListDefinition.UNASSIGNED_ID);
                        rowRelationshipsDropped++;
                    }

                    String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
                    ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);
                    Integer storedSortOrder = safeInteger(rows, "sort_order");
                    int sortOrder = storedSortOrder != null && storedSortOrder >= 0 ? storedSortOrder : sourceOrder - 1;
                    if(storedSortOrder == null || storedSortOrder < 0)
                    {
                        repaired = true;
                    }
                    String decoderType = projection.decoderType();
                    if(decoderType == null || decoderType.isBlank())
                    {
                        throw new IOException(label + " has no decoder type");
                    }
                    Long primaryFrequency = projection.primaryFrequencyHz();
                    if(primaryFrequency != null && primaryFrequency <= 0)
                    {
                        primaryFrequency = null;
                        repaired = true;
                    }

                    payload.remove("configurationId");
                    configurationIds.add(configurationId);
                    if(radioResolveId != null)
                    {
                        radioResolveIds.add(radioResolveId);
                    }
                    channels.add(new ChannelRow(rowId, configurationId, channelKind, sortOrder,
                        systemName, siteName, name, aliasListId, radioResolveId, autoStart, autoStartOrder,
                        decoderType, projection.addressDomainCode(), primaryFrequency,
                        MAPPER.writeValueAsString(payload)));
                    relationshipsDropped = Math.addExact(relationshipsDropped, rowRelationshipsDropped);
                    defaultedRows += repaired ? 1 : 0;
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
            }
        }
        return new RowInspection<>(List.copyOf(channels), relationshipsDropped, droppedRows, defaultedRows);
    }

    private static Long resolveAliasList(String name, Map<String,List<Long>> aliasListsByExactName,
                                         Map<String,List<Long>> aliasListsByName)
    {
        if(name == null)
        {
            return null;
        }

        List<Long> exactMatches = aliasListsByExactName.getOrDefault(ConfigurationNameRepair.normalize(name),
            List.of());
        if(exactMatches.size() == 1)
        {
            return exactMatches.getFirst();
        }

        List<Long> matches = aliasListsByName.getOrDefault(ConfigurationNameRepair.normalize(name.trim()), List.of());
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static RowInspection<BroadcastRow> inspectBroadcasts(Connection connection) throws SQLException
    {
        List<BroadcastCandidate> candidates = new ArrayList<>();
        long droppedRows = 0;
        long defaultedRows = 0;
        int sourceOrder = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, sort_order,
                   CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=4194304
                        THEN name END AS name,
                   CASE WHEN typeof(config_json)='text'
                              AND length(CAST(config_json AS BLOB)) <= 4194304
                        THEN config_json END AS config_json
            FROM configuration_broadcast_stream
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                sourceOrder++;
                try
                {
                    long rowId = positiveId(rows, "id", "broadcast provider");
                    String storedName = safeText(rows, "name");
                    String label = broadcastProviderLabel(rowId, storedName);
                    ObjectNode payload = parseObject(safeText(rows, "config_json"), label);
                    normalizeLegacyBroadcastType(payload);
                    JsonNode storedPayloadName = payload.get("name");
                    String payloadName = storedPayloadName != null && storedPayloadName.isTextual() ?
                        normalizeBlank(storedPayloadName.textValue()) : null;
                    boolean repairedName = payloadName == null;
                    if(payloadName == null)
                    {
                        String recoveredName = normalizeBlank(storedName);
                        if(recoveredName == null)
                        {
                            recoveredName = "Recovered Broadcast Provider " + rowId;
                        }
                        recoveredName = ConfigurationNameRepair.truncateToCodePoints(recoveredName,
                            MAXIMUM_RECOVERED_BROADCAST_NAME_CODE_POINTS).strip();
                        payload.put("name", recoveredName);
                    }
                    BroadcastConfiguration configuration = decodeBroadcast(payload, label);
                    String name = normalizeBlank(configuration.getName());
                    if(name == null)
                    {
                        throw new IOException(label + " has no recoverable name");
                    }
                    Integer storedSortOrder = safeInteger(rows, "sort_order");
                    int sortOrder = storedSortOrder != null && storedSortOrder >= 0 ? storedSortOrder : sourceOrder - 1;
                    boolean repaired = repairedName || storedSortOrder == null || storedSortOrder < 0;
                    JsonNode storedId = payload.get("configurationId");
                    String candidateId = tryCanonicalUuid(storedId);
                    repaired |= storedId != null && (!storedId.isTextual() ||
                        !Objects.equals(storedId.textValue(), candidateId));
                    payload.remove(List.of("configurationId", "aliasListName"));
                    Set<String> routeNames = new LinkedHashSet<>();
                    routeNames.add(name);
                    if(normalizeBlank(storedName) != null)
                    {
                        routeNames.add(storedName);
                    }
                    candidates.add(new BroadcastCandidate(rowId, candidateId, sortOrder, name,
                        Set.copyOf(routeNames),
                        MAPPER.writeValueAsString(payload), repaired));
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
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
            boolean repaired = candidate.repaired() || candidateId == null ||
                !Objects.equals(candidateId, configurationId);
            unavailable.add(configurationId);
            broadcasts.add(new BroadcastRow(candidate.id(), configurationId, candidate.sortOrder(), candidate.name(),
                candidate.routeNames(), candidate.payload()));
            defaultedRows += repaired ? 1 : 0;
        }
        return new RowInspection<>(List.copyOf(broadcasts), 0, droppedRows, defaultedRows);
    }

    private static Map<String,List<BroadcastRow>> broadcastsByName(List<BroadcastRow> broadcasts)
    {
        Map<String,List<BroadcastRow>> byName = new LinkedHashMap<>();
        for(BroadcastRow broadcast: broadcasts)
        {
            for(String routeName: broadcast.routeNames())
            {
                List<BroadcastRow> matches = byName.computeIfAbsent(routeName, ignored -> new ArrayList<>());
                if(matches.stream().noneMatch(match -> match.configurationId().equals(broadcast.configurationId())))
                {
                    matches.add(broadcast);
                }
            }
        }
        byName.replaceAll((ignored, matches) -> List.copyOf(matches));
        return Map.copyOf(byName);
    }

    private static RelationshipInspection<AliasRoute> inspectAliasRoutes(Connection connection,
                                                                          Map<String,List<BroadcastRow>> broadcastsByName,
                                                                          Set<Long> aliasIds)
        throws SQLException
    {
        List<AliasRoute> routes = new ArrayList<>();
        Set<ResolvedRouteTarget> retainedTargets = new HashSet<>();
        long orphanedRows = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, alias_id,
                   CASE WHEN typeof(channel_name)='text'
                              AND length(CAST(channel_name AS BLOB))<=4194304
                        THEN channel_name END AS channel_name
            FROM alias_broadcast_channel ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long id = positiveId(rows, "id", "Alias broadcast route");
                    String name = safeText(rows, "channel_name");
                    long aliasId = positiveId(rows, "alias_id", "Alias broadcast route");
                    String broadcastConfigurationId = resolveBroadcast(name, broadcastsByName);
                    if(!aliasIds.contains(aliasId) || broadcastConfigurationId == null ||
                        !retainedTargets.add(new ResolvedRouteTarget(aliasId, broadcastConfigurationId)))
                    {
                        orphanedRows++;
                    }
                    else
                    {
                        routes.add(new AliasRoute(id, aliasId, broadcastConfigurationId));
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    orphanedRows++;
                }
            }
        }
        return new RelationshipInspection<>(List.copyOf(routes), orphanedRows);
    }

    private static RelationshipInspection<UnmatchedRoute> inspectUnmatchedRoutes(Connection connection,
                                                                                  Map<String,List<BroadcastRow>> broadcastsByName,
                                                                                  Set<Long> aliasListIds)
        throws SQLException
    {
        List<UnmatchedRoute> routes = new ArrayList<>();
        Set<ResolvedRouteTarget> retainedTargets = new HashSet<>();
        long orphanedRows = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, alias_list_id,
                   CASE WHEN typeof(channel_name)='text'
                              AND length(CAST(channel_name AS BLOB))<=4194304
                        THEN channel_name END AS channel_name
            FROM alias_list_unmatched_talkgroup_stream ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                try
                {
                    long id = positiveId(rows, "id", "unmatched-talkgroup broadcast route");
                    String name = safeText(rows, "channel_name");
                    long aliasListId = positiveId(rows, "alias_list_id", "unmatched-talkgroup broadcast route");
                    String broadcastConfigurationId = resolveBroadcast(name, broadcastsByName);
                    if(!aliasListIds.contains(aliasListId) || broadcastConfigurationId == null ||
                        !retainedTargets.add(new ResolvedRouteTarget(aliasListId, broadcastConfigurationId)))
                    {
                        orphanedRows++;
                    }
                    else
                    {
                        routes.add(new UnmatchedRoute(id, aliasListId, broadcastConfigurationId));
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    orphanedRows++;
                }
            }
        }
        return new RelationshipInspection<>(List.copyOf(routes), orphanedRows);
    }

    private static String resolveBroadcast(String name, Map<String,List<BroadcastRow>> broadcastsByName)
    {
        if(name == null || name.isBlank())
        {
            return null;
        }
        List<BroadcastRow> matches = broadcastsByName.getOrDefault(name, List.of());
        if(matches.isEmpty())
        {
            return null;
        }
        return matches.size() == 1 ? matches.getFirst().configurationId() : null;
    }

    private static UserInspection inspectUsers(Connection connection) throws SQLException
    {
        List<WebUserRow> users = new ArrayList<>();
        long sourceRows = 0;
        long droppedRows = 0;
        boolean primaryPresent = false;
        boolean intendedPrimaryUnusable = false;
        int ordinaryUsers = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id,
                   CASE WHEN typeof(username)='text' AND length(CAST(username AS BLOB)) <= 64
                        THEN username END AS username,
                   CASE WHEN typeof(tier)='text' AND length(CAST(tier AS BLOB)) <= 16
                        THEN tier END AS tier,
                   primary_admin, credential_version,
                   CASE WHEN typeof(password_algorithm)='text'
                              AND length(CAST(password_algorithm AS BLOB)) <= 64
                        THEN password_algorithm END AS password_algorithm,
                   password_iterations, password_derived_key_bits,
                   CASE WHEN typeof(password_salt)='blob' AND length(password_salt) <= 64
                        THEN password_salt END AS password_salt,
                   CASE WHEN typeof(password_hash)='blob' AND length(password_hash) <= 32
                        THEN password_hash END AS password_hash,
                   password_changed_at_ms, auth_revision,
                   CASE WHEN typeof(preferences_json)='text'
                              AND length(CAST(preferences_json AS BLOB)) <= 131072
                        THEN preferences_json END AS preferences_json,
                   preferences_revision,
                   created_at_ms, updated_at_ms
            FROM web_user
            ORDER BY id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                sourceRows++;
                String possibleUsername = safeText(rows, "username");
                Integer possiblePrimary = safeInteger(rows, "primary_admin");
                boolean intendedPrimary = "admin".equalsIgnoreCase(possibleUsername) ||
                    (possiblePrimary != null && possiblePrimary == 1);
                try
                {
                    long id = positiveId(rows, "id", "web user");
                    String username = requiredStoredText(rows, "username", "Web user database row " + id);
                    String label = "Web user " + displayName(username) + " (database row " + id + ")";
                    if(username.length() < 1 || username.length() > 64 ||
                        !username.matches("[a-z0-9][a-z0-9._-]*"))
                    {
                        throw new IOException(label + " has an invalid username");
                    }
                    String tier = requiredStoredText(rows, "tier", label + " access tier");
                    boolean primary = booleanFlag(rows, "primary_admin", label);
                    if(!Set.of("USER", "ADMIN").contains(tier) ||
                        (primary && (!"admin".equals(username) || !"ADMIN".equals(tier))) ||
                        (!primary && "admin".equals(username)))
                    {
                        throw new IOException(label + " has invalid account identity");
                    }
                    Long credentialVersion = safeLong(rows, "credential_version");
                    Long iterations = safeLong(rows, "password_iterations");
                    Long keyBits = safeLong(rows, "password_derived_key_bits");
                    byte[] salt = safeBlob(rows, "password_salt");
                    byte[] hash = safeBlob(rows, "password_hash");
                    Long storedPasswordChanged = safePositiveLong(rows, "password_changed_at_ms");
                    Long storedAuthRevision = safePositiveLong(rows, "auth_revision");
                    if(credentialVersion == null || credentialVersion != 1 ||
                        !"PBKDF2WithHmacSHA256".equals(safeText(rows, "password_algorithm")) ||
                        iterations == null || iterations < 600_000 || iterations > 5_000_000 ||
                        keyBits == null || keyBits != 256 || salt == null || salt.length < 16 || salt.length > 64 ||
                        hash == null || hash.length != 32)
                    {
                        throw new IOException(label + " has an unusable password credential");
                    }
                    boolean credentialBookkeepingDefaulted = storedPasswordChanged == null ||
                        storedAuthRevision == null || storedAuthRevision >= Long.MAX_VALUE - 1;
                    long passwordChanged = storedPasswordChanged != null ? storedPasswordChanged : 1;
                    long authRevision = storedAuthRevision != null && storedAuthRevision < Long.MAX_VALUE - 1 ?
                        storedAuthRevision : 1;

                    String preferences;
                    boolean preferenceTransformed = false;
                    Long storedRevision = safeLong(rows, "preferences_revision");
                    boolean revisionIncrementable = storedRevision != null && storedRevision > 0 &&
                        storedRevision < Long.MAX_VALUE - 2;
                    long revision = revisionIncrementable ? storedRevision + 1 : 1;
                    boolean preferenceDefaulted = !revisionIncrementable || credentialBookkeepingDefaulted;
                    try
                    {
                        String storedPreferences = safeText(rows, "preferences_json");
                        if(storedPreferences == null ||
                            storedPreferences.length() > MAXIMUM_WEB_PREFERENCES_CHARACTERS)
                        {
                            throw new IOException("invalid preferences");
                        }
                        preferences = Format14WebUserPreferencesCodec.migrate(storedPreferences);
                        preferenceTransformed = true;
                    }
                    catch(IOException | RuntimeException exception)
                    {
                        preferences = WebUserPreferencesCodec.encode(WebUserPreferences.defaults());
                        preferenceDefaulted = true;
                    }

                    Long createdAtValue = safePositiveLong(rows, "created_at_ms");
                    Long updatedAtValue = safePositiveLong(rows, "updated_at_ms");
                    long createdAt = createdAtValue != null ? createdAtValue : passwordChanged;
                    long updatedAt = updatedAtValue != null ? Math.max(updatedAtValue, createdAt) : createdAt;
                    if(createdAtValue == null || updatedAtValue == null || updatedAtValue < createdAt)
                    {
                        preferenceDefaulted = true;
                    }
                    WebUserRow user = new WebUserRow(id, username, tier, primary, 1,
                        "PBKDF2WithHmacSHA256", iterations.intValue(), 256, salt, hash, passwordChanged,
                        authRevision, preferences, revision, createdAt, updatedAt, preferenceTransformed,
                        preferenceDefaulted);
                    if(primary || ordinaryUsers++ < WebAccessService.MAXIMUM_USERS)
                    {
                        users.add(user);
                    }
                    else
                    {
                        droppedRows++;
                    }
                    primaryPresent |= primary;
                }
                catch(IOException | RuntimeException exception)
                {
                    if(intendedPrimary)
                    {
                        intendedPrimaryUnusable = true;
                    }
                    else
                    {
                        droppedRows++;
                    }
                }
            }
        }
        if(!primaryPresent || intendedPrimaryUnusable)
        {
            return new UserInspection(List.of(), 0, 0, 0, sourceRows, true);
        }
        long defaultedRows = users.stream().filter(WebUserRow::preferenceDefaulted).count();
        long transformedPreferences = users.stream().filter(WebUserRow::preferenceTransformed).count();
        return new UserInspection(List.copyOf(users), droppedRows, defaultedRows, transformedPreferences, 0, false);
    }

    private static PolicyInput inspectPolicy(Connection connection) throws SQLException
    {
        Map<String,PolicyRow> source = new LinkedHashMap<>();
        long droppedRows = 0;
        long sourceRows = 0;
        long defaultedRows = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(capability_id)='text'
                              AND length(CAST(capability_id AS BLOB)) <= 64
                        THEN capability_id END AS capability_id,
                   CASE WHEN typeof(required_tier)='text'
                              AND length(CAST(required_tier AS BLOB)) <= 16
                        THEN required_tier END AS required_tier,
                   CASE WHEN typeof(updated_at_ms)='integer' THEN updated_at_ms END AS updated_at_ms
            FROM web_access_policy
            ORDER BY capability_id
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                sourceRows++;
                try
                {
                    String id = requiredStoredText(rows, "capability_id", "Web access policy capability");
                    String label = "Web access policy " + displayName(id);
                    if(id.length() < 1 || id.length() > 64 || !id.equals(id.toLowerCase(Locale.ROOT)))
                    {
                        throw new IOException(label + " has an invalid capability name");
                    }
                    String tier = requiredStoredText(rows, "required_tier", label + " tier");
                    if(!Set.of("PUBLIC", "USER", "ADMIN").contains(tier))
                    {
                        throw new IOException(label + " has an invalid access tier");
                    }
                    Long storedUpdatedAt = safePositiveLong(rows, "updated_at_ms");
                    long updatedAt = storedUpdatedAt != null ? storedUpdatedAt : 1;
                    defaultedRows += storedUpdatedAt == null ? 1 : 0;
                    if(!LEGACY_POLICY_IDS.contains(id) && WebCapability.fromId(id).isEmpty())
                    {
                        droppedRows++;
                        continue;
                    }
                    source.put(id, new PolicyRow(id, tier, updatedAt));
                }
                catch(IOException | RuntimeException exception)
                {
                    droppedRows++;
                }
            }
        }

        PolicyRow systems = source.remove(SYSTEMS_POLICY_ID);
        PolicyRow conventional = source.remove(CONVENTIONAL_POLICY_ID);
        String tier = moreRestrictive(systems != null ? systems.tier() : "PUBLIC",
            conventional != null ? conventional.tier() : "PUBLIC");
        long updatedAt = Math.max(systems != null ? systems.updatedAtMs() : 0,
            conventional != null ? conventional.updatedAtMs() : 0);
        PolicyRow siteAccess = source.remove(SITE_ACCESS_POLICY_ID);

        Map<String,PolicyRow> target = new LinkedHashMap<>();
        for(PolicyRow row: source.values())
        {
            WebCapability capability = WebCapability.fromId(row.capabilityId()).orElse(null);
            if(capability == null || !capability.configurable() ||
                capability.defaultTier().name().equals(row.tier()))
            {
                droppedRows++;
            }
            else
            {
                mergePolicy(target, row);
            }
        }
        if(siteAccess != null)
        {
            mergeNonDefaultPolicy(target,
                new PolicyRow(WEB_ACCESS_POLICY_ID, siteAccess.tier(), siteAccess.updatedAtMs()));
        }
        if(!"PUBLIC".equals(tier))
        {
            mergeNonDefaultPolicy(target, new PolicyRow(RADIO_POLICY_ID, tier, updatedAt));
        }
        List<PolicyRow> rows = new ArrayList<>(target.values());
        rows.sort(java.util.Comparator.comparing(PolicyRow::capabilityId));
        int transformed = (systems != null ? 1 : 0) + (conventional != null ? 1 : 0) +
            (siteAccess != null ? 1 : 0);
        return new PolicyInput(List.copyOf(rows), transformed, droppedRows, sourceRows, defaultedRows);
    }

    private static void mergeNonDefaultPolicy(Map<String,PolicyRow> target, PolicyRow row)
    {
        WebCapability capability = WebCapability.fromId(row.capabilityId()).orElse(null);
        if(capability != null && capability.configurable() &&
            !capability.defaultTier().name().equals(row.tier()))
        {
            mergePolicy(target, row);
        }
    }

    private static void mergePolicy(Map<String,PolicyRow> target, PolicyRow row)
    {
        target.merge(row.capabilityId(), row, (first, second) -> new PolicyRow(first.capabilityId(),
            moreRestrictive(first.tier(), second.tier()), Math.max(first.updatedAtMs(), second.updatedAtMs())));
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
        copyUnchangedApplicationRows(connection, input);
        insertChannels(connection, input.channels());
        insertBroadcasts(connection, input.broadcasts());
        insertAliasRoutes(connection, input.aliasRoutes());
        insertUnmatchedRoutes(connection, input.unmatchedRoutes());
        insertPolicies(connection, input.policy());

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

    private static long smallestUnusedScanListId(List<ScanListRow> rows) throws SQLException
    {
        Set<Long> used = new HashSet<>();
        for(ScanListRow row: rows)
        {
            if(row.id() != null && row.id() > 0 &&
                row.id() < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM)
            {
                used.add(row.id());
            }
        }
        for(long candidate = 1; candidate < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM; candidate++)
        {
            if(!used.contains(candidate))
            {
                return candidate;
            }
        }
        throw new SQLException("No JSON-safe identifier remains for the repaired Default scan list");
    }

    private static Map<String,Long> retainedSequenceMaximums(AliasAdminIndex aliases, List<ChannelRow> channels,
                                                              List<BroadcastRow> broadcasts,
                                                              List<AliasRoute> aliasRoutes,
                                                              List<UnmatchedRoute> unmatchedRoutes,
                                                              List<WebUserRow> users)
    {
        Map<String,Long> values = new LinkedHashMap<>();
        values.put("alias_list", maximumId(aliases.aliasListIds()));
        values.put("alias", maximumId(aliases.aliasIds()));
        values.put("scan_list", aliases.scanLists().stream().map(ScanListRow::id).filter(Objects::nonNull)
            .mapToLong(Long::longValue).max().orElse(0));
        values.put("alias_broadcast_channel", aliasRoutes.stream().mapToLong(AliasRoute::id).max().orElse(0));
        values.put("alias_list_unmatched_talkgroup_stream",
            unmatchedRoutes.stream().mapToLong(UnmatchedRoute::id).max().orElse(0));
        values.put("configuration_channel", channels.stream().mapToLong(ChannelRow::id).max().orElse(0));
        values.put("configuration_broadcast_stream",
            broadcasts.stream().mapToLong(BroadcastRow::id).max().orElse(0));
        values.put("web_user", users.stream().mapToLong(WebUserRow::id).max().orElse(0));
        return Map.copyOf(values);
    }

    private static long maximumId(Collection<Long> ids)
    {
        return ids.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    private static SequenceInspection inspectSequences(Connection connection, Map<String,Long> retainedMaximums)
        throws SQLException
    {
        Map<String,Long> values = new LinkedHashMap<>();
        Set<String> observed = new HashSet<>();
        long defaultedRows = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(name)='text' AND length(CAST(name AS BLOB))<=128 THEN name END AS name,
                   seq
            FROM sqlite_sequence
            WHERE typeof(name)='text' AND length(CAST(name AS BLOB))<=128
            ORDER BY name, seq
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                String name = safeText(rows, "name");
                if(name == null || !PRESERVED_AUTOINCREMENT_TABLES.contains(name))
                {
                    continue;
                }
                observed.add(name);
                Long sequence = safeLong(rows, "seq");
                if(sequence == null || sequence < 0 ||
                    sequence >= SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM)
                {
                    defaultedRows++;
                    continue;
                }
                Long prior = values.put(name, Math.max(sequence, values.getOrDefault(name, 0L)));
                if(prior != null)
                {
                    defaultedRows++;
                }
            }
        }
        for(String table: PRESERVED_AUTOINCREMENT_TABLES)
        {
            long retainedMaximum = retainedMaximums.getOrDefault(table, 0L);
            Long sourceSequence = values.get(table);
            boolean exhausted = sourceSequence != null &&
                sourceSequence >= SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1 &&
                retainedMaximum < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1;
            if((sourceSequence != null && sourceSequence < retainedMaximum) || exhausted ||
                (sourceSequence == null && !observed.contains(table) && retainedMaximum > 0))
            {
                defaultedRows++;
            }
            if(exhausted)
            {
                values.remove(table);
            }
        }
        return new SequenceInspection(Map.copyOf(values), defaultedRows);
    }

    private static void restorePreservedSequences(Connection connection, Map<String,Long> sourceSequences)
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
                delete.setString(1, table);
                delete.executeUpdate();
                long sequence = Math.max(maximumId, sourceSequences.getOrDefault(table, 0L));
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
    private static void copyUnchangedApplicationRows(Connection connection, MigrationInput input) throws SQLException
    {
        try(PreparedStatement metadata = connection.prepareStatement("""
                INSERT INTO database_metadata (key, value, updated_at_ms) VALUES (?, ?, ?)
                """);
            PreparedStatement setting = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, ?, ?)
                """);
            PreparedStatement icon = connection.prepareStatement("""
                INSERT INTO application_icons (key, icons_json, updated_at_ms) VALUES (?, ?, ?)
                """);
            PreparedStatement aliasList = connection.prepareStatement("""
                INSERT INTO alias_list (id, name, family, unmatched_talkgroup_record_enabled)
                SELECT id, ?, family, ?
                FROM format14_alias_list WHERE id=?
                """);
            PreparedStatement scanList = connection.prepareStatement("""
                INSERT INTO scan_list (id, sort_order, name, description, published, is_default)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
            PreparedStatement alias = connection.prepareStatement("""
                INSERT INTO alias (
                    id, alias_list_id, name, description, group_name, color, icon_name,
                    stream_as_talkgroup, record_enabled, matcher_type, protocol, value, min_value,
                    max_value, text_value, numeric_value, tone_sequence
                )
                SELECT id, alias_list_id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM format14_alias WHERE id=?
                """);
            PreparedStatement user = connection.prepareStatement("""
                INSERT INTO web_user (
                    id, username, tier, primary_admin, credential_version, password_algorithm,
                    password_iterations, password_derived_key_bits, password_salt, password_hash,
                    password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
        {
            for(MetadataRow row: input.core().metadata())
            {
                metadata.setString(1, row.key());
                metadata.setString(2, row.value());
                metadata.setLong(3, row.updatedAtMs());
                metadata.executeUpdate();
            }
            for(SettingRow row: input.core().settings())
            {
                setting.setString(1, row.key());
                setting.setString(2, row.payload());
                setting.setLong(3, row.updatedAtMs());
                setting.executeUpdate();
            }
            for(IconRow row: input.core().icons())
            {
                icon.setString(1, row.key());
                icon.setString(2, row.payload());
                icon.setLong(3, row.updatedAtMs());
                icon.executeUpdate();
            }
            try(Statement clearSeed = connection.createStatement())
            {
                clearSeed.executeUpdate("DELETE FROM scan_list");
            }
            for(long id: input.aliases().aliasListIds())
            {
                aliasList.setString(1, input.aliases().aliasListNames().get(id));
                aliasList.setInt(2, input.aliases().aliasListRecordPolicies().getOrDefault(id, false) ? 1 : 0);
                aliasList.setLong(3, id);
                if(aliasList.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias List changed during migration");
                }
            }
            Long generatedScanListId = null;
            Long defaultScanListId = null;
            for(ScanListRow row: input.aliases().scanLists())
            {
                long targetId;
                if(row.id() != null)
                {
                    targetId = row.id();
                }
                else
                {
                    if(generatedScanListId == null)
                    {
                        long retainedMaximum = input.aliases().scanLists().stream().map(ScanListRow::id)
                            .filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0);
                        long sourceHighWater = input.sequences().getOrDefault("scan_list", 0L);
                        long highWater = Math.max(retainedMaximum, sourceHighWater);
                        generatedScanListId = highWater < SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM - 1 ?
                            highWater + 1 : smallestUnusedScanListId(input.aliases().scanLists());
                    }
                    targetId = generatedScanListId;
                }
                scanList.setLong(1, targetId);
                scanList.setInt(2, row.sortOrder());
                scanList.setString(3, row.name());
                scanList.setString(4, row.description());
                scanList.setInt(5, row.published() ? 1 : 0);
                scanList.setInt(6, row.isDefault() ? 1 : 0);
                scanList.executeUpdate();
                if(row.isDefault())
                {
                    defaultScanListId = targetId;
                }
            }
            for(long id: input.aliases().aliasIds())
            {
                AliasOptionalFields fields = input.aliases().aliasOptionalFields().get(id);
                if(fields == null)
                {
                    throw new SQLException("Accepted Alias optional-field plan is missing");
                }
                AliasMatcherFields matcher = input.aliases().aliasMatchers().get(id);
                if(matcher == null)
                {
                    throw new SQLException("Accepted Alias matcher plan is missing");
                }
                alias.setString(1, input.aliases().aliasNames().get(id));
                alias.setString(2, fields.description());
                alias.setString(3, fields.groupName());
                alias.setInt(4, fields.color());
                alias.setString(5, fields.iconName());
                setInteger(alias, 6, fields.streamAsTalkgroup());
                alias.setInt(7, fields.recordEnabled() ? 1 : 0);
                alias.setString(8, matcher.matcherType());
                alias.setString(9, matcher.protocol());
                setInteger(alias, 10, matcher.value());
                setInteger(alias, 11, matcher.minimum());
                setInteger(alias, 12, matcher.maximum());
                alias.setString(13, matcher.textValue());
                setInteger(alias, 14, matcher.numericValue());
                alias.setString(15, matcher.toneSequence());
                alias.setLong(16, id);
                if(alias.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted Alias changed during migration");
                }
            }
            try(Statement memberships = connection.createStatement())
            {
                memberships.executeUpdate("""
                INSERT INTO alias_scan_list_membership (alias_id, scan_list_id)
                SELECT owner.id, scan_list.id
                FROM format14_alias_scan_list_membership membership
                JOIN alias owner ON owner.id=membership.alias_id
                JOIN scan_list scan_list ON scan_list.id=membership.scan_list_id
                """);
                memberships.executeUpdate("""
                INSERT INTO alias_list_unmatched_talkgroup_scan_list_membership (alias_list_id, scan_list_id)
                SELECT owner.id, scan_list.id
                FROM format14_alias_list_unmatched_talkgroup_scan_list_membership membership
                JOIN alias_list owner ON owner.id=membership.alias_list_id
                JOIN scan_list scan_list ON scan_list.id=membership.scan_list_id
                """);
            }
            insertRecoveredDefaultMemberships(connection, defaultScanListId,
                input.aliases().recoveredDefaultMemberships());
            for(WebUserRow row: input.users().rows())
            {
                user.setLong(1, row.id());
                user.setString(2, row.username());
                user.setString(3, row.tier());
                user.setInt(4, row.primary() ? 1 : 0);
                user.setInt(5, row.credentialVersion());
                user.setString(6, row.passwordAlgorithm());
                user.setInt(7, row.passwordIterations());
                user.setInt(8, row.passwordDerivedKeyBits());
                user.setBytes(9, row.passwordSalt());
                user.setBytes(10, row.passwordHash());
                user.setLong(11, row.passwordChangedAtMs());
                user.setLong(12, row.authRevision());
                user.setString(13, row.preferences());
                user.setLong(14, row.preferencesRevision());
                user.setLong(15, row.createdAtMs());
                user.setLong(16, row.updatedAtMs());
                user.executeUpdate();
            }
        }

        if(input.users().authenticationReset())
        {
            long updateTime = input.core().metadata().stream()
                .filter(row -> INITIAL_ADMIN_SETUP_KEY.equals(row.key()))
                .mapToLong(MetadataRow::updatedAtMs).findFirst().orElse(1);
            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO database_metadata(key, value, updated_at_ms) VALUES ('initial_admin_setup', 'required', ?)
                ON CONFLICT(key) DO UPDATE SET value='required', updated_at_ms=excluded.updated_at_ms
                """))
            {
                statement.setLong(1, updateTime);
                statement.executeUpdate();
            }
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

    private static void insertRecoveredDefaultMemberships(Connection connection, Long defaultScanListId,
                                                           GeneratedDefaultMembershipRecovery recovery)
        throws SQLException
    {
        if(recovery.sourceRows() == 0)
        {
            return;
        }
        if(defaultScanListId == null)
        {
            throw new SQLException("Recovered scan-list memberships require a Default scan list");
        }
        insertGeneratedDefaultMemberships(connection, "alias_scan_list_membership", "alias_id",
            recovery.aliasIds(), defaultScanListId);
        insertGeneratedDefaultMemberships(connection, "alias_list_unmatched_talkgroup_scan_list_membership",
            "alias_list_id", recovery.aliasListIds(), defaultScanListId);
    }

    private static void insertGeneratedDefaultMemberships(Connection connection, String table, String ownerColumn,
                                                            Set<Long> ownerIds, long generatedScanListId)
        throws SQLException
    {
        try(PreparedStatement insert = connection.prepareStatement(
            "INSERT OR IGNORE INTO " + identifier(table) + "(" + identifier(ownerColumn) +
                ", scan_list_id) VALUES (?, ?)"))
        {
            for(long ownerId: ownerIds)
            {
                insert.setLong(1, ownerId);
                insert.setLong(2, generatedScanListId);
                insert.addBatch();
            }
            insert.executeBatch();
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

    private static long deriveMetricBoundary(Connection connection) throws SQLException
    {
        long latest = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB))<=64
                        THEN value END AS value,
                   CASE WHEN typeof(updated_at_ms)='integer' THEN updated_at_ms END AS updated_at_ms
            FROM database_metadata WHERE key=?
            """))
        {
            for(String key: REPLACED_METRIC_BOUNDARIES)
            {
                statement.setString(1, key);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        Long updatedAt = safePositiveLong(rows, "updated_at_ms");
                        if(updatedAt != null && updatedAt < Long.MAX_VALUE - 1)
                        {
                            latest = Math.max(latest, updatedAt);
                        }
                        String value = safeText(rows, "value");
                        if(value != null)
                        {
                            try
                            {
                                long parsed = Long.parseLong(value);
                                if(parsed > 0 && parsed < Long.MAX_VALUE - 1)
                                {
                                    latest = Math.max(latest, parsed);
                                }
                            }
                            catch(NumberFormatException exception)
                            {
                                //A corrupt retired boundary does not block the deterministic replacement boundary.
                            }
                        }
                    }
                }
            }
        }
        return latest > 0 ? latest + 1 : 1;
    }

    private static void seedMetricBoundaries(Connection connection, long boundary) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            for(String key: List.of(CONVENTIONAL_CALL_BOUNDARY, TRUNKED_CALL_BOUNDARY, RADIO_SYSTEM_BOUNDARY))
            {
                statement.setString(1, key);
                statement.setString(2, Long.toString(boundary));
                statement.setLong(3, boundary);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<String> replacedMetadataKeys()
    {
        List<String> keys = new ArrayList<>(SUBSYSTEM_VERSION_KEYS.size() + REPLACED_METRIC_BOUNDARIES.size());
        keys.addAll(SUBSYSTEM_VERSION_KEYS);
        keys.addAll(REPLACED_METRIC_BOUNDARIES);
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

    private static long sumCounts(long... counts)
    {
        long total = 0;
        for(long count: counts)
        {
            if(count < 0)
            {
                throw new IllegalArgumentException("Migration effect count cannot be negative");
            }
            total = Math.addExact(total, count);
        }
        return total;
    }

    private static long countMetadataRows(Connection connection, Collection<String> keys) throws SQLException
    {
        long total = 0;
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM database_metadata WHERE key=?"))
        {
            for(String key: keys)
            {
                statement.setString(1, key);
                try(ResultSet rows = statement.executeQuery())
                {
                    if(!rows.next())
                    {
                        throw new SQLException("Unable to count database metadata " + key);
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

    private static void requireTypedChannelComponent(ObjectNode payload, String property, String label)
        throws IOException
    {
        JsonNode component = payload.get(property);
        if(component == null || !component.isObject() || !component.hasNonNull("type") ||
            !component.get("type").isTextual() || component.get("type").textValue().isBlank())
        {
            throw new IOException(label + " has no supported " + property);
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

    private static String safeText(ResultSet rows, String column) throws SQLException
    {
        Object value = rows.getObject(column);
        return value instanceof String text ? text : null;
    }

    private static boolean storedInvalidBoundedText(ResultSet rows, String column, int maximumBytes)
        throws SQLException
    {
        String type = rows.getString(column + "_type");
        if("null".equals(type))
        {
            return false;
        }
        if(!"text".equals(type))
        {
            return true;
        }
        long bytes = rows.getLong(column + "_bytes");
        return rows.wasNull() || bytes > maximumBytes;
    }

    private static Long safeLong(ResultSet rows, String column) throws SQLException
    {
        Object value = rows.getObject(column);
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
        {
            return ((Number)value).longValue();
        }
        return null;
    }

    private static Long safePositiveLong(ResultSet rows, String column) throws SQLException
    {
        Long value = safeLong(rows, column);
        return value != null && value > 0 ? value : null;
    }

    private static Integer safeInteger(ResultSet rows, String column) throws SQLException
    {
        Long value = safeLong(rows, column);
        return value != null && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? value.intValue() : null;
    }

    private static Boolean safeBoolean(ResultSet rows, String column) throws SQLException
    {
        Integer value = safeInteger(rows, column);
        return value != null && (value == 0 || value == 1) ? value == 1 : null;
    }

    private static byte[] safeBlob(ResultSet rows, String column) throws SQLException
    {
        Object value = rows.getObject(column);
        return value instanceof byte[] bytes ? bytes.clone() : null;
    }

    private static String inferLegacyDecoderType(ObjectNode payload, String storedDecoderType)
    {
        JsonNode decoder = payload.get("decodeConfiguration");
        JsonNode discriminator = decoder != null && decoder.isObject() ? decoder.get("type") : null;
        if(discriminator != null && discriminator.isTextual())
        {
            String type = discriminator.textValue().toUpperCase(Locale.ROOT);
            if(type.contains("NXDN"))
            {
                return "NXDN";
            }
            if(type.contains("DMR"))
            {
                return "DMR";
            }
        }
        return storedDecoderType;
    }

    private static String tryCanonicalUuid(String value)
    {
        if(value == null)
        {
            return null;
        }
        try
        {
            return UUID.fromString(value.strip()).toString();
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static String tryCanonicalUuid(JsonNode value)
    {
        if(value == null || !value.isTextual())
        {
            return null;
        }
        try
        {
            return UUID.fromString(value.textValue().strip()).toString();
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            return null;
        }
    }

    private static String deterministicBroadcastId(long rowId, Set<String> unavailable)
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
        throw new IllegalArgumentException(
            "Unable to allocate a unique stable ID for broadcast provider database row " + rowId);
    }

    private static String deterministicChannelId(long rowId, Set<String> unavailable)
    {
        for(int attempt = 0; attempt < Integer.MAX_VALUE; attempt++)
        {
            String seed = "sdrtrunk-vce:format-14:saved-channel:" + rowId +
                (attempt == 0 ? "" : ":" + attempt);
            String candidate = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
            if(!unavailable.contains(candidate))
            {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unable to allocate a unique stable ID for saved channel database row " +
            rowId);
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
        if(value <= 0 || value >= SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM)
        {
            throw new IOException(label + " has an unusable " + column);
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

    private static String requiredBoundedStoredText(ResultSet rows, String column, String label, int maximumBytes)
        throws SQLException, IOException
    {
        String value = optionalBoundedStoredText(rows, column, label, maximumBytes);
        if(value == null)
        {
            throw new IOException(label + " cannot be null");
        }
        return value;
    }

    private static String optionalBoundedStoredText(ResultSet rows, String column, String label, int maximumBytes)
        throws SQLException, IOException
    {
        String type = rows.getString(column + "_type");
        if("null".equals(type))
        {
            return null;
        }
        long bytes = rows.getLong(column + "_bytes");
        boolean bytesMissing = rows.wasNull();
        Object value = rows.getObject(column);
        if(!"text".equals(type) || bytesMissing || bytes > maximumBytes || !(value instanceof String text))
        {
            throw new IOException(label + " exceeds its supported text storage bound");
        }
        return text;
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

    private record BroadcastRow(long id, String configurationId, int sortOrder, String name,
                                Set<String> routeNames, String payload)
    {
    }

    private record BroadcastCandidate(long id, String candidateConfigurationId, int sortOrder, String name,
                                      Set<String> routeNames, String payload, boolean repaired)
    {
    }

    private record AliasRoute(long id, long aliasId, String broadcastConfigurationId)
    {
    }

    private record UnmatchedRoute(long id, long aliasListId, String broadcastConfigurationId)
    {
    }

    private record ResolvedRouteTarget(long ownerId, String broadcastConfigurationId)
    {
    }

    private record RelationshipInspection<T>(List<T> rows, long orphanedRows)
    {
    }

    private record RowInspection<T>(List<T> rows, long relationshipsDropped, long droppedRows, long defaultedRows)
    {
    }

    private record PolicyRow(String capabilityId, String tier, long updatedAtMs)
    {
    }

    private record PolicyInput(List<PolicyRow> rows, int transformedRows, long droppedRows, long sourceRows,
                               long defaultedRows)
    {
    }

    private record MetadataRow(String key, String value, long updatedAtMs)
    {
    }

    private record SettingRow(String key, String payload, long updatedAtMs)
    {
    }

    private record IconRow(String key, String payload, long updatedAtMs)
    {
    }

    private record PortablePreferenceRepair(String payload, boolean transformed, boolean defaulted, boolean drop)
    {
    }

    private record CoreInspection(List<MetadataRow> metadata, List<SettingRow> settings, List<IconRow> icons,
                                  long droppedRows, long defaultedRows, long portablePreferencesTransformed,
                                  long preservedRows)
    {
    }

    private record ScanListRow(Long id, int sortOrder, String name, String description, boolean published,
                               boolean isDefault)
    {
        private ScanListRow withName(String preparedName)
        {
            return new ScanListRow(id, sortOrder, preparedName, description, published, isDefault);
        }

        private ScanListRow withDefault()
        {
            return new ScanListRow(id, sortOrder, name, description, true, true);
        }

        private ScanListRow withoutDefault()
        {
            return new ScanListRow(id, sortOrder, name, description, published, false);
        }
    }

    private record AliasAdminIndex(Set<Long> aliasListIds, Set<Long> aliasIds,
                                   Map<String,List<Long>> aliasListsByExactName,
                                   Map<String,List<Long>> aliasListsByName,
                                   Map<Long,AliasListFamily> aliasListFamilies,
                                   Map<Long,String> aliasListNames, Map<Long,Boolean> aliasListRecordPolicies,
                                   Map<Long,String> aliasNames, Map<Long,AliasMatcherFields> aliasMatchers,
                                   Map<Long,AliasOptionalFields> aliasOptionalFields, List<ScanListRow> scanLists,
                                   long orphanedRows, long droppedRows, long defaultedRows,
                                   long defaultedAliasRows, long preservedRows,
                                   GeneratedDefaultMembershipRecovery recoveredDefaultMemberships)
    {
    }

    private record AliasOptionalFields(String description, String groupName, int color, String iconName,
                                       Integer streamAsTalkgroup, boolean recordEnabled, long defaultedFieldCount)
    {
    }

    record AliasMatcherFields(String matcherType, String protocol, Integer value, Integer minimum,
                              Integer maximum, String textValue, Integer numericValue, String toneSequence,
                              long defaultedFieldCount)
    {
    }

    private record GeneratedDefaultMembershipRecovery(Set<Long> aliasIds, Set<Long> aliasListIds, long sourceRows)
    {
        private static GeneratedDefaultMembershipRecovery none()
        {
            return new GeneratedDefaultMembershipRecovery(Set.of(), Set.of(), 0);
        }
    }

    private record WebUserRow(long id, String username, String tier, boolean primary, int credentialVersion,
                              String passwordAlgorithm, int passwordIterations, int passwordDerivedKeyBits,
                              byte[] passwordSalt, byte[] passwordHash, long passwordChangedAtMs,
                              long authRevision, String preferences, long preferencesRevision,
                              long createdAtMs, long updatedAtMs, boolean preferenceTransformed,
                              boolean preferenceDefaulted)
    {
    }

    private record UserInspection(List<WebUserRow> rows, long droppedRows, long defaultedRows,
                                  long transformedPreferences, long authenticationResetRows,
                                  boolean authenticationReset)
    {
        private UserInspection withAdditionalAuthenticationResetRows(long rows)
        {
            return new UserInspection(this.rows, droppedRows, defaultedRows, transformedPreferences,
                Math.addExact(authenticationResetRows, rows), authenticationReset);
        }
    }

    private record SequenceInspection(Map<String,Long> values, long defaultedRows)
    {
    }

    private record MigrationInput(CoreInspection core, AliasAdminIndex aliases,
                                  List<ChannelRow> channels, List<BroadcastRow> broadcasts,
                                  List<AliasRoute> aliasRoutes, List<UnmatchedRoute> unmatchedRoutes,
                                  UserInspection users, PolicyInput policy, Map<String,Long> sequences,
                                  long callHistoryRows,
                                  long siteRows, long qualityRows, long identityRows, long metricBoundaryRows,
                                  long newMetricBoundary,
                                  long retiredNamedChannelMapRows, long orphanedRelationshipRows,
                                  long droppedChannelRows, long defaultedChannelRows,
                                  long droppedBroadcastRows, long defaultedBroadcastRows,
                                  long defaultedSequenceRows, long preservedRows,
                                  long redundantMetadataRows)
    {
    }
}

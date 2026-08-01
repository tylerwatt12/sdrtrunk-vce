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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one bundled public-release transition from v0.6.2-alpha-7 to the current main database.
 *
 * <p>This class has no command-line entry point and never opens or copies a database. The Application Migrator owns
 * the immutable backup, staged copy, transaction, validation and promotion boundaries. Intermediate development
 * schema combinations are deliberately not represented as accepted source states.</p>
 */
final class Alpha7DatabaseMigration
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Set<String> RETIRED_DECODER_TYPES = Set.of("AM", "LTR", "LTR_NET", "PASSPORT");
    private static final Set<String> ACTIVE_DECODER_TYPES = Set.of(
        "DMR", "NBFM", "NXDN", "P25_CONVENTIONAL", "P25_PHASE1", "P25_PHASE2");
    private static final Set<String> ACTIVE_SOURCE_TYPES = Set.of(
        "TUNER", "TUNER_MULTIPLE_FREQUENCIES", "RECORDING");
    private static final Map<String,String> RETIRED_DECODER_JSON_TYPES = Map.of(
        "AM", "decodeConfigAM",
        "LTR", "decodeConfigLTRStandard",
        "LTR_NET", "decodeConfigLTRNet",
        "PASSPORT", "decodeConfigPassport");
    private static final Map<String,String> SOURCE_JSON_TYPES = Map.of(
        "NONE", "sourceConfigNone",
        "MIXER", "sourceConfigMixer",
        "TUNER", "sourceConfigTuner",
        "TUNER_MULTIPLE_FREQUENCIES", "sourceConfigTunerMultipleFrequency",
        "RECORDING", "sourceConfigRecording");
    private static final Set<String> PUBLISHED_ALPHA_7_SCHEMA_FINGERPRINTS = Set.of(
        //Fresh Alpha 7 database.
        "2f77565df91e55b5570b17ca60f2fa20c66085819af337b6684c3b6e3f6d72e0",
        //Database upgraded sequentially through Alpha 7.
        "e40a4d17a2b517bb65bc726777db500be7bdb7e498cb3b5565633626610d7a7b");
    private static final List<String> ALPHA_7_ACTIVITY_TABLES = List.of(
        "p25_talkgroup_summary", "p25_radio_summary", "p25_radio_talkgroup_summary",
        "p25_radio_affiliation", "p25_site_frequency_summary", "p25_site_talkgroup_bucket",
        "p25_site_activity_bucket", "conventional_activity_summary", "conventional_activity_bucket",
        "p25_activity_event", "receiver_context", "p25_system", "p25_site_channel_tag_summary",
        "p25_site_channel_tag", "p25_site_frequency_band_summary", "p25_site_frequency_band",
        "p25_foreign_system_band_summary", "p25_foreign_system_band", "p25_site_neighbor_summary",
        "p25_site_neighbor", "p25_site_patch_group_talkgroup_summary", "p25_site_patch_group_talkgroup",
        "p25_site_patch_group_radio_summary", "p25_site_patch_group_radio", "p25_site_patch_group_summary",
        "p25_site_patch_group", "p25_site_channel_summary", "p25_site_channel", "p25_site_snapshot",
        "p25_control_channel_quality", "logger_status", "trunked_site_neighbor_summary",
        "trunked_site_channel_summary", "trunked_site_snapshot");

    private Alpha7DatabaseMigration()
    {
    }

    static void validateSource(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
        requirePublishedAlpha7SchemaFingerprint(connection);
        validateConfigurationJsonAndModes(connection, true);
        Alpha7AliasMigration.validateSourceData(connection);
    }

    private static void requirePublishedAlpha7SchemaFingerprint(Connection connection) throws SQLException
    {
        String fingerprint = SqliteSchemaValidator.fingerprint(connection);
        if(!PUBLISHED_ALPHA_7_SCHEMA_FINGERPRINTS.contains(fingerprint))
        {
            throw new SQLException("Database schema is not an exact published Alpha 7 layout (" + fingerprint +
                ")");
        }
    }


    static String migrate(Connection connection) throws SQLException
    {
        long sourceChannelCount = count(connection, "configuration_channel");
        ConfigurationCounts configuration = migrateConfiguration(connection);
        Alpha7AliasMigration.Result aliases = Alpha7AliasMigration.migrate(connection);
        resetActivitySchemas(connection);
        validateConfigurationJsonAndModes(connection, false);

        long expectedChannels = sourceChannelCount - aliases.removedRetiredChannels();
        if(count(connection, "configuration_channel") != expectedChannels)
        {
            throw new SQLException("Configuration channel row count changed outside the planned retirement set");
        }

        String summary = "Alpha 7 migration: aliases=" + aliases.targetAliases() + ", alias lists=" +
            aliases.aliasLists() + ", DMR conventional channels=" + configuration.modes().dmrConventional() +
            ", DMR trunked channels=" + configuration.modes().dmrTrunked() +
            ", NXDN trunked channels=" + configuration.modes().nxdnTrunked() +
            "; activity, statistics, site observations, identities, affiliations, and quality history reset; " +
            "new activity starts empty";

        List<String> plannedChanges = new ArrayList<>();
        addNonzero(plannedChanges, aliases.removedRetiredChannels(), "retired channels removed");
        addNonzero(plannedChanges, configuration.removedStreams(), "retired streams removed");
        addNonzero(plannedChanges, aliases.discardedActions(), "alias actions removed");
        addNonzero(plannedChanges, aliases.removedNonRecordableFlags(),
            "non-recordable alias flags removed");
        addNonzero(plannedChanges, aliases.discardedLegacyMatcherDetailFields(),
            "legacy matcher detail fields removed");
        addNonzero(plannedChanges, aliases.collapsedDuplicateMatchers(),
            "duplicate matcher rows collapsed");
        addNonzero(plannedChanges, aliases.collapsedDuplicateRoutes(),
            "duplicate broadcast-route rows collapsed");
        addNonzero(plannedChanges, aliases.skippedBroadcastRoutes(), "broadcast routes skipped");
        addNonzero(plannedChanges, aliases.skippedAliases(), "source aliases skipped");
        addNonzero(plannedChanges, aliases.skippedMatcherlessAliases(), "matcherless aliases skipped");
        addNonzero(plannedChanges, aliases.skippedInvalidMatchers(), "invalid matcher rows skipped");
        addNonzero(plannedChanges, aliases.skippedRetiredMatchers(), "retired matcher rows skipped");
        addNonzero(plannedChanges, aliases.skippedUntypedMatchers(), "untyped matcher rows skipped");
        addNonzero(plannedChanges, aliases.skippedIncompatibleMatchers(),
            "incompatible matcher rows skipped");

        if(!plannedChanges.isEmpty())
        {
            summary += "; planned removals/collapses: " + String.join(", ", plannedChanges);
        }
        if(aliases.detachedUnsupportedChannels() > 0)
        {
            summary += "; unsupported compatibility channels retained unchanged=" +
                aliases.detachedUnsupportedChannels();
        }
        return summary + ".";
    }

    /**
     * Alpha 7 activity is intentionally not a supported release-migration input. Replace every P25, conventional,
     * DMR, NXDN, site-observation, identity, affiliation, quality, and statistics object with the exact empty current
     * schemas while this staged database transaction is still rollback-safe.
     */
    private static void resetActivitySchemas(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW p25_activity_event_resolved");
            for(String table: ALPHA_7_ACTIVITY_TABLES)
            {
                statement.executeUpdate("DROP TABLE " + table);
            }
        }

        P25ActivityLogSchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        P25ActivityLogSchema.validate(connection);
        DmrActivitySchema.validate(connection);
        TrunkedSiteSchema.validate(connection);
    }

    private static void addNonzero(List<String> values, long count, String label)
    {
        if(count > 0)
        {
            values.add(label + '=' + count);
        }
    }

    private static ConfigurationCounts migrateConfiguration(Connection connection) throws SQLException
    {
        int dmrConventional = 0;
        int dmrTrunked = 0;
        int nxdnTrunked = 0;
        Set<String> channelIdentities = new LinkedHashSet<>();

        try(PreparedStatement select = connection.prepareStatement("""
                SELECT id, decoder_type, source_type, config_json
                FROM configuration_channel
                ORDER BY id
                """);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE configuration_channel SET decoder_type=?, source_type=?, config_json=? WHERE id=?
                """);
            ResultSet resultSet = select.executeQuery())
        {
            while(resultSet.next())
            {
                String decoder = normalized(resultSet.getString("decoder_type"));
                String cachedSource = resultSet.getString("source_type");
                String source = normalized(cachedSource);
                if(RETIRED_DECODER_TYPES.contains(decoder))
                {
                    validateRetiredConfigurationChannel(resultSet.getLong("id"), decoder, source,
                        resultSet.getString("config_json"));
                    //These four decoder rows are deleted later with their retired aliases.
                    continue;
                }
                if(ChannelConfigurationPolicy.isRetiredPersisted(decoder, source))
                {
                    validateCompatibilityChannel(resultSet.getLong("id"), decoder, source,
                        resultSet.getString("config_json"));
                    continue;
                }

                long id = resultSet.getLong("id");
                ObjectNode channel = parseObject("configuration_channel", id, resultSet.getString("config_json"));
                Channel parsed = parseChannel(id, channel);
                source = validateActiveChannelTypes(id, decoder, cachedSource, channel, parsed, true);
                if(source == null)
                {
                    ObjectNode sourceConfiguration = OBJECT_MAPPER.createObjectNode();
                    sourceConfiguration.put("type", "sourceConfigNone");
                    channel.set("sourceConfiguration", sourceConfiguration);
                    source = "NONE";
                }

                ObjectNode decode = decodeConfiguration(id, channel, true);
                String type = decode.path("type").asText();
                if("decodeConfigDMR".equals(type))
                {
                    JsonNode existingMode = decode.get("channelMode");
                    validateMode(id, existingMode, true, false);
                    String mode = existingMode != null && existingMode.isTextual() ? existingMode.textValue() :
                        hasValidDmrFrequencyMap(decode) ? "TRUNKED" : "CONVENTIONAL";
                    decode.put("channelMode", mode);
                    if("TRUNKED".equals(mode))
                    {
                        dmrTrunked++;
                    }
                    else
                    {
                        dmrConventional++;
                    }
                }
                else if("decodeConfigNXDN".equals(type))
                {
                    decode.put("channelMode", "TRUNKED");
                    nxdnTrunked++;
                }

                String identity = parsed.getConfigurationId();
                while(!channelIdentities.add(identity))
                {
                    parsed.regenerateConfigurationId();
                    identity = parsed.getConfigurationId();
                }
                channel.put("configurationId", identity);

                update.setString(1, decoder);
                update.setString(2, source);
                try
                {
                    update.setString(3, OBJECT_MAPPER.writeValueAsString(channel));
                }
                catch(IOException e)
                {
                    throw new SQLException("Channel row [" + id + "] could not be serialized", e);
                }
                update.setLong(4, id);
                if(update.executeUpdate() != 1)
                {
                    throw new SQLException("Channel row [" + id + "] changed during migration");
                }
            }
        }

        int removedStreams = migrateBroadcastConfigurationIdentities(connection);
        return new ConfigurationCounts(new ModeCounts(dmrConventional, dmrTrunked, nxdnTrunked), removedStreams);
    }

    private static int migrateBroadcastConfigurationIdentities(Connection connection) throws SQLException
    {
        int removed = 0;
        Set<String> identities = new LinkedHashSet<>();
        try(PreparedStatement select = connection.prepareStatement("""
                SELECT id, server_type, config_json
                FROM configuration_broadcast_stream
                ORDER BY id
                """);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE configuration_broadcast_stream SET server_type=?, config_json=? WHERE id=?
                """);
            PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM configuration_broadcast_stream WHERE id=?");
            ResultSet resultSet = select.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String serverType = normalized(resultSet.getString("server_type"));
                ObjectNode json = parseObject("configuration_broadcast_stream", id,
                    resultSet.getString("config_json"));
                if(isRetiredShoutcastV2(id, serverType, json))
                {
                    delete.setLong(1, id);
                    if(delete.executeUpdate() != 1)
                    {
                        throw new SQLException("Retired Shoutcast v2 stream row [" + id + "] changed");
                    }
                    removed++;
                    continue;
                }
                if(serverType.isBlank())
                {
                    throw new SQLException("Broadcast stream row [" + id + "] has no cached server type");
                }

                BroadcastConfiguration parsed = parseBroadcastConfiguration(id, json);
                String jsonServerType = broadcastServerType(id, parsed);
                if(!serverType.isBlank() && !serverType.equals(jsonServerType))
                {
                    throw new SQLException("Broadcast stream row [" + id +
                        "] cached server type does not match its configuration JSON");
                }

                String identity = parsed.getConfigurationId();
                while(!identities.add(identity))
                {
                    parsed.regenerateConfigurationId();
                    identity = parsed.getConfigurationId();
                }
                json.put("configurationId", identity);
                update.setString(1, jsonServerType);
                try
                {
                    update.setString(2, OBJECT_MAPPER.writeValueAsString(json));
                }
                catch(IOException e)
                {
                    throw new SQLException("Broadcast stream row [" + id + "] could not be serialized", e);
                }
                update.setLong(3, id);
                if(update.executeUpdate() != 1)
                {
                    throw new SQLException("Broadcast stream row [" + id + "] changed during migration");
                }
            }
        }
        return removed;
    }

    private static void validateConfigurationJsonAndModes(Connection connection, boolean alpha7Source)
        throws SQLException
    {
        Set<String> channelIdentities = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, decoder_type, source_type, config_json
                FROM configuration_channel
                ORDER BY id
                """))
        {
            while(resultSet.next())
            {
                String decoder = normalized(resultSet.getString("decoder_type"));
                String cachedSource = resultSet.getString("source_type");
                String source = normalized(cachedSource);
                if(RETIRED_DECODER_TYPES.contains(decoder))
                {
                    validateRetiredConfigurationChannel(resultSet.getLong("id"), decoder, source,
                        resultSet.getString("config_json"));
                    continue;
                }
                if(ChannelConfigurationPolicy.isRetiredPersisted(decoder, source))
                {
                    validateCompatibilityChannel(resultSet.getLong("id"), decoder, source,
                        resultSet.getString("config_json"));
                    continue;
                }
                long id = resultSet.getLong("id");
                ObjectNode channel = parseObject("configuration_channel", id, resultSet.getString("config_json"));
                Channel parsed = parseChannel(id, channel);
                source = validateActiveChannelTypes(id, decoder, cachedSource, channel, parsed, alpha7Source);
                ObjectNode decode = decodeConfiguration(id, channel, true);
                String type = decode.path("type").asText();
                JsonNode mode = decode.get("channelMode");
                if("DMR".equals(decoder))
                {
                    if(!"decodeConfigDMR".equals(type))
                    {
                        throw new SQLException("DMR channel row [" + id + "] has the wrong decode configuration");
                    }
                    validateMode(id, mode, alpha7Source, false);
                }
                else if("NXDN".equals(decoder))
                {
                    if(!"decodeConfigNXDN".equals(type))
                    {
                        throw new SQLException("NXDN channel row [" + id + "] has the wrong decode configuration");
                    }
                    validateMode(id, mode, alpha7Source, true);
                }
                if(!alpha7Source && (parsed.isConfigurationIdPersistenceRequired() ||
                    !channelIdentities.add(parsed.getConfigurationId())))
                {
                    throw new SQLException("Migrated channel row [" + id +
                        "] has a missing, malformed, or duplicate configurationId");
                }
            }
        }

        Set<String> streamIdentities = new LinkedHashSet<>();
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT id, server_type, config_json FROM configuration_broadcast_stream ORDER BY id
                """))
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String serverType = normalized(resultSet.getString("server_type"));
                ObjectNode json = parseObject("configuration_broadcast_stream", id,
                    resultSet.getString("config_json"));
                if(isRetiredShoutcastV2(id, serverType, json))
                {
                    if(!alpha7Source)
                    {
                        throw new SQLException("Migrated database retains Shoutcast v2 stream row [" + id + ']');
                    }
                    continue;
                }
                if(serverType.isBlank())
                {
                    throw new SQLException("Broadcast stream row [" + id + "] has no cached server type");
                }
                BroadcastConfiguration parsed = parseBroadcastConfiguration(id, json);
                String jsonServerType = broadcastServerType(id, parsed);
                if(!serverType.isBlank() && !serverType.equals(jsonServerType))
                {
                    throw new SQLException("Broadcast stream row [" + id +
                        "] cached server type does not match its configuration JSON");
                }
                if(!alpha7Source && (parsed.isConfigurationIdPersistenceRequired() ||
                    !streamIdentities.add(parsed.getConfigurationId())))
                {
                    throw new SQLException("Migrated broadcast stream row [" + id +
                        "] has a missing, malformed, or duplicate configurationId");
                }
            }
        }
    }

    /**
     * Validates the duplicated channel type facts and returns the authoritative source type. A newly-created Alpha 7
     * channel could be persisted after its decoder was selected but before the editor supplied a source. Preserve
     * only that exact incomplete state: the decoder must be a recognized active type and both source facts must be
     * absent. The runtime policy keeps the resulting channel non-runnable until the user supplies a source.
     */
    private static String validateActiveChannelTypes(long id, String decoder, String cachedSource,
                                                     ObjectNode channel, Channel parsed, boolean alpha7Source)
        throws SQLException
    {
        String jsonDecoder = channelDecoderType(id, parsed);
        requireKnownActiveDecoder(id, decoder);
        requireKnownActiveDecoder(id, jsonDecoder);
        if(!decoder.equals(jsonDecoder))
        {
            throw cachedChannelTypeMismatch(id);
        }

        JsonNode sourceNode = channel.get("sourceConfiguration");
        boolean jsonSourceAbsent = sourceNode == null || sourceNode.isNull();
        if(alpha7Source && cachedSource == null && jsonSourceAbsent)
        {
            return null;
        }

        if(cachedSource == null || jsonSourceAbsent)
        {
            throw cachedChannelTypeMismatch(id);
        }

        String source = normalized(cachedSource);
        String jsonSource = channelSourceType(id, parsed);
        if("NONE".equals(source) || "NONE".equals(jsonSource))
        {
            if(source.equals(jsonSource))
            {
                return source;
            }
            throw cachedChannelTypeMismatch(id);
        }
        requireKnownActiveSource(id, source);
        requireKnownActiveSource(id, jsonSource);
        if(!source.equals(jsonSource))
        {
            throw cachedChannelTypeMismatch(id);
        }
        return jsonSource;
    }

    private static void requireKnownActiveDecoder(long id, String decoder) throws SQLException
    {
        if(decoder.isBlank() || !ACTIVE_DECODER_TYPES.contains(decoder))
        {
            throw new SQLException("Channel row [" + id + "] has unsupported decoder type [" + decoder + "]");
        }
    }

    private static void requireKnownActiveSource(long id, String source) throws SQLException
    {
        if(source.isBlank() || !ACTIVE_SOURCE_TYPES.contains(source))
        {
            throw new SQLException("Channel row [" + id + "] has unsupported source type [" + source + "]");
        }
    }

    private static SQLException cachedChannelTypeMismatch(long id)
    {
        return new SQLException("Channel row [" + id + "] cached decoder/source types do not match " +
            "its configuration JSON");
    }

    private static void validateCompatibilityChannel(long id, String decoder, String source, String json)
        throws SQLException
    {
        ObjectNode object = parseObject("configuration_channel", id, json);
        Channel parsed = parseChannel(id, object);
        String jsonDecoder = channelDecoderType(id, parsed);
        String jsonSource = channelSourceType(id, parsed);
        if(!decoder.equals(jsonDecoder) || !source.equals(jsonSource))
        {
            throw new SQLException("Compatibility channel row [" + id +
                "] cached decoder/source types do not match its configuration JSON");
        }
        if(!"MPT1327".equals(jsonDecoder) && !"MIXER".equals(jsonSource))
        {
            throw new SQLException("Channel row [" + id + "] is not a recognized compatibility row");
        }
    }

    private static void validateRetiredConfigurationChannel(long id, String decoder, String source, String json)
        throws SQLException
    {
        String expectedDecoderType = RETIRED_DECODER_JSON_TYPES.get(decoder);
        String expectedSourceType = SOURCE_JSON_TYPES.get(source);
        ObjectNode channel = parseObject("configuration_channel", id, json);
        ObjectNode decode = decodeConfiguration(id, channel, true);
        JsonNode sourceConfiguration = channel.get("sourceConfiguration");
        String jsonDecoderType = decode.path("type").asText();
        String jsonSourceType = sourceConfiguration != null && sourceConfiguration.isObject() ?
            sourceConfiguration.path("type").asText() : "";

        if(expectedDecoderType == null || !expectedDecoderType.equals(jsonDecoderType) ||
            expectedSourceType == null || !expectedSourceType.equals(jsonSourceType))
        {
            throw new SQLException("Retired channel row [" + id +
                "] cached decoder/source types do not match its configuration JSON");
        }
    }

    private static String channelDecoderType(long id, Channel channel) throws SQLException
    {
        if(channel.getDecodeConfiguration() == null || channel.getDecodeConfiguration().getDecoderType() == null)
        {
            throw new SQLException("Channel row [" + id + "] has no valid decoder type in its configuration JSON");
        }
        return channel.getDecodeConfiguration().getDecoderType().name();
    }

    private static String channelSourceType(long id, Channel channel) throws SQLException
    {
        if(channel.getSourceConfiguration() == null || channel.getSourceConfiguration().getSourceType() == null)
        {
            throw new SQLException("Channel row [" + id + "] has no valid source type in its configuration JSON");
        }
        return channel.getSourceConfiguration().getSourceType().name();
    }

    private static String broadcastServerType(long id, BroadcastConfiguration configuration) throws SQLException
    {
        if(configuration.getBroadcastServerType() == null)
        {
            throw new SQLException("Broadcast stream row [" + id +
                "] has no valid server type in its configuration JSON");
        }
        return configuration.getBroadcastServerType().name();
    }

    private static Channel parseChannel(long id, ObjectNode json) throws SQLException
    {
        try
        {
            return OBJECT_MAPPER.treeToValue(json, Channel.class);
        }
        catch(IOException | RuntimeException e)
        {
            throw new SQLException("Channel row [" + id + "] cannot be loaded by the current channel model", e);
        }
    }

    private static BroadcastConfiguration parseBroadcastConfiguration(long id, ObjectNode json) throws SQLException
    {
        try
        {
            return OBJECT_MAPPER.treeToValue(json, BroadcastConfiguration.class);
        }
        catch(IOException | RuntimeException e)
        {
            throw new SQLException("Broadcast stream row [" + id +
                "] cannot be loaded by the current broadcast model", e);
        }
    }

    private static boolean isRetiredShoutcastV2(long id, String serverType, ObjectNode json) throws SQLException
    {
        boolean cached = "SHOUTCAST_V2".equals(serverType);
        boolean configured = "shoutcastV2Configuration".equalsIgnoreCase(json.path("type").asText());
        if(cached != configured)
        {
            throw new SQLException("Broadcast stream row [" + id +
                "] cached server type does not match its configuration JSON subtype");
        }
        return cached;
    }

    private static void validateMode(long id, JsonNode mode, boolean allowMissing, boolean nxdn)
        throws SQLException
    {
        if(mode == null || mode.isNull())
        {
            if(!allowMissing)
            {
                throw new SQLException("Channel row [" + id + "] has no explicit channelMode");
            }
            return;
        }
        if(!mode.isTextual() || nxdn && !"TRUNKED".equals(mode.textValue()) ||
            !nxdn && !Set.of("CONVENTIONAL", "TRUNKED").contains(mode.textValue()))
        {
            throw new SQLException("Channel row [" + id + "] has an invalid channelMode");
        }
    }

    private static ObjectNode decodeConfiguration(long id, ObjectNode channel, boolean required)
        throws SQLException
    {
        JsonNode value = channel.get("decodeConfiguration");
        if(value == null || value.isNull())
        {
            if(required)
            {
                throw new SQLException("Channel row [" + id + "] has no decodeConfiguration object");
            }
            return null;
        }
        if(value instanceof ObjectNode object)
        {
            return object;
        }
        throw new SQLException("Channel row [" + id + "] decodeConfiguration must be an object");
    }

    private static ObjectNode parseObject(String table, long id, String json) throws SQLException
    {
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            if(parsed instanceof ObjectNode object)
            {
                return object;
            }
        }
        catch(IOException | RuntimeException e)
        {
            throw new SQLException(table + " row [" + id + "] contains invalid JSON", e);
        }
        throw new SQLException(table + " row [" + id + "] JSON must be an object");
    }

    private static boolean hasValidDmrFrequencyMap(ObjectNode decode)
    {
        JsonNode mappings = decode.has("timeslotMap") ? decode.get("timeslotMap") : decode.get("timeslot");
        if(mappings == null || !mappings.isArray())
        {
            return false;
        }
        for(JsonNode mapping: mappings)
        {
            if(positiveIntegral(mapping, "number", "lsn") &&
                positiveIntegral(mapping, "downlinkFrequency", "downlink"))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean positiveIntegral(JsonNode object, String primary, String legacy)
    {
        if(object == null || !object.isObject())
        {
            return false;
        }
        JsonNode value = object.has(primary) ? object.get(primary) : object.get(legacy);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private static long count(Connection connection, String table) throws SQLException
    {
        return scalar(connection, "SELECT count(*) FROM " + table);
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            if(resultSet.next())
            {
                return resultSet.getLong(1);
            }
        }
        throw new SQLException("No result for migration query: " + sql);
    }

    private static String normalized(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ModeCounts(int dmrConventional, int dmrTrunked, int nxdnTrunked)
    {
    }

    private record ConfigurationCounts(ModeCounts modes, int removedStreams)
    {
    }
}

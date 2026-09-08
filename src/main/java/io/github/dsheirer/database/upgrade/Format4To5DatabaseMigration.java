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
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfigRecording;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebPasswordVerifier;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Normalizes format-4 web identity/settings state and active channel identity projections into strict format 5. */
final class Format4To5DatabaseMigration implements DatabaseMigrationStep
{
    private static final String ACCESS_KEY = "web.access.v1";
    private static final String DISPLAY_KEY = "web.display.v1";
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String NOW_PLAYING_NODE = "user/io/github/dsheirer/preference/nowplaying";
    private static final String SHOW_CONTROL_KEY = "show.control.decode.quality";
    private static final String SHOW_VOICE_KEY = "show.voice.decode.quality";
    private static final String DISPLAY_MODE_KEY = "decode.quality.display.mode";
    private static final String ROW_LIMIT_KEY = "live.detail.matching.row.limit";
    private static final String SITE_SETTINGS_REVISION_KEY = "site.settings.revision";
    private static final Set<String> MOVED_NOW_PLAYING_KEYS = Set.of(
        SHOW_CONTROL_KEY, SHOW_VOICE_KEY, DISPLAY_MODE_KEY, ROW_LIMIT_KEY);
    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
        "version", "username", "algorithm", "iterations", "derivedKeyBits", "saltBase64",
        "passwordHashBase64", "passwordChangedAtEpochMillis", "credentialVersion");
    private static final Set<String> STORED_USER_FIELDS = Set.of("tier", "credential");
    private static final Set<String> DISPLAY_FIELDS = Set.of("format_version", "show_encryption_details");
    private static final Set<String> LEGACY_CONFIGURABLE_CAPABILITIES = Set.of(
        "site-access", "dashboard", "live", "systems", "conventional", "credits", "csv-export", "call-audio");
    private static final Set<String> LEGACY_FIXED_CAPABILITIES = Set.of(
        "user-settings", "admin-users", "admin-access", "admin-aliases", "admin-settings", "receiver-health");
    private static final int MAXIMUM_LEGACY_ACCESS_BYTES = 1_048_576;
    private static final int MAXIMUM_PORTABLE_PREFERENCES_BYTES = 4_194_304;
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final ObjectMapper CHANNEL_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String id()
    {
        return "format-4-to-5";
    }

    @Override
    public String description()
    {
        return "Normalize web users, per-user preferences, access policy, and active saved channel identities";
    }

    @Override
    public int sourceVersion()
    {
        return 4;
    }

    @Override
    public int targetVersion()
    {
        return 5;
    }

    @Override
    public List<DatabaseMigrationEffect> declaredEffects()
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "saved channel identity scalars",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Project exact configuration UUID, channel kind, and query scalars from each active channel document"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired channel configurations",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Drop decoded unsupported channels and use exact legacy scalars only for opaque MPT-1327 or " +
                    "sound-card documents"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "unusable saved channel configurations",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Skip only saved channel rows whose identity or configuration document cannot be recovered"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "recoverable saved channel values",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Generate replacement identities and default only unusable optional channel subdocuments"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Move every verifier, role, and authentication revision into normalized web_user rows"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Move active configurable overrides into web_access_policy"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Seed every migrated account from the former shared browser presentation values"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "initial administrator browser preferences", DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Retain ownerless Alpha presentation values until setup creates the primary administrator"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision",
                1, "Seed the optimistic concurrency revision for receiver-wide settings"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Drop only the recognized aliases and tuner-spectrum legacy overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Remove web.access.v1, web.display.v1, and moved per-user Java preference fields"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET, "unusable legacy web/settings state",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Reset only malformed legacy authentication or browser settings while preserving receiver configuration"));
    }

    @Override
    public List<DatabaseMigrationEffect> validateSource(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);
        return effects(input);
    }

    @Override
    public void migrate(Connection connection) throws SQLException
    {
        MigrationInput input = inspect(connection);

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("ALTER TABLE configuration_channel RENAME TO configuration_channel_format4");
            Format5SchemaSql.createConfigurationChannel(statement);
        }

        copyChannels(connection, input.channels(), input.retiredChannelIds(), input.unusableChannelIds());

        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE configuration_channel_format4");
            Format5SchemaSql.createConfigurationIndexes(statement);
            Format5SchemaSql.createWebSettings(statement);
        }

        insertAccounts(connection, input.accounts(), input.preferencesJson());
        insertPolicies(connection, input.policies());
        updatePortablePreferences(connection, input.portablePreferences());
        deleteSetting(connection, ACCESS_KEY);
        if(!input.pendingInitialAdminPreferences())
        {
            deleteSetting(connection, DISPLAY_KEY);
        }
        setMetadata(connection, "configuration_schema_version", "3");
        setMetadata(connection, "settings_schema_version", "3");
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspectForMigration(connection);
        if(detected.version() != 4)
        {
            throw new SQLException("Migration step format-4-to-5 requires exact source format 4; found " +
                detected.version() + " [" + detected.id() + "]");
        }

        ChannelInspection channelInspection = inspectChannels(connection);
        Optional<String> accessStored = setting(connection, ACCESS_KEY);
        LegacyAccess access;
        try
        {
            access = parseAccess(accessStored);
        }
        catch(IOException | IllegalArgumentException ignored)
        {
            access = new LegacyAccess(List.of(), Map.of(), 0, accessStored.isPresent() ? 1 : 0);
        }

        Optional<String> displayStored = setting(connection, DISPLAY_KEY);
        LegacyPresentation presentation;
        try
        {
            presentation = parsePresentation(displayStored, setting(connection, PORTABLE_PREFERENCES_KEY),
                access.accounts().isEmpty());
        }
        catch(IOException | IllegalArgumentException exception)
        {
            throw new SQLException("Unable to create bounded replacement browser preferences", exception);
        }

        boolean pendingInitialAdminPreferences =
            access.accounts().isEmpty() && presentation.hasPersonalState();
        String preferencesJson;
        try
        {
            preferencesJson = Format6WebUserPreferencesCodec.defaults(
                presentation.showEncryptionDetails(), presentation.showControlDecodeQuality(),
                presentation.showVoiceDecodeQuality(), presentation.decodeQualityDisplayMode(),
                presentation.liveDetailRowLimit());
        }
        catch(IOException | IllegalArgumentException exception)
        {
            throw new SQLException("Unable to create bounded version-1 browser preferences", exception);
        }

        return new MigrationInput(channelInspection.activeChannels(), channelInspection.retiredChannelIds(),
            channelInspection.unusableChannelIds(), channelInspection.recoveredIdentityChannelIds(),
            access.accounts(), access.policies(), preferencesJson,
            presentation.portablePreferences(), access.retiredOverrideCount(), presentation.movedPreferenceCount(),
            accessStored.isPresent() ? 1 : 0, displayStored.isPresent() ? 1 : 0,
            pendingInitialAdminPreferences, access.resetRows() + presentation.resetRows());
    }

    private static ChannelInspection inspectChannels(Connection connection) throws SQLException
    {
        List<ActiveChannelRow> activeChannels = new ArrayList<>();
        Set<Long> retiredChannelIds = new HashSet<>();
        Set<Long> unusableChannelIds = new HashSet<>();
        Set<Long> recoveredIdentityChannelIds = new HashSet<>();
        Set<String> configurationIds = new HashSet<>();
        Map<String,Long> radresOwners = new HashMap<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                   frequency_count, recording_enabled, event_logging_enabled,
                   CASE WHEN typeof(system_name)='text'
                              AND length(CAST(system_name AS BLOB)) <= 4194304
                        THEN system_name END AS system_name,
                   CASE WHEN typeof(site_name)='text'
                              AND length(CAST(site_name AS BLOB)) <= 4194304
                        THEN site_name END AS site_name,
                   CASE WHEN typeof(name)='text'
                              AND length(CAST(name AS BLOB)) <= 4194304
                        THEN name END AS name,
                   CASE WHEN typeof(alias_list_name)='text'
                              AND length(CAST(alias_list_name AS BLOB)) <= 4194304
                        THEN alias_list_name END AS alias_list_name,
                   CASE WHEN typeof(config_json)='text'
                              AND length(CAST(config_json AS BLOB)) <= 4194304
                        THEN config_json END AS config_json,
                   CASE WHEN typeof(radres_guid)='text'
                              AND length(CAST(radres_guid AS BLOB)) <= 128
                        THEN radres_guid END AS radres_guid
            FROM configuration_channel
            ORDER BY id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String decoderType = resultSet.getString("decoder_type");
                String sourceType = resultSet.getString("source_type");
                try
                {
                    String json = resultSet.getString("config_json");
                    ObjectNode root = (ObjectNode)parseObject(json, "configuration_channel row " + id,
                        MAXIMUM_PORTABLE_PREFERENCES_BYTES);
                    if(!(root.get("decodeConfiguration") instanceof ObjectNode decoder) ||
                        !decoder.path("type").isTextual() ||
                        !(root.get("sourceConfiguration") instanceof ObjectNode source) ||
                        !source.path("type").isTextual())
                    {
                        throw new IOException("configuration_channel row " + id +
                            " has no classifiable decoder or source configuration");
                    }
                    JsonNode idNode = root.get("configurationId");
                    String storedConfigurationId = idNode != null && idNode.isTextual() ?
                        idNode.textValue() : null;
                    String configurationId = recoverableUuid(storedConfigurationId);
                    if(configurationId == null || configurationIds.contains(configurationId))
                    {
                        configurationId = deterministicUuid("configuration", id, configurationIds);
                        recoveredIdentityChannelIds.add(id);
                    }
                    else if(!java.util.Objects.equals(storedConfigurationId, configurationId))
                    {
                        recoveredIdentityChannelIds.add(id);
                    }
                    root.put("configurationId", configurationId);

                    ChannelConfigurationSalvage.Result channelSalvage =
                        ChannelConfigurationSalvage.decode(CHANNEL_MAPPER, root,
                            "configuration_channel row " + id);
                    Channel channel = channelSalvage.channel();
                    root = channelSalvage.payload();
                    if(channelSalvage.defaultedComponents() > 0)
                    {
                        recoveredIdentityChannelIds.add(id);
                    }
                    if(!ChannelConfigurationPolicy.isActive(channel))
                    {
                        retiredChannelIds.add(id);
                        continue;
                    }
                    if(channel.isConfigurationIdPersistenceRequired() ||
                        !configurationId.equals(channel.getConfigurationId()))
                    {
                        throw new IOException("configuration_channel row " + id +
                            " changes identity during strict decoding");
                    }

                    Format5ChannelProjection projection = Format5ChannelProjection.from(channel);
                    String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
                    String systemName = preferredChannelText(resultSet.getString("system_name"), root, "system");
                    String siteName = preferredChannelText(resultSet.getString("site_name"), root, "site");
                    String name = preferredChannelText(resultSet.getString("name"), root, "name");
                    String aliasListName = preferredChannelText(resultSet.getString("alias_list_name"), root,
                        "aliasListName");
                    if(!java.util.Objects.equals(resultSet.getString("system_name"), systemName) ||
                        !java.util.Objects.equals(resultSet.getString("site_name"), siteName) ||
                        !java.util.Objects.equals(resultSet.getString("name"), name) ||
                        !java.util.Objects.equals(resultSet.getString("alias_list_name"), aliasListName))
                    {
                        recoveredIdentityChannelIds.add(id);
                    }
                    putNullableText(root, "system", systemName);
                    putNullableText(root, "site", siteName);
                    putNullableText(root, "name", name);
                    putNullableText(root, "aliasListName", aliasListName);

                    String storedRadresGuid = resultSet.getString("radres_guid");
                    String canonicalRadresGuid = recoverableUuid(storedRadresGuid);
                    if(canonicalRadresGuid == null || radresOwners.containsKey(canonicalRadresGuid))
                    {
                        canonicalRadresGuid = firstUnusedCanonicalUuid(root, radresOwners.keySet());
                    }
                    if(canonicalRadresGuid == null && "TRUNKED".equals(channelKind))
                    {
                        canonicalRadresGuid = deterministicUuid("radio-reference-site", id,
                            radresOwners.keySet());
                    }
                    if(!java.util.Objects.equals(storedRadresGuid, canonicalRadresGuid))
                    {
                        recoveredIdentityChannelIds.add(id);
                    }
                    root.remove(List.of("radresGuid", "radioResolveId", "radres_guid"));
                    if(canonicalRadresGuid != null)
                    {
                        root.put("radresGuid", canonicalRadresGuid);
                    }
                    activeChannels.add(new ActiveChannelRow(id, configurationId, channelKind, systemName, siteName,
                        name, aliasListName, canonicalRadresGuid, projection, STRICT_MAPPER.writeValueAsString(root)));
                    configurationIds.add(configurationId);
                    if(canonicalRadresGuid != null)
                    {
                        radresOwners.put(canonicalRadresGuid, id);
                    }
                }
                catch(IOException | RuntimeException ignored)
                {
                    //Some retired Alpha configurations were intentionally stored as opaque JSON.  Their exact
                    //legacy scalar classification remains sufficient to discard them, while active-looking rows
                    //with an unreadable document are reported separately as unusable.
                    if(ChannelConfigurationPolicy.isRetiredPersisted(decoderType, sourceType))
                    {
                        retiredChannelIds.add(id);
                    }
                    else
                    {
                        unusableChannelIds.add(id);
                    }
                }
            }
        }

        return new ChannelInspection(List.copyOf(activeChannels), Set.copyOf(retiredChannelIds),
            Set.copyOf(unusableChannelIds), Set.copyOf(recoveredIdentityChannelIds));
    }

    /** A usable relational projection remains authoritative; otherwise retain the decoded saved-channel value. */
    private static String preferredChannelText(String stored, ObjectNode payload, String property)
    {
        if(stored != null && !stored.isBlank())
        {
            return stored;
        }

        JsonNode value = payload.get(property);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : stored;
    }

    /** Prefer a usable JSON identity before generating or discarding one because its relational projection is bad. */
    private static String firstUnusedCanonicalUuid(ObjectNode payload, Set<String> used)
    {
        for(String property: List.of("radioResolveId", "radresGuid", "radres_guid"))
        {
            JsonNode value = payload.get(property);
            String candidate = value != null && value.isTextual() ? recoverableUuid(value.textValue()) : null;
            if(candidate != null && !used.contains(candidate))
            {
                return candidate;
            }
        }
        return null;
    }

    private static String recoverableUuid(String value)
    {
        if(value == null)
        {
            return null;
        }

        String stripped = value.strip();
        if(stripped.length() != 36)
        {
            return null;
        }

        try
        {
            String canonical = UUID.fromString(stripped).toString();
            return canonical.equalsIgnoreCase(stripped) ? canonical : null;
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static void putNullableText(ObjectNode payload, String property, String value)
    {
        if(value != null)
        {
            payload.put(property, value);
        }
        else
        {
            payload.putNull(property);
        }
    }

    private static String deterministicUuid(String kind, long sourceRowId, Set<String> used)
    {
        for(int attempt = 0; ; attempt++)
        {
            String value = UUID.nameUUIDFromBytes(("sdrtrunk-vce:format-4:" + kind + ':' + sourceRowId + ':' +
                attempt).getBytes(StandardCharsets.UTF_8)).toString();
            if(!used.contains(value))
            {
                return value;
            }
        }
    }

    private static LegacyAccess parseAccess(Optional<String> stored) throws IOException
    {
        if(stored.isEmpty())
        {
            return new LegacyAccess(List.of(), Map.of(), 0, 0);
        }

        JsonNode root = parseObject(stored.get(), ACCESS_KEY, MAXIMUM_LEGACY_ACCESS_BYTES);
        List<AccountInput> accounts = new ArrayList<>();
        Set<String> usernames = new HashSet<>();
        JsonNode primary = root.get("primaryAdmin");
        boolean primaryPresent = false;
        int resetRows = 0;
        try
        {
            requireInteger(root, "formatVersion", ACCESS_KEY, 1, 1);
        }
        catch(IOException | RuntimeException ignored)
        {
            //The database format already identifies this historical setting.  Treat corrupt bookkeeping as one
            //reset component instead of discarding independently valid credentials and policy overrides.
            resetRows++;
        }

        if(primary != null && !primary.isNull())
        {
            try
            {
                CredentialInput credential = parseCredential(primary, "primaryAdmin");
                if(!"admin".equals(credential.username()))
                {
                    throw new IOException("Primary administrator username must be admin");
                }
                accounts.add(new AccountInput(credential, AccessTier.ADMIN, true));
                usernames.add(credential.username());
                primaryPresent = true;
            }
            catch(IOException | RuntimeException ignored)
            {
                resetRows++;
            }
        }

        JsonNode users = root.get("users");
        if(users == null || !users.isArray())
        {
            resetRows++;
            users = null;
        }
        else
        {
            int retainedUsers = Math.min(users.size(), 256);
            resetRows += users.size() - retainedUsers;
            for(int index = 0; index < retainedUsers; index++)
            {
                try
                {
                    JsonNode user = requireObject(users.get(index), "users[" + index + "]");
                    requireExactFields(user, STORED_USER_FIELDS, "users[" + index + "]");
                    AccessTier tier = parseAccountTier(requireText(user, "tier", "users[" + index + "]"));
                    CredentialInput credential = parseCredential(user.get("credential"),
                        "users[" + index + "].credential");

                    if("admin".equals(credential.username()) || !usernames.add(credential.username()))
                    {
                        throw new IOException("Duplicate or misplaced legacy web username: " +
                            credential.username());
                    }
                    accounts.add(new AccountInput(credential, tier, false));
                }
                catch(IOException | RuntimeException ignored)
                {
                    resetRows++;
                }
            }
        }

        Map<String,AccessTier> policies = new LinkedHashMap<>();
        int retired = 0;
        JsonNode overrides = root.get("policyOverrides");
        if(overrides == null || !overrides.isObject())
        {
            resetRows++;
        }
        else
        {
            var fields = overrides.fields();
            while(fields.hasNext())
            {
                Map.Entry<String,JsonNode> entry = fields.next();
                String id = entry.getKey();
                try
                {
                    if(!entry.getValue().isTextual())
                    {
                        throw new IOException("Legacy policy tier must be a string: " + id);
                    }
                    if("aliases".equals(id) || "tuner-spectrum".equals(id))
                    {
                        parseTier(entry.getValue().textValue(), "policy " + id);
                        retired++;
                        continue;
                    }
                    if(LEGACY_FIXED_CAPABILITIES.contains(id))
                    {
                        throw new IOException("Fixed web capability has a legacy override: " + id);
                    }
                    if(!LEGACY_CONFIGURABLE_CAPABILITIES.contains(id))
                    {
                        throw new IOException("Unknown legacy web capability: " + id);
                    }
                    AccessTier tier = parseTier(entry.getValue().textValue(), "policy " + id);
                    if(tier != AccessTier.PUBLIC)
                    {
                        policies.put(id, tier);
                    }
                }
                catch(IOException | RuntimeException ignored)
                {
                    resetRows++;
                }
            }
        }

        if(!primaryPresent)
        {
            resetRows += accounts.size() + policies.size();
            accounts.clear();
            policies.clear();
        }

        accounts.sort(Comparator.comparing(AccountInput::primaryAdmin).reversed()
            .thenComparing(account -> account.credential().username()));
        return new LegacyAccess(List.copyOf(accounts), Map.copyOf(policies), retired, resetRows);
    }

    private static CredentialInput parseCredential(JsonNode node, String label) throws IOException
    {
        JsonNode credential = requireObject(node, label);
        requireExactFields(credential, CREDENTIAL_FIELDS, label);
        WebPasswordVerifier validated = new WebPasswordVerifier(
            requireInteger(credential, "version", label, 1, 1),
            requireText(credential, "username", label), requireText(credential, "algorithm", label),
            requireInteger(credential, "iterations", label, 1, Integer.MAX_VALUE),
            requireInteger(credential, "derivedKeyBits", label, 1, Integer.MAX_VALUE),
            requireText(credential, "saltBase64", label), requireText(credential, "passwordHashBase64", label),
            requireLong(credential, "passwordChangedAtEpochMillis", label, 1),
            requireLong(credential, "credentialVersion", label, 1));
        return new CredentialInput(validated.version(), validated.username(), validated.algorithm(),
            validated.iterations(), validated.derivedKeyBits(), validated.saltBase64(),
            validated.passwordHashBase64(), validated.passwordChangedAtEpochMillis(),
            validated.authRevision());
    }

    private static LegacyPresentation parsePresentation(Optional<String> displayStored,
                                                         Optional<String> portableStored,
                                                         boolean retainForInitialAdministrator) throws IOException
    {
        boolean encryption = true;
        boolean hasPersonalState = false;
        int resetRows = 0;
        if(displayStored.isPresent())
        {
            try
            {
                JsonNode display = parseObject(displayStored.get(), DISPLAY_KEY, 4096);
                requireExactFields(display, DISPLAY_FIELDS, DISPLAY_KEY);
                requireInteger(display, "format_version", DISPLAY_KEY, 1, 1);
                encryption = requireBoolean(display, "show_encryption_details", DISPLAY_KEY);
                hasPersonalState = true;
            }
            catch(IOException | RuntimeException ignored)
            {
                resetRows++;
            }
        }

        boolean control = true;
        boolean voice = true;
        String mode = "percentage";
        int rowLimit = 200;
        int moved = 0;
        Map<String,Map<String,String>> portable = new LinkedHashMap<>();

        if(portableStored.isPresent())
        {
            JsonNode root = null;
            try
            {
                root = parseObject(portableStored.get(), PORTABLE_PREFERENCES_KEY,
                    MAXIMUM_PORTABLE_PREFERENCES_BYTES);
            }
            catch(IOException | RuntimeException ignored)
            {
                resetRows++;
            }

            if(root != null)
            {
                var nodes = root.fields();
                while(nodes.hasNext())
                {
                    Map.Entry<String,JsonNode> node = nodes.next();
                    if(!node.getValue().isObject())
                    {
                        resetRows++;
                        continue;
                    }
                    Map<String,String> copied = new LinkedHashMap<>();
                    var fields = node.getValue().fields();
                    while(fields.hasNext())
                    {
                        Map.Entry<String,JsonNode> field = fields.next();
                        if(field.getValue().isTextual())
                        {
                            copied.put(field.getKey(), field.getValue().textValue());
                        }
                        else
                        {
                            resetRows++;
                        }
                    }
                    portable.put(node.getKey(), copied);
                }
            }

            Map<String,String> nowPlaying = portable.get(NOW_PLAYING_NODE);
            if(nowPlaying != null)
            {
                if(nowPlaying.containsKey(SHOW_CONTROL_KEY))
                {
                    moved++;
                    try
                    {
                        control = parseBooleanText(nowPlaying.get(SHOW_CONTROL_KEY), SHOW_CONTROL_KEY);
                        hasPersonalState = true;
                    }
                    catch(IOException | RuntimeException ignored)
                    {
                        resetRows++;
                        nowPlaying.remove(SHOW_CONTROL_KEY);
                    }
                }
                if(nowPlaying.containsKey(SHOW_VOICE_KEY))
                {
                    moved++;
                    try
                    {
                        voice = parseBooleanText(nowPlaying.get(SHOW_VOICE_KEY), SHOW_VOICE_KEY);
                        hasPersonalState = true;
                    }
                    catch(IOException | RuntimeException ignored)
                    {
                        resetRows++;
                        nowPlaying.remove(SHOW_VOICE_KEY);
                    }
                }
                if(nowPlaying.containsKey(DISPLAY_MODE_KEY))
                {
                    moved++;
                    try
                    {
                        mode = switch(nowPlaying.get(DISPLAY_MODE_KEY))
                        {
                            case "PERCENTAGE" -> "percentage";
                            case "DETAILED" -> "detailed";
                            default -> throw new IOException("Invalid moved Java preference: " + DISPLAY_MODE_KEY);
                        };
                        hasPersonalState = true;
                    }
                    catch(IOException | RuntimeException ignored)
                    {
                        resetRows++;
                        nowPlaying.remove(DISPLAY_MODE_KEY);
                    }
                }
                if(nowPlaying.containsKey(ROW_LIMIT_KEY))
                {
                    moved++;
                    try
                    {
                        int parsed = parseCanonicalInteger(nowPlaying.get(ROW_LIMIT_KEY), ROW_LIMIT_KEY);
                        if(parsed < 25 || parsed > 500)
                        {
                            throw new IOException(
                                "Moved Java preference is outside its supported range: " + ROW_LIMIT_KEY);
                        }
                        rowLimit = parsed;
                        hasPersonalState = true;
                    }
                    catch(IOException | RuntimeException ignored)
                    {
                        resetRows++;
                        nowPlaying.remove(ROW_LIMIT_KEY);
                    }
                }

                if(!retainForInitialAdministrator)
                {
                    MOVED_NOW_PLAYING_KEYS.forEach(nowPlaying::remove);
                }
            }
        }

        portable.computeIfAbsent(NOW_PLAYING_NODE, ignored -> new LinkedHashMap<>())
            .put(SITE_SETTINGS_REVISION_KEY, "1");

        return new LegacyPresentation(encryption, control, voice, mode, rowLimit,
            Optional.of(STRICT_MAPPER.writeValueAsString(portable)),
            moved, hasPersonalState, resetRows);
    }

    private static void copyChannels(Connection connection, List<ActiveChannelRow> activeChannels,
                                     Set<Long> retiredChannelIds, Set<Long> unusableChannelIds) throws SQLException
    {
        Map<Long,ActiveChannelRow> byId = new HashMap<>();
        activeChannels.forEach(channel -> byId.put(channel.id(), channel));
        Set<Long> retired = new HashSet<>(retiredChannelIds);
        Set<Long> unusable = new HashSet<>(unusableChannelIds);
        try(PreparedStatement source = connection.prepareStatement("""
                SELECT id, sort_order FROM configuration_channel_format4 ORDER BY id
                """);
            ResultSet rows = source.executeQuery();
            PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    id, configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                    radres_guid, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                    frequency_count, recording_enabled, event_logging_enabled, config_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """))
        {
            while(rows.next())
            {
                long id = rows.getLong("id");
                ActiveChannelRow channel = byId.remove(id);
                if(channel == null)
                {
                    if(retired.remove(id) || unusable.remove(id))
                    {
                        continue;
                    }
                    throw new SQLException("Saved channel changed after format-5 preflight");
                }
                insert.setLong(1, channel.id());
                insert.setString(2, channel.configurationId());
                insert.setString(3, channel.channelKind());
                insert.setObject(4, rows.getObject("sort_order"));
                insert.setString(5, channel.systemName());
                insert.setString(6, channel.siteName());
                insert.setString(7, channel.name());
                insert.setString(8, channel.aliasListName());
                insert.setString(9, channel.radresGuid());
                insert.setInt(10, channel.projection().autoStart() ? 1 : 0);
                if(channel.projection().autoStartOrder() != null)
                {
                    insert.setInt(11, channel.projection().autoStartOrder());
                }
                else
                {
                    insert.setNull(11, Types.INTEGER);
                }
                channel.projection().bind(insert, 12);
                insert.setString(18, channel.payload());
                insert.executeUpdate();
            }
        }

        if(!byId.isEmpty() || !retired.isEmpty() || !unusable.isEmpty())
        {
            throw new SQLException("Saved channel changed after format-5 preflight");
        }
    }

    private static void insertAccounts(Connection connection, List<AccountInput> accounts, String preferencesJson)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO web_user (
                username, tier, primary_admin, credential_version, password_algorithm, password_iterations,
                password_derived_key_bits, password_salt, password_hash, password_changed_at_ms, auth_revision,
                preferences_json, preferences_revision, created_at_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            """))
        {
            for(AccountInput account: accounts)
            {
                CredentialInput credential = account.credential();
                byte[] salt = null;
                byte[] passwordHash = null;
                try
                {
                    salt = Base64.getDecoder().decode(credential.saltBase64());
                    passwordHash = Base64.getDecoder().decode(credential.passwordHashBase64());
                    statement.setString(1, credential.username());
                    statement.setString(2, account.tier().name());
                    statement.setInt(3, account.primaryAdmin() ? 1 : 0);
                    statement.setInt(4, credential.version());
                    statement.setString(5, credential.algorithm());
                    statement.setInt(6, credential.iterations());
                    statement.setInt(7, credential.derivedKeyBits());
                    statement.setBytes(8, salt);
                    statement.setBytes(9, passwordHash);
                    statement.setLong(10, credential.passwordChangedAtMs());
                    statement.setLong(11, credential.authRevision());
                    statement.setString(12, preferencesJson);
                    statement.setLong(13, credential.passwordChangedAtMs());
                    statement.setLong(14, credential.passwordChangedAtMs());
                    if(statement.executeUpdate() != 1)
                    {
                        throw new SQLException("Web account migration did not insert one row");
                    }
                }
                finally
                {
                    if(salt != null)
                    {
                        Arrays.fill(salt, (byte)0);
                    }
                    if(passwordHash != null)
                    {
                        Arrays.fill(passwordHash, (byte)0);
                    }
                }
            }
        }
    }

    private static void insertPolicies(Connection connection, Map<String,AccessTier> policies) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO web_access_policy(capability_id, required_tier, updated_at_ms)
            VALUES (?, ?, ?)
            """))
        {
            for(Map.Entry<String,AccessTier> policy: policies.entrySet())
            {
                statement.setString(1, policy.getKey());
                statement.setString(2, policy.getValue().name());
                statement.setLong(3, System.currentTimeMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void updatePortablePreferences(Connection connection, Optional<String> json) throws SQLException
    {
        if(json.isEmpty())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,
                updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);
            statement.setString(2, json.get());
            statement.setLong(3, System.currentTimeMillis());
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Portable Java preferences were not persisted");
            }
        }
    }

    private static List<DatabaseMigrationEffect> effects(MigrationInput input)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "saved channel identity scalars",
                input.channels().size(),
                "Add exact configuration UUID and channel kind and rebuild deterministic query projections"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired channel configurations",
                input.retiredChannelIds().size(),
                "Drop decoded unsupported channels and use exact legacy scalars only for opaque MPT-1327 or " +
                    "sound-card documents"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "unusable saved channel configurations",
                input.unusableChannelIds().size(),
                "Skip only saved channel rows whose identity or configuration document cannot be recovered"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "recoverable saved channel values",
                input.recoveredIdentityChannelIds().size(),
                "Generate replacement identities and default only unusable optional channel subdocuments"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts",
                input.accounts().size(), "Preserve password verifiers, roles, and authentication revisions"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides",
                input.policies().size(), "Preserve active non-default configurable overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences",
                input.accounts().size(), "Seed the typed version-1 preference document for each migrated account"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.PRESERVE,
                "initial administrator browser preferences",
                input.pendingInitialAdminPreferences() ? input.displayRows() + input.movedPreferenceCount() : 0,
                "Retain ownerless Alpha presentation values until setup creates the primary administrator"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision",
                1, "Seed the optimistic concurrency revision for receiver-wide settings"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides",
                input.retiredOverrideCount(), "Drop recognized aliases and tuner-spectrum legacy overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                input.accessRows() + (input.pendingInitialAdminPreferences() ? 0 :
                    input.displayRows() + input.movedPreferenceCount()),
                "Remove the two legacy web documents and moved per-user Java preference fields"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET, "unusable legacy web/settings state",
                input.resetWebSettingRows(),
                "Reset only malformed legacy authentication or browser settings while preserving receiver configuration"));
    }

    private static Optional<String> setting(Connection connection, String key) throws SQLException
    {
        int maximumBytes = switch(key)
        {
            case ACCESS_KEY -> MAXIMUM_LEGACY_ACCESS_BYTES;
            case DISPLAY_KEY -> 4_096;
            case PORTABLE_PREFERENCES_KEY -> MAXIMUM_PORTABLE_PREFERENCES_BYTES;
            default -> throw new IllegalArgumentException("Unsupported legacy setting key");
        };
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT CASE WHEN typeof(settings_json)='text'
                              AND length(CAST(settings_json AS BLOB)) <= ?
                        THEN settings_json END
            FROM application_settings WHERE key=?
            """))
        {
            statement.setInt(1, maximumBytes);
            statement.setString(2, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return Optional.empty();
                }

                String value = resultSet.getString(1);
                //An existing null, non-text, or oversized row is present but unusable. Preserve that distinction so
                //the component is counted as reset without ever materializing its payload in the JVM.
                return Optional.of(value != null ? value : "");
            }
        }
    }

    private static void deleteSetting(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM application_settings WHERE key=?"))
        {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    private static void setMetadata(Connection connection, String key, String value) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata(key, value, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private static JsonNode parseObject(String json, String label, int maximumBytes) throws IOException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
        {
            throw new IOException(label + " is missing or exceeds its migration bound");
        }
        return requireObject(STRICT_MAPPER.readTree(json), label);
    }

    private static JsonNode requireObject(JsonNode node, String label) throws IOException
    {
        if(node == null || !node.isObject())
        {
            throw new IOException(label + " must be a JSON object");
        }
        return node;
    }

    private static JsonNode requireArray(JsonNode object, String field, String label) throws IOException
    {
        JsonNode node = object.get(field);
        if(node == null || !node.isArray())
        {
            throw new IOException(label + "." + field + " must be a JSON array");
        }
        return node;
    }

    private static void requireExactFields(JsonNode object, Set<String> expected, String label) throws IOException
    {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))
        {
            throw new IOException(label + " fields are not the exact recognized format");
        }
    }

    private static String requireText(JsonNode object, String field, String label) throws IOException
    {
        JsonNode node = object.get(field);
        if(node == null || !node.isTextual())
        {
            throw new IOException(label + "." + field + " must be text");
        }
        return node.textValue();
    }

    private static boolean requireBoolean(JsonNode object, String field, String label) throws IOException
    {
        JsonNode node = object.get(field);
        if(node == null || !node.isBoolean())
        {
            throw new IOException(label + "." + field + " must be boolean");
        }
        return node.booleanValue();
    }

    private static int requireInteger(JsonNode object, String field, String label, int minimum, int maximum)
        throws IOException
    {
        JsonNode node = object.get(field);
        if(node == null || !node.isInt() || node.intValue() < minimum || node.intValue() > maximum)
        {
            throw new IOException(label + "." + field + " is outside its integer bound");
        }
        return node.intValue();
    }

    private static long requireLong(JsonNode object, String field, String label, long minimum) throws IOException
    {
        JsonNode node = object.get(field);
        if(node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < minimum)
        {
            throw new IOException(label + "." + field + " is outside its integer bound");
        }
        return node.longValue();
    }

    private static AccessTier parseAccountTier(String value) throws IOException
    {
        AccessTier tier = parseTier(value, "account tier");
        if(!tier.isAccountTier())
        {
            throw new IOException("Persisted web account cannot use PUBLIC tier");
        }
        return tier;
    }

    private static AccessTier parseTier(String value, String label) throws IOException
    {
        try
        {
            return AccessTier.valueOf(value);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw new IOException("Invalid " + label, exception);
        }
    }

    private static boolean parseBooleanText(String value, String label) throws IOException
    {
        return switch(value)
        {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IOException("Invalid moved Java preference: " + label);
        };
    }

    private static int parseCanonicalInteger(String value, String label) throws IOException
    {
        try
        {
            int parsed = Integer.parseInt(value);
            if(!Integer.toString(parsed).equals(value))
            {
                throw new NumberFormatException("not canonical");
            }
            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw new IOException("Invalid moved Java preference: " + label, exception);
        }
    }

    private record ActiveChannelRow(long id, String configurationId, String channelKind, String systemName,
                                    String siteName, String name, String aliasListName, String radresGuid,
                                    Format5ChannelProjection projection, String payload)
    {
    }

    /** Frozen format-5 row projection; it must not follow the smaller current projection. */
    private record Format5ChannelProjection(boolean autoStart, Integer autoStartOrder, String decoderType,
                                            String sourceType, Long primaryFrequencyHz, int frequencyCount,
                                            boolean hasRecorders, boolean hasEventLoggers)
    {
        private static Format5ChannelProjection from(Channel channel)
        {
            DecodeConfiguration decode = channel.getDecodeConfiguration();
            SourceConfiguration source = channel.getSourceConfiguration();
            List<Long> frequencies = channel.getFrequencyList();
            RecordConfiguration record = channel.getRecordConfiguration();
            EventLogConfiguration eventLog = channel.getEventLogConfiguration();
            return new Format5ChannelProjection(channel.getAutoStart(), channel.getAutoStartOrder(),
                decode != null && decode.getDecoderType() != null ? decode.getDecoderType().name() : null,
                source != null && source.getSourceType() != null ? source.getSourceType().name() : null,
                primaryFrequency(source), frequencies != null ? frequencies.size() : 0,
                record != null && record.getRecorders() != null && !record.getRecorders().isEmpty(),
                eventLog != null && eventLog.getLoggers() != null && !eventLog.getLoggers().isEmpty());
        }

        private static boolean readBooleanFlag(ResultSet resultSet, String column) throws SQLException, IOException
        {
            long value = requiredInteger(resultSet, column);
            if(value == 0)
            {
                return false;
            }
            if(value == 1)
            {
                return true;
            }
            throw new IOException("configuration_channel " + column + " must be 0 or 1");
        }

        private static Integer readNullableInt(ResultSet resultSet, String column) throws SQLException, IOException
        {
            Long value = nullableInteger(resultSet, column);
            if(value == null)
            {
                return null;
            }
            if(value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            {
                throw new IOException("configuration_channel " + column +
                    " is outside the supported integer range");
            }
            return value.intValue();
        }

        private void bind(PreparedStatement statement, int firstParameter) throws SQLException
        {
            statement.setString(firstParameter, decoderType);
            statement.setString(firstParameter + 1, sourceType);
            if(primaryFrequencyHz != null)
            {
                statement.setLong(firstParameter + 2, primaryFrequencyHz);
            }
            else
            {
                statement.setNull(firstParameter + 2, Types.INTEGER);
            }
            statement.setInt(firstParameter + 3, frequencyCount);
            statement.setInt(firstParameter + 4, hasRecorders ? 1 : 0);
            statement.setInt(firstParameter + 5, hasEventLoggers ? 1 : 0);
        }

        private static Long primaryFrequency(SourceConfiguration source)
        {
            if(source instanceof SourceConfigTuner tuner)
            {
                return tuner.getFrequency();
            }
            if(source instanceof SourceConfigTunerMultipleFrequency multiple)
            {
                long frequency = multiple.getPreferredFrequency();
                return frequency > 0 ? frequency : null;
            }
            if(source instanceof SourceConfigRecording recording)
            {
                return recording.getFrequency();
            }
            return null;
        }

        private static long requiredInteger(ResultSet resultSet, String column) throws SQLException, IOException
        {
            Long value = nullableInteger(resultSet, column);
            if(value == null)
            {
                throw new IOException("configuration_channel " + column + " cannot be null");
            }
            return value;
        }

        private static Long nullableInteger(ResultSet resultSet, String column) throws SQLException, IOException
        {
            Object value = resultSet.getObject(column);
            if(value == null)
            {
                return null;
            }
            if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            {
                return ((Number)value).longValue();
            }
            throw new IOException("configuration_channel " + column + " is not stored as an integer");
        }
    }

    private record ChannelInspection(List<ActiveChannelRow> activeChannels, Set<Long> retiredChannelIds,
                                     Set<Long> unusableChannelIds, Set<Long> recoveredIdentityChannelIds)
    {
    }

    private record CredentialInput(int version, String username, String algorithm, int iterations,
                                   int derivedKeyBits, String saltBase64, String passwordHashBase64,
                                   long passwordChangedAtMs, long authRevision)
    {
    }

    private record AccountInput(CredentialInput credential, AccessTier tier, boolean primaryAdmin)
    {
    }

    private record LegacyAccess(List<AccountInput> accounts, Map<String,AccessTier> policies,
                                int retiredOverrideCount, int resetRows)
    {
    }

    private record LegacyPresentation(boolean showEncryptionDetails, boolean showControlDecodeQuality,
                                      boolean showVoiceDecodeQuality, String decodeQualityDisplayMode,
                                      int liveDetailRowLimit, Optional<String> portablePreferences,
                                      int movedPreferenceCount, boolean hasPersonalState, int resetRows)
    {
    }

    private record MigrationInput(List<ActiveChannelRow> channels, Set<Long> retiredChannelIds,
                                  Set<Long> unusableChannelIds, Set<Long> recoveredIdentityChannelIds,
                                  List<AccountInput> accounts,
                                  Map<String,AccessTier> policies, String preferencesJson,
                                  Optional<String> portablePreferences, int retiredOverrideCount,
                                  int movedPreferenceCount, int accessRows, int displayRows,
                                  boolean pendingInitialAdminPreferences, int resetWebSettingRows)
    {
    }
}

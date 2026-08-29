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
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.configuration.ConfigurationChannelProjection;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebPasswordVerifier;
import io.github.dsheirer.web.auth.WebCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
    private static final Set<String> ACCESS_FIELDS = Set.of(
        "formatVersion", "primaryAdmin", "users", "policyOverrides");
    private static final Set<String> CREDENTIAL_FIELDS = Set.of(
        "version", "username", "algorithm", "iterations", "derivedKeyBits", "saltBase64",
        "passwordHashBase64", "passwordChangedAtEpochMillis", "credentialVersion");
    private static final Set<String> STORED_USER_FIELDS = Set.of("tier", "credential");
    private static final Set<String> DISPLAY_FIELDS = Set.of("format_version", "show_encryption_details");
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
                "Drop recognized unsupported MPT-1327 and sound-card channel rows before JSON decoding"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Move every verifier, role, and authentication revision into normalized web_user rows"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Move active configurable overrides into web_access_policy"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Seed every migrated account from the former shared browser presentation values"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision",
                1, "Seed the optimistic concurrency revision for receiver-wide settings"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Drop only the recognized aliases and tuner-spectrum legacy overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                DatabaseMigrationEffect.UNKNOWN_COUNT,
                "Remove web.access.v1, web.display.v1, and moved per-user Java preference fields"));
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

        copyChannels(connection, input.channels(), input.retiredChannelIds());

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
        deleteSetting(connection, DISPLAY_KEY);
        setMetadata(connection, "configuration_schema_version", "3");
        setMetadata(connection, "settings_schema_version", "3");
    }

    private static MigrationInput inspect(Connection connection) throws SQLException
    {
        DatabaseFormatCatalog.DetectedFormat detected = DatabaseFormatCatalog.inspect(connection);
        if(detected.version() != 4)
        {
            throw new SQLException("Migration step format-4-to-5 requires exact source format 4; found " +
                detected.version() + " [" + detected.id() + "]");
        }

        try
        {
            ChannelInspection channelInspection = inspectChannels(connection);
            LegacyAccess access = parseAccess(setting(connection, ACCESS_KEY));
            LegacyPresentation presentation = parsePresentation(setting(connection, DISPLAY_KEY),
                setting(connection, PORTABLE_PREFERENCES_KEY));
            if(access.accounts().isEmpty() && presentation.hasPersonalState())
            {
                throw new IOException("Personal web settings exist without an account to own them; " +
                    "create the primary administrator in the old build before migrating");
            }
            String preferencesJson = Format6WebUserPreferencesCodec.defaults(
                presentation.showEncryptionDetails(), presentation.showControlDecodeQuality(),
                presentation.showVoiceDecodeQuality(), presentation.decodeQualityDisplayMode(),
                presentation.liveDetailRowLimit());

            return new MigrationInput(channelInspection.activeChannels(), channelInspection.retiredChannelIds(),
                access.accounts(), access.policies(), preferencesJson, presentation.portablePreferences(),
                access.retiredOverrideCount(), presentation.movedPreferenceCount(),
                setting(connection, ACCESS_KEY).isPresent() ? 1 : 0,
                setting(connection, DISPLAY_KEY).isPresent() ? 1 : 0);
        }
        catch(IOException | IllegalArgumentException exception)
        {
            throw new SQLException("Format-4 web/settings state is ambiguous or malformed: " +
                exception.getMessage(), exception);
        }
    }

    private static ChannelInspection inspectChannels(Connection connection) throws SQLException, IOException
    {
        List<ActiveChannelRow> activeChannels = new ArrayList<>();
        Set<Long> retiredChannelIds = new HashSet<>();
        Set<String> configurationIds = new HashSet<>();
        Map<String,Long> radresOwners = new HashMap<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, auto_start, auto_start_order, decoder_type, source_type, primary_frequency_hz,
                   frequency_count, recording_enabled, event_logging_enabled, config_json, radres_guid
            FROM configuration_channel
            ORDER BY id
            """); ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                long id = resultSet.getLong("id");
                String decoderType = resultSet.getString("decoder_type");
                String sourceType = resultSet.getString("source_type");
                if(ChannelConfigurationPolicy.isRetiredPersisted(decoderType, sourceType))
                {
                    retiredChannelIds.add(id);
                    continue;
                }

                ConfigurationChannelProjection.readBooleanFlag(resultSet, "auto_start");
                ConfigurationChannelProjection.readNullableInt(resultSet, "auto_start_order");

                String json = resultSet.getString("config_json");
                JsonNode root = parseObject(json, "configuration_channel row " + id, MAXIMUM_PORTABLE_PREFERENCES_BYTES);
                JsonNode idNode = root.get("configurationId");

                if(idNode == null || !idNode.isTextual())
                {
                    throw new IOException("configuration_channel row " + id +
                        " has no textual configurationId");
                }

                String configurationId = idNode.textValue();
                if(!UUID.fromString(configurationId).toString().equals(configurationId))
                {
                    throw new IOException("configuration_channel row " + id +
                        " configurationId is not a canonical lowercase UUID");
                }

                if(!configurationIds.add(configurationId))
                {
                    throw new IOException("Duplicate saved channel configurationId: " + configurationId);
                }

                Channel channel = CHANNEL_MAPPER.treeToValue(root, Channel.class);
                if(!ChannelConfigurationPolicy.isActive(channel))
                {
                    throw new IOException("configuration_channel row " + id +
                        " is not an active supported channel");
                }
                if(channel.isConfigurationIdPersistenceRequired() || !configurationId.equals(channel.getConfigurationId()))
                {
                    throw new IOException("configuration_channel row " + id +
                        " changes identity during strict decoding");
                }

                ConfigurationChannelProjection projection = ConfigurationChannelProjection.from(channel);

                String channelKind = ChannelConfigurationPolicy.requireChannelKind(channel).name();
                String radresGuid = resultSet.getString("radres_guid");
                if("TRUNKED".equals(channelKind) && (radresGuid == null || radresGuid.strip().isEmpty()))
                {
                    throw new IOException("configuration_channel row " + id +
                        " is trunked but has no RadioReference site GUID");
                }
                if(radresGuid != null && !radresGuid.strip().isEmpty())
                {
                    String canonicalRadresGuid;
                    try
                    {
                        canonicalRadresGuid = UUID.fromString(radresGuid).toString();
                    }
                    catch(IllegalArgumentException exception)
                    {
                        throw new IOException("configuration_channel row " + id +
                            " radres_guid is not a canonical lowercase UUID", exception);
                    }
                    if(!canonicalRadresGuid.equals(radresGuid))
                    {
                        throw new IOException("configuration_channel row " + id +
                            " radres_guid is not a canonical lowercase UUID");
                    }
                    Long previous = radresOwners.putIfAbsent(canonicalRadresGuid, id);
                    if(previous != null)
                    {
                        throw new IOException("Duplicate nonblank saved channel radres_guid in rows " + previous +
                            " and " + id);
                    }
                }
                activeChannels.add(new ActiveChannelRow(id, configurationId, channelKind, projection));
            }
        }
        catch(IllegalArgumentException exception)
        {
            throw new IOException("Saved channel identity or kind cannot be classified", exception);
        }

        return new ChannelInspection(List.copyOf(activeChannels), Set.copyOf(retiredChannelIds));
    }

    private static LegacyAccess parseAccess(Optional<String> stored) throws IOException
    {
        if(stored.isEmpty())
        {
            return new LegacyAccess(List.of(), Map.of(), 0);
        }

        JsonNode root = parseObject(stored.get(), ACCESS_KEY, MAXIMUM_LEGACY_ACCESS_BYTES);
        requireExactFields(root, ACCESS_FIELDS, ACCESS_KEY);
        requireInteger(root, "formatVersion", ACCESS_KEY, 1, 1);
        List<AccountInput> accounts = new ArrayList<>();
        Set<String> usernames = new HashSet<>();
        JsonNode primary = root.get("primaryAdmin");
        boolean primaryPresent = false;

        if(primary != null && !primary.isNull())
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

        JsonNode users = requireArray(root, "users", ACCESS_KEY);
        if(users.size() > 256)
        {
            throw new IOException("Legacy web user count exceeds 256");
        }

        for(int index = 0; index < users.size(); index++)
        {
            JsonNode user = requireObject(users.get(index), "users[" + index + "]");
            requireExactFields(user, STORED_USER_FIELDS, "users[" + index + "]");
            AccessTier tier = parseAccountTier(requireText(user, "tier", "users[" + index + "]"));
            CredentialInput credential = parseCredential(user.get("credential"), "users[" + index + "].credential");

            if("admin".equals(credential.username()) || !usernames.add(credential.username()))
            {
                throw new IOException("Duplicate or misplaced legacy web username: " + credential.username());
            }
            accounts.add(new AccountInput(credential, tier, false));
        }

        JsonNode overrides = requireObject(root.get("policyOverrides"), "policyOverrides");
        Map<String,AccessTier> policies = new LinkedHashMap<>();
        int retired = 0;
        var fields = overrides.fields();
        while(fields.hasNext())
        {
            Map.Entry<String,JsonNode> entry = fields.next();
            String id = entry.getKey();
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

            WebCapability capability = WebCapability.fromId(id)
                .orElseThrow(() -> new IOException("Unknown legacy web capability: " + id));
            if(!capability.configurable())
            {
                throw new IOException("Fixed web capability has a legacy override: " + id);
            }
            AccessTier tier = parseTier(entry.getValue().textValue(), "policy " + id);
            if(tier != capability.defaultTier())
            {
                policies.put(id, tier);
            }
        }

        if(!primaryPresent && (!users.isEmpty() || !policies.isEmpty()))
        {
            throw new IOException("Legacy users or policies exist without the primary administrator");
        }

        accounts.sort(Comparator.comparing(AccountInput::primaryAdmin).reversed()
            .thenComparing(account -> account.credential().username()));
        return new LegacyAccess(List.copyOf(accounts), Map.copyOf(policies), retired);
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
                                                         Optional<String> portableStored) throws IOException
    {
        boolean encryption = true;
        if(displayStored.isPresent())
        {
            JsonNode display = parseObject(displayStored.get(), DISPLAY_KEY, 4096);
            requireExactFields(display, DISPLAY_FIELDS, DISPLAY_KEY);
            requireInteger(display, "format_version", DISPLAY_KEY, 1, 1);
            encryption = requireBoolean(display, "show_encryption_details", DISPLAY_KEY);
        }

        boolean control = true;
        boolean voice = true;
        String mode = "percentage";
        int rowLimit = 200;
        int moved = 0;
        Map<String,Map<String,String>> portable = new LinkedHashMap<>();

        if(portableStored.isPresent())
        {
            JsonNode root = parseObject(portableStored.get(), PORTABLE_PREFERENCES_KEY,
                MAXIMUM_PORTABLE_PREFERENCES_BYTES);
            var nodes = root.fields();
            while(nodes.hasNext())
            {
                Map.Entry<String,JsonNode> node = nodes.next();
                JsonNode values = requireObject(node.getValue(), "portable preference node " + node.getKey());
                Map<String,String> copied = new LinkedHashMap<>();
                var fields = values.fields();
                while(fields.hasNext())
                {
                    Map.Entry<String,JsonNode> field = fields.next();
                    if(!field.getValue().isTextual())
                    {
                        throw new IOException("Portable Java preference value must be text: " + node.getKey() +
                            "/" + field.getKey());
                    }
                    copied.put(field.getKey(), field.getValue().textValue());
                }
                portable.put(node.getKey(), copied);
            }

            Map<String,String> nowPlaying = portable.get(NOW_PLAYING_NODE);
            if(nowPlaying != null)
            {
                if(nowPlaying.containsKey(SHOW_CONTROL_KEY))
                {
                    control = parseBooleanText(nowPlaying.get(SHOW_CONTROL_KEY), SHOW_CONTROL_KEY);
                    moved++;
                }
                if(nowPlaying.containsKey(SHOW_VOICE_KEY))
                {
                    voice = parseBooleanText(nowPlaying.get(SHOW_VOICE_KEY), SHOW_VOICE_KEY);
                    moved++;
                }
                if(nowPlaying.containsKey(DISPLAY_MODE_KEY))
                {
                    mode = switch(nowPlaying.get(DISPLAY_MODE_KEY))
                    {
                        case "PERCENTAGE" -> "percentage";
                        case "DETAILED" -> "detailed";
                        default -> throw new IOException("Invalid moved Java preference: " + DISPLAY_MODE_KEY);
                    };
                    moved++;
                }
                if(nowPlaying.containsKey(ROW_LIMIT_KEY))
                {
                    rowLimit = parseCanonicalInteger(nowPlaying.get(ROW_LIMIT_KEY), ROW_LIMIT_KEY);
                    if(rowLimit < 25 || rowLimit > 500)
                    {
                        throw new IOException("Moved Java preference is outside its supported range: " + ROW_LIMIT_KEY);
                    }
                    moved++;
                }

                MOVED_NOW_PLAYING_KEYS.forEach(nowPlaying::remove);
            }
        }

        portable.computeIfAbsent(NOW_PLAYING_NODE, ignored -> new LinkedHashMap<>())
            .put(SITE_SETTINGS_REVISION_KEY, "1");

        return new LegacyPresentation(encryption, control, voice, mode, rowLimit,
            Optional.of(STRICT_MAPPER.writeValueAsString(portable)),
            moved, displayStored.isPresent() || moved > 0);
    }

    private static void copyChannels(Connection connection, List<ActiveChannelRow> activeChannels,
                                     Set<Long> retiredChannelIds) throws SQLException
    {
        Map<Long,ActiveChannelRow> byId = new HashMap<>();
        activeChannels.forEach(channel -> byId.put(channel.id(), channel));
        Set<Long> retired = new HashSet<>(retiredChannelIds);
        try(PreparedStatement source = connection.prepareStatement("""
                SELECT id, sort_order, system_name, site_name, name, alias_list_name, radres_guid, auto_start,
                       auto_start_order, decoder_type, source_type, primary_frequency_hz, frequency_count,
                       recording_enabled, event_logging_enabled, config_json
                FROM configuration_channel_format4
                ORDER BY id
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
                    if(retired.remove(id))
                    {
                        continue;
                    }
                    throw new SQLException("Saved channel changed after format-5 preflight");
                }
                insert.setLong(1, channel.id());
                insert.setString(2, channel.configurationId());
                insert.setString(3, channel.channelKind());
                for(int sourceColumn = 2; sourceColumn <= 9; sourceColumn++)
                {
                    insert.setObject(sourceColumn + 2, rows.getObject(sourceColumn));
                }
                channel.projection().bind(insert, 12);
                insert.setObject(18, rows.getObject("config_json"));
                insert.executeUpdate();
            }
        }

        if(!byId.isEmpty() || !retired.isEmpty())
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
                "Drop recognized unsupported MPT-1327 and sound-card channel rows before JSON decoding"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web accounts",
                input.accounts().size(), "Preserve password verifiers, roles, and authentication revisions"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.TRANSFORM, "web access policy overrides",
                input.policies().size(), "Preserve active non-default configurable overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "per-user browser preferences",
                input.accounts().size(), "Seed the typed version-1 preference document for each migrated account"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "site-settings revision",
                1, "Seed the optimistic concurrency revision for receiver-wide settings"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "retired web policy overrides",
                input.retiredOverrideCount(), "Drop recognized aliases and tuner-spectrum legacy overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "superseded settings storage",
                input.accessRows() + input.displayRows() + input.movedPreferenceCount(),
                "Remove the two legacy web documents and moved per-user Java preference fields"));
    }

    private static Optional<String> setting(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? Optional.ofNullable(resultSet.getString(1)) : Optional.empty();
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
            UPDATE database_metadata SET value=?, updated_at_ms=? WHERE key=?
            """))
        {
            statement.setString(1, value);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, key);
            if(statement.executeUpdate() != 1)
            {
                throw new SQLException("Required database metadata is missing: " + key);
            }
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

    private record ActiveChannelRow(long id, String configurationId, String channelKind,
                                    ConfigurationChannelProjection projection)
    {
    }

    private record ChannelInspection(List<ActiveChannelRow> activeChannels, Set<Long> retiredChannelIds)
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
                                int retiredOverrideCount)
    {
    }

    private record LegacyPresentation(boolean showEncryptionDetails, boolean showControlDecodeQuality,
                                      boolean showVoiceDecodeQuality, String decodeQualityDisplayMode,
                                      int liveDetailRowLimit, Optional<String> portablePreferences,
                                      int movedPreferenceCount, boolean hasPersonalState)
    {
    }

    private record MigrationInput(List<ActiveChannelRow> channels, Set<Long> retiredChannelIds,
                                  List<AccountInput> accounts, Map<String,AccessTier> policies, String preferencesJson,
                                  Optional<String> portablePreferences, int retiredOverrideCount,
                                  int movedPreferenceCount, int accessRows, int displayRows)
    {
    }
}

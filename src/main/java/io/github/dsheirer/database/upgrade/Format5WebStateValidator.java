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
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessAccount;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.auth.WebPasswordVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, read-only validation for persisted format-5 web users and access policy.
 *
 * <p>SQLite table checks protect the basic column shape.  This validator protects the application-owned semantics
 * that cannot be expressed cleanly in DDL, and deliberately reuses the same domain boundaries used by the runtime.</p>
 */
public final class Format5WebStateValidator
{
    private static final String INVALID_PREFIX = "Invalid format-5 web state: ";
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final int MAXIMUM_PORTABLE_PREFERENCES_BYTES = 4_194_304;
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private Format5WebStateValidator()
    {
    }

    public static void validate(Connection connection) throws SQLException
    {
        validate(connection, 3);
    }

    /** Validates one exact persisted preference-document generation for its owning database format. */
    public static void validate(Connection connection, int preferenceDocumentVersion) throws SQLException
    {
        Objects.requireNonNull(connection, "Database connection cannot be null");
        if(preferenceDocumentVersion != 1 && preferenceDocumentVersion != 2 && preferenceDocumentVersion != 3)
        {
            throw invalid("unsupported preference-document version " + preferenceDocumentVersion);
        }

        UserCounts userCounts = validateUsers(connection, preferenceDocumentVersion);
        long policyCount = validatePolicies(connection);
        validateSiteSettings(connection, preferenceDocumentVersion == 1);

        if(userCounts.primary() > 1)
        {
            throw invalid("more than one primary administrator exists");
        }

        if(userCounts.primary() == 0 && (userCounts.total() > 0 || policyCount > 0))
        {
            throw invalid("ordinary users or access policy exist without the primary administrator");
        }
    }

    private static UserCounts validateUsers(Connection connection, int preferenceDocumentVersion) throws SQLException
    {
        long maximumRows = (long)WebAccessService.MAXIMUM_USERS + 1;
        long total = 0;
        long primary = 0;
        long ordinary = 0;
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id, username, tier, primary_admin, credential_version, password_algorithm,
                   password_iterations, password_derived_key_bits, password_salt, password_hash,
                   password_changed_at_ms, auth_revision, preferences_json, preferences_revision,
                   created_at_ms, updated_at_ms,
                   typeof(id) AS id_type,
                   typeof(username) AS username_type,
                   typeof(tier) AS tier_type,
                   typeof(primary_admin) AS primary_admin_type,
                   typeof(credential_version) AS credential_version_type,
                   typeof(password_algorithm) AS password_algorithm_type,
                   typeof(password_iterations) AS password_iterations_type,
                   typeof(password_derived_key_bits) AS password_derived_key_bits_type,
                   typeof(password_salt) AS password_salt_type,
                   typeof(password_hash) AS password_hash_type,
                   typeof(password_changed_at_ms) AS password_changed_at_type,
                   typeof(auth_revision) AS auth_revision_type,
                   typeof(preferences_json) AS preferences_json_type,
                   typeof(preferences_revision) AS preferences_revision_type,
                   typeof(created_at_ms) AS created_at_type,
                   typeof(updated_at_ms) AS updated_at_type
            FROM web_user
            LIMIT ?
            """))
        {
            statement.setLong(1, maximumRows + 1);
            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    if(++total > maximumRows)
                    {
                        throw invalid("ordinary user count exceeds " + WebAccessService.MAXIMUM_USERS);
                    }

                    if(validateUser(resultSet, preferenceDocumentVersion))
                    {
                        primary++;
                    }
                    else
                    {
                        ordinary++;
                    }
                }
            }
        }

        if(ordinary > WebAccessService.MAXIMUM_USERS)
        {
            throw invalid("ordinary user count exceeds " + WebAccessService.MAXIMUM_USERS);
        }
        return new UserCounts(total, primary, ordinary);
    }

    private static boolean validateUser(ResultSet resultSet, int preferenceDocumentVersion) throws SQLException
    {
        requireStorage(resultSet, "id_type", "integer", "account identifier");
        requireStorage(resultSet, "username_type", "text", "username");
        requireStorage(resultSet, "tier_type", "text", "account tier");
        requireStorage(resultSet, "primary_admin_type", "integer", "primary-administrator flag");
        requireStorage(resultSet, "credential_version_type", "integer", "credential version");
        requireStorage(resultSet, "password_algorithm_type", "text", "password algorithm");
        requireStorage(resultSet, "password_iterations_type", "integer", "password work factor");
        requireStorage(resultSet, "password_derived_key_bits_type", "integer", "password derived-key size");
        requireStorage(resultSet, "password_salt_type", "blob", "password salt");
        requireStorage(resultSet, "password_hash_type", "blob", "password verifier");
        requireStorage(resultSet, "password_changed_at_type", "integer", "password-change time");
        requireStorage(resultSet, "auth_revision_type", "integer", "authentication revision");
        requireStorage(resultSet, "preferences_json_type", "text", "preference document");
        requireStorage(resultSet, "preferences_revision_type", "integer", "preference revision");
        requireStorage(resultSet, "created_at_type", "integer", "account creation time");
        requireStorage(resultSet, "updated_at_type", "integer", "account update time");
        requireIncrementablePositive(resultSet, "preferences_revision", "preference revision");
        requirePositive(resultSet, "created_at_ms", "account creation time");
        requirePositive(resultSet, "updated_at_ms", "account update time");

        String username = resultSet.getString("username");
        long id = resultSet.getLong("id");
        if(id <= 0)
        {
            throw invalid("account identifier must be positive");
        }

        int primaryValue = resultSet.getInt("primary_admin");
        if(primaryValue != 0 && primaryValue != 1)
        {
            throw invalid("primary-administrator flag must be zero or one");
        }

        AccessTier tier;
        try
        {
            String normalized = WebPasswordVerifier.normalizeUsername(username);
            if(!normalized.equals(username))
            {
                throw new IllegalArgumentException("Persisted username is not canonical");
            }
            tier = AccessTier.valueOf(resultSet.getString("tier"));
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw invalid("an account has an invalid username or tier", exception);
        }

        byte[] salt = resultSet.getBytes("password_salt");
        byte[] hash = resultSet.getBytes("password_hash");
        try
        {
            WebPasswordVerifier verifier = new WebPasswordVerifier(resultSet.getInt("credential_version"), username,
                resultSet.getString("password_algorithm"), resultSet.getInt("password_iterations"),
                resultSet.getInt("password_derived_key_bits"), Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash), resultSet.getLong("password_changed_at_ms"),
                resultSet.getLong("auth_revision"));
            new WebAccessAccount(id, username, tier,
                verifier.passwordChangedAtEpochMillis(), verifier.authRevision(),
                primaryValue == 1);
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw invalid("an account has invalid credential or primary-administrator semantics", exception);
        }
        finally
        {
            if(salt != null)
            {
                Arrays.fill(salt, (byte)0);
            }
            if(hash != null)
            {
                Arrays.fill(hash, (byte)0);
            }
        }

        try
        {
            if(preferenceDocumentVersion == 1)
            {
                Format6WebUserPreferencesCodec.validate(resultSet.getString("preferences_json"));
            }
            else if(preferenceDocumentVersion == 2)
            {
                Format7WebUserPreferencesCodec.validate(resultSet.getString("preferences_json"));
            }
            else if(preferenceDocumentVersion == 3)
            {
                Format8WebUserPreferencesCodec.validate(resultSet.getString("preferences_json"));
            }
            else
            {
                throw new IOException("Unsupported web preference document version " +
                    preferenceDocumentVersion);
            }
        }
        catch(IOException exception)
        {
            throw invalid("an account has an invalid typed preference document", exception);
        }

        return primaryValue == 1;
    }

    private static long validatePolicies(Connection connection) throws SQLException
    {
        long configurableCapabilities = Arrays.stream(WebCapability.values()).filter(WebCapability::configurable)
            .count();
        Set<WebCapability> visited = EnumSet.noneOf(WebCapability.class);
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT capability_id, required_tier, updated_at_ms,
                   typeof(capability_id) AS capability_id_type,
                   typeof(required_tier) AS required_tier_type,
                   typeof(updated_at_ms) AS updated_at_type
            FROM web_access_policy
            LIMIT ?
            """))
        {
            statement.setLong(1, configurableCapabilities + 1);
            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    if(visited.size() >= configurableCapabilities)
                    {
                        throw invalid("access-policy row count exceeds the configurable capability registry");
                    }

                    requireStorage(resultSet, "capability_id_type", "text", "access-policy capability");
                    requireStorage(resultSet, "required_tier_type", "text", "access-policy tier");
                    requireStorage(resultSet, "updated_at_type", "integer", "access-policy update time");
                    requirePositive(resultSet, "updated_at_ms", "access-policy update time");
                    String id = resultSet.getString("capability_id");
                    WebCapability capability = WebCapability.fromId(id)
                        .orElseThrow(() -> invalid("unknown access-policy capability: " + id));
                    AccessTier tier;
                    try
                    {
                        tier = AccessTier.valueOf(resultSet.getString("required_tier"));
                    }
                    catch(IllegalArgumentException | NullPointerException exception)
                    {
                        throw invalid("access-policy capability has an invalid tier: " + id, exception);
                    }

                    if(!capability.configurable())
                    {
                        throw invalid("fixed access-policy capability is persisted: " + id);
                    }
                    if(tier == capability.defaultTier())
                    {
                        throw invalid("default access-policy capability is redundantly persisted: " + id);
                    }
                    if(!visited.add(capability))
                    {
                        throw invalid("duplicate access-policy capability is persisted: " + id);
                    }
                }
            }
        }
        return visited.size();
    }

    /**
     * Validates the bounded portable-preferences document and the receiver-wide site settings stored inside it.
     * Other Java preference nodes and keys remain application-owned and are accepted as opaque string values.
     */
    private static void validateSiteSettings(Connection connection, boolean allowRetiredWebAudioSettings)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT settings_json, updated_at_ms,
                   typeof(settings_json) AS settings_json_type,
                   typeof(updated_at_ms) AS updated_at_type
            FROM application_settings
            WHERE key=?
            """))
        {
            statement.setString(1, PORTABLE_PREFERENCES_KEY);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return;
                }

                requireStorage(resultSet, "settings_json_type", "text", "portable preference document");
                requireStorage(resultSet, "updated_at_type", "integer", "portable preference update time");
                requirePositive(resultSet, "updated_at_ms", "portable preference update time");
                validatePortablePreferences(resultSet.getString("settings_json"), allowRetiredWebAudioSettings);
            }
        }
    }

    private static void validatePortablePreferences(String json, boolean allowRetiredWebAudioSettings)
        throws SQLException
    {
        if(json == null || json.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PORTABLE_PREFERENCES_BYTES)
        {
            throw invalid("portable preference document is missing or exceeds its storage bound");
        }

        try
        {
            JsonNode root = STRICT_MAPPER.readTree(json);
            if(root == null || !root.isObject())
            {
                throw invalid("portable preference document must be a JSON object");
            }

            var nodes = root.fields();
            while(nodes.hasNext())
            {
                var nodeEntry = nodes.next();
                JsonNode node = nodeEntry.getValue();
                if(!node.isObject())
                {
                    throw invalid("portable preference node must be a JSON object: " + nodeEntry.getKey());
                }

                var preferences = node.fields();
                while(preferences.hasNext())
                {
                    var preference = preferences.next();
                    if(!preference.getValue().isTextual())
                    {
                        throw invalid("portable preference value must be text: " + nodeEntry.getKey() + "/" +
                            preference.getKey());
                    }

                    if(!allowRetiredWebAudioSettings &&
                        Format6To7DatabaseMigration.RETIRED_WEB_AUDIO_KEYS.contains(preference.getKey()))
                    {
                        throw invalid("retired global browser-audio setting is still stored: " +
                            preference.getKey());
                    }
                }
            }

            JsonNode nowPlaying = root.get(NowPlayingPreference.PORTABLE_PREFERENCE_NODE);
            if(nowPlaying != null)
            {
                validateNowPlayingSiteSettings(nowPlaying);
            }
        }
        catch(IOException exception)
        {
            throw invalid("portable preference document is not strict JSON", exception);
        }
    }

    private static void validateNowPlayingSiteSettings(JsonNode nowPlaying) throws SQLException
    {
        String revisionKey = NowPlayingPreference.PREFERENCE_KEY_SITE_SETTINGS_REVISION;
        String retainKey = NowPlayingPreference.PREFERENCE_KEY_RETAIN_IDLE_CALL_DETAILS;
        String clearKey = NowPlayingPreference.PREFERENCE_KEY_CLEAR_VOICE_DECODE_QUALITY_ON_CALL_END;
        String ageOutKey = NowPlayingPreference.PREFERENCE_KEY_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
        boolean hasSiteSetting = nowPlaying.has(revisionKey) || nowPlaying.has(retainKey) || nowPlaying.has(clearKey) ||
            nowPlaying.has(ageOutKey);

        if(!hasSiteSetting)
        {
            return;
        }
        if(!nowPlaying.has(revisionKey))
        {
            throw invalid("site settings exist without their revision");
        }

        long revision = parseCanonicalLong(nowPlaying.get(revisionKey).textValue(), revisionKey);
        if(revision < 1 || revision == Long.MAX_VALUE)
        {
            throw invalid("site-settings revision must be positive and incrementable");
        }
        requireBoolean(nowPlaying, retainKey);
        requireBoolean(nowPlaying, clearKey);

        if(nowPlaying.has(ageOutKey))
        {
            long ageOut = parseCanonicalLong(nowPlaying.get(ageOutKey).textValue(), ageOutKey);
            if(ageOut < NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS ||
                ageOut > NowPlayingPreference.MAX_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS)
            {
                throw invalid("site setting is outside its supported range: " + ageOutKey);
            }
        }
    }

    private static void requireBoolean(JsonNode object, String key) throws SQLException
    {
        if(object.has(key))
        {
            String value = object.get(key).textValue();
            if(!"true".equals(value) && !"false".equals(value))
            {
                throw invalid("site setting must be true or false: " + key);
            }
        }
    }

    private static long parseCanonicalLong(String value, String key) throws SQLException
    {
        try
        {
            long parsed = Long.parseLong(value);
            if(!Long.toString(parsed).equals(value))
            {
                throw invalid("site setting must be a canonical integer: " + key);
            }
            return parsed;
        }
        catch(NumberFormatException exception)
        {
            throw invalid("site setting must be a canonical integer: " + key, exception);
        }
    }

    private static void requireStorage(ResultSet resultSet, String column, String expected, String label)
        throws SQLException
    {
        String actual = resultSet.getString(column);
        if(!expected.equals(actual))
        {
            throw invalid(label + " must use SQLite " + expected + " storage");
        }
    }

    private static void requirePositive(ResultSet resultSet, String column, String label) throws SQLException
    {
        if(resultSet.getLong(column) <= 0)
        {
            throw invalid(label + " must be positive");
        }
    }

    private static void requireIncrementablePositive(ResultSet resultSet, String column, String label)
        throws SQLException
    {
        long value = resultSet.getLong(column);
        if(value <= 0 || value == Long.MAX_VALUE)
        {
            throw invalid(label + " must be positive and incrementable");
        }
    }

    private static SQLException invalid(String detail)
    {
        return new SQLException(INVALID_PREFIX + detail);
    }

    private static SQLException invalid(String detail, Throwable cause)
    {
        return new SQLException(INVALID_PREFIX + detail, cause);
    }

    private record UserCounts(long total, long primary, long ordinary)
    {
    }
}

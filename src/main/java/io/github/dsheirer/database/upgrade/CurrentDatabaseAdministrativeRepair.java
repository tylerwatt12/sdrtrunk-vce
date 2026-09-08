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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.gui.setup.SetupProgress;
import io.github.dsheirer.icon.IconSet;
import io.github.dsheirer.web.auth.AccessTier;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebCapability;
import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded same-format repair for current administrative state whose individual components have safe defaults.
 * Credential-bearing web rows are retained whenever their account and verifier remain usable; a broken preference
 * document never causes that credential to be discarded.
 */
final class CurrentDatabaseAdministrativeRepair
{
    static final String STEP_ID = "repair-current-administrative-state";
    private static final String INITIAL_ADMIN_SETUP_KEY = "initial_admin_setup";
    private static final String PORTABLE_PREFERENCES_KEY = "portable_java_preferences_v1";
    private static final String DEFAULT_ICONS_KEY = "default";
    private static final String ICONS_INITIALIZED_KEY = "icon_config_initialized";
    private static final int MAXIMUM_SPECIAL_SETTING_BYTES = 65_536;
    private static final int MAXIMUM_APPLICATION_SETTING_BYTES = 4_194_304;
    private static final int MAXIMUM_ICON_JSON_BYTES = 4_194_304;
    private static final int MAXIMUM_METADATA_VALUE_BYTES = 65_536;
    private static final Set<String> SPECIAL_SETTING_KEYS = Set.of(
        SetupProgress.KEY, SpectrumSnapSettings.KEY, PORTABLE_PREFERENCES_KEY);
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CurrentDatabaseAdministrativeRepair()
    {
    }

    static Inspection inspect(Connection connection) throws SQLException
    {
        WebInspection web = inspectWebState(connection);
        CoreInspection core = inspectCoreRows(connection, web);
        boolean setupInvalid = invalidSetupProgress(connection);
        boolean spectrumInvalid = invalidSpectrumSettings(connection);
        boolean resetSetupForAuthentication = web.resetWebAccounts() > 0;
        return new Inspection(setupInvalid || resetSetupForAuthentication ? 1 : 0,
            spectrumInvalid ? 1 : 0, web.defaultedPreferences().size(),
            web.droppedSecondaryAccounts(), web.droppedPolicies(),
            web.policyRepairs().size(),
            web.resetWebAccounts(), core.repairAdminSetupMarker() ? 1 : 0,
            core.droppedSettings().size(), core.droppedIcons().size(), core.droppedMetadata().size(),
            core.defaultedSettingTimestamps().size() + core.defaultedIconTimestamps().size() +
                core.defaultedMetadataTimestamps().size(),
            core.clearIconInitialized() ? 1 : 0, web, core);
    }

    static Inspection repair(Connection connection) throws SQLException
    {
        Inspection before = inspect(connection);
        if(!before.requiresRepair())
        {
            return before;
        }

        long now = Math.max(1, System.currentTimeMillis());
        if(before.defaultedSetupProgress() > 0)
        {
            SetupProgress.write(connection, SetupProgress.replacementReview());
        }
        if(before.defaultedSpectrumSettings() > 0)
        {
            SpectrumSnapSettings.write(connection, SpectrumSnapSettings.defaults());
        }
        repairWebState(connection, before.web(), now);
        if(before.defaultedAdminSetupMarker() > 0)
        {
            writeMetadata(connection, INITIAL_ADMIN_SETUP_KEY,
                before.web().usablePrimaryAfterRepair() ? "complete" : "required", now);
        }
        repairCoreRows(connection, before.core(), now);

        Inspection remaining = inspect(connection);
        if(remaining.requiresRepair())
        {
            throw new SQLException("Bounded current-format administrative repair did not restore every targeted " +
                "component");
        }
        return before;
    }

    static DatabaseMigrationChain.StepPreflight preflight(Inspection inspection)
    {
        return new DatabaseMigrationChain.StepPreflight(STEP_ID,
            "Repair recoverable current-format administrative components independently",
            DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.CURRENT_VERSION, effects(inspection));
    }

    static List<DatabaseMigrationEffect> effects(Inspection inspection)
    {
        return List.of(
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "unusable setup progress",
                inspection.defaultedSetupProgress(),
                "Replace only missing or malformed setup progress with a bounded review state"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "unusable spectrum-snap settings",
                inspection.defaultedSpectrumSettings(),
                "Replace only missing or malformed spectrum-snap settings with the supported default country"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT, "recoverable web-user state",
                inspection.defaultedWebPreferences(),
                "Preserve each usable account and password verifier while repairing only preferences or an " +
                    "exhausted revision"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "unusable secondary web accounts",
                inspection.droppedSecondaryWebAccounts(),
                "Preserve the usable primary administrator and discard only unusable or excess secondary accounts"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP, "unusable web access policy overrides",
                inspection.droppedWebPolicies(),
                "Discard only an override whose capability cannot be identified or no longer accepts overrides"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable web access policy values", inspection.defaultedWebPolicies(),
                "Preserve each known restriction while defaulting only its malformed tier or update timestamp"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.RESET,
                "web accounts with no usable primary administrator", inspection.resetWebAccounts(),
                "Require administrator password setup only when the primary credential cannot be retained safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "administrator password setup state", inspection.defaultedAdminSetupMarker(),
                "Normalize the marker and require a password only when no usable primary credential remains"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "malformed opaque application settings", inspection.droppedApplicationSettings(),
                "Preserve valid opaque settings and discard only rows that violate the current storage contract"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "malformed application icon sets", inspection.droppedApplicationIcons(),
                "Preserve usable icon sets and discard only rows that cannot be loaded safely"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DROP,
                "malformed non-structural metadata", inspection.droppedNonStructuralMetadata(),
                "Preserve valid metadata while discarding malformed rows unrelated to format identity"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "recoverable administrative update timestamps", inspection.defaultedAdministrativeTimestamps(),
                "Keep independently valid settings, icons, and metadata while rebasing only malformed bookkeeping"),
            new DatabaseMigrationEffect(DatabaseMigrationEffect.Kind.DEFAULT,
                "icon initialization marker after unusable default icons",
                inspection.clearedIconInitializedMarker(),
                "Allow the application to rebuild defaults when its stored default icon set was unusable"));
    }

    private static boolean invalidSetupProgress(Connection connection) throws SQLException
    {
        if(!validSpecialSettingStorage(connection, SetupProgress.KEY))
        {
            return true;
        }
        try
        {
            SetupProgress.read(connection);
            return false;
        }
        catch(SQLException ignored)
        {
            return true;
        }
    }

    private static boolean invalidSpectrumSettings(Connection connection) throws SQLException
    {
        if(!validSpecialSettingStorage(connection, SpectrumSnapSettings.KEY))
        {
            return true;
        }
        try
        {
            SpectrumSnapSettings.read(connection);
            return false;
        }
        catch(SQLException ignored)
        {
            return true;
        }
    }

    private static boolean validSpecialSettingStorage(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT updated_at_ms, typeof(key) AS key_type, typeof(settings_json) AS json_type,
                   typeof(updated_at_ms) AS updated_type,
                   CASE WHEN typeof(settings_json)='text'
                                  AND length(CAST(settings_json AS BLOB)) <= 65536
                        THEN json_valid(settings_json) ELSE 0 END AS valid_json,
                   length(CAST(settings_json AS BLOB)) AS json_bytes
            FROM application_settings WHERE key=?
            """))
        {
            statement.setString(1, key);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() && "text".equals(rows.getString("key_type")) &&
                    "text".equals(rows.getString("json_type")) &&
                    rows.getInt("valid_json") == 1 && rows.getLong("json_bytes") <= MAXIMUM_SPECIAL_SETTING_BYTES;
            }
        }
    }

    private static CoreInspection inspectCoreRows(Connection connection, WebInspection web) throws SQLException
    {
        List<Long> droppedSettings = new ArrayList<>();
        List<Long> defaultedSettingTimestamps = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT rowid AS physical_rowid,
                   CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256 THEN key END AS key,
                   updated_at_ms,
                   typeof(key) AS key_type, typeof(settings_json) AS json_type,
                   typeof(updated_at_ms) AS updated_type,
                   CASE WHEN typeof(settings_json)='text'
                                  AND length(CAST(settings_json AS BLOB)) <= 4194304
                        THEN json_valid(settings_json) ELSE 0 END AS valid_json,
                   length(CAST(settings_json AS BLOB)) AS json_bytes
            FROM application_settings ORDER BY rowid
            """))
        {
            while(rows.next())
            {
                String key = rows.getObject("key") instanceof String text ? text : null;
                if(PORTABLE_PREFERENCES_KEY.equals(key))
                {
                    //The portable-preference repair owns both this payload and its bookkeeping.
                    continue;
                }
                boolean validPayload = validKey(rows) && "text".equals(rows.getString("json_type")) &&
                    rows.getInt("valid_json") == 1 &&
                    rows.getLong("json_bytes") <= (SPECIAL_SETTING_KEYS.contains(key) ?
                        MAXIMUM_SPECIAL_SETTING_BYTES : MAXIMUM_APPLICATION_SETTING_BYTES);
                if(!validPayload)
                {
                    //Setup progress and spectrum settings have component-specific defaults written separately.
                    if(!SPECIAL_SETTING_KEYS.contains(key))
                    {
                        droppedSettings.add(rows.getLong("physical_rowid"));
                    }
                }
                else if(!validTimestamp(rows))
                {
                    defaultedSettingTimestamps.add(rows.getLong("physical_rowid"));
                }
            }
        }

        List<Long> droppedIcons = new ArrayList<>();
        List<Long> defaultedIconTimestamps = new ArrayList<>();
        boolean droppedDefaultIcons = false;
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT rowid AS physical_rowid,
                   CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256 THEN key END AS key,
                   CASE WHEN length(CAST(key AS BLOB)) <= 256 THEN CAST(key AS TEXT) END AS logical_key,
                   CASE WHEN typeof(icons_json)='text'
                              AND length(CAST(icons_json AS BLOB)) <= 4194304
                        THEN icons_json END AS icons_json,
                   updated_at_ms,
                   typeof(key) AS key_type, typeof(icons_json) AS json_type,
                   typeof(updated_at_ms) AS updated_type,
                   CASE WHEN typeof(icons_json)='text'
                                  AND length(CAST(icons_json AS BLOB)) <= 4194304
                        THEN json_valid(icons_json) ELSE 0 END AS valid_json,
                   CASE WHEN typeof(icons_json)='text'
                                  AND length(CAST(icons_json AS BLOB)) <= 4194304
                                  AND json_valid(icons_json)
                        THEN json_type(icons_json, '$') END AS root_type,
                   length(CAST(icons_json AS BLOB)) AS json_bytes
            FROM application_icons ORDER BY rowid
            """))
        {
            while(rows.next())
            {
                String key = rows.getObject("key") instanceof String text ? text : null;
                String logicalKey = rows.getObject("logical_key") instanceof String text ? text : null;
                boolean valid = validKey(rows) && "text".equals(rows.getString("json_type")) &&
                    rows.getInt("valid_json") == 1 && "object".equals(rows.getString("root_type")) &&
                    rows.getLong("json_bytes") <= MAXIMUM_ICON_JSON_BYTES;
                if(valid && DEFAULT_ICONS_KEY.equals(key))
                {
                    try
                    {
                        JsonNode json = JSON.readTree(rows.getString("icons_json"));
                        JSON.treeToValue(json, IconSet.class);
                    }
                    catch(IOException | RuntimeException exception)
                    {
                        valid = false;
                    }
                }
                if(!valid)
                {
                    droppedIcons.add(rows.getLong("physical_rowid"));
                    droppedDefaultIcons |= DEFAULT_ICONS_KEY.equals(logicalKey);
                }
                else if(!validTimestamp(rows))
                {
                    defaultedIconTimestamps.add(rows.getLong("physical_rowid"));
                }
            }
        }

        List<Long> droppedMetadata = new ArrayList<>();
        List<Long> defaultedMetadataTimestamps = new ArrayList<>();
        boolean initialMarkerPresent = false;
        boolean initialMarkerValid = false;
        String initialMarkerValue = null;
        boolean iconMarkerPresent = false;
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT rowid AS physical_rowid,
                   CASE WHEN typeof(key)='text' AND length(CAST(key AS BLOB)) <= 256 THEN key END AS key,
                   CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB)) <= 65536
                        THEN value END AS value,
                   length(CAST(value AS BLOB)) AS value_bytes, updated_at_ms,
                   typeof(key) AS key_type, typeof(value) AS value_type,
                   typeof(updated_at_ms) AS updated_type
            FROM database_metadata ORDER BY rowid
            """))
        {
            while(rows.next())
            {
                String key = rows.getObject("key") instanceof String text ? text : null;
                boolean validPayload = validKey(rows) && "text".equals(rows.getString("value_type")) &&
                    rows.getLong("value_bytes") <= MAXIMUM_METADATA_VALUE_BYTES;
                if(INITIAL_ADMIN_SETUP_KEY.equals(key))
                {
                    initialMarkerPresent = true;
                    initialMarkerValue = rows.getString("value");
                    initialMarkerValid = validPayload && Set.of("complete", "required").contains(initialMarkerValue);
                    if(initialMarkerValid && !validTimestamp(rows))
                    {
                        defaultedMetadataTimestamps.add(rows.getLong("physical_rowid"));
                    }
                    continue;
                }
                if(ICONS_INITIALIZED_KEY.equals(key))
                {
                    iconMarkerPresent = true;
                    if(droppedDefaultIcons)
                    {
                        continue;
                    }
                }
                if(DatabaseFormatCatalog.RETIRED_SUBSYSTEM_VERSION_KEYS.contains(key) ||
                    DatabaseFormatCatalog.RETIRED_TRUNKED_IDENTITY_BOUNDARY_KEY.equals(key))
                {
                    droppedMetadata.add(rows.getLong("physical_rowid"));
                    continue;
                }
                if(DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(key) ||
                    CurrentDatabaseDerivedStateRepair.isMetricBoundaryKey(key))
                {
                    //Format identity and boundary rows are admitted or repaired by their dedicated checks.
                    if(DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(key) && validPayload &&
                        !validTimestamp(rows))
                    {
                        defaultedMetadataTimestamps.add(rows.getLong("physical_rowid"));
                    }
                    continue;
                }
                if(!validPayload)
                {
                    droppedMetadata.add(rows.getLong("physical_rowid"));
                }
                else if(!validTimestamp(rows))
                {
                    defaultedMetadataTimestamps.add(rows.getLong("physical_rowid"));
                }
            }
        }

        boolean markerDisagreesWithAccounts = initialMarkerPresent &&
            (("complete".equals(initialMarkerValue) && !web.usablePrimaryAfterRepair()) ||
                ("required".equals(initialMarkerValue) && web.usablePrimaryAfterRepair()));
        boolean repairAdminMarker = web.resetWebAccounts() > 0 ||
            initialMarkerPresent && (!initialMarkerValid || markerDisagreesWithAccounts);
        return new CoreInspection(List.copyOf(droppedSettings), List.copyOf(droppedIcons),
            List.copyOf(droppedMetadata), List.copyOf(defaultedSettingTimestamps),
            List.copyOf(defaultedIconTimestamps), List.copyOf(defaultedMetadataTimestamps),
            droppedDefaultIcons && iconMarkerPresent, repairAdminMarker);
    }

    private static boolean validKey(ResultSet rows) throws SQLException
    {
        Object keyValue = rows.getObject("key");
        return "text".equals(rows.getString("key_type")) && keyValue instanceof String key &&
            !key.trim().isEmpty();
    }

    private static boolean validTimestamp(ResultSet rows) throws SQLException
    {
        return "integer".equals(rows.getString("updated_type")) && rows.getLong("updated_at_ms") > 0;
    }

    private static void repairCoreRows(Connection connection, CoreInspection inspection, long now) throws SQLException
    {
        deleteRows(connection, "application_settings", inspection.droppedSettings());
        deleteRows(connection, "application_icons", inspection.droppedIcons());
        deleteRows(connection, "database_metadata", inspection.droppedMetadata());
        repairTimestamps(connection, "application_settings", inspection.defaultedSettingTimestamps(), now);
        repairTimestamps(connection, "application_icons", inspection.defaultedIconTimestamps(), now);
        repairTimestamps(connection, "database_metadata", inspection.defaultedMetadataTimestamps(), now);
        if(inspection.clearIconInitialized())
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM database_metadata WHERE key=?"))
            {
                statement.setString(1, ICONS_INITIALIZED_KEY);
                statement.executeUpdate();
            }
        }
    }

    private static WebInspection inspectWebState(Connection connection) throws SQLException
    {
        List<Long> retainedUsers = new ArrayList<>();
        Map<Long,PreferenceRepair> preferenceRepairs = new LinkedHashMap<>();
        long total = 0;
        long droppedSecondary = 0;
        int ordinary = 0;
        boolean usablePrimary = false;
        boolean intendedPrimaryUnusable = false;

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT rowid AS physical_rowid, id,
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
                   CASE WHEN typeof(preferences_revision)='integer'
                        THEN preferences_revision END AS preferences_revision,
                   created_at_ms,
                   CASE WHEN typeof(updated_at_ms)='integer' THEN updated_at_ms END AS updated_at_ms,
                   typeof(id) AS id_type, typeof(username) AS username_type, typeof(tier) AS tier_type,
                   typeof(primary_admin) AS primary_admin_type,
                   typeof(credential_version) AS credential_version_type,
                   typeof(password_algorithm) AS password_algorithm_type,
                   typeof(password_iterations) AS password_iterations_type,
                   typeof(password_derived_key_bits) AS password_derived_key_bits_type,
                   typeof(password_salt) AS password_salt_type, typeof(password_hash) AS password_hash_type,
                   typeof(password_changed_at_ms) AS password_changed_at_type,
                   typeof(auth_revision) AS auth_revision_type,
                   typeof(preferences_json) AS preferences_json_type,
                   typeof(preferences_revision) AS preferences_revision_type,
                   typeof(created_at_ms) AS created_at_type, typeof(updated_at_ms) AS updated_at_type
            FROM web_user ORDER BY id, rowid
            """); ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                total++;
                Object primaryValue = rows.getObject("primary_admin");
                String username = rows.getObject("username") instanceof String text ? text : null;
                boolean intendedPrimary = "admin".equalsIgnoreCase(username) ||
                    primaryValue instanceof Number number && number.longValue() == 1;
                try
                {
                    Object identifier = rows.getObject("id");
                    if(!(identifier instanceof Byte || identifier instanceof Short ||
                        identifier instanceof Integer || identifier instanceof Long) ||
                        ((Number)identifier).longValue() <= 0 ||
                        ((Number)identifier).longValue() >= SqliteIdentityRepair.JSON_SAFE_INTEGER_MAXIMUM)
                    {
                        throw new SQLException("Web-user identity is outside the JSON-safe range");
                    }
                    boolean primary = Format5WebStateValidator.validateCurrentUserAccount(rows);
                    long rowId = rows.getLong("physical_rowid");
                    boolean invalidPreferenceDocument;
                    try
                    {
                        Format5WebStateValidator.validateCurrentUserPreferenceDocument(rows);
                        invalidPreferenceDocument = false;
                    }
                    catch(SQLException ignored)
                    {
                        invalidPreferenceDocument = true;
                    }
                    if(!primary && ordinary++ >= WebAccessService.MAXIMUM_USERS)
                    {
                        droppedSecondary++;
                    }
                    else
                    {
                        retainedUsers.add(rowId);
                        long storedRevision = rows.getLong("preferences_revision");
                        boolean missingRevision = rows.wasNull();
                        boolean validRevision = "integer".equals(rows.getString("preferences_revision_type")) &&
                            !missingRevision && storedRevision > 0 && storedRevision < Long.MAX_VALUE - 1;
                        long storedUpdatedAt = rows.getLong("updated_at_ms");
                        boolean missingUpdatedAt = rows.wasNull();
                        boolean validUpdatedAt = "integer".equals(rows.getString("updated_at_type")) &&
                            !missingUpdatedAt && storedUpdatedAt > 0;
                        long storedAuthRevision = rows.getLong("auth_revision");
                        boolean validAuthRevision = "integer".equals(rows.getString("auth_revision_type")) &&
                            storedAuthRevision > 0 && storedAuthRevision < Long.MAX_VALUE - 1;
                        long storedPasswordChangedAt = rows.getLong("password_changed_at_ms");
                        boolean validPasswordChangedAt = "integer".equals(rows.getString("password_changed_at_type")) &&
                            storedPasswordChangedAt > 0;
                        long storedCreatedAt = rows.getLong("created_at_ms");
                        boolean validCreatedAt = "integer".equals(rows.getString("created_at_type")) &&
                            storedCreatedAt > 0;
                        if(invalidPreferenceDocument || !validRevision || !validUpdatedAt || !validAuthRevision ||
                            !validPasswordChangedAt || !validCreatedAt)
                        {
                            long targetRevision = validRevision ? storedRevision : 1;
                            if(invalidPreferenceDocument)
                            {
                                targetRevision = validRevision && storedRevision < Long.MAX_VALUE - 2 ?
                                    storedRevision + 1 : 1;
                            }
                            preferenceRepairs.put(rowId, new PreferenceRepair(
                                invalidPreferenceDocument ? null : rows.getString("preferences_json"),
                                targetRevision, invalidPreferenceDocument || !validUpdatedAt ? 0 : storedUpdatedAt,
                                validAuthRevision ? null : 1L,
                                validPasswordChangedAt ? null : (long)0,
                                validCreatedAt ? null : (long)0));
                        }
                    }
                    usablePrimary |= primary;
                }
                catch(SQLException ignored)
                {
                    if(intendedPrimary)
                    {
                        intendedPrimaryUnusable = true;
                    }
                    else
                    {
                        droppedSecondary++;
                    }
                }
            }
        }

        if(total > 0 && (!usablePrimary || intendedPrimaryUnusable))
        {
            long policies = count(connection, "SELECT COUNT(*) FROM web_access_policy");
            return new WebInspection(Map.of(), List.of(), 0, policies, List.of(), Map.of(), total, false);
        }

        long policies = count(connection, "SELECT COUNT(*) FROM web_access_policy");
        List<String> retainedPolicyIds = new ArrayList<>();
        Map<String,PolicyRepair> policyRepairs = new LinkedHashMap<>();
        if(usablePrimary)
        {
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT CASE WHEN typeof(capability_id)='text'
                                      AND length(CAST(capability_id AS BLOB))<=64
                            THEN capability_id END AS capability_id,
                       CASE WHEN typeof(required_tier)='text'
                                      AND length(CAST(required_tier AS BLOB))<=16
                            THEN required_tier END AS required_tier,
                       CASE WHEN typeof(updated_at_ms)='integer' THEN updated_at_ms END AS updated_at_ms
                FROM web_access_policy ORDER BY capability_id
                """))
            {
                while(rows.next())
                {
                    String id = rows.getString("capability_id");
                    WebCapability capability = id != null ? WebCapability.fromId(id).orElse(null) : null;
                    if(capability == null || !capability.configurable())
                    {
                        continue;
                    }

                    AccessTier tier;
                    boolean defaulted = false;
                    try
                    {
                        tier = AccessTier.valueOf(rows.getString("required_tier"));
                    }
                    catch(IllegalArgumentException | NullPointerException exception)
                    {
                        tier = AccessTier.ADMIN;
                        defaulted = true;
                    }
                    if(tier == capability.defaultTier())
                    {
                        continue;
                    }

                    long updatedAt = rows.getLong("updated_at_ms");
                    if(rows.wasNull() || updatedAt <= 0)
                    {
                        updatedAt = 0;
                        defaulted = true;
                    }
                    retainedPolicyIds.add(id);
                    if(defaulted)
                    {
                        policyRepairs.put(id, new PolicyRepair(tier.name(), updatedAt));
                    }
                }
            }
        }
        long droppedPolicies = policies - retainedPolicyIds.size();
        return new WebInspection(Map.copyOf(preferenceRepairs), List.copyOf(retainedUsers), droppedSecondary,
            droppedPolicies, List.copyOf(retainedPolicyIds), Map.copyOf(policyRepairs), 0, usablePrimary);
    }

    private static void repairWebState(Connection connection, WebInspection inspection, long now)
        throws SQLException
    {
        if(inspection.resetWebAccounts() > 0)
        {
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("DELETE FROM web_access_policy");
                statement.executeUpdate("DELETE FROM web_user");
            }
            return;
        }

        deletePoliciesExcept(connection, inspection.retainedPolicyIds());
        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE web_access_policy SET required_tier=?, updated_at_ms=? WHERE capability_id=?
            """))
        {
            for(Map.Entry<String,PolicyRepair> entry: inspection.policyRepairs().entrySet())
            {
                statement.setString(1, entry.getValue().tier());
                statement.setLong(2, entry.getValue().updatedAtMs() > 0 ? entry.getValue().updatedAtMs() : now);
                statement.setString(3, entry.getKey());
                if(statement.executeUpdate() != 1)
                {
                    throw new SQLException("Accepted web access policy changed during current-format repair");
                }
            }
        }
        if(inspection.droppedSecondaryAccounts() > 0)
        {
            deleteUsersExcept(connection, inspection.retainedUserRowIds());
        }

        final String defaults;
        try
        {
            defaults = WebUserPreferencesCodec.encode(WebUserPreferences.defaults());
        }
        catch(IOException exception)
        {
            throw new SQLException("Unable to create default web-user preferences", exception);
        }
        try(PreparedStatement update = connection.prepareStatement("""
            UPDATE web_user
            SET preferences_json=?, preferences_revision=?, updated_at_ms=?,
                auth_revision=coalesce(?, auth_revision),
                password_changed_at_ms=coalesce(?, password_changed_at_ms),
                created_at_ms=coalesce(?, created_at_ms)
            WHERE rowid=?
            """))
        {
            for(Map.Entry<Long,PreferenceRepair> entry: inspection.preferenceRepairs().entrySet())
            {
                PreferenceRepair repair = entry.getValue();
                update.setString(1, repair.payload() != null ? repair.payload() : defaults);
                update.setLong(2, repair.revision());
                update.setLong(3, repair.updatedAtMs() > 0 ? repair.updatedAtMs() : now);
                if(repair.authRevision() == null)
                {
                    update.setNull(4, java.sql.Types.INTEGER);
                }
                else
                {
                    update.setLong(4, repair.authRevision());
                }
                if(repair.passwordChangedAtMs() == null)
                {
                    update.setNull(5, java.sql.Types.INTEGER);
                }
                else
                {
                    update.setLong(5, now);
                }
                if(repair.createdAtMs() == null)
                {
                    update.setNull(6, java.sql.Types.INTEGER);
                }
                else
                {
                    update.setLong(6, now);
                }
                update.setLong(7, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void deleteUsersExcept(Connection connection, List<Long> retainedRowIds) throws SQLException
    {
        if(retainedRowIds.isEmpty())
        {
            throw new SQLException("Cannot preserve web authentication without a retained primary administrator");
        }
        String placeholders = String.join(",", retainedRowIds.stream().map(ignored -> "?").toList());
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM web_user WHERE rowid NOT IN (" + placeholders + ")"))
        {
            int index = 1;
            for(long rowId: retainedRowIds)
            {
                statement.setLong(index++, rowId);
            }
            statement.executeUpdate();
        }
    }

    private static void deletePoliciesExcept(Connection connection, List<String> retainedIds) throws SQLException
    {
        if(retainedIds.isEmpty())
        {
            try(Statement statement = connection.createStatement())
            {
                statement.executeUpdate("DELETE FROM web_access_policy");
            }
            return;
        }
        String placeholders = String.join(",", retainedIds.stream().map(ignored -> "?").toList());
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM web_access_policy WHERE capability_id NOT IN (" + placeholders + ")"))
        {
            int index = 1;
            for(String id: retainedIds)
            {
                statement.setString(index++, id);
            }
            statement.executeUpdate();
        }
    }

    private static void deleteRows(Connection connection, String table, List<Long> rowIds) throws SQLException
    {
        if(rowIds.isEmpty())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE rowid=?"))
        {
            for(long rowId: rowIds)
            {
                statement.setLong(1, rowId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void repairTimestamps(Connection connection, String table, List<Long> rowIds, long now)
        throws SQLException
    {
        if(rowIds.isEmpty())
        {
            return;
        }
        try(PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + table + " SET updated_at_ms=? WHERE rowid=?"))
        {
            for(long rowId: rowIds)
            {
                statement.setLong(1, now);
                statement.setLong(2, rowId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void writeMetadata(Connection connection, String key, String value, long now) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata(key, value, updated_at_ms) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at_ms=excluded.updated_at_ms
            """))
        {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            if(!rows.next())
            {
                throw new SQLException("Current-format administrative repair count returned no result");
            }
            return rows.getLong(1);
        }
    }

    record Inspection(long defaultedSetupProgress, long defaultedSpectrumSettings,
                      long defaultedWebPreferences, long droppedSecondaryWebAccounts,
                      long droppedWebPolicies, long defaultedWebPolicies, long resetWebAccounts,
                      long defaultedAdminSetupMarker,
                      long droppedApplicationSettings, long droppedApplicationIcons,
                      long droppedNonStructuralMetadata, long defaultedAdministrativeTimestamps,
                      long clearedIconInitializedMarker,
                      WebInspection web, CoreInspection core)
    {
        static Inspection none()
        {
            return new Inspection(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new WebInspection(Map.of(), List.of(), 0, 0, List.of(), Map.of(), 0, false),
                new CoreInspection(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, false));
        }

        boolean requiresRepair()
        {
            return defaultedSetupProgress > 0 || defaultedSpectrumSettings > 0 ||
                defaultedWebPreferences > 0 || droppedSecondaryWebAccounts > 0 ||
                droppedWebPolicies > 0 || defaultedWebPolicies > 0 || resetWebAccounts > 0 ||
                defaultedAdminSetupMarker > 0 ||
                droppedApplicationSettings > 0 || droppedApplicationIcons > 0 ||
                droppedNonStructuralMetadata > 0 || defaultedAdministrativeTimestamps > 0 ||
                clearedIconInitializedMarker > 0;
        }
    }

    private record WebInspection(Map<Long,PreferenceRepair> preferenceRepairs, List<Long> retainedUserRowIds,
                                 long droppedSecondaryAccounts, long droppedPolicies,
                                 List<String> retainedPolicyIds, Map<String,PolicyRepair> policyRepairs,
                                 long resetWebAccounts,
                                 boolean usablePrimaryAfterRepair)
    {
        private Map<Long,PreferenceRepair> defaultedPreferences()
        {
            return preferenceRepairs;
        }
    }

    private record PreferenceRepair(String payload, long revision, long updatedAtMs, Long authRevision,
                                    Long passwordChangedAtMs, Long createdAtMs)
    {
    }

    private record PolicyRepair(String tier, long updatedAtMs)
    {
    }

    private record CoreInspection(List<Long> droppedSettings, List<Long> droppedIcons,
                                  List<Long> droppedMetadata, List<Long> defaultedSettingTimestamps,
                                  List<Long> defaultedIconTimestamps, List<Long> defaultedMetadataTimestamps,
                                  boolean clearIconInitialized,
                                  boolean repairAdminSetupMarker)
    {
    }
}

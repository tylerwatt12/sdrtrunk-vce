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

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Authoritative catalog for every supported whole-file SQLite database format.
 *
 * <p>The complete schema fingerprint is the structural identity.  Subsystem metadata and the critical invariants
 * below prevent a structurally plausible mixed or partially migrated database from being admitted.  Release-channel
 * names and file names are deliberately not inputs.</p>
 */
public final class DatabaseFormatCatalog
{
    public static final String FORMAT_VERSION_KEY = "database_format_version";
    public static final int CURRENT_VERSION = 2;

    private static final String FORMAT_1_FINGERPRINT =
        "ef9197c7cee7261cdda03a395b6552754f3607f6c0053acbe21c273e4242ce3a";
    private static final String FORMAT_2_FINGERPRINT =
        "38294d5173dbaa550b7818006f09b9d2b83fe3c2bae1ba15b6c56416d8fd69dc";
    private static final Map<String,String> BASE_METADATA = Map.of(
        "configuration_schema_version", "2",
        "settings_schema_version", "2",
        "icon_schema_version", "2",
        "trunked_site_schema_version", "2",
        "dmr_activity_schema_version", "1");

    private static final FormatDescriptor FORMAT_1 = descriptor(1, "alpha8-shared",
        "Shared Alpha 8, Alpha 9, and Alpha 10 database format", FORMAT_1_FINGERPRINT, 4, 24,
        List.of("Alpha8", "Alpha9", "Alpha10", "diagnostics builds",
            "successful nightly publications through 2026-08-03"),
        "src/test/java/io/github/dsheirer/database/upgrade/Format1TestDatabase.java",
        List.of(
            "Preserve supported administrator-owned configuration",
            "Convert eligible catch-all aliases to list-level unmatched behavior",
            "Drop retired fully-qualified alias matchers and their dependent stream routes",
            "Retain playback enablement while dropping retired numeric priority ordering",
            "Reset reproducible trunked-identity evidence and P25 affiliations; live traffic rebuilds current state"));
    private static final FormatDescriptor FORMAT_2 = descriptor(2, "scan-lists-p25-v26",
        "Scan-list and protocol-neutral identity database format", FORMAT_2_FINGERPRINT, 6, 26,
        List.of("b131d0927", "6b3500f4c", "successful nightly publications 2026-08-19 through 2026-08-25"),
        "src/test/java/io/github/dsheirer/database/upgrade/Format2TestDatabase.java",
        List.of(
            "Preserve administrator-owned configuration and bounded activity data",
            "Adopt the authoritative global marker without changing an exact markerless format-2 layout"));
    private static final List<FormatDescriptor> FORMATS = List.of(FORMAT_1, FORMAT_2);

    private static final Map<Integer,FormatDescriptor> BY_VERSION = Map.of(
        FORMAT_1.version(), FORMAT_1,
        FORMAT_2.version(), FORMAT_2);
    /* Several marker-bearing semantic formats may intentionally share one DDL fingerprint. */
    private static final Map<String,List<FormatDescriptor>> BY_FINGERPRINT = formatsByFingerprint();

    /** Exact investigated states which must never be guessed into a supported format. */
    private static final Map<String,String> KNOWN_UNSUPPORTED_FINGERPRINTS = Map.of(
        "46fa0cf3e890e3b6fbf91a5b38fa0de87189c5396cce4828ee868eb07c6a5b04",
            "unreleased development schema at 7ec7b887",
        "cec72fb1e4f3438738c9187953f3a3ad84ad0ca176ac0b5b710b3173382fb834",
            "unreleased development schema at 51d33b52",
        "185142536870e4ae6ce9bc1bf55c4d21e9ece966d69cf1ad4df36cb76455093f",
            "unreleased development schema at 07125683",
        "4413598b7db3a7c4de9514fbf8ed016f45eb966d8f44b45a56477e74ef1ff578",
            "unreleased development schema at 342e8116",
        "da64bc56c921d22498b92a742151a6816979329059ccaf1893dbf7699a5f0b0e",
            "investigated pre-format-2 Alias v5/P25 v26 schema at 6c0d291b");

    private DatabaseFormatCatalog()
    {
    }

    /** Inspects without modifying the database. */
    public static DetectedFormat inspect(Connection connection) throws SQLException
    {
        String marker = metadataIfAvailable(connection, FORMAT_VERSION_KEY);
        Integer markedVersion = marker == null ? null : parseMarker(marker);

        //Read the authoritative marker before fingerprint lookup so an older build gives a precise forward-only
        //refusal even when it has never seen the newer layout's fingerprint.
        if(markedVersion != null && markedVersion > CURRENT_VERSION)
        {
            throw new FormatRejectionException("SQLite database format version " + markedVersion +
                " is newer than this build supports (current " + CURRENT_VERSION + ")");
        }

        String fingerprint = SqliteSchemaValidator.fingerprint(connection);
        List<FormatDescriptor> candidates = BY_FINGERPRINT.get(fingerprint);

        if(candidates == null)
        {
            String knownState = KNOWN_UNSUPPORTED_FINGERPRINTS.get(fingerprint);

            if(knownState != null)
            {
                throw new FormatRejectionException("Known but unsupported SQLite database format [" + knownState + "] (" +
                    fingerprint + "); add an explicit catalog entry and adjacent migration before using it");
            }

            throw new FormatRejectionException("Unrecognized SQLite database schema fingerprint (" + fingerprint + ")");
        }

        boolean markerPresent = marker != null;

        if(markerPresent)
        {
            FormatDescriptor descriptor = BY_VERSION.get(markedVersion);

            if(descriptor == null)
            {
                throw new FormatRejectionException("SQLite database format marker " + markedVersion +
                    " is not registered by this build");
            }

            if(!descriptor.fingerprint().equals(fingerprint))
            {
                throw new FormatRejectionException("SQLite database format marker " + markedVersion +
                    " [" + descriptor.id() + "] does not match schema fingerprint " + fingerprint +
                    "; the database is mixed or partially migrated");
            }

            validateMetadata(connection, descriptor);
            validateInvariants(connection, descriptor);
            return new DetectedFormat(descriptor, true);
        }

        List<FormatDescriptor> matches = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for(FormatDescriptor candidate: candidates)
        {
            try
            {
                validateMetadata(connection, candidate);
                validateInvariants(connection, candidate);
                matches.add(candidate);
            }
            catch(FormatRejectionException e)
            {
                rejections.add(e.getMessage());
            }
        }

        if(matches.size() == 1)
        {
            return new DetectedFormat(matches.getFirst(), false);
        }

        if(matches.isEmpty())
        {
            throw new FormatRejectionException("Markerless SQLite schema fingerprint " + fingerprint +
                " does not satisfy any matching catalog entry: " + String.join("; ", rejections));
        }

        throw new FormatRejectionException("Markerless SQLite schema fingerprint " + fingerprint +
            " is ambiguous across formats " + matches.stream().map(format -> Integer.toString(format.version()))
                .toList() + "; an authoritative " + FORMAT_VERSION_KEY + " marker is required");
    }

    /** Current catalog descriptor. */
    public static FormatDescriptor current()
    {
        return FORMAT_2;
    }

    /** Ordered manifest used by completeness tests and migration UX. */
    public static List<FormatDescriptor> formats()
    {
        return FORMATS;
    }

    /** Requires both the exact current signature and its authoritative global marker. */
    public static DetectedFormat requireCurrent(Connection connection) throws SQLException
    {
        DetectedFormat detected = inspect(connection);

        if(detected.version() != CURRENT_VERSION)
        {
            throw new SQLException("SQLite database format " + detected.version() + " [" + detected.id() +
                "] requires migration to current format " + CURRENT_VERSION);
        }

        if(!detected.markerPresent())
        {
            throw new SQLException("Current-layout SQLite database is missing authoritative metadata [" +
                FORMAT_VERSION_KEY + "]; run the Application Migrator to adopt it safely");
        }

        return detected;
    }

    /** Writes only the whole-file format marker. Existing schema mutation remains the migrator's responsibility. */
    public static void stamp(Connection connection, int version) throws SQLException
    {
        FormatDescriptor descriptor = BY_VERSION.get(version);

        if(descriptor == null)
        {
            throw new SQLException("Cannot stamp unregistered SQLite database format version " + version);
        }

        String fingerprint = SqliteSchemaValidator.fingerprint(connection);

        if(!descriptor.fingerprint().equals(fingerprint))
        {
            throw new SQLException("Cannot stamp SQLite database format " + version + " [" + descriptor.id() +
                "]: schema fingerprint is " + fingerprint + "; expected " + descriptor.fingerprint());
        }

        validateMetadata(connection, descriptor);
        validateInvariants(connection, descriptor);
        String existing = metadata(connection, FORMAT_VERSION_KEY);

        if(existing != null)
        {
            int existingVersion = parseMarker(existing);

            if(existingVersion != version && existingVersion != version - 1)
            {
                throw new SQLException("Cannot replace SQLite database format marker " + existing + " with " +
                    version + " without an adjacent migration");
            }
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO database_metadata (key, value, updated_at_ms)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at_ms = excluded.updated_at_ms
            """))
        {
            statement.setString(1, FORMAT_VERSION_KEY);
            statement.setString(2, Integer.toString(version));
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    static FormatDescriptor requireVersion(int version) throws SQLException
    {
        FormatDescriptor descriptor = BY_VERSION.get(version);

        if(descriptor == null)
        {
            throw new SQLException("No registered SQLite database format version " + version);
        }

        return descriptor;
    }

    private static FormatDescriptor descriptor(int version, String id, String description, String fingerprint,
                                                int aliasVersion, int p25Version, List<String> sourceReferences,
                                                String fixtureResource, List<String> migrationPolicy)
    {
        Map<String,String> metadata = new LinkedHashMap<>(BASE_METADATA);
        metadata.put("alias_schema_version", Integer.toString(aliasVersion));
        metadata.put("p25_activity_schema_version", Integer.toString(p25Version));
        return new FormatDescriptor(version, id, description, fingerprint, Map.copyOf(metadata),
            List.copyOf(sourceReferences), fixtureResource, List.copyOf(migrationPolicy));
    }

    private static Map<String,List<FormatDescriptor>> formatsByFingerprint()
    {
        Map<String,List<FormatDescriptor>> grouped = new LinkedHashMap<>();

        for(FormatDescriptor format: FORMATS)
        {
            grouped.computeIfAbsent(format.fingerprint(), ignored -> new ArrayList<>()).add(format);
        }

        grouped.replaceAll((ignored, formats) -> List.copyOf(formats));
        return Map.copyOf(grouped);
    }

    private static void validateMetadata(Connection connection, FormatDescriptor descriptor) throws SQLException
    {
        for(Map.Entry<String,String> expected: descriptor.subsystemMetadata().entrySet())
        {
            String actual = metadata(connection, expected.getKey());

            if(!expected.getValue().equals(actual))
            {
                throw new FormatRejectionException("SQLite schema format [" + descriptor.id() + "] metadata [" +
                    expected.getKey() + "] is " + actual + "; expected " + expected.getValue() +
                    "; the database is mixed or partially migrated");
            }
        }
    }

    private static void validateInvariants(Connection connection, FormatDescriptor descriptor) throws SQLException
    {
        requirePositiveMetadata(connection, "p25_call_output_metrics_started_at_ms", descriptor);
        requirePositiveMetadata(connection, "all_mode_call_output_metrics_started_at_ms", descriptor);
        requirePositiveMetadata(connection, "trunked_identity_metrics_started_at_ms", descriptor);

        if(descriptor.version() >= 2)
        {
            long defaultScanLists = scalarLong(connection,
                "SELECT COUNT(*) FROM scan_list WHERE is_default = 1");

            if(defaultScanLists != 1)
            {
                throw new FormatRejectionException("SQLite schema format [" + descriptor.id() + "] must contain exactly one " +
                    "Default scan list; found " + defaultScanLists);
            }
        }
    }

    private static int parseMarker(String marker) throws SQLException
    {
        try
        {
            int parsed = Integer.parseInt(marker);

            if(parsed <= 0 || !Integer.toString(parsed).equals(marker))
            {
                throw new NumberFormatException("non-canonical positive integer");
            }

            return parsed;
        }
        catch(NumberFormatException e)
        {
            throw new FormatRejectionException("SQLite database metadata [" + FORMAT_VERSION_KEY + "] is not a canonical " +
                "positive integer: " + marker, e);
        }
    }

    private static String metadata(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement =
                connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String metadataIfAvailable(Connection connection, String key) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT 1 FROM sqlite_master WHERE type='table' AND name='database_metadata'
            """))
        {
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(!resultSet.next())
                {
                    return null;
                }
            }
        }

        return metadata(connection, key);
    }

    private static void requirePositiveMetadata(Connection connection, String key, FormatDescriptor descriptor)
        throws SQLException
    {
        String value = metadata(connection, key);

        try
        {
            if(value == null || Long.parseLong(value) <= 0)
            {
                throw new FormatRejectionException("SQLite schema format [" + descriptor.id() + "] metadata [" + key +
                    "] must be a positive timestamp");
            }
        }
        catch(NumberFormatException e)
        {
            throw new FormatRejectionException("SQLite schema format [" + descriptor.id() + "] metadata [" + key +
                "] is not a valid timestamp", e);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException
    {
        try(java.sql.Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    /** Immutable checked-in format manifest entry. */
    public record FormatDescriptor(int version, String id, String description, String fingerprint,
                                   Map<String,String> subsystemMetadata, List<String> sourceReferences,
                                   String fixtureResource, List<String> migrationPolicy)
    {
    }

    /** Result of exact, read-only catalog inspection. */
    public record DetectedFormat(FormatDescriptor descriptor, boolean markerPresent)
    {
        public int version()
        {
            return descriptor.version();
        }

        public String id()
        {
            return descriptor.id();
        }

        public String description()
        {
            return descriptor.description();
        }

        public boolean requiresMigration()
        {
            return descriptor.version() < CURRENT_VERSION || !markerPresent;
        }
    }

    /** A structurally readable database that the exact format catalog deliberately refuses. */
    public static final class FormatRejectionException extends SQLException
    {
        private FormatRejectionException(String message)
        {
            super(message);
        }

        private FormatRejectionException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}

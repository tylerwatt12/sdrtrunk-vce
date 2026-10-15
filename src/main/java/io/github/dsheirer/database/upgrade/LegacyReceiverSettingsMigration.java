/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Profile-scoped compatibility input for the Application Migrator, never a runtime settings fallback. */
final class LegacyReceiverSettingsMigration
{
    private static final String SNAPSHOT_DIRECTORY = ".legacy-receiver-settings";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String,Artifact> ARTIFACTS = Map.of(
        ReceiverSettingsSchema.REMOTE_LINKS_KEY,
        new Artifact("remote-links/settings.json", "Remote Links", 256_000),
        ReceiverSettingsSchema.PAGING_TONES_KEY,
        new Artifact("audio-analysis/paging-tones.json", "Paging-tone", 4096),
        ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
        new Artifact("audio-models/selection.json", "Voice-enhancement", 4096));

    private LegacyReceiverSettingsMigration() {}

    static Set<String> missingKeys(Connection connection) throws SQLException
    {
        Set<String> missing = new LinkedHashSet<>();
        try(var query = connection.prepareStatement("SELECT 1 FROM application_settings WHERE key=?"))
        {
            for(String key: ReceiverSettingsSchema.KEYS)
            {
                query.setString(1, key);
                try(var row = query.executeQuery()) { if(!row.next()) missing.add(key); }
            }
        }
        return Set.copyOf(missing);
    }

    static Snapshot capture(Path root, Set<String> keys) throws IOException
    {
        Map<String,String> values = new LinkedHashMap<>();
        Map<String,String> fingerprints = new LinkedHashMap<>();
        for(String key: keys)
        {
            Artifact artifact = artifact(key);
            byte[] bytes = read(root, Path.of(artifact.relativePath()), artifact.maximumBytes(), artifact.label());
            fingerprints.put(key, bytes == null ? "missing" : sha256(bytes));
            if(bytes == null) continue;
            try
            {
                JsonNode legacy = JSON.readTree(bytes);
                JsonNode current = legacy;
                if(!ReceiverSettingsSchema.REMOTE_LINKS_KEY.equals(key))
                {
                    if(legacy == null || !legacy.isObject() || !legacy.path("version").isInt() ||
                        !legacy.path("enabled").isBoolean()) throw new IOException();
                    int version = legacy.path("version").intValue();
                    boolean voice = ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY.equals(key);
                    if(voice ? version == 1 ? legacy.size() != 3 || !legacy.path("bundle_sha256").isTextual() :
                        version != 2 || legacy.size() != 2 : version != 1 || legacy.size() != 2)
                        throw new IOException();
                    current = JSON.createObjectNode().put("enabled", legacy.path("enabled").booleanValue());
                }
                String value = JSON.writeValueAsString(current);
                ReceiverSettingsSchema.validateJson(key, value);
                values.put(key, value);
            }
            catch(IOException | SQLException | RuntimeException failure)
            {
                //Parser causes and messages can quote pairing credentials. Retain only the fixed artifact label.
                throw invalid(artifact.label());
            }
        }
        return new Snapshot(values, fingerprints);
    }

    static Snapshot captureMissing(Path root, Path database, int sourceVersion) throws IOException, SQLException
    {
        if(sourceVersion > 52) return new Snapshot(Map.of());
        var config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties()))
        {
            return capture(root, missingKeys(connection));
        }
    }

    static Set<String> availableRecoveryKeys(Path root) throws IOException, SQLException
    {
        Set<String> defaults = new LinkedHashSet<>();
        var config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            io.github.dsheirer.database.SdrTrunkDatabasePath.getDatabasePath(root), config.toProperties());
            var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            for(String key: ReceiverSettingsSchema.KEYS)
            {
                query.setString(1, key);
                try(var row = query.executeQuery())
                {
                    if(!row.next()) continue;
                    String current = row.getString(1);
                    ReceiverSettingsSchema.validateJson(key, current);
                    if(JSON.readTree(current).equals(JSON.readTree(ReceiverSettingsSchema.defaultJson(key))))
                        defaults.add(key);
                }
            }
        }
        Set<String> available = new LinkedHashSet<>();
        for(String key: defaults)
        {
            try
            {
                if(capture(root, Set.of(key)).values().containsKey(key)) available.add(key);
            }
            catch(IOException ignored)
            {
                //Discovery is optional. Only a selected recovery or an actual missing-value import may refuse.
            }
        }
        return Set.copyOf(available);
    }

    /** Copies only validated bounded compatibility input into the private stage, leaving the source untouched. */
    static void stage(Snapshot snapshot, Path stageRoot) throws IOException
    {
        if(snapshot.values().isEmpty()) return;
        Path directory = stageRoot.resolve(SNAPSHOT_DIRECTORY);
        Files.createDirectory(directory);
        FileAccessAttributeSnapshot.restrictPrivateDirectory(directory);
        for(var entry: snapshot.values().entrySet())
        {
            Path file = directory.resolve(entry.getKey() + ".json");
            Files.writeString(file, entry.getValue());
            FileAccessAttributeSnapshot.restrictSensitiveFile(file);
        }
    }

    static Snapshot staged(Path stageRoot) throws IOException
    {
        Map<String,String> values = new LinkedHashMap<>();
        for(String key: ReceiverSettingsSchema.KEYS)
        {
            Artifact artifact = artifact(key);
            byte[] bytes = read(stageRoot, Path.of(SNAPSHOT_DIRECTORY, key + ".json"), 256_000, artifact.label());
            if(bytes == null) continue;
            try
            {
                String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                ReceiverSettingsSchema.validateJson(key, json);
                values.put(key, json);
            }
            catch(SQLException | RuntimeException failure) { throw invalid(artifact.label()); }
        }
        return new Snapshot(Map.copyOf(values));
    }

    static void removeStage(Path stageRoot) throws IOException
    {
        Path directory = stageRoot.resolve(SNAPSHOT_DIRECTORY);
        for(String key: ReceiverSettingsSchema.KEYS) Files.deleteIfExists(directory.resolve(key + ".json"));
        Files.deleteIfExists(directory);
    }

    private static byte[] read(Path root, Path relative, int maximumBytes, String label) throws IOException
    {
        Path file = root;
        try
        {
            for(Path part: relative)
            {
                file = file.resolve(part);
                if(Files.isSymbolicLink(file)) throw invalid(label);
                if(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            }
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if(!before.isRegularFile() || before.size() > maximumBytes) throw invalid(label);
            byte[] bytes;
            try(var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
            {
                bytes = input.readNBytes(maximumBytes + 1);
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if(bytes.length > maximumBytes || before.size() != after.size() ||
                !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw invalid(label);
            return bytes;
        }
        catch(IOException | RuntimeException failure) { throw invalid(label); }
    }

    private static Artifact artifact(String key) throws IOException
    {
        Artifact artifact = ARTIFACTS.get(key);
        if(artifact == null) throw new IOException("Unknown legacy receiver setting selection");
        return artifact;
    }

    private static IOException invalid(String label)
    {
        return new IOException(label + " legacy settings could not be imported safely; keep the original file and check its contents");
    }

    private static String sha256(byte[] bytes)
    {
        try
        {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Snapshot(Map<String,String> values, Map<String,String> fingerprints)
    {
        Snapshot { values = Map.copyOf(values); fingerprints = Map.copyOf(fingerprints); }
        Snapshot(Map<String,String> values) { this(values, Map.of()); }

        void requireUnchanged(Snapshot actual) throws IOException
        {
            if(!fingerprints.equals(actual.fingerprints()))
                throw new IOException("Legacy receiver settings changed after source review; close the previous application, review the folder again, and retry");
        }

        int importMissing(Connection connection) throws SQLException
        {
            int imported = 0;
            try(var insert = connection.prepareStatement("""
                INSERT INTO application_settings(key,settings_json,updated_at_ms)
                SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM application_settings WHERE key=?)
                """))
            {
                for(var entry: values.entrySet())
                {
                    insert.setString(1, entry.getKey());
                    insert.setString(2, entry.getValue());
                    insert.setLong(3, System.currentTimeMillis());
                    insert.setString(4, entry.getKey());
                    imported += insert.executeUpdate();
                }
            }
            return imported;
        }

        int recoverSelected(Connection connection, Map<String,String> expectedDefaults) throws SQLException, IOException
        {
            int recovered = 0;
            for(var expected: expectedDefaults.entrySet())
            {
                Artifact artifact = artifact(expected.getKey());
                String imported = values.get(expected.getKey());
                if(imported == null) throw invalid(artifact.label());
                try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
                {
                    query.setString(1, expected.getKey());
                    try(var row = query.executeQuery())
                    {
                        if(!row.next() || !JSON.readTree(expected.getValue()).equals(JSON.readTree(
                            ReceiverSettingsSchema.defaultJson(expected.getKey()))) ||
                            !JSON.readTree(expected.getValue()).equals(JSON.readTree(row.getString(1))))
                            throw new IOException(artifact.label() + " database settings changed; review recovery before trying again");
                    }
                }
                catch(IOException | RuntimeException failure)
                {
                    throw new IOException(artifact.label() + " database settings changed; review recovery before trying again");
                }
                try(var update = connection.prepareStatement(
                    "UPDATE application_settings SET settings_json=?,updated_at_ms=? WHERE key=?"))
                {
                    update.setString(1, imported);
                    update.setLong(2, System.currentTimeMillis());
                    update.setString(3, expected.getKey());
                    recovered += update.executeUpdate();
                }
            }
            return recovered;
        }

        @Override public String toString() { return "Legacy receiver settings snapshot[values redacted]"; }
    }

    private record Artifact(String relativePath, String label, int maximumBytes) {}
}

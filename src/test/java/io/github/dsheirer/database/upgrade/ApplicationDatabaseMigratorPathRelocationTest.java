/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.portable.PortableFileIdentity;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@Tag("platform-filesystem")
class ApplicationDatabaseMigratorPathRelocationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void sourceParentAliasStillRelocatesCanonicalSavedOutputModuleAndTunerPaths() throws Exception
    {
        for(MigrationContent content: List.of(MigrationContent.FULL, MigrationContent.CONFIGURATION_ONLY))
        {
            Path area = Files.createDirectories(temporary.resolve(content.name())).toRealPath();
            Path original = Files.createDirectory(area.resolve("original"));
            Path alias = area.resolve("selected-installation");
            try { Files.createSymbolicLink(alias, original); }
            catch(IOException | UnsupportedOperationException | SecurityException unsupported)
            { Assumptions.abort("Installation-parent aliases are unavailable"); }
            Path source = original.resolve("data"), selectedSource = alias.resolve("data");
            Path target = Files.createDirectory(area.resolve("new")).resolve("data");
            Format64TestDatabase.create(source.resolve("database/sdrtrunk.sqlite"));
            Path external = Files.createDirectory(area.resolve("external"));
            for(String file: List.of("recordings/classic.wav", "recordings-managed/saved.mp3",
                "jmbe/library.jar", "modules/voice.jar", "recording_tuners/baseband.wav"))
            {
                Path path = source.resolve(file);
                Files.createDirectories(path.getParent());
                Files.writeString(path, file);
            }
            settings(source, "portable_java_preferences_v1", Map.of("directories", Map.of(
                "directory.recording", source.resolve("recordings").toString(),
                "directory.recording.managed", source.resolve("recordings-managed").toString(),
                "directory.streaming", external.toString(),
                "path.jmbe.library.primary", source.resolve("jmbe/library.jar").toString(),
                "path.voice.decryption.module.primary", source.resolve("modules/voice.jar").toString(),
                "path.jmbe.library.relative", "jmbe/relative.jar")));
            settings(source, "tuner.settings", Map.of("tunerConfigurations", List.of(Map.of(
                "type", "recordingTunerConfiguration", "uniqueID", "contained",
                "path", source.resolve("recording_tuners/baseband.wav").toString()))));
            assertTrue(Files.isSameFile(source, selectedSource));

            var result = VceUpgradeService.upgrade(selectedSource, target, content, true, null, null);

            try(PortableDataRootLock ignored = result.lock())
            {
                JsonNode paths = setting(target, "portable_java_preferences_v1").path("directories");
                assertEquals(target.resolve("recordings").toString(), paths.path("directory.recording").asText());
                assertEquals(target.resolve("recordings-managed").toString(),
                    paths.path("directory.recording.managed").asText());
                assertEquals(target.resolve("jmbe/library.jar").toString(),
                    paths.path("path.jmbe.library.primary").asText());
                assertEquals(target.resolve("modules/voice.jar").toString(),
                    paths.path("path.voice.decryption.module.primary").asText());
                assertEquals(external.toString(), paths.path("directory.streaming").asText());
                assertEquals("jmbe/relative.jar", paths.path("path.jmbe.library.relative").asText());
                assertEquals(target.resolve("recording_tuners/baseband.wav").toString(),
                    setting(target, "tuner.settings").path("tunerConfigurations").get(0).path("path").asText());
                assertEquals("recordings-managed/saved.mp3", Files.readString(target.resolve("recordings-managed/saved.mp3")));
                assertFalse(Files.exists(source));
            }
        }
    }

    @Test
    void populatedOlderCatalogAndAudioRemainUsableAfterBothUpgradeChoices() throws Exception
    {
        for(MigrationContent content: List.of(MigrationContent.FULL, MigrationContent.CONFIGURATION_ONLY))
        {
            for(boolean external: List.of(false, true))
            {
                Path area = Files.createDirectories(temporary.resolve(content.name() + "-" + external)).toRealPath();
                Path source = Files.createDirectory(area.resolve("original")).resolve("data");
                Path target = Files.createDirectory(area.resolve("new")).resolve("data");
                Format64TestDatabase.create(source.resolve("database/sdrtrunk.sqlite"));
                Path recordings = external ? Files.createDirectory(area.resolve("external-audio")) :
                    source.resolve("custom-audio");
                Path expectedRecordings = external ? recordings : target.resolve("custom-audio");
                settings(source, "portable_java_preferences_v1", Map.of("directories", Map.of(
                    "directory.recording.managed", recordings.toString())));
                Path catalog = source.resolve("database/managed-recordings.sqlite");
                try(var input = getClass().getResourceAsStream("/database/managed-recordings-format-8.sql");
                    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + catalog);
                    var statement = connection.createStatement())
                {
                    assertNotNull(input);
                    for(String sql: new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList())
                        statement.execute(sql);
                    //The dump pairs call 42's encoded symbols with nominal PCM context. Real producers have
                    //always rejected that combination; give those retained encoded facts their matching context.
                    statement.executeUpdate("UPDATE recording_tone_sequence SET source_code=2,method_code=2 WHERE call_id=42");
                    ManagedRecordingSchema.validate(connection, 8);
                }
                List<String> names = List.of("fixture/one.mp3", "fixture/two.wav", "fixture/three.mp3");
                for(String name: names)
                {
                    Path audio = recordings.resolve(name);
                    Files.createDirectories(audio.getParent());
                    Files.write(audio, new byte[64]);
                }
                List<String> identities = names.stream().map(name -> {
                    try { return PortableFileIdentity.read(recordings.resolve(name)); }
                    catch(IOException failure) { throw new RuntimeException(failure); }
                }).toList();
                byte[] catalogBefore = Files.readAllBytes(catalog);

                var result = VceUpgradeService.upgrade(source, target, content, true, null, null);

                try(PortableDataRootLock ignored = result.lock())
                {
                    Path movedCatalog = target.resolve("database/managed-recordings.sqlite");
                    assertArrayEquals(catalogBefore, Files.readAllBytes(movedCatalog),
                        "Profile transfer must retain the original catalog before its explicit adjacent migration");
                    assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
                        ManagedRecordingCatalogMigrator.inspectForStartup(movedCatalog).state());
                    assertTrue(ManagedRecordingCatalogMigrator.migrate(movedCatalog, false).migrated());
                    try(ManagedRecordingCatalog opened = new ManagedRecordingCatalog(movedCatalog, expectedRecordings))
                    {
                        assertEquals(3, opened.search(ManagedRecordingCatalog.SearchFilter.builder()
                            .fromMs(0L).toMs(6000L).build()).calls().size());
                        var found = opened.search(ManagedRecordingCatalog.SearchFilter.builder()
                            .fromMs(0L).toMs(6000L).transcript("verbatim").build());
                        assertEquals(1, found.calls().size());
                        assertEquals(41, found.calls().getFirst().id());
                        assertEquals("p25_pcm_tone_v1",
                            ((Map<?,?>)opened.find(41).toMap().get("paging_tone_summary")).get("method"));
                        assertEquals("ambe_tone_v1",
                            ((Map<?,?>)opened.find(42).toMap().get("paging_tone_summary")).get("method"));
                        for(int index = 0; index < names.size(); index++)
                        {
                            Path audio = opened.audioPath(41 + index);
                            assertEquals(expectedRecordings.resolve(names.get(index)), audio);
                            assertEquals(identities.get(index), PortableFileIdentity.read(audio));
                            assertArrayEquals(new byte[64], Files.readAllBytes(audio));
                        }
                    }
                    assertEquals(expectedRecordings.toString(), setting(target, "portable_java_preferences_v1")
                        .path("directories").path("directory.recording.managed").asText());
                }
            }
        }
    }

    private static void settings(Path root, String key, Object value) throws Exception
    {
        try(Connection connection = open(root); var statement = connection.prepareStatement(
            "INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,1) " +
                "ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json"))
        {
            statement.setString(1, key);
            statement.setString(2, JSON.writeValueAsString(value));
            statement.executeUpdate();
        }
    }

    private static JsonNode setting(Path root, String key) throws Exception
    {
        try(Connection connection = open(root); var statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, key);
            try(var rows = statement.executeQuery())
            {
                assertTrue(rows.next());
                return JSON.readTree(rows.getString(1));
            }
        }
    }

    private static Connection open(Path root) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("database/sdrtrunk.sqlite")); }
}

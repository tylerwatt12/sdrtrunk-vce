/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.bugreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.ZonedDateTime;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BugReportBundleBuilderTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void createsFixedSanitizedBundleWithoutRawDatabaseOrExcludedFiles() throws Exception
    {
        Path dataRoot = mTemporaryDirectory.resolve("data");
        Path logs = dataRoot.resolve("logs");
        Path database = dataRoot.resolve("database").resolve("sdrtrunk.sqlite");
        Files.createDirectories(logs);
        Files.createDirectories(database.getParent());
        Files.writeString(logs.resolve("sdrtrunk_app.log"),
            "Startup complete password=log-secret serial=SER123\n", StandardCharsets.UTF_8);
        createDatabase(database);

        BufferedImage screenshot = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        BufferedImage userScreenshotOne = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        BufferedImage userScreenshotTwo = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        BugReportRequest request = new BugReportRequest("Tuner stopped", "Failure api_key=user-secret",
            "Restart the tuner", ZonedDateTime.now(), screenshot, List.of(userScreenshotOne, userScreenshotTwo));
        BugReportBundle bundle = new BugReportBundleBuilder(dataRoot, logs, database, null).build(request);

        assertTrue(Files.isRegularFile(bundle.path()));
        assertTrue(bundle.path().startsWith(dataRoot.resolve("bug_reports")));
        assertTrue(bundle.sizeBytes() > 0);

        try(ZipFile zip = new ZipFile(bundle.path().toFile(), StandardCharsets.UTF_8))
        {
            Map<String,ZipEntry> entries = entries(zip);
            assertNotNull(entries.get("manifest.json"));
            assertNotNull(entries.get("user-report.json"));
            assertNotNull(entries.get("system.json"));
            assertNotNull(entries.get("tuners.json"));
            assertNotNull(entries.get("screenshots/application.png"));
            assertNotNull(entries.get("screenshots/user-001.png"));
            assertNotNull(entries.get("screenshots/user-002.png"));
            assertNotNull(entries.get("logs/sdrtrunk_app.log"));
            assertNotNull(entries.get("configuration/configuration_broadcast_stream.json"));
            assertNotNull(entries.get("configuration/application_settings.json"));
            assertNotNull(entries.get("checksums.sha256"));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.endsWith(".sqlite")));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("vault") || name.contains("recording")));

            String allText = textEntries(zip, entries);
            assertFalse(allText.contains("log-secret"));
            assertFalse(allText.contains("stream-secret"));
            assertFalse(allText.contains("vault-secret"));
            assertFalse(allText.contains("user-secret"));
            assertTrue(allText.contains("SER123"));

            JsonNode manifest = new ObjectMapper().readTree(read(zip, entries.get("manifest.json")));
            assertEquals(BugReportConstants.EXCLUSION_NOTICE, manifest.get("exclusion_notice").textValue());
            assertFalse(manifest.get("optional_categories").booleanValue());
            assertFalse(manifest.get("tuner_serial_numbers_redacted").booleanValue());
            assertFalse(manifest.get("local_ip_addresses_included").booleanValue());
            assertFalse(manifest.get("disk_serial_numbers_included").booleanValue());
            assertFalse(manifest.get("raw_database_included").booleanValue());
            assertFalse(manifest.get("screenshot_redaction_applied").booleanValue());
            assertFalse(manifest.get("screenshot_source_metadata_retained").booleanValue());
            assertEquals(3, manifest.get("screenshots").size());
            assertEquals("automatic_application", manifest.at("/screenshots/0/source").textValue());
            assertEquals("user_added", manifest.at("/screenshots/1/source").textValue());

            JsonNode system = new ObjectMapper().readTree(read(zip, entries.get("system.json")));
            assertFalse(system.has("network_interfaces"));
            assertNotNull(system.at("/machine/operating_system/name").textValue());
            assertTrue(system.at("/machine/cpu").isObject());
            assertTrue(system.at("/machine/memory").isObject());
            assertTrue(system.at("/machine/data_storage/total_bytes").canConvertToLong());
            assertTrue(system.at("/machine/data_storage/used_bytes").canConvertToLong());
            assertFalse(system.at("/machine/data_storage/physical_disk").has("serial"));
        }
        finally
        {
            Files.deleteIfExists(bundle.path());
        }
    }

    private static void createDatabase(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream
                (sort_order, name, server_type, enabled, host, port, config_json)
                VALUES (0, 'Feed', 'RADIORESOLVE', 1, 'radio.example', 443, ?)
                """))
            {
                statement.setString(1, "{\"password\":\"stream-secret\",\"serialNumber\":\"SER123\"}");
                statement.executeUpdate();
            }

            try(PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms) VALUES (?, ?, 1)
                """))
            {
                statement.setString(1, "portable_java_preferences_v1");
                statement.setString(2, "{\"user/test\":{\"vault.saved.password\":\"vault-secret\"}}");
                statement.executeUpdate();
            }
        }
    }

    private static Map<String,ZipEntry> entries(ZipFile zip)
    {
        Map<String,ZipEntry> entries = new HashMap<>();
        Enumeration<? extends ZipEntry> available = zip.entries();

        while(available.hasMoreElements())
        {
            ZipEntry entry = available.nextElement();
            entries.put(entry.getName(), entry);
        }

        return entries;
    }

    private static String textEntries(ZipFile zip, Map<String,ZipEntry> entries) throws Exception
    {
        StringBuilder text = new StringBuilder();

        for(ZipEntry entry: entries.values())
        {
            if(!entry.getName().endsWith(".png"))
            {
                text.append(read(zip, entry));
            }
        }

        return text.toString();
    }

    private static String read(ZipFile zip, ZipEntry entry) throws Exception
    {
        try(var input = zip.getInputStream(entry))
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

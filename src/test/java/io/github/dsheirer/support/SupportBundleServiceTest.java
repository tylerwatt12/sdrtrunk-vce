/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkTestDatabase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportBundleServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void createsCuratedBundleWithoutDatabaseOrSecrets() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.db");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE database_metadata(key TEXT, value TEXT, updated_at_ms INTEGER)");
            statement.execute("INSERT INTO database_metadata VALUES('database_format_version','21',1)");
            statement.execute("CREATE TABLE alias_list(id INTEGER, name TEXT)");
            statement.execute("INSERT INTO alias_list VALUES(1,'Default NXDN')");
            statement.execute("CREATE TABLE receiver_activity_event(id INTEGER, timestamp_ms INTEGER, details TEXT)");
            statement.execute("INSERT INTO receiver_activity_event VALUES(1," + System.currentTimeMillis() +
                ",'recent call')");
            statement.execute("CREATE TABLE web_user(id INTEGER, username TEXT, password_verifier TEXT)");
            statement.execute("INSERT INTO web_user VALUES(1,'admin','never-include-this-verifier')");
            statement.execute("CREATE TABLE future_feature(id INTEGER, value TEXT)");
            statement.execute("INSERT INTO future_feature VALUES(1,'safe omission')");
        }
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("logs"));
        Files.writeString(logs.resolve("sdrtrunk_app.log"),
            "API key=never-include-this-key\nPath /Users/alice/private/data\n", StandardCharsets.UTF_8);
        SupportBundleService.Request request = new SupportBundleService.Request("NXDN recording", "a@example.com",
            "Audio and recordings", "Calls are not recording", "Audio plays but no recording is saved.",
            "Start the receiver and wait for a call.", EnumSet.of(SupportBundleService.Section.APPLICATION,
            SupportBundleService.Section.HEALTH, SupportBundleService.Section.SETUP,
            SupportBundleService.Section.ALIASES, SupportBundleService.Section.LOGS,
            SupportBundleService.Section.RECENT_ACTIVITY));

        try(SupportBundleService service = new SupportBundleService(database, logs,
            () -> Map.of("generated_at_ms", 1L, "summary", Map.of("active_count", 0))))
        {
            String id = service.generate(request);
            SupportBundleService.Status status = awaitReady(service, id);
            assertEquals("ready", status.state());
            assertTrue(status.bytes() > 0);
            SupportBundleService.PreparedDownload download = service.download(id);
            Map<String,String> entries = unzip(download.path());
            assertTrue(entries.containsKey("report.json"));
            assertTrue(entries.containsKey("application-and-computer.json"));
            assertTrue(entries.containsKey("receiver-setup/database-metadata.csv"));
            assertTrue(entries.containsKey("aliases-and-listening/alias-list.csv"));
            assertTrue(entries.containsKey("recent-activity-history/receiver-activity-event.csv"));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("web-user")));
            String all = String.join("\n", entries.values());
            assertFalse(all.contains("never-include-this-verifier"));
            assertFalse(all.contains("never-include-this-key"));
            assertFalse(all.contains("/Users/alice"));
            assertTrue(all.contains("future_feature"));
            assertTrue(all.contains("Not part of a support-bundle section"));

            service.cancel(id);
            assertFalse(Files.exists(download.path()));
            assertEquals("cancelled", service.status(id).state());
        }
    }

    @Test
    void validatesRequiredReportDetails()
    {
        assertThrows(IllegalArgumentException.class, () -> new SupportBundleService.Request("", "bad",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.APPLICATION)));
        assertThrows(IllegalArgumentException.class, () -> new SupportBundleService.Request("Title", "bad",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.APPLICATION)));
        SupportBundleService.Request normalized = new SupportBundleService.Request("Title", "a@example.com",
            "Other", "Other", "Description", "Steps", EnumSet.of(SupportBundleService.Section.RECENT_ACTIVITY,
            SupportBundleService.Section.FULL_ACTIVITY));
        assertTrue(normalized.sections().contains(SupportBundleService.Section.FULL_ACTIVITY));
        assertFalse(normalized.sections().contains(SupportBundleService.Section.RECENT_ACTIVITY));
    }

    @Test
    void currentAndFutureDatabaseTablesCannotBreakBundleCreation() throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(mTemporaryDirectory.resolve("current.sqlite"));
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("current-logs"));
        SupportBundleService.Request request = new SupportBundleService.Request("Current schema", "a@example.com",
            "Other", "Something else", "Check every current data area.", "Generate a support bundle.",
            EnumSet.allOf(SupportBundleService.Section.class));
        try(SupportBundleService service = new SupportBundleService(database, logs, Map::of))
        {
            String id = service.generate(request);
            SupportBundleService.Status status = awaitReady(service, id);
            assertEquals("ready", status.state(), status.message());
            Map<String,String> entries = unzip(service.download(id).path());
            assertTrue(entries.containsKey("bundle-summary.json"));
            assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("web-user") ||
                name.contains("application-settings") || name.contains("configuration-broadcast-stream")));
            assertTrue(entries.get("bundle-summary.json").contains("private_data_never_included"));
        }
    }

    private static SupportBundleService.Status awaitReady(SupportBundleService service, String id) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while(System.nanoTime() < deadline)
        {
            SupportBundleService.Status status = service.status(id);
            if(!"queued".equals(status.state()) && !"generating".equals(status.state())) return status;
            Thread.sleep(20);
        }
        throw new AssertionError("Support bundle did not finish");
    }

    private static Map<String,String> unzip(Path archive) throws Exception
    {
        Map<String,String> entries = new HashMap<>();
        try(ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive)))
        {
            ZipEntry entry;
            while((entry = zip.getNextEntry()) != null)
            {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                entries.put(entry.getName(), bytes.toString(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}

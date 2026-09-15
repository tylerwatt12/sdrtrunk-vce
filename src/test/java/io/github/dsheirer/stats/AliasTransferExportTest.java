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

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasTransferCsv;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.http.WebRequestSecurity;
import io.github.dsheirer.web.http.WebSessionHttpController;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasTransferExportTest
{
    private static final String EXPORT_TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void spoolsMoreThanOneHundredThousandRowsInBoundedBatches() throws Exception
    {
        int expected = 100_001;
        AtomicInteger largestBatch = new AtomicInteger();
        AtomicInteger batchCount = new AtomicInteger();
        AliasTransferExport.Prepared export = AliasTransferExport.prepare(mTemporaryFolder, 17, true, consumer ->
        {
            for(int start = 1; start <= expected; start += AliasTransferExport.BATCH_SIZE)
            {
                int end = Math.min(expected + 1, start + AliasTransferExport.BATCH_SIZE);
                List<Map<String,Object>> batch = new ArrayList<>(end - start);

                for(int value = start; value < end; value++)
                {
                    batch.add(row(value));
                }

                largestBatch.accumulateAndGet(batch.size(), Math::max);
                batchCount.incrementAndGet();
                consumer.accept(batch);
            }
        });

        try
        {
            assertEquals(expected, export.rowCount());
            assertEquals(Files.size(export.path()), export.byteCount());
            assertEquals("vce-alias-list-17-filtered.csv", export.fileName());
            assertEquals(AliasTransferExport.BATCH_SIZE, largestBatch.get());
            assertEquals(101, batchCount.get(), "The export source must retain only fixed 1,000-row batches");

            long rows = 0;

            try(BufferedReader reader = Files.newBufferedReader(export.path(), StandardCharsets.UTF_8))
            {
                assertEquals('\uFEFF', reader.read());

                try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                    .parse(reader))
                {
                    assertEquals(AliasTransferCsv.HEADERS, parser.getHeaderNames());

                    for(var record: parser)
                    {
                        rows++;

                        if(rows == 1)
                        {
                            assertEquals("Alias 1", record.get("name"));
                            assertEquals("1", record.get("value"));
                        }
                        else if(rows == expected)
                        {
                            assertEquals("Alias " + expected, record.get("name"));
                            assertEquals(Integer.toString(expected), record.get("value"));
                        }
                    }
                }
            }

            assertEquals(expected, rows);
            assertEquals(1, regularFileCount());
        }
        finally
        {
            export.close();
        }

        assertEquals(0, regularFileCount());
    }

    @Test
    void preservesImportableConfigurationFields() throws Exception
    {
        Map<String,Object> source = row(42);
        source.put("description", "=formula, \"quoted\"\nline");
        source.put("group", "'Operations");
        source.put("color", -123456L);
        source.put("icon_name", "Car");
        source.put("record_enabled", 1L);
        source.put("stream_as_talkgroup", 16_777_215L);
        source.put("scan_lists", List.of("Dispatch", "Fire"));
        source.put("broadcast_channels", List.of("Provider 2", "Provider 1"));
        AliasTransferExport.Prepared export = AliasTransferExport.prepare(mTemporaryFolder, 17, false,
            consumer -> consumer.accept(List.of(source)));

        try
        {
            var inputs = AliasTransferCsv.read(Files.newBufferedReader(export.path(), StandardCharsets.UTF_8),
                AliasTransferCsv.Format.VCE,
                new io.github.dsheirer.alias.AliasListDefinition("Destination",
                    io.github.dsheirer.alias.AliasListFamily.P25));
            var input = inputs.getFirst();
            Map<String,String> expected = AliasTransferExport.configurationRow(source);
            assertEquals(expected, AliasTransferCsv.fields(input.alias(), input.sourceAliasList(),
                input.scanLists(), input.streams()));
        }
        finally
        {
            export.close();
        }
    }

    @Test
    void duplicateMatchersArePreservedAndSourceFailuresLeaveNoPartialDownload() throws Exception
    {
        AliasTransferExport.Prepared duplicate = AliasTransferExport.prepare(mTemporaryFolder, 17, false,
            consumer -> consumer.accept(List.of(row(1), row(1))));
        assertEquals(2, duplicate.rowCount());
        duplicate.close();
        assertEquals(0, regularFileCount());

        assertThrows(SQLException.class, () -> AliasTransferExport.prepare(mTemporaryFolder, 17, false,
            consumer ->
            {
                consumer.accept(List.of(row(1)));
                throw new SQLException("injected query interruption");
            }));
        assertEquals(0, regularFileCount());
    }

    @Test
    void rejectsAnOversizedSourceBatchAndRemovesThePartialSpool() throws Exception
    {
        List<Map<String,Object>> oversized = new ArrayList<>(AliasTransferExport.BATCH_SIZE + 1);

        for(int value = 1; value <= AliasTransferExport.BATCH_SIZE + 1; value++)
        {
            oversized.add(row(value));
        }

        assertThrows(SQLException.class, () -> AliasTransferExport.prepare(mTemporaryFolder, 17, false,
            consumer -> consumer.accept(oversized)));
        assertEquals(0, regularFileCount());
    }

    @Test
    void databaseExportsUseDurableIdOrderForAllAndFilteredSelections() throws Exception
    {
        Path databasePath = mTemporaryFolder.resolve("ordered.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(databasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,color,matcher_type,protocol,value)
                VALUES
                    (30,1,'Zulu highest-ID winner',30,'TALKGROUP','APCO25',700),
                    (10,1,'Middle first occurrence',10,'TALKGROUP','APCO25',700),
                    (20,1,'Alpha second occurrence',20,'TALKGROUP','APCO25',700)
                """);
        }

        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), databasePath);
        List<String> expected = List.of("Middle first occurrence", "Alpha second occurrence",
            "Zulu highest-ID winner");

        try(AliasTransferExport.Prepared all = database.aliasTransferExport(
            new StatsRequest(Map.of("list", "1", "scope", "all"))))
        {
            assertEquals(expected, exportedNames(all.path()));
        }

        try(AliasTransferExport.Prepared filtered = database.aliasTransferExport(new StatsRequest(Map.of(
            "list", "1", "scope", "filtered", "sort", "name", "direction", "desc"))))
        {
            assertEquals(expected, exportedNames(filtered.path()),
                "Presentation sorting must not change importable duplicate precedence");
        }

        StatsApiException missing = assertThrows(StatsApiException.class, () -> database.aliasTransferExport(
            new StatsRequest(Map.of("list", "999999", "scope", "all"))));
        assertEquals(404, missing.status());
        assertEquals("Alias List was not found", missing.getMessage());
    }

    @Test
    void exportsDenseValidScanListConfigurationAcrossBoundedBatches() throws Exception
    {
        Path databasePath = mTemporaryFolder.resolve("dense-configuration.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(databasePath);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath))
        {
            connection.setAutoCommit(false);
            List<Long> scanListIds = new ArrayList<>();

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT id FROM scan_list ORDER BY id"))
            {
                while(resultSet.next())
                {
                    scanListIds.add(resultSet.getLong(1));
                }
            }

            try(PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO scan_list(sort_order,name,published,is_default) VALUES (?, ?, 1, 0)
                """))
            {
                for(int index = scanListIds.size(); index < 100; index++)
                {
                    insert.setInt(1, index);
                    insert.setString(2, "Dense export scan list " + index);
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            scanListIds.clear();
            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT id FROM scan_list ORDER BY id"))
            {
                while(resultSet.next())
                {
                    scanListIds.add(resultSet.getLong(1));
                }
            }
            assertEquals(100, scanListIds.size());

            try(PreparedStatement alias = connection.prepareStatement("""
                    INSERT INTO alias(id,alias_list_id,name,color,matcher_type,protocol,value)
                    VALUES (?,1,?,0,'TALKGROUP','APCO25',?)
                    """);
                PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO alias_scan_list_membership(alias_id,scan_list_id) VALUES (?,?)
                    """))
            {
                for(int index = 0; index < 201; index++)
                {
                    long aliasId = 10_000L + index;
                    alias.setLong(1, aliasId);
                    alias.setString(2, "Dense alias " + index);
                    alias.setInt(3, 1_000 + index);
                    alias.addBatch();

                    for(long scanListId: scanListIds)
                    {
                        membership.setLong(1, aliasId);
                        membership.setLong(2, scanListId);
                        membership.addBatch();
                    }
                }
                alias.executeBatch();
                membership.executeBatch();
            }
            connection.commit();
        }

        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), databasePath);
        try(AliasTransferExport.Prepared export = database.aliasTransferExport(new StatsRequest(Map.of(
            "list", "1", "scope", "all"))))
        {
            List<io.github.dsheirer.alias.AliasImportService.Input> inputs = AliasTransferCsv.read(
                Files.newBufferedReader(export.path(), StandardCharsets.UTF_8), AliasTransferCsv.Format.VCE,
                new io.github.dsheirer.alias.AliasListDefinition("P25", io.github.dsheirer.alias.AliasListFamily.P25));
            List<io.github.dsheirer.alias.AliasImportService.Input> dense = inputs.stream()
                .filter(input -> input.alias().getName().startsWith("Dense alias ")).toList();
            assertEquals(201, dense.size());
            assertTrue(dense.stream().allMatch(input -> input.scanLists().size() == 100));
        }
    }

    @Test
    void hundredThousandAliasDownloadStartsPromptlyAndStreamsToCompletion() throws Exception
    {
        Path databasePath = mTemporaryFolder.resolve("representative-export.sqlite");
        AliasActivityRepresentativeTestDatabase.Fixture fixture =
            AliasActivityRepresentativeTestDatabase.createExact(databasePath);
        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(), databasePath);
        WebAccessService accessService = new WebAccessService(databasePath);
        char[] password = "alias-export-performance-password".toCharArray();

        try
        {
            accessService.provisionOrResetPrimaryAdmin(password);
        }
        finally
        {
            Arrays.fill(password, '\u0000');
        }

        WebAuthenticationService authenticationService = new WebAuthenticationService(accessService);
        WebRequestSecurity requestSecurity = new WebRequestSecurity(accessService, authenticationService);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        new WebSessionHttpController(accessService, authenticationService, requestSecurity).register(server);
        new StatsApiV1Controller(database, Map::of, requestSecurity, null).register(server);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();
            String session = login(client, origin, "admin", "alias-export-performance-password");
            URI exportUri = origin.resolve(StatsApiV1.EXPORTS + "/alias-list.csv?list=" +
                fixture.aliasListId() + "&scope=all&export_token=" + EXPORT_TOKEN);

            long coldStart = System.nanoTime();
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(exportUri)
                .header("Cookie", session).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            long coldHeadersMilliseconds = elapsedMilliseconds(coldStart);
            assertEquals(200, response.statusCode());
            assertEquals(Integer.toString(fixture.aliasCount()),
                response.headers().firstValue("X-Export-Row-Count").orElseThrow());
            long coldBytes = response.headers().firstValueAsLong("Content-Length").orElseThrow();
            //The locally measured target is well below two seconds; hosted macOS runners can have much slower
            //temporary SQLite I/O, so retain a firm upper bound without making the release gate machine-specific.
            assertTrue(coldHeadersMilliseconds < 5_000,
                "Cold 100,000-Alias download headers took " + coldHeadersMilliseconds + " ms");

            long downloadedBytes;
            try(InputStream input = response.body())
            {
                downloadedBytes = input.transferTo(OutputStream.nullOutputStream());
            }
            long coldDownloadMilliseconds = elapsedMilliseconds(coldStart);
            assertEquals(coldBytes, downloadedBytes,
                "The native streamed response must deliver its declared complete length");

            long repeatedStart = System.nanoTime();
            try(AliasTransferExport.Prepared export = database.aliasTransferExport(new StatsRequest(Map.of(
                "list", Long.toString(fixture.aliasListId()), "scope", "all"))))
            {
                assertEquals(fixture.aliasCount(), export.rowCount());
                assertEquals(coldBytes, export.byteCount());
            }
            long repeatedMilliseconds = elapsedMilliseconds(repeatedStart);

            long filteredStart = System.nanoTime();
            long filteredRows;
            try(AliasTransferExport.Prepared export = database.aliasTransferExport(new StatsRequest(Map.of(
                "list", Long.toString(fixture.aliasListId()), "scope", "filtered", "group", "Dispatch"))))
            {
                filteredRows = export.rowCount();
                assertEquals(25_000, filteredRows,
                    "Filtered export must include every match beyond the old 10k limit");
            }
            long filteredMilliseconds = elapsedMilliseconds(filteredStart);
            System.out.printf("Representative Alias CSV: cold_headers=%dms cold_complete=%dms " +
                    "repeated_prepare=%dms filtered_prepare=%dms rows=%d filteredRows=%d bytes=%d%n",
                coldHeadersMilliseconds, coldDownloadMilliseconds, repeatedMilliseconds, filteredMilliseconds,
                fixture.aliasCount(), filteredRows, coldBytes);
            assertTrue(repeatedMilliseconds < 2_000,
                "Repeated 100,000-Alias preparation took " + repeatedMilliseconds + " ms");
            assertTrue(filteredMilliseconds < 2_000,
                "Filtered 25,000-Alias preparation took " + filteredMilliseconds + " ms");
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
            requestSecurity.close();
        }
    }

    @Test
    void rejectsInvalidRowsBeforeTheyCanBecomeDownloads()
    {
        Map<String,Object> invalid = row(1);
        invalid.put("record_enabled", 2);
        assertThrows(IllegalArgumentException.class, () -> AliasTransferExport.prepare(mTemporaryFolder, 17,
            false, consumer -> consumer.accept(List.of(invalid))));
        assertFalse(Files.exists(mTemporaryFolder.resolve("vce-alias-list-17.csv")));
    }

    private long regularFileCount() throws Exception
    {
        try(var files = Files.list(mTemporaryFolder))
        {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static List<String> exportedNames(Path path) throws Exception
    {
        try(BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            assertEquals('\uFEFF', reader.read());

            try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(reader))
            {
                List<String> names = new ArrayList<>();
                parser.forEach(row -> names.add(row.get("name")));
                return names;
            }
        }
    }

    private static long elapsedMilliseconds(long started)
    {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String login(HttpClient client, URI origin, String username, String password) throws Exception
    {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            Map.of("username", username, "password", password));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/api/v1/auth/login"))
            .header("Origin", origin.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static Map<String,Object> row(int value)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("alias_list_name", "County");
        row.put("name", "Alias " + value);
        row.put("description", null);
        row.put("group", "Dispatch");
        row.put("color", 0L);
        row.put("icon_name", null);
        row.put("matcher_type", "TALKGROUP");
        row.put("protocol", "APCO25");
        row.put("value", (long)value);
        row.put("min_value", null);
        row.put("max_value", null);
        row.put("text_value", null);
        row.put("numeric_value", null);
        row.put("tone_sequence", null);
        row.put("record_enabled", 0L);
        row.put("scan_lists", List.of());
        row.put("broadcast_channels", List.of());
        row.put("stream_as_talkgroup", null);
        return row;
    }
}

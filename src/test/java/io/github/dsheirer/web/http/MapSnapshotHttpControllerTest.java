/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.map.MapSnapshotService;
import io.github.dsheirer.map.StandardMapIconCatalog;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.junit.jupiter.api.Test;

class MapSnapshotHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void servesStatelessMapSnapshotsAndFixedBundledIconsAcrossServiceRebind() throws Exception
    {
        AtomicReference<MapSnapshotService> current = new AtomicReference<>();
        MapSnapshotHttpController controller = new MapSnapshotHttpController(current::get);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(MapSnapshotHttpController.PATH, controller::handle);
        server.start();

        try(HttpClient client = HttpClient.newHttpClient(); MapSnapshotService service = new MapSnapshotService(null))
        {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals(503, request(client, root.resolve(MapSnapshotHttpController.PATH)).statusCode());

            current.set(service);
            HttpResponse<byte[]> snapshot = request(client, root.resolve(MapSnapshotHttpController.PATH));
            assertEquals(200, snapshot.statusCode());
            assertEquals("no-store", snapshot.headers().firstValue("Cache-Control").orElseThrow());
            JsonNode data = MAPPER.readTree(snapshot.body()).path("data");
            assertTrue(data.path("entities").isArray());
            assertEquals(0, data.path("entities").size());
            assertEquals(0, data.path("dropped_observations").longValue());

            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(SystemConfigurationIdentifier.create("Test System"));
            identifiers.update(APCO25RadioIdentifier.createFrom(1234));
            service.receive(PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1_000)
                .identifiers(identifiers).location(new GeoPosition(40, -83)).build());
            await(() -> service.snapshot().entities().size() == 1);
            data = MAPPER.readTree(request(client, root.resolve(MapSnapshotHttpController.PATH)).body()).path("data");
            assertEquals("Test System", data.path("entities").get(0).path("system").textValue());

            JsonNode options = MAPPER.readTree(request(client,
                root.resolve(MapSnapshotHttpController.ICON_CATALOG_PATH)).body()).path("data");
            assertEquals(StandardMapIconCatalog.values().length, options.size());
            assertEquals("No Icon", options.get(0).path("name").textValue());
            assertEquals("no-icon", options.get(0).path("slug").textValue());

            HttpResponse<byte[]> icon = request(client, root.resolve(MapSnapshotHttpController.ICON_PATH + "police"));
            assertEquals(200, icon.statusCode());
            assertEquals("image/png", icon.headers().firstValue("Content-Type").orElseThrow());
            assertEquals((byte)0x89, icon.body()[0]);
            assertEquals((byte)'P', icon.body()[1]);
            assertEquals(404, request(client,
                root.resolve(MapSnapshotHttpController.ICON_PATH + "custom-icon")).statusCode());
            assertEquals(404, request(client,
                root.resolve(MapSnapshotHttpController.ICON_PATH + "../police")).statusCode());

            current.set(null);
            assertEquals(503, request(client, root.resolve(MapSnapshotHttpController.PATH)).statusCode());
            assertEquals(200, request(client, root.resolve(MapSnapshotHttpController.ICON_PATH + "police"))
                .statusCode());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpResponse<byte[]> request(HttpClient client, URI uri) throws Exception
    {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for map snapshot");
    }
}

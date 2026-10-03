/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.dsheirer.map.MapSnapshotService;
import io.github.dsheirer.map.StandardMapIconCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Function;

/** Read-only, stateless map snapshot and allowlisted bundled-icon delivery for Listen viewers. */
public final class MapSnapshotHttpController
{
    public static final String PATH = "/api/v1/listen/map";
    public static final String ICON_CATALOG_PATH = PATH + "/icons";
    public static final String ICON_PATH = ICON_CATALOG_PATH + "/";
    private static final int MAXIMUM_ICON_BYTES = 128 * 1024;
    private final Supplier<MapSnapshotService> mService;
    private final Function<Object,Object> mPresenter;

    public MapSnapshotHttpController(Supplier<MapSnapshotService> service)
    {
        this(service, Function.identity());
    }

    public MapSnapshotHttpController(Supplier<MapSnapshotService> service, Function<Object,Object> presenter)
    {
        mService = Objects.requireNonNull(service);
        mPresenter = Objects.requireNonNull(presenter);
    }

    public void handle(HttpExchange exchange) throws IOException
    {
        if(!WebHttpSupport.requireNoQuery(exchange))
        {
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if(!PATH.equals(path) && !ICON_CATALOG_PATH.equals(path) && !path.startsWith(ICON_PATH))
        {
            WebHttpSupport.notFound(exchange);
            return;
        }
        if(!"GET".equals(exchange.getRequestMethod()))
        {
            WebHttpSupport.methodNotAllowed(exchange, "GET");
            return;
        }
        if(WebHttpSupport.hasRequestBody(exchange))
        {
            ApiHttpResponse.sendError(exchange, 400, "invalid_request", "GET requests cannot include a body");
            return;
        }

        if(PATH.equals(path))
        {
            sendSnapshot(exchange);
        }
        else if(ICON_CATALOG_PATH.equals(path))
        {
            ApiHttpResponse.sendData(exchange, 200, java.util.Arrays.stream(StandardMapIconCatalog.values())
                .map(icon -> new IconOption(icon.aliasName(), icon.slug())).toList());
        }
        else
        {
            sendIcon(exchange, path.substring(ICON_PATH.length()));
        }
    }

    private void sendSnapshot(HttpExchange exchange) throws IOException
    {
        MapSnapshotService service = mService.get();
        if(service == null)
        {
            ApiHttpResponse.sendError(exchange, 503, "map_unavailable", "Map tracking is unavailable");
            return;
        }

        MapSnapshotService.Snapshot snapshot = service.snapshot();
        ApiHttpResponse.sendData(exchange, 200, mPresenter.apply(new Document(snapshot.generatedAtMs(),
            service.droppedObservations(), service.invalidObservations(), service.projectionFailures(),
            snapshot.evictedEntities(), snapshot.entities())));
    }

    private static void sendIcon(HttpExchange exchange, String slug) throws IOException
    {
        StandardMapIconCatalog icon = StandardMapIconCatalog.forSlug(slug);
        if(icon == null)
        {
            WebHttpSupport.notFound(exchange);
            return;
        }

        byte[] bytes;
        try(InputStream input = MapSnapshotHttpController.class.getClassLoader()
            .getResourceAsStream(icon.resourcePath()))
        {
            if(input == null)
            {
                ApiHttpResponse.sendError(exchange, 503, "map_icon_unavailable", "Map icon is unavailable");
                return;
            }
            bytes = input.readNBytes(MAXIMUM_ICON_BYTES + 1);
        }
        if(bytes.length > MAXIMUM_ICON_BYTES)
        {
            ApiHttpResponse.sendError(exchange, 503, "map_icon_unavailable", "Map icon is unavailable");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, bytes.length);
        try(OutputStream output = exchange.getResponseBody())
        {
            output.write(bytes);
        }
    }

    private record Document(long generatedAtMs, long droppedObservations, long invalidObservations,
                            long projectionFailures, long evictedEntities, List<MapSnapshotService.Entity> entities)
    {
    }

    private record IconOption(String name, String slug)
    {
    }
}

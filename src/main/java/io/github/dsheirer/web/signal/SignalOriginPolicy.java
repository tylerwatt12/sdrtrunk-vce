/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web.signal;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;

/**
 * Browser-origin validation for the signal WebSocket handshake.
 */
@FunctionalInterface
public interface SignalOriginPolicy
{
    boolean isAllowed(ServerUpgradeRequest request);

    /**
     * Requires the browser Origin header to identify the same scheme, host, and effective port as the request.
     */
    static SignalOriginPolicy sameOrigin()
    {
        return request ->
        {
            Origin requestOrigin = requestOrigin(request);
            Origin browserOrigin = headerOrigin(request);
            return requestOrigin != null && requestOrigin.equals(browserOrigin);
        };
    }

    /**
     * Allows the request's own origin and an explicit, immutable set of additional origins.
     */
    static SignalOriginPolicy sameOriginOr(Collection<URI> allowedOrigins)
    {
        Objects.requireNonNull(allowedOrigins, "Allowed origins cannot be null");
        Set<Origin> normalized = new HashSet<>();

        for(URI uri: allowedOrigins)
        {
            Origin origin = normalize(uri);

            if(origin == null)
            {
                throw new IllegalArgumentException("Invalid allowed signal origin: " + uri);
            }

            normalized.add(origin);
        }

        Set<Origin> immutableAllowedOrigins = Set.copyOf(normalized);
        return request ->
        {
            Origin browserOrigin = headerOrigin(request);
            return browserOrigin != null && (browserOrigin.equals(requestOrigin(request)) ||
                immutableAllowedOrigins.contains(browserOrigin));
        };
    }

    private static Origin headerOrigin(ServerUpgradeRequest request)
    {
        Objects.requireNonNull(request, "Upgrade request cannot be null");
        var values = request.getHeaders().getValuesList(HttpHeader.ORIGIN);

        if(values.size() != 1)
        {
            return null;
        }

        try
        {
            return normalize(URI.create(values.getFirst()));
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static Origin requestOrigin(ServerUpgradeRequest request)
    {
        String scheme = request.isSecure() ? "https" : "http";
        String host = Request.getServerName(request);
        int port = Request.getServerPort(request);
        return normalize(scheme, host, port);
    }

    private static Origin normalize(URI uri)
    {
        if(uri == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
        {
            return null;
        }

        String path = uri.getPath();

        if(path != null && !path.isEmpty() && !"/".equals(path))
        {
            return null;
        }

        return normalize(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    private static Origin normalize(String scheme, String host, int port)
    {
        if(scheme == null || host == null || host.isBlank())
        {
            return null;
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);

        if(!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme))
        {
            return null;
        }

        int effectivePort = port;

        if(effectivePort < 0)
        {
            effectivePort = "https".equals(normalizedScheme) ? 443 : 80;
        }

        if(effectivePort < 1 || effectivePort > 65_535)
        {
            return null;
        }

        return new Origin(normalizedScheme, host.toLowerCase(Locale.ROOT), effectivePort);
    }

    record Origin(String scheme, String host, int port)
    {
    }
}

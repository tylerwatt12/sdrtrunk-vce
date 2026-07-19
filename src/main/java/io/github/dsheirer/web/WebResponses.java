/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.web;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Small bounded response helpers shared by embedded web handlers.
 */
public final class WebResponses
{
    private WebResponses()
    {
    }

    public static void text(Response response, Callback callback, int status, String contentType, String body)
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    public static void json(Response response, Callback callback, int status, byte[] body)
    {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, body.length);
        response.write(true, ByteBuffer.wrap(body), callback);
    }
}

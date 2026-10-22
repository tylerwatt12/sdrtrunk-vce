/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Captures real contexts without adding another listener or duplicating routing/authentication policy. */
public final class RemoteSupportHttpServer extends HttpServer
{
    private final HttpServer mDelegate;
    private final Map<String,HttpContext> mContexts = new LinkedHashMap<>();
    public RemoteSupportHttpServer(HttpServer delegate) { mDelegate = delegate; }
    public Map<String,HttpContext> contexts() { return Map.copyOf(mContexts); }
    @Override public void bind(InetSocketAddress address, int backlog) throws IOException { mDelegate.bind(address, backlog); }
    @Override public void start() { mDelegate.start(); }
    @Override public void setExecutor(Executor executor) { mDelegate.setExecutor(executor); }
    @Override public Executor getExecutor() { return mDelegate.getExecutor(); }
    @Override public void stop(int delay) { mDelegate.stop(delay); }
    @Override public HttpContext createContext(String path, HttpHandler handler)
    { HttpContext context = mDelegate.createContext(path, handler); mContexts.put(path, context); return context; }
    @Override public HttpContext createContext(String path)
    { HttpContext context = mDelegate.createContext(path); mContexts.put(path, context); return context; }
    @Override public void removeContext(String path) { mDelegate.removeContext(path); mContexts.remove(path); }
    @Override public void removeContext(HttpContext context) { mDelegate.removeContext(context); mContexts.remove(context.getPath()); }
    @Override public InetSocketAddress getAddress() { return mDelegate.getAddress(); }
}

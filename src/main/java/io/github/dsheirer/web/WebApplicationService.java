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

package io.github.dsheirer.web;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle owner for the application's one embedded HTTP/WebSocket listener.
 *
 * <p>The service is deliberately explicit: construction does not bind a socket, {@link #start()} performs one
 * deterministic bind, and {@link #close()} stops and joins Jetty before returning.  Route/data services are supplied
 * by the application and remain independent of server startup.</p>
 */
public final class WebApplicationService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(WebApplicationService.class);

    private final Configuration mConfiguration;
    private final Handler mHttpHandler;
    private final Consumer<ServerWebSocketContainer> mWebSocketConfigurer;
    private Server mServer;
    private ServerConnector mConnector;
    private QueuedThreadPool mThreadPool;

    public WebApplicationService(Configuration configuration, Handler httpHandler,
                                 Consumer<ServerWebSocketContainer> webSocketConfigurer)
    {
        mConfiguration = Objects.requireNonNull(configuration, "Web application configuration cannot be null");
        mHttpHandler = Objects.requireNonNull(httpHandler, "HTTP handler cannot be null");
        mWebSocketConfigurer = Objects.requireNonNull(webSocketConfigurer,
            "WebSocket configurer cannot be null");
    }

    /**
     * Binds and starts the configured listener.  Repeated calls while started are harmless.
     */
    public synchronized void start()
    {
        if(mServer != null)
        {
            return;
        }

        QueuedThreadPool threadPool = new QueuedThreadPool(mConfiguration.maximumThreads(),
            mConfiguration.minimumThreads(), (int)mConfiguration.threadIdleTimeout().toMillis());
        threadPool.setName(mConfiguration.threadName());
        threadPool.setDaemon(true);
        threadPool.setReservedThreads(mConfiguration.reservedThreads());

        Server server = new Server(threadPool);
        server.setStopTimeout(mConfiguration.stopTimeout().toMillis());
        server.setStopAtShutdown(false);

        ServerConnector connector = new ServerConnector(server, mConfiguration.acceptorThreads(),
            mConfiguration.selectorThreads());
        connector.setHost(mConfiguration.bindAddress().getHostAddress());
        connector.setPort(mConfiguration.port());
        connector.setAcceptQueueSize(mConfiguration.acceptQueueSize());
        connector.setIdleTimeout(mConfiguration.connectionIdleTimeout().toMillis());
        connector.setAcceptedTcpNoDelay(true);
        server.addConnector(connector);

        ContextHandler contextHandler = new ContextHandler("/");
        WebSocketUpgradeHandler upgradeHandler = WebSocketUpgradeHandler.from(server, contextHandler, container ->
        {
            container.setIdleTimeout(mConfiguration.webSocketIdleTimeout());
            container.setMaxTextMessageSize(mConfiguration.maximumWebSocketTextBytes());
            container.setMaxBinaryMessageSize(mConfiguration.maximumWebSocketBinaryBytes());
            container.setMaxFrameSize(mConfiguration.maximumWebSocketFrameBytes());
            container.setMaxOutgoingFrames(mConfiguration.maximumPendingWebSocketFrames());
            mWebSocketConfigurer.accept(container);
        });
        upgradeHandler.setHandler(mHttpHandler);
        contextHandler.setHandler(upgradeHandler);
        server.setHandler(contextHandler);

        try
        {
            server.start();
            mThreadPool = threadPool;
            mConnector = connector;
            mServer = server;
            mLog.info("Web application started at http://{}:{}", mConfiguration.bindAddress().getHostAddress(),
                connector.getLocalPort());
        }
        catch(Exception exception)
        {
            stopFailedStart(server);
            throw new IllegalStateException("Unable to start web application", exception);
        }
    }

    private static void stopFailedStart(Server server)
    {
        try
        {
            server.stop();
        }
        catch(Exception stopException)
        {
            mLog.debug("Unable to stop partially started web application", stopException);
        }

        server.destroy();
    }

    public synchronized boolean isRunning()
    {
        return mServer != null && mServer.isStarted();
    }

    public synchronized int getLocalPort()
    {
        return mConnector != null ? mConnector.getLocalPort() : -1;
    }

    public synchronized URI getBaseUri()
    {
        if(mConnector == null)
        {
            throw new IllegalStateException("Web application is not running");
        }

        String host = mConfiguration.bindAddress().isAnyLocalAddress() ? "127.0.0.1" :
            mConfiguration.bindAddress().getHostAddress();
        return URI.create("http://" + host + ":" + mConnector.getLocalPort() + "/");
    }

    public synchronized ThreadPoolSnapshot getThreadPoolSnapshot()
    {
        if(mThreadPool == null)
        {
            return new ThreadPoolSnapshot(0, 0, 0, 0, 0);
        }

        return new ThreadPoolSnapshot(mThreadPool.getThreads(), mThreadPool.getBusyThreads(),
            mThreadPool.getIdleThreads(), mThreadPool.getQueueSize(), mThreadPool.getMaxThreads());
    }

    @Override
    public synchronized void close()
    {
        Server server = mServer;

        if(server == null)
        {
            return;
        }

        mServer = null;
        mConnector = null;
        mThreadPool = null;

        try
        {
            server.stop();
            server.join();
            mLog.info("Web application stopped");
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping web application", exception);
        }
        catch(Exception exception)
        {
            throw new IllegalStateException("Unable to stop web application", exception);
        }
        finally
        {
            server.destroy();
        }
    }

    public record ThreadPoolSnapshot(int threads, int busyThreads, int idleThreads, int queuedTasks, int maximumThreads)
    {
    }

    public record Configuration(InetAddress bindAddress, int port, int maximumThreads, int minimumThreads,
                                int reservedThreads, int acceptorThreads, int selectorThreads, int acceptQueueSize,
                                Duration threadIdleTimeout, Duration connectionIdleTimeout, Duration webSocketIdleTimeout,
                                Duration stopTimeout, long maximumWebSocketTextBytes,
                                long maximumWebSocketBinaryBytes, long maximumWebSocketFrameBytes,
                                int maximumPendingWebSocketFrames, String threadName)
    {
        public Configuration
        {
            Objects.requireNonNull(bindAddress, "Bind address cannot be null");

            if(port < 0 || port > 65_535)
            {
                throw new IllegalArgumentException("Port must be between 0 and 65535");
            }

            if(maximumThreads < 8 || minimumThreads < 1 || minimumThreads > maximumThreads)
            {
                throw new IllegalArgumentException("Invalid Jetty thread bounds");
            }

            if(reservedThreads < 0 || reservedThreads >= maximumThreads || acceptorThreads < 1 || selectorThreads < 1)
            {
                throw new IllegalArgumentException("Invalid Jetty infrastructure thread counts");
            }

            if(acceptQueueSize < 1)
            {
                throw new IllegalArgumentException("Accept queue size must be positive");
            }

            requirePositive(threadIdleTimeout, "Thread idle timeout");
            requirePositive(connectionIdleTimeout, "Connection idle timeout");
            requirePositive(webSocketIdleTimeout, "WebSocket idle timeout");
            requirePositive(stopTimeout, "Stop timeout");

            if(maximumWebSocketTextBytes < 1 || maximumWebSocketBinaryBytes < 1 ||
                maximumWebSocketFrameBytes < 1 || maximumPendingWebSocketFrames < 1)
            {
                throw new IllegalArgumentException("WebSocket bounds must be positive");
            }

            if(maximumWebSocketTextBytes > maximumWebSocketFrameBytes ||
                maximumWebSocketBinaryBytes > maximumWebSocketFrameBytes)
            {
                throw new IllegalArgumentException("WebSocket message limits cannot exceed the frame limit");
            }

            if(threadName == null || threadName.isBlank())
            {
                throw new IllegalArgumentException("Jetty thread name cannot be blank");
            }
        }

        private static void requirePositive(Duration duration, String label)
        {
            Objects.requireNonNull(duration, label + " cannot be null");

            if(duration.isZero() || duration.isNegative() || duration.toMillis() <= 0)
            {
                throw new IllegalArgumentException(label + " must be at least one millisecond");
            }
        }

        public static Configuration ephemeralLoopback()
        {
            return application(InetAddress.getLoopbackAddress(), 0);
        }

        /**
         * Resource-bounded receiver-node defaults.  Long-lived SSE and signal delivery run outside this bounded
         * platform-thread pool, so ten listeners do not require ten additional Jetty workers.
         */
        public static Configuration application(InetAddress bindAddress, int port)
        {
            return new Configuration(bindAddress, port, 16, 2, 1, 1, 1, 32,
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5),
                4_096, 256 * 1024L, 256 * 1024L, 2, "sdrtrunk web");
        }
    }
}

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

import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically refreshed immutable bridge between configured channels, learned scopes, and live web navigation.
 * Database loading is confined to one low-priority worker. Receiver and channel-activity paths perform only atomic
 * snapshot reads and bounded map lookups.
 */
final class WebEntityNavigationCatalog implements AutoCloseable
{
    static final long DEFAULT_REFRESH_MILLISECONDS = 5_000L;
    private final Loader mLoader;
    private final long mRefreshMilliseconds;
    private final AtomicReference<Snapshot> mSnapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicLong mSuccessfulRefreshes = new AtomicLong();
    private final AtomicLong mFailedRefreshes = new AtomicLong();
    private final AtomicLong mLifecycleGeneration = new AtomicLong();
    private ScheduledExecutorService mExecutor;
    private ScheduledExecutorService mRetiringExecutor;

    WebEntityNavigationCatalog(Loader loader)
    {
        this(loader, DEFAULT_REFRESH_MILLISECONDS);
    }

    WebEntityNavigationCatalog(Loader loader, long refreshMilliseconds)
    {
        mLoader = Objects.requireNonNull(loader, "Navigation loader cannot be null");

        if(refreshMilliseconds < 1)
        {
            throw new IllegalArgumentException("Navigation refresh interval must be positive");
        }

        mRefreshMilliseconds = refreshMilliseconds;
    }

    static WebEntityNavigationCatalog empty()
    {
        return new WebEntityNavigationCatalog(Snapshot::empty);
    }

    synchronized void start()
    {
        if(mExecutor != null)
        {
            return;
        }

        if(mRetiringExecutor != null)
        {
            if(!mRetiringExecutor.isTerminated())
            {
                throw new IllegalStateException("The previous navigation-catalog worker is still stopping");
            }

            mRetiringExecutor = null;
        }

        Snapshot initial;

        try
        {
            initial = loadRequired();
        }
        catch(Exception exception)
        {
            mFailedRefreshes.incrementAndGet();
            throw new IllegalStateException("The configured web-navigation catalog could not be loaded", exception);
        }

        mSnapshot.set(initial);
        mSuccessfulRefreshes.incrementAndGet();

        mExecutor = Executors.newSingleThreadScheduledExecutor(
            new ObserverThreadFactory("web entity navigation catalog"));
        long generation = mLifecycleGeneration.incrementAndGet();
        mExecutor.scheduleWithFixedDelay(() -> refreshSafely(generation, false), mRefreshMilliseconds,
            mRefreshMilliseconds, TimeUnit.MILLISECONDS);
    }

    void stop()
    {
        ScheduledExecutorService executor;

        synchronized(this)
        {
            executor = mExecutor;
            mExecutor = null;
            mLifecycleGeneration.incrementAndGet();

            if(executor != null)
            {
                mRetiringExecutor = executor;
            }
        }

        if(executor != null)
        {
            executor.shutdownNow();

            try
            {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            synchronized(this)
            {
                if(mRetiringExecutor == executor && executor.isTerminated())
                {
                    mRetiringExecutor = null;
                }
            }
        }
    }

    Snapshot snapshot()
    {
        return mSnapshot.get();
    }

    /** Focused test and startup hook; production periodic work uses the same guarded path. */
    void refreshNow()
    {
        refreshSafely(0, true);
    }

    long successfulRefreshes()
    {
        return mSuccessfulRefreshes.get();
    }

    long failedRefreshes()
    {
        return mFailedRefreshes.get();
    }

    private void refreshSafely(long generation, boolean explicit)
    {
        try
        {
            Snapshot loaded = loadRequired();

            if(explicit || generation == mLifecycleGeneration.get())
            {
                mSnapshot.updateAndGet(current -> current.equals(loaded) ? current : loaded);
                mSuccessfulRefreshes.incrementAndGet();
            }
        }
        catch(Exception exception)
        {
            //Retain the last complete snapshot. A partial or failed refresh must never erase working navigation.
            mFailedRefreshes.incrementAndGet();
        }
    }

    private Snapshot loadRequired() throws Exception
    {
        Snapshot loaded = mLoader.load();

        if(loaded == null)
        {
            throw new IllegalStateException("Navigation loader returned no snapshot");
        }

        return loaded;
    }

    @Override
    public void close()
    {
        stop();
    }

    @FunctionalInterface
    interface Loader
    {
        Snapshot load() throws Exception;
    }

    record Channel(String configurationId, String guid, WebEntityRef.KeyRef entityRef,
                   WebEntityRef.KeyRef systemRef, int protocolCode, int identityDomainCode)
    {
        Channel
        {
            if(configurationId == null || configurationId.isBlank() || entityRef == null)
            {
                throw new IllegalArgumentException("Channel navigation requires configured identity");
            }
            boolean referenceMatches = switch(entityRef.kind())
            {
                case SITE -> guid != null && guid.equals(entityRef.key());
                case CONVENTIONAL -> configurationId.equals(entityRef.key());
                default -> false;
            };

            if(!referenceMatches)
            {
                throw new IllegalArgumentException("Channel navigation reference does not match its canonical identity");
            }
            if(systemRef != null && systemRef.kind() != WebEntityRef.Kind.SYSTEM)
            {
                throw new IllegalArgumentException("Channel system reference must identify a learned system scope");
            }
        }

        WebEntityRef identity(ChannelActivitySnapshot.MatcherReference matcher)
        {
            if(systemRef == null || matcher == null || !protocolMatches(matcher.protocol()))
            {
                return null;
            }

            Form form = switch(matcher.type())
            {
                case "talkgroup" -> Form.TALKGROUP;
                case "patch_group" -> Form.PATCH_GROUP;
                case "radio" -> Form.RADIO;
                default -> null;
            };
            return identity(form, protocol(), matcher.value());
        }

        WebEntityRef identity(Form form, Protocol identifierProtocol, int identifier)
        {
            Protocol protocol = protocol();

            if(systemRef == null || identifierProtocol == null || !sameProtocol(protocol, identifierProtocol))
            {
                return null;
            }

            TrunkedIdentityDomain domain = switch(identityDomainCode)
            {
                case 1 -> TrunkedIdentityDomain.NXDN_TYPE_C;
                case 2 -> TrunkedIdentityDomain.NXDN_TYPE_D;
                default -> TrunkedIdentityDomain.STANDARD;
            };

            if(!TrunkedIdentityEligibility.isEligible(protocol, domain, form, identifier))
            {
                return null;
            }

            return switch(form)
            {
                case TALKGROUP -> WebEntityRef.talkgroup(systemRef.key(), identifier);
                case PATCH_GROUP -> WebEntityRef.patchGroup(systemRef.key(), identifier);
                case RADIO -> WebEntityRef.radio(systemRef.key(), identifier);
                default -> null;
            };
        }

        private Protocol protocol()
        {
            return switch(protocolCode)
            {
                case 1 -> Protocol.APCO25;
                case 3 -> Protocol.DMR;
                case 4 -> Protocol.NXDN;
                default -> Protocol.UNKNOWN;
            };
        }

        private static boolean sameProtocol(Protocol configured, Protocol identifier)
        {
            return configured == identifier || configured == Protocol.APCO25 && identifier == Protocol.APCO25_PHASE2;
        }

        private boolean protocolMatches(String value)
        {
            return switch(protocolCode)
            {
                case 1 -> "p25".equals(value);
                case 3 -> "dmr".equals(value);
                case 4 -> "nxdn".equals(value);
                default -> false;
            };
        }
    }

    record Snapshot(Map<String,Channel> byConfigurationId, Map<String,Channel> byGuid)
    {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

        Snapshot
        {
            byConfigurationId = Map.copyOf(byConfigurationId != null ? byConfigurationId : Map.of());
            byGuid = Map.copyOf(byGuid != null ? byGuid : Map.of());
        }

        static Snapshot empty()
        {
            return EMPTY;
        }

        static Snapshot of(List<Channel> channels)
        {
            Map<String,Channel> configurations = new LinkedHashMap<>();
            Map<String,Channel> sites = new LinkedHashMap<>();

            for(Channel channel: channels != null ? channels : List.<Channel>of())
            {
                if(configurations.putIfAbsent(channel.configurationId(), channel) != null)
                {
                    throw new IllegalArgumentException("Duplicate configured-channel navigation identity");
                }
                if(channel.entityRef().kind() == WebEntityRef.Kind.SITE &&
                    channel.guid() != null && !channel.guid().isBlank() &&
                    sites.putIfAbsent(channel.guid(), channel) != null)
                {
                    throw new IllegalArgumentException("Duplicate site navigation identity");
                }
            }

            return new Snapshot(configurations, sites);
        }

        Channel channel(String configurationId, String guid)
        {
            if(configurationId != null)
            {
                return byConfigurationId.get(configurationId);
            }

            return guid != null ? byGuid.get(guid) : null;
        }
    }
}

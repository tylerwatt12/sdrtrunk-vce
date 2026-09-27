/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.map;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.jdesktop.swingx.mapviewer.GeoPosition;

/**
 * Receiver-owned, bounded projection of decoded locations for the Listen map.  Decoder callbacks copy only location
 * primitives and identifier references into a lock-free bounded queue.  Alias lookup, string conversion, history
 * management, and immutable snapshot construction occur on one lower-priority worker.  Browser clients only read the
 * latest snapshot and never subscribe to a decoder or hold a receiver-side lease.
 */
public final class MapSnapshotService implements Listener<IDecodeEvent>, AutoCloseable
{
    public static final int MAXIMUM_ENTITIES = 256;
    public static final int MAXIMUM_HISTORY_PER_ENTITY = 10;
    private static final int INGRESS_CAPACITY = 256;
    private static final int MAXIMUM_TEXT_CHARACTERS = 160;
    private static final long PUBLISH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private final BoundedMpscReferenceQueue<Ingress> mIngress;
    private final AliasResolver mAliasResolver;
    private final int mMaximumEntities;
    private final int mMaximumHistory;
    private final LinkedHashMap<String,MutableEntity> mEntities = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicLong mDroppedObservations = new AtomicLong();
    private final AtomicLong mInvalidObservations = new AtomicLong();
    private final AtomicLong mProjectionFailures = new AtomicLong();
    private final Thread mWorker;
    private volatile Snapshot mSnapshot = new Snapshot(0, 0, List.of());
    private long mEvictedEntities;

    public MapSnapshotService(AliasModel aliasModel)
    {
        this((aliasList, identifier) -> resolveAlias(aliasModel, aliasList, identifier), INGRESS_CAPACITY,
            MAXIMUM_ENTITIES, MAXIMUM_HISTORY_PER_ENTITY);
    }

    MapSnapshotService(AliasResolver aliasResolver, int ingressCapacity, int maximumEntities, int maximumHistory)
    {
        if(ingressCapacity < 2 || Integer.bitCount(ingressCapacity) != 1 || maximumEntities < 1 || maximumHistory < 1)
        {
            throw new IllegalArgumentException("Map capacity limits are invalid");
        }
        mAliasResolver = aliasResolver;
        mIngress = new BoundedMpscReferenceQueue<>(ingressCapacity);
        mMaximumEntities = maximumEntities;
        mMaximumHistory = maximumHistory;
        mWorker = new Thread(this::runWorker, "map location projection");
        mWorker.setDaemon(true);
        mWorker.setPriority(Thread.MIN_PRIORITY + 1);
        mWorker.start();
    }

    /** Constant-bounded receiver callback: no formatting, alias lookup, locks, I/O, or client fan-out. */
    @Override
    public void receive(IDecodeEvent event)
    {
        if(mClosed.get() || !(event instanceof PlottableDecodeEvent plottable))
        {
            return;
        }

        GeoPosition location = plottable.getLocation();
        IdentifierCollection identifiers = plottable.getIdentifierCollection();
        if(location == null || identifiers == null)
        {
            return;
        }

        // Capture identity now rather than retaining a mutable decode event or identifier collection in the queue.
        Identifier from = identifiers.getFromIdentifier();
        if(from == null || from.getForm() == Form.LOCATION)
        {
            return;
        }

        Ingress observation = new Ingress(from, identifiers.getAliasListConfiguration(), location.getLatitude(),
            location.getLongitude(), plottable.getTimeStart(), plottable.getHeading(), plottable.getSpeed());
        if(mIngress.offer(observation))
        {
            LockSupport.unpark(mWorker);
        }
        else
        {
            mDroppedObservations.incrementAndGet();
        }
    }

    /** Lock-free read of one immutable receiver-side snapshot. */
    public Snapshot snapshot()
    {
        return mSnapshot;
    }

    public long droppedObservations()
    {
        return mDroppedObservations.get();
    }

    public long invalidObservations()
    {
        return mInvalidObservations.get();
    }

    public long projectionFailures()
    {
        return mProjectionFailures.get();
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mWorker.interrupt();
            LockSupport.unpark(mWorker);
        }
    }

    private void runWorker()
    {
        long lastPublishedNanos = 0;
        boolean changed = false;

        while(!mClosed.get())
        {
            for(int processed = 0; processed < mIngress.capacity(); processed++)
            {
                Ingress ingress = mIngress.poll();
                if(ingress == null)
                {
                    break;
                }
                try
                {
                    changed |= process(ingress);
                }
                catch(RuntimeException exception)
                {
                    // A malformed observer event must not terminate location collection or touch decoder threads.
                    mProjectionFailures.incrementAndGet();
                }
            }

            long now = System.nanoTime();
            if(changed && (lastPublishedNanos == 0 || now - lastPublishedNanos >= PUBLISH_INTERVAL_NANOS))
            {
                publishSnapshot();
                lastPublishedNanos = now;
                changed = false;
            }

            if(mIngress.size() == 0)
            {
                LockSupport.parkNanos(this, IDLE_PARK_NANOS);
            }
        }

        mIngress.clear();
    }

    private boolean process(Ingress ingress)
    {
        if(!Double.isFinite(ingress.latitude()) || !Double.isFinite(ingress.longitude()) ||
            Math.abs(ingress.latitude()) > 90 || Math.abs(ingress.longitude()) > 180 ||
            (Math.abs(ingress.latitude()) <= 0.01 && Math.abs(ingress.longitude()) <= 0.01))
        {
            mInvalidObservations.incrementAndGet();
            return false;
        }

        String identifier = boundedText(ingress.from().toString());
        String aliasList = ingress.aliasList() != null ? boundedText(ingress.aliasList().toString()) : "";
        String key = aliasList + '\u001f' + ingress.from().getClass().getName() + '\u001f' + identifier;
        Display display;
        try
        {
            display = mAliasResolver != null ? mAliasResolver.resolve(ingress.aliasList(), ingress.from()) : null;
        }
        catch(RuntimeException exception)
        {
            // Alias edits can race a projection. Keep the position with its radio identifier and retry next update.
            mProjectionFailures.incrementAndGet();
            display = null;
        }
        MutableEntity entity = mEntities.get(key);

        if(entity == null)
        {
            entity = new MutableEntity(key, identifier, aliasList);
            mEntities.put(key, entity);
            if(mEntities.size() > mMaximumEntities)
            {
                Iterator<String> iterator = mEntities.keySet().iterator();
                iterator.next();
                iterator.remove();
                mEvictedEntities++;
            }
        }

        entity.label = display != null && display.label() != null && !display.label().isBlank() ?
            boundedText(display.label()) : identifier;
        entity.icon = display != null ? StandardMapIconCatalog.forSlug(display.icon()) :
            StandardMapIconCatalog.NO_ICON;
        if(entity.icon == null)
        {
            entity.icon = StandardMapIconCatalog.NO_ICON;
        }
        entity.color = display != null && display.color() != null && display.color().matches("#[0-9a-fA-F]{6}") ?
            display.color() : "#0000ff";
        entity.heading = Double.isFinite(ingress.heading()) ? ingress.heading() : 0;
        entity.speedKph = Double.isFinite(ingress.speedKph()) ? ingress.speedKph() : 0;

        Position position = new Position(ingress.latitude(), ingress.longitude(), ingress.timestampMs());
        Position previous = entity.positions.peekFirst();
        if(previous == null || position.timestampMs() > previous.timestampMs() + 2_000 ||
            Math.abs(position.latitude() - previous.latitude()) > 0.00001 ||
            Math.abs(position.longitude() - previous.longitude()) > 0.00001)
        {
            entity.positions.addFirst(position);
            while(entity.positions.size() > mMaximumHistory)
            {
                entity.positions.removeLast();
            }
        }
        return true;
    }

    private void publishSnapshot()
    {
        List<Entity> entities = new ArrayList<>(mEntities.size());
        for(MutableEntity mutable: mEntities.values())
        {
            entities.add(new Entity(mutable.key, mutable.label, mutable.identifier, mutable.aliasList,
                mutable.icon.slug(), mutable.color, mutable.heading, mutable.speedKph,
                List.copyOf(mutable.positions)));
        }
        java.util.Collections.reverse(entities);
        mSnapshot = new Snapshot(System.currentTimeMillis(), mEvictedEntities, List.copyOf(entities));
    }

    private static Display resolveAlias(AliasModel aliasModel, AliasListConfigurationIdentifier aliasListIdentifier,
                                        Identifier identifier)
    {
        if(aliasModel == null || aliasListIdentifier == null)
        {
            return null;
        }
        AliasList aliasList = aliasModel.getAliasList(aliasListIdentifier);
        if(aliasList == null)
        {
            return null;
        }
        List<Alias> aliases = aliasList.getAliases(identifier);
        if(aliases.isEmpty())
        {
            return null;
        }
        Alias alias = aliases.getFirst();
        int rgb = alias.getColor() & 0xffffff;
        String color = "#" + "000000".substring(Integer.toHexString(rgb).length()) + Integer.toHexString(rgb);
        return new Display(alias.getName(), StandardMapIconCatalog.forAliasName(alias.getIconName()).slug(), color);
    }

    private static String boundedText(String value)
    {
        if(value == null)
        {
            return "";
        }
        StringBuilder bounded = new StringBuilder(Math.min(value.length(), MAXIMUM_TEXT_CHARACTERS));
        for(int index = 0; index < value.length() && bounded.length() < MAXIMUM_TEXT_CHARACTERS; index++)
        {
            char character = value.charAt(index);
            bounded.append(Character.isISOControl(character) ? ' ' : character);
        }
        return bounded.toString().strip();
    }

    @FunctionalInterface
    interface AliasResolver
    {
        Display resolve(AliasListConfigurationIdentifier aliasList, Identifier identifier);
    }

    record Display(String label, String icon, String color)
    {
    }

    private record Ingress(Identifier from, AliasListConfigurationIdentifier aliasList, double latitude,
                           double longitude, long timestampMs, double heading, double speedKph)
    {
    }

    private static final class MutableEntity
    {
        private final String key;
        private final String identifier;
        private final String aliasList;
        private final Deque<Position> positions = new ArrayDeque<>();
        private String label;
        private StandardMapIconCatalog icon = StandardMapIconCatalog.NO_ICON;
        private String color = "#0000ff";
        private double heading;
        private double speedKph;

        private MutableEntity(String key, String identifier, String aliasList)
        {
            this.key = key;
            this.identifier = identifier;
            this.aliasList = aliasList;
        }
    }

    public record Position(double latitude, double longitude, long timestampMs)
    {
    }

    public record Entity(String id, String label, String identifier, String aliasList, String icon, String color,
                         double heading, double speedKph, List<Position> positions)
    {
    }

    public record Snapshot(long generatedAtMs, long evictedEntities, List<Entity> entities)
    {
    }
}

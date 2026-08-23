/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI-neutral, demand-owned projection of live decoder events. The receiver callback performs only a bounded,
 * nonblocking reference offer; projection and fan-out run on the observer worker. No event history or replay is kept.
 */
public class DecodeEventViewService implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(DecodeEventViewService.class);
    static final int UPDATE_QUEUE_SIZE = 1_024;
    private static final int MAXIMUM_DRAIN_PER_RUN = 512;
    private static final int DETAILS_MAXIMUM_LENGTH = 512;
    private static final int PARTY_MAXIMUM_IDENTIFIERS = 32;
    private static final int TEXT_MAXIMUM_LENGTH = 512;
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLISECONDS = 2_000;
    private static final FilterCatalog FILTER_CATALOG = createFilterCatalog();
    private final ChannelProcessingManager mChannelProcessingManager;
    private final AliasModel mAliasModel;
    private final Broadcaster<EventView> mBroadcaster = new Broadcaster<>();
    private volatile BoundedMpscPairQueue<Channel,IDecodeEvent> mIngress =
        new BoundedMpscPairQueue<>(UPDATE_QUEUE_SIZE);
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(
        new ObserverThreadFactory("sdrtrunk decode event views"));
    private final Semaphore mWakeup = new Semaphore(0);
    private final AtomicLong mDroppedObservations = new AtomicLong();
    private final AtomicLong mDemandGeneration = new AtomicLong();
    private final AtomicLong mLiveEdgeEpoch = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final AtomicBoolean mActive = new AtomicBoolean();
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receive;
    private final long mCloseTimeoutMilliseconds;

    public DecodeEventViewService(ChannelProcessingManager channelProcessingManager, AliasModel aliasModel)
    {
        this(channelProcessingManager, aliasModel, DEFAULT_CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    }

    DecodeEventViewService(ChannelProcessingManager channelProcessingManager, AliasModel aliasModel,
                           long closeTimeout, TimeUnit unit)
    {
        mChannelProcessingManager = channelProcessingManager;
        mAliasModel = aliasModel;
        java.util.Objects.requireNonNull(unit, "unit cannot be null");
        mCloseTimeoutMilliseconds = Math.max(0, unit.toMillis(closeTimeout));
        mWorker.execute(this::runWorker);
    }

    private void runWorker()
    {
        try
        {
            while(!mClosed.get())
            {
                if(mActive.get())
                {
                    long generation = mDemandGeneration.get();
                    BoundedMpscPairQueue<Channel,IDecodeEvent> ingress = mIngress;
                    drainSafely(generation, ingress);
                }

                try
                {
                    if(mActive.get())
                    {
                        mWakeup.tryAcquire(10, TimeUnit.MILLISECONDS);
                    }
                    else
                    {
                        mWakeup.acquire();
                    }

                    mWakeup.drainPermits();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        finally
        {
            cleanupOnWorker();
        }
    }

    public BiConsumer<Channel,IDecodeEvent> getDecodeEventListener()
    {
        return mDecodeEventListener;
    }

    /**
     * Establishes a live edge for a new downstream subscription. Observations accepted before this boundary retain an
     * older primitive stamp, allowing shared worker projection to continue for existing subscribers without replaying
     * those observations to the new subscriber.
     */
    public long advanceLiveEdge()
    {
        if(mClosed.get())
        {
            throw new IllegalStateException("decode event view service is closed");
        }

        return mLiveEdgeEpoch.incrementAndGet();
    }

    public void addListener(Listener<EventView> listener)
    {
        if(listener == null || mClosed.get())
        {
            return;
        }

        synchronized(mBroadcaster)
        {
            if(mClosed.get())
            {
                return;
            }

            boolean firstListener = !mBroadcaster.hasListeners();
            mBroadcaster.addListener(listener);

            if(firstListener && mBroadcaster.hasListeners())
            {
                //A fresh queue gives each demand generation an exact ingress boundary without making a receiver
                //callback allocate, lock, or wait for worker cleanup.
                mIngress = new BoundedMpscPairQueue<>(UPDATE_QUEUE_SIZE);
                mDemandGeneration.incrementAndGet();
            }

            mActive.set(mBroadcaster.hasListeners());
            mWakeup.release();
        }
    }

    public void removeListener(Listener<EventView> listener)
    {
        synchronized(mBroadcaster)
        {
            mBroadcaster.removeListener(listener);

            if(!mBroadcaster.hasListeners())
            {
                mActive.set(false);
                mDemandGeneration.incrementAndGet();
                mWakeup.release();
            }
        }
    }

    void receive(Channel channel, IDecodeEvent event)
    {
        if(channel == null || event == null || mClosed.get() || !mActive.get())
        {
            return;
        }

        BoundedMpscPairQueue<Channel,IDecodeEvent> ingress = mIngress;

        if(!mActive.get())
        {
            return;
        }

        long liveEdgeEpoch = mLiveEdgeEpoch.get();

        if(!ingress.offer(channel, event, liveEdgeEpoch))
        {
            mDroppedObservations.incrementAndGet();
        }
    }

    private void drainSafely(long generation, BoundedMpscPairQueue<Channel,IDecodeEvent> ingress)
    {
        try
        {
            drain(generation, ingress);
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Error processing a decoder event view observation", exception);
        }
    }

    private void cleanupOnWorker()
    {
        mIngress.clear();
    }

    private void drain(long generation, BoundedMpscPairQueue<Channel,IDecodeEvent> ingress)
    {
        if(mClosed.get())
        {
            return;
        }

        List<EventView> batch = new ArrayList<>();

        for(int count = 0; count < MAXIMUM_DRAIN_PER_RUN; count++)
        {
            BoundedMpscPairQueue.Entry<Channel,IDecodeEvent> observation = ingress.poll();

            if(observation == null)
            {
                break;
            }

            Channel channel = observation.first();
            IDecodeEvent event = observation.second();
            String configurationId = channel.getConfigurationId();
            ProcessingChain chain = mChannelProcessingManager != null ?
                mChannelProcessingManager.getProcessingChain(channel) : null;
            Source source = chain != null ? chain.getSource() : null;
            Long sourceFrequency = source != null && source.getFrequency() > 0 ? source.getFrequency() : null;
            EventView projected = view(configurationId, event, sourceFrequency, observation.stamp());

            if(mClosed.get() || !mActive.get() || mDemandGeneration.get() != generation || mIngress != ingress)
            {
                break;
            }

            batch.add(projected);
        }

        if(!batch.isEmpty() && mActive.get() && mDemandGeneration.get() == generation && mIngress == ingress)
        {
            for(EventView projected: batch)
            {
                if(mClosed.get() || !mActive.get() || mDemandGeneration.get() != generation || mIngress != ingress)
                {
                    break;
                }

                mBroadcaster.broadcast(projected);
            }
        }
    }

    public long getDroppedObservationCount()
    {
        return mDroppedObservations.get();
    }

    /** Stable Java-style filter choices for each live-only browser subscription. */
    public static FilterCatalog filterCatalog()
    {
        return FILTER_CATALOG;
    }

    private static FilterCatalog createFilterCatalog()
    {
        return FilterCatalog.create(List.of(
            eventGroup("event-group/VOICE", "Voice Calls", DecodeEventType.VOICE_CALLS),
            eventGroup("event-group/ENCRYPTED_VOICE", "Voice Calls - Encrypted",
                DecodeEventType.VOICE_CALLS_ENCRYPTED),
            eventGroup("event-group/DATA", "Data Calls", DecodeEventType.DATA_CALLS),
            eventGroup("event-group/COMMAND", "Commands", DecodeEventType.COMMANDS),
            eventGroup("event-group/REGISTRATION", "Registrations", DecodeEventType.REGISTRATION),
            eventGroup("event-group/OTHER", "Other", DecodeEventType.OTHERS)), List.of());
    }

    private static FilterCatalog.Node eventGroup(String key, String label, Set<DecodeEventType> types)
    {
        List<FilterCatalog.Node> children = types.stream()
            .sorted(Comparator.comparing(DecodeEventType::getLabel).thenComparing(Enum::name))
            .map(type -> new FilterCatalog.Node(type.name(), type.getLabel(), List.of()))
            .toList();
        return new FilterCatalog.Node(key, label, children);
    }

    int getPendingObservationCount()
    {
        return mIngress.size();
    }

    boolean isWorkerTerminated()
    {
        return mWorker.isTerminated();
    }

    EventView view(String configurationId, IDecodeEvent event)
    {
        return view(configurationId, event, null);
    }

    EventView view(String configurationId, IDecodeEvent event, Long sourceFrequency)
    {
        return view(configurationId, event, sourceFrequency, 0);
    }

    private EventView view(String configurationId, IDecodeEvent event, Long sourceFrequency, long observationEpoch)
    {
        IdentifierCollection identifiers = event.getIdentifierCollection();
        Parties from = parties(identifiers, Role.FROM);
        Parties to = parties(identifiers, Role.TO);
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = sourceFrequency;

        if(descriptor != null && descriptor.getDownlinkFrequency() > 0)
        {
            frequency = descriptor.getDownlinkFrequency();
        }
        DecodeEventType type = event.getEventType();

        return new EventView(eventId(event), bounded(configurationId), event.getTimeStart(), event.getDuration(),
            type != null ? type.name() : DecodeEventType.UNKNOWN.name(),
            type != null ? type.getLabel() : DecodeEventType.UNKNOWN.getLabel(), category(type),
            from.identifiers(), from.aliases(), to.identifiers(), to.aliases(),
            descriptor != null ? bounded(descriptor.toString()) : null, frequency,
            event.hasTimeslot() ? event.getTimeslot() : null, bounded(event.getDetails()),
            event.getProtocol() != null ? event.getProtocol().name() : null, observationEpoch);
    }

    private Parties parties(IdentifierCollection collection, Role role)
    {
        if(collection == null)
        {
            return Parties.EMPTY;
        }

        List<Identifier> identifiers = collection.getIdentifiers(role);

        if(identifiers == null || identifiers.isEmpty())
        {
            return Parties.EMPTY;
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        AliasList aliasList = mAliasModel != null ? mAliasModel.getAliasList(collection) : null;

        int examined = 0;

        for(Identifier identifier: identifiers)
        {
            if(identifier == null)
            {
                continue;
            }

            if(examined++ >= PARTY_MAXIMUM_IDENTIFIERS)
            {
                break;
            }

            values.add(bounded(identifier.toString()));

            if(aliasList != null)
            {
                for(Alias alias: aliasList.getAliases(identifier))
                {
                    if(alias != null && alias.getName() != null && !alias.getName().isBlank())
                    {
                        aliases.add(bounded(alias.getName()));

                        if(aliases.size() >= PARTY_MAXIMUM_IDENTIFIERS)
                        {
                            break;
                        }
                    }
                }
            }
        }

        return new Parties(join(values), join(aliases));
    }

    private static String eventId(IDecodeEvent event)
    {
        return Long.toUnsignedString(event.getTimeStart(), 36) + "-" +
            Integer.toUnsignedString(System.identityHashCode(event), 36);
    }

    private static String category(DecodeEventType type)
    {
        if(type != null && DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(type))
        {
            return "ENCRYPTED_VOICE";
        }
        else if(type != null && DecodeEventType.VOICE_CALLS.contains(type))
        {
            return "VOICE";
        }
        else if(type != null && DecodeEventType.DATA_CALLS.contains(type))
        {
            return "DATA";
        }
        else if(type != null && DecodeEventType.COMMANDS.contains(type))
        {
            return "COMMAND";
        }
        else if(type != null && DecodeEventType.REGISTRATION.contains(type))
        {
            return "REGISTRATION";
        }

        return "OTHER";
    }

    private static String bounded(String value)
    {
        if(value == null)
        {
            return null;
        }

        String trimmed = value.strip();
        return trimmed.length() <= DETAILS_MAXIMUM_LENGTH ? trimmed :
            trimmed.substring(0, DETAILS_MAXIMUM_LENGTH - 1) + "…";
    }

    private static String join(LinkedHashSet<String> values)
    {
        if(values.isEmpty())
        {
            return null;
        }

        StringBuilder joined = new StringBuilder(Math.min(TEXT_MAXIMUM_LENGTH, values.size() * 16));

        for(String value: values)
        {
            String separator = joined.isEmpty() ? "" : ", ";
            int remaining = TEXT_MAXIMUM_LENGTH - joined.length();

            if(separator.length() + value.length() <= remaining)
            {
                joined.append(separator).append(value);
            }
            else
            {
                if(remaining <= 0)
                {
                    break;
                }
                else if(remaining > 1)
                {
                    joined.append(separator, 0, Math.min(separator.length(), remaining - 1));
                    remaining = TEXT_MAXIMUM_LENGTH - joined.length();
                    joined.append(value, 0, Math.min(value.length(), Math.max(0, remaining - 1)));
                }

                joined.append('…');
                break;
            }
        }

        return joined.toString();
    }

    @Override
    public void close()
    {
        synchronized(mBroadcaster)
        {
            if(!mClosed.compareAndSet(false, true))
            {
                return;
            }

            mActive.set(false);
            mBroadcaster.clear();
        }

        //Only the observer worker consumes and clears ingress/history, including after a timed close returns.
        mWakeup.release();
        mWorker.shutdown();

        try
        {
            if(!mWorker.awaitTermination(mCloseTimeoutMilliseconds, TimeUnit.MILLISECONDS))
            {
                mLog.warn("Timed out waiting for decoder-event observer cleanup");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    public record Scope(String configurationId, Long frequencyHz, Integer timeslot)
    {
        public Scope
        {
            if(configurationId == null || configurationId.isBlank())
            {
                throw new IllegalArgumentException("configurationId is required");
            }

            configurationId = configurationId.strip();

            if(frequencyHz != null && frequencyHz <= 0)
            {
                throw new IllegalArgumentException("frequencyHz must be positive");
            }

            if(timeslot != null && timeslot <= 0)
            {
                throw new IllegalArgumentException("timeslot must be positive");
            }
        }

        public boolean matches(EventView event)
        {
            return event != null && configurationId.equals(event.configurationId()) &&
                (frequencyHz == null || frequencyHz.equals(event.frequencyHz())) &&
                (timeslot == null || timeslot.equals(event.timeslot()));
        }
    }

    public record EventView(String eventId, String configurationId, long timeStartMs, long durationMs,
                            String eventType, String eventLabel, String category, String fromIdentifiers,
                            String fromAliases, String toIdentifiers, String toAliases, String channel,
                            Long frequencyHz, Integer timeslot, String details, String protocol,
                            long observationEpoch)
    {
        /** Internal transport boundary; it is not part of the browser event payload. */
        @JsonIgnore
        public long observationEpoch()
        {
            return observationEpoch;
        }
    }

    private record Parties(String identifiers, String aliases)
    {
        private static final Parties EMPTY = new Parties(null, null);
    }

}

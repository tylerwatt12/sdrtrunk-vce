/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * UI-neutral, session-only projection of decoder events. Existing processing-chain histories own retention; this
 * service only creates immutable views for live consumers.
 */
public class DecodeEventViewService implements AutoCloseable
{
    public static final int HISTORY_SIZE = 200;
    private static final int UPDATE_QUEUE_SIZE = 512;
    private static final int DETAILS_MAXIMUM_LENGTH = 512;
    private static final int PARTY_MAXIMUM_IDENTIFIERS = 32;
    private static final int TEXT_MAXIMUM_LENGTH = 512;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final AliasModel mAliasModel;
    private final Broadcaster<EventView> mBroadcaster = new Broadcaster<>();
    private final ThreadPoolExecutor mExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(UPDATE_QUEUE_SIZE), new NamingThreadFactory("decode event views"),
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final BiConsumer<Channel,IDecodeEvent> mDecodeEventListener = this::receive;

    public DecodeEventViewService(ChannelProcessingManager channelProcessingManager, AliasModel aliasModel)
    {
        mChannelProcessingManager = channelProcessingManager;
        mAliasModel = aliasModel;
    }

    public BiConsumer<Channel,IDecodeEvent> getDecodeEventListener()
    {
        return mDecodeEventListener;
    }

    public void addListener(Listener<EventView> listener)
    {
        mBroadcaster.addListener(listener);
    }

    public void removeListener(Listener<EventView> listener)
    {
        mBroadcaster.removeListener(listener);
    }

    /**
     * Returns the newest events for the selected configured receiver or exact channel frequency.
     */
    public List<EventView> snapshot(Scope scope)
    {
        if(scope == null || mChannelProcessingManager == null)
        {
            return List.of();
        }

        List<ProcessingChain> chains = mChannelProcessingManager.getProcessingChainsByConfiguration(
            scope.configurationId(), scope.frequencyHz());

        if(chains.isEmpty())
        {
            return List.of();
        }

        Comparator<EventCandidate> oldestFirst = Comparator.comparingLong(EventCandidate::timeStartMs)
            .thenComparing(Comparator.comparingLong(EventCandidate::sequence).reversed());
        PriorityQueue<EventCandidate> newest = new PriorityQueue<>(HISTORY_SIZE, oldestFirst);
        IdentityHashMap<IDecodeEvent,EventCandidate> included = new IdentityHashMap<>();
        IdentityHashMap<IDecodeEvent,Long> sourceFrequencies = new IdentityHashMap<>();
        long sequence = 0;

        for(ProcessingChain chain: chains)
        {
            Source source = chain.getSource();
            Long sourceFrequency = source != null && source.getFrequency() > 0 ? source.getFrequency() : null;

            for(IDecodeEvent event: chain.getDecodeEventHistory().getItems())
            {
                if(event == null || !matchesTimeslot(event, scope.timeslot()))
                {
                    continue;
                }

                EventCandidate existing = included.get(event);

                if(existing != null)
                {
                    if(sourceFrequencies.get(event) == null && sourceFrequency != null)
                    {
                        sourceFrequencies.put(event, sourceFrequency);
                    }

                    continue;
                }

                EventCandidate candidate = new EventCandidate(event, sequence++);

                if(newest.size() < HISTORY_SIZE)
                {
                    newest.add(candidate);
                    included.put(event, candidate);
                    sourceFrequencies.put(event, sourceFrequency);
                }
                else if(oldestFirst.compare(candidate, newest.peek()) > 0)
                {
                    EventCandidate removed = newest.remove();
                    included.remove(removed.event());
                    sourceFrequencies.remove(removed.event());
                    newest.add(candidate);
                    included.put(event, candidate);
                    sourceFrequencies.put(event, sourceFrequency);
                }
            }
        }

        List<EventCandidate> candidates = new ArrayList<>(newest);
        candidates.sort(Comparator.comparingLong(EventCandidate::timeStartMs).reversed()
            .thenComparingLong(EventCandidate::sequence));
        List<EventView> events = new ArrayList<>(candidates.size());

        for(EventCandidate candidate: candidates)
        {
            events.add(view(scope.configurationId(), candidate.event(), sourceFrequencies.get(candidate.event())));
        }

        return List.copyOf(events);
    }

    void receive(Channel channel, IDecodeEvent event)
    {
        if(channel == null || event == null || !mBroadcaster.hasListeners())
        {
            return;
        }

        String configurationId = channel.getConfigurationId();
        ProcessingChain chain = mChannelProcessingManager != null ?
            mChannelProcessingManager.getProcessingChain(channel) : null;
        Source source = chain != null ? chain.getSource() : null;
        Long sourceFrequency = source != null && source.getFrequency() > 0 ? source.getFrequency() : null;

        try
        {
            mExecutor.execute(() -> mBroadcaster.broadcast(view(configurationId, event, sourceFrequency)));
        }
        catch(RejectedExecutionException _)
        {
            // Service is shutting down.
        }
    }

    EventView view(String configurationId, IDecodeEvent event)
    {
        return view(configurationId, event, null);
    }

    EventView view(String configurationId, IDecodeEvent event, Long sourceFrequency)
    {
        IdentifierCollection identifiers = event.getIdentifierCollection();
        Parties from = parties(identifiers, Role.FROM);
        Parties to = parties(identifiers, Role.TO);
        IChannelDescriptor descriptor = event.getChannelDescriptor();
        Long frequency = descriptor != null && descriptor.getDownlinkFrequency() > 0 ?
            descriptor.getDownlinkFrequency() : sourceFrequency;
        DecodeEventType type = event.getEventType();

        return new EventView(eventId(event), bounded(configurationId), event.getTimeStart(), event.getDuration(),
            type != null ? type.name() : DecodeEventType.UNKNOWN.name(),
            type != null ? type.getLabel() : DecodeEventType.UNKNOWN.getLabel(), category(type),
            from.identifiers(), from.aliases(), to.identifiers(), to.aliases(),
            descriptor != null ? bounded(descriptor.toString()) : null, frequency,
            event.hasTimeslot() ? event.getTimeslot() : null, bounded(event.getDetails()),
            event.getProtocol() != null ? event.getProtocol().name() : null);
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

    private static boolean matchesTimeslot(IDecodeEvent event, Integer timeslot)
    {
        return timeslot == null || event != null && event.hasTimeslot() && event.getTimeslot() == timeslot;
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
        mBroadcaster.clear();
        mExecutor.shutdownNow();
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
                            Long frequencyHz, Integer timeslot, String details, String protocol)
    {
    }

    private record Parties(String identifiers, String aliases)
    {
        private static final Parties EMPTY = new Parties(null, null);
    }

    private record EventCandidate(IDecodeEvent event, long sequence)
    {
        private long timeStartMs()
        {
            return event.getTimeStart();
        }
    }
}

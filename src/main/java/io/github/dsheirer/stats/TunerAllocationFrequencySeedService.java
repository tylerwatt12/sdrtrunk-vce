/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationKnownSite;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationRuntimeChannel;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Reads existing confirmed frequencies in the background, independently of browser activity. No diagnostic is persisted. */
final class TunerAllocationFrequencySeedService implements AutoCloseable
{
    private final Supplier<List<TunerAllocationRuntimeChannel>> mCurrentChannels;
    private final Function<String,List<TunerAllocationKnownSite.Frequency>> mReader;
    private final BiConsumer<TunerAllocationContext,TunerAllocationKnownSite.Frequency> mObserver;
    private final ScheduledExecutorService mExecutor = Executors.newSingleThreadScheduledExecutor(
        new ObserverThreadFactory("tuner frequency knowledge"));

    TunerAllocationFrequencySeedService(StatsWebDatabase database, TunerManager manager,
                                         ChannelProcessingManager channels)
    {
        this(channels::getActiveTunerAllocationChannels, database::tunerAllocationKnownFrequencies,
            (context, frequency) -> manager.observeKnownFrequency(context, frequency.frequencyHz(),
                Math.toIntExact(frequency.bandwidthHz())));
    }

    TunerAllocationFrequencySeedService(Supplier<List<TunerAllocationRuntimeChannel>> currentChannels,
                                         Function<String,List<TunerAllocationKnownSite.Frequency>> reader,
                                         BiConsumer<TunerAllocationContext,TunerAllocationKnownSite.Frequency> observer)
    {
        mCurrentChannels = currentChannels;
        mReader = reader;
        mObserver = observer;
    }

    void start() { mExecutor.scheduleWithFixedDelay(this::refreshSafely, 3, 60, TimeUnit.SECONDS); }

    private void refreshSafely()
    {
        try { refresh(); }
        catch(RuntimeException ignored) { /* Database unavailable: configured/live frequencies remain sufficient to receive. */ }
    }

    void refresh()
    {
        Set<String> visited = new LinkedHashSet<>();
        for(TunerAllocationRuntimeChannel channel: mCurrentChannels.get())
        {
            TunerAllocationContext context = channel.context();
            if(context == null || context.role() != TunerAllocationContext.Role.CONTROL || context.channelId() == null ||
                !visited.add(context.channelId())) continue;
            if(visited.size() > 128 || Thread.currentThread().isInterrupted()) break;
            List<TunerAllocationKnownSite.Frequency> frequencies = mReader.apply(context.channelId());
            for(int index = 0; index < Math.min(256, frequencies.size()); index++)
            {
                TunerAllocationKnownSite.Frequency frequency = frequencies.get(index);
                TunerAllocationContext learned = new TunerAllocationContext(context.channelId(), context.channelName(),
                    context.systemName(), context.siteName(), context.preferredTuner(), frequency.role());
                mObserver.accept(learned, frequency);
            }
        }
    }

    @Override public void close() { mExecutor.shutdownNow(); }
}

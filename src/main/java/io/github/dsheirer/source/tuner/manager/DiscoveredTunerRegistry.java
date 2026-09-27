/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner1;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.DiscoveredRspDuoTuner2;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Receiver-owned tuner inventory. Neither discovery, allocation nor web reads depend on a Swing table model.
 * Changes are rare; snapshots and lookups are lock-free even when they originate on receiver threads.
 */
public final class DiscoveredTunerRegistry implements Listener<TunerEvent>, IDiscoveredTunerStatusListener
{
    private final CopyOnWriteArrayList<DiscoveredTuner> mTuners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ChangeListener> mChangeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Listener<TunerEvent>> mTunerEventListeners = new CopyOnWriteArrayList<>();
    private final TunerConfigurationManager mConfigurations;

    public DiscoveredTunerRegistry(TunerConfigurationManager configurations)
    {
        mConfigurations = Objects.requireNonNull(configurations);
    }

    public List<DiscoveredTuner> snapshot()
    {
        return List.copyOf(mTuners);
    }

    public List<DiscoveredTuner> availableTuners()
    {
        return mTuners.stream().filter(tuner -> tuner.isAvailable() && tuner.hasTuner()).toList();
    }

    public DiscoveredTuner find(String nativeId)
    {
        return nativeId == null ? null : mTuners.stream()
            .filter(tuner -> nativeId.equals(tuner.getId())).findFirst().orElse(null);
    }

    public boolean contains(DiscoveredTuner tuner)
    {
        return mTuners.contains(tuner);
    }

    public boolean hasUsbTuner(int bus, String portAddress)
    {
        return mTuners.stream().anyMatch(tuner -> tuner instanceof DiscoveredUSBTuner usb &&
            usb.isAt(bus, portAddress));
    }

    public DiscoveredTuner removeUsbTuner(int bus, String portAddress)
    {
        DiscoveredTuner tuner = mTuners.stream().filter(candidate -> candidate instanceof DiscoveredUSBTuner usb &&
            usb.isAt(bus, portAddress)).findFirst().orElse(null);
        if(tuner != null)
        {
            remove(tuner);
        }
        return tuner;
    }

    public void add(DiscoveredTuner tuner)
    {
        Objects.requireNonNull(tuner);
        if(mTuners.addIfAbsent(tuner))
        {
            tuner.addTunerStatusListener(this);
            if(tuner.hasTuner())
            {
                tuner.getTuner().addTunerEventListener(this);
            }
            changed(Change.ADDED, tuner, mTuners.indexOf(tuner));
        }
    }

    public void remove(DiscoveredTuner tuner)
    {
        int index = mTuners.indexOf(tuner);
        if(index >= 0 && mTuners.remove(tuner))
        {
            tuner.removeTunerStatusListener(this);
            if(tuner.hasTuner())
            {
                tuner.getTuner().removeTunerEventListener(this);
            }
            changed(Change.REMOVED, tuner, index);
            ThreadPool.CACHED.execute(tuner::stop);
        }
    }

    public void release()
    {
        List<DiscoveredTuner> tuners = snapshot();
        mTuners.clear();
        changed(Change.RESET, null, -1);
        for(DiscoveredTuner tuner: tuners)
        {
            tuner.removeTunerStatusListener(this);
            if(tuner.hasTuner())
            {
                tuner.getTuner().removeTunerEventListener(this);
            }
            tuner.stop();
        }
    }

    public void addChangeListener(ChangeListener listener)
    {
        mChangeListeners.addIfAbsent(listener);
    }

    public void removeChangeListener(ChangeListener listener)
    {
        mChangeListeners.remove(listener);
    }

    public void addTunerEventListener(Listener<TunerEvent> listener)
    {
        mTunerEventListeners.addIfAbsent(listener);
    }

    public void removeTunerEventListener(Listener<TunerEvent> listener)
    {
        mTunerEventListeners.remove(listener);
    }

    private void changed(Change change, DiscoveredTuner tuner, int index)
    {
        for(ChangeListener listener: mChangeListeners)
        {
            listener.changed(change, tuner, index);
        }
    }

    @Override
    public void tunerStatusUpdated(DiscoveredTuner tuner, TunerStatus previous, TunerStatus current)
    {
        if(current == TunerStatus.REMOVED)
        {
            // RSPduo master/slave mode shares one physical device; either removal retires both logical tuners.
            if(tuner instanceof DiscoveredRspDuoTuner1 master &&
                master.getDeviceInfo().getDeviceSelectionMode().isMasterMode())
            {
                DiscoveredTuner slave = find(master.getSlaveId());
                if(slave != null)
                {
                    remove(slave);
                }
            }
            else if(tuner instanceof DiscoveredRspDuoTuner2 slave &&
                slave.getDeviceInfo().getDeviceSelectionMode().isSlaveMode())
            {
                DiscoveredTuner master = find(slave.getMasterId());
                if(master != null)
                {
                    remove(master);
                }
            }
            remove(tuner);
            return;
        }

        if(current == TunerStatus.ENABLED && tuner.hasTuner())
        {
            tuner.getTuner().addTunerEventListener(this);
        }
        int index = mTuners.indexOf(tuner);
        if(index >= 0)
        {
            changed(Change.UPDATED, tuner, index);
        }
    }

    @Override
    public void receive(TunerEvent event)
    {
        if(event == null || !event.hasTuner())
        {
            return;
        }
        Tuner source = event.getTuner();
        DiscoveredTuner matching = mTuners.stream()
            .filter(tuner -> tuner.hasTuner() && tuner.getTuner() == source).findFirst().orElse(null);
        if(matching != null)
        {
            switch(event.getEvent())
            {
                case UPDATE_FREQUENCY_ERROR -> mConfigurations.updateTunerPPM(matching);
                case UPDATE_CHANNEL_COUNT, UPDATE_FREQUENCY, NOTIFICATION_ERROR_STATE,
                     UPDATE_LOCK_STATE, UPDATE_SAMPLE_RATE ->
                    changed(Change.UPDATED, matching, mTuners.indexOf(matching));
                default -> { }
            }
        }
        for(Listener<TunerEvent> listener: mTunerEventListeners)
        {
            listener.receive(event);
        }
    }

    public enum Change { ADDED, UPDATED, REMOVED, RESET }

    @FunctionalInterface
    public interface ChangeListener
    {
        void changed(Change change, DiscoveredTuner tuner, int index);
    }
}

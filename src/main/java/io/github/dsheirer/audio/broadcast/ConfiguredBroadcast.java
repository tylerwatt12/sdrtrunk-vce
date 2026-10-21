/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.audio.broadcast;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** A saved destination and its optional active sender, independent of desktop rendering. */
public final class ConfiguredBroadcast
{
    private final BroadcastConfiguration mConfiguration;
    private final List<Runnable> mChangeListeners = new CopyOnWriteArrayList<>();
    private volatile AbstractAudioBroadcaster<?> mAudioBroadcaster;
    private long mAudioBroadcasterVersion;
    private Runnable mSourceStateListener;

    public ConfiguredBroadcast(BroadcastConfiguration configuration)
    {
        mConfiguration = Objects.requireNonNull(configuration);
        mConfiguration.addChangeListener(this::notifyChanged);
    }

    public BroadcastConfiguration getBroadcastConfiguration() { return mConfiguration; }
    public BroadcastServerType getBroadcastServerType() { return mConfiguration.getBroadcastServerType(); }
    public AbstractAudioBroadcaster<?> getAudioBroadcaster() { return mAudioBroadcaster; }
    public boolean hasAudioBroadcaster() { return mAudioBroadcaster != null; }
    public void addChangeListener(Runnable listener) { mChangeListeners.add(listener); }
    public void removeChangeListener(Runnable listener) { mChangeListeners.remove(listener); }
    private void notifyChanged() { mChangeListeners.forEach(Runnable::run); }

    /** One coherent state/error pair; no GUI event queue sits between sender and operator status. */
    public AbstractAudioBroadcaster.StateSnapshot getStateSnapshot()
    {
        AbstractAudioBroadcaster<?> source = mAudioBroadcaster;
        return source != null ? source.getStateSnapshot() :
            new AbstractAudioBroadcaster.StateSnapshot(mConfiguration.isValid() ? BroadcastState.READY :
                BroadcastState.CONFIGURATION_ERROR, null);
    }

    public BroadcastState getBroadcastState() { return getStateSnapshot().state(); }
    public BroadcastState getLastBadBroadcastState() { return getStateSnapshot().lastBadState(); }

    public synchronized void setAudioBroadcaster(AbstractAudioBroadcaster<?> source)
    {
        long version = ++mAudioBroadcasterVersion;
        AbstractAudioBroadcaster<?> previous = mAudioBroadcaster;
        if(previous != null && mSourceStateListener != null)
            previous.removeStateChangeListener(mSourceStateListener);
        mAudioBroadcaster = source;
        mSourceStateListener = null;
        if(source != null)
        {
            mSourceStateListener = () -> sourceStateChanged(source, version);
            source.addStateChangeListener(mSourceStateListener);
        }
        notifyChanged();
    }

    private synchronized void sourceStateChanged(AbstractAudioBroadcaster<?> source, long version)
    {
        if(mAudioBroadcaster == source && mAudioBroadcasterVersion == version) notifyChanged();
    }
}

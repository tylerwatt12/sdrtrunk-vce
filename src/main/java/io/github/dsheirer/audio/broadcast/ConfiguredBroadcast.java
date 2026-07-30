/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.audio.broadcast;

import java.awt.GraphicsEnvironment;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.util.Callback;

/**
 * Composite observable object that joins a broadcast configuration and (optional) constructed audio broadcaster
 */
public class ConfiguredBroadcast
{
    private final BroadcastConfiguration mBroadcastConfiguration;
    private final Consumer<Runnable> mFxDispatcher;
    private volatile AbstractAudioBroadcaster<?> mAudioBroadcaster;
    private volatile long mAudioBroadcasterVersion;
    private ChangeListener<BroadcastState> mSourceBroadcastStateListener;
    private final ObjectProperty<BroadcastState> mBroadcastState = new SimpleObjectProperty<>();
    private final ObjectProperty<BroadcastState> mLastBadBroadcastState = new SimpleObjectProperty<>();

    /**
     * Constructs an instance
     * @param broadcastConfiguration for the instance
     */
    public ConfiguredBroadcast(BroadcastConfiguration broadcastConfiguration)
    {
        this(broadcastConfiguration, ConfiguredBroadcast::dispatchToJavaFx);
    }

    /**
     * Constructs an instance with an injectable JavaFX dispatcher for deterministic thread-handoff tests.
     */
    ConfiguredBroadcast(BroadcastConfiguration broadcastConfiguration, Consumer<Runnable> fxDispatcher)
    {
        mBroadcastConfiguration = broadcastConfiguration;
        mFxDispatcher = fxDispatcher;
        mBroadcastState.setValue(mBroadcastConfiguration.isValid() ? BroadcastState.READY :
            BroadcastState.CONFIGURATION_ERROR);
        mBroadcastConfiguration.validProperty()
            .addListener((observable, oldValue, newValue) -> queueConfigurationStateUpdate());
    }

    /**
     * Configuration for this broadcast
     */
    public BroadcastConfiguration getBroadcastConfiguration()
    {
        return mBroadcastConfiguration;
    }

    /**
     * Enabled state of the configuration
     */
    public BooleanProperty enabledProperty()
    {
        return mBroadcastConfiguration.enabledProperty();
    }

    /**
     * Name of the broadcast configuration
     */
    public StringProperty nameProperty()
    {
        return mBroadcastConfiguration.nameProperty();
    }

    /**
     * Server type for the broadcast configuration
     */
    public BroadcastServerType getBroadcastServerType()
    {
        return mBroadcastConfiguration.getBroadcastServerType();
    }

    /**
     * Broadcast state of the configured audio broadcaster (optional)
     */
    public ObjectProperty<BroadcastState> broadcastStateProperty()
    {
        return mBroadcastState;
    }

    /**
     * Last bad broadcast state of the configured audio broadcaster (optional)
     */
    public ObjectProperty<BroadcastState> lastBadBroadcastStateProperty()
    {
        return mLastBadBroadcastState;
    }

    /**
     * Sets the audio broadcaster
     * @param audioBroadcaster to use for this configuration
     */
    public synchronized void setAudioBroadcaster(AbstractAudioBroadcaster<?> audioBroadcaster)
    {
        //Invalidate already-queued updates before detaching the old source.
        long audioBroadcasterVersion = ++mAudioBroadcasterVersion;
        AbstractAudioBroadcaster<?> previousAudioBroadcaster = mAudioBroadcaster;

        if(previousAudioBroadcaster != null && mSourceBroadcastStateListener != null)
        {
            previousAudioBroadcaster.broadcastStateProperty().removeListener(mSourceBroadcastStateListener);
        }

        mSourceBroadcastStateListener = null;
        mAudioBroadcaster = audioBroadcaster;

        if(audioBroadcaster != null)
        {
            mSourceBroadcastStateListener = (observable, oldValue, newValue) ->
                queueAudioBroadcasterStateUpdate(audioBroadcaster, audioBroadcasterVersion);
            audioBroadcaster.broadcastStateProperty().addListener(mSourceBroadcastStateListener);
            queueAudioBroadcasterStateUpdate(audioBroadcaster, audioBroadcasterVersion);
        }
        else
        {
            queueConfigurationStateUpdate(audioBroadcasterVersion);
        }
    }

    /**
     * Copies the worker-owned broadcaster state to the table-facing properties on the JavaFX application thread.
     * Each callback reads the source's latest values instead of retaining an intermediate connection state.
     */
    private void queueAudioBroadcasterStateUpdate(AbstractAudioBroadcaster<?> source, long audioBroadcasterVersion)
    {
        mFxDispatcher.accept(() -> {
            if(mAudioBroadcaster == source && mAudioBroadcasterVersion == audioBroadcasterVersion)
            {
                //Last-error changes occur before the corresponding state change.  Copy it first so the row update
                //triggered by the state property observes a consistent pair.
                mLastBadBroadcastState.setValue(source.getLastBadBroadcastState());
                mBroadcastState.setValue(source.getBroadcastState());
            }
        });
    }

    private void queueConfigurationStateUpdate()
    {
        queueConfigurationStateUpdate(mAudioBroadcasterVersion);
    }

    /**
     * Restores the row's configuration-only state after a broadcaster is detached.  A newly attached broadcaster
     * invalidates this queued update before it can overwrite the replacement's state.
     */
    private void queueConfigurationStateUpdate(long audioBroadcasterVersion)
    {
        mFxDispatcher.accept(() -> {
            if(mAudioBroadcaster == null && mAudioBroadcasterVersion == audioBroadcasterVersion)
            {
                mBroadcastState.setValue(mBroadcastConfiguration.isValid() ? BroadcastState.READY :
                    BroadcastState.CONFIGURATION_ERROR);
                mLastBadBroadcastState.setValue(null);
            }
        });
    }

    /**
     * Dispatches table-facing property changes to JavaFX.  Headless operation and construction before the JavaFX
     * toolkit starts have no JavaFX controls observing these properties, so direct execution is safe in those cases.
     */
    private static void dispatchToJavaFx(Runnable runnable)
    {
        if(GraphicsEnvironment.isHeadless() || Platform.isFxApplicationThread())
        {
            runnable.run();
            return;
        }

        try
        {
            Platform.runLater(runnable);
        }
        catch(IllegalStateException e)
        {
            //The JavaFX toolkit has not started yet, so no JavaFX table can be observing this row.
            runnable.run();
        }
    }

    /**
     * Optional audio broadcaster created from the configuration
     */
    public AbstractAudioBroadcaster<?> getAudioBroadcaster()
    {
        return mAudioBroadcaster;
    }

    /**
     * Indicates if this configured broadcast has a non-null audio broadcaster assigned
     */
    public boolean hasAudioBroadcaster()
    {
        return mAudioBroadcaster != null;
    }

    /**
     * Creates an observable property extractor for use with observable lists to detect changes internal to this object.
     */
    public static Callback<ConfiguredBroadcast, Observable[]> extractor()
    {
        return (ConfiguredBroadcast b) -> new Observable[] {b.nameProperty(), b.enabledProperty(),
            b.broadcastStateProperty(), b.getBroadcastConfiguration().validProperty()};
    }
}

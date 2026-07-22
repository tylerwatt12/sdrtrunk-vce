/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A discovered tuner that may be accessible for use.
 */
public abstract class DiscoveredTuner implements ITunerErrorListener
{
    private static final long LIFECYCLE_QUIESCE_TIMEOUT_SECONDS = 5;
    private Logger mLog = LoggerFactory.getLogger(DiscoveredTuner.class);
    private volatile TunerStatus mTunerStatus = TunerStatus.ENABLED;
    private volatile boolean mEnabled = true;
    private volatile String mErrorMessage;
    private List<IDiscoveredTunerStatusListener> mListeners = new CopyOnWriteArrayList<>();
    protected volatile Tuner mTuner;
    protected volatile TunerConfiguration mTunerConfiguration;
    private final Object mLifecycleLock = new Object();
    private boolean mLifecycleQuiescing;
    private int mLifecycleLeaseCount;
    private final List<Runnable> mLifecycleQuiesceListeners = new ArrayList<>();

    /**
     * Tuner Class
     */
    public abstract TunerClass getTunerClass();

    /**
     * Current status of the discovered tuner
     */
    public TunerStatus getTunerStatus()
    {
        return mTunerStatus;
    }

    /**
     * Logs current state of the tuner
     */
    public void logState()
    {
        mLog.info(getDiagnosticReport());
    }

    /**
     * Generates a state report for this tuner.
     * @return
     */
    public String getDiagnosticReport()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Discovered Tuner: ").append(getId());
        sb.append("\n\tClass:").append(getClass());

        if(hasTuner())
        {
            sb.append("\n\tTuner Class:").append(getTuner().getClass());
            sb.append("\n\tTuner Controller Class:").append(getTuner().getTunerController().getClass());
            sb.append("\n\tFrequency:").append(getTuner().getTunerController().getFrequency());
            sb.append("\n\tError:").append(getErrorMessage());
            sb.append("\n\tChannel Manager Class:").append(getTuner().getChannelSourceManager().getClass());
            sb.append("\n\tChannel Manager:").append(getTuner().getChannelSourceManager().getStateDescription());
        }
        else
        {
            sb.append("\n\tTuner - no tuner");
        }

        return sb.toString();
    }

    /**
     * Sets the status of the discovered tuner and notifies registered listeners of the status change.
     * @param tunerStatus to set
     */
    private void setTunerStatus(TunerStatus tunerStatus)
    {
        setTunerStatus(tunerStatus, true);
    }

    /**
     * Sets the status of the discovered tuner and optionally notifies registered listeners of the status change.
     * @param tunerStatus to set
     * @param notifyListeners true to notify and false to not notify
     */
    private void setTunerStatus(TunerStatus tunerStatus, boolean notifyListeners)
    {
        if(mTunerStatus != tunerStatus)
        {
            TunerStatus previous = mTunerStatus;
            mTunerStatus = tunerStatus;

            if(notifyListeners)
            {
                broadcast(this, previous, mTunerStatus);
            }
        }
    }

    /**
     * Indicates if this discovered tuner is enabled and usable.
     */
    public boolean isEnabled()
    {
        return mEnabled;
    }

    /**
     * Sets the enabled state of this discovered tuner
     */
    public synchronized void setEnabled(boolean enabled)
    {
        boolean retryingIncompleteStop = !enabled && !mEnabled && hasTuner() && isLifecycleQuiescing();

        //Apply a normal state change, or allow a repeated disable command to finish quarantined native cleanup.
        if((mEnabled ^ enabled) || retryingIncompleteStop)
        {
            mErrorMessage = null;

            if(enabled)
            {
                if(!mEnabled && hasTuner() && isLifecycleQuiescing())
                {
                    throw new IllegalStateException("Receiver cleanup must finish before it can be enabled");
                }

                //Keep allocations and passive sample consumers out until startup and configuration listeners finish.
                setLifecycleQuiescing(true);
                mEnabled = true;
                TunerStatus previousStatus = getTunerStatus();

                try
                {
                    //Concrete USB discovery starts only while status is ENABLED. Publish that prerequisite silently,
                    //open the hardware directly, and notify listeners only after a real tuner exists.
                    setTunerStatus(TunerStatus.ENABLED, false);
                    start();

                    if(hasTuner() && getTunerStatus().isAvailable())
                    {
                        if(previousStatus != TunerStatus.ENABLED)
                        {
                            setTunerStatus(previousStatus, false);
                            setTunerStatus(TunerStatus.ENABLED);
                        }

                        setLifecycleQuiescing(false);
                    }
                    else
                    {
                        mEnabled = false;

                        if(getTunerStatus() == TunerStatus.ENABLED)
                        {
                            mErrorMessage = "Receiver did not finish starting";
                            setTunerStatus(TunerStatus.ERROR);
                        }

                        throw new IllegalStateException("Receiver did not finish starting");
                    }
                }
                catch(RuntimeException | Error exception)
                {
                    mEnabled = false;

                    if(getTunerStatus() == TunerStatus.ENABLED)
                    {
                        mErrorMessage = "Receiver did not finish starting: " + exception.getMessage();
                        setTunerStatus(TunerStatus.ERROR);
                    }

                    //Remain quiesced.  A failed or partially-started receiver must not accept new consumers.
                    throw exception;
                }
            }
            else
            {
                mEnabled = false;

                try
                {
                    //No lifecycle lock is held while channel events, DSP disposal, or native USB shutdown execute.
                    stop();
                    setTunerStatus(TunerStatus.DISABLED);
                }
                catch(RuntimeException | Error exception)
                {
                    //Keep a partially-stopped receiver quarantined.  A later disable command may retry cleanup, but
                    //new allocations and a second native handle remain forbidden.
                    mErrorMessage = "Receiver shutdown did not finish: " + exception.getMessage();
                    setTunerStatus(TunerStatus.ERROR);
                    throw exception;
                }
            }
        }
    }

    /**
     * Acquires a short-lived lifecycle lease for source allocation, live settings, or a passive sample consumer.
     * The lease prevents native shutdown until the caller has either registered its new work or detached it.
     * No radio callback, channel monitor, or controller lock is held while this bookkeeping lock is used.
     */
    public LifecycleLease tryAcquireLifecycleLease()
    {
        synchronized(mLifecycleLock)
        {
            Tuner tuner = mTuner;

            if(mLifecycleQuiescing || !mEnabled || !mTunerStatus.isAvailable() || tuner == null)
            {
                return null;
            }

            mLifecycleLeaseCount++;
            return new LifecycleLease(this, tuner);
        }
    }

    /**
     * Indicates that this receiver is refusing new work while it starts, stops, or remains disabled.
     */
    public boolean isLifecycleQuiescing()
    {
        synchronized(mLifecycleLock)
        {
            return mLifecycleQuiescing;
        }
    }

    private boolean beginLifecycleQuiesce(long timeout, TimeUnit timeUnit)
    {
        long remainingNanos = timeUnit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        List<Runnable> listeners;

        synchronized(mLifecycleLock)
        {
            mLifecycleQuiescing = true;
            listeners = List.copyOf(mLifecycleQuiesceListeners);
            //Registrations describe work owned by this specific live tuner instance and are one-shot.
            mLifecycleQuiesceListeners.clear();
        }

        //Notify outside the lifecycle bookkeeping lock. A channel callback may synchronously stop its processing
        //chain and release an allocation lease that this quiesce is about to wait for.
        for(Runnable listener: listeners)
        {
            try
            {
                listener.run();
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Receiver lifecycle listener failed while quiescing [{}]", getId(), exception);
            }
        }

        synchronized(mLifecycleLock)
        {

            while(mLifecycleLeaseCount > 0)
            {
                if(remainingNanos <= 0)
                {
                    return false;
                }

                try
                {
                    TimeUnit.NANOSECONDS.timedWait(mLifecycleLock, remainingNanos);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }

                remainingNanos = deadline - System.nanoTime();
            }

            return true;
        }
    }

    /**
     * Registers one owner callback that fires when this live receiver begins shutdown.  A null result means shutdown
     * already began and the caller must not attach new work.
     */
    public LifecycleQuiesceRegistration tryRegisterLifecycleQuiesceListener(Runnable listener)
    {
        if(listener == null)
        {
            throw new IllegalArgumentException("Lifecycle listener cannot be null");
        }

        synchronized(mLifecycleLock)
        {
            if(mLifecycleQuiescing || !mEnabled || !mTunerStatus.isAvailable() || mTuner == null)
            {
                return null;
            }

            mLifecycleQuiesceListeners.add(listener);
            return new LifecycleQuiesceRegistration(this, listener);
        }
    }

    private void removeLifecycleQuiesceListener(Runnable listener)
    {
        synchronized(mLifecycleLock)
        {
            mLifecycleQuiesceListeners.remove(listener);
        }
    }

    private void setLifecycleQuiescing(boolean quiescing)
    {
        synchronized(mLifecycleLock)
        {
            mLifecycleQuiescing = quiescing;
            mLifecycleLock.notifyAll();
        }
    }

    private void releaseLifecycleLease()
    {
        synchronized(mLifecycleLock)
        {
            if(mLifecycleLeaseCount <= 0)
            {
                throw new IllegalStateException("Receiver lifecycle lease count underflow");
            }

            mLifecycleLeaseCount--;

            if(mLifecycleLeaseCount == 0)
            {
                mLifecycleLock.notifyAll();
            }
        }
    }

    /**
     * One bounded ownership handoff around a live tuner.  Close is idempotent so failure cleanup can be simple.
     */
    public static final class LifecycleLease implements AutoCloseable
    {
        private final DiscoveredTuner mOwner;
        private final Tuner mTuner;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private LifecycleLease(DiscoveredTuner owner, Tuner tuner)
        {
            mOwner = owner;
            mTuner = tuner;
        }

        public Tuner getTuner()
        {
            return mTuner;
        }

        DiscoveredTuner owner()
        {
            return mOwner;
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mOwner.releaseLifecycleLease();
            }
        }
    }

    /**
     * Removable one-shot shutdown notification owned by one live receiver instance.
     */
    public static final class LifecycleQuiesceRegistration implements AutoCloseable
    {
        private final DiscoveredTuner mOwner;
        private final Runnable mListener;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private LifecycleQuiesceRegistration(DiscoveredTuner owner, Runnable listener)
        {
            mOwner = owner;
            mListener = listener;
        }

        @Override
        public void close()
        {
            if(mClosed.compareAndSet(false, true))
            {
                mOwner.removeLifecycleQuiesceListener(mListener);
            }
        }
    }

    /**
     * Indicates if this discovered tuner is available and usable.  Use this method to check if a discovered tuner is
     * available prior to access the tuner directly via the getTuner() method.
     */
    public boolean isAvailable()
    {
        return getTunerStatus().isAvailable();
    }

    /**
     * An identifier for this discovered tuner where this identifier when combined with the discovered tuner type
     * is a globally unique value.
     *
     * Note: this identifier will be used to persist the enabled/disabled state of each discoverable tuner.  Therefore,
     * this identifier must be consistent across application run cycles in order to correctly manage disabled tuners.
     * @return globally unique string identifier for the discovered tuner.
     */
    public abstract String getId();

    /**
     * Access a started and initialized.
     *
     * Use the isAvailable() method to check if this tuner is available prior to invoking this method to avoid a
     * null tuner instance.
     *
     * @return started and initialized tuner
     */
    public Tuner getTuner()
    {
        return mTuner;
    }

    /**
     * Indicates if this discovered tuner is started and has a fully constructed tuner instance
     */
    public boolean hasTuner()
    {
        return getTuner() != null;
    }

    /**
     * Tuner configuration for this tuner
     */
    public TunerConfiguration getTunerConfiguration()
    {
        return mTunerConfiguration;
    }

    /**
     * Sets the tuner configuration for this tuner.
     */
    public void setTunerConfiguration(TunerConfiguration tunerConfiguration)
    {
        mTunerConfiguration = tunerConfiguration;

        if(hasTuner())
        {
            try
            {
                getTuner().getTunerController().apply(mTunerConfiguration);
            }
            catch(SourceException se)
            {
                mLog.error("Error applying tuner configuration [" + mTunerConfiguration.getClass() +
                        "] to discovered tuner [" + getId() + "}", se);
            }
        }
    }

    /**
     * Indicates if this discovered tuner has a tuner configuration
     */
    public boolean hasTunerConfiguration()
    {
        return mTunerConfiguration != null;
    }

    /**
     * Adds a tuner status change listener to monitor this discovered tuner for changes in status.
     */
    public void addTunerStatusListener(IDiscoveredTunerStatusListener listener)
    {
        if(!mListeners.contains(listener))
        {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a tuner status change listener from monitoring this discovered tuner for changes in status.
     */
    public void removeTunerStatusListener(IDiscoveredTunerStatusListener listener)
    {
        mListeners.remove(listener);
    }

    /**
     * Broadcasts the tuner status change to all registered listeners.
     * @param tuner that was changed
     * @param previous tuner status
     * @param current tuner status
     */
    private void broadcast(DiscoveredTuner tuner, TunerStatus previous, TunerStatus current)
    {
        for(IDiscoveredTunerStatusListener listener: mListeners)
        {
            listener.tunerStatusUpdated(tuner, previous, current);
        }
    }

    /**
     * Sets this tuner to an error state and applies the error message
     * @param errorMessage to set
     */
    @Override
    public void setErrorMessage(String errorMessage)
    {
        mErrorMessage = errorMessage;
        mLog.info("Tuner Error - Stopping - " + getId() + " Error: " + errorMessage);

        try
        {
            stop();
        }
        catch(RuntimeException | Error exception)
        {
            //The event callback may be the very thread that native shutdown needs to join.  Keep the tuner attached
            //and quiesced so a control thread can retry the complete shutdown instead of clearing a live USB handle.
            mErrorMessage = errorMessage + " (shutdown pending: " + exception.getMessage() + ")";
            mLog.warn("Receiver shutdown remains pending for [{}]", getId(), exception);
        }
        finally
        {
            setTunerStatus(TunerStatus.ERROR);
        }
    }

    @Override
    public void tunerRemoved()
    {
        setTunerStatus(TunerStatus.REMOVED);
    }

    /**
     * Indicates if this tuner has an error message.
     */
    public boolean hasErrorMessage()
    {
        return mErrorMessage != null;
    }

    /**
     * Optional error message.
     * @return error message if there is one, or null if there is not.
     */
    public String getErrorMessage()
    {
        return mErrorMessage;
    }

    /**
     * Fully instantiate and start this discovered tuner to make it usable within the application.  Implementations
     * should attempt to instantiate the tuner and assign it to mTuner variable.  If there is an error, invoke the
     * setErrorMessage() to signal the tuner is unusable.
     */
    public abstract void start();

    /**
     * Attempts to restart a tuner that's currently in an error state
     */
    public synchronized void restart()
    {
        if(getTunerStatus() == TunerStatus.ERROR)
        {
            mErrorMessage = null;

            if(isEnabled())
            {
                //An error shutdown leaves the receiver quiesced so no new work can race the native teardown.  Keep
                //that gate closed while reopening, then explicitly make the successfully restarted tuner available.
                setLifecycleQuiescing(true);
                //Change status to enabled so that we can attempt to start, but don't notify listeners yet.
                setTunerStatus(TunerStatus.ENABLED);
                start();

                if(hasTuner() && getTunerStatus().isAvailable())
                {
                    setLifecycleQuiescing(false);
                }
            }
            else
            {
                setTunerStatus(TunerStatus.DISABLED);
            }
        }
    }

    /**
     * Stop this discovered tuner, notify registered listeners/consumers and release any resources that it is using.
     */
    public synchronized void stop()
    {
        if(!beginLifecycleQuiesce(LIFECYCLE_QUIESCE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            throw new IllegalStateException("Receiver is still finishing radio work");
        }

        if(hasTuner())
        {
            mLog.info("Stopping Tuner: " + getId());
            getTuner().stop();
            mTuner = null;
        }
    }
}

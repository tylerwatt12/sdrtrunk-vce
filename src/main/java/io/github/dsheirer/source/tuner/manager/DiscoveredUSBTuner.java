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

package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerFactory;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A USB tuner that is discovered during the USB device traversal process that has a VID and PID that match a known
 * USB tuner type that is currently supported by the software.
 */
public class DiscoveredUSBTuner extends DiscoveredTuner
{
    private Logger mLog = LoggerFactory.getLogger(DiscoveredUSBTuner.class);
    private TunerClass mTunerClass;
    private USBTunerController mRetiredController;
    private volatile long mRuntimeGeneration;
    private volatile boolean mManagedRecovery;
    private final AtomicReference<RuntimeFailure> mRuntimeFailure = new AtomicReference<>();
    private int mDeviceAddress = -1;
    private int mBus;
    private String mPortAddress;

    /**
     * Constructs an instance
     * @param bus (USB) number
     * @param portAddress (USB)
     */
    public DiscoveredUSBTuner(TunerClass tunerClass, int bus, String portAddress)
    {
        mTunerClass = tunerClass;
        mBus = bus;
        mPortAddress = portAddress;
    }

    void setDeviceAddress(int address) { mDeviceAddress = address; }

    void enableManagedRecovery() { mManagedRecovery = true; }

    @Override
    public boolean isAvailable()
    {
        RuntimeFailure failure = mRuntimeFailure.get();
        return (failure == null || failure.generation != mRuntimeGeneration) && super.isAvailable();
    }

    /** Called on the lifecycle worker, never on the native/decoder callback that reported the failure. */
    boolean publishRuntimeFailure()
    {
        RuntimeFailure failure = mRuntimeFailure.get();
        if(failure == null) return false;
        boolean[] accepted = {false};
        withLifecycleLock(() -> {
            if(failure.generation == mRuntimeGeneration && isEnabled())
            {
                markErrorMessage(failure.message);
                accepted[0] = true;
            }
            // Keep the allocation gate closed while waiting for a concurrent settings operation to release it.
            mRuntimeFailure.compareAndSet(failure, null);
        });
        return accepted[0];
    }

    void markRecoveryNeeded(String message)
    {
        withLifecycleLock(() -> { if(isEnabled()) markErrorMessage(message); });
    }

    private void reportRuntimeFailure(long generation, String message)
    {
        if(generation != mRuntimeGeneration) return;
        if(mManagedRecovery)
        {
            // One latest-value slot per physical radio: a burst of callbacks never waits for cleanup, channels, UI
            // observers or the USB worker, and it cannot create an unbounded recovery queue.
            RuntimeFailure pending = mRuntimeFailure.get();
            if(generation == mRuntimeGeneration && (pending == null || pending.generation <= generation))
                mRuntimeFailure.compareAndSet(pending, new RuntimeFailure(generation, message));
        }
        else
        {
            withLifecycleLock(() -> {
                if(generation == mRuntimeGeneration) DiscoveredUSBTuner.this.setErrorMessage(message);
            });
        }
    }

    boolean isSameAttachment(DiscoveredUSBTuner other)
    {
        return isAt(other.getBus(), other.getPortAddress()) && getTunerClass() == other.getTunerClass() &&
            mDeviceAddress == other.mDeviceAddress;
    }

    /** A new attachment at the same port reuses saved settings and the operator's current mode. */
    void recoverAttachment(DiscoveredUSBTuner attached)
    {
        withLifecycleLock(() -> {
            mDeviceAddress = attached.mDeviceAddress;
            if(!isEnabled()) return;
            setErrorMessage("USB device reconnected");
            restart();
        });
    }

    /**
     * Indicates if this USB tuner is plugged into the specified bus and port.
     * @param bus number
     * @param port address
     * @return true if there is a match
     */
    public boolean isAt(int bus, String port)
    {
        return mBus == bus && port != null && port.equalsIgnoreCase(mPortAddress);
    }

    /**
     * Tuner class as determined from the USB VID and PID values
     */
    public TunerClass getTunerClass()
    {
        return mTunerClass;
    }

    /**
     * USB bus number
     */
    public int getBus()
    {
        return mBus;
    }

    /**
     * USB port number
     */
    public String getPortAddress()
    {
        return mPortAddress;
    }

    @Override
    public String getId()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(getTunerClass());
        sb.append(" USB Bus:").append(getBus());
        sb.append(" Port:").append(getPortAddress());
        return sb.toString();
    }

    @Override
    public void start()
    {
        if(isEnabled() && !hasTuner())
        {
            try
            {
                if(!isUsbStopComplete())
                    throw new SourceException("Previous USB shutdown did not finish. Restart the receiver before retrying.");
                mRetiredController = null;
                mRuntimeGeneration++;
                mTuner = createTuner();
                mTuner.start();
            }
            catch(SourceException se)
            {
                //Set error message to flag this tuner with error status and invoke stop()
                setErrorMessage(se.getMessage());
                mLog.error("Unable to start tuner [" + getTunerClass() + "] - error: " + getErrorMessage());
            }
        }
    }

    /** Creates a fresh controller only after the preceding controller has relinquished native ownership. */
    protected Tuner createTuner() throws SourceException
    {
        return TunerFactory.getUsbTuner(getTunerClass(), getPortAddress(), getBus(), createRuntimeListener());
    }

    protected ITunerErrorListener createRuntimeListener()
    {
        long generation = mRuntimeGeneration;
        return new ITunerErrorListener()
        {
            @Override public void setErrorMessage(String message)
            {
                reportRuntimeFailure(generation, message);
            }
            @Override public void tunerRemoved()
            {
                if(mManagedRecovery) reportRuntimeFailure(generation, "USB radio disconnected");
                else withLifecycleLock(() -> {
                    if(generation == mRuntimeGeneration) DiscoveredUSBTuner.this.tunerRemoved();
                });
            }
        };
    }

    @Override
    protected void runtimeStopped(Tuner tuner)
    {
        mRuntimeGeneration++;
        if(tuner.getTunerController() instanceof USBTunerController usb && !usb.isUsbShutdownComplete())
            mRetiredController = usb;
    }

    boolean isUsbStopComplete()
    {
        return mRetiredController == null || mRetiredController.isUsbShutdownComplete();
    }

    private record RuntimeFailure(long generation, String message) {}

    @Override
    public String toString()
    {
        return "USB Tuner - " + getId();
    }
}

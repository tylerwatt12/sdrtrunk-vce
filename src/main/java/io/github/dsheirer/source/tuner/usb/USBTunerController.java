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
package io.github.dsheirer.source.tuner.usb;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Context;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;
import org.usb4java.Transfer;
import org.usb4java.TransferCallback;

/**
 * Tuner controller implementation for USB tuners.  Manages general USB operations and incorporates threaded USB
 * Transfer processing.
 */
public abstract class USBTunerController extends TunerController
{
    private Logger mLog = LoggerFactory.getLogger(USBTunerController.class);
    private static final int USB_INTERFACE = 0x0;  //Common value for all currently supported devices
    private static final int USB_CONFIGURATION = 0x1;  //Common value for all currently supported devices
    private static final int USB_BULK_TRANSFER_BUFFER_POOL_SIZE = 8;
    private static final long USB_TRANSFER_LONG_GAP_MILLISECONDS = 200L;
    protected static final byte USB_BULK_TRANSFER_ENDPOINT = (byte) 0x81;
    private static final long USB_BULK_TRANSFER_TIMEOUT_MS = 2000l;
    private static final long USB_EVENT_STOP_TIMEOUT_MS = 500;
    private static final long USB_TRANSFER_DRAIN_TIMEOUT_MS = 500;
    private static final long USB_DEVICE_SHUTDOWN_TIMEOUT_MS = 1500;
    private static final long USB_FINAL_RELEASE_LOCK_TIMEOUT_MS = 500;

    protected int mBus;
    protected String mPortAddress;
    private volatile Context mDeviceContext = new Context();
    private Device mDevice;
    private DeviceHandle mDeviceHandle;
    private DeviceDescriptor mDeviceDescriptor;
    private TransferManager mTransferManager = new TransferManager();
    private UsbEventProcessor mEventProcessor = new UsbEventProcessor();
    private final UsbTransferHealth mUsbTransferHealth = new UsbTransferHealth();
    private AtomicBoolean mStreaming = new AtomicBoolean();
    private AtomicBoolean mStopping = new AtomicBoolean();
    private volatile boolean mRunning = false;

    /**
     * USB tuner controller class. Provides auto-start and auto-stop function when complex buffer listeners are added
     * or removed from this tuner controller.
     *
     * @param bus number USB
     * @param portAddress address USB
     * @param tunerErrorListener to receive errors from this tuner controller
     */
    protected USBTunerController(int bus, String portAddress, ITunerErrorListener tunerErrorListener)
    {
        super(tunerErrorListener);
        mBus = bus;
        mPortAddress = portAddress;
    }

    /**
     * Constructs an instance
     * @param bus usb
     * @param portAddress usb
     * @param minimum tunable frequency in Hertz
     * @param maximum tunable frequency in Hertz
     * @param halfBandwidth that is unusable for DC spike avoidance
     * @param usablePercent bandwith in Hertz
     * @param tunerErrorListener to receive errors from this tuner controller
     */
    protected USBTunerController(int bus, String portAddress, long minimum, long maximum, int halfBandwidth, double usablePercent,
                              ITunerErrorListener tunerErrorListener)
    {
        this(bus, portAddress, tunerErrorListener);
        setMinimumFrequency(minimum);
        setMaximumFrequency(maximum);
        setMiddleUnusableHalfBandwidth(halfBandwidth);
        setUsableBandwidthPercentage(usablePercent);
    }

    /**
     * Tuner type for this USB controller
     */
    public abstract TunerType getTunerType();

    /**
     * Factory for converting received streaming sample data into native buffers, as provided by sub-class.
     */
    protected abstract INativeBufferFactory getNativeBufferFactory();

    /**
     * Sub-class definition of transfer buffer sizes to use for the tuner.  Note: this should be a power-of-two
     * value for compatibility with downstream (SIMD) operations (e.g. 65,536, 131072, 262144, etc.)
     * @return transfer buffer size.
     */
    protected abstract int getTransferBufferSize();

    /**
     * Immutable snapshot of USB sample-transfer measurements for this tuner.  The cumulative byte and transfer
     * counters give an off-thread sampler enough information to calculate delivered throughput between snapshots.
     */
    public UsbTransferHealthSnapshot getUsbTransferHealthSnapshot()
    {
        return mUsbTransferHealth.snapshot();
    }

    /**
     * Sub-class method to perform additional device setup steps after the USB interface has been claimed and before any
     * transfer operations start.
     * @throws SourceException if there is an issue in configuring the device
     */
    protected abstract void deviceStart() throws SourceException;

    /**
     * Sub-class method to perform additional device shutdown steps after transfer processing has stopped and before
     * the USB interface is released.
     */
    protected abstract void deviceStop();

    /**
     * Starts or initializes this tuner.
     *
     * Note: sub-class implementations should override and invoke this method and then perform additional initialization
     * operations for the tuner.
     *
     * @throws SourceException if there is an error and/or this tuner is unusable.
     */
    public final void start() throws SourceException
    {
        if(mDeviceContext == null)
        {
            throw new SourceException("Device cannot be reused once it has been shutdown");
        }

        int status = LibUsb.init(mDeviceContext);

        if(status != LibUsb.SUCCESS)
        {
            throw new SourceException("Can't initialize libusb library - " + LibUsb.errorName(status));
        }

        mDevice = findDevice();

        if(mDevice == null)
        {
            throw new SourceException("Couldn't find USB device at bus [" + mBus + "] port [" + mPortAddress + "]");
        }

        //Capture negotiated link speed during startup.  Querying libusb here keeps native calls off the transfer path,
        //and speed discovery is optional so that observability cannot prevent a tuner from starting.
        try
        {
            mUsbTransferHealth.setNegotiatedDeviceSpeed(LibUsb.getDeviceSpeed(mDevice));
        }
        catch(RuntimeException | LinkageError ignored)
        {
            mUsbTransferHealth.setNegotiatedDeviceSpeed(LibUsb.SPEED_UNKNOWN);
        }

        mDeviceDescriptor = new DeviceDescriptor();
        status = LibUsb.getDeviceDescriptor(mDevice, mDeviceDescriptor);

        if(status != LibUsb.SUCCESS)
        {
            mDeviceDescriptor = null;
            throw new SourceException("Can't obtain tuner's device descriptor - " + LibUsb.errorName(status));
        }

        mDeviceHandle = new DeviceHandle();
        status = LibUsb.open(mDevice, mDeviceHandle);

        //Now that we have opened the device and added an additional reference, remove the original reference placed on
        // the device during the findDevice() operation
        LibUsb.unrefDevice(mDevice);

        if(status == LibUsb.ERROR_ACCESS)
        {
            mDeviceHandle = null;
            mDeviceDescriptor = null;

            mLog.error("Access to USB tuner denied - (windows) reinstall zadig driver or (linux) blacklist driver and/or check udev rules");
            throw new SourceException("access denied - if using linux, blacklist the default driver and/or install udev rules");
        }
        else if(status != LibUsb.SUCCESS)
        {
            mDeviceHandle = null;
            mDeviceDescriptor = null;

            mLog.error("Can't open USB tuner - check driver or Linux udev rules");
            throw new SourceException("Can't open USB tuner - reinstall driver? - " + LibUsb.errorName(status));
        }

        //Detach the kernel driver if active and detach is supported.  Otherwise, let the claim interface fail.
        status = LibUsb.kernelDriverActive(mDeviceHandle, USB_INTERFACE);

        if(status == 1) //kernel driver is attached and detach operation is supported
        {
            status = LibUsb.detachKernelDriver(mDeviceHandle, USB_INTERFACE);

            if(status != LibUsb.SUCCESS)
            {
                mLog.error("Unable to detach kernel driver for USB tuner device - bus:" + mBus + " port:" + mPortAddress);
                mDeviceHandle = null;
                mDeviceDescriptor = null;
                throw new SourceException("Can't detach kernel driver");
            }
        }

        //Set the configuration which also invokes a soft reset on the device
        status = LibUsb.setConfiguration(mDeviceHandle, USB_CONFIGURATION);

        if(status == LibUsb.ERROR_BUSY)
        {
            mLog.error("Unable to set USB configuration on tuner - device is busy (in use by another application)");
            mDeviceHandle = null;
            mDeviceDescriptor = null;
            throw new SourceException("USB tuner is in-use by another application");
        }
        else if(status != LibUsb.SUCCESS)
        {
            mDeviceHandle = null;
            mDeviceDescriptor = null;
            throw new SourceException("Can't set configuration (ie reset) on the USB tuner - " + LibUsb.errorName(status));
        }

        //Claim the interface
        status = LibUsb.claimInterface(mDeviceHandle, USB_INTERFACE);

        if(status == LibUsb.ERROR_BUSY)
        {
            mDeviceHandle = null;
            mDeviceDescriptor = null;
            throw new SourceException("USB tuner is in-use by another application");
        }
        else if(status != LibUsb.SUCCESS)
        {
            mDeviceHandle = null;
            mDeviceDescriptor = null;
            throw new SourceException("Can't claim interface on USB tuner - " + LibUsb.errorName(status));
        }

        //Set running true for deviceStart() operations that require it.
        mRunning = true;

        try
        {
            deviceStart();
        }
        catch(Exception se)
        {
            mRunning = false;
            throw se;
        }
    }

    /**
     * Prepares the tuner for full shutdown by stopping streaming, shutdown the device, and releasing the USB resources.
     */
    public final void stop()
    {
        if(mDeviceContext == null || !mStopping.compareAndSet(false, true))
        {
            return;
        }

        mRunning = false;

        AtomicBoolean safeToRelease = new AtomicBoolean();

        //Spin the shutdown onto a new thread so that we can set a max wait threshold.
        Thread t = new Thread(() -> {
            if(stopStreaming())
            {
                mNativeBufferBroadcaster.clear();
                deviceStop();
                safeToRelease.set(true);
            }
        }, "sdrtrunk USB tuner shutdown - bus [" + mBus + "] port [" + mPortAddress + "]");

        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();

        try
        {
            t.join(USB_DEVICE_SHUTDOWN_TIMEOUT_MS);

            if(t.isAlive())
            {
                mLog.error("USB tuner shutdown timed out - native resources will remain allocated until application restart");
                t.interrupt();
            }
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }

        if(!safeToRelease.get())
        {
            mLog.error("USB tuner did not reach a safe stopped state - skipping native USB resource release");

            if(!t.isAlive())
            {
                mStopping.set(false);
            }

            return;
        }

        //Serialize final release against listener registration/startup without allowing a stuck caller to hang shutdown.
        boolean releaseLocked = false;

        try
        {
            releaseLocked = getLock().tryLock(USB_FINAL_RELEASE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if(!releaseLocked)
            {
                mLog.error("Timed out waiting for USB tuner lifecycle lock - native resources will remain allocated");
                return;
            }

            if(!mTransferManager.freeTransfers())
            {
                mLog.error("USB tuner still has native-owned transfers - skipping native USB resource release");
                mStopping.set(false);
                return;
            }

            if(mDeviceHandle != null)
            {
                LibUsb.releaseInterface(mDeviceHandle, USB_INTERFACE);
                LibUsb.close(mDeviceHandle);
                mDeviceHandle = null;
                mDevice = null;
                mDeviceDescriptor = null;
            }

            LibUsb.exit(mDeviceContext);
            mDeviceContext = null;
        }
        catch(InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            mLog.error("Interrupted while waiting to release USB tuner native resources", ie);
        }
        finally
        {
            if(releaseLocked)
            {
                getLock().unlock();
            }
        }
    }

    /**
     * Starts streaming data from the tuner
     */
    private void startStreaming()
    {
        String errorMessage = null;

        synchronized(this)
        {
            if(mStreaming.compareAndSet(false, true))
            {
                try
                {
                    prepareStreaming();
                    List<Transfer> transfers = mTransferManager.getTransfers();
                    mTransferManager.setAutoResubmitTransfers(true);
                    mTransferManager.submitTransfers(transfers);
                    mEventProcessor.start();
                }
                catch(Exception e)
                {
                    mLog.error("Error starting streaming on USB tuner", e);
                    boolean safeToFree = stopStreaming();

                    if(safeToFree)
                    {
                        mTransferManager.freeTransfers();
                    }
                    else
                    {
                        mLog.error("Streaming startup rollback did not quiesce native transfers - resources will remain " +
                                "allocated until application restart");
                        streamingCleanup();
                    }

                    errorMessage = "Unable to start USB sample streaming" +
                            (e.getMessage() == null ? "" : " - " + e.getMessage());
                }
            }
        }

        if(errorMessage != null)
        {
            String message = errorMessage;
            ThreadPool.CACHED.submit(() -> setErrorMessage(message));
        }
    }

    /**
     * Prepares to start streaming.  This method can be overridden by sub-class to implement additional actions
     * need to prepare before start streaming.
     */
    protected void prepareStreaming() throws SourceException
    {
    }

    /**
     * Stop streaming data from the tuner
     */
    private synchronized boolean stopStreaming()
    {
        boolean wasStreaming = mStreaming.getAndSet(false);

        if(wasStreaming || mEventProcessor.isRunning() || mTransferManager.hasActiveTransfers())
        {
            //Turn off auto-resubmit of USB transfer buffers
            mTransferManager.setAutoResubmitTransfers(false);

            //Stop event processing thread to put all submitted tranfers in a stable state - blocks until stopped
            if(!mEventProcessor.stop())
            {
                mLog.error("LibUsb event processing thread did not stop - leaving native resources allocated");
                return false;
            }

            //Cancel all currently submitted transfers
            mTransferManager.cancelTransfers();

            //Perform final event processing iteration so LibUsb returns all of our cancelled tranfers
            if(!mEventProcessor.drainEvents(mTransferManager, USB_TRANSFER_DRAIN_TIMEOUT_MS))
            {
                mLog.error("Timed out waiting for LibUsb to return cancelled transfers - leaving native resources allocated");
                return false;
            }

            streamingCleanup();
        }

        return !mTransferManager.hasActiveTransfers();
    }

    /**
     * Post streaming cleanup actions.  This method can be overridden by sub-class to implement additional actions
     * needed to cleanup after streaming stops.
     */
    protected void streamingCleanup()
    {
    }

    /**
     * Finds the USB device for this tuner at the specified USB bus and port.
     * @return discovered USB device
     * @throws SourceException if there is an error or the device is not discovered.
     */
    private Device findDevice() throws SourceException
    {
        Device foundDevice = null;

        DeviceList deviceList = new DeviceList();
        int count = LibUsb.getDeviceList(mDeviceContext, deviceList);

        if(count >= 0)
        {
            for(Device device: deviceList)
            {
                int bus = LibUsb.getBusNumber(device);
                int port = LibUsb.getPortNumber(device);

                if(port > 0)
                {
                    String portAddress = TunerManager.getPortAddress(device);

                    if(mBus == bus && mPortAddress != null && mPortAddress.equals(portAddress))
                    {
                        foundDevice = device;
                    }
                    else
                    {
                        LibUsb.unrefDevice(device);
                    }
                }
                else
                {
                    LibUsb.unrefDevice(device);
                }
            }
        }

        //Free the device list but don't auto-unref all the devices ... we already did that during iteration
        LibUsb.freeDeviceList(deviceList, false);

        if(foundDevice != null)
        {
            return foundDevice;
        }

        throw new SourceException("LibUsb couldn't discover USB device [" + mBus + ":" + mPortAddress +
                "] from device list" + (count < 0 ? " - error: " + LibUsb.errorName(count) : ""));
    }

    /**
     * Access the discovered USB device.
     */
    protected Device getDevice()
    {
        return mDevice;
    }

    /**
     * LibUsb context for this device.
     */
    protected Context getDeviceContext()
    {
        return mDeviceContext;
    }

    /**
     * LibUsb device descriptor for this device
     */
    protected DeviceDescriptor getDeviceDescriptor()
    {
        return mDeviceDescriptor;
    }

    /**
     * USB Device Handle for the claimed device
     */
    protected DeviceHandle getDeviceHandle()
    {
        return mDeviceHandle;
    }

    /**
     * Indicates if the device handle is non-null
     */
    protected boolean hasDeviceHandle()
    {
        return getDeviceHandle() != null;
    }

    /**
     * Indicates if this device is usable, meaning it has been started and is not yet stopping.
     *
     * Note: this is a general usability flag for controlling all code that touches the USB interface(s)
     */
    protected boolean isRunning()
    {
        return mRunning;
    }

    /**
     * Adds the IQ buffer listener and automatically starts stream buffer transfer processing, if not already started.
     */
    @Override
    public void addBufferListener(Listener<INativeBuffer> listener)
    {
        if(isRunning() && !mStopping.get())
        {
            getLock().lock();

            try
            {
                if(!isRunning() || mStopping.get())
                {
                    return;
                }

                boolean hasExistingListeners = hasBufferListeners();

                super.addBufferListener(listener);

                if(!hasExistingListeners)
                {
                    startStreaming();
                }
            }
            finally
            {
                getLock().unlock();
            }
        }
    }

    /**
     * Removes the IQ buffer listener and stops stream buffer transfer processing if there are no more listeners.
     */
    @Override
    public void removeBufferListener(Listener<INativeBuffer> listener)
    {
        boolean stopOnWorker = false;
        getLock().lock();

        try
        {
            super.removeBufferListener(listener);

            if(!hasBufferListeners())
            {
                if(mEventProcessor.isCurrentThread())
                {
                    stopOnWorker = true;
                }
                else
                {
                    stopStreaming();
                }
            }
        }
        finally
        {
            getLock().unlock();
        }

        if(stopOnWorker)
        {
            Thread stopThread = new Thread(this::stopStreamingIfNoListeners,
                    "sdrtrunk USB stream stop - bus [" + mBus + "] port [" + mPortAddress + "]");
            stopThread.setDaemon(true);
            stopThread.setPriority(Thread.NORM_PRIORITY);
            stopThread.start();
        }
    }

    void stopStreamingIfNoListeners()
    {
        getLock().lock();

        try
        {
            if(!hasBufferListeners())
            {
                stopStreaming();
            }
        }
        finally
        {
            getLock().unlock();
        }
    }

    /**
     * Manages USB transfer (ie zero-copy) buffer processing
     */
    static class TransferLedger<T>
    {
        record SubmissionResult<T>(int status, T retryTransfer, int retryStatus, boolean exhausted) {}

        private final IdentityHashMap<T, Boolean> mActiveTransfers = new IdentityHashMap<>();
        private final List<T> mRetryTransfers = new ArrayList<>();
        private int mTransferErrorCount;
        private boolean mExhaustionReported;

        SubmissionResult<T> submit(T transfer, ToIntFunction<T> submitter)
        {
            int status = submitter.applyAsInt(transfer);
            boolean exhausted = recordSubmission(transfer, status);
            T retryTransfer = null;
            int retryStatus = LibUsb.SUCCESS;

            if(status == LibUsb.SUCCESS)
            {
                retryTransfer = pollRetryTransfer();

                if(retryTransfer != null)
                {
                    retryStatus = submitter.applyAsInt(retryTransfer);
                    exhausted |= recordSubmission(retryTransfer, retryStatus);
                }
            }

            return new SubmissionResult<>(status, retryTransfer, retryStatus, exhausted);
        }

        boolean recordSubmission(T transfer, int status)
        {
            int retryIndex = getRetryIndex(transfer);

            if(status == LibUsb.SUCCESS || status == LibUsb.ERROR_BUSY)
            {
                if(retryIndex >= 0)
                {
                    mRetryTransfers.remove(retryIndex);
                }

                mActiveTransfers.put(transfer, Boolean.TRUE);
                return false;
            }

            mActiveTransfers.remove(transfer);

            if(retryIndex < 0)
            {
                mRetryTransfers.add(transfer);
            }

            mTransferErrorCount++;

            if(!mExhaustionReported && mRetryTransfers.size() >= USB_BULK_TRANSFER_BUFFER_POOL_SIZE)
            {
                mExhaustionReported = true;
                return true;
            }

            return false;
        }

        T pollRetryTransfer()
        {
            if(mRetryTransfers.isEmpty())
            {
                return null;
            }

            return mRetryTransfers.remove(0);
        }

        void transferReturned(T transfer)
        {
            mActiveTransfers.remove(transfer);
            int retryIndex = getRetryIndex(transfer);

            if(retryIndex >= 0)
            {
                mRetryTransfers.remove(retryIndex);
            }
        }

        List<T> getActiveTransfers() { return new ArrayList<>(mActiveTransfers.keySet()); }

        boolean hasActiveTransfers() { return !mActiveTransfers.isEmpty(); }

        int getRetryTransferCount() { return mRetryTransfers.size(); }

        int getActiveTransferCount() { return mActiveTransfers.size(); }

        int getTransferErrorCount() { return mTransferErrorCount; }

        void resetExhaustionNotification() { mExhaustionReported = false; }

        void clear()
        {
            mActiveTransfers.clear();
            mRetryTransfers.clear();
        }

        private int getRetryIndex(T transfer)
        {
            for(int x = 0; x < mRetryTransfers.size(); x++)
            {
                if(mRetryTransfers.get(x) == transfer)
                {
                    return x;
                }
            }

            return -1;
        }
    }

    /**
     * Immutable USB transfer measurements.  Counts and the worst gap are cumulative for the life of this controller.
     * The first/last timestamps and last gap apply to the latest streaming session and exclude time while stopped.
     */
    public record UsbTransferHealthSnapshot(boolean streaming, long streamSequence,
                                            int expectedTransferLengthBytes, int sampleFrameSizeBytes,
                                            int negotiatedDeviceSpeedCode, String negotiatedDeviceSpeed,
                                            int transferPoolSize, int activeTransferCount, int retryTransferCount,
                                            long submissionFailureCount,
                                            long transferCount, long completedTransferCount,
                                            long stalledTransferCount, long timedOutTransferCount,
                                            long errorTransferCount, long cancelledTransferCount,
                                            long unexpectedStatusTransferCount, long expectedBytes,
                                            long actualBytes, long usableBytes, long estimatedMissingBytes,
                                            long unusableBytes, long shortTransferCount, long zeroLengthTransferCount,
                                            long malformedTransferCount, long malformedRemainderBytes,
                                            long streamStartedTimestampMilliseconds,
                                            long firstTransferTimestampMilliseconds,
                                            long lastTransferTimestampMilliseconds,
                                            long lastInterTransferGapMilliseconds,
                                            long worstInterTransferGapMilliseconds, long longTransferGapCount)
    {
    }

    /**
     * Single-producer USB callback accounting.  Callback updates use primitive reads, arithmetic and volatile writes
     * only.  Snapshot allocation is deliberately confined to the off-thread snapshot caller.
     */
    static class UsbTransferHealth
    {
        private volatile boolean mStreaming;
        private volatile long mStreamSequence;
        private volatile int mExpectedTransferLengthBytes;
        private volatile int mSampleFrameSizeBytes = 1;
        private volatile int mNegotiatedDeviceSpeedCode = LibUsb.SPEED_UNKNOWN;
        private volatile String mNegotiatedDeviceSpeed = usbDeviceSpeedLabel(LibUsb.SPEED_UNKNOWN);
        private volatile int mTransferPoolSize;
        private volatile int mActiveTransferCount;
        private volatile int mRetryTransferCount;
        private volatile long mSubmissionFailureCount;
        private volatile long mTransferCount;
        private volatile long mCompletedTransferCount;
        private volatile long mStalledTransferCount;
        private volatile long mTimedOutTransferCount;
        private volatile long mErrorTransferCount;
        private volatile long mCancelledTransferCount;
        private volatile long mUnexpectedStatusTransferCount;
        private volatile long mExpectedBytes;
        private volatile long mActualBytes;
        private volatile long mUsableBytes;
        private volatile long mEstimatedMissingBytes;
        private volatile long mUnusableBytes;
        private volatile long mShortTransferCount;
        private volatile long mZeroLengthTransferCount;
        private volatile long mMalformedTransferCount;
        private volatile long mMalformedRemainderBytes;
        private volatile long mStreamStartedTimestampMilliseconds;
        private volatile long mFirstTransferTimestampMilliseconds;
        private volatile long mLastTransferTimestampMilliseconds;
        private volatile long mPreviousTransferTimestampMilliseconds;
        private volatile long mLastInterTransferGapMilliseconds;
        private volatile long mWorstInterTransferGapMilliseconds;
        private volatile long mLongTransferGapCount;

        void beginStreaming(int expectedTransferLengthBytes, int sampleFrameSizeBytes)
        {
            mExpectedTransferLengthBytes = Math.max(0, expectedTransferLengthBytes);
            mSampleFrameSizeBytes = Math.max(1, sampleFrameSizeBytes);
            mFirstTransferTimestampMilliseconds = 0;
            mLastTransferTimestampMilliseconds = 0;
            mPreviousTransferTimestampMilliseconds = 0;
            mLastInterTransferGapMilliseconds = 0;
            mStreamStartedTimestampMilliseconds = System.currentTimeMillis();
            mStreamSequence++;
            mStreaming = true;
        }

        void endStreaming()
        {
            mStreaming = false;
            mPreviousTransferTimestampMilliseconds = 0;
            mLastInterTransferGapMilliseconds = 0;
        }

        void setNegotiatedDeviceSpeed(int speedCode)
        {
            mNegotiatedDeviceSpeedCode = speedCode;
            mNegotiatedDeviceSpeed = usbDeviceSpeedLabel(speedCode);
        }

        void recordSubmissionState(int poolSize, int activeTransfers, int retryTransfers, long submissionFailures)
        {
            mTransferPoolSize = Math.max(0, poolSize);
            mActiveTransferCount = Math.max(0, activeTransfers);
            mRetryTransferCount = Math.max(0, retryTransfers);
            mSubmissionFailureCount = Math.max(0, submissionFailures);
        }

        /**
         * Records one active-stream callback.  This method must remain allocation-free and non-blocking.
         */
        void recordTransfer(int status, int actualLength, long timestampMilliseconds)
        {
            int expectedLength = mExpectedTransferLengthBytes;
            int frameSize = mSampleFrameSizeBytes;
            int actualBytes = Math.max(0, actualLength);
            boolean recognizedStatus = true;

            switch(status)
            {
                case LibUsb.TRANSFER_COMPLETED -> mCompletedTransferCount++;
                case LibUsb.TRANSFER_STALL -> mStalledTransferCount++;
                case LibUsb.TRANSFER_TIMED_OUT -> mTimedOutTransferCount++;
                case LibUsb.TRANSFER_ERROR -> mErrorTransferCount++;
                case LibUsb.TRANSFER_CANCELLED -> mCancelledTransferCount++;
                default ->
                {
                    mUnexpectedStatusTransferCount++;
                    recognizedStatus = false;
                }
            }

            mTransferCount++;
            mExpectedBytes += expectedLength;
            mActualBytes += actualBytes;

            if(actualBytes < expectedLength)
            {
                mShortTransferCount++;
                mEstimatedMissingBytes += expectedLength - actualBytes;
            }

            if(actualBytes == 0)
            {
                mZeroLengthTransferCount++;
            }

            int remainderBytes = frameSize > 1 ? actualBytes % frameSize : 0;

            if(remainderBytes > 0)
            {
                mMalformedTransferCount++;
                mMalformedRemainderBytes += remainderBytes;
            }

            if(recognizedStatus && isCompleteTransfer(expectedLength, actualBytes))
            {
                mUsableBytes += expectedLength;
            }
            else
            {
                mUnusableBytes += actualBytes;
            }

            long previousTimestamp = mPreviousTransferTimestampMilliseconds;

            if(mFirstTransferTimestampMilliseconds == 0)
            {
                mFirstTransferTimestampMilliseconds = timestampMilliseconds;
            }

            if(previousTimestamp > 0 && timestampMilliseconds >= previousTimestamp)
            {
                long gap = timestampMilliseconds - previousTimestamp;
                mLastInterTransferGapMilliseconds = gap;

                if(gap > mWorstInterTransferGapMilliseconds)
                {
                    mWorstInterTransferGapMilliseconds = gap;
                }

                if(gap >= USB_TRANSFER_LONG_GAP_MILLISECONDS)
                {
                    mLongTransferGapCount++;
                }
            }
            else
            {
                mLastInterTransferGapMilliseconds = 0;
            }

            mLastTransferTimestampMilliseconds = timestampMilliseconds;
            mPreviousTransferTimestampMilliseconds = timestampMilliseconds;
        }

        UsbTransferHealthSnapshot snapshot()
        {
            return new UsbTransferHealthSnapshot(mStreaming, mStreamSequence, mExpectedTransferLengthBytes,
                    mSampleFrameSizeBytes, mNegotiatedDeviceSpeedCode, mNegotiatedDeviceSpeed, mTransferPoolSize,
                    mActiveTransferCount, mRetryTransferCount, mSubmissionFailureCount, mTransferCount,
                    mCompletedTransferCount, mStalledTransferCount,
                    mTimedOutTransferCount, mErrorTransferCount, mCancelledTransferCount,
                    mUnexpectedStatusTransferCount, mExpectedBytes, mActualBytes, mUsableBytes,
                    mEstimatedMissingBytes, mUnusableBytes, mShortTransferCount, mZeroLengthTransferCount,
                    mMalformedTransferCount, mMalformedRemainderBytes, mStreamStartedTimestampMilliseconds,
                    mFirstTransferTimestampMilliseconds,
                    mLastTransferTimestampMilliseconds, mLastInterTransferGapMilliseconds,
                    mWorstInterTransferGapMilliseconds, mLongTransferGapCount);
        }
    }

    /**
     * Only complete transfer buffers are safe for the existing native-buffer factories.  They copy the buffer's
     * capacity and several downstream iterators require tuner-specific fixed fragment multiples, so forwarding a
     * short buffer would either consume stale tail data or violate sample alignment.
     */
    static boolean isCompleteTransfer(int expectedLength, int actualLength)
    {
        return expectedLength > 0 && actualLength == expectedLength;
    }

    /**
     * Preserves the existing live decode behavior: any positive-length callback is forwarded while streaming.  Health
     * accounting independently flags short or malformed payloads; changing their decode treatment requires separate,
     * tuner-specific prefix buffering/alignment work.
     */
    static boolean shouldDispatchTransfer(boolean streaming, int actualLength)
    {
        return streaming && actualLength > 0;
    }

    /**
     * Human-readable libusb negotiated device speed.  This describes the device link, not the capacity or generation
     * of every upstream hub and host controller in the physical USB path.
     */
    public static String usbDeviceSpeedLabel(int speedCode)
    {
        return switch(speedCode)
        {
            case LibUsb.SPEED_LOW -> "low (1.5 Mb/s)";
            case LibUsb.SPEED_FULL -> "full (12 Mb/s)";
            case LibUsb.SPEED_HIGH -> "high (480 Mb/s)";
            case LibUsb.SPEED_SUPER -> "super (5 Gb/s)";
            case LibUsb.SPEED_SUPER_PLUS -> "super-plus (10 Gb/s)";
            default -> "unknown";
        };
    }

    class TransferManager implements TransferCallback
    {
        private List<Transfer> mAvailableTransfers;
        private final TransferLedger<Transfer> mLedger = new TransferLedger<>();
        private volatile boolean mAutoResubmitTransfers;
        private int mExpectedTransferLengthBytes;
        private int mSampleFrameSizeBytes = 1;

        /**
         * Creates USB Transfers to carry the streaming sample data.  Transfer buffers are backed by native memory
         * byte buffers outside the JVM.
         *
         * @return list of transfers
         * @throws SourceException if there is an error creating transfers
         */
        private List<Transfer> getTransfers() throws SourceException
        {
            if(mAvailableTransfers == null)
            {
                mAvailableTransfers = new ArrayList<>();
                mExpectedTransferLengthBytes = getTransferBufferSize();
                int bufferSampleCount = getBufferSampleCount();

                if(bufferSampleCount > 0 && mExpectedTransferLengthBytes % bufferSampleCount == 0)
                {
                    mSampleFrameSizeBytes = mExpectedTransferLengthBytes / bufferSampleCount;
                }
                else
                {
                    mSampleFrameSizeBytes = 1;
                }

                for(int x = 0; x < USB_BULK_TRANSFER_BUFFER_POOL_SIZE; x++)
                {
                    Transfer transfer = LibUsb.allocTransfer();

                    if(transfer == null)
                    {
                        throw new SourceException("Couldn't allocate USB transfer buffer - out of memory");
                    }

                    //Record native allocation immediately so startup rollback can free it if buffer setup fails.
                    mAvailableTransfers.add(transfer);
                    final ByteBuffer buffer;

                    try
                    {
                        buffer = ByteBuffer.allocateDirect(mExpectedTransferLengthBytes);
                    }
                    catch(OutOfMemoryError oome)
                    {
                        throw new SourceException("Couldn't allocate direct USB transfer buffer - out of memory", oome);
                    }

                    LibUsb.fillBulkTransfer(transfer, mDeviceHandle, USB_BULK_TRANSFER_ENDPOINT, buffer,
                            TransferManager.this, "Transfer Buffer " + x, USB_BULK_TRANSFER_TIMEOUT_MS);
                }
            }

            return mAvailableTransfers;
        }

        /**
         * Prepare to stop processing transfers when stopping streaming of data.
         */
        private void setAutoResubmitTransfers(boolean resubmit)
        {
            mAutoResubmitTransfers = resubmit;

            if(resubmit)
            {
                mLedger.resetExhaustionNotification();
                mUsbTransferHealth.beginStreaming(mExpectedTransferLengthBytes, mSampleFrameSizeBytes);
                updateTransferLedgerHealth();
            }
            else
            {
                mUsbTransferHealth.endStreaming();
            }
        }

        /**
         * Submits the transfers to start sample stream processing
         * @param transfers to submit
         */
        private void submitTransfers(List<Transfer> transfers)
        {
            for(Transfer transfer: transfers)
            {
                submitTransfer(transfer);
            }
        }

        /**
         * (Re)Submits the transfer for stream processing.  Ledger mutation is confined to the event thread during
         * streaming and to the shutdown thread only after the event thread has positively stopped.
         */
        private void submitTransfer(Transfer transfer)
        {
            TransferLedger.SubmissionResult<Transfer> result = mLedger.submit(transfer, LibUsb::submitTransfer);
            int status = result.status();

            if(result.retryTransfer() != null)
            {
                int resubmitStatus = result.retryStatus();

                if(resubmitStatus == LibUsb.SUCCESS || resubmitStatus == LibUsb.ERROR_BUSY)
                {
                    if(mLedger.getRetryTransferCount() >= (mAvailableTransfers.size() / 2))
                    {
                        mLog.info("Successfully resubmitted previous error USB transfer buffer.  Current transfer buffer" +
                                " status (error queue/total available) [" + mLedger.getRetryTransferCount() + "/" +
                                mAvailableTransfers.size() + "]");
                    }
                }
            }

            if(status == LibUsb.ERROR_BUSY)
            {
                //Ignore - this indicates the transfer was previously submitted and libusb is still working it.  I'm not
                //sure how this happens because we give libusb a transfer and it hands it back when it's full.  If
                //libusb is still working it, then why did it indicate the transfer was completed?  So, we simply
                //ignore this error code.  Other libraries simply ignore the submit status code altogether.
            }
            else if(status != LibUsb.SUCCESS)
            {
                mLog.error("USB transfer [" + transfer + "] submit attempt failed with error [" + LibUsb.errorName(status) +
                        "] - adding to error queue to resubmit later - this may be a temporary USB issue and has happened [" +
                        mLedger.getTransferErrorCount() + "] time(s) so far.  Current transfer error queue (error/total) [" +
                        mLedger.getRetryTransferCount() + "/" + mAvailableTransfers.size() + "]");
            }

            if(result.exhausted())
            {
                mLog.error("Maximum USB transfer buffer errors reached - transfer buffers exhausted - shutting down USB tuner");
                ThreadPool.CACHED.submit(() -> setErrorMessage("USB Error - Transfer Buffers Exhausted"));
            }

            updateTransferLedgerHealth();
        }

        private void updateTransferLedgerHealth()
        {
            mUsbTransferHealth.recordSubmissionState(mAvailableTransfers != null ? mAvailableTransfers.size() : 0,
                mLedger.getActiveTransferCount(), mLedger.getRetryTransferCount(), mLedger.getTransferErrorCount());
        }

        /**
         * Cancels any in-progress transfers to prepare for shutdown.
         *
         * Note: this should only be invoked after the LibUsb event processing thread has been stopped so that the
         * transfer buffers are in a stable (submitted vs callback) state and we can then flip their cancel state and
         * then finish processing the timeout events under the control of a single (shutdown) thread.
         */
        private void cancelTransfers()
        {
            for(Transfer transfer: mLedger.getActiveTransfers())
            {
                LibUsb.cancelTransfer(transfer);
            }
        }

        private boolean hasActiveTransfers() { return mLedger.hasActiveTransfers(); }

        /**
         * Frees/disposes allocated USB transfer buffers.
         */
        private boolean freeTransfers()
        {
            if(mLedger.hasActiveTransfers())
            {
                return false;
            }

            if(mAvailableTransfers != null)
            {
                for(Transfer transfer: mAvailableTransfers)
                {
                    try
                    {
                        LibUsb.freeTransfer(transfer);
                    }
                    catch(Exception e)
                    {
                        mLog.error("Error releasing allocated USB transfer buffer during tuner shutdown: " +
                                e.getLocalizedMessage());
                    }
                }

                mAvailableTransfers.clear();
                mAvailableTransfers = null;
            }

            mLedger.clear();
            updateTransferLedgerHealth();
            return true;
        }

        @Override
        public void processTransfer(Transfer transfer)
        {
            mLedger.transferReturned(transfer);
            int transferStatus = transfer.status();
            int transferLength = transfer.actualLength();
            boolean streaming = mAutoResubmitTransfers;
            long timestamp = 0;

            if(streaming)
            {
                timestamp = System.currentTimeMillis();
                mUsbTransferHealth.recordTransfer(transferStatus, transferLength, timestamp);
            }

            switch(transferStatus)
            {
                case LibUsb.TRANSFER_COMPLETED:
                case LibUsb.TRANSFER_STALL:
                case LibUsb.TRANSFER_TIMED_OUT:
                case LibUsb.TRANSFER_ERROR:
                //Note: cancel flag can be set by libusb, independent of commanded cancel of transfers - we simply
                //resubmit the transfer for continued use.
                case LibUsb.TRANSFER_CANCELLED:
                    //Do not dispatch stale sample data while draining cancellation callbacks during shutdown.
                    if(shouldDispatchTransfer(mAutoResubmitTransfers, transferLength))
                    {
                        dispatchTransfer(transfer, timestamp);
                    }

                    transfer.buffer().rewind();

                    if(mAutoResubmitTransfers)
                    {
                        submitTransfer(transfer);
                    }
                    break;
                default:
                    //Unexpected transfer error - shutdown the tuner
                    transfer.buffer().rewind();

                    //Only set an error if we're not shutting down
                    if(mAutoResubmitTransfers)
                    {
                        String errorMessage = "LibUsb Transfer Error - stopping device - status [" + transferStatus +
                                "] - " + LibUsb.errorName(transferStatus);
                        //spin this off onto the thread pool, so it doesn't impact the usb processor thread.
                        ThreadPool.CACHED.submit(() -> setErrorMessage(errorMessage));
                    }
                    break;
            }

            updateTransferLedgerHealth();
        }

        /**
         * Makes a copy of the transfer's native memory byte array payload so that the transfer can be reused.
         * Dispatches the native buffer to registered listeners.
         * @param transfer to copy and dispatch
         */
        private void dispatchTransfer(Transfer transfer, long timestamp)
        {
            //Pass the transfer's byte buffer so the native buffer factory can make a copy of the byte array contents
            //and package it as a native buffer.
            INativeBuffer nativeBuffer = getNativeBufferFactory().getBuffer(transfer.buffer(), timestamp);
            mNativeBufferBroadcaster.broadcast(nativeBuffer);
        }
    }

    /**
     * Threaded LibUsb event processor - continuously polls LibUsb to process events exclusively for this USB tuner
     * device using the device context.
     */
    class UsbEventProcessor implements Runnable
    {
        private volatile Thread mThread;
        private volatile boolean mProcessing;

        /**
         * Start the event processing thread
         */
        public synchronized void start()
        {
            if(mThread == null)
            {
                mProcessing = true;
                mThread = createUsbEventThread(this);
                mThread.setName("sdrtrunk USB tuner - bus [" + mBus + "] port [" + mPortAddress + "]");
                mThread.setDaemon(true);
                mThread.setPriority(Thread.MAX_PRIORITY);
                mThread.start();
            }
        }

        /**
         * Set the stop processing flag and block until the thread stops, blocking up to 1000 ms.
         */
        public synchronized boolean stop()
        {
            mProcessing = false;
            Thread thread = mThread;

            if(thread == null)
            {
                return true;
            }

            if(Thread.currentThread() == thread)
            {
                return false;
            }

            interruptUsbEventHandler();

            try
            {
                thread.join(USB_EVENT_STOP_TIMEOUT_MS);
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                mLog.error("Interrupted while stopping LibUsb event processing thread", ie);
                return false;
            }

            if(thread.isAlive())
            {
                return false;
            }

            mThread = null;
            return true;
        }

        /**
         * Processes cancellation callbacks until libusb has returned every transfer or the bounded deadline expires.
         */
        public boolean drainEvents(TransferManager transferManager, long timeoutMs)
        {
            long deadline = System.nanoTime() + (timeoutMs * 1_000_000L);

            while(transferManager.hasActiveTransfers() && System.nanoTime() < deadline &&
                    !Thread.currentThread().isInterrupted())
            {
                try
                {
                    handleUsbEvents(25);
                }
                catch(Throwable throwable)
                {
                    mLog.error("Error while processing stop-streaming LibUsb timeout events", throwable);
                    return false;
                }
            }

            return !transferManager.hasActiveTransfers();
        }

        public boolean isRunning() { Thread thread = mThread; return thread != null && thread.isAlive(); }

        boolean isCurrentThread() { return Thread.currentThread() == mThread; }

        boolean isProcessing() { return mProcessing; }

        /**
         * LibUsb event/timeout processing loop
         */
        @Override
        public void run()
        {
            while(mProcessing)
            {
                try
                {
                    handleUsbEvents(250);
                }
                catch(Throwable throwable)
                {
                    mLog.error("Error while processing LibUsb timeout events", throwable);
                }
            }
        }
    }

    protected Thread createUsbEventThread(Runnable runnable)
    {
        return new Thread(runnable);
    }

    protected void handleUsbEvents(long timeoutMilliseconds)
    {
        LibUsb.handleEventsTimeout(mDeviceContext, timeoutMilliseconds);
    }

    protected void interruptUsbEventHandler()
    {
        LibUsb.interruptEventHandler(mDeviceContext);
    }
}

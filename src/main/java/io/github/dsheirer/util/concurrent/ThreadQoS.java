/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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
 * *****************************************************************************
 */

package io.github.dsheirer.util.concurrent;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an operating-system scheduling quality-of-service classification once, when a dedicated worker thread
 * starts.  macOS uses these classifications when choosing between Apple Silicon performance and efficiency cores.
 * Other operating systems safely retain the existing Java thread-priority behavior.
 *
 * <p>This class must only be used to wrap the lifetime runnable supplied to a {@link java.util.concurrent.ThreadFactory}.
 * It is not intended for per-task or receiver-callback use.</p>
 */
public final class ThreadQoS
{
    private static final Logger mLog = LoggerFactory.getLogger(ThreadQoS.class);
    private static final int SUCCESS = 0;
    private static final NativeAccess NATIVE_ACCESS = createNativeAccess();
    private static final AtomicBoolean mFailureLogged = new AtomicBoolean();
    private static final ThreadLocal<QoSClass> mCurrentClass = new ThreadLocal<>();

    private ThreadQoS()
    {
    }

    /**
     * Resolves the native QoS bridge on the calling thread. USB tuner startup invokes this before submitting any
     * transfers so one-time FFM linkage cannot delay the event worker while native sample buffers are already live.
     *
     * @return true when the macOS native bridge is available, or false when this platform uses the safe Java-only
     * fallback
     */
    public static boolean initialize()
    {
        return NATIVE_ACCESS.supported();
    }

    /**
     * macOS scheduling classes used by SDRTrunk.  Receiver work must meet live sample deadlines, while observer work
     * is intentionally loss-tolerant and must yield before receiver processing.
     */
    public enum QoSClass
    {
        USER_INITIATED(0x19),
        UTILITY(0x11);

        private final int mNativeValue;

        QoSClass(int nativeValue)
        {
            mNativeValue = nativeValue;
        }

        private int nativeValue()
        {
            return mNativeValue;
        }
    }

    /**
     * Wraps a dedicated worker's lifetime runnable and applies the requested QoS before any worker task executes.
     */
    public static Runnable wrap(QoSClass qosClass, Runnable runnable)
    {
        return wrap(qosClass, runnable, NATIVE_ACCESS);
    }

    static Runnable wrap(QoSClass qosClass, Runnable runnable, QoSApplier applier)
    {
        Objects.requireNonNull(qosClass, "QoS class cannot be null");
        Objects.requireNonNull(runnable, "Worker runnable cannot be null");
        Objects.requireNonNull(applier, "QoS applier cannot be null");

        return () ->
        {
            QoSClass previous = mCurrentClass.get();
            mCurrentClass.set(qosClass);

            try
            {
                applyCurrentThread(qosClass, applier);
                runnable.run();
            }
            finally
            {
                if(previous != null)
                {
                    mCurrentClass.set(previous);
                }
                else
                {
                    mCurrentClass.remove();
                }
            }
        };
    }

    /**
     * Returns the QoS requested by SDRTrunk for the current dedicated worker, or null for an unclassified thread.
     * This reports application intent on every platform and is primarily useful for diagnostics and tests.
     */
    public static QoSClass currentClass()
    {
        return mCurrentClass.get();
    }

    /**
     * Applies the requested class to the current native thread.  Unsupported platforms are a successful no-op so
     * platform scheduling never prevents the worker from starting.
     */
    static boolean applyCurrentThread(QoSClass qosClass)
    {
        return applyCurrentThread(qosClass, NATIVE_ACCESS);
    }

    private static boolean applyCurrentThread(QoSClass qosClass, QoSApplier applier)
    {
        int result;

        try
        {
            result = applier.apply(qosClass.nativeValue(), 0);
        }
        catch(VirtualMachineError | ThreadDeath fatal)
        {
            throw fatal;
        }
        catch(Throwable throwable)
        {
            result = -1;
        }

        if(result != SUCCESS && mFailureLogged.compareAndSet(false, true))
        {
            mLog.warn("Unable to apply macOS worker QoS [{}], native result [{}]; continuing with Java scheduling",
                qosClass, result);
        }

        return result == SUCCESS;
    }

    static boolean nativeSupported()
    {
        return NATIVE_ACCESS.supported();
    }

    private static NativeAccess createNativeAccess()
    {
        String osName;

        try
        {
            osName = System.getProperty("os.name", "");
        }
        catch(SecurityException securityException)
        {
            return NativeAccess.unsupported();
        }

        if(!isMacOS(osName))
        {
            return NativeAccess.unsupported();
        }

        try
        {
            Linker linker = Linker.nativeLinker();
            MemorySegment symbol = linker.defaultLookup().find("pthread_set_qos_class_self_np")
                .orElseThrow(() -> new IllegalStateException("macOS pthread QoS function is unavailable"));
            MethodHandle handle = linker.downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            return new MacNativeAccess(handle);
        }
        catch(VirtualMachineError | ThreadDeath fatal)
        {
            throw fatal;
        }
        catch(Throwable throwable)
        {
            mLog.warn("macOS worker QoS is unavailable; continuing with Java scheduling", throwable);
            return NativeAccess.unsupported();
        }
    }

    static boolean isMacOS(String osName)
    {
        String normalized = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        return normalized.startsWith("mac") || normalized.contains("darwin") || normalized.contains("os x");
    }

    @FunctionalInterface
    interface QoSApplier
    {
        int apply(int qosClass, int relativePriority);
    }

    private interface NativeAccess extends QoSApplier
    {
        int apply(int qosClass, int relativePriority);

        boolean supported();

        static NativeAccess unsupported()
        {
            return new NativeAccess()
            {
                @Override
                public int apply(int qosClass, int relativePriority)
                {
                    return SUCCESS;
                }

                @Override
                public boolean supported()
                {
                    return false;
                }
            };
        }
    }

    private record MacNativeAccess(MethodHandle setQosHandle) implements NativeAccess
    {
        @Override
        public int apply(int qosClass, int relativePriority)
        {
            try
            {
                return (int)setQosHandle.invokeExact(qosClass, relativePriority);
            }
            catch(VirtualMachineError | ThreadDeath fatal)
            {
                throw fatal;
            }
            catch(Throwable throwable)
            {
                return -1;
            }
        }

        @Override
        public boolean supported()
        {
            return true;
        }
    }
}

/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.util.concurrent;

import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ThreadQoSTest
{
    @Test
    public void appliesRequestedClassBeforeWorkerRunsAndClearsItAfterward() throws Exception
    {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicReference<QoSClass> observed = new AtomicReference<>();
        Thread thread = new Thread(ThreadQoS.wrap(QoSClass.USER_INITIATED, () ->
        {
            observed.set(ThreadQoS.currentClass());
            order.add("worker");
        }, (nativeClass, relativePriority) ->
        {
            assertEquals(0x19, nativeClass);
            assertEquals(0, relativePriority);
            assertEquals(QoSClass.USER_INITIATED, ThreadQoS.currentClass());
            order.add("qos");
            return 0;
        }));

        thread.start();
        thread.join();

        assertEquals(List.of("qos", "worker"), order);
        assertEquals(QoSClass.USER_INITIATED, observed.get());
        assertNull(ThreadQoS.currentClass());
    }

    @Test
    public void nativeFailureNeverPreventsTheWorkerFromRunning() throws Exception
    {
        AtomicBoolean ran = new AtomicBoolean();
        Thread thread = new Thread(ThreadQoS.wrap(QoSClass.UTILITY, () -> ran.set(true),
            (nativeClass, relativePriority) ->
            {
                assertEquals(0x11, nativeClass);
                throw new IllegalStateException("simulated unavailable QoS");
            }));

        thread.start();
        thread.join();

        assertTrue(ran.get());
    }

    @Test
    public void nonzeroNativeResultNeverPreventsTheWorkerFromRunning() throws Exception
    {
        AtomicBoolean ran = new AtomicBoolean();
        Thread thread = new Thread(ThreadQoS.wrap(QoSClass.UTILITY, () -> ran.set(true),
            (nativeClass, relativePriority) -> 1));

        thread.start();
        thread.join();

        assertTrue(ran.get());
    }

    @Test
    public void recognizesMacNamesWithoutTreatingOtherPlatformsAsMac()
    {
        assertTrue(ThreadQoS.isMacOS("Mac OS X"));
        assertTrue(ThreadQoS.isMacOS("Darwin"));
        assertTrue(ThreadQoS.isMacOS("macOS"));
        assertFalse(ThreadQoS.isMacOS("Linux"));
        assertFalse(ThreadQoS.isMacOS("Windows 11"));
        assertFalse(ThreadQoS.isMacOS(null));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    public void macNativeSetterAcceptsBothWorkerClasses() throws Exception
    {
        assertTrue(ThreadQoS.nativeSupported());
        Linker linker = Linker.nativeLinker();
        MethodHandle currentQos = linker.downcallHandle(
            linker.defaultLookup().find("qos_class_self").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

        for(QoSClass qosClass: QoSClass.values())
        {
            AtomicBoolean applied = new AtomicBoolean();
            AtomicInteger observed = new AtomicInteger();
            Thread thread = new Thread(() ->
            {
                applied.set(ThreadQoS.applyCurrentThread(qosClass));

                try
                {
                    observed.set((int)currentQos.invokeExact());
                }
                catch(Throwable throwable)
                {
                    throw new AssertionError(throwable);
                }
            });
            thread.start();
            thread.join();
            assertTrue(applied.get(), qosClass.toString());
            assertEquals(qosClass == QoSClass.USER_INITIATED ? 0x19 : 0x11, observed.get());
        }
    }
}

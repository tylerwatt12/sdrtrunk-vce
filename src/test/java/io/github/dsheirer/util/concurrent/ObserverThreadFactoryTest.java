/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.util.concurrent;

import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObserverThreadFactoryTest
{
    @Test
    public void defaultObserverRemainsUnclassified() throws Exception
    {
        assertNull(observe(new ObserverThreadFactory("default observer")));
    }

    @Test
    public void diagnosticObserverUsesExplicitUtilityQoS() throws Exception
    {
        assertEquals(QoSClass.UTILITY,
            observe(new ObserverThreadFactory("diagnostic observer", QoSClass.UTILITY)));
    }

    private QoSClass observe(ObserverThreadFactory factory) throws Exception
    {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<QoSClass> observed = new AtomicReference<>();
        Thread thread = factory.newThread(() ->
        {
            observed.set(ThreadQoS.currentClass());
            ran.countDown();
        });
        assertTrue(thread.isDaemon());
        assertTrue(thread.getPriority() < Thread.NORM_PRIORITY);
        thread.start();
        assertTrue(ran.await(2, TimeUnit.SECONDS));
        thread.join();
        return observed.get();
    }
}

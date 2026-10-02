/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SpectrumSearchHardwareTest
{
    @Test
    void insufficientFreshFramesLogOnlyCountsOnTheObservationWorker() throws Exception
    {
        Logger logger = (Logger)LoggerFactory.getLogger(SpectrumSearchHardware.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>()
        {
            @Override protected void append(ILoggingEvent event)
            { event.prepareForDeferredProcessing(); super.append(event); }
        };
        messages.start();
        logger.addAppender(messages);
        try
        {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread observer = new Thread(() -> {
                try { SpectrumSearchHardware.requireFreshFrames(1000, 4); }
                catch(Throwable exception) { failure.set(exception); }
            }, "test-search-observer");
            observer.start(); observer.join(2000);
            assertFalse(observer.isAlive());
            assertInstanceOf(SpectrumSearchHardware.ObservationException.class, failure.get());
            assertEquals(1, messages.list.size());
            ILoggingEvent event = messages.list.getFirst();
            assertEquals("test-search-observer", event.getThreadName());
            assertEquals("Spectrum search observation received 4 fresh frames; required 6 within 1000 ms",
                event.getFormattedMessage());
            assertNull(event.getThrowableProxy(), "Do not include raw receiver metadata through a source exception");
            SpectrumSearchHardware.requireFreshFrames(1500, 6);
            assertEquals(1, messages.list.size(), "A valid observation does not log a failure");
        }
        finally { logger.detachAppender(messages); messages.stop(); }
    }
}

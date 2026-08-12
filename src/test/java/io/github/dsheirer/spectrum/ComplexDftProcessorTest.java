/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.spectrum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComplexDftProcessorTest
{
    @Test
    void inactiveConstructionDoesNotScheduleDftWork()
    {
        ComplexDftProcessor processor = new ComplexDftProcessor(null, false);

        try
        {
            assertFalse(processor.isRunning());
            processor.start();
            assertTrue(processor.isRunning());
        }
        finally
        {
            processor.dispose();
        }
    }

    @Test
    void disposeTerminatesThePrivateExecutorAndPreventsRestart()
    {
        ComplexDftProcessor processor = new ComplexDftProcessor();
        processor.dispose();

        assertTrue(processor.isExecutorTerminated());
        assertThrows(IllegalStateException.class, processor::start);
    }
}

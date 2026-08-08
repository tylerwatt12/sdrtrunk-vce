/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.spectrum;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComplexDftProcessorTest
{
    @Test
    void disposeTerminatesThePrivateExecutorAndPreventsRestart()
    {
        ComplexDftProcessor processor = new ComplexDftProcessor();
        processor.dispose();

        assertTrue(processor.isExecutorTerminated());
        assertThrows(IllegalStateException.class, processor::start);
    }
}

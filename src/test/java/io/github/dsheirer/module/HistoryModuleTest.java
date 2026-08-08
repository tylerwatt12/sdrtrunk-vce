/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryModuleTest
{
    @Test
    void keepsAnExactBoundedHistoryWithoutDuplicatingUpdates()
    {
        TestHistory history = new TestHistory(2);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();

        history.receive(first);
        history.receive(first);
        assertEquals(List.of(first), history.getItems());

        history.receive(second);
        history.receive(third);
        assertEquals(List.of(second, third), history.getItems());
    }

    private static class TestHistory extends HistoryModule<Object>
    {
        private TestHistory(int maximumHistorySize)
        {
            super(maximumHistorySize);
        }
    }
}

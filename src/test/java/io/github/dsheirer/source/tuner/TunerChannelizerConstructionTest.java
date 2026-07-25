/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.preference.source.ChannelizerType;
import io.github.dsheirer.source.tuner.manager.HeterodyneChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import org.junit.jupiter.api.Test;

class TunerChannelizerConstructionTest
{
    @Test
    void constructsSelectedPolyphaseChannelizer()
    {
        TestSelectableTuner tuner = new TestSelectableTuner(ChannelizerType.POLYPHASE);

        try
        {
            assertInstanceOf(PolyphaseChannelSourceManager.class, tuner.getChannelSourceManager());
        }
        finally
        {
            dispose(tuner);
        }
    }

    @Test
    void constructsSelectedHeterodyneChannelizer()
    {
        TestSelectableTuner tuner = new TestSelectableTuner(ChannelizerType.HETERODYNE);

        try
        {
            assertInstanceOf(HeterodyneChannelSourceManager.class, tuner.getChannelSourceManager());
        }
        finally
        {
            dispose(tuner);
        }
    }

    private static void dispose(TestSelectableTuner tuner)
    {
        try
        {
            tuner.getChannelSourceManager().dispose();
        }
        catch(IllegalStateException e)
        {
            if(!"Sample generator is already stopped".equals(e.getMessage()))
            {
                throw e;
            }
        }
    }

    private static class TestSelectableTuner extends Tuner
    {
        private TestSelectableTuner(ChannelizerType channelizerType)
        {
            super(new TestTunerController(), new LoggingTunerErrorListener(), channelizerType);
        }

        @Override
        public int getMaximumUSBBitsPerSecond()
        {
            return 0;
        }

        @Override
        public String getUniqueID()
        {
            return "channelizer-test";
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.TEST_TUNER;
        }

        @Override
        public String getPreferredName()
        {
            return "Channelizer Test";
        }

        @Override
        public double getSampleSize()
        {
            return 16.0;
        }
    }
}

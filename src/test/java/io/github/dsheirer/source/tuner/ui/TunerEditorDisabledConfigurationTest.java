/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.gui.control.FrequencyTextField;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TunerEditorDisabledConfigurationTest
{
    @Test
    void disabledEditorLoadsAndPersistsFrequencyExtentsWithoutHardware() throws Exception
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration("disabled-editor");
        configuration.setSampleRate(2_500_000);
        configuration.setFrequency(101_000_000L);
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(103_000_000L);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(configuration);
        AtomicReference<TestEditor> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> reference.set(new TestEditor(discoveredTuner)));
        TestEditor editor = reference.get();

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(editor.minimum().isEnabled());
            assertTrue(editor.maximum().isEnabled());
            assertTrue(editor.reset().isEnabled());
            assertTrue(editor.frequency().isEnabled());
            assertTrue(editor.centerFrequencyLock().isEnabled());
            assertEquals(2_500_000, editor.getCurrentSampleRate());
            assertEquals(100_000_000L, editor.minimum().getFrequency());
            assertEquals(103_000_000L, editor.maximum().getFrequency());

            fireFocusGained(editor.minimum());
            editor.minimum().setFrequency(102_000_000L);
            fireFocusLost(editor.minimum());
        });

        assertEquals(102_000_000L, configuration.getMinimumFrequency());
        assertEquals(104_500_000L, configuration.getMaximumFrequency());
        assertEquals(102_000_000L, configuration.getFrequency());
        assertEquals(1, editor.mSaveCount);
        assertFalse(editor.loading());

        SwingUtilities.invokeAndWait(editor.reset()::doClick);
        assertEquals(TestEditor.MINIMUM, configuration.getMinimumFrequency());
        assertEquals(TestEditor.MAXIMUM, configuration.getMaximumFrequency());
        assertEquals(2, editor.mSaveCount);
        SwingUtilities.invokeAndWait(editor::dispose);
    }

    @Test
    void disabledEditorPersistsCenterFrequencyAndLockWithoutHardware() throws Exception
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration("disabled-editor");
        configuration.setSampleRate(2_500_000);
        configuration.setFrequency(101_000_000L);
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(105_000_000L);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(configuration);
        AtomicReference<TestEditor> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> reference.set(new TestEditor(discoveredTuner)));
        TestEditor editor = reference.get();

        SwingUtilities.invokeAndWait(() -> {
            assertTrue(editor.frequency().isEnabled());
            assertTrue(editor.centerFrequencyLock().isEnabled());
            editor.setCenterFrequency(102_500_000L);
            editor.centerFrequencyLock().doClick();
        });

        assertEquals(102_500_000L, configuration.getFrequency());
        assertTrue(configuration.isCenterFrequencyLocked());
        assertTrue(editor.mSaveCount > 0);
        assertEquals(0, editor.mHardwareSaveCount,
            "disabled frequency controls must not invoke a hardware-specific editor save");
        SwingUtilities.invokeAndWait(editor::dispose);
    }

    @Test
    void disabledEditorNeverUsesRetiringHardwareObject() throws Exception
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration("disabled-editor");
        configuration.setSampleRate(2_500_000);
        configuration.setFrequency(101_000_000L);
        configuration.setMinimumFrequency(100_000_000L);
        configuration.setMaximumFrequency(105_000_000L);
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(configuration);
        AtomicReference<TestEditor> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> reference.set(new TestEditor(discoveredTuner)));
        TestEditor editor = reference.get();

        SwingUtilities.invokeAndWait(() -> {
            discoveredTuner.simulateRetiringHardware(true);
            editor.refreshFrequencyControls();
            editor.setCenterFrequency(103_000_000L);
            editor.centerFrequencyLock().doClick();
        });

        assertEquals(103_000_000L, configuration.getFrequency());
        assertTrue(configuration.isCenterFrequencyLocked());
        assertEquals(0, editor.mHardwareSaveCount);
        discoveredTuner.simulateRetiringHardware(false);
        SwingUtilities.invokeAndWait(editor::dispose);
    }

    private static void fireFocusGained(FrequencyTextField field)
    {
        FocusEvent event = new FocusEvent(field, FocusEvent.FOCUS_GAINED);
        for(FocusListener listener: field.getFocusListeners())
        {
            listener.focusGained(event);
        }
    }

    private static void fireFocusLost(FrequencyTextField field)
    {
        FocusEvent event = new FocusEvent(field, FocusEvent.FOCUS_LOST);
        for(FocusListener listener: field.getFocusListeners())
        {
            listener.focusLost(event);
        }
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private boolean mSimulateRetiringHardware;

        private TestDiscoveredTuner(AirspyTunerConfiguration configuration)
        {
            setEnabled(false);
            setTunerConfiguration(configuration);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.AIRSPY;
        }

        @Override
        public String getId()
        {
            return "disabled-editor";
        }

        @Override
        public void start()
        {
            //No hardware is available in this test.
        }

        @Override
        public boolean hasTuner()
        {
            return mSimulateRetiringHardware || super.hasTuner();
        }

        @Override
        public Tuner getTuner()
        {
            if(mSimulateRetiringHardware)
            {
                throw new AssertionError("disabled editor accessed retiring tuner hardware");
            }

            return super.getTuner();
        }

        private void simulateRetiringHardware(boolean simulate)
        {
            mSimulateRetiringHardware = simulate;
        }
    }

    private static class TestEditor extends TunerEditor<Tuner, AirspyTunerConfiguration>
    {
        private static final long MINIMUM = 1L;
        private static final long MAXIMUM = 1_000_000_000L;
        private int mSaveCount;
        private int mHardwareSaveCount;

        private TestEditor(DiscoveredTuner discoveredTuner)
        {
            super(null, null, discoveredTuner, Tuner.class, AirspyTunerConfiguration.class);
            setLoading(true);
            getFrequencyPanel().updateControls();
            setLoading(false);
        }

        @Override
        public long getMinimumTunableFrequency()
        {
            return MINIMUM;
        }

        @Override
        public long getMaximumTunableFrequency()
        {
            return MAXIMUM;
        }

        @Override
        protected void save()
        {
            mHardwareSaveCount++;
        }

        @Override
        protected void saveConfiguration()
        {
            mSaveCount++;
        }

        @Override
        protected void tunerStatusUpdated()
        {
            getFrequencyPanel().updateControls();
        }

        @Override
        public void setTunerLockState(boolean locked)
        {
            getFrequencyPanel().updateControls();
        }

        private FrequencyTextField minimum()
        {
            return getMinimumFrequencyTextField();
        }

        private FrequencyTextField maximum()
        {
            return getMaximumFrequencyTextField();
        }

        private JButton reset()
        {
            return getResetFrequenciesButton();
        }

        private boolean loading()
        {
            return isLoading();
        }

        private javax.swing.JComponent frequency()
        {
            return getFrequencyControl();
        }

        private void setCenterFrequency(long frequency)
        {
            getFrequencyControl().setFrequency(frequency, true);
        }

        private JCheckBox centerFrequencyLock()
        {
            return getCenterFrequencyLockCheckBox();
        }

        private void refreshFrequencyControls()
        {
            getFrequencyPanel().updateControls();
        }
    }
}

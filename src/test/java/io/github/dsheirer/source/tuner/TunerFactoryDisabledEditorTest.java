/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KTunerEditor;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerConfiguration;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013TunerEditor;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xTunerEditor;
import io.github.dsheirer.source.tuner.rtl.r8x.r820t.R820TTunerConfiguration;
import io.github.dsheirer.source.tuner.ui.TunerEditor;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class TunerFactoryDisabledEditorTest
{
    @Test
    void savedRtlSubtypeSelectsItsFrequencyEditorWhileDisabled() throws Exception
    {
        assertEditor(E4KTunerConfiguration.class, E4KTunerEditor.class);
        assertEditor(FC0013TunerConfiguration.class, FC0013TunerEditor.class);
        assertEditor(R820TTunerConfiguration.class, R8xTunerEditor.class);
    }

    private static void assertEditor(Class<? extends TunerConfiguration> configurationClass,
                                     Class<? extends TunerEditor> editorClass) throws Exception
    {
        TunerConfiguration configuration = configurationClass.getConstructor(String.class).newInstance("disabled-rtl");
        TestDiscoveredTuner discoveredTuner = new TestDiscoveredTuner(configuration);
        AtomicReference<TunerEditor> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> reference.set(TunerFactory.getEditor(null, discoveredTuner, null)));
        assertInstanceOf(editorClass, reference.get());
        SwingUtilities.invokeAndWait(reference.get()::dispose);
    }

    private static class TestDiscoveredTuner extends DiscoveredTuner
    {
        private TestDiscoveredTuner(TunerConfiguration configuration)
        {
            setEnabled(false);
            setTunerConfiguration(configuration);
        }

        @Override
        public TunerClass getTunerClass()
        {
            return TunerClass.RTL2832;
        }

        @Override
        public String getId()
        {
            return "disabled-rtl";
        }

        @Override
        public void start()
        {
            //No hardware is available in this test.
        }
    }
}

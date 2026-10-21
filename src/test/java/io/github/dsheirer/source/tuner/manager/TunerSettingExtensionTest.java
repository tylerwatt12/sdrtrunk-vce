/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TunerSettingExtensionTest
{
    @Test
    void oneTypedDefinitionDescribesValidatesStoresAndAppliesANewFamilySetting() throws Exception
    {
        ExtensionConfiguration configuration = new ExtensionConfiguration();
        DiscoveredTuner tuner = new DiscoveredTuner()
        {
            @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
            @Override public String getId() { return "extension-fixture"; }
            @Override public void start() {}
        };
        tuner.setTunerConfiguration(configuration);
        ExtensionController controller = new ExtensionController();
        var setting = TunerSettingDefinitions.setting(ExtensionConfiguration.class, Integer.class,
            "preselector", "Preselector", ExtensionConfiguration::preselector, ExtensionConfiguration::setPreselector,
            (hardware, saved, value) -> ((ExtensionController)hardware).setPreselector(value)).range(0,7,1,null);

        var descriptors = TunerSettingCatalog.describe(tuner, List.of(setting));
        assertEquals(1, descriptors.size());
        var descriptor = descriptors.getFirst();
        assertEquals("preselector", descriptor.id());
        assertEquals("integer", descriptor.kind());
        assertEquals("hardware", descriptor.group());
        assertTrue(descriptor.editable());
        assertTrue(ApiHttpResponse.normalizePayload(descriptors).toString().contains("preselector"));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(tuner, setting, 8, false));
        assertThrows(IllegalArgumentException.class, () -> TunerSettingCatalog.validate(tuner, setting, 1.5, false));
        Object accepted = TunerSettingCatalog.validate(tuner, setting, 4, false);
        setting.write(configuration, accepted);
        setting.apply(controller, configuration);
        assertEquals(4, setting.read(configuration));
        assertEquals(4, controller.preselector);
        assertEquals(1, controller.writes);
        assertEquals(4, TunerSettingCatalog.describe(tuner, List.of(setting)).getFirst().value());
    }

    private static final class ExtensionConfiguration extends TunerConfiguration
    {
        private int preselector;
        private ExtensionConfiguration() { super(0,0); }
        @Override public TunerType getTunerType() { return TunerType.TEST; }
        private int preselector() { return preselector; }
        private void setPreselector(int value) { preselector = value; }
    }

    private static final class ExtensionController extends TestTunerController
    {
        private int preselector;
        private int writes;
        private void setPreselector(int value) { preselector = value; writes++; }
    }
}

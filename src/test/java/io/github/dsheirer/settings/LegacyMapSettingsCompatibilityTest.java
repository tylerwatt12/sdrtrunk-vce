/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.map.DefaultIcon;
import io.github.dsheirer.map.MapIcon;
import org.junit.jupiter.api.Test;

/** Legacy UI settings remain readable even though their Swing map editor has been retired. */
class LegacyMapSettingsCompatibilityTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void readsAndRoundTripsPersistedMapViewAndIconSubtypes() throws Exception
    {
        String legacyJson = """
            {"settings":[
              {"type":"mapViewSetting","name":"Default","latitude":41.5,"longitude":-81.7,"zoom":8},
              {"type":"mapIcon","name":"Police","path":"images/police.png"},
              {"type":"defaultIcon","name":"No Icon"}
            ]}
            """;

        Settings loaded = MAPPER.readValue(legacyJson, Settings.class);
        MapViewSetting view = assertInstanceOf(MapViewSetting.class, loaded.getSettings().get(0));
        assertEquals("Default", view.getName());
        assertEquals(41.5, view.getLatitude());
        assertEquals(-81.7, view.getLongitude());
        assertEquals(8, view.getZoom());
        MapIcon icon = assertInstanceOf(MapIcon.class, loaded.getSettings().get(1));
        assertEquals("Police", icon.getName());
        assertEquals("images/police.png", icon.getPath());
        assertEquals("No Icon", assertInstanceOf(DefaultIcon.class, loaded.getSettings().get(2)).getName());

        Settings savedAgain = MAPPER.readValue(MAPPER.writeValueAsString(loaded), Settings.class);
        assertEquals(3, savedAgain.getSettings().size());
        assertEquals("images/police.png", assertInstanceOf(MapIcon.class, savedAgain.getSettings().get(1)).getPath());
        assertEquals(8, assertInstanceOf(MapViewSetting.class, savedAgain.getSettings().get(0)).getZoom());
        assertInstanceOf(DefaultIcon.class, savedAgain.getSettings().get(2));
    }
}
